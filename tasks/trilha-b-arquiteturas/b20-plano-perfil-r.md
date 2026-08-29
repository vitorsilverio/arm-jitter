# B20 — Perfil R (ARMv7-R / ARMv8-R, PMSA/MPU): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Escrito na auditoria de 2026-08-29 a pedido do usuário ("escrever a spec
de todas as extensões ainda faltantes"). O perfil R era **a única das três famílias de perfil ARM
sem plano nenhum** — a B12 (catálogo de processadores) registrou a pendência com todas as letras:

> Restam só pendências que exigem trabalho fora do escopo de catalogação pura de B12: `Cortex-A32`
> (task de decode própria), **perfil R inteiro (nunca modelado, épico próprio)** e
> `ARMv1`/`ARMv2`/`ARMv2a`/`ARMv3`.

Perfil R é o ARM de **tempo real**: mesmo conjunto de instruções A32/T32 do perfil A, mas com
**PMSA** (Protected Memory System Architecture — MPU por regiões) no lugar de **VMSA** (MMU com
tabelas de página), e garantias de latência (TCM, sem tradução de endereço). Cortex-R4/R5/R7/R8 são
ARMv7-R; Cortex-R52/R52+/R82 são ARMv8-R (o R82 é AArch64).

## O que falta de infraestrutura (verificado no código, não suposto)

1. **Nenhuma menção a perfil R no projeto**: `grep -r "PMSA\|R_PROFILE\|Cortex-R"` em
   `core/src/main` não devolve NADA. Não é uma feature desligada — não existe.
2. **`ArmFeature` tem `M_PROFILE`** (e a B9.11 usou `M_PROFILE` puro como gate real), mas não tem
   `R_PROFILE` nem `PMSA`.
3. **Toda a infra de memória privilegiada é VMSA**: `memory/mmu/TranslatingAddressSpace` (tabelas
   short-descriptor), `Cp15VmsaCoprocessor`, `Aarch64VmsaSystemRegisters`. PMSA não traduz endereço
   — ele só **permite ou nega** o acesso físico, com regiões sobrepostas resolvidas por prioridade.
   É um `AddressSpace` de checagem, estruturalmente mais simples que o de tradução (a F3 já provou
   que o padrão "wrapper de `AddressSpace`" funciona: ver `docs/RFC-SOFTMMU.md`, decisão 1).
4. **Nenhum preset/`ArmProcessor` de perfil R** — os 10 Cortex-M que a B12.4 não pôde catalogar têm
   o análogo aqui: nenhum Cortex-R é catalogável hoje.

## Por que isto NÃO é um épico de decode

O conjunto de instruções de ARMv7-R é praticamente o de ARMv7-A (A32+T32, com `DIV` em ambos os
conjuntos e sem as extensões de virtualização). **A cobertura de instrução já está quase toda
pronta** — o que falta é o MODELO DE SISTEMA. Consequência prática: este épico quase não mexe em
`docs/COBERTURA-ISA.md` (as colunas novas nascem altas), e o valor dele é destravar hardware real
(automotivo/armazenamento/baseband) e fechar a terceira perna do ARM.

## Escada (refinar em spec própria quando cada degrau for pego)

