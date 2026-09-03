# B13 — NEON / Advanced SIMD de 32 bits (A32 + T32): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE do épico. Nasce de uma cobrança direta do usuário (2026-08-28): por que
`docs/COBERTURA-ISA.md` tem grupos inteiros marcados "não se aplica a nenhum preset atual" —
NEON, MVE, SVE, SME — se a regra máxima do `tasks/README.md` diz que o alvo é ARM inteiro?

**Resposta honesta: "não se aplica a nenhum preset atual" nunca foi uma decisão de escopo — é
uma descrição factual de uma lacuna de infraestrutura**, exatamente como o B11 diagnosticou para o
lado A64 ("isso nunca foi uma escolha de escopo do usuário — é uma lacuna de infraestrutura"). O
`IsaCoverageReport` marca esses grupos com `NOT_IN_ANY_PRESET` porque nenhum `ArmArchitecture`
declara a extensão; não porque alguém decidiu não implementar. A correção de rumo do usuário
registrada no B11 vale integralmente aqui, e foi repetida com todas as letras nesta sessão:

> o `arm-jitter` é publicado no Maven Central como **biblioteca ARM** — qualquer pessoa pode
> construir um projeto sobre ela. "Nenhum projeto meu usa NEON hoje" é fato sobre REGRESSÃO A
> TESTAR (G5), nunca argumento para adiar. Uma extensão ARM real não precisa de consumidor interno
> pedindo — precisa só de existir no ARM.

## O número (medido nesta sessão, `target/isa-decode/`)

| Grupo | Arquivo | Encodings | Cobertura hoje |
|---|---|---:|---|
| NEON — processamento de dados | `neon-dp.decode` | 297 | 0% (`NOT_IN_ANY_PRESET`) |
| NEON — load/store | `neon-ls.decode` | 5 | 0% (`NOT_IN_ANY_PRESET`) |
| NEON — formas compartilhadas VFP/NEON | `neon-shared.decode` | 23 | 0% (`NOT_IN_ANY_PRESET`) |
| **Total deste épico** | | **325** | |

Quebra medida de `neon-dp.decode` por família de encoding (as próprias seções `#####` do arquivo):

| Família | Linhas do `.decode` | Encodings |
|---|---|---:|
| 3-reg-same (`1111 001 U 0 D sz Vn Vd opc N Q M op Vm`) | 35-184 | 84 |
| 2-reg-and-shift (shift por imediato, narrowing, long, `VCVT` fixo↔float) | 185-363 | 94 |
| 1-reg-and-modified-immediate (`VMOV`/`VORR`/`VBIC`/`VMVN` imediato) | 364-385 | 1 |
| two-reg-misc / 3-reg-different-lengths / 2-reg-scalar / `VEXT` / `VTBL` / dup-scalar | 386-621 | 118 |

## O que falta de infraestrutura (investigado no código, não suposto)

1. **`ArmFeature` não tem NENHUMA feature de Advanced SIMD.** O enum vai de `BLX` a
   `VIRTUALIZATION_EXTENSIONS` (39 constantes) e tem `VFPV2` e
   `VFP_FUSED_MULTIPLY_ACCUMULATE` — nada de NEON. Não é uma feature "desligada": não existe.
2. **O banco de registradores não comporta NEON.** `core/VfpRegisters.java` é
   `private final int[] s = new int[32]` (`SINGLE_COUNT = 32`), e `d(index)`/`setD(index)` montam o
   `D` a partir de DOIS `S` — ou seja, só **D0-D15** são endereçáveis. NEON exige **D0-D31**
   (VFPv3-D32) com vista **Q0-Q15** de 128 bits. `VfpDecoder#validDoubleRegister` já rejeita
   D16-D31 explicitamente, com o comentário `"D16-D31 não existem neste projeto (sem VFPv3-D32/
   NEON)"` — a lacuna está documentada no próprio código.
3. **Nenhum preset declara NEON**, logo `IsaCoverageReport` marca os 3 grupos como
   `NOT_IN_ANY_PRESET` e eles nem entram no denominador global.

