# arm-jitter

Biblioteca Java 25 para executar, depurar e compilar (JIT) blocos ARM/THUMB em emuladores de Game Boy Advance, Nintendo DS e outros dispositivos ARM.

O projeto não possui `Main`: ele é um core auxiliar para ser embutido por um emulador hospedeiro. É usado em produção pelo gbaemu e pelo ndsemu.

## Estado atual

Pacotes principais:

- `core`: registradores, CPSR, modos bancados, SPSR, exceções e avaliação condicional.
- `memory`: barramento abstrato `AddressSpace` + invalidação SMC.
- `decoder`: decodificação ARM/THUMB.
- `arch`: `ArmArchitecture`/`ArmFeature` — presets `ARMV4T` e `ARMV5TE` e quirks por arquitetura.
- `ir` / `ir.opt`: representação intermediária imutável + otimizador (constant fold, DCE, flag merge).
- `jit`: runtime tiered, cache de blocos com inline cache e encadeamento de blocos.
- `codegen` / `codegen.jvm`: emissores de código (interpretado, bytecode JVM via ASM) + harness de equivalência.
- `coprocessor`: barramento de coprocessadores (CP15 etc. ficam no hospedeiro).
- `swi`: callbacks de SWI sem obrigar entrada na BIOS.
- `debug`: `GdbServer` (stub GDB remote serial) e trace listener.

Cobertura de instruções: **ARMv4T completo** (ARM7TDMI — todo o conjunto ARM/THUMB
incluindo quirks de LDM/STM, acessos desalinhados com rotação e máscaras vazias) e
**ARMv5TE** (ARM9E — `BLX`, `CLZ`, DSP multiplies `SMUL/SMLA/SMULW/SMLAW`, saturating
`QADD/QSUB/QDADD/QDSUB`, `LDRD/STRD`, load-to-PC com interworking), tudo com emissão
nativa no backend JVM. O interpretador é a referência de semântica; o backend compilado
passa em harness de equivalência e em runs longos de divergence-checking com ROMs reais.

O core já possui bancos de `SP/LR` por modo, banco FIQ para `r8-r14`, `SPSR` por modo privilegiado, entrada real de `SWI` no vetor `0x08` quando não há handler host registrado, entrada de `IRQ` no vetor `0x18` quando `interruptLine` está ativa e o bit I do CPSR está limpo, e entrada de instrução indefinida no vetor `0x04` para opcodes ainda não implementados.
Também há estado explícito de `HALT/STOP` para integração com registradores de I/O do dispositivo e waitstates opcionais por acesso de memória via `AddressSpace.accessCycles(...)`.

## Uso basico

```java
AddressSpace memory = new AddressSpace() {
    @Override public int read8(int address) { return 0; }
    @Override public int read16(int address) { return 0; }
    @Override public int read32(int address) { return 0; }
    @Override public void write8(int address, int value) { notifyWrite(address); }
    @Override public void write16(int address, int value) { notifyWrite(address); }
    @Override public void write32(int address, int value) { notifyWrite(address); }
};

ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
core.setProgramCounter(0x08000000);
core.step();
core.step(16);
```

Para skip BIOS ou restaurar snapshot de boot, configure PC e CPSR juntos para que
os bancos de registradores acompanhem o modo da CPU:

```java
core.setBankedRegister(CpuMode.SUPERVISOR, 13, 0x03007FE0);
core.setBankedRegister(CpuMode.IRQ, 13, 0x03007FA0);
core.configureExecutionState(
        0x08000000,
        CpuMode.SYSTEM,
        InstructionSet.ARM,
        false,
        true);
```

Para investigar loops de BIOS/ROM, instale um trace leve:

```java
core.setTraceListener(new ArmTraceListener() {
    @Override
    public void afterInstruction(ArmCore tracedCore, DecodedInstruction instruction) {
        System.out.printf(
                "pc=%08X raw=%08X kind=%s cpsr=%08X r0=%08X sp=%08X lr=%08X%n",
                instruction.address(),
                instruction.raw(),
                instruction.kind(),
                tracedCore.cpsr().get(),
                tracedCore.register(0),
                tracedCore.register(13),
                tracedCore.register(14));
    }
});
```

