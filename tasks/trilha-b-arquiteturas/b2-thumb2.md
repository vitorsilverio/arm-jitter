# B2 — Thumb-2 (decoder de 32 bits + IT blocks) **[REFINAR]**

**Trilha:** B · **Depende de:** B1.6 · **Repo:** arm-jitter
**Status de spec:** alto nível — refinar em sub-tasks (B2.1–B2.5) quando B1 concluir.

## Contexto

Maior salto de decoder do plano: o `ThumbDecoder` atual só conhece encodings de
16 bits (+ o par BL prefix/suffix, que JÁ é um embrião de instrução de 32 bits — ver
`ThumbBlPrefix`/`ThumbBlSuffix` como precedente). Thumb-2 (ARMv6T2/ARMv7) adiciona
encodings de 32 bits que cobrem quase todo o conjunto ARM, mais os IT blocks
(predicação em Thumb). Linux userland ARMv7 e homebrew 3DS moderno são Thumb-2.

## Divisão prevista (cada uma virará uma spec própria)

| Sub | Escopo |
|-----|--------|
| B2.1 | Infra: reconhecer halfword inicial `0b111xx` (exceto BL atual) como 32-bit; pipeline de fetch de 2 halfwords; feature `THUMB2` (já existe no enum — conferir) |
| B2.2 | Data-processing 32-bit (imediatos "modified immediate", MOVW/MOVT, shifts) |
| B2.3 | Load/store 32-bit (offsets de 12 bits, LDRD/STRD Thumb, tabela LDM/STM.W) |
| B2.4 | Branches: B.W/BL/BLX, CBZ/CBNZ, TBB/TBH, IT block |
| B2.5 | Misc: hints, barriers (DMB/DSB/ISB — v7), MSR/MRS Thumb |

## Decisões de desenho já tomadas (respeitar no refinamento)

- **IT blocks viram condição por-op no IR** — o guard condicional por-op do JIT já
  existe e mapeia 1:1; o decoder carrega o estado IT (base cond + máscara, até 4
  instruções) e anota a condição em cada instrução liftada. O estado ITSTATE do CPSR
  (bits 26:25, 15:10) precisa existir para exceções no meio de um bloco IT.
- O IR NÃO muda: Thumb-2 gera os MESMOS `IrOp`s que o ARM equivalente. Instrução nova
  de decoder ≠ op nova de IR.
- Oráculo de decode: validar a tabela contra um assembler/disassembler de referência
  (binutils `arm-none-eabi-objdump` ou capstone) com um corpus gerado — spec do
  refinamento definirá o formato.

## Aceite (do épico, quando todas as subs concluírem)

- Binário Thumb-2 real (hello-world `-mthumb -march=armv7-a`) roda no runner
  user-mode (B4.0) ou em teste de bloco.
- Harness de equivalência cobrindo IT blocks (todas as formas T/E até 4 instruções).
