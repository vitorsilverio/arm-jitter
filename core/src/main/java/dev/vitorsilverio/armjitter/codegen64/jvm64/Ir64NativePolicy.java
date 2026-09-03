package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decide se um {@link Ir64Block} pode ser emitido nativamente pelo
/// {@code Asm64CodeEmitter} — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy} (32 bits), introduzido na task
/// B6.4 (PR1).
///
/// ## Cobertura — NÃO é exaustiva (medida em 2026-09-02 / C12.1)
///
/// O `switch` abaixo cobre **24 dos 96 {@link Ir64Op.Kind}**: os conjuntos das tasks B6.1 (reta +
/// desvios), B6.2 (loads/stores + `Svc`), B6.3.1-B6.3.4 (registrador deslocado/estendido,
/// `CSEL`, bitfield, `MADD`, `SDIV`, exclusivos) e B6.5.2-B6.5.4 (FP escalar `FP64_ALU`/
/// `FP64_MOVE_IMMEDIATE`/`FP64_COMPARE`/`FP64_CONVERT`). Quando a B6.5.4 fechou, isso ERA tudo
/// que existia — mas desde então **B6.6.x** (MMU/EL1), **B8.6-B8.20** (toda a AdvSIMD do
/// AArch64), **B10** (EL2/EL3), **B11** (gating) e **B19.1-B19.4** acrescentaram ~72 `Kind`, e
/// **nenhum tem emissão nativa**. Os 72 que faltam — incluindo 100% do SIMD/FP vetorial — caem no
/// {@link dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter}.
///
/// Agrava porque a política é `WHOLE_BLOCK` (único modo — sem `PER_OP`/`FAIL_FAST` ainda, ver
/// C12.2): **UMA op não suportada derruba o BLOCO INTEIRO** para o interpretador.
///
/// A escada que fecha esse gap é **C12.3-C12.6** (`tasks/trilha-c-perf/c12-plano-jit-nativo.md`);
/// a lista medida vive em `docs/COBERTURA-JIT.md` (gerado por `./gerar-cobertura-jit.sh`), com
/// teste de guarda (`JitCoverageReportGuardTest`) para não voltar a mentir sobre o presente.
public final class Ir64NativePolicy {
    private Ir64NativePolicy() {
    }

    /// `true` quando TODAS as ops do bloco são suportadas nativamente.
    public static boolean supports(Ir64Block block) {
        for (Ir64Op op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    /// `true` quando a operação é suportada nativamente pelo `Ir64BlockCompiler` do PR1.
    public static boolean supports(Ir64Op op) {
        return switch (op.kind()) {
            case Ir64Op.Kind.ALU64,
                 Ir64Op.Kind.MOVE_WIDE,
                 Ir64Op.Kind.PC_RELATIVE,
                 Ir64Op.Kind.BRANCH64,
                 Ir64Op.Kind.COMPARE_BRANCH64,
                 Ir64Op.Kind.CYCLE,
                 Ir64Op.Kind.FETCH,
                 Ir64Op.Kind.LOAD64,
                 Ir64Op.Kind.STORE64,
                 Ir64Op.Kind.LOAD_STORE_PAIR,
                 Ir64Op.Kind.LOAD_LITERAL64,
                 Ir64Op.Kind.SVC,
                 Ir64Op.Kind.ALU_SHIFTED_REGISTER,
                 Ir64Op.Kind.ALU_EXTENDED_REGISTER,
                 Ir64Op.Kind.CONDITIONAL_SELECT,
                 Ir64Op.Kind.BITFIELD,
                 Ir64Op.Kind.MULTIPLY_ACCUMULATE,
                 Ir64Op.Kind.DIVIDE,
                 Ir64Op.Kind.LOAD_EXCLUSIVE,
                 Ir64Op.Kind.STORE_EXCLUSIVE,
                 Ir64Op.Kind.FP64_ALU,
                 Ir64Op.Kind.FP64_MOVE_IMMEDIATE,
                 Ir64Op.Kind.FP64_COMPARE,
                 Ir64Op.Kind.FP64_CONVERT -> true;
            default -> false;
        };
    }
}
