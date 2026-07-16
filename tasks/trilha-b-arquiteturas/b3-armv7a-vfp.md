# B3 — ARMv7-A user-level + VFP (épico REFINADO em B3.1–B3.7)

**Trilha:** B · **Depende de:** B2.6 · **Repo:** arm-jitter (+ armbox na B3.7)
**Status de spec:** ✅ refinado 2026-07-15 (sessão de modelo forte). NÃO execute este
arquivo — execute as sub-tasks, na ordem B3.1 → B3.2 → B3.3 → B3.4 → B3.5 → B3.6 → B3.7.
Cada sub é um PR independente e mergeável.

## Contexto

Completa o user-mode de 32 bits: com B1+B2+B2.6+B3, binários Linux ARMv7 hard-float e
o core ARM11 do 3DS (VFPv2) ficam cobertos. NEON fica explicitamente FORA (fallback
UNDEFINED documentado; se algum alvo real precisar, vira épico próprio).

## Sub-tasks

| Sub | Arquivo | Escopo | Depende de |
|-----|---------|--------|-----------|
| B3.1 | [b3.1-armv7-inteiro-arm.md](b3.1-armv7-inteiro-arm.md) | Inteiro v7, encodings ARM: MOVW/MOVT, MLS, SBFX/UBFX/BFI/BFC, RBIT, SDIV/UDIV, DMB/DSB/ISB | B2.6 |
| B3.2 | [b3.2-armv7-inteiro-thumb2.md](b3.2-armv7-inteiro-thumb2.md) | Mesmo grupo em encodings Thumb-2 (bitfield, MLS, RBIT, SDIV/UDIV) | B3.1 |
| B3.3 | [b3.3-vfp-banco-registradores.md](b3.3-vfp-banco-registradores.md) | Banco S/D + FPSCR no core, snapshot/save-state | — (paralelo a B3.1/B3.2) |
| B3.4 | [b3.4-vfp-ir-interpretador.md](b3.4-vfp-ir-interpretador.md) | `IrOp`s de FP + `IrVfpExecutor` interpretado | B3.3 |
| B3.5 | [b3.5-vfp-decoder.md](b3.5-vfp-decoder.md) | `VfpDecoder` (espaço CP10/11) ARM + Thumb-2 | B3.4 |
| B3.6 | [b3.6-vfp-asm-nativo.md](b3.6-vfp-asm-nativo.md) | Emissão ASM nativa das ops de FP | B3.5 |
| B3.7 | [b3.7-preset-armv7a-armbox.md](b3.7-preset-armv7a-armbox.md) | Preset `ARMV7A` + validação armbox com binário gcc hard-float | B3.1-B3.6 |

## Decisões fechadas (não reavaliar nas subs)

1. **VFP antes de NEON; NEON fora do épico.** Encodings NEON caem no UNDEFINED
   controlado existente.
2. **Alvo VFP = VFPv2 + `VMOV.F32/F64 #imm` do VFPv3-d16** (gcc emite constantes
   assim mesmo com `-mfpu=vfp`; é 1 encoding barato). VCVT fixed-point do VFPv3 fica
   FORA (UNDEFINED documentado).
3. **Semântica FP = IEEE 754 do Java** (round-to-nearest-even, sem flush-to-zero).
   Escrita em FPSCR de `RMode≠00`, `FZ=1`, `Len≠0` ou `Stride≠0` →
   `UnsupportedOperationException` central (mesmo padrão do bit E de B1.5). Flags
   cumulativas de exceção do FPSCR (IOC/DZC/OFC/UFC/IXC) **não são calculadas**
   (leitura devolve 0) — limitação documentada no javadoc do FPSCR; os binários de
   aceite não usam `fetestexcept`.
4. **`VMRS APSR_nzcv, FPSCR` (Rt=15) é obrigatório** — é como TODO compare de float
   compilado chega ao branch. Sem ele nenhum binário real funciona.
5. **SDIV/UDIV entram gateados por `ArmFeature.DIVIDE`** e o preset `ARMV7A` os
   habilita (são v7VE/v7-R no encoding ARM, mas onipresentes em userland moderno;
   B7.4/perfil M reusa a mesma feature no encoding Thumb-2).
6. gbaemu/ndsemu não têm FP nem v7 — risco de regressão baixo, mas G5 vale em toda sub.

## Aceite do épico (fecha na B3.7)

- Binário user-mode `gcc -march=armv7-a -mfpu=vfp -mfloat-abi=hard` com aritmética
  float/double/printf de resultado roda no armbox com stdout idêntico ao esperado,
  nos 3 backends (JIT/`--interp`/`--check`).
- Harness de equivalência cobre cada `IrOp` de FP (B3.6).
