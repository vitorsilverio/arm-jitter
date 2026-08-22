# Trilha B — Arquiteturas — Índice e dependências

Título, dependências e status de cada task da trilha. Quando o status diz "ver
**Resultado** na task", o histórico completo está no final do arquivo da task. Ver
[../README.md](../README.md) para protocolo de execução e invariantes globais.

| Task | Título | Depende de | Status |
|------|--------|-----------|--------|
| [B0](b0-rfc-ir-64bit.md) | RFC: IR de 64 bits (para AArch64) | — | ✅ — ver **Resultado** na task |
| [B1.1](b1.1-armv6-features-preset.md) | ArmFeatures + preset ARMV6K | — | ✅ (10 features + `ARMV6K` via `extending`; zero-diff de runtime; gbaemu/ndsemu verdes) |
| [B1.2](b1.2-armv6-extend-reverse.md) | SXT/UXT, REV, UMAAL | B1.1 | ✅ — ver **Resultado** na task |
| [B1.3](b1.3-armv6-simd-media.md) | SIMD paralelo, GE flags, SAT, USAD8, PKH | B1.1 | ✅ — ver **Resultado** na task |
| [B1.4](b1.4-armv6-exclusive.md) | LDREX/STREX + monitor de exclusividade | B1.1 | ✅ — ver **Resultado** na task |
| [B1.5](b1.5-armv6-system.md) | CPS, SRS/RFE, SETEND, hints WFI/WFE | B1.1 | ✅ — ver **Resultado** na task |
| [B1.6](b1.6-armv6-asm-nativo.md) | Emissão nativa ASM das ops v6 | B1.2–B1.5 | ✅ — ver **Resultado** na task |
| [B1.7](b1.7-armv6-unaligned-access.md) | Acesso desalinhado ARMv6+ (`UNALIGNED_ACCESS`; hoje a rotação v4T é incondicional — corrupção silenciosa em binários v6+) | — | ✅ — ver **Resultado** na task |
| [B1.8](b1.8-armv6-be8-data-endianness.md) | Suporte real a BE8 (`CPSR.E=1`, acesso de dados big-endian) — revoga a exceção MVP da B1.5 | B1.5–B1.7 | ✅ (2026-08-15) motivada pela F3 — ver **Resultado** na task |
| [B2](b2-thumb2.md) | Thumb-2 (decoder 32-bit + IT blocks) — épico | B1.6 | 🟡 refinado em B2.1–B2.5 (2026-07-11); nenhuma sub ainda implementada |
| [B2.1](b2.1-thumb2-infra.md) | Infra: fetch 32-bit + gate `THUMB2` + desambiguação vs BL/BLX legado | B2 (B1.6) | ✅ — ver **Resultado** na task |
| [B2.2](b2.2-thumb2-dataproc.md) | Data-processing 32-bit (modified immediate, MOVW/MOVT, shifts) | B2.1 | ✅ — ver **Resultado** na task |
| [B2.2.1](b2.2.1-thumb2-dataproc-register-form-sp.md) | Auditar `Rm=SP` na forma registrador do data-processing Thumb-2 | B2.2 | ✅ — ver **Resultado** na task |
| [B2.2.2](b2.2.2-thumb2-reserved-encoding-undefined.md) | Fechar fallback incorreto de `op4` reservados sob `top5=0b11101` | B2.1, B2.2 (idealmente após B2.3, ver Armadilhas) | ✅ — ver **Resultado** na task |
| [B2.3](b2.3-thumb2-loadstore.md) | Load/store 32-bit (offset 12-bit, LDRD/STRD, LDM/STM.W) | B2.1 | ✅ — ver **Resultado** na task |
| [B2.4](b2.4-thumb2-branches-it.md) | Branches (B.W/BL/BLX/CBZ/CBNZ/TBB/TBH) + IT block (maior risco) | B2.1 | ✅ — ver **Resultado** na task |
| [B2.5](b2.5-thumb2-misc.md) | Misc: hints, barriers (DMB/DSB/ISB), MSR/MRS Thumb-2 | B2.1 (+ B1.5) | ✅ — ver **Resultado** na task |
| [B2.6](b2.6-thumb2-preset-fechamento.md) | Fechar o preset Thumb-2: BL/BLX vira decode 32-bit + plugar as 4 extensões | B2.1-B2.5 | ✅ — ver **Resultado** na task |
| [B2.7](b2.7-thumb2-paridade-encodings.md) | **Paridade Thumb-2** (MUL.W/UMULL/shifts-reg/extend/REV/CLZ/SEL/QADD/paralelas/SSAT + LDREX/STREX.W + CPS.W + MCR/MRC Thumb) — sem isto binário v7 real morre na 1ª multiplicação; 3 PRs | B2.6 | ✅ (2026-07-16/17) PR1 — ver **Resultado** na task |
| [B2.8](b2.8-pld-pli-nop.md) | PLD/PLI como NOP (ARM + Thumb-2) — memcpy de libc v7 emite PLD; hoje UNDEFINED | B2.6 (lado Thumb) | ✅ (2026-07-17) — ver **Resultado** na task |
| [B3](b3-armv7a-vfp.md) | ARMv7-A user-level + VFP — ÉPICO refinado 2026-07-15 | B2.6 | ✅ FECHADO 2026-07-23 (B3.1–B3.7 todas ✅ — ver B3.7 para o resumo final e o preset público `ARMV7A`; reaberto pontualmente pela B3.8 em 2026-08-15) |
| [B3.8](b3.8-fpscr-rmode-fz.md) | FPSCR: RMode e FZ de verdade (revisita a decisão nº 3 do B3) | B3.3, B3.4 | ✅ (2026-08-15) — ver **Resultado** na task |
| [B3.1](b3.1-armv7-inteiro-arm.md) | Inteiro v7 encodings ARM (MOVW/MOVT, MLS, bitfield, RBIT, SDIV/UDIV, barreiras) | B2.6 | ✅ (2026-07-17) — ver **Resultado** na task |
| [B3.2](b3.2-armv7-inteiro-thumb2.md) | Inteiro v7 encodings Thumb-2 | B3.1 | ✅ (2026-07-17) — ver **Resultado** na task |
| [B3.3](b3.3-vfp-banco-registradores.md) | VFP: banco S/D + FPSCR no core | — | ✅ — ver **Resultado** na task |
| [B3.4](b3.4-vfp-ir-interpretador.md) | VFP: IrOps + executor interpretado | B3.3 | ✅ (2026-07-17) 10 records novos em — ver **Resultado** na task |
| [B3.5](b3.5-vfp-decoder.md) | VFP: decoder CP10/11 ARM+Thumb-2 | B3.4 | ✅ (2026-07-22) — ver **Resultado** na task |
| [B3.6](b3.6-vfp-asm-nativo.md) | VFP + inteiro v7: emissão ASM nativa (2 PRs) | B3.5 | ✅ PR1 2026 — ver **Resultado** na task |
| [B3.7](b3.7-preset-armv7a-armbox.md) | Preset `ARMV7A` + armbox torture + gcc hard-float (fecha B3) | B3.1–B3.6 | ✅ (2026-07-23) — ver **Resultado** na task |
| [B4.0](b4.0-runner-user-mode.md) | Runner Linux user-mode (estilo qemu-user) | — | ✅ — ver **Resultado** na task |
| [B4.0.1](b4.0.1-armbox-validar-armv6k.md) | Validar ARMv6K de verdade no armbox (binário real) | B4.0, B1.1-B1.6 | ✅ — ver **Resultado** na task |
| [B4.0.2](b4.0.2-armbox-validar-thumb2.md) | Validar Thumb-2 de verdade no armbox (binário real) | B4.0.1, B2.1-B2.2 | ✅ — ver **Resultado** na task |
| [B4.0.3](b4.0.3-armbox-validar-thumb2-completo.md) | armbox: Thumb-2 de compilador real (gcc/busybox) | B2.6 ✅, **B2.7 (incl. PR3 = MCR/MRC Thumb — o gap que B4.0.4 confirmou), B1.7 ✅, B2.8**, B4.0.4 ✅, B4.0.4.1 ✅ | 🟡 PARCIAL — ver **Resultado** na task |
| [B4.0.4](b4.0.4-armbox-tls-tpidruro.md) | armbox: TLS v7 (TPIDRURO via `CoprocessorBus`) — musl v7 lê TLS por MRC, hoje explode | — | ✅ — ver **Resultado** na task |
| [B4.0.4.1](b4.0.4.1-coprocessor-handles-fino.md) | `CoprocessorBus.handles` fino por registrador — Undefined limpo de guest para CP15 parcial (gap 1 do relatório da B4.0.4; decisão fechada: predicado ANTES de read/write, com default retrocompatível) | — | ✅ — ver **Resultado** na task |
| [B4.0.5](b4.0.5-armbox-fork-pipes.md) | armbox fase 3: fork/execve/pipes/wait (scripts shell reais como corpus N3) | B4.0 ✅ (B4.0.4 recomendada antes) | ⬜ |
| [B4.1](b4.1-mmu-softmmu.md) | MMU/softmmu full-system — refinado em B4.1.1–B4.1.5; [RFC-SOFTMMU](../docs/RFC-SOFTMMU.md) aprovada 2026-07-15 (**não depende mais de B3**: hospedeiro versatilepb+ARM1176/ARMv6K) | B1.6 | ✅ — ver **Resultado** na task |
| [B5](b5-3ds.md) | 3DS: lado arm-jitter (monitor exclusivo global B5.1 + preset MPCore B5.2) — refinada 2026-07-15 | B3.3–B3.6 (só B5.2; B5.1 já executável) | 🟡 — ver **Resultado** na task |
| [B6](b6-aarch64.md) | AArch64 — refinado: B6.1/B6.2 executáveis; B6.3 decomposta em B6.3.1-B6.3.4 (2026-07-24), TODAS ✅ (2026-07-26); B6.4 ganhou spec própria (2026-07-26, `b6.4-aarch64-asm-backend.md`, 3 PRs) — PR1/PR2/PR3 ✅, épico B6.4 FECHADO 2026-07-26; B6.5 decomposta em B6.5.1-B6.5.4 (2026-07-26, espelha B3.3-B3.6/VFP32); B6.6 decomposta em B6.6.1-B6.6.6 (2026-07-26, uma sub-task a mais que o precedente B4.1.1-B4.1.5 porque A64 não tem MCR/MRC — acesso a registrador de sistema via MRS/MSR é pré-requisito novo, B6.6.1) | B0 ✅ | 🔶 B6 — ver **Resultado** na task |
| [B6.3.1](b6.3.1-aarch64-logical-imm-alu-register.md) | AArch64: logical immediate (`DecodeBitMasks`) + ALU registrador (shifted/extended) — cria o dispatch novo de classe top-level "Data Processing — Register" | B6.2 🟡 | ✅ (2026-07-24/25) — ver **Resultado** na task |
| [B6.3.2](b6.3.2-aarch64-csel-bitfield.md) | AArch64: `CSEL`/`CSINC`/`CSINV`/`CSNEG` (+ aliases) + `UBFM`/`SBFM`/`BFM` (+ aliases) | B6.3.1 ✅ | ✅ (2026-07-25) — ver **Resultado** na task |
| [B6.3.3](b6.3.3-aarch64-mul-div.md) | AArch64: `MADD`/`MSUB` (+ aliases `MUL`/`MNEG`), `SDIV`/`UDIV` | B6.3.1 ✅ | ✅ (2026-07-25) — ver **Resultado** na task |
| [B6.3.4](b6.3.4-aarch64-exclusive-monitor.md) | AArch64: `LDXR`/`LDAXR`/`STXR`/`STLXR` + `Aarch64ExclusiveMonitor` (sibling do monitor de B1.4/B5.1) | B6.2 🟡 | ✅ (2026-07-26) — ver **Resultado** na task |
| [B6.4](b6.4-aarch64-asm-backend.md) | AArch64: backend ASM 64-bit, dividido em 3 PRs (spec própria escrita 2026-07-26) | B6.3 ✅ | 🟡 — ver **Resultado** na task |
| [B6.5.1](b6.5.1-aarch64-fp-register-bank.md) | AArch64: banco de registradores FP escalar (`Aarch64FpRegisters`, V0-V31 só bits 63:0, sem FPCR/FPSR) — 1ª das 4 sub-tasks de B6.5 (rodada de spec 2026-07-26) | B6.1 ✅ | ⬜ |
| [B6.5.2](b6.5.2-aarch64-fp-ir-interpretador.md) | AArch64: `Ir64Op`s de FP (`Fp64Alu`/`Fp64MoveImmediate`/`Fp64Compare`/`Fp64Convert`) + executor interpretado | B6.5.1 ✅ | ✅ (2026-07-27) 4 records novos em — ver **Resultado** na task |
| [B6.5.3](b6.5.3-aarch64-fp-decoder.md) | AArch64: decoder da classe "Data Processing — Scalar FP" (`bit26=1`, ramo novo em `decodeDataProcessingRegister`) | B6.5.2 | ✅ |
| [B6.5.4](b6.5.4-aarch64-fp-asm-nativo.md) | AArch64: emissão ASM nativa de FP (extensão de `Ir64NativePolicy`/`Ir64BlockCompiler`) | B6.5.3, B6.4 ✅ | ✅ (2026-07-27) — ver **Resultado** na task |
| [B6.6.1](b6.6.1-aarch64-system-register-access.md) | AArch64: acesso a registrador de sistema (`MRS`/`MSR (register)`) — pré-requisito da MMU, sem equivalente 32-bit (A64 não tem MCR/MRC) — 1ª das 6 sub-tasks de B6.6 (rodada de spec 2026-07-26) | B6.1 ✅ | ✅ (2026-07-27) — ver **Resultado** na task |
| [B6.6.2](b6.6.2-aarch64-translating-address-space.md) | AArch64: `TranslatingAddressSpace64` — page-walk VMSA64 (4KiB granule, VA 48 bits, 4 níveis L0-L3) + micro-TLB, independente de B6.6.1 | B6.1 ✅ | ✅ (2026-07-27) — ver **Resultado** na task |
| [B6.6.3](b6.6.3-aarch64-system-register-mmu-bridge.md) | AArch64: `Aarch64VmsaSystemRegisters` ligando B6.6.1↔B6.6.2 + decode mínimo de `TLBI VMALLE1` | B6.6.1, B6.6.2 | ✅ (2026-07-27) — ver **Resultado** na task |
| [B6.6.4](b6.6.4-aarch64-precise-aborts-el1.md) | AArch64: modelo mínimo de exceção EL0→EL1 (`Aarch64ExceptionState`) + `ERET` + aborts precisos no interpretador | B6.6.3 | ✅ (2026-07-27) — ver **Resultado** na task |
| [B6.6.5](b6.6.5-aarch64-translation-generation.md) | AArch64: `translationGeneration` em `jit64/BlockKey64`/`JitRuntime64` | B6.6.4, B6.4 ✅ | ✅ (2026-07-27) espelho direto de B4 — ver **Resultado** na task |
| [B6.6.6](b6.6.6-aarch64-virt64-host.md) | AArch64: hospedeiro `virt64` (repo novo) — kernel arm64 mínimo até shell | B6.6.5 | 🧑 bloqueada no usuário (toolchain/kernel real, mesmo bloqueio de B6.2/B4.1.5) — EM ESPERA (não cancelada), converge com o mesmo bloqueio de fundo da F11, agora atacado por B6.6.7 |
| [B6.6.7](b6.6.7-aarch64-el1-kernel-surface.md) | AArch64: superfície mínima de EL1 para kernel real (system regs restantes + `WFI`/`WFE`/`HVC`/`SMC` + IRQ) | B6.6.4 | ✅ (2026-08-18) priorizada pelo usuário em — ver **Resultado** na task |
| [B6.8](b6.8-aarch64-conditional-compare.md) | AArch64: `CCMP`/`CCMN` (decode gap achado pela F11 — primeira instrução de `kernel8.img` real, truque polyglot EFI "MZ") | B6.3.1 ✅ | ✅ (2026-08-20) — ver **Resultado** na task |
| [B6.9](b6.9-aarch64-logical-shifted-register.md) | AArch64: `AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` (`Logical (shifted register)`, incl. alias `MOV`/`MVN`) — SEGUNDO decode gap achado pela F11 (`0xaa0003f5`=`MOV X21,X0`, `0x13ba9e8` do `kernel8.img` real) | B6.3.1 ✅ | ✅ (2026-08-20) — ver **Resultado** na task |
| [B6.10](b6.10-aarch64-ctr-el0-dczid-el0.md) | AArch64: `CTR_EL0`/`DCZID_EL0` (registradores de identidade de cache) — TERCEIRO decode gap achado pela F11 (`MRS X3, CTR_EL0`, `0x38fc4` do `kernel8.img` real) | B6.6.7 ✅ | ✅ (2026-08-20) Fatos de referência — ver **Resultado** na task |
| [B6.12](b6.12-aarch64-cache-maintenance.md) | AArch64: manutenção de cache `IC`/`DC` (NOP, sem cache emulado) — QUINTO decode gap achado pela F11 (`DC IVAC, X0`=`0xd5087620`, `0x39000` do `kernel8.img` real) | B6.6.3 ✅ | ✅ (2026-08-20) Fatos de referência — ver **Resultado** na task |
| [B6.13](b6.13-aarch64-ttbr1-el1.md) | AArch64: `TTBR1_EL1` — hipótese da F11 sessão 6 para o SÉTIMO bloqueio (`write64` traduzindo acima de 4 GiB) | B6.6.3 ✅ | ✅ (2026-08-20) — ver **Resultado** na task |
| [B7](b7-m-profile.md) | Perfil M / Cortex-M — ÉPICO refinado 2026-07-15; decisão fechada na [RFC-M-PROFILE](../docs/RFC-M-PROFILE.md) (`ExceptionModel` plugável, sem core irmão) | B2.6 | ✅ ÉPICO CONCLUÍDO (B7.1–B7.5, 2026-07-23) |
| [B7.1](b7.1-exception-model-refactor.md) | Extrair `ExceptionModel` (refactor zero-diff) | B2.6 | ✅ (2026-07-17) — ver **Resultado** na task |
| [B7.2](b7.2-mprofile-exception-model.md) | `MProfileExceptionModel`: MSP/PSP, xPSR, stacking, EXC_RETURN | B7.1 | ✅ (2026-07-23) — ver **Resultado** na task |
| [B7.3](b7.3-scs-nvic-systick.md) | SCS/NVIC/VTOR/SysTick memory-mapped na lib | B7.2 | ✅ (2026-07-23) — ver **Resultado** na task |
| [B7.4](b7.4-presets-armv6m-armv7m.md) | MRS/MSR SYSm + CPS M + presets `ARMV6M`/`ARMV7M` | B7.2 (pleno c/ B3.2) | ✅ (2026-07-23) — ver **Resultado** na task |
| [B7.5](b7.5-runner-bare-metal.md) | armbox `--machine=cortex-m` + firmware torture + semihosting (fecha B7) | B7.3 ✅, B7.4 ✅ | ✅ (2026-07-23) — ver **Resultado** na task |
| [B8.1](b8.1-a64-load-store-escalar.md) | A64: load/store escalar restantes (`STNP`/`LDNP`, `LDPSW`, `PRFM`, `LDTR`/`STTR`, `LDXP`/`STXP`, `LDAR`/`STLR`, `CAS`/`CASP`) — frente de cobertura de ISA, `b7-plano-cobertura-isa.md` | E6 ✅ | ✅ (2026-08-21) — ver **Resultado** na task |
| [B8.2](b8.2-a64-inteiro-restante.md) | A64: inteiro restante (`ADC`/`SBC`, `EXTR`, `RBIT`/`REV16`/`REV32`/`REV64`/`CLZ`/`CLS`/`CNT`, `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/`SMULH`/`UMULH`, `RMIF`/`SETF8`/`SETF16`/`CFINV`/`XAFLAG`/`AXFLAG`) — frente de cobertura de ISA, `b7-plano-cobertura-isa.md` | B8.1 ✅ | ✅ (2026-08-21) — ver **Resultado** na task |
| [B8.3](b8.3-a64-branch-system.md) | A64: branch/system (`CB*` excluído/`FEAT_CMPBR`, `BRK`/`HLT`, `DSB`/`CLREX`/`SB`, `MSR`/`MRS` restantes, `WFET`/`WFIT`, `SYS`/TLBI/cache ampliado) — frente de cobertura de ISA, `b7-plano-cobertura-isa.md` | B8.2 ✅ | ✅ (2026-08-21) — ver **Resultado** na task |
| [B10.1](b10.1-el2-el3-estado-generalizado.md) | EL2/EL3: generaliza `Aarch64ExceptionState`/`Aarch64Core` para os 4 níveis (`Aarch64ExceptionLevel`, vetor "mesmo nível"/"nível inferior", `ERET` lendo o alvo real de `SPSR_ELx.M`) — fundação do épico `b10-plano-el2-el3.md` | — | ✅ (2026-08-21) — ver **Resultado** na task |
| [B10.2](b10.2-el2-registradores-sistema.md) | EL2: 13 registradores de sistema via `MRS`/`MSR` (`SCTLR_EL2`/`HCR_EL2`/`MDCR_EL2`/`CPTR_EL2`/`TCR_EL2`/`VTTBR_EL2`/`VTCR_EL2`/`SPSR_EL2`/`ELR_EL2`/`FAR_EL2`/`ESR_EL2`/`CNTHCTL_EL2`/`VBAR_EL2`) — armazenamento puro, sem side effect ainda | B10.1 ✅ | ✅ (2026-08-21) — ver **Resultado** na task |
| [B10.3](b10.3-el3-registradores-sistema.md) | EL3: 7 registradores de sistema via `MRS`/`MSR` (`SCTLR_EL3`/`SCR_EL3`/`MDCR_EL3`/`CPTR_EL3`/`SPSR_EL3`/`ELR_EL3`/`VBAR_EL3`) — armazenamento puro, sem side effect ainda | B10.1 ✅ | ✅ (2026-08-21) — ver **Resultado** na task |

Legenda: ⬜ pendente · 🟡 em andamento · ✅ concluída
