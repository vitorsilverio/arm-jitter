package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica da aritmética de ponto flutuante SVE (B17.13): as 6 não predicadas, `FRECPE`/`FRSQRTE`, as 15 predicadas
/// por vetor, as 8 com imediato de UM bit e `FTMAD`. A matemática vive em {@link SveFloat} (IEEE exato, `FPCR`/`FPSR`
/// do próprio core); aqui só há o laço por elemento.
///
/// Um elemento está ativo quando o bit do byte MAIS BAIXO dele em `P[pg]` está ligado. **Elemento inativo nunca
/// executa a operação** — não levanta exceção FP nem suja o `FPSR`, e o `Zd` fica como estava (merging). As flags são
/// acumuladas num {@link SveFloat.Env} e gravadas UMA vez no fim, só se alguma foi levantada.
///
/// `FTMAD`/`FTSMUL` são ilegais em modo streaming sem `FEAT_SME_FA64` (`TRANS_FEAT_NONSTREAMING` do QEMU). `FPCR.AH`
/// não é modelado (pendência nomeada): valem os caminhos `AH = 0`.
final class SveFpArithmeticOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int TMAD_NEGATIVE_BANK = 8;

    /// `coeff[]` de `FTMAD` (`.H`): 8 coeficientes de seno e 8 de cosseno (`helper_sve_ftmad_h` do QEMU).
    private static final long[] TMAD_HALF = {
        0x3c00, 0xb155, 0x2030, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x3c00, 0xb800, 0x293a, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
    };
    /// `coeff[]` de `FTMAD` (`.S`).
    private static final long[] TMAD_SINGLE = {
        0x3f800000L, 0xbe2aaaabL, 0x3c088886L, 0xb95008b9L,
        0x36369d6dL, 0x00000000L, 0x00000000L, 0x00000000L,
        0x3f800000L, 0xbf000000L, 0x3d2aaaa6L, 0xbab60705L,
        0x37cd37ccL, 0x00000000L, 0x00000000L, 0x00000000L,
    };
    /// `coeff[]` de `FTMAD` (`.D`).
    private static final long[] TMAD_DOUBLE = {
        0x3ff0000000000000L, 0xbfc5555555555543L, 0x3f8111111110f30cL, 0xbf2a01a019b92fc6L,
        0x3ec71de351f3d22bL, 0xbe5ae5e2b60f7b91L, 0x3de5d8408868552fL, 0x0000000000000000L,
        0x3ff0000000000000L, 0xbfe0000000000000L, 0x3fa5555555555536L, 0xbf56c16c16c13a0bL,
        0x3efa01a019b1e8d8L, 0xbe927e4f7282f468L, 0x3e21ee96d2641b13L, 0xbda8f76380fbb401L,
    };

    private SveFpArithmeticOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveFpArithmetic op) {
        if (op.op() == Ir64Op.SveFpArithmetic.Op.TSMUL || op.op() == Ir64Op.SveFpArithmetic.Op.TMAD) {
            SvePredicateOps.requireNonStreaming(core);
        }
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        SveFloat.Env env = SveFloat.Env.of(core, esz);
        for (int e = 0; e < elements; e++) {
            if (op.predicated()) {
                int bit = e << esz;
                if (((regs.pWord(op.pg(), bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) == 0L) {
                    continue;
                }
            }
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long m = operandM(regs, op, e, esz, env);
            SveIntegerOps.set(regs, op.rd(), e, esz, compute(op, n, m, esz, env));
        }
        env.commit(core);
        return false;
    }

    /// Segundo operando: a constante das formas `_zpzi`, `Zm` nas demais (as unárias não o usam).
    private static long operandM(Aarch64ScalableRegisters regs, Ir64Op.SveFpArithmetic op, int element, int esz,
            SveFloat.Env env) {
        if (op.immediateForm()) {
            return immediateConstant(op.op(), op.immediate(), env);
        }
        return switch (op.op()) {
            case RECPE, RSQRTE -> 0L;
            default -> SveIntegerOps.get(regs, op.rm(), element, esz);
        };
    }

    /// As duas constantes de cada forma `_zpzi`: `0.5`/`1.0` (`FADD`/`FSUB`/`FSUBR`), `0.5`/`2.0` (`FMUL`),
    /// `0.0`/`1.0` (`FMAXNM`/`FMINNM`/`FMAX`/`FMIN`).
    private static long immediateConstant(Ir64Op.SveFpArithmetic.Op op, int immediate, SveFloat.Env env) {
        return switch (op) {
            case ADD, SUB -> immediate == 0 ? env.half() : env.one();
            case MUL -> immediate == 0 ? env.half() : env.two();
            default -> immediate == 0 ? env.zero(false) : env.one(); // MAXNM/MINNM/MAX/MIN
        };
    }

    private static long compute(Ir64Op.SveFpArithmetic op, long n, long m, int esz, SveFloat.Env env) {
        long a = op.reversed() ? m : n;
        long b = op.reversed() ? n : m;
        return switch (op.op()) {
            case ADD -> SveFloat.add(a, b, false, env);
            case SUB -> SveFloat.add(a, b, true, env);
            case MUL -> SveFloat.multiply(a, b, env);
            case DIV -> SveFloat.divide(a, b, env);
            case MAXNM -> SveFloat.maxMinNumber(a, b, true, env);
            case MINNM -> SveFloat.maxMinNumber(a, b, false, env);
            case MAX -> SveFloat.maxMin(a, b, true, env);
            case MIN -> SveFloat.maxMin(a, b, false, env);
            case ABD -> SveFloat.absoluteDifference(a, b, env);
            case SCALE -> SveFloat.scale(a, SveIntegerOps.signExtend(b, esz), env);
            case MULX -> SveFloat.multiplyExtended(a, b, env);
            case AMAX -> SveFloat.absoluteMaxMin(a, b, true, env);
            case AMIN -> SveFloat.absoluteMaxMin(a, b, false, env);
            case TSMUL -> SveFloat.trigonometricStartingValue(a, b, env);
            case RECPS -> SveFloat.reciprocalStep(a, b, env);
            case RSQRTS -> SveFloat.reciprocalSquareRootStep(a, b, env);
            case RECPE -> SveFloat.reciprocalEstimate(n, env);
            case RSQRTE -> SveFloat.reciprocalSquareRootEstimate(n, env);
            default -> trigonometricMultiplyAdd(n, m, op.immediate(), esz, env); // TMAD
        };
    }

    /// `FTMAD`: `n × |m| + coeff[imm3 + (m < 0 ? 8 : 0)]`, fundido. O sinal de `m` escolhe o banco de cosseno.
    private static long trigonometricMultiplyAdd(long n, long m, int index, int esz, SveFloat.Env env) {
        boolean negative = env.isNegative(m);
        long[] coefficients = switch (esz) {
            case SveFloat.ESZ_HALF -> TMAD_HALF;
            case SveFloat.ESZ_SINGLE -> TMAD_SINGLE;
            default -> TMAD_DOUBLE;
        };
        long coefficient = coefficients[index + (negative ? TMAD_NEGATIVE_BANK : 0)];
        return SveFloat.fusedMultiplyAdd(coefficient, n, negative ? env.abs(m) : m, 0, env);
    }
}
