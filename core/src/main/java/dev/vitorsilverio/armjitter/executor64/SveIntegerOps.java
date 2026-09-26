package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das operações inteiras SVE sem predicado governante (B17.5): aritmética e lógica por
/// elemento, shifts, lógica ternária SVE2, `MLA`/`MLS`/`MAD`/`MSB` (estas COM predicado), `MOVPRFX`,
/// `FEXPA`/`FTSSEL` e `INDEX`.
///
/// Tudo opera sobre o `VL` **efetivo** do core ({@link Aarch64Core#vectorLengthBytes()}) — o mesmo
/// código serve `VL` 256/512 e o `SVL` em modo streaming (G6). Os elementos são lidos/escritos com
/// {@link SvePredicateOps#elementOf}/{@link SvePredicateOps#setElementOf}.
///
/// **`MOVPRFX`** é executado como um `MOV Zd, Zn` comum (estratégia (a) da Armadilha 1 da task, a mesma
/// do `trans_MOVPRFX` do QEMU: `do_mov_z`): a instrução seguinte lê `Zd` como uma fonte destrutiva
/// qualquer, então o resultado é idêntico ao da forma construtiva.
final class SveIntegerOps {
    private static final int BITS_PER_BYTE = Byte.SIZE;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final long FTSSEL_SWAP_TO_ONE_BIT = 1L;
    private static final long FTSSEL_NEGATE_BIT = 2L;
    private static final long HALF_ONE = 0x3C00L;
    private static final long SINGLE_ONE = 0x3F800000L;
    private static final long DOUBLE_ONE = 0x3FF0000000000000L;
    private static final int FEXPA_HALF_INDEX_BITS = 5;
    private static final int FEXPA_HALF_EXP_BITS = 5;
    private static final int FEXPA_HALF_EXP_SHIFT = 10;
    private static final int FEXPA_SINGLE_INDEX_BITS = 6;
    private static final int FEXPA_SINGLE_EXP_BITS = 8;
    private static final int FEXPA_SINGLE_EXP_SHIFT = 23;
    private static final int FEXPA_DOUBLE_INDEX_BITS = 6;
    private static final int FEXPA_DOUBLE_EXP_BITS = 11;
    private static final int FEXPA_DOUBLE_EXP_SHIFT = 52;

