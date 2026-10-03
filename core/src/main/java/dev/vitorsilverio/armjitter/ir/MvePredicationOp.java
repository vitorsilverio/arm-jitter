package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações MVE de predicação e controle: máquina `VPT` (`VPST`/`VPNOT`/`VPSEL`/`VPR`), predicação
/// de cauda (`VCTP`/`LCTP`), continuação de exceção (`ECI`) e as comparações que escrevem `VPR.P0`.
///
/// Sub-interface selada de {@link MveOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface MvePredicationOp extends MveOp permits
        MvePredicationOp.LoopClearTailPredication, MvePredicationOp.Vctp,
        MvePredicationOp.AdvanceVpt, MvePredicationOp.Vpst, MvePredicationOp.Vpnot,
        MvePredicationOp.Vpsel, MvePredicationOp.VprTransfer, MvePredicationOp.AdvanceEci,
        MvePredicationOp.VectorCompare, MvePredicationOp.VectorCompareScalar {

    /// `LCTP` (perfil M, B16.15, MVE): restaura `FPSCR.LTPSIZE = 4` (tail-predication inativa) —
    /// única coisa que a instrução faz (`trans_LCTP` do QEMU, que não guarda cache de branch).
    record LoopClearTailPredication(
            /// Condição necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.LOOP_CLEAR_TAIL_PREDICATION; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.systemExecutor().executeLctp(core, this); return false; }
        // Só grava `FPSCR.LTPSIZE`.
        @Override public int regUse() { return 0; }
    }

    /// `VCTP.<size> Rn` (perfil M, B16.15, MVE, beatwise): cria o predicado de cauda em `VPR.P0` —
    /// os primeiros `Rn << size` bytes ficam ativos (todos os 16 se `Rn > 16 >> size`), mascarados
    /// pelo `elementMask` corrente e gravados só nos beats ainda não executados (`HELPER(mve_vctp)`
    /// do QEMU real).
    record Vctp(
            /// Registrador com o número de elementos restantes.
            int rn,
            /// Log2 do tamanho de elemento em bytes (`0`=8 bits ... `3`=64 bits).
            int size,
            /// Condição necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.VCTP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeVctp(core, this); }
        @Override public int regUse() { return (1 << rn) | GprMask.MVE_TAIL_PREDICATION; }
    }

    /// Avanço pós-instrução do `VPR`/`ECI` (perfil M, B16.2, MVE/Helium) — transcrição de
    /// `mve_advance_vpt` via {@link dev.vitorsilverio.armjitter.core.MveVptState#advance}. Emitido
    /// por `StandardIrBuilder#lift` depois de QUALQUER {@code InstructionKind} beatwise (ver
    /// {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#isMveBeatwise()}), mesmo padrão
    /// de {@link SystemOp.SetItState} (avanço do `IT`) — sempre com {@link Condition#AL}: o avanço é
    /// INCONDICIONAL (G4), mesmo quando a instrução governada estava totalmente predicada.
    record AdvanceVpt(
            /// Condição necessária para executar — sempre {@link Condition#AL} na prática (o
            /// lifter nunca emite este `IrOp` sob outra condição, mesmo padrão de `SetItState`).
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.ADVANCE_VPT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.systemExecutor().executeAdvanceVpt(core, this); return false; }
        // Só avança `VPR`/`ECI`.
        @Override public int regUse() { return 0; }
    }

    /// `VPST` (perfil M, B16.2, MVE/Helium, `target/isa-decode/mve.decode`): grava `mask` em
    /// `VPR.MASK01`/`MASK23` via {@link dev.vitorsilverio.armjitter.core.MveVptState#vpstMask}
    /// (o `eci` corrente decide se `MASK01` também é atualizado, ver Javadoc de `vpstMask`).
    record Vpst(
            /// Campo `mask` de 4 bits (`%mask_22_13`, bit 22 ++ bits\[15:13\]).
            int mask,
            /// Condição necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.VPST; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeVpst(core, this); }
        @Override public int regUse() { return 0; }
    }

    /// `VPNOT` (perfil M, B16.2, MVE/Helium): inverte `VPR.P0` nas lanes correspondentes aos beats
    /// já executados (via {@link dev.vitorsilverio.armjitter.core.MveVptState#eciMask} — o mesmo
    /// idioma que `mve_advance_vpt` usa para o "invMask" antes de deslocar `MASK01`/`MASK23`).
    /// Nenhum campo neutro além da condição: o encoding é totalmente fixo (`VPST` com `mask=0`).
    record Vpnot(
            /// Condição necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.VPNOT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeVpnot(core, this); }
        @Override public int regUse() { return 0; }
    }

    /// `VPSEL` (perfil M, B16.2, MVE/Helium): seleciona lane a lane (byte a byte, `@2op_nosz` —
    /// sem campo `size`, a arquitetura real não distingue largura de elemento aqui) entre `qn` e
    /// `qm` conforme `VPR.P0`, escrevendo em `qd`, mascarado pelo `elementMask` corrente (beat/
    /// tail/ECI) — ver Javadoc do executor para a derivação da semântica exata (não pôde ser
    /// confirmada byte a byte contra `HELPER(mve_vpsel)` do QEMU real nesta rodada de spec, ver
    /// `## Resultado` da task).
    record Vpsel(
            /// `Qd` (`0`-`7` depois de validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qd,
            /// `Qn`.
            int qn,
            /// `Qm`.
            int qm,
            /// Condição necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.VPSEL; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeVpsel(core, this); }
    }

    /// `VMSR`/`VMRS` com `reg=12` (perfil M, B16.2, MVE/Helium): transfere o `VPR` bruto de/para
    /// `armRegister` — MESMO layout de {@link VfpOp.SystemTransfer}, sem o caso especial de aliasing
    /// `APSR_nzcv` (`VPR` não tem equivalente) e sem chamar {@link
    /// dev.vitorsilverio.armjitter.core.MveVptState#advance} (`VMSR_VMRS` nunca é beatwise, ao
    /// contrário de {@link Vpst}/{@link Vpnot}/{@link Vpsel} — o QEMU real não chama
    /// `mve_advance_vpt` aqui).
    record VprTransfer(
            /// `true` para `VMRS` (`VPR` → `armRegister`); `false` para `VMSR` (`armRegister` →
            /// `VPR`).
            boolean read,
            /// Registrador ARM envolvido (`Rt`, nunca `15` — recusado no decode).
            int armRegister,
            /// Condição necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.VPR_TRANSFER; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.systemExecutor().executeVprTransfer(core, this); return false; }
        @Override public int regUse() { return read ? 0 : (1 << armRegister); }
        @Override public int regDef() { return read ? (1 << armRegister) : 0; }
    }

    /// Avanço pós-instrução SÓ do `ECI` (perfil M, B16.5, MVE/Helium) — `mve_update_and_store_eci`
    /// verbatim: cicla o nibble de `ECI` exatamente como
    /// {@link dev.vitorsilverio.armjitter.core.MveVptState#advance} faz, mas
    /// **nunca** toca `VPR.MASK01`/`MASK23`/`P0` (ao contrário de {@link AdvanceVpt}). Usado só por
    /// {@link MveMoveOp.InterleavedLoadStore} (`VLD2`/`VLD4`/`VST2`/`VST4`) — instruções "beatwise mas não
    /// predicadas" que não participam da máquina `VPT` (ver Javadoc de {@link MveMoveOp.InterleavedLoadStore}).
    record AdvanceEci(
            /// Condição necessária para executar — sempre {@link Condition#AL} na prática, mesmo
            /// padrão de {@link AdvanceVpt}.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.ADVANCE_ECI; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.systemExecutor().executeAdvanceEci(core, this); return false; }
        @Override public int regUse() { return 0; }
    }

    /// `VCMPEQ`/`VCMPNE`/`VCMPGE`/`VCMPLT`/`VCMPGT`/`VCMPLE`/`VCMPCS`/`VCMPHI` e as 6 formas `_fp`
    /// correspondentes (perfil M, B16.8, MVE/Helium, `target/isa-decode/mve.decode`, seção
    /// "Comparisons", vetor×vetor): compara `Qn`/`Qm` lane a lane e escreve o resultado em
    /// `VPR.P0` (**não** um registrador vetorial — diferente de `CMEQ`/`CMGT` do NEON/A64,
    /// Armadilha 2 da task), replicando o bit de cada lane pelos `1 << esz` bytes do elemento
    /// (verbatim de `DO_VCMP`, `target/arm/tcg/mve_helper.c`: "Comparison sets 0/1 bits for each
    /// byte in the element"). Bits de `P0` para beats AINDA NÃO executados (`eciMask == 0`)
    /// ficam INTOCADOS; para lanes predicadas-fora em beats executados (`elementMask == 0`)
    /// ficam ZERADOS; do contrário recebem o resultado da comparação (comentário literal do QEMU
    /// real, citado verbatim no Javadoc do executor). Quando `mask` (`%mask_22_13`) é diferente de
    /// zero, a instrução é uma `VPT` (`VCMP` seguida de `VPST`, achado da B16.2) — o
    /// `StandardIrBuilder` emite um {@link Vpst} adicional LOGO APÓS o {@link AdvanceVpt} desta
    /// instrução, reproduzindo a ordem real do QEMU (`do_vcmp`: helper que já chama
    /// `mve_advance_vpt` interno, DEPOIS `gen_vpst` se `a->mask`). Beatwise ({@link AdvanceVpt}
    /// sempre roda depois) e TERMINAL (`DISAS_UPDATE_NOCHAIN` real — ver
    /// {@link dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter}).
    record VectorCompare(
            /// Condição de comparação (`EQ`/`NE`/`GE`/`LT`/`GT`/`LE`/`CS`/`HI`) — `CS`/`HI` só
            /// existem nas formas inteiras, nunca em {@link #floatingPoint}.
            dev.vitorsilverio.armjitter.advsimd.MveCompareCondition compareCondition,
            /// `true` para as formas `_fp` (`@vcmp_fp`); `false` para as inteiras (`@vcmp`).
            boolean floatingPoint,
            /// Tamanho do elemento: inteiras `0`/`1`/`2` (byte/halfword/word, extraído do campo
            /// `size` — `size==3` já recusado no decode); FP `1`/`2` (binary16/binary32, bit 28,
            /// `%2op_fp_scalar_size`, MESMA convenção `neon_3same_fp_size` de
            /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2MveVector2opFpDecoder}).
            int esz,
            /// `Qn` (campo inline de 3 bits em `@vcmp`/`@vcmp_fp` — sempre `0`-`7`, nunca precisa
            /// de validação de faixa).
            int qn,
            /// `Qm` (`%qm`, 4 bits — já validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qm,
            /// Campo `mask` de 4 bits (`%mask_22_13`) — `VCMP` pura tem `mask=0`; `!= 0` estabelece
            /// `VPT` (ver Javadoc da classe).
            int mask,
            /// Condição ARM necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.MVE_VECTOR_COMPARE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorCompare(core, this); }
    }

    /// Forma escalar (vetor × GPR broadcast) das mesmas 8 condições de {@link VectorCompare}
    /// (`@vcmp_scalar`/`@vcmp_fp_scalar`) — MESMA semântica de escrita em `P0`, comparando cada
    /// lane de `Qn` contra o MESMO valor de `Rm` (broadcast). **Achado medido contra o QEMU real
    /// nesta task, que DIVERGE do que a spec citava**: `do_vcmp_scalar` só recusa `Rm == 13`
    /// (`a->rm == 13` → `false`, UNPREDICTABLE); `Rm == 15` é uma forma VÁLIDA, "constante zero"
    /// (`if (a->rm == 15) rm = tcg_constant_i32(0);`), não UNPREDICTABLE — resolvido no
    /// EXECUTOR, não no decode. `@vcmp_fp_scalar` **não decodifica o bit 28** (comentário literal
    /// do arquivo real: "we do not decode it in this format to avoid complicated
    /// overlapping-instruction-groups") — cada linha passa `size=1` ou `size=2` fixo.
    record VectorCompareScalar(
            dev.vitorsilverio.armjitter.advsimd.MveCompareCondition compareCondition,
            boolean floatingPoint,
            /// Inteiras: `0`/`1`/`2` (campo `size`, `size==3` recusado no decode). FP: `1`/`2`
            /// literal por linha (bit 28 NÃO decodificado, ver Javadoc da classe).
            int esz,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Rm` (`0`-`15`, `13` já recusado no decode; `15` = "constante zero", resolvido no
            /// executor).
            int rm,
            /// Campo `mask` de 4 bits — MESMA semântica de {@link VectorCompare#mask}.
            int mask,
            /// Condição ARM necessária para executar.
            Condition condition) implements MvePredicationOp {
        @Override public int kind() { return Kind.MVE_VECTOR_COMPARE_SCALAR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorCompareScalar(core, this); }
        /// `rm == 15` codifica o escalar zero, não o `PC`.
        private static final int ZERO_SCALAR_ENCODING = 15;
        @Override public int regUse() { return (rm == ZERO_SCALAR_ENCODING ? 0 : (1 << rm)) | GprMask.MVE_TAIL_PREDICATION; }
    }
}
