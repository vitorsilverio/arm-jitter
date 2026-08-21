# B10 — EL2/EL3 completo (Hyp + Secure Monitor)

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Nasce de uma decisão explícita do usuário (2026-08-21, na sessão que
fechou B8.3): **nenhuma instrução ARM real fica de fora por "provavelmente desnecessária" ou
"grande demais para esta sessão"** — se uma instrução exige EL2/EL3 completos para funcionar
corretamente, a resposta é implementar EL2/EL3, não documentar o adiamento e seguir em frente. Ver
`tasks/README.md`, "Pendências que EXIGEM sessão de modelo forte" — este é exatamente esse caso:
grande demais para uma task ad-hoc como B8.x, precisa de escada própria.

## Por que isso apareceu agora

B8.3 (branch/system do A64) deixou de fora, "documentado":
- `AT` (address translation) — escreve `PAR_EL1` com um resultado de tradução real.
- Registradores de debug (`MRS`/`MSR` com `op0=2`).

Investigando o porquê, os dois (e várias outras lacunas já visíveis em `docs/COBERTURA-ISA.md`)
dependem de infraestrutura que este emulador nunca teve: `Aarch64Core`/`Aarch64ExceptionState`
só modelam **EL0 e EL1** (`inEl1: boolean`, não um nível de 4 valores) — ver o javadoc de
`Aarch64ExceptionState` ("NÃO um enum de 4 níveis completo (só EL0/EL1 existem no escopo de
B6.6.4)"). `HVC`/`SMC` são stubs que devolvem `PSCI_RET_NOT_SUPPORTED` sem entrar em nível
nenhum. Isso bloqueia, hoje: `AT` de verdade, os registradores EL2/EL3 (`HCR_EL2`, `SCR_EL3`,
`VTTBR_EL2`, ...), `HVC`/`SMC` reais, `TLBI` das formas EL2/EL3, e qualquer semântica de
virtualização (stage-2).

## Meta

Generalizar o modelo de exceção do A64 para os 4 níveis reais (`EL0`-`EL3`), com registradores,
entrada/saída de exceção e instruções privilegiadas corretas em cada um — sem stub "sem EL2/EL3
modelados" sobrando em lugar nenhum do código. Alvo primário de validação: `virtual-arm-box`
(`raspi3-64`/F11, que hoje pula GIC/PSCI de propósito — este épico é o que destrava isso de
verdade) — mas a implementação vale para QUALQUER preset A64 futuro, não só o raspi3.

## Escada

Cada item é uma sessão (ou mais, se crescer) — protocolo igual ao de `b7-plano-cobertura-isa.md`:
ler a fonte real (`ARM DDI 0487`, seção `D1`/`D17` de "Exception handling"), nunca decorar de
memória, corpus real via `aarch64-none-elf-as`/`objdump` quando aplicável.

