# B6 — AArch64 (épico) **[REFINAR — bloqueado pela RFC B0]**

**Trilha:** B · **Depende de:** B0 aprovada · **Repo:** arm-jitter (+ runner arm64)

## Contexto

AArch64 NÃO é uma extensão do ARM 32-bit: encoding totalmente novo, 31 registradores
X de 64 bits + SP/XZR (PC não é registrador geral), sem predicação condicional geral,
sem LDM/STM (LDP/STP), modelo de exceções por níveis (EL0–EL3). É um segundo frontend
que compartilha a infraestrutura de JIT (cache, tiers, chaining, emissores genéricos),
não o decoder/core atuais. **ARMv9-A é um superset do ARMv8-A** — alvo inicial é
ARMv8-A base (sem SVE/SVE2; essas viram features depois).

Meta honesta: **Linux arm64 user-mode → full-system**. Android é norte distante
(exige binder/GPU/HALs — fora do escopo da lib).

## Sub-épicos previstos (cada um vira spec após a RFC B0)

| Sub | Escopo | Aceite incremental |
|-----|--------|--------------------|
| B6.0 | = RFC B0 executada: aplicar a decisão de IR 64-bit (migração incremental com gbaemu/ndsemu verdes por fase) | suites verdes em cada fase |
| B6.1 | Decoder A64: grupos principais (data-processing imm/reg, load/store, branches) | corpus validado contra objdump/capstone |
| B6.2 | `Aarch64Core` (ou generalização decidida na RFC): banco X/W, SP por EL, PSTATE, exceções EL0/EL1 | testes unitários de estado |
| B6.3 | Interpretador A64 completo (inteiro; FP/SIMD escalar depois) | hello-world arm64 estático no runner user-mode (B4.0 estendido para ELF64) |
| B6.4 | Backend ASM 64-bit (locals `long` de 2 slots — o `GuestToHostMapper` decidido na RFC) | harness de equivalência A64 |
| B6.5 | FP/NEON escalar mínimo para userland | busybox arm64 |
| B6.6 | MMU v8 (VMSA64) + hospedeiro virt | kernel arm64 mínimo até shell |

## Decisões já tomadas (respeitar no refinamento)

- Nada de AArch64 entra nos caminhos ARMv4T/v5TE em produção (G3/G5).
- O runner user-mode (B4.0) é o veículo de validação — estendê-lo para ELF64/syscalls
  arm64 faz parte de B6.3.
- Flags NZCV do A64 vivem em PSTATE ≠ CPSR — não reusar a classe CPSR 32-bit.
