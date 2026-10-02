package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.SvePredicateOp64;

/// Semântica do "resto" do SVE2 (B17.22): `MATCH`/`NMATCH`, `HISTCNT`/`HISTSEG`, `LUTI2`/`LUTI4`, `PSEL` e
/// `SCLAMP`/`UCLAMP`/`FCLAMP`. Toda fórmula foi conferida contra `sve_helper.c`/`vec_helper.c`/`sme_helper.c`/
/// `translate-sve.c` do QEMU (revisão fixada), a parte menos verificada da rodada de spec — ver `## Resultado`
/// da task para as correções encontradas (a mais importante: `HISTCNT`, ao contrário de `MATCH`/`HISTSEG`, NÃO é
/// por segmento de 128 bits — é um histograma prefixo do VETOR INTEIRO).
final class SveMiscOps {
    private static final int SEGMENT_BYTES = 16;
    private static final int TABLE_REGISTER_COUNT = 32;
    /// `LUTI4_1h` (16 entradas × 16 bits = 256 bits de tabela) exige `VL >= 256` — a ÚNICA das 5 formas com essa
    /// restrição em tempo de EXECUÇÃO (`if (vsz < 32) unallocated_encoding` do `trans_LUTI4_1h`, medido contra o
    /// `VL` EFETIVO do core, não contra o encoding). `LUTI4_2h` não tem essa restrição — concatena DOIS
    /// registradores de 128 bits cada, alcançando as 256 bits em qualquer `VL`.
    private static final int LUTI4_1H_MINIMUM_VL_BYTES = 32;
    private static final int LUTI2_INDEX_BITS = 2;
    private static final int LUTI4_INDEX_BITS = 4;
    private static final int BITS_PER_BYTE = 8;
    private static final int WORD_BITS = Long.SIZE;

    private SveMiscOps() {
    }

    // ── MATCH / NMATCH ──────────────────────────────────────────────────────────────────────────

    /// `MATCH`/`NMATCH`: **por segmento de 128 bits** (`do_match` do QEMU reinicia `i` a cada 16 bytes). Para
    /// cada elemento ATIVO de `Zn` (por `Pg`), o bit do predicado é `1` se aquele valor aparece em QUALQUER
    /// elemento do MESMO segmento de `Zm` — `Zm` não é filtrado por predicado nenhum (o segmento inteiro é
    /// varrido). `NMATCH` inverte o resultado (não a máscara de ativos). Sempre seta `NZCV` por `predTest`.
    static boolean executeMatch(Aarch64Core core, SvePredicateOp64.Match op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int vlBytes = core.vectorLengthBytes();
        long[] pg = SvePredicateOps.read(regs, op.pg());
        long[] result = new long[regs.wordsPerPredicate()];
        for (int segment = 0; segment < vlBytes; segment += SEGMENT_BYTES) {
            for (int byteOffset = segment; byteOffset < segment + SEGMENT_BYTES; byteOffset += elementBytes) {
                if (!SvePredicateOps.bit(pg, byteOffset)) {
                    continue;
                }
                long needle = SveIntegerOps.get(regs, op.rn(), byteOffset >> esz, esz);
                boolean found = false;
                for (int k = segment; k < segment + SEGMENT_BYTES; k += elementBytes) {
                    if (SveIntegerOps.get(regs, op.rm(), k >> esz, esz) == needle) {
                        found = true;
                        break;
                    }
                }
                SvePredicateOps.setBit(result, byteOffset, found != op.invert());
            }
        }
        SvePredicateOps.write(regs, op.pd(), result);
        SvePredicateOps.predTest(core, pg, result, esz, SvePredicateOps.predicateBits(core));
        return false;
    }

    // ── HISTCNT / HISTSEG ───────────────────────────────────────────────────────────────────────

    /// `HISTCNT` (`.S`/`.D`, com predicado): histograma prefixo do VETOR INTEIRO — `Zd[i]` = quantos elementos
    /// ATIVOS `j <= i` (também filtrados por `Pg`) têm `Zm[j] == Zn[i]`; elemento inativo de `Zn` grava `0`.
    /// `HISTSEG` (`.B`, sem predicado): por segmento de 128 bits — `Zd[e]` = quantos bytes do MESMO segmento de
    /// `Zm` são iguais a `Zn[e]`. Ambas leem `Zn`/`Zm` de um instantâneo (o QEMU faz o mesmo com `memcpy` quando
    /// `Zd` colide com `Zn`/`Zm`).
    static boolean executeHistogram(Aarch64Core core, SveIntegerOp64.Histogram op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        if (op.counting()) {
            histogramCount(core, regs, op);
        } else {
            histogramSegment(core, regs, op);
        }
        return false;
    }

