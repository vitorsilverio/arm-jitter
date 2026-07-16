# RFC — Perfil M (Cortex-M) no arm-jitter

**Status: APROVADA POR REFINAMENTO 2026-07-15** (sessão de modelo forte; decisão
tomada para destravar B7.1–B7.5 — se o usuário discordar, reabrir ANTES de B7.2).

## Pergunta

O modelo de execução atual (`ArmCore` + `CpuMode` + `ArmException`, perfil A/R:
modos bancados por CPSR, vetores fixos, SPSR) pode ser estendido para o perfil M
(Thread/Handler, MSP/PSP, xPSR, NVIC, EXC_RETURN) ou precisa de um core irmão?

## Decisão: **Opção A com estratégia — `ArmCore` único + `ExceptionModel` plugável**

Nada de core irmão. Racional:

1. **A ISA é a mesma.** Perfil M executa Thumb-1/Thumb-2 — exatamente o decoder/IR/
   interpretador/ASM de B1–B2.6. Um core irmão obrigaria a duplicar ou abstrair os 7
   executores (`IrAluExecutor`…`IrTransferExecutor`), todos acoplados a `ArmCore` —
   custo enorme para reusar o que já funciona. (Contraste com AArch64/RFC B0, onde a
   ISA em si é outra — lá o irmão se justifica; aqui não.)
2. **O que difere é SÓ o entorno de exceção/PSR.** Isso é isolável atrás de uma
   interface: entrada de exceção, retorno de exceção, banking de SP e política de
   escrita no PSR.
3. **Os bits coincidem onde importa.** APSR (NZCV+Q) e ITSTATE do xPSR ocupam AS
   MESMAS posições do CPSR A-profile (`CpsrRegister`/`ItState` de B2.4 são
   reusados sem mudança). O que muda: bit T (A: bit 5 / M: EPSR bit 24), modo (A:
   bits 4:0 / M: não existe — IPSR bits 8:0 é o número da exceção ativa).

## Desenho

```
ArmCore
 ├─ ExceptionModel (interface nova, pacote core/)
 │   ├─ AProfileExceptionModel   ← 100% o código atual de handleException/switchMode
 │   └─ MProfileExceptionModel   ← B7.2: Thread/Handler, MSP/PSP, CONTROL, IPSR,
 │                                  stacking automático, EXC_RETURN, tabela VTOR
 └─ (resto intacto: registers[], CpsrRegister, memória, JIT — tudo compartilhado)
```

- `ArmCore#handleException`/`requestException`/`switchMode`/`bankedRegister` delegam
  ao `ExceptionModel`; o default é `AProfileExceptionModel` (zero-diff, G3).
- **EXC_RETURN**: os caminhos que escrevem PC vindo de dados (`BranchExchange`,
  `IrExecutionSupport#loadToPc`, `Pop` com PC) consultam
  `exceptionModel.interceptsBranch(target)` — só o modelo M devolve `true` para
  `0xFFFFFFF0..0xFFFFFFFF` e executa o retorno de exceção. Gate barato: o modelo A
  devolve `false` constante (JIT inlina/elimina).
- **NVIC/SCS/SysTick** NÃO são "periférico do hospedeiro" como IRQ/FIQ do perfil A:
  o acoplamento com a entrada de exceção (prioridades, preempção, late-arrival) é
  forte demais. Entram na lib como componente memory-mapped opcional
  (`MProfileSystemControl`, B7.3) que o hospedeiro mapeia em `0xE000E000` — de
  preferência via `PagedAddressSpace.mapHandler` (C3). Periféricos de fabricante
  (UART/GPIO/timers STM32 etc.) ficam no hospedeiro, como sempre.
- **Feature**: `ArmFeature.M_PROFILE` gateia decoder específico (MRS/MSR com SYSm,
  CPS limitado a PRIMASK/FAULTMASK, tratamento de BX-para-ARM como fault) e os
  intercepts. Presets: `ARMV6M` e `ARMV7M` (B7.4).
- **ARMv8-M (TrustZone-M/SAU)**: fora. Extensão futura por cima de v7-M.

## Consequências

- gbaemu/ndsemu/armbox nunca veem o modelo M (default A, G3/G5).
- `CpuMode`/`ArmException` continuam existindo com os nomes atuais; o modelo M não
  os usa (tem os próprios números de exceção via IPSR). `ArmException` ganha um
  irmão `MProfileException` (enum: Reset/NMI/HardFault/MemManage/BusFault/
  UsageFault/SVCall/DebugMonitor/PendSV/SysTick/IRQ0+n) SÓ visível ao modelo M.
- O harness de equivalência G1 continua válido: o interpretador com
  `MProfileExceptionModel` é o oráculo do backend ASM com o mesmo modelo.
