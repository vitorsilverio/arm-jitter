package dev.vitorsilverio.armjitter.codegen.jvm;

/// Contrato de emissão de flags NZCV em bytecode JVM.
///
/// A implementação de referência da semântica está em
/// {@link dev.vitorsilverio.armjitter.codegen.executor.IrExecutionSupport}; o codegen ASM
/// deve espelhar as mesmas chamadas descritas aqui.
public interface FlagEmitter {
    /// Acesso ao CPSR a partir do {@link dev.vitorsilverio.armjitter.core.ArmCore} na pilha.
    HostMethodBinding cpsr();

    /// Atualiza NZCV a partir de quatro booleanos na pilha ({@code ZZZZ}).
    HostMethodBinding setNzcv();

    /// Lê o flag carry atual (útil para ADC/SBC e shifts).
    HostMethodBinding carry();
}
