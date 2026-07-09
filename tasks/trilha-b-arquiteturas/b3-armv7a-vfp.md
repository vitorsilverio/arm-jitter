# B3 — ARMv7-A user-level + VFP **[REFINAR]**

**Trilha:** B · **Depende de:** B2 · **Repo:** arm-jitter
**Status de spec:** alto nível — refinar quando B2 estiver encaminhado.

## Contexto

Completa o user-mode de 32 bits: com B1+B2+B3, binários Linux ARMv7 e o core ARM11 do
3DS (que precisa de VFPv2) ficam cobertos. NEON fica explicitamente de FORA da primeira
rodada — cai no fallback PER_OP interpretado se/quando decodificado.

## Escopo previsto

| Sub | Escopo |
|-----|--------|
| B3.1 | Restante ARMv7 inteiro: MOVW/MOVT (encoding ARM), DMB/DSB/ISB, MLS, SBFX/UBFX/BFI/BFC, RBIT, SDIV/UDIV (nota: div é v7-M/v7VE — gatear separado) |
| B3.2 | Banco de registradores FP no core: 32×S / 16×D (VFPv2) sobrepostos, FPSCR (flags NZCV próprios, modos de arredondamento), acesso via CP10/CP11 e habilitação (FPEXC/CPACR ficam com o hospedeiro via `CoprocessorBus` — decidir no refinamento) |
| B3.3 | IR de FP: novos `IrOp`s (FpAlu, FpLoad/FpStore, FpTransfer, FpCompare, FpConvert) + executores interpretados |
| B3.4 | Decoder VFP (encodings CDP/LDC/STC/MCR/MRC de CP10/11 — o decoder de coprocessador existente é o ponto de entrada) |
| B3.5 | Emissão nativa ASM de FP (mapear para float/double da JVM onde a semântica IEEE bater; onde não bater — flush-to-zero, default NaN — helper com semântica exata) |

## Decisões já tomadas (respeitar)

- **VFP antes de NEON.** NEON é enorme e o fallback PER_OP cobre o gap.
- Semântica FP: modo de referência = IEEE 754 estrito com FPSCR default; os modos
  não-IEEE do VFP (flush-to-zero etc.) são gateados e podem começar como exceção clara
  se não usados pelos alvos (mesma filosofia do bit E em B1.5).
- gbaemu/ndsemu não têm FP — risco de regressão baixo, mas G5 continua valendo.

## Aceite (do épico)

- Binário user-mode `gcc -march=armv7-a -mfpu=vfpv3 -mfloat-abi=hard` com aritmética
  double/float roda correto no runner B4.0 (comparar stdout com execução nativa).
- Harness de equivalência para cada IrOp de FP.
