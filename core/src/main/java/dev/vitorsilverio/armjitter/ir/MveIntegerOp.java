package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.executor.IrMveIntegerExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações MVE inteiras: aritmética vetor × vetor e vetor × escalar, alargantes, estreitantes,
/// deslocamentos, unárias e os deslocamentos longos em GPR (`ASRL`/`LSLL`/`SQSHL`/...).
///
/// Sub-interface selada de {@link MveOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface MveIntegerOp extends MveOp permits MveIntegerOp.WideShift,
        MveIntegerOp.Vector2Op, MveIntegerOp.Vector2OpWidening, MveIntegerOp.VectorCarry,
        MveIntegerOp.VectorComplexAdd, MveIntegerOp.VectorAbsAccumulate,
        MveIntegerOp.VectorShiftWidenInterleaved, MveIntegerOp.VectorNarrowInterleaved,
        MveIntegerOp.VectorDualMultiplyAddHigh, MveIntegerOp.VectorDoublingWideningMultiply,
        MveIntegerOp.VectorScalar, MveIntegerOp.VectorScalarWidening,
        MveIntegerOp.VectorScalarSpecial, MveIntegerOp.VectorShiftImmediate,
        MveIntegerOp.VectorShiftWidenImmediateInterleaved,
        MveIntegerOp.VectorShiftNarrowImmediateInterleaved, MveIntegerOp.VectorShiftLeftCarry,
        MveIntegerOp.VectorUnary {

    /// As 19 operações do MVE "long shift" sobre registradores de propósito geral (B16.16,
    /// `target/isa-decode/t32.decode`, `mve_shl_ri`/`mve_shl_rr`/`mve_sh_ri`/`mve_sh_rr`). Cada
    /// constante carrega as propriedades que o executor precisa, lidas dos helpers reais do QEMU
    /// (`translate.c`/`mve_helper.c`): {@code wide} (par `RdaLo:RdaHi` de 64 bits vs `Rda` de 32),
    /// {@code bits} (largura de saturação: 32/48/64), {@code signed}, {@code round}, {@code
    /// saturating} (`true` = seta `APSR.Q`; as formas sem saturação passam `sat=NULL` no QEMU),
    /// {@code right} (nega a quantidade: `SQRSHR*`/`ASRL`/`LSRL`/`*SHR*`) e {@code register} (a
    /// quantidade vem do byte baixo de `Rm`, com sinal; senão é o imediato).
    enum WideShiftOperation {
        UQSHL_RI(false, 32, false, false, true, false, false),
        URSHR_RI(false, 32, false, true, false, true, false),
        SRSHR_RI(false, 32, true, true, false, true, false),
        SQSHL_RI(false, 32, true, false, true, false, false),
        LSLL_RI(true, 64, false, false, false, false, false),
        LSRL_RI(true, 64, false, false, false, true, false),
        ASRL_RI(true, 64, true, false, false, true, false),
        URSHRL_RI(true, 64, false, true, false, true, false),
        SRSHRL_RI(true, 64, true, true, false, true, false),
        UQSHLL_RI(true, 64, false, false, true, false, false),
        SQSHLL_RI(true, 64, true, false, true, false, false),
        UQRSHL_RR(false, 32, false, true, true, false, true),
        SQRSHR_RR(false, 32, true, true, true, true, true),
        LSLL_RR(true, 64, false, false, false, false, true),
        ASRL_RR(true, 64, true, false, false, true, true),
        UQRSHLL64_RR(true, 64, false, true, true, false, true),
        SQRSHRL64_RR(true, 64, true, true, true, true, true),
        UQRSHLL48_RR(true, 48, false, true, true, false, true),
        SQRSHRL48_RR(true, 48, true, true, true, true, true);

        private final boolean wide;
        private final int bits;
        private final boolean signed;
        private final boolean round;
        private final boolean saturating;
        private final boolean right;
        private final boolean register;

        WideShiftOperation(boolean wide, int bits, boolean signed, boolean round, boolean saturating,
                boolean right, boolean register) {
            this.wide = wide;
            this.bits = bits;
            this.signed = signed;
            this.round = round;
            this.saturating = saturating;
            this.right = right;
            this.register = register;
        }

        /// `true` para as formas de 64 bits (par `RdaLo:RdaHi`); `false` para `Rda` de 32 bits.
        public boolean wide() { return wide; }

        /// Largura de saturação: `32`, `48` (`*L48_RR`) ou `64`.
        public int bits() { return bits; }

        /// `true` para as formas com sinal (`S*`/`ASR*`).
        public boolean signed() { return signed; }

        /// `true` quando o deslocamento à direita arredonda (`*R*`).
        public boolean round() { return round; }

        /// `true` quando a operação pode setar `APSR.Q` (não `FPSCR.QC`).
        public boolean saturating() { return saturating; }

        /// `true` quando a quantidade é NEGADA antes do helper (deslocamento à direita).
        public boolean right() { return right; }

        /// `true` quando a quantidade vem de `Rm`; `false` quando é o imediato.
        public boolean register() { return register; }
    }

    /// MVE "long shift" sobre GPR (perfil M, B16.16, `FEAT_MVE_INTEGER`): `LSLL`/`LSRL`/`ASRL`/
    /// `URSHRL`/`SRSHRL`/`UQSHLL`/`SQSHLL`/`UQRSHLL`/`SQRSHRL` (par `RdaLo:RdaHi`),
    /// `UQSHL`/`SQSHL`/`URSHR`/`SRSHR`/`UQRSHL`/`SQRSHR` (`Rda`). **Não** usa `Q0`-`Q7` nem `VPR`
    /// e **não** é beatwise (o QEMU real nunca chama `mve_eci_check`/`mve_advance_vpt` aqui); satura
    /// em `APSR.Q`, sticky. Condicionada por `IT` como qualquer instrução escalar.
    record WideShift(
            /// Qual das 19 operações.
            WideShiftOperation operation,
            /// Quantidade imediata `1..32` (`shim == 0` já convertido em `32` no decode); `0` nas
            /// formas por registrador.
            int shim,
            /// `Rm` (o byte baixo, com sinal, é a quantidade) nas formas por registrador; `-1` nas demais.
            int rm,
            /// `Rda` (formas de 32 bits) ou `RdaLo` (formas de 64 bits).
            int rdaLo,
            /// `RdaHi` nas formas de 64 bits; `-1` nas de 32 bits.
            int rdaHi,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_WIDE_SHIFT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { IrMveIntegerExecutor.executeMveWideShift(core, this); return false; }
        // Escalar: não consulta `VPR`/`ECI` nem o contador de tail-predication.
        @Override public int regUse() {
            return (operation.register() ? (1 << rm) : 0) | (1 << rdaLo) | (operation.wide() ? (1 << rdaHi) : 0);
        }
        @Override public int regDef() { return (1 << rdaLo) | (operation.wide() ? (1 << rdaHi) : 0); }
    }

    /// Vector 2-op inteiro (perfil M, B16.6, MVE/Helium, `target/isa-decode/mve.decode`, seção
    /// "Vector 2-op"): `Qd[i] = op(Qn[i], Qm[i])` para cada lane de `1 << esz` bytes de `Q0`-`Q7`,
    /// PREDICADO por elemento — diferente de {@link dev.vitorsilverio.armjitter.ir.NeonIntegerOp.ThreeSame}
    /// (NEON de 32 bits, sem predicação), reusa o MESMO {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} (RFC B13.2 D1) via {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameMasked}. Cobre as 34 linhas
    /// (lógica/aritmética base, min/max/abd/halving/saturantes/deslocamento por vetor, `VRHADD`) que
    /// já existem no núcleo compartilhado — ver `## Resultado` da task para o inventário completo.
    /// Beatwise (gancho manual de {@link MvePredicationOp.AdvanceVpt} em `StandardIrBuilder`, mesmo padrão de
    /// {@link MveMoveOp.LoadStore} desde a B16.3 — chega via o escape hatch {@code
    /// DecodedInstruction#liftedOp}).
    record Vector2Op(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` é recusado no decoder, MVE não
            /// tem elemento de 64 bits nesta forma).
            int esz,
            /// `Qd` (`0`-`7`, já validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qd,
            /// `Qn` — na forma `@2op_rev` (deslocamento por vetor) já vem TROCADO com `Qm` pelo
            /// decoder (achado da task: "Vn e Vm invertidos de propósito" no `mve.decode` real).
            int qn,
            /// `Qm` — ver {@link #qn}.
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_2OP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVector2Op(core, this); }
    }

    /// `VMULLP_B`/`VMULLP_T` (polinomial) e `VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (inteiro,
    /// perfil M, B16.6, MVE/Helium): ALARGA — lê `8 >> esz` elementos de `1 << esz` bytes de `Qn`/
    /// `Qm` e escreve `Qd` INTEIRO com elementos de `1 << (esz+1)` bytes (dobro da largura) — reusa
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#wideningInterleavedMasked}.
    /// **Achado real que corrige a suposição inicial**: {@link #top} `false`/`true` (`_B`/`_T`) NÃO
    /// seleciona metade contígua baixa/alta (padrão `SMULL2`/`UMULL2` do A64) — seleciona lanes
    /// PARES/ÍMPARES intercaladas da fonte (`le*2 + top`, verbatim de `DO_2OP_L`,
    /// `target/arm/tcg/mve_helper.c`, confirmado via `WebFetch`). `VMULLP_*` usa {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#PMULL} — o núcleo precisou generalizar
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#polynomialMultiply8} para largura
    /// VARIÁVEL (`esz` `0`/`1`, byte/halfword — QEMU nomeia as duas formas `vmullpbh`/`vmullpbw`:
    /// fonte BYTE produz resultado HALFWORD, fonte HALFWORD produz resultado WORD; MVE não tem forma
    /// de fonte WORD para `VMULLP`, ao contrário do `VMULL` inteiro), ver
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#polynomialMultiply}. Beatwise (mesmo
    /// gancho de {@link Vector2Op}), predicado por byte na granularidade da lane LARGA
    /// (`mergemask` real também se aplica a esta forma — outro achado que corrige a suposição
    /// inicial de que só `threeSame` precisaria de máscara).
    record Vector2OpWidening(
            /// Operação alargante a executar — só {@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#SMULL}/{@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#UMULL}/{@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp#PMULL} nesta task.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp op,
            /// `log2` do tamanho do elemento FONTE (`Qn`/`Qm`) em bytes — `0`/`1` (byte/halfword)
            /// para `VMULLP_*` (`%size_28` decodifica `bit28+1` em `1`/`2`; o esz FONTE real é
            /// `bit28` diretamente — ver Javadoc da classe); `0`-`2` para `VMULL_*S`/`VMULL_*U`
            /// (`3` recusado no decoder).
            int esz,
            /// `true` para a forma `_T` (lanes ÍMPARES da fonte); `false` para `_B` (lanes PARES) —
            /// discriminado por `bit12` no encoding real. Ver Javadoc da classe (não é metade
            /// contígua).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (fonte, `0`-`7`).
            int qn,
            /// `Qm` (fonte, `0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_2OP_WIDENING; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVector2OpWidening(core, this); }
    }

    /// `VADC`/`VADCI`/`VSBC`/`VSBCI` (perfil M, B16.6, MVE/Helium): soma/subtração com CARRY
    /// encadeado por `FPSCR.C` (não `APSR`/`CPSR`) através dos 4 elementos de 32 bits de `Qn`/`Qm`
    /// (ESIZE fixo em 4 — `size` do encoding é decorativo, sempre `0` nesta forma `@2op_nosz`),
    /// verbatim de `do_vadc`/`HELPER(mve_vadc)`/`HELPER(mve_vadci)`/`HELPER(mve_vsbc)`/
    /// `HELPER(mve_vsbci)` (`target/arm/tcg/mve_helper.c`): o carry de SAÍDA de um elemento vira o
    /// carry de ENTRADA do elemento seguinte, DENTRO da mesma instrução — só elementos ATIVOS
    /// (bit de `elementMask`) atualizam a cadeia; ao final, `FPSCR.C` recebe o carry final sempre
    /// que ALGUM elemento estava ativo (`mask & 0x1111`, verificado mesmo quando `updateFlags` seria
    /// `false` pelo chamador — achado real do QEMU: a checagem FORÇA `true`). Formas `I`
    /// (`VADCI`/`VSBCI`) ignoram o `FPSCR.C` de ENTRADA (`VADCI` usa `0`; `VSBCI` usa `1`, convenção
    /// SBC padrão de "sem empréstimo"), mas ainda ESCREVEM o carry de saída. `VSBC`/`VSBCI` invertem
    /// `Qm` bit a bit antes de somar (`n + ~m + carry_in`, complemento de dois). Beatwise (mesmo
    /// gancho de {@link Vector2Op}).
    record VectorCarry(
            /// `true` para `VADC`/`VADCI` (`Qm` não invertido); `false` para `VSBC`/`VSBCI`
            /// (`Qm` invertido bit a bit).
            boolean add,
            /// `true` para as formas `I` (`VADCI`/`VSBCI` — carry de entrada IGNORADO, `0`/`1` fixo
            /// conforme {@link #add}); `false` para `VADC`/`VSBC` (carry de entrada = `FPSCR.C`
            /// atual).
            boolean immediateCarry,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_CARRY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorCarry(core, this); }
    }

    /// `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` (perfil M, B16.6, MVE/Helium): soma complexa
    /// INTEIRA entre lanes ADJACENTES de `Qn`/`Qm` — verbatim de `DO_VCADD`/`DO_VCADD_ALL`
    /// (`target/arm/tcg/mve_helper.c`): para cada par `(2i, 2i+1)`, a lane PAR do destino combina
    /// `Qn[2i]` com `Qm[2i+1]` e a lane ÍMPAR combina `Qn[2i+1]` com `Qm[2i]` — o SINAL da combinação
    /// (soma/subtração) e QUAL lane usa qual sinal dependem de {@link #rotate90}: `90` faz
    /// PAR=SUBTRAI/ÍMPAR=SOMA, `270` faz PAR=SOMA/ÍMPAR=SUBTRAI (`DO_VCADD_ALL(vcadd90, DO_SUB,
    /// DO_ADD)`/`DO_VCADD_ALL(vcadd270, DO_ADD, DO_SUB)` reais). {@link #halving} (`VHCADD*`) troca
    /// soma/subtração planas por {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp#SHADD}/
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp#SHSUB} (SEMPRE assinado — não
    /// existe forma `VCADD`/`VHCADD` não assinada, diferente de {@link Vector2Op}). **Não
    /// confundir com `VCADD90_fp`/`VCADD270_fp` (B16.7, encodings DISTINTOS, núcleo FP separado
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd} — Armadilha 4 da
    /// task).** Beatwise (mesmo gancho de {@link Vector2Op}).
    record VectorComplexAdd(
            /// `true` para `VHCADD90`/`VCADD90`; `false` para `VHCADD270`/`VCADD270` — ver Javadoc
            /// da classe para qual lane (par/ímpar) soma ou subtrai em cada caso.
            boolean rotate90,
            /// `true` para `VHCADD90`/`VHCADD270` (halving, sempre assinado); `false` para
            /// `VCADD90`/`VCADD270` (soma/subtração plana, sem halving).
            boolean halving,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_COMPLEX_ADD; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorComplexAdd(core, this); }
    }

    /// `VMAXA`/`VMINA` (perfil M, B16.7, MVE/Helium, `target/isa-decode/mve.decode` `@1op`, verbatim
    /// de `DO_VMAXMINA`, `target/arm/tcg/mve_helper.c`): `Qd[i] = max/min(Qd[i], |sext(Qm[i])|)`
    /// — comparação NÃO ASSINADA (`Qd` é `unsigned`, `Qm` é `signed` e seu valor absoluto é tomado
    /// primeiro; ver Javadoc de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#absAccumulateMasked}).
    /// `Qd` é FONTE e DESTINO ao mesmo tempo — não tem `Qn` separado. Beatwise (mesmo gancho de
    /// {@link Vector2Op}). Nunca satura.
    record VectorAbsAccumulate(
            /// `true` para `VMAXA`; `false` para `VMINA`.
            boolean max,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`) — fonte E destino.
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_ABS_ACCUMULATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorAbsAccumulate(core, this); }
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma **T2** (`shift == esize`, perfil M, B16.7,
    /// MVE/Helium, `target/isa-decode/mve.decode` `@2_shll_esize_b`/`@2_shll_esize_h` — comentário do
    /// arquivo real: "not a @2op pattern, but is here because it overlaps what would be size=0b11
    /// VMULH/VRMULH"): ALARGA — lê `8 >> esz` elementos de `1 << esz` bytes da lane INTERCALADA
    /// `le*2 + (top?1:0)` de `Qm` (mesmo padrão de {@link Vector2OpWidening}) e escreve `Qd`
    /// INTEIRO com elementos de `1 << (esz+1)` bytes (dobro da largura), sinal/zero-estendidos e
    /// deslocados à esquerda por `esize` bits. **Não confundir com `VSHLL` forma T1 (B16.10,
    /// encoding DISTINTO com o MESMO mnemônico).** Beatwise (mesmo gancho de {@link Vector2Op}).
    /// Nunca satura.
    record VectorShiftWidenInterleaved(
            /// `true` = sinal-estende (`VSHLL_*S`); `false` = zero-estende (`VSHLL_*U`).
            boolean signed,
            /// `log2` do tamanho do elemento FONTE em bytes — `0`(byte, `shift=8`) ou `1`(halfword,
            /// `shift=16`); a T2 nunca tem fonte WORD.
            int esz,
            /// `true` para a forma `_T` (lanes ÍMPARES da fonte); `false` para `_B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorShiftWidenInterleaved(core, this); }
    }

    /// `VMOVNB`/`VMOVNT`/`VQMOVN_B*`/`VQMOVN_T*`/`VQMOVUNB`/`VQMOVUNT` (perfil M, B16.7, MVE/Helium,
    /// `target/isa-decode/mve.decode` `@1op`, verbatim de `DO_VMOVN`/`DO_VMOVN_SAT`,
    /// `target/arm/tcg/mve_helper.c`): ESTREITA — lê `8 >> esz` elementos de `1 << (esz+1)` bytes de
    /// `Qm` e escreve a lane ESTREITA INTERCALADA `le*2 + (top?1:0)` de `Qd` (oposto de
    /// {@link Vector2OpWidening}: aqui a intercalação é no DESTINO). Reusa {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp} (mesmo mapeamento do A64/NEON:
    /// `XTN`=`VMOVN`, `SQXTN`=`VQMOVN_*S`, `SQXTUN`=`VQMOVUN*`, `UQXTN`=`VQMOVN_*U`). Beatwise (mesmo
    /// gancho de {@link Vector2Op}). `FPSCR.QC` só para as 3 formas saturantes.
    record VectorNarrowInterleaved(
            /// Operação de estreitamento a executar.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp op,
            /// `log2` do tamanho do elemento ESTREITO (saída) em bytes — `0`(byte) ou `1`(halfword);
            /// nunca `2`/`3` (MVE não tem forma de saída WORD aqui).
            int esz,
            /// `true` para a forma `T`; `false` para `B`.
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_NARROW_INTERLEAVED; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorNarrowInterleaved(core, this); }
    }

    /// `VQDMLADH`/`VQDMLSDH` e variantes `X` (exchange)/`R` (rounded) (perfil M, B16.7 sub-família 2,
    /// MVE/Helium, `target/isa-decode/mve.decode` `@2op` — o `{}` sobreposto com `VCMUL*` onde
    /// `bits[21:20]` é `size` real, não o literal `11` que `VCMUL*` reivindica): delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dualMultiplyAddHighMasked} — ver
    /// Javadoc de lá para o achado real de que só METADE das lanes é escrita por instância. `size`
    /// (`bits[21:20]`) é real (`0`-`2`; `3` reservado para `VCMUL*` pela prioridade textual do `{}`).
    /// Beatwise (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` só quando alguma lane ATIVA
    /// (da metade escrita) satura.
    record VectorDualMultiplyAddHigh(
            /// `true` para `VQDMLADH*` (soma os dois produtos); `false` para `VQDMLSDH*` (subtrai) —
            /// `bit28` (`U`) no encoding real.
            boolean add,
            /// `true` para as formas `X` (exchange, escreve lanes ÍMPARES); `false` para as formas
            /// sem sufixo (escreve lanes PARES) — `bit16` no encoding real.
            boolean exchange,
            /// `true` para as formas `R` (rounded, `VQRDMLADH*`/`VQRDMLSDH*`); `false` para as sem
            /// `R` — `bit0` no encoding real.
            boolean rounded,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` reservado, ver Javadoc da classe).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorDualMultiplyAddHigh(core, this); }
    }

    /// `VQDMULLB`/`VQDMULLT` (perfil M, B16.7 sub-família 2, MVE/Helium, `target/isa-decode/mve.decode`
    /// `@2op_sz28`, verbatim de `DO_2OP_SAT_L`/`do_qdmullh`/`do_qdmullw`, `target/arm/tcg/mve_helper.c`):
    /// ALARGA saturando — delega a {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#doublingWideningInterleavedMasked}
    /// (mesmo padrão de indexação intercalada `le*2+top` de {@link Vector2OpWidening}). Beatwise
    /// (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` quando alguma lane ATIVA satura — a
    /// PRIMEIRA forma alargante intercalada que satura (diferente de {@link Vector2OpWidening},
    /// B16.6, que nunca satura).
    record VectorDoublingWideningMultiply(
            /// `log2` do tamanho do elemento FONTE em bytes — `1`(halfword) ou `2`(word), `%size_28`
            /// (`bit28+1`).
            int esz,
            /// `true` para a forma `T` (lanes ÍMPARES da fonte); `false` para `B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`, fonte).
            int qn,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorDoublingWideningMultiply(core, this); }
    }

    /// Operações escalares (vetor × GPR broadcast, perfil M, B16.9, MVE/Helium, `target/isa-decode/
    /// mve.decode`, seção "Scalar operations", 22 encodings): reusa o MESMO
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} de {@link Vector2Op}
    /// (RFC B13.2 D1) — via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameScalarMasked}
    /// — mas o segundo operando é um valor ÚNICO lido de `Rm` (`0`-`15`, nunca `13`/`15`,
    /// UNPREDICTABLE em ambos, recusados no decode) e replicado por toda a operação, não uma lane de
    /// `Qm`. Cobre DUAS formas de encoding com o MESMO núcleo: `@2scalar` (`VADD_scalar`…
    /// `VQRDMULH_scalar`/`VMLA` — `qn`≠`qd`, `Rm` é o VALOR replicado) e `@shl_scalar`
    /// (`VSHL_S_scalar`…`VQRSHL_U_scalar` — `qn`={@link #qd}, o `&shl_scalar` real só tem `qda`;
    /// `Rm` é a CONTAGEM de deslocamento, mas {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameScalarMasked} funciona igual porque
    /// só o BYTE BAIXO de `Rm` importa nas formas `SSHL`/`USHL`/`SRSHL`/`URSHL`/`SQSHL`/`UQSHL`/
    /// `SQRSHL`/`UQRSHL`, preservado por qualquer truncamento `esz>=0`). **`VMLA` é a ÚNICA linha
    /// com `111 -` (bit 28 don't-care)** — o decoder não lê `U` para produzir `MLA`, único caso do
    /// arquivo. Beatwise (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` só para lanes ATIVAS nas
    /// 10 formas saturantes.
    record VectorScalar(
            /// Operação a executar (núcleo compartilhado) — qualquer valor de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} usado por
            /// {@link Vector2Op} EXCETO os exclusivos de widening/carry/complexo.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`) — para `@shl_scalar`, é o MESMO valor de {@link #qn} (`Qda`).
            int qd,
            /// `Qn` (`0`-`7`) — para `@shl_scalar`, é o MESMO valor de {@link #qd} (`Qda`, fonte E
            /// destino).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SCALAR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorScalar(core, this); }
        @Override public int regUse() { return (1 << rm) | GprMask.MVE_TAIL_PREDICATION; }
    }

    /// `VQDMULLB_scalar`/`VQDMULLT_scalar` (perfil M, B16.9, MVE/Helium, verbatim de
    /// `DO_2OP_SAT_SCALAR_L`, `target/arm/tcg/mve_helper.c`): ALARGA — lê `8 >> esz` elementos de
    /// `1 << esz` bytes de `Qn` na indexação INTERCALADA `le*2 + (top?1:0)` (mesmo padrão de
    /// {@link Vector2OpWidening}/{@link VectorDoublingWideningMultiply}) e multiplica cada um
    /// pelo MESMO valor de `Rm` (truncado a `esz`), saturando ao DOBRO da largura — delega a
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#doublingWideningScalarInterleavedMasked}.
    /// **`esz` vem do bit 28 DIRETAMENTE** (`%size_28`, que aqui coincide numericamente com o `esz`
    /// real da fonte: `0`→halfword(`1`), `1`→word(`2`) — ver Javadoc do decoder para a diferença com
    /// a Armadilha 2/7 da B16.7/B16.6, onde `%size_28` NÃO coincidia). `Qd == Qn` com `esz` word é
    /// UNPREDICTABLE (`a->qd == a->qn && a->size == MO_32` no QEMU real — "choose to undef"),
    /// recusado no decoder. Beatwise (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` quando
    /// alguma lane ATIVA satura.
    record VectorScalarWidening(
            /// `log2` do tamanho do elemento FONTE (`Qn`) em bytes — `1` (halfword) ou `2` (word);
            /// nunca `0`/`3` (ver Javadoc da classe).
            int esz,
            /// `true` para a forma `T` (lanes ÍMPARES da fonte); `false` para `B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qn` (`0`-`7`, fonte) — `Qd == Qn` com {@link #esz} `2` (word) já recusado no decoder.
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SCALAR_WIDENING; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorScalarWidening(core, this); }
        @Override public int regUse() { return (1 << rm) | GprMask.MVE_TAIL_PREDICATION; }
    }

    /// `VBRSR`/`VMLAS`/`VQDMLAH`/`VQRDMLAH`/`VQDMLASH`/`VQRDMLASH` (perfil M, B16.9, MVE/Helium):
    /// as 6 formas escalares SEM análogo em
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} — cada uma delega a um método
    /// dedicado de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes} (ver Javadoc de cada
    /// {@link SpecialOp} para a semântica verbatim do `mve_helper.c` real). `VBRSR` (bit reverse and
    /// shift right) não tem análogo em NEON/A64. `VMLAS` inverte os papéis de `Qd`/`Rm` em relação a
    /// `VMLA` ({@link VectorScalar}) — "vector * vector + scalar". `VQDMLAH`/`VQRDMLASH`/
    /// `VQDMLASH`/`VQRDMLASH` são multiplicação dobrada saturante com acumulador, saturada numa
    /// ÚNICA operação de largura dupla (arquiteturalmente distinta de `SQRDMLAH` do A64/NEON).
    /// Beatwise (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` só para lanes ATIVAS nas 4 formas
    /// `VQ*DMLA*H` (`VMLAS`/`VBRSR` nunca saturam).
    record VectorScalarSpecial(
            /// Qual das 6 operações executar.
            SpecialOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; `3` recusado no decoder).
            int esz,
            /// `Qd` (`0`-`7`) — fonte (acumulador, exceto `VBRSR`) E destino.
            int qd,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`; `13`/`15` já recusados no decoder, G8).
            int rm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SCALAR_SPECIAL; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorScalarSpecial(core, this); }
        @Override public int regUse() { return (1 << rm) | GprMask.MVE_TAIL_PREDICATION; }

        /// As 6 operações servidas por {@link VectorScalarSpecial} — nenhuma cabe em
        /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp} sem alterar o `switch`
        /// exaustivo compartilhado por A64/NEON/`MveIntegerOp.Vector2Op` (RFC B13.2 D1: reuso não vale a pena
        /// quando a semântica REAL diverge, aqui confirmado contra o QEMU verbatim).
        public enum SpecialOp {
            /// `Qd[i] = do_vbrsr(Qn[i], Rm)` — ver Javadoc de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bitReverseShiftRightMasked}.
            VBRSR,
            /// `Qd[i] = Qn[i] * Qd[i] + Rm` — ver Javadoc de
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#multiplyAccumulateSwapScalarMasked}.
            VMLAS,
            /// `Qd[i] = round(2*Qn[i]*Rm) + Qd[i]`, saturado — `swapAccumulatorAndScalar=false`,
            /// `rounding=false` em
            /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#doublingMultiplyAccumulateScalarMasked}.
            VQDMLAH,
            /// Como {@link #VQDMLAH}, com arredondamento (`rounding=true`).
            VQRDMLAH,
            /// `Qd[i] = round(2*Qn[i]*Qd[i]) + Rm`, saturado — `swapAccumulatorAndScalar=true`,
            /// `rounding=false`.
            VQDMLASH,
            /// Como {@link #VQDMLASH}, com arredondamento (`rounding=true`).
            VQRDMLASH
        }
    }

    /// `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/`VSRI`/`VSLI`
    /// (perfil M, B16.10, MVE/Helium, `target/isa-decode/mve.decode`, `@2_shl_*`/`@2_shr_*`): reusa
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp} — MESMO núcleo do A64/NEON
    /// (RFC B13.2 D1) — via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftImmediateMasked}. `shift` já vem
    /// resolvido do decoder (`N - shift` nas formas `_shr`/`VSRI`, valor cru nas formas `_shl`/
    /// `VSLI`/`VSHLUI`). Beatwise (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` só para as 3
    /// formas saturantes (`SQSHL`/`UQSHL`/`SQSHLU`).
    record VectorShiftImmediate(
            /// Operação a executar (núcleo compartilhado com A64/NEON).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp op,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; determinado pelo PREFIXO de bits do
            /// encoding, não por um campo `size` — ver Javadoc do decoder).
            int esz,
            /// Quantidade de deslocamento, já resolvida pelo decoder.
            int shift,
            /// `Qd` (`0`-`7`) — fonte (RMW nas formas `SRI`/`SLI`) E destino.
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_IMMEDIATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorShiftImmediate(core, this); }
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma **T1** (`shift < esize`, perfil M, B16.10,
    /// MVE/Helium, `target/isa-decode/mve.decode` `@2_shll_b`/`@2_shll_h`): ALARGA — mesmo padrão de
    /// indexação INTERCALADA `le*2 + (top?1:0)` de {@link VectorShiftWidenInterleaved} (T2), via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftWidenInterleavedMasked} — a
    /// ÚNICA diferença real entre as duas formas é que aqui `shift` é um campo do encoding (`0` a
    /// `esize-1`) em vez de fixo em `esize`. **`VMOVL` é esta forma com `shift == 0`** (comentário
    /// literal do arquivo real: "we implement it that way rather than special-casing it in the
    /// decode") — não tem `Kind` próprio. **Não confundir com a forma T2 (B16.7, encoding
    /// DISTINTO).** Beatwise (mesmo gancho de {@link Vector2Op}). Nunca satura.
    record VectorShiftWidenImmediateInterleaved(
            /// `true` = sinal-estende (`VSHLL_*S`); `false` = zero-estende (`VSHLL_*U`).
            boolean signed,
            /// `log2` do tamanho do elemento FONTE em bytes — `0`(byte) ou `1`(halfword); a T1 nunca
            /// tem fonte WORD.
            int esz,
            /// Quantidade de deslocamento, já resolvida pelo decoder (`0..esize-1`; `0` = `VMOVL`).
            int shift,
            /// `true` para a forma `_T` (lanes ÍMPARES da fonte); `false` para `_B` (lanes PARES).
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorShiftWidenImmediateInterleaved(core, this); }
    }

    /// `VSHRNB`/`VSHRNT`/`VRSHRNB`/`VRSHRNT`/`VQSHRNB_S`/`VQSHRNT_S`/`VQSHRNB_U`/`VQSHRNT_U`/
    /// `VQSHRUNB`/`VQSHRUNT`/`VQRSHRNB_S`/`VQRSHRNT_S`/`VQRSHRNB_U`/`VQRSHRNT_U`/`VQRSHRUNB`/
    /// `VQRSHRUNT` (perfil M, B16.11, MVE/Helium, `target/isa-decode/mve.decode`, só `b`/`h`):
    /// ESTREITA com deslocamento — mesmo padrão de indexação INTERCALADA `le*2 + (top?1:0)` de
    /// {@link VectorNarrowInterleaved}, via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftNarrowInterleavedMasked}.
    /// Reusa {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp} (MESMO enum/mapeamento
    /// do A64/NEON: `SHRN`=`VSHRN`, `RSHRN`=`VRSHRN`, `SQSHRN`/`UQSHRN`=`VQSHRN_S`/`_U`,
    /// `SQSHRUN`=`VQSHRUN`, `SQRSHRN`/`UQRSHRN`=`VQRSHRN_S`/`_U`, `SQRSHRUN`=`VQRSHRUN`). Beatwise
    /// (mesmo gancho de {@link Vector2Op}). `FPSCR.QC` só para as 6 formas saturantes (`SHRN`/
    /// `RSHRN` nunca saturam).
    record VectorShiftNarrowImmediateInterleaved(
            /// Operação de deslocamento estreitante a executar.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp op,
            /// `log2` do tamanho do elemento ESTREITO (saída) em bytes — `0`(byte) ou `1`(halfword);
            /// nunca `2`/`3` (a família só suporta `b`/`h`, daí o título da seção real).
            int esz,
            /// Quantidade de deslocamento, já resolvida pelo decoder (`N - raw`, `%rshift_i3/i4`).
            int shift,
            /// `true` para a forma `T`; `false` para `B`.
            boolean top,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, fonte).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorShiftNarrowImmediateInterleaved(core, this); }
    }

    /// `VSHLC` (perfil M, B16.11, MVE/Helium, `target/isa-decode/mve.decode`): "Whole Vector Left
    /// Shift with Carry" — desloca os 128 bits de `Qd` à esquerda por `imm` bits (contagem `1`-`32`;
    /// `imm == 0` no encoding significa "desloca por 32", NÃO é `UNDEF`/no-op — confirmado verbatim
    /// contra `trans_VSHLC`/`HELPER(mve_vshlc)`, `target/arm/tcg/{translate,mve_helper}.c`),
    /// injetando os bits BAIXOS de `Rdm` na base e devolvendo em `Rdm` os bits que saíram pelo topo.
    /// **NÃO é estreitante** (está na mesma seção do arquivo real por adjacência de encoding, não por
    /// família — ver Javadoc do decoder) e **NÃO é predicada lane-a-lane**: opera em 4 elementos de
    /// 32 bits (granularidade de BEAT), cada um checado contra UM bit da máscara MVE (`mask & 1` por
    /// beat, não por byte). Beatwise (mesmo gancho de {@link Vector2Op} para `AdvanceVpt`/`ECI`).
    /// Nunca satura, sem `FPSCR.QC`.
    record VectorShiftLeftCarry(
            /// Campo `imm:5` cru do encoding (`0`-`31`; `0` significa "desloca por 32" — NUNCA
            /// pré-resolvido pelo decoder, ao contrário de {@link VectorShiftImmediate}, porque o
            /// helper real trata `shift == 0` como caso especial, não como "sem deslocamento").
            int imm,
            /// `Qd` (`0`-`7`) — fonte E destino.
            int qd,
            /// `Rdm` (GPR que fornece os bits que entram e recebe os que saem; `13`/`15` são `UNDEF`,
            /// recusados pelo decoder).
            int rdm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_SHIFT_LEFT_CARRY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorShiftLeftCarry(core, this); }
        @Override public int regUse() { return (1 << rdm) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return 1 << rdm; }
    }

    /// `VCLS`/`VCLZ`/`VREV16`/`VREV32`/`VREV64`/`VMVN`/`VABS`/`VNEG`/`VQABS`/`VQNEG` (perfil M,
    /// B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode` `@1op`/`@1op_nosz`,
    /// linhas 371-386, 10 dos 15 encodings da sub-família 2 — os outros 2 são {@link
    /// MveFpOp.VectorFpUnary}, 3 são {@link MveMoveOp.VectorDup}): delega ao núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#unaryMasked}) — a MESMA operação ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp}) que `NeonIntegerOp.Unary`/A64 já usam via
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#unary} para o caminho NÃO predicado —
    /// zero código novo de aritmética, só o gancho PREDICADO. `VMVN` usa `esz=0` sempre (`@1op_nosz`
    /// — bitwise, tamanho de elemento é irrelevante). `FPSCR.QC` setado (via {@link
    /// dev.vitorsilverio.armjitter.core.FpscrRegister#orQc}) quando `SQABS`/`SQNEG` saturam numa
    /// lane ATIVA. Beatwise (mesmo gancho de {@link Vector2Op}).
    record VectorUnary(
            /// Operação a executar (núcleo compartilhado) — só `CLS`/`CLZ`/`REV64`/`REV32`/`REV16`/
            /// `NOT`/`ABS`/`NEG`/`SQABS`/`SQNEG` têm forma correspondente nesta família MVE.
            dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp op,
            /// `0`(byte)/`1`(halfword)/`2`(word); sempre `0` para `VMVN` (`@1op_nosz`).
            int esz,
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`).
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MveIntegerOp {
        @Override public int kind() { return Kind.MVE_VECTOR_UNARY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return IrMveIntegerExecutor.executeMveVectorUnary(core, this); }
    }
}
