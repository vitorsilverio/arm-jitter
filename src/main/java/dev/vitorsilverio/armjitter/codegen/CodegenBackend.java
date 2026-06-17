package dev.vitorsilverio.armjitter.codegen;

/// Identifica como um {@link CodeEmitter} materializa blocos IR em unidades executáveis.
public enum CodegenBackend {
    /// Executa {@link dev.vitorsilverio.armjitter.ir.IrOp} em loop Java ({@link InterpretedCodeEmitter}).
    INTERPRETED_IR,

    /// Gera bytecode JVM carregado em tempo de execução (emissores ASM).
    JVM_BYTECODE
}
