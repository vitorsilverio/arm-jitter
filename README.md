# arm-jitter

Biblioteca Java 25 para executar, depurar e futuramente compilar blocos ARM/THUMB em emuladores de Game Boy Advance, Nintendo DS e outros dispositivos ARM.

O projeto não possui `Main`: ele é um core auxiliar para ser embutido por um emulador hospedeiro.

## Estado atual

Esta estrutura define os contratos principais da arquitetura e um interpretador frio inicial:

- `core`: registradores, CPSR, modos bancados, SPSR, exceções iniciais e avaliação condicional.
- `memory`: barramento abstrato `AddressSpace`.
- `decoder`: contrato de decodificação ARM/THUMB.
- `ir`: representação intermediária imutável.
- `jit`: runtime, cache de blocos e estratégia interpretado/JIT.
- `codegen`: contrato para emissores de código.
- `swi`: callbacks de SWI sem obrigar entrada na BIOS.

O interpretador e o lifter IR atuais cobrem uma fatia pequena, mas testável, de ARM/THUMB: `MOV`, `ADD`, `ADC`, `SUB`, `RSB`, `SBC`, `RSC`, `NEG`, `CMN`, `MUL`, `MLA`, `UMULL/UMLAL/SMULL/SMLAL`, `CLZ`, `SWP/SWPB`, `MRS/MSR` por registrador e `MSR` imediato, `AND/EOR/ORR/BIC/MVN/TST/TEQ`, shifts THUMB, operand2 ARM com shift imediato, shift por registrador e `RRX`, `CMP`, high-register ops THUMB, escrita ALU em `PC` e retorno `S` via `SPSR`, branch incondicional, branch condicional THUMB, `BX`, `BL` ARM/THUMB, `LDR` literal THUMB, `PUSH/POP` THUMB, ajuste de SP THUMB, `LDM/STM` ARM com modos `IA/IB/DA/DB`, bit `^` inicial e máscara vazia ARM7TDMI, `LDMIA/STMIA` THUMB incluindo máscara vazia, `LDR/STR` word/halfword/byte ARM imediato e offset por registrador simples/subtrativo/shiftado incluindo `RRX`, writeback pre-index/post-index ARM, loads ARM assinados byte/halfword, load em `PC`, fetch ARM/THUMB alinhado, leitura word desalinhada com rotação e halfword desalinhado aproximados ao ARM7TDMI, `LDR/STR` word/halfword/byte THUMB imediato e offset por registrador, SP-relative THUMB e `SWI`.

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
| `JVM_BYTECODE` | `armThumb(...)` | Bytecode JVM via ASM + otimizador GBA | **Padrão recomendado** |
| `INTERPRETED_IR` | `interpretedArmThumb(...)` | Loop Java sobre `IrOp[]` | Debug, step-by-step, oráculo de testes |

### Uso recomendado

```java
// Produção — bytecode JVM com constant fold + DCE + flag merge
JitRuntime runtime = JitRuntimeFactory.armThumb(1024, 3);
int cycles = runtime.execute(core.programCounter(), core);
long frameSliceCycles = core.runBlocks(runtime, 256);
```

### Debug / oráculo

```java
// Interpretador IR puro — útil para comparar comportamento ou depurar
JitRuntime oracle = JitRuntimeFactory.interpretedArmThumb(1024, 3);
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
// PER_OP: instrucoes condicionais/Swap ficam inline no bloco compilado em vez de cair no interpretado
AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.PER_OP, StandardIrOptimizer.gba());

// Contadores de diagnóstico
long native   = emitter.nativeBlockCount();
long fallback = emitter.fallbackBlockCount();
long perOpOps = emitter.perOpFallbackOpCount();
emitter.resetCounters();

// Tipos de IrOp emitidos nativamente (todos exceto Swap)
Set<Class<? extends IrOp>> supported = AsmCodeEmitter.supportedOps();
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

## Compilação e testes

Por regra do projeto, o agente não deve executar compilação/testes fora do sandbox. Execute localmente:

```bash
mvn test
```
