package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações NEON de ponto flutuante: aritmética, conversão, números complexos (`FEAT_FCMA`),
/// `VFMAL`/`VFMSL` (`FEAT_FHM`) e `BFloat16`.
///
/// Sub-interface selada de {@link NeonOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface NeonFpOp extends NeonOp permits NeonFpOp.FpThreeSame, NeonFpOp.FpPairwise,
        NeonFpOp.ConvertFixedPoint, NeonFpOp.FpThreeSameByElement, NeonFpOp.FpUnary,
        NeonFpOp.FpConvertPrecision, NeonFpOp.Complex, NeonFpOp.ComplexByElement,
        NeonFpOp.FusedMultiplyAddLong, NeonFpOp.FusedMultiplyAddLongByElement,
        NeonFpOp.DotProductBFloat16, NeonFpOp.DotProductByElementBFloat16,
        NeonFpOp.MatrixMultiplyAccumulateBFloat16, NeonFpOp.FusedMultiplyAddLongBFloat16,
        NeonFpOp.FusedMultiplyAddLongByElementBFloat16 {

    /// NEON/Advanced SIMD de 32 bits, "3-reg-same" de PONTO FLUTUANTE (B13.6 F32, B13.24 F16):
    /// `VADD`/`VSUB`/`VMUL`/`VMLA`/`VMLS`/`VFMA`/`VFMS`/`VABD`/`VMAX`/`VMIN`/`VMAXNM`/`VMINNM`/
    /// `VCEQ`/`VCGE`/`VCGT`/`VACGE`/`VACGT`/`VRECPS`/`VRSQRTS`, formas F32 (`esz=2`) E F16
    /// (`esz=1`, `sz`=bit20=1 no encoding — MESMA linha `.decode` que F32, discriminada só pelo
    /// bit, não uma linha separada).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticThreeSame} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSame}), RFC B13.2 D1. A
    /// distinção fundido × NÃO fundido do multiply-accumulate (`VFMA` vs `VMLA`) está na
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp} escolhida pelo decoder.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FpThreeSame(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`, bit `Q` do encoding),
            /// `false` para o de 64 bits (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `2` (F32, B13.6) ou `1` (F16, B13.24).
            int esz,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FP_THREE_SAME; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFpThreeSame(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, "pairwise" de PONTO FLUTUANTE (B13.6 F32, B13.24 F16):
    /// `VPADD`/`VPMAX`/`VPMIN`, formas F32 (`esz=2`) e F16 (`esz=1`). Concatena `Vn:Vm` (`Vn`
    /// primeiro), combina pares de elementos ADJACENTES nessa sequência de `2 * (8 >> esz)`
    /// elementos e grava `8 >> esz` resultados em `Vd` (metade baixa vinda de `Vn`, metade alta de
    /// `Vm`). Só forma `D` no encoding A32 (`@3same_fp_q0`), por isso não há campo `quad`.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticPairwise} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpPairwise}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FpPairwise(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp op,
            /// `log2` do tamanho do elemento em bytes: `2` (F32, B13.6) ou `1` (F16, B13.24).
            int esz,
            /// Registrador de destino, índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte 1 (metade baixa do resultado), índice de `D`.
            int vn,
            /// Registrador fonte 2 (metade alta do resultado), índice de `D`.
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FP_PAIRWISE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFpPairwise(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" `VCVT` fixo↔float F32 (B13.8) e F16 (B13.24):
    /// `VCVT.F32.S32`/`VCVT.F32.U32`/`VCVT.F16.S16`/`VCVT.F16.U16` (`toFloat`, inteiro fixo
    /// `* 2^-fractionBits` → FP) e `VCVT.S32.F32`/`VCVT.U32.F32`/`VCVT.S16.F16`/`VCVT.U16.F16`
    /// (`!toFloat`, FP `* 2^fractionBits`, arredonda para zero, satura → inteiro). Elementos da MESMA
    /// largura ({@link #esz}) nos dois lados, `4`/`2` (F32) ou `8`/`4` (F16) lanes conforme
    /// {@link #quad}.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpConvertFixedPoint} no
    /// ENCODING/IR (menos o campo `scalar`, que não existe em NEON A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPoint}),
    /// RFC B13.2 D1 — já genérico em `esz` desde a B19.5.1, sem mudança de executor para a forma F16.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ConvertFixedPoint(
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`, bit `Q` do encoding), `false` para
            /// o de 64 bits (`D<d>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `2` (F32, B13.8) ou `1` (F16, B13.24).
            int esz,
            /// Número de bits fracionários (`#fbits` do encoding): `1..32` para F32, `1..16` para
            /// F16. Fator de escala `2^fractionBits`.
            int fractionBits,
            /// `true` → `SCVTF`/`UCVTF` (inteiro → FP, depois `/ 2^fbits`); `false` → `FCVTZS`/
            /// `FCVTZU` (FP `* 2^fbits`, arredonda para zero, satura → inteiro).
            boolean toFloat,
            /// `true` para as variantes assinadas (`.S32`), `false` para as não assinadas (`.U32`).
            boolean signed,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (ver {@link #vd}).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_CONVERT_FIXED_POINT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonConvertFixedPoint(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-regs-plus-scalar" de PONTO FLUTUANTE F32 (B13.11) e F16
    /// (B13.24): `VMLA_F`/`VMLS_F`/`VMUL_F` — `MLA`/`MLS` NÃO fundidos (decisão 3 da B13.6: reusa
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp#MLA}/{@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp#MLS}, nunca `FMLA`/`FMLS`). `Vd`/
    /// `Vn` são `D` ou `Q` conforme {@link #quad}; {@link #vm} é o registrador do ESCALAR (`D0`-
    /// `D15` em F32, `D0`-`D7` em F16 — já restrito pelo decoder, mesma tabela `size` usada pelas
    /// formas inteiras) e {@link #index} (`M`, 1 ou 2 bits conforme {@link #esz}) já extraídos.
    ///
    /// Espelho de {@link
    /// dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticThreeSameByElement} no ENCODING/IR
    /// (sem `scalar`, que não existe nesta seção do A32 — sempre vetorial); a SEMÂNTICA vem do
    /// núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSameByElement}),
    /// RFC B13.2 D1, já genérico em `esz` desde a B19.5.1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FpThreeSameByElement(
            /// Operação a executar (núcleo compartilhado) — só `MUL`/`MLA`/`MLS` são válidas aqui
            /// (G8: o decoder nunca produz `MULX`/`FMLA`/`FMLS`/etc. nesta forma).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `2` (F32, B13.11) ou `1` (F16, B13.24).
            int esz,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE em `MLA`/`MLS` (lê `Vd` atual).
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vn,
            /// Registrador do ESCALAR (já restrito pelo decoder, ver {@link #esz}).
            int vm,
            /// Índice do elemento dentro de {@link #vm} (já extraído pelo decoder).
            int index) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FP_THREE_SAME_BY_ELEMENT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFpThreeSameByElement(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous" de PONTO FLUTUANTE, sub-grupo
    /// `size==0b11` (B13.12): `VABS_F`/`VNEG_F`, as 5 comparações-com-zero FP (`VCGT0_F`/`VCGE0_F`/
    /// `VCEQ0_F`/`VCLE0_F`/`VCLT0_F`) e `VRECPE_F`/`VRSQRTE_F`. **Sem campo `esz`**: só F32
    /// (`esz=2`) existe neste sub-grupo em A32 (F16 é `FEAT_FP16`, task futura irmã da B19.5) —
    /// mesma convenção de {@link FpThreeSameByElement}, que também fixa `esz=2` internamente.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticUnary} no
    /// ENCODING/IR (sem `scalar`/`esz`); a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnary}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FpUnary(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<m>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FP_UNARY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFpUnary(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous" de PONTO FLUTUANTE, conversão de
    /// PRECISÃO, sub-grupo `size==0b11` (B13.13): `VCVT_F16_F32`/`VCVT_B16_F32` (estreita, 4 lanes
    /// F32 de `Vm` → 4 lanes F16/`bf16` de `Vd`) e `VCVT_F32_F16` (alarga, 4 lanes F16 de `Vm` → 4
    /// lanes F32 de `Vd`). **Sem campo `quad`** (diferente de {@link FpUnary}): o encoding real
    /// é `@2misc_q0` — um dos dois lados é sempre `D` e o outro sempre `Q`, nunca as duas formas
    /// D/D ou Q/Q.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpConvertPrecision}), RFC B13.2 D1
    /// — reaproveita `Float.floatToFloat16`/`float16ToFloat` (B19.4) e `bf16Bits`/`bf16ToFloat`
    /// (B19.7) sem escrever conversão nova.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FpConvertPrecision(
            /// Direção/formato da conversão.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpConvertPrecisionOp op,
            /// Registrador de destino, em índice de `D` (`0`-`31`); nas formas estreitas é o `D`
            /// único de saída, na forma larga (`WIDEN_F16`) é o `D` par que inicia o `Q` de saída.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); nas formas estreitas é o `D` par que
            /// inicia o `Q` de entrada, na forma larga (`WIDEN_F16`) é o `D` único de entrada.
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FP_CONVERT_PRECISION; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFpConvertPrecision(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VCMLA`/`VCADD` (B13.17, `FEAT_FCMA`): trata
    /// pares de lanes ADJACENTES (par = parte real, ímpar = parte imaginária) como um número
    /// complexo. `VCMLA` acumula em `vd` (lê e escreve); `VCADD` só escreve. Mesmo encoding em A32
    /// e T32 (`neon-shared.decode`, cabeçalho do arquivo) — nenhuma task T32 própria necessária.
    ///
    /// Núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd}/
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate}), RFC
    /// B13.2 D1 — **exceção do épico B13**: não há semântica A64 prévia para migrar (`FCMLA`/
    /// `FCADD` do A64 também não existem ainda), a semântica nasce aqui para o A64 reusar depois.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Complex(
            /// `true` para `VCMLA` (multiply-accumulate, FUNDIDO, lê e escreve `vd`), `false` para
            /// `VCADD` (só soma, só escreve `vd`).
            boolean multiplyAccumulate,
            /// Rotação em graus: `0`/`90`/`180`/`270` para `VCMLA`; só `90`/`270` para `VCADD`
            /// (já convertido do campo cru de 1/2 bits do encoding — nunca recalculado aqui).
            int rotation,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `1`=F16 (`FEAT_FP16`), `2`=F32.
            int esz,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`a` = real/imaginária), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (`b` = real/imaginária, rotacionado por {@link #rotation}), em
            /// índice de `D` (ver {@link #vd}).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_COMPLEX; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonComplex(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VCMLA_scalar` (B13.17, `FEAT_FCMA`): como
    /// {@link Complex} com `multiplyAccumulate=true`, mas o operando `b` é um único número
    /// complexo FIXO lido de `vm` no par de lanes `index`/`index+1` e replicado para cada par de
    /// `vn`. **Não existe `VCADD_scalar`** (só `VCMLA` tem forma indexada).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulateByElement}),
    /// RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ComplexByElement(
            /// Rotação em graus: `0`/`90`/`180`/`270`.
            int rotation,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`). `vm` é sempre um `D` (nunca `Q`), independente desta forma.
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `1`=F16 (`índice` de 1 bit, 2 complexos por
            /// `D`), `2`=F32 (`índice` sempre `0`, 1 complexo ocupa o `D` inteiro).
            int esz,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`a`, varia por par), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (`b`, FIXO, lido uma vez), em índice de `D` (`0`-`31`, **nunca**
            /// combinado com {@link #quad}).
            int vm,
            /// Índice do par complexo dentro de {@link #vm}: `0`-`1` para F16, sempre `0` para F32.
            int index) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_COMPLEX_BY_ELEMENT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonComplexByElement(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFML`/`VFMSL` (B13.20, `FEAT_FHM`, forma
    /// vetorial): multiplica lanes de MEIA precisão de {@link #vn}/{@link #vm} e acumula (FUNDIDO,
    /// um único arredondamento) em lanes de precisão SIMPLES de {@link #vd} (lido e escrito) —
    /// largura mista, destino do dobro de lanes largas que fontes. **`quad=false`**: {@link #vn}/
    /// {@link #vm} são registradores `S` (`0`-`31`, vista de 32 bits = 2 lanes f16), {@link #vd} é
    /// `D` (2 lanes f32). **`quad=true`**: {@link #vn}/{@link #vm} são `D` (4 lanes f16), {@link
    /// #vd} é o `D` par que inicia o `Q` (4 lanes f32) — índice ÍMPAR é UNDEFINED (mesma disciplina
    /// das siblings deste arquivo). Ao contrário do `2`/laneOffset do A64 (`FMLAL2`/`FMLSL2`), o
    /// NEON de 32 bits NÃO tem forma de metade alta — o executor sempre lê o registrador FONTE
    /// inteiro (nenhum `laneOffset` além de `0`).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddLong}) — nasce
    /// aqui (B13.20) porque nem esta nem a task irmã A64 (**B19.13**, `FMLAL`/`FMLSL`/`FMLAL2`/
    /// `FMLSL2`) tinham semântica prévia; quem rodar primeiro põe no núcleo, a outra reusa.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FusedMultiplyAddLong(
            /// `false` para `VFML`/`VFMAL` (soma), `true` para `VFMSL` (subtração — acumula `-a*b`).
            boolean subtract,
            /// `true` para a forma `Q0,D,D` (fontes `D`, destino `Q`), `false` para `D,S,S` (fontes
            /// `S`, destino `D`) — ver Javadoc da classe.
            boolean quad,
            /// Registrador de destino/acumulador: índice de `D` (`quad=false`) ou o `D` par que
            /// inicia o `Q` (`quad=true`).
            int vd,
            /// Registrador fonte 1: índice de `S` (`0`-`31`, `quad=false`) ou de `D` (`quad=true`).
            int vn,
            /// Registrador fonte 2: índice de `S` (`0`-`31`, `quad=false`) ou de `D` (`quad=true`).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFusedMultiplyAddLong(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFML_scalar`/`VFMSL_scalar` (B13.20,
    /// `FEAT_FHM`): como {@link FusedMultiplyAddLong}, mas o operando `b` é uma única lane f16
    /// FIXA, replicada para cada lane de {@link #vn}. **Os extratores de {@link #rm}/{@link #index}
    /// diferem entre as duas formas** (bits espalhados, ver `NeonSharedDecoder`): `quad=false`:
    /// {@link #rm} é um `S` de 4 bits (`S0`-`S15`, SEM bit de extensão) e {@link #index} (`0`-`1`)
    /// escolhe qual das 2 lanes f16 de {@link #rm}; `quad=true`: {@link #rm} é um `D` de 3 bits
    /// (`D0`-`D7`) e {@link #index} (`0`-`3`) escolhe qual das 4 lanes f16.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddLongByElement}).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FusedMultiplyAddLongByElement(
            /// `false` para `VFML`/`VFMAL` (soma), `true` para `VFMSL` (subtração — acumula `-a*b`).
            boolean subtract,
            /// `true` para a forma `Q0,D,D` (fonte `vn` `D`, destino `Q`), `false` para `D,S,S`
            /// (fonte `vn` `S`, destino `D`) — ver Javadoc da classe.
            boolean quad,
            /// Registrador de destino/acumulador: índice de `D` (`quad=false`) ou o `D` par que
            /// inicia o `Q` (`quad=true`).
            int vd,
            /// Registrador fonte 1 (varia por lane): índice de `S` (`0`-`31`, `quad=false`) ou de
            /// `D` (`quad=true`).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez): `S0`-`S15` (`quad=false`) ou `D0`-`D7`
            /// (`quad=true`) — ver Javadoc da classe.
            int rm,
            /// Índice da lane f16 dentro de {@link #rm}: `0`-`1` (`quad=false`) ou `0`-`3`
            /// (`quad=true`).
            int index) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFusedMultiplyAddLongByElement(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VDOT_b16` (B13.21, `FEAT_BF16`): produto
    /// escalar de PARES `bf16`, acumulando em `f32`. Sibling FP de {@link NeonIntegerOp.DotProduct} (que é
    /// inteiro) — sem campos de sinal, o formato `bf16` não tem variante assinada/sem sinal.
    ///
    /// Núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfDotProduct}),
    /// criado pela B19.7 (a task irmã A64 de `BFDOT`) — reusado sem nenhuma mudança.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record DotProductBFloat16(
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT_BFLOAT16; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonDotProductBFloat16(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VDOT_b16_scal` (B13.21): como
    /// {@link DotProductBFloat16}, mas o operando `b` é um único par `bf16` de 32 bits FIXO
    /// lido de {@link #vm} no {@link #index}, replicado para cada lane de {@link #vn}.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfDotProductByElement}).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record DotProductByElementBFloat16(
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`). `vm` é sempre um `D` (nunca `Q`), independente desta forma.
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (varia por lane), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez), em índice de `D` (`0`-`15`, **nunca**
            /// combinado com {@link #quad}).
            int vm,
            /// Índice do par `bf16` de 32 bits dentro de {@link #vm}: `0`-`1` (um `D` guarda 2
            /// pares).
            int index) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonDotProductByElementBFloat16(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VMMLA_b16` (B13.21, `FEAT_BF16`):
    /// multiplicação de matriz `2×4 · 4×2` de pares `bf16`, acumulando em `f32`. Irmã de ponto
    /// flutuante de {@link NeonIntegerOp.MatrixMultiplyAccumulate} (`K=4` em vez de `K=8`, sem campos de
    /// sinal). **Sempre 128 bits** — não existe forma `D` (índice de registrador ímpar em
    /// {@link #vd}/{@link #vn}/{@link #vm} é UNDEFINED, mesma disciplina de
    /// {@link NeonIntegerOp.MatrixMultiplyAccumulate}).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfMatrixMultiplyAccumulate}),
    /// criado pela B19.7 (a task irmã A64 de `BFMMLA`) — reusado sem nenhuma mudança.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record MatrixMultiplyAccumulateBFloat16(
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`) — o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (duas linhas de 4 pares `bf16`), em índice de `D` (ver
            /// {@link #vd}).
            int vn,
            /// Registrador fonte 2 (duas colunas de 4 pares `bf16`), em índice de `D` (ver
            /// {@link #vd}).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonMatrixMultiplyAccumulateBFloat16(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFMA_b16` (B13.21, `FEAT_BF16`, forma
    /// vetorial: mnemônicos `VFMAB`/`VFMAT`): multiply-accumulate LONG (soma simples, NÃO fundida)
    /// — para cada uma das 4 lanes `f32` de {@link #vd} (`Vd.4S`, sempre 128 bits), lê o elemento
    /// `bf16` de índice `2e+top` de {@link #vn}/{@link #vm} (SEMPRE `Q`, 8 elementos `bf16` cada),
    /// multiplica em `binary32` e acumula. **Diferente do irmão inteiro/meia-precisão
    /// {@link FusedMultiplyAddLong}**: aqui NÃO existe forma `D,S,S` — confirmado que GAS
    /// recusa a forma não-`Q` (`invalid instruction shape`) e que o bit nomeado `q` no `.decode`
    /// é, na prática, o seletor BOTTOM/TOP (`VFMAB`=`0`/`VFMAT`=`1`), estrutura IDÊNTICA à do A64
    /// `BFMLALB`/`BFMLALT` — medido byte a byte contra `arm-linux-gnueabihf-as -march=armv8.2-a+bf16`
    /// (`vfmab.bf16 q0,q1,q2`=`0xFC320814`, `vfmat.bf16 q0,q1,q2`=`0xFC320854`, só o bit6 muda).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfMultiplyAddLong}), criado pela
    /// B19.7 (a task irmã A64 de `BFMLALB`/`BFMLALT`) — reusado sem nenhuma mudança (a mesma
    /// interleave par/ímpar de `top` já assume fonte de 8 elementos, exatamente o que `Q` fornece
    /// aqui).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FusedMultiplyAddLongBFloat16(
            /// `false`=`VFMAB` (elementos PARES de {@link #vn}/{@link #vm}, índice `2e`),
            /// `true`=`VFMAT` (ÍMPARES, índice `2e+1`).
            boolean top,
            /// Registrador de destino/acumulador: o `D` par que inicia o `Q` (`Vd.4S`).
            int vd,
            /// Registrador fonte 1: o `D` par que inicia o `Q` (`Vn.8H`, lido elemento a elemento).
            int vn,
            /// Registrador fonte 2: o `D` par que inicia o `Q` (`Vm.8H`, lido elemento a elemento).
            int vm) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFusedMultiplyAddLongBFloat16(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VFMA_b16_scal` (B13.21, mnemônicos
    /// `VFMAB`/`VFMAT` indexados): como {@link FusedMultiplyAddLongBFloat16}, mas {@link #vm}
    /// sempre contribui o MESMO elemento `bf16` {@link #index}, restrito a `D0`-`D7` (3 bits, SEM
    /// bit de extensão — nunca `D8`-`D31`), diferente de {@link #vn} (sempre `Q` completo).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfMultiplyAddLongByElement}).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record FusedMultiplyAddLongByElementBFloat16(
            /// Ver {@link FusedMultiplyAddLongBFloat16#top}.
            boolean top,
            /// Registrador de destino/acumulador: o `D` par que inicia o `Q` (`Vd.4S`).
            int vd,
            /// Registrador fonte 1: o `D` par que inicia o `Q` (`Vn.8H`, lido elemento a elemento).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez): `D0`-`D7`.
            int vm,
            /// Índice do elemento `bf16` de {@link #vm} usado em TODA a operação (`0`-`3`).
            int index) implements NeonFpOp {
        @Override public int kind() { return Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonFusedMultiplyAddLongByElementBFloat16(core, this); return false; }
    }
}