    private static void histogramCount(Aarch64Core core, Aarch64ScalableRegisters regs, SveIntegerOp64.Histogram op) {
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elements = core.vectorLengthBytes() / elementBytes;
        long[] pg = SvePredicateOps.read(regs, op.pg());
        long[] n = new long[elements];
        long[] m = new long[elements];
        boolean[] active = new boolean[elements];
        for (int e = 0; e < elements; e++) {
            n[e] = SveIntegerOps.get(regs, op.rn(), e, esz);
            m[e] = SveIntegerOps.get(regs, op.rm(), e, esz);
            active[e] = SvePredicateOps.bit(pg, e * elementBytes);
        }
        for (int i = 0; i < elements; i++) {
            long count = 0;
            if (active[i]) {
                for (int j = 0; j <= i; j++) {
                    if (active[j] && m[j] == n[i]) {
                        count++;
                    }
                }
            }
            SveIntegerOps.set(regs, op.rd(), i, esz, count);
        }
    }

    private static void histogramSegment(Aarch64Core core, Aarch64ScalableRegisters regs, SveIntegerOp64.Histogram op) {
        int vlBytes = core.vectorLengthBytes();
        long[] n = new long[vlBytes];
        long[] m = new long[vlBytes];
        for (int i = 0; i < vlBytes; i++) {
            n[i] = SveIntegerOps.get(regs, op.rn(), i, 0);
            m[i] = SveIntegerOps.get(regs, op.rm(), i, 0);
        }
        for (int segment = 0; segment < vlBytes; segment += SEGMENT_BYTES) {
            for (int e = segment; e < segment + SEGMENT_BYTES; e++) {
                long count = 0;
                for (int k = segment; k < segment + SEGMENT_BYTES; k++) {
                    if (m[k] == n[e]) {
                        count++;
                    }
                }
                SveIntegerOps.set(regs, op.rd(), e, 0, count);
            }
        }
    }

    // ── LUTI2 / LUTI4 ───────────────────────────────────────────────────────────────────────────

    /// `LUTI2`/`LUTI4`: `Zn` é a tabela (`2^indexBits` entradas alcançáveis); `Zm` guarda os índices empacotados
    /// a `indexBits` bits por elemento de SAÍDA; `op.index()` seleciona qual GRUPO de índices dentro de `Zm`
    /// (`ibase = elements × index`, `do_lut_b`/`do_lut_h` do QEMU). `LUTI4_1h` (`tableRegisters = 1`, halfword)
    /// exige `VL >= 256` em tempo de execução; `LUTI4_2h` (`tableRegisters = 2`) concatena `Zn` e `Zn+1 mod 32`
    /// (só os 128 bits BAIXOS de cada) e não tem essa restrição.
    static boolean executeLookupTable(Aarch64Core core, SveIntegerOp64.LookupTable op) {
        if (op.four() && op.esz() == SveFloat.ESZ_HALF && op.tableRegisters() == 1
                && core.vectorLengthBytes() < LUTI4_1H_MINIMUM_VL_BYTES) {
            throw new Aarch64UndefinedInstructionException();
        }
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elementBits = BITS_PER_BYTE << esz;
        int elements = core.vectorLengthBytes() * BITS_PER_BYTE / elementBits;
        int indexBits = op.four() ? LUTI4_INDEX_BITS : LUTI2_INDEX_BITS;
        long base = (long) elements * op.index();
        for (int e = 0; e < elements; e++) {
            int index = (int) extractField(regs, op.rm(), (base + e) * indexBits, indexBits);
            long value = tableEntry(regs, op.rn(), op.tableRegisters(), index, elementBits);
            SveIntegerOps.set(regs, op.rd(), e, esz, value);
        }
        return false;
    }

    /// Entrada `index` da tabela: um registrador só (`Zn`, `index × elementBits` direto) ou dois (`Zn`/`Zn+1`,
    /// só os 128 bits baixos de cada — as entradas `0..entriesPerReg-1` vêm de `Zn`, o resto de `Zn+1 mod 32`).
    private static long tableEntry(Aarch64ScalableRegisters regs, int rn, int tableRegisters, int index,
            int elementBits) {
        if (tableRegisters == 1) {
            return extractField(regs, rn, (long) index * elementBits, elementBits);
        }
        int entriesPerReg = (SEGMENT_BYTES * BITS_PER_BYTE) / elementBits;
        int reg = index < entriesPerReg ? rn : (rn + 1) % TABLE_REGISTER_COUNT;
        int local = index < entriesPerReg ? index : index - entriesPerReg;
        return extractField(regs, reg, (long) local * elementBits, elementBits);
    }

