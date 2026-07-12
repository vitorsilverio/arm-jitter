# RELATÓRIO A5 — Demo native-image (2026-07-11/12)

**Status: 🟡 PARCIAL.** O binário nativo existe, executa ELFs reais com resultado
IDÊNTICO à JVM (aceite #1 ✅) e o backend ASM é recusado cedo com mensagem clara sob
native-image (armadilha da task ✅). **Aceite #2 (log de `TraceCompilation` provando
blocos COMPILADOS, não só interpretados) NÃO foi alcançado** — nem no binário nativo,
nem na JVM (JBR + Truffle Unchained com os flags corretos): a compilação real via Graal
falha com um bailout de partial evaluation em 100% das tentativas, para QUALQUER bloco
real de ARM, nos dois ambientes. É uma limitação arquitetural pré-existente do
`TruffleCodeEmitter`/`IrBlockExecutor` (desde A2/A3), não algo introduzido por esta
task nem específico de native-image — a A5 apenas foi a primeira vez que alguém rodou
o backend Truffle contra blocos ARM reais (loops de verdade, não os blocos sintéticos
retos do bench A0/A4) e mediu com `TraceCompilation`.

## Ambiente

- GraalVM: `E:\graalvm-jdk-25.0.3+9.1` — banner "Oracle GraalVM 25.0.3+9.1
  (build 25.0.3+9-LTS-jvmci-b01)". `native-image 25.0.3`, Substrate VM Oracle GraalVM
  25.0.3+9.1. Confirmado via `native-image --version`.
- Compilador C: MSVC do Visual Studio 2022 Community (`cl.exe 19.44.35226`, x64),
  ativado via `vcvars64.bat`. **Windows exige rodar o `native-image`/`mvn -Pnative` a
  partir de um shell com o ambiente MSVC carregado** — o `native-image.cmd` não faz
  `vcvarsall` sozinho.
- JDK do resto do reactor (compilação normal, `mvn test`): continua JBR 25
  (`C:\Users\user\.jdks\jbr-25.0.3`), sem mudança — só o profile `native` usa o GraalVM.

## O que foi implementado

1. **`armbox/pom.xml`**: dependência em `dev.vitorsilverio:arm-jitter-truffle:1.0`
   (módulo já existente desde A2/A4, sem mudanças) e em `org.graalvm.sdk:nativeimage:
   25.0.3` (`ImageInfo`, biblioteca leve — funciona em qualquer JVM, devolve `false`
   fora de native-image). Perfil Maven `native` com `org.graalvm.buildtools:
   native-maven-plugin:0.11.1`, goal `compile-no-fork` na fase `package`,
   `mainClass=dev.vitorsilverio.armbox.Main`, `imageName=armbox`. Nenhuma configuração
   de linguagem Truffle (`@Registration`/`TruffleLanguage`) foi necessária — o
   `native-maven-plugin` detectou e habilitou sozinho as features
   `TruffleBaseFeature`/`TruffleFeature`/`EnterpriseTruffleCompilerFeature` por
   reachability (nenhuma flag `-H:` manual precisou ser escrita para isso). Este é o
   ponto onde a doc oficial mudou desde que a task foi escrita: versões antigas do
   GraalVM exigiam registrar uma `TruffleLanguage` para o SVM incluir o compilador
   Truffle na imagem; na 25.0.3 isso é automático a partir do uso de `RootNode`/
   `CallTarget` alcançável, mesmo com `super(null)` (sem linguagem) como o
   `TruffleBlockRootNode` já fazia desde A2.
2. **`Armbox.java`**: `Backend.TRUFFLE` novo, ligado a
   `TruffleJitRuntimeFactory.truffleArmThumb` (sem mudança no módulo `truffle/`).
   `Backend.JIT`/`Backend.CHECK` (que usam o backend ASM) agora chamam
   `rejectAsmUnderNativeImage()`, que checa `ImageInfo.inImageCode()` e lança
   `UnsupportedOperationException` com mensagem explicando o motivo (`defineClass` em
   runtime) e apontando a alternativa — testado manualmente no binário nativo (ver
   abaixo), mensagem aparece limpa, sem stack trace obscuro de classloading.
3. **`Main.java`**: flag `--truffle` na CLI (commitada por engano junto com B4.0.2 na
   sessão paralela — mesmo conteúdo, sem conflito).
