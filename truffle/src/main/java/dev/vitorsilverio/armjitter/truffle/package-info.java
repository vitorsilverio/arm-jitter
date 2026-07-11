/// Backend GraalVM/Truffle do arm-jitter (trilha A do ROADMAP).
///
/// Terceiro backend de codegen (`CodegenBackend.TRUFFLE`), convertendo o MESMO `IrBlock`
/// pós-otimizador em nós Truffle — o IR próprio continua sendo o contrato central. As
/// dependências pesadas (truffle-api, truffle-runtime) vivem só aqui, nunca no core.
///
/// Desde a task A2: {@link dev.vitorsilverio.armjitter.truffle.TruffleCodeEmitter} cobre um
/// subconjunto mínimo (ALU simples + `Cycle`/`Fetch`), delegando o resto para
/// `InterpretedCodeEmitter`. Cobertura completa de `IrOp`, factory pública e bench nos 3
/// ambientes ficam para A3/A4.
package dev.vitorsilverio.armjitter.truffle;
