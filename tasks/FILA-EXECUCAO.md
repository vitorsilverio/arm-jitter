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
  **(4) Quarto achado, do lado do `linuxbox`**: o `busybox-armv5l` de `testdata/` é um binário
  **hard-float** (`e_flags` traz `EF_ARM_ABI_FLOAT_HARD`) e usa prólogos VFP reais
  (`VPUSH {d8-d14}`) não gateados por `HWCAP_VFP` — sem VFP o PID 1 tomava SIGILL. O hospedeiro
  passou a montar o core com `ARMV5TE + ArmFeature.VFPV2` (+ `VfpDecoder`), que é o **ARM926EJ-S
  com a VFP9-S opcional**, exatamente o que o `-cpu arm926` do QEMU modela. Só mudança de
  `linuxbox`, nenhum preset novo no arm-jitter. (O kernel segue imprimindo `VFP support: not
  present` porque a sondagem lê `FPSID` via `MRC p10,7,...` e o `VfpDecoder` só decodifica
  `FPSCR` — sem consequência prática aqui, num ARMv5 não há `CPACR` gateando CP10/CP11.)
  **Armadilha registrada** (custou uma rodada de teste): o FIFO de recepção do PL011 tem 16
  posições e descarta o excedente como hardware real — digitar uma linha inteira de uma vez faz o
  guest receber só os 16 primeiros bytes, sem o `\n`. O teste digita um byte por lote de fatias.
  Testes novos no arm-jitter: `StandardIrBlockLifterTest` (2, acima), `Cp15VmsaCoprocessorTest` (faces de TLB separadas encaminhadas e
  independentes; `ModeChangeListener` sincronizando privilégio — escrita de usuário em página
  `AP=01` falta com `SECTION_PERMISSION` e não chega à memória) e `TranslatingAddressSpaceTest`
  (independência real das duas faces + só a de instrução bumpa a geração). `Main` do `linuxbox`
  ganhou drenagem não bloqueante de `stdin` para o UART0 (shell realmente usável na linha de
  comando). `mvn -o test` verde em arm-jitter (1327 core + 13 truffle), linuxbox, gbaemu (244),
  ndsemu (183) e armbox (41) — G5 se aplica (`ArmCore`/CP15/MMU/lifter são compartilhados).
  **Desvios que PERMANECEM** (não são bugs, são falta de toolchain — ver `testdata/README.md`):
  kernel Debian 3.2 pré-compilado + ATAGs em vez de `versatile_defconfig` mainline + DTB, e
  ARM926EJ-S/ARMv5TE em vez do ARM1176/ARMv6K da RFC decisão 2. Fechar isso exige um
  `arm-linux-gnueabihf-*` real ou WSL — mesmo bloqueio de B4.0.3/B6.2/B6.6.6.
- **B4.1.5 (sessão 1)** 🟡 PARCIAL (2026-07-26, quinta e última sub-task do épico B4.1/MMU-softmmu — repo
  novo `linuxbox`): hospedeiro `versatilepb`-like completo (RAM 128MiB via `PagedAddressSpace`
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
  `mvn -o test` verde (linuxbox + arm-jitter + gbaemu + ndsemu + armbox, G5 aplicável). **Fecha
  o épico B4.1 apenas parcialmente** — pendência clara para retomar: isolar a causa do loop de
  abort na página de vetores, ou obter toolchain `arm-linux-gnueabihf-*`/WSL para fechar o
  desvio ARM1176/ARMv6K+DT da RFC por completo.

## Onda 3 — fila ATUAL (executar de cima para baixo)

Mesmas regras de sempre: 1 sessão = 1 task (ou 1 PR); **ordem dentro do mesmo
repo é obrigatória**; a coluna Repo mostra o que pode andar em paralelo (repos
diferentes apenas).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| P1 | **B4.0.5** — armbox fase 3: fork/execve/pipes/wait | `trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md` | armbox | B4.0.3 | ainda bloqueada — B4.0.3 fechou parcial, falta o busybox thumb2 (ver 🧑 abaixo) que essa task precisa como corpus |

## Onda 4 — priorizada pelo usuário em 2026-08-15 (executar de cima para baixo)

Cinco frentes pedidas pelo usuário: rename do `linuxbox`, emulador de 3DS, licença BSD,
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
público, criado e vinculado como `origin` do checkout local `linuxbox/` (ainda não renomeado
localmente, o diretório físico e o histórico git continuam como `linuxbox` até a F2 rodar), push
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
| P5 | **F2** — rename `linuxbox` → `virtual-arm-box` + abstração `Machine` | `trilha-f-infra/f2-rename-virtual-arm-box.md` | virtual-arm-box | F1 | também mexe na documentação do arm-jitter |
| P6 | **F4** — preparar o arm-jitter 1.0.0 | `trilha-f-infra/f4-arm-jitter-1.0.0-escopo.md` | arm-jitter | F1 | ⚠️ **a partir daqui os consumidores ficam quebrados até a F7** — agende as duas juntas |
| P7 | **F5** — publicar no Maven Central | `trilha-f-infra/f5-maven-central-publicacao.md` | arm-jitter | F4 | 🧑 passos 1-3 (conta Central, DNS TXT, chave GPG) são do usuário |
| P8 | **F7** — consumidores no Central, sem `asm` declarado | `trilha-f-infra/f7-consumidores-central.md` | 4 consumidores | F5 | fecha a janela aberta pela F6; **este é o G5 completo da onda** |
| P9 | **F6** — GitHub Actions (CI + release por tag) | `trilha-f-infra/f6-github-actions-pipeline.md` | 4 repos | F5 | o trabalho real é fazer os testes que dependem de asset local serem *skipped* |
| P10 | **G1** — `n3dsemu`: esqueleto + loader `.3dsx` + primeira `svc` | `trilha-g-3ds/g1-esqueleto-n3dsemu.md` | n3dsemu (novo) | F7 | **leia `trilha-g-3ds/RFC-N3DSEMU.md` inteira antes** |
| P11 | **G2** — kernel Horizon em HLE | `trilha-g-3ds/g2-kernel-hle-svc.md` | n3dsemu | G1 | grande; 2 PRs se precisar |
| P12 | **G3** — IPC + serviços (`srv:`/`APT`/`hid`/`fs`/`gsp` mínimo) | `trilha-g-3ds/g3-servicos-srv-apt-hid-fs.md` | n3dsemu | G2 | `C:\devkitPro\libctru` tem o fonte do cliente — é o oráculo |
| P13 | **G4** — janela Vulkan apresentando os framebuffers | `trilha-g-3ds/g4-vulkan-apresentacao.md` | n3dsemu | G3 | primeira imagem na tela |
| P14 | **G5** — PICA200 (command list + shader + TEV) | `trilha-g-3ds/g5-pica200-render.md` | n3dsemu | G4 | LONGA, 3 PRs; aceite é **só** o `simple_tri` |
| P15 | **F3** — `virtual-arm-box --machine=raspi1` | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box | F2 | LONGA, 3 marcos; **pode andar em paralelo** com P10-P14 (repo diferente) |
| P16 | **F10** — disco virtual `raw`+QCOW2 (r/w) + PL181 MMCI/SD | `trilha-f-infra/f10-disco-virtual-raw-qcow2.md` | virtual-arm-box | F2 | LONGA, 3 PRs; **mesmo repo que P15 — serializar com a F3**, nunca em paralelo (regra 6) |

**Paralelismo permitido nesta onda** (regra 6: repos diferentes, nunca o mesmo checkout):
`P3/P4` (GitHub) ∥ `P2/P5` no começo; depois de P8, `P9` (4 repos) ∥ `P10+` (n3dsemu) ∥
`P15` (virtual-arm-box).

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
histórico acima), **épico quase 100%**; só falta B6.6.6 (hospedeiro `virt64`), que já nasce
bloqueada no usuário, ver seção 🧑 abaixo. Ver `b6-aarch64.md` para o detalhe completo de cada
sub-task.

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

## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) | **C9** (fastmem ndsemu, `trilha-c-perf/c9-jit-fastmem-ndsemu.md`) |
| ~~A7~~ ✅ fechada 2026-07-27 (medição concluída, resultado misto — ver índice do `tasks/README.md`); entrada mantida só para registrar que **A9 PR2** segue bloqueada pelo PRÓPRIO resultado da A7 (bailout SVM não fechou), não por falta de ambiente | `trilha-a-truffle/a7-native-image-revalidacao.md` | — (fechada; causa raiz do bailout SVM precisa de sessão de modelo forte dedicada) | **A9 PR2** só quando o bailout SVM for corrigido |
| ~~A9 PR1~~ ✅ fechada 2026-07-31 (ambiente GraalVM+MSVC ficou disponível nesta máquina — ver nota abaixo) | `trilha-a-truffle/a9-native-shared-library.md` | — | A9 PR2 segue bloqueada em A7 (bailout SVM do Truffle não fechou) |
| ~~A8~~ ✅ fechada 2026-07-31 (mesma sessão desta nota de ambiente — task mecânica de build+medição, não precisava de validação humana além do ambiente GraalVM+MSVC já confirmado disponível) | `trilha-a-truffle/a8-native-image-otimizacoes.md` | — | PGO+`-O3` promovido a default do perfil `native` do armbox — ver índice do `tasks/README.md` |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real | fecha de vez a C10 |
| **B4.0.3 item 3** — busybox estático Thumb-2 (armbox) | `trilha-b-arquiteturas/b4.0.3-armbox-validar-thumb2-completo.md` | Toolchain `arm-linux-*` real (musl/glibc) — ex. WSL com distro configurada + build tools, ou um cross-toolchain Windows-hosted; o musl.cc é ELF Linux (não roda em MSYS2) e o devkitARM instalado é bare-metal | fecha B4.0.3 por completo e destrava **B4.0.5** |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2, item 4) | Fonte confiável de busybox estático arm64/aarch64 real (busybox.net só publica `armv8l`, que é ARM 32-bit — ISA errada) OU um toolchain `aarch64-linux-*` (musl/glibc) para compilar da fonte, já que o devkitA64 instalado é bare-metal (`aarch64-none-elf`) | fecha B6.2 por completo (aceite #1, `hello-aarch64.elf`, já fechado 2026-07-24), **o aceite agregado do épico B6.3** ("`busybox sh -c` completo no armbox64", já com as 4 sub-tasks B6.3.1-B6.3.4 fechadas) **e o bench "busybox ≥3× interpretador" do PR3 de B6.4** (codegen fechado 2026-07-26, só falta medir) — mesmo bloqueio, um só ambiente resolve os três |
| **B6.6.6** — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Kernel arm64 mainline real (pré-compilado ou toolchain para buildar) + idealmente um initramfs busybox aarch64 real — mesmo bloqueio de toolchain/binário de B6.2 aceite #2/B4.0.3 item 3, um só ambiente resolve os três; adicionalmente, GICv2/GICv3/PSCI/DTB são substancialmente mais complexos que os periféricos versatilepb do precedente B4.1.5, reservar tempo de sessão maior | fecha o épico B6.6 por completo (depende de B6.6.1-B6.6.5, rodada de spec 2026-07-26, ver `b6-aarch64.md`) |

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