Isto é **o mesmo problema que a B8.6 resolveu do lado A64** ("estendeu `Aarch64FpRegisters` para
128 bits reais — reabre B6.5.1 D4, decisão explícita do usuário: pré-requisito de toda a escada
AdvSIMD, não só daquela task"). O precedente existe, funcionou, e dá o formato da fundação aqui.

## A decisão de arquitetura que este épico precisa tomar primeiro (B13.2)

O projeto já implementou **quase toda a superfície AdvSIMD do A64** (B8.6-B8.20: three-same,
shift-by-immediate, FP vetorial, permute/reduce/table, copy, indexed-element, cripto — ~500
encodings), com IR e executor no pipeline de 64 bits (`ir64`/`executor64`). NEON de 32 bits é, em
grande parte, **a mesma semântica vetorial** com outro encoding — o próprio QEMU compartilha os
helpers entre A32 NEON e A64 AdvSIMD (é o motivo do comentário `_rev` em `neon-dp.decode`:
"...call Neon helper functions that are **shared with AArch64**").

Existem duas saídas, e escolher errado custa o épico inteiro:

- **(a) espelhar** — criar `IrOp`s vetoriais próprios no pipeline de 32 bits, como B6.5.x espelhou
  B3.x para FP escalar. Simples, isolado, respeita a disciplina "nunca misturar os dois mundos" do
  B6 — mas duplica ~500 encodings de semântica já escrita e testada, e cria duas fontes de verdade
  para o mesmo comportamento ARM (risco de divergência exatamente do tipo que o G1 existe para
  evitar).
- **(b) extrair o núcleo vetorial** — mover a semântica de lane (largura de elemento, saturação,
  arredondamento, narrow/widen) para um módulo compartilhado que os dois pipelines chamam,
  mantendo `IrOp`/`Ir64Op` separados como hoje. Mais trabalho de fundação, mas uma fonte de verdade.

**B13.2 é uma RFC** (mesmo formato de `docs/RFC-SOFTMMU.md` e `docs/RFC-M-PROFILE.md`) que decide
isso com o código na mão, ANTES de qualquer decode novo. Nenhuma task de B13.3 em diante deve ser
pega antes da RFC fechar — é a diferença entre 12 sessões e 25.

✅ **RFC FECHADA em 2026-08-29: `docs/RFC-NEON-NUCLEO-VETORIAL.md` — venceu a opção (b), EXTRAÇÃO**,
no nível da PALAVRA de 64 bits (`advsimd/AdvSimdRegisterWords` + `AdvSimdLanes`); leitura
obrigatória antes de pegar qualquer task de B13.3 em diante. Duas consequências que mudam as
sub-tasks abaixo: (1) cada família migra suas operações para o núcleo COMPARTILHADO em vez de
reescrever a semântica — o que já existe testado no A64 vale para os dois lados; (2) o decoder
vetorial de 32 bits entrega o `IrOp` PRONTO pelo escape hatch `DecodedInstruction#liftedOp`
(`InstructionKind.LIFTED_IR_OP`), nunca espremendo a forma do operando nos campos neutros. Ver
também os "Achados abertos" da RFC — em especial que o backend Truffle hoje quebra com qualquer
`Kind` de VFP/vetorial (`IrOpNodeFactory`).

## Escada (refinar em spec própria quando cada degrau for pego)

| Task | Escopo | Encodings | Depende de |
|---|---|---:|---|
| **B13.1** | Fundação do banco: `VfpRegisters` cresce para D0-D31 (64 bits reais, `S0-S31` viram vista dos 16 `D` baixos) + vista `Q0-Q15` de 128 bits; `ArmFeature.ADVANCED_SIMD` + `ArmFeature.VFPV3_D32` novos; `saveState`/`loadState`/`snapshot` acompanham (⚠️ formato de save-state dos consumidores). SEM decode novo — espelho direto de B8.6 | 0 | — |
| **B13.2** | **RFC**: reuso do núcleo vetorial A64 (B8.6-B8.20) pelo pipeline de 32 bits vs espelhamento. Decide (a)/(b) acima, com protótipo de uma família só (`VADD`/`VSUB` inteiro) para medir o custo real | 0 | B13.1 |
| **B13.3** | NEON load/store: `VLDST_multiple`/`VLD_all_lanes`/`VLDST_single` (`VLD1`-`VLD4`/`VST1`-`VST4`, multiple + single-lane + all-lanes) — espelho de B8.6 | 5 | B13.2 |
| **B13.4** | 3-reg-same **inteiro**: aritmética/comparação/lógica (`VADD`/`VSUB`/`VMUL`/`VAND`/`VORR`/`VEOR`/`VBSL`/`VCGE`/`VCGT`/`VMAX`/`VMIN`/`VABD`/`VHADD`/`VRHADD`/`VTST`/`VPADD`/`VPMAX`/`VPMIN`) | ~50 | B13.2 |
| **B13.5** | 3-reg-same **saturante/deslocamento** (`VQADD`/`VQSUB`/`VSHL`/`VRSHL`/`VQSHL`/`VQRSHL`/`VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH`) | ~20 | B13.4 |
| **B13.6** | 3-reg-same **ponto flutuante** (`VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VMLA_fp`/`VFMA_fp`/`VCEQ_fp`/`VCGE_fp`/`VACGE`/`VMAX_fp`/`VRECPS`/`VRSQRTS`/`VPADD_fp`/`VMAXNM_fp`) — inclui as formas `_hp` (gate `FEAT_FP16` próprio) | ~14 | B13.4 |
| **[B13.7](b13.7-neon-2reg-shift-imediato.md)** | 2-reg-and-shift: deslocamento por imediato (`VSHR`/`VSRA`/`VRSHR`/`VRSRA`/`VSHL`/`VSLI`/`VSRI`/`VQSHL`/`VQSHLU`). **Spec 2026-08-29** | **56** (medido; era "~50") | B13.4 |
| **[B13.8](b13.8-neon-2reg-shift-narrow-widen-vcvt.md)** | 2-reg-and-shift: estreitamento/alargamento (`VSHRN`/`VRSHRN`/`VQSHRN`/`VQRSHRN`/`VQSHRUN`/`VQRSHRUN`/`VSHLL`) + `VCVT` fixo↔float **F32**. **Spec 2026-09-02**; as 4 linhas `VCVT` **F16** ficam para a task irmã "NEON FP16 AArch32" (depende de B19.5.1) | **34** + 4 adiadas (era "~44") | B13.7 |
| **[B13.9](b13.9-neon-1reg-imediato-modificado.md)** | 1-reg-and-modified-immediate (`Vimm_1r`: `VMOV`/`VORR`/`VBIC`/`VMVN` imediato) — o buraco que a B13.7 deixou de propósito (`immh==0`). **Achado**: o irmão A64 (`Vimm`) também está ❌ e `AdvSIMDExpandImm` não existe no projeto ⇒ nasce no núcleo `advsimd/` | 1 | B13.4, B13.7 |
| **[B13.10](b13.10-neon-3reg-different-lengths.md)** | 3-reg-different-lengths: alargando (`VADDL`/`VADDW`/`VMULL`/`VMLAL`/`VQDMULL`…) e estreitando (`VADDHN`/`VRADDHN`/`VSUBHN`/`VRSUBHN`). Abre o 3º frame (bit23=1, **bit4=0**); `size==0b11` devolve `null` para B13.12-B13.15 | **26** (medido; plano estimava ~35) | B13.4, B13.8 |
| **[B13.11](b13.11-neon-2reg-and-scalar.md)** | 2-reg-and-scalar — espelho A32 da classe indexada da B8.19. **O índice A32 NÃO é o A64**: `Vm` limitado a `D0`-`D7` (halfword) / `D0`-`D15` (word), índice em bits espalhados | **19** (plano ~24) | B13.10 |
| **[B13.12](b13.12-neon-two-reg-misc.md)** | two-reg-misc, sub-grupo `size==0b11` — **layout de campos PRÓPRIO** (`size`=bits[19:18], `opc1`=[17:16], `opc2`=[10:7]), diferente de tudo em B13.4-B13.11. Deixa `null` para B13.13/14/15 | **36** (plano ~45) | B13.10 |
| **[B13.13](b13.13-neon-two-reg-misc-conversoes.md)** | two-reg-misc de conversão/arredondamento: `VRINT*` (6), `VCVT` de precisão (3), `VCVTA/N/P/M{S,U}` (8), `VCVT_{FS,FU,SF,UF}` (4). `binary16` já vem pronto da B19.4; `VCVT_B16_F32` depende da B19.7 (decisão (a)/(b) na spec) | **21** (plano ~25) | B13.12 |
| **[B13.14](b13.14-neon-permuta-tabela.md)** | `VEXT`/`VTBL`/`VDUP_scalar`(×3)/`VSWP`/`VTRN`/`VUZP`/`VZIP`. **Achado**: `VUZP`/`VTRN`/`VZIP` do A32 escrevem os DOIS registradores — NÃO são as `UZP1`/`UZP2`/… do A64 (seis instruções de um destino); exige `IrOp` próprio e buffer | **9** (plano ~10) | B13.12 |
| **[B13.15](b13.15-neon-cripto.md)** | Cripto no espaço NEON de 32 bits: `AESE`/`AESD`/`AESMC`/`AESIMC`/`SHA1H`/`SHA1SU1`/`SHA256SU0` — semântica JÁ existe (B8.11/B8.11b), só o encoding é novo. Gate `ArmFeature.CRYPTO` novo. **Fecha o `null` do sub-grupo `size==0b11`** | **7** (plano ~15 — as formas de 3 registradores NÃO estão em `neon-dp`) | B13.12, B13.14 |
| **[B13.16](b13.16-neon-t32.md)** | **T32**: adaptador que TRANSFORMA e DELEGA aos decoders A32 (A32 `1111_001p_q…` ↔ T32 `111p_1111_q…`, 24 bits baixos idênticos). Confirmado pela fonte: é transformação mecânica, não reimplementação. **`neon-shared` dispensa esta task** (encoding já compartilhado) | (as mesmas 297) | B13.9-B13.15 |
| **[B13.17](b13.17-neon-shared-fcma.md)** | `neon-shared`: `VCMLA`/`VCADD` (`FEAT_FCMA`). Cria o `NeonSharedDecoder` compartilhado por B13.17-B13.21. **Exceção do épico: NÃO há semântica A64 para migrar** (`FCMLA`/`FCADD` do A64 também faltam) ⇒ nasce no núcleo. `size` tem sentido INVERTIDO vs `3same_fp` | 4 | B13.6 |
| **[B13.18](b13.18-neon-shared-dotprod.md)** | `neon-shared`: `VSDOT`/`VUDOT` (`FEAT_DotProd`) + `VUSDOT`/`VSUDOT` (**`FEAT_I8MM`** — duas features, não uma) e as formas `_scalar` | 7 | B13.17 |
| **[B13.19](b13.19-neon-shared-i8mm-matricial.md)** | `neon-shared`: `VSMMLA`/`VUMMLA`/`VUSMMLA` (`FEAT_I8MM` matricial). Compartilha a semântica com a **B19.12** — quem rodar primeiro põe no núcleo | 3 | B13.18 |
| **[B13.20](b13.20-neon-shared-fhm.md)** | `neon-shared`: `VFML`/`VFML_scalar` (`FEAT_FHM`). Irmã da **B19.13**; `q=0` e `q=1` leem campos de registrador DIFERENTES (`_sp` × `_dp`) e os extratores das `_scalar` diferem entre as duas | 4 | B13.17 |
| **[B13.21](b13.21-neon-shared-bf16.md)** | `neon-shared`: `VDOT_b16`/`VFMA_b16`/`VMMLA_b16` + `_scal` (`FEAT_BF16`). **Depende da B19.7** pelo núcleo `bfloat16` (o JDK não tem esse formato). **Fecha o arquivo `neon-shared`** e o `null` do decoder | 5 | B13.19, B19.7 |
| **[B13.22](b13.22-neon-fechamento-presets.md)** | **Fechamento**: presets públicos com NEON (`ARMV7A_NEON`), entradas de `ArmProcessor` que faltam por falta de NEON, e `Applicability` real nos 3 grupos — **é esta task que faz "não se aplica a nenhum preset atual" sumir**. Primeira task NÃO-zero-diff do épico; a cobertura global pode CAIR (denominador +325) e isso é o certo | 0 | B13.16, B13.21 |

### Task irmã fora da escada: **NEON FP16 AArch32**

Não tem número B13.x porque **atravessa vários degraus** e depende de fundação de outro épico. As
formas de MEIA PRECISÃO do NEON A32 vão sendo recusadas (`UNIMPLEMENTED` explícito) degrau a degrau,
com destino registrado, e serão fechadas de uma vez quando a fundação existir:

| Origem | O que ficou de fora | Linhas |
|---|---|---:|
| **B13.6** (`## Resultado`) | formas `sz=1` das 22 linhas de 3-reg-same FP | 22 |
| **B13.8** (decisão da spec) | `VCVT_SH`/`VCVT_UH`/`VCVT_HS`/`VCVT_HU` (`@2reg_vcvt_f16`) | 4 |
| B13.13 (previsto) | `VCVT_F16_F32`/`VCVT_F32_F16` e conversões two-reg-misc | a medir |

**Depende de [B19.5.1](b19.5.1-nucleo-meia-precisao.md)** (caminho `esz=1`/binary16 em
`advsimd/AdvSimdLanes`) — a MESMA fundação que o `FEAT_FP16` do lado A64 usa. Foi exatamente para não
criar assimetria entre os dois lados que a B13.6 recusou F16 em vez de implementá-la só em A32; a
fundação compartilhada é o que torna essa recusa uma decisão de sequenciamento, não uma exclusão
(regra máxima do `tasks/README.md`).

## Meta

Os 3 grupos NEON deixam de ser `NOT_IN_ANY_PRESET` em `IsaCoverageReport#GROUPS` e passam a ser
medidos de verdade contra um preset que declara `ADVANCED_SIMD` — com as 325 células entrando no
denominador global e sendo ✅.

## Invariantes específicos deste épico

- **G2 continua valendo**: NEON NÃO existe em `ARMV4T`/`ARMV5TE`/`ARMV6K`/`ARM11_MPCORE`. É extensão
  OPCIONAL do ARMv7-A (e do ARMv8-A 32 bits). O ARM11 MPCore do 3DS não tem NEON — `n3dsemu` não
  pode passar a ver essas instruções decodificando.
- **G3 (sem breaking change)**: `VfpRegisters` cresce de forma aditiva; o preset `ARMV7A` atual
  continua SEM NEON (preset novo `ARMV7A_NEON` para quem quiser). Os 5 consumidores não podem ver
  diferença nenhuma até optarem pelo preset novo.
- **G8**: cada família nova fecha o seu espaço de encoding — o que sobra tem que cair em
  `UNIMPLEMENTED`, nunca virar outra instrução. O espaço NEON de 32 bits é o **espaço
  incondicional** (`cond=0b1111`), que `docs/COBERTURA-ISA.md` já registra como mal decodificado
  historicamente (o achado do `0xF2000000`/`VHADD` decodificando como `AND`, task E6) — esta escada
  é o que finalmente fecha esse buraco.
- **Save-state**: `VfpRegisters#saveState`/`loadState` são serializados pelos save-states de
  `gbaemu`/`ndsemu`. Crescer o banco muda o formato — B13.1 precisa versionar, não quebrar
  arquivos `.ss` existentes.

## Armadilhas conhecidas (do próprio inventário)

- **Operandos invertidos (`_rev`)**: o `neon-dp.decode` documenta que nos deslocamentos `Vn`/`Vm`
  aparecem trocados em relação à sintaxe do manual da ARM ("The `_rev` suffix indicates that Vn and
  Vm are reversed"). Copiar o padrão do decodetree sem ler esse comentário produz shift com os
  operandos ao contrário — bug silencioso, não erro de decode.
- **`size` das formas FP não é tamanho**: mesma armadilha que a B8.9 achou no A64 ("bits[23:22] NÃO
  é tamanho puro nas formas FP") — aqui o `.decode` avisa: "For FP insns the high bit of 'size' is
  used as part of opcode decode".
- **`Q` faz parte do opcode** em narrowing/long shifts ("here the Q bit is part of the opcode
  decode"), não é só seletor de largura — mesmo achado que B8.8 teve no A64.
- **`Vimm_1r` é uma linha só de decodetree para 4 instruções** (`VORR`/`VBIC`/`VMOV`/`VMVN`): o
  `cmode`/`op` é conferido na função de tradução, não no padrão. O medidor conta 1 célula; a
  implementação tem 4 comportamentos.
