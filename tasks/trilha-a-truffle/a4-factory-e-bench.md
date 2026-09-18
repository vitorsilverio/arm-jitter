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

## Resultado

✅ `TruffleJitRuntimeFactory.truffleArmThumb(cacheEntries, hotThreshold[, arch])` no
módulo `truffle/`, espelha `JitRuntimeFactory.armThumb` — mesmo pipeline tiered (frio
interpretado + quente `TruffleCodeEmitter`), otimizador GBA aplicado no nível do
`JitRuntime` (`TruffleCodeEmitter` não recebe otimizador no construtor, diferente do
`AsmCodeEmitter`); 6 testes novos espelhando `JitRuntimeJvmFactoryTest`.

Bench REAL coletado (não reaproveitado da A0): emissores de PRODUÇÃO
`AsmCodeEmitter`×`TruffleCodeEmitter` em JBR 25.0.3 + Truffle Unchained
(`Truffle.getRuntime().getName()=="GraalVM CE"` confirmado — compilação real, não
fallback interpretado), mesma sessão/máquina, best-of-5×2M execuções, blocos
20/80/320 instr: ASM vence 5,17× a 20 instr, Truffle vence 1,33× a 80 e 5,5× a 320.

**2026-07-11 (sessão seguinte): terceira coluna fechada** — usuário instalou
`E:\graalvm-jdk-25.0.3+9.1` (banner "Oracle GraalVM", não GraalVM CE puro — release
lista módulos enterprise; distribuição community separada não existe mais desde o
GraalVM 21). Bench idêntico rodado com essa JVM (`Truffle.getRuntime().getName()=
"Oracle GraalVM"` confirmado — compilação real): ASM vence 5,24× a 20 e **2,20× a 80**
(diferente do JBR, onde Truffle já vence a 80 — o C2 desta build de HotSpot compila o
bloco de 80 instr ~4× mais rápido que o C2 do JBR, resultado estável em 3 execuções),
Truffle vence 7,18× a 320. Achado novo documentado: o ponto de crossover ASM↔Truffle é
JVM-dependente, não um número fixo — recomendação do README ajustada para não assumir
"~80 instruções" universalmente. Tabela completa nas 3 linhas (seção "Runtime JIT" do
README). Bench NÃO rodou dentro de gbaemu/ndsemu com ROM real — Truffle não tem NENHUM
wiring nos consumidores (fora de escopo, factory é opt-in); suites gbaemu 216 + ndsemu
175 permanecem verdes sem mudar defaults (G3/G5); `mvn -o test` na raiz revalidado
verde nesta sessão.
