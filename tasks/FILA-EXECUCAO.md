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

## Onda 3 — fila ATUAL (executar de cima para baixo)

Mesmas regras de sempre: 1 sessão = 1 task (ou 1 PR); **ordem dentro do mesmo
repo é obrigatória**; a coluna Repo mostra o que pode andar em paralelo (repos
diferentes apenas).

| # | Task | Arquivo | Repo | Depende de | Nota de sessão |
|---|------|---------|------|-----------|----------------|
| P1 | **B4.0.5** — armbox fase 3: fork/execve/pipes/wait | `trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md` | armbox | B4.0.3 | ainda bloqueada — B4.0.3 fechou parcial, falta o busybox thumb2 (ver 🧑 abaixo) que essa task precisa como corpus |

Backlog sem prioridade definida (não pegar sem o usuário priorizar): B4.1.1+
(softmmu/full-system, RFC-SOFTMMU) e B6.4+ (AArch64 — escopo fechado no épico,
mas spec própria ainda não escrita; ver `b6-aarch64.md`).

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
| **A7** — revalidação native-image pós-A6 | `trilha-a-truffle/a7-native-image-revalidacao.md` | Máquina com GraalVM 25 (`E:\graalvm-jdk-25.0.3+9.1`) + MSVC (`vcvars64.bat`) | **A8** (otimizações native-image) e **A9 PR2** |
| **A9 PR1** — lib nativa `.dll`/`.so` com API C, backend interpretado | `trilha-a-truffle/a9-native-shared-library.md` | Mesmo ambiente GraalVM+MSVC (+ `cl.exe` p/ o smoke test em C); pode rodar a qualquer momento | A9 PR2 (após A7) |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real | fecha de vez a C10 |
| **B4.0.3 item 3** — busybox estático Thumb-2 (armbox) | `trilha-b-arquiteturas/b4.0.3-armbox-validar-thumb2-completo.md` | Toolchain `arm-linux-*` real (musl/glibc) — ex. WSL com distro configurada + build tools, ou um cross-toolchain Windows-hosted; o musl.cc é ELF Linux (não roda em MSYS2) e o devkitARM instalado é bare-metal | fecha B4.0.3 por completo e destrava **B4.0.5** |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2, item 4) | Fonte confiável de busybox estático arm64/aarch64 real (busybox.net só publica `armv8l`, que é ARM 32-bit — ISA errada) OU um toolchain `aarch64-linux-*` (musl/glibc) para compilar da fonte, já que o devkitA64 instalado é bare-metal (`aarch64-none-elf`) | fecha B6.2 por completo (aceite #1, `hello-aarch64.elf`, já fechado 2026-07-24) **e também o aceite agregado do épico B6.3** ("`busybox sh -c` completo no armbox64") depois que as 4 sub-tasks B6.3.1-B6.3.4 fecharem — mesmo bloqueio, um só ambiente resolve os dois |

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
