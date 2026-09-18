# A5 — Demo native-image

**Trilha:** A · **Depende de:** A4 · **Repo:** arm-jitter + armbox
**Status: PENDENTE — bloqueada até o usuário instalar o GraalVM 25 LTS.** Não iniciar
sem isso disponível (`native-image` é ferramenta da distribuição GraalVM, não do JBR).

## Contexto

A motivação central — e ÚNICA — da trilha A: o backend ASM define classes em runtime,
o que o GraalVM native-image NÃO suporta — um binário compilado nativamente hoje
ficaria preso no interpretador. Com o backend Truffle, o JIT funciona dentro do binário
nativo. **Não é sobre ganho de performance em JVM normal** — lá o ASM continua sendo a
escolha (ver `ROADMAP.md`); native-image é o ÚNICO cenário onde Truffle vale a pena.

**Decisão explícita do usuário (2026-07-11): o alvo é `armbox`, NÃO gbaemu/ndsemu.**
Sem interesse em dar suporte nativo aos dois emuladores por ora — `armbox` (CLI pura,
sem GUI/Swing/áudio/input) é um hospedeiro muito mais simples de fechar para
native-image, e é o candidato natural para provar o conceito antes de considerar os
outros dois (se algum dia fizer sentido).

## Objetivo

Um binário nativo (native-image) do `armbox` executando um ELF de teste com o backend
Truffle compilando blocos em runtime.

## Especificação

1. Hospedeiro: `armbox` (não gbaemu/ndsemu — ver Contexto). Um mini-main de
   demonstração num módulo `arm-jitter-demo` só entra em jogo se o armbox se provar
   difícil de fechar para native-image (decidir com o usuário nesse caso).
2. Configurar o build native-image (plugin Maven `native-maven-plugin`), incluindo a
   configuração de linguagem Truffle exigida pelo native-image (macro/truffle feature
   da versão corrente — conferir doc oficial, pode ter mudado desde a escrita desta task).
3. Levantar e registrar toda config de reflection/resources que o armbox precisar
   (`reflect-config.json` etc. via agente de tracing do GraalVM).
4. Rodar um ELF de teste (os `.s` handwritten de B4.0, sem toolchain glibc disponível
   — ver `armbox/README.md`) e, se possível, o `busybox-armv5l` estático já usado lá.

## Aceite

1. Binário nativo executa o ELF com resultado idêntico ao da JVM (mesmo stdout/exit
   code — mesmo padrão de `ArmboxIntegrationTest`).
2. Log de TraceCompilation comprova que blocos foram COMPILADOS dentro do binário
   nativo (não apenas interpretados).
3. Documento curto `tasks/trilha-a-truffle/RELATORIO-A5.md` com: tamanho do binário,
   tempo de startup, fps/chunks-por-segundo vs JVM, e os passos de build reproduzíveis.

## Armadilhas

- O backend ASM deve ser automaticamente indisponível/rejeitado sob native-image com
  mensagem clara (detectar via `ImageInfo.inImageCode()` ou tentativa de defineClass
  falhando cedo) — não deixar quebrar com erro obscuro em runtime.
- native-image resolve TUDO em build time: qualquer `Class.forName`/reflection do
  armbox (loader ELF, dispatcher de syscall, etc.) precisa de config explícita.
- Confirmar a versão exata do GraalVM 25 LTS que o usuário instalar antes de escrever
  os passos de build no relatório — comandos/flags do `native-image` mudam entre
  versões.

## Resultado

🟡 PARCIAL (ver [relatório](RELATORIO-A5.md)). Repo `armbox` (commit dedicado A5):
`Backend.TRUFFLE` novo (`--truffle`) ligado a `TruffleJitRuntimeFactory`; perfil Maven
`native` (`native-maven-plugin` 0.11.1) gera `armbox.exe` (39,5MB) com
`JAVA_HOME=`GraalVM 25.0.3 Oracle + ambiente MSVC (`vcvars64.bat`, exigido no
Windows); backend ASM recusado cedo sob native-image via `ImageInfo.inImageCode()`
com mensagem clara (armadilha ✅).

**Aceite #1 ✅** (stdout/exit idênticos à JVM para `hello.elf` e `busybox-armv5l`
reais).

**Aceite #2 🔴 não alcançado**: `TraceCompilation` mostra 0 blocos compilados com
sucesso — TODA tentativa de compilar um `TruffleBlockRootNode` contra um bloco ARM
real (loop de verdade via busybox, não os blocos sintéticos retos do bench A0/A4)
bilateral bailout de partial evaluation, tanto no binário nativo (`FrameWithoutBoxing
should not be materialized`) quanto no JBR com Truffle Unchained (`tooDeepInlining`) —
causa raiz: `IrBlockExecutor#executeOp` (A3) é um dispatcher único exaustivo sobre
TODO `IrOp.Kind`, sem especialização por nó Truffle, e o PE não consegue
expandir/podar os ramos para nenhum bloco real; consequência medida: `--truffle`
ficou MAIS LENTO que `--interp` (2,76s vs 1,77s, loop de 2000 iterações) porque paga o
custo de compilar em background sem nunca ter sucesso. Não é bug de A5 nem específico
de native-image — é limitação pré-existente de A2/A3 nunca antes exercitada com
blocos reais; recomendação registrada no relatório para uma eventual task futura de
especialização de nós Truffle por `IrOp`, fora do escopo desta task.

**Revalidada pela A7 (2026-07-27): permanece 🟡** — ver nota na task A7.
