package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica da permutação de predicado, da permutação predicada e do `SEL` do SVE (B17.11), transcrita de
/// `sve_helper.c`/`translate-sve.c` do QEMU (`curl` para arquivo local, verbatim).
///
/// Três armadilhas que o QEMU deixa explícitas e que aqui viram testes:
///
/// - **`CLAST` ≠ `LAST`**: com predicado vazio, `LASTA`/`LASTB` extraem o elemento `0`/o ÚLTIMO (o `-1` do "não achou"
///   dá a volta), mas `CLASTA`/`CLASTB` deixam o destino como estava — e nas formas `_v`/`_r` o valor anterior do
///   destino tem que ser LIDO antes de escrever;
/// - a permutação de predicado copia GRUPOS INTEIROS de `1 << esz` bits (não só o bit menos significativo do
///   elemento), como o `expand_bits`/`compress_bits` do QEMU e o `Elem[..., esize DIV 8]` do pseudocódigo;
/// - o `COMPACT`/`EXPAND` percorrem o vetor INTEIRO com um contador só (não reiniciam por segmento de 128 bits).
///
/// Os elementos são `long[]` zero-estendidos; o executor não tem constante de `VL` — tudo lê o `VL` efetivo do core.
final class SvePermutePredicatedOps {
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int Z_REGISTER_MASK = 0b11111;
    private static final int QUADWORD_BYTES = 16;
    private static final int UNIT_BYTE = 1;
    private static final int UNIT_HALFWORD = 2;
    private static final int UNIT_WORD = 4;
    private static final int NOT_FOUND = -1;
    private static final int ESZ_DOUBLEWORD = 3;

