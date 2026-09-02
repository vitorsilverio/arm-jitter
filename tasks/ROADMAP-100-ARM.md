# Roadmap para 100% de ARM — o mapa medido

**Status: mapa completo, medido em 2026-09-02.** Este documento responde a uma pergunta só:
**o que exatamente falta para o `arm-jitter` implementar ARM 100%** — a regra máxima do
`tasks/README.md` — e em que ordem.

Ele existe porque o mapa estava **incompleto de um jeito que não aparecia**: os épicos B13-B22
cobrem a superfície de DECODE, e havia consenso implícito de que "falta só isso". A medição desta
sessão mostrou que **decode é uma das QUATRO dimensões**, e que duas delas não tinham épico nenhum.

> ⚠️ **Nenhum número aqui é estimativa.** Todos vêm de medição reproduzível sobre
> `docs/COBERTURA-ISA.md`, `target/isa-decode/*.decode` e o código-fonte. A rodada de spec de
> 2026-08-29 aprendeu essa lição do jeito caro: a escada do épico B19 estava dimensionada errada
> em 6× porque alguém contou mnemônicos em vez de linhas de encoding.

## As 4 dimensões

Uma instrução ARM só está **realmente** implementada quando as quatro estão verdes. Hoje a
tabela `docs/COBERTURA-ISA.md` mede **só a primeira**.

| # | Dimensão | Pergunta | Onde se mede hoje | Estado |
|---|---|---|---|---|
| **1** | **Decode + execução interpretada** | O decoder reconhece o encoding e o interpretador executa? | `docs/COBERTURA-ISA.md` | **88%** global |
| **2** | **Emissão JIT nativa** | O backend ASM emite bytecode para essa op, ou o bloco cai no interpretador? | ❌ **não se mede** | 32-bit **46/73** · 64-bit **24/95** |
| **3** | **Truffle** | O backend Truffle executa essa op? | ❌ **não se mede** | 32-bit **40/73** e **quebra** · 64-bit **0** |
| **4** | **Catálogo de processadores** | Um núcleo ARM real nomeado resolve para as features certas? | `ArmProcessor`/`Aarch64Processor` | bloqueado pelas dimensões 1 |

**A dimensão 1 é pré-requisito das outras três**, mas 2 e 3 **não vêm de graça** com ela — é a
descoberta central desta medição, e o motivo de o usuário estar certo ao dizer *"ainda temos um
grande trabalho depois para fazer o jit de tudo e o truffle realmente funcionar"*.

---

## Dimensão 1 — Decode + execução interpretada

Global **88%** (16122/18164 células aplicáveis). O que falta, por origem:

### 1a. Grupos `NOT_IN_ANY_PRESET` — 2246 encodings

Nenhum `ArmArchitecture`/`Aarch64Architecture` declara a extensão, então nem entram no denominador.
**Não é decisão de escopo** — é lacuna de infraestrutura (ver `tasks/README.md`).

| Grupo | Encodings | Épico | Escada | Specs escritas |
|---|---:|---|---:|---:|
| NEON — processamento de dados | 297 | [B13](trilha-b-arquiteturas/b13-plano-neon-a32.md) | 22 | **8** |
| NEON — load/store | 5 | B13 | (incl.) | ✅ |
| NEON — formas compartilhadas | 23 | B13 | (incl.) | 0 |
| VFP incondicional ARMv8-A 32 bits | 17 | [B14](trilha-b-arquiteturas/b14-plano-vfp-armv8-32bit.md) | 7 | **0** |
| MVE / Helium | 352 | [B16](trilha-b-arquiteturas/b16-plano-mve-helium.md) | 14 | **0** |
| SVE / SVE2 | 929 | [B17](trilha-b-arquiteturas/b17-plano-sve.md) | 26 | **0** |
| SME / SME2 | 623 | [B18](trilha-b-arquiteturas/b18-plano-sme.md) | 13 | **0** |

### 1b. Gaps dentro de presets que JÁ existem