| Task | Escopo | Depende de |
|---|---|---|
| **B20.1** | `ArmFeature.R_PROFILE` + `ArmFeature.PMSA` + preset `ARMV7R` (base ARMv7-A menos virtualização/VMSA, mais `DIVIDE` em A32 — que no perfil A só existe em T32). Sem modelo de memória ainda: o preset já roda código user-mode | — |
| **B20.2** | Registradores de MPU no CP15 (PMSAv7): `MPUIR` (número de regiões), `RGNR` (seleção), `DRBAR`/`DRSR`/`DRACR` (base/tamanho+sub-regiões/atributos) e o `SCTLR.M`/`.BR` que ligam a MPU e a região de background | B20.1 |
| **B20.3** | `PmsaAddressSpace implements AddressSpace` — checagem por região com prioridade (região de MAIOR índice vence, ao contrário do "primeira que casa" de outras arquiteturas), sub-regiões de 1/8, atributos `AP`/`XN`, e falha → `ArmException.DATA_ABORT`/`PREFETCH_ABORT` com `DFSR`/`IFSR` de PMSA (códigos DIFERENTES dos de VMSA) | B20.2 |
| **B20.4** | TCM (Tightly Coupled Memory): `ATCMRR`/`BTCMRR`, mapeamento de TCM sobrepondo o barramento — é o que distingue perfil R de "A sem MMU" no comportamento real de um binário de tempo real | B20.3 |
| **B20.5** | Modelo de exceção do perfil R: igual ao do perfil A (mesmos modos/bancos), com `VBAR`/HIVECS e a diferença de **não haver Hyp/Monitor** em ARMv7-R. Reusa `AProfileExceptionModel` com gate, não copia | B20.1 |
| **B20.6** | Catálogo `ArmProcessor`: `CORTEX_R4`/`R5`/`R7`/`R8` (ARMv7-R) — espelho do que B12.x fez para A/M | B20.2 |
| **B20.7** | **ARMv8-R AArch32** (`Cortex-R52`): PMSAv8-32 (regiões por `PRBAR`/`PRLAR`, layout diferente do v7-R) + EL2 obrigatório no perfil R | B20.4 |
| **B20.8** | **ARMv8-R AArch64** (`Cortex-R82`): PMSA no lado A64 — depende do modelo de EL/`Aarch64` que B10/B11 já construíram, e é o degrau que junta as duas metades do projeto | B20.7, B19.9 |
| **B20.9** | Validação N1-N4 em `docs/VALIDACAO-ARQUITETURAS.md` + hospedeiro real: candidato natural é o `-M mps3-an536` (Cortex-R52) do QEMU, com binário bare-metal — a mesma disciplina do `virtual-arm-box` (F3) | B20.7 |

## Meta

Presets `ARMV7R`/`ARMV8R` medidos em `docs/COBERTURA-ISA.md` como colunas próprias, Cortex-R
catalogáveis em `ArmProcessor`, e um binário bare-metal de perfil R rodando com MPU ativa.

## Invariantes específicos deste épico

- **G3**: nada disto muda o comportamento dos presets A/M existentes. `PmsaAddressSpace` é um
  wrapper NOVO; `TranslatingAddressSpace` (VMSA) não é tocado.
- **G2 generalizado**: PMSA e VMSA são MUTUAMENTE EXCLUSIVOS numa mesma implementação — um preset R
  nunca declara features de VMSA, e vice-versa. Se as duas puderem ser declaradas juntas, o gate
  está errado.
- **G8**: o espaço de encoding não muda praticamente nada aqui; o que muda é sistema. Cuidado para
  não "abrir" instrução de virtualização (`HVC`/`ERET`) num preset ARMv7-R, onde não existe.

## Armadilhas conhecidas

- **Prioridade de região invertida**: em PMSA, quando duas regiões se sobrepõem, vence a de MAIOR
  número — o oposto da intuição de "primeira que casa". Errar isso dá permissão silenciosamente
  errada, não crash.
- **`DFSR` de PMSA não é o de VMSA**: os códigos de status de falha diferem (não há "translation
  fault" — só background/permission/alignment). Reusar o encoder de FSR do VMSA produz um kernel de
  tempo real diagnosticando a falha errada.
- **ARMv7-R tem `DIV` em A32**, ao contrário do ARMv7-A (onde `SDIV`/`UDIV` só existem em Thumb-2
  fora do perfil R/M) — é a diferença de conjunto de instruções mais fácil de esquecer.
- **PMSAv7 e PMSAv8 são arquiteturas de MPU DIFERENTES** (registradores, granularidade e semântica
  de sub-região): B20.2/B20.3 fazem a v7, B20.7 faz a v8 do zero — não tentar generalizar as duas
  num modelo só antes de ter as duas escritas.
- **O perfil R não tem `Hyp` em ARMv7-R mas tem EL2 OBRIGATÓRIO em ARMv8-R** — a intuição "R é A sem
  MMU" quebra exatamente aí.
