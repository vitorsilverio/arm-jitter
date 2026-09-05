package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decide se um {@link Ir64Block} pode ser emitido nativamente pelo
/// {@code Asm64CodeEmitter} — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy} (32 bits), introduzido na task
/// B6.4 (PR1).
///
/// ## Cobertura — NÃO é exaustiva (medida em 2026-09-02 / C12.1; +16 pela C12.3)
///
/// O `switch` abaixo cobre **40 dos 96 {@link Ir64Op.Kind}**: os conjuntos das tasks B6.1 (reta +
/// desvios), B6.2 (loads/stores + `Svc`), B6.3.1-B6.3.4 (registrador deslocado/estendido,
/// `CSEL`, bitfield, `MADD`, `SDIV`, exclusivos), B6.5.2-B6.5.4 (FP escalar `FP64_ALU`/
/// `FP64_MOVE_IMMEDIATE`/`FP64_COMPARE`/`FP64_CONVERT`) e **C12.3** (inteiro restante: ALU de
/// registrador, comparação condicional, 1-source/multiplicação, exclusivos/atômicos de par e
/// manipulação de flags — 16 `Kind`, ver {@code c12.3-a64-inteiro-nativo.md}). Os 56 que ainda
/// faltam — FP escalar restante (C12.4), load/store FP/SIMD (C12.5), AdvSIMD aritmético (C12.6) e
/// sistema (C12.10) — caem no
/// {@link dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter}.
///
/// Agravava porque a política padrão do {@code Asm64CodeEmitter} era `WHOLE_BLOCK`: **UMA op não
/// suportada derrubava o BLOCO INTEIRO** para o interpretador. A task C12.2 deu ao emissor um
/// segundo modo, `PER_OP` (`Asm64FallbackPolicy`), que compila as ops nativas e despacha só as
/// demais ao interpretado — mas o DEFAULT continua `WHOLE_BLOCK` (G3), e nenhuma linha desta
/// política passou a ser suportada nativamente por causa disso (C12.2 não move `docs/COBERTURA-JIT.md`).
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
                 Ir64Op.Kind.FP64_CONVERT,
                 Ir64Op.Kind.CONDITIONAL_COMPARE,
                 Ir64Op.Kind.LOGICAL_SHIFTED_REGISTER,
                 Ir64Op.Kind.SHIFT_VARIABLE,
                 Ir64Op.Kind.ALU_WITH_CARRY,
                 Ir64Op.Kind.EXTRACT,
                 Ir64Op.Kind.DATA_PROCESSING_1_SOURCE,
                 Ir64Op.Kind.MULTIPLY_ACCUMULATE_LONG,
                 Ir64Op.Kind.MULTIPLY_HIGH,
                 Ir64Op.Kind.COMPARE_AND_SWAP,
                 Ir64Op.Kind.COMPARE_AND_SWAP_PAIR,
                 Ir64Op.Kind.LOAD_EXCLUSIVE_PAIR,
                 Ir64Op.Kind.STORE_EXCLUSIVE_PAIR,
                 Ir64Op.Kind.ATOMIC_MEMORY_OP,
                 Ir64Op.Kind.EVALUATE_INTO_FLAGS,
                 Ir64Op.Kind.ROTATE_INTO_FLAGS,
                 Ir64Op.Kind.CONVERT_FLAGS -> true;
            default -> false;
        };
    }
}