Para implementar waitstates de memória no emulador hospedeiro, sobrescreva
`accessCycles`. Esses ciclos extras são acumulados em `core.cycles()`:

```java
@Override
public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
    if ((address & 0x0E000000) == 0x08000000) {
        return type == MemoryAccessType.INSTRUCTION_FETCH ? 3 : 5;
    }
    return 0;
}
```

Para mapear `HALTCNT` ou um mecanismo equivalente:

```java
core.halt();
core.setInterruptLine(true);
```

## Gerando IR

```java
IrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());
IrBlock block = lifter.lift(memory, 0x08000000, 32);
```

## Runtime JIT

O pipeline é o mesmo (cache → decode → lift → otimizar → emit); o que muda é o backend:

| Backend | Factory | Comportamento | Quando usar |
|---------|---------|---------------|-------------|
| `JVM_BYTECODE` | `armThumb(...)` | Tiered: tier frio interpretado + tier quente em bytecode JVM (ASM, fallback `PER_OP`, compilação em pool de threads) | **Padrão recomendado** (default dos consumidores gbaemu/ndsemu) |
| `INTERPRETED_IR` | `interpretedArmThumb(...)` | Loop Java sobre `IrOp[]` | Debug, step-by-step, oráculo de testes |
| `TRUFFLE` | `TruffleJitRuntimeFactory.truffleArmThumb(...)` (módulo opcional `arm-jitter-truffle`) | Tiered: tier frio interpretado + tier quente em nós Truffle/AST, compilados por partial evaluation quando o host roda sob Graal/JVMCI | Blocos/traces grandes (superblocos) e `native-image` (trilha A) — **opt-in**, não é default de nenhum consumidor |
| (ambos) | `divergenceCheckingArmThumb(...)` | Executa cada bloco pelos dois backends e lança na primeira divergência | Diagnóstico de bugs de codegen com ROM real |

### Backend Truffle/GraalVM — quando escolher (task A4)

O backend Truffle vive no módulo opcional `arm-jitter-truffle` (o core não depende de
API Truffle) e é exposto por `TruffleJitRuntimeFactory.truffleArmThumb(cacheEntries,
hotThreshold[, architecture])`, espelhando `JitRuntimeFactory.armThumb(...)`: mesmo
pipeline tiered (frio interpretado + quente em background) e mesmo otimizador
`StandardIrOptimizer.gba()` (aplicado no nível do `JitRuntime`, já que
`TruffleCodeEmitter` — ao contrário do `AsmCodeEmitter` — não recebe otimizador no
construtor).

**Bench honesto (coletado nesta task, não reaproveitado do spike A0):** compara os
emissores de PRODUÇÃO (`AsmCodeEmitter`/`TruffleCodeEmitter`, não o protótipo
descartável da A0) executando o mesmo bloco ALU reto (`MOV`/`ADD`/`SUB` +
`Cycle`/`Fetch` por instrução, sem otimizador — igual à metodologia do
`RELATORIO-A0.md`) em tamanhos crescentes, best-of-5 × 2.000.000 execuções, mesma
máquina/sessão:

| Ambiente | JVM (`java -version`) | Bloco | ASM (`JVM_BYTECODE`) | Truffle (Graal) | truffle/asm |
|----------|------------------------|------:|----------------------:|-----------------:|------------:|
| JBR 25 puro + Truffle **Unchained** (`-XX:+EnableJVMCI --upgrade-module-path=<compiler 25.0.1> --module-path=<truffle-api/runtime/compiler> --add-modules=org.graalvm.truffle`) | `openjdk 25.0.3` (JBR-25.0.3+9-480.61) | 20 instr | 3,93 ns | 20,36 ns | 5,17× (pior) |
| idem | idem | 80 instr | 58,36 ns | **43,51 ns** | 0,75× (melhor) |
| idem | idem | 320 instr | 734,62 ns | **134,62 ns** | 0,18× (5,5× melhor) |
| GraalVM standalone (`E:\graalvm-jdk-25.0.3+9.1`, instalado pelo usuário) | `Java HotSpot(TM) 64-Bit Server VM 25.0.3` — banner `Oracle GraalVM 25.0.3+9.1 (build 25.0.3+9-LTS-jvmci-b01)` | 20 instr | 3,70 ns | 19,40 ns | 5,24× (pior) |
| idem | idem | 80 instr | **15,30 ns** | 33,70 ns | 2,20× (pior) |
| idem | idem | 320 instr | 693,00 ns | **96,60 ns** | 0,14× (7,18× melhor) |

