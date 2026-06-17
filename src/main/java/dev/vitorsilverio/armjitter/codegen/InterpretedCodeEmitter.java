package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

/// Emissor que transforma IR em um bloco executavel por interpretacao de IR.
public final class InterpretedCodeEmitter implements CodeEmitter {
    private final IrBlockExecutor executor;

    /// Emissor para a arquitetura base (ARMv4T / GBA).
    public InterpretedCodeEmitter() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Emissor ligado a uma arquitetura: os poucos forks de comportamento entre versões
    /// (ex.: interworking de load->PC) consultam a arquitetura do {@link IrBlockExecutor}.
    public InterpretedCodeEmitter(ArmArchitecture architecture) {
        this.executor = new IrBlockExecutor(architecture);
    }

    /// Emissor com executor customizado (útil em testes).
    InterpretedCodeEmitter(IrBlockExecutor executor) {
        this.executor = executor;
    }

    /// Emite um bloco executável que interpreta as operações IR em ordem.
    @Override
    public CompiledBlock emit(IrBlock block) {
        return core -> execute(block, core);
    }

    @Override
    public CodegenBackend backend() {
        return CodegenBackend.INTERPRETED_IR;
    }

    /// Interpreta um bloco IR sobre o core informado e devolve os ciclos internos
    /// (`IrOp.Cycle`) consumidos; ciclos de memória são somados diretamente ao core.
    public int execute(IrBlock block, ArmCore core) {
        return executor.execute(block, core);
    }
}