    /// `coeff[]` de `FEXPA` (`.H`), colada do pseudocódigo da ARM (`helper_sve_fexpa_h` do QEMU).
    private static final int[] FEXPA_HALF = {
        0x0000, 0x0016, 0x002d, 0x0045, 0x005d, 0x0075, 0x008e, 0x00a8,
        0x00c2, 0x00dc, 0x00f8, 0x0114, 0x0130, 0x014d, 0x016b, 0x0189,
        0x01a8, 0x01c8, 0x01e8, 0x0209, 0x022b, 0x024e, 0x0271, 0x0295,
        0x02ba, 0x02e0, 0x0306, 0x032e, 0x0356, 0x037f, 0x03a9, 0x03d4,
    };
    /// `coeff[]` de `FEXPA` (`.S`).
    private static final int[] FEXPA_SINGLE = {
        0x000000, 0x0164d2, 0x02cd87, 0x043a29,
        0x05aac3, 0x071f62, 0x08980f, 0x0a14d5,
        0x0b95c2, 0x0d1adf, 0x0ea43a, 0x1031dc,
        0x11c3d3, 0x135a2b, 0x14f4f0, 0x16942d,
        0x1837f0, 0x19e046, 0x1b8d3a, 0x1d3eda,
        0x1ef532, 0x20b051, 0x227043, 0x243516,
        0x25fed7, 0x27cd94, 0x29a15b, 0x2b7a3a,
        0x2d583f, 0x2f3b79, 0x3123f6, 0x3311c4,
        0x3504f3, 0x36fd92, 0x38fbaf, 0x3aff5b,
        0x3d08a4, 0x3f179a, 0x412c4d, 0x4346cd,
        0x45672a, 0x478d75, 0x49b9be, 0x4bec15,
        0x4e248c, 0x506334, 0x52a81e, 0x54f35b,
        0x5744fd, 0x599d16, 0x5bfbb8, 0x5e60f5,
        0x60ccdf, 0x633f89, 0x65b907, 0x68396a,
        0x6ac0c7, 0x6d4f30, 0x6fe4ba, 0x728177,
        0x75257d, 0x77d0df, 0x7a83b3, 0x7d3e0c,
    };
    /// `coeff[]` de `FEXPA` (`.D`).
    private static final long[] FEXPA_DOUBLE = {
        0x0000000000000L, 0x02C9A3E778061L, 0x059B0D3158574L,
        0x0874518759BC8L, 0x0B5586CF9890FL, 0x0E3EC32D3D1A2L,
        0x11301D0125B51L, 0x1429AAEA92DE0L, 0x172B83C7D517BL,
        0x1A35BEB6FCB75L, 0x1D4873168B9AAL, 0x2063B88628CD6L,
        0x2387A6E756238L, 0x26B4565E27CDDL, 0x29E9DF51FDEE1L,
        0x2D285A6E4030BL, 0x306FE0A31B715L, 0x33C08B26416FFL,
        0x371A7373AA9CBL, 0x3A7DB34E59FF7L, 0x3DEA64C123422L,
        0x4160A21F72E2AL, 0x44E086061892DL, 0x486A2B5C13CD0L,
        0x4BFDAD5362A27L, 0x4F9B2769D2CA7L, 0x5342B569D4F82L,
        0x56F4736B527DAL, 0x5AB07DD485429L, 0x5E76F15AD2148L,
        0x6247EB03A5585L, 0x6623882552225L, 0x6A09E667F3BCDL,
        0x6DFB23C651A2FL, 0x71F75E8EC5F74L, 0x75FEB564267C9L,
        0x7A11473EB0187L, 0x7E2F336CF4E62L, 0x82589994CCE13L,
        0x868D99B4492EDL, 0x8ACE5422AA0DBL, 0x8F1AE99157736L,
        0x93737B0CDC5E5L, 0x97D829FDE4E50L, 0x9C49182A3F090L,
        0xA0C667B5DE565L, 0xA5503B23E255DL, 0xA9E6B5579FDBFL,
        0xAE89F995AD3ADL, 0xB33A2B84F15FBL, 0xB7F76F2FB5E47L,
        0xBCC1E904BC1D2L, 0xC199BDD85529CL, 0xC67F12E57D14BL,
        0xCB720DCEF9069L, 0xD072D4A07897CL, 0xD5818DCFBA487L,
        0xDA9E603DB3285L, 0xDFC97337B9B5FL, 0xE502EE78B3FF6L,
        0xEA4AFA2A490DAL, 0xEFA1BEE615A27L, 0xF50765B6E4540L,
        0xFA7C1819E90D8L,
    };

