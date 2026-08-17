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
| P12 | **G3** — IPC + serviços (`srv:`/`APT`/`hid`/`fs`/`gsp` mínimo) | `trilha-g-3ds/g3-servicos-srv-apt-hid-fs.md` | n3dsemu | G2 | `C:\devkitPro\libctru` só tem os headers instalados localmente, **NÃO o código-fonte** (achado da investigação 2026-08-16 — `find`/`ls` em `C:\devkitPro\libctru` mostra só `include/`+`lib/*.a`, sem `source/`; a task G3 presume fonte disponível, revisar antes de executar) — 3dbrew + o próprio `.3dsx` desmontado (`arm-none-eabi-objdump`) seguem como oráculo; **bloqueio FPSCR CONFIRMADO RESOLVIDO 2026-08-16** pela B3.8 (ver nota abaixo); **os 2 gaps de G2 achados na investigação 2026-08-16 foram FECHADOS numa sessão de continuação de G2 no mesmo dia** (SVC `0x39`/`svcGetResourceLimitLimitValues` implementado + bug real de endereços do heap geral/linear trocados corrigido — `n3dsemu testdata/application.3dsx` não panica mais, ver índice do `tasks/README.md`); **AINDA NÃO PRONTA PARA EXECUTAR** — a mesma sessão achou um blocker NOVO e diferente: com os dois heaps commitados, o backend JIT entra num laço indefinido chamando `svcCreateAddressArbiter` (`0x21`) sempre no mesmo PC (confirmado até 200 mil fatias sem sair sozinho) — nenhum `svcConnectToPort`/serviço de verdade é alcançado por trás desse laço. Provavelmente precisa de sincronização/escalonador cooperativo reagindo de verdade a esse padrão (possível G2.2) antes de G3 fazer sentido; INTERPRETED/CHECK progridem bem mais devagar por fatia que o JIT dentro do mesmo orçamento, então o aceite "JIT e `--interp` idênticos" da G2 também não foi revalidado ponta a ponta |
| P13 | **G4** — janela Vulkan apresentando os framebuffers | `trilha-g-3ds/g4-vulkan-apresentacao.md` | n3dsemu | G3 | primeira imagem na tela |
| P14 | **G5** — PICA200 (command list + shader + TEV) | `trilha-g-3ds/g5-pica200-render.md` | n3dsemu | G4 | LONGA, 3 PRs; aceite é **só** o `simple_tri` |
| P15 | **F3** — `virtual-arm-box --machine=raspi1` | `trilha-f-infra/f3-raspi1-machine.md` | virtual-arm-box | F2 | 🟡 PARCIAL (2026-08-17, sessão de extensão do `FdtPatcher`, ver detalhe abaixo) — **M1 continua fechado**; abort storm do `CPSR.E` (sessão anterior) e o panic de VFS/root-mount (`FdtPatcher` sem `linux,initrd-start`/`-end`, agora implementados) ambos RESOLVIDOS; **M2 ainda NÃO fecha**: boot avança até `/init` executar de verdade, mas `Internal error: Oops - undefined instruction` em `v6_clear_user_highpage_aliasing` mata o `init` — opcode decodificado é `MCRR p15,0,r2,r3,c6`, provável lacuna de DECODE de `MCRR`/`MRRC` no `arm-jitter` A32 (nunca exercitado por gbaemu/ndsemu/armbox); LONGA, 3 marcos |
| P16 | **F10** — disco virtual `raw`+QCOW2 (r/w) + PL181 MMCI/SD | `trilha-f-infra/f10-disco-virtual-raw-qcow2.md` | virtual-arm-box | F2 | LONGA, 3 PRs; **mesmo repo que P15 — serializar com a F3**, nunca em paralelo (regra 6) |

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

**F3 — sessão de extensão do `FdtPatcher` (2026-08-17) — `linux,initrd-start`/`linux,initrd-end`
IMPLEMENTADOS, M2 ainda NÃO fecha (bloqueio novo, bem mais tardio no boot, causa raiz NARROWED a
um opcode específico)**:

