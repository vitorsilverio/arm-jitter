## Resumo

O backend Truffle **funciona em JVM** (JBR + Unchained) mas, sob `native-image`/SVM, sofre um
bailout de *partial evaluation* e não chega a compilar blocos de verdade. A causa raiz nunca
foi diagnosticada.

## Impacto

Bloqueia o **A9 PR2** (embutir o backend Truffle na biblioteca nativa `arm_jitter.dll`/`.so`
com API C). Hoje a lib nativa (`capi/`, A9 PR1) só oferece o backend `INTERPRETED_IR`, porque
o backend ASM define classes em tempo de execução e é incompatível com native-image por
construção.

## Ambiente

**Não é falta de ambiente.** Desde 2026-07-31 a máquina tem GraalVM 25
(`E:\graalvm-jdk-25.0.3+9.1`) e Visual Studio 2022 com MSVC — foi com esse ambiente que as
tasks A8 (otimizações de native-image, PGO+`-O3` como default do perfil `native` do armbox) e
A9 PR1 fecharam. O bailout é do Truffle sob SVM em si.

## Referência

Tasks `a7-native-image-revalidacao.md` e `a9-native-shared-library.md`.

## Labels sugeridas

`bug`, `jit`, `needs-design`