    /// Campo de `width` bits (`LUTI2`/`LUTI4`: sempre `<= 16`, nunca `64` — índice de 2/4 bits ou entrada de
    /// tabela de até 16 bits — então nunca cruza a fronteira de 64 bits usada aqui) no deslocamento `bitOffset`
    /// de `Z<reg>`.
    private static long extractField(Aarch64ScalableRegisters regs, int reg, long bitOffset, int width) {
        int wordIndex = (int) (bitOffset >>> 6);
        int bitInWord = (int) (bitOffset & (WORD_BITS - 1));
        long word = regs.zWord(reg, wordIndex);
        return (word >>> bitInWord) & ((1L << width) - 1L);
    }

    // ── PSEL ────────────────────────────────────────────────────────────────────────────────────

    /// `PSEL Pd, Pn, Pm[Wrv, imm]`: **não escreve vetor nenhum**. `Pd = Pm[(Wrv + imm) mod elements] ? Pn : 0` —
    /// o bit TESTADO de `Pm`, no elemento calculado, escolhe entre copiar `Pn` inteiro ou zerar `Pd` inteiro (não
    /// é seleção elemento-a-elemento). `elements = VL >> esz`; `Wrv` já chega resolvido a `W12`-`W15` do decoder.
    static boolean executePredicateSelect(Aarch64Core core, SvePredicateOp64.PredicateSelect op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = core.vectorLengthBytes() >> op.esz();
        long selector = Long.remainderUnsigned(core.x(op.rv()) + op.imm(), elements);
        long[] pm = SvePredicateOps.read(regs, op.pm());
        boolean selected = SvePredicateOps.bit(pm, (int) (selector << op.esz()));
        long[] result = selected ? SvePredicateOps.read(regs, op.pn()) : new long[regs.wordsPerPredicate()];
        SvePredicateOps.write(regs, op.pd(), result);
        return false;
    }

    // ── SCLAMP / UCLAMP / FCLAMP ────────────────────────────────────────────────────────────────

    /// `Zd = min(max(Zd, Zn), Zm)`. `SCLAMP`/`UCLAMP`: inteiro, com/sem sinal (`ICLAMP` do `sme_helper.c`).
    /// `FCLAMP`: `minNum(maxNum(Zn, Zd), Zm)` — a variante NÃO propagadora de NaN (mesma primitiva de
    /// `FMAXNM`/`FMINNM`), ordem dos operandos exatamente como o `FCLAMP` do `sme_helper.c` (comentário do QEMU:
    /// "a ordem dos argumentos deve casar com o pseudocódigo do ARM para propagar NaN corretamente").
    static boolean executeClamp(Aarch64Core core, SveIntegerOp64.Clamp op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        if (op.op() == SveIntegerOp64.Clamp.Op.FCLAMP) {
            SveFloat.Env env = SveFloat.Env.of(core, esz);
            for (int e = 0; e < elements; e++) {
                long n = SveIntegerOps.get(regs, op.rn(), e, esz);
                long d = SveIntegerOps.get(regs, op.rd(), e, esz);
                long m = SveIntegerOps.get(regs, op.rm(), e, esz);
                long clamped = SveFloat.maxMinNumber(SveFloat.maxMinNumber(n, d, true, env), m, false, env);
                SveIntegerOps.set(regs, op.rd(), e, esz, clamped);
            }
            env.commit(core);
            return false;
        }
        boolean unsigned = op.op() == SveIntegerOp64.Clamp.Op.UCLAMP;
        for (int e = 0; e < elements; e++) {
            long acc = elementValue(regs, op.rd(), e, esz, unsigned);
            long n = elementValue(regs, op.rn(), e, esz, unsigned);
            long m = elementValue(regs, op.rm(), e, esz, unsigned);
            long high = unsigned ? maxUnsigned(acc, n) : Math.max(acc, n);
            long clamped = unsigned ? minUnsigned(high, m) : Math.min(high, m);
            SveIntegerOps.set(regs, op.rd(), e, esz, clamped);
        }
        return false;
    }

    private static long elementValue(Aarch64ScalableRegisters regs, int reg, int element, int esz,
            boolean unsigned) {
        long raw = SveIntegerOps.get(regs, reg, element, esz);
        return unsigned ? raw : SveIntegerOps.signExtend(raw, esz);
    }

    private static long maxUnsigned(long a, long b) {
        return Long.compareUnsigned(a, b) >= 0 ? a : b;
    }

    private static long minUnsigned(long a, long b) {
        return Long.compareUnsigned(a, b) <= 0 ? a : b;
    }
}