`Truffle.getRuntime().getName()` confirmado como `"GraalVM CE"` na via JBR/Unchained e
como `"Oracle GraalVM"` na via standalone — em ambos os casos prova de compilação real
pelo Graal (não fallback interpretado). "JBR 25 puro" e "Unchained" são, na prática, **a
mesma via hoje**: o JBR usa o JVMCI embutido para rodar o Graal community como compilador
de Truffle sem precisar trocar de JDK (confirmado pela A0 e reconfirmado aqui) — não
existe uma segunda via "JBR sem Unchained" que compile Truffle de verdade neste ambiente,
então a tabela não duplica a linha.

**Nota sobre a distribuição standalone:** o instalador que o usuário baixou
(`E:\graalvm-jdk-25.0.3+9.1`) é **Oracle GraalVM**, não GraalVM CE — o `release` do
diretório lista módulos enterprise (`com.oracle.graal.graal_enterprise`,
`com.oracle.svm.enterprise.truffle`) que não existem na distribuição community pura (desde
o GraalVM 21 a Oracle passou a distribuir um único binário "Oracle GraalVM" gratuito via
GFTC, com o compilador Graal Enterprise embutido, em vez do antigo binário separado
"GraalVM CE"). Não há hoje uma distribuição GraalVM CE 25 LTS instalada nesta máquina;
os números acima são da via standalone realmente disponível, e são a melhor aproximação
possível de "Graal fora do JBR" — mantendo a ressalva no nome da coluna.

**Achado não previsto:** a via standalone tem um C2 (HotSpot) que compila o bloco reto de
80 instruções MUITO melhor que o C2 do JBR (15,3 ns vs 58,36 ns — quase 4× mais rápido no
mesmo hardware/sessão) — troca a posição do crossover: no JBR/Unchained o Truffle já vence
a partir de 80 instruções; na via standalone o ASM ainda vence a 80 e só perde a 320. O
lado Truffle/Graal, em contraste, é consistente entre as duas vias (43,51/38,0 vs 33,70 ns
a 80; 134,62 vs 96,60 ns a 320 — a via standalone é sempre igual ou mais rápida). A
diferença está inteiramente do lado do C2/ASM: builds de HotSpot diferentes (JBR vs
Oracle GraalVM), mesma versão 25.0.3, produzem qualidade de código bem diferente para um
método reto gigante — não é um artefato de metodologia (verificado com 3 execuções
consecutivas, resultado estável em todas). Conclusão prática: o ponto de crossover onde
compensa trocar para Truffle **depende da JVM de destino**, não é um número fixo.

**Recomendação:** para o tamanho de bloco típico de jogo (pequeno — a maioria dos blocos
reais em gbaemu/ndsemu tem poucas dezenas de instruções), o **ASM continua a escolha
certa** em qualquer uma das 3 linhas medidas — vence com folga a 20 instruções (5–5,2×) e
é o backend padrão dos dois consumidores (não muda, G3). O Truffle vale a pena quando o
bloco/trace é grande o suficiente para o C2 degradar, mas ONDE exatamente isso acontece é
JVM-dependente (a partir de ~80 instruções no JBR/Unchained; só a partir de algum ponto
entre 80 e 320 na via standalone) — candidato natural são os *loop-superblocos* da trilha
C (`C0`), que devem medir no ambiente de destino em vez de assumir o crossover de uma
única JVM. Fora de performance pura, o Truffle é a única opção viável sob `native-image`
(ASM define classes em runtime, incompatível — ver A5). gbaemu/ndsemu não têm nenhuma
integração com o backend Truffle hoje (fora de escopo desta task — factory é opt-in, sem
wiring nos consumidores); os benches acima medem os emissores de produção diretamente
dentro do arm-jitter, não dentro de um jogo real via ROM.

### Uso recomendado

