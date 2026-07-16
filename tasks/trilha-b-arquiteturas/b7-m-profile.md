# B7 — Perfil M / Cortex-M (épico REFINADO em B7.1–B7.5)

**Trilha:** B · **Depende de:** B2.6 · **Repo:** arm-jitter (+ armbox na B7.5)
**Status de spec:** ✅ refinado 2026-07-15. Decisão de arquitetura FECHADA em
[docs/RFC-M-PROFILE.md](../../docs/RFC-M-PROFILE.md) — **`ArmCore` único +
`ExceptionModel` plugável, sem core irmão**. NÃO reavalie isso nas subs; execute na
ordem B7.1 → B7.2 → B7.3 → B7.4 → B7.5.

## Contexto (resumo; o histórico completo está no git deste arquivo)

Cortex-M (STM32, nRF, Arduino ARM) é Thumb/Thumb-2 puro — a ISA já está pronta
(B1–B2.6). O que falta é o entorno: Thread/Handler mode, MSP/PSP, xPSR, NVIC/VTOR,
stacking automático e EXC_RETURN. A RFC define que isso entra como estratégia
(`ExceptionModel`) dentro do `ArmCore`, reusando `CpsrRegister`/`ItState` (as
posições de NZCV/Q/ITSTATE coincidem entre CPSR e xPSR).

## Sub-tasks

| Sub | Arquivo | Escopo | Depende de |
|-----|---------|--------|-----------|
| B7.1 | [b7.1-exception-model-refactor.md](b7.1-exception-model-refactor.md) | Extrair `ExceptionModel` (refactor zero-diff, só perfil A) | B2.6 |
| B7.2 | [b7.2-mprofile-exception-model.md](b7.2-mprofile-exception-model.md) | `MProfileExceptionModel`: MSP/PSP, xPSR, stacking, EXC_RETURN, SVCall | B7.1 |
| B7.3 | [b7.3-scs-nvic-systick.md](b7.3-scs-nvic-systick.md) | `MProfileSystemControl`: SCS/NVIC/VTOR/SysTick memory-mapped | B7.2 |
| B7.4 | [b7.4-presets-armv6m-armv7m.md](b7.4-presets-armv6m-armv7m.md) | MRS/MSR SYSm + CPS M-profile + presets `ARMV6M`/`ARMV7M` | B7.2 (v7-M pleno também de B3.2) |
| B7.5 | [b7.5-runner-bare-metal.md](b7.5-runner-bare-metal.md) | Runner bare-metal no armbox (`--machine=cortex-m`) + firmware torture | B7.3, B7.4 |

## Aceite do épico (fecha na B7.5)

- Firmware real (`arm-none-eabi-gcc -mcpu=cortex-m0` e `-mcpu=cortex-m3`) roda no
  runner com pelo menos: reset via tabela de vetores, `SVC` disparado e retornado,
  SysTick periódico preemptando o main loop, troca MSP→PSP — validado por torture
  test auto-verificável (semihosting para saída/exit), nos 3 backends.
- Harness de equivalência (G1) cobrindo entrada/saída de exceção M.
- G2/G3/G5: presets A-profile e gbaemu/ndsemu intocados.
