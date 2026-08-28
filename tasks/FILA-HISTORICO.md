# FILA-HISTORICO.md — detalhe completo de sessões e tasks já FECHADAS

Este arquivo existe só para preservar o relato minucioso (achados, comandos, números de
teste) de trabalho já concluído. **Agentes de sessões novas NÃO devem ler este arquivo** a
menos que estejam investigando uma regressão específica e precisem do histórico exato de
uma sessão passada — o protocolo normal (`tasks/README.md`) só manda ler `FILA-EXECUCAO.md`
(a fila ativa, enxuta) + o arquivo da task do dia. Ler este arquivo inteiro por hábito é
desperdício de contexto/custo.

Organização: mesma ordem cronológica que estava em `FILA-EXECUCAO.md` antes da compactação
de 2026-08-17.

---

## Ondas 1 e 2 — detalhe completo (histórico compactado em FILA-EXECUCAO.md)
## Histórico — ondas 1 e 2 FECHADAS (detalhes no índice do `tasks/README.md`)

- **Onda 1 — ✅ 100%** (2026-07-16): C11 · B2.6 · B1.7 · B4.0.4 · B4.0.4.1 · C6 ·
  D1 · D2 · D3 · D4 · A6 · B5.1 · E1.
- **Onda 2 — ✅ tudo que um agente podia executar** (2026-07-16/17): B2.7 · B2.8 ·
  B3.1 · B3.3 · B7.1 · C8 · C10. As 2 "restantes" (**C7** e **A7**) NÃO são
  executáveis por agente sozinho — exigem o usuário presente (validação de
  gameplay / máquina GraalVM+MSVC) e moram na seção "🧑 Bloqueadas no usuário".
  **Se você é um agente procurando trabalho: a onda 2 está TERMINADA para você;
  vá direto à tabela da onda 3.**
- Da onda 3 já fecharam: **B3.2** ✅ e **B3.4** ✅ (2026-07-17, VFP IrOps +
  executor interpretado); **B3.5** ✅ (2026-07-22, decoder VFP CP10/11 ARM+Thumb-2);
  **B3.6** ✅ (PR1 2026-07-22 + PR2 2026-07-23, emissão ASM nativa VFP+inteiro v7);
  **B3.7** ✅ (2026-07-23, fecha o épico B3 por completo) — preset público
  `ArmArchitecture.ARMV7A` (arm-jitter) + `--arch=armv7a`/`armv7a-torture.s`
  (28 checagens)/`hello-float.c` (gcc hard-float real, série de Leibniz em
  `double`, zero libc) no armbox. Achado: a validação N3 revelou um bug REAL de
  `baseValueOverride` ausente em `VfpLoad`/`VfpStore`/`VfpMultipleTransfer`
  (literal pool `VLDR Dx,[pc,#imm]` lia endereço errado, sem o viés `+8` do PC
  — JIT e interpretado divergiam entre si), corrigido no arm-jitter com 3
  testes de regressão novos. Todos os 3 backends (JIT/interp/check) idênticos
  nos dois binários reais; suítes arm-jitter+armbox+gbaemu+ndsemu verdes.
- **B5.2** ✅ (2026-07-23, preset `ArmArchitecture.ARM11_MPCORE` — ARMv6K +
  VFPv2, sem Thumb-2; fecha o épico B5 por completo, já que B5.1 fechou em
  2026-07-16).
- **B7.2** ✅ (2026-07-23, `MProfileExceptionModel`: MSP/PSP, xPSR, stacking
  automático, EXC_RETURN — ver índice do `tasks/README.md` para os detalhes).
- **B7.3** ✅ (2026-07-23, `MProfileSystemControl`: SCS/NVIC/VTOR/SysTick
  memory-mapped, pendência/prioridade/preempção no `MProfileExceptionModel`,
  `ExceptionModel.hasPendingException`/`enterPendingException` plugados em
  `ArmCore.servicePendingIrq` — ver índice do `tasks/README.md` para os
  detalhes, incl. o achado de bug do `SysTick.tick()` com granularidade fina).
- **B7.4** ✅ (2026-07-23, `MRS`/`MSR` SYSm + `CPS` de 16 bits do perfil M +
  presets `ARMV6M`/`ARMV7M`: `IrOp.MProfileSystemRegister` + tabela SYSm no
  `MProfileExceptionModel`, feature nova `M_FAULT_MASKING` gateando
  BASEPRI/FAULTMASK/`CPS f`, `ARMV6M` sem Thumb-2 largo e `ARMV7M` sem VFP;
  21 testes novos — ver índice do `tasks/README.md`. G5 verde nas 3 suítes).
- **B7.5** ✅ (2026-07-23, **fecha o épico B7 por completo**) — armbox
  `--machine=cortex-m` (`CortexMMachine`: flash/RAM/SCS fixos via `PagedAddressSpace`
  C3, boot pela tabela de vetores) + semihosting novo no arm-jitter (`IrOp.Breakpoint`
  + `BkptDispatcher`, decode Thumb `BKPT`, gated por `ArmFeature.BREAKPOINT`) +
  firmware torture m0/m3 + `hello-cortexm.c` (gcc real, sem CRT). Achado de bug real
  (categoria "+ arm-jitter só se sair bug"): `DivergenceCheckingCodeEmitter`
  (`--check`) sempre criava o core scratch com `AProfileExceptionModel` default,
  nunca com o `MProfileExceptionModel` real — corrigido tratando todo bloco como
  oráculo-only (sem comparação por bloco) sob perfil M, mesmo precedente do
  SWI/Coprocessor. Suítes arm-jitter + armbox + gbaemu + ndsemu verdes. Ver índice
  do `tasks/README.md` para os detalhes.

- **B6.1** ✅ (2026-07-24, backlog priorizado pelo usuário — épico novo AArch64) — esqueleto
  IR-64 (`ir64/Ir64Op` e afins, espelho estrutural do `IrOp` 32-bit mas sem `condition()`
  universal — A64 não tem predicação geral), `core64/Aarch64Core` + `core64/PstateRegister`
  (NZCV próprio, não reusa `CpsrRegister`), `memory/AddressSpace64`, `decoder64/Aarch64Decoder`
  (ADR/ADRP, ADD/SUB imm, MOVZ/MOVN/MOVK, B/BL/B.cond/CBZ/CBNZ/TBZ/TBNZ/BR/BLR/RET/SVC) e
  `executor64/Ir64BlockExecutor` (interpretador direto, sem JIT/cache — isso é B6.4). Oráculo de
  decode = corpus golden-file montado com devkitA64 real (`aarch64-none-elf-as`/`objdump`, já
  instalado em `C:\devkitPro\devkitA64\bin` — nenhum encoding foi inventado à mão). Achou 2 bugs
  reais antes do commit (off-by-one no shift da classe de instrução top-level; array de
  registradores gerais dimensionado para 30 em vez de 31, X0-X30). Zero mudança no pipeline
  32-bit (G2/G3). `mvn -o test`/`install` verdes.
- **B4.0.3** 🟡 PARCIAL (2026-07-24): `hello-thumb2.c` (bitfields/STRD/TBB/qsort, gcc real)
  fechado + achou e corrigiu um bug REAL no arm-jitter (`ArmArchitecture.ARMV7A` decodificava
  `UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` como UNDEFINED em encoding **Thumb-2**, mesmo com as
  features certas no preset — decoders construídos com o objeto de features errado). Item 3
  (busybox Thumb-2) NÃO fechado — precisa de um toolchain `arm-linux-*` real (musl/glibc), que
  não existe neste ambiente (Windows/MSYS2 sem WSL configurado; devkitARM é bare-metal). Ver
  índice do `tasks/README.md` para os detalhes.
- **B6.2** 🟡 PARCIAL (2026-07-24, próxima fatia do épico AArch64 depois de B6.1): loads/stores
  completos no arm-jitter — `Ir64Op.Load64`/`Store64`/`LoadStorePair`/`LoadLiteral64` (tamanhos
  B/H/W/X+sign-extend, unsigned-offset/`LDUR`/pre-index/post-index/registrador+extend
  UXTW·LSL·SXTW·SXTX, `LDP`/`STP` — o idioma de prólogo/epílogo, sem ele nenhum binário real roda
  — e `LDR`/`LDRSW (literal)`), decode do grupo `x1x0` verificado campo a campo contra corpus REAL
  (apêndice do `corpus.s`/`.bin`/`.objdump.txt` de B6.1, montado de novo com `aarch64-none-elf-as`/
  `objdump`, offsets `0x94`-`0x114`) + `AddressSpace64.read64`/`write64` (default, dois
  `read32`/`write32`). armbox: `--arch=aarch64` no `Main`, pacote novo `aarch64/` — `Elf64Loader`
  (ELF64 `EM_AARCH64`/`ET_EXEC`), `Aarch64GuestMemory` (paginada em `Map<Long,byte[]>`, não um
  array como a de 32 bits — endereço de 64 bits não cabe), `Aarch64LinuxAbi` (tabela de syscall
  arm64 genérica, números DIFEREM do ARM32: `write`=64/`exit`=93/`exit_group`=94/`brk`=214/
  `mmap`=222), `Aarch64LinuxGuest` (~10 syscalls implementadas), `Aarch64LinuxMachine` (só
  `Ir64BlockExecutor` interpretado — SEM JIT para A64 ainda, isso é B6.4 futuro). **Aceite #1
  fechado**: `hello-aarch64.s` escrito à mão (`svc` write+exit cruas, sem libc) montado com
  `aarch64-none-elf-gcc -nostdlib -static` (devkitA64 bare-metal — resolve o toolchain sem
  precisar de `aarch64-linux-*`/musl real, já que o binário não usa runtime nenhum do toolchain)
  produz ELF64 válido; `armbox --arch=aarch64 hello-aarch64.elf` imprime a mensagem e sai com
  código 42, idêntico ao esperado. **Aceite #2 (busybox aarch64) NÃO fechado, mesmo bloqueio
  documentado em B4.0.3 item 3 acima**: busybox.net não publica binário estático aarch64/arm64
  (só `armv8l` = ARM 32-bit em silício v8, ISA errada) e o devkitA64 instalado é bare-metal (sem
  libc de userspace para compilar da fonte) — sem uma fonte confiável de busybox estático arm64
  real ou um toolchain `aarch64-linux-*` (musl/glibc), fica pendente para sessão com esse
  ambiente disponível. Suítes arm-jitter (core+truffle) + armbox + gbaemu + ndsemu revalidadas
  verdes (G5). Ver índice do `tasks/README.md` para os detalhes.
- **B6.3.1** ✅ (2026-07-24/25, primeira das 4 sub-tasks de B6.3): `logical (immediate)`
  (`AND`/`ORR`/`EOR`/`ANDS`) via `DecodeBitMasks` (transcrito de
  `translate-a64.c:5224-5286`, validado por property test EXAUSTIVO — `2×64×64=8192`
  combinações — contra uma SEGUNDA implementação independente por array de bits/rotação por
  índice, não a mesma fórmula de shift-e-OR) + ALU por registrador (`ADD`/`SUB`/`ADDS`/`SUBS`
  shifted register e extended register, records novos `Ir64Op.AluShiftedRegister`/
  `AluExtendedRegister`, enums novos `Ir64ShiftType`/`Ir64AluExtendType`) + dispatch novo de
  classe top-level "Data Processing — Register" (`isDataProcessingRegisterClass`/
  `decodeDataProcessingRegister`, do qual **B6.3.2**/**B6.3.3** dependem). Corpus real
  estendido (apêndice do mesmo `corpus.s`/`.bin`/`.objdump.txt` de B6.1/B6.2, offsets
  `0x118`-`0x184`, `aarch64-none-elf-as`/`objdump` reais). **Achado real reportado, NÃO
  corrigido (fora do escopo desta task)**: `Ir64BlockExecutor#executeAlu` (B6.1,
  `Ir64Op.Alu64`/ADD-SUB imediato) resolve `Rd|SP`/`Rn|SP` checando SÓ a flag booleira
  (`dstIsStackPointer`/`src1IsStackPointer`), nunca o ÍNDICE do registrador (`==31`) — o
  pseudocódigo real do manual e o próprio `readBaseRegister`/`writeBaseRegister` (load/store,
  mesmo arquivo) checam o índice; confirmado com um teste descartável que `add x4, x5,
  #0x123` grava em `SP` em vez de `X4`. O código NOVO desta task (`AluExtendedRegister`)
  implementa a checagem CORRETA (por índice, espelhando `readBaseRegister`/`writeBaseRegister`)
  — não copia o padrão do B6.1. Bug do B6.1 fica para uma task de correção futura (fora do
  "Inclui" de B6.3.1). `mvn -o test` verde (1022 testes); gbaemu/ndsemu **não revalidados**
  (aceite da própria task confirma que G5 não se aplica — nenhum arquivo 32-bit tocado).
- **B6.3.2** ✅ (2026-07-25, segunda das 4 sub-tasks de B6.3): `CSEL`/`CSINC`/`CSINV`/`CSNEG`
  via `case` novo em `decodeDataProcessingRegister` (fixo de 9 bits `011010100` em `[29:21]` +
  bit11=0) — record `Ir64Op.ConditionalSelect`, executor só LÊ `PSTATE` (nunca escreve NZCV),
  sem atalho para os aliases `CSET`/`CSETM`/`CINC`/`CINV`/`CNEG` (caminho geral com
  `src1==src2`/`==XZR` já basta). `SBFM`/`BFM`/`UBFM` via `case 0b10` novo em
  `decodeDataProcessingImmediate` — record `Ir64Op.Bitfield` com `immr`/`imms` CRUS (D2, sem
  pré-cálculo de `pos`/`len` no decoder); executor com UM cálculo de `pos`/`len` compartilhado
  pelos 3 opcodes (só a política de preenchimento fora do campo muda: sinal/zero/preserva-Rd,
  testado com `Rd` pré-populado no caso `BFM`). Os 11 aliases de bitfield do épico e os 5 de
  `CSEL` cobertos de graça, sem `case` de decode dedicado. Corpus real estendido (offsets
  `0x188`-`0x1f8`, `aarch64-none-elf-as`/`objdump` reais do devkitA64, incl. um vetor extra
  `csneg x25,x26,xzr,eq` para exercitar `CSNEG` com `src2=XZR`). `opc==0b11` (`EXTR`, fora de
  escopo) continua `unsupported`. `mvn -o test` verde (1074 testes); gbaemu/ndsemu **não
  revalidados** (G5 não se aplica, nenhum arquivo 32-bit tocado — mesmo precedente de
  B6.1/B6.2/B6.3.1).
- **B6.3.3** ✅ (2026-07-25, terceira das 4 sub-tasks de B6.3): `MADD`/`MSUB` via `case` novo
  em `decodeDataProcessingRegister` (fixo de 8 bits `11011000` em `[28:21]`, `o0`(15) seleciona
  `MADD`/`MSUB`) — record `Ir64Op.MultiplyAccumulate`, executor faz a multiplicação/soma em
  `long` puro (overflow silencioso já é a truncagem módulo `2^64` exigida) e lê CADA operando
  via `xForWidth` explicitamente (D2, nunca confiando no invariante de zero-extensão do QEMU).
  `MUL`/`MNEG` (aliases com `Ra=XZR`) cobertos de graça, sem `case`/atalho dedicado — o caminho
  geral já produz o resultado certo com o acumulador lendo `0`. `SDIV`/`UDIV` via `case` novo
  (fixo `11010110` em `[28:21]` + opcode `00001` em `[15:11]`, bit10 seleciona `UDIV`/`SDIV`) —
  record `Ir64Op.Divide`, divisor `0` checado ANTES de dividir (retorna `0`, sem exceção —
  diferente de Java puro), `UDIV` via `Long.divideUnsigned`/`Integer.divideUnsigned` (nunca `/`
  comum), `SDIV` com overflow (`MIN_VALUE / -1`) deixado acontecer via `/` de Java (já trunca
  sem lançar). Corpus real estendido (offsets `0x1fc`-`0x228`, `aarch64-none-elf-as`/`objdump`
  reais do devkitA64, cobrindo `MADD`/`MSUB`/`MUL`/`MNEG` em `W`/`X` e `SDIV`/`UDIV` em `W`/`X`).
  `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` (fora do escopo fechado do épico) NÃO implementadas.
  `mvn -o test` verde (1097 testes: 1074 anteriores + 23 novos); gbaemu/ndsemu **não
  revalidados** (G5 não se aplica, nenhum arquivo 32-bit tocado — mesmo precedente de
  B6.1/B6.2/B6.3.1/B6.3.2).
- **B6.3.4** ✅ (2026-07-26, quarta e ÚLTIMA das 4 sub-tasks de B6.3 — **B6.3 fecha 100%**):
  `LDXR`/`LDAXR`/`STXR`/`STLXR` via `decodeExclusive` novo em `Aarch64Decoder` (`case
  SUBCLASS_EXCLUSIVE_ATOMIC`, antes só `unsupported`) — as 4 mnemônicas compartilham o MESMO
  encoding `@stxr` (D0, só o bit `lasr` difere: `LDAXR`/`STLXR` sem decodificar `LDXR`/`STXR`
  decodificaria só metade dos valores do bit, não é opção coerente); `sz` reaproveita a mesma
  codificação de `Ir64MemSize`/`SINGLE_SIZE_*` já usada por `LDR`/`STR` (B6.2). Records novos
  `Ir64Op.LoadExclusive`/`StoreExclusive` (D2), `acquireRelease` como NOP observável (mesma
  convenção de `IrOp.MemoryBarrier` de 32 bits). `Aarch64ExclusiveMonitor` novo em `core64/`
  (D1 — sibling ESTRUTURAL de `core.ExclusiveMonitor`, não generalização, mesmos nomes de
  método, `IdentityHashMap<Aarch64Core, Reservation>`, sem comparação de valor); `Aarch64Core`
  ganha a API pública espelhando `ArmCore` 1:1 nos nomes
  (`markExclusiveMonitor`/`exclusiveMonitorCovers`/`clearExclusiveMonitor`/
  `notifyOrdinaryWrite`/etc.). Executores `executeLoadExclusive`/`executeStoreExclusive`
  (checa o monitor ANTES de escrever — mesma armadilha crítica de `STREX`/B1.4). **Achado
  real**: `executeStore`/`executeLoadStorePair` (B6.2) não chamavam `notifyOrdinaryWrite`
  nenhuma — auditoria da Especificação #2 da task pegou e corrigiu (sem a chamada, o teste de
  escrita comum abrindo o monitor falhava). `LDXP`/`STXP`/`CAS`/`LDAR`/`STLR` (mesmo subgrupo,
  fora de escopo) continuam `unsupported`, confirmado por regressão negativa. Corpus real
  estendido (offsets `0x22c`-`0x268`, `aarch64-none-elf-as`/`objdump` reais do devkitA64,
  4 mnemônicas × 4 tamanhos B/H/W/X). Teste novo `Aarch64ExclusiveAccessTest` espelha
  `ArmV6ExclusiveAccessTest` (precedente B1.4). `mvn -o test` verde; gbaemu/ndsemu **não
  revalidados** (G5 não se aplica, nenhum arquivo 32-bit tocado, `core.ExclusiveMonitor`
  intocado — mesmo precedente de B6.1/B6.2/B6.3.1-3). Limpeza do monitor em entrada de exceção
  fica pendência explícita (A64 ainda sem modelo de exceção síncrona/assíncrona). Próximo passo
  natural do épico B6 maior: **B6.4** (backend ASM 64-bit) ou **B6.5**/**B6.6** — nenhuma spec
  nova escrita/executada, fica para o usuário priorizar depois.

- **B4.1.2** ✅ (2026-07-26, segunda das 5 sub-tasks do épico B4.1/MMU-softmmu depois de
  B4.1.1): `memory/mmu/Cp15VmsaCoprocessor` novo ligando `MCR`/`MRC` aos controles do
  wrapper da B4.1.1 (`SCTLR.M`/`V`, `TTBR0`, `DACR`, `TLBIALL`/`TLBIMVA`, `CONTEXTIDR`→ASID,
  `c7` como NOP, `DFSR`/`IFSR`/`DFAR`/`IFAR` só armazenamento) — ver índice do
  `tasks/README.md` para o detalhe completo, incl. o aceite executado via `ArmCore.step()`
  real (sequência `TTBR0→DACR→SCTLR.M=1`). Próximo da ordem do épico: **B4.1.3** (aborts
  precisos — captura de `MemoryTranslationException` nos 2 motores).
- **B4.1.3** ✅ (2026-07-26, terceira das 5 sub-tasks do épico B4.1/MMU-softmmu, "aborts
  precisos"): `ArmCore.enterMemoryAbort` novo (converte `MemoryTranslationException` em
  `PREFETCH_ABORT`/`DATA_ABORT`, preenchendo FAR/FSR via `MemoryAbortListener` novo — mesmo
  padrão aditivo de `CoprocessorBus`/`BkptDispatcher` — ANTES de `setProgramCounter`+
  `requestException`; `instructionAddress` é o endereço da PRÓPRIA instrução, não o sequencial
  seguinte, porque `AProfileExceptionModel` já soma +4/+8) + `Cp15VmsaCoprocessor implements
  MemoryAbortListener` (grava `DFAR`/`DFSR`/`IFAR`/`IFSR` de verdade, fechando a última
  pendência documentada da B4.1.2). Captura nos DOIS motores: `ArmCore#executeSingleInstruction`
  (interpretador de um passo — `pc` já capturado antes do decode/lift/execute é o endereço
  certo, cobre fetch E dados da mesma instrução) e `IrBlockExecutor#execute` (motor único
  compartilhado por bloco interpretado E fallback PER_OP do JIT — `try` cercando o laço quente
  inteiro sem custo quando não lança; no `catch`, o endereço da instrução é o do próximo
  `IrOp.Fetch` a partir do índice corrente, já que toda instrução termina SEMPRE com
  `Cycle`+`Fetch`, G4). **JIT nativo** (`AsmBlockCompiler`): todo o laço de emissão por-op é
  cercado por um ÚNICO `visitTryCatchBlock` de `MemoryTranslationException` (custo zero no
  bytecode gerado quando não lança); o endereço da instrução-dona de cada op é computado em
  TEMPO DE COMPILAÇÃO (`computeInstructionAddresses`, mesma técnica de scan-para-trás a partir
  do próximo `Fetch`) e gravado num slot fixo (`FAULT_PC_LOCAL`, acima de toda a faixa dinâmica
  do register cache) via `LDC`+`ISTORE` a cada op; o handler faz `emitCacheFlush` (os locais do
  register cache SOBREVIVEM ao unwind dentro do mesmo frame JVM, então registradores já
  escritos antes da falta — ex. no meio de um LDM desenrolado — chegam ao core) e chama
  `core.enterMemoryAbort(...)` antes de retornar com os ciclos parciais. **Achado real corrigido
  no meio do trabalho** (não estava no escopo original, mas sem ele nenhuma falta de BUSCA DE
  INSTRUÇÃO seria possível): `TranslatingAddressSpace.fetch16`/`fetch32` (B4.1.1) já existiam mas
  NINGUÉM os chamava — `ArmDecoder`/`ThumbDecoder` liam `read16`/`read32` direto, então uma
  busca de instrução nunca passava pela TLB de INSTRUÇÃO nem podia gerar `PREFETCH_ABORT`;
  corrigido com `AddressSpace.fetch16`/`fetch32` novos (default delega a `read16`/`read32` —
  ZERO mudança de comportamento para todo barramento existente, G3) e os dois decoders chamando
  `fetch16`/`fetch32` em vez de `read16`/`read32` nos 3 pontos de busca de instrução.
  **MultipleTransfer base-restored cai de graça nos dois motores** (RFC §3): o writeback da
  base já era emitido DEPOIS do laço de registradores em ambos (`IrTransferExecutor`,
  `AsmBlockCompiler#emitMultipleTransferInline`), então uma falta no meio do laço simplesmente
  nunca alcança a linha de writeback — nenhuma mudança extra precisou ser feita ali. Testes
  novos: `ArmCoreMemoryAbortTest` (DATA_ABORT/PREFETCH_ABORT com FAR/FSR reais via
  `Cp15VmsaCoprocessor`, `SUBS PC,LR` retomando a instrução faltosa), `Cp15VmsaCoprocessorTest`
  (`onDataAbort`/`onPrefetchAbort`), `MultipleTransferInterpreterTest` (LDM base-restored),
  `MemoryAbortEquivalenceTest` novo (G1: mesmo bloco lifted de uma instrução real rodando por
  `InterpretedCodeEmitter` E `AsmCodeEmitter`, via `BlockEquivalenceHarness`/`CpuSnapshot`,
  precisa terminar no MESMO estado após o abort — Load e LDM). `FaultingAddressSpace` novo em
  `support/` (decorator de teste que injeta a exceção num endereço configurado, sem precisar
  montar page tables reais — essas já são cobertas por `TranslatingAddressSpaceTest`, B4.1.1).
  `mvn -o test` raiz verde; gbaemu + ndsemu + armbox revalidados verdes (G5 se aplica desta vez:
  `ArmCore`/`IrBlockExecutor`/`AsmBlockCompiler`/decoders são compartilhados). Próximo da ordem
  do épico: **B4.1.4** (`translationGeneration` no `BlockKey`/`JitRuntime`).
- **B4.1.5** ✅ FECHADA (2026-08-14, segunda sessão — **fecha o épico B4.1/MMU-softmmu por
  completo**). O aceite objetivo foi alcançado: o kernel Linux REAL de `testdata/` boota até um
  **shell `busybox` interativo** (`/ #`) nos backends INTERPRETED e JIT, e o shell RESPONDE a um
  comando digitado pelo UART0 (`VersatilePbBootTest` faz os dois: espera o prompt, digita
  `echo LINUX"BOX-SHELL-OK"`, exige a saída sem as aspas — o eco do tty sozinho não passa no
  teste). Boot completo em ~18s interpretado, ~12s no JIT. Foram **4 causas raiz** — nenhuma delas na página de vetores, que era só o sintoma final:
  **(1) A pendência da sessão anterior — o loop de `PREFETCH_ABORT` na página de vetores**:
  `Cp15VmsaCoprocessor` (B4.1.2) só
  atendia a TLB **unificada** (`c8,c7,*`), e o ARM926EJ-S/ARMv5 tem TLBs de instrução e dados
  SEPARADAS: todo `flush_tlb_*` do kernel usa `c8,c6,*` (dados) e `c8,c5,*` (instrução), que caíam
  em UNDEFINED. A exceção de instrução indefinida acontecia ANTES de `early_trap_init` copiar os
  vetores para `0xffff0000`, então o vetor UNDEF continha zeros (`andeq r0,r0,r0` = NOP), a
  execução "escorregava" a página inteira de NOPs e caía em `0xffff1000` (não mapeada) → o abort
  recursivo observado. Corrigido no arm-jitter: `TranslatingAddressSpace` ganhou as 4 operações
  por face (`invalidateInstruction/DataTlbAll`/`...ByMva`; só a de INSTRUÇÃO bumpa
  `translationGeneration`, RFC §5 — mapeamento de dados não invalida bloco compilado) e o
  `Cp15VmsaCoprocessor` passou a atender `c8,c{5,6,7},{0,1,2}` (inclui `TLBIASID`).
  **(2) Segundo bug real, encontrado logo depois** (o kernel passou a bootar inteiro e morrer em
  `Kernel panic: Attempted to kill init!`): `TranslatingAddressSpace.setPrivileged` NUNCA era
  sincronizado com o modo do core (estava documentado como "fora de escopo" desde a B4.1.2), ou
  seja **todo** acesso era privilegiado. Consequência: uma escrita de USUÁRIO numa página
  somente-leitura passava em vez de faltar — o *copy-on-write* do `fork()` do Linux nunca
  disparava, pai e filho seguiam compartilhando fisicamente a mesma pilha e se corrompiam (todo
  processo filho do shell morria com SIGSEGV saltando para um endereço lixo). Corrigido com
  `ModeChangeListener` novo no `ArmCore` (mesmo padrão aditivo de `MemoryAbortListener`, G3),
  disparado nos 2 pontos de troca de modo (`setCpsr` e `switchMode`); `Cp15VmsaCoprocessor`
  implementa a interface e repassa `mode != USER` para o wrapper. O host registra o gancho com
  `core.setModeChangeListener(cp15)`.
  **(3) Terceiro bug real (só o backend JIT/tiered)**: com os dois anteriores corrigidos o
  INTERPRETED chegava ao shell mas o JIT travava logo depois de `Freeing init memory`, com o
  kernel imprimindo `INFO: task kworker/0:0 blocked for more than 120 seconds`. Bisecção: com
  `hotThreshold` gigante (nenhum bytecode ASM emitido) o JIT travava IGUAL — logo não era emissão
  ASM, era o `JitRuntime` **tiered**, cuja única diferença é executar BLOCOS desde a primeira
  visita (o caminho não-tiered interpreta instrução a instrução até o bloco esquentar). Causa:
  `StandardIrBlockLifter` já terminava o bloco quando a leitura ADIANTADA estourava o barramento
  (`IndexOutOfBoundsException`), mas deixava escapar `MemoryTranslationException` — ou seja, o
  lift de um bloco que apenas ENCOSTA na página seguinte (que a CPU talvez nunca execute)
  entregava ao host um `PREFETCH_ABORT` no PC de INÍCIO do bloco (o único que o `JitRuntime`
  conhece), o kernel "consertava" o endereço errado e retornava para o mesmo lugar. Corrigido
  tratando as duas exceções igual: bloco não-vazio → termina o bloco; bloco vazio → propaga (aí a
  falta é da instrução que a CPU vai executar agora, e vira o abort real). Depois disso o JIT
  boota em ~12s, mais rápido que o interpretado. Testes novos em `StandardIrBlockLifterTest`
  (leitura adiantada faltando termina o bloco; falta na PRIMEIRA instrução propaga).
  **(4) Quarto achado, do lado do `virtual-arm-box` (ex-`linuxbox`)**: o `busybox-armv5l` de `testdata/` é um binário
  **hard-float** (`e_flags` traz `EF_ARM_ABI_FLOAT_HARD`) e usa prólogos VFP reais
  (`VPUSH {d8-d14}`) não gateados por `HWCAP_VFP` — sem VFP o PID 1 tomava SIGILL. O hospedeiro
  passou a montar o core com `ARMV5TE + ArmFeature.VFPV2` (+ `VfpDecoder`), que é o **ARM926EJ-S
  com a VFP9-S opcional**, exatamente o que o `-cpu arm926` do QEMU modela. Só mudança de
  `virtual-arm-box`, nenhum preset novo no arm-jitter. (O kernel segue imprimindo `VFP support: not
  present` porque a sondagem lê `FPSID` via `MRC p10,7,...` e o `VfpDecoder` só decodifica
  `FPSCR` — sem consequência prática aqui, num ARMv5 não há `CPACR` gateando CP10/CP11.)
  **Armadilha registrada** (custou uma rodada de teste): o FIFO de recepção do PL011 tem 16
  posições e descarta o excedente como hardware real — digitar uma linha inteira de uma vez faz o
  guest receber só os 16 primeiros bytes, sem o `\n`. O teste digita um byte por lote de fatias.
  Testes novos no arm-jitter: `StandardIrBlockLifterTest` (2, acima), `Cp15VmsaCoprocessorTest` (faces de TLB separadas encaminhadas e
  independentes; `ModeChangeListener` sincronizando privilégio — escrita de usuário em página
  `AP=01` falta com `SECTION_PERMISSION` e não chega à memória) e `TranslatingAddressSpaceTest`
  (independência real das duas faces + só a de instrução bumpa a geração). `Main` do `virtual-arm-box`
  ganhou drenagem não bloqueante de `stdin` para o UART0 (shell realmente usável na linha de
  comando). `mvn -o test` verde em arm-jitter (1327 core + 13 truffle), virtual-arm-box, gbaemu (244),
  ndsemu (183) e armbox (41) — G5 se aplica (`ArmCore`/CP15/MMU/lifter são compartilhados).
  **Desvios que PERMANECEM** (não são bugs, são falta de toolchain — ver `testdata/README.md`):
  kernel Debian 3.2 pré-compilado + ATAGs em vez de `versatile_defconfig` mainline + DTB, e
  ARM926EJ-S/ARMv5TE em vez do ARM1176/ARMv6K da RFC decisão 2. Fechar isso exige um
  `arm-linux-gnueabihf-*` real ou WSL — mesmo bloqueio de B4.0.3/B6.2/B6.6.6.
- **B4.1.5 (sessão 1)** 🟡 PARCIAL (2026-07-26, quinta e última sub-task do épico B4.1/MMU-softmmu — repo
  novo `virtual-arm-box`): hospedeiro `versatilepb`-like completo (RAM 128MiB via `PagedAddressSpace`
  C3, `Pl011Uart`/`Sp804DualTimer`/`Pl190Vic` transcritos dos respectivos arquivos de
  `hw/*/*.c` do QEMU, `AtagsBuilder`) sobre `TranslatingAddressSpace`+`Cp15VmsaCoprocessor`
  (B4.1.1-B4.1.4, sem tocar). **Desvio forçado documentado** (mesmo bloqueio de toolchain
  `arm-linux-*` de B4.0.3/B6.2): kernel REAL pré-compilado da Debian (`vmlinuz-3.2.0-4-
  versatile`, ARM926EJ-S/ARMv5TE, ATAGs — não um `versatile_defconfig` self-built, que hoje
  exige Device Tree) + `busybox-armv5l` real (achado que destrava o que B4.0.3/B6.2 não
  conseguiram: variante ARM-mode existe nos binários oficiais do busybox.net, ao contrário de
  Thumb-2/AArch64). **2 bugs reais do arm-jitter corrigidos** (commit separado): `ARMV5TE` sem
  `ArmFeature.PRELOAD_HINTS` (PLD é ARMv5TE de verdade, não só ARMv6K — sem isso um `PLD` real
  do kernel virava UNDEFINED) e `JitRuntime.execute`/`executeTiered` sem `try/catch` de
  `MemoryTranslationException` ao redor de `lift(...)` (falta na busca da PRIMEIRA instrução de
  um bloco novo, antes da proteção de B4.1.3 existir — derrubava o processo). **Aceite objetivo
  NÃO alcançado**: sem shell interativo — kernel descomprime e salta para código pós-MMU
  (`0xc0...`) mas trava num `PREFETCH_ABORT` recursivo ao redor da página de vetores altos
  (`0xffff0000`), causa raiz não isolada. `VersatilePbBootTest` prova o marco alcançável hoje
  (mensagem de descompressão do zImage, INTERPRETED e JIT) + testes de unidade por periférico.
  `mvn -o test` verde (virtual-arm-box + arm-jitter + gbaemu + ndsemu + armbox, G5 aplicável). **Fecha
  o épico B4.1 apenas parcialmente** — pendência clara para retomar: isolar a causa do loop de
  abort na página de vetores, ou obter toolchain `arm-linux-gnueabihf-*`/WSL para fechar o
  desvio ARM1176/ARMv6K+DT da RFC por completo.
## F3 (`virtual-arm-box --machine=raspi1`) — sessões 1/3 até a sessão de trace instrução-a-instrução (histórico completo)

Sessões mais recentes (fechamento do CPSR.E, extensão do FdtPatcher) continuam em
`FILA-EXECUCAO.md` — só as sessões abaixo (já superadas por achados posteriores) foram
movidas para cá.
**F3 — sessão 1/3 (2026-08-15) — PARCIAL, infra completa, M1 não fechado (achado de desempenho,
não de correção)**: `virtual-arm-box/device/bcm2835/` novo — `Bcm2835SystemTimer` (contador
livre 1MHz + 4 comparadores com IRQ, `hw/timer/bcm2835_systmr.c`), `Bcm2835Ic` (64 IRQs GPU + 8
ARM, `hw/intc/bcm2835_ic.c` — achado real ao testar: nem a linha `nIRQ` nem os registradores
`IRQ_PENDING_*` excluem a fonte selecionada para FIQ, só `fiqAsserted()` filtra por `fiqSelect`,
diferente do que a intuição sugeriria), `Bcm2835Mailbox` (registradores MAIL0/MAIL1 +
canal 8 de propriedades processado SINCRONAMENTE — simplificação deliberada frente ao QEMU real,
que usa um barramento privado assíncrono — toda tag responde zerada com bit de sucesso, exatamente
o que a spec pedia), `Bcm2835ArmControlBlock` (achado real: IC em `+0x200`/mailboxes em `+0x800`
do bloco `ARM_OFFSET=0xB000` NÃO caem em limites de página de 4KiB cada — diferente do
`versatilepb`, onde cada periférico já nascia alinhado — teve que nascer um dispatcher de janela
único mapeado na página inteira), `Bcm2835Cp15Extras` (mesmo padrão de `VersatileCp15Extras`:
`MIDR`/`CTR` reais do ARM1176JZF-S de `target/arm/tcg/cpu32.c: arm1176_initfn` do QEMU + idioma
`c7`/`Rt=PC` de manutenção de cache). `boot/FdtPatcher` novo — reescreve `/chosen/bootargs` e
`/memory@0/reg` num `.dtb` real sem `dtc`/`libfdt` (achado real ao inspecionar bytes do `.dtb`
oficial do `raspberrypi/firmware`: `reg` vem zerado, `<0x0 0x0>` — quem preenche isso em hardware
real é o `start.elf` proprietário que este repo não tem, então o próprio `FdtPatcher` precisa
fazer essa reescrita, não só a de `bootargs`). `Bcm2835Machine implements Machine`
(`ArmArchitecture.ARM11_MPCORE`, protocolo de boot direto do QEMU — `KERNEL_LOAD_ADDR=0x10000`,
**não** os `0x8000` do bootloader real, que este repo explicitamente não tem — confirmado
testando o `.dtb`/`kernel.img` reais no `qemu-system-arm -M raspi1ap` instalado nesta máquina como
oráculo), registrado `--machine=raspi1` no `Main`. `testdata/raspi1/` (`kernel.img`+
`bcm2708-rpi-b.dtb` reais, `raspberrypi/firmware` commit `12eeaa12865869b07db760f4bbb7507ec6f1976c`,
`initramfs.cpio.gz` reaproveitado do `versatilepb` — `busybox-armv5l` roda em ARMv6K por
compatibilidade retroativa, confirmado) com README de proveniência/sha256.

**M1 redefinido, e mesmo assim NÃO fechado**: a mensagem literal do enunciado
("Uncompressing Linux... done, booting the kernel.") não existe neste `kernel.img` OFICIAL —
confirmado batendo o mesmo binário no `qemu-system-arm -M raspi1ap` como oráculo: o build do
Raspberry Pi Foundation não tem `CONFIG_DEBUG_LL` no estágio de descompressão do zImage. O
marcador equivalente adotado foi `Booting Linux on physical CPU` via `earlycon` (poke direto de
MMIO a partir do `stdout-path` do Device Tree, sem depender do driver `amba`/`pl011` real).
**Mesmo assim, M1 não fecha**: um rastreamento instrução-a-instrução (`ArmCore#step()`, não só
amostragem por fatia — a 1ª tentativa de diagnóstico por amostragem grossa a cada 5000 fatias
LEVOU A UMA CONCLUSÃO ERRADA de "loop travado reiniciando do zero", corrigida só ao rastrear
instrução a instrução) confirma que o boot está CORRETO: `head.S` roda normalmente (NOPs de
alinhamento, `MRS`/`TST`/`MSR` do `safe_svcmode_maskall`, sem UNDEFINED/abort) e entra de fato no
`inflate()` real do zlib (padrão `LOAD_MULTIPLE`/`STORE_MULTIPLE`/`BRANCH_EXCHANGE` do decodificador
Huffman). O problema é de DESEMPENHO: descomprimir os ~7,7MB deste `kernel.img` byte a byte custa
tantas instruções ARM (medido: ~750 milhões de ciclos por 1,28M blocos no backend JIT) que o boot
completo não termina num orçamento de sessão/CI razoável (extrapolação da taxa medida: dezenas de
minutos só na descompressão). **Próximo passo recomendado para a sessão 2/3**: descomprimir o
`kernel.img` no HOST (o payload é um stream gzip padrão embutido no zImage, localizável pelo magic
`1f 8b 08` — a mesma técnica do `scripts/extract-vmlinux` do próprio kernel Linux) e carregar a
imagem JÁ DESCOMPRIMIDA direto no endereço de link esperado, pulando o `inflate()` caro do guest
inteiramente — o resto do protocolo de entrada (r0/r1/r2) não muda. `Raspi1BootTest` tem um smoke
test rápido (prova a infra sem exceção, ~1s) + o teste de M1 real como `@Disabled` documentando o
achado. `mvn -o test` verde no virtual-arm-box (51 testes, 1 skipped=o `@Disabled`);
`VersatilePbBootTest` continua verde (G5 não se aplica — nenhum arquivo de arm-jitter tocado).

**F3 — sessão 2/3 (2026-08-15) — PARCIAL, M1 ainda NÃO fecha, mas 2 bugs reais de CP15
corrigidos no `arm-jitter` e o gargalo de desempenho da sessão 1 foi ELIMINADO**: seguiu o
"próximo passo recomendado" da sessão 1 — `boot.ZImageDecompressor` (novo, `virtual-arm-box`)
localiza o payload gzip padrão embutido no `zImage` (magic `1F 8B 08`, mesma técnica do
`scripts/extract-vmlinux` do kernel Linux) e descomprime com `java.util.zip` NO HOST;
`Bcm2835Machine` carrega a imagem já pronta em `0x8000` (`TEXT_OFFSET`/`AUTO_ZRELADDR` — o
stub real calcularia esse mesmo endereço em tempo de execução para um zImage carregado bem
abaixo de 128MiB, confirmado testando um stub sintético e o real) com o PC apontando direto
para o `stext`, pulando o `inflate()` caro do guest inteiramente. Isso destravou o boot para
avançar MUITO mais fundo no kernel real (de ~90 mil ciclos travado num loop de abort para
centenas de milhares de ciclos, dezenas de funções de kernel distintas visitadas, confirmado
por rastreamento instrução-a-instrução via `ArmTraceListener` temporário) — e revelou 2 bugs
reais e arquiteturais no `arm-jitter`, a primeira validação de sistema real do
`ARM11_MPCORE`/ARMv6K no projeto (user-mode/armbox já validava ARMv6K; nunca um kernel com MMU):
**(1)** `MCR p15,0,Rt,c13,c0,3` (`TPIDRURO`, ponteiro de TLS) não era reconhecido por
`Cp15VmsaCoprocessor` (só `CONTEXTIDR`, `c13,c0,1`) — UNDEFINED tão cedo no boot que os vetores
de exceção ainda não tinham sido copiados por `early_trap_init()`, cascateando num laço
infinito de `PREFETCH_ABORT` (a busca da PRÓPRIA rotina de vetor também falhava, por vetores
ainda não mapeados — mesma FAMÍLIA de bug do "NOP-sled" da B4.1.5, mas a causa raiz aqui é
diferente: registrador CP15 faltante, não TLB de face errada). Corrigido: `c13,c0,{0,2,3,4}`
(FCSEIDR/TPIDRURW/TPIDRURO/TPIDRPRW) viraram armazenamento simples, sem efeito colateral, no
`arm-jitter` (commit separado, `Cp15VmsaCoprocessorTest` ganhou regressão, G5 completo
revalidado: arm-jitter 1341+13, gbaemu 240, ndsemu 183, virtual-arm-box 62 verdes; armbox 38/39
— o 1 que falha, `Armv7TortureTest`, foi confirmado PRÉ-EXISTENTE via `git stash`/rerun no
baseline, `ArrayIndexOutOfBoundsException` em `VfpRegisters`, subsistema não tocado por este
fix, não é regressão desta sessão). **(2)** logo depois, `MRC p15,0,Rt,c0,c1,4` (`ID_MMFR0`,
lido por `build_mem_type_table()` bem cedo em `paging_init()`) e um sub-registrador `c0,c3,4`
sem nome nesta revisão da arquitetura — mesma cascata de `PREFETCH_ABORT`. Corrigido em
`Bcm2835Cp15Extras` (`virtual-arm-box`, board-specific) de forma arquiteturalmente correta, não
um palpite: a ARM GARANTE que ler um sub-registrador de ID não alocado/reservado devolve
UNKNOWN (aqui `0`), NUNCA lança UNDEFINED (mecanismo de compatibilidade futura do próprio
esquema CPUID) — em vez de continuar listando `CRm` um a um à medida que aparecessem, a classe
passou a reivindicar `c0`/`opcode1=0` inteiro; `ID_PFR0..ID_MMFR3`/`ID_ISAR0..5` usam os
valores REAIS do ARM1176JZF-S (QEMU `target/arm/tcg/cpu32.c: arm1176_initfn`, obtidos via
`WebFetch` do repositório público). **M1 AINDA não fecha**: depois dos dois fixes, o boot
esbarra num LIMITE DELIBERADO e JÁ DOCUMENTADO do `arm-jitter` — `CPSR.E=1`/acesso a dado
big-endian (`SETEND BE`, ARMv6) lança `UnsupportedOperationException` de propósito
(`IrExecutionSupport.checkLittleEndianData`, task `B1.5` do `arm-jitter`, decisão de escopo MVP
"só little-endian", "falhar ALTO em vez de corromper silenciosamente"). O kernel ARMv6K real
toca isso bem cedo no boot (antes de `setup_arch()`/`early_trap_init()`, console `earlycon`
ainda vazio quando isso acontece) — **não é um bug desta task, é funcionalidade nova
(suporte a acesso de dados big-endian) fora do escopo cirúrgico "bug real, commit separado"**
que F3 permite. `Raspi1BootTest` documenta o achado completo no Javadoc da classe;
`smokeTestBootsWithoutException` (60 fatias, antes do limite BE8) prova que a infra desta task
está correta hoje; M1/M2/M3 seguem `@Disabled`. `mvn -o test` verde no virtual-arm-box (62
testes, 6 skipped); `VersatilePbBootTest` continua verde. **Para a sessão 3/3**: decisão do
usuário sobre priorizar uma task dedicada de suporte a BE8 no `arm-jitter` antes de M1 poder
fechar de verdade — sem isso, esta task fica travada no mesmo lugar architeturalmente (não é
mais um problema de desempenho nem de CP15 faltante, é um recurso de CPU não implementado).

**BLOQUEIO FECHADO 2026-08-15 pela task `B1.8` do `arm-jitter`** (sessão dedicada, repo
`arm-jitter` — ver o índice de `arm-jitter/tasks/README.md`): suporte real a BE8
implementado (`CPSR.E=1` troca a ordem de bytes de acessos de dados multi-byte via
`Integer.reverseBytes`/troca de halfword nos primitivos compartilhados de
`IrExecutionSupport`/`AsmRuntimeHelpers`; fetch de instrução continua sempre little-endian;
byte único invariante; achado extra: o caminho ASM nativo rodava com `E=1` sem NENHUMA
checagem antes desta task — produzia valor little-endian errado silenciosamente, também
corrigido). `mvn -o test` verde (1367 testes core+truffle) + `mvn -o install` OK; G5
revalidado — gbaemu verde, ndsemu verde, armbox 40/41 (a 1 falha, `Armv7TortureTest`, é a
MESMA pré-existente já documentada acima, subsistema VFP não tocado). **Esta task NÃO tocou
`virtual-arm-box`** (fora do escopo, conforme instrução) — a sessão seguinte de F3 deve
consumir o `arm-jitter` novo via o `.m2` local (já publicado) e destravar `Raspi1BootTest`/
M1/M2/M3 a partir daqui.

**F3 — sessão 3/3 (2026-08-15) — ÚLTIMA sessão do orçamento original, M1 FECHOU, M2/M3
continuam bloqueados por um achado NOVO (não BE8, não CP15, não desempenho)**: confirmado
primeiro que `mvn -o test` no `virtual-arm-box` já pegava o `.m2` local atualizado pela `B1.8`
(jar com timestamp da sessão da B1.8, `dependency` fixa em `1.0.0` no `pom.xml`, sem
necessidade de purgar cache). Reativados os testes `reachesEarlyconBannerAcceiteM1Interpreted`/
`...M1Jit` (estavam `@Disabled` desde a sessão 2/3): **ambos passam, cada um em menos de 1
segundo** — o banner `Booting Linux on physical CPU` aparece muito cedo no log, nenhum bug novo
do `arm-jitter` apareceu. **M1 fechado de verdade, nos dois backends.**

Reativado em seguida `reachesFreeingKernelMemoryAcceiteM2Interpreted`: o boot avança bem além do
`earlycon` (scan físico inicial do FDT funciona — "Machine model: Raspberry Pi Model B",
"Reserved memory: created CMA memory pool..." aparecem certinhos) mas entra num LAÇO DE `Oops`
do próprio kernel (`Unable to handle kernel paging request`, `8<--- cut here ---` repetido) já
em `unflatten_device_tree()`/`fdt_next_tag` — a fase que remapeia o FDT por endereço VIRTUAL via
`fixmap`, logo depois do scan físico inicial. Isso acontece a poucas dezenas de milhares de
instruções do banner do M1, MUITO antes de `Freeing unused kernel memory`.

**Confirmado como divergência REAL via o oráculo QEMU 8.0.0** (`qemu-system-arm -M raspi1ap`,
EXATAMENTE o mesmo `kernel.img`+`bcm2708-rpi-b.dtb`+`initramfs.cpio.gz`+cmdline desta task): o
QEMU boota limpo até enumerar USB (`dwc_otg`/`smsc95xx`) e montar o initramfs (`Trying to unpack
rootfs image as initramfs...`/`Freeing initrd memory`), MUITO além de `Freeing unused kernel
memory` — sem nenhum Oops. Isto não é uma feature faltando (como o BE8 era): é uma divergência de
comportamento observável entre este emulador e uma referência de hardware real para a MESMA
entrada — ou seja, um bug real em algum lugar (`arm-jitter` ou `virtual-arm-box`), mas a causa
raiz NÃO foi isolada nesta sessão.

Investigação com `ArmTraceListener` temporário (removido antes do commit, não faz parte do
código entregue): o texto do PRIMEIRO `Oops` (`PC is at fdt_next_tag+0xec/0x154`, falha de
leitura em `ff8ae000`, `*pgd=0800000e(bad)` — um descritor de SEÇÃO onde o kernel esperava uma
tabela de 2º nível) já aparece no console ANTES de QUALQUER instrução observada pelo listener
atingir o vetor de abort (`0xffff0010`/`0x00000010`); a primeira entrada de vetor que o listener
de fato observa corresponde a um Oops LATER (`kmem_dump_obj+0xa8`, código de diagnóstico que o
próprio kernel roda ao IMPRIMIR o primeiro Oops — uma falha em cascata, não a causa raiz). Achado
colateral: `ArmCore#runBlocks` (o caminho que `Bcm2835Machine#runSlice()` usa, via
`JitRuntime#execute`) entrega pelo menos o PRIMEIRO abort desta sessão sem passar pelo mesmo
`afterInstruction`/`enterMemoryAbort` que `ArmCore#step()` usa — o texto do console é genuíno
(o guest realmente imprimiu aquilo), mas a ferramenta de rastreamento por instrução tem uma
lacuna de cobertura nesse caminho que impediu identificar QUAL instrução exata disparou o
primeiro abort real. Hipóteses de causa raiz NÃO eliminadas (nenhuma confirmada): (a) a PTE nova
que `fixmap_remap_fdt()` cria para mapear o FDT por virtual não fica visível para um walk de
página subsequente (a `TranslatingAddressSpace`/micro-TLB do `arm-jitter` foi inspecionada nesta
sessão e parece correta — miss sempre re-anda a tabela, `invalidateEntry` confere a tag antes de
invalidar — mas isso não foi provado sob o caminho de bloco real); (b) o tamanho/layout fixo de
RAM desta task (256MiB) vs. os ~448MiB que o QEMU sintetiza para `raspi1ap` desloca onde o
fixmap cai o suficiente pra expor um bug de borda; (c) o `.dtb` patcheado pelo `FdtPatcher` está
correto no round-trip (`FdtPatcherTest`), mas não foi validado byte-a-byte contra o que
`fixmap_remap_fdt()` espera do `totalsize` do cabeçalho.

`Raspi1BootTest` documenta o achado completo no Javadoc da classe; M2/M3 voltaram para
`@Disabled` (M1 fica permanentemente ativo e verde). `mvn -o test` verde no `virtual-arm-box`
(62 testes, 4 skipped = M2×2 + M3×2); `VersatilePbBootTest` continua verde. Nenhum arquivo do
`arm-jitter` foi tocado nesta sessão (G5 não se aplica — a investigação usou só um listener
temporário, nunca commitado).

**F3 NÃO fecha por completo** — ficou fora do orçamento original de 3 sessões (M1 fechou, M2/M3
não). Não movida para o histórico; segue na tabela "fila ATUAL" com o status acima. **Próximo
passo recomendado, antes de tentar M2 de novo**: investigar a lacuna de observabilidade do
`ArmTraceListener` sob `ArmCore#runBlocks`/`JitRuntime#execute` (não necessariamente um bug
funcional — pode ser só uma lacuna de instrumentação — mas sem resolver isso primeiro é fácil
consertar o sintoma errado). A `F10` (mesmo repo, regra 6: nunca em paralelo com a F3) fica
BLOQUEADA até a F3 fechar de vez — decisão do usuário se quer abrir uma sessão extra dedicada
a M2 antes de seguir para F10.

**F3 — sessão EXTRA (2026-08-16), além das 3 do orçamento original — M2 continua NÃO fechado,
mas a lacuna de observabilidade foi fechada e duas hipóteses de causa raiz foram descartadas com
evidência concreta**:

1. **`E2` (novo, `arm-jitter`, `trilha-e-manutencao`) — lacuna de observabilidade fechada**:
   `ArmTraceListener` ganhou `onMemoryAbort(ArmCore, int instructionAddress,
   MemoryTranslationException)`, chamado por `ArmCore#enterMemoryAbort` — o único ponto de
   convergência dos 3 caminhos de execução (`step()`, bloco interpretado via `IrBlockExecutor`,
   bloco compilado via `AsmBlockCompiler`/JIT), todos convertendo `MemoryTranslationException` em
   `PREFETCH_ABORT`/`DATA_ABORT` pelo mesmo método, com o PC exato já calculado internamente
   (`IrBlockExecutor#ownerInstructionAddress`) mas sem forma de observar de fora. Aditivo (G3),
   `default` vazio, custo zero quando nenhum listener está instalado. 3 testes novos em
   `ArmCoreMemoryAbortTest` provam paridade do PC exato nos 3 caminhos. `mvn -o test` verde (core)
   + `mvn -o install`; G5 revalidado nesta sessão: gbaemu verde, ndsemu verde, armbox 40/41 (a 1
   falha é a MESMA pré-existente do `Armv7TortureTest`/VFP já documentada, não-regressão).
2. Com o gancho instalado (harness temporário na `virtual-arm-box`, removido antes do commit
   final), o PRIMEIRO fault do loop de Oops da F3 reporta `pc=0xc0a69088` — **bate byte-a-byte**
   com o que o próprio kernel imprime (`PC is at fdt_next_tag+0xec/0x154`). Confirma que o
   interpretador está consistente com o console; a lacuna de antes era só de instrumentação, não
   uma divergência real de execução.
3. **Hipótese (a) da sessão 3/3 (staleness de TLB/PTE da `TranslatingAddressSpace`) DESCARTADA**
   por leitura do código-fonte: `walk()` sempre re-lê `physical.read32(ttbr0Base + l1Index*4)` sem
   cache de L1 para decidir o TIPO do descritor — o valor visto na tradução É o mesmo que o
   diagnóstico do kernel lê. O conteúdo físico real da RAM do guest naquele slot de PGD
   genuinamente é um descritor de SEÇÃO, não é uma questão de visibilidade/cache.
4. **Hipótese (b) da sessão 3/3 (RAM 256MiB vs. ~448MiB do QEMU) TESTADA e DESCARTADA**:
   `RAM_SIZE_BYTES` elevado temporariamente para 512MiB (experimento revertido, não é mudança
   permanente) produz o MESMO fault, mesmo PC, mesmo endereço virtual, mesmo conteúdo de PGD — só
   a reserva de CMA mudou de endereço físico proporcionalmente, como esperado. RAM não é a causa.
5. **Hipótese nova e mais específica, NÃO confirmada (melhor pista disponível)**: os registradores
   do Oops mostram `r5=0xff8ac000` (provável base da janela `fixmap` do FDT) e o endereço que
   falta é exatamente `r5 + 0x2000` — consistente com `fdt_next_tag()` andando sequencialmente
   pela estrutura do FDT e ultrapassando o fim de uma janela `fixmap` mapeada com MENOS páginas do
   que o `totalsize` real do `.dtb` patcheado por `FdtPatcher` exige (não corrupção do `.dtb` em
   si — `FdtPatcherTest` cobre round-trip — mas um possível descompasso entre o `totalsize` que
   `fixmap_remap_fdt()` usa para DECIDIR quantas páginas mapear e o tamanho real após os patches de
   `/memory@0/reg`/`/chosen/bootargs`, que podem crescer o blob). Próximo passo recomendado,
   concreto: monitorar o slot de PGD em `ttbr0Base + 4088*4` (`0xff8ae000 >>> 20`, `ttbr0Base`
   deduzido de `Table: 00004008` do próprio Oops) a cada `slice` via leitura FÍSICA direta (sem
   passar pela MMU) para determinar se ele é escrito alguma vez antes do fault, e comparar o
   `totalsize` do cabeçalho FDT antes/depois do patch contra o tamanho real do array de bytes,
   byte a byte. Detalhe completo no Javadoc de `Raspi1BootTest`. `mvn -o test` verde na
   `virtual-arm-box` (mesmos 62 testes, 4 skipped); M2/M3 continuam `@Disabled`, F3 permanece 🟡 na
   fila "ATUAL" (não movida para o histórico).

**F3 — sessão de fechamento do M2 (2026-08-16) — causa raiz do laço de Oops ISOLADA E CORRIGIDA
(2 bugs reais), M2 ainda NÃO fecha (bloqueio novo e diferente encontrado logo depois)**:

1. **Causa raiz do laço de Oops confirmada via comparação byte a byte contra o oráculo QEMU 8.0.0**
   (monitor HMP `xp` lendo a RAM física do guest real durante um boot limpo, mesmo
   `kernel.img`+`bcm2708-rpi-b.dtb`+`initramfs.cpio.gz`+cmdline): no mesmo slot de L1
   (`swapper_pg_dir`, físico `0x7fe0`, cobre a janela `0xff800000`-`0xff8fffff` onde o kernel
   mapeia o `.dtb` como `MT_MEMORY_RO`), o QEMU produz `0x0800841e` (`AP=01`,`APX=1`, só leitura
   privilegiada) e o nosso produzia `0x0800000e` (`AP=00`,`APX=0`, SEM ACESSO ALGUM — daí o
   `DATA_ABORT` na primeira leitura de `fdt_next_tag()`). Diferença de 2 bits exatos:
   `build_mem_type_table()` (`arch/arm/mm/mmu.c`) só adiciona `APX|AP_WRITE` a `MT_MEMORY_RO`
   quando `cpu_arch >= CPU_ARCH_ARMv6 && (cr & CR_XP)` — `cr` é o próprio `SCTLR` relido via
   `get_cr()`. Log real: `cr=00c5387d` (bit 23/`CR_XP` ligado) vs. nosso `cr=00002001` (desligado),
   **apesar do kernel ter escrito um `SCTLR` com o bit 23 ligado**. Causa: `Cp15VmsaCoprocessor.
   sctlrValue()` reconstruía a leitura só a partir de `M`/`V` (RAZ pro resto) — `CR_XP` "sumia" na
   releitura. **Corrigido no arm-jitter** (`Cp15VmsaCoprocessor`, commit `bd4cfe7`): o valor de 32
   bits escrito agora é armazenado e devolvido por inteiro; só `M`/`V` continuam recomputados a
   partir do estado autoritativo. Aditivo/G3, teste de regressão
   (`sctlrUnmodeledBitsRoundTripOnRead`). G5 revalidado: arm-jitter+gbaemu+ndsemu verdes; armbox
   tem a MESMA falha pré-existente/não relacionada de `Armv7TortureTest`/`VfpRegisters` já
   documentada na entrada da `E2` acima (confirmada via `git stash` que reproduz idêntica sem o fix
   — não é regressão desta sessão).
2. **Segundo bug real, encontrado imediatamente depois do fix acima** (`virtual-arm-box`, commit
   `12159f1`): com o laço de Oops resolvido, um NOVO `Kernel panic - not syncing: Attempted to kill
   the idle task!` aparece em `init_hw_breakpoint()`→`get_debug_arch()`, que lê `DBGDIDR` via
   `MRC p14,0,Rd,c0,c0,0` — nenhum `CoprocessorBus` deste host reivindicava CP14 (depuração) →
   `UNDEFINED` → panic. Oráculo QEMU: `hw-breakpoint: debug architecture 0x0 unsupported.` (o
   `arm1176_initfn` do QEMU não seta `dbgdidr`, fica `0`/RAZ). Corrigido com
   `Bcm2835Cp14Extras` novo, reivindicando CP14 inteiro com RAZ/WI (mesmo precedente de
   `Bcm2835Cp15Extras` para `c7`), encadeado em `Bcm2835Machine#create`.
3. **M2 continua NÃO fechado**: com os dois bugs acima corrigidos, `total faults=0` pelo resto do
   boot testado (INTERPRETED e JIT) — nenhum novo Oops/panic — mas o console para de avançar logo
   depois de `Console: colour dummy device 80x30`, exatamente onde `calibrate_delay()` roda no
   kernel real. **Evidência concreta**: `ModeChangeListener` temporário contando entradas em
   `CpuMode.IRQ` mostrou só UMA IRQ de timer entregue em ~4,8 milhões de fatias/~100s reais (mesmo
   resultado em INTERPRETED e JIT), enquanto o contador livre do `Bcm2835SystemTimer` seguia
   avançando normalmente (~15 minutos de tempo simulado). A emulação de registrador do timer
   (ack/re-armamento) foi relida e está correta. Aponta para o handler de IRQ do kernel nunca
   completar/re-armar, ou para `CPSR.I` ficar mascarado após a primeira entrega e nunca ser
   restaurado no retorno — causa raiz exata NÃO isolada nesta sessão (possível bug real do
   `arm-jitter` no caminho de retorno de exceção IRQ, nunca testado em sistema real com timer
   periódico antes desta task; próximo passo recomendado no Javadoc de `Raspi1BootTest`).
   `mvn -o test` verde na `virtual-arm-box`; M2/M3 continuam `@Disabled` com motivo atualizado. F3
   permanece 🟡 na fila "ATUAL" (M2 ainda não fechou, F10 continua bloqueada).

**F3 — sessão de continuação do M2 (2026-08-16, segunda rodada) — causa raiz REFINADA (1 bug real
corrigido), M2 ainda NÃO fecha (bloqueio novo e diferente revelado pelo fix)**:

1. **Bug real corrigido**: decodificando o `.dtb` real desta task byte a byte, o nó
   `timer@7e003000` declara `interrupts = <1 0>,<1 1>,<1 2>,<1 3>;` com
   `compatible = "brcm,bcm2835-system-timer"` — exatamente o binding do driver mainline
   `drivers/clocksource/bcm2835_timer.c`, cujo `DEFAULT_TIMER` é o comparador **3** (0/1 são
   reservados ao firmware VideoCore). `Bcm2835Machine#runSlice()` só encaminhava o comparador
   **0** do `Bcm2835SystemTimer` para o `Bcm2835Ic` — o clockevent periódico que o kernel arma no
   comparador 3 nunca era entregue. Corrigido: os 4 comparadores agora são encaminhados 1:1 para
   as fontes GPU 0-3 (mesma fiação do `hw/timer/bcm2835_systmr.c` do QEMU).
2. **O fix acima é necessário mas NÃO suficiente — revelou uma TEMPESTADE de IRQ, não mais
   silêncio**: instrumentação temporária (removida antes do commit) provou, por leitura direta
   dos registradores a cada 1M fatias em JIT: `COMPARE3` congelado em `0x27f4` por >250s reais
   (o contador livre passa de `0x10767060` para `0xc64beb0a` no mesmo intervalo), bit 3 de
   `REG_CTRL_STATUS` permanentemente pendente (nunca acked), bit 3 de `IRQ_ENABLE_1` nunca
   mascarado — e mesmo assim a CPU reentra em `CpuMode.IRQ` continuamente (~60.600 vezes por 1M
   fatias, contador de bordas medido diretamente). O handler do kernel para o timer nunca chega a
   fazer `ack`/rearme; o nível fica preso "pendente" e a CPU reentra assim que `CPSR.I` é
   reabilitado no retorno da IRQ anterior. Causa raiz exata NÃO isolada (handler nunca despachado
   vs. escrita do handler não chegando ao dispositivo) — próximo passo recomendado no Javadoc de
   `Raspi1BootTest`: trace instrução-a-instrução via `ArmCore#step()` (não `runBlocks`, já que
   `ArmTraceListener#beforeInstruction` só dispara sob `step()`) logo após a primeira entrada em
   `CpuMode.IRQ`.
   `mvn -o test` verde na `virtual-arm-box` (92 testes, 4 skipped); M2/M3 continuam `@Disabled`
   com motivo atualizado. F3 permanece 🟡 na fila "ATUAL".

**F3 — sessão de reconhecimento (2026-08-16, só diagnóstico de periférico, sem trace de
instrução) — achado que restringe a hipótese**: harness temporário (removido antes do commit)
amostrou `Bcm2835SystemTimer`/`Bcm2835Ic` DIRETO via `read32` (sem passar pela CPU) a cada 5.000
fatias por 2.000.000 de fatias INTERPRETED. `COMPARE3`/`CTRL_STATUS`/`IRQ_ENABLE_1` mudam
**exatamente uma vez** (~75.000 fatias: comparador armado + IRQ desmascarada no controlador —
`request_irq`/`irq_unmask` do driver `bcm2835-armctrl-ic` SUCEDERAM de verdade) e depois ficam
CONGELADOS pelas 1.925.000 fatias seguintes, apesar da CPU reentrar em `CpuMode.IRQ`
continuamente (achado da sessão anterior). Ou seja: não é "o handler parou de rodar depois de um
tempo" — é o corpo do handler nunca rodar nem uma ÚNICA vez. Isso torna a hipótese (a) do
bloqueio anterior (dispatcher de nível superior `bcm2835_handle_irq`/`asm_do_IRQ` nunca alcança o
ISR do timer) a mais provável, e enfraquece a (b) (efeito de escrita não chegando ao dispositivo
— não há evidência de nenhuma escrita, nem errada). Próximo passo recomendado: o trace
instrução-a-instrução via `ArmCore#step()` (backend INTERPRETED) já recomendado antes, mas agora
mirando especificamente em confirmar se o PC, ao reentrar em `CpuMode.IRQ`, chega a alcançar o
corpo de `bcm2835_handle_irq`/`generic_handle_irq` do driver `irq-bcm2835.c`, ou retorna antes
disso. Detalhe completo no Javadoc de `Raspi1BootTest`. `mvn -o test` verde no `virtual-arm-box`;
M1/M2/M3 no mesmo estado das sessões anteriores. Nenhum arquivo de produção tocado (só
Javadoc/documentação desta sessão).

**F3 — sessão de correção da tempestade de IRQ (2026-08-16) — causa raiz ISOLADA E CORRIGIDA (bug
real do `arm-jitter`), M2 ainda NÃO fecha (bloqueio novo e diferente revelado logo depois)**:

1. **Trace instrução-a-instrução via `ArmCore#step()`** (harness temporário, removido antes do
   commit): seguindo o próximo passo recomendado pela sessão anterior, um detector corrigido de
   "primeira entrada REAL em `CpuMode.IRQ`" (checando `mode()==IRQ` E `pc==vetor exato`, não só
   `mode()==IRQ` — a primeira tentativa capturou por engano o `cpu_init()` do kernel fazendo `MSR
   CPSR_c` explícito por IRQ/ABT/UND/FIQ só para programar o SP de cada modo, nada a ver com
   hardware IRQ; achado colateral que também põe em dúvida medições anteriores de "60.600 entradas
   em `CpuMode.IRQ`" como possivelmente incluindo esses falsos positivos) capturou a entrada REAL
   no vetor `0xffff0018` (`highVectors`) → `vector_irq` → `__irq_svc` → `irq_handler`/
   `handle_arch_irq` → `bcm2835_handle_irq`-like. Instrumentação adicional (temporária, direto em
   `Bcm2835ArmControlBlock#read32`, removida antes do commit) provou que o driver LÊ
   `IRQ_PENDING_BASIC` (offset `0x00`) e recebe `0x100` corretamente na primeira leitura — mas as 3
   leituras SEGUINTES, em `addr+1`/`addr+2`/`addr+3` (não alinhadas), caem no `default -> 0` do
   `Bcm2835Ic` (offsets desconhecidos).
2. **Causa raiz identificada em `IrExecutionSupport`/`AsmRuntimeHelpers` (`arm-jitter`)**:
   `readWordForLoad`/`writeWordForStore`/os equivalentes de halfword decompunham TODO `LDR`/`STR`
   não-PC sob `ArmFeature.UNALIGNED_ACCESS` (ligada em `ARM11_MPCORE`/ARMv6K+) em 4 (ou 2) chamadas
   independentes de `AddressSpace#read8`/`write8`, MESMO quando `address` já era múltiplo de 4 (ou
   2) — nunca havia uma checagem de alinhamento antes de escolher o caminho "atravessado". Hardware
   ARMv6+ real faz uma ÚNICA transação de barramento para um acesso JÁ ALINHADO (só o caso
   GENUINAMENTE desalinhado precisa da composição byte a byte, ARM DDI 0406C A3.2.1) — o código
   antigo aplicava o caminho atravessado sempre, então um `LDR` alinhado ao `IRQ_PENDING_BASIC`
   (`0x2000B200`) virava `read8(0x2000B200)|read8(0x2000B201)<<8|read8(0x2000B202)<<16|
   read8(0x2000B203)<<24` — o byte 0 batia (`0x00`, correto), mas os 3 bytes seguintes caíam no
   fallback "offset desconhecido" de QUALQUER periférico deste repositório (nenhum reimplementa
   "byte N da word alinhada" em `read8`/`write8` — nunca precisaram, pois nenhum consumidor
   anterior exercitava um sistema MMIO real sob um preset com `UNALIGNED_ACCESS`), reconstruindo
   `0x00000000` em vez do `0x00000100` real. Driver do kernel (`irq-bcm2835.c`,
   `get_next_armctrl_hwirq`) via sempre "nada pendente" mesmo com o bit certo armado no periférico
   → `bcm2835_handle_irq()` nunca despachava → handler do timer nunca fazia `ack`/rearme → nível
   ficava preso "pendente" → CPU reentrava em `CpuMode.IRQ` assim que `CPSR.I` era reabilitado —
   exatamente a "tempestade de IRQ" das duas sessões anteriores.
3. **Corrigido** (`IrExecutionSupport.java` e `AsmRuntimeHelpers.java`, interpretado E JIT): uma
   checagem de alinhamento (`isWordAligned`/`isHalfwordAligned`, nomeadas por G6) agora decide entre
   o caminho legado de transação única (`read32Arm7`/`loadWord`/etc., correto para o caso alinhado)
   e o caminho atravessado byte a byte (só para endereço GENUINAMENTE desalinhado). No caminho JIT,
   como o `AsmBlockCompiler` não decide alinhamento em tempo de compilação (o endereço tipicamente
   vem de um registrador), a checagem foi movida para DENTRO dos próprios helpers
   `loadWordCrossed`/`storeWordCrossed`/etc. — mesmo ponto único de emissão de antes, decisão em
   tempo de execução. Aditivo/G3, sem mudança de assinatura pública.
4. **3 testes de regressão novos**: 2 no interpretador (`ArmV6UnalignedAccessTest` —
   `alignedWordLoadReadsTheFullMmioRegisterInsteadOfCrossingWithTheFeature`/
   `alignedWordStoreWritesTheFullMmioRegisterInsteadOfCrossingWithTheFeature`, usando um
   `AddressSpace` de teste que modela exatamente o padrão real de `Bcm2835Ic` — só o offset
   alinhado existe, os 3 vizinhos leem/ignoram `0`) + 1 de equivalência nativa
   (`ArmV6UnalignedAccessNativeEquivalenceTest#conditionalAlignedWordLoadStoreMatchInterpretedAcrossAllCodesAndFlags`,
   prova que o `AsmBlockCompiler` não regride para um caminho diferente do interpretado no caso
   alinhado). **Sanidade confirmada por `git stash`**: revertendo só o fix, os 2 testes do
   interpretador falham exatamente como esperado (`expected: <256> but was: <0>` e
   `expected: <-1430532899> but was: <0>`) — prova que os testes são reais, não vácuos.
5. **`mvn -o test` verde no `arm-jitter`** (1361 core+truffle, incl. os 3 novos) + `mvn -o install`.
   **G5 revalidado**: gbaemu 240 verde (17 skipped, pré-existente), ndsemu 183 verde, `virtual-arm-box`
   66 verde (4 skipped = M2×2+M3×2, ver abaixo), armbox 40/41 — a 1 falha (`Armv7TortureTest`,
   `ArrayIndexOutOfBoundsException` em `VfpRegisters`) é a MESMA pré-existente já documentada em
   sessões anteriores da F3/E2, **reconfirmada nesta sessão via `git stash`** (reproduz idêntica COM
   e SEM o fix — não é regressão).
6. **Efeito no boot do raspi1**: com o fix, `Bcm2835Machine.Backend.JIT` avança MUITO além do ponto
   anterior — `Calibrating delay loop... 3.81 BogoMIPS`, `CPU: Testing write buffer coherency: ok`,
   `Setting up static identity map...`, `devtmpfs: initialized` aparecem pela primeira vez (14:43min
   de wall-clock, rodada real medida). **M2 ainda NÃO fecha**: um bloqueio NOVO e diferente aparece
   logo depois — `Internal error: Oops - undefined instruction` em `vfp_enable+0x8/0x20`, chamado
   por `on_each_cpu_cond_mask` ← `vfp_init` ← `do_one_initcall` (ou seja, durante a inicialização do
   subsistema VFP do kernel, com IRQs desligadas) — o processo `init` morre
   (`Kernel panic - not syncing: Attempted to kill init!`). Causa provável: alguma instrução de
   habilitação de VFP (ex. `VMSR FPEXC, Rt` ligando o bit `EN`) que o `ARM11_MPCORE`/`CoprocessorBus`
   deste host ainda não reconhece — NÃO investigado nesta sessão (fora do orçamento). **Backend
   INTERPRETED não foi levado até o fim**: rodada real tentada ficou >49 minutos sem terminar (o
   `@Timeout(30, MINUTES)` do JUnit, modo padrão `SAME_THREAD`, não preempte um laço apertado que
   nunca checa interrupção — só reporta falha DEPOIS que o método retorna) e foi abortada; dado que
   o JIT já deu um resultado definitivo e mais barato, o interpretado fica para quando alguém
   precisar (não é um requisito do aceite rodar até o fim fora do orçamento de uma sessão).
   `reachesFreeingKernelMemoryAcceiteM2Interpreted`/`...M2Jit` voltam a `@Disabled` com o motivo
   atualizado (Oops de `vfp_enable`). **Próximo passo recomendado**: identificar a instrução exata
   que dispara o `UNDEFINED` em `vfp_enable()` (provavelmente `VMSR FPEXC,Rt` ou leitura de
   `FPEXC`/`FPSID` via `VMRS`) — usar o mesmo `ArmTraceListener`/trace instrução-a-instrução desta
   sessão, agora mirando o PC exato do Oops (`vfp_enable+0x8`) via `onMemoryAbort`-like ou
   inspeção direta do decodificador para essa instrução específica; comparar contra o oráculo QEMU
   8.0.0 (`-M raspi1ap`) para confirmar se é falta de suporte do coprocessador CP10/CP11 (VFP)
   nesse ponto do boot ou uma feature de VFPv2/ARM11 genuinamente não modelada. Detalhe completo no
   Javadoc de `Raspi1BootTest`. F3 permanece 🟡 na fila "ATUAL".

**F3 — sessão de investigação do Oops em `vfp_enable` (2026-08-16) — causa raiz ISOLADA E
CORRIGIDA (bug real do `arm-jitter`, hipótese da sessão anterior estava ERRADA), M2 ainda NÃO
fecha (bloqueio novo e MAIS TARDIO revelado logo depois)**:

1. **A hipótese recomendada pela sessão anterior estava errada**: o palpite era que `vfp_enable()`
   executasse `VMSR FPEXC,Rt` ou uma leitura de `FPEXC`/`FPSID` via `VMRS` (registradores VFP,
   CP10/CP11). Lendo o fonte real do kernel (`arch/arm/vfp/vfpmodule.c`, `raspberrypi/linux`
   `rpi-6.18.y`, a MESMA árvore do `kernel.img` desta task, versão confirmada no `testdata/raspi1/
   README.md`): `vfp_init()` chama `on_each_cpu(vfp_enable, NULL, 1)` INCONDICIONALMENTE em
   `cpu_arch >= CPU_ARCH_ARMv6`, ANTES de sondar `FPSID` (a sonda de `FPSID`, protegida por
   `register_undef_hook(&vfp_detect_hook)`/`unregister_undef_hook`, só acontece DEPOIS). O corpo de
   `vfp_enable()` em si:
   ```c
   static void vfp_enable(void *unused) {
       u32 access = get_copro_access();
       set_copro_access(access | CPACC_FULL(10) | CPACC_FULL(11));
   }
   ```
   não toca em NENHUM registrador VFP — `get_copro_access()`/`set_copro_access()` são
   `MRC`/`MCR p15,0,Rt,c1,c0,2`, o **`CPACR`** (Coprocessor Access Control Register, um registrador
   **CP15**, não CP10/CP11), concedendo acesso pleno (`CPACC_FULL`, `0b11`) aos coprocessadores
   10/11 antes de qualquer instrução VFP genuína rodar.
2. **Causa raiz real**: {@code Cp15VmsaCoprocessor} (`arm-jitter`) nunca reivindicava `c1,c0,2` —
   só `c1,c0,0` (`SCTLR`) sob o mesmo `CRn=1`. A leitura de `CPACR` em `vfp_enable+0x8` caía em
   `unsupported()` (`IllegalStateException`/UNDEFINED), exatamente o `Oops - undefined instruction`
   observado, matando o `init`.
3. **Corrigido no `arm-jitter`** (`Cp15VmsaCoprocessor.java`): `CPACR` agora é um campo de
   armazenamento simples (round-trip fiel escrita→leitura), sem enforcement de trap de acesso —
   mesma decisão de escopo já usada para `c7` (manutenção de cache): este host já decodifica VFP
   incondicionalmente a partir de `ArmFeature.VFPV2` do preset, nunca a partir do valor de `CPACR`,
   então simular o "gate" de acesso não teria efeito observável nenhum nesta fase. `CPACR` está no
   MESMO `CRn=1`/`CRm=0` do `SCTLR` (só o `opcode2` difere, `0` vs `2`) — os métodos
   `handles`/`read`/`write` foram ajustados dentro do `case CRN_SYSTEM_CONTROL` existente, não um
   `case` novo (evitando "duplicate case label", erro de compilação cometido e corrigido na hora
   nesta mesma sessão). Aditivo/G3, sem mudança de assinatura pública. 1 teste de regressão novo
   (`cpacrIsStoredAndReadBackWithoutTrapEnforcement`, prova o round-trip com os bits reais que o
   kernel escreve, `CPACC_FULL(10)|CPACC_FULL(11)` = `0b11<<20 | 0b11<<22`).
4. **`mvn -o test` verde no `arm-jitter`** (1364 core+truffle, incl. o novo) + `mvn -o install`.
   **G5 revalidado**: gbaemu verde, ndsemu verde, armbox verde (as 3 suítes revalidadas de forma
   síncrona nesta sessão, em paralelo enquanto o boot do raspi1 rodava em background).
5. **Confirmado ao vivo via harness diagnóstico temporário** (`Raspi1DiagTempTest`, classe de teste
   temporária criada só para esta sessão — loop de fatias em lotes de 100 mil com impressão do
   console a cada mudança, mesmo precedente de instrumentação temporária "removida antes do
   commit" já usado em sessões anteriores da F3; DE FATO removida, não faz parte deste commit): com
   o fix, `VFP support v0.3: not present` aparece **limpo, sem Oops**, e o boot continua bem além do
   ponto anterior — em menos de 4 segundos REAIS (100 mil fatias) o log já mostra `Setting up
   static identity map`, `Memory: 174968K/262144K available`, `devtmpfs: initialized`, `VFP support
   v0.3: not present`, `pinctrl core: initialized`, `NET: Registered PF_NETLINK/PF_ROUTE`, `DMA:
   preallocated 256 KiB pool`, `hw-breakpoint: debug architecture 0x0 unsupported` (o mesmo RAZ de
   `Bcm2835Cp14Extras` de uma sessão anterior, ainda correto), `Serial: AMBA PL011 UART driver`,
   `bcm2835-mbox 2000b880.mailbox: mailbox enabled`, e **3 requisições `raspberrypi-firmware`
   respondidas com sucesso** (`Request 0x00000001`/`0x00000003`/`0x00030046`, todas `status
   0x00000000`), terminando em `kprobes: kprobe jump-optimization is enabled` — tudo isso em MENOS
   DE 4 SEGUNDOS de tempo simulado do próprio kernel (`kernel time=3.465s` no timestamp do log).
6. **M2 ainda NÃO fecha — bloqueio NOVO e MUITO mais tardio no boot que qualquer um dos anteriores**:
   depois de `kprobes: kprobe jump-optimization is enabled`, o console **para de crescer por
   completo** — 27,9 milhões de fatias seguintes (~18 minutos reais, harness diagnóstico com
   orçamento de tempo, não de fatias) sem NENHUM byte novo. Diferente de todos os bloqueios
   anteriores da F3 (laço de Oops do FDT, tempestade de IRQ, Oops de `vfp_enable`), este **não
   produz nenhuma mensagem observável** — não é um crash, é ausência total de progresso (mesma
   assinatura qualitativa da "tempestade de IRQ" antiga: CPU presa em algum lugar sem sinalizar
   erro — mas agora depois de MUITO mais boot bem-sucedido, e sem a evidência de reentrada
   constante em `CpuMode.IRQ` que caracterizava aquele bug). Causa raiz NÃO investigada nesta sessão
   (orçamento esgotado depois do fix do CPACR + validação G5 + confirmação ao vivo). **Próximo passo
   recomendado**: (a) comparar contra o oráculo QEMU 8.0.0 (mesmo kernel+DTB+initramfs+cmdline) para
   ver que linhas de log aparecem logo depois de `kprobes:` no boot real — é o jeito mais barato de
   restringir o espaço de causa raiz antes de qualquer trace; (b) se a comparação não bastar, repetir
   a técnica de trace instrução-a-instrução (`ArmCore#step()` ou o gancho `ArmTraceListener#
   onMemoryAbort` da task `E2`) a partir do ponto exato onde o console para, com atenção a duas
   hipóteses concretas: um `WFI` sem IRQ chegando (suspeita nº1, dado o precedente da tempestade de
   IRQ já corrigida — mas desta vez por FALTA de entrega, não excesso) e uma resposta de mailbox/
   `raspberrypi-firmware` que nunca chega para uma tag ainda não implementada (só 3 tags foram
   respondidas antes de travar; pode haver uma 4ª que o kernel espera e o `Bcm2835Mailbox` deste
   host ignora sem sinalizar erro nem responder, deixando o driver preso num `wait_for_completion`).
7. **`mvn -o test` verde no `virtual-arm-box`** (66 testes, 4 skipped = M2×2+M3×2, motivo do
   `@Disabled` atualizado no Javadoc/anotação de `Raspi1BootTest` para refletir o achado desta
   sessão); `VersatilePbBootTest` continua verde. Commits: arm-jitter `caac7af`, virtual-arm-box
   `c8c3e9e`. F3 permanece 🟡 na fila "ATUAL".

**F3 — sessão de investigação do silêncio pós-`kprobes:` (2026-08-16) — hipótese de WFI/mailbox
DESCARTADA com evidência direta, achado real e independente CORRIGIDO (`virtual-arm-box`, SMC/JIT),
causa raiz do bloqueio principal da F3 ainda NÃO isolada**:

1. **Reviveu o padrão de instrumentação temporária** (`Raspi1DiagTempTest`, removida antes do
   commit, mesmo precedente de sessões anteriores) usando `ArmCore#setTraceListener` com um
   `onMemoryAbort` (gancho da task `E2`, dispara sob `runBlocks`/JIT) para amostrar `sleepState()`/
   `mode()`/`cpsr().irqDisabled()`/estado do `Bcm2835Ic`/`Bcm2835Mailbox` a cada 2M fatias depois de
   `kprobes:`. **As duas hipóteses recomendadas pela sessão anterior estavam erradas**: `sleepState()`
   nunca é `HALTED` (não é um `WFI` sem IRQ) e nenhuma requisição `raspberrypi-firmware` nova
   aparece nem é esperada (não é mailbox travado numa tag não implementada) — o log real mostra só
   3 tags respondidas e nenhuma 4ª sendo aguardada.
2. **Causa real do silêncio**: a CPU está presa num LAÇO DE ABORTOS (`SECTION_TRANSLATION`,
   `MemoryTranslationException`) que nunca produz saída observável porque nenhum handler de kernel
   chega a rodar `printk`/`die()` antes de reabortar — silencioso por natureza, diferente de todos
   os bloqueios anteriores da F3. Confirmado via `onMemoryAbort`: primeiro abort em
   `instructionAddress=0x208d00c0` (`SECTION_TRANSLATION`, inicialmente `INSTRUCTION_FETCH`),
   seguido por dezenas de reaborts consecutivos na MESMA fatia num segundo endereço fixo
   (`0x608e00c0` numa rodada) — o retorno da exceção reencontra a mesma falta, infinitamente, sem
   IRQ nem `printk` para interromper o laço.
3. **Achado real e independente, CORRIGIDO** (`virtual-arm-box`, não `arm-jitter`):
   `Bcm2835Machine#create` nunca envolvia o barramento do `ArmCore` em
   `InvalidationAwareAddressSpace` (`arm-jitter`) — o MESMO decorador que `GbaConsole`/`Armbox` já
   usam há muito tempo (bug histórico idêntico documentado em `gba-game-compat.md`: jogos que
   constroem código na pilha/IWRAM crashavam por escritas não invalidarem blocos JIT em cache).
   `kprobes: kprobe jump-optimization` é a PRIMEIRA vez que este repositório exercita código de
   guest automodificável (o self-test de kprobes arma um breakpoint otimizado logo depois dessa
   mensagem) — sem o decorador, uma escrita do guest numa página com bloco JIT já compilado nunca
   invalidava o cache, e o core continuava executando bytecode compilado a partir do código ANTIGO.
   Corrigido envolvendo `mmu` (não `physical`) em `InvalidationAwareAddressSpace` antes de passar
   ao `ArmCore` — blocos JIT são indexados pelo PC VIRTUAL que o core busca, o mesmo espaço de
   endereço que `TranslatingAddressSpace#write32` recebe. `VersatilePbMachine` tem a MESMA lacuna,
   nunca exercitada porque userspace busybox não se automodifica — **não corrigida nesta sessão**
   (fora do escopo da F3, achado registrado para referência futura).
4. **O fix acima NÃO fecha M2 sozinho**: repetindo a mesma medição antes/depois do fix, o MESMO
   endereço-raiz de abort (`0x208d00c0`, agora capturado como `SECTION_TRANSLATION`/`DATA_READ`)
   reaparece IDENTICO nos dois casos — só a fatia exata muda (~86460 sem o fix, ~77273 com o fix,
   diferença esperada: o fix altera o timing de recompilação JIT, não a lógica). Isso indica
   fortemente a MESMA causa raiz pré-existente nos dois casos, não uma regressão introduzida pelo
   fix — mantido porque é um bug real e de baixo risco por si só (G3, aditivo).
5. **Registros de CPU no momento do primeiro abort** (capturados para a próxima sessão):
   `pc=lr` região — `r14`/LR = `0xc0337b50` (endereço de `.text` do kernel plausível, a instrução
   CHAMADORA é código real); `r0=0xc10611c0`→`0xc10611d0` (incrementa +0x10 entre a 1ª e a 2ª
   tentativa — sugere fixup/retry de algum handler); `r1-r10` todos em faixas plausíveis de
   `.data`/heap do kernel (`0xc0xxxxxx`/`0xc1xxxxxx`); `r11=r12=0`; `r13`/SP=`0xd0821ecc` (fora da
   faixa típica de pilha de boot inicial, possivelmente já vmalloc). O valor lido/desreferenciado
   (`0x208d00c0`) NÃO bate com nenhum registrador `r0-r13` capturado — não é cópia direta de
   registrador, precisa vir de um deslocamento/tabela ainda não identificado. O SEGUNDO endereço
   (para onde a exceção tenta reler, ficando presa) MUDA entre execuções — sugere que a fixup do
   abort depende de algo que varia por execução (ex.: o contador livre do `Bcm2835SystemTimer`),
   não é puramente determinístico, ao contrário do primeiro endereço.
6. **Próximo passo recomendado**: trace instrução-a-instrução (`Bcm2835Machine.Backend.INTERPRETED`
   + `ArmCore#step()`, não `runBlocks`) a partir de ~70 mil instruções depois do boot para capturar
   a instrução EXATA (opcode, não só PC) que produz `0x208d00c0` a partir de `LR=0xc0337b50` —
   provavelmente um `LDR` com deslocamento/indexado a partir de uma tabela ou lista cujo conteúdo
   está corrompido (fonte ainda desconhecida: pode ser um bug real de decodificação/execução do
   `arm-jitter` em alguma instrução ainda não exercitada por gbaemu/ndsemu, já que este é o
   primeiro kernel Linux de sistema real sob `ARM11_MPCORE`). Comparar contra o oráculo QEMU 8.0.0
   (registrador a registrador via monitor HMP, mesma técnica já usada para o bug de `SCTLR`/`CR_XP`)
   no mesmo ponto do boot é o próximo passo mais barato antes de instrumentar mais.
7. **`mvn -o test` verde no `virtual-arm-box`** (66 testes, 4 skipped = M2×2+M3×2, motivo do
   `@Disabled` de `reachesFreeingKernelMemoryAcceiteM2Jit` atualizado no Javadoc/anotação de
   `Raspi1BootTest`); `VersatilePbBootTest` continua verde. Nenhum arquivo do `arm-jitter` tocado
   nesta sessão (G5 não se aplica — `git status` confirmado limpo no `arm-jitter` antes do commit).
   F3 permanece 🟡 na fila "ATUAL".

**F3 — sessão de trace instrução-a-instrução (2026-08-16/17) — 1 bug real do `arm-jitter`
ISOLADO/CORRIGIDO/VALIDADO (G5 completo), M2 ainda NÃO fecha mas a causa raiz do abort storm foi
NARROWED a um achado concreto e específico (uma instrução exata, não mais "algum lugar")**:

1. **Seguiu o próximo passo recomendado**: trace via `ArmCore#step()` a partir de ~77 mil fatias,
   usando a técnica de duas fases (fast-forward via JIT até a fatia exata do primeiro fault,
   detectada com `onMemoryAbort` fazendo o loop lançar uma sentinela — depois troca para `step()`
   instrução a instrução só no trecho final, evitando rodar `step()` do zero). Harness temporário
   `Raspi1DiagTempTest` (7 fases, removido por completo antes do commit).
2. **Bug real corrigido no `arm-jitter`**: o primeiro fault reportava `DATA_READ` em vez de
   `INSTRUCTION_FETCH` — errado, já que a falha acontece na BUSCA da instrução em `0x208d00c0`.
   Causa: `InvalidationAwareAddressSpace` (decorador adotado na sessão anterior para resolver
   SMC/kprobes) nunca sobrescrevia `fetch16`/`fetch32` — caíam no `default` de `AddressSpace`, que
   delega à PRÓPRIA `read32` do decorador (caminho de DADOS do delegado), não a `fetch32` dele
   (caminho de INSTRUÇÃO, TLB separada). Toda busca de instrução sob este decorador perdia a TLB de
   instrução e o tipo `INSTRUCTION_FETCH` — uma falha de busca virava `DATA_ABORT` em vez de
   `PREFETCH_ABORT` (vetor errado, correção de PC errada, -4 em vez de -8). Mesma lacuna em
   `DualInvalidationAwareAddressSpace` (usado pelo ndsemu) e em `translationGeneration()` (também
   não encaminhado — quebraria invalidação de bloco JIT após troca de `TTBR0`/`CONTEXTIDR` para
   qualquer futuro consumidor MMU deste decorador). **Corrigido**: as duas classes agora
   sobrescrevem `fetch16`/`fetch32`/`translationGeneration` encaminhando ao delegado — aditivo/G3,
   4 testes de regressão novos (delegado de teste com valores DIFERENTES em `fetchNN` vs. `readNN`
   prova que o caminho certo é chamado; sanidade confirmada via `git stash`, testes falham
   exatamente como esperado sem o fix). `mvn -o test` verde no `arm-jitter` (1368 core+truffle) +
   `mvn -o install`; G5 revalidado: gbaemu verde, ndsemu verde, armbox 40/41 (mesma falha
   pré-existente de `Armv7TortureTest`/`VfpRegisters` documentada em toda sessão anterior da F3, não
   é regressão).
3. **O fix não fecha M2 sozinho**: com o tipo corrigido, o MESMO endereço (`0x208d00c0`) continua
   faltando (`SECTION_TRANSLATION`), agora como `INSTRUCTION_FETCH` corretamente. Deixar a execução
   CONTINUAR além do primeiro fault (em vez de parar nele) mostra 27,5 MILHÕES de reaborts idênticos
   em 400 mil fatias, console sem crescer 1 byte — confirma que era um bug real e correto de se
   corrigir, mas não a causa raiz do travamento.
4. **Causa raiz NOVA e concreta identificada** (a instrução exata, não mais "algum lugar"): trace
   registrador-a-registrador mostra que `0x208d00c0` vem do `LDR LR,[PC,LR,LSL#2]` (`0xe79fe10e`)
   do `vector_stub` de IRQ do kernel real (`arch/arm/kernel/entry-armv.S`, o idioma clássico de
   despacho por tabela de branch), em `0xffff1044`, com `Rd==Rm==r14`. Dump direto da RAM confirma
   que a TABELA está perfeita: `0xffff1058` (índice 3, modo SVC interrompido) contém `0xc0008d20`
   (endereço de `.text` plausível, `__irq_svc`). O `LDR` LÊ E DEVOLVE `0x208d00c0` — que é
   EXATAMENTE `0xc0008d20` com os 4 bytes invertidos. Ou seja: uma leitura de dados de 32 bits sendo
   devolvida em BIG-ENDIAN quando deveria ser little-endian. Rastreando `cpsr().isBigEndian()`
   (`CPSR.E`, bit 9) instrução a instrução: **`CPSR.E` está `true` bem antes deste `LDR`**. Uma
   sonda mais ampla (`cpsr.E` amostrado a cada fatia desde o início do boot) mostra que o bit NÃO
   fica preso permanentemente — ele OSCILA entre `true`/`false` repetidamente, sempre em modo
   `SUPERVISOR`, concentrado num punhado de PCs perto do FIM do `.text` do kernel
   (`0xc0a6xxxx`-`0xc0a9xxxx`, plausivelmente a região do laço ocioso/`WFI`/`arch_cpu_idle`, dado
   que o kernel tem ~10,8MB de código). A última virada antes do fault acontece na MESMA fatia
   (`77273`), no MESMO PC (`0xc0a6603c`) onde `servicePendingIrq()` intercepta a CPU e desvia para o
   vetor de IRQ. `spsr(IRQ)`/`spsr(SUPERVISOR)`/`spsr(ABORT)` amostrados ao final NÃO mostram `E=1`
   armazenado (bit 9 = 0 nos três) — a hipótese simples "um SPSR poluído uma vez propaga `E=1` para
   sempre via `MOVS PC,LR`" não está confirmada. O mecanismo exato de COMO/ONDE `CPSR.E` vira `true`
   momentos antes deste `LDR` específico NÃO foi isolado nesta sessão.
5. **Próximo passo recomendado, concreto**: (a) localizar a PRIMEIRA instrução (não só a primeira
   fatia) que escreve `CPSR.E=1` — trace via `step()` cobrindo a região `0xc0a6xxxx`-`0xc0a9xxxx`
   perto do laço ocioso, correlacionando cada `MSR`/`MOVS PC,Rn`/`RFE` com o valor de E antes/depois;
   (b) cross-referenciar contra `arch/arm/kernel/entry-armv.S`/`arch/arm/kernel/process.S` do
   `raspberrypi/linux` (árvore documentada em `testdata/raspi1/README.md`) para essa faixa de
   endereço — plausivelmente `cpu_v6_do_idle`/`arch_cpu_idle`/`default_idle` ou o próprio
   `vector_stub`/`ret_from_intr`, mas NÃO confirmado ainda; (c) considerar se isto é causado por um
   bug real do `arm-jitter` na banked-register/SPSR machinery do interpretador/JIT nativo (ex.:
   leitura de SPSR de um banco errado, ou um `MSR` mal decodificado que seta bit 9 por engano) em vez
   de comportamento genuíno do kernel — um kernel LE normal não deveria precisar de `SETEND` nesta
   fase do boot.
6. `mvn -o test` verde no `virtual-arm-box` (66 testes, 4 skipped = M2×2+M3×2, motivo do `@Disabled`
   atualizado); `VersatilePbBootTest` continua verde. Harness temporário `Raspi1DiagTempTest`
   REMOVIDO por completo antes do commit (`git status` confirmado limpo além do Javadoc). Commits:
   arm-jitter (fetch/translationGeneration), virtual-arm-box (Javadoc/anotação de
   `Raspi1BootTest`). F3 permanece 🟡 na fila "ATUAL".

## Épico B6 (AArch64) — detalhe completo de B6.6.1-B6.6.5 (resumo compacto em FILA-EXECUCAO.md)
- **B6.5.4** ✅ (2026-07-27, 4ª e última sub-task de B6.5 — **fecha o épico B6.5 por completo**,
  executada logo depois de B6.5.3 fechar): `Ir64NativePolicy.supports` ganha os 4 `case`s
  (`FP64_ALU`/`FP64_MOVE_IMMEDIATE`/`FP64_COMPARE`/`FP64_CONVERT`); `Ir64BlockCompiler` ganha
  `constructFp64Alu`/`constructFp64MoveImmediate`/`constructFp64Compare`/`constructFp64Convert`,
  mesmo padrão D-ASM de reconstrução-de-record dos `construct*` de B6.4 (decisão D1 da spec:
  consistência com o resto do compilador, sem inlinar `FADD`/`DADD` da JVM). D2 confirmada:
  nenhum binding novo em `Aarch64GuestToHostMapper` foi necessário — `fp()` já está acessível
  dentro de `Ir64AsmRuntimeHelpers.executeOp` via o `Aarch64Core` inteiro. 8 testes novos
  (`Ir64NativePolicyTest` + `BlockEquivalenceHarness64Test`: ALU 4 ops single/double, NEG/ABS/MOV
  com payload de NaN, `FMOV` imediato 4 vetores canônicos, `FCMP`/`FCMPE` 4 quadrantes NZCV,
  `FCVT` 2 direções, property test 50 rodadas seed fixa) provando equivalência byte-a-byte
  interpretado×ASM (banco `V` completo via `Aarch64CpuSnapshot`). `mvn -o test` verde (core
  1322 = 1314 + 8; truffle 13); `mvn -o install` verde. G5 não se aplica. Sem meta de
  performance (D1, mesma disciplina de B6.4) — bench "busybox ≥3× interpretador" segue
  bloqueado no usuário (🧑 abaixo). Ver índice do `tasks/README.md` para o detalhe completo.
  **Próximo passo**: nenhuma sub-task nova de B6.5/B6.6 elegível sem o usuário priorizar — só
  B6.6.6 resta em B6.6 e já nasce bloqueada no usuário (🧑 abaixo); fila automática vazia de
  novo.

- **B6.5.3** ✅ (2026-07-27, 3ª das 4 sub-tasks de B6.5, executada logo depois de B6.5.2 fechar):
  dispatch novo em `Aarch64Decoder.decodeDataProcessingRegister` — `if (bit26set) return
  decodeDataProcessingScalarFpSimd(word, address);` logo no topo, antes de toda a lógica
  existente que já assumia implicitamente `bit26=0` (nenhuma mudança de assinatura/nome, mesmo
  `isDataProcessingRegisterClass` de sempre). **Rodada anterior era spec-only (sem código) e
  avisava explicitamente para não confiar no esqueleto de bits sem conferir contra o assembler
  real — isso foi feito PRIMEIRO, antes de qualquer linha de decoder**: montei ~40 vetores com
  `aarch64-none-elf-as`/`objdump` reais (devkitA64) — `FADD`/`FSUB`/`FMUL`/`FDIV` (single+double),
  `FNEG`/`FABS`/`FMOV`(reg), `FMOV`(imediato, 4 vetores canônicos), `FCMP`/`FCMPE` (com/sem
  zero), `FCVT` (as duas direções) — e também os VIZINHOS fora de escopo (`fadd v0.4s,...`
  vetorial, `fmov s0,w0`, `scvtf`/`fcvtzs`, `fsqrt`, `fmadd`, `fccmp`, `fcsel`, `frintn`) para
  confirmar empiricamente que nenhum deles colide com os 4 padrões fixos usados aqui. **Achados
  reais que DIVERGEM do esqueleto da spec anterior**: (1) o prefixo fixo real é
  `bits[28:24]="11110"` + `bit21=1` (a spec citava só "bit26=1" como gate, sem mencionar que
  Advanced SIMD vetorial TAMBÉM tem bit26=1 mas prefixo(28:24) diferente — `01110` — e que
  3-source usa prefixo `11111`, não `11110`; ambos precisam ser excluídos ANTES de tentar
  qualquer sub-padrão, senão vazariam); (2) `type` (bits[23:22]) está de fato na MESMA posição
  nos 4 subgrupos (2-source/1-source/imediato/compare) — a spec pedia para não presumir isso sem
  conferir, e a conferência confirmou que é seguro assumir; (3) `FMOV`-imediato em A64 tem o
  `imm8` CONTÍGUO em bits[20:13] (bem mais simples que o VFP32, que espalha em dois pedaços de 4
  bits) — o algoritmo `VFPExpandImm` (sinal + expoente replicado + mantissa) é idêntico ao
  precedente `StandardIrBuilder#vfpExpandImm`, só duplicado com a extração de campo diferente
  (mundos 32/64 não compartilham decoder, G2/G3); (4) `FCMP`/`FCMPE` tem `Rm` fixo em `00000` na
  forma "compara com zero" — CONFIRMADO que não é coincidência do assembler, é parte do encoding
  fixo (testado com `Rn` variando e `Rm` sempre zero); (5) `FCVT` F32↔F64: opcode=5 (bits[20:15]
  do grupo 1-source) exige `type=00` (fonte single, destino double), opcode=4 exige `type=01`
  (fonte double, destino single) — a combinação oposta em cada `case` lança `unsupported`
  (mistura errada de opcode/type não é uma instrução válida, mesmo padrão de "UNDEFINED real" já
  usado em B6.3.x). 5 métodos novos (`decodeDataProcessingScalarFpSimd` + um por subgrupo:
  `decodeFpTwoSource`/`decodeFpOneSource`/`decodeFpMoveImmediate`/`decodeFpCompare`) + helper
  `decodeFpDoublePrecision` (type→boolean, `10`/`11` lançam `UnsupportedOperationException` como
  UNDEFINED real) + `expandFpImmediate` (VFPExpandImm-equivalente). Corpus real estendido
  (offsets `0x298`-`0x314`, 32 instruções novas — `corpus.s`/`corpus.bin`/`corpus.objdump.txt`
  todos regenerados a partir do assembler real, nenhum byte editado à mão). 38 testes novos em
  `Aarch64DecoderCorpusTest` (um por vetor do corpus + `type=10`/`type=11` reservado +
  regressão negativa confirmando que Advanced SIMD vetorial/`scvtf`/`fcsel`/`fsqrt` continuam
  `unsupported` + resanity de um vetor pré-existente de `decodeDataProcessingRegister` para
  provar que o `if` novo de bit26 não mudou nada de `bit26=0`) + 1 teste ponta-a-ponta novo em
  `Ir64BlockExecutorTest` (`fmovFaddFcmpBCondMinimalFloatBlock`: `FMOV s0,#1.0` → `FADD s0,s0,s0`
  → `FCMP s0,#0.0` → `B.gt` — o "hello float" mínimo de A64 citado na task, sem o passo `VMRS`
  intermediário que A64 não precisa). `mvn -o test` verde (core 1314 = 1275 + 39 novos). G5 não
  se aplica (nenhum arquivo 32-bit tocado, confirmado por grep). **Não inclui** (herdado de
  B6.5.2, reafirmado aqui): Advanced SIMD vetorial, `type=10` (meia-precisão), `SCVTF`/`UCVTF`/
  `FCVTZS`/`FCVTZU`, `FMOV` core↔FP, `FSQRT`, `FCCMP`/`FCSEL`, nenhum feature-gating novo (D2 da
  task, mesmo raciocínio "sem consumidor real" de B6.4 D0). **Próximo passo**: B6.5.4 (emissão
  ASM nativa de FP) fica elegível — última das 4 sub-tasks de B6.5, depende também de B6.4 ✅.

- **B6.5.2** ✅ (2026-07-27, 2ª das 4 sub-tasks de B6.5, priorizada pelo usuário depois que a fila
  automática ficou vazia): 4 records novos em `Ir64Op` (`Fp64Alu`/`Fp64MoveImmediate`/
  `Fp64Compare`/`Fp64Convert`, enums `Fp64Operation`/`Fp64Conversion`) + `executor64/
  Ir64FpExecutor.java` novo (estático, sem estado — nenhuma op desta fatia toca memória, D3: sem
  load/store/multiple-transfer de FP). **Achado**: a spec previa `Kind` contíguo a partir de `20`,
  mas B6.6.1-B6.6.4 (intermediárias entre a rodada de spec e a execução) já tinham reivindicado
  `20`-`22` — usados `23`-`26`. `Fp64Compare` escreve `PSTATE.NZCV` DIRETO (sem o segundo passo
  `VMRS APSR_nzcv` que o VFP32 precisa); `NEG`/`ABS` via bit de sinal (nunca `-x`/`Math.abs`);
  `FCVT` via cast direto do Java. `signalOnQuietNaN` auditado contra `IrVfpExecutor` (confirmado:
  sem efeito observável nos dois mundos). `mvn -o test` verde (core 1274 = 1254 + 20 novos;
  truffle 13). G5 não se aplica (nenhum arquivo 32-bit tocado). Ver índice do `tasks/README.md`
  para o detalhe completo. **Não inclui** (gaps registrados, podem bloquear o aceite agregado do
  épico "musl printf de float"): `Fp64Load`/`Store`/`MultipleTransfer`, `SQRT`/`MLA`/`MLS`/`NMUL`,
  conversão inteiro↔float (`SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU`), `FMOV Rt,Sn` core↔FP. **Próximo
  passo**: B6.5.3 (decoder) fica elegível.

- **B6.6.5** ✅ (2026-07-27, 5ª das 6 sub-tasks de B6.6 — PENÚLTIMA, só falta B6.6.6 bloqueada no
  usuário): `translationGeneration` em `jit64/BlockKey64`/`JitRuntime64`, espelho direto de
  B4.1.4 (32-bit) mas menor em escopo — `jit64/` não tem inline cache nem encadeamento de blocos
  ainda (B6.4 D0), então só UM dos três pontos de uso do precedente 32-bit se aplica hoje (o
  lookup/lift de `JitRuntime64.execute`). `BlockKey64` ganhou 2º campo `int translationGeneration`
  com construtor de compatibilidade `BlockKey64(long pc)` preservando `translationGeneration=0`
  (G3 — todo chamador existente de B6.4, incl. `JitRuntime64Test`, continua compilando sem
  mudança). `JitRuntime64.execute` passou a ler `core.memory().translationGeneration()` uma vez
  por chamada e usar no `BlockKey64` do lookup/lift — troca de `TTBR0_EL1` vira MISS natural no
  `BlockCache64`, nunca servindo um bloco compilado sob mapeamento antigo. Javadoc de pendência
  (D3) registrado em ambos os arquivos: se uma PR futura adicionar IC ou encadeamento a `jit64/`,
  precisa lembrar de checar `translationGeneration` nesses pontos novos também (mesmo achado que
  B4.1.4 documentou para o precedente 32-bit). `JitRuntime64TranslationGenerationTest` novo
  (espelho de `JitRuntimeTranslationGenerationTest`): duas tabelas de página L0-L3 completas (4
  níveis reais do page-walk VMSA64, não um atalho de bloco) mapeando o MESMO VA para código físico
  diferente, troca via `TranslatingAddressSpace64.setTtbr0`+`invalidateTlbAll`, cada troca executa
  o `MOVZ` certo — nunca o bloco stale da geração anterior; + teste do construtor de
  compatibilidade. `mvn -o test` verde (core 1254 + truffle 13); `mvn -o install` verde. G5 não se
  aplica — confirmado por grep, nenhum arquivo 32-bit referencia `BlockKey64`/`JitRuntime64`/
  `AddressSpace64` (infra A64 isolada, diferente do precedente 32-bit onde B4.1.4 TINHA que
  revalidar gbaemu/ndsemu). **Épico B6.6 quase fechado**: só falta **B6.6.6** (hospedeiro `virt64`),
  que já nasce bloqueada no usuário (toolchain/kernel real) — ver seção 🧑 abaixo.

- **B6.6.4** ✅ (2026-07-27, 4ª das 6 sub-tasks de B6.6, PRIMEIRO estado de EL1 real de A64):
  `core64/Aarch64ExceptionState` novo (`sp1`/`elr1`/`spsr1`/`esr1`/`far1`/`vbar1`/`inEl1`) —
  ÚNICA fonte de verdade em `Aarch64Core#exceptionState()`; `Aarch64VmsaSystemRegisters` (B6.6.3)
  refatorado para delegar `ESR_EL1`/`FAR_EL1`/`VBAR_EL1`/`ELR_EL1`/`SPSR_EL1` para lá em vez de
  campos próprios (evita duas cópias divergindo quando o guest lê via `MRS` depois de um abort
  real — o parâmetro `core`, antes só "reservado por simetria", virou consumidor real).
  `sp()`/`setSp()` resolvem `SP_EL0`/`SP_EL1` automaticamente por `inEl1`, sem mudar nenhum
  chamador existente. `Aarch64Core.enterMemoryAbort` (espelho de `ArmCore.enterMemoryAbort`)
  preenche `ESR_EL1`/`FAR_EL1`/`ELR_EL1`/`SPSR_EL1` e salta para `VBAR_EL1 + 0x400` (única
  entrada usada da tabela de 16×0x80 bytes do `ARM DDI 0487 D1.10`), abrindo o monitor de
  exclusividade (fecha a pendência de B6.3.4). **Achado real confirmado contra `ARM DDI 0487
  D17.2.30`**: `ESR_EL1.EC` de instruction abort vindo de EL inferior é `0x20`, NÃO `0x21` como a
  task citava (`0x21` é "sem troca de EL", categoria errada para EL0→EL1) — `0x24` (data abort)
  estava correto. `ERET` decodificado (`Ir64Op.ExceptionReturn` novo, `Kind=22`, record dedicado
  — não reaproveita `SystemInstruction`: muda PC/PSTATE como um desvio tomado, diferente de
  TLBI/barreira; encoding `opc=0b0100` no mesmo formato fixo de `BR`/`BLR`/`RET`, CONFERIDO via
  `aarch64-none-elf-as`/`objdump` reais: `eret`→`0xD69F03E0`). `PstateRegister` ganhou
  `toSpsrFormat()`/`setFromSpsrFormat(long)` (NZCV em `[31:28]`, mesma posição do CPSR ARM32).
  Captura de `MemoryTranslationException64` em `Ir64BlockExecutor.step`/`executeBlock` (só
  interpretador — ASM/`jit64` fica pendência explícita). `Aarch64MemoryAbortTest` novo (3 casos):
  data abort real via `TranslatingAddressSpace64` → EL1 → `ERET` → continuação bem-sucedida;
  instruction abort com `EC` distinto; `SP_EL1` separado de `SP_EL0`. `mvn -o test` verde (1265
  testes, core+truffle, 0 falhas); gbaemu/ndsemu não revalidados (G5 não se aplica — `ArmCore`/
  32-bit não referencia `core64`, confirmado por grep). Ver índice do `tasks/README.md` para o
  detalhe completo. **Próximo passo**: B6.6.5 (`translationGeneration` em `jit64`) ficou elegível
  e já fechou — ver bullet acima.

- **B6.6.3** ✅ (2026-07-27, 3ª das 6 sub-tasks de B6.6, ponte registrador-de-sistema↔MMU):
  `memory/mmu/Aarch64VmsaSystemRegisters` (espelho de `Cp15VmsaCoprocessor`) liga `SCTLR_EL1`
  (só bit `M`)/`TTBR0_EL1`/`TCR_EL1`/`MAIR_EL1` a `TranslatingAddressSpace64` (B6.6.2);
  `ESR`/`FAR`/`VBAR`/`ELR`/`SPSR` só armazenamento (B6.6.4 os consome). **Achado real D1
  confirmado com `aarch64-none-elf-as`/`objdump` reais**: `TLBI VMALLE1`/`VMALLE1IS` são `SYS`
  (`op0=1`), NÃO `MRS`/`MSR` — `Ir64Op.SystemInstruction` novo (`Kind=21`) +
  `Ir64SystemInstructionOp{TLBI_ALL,BARRIER}`; `DSB`/`ISB`/`DMB` (`op0=0`) também decodificadas
  como NOP. `Aarch64SystemRegisterBus` ganhou `invalidateTlbAll()` default NOP (mesmo barramento
  único instalado no core, já que `TLBI` não é leitura/escrita de registrador mas ainda é uma
  ação de sistema). `TLBI` per-VA (`VAE1`)/`SYSL` continuam fora de escopo (regressão negativa
  testada). Corpus real estendido (offsets `0x284`-`0x294`). `mvn -o test` verde (core 1249 +
  truffle 13); gbaemu/ndsemu não revalidados (G5 não se aplica, nenhum arquivo 32-bit tocado). Ver
  índice do `tasks/README.md` para o detalhe completo. **Próximo passo**: B6.6.4 (modelo de
  exceção EL0→EL1 + aborts precisos) fica elegível agora.

- **B6.6.2** ✅ (2026-07-27, 2ª das 6 sub-tasks de B6.6, independente de B6.6.1): `memory/mmu/
  TranslatingAddressSpace64` — page-walk VMSA64 completo (L0-L3, bloco L1/L2, página L3), `AP`+
  `PXN`/`UXN`, micro-TLB 256 entradas, `translationGeneration`. `FaultStatus64`/
  `MemoryTranslationException64` novos. `AddressSpace64.translationGeneration()` ganhou default.
  **Achado real**: em VMSA64 o bit de somente-leitura do `AP` é UNIVERSAL (afeta EL1 e EL0 por
  igual) — diferente do ARMv6 `AP=0b10` (RW privilegiado + RO usuário); não existe modo "RW
  privilegiado + RO usuário" em AArch64. `mvn -o test` verde (13 testes novos); gbaemu/ndsemu não
  revalidados (G5 não se aplica, pacote aditivo, nenhum arquivo 32-bit tocado). Ver índice do
  `tasks/README.md` para o detalhe completo. **Próximo passo**: B6.6.3 (`Aarch64VmsaSystemRegisters`
  ligando B6.6.1↔B6.6.2) fica elegível agora que as duas fecharam.

- **B6.6.1** ✅ (2026-07-27, 1ª das 6 sub-tasks de B6.6, pré-requisito estrutural da MMU v8 —
  A64 não tem `MCR`/`MRC`): `Ir64Op.SystemRegister` (`Kind=20`) + `Aarch64SystemRegisterId` (9
  registradores EL1 — `SCTLR`/`TTBR0`/`TCR`/`MAIR`/`ESR`/`FAR`/`VBAR`/`ELR`/`SPSR`, `FPCR`/`FPSR`
  fora por decisão de fronteira de épico) + `Aarch64SystemRegisterBus` novo em `core64/`
  (sibling de `CoprocessorBus` 32-bit) + decoder `MRS`/`MSR (register)` (encoding `SYS`
  verificado contra `aarch64-none-elf-as`/`objdump` real antes de codificar) resolvendo a
  5-upla `op0:op1:CRn:CRm:op2` para o enum NO DECODER (não no executor). Registrador sem
  hospedeiro instalado lança `UnsupportedOperationException` do próprio executor (`Aarch64Core`
  ainda não tem modelo de exceção síncrona para Undefined-ao-guest como o mundo 32-bit).
  Corpus real estendido (offsets `0x26c`-`0x280`). `mvn -o test` verde (core 1221 + truffle
  13); gbaemu/ndsemu não revalidados (G5 não se aplica, nenhum arquivo 32-bit tocado). Mecanismo
  pronto para B6.6.3 instalar um bus real de MMU sem tocar decoder/IR/executor de novo. Ver
  índice do `tasks/README.md` para o detalhe completo. **Próximo passo**: B6.6.2
  (`TranslatingAddressSpace64`, independente) segue na tabela executável acima; B6.6.3
  (`Aarch64VmsaSystemRegisters` ligando os dois) só fica elegível quando ambas fecharem.

## Épico B6 (AArch64) — detalhe completo de B6.5.1, B6.4 PR1-3 e B6.3 decomposta (resumo compacto em FILA-EXECUCAO.md)

- **B6.5.1** ✅ (2026-07-26, 1ª das 4 sub-tasks de B6.5): `core64/Aarch64FpRegisters.java` novo —
  banco `long[32]` (`V0`-`V31`, só bits 63:0). **Achado que muda a forma em relação ao
  precedente VFP32**: em A64 `Sn`/`Dn` são a MESMA célula de armazenamento (não um par de
  índices independentes como `VfpRegisters`), e escrever `Sn` ZERA os 32 bits altos da célula
  ("SIMD&FP destructive write" — o oposto do comportamento VFP32). `FPCR`/`FPSR` deliberadamente
  fora de escopo (dependem do mecanismo `MRS`/`MSR` que só B6.6.1 introduz) — modo de
  arredondamento fixo em round-to-nearest, sem flush-to-zero. `Aarch64Core` ganha `fp()` (sempre
  alocado); `Aarch64CpuSnapshot` estendido com o banco `V` completo para o harness de
  equivalência de B6.5.4 pegar divergência de FP. `mvn -o test` verde (JBR 25, core 1206 +
  truffle 13). G5 não se aplica (pacote aditivo, nenhum arquivo 32-bit tocado). **Próximo
  passo**: B6.5.2 (IR+interpretador escalar FMOV/FADD/FMUL/FDIV/FCMP/FCVT) fica para sessão
  futura.

- **B6.4 PR3** ✅ (2026-07-26, terceira e última PR do backend ASM 64-bit — fecha o épico B6.4
  do lado de codegen): estende `Ir64NativePolicy`/`Ir64BlockCompiler` para o conjunto
  B6.3.1-B6.3.4 (`AluShiftedRegister`/`AluExtendedRegister`/`ConditionalSelect`/`Bitfield`/
  `MultiplyAccumulate`/`Divide`/`LoadExclusive`/`StoreExclusive`) — mesmo padrão D-ASM dos PRs
  1/2 (reconstrói o record `Ir64Op` exato e delega a `Ir64AsmRuntimeHelpers.executeOp`, que já
  despachava esses `case`s desde B6.3.1-B6.3.4; nenhum arquivo de execução mudou). Com isso
  TODO `Ir64Op.Kind` existente é suportado nativamente — nenhum bloco A64 cai mais no
  interpretado por falta de `case`. `mvn -o test` verde (core 1197 + truffle 13); `mvn -o
  install` verde. gbaemu/ndsemu não revalidados (G5 não se aplica, mesmo precedente dos PRs
  1/2). **Próximo passo** (fora do escopo de qualquer PR, ver `b6.4-aarch64-asm-backend.md`):
  registrador-cache de verdade (ganho de performance real, sem consumidor A64 medido para
  justificar ainda) e o bench "busybox ≥3× interpretador" do aceite agregado do épico, que
  segue bloqueado no mesmo ambiente da seção 🧑 abaixo (busybox aarch64 real ou toolchain
  `aarch64-linux-*`).

- **B6.4 PR2** 🟡 (2026-07-26, mesma leva de sessões do PR1: loads/stores/SVC nativos):
  estende `Ir64NativePolicy.supports` para `LOAD64`/`STORE64`/`LOAD_STORE_PAIR`/
  `LOAD_LITERAL64`/`SVC`; `Ir64BlockCompiler` ganha `constructLoad64`/`constructStore64`/
  `constructLoadStorePair`/`constructLoadLiteral64`/`constructSvc` — mesmo padrão D-ASM do PR1
  (reconstrói o record `Ir64Op` exato, campo a campo, e delega a
  `Ir64AsmRuntimeHelpers.executeOp`, o MESMO despacho do interpretador). **Achado que
  simplificou o escopo em relação ao que a spec cogitava**: como o acesso à memória e ao
  `Aarch64SvcHandler` já acontece inteiramente DENTRO de `Ir64BlockExecutor#execute` (mesmo
  caminho pros dois backends), PR2 não precisou de NENHUM binding novo em
  `Aarch64GuestToHostMapper` — não emite nenhuma instrução de acesso a memória, só reconstrói o
  record e despacha. Único detalhe de bytecode novo: `Load64`/`Store64.extendType()` é `null`
  fora de `REGISTER_OFFSET` — `emitEnumConstantOrNull` (`ACONST_NULL` vs `GETSTATIC` do enum)
  cobre isso. Testes novos: `Ir64NativePolicyTest.supportsPr2OpSet`/`rejectsOpsOutsidePr2Scope`;
  `BlockEquivalenceHarness64Test` ganha 7 testes novos cobrindo round-trip STR/LDR offset,
  LDRSB sign-extend, writeback pre/post-index, endereçamento `REGISTER_OFFSET` (única forma que
  carrega `rm`/`extendType`, exercita o `emitEnumConstantOrNull`), STP/LDP 64-bit round-trip,
  `LDR (literal)`, e `SVC` (mesmo `Aarch64SvcHandler` instalado nos dois cores do par, prova que
  o despacho reconstruído chega ao mesmo handler). `mvn -o test` verde (JBR 25, core 1188 +
  truffle 13). gbaemu/ndsemu **não revalidados** (G5 não se aplica — nenhum arquivo 32-bit
  tocado, mesmo precedente do PR1). **Próximo passo**: PR3 (B6.3.1-B6.3.4 nativos +
  registrador-cache de verdade + bench busybox-aarch64, este último bloqueado no usuário —
  mesma pendência 🧑 de B6.2/B6.3 abaixo) fica para sessão futura.

- **B6.4 PR1** 🟡 (2026-07-26, rodada de spec + PR1 do esqueleto do backend ASM 64-bit):
  spec `trilha-b-arquiteturas/b6.4-aarch64-asm-backend.md` escrita e commitada separadamente
  (decisão D0: `JitRuntime`/`BlockCache`/`BlockKey` 32-bit não são reusáveis — amarrados a
  `ArmCore`/`InstructionSet`/`itState`/MMU 32-bit — `jit64/` nasce como pacote-irmão
  deliberadamente mais simples, sem IC/chaining/tiering/invalidação; decisão D-ASM: o bytecode
  do PR1 delega ao MESMO despacho do `Ir64BlockExecutor` compartilhado, sem cache de
  registrador ainda — pipeline+equivalência, não performance). PR1 implementado na mesma
  sessão: `ir64/Ir64Block`+lifter (faltava desde B6.1), `jit64/`
  (`CompiledBlock64`/`BlockKey64`/`BlockCache64`/`JitRuntime64`), `codegen64/`
  (`Ir64CodeEmitter`/`InterpretedIr64CodeEmitter`/`Asm64CodeEmitter`,
  `Ir64NativePolicy`/`Aarch64GuestToHostMapper`/`Ir64AsmRuntimeHelpers`/`Ir64BlockCompiler` em
  `jvm64/`), `codegen/equivalence/BlockEquivalenceHarness64`+`Aarch64CpuSnapshot`. Cobre só o
  conjunto de ops da B6.1 (`Alu64`/`MoveWide`/`PcRelative`/`Branch64`/`CompareBranch64`/
  `Cycle`/`Fetch`). `mvn -o test` verde (1182 core + 13 truffle, 25 testes novos); gbaemu/ndsemu
  **não revalidados** (G5 não se aplica, nenhum arquivo 32-bit tocado — mesmo precedente de
  B6.1-B6.3.4). **Próximo passo** (na época): PR2 (loads/stores/SVC nativos) e PR3
  (B6.3.1-B6.3.4 nativos + registrador-cache de verdade + bench busybox-aarch64) ficariam para
  sessões futuras — **PR2 já fechou**, ver o bullet acima; PR3 segue pendente, ver o arquivo da
  spec para o detalhe de escopo.

**B6.3 decomposta em 4 sub-tasks (2026-07-24, rodada de spec) — TODAS ✅
FECHADAS (2026-07-26)**: **B6.3.1** ✅ fechou (2026-07-24/25) — criou o
dispatch de "Data Processing — Register"
(`isDataProcessingRegisterClass`/`decodeDataProcessingRegister`). Isso destravou
**B6.3.2** e **B6.3.3** simultaneamente. **B6.3.2** ✅ já fechou (2026-07-25,
`CSEL`/`CSINC`/`CSINV`/`CSNEG` + `SBFM`/`UBFM`/`BFM`, ver o histórico acima).
**B6.3.3** ✅ também já fechou (2026-07-25, `MADD`/`MSUB`/`MUL`/`MNEG`/`SDIV`/
`UDIV`, ver o histórico acima) — independente de B6.3.2, só compartilhava a
dependência em B6.3.1. **B6.3.4** ✅ fechou por último (2026-07-26,
`LDXR`/`LDAXR`/`STXR`/`STLXR` + `Aarch64ExclusiveMonitor` novo em `core64/`,
ver o índice principal em `tasks/README.md` para o detalhe completo) — já
estava elegível antes mesmo de B6.3.1 fechar (dependia só de B6.2), era a
única das 4 que introduzia estado novo no core (monitor de exclusividade) e
por isso ficou sozinha na sua categoria de risco. Com isso, **a categoria
"Base ISA inteira restante" (B6.3) está 100% fechada**. Ver `b6-aarch64.md` e
os 4 arquivos de sub-task para o detalhe de cada uma. Próximo passo natural
do épico B6 maior seria **B6.4** (backend ASM 64-bit) ou **B6.5**/**B6.6**,
mas nenhuma spec nova foi escrita nem executada — fica para o usuário
priorizar. O aceite agregado do épico ("`busybox sh -c` completo no
armbox64") segue bloqueado no usuário (ver 🧑 abaixo, mesmo bloqueio de B6.2
aceite #2) mesmo com as 4 sub-tasks fechadas.



## F3 -- sessao de fechamento do CPSR.E (2026-08-17), movida para ca 2026-08-17 para manter FILA-EXECUCAO.md enxuto

**F3 — sessão de fechamento do CPSR.E (2026-08-17) — causa raiz ISOLADA E CORRIGIDA (bug real do
`arm-jitter`), abort storm 100% RESOLVIDO, M2 ainda NÃO fecha (bloqueio novo, bem mais tardio no
boot, já com causa raiz identificada)**:

1. Seguiu o próximo passo recomendado da sessão anterior: harness temporário
   (`Raspi1DiagTempTest`, removido antes do commit) com trace por-bloco (`ArmTraceListener
   .afterBlock`) primeiro para localizar aproximadamente onde `CPSR.E` oscila, depois trace
   instrução-a-instrução completo via `ArmCore#step()` DESDE O BOOT (só ~320 mil instruções até o
   primeiro flip — muito mais cedo do que a suspeita "perto do fault" da sessão anterior sugeria).
2. **Achado**: `CPSR.E` vira `true` numa instrução `SETEND BE` real (`0xf1010200`) em `0xc0a65c84`,
   e volta a `false` ~60 instruções depois numa `SETEND LE` (`0xf1010000`) em `0xc0a6616c`/
   `0xc0a66228` — **o próprio kernel Linux real executa este par deliberadamente**, numa rotina
   perto do laço ocioso (identificada por dump direto de RAM/objdump manual dos opcodes, sem
   precisar de símbolos do kernel). Não é bug de decodificação do `SETEND` (que está correto nos
   dois casos) — é a IRQ do timer chegando NO MEIO dessa janela.
3. **Causa raiz real, arquitetural**: o ARM ARM (DDI 0406C B1.8.3) exige que hardware real
   reprograme `CPSR.E` para `SCTLR.EE` em TODA entrada de exceção, independente do `SETEND` do
   código interrompido — garante que todo handler rode numa endianness conhecida mesmo
   interrompendo um trecho legitimamente em `SETEND BE`. `AProfileExceptionModel#enterException`
   nunca fazia isso (herdava `CPSR.E` do contexto interrompido) — quando a IRQ do timer chegava
   dentro da janela `SETEND BE`, o `vector_stub` herdava `E=1` e o próprio `LDR LR,[PC,LR,LSL#2]`
   (busca do alvo de salto na tabela de branch, um acesso de DADOS comum) lia os 4 bytes
   invertidos — o `0xc0008d20`→`0x208d00c0` já identificado na sessão anterior.
4. **Corrigido no `arm-jitter`**: `ExceptionEndiannessPolicy` novo (`core/`, mesmo padrão aditivo
   de `ModeChangeListener`/`MemoryAbortListener` — vazio por padrão, G3), chamado por
   `AProfileExceptionModel#enterException` logo depois do `CPSR` antigo já estar salvo em `SPSR`
   (preserva o `E` real do contexto interrompido) mas antes do PC saltar para o vetor.
   `Cp15VmsaCoprocessor` implementa a interface, forçando `CPSR.E = SCTLR.EE` (bit 25 — o `sctlr`
   já round-tripa o word inteiro desde a B4.1.5, então o bit sobrevive sem mudança adicional).
   `Bcm2835Machine#create`/`VersatilePbMachine#create` registram `core.setExceptionEndiannessPolicy
   (cp15)` (quarto gancho independente do mesmo CP15). 3 testes novos
   (`ExceptionEndiannessPolicyTest` no `core/`, `Cp15VmsaCoprocessorTest
   .applyOnExceptionEntryForcesCpsrEFromSctlrEeBit`). `mvn -o test` verde no arm-jitter (1370
   core + 13 truffle) + `mvn -o install`; G5 revalidado: gbaemu verde, ndsemu verde, armbox 40/41
   (mesma falha pré-existente de `Armv7TortureTest`/`VfpRegisters`, não é regressão),
   `virtual-arm-box` verde (incl. `VersatilePbBootTest`).
5. **Efeito no boot, confirmado ao vivo (re-executando o teste M2 JIT antes `@Disabled`)**: o abort
   storm em `0x208d00c0` desaparece por completo — 27,5 milhões de reaborts idênticos da sessão
   anterior viram ZERO. O boot avança MUITO além do ponto anterior: mailbox/`raspberrypi-firmware`,
   `mmc0`, enumeração USB inicializam, até tentar montar a raiz de verdade. **M2 ainda NÃO fecha**:
   um bloqueio NOVO e bem mais tardio aparece — `Kernel panic - not syncing: VFS: Unable to mount
   root fs on "/dev/ram" or unknown-block(1,0)`, em `prepare_namespace()`. Como esse panic acontece
   DENTRO de `kernel_init_freeable()` (chamada ANTES de `free_initmem()` na sequência real do
   `kernel_init()` do Linux), a mensagem `Freeing unused kernel memory` nunca é alcançada —
   consistente com o comportamento real do kernel, não um sintoma de regressão do fix.
6. **Causa raiz do bloqueio novo já identificada (não corrigida nesta sessão, fora do orçamento)**:
   o `FdtPatcher` (`virtual-arm-box`) escreve `/chosen/bootargs`/`/memory@0/reg` mas NUNCA
   `/chosen/linux,initrd-start`/`linux,initrd-end` — as duas propriedades que um kernel com Device
   Tree (ao contrário do protocolo ATAGs do `versatilepb`, que usa `ATAG_INITRD2`) precisa para
   descobrir onde o `initramfs.cpio.gz` carregado na RAM está. Sem elas, o kernel ignora o blob
   inteiro e, como a cmdline pede `root=/dev/ram`, tenta montar `/dev/ram` como um dispositivo de
   bloco formatado (que não é) — daí o panic. **Próximo passo recomendado, concreto**: estender
   `FdtPatcher` (hoje só sobrescreve o VALOR de propriedades já existentes, documentado no próprio
   Javadoc da classe) para CRIAR propriedades novas dentro de um nó já existente (`/chosen` já
   existe no `.dtb` real, só faltam as 2 propriedades), escrever `linux,initrd-start`/
   `linux,initrd-end` com o endereço físico onde `initramfs.cpio.gz` foi carregado
   (`INITRD_LOAD_ADDR`/`INITRD_LOAD_ADDR + initramfs.length` de `Bcm2835Machine`), e então tentar
   de novo os testes `@Disabled` do M2 (o INTERPRETED nem chegou a ser re-executado nesta sessão
   por orçamento, mas deve se beneficiar do mesmo fix de `CPSR.E` — a causa raiz é comum aos dois
   motores, `ArmCore`/`AProfileExceptionModel`).
7. `mvn -o test` verde no `virtual-arm-box` (66 testes, mesmos 4 skipped = M2×2+M3×2, Javadoc/
   `@Disabled` atualizados com o achado desta sessão); harness temporário `Raspi1DiagTempTest`
   removido por completo antes do commit. Commits: arm-jitter (`ExceptionEndiannessPolicy` +
   registro nos 2 hosts + testes), virtual-arm-box (Javadoc/`@Disabled` de `Raspi1BootTest`). F3
   permanece 🟡 na fila "ATUAL" — muito mais perto de M2 do que em qualquer sessão anterior.

## F3 (`virtual-arm-box --machine=raspi1`) — histórico condensado movido de FILA-EXECUCAO.md (2026-08-17)

M1 (banner de boot) FECHADO. Sessões 1/3 até a sessão de fechamento do CPSR.E
(10 sessões, 2026-08-15/16/17) corrigiram, em sequência: TLB de instrução/dados separada do
ARMv6K + sincronização de privilégio user/priv (CP15), 2 lacunas de decode/gating de CP15
(CR_XP no SCTLR, CPACR), IRQ do timer roteada para o comparador certo, um bug real de
alinhamento em UNALIGNED_ACCESS (leitura/escrita alinhada sendo decomposta byte a byte sem
necessidade — corrompia MMIO), fetch16/32/translationGeneration não encaminhados pelos
decoradores de invalidação, e o achado raiz do "abort storm": CPSR.E não era reprogramado para
SCTLR.EE na entrada de exceção (ARM ARM B1.8.3), causando leitura big-endian de uma tabela de
branch quando uma IRQ interrompia um SETEND BE legítimo do kernel. Detalhe completo dessas 10
sessões: mais acima neste mesmo arquivo.

**F3 — sessão de extensão do FdtPatcher (2026-08-17) — linux,initrd-start/linux,initrd-end
IMPLEMENTADOS, M2 ainda NAO fecha (bloqueio novo, bem mais tardio no boot, causa raiz NARROWED a
um opcode especifico)**:

1. Seguiu o proximo passo recomendado pela sessao anterior: FdtPatcher (virtual-arm-box) ganhou
   withInitrdRange/withNewProperty — diferente de withBootargs/withMemorySize (que so sobrescrevem
   propriedades JA existentes), o metodo novo CRIA /chosen/linux,initrd-start/linux,initrd-end
   (ausentes no .dtb cru), reaproveitando o nome no bloco de strings se ja ocorrer la ou anexando
   um novo. Bcm2835Machine#create aplica o patch com INITRD_LOAD_ADDR/INITRD_LOAD_ADDR +
   initramfs.length. 2 testes novos em FdtPatcherTest.
2. Efeito confirmado no boot JIT: o Kernel panic - VFS: Unable to mount root fs da sessao anterior
   desaparece por completo — o kernel monta o initramfs e chega a executar /init de verdade
   (Run /init as init process no console, ~8min reais) — o mais longe que este repositorio ja
   chegou no boot do raspi1.
3. M2 ainda NAO fecha — bloqueio NOVO, mais tardio que qualquer sessao anterior: logo depois de
   Run /init as init process, Internal error: Oops - undefined instruction em
   v6_clear_user_highpage_aliasing+0x58/0x104 (chamado por handle_mm_fault tratando uma falta de
   pagina do execve() do /init) mata o processo init (Attempted to kill init!). Opcode decodificado
   a partir do dump Code: do Oops (ec432f06): MCRR p15,0,r2,r3,c6 (encoding A1 padrao de
   MCRR/MRRC, cond=AL, L=0->MCRR, Rt2=r3, Rt=r2, coproc=p15, opc1=0, CRm=c6) — uma transferencia
   DUPLA de registrador para coprocessador, diferente do MCR/MRC de registrador unico que
   Cp15VmsaCoprocessor/Bcm2835Cp15Extras ja tratam. Provavel lacuna de DECODE no arm-jitter A32
   (nao so de despacho do CoprocessorBus) — nem gbaemu (ARMv4T), nem ndsemu (ARMv5TE), nem armbox
   (user-mode) jamais exercitaram MCRR/MRRC. Nao investigado alem da decodificacao do opcode (fora
   do orcamento desta sessao).
4. Achado colateral, nao fatal: duas ocorrencias de Division by zero in kernel em
   pl011_set_termios (div64_u64) ao abrir /dev/console, bem antes do Oops de init — o kernel trata
   como excecao nao fatal e o boot segue normalmente logo depois. Possivel causa: um campo de
   clock/baud que Pl011Uart/mailbox devolve como 0 onde o driver espera um divisor nao-zero — so
   observado, nao investigado.
5. Proximo passo recomendado: (a) confirmar no decoder A32 do arm-jitter se MCRR/MRRC tem case
   proprio ou caem em UNDEFINED por ausencia de decode (mais provavel); se for lacuna de decode, e
   uma task nova do arm-jitter (mesmo precedente de escopo do BE8/B1.8); (b) so depois de MCRR/MRRC
   decodificarem, repetir o boot e ver se execve("/init") conclui; (c) investigar a divisao por
   zero do PL011 se voltar a aparecer de forma fatal. Backend INTERPRETED nao foi re-executado
   nesta sessao (orcamento).
6. mvn -o test verde no virtual-arm-box (68 testes, 4 skipped = M2x2+M3x2, motivo do @Disabled
   atualizado); VersatilePbBootTest continua verde. Nenhum arquivo do arm-jitter tocado nesta
   sessao.

**F3 — sessao de decode MCRR/MRRC (2026-08-17) — bug real CORRIGIDO (2 commits, arm-jitter +
virtual-arm-box), M2 re-teste INCONCLUSIVO (sessao interrompida por rate-limit + custo)**:

1. Confirmado o palpite da sessao anterior: CoprocessorDecoder (arm-jitter) nao tinha case para
   MCRR/MRRC (bits[27:21]=1100010, espaco distinto de MCR/MRC) — caia direto em UNDEFINED por
   ausencia de decode, nao por rejeicao deliberada. Decodificado com IrOp/
   InstructionKind.COPROCESSOR_DOUBLE novos, seguindo o padrao de MCR/MRC ja existente (Rt/Rt2
   empacotados, L seleciona MCRR(0)/MRRC(1)). Registrador c6 (o que
   discard_old_kernel_data/copypage-v6.c usa via MCRR p15,0,Rt,Rt2,c6 para invalidar D-cache por
   faixa [Rt,Rt2] antes do execve() popular uma pagina) tratado em Cp15VmsaCoprocessor como
   RAZ/WI, mesmo precedente de c7.
2. Segundo bug real achado ao integrar (nao estava na spec, mas sem ele o fix #1 nao teria efeito
   nenhum no boot real): a cadeia de decorators CoprocessorBus do virtual-arm-box
   (Bcm2835Cp14Extras -> Bcm2835Cp15Extras -> Cp15VmsaCoprocessor, mesmo padrao em
   VersatileCp15Extras do versatilepb) nao repassava handlesDouble/readDouble/writeDouble — o
   default de CoprocessorBus#handlesDouble e false e NAO delega automaticamente (decisao
   deliberada da propria interface, evita que um bus que so implementa MCR/MRC reivindique
   MCRR/MRRC por acidente) — entao uma chamada de MCRR real nunca alcancava o fim da cadeia onde
   o fix #1 vive, mesmo com os testes de unidade de Cp15VmsaCoprocessor isolado passando.
   Corrigido nos 3 decorators (2 do BCM2835 + o do versatilepb, por simetria, mesmo sem uso real
   hoje).
3. mvn -o test verde no arm-jitter (1378 testes) + mvn -o install; G5 completo revalidado (sessao
   atual, nao a anterior): gbaemu verde, ndsemu verde, armbox 40/41 (mesma falha pre-existente
   Armv7TortureTest/VfpRegisters documentada em toda sessao anterior da F3, nao e regressao),
   virtual-arm-box verde (68 testes, mesmos 4 skipped).
4. M2 JIT re-testado, resultado INCONCLUSIVO: reachesFreeingKernelMemoryAcceiteM2Jit foi
   reativado e rodado de forma bloqueante — passou de ~40 minutos sem concluir (heap da JVM ainda
   crescendo lentamente, nao travado num laco obvio) e foi abortado manualmente. Isso NAO e uma
   falha confirmada nem um sucesso confirmado — e desconhecido se o fix fecha M2, se ha um
   bloqueio novo mais tardio, ou se o boot real deste ponto em diante e so mais lento que 40min
   (o historico da F3 ja viu boots de dezenas de minutos antes). Teste voltou a @Disabled com o
   achado documentado no Javadoc da classe.
5. Causa da interrupcao, registrada para a disciplina de custo: a sessao original que fez #1/#2
   rodou em um agente de background que bateu o limite de sessao da conta (rate limit) no meio da
   tentativa de reexecutar o teste M2 — 3 rodadas de resume foram gastas so tentando esperar um
   processo de shell em background "acordar" o agente (nao funciona; so filhos da ferramenta Agent
   notificam). Depois disso a continuacao foi feita diretamente nesta sessao (sem subagente), que
   por sua vez tambem nao conseguiu um resultado definitivo de M2 dentro de um orcamento razoavel
   — dai a nova secao "Disciplina de custo" no topo de FILA-EXECUCAO.md.
6. Proximo passo recomendado, concreto: NAO repetir o @Test cru de novo as cegas. Escrever um
   harness temporario (mesmo padrao Raspi1DiagTempTest de sessoes anteriores, removido antes do
   commit) que imprime progresso periodico (PC, contagem de fatias, trecho do console) a cada N
   fatias, para ter visibilidade de "ainda progredindo" vs. "travado" sem esperar o teste inteiro
   terminar as cegas — so entao decidir se vale a pena deixar rodar ate o fim ou se ha um bloqueio
   novo para investigar. Este e o real motivo de custo desta sessao: um teste opaco de longa
   duracao sem visibilidade intermediaria e caro de esperar E caro de interromper sem saber o que
   se perdeu.
7. Commits: arm-jitter (bc4baf8, decode MCRR/MRRC), virtual-arm-box (fca8a38, decorators +
   @Disabled do M2 atualizado).

**F3 — sessao de correcao do marcador de M2 (2026-08-17) — M2 FECHADO nos dois backends (achado:
nao era bug, era texto de marcador desatualizado)**:

1. Seguindo o proximo passo recomendado pela sessao anterior, um harness temporario
   (Raspi1DiagTempTest, removido antes do commit) rodou o boot JIT com progresso periodico
   impresso (PC/fatia/cauda do console a cada 200 mil fatias) em vez do @Test cru sem visibilidade.
   Resultado: o boot chega a Run /init as init process em ~40s reais (2,4 milhoes de fatias) e
   NUNCA trava/aborta — o fix de MCRR/MRRC da sessao anterior funcionou de verdade.
2. Causa raiz do "M2 inconclusivo" identificada: o marcador literal do enunciado ("Freeing unused
   kernel memory") nunca aparece neste kernel.img real (6.18.33) — nao por bug, por REDACAO.
   mark_readonly() (que so roda DEPOIS de free_initmem() em kernel_init()) ja tinha impresso sua
   mensagem quando "Run /init" apareceu, ou seja free_initmem() ja tinha rodado com outro texto:
   "Freeing unused kernel image (initmem) memory: 500K" (kernels modernos unificaram a mensagem
   de memoria do initmem com a de imagem do kernel). Mesmo precedente exato da redefinicao de M1.
   Corrigido o marcador para o prefixo estavel "Freeing unused kernel" em Raspi1BootTest.
3. Reativados os dois testes de M2 (removido @Disabled): JIT passa em 38,7s, INTERPRETED passa em
   50,2s — muito mais rapido que o temido pelas sessoes anteriores, porque o marcador correto
   ocorre bem mais cedo no boot que o ponto (retry indefinido de mmc0/sdhost-bcm2835, "sem suporte
   de tensao do cartao") onde o harness de diagnostico continuou observando sem crash.
4. Achado colateral para M3: depois do prompt do shell (fora do escopo desta sessao), o console
   pode ficar dominado pelo retry infinito de mmc0/SD — esperado, ja que SD/MMC real e
   deliberadamente fora do "Inclui" da spec, nao e um bug.
5. mvn -o test verde no virtual-arm-box (70 testes, 2 skipped = so M3x2 agora). Nenhum arquivo do
   arm-jitter tocado nesta sessao (G5 completo nao necessario).

**F3 — sessao do CPRMAN (2026-08-17) — bug real corrigido (ETIMEDOUT/deferred-probe do ttyAMA0),
M3 ainda NAO fecha (bloqueio novo e diferente, fora do escopo desta task)**:

1. Reconhecimento por trace de boot (antes de escrever codigo, achado real em vez de suposicao):
   sem NENHUM periferico de clock, bcm2835-clk 20101000.cprman: plld: couldn't lock PLL -> error
   -ETIMEDOUT: failed to register clk 'plld' -> o driver cai em deferred probe — o ttyAMA0 real so
   termina de registrar ("is a PL011 rev2") bem depois, numa workqueue assincrona, tarde demais
   para o PID 1 (/init) que ja abriu /dev/console preso no earlycon antigo. A hipotese herdada
   ("GPIO/pinctrl tambem bloqueiam o probe") era PARCIALMENTE errada — o driver PL011 ja
   registrava mesmo sem GPIO nenhum; nenhum stub de GPIO foi implementado (checado antes de
   assumir, como a task pedia).
2. dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cprman novo, deliberadamente minimo
   (transcrito de hw/misc/bcm2835_cprman.c/bcm2835_cprman_internals.h do QEMU so para os
   offsets/constantes): CM_LOCK (0x114) sempre reporta "todos os PLLs travados" (nenhuma
   matematica de PLL real), resto do espaco e armazenamento simples round-trip, CM_UARTCTL/
   CM_UARTDIV pre-semeados com os valores de reset do QEMU (achado colateral: evita o "Division by
   zero" ja documentado em pl011_set_termios). Confirmado ao vivo: ETIMEDOUT/couldn't lock PLL
   desaparecem do log. 5 testes de unidade novos (Bcm2835CprmanTest).
3. M3 continua NAO fechando — bloqueio novo, descoberto DEPOIS do fix acima: poucos segundos de
   tempo simulado apos Run /init as init process, o console e dominado por um retry aparentemente
   infinito de sdhost-bcm2835/mmc0 ("Card stuck being busy"/"no support for card's volts"/"error
   -22") — comportamento ESPERADO em hardware real (mmc_rescan procura hot-plug pra sempre sem
   cartao; SD/MMC real esta deliberadamente fora do "Inclui" desta task), mas que aqui nunca cede
   espaco porque Bcm2835SystemTimer comprime tempo-de-CPU-emulado em microssegundos numa proporcao
   fixa desacoplada do relogio real — o tempo simulado do kernel corre muito a frente do tempo
   real. Harness de diagnostico temporario (removido antes do commit) confirmou: console cresce
   linear e estavel (~27 mil caracteres/milhao de fatias, sem desacelerar), mas nem o banner do
   proprio /init (echo simples, zero dependencia de hardware) nem o prompt "/ #" apareceram em 20
   milhoes de fatias (~8 minutos reais de JIT) — extrapolacao sugere 60-90 minutos reais para os
   200 milhoes de fatias do orcamento atual de Raspi1BootTest, muito alem do que esta sessao
   validou.
4. Proximo passo recomendado: desabilitar o no mmc@7e202000 (sdhost) no .dtb via
   status = "disabled" (a propriedade ja existe em outros nos do .dtb real) — mas isso exige
   estender FdtPatcher para SUBSTITUIR uma propriedade existente por um valor de TAMANHO DIFERENTE
   ("okay\0"=5 bytes, "disabled\0"=9 bytes; os metodos atuais so sobrescrevem em tamanho fixo ou
   criam propriedades novas). Nao implementado nesta sessao — decisao explicita de nao improvisar
   algo fora do "Inclui" da task sem checar com o usuario primeiro. Alternativa mais arriscada
   (nao recomendada sem medir): revisitar HOST_CYCLES_PER_MICROSECOND.
5. mvn -o test verde no virtual-arm-box (76 testes, 2 skipped = so M3x2). Nenhum arquivo do
   arm-jitter tocado (G5 completo nao necessario).

**F3 — sessao FdtPatcher#withNodeDisabled (2026-08-17) — DOIS bloqueios reais fechados
(mmc0/sdhost E usb/dwc_otg), M3 ainda NAO fecha (TERCEIRO bloqueio novo revelado logo depois,
causa raiz NAO isolada)**:

1. Seguiu o proximo passo recomendado pela sessao anterior: FdtPatcher.withNodeDisabled(dtb,
   nodeName) novo, em virtual-arm-box. Achado: ao contrario do que a sessao anterior presumiu, NAO
   foi necessaria nenhuma extensao estrutural nova — o withProperty privado (usado por
   withBootargs) ja calcula delta = newPaddedLen - oldPaddedLen genericamente e desliza o resto do
   blob de estrutura, entao ja suportava trocar "okay\0" (5 bytes) por "disabled\0" (9 bytes) sem
   modificacao nenhuma — so faltava expor um metodo publico para o caso de uso. O metodo tenta
   primeiro SOBRESCRITA (withProperty, caso comum — todo no de dispositivo real ja tem
   status = "okay") e cai para CRIACAO (withNewProperty, reaproveitado do padrao de
   withInitrdRange) se a propriedade status nao existir (ausencia == "okay" por definicao do
   Device Tree). withProperty teve visibilidade rebaixada de private para pacote-privado (static)
   para o teste de round-trip poder reaproveita-lo sem duplicar logica. 3 testes novos em
   FdtPatcherTest (sobrescrita crescendo o blob, round-trip de volta a okay, criacao num no sem
   status).
2. Bcm2835Machine#create passou a desabilitar mmc@7e202000 (sdhost) — efeito confirmado via
   harness de diagnostico temporario (Raspi1DiagTempTest, removido antes do commit, mesmo
   precedente de sessoes anteriores): o retry de mmc0 REALMENTE desaparece (mmc0count=0 do inicio
   ao fim de uma corrida de 72,6 milhoes de fatias, ~30min reais) — mas o console fica preso, agora
   SILENCIOSAMENTE (sem spam), num tamanho ESTAVEL (consoleLen=32869) a partir de ~12,6 milhoes de
   fatias, com a ultima linha sendo "state() pending due to 20980000.usb".
3. Segundo bloqueio real isolado e corrigido, mesma sessao: 20980000.usb e o no usb@7e980000
   (compatible = "brcm,bcm2708-usb", o dwc_otg) — deliberadamente fora do "Inclui" da spec (servido
   por OpenBus), mas ao contrario de mmc0 (retry ruidoso para sempre), o driver USB real fica preso
   numa espera SINCRONA e SILENCIOSA (state() pending, plausivelmente
   device_pm_wait_for_dev/dpm_prepare esperando um probe() que nunca conclui sob OpenBus) — sem
   printk periodico, entao o sintoma e ausencia TOTAL de crescimento do console, nao inundacao.
   Como este no NAO tem propriedade status no .dtb cru, withNodeDisabled exercita o caminho de
   CRIACAO. Bcm2835Machine#create passou a desabilitar usb@7e980000 tambem.
4. Efeito confirmado ao vivo, os dois nos desabilitados juntos: mmc0count continua 0 E o console
   avanca MUITO alem do ponto anterior — "Run /init as init process" reaparece (consoleLen sobe de
   32869 para 34036) — mas M3 ainda NAO fecha: um TERCEIRO bloqueio, novo e diferente dos dois
   anteriores, aparece imediatamente depois — o console fica ESTAVEL em consoleLen=34036 por pelo
   menos 24 milhoes de fatias adicionais (~10 minutos reais observados nesta sessao, harness
   interrompido por orcamento, nao por timeout do teste) sem NENHUMA linha nova — nem o banner do
   proprio /init deste repositorio (um echo simples, sem dependencia de hardware nenhuma, que a
   sessao do CPRMAN ja registrou como o primeiro sinal esperado depois de /init rodar), nem o
   prompt do shell. Categoricamente diferente dos dois anteriores: nao ha NENHUMA pista textual do
   que esta travando (nem retry ruidoso, nem mensagem de espera por nome de dispositivo).
5. Causa raiz do terceiro bloqueio NAO isolada nesta sessao (orcamento de investigacao aberta
   esgotado depois dos dois fixes de DTB). Proximo passo recomendado, concreto: trace
   instrucao-a-instrucao (ArmCore#step(), backend INTERPRETED, mesma tecnica das sessoes de
   CPSR.E/tempestade de IRQ) a partir do ponto exato onde "Run /init as init process" e impresso,
   para descobrir se a CPU esta presa em WFI sem IRQ chegando (suspeita no1, dado o precedente da
   tempestade de IRQ ja corrigida — mas agora a suspeita seria falta de entrega, nao excesso), num
   laco de espera de alguma outra chamada de sistema que execve("/init")/do_execve faz cedo
   (leitura de mais paginas do initramfs, alocacao de pilha do processo), ou algo no proprio script
   de init/inittab do busybox reagindo a ausencia dos dois dispositivos desabilitados (nao lido
   ainda). Reproduzir com o .dtb PATCHEADO no QEMU 8.0.0 (oraculo ja instalado) tambem ajudaria a
   isolar se e especifico deste emulador.
6. Nota operacional sobre custo desta sessao: a primeira tentativa de rodar o harness de
   diagnostico via Bash run_in_background + | tail -300 produziu um arquivo de saida VAZIO por
   quase 20 minutos reais — o tail sem -f so imprime depois que recebe EOF do pipe, entao nenhuma
   linha aparecia ate o processo inteiro terminar (o oposto do que se pretendia com um harness de
   progresso periodico). A ferramenta Monitor (que faz tail -f de verdade no arquivo de saida do
   processo em background) contornou isso e entregou os eventos em tempo real. Licao para sessoes
   futuras: nunca canalizar (|) a saida de um comando de longa duracao atraves de tail sem -f ao
   rodar em background — usar Monitor ou redirecionamento direto (>) com leitura incremental.
7. mvn -o test verde no virtual-arm-box (78 testes, 2 skipped = so M3x2; FdtPatcherTest foi de 10
   para 13 testes). Nenhum arquivo do arm-jitter tocado (G5 completo nao necessario, so o G5 "leve"
   deste repo). M3 volta a @Disabled com o achado atualizado no Javadoc de
   Raspi1BootTest/Bcm2835Machine.

## Task G5 (trilha-g-3ds, n3dsemu) — PICA200: command list + shader + TEV — detalhe completo

- **PR1** (`bae9813`): `gpu/CommandListParser` + `gpu/PicaRegisters` (banco bruto de registradores
  internos `0x0000`-`0x0FFF`, escrita mascarada por byte, contadores de `DrawArrays`/
  `DrawElements`/`Finalize`).
- **PR2** (2026-08-19): `gpu/shader/ShaderBinary` (parser DVLB/DVLP/DVLE) + `VertexShaderInterpreter`
  (ISA formatos 1/1u, ~17 opcodes) + `gpu/VertexAttributeLoader` (algoritmo de `Pica::VertexLoader`
  do Citra real) + `gpu/VertexPipeline` + `PicaRenderer#drawTriangles`/`ShadedVertex`, implementados
  em `RecordingRenderer` e `VulkanRenderer` (*render pass*/pipeline de geometria novo, desenha na
  `ScreenTexture` já existente da G4). Tudo cross-validado campo a campo contra um `.shbin` REAL
  compilado pelo `picasso.exe` a partir do `.v.pica` de origem do `simple_tri`
  (`testdata/shaders/simple_tri.shbin`) — achou a base de registrador de constantes da ISA (32) ser
  diferente da base das tabelas de metadados do DVLE (16). Escopo cortado conscientemente: CMP/MAD/
  controle de fluxo NÃO implementados (lançam `UnsupportedOperationException`), nenhum exemplo do M5
  usa. `mvn -o test` verde (163, +17); `VulkanRendererSmokeTest` estendido roda o pipeline de
  geometria contra o driver Vulkan REAL desta máquina com validation layers.
- **PR3** (2026-08-19, mesma onda): texturas + TEV + **integração real ao boot** (o item que faltava
  para `simple_tri` desenhar de verdade, não só ter as classes prontas sem uso).
  - `gpu/tev/TevConfig` (decodifica os 6 estágios TEV dos registradores `GPUREG_TEXENV0`-`5`,
    offsets confirmados via `WebFetch` da tabela real do 3dbrew — `GPU/Internal_Registers`) +
    `gpu/tev/TevGlslGenerator` (função pura `TevConfig → GLSL`, testada por comparação de string
    contra 2-3 configs conhecidas, incl. a do `simple_tri` = passagem direta de `PRIMARY_COLOR`).
    **Não integrado ao pipeline Vulkan nesta PR** (decisão consciente, ver Armadilhas abaixo) — a
    infraestrutura existe e está testada, mas `VulkanRenderer` continua usando o
    `shaders/triangle.frag` estático do PR2 (comportamento idêntico ao caso `simple_tri`, já que o
    TEV dele reduz a passagem direta da cor interpolada — zero mudança observável).
  - `gpu/Texture`: decodifica os formatos não-comprimidos (`RGBA8`/`RGB8`/`RGBA5551`/`RGB565`/
    `RGBA4`/`IA8`/`RG8`/`I8`/`A8`/`IA4`/`I4`/`A4`) com deswizzle Morton 8×8 (a "armadilha grande" da
    task) — rotina `MortonInterleave`/`GetMortonOffset` amplamente documentada para PICA200,
    transcrita e testada com bytes conhecidos por posição. **Não usado pelo `Vulkan` ainda**
    (`simple_tri` não usa textura nenhuma — a própria task permite adiar a integração de sampler
    Vulkan para PR4: "se o PR3 ficar grande, texturas podem virar PR4").
  - **Integração real ao boot** (o núcleo desta PR): até aqui `GSPGPU_TriggerCmdReqQueue` só
    CONTAVA o disparo (RFC G3: "descarta tudo") — nenhuma lista de comandos PICA200 chegava a ser
    interpretada de verdade, então `simple_tri` nunca desenhava nada mesmo com PR1/PR2 prontos.
    Layout da fila GX real (`gxCmdQueue_s`) confirmado via `WebFetch` de `GSP_Shared_Memory`
    (3dbrew): cabeçalho de 8 bytes em `sharedMemBase+0x800` (cliente 0) + até 15 entradas de 32
    bytes. `gpu/GxCommandQueue` decodifica/processa (`ProcessCommandList` lê a lista real da
    memória do guest e chama `CommandListParser`; `MemoryFill`/`DisplayTransfer`/`TextureCopy`/
    `RequestDma` são completados IMEDIATAMENTE sem efeito real — `simple_tri` desenha DIRETO na
    textura de apresentação, ver Javadoc de `VulkanRenderer`, então o `DisplayTransfer` que o
    hardware real precisaria não tem efeito observável aqui — mas o evento correspondente PRECISA
    ser sinalizado, senão `gxCmdQueueWait`/`gspWaitForEvent` do guest trava para sempre, mesma
    classe de bug já documentada para VBlank). `gpu/shader/ShaderUpload` (novo) captura o upload de
    vertex shader por REGISTRADOR-FIFO (o caminho real do hardware — código/`opdescs`/uniforms
    float chegam um-a-um via `GPUREG_VS_CODETRANSFER_*`/`OPDESCS_*`/`FLOATUNIFORM_*`, offsets
    confirmados via `WebFetch`), diferente do `.shbin`-arquivo que PR1/PR2 usavam nos testes.
    `CommandListParser.parse` ganhou uma sobrecarga com `RegisterWriteListener` (aditiva, G3) para
    que esses registradores-FIFO sejam observados escrita a escrita (o valor final em
    `PicaRegisters` não basta — perde a sequência da rajada). `GspGpuService` agora tem
    `PicaRegisters`/`ShaderUpload` persistentes + um `PicaRenderer` real opcional (novo construtor,
    o antigo delega com `renderer=null` — comportamento herdado no `--headless`); ao detectar um
    `DrawArrays`/`DrawElements` de verdade dentro de uma lista processada, monta o
    `ShaderBinary.Executable` a partir do que `ShaderUpload` capturou e chama `VertexPipeline` de
    verdade. `Main`/`N3dsMachine` ganharam uma sobrecarga de `create(...)` com `PicaRenderer`
    opcional — o modo janela agora cria o `VulkanRenderer` ANTES da máquina e injeta ele no
    `gsp::Gpu`, fechando o caminho ponta a ponta.
  - **Simplificações documentadas conscientemente** (Javadoc de `ShaderUpload`, não são bugs):
    (1) mapeamento saída→semântica (`GPUREG_SH_OUTMAP_O0`-`O6`) NÃO decodificado — segue a
    convenção universal do `picasso`/`citro3d` (`o0`=posição,`o1`=cor), a mesma que PR1/PR2 já
    assumiam implicitamente; (2) só o modo **float32** do upload de uniforms é suportado (4 palavras
    IEEE754/constante) — o modo float24 empacotado (3 palavras cruzando fronteira de bits) não pôde
    ser validado sem GPU real disponível nesta sessão e lança `UnsupportedOperationException` em vez
    de arriscar decodificar errado; (3) desenho sempre em `Screen.TOP` (única tela composta por todo
    o HLE, RFC D6); (4) múltiplos `DrawArrays`/`DrawElements` dentro da MESMA lista leem o estado
    FINAL dos registradores, não um snapshot por disparo (suficiente para `simple_tri`, que desenha
    uma vez).
  - **Teste-alvo da PR** (`GspGpuServiceTest#triggerCmdReqQueueProcessaListaDeComandosRealEDesenhaTrianguloDeVerdade`):
    monta a fila GX real na memória compartilhada com um `ProcessCommandList` apontando pra uma
    lista PICA200 completa (upload de shader por FIFO + formato de vértice + `DrawArrays`),
    dispara `TriggerCmdReqQueue` por IPC (o MESMO caminho que `gspSubmitGxCommand` usa de verdade)
    e afirma que o `RecordingRenderer` recebeu os 3 vértices certos (posição/cor) — SEM `.shbin`-
    arquivo, sem GPU. É o primeiro teste da G5 que exercita o caminho ponta a ponta real (fila →
    registradores → shader → `VertexPipeline` → renderer), não só uma camada isolada.
  - **Fumaça manual**: `n3dsemu --headless --slices=200 testdata/hello-world.3dsx` e
    `.../simple_tri.3dsx` (`C:\devkitPro\examples\3ds\graphics\gpu\simple_tri\simple_tri.3dsx`) —
    os dois terminam no MESMO padrão de loop estacionário (`svcArbitrateAddress`/
    `svcWaitSynchronization`/`svcClearEvent`, espera de VBlank normal, já existente antes desta PR)
    sem exceção nova nem trava adicional — não é validação visual (isso cabe ao usuário, RFC D4),
    só confirma que a integração não quebrou o boot nem lançou nada.
  - `mvn -o test` verde: **180 testes** (17 novos: `TevConfigTest`+`TevGlslGeneratorTest` (6),
    `TextureTest` (5), `ShaderUploadTest` (6), mais o teste de integração real acima), incl.
    `VulkanRendererSmokeTest` (3, contra o driver real desta máquina, inalterado). G5-invariante não
    se aplica (nenhum arquivo arm-jitter tocado).
  - **Pendências reais para PR4** (não são bugs desta PR, escopo conscientemente adiado):
    integração do sampler Vulkan de textura (descriptor set/binding real por *draw*), wiring do
    `TevGlslGenerator` na criação do pipeline Vulkan (hoje só testado como string pura), modo
    float24 do upload de uniforms, decodificação granular de `SH_OUTMAP` (para shaders que não
    seguem a convenção `o0`=posição/`o1`=cor), `ETC1`/`ETC1A4`. **Nenhuma delas bloqueia o aceite
    do M5** (`simple_tri` não usa textura, não usa TEV além de passagem direta, não foge da
    convenção de saída).
  - **Pendente do usuário**: rodar `n3dsemu <caminho>/simple_tri.3dsx` (modo janela, default) e
    confirmar visualmente o triângulo colorido na tela de cima — RFC D4, nenhuma task da trilha G
    fecha sozinha por inspeção de log/framebuffer. Se aparecer errado ("embaralhado em
    quadradinhos" = deswizzle; cor errada = TEV/operand descriptor; nada aparece = revisar o
    `--trace-svc` em busca de uma `svc` não implementada ANTES do primeiro `TriggerCmdReqQueue`, ou
    o modo float24 de uniform não suportado — lança exceção visível no console).

## F11 (`virtual-arm-box --machine=raspi3-64`) — sessão 2 (2026-08-18), relato minucioso

Itens 1-2 do "Inclui" (assets versionados + `FdtPatcher.withNodeRemoved`) fecharam numa sessão
anterior (parte 1: `testdata/raspi3-64/kernel8.img`+`bcm2710-rpi-3-b.dtb` do mesmo commit já
fixado por `testdata/raspi1/README.md`, sha256 documentados, achado do `gunzip` manual necessário
no download via `raw.githubusercontent.com`; parte 2: `FdtPatcher.withNodeRemoved` removendo de
verdade `FDT_BEGIN_NODE`/subárvore/`FDT_END_NODE`, distinto de `withNodeDisabled`).

Itens 3-5 fecharam nesta sessão: `Raspi364Machine implements Machine64` (interface nova —
`Machine` existente é ligada a `ArmCore`/`JitRuntime` 32-bit, incompatível com `Aarch64Core`;
`RunnableMachine` extraído como supertipo comum de `runSlice()`/`typeByte()`, mudança aditiva,
`VersatilePbMachine`/`Bcm2835Machine` inalterados). Mapa físico BCM2837 (`0x3F00_0000`) montado
reaproveitando a MESMA `PagedAddressSpace`/periféricos MMIO 32-bit de `device/bcm2835/`
(UART/IC/mailbox/CPRMAN), envolvida como `AddressSpace64` via `AddressSpace64.wrapping()` (RFC-
IR-64BIT §3.2). `device/bcm2836/` novo: `Bcm2836GenericTimer` (`Aarch64SystemRegisterBus` para
`CNTFRQ_EL0`/`CNTPCT_EL0`/`CNTP_TVAL_EL0`/`CNTP_CTL_EL0`/`CNTP_CVAL_EL0`) + `Bcm2836LocalIntc`
(MMIO, `brcm,bcm2836-l1-intc`, janela física separada `0x4000_0000`, roteia a PPI do timer +
passthrough do `Bcm2835Ic` legado). `boot/CompositeSystemRegisterBus` novo (delega ao primeiro bus
que atende — `Aarch64VmsaSystemRegisters` e `Bcm2836GenericTimer` cobrem subconjuntos disjuntos de
`Aarch64SystemRegisterId`). DTB real inspecionado por `strings`: `cpu@1..3` removidos (D1, sem
SMP/PSCI), `mmc@7e202000`/`mmc@7e300000` (Pi 3 tem DOIS nós MMC)/`usb@7e980000` desabilitados.
Achado D2 (decisão de escopo): `Aarch64Core` só modela EL0→EL1 transiente, não um "EL1
persistente"; a máquina contorna forçando `exceptionState().setInEl1(true)` no boot — pendência
explícita: `enterIrq`/`enterMemoryAbort` sempre saltam para os offsets "lower EL AArch64"
(`+0x400`/`+0x480`), corretos só para EL0→EL1; um kernel já "em EL1" tomando sua PRÓPRIA exceção
esperaria `+0x200`/`+0x280` — resolver exigiria estender `Aarch64Core`, fora do "Inclui".

**Bloqueio real desta sessão**: máquina/DTB/carga provados byte-a-byte corretos (`PC=0`/`X0`=
endereço do DTB batendo com `text_offset=0` real), mas a PRIMEIRA instrução do kernel8.img real
(`ccmp x18, #0x0, #0xd, pl`, offset `0x0`, truque polyglot EFI "MZ") lança
`UnsupportedOperationException`: `CCMP`/`CCMN` nunca foram implementados em nenhuma sub-task do
épico B6.3. Gap de FEATURE real, fora do "Inclui" da task. `Raspi364BootTest#smokeTestBootsWithoutException`
pina o achado como regressão; os 3 testes de marco textual ficam `@Disabled`. Achado menor: sem
busybox `aarch64` estático real disponível, `testdata/raspi3-64/initramfs.cpio.gz` é SINTÉTICO (só
`TRAILER!!!`), suficiente porque os marcos de boot acontecem antes da montagem. `mvn -o test`
verde no virtual-arm-box; G5 não se aplica (nenhum arquivo arm-jitter tocado).

## F11 — sessão 3 (2026-08-20), relato minucioso

Retomada após **B6.8** (CCMP/CCMN fechado no arm-jitter, `mvn -o install` local já feito antes
desta sessão começar). Rodando `Raspi364BootTest#smokeTestBootsWithoutException` (backend
INTERPRETED) sem alterar nenhum código de produção: a primeira instrução já não lança mais — o
boot avança de `0x0` até `0x13ba9e8`, onde bate num **SEGUNDO** gap de decode real:
`0xaa0003f5` = `ORR X21, XZR, X0` (`LSL #0`), ou seja, o alias `MOV X21, X0` da classe "Logical
(shifted register)" (`AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` com operando registrador).
Confirmado por leitura direta de `Aarch64Decoder#decodeDataProcessingRegister`
(`arm-jitter/core/.../decoder64/Aarch64Decoder.java`, linha ~942): comentário explícito "Logical
(shifted register): fora do escopo fechado do épico (ver a task B6.3.1)" — gap documentado desde
B6.3.1, nunca coberto por nenhuma sub-task de B6 (B6.3.1-B6.3.4 cobriram ALU shifted/extended
register, `CSEL`/aliases, bitfield, mul/div, exclusive access; B6.8 cobriu só `CCMP`/`CCMN`).

Mesma categoria de decisão da sessão 2 (não é bug, é feature ausente, fora do "Inclui"/"Não
inclui" desta task) — **não implementado nesta sessão**. Diferença relevante para priorização: o
alias `MOV` de registrador via `ORR` é onipresente em qualquer prólogo/epílogo de função A64 real
(muito mais comum que `CCMP`), então é provável que seja o PRÓXIMO obstáculo dominante para
qualquer kernel real, não uma curiosidade isolada como o polyglot EFI do `CCMP`.

Trabalho desta sessão: `Raspi364BootTest` atualizado — `smokeTestBootsWithoutException` agora
espera o novo endereço/encoding (`0x13ba9e8`/`0xaa0003f5`) em vez do antigo `CCMP`; Javadoc da
classe e razões dos 3 `@Disabled` reescritos para descrever o achado novo (continuam desabilitados
— nenhum marco textual foi alcançado). Nenhum arquivo do arm-jitter tocado (G5 não se aplica,
mesmo precedente da sessão 2). `mvn -o test` verde no virtual-arm-box (87 testes, 5 skipped —
mesmos 3 `@Disabled` da F11 + 2 pré-existentes do `Raspi1BootTest`), sem regressão em
`Raspi1BootTest`/`VersatilePbBootTest`. Sugestão para o usuário: uma sub-task nova no arm-jitter
(ex. `B6.9`) cobrindo "Logical (shifted register)" teria o mesmo formato/rigor de corpus real de
B6.8, e destravaria a continuação da F11 de novo (com a expectativa real de que ainda pode haver
um terceiro/quarto gap depois deste — instruções tão básicas como `MOV`/`AND`/`ORR` de registrador
faltando sugere que a superfície "comum" da classe "Data Processing — Register" pode ter mais
buracos do que os já mapeados).

## B10.6b/B10.6c — sessão única (2026-08-27), relato minucioso

Últimos dois itens do plano mestre `b10-plano-el2-el3.md` — task spec escrita e executada na mesma
sessão (só existiam como duas linhas no plano mestre), combinadas numa task só (mesmo precedente de
B9.2/B9.4/B9.6) por serem estruturalmente idênticas: `AT S1E2R`/`S1E2W`/`S1E3R`/`S1E3W`, stage-1
PURA dos regimes EL2/EL3 (sem stage-2 — diferente de `S12E*`/B10.8), que B10.6/B10.8 tinham deixado
de fora por faltar `TTBR0_EL2`/`TTBR0_EL3`.

**Registradores novos**: `TTBR0_EL2` (`op1=4,CRn=2,CRm=0,op2=0`, mesmo `CRn`/`CRm` de `TCR_EL2`) e
`TTBR0_EL3`/`TCR_EL3` (`op1=6,CRn=2,CRm=0,op2=0/2`) — `TTBR0_EL2`/`TTBR0_EL3` têm side effect real
(alimentam a classe de tradução nova), `TCR_EL3` é armazenamento puro (mesma disciplina de
`TCR_EL2`).

**Classe de tradução nova**: `Aarch64PrivilegedStage1TranslatingAddressSpace64` (`memory.mmu`) —
mesma geometria de granule 4KiB/48-bit/4-níveis de `TranslatingAddressSpace64`/
`Stage2TranslatingAddressSpace64`, mas com o formato de permissão real e mais simples que os
regimes EL2/EL3 usam sem VHE: só `AP[2]` (bit 7, somente-leitura — `AP[1]`/bit 6 é `RES0`, sem
distinção EL0/EL1 porque esses regimes não têm um "EL0 companheiro" sem VHE) e um `XN` único
(bit 54, sem `PXN`/`UXN` separados). Duas instâncias em `Aarch64VmsaSystemRegisters`
(`el2Stage1`/`el3Stage1`), cada uma sobre o MESMO físico da stage-1 EL1&0.

**Decoder**: `decodeAddressTranslateStage12` (que já existia desde B10.8, tratando o `op1=0b100` de
EL2) foi renomeado para `decodeAddressTranslateEl2` e ganhou os casos `op2=0/1` (`S1E2R`/`S1E2W`);
`decodeAddressTranslateEl3` novo trata `op1=0b110` (antes caía incondicionalmente em
`unsupported`). Nenhuma colisão de decode nova — o espaço já estava isolado desde B10.6/B10.8, que
documentavam esta task como o próximo passo natural.

**`Aarch64VmsaSystemRegisters#addressTranslate` refatorado**: de um `if`/`else` (que só distinguia
"combinada + `HCR_EL2.VM`" de "stage-1 EL1&0 só") para um `switch` exaustivo sobre
`Aarch64AddressTranslateForm` — 4 grupos (EL1&0, combinada stage-1+stage-2, EL2 puro, EL3 puro). O
método `isCombinedStage12()` do enum ficou sem chamador depois do refactor e foi removido (não
deixar código morto).

**Achado sobre `docs/COBERTURA-ISA.md`**: o script `gerar-cobertura-isa.sh` foi rodado (rede
disponível nesta sessão) e mostrou 5 células de A32/T32 regredindo (`MRS_bank`/`MSR_bank`/`ERET`/
`HVC`/`SMC`, `✅`→`❌`) — instruções que sessões B9.8.2-B9.8.5 já implementaram corretamente e que
esta sessão NÃO tocou (nenhum arquivo A32/T32 foi modificado). Isso é instabilidade do medidor ou
do inventário `decodetree` puxado do QEMU upstream (que pode ter mudado desde a última medição),
não uma regressão real de código — mas a causa raiz não foi investigada (fora do orçamento desta
task, que é sobre A64). A tabela regenerada foi **descartada** (`git checkout`) para não commitar
números não explicados junto com o trabalho real desta sessão; documentado como achado/armadilha
na task para quem for regenerar a tabela de verdade no futuro.

Testes novos/atualizados: `Aarch64PrivilegedStage1TranslatingAddressSpace64Test` (5, mesmo padrão
de `Stage2TranslatingAddressSpace64Test`), `Aarch64VmsaSystemRegistersTest` (+7, via `TTBR0_EL2`/
`TTBR0_EL3` reais do barramento), `Aarch64DecoderCorpusTest` (3 testes antigos que assumiam
`S1E2*`/`S1E3*` fora de escopo substituídos por 8 novos que decodificam de verdade + regressão de
`op2` reservado). `mvn -o test` verde no `core` (2352 testes, baseline 2343) + `truffle`; `mvn -o
install` local. G5 completo: gbaemu ✅, ndsemu ✅, n3dsemu ✅, virtual-arm-box ✅ (único consumidor
A64 — F11 não emite `AT` EL2/EL3, sem mudança observável), armbox 43/43 (nenhuma falha
pré-existente reproduzida nesta sessão). Commit `ab4e794`, push feito.

**Fecha o épico `b10-plano-el2-el3.md` por completo** — as 12 linhas da escada B10 (B10.1-B10.9,
incluindo B10.6b/B10.6c) estão todas fechadas. Sem marco de release cruzado (mudança de decode
pequena, 4 formas de uma instrução já parcialmente coberta) — regeneração de cobertura real fica
para sessão que também investigue o desvio de A32/T32 descrito acima.



---

## Arquivo de 2026-08-28 — narrativa completa movida de FILA-EXECUCAO.md

A sessão a seguir limpou o `FILA-EXECUCAO.md` (regra 5 da Disciplina de custo: o arquivo
ativo é lido INTEIRO por todo agente novo, então seu tamanho é custo pago em toda sessão
futura). Todo o conteúdo detalhado que estava lá (Onda 3/4/5, histórico narrativo B8.x/B9.x/
B10.x/B11.x/G6.x/F-infra até B11.6) foi movido para cá sem edição — o `INDICE.md` de cada
trilha já é a fonte de verdade sobre status, e cada task tem sua própria seção `## Resultado`.

# Fila de execução (2026-07-15; reestruturada 2026-07-17) — para agentes com contexto limitado

**Regras de sessão (obrigatórias, existem para o agente NÃO se perder):**

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a
   próxima task na mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes
   G1-G7) e depois SÓ o arquivo da task + os fontes que ela cita. Não explorar o
   repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao
   usuário — não improvisar.
4. Nunca pegar itens da seção "Pendências que EXIGEM modelo forte" do
   `tasks/README.md`, nem da seção "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25 + gbaemu +
   ndsemu), status atualizado no índice do `tasks/README.md`, 1 commit começando
   com o ID (`B3.5: ...`).
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale
   só entre repos DIFERENTES (arm-jitter ∥ gbaemu ∥ armbox). Duas sessões no
   mesmo working tree misturam WIP uma da outra (já aconteceu em 2026-07-16:
   um `git add -A` de uma sessão de docs varreu os arquivos half-done da A6
   para dentro do commit errado — desfeito, mas é exatamente o acidente que
   esta regra evita). Pelo mesmo motivo: **commits sempre com paths explícitos
   (`git add <arquivos da SUA task>`), nunca `git add -A`.**

**Prompt de kickoff (copiar/colar, trocando o ID):**

> Leia `arm-jitter/tasks/README.md` inteiro. Depois execute APENAS a task
> **<ID>** (arquivo `<caminho>`), seguindo o protocolo do README. Leia os
> arquivos-fonte citados na task antes de escrever código. Implemente somente o
> que está em "Inclui"; respeite as decisões já tomadas (não reavaliar). Ao
> final: suites verdes, índice atualizado, um commit `"<ID>: ..."`.

---

## Disciplina de custo (obrigatória, adicionada 2026-08-17)

Sessões de investigação longa (ex.: F3) já consumiram rodadas caras demais. Regras novas,
valem para QUALQUER sessão futura desta fila:

1. **G5 "leve" durante iteração, G5 completo só uma vez por sessão.** Ao corrigir um bug no
   arm-jitter, rode primeiro só a suíte do próprio arm-jitter + o consumidor que exercita o
   bug (ex.: `virtual-arm-box` para CP15/MMU). Só rode a sequência completa
   arm-jitter+gbaemu+ndsemu+armbox+virtual-arm-box UMA vez, pouco antes do commit final da
   sessão — não a cada fix intermediário.
2. **Backend INTERPRETED em boot de sistema real é caro (já levou >30min) — rode-o só
   quando o backend JIT já confirmar o marco.** Nunca rode os dois "por rotina" a cada
   sessão. Se INTERPRETED não terminar num orçamento razoável (~10-15min), documente como
   "não concluído nesta sessão" e siga — não é bloqueador.
3. **Nunca lance um teste/boot longo em background e pare a sessão "esperando notificação".**
   Um processo de shell solto (fora da ferramenta `Agent`) não avisa ninguém quando termina.
   Rode de forma BLOQUEANTE numa única chamada, com timeout alto; se precisar de mais tempo
   que um timeout permite, faça polling DENTRO da mesma chamada (loop com `sleep`) até ter o
   resultado real, em vez de retornar e reentrar em rodadas novas só para checar.
4. **Orçamento de tool-calls por sessão de investigação aberta (~60-80).** Se a causa raiz
   não foi isolada dentro disso, pare, documente o que foi descartado/aprendido e devolva —
   não é obrigatório fechar tudo numa sessão só (histórico da F3 mostra isso sendo normal).
5. **Ao fechar uma task ou sessão, mova o relato minucioso para `tasks/FILA-HISTORICO.md`**
   e deixe aqui só um resumo de poucas linhas (o que fechou, o achado principal, o próximo
   passo). O arquivo ativo deve continuar pequeno — ele é lido INTEIRO por todo agente novo
   como parte do protocolo, então seu tamanho é custo pago em toda sessão futura.
---
## Onda 5 — cobertura de ISA (priorizada pelo usuário em 2026-08-21) 🔝 TOPO DA FILA

Frente pedida pelo usuário depois de ver a `docs/COBERTURA-ISA.md`: **completar as instruções das
arquiteturas que os emuladores usam, mínimo 80% por arquitetura, alvo final tudo ✅** — sem presumir
que alguma instrução "nunca vai ser usada" (o precedente é EL1/EL2, descartado como desnecessário e
depois exigido inteiro pelo `virtual-arm-box`).

**Plano mestre: `trilha-b-arquiteturas/b7-plano-cobertura-isa.md`** — leia ANTES de pegar qualquer
`B7.x`/`B8.x`. Ele traz a regra de triagem (o inventário do QEMU mistura versões de arquitetura) e
o protocolo por task.

**Estado dos repositórios (2026-08-21)**: os 6 estão sincronizados com o GitHub, `ahead=0`.
O `n3dsemu` ganhou repositório nesta sessão (`https://github.com/vitorsilverio/n3dsemu`, público,
21 commits) — ele era o único que nunca teve `origin`. ⚠️ **Ainda falta nele o que a F8/F9 fizeram
nos outros**: labels, `ISSUE_TEMPLATE`, e a CI da F6 (`.github/workflows`). Candidato a task curta.

**Duas regras novas do `tasks/README.md` valem para TODA task, não só desta onda:**
- **push obrigatório** ao fechar (os repos estavam só locais — `arm-jitter` chegou a 76 commits à
  frente do `origin`);
- **marco de cobertura → release no Maven Central**: global +5 pp ou qualquer arquitetura +10 pp
  desde o último release. Baseline 2026-08-21: global **53%**, A64 **18%**.

Diagnóstico medido: no 32 bits já estamos em 82-83% (o que derrubava os emuladores era a cauda
longa, não "quase nada implementado"); **em A64 estamos em 18%, e é lá que está ~90% do trabalho.**

## ✅ B10 — EL2/EL3 completo — FECHADA 2026-08-22, e ✅ `B10.6b`/`B10.6c` (as duas últimas células,
## `AT S1E2*`/`S1E3*`) fechadas 2026-08-27 — a escada B10 inteira está ✅ agora, sem pendência
## conhecida. Volta ao usuário decidir o próximo item da fila (Onda 5 restante, Q5+, ou outra
## prioridade) — nada aqui deve ser pego automaticamente sem essa decisão.

## 🔝🔝 B10 — EL2/EL3 completo (priorizado pelo usuário em 2026-08-21, à FRENTE do resto da Onda 5)

Ao fechar B8.3, o usuário reagiu ao adiamento documentado de `AT`/registradores de debug: **nenhuma
instrução ARM real fica de fora por parecer grande demais** — se depende de EL2/EL3 completos,
implementa-se EL2/EL3. Ver `feedback-nunca-excluir-instrucao-arm` na memória do agente e o plano
mestre `trilha-b-arquiteturas/b10-plano-el2-el3.md` (leia ANTES de pegar qualquer `B10.x` — traz a
escada completa, fatos de referência do `ARM DDI 0487` e a ordem sugerida). Isto passa NA FRENTE de
Q5+ abaixo: só volte para B9.x/B8.4+ depois que a escada B10 fechar (ou o usuário repriorizar).

| # | Task | Arquivo | Repo | Depende de | Nota |
|---|------|---------|------|-----------|------|
| ~~R1~~ | ~~**B10.1**~~ ✅ fechada 2026-08-21 — generalizar estado de exceção para EL0-EL3 | `trilha-b-arquiteturas/b10.1-el2-el3-estado-generalizado.md` | arm-jitter | — | `Aarch64ExceptionLevel` novo (4 níveis, `M[3:0]` real de `SPSR_ELx`); `Aarch64ExceptionState` generalizado por nível (conveniências EL1 preservadas, comportamento EL0↔EL1 idêntico); vetor de exceção agora escolhe o grupo certo ("mesmo nível" vs "nível inferior"); `ERET` lê o alvo real de `SPSR_ELx.M` em vez de sempre "EL0". `EL2`/`EL3` ainda não roteados de verdade (B10.4/B10.5) — só a infraestrutura. G5 completo (armbox 40/41 pré-existente). Ver **Resultado** na task. **Destrava B10.2/B10.3** |
| ~~R2~~ | ~~**B10.2**~~ ✅ fechada 2026-08-21 — registradores de sistema EL2 | `trilha-b-arquiteturas/b10.2-el2-registradores-sistema.md` | arm-jitter | B10.1 ✅ | 13 registradores (`HCR_EL2`/`SCTLR_EL2`/`MDCR_EL2`/`CPTR_EL2`/`TCR_EL2`/`VTTBR_EL2`/`VTCR_EL2`/`SPSR_EL2`/`ELR_EL2`/`FAR_EL2`/`ESR_EL2`/`CNTHCTL_EL2`/`VBAR_EL2`) decodificados (`Aarch64Decoder`, novo branch `op1=4`) e armazenados em `Aarch64VmsaSystemRegisters` (5 delegam ao banco por nível já criado em B10.1; 8 são armazenamento puro novo) — SEM side effect funcional ainda (nenhum código roda em EL2, isso é B10.4+). `mvn -o test` verde + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente). ⚠️ Achado: toolchain devkitA64 indisponível nesta sessão — encodings derivados por fórmula (conferida contra 3 opcodes reais já no repo), não corpus real; documentado para regeneração futura. Ver **Resultado** na task |
| ~~R3~~ | ~~**B10.3**~~ ✅ fechada 2026-08-21 — registradores de sistema EL3 | `trilha-b-arquiteturas/b10.3-el3-registradores-sistema.md` | arm-jitter | B10.1 ✅ | 7 registradores (`SCTLR_EL3`/`SCR_EL3`/`MDCR_EL3`/`CPTR_EL3`/`SPSR_EL3`/`ELR_EL3`/`VBAR_EL3`) decodificados (`Aarch64Decoder`, novo branch `op1=6`) e armazenados em `Aarch64VmsaSystemRegisters` (3 delegam ao banco por nível já criado em B10.1; 4 são armazenamento puro novo) — SEM side effect funcional ainda, mesma disciplina de B10.2. `ESR_EL3`/`FAR_EL3` deliberadamente FORA (não listados no plano mestre para esta task). `mvn -o test` verde + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente). Mesmo achado de B10.2: encodings derivados por fórmula (toolchain devkitA64 indisponível). Ver **Resultado** na task |
| ~~R4~~ | ~~**B10.4**~~ ✅ fechada 2026-08-21 — `HVC` real (entra em EL2) | `trilha-b-arquiteturas/b10.4-hvc-real.md` | arm-jitter | B10.1 ✅, B10.2 ✅ | Árvore de decisão real (`ARM DDI 0487 C6.2.83`): EL0→UNDEFINED(EL1); EL1→EL2 (ou UNDEFINED self-trap se `HCR_EL2.HCD`); EL2→EL2 (auto-chamada real do manual); EL3→UNDEFINED self-trap em EL3 (nunca reduz pra EL1 — achado de design corrigido na mesma sessão, `enterUndefinedInstructionException` tem alvo fixo EL1 e não serve para o caso EL3). `Ir64Op.PrivilegedCall` ganhou `isHvc()`; `Aarch64HypervisorCallException` nova, capturada em `step()`/`executeBlock()` (mesmo padrão de `Aarch64BreakpointException`). `SMC` inalterado (stub, B10.5). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não relacionada). Sem mudança em `docs/COBERTURA-ISA.md` (sem side effect observável em consumidor real hoje — F11 pula GIC/PSCI). Task spec escrita nesta sessão (só existia como linha no plano mestre). **Destrava nada diretamente, mas próximo natural é B10.5 (`SMC`, mesmo raciocínio com `SCR_EL3`) ou B10.7 (registradores de debug, independente)** |
| ~~R5~~ | ~~**B10.5**~~ ✅ fechada 2026-08-21 — `SMC` real (entra em EL3) | `trilha-b-arquiteturas/b10.5-smc-real.md` | arm-jitter | B10.1 ✅, B10.3 ✅ | Mesma árvore de decisão de B10.4 (`HVC`), com `SCR_EL3.SMD` no lugar de `HCR_EL2.HCD`: `EL0`→`UNDEFINED(EL1)`; `EL1`/`EL2`/`EL3`→self-trap se `SMD`, senão `EL3` (auto-chamada de `EL3`). Stub `PSCI_RET_NOT_SUPPORTED` REMOVIDO (não só para `SMC`: `Ir64BlockExecutor#executePrivilegedCall` agora lança nos dois casos). Task spec escrita nesta sessão (só existia como linha no plano mestre). `mvn -o test` verde (1697) + `install`; G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente não relacionada). Ver **Resultado** na task. **Próximos sem dependência pendente: B10.6 (`AT`), B10.7 (debug, independente) ou B10.9 (`TLBI` EL2/EL3)**
| ~~R6~~ | ~~**B10.6**~~ ✅ fechada 2026-08-21 — `AT`, formas EL1&0 | `trilha-b-arquiteturas/b10.6-at-address-translation.md` | arm-jitter | B10.1 ✅, B10.2 ✅ | `S1E0R`/`S1E0W`/`S1E1R`/`S1E1W` reais: `PAR_EL1` novo (armazenamento), `Ir64Op.AddressTranslate`/`Aarch64AddressTranslateForm` novos, decoder/executor/`Aarch64SystemRegisterBus#addressTranslate`, `TranslatingAddressSpace64#translateForAddressTranslate` (reaproveita `walk()` sem tocar TLB, `privileged` trocado temporariamente), `Aarch64ParEncoder` novo (`memory.mmu`). **2 bugs de decode corrigidos (G8)**: `AT` caía em `CACHE_MAINTENANCE_NOP` (mesmo `CRn=0b0111` da manutenção de cache, `CRm` não checado); um primeiro fix só cobria `op1==0`, deixando `S1E2R`/`S1E3R` caírem no mesmo bug — corrigido excluindo `CRm=0b1000` do bucket de NOP para QUALQUER `op1`. `mvn -o test` verde (1724) + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente). `S1E2*`/`S1E3*`/`S12E*` viraram tasks explícitas no plano mestre (**B10.6b**/**B10.6c**, dependem de `TTBR0_EL2`/`TTBR0_EL3`+`TCR_EL3` NOVOS que não existem; `S12E*` seguem em **B10.8**). Ver **Resultado** na task |
| ~~R6b/R6c~~ | ~~**B10.6b/B10.6c**~~ ✅ fechada 2026-08-27 — `AT`, formas EL2/EL3 puras (`S1E2R`/`S1E2W`/`S1E3R`/`S1E3W`) | `trilha-b-arquiteturas/b10.6b-b10.6c-at-el2-el3-stage1.md` | arm-jitter | B10.2 ✅, B10.3 ✅, B10.6 ✅ | `TTBR0_EL2`/`TTBR0_EL3`/`TCR_EL3` novos + `Aarch64PrivilegedStage1TranslatingAddressSpace64` novo (regime próprio de EL2/EL3, só `AP[2]`/`XN` único, sem `AP[1]`/`PXN`/`UXN`); `addressTranslate` refatorado para `switch` exaustivo. **Fecha a escada B10 por completo.** `mvn -o test` verde (core 2352) + `install`; G5 nos 5 repos ✅ (armbox 43/43, sem falha pré-existente reproduzida). `docs/COBERTURA-ISA.md` regenerado e DESCARTADO (mostrou regressão não relacionada em 5 células A32/T32 já implementadas por B9.8.x — instabilidade do medidor, não investigada). Ver **Resultado**/histórico completo em `tasks/FILA-HISTORICO.md` |
| ~~R7~~ | ~~**B10.7**~~ ✅ fechada 2026-08-21 — registradores de debug (`op0=2`) | `trilha-b-arquiteturas/b10.7-aarch64-debug-registers.md` | arm-jitter | — | `MDSCR_EL1`/`OSLAR_EL1`/`OSLSR_EL1`/`DBGBVR0_EL1`/`DBGBCR0_EL1`/`DBGWVR0_EL1`/`DBGWCR0_EL1` decodificados (`Aarch64Decoder`, novo branch `op0==2` checado ANTES do `op0!=3` early-return) e armazenados em `Aarch64VmsaSystemRegisters` — armazenamento puro, SEM enforcement de `RO`/`WO` (decisão explícita: `OSLAR_EL1`/`OSLSR_EL1` são `WO`/`RO` no hardware real, aqui aceitam os dois sentidos para não travar o guest sem debugger conectado). Encodings conferidos contra `target/arm/debug_helper.c` real do QEMU via `WebFetch` antes de codificar. Escopo: só `n=0` de `DBGBVR`/`DBGBCR`/`DBGWVR`/`DBGWCR` (consistente com `ID_AA64DFR0_EL1.BRPs=WRPs=0` já anunciado por `Aarch64Core`, B6.6.7/B6.10) — task escrita nesta sessão (só existia como linha no plano mestre). `mvn -o test` verde (core+truffle, +9) + `install`; G5 nos 5 repos ✅ (armbox 40/41 pré-existente, `Armv7TortureTest`/`VfpRegisters`, não relacionada). Sem mudança em `docs/COBERTURA-ISA.md` (sem side effect observável em consumidor real hoje). **Próximos sem dependência pendente da escada B10**: `B10.8` (stage-2) ou `B10.9` (`TLBI` EL2/EL3) |
| ~~R8~~ | ~~**B10.8**~~ ✅ fechada 2026-08-22 — stage-2 (`IPA→PA`), `AT S12E1R`/`S12E1W`/`S12E0R`/`S12E0W` reais | `trilha-b-arquiteturas/b10.8-at-stage2.md` | arm-jitter | B10.2 ✅, B10.6 ✅ | `Stage2TranslatingAddressSpace64` novo (`memory.mmu`, walk IPA→PA independente, mesma geometria 4KiB/48-bit/4-níveis da stage-1, permissão via `S2AP`/`XN` único, sem micro-TLB). Achado real (conferido contra `cpregs-at.c` do QEMU): `AT S12E1R`/`S12E1W`/`S12E0R`/`S12E0W` usam o MESMO `op1=0b100` de `AT S1E2R`/`S1E2W` — só `op2` (4-7 vs. 0-1) distingue; `S1E2*` continua `unsupported` (B10.6b). `TranslatingAddressSpace64#translateForAddressTranslateStage12` encadeia stage-1+stage-2 — simplificação documentada: as PRÓPRIAS tabelas de stage-1 continuam lidas do físico direto, só o endereço de dados final passa por stage-2. `PAR_EL1.S` (bit `9`) novo em `Aarch64ParEncoder` distingue falha de stage-1/stage-2 (MESMO `FST` por nível nos dois). `HCR_EL2.VM=0` faz `S12E*` se comportar como `S1E*` (stage-2 desligada). `mvn -o test` verde no `core` (1756) + `truffle` (13), `mvn -o install`. G5: gbaemu ✅, ndsemu ✅, virtual-arm-box ✅ (único consumidor A64, sem mudança observável), n3dsemu ✅, armbox 40/41 (pré-existente, não relacionada). **Fecha a escada B10** (só `B10.6b`/`B10.6c` seguem fora, bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos — sem consumidor real hoje). Ver **Resultado** na task |
| ~~R9~~ | ~~**B10.9**~~ ✅ fechada 2026-08-22 — `TLBI` EL2/EL3 + stage-2 (decode) | `trilha-b-arquiteturas/b10.9-tlbi-el2-el3.md` | arm-jitter | B10.1 ✅ | `op1=0b100`(EL2, cobre também stage-2 `IPAS2E1*`/`IPAS2LE1*` e combinado `ALLE1*`/`VMALLS12E1*` — MESMO `op1` no hardware real, conferido contra `target/arm/tcg/tlb-insns.c` real do QEMU)/`0b110`(EL3) passam a ser aceitos em `decodeSystemInstructionSys` (novo helper `isTlbiRegime`), mapeando para `Ir64SystemInstructionOp.TLBI_ALL` já existente — sem mudança no executor, mesma simplificação "invalidar tudo" já aplicada ao regime EL1&0 (nenhuma TLB modelada em EL1/EL2/EL3). Toolchain devkitA64 estava DISPONÍVEL nesta sessão (ao contrário do achado de B10.2/B10.3) — 7 testes novos com encodings reais via `aarch64-none-elf-as`/`objdump`. `mvn -o test` verde (core 1735 + truffle 13) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não relacionada). Task spec escrita nesta sessão. Ver **Resultado** na task. **Único item restante da escada B10: `B10.8` (stage-2 de verdade — o mais arriscado, deixado por último de propósito); `B10.6b`/`B10.6c` seguem bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` inexistentes** |

## Onda 5 — cobertura de ISA restante (retomar só depois de B10 fechar)

| # | Task | Arquivo | Repo | Depende de | Nota |
|---|------|---------|------|-----------|------|
| ~~Q1~~ | ~~**E6**~~ ✅ fechada 2026-08-21 — espaço incondicional (`cond==0b1111`) agora vira `UNIMPLEMENTED` | `trilha-e-manutencao/e6-espaco-incondicional-undefined.md` | arm-jitter | — | `decodeUnconditional` novo roteia `cond=1111` antes de qualquer dispatch condicional (inclusive antes do `SWI`, que tinha o mesmo vazamento); os 8 carve-outs já existentes movidos sem mudar bits; `CoprocessorDecoder`/`VfpDecoder` preservados via `decoderExtensions()`. Teste de regressão: `0xF2000000` (`VHADD`) não vira mais `AND`. `mvn -o test` verde + G5 nos 4 consumidores (armbox com a falha pré-existente já documentada). Sem mudança na tabela de cobertura (o script já contava esses casos como ❌) — sem marco de release. Ver índice da trilha E para o detalhe completo. **Destrava Q2 (B8.1)** |
| ~~Q2~~ | ~~**B8.1**~~ ✅ fechada 2026-08-21 — A64 load/store escalar | `trilha-b-arquiteturas/b8.1-a64-load-store-escalar.md` | arm-jitter | E6 ✅ | `STNP`/`LDNP`/`LDPSW`/`PRFM`/`LDTR`/`STTR`/`LDXP`/`STXP`/`LDAR`/`STLR`/`CAS`/`CASP` implementados. 2 bugs reais de decode corrigidos (G8): `idx=10` sem checar bit21 confundia `LDTR`/`STTR` com registrador-offset; `PRFM` (`sz=DOUBLEWORD`,`opc=10`) caía em "reservado" por engano (achava que era a forma SIMD 128-bit, que exige `V=1`). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente não relacionada). `docs/COBERTURA-ISA.md`: A64 18%→20%, global 53%→53% — sem marco de release ainda. **⚠️ Achado + CORRIGIDO na mesma sessão de fechamento**: `b7-plano-cobertura-isa.md` reusava os IDs `B7.1`-`B7.5`, que já pertenciam ao épico M-profile/Cortex-M (fechado 2026-07-23) — renumerado para `B9.1`-`B9.7` (nenhuma das tasks tinha sido executada ainda, sem retrabalho). Ver **Resultado** na task. **Destrava B8.2**|
| ~~Q3~~ | ~~**B8.2**~~ ✅ fechada 2026-08-21 — A64 inteiro restante | `trilha-b-arquiteturas/b8.2-a64-inteiro-restante.md` | arm-jitter | B8.1 ✅ | `ADC`/`SBC`/`EXTR`/`RBIT`/`REV*`/`CLZ`/`CLS`/`CNT`/`SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/`SMULH`/`UMULH`/`RMIF`/`SETF8`/`SETF16`/`CFINV`/`XAFLAG`/`AXFLAG`; bug real corrigido (`REV32`/`REV64` colidiam com `SDIV`/`UDIV`, `opc2` não checado). A64 20%→22%, global 53%→54%. Sem marco de release. **Destrava Q4 (B8.3)** |
| ~~Q4~~ | ~~**B8.3**~~ ✅ fechada 2026-08-21 — A64 branch/system | `trilha-b-arquiteturas/b8.3-a64-branch-system.md` | arm-jitter | B8.2 ✅ | `WFET`/`WFIT`/`CLREX`/`DSB(nXS)`/`SB`/`BRK`/`HLT`/`MSR (immediate)` restantes (`UAO`/`PAN`/`SPSel`/`SBSS`/`DIT`/`TCO`/`ALLINT`/`DAIFSet`/`DAIFClr`) implementados; `SYS` (TLBI/cache) ampliado para "qualquer variante = NOP" (sem TLB/cache modelada, seguro); `CB<cc>`/branches `PAuth` reais/`SVCR` excluídos em `isa-nao-aplicavel.tsv` (posteriores ao Cortex-A53). Bug real corrigido (G8): `MSR SBSS`/`DIT`/`ALLINT` já ✅ por engano — colidiam com `XAFLAG`/`AXFLAG`/`CFINV` por falta de checar `op1`. `AT`/registradores de debug (`op0=2`) deliberadamente deferidos (documentado, não presumidos desnecessários — precedente EL1/EL2 do plano mestre). A64 22%→24%, global 54%→55%. Sem marco de release. G5: gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 40/41 (falha pré-existente). Ver **Resultado** na task |
| ~~Q5~~ | ~~**B9.5**~~ ✅ fechada 2026-08-22 — VFP `VMOV_to_gp`/`VMOV_from_gp` (word), `VMOV_64_sp`, `VCVT_fix_{sp,dp}` | `trilha-b-arquiteturas/b9.5-vfp-vcvt-vmov.md` | arm-jitter | — | Triagem real (QEMU `translate-vfp.c`) refutou o plano mestre: só 5 das 15 células eram VFPv2/v3 genuínas — as outras 10 exigem NEON (byte/halfword de `VMOV_to_gp`/`from_gp`, sem Kind novo, não excluídas na TSV por colisão de nome com a forma word já implementada) ou extensões opcionais posteriores (`VCVT_f16*`/`VCVT_b16_f32`/`VCVT_hp_int`, agora em `docs/isa-nao-aplicavel.tsv`). 2 `Kind`/`IrOp` novos (`VFP_CORE_PAIR_TRANSFER_SINGLE`, `VFP_CONVERT_FIXED`), sem suporte ASM nativo (cai no interpretado). `mvn -o test` verde (core+truffle) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente, confirmada via `git stash` nesta sessão). MPCore 82%→85%, v7-A 83%→85%, global 55%→55% — sem marco de release. Ver **Resultado** na task |
| ~~Q6~~ | ~~**B9.1**~~ ✅ fechada 2026-08-22 — A32 DSP/media (`SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`/`SMMLA{R}`/`SMMLS{R}` + `UDF`) | `trilha-b-arquiteturas/b9.1-a32-dsp-media.md` | arm-jitter | — | Confirmado ARMv6 genuíno (`op_smlad`/`op_smlald`/`op_smmla`, `ENABLE_ARCH_6` real do QEMU) — excluído de `v4T`/`v5TE` na TSV. `Ra=15` é o alias sem acumulador (`SMUAD`/`SMUSD`/`SMMUL`), mesmo encoding. `UDF` reconhecida explicitamente (mesmo `IrOp.Undefined` de `UNIMPLEMENTED`) — achado real: precisou estender o corte de fronteira de bloco do `StandardIrBlockLifter` para `UDF` também, senão 23 testes que usavam `0xE7F0_00F0` como sentinela genérico de "fim do bloco" quebravam (ganhavam um op a mais). 17 encodings conferidos com corpus real (`arm-none-eabi-as`/`objdump`, devkitARM). `mvn -o test` verde (core 1767 + truffle 13) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente não relacionada). MPCore 85%→88%, v7-A 85%→87%, global 55%→56% — sem marco de release. Ver **Resultado** na task |
| ~~Q7~~ | ~~**B9.3**~~ ✅ fechada 2026-08-22 (commit `09aeb5c`, já no `origin`) — T16 ARMv6 genuínos: `SETEND`, `CPS` A/R-profile, `REV`/`REV16`/`REVSH`, `SXTH`/`SXTB`/`UXTH`/`UXTB` | `trilha-b-arquiteturas/b9.3-t16-armv6.md` | arm-jitter | — | ⚠️ Índice desta fila estava desatualizado — a task já tinha `## Resultado` e status ✅ no `INDICE.md` da trilha B, corrigido nesta sessão sem reexecutar nada. Achado principal: plano mestre citava `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB` (com acumulador) para T16, mas a forma real de 16 bits é `SXTH`/`SXTB`/`UXTH`/`UXTB` (sem acumulador, `Rn` fixo em 15 no formato `@extend`). v6K 90%→93%, MPCore 88%→91%, v7-A 87%→88%, v7-M 54%→56%, global 56%→57% — sem marco de release. Ver **Resultado** na task |
| ~~Q8~~ | ~~**B8.4**~~ ✅ fechada 2026-08-23 — A64 FP escalar aritmética (`FNMUL`/`FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`FSQRT`/`FMADD`/`FMSUB`/`FNMADD`/`FNMSUB`) | `trilha-b-arquiteturas/b8.4-a64-fp-escalar-aritmetica.md` | arm-jitter | B8.3 ✅ | `FADD`/`FSUB`/`FMUL`/`FDIV`/`FABS`/`FNEG`/`FMOV` já existiam desde B6.5.2/B6.5.3 — escopo real era só o resto de "2 source" (opcodes 4-8 antes `unsupported`), `FSQRT` ("1 source") e a classe própria "3 source" (nunca decodificada). **2 colisões de decode reais achadas rodando o corpus** (G8): "3 source" e "Advanced SIMD scalar x indexed element/shift by immediate" compartilham `bits[28:24]`, só `bit30` separa; e o MESMO problema já existia ADORMECIDO desde B6.5.3 entre 2/1-source/imediato/compare e "scalar two-register misc"/cripto — corrigido alargando os prefixos para incluir `bit30`, com efeito colateral de corrigir 9 falsos-positivos pré-existentes (`SHA1H`/`SUQADD_s`/`SQDMULL_si`/etc. eram misdecodificados como `Fp64*` em silêncio). `mvn -o test` verde (core+truffle) + `install`. `Fp64MultiplyAdd` fica FORA de `Ir64NativePolicy` de propósito (cai no interpretado, mesmo padrão de B6.8/B6.9/B8.2/B8.3 para `Kind`s novos); os 6 ops novos de `Fp64Alu` (`NMUL`/`SQRT`/`MAX`/`MIN`/`MAXNM`/`MINNM`) JÁ são nativos (kind `FP64_ALU` reconstrói genericamente, sem mudança no compilador). A64 24%→24% (274→275/1135, +1 líquido), global 57%→57% (2287→2288/3948) — sem marco de release. Ver **Resultado** na task |
| ~~Q9~~ | ~~**B8.5**~~ ✅ fechada 2026-08-23 — A64 FP escalar comparação/seleção/conversão (`FCSEL`/`FCCMP(E)`/`FRINTx`/`SCVTF`/`UCVTF`/`FCVTxS`/`FCVTxU`/`FMOV` registrador-geral) | `trilha-b-arquiteturas/b8.5-a64-fp-escalar-comparacao-conversao.md` | arm-jitter | B8.4 ✅ | 5 `Kind`s novos (`Fp64ConditionalSelect`/`Fp64ConditionalCompare`/`Fp64Round`/`Fp64IntegerConvert`/`Fp64GeneralRegisterMove`); nenhuma colisão de decode nova (diferente de B8.4) — os 4 valores de `bits[11:10]` particionam exatamente `FCSEL`/`FCCMP`/2-source/compare-1-source sem ambiguidade. 24 exclusões novas em `isa-nao-aplicavel.tsv` (`FEAT_FP16`/`FEAT_BF16`/`FEAT_FRINTTS`/`FEAT_JSCVT`/`FEAT_FPRCVT`, todas conferidas contra `translate-a64.c` real); `FMOV_xu`/`FMOV_ux`(metade-alta de 128 bits)/`FCVTXN_s`(espaço NEON escalar) ficam de fora por limite de armazenamento/classe, não TSV. `mvn -o test` verde (+75) + `install`; G5 não se aplica (nenhum arquivo 32-bit tocado). Corpus real estendido (`aarch64-none-elf-as`/`objdump`, offsets `0x658`-`0x6fc`). A64 24%→27% (275/1135→308/1111), global 57%→59% (2288/3948→2321/3924). **🔔 MARCO DE RELEASE CRUZADO desde o `1.0.0`** (baseline global 53%/A64 18%; regra do `tasks/README.md`: publicar sempre que global suba ≥5pp — já subiu +6pp): global **59%** e A64 **27%** hoje. **Publicação de `1.1.0` no Maven Central (F5) NÃO feita nesta sessão** — ação externa/irreversível, decisão consciente de deixar para sessão dedicada (com F7 em seguida para os 4 consumidores, mesma janela que a F4 abriu). ✅ **Publicada depois, mesmo dia, por outra sessão** (`F5`, commit `dcb5ca1`, tag `v1.1.0`, confirmada no `repo1.maven.org`) — nova baseline de release passa a ser v7-A 88%/global 59% (ver Q10/B9.7 abaixo). Ver **Resultado** na task |
| ~~Q10~~ | ~~**B9.7**~~ ✅ fechada 2026-08-23 — T32 (Thumb-2), 58 lacunas | `trilha-b-arquiteturas/b9.7-t32-thumb2.md` | arm-jitter | — | `PKH`/DSP-media(10)/`BXJ`/`UDF.W`/`SUBS PC,LR,#imm`/`RFE`/`SRS` implementados (decode-reuse puro, G1); `LDM.W`/`STM.W` já estavam certos — falso negativo do MEDIDOR corrigido (`IsaCoverageReport.FILL_STRATEGIES`, campo `list:16` nunca tinha ≥2 bits nas estratégias antigas). **Bug real corrigido (G8)**: `SMLALD{X}` caía silenciosamente em `SMLAL<x><y>` (mesmos `x`/`y`, instrução ERRADA sem lançar). **⚠️ Achado do usuário em tempo real**: rascunho inicial excluiu `MRS_bank`/`MSR_bank`/`ERET`/`SMC`/`HVC` como "Hyp mode não modelado" — revertido, são instruções REAIS do ARMv7VE/Security Extensions (mesma versão v7-A, não posterior); ficam `❌` como trabalho pendente (+ `LDRxT`/`STRxT`, mesmo critério — 13 células ao todo, candidatas a 2 escadas futuras: Hyp/Monitor mode de 32 bits, e acesso não-privilegiado no MMU). `mvn -o test` verde (core 1901) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: T32 v7-A 80%→95%, v7-A geral 88%→95%. **Sem marco de release**: a `1.1.0` já tinha sido publicada por outra sessão (`F5`, commit `dcb5ca1`, mesmo dia) ANTES desta task começar — a baseline correta é a do `1.1.0` (v7-A 88%/global 59%), não a do `1.0.0`; delta real desta task é v7-A +7pp/global +1pp, abaixo dos dois gatilhos (+10pp arquitetura/+5pp global). Ver **Resultado** na task |
| ~~Q11~~ | ~~**B9.2 / B9.4 / B9.6**~~ ✅ fechada 2026-08-24 — triagem do resto do 32 bits | `trilha-b-arquiteturas/b9.2-b9.4-b9.6-triagem-resto-32bit.md` | arm-jitter | — | **B9.2**: `MOVW`/`MOVT`/`MLS`/`SBFX`/`UBFX`/`BFC`/`BFI`/`RBIT`/`SDIV`/`UDIV` já implementadas em `v7-A` desde B3.1 — confirmado real (`ENABLE_ARCH_6T2`/`aa32_arm_div` do QEMU) que são POSTERIORES a `v6K`/`MPCore`; 9 linhas novas na TSV, zero código (já implementado onde pertence). **B9.4**: quase tudo já implementado (`IT`/`CBZ`/`B`/`BLX_r`); 2 achados reais — `B_cond_thumb` era falso negativo da FERRAMENTA de medição (`cond=AL` force-preenchido caía no encoding reservado de `UDF`, corrigido em `IsaCoverageReport`), `UDF` T16 (`0xDE00`) ganhou `InstructionKind.UDF` explícito (era `UNIMPLEMENTED` genérico, mesmo padrão B9.1/B9.7); `HLT` T16 deixado pendente (depende de semihosting, não implementado no projeto, fora do escopo desta task). **B9.6**: `VFMA`/`VFMS`/`VFNMA`/`VFNMS` NOVAS em `v7-A` (`ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE`, `IrOp.VfpOperation` + `Math.fma` de verdade via `DirectedFpRounding.exactFma` novo, decoder reaproveitando 100% da extração de bits de `VMLA`/`VDIV`, ASM nativo no caminho "cold"); confirmado real (`translate-vfp.c`, "Present in VFPv4 only") que MPCore é cronologicamente ANTERIOR à VFPv4 — 8 linhas na TSV. `mvn -o test` verde (core+truffle) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`SQRT` lendo `vn=-1`, confirmada não relacionada ao código novo). `docs/COBERTURA-ISA.md`: A32 v6K/MPCore 96%→100%; v6K 94%→97%, MPCore 92%→96%, v7-A 95%→97%; global 59%→61% (+2pp). Sem marco de release (abaixo dos dois gatilhos). Ver **Resultado** na task |
| ~~Q12~~ | ~~**B8.6**~~ ✅ fechada 2026-08-24 — AdvSIMD load/store estruturado (`LD1`-`LD4`/`ST1`-`ST4`/`LD1R`-`LD4R`) | `trilha-b-arquiteturas/b8.6-a64-advsimd-load-store-estruturado.md` | arm-jitter | B8.5 ✅ | **Reabriu B6.5.1 D4 com decisão explícita do usuário**: `Aarch64FpRegisters` estendida de 64→128 bits reais (pré-requisito de toda a escada AdvSIMD B8.6-B8.10, não só desta task) — API pré-existente preservada (G3), `element`/`setElement`/`replicateElement`/`setQ` novos. Decoder: `V=1` dentro de `Loads-and-Stores` (antes sempre `unsupported`) ganhou 2 sub-rotas reais (`multiple`/`single structures`, fatos conferidos contra `a64.decode`/`translate-a64.c` reais do QEMU); load/store escalar SIMD&FP continua fora (não pedido). 3 `Ir64Op`/`Kind` novos (53-55), caem no interpretado (G1, sem ASM nativo ainda). Testes com corpus PRÓPRIO via devkitA64 real (30 decode + 13 executor). `mvn -o test` verde (arm-jitter completo) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters` de 32 bits, confirmada não relacionada). Sem marco de release (`docs/COBERTURA-ISA.md` não regenerado nesta sessão). Ver **Resultado** na task. **Próximo da escada: `B8.7` (AdvSIMD inteiro — aritmética/comparação), já com o banco `V` pronto** |
| ~~Q13~~ | ~~**B8.7**~~ ✅ fechada 2026-08-24 — AdvSIMD inteiro (aritmética/comparação) | `trilha-b-arquiteturas/b8.7-a64-advsimd-inteiro-aritmetica-comparacao.md` | arm-jitter | B8.6 ✅ | "Three same"/"three same pairwise"/"three different" (alargando/largo+estreito/estreitando)/"across lanes"/"two-register miscellaneous" + formas escalares D-only (reaproveitam o record vetorial com `esz=3`/`q=false`, mesmo truque de prefixo já usado por B8.4 para separar `decodeFpTwoSource` de "scalar two-register miscellaneous"). 8 `Kind`s novos, todos caem no interpretado (G1). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: A64 27%→38%, global 59%→64%. **🔔 MARCO DE RELEASE CRUZADO** (arquitetura +10pp E global +5pp desde `1.1.0`) — publicação no Maven Central NÃO feita nesta sessão (mesma decisão consciente de B8.5), fica para sessão dedicada (F5 + F7 em seguida). Ver **Resultado** na task. **Próximo da escada: `B8.8` (deslocamento/saturação/estreitamento, ~140), já com a mesma cautela sobre "three different"/`Rm` documentada nas Armadilhas** |
| ~~Q14~~ | ~~**B8.8**~~ ✅ fechada 2026-08-24 — AdvSIMD deslocamento/saturação/estreitamento | `trilha-b-arquiteturas/b8.8-a64-advsimd-deslocamento-saturacao-estreitamento.md` | arm-jitter | B8.7 ✅ | `SQADD`/`UQADD`/`SQSUB`/`UQSUB`/`SSHL`/`USHL`/`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/`SQRSHL`/`UQRSHL`/`SQDMULH`/`SQRDMULH` (reaproveitam "three same"); `SUQADD`/`USQADD` (reaproveitam "two-register misc"); `SQDMULL`/`SQDMLAL`/`SQDMLSL` (reaproveitam "three different"); 4 `Kind`s novos (64-67: `SQXTN`/`SQXTUN`/`UQXTN` narrow unário, e o espaço de encoding PRÓPRIO "shift by immediate" — não-largo/não-estreito, estreitando, alargando). **Achado real de G8/G1**: reaproveitar os records vetoriais de B8.7 para as novas formas escalares de tamanho VARIÁVEL (`SQADD_s` aceita B/H/S/D, ao contrário de `ADD_s`, D-only) quebrava o truque "esz=3/q=false implica escalar" — `sqadd v0.8b` e `sqadd b0` colidiam no mesmo `(q=false,esz=0)`; corrigido com um `boolean scalar` explícito novo em `VectorArithmeticThreeSame`/`VectorArithmeticUnary`/`VectorShiftImmediate`/`VectorShiftNarrowImmediate` (breaking change de construtor, sem impacto público — pacote `ir64` interno) + `finishScalarAwareWrite` no executor (zera TUDO acima do elemento, não só os 64 bits altos). Também corrigido bug latente de B8.7 (esz forçado a `3` mesmo quando os bits reais não eram `11`, mascarando encodings reservados). `SQRDMLAH`/`SQRDMLSH` excluídos (`ARMv8.1`/`FEAT_RDM`, `isa-nao-aplicavel.tsv`); formas indexadas (`SQDMULL_vi`/etc, "vector x indexed element") fora de escopo (classe de encoding distinta, não coberta pelo título "deslocamento/saturação/estreitamento"). Sem toolchain devkitA64 nesta sessão — encodings derivados por fórmula (bit a bit, conferidos contra um encoding-base já validado no repo), mesmo fallback de B10.2/B10.3. `mvn -o test` verde (core+truffle) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: A64 38%→54%, global 64%→69%. **🔔 MARCO DE RELEASE CRUZADO** (A64 +15pp, global +5pp desde `1.1.0`) — publicação NÃO feita nesta sessão (mesma decisão consciente das tasks anteriores). Ver **Resultado** na task. **Próximo da escada: `B8.9` (AdvSIMD FP vetorial, ~120)** |
| ~~Q16~~ | ~~**B8.10**~~ ✅ fechada 2026-08-24 — AdvSIMD permutação/redução/tabela | `trilha-b-arquiteturas/b8.10-a64-advsimd-permutacao-reducao-tabela.md` | arm-jitter | B8.7 ✅ | `EXT`/`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`/`TBL`/`TBX` novos (sub-dispatch inédito no espaço vetorial `bit21=0`, nunca examinado por B8.7-B8.9) + `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` (fallback no slot "across lanes" já existente). Corpus REAL via devkitA64 (disponível nesta sessão, diferente de B8.8/B8.9). `mvn -o test` verde + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente). `docs/COBERTURA-ISA.md`: A64 59%→62%, global 70%→71% — marco isolado abaixo dos gatilhos, mas o marco CUMULATIVO desde `1.1.0` (A64 27%→62%=+35pp, global 59%→71%=+12pp) continua cruzado — release não feito nesta sessão (mesma decisão consciente de B8.5/B8.7/B8.8/B8.9). **Achado**: `DUP`/`INS`/`SMOV`/`UMOV` (AdvSIMD copy) vivem no MESMO prefixo `bit21=0`, discriminados só por `bit15=1` — fora do título desta task, candidata a task própria. Ver **Resultado** na task |
| ~~Q15~~ | ~~**B8.9**~~ ✅ fechada 2026-08-24 — AdvSIMD FP vetorial | `trilha-b-arquiteturas/b8.9-a64-advsimd-fp-vetorial.md` | arm-jitter | B8.7 ✅ | "Three same"/"three same pairwise"/"two-register miscellaneous" de ponto flutuante, só formas simples/dupla (`FADD_v`/`FSUB_v`/`FMUL_v`/`FDIV_v`/`FMAX_v`/`FMIN_v`/`FMAXNM_v`/`FMINNM_v`/`FMULX_v`/`FMLA_v`/`FMLS_v`/`FCM**_v`/`FACG*_v`/`FABD_v`/`FRECPS_v`/`FRSQRTS_v`, `F**P_v` pareado, `FABS_v`/`FNEG_v`/`FSQRT_v`/`FRINTx_v`/`FRECPE_v`/`FRSQRTE_v`/`FCM**0_v`/`SCVTF_vi`/`UCVTF_vi`/`FCVTxS_vi`/`FCVTxU_vi`). 3 `Kind`s novos (68-70), sem campo `scalar` (só vetorial — forma AdvSIMD-escalar desta família, real e distinta de `FADD_s`/etc. já feito em B8.4/B8.5, fica fora, candidata a task própria). **Achado real**: `bits[23:22]` do encoding "three same"/"two-register misc" NÃO é tamanho de elemento puro nas formas FP (só o bit baixo, `sz`, é tamanho; o bit alto, `a`, é mais um discriminador de opcode) — diferente de todo o resto da escada AdvSIMD (B8.7/B8.8), onde os mesmos 2 bits sempre foram `esz` livre. **Achado real 2**: "two-register misc (FP)" vive PARTIDO entre os 2 slots `Rm=00000`(`FABS`/`FNEG`/comparação-contra-zero)/`Rm=00001`(`FSQRT`/`FRINTx`/`FRECPE`/`FRSQRTE`/conversão inteiro↔float) que o inteiro já ocupava (B8.7 "two-register misc"/B8.8 "narrow unário") — sem colisão de opcode, conferido exaustivamente. Reaproveita as tabelas de arredondamento/saturação/`FPMaxNum` de `Ir64FpExecutor` (B8.5, promovidas de `private` para package-private). 15 exclusões novas em `isa-nao-aplicavel.tsv` (`FEAT_FHM`/`FEAT_FCMA`/`FEAT_FRINTTS`/`FEAT_FAMINMAX`/extensão recente de `FSCALE`); meia-precisão (`FEAT_FP16`) deliberadamente SEM entrada na TSV (mesmo mnemônico reaproveitado pela linha "sd" implementada — TSV casa por nome, excluir suprimiria as duas linhas). Sem toolchain devkitA64 nesta sessão — encodings derivados por fórmula. `mvn -o test` verde (core+truffle, 2120 testes) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (pré-existente, mesmo stack trace `VfpRegisters.s` já documentado). `docs/COBERTURA-ISA.md`: A64 54%→59%, global 69%→70% (denominador mudou por causa das 15 exclusões novas). Marco isolado desta sessão abaixo dos gatilhos (+5pp/+1pp), mas o marco CUMULATIVO desde `1.1.0` (A64 27%→59%=+32pp, global 59%→70%=+11pp) continua cruzado — publicação no Maven Central (F5) NÃO feita nesta sessão (mesma decisão consciente de B8.5/B8.7/B8.8). Ver **Resultado** na task. **Próximo da escada: `B8.10` (permutação/redução/tabela: `EXT`/`UZP`/`TRN`/`ZIP`/`TBL`/`*V`, ~40)** |

✅ **`1.1.0` já publicada no Maven Central** (`F5`, commit `dcb5ca1`, tag `v1.1.0`, confirmada em
`repo1.maven.org`) — publicada por outra sessão no mesmo dia, ENTRE o fechamento da B8.5 e o início
da B9.7. Nova baseline de release: v7-A **88%**, global **59%**. A B9.7 mediu contra essa baseline
(não a do `1.0.0`) e ficou abaixo dos dois gatilhos (v7-A +7pp, global +1pp) — **sem marco de
release pendente no momento**.

✅ **F7 rodada 2 fechada (2026-08-23)** — os 5 consumidores (gbaemu/ndsemu/armbox/virtual-arm-box/
n3dsemu) subiram de `1.0.0` para `1.1.0`; `org.ow2.asm` já não estava mais declarado em nenhum
(nada a remover). Aceite reconfirmado com `~/.m2/repository/dev/vitorsilverio` renomeada: gbaemu
240, ndsemu 183, armbox 40/41 (falha pré-existente, não relacionada), virtual-arm-box 87, n3dsemu
199 — todos resolvendo do Central. Commit+push nos 5 consumidores e no arm-jitter (docs). Ver
**Resultado** em `trilha-f-infra/f7-consumidores-central.md`. Janela F4/F7 fechada de novo.

✅ **Q11 (B9.2/B9.4/B9.6, triagem do resto do 32 bits) fechada 2026-08-24** e ✅ **Q12 (B8.6,
AdvSIMD load/store estruturado) fechada 2026-08-24** — ver as duas linhas na tabela acima. B8.6 é
o primeiro degrau da escada AdvSIMD e estendeu o banco `V` para 128 bits reais (decisão do
usuário). ✅ **B8.7 (AdvSIMD inteiro — aritmética/comparação) fechada 2026-08-24** — priorizada pelo
usuário entre 3 opções (B8.7 arm-jitter vs. G6.1 n3dsemu vs. B6.14 F11); ver linha Q13 acima.
✅ **B8.8 (deslocamento/saturação/estreitamento) fechada 2026-08-24** — mesma priorização de 3
opções (B8.8 arm-jitter vs. G6.1 n3dsemu vs. B6.14 F11); ver linha Q14 acima.
✅ **B8.9 (AdvSIMD FP vetorial) fechada 2026-08-24** — ver linha Q15 acima.
✅ **B8.10 (AdvSIMD permutação/redução/tabela) fechada 2026-08-24** — priorizada pelo usuário entre
3 opções (B8.10 arm-jitter vs. G6.1 n3dsemu vs. B6.14 F11); ver linha Q16 acima. Achado novo:
`DUP`/`INS`/`SMOV`/`UMOV` (AdvSIMD copy) ficaram de fora, candidata a task própria.
✅ **B8.11 fechada 2026-08-26** (priorizada pelo usuário entre B8.11/G6.2/G6.3/AdvSIMD-copy) —
`AESE`/`AESD`/`AESMC`/`AESIMC`/`PMULL`/`PMULL2` implementados (achado: o Cortex-A53 do raspi3 TEM a
Crypto Extension base, ao contrário do que o plano mestre presumia); 66 exclusões TSV reais
(PAC/MTE/`CPY*`/dot-matmul/SHA512/SM3/SM4, todas ARMv8.2-9.x confirmadas); `SHA1*`/`SHA256*` ficaram
de fora por orçamento (candidata **B8.11b**, mesma Crypto Extension ARMv8.0). A64 61%→66%, global
71%→72%; marco cumulativo desde `1.1.0` continua cruzado, release não feito nesta sessão (mesma
decisão consciente de B8.7-B8.10). **Achado de bug PRÉ-EXISTENTE** (B8.7+, não desta task):
`decodeAdvancedSimdInteger` confunde "AdvSIMD across lanes" com "three different" genuíno sempre que
`Rm` é um registrador `16`-`31` (heurística de bit4 inválida nesse caso) — documentado com teste de
regressão, não corrigido, candidata a task própria. Ver **Resultado** em
`trilha-b-arquiteturas/b8.11-a64-extensoes-opcionais-triagem.md`. **Candidatas seguintes, não pegas
automaticamente**: `B8.11b` (SHA1/SHA256), o bug de dispatch across-lanes×three-different, `DUP`/
`INS`/`SMOV`/`UMOV` (achado de B8.10), `G6.2`/`G6.3` (n3dsemu).

✅ **E8 fechada 2026-08-26** (`trilha-e-manutencao/e8-advsimd-integer-rm-vs-bit11-dispatch-bug.md`) —
priorizada pelo usuário entre as 4 candidatas acima; corrigiu o bug de dispatch across-lanes×
three-different achado (não corrigido) pela B8.11. Achado mais amplo que o documentado: a colisão
por `Rm` também acontecia com `Rm=0`/`Rm=1` (não só `Rm>=16`). Discriminador real = bit11 do word
(fixo em `0` só em "three different"), corpus real via devkitA64. `mvn -o test` verde + `install`;
G5 completo nos 5 consumidores + armbox ✅. Candidatas restantes, não pegas automaticamente:
`B8.11b`, `DUP`/`INS`/`SMOV`/`UMOV`, `G6.2`/`G6.3`.

✅ **B8.11b fechada 2026-08-26** (`trilha-b-arquiteturas/b8.11b-a64-sha1-sha256.md`, priorizada pelo
usuário entre B8.11b/DUP-INS-SMOV-UMOV/G6.2/G6.3) — `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/
`SHA256H2`/`SHA256SU1`/`SHA1H`/`SHA1SU1`/`SHA256SU0` implementados (resto da Crypto Extension da
B8.11). 2 espaços de encoding novos achados: three-register SHA no prefixo escalar `bit21=0`
(nunca examinado antes); two-register SHA no mesmo `Rm`/`esz`/`U` de `AESE` mas exigindo checagem
própria `scalar && ...` (a checagem `q && ...` de AES nunca dispara para formas escalares, porque
`q` é forçado a `false` ali). Corpus real via devkitA64; valores esperados do executor vieram de
reimplementação Python independente do FIPS PUB 180-4. `mvn -o test` verde + `install`; G5 completo
nos 5 consumidores ✅ (armbox 43/43, falha pré-existente não reproduziu, mesmo achado da B8.11). A64
66%→66%, global 72%→72% (medidor conta células de decode-tree, não mnemônicos) — sem marco de
release isolado, mas o cumulativo desde `1.1.0` continua cruzado (release não feito nesta sessão).
Ver **Resultado** na task. **Candidatas restantes, não pegas automaticamente**: `DUP`/`INS`/`SMOV`/
`UMOV` (achado de B8.10), `G6.2`/`G6.3` (n3dsemu).

✅ **B8.12 fechada 2026-08-26** (`trilha-b-arquiteturas/b8.12-a64-advsimd-copy.md`, priorizada pelo
usuário entre B8.12/G6.2/G6.3) — `DUP`(elemento/geral)/`INS`(geral/elemento)/`SMOV`/`UMOV`
implementados. Achado real: o discriminador desta família dentro do sub-dispatch da B8.10 é
`bit10` (não `bit15`, como a nota "Não inclui" da B8.10 sugeria) — as 5 instruções têm `bit10=1`
fixo, oposto de `EXT`/permute/`TBL`/`TBX`, então o gate `if(bit10) return null` do topo já
descartava a família inteira antes de chegar ao `if(bit15)`; método novo `decodeAdvancedSimdCopy`
despachado a partir desse gate. `mvn -o test` verde (2260) + `install`; G5 completo nos 5
consumidores ✅ (armbox 43/43, falha pré-existente não reproduziu). A64 66%→68%, global 72%→73%;
marco cumulativo desde `1.1.0` continua cruzado (release não feito nesta sessão). **Candidatas
restantes, não pegas automaticamente**: `G6.2`/`G6.3` (n3dsemu).

✅ **G6.1 fechada 2026-08-24** (`arm-jitter/tasks/trilha-g-3ds/g6.1-exemplos-restantes.md`, escrita
e executada na mesma sessão, priorizada pelo usuário entre B8.11/G6.1/B6.14/AdvSIMD-copy) — os 6
exemplos `graphics/gpu` que ainda não desenhavam (`composite_scene`/`fragment_light`/`lenny`/
`textured_cube`/`cubemap`/`gpusprites`) têm causa real diagnosticada, sem correção aplicada (não são
"pequenas e óbvias"): 3 morrem por `fs:USER` sem RomFS (`OpenArchive`/`OpenFileDirectly` não
implementados → `svcBreak(PANIC)` do guest), os outros 3 por `VertexShaderInterpreter` sem `CMP`
(opcodes `0x2E`-`0x2F`) nem `MAD` (`0x30`-`0x3F`, instrução multiply-add fundamental do PICA200).
Só documentação tocada (`tasks/`), nenhum código de produção — G5-invariante não se aplica. Ver
**Resultado** na task. **Candidatas novas, não pegas automaticamente**: `G6.2` (RomFS mínimo em
`fs:USER`) e `G6.3` (`VertexShaderInterpreter`: `CMP`+`MAD`).

✅ **G6.2 fechada 2026-08-26** (`trilha-g-3ds/g6.2-fs-user-romfs-self-mount.md`, priorizada pelo
usuário entre release Maven Central+F7/G6.2/G6.3) — `fs:USER OpenArchive`/`OpenFileDirectly` +
`FSFILE::Read`/`GetSize`/`Close` implementados no n3dsemu. Achado real que simplificou a task: para
um `.3dsx`, `romfsInit()` é `romfsMountSelf`, que abre o PRÓPRIO arquivo via `ARCHIVE_SDMC` e lê nele
em offsets absolutos — o parsing da estrutura RomFS é feito inteiramente pelo GUEST, sem precisar de
parser de RomFS em Java (bastou servir os bytes brutos do `.3dsx` já carregado via uma sessão de
arquivo sintética por abertura). `mvn -o test` verde no n3dsemu (206, +7); G5 não se aplica (nenhum
arquivo do arm-jitter tocado). `cubemap`/`gpusprites` leem seus `.t3x` reais do RomFS embutido e não
panicam mais (ainda 0 desenhos no `--report`, não investigado além disso); `composite_scene` não
panica mais por `fs:USER`, mas revela causa NOVA e não relacionada em `APT:U` (`0x0044`/`0x000B`/
`0x0102`), candidata a task própria. Ver **Resultado** na task. **Candidatas restantes, não pegas
automaticamente**: `G6.3` (n3dsemu, `VertexShaderInterpreter` `CMP`+`MAD`), o `APT:U` achado acima.
~~A publicação pendente do release Maven Central~~ — **ver nota logo abaixo: já havia sido feita
(`1.2.0`, 2026-08-26) por sessão não refletida aqui, e uma `1.3.0` nova foi publicada em seguida.**

✅ **`1.2.0` publicada no Maven Central em 2026-08-26** (sessão sem registro nesta fila até agora —
achado só na sessão da `1.3.0` abaixo) — inclui todo o trabalho de cobertura B8.6-B8.10 (AdvSIMD:
load/store estruturado, inteiro, deslocamento/saturação, FP vetorial, permutação/redução/tabela) +
`Gdb64Server` (stub GDB para AArch64) + fix de `VSQRT` interpretado (`vn=-1`). `armbox` já estava em
`1.2.0`; `gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu` ficaram para trás em `1.1.0` (F7 incompleta).

✅ **`1.3.0` publicada no Maven Central em 2026-08-27** (a pedido explícito do usuário, mesmo com o
delta desde `1.2.0` abaixo dos dois gatilhos normais de release — A64 61%→68%=+7pp, global
71%→73%=+2pp) — `B8.11`/`B8.11b` (Crypto Extension: `AES*`/`PMULL*`/`SHA1*`/`SHA256*`), `B8.12`
(AdvSIMD copy: `DUP`/`INS`/`SMOV`/`UMOV`), `E7` (JIT A64: exceções de guest escapando pro host) e
`E8` (bug de dispatch `decodeAdvancedSimdInteger`/`Rm`). Publicado via CI (`release.yml`, tag
`v1.3.0`, commit `eaa979f`) — a publicação manual (`mvn -Prelease clean deploy`) tinha travado no
`maven-gpg-plugin` pedindo pinentry interativo (sem passphrase salva em `settings.xml`, decisão do
usuário). O workflow do GitHub Actions terminou ✅, mas a sincronização com `repo1.maven.org` ficou
MUITO mais lenta que o normal (>1h30, portal mostrando `PUBLISHING` — usuário mencionou que a conta
está perto do limite mensal de releases do Central, possível causa). **F7 (subir os 5 consumidores
para `1.3.0`) foi ADIADA a pedido do usuário** — não pegar automaticamente ainda; retomar quando o
usuário confirmar que `1.3.0` sincronizou (checar
`https://repo1.maven.org/maven2/dev/vitorsilverio/arm-jitter/1.3.0/arm-jitter-1.3.0.pom`). Estado
dos consumidores nesta pausa: `armbox` em `1.2.0`; `gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu`
ainda em `1.1.0`. **Considerar, antes de publicar a próxima versão**: mirror de artefatos (jar/pom
direto) e GitHub Pages como réplica do Central foram avaliados e EXPLICITAMENTE RECUSADOS pelo
usuário (2026-08-27) — nada de mirror, só o Central mesmo, mesmo com o limite mensal apertado.

✅ **G6.3 fechada 2026-08-27** (`trilha-g-3ds/g6.3-vertex-shader-cmp-mad.md`, escrita e executada na
mesma sessão, priorizada pelo usuário entre G6.3/investigar-APT:U/checar-sync-1.3.0) — `CMP`
(formato 1c) e `MAD`/`MADI` (formato 5/5i) implementados no `VertexShaderInterpreter` do n3dsemu.
Layout de bits validado contra o código-fonte real do nihstro (`shader_bytecode.h`, via `curl` — o
`WebFetch` sobre o wiki do 3dbrew deu 403, e sobre o próprio `shader_bytecode.h` resumiu em vez de
citar os `BitField`s). Achado real de decode: os bits "ignorados" na identificação de
`CMP`/`MAD`/`MADI` (LSB/3-bits-baixos) são dados reais (`cmp.x`/parte de `dest`) — resolvido
despachando por FAIXA de opcode antes do `switch` do formato 1. 3 testes sintéticos novos
(`ShaderBinary`s montados à mão, sem `.shbin` real disponível que use essas instruções). `mvn -o
test` verde no n3dsemu (209, +3); G5 não se aplica (nenhum arquivo do arm-jitter tocado). **Efeito
real**: `textured_cube` destravado por completo (`desenhos=605 vertices=21780`, antes 0);
`fragment_light`/`lenny` não morrem mais no `CMP`, mas revelam bloqueio NOVO em `JMPC` (`0x2C`,
controle de fluxo condicional) — candidata própria, não pega automaticamente. Ver **Resultado** na
task. **Candidatas restantes, não pegas automaticamente**: controle de fluxo do vertex shader
(`JMPC`/`JMPU`/`IFC`/`IFU`/`CALL*`/`LOOP`/`BREAK*`), o achado de `APT:U` da G6.2, e retomar a F7
quando `1.3.0` sincronizar no Maven Central.

✅ **G6.4 fechada 2026-08-27** (`trilha-g-3ds/g6.4-vertex-shader-controle-de-fluxo.md`, escrita e
executada na mesma sessão, priorizada pelo usuário entre G6.4/investigar-APT:U/checar-sync-1.3.0) —
controle de fluxo completo do `VertexShaderInterpreter` (`JMPC`/`JMPU`/`IFC`/`IFU`/`CALL`/`CALLC`/
`CALLU`/`LOOP`/`BREAK`/`BREAKC`). Layout de bits do nihstro + semântica de 3 pilhas (`IF`/`CALL`/
`LOOP`) transcrita fielmente do interpretador de referência real do Citra (`shader_interpreter.cpp`,
fork `lime3ds/lime3ds` via `curl` — o `citra-emu/citra` original não existe mais nesse caminho).
`fragment_light` (597 desenhos, 21492 vértices) e `lenny` (568 desenhos, 1899960 vértices)
destravados por completo; sem regressão em `textured_cube`/`simple_tri`. `mvn -o test` verde no
n3dsemu (215, +6); G5-invariante não se aplica (nenhum arquivo do arm-jitter tocado). Não validado
visualmente (RFC D4). Ver **Resultado** na task.

✅ **G6.5 fechada 2026-08-27** (`trilha-g-3ds/g6.5-apt-inquire-checknew3ds-sharedfont.md`, escrita e
executada na mesma sessão, priorizada pelo usuário para investigar o achado de `APT:U` da G6.2) —
`InquireNotification`/`CheckNew3DS` triviais; `GetSharedFont` (a causa REAL do `svcBreak(PANIC)`
em `composite_scene` — `C2D_TextParse`/citro2d exige fonte mapeada) implementado com
`SharedFontGenerator` novo, gerando um BCFNT/CFNU real via `java.awt.Font` (Noto Sans, SIL OFL
1.1, embutida em `src/main/resources/fonts/`). **Achado que revogou parte da decisão da G4.1**: o
toolset `JayFoxRox/3ds-font` (clonado nesta sessão) mostrou ser um stub Python2/pycairo
não-funcional (nunca serializou BCFNT de verdade) — substituído por um serializador próprio
escrito contra a especificação pública do 3dbrew. `composite_scene` não panica mais e desenha de
verdade pela 1ª vez (`desenhos=834 vertices=105084`). `mvn -o test` verde no n3dsemu (219, +4);
G5-invariante não se aplica (nenhum arquivo do arm-jitter tocado). Ver **Resultado** na task.
**Candidatas restantes, não pegas automaticamente**: `ptm:sysm` (sessão sem serviço registrado,
achado colateral da G6.5, não bloqueador), e retomar a F7 quando `1.3.0` sincronizar no Maven
Central (checado nesta sessão: ainda 404 em `repo1.maven.org`).

✅ **G6.6 já estava fechada 2026-08-27** (`trilha-g-3ds/g6.6-ptm-sysm.md`, priorizada pelo usuário
nesta sessão entre G6.6/B9.8-spec/LDRxT-STRxT-spec — mas ao abrir a task, `PtmSysmService`/
`PtmSysmServiceTest` já existiam commitados e pushados (`d2bf926`) e o `INDICE.md` da trilha G já
mostrava ✅; esta `FILA-EXECUCAO.md` só estava desatualizada, mesmo padrão de dessincronia já visto
com a `1.2.0`). Nada reexecutado; só esta entrada de bookkeeping foi escrita/pushada. **Candidatas
restantes, não pegas automaticamente**: `B9.8` (Hyp/Monitor mode 32-bit, precisa spec), `LDRxT`/
`STRxT` (precisa spec), retomar F7 quando `1.3.0` sincronizar (ainda 404 em 2026-08-27), `B10.6b`/
`B10.6c` (bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos, sem consumidor real).

✅ **B9.8.1 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.1-hyp-monitor-estado-generalizado.md`,
priorizada pelo usuário entre B9.8/LDRxT-STRxT — escolheu B9.8) — plano mestre novo
`b9.8-plano-hyp-monitor-32bit.md` escrito nesta sessão (mesmo padrão do `b10-plano-el2-el3.md`,
fatos conferidos via QEMU real: `target/arm/tcg/{translate.c,a32.decode}`, `curl` direto). B9.8.1 é
a fundação (fecha o achado 6 da B9.7): `CpuMode.HYP`/`CpuMode.MONITOR` novos + banking real em
`ArmCore` — `SP_hyp` banco próprio, `LR` em Hyp mode é o `LR_usr`/`LR_sys` COMPARTILHADO (achado
real do QEMU: Hyp mode não banca LR), `ELR_hyp` registrador à parte fora de R0-R15, `SP_mon`/
`LR_mon`/`SPSR_mon` banco normal. Corrige bug latente (`CpuMode.fromBits` lançava para esses 2 bits
de modo). Puramente aditivo, sem decode de instrução nova ainda. `mvn -o test` verde (core +
suíte completa) + `install`; G5 completo nos 5 consumidores ✅ (zero-diff, nenhum usa Hyp/Monitor
ainda). Ver **Resultado** na task. **Próximas da escada B9.8, qualquer ordem**: B9.8.2 (`HVC`),
B9.8.3 (`SMC`), B9.8.4 (`ERET` A32), B9.8.5 (`MRS_bank`/`MSR_bank`).

✅ **B9.8.2 (`HVC` real) e B9.8.3 (`SMC` real) fechadas 2026-08-27** — sessão sem registro nesta fila
até agora (mesmo padrão de dessincronia já visto com `1.2.0`/G6.6), achado só na sessão da B9.8.4
abaixo. Ver **Resultado** em `b9.8.2-hvc-real.md`/`b9.8.3-smc-real.md`.

✅ **B9.8.4 (`ERET` real A32) fechada 2026-08-27** (task spec escrita e executada na mesma sessão,
só existia como linha no plano mestre) — `ArmFeature.VIRTUALIZATION_EXTENSIONS` novo, sem preset
habilitando ainda (nenhum consumidor modela V7VE hoje, aditivo puro); `UNDEFINED` em `USER`; em
qualquer outro modo, `PC`=`ELR_hyp` (Hyp mode) ou `LR` do banco ativo, `CPSR`=SPSR do modo ativo —
instrução de RETORNO pura (`IrOp.Eret`), SEM `ArmException` nova (diferente de `HVC`/`SMC`, mesma
categoria de `RFE`). Confirmado que o caminho genérico do ALU (`SUBS PC,LR` e o alias T32 de B9.7)
continua lendo `LR` mesmo em Hyp mode — simplificação deliberada já documentada, não alterada por
esta task. `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5 consumidores ✅. Sem
marco de release. Ver **Resultado** em `b9.8.4-eret-real.md`. **Falta só `B9.8.5`
(`MRS_bank`/`MSR_bank`) para fechar a escada B9.8 por completo.**

**Trabalho real pendente aberto pela B9.7 (13 células T32, NÃO excluídas — ver a task)**: (1) Hyp
mode + Monitor mode de 32 bits, para `MRS_bank`/`MSR_bank`/`ERET`/`SMC`/`HVC` — épico comparável à
escada EL2/EL3 do AArch64 (B10), candidato a `B9.8`/spec própria; (2) acesso de memória
não-privilegiado (`LDRxT`/`STRxT`, 8 instruções) — precisa de `IrOp` novo + parâmetro de privilégio
efetivo na tradução MMU, candidato a task própria. Nenhum dos dois pego automaticamente — são
épicos, não sessões.

✅ **B9.8.5 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.5-mrs-msr-bank.md`, spec escrita e
executada na mesma sessão — próximo item natural da escada B9.8 já aberta, sem precisar de nova
priorização do usuário) — `MRS`/`MSR` bancado (A32 e T32): `BankedRegisterSysm` novo resolve `(r,
sysm)` em `(modo,registro|ELR_hyp|SPSR)`, reaproveita `bankedRegister`/`setBankedRegister`/`spsr`/
`setSpsr`/`elrHyp` de B9.8.1; `UNDEFINED` em modo `USER`. Achado real: o dispatch T32 de `MRS` em
`Thumb2MiscDecoder` usava igualdade exata (só reconhecia a forma registrador) — generalizado para
máscara, mesmo padrão que `MSR` já usava. Encodings reais via devkitARM antes de codificar. `mvn -o
test` verde + `install`; G5 completo nos 5 consumidores ✅ (armbox 43/43 desta vez, a falha
pré-existente não reproduziu). Sem marco de release. **Fecha a escada B9.8 (Hyp/Monitor 32-bit) por
completo — B9.8.1 até B9.8.5.** Ver **Resultado** na task. Candidatas restantes, não pegas
automaticamente: `LDRxT`/`STRxT` (precisa spec), `B10.6b`/`B10.6c` (bloqueadas), retomar F7 quando
`1.3.0` sincronizar no Maven Central.

✅ **B9.9 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.9-ldrxt-strxt-unprivileged-access.md`, spec
escrita e executada na mesma sessão, priorizada pelo usuário entre B9.9/B10.6b-B10.6c — `1.3.0`
confirmada sincronizada no Maven Central `repo1.maven.org` durante a mesma checagem, então F7 também
ficou desbloqueada, mas não foi executada nesta sessão) — `LDRxT`/`STRxT` (acesso "unprivileged", 8
formas A32+T32) reais: `AddressSpace#withUnprivilegedAccess` novo (`TranslatingAddressSpace` alterna
`privileged=false` no escopo, `try/finally`, mesmo padrão de
`TranslatingAddressSpace64#translateForAddressTranslate`/B10.6); `DecodedInstruction`/`IrOp.Load`/
`Store` ganham o campo `unprivileged`; A32 usa o mesmo bit `W` que já existia (`!preIndexed&&writeback`,
sem bit novo), T32 usa o ramo `p&&u` do `decodeT4` que antes devolvia `null`; `AsmNativePolicy` cai
no interpretado quando `unprivileged()`. Teste ponta-a-ponta novo (`LdrxtStrxtPrivilegeTest`) prova
que um `STRT` em modo privilegiado aborta como `USER` numa página `AP_USER_READ_ONLY`, sob `step()`
E sob bloco JIT compilado. `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5
consumidores ✅. Sem marco de release (mudança pequena). **Fecha as 13 células que a B9.7 deixou `❌`
de propósito por completo** (5 pela escada B9.8, 8 por esta task). Ver **Resultado** na task.
**Candidatas restantes, não pegas automaticamente**: `F7` (subir os 4 consumidores para `1.3.0`,
agora desbloqueada — `armbox` já em `1.2.0`, os outros 4 em `1.1.0`), `B10.6b`/`B10.6c`
(bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos, sem consumidor real).

✅ **F7 fechada 2026-08-27** (`trilha-f-infra/f7-consumidores-central.md`, "Rodada 3" — única
candidata não bloqueada da lista acima, sem precisar de nova priorização do usuário) — `1.3.0`
confirmada resolvível no Central antes de começar; os 5 consumidores (`gbaemu`/`ndsemu`/`armbox`/
`virtual-arm-box`/`n3dsemu`) bump para `1.3.0` (armbox vinha de `1.2.0`, os outros 4 de `1.1.0`) +
docs corrigidas. Aceite com `~/.m2/repository/dev/vitorsilverio` renomeada: gbaemu 240, ndsemu 183,
virtual-arm-box 87, n3dsemu 221, armbox 43/43 (falha pré-existente não reproduziu) — todos BUILD
SUCCESS resolvendo do Central; `org.ow2.asm:asm:9.7.1` confirmado transitivo. Commit por repo +
push. Ver **Resultado** na task.

✅ **B10.6b/B10.6c fechadas 2026-08-27** (`trilha-b-arquiteturas/b10.6b-b10.6c-at-el2-el3-stage1.md`,
task spec escrita e executada na mesma sessão — única candidata não bloqueada da lista acima, sem
precisar de nova priorização do usuário) — `AT S1E2R`/`S1E2W`/`S1E3R`/`S1E3W` (stage-1 pura dos
regimes EL2/EL3): `TTBR0_EL2`/`TTBR0_EL3`/`TCR_EL3` novos + `Aarch64PrivilegedStage1TranslatingAddressSpace64`
novo. **Fecha a escada B10 (EL2/EL3) por completo** — sem pendência conhecida na trilha. `mvn -o
test` verde (core 2352) + `install`; G5 nos 5 repos ✅ (armbox 43/43). `docs/COBERTURA-ISA.md`
regenerado e descartado nesta sessão (regressão não relacionada em 5 células A32/T32, não
investigada — ver a task). Ver **Resultado**/histórico completo em `tasks/FILA-HISTORICO.md`.
**Fila automática vazia de novo** — próxima priorização cabe ao usuário (Onda 5 restante, Q5+,
investigar o desvio de `docs/COBERTURA-ISA.md`, ou outra prioridade).

✅ **E9 fechada 2026-08-27** (`trilha-e-manutencao/e9-cobertura-isa-mrs-bank-eret-regressao.md`,
priorizada pelo usuário entre "investigar COBERTURA-ISA" / n3dsemu / aarch64-virt64) — a
"regressão" de `MRS_bank`/`MSR_bank`/`ERET`/`HVC`/`SMC` (A32) achada pela B10.6b/B10.6c NÃO era
regressão nem instabilidade do medidor: era um falso positivo antigo (G8, misdecode silencioso —
`a32.decode` tem `simd=false`, então o medidor nunca rebaixava esses encodings pra `FALLBACK`)
corrigido de verdade por `B9.8.2`-`B9.8.5`, que deram decode dedicado + gate real por `ArmFeature`
a essas 5 instruções — nenhum preset declara `VIRTUALIZATION_EXTENSIONS` ainda (decisão explícita
da B9.8.4, "aditivo puro"), então `❌` é o valor CORRETO hoje. `docs/COBERTURA-ISA.md` regenerado
e COMMITADO (a sessão B10.6b/c tinha descartado): global 73%→73% (2774→2771/3777, sem gatilho de
release — T32 subiu mais do que A32 "caiu"). Nenhum código de produção tocado, G5 não se aplica.
Ver **Resultado** na task. **Candidata registrada, não pega automaticamente**: preset novo com
Virtualization Extensions (Hyp/Monitor 32-bit real) para essas 5 células ficarem `✅` de verdade —
sem consumidor real pedindo isso hoje. **Fila automática vazia de novo.**

✅ **Levantamento geral 2026-08-27** (a pedido do usuário: "verificar o que tem de tarefas em
aberto nas várias frentes") — cruzado `FILA-EXECUCAO.md` com o `INDICE.md` de cada trilha
(vários estavam desatualizados, mesmo padrão de dessincronia já visto). Resultado completo
entregue ao usuário no chat; resumo: só **G7** estava livre de bloqueio real, o resto é
🧑 bloqueado (C7/C9, C10, B4.0.3 item 3, B6.2 aceite #2, B6.6.6, A9 PR2, G6 comercial) ou
"exige modelo forte" (D6, dispatch megamórfico ndsemu, idle-loop skip, JUS ASM×interp,
WiFi fases 3-5, Platinum INTERPRETED, Platinum billboard/VRAM). Usuário escolheu G7.

🔴 **G7 refinada 2026-08-27** (`trilha-g-3ds/g7-backport-vulkan-ndsemu.md`) — a pergunta gating
do próprio `[REFINAR]` ("vale a pena mover a rasterização pro Vulkan?") foi respondida por
MEDIÇÃO real (instrumentação temporária revertida, `mvn exec:java ... bench asm` em MKDS e
SM64DS): a rasterização 3D já roda numa thread própria overlapada com a CPU do próximo quadro
(`RenderPipeline`, ping-pong de 2 slots) e o tempo de render (10,5-11,3ms) fica folgadamente
abaixo do tempo de CPU por quadro (13,5-21,3ms nos dois jogos) — CPU é o teto, não a
rasterização, confirmando por medição direta o que `ndsemu-perf-plan` já suspeitava por outro
caminho. **G7 não avança como proposta de ganho de fps** — decisão registrada na task, não é
"nunca fazer" (só sem motivo hoje). Nenhum código de produção alterado (instrumentação
revertida via `git checkout`, nada commitado no ndsemu). Ver **Resultado** na task.
**Fila automática vazia de novo** — restam só os itens bloqueados/modelo-forte do levantamento
acima; próxima priorização cabe ao usuário.

✅ **Ambiente `arm-linux-*`/`aarch64-linux-*` resolvido 2026-08-27** (usuário perguntou quais
toolchains faltavam para B4.0.3/B6.2/B6.6.6, decidiu resolver na hora) — **WSL2 + Ubuntu 26.04**
(já instalado pelo
usuário) + `apt install gcc-arm-linux-gnueabihf gcc-aarch64-linux-gnu` + cross-toolchains `musl`
do `musl.cc` (`arm-linux-musleabihf-cross`/`aarch64-linux-musl-cross`, baixados e RODANDO de
verdade dentro do WSL — o bloqueio antigo "ELF Linux não roda em MSYS2" nunca foi sobre o
toolchain não existir, só sobre faltar um Linux de verdade para rodá-lo). `busybox-1.36.1`
buildado estático para os dois alvos (musl, não glibc — glibc estático usa IFUNC para
`memcpy`/`strcmp`, e nenhum loader do armbox processa `R_ARM_IRELATIVE`, confirmado crashando
antes de trocar para musl) com `-no-pie -static` (achado: musl por padrão gera `static-pie`/
`ET_DYN`, que o `Elf32Loader` recusa de propósito). ✅ **B4.0.3 fechada por completo** (item 3,
Thumb-2, ver linha própria abaixo) — **destrava B4.0.5**. 🔶 **B6.2 aceite #2 (aarch64) NÃO
fechada, mas o bloqueio mudou de natureza**: o binário aarch64 carrega e começa a rodar, mas o
`Aarch64Decoder` não tem a família `LDR`/`STR` SIMD&FP registrador-imediato (`STR Q0,[x0]`, ARM
DDI 0487 C4.1.5 — diferente da AdvSIMD estruturada de B8.6 e do load/store escalar de B6.2/B8.1),
que musl usa em `memcpy`/`memset`. Não é mais bloqueio de ambiente — vira candidata de decode A64
pura, mesma categoria de B8.x. `busybox-thumb2`/`busybox-aarch64` ficam versionados em
`armbox/testdata/` para reuso. Ver **Resultado** em `b4.0.3-armbox-validar-thumb2-completo.md` e
a atualização 2026-08-27 em `b6-aarch64.md`.

✅ **B4.0.3 fechada 2026-08-27** — ver acima. `Thumb2BusyboxTest` novo (4 testes, INTERPRETED+JIT,
`echo`/`sh -c` sequencial+aritmética) + 5 syscalls novas no `LinuxGuest` do armbox (`mprotect`,
`set_robust_list`, `getrandom`, `clock_gettime64`, `rseq`/`statx` como `-ENOSYS` explícito — o
musl trata como "kernel antigo" e cai pro caminho alternativo sozinho). `mvn -o test` verde no
armbox (47, +4); G5 não se aplica (só `armbox/linux/` tocado, nada do arm-jitter). **Destrava
B4.0.5** (fase 3: fork/pipes, usa este busybox como corpus) — ainda não pega automaticamente,
"1 sessão = 1 task".

## 🔒 Congelamento de subprojetos (decisão do usuário, 2026-08-27)

**Nenhuma task de armbox/gbaemu/ndsemu/virtual-arm-box/n3dsemu deve ser pega enquanto o arm-jitter
não cobrir ~100% de instruções/perfis/features/modos ARM** — ver `tasks/README.md` e a memória do
agente `feedback-100-cobertura-antes-subprojetos`. Só cobertura de ISA no arm-jitter é elegível.

✅ **B8.13 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.13-a64-fp-simd-load-store-escalar.md`,
achado tentando rodar o `busybox-aarch64` da sessão anterior) — `LDR`/`STR`/`LDP`/`STP`/
`LDR (literal)` SIMD&FP escalar (`B`/`H`/`S`/`D`/`Q`), gap que `decodeLoadsAndStores` recusava por
completo fora da família estruturada da B8.6. `Ir64FpMemSize` novo (com `QUAD`, que o enum de
GPR não tem), 4 `Ir64Op`s novos, decode+executor completos, corpus real via devkitA64
(`Aarch64FpLoadStoreDecoderTest` 32 + `Ir64BlockExecutorB813Test` 10). `mvn -o test` verde
(2394+42) + `install`; G5: gbaemu 240 ✅, ndsemu 183 ✅, armbox 47 ✅. `docs/COBERTURA-ISA.md`:
A64 68%→73%, global 73%→74% — abaixo dos 2 gatilhos de release. **Achado colateral**: com o gap
fechado, `busybox-aarch64` avança bem mais fundo no boot e revela um gap NOVO — `MRS`/`MSR` de um
registrador de sistema não reconhecido (`0xd51bd040`). **NÃO investigado nesta sessão** (decisão
consciente: reagir corrida-atrás-do-crash é exatamente o padrão que motivou o congelamento — a
próxima sessão ataca cobertura de registradores de sistema A64 de forma sistemática via
`docs/COBERTURA-ISA.md`, não "roda o binário de novo"). Ver **Resultado** na task.

✅ **B8.14 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.14-a64-tpidr-el0.md`, usuário escolheu
"registradores de sistema A64 primeiro" entre 3 opções de sequenciamento pros 100%) — o gap que a
B8.13 revelou (`MSR TPIDR_EL0, x0`) era `TPIDR_EL0`/`TPIDRRO_EL0` (ponteiro de TLS de EL0) — só
`TPIDR_EL1` (kernel) existia desde B6.6.7; sem `TPIDR_EL0` NENHUM binário aarch64 real com libc
roda (todo `crt0` grava isso antes de `main`). 2 escaninhos novos no `Aarch64Core`, mesmo padrão
intrínseco já usado por `TPIDR_EL1`. `Aarch64ThreadPointerEl0Test` (6, corpus real via devkitA64).
`mvn -o test` verde + `install`; G5: gbaemu 240 ✅, ndsemu 183 ✅, armbox 47 ✅. **Achado
colateral**: com o registrador funcionando, `busybox-aarch64` avança de novo e bate um
`Aarch64GuestSegmentationFault` por bloco TLS nunca alocado — isso é trabalho do ARMBOX (alocar
memória de guest pro TLS no loader), fora do congelamento de subprojetos, não pego aqui. Ver
**Resultado** na task.

✅ **B8.15 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.15-a64-fpcr-fpsr.md`) — continuação da
varredura sistemática de registradores de sistema A64; diferente de B8.13/B8.14, não foi achado
reativo — `FPCR`/`FPSR` já estavam documentados como pendência EXPLÍCITA desde B6.5.1/B6.6.1 (D3).
Armazenamento puro (mesmo padrão intrínseco de `TPIDR_EL0`); arredondamento/flags de exceção
continuam sem efeito real (fixo em round-to-nearest-even), decisão explícita registrada, não
"nunca fazer". `Aarch64FpControlStatusRegisterTest` (5, corpus real). `mvn -o test` verde +
`install`; G5: gbaemu 240 ✅, ndsemu 183 ✅, armbox 47 ✅. Confirmado que `busybox-aarch64` ainda
para no mesmo segfault de TLS (achado da B8.14, trabalho do armbox) — `FPCR`/`FPSR` não eram o
próximo bloqueio real, mas fecham a pendência de qualquer forma (varredura sistemática, não só
reativa). Ver **Resultado** na task.

✅ **B8.16 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.16-a64-nzcv-daif-cntv.md`) — `NZCV`/
`DAIF` via `MRS`/`MSR` + `CNTVCT_EL0`/timer virtual. **Achado de design real**: `NZCV`/`DAIF` NÃO
podiam ser escaninhos novos como `TPIDR_EL0`/`FPCR`/`FPSR` — são uma segunda via de acesso ao
MESMO estado que `B.cond`/`enterIrq` já consultam; `PstateRegister` ganhou métodos de
codificação/decodificação que leem/escrevem o `nzcv`/`irqDisabled` reais (um escaninho paralelo
teria sido um bug de verdade: `MSR NZCV` sem efeito na próxima `B.cond`). `CNTVCT_EL0`/`CNTV_*`
reaproveitam o `Aarch64SystemRegisterBus` pluggable já existente. `Aarch64PstateSystemRegisterTest`
(7, corpus real via devkitA64). `mvn -o test` verde + `install`; G5: gbaemu 240 ✅, ndsemu 183 ✅,
armbox 47 ✅. Ver **Resultado** na task. **Candidatas registradas para a próxima sessão desta
frente**: `SPSel`/`UAO`/`PAN`/`DIT`/`SSBS`/`TCO`/`ALLINT` via `MRS`/`MSR (register)` (já existem
como `MSR (immediate)`, B8.3), e o resto do espaço de registradores de sistema A64.

✅ **B8.17 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.17-a64-pstate-fields-mrs-msr.md`,
usuário escolheu fechar mesmo com baixo impacto prático esperado — "100% é 100%") — `SPSel`/`PAN`/
`UAO`/`DIT`/`SSBS`/`TCO`/`ALLINT` via `MRS`/`MSR (register)`, completando o que `B8.3` já tinha
feito pela via `MSR (immediate)`. 7 escaninhos de armazenamento puro (mesma disciplina de
`FPCR`/`FPSR`, B8.15 — nenhum consumidor real modelado, confirmado já pela B8.3).
`Aarch64PstateFieldRegistersTest` (8, corpus real via `aarch64-none-elf-as -march=armv8.5-a`).
`mvn -o test` verde + `install`; G5: gbaemu 240 ✅, ndsemu 183 ✅, armbox 47 ✅. **Fecha a rodada
de varredura sistemática de registradores de sistema A64** (B8.13-B8.17) desta sessão — próxima
frente cabe ao usuário decidir (v4T/v5TE, ou NEON/MVE/SVE/SME). Ver **Resultado** na task.

✅ **B8.18 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.18-a64-advsimd-logico-two-register-misc-restante.md`,
usuário pediu a lacuna `LDR`/`STR` SIMD&FP reg-imediato — já fechada pela `B8.13`; escolheu então
"varredura sistemática A64 restante" entre as opções oferecidas) — triagem de `docs/COBERTURA-ISA.md`
(274 lacunas A64) achou 2 clusters pequenos e limpos, deixados de fora dos títulos de B8.7/B8.8/B8.10:
`AND_v`/`BIC_v`/`ORR_v`/`ORN_v`/`EOR_v`/`BSL_v`/`BIT_v`/`BIF_v` (AdvSIMD "three same" lógico, MESMO
slot `bit10=1` de B8.7, opcode `0b00011` nunca tocado) e `SQABS`/`SQNEG`/`CLS`/`CLZ`/`CNT`/`NOT`/
`RBIT` (resto do slot `Rm=00000` "two-register misc"). Achado real: `CNT_v`/`NOT_v`/`RBIT_v`
compartilham o MESMO opcode (`0b01011`), o campo cru `esz` só desambigua as 3 (arranjo sempre
byte, não tamanho de elemento livre). `Aarch64AdvSimdLogicalDecoderTest` (19, corpus real via
devkitA64) + 17 testes novos no executor compartilhado. `mvn -o test` verde (core+truffle) +
`install`; G5: gbaemu/ndsemu/armbox ✅. A64 73%→74%, global 74%→75% — sem marco de release. Ver
**Resultado** na task. **Candidatas registradas, não pegas automaticamente**: `REV16_v`/`REV32_v`/
`REV64_v` (reversão por grupo de bytes), `XTN`/`SHLL_v`/`URECPE_v`/`URSQRTE_v` (slot narrow/widen),
`B8.19` (AdvSIMD "vector/scalar × indexed element", ~50 lacunas — `FMLA_vi`/`SQDMULL_si`/etc.,
classe de encoding própria). **Fila automática vazia de novo** — próxima priorização cabe ao
usuário.

✅ **B8.19 fechada 2026-08-27** (`trilha-b-arquiteturas/b8.19-a64-advsimd-vetor-escalar-indexed-element.md`,
task spec escrita e executada na mesma sessão, priorizada pelo usuário entre B8.19/`REV16-32-64_v`/
`XTN-SHLL-URECPE-URSQRTE_v` — escolheu B8.19, a maior das três) — `MUL`/`MLA`/`MLS`/`SQDMULH`/
`SQRDMULH`/`SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL`/`FMUL`/
`FMLA`/`FMLS`/`FMULX` (vetorial `_vi` e, onde real, escalar `_si`) implementados, escopo
ARMv8.0/Cortex-A53 (meia-precisão/`SQRDMLAH`-`SQRDMLSH`/dot-product/`FMLAL`-família/`FCMLA`
deliberadamente fora, `docs/isa-nao-aplicavel.tsv`). Achado real: a família inteira compartilha o
MESMO prefixo de "shift by immediate" (B8.8), discriminada só por `bit10`. 3 `Ir64Op` novos
(`VectorArithmeticThreeSameByElement`/`VectorArithmeticWideningByElement`/
`VectorFpArithmeticThreeSameByElement`) reaproveitando 100% dos enums de operação já existentes —
`Rm` sempre contribui o MESMO elemento `index`, replicado (diferente de "three same"/"three
different"). `Aarch64AdvSimdIndexedElementDecoderTest` (27+4 negativos) + novos testes no executor,
corpus real via devkitA64. `mvn -o test` verde (arm-jitter completo) + `install`; G5 nos 5
consumidores ✅ (gbaemu/ndsemu/armbox/virtual-arm-box/n3dsemu). `docs/COBERTURA-ISA.md`: A64
68%→81% (+12pp desde `1.3.0`), global 73%→76% (+3pp) — cruza o gatilho de arquitetura (+10pp);
release (`1.4.0`) NÃO feito nesta sessão (mesma decisão consciente de sessões anteriores, conta
perto do limite mensal do Maven Central). **Achado adicional fora de escopo**: `SQDMULL`/
`SQDMLAL`/`SQDMLSL` escalares SEM índice (dois registradores, "three different" escalar) seguem
`unsupported` — categoria diferente desta task, candidata própria. Ver **Resultado** na task.
**Candidatas restantes, não pegas automaticamente**: `SQDMULL`/`SQDMLAL`/`SQDMLSL` escalares sem
índice, `REV16_v`/`REV32_v`/`REV64_v`, `XTN`/`SHLL_v`/`URECPE_v`/`URSQRTE_v`, publicação de
`1.4.0`. **Fila automática vazia de novo** — próxima priorização cabe ao usuário.

🆕 **Épico novo registrado 2026-08-27: `B11`** (`trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`)
— o usuário perguntou por que o A64 não é componível por versão/feature como o 32-bit
(`ArmFeature`/`ArmArchitecture`); investigação confirmou que `Aarch64Core`/`Aarch64Decoder` não têm
NENHUM mecanismo de feature-gating (um construtor só, sem parâmetro de arquitetura) — dívida técnica
nunca escolhida pelo usuário, não uma decisão de escopo. **Correção de rumo importante do usuário,
ver `feedback-nunca-excluir-instrucao-arm` na memória do agente**: "essa biblioteca pode ter outros
consumidores diferentes dos que estamos fazendo" — "nenhum consumidor NOSSO pede isso hoje" nunca é
argumento para descartar/adiar uma feature ARM real (só para sequenciar). Escada B11.1-B11.5+
definida na task (fundação → threading no core/decoder sem quebrar G3 → auditoria de versão por
instrução já implementada → prova de conceito com 1 feature isolada → medidor `COBERTURA-ISA.md`
por versão de A64, não mais uma coluna monolítica).

✅ **B11.1 fechada 2026-08-27** (priorizada pelo usuário entre B11.1/publicar 1.4.0/lacunas A64
pequenas restantes) — `arch64.Aarch64Feature` (enum, 19 `FEAT_*` reais ARMv8.1-A..ARMv9.5-A já
catalogados em `docs/isa-nao-aplicavel.tsv`) + `arch64.Aarch64Architecture` (mesmo padrão
`of`/`extending`/`has` de `ArmArchitecture`, sem `DecoderExtension` — mecanismo que não existe no
pipeline A64 ainda), presets `ARMV8_0_A`..`ARMV8_9_A`/`ARMV9_0_A`..`ARMV9_5_A`. Achado confirmado
no manual ARM: toda ARMv9.x-A tem como baseline mandatório (fora SVE/SME) o conjunto de features
da ARMv8.(x+4)-A correspondente — presets `ARMV9_x_A` estendem o `ARMV8_(x+4)_A` equivalente.
**Zero wiring**: nenhum decoder/executor A64 consulta a arquitetura nova ainda (G3, comportamento
idêntico) — isso é B11.2. `mvn -o test` verde (core 2513, +10) + `install`; G5 completo nos 5
consumidores (gbaemu/ndsemu/virtual-arm-box/n3dsemu ✅, armbox 47/47 ✅, sem a falha pré-existente
de sessões anteriores reproduzida desta vez). Ver **Resultado** em
`trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`. **Próximo da escada, não pego
automaticamente**: B11.2 (fiação no `Aarch64Core`/`Aarch64Decoder`) ou B11.3 (auditoria de versão
de cada instrução A64 já implementada) — cabe ao usuário priorizar. Candidatas restantes de
sessões anteriores continuam de pé: publicar `1.4.0`, `SQDMULL`/`SQDMLAL`/`SQDMLSL` escalares sem
índice, `REV16_v`/`REV32_v`/`REV64_v`, `XTN`/`SHLL_v`/`URECPE_v`/`URSQRTE_v`.

🆕 **Épico novo registrado 2026-08-28: `B12`** (`trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`,
spec escrita a pedido do usuário — "escrever a spec para ter a lista completa de processadores e o
cliente escolher no futuro", **NÃO EXECUTAR ainda**, só planejamento) — usuário perguntou se a
coluna "Architecture" ou "Processor" da Wikipedia (`List of ARM processors`) determina o conjunto
de instruções (resposta: Architecture; Processor é uma implementação daquela arquitetura) e, a
partir disso, se o projeto poderia ter um catálogo de processadores reais nomeados (`Cortex-A53`,
`ARM7TDMI`, `Neoverse V1`, ...) resolvendo para `ArmArchitecture`/`Aarch64Architecture`, em vez do
cliente montar features na mão. Irmão de B11 (usa `Aarch64Architecture` de B11.1, mas é uma camada
de conveniência por NOME comercial, não o mecanismo de composição por versão). Inventário completo
das famílias (ARM clássico, SecurCore, Cortex-M/R/A/X, Neoverse, C-Series) extraído da Wikipedia e
tabulado na task, com escada B12.1-B12.6+ (+ um épico de perfil R como pré-requisito de B12.x) —
maior parte já resolve para presets `ArmArchitecture`/`Aarch64Architecture` existentes (zero
trabalho de arquitetura novo, só a tabela de resolução nome→arquitetura); uma minoria (ARM clássico
pré-v4T, perfil R, Cortex-A32 AArch32-only, `ARMv7E-M`/`ARMv8-M`/`ARMv8.1-M`) precisa de preset
novo ou decisão de escopo, documentado por linha na task. **Candidata pegável, não pega
automaticamente**: `B12.1` (Cortex-A/X/Neoverse ARMv8.0-A→ARMv8.2-A, maior massa por menor
esforço) ou `B12.3` (equivalente 32-bit) — cabe ao usuário priorizar frente às candidatas de B11
acima.

✅ **B11.2 fechada 2026-08-28** (`trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`,
usuário escolheu entre B11.2/B11.3/lacunas A64 pequenas) — `Aarch64Core`/`Aarch64Decoder`/
`Ir64BlockExecutor`/`StandardIr64BlockLifter` ganharam overload aceitando `Aarch64Architecture`
(construtor antigo delega para `ARMV8_0_A`, zero-diff comportamental provado por teste, G3). Ainda
NENHUM gate de decode real (isso é B11.4). `mvn -o test` verde (core+truffle 2524, +11) +
`install`; G5 completo nos 5 consumidores ✅ (gbaemu 240, ndsemu 183, virtual-arm-box 87, n3dsemu
221, armbox 47/47, sem falha pré-existente). Sem marco de release (zero-diff, nada mudou em
`docs/COBERTURA-ISA.md`). Ver **Resultado** na task. **Candidatas restantes, não pegas
automaticamente**: `B11.3` (auditoria de versão de cada instrução A64 já implementada), `B11.4`
(primeiro gate real), lacunas A64 pequenas (`SQDMULL`/`SQDMLAL`/`SQDMLSL` escalares sem índice,
`REV16_v`/`REV32_v`/`REV64_v`, `XTN`/`SHLL_v`/`URECPE_v`/`URSQRTE_v`), `B12.1`/`B12.3`, publicação
de `1.4.0` (reservada para 100% de cobertura). **Fila automática vazia de novo** — próxima
priorização cabe ao usuário.

✅ **B11.3 fechada 2026-08-28** (`trilha-b-arquiteturas/b11.3-auditoria-versao-a64.md`, task spec
escrita e executada na mesma sessão, priorizada pelo usuário entre B11.3/B11.4/lacunas A64
pequenas/B12.1) — auditoria dos 528 mnemônicos A64 ✅ únicos de `docs/COBERTURA-ISA.md` contra a
versão/feature ARM real que os introduziu. **Achado principal, bug real de decode (G8)**: 10
mnemônicos apareciam ✅ mas não tinham NENHUM código implementando-os — `CPYFP`/`CPYFM`/`CPYFE`/
`SETP`/`SETM`/`SETE` (`FEAT_MOPS`, ARMv8.8-A), `LDCLRP`/`LDSETP`/`SWPP` (128-bit atomics,
`FEAT_LSE128`) e `LDAPR_i`/`STLR_i` ("LDAPUR"/"STLUR", `FEAT_LRCPC2`, ARMv8.4-A) eram
silenciosamente misdecodificados como `LDR (literal)` (`decodeLoadsAndStores` não checava bit24
dentro do bucket `SUBCLASS_LITERAL`); `LDRA` (`LDRAA`/`LDRAB`, `FEAT_PAuth`, ARMv8.3-A) caía como
`STR`/`STUR` (`decodeLoadStoreSingle` não checava bit21 para `idx=POST_INDEX`/`PRE_INDEX`).
Confirmado por probe direto no `Aarch64Decoder` antes de corrigir. Corrigido com 2 checagens novas
+ `Aarch64LoadStoreRegisterReservedSpaceDecoderTest` (11 testes: 6 negativos + 5 de não-regressão);
`docs/isa-nao-aplicavel.tsv` ganhou as 10 linhas correspondentes. **Achado secundário**: ~15
mnemônicos ✅ legítimos não são baseline ARMv8.0-A — `CAS`/`CASP` (`FEAT_LSE`, ARMv8.1), `RMIF`/
`SETF8`/`SETF16` (`FEAT_FlagM`, ARMv8.4), `AXFLAG`/`XAFLAG` (`FEAT_FlagM2`, ARMv8.5), `WFET`/`WFIT`
(`FEAT_WFxT`, ARMv8.7), `PAN`/`UAO`/`DIT`/`SSBS`/`TCO`/`ALLINT` via `MSR` (ARMv8.1/8.2/8.4/8.0-opc/
8.5/8.8), `SHA512*`/`SM3*`/`SM4*`/`RAX1`/`XAR`/`BCAX`/`EOR3` (ARMv8.2, achado novo — B8.11b não
tinha citado a versão). `AES*`/`PMULL*`/`SHA1*`/`SHA256*` confirmados extensão OPCIONAL desde
ARMv8.0-A; `ESB`/`GCSB`/`CHKFEAT` confirmados hint-space (RES NOP correto sem a feature, mesmo
padrão de `PACIA1716`/etc.). `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5
consumidores ✅. `docs/COBERTURA-ISA.md`: A64 81%→81% (824/1011→807/994), global 76%→76%
(2889/3761→2872/3744) — sem gatilho de release (suspenso até 100%). Ver **Resultado** na task.
**Candidatas restantes, não pegas automaticamente**: `B11.4` (primeiro gate real), `B11.5`
(medidor por versão), adicionar os ~15 features achados a `Aarch64Feature` (decisão de B11.4),
lacunas A64 pequenas (`SQDMULL`/`SQDMLAL`/`SQDMLSL` escalares sem índice, `REV16_v`/`REV32_v`/
`REV64_v`, `XTN`/`SHLL_v`/`URECPE_v`/`URSQRTE_v`), `B12.1`/`B12.3`, publicação de `1.4.0`
(reservada para 100%). **Fila automática vazia de novo** — próxima priorização cabe ao usuário.

✅ **B11.4 fechada 2026-08-28** (`trilha-b-arquiteturas/b11.4-aarch64-feature-gate-rdm.md`, task
spec escrita e executada na mesma sessão, priorizada pelo usuário entre B11.4/lacunas A64
pequenas/B12.1/B12.3 — escolheu B11.4) — primeiro gate real de feature A64: `SQRDMLAH`/`SQRDMLSH`
(`FEAT_RDM`, `ARMv8.1-A`), já isolados no espaço de encoding desde B8.8/B8.19, agora decodificam de
verdade quando `architecture.has(Aarch64Feature.RDM)` (2 pontos no `Aarch64Decoder`: forma
vetorial/escalar não-indexada, nova, e a indexada dentro de `decodeAdvancedSimdIndexedInt`).
Reaproveita 100% o `Ir64VectorThreeSameOp`/`Ir64Op.VectorArithmeticThreeSame`/
`VectorArithmeticThreeSameByElement` já existentes de `SQDMULH`/`SQRDMULH` — só 2 valores de enum +
2 casos novos no executor (acumula `Rd` sign-extendido com a MESMA `doublingMultiplyHigh` de
`SQRDMULH`, saturando). **Confirmado que não havia bug de decode (G8) a corrigir**: os 24 encodings
já caíam em `unsupported` antes desta task, sem colisão com `decodeAdvancedSimdCopy`/SHA/indexado —
só faltava implementação. Corpus real via devkitA64 (`.arch armv8.1-a`); `Aarch64AdvSimdRdmDecoderTest`
novo (21 testes, incluindo regressão de que o decoder default `ARMv8.0-A` continua rejeitando os 24
encodings) + 6 testes de executor novos (2 de saturação no acumulador). `mvn -o test` verde +
`install`; G5 completo nos 5 consumidores ✅ (zero-diff esperado, nenhum usa `ARMv8.1-A`; armbox sem
a falha pré-existente reproduzida desta vez). Sem mudança em `docs/COBERTURA-ISA.md` (medidor ainda
não distingue por versão — isso é B11.5). Ver **Resultado** na task. **Próximo da escada, não pego
automaticamente**: `B11.5` (medidor por versão A64) ou gatear mais features de
`docs/isa-nao-aplicavel.tsv` seguindo o mesmo padrão. **Fila automática vazia de novo** — próxima
priorização cabe ao usuário.

✅ **B11.5 fechada 2026-08-28** (`trilha-b-arquiteturas/b11.5-medidor-por-versao-a64.md`, task spec
escrita e executada na mesma sessão, priorizada pelo usuário entre B11.5/lacunas A64 pequenas/B12.1/
gatear mais features — escolheu B11.5) — `IsaCoverageReport`/`gerar-cobertura-isa.sh`: o grupo
`a64.decode` passa a ter 16 colunas (`ARMv8.0-A`...`ARMv9.5-A`, presets de `Aarch64Architecture`),
mesma UX de `v4T`/`v5TE`/.../`v7-M` do 32-bit. 9 features novas em `Aarch64Feature`
(`LSE`/`PAN`/`SHA3`/`UAO`/`FLAG_MANIPULATION`/`DIT`/`FLAG_MANIPULATION_2`/`WFXT`/`NMI`) para os
mnemônicos que B11.3 achou implementados SEM gate, ligadas nos presets `ARMV8_1_A`-`ARMV8_8_A` (zero
gate real no decoder, só `FEAT_RDM`/B11.4 é consultado hoje — a versão aqui é curadoria própria do
medidor, `AARCH64_VERSION_REQUIREMENTS`, mesmo espírito de `docs/isa-nao-aplicavel.tsv`). 8 linhas
`SQRDMLAH_v`/`SQRDMLSH_v`/etc removidas do TSV (a curadoria nova já sabe que são `ARMv8.1-A`, e uma
exclusão `A64` legada agora casa com TODA coluna de versão — mantê-las esconderia que já são `✅`
desde `ARMv8.1-A`). `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5 consumidores
✅ (zero-diff esperado). `docs/COBERTURA-ISA.md`: A64 vira 16 colunas (`ARMv8.0-A` 80%→`ARMv9.5-A`
81%); global 76%→80% — **efeito esperado de denominador** (baseline A64 agora contado em 16 colunas
em vez de 1, mesmo padrão do 32-bit contando cada arquitetura separadamente), não progresso real de
implementação, documentado no Achado 3 da task. Sem gatilho de release (regra suspensa até 100%).
**Achado**: `MSR (register)`/`MRS` não dão para versionar (mnemônico único no `a64.decode` cobrindo
TODOS os registradores de sistema, sem granularidade por `sysreg`) — ficam baseline. Ver
**Resultado** na task. **Candidatas restantes, não pegas automaticamente**: gatear as 9 features
novas de verdade no decoder (mesmo padrão de B11.4/RDM, uma por vez), lacunas A64 pequenas
(`SQDMULL`/`SQDMLAL`/`SQDMLSL` escalares sem índice, `REV16_v`/`REV32_v`/`REV64_v`, `XTN`/`SHLL_v`/
`URECPE_v`/`URSQRTE_v`), `B12.1`/`B12.3`, publicação de `1.4.0` (reservada para 100%). **Fila
automática vazia de novo** — próxima priorização cabe ao usuário.

✅ **B8.20 fechada 2026-08-28** (`trilha-b-arquiteturas/b8.20-a64-rev-narrow-scalar-restante.md`,
task spec escrita e executada na mesma sessão, priorizada pelo usuário entre 4 candidatas: lacunas
A64 pequenas / gatear as 9 features de B11.5 / `B12.1` / `B4.0.5`) — fecha as 3 lacunas pequenas que
B8.18/B8.19 tinham deixado de fora: `REV64`/`REV32`/`REV16` (permutação de grupo, "two-register
misc"), `XTN`/`SHLL`/`URECPE`/`URSQRTE` (slot narrow/widen `Rm=00001`) e `SQDMULL`/`SQDMLAL`/
`SQDMLSL` escalares sem índice (prefixo "three different" escalar). **Achados reais**: (1) `SHLL`
reaproveita 100% `USHLL`/`Ir64Op.VectorShiftWidenImmediate` já existente (zero código novo de
IR/executor — `shift` fixo em `8<<esz` em vez de imediato genérico, mas a fórmula é idêntica); (2)
`URECPE`/`URSQRTE` são fórmula PURA-INTEIRA fechada no QEMU/ARM DDI 0487 (`recip_estimate`/
`do_recip_sqrt_estimate`), não uma tabela de 256 entradas como o plano inicial temia — achado que
baixou bastante o risco desta parte; (3) comentário antigo de B8.8 dizia que o opcode `0b00101`/
`U=0` era `FCVTN` — corpus real (devkitA64) confirma que é `XTN`, `FCVTN` vive em outro opcode.
`Ir64Op.VectorArithmeticWidening` ganhou campo `scalar` novo (breaking change de construtor interno,
G3 preservado) para a forma escalar de `SQDMULL`-família reaproveitar o record/executor vetoriais já
existentes. `Aarch64AdvSimdRevNarrowScalarDecoderTest` (27 testes, corpus real via devkitA64 para as
formas positivas + encodings negativos derivados por fórmula) + 17 testes novos no executor
compartilhado. `mvn -o test` verde (core+truffle) + `install`; G5 completo nos 5 consumidores ✅
(gbaemu/ndsemu/armbox/virtual-arm-box/n3dsemu, zero-diff esperado e confirmado). `docs/COBERTURA-ISA.md`:
A64 (`ARMv8.0-A`) 80%→82%, global 80%→81% — sem marco de release (suspenso até 100%, ver
`tasks/README.md`). Ver **Resultado** na task. **Candidatas restantes, não pegas automaticamente**:
gatear as 9 features A64 de B11.5, `B12.1`/`B12.3` (catálogo de processadores), `B4.0.5` (armbox
fork/pipes — possivelmente já destravada por B4.0.3, não confirmado nesta sessão), demais lacunas A64
da varredura sistemática (segue aberta), publicação de `1.4.0` (reservada para 100%). **Fila
automática vazia de novo** — próxima priorização cabe ao usuário.

## Onda 3 — fila ATUAL (executar de cima para baixo)

Mesmas regras de sempre: 1 sessão = 1 task (ou 1 PR); **ordem dentro do mesmo
repo é obrigatória**; a coluna Repo mostra o que pode andar em paralelo (repos
diferentes apenas).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| P1 | **B4.0.5** — armbox fase 3: fork/execve/pipes/wait | `trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md` | armbox | B4.0.3 | ainda bloqueada — B4.0.3 fechou parcial, falta o busybox thumb2 (ver 🧑 abaixo) que essa task precisa como corpus |

## Onda 4 — priorizada pelo usuário em 2026-08-15 (executar de cima para baixo)

Cinco frentes pedidas pelo usuário: rename do `virtual-arm-box` (ex-`linuxbox`), emulador de 3DS, licença BSD,
issues no GitHub, e o 1.0 do arm-jitter no Maven. **Ordem escolhida pelo usuário:
infra primeiro, n3dsemu depois** — o n3dsemu é a única frente multi-sessão longa, e começar
por ela deixaria o resto parado.

Decisões já tomadas (**não reabrir**): BSD **3-Clause** · Maven **Central** mantendo o
`groupId` `dev.vitorsilverio` (domínio `vitorsilverio.dev` é do usuário, verificação por DNS
TXT) · gráficos do n3dsemu em **Vulkan/LWJGL 3 com janela GLFW própria**, sem backend de
software · `armbox` e `virtual-arm-box` seguem **repos separados** · n3dsemu começa por
**`.3dsx` homebrew**, ROM comercial é [REFINAR].

**Atualização 2026-08-15 (tarde)**: `virtual-arm-box` **agora TEM repositório no GitHub**
(decisão anterior revertida a pedido do usuário) — `https://github.com/vitorsilverio/virtual-arm-box`,
público, criado e vinculado como `origin` do checkout local `linuxbox/` (na época; renomeado
localmente para `virtual-arm-box/` pela F2, histórico git preservado via `git mv`), push
inicial feito (5 commits, incl. `F1: licença BSD 3-Clause`). **F8 e F9 retroativamente
completadas para este repo** (mesma sessão): 11 labels + `.github/ISSUE_TEMPLATE/{bug,feature,config}.yml`
(commit `3215f29` no `virtual-arm-box`, `criar-labels.sh` do arm-jitter estendido com o 5º repo),
issue `virtual-arm-box#1` postada (manifesto e `Fecha:` de F3 atualizados). Sem milestone (mesmo
padrão do armbox — sem agrupamento definido ainda).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| ~~P2~~ | ~~**F1**~~ ✅ fechada 2026-08-15 — licença BSD 3-Clause nos 5 repos | `trilha-f-infra/f1-licenca-bsd.md` | todos | — | `LICENSE`+`<licenses>`+README nos 5 repos, `mvn -o validate` verde, 1 commit por repo; destravou a F5 |
| ~~P3~~ | ~~**F8**~~ ✅ fechada 2026-08-15 — labels/milestones/templates + fronteira issues×`tasks/` | `trilha-f-infra/f8-github-issues-setup.md` | 4 repos | — | seção no `tasks/README.md`, 11 labels × 4 repos (`tasks/issues/criar-labels.sh`), 3 milestones (arm-jitter 1.1, gbaemu Fidelidade, ndsemu Compatibilidade), templates `bug.yml`/`feature.yml`/`config.yml` nos 4 repos; destrava a F9 |
| ~~P4~~ | ~~**F9**~~ ✅ fechada 2026-08-15 — 20 issues postadas | `trilha-f-infra/f9-github-issues-criacao.md` | 4 repos | F8 | gbaemu#1-5, ndsemu#1-7, arm-jitter#1-5, armbox#1-3; manifesto preenchido, placeholders `#TBD-*` resolvidos, `Fecha:` nas 8 tasks relacionadas; `virtual-arm-box/01` pendente (sem remote); destrava P5 |
| ~~P5~~ | ~~**F2**~~ ✅ fechada 2026-08-15 — rename `linuxbox` → `virtual-arm-box` + abstração `Machine` | `trilha-f-infra/f2-rename-virtual-arm-box.md` | virtual-arm-box | F1 | diretório+pacote+artefato renomeados, `Machine`/`--machine=versatilepb` novo, `README.md`/`ROADMAP.md` novos, docs do arm-jitter atualizadas; `mvn -o test` verde (23 testes); destrava P15/P16 |
| ~~P6~~ | ~~**F4**~~ ✅ fechada 2026-08-15 — arm-jitter 1.0.0 preparado | `trilha-f-infra/f4-arm-jitter-1.0.0-escopo.md` | arm-jitter | F1 | bump `1.0`→`1.0.0` nos 4 POMs (`grep` de confirmação vazio); `maven.deploy.skip=true` no `capi/pom.xml` (jar da lib nativa não serve como dependência); `CHANGELOG.md` novo (Keep a Changelog PT-BR, entrada 1.0.0 resumida por capacidade a partir do `ROADMAP.md`/README); seção `## Versionamento` no README (semver, API pública = pacotes `arch`/`core`/`core64`/`memory`/`jit`/`codegen`/`coprocessor`/`swi`/`debug`/`ir`/`ir64` + factories padrão de `JitRuntimeFactory`); corrigida a linha "MMU / full-system 32-bit" do README (era 🟡 citando `PREFETCH_ABORT` recursivo no `linuxbox` — desatualizada desde que B4.1.5 fechou o épico por completo em 2026-08-14 com shell `busybox` interativo; virou ✅ citando `virtual-arm-box`) + o parágrafo B4 inteiro e a data do cabeçalho do `ROADMAP.md` (mesmo ajuste, "Onde estamos" 2026-07-31→2026-08-15); coordenadas Maven de exemplo no README também atualizadas para `1.0.0`. `mvn -o test` verde (core 1327 + truffle 13, JBR 25 `C:\Users\user\.jdks\jbr-25.0.3`) e `mvn -o install` verde, `dev.vitorsilverio:arm-jitter:1.0.0` instalado em `~/.m2` (o `1.0` antigo preservado como rede de segurança, conforme a spec pede). **G5 suspenso por desenho nesta task**: gbaemu/ndsemu/armbox/virtual-arm-box não foram tocados e ficam quebrados (pedem `arm-jitter:1.0`) até a F7 rodar — ⚠️ agende F7 logo em seguida, janela deveria ser curta |
| ~~P7~~ | ~~**F5**~~ ✅ fechada 2026-08-15 — publicado no Maven Central | `trilha-f-infra/f5-maven-central-publicacao.md` | arm-jitter | F4 | `dev.vitorsilverio:arm-jitter:1.0.0`+`arm-jitter-truffle:1.0.0` no ar (`repo1.maven.org` confirmado por `mvn dependency:resolve` sem instalação local); 2 bugs corrigidos (`capi` vazando pro bundle, plugin `0.7.0`→`0.11.0`); ver índice do `tasks/README.md` para o detalhe. Destrava P8/P9 |
| ~~P8~~ | ~~**F7**~~ ✅ fechada 2026-08-15 — consumidores no Central, sem `asm` declarado | `trilha-f-infra/f7-consumidores-central.md` | 4 consumidores | F5 | 4 POMs em `1.0.0`, `org.ow2.asm` removido de gbaemu/ndsemu (transitivo, confirmado); `mvn test` com `~/.m2/repository/dev/vitorsilverio` renomeada passou nos 4 (gbaemu 240/ndsemu 183/armbox 41/virtual-arm-box 23); docs corrigidas; commit por repo. Fecha a janela aberta pela F4/F6 |
| ~~P9~~ | ~~**F6**~~ ✅ fechada 2026-08-15 — CI verde nos 4 repos + release.yml escrito | `trilha-f-infra/f6-github-actions-pipeline.md` | 4 repos | F5 | 5 testes do gbaemu sem guarda (falhariam, não pulariam) ganharam `assumeTrue`; ndsemu/armbox não precisaram de nada; 4 execuções reais observadas verdes na primeira tentativa; `release.yml` escrito mas não testado ponta a ponta (decisão da spec — sem release vazia só pra provar); usuário ainda precisa cadastrar os 4 segredos; ver índice do `tasks/README.md` para o detalhe |
| ~~P10~~ | ~~**G1**~~ ✅ fechada 2026-08-15 — `n3dsemu`: esqueleto + loader `.3dsx` + primeira `svc` | `trilha-g-3ds/g1-esqueleto-n3dsemu.md` | n3dsemu (novo) | F7 | repo novo criado e commitado localmente; loader+memória+CP15/c13+SvcTable+`ARM11_MPCORE`; achado real documentado (convenção de imediato de `SVC` GBA/NDS vs. Horizon, sem tocar no decoder compartilhado); `n3dsemu testdata/application.3dsx --trace-svc` chega a `0x21 svcCreateAddressArbiter` idêntico nos 3 backends; `mvn -o test` verde (15); ver índice do `tasks/README.md` para o detalhe completo; destrava G2 |
| ~~P11~~ | ~~**G2**~~ 🟡 PARCIAL (2026-08-15, PR1+PR2; investigação 2026-08-16 confirmou desbloqueio + achou/corrigiu 1 bug novo) — kernel Horizon em HLE | `trilha-g-3ds/g2-kernel-hle-svc.md` | n3dsemu | G1 | PR2 fechou threads/sincronização/`AddressArbiter`/começo de IPC (ver índice do `tasks/README.md`); aceite objetivo "roda até `svcExitProcess`" ainda **NÃO alcançado**, mas o bloqueio original (FPSCR) está resolvido — ver nota abaixo, "Investigação 2026-08-16" |
| ~~P11.5~~ | ~~**G2.2**~~ ✅ fechada 2026-08-18 — causa raiz do "laço" achada + fix pequeno aplicado | `trilha-g-3ds/g2.2-address-arbiter-loop.md` | n3dsemu | G2 | NÃO era uma SVC retentando: trace de duas fases (`ArmCore#step()` puro, sem JIT — achado vale para os 2 backends) mostrou o boot inteiro reiniciando do zero a cada 271.058 instruções porque `srvInit` executa `MCR p15,0,r0,c7,c10,{5}` (`DMB`, ARMv6K) que `N3dsCp15` não reconhecia → `ArmException.UNDEFINED` → sem vetor de exceção configurado, PC caía em 0x4 e andava até tropeçar de volta em `0x100000`. Fix: `DSB`/`DMB` como no-op em `N3dsCp15`. Boot agora alcança `svcSendSyncRequest` (fronteira real com G3) nos 3 backends. `mvn -o test` verde (94 testes). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava G3** |
| ~~P12~~ | ~~**G3**~~ 🟡 PARCIAL (2026-08-18) — IPC + serviços (`srv:`/`APT`/`hid`/`fs`/`gsp` mínimo) | `trilha-g-3ds/g3-servicos-srv-apt-hid-fs.md` | n3dsemu | G2.2 | codec IPC + `ServiceRegistry` + 7 serviços implementados, `svcSendSyncRequest` despacha de verdade. 2 achados reais corrigidos: `Loader3dsx` não alinhava segmentos a página (bloqueava TODO `srv:GetServiceHandle`) + fila de interrupção do `gsp::Gpu` precisa ser populada, não só o evento sinalizado. `mvn -o test` verde (118). **Aceite NÃO fechado**: `read-controls.3dsx` não sai sozinho via `--script`+START (trava no loop `ClearEvent`/`WaitSynchronization` da thread de relay do gsp, prioridade mais alta nunca devolve controle); raiz não isolada — candidato a **G3.2**. Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P12.5~~ | ~~**G3.2**~~ 🟡 PARCIAL (2026-08-18) — investigar a inanição do loop de `read-controls.3dsx` (thread de relay do `gsp::Gpu`) | `trilha-g-3ds/g3.2-gsp-relay-starvation.md` | n3dsemu | G3 | causa raiz do sintoma ORIGINAL achada e corrigida (bug real: `RegisterInterruptRelayQueue` devolvia o "GSP module thread index" errado, `1` em vez de `0` — guest lia/escrevia no bloco de fila ERRADO da memória compartilhada); revelou bloqueio NOVO mais profundo (main thread nunca reescalonada depois de criar a thread de relay, mesmo com o relay drenando certo) — virou **G3.3** (ver linha abaixo: causa raiz achada, não é o `Scheduler`). Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P12.6~~ | ~~**G3.3**~~ ✅ fechada 2026-08-18 — causa raiz do não-reescalonamento achada + corrigida | `trilha-g-3ds/g3.3-main-thread-never-rescheduled.md` | n3dsemu | G3.2 | trace com `LR` (não só `PC`) localizou o chamador real: `T1` bloqueava em `svcArbitrateAddress` esperando `gspEvents[2]` (`GSPGPU_EVENT_VBlank0`, endereço `0x0011D26C = 0x11D25C + 2*8`, aritmética fechada via desmontagem de `gspWaitForEvent`/`gspEventThreadMain` cruzada com `WebFetch` no libctru real). **Causa raiz não era um evento faltando** — era `GspGpuService#pushInterrupt` escrevendo a entrada nova no CURSOR DE LEITURA da fila (campo que pertence só ao cliente, confirmado contra 3dbrew + `popInterrupt()` real) e avançando esse mesmo campo, em vez de derivar o índice como `(readCursor+count)%CAPACITY` — a 1ª interrupção real (`PDC0`=2) acabava sinalizando `gspEvents[0]` em vez de `gspEvents[2]`. Corrigido em `GspGpuService.java`; teste de regressão unitário novo; confirmado ao vivo (`--slices=200`) que a main thread agora reescalona a cada VBlank e entra no laço normal de `read-controls` (não sai sozinho ainda — sem input real, fora do escopo). `mvn -o test` verde (124). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava G4** |
| ~~P13~~ | ~~**G4**~~ 🟡 PARCIAL (2026-08-19) — janela Vulkan (LWJGL 3 + GLFW) apresentando os framebuffers | `trilha-g-3ds/g4-vulkan-apresentacao.md` | n3dsemu | G3.3 ✅ | implementado por completo: POM com LWJGL 3 (BOM + profiles de natives por SO, `lwjgl-vulkan` sem classifier em Windows/Linux); `gpu/` novo (`Screen`/`PixelFormat`/`PicaRenderer`/`RecordingRenderer`/`FrameBufferCodec`/`FrameBufferState`/`GuestFrameBufferReader`); `GspGpuService` agora INTERPRETA `SetBufferSwap` de verdade (antes descartava, G3: "descarta tudo") e expõe `FrameBufferState`; `gpu/vulkan/VulkanRenderer` com pipeline Vulkan completo (instância/dispositivo/swapchain/render pass/pipeline único de quad-cheio/2 texturas/upload via staging buffer/sync 2 quadros em voo), shaders GLSL como recursos do jar compilados via `lwjgl-shaderc` em runtime (`present.vert`/`present.frag` — rotação retrato→paisagem em UMA linha de GLSL, conforme a task pede); `Main` com janela como DEFAULT + `--headless` preservando o comportamento da G3; teclado (mapeamento fixo) e mouse (touch) ligados via GLFW. **Bug real encontrado e corrigido nesta sessão**: `VulkanRenderer#close()` chamava `vkDestroySampler` DENTRO do laço por tela (2 texturas = destruía o mesmo sampler compartilhado duas vezes) — Vulkan não valida isso sem validation layers, e o double-destroy corrompia o heap nativo (`STATUS_HEAP_CORRUPTION`, processo morria sem stack trace/hs_err; isolado por bisecção com prints de progresso rodando a JVM fora do Surefire). `mvn -o test` verde (inclui `VulkanRendererSmokeTest`, que roda de verdade contra o driver Vulkan real desta máquina — não só `RecordingRenderer` — e prova que inicializar/apresentar um quadro sintético/fechar não lança; guarda com `Assumptions`/`LinkageError` para CI sem GPU, RFC D4). Smoke-run manual confirmado: `n3dsemu testdata/hello-world.3dsx` roda 10s em janela sem crashar/exceção nenhuma; `--headless` continua idêntico à G3. **Não fechado por completo**: falta só o passo HUMANO explícito do Aceite ("anexe uma captura de tela" — RFC D4: "nenhuma task da trilha G pode ter como aceite automatizado 'o triângulo apareceu'", validação é sempre visual/do usuário) — esta sessão não tem como capturar/julgar a imagem. Usuário: rode `n3dsemu testdata/hello-world.3dsx` e confirme visualmente (texto legível, orientação correta nas duas telas) para fechar G4 de vez e destravar G5 |

**Sessão de validação 2026-08-19 (usuário tentou confirmar G4 visualmente — ainda preto, 2 bugs
reais corrigidos, 2 achados novos viram task)**: `hello-world.3dsx` abria em janela preta, sem
texto algum. Corrigidos 2 bugs reais do n3dsemu (commit `f36a6aa`): (1) `aptInit` real abre 3
sessões (`APT:U`/`APT:S`/`APT:A`) para o mesmo serviço — só `APT:U` estava registrado, `APT:S`
falhava e abortava o boot antes de qualquer desenho (`ServiceRegistry` ganhou `registerAlias`);
(2) o libctru moderno (2.7.0) não chama mais `GSPGPU:SetBufferSwap` por IPC a cada quadro, escreve
direto no `FrameBufferUpdate` da memória compartilhada do `gsp` (3dbrew) — só o caminho antigo por
IPC estava implementado; `GspGpuService#onVBlank` agora lê esse bloco. Mesmo assim a tela continua
preta: rastreado até `APT:U GetSharedFont` (cmd `0x44`) não implementado — sem fonte mapeada o
console não tem o que desenhar (framebuffer troca de endereço certo a cada quadro, mas fica
100% zerado, confirmado varrendo o buffer inteiro). Virou task **G4.1** (ver tabela acima). Achado
colateral mais sério: com os 2 fixes, o boot avança mais longe e `mvn -o test` do n3dsemu expõe um
bug PRÉ-EXISTENTE do `arm-jitter` (confirmado via `git stash`, não introduzido por este commit) —
divergência real ASM×interpretador em `StoreExclusive` (STREX), padrão de spinlock/retry sob
`ARM11_MPCORE`. Virou task **E3** no `arm-jitter`. `mvn -o test` do n3dsemu: 2 falhas (a
divergência STREX), não corrigidas nesta sessão — commit feito mesmo assim a pedido do usuário
(os 2 fixes são corretos e uma melhoria real, mesmo sem fechar a suíte). |
| P13.5 | **G4.1** — `APT:GetSharedFont` (fonte do sistema para o console) | `trilha-g-3ds/g4.1-apt-shared-font.md` | n3dsemu | G4 | ⏸️ **PARADA (sessão 2026-08-19, passo "Inclui" 1 — confirmar a hipótese): HIPÓTESE REFUTADA por evidência, não implementado.** Ver nota abaixo ("Sessão 2026-08-19 (G4.1) — hipótese de GetSharedFont refutada") — usuário decidiu o próximo passo: task nova **G4.2** (ver linha abaixo), não é a causa da tela preta |
**Sessão 2026-08-19 (G4.1) — hipótese de `GetSharedFont` REFUTADA (passo 1 do "Inclui": "confirmar a
hipótese... não presumir")**: a task pedia confirmar, com trace, que `APT:GetSharedFont` era chamado
e falhava antes de implementar o fix. Rodando `n3dsemu testdata/hello-world.3dsx --headless
--trace-svc` com orçamento de fatias bem maior que o da sessão anterior (200→3000, chega a executar
o laço principal do app em regime estacionário por milhares de iterações, log completo capturado, não
só as últimas 32 chamadas do ring buffer) o comando `0x0044` (`GetSharedFont`, confirmado via
`WebFetch` na 3dbrew: `NS_and_APT_Services`, cabeçalho `0x00440000`) **nunca aparece nem uma vez** —
nenhum log `[APT:U] comando desconhecido 0x0044`. Cruzando com o fonte real do libctru
(`libctru/source/console.c` via `WebFetch` no GitHub `devkitPro/libctru`): `consoleInit`/
`consoleDrawChar` usam uma fonte **embutida no próprio binário** (`default_font_bin`, bitmap 8×8
compilado estaticamente), *não* a fonte do sistema via `APT_GetSharedFont` — essa API só é usada
pelo módulo de fontes do `citro2d`/`citro3d` (texto TTF-like), que o `hello-world` (baseado em
`console.c` puro) não usa. **Confirmado também no fonte do próprio exemplo**
(`C:\devkitPro\examples\3ds\graphics\printing\hello-world\source\main.c`): `printf` roda antes do
laço principal; nenhuma chamada relacionada a fonte aparece em lugar nenhum. Conclusão: a tela preta
**não tem relação com fonte nenhuma** — a causa real está em algum outro lugar do caminho
`consoleInit`→`printf`(escreve direto no framebuffer via ponteiro de `gfxGetFramebuffer`)→
`gfxSwapBuffers`→`GspGpuService`/`FrameBufferState`→`Main#presentFrame`. **Não implementado**
(rodar o toolset `JayFoxRox/3ds-font` teria sido trabalho desperdiçado sobre uma hipótese falsa) —
devolvida ao usuário: precisa de uma sessão nova de investigação (candidata a task **G4.2**) para
achar a causa real, provavelmente rastreando byte a byte o que `consoleDrawChar` escreve no
framebuffer do guest vs. o que `GuestFrameBufferReader`/`presentFrame` de fato lê (mesma técnica de
dump de memória já usada em outras investigações desta fila). `mvn -o test` não rodado nesta sessão
(nenhum código tocado). Nenhum commit desta sessão além da atualização deste arquivo.

| ~~P13.6~~ | ~~**E3**~~ ✅ fechada 2026-08-19 — causa raiz achada + corrigida: bug do harness, não de LDREX/STREX | `trilha-e-manutencao/e3-strex-asm-interp-divergence.md` | arm-jitter | — | causa raiz NÃO era semântica de STREX em nenhum backend — era `DivergenceCheckingCodeEmitter` restaurando o `scratchCore` do candidato via `ArmCore#loadState` a CADA bloco, que limpa o monitor de exclusividade de propósito (correto p/ save-state real, errado aqui); um `STREX` no bloco seguinte a um `LDREX` (padrão spinlock/retry) sempre via o monitor aberto no candidato. Fix aditivo: transfere a reserva do core real pro scratch após o restore. `mvn -o test` verde (arm-jitter core+truffle) + `mvn -o install`; G5: gbaemu/ndsemu/n3dsemu verdes (os 2 testes do n3dsemu citados na task voltam a passar), armbox 40/41 (falha pré-existente não relacionada). Ver índice do `tasks/README.md` para o detalhe completo |
| ~~P13.7~~ | ~~**G4.2**~~ ✅ fechada 2026-08-19 — causa raiz real achada e corrigida: `descriptorCount` ausente no `VkWriteDescriptorSet` | `trilha-g-3ds/g4.2-tela-preta-sem-fonte.md` | n3dsemu | G4.1 (hipótese refutada) | Passo 1 (dump de memória) refutou a premissa da própria task: `hello-world.3dsx` escreve pixels reais em `Screen.TOP` e a cadeia até `GuestFrameBufferReader` está correta (teste `HelloWorldFramebufferTest`). Passo 2/3: usuário confirmou a janela AINDA preta rodando de verdade — instrumentação (System.err temporário) provou milhares de `uploadPending`/`renderFrame` reais com dados de textura corretos, ainda preto; **achado real**: `-Dn3dsemu.vulkan.validation=true` (validation layers, já existiam no código) acusou `VUID-vkCmdDraw-None-02699` — o descriptor set nunca foi atualizado. Causa: `createScreenTexture` monta o `VkWriteDescriptorSet` via `calloc` (zerado) e nunca chama `.descriptorCount(1)` — `vkUpdateDescriptorSets` virava um no-op silencioso, sem erro de API (só a validation layer acusa, e só no `vkCmdDraw` seguinte, não no próprio update — por isso passou despercebido nas sessões G4/G4.1 sem validation ligada). Fix de 1 linha (`VulkanRenderer.java`), usuário confirmou visualmente ("agora imprimiu"). `mvn -o test` verde (136). **Destrava G5** |
| ~~P14~~ | ~~**G5**~~ ✅ fechada 2026-08-21 (PR1-PR4; aceite visual alcançado pela G5.2/G5.3, PR4 e levantamento fechados) — PICA200 (command list + shader + TEV) | `trilha-g-3ds/g5-pica200-render.md` | n3dsemu | G4.2 ✅ | LONGA, 3 PRs; aceite é **só** o `simple_tri` desenhar visualmente, validação do usuário (RFC D4). PR1/PR2: parser+registradores+shader interpretado+`VertexPipeline`, ver `FILA-HISTORICO.md`. **PR3 fechada nesta sessão**: `gpu/tev/TevConfig`+`TevGlslGenerator` (decodifica os 6 estágios TEV dos registradores reais, gera GLSL — testado, mas AINDA não usado pelo pipeline Vulkan, que continua com o shader estático equivalente ao caso `simple_tri`); `gpu/Texture` (formatos não-comprimidos + deswizzle Morton 8×8, testado; não usado pelo Vulkan ainda — task permite adiar p/ PR4); e **o item que faltava para `simple_tri` desenhar de verdade**: `GSPGPU_TriggerCmdReqQueue` agora lê a fila GX real (`gpu/GxCommandQueue`, layout confirmado via 3dbrew), `gpu/shader/ShaderUpload` captura o upload de vertex shader por registrador-FIFO (código/opdescs/uniforms — o caminho real do hardware, não mais só `.shbin`-arquivo), e `GspGpuService` desenha de verdade no `VulkanRenderer` quando um `DrawArrays`/`DrawElements` real dispara (`Main`/`N3dsMachine` injetam o renderer real no modo janela). Teste de integração ponta-a-ponta novo (`GspGpuServiceTest`) monta a fila GX real e confirma 3 vértices corretos chegando ao renderer. `mvn -o test` verde (**180**, +17). Fumaça manual (`--headless`) de `simple_tri.3dsx` não crasha, mesmo padrão de espera de VBlank do `hello-world.3dsx`. **Simplificações documentadas** (Javadoc de `ShaderUpload`): convenção fixa `o0`=posição/`o1`=cor (sem decodificar `SH_OUTMAP`), só uniform float32 (float24 lança exceção), sempre `Screen.TOP`. G5-invariante não se aplica. Detalhe completo em `FILA-HISTORICO.md`. **Falta**: usuário rodar `n3dsemu <path>/simple_tri.3dsx` (janela) e confirmar visualmente; se OK, PR4 (sampler Vulkan de textura + wiring do TEV no pipeline + float24 + `SH_OUTMAP` granular) fecha a trilha G de vez |
| ~~P14.5~~ | ~~**G5.1**~~ 🟡 encerrada 2026-08-21 pela **G5.2** (que achou a causa raiz que faltava — ver linha abaixo) — 3 sessões — tela preta em `simple_tri.3dsx`: 1 bug real corrigido, causa raiz do travamento bem mais estreita mas ainda não fechada | `trilha-g-3ds/g5.1-simple-tri-tela-preta.md` | n3dsemu (+ 1 fix aditivo no arm-jitter) | G5 | **Sessão 1**: `onVBlank` dispara e `WaitSynchronization` bloqueia de verdade (2 hipóteses da validação de 2026-08-20 refutadas). **Bug real corrigido** (arm-jitter, aditivo/G3): `Scheduler#switchTo` restaurava `ArmCore.cycles` por thread via `loadState`, fazendo o relógio virtual COMPARTILHADO andar para trás a cada troca de contexto; `ArmCore.setCycles(long)` novo + `Scheduler` reafirma o relógio depois do restore. **Sessão 2**: LR+`objdump` de `simple_tri.elf` (técnica da G3.3) mostrou que `0x1140A4`/`0x1140CC` são um mailbox single-slot de `gspWaitForAnyEvent` (não `gspEvents[]`); instrumentação dinâmica confirmou que `TriggerCmdReqQueue` nunca é chamado E que o loop de vsync do `C3D_FrameBegin` FUNCIONA CORRETAMENTE. **Sessão 3**: usuário reproduziu o triângulo brevemente 2x pela linha de comando (nunca pelo IntelliJ, mesmo JDK/`Rebuild`/`target/` apagado — diferença não explicada, provável timing real sensível a carga de CPU); a janela fica preta e ABERTA (não fecha) depois — hipótese "evento P3D mal entregue pro guest" testada e REFUTADA por um teste novo (`GspGpuServiceTest`, confirma entrega correta: fila de interrupção recebe id `5` certinho). Travamento real ainda não isolado — candidata **G5.2** com alvo preciso (capturar `LR` no retorno de `C3D_FrameBegin` / desmontar `C3D_FrameDrawOn`/`main()` do `simple_tri`). `mvn -o test` verde (arm-jitter 1502+13, n3dsemu 180), G5 revalidado (gbaemu 240, ndsemu 183). Ver **Resultado** na task |
| ~~P14.6~~ | ~~**G5.2**~~ ✅ fechada 2026-08-21 — **o triângulo do `simple_tri` APARECE** (validado por captura de tela) | `trilha-g-3ds/g5.2-simple-tri-causas-reais.md` | n3dsemu | G5.1 | Investigação dirigida a pedido do usuário ("achar TODAS as causas de uma vez, não uma por sessão"): eram **7 bloqueios INDEPENDENTES em série** — por isso as 3 sessões da G5.1 pareciam "andar e voltar", cada fix era real e destravava exatamente um passo. (1) só `PDC0` era gerado, mas `C3D_FrameSync` do citro3d exige que os DOIS contadores de VBlank avancem (`|| `, não `&&`) — a fila GX ficava 100% zerada para sempre; a G5.1 tinha olhado só `frameCounter[0]`. (2) entradas da fila GX começam em `0x20`, não `0x8`. (3) o byte 1 do cabeçalho é CONTAGEM de pendentes e o GSP tem que decrementá-la, senão o cliente nunca mais dispara `TriggerCmdReqQueue`. (4) `CommandListParser` não pulava a palavra de padding de comandos com nº ÍMPAR de extras. (5) endereços da GPU são FÍSICOS e os da CPU VIRTUAIS — VRAM `0x1F000000` nem estava mapeada e o heap linear não espelhava o FCRAM, todo atributo saía zerado (resolvido com `mapMirror`). (6) `GPUREG_VS_ENTRYPOINT` sem máscara (shader nunca rodava) + ordem de componentes de uniform float INVERTIDA (`w,z,y,x`, projeção com `w=0` → todo vértice `NaN`) + float24 empacotado não implementado. (7) atributos FIXOS (`GPUREG_FIXEDATTRIB_*`, classe `FixedAttributes` nova) inexistentes — a cor do `simple_tri` é fixa, o triângulo saía preto sobre preto — e permutação aplicada aos 12 slots em vez de `max_attribute_index+1`, zerando `v0`. Extra: `VulkanRenderer` não descarta mais a geometria e ignora o framebuffer zerado do guest — era o "flash" relatado. `mvn -o test` verde (**184**, +4). Nenhum arquivo do arm-jitter tocado, G5-invariante não se aplica. **G5.3, mesma sessão** (commit `3ad9634` no n3dsemu): `GX_MemoryFill` implementado de verdade (respeita `GX_FILL_TRIGGER` e a largura 16/24/32 bits nos 2 buffers) + `ColorBufferFormat` nova (⚠️ NÃO é o mesmo enum de `PixelFormat` — códigos 2/3 trocados) + `PicaRenderer#setClearColor`: o fundo agora é o azul `0x68B0D8` do app, tela idêntica ao hardware real. `mvn -o test` verde (**188**). **Pendências → PR4**: `DisplayTransfer` real, sampler/TEV, `SH_OUTMAP` |
| ~~P14.7~~ | ~~**G5/PR4**~~ ✅ fechada 2026-08-21 — TEV real no pipeline, texturas, `SH_OUTMAP`, e o levantamento dos 20 exemplos | `trilha-g-3ds/g5-pr4-levantamento-exemplos.md` (relatório) · `g5-pica200-render.md` (spec) | n3dsemu | G5.3 | commit `d530382`. **TEV ligado de verdade**: o GLSL agora é GERADO da configuração real e compilado em runtime, com pipeline+módulo cacheados por `TevConfig` — que deixou de usar arrays porque um record com array compara por IDENTIDADE e o cache erraria sempre (a spec avisa que é o erro clássico). O gerador ganhou os **operandos** por fonte (a armadilha explícita da spec), cor constante por estágio, buffer de cor combinada, saturação `[0,1]` por estágio e teste de alpha como `discard`. **Texturas**: `TextureUnits` novo (3 unidades, offsets irregulares) + desembaralho de Morton + 4 bindings de amostrador no Vulkan, descritor por chamada com pool por frame-in-flight. **`SH_OUTMAP` granular** (`OutputMap` novo): saída distribuída por SEMÂNTICA em vez de presumir `o0`=posição/`o1`=cor — é o que dá acesso às coordenadas de textura. **2 simplificações a menos**: `drawTriangles` ACUMULA por tela (um quadro real emite dezenas) com render pass `LOAD_OP_LOAD` nas subsequentes, e `Screen.TOP` fixo virou tela APRENDIDA do `GX_DisplayTransfer` (`both_screens` passou a desenhar). **`--report` novo** (headless, conta desenhos/vértices/fundo/texturas) é como o levantamento foi feito sem GPU. `mvn -o test` verde (**199**, +6). **Levantamento: 7 dos 20 exemplos produzem geometria, 1 validado visualmente.** Maior achado: 5 exemplos (incl. `textured_cube`) morrem da MESMA causa — `curQueue->entries` NULL em `gxCmdQueueDoCommands`, suspeita de dessincronia de contagem de interrupções da fila GX; **candidato a `G6.1`, destrava 5 de uma vez**. Ver o relatório para a tabela completa e o backlog gráfico derivado |
| ~~P14.8~~ | ~~**docs PICA200**~~ ✅ 2026-08-21 — `n3dsemu/docs/PICA200.md` | — | n3dsemu | — | A pedido do usuário, que perguntou se valeria um repo separado só de tradução PICA200→Vulkan. **Recomendação registrada: NÃO** — o `arm-jitter` tem 5 consumidores reais (foi isso que pagou o split), a PICA200 teria exatamente um, para sempre; e as 7 causas da G5.2 cruzaram a fronteira o tempo todo (3 delas estariam do lado do n3dsemu). Além disso `VertexPipeline`/`GxCommandQueue` leem memória pelo `AddressSpace` do arm-jitter, então um repo "sem relação com ARM" dependeria do arm-jitter mesmo assim. **O benefício de documentação, que era o argumento real, foi entregue sem custo arquitetural**: `docs/PICA200.md` reúne fila GX, virtual×físico, lista de comandos, shader/float24/outmap, atributos fixos, TEV e texturas — e é a fronteira que um módulo Maven interno usaria, se um dia o usuário quiser a barreira imposta pelo compilador (meio-termo sugerido, task própria) |
| ~~P14.9~~ | ~~**B3.9**~~ ✅ fechada 2026-08-21 — VFP `VNMLA`/`VNMLS`: gap de decode que derrubava 5 exemplos do n3dsemu | `trilha-b-arquiteturas/b3.9-vfp-vnmla-vnmls.md` | arm-jitter | B3.5 ✅ | Veio do levantamento da G5/PR4, que registrou uma **hipótese ERRADA** (dessincronia da fila GX). O que resolveu foi MEDIR: instrumentar a fila mostrou 1:1 perfeito no `simple_tri` e ZERO atividade no `textured_cube` (morre antes de submeter qualquer comando), e capturar os registradores na parada deu **`pc=0x4, sp=0x0`** — vetor de instrução indefinida com o SP bancado do modo UND nunca inicializado. Com `LR_und = PC+4`, a desmontagem dos 5 binários em `lr-4` mostrou todos na MESMA instrução: `VNMLS` (`.f64` em 4, `.f32` no `loop_subdivision`), gap documentado explicitamente no `VfpDecoder` desde a B3.5. Implementado nos 2 backends. ⚠️ Duas armadilhas fixadas por teste: **`VNMLS` não é `VMLS` com sinal trocado** (nega o ACUMULADOR, não o produto) e **`bit6` tem polaridade INVERTIDA** em `op1==0b001` vs. os vizinhos. Não fundidas, como MLA/MLS. `mvn -o test` verde (**1506**, +4) + `install`. **G5 revalidado**: gbaemu 240 ✅, ndsemu 183 ✅, n3dsemu 199 ✅, virtual-arm-box 87 ✅, armbox 40/41 (falha `Armv7TortureTest` PRÉ-EXISTENTE, confirmada com `git stash` nesta sessão). **Efeito**: `loop_subdivision` 0 → 501 desenhos / 334 mil vértices / textura 32×32 — primeiro exemplo com textura real a renderizar, fecha a validação visual do caminho de textura da PR4; levantamento passou a **8 de 20**. Próximo: mesma técnica nos 4 que agora param mais adiante |
| ~~P14.10~~ | ~~**E5**~~ ✅ fechada 2026-08-21 — `docs/COBERTURA-ISA.md`: tabela de cobertura de ISA GERADA POR MEDIÇÃO | `trilha-e-manutencao/e5-cobertura-isa.md` | arm-jitter | — | Pedido do usuário para parar de descobrir instrução faltante uma por vez (B6.8/B6.9/B6.10/B6.11/B6.12/B3.9 = 6 tasks, 6 investigações). **Não é tabela escrita à mão**: o inventário vem dos `decodetree` do QEMU, cada instrução vira um encoding representativo e é sondada no decoder REAL, por preset de `ArmArchitecture`. 3 estados: ✅ / ❌ / ⚠️ (decodifica como OUTRA coisa). Regenera com `./gerar-cobertura-isa.sh`. ⚠️ **Licença**: os `.decode` do QEMU são GPL e este repo é BSD — NÃO são versionados, o script baixa para `target/` (gitignored); só a tabela gerada fica no repo. **🔴 ACHADO na 1ª execução**: `ArmDecoder` decodifica `0xF2000000` (`VHADD` de NEON) como **`AND` cond=`AL`** — o espaço INCONDICIONAL (`cond==0b1111`: NEON, `PLD`, `BLX` imediato, `CPS`, `SETEND`, barreiras) não é reconhecido como espaço próprio, e tudo que não está explicitamente tratado vira outra instrução em SILÊNCIO, onde o hardware levantaria instrução indefinida. Pior que "não suporta" — **candidato a `E6`**. Panorama: A32 89% v7-A · T32 75% v7-A · VFP 44% MPCore · A64 **18%** · NEON/MVE/SVE/SME **0%**. Validado contra verdades conhecidas (`VNMLS` ✅ pós-B3.9, `_hp` ❌; `CCMP`/`LSLV` ✅ em A64). Mede decode, NÃO semântica |
| P15 | **F3** — `virtual-arm-box --machine=raspi1` | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box | F2 | ⏸️ **PAUSADA a pedido do usuário em 2026-08-18** — 6 sessões de diagnóstico sem fechar M3 (ver resumo abaixo); a sessão 6 tentou usar `qemu-system-arm -M raspi1ap` como oráculo e o PRÓPRIO QEMU travou aos 2,5s com este kernel/DTB (lacuna do modelo `raspi1ap`, não bug nosso) — sinal de que este `kernel.img` real (6.18.33) pode estar puxando periféricos/caminhos de código bem além do que emuladores minimalistas cobrem sem esforço desproporcional. **Não pegar automaticamente** — só retomar se o usuário priorizar de novo, possivelmente reavaliando o kernel/DTB alvo em vez de insistir no mesmo |
| P16 | **F10** — disco virtual `raw`+QCOW2 (r/w) + PL181 MMCI/SD | `trilha-f-infra/f10-disco-virtual-raw-qcow2.md` | virtual-arm-box | F2 | ⏸️ **PAUSADA junto com F3** (mesmo repo, regra 6 de serialização — sem sentido priorizar isolada enquanto F3 está parada) |
| ~~P18~~ | ~~**B6.6.7**~~ ✅ fechada 2026-08-18 — AArch64: superfície mínima de EL1 para kernel real | `trilha-b-arquiteturas/b6.6.7-aarch64-el1-kernel-surface.md` | arm-jitter | B6.6.4 | priorizada pelo usuário em resposta direta ao bloqueio achado pela F11; estendeu `Aarch64SystemRegisterId` (identidade da CPU resolvida intrinsecamente + timer genérico host-pluggável), decodificou `WFI`/`WFE`/hints/`HVC`/`SMC`, confirmou `ERET` já existente (B6.6.4, sem mudança), e deu a `Aarch64Core` um mecanismo mínimo de IRQ (`interruptLine`/`enterIrq`, espelho de `enterMemoryAbort`) + `PstateRegister.irqDisabled` (bit `I` de `DAIF`) — GIC/PSCI ficam fora, são responsabilidade do hospedeiro. `mvn -o test` verde (core 1380 + truffle 13), `mvn -o install` local feito, G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava F11 e B6.6.6** |
| ~~P17b~~ | ~~**B6.8**~~ ✅ fechada 2026-08-20 — AArch64: `CCMP`/`CCMN` (decode gap achado pela F11) | `trilha-b-arquiteturas/b6.8-aarch64-conditional-compare.md` | arm-jitter | B6.3.1 ✅ | Encoding CONFERIDO contra `a64.decode`/`translate-a64.c` reais do QEMU antes de codificar (os "Fatos de referência" recordados de memória na spec bateram campo a campo com a fonte real, inclusive `S=1` fixo e `Rn` nunca `SP`). `case` novo em `decodeDataProcessingRegister` + record único `Ir64Op.ConditionalCompare` (cobre registrador e imediato, D1) + executor reaproveitando `addWithFlags`/`subWithFlags` já existentes (D2, sem duplicar cálculo de flags). Corpus real estendido com as 4 combinações (`CCMP`/`CCMN`)×(registrador/imediato) em `W`/`X`, incl. o vetor literal `ccmp x18,#0,#0xd,pl` da F11. `mvn -o test` verde (core 1421 + truffle 13), `mvn -o install` local feito. G5 não se aplica (nenhum arquivo 32-bit tocado; backend ASM A64 não ganhou suporte a este `Kind`, cai no interpretado). Ver índice do `tasks/README.md` para o detalhe completo. **Destrava a continuação da F11** (retomar `Raspi364Machine`, sessão própria, outro repo — pode haver mais gaps de decode além deste) |
| ~~P17~~ | ~~**F11**~~ 🟡 PARCIAL (2026-08-18, sessão 2) — `Raspi364Machine` implementada, boot bloqueado num gap de decode real (`CCMP`/`CCMN`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.6.7 ✅ | Itens 1-5 do "Inclui" fecharam (máquina/periféricos/DTB completos). Relato minucioso em `FILA-HISTORICO.md` |
| P17c | **F11** 🟡 PARCIAL (2026-08-20, sessão 3) — retomada após B6.8, SEGUNDO gap de decode achado (`Logical (shifted register)`, incl. alias `MOV` de registrador) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.8 ✅ | Com `CCMP`/`CCMN` disponíveis o boot avança até `0x13ba9e8` (`0xaa0003f5` = `ORR X21,XZR,X0` = alias `MOV`), onde bate num gap documentado desde B6.3.1 (`Aarch64Decoder#decodeDataProcessingRegister`, comentário explícito "Logical (shifted register): fora do escopo fechado do épico") nunca implementado. Não é bug, fora do "Inclui" desta task — nenhuma mudança no arm-jitter. `Raspi364BootTest` atualizado (novo endereço/encoding pinado, `@Disabled` reescritos); `mvn -o test` verde no virtual-arm-box (87, 5 skipped), sem regressão em `Raspi1BootTest`/`VersatilePbBootTest`. G5 não se aplica. Relato minucioso em `FILA-HISTORICO.md`. **Sugestão ao usuário**: sub-task nova no arm-jitter (ex. `B6.9`) para "Logical (shifted register)", mesmo rigor de B6.8 — provavelmente destrava mais boot que CCMP sozinho, dado que `MOV` de registrador é onipresente; esperar possíveis gaps adicionais na mesma classe de instruções antes de presumir boot completo |
| P17f | **F11** 🟡 PARCIAL (2026-08-20, sessão 4) — retomada após B6.9, TERCEIRO gap de decode achado (`MRS CTR_EL0`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.9 ✅ | Com "Logical shifted register" disponível o boot avança bem mais longe (`0x13ba9e8`→`0x38fc4`) até `MRS X3, CTR_EL0` (Cache Type Register), fora do subconjunto de registradores de sistema que `Aarch64Decoder#decodeSystemRegisterId` resolve (só EL1 geral + timer genérico `CRn=14`, não o grupo de identificação EL0 `CRn=0`). Não é bug, fora do "Inclui" desta task — nenhuma mudança no arm-jitter. `Raspi364BootTest` atualizado (novo endereço/encoding pinado); `mvn -o test` verde no virtual-arm-box. **Sugestão ao usuário**: sub-task nova no arm-jitter (candidata `B6.10`) para estender `Aarch64SystemRegisterId`/`decodeSystemRegisterId` com `CTR_EL0` (e possivelmente `DCZID_EL0`, mesma família) — esperar mais gaps na mesma classe "System register access" antes de presumir boot completo, mesmo padrão iterativo de B6.8→B6.9 |
| ~~P17g~~ | ~~**B6.10**~~ ✅ fechada 2026-08-20 — AArch64: `CTR_EL0`/`DCZID_EL0` (decode gap achado pela F11) | `trilha-b-arquiteturas/b6.10-aarch64-ctr-el0-dczid-el0.md` | arm-jitter | B6.6.7 ✅ | Fatos de referência CONFERIDOS contra `target/arm/helper.c`+`cpu64.c` reais do QEMU: `CTR_EL0`/`DCZID_EL0` vivem no MESMO grupo `op1=3` do timer genérico, distinguidos por `CRn` (`0`=cache, `14`=timer) — `decodeSystemRegisterId` ganhou um `if` irmão do despacho ao timer. Dois registradores novos resolvidos intrinsecamente pelo `Aarch64Core` (mesmo padrão de `MIDR_EL1`): `CTR_EL0=0x8444_8004` (Cortex-A53 real), `DCZID_EL0=0x10` (só `DZP` setado — `DC ZVA` não implementado, anunciado como desabilitado em vez de expor um gap de decode diferente). Corpus real estendido, 4 testes novos. `mvn -o test` verde (core 1473 + truffle 13), `mvn -o install` local feito. G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava a continuação da F11** (retomar `Raspi364Machine`, sessão própria, outro repo) |
| ~~P17d~~ | ~~**B6.9**~~ ✅ fechada 2026-08-20 — AArch64: `Logical (shifted register)` (`AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` + alias `MOV`/`MVN`), SEGUNDO gap achado pela F11 | `trilha-b-arquiteturas/b6.9-aarch64-logical-shifted-register.md` | arm-jitter | B6.3.1 ✅ | Spec escrita e executada na MESMA sessão (fatos CONFERIDOS contra `a64.decode`/`translate-a64.c` reais do QEMU via `curl`, não recordados de memória — ao contrário do aviso deixado pela B6.8). Achado real que mudou o escopo: `MOV`/`MVN` (registrador) são alias de disassembly PURO no QEMU — o caminho geral `ORR`/`ORN` com `Rn=XZR` já produz o resultado correto, nenhum decode/executor dedicado foi necessário. Record novo `Ir64Op.LogicalShiftedRegister` + enum `Ir64LogicalShiftType` (não reaproveita `AluShiftedRegister`/`Ir64ShiftType` — precisa de `ROR`, reservado naquele). `ANDS`/`BICS` reaproveitam `Ir64AluOp.AND`+`logicalWithFlags` já existentes (D2, mesma decisão de B6.3.1). Corpus real estendido (offsets `0x3a8`-`0x444`, cobre as 4 combinações de opcode × `n` × os 4 shifts + `W` + os 4 aliases). 48 testes novos (41 decode + 7 executor em arquivo dedicado). `mvn -o test` verde (core 1469 + truffle 13), `mvn -o install` local feito. G5 não se aplica. Ver índice do `tasks/README.md` para o detalhe completo. **Destrava a continuação da F11** — próxima sessão deve esperar mais gaps na mesma classe "Data Processing — Register" antes de presumir boot completo |
| ~~P17h~~ | ~~**B6.11**~~ ✅ fechada 2026-08-20 — AArch64: `LSLV`/`LSRV`/`ASRV`/`RORV` (deslocamento variável), QUARTO gap achado pela F11 | `trilha-b-arquiteturas/b6.11-aarch64-shift-variable.md` [ainda não escrita como spec formal — implementada direto, mesmo padrão ad-hoc de B6.9] | arm-jitter | B6.3.1 ✅ | Mesmo subgrupo "Data-processing (2 source)" de `SDIV`/`UDIV` (`decodeDataProcessingRegister`, comentário já existente desde B6.3.3 apontando o gap). Encoding CONFERIDO contra `a64.decode` real do QEMU antes de codificar: `opcode(15:11)=00100`(`LSLV`/`LSRV`)/`00101`(`ASRV`/`RORV`), bit10 distingue dentro do par — os bits `[11:10]` batem exatamente com a ordem de `Ir64LogicalShiftType` (B6.9), reaproveitado em vez de um enum próprio. Record novo `Ir64Op.ShiftVariable`; executor reaproveita `applyLogicalShift` já existente, mas a quantidade vem de `Rm` EM TEMPO DE EXECUÇÃO (`mod` largura), não de um campo resolvido pelo decoder — diferença central vs. `LogicalShiftedRegister`. Backend ASM A64 não ganhou suporte a este `Kind` (cai no interpretado, mesma decisão de B6.9). Corpus real estendido (offsets `0x450`-`0x470`, incl. o vetor literal `lsl x2, x2, x3` = `0x9ac32042` achado em `0x38fd4` do `kernel8.img` real). `mvn -o test` verde (core 1487 + truffle 13), `mvn -o install` local feito. G5 não se aplica. **Destrava a continuação da F11** |
| P17i | **F11** 🟡 PARCIAL (2026-08-20, sessão 5) — retomada após B6.10, fechou B6.11 NA MESMA sessão, QUINTO gap achado: primeira instrução de uma FAMÍLIA nova (`SYS`) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | B6.11 ✅ | Com `CTR_EL0` disponível (B6.10) o boot avançou `0x38fc4`→`0x38fd4` até `LSL X2,X2,X3` (alias `LSLV`) — fechado NA MESMA sessão pela B6.11 acima (não precisou de sessão separada). Com `LSLV`/`LSRV`/`ASRV`/`RORV` disponíveis, o boot avança `0x38fd4`→`0x39000` até `0xd5087620`, confirmado via `aarch64-none-elf-as`/`objdump` (G1, mesmo oráculo real de sempre) como `DC IVAC, X0` (Data Cache Invalidate by VA to PoC). **Diferença de escopo desta vez**: não é uma instrução isolada — é a PRIMEIRA instrução da classe inteira "System instructions" (`SYS`/`SYSL` genérico, `op0=1` no encoding, `Aarch64Decoder` hoje só resolve `MRS`/`MSR` de registrador nomeado via `decodeSystemRegisterId`, nunca viu esse `op0`); `DC`/`IC`/`AT`/`TLBI` são todas variantes do MESMO encoding, distinguidas só por `CRn`/`CRm`/`op1`/`op2` — esperado que mais variantes apareçam à frente antes de qualquer marco de boot (cache maintenance é onipresente em `head.S`/`cache.S`). Não é bug, fora do "Inclui" desta task — nenhuma mudança adicional no arm-jitter nesta sessão além do commit separado de B6.11. `Raspi364BootTest` atualizado (novo endereço/encoding pinado, Javadoc com o histórico completo das 5 sessões); `mvn -o test` verde no virtual-arm-box (88). **Sugestão ao usuário**: sub-task nova no arm-jitter (candidata `B6.12`), desta vez dimensionada para a família `SYS`/`DC`/`IC` inteira de uma vez (não uma instrução por sessão como B6.8-B6.11) — considerar se `DC`/`IC`/`TLBI` podem virar NO-OP aceito dado que a MMU/TLB do `arm-jitter` já são "sempre coerentes" por construção, em vez de reimplementar semântica de cache real (CONFERIR antes de presumir) |
| ~~P17j~~ | ~~**B6.12**~~ ✅ fechada 2026-08-20 — AArch64: manutenção de cache `IC`/`DC` (NOP), QUINTO gap achado pela F11 | `trilha-b-arquiteturas/b6.12-aarch64-cache-maintenance.md` | arm-jitter | B6.6.3 ✅ | Hipótese da F11 CONFIRMADA contra `helper.c` real do QEMU antes de codificar: as 10 operações `IC`/`DC` de manutenção de cache (`IC IALLUIS`/`IALLU`/`IVAU`, `DC IVAC`/`ISW`/`CVAC`/`CSW`/`CVAU`/`CIVAC`/`CISW`) são TODAS `ARM_CP_NOP` no próprio QEMU ("Cache ops: all NOPs since we don't emulate caches"). `Aarch64Decoder#decodeSystemInstructionSys` ganhou uma tabela `{op1,crm,op2}` (`SYSTEM_INSTRUCTION_CACHE_OPS`) cobrindo as 10 combinações reais; `Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP` nova, mesmo ramo vazio de `BARRIER`/`NOP_HINT` no executor. **`DC ZVA` deliberadamente EXCLUÍDA** (única do grupo com efeito observável real — zera memória — já anunciada como indisponível via `DCZID_EL0.DZP=1`, B6.10); continua lançando `unsupported`, testado como regressão negativa com o vetor real `0xd50b7420`. `AT`/`TLBI` per-VA ficam fora (nenhum uso encontrado até este ponto). Corpus real estendido (offsets `0x474`-`0x498`, incl. o vetor literal `DC IVAC, X0`=`0xd5087620` da F11). `mvn -o test` verde (core 1498 + truffle 13, +11), `mvn -o install` local feito. G5 não se aplica (nenhum arquivo 32-bit tocado). **Confirmado nesta sessão** (`mvn -o test` no `virtual-arm-box`, sem editar nada lá): o `Raspi364BootTest` pinado antigo agora FALHA porque a exceção esperada em `DC IVAC` não ocorre mais — prova que o gap fechou e o boot avança além dele; achar o próximo gap e atualizar aquele teste fica para uma sessão própria no `virtual-arm-box` (repo diferente, regra 6). **Destrava a continuação da F11** |
| ~~P17k~~ | ~~**E4**~~ ✅ fechada 2026-08-20 — `AddressSpace64.Wrapping` rejeitava endereços válidos `[0x8000_0000,0xFFFF_FFFF]`, SEXTO achado pela F11 (bug real, não gap de feature) | — (commit direto, sem spec formal — mesmo padrão do fix `ArmCore#enterMemoryAbort` na F3 sessão 5) | arm-jitter | — | Retomando F11 após B6.12, o boot avançava ~157s/1.456.350 fatias e lançava `ArithmeticException: integer overflow` em `AddressSpace64.Wrapping.read32` (`Math.toIntExact`). Isolado com harness de debug temporário (descartado): o endereço SEMPRE caía dentro de `[0x8000_0000,0xFFFF_FFFF]` — dentro dos "4 GiB baixos" que o próprio Javadoc da classe promete suportar, só interpretados incorretamente como `long` positivo `> Integer.MAX_VALUE`. Corrigido truncando com `(int)` (preserva o padrão de bits, convenção "endereço = int sem sinal" do resto de `AddressSpace`) em vez de `Math.toIntExact`; só lança de verdade acima de 4 GiB. Teste de regressão novo `AddressSpace64WrappingTest` (core). `mvn -o test` verde (core+truffle), `mvn -o install` local feito. G5 revalidado nesta sessão: gbaemu ✅, ndsemu ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`, não relacionada), virtual-arm-box ✅ |
| ~~P17l~~ | ~~**F11** sessão 6~~ — achou e fechou o SEXTO gap (bug `Wrapping`, ver E4 acima), levantou a hipótese `TTBR1_EL1` para o SÉTIMO bloqueio (ver B6.13 abaixo, hipótese REFUTADA) | `trilha-f-infra/f11-raspi3-aarch64-machine.md` | virtual-arm-box | E4 ✅ | Com o fix da E4 instalado localmente, o boot avança ainda mais longe e um `write64` (`STP`, `executeLoadStorePair`) tenta traduzir para um PA genuinamente acima de 4 GiB (`0x1_0000_0882`) — desta vez uma rejeição correta do `Wrapping`. Hipótese registrada (não confirmada nesta sessão por orçamento): ausência de `TTBR1_EL1`. `Raspi364BootTest` atualizado, `mvn -o test` verde no virtual-arm-box. Ver **B6.13** abaixo — a hipótese foi investigada na sessão seguinte e REFUTADA com instrumentação real |
| ~~P17m~~ | ~~**B6.13**~~ ✅ fechada 2026-08-20 (por REFUTAÇÃO, não implementação) — hipótese `TTBR1_EL1` da F11 sessão 6 é FALSA | `trilha-b-arquiteturas/b6.13-aarch64-ttbr1-el1.md` | arm-jitter | — | Spec escrita e investigada na MESMA sessão (mesmo padrão de B6.9/B6.12). Instrumentação temporária em 3 pontos (`TranslatingAddressSpace64.leaf`/`translateData`, `Ir64BlockExecutor.executeLoadStorePair`) provou que o `write64` problemático (`STP` em `pc=0x13b8200`, PA `0x100000882`) ocorre com **`mmuEnabled=false`** — a MMU está desligada neste ponto do boot, então NENHUM page-walk acontece (nem TTBR0 nem um TTBR1 hipotético); `SP` (`X31`) já contém `0x100000872` (acima de 4 GiB) ANTES do `STP`, e nem `bit55` nem `bit63` do endereço estão setados (refutando também o detalhe "VA alto/bit 63" da hipótese original). **Causa raiz real**: conteúdo de `SP` corrompido/inesperado nesse ponto — bug de aritmética/carga de registrador anterior, não de tradução de endereço. Fatos de referência do QEMU real conferidos mesmo assim (`target/arm/helper.c`: `TTBR1_EL1` = mesmo `CRn`/`CRm` de `TTBR0_EL1`, só `op2=1`; `aa64_va_parameters`: seleção real usa **bit 55**, não bit 63 como a hipótese da F11 citava) — registrados na spec para uma eventual implementação futura de `TTBR1_EL1` por outro motivo. **`TTBR1_EL1` NÃO foi implementado** (implementar sobre uma causa raiz errada seria trabalho desperdiçado, mesmo precedente de G4.1/`n3dsemu`). `git diff` limpo em ambos os repos ao final (instrumentação toda revertida). `mvn -o test` não precisou rodar de novo (nenhuma mudança de código). **Sugestão ao usuário**: task nova (candidata `B6.14`) para rastrear a origem do valor de `SP` em `pc=0x13b8200` (trace reverso de qual instrução escreveu esse valor) — ver a spec da B6.13 para o "próximo passo" completo |
| P17n | ~~**B6.14**~~ ✅ fechada 2026-08-24 — causa raiz real achada e corrigida: `ADD`/`SUB` (immediate) resolvia `Rd\|SP`/`Rn\|SP` pela FLAG, não pelo ÍNDICE | `trilha-b-arquiteturas/b6.14-aarch64-alu-immediate-sp-bug.md` | arm-jitter | B6.1 ✅ | Investigação por LEITURA DE CÓDIGO (sem reinstrumentar o boot de 1,4M+ fatias da F11) confirmou a causa raiz sugerida pela B6.13 e fechou também a pendência #7 do `tasks/README.md` (achada em 2026-07-25, nunca corrigida): `Ir64BlockExecutor#executeAlu` (forma imediata de `ADD`/`SUB`) checava só a flag `dstIsStackPointer`/`src1IsStackPointer` do decoder, nunca o índice do registrador (`==31`) — diferente de `executeAluExtendedRegister` (B6.3.1), que já fazia a checagem dupla certa. Como `src1IsStackPointer` é sempre `true` nesta forma (classificação correta do encoding — `Rn` é sempre `Rn\|SP`), `operand1` sempre lia `SP`, ignorando `Rn`; e como `dstIsStackPointer=!setFlags`, qualquer `ADD`/`SUB` imediato sem `S` (o mais comum) gravava em `SP` em vez de `Rd` — exatamente o bug que corrompe `SP` ao longo do boot até o `STP` em `pc=0x13b8200` (B6.13). Fix: mesma checagem dupla (`&& op.dst()/op.src1() == ALU_STACK_POINTER_ENCODING`) que `executeAluExtendedRegister` já tinha; cobre os dois backends (interpretado + ASM nativo, que delega ao mesmo executor via D-ASM) sem tocar codegen. 3 testes de regressão novos (encodings conferidos com `aarch64-none-elf-as`/`objdump` real). `mvn -o test` verde (arm-jitter completo) + `install`; G5: gbaemu/ndsemu/n3dsemu/virtual-arm-box ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não relacionada). **Não** re-executou o boot completo da F11 (fora do orçamento desta sessão) — retomar a F11 com este fix instalado fica para uma sessão própria no `virtual-arm-box` |

| P17o | ~~**E7**~~ ✅ fechada 2026-08-26 — A64 JIT: 2 bugs reais faziam exceções de guest escaparem para o host (retomando a F11 pós-B6.14) | `trilha-e-manutencao/e7-a64-jit-guest-exceptions-escaping-to-host.md` | arm-jitter | — | `Ir64BlockCompiler` nunca cercava o bloco nativo com `try/catch` (precedente 32-bit `AsmBlockCompiler` tem desde B4.1.3); `JitRuntime64#execute` nunca protegia o `lift()` de um bloco quente (precedente 32-bit `LIFT_FAULT_CYCLES` desde B4.1.5) — este era a causa REAL do `TRANSLATION_FAULT_L3 em 0x200` que travava o boot JIT da F11 sessão 7. 2 testes de regressão novos, confirmados falhando sem o fix. `mvn -o test` verde + `install`; G5 nos 5 consumidores ✅. Sem release publicado. **Efeito no F11 (sessão 8, medido localmente)**: JIT deixa de lançar QUALQUER exceção e roda o orçamento completo (114s) — mas não alcança o marco, mesmo bloqueio "lento vs. preso" do INTERPRETED. `virtual-arm-box` segue em `1.1.0` (Central) — reabilitar `reachesEarlyconBannerJit` só depois que este fix for publicado (F5) e consumido (F7). Ver **Resultado** na task |

✅ **B9.8.2 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.2-hvc-real.md`, priorizada como próximo
item da escada B9.8, sem dependência pendente — B9.8.1 já fechada) — `HVC` real (A32 e T32):
`ArmFeature.HYPERVISOR_CALL` novo (só `ARMV7A`, gate real confirmado no QEMU: `ENABLE_ARCH_7 &&
!M_PROFILE`, NÃO exige V7VE); `UNDEFINED` em modo `USER`, entra em Hyp mode (`ELR_hyp`=retorno, não
`LR` — B9.8.1 já tinha o banking; `SPSR_hyp`=CPSR antigo; vetor fixo `0x14`) em qualquer outro modo.
Encodings reais via `arm-none-eabi-as -march=armv7ve`. Achado real (corrigido ainda na escrita dos
testes, nunca chegou a produção): `LR` em modo `SUPERVISOR` tem banco PRÓPRIO, diferente do
`LR_usr`/`LR_sys` compartilhado com Hyp mode — testar a preservação de `LR` exige escrevê-lo a
partir de `USER`/`SYSTEM`, não de `SUPERVISOR`. `mvn -o test` verde (2276, +8) + `install`; G5
completo nos 5 consumidores ✅ (virtual-arm-box é o único consumidor A32 real, sem regressão em
nenhum boot). Sem marco de release. Ver **Resultado** na task. **Próximas da escada, qualquer
ordem**: `B9.8.3` (`SMC`), `B9.8.4` (`ERET` A32), `B9.8.5` (`MRS_bank`/`MSR_bank`).

✅ **B9.8.3 fechada 2026-08-27** (`trilha-b-arquiteturas/b9.8.3-smc-real.md`, spec escrita e
executada nesta sessão — próximo item sem dependência pendente da escada B9.8) — `SMC` real (A32 e
T32): `ArmFeature.SECURE_MONITOR_CALL` novo, gate `ARMv6K` (mais antigo que `HVC`/`ARMv7`, herdado
já por `ARM11_MPCORE`); entra em Monitor mode via `LR_mon` (banco normal, SEM branch especial em
`enterException` — ao contrário de `HVC`/`ELR_hyp`), vetor fixo `0x08` (colide com `SWI` no esquema
sem `MVBAR`, documentado, inofensivo hoje). **Bug real de dispatch T32 corrigido (G8)**: `SMC` e
`UDF.W` compartilham o mesmo prefixo de `hi` (`0xF7Fx`) — `decodeUdf` engolia todo `SMC` em `null`
sem chance de outro branch reconhecê-lo; corrigido com fallback para `decodeSmc` quando o `lo` de
`UDF` não bate. Encodings reais via `arm-none-eabi-as -march=armv7ve`. `mvn -o test` verde (+11) +
`install`; G5 completo nos 5 consumidores ✅. Sem marco de release. Ver **Resultado** na task.
**Próximas da escada, qualquer ordem**: `B9.8.4` (`ERET` A32), `B9.8.5` (`MRS_bank`/`MSR_bank`).

## F3 (`virtual-arm-box --machine=raspi1`) — resumo (histórico minucioso movido para `tasks/FILA-HISTORICO.md`)

M1 e M2 ✅ fechados (JIT e INTERPRETED). M3 (shell interativo): a sessão de
`FdtPatcher#withNodeDisabled` (2026-08-17) fechou DOIS bloqueios reais — retry infinito de
`mmc0`/`sdhost` e uma espera síncrona silenciosa de `usb`/`dwc_otg` — desabilitando os nós
`mmc@7e202000`/`usb@7e980000` no `.dtb` via `status = "disabled"`. Com os dois fechados, o boot
chega de novo a `"Run /init as init process"`, mas revelou um TERCEIRO bloqueio.

**Sessão de diagnóstico 1/2 (2026-08-18)**: amostragem barata (sem trace completo) descartou `WFI`
sem IRQ e tempestade de IRQ — a CPU fica `RUNNING`/`SUPERVISOR` o tempo todo. Histograma de PC
mostrou o console parando de crescer bem cedo enquanto a CPU segue ativa presa em ~20 PCs
estáveis. Desmontagem estática levantou a hipótese (NÃO confirmada) de bug de `LDREX`/`STREX`/DACR
no `arm-jitter`.

**Sessão de diagnóstico 2/2 (2026-08-18)**: trace instrução-a-instrução (fast-forward JIT +
`ArmCore#step()`) **DESCARTOU** essa hipótese com evidência dinâmica: o loop real é um corpo
determinístico de 157 instruções em `0xc05b1750`-`0xc05b18c4` (não o código hipotetizado por
disassembly estático) onde TODOS os registradores amostrados (`r0`-`r4`/`r6`/`r9`/`r13`/`r14`)
voltam bit-a-bit idênticos a cada período (20+ repetições); DACR faz round-trip correto
(`0x55`→`0x55`) e `LDREX`/`STREX` sucedem de primeira em 2 call-sites distintos, sem retry — não é
bug de `arm-jitter`. Timer ainda entrega IRQ nesta janela (27 em 100k `runSlice`). Achado colateral:
o "Division by zero" já documentado (`pl011_set_termios`) acontece 2x ao abrir `/dev/console`,
não-fatal; o travamento real é logo depois, dentro do `execve("/init")`, antes de qualquer saída do
PID 1. Próximo passo: identificar a 3ª sub-rotina chamada pelo loop (`0xc02529b4`, ainda não
identificada) e inspecionar CONTEÚDO DE MEMÓRIA (não só registradores) — a condição de saída não
depende de nenhum registrador de propósito geral observado. Ver Javadoc de `Raspi1BootTest`
(`virtual-arm-box`) para o achado completo. `mvn -o test` verde no `virtual-arm-box`; nenhum
arquivo do `arm-jitter` tocado (hipótese de bug real ali agora descartada, não só não confirmada).

**Sessão de diagnóstico 3 (2026-08-18)**: seguiu o passo (b) — inspeção de CONTEÚDO DE MEMÓRIA, não
só registradores, em `[0x0014622d]` (alvo do `strb` de prova) e `[0xc1558c2c]` (contagem do
`rw_semaphore`). Rodando com um orçamento de estagnação maior, o console progrediu MAIS do que
qualquer sessão anterior tinha visto (`thermal thermal_zone0: ...` em kernel time 699s, muito além
do "silêncio" pós-`Run /init` documentado antes em 482s) antes de travar de vez no mesmo loop de 157
instruções. Nos 12 períodos observados (1805 passos), as DUAS memórias vigiadas ficaram **bit-a-bit
estáticas** (`0x00002d00`/`0x00000100`) — fecha a lacuna da sessão anterior: não há progresso
invisível em memória nesses dois pontos. `rw_semaphore` travado com exatamente 1 leitor
(`0x100` = `RWSEM_READER_BIAS`), nunca liberado. Hipótese refinada: o bug provavelmente está no
CHAMADOR do loop (que deveria avançar um contador/endereço entre chamadas e não avança), não na
subrotina chamada em si. Próximo passo recomendado: ler `thread_info->flags`/`preempt_count` (via
`lr = *(TPIDRURO+0x520)`) para checar `TIF_NEED_RESCHED`, ou tracear o PRIMEIRO período (não os
últimos) para ver o valor inicial do "laço externo". `mvn -o test` verde no `virtual-arm-box`;
harness temporário não commitado; nenhum arquivo do `arm-jitter` tocado. F3 segue 🟡 na fila
"ATUAL".

**Sessão de diagnóstico 4 (2026-08-18, `0a745ff`)**: rotina IDENTIFICADA por correspondência
byte-a-byte contra o fonte real do kernel (`arch/arm/lib/uaccess_with_memcpy.c`, baixado via `curl`
direto ao GitHub — `WebFetch`/Bootlin não serve fonte cru) — o loop de 157 passos é
`__copy_to_user_memcpy()`/`pin_page_for_write()`, chamado por `arm_copy_to_user()` durante
`execve("/init")` copiando `argv`/`envp`. Instrumentação de `onMemoryAbort` durante ~5,1M fatias
mostrou **exatamente 1 abort em todo o boot**, no `strb` esperado (`0xc05b189c`) e no endereço
esperado (`0x0014622d`) — prova que o `AP`/DACR do `arm-jitter` FUNCIONA (falta entregue, corrigida,
escrita nunca mais falta), mas `pin_page_for_write()` continua falhando para sempre porque os bits
de contabilidade SOFTWARE da PTE (`pte_young`/`pte_dirty`/`pte_write`) nunca refletem o conserto —
descarta de vez a hipótese de bug genérico de permissão/DACR (3 sessões já tinham testado isso).
Próximo passo: dump da palavra de PTE de `0x0014622d` antes/depois do abort, comparado bit a bit
contra `arch/arm/include/asm/pgtable-2level.h` (mesma técnica de `curl` desta sessão). Ver Javadoc de
`Raspi1BootTest` para o detalhe completo. `mvn -o test` verde (78, 2 skipped); nenhum arquivo do
`arm-jitter` tocado.

**Sessão de diagnóstico 6 (2026-08-18) — tentativa de oráculo QEMU, sem sucesso (achado
negativo)**: antes de partir para a arqueologia de `mm_struct`/maple-tree recomendada pela sessão
5, tentou-se o atalho mais barato: bootar os MESMOS `kernel.img`/`bcm2708-rpi-b.dtb`/
`initramfs.cpio.gz` desta task no `qemu-system-arm -M raspi1ap` (QEMU 8.0.0 instalado) para
comparar. **Não deu para comparar**: o próprio QEMU trava aos 2,5s de tempo de kernel — bem antes
do bloqueio desta task (que só acontece no `execve("/init")`, depois de `mmc`/`usb`) — com um
`external abort on non-linefetch` real dentro do `bcm2835_power_probe` (driver de clock/power),
lacuna própria do modelo `raspi1ap` do QEMU para este kernel 6.18.33, não um travamento do Linux.
Achado negativo registrado (ver Javadoc de `Raspi1BootTest`) para não repetir essa tentativa.
Próximo passo segue sendo o da sessão 5 (dump de VMA via `TPIDRURO`=`current`, confirmado como o
mecanismo certo pelo fonte real do kernel), com uma heurística nova sugerida (procurar
`vm_flags`≈`0x875` por padrão de busca perto do `mm_struct`, em vez de reconstruir a struct campo
a campo sem `vmlinux`/BTF). `mvn -o test` verde; nenhum arquivo do `arm-jitter` tocado.

**Sessão de diagnóstico 5 (2026-08-18, `7355868` arm-jitter + `3ba61f0` virtual-arm-box) — BUG REAL
ISOLADO E CORRIGIDO no `arm-jitter`, M3 ainda NÃO fecha (bloqueio mais estreito revelado logo
depois)**: dump direto da palavra de PTE (`TranslatingAddressSpace#setMmuEnabled(false)`+`read32` =
leitura física crua, walk manual de L1/L2 a partir de `TTBR0`) confirmou `DIRTY=0`/`RDONLY=1` no
endereço travado — bate com a checagem de `pin_page_for_write()`. **Causa raiz**:
`ArmCore#enterMemoryAbort` nunca preenchia `DFSR[11]` (`WnR`, ARM DDI 0406C B3.13.4) — toda falta de
DADOS chegava ao guest indistinguível de uma leitura, mesmo com `accessType()` já disponível no
ponto de chamada. O Linux real usa esse bit para decidir `FAULT_FLAG_WRITE`; sem ele, a falta de
ESCRITA que causou o único abort do boot era tratada como leitura, corrigindo o `AP` de hardware mas
nunca marcando a PTE como `dirty`. **Corrigido** (`ArmCore.java`, aditivo/G3, 2 testes de regressão
novos); `mvn -o test` verde (1369 core+truffle) + `mvn -o install`; G5 revalidado (gbaemu verde,
ndsemu verde, armbox 40/41 — a 1 falha é a mesma pré-existente `Armv7TortureTest`/`VfpRegisters`, não
relacionada). **Efeito medido**: a mesma PTE relida após o fix mostra `DIRTY=1` (fix funcionou) mas
`RDONLY` continua `1` — `pin_page_for_write()` ainda falha, por um motivo mais estreito e ainda não
isolado (candidatos: outro bug real, ou o kernel corretamente recusando escrita numa VMA que ele não
considera `VM_WRITE`, apontando para um problema no setup da pilha do `execve()` em vez do
`arm-jitter`). Ver Javadoc de `Raspi1BootTest` para o próximo passo recomendado (dump da VMA
correspondente). F3 segue 🟡 na fila "ATUAL".

<!-- Histórico minucioso completo da F3 (todas as sessões, começando com o abort storm ARMv6K)
     está em tasks/FILA-HISTORICO.md, seção "F3 (...) — histórico condensado movido de
     FILA-EXECUCAO.md". -->

**Paralelismo permitido nesta onda** (regra 6: repos diferentes, nunca o mesmo checkout):
`P3/P4` (GitHub) ∥ `P2/P5` no começo; depois de P8, `P9` (4 repos) ∥ `P10+` (n3dsemu) ∥
`P15` (virtual-arm-box).

**Bloqueio novo descoberto na G2 (2026-08-15, sessão PR2) — RESOLVIDO 2026-08-15 pela B3.8 do
arm-jitter** (sessão separada, ver índice do `tasks/README.md`): com `svcCreateAddressArbiter`
implementado, `n3dsemu testdata/application.3dsx` progride além da primeira `svc` e travava no
`crt0`/newlib configurando a VFP com um FPSCR que `arm-jitter` rejeitava de propósito
(`FpscrRegister`, decisão nº 3 do épico B3: só IEEE round-to-nearest, sem `RMode`/`FZ`/`LEN`/
`STRIDE`). O usuário escolheu o caminho (1) do leque abaixo (revisitar a decisão de verdade, não
só aceitar-e-ignorar): `FpscrRegister` agora aceita os 32 bits sem lançar, com `RMode` (os 4
modos de arredondamento IEEE 754, via `DirectedFpRounding`) e `FZ` (flush-to-zero) IMPLEMENTADOS
de verdade nos executores VFP interpretados (o oráculo, G1); `LEN`/`STRIDE` são aceitos e
armazenados mas SEM semântica de vetor executada (decisão de escopo explícita da B3.8 — nenhum
consumidor real conhecido usa modo vetor de propósito). `mvn -o install` local do arm-jitter +
`mvn -o test` verde em gbaemu/ndsemu (G5). **Pendente**: uma sessão do n3dsemu precisa confirmar
que `templates/application` progride de verdade além do `crt0` agora — não testado nesta sessão
(fora do repo arm-jitter). Histórico da decisão (não mais em aberto, mantido por rastreabilidade):
três caminhos possíveis haviam sido levantados — (1) revisitar a decisão nº 3 do B3 no arm-jitter
suportando `LEN`/`STRIDE`/`FZ`/`RMode` de verdade — **ESCOLHIDO**; (2) task cirúrgica só para
ACEITAR (ignorar) esses bits sem lançar; (3) mudar o `crt0` do libctru/newlib para não gravar
esses bits — descartado, binário já compilado.

**Investigação 2026-08-16 (sessão n3dsemu, só reconhecimento — não implementou G3):**
confirmado com `mvn -o test`/execução direta de `n3dsemu testdata/application.3dsx
--trace-svc` (arm-jitter `1.0.0` local pós-B3.8, `mvn -o install` já refletido no `.m2`) que o
bloqueio de FPSCR está de fato resolvido — o boot passa inteiro pelo `crt0`/newlib configurando
a VFP sem lançar. **Achou e corrigiu 1 bug real do n3dsemu, não do arm-jitter**: as constantes
`HandleTable.CURRENT_PROCESS_HANDLE`/`CURRENT_THREAD_HANDLE` estavam com os valores
`0xFFFF8000`/`0xFFFF8001` TROCADOS em relação ao header real (`libctru/include/3ds/svc.h`:
`CUR_PROCESS_HANDLE=0xFFFF8001`, `CUR_THREAD_HANDLE=0xFFFF8000`), confirmado também por
`arm-none-eabi-objdump` no `.3dsx` real (`__system_allocateHeaps` passa literalmente
`0xFFFF8001` para `svcGetResourceLimit`, que só aceita handle de processo). Sem a correção,
isso fazia `svcGetResourceLimit` devolver `INVALID_HANDLE` e o guest reagia com
`svcBreak(PANIC)` sozinho, logo após passar da VFP — o que por um instante pareceu "o fix do
B3.8 não bastou", mas na verdade era um bug independente e pré-existente, só exposto porque a
execução finalmente chegou lá. Corrigido em `HandleTable.java` (commit `c939549`), `mvn -o test`
verde (84 testes), `Application3dsxTest` reescrito para documentar a cadeia completa.

**Novo limite real encontrado nesta investigação — FECHADO na sessão de continuação de G2 do
mesmo dia (2026-08-16)**: com o bug de handle corrigido, o boot avançava até `svc 0x39`
(`svcGetResourceLimitLimitValues`), que **não está na lista de SVCs da própria task G2** (só
lista `0x38`/`0x3A`) — mesmo padrão já visto com `svcCreateAddressArbiter` na PR2 (um SVC vizinho
que o crt0 usa e a spec original não previu). O `Main` real finge sucesso e segue (convenção já
estabelecida), então o guest continuava até um `svcControlMemory(MEMOP_ALLOC, addr=0x08000000,
...)` de verdade que devolvia falha para os parâmetros que o crt0 usa — e o guest chamava
`svcBreak(PANIC)` de novo. **Os dois foram corrigidos na sessão de continuação**: (1)
`svcGetResourceLimitLimitValues` implementado com valores de `COMMIT` plausíveis; (2) a causa
raiz real do `ALLOC` falhando não era o teto (embora zero também bastasse) — era um bug
arquitetural preexistente: `MemoryMap.LINEAR_HEAP_BASE`/`NEW_HEAP_BASE` deste projeto tinham os
endereços `0x08000000`/`0x14000000` TROCADOS em relação ao 3dbrew real (`Memory_layout`: o heap
GERAL, sem a flag `LINEAR`, é `0x08000000`; o heap LINEAR de verdade é `0x14000000`), confirmado
via `WebFetch` na wiki antes de corrigir. `n3dsemu testdata/application.3dsx` não panica mais.
Ver índice do `tasks/README.md` (linha G2) para o detalhe completo, incl. o blocker NOVO
encontrado depois (laço de `svcCreateAddressArbiter`). Também achado: `C:\devkitPro\libctru` só
tem `include/`+`lib/*.a` instalados localmente — **sem o código-fonte** que a spec da G3 presume
disponível como oráculo; 3dbrew + desmontagem do `.3dsx` seguem funcionando como substituto, mas
vale avisar quem for executar G3.

**Armazenamento (decidido 2026-08-15):** o `virtual-arm-box` usa **disco virtual em formato
padrão, compatível com outras VMs** — `raw` e **QCOW2**, ambos com leitura e escrita; VDI,
VMDK e VHD/VHDX são atendidos por `qemu-img convert`, sem código nosso. Primeiro controlador:
**PL181 MMCI (SD/MMC)** no `versatilepb`. Task **F10** (P16).

**Achado de ambiente 2026-08-15 — o QEMU 8.0.0 está instalado** (`C:\Program Files\qemu\`,
só binários, sem fonte): `qemu-img` (incl. **`check`**), `qemu-io` e `qemu-system-arm`. É
oráculo externo direto para a F10 (validar imagem QCOW2 que nós escrevemos) e para a F3
(bootar a mesma placa/kernel e comparar log serial). O **código-fonte** do QEMU continua
ausente — quem for transcrever periférico precisa buscá-lo no repositório público.

**[REFINAR] desta onda** (não executar; viram spec nova quando a dependência fechar):
**G6** (ROMs comerciais `.cia`/`.3ds`, bloqueada em dump de `boot9.bin`) e **G7** (trazer o
núcleo Vulkan para o ndsemu — condicional a a G5 dar certo).

- **Nota de ambiente (2026-07-31)**: esta máquina passou a ter GraalVM 25
  (`E:\graalvm-jdk-25.0.3+9.1`) + Visual Studio 2022 com MSVC (`vcvars64.bat`)
  disponíveis — o mesmo ambiente que várias tasks desta fila estavam
  bloqueadas esperando. **A9 PR1** (lib nativa `.dll` com API C, backend
  interpretado) foi executada e fechou ✅ nesta sessão (ver índice do
  `tasks/README.md`) — movida para fora da tabela 🧑 abaixo. **A8**
  (otimizações native-image: PGO/-O3/-march=native/G1, tabela startup/RSS)
  também foi executada e fechou ✅ na MESMA sessão (dependia só de A7, que já
  tinha fechado — a entrada "A7" na tabela 🧑 abaixo estava desatualizada,
  A7 não bloqueia mais nada além de A9 PR2): PGO+`-O3` venceu as 4 métricas e
  virou default do perfil `native` do armbox — ver índice do `tasks/README.md`
  e o README do armbox para a tabela completa. As DEMAIS tasks 🧑 (C7, A9 PR2,
  C10, B4.0.3 item 3, B6.2 aceite #2, B6.6.6) continuam bloqueadas por motivos
  DIFERENTES do ambiente GraalVM+MSVC (validação de gameplay, o bailout SVM do
  Truffle em si — não falta de ambiente —, medição com ROM real, toolchains
  `arm-linux-*`/`aarch64-linux-*`/kernel real) — não presumir que esta nota as
  destrava, conferir a coluna "O que precisa do usuário" de cada uma antes de
  pegar. **Fila automática volta a ficar vazia após A8/A9 PR1** — nenhuma task
  elegível sem prioridade do usuário.

## Épico B6 (AArch64) — histórico condensado

B6.1 até B6.6.5 fecharam entre 2026-07-24 e 2026-07-27 (decoder A64 completo, MMU VMSA64,
modelo de exceção EL0↔EL1, FP/SIMD escalar interpretado+ASM nativo, `translationGeneration`
em `jit64`). **Detalhe completo de cada sub-task: `tasks/FILA-HISTORICO.md`.** Resumo do que
falta:
Backlog sem prioridade definida (não pegar sem o usuário priorizar): B6.4 (backend ASM 64-bit) fechou os 3 PRs (ver histórico abaixo) — só resta,
como pendência EXPLÍCITA fora do escopo de qualquer PR (registrador-cache sem consumidor A64
medido ainda, D0/D-ASM) ou bloqueada no ambiente (bench busybox-aarch64), ver a seção 🧑 abaixo.
B6.5 (FP/SIMD escalar) decomposta em B6.5.1-B6.5.4 (2026-07-26) — **B6.5.1 ✅ fechada
(2026-07-26, ver histórico abaixo)**; **B6.5.2 ✅ fechada (2026-07-27, priorizada pelo usuário,
ver histórico abaixo)** — `Ir64Op`s de FP + executor interpretado; **B6.5.3 ✅ fechada (2026-07-27,
ver histórico acima)** — decoder da classe "Data Processing — Scalar FP" (`bit26=1`); **B6.5.4 ✅
fechada (2026-07-27, ver histórico acima) — fecha o épico B6.5 por completo** (emissão ASM
nativa de FP, `Ir64NativePolicy`/`Ir64BlockCompiler` estendidos, sem meta de performance por
decisão D1). B6.6 (MMU v8 +
hospedeiro `virt64`) decomposta em B6.6.1-B6.6.6 (2026-07-26) — B6.6.1-B6.6.5 já fecharam (ver
histórico acima), **épico quase 100%**; só falta B6.6.6 (hospedeiro `virt64`), que nasceu
bloqueada no usuário (kernel/toolchain `aarch64-linux-*`). **Atualização 2026-08-18 (a pedido do
usuário)**: esse bloqueio de ambiente tem uma rota alternativa mais barata — o repositório
`raspberrypi/firmware` (o mesmo que já deu os assets da F3) publica `kernel8.img`, um kernel
AArch64 pré-compilado real, sem precisar de toolchain nem de GIC/PSCI (o Pi 3 real não usa GIC).
Nova task **F11** (`trilha-f-infra/f11-raspi3-aarch64-machine.md`, repo virtual-arm-box) ataca o
degrau aarch64 por essa rota; **B6.6.6 fica formalmente em espera** (não cancelada) enquanto a
F11 não se esgotar — ver seção 🧑 abaixo (entrada ajustada) e a tabela de Onda 4 para F11.
## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) | **C9** (fastmem ndsemu, `trilha-c-perf/c9-jit-fastmem-ndsemu.md`) |
| ~~A7~~ ✅ fechada 2026-07-27 (medição concluída, resultado misto — ver índice do `tasks/README.md`); entrada mantida só para registrar que **A9 PR2** segue bloqueada pelo PRÓPRIO resultado da A7 (bailout SVM não fechou), não por falta de ambiente | `trilha-a-truffle/a7-native-image-revalidacao.md` | — (fechada; causa raiz do bailout SVM precisa de sessão de modelo forte dedicada) | **A9 PR2** só quando o bailout SVM for corrigido |
| ~~A9 PR1~~ ✅ fechada 2026-07-31 (ambiente GraalVM+MSVC ficou disponível nesta máquina — ver nota abaixo) | `trilha-a-truffle/a9-native-shared-library.md` | — | A9 PR2 segue bloqueada em A7 (bailout SVM do Truffle não fechou) |
| ~~A8~~ ✅ fechada 2026-07-31 (mesma sessão desta nota de ambiente — task mecânica de build+medição, não precisava de validação humana além do ambiente GraalVM+MSVC já confirmado disponível) | `trilha-a-truffle/a8-native-image-otimizacoes.md` | — | PGO+`-O3` promovido a default do perfil `native` do armbox — ver índice do `tasks/README.md` |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real | fecha de vez a C10 |
| ~~B4.0.3 item 3~~ ✅ fechada 2026-08-27 — busybox estático Thumb-2 (armbox) | `trilha-b-arquiteturas/b4.0.3-armbox-validar-thumb2-completo.md` | — (WSL2+Ubuntu resolveu o toolchain `arm-linux-*`; ver Onda 5/histórico) | fechou B4.0.3 por completo; **destrava B4.0.5** |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2, item 4) | Ambiente RESOLVIDO 2026-08-27 (WSL2+Ubuntu, mesmo toolchain do B4.0.3) — o bloqueio real agora é um **gap de decode do arm-jitter**: `LDR`/`STR` SIMD&FP registrador-imediato (`STR Q0,[x0]`, ARM DDI 0487 C4.1.5) não implementado, musl usa isso em `memcpy`/`memset`. Precisa de sessão implementando essa família no `Aarch64Decoder`/`Ir64` (candidata de decode A64, não mais bloqueio de ambiente) | fecha B6.2 por completo (aceite #1 já fechado 2026-07-24), o aceite agregado do B6.3 e o bench do PR3 de B6.4 |
| ~~B6.6.6~~ **EM ESPERA** (não cancelada) desde 2026-08-18 — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Toolchain aarch64 RESOLVIDO 2026-08-27 (WSL2+Ubuntu) — falta só um **kernel arm64 mainline real** (pré-compilado ou buildado) + idealmente um initramfs busybox aarch64 (já temos um estático em `armbox/testdata/busybox-aarch64`, mas trava no mesmo gap SIMD&FP do B6.2 se o kernel/init o exercitar). GICv2/GICv3/PSCI/DTB continuam mais complexos que o precedente B4.1.5 — reservar sessão maior. Feature-completo no arm-jitter desde B6.6.7 | fecha o épico B6.6 por completo — só falta kernel real (+ o gap SIMD&FP acima, se o initramfs precisar dele) |

## Fila de BUGS de compat (trilha D) — sessões separadas da fila principal

Backend-independentes (confirmado 2026-07-16: INTERPRETED e ASM idênticos em
sintoma e velocidade no GBA — a atribuição antiga ao ASM está REVOGADA).
D2/D3/D4 já fechadas (ver índice).

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D6** — BIOS lenta/interrompida | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| ~~D5~~ 🟡 fix implementado 2026-07-17 (ndsemu `e9bebfd`: fim de canal one-shot do SPU agora corre no tempo emulado — o título esperava a sequência one-shot "terminar") — **falta só validação do usuário na GUI** (título→menu→novo jogo) | — | 🧑 usuário |
| ~~D5/Buneary~~ ✅ FECHADA 2026-07-18 (arm-jitter `65a9a66`, **user-validada na GUI**): bloco JIT stale da troca de overlay — invalidação só olhava o endereço-base da escrita, e um bloco THUMB de 1 instrução em X≡2 (mod 4) escapava da cópia em words; o `b` do overlay 77 (título) executava dentro do overlay 73 e pulava o init da animação. Fix = invalidação por intervalo da escrita; A/B no ROM real + validação live. Diagnósticos removidos do ndsemu (`2295157`) | — | — |
| (sem task, ORÁCULO APLICADO 2026-07-19) Platinum billboard do char invisível = DIVERGÊNCIA DE ALOCAÇÃO de VRAM de textura (byte-diff bank A ndsemu×melonDS: 0x0-0x5000 idêntico, 0x5000-0xB800 diverge = texturas do char só no melonDS onde o billboard lê, 0x10000+ = lixo só no ndsemu = textura obsoleta não liberada). Upstream tex-manager, não render nem fix de fase (6f72757 correto). Multi-sessão: tracear o alocador de textura do jogo. Detalhes na memória `ndsemu-game-compat` | ndsemu | ⚠️ MODELO FORTE |
| **PROJETO WiFi (multi-sessão, pedido do usuário 2026-07-22)** — Fase 1 (fundação de hardware `wifi/WifiController`) SHIPPED (ndsemu `0c7d17e`): register file + BB/RF + WiFi RAM + timer; self-test do NitroWM passa, init de HW roda até o fim. Continue do Platinum AINDA erra (falta handshake WM ARM9↔ARM7 "ready"). Roadmap (Fase 2 ready→Continue funciona; Fase 3-5 RX/TX + AP falso + bridge de rede real p/ GTS via DNS alternativo) na memória `ndsemu-wifi-stack` | ndsemu | ⚠️ MODELO FORTE |
| (sem task, NOVA 2026-07-17) Platinum NÃO boota em INTERPRETED — ARM9 preso no handshake IPCSYNC do `PXI_Init` (`0x020C640C`) desde o frame 0; em ASM boota. Achado colateral da D5 | ndsemu; race de boot cross-CPU backend-dependente | ⚠️ MODELO FORTE |
| (sem task) Divergência ASM×interp no JUS | ver pendência 6 do tasks/README | ⚠️ MODELO FORTE |

