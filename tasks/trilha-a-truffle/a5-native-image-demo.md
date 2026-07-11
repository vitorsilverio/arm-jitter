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
