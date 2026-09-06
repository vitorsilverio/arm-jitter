package dev.vitorsilverio.armjitter.advsimd;

import java.math.BigInteger;

/// Núcleo vetorial COMPARTILHADO pelos dois pipelines (AArch64 `ir64`/`executor64` e o pipeline de
/// 32 bits `ir`/`codegen.executor`) — RFC B13.2, decisão D1 (reuso, não espelhamento).
///
/// Toda a semântica de lane (largura de elemento, extensão de sinal, truncamento) mora aqui e é
/// escrita UMA vez; o que fica com cada pipeline é só o que realmente difere entre as duas
/// arquiteturas: o encoding, a IR, o mapeamento registrador→palavra
/// ({@link AdvSimdRegisterWords}) e a disciplina de escrita do destino (o A64 zera os bits altos
/// do `V<n>`; o NEON de 32 bits escreve exatamente o `D`/`Q` nomeado).
///
/// Sem estado próprio (nenhuma operação toca memória ou registrador geral), métodos estáticos —
/// mesma forma de `Ir64VectorArithmeticExecutor`, do qual esta classe é a extração.
public final class AdvSimdLanes {
    private AdvSimdLanes() {
    }

    /// Bits de uma palavra do banco ({@link AdvSimdRegisterWords}).
    public static final int WORD_BITS = 64;

    /// Máscara de posição dentro de uma palavra de {@value #WORD_BITS} bits.
    private static final int WORD_BIT_MASK = WORD_BITS - 1;

    /// Máscara de um elemento de `1 << esz` bytes (`esz` `0`-`3`: byte/halfword/word/doubleword).
    public static long elementMask(int esz) {
        int bits = 8 << esz;
        return bits == WORD_BITS ? -1L : (1L << bits) - 1;
    }

    /// Sign-extende para `long` um elemento de `1 << esz` bytes já lido zero-extendido por
    /// {@link #element}.
    public static long signExtend(long value, int esz) {
        int bits = 8 << esz;
        if (bits == WORD_BITS) {
            return value;
        }
        int shift = WORD_BITS - bits;
        return (value << shift) >> shift;
    }

    /// Trunca `value` ao tamanho de um elemento de `1 << esz` bytes.
    public static long truncate(long value, int esz) {
        return value & elementMask(esz);
    }

    /// Máscara "todos-1" (do tamanho do elemento) ou `0` para o resultado de uma comparação de
    /// elemento (`VCGT`/`VCGE`/`VTST`/`VCEQ` e os `CM**_v` do A64).
    public static long boolMask(boolean condition, int esz) {
        return condition ? elementMask(esz) : 0L;
    }