4. **Testes**: `ArmboxIntegrationTest#helloWorldTruffle` (espelha `helloWorldJit`),
   `TRUFFLE` adicionado às asserções de `kuserCmpxchgStoresOnMatch`/
   `setTlsRoundTripsThroughKuserGetTls`; `RealElfTest` roda `testdata/hello.elf` nos
   três backends. Todos verdes em `mvn -o test` (JBR 25), reactor completo
   (`arm-jitter` + `armbox`).

## Aceite #1 — binário nativo produz resultado idêntico à JVM ✅

```
target\armbox.exe --truffle testdata\hello.elf
  → stdout "hello from a real ELF\n", exit 42   (idêntico à JVM)

target\armbox.exe --truffle testdata\busybox-armv5l sh -c "echo a; echo b"
  → stdout "a\nb\n", exit 0                      (idêntico à JVM)

target\armbox.exe testdata\hello.elf   (backend padrão = ASM/JIT)
  → UnsupportedOperationException clara, exit 1  (rejeição correta da armadilha)
```

Comparação byte-a-byte (stdout + exit code) entre o binário nativo e
`java -cp <mesmos jars> dev.vitorsilverio.armbox.Main` feita para os dois ELFs reais —
sem diferença.

## Aceite #2 — TraceCompilation prova blocos compilados 🔴 NÃO ALCANÇADO

Tentativa com `-Dpolyglot.engine.TraceCompilation=true` (mesma flag que A0 usou para
confirmar compilação real no spike) contra um workload real com loop (`busybox sh -c
'i=0; while [ $i -lt N ]; do i=$((i+1)); done'`, forçando o MESMO bloco ARM — poucas
instruções: incremento, comparação, branch — a ser executado milhares de vezes, bem
acima do `HOT_THRESHOLD=3` do armbox e de qualquer threshold razoável do engine
Truffle):

- **No binário nativo**: toda tentativa de compilação (`opt failed`, centenas
  observadas) falha com
  `jdk.graal.compiler.code.SourceStackTraceBailoutException: Object of type
  Lcom/oracle/truffle/api/impl/FrameWithoutBoxing; should not be materialized (must
  not pass virtual object into an invoke that cannot be inlined)` — 0 `opt done` em
  qualquer tentativa, com ou sem thresholds/orçamentos de inlining aumentados
  (`engine.InliningExpansionBudget`/`engine.InliningInliningBudget` a 100.000, bem
  acima do default, sem efeito).
- **Na JVM (JBR 25 + Truffle Unchained, com os MESMOS flags de launcher do
  `RELATORIO-A0.md`: `--upgrade-module-path` com o compiler 25.0.1 + `--module-path`
  com truffle-api/runtime/compiler/polyglot + `--add-modules=org.graalvm.truffle`)**:
  o MESMO workload falha com um bailout DIFERENTE —
  `PermanentBailoutException` por `PEGraphDecoder.tooDeepInlining` — mas com o MESMO
  resultado final: 0 `opt done`.

**Causa raiz identificada (não corrigida — fora do escopo de uma task de demo):** o
`TruffleBlockRootNode` (A2) delega toda semântica de op para
`IrBlockExecutor#executeOp` (A3), um único método com um `switch` exaustivo sobre TODO
`IrOp.Kind` (ALU, ShiftedRegister, memória, branches, LDM/STM, PSR/SWI, ARMv6/Thumb-2 —
tudo). Isso foi uma decisão consciente e correta para A2/A3 (evita reimplementar
semântica, G1 intacto) — mas sem especialização por nó Truffle (cada op deveria virar
um nó próprio que o Graal aprende a especializar/podar via profiling), a
avaliação parcial (PE) tenta expandir/inlinear TODOS os ramos do switch para
QUALQUER bloco, nos dois ambientes — e estoura o orçamento de inlining (JBR) ou não
consegue manter o frame virtualizado através do dispatch grande o suficiente (SVM). Os
blocos sintéticos ALU-somente do bench A0/A4 (só MOV/ADD/SUB, sem branches/memória/
sistema) não expunham essa limitação porque eram muito mais simples que qualquer bloco
ARM real; esta foi a primeira vez que o backend Truffle rodou contra um `IrBlockExecutor`
exercitando ramos reais do switch em um loop de verdade.

