# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/);
o projeto segue [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [1.2.0] — 2026-08-26

### Adicionado
- **`Gdb64Server`**: stub do protocolo de série remota GDB para {@code Aarch64Core} — irmão A64
  do `GdbServer` (ARM32) já existente, mesma capacidade (ler/escrever registradores e memória,
  breakpoints em PC, watchpoints de escrita, step/continue), layout de registrador `g`/`p`/`P`
  no formato AArch64 real (`x0`-`x30`/`sp`/`pc`/`cpsr`) e endereços de 64 bits em `m`/`M`/`Z`/`z`.
  Classe nova, não uma generalização do `GdbServer` existente — os dois mundos de 32/64 bits já
  são independentes por desenho no resto do arm-jitter.

### Corrigido
- `GdbServer`/`Gdb64Server`: um acesso de memória (`m`/`M`) a um endereço fora da faixa mapeada
  do hospedeiro agora responde `E01` ao gdb em vez de deixar a exceção do hospedeiro (ex. um
  segfault simulado de guest) atravessar e derrubar a sessão de depuração inteira.

## [1.1.0] — 2026-08-23

Marco de cobertura de ISA (`docs/COBERTURA-ISA.md`): **global 53% → 59%**, **A64 18% → 27%**
desde o `1.0.0` — dispara release conforme a regra do `tasks/README.md`.

### Adicionado
- **EL2/EL3 completos** (épico B10): estado de exceção generalizado para os 4 níveis
  (`Aarch64ExceptionLevel`), registradores de sistema de EL2 e EL3, `HVC` (entra em EL2) e
  `SMC` (entra em EL3) reais com a árvore de decisão do manual, `AT` (`S1E0*`/`S1E1*` e
  stage-2 `S12E*`, com `Stage2TranslatingAddressSpace64` novo), `TLBI` EL2/EL3 (decode) e
  registradores de debug (`op0=2`, armazenamento). `S1E2*`/`S1E3*` (formas EL2/EL3 puras de
  `AT`) ficam de fora, bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos sem consumidor real hoje.
- **A64 — inteiro e branch/system**: load/store escalar restante (`STNP`/`LDNP`/`LDPSW`/
  `PRFM`/`LDTR`/`STTR`/`LDXP`/`STXP`/`LDAR`/`STLR`/`CAS`/`CASP`), aritmética/bit restante
  (`ADC`/`SBC`/`EXTR`/`RBIT`/`REV*`/`CLZ`/`CLS`/`CNT`/`SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/
  `SMULH`/`UMULH`/`RMIF`/`SETF8`/`SETF16`/`CFINV`/`XAFLAG`/`AXFLAG`), e
  `WFET`/`WFIT`/`CLREX`/`DSB(nXS)`/`SB`/`BRK`/`HLT`/`MSR` (imediato) restantes.
- **A64 — FP escalar**: aritmética restante (`FNMUL`/`FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`FSQRT`/
  `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB`) e comparação/seleção/conversão (`FCSEL`/`FCCMP(E)`/
  `FRINTx`/`SCVTF`/`UCVTF`/`FCVTxS`/`FCVTxU`/`FMOV` registrador-geral).
- **A32/T16 (ARMv6)**: DSP/media (`SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`/`SMMLA{R}`/
  `SMMLS{R}` + `UDF`) e T16 genuínos (`SETEND`, `CPS` A/R-profile, `REV`/`REV16`/`REVSH`,
  `SXTH`/`SXTB`/`UXTH`/`UXTB`).
- **VFP**: `VNMLA`/`VNMLS`, `VMOV_to_gp`/`VMOV_from_gp` (word), `VMOV_64_sp`,
  `VCVT_fix_{sp,dp}`.
- Espaço incondicional (`cond==0b1111`) agora recusa (`UNIMPLEMENTED`) em vez de colidir
  silenciosamente com o dispatch condicional (invariante **G8** novo).
- `docs/COBERTURA-ISA.md`: tabela de cobertura de ISA gerada por medição (`decodetree` do
  QEMU sondado contra o decoder real), regenerável via `./gerar-cobertura-isa.sh`.

### Corrigido
- Múltiplas colisões de decode reais que faziam encodings desconhecidos serem confundidos
  silenciosamente com outra instrução em vez de recusados (ver `docs/COBERTURA-ISA.md` e as
  tasks `E6`/`B8.1`-`B8.5`/`B9.1`/`B10.6` para o detalhe de cada uma).

### Conhecido / fora de escopo desta versão
- `B10.6b`/`B10.6c` (`AT` formas EL2/EL3 puras) — bloqueadas em `TTBR0_EL2`/`TTBR0_EL3` novos.
- T32 (Thumb-2) ainda com 58 lacunas conhecidas (`B9.7`).
- AdvSIMD A64 (NEON) ainda em 0% — maior bloco restante (~690 células).
- Hospedeiro full-system AArch64 (`virt64`) ainda não fecha (`B6.6.6`) — bloqueado em
  toolchain/kernel `aarch64-linux-*` reais.

## [1.0.0] — 2026-08-15

Primeira versão publicada. Consolida o que já estava em produção nos emuladores
`gbaemu` e `ndsemu`.

### Adicionado
- Pipeline `cache → decode → lift IR → otimizar → emit` com três backends:
  `INTERPRETED_IR` (oráculo/debug), `JVM_BYTECODE` (ASM, default recomendado,
  tiered com tier frio interpretado + tier quente compilado, fallback `PER_OP`,
  compilação em pool de threads, execução condicional nativa, shifted-register
  nativo, register cache em locals, inline cache de 32K, encadeamento de blocos
  e superblocos de loop) e `TRUFFLE` (módulo opcional `arm-jitter-truffle`,
  compila de verdade em JVM sob JBR+Unchained/GraalVM).
- Arquiteturas guest de 32 bits: ARMv4T (GBA, produção), ARMv5TE (NDS, produção),
  ARMv6K, `ARMV6K_THUMB2` (Thumb-2), ARMv7-A + VFPv2 e o perfil M
  (ARMv6-M/ARMv7-M, `ExceptionModel` plugável com NVIC/VTOR/SysTick e
  semihosting) — todos completos e validados com binários ELF reais (torture
  handwritten e `gcc` real) no `armbox`.
- MMU/softmmu de 32 bits (épico B4.1): page-walk short-descriptor VMSA,
  domínios/AP, aborts precisos (FAR/FSR) nos três motores de execução, geração
  de tradução ciente do `BlockCache`/inline cache; validado com um kernel Linux
  ARMv5TE real (Debian) e busybox estáticos até um shell interativo no
  `virtual-arm-box`.
- AArch64 (épico B6): decoder A64 completo (base ISA inteira, FP/SIMD escalar,
  exclusivos), `Aarch64Core` com EL0/EL1 e aborts precisos, MMU v8
  (`TranslatingAddressSpace64`), backend ASM nativo (`jit64`) cobrindo todo
  `Ir64Op.Kind`; `armbox --arch=aarch64` roda binários ELF64 bare-metal.
- Biblioteca nativa (`arm_jitter.dll`/`.so`) com API C (`capi/`, `native-image
  --shared`), embutível por qualquer linguagem com FFI, backend
  `INTERPRETED_IR`.
- Depuração: `GdbServer` (stub GDB remote serial), trace listener, runtime de
  divergência (`divergenceCheckingArmThumb`) e harness de equivalência entre
  emissores (32 e 64 bits).

### Conhecido / fora de escopo desta versão
- Hospedeiro full-system AArch64 (`virt64`) ainda não fecha (`B6.6.6`) —
  bloqueado em toolchain/kernel `aarch64-linux-*` reais.
- Backend Truffle sob `native-image` ainda não compila blocos de verdade
  (bailout de partial evaluation sob SVM, `A7`/`A9 PR2`); `native-image`
  (perfil `native` do `armbox`) roda hoje só com o backend `INTERPRETED_IR`.
- Sem NEON/SIMD avançado; sem virtualização (EL2), TrustZone (EL3) ou LPAE.