    /// Multiplicação polinomial (`GF(2)`, `PMUL_v`/`VMUL.P8`, sempre `byte`): XOR de `a<<i` para
    /// cada bit `i` setado de `b`, sem carry — definição real de `PolynomialMult` do ARM ARM.
    /// Pública porque `PMULL`/`PMULL2` do A64 (B8.11, alargando sem truncar) reaproveita o mesmo
    /// produto de 15 bits.
    public static long polynomialMultiply8(long a, long b) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            if (((b >>> i) & 1) != 0) {
                result ^= a << i;
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Aritmética de SATURAÇÃO e deslocamento por registrador — B13.5 (migração D1 da RFC B13.2).
    // Cópias VERBATIM dos helpers homônimos de `executor64/Ir64VectorArithmeticExecutor` (B8.8/
    // B11.4): as 16 operações `SQADD`..`SQRDMLSH` que a B13.4 tinha deixado no `switch` A64 agora
    // vivem SÓ aqui. Nenhuma modela `FPSCR.QC`/`FPSR.QC` (só o VALOR saturado é observável —
    // paridade consciente com o A64, que nunca modelou o bit cumulativo). Toda a aritmética larga
    // usa {@link BigInteger} deliberadamente: estas ops caem no interpretador (nenhum `Kind`
    // vetorial entra em política ASM nativa), então exatidão pesa mais que velocidade.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /// Reinterpreta um `long` java (que pode ser negativo) como o inteiro NÃO ASSINADO de 64 bits
    /// que ele representa em complemento de dois — `esz=3` (doubleword) é o único tamanho em que um
    /// elemento não assinado pode ultrapassar {@link Long#MAX_VALUE}.
    private static BigInteger unsignedBig(long value) {
        return value >= 0 ? BigInteger.valueOf(value) : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    /// Satura `value` (matemático, sem wraparound) ao intervalo representável por um elemento de
    /// `esz` bytes, assinado ou não.
    private static long saturateToElement(BigInteger value, int esz, boolean signed) {
        int bits = 8 << esz;
        BigInteger max = signed
                ? BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger min = signed ? max.negate().subtract(BigInteger.ONE) : BigInteger.ZERO;
        if (value.compareTo(max) > 0) {
            return max.longValue();
        }
        if (value.compareTo(min) < 0) {
            return min.longValue();
        }
        return value.longValue();
    }

    private static long signedSaturatingAdd(long sa, long sb, int esz) {
        return saturateToElement(BigInteger.valueOf(sa).add(BigInteger.valueOf(sb)), esz, true);
    }

    private static long signedSaturatingSub(long sa, long sb, int esz) {
        return saturateToElement(BigInteger.valueOf(sa).subtract(BigInteger.valueOf(sb)), esz, true);
    }

    private static long unsignedSaturatingAdd(long a, long b, int esz) {
        return saturateToElement(unsignedBig(a).add(unsignedBig(b)), esz, false);
    }

    private static long unsignedSaturatingSub(long a, long b, int esz) {
        return saturateToElement(unsignedBig(a).subtract(unsignedBig(b)), esz, false);
    }

    /// Deslocamento à esquerda seguro: Java `<<`/`>>>`/`>>` usam o deslocamento MOD 64 para `long`
    /// (`x << 64` == `x << 0`, não `0`) — guarda-corpo para quando o deslocamento pode chegar a 64
    /// (`esz=3`) ou, por registrador, a qualquer magnitude de um byte assinado.
    private static long safeShiftLeft(long value, int shift) {
        return shift >= 64 ? 0L : value << shift;
    }

    private static long logicalShiftRight(long value, int shift) {
        return shift >= 64 ? 0L : value >>> shift;
    }

    private static long arithmeticShiftRight(long value, int shift) {
        return shift >= 64 ? (value < 0 ? -1L : 0L) : value >> shift;
    }

    /// Deslocamento à direita com ARREDONDAMENTO (`round = 1 << (shift-1)` somado antes de deslocar)
    /// — em {@link BigInteger} para não lidar manualmente com o transbordo de 64 bits.
    private static long roundingShiftRight(long value, int shift, boolean signed) {
        if (shift <= 0) {
            return value;
        }
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        BigInteger round = BigInteger.ONE.shiftLeft(shift - 1);
        return v.add(round).shiftRight(shift).longValue();
    }

    /// Deslocamento à esquerda por quantidade VARIÁVEL com saturação ao tamanho do elemento.
    private static long saturatingShiftLeft(long value, int shift, int esz, boolean signed) {
        BigInteger v = signed ? BigInteger.valueOf(value) : unsignedBig(value);
        return saturateToElement(v.shiftLeft(shift), esz, signed);
    }

    /// "Shift Left and Insert" (`SLI`): desloca `source` à esquerda por `shift` e insere no `Rd`
    /// ATUAL, preservando os `shift` bits BAIXOS de `Rd` (o deslocamento já traz zeros nos bits
    /// baixos, então basta unir com a máscara dos bits preservados de `current`). Movido VERBATIM de
    /// `executor64/Ir64VectorArithmeticExecutor` em B13.7 (D1 da RFC B13.2).
    private static long insertShiftLeft(long current, long source, int shift) {
        long shifted = safeShiftLeft(source, shift);
        long preserveMask = shift <= 0 ? 0L : (shift >= 64 ? -1L : (1L << shift) - 1);
        return (current & preserveMask) | shifted;
    }

    /// "Shift Right and Insert" (`SRI`): desloca `source` à direita por `shift` e insere no `Rd`
    /// ATUAL, preservando os `shift` bits ALTOS de `Rd` dentro da largura do elemento. Movido
    /// VERBATIM de `executor64/Ir64VectorArithmeticExecutor` em B13.7 (D1 da RFC B13.2).
    private static long insertShiftRight(long current, long source, int shift, int esz) {
        long shifted = logicalShiftRight(source, shift);
        int esize = 8 << esz;
        long mask = elementMask(esz);
        long preserveMask = shift >= esize ? mask : (mask & ~((1L << (esize - shift)) - 1));
        return (current & preserveMask) | shifted;
    }

    /// Multiplicação dobrada de alta ordem saturante (`SQDMULH`/`SQRDMULH`) — `esize = 8<<esz`.
    private static long doublingMultiplyHigh(long sa, long sb, int esz, boolean rounding) {
        int esize = 8 << esz;
        BigInteger product = BigInteger.valueOf(sa).multiply(BigInteger.valueOf(sb)).shiftLeft(1);
        if (rounding) {
            product = product.add(BigInteger.ONE.shiftLeft(esize - 1));
        }
        return saturateToElement(product.shiftRight(esize), esz, true);
    }

    /// `2*sext(a)*sext(b)`, saturado ao tamanho `wideEsz` (LARGO — já é o `esz+1` do chamador) —
    /// usado por `SQDMULL`/`SQDMLAL`/`SQDMLSL` (B13.10, migração D1 — cópia VERBATIM do homônimo
    /// privado de `Ir64VectorArithmeticExecutor`, que continua com a própria cópia porque
    /// `executeWideningByElement` — ainda não migrado — também precisa dela; mesma duplicação
    /// mínima já aceita para `unsignedBig`/`saturateToElement`/`signedSaturatingAdd`/
    /// `signedSaturatingSub` desde B13.5).
    private static long saturatingDoublingProduct(long sa, long sb, int wideEsz) {
        return saturateToElement(BigInteger.valueOf(sa).multiply(BigInteger.valueOf(sb)).shiftLeft(1), wideEsz, true);
    }

    /// Deslocamento por REGISTRADOR (`SSHL`/`USHL`/`SQSHL`/`UQSHL`/...): a quantidade é o BYTE BAIXO
    /// do elemento `Rm`, sempre — nunca `sext(Rm,esz)` (`ARM DDI 0487`, pseudocódigo de `SSHL`:
    /// `shift = SInt(Elem[m,e,8])`). `>=0` desloca à esquerda; `<0` desloca à direita com a
    /// MAGNITUDE.
    private static int registerShiftAmount(long rmElement) {
        return (byte) rmElement;
    }

    private static long shiftByRegister(long value, int amount, boolean signed) {
        if (amount >= 0) {
            return safeShiftLeft(value, amount);
        }
        int magnitude = -amount;
        return signed ? arithmeticShiftRight(value, magnitude) : logicalShiftRight(value, magnitude);
    }

    private static long roundingShiftByRegister(long value, int amount, boolean signed) {
        if (amount >= 0) {
            return safeShiftLeft(value, amount);
        }
        return roundingShiftRight(value, -amount, signed);
    }

    /// `SQSHL`/`UQSHL`/`SQRSHL`/`UQRSHL` por registrador: só o lado ESQUERDO (`amount>=0`) satura;
    /// o lado direito (`amount<0`) é um deslocamento comum (com ou sem arredondamento), NUNCA satura.
    private static long saturatingShiftByRegister(long value, int amount, int esz, boolean signed, boolean rounding) {
        if (amount >= 0) {
            return saturatingShiftLeft(value, amount, esz, signed);
        }
        int magnitude = -amount;
        return rounding ? roundingShiftRight(value, magnitude, signed)
                : (signed ? arithmeticShiftRight(value, magnitude) : logicalShiftRight(value, magnitude));
    }

    /// Lê a lane `lane` (elemento de `1 << esz` bytes, lane `0` = bits menos significativos) do
    /// operando que começa na palavra `baseWord`, com zero-extend em um `long`.
    public static long element(AdvSimdRegisterWords regs, int baseWord, int lane, int esz) {
        int elementBits = 8 << esz;
        int bitOffset = lane * elementBits;
        long word = regs.word(baseWord + (bitOffset / WORD_BITS));
        return (word >>> (bitOffset & WORD_BIT_MASK)) & elementMask(esz);
    }

    /// Grava a lane `lane` do operando que começa na palavra `baseWord`, sem afetar nenhum outro
    /// bit do banco.
    public static void setElement(AdvSimdRegisterWords regs, int baseWord, int lane, int esz, long value) {
        int elementBits = 8 << esz;
        int bitOffset = lane * elementBits;
        int wordIndex = baseWord + (bitOffset / WORD_BITS);
        int shift = bitOffset & WORD_BIT_MASK;
        long mask = elementMask(esz);
        long current = regs.word(wordIndex);
        regs.setWord(wordIndex, (current & ~(mask << shift)) | ((value & mask) << shift));
    }

    /// Executa uma operação "three same" sobre `lanes` elementos de `1 << esz` bytes: cada lane de
    /// `baseRd` recebe `op` aplicada às lanes correspondentes de `baseRn` e `baseRm`. Os três
    /// `base*` são índices de PALAVRA (ver {@link AdvSimdRegisterWords}), não de registrador — é o
    /// chamador que traduz `V<n>`/`D<n>`/`Q<n>` para palavra.
    ///
    /// Nada aqui zera bits fora das lanes escritas: a escrita destrutiva do A64 (bits 127:64 do
    /// `V<n>` quando o arranjo tem 64 bits) é responsabilidade do executor daquele pipeline.
    public static void threeSame(AdvSimdRegisterWords regs, AdvSimdThreeSameOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        for (int i = 0; i < lanes; i++) {
            long a = element(regs, baseRn, i, esz);
            long b = element(regs, baseRm, i, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            // `d` só é lido pelas operações RMW (`SABA`/`UABA`/`MLA`/`MLS`/`BSL`/`BIT`/`BIF` e,
            // desde B13.5, `SQRDMLAH`/`SQRDMLSH`), elemento a elemento — mesmo comportamento
            // arquitetural do executor A64.
            long d = element(regs, baseRd, i, esz);
            long result = switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
                case CMGT -> boolMask(sa > sb, esz);
                case CMHI -> boolMask(Long.compareUnsigned(a, b) > 0, esz);
                case CMGE -> boolMask(sa >= sb, esz);
                case CMHS -> boolMask(Long.compareUnsigned(a, b) >= 0, esz);
                case CMTST -> boolMask((a & b) != 0, esz);
                case CMEQ -> boolMask(a == b, esz);
                case SHADD -> (sa + sb) >> 1;
                case UHADD -> (a + b) >>> 1;
                case SHSUB -> (sa - sb) >> 1;
                case UHSUB -> (a - b) >>> 1;
                case SRHADD -> (sa + sb + 1) >> 1;
                case URHADD -> (a + b + 1) >>> 1;
                case SMAX -> Math.max(sa, sb);
                case UMAX -> Long.compareUnsigned(a, b) >= 0 ? a : b;
                case SMIN -> Math.min(sa, sb);
                case UMIN -> Long.compareUnsigned(a, b) <= 0 ? a : b;
                case SABD -> Math.abs(sa - sb);
                case UABD -> Long.compareUnsigned(a, b) >= 0 ? a - b : b - a;
                case SABA -> signExtend(d, esz) + Math.abs(sa - sb);
                case UABA -> d + (Long.compareUnsigned(a, b) >= 0 ? a - b : b - a);
                case MUL -> a * b;
                case PMUL -> polynomialMultiply8(a, b);
                case MLA -> d + a * b;
                case MLS -> d - a * b;
                case AND -> a & b;
                case BIC -> a & ~b;
                case ORR -> a | b;
                case ORN -> a | ~b;
                case EOR -> a ^ b;
                case BSL -> (d & a) | (~d & b);
                case BIT -> (a & b) | (d & ~b);
                case BIF -> (a & ~b) | (d & b);
                // B13.5 — saturantes / deslocamento por registrador (verbatim do A64):
                case SQADD -> signedSaturatingAdd(sa, sb, esz);
                case UQADD -> unsignedSaturatingAdd(a, b, esz);
                case SQSUB -> signedSaturatingSub(sa, sb, esz);
                case UQSUB -> unsignedSaturatingSub(a, b, esz);
                case SSHL -> shiftByRegister(sa, registerShiftAmount(b), true);
                case USHL -> shiftByRegister(a, registerShiftAmount(b), false);
                case SRSHL -> roundingShiftByRegister(sa, registerShiftAmount(b), true);
                case URSHL -> roundingShiftByRegister(a, registerShiftAmount(b), false);
                case SQSHL -> saturatingShiftByRegister(sa, registerShiftAmount(b), esz, true, false);
                case UQSHL -> saturatingShiftByRegister(a, registerShiftAmount(b), esz, false, false);
                case SQRSHL -> saturatingShiftByRegister(sa, registerShiftAmount(b), esz, true, true);
                case UQRSHL -> saturatingShiftByRegister(a, registerShiftAmount(b), esz, false, true);
                case SQDMULH -> doublingMultiplyHigh(sa, sb, esz, false);
                case SQRDMULH -> doublingMultiplyHigh(sa, sb, esz, true);
                // RMW: acumula/subtrai a MESMA multiplicação dobrada arredondada de `SQRDMULH` sobre
                // o `Rd` ATUAL sign-extendido, com DUAS saturações independentes.
                case SQRDMLAH -> signedSaturatingAdd(signExtend(d, esz), doublingMultiplyHigh(sa, sb, esz, true), esz);
                case SQRDMLSH -> signedSaturatingSub(signExtend(d, esz), doublingMultiplyHigh(sa, sb, esz, true), esz);
            };
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }

    /// Executa uma operação "vector/scalar × indexed element" (ver {@link AdvSimdThreeSameOp}) sobre
    /// `elements` elementos de `1 << esz` bytes: cada lane de `baseRd` recebe `op` aplicada à lane
    /// correspondente de `baseRn` e ao elemento FIXO `index` de `baseRm` (lido UMA VEZ, replicado
    /// para toda a operação — nunca `element(regs, baseRm, i, esz)`). Os `base*` são índices de
    /// PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeThreeSameByElement` (B8.19)
    /// em B13.11 (D1 da RFC B13.2) — só as 7 operações que esta classe de encoding realmente produz
    /// (`MUL`/`MLA`/`MLS`/`SQDMULH`/`SQRDMULH`/`SQRDMLAH`/`SQRDMLSH`); qualquer outro
    /// {@link AdvSimdThreeSameOp} é erro de chamador (G8 já filtrou no decoder). Nada aqui zera bits
    /// fora das lanes escritas: a escrita destrutiva do A64 é do chamador.
    public static void threeSameByElement(AdvSimdRegisterWords regs, AdvSimdThreeSameOp op, int esz,
            int elements, int baseRd, int baseRn, int baseRm, int index) {
        long b = element(regs, baseRm, index, esz);
        long sb = signExtend(b, esz);
        for (int i = 0; i < elements; i++) {
            long a = element(regs, baseRn, i, esz);
            long sa = signExtend(a, esz);
            long d = element(regs, baseRd, i, esz);
            long result = switch (op) {
                case MUL -> a * b;
                case MLA -> d + a * b;
                case MLS -> d - a * b;
                case SQDMULH -> doublingMultiplyHigh(sa, sb, esz, false);
                case SQRDMULH -> doublingMultiplyHigh(sa, sb, esz, true);
                case SQRDMLAH -> signedSaturatingAdd(signExtend(d, esz), doublingMultiplyHigh(sa, sb, esz, true), esz);
                case SQRDMLSH -> signedSaturatingSub(signExtend(d, esz), doublingMultiplyHigh(sa, sb, esz, true), esz);
                default -> throw new IllegalArgumentException(
                        "AdvSimdThreeSameOp não suportado em by-element: " + op);
            };
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }

    /// Executa uma operação "vector/scalar × indexed element" ALARGANDO (ver {@link
    /// AdvSimdWideningOp}) sobre `outputElements` elementos de `1 << (esz+1)` bytes: cada lane larga
    /// de `baseRd` recebe `op` aplicada à lane de `esz` bytes de `baseRn` (lida a partir de
    /// `laneOffset` — a forma `*2` do A64; o NEON de 32 bits passa sempre `0`) e ao elemento FIXO
    /// `index` de `baseRm` (lido UMA VEZ, replicado). Os `base*` são índices de PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeWideningByElement` (B8.19)
    /// em B13.11 (D1 da RFC B13.2) — só as 9 operações que esta classe de encoding realmente produz
    /// (`SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL`); `ABAL`/`ABDL`/
    /// `ADDL`/`SUBL`/`PMULL` não têm forma indexada real (G8 já filtrou no decoder). Resultados num
    /// buffer ANTES de qualquer escrita (mesmo motivo da E10 em {@link #widening}): `baseRd` pode
    /// coincidir com `baseRn` (o elemento de `baseRm` já foi lido fora do laço). A escrita
    /// destrutiva do A64 (e a forma escalar) é do chamador.
    public static void wideningByElement(AdvSimdRegisterWords regs, AdvSimdWideningOp op, int esz,
            int outputElements, int laneOffset, int baseRd, int baseRn, int baseRm, int index) {
        int wideEsz = esz + 1;
        long b = element(regs, baseRm, index, esz);
        long sb = signExtend(b, esz);
        long[] results = new long[outputElements];
        for (int i = 0; i < outputElements; i++) {
            long a = element(regs, baseRn, laneOffset + i, esz);
            long sa = signExtend(a, esz);
            long current = element(regs, baseRd, i, wideEsz);
            results[i] = switch (op) {
                case SMULL -> sa * sb;
                case UMULL -> a * b;
                case SMLAL -> current + sa * sb;
                case UMLAL -> current + a * b;
                case SMLSL -> current - sa * sb;
                case UMLSL -> current - a * b;
                case SQDMULL -> saturatingDoublingProduct(sa, sb, wideEsz);
                case SQDMLAL -> signedSaturatingAdd(current, saturatingDoublingProduct(sa, sb, wideEsz), wideEsz);
                case SQDMLSL -> signedSaturatingSub(current, saturatingDoublingProduct(sa, sb, wideEsz), wideEsz);
                default -> throw new IllegalArgumentException(
                        "AdvSimdWideningOp não suportado em by-element: " + op);
            };
        }
        for (int i = 0; i < outputElements; i++) {
            setElement(regs, baseRd, i, wideEsz, truncate(results[i], wideEsz));
        }
    }

    /// Executa uma operação "pairwise" (ver {@link AdvSimdPairwiseOp}): concatena o operando que
    /// começa em `baseRn` com o que começa em `baseRm`, combina pares de elementos ADJACENTES
    /// nessa sequência e grava `lanes` resultados a partir de `baseRd` (os `lanes/2` primeiros
    /// vindos de `baseRn`, os demais de `baseRm`).
    ///
    /// Como no A64, os resultados são calculados num buffer ANTES de qualquer escrita — `baseRd`
    /// pode coincidir com `baseRn`/`baseRm`. Nenhuma escrita destrutiva fora das lanes: é do
    /// chamador (o A64 zera os bits altos do `V`; o VFP32 não).
    public static void pairwise(AdvSimdRegisterWords regs, AdvSimdPairwiseOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        int half = lanes / 2;
        long[] results = new long[lanes];
        for (int i = 0; i < lanes; i++) {
            int base = i < half ? baseRn : baseRm;
            int pairBase = (i < half ? i : i - half) * 2;
            long a = element(regs, base, pairBase, esz);
            long b = element(regs, base, pairBase + 1, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            results[i] = switch (op) {
                case ADD -> a + b;
                case SMAX -> Math.max(sa, sb);
                case UMAX -> Long.compareUnsigned(a, b) >= 0 ? a : b;
                case SMIN -> Math.min(sa, sb);
                case UMIN -> Long.compareUnsigned(a, b) <= 0 ? a : b;
            };
        }
        for (int i = 0; i < lanes; i++) {
            setElement(regs, baseRd, i, esz, truncate(results[i], esz));
        }
    }

    /// Executa uma operação "shift by immediate" (ver {@link AdvSimdShiftImmediateOp}) sobre
    /// `lanes` elementos de `1 << esz` bytes: cada lane de `baseRd` recebe `op` aplicada à lane
    /// correspondente de `baseRn` (e do próprio `baseRd` para as formas RMW `SSRA`/`USRA`/`SRSRA`/
    /// `URSRA`/`SRI`/`SLI`, que ACUMULAM ou INSEREM no destino atual). `shift` já vem resolvido do
    /// decoder (`immh:immb`), na faixa `1..esize` para os deslocamentos à direita e `0..esize-1`
    /// para os à esquerda. Os `base*` são índices de PALAVRA (ver {@link AdvSimdRegisterWords}).
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeShiftImmediate` (B8.8) em
    /// B13.7 (D1 da RFC B13.2) — inclusive o tratamento especial de `SQSHLU` (fonte assinada,
    /// saturação NÃO assinada). Nada aqui zera bits fora das lanes escritas: a escrita destrutiva
    /// do A64 é do chamador.
    public static void shiftImmediate(AdvSimdRegisterWords regs, AdvSimdShiftImmediateOp op,
            int esz, int shift, int lanes, int baseRd, int baseRn) {
        for (int i = 0; i < lanes; i++) {
            long a = element(regs, baseRn, i, esz);
            long sa = signExtend(a, esz);
            long current = element(regs, baseRd, i, esz);
            long result = switch (op) {
                case SSHR -> arithmeticShiftRight(sa, shift);
                case USHR -> logicalShiftRight(a, shift);
                case SRSHR -> roundingShiftRight(sa, shift, true);
                case URSHR -> roundingShiftRight(a, shift, false);
                case SSRA -> signExtend(current, esz) + arithmeticShiftRight(sa, shift);
                case USRA -> current + logicalShiftRight(a, shift);
                case SRSRA -> signExtend(current, esz) + roundingShiftRight(sa, shift, true);
                case URSRA -> current + roundingShiftRight(a, shift, false);
                case SRI -> insertShiftRight(current, a, shift, esz);
                case SHL -> safeShiftLeft(a, shift);
                case SLI -> insertShiftLeft(current, a, shift);
                case SQSHL -> saturatingShiftLeft(sa, shift, esz, true);
                case UQSHL -> saturatingShiftLeft(a, shift, esz, false);
                // `SQSHLU`: fonte ASSINADA (desloca como `sa`, não `a`) mas saturação NÃO
                // assinada — `saturatingShiftLeft` não serve aqui porque seu único parâmetro
                // `signed` governa as DUAS coisas (interpretação do deslocamento E sinal da
                // saturação), que para `SQSHLU` divergem de propósito (achado real ao testar:
                // `unsignedBig(sa=-1)` trataria `-1` como quase `2^64`, produzindo lixo em vez de
                // saturar em `0`).
                case SQSHLU -> saturateToElement(BigInteger.valueOf(sa).shiftLeft(shift), esz, false);
            };
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }

    /// Executa uma operação "shift by immediate" ESTREITANTE (ver {@link AdvSimdShiftNarrowOp})
    /// sobre `elements` elementos: cada lane de saída (`esz` bytes) vem da lane correspondente de
    /// `baseRn` (elementos de `esz + 1` bytes) deslocada à direita por `shift` e, conforme a
    /// família, arredondada e/ou saturada. As lanes de saída são escritas a partir de
    /// `laneOffset` (o A64 usa `laneOffset` para a forma "2"/`q=1`; o NEON de 32 bits passa sempre
    /// `0`). Os `base*` são índices de PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeShiftNarrowImmediate`
    /// (B8.8) em B13.8 (D1 da RFC B13.2). Resultados calculados num buffer ANTES de qualquer
    /// escrita — `baseRd` pode coincidir com `baseRn` e ao estreitar a escrita de uma lane larga
    /// cobriria lanes estreitas ainda não lidas. A escrita destrutiva do A64 é do chamador.
    public static void shiftNarrowImmediate(AdvSimdRegisterWords regs, AdvSimdShiftNarrowOp op,
            int esz, int shift, int elements, int laneOffset, int baseRd, int baseRn) {
        int wideEsz = esz + 1;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            long wide = element(regs, baseRn, i, wideEsz);
            long signedWide = signExtend(wide, wideEsz);
            long narrow = switch (op) {
                case SHRN -> logicalShiftRight(wide, shift);
                case RSHRN -> roundingShiftRight(wide, shift, false);
                case SQSHRN -> saturateToElement(BigInteger.valueOf(arithmeticShiftRight(signedWide, shift)), esz, true);
                case UQSHRN -> saturateToElement(BigInteger.valueOf(logicalShiftRight(wide, shift)), esz, false);
                case SQSHRUN -> saturateToElement(BigInteger.valueOf(arithmeticShiftRight(signedWide, shift)), esz, false);
                case SQRSHRN -> saturateToElement(BigInteger.valueOf(roundingShiftRight(signedWide, shift, true)), esz, true);
                case UQRSHRN -> saturateToElement(BigInteger.valueOf(roundingShiftRight(wide, shift, false)), esz, false);
                case SQRSHRUN -> saturateToElement(BigInteger.valueOf(roundingShiftRight(signedWide, shift, true)), esz, false);
            };
            results[i] = truncate(narrow, esz);
        }
        for (int i = 0; i < elements; i++) {
            setElement(regs, baseRd, laneOffset + i, esz, results[i]);
        }
    }

    /// Executa uma operação "shift by immediate" ALARGANTE (ver {@link AdvSimdShiftWidenOp}) sobre
    /// `outputElements` elementos: cada lane de saída (`esz + 1` bytes) vem da lane de `baseRn`
    /// (elementos de `esz` bytes, lida a partir de `laneOffset` — a forma "2"/`q=1` do A64; o NEON
    /// de 32 bits passa sempre `0`) sinal/zero-estendida e deslocada à esquerda por `shift`. Nunca
    /// satura — o valor alargado sempre cabe. Os `base*` são índices de PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeShiftWidenImmediate` (B8.8,
    /// já bufferizado pela E10) em B13.8 (D1 da RFC B13.2). Resultados num buffer ANTES de escrever
    /// — `baseRd` pode ser `baseRn` e a escrita de uma lane larga cobriria lanes estreitas ainda
    /// não lidas. A escrita destrutiva do A64 é do chamador.
    public static void shiftWidenImmediate(AdvSimdRegisterWords regs, AdvSimdShiftWidenOp op,
            int esz, int shift, int outputElements, int laneOffset, int baseRd, int baseRn) {
        int wideEsz = esz + 1;
        long[] results = new long[outputElements];
        for (int i = 0; i < outputElements; i++) {
            long narrow = element(regs, baseRn, laneOffset + i, esz);
            long extended = op == AdvSimdShiftWidenOp.SSHLL ? signExtend(narrow, esz) : narrow;
            results[i] = truncate(safeShiftLeft(extended, shift), wideEsz);
        }
        for (int i = 0; i < outputElements; i++) {
            setElement(regs, baseRd, i, wideEsz, results[i]);
        }
    }

    /// Executa uma operação AdvSIMD "three different" ALARGANDO (ver {@link AdvSimdWideningOp},
    /// forma "Long") sobre `outputElements` elementos de `1 << (esz+1)` bytes: cada lane larga de
    /// `baseRd` recebe `op` aplicada às lanes de {@code esz} bytes de `baseRn`/`baseRm` (lidas a
    /// partir de `laneOffset` — a forma `*2` do A64; o NEON de 32 bits passa sempre `0`). As
    /// famílias RMW (`*MLAL`/`*MLSL`/`*ABAL`) leem o `Rd` ATUAL, já largo, no elemento `i` (sem
    /// `laneOffset` — o destino nunca tem forma "2" nesta função: quem seleciona metade ALTA/BAIXA
    /// do DESTINO em `SMULL2`/etc é a escrita destrutiva do chamador, não este núcleo). Os `base*`
    /// são índices de PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeWidening` (B8.7/B8.8/B8.20,
    /// já com o buffer da E10) em B13.10 (D1 da RFC B13.2), mais {@link AdvSimdWideningOp#PMULL}
    /// (`VMULL.P8` do NEON A32, que reusa {@link #polynomialMultiply8}). Resultados calculados num
    /// buffer ANTES de qualquer escrita — `baseRd` pode coincidir com `baseRn`/`baseRm` (E10):
    /// escrever a lane larga `i` cobre as lanes estreitas `2i`/`2i+1` da fonte, ainda não lidas
    /// quando `laneOffset=0`. A escrita destrutiva do A64 (e a forma escalar) é do chamador.
    public static void widening(AdvSimdRegisterWords regs, AdvSimdWideningOp op, int esz,
            int outputElements, int laneOffset, int baseRd, int baseRn, int baseRm) {
        int wideEsz = esz + 1;
        long[] results = new long[outputElements];
        for (int i = 0; i < outputElements; i++) {
            int lane = laneOffset + i;
            long a = element(regs, baseRn, lane, esz);
            long b = element(regs, baseRm, lane, esz);
            long sa = signExtend(a, esz);
            long sb = signExtend(b, esz);
            long current = element(regs, baseRd, i, wideEsz);
            results[i] = switch (op) {
                case SMULL -> sa * sb;
                case UMULL -> a * b;
                case SMLAL -> current + sa * sb;
                case UMLAL -> current + a * b;
                case SMLSL -> current - sa * sb;
                case UMLSL -> current - a * b;
                case SADDL -> sa + sb;
                case UADDL -> a + b;
                case SSUBL -> sa - sb;
                case USUBL -> a - b;
                case SABAL -> signExtend(current, wideEsz) + Math.abs(sa - sb);
                case UABAL -> current + (Long.compareUnsigned(a, b) >= 0 ? a - b : b - a);
                case SABDL -> Math.abs(sa - sb);
                case UABDL -> Long.compareUnsigned(a, b) >= 0 ? a - b : b - a;
                case SQDMULL -> saturatingDoublingProduct(sa, sb, wideEsz);
                case SQDMLAL -> signedSaturatingAdd(current, saturatingDoublingProduct(sa, sb, wideEsz), wideEsz);
                case SQDMLSL -> signedSaturatingSub(current, saturatingDoublingProduct(sa, sb, wideEsz), wideEsz);
                case PMULL -> polynomialMultiply8(a, b);
            };
        }
        for (int i = 0; i < outputElements; i++) {
            setElement(regs, baseRd, i, wideEsz, truncate(results[i], wideEsz));
        }
    }

    /// Executa uma operação AdvSIMD "three different" LARGA (ver {@link AdvSimdWideOp}, forma
    /// "Wide") sobre `elements` elementos: `baseRn` já tem elementos LARGOS (`esz+1`), `baseRm` é
    /// ESTREITO (`esz`, lido a partir de `laneOffset` — a forma `*2` do A64; o NEON de 32 bits passa
    /// sempre `0`). Os `base*` são índices de PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeWide` (B8.7, já com o buffer
    /// da E10) em B13.10 (D1 da RFC B13.2). Resultados calculados num buffer ANTES de qualquer
    /// escrita — `baseRd` pode coincidir com `baseRm` (E10: `Rd`==`Rn` sempre foi seguro, mesma lane/
    /// largura, mas o buffer cobre os dois de graça). A escrita destrutiva do A64 é do chamador.
    public static void wide(AdvSimdRegisterWords regs, AdvSimdWideOp op, int esz,
            int elements, int laneOffset, int baseRd, int baseRn, int baseRm) {
        int wideEsz = esz + 1;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            long wide = element(regs, baseRn, i, wideEsz);
            long narrow = element(regs, baseRm, laneOffset + i, esz);
            long extended = switch (op) {
                case SADDW, SSUBW -> signExtend(narrow, esz);
                case UADDW, USUBW -> narrow;
            };
            results[i] = switch (op) {
                case SADDW, UADDW -> wide + extended;
                case SSUBW, USUBW -> wide - extended;
            };
        }
        for (int i = 0; i < elements; i++) {
            setElement(regs, baseRd, i, wideEsz, truncate(results[i], wideEsz));
        }
    }

    /// Executa uma operação AdvSIMD "three different" ESTREITANDO ("half narrowing", ver
    /// {@link AdvSimdNarrowOp}) sobre `elements` elementos: `baseRn`/`baseRm` têm elementos LARGOS
    /// (`esz+1`), a lane estreita de saída `i` (`esz` bytes) é a metade ALTA da soma/diferença larga
    /// correspondente, escrita a partir de `laneOffset` (a forma `*2` do A64; o NEON de 32 bits passa
    /// sempre `0`). Os `base*` são índices de PALAVRA.
    ///
    /// `switch` movido VERBATIM de `Ir64VectorArithmeticExecutor#executeNarrow` (B8.7, já com o
    /// buffer da E10) em B13.10 (D1 da RFC B13.2). Resultados calculados num buffer ANTES de
    /// qualquer escrita — na forma `laneOffset != 0` (`*2` do A64) `baseRd` pode coincidir com
    /// `baseRn`/`baseRm` e a escrita da lane estreita `laneOffset+i` cobriria uma lane larga ainda
    /// não lida. A escrita destrutiva do A64 é do chamador.
    public static void narrow(AdvSimdRegisterWords regs, AdvSimdNarrowOp op, int esz,
            int elements, int laneOffset, int baseRd, int baseRn, int baseRm) {
        int narrowBits = 8 << esz;
        long rounding = 1L << (narrowBits - 1);
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            long a = element(regs, baseRn, i, esz + 1);
            long b = element(regs, baseRm, i, esz + 1);
            long sum = switch (op) {
                case ADDHN -> a + b;
                case RADDHN -> a + b + rounding;
                case SUBHN -> a - b;
                case RSUBHN -> a - b + rounding;
            };
            results[i] = truncate(sum >>> narrowBits, esz);
        }
        for (int i = 0; i < elements; i++) {
            setElement(regs, baseRd, laneOffset + i, esz, results[i]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // PONTO FLUTUANTE — B13.6 (migração D1 da RFC B13.2). Primeiro caminho FP do núcleo. As ops
    // "three same"/"pairwise" de FP do A64 (B8.9) vivem SÓ aqui a partir daquela task; o
    // `Ir64VectorFpArithmeticExecutor` só delega. Sem modelo de `FPSCR`/`FPCR` (RMode/FZ/exceções)
    // — paridade consciente com o escalar (B3.8/B8.5) e com o inteiro saturante acima (`QC`).
    //
    // MEIA PRECISÃO (`esz=1`, binary16) — B19.5.1. Java não tem tipo `binary16`; o caminho `esz=1`
    // calcula em `float` e estreita o resultado UMA vez com round-to-nearest-even
    // ({@link #halfBits}). Para `+`/`-`/`*`/`/`/`√` isso é correto por construção (regra do
    // arredondamento duplo inócuo: binary32 tem `2·11+2 = 24` bits de significando), verificado por
    // teste diferencial. `VMLA.F16`/`VMLS.F16` NÃO fundidos são a exceção: como `f16 × f16` cabe
    // exato em binary32, "estreitar em float e somar" colapsaria no valor FUNDIDO — por isso o
    // produto é explicitamente estreitado a binary16 ANTES do acumulador (dois arredondamentos F16,
    // como o hardware).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /// Bits crus (zero-estendidos a 64) de um `float` — usado para gravar uma lane F32.
    public static long floatBits(float value) {
        return Float.floatToRawIntBits(value) & 0xFFFF_FFFFL;
    }

    /// Bits crus de um `double` — usado para gravar uma lane F64.
    public static long doubleBits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    /// Bits binary16 (16 bits, zero-estendidos a 64) de `value`, arredondado round-to-nearest-even
    /// — usado para gravar uma lane F16 (`FEAT_FP16` no A64, `VADD.F16`/... no NEON A32). A máscara
    /// `& 0xFFFF` é OBRIGATÓRIA: {@link Float#floatToFloat16(float)} devolve `short` e a promoção a
    /// `long` sign-estende, corrompendo as lanes vizinhas na escrita quando o bit 15 está setado
    /// (qualquer negativo, todo `NaN`).
    public static long halfBits(float value) {
        return Float.floatToFloat16(value) & 0xFFFFL;
    }

    /// `float` (conversão exata — binary16 ⊂ binary32) a partir dos 16 bits binary16 crus em
    /// `bits` (bits acima do 15 são ignorados).
    public static float halfToFloat(long bits) {
        return Float.float16ToFloat((short) bits);
    }

    /// `FPMulX` (`ARM DDI 0487`): `0 * Infinito`/`Infinito * 0` devolve `2.0` com o sinal do
    /// produto dos operandos, em vez do `NaN` que a multiplicação IEEE normal produziria — único
    /// desvio de {@code a * b}.
    public static float mulX(float a, float b) {
        if ((a == 0f && Float.isInfinite(b)) || (Float.isInfinite(a) && b == 0f)) {
            int sign = (Float.floatToRawIntBits(a) ^ Float.floatToRawIntBits(b)) & Integer.MIN_VALUE;
            return Float.intBitsToFloat(sign | Float.floatToRawIntBits(2.0f));
        }
        return a * b;
    }

    /// @see #mulX(float, float)
    public static double mulX(double a, double b) {
        if ((a == 0.0 && Double.isInfinite(b)) || (Double.isInfinite(a) && b == 0.0)) {
            long sign = (Double.doubleToRawLongBits(a) ^ Double.doubleToRawLongBits(b)) & Long.MIN_VALUE;
            return Double.longBitsToDouble(sign | Double.doubleToRawLongBits(2.0));
        }
        return a * b;
    }

    /// `FPMaxNum` (`ARM DDI 0487`): se exatamente um operando é `NaN`, devolve o OUTRO; se os dois
    /// são `NaN`, devolve `NaN`; senão, `Math.max` normal (mesma semântica de sinal de zero do
    /// `MAX`). `Ir64FpExecutor` (escalar) delega a este método.
    public static float maxNum(float a, float b) {
        if (Float.isNaN(a)) {
            return Float.isNaN(b) ? a : b;
        }
        return Float.isNaN(b) ? a : Math.max(a, b);
    }

    /// @see #maxNum(float, float)
    public static double maxNum(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? a : b;
        }
        return Double.isNaN(b) ? a : Math.max(a, b);
    }

    /// `FPMinNum` — espelho de {@link #maxNum(float, float)} com `Math.min`.
    public static float minNum(float a, float b) {
        if (Float.isNaN(a)) {
            return Float.isNaN(b) ? a : b;
        }
        return Float.isNaN(b) ? a : Math.min(a, b);
    }

    /// @see #minNum(float, float)
    public static double minNum(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? a : b;
        }
        return Double.isNaN(b) ? a : Math.min(a, b);
    }

    /// Executa uma operação "three same" de PONTO FLUTUANTE sobre `lanes` elementos de `1 << esz`
    /// bytes (`esz` `1` = F16, `2` = F32, `3` = F64): cada lane de `baseRd` recebe `op` aplicada às
    /// lanes correspondentes de `baseRn`/`baseRm` (e do próprio `baseRd` para as formas RMW
    /// `MLA`/`MLS`/`FMLA`/`FMLS`). Os três `base*` são índices de PALAVRA.
    ///
    /// Nada aqui zera bits fora das lanes escritas: a escrita destrutiva do A64 é do chamador.
    /// Sem escrita destrutiva DENTRO do laço tampouco. `esz` fora de `{1,2,3}` é
    /// {@link IllegalArgumentException} (antes de B19.5.1 um `esz` inválido era calculado
    /// SILENCIOSAMENTE como binary64).
    public static void fpThreeSame(AdvSimdRegisterWords regs, AdvSimdFpThreeSameOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        for (int i = 0; i < lanes; i++) {
            long anBits = element(regs, baseRn, i, esz);
            long bmBits = element(regs, baseRm, i, esz);
            long dBits = element(regs, baseRd, i, esz);
            long resultBits = switch (esz) {
                case 1 -> halfThreeSame(op, anBits, bmBits, dBits);
                case 2 -> singleThreeSame(op, anBits, bmBits, dBits);
                case 3 -> doubleThreeSame(op, anBits, bmBits, dBits);
                default -> throw new IllegalArgumentException("esz inválido para FP three-same: " + esz);
            };
            setElement(regs, baseRd, i, esz, resultBits);
        }
    }

    /// Ramo F16 (`esz=1`) de {@link #fpThreeSame}: calcula em `float` e estreita uma vez
    /// ({@link #halfBits}). `MLA`/`MLS` estreitam o produto a binary16 ANTES do acumulador (dois
    /// arredondamentos F16, NÃO fundido); `FMLA`/`FMLS` são fundidos ({@link Math#fma(float,float,float)}).
    private static long halfThreeSame(AdvSimdFpThreeSameOp op, long anBits, long bmBits, long dBits) {
        float a = halfToFloat(anBits);
        float b = halfToFloat(bmBits);
        float current = halfToFloat(dBits);
        return switch (op) {
            case ADD -> halfBits(a + b);
            case SUB -> halfBits(a - b);
            case MUL -> halfBits(a * b);
            case DIV -> halfBits(a / b);
            case MAX -> halfBits(Math.max(a, b));
            case MIN -> halfBits(Math.min(a, b));
            case MAXNM -> halfBits(maxNum(a, b));
            case MINNM -> halfBits(minNum(a, b));
            case MULX -> halfBits(mulX(a, b));
            // NÃO fundido: produto estreitado a binary16, depois acumula/subtrai (dois
            // arredondamentos F16) — `VMLA.F16`/`VMLS.F16` NEON.
            case MLA -> halfBits(current + halfToFloat(halfBits(a * b)));
            case MLS -> halfBits(current - halfToFloat(halfBits(a * b)));
            // FUNDIDO (arredondamento binary16 único) — `VFMA.F16`/`VFMS.F16` e `FMLA_h`/`FMLS_h` A64.
            case FMLA -> halfBits(Math.fma(a, b, current));
            case FMLS -> halfBits(Math.fma(-a, b, current));
            case CMEQ -> boolMask(a == b, 1);
            case CMGE -> boolMask(a >= b, 1);
            case CMGT -> boolMask(a > b, 1);
            case FACGE -> boolMask(Math.abs(a) >= Math.abs(b), 1);
            case FACGT -> boolMask(Math.abs(a) > Math.abs(b), 1);
            case ABD -> halfBits(Math.abs(a - b));
            case RECPS -> halfBits(2.0f - a * b);
            case RSQRTS -> halfBits((3.0f - a * b) / 2.0f);
        };
    }

    /// Ramo F32 (`esz=2`) de {@link #fpThreeSame} — movido VERBATIM de B13.6.
    private static long singleThreeSame(AdvSimdFpThreeSameOp op, long anBits, long bmBits, long dBits) {
        float a = Float.intBitsToFloat((int) anBits);
        float b = Float.intBitsToFloat((int) bmBits);
        float current = Float.intBitsToFloat((int) dBits);
        return switch (op) {
            case ADD -> floatBits(a + b);
            case SUB -> floatBits(a - b);
            case MUL -> floatBits(a * b);
            case DIV -> floatBits(a / b);
            case MAX -> floatBits(Math.max(a, b));
            case MIN -> floatBits(Math.min(a, b));
            case MAXNM -> floatBits(maxNum(a, b));
            case MINNM -> floatBits(minNum(a, b));
            case MULX -> floatBits(mulX(a, b));
            // NÃO fundido (dois arredondamentos) — `VMLA.F32`/`VMLS.F32` NEON.
            case MLA -> floatBits(a * b + current);
            case MLS -> floatBits(current - a * b);
            // FUNDIDO (arredondamento único) — `VFMA.F32`/`VFMS.F32` e `FMLA_v`/`FMLS_v` A64.
            case FMLA -> floatBits(Math.fma(a, b, current));
            case FMLS -> floatBits(Math.fma(-a, b, current));
            case CMEQ -> boolMask(a == b, 2);
            case CMGE -> boolMask(a >= b, 2);
            case CMGT -> boolMask(a > b, 2);
            case FACGE -> boolMask(Math.abs(a) >= Math.abs(b), 2);
            case FACGT -> boolMask(Math.abs(a) > Math.abs(b), 2);
            case ABD -> floatBits(Math.abs(a - b));
            case RECPS -> floatBits(2.0f - a * b);
            case RSQRTS -> floatBits((3.0f - a * b) / 2.0f);
        };
    }

    /// Ramo F64 (`esz=3`) de {@link #fpThreeSame} — movido VERBATIM de B13.6.
    private static long doubleThreeSame(AdvSimdFpThreeSameOp op, long anBits, long bmBits, long dBits) {
        double a = Double.longBitsToDouble(anBits);
        double b = Double.longBitsToDouble(bmBits);
        double current = Double.longBitsToDouble(dBits);
        return switch (op) {
            case ADD -> doubleBits(a + b);
            case SUB -> doubleBits(a - b);
            case MUL -> doubleBits(a * b);
            case DIV -> doubleBits(a / b);
            case MAX -> doubleBits(Math.max(a, b));
            case MIN -> doubleBits(Math.min(a, b));
            case MAXNM -> doubleBits(maxNum(a, b));
            case MINNM -> doubleBits(minNum(a, b));
            case MULX -> doubleBits(mulX(a, b));
            case MLA -> doubleBits(a * b + current);
            case MLS -> doubleBits(current - a * b);
            case FMLA -> doubleBits(Math.fma(a, b, current));
            case FMLS -> doubleBits(Math.fma(-a, b, current));
            case CMEQ -> boolMask(a == b, 3);
            case CMGE -> boolMask(a >= b, 3);
            case CMGT -> boolMask(a > b, 3);
            case FACGE -> boolMask(Math.abs(a) >= Math.abs(b), 3);
            case FACGT -> boolMask(Math.abs(a) > Math.abs(b), 3);
            case ABD -> doubleBits(Math.abs(a - b));
            case RECPS -> doubleBits(2.0 - a * b);
            case RSQRTS -> doubleBits((3.0 - a * b) / 2.0);
        };
    }

    /// Executa uma operação "vector/scalar × indexed element" de PONTO FLUTUANTE (ver {@link
    /// AdvSimdFpThreeSameOp}) sobre `elements` elementos de `1 << esz` bytes: cada lane de `baseRd`
    /// recebe `op` aplicada à lane correspondente de `baseRn` e ao elemento FIXO `index` de
    /// `baseRm` (lido UMA VEZ, replicado — nunca `element(regs, baseRm, i, esz)`). Os `base*` são
    /// índices de PALAVRA.
    ///
    /// Reusa os mesmos ramos F16/F32/F64 de {@link #fpThreeSame} (`halfThreeSame`/
    /// `singleThreeSame`/`doubleThreeSame`) — só `MUL`/`MLA`/`MLS`/`MULX` são válidas aqui (G8 já
    /// filtra no decoder: nem A32 nem A64 produzem outro valor nesta classe). Nada aqui zera bits
    /// fora das lanes escritas: a escrita destrutiva do A64 é do chamador.
    public static void fpThreeSameByElement(AdvSimdRegisterWords regs, AdvSimdFpThreeSameOp op, int esz,
            int elements, int baseRd, int baseRn, int baseRm, int index) {
        long bBits = element(regs, baseRm, index, esz);
        for (int i = 0; i < elements; i++) {
            long aBits = element(regs, baseRn, i, esz);
            long dBits = element(regs, baseRd, i, esz);
            long resultBits = switch (esz) {
                case 1 -> halfThreeSame(op, aBits, bBits, dBits);
                case 2 -> singleThreeSame(op, aBits, bBits, dBits);
                case 3 -> doubleThreeSame(op, aBits, bBits, dBits);
                default -> throw new IllegalArgumentException("esz inválido para FP three-same: " + esz);
            };
            setElement(regs, baseRd, i, esz, resultBits);
        }
    }

    /// Executa uma operação "pairwise" de PONTO FLUTUANTE (ver {@link AdvSimdFpPairwiseOp}):
    /// concatena `baseRn` com `baseRm`, combina pares de elementos ADJACENTES nessa sequência e
    /// grava `lanes` resultados a partir de `baseRd` (os `lanes/2` primeiros vindos de `baseRn`, os
    /// demais de `baseRm`). Resultados calculados num buffer ANTES de qualquer escrita — `baseRd`
    /// pode coincidir com `baseRn`/`baseRm`. Sem escrita destrutiva: é do chamador.
    public static void fpPairwise(AdvSimdRegisterWords regs, AdvSimdFpPairwiseOp op, int esz, int lanes,
            int baseRd, int baseRn, int baseRm) {
        int half = lanes / 2;
        long[] results = new long[lanes];
        for (int i = 0; i < lanes; i++) {
            int base = i < half ? baseRn : baseRm;
            int pairBase = (i < half ? i : i - half) * 2;
            results[i] = fpCombinePair(op, element(regs, base, pairBase, esz),
                    element(regs, base, pairBase + 1, esz), esz);
        }
        for (int i = 0; i < lanes; i++) {
            setElement(regs, baseRd, i, esz, results[i]);
        }
    }

    /// Combina um par de elementos FP de tamanho `esz` (`1` = F16, `2` = F32, `3` = F64) segundo
    /// `op` — mesma semântica nas formas vetorial e escalar: `MAX`/`MIN` propagam `NaN`
    /// (`Math.max`/`Math.min`), `MAXNM`/`MINNM` só quando os DOIS são `NaN`, `ADD` é `+` IEEE.
    /// Público para a forma ESCALAR do A64 (`FADDP_s`/... , B19.2) reusar sem duplicar. `esz` fora
    /// de `{1,2,3}` é {@link IllegalArgumentException} (antes de B19.5.1 qualquer `esz != 2` era
    /// tratado como binary64).
    public static long fpCombinePair(AdvSimdFpPairwiseOp op, long aBits, long bBits, int esz) {
        return switch (esz) {
            case 1 -> {
                float a = halfToFloat(aBits);
                float b = halfToFloat(bBits);
                yield switch (op) {
                    case ADD -> halfBits(a + b);
                    case MAX -> halfBits(Math.max(a, b));
                    case MIN -> halfBits(Math.min(a, b));
                    case MAXNM -> halfBits(maxNum(a, b));
                    case MINNM -> halfBits(minNum(a, b));
                };
            }
            case 2 -> {
                float a = Float.intBitsToFloat((int) aBits);
                float b = Float.intBitsToFloat((int) bBits);
                yield switch (op) {
                    case ADD -> floatBits(a + b);
                    case MAX -> floatBits(Math.max(a, b));
                    case MIN -> floatBits(Math.min(a, b));
                    case MAXNM -> floatBits(maxNum(a, b));
                    case MINNM -> floatBits(minNum(a, b));
                };
            }
            case 3 -> {
                double a = Double.longBitsToDouble(aBits);
                double b = Double.longBitsToDouble(bBits);
                yield switch (op) {
                    case ADD -> doubleBits(a + b);
                    case MAX -> doubleBits(Math.max(a, b));
                    case MIN -> doubleBits(Math.min(a, b));
                    case MAXNM -> doubleBits(maxNum(a, b));
                    case MINNM -> doubleBits(minNum(a, b));
                };
            }
            default -> throw new IllegalArgumentException("esz inválido para FP pairwise: " + esz);
        };
    }

    /// Rotação de `90°` (`VCADD`/`FCADD` com `rotate=90`; `VCMLA`/`FCMLA` com `rotate=0`/`90`... —
    /// ver os métodos que consomem esta constante para o mapeamento exato por instrução).
    public static final int COMPLEX_ROTATE_0 = 0;
    /// @see #COMPLEX_ROTATE_0
    public static final int COMPLEX_ROTATE_90 = 90;
    /// @see #COMPLEX_ROTATE_0
    public static final int COMPLEX_ROTATE_180 = 180;
    /// @see #COMPLEX_ROTATE_0
    public static final int COMPLEX_ROTATE_270 = 270;

    /// Executa `VCADD`/`FCADD` (`FEAT_FCMA`) sobre `lanes` elementos de `1 << esz` bytes (`esz` `1` =
    /// F16, `2` = F32, `3` = F64): trata pares de lanes ADJACENTES (par = parte real, ímpar = parte
    /// imaginária) do operando começando em `baseRd` como recebendo a soma complexa de `baseRn` com
    /// `baseRm` ROTACIONADO por `rotation` (`90` ou `270`, ARM DDI 0487 `FComplexAddImpl`):
    /// `rotation=90`: `re' = a_re - b_im`, `im' = a_im + b_re`;
    /// `rotation=270`: `re' = a_re + b_im`, `im' = a_im - b_re`.
    /// `lanes` PRECISA ser par. Nada aqui zera bits fora das lanes escritas.
    public static void fpComplexAdd(AdvSimdRegisterWords regs, int esz, int lanes, int baseRd, int baseRn,
            int baseRm, int rotation) {
        for (int pair = 0; pair < lanes; pair += 2) {
            long aReBits = element(regs, baseRn, pair, esz);
            long aImBits = element(regs, baseRn, pair + 1, esz);
            long bReBits = element(regs, baseRm, pair, esz);
            long bImBits = element(regs, baseRm, pair + 1, esz);
            long reResult = complexAddPart(esz, aReBits, aImBits, bReBits, bImBits, rotation, true);
            long imResult = complexAddPart(esz, aReBits, aImBits, bReBits, bImBits, rotation, false);
            setElement(regs, baseRd, pair, esz, reResult);
            setElement(regs, baseRd, pair + 1, esz, imResult);
        }
    }

    private static long complexAddPart(int esz, long aReBits, long aImBits, long bReBits, long bImBits,
            int rotation, boolean real) {
        boolean rot90 = rotation == COMPLEX_ROTATE_90;
        return switch (esz) {
            case 1 -> {
                float aRe = halfToFloat(aReBits), aIm = halfToFloat(aImBits);
                float bRe = halfToFloat(bReBits), bIm = halfToFloat(bImBits);
                yield halfBits(real ? (rot90 ? aRe - bIm : aRe + bIm) : (rot90 ? aIm + bRe : aIm - bRe));
            }
            case 2 -> {
                float aRe = Float.intBitsToFloat((int) aReBits), aIm = Float.intBitsToFloat((int) aImBits);
                float bRe = Float.intBitsToFloat((int) bReBits), bIm = Float.intBitsToFloat((int) bImBits);
                yield floatBits(real ? (rot90 ? aRe - bIm : aRe + bIm) : (rot90 ? aIm + bRe : aIm - bRe));
            }
            case 3 -> {
                double aRe = Double.longBitsToDouble(aReBits), aIm = Double.longBitsToDouble(aImBits);
                double bRe = Double.longBitsToDouble(bReBits), bIm = Double.longBitsToDouble(bImBits);
                yield doubleBits(real ? (rot90 ? aRe - bIm : aRe + bIm) : (rot90 ? aIm + bRe : aIm - bRe));
            }
            default -> throw new IllegalArgumentException("esz inválido para complex add: " + esz);
        };
    }

    /// Executa `VCMLA`/`FCMLA` (`FEAT_FCMA`) sobre `lanes` elementos de `1 << esz` bytes: multiply-
    /// accumulate complexo FUNDIDO (`Math.fma`, um arredondamento — mesma convenção `FMLA`/`FMLS` do
    /// resto do núcleo) de pares de lanes ADJACENTES de `baseRn`/`baseRm` acumulando em `baseRd`
    /// (lido E escrito). `rotation` (`0`/`90`/`180`/`270`, ARM DDI 0487 `FComplexMulAdd`) escolhe QUAL
    /// parcela do produto complexo esta chamada contribui — duas chamadas com rotações
    /// complementares (tipicamente `0`+`90`) completam um multiply-accumulate complexo cheio:
    /// `0`:   `d_re += a_re*b_re`,   `d_im += a_re*b_im`
    /// `90`:  `d_re -= a_im*b_im`,   `d_im += a_im*b_re`
    /// `180`: `d_re -= a_re*b_re`,   `d_im -= a_re*b_im`
    /// `270`: `d_re += a_im*b_im`,   `d_im -= a_im*b_re`
    /// `lanes` PRECISA ser par. Nada aqui zera bits fora das lanes escritas.
    public static void fpComplexMultiplyAccumulate(AdvSimdRegisterWords regs, int esz, int lanes, int baseRd,
            int baseRn, int baseRm, int rotation) {
        for (int pair = 0; pair < lanes; pair += 2) {
            long aReBits = element(regs, baseRn, pair, esz);
            long aImBits = element(regs, baseRn, pair + 1, esz);
            long bReBits = element(regs, baseRm, pair, esz);
            long bImBits = element(regs, baseRm, pair + 1, esz);
            long dReBits = element(regs, baseRd, pair, esz);
            long dImBits = element(regs, baseRd, pair + 1, esz);
            long reResult = complexMlaPart(esz, aReBits, aImBits, bReBits, bImBits, dReBits, dImBits, rotation, true);
            long imResult = complexMlaPart(esz, aReBits, aImBits, bReBits, bImBits, dReBits, dImBits, rotation, false);
            setElement(regs, baseRd, pair, esz, reResult);
            setElement(regs, baseRd, pair + 1, esz, imResult);
        }
    }

    /// Executa `VCMLA_scalar`/`FCMLA` (por elemento): como {@link #fpComplexMultiplyAccumulate}, mas
    /// `b` é um único número complexo FIXO lido de `baseRm` no par `index`/`index+1` (lido UMA vez,
    /// replicado), nunca `element(regs, baseRm, pair, esz)`.
    public static void fpComplexMultiplyAccumulateByElement(AdvSimdRegisterWords regs, int esz, int lanes,
            int baseRd, int baseRn, int baseRm, int index, int rotation) {
        long bReBits = element(regs, baseRm, index * 2, esz);
        long bImBits = element(regs, baseRm, index * 2 + 1, esz);
        for (int pair = 0; pair < lanes; pair += 2) {
            long aReBits = element(regs, baseRn, pair, esz);
            long aImBits = element(regs, baseRn, pair + 1, esz);
            long dReBits = element(regs, baseRd, pair, esz);
            long dImBits = element(regs, baseRd, pair + 1, esz);
            long reResult = complexMlaPart(esz, aReBits, aImBits, bReBits, bImBits, dReBits, dImBits, rotation, true);
            long imResult = complexMlaPart(esz, aReBits, aImBits, bReBits, bImBits, dReBits, dImBits, rotation, false);
            setElement(regs, baseRd, pair, esz, reResult);
            setElement(regs, baseRd, pair + 1, esz, imResult);
        }
    }

    private static long complexMlaPart(int esz, long aReBits, long aImBits, long bReBits, long bImBits,
            long dReBits, long dImBits, int rotation, boolean real) {
        return switch (esz) {
            case 1 -> {
                float aRe = halfToFloat(aReBits), aIm = halfToFloat(aImBits);
                float bRe = halfToFloat(bReBits), bIm = halfToFloat(bImBits);
                float current = halfToFloat(real ? dReBits : dImBits);
                yield halfBits(complexMlaFma(rotation, real, aRe, aIm, bRe, bIm, current));
            }
            case 2 -> {
                float aRe = Float.intBitsToFloat((int) aReBits), aIm = Float.intBitsToFloat((int) aImBits);
                float bRe = Float.intBitsToFloat((int) bReBits), bIm = Float.intBitsToFloat((int) bImBits);
                float current = Float.intBitsToFloat((int) (real ? dReBits : dImBits));
                yield floatBits(complexMlaFma(rotation, real, aRe, aIm, bRe, bIm, current));
            }
            case 3 -> {
                double aRe = Double.longBitsToDouble(aReBits), aIm = Double.longBitsToDouble(aImBits);
                double bRe = Double.longBitsToDouble(bReBits), bIm = Double.longBitsToDouble(bImBits);
                double current = Double.longBitsToDouble(real ? dReBits : dImBits);
                yield doubleBits(complexMlaFmaDouble(rotation, real, aRe, aIm, bRe, bIm, current));
            }
            default -> throw new IllegalArgumentException("esz inválido para complex multiply-accumulate: " + esz);
        };
    }

    /// `FComplexMulAdd` (ramo `float`) — ver a tabela de {@link #fpComplexMultiplyAccumulate}.
    private static float complexMlaFma(int rotation, boolean real, float aRe, float aIm, float bRe, float bIm,
            float current) {
        return switch (rotation) {
            case COMPLEX_ROTATE_0 -> real ? Math.fma(aRe, bRe, current) : Math.fma(aRe, bIm, current);
            case COMPLEX_ROTATE_90 -> real ? Math.fma(-aIm, bIm, current) : Math.fma(aIm, bRe, current);
            case COMPLEX_ROTATE_180 -> real ? Math.fma(-aRe, bRe, current) : Math.fma(-aRe, bIm, current);
            case COMPLEX_ROTATE_270 -> real ? Math.fma(aIm, bIm, current) : Math.fma(-aIm, bRe, current);
            default -> throw new IllegalArgumentException("rotation inválido: " + rotation);
        };
    }

    /// @see #complexMlaFma(int, boolean, float, float, float, float, float)
    private static double complexMlaFmaDouble(int rotation, boolean real, double aRe, double aIm, double bRe,
            double bIm, double current) {
        return switch (rotation) {
            case COMPLEX_ROTATE_0 -> real ? Math.fma(aRe, bRe, current) : Math.fma(aRe, bIm, current);
            case COMPLEX_ROTATE_90 -> real ? Math.fma(-aIm, bIm, current) : Math.fma(aIm, bRe, current);
            case COMPLEX_ROTATE_180 -> real ? Math.fma(-aRe, bRe, current) : Math.fma(-aRe, bIm, current);
            case COMPLEX_ROTATE_270 -> real ? Math.fma(aIm, bIm, current) : Math.fma(-aIm, bRe, current);
            default -> throw new IllegalArgumentException("rotation inválido: " + rotation);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CONVERSÃO FP ↔ PONTO FIXO — B13.8 (migração D1 da RFC B13.2). `SCVTF`/`UCVTF`/`FCVTZS`/
    // `FCVTZU` na forma AdvSIMD com fator de escala `2^fractionBits` (`VCVT` fixo↔float F32 no NEON
    // de 32 bits; `@fcvt_fixed` escalar/vetorial no A64). O arredondamento é SEMPRE toward-zero
    // (é o que o encoding desta forma define nos dois lados — não há variante de direção), e a
    // saturação usa os mesmos helpers do escalar (`saturateToInteger`), movidos para cá em vez de
    // duplicados. Sem modelo de `FPSCR`/`FPCR`.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /// Executa `SCVTF`/`UCVTF` (`toFloat=true`: inteiro `esz`-wide → FP `esz`-wide, depois
    /// `/ 2^fractionBits`) ou `FCVTZS`/`FCVTZU` (`toFloat=false`: FP `* 2^fractionBits`, arredonda
    /// para zero, satura → inteiro) sobre `lanes` elementos de `1 << esz` bytes (`esz` `1` = 16
    /// bits/`FEAT_FP16`, `2` = 32 bits, `3` = 64 bits). `signed` escolhe a variante assinada. Os
    /// `base*` são índices de PALAVRA; a leitura e a escrita são na MESMA largura (`esz`), então
    /// não há buffer — nenhuma lane escrita cobre uma ainda não lida. A escrita destrutiva do A64
    /// é do chamador.
    ///
    /// Corpo movido VERBATIM de `Ir64VectorFpArithmeticExecutor#executeConvertFixedPoint` (B19.3/
    /// B19.4) em B13.8. **Achado da B19.5.3**: o `esz==2` (`(int) inputBits` sign-estende
    /// corretamente porque a largura de `int` já é 32 bits) não generaliza para `esz==1` — um
    /// `(int) inputBits` de 16 bits mascarados NÃO sign-estende (o padrão `0x8000` viraria `32768`
    /// em vez de `-32768`); precisa do cast intermediário por `short`.
    public static void convertFixedPoint(AdvSimdRegisterWords regs, int esz, int fractionBits,
            boolean toFloat, boolean signed, int lanes, int baseRd, int baseRn) {
        boolean wide = esz == 3;
        double scale = Math.scalb(1.0, fractionBits);
        for (int i = 0; i < lanes; i++) {
            long inputBits = element(regs, baseRn, i, esz);
            long resultBits;
            if (toFloat) {
                double asDouble;
                if (wide) {
                    asDouble = signed ? (double) inputBits : unsignedLongToDouble(inputBits);
                } else if (esz == 1) {
                    asDouble = signed ? (double) (short) inputBits : (double) inputBits;
                } else {
                    asDouble = signed ? (double) (int) inputBits : (double) inputBits;
                }
                double scaled = asDouble / scale;
                resultBits = esz == 1 ? halfBits((float) scaled) : esz == 2 ? floatBits((float) scaled) : doubleBits(scaled);
            } else {
                double value = esz == 1 ? halfToFloat(inputBits)
                        : esz == 2 ? Float.intBitsToFloat((int) inputBits) : Double.longBitsToDouble(inputBits);
                double scaled = value * scale;
                double rounded = roundTowardZeroForConversion(scaled);
                long converted = esz == 1 ? saturateToHalfwordInteger(rounded, signed)
                        : saturateToInteger(rounded, signed, wide);
                resultBits = converted & (wide ? -1L : esz == 1 ? 0xFFFFL : 0xFFFF_FFFFL);
            }
            setElement(regs, baseRd, i, esz, resultBits);
        }
    }

    /// Limite superior/inferior de um inteiro de 16 bits assinado/sem sinal (G6 — nomeado em vez
    /// de literal solto em {@link #saturateToHalfwordInteger}).
    private static final double HALFWORD_SIGNED_MIN = -32768.0;
    private static final double HALFWORD_SIGNED_MAX = 32767.0;
    private static final double HALFWORD_UNSIGNED_MAX = 65535.0;

    /// {@link #saturateToInteger} satura para 32/64 bits (`wide`); `FCVTZS_f`/`FCVTZU_f` de meia
    /// precisão (`FEAT_FP16`, B19.5.3) precisam do limite de 16 bits, que aquele método não
    /// expressa — variante dedicada em vez de sobrecarregar `wide` com um terceiro estado.
    private static long saturateToHalfwordInteger(double rounded, boolean signed) {
        if (Double.isNaN(rounded)) {
            return 0L;
        }
        double minValue = signed ? HALFWORD_SIGNED_MIN : 0.0;
        double maxValue = signed ? HALFWORD_SIGNED_MAX : HALFWORD_UNSIGNED_MAX;
        return (long) Math.max(minValue, Math.min(maxValue, rounded));
    }

    /// Arredonda `value` na direção do zero, deixando `NaN`/infinito INTACTOS (quem chama —
    /// {@link #saturateToInteger} — precisa deles para saturar corretamente: `NaN`→`0`,
    /// infinito→limite da largura). Equivale a `Ir64FpExecutor.roundToIntegralForConversion(value,
    /// TOWARD_ZERO)`: esta forma de `VCVT`/`@fcvt_fixed` só arredonda para zero, então a direção é
    /// fixa e o `enum` do pipeline `ir64` não precisa ser importado aqui.
    private static double roundTowardZeroForConversion(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /// Converte um `long` de 64 bits SEM SINAL (bit mais alto pode estar setado) para o `double`
    /// mais próximo — truque padrão (deslocar 1 bit sem sinal, escalar de volta, somar o bit
    /// perdido) já que `(double) long` do Java sempre assume sinal. Vive aqui desde B13.8 (era de
    /// `Ir64FpExecutor`, que agora delega); usado pelas conversões inteiro→FP dos dois pipelines.
    public static double unsignedLongToDouble(long value) {
        if (value >= 0) {
            return (double) value;
        }
        return ((double) (value >>> 1)) * 2.0 + (value & 1L);
    }

    /// `FPToFixed` (`ARM DDI 0487`): arredonda+satura `rounded` (já na direção certa) para a
    /// largura/sinal pedida. `NaN`→`0`; fora da faixa→o limite mais próximo. Vive aqui desde B13.8
    /// (era de `Ir64FpExecutor`, que agora delega); fonte ÚNICA da saturação FP→int dos dois
    /// pipelines.
    public static long saturateToInteger(double rounded, boolean signed, boolean wide) {
        if (Double.isNaN(rounded)) {
            return 0L;
        }
        int bits = wide ? 64 : 32;
        double minValue = signed ? -Math.scalb(1.0, bits - 1) : 0.0;
        double maxValue = signed ? Math.scalb(1.0, bits - 1) - 1.0 : Math.scalb(1.0, bits) - 1.0;
        double clamped = Math.max(minValue, Math.min(maxValue, rounded));
        if (wide && !signed) {
            return doubleToUnsignedLongBits(clamped);
        }
        // signed64: o cast (double->long) do Java já satura em Long.MIN/MAX_VALUE (JLS 5.1.3);
        // signed32/unsigned32 cabem folgados na faixa de `long`, o chamador mascara os 32 bits
        // altos ao escrever.
        return (long) clamped;
    }

    /// `clamped` já está em `[0, 2^64-1]` — para a metade superior (`>= 2^63`) o cast direto
    /// `(long)` do Java satura em `Long.MAX_VALUE` em vez de produzir o padrão de bits sem sinal;
    /// desloca para baixo de `2^63`, converte e soma de volta em complemento de dois.
    private static long doubleToUnsignedLongBits(double clamped) {
        double twoToThe63 = Math.scalb(1.0, 63);
        if (clamped < twoToThe63) {
            return (long) clamped;
        }
        return Long.MIN_VALUE + (long) (clamped - twoToThe63);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // "Two-register miscellaneous" INTEIRO/FP de um só operando — B13.12 (migração D1 da RFC
    // B13.2). `switch`/helpers movidos VERBATIM de
    // `executor64/Ir64VectorArithmeticExecutor#executeUnary`/`executeReverseGroups` (B8.7/B8.18/
    // B8.20); `SUQADD`/`USQADD`/`RBIT` NÃO migram (sem encoding A32 correspondente neste grupo,
    // mesma duplicação mínima já aceita para `saturateToElement`/`unsignedBig` desde B13.5) —
    // continuam no `switch` local daquela classe.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private static final int ADVSIMD_REV_GROUP_16_BITS = 16;
    private static final int ADVSIMD_REV_GROUP_32_BITS = 32;
    private static final int ADVSIMD_REV_GROUP_64_BITS = 64;
    private static final long URECPE_TOP_BIT_MASK = 0x8000_0000L;
    private static final long URSQRTE_TOP_BITS_MASK = 0xC000_0000L;
    private static final int URECPE_FIELD_SHIFT = 23;
    private static final int URECPE_FIELD_MASK = 0x1FF;
    private static final long URECPE_ALL_ONES = 0xFFFF_FFFFL;

    /// Executa uma operação AdvSIMD "two-register miscellaneous" inteira (ver {@link
    /// AdvSimdUnaryOp}) sobre `elements` elementos de ORIGEM de `1 << esz` bytes (a contagem de
    /// SAÍDA das formas de pareamento largo — {@link AdvSimdUnaryOp#SADDLP}/{@code UADDLP}/
    /// {@code SADALP}/{@code UADALP} — é metade disso, calculada aqui dentro). `baseRd`/`baseRn` são
    /// índices de PALAVRA; a escrita destrutiva de `[127:64]`/escalar é do chamador.
    public static void unary(AdvSimdRegisterWords regs, AdvSimdUnaryOp op, int esz, int elements,
            int baseRd, int baseRn) {
        switch (op) {
            case REV64 -> reverseGroups(regs, esz, ADVSIMD_REV_GROUP_64_BITS, elements, baseRd, baseRn);
            case REV32 -> reverseGroups(regs, esz, ADVSIMD_REV_GROUP_32_BITS, elements, baseRd, baseRn);
            case REV16 -> reverseGroups(regs, esz, ADVSIMD_REV_GROUP_16_BITS, elements, baseRd, baseRn);
            case SADDLP, UADDLP, SADALP, UADALP -> widenPairwiseAdd(regs, op, esz, elements, baseRd, baseRn);
            default -> generalUnary(regs, op, esz, elements, baseRd, baseRn);
        }
    }

    /// `REV64`/`REV32`/`REV16`: dentro de cada grupo consecutivo de {@code containerBits} bits,
    /// inverte a ORDEM dos elementos de `esz` bytes (o VALOR de cada elemento não muda, só a
    /// posição).
    private static void reverseGroups(AdvSimdRegisterWords regs, int esz, int containerBits, int elements,
            int baseRd, int baseRn) {
        int elementsPerContainer = containerBits / (8 << esz);
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            int containerBase = (i / elementsPerContainer) * elementsPerContainer;
            int offsetWithinContainer = i % elementsPerContainer;
            int sourceIndex = containerBase + (elementsPerContainer - 1 - offsetWithinContainer);
            results[i] = element(regs, baseRn, sourceIndex, esz);
        }
        for (int i = 0; i < elements; i++) {
            setElement(regs, baseRd, i, esz, results[i]);
        }
    }

    private static long extendMaybe(long value, int esz, boolean signed) {
        return signed ? signExtend(value, esz) : value;
    }

    /// `SADDLP`/`UADDLP`/`SADALP`/`UADALP`: pareia elementos adjacentes de `inputElements` (`esz`
    /// bytes), soma alargando para `esz+1`; a forma "ALP" ACUMULA no `Rd` ATUAL (já em `esz+1`) em
    /// vez de sobrescrever.
    private static void widenPairwiseAdd(AdvSimdRegisterWords regs, AdvSimdUnaryOp op, int esz,
            int inputElements, int baseRd, int baseRn) {
        int wideEsz = esz + 1;
        int outputElements = inputElements / 2;
        boolean signed = op == AdvSimdUnaryOp.SADDLP || op == AdvSimdUnaryOp.SADALP;
        boolean accumulate = op == AdvSimdUnaryOp.SADALP || op == AdvSimdUnaryOp.UADALP;
        long[] results = new long[outputElements];
        for (int i = 0; i < outputElements; i++) {
            long a = extendMaybe(element(regs, baseRn, i * 2, esz), esz, signed);
            long b = extendMaybe(element(regs, baseRn, i * 2 + 1, esz), esz, signed);
            long sum = a + b;
            results[i] = accumulate
                    ? extendMaybe(element(regs, baseRd, i, wideEsz), wideEsz, signed) + sum
                    : sum;
        }
        for (int i = 0; i < outputElements; i++) {
            setElement(regs, baseRd, i, wideEsz, truncate(results[i], wideEsz));
        }
    }

    /// O resto de {@link AdvSimdUnaryOp}: um resultado por elemento de ORIGEM, mesma largura
    /// `esz` no destino.
    private static void generalUnary(AdvSimdRegisterWords regs, AdvSimdUnaryOp op, int esz, int elements,
            int baseRd, int baseRn) {
        for (int i = 0; i < elements; i++) {
            long a = element(regs, baseRn, i, esz);
            long sa = signExtend(a, esz);
            long result = switch (op) {
                case ABS -> Math.abs(sa);
                case NEG -> -a;
                case CMEQ0 -> boolMask(sa == 0, esz);
                case CMGT0 -> boolMask(sa > 0, esz);
                case CMGE0 -> boolMask(sa >= 0, esz);
                case CMLT0 -> boolMask(sa < 0, esz);
                case CMLE0 -> boolMask(sa <= 0, esz);
                case SQABS -> saturateToElement(BigInteger.valueOf(sa).abs(), esz, true);
                case SQNEG -> saturateToElement(BigInteger.valueOf(sa).negate(), esz, true);
                case CLS -> countLeadingSignBits(a, sa, esz);
                case CLZ -> leadingZerosInWidth(a, 8 << esz);
                case CNT -> (long) Long.bitCount(a);
                case NOT -> ~a;
                case URECPE -> unsignedRecipEstimate32(a);
                case URSQRTE -> unsignedRSqrtEstimate32(a);
                case SADDLP, UADDLP, SADALP, UADALP, REV64, REV32, REV16 ->
                        throw new IllegalStateException("tratado em unary() antes de generalUnary: " + op);
            };
            setElement(regs, baseRd, i, esz, truncate(result, esz));
        }
    }

    /// Conta zeros à esquerda de `pattern` (já zero-extendido/mascarado a `widthBits` bits) DENTRO
    /// de `widthBits`, não dos 64 bits inteiros do `long`.
    private static long leadingZerosInWidth(long pattern, int widthBits) {
        if (pattern == 0) {
            return widthBits;
        }
        return Long.numberOfLeadingZeros(pattern) - (64 - widthBits);
    }

    /// `CLS`: conta bits à esquerda IGUAIS ao bit de sinal, sem contar o próprio bit de sinal —
    /// equivalente a {@link #leadingZerosInWidth} do padrão (inverte se negativo) menos 1.
    private static long countLeadingSignBits(long a, long sa, int esz) {
        long pattern = sa < 0 ? (~a) & elementMask(esz) : a;
        return leadingZerosInWidth(pattern, 8 << esz) - 1;
    }

    /// `URECPE`/`UnsignedRecipEstimate` (ARM DDI 0487) — `input`/`estimate` são campos de 9 bits,
    /// posicionados em `bits[31:23]` do resultado de 32 bits.
    private static long unsignedRecipEstimate32(long a) {
        if ((a & URECPE_TOP_BIT_MASK) == 0) {
            return URECPE_ALL_ONES;
        }
        int input = (int) ((a >>> URECPE_FIELD_SHIFT) & URECPE_FIELD_MASK);
        return ((long) recipEstimateTable(input)) << URECPE_FIELD_SHIFT;
    }

    /// `RecipEstimate` (ARM DDI 0487) — `input` em `[256,511)`, resultado em `[256,511)`.
    private static int recipEstimateTable(int input) {
        int a = (input * 2) + 1;
        int b = (1 << 19) / a;
        return (b + 1) >> 1;
    }

    /// `URSQRTE`/`UnsignedRSqrtEstimate` — mesma disciplina de {@link #unsignedRecipEstimate32}.
    private static long unsignedRSqrtEstimate32(long a) {
        if ((a & URSQRTE_TOP_BITS_MASK) == 0) {
            return URECPE_ALL_ONES;
        }
        int input = (int) ((a >>> URECPE_FIELD_SHIFT) & URECPE_FIELD_MASK);
        return ((long) rSqrtEstimateTable(input)) << URECPE_FIELD_SHIFT;
    }

    /// `RecipSqrtEstimate` (ARM DDI 0487) — `input` em `[128,512)`, resultado em `[256,511)`.
    private static int rSqrtEstimateTable(int input) {
        int a = input;
        if (a < 256) {
            a = (a * 2) + 1;
        } else {
            a = (a >> 1) << 1;
            a = (a + 1) * 2;
        }
        int b = 512;
        while ((long) a * (b + 1) * (b + 1) < (1L << 28)) {
            b += 1;
        }
        return (b + 1) / 2;
    }

    /// Executa uma operação AdvSIMD "narrow unary" (ver {@link AdvSimdNarrowUnaryOp}) sobre
    /// `elements` elementos de SAÍDA de `esz` bytes, lidos de `baseRn` em elementos de `esz+1`
    /// bytes a partir de `laneOffset` (o A64 usa `laneOffset` para a forma "2"/`q=1`; o NEON de 32
    /// bits passa sempre `0`) e escritos em `baseRd` a partir de `laneOffset` também (mesma
    /// convenção do A64: a forma "2" escreve na metade ALTA do MESMO registrador largo que a forma
    /// "1" leu na metade baixa). Resultados calculados num buffer ANTES de qualquer escrita (E10).
    public static void narrowUnary(AdvSimdRegisterWords regs, AdvSimdNarrowUnaryOp op, int esz,
            int elements, int laneOffset, int baseRd, int baseRn) {
        int wideEsz = esz + 1;
        long[] results = new long[elements];
        for (int i = 0; i < elements; i++) {
            long wide = element(regs, baseRn, i, wideEsz);
            long signedWide = signExtend(wide, wideEsz);
            long narrow = switch (op) {
                case SQXTN -> saturateToElement(BigInteger.valueOf(signedWide), esz, true);
                case SQXTUN -> saturateToElement(BigInteger.valueOf(signedWide), esz, false);
                case UQXTN -> saturateToElement(unsignedBig(wide), esz, false);
                case XTN -> wide;
            };
            results[i] = truncate(narrow, esz);
        }
        for (int i = 0; i < elements; i++) {
            setElement(regs, baseRd, laneOffset + i, esz, results[i]);
        }
    }

    /// Executa uma operação AdvSIMD "two-register miscellaneous" de PONTO FLUTUANTE (ver {@link
    /// AdvSimdFpUnaryOp}) sobre um elemento de `esz` bytes (`2`=F32, `3`=F64) já lido de `baseRn` —
    /// o chamador itera as lanes e chama esta função por elemento (mesma convenção de
    /// {@link #fpCombinePair}, que também opera por par já extraído em vez de por registrador
    /// inteiro).
    public static long fpUnary(AdvSimdFpUnaryOp op, int esz, long inputBits) {
        if (esz == 2) {
            float a = Float.intBitsToFloat((int) inputBits);
            return switch (op) {
                case ABS -> floatBits(Float.intBitsToFloat((int) inputBits & Integer.MAX_VALUE));
                case NEG -> floatBits(Float.intBitsToFloat((int) inputBits ^ Integer.MIN_VALUE));
                case RECPE -> floatBits(1.0f / a);
                case RSQRTE -> floatBits((float) (1.0 / Math.sqrt(a)));
                case CMGT0 -> boolMask(a > 0f, esz);
                case CMGE0 -> boolMask(a >= 0f, esz);
                case CMEQ0 -> boolMask(a == 0f, esz);
                case CMLE0 -> boolMask(a <= 0f, esz);
                case CMLT0 -> boolMask(a < 0f, esz);
            };
        }
        double a = Double.longBitsToDouble(inputBits);
        return switch (op) {
            case ABS -> doubleBits(Double.longBitsToDouble(inputBits & Long.MAX_VALUE));
            case NEG -> doubleBits(Double.longBitsToDouble(inputBits ^ Long.MIN_VALUE));
            case RECPE -> doubleBits(1.0 / a);
            case RSQRTE -> doubleBits(1.0 / Math.sqrt(a));
            case CMGT0 -> boolMask(a > 0.0, esz);
            case CMGE0 -> boolMask(a >= 0.0, esz);
            case CMEQ0 -> boolMask(a == 0.0, esz);
            case CMLE0 -> boolMask(a <= 0.0, esz);
            case CMLT0 -> boolMask(a < 0.0, esz);
        };
    }
}
