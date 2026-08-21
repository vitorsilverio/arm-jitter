# B7/B8 — plano de cobertura de ISA: fechar as lacunas das arquiteturas que os emuladores usam

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Documento MESTRE da frente de cobertura. Cada degrau (`B7.x`, `B8.x`) é uma task de uma sessão.
A fonte da verdade do que falta é sempre `docs/COBERTURA-ISA.md`, **regenerada no início de cada
task** (`./gerar-cobertura-isa.sh`) — nunca a lista colada aqui, que envelhece.

## O diagnóstico, com número

O pedido veio da leitura de que "não implementamos quase nada de um processador ARM, muita coisa
funciona por sorte". A medição diz que isso está **meio certo** — e a metade errada muda para onde
o trabalho vai:

| Alvo | Onde é usado | Cobertura hoje | Faltam |
|---|---|---:|---:|
| **ARMv6K / ARM11 MPCore** | `virtual-arm-box` raspi1, `n3dsemu` | **82%** | 67 |
| ARMv7-A | `armbox` | **83%** | 111 |
| **A64 (AArch64)** | `virtual-arm-box` raspi3+ | **18%** | 935 |
| ARMv5TE | `ndsemu` | 67% | — |
| ARMv4T | `gbaemu` | 62% | — |
| **Global** | | **53%** (2142/4001 células) | |

Ou seja: no **32 bits** já estamos acima dos 80% pedidos — o que derruba os emuladores lá não é
"quase nada implementado", é a **cauda longa**: 67 instruções que, por azar, são exatamente as que
um compilador real emite (`VCVT`/`VMOV` em suas várias formas, multiplicação-acumulação fundida).
No **A64** a leitura original está certa: 18% é funcionar por sorte, e é lá que está 90% do
trabalho desta frente.

## Meta

1. **Mínimo 80% por arquitetura-alvo** — ARMv6K/MPCore e ARMv7-A já passaram; **A64 é a meta real**.
2. **Alvo final: tudo ✅.** Uma instrução só sai da conta indo para `docs/isa-nao-aplicavel.tsv`
   com a versão de arquitetura que a introduziu — nunca por ser considerada "improvável". O
   precedente é EL1/EL2, descartado como desnecessário e depois exigido inteiro pelo
   `virtual-arm-box`.

## Regra de triagem (vale para toda task desta frente)

O inventário do QEMU agrupa por **espaço de encoding**, não por versão de arquitetura: o
`a32.decode` traz ARMv4 e ARMv8 na mesma lista. Antes de implementar, cada task **tria** a sua
lista:

- instrução pertence à arquitetura-alvo → **implementar**;
- pertence a uma versão POSTERIOR → linha em `docs/isa-nao-aplicavel.tsv` **com a fonte**;
- **na dúvida, implementar.** Errar para o lado de implementar custa trabalho; errar para o lado de
  excluir recria exatamente o problema que esta frente existe para acabar.

## Escada — 32 bits (B7)

| Task | Escopo | Alvos | Tamanho |
|---|---|---|---:|
| **B7.1** | A32 DSP/media: `SMLAD`/`SMLADX`/`SMLSD`/`SMLSDX`/`SMLALD`/`SMLALDX`/`SMLSLD`/`SMLSLDX`/`SMMLA`/`SMMLAR`/`SMMLS`/`SMMLSR` + `UDF` | MPCore, v7-A | ~13 |
| **B7.2** | A32, triagem + implementação do resto: `MOVW`/`MOVT`/`MLS`/`SBFX`/`UBFX`/`BFCI`/`RBIT`/`SDIV`/`UDIV` (⚠️ ARMv6T2/v7 — provável exclusão para ARMv6K puro; **conferir**) | MPCore | ~9 |
| **B7.3** | T16 ARMv6 genuínos: `CPS`, `REV`, `REVSH`, `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB`, `SETEND` + hints (`YIELD`/`WFE`/`WFI`/`SEV`/`NOP`) | todos | ~14 |
| **B7.4** | T16 restante, com triagem: `IT`, `CBZ`, `HLT`, `UDF`, `B`, `BLX` | v7-A | ~6 |
| **B7.5** | VFP `VCVT` (8 formas) + `VMOV` (7 formas) — VFPv2 genuínos, é o que mais aparece em código real | MPCore, v7-A | ~15 |
| **B7.6** | VFP fundidas `VFMA`/`VFMS`/`VFNMA`/`VFNMS` (⚠️ VFPv4 — provável exclusão para MPCore; **conferir**) | v7-A | ~8 |
| **B7.7** | T32 (Thumb-2) — 58 lacunas: `LDM`/`STM`/`RFE`/`SRS`, `LDRxT`/`STRxT` (não privilegiadas), `MRS`/`MSR`, `PKH`, DSP/media, `SMC`/`HVC`/`ERET`/`BXJ` | v7-A | ~58 |