| O que | Tamanho | Épico | Escada | Specs |
|---|---|---|---:|---:|
| A64 remanescente | **121 linhas** × 16 colunas de versão | [B19](trilha-b-arquiteturas/b19-plano-a64-gap-remanescente.md) | 9 (+6 de B19.5) | **6** |
| `m-nocp` (perfil M moderno) | 11 encodings × 2 colunas | [B15](trilha-b-arquiteturas/b15-plano-armv8m.md) | 7 | **0** |
| Resíduos de 32 bits | ✅ **fechado** (0 `❌`, 0 `⚠️`) | [B22](trilha-b-arquiteturas/b22-plano-residuos-32-bits.md) | 6 | **6** |

### 1c. Arquiteturas/perfis nunca modelados

| O que | Natureza | Épico | Escada | Specs |
|---|---|---|---:|---:|
| Perfil R (ARMv7-R/ARMv8-R, PMSA/MPU) | modelo de sistema inteiro, não decode | [B20](trilha-b-arquiteturas/b20-plano-perfil-r.md) | 9 | **0** |
| ARMv1/v2/v2a/v3 (modelo de 26 bits) | modelo de estado, `R15`=PC+PSR | [B21](trilha-b-arquiteturas/b21-plano-arm-26-bits.md) | 8 | **0** |

### 1d. Task irmã sem número de épico

**NEON FP16 AArch32** — as formas de meia precisão do NEON A32, recusadas degrau a degrau com
destino registrado: 22 linhas pela **B13.6**, 4 pela **B13.8**, mais as de B13.13. Depende da
fundação **B19.5.1** (a MESMA do `FEAT_FP16` do A64). Ver `b13-plano-neon-a32.md`.

### Resumo da dimensão 1

**126 degraus de escada no total; 20 têm spec escrita; faltam 106.**

---

## Dimensão 2 — Emissão JIT nativa (épico [C12](trilha-c-perf/c12-plano-jit-nativo.md), NOVO)

Uma op sem emissão nativa **funciona** (cai no interpretador), mas custa caro: a política é
`WHOLE_BLOCK`, então **UMA op não suportada derruba o BLOCO INTEIRO** para o interpretador.

| Pipeline | Política | Cobertura medida | Falta |
|---|---|---|---|
| 32 bits | `codegen/jvm/AsmNativePolicy` (casa por record) | **46/73** nativos | 27 records `-> false` + ~8 carve-outs condicionais |
| 64 bits | `codegen64/jvm64/Ir64NativePolicy` (casa por `Kind`) | **24/95** nativos | **71 Kinds** |

**O achado**: o Javadoc de `Ir64NativePolicy` afirma *"com isso, TODO `Ir64Op.Kind` existente hoje é
suportado nativamente"*. Era verdade na **B6.5.4**. Desde então entraram B8 (toda a AdvSIMD), B10
(EL2/EL3), B11, B19 — e **nada disso tem emissão nativa**. Um bloco AArch64 com uma única instrução
SIMD roda inteiro interpretado.

---

## Dimensão 3 — Truffle (épico [A10](trilha-a-truffle/a10-plano-truffle-completo.md), NOVO)

| Item | Estado |
|---|---|
| `IrOpNodeFactory` (32 bits) | **40/73** Kinds; `default -> throw new IllegalStateException` |
| `TruffleCodeEmitter#supports` | **`return true` sempre** ⇒ o fallback é inatingível |
| Truffle para AArch64 | **não existe** (zero nós de 64 bits no módulo) |

**Consequência medida**: o backend Truffle **não cai para o fallback — ele quebra** (exceção) em
qualquer op de VFP, NEON, DSP dual/top-word, `HVC`/`SMC`/`ERET`/`MRS_bank`/`MSR_bank`, bitfield,
`RBIT`, `SDIV`/`UDIV`, registrador de sistema do perfil M, `BKPT` e coprocessador duplo — **33
Kinds**. É o achado que a RFC B13.2 registrou como "pré-existente, verificado", agora quantificado.

---

## Dimensão 4 — Catálogo de processadores