**Consequência prática medida:** no workload de loop (2000 iterações via busybox sh),
`--truffle` (2,76s) foi mais LENTO que `--interp` (1,77s) no binário nativo — o backend
Truffle hoje paga o custo de tentar (e falhar) compilar repetidamente em background sem
nunca colher o benefício, e a árvore Truffle interpretada pura parece mais lenta que o
loop Java do `InterpretedCodeEmitter`. **O backend Truffle, do jeito que está hoje
(A2/A3), não deve ser considerado pronto para uso — nem em JVM normal nem em
native-image** — a motivação original da trilha A (viabilizar JIT dentro de
native-image) permanece só parcialmente demonstrada: o binário roda, mas sem
compilação real dos blocos do guest.

**Recomendação para o time (decisão do usuário, fora do escopo desta task):** uma
eventual task futura (ex. "A6") precisaria refatorar `TruffleCodeEmitter` para nós
especializados por categoria de `IrOp` (padrão real de linguagem Truffle — nó com
`@Specialization`, profiling por site), em vez do dispatcher único de A2/A3, antes que
a compilação real volte a funcionar contra blocos ARM genuínos. Isso é uma mudança de
arquitetura não-trivial, por isso não foi tentada aqui.

## Binário: tamanho, startup

| Métrica | Valor |
|---|---|
| Tamanho do executável (`armbox.exe`) | 39,48 MB (20,38 MB código + 18,22 MB heap de imagem) |
| Tempo de build (`mvn -Pnative package`, incremental, máquina de 8 threads) | ~3min10s |
| Startup (`--truffle testdata/hello.elf`, média de 5 execuções) | **~218 ms** |
| Startup JVM equivalente (JBR 25, `java -cp <jars> ... --truffle hello.elf`, média de 5) | **~348 ms** |

Startup nativo ~1,6× mais rápido que a JVM — bem abaixo do ganho de 10-100× típico de
apps native-image triviais, porque a imagem ainda carrega e inicializa toda a máquina
Truffle/Graal-em-runtime (mesmo sem nunca compilar nada com sucesso neste teste) — o
custo fixo de ter o compilador Graal embarcado na imagem para o tier "quente" não é
grátis mesmo quando esse tier não entrega valor (ver Aceite #2).

## Passos de build reproduzíveis

Pré-requisitos: GraalVM 25 em `E:\graalvm-jdk-25.0.3+9.1` (ou o path real do GraalVM do
usuário) e Visual Studio 2022 com "Desktop development with C++" instalado (fornece
`cl.exe`/`link.exe`/Windows SDK).

```bat
:: 1. Instalar o arm-jitter (core + truffle) no repo Maven local — JBR 25 é suficiente aqui.
cd arm-jitter
set JAVA_HOME=C:\Users\user\.jdks\jbr-25.0.3
mvn -o install -DskipTests

:: 2. Build nativo do armbox — PRECISA do ambiente MSVC carregado + JAVA_HOME=GraalVM.
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
set JAVA_HOME=E:\graalvm-jdk-25.0.3+9.1
set PATH=%JAVA_HOME%\bin;%PATH%
cd armbox
mvn -Pnative -DskipTests package
:: → target\armbox.exe
```

Uso do binário (mesma CLI da JVM, `Backend.TRUFFLE` é a única opção viável sob
native-image):

```bat
target\armbox.exe --truffle testdata\hello.elf
target\armbox.exe --truffle testdata\busybox-armv5l sh -c "echo a; echo b"
target\armbox.exe --interp testdata\hello.elf
:: backend padrão (ASM) falha de propósito, com mensagem clara:
target\armbox.exe testdata\hello.elf
```

Diagnóstico de compilação (reproduz o achado do Aceite #2):

```bat
target\armbox.exe -Dpolyglot.engine.CompilationFailureAction=Print ^
    -Dpolyglot.engine.FirstTierCompilationThreshold=20 --truffle ^
    testdata\busybox-armv5l sh -c "i=0; while [ $i -lt 5000 ]; do i=$((i+1)); done; echo done $i"
```

## Validação do reactor (não native-image)

`mvn -o test` (JAVA_HOME=JBR 25) verde em `arm-jitter` (raiz, `core`+`truffle`) e em
`armbox` — inclui os testes novos de `Backend.TRUFFLE`.
