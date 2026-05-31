# arm-jitter

Biblioteca Java 25 para executar, depurar e futuramente compilar blocos ARM/THUMB em emuladores de Game Boy Advance, Nintendo DS e outros dispositivos ARM.

O projeto nao possui `Main`: ele e um core auxiliar para ser embutido por um emulador hospedeiro.

## Estado atual

Esta estrutura define os contratos principais da arquitetura e um interpretador frio inicial:

- `core`: registradores, CPSR, modo de CPU e avaliacao condicional.
- `memory`: barramento abstrato `AddressSpace`.
- `decoder`: contrato de decodificacao ARM/THUMB.
- `ir`: representacao intermediaria imutavel.
- `jit`: runtime, cache de blocos e estrategia interpretado/JIT.
- `codegen`: contrato para emissores de codigo.
- `swi`: callbacks de SWI sem obrigar entrada na BIOS.

O interpretador e o lifter IR atuais cobrem uma fatia pequena, mas testavel, de ARM/THUMB: `MOV`, `ADD`, `SUB`, `MUL`, `AND/EOR/ORR/BIC/MVN/TST/TEQ`, shifts THUMB, `CMP`, high-register ops THUMB, branch incondicional, branch condicional THUMB, `BX`, `BL` ARM/THUMB, `LDR` literal THUMB, `PUSH/POP` THUMB, ajuste de SP THUMB, `LDMIA/STMIA` ARM/THUMB, `LDR/STR` word/halfword/byte ARM imediato, `LDR/STR` word/halfword/byte THUMB imediato, SP-relative THUMB e `SWI`.

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

## Gerando IR

```java
IrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());
IrBlock block = lifter.lift(memory, 0x08000000, 32);
```

## Runtime JIT interpretado

Enquanto o emissor ASM nao entra, `InterpretedCodeEmitter` executa blocos IR e permite integrar o pipeline completo no emulador:

```java
JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(1024, 3);
int cycles = runtime.execute(core.programCounter(), core);
long frameSliceCycles = core.runBlocks(runtime, 256);
```

Para invalidar blocos automaticamente em escrita de memoria:

```java
AddressSpace memoryWithInvalidation = new InvalidationAwareAddressSpace(deviceMemory, runtime);
ArmCore core = new ArmCore(memoryWithInvalidation, SwiDispatcher.empty());
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

## Compilacao e testes

Por regra do projeto, o agente nao deve executar compilacao/testes fora do sandbox. Execute localmente:

```bash
mvn test
```
