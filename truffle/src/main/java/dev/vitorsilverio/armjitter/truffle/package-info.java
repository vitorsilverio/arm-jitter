/// Backend GraalVM/Truffle do arm-jitter (trilha A do ROADMAP).
///
/// Este módulo existirá como um TERCEIRO backend de codegen (`CodegenBackend.TRUFFLE`),
/// convertendo o MESMO `IrBlock` pós-otimizador em nós Truffle — o IR próprio continua
/// sendo o contrato central (ver ROADMAP, decisão de arquitetura da trilha A). As
/// dependências pesadas (truffle-api, polyglot) entram aqui na task A2, nunca no core.
///
/// Vazio de propósito nesta fase (task A1 — só a estrutura multi-módulo).
package dev.vitorsilverio.armjitter.truffle;