    private SveIntegerOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveIntegerUnpredicated op) {
        if (op.op() == Ir64Op.SveIntegerUnpredicated.Op.FEXPA || op.op() == Ir64Op.SveIntegerUnpredicated.Op.FTSSEL) {
            SvePredicateOps.requireNonStreaming(core); // sem FEAT_SSVE_FEXPA: ilegais em streaming
        }
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = core.vectorLengthBytes() >> op.esz();
        switch (op.op()) {
            case ADD, SUB, SQADD, UQADD, SQSUB, UQSUB -> arithmetic(regs, op, elements);
            case AND, ORR, EOR, BIC, EOR3, BSL, BCAX, BSL1N, BSL2N, NBSL -> bitwise(core, regs, op);
            case XAR -> exclusiveOrRotate(regs, op, elements);
            case ASR_IMM, LSR_IMM, LSL_IMM -> shiftImmediate(regs, op, elements);
            case ASR_WIDE, LSR_WIDE, LSL_WIDE -> shiftWide(regs, op, elements);
            case MLA, MLS, MAD, MSB -> multiplyAdd(core, regs, op, elements);
            case MOVPRFX -> copyVector(core, regs, op.rd(), op.rn());
            case FEXPA -> exponentialAccelerator(regs, op, elements);
            case FTSSEL -> trigonometricSelect(regs, op, elements);
            default -> index(core, regs, op, elements); // INDEX_II/IR/RI/RR
        }
        return false;
    }

    // ── Auxiliares de elemento ───────────────────────────────────────────────────────────────────

    static int elementBits(int esz) {
        return BITS_PER_BYTE << esz;
    }

    static long elementMask(int esz) {
        return esz == ESZ_DOUBLEWORD ? -1L : (1L << elementBits(esz)) - 1L;
    }

    static long signExtend(long value, int esz) {
        int shift = Long.SIZE - elementBits(esz);
        return (value << shift) >> shift;
    }

    static long get(Aarch64ScalableRegisters regs, int reg, int element, int esz) {
        return SvePredicateOps.elementOf(regs, reg, element, esz);
    }

    static void set(Aarch64ScalableRegisters regs, int reg, int element, int esz, long value) {
        SvePredicateOps.setElementOf(regs, reg, element, esz, value & elementMask(esz));
    }

    private static long mask(int bits) {
        return (1L << bits) - 1L;
    }

    // ── Aritmética ───────────────────────────────────────────────────────────────────────────────

    private static void arithmetic(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        for (int e = 0; e < elements; e++) {
            long n = get(regs, op.rn(), e, esz);
            long m = get(regs, op.rm(), e, esz);
            long result = switch (op.op()) {
                case ADD -> n + m;
                case SUB -> n - m;
                case SQADD -> saturateSigned(n, m, esz, false);
                case SQSUB -> saturateSigned(n, m, esz, true);
                case UQADD -> saturateUnsigned(n, m, esz, false);
                default -> saturateUnsigned(n, m, esz, true); // UQSUB
            };
            set(regs, op.rd(), e, esz, result);
        }
    }

    private static long saturateSigned(long n, long m, int esz, boolean subtract) {
        long a = signExtend(n, esz);
        long b = signExtend(m, esz);
        long result = subtract ? a - b : a + b;
        if (esz == ESZ_DOUBLEWORD) {
            boolean overflow = subtract ? ((a ^ b) & (a ^ result)) < 0 : ((a ^ result) & (b ^ result)) < 0;
            return overflow ? (a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : result;
        }
        long max = (1L << (elementBits(esz) - 1)) - 1L;
        long min = -max - 1L;
        return Math.max(min, Math.min(max, result));
    }

    private static long saturateUnsigned(long n, long m, int esz, boolean subtract) {
        if (esz == ESZ_DOUBLEWORD) {
            if (subtract) {
                return Long.compareUnsigned(n, m) < 0 ? 0L : n - m;
            }
            long sum = n + m;
            return Long.compareUnsigned(sum, n) < 0 ? -1L : sum;
        }
        long result = subtract ? n - m : n + m;
        return Math.max(0L, Math.min(elementMask(esz), result));
    }

    // ── Lógica ───────────────────────────────────────────────────────────────────────────────────

    /// Lógica de vetor inteiro (as 4 formas SVE e as 6 ternárias SVE2): bit a bit, `esz` não conta.
    /// As ternárias são destrutivas (`Zdn`), com `Zm` e `Zk` (`ra`) como as outras duas fontes.
    private static void bitwise(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op) {
        int words = core.vectorLengthBytes() / Long.BYTES;
        for (int w = 0; w < words; w++) {
            long n = regs.zWord(op.rn(), w);
            long m = regs.zWord(op.rm(), w);
            long k = regs.zWord(op.ra(), w);
            long result = switch (op.op()) {
                case AND -> n & m;
                case ORR -> n | m;
                case EOR -> n ^ m;
                case BIC -> n & ~m;
                case EOR3 -> n ^ m ^ k;
                case BCAX -> n ^ (m & ~k);
                case BSL -> (n & k) | (m & ~k);
                case BSL1N -> (~n & k) | (m & ~k);
                case BSL2N -> (n & k) | (~m & ~k);
                default -> ~((n & k) | (m & ~k)); // NBSL
            };
            regs.setZWord(op.rd(), w, result);
        }
    }

    /// `XAR`: `(Zdn ^ Zm)` rotacionado à direita por `imm` dentro de cada elemento.
    private static void exclusiveOrRotate(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        int bits = elementBits(esz);
        int amount = (int) (op.imm() % bits); // rotação por `esize` = identidade
        long mask = elementMask(esz);
        for (int e = 0; e < elements; e++) {
            long value = (get(regs, op.rn(), e, esz) ^ get(regs, op.rm(), e, esz)) & mask;
            long rotated = amount == 0 ? value : ((value >>> amount) | (value << (bits - amount))) & mask;
            set(regs, op.rd(), e, esz, rotated);
        }
    }

    // ── Shifts ───────────────────────────────────────────────────────────────────────────────────

    /// `amount` é sem sinal de até 64 bits; um valor com o bit 63 ligado aparece negativo e conta como
    /// "estoura o elemento" (`ASR` preenche com o sinal, `LSR`/`LSL` zeram).
    private static long shift(Ir64Op.SveIntegerUnpredicated.Op kind, long value, long amount, int esz) {
        int bits = elementBits(esz);
        boolean overflow = amount < 0 || amount >= bits;
        return switch (kind) {
            case ASR_IMM, ASR_WIDE -> signExtend(value, esz) >> (overflow ? bits - 1 : (int) amount);
            case LSR_IMM, LSR_WIDE -> overflow ? 0L : value >>> amount;
            default -> overflow ? 0L : value << amount; // LSL_*
        };
    }

    private static void shiftImmediate(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        for (int e = 0; e < elements; e++) {
            set(regs, op.rd(), e, op.esz(), shift(op.op(), get(regs, op.rn(), e, op.esz()), op.imm(), op.esz()));
        }
    }

    /// `_zzw`: o elemento de `Zm` que governa é o doubleword (64 bits) que CONTÉM o elemento de `Zn`.
    private static void shiftWide(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        int perDoubleword = Long.BYTES >> esz;
        for (int e = 0; e < elements; e++) {
            long amount = regs.zWord(op.rm(), e / perDoubleword);
            set(regs, op.rd(), e, esz, shift(op.op(), get(regs, op.rn(), e, esz), amount, esz));
        }
    }

    // ── Multiply-add (predicado) ─────────────────────────────────────────────────────────────────

    /// `MLA`/`MLS`: `Zda ± Zn*Zm`; `MAD`/`MSB`: `Za ± Zdn*Zm`. Merging: elemento inativo fica como está.
    /// O bit do predicado de um elemento é o do byte mais baixo dele (`P` guarda um bit por byte).
    private static void multiplyAdd(Aarch64Core core, Aarch64ScalableRegisters regs,
            Ir64Op.SveIntegerUnpredicated op, int elements) {
        int esz = op.esz();
        boolean accumulateIntoDestination = op.op() == Ir64Op.SveIntegerUnpredicated.Op.MLA
                || op.op() == Ir64Op.SveIntegerUnpredicated.Op.MLS;
        boolean subtract = op.op() == Ir64Op.SveIntegerUnpredicated.Op.MLS
                || op.op() == Ir64Op.SveIntegerUnpredicated.Op.MSB;
        for (int e = 0; e < elements; e++) {
            int bit = e << esz;
            if (((regs.pWord(op.pg(), bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) == 0L) {
                continue;
            }
            long product = get(regs, op.rn(), e, esz) * get(regs, op.rm(), e, esz);
            long addend = get(regs, accumulateIntoDestination ? op.rd() : op.ra(), e, esz);
            set(regs, op.rd(), e, esz, subtract ? addend - product : addend + product);
        }
    }

    // ── MOVPRFX / FEXPA / FTSSEL ─────────────────────────────────────────────────────────────────

    private static void copyVector(Aarch64Core core, Aarch64ScalableRegisters regs, int rd, int rn) {
        int words = core.vectorLengthBytes() / Long.BYTES;
        for (int w = 0; w < words; w++) {
            regs.setZWord(rd, w, regs.zWord(rn, w));
        }
    }

    private static void exponentialAccelerator(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        for (int e = 0; e < elements; e++) {
            long n = get(regs, op.rn(), e, esz);
            long result = switch (esz) {
                case ESZ_HALF -> FEXPA_HALF[(int) (n & mask(FEXPA_HALF_INDEX_BITS))]
                        | (((n >>> FEXPA_HALF_INDEX_BITS) & mask(FEXPA_HALF_EXP_BITS)) << FEXPA_HALF_EXP_SHIFT);
                case ESZ_WORD -> FEXPA_SINGLE[(int) (n & mask(FEXPA_SINGLE_INDEX_BITS))]
                        | (((n >>> FEXPA_SINGLE_INDEX_BITS) & mask(FEXPA_SINGLE_EXP_BITS)) << FEXPA_SINGLE_EXP_SHIFT);
                default -> FEXPA_DOUBLE[(int) (n & mask(FEXPA_DOUBLE_INDEX_BITS))]
                        | (((n >>> FEXPA_DOUBLE_INDEX_BITS) & mask(FEXPA_DOUBLE_EXP_BITS)) << FEXPA_DOUBLE_EXP_SHIFT);
            };
            set(regs, op.rd(), e, esz, result);
        }
    }

    /// `FTSSEL`: bit 0 de `Zm` troca o elemento por `1.0`; bit 1 inverte o sinal (`FPCR.AH` não é
    /// modelado no core — pendência nomeada da task, vale o caso `AH = 0`).
    private static void trigonometricSelect(Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        int esz = op.esz();
        long one = switch (esz) {
            case ESZ_HALF -> HALF_ONE;
            case ESZ_WORD -> SINGLE_ONE;
            default -> DOUBLE_ONE;
        };
        long signBit = 1L << (elementBits(esz) - 1);
        for (int e = 0; e < elements; e++) {
            long n = get(regs, op.rn(), e, esz);
            long m = get(regs, op.rm(), e, esz);
            if ((m & FTSSEL_SWAP_TO_ONE_BIT) != 0L) {
                n = one;
            }
            if ((m & FTSSEL_NEGATE_BIT) != 0L) {
                n ^= signBit;
            }
            set(regs, op.rd(), e, esz, n);
        }
    }

    // ── INDEX ────────────────────────────────────────────────────────────────────────────────────

    /// `INDEX`: `Zd[i] = início + i * incremento`, truncado ao elemento. Os quatro formatos só diferem
    /// em de onde vêm o início e o incremento (imediato com sinal ou `Xn`/`Xm`, sendo `31` = `XZR`).
    private static void index(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SveIntegerUnpredicated op,
            int elements) {
        long start;
        long increment;
        switch (op.op()) {
            case INDEX_II -> {
                start = op.imm();
                increment = op.imm2();
            }
            case INDEX_IR -> {
                start = op.imm();
                increment = core.x(op.rm());
            }
            case INDEX_RI -> {
                start = core.x(op.rn());
                increment = op.imm();
            }
            default -> {
                start = core.x(op.rn());
                increment = core.x(op.rm());
            }
        }
        for (int e = 0; e < elements; e++) {
            set(regs, op.rd(), e, op.esz(), start + e * increment);
        }
    }
}
