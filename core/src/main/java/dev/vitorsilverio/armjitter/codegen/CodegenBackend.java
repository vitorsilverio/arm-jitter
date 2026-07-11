package dev.vitorsilverio.armjitter.codegen;

/// Identifica como um {@link CodeEmitter} materializa blocos IR em unidades executáveis.
public enum CodegenBackend {
    /// Executa {@link dev.vitorsilverio.armjitter.ir.IrOp} em loop Java ({@link InterpretedCodeEmitter}).
    INTERPRETED_IR,

    /// Gera bytecode JVM carregado em tempo de execução (emissores ASM).
    JVM_BYTECODE,

    /// Gera nós Truffle (AST) compilados por partial evaluation quando o host roda sob
    /// Graal/JVMCI. A implementação (`TruffleCodeEmitter`) vive no módulo opcional
    /// `arm-jitter-truffle` (trilha A) — o core só declara o valor do enum, sem depender
    /// de nenhuma API Truffle.
    TRUFFLE
}
