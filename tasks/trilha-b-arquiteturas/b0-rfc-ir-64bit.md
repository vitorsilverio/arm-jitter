# B0 — RFC: generalização do IR para 64 bits (pré-requisito do AArch64)

**Trilha:** B · **Depende de:** — (recomendado fazer CEDO: influencia decisões de B1–B3)
**Repo:** arm-jitter · **Tipo: análise — NENHUM código de produção nesta task**

## Contexto

Todo o pipeline assume valores de 32 bits (`int`): records de `IrOp`, executores em
`codegen/executor/`, `GuestToHostMapper` (locals JVM de 1 slot), `CpuSnapshot`,
`AddressSpace` (endereços `int`). AArch64 precisa de registradores de 64 bits (X0–X30),
endereços de 64 bits e um banco de registradores diferente (SP separado, sem PC como
registrador geral). Sem uma decisão de desenho, qualquer código AArch64 nasceria torto.

## Objetivo

Documento `docs/RFC-IR-64BIT.md` com análise, opções, recomendação e plano de migração
incremental — aprovado pelo usuário antes de qualquer implementação.

## O RFC deve responder

1. **Opção A — IR parametrizado por largura** (um `IrOp` com width 32/64) vs
   **Opção B — IR-64 paralelo** (frontends de 64 bits geram um conjunto de ops
   próprio, compartilhando só a infraestrutura de bloco/cache/emissores genéricos).
   Prós/contras de cada, com foco em: risco de regressão no ARMv4T/v5TE em produção,
   custo nos emissores (ASM usa locals de 1 slot; `long` usa 2), legibilidade.
2. Como o banco de registradores do `ArmCore` se generaliza (ou se AArch64 ganha um
   `Aarch64Core` irmão compartilhando interfaces — provável).
3. `AddressSpace` com endereços 64-bit: interface nova (`AddressSpace64`) vs
   sobrecarga `long` na existente (impacto em TODOS os hospedeiros).
4. Impacto em: `IrBlock`/`BlockKey` (PC 64-bit), `CpuSnapshot`, harness de
   equivalência, `GdbServer` (protocolo tem layout de registradores por arquitetura),
   otimizador (constant fold em `long`).
5. Plano de migração em fases mergeáveis, cada uma com gbaemu/ndsemu verdes (G5).

## Método

- Inventariar usos: grep por `int address`, `int value` nas interfaces públicas de
  `ir/`, `memory/`, `codegen/`; listar no RFC os pontos de acoplamento reais.
- Olhar como projetos semelhantes separam frontends (ex.: a separação decoder/IR do
  próprio projeto entre ARM e THUMB é o precedente interno).

## Aceite

- RFC commitado com recomendação única e justificada + plano de fases.
- Seção "Decisões que B1–B3 devem respeitar desde já" (ex.: não introduzir novos usos
  de `int` em APIs que o RFC decidir migrar para `long`).

## Não fazer

- Nenhuma mudança em código de produção ou testes.
