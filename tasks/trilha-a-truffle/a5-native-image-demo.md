# A5 — Demo native-image

**Trilha:** A · **Depende de:** A4 · **Repo:** arm-jitter + gbaemu (ou mini-hospedeiro)

## Contexto

A motivação central da trilha: o backend ASM define classes em runtime, o que o
GraalVM native-image NÃO suporta — um emulador compilado nativamente hoje ficaria
preso no interpretador. Com o backend Truffle, o JIT funciona dentro do binário nativo.

## Objetivo

Um binário nativo (native-image) rodando um ROM de teste com o backend Truffle
compilando blocos em runtime.

## Especificação

1. Escolher o hospedeiro: gbaemu headless (preferido — já tem modo sem GUI) ou um
   mini-main de demonstração num módulo `arm-jitter-demo` (decidir com o usuário se o
   gbaemu se provar difícil de fechar para native-image).
2. Configurar o build native-image (plugin Maven `native-maven-plugin`), incluindo a
   configuração de linguagem Truffle exigida pelo native-image (macro/truffle feature
   da versão corrente — conferir doc oficial).
3. Levantar e registrar toda config de reflection/resources que o hospedeiro precisar
   (`reflect-config.json` etc. via agente de tracing do GraalVM).
4. Rodar um ROM de teste (ex.: um dos gba-tests) e um jogo de referência headless.

## Aceite

1. Binário nativo executa o ROM com resultado idêntico ao da JVM (mesmo frame.png /
   mesmo resultado de teste).
2. Log de TraceCompilation comprova que blocos foram COMPILADOS dentro do binário
   nativo (não apenas interpretados).
3. Documento curto `tasks/trilha-a-truffle/RELATORIO-A5.md` com: tamanho do binário,
   tempo de startup, fps vs JVM, e os passos de build reproduzíveis.

## Armadilhas

- O backend ASM deve ser automaticamente indisponível/rejeitado sob native-image com
  mensagem clara (detectar via `ImageInfo.inImageCode()` ou tentativa de defineClass
  falhando cedo) — não deixar quebrar com erro obscuro em runtime.
- native-image resolve TUDO em build time: qualquer `Class.forName`/reflection do
  hospedeiro precisa de config explícita.
