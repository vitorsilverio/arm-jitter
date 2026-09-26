package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica da permutação SVE não predicada (B17.10), transcrita de `sve_helper.c`/`translate-sve.c` do QEMU.
///
/// Toda permutação só MOVE bytes, então o executor trabalha sobre `byte[]` little-endian de `VL/8` bytes e um tamanho
/// de elemento em bytes — o que dá, sem código extra, as três granularidades que convivem no grupo (Achado 1 da
/// spec): o vetor INTEIRO (`ZIP1`, elemento `esz`), o segmento de 128 bits COMO elemento (`ZIP1_Q`, elemento de 16
/// bytes) e a permutação DENTRO de cada segmento (`ZIPQ1`, o mesmo algoritmo com operando de 16 bytes). Em
/// `VL = 128` as três coincidem. Nunca há constante `128` no laço: tudo lê o `VL` efetivo do core (G6).
final class SvePermuteOps {
    private static final int SEGMENT_BYTES = 16;
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int ZERO_REGISTER_ENCODING = 31;
    private static final int Z_REGISTER_MASK = 0b11111;
    private static final int MIN_SEGMENT_INTERLEAVE_BYTES = 32;
    private static final int PREDICATE_WORD_SHIFT = 6;
    private static final int PREDICATE_BIT_MASK = 63;

