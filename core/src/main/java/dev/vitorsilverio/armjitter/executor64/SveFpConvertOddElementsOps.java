package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Semântica de `FCVTNT_sh`/`FCVTLT_hs`/`FCVTNT_ds`/`FCVTLT_sd`/`FCVTXNT_ds`/`BFCVTNT` (B17.23) — as 12
/// conversões "odd elements". Predicado testado na granularidade LARGA (`op.wideEsz()`, medido contra
/// `DO_FCVTNT`/`DO_FCVTLT` do QEMU real: o laço decrementa por `sizeof(TYPEW)`). `FCVTNT`/`BFCVTNT` (estreita)
/// leem o elemento LARGO `e` e escrevem SÓ o elemento estreito ÍMPAR `2e+1` (PRESERVAM o par `2e` — não
/// escrever nada nele, mesmo em zeroing); `FCVTLT` (alarga) lê o elemento estreito ÍMPAR `2e+1` e escreve o
/// elemento largo `e` inteiro.
final class SveFpConvertOddElementsOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;

    private SveFpConvertOddElementsOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, SveFpOp64.FpConvertOddElements op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int wideEsz = op.wideEsz();
        int narrowEsz = wideEsz - 1;
        int elements = core.vectorLengthBytes() >> wideEsz;
        boolean narrowing = op.op() != SveFpOp64.FpConvertOddElements.Op.FCVTLT;
        SveFloat.Env destinationEnv = SveFloat.Env.of(core, narrowing ? narrowEsz : wideEsz);
        SveFloat.Env sourceEnv = SveFloat.Env.ofConversionSource(core, narrowing ? wideEsz : narrowEsz);
        for (int e = 0; e < elements; e++) {
            int bit = e << wideEsz;
            boolean active = ((regs.pWord(op.pg(), bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
            if (narrowing) {
                if (!active) {
                    if (op.zeroing()) {
                        SveIntegerOps.set(regs, op.rd(), 2 * e + 1, narrowEsz, 0L);
                    }
                    continue;
                }
                long wide = SveIntegerOps.get(regs, op.rn(), e, wideEsz);
                long narrow = op.bfloat16()
                        ? AdvSimdLanes.bf16Bits(Float.intBitsToFloat((int) wide))
                        : SveFloat.convertPrecision(wide, sourceEnv, destinationEnv, op.roundToOdd());
                SveIntegerOps.set(regs, op.rd(), 2 * e + 1, narrowEsz, narrow);
            } else {
                if (!active) {
                    if (op.zeroing()) {
                        SveIntegerOps.set(regs, op.rd(), e, wideEsz, 0L);
                    }
                    continue;
                }
                long narrow = SveIntegerOps.get(regs, op.rn(), 2 * e + 1, narrowEsz);
                long wide = SveFloat.convertPrecision(narrow, sourceEnv, destinationEnv, false);
                SveIntegerOps.set(regs, op.rd(), e, wideEsz, wide);
            }
        }
        destinationEnv.commit(core);
        return false;
    }
}
