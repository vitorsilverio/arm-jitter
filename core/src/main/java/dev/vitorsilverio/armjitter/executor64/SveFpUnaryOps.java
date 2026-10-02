package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica das unárias de ponto flutuante SVE predicadas (B17.16): conversões de precisão, FP↔inteiro, `FRINT*`,
/// `FRINT32/64{X,Z}`, `FRECPX` e `FSQRT`, nas formas merging (`_m`) e zeroing (`_z`). A matemática vive em
/// {@link SveFloat} (IEEE exato, `FPCR`/`FPSR` do próprio core); aqui só há o laço por elemento.
///
/// **Elemento do vetor = o maior dos dois tamanhos.** O valor menor é lido dos bits baixos (o resto é ignorado) e, ao
/// escrever, o elemento inteiro recebe o resultado ZERO-estendido — `FCVT_sh` deixa `0x0000_hhhh` num elemento de 32
/// bits, `FCVTZS_ds` deixa um `int32` zero-estendido num de 64. Só o predicado do elemento (bit do byte mais baixo)
/// decide se ele é ativo: **elemento inativo nunca executa nem suja o `FPSR`**; `_m` o preserva, `_z` o zera.
///
/// Modos de arredondamento: `FRINTN`/`FRINTP`/`FRINTM`/`FRINTZ`/`FRINTA` (e `FRINT32Z`/`FRINT64Z`) têm o modo NO
/// OPCODE e ignoram `FPCR.RMode`; `FRINTI`, `FRINTX`, `FRINT32X`/`FRINT64X`, `FCVT` (estreitando) e `SCVTF`/`UCVTF`
/// seguem `FPCR.RMode`; `FCVTX` é sempre "ímpar"; `FCVTZS`/`FCVTZU` sempre truncam. `FRINTX` é a única `FRINT*` que
/// levanta `IXC`. Não modelados (pendências nomeadas): `FPCR.AHP` (meia alternativa) e `FPCR.AH`.
final class SveFpUnaryOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int INT32_BITS = 32;
    private static final int INT64_BITS = 64;

    private SveFpUnaryOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, SveFpOp64.FpUnary op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int container = Math.max(op.source(), op.destination());
        int elements = core.vectorLengthBytes() >> container;
        Context context = new Context(core, op);
        for (int e = 0; e < elements; e++) {
            int bit = e << container;
            boolean active = ((regs.pWord(op.pg(), bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
            if (!active) {
                if (op.zeroing()) {
                    SveIntegerOps.set(regs, op.rd(), e, container, 0L);
                }
                continue;
            }
            long source = SveIntegerOps.get(regs, op.rn(), e, container) & SveIntegerOps.elementMask(op.source());
            SveIntegerOps.set(regs, op.rd(), e, container, context.compute(source));
        }
        context.commit(core);
        return false;
    }

    /// Ambientes FP da instrução, montados uma vez (o `FPCR` é lido uma vez) e o cálculo de UM elemento.
    private static final class Context {
        private final SveFpOp64.FpUnary op;
        /// Ambiente cujas flags são gravadas: o do formato de destino (FP) ou de origem (FP→inteiro, `FRINT*`, …).
        private final SveFloat.Env env;
        /// Só nas conversões de precisão: origem sem `FZ16`.
        private final SveFloat.Env conversionSource;

        Context(Aarch64Core core, SveFpOp64.FpUnary op) {
            this.op = op;
            switch (op.op()) {
                case FCVT, FCVTX, BFCVT -> {
                    this.env = SveFloat.Env.of(core, op.destination());
                    this.conversionSource = SveFloat.Env.ofConversionSource(core, op.source());
                }
                case SCVTF, UCVTF -> {
                    this.env = SveFloat.Env.of(core, op.destination());
                    this.conversionSource = null;
                }
                default -> { // FCVTZ*: origem FP; FRINT*/FRECPX/FSQRT: origem = destino
                    this.env = SveFloat.Env.of(core, op.source());
                    this.conversionSource = null;
                }
            }
            switch (op.op()) {
                case FRINTN -> env.rmode = SveFloat.RMODE_NEAREST;
                case FRINTP -> env.rmode = SveFloat.RMODE_PLUS_INFINITY;
                case FRINTM -> env.rmode = SveFloat.RMODE_MINUS_INFINITY;
                case FRINTZ, FRINT32Z, FRINT64Z -> env.rmode = SveFloat.RMODE_ZERO;
                case FRINTA -> env.rmode = SveFloat.RMODE_TIE_AWAY;
                default -> {
                    // FRINTI/FRINTX/FRINT32X/FRINT64X e as conversões: `FPCR.RMode` como veio.
                }
            }
        }

        long compute(long source) {
            return switch (op.op()) {
                case FCVT, BFCVT -> SveFloat.convertPrecision(source, conversionSource, env, false);
                case FCVTX -> SveFloat.convertPrecision(source, conversionSource, env, true);
                case FCVTZS -> SveFloat.toInteger(source, env, integerBits(op.destination()), false);
                case FCVTZU -> SveFloat.toInteger(source, env, integerBits(op.destination()), true);
                case SCVTF -> SveFloat.fromInteger(SveIntegerOps.signExtend(source, op.source()), false, env);
                case UCVTF -> SveFloat.fromInteger(source, true, env);
                case FRINTN, FRINTP, FRINTM, FRINTZ, FRINTA -> SveFloat.roundToIntegral(source, env, env.rmode, false);
                case FRINTI -> SveFloat.roundToIntegral(source, env, env.rmode, false);
                case FRINTX -> SveFloat.roundToIntegral(source, env, env.rmode, true);
                case FRINT32X, FRINT32Z -> SveFloat.roundToIntegralBounded(source, env, env.rmode, INT32_BITS);
                case FRINT64X, FRINT64Z -> SveFloat.roundToIntegralBounded(source, env, env.rmode, INT64_BITS);
                case FRECPX -> SveFloat.reciprocalExponent(source, env);
                default -> SveFloat.squareRoot(source, env); // FSQRT
            };
        }

        void commit(Aarch64Core core) {
            env.commit(core);
        }
    }

    /// Largura em bits do inteiro de `1` = 16, `2` = 32, `3` = 64.
    private static int integerBits(int size) {
        return SveIntegerOps.elementBits(size);
    }
}