    private SvePermuteOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SvePermute op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int vl = core.vectorLengthBytes();
        switch (op.op()) {
            case EXT -> store(regs, op.rd(), extract(load(regs, op.rd(), vl), load(regs, op.rm(), vl), op.imm(), vl));
            case EXT_SVE2 -> store(regs, op.rd(), extract(load(regs, op.rn(), vl),
                    load(regs, (op.rn() + 1) & Z_REGISTER_MASK, vl), op.imm(), vl));
            case EXTQ -> store(regs, op.rd(), extractPerSegment(load(regs, op.rd(), vl), load(regs, op.rm(), vl),
                    op.imm(), vl));
            case DUP_S -> broadcast(core, regs, op, vl);
            case DUP_X -> store(regs, op.rd(), duplicateElement(load(regs, op.rn(), vl), op.esz(), op.imm(), vl));
            case DUPQ -> store(regs, op.rd(), duplicatePerSegment(load(regs, op.rn(), vl), op.esz(), op.imm(), vl));
            case INSR_F -> insert(regs, op, regs.zWord(op.rm(), 0), vl);
            case INSR_R -> insert(regs, op, op.rm() == ZERO_REGISTER_ENCODING ? 0L : core.x(op.rm()), vl);
            case REV -> store(regs, op.rd(), reverse(load(regs, op.rn(), vl), 1 << op.esz()));
            case PMOV_PV -> moveToPredicate(regs, op, vl);
            case PMOV_VP -> moveToVector(regs, op, vl);
            case TBL -> store(regs, op.rd(), lookup(load(regs, op.rn(), vl), null, load(regs, op.rm(), vl), null,
                    1 << op.esz()));
            case TBL_SVE2 -> store(regs, op.rd(), lookup(load(regs, op.rn(), vl),
                    load(regs, (op.rn() + 1) & Z_REGISTER_MASK, vl), load(regs, op.rm(), vl), null, 1 << op.esz()));
            case TBX -> store(regs, op.rd(), lookup(load(regs, op.rn(), vl), null, load(regs, op.rm(), vl),
                    load(regs, op.rd(), vl), 1 << op.esz()));
            case TBLQ -> store(regs, op.rd(), lookupPerSegment(load(regs, op.rn(), vl), load(regs, op.rm(), vl),
                    null, 1 << op.esz()));
            case TBXQ -> store(regs, op.rd(), lookupPerSegment(load(regs, op.rn(), vl), load(regs, op.rm(), vl),
                    load(regs, op.rd(), vl), 1 << op.esz()));
            case SUNPKLO, SUNPKHI, UUNPKLO, UUNPKHI -> store(regs, op.rd(), unpack(load(regs, op.rn(), vl), op, vl));
            default -> interleave(core, regs, op, vl);
        }
        return false;
    }

    // ── Estado ↔ bytes ───────────────────────────────────────────────────────────────────────────

    private static byte[] load(Aarch64ScalableRegisters regs, int reg, int vl) {
        byte[] bytes = new byte[vl];
        for (int i = 0; i < vl; i++) {
            bytes[i] = (byte) (regs.zWord(reg, i / Long.BYTES) >>> (Byte.SIZE * (i % Long.BYTES)));
        }
        return bytes;
    }

    private static void store(Aarch64ScalableRegisters regs, int reg, byte[] bytes) {
        for (int w = 0; w * Long.BYTES < bytes.length; w++) {
            long word = 0;
            for (int b = 0; b < Long.BYTES; b++) {
                word |= (bytes[w * Long.BYTES + b] & 0xFFL) << (Byte.SIZE * b);
            }
            regs.setZWord(reg, w, word);
        }
    }

    private static void copy(byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        System.arraycopy(source, sourceOffset, target, targetOffset, length);
    }

    private static byte[] littleEndian(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (value >>> (Byte.SIZE * i));
        }
        return bytes;
    }

    // ── EXT / DUP / INSR / REV ───────────────────────────────────────────────────────────────────

    /// `EXT`: `Zd = (hi:lo) >> (imm × 8)`, com `imm >= VL` valendo `0` (nem módulo, nem `UNDEFINED` — `do_EXT`).
    private static byte[] extract(byte[] lo, byte[] hi, int imm, int vl) {
        int offset = imm >= vl ? 0 : imm;
        byte[] result = new byte[vl];
        copy(lo, offset, result, 0, vl - offset);
        copy(hi, 0, result, vl - offset, offset);
        return result;
    }

    /// `EXTQ`: o mesmo, dentro de cada segmento de 128 bits (`imm` já cabe em 4 bits).
    private static byte[] extractPerSegment(byte[] lo, byte[] hi, int imm, int vl) {
        byte[] result = new byte[vl];
        for (int base = 0; base < vl; base += SEGMENT_BYTES) {
            copy(lo, base + imm, result, base, SEGMENT_BYTES - imm);
            copy(hi, base, result, base + SEGMENT_BYTES - imm, imm);
        }
        return result;
    }

    private static void broadcast(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SvePermute op, int vl) {
        long value = op.rn() == STACK_POINTER_ENCODING ? core.sp() : core.x(op.rn());
        int size = 1 << op.esz();
        byte[] element = littleEndian(value, size);
        byte[] result = new byte[vl];
        for (int offset = 0; offset < vl; offset += size) {
            copy(element, 0, result, offset, size);
        }
        store(regs, op.rd(), result);
    }

    /// `DUP Zd.T, Zn.T[index]`: um índice fora do vetor zera o resultado (`(index << esz) < vsz` do QEMU).
    private static byte[] duplicateElement(byte[] source, int esz, int index, int vl) {
        int size = 1 << esz;
        byte[] result = new byte[vl];
        if (((long) index << esz) < vl) {
            for (int offset = 0; offset < vl; offset += size) {
                copy(source, index * size, result, offset, size);
            }
        }
        return result;
    }

    /// `DUPQ`: difunde o elemento `index` de CADA segmento de 128 bits dentro do próprio segmento.
    private static byte[] duplicatePerSegment(byte[] source, int esz, int index, int vl) {
        int size = 1 << esz;
        byte[] result = new byte[vl];
        for (int base = 0; base < vl; base += SEGMENT_BYTES) {
            for (int offset = 0; offset < SEGMENT_BYTES; offset += size) {
                copy(source, base + index * size, result, base + offset, size);
            }
        }
        return result;
    }

    /// `INSR`: desloca o vetor inteiro um elemento para cima e escreve o escalar (truncado) no elemento 0.
    private static void insert(Aarch64ScalableRegisters regs, Ir64Op.SvePermute op, long value, int vl) {
        int size = 1 << op.esz();
        byte[] source = load(regs, op.rd(), vl);
        byte[] result = new byte[vl];
        copy(source, 0, result, size, vl - size);
        copy(littleEndian(value, size), 0, result, 0, size);
        store(regs, op.rd(), result);
    }

    /// `REV`: inverte a ORDEM DOS ELEMENTOS do vetor inteiro (não os bytes dentro do elemento — isso é `REVB`, B17.11).
    private static byte[] reverse(byte[] source, int size) {
        byte[] result = new byte[source.length];
        for (int offset = 0; offset < source.length; offset += size) {
            copy(source, offset, result, source.length - size - offset, size);
        }
        return result;
    }

    // ── TBL / TBX ────────────────────────────────────────────────────────────────────────────────

    /// `TBL`/`TBX`: `Zd[i] = table[Zm[i]]`. Índice fora da tabela vale ZERO no `TBL` e PRESERVA o destino no `TBX`
    /// (`preserved != null`). A tabela é 1 vetor, ou 2 quando `table1 != null` (`TBL_sve2`).
    private static byte[] lookup(byte[] table0, byte[] table1, byte[] indexes, byte[] preserved, int size) {
        int elements = table0.length / size;
        byte[] result = preserved != null ? preserved.clone() : new byte[table0.length];
        for (int i = 0; i < elements; i++) {
            long index = elementValue(indexes, i * size, size);
            boolean inFirst = Long.compareUnsigned(index, elements) < 0;
            boolean inSecond = table1 != null && !inFirst && Long.compareUnsigned(index - elements, elements) < 0;
            if (inFirst) {
                copy(table0, (int) index * size, result, i * size, size);
            } else if (inSecond) {
                copy(table1, (int) (index - elements) * size, result, i * size, size);
            } else if (preserved == null) {
                java.util.Arrays.fill(result, i * size, (i + 1) * size, (byte) 0);
            }
        }
        return result;
    }

    /// `TBLQ`/`TBXQ`: a tabela é o segmento de 128 bits correspondente, não o vetor.
    private static byte[] lookupPerSegment(byte[] table, byte[] indexes, byte[] preserved, int size) {
        byte[] result = new byte[table.length];
        for (int base = 0; base < table.length; base += SEGMENT_BYTES) {
            byte[] segment = lookup(slice(table, base), null, slice(indexes, base),
                    preserved == null ? null : slice(preserved, base), size);
            copy(segment, 0, result, base, SEGMENT_BYTES);
        }
        return result;
    }

    private static byte[] slice(byte[] source, int base) {
        byte[] segment = new byte[SEGMENT_BYTES];
        copy(source, base, segment, 0, SEGMENT_BYTES);
        return segment;
    }

    private static long elementValue(byte[] bytes, int offset, int size) {
        long value = 0;
        for (int b = 0; b < size; b++) {
            value |= (bytes[offset + b] & 0xFFL) << (Byte.SIZE * b);
        }
        return value;
    }

    // ── UNPK ─────────────────────────────────────────────────────────────────────────────────────

    /// `SUNPK*`/`UUNPK*`: estende os elementos da metade baixa (ou alta) do vetor fonte para o DOBRO do tamanho.
    private static byte[] unpack(byte[] source, Ir64Op.SvePermute op, int vl) {
        int size = 1 << op.esz();
        int sourceSize = size / 2;
        boolean signed = op.op() == Ir64Op.SvePermute.Op.SUNPKLO || op.op() == Ir64Op.SvePermute.Op.SUNPKHI;
        boolean high = op.op() == Ir64Op.SvePermute.Op.SUNPKHI || op.op() == Ir64Op.SvePermute.Op.UUNPKHI;
        int base = high ? vl / 2 : 0;
        byte[] result = new byte[vl];
        for (int i = 0; i * size < vl; i++) {
            long value = elementValue(source, base + i * sourceSize, sourceSize);
            if (signed) {
                int shift = Long.SIZE - Byte.SIZE * sourceSize;
                value = (value << shift) >> shift;
            }
            copy(littleEndian(value, size), 0, result, i * size, size);
        }
        return result;
    }

    // ── PMOV ─────────────────────────────────────────────────────────────────────────────────────

    /// `PMOV Pd.T, Zn[imm]`: o bit 0 de cada elemento da fatia `imm` do vetor vira o bit de predicado do elemento `e`
    /// correspondente (posição `e × esize`), e o resto de `Pd` é zerado. `elements = VL / esize` (em bytes).
    private static void moveToPredicate(Aarch64ScalableRegisters regs, Ir64Op.SvePermute op, int vl) {
        int size = 1 << op.esz();
        int elements = vl / size;
        long[] predicate = new long[regs.wordsPerPredicate()];
        for (int e = 0; e < elements; e++) {
            int vectorBit = elements * op.imm() + e;
            long bit = (regs.zWord(op.rn(), vectorBit >>> PREDICATE_WORD_SHIFT) >>> (vectorBit & PREDICATE_BIT_MASK)) & 1L;
            int predicateBit = e * size;
            predicate[predicateBit >>> PREDICATE_WORD_SHIFT] |= bit << (predicateBit & PREDICATE_BIT_MASK);
        }
        for (int w = 0; w < predicate.length; w++) {
            regs.setPWord(op.rd(), w, predicate[w]);
        }
    }

    /// `PMOV Zd[imm], Pn.T`: o inverso. Só a fatia `imm` é reescrita, e `imm = 0` zera antes o vetor inteiro
    /// (`DO_PMOV_VP`) — é assim que a forma de byte (única fatia) limpa o resto de `Zd`.
    private static void moveToVector(Aarch64ScalableRegisters regs, Ir64Op.SvePermute op, int vl) {
        int size = 1 << op.esz();
        int elements = vl / size;
        long[] vector = new long[vl / Long.BYTES];
        if (op.imm() != 0) {
            for (int w = 0; w < vector.length; w++) {
                vector[w] = regs.zWord(op.rd(), w);
            }
        }
        for (int e = 0; e < elements; e++) {
            int predicateBit = e * size;
            long bit = (regs.pWord(op.rn(), predicateBit >>> PREDICATE_WORD_SHIFT)
                    >>> (predicateBit & PREDICATE_BIT_MASK)) & 1L;
            int vectorBit = elements * op.imm() + e;
            int word = vectorBit >>> PREDICATE_WORD_SHIFT;
            long mask = 1L << (vectorBit & PREDICATE_BIT_MASK);
            vector[word] = bit != 0 ? vector[word] | mask : vector[word] & ~mask;
        }
        for (int w = 0; w < vector.length; w++) {
            regs.setZWord(op.rd(), w, vector[w]);
        }
    }

    // ── ZIP / UZP / TRN ──────────────────────────────────────────────────────────────────────────

    private enum Family { ZIP, UZP, TRN }

    private static Family family(Ir64Op.SvePermute.Op kind) {
        return switch (kind) {
            case ZIP1, ZIP2, ZIP1_Q, ZIP2_Q, ZIPQ1, ZIPQ2 -> Family.ZIP;
            case UZP1, UZP2, UZP1_Q, UZP2_Q, UZPQ1, UZPQ2 -> Family.UZP;
            default -> Family.TRN;
        };
    }

    /// `true` nas formas `2` (elementos ímpares / metade alta).
    private static boolean second(Ir64Op.SvePermute.Op kind) {
        return switch (kind) {
            case ZIP2, ZIP2_Q, ZIPQ2, UZP2, UZP2_Q, UZPQ2, TRN2, TRN2_Q -> true;
            default -> false;
        };
    }

    private static boolean segmentAsElement(Ir64Op.SvePermute.Op kind) {
        return switch (kind) {
            case ZIP1_Q, ZIP2_Q, UZP1_Q, UZP2_Q, TRN1_Q, TRN2_Q -> true;
            default -> false;
        };
    }

    private static boolean perSegment(Ir64Op.SvePermute.Op kind) {
        return kind == Ir64Op.SvePermute.Op.ZIPQ1 || kind == Ir64Op.SvePermute.Op.ZIPQ2
                || kind == Ir64Op.SvePermute.Op.UZPQ1 || kind == Ir64Op.SvePermute.Op.UZPQ2;
    }

    private static void interleave(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SvePermute op, int vl) {
        Ir64Op.SvePermute.Op kind = op.op();
        boolean segmentElement = segmentAsElement(kind);
        if (segmentElement) {
            // As formas `_q` não existem em modo streaming (sem `FEAT_SME_FA64`) e exigem `VL >= 256`.
            SvePredicateOps.requireNonStreaming(core);
            if (vl < MIN_SEGMENT_INTERLEAVE_BYTES) {
                throw new Aarch64UndefinedInstructionException();
            }
        }
        byte[] n = load(regs, op.rn(), vl);
        byte[] m = load(regs, op.rm(), vl);
        Family family = family(kind);
        boolean second = second(kind);
        if (!perSegment(kind)) {
            store(regs, op.rd(), shuffle(family, second, n, m, segmentElement ? SEGMENT_BYTES : 1 << op.esz(), vl,
                    segmentElement));
            return;
        }
        // `ZIPQ*`/`UZPQ*`: o algoritmo do vetor inteiro aplicado a cada segmento de 128 bits.
        byte[] result = new byte[vl];
        for (int base = 0; base < vl; base += SEGMENT_BYTES) {
            byte[] part = shuffle(family, second, slice(n, base), slice(m, base), 1 << op.esz(), SEGMENT_BYTES,
                    false);
            copy(part, 0, result, base, SEGMENT_BYTES);
        }
        store(regs, op.rd(), result);
    }

    /// `do_zip`/`do_uzp`/`do_trn` do QEMU sobre um operando de `length` bytes com elementos de `size` bytes. As
    /// formas `_q` deixam ZERO o último segmento quando o número de segmentos é ímpar (`oprsz & 16`) — o `break` dos
    /// laços abaixo, que o QEMU obtém escrevendo além e zerando depois.
    private static byte[] shuffle(Family family, boolean second, byte[] n, byte[] m, int size, int length,
            boolean segmentElement) {
        byte[] d = new byte[length];
        switch (family) {
            case ZIP -> {
                int sourceOffset = !second ? 0
                        : segmentElement ? alignDown(length, MIN_SEGMENT_INTERLEAVE_BYTES) / 2 : length / 2;
                for (int i = 0; i < length / 2 && 2 * (i + size) <= length; i += size) {
                    copy(n, sourceOffset + i, d, 2 * i, size);
                    copy(m, sourceOffset + i, d, 2 * i + size, size);
                }
            }
            case UZP -> {
                int i = 0;
                int p = second ? size : 0;
                for (; p < length; p += 2 * size, i += size) {
                    copy(n, p, d, i, size);
                }
                for (p -= length; p < length; p += 2 * size, i += size) {
                    copy(m, p, d, i, size);
                }
            }
            default -> {
                int sourceOffset = second ? size : 0;
                for (int i = 0; i + 2 * size <= length; i += 2 * size) {
                    copy(n, i + sourceOffset, d, i, size);
                    copy(m, i + sourceOffset, d, i + size, size);
                }
            }
        }
        return d;
    }

    private static int alignDown(int value, int alignment) {
        return value - value % alignment;
    }
}
