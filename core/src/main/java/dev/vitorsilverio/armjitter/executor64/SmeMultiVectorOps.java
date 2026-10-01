package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// SME2 multi-vetor (B18.7): `SMAX`/`UMAX`/`SMIN`/`UMIN`/`ADD`/`SRSHL`/`URSHL`/`SQDMULH`/`FMAX`/`FMIN`/`FMAXNM`/
/// `FMINNM`/`FSCALE` na forma "multiple-and-single" (`do_zzz_n1`/`do_zzz_n1_fpst` do `translate-sme.c`).
///
/// - Exige modo streaming e SME habilitado (`sme_sm_enabled_check`) — **não** `ZA`; o comprimento é o `SVL`.
/// - **Sem predicado**: toda lane de todo membro do grupo é operada.
/// - **A operação por lane é a do SVE, chamada e não copiada**: inteiros em {@link SveIntegerPredicatedOps#binary},
///   `SQDMULH` em {@link SveMultiplyIndexedOps#sqrdmlah}, ponto flutuante em {@link SveFloat}. A ÚNICA semântica
///   própria é a de `SRSHL`/`URSHL` ({@link Sve2VectorOps#roundingShiftByElement}: quantidade = elemento inteiro).
/// - Ponto flutuante usa o `FPCR` do core (`FPST_A64`) e acumula as flags em `FPSR` uma vez no fim; `FPCR.AH` não é
///   modelado (pendência nomeada, igual ao SVE).
/// - `Zm` é copiado ANTES de qualquer escrita: ele pode ser membro do grupo de destino (ver {@link SmeVectorGroup}).
final class SmeMultiVectorOps {
    private static final int WORD_BYTES = Long.BYTES;

    private SmeMultiVectorOps() {
    }

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    static boolean execute(Aarch64Core core, Ir64Op.SmeMultiVectorSingle op) {
        if (!core.smeStreamingEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int svlBytes = core.streamingVectorLengthBytes();
        int elements = svlBytes >>> op.esz();
        SmeVectorGroup group = new SmeVectorGroup(op.zdn(), op.count());
        long[] single = SmeVectorGroup.snapshot(regs, op.zm(), svlBytes / WORD_BYTES);
        SveFloat.Env env = op.op().isFloatingPoint() ? SveFloat.Env.of(core, op.esz()) : null;
        for (int member = 0; member < group.count(); member++) {
            int register = group.register(member);
            for (int e = 0; e < elements; e++) {
                long n = SveIntegerOps.get(regs, register, e, op.esz());
                long m = SmeVectorGroup.element(single, e, op.esz());
                SveIntegerOps.set(regs, register, e, op.esz(), compute(op, n, m, env));
            }
        }
        if (env != null) {
            env.commit(core);
        }
        return false;
    }

    private static long compute(Ir64Op.SmeMultiVectorSingle op, long n, long m, SveFloat.Env env) {
        int esz = op.esz();
        return switch (op.op()) {
            case SMAX -> SveIntegerPredicatedOps.binary(Ir64Op.SveIntegerPredicated.Op.SMAX, n, m, esz);
            case UMAX -> SveIntegerPredicatedOps.binary(Ir64Op.SveIntegerPredicated.Op.UMAX, n, m, esz);
            case SMIN -> SveIntegerPredicatedOps.binary(Ir64Op.SveIntegerPredicated.Op.SMIN, n, m, esz);
            case UMIN -> SveIntegerPredicatedOps.binary(Ir64Op.SveIntegerPredicated.Op.UMIN, n, m, esz);
            case ADD -> SveIntegerPredicatedOps.binary(Ir64Op.SveIntegerPredicated.Op.ADD, n, m, esz);
            case SRSHL -> Sve2VectorOps.roundingShiftByElement(n, m, esz, true);
            case URSHL -> Sve2VectorOps.roundingShiftByElement(n, m, esz, false);
            case SQDMULH -> SveMultiplyIndexedOps.sqrdmlah(SveIntegerOps.signExtend(n, esz),
                    SveIntegerOps.signExtend(m, esz), 0L, false, false, esz);
            case FMAX -> SveFloat.maxMin(n, m, true, env);
            case FMIN -> SveFloat.maxMin(n, m, false, env);
            case FMAXNM -> SveFloat.maxMinNumber(n, m, true, env);
            case FMINNM -> SveFloat.maxMinNumber(n, m, false, env);
            case FSCALE -> SveFloat.scale(n, SveIntegerOps.signExtend(m, esz), env);
        };
    }
}