| Task | Escopo | Depende de |
|---|---|---|
| **B10.1** | Generalizar `Aarch64ExceptionState`→estado por nível (`ELR`/`SPSR`/`ESR`/`FAR`/`VBAR`/`SP` para EL1-EL3, `CurrentEL` de 4 valores em vez de `boolean inEl1`), `Aarch64Core.enterSynchronousException` recebendo o nível-alvo (hoje sempre EL1) | — |
| **B10.2** | Registradores de sistema EL2 via `MRS`/`MSR` (`op0=3,op1=4`): `HCR_EL2`, `SCTLR_EL2`, `TCR_EL2`, `VTCR_EL2`, `VTTBR_EL2`, `ESR_EL2`, `FAR_EL2`, `ELR_EL2`, `SPSR_EL2`, `VBAR_EL2`, `CPTR_EL2`, `MDCR_EL2`, `CNTHCTL_EL2` | B10.1 |
| **B10.3** | Registradores de sistema EL3 via `MRS`/`MSR` (`op0=3,op1=6`): `SCR_EL3`, `SCTLR_EL3`, `ELR_EL3`, `SPSR_EL3`, `VBAR_EL3`, `MDCR_EL3`, `CPTR_EL3` | B10.1 |
| **B10.4** | `HVC` real: entra em EL2 (`ARM DDI 0487 D1.10`), substitui o stub `PrivilegedCall`/`PSCI_RET_NOT_SUPPORTED` — se `HCR_EL2` não roteia pra EL3, cai no handler de EL2 real | B10.1, B10.2 |
| **B10.5** | `SMC` real: entra em EL3, mesmo raciocínio de B10.4 (`SCR_EL3` decide o roteamento) | B10.1, B10.3 |
| **B10.6** | `AT` (todas as formas: `S1E0R`/`S1E0W`/`S1E1R`/`S1E1W`/`S1E2R`/`S1E2W`/`S1E3R`/`S1E3W`/`S12E1R`/`S12E1W`/`S12E0R`/`S12E0W`) — escreve `PAR_EL1` com o resultado real de uma tradução (reaproveita `TranslatingAddressSpace64`, um regime de tabela por nível) | B10.1, B10.2 |
| **B10.7** | Registradores de debug (`op0=2`): `MDSCR_EL1`, `OSLAR_EL1`, `OSLSR_EL1`, `DBGBVR<n>_EL1`/`DBGBCR<n>_EL1`/`DBGWVR<n>_EL1`/`DBGWCR<n>_EL1` — sem debugger de hardware conectado, mas armazenamento real (leitura devolve o que foi escrito, não trava/rejeita o guest) | — |
| **B10.8** | Stage-2 (`IPA→PA`, `HCR_EL2.VM=1`) — necessário pra `AT S12E*` valerem de verdade e pra qualquer guest rodando sob um hypervisor emulado | B10.2, B10.6 |
| **B10.9** | `TLBI` EL2/EL3 (`ALLE2`/`ALLE2IS`/`ALLE3`/`ALLE3IS`/`VAE2`/`VAE3`/... ) e stage-2 (`IPAS2E1*`) — decode específico (o `op1` que B8.3 tratou como "regime EL1 genérico" precisa dos regimes 2/3 também) | B10.1 |

**Ordem sugerida**: B10.1 primeiro (fundação, tudo depende dele) → B10.2/B10.3 em paralelo (são
independentes entre si) → B10.4/B10.5/B10.6/B10.9 (consomem B10.2/B10.3) → B10.7 (independente,
pode entrar a qualquer momento) → B10.8 por último (o mais arriscado/carente de fonte real).

## Fatos de referência (conferir contra `ARM DDI 0487`, não usar de memória)

- Vetor de exceção: MESMA tabela de 16 entradas por nível-ALVO (`ARM DDI 0487 D1.10`), só o
  `VBAR_ELx` do nível-alvo muda — infraestrutura já existe (`SYNCHRONOUS_LOWER_EL_AARCH64_VECTOR_OFFSET`
  em `Aarch64Core`), só falta generalizar "qual `VBAR_ELx`" em vez de sempre `VBAR_EL1`.
- Roteamento de `HVC`/`SMC`/IRQ/abort para EL2 vs EL3 depende de `HCR_EL2.TGE`/`SCR_EL3.{NS,EA,IRQ,FIQ}`
  — não é sempre "vai pro nível imediatamente acima do atual". Conferir a árvore de decisão real do
  manual antes de implementar B10.4/B10.5, não assumir.
- `SCTLR_EL2`/`SCTLR_EL3`/`TCR_EL2` têm RES1 bits diferentes de `SCTLR_EL1` — não copiar valores
  default de EL1 sem checar.

## Consumidores a revalidar (G5, toda task desta escada)

`virtual-arm-box` é o único consumidor A64 hoje (raspi3-64/F11) — mas G5 continua cobrindo os 5
repos (gbaemu/ndsemu não usam A64, mas compartilham `core`; n3dsemu usa `ARM11_MPCORE`, 32-bit,
não afetado; armbox é ARMv7-A 32-bit, não afetado). Push obrigatório em toda task — ver
`tasks/README.md`.