```java
// Produção — bytecode JVM com constant fold + DCE + flag merge (ARMv4T por padrão)
JitRuntime runtime = JitRuntimeFactory.armThumb(1024, 3);

// Para um core ARM9E (NDS): mesmas factories com a arquitetura explícita
JitRuntime arm9 = JitRuntimeFactory.armThumb(1024, 3, ArmArchitecture.ARMV5TE);

int cycles = runtime.execute(core.programCounter(), core);
long frameSliceCycles = core.runBlocks(runtime, 256);
```

### Encadeamento de blocos

Blocos quentes podem se encadear sem voltar ao dispatch do runtime, dentro de um
orçamento de ciclos por chamada de `execute`:

```java
runtime.setChainCycleBudget(96); // 0 desliga (default)
```

O budget é sensível a timing entre CPUs no hospedeiro (handshakes de boot cross-CPU);
suba com medição e validação de boot dos jogos de referência.

### Debug / oráculo

```java
// Interpretador IR puro — útil para comparar comportamento ou depurar
JitRuntime oracle = JitRuntimeFactory.interpretedArmThumb(1024, 3);
```

### Depuração com GDB

`GdbServer` expõe o core como um stub GDB remote serial (como o do mGBA): registradores,
memória, breakpoints em PC, watchpoints de escrita, step e continue:

```java
GdbServer.listenAndServe(3333, core, memory, () -> stepOneInstruction());
// arm-none-eabi-gdb> target remote :3333
```

### Introspecção

```java
CodegenBackend backend = runtime.codegenBackend(); // JVM_BYTECODE para armThumb(...)
```

### Migração de `jvmArmThumb`

`JitRuntimeFactory.jvmArmThumb(...)` está depreciado desde a Fase 8. Substitua por `armThumb(...)`:

```java
// Antes
JitRuntime runtime = JitRuntimeFactory.jvmArmThumb(1024, 3);

// Depois — inclui otimizador GBA (constant fold + DCE + flag merge)
JitRuntime runtime = JitRuntimeFactory.armThumb(1024, 3);
```

### Política de fallback e métricas (AsmCodeEmitter)

```java
// PER_OP: ops ainda não emitidas nativamente são despachadas ao interpretado inline
// no bloco compilado, em vez de derrubar o bloco inteiro
AsmCodeEmitter emitter = new AsmCodeEmitter(
        ArmArchitecture.ARMV5TE, AsmFallbackPolicy.PER_OP, StandardIrOptimizer.gba());

// Contadores de diagnóstico
long native   = emitter.nativeBlockCount();
long fallback = emitter.fallbackBlockCount();
long perOpOps = emitter.perOpFallbackOpCount();
emitter.resetCounters();

// Tipos de IrOp e opcodes ALU emitidos nativamente
Set<Class<? extends IrOp>> supported = AsmCodeEmitter.supportedOps();
Set<IrOpCode> supportedAlu = AsmCodeEmitter.supportedAluOpcodes();
```

### Testes de equivalência entre emissores

```java
BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
harness.assertEquivalent(
        new InterpretedCodeEmitter(),
        candidateEmitter,
        irBlock,
        EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 1)));
```

## Invalidação SMC

Para invalidar blocos automaticamente em escrita de memoria:

```java
AddressSpace memoryWithInvalidation = new InvalidationAwareAddressSpace(deviceMemory, runtime);
ArmCore core = new ArmCore(memoryWithInvalidation, SwiDispatcher.empty());
```

Para interceptar SWIs no host mantendo acesso ao numero solicitado:

```java
SwiDispatcher dispatcher = SwiDispatcher.empty();
dispatcher.fallbackWithNumber((swi, state) -> {
    return state.withR0(swi);
});
```

Para publicar no repositorio Maven local e consumir em outro emulador:

```bash
mvn install
```

Coordenadas Maven atuais:

```xml
<dependency>
    <groupId>dev.vitorsilverio</groupId>
    <artifactId>arm-jitter</artifactId>
    <version>1.0</version>
</dependency>
```

## Roadmap

Planos futuros (backend Truffle/GraalVM, ARMv6K/Thumb-2/ARMv7-A, AArch64, perf) estão
em [ROADMAP.md](ROADMAP.md), divididos em fases com critérios de aceite.

## Compilação e testes

Por regra do projeto, o agente não deve executar compilação/testes fora do sandbox. Execute localmente:

```bash
mvn test
```
