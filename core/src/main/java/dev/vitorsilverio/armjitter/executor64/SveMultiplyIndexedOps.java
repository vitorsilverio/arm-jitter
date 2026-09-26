package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.math.BigInteger;

/// Semântica do multiply SVE por elemento indexado e dos dot-products vetoriais (B17.8): `SDOT`/`UDOT`/`USDOT`/
/// `SUDOT` (2 e 4 vias), `CDOT`, `MLA`/`MLS`/`MUL`, `SQDMULH`/`SQRDMULH`/`SQRDMLAH`/`SQRDMLSH`, as alargantes
/// `*MLAL`/`*MLSL`/`*MULL`/`SQDML*L` (`B`/`T`) e os complexos `CMLA`/`SQRDCMLAH`.
///
/// **O índice é por segmento de 128 bits**: cada segmento lê o elemento `index` DELE em `Zm`. Com `VL = 128` isso
/// é indistinguível de "índice global"; só `VL >= 256` (como nos testes) mostra a diferença.
///
/// Todas as fontes (`Zn`, `Zm` e o acumulador `Zda` = `Zd`) são lidas de um **instantâneo** tirado antes da
/// primeira escrita, então o resultado é função só dos valores antigos mesmo com `Zd == Zn` ou `Zd == Zm` — que é
/// o que o pseudocódigo do manual descreve (o QEMU obtém o mesmo lendo o operando indexado antes de cada
/// segmento). As operações saturantes NÃO tocam `FPSR.QC` (só o AdvSIMD o faz; medido em `do_sqrdmlah_*` do QEMU,
/// que descarta `sat` nas formas SVE).
final class SveMultiplyIndexedOps {
    private static final int SEGMENT_BYTES = 16;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int COMPLEX_PAIR = 2;
    private static final int COMPLEX_GROUP = 4;
    private static final int ROTATION_SUBTRACT_REAL_LOW = 1;
    private static final int ROTATION_SUBTRACT_REAL_HIGH = 2;
    private static final int ROTATION_SUBTRACT_IMAGINARY_FROM = 2;
    private static final int ROTATION_NEGATE_LOW = 0;
    private static final int ROTATION_NEGATE_HIGH = 3;

