# A4 — Factory pública + bench em 3 ambientes

**Trilha:** A · **Depende de:** A3 · **Repo:** arm-jitter (+ runs em gbaemu/ndsemu)
**Nota (2026-07-11):** a factory + os benches em JBR 25 puro e JBR/OpenJDK+Graal
(Unchained) podem começar já; a coluna "GraalVM CE" da tabela de resultados fica
PENDENTE até o usuário instalar o GraalVM 25 LTS (mesmo bloqueio da A5). Não é motivo
para adiar a task inteira — só essa parte do bench. Lembrete de escopo: isto é só
BENCHMARK em JVM normal (gbaemu/ndsemu continuam sem suporte nativo — ver A5).

## Objetivo

Expor o backend Truffle como API pública e publicar uma comparação honesta de
performance para orientar qual backend usar em cada ambiente.

## Especificação

1. Nova classe `TruffleJitRuntimeFactory` **no módulo truffle** (não em
   `JitRuntimeFactory` do core — o core não pode depender de Truffle):
   - `truffleArmThumb(int cacheEntries, int hotThreshold)` → ARMV4T.
   - `truffleArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture arch)`.
   - Mesmo pipeline tiered do `armThumb` (tier frio interpretado + tier quente Truffle,
     otimizador `StandardIrOptimizer.gba()`), espelhando `JitRuntimeFactory.build(...)`.
   - Javadoc `///` explicando quando escolher Truffle vs ASM (usar os dados do bench).
2. Bench: rodar os benches existentes dos consumidores (gbaemu headless; ndsemu
   `Main <rom> <frames> bench`) com backend ASM vs Truffle em:
   - JBR 25 puro (HotSpot C2);
   - JBR/OpenJDK + Graal via Unchained (se A0 provou viável);
   - GraalVM CE.
   Os runs são executados pelo usuário; prepare as instruções e colete os números.
3. Documentar a tabela de resultados no `README.md` (seção "Runtime JIT") e a
   recomendação por ambiente.

## Aceite

- Factory com testes (espelhar `JitRuntimeJvmFactoryTest`).
- Tabela de perf no README com os 3 ambientes × 2 backends, e a frase de recomendação.
- Suites gbaemu/ndsemu verdes com a dependência atualizada (eles NÃO passam a usar
  Truffle por default — G3).

## Armadilhas

- Comparar SEMPRE no mesmo hardware/sessão; anotar JVM exata (`java -version`) em cada
  linha da tabela.
- O tier frio continua interpretado — warmup afeta bench curto; use os frames de bench
  que os emuladores já usam para medir regime quente (≥600 frames no ndsemu).