`ArmProcessor` (24 constantes) e `Aarch64Processor` (41) resolvem um núcleo ARM real nomeado para
uma arquitetura. **As pendências do B12 são todas downstream das dimensões acima** — nenhuma é
trabalho de catálogo:

| Núcleos que faltam | Bloqueado por |
|---|---|
| `Cortex-A32` | `LDA`/`STL`/`CRC32` ⇒ **B14** |
| `SC300`, `Cortex-M3`/`M4`/`M7`/`M23`/`M33`/`M35P`/`M52`/`M55`/`M85` (10) | presets ARMv7E-M/ARMv8-M/ARMv8.1-M ⇒ **B15** |
| `Cortex-A8`/`A9`/`A5`/`A7`/`A15`/`A17`… | NEON ⇒ **B13.22** |
| Toda a linha `Cortex-R` | ⇒ **B20** |
| `ARM1`/`ARM2`/`ARM250`/`ARM3`… | ⇒ **B21** |

⚠️ **Uma entrada de catálogo factualmente errada é pior que uma ausente** — foi a decisão consciente
da B12.4 (não mapear `Cortex-M3` para `ARMV7M`, que inclui DSP) e da B12.6 (`Cortex-A32`). Nenhum
núcleo entra no catálogo "por aproximação". Precisa de uma task de fechamento própria depois que
B13.22/B14/B15/B20/B21 fecharem — hoje ela não existe e **não pode ser escrita ainda**, porque
depende de features que ainda não existem.

---

## Ordem recomendada

Critério: **destravar dimensões inteiras primeiro**, depois volume.

1. ~~**B22.6** — fecha o épico B22~~ ✅ **FEITA 2026-09-02** (A32/T16/T32/VFP em 0 `❌`/0 `⚠️`,
   `⚠️` zero no projeto; `VALIDACAO-ARQUITETURAS.md` redatada). Épico B22 fechado.
2. **A10.1** — fazer o `TruffleCodeEmitter#supports` dizer a verdade. É uma correção **pequena** que
   troca "quebra com exceção" por "cai no fallback", e é a única coisa entre o Truffle e a
   honestidade. Antes de qualquer expansão de cobertura Truffle.
3. **B19.4 → B19.5.1 → B19.5.2** — o A64 é o maior salto de cobertura global disponível, e a
   B19.5.2 conserta 168 células de denominador errado.
4. **B13.7 → B13.8** — fecham a seção "2-reg-and-shift" do NEON.
5. **C12.1** — remedir e destravar a política de emissão nativa do A64 (hoje 24/95, com Javadoc
   mentindo).
6. Depois: B14 → B15 → B13.9+ → B16 → B17 → B18, com B20/B21 quando o usuário abrir.

## O que este roadmap NÃO faz

**Não substitui a decomposição em specs.** Os 106 degraus sem spec continuam sem spec, e escrevê-los
exige **medição instrução a instrução** contra `target/isa-decode/` — o que, no ritmo estabelecido
(≈3 specs por sessão, cada uma medida contra o oráculo real e contra o código), é trabalho de
**dezenas de sessões**. Escrever spec sem medir é exatamente o erro que a remedição do B19 pegou.

A ordem acima é o que cada rodada de spec futura deve seguir para decidir o que decompor em seguida.

## Regras que continuam valendo (não reabrir sem o usuário)

- **Congelamento de subprojetos** (`tasks/README.md`, 2026-08-27): nenhuma task de `armbox`/`gbaemu`/
  `ndsemu`/`virtual-arm-box`/`n3dsemu` até a cobertura fechar. **Fechar um épico não descongela
  nada** — o congelamento é sobre a cobertura TOTAL, e este documento mostra o tamanho real dela.
- **Release `1.4.0` reservada para 100%**; os gatilhos de +5pp/+10pp seguem **suspensos**.
- **`docs/isa-nao-aplicavel.tsv` nunca recebe "está fora do nosso alvo"** — só "esta versão
  introduziu a instrução depois desta coluna", com a fonte.