    private SveMultiplyIndexedOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveMultiplyIndexed op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int words = core.vectorLengthBytes() / Long.BYTES;
        long[] n = snapshot(regs, op.rn(), words);
        long[] m = snapshot(regs, op.rm(), words);
        long[] a = snapshot(regs, op.rd(), words);
        switch (op.op()) {
            case SDOT, UDOT, USDOT, SUDOT -> dot(core, regs, op, n, m, a);
            case CDOT -> complexDot(core, regs, op, n, m, a);
            case CMLA, SQRDCMLAH -> complexMultiplyAdd(core, regs, op, n, m, a);
            case SQDMLAL, SQDMLSL, SMLAL, UMLAL, SMLSL, UMLSL, SMULL, UMULL, SQDMULL ->
                    widening(core, regs, op, n, m, a);
            default -> sameSize(core, regs, op, n, m, a);
        }
        return false;
    }

    private static long[] snapshot(Aarch64ScalableRegisters regs, int reg, int words) {
        long[] copy = new long[words];
        for (int w = 0; w < words; w++) {
            copy[w] = regs.zWord(reg, w);
        }
        return copy;
    }

    /// Elemento `index` (de `bits` bits, sem sinal) de um instantâneo.
    private static long element(long[] z, int index, int bits) {
        int bitOffset = index * bits;
        long shifted = z[bitOffset >>> WORD_INDEX_SHIFT] >>> (bitOffset & WORD_BIT_MASK);
        return bits == Long.SIZE ? shifted : shifted & ((1L << bits) - 1L);
    }

    private static long signed(long value, int bits) {
        int shift = Long.SIZE - bits;
        return (value << shift) >> shift;
    }

    // ── Dot-product (4 e 2 vias, vetorial e indexado) ────────────────────────────────────────────

    private static void dot(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SveMultiplyIndexed op, long[] n,
            long[] m, long[] a) {
        int esz = op.esz();
        int destBits = Byte.SIZE << esz;
        int sourceBits = destBits / op.ways();
        int elements = core.vectorLengthBytes() >> esz;
        int perSegment = SEGMENT_BYTES >> esz;
        boolean signedN = op.op() == Ir64Op.SveMultiplyIndexed.Op.SDOT || op.op() == Ir64Op.SveMultiplyIndexed.Op.SUDOT;
        boolean signedM = op.op() == Ir64Op.SveMultiplyIndexed.Op.SDOT || op.op() == Ir64Op.SveMultiplyIndexed.Op.USDOT;
        for (int e = 0; e < elements; e++) {
            long sum = element(a, e, destBits);
            for (int k = 0; k < op.ways(); k++) {
                int mIndex = op.indexed() ? (e / perSegment) * perSegment * op.ways() + op.index() * op.ways() + k
                        : e * op.ways() + k;
                long nn = element(n, e * op.ways() + k, sourceBits);
                long mm = element(m, mIndex, sourceBits);
                sum += (signedN ? signed(nn, sourceBits) : nn) * (signedM ? signed(mm, sourceBits) : mm);
            }
            SveIntegerOps.set(regs, op.rd(), e, esz, sum);
        }
    }

    /// `CDOT`: cada elemento de destino acumula 2 produtos complexos de 4 elementos estreitos (`re`, `im` × 2).
    private static void complexDot(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SveMultiplyIndexed op,
            long[] n, long[] m, long[] a) {
        int esz = op.esz();
        int destBits = Byte.SIZE << esz;
        int sourceBits = destBits / COMPLEX_GROUP;
        int elements = core.vectorLengthBytes() >> esz;
        int perSegment = SEGMENT_BYTES >> esz;
        int selA = op.rot() & 1;
        int selB = selA ^ 1;
        int imaginarySign = op.rot() == ROTATION_NEGATE_LOW || op.rot() == ROTATION_NEGATE_HIGH ? -1 : 1;
        for (int e = 0; e < elements; e++) {
            long sum = element(a, e, destBits);
            int mElement = op.indexed() ? (e / perSegment) * perSegment + op.index() : e;
            for (int pair = 0; pair < COMPLEX_PAIR; pair++) {
                int base = e * COMPLEX_GROUP + pair * COMPLEX_PAIR;
                int mBase = mElement * COMPLEX_GROUP + pair * COMPLEX_PAIR;
                long real = signed(element(n, base, sourceBits), sourceBits);
                long imaginary = signed(element(n, base + 1, sourceBits), sourceBits);
                long mA = signed(element(m, mBase + selA, sourceBits), sourceBits);
                long mB = signed(element(m, mBase + selB, sourceBits), sourceBits);
                sum += real * mA + imaginary * mB * imaginarySign;
            }
            SveIntegerOps.set(regs, op.rd(), e, esz, sum);
        }
    }

    // ── Complexos indexados ──────────────────────────────────────────────────────────────────────

    /// `CMLA`/`SQRDCMLAH` indexados: o índice escolhe um PAR (real, imaginário) de `Zm` dentro do segmento; cada
    /// par de `Zn` contribui `elt1_a * elt2_a` para o real e `elt1_a * elt2_b` para o imaginário, com os sinais
    /// da rotação (`sub_r = rot in {1,2}`, `sub_i = rot >= 2`). `esz` é o tamanho do elemento (half ou word).
    private static void complexMultiplyAdd(Aarch64Core core, Aarch64ScalableRegisters regs,
            Ir64Op.SveMultiplyIndexed op, long[] n, long[] m, long[] a) {
        int esz = op.esz();
        int bits = Byte.SIZE << esz;
        int elements = core.vectorLengthBytes() >> esz;
        int perSegment = SEGMENT_BYTES >> esz;
        int selA = op.rot() & 1;
        int selB = selA ^ 1;
        boolean subtractReal = op.rot() == ROTATION_SUBTRACT_REAL_LOW || op.rot() == ROTATION_SUBTRACT_REAL_HIGH;
        boolean subtractImaginary = op.rot() >= ROTATION_SUBTRACT_IMAGINARY_FROM;
        boolean saturating = op.op() == Ir64Op.SveMultiplyIndexed.Op.SQRDCMLAH;
        for (int segment = 0; segment < elements; segment += perSegment) {
            long m2a = signed(element(m, segment + op.index() * COMPLEX_PAIR + selA, bits), bits);
            long m2b = signed(element(m, segment + op.index() * COMPLEX_PAIR + selB, bits), bits);
            for (int j = 0; j < perSegment; j += COMPLEX_PAIR) {
                long n1a = signed(element(n, segment + j + selA, bits), bits);
                long real = complexTerm(n1a, m2a, signed(element(a, segment + j, bits), bits), subtractReal,
                        saturating, esz);
                long imaginary = complexTerm(n1a, m2b, signed(element(a, segment + j + 1, bits), bits),
                        subtractImaginary, saturating, esz);
                SveIntegerOps.set(regs, op.rd(), segment + j, esz, real);
                SveIntegerOps.set(regs, op.rd(), segment + j + 1, esz, imaginary);
            }
        }
    }

    private static long complexTerm(long n, long m, long acc, boolean subtract, boolean saturating, int esz) {
        if (saturating) {
            return sqrdmlah(n, m, acc, subtract, true, esz);
        }
        return subtract ? acc - n * m : acc + n * m;
    }

    // ── Alargantes (B/T) ─────────────────────────────────────────────────────────────────────────

    /// `*MLAL`/`*MLSL`/`*MULL`/`SQDML*L`: o elemento de destino `e` usa o elemento estreito `2e + top` de `Zn` e o
    /// elemento estreito `index` do segmento de `Zm` (o mesmo para as duas metades, `B` e `T`).
    private static void widening(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SveMultiplyIndexed op,
            long[] n, long[] m, long[] a) {
        int esz = op.esz();
        int destBits = Byte.SIZE << esz;
        int narrowBits = destBits / 2;
        int elements = core.vectorLengthBytes() >> esz;
        int perSegment = SEGMENT_BYTES >> esz;
        boolean unsigned = op.op() == Ir64Op.SveMultiplyIndexed.Op.UMLAL
                || op.op() == Ir64Op.SveMultiplyIndexed.Op.UMLSL || op.op() == Ir64Op.SveMultiplyIndexed.Op.UMULL;
        for (int e = 0; e < elements; e++) {
            long nn = element(n, 2 * e + (op.top() ? 1 : 0), narrowBits);
            long mm = element(m, (e / perSegment) * perSegment * 2 + op.index(), narrowBits);
            if (!unsigned) {
                nn = signed(nn, narrowBits);
                mm = signed(mm, narrowBits);
            }
            long acc = element(a, e, destBits);
            long result = switch (op.op()) {
                case SMLAL, UMLAL -> acc + nn * mm;
                case SMLSL, UMLSL -> acc - nn * mm;
                case SMULL, UMULL -> nn * mm;
                case SQDMULL -> doublingMultiply(nn, mm, esz);
                case SQDMLAL -> saturatingAdd(signed(acc, destBits), doublingMultiply(nn, mm, esz), esz, false);
                default -> saturatingAdd(signed(acc, destBits), doublingMultiply(nn, mm, esz), esz, true); // SQDMLSL
            };
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
    }

    /// `SQDMULL`: `2 * n * m` saturado ao tamanho do destino (só `MIN * MIN` estoura).
    private static long doublingMultiply(long n, long m, int esz) {
        long product = n * m;
        if (esz == ESZ_DOUBLEWORD) {
            return product == 1L << (Long.SIZE - 2) ? Long.MAX_VALUE : product * 2L;
        }
        return saturate(product * 2L, esz);
    }

    private static long saturatingAdd(long acc, long product, int esz, boolean subtract) {
        if (esz == ESZ_DOUBLEWORD) {
            long result = subtract ? acc - product : acc + product;
            boolean overflow = subtract ? ((acc ^ product) & (acc ^ result)) < 0 : ((acc ^ result) & (product ^ result)) < 0;
            return overflow ? (acc < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : result;
        }
        return saturate(subtract ? acc - product : acc + product, esz);
    }

    private static long saturate(long value, int esz) {
        long max = (1L << ((Byte.SIZE << esz) - 1)) - 1L;
        return Math.max(-max - 1L, Math.min(max, value));
    }

    // ── Operações no tamanho do elemento ─────────────────────────────────────────────────────────

    private static void sameSize(Aarch64Core core, Aarch64ScalableRegisters regs, Ir64Op.SveMultiplyIndexed op,
            long[] n, long[] m, long[] a) {
        int esz = op.esz();
        int bits = Byte.SIZE << esz;
        int elements = core.vectorLengthBytes() >> esz;
        int perSegment = SEGMENT_BYTES >> esz;
        for (int e = 0; e < elements; e++) {
            long nn = element(n, e, bits);
            long mm = element(m, (e / perSegment) * perSegment + op.index(), bits);
            long acc = element(a, e, bits);
            long result = switch (op.op()) {
                case MLA -> acc + nn * mm;
                case MLS -> acc - nn * mm;
                case MUL -> nn * mm;
                case SQDMULH -> sqrdmlah(signed(nn, bits), signed(mm, bits), 0L, false, false, esz);
                case SQRDMULH -> sqrdmlah(signed(nn, bits), signed(mm, bits), 0L, false, true, esz);
                case SQRDMLAH -> sqrdmlah(signed(nn, bits), signed(mm, bits), signed(acc, bits), false, true, esz);
                default -> sqrdmlah(signed(nn, bits), signed(mm, bits), signed(acc, bits), true, true, esz); // SQRDMLSH
            };
            SveIntegerOps.set(regs, op.rd(), e, esz, result);
        }
    }

    /// `((acc << (bits-1)) ± n*m + round) >> (bits-1)` saturado ao tamanho do elemento — `do_sqrdmlah_*` do QEMU.
    /// Em 64 bits o intermediário passa de 64 bits, então usa `BigInteger` (o caminho de 16/32 bits cabe em `long`).
    static long sqrdmlah(long n, long m, long acc, boolean subtract, boolean round, int esz) {
        int bits = Byte.SIZE << esz;
        int shift = bits - 1;
        if (esz == ESZ_DOUBLEWORD) {
            BigInteger product = BigInteger.valueOf(n).multiply(BigInteger.valueOf(m));
            if (subtract) {
                product = product.negate();
            }
            BigInteger total = product.add(BigInteger.valueOf(acc).shiftLeft(shift));
            if (round) {
                total = total.add(BigInteger.ONE.shiftLeft(shift - 1));
            }
            BigInteger result = total.shiftRight(shift);
            if (result.bitLength() > Long.SIZE - 1) {
                return result.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            return result.longValue();
        }
        long product = subtract ? -(n * m) : n * m;
        long total = product + (acc << shift) + (round ? 1L << (shift - 1) : 0L);
        return saturate(total >> shift, esz);
    }
}