## Escada — A64 (B8) — onde está o trabalho

935 lacunas. Dividida por classe funcional, cada degrau é uma sessão razoável:

| Task | Escopo | Tamanho |
|---|---|---:|
| **B8.1** | Load/store escalar: `LDR`/`STR`/`LDP`/`STP` restantes, `LDXP`/`STXP`, `CAS`/`CASP`, `LDAR`/`STLR` | ~95 |
| **B8.2** | Inteiro restante: `ADC`/`SBC`, `EXTR`, `CLZ`/`CLS`/`CNT`/`RBIT`/`REV`, `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL`/`SMULH`/`UMULH`, `SETF`/`RMIF`/`CFINV`/`XAFLAG`/`AXFLAG` | ~30 |
| **B8.3** | Branch/system: `CB*`, `BRK`/`HLT`, `DSB`/`CLREX`/`SB`, `MSR`/`MRS` restantes, `WFET`/`WFIT`, `SYS` | ~35 |
| **B8.4** | FP escalar — aritmética: `FADD`/`FSUB`/`FMUL`/`FDIV`/`FNMUL`, `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB`, `FMAX`/`FMIN`/`FMAXNM`/`FMINNM`, `FABS`/`FNEG`/`FSQRT` | ~40 |
| **B8.5** | FP escalar — comparação/seleção/conversão: `FCMP`/`FCCMP`/`FCSEL`, `FCVT*`, `SCVTF`/`UCVTF`, `FRINT*`, `FMOV` | ~110 |
| **B8.6** | AdvSIMD load/store estruturado: `LD1`-`LD4`/`ST1`-`ST4` e variantes | ~60 |
| **B8.7** | AdvSIMD inteiro — aritmética e comparação | ~150 |
| **B8.8** | AdvSIMD — deslocamento, saturação e estreitamento (`SQ*`/`UQ*`/`*SHRN`) | ~140 |
| **B8.9** | AdvSIMD FP vetorial | ~120 |
| **B8.10** | AdvSIMD — permutação, redução, tabela (`EXT`/`UZP`/`TRN`/`ZIP`/`TBL`/`*V`) | ~40 |
| **B8.11** | Extensões opcionais, com triagem: cripto (`AES*`/`SHA*`/`SM3`/`SM4`/`PMULL`), PAC (`AUTI*`/`BRA*`/`XPAC*`), MTE (`STG*`/`LDG*`/`IRG`), `CPY*`/`SET*` (ARMv8.8 memcpy), dot/matmul (`SDOT`/`SMMLA`/`BF*`) — **a maioria não está no Cortex-A53 do raspi3**; a entrega desta task pode ser majoritariamente `isa-nao-aplicavel.tsv` justificado | ~180 |

**Ordem sugerida**: B8.1 → B8.2 → B8.3 (é o que um kernel Linux realmente executa, e destrava a
F11) → B8.4/B8.5 → o resto de AdvSIMD conforme surgir necessidade real.

## Além da tabela: dois achados que valem mais que uma linha ✅

- **`E6` (✅ fechada 2026-08-21)** — `ArmDecoder` decodificava todo o espaço incondicional
  (`cond==0b1111`) como se fosse condicional: `0xF2000000` (`VHADD` de NEON) virava `AND cond=AL`.
  Corrigido: `decodeUnconditional` novo reconhece explicitamente os grupos do espaço (`BLX`
  imediato, `SETEND`, `CPS`, `CLREX`, `PLD`/`PLI`, `DMB`/`DSB`/`ISB`, `SRS`, `RFE` + extensões de
  coprocessor/VFP) e devolve `UNIMPLEMENTED` para o resto — nunca mais cai no dispatch condicional
  genérico. Ver `trilha-e-manutencao/e6-espaco-incondicional-undefined.md`. **B8 já pode ser
  pega**: lacunas novas agora se manifestam como instrução indefinida, não corrupção silenciosa.
- A tabela mede **decode, não semântica**. `STREX` (E3) e `LDR/STR` alinhado (F3) decodificavam e
  estavam errados.

## Protocolo de cada task desta frente

1. `./gerar-cobertura-isa.sh` no início — a lista vem da tabela, não deste documento.
2. Triar (regra acima) antes de implementar.
3. Encoding conferido contra fonte real (`a32.decode`/`a64.decode` do QEMU + ARM ARM), nunca de
   memória — precedente da B6.8, que registrou esse aviso.
4. Corpus de teste real quando possível (`aarch64-none-elf-as`/`arm-none-eabi-as` + `objdump`),
   como o `Aarch64DecoderCorpusTest` já faz.
5. Fechar com `./gerar-cobertura-isa.sh` de novo e **commitar a tabela atualizada** — é ela que
   mede o progresso e dispara o release.
6. **G5** (suítes dos consumidores) + **push** — ver `tasks/README.md`.