1. Seguiu o próximo passo recomendado pela sessão anterior: `FdtPatcher` (`virtual-arm-box`) ganhou
   `withInitrdRange`/`withNewProperty` — diferente de `withBootargs`/`withMemorySize` (que só
   sobrescrevem propriedades JÁ existentes), o método novo CRIA `/chosen/linux,initrd-start`/
   `linux,initrd-end` (ausentes no `.dtb` cru), reaproveitando o nome no bloco de strings se já
   ocorrer lá ou anexando um novo. `Bcm2835Machine#create` aplica o patch com
   `INITRD_LOAD_ADDR`/`INITRD_LOAD_ADDR + initramfs.length`. 2 testes novos em `FdtPatcherTest`.
2. **Efeito confirmado no boot JIT**: o `Kernel panic - VFS: Unable to mount root fs` da sessão
   anterior desaparece por completo — o kernel monta o initramfs e chega a executar `/init` de
   verdade (`Run /init as init process` no console, ~8min reais) — o mais longe que este
   repositório já chegou no boot do raspi1.
3. **M2 ainda NÃO fecha — bloqueio NOVO, mais tardio que qualquer sessão anterior**: logo depois de
   `Run /init as init process`, `Internal error: Oops - undefined instruction` em
   `v6_clear_user_highpage_aliasing+0x58/0x104` (chamado por `handle_mm_fault` tratando uma falta
   de página do `execve()` do `/init`) mata o processo `init` (`Attempted to kill init!`). Opcode
   decodificado a partir do dump `Code:` do Oops (`ec432f06`): `MCRR p15,0,r2,r3,c6` (encoding A1
   padrão de `MCRR`/`MRRC`, cond=AL, L=0→MCRR, Rt2=r3, Rt=r2, coproc=p15, opc1=0, CRm=c6) — uma
   transferência DUPLA de registrador para coprocessador, diferente do `MCR`/`MRC` de registrador
   único que `Cp15VmsaCoprocessor`/`Bcm2835Cp15Extras` já tratam. Provável lacuna de DECODE no
   `arm-jitter` A32 (não só de despacho do `CoprocessorBus`) — nem gbaemu (ARMv4T), nem ndsemu
   (ARMv5TE), nem armbox (user-mode) jamais exercitaram `MCRR`/`MRRC`. Não investigado além da
   decodificação do opcode (fora do orçamento desta sessão).
4. **Achado colateral, não fatal**: duas ocorrências de `Division by zero in kernel` em
   `pl011_set_termios` (`div64_u64`) ao abrir `/dev/console`, bem antes do Oops de `init` — o
   kernel trata como exceção não fatal e o boot segue normalmente logo depois. Possível causa: um
   campo de clock/baud que `Pl011Uart`/mailbox devolve como `0` onde o driver espera um divisor
   não-zero — só observado, não investigado.
5. **Próximo passo recomendado**: (a) confirmar no decoder A32 do `arm-jitter` se `MCRR`/`MRRC` têm
   `case` próprio ou caem em UNDEFINED por ausência de decode (mais provável); se for lacuna de
   decode, é uma task nova do `arm-jitter` (mesmo precedente de escopo do BE8/B1.8); (b) só depois
   de `MCRR`/`MRRC` decodificarem, repetir o boot e ver se `execve("/init")` conclui; (c)
   investigar a divisão por zero do PL011 se voltar a aparecer de forma fatal. Backend INTERPRETED
   não foi re-executado nesta sessão (orçamento).
6. `mvn -o test` verde no `virtual-arm-box` (68 testes, 4 skipped = M2×2+M3×2, motivo do
   `@Disabled` atualizado); `VersatilePbBootTest` continua verde. Nenhum arquivo do `arm-jitter`
   tocado nesta sessão. F3 permanece 🟡 na fila "ATUAL".

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
