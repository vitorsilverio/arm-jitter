package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.executor.IrMveFpExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações MVE de ponto flutuante: aritmética vetor × vetor e vetor × escalar, números
/// complexos, conversões e unárias.
///
/// Sub-interface selada de {@link MveOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface MveFpOp extends MveOp permits MveFpOp.VectorFpAbsAccumulate,
        MveFpOp.VectorFpConvertPrecision, MveFpOp.VectorFpComplexMultiply, MveFpOp.VectorFpTwoOp,
        MveFpOp.VectorFpComplexAdd, MveFpOp.VectorFpComplexMultiplyAccumulate,
        MveFpOp.VectorFpScalar, MveFpOp.VectorFpScalarFma, MveFpOp.VectorFpConvert,
        MveFpOp.VectorFpConvertFixed, MveFpOp.VectorFpUnary {

    /// `VMAXNMA`/`VMINNMA` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode`
    /// `@vmaxnma` — "Qd and Qn share a field"): `Qd[i] = maxNum/minNum(|Qd[i]|, |Qm[i]|)`, ponto
    /// flutuante, verbatim de `DO_2OP_FP` instanciado com `float16_maxnuma`/`minnuma`/
    /// `float32_maxnuma`/`minnuma` (`target/arm/tcg/mve_helper.c`). `Qd` é FONTE e DESTINO. Beatwise
    /// (mesmo gancho de {@link MveIntegerOp.Vector2Op}). Nunca satura (`FPSCR.QC` não se aplica a operações
    /// FP MVE nesta task).
    record VectorFpAbsAccumulate(
            /// `true` para `VMAXNMA`; `false` para `VMINNMA`.
            boolean max,
            /// `1` = binary16, `2` = binary32 (campo `size` do encoding real, literal por bloco —
            /// nunca `0`/`3`).
            int esz,
            /// `Qd` (`0`-`7`) — fonte E destino.
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_ABS_ACCUMULATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpAbsAccumulate(core, this); }
    }

    /// `VCVTB_SH`/`VCVTT_SH`/`VCVTB_HS`/`VCVTT_HS` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`,
    /// `target/isa-decode/mve.decode` `@1op_nosz`, verbatim de `do_vcvt_sh`/`do_vcvt_hs`,
    /// `target/arm/tcg/mve_helper.c`): conversão binary16↔binary32 "bottom"/"top" — não confundir
    /// com `VCVT` fp↔int/ponto-fixo (B16.12, encodings distintos). {@link #widen} `true` (`_HS`):
    /// lê `Qm` INTERCALADO (halfword, lane `i*2+top`), escreve `Qd` word (lane `i`, `0`-`3`).
    /// {@link #widen} `false` (`_SH`): lê `Qm` word (lane `i`), escreve `Qd` INTERCALADO (halfword,
    /// lane `i*2+top`). Beatwise (mesmo gancho de {@link MveIntegerOp.Vector2Op}). Nunca satura.
    record VectorFpConvertPrecision(
            /// `true` = half→single (`_HS`, ALARGANDO); `false` = single→half (`_SH`, ESTREITANDO).
            boolean widen,
            /// `true` para a forma `T`; `false` para `B`.
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_CONVERT_PRECISION; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpConvertPrecision(core, this); }
    }

    /// `VCMUL0`/`VCMUL90`/`VCMUL180`/`VCMUL270` (perfil M, B16.7 sub-família 2, MVE/Helium,
    /// `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@2op_sz28` — "note that in this format bit 28
    /// is size, not U"): delega a {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyMasked}
    /// — ver Javadoc de lá para a diferença real com `VCMLA`/`FCMLA`. Beatwise (mesmo gancho de
    /// {@link MveIntegerOp.Vector2Op}). Nunca satura.
    record VectorFpComplexMultiply(
            /// `0`=`VCMUL0`, `1`=`VCMUL90`, `2`=`VCMUL180`, `3`=`VCMUL270` — `(bit16<<1)|bit0` no
            /// encoding real, mesma convenção `ROT` de `DO_VCMLA`.
            int rotation,
            /// `1` = binary16, `2` = binary32 (`%size_28`, `bit28+1`; nunca `0`/`3`).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpComplexMultiply(core, this); }
    }

    /// `VADD_fp`/`VSUB_fp`/`VMUL_fp`/`VABD_fp`/`VMAXNM`/`VMINNM`/`VFMA`/`VFMS` (perfil M, B16.7
    /// sub-família 3, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@2op_fp`, seção
    /// "2-operand FP"): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSameMasked}) — a MESMA função de
    /// operação ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp}) que
    /// `NeonFpOp.FpThreeSame`/A64 já usam via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSame}
    /// para o caminho NÃO predicado (RFC B13.2 D1) — zero código novo de aritmética, só o gancho
    /// PREDICADO. `VFMA`/`VFMS` reusam {@code FMLA}/{@code FMLS} (FUNDIDO, um arredondamento —
    /// `DO_VFMA`/`mve_helper.c` real). `size` (`%2op_fp_size`, bit20 DIRETO: `1`=binary16,
    /// `0`=binary32 — convenção NEON FP, não confundir com {@link VectorFpComplexAdd}/
    /// {@link VectorFpComplexMultiplyAccumulate} abaixo, que usam a forma REVERSA). Beatwise
    /// (mesmo gancho de {@link MveIntegerOp.Vector2Op}). Nunca satura (`FPSCR.QC` não se aplica a operações FP
    /// MVE, mesmo precedente de {@link VectorFpAbsAccumulate}/{@link VectorFpConvertPrecision}).
    record VectorFpTwoOp(
            /// Operação a executar (núcleo compartilhado) — só `ADD`/`SUB`/`MUL`/`ABD`/`MAXNM`/
            /// `MINNM`/`FMLA`/`FMLS` nesta task.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_size`, bit20 DIRETO).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_TWO_OP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpTwoOp(core, this); }
    }

    /// `VCADD90_fp`/`VCADD270_fp` (perfil M, B16.7 sub-família 3, MVE/Helium, `FEAT_MVE_FP`,
    /// `target/isa-decode/mve.decode` `@2op_fp_size_rev` — "VCADD is an exception, where bit 20 is 0
    /// for 16 bit and 1 for 32 bit"): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAddMasked}) — MESMA fórmula
    /// `FComplexAddImpl` de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd}
    /// (`FEAT_FCMA`/NEON), só que PREDICADA por par (real/imaginário). **Não confundir com
    /// `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` INTEIROS (B16.6, {@link MveIntegerOp.VectorComplexAdd},
    /// encodings DISTINTOS, núcleo separado).** Beatwise (mesmo gancho de {@link MveIntegerOp.Vector2Op}).
    /// Nunca satura.
    record VectorFpComplexAdd(
            /// `true` para `VCADD90_fp` (rotação `90°`); `false` para `VCADD270_fp` (`270°`).
            boolean rotate90,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_size_rev`, `bit20+1` — forma REVERSA, ver
            /// Javadoc da classe).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_COMPLEX_ADD; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpComplexAdd(core, this); }
    }

    /// `VCMLA0`/`VCMLA90`/`VCMLA180`/`VCMLA270` (perfil M, B16.7 sub-família 3, MVE/Helium,
    /// `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@2op_fp_size_rev`): delega ao núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulateMasked})
    /// — MESMA fórmula `FComplexMulAdd` de {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate} (`FEAT_FCMA`/
    /// NEON `VCMLA`/`FCMLA`), só que PREDICADA por par. **Não confundir com `VCMUL0`/`VCMUL90`/
    /// `VCMUL180`/`VCMUL270` (B16.7 sub-família 2, {@link VectorFpComplexMultiply}, "real ×
    /// complexo" sem acumular — encodings e núcleo DISTINTOS, achado da sub-família 2).** Beatwise
    /// (mesmo gancho de {@link MveIntegerOp.Vector2Op}). Nunca satura.
    record VectorFpComplexMultiplyAccumulate(
            /// `0`/`90`/`180`/`270` — ver a tabela de contribuição de cada rotação no Javadoc de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate}.
            int rotation,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_size_rev`, `bit20+1`).
            int esz,
            /// `Qd` (`0`-`7`) — fonte (acumulador) E destino.
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpComplexMultiplyAccumulate(core, this); }
    }

    /// `VADD_fp_scalar`/`VSUB_fp_scalar`/`VMUL_fp_scalar` (perfil M, B16.9, MVE/Helium,
    /// `FEAT_MVE_FP`, verbatim de `DO_2OP_FP_SCALAR_ALL`, `target/arm/tcg/mve_helper.c`): delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSameScalarMasked} — o segundo
    /// operando é o valor ÚNICO de `Rm` (binary16/binary32 conforme {@link #esz}), replicado por
    /// toda a operação, em vez de uma lane de `Qm`. Beatwise (mesmo gancho de {@link MveIntegerOp.Vector2Op}).
    /// Nunca satura (nenhuma operação FP de MVE seta `FPSCR.QC`).
    record VectorFpScalar(
            /// Só `ADD`/`SUB`/`MUL` de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp} nesta task.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp op,
            /// `1` = binary16, `2` = binary32 (`%2op_fp_scalar_size`, bit 28: `1`→16 bits,
            /// `0`→32 bits).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_SCALAR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpScalar(core, this); }
        @Override public int regUse() { return (1 << rm) | GprMask.MVE_TAIL_PREDICATION; }
    }

    /// `VFMA_scalar`/`VFMAS_scalar` (perfil M, B16.9, MVE/Helium, `FEAT_MVE_FP`, verbatim de
    /// `DO_2OP_FP_ACC_SCALAR`/`DO_VFMAS_SCALARH`/`DO_VFMAS_SCALARS`, `target/arm/tcg/mve_helper.c`):
    /// multiply-accumulate FUNDIDO (arredondamento único) com escalar — delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddScalarMasked}.
    /// **Achado real, confirmado nos DOIS comentários literais do arquivo (não adivinhado)**:
    /// `VFMA_scalar` ("vector * scalar + vector") calcula `Qd[i] = fma(Qn[i], Rm, Qd[i])`;
    /// `VFMAS_scalar` ("vector * vector + scalar, so swap op2 and op3") calcula
    /// `Qd[i] = fma(Qn[i], Qd[i], Rm)` — MESMA troca de papéis de `VMLA`/`VMLAS` (ver
    /// {@link MveIntegerOp.VectorScalarSpecial}), só que fundido/ponto-flutuante. Beatwise (mesmo gancho de
    /// {@link MveIntegerOp.Vector2Op}). Nunca satura.
    record VectorFpScalarFma(
            /// `false` = `VFMA_scalar` (`fma(Qn,Rm,Qd)`); `true` = `VFMAS_scalar`
            /// (`fma(Qn,Qd,Rm)`) — ver Javadoc da classe.
            boolean swapAccumulator,
            /// `1` = binary16, `2` = binary32.
            int esz,
            /// `Qd` (`0`-`7`) — fonte (acumulador) E destino.
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_SCALAR_FMA; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpScalarFma(core, this); }
        @Override public int regUse() { return (1 << rm) | GprMask.MVE_TAIL_PREDICATION; }
    }

    /// `VCVT_SF`/`VCVT_UF`/`VCVT_FS`/`VCVT_FU`, `VCVTAS`/`VCVTAU`/`VCVTNS`/`VCVTNU`/`VCVTPS`/
    /// `VCVTPU`/`VCVTMS`/`VCVTMU` e `VRINTN`/`VRINTX`/`VRINTA`/`VRINTZ`/`VRINTM`/`VRINTP` (perfil M,
    /// B16.12, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@1op`, linhas 810-832 —
    /// 18 dos 26 encodings da task; os outros 8 são {@link VectorFpConvertFixed}): delega ao
    /// núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnaryMasked})
    /// — a MESMA função de operação ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp})
    /// que `NeonFpOp.FpUnary`/A64 já usam via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnary}
    /// para o caminho NÃO predicado — zero código novo de aritmética/arredondamento, só o gancho
    /// PREDICADO. `VCVT_FS`/`VCVT_FU` (`FCVTZS`/`FCVTZU`) SEMPRE arredondam para zero, IGNORANDO
    /// `FPSCR.RMode` (confirmado verbatim contra `DO_VCVT`/`mve_helper.c` real: usa os helpers
    /// `*_round_to_zero`, não o `FPSCR` corrente — o MESMO comportamento que o NEON de 32 bits já
    /// tinha para `VCVT` sem sufixo de modo). `VCVTA`/`N`/`P`/`M` carregam o modo de arredondamento
    /// no PRÓPRIO encoding (não consultam `FPSCR.RMode`). Beatwise (mesmo gancho de
    /// {@link MveIntegerOp.Vector2Op}). Nunca satura (`FPSCR.QC` não se aplica a operações FP MVE, mesmo
    /// precedente de {@link VectorFpTwoOp}).
    record VectorFpConvert(
            /// Operação a executar (núcleo compartilhado) — só as 18 formas de conversão/
            /// arredondamento desta task (nunca `ABS`/`NEG`/`RECPE`/`RSQRTE`/comparações-com-zero,
            /// que não têm encoding nesta família MVE).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp op,
            /// `1` = binary16, `2` = binary32 (`size`, `bits[19:18]` do `@1op`; MVE nunca tem `3`).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_CONVERT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpConvert(core, this); }
    }

    /// `VCVT_SH_fixed`/`VCVT_UH_fixed`/`VCVT_HS_fixed`/`VCVT_HU_fixed`/`VCVT_SF_fixed`/
    /// `VCVT_UF_fixed`/`VCVT_FS_fixed`/`VCVT_FU_fixed` (perfil M, B16.12, MVE/Helium,
    /// `FEAT_MVE_FP`, `target/isa-decode/mve.decode` `@vcvt`/`@vcvt_f16`, linhas 793-808 — os 8
    /// encodings de ponto fixo↔ponto flutuante da task): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPointMasked}) — a MESMA função
    /// que o A64 (`@fcvt_fixed`) e o NEON de 32 bits (`VCVT` fixo↔float F32) já usam via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPoint} (RFC B13.2 D1) —
    /// zero aritmética nova, só o gancho PREDICADO + meia precisão (`esz=1`, que o NEON de 32 bits
    /// não tinha). `toFloat=true` (`VCVT_{S,U}{H,F}_fixed`) SEMPRE arredonda pelo cast Java padrão
    /// (mais-próximo); `toFloat=false` (`VCVT_{H,F}{S,U}_fixed`) SEMPRE trunca para zero — NENHUMA
    /// das 8 consulta `FPSCR.RMode` (confirmado verbatim: `DO_VCVT` chama os MESMOS helpers
    /// `*_round_to_zero`/sem-sufixo que {@link VectorFpConvert} usa para `VCVT_FS`/`VCVT_SF` com
    /// `shift=0` — a forma "fixa" e a forma "simples" são LITERALMENTE a mesma operação, só o fator
    /// de escala muda). `shift`/`fractionBits` já resolvido pelo decoder (`N - raw`, mesma
    /// convenção `%rshift_i4`/`%rshift_i5` de B16.10/B16.11). Beatwise (mesmo gancho de
    /// {@link MveIntegerOp.Vector2Op}). Nunca satura.
    record VectorFpConvertFixed(
            /// `true` para `VCVT_{S,U}{H,F}_fixed` (inteiro → ponto flutuante); `false` para
            /// `VCVT_{H,F}{S,U}_fixed` (ponto flutuante → inteiro, sempre truncado).
            boolean toFloat,
            /// `true` para as formas `S` (assinado); `false` para `U` (sem sinal).
            boolean signed,
            /// `1` = binary16 (`@vcvt_f16`), `2` = binary32 (`@vcvt`); nunca `3`.
            int esz,
            /// Quantidade de bits fracionários, já resolvida pelo decoder (`N - raw`, `N` = `8 <<
            /// esz`).
            int fractionBits,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_CONVERT_FIXED; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpConvertFixed(core, this); }
    }

    /// `VABS_fp`/`VNEG_fp` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_FP`, `target/isa-decode/mve.decode`
    /// `@1op`, linhas 380-383, 2 dos 15 encodings da sub-família 2): delega ao núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpUnaryMasked}) com {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp#ABS}/{@code NEG} — MESMA função que já
    /// existe desde B13.13/B16.12, zero código novo de aritmética. Record dedicado (em vez de reusar
    /// {@link VectorFpConvert}, que tem o MESMO formato de campos) porque o gancho beatwise
    /// (`AdvanceVpt` em `StandardIrBuilder`) de `MveFpOp.VectorFpConvert`/`MveFpOp.VectorFpConvertFixed` está
    /// AUSENTE hoje (achado desta task, não corrigido aqui — fora do escopo do B16.13, candidato a
    /// task própria: `VCVT`/`VRINT` MVE nunca avançam `VPR`/`ECI`, ao contrário do que o Javadoc
    /// desses dois records afirma). Beatwise (mesmo gancho de {@link MveIntegerOp.Vector2Op}). Nunca satura.
    record VectorFpUnary(
            /// Só {@code ABS}/{@code NEG} têm forma correspondente nesta família MVE.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp op,
            /// `1` = binary16, `2` = binary32 (`size`, `bits[19:18]` do `@1op`; MVE nunca tem `3`).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveFpOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_UNARY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveFpExecutor.executeMveVectorFpUnary(core, this); }
    }
}