    private SvePermutePredicatedOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SvePermutePredicated op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int vl = core.vectorLengthBytes();
        switch (op.op()) {
            case ZIP1_P, ZIP2_P, UZP1_P, UZP2_P, TRN1_P, TRN2_P, REV_P, PUNPKLO, PUNPKHI ->
                    permutePredicate(regs, op, vl);
            case COMPACT -> {
                SvePredicateOps.requireNonStreaming(core);
                compact(regs, op, vl);
            }
            case EXPAND -> {
                SvePredicateOps.requireNonStreaming(core);
                expand(regs, op, vl);
            }
            case SPLICE -> splice(regs, op, op.rd(), op.rm(), vl);
            case SPLICE_SVE2 -> splice(regs, op, op.rn(), (op.rn() + 1) & Z_REGISTER_MASK, vl);
            case SEL -> select(regs, op, vl);
            case CLASTA_Z, CLASTB_Z -> conditionalBroadcast(regs, op, vl);
            case CLASTA_V, CLASTB_V, CLASTA_R, CLASTB_R, LASTA_V, LASTB_V, LASTA_R, LASTB_R ->
                    extractLast(core, regs, op, vl);
            case CPY_M_V, CPY_M_R -> copyMerging(core, regs, op, vl);
            case REVD_M, REVD_Z -> reverseDoublewords(regs, op, vl);
            default -> reverseWithin(regs, op, vl);
        }
        return false;
    }

    // ── Estado ↔ elementos ───────────────────────────────────────────────────────────────────────

    private static long[] elements(Aarch64ScalableRegisters regs, int reg, int esz, int vl) {
        long[] out = new long[vl >> esz];
        for (int i = 0; i < out.length; i++) {
            out[i] = SvePredicateOps.elementOf(regs, reg, i, esz);
        }
        return out;
    }

    private static void store(Aarch64ScalableRegisters regs, int reg, int esz, long[] values) {
        for (int i = 0; i < values.length; i++) {
            SvePredicateOps.setElementOf(regs, reg, i, esz, values[i]);
        }
    }

    private static boolean active(long[] predicate, int element, int esz) {
        return SvePredicateOps.bit(predicate, element << esz);
    }

    /// Escrita de escalar SIMD&FP (`write_fp_dreg`): o valor vai para os 64 bits baixos e o RESTO de `Z<reg>` é zerado.
    private static void writeScalar(Aarch64ScalableRegisters regs, int reg, long value) {
        regs.setZWord(reg, 0, value);
        for (int w = 1; w < regs.wordsPerVector(); w++) {
            regs.setZWord(reg, w, 0L);
        }
    }

    private static long mask(int esz) {
        return esz == ESZ_DOUBLEWORD ? -1L : (1L << (Byte.SIZE << esz)) - 1L;
    }

    // ── Permutação de predicado ──────────────────────────────────────────────────────────────────

    /// Copia o grupo de `width` bits `from` de `source` para o grupo `to` de `target`.
    private static void copyGroup(long[] source, int from, long[] target, int to, int width) {
        for (int b = 0; b < width; b++) {
            SvePredicateOps.setBit(target, to * width + b, SvePredicateOps.bit(source, from * width + b));
        }
    }

    private static void permutePredicate(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        long[] n = SvePredicateOps.read(regs, op.rn());
        long[] d = new long[n.length];
        int width = 1 << op.esz();
        int groups = vl / width;
        int half = groups / 2;
        switch (op.op()) {
            case ZIP1_P, ZIP2_P -> {
                long[] m = SvePredicateOps.read(regs, op.rm());
                int base = op.op() == Ir64Op.SvePermutePredicated.Op.ZIP2_P ? half : 0;
                for (int p = 0; p < half; p++) {
                    copyGroup(n, base + p, d, 2 * p, width);
                    copyGroup(m, base + p, d, 2 * p + 1, width);
                }
            }
            case UZP1_P, UZP2_P -> {
                long[] m = SvePredicateOps.read(regs, op.rm());
                int odd = op.op() == Ir64Op.SvePermutePredicated.Op.UZP2_P ? 1 : 0;
                for (int i = 0; i < half; i++) {
                    copyGroup(n, 2 * i + odd, d, i, width);
                    copyGroup(m, 2 * i + odd, d, half + i, width);
                }
            }
            case TRN1_P, TRN2_P -> {
                long[] m = SvePredicateOps.read(regs, op.rm());
                int odd = op.op() == Ir64Op.SvePermutePredicated.Op.TRN2_P ? 1 : 0;
                for (int p = 0; p < half; p++) {
                    copyGroup(n, 2 * p + odd, d, 2 * p, width);
                    copyGroup(m, 2 * p + odd, d, 2 * p + 1, width);
                }
            }
            case REV_P -> {
                for (int i = 0; i < groups; i++) {
                    copyGroup(n, groups - 1 - i, d, i, width);
                }
            }
            default -> {
                // PUNPKLO/PUNPKHI: cada bit da metade escolhida vira um elemento de 2 bits (o bit alto fica 0).
                int base = op.op() == Ir64Op.SvePermutePredicated.Op.PUNPKHI ? vl / 2 : 0;
                for (int i = 0; i < vl / 2; i++) {
                    SvePredicateOps.setBit(d, 2 * i, SvePredicateOps.bit(n, base + i));
                }
            }
        }
        SvePredicateOps.write(regs, op.rd(), d);
    }

    // ── COMPACT / EXPAND / SPLICE / SEL ──────────────────────────────────────────────────────────

    /// `COMPACT`: empacota os elementos ativos no início do vetor e ZERA o resto.
    private static void compact(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        long[] source = elements(regs, op.rn(), op.esz(), vl);
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long[] out = new long[source.length];
        int j = 0;
        for (int e = 0; e < source.length; e++) {
            if (active(predicate, e, op.esz())) {
                out[j++] = source[e];
            }
        }
        store(regs, op.rd(), op.esz(), out);
    }

    /// `EXPAND`: o inverso — espalha os elementos contíguos de `Zn` pelas posições ativas; as inativas ficam ZERO.
    private static void expand(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        long[] source = elements(regs, op.rn(), op.esz(), vl);
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long[] out = new long[source.length];
        int j = 0;
        for (int e = 0; e < source.length; e++) {
            if (active(predicate, e, op.esz())) {
                out[e] = source[j++];
            }
        }
        store(regs, op.rd(), op.esz(), out);
    }

    /// `SPLICE`: copia o trecho de `first` (primeiro ativo) até `last` (último ativo) de `low` para o início do
    /// resultado e completa com o começo de `high`. Sem elemento ativo, o resultado é `high` inteiro.
    private static void splice(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int lowReg,
            int highReg, int vl) {
        long[] low = elements(regs, lowReg, op.esz(), vl);
        long[] high = elements(regs, highReg, op.esz(), vl);
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        int first = NOT_FOUND;
        int last = NOT_FOUND;
        for (int e = 0; e < low.length; e++) {
            if (active(predicate, e, op.esz())) {
                if (first == NOT_FOUND) {
                    first = e;
                }
                last = e;
            }
        }
        int length = first == NOT_FOUND ? 0 : last - first + 1;
        long[] out = new long[low.length];
        System.arraycopy(low, Math.max(first, 0), out, 0, length);
        System.arraycopy(high, 0, out, length, low.length - length);
        store(regs, op.rd(), op.esz(), out);
    }

    private static void select(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        long[] n = elements(regs, op.rn(), op.esz(), vl);
        long[] m = elements(regs, op.rm(), op.esz(), vl);
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        for (int e = 0; e < n.length; e++) {
            if (!active(predicate, e, op.esz())) {
                n[e] = m[e];
            }
        }
        store(regs, op.rd(), op.esz(), n);
    }

    // ── LAST* / CLAST* ───────────────────────────────────────────────────────────────────────────

    /// `LastActiveElement`: índice do último elemento ativo, ou `-1`.
    private static int lastActive(long[] predicate, int esz, int elements) {
        for (int e = elements - 1; e >= 0; e--) {
            if (active(predicate, e, esz)) {
                return e;
            }
        }
        return NOT_FOUND;
    }

    private static boolean isAfter(Ir64Op.SvePermutePredicated.Op kind) {
        return switch (kind) {
            case CLASTA_Z, CLASTA_V, CLASTA_R, LASTA_V, LASTA_R -> true;
            default -> false;
        };
    }

    private static boolean isVectorDestination(Ir64Op.SvePermutePredicated.Op kind) {
        return switch (kind) {
            case CLASTA_V, CLASTB_V, LASTA_V, LASTB_V -> true;
            default -> false;
        };
    }

    private static boolean isConditional(Ir64Op.SvePermutePredicated.Op kind) {
        return switch (kind) {
            case CLASTA_Z, CLASTB_Z, CLASTA_V, CLASTB_V, CLASTA_R, CLASTB_R -> true;
            default -> false;
        };
    }

    /// Índice do elemento a extrair, ou `-1` quando `CLAST*` não tem nada a copiar. `LASTA` dá a volta para `0` e
    /// `LASTB` (com predicado vazio) vale o ÚLTIMO elemento do vetor.
    private static int chooseIndex(Ir64Op.SvePermutePredicated.Op kind, int last, int elements) {
        if (isConditional(kind) && last == NOT_FOUND) {
            return NOT_FOUND;
        }
        if (isAfter(kind)) {
            return last + 1 >= elements ? 0 : last + 1;
        }
        return last == NOT_FOUND ? elements - 1 : last;
    }

    private static void conditionalBroadcast(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        int elements = vl >> op.esz();
        int index = chooseIndex(op.op(), lastActive(SvePredicateOps.read(regs, op.pg()), op.esz(), elements),
                elements);
        if (index == NOT_FOUND) {
            return; // predicado vazio: Zdn inalterado
        }
        long value = SvePredicateOps.elementOf(regs, op.rm(), index, op.esz());
        long[] out = new long[elements];
        java.util.Arrays.fill(out, value);
        store(regs, op.rd(), op.esz(), out);
    }

    private static void extractLast(Aarch64Core core, Aarch64ScalableRegisters regs,
            Ir64Op.SvePermutePredicated op, int vl) {
        int elements = vl >> op.esz();
        int index = chooseIndex(op.op(), lastActive(SvePredicateOps.read(regs, op.pg()), op.esz(), elements),
                elements);
        boolean vector = isVectorDestination(op.op());
        // O valor anterior do destino é lido ANTES de escrever: é ele que sobrevive quando o predicado é vazio.
        long previous = vector ? SvePredicateOps.elementOf(regs, op.rd(), 0, op.esz())
                : core.x(op.rd()) & mask(op.esz());
        long value = index == NOT_FOUND ? previous : SvePredicateOps.elementOf(regs, op.rn(), index, op.esz());
        if (vector) {
            writeScalar(regs, op.rd(), value);
        } else {
            core.setX(op.rd(), value);
        }
    }

    /// `CPY` merging: copia o escalar (truncado ao elemento) só para os elementos ativos e preserva os demais.
    private static void copyMerging(Aarch64Core core, Aarch64ScalableRegisters regs,
            Ir64Op.SvePermutePredicated op, int vl) {
        long value = op.op() == Ir64Op.SvePermutePredicated.Op.CPY_M_R
                ? (op.rn() == STACK_POINTER_ENCODING ? core.sp() : core.x(op.rn()))
                : SvePredicateOps.elementOf(regs, op.rn(), 0, op.esz());
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        for (int e = 0; e < vl >> op.esz(); e++) {
            if (active(predicate, e, op.esz())) {
                SvePredicateOps.setElementOf(regs, op.rd(), e, op.esz(), value & mask(op.esz()));
            }
        }
    }

    // ── REVB / REVH / REVW / RBIT / REVD ─────────────────────────────────────────────────────────

    private static boolean zeroing(Ir64Op.SvePermutePredicated.Op kind) {
        return switch (kind) {
            case REVB_Z, REVH_Z, REVW_Z, RBIT_Z, REVD_Z -> true;
            default -> false;
        };
    }

    /// Inverte a ordem de `unit` bytes dentro de um elemento de `size` bytes.
    private static long reverseUnits(long value, int size, int unit) {
        int units = size / unit;
        long unitMask = (1L << (Byte.SIZE * unit)) - 1L;
        long out = 0;
        for (int k = 0; k < units; k++) {
            out |= ((value >>> (Byte.SIZE * unit * k)) & unitMask) << (Byte.SIZE * unit * (units - 1 - k));
        }
        return out;
    }

    private static void reverseWithin(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        int esz = op.esz();
        int size = 1 << esz;
        long[] source = elements(regs, op.rn(), esz, vl);
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        boolean zero = zeroing(op.op());
        long[] out = zero ? new long[source.length] : elements(regs, op.rd(), esz, vl);
        for (int e = 0; e < source.length; e++) {
            if (!active(predicate, e, esz)) {
                continue;
            }
            out[e] = switch (op.op()) {
                case REVB_M, REVB_Z -> reverseUnits(source[e], size, UNIT_BYTE);
                case REVH_M, REVH_Z -> reverseUnits(source[e], size, UNIT_HALFWORD);
                case REVW_M, REVW_Z -> reverseUnits(source[e], size, UNIT_WORD);
                default -> Long.reverse(source[e]) >>> (Long.SIZE - Byte.SIZE * size);
            };
        }
        store(regs, op.rd(), esz, out);
    }

    /// `REVD`: troca as duas doublewords de cada quadword ativo; o predicado do quadword é o bit do PRIMEIRO byte dele.
    private static void reverseDoublewords(Aarch64ScalableRegisters regs, Ir64Op.SvePermutePredicated op, int vl) {
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        boolean zero = zeroing(op.op());
        int words = vl / Long.BYTES;
        long[] source = new long[words];
        long[] old = new long[words];
        for (int w = 0; w < words; w++) {
            source[w] = regs.zWord(op.rn(), w);
            old[w] = regs.zWord(op.rd(), w);
        }
        for (int q = 0; q < vl / QUADWORD_BYTES; q++) {
            int low = 2 * q;
            if (SvePredicateOps.bit(predicate, q * QUADWORD_BYTES)) {
                regs.setZWord(op.rd(), low, source[low + 1]);
                regs.setZWord(op.rd(), low + 1, source[low]);
            } else if (zero) {
                regs.setZWord(op.rd(), low, 0L);
                regs.setZWord(op.rd(), low + 1, 0L);
            } else {
                regs.setZWord(op.rd(), low, old[low]);
                regs.setZWord(op.rd(), low + 1, old[low + 1]);
            }
        }
    }
}
