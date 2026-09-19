package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B14.4 — `VSEL`/`VMAXNM`/`VMINNM` (espaço VFP incondicional, `bits[31:28]=0xF`, `vfp-uncond.decode`).
/// Oráculo: QEMU `target/arm/tcg/translate-vfp.c` (`trans_VSEL`/`trans_VMAXNM_sp/dp`). Ver
/// `VfpDecoder#decodeUnconditionalSpace` e a task `b14.4-vsel-vmaxnm-vminnm.md`.
class VfpUnconditionalSelectMaxMinTest {
    /// Mesma base de `VfpDecoderTest#VFP_TEST_ARCH` (VFPV2 sobre ARMV6K_THUMB2), SEM
    /// `ArmFeature#ARMV8_FP` — o "ARMV7A/ARM11_MPCORE" desta suíte, usado para provar o
    /// NÃO-misdecode (achado central da task).
    private static final ArmArchitecture VFP_TEST_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K_THUMB2, "ARMv7-TestVfpUncond", ArmFeature.VFPV2);
    private static final ArmArchitecture VFP_TEST_ARCH = VFP_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(VFP_TEST_FEATURES));

    /// Mesma base, mais `ArmFeature#ARMV8_FP` — equivalente de teste do `ArmArchitecture#ARMV8A_32`
    /// real (B14.1), só que sem `LOAD_ACQUIRE_STORE_RELEASE`/`CRC32`/`HALT` (irrelevantes aqui).
    private static final ArmArchitecture VFP_V8_TEST_FEATURES =
            ArmArchitecture.extending(VFP_TEST_FEATURES, "ARMv8-TestVfpUncond", ArmFeature.ARMV8_FP);
    private static final ArmArchitecture VFP_V8_TEST_ARCH = VFP_V8_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_V8_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(VFP_V8_TEST_FEATURES));

    private static List<DecoderExtension> thumb32Extensions(ArmArchitecture features) {
        return List.of(new Thumb2VfpDecoder(features), new Thumb2CoprocessorDecoder());
    }

    // ── Encoders manuais (vfp-uncond.decode) ──────────────────────────────────────────────────

    private static int nibbleOf(int combined, boolean doublePrecision) {
        return doublePrecision ? combined & 0xF : combined >>> 1;
    }

    private static int extOf(int combined, boolean doublePrecision) {
        return doublePrecision ? (combined >>> 4) & 1 : combined & 1;
    }

    private static int size(boolean doublePrecision) {
        return doublePrecision ? 0xB : 0xA;
    }

    /// `VSEL` (`sz=2`/`sz=3`): `1111 1110 0. cc:2 vn(4) vd(4) size .0.0 vm(4)`.
    private static int vselWord(int cc, boolean doublePrecision, int vd, int vn, int vm) {
        int word = (0xF << 28) | (0xE << 24);
        word |= extOf(vd, doublePrecision) << 22;
        word |= (cc & 0x3) << 20;
        word |= nibbleOf(vn, doublePrecision) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= extOf(vn, doublePrecision) << 7;
        word |= extOf(vm, doublePrecision) << 5;
        word |= nibbleOf(vm, doublePrecision);
        return word;
    }

    /// `VMAXNM`(`bit6=0`)/`VMINNM`(`bit6=1`) `sp`/`dp`: `1111 1110 1.00 vn(4) vd(4) size .b.0 vm(4)`.
    private static int maxNmMinNmWord(boolean min, boolean doublePrecision, int vd, int vn, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (1 << 23);
        word |= extOf(vd, doublePrecision) << 22;
        word |= nibbleOf(vn, doublePrecision) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= extOf(vn, doublePrecision) << 7;
        word |= (min ? 1 : 0) << 6;
        word |= extOf(vm, doublePrecision) << 5;
        word |= nibbleOf(vm, doublePrecision);
        return word;
    }

    private static DecodedInstruction decodeArm(ArmArchitecture arch, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(arch).decode(memory, 0);
    }

    private static DecodedInstruction decodeThumb32(ArmArchitecture arch, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, word >>> 16);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(arch).decode(memory, 0);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), VFP_V8_TEST_ARCH);
    }

    // ── 1. Decodificação/lift corretos sob ARMV8_FP (A32 e T32) ──────────────────────────────

    @Test
    void vselSingleDecodesToVfpSelectAllFourConditions() {
        Condition[] expected = {Condition.EQ, Condition.VS, Condition.GE, Condition.GT};
        for (int cc = 0; cc < 4; cc++) {
            DecodedInstruction decoded = decodeArm(VFP_V8_TEST_ARCH, vselWord(cc, false, 2, 0, 1));
            assertEquals(InstructionKind.VFP_SELECT, decoded.kind());
            IrOp op = liftSingleOp(decoded);
            assertEquals(new IrOp.VfpSelect(false, 2, 0, 1, expected[cc], Condition.AL), op);
        }
    }

    @Test
    void vselDoubleDecodesToVfpSelect() {
        IrOp op = liftSingleOp(decodeArm(VFP_V8_TEST_ARCH, vselWord(3, true, 5, 1, 2)));
        assertEquals(new IrOp.VfpSelect(true, 5, 1, 2, Condition.GT, Condition.AL), op);
    }

    @Test
    void vmaxnmSingleDecodesToVfpAlu() {
        IrOp op = liftSingleOp(decodeArm(VFP_V8_TEST_ARCH, maxNmMinNmWord(false, false, 4, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.MAXNM, false, 4, 0, 1, Condition.AL), op);
    }

    @Test
    void vminnmDoubleDecodesToVfpAlu() {
        IrOp op = liftSingleOp(decodeArm(VFP_V8_TEST_ARCH, maxNmMinNmWord(true, true, 4, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.MINNM, true, 4, 0, 1, Condition.AL), op);
    }

    @Test
    void vselRoundTripsArmAndThumb2() {
        int word = vselWord(2, false, 3, 1, 2);
        DecodedInstruction armDecoded = decodeArm(VFP_V8_TEST_ARCH, word);
        DecodedInstruction thumbDecoded = decodeThumb32(VFP_V8_TEST_ARCH, word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    @Test
    void vmaxnmRoundTripsArmAndThumb2() {
        int word = maxNmMinNmWord(false, true, 3, 1, 2);
        DecodedInstruction armDecoded = decodeArm(VFP_V8_TEST_ARCH, word);
        DecodedInstruction thumbDecoded = decodeThumb32(VFP_V8_TEST_ARCH, word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    // ── 2. Não-misdecode (achado central da B14.4): sob ARMV7A/MPCore (sem ARMV8_FP) ─────────

    @Test
    void vselNeverMisdecodesAsVmlaVnmlsVmulVaddWithoutArmv8Fp() {
        for (int cc = 0; cc < 4; cc++) {
            DecodedInstruction decoded = decodeArm(VFP_TEST_ARCH, vselWord(cc, false, 2, 0, 1));
            assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind(),
                    "VSEL cc=" + cc + " misdecodificou como " + decoded.kind());
            assertNotEquals(InstructionKind.VFP_ALU, decoded.kind());
        }
        for (int cc = 0; cc < 4; cc++) {
            DecodedInstruction decoded = decodeArm(VFP_TEST_ARCH, vselWord(cc, true, 5, 1, 2));
            assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
        }
    }

    @Test
    void vmaxnmVminnmNeverMisdecodeAsVdivWithoutArmv8Fp() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, maxNmMinNmWord(false, false, 4, 0, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, maxNmMinNmWord(true, false, 4, 0, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, maxNmMinNmWord(false, true, 4, 0, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, maxNmMinNmWord(true, true, 4, 0, 1)).kind());
    }

    @Test
    void vselNeverMisdecodesInThumb2WithoutArmv8Fp() {
        DecodedInstruction decoded = decodeThumb32(VFP_TEST_ARCH, vselWord(0, false, 2, 0, 1));
        assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
    }

    // ── 3. Zero-diff do espaço condicional: MCR2/CDP2 continuam livres para cp9/cp15 ──────────

    @Test
    void mcr2ToCp15UnderCond1111StaysUnclaimedByVfpDecoder() {
        // MCR2 p15,0,R0,c2,c0,0: cond=1111, bits[27:24]=1110, bit4=1, coproc=1111.
        int word = (0xF << 28) | (0xE << 24) | (0 << 21) | (2 << 16) | (0 << 12) | (0xF << 8) | (0 << 5) | (1 << 4) | 0;
        DecodedInstruction decoded = decodeArm(VFP_V8_TEST_ARCH, word);
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
        assertEquals(InstructionKind.COPROCESSOR, decoded.kind());
    }

    // ── 4. Execução: VSEL seleciona pelos flags do CPSR, sem tocar FPSCR ──────────────────────

    @Test
    void vselSelectsVnWhenSelectConditionTrueOtherwiseVm() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 11.0f);
        core.vfp().setSFloat(1, 22.0f);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro -> escolhe vn.
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpSelect(false, 2, 0, 1, Condition.EQ, Condition.AL), 0);
        assertEquals(11.0f, core.vfp().sFloat(2));

        core.cpsr().setNzcv(false, false, false, false); // Z=0 -> EQ falso -> escolhe vm.
        executor.executeOp(core, new IrOp.VfpSelect(false, 2, 0, 1, Condition.EQ, Condition.AL), 0);
        assertEquals(22.0f, core.vfp().sFloat(2));
    }

    /// Fecha o branch `false` de `IrVfpExecutor#executeVfpSelect` — `op.condition()` (gate do
    /// BLOCO) é sempre `AL` no que o decoder produz, mas o record aceita qualquer `Condition` (é
    /// código real, alcançável por quem monta `IrOp.VfpSelect` na mão, mesmo padrão de
    /// `executeVfpCompare`/`executeVfpConvert` etc.). Prova direta da Armadilha 2: `condition`
    /// (bloco) e `selectCondition` (dado) são campos INDEPENDENTES — aqui `condition=NE` é falso
    /// (Z=1) e a operação inteira é pulada, mesmo que `selectCondition=EQ` fosse verdadeiro.
    @Test
    void vselWithFalseBlockConditionSkipsEntirelyRegardlessOfSelectCondition() {
        ArmCore core = newCore();
        core.vfp().setSFloat(2, -1.0f); // valor sentinela: não deve mudar.
        core.vfp().setSFloat(0, 11.0f);
        core.vfp().setSFloat(1, 22.0f);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> NE falso, EQ verdadeiro.
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpSelect(false, 2, 0, 1, Condition.EQ, Condition.NE), 0);
        assertEquals(-1.0f, core.vfp().sFloat(2));
    }

    @Test
    void vselDoublePrecisionCopiesBitsExactly() {
        ArmCore core = newCore();
        core.vfp().setD(0, Double.doubleToRawLongBits(Double.NaN));
        core.vfp().setDDouble(1, 7.5);
        core.cpsr().setNzcv(true, false, false, true); // N=1,V=1 -> GE (N==V) verdadeiro -> vn.
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpSelect(true, 2, 0, 1, Condition.GE, Condition.AL), 0);
        assertEquals(Double.doubleToRawLongBits(Double.NaN), core.vfp().d(2));
    }

    /// Fecha o lado `vm` do ternário de precisão dupla em `executeVfpSelect` — o teste acima só
    /// exercitava `selectCondition` verdadeiro (escolhe `vn`); este cobre falso (escolhe `vm`).
    @Test
    void vselDoublePrecisionSelectsVmWhenSelectConditionFalse() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 1.5);
        core.vfp().setD(1, Double.doubleToRawLongBits(Double.NaN));
        core.cpsr().setNzcv(true, false, false, false); // N=1,V=0 -> GE (N==V) falso -> escolhe vm.
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpSelect(true, 2, 0, 1, Condition.GE, Condition.AL), 0);
        assertEquals(Double.doubleToRawLongBits(Double.NaN), core.vfp().d(2));
    }

    // ── 5. Execução: VMAXNM/VMINNM (semântica maxNum/minNum, nunca Math.max/Math.min) ─────────

    @Test
    void vmaxnmPrefersNumericOperandOverNaN() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, Float.NaN);
        core.vfp().setSFloat(1, 1.0f);
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MAXNM, false, 2, 0, 1, Condition.AL), 0);
        assertEquals(1.0f, core.vfp().sFloat(2));

        executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MAXNM, false, 2, 1, 0, Condition.AL), 0);
        assertEquals(1.0f, core.vfp().sFloat(2));
    }

    @Test
    void vminnmBothNanProducesNan() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, Float.NaN);
        core.vfp().setSFloat(1, Float.NaN);
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MINNM, false, 2, 0, 1, Condition.AL), 0);
        assertEquals(true, Float.isNaN(core.vfp().sFloat(2)));
    }

    @Test
    void vmaxnmZeroSignsMatchAdvSimdLanesMaxNum() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 0.0);
        core.vfp().setDDouble(1, -0.0);
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MAXNM, true, 2, 0, 1, Condition.AL), 0);
        assertEquals(dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.maxNum(0.0, -0.0), core.vfp().dDouble(2));
    }

    /// `computeDoubleArithmetic` tem o `case MINNM` próprio (não cai no `default`/`MAXNM`) — sem
    /// este teste, o `switch` de precisão dupla nunca exercitava `MINNM`, só `MAXNM`.
    @Test
    void vminnmDoublePrecisionPrefersNumericOperandOverNaN() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, Double.NaN);
        core.vfp().setDDouble(1, 2.5);
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MINNM, true, 2, 0, 1, Condition.AL), 0);
        assertEquals(2.5, core.vfp().dDouble(2));
    }

    /// Equivalência explícita com o núcleo A64 `FMAXNM`/`FMINNM` (mesma delegação a
    /// `AdvSimdLanes`) — casos de teste obrigatórios da task: `(NaN,1.0)`, `(1.0,NaN)`,
    /// `(NaN,NaN)`, `(+0.0,-0.0)`, `(-0.0,+0.0)`.
    @Test
    void vmaxnmVminnmMatchAdvSimdLanesForMandatoryCases() {
        float[][] pairs = {{Float.NaN, 1.0f}, {1.0f, Float.NaN}, {Float.NaN, Float.NaN}, {0.0f, -0.0f}, {-0.0f, 0.0f}};
        ArmCore core = newCore();
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        for (float[] pair : pairs) {
            core.vfp().setSFloat(0, pair[0]);
            core.vfp().setSFloat(1, pair[1]);
            executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MAXNM, false, 2, 0, 1, Condition.AL), 0);
            float expectedMax = dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.maxNum(pair[0], pair[1]);
            assertEquals(Float.floatToRawIntBits(expectedMax), Float.floatToRawIntBits(core.vfp().sFloat(2)));

            executor.executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MINNM, false, 2, 0, 1, Condition.AL), 0);
            float expectedMin = dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.minNum(pair[0], pair[1]);
            assertEquals(Float.floatToRawIntBits(expectedMin), Float.floatToRawIntBits(core.vfp().sFloat(2)));
        }
    }

    /// Prova DIRETA (não só por exclusão no loop de equivalência) de que `AsmNativePolicy` recusa
    /// `MAXNM`/`MINNM` — achado da revisão de cobertura: o loop de equivalência só EXCLUI esses 2
    /// valores, nunca invoca `supports()` com eles, então o branch `op==MAXNM`/`op==MINNM` de
    /// `AsmNativePolicy` ficava sem teste algum (JaCoCo confirmou: 1 de 4 branches faltando).
    @Test
    void asmNativePolicyRefusesMaxNmAndMinNmButAcceptsRestOfVfpAlu() {
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpAlu(IrOp.VfpOperation.MAXNM, false, 0, 1, 2, Condition.AL)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpAlu(IrOp.VfpOperation.MINNM, true, 0, 1, 2, Condition.AL)));
        assertEquals(true, AsmNativePolicy.supports(new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 0, 1, 2, Condition.AL)));
    }

    // ── 6. Fechamento G8: o resto do espaço incondicional (B14.5/B14.6) é UNIMPLEMENTED, não `null` ─

    @Test
    void reservedUnconditionalSpaceIsUnimplementedNotSwallowedByCoprocessorDecoder() {
        // bits[11:8]=1001 (formas _hp, B14.6): mesmo padrão de VSEL mas size=half-precision.
        int vselHp = vselWord(0, false, 2, 0, 1) & ~(0xF << 8) | (0x9 << 8);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_V8_TEST_ARCH, vselHp).kind());

        // VRINT (bits[23:20]=1110-ish, B14.5): bit23=1, bits21:20 != 00 -> cai no "resto".
        int vrintLike = (0xF << 28) | (0xE << 24) | (1 << 23) | (0x3 << 20) | (0xA << 8);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_V8_TEST_ARCH, vrintLike).kind());
    }

    /// **Achado pós-fechamento (revisão de cobertura)**: o desenho original devolvia o `null` de
    /// `decodeVsel`/`decodeMaxNmMinNm` (registrador `D16`+ inválido sem `VFPV3_D32`) direto de
    /// `tryDecode`, sem converter para `UNIMPLEMENTED` — violação G8 (`claimsEncodingSpace` já diz
    /// `true` para este `raw`). Passava despercebido porque `ArmDecoder#decodeUnconditional`
    /// converte QUALQUER `null` de extensão em `UNIMPLEMENTED` no fallback final — então testar só
    /// via `decodeArm` (que passa por `ArmDecoder`) não pegava o bug; é preciso chamar
    /// `VfpDecoder#tryDecode` diretamente.
    @Test
    void vselDoubleWithInvalidD16RegisterIsExplicitlyUnimplementedNeverNull() {
        int word = vselWord(0, true, 16, 0, 1); // VSELEQ.F64 D16, D0, D1
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        assertEquals(true, decoder.claimsEncodingSpace(word));
        DecodedInstruction decoded = decoder.tryDecode(word, 0, Condition.AL);
        assertNotNull(decoded, "tryDecode devolveu null apesar de claimsEncodingSpace=true (G8)");
        assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
    }

    @Test
    void vmaxnmDoubleWithInvalidD16RegisterIsExplicitlyUnimplementedNeverNull() {
        int word = maxNmMinNmWord(false, true, 0, 0, 16); // VMAXNM.F64 D0, D0, D16
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        assertEquals(true, decoder.claimsEncodingSpace(word));
        DecodedInstruction decoded = decoder.tryDecode(word, 0, Condition.AL);
        assertNotNull(decoded, "tryDecode devolveu null apesar de claimsEncodingSpace=true (G8)");
        assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
    }

    /// Fecha os outros 2 operandos do `||` de `decodeVsel` (JaCoCo apontou: só `vd` inválido
    /// estava exercitado, `vn`/`vm` inválidos individualmente não).
    @Test
    void vselWithInvalidVnOrVmRegisterIsUnimplementedNeverNull() {
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        int vnInvalid = vselWord(0, true, 0, 16, 1); // VSELEQ.F64 D0, D16, D1
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vnInvalid).kind());
        int vmInvalid = vselWord(0, true, 0, 1, 16); // VSELEQ.F64 D0, D1, D16
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vmInvalid).kind());
    }

    /// Mesmo fechamento para `decodeMaxNmMinNm` (só `vm` inválido estava exercitado).
    @Test
    void vmaxnmWithInvalidVdOrVnRegisterIsUnimplementedNeverNull() {
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        int vdInvalid = maxNmMinNmWord(false, true, 16, 0, 1); // VMAXNM.F64 D16, D0, D1
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vdInvalid).kind());
        int vnInvalid = maxNmMinNmWord(false, true, 0, 16, 1); // VMAXNM.F64 D0, D16, D1
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vnInvalid).kind());
    }

    private static DecodedInstruction notNullDecode(VfpDecoder decoder, int word) {
        DecodedInstruction decoded = decoder.tryDecode(word, 0, Condition.AL);
        assertNotNull(decoded, "tryDecode devolveu null apesar de claimsEncodingSpace=true (G8)");
        return decoded;
    }

    /// Fecha o branch `false` de `claimsEncodingSpace` (`isUnconditionalVfpSpace(raw)==false`) —
    /// os outros testes desta suíte só passavam `raw` DENTRO do espaço incondicional.
    @Test
    void claimsEncodingSpaceFalseBranchFallsThroughToClaimsThisDecoder() {
        // VADD.F32 S2,S0,S1 (cond=AL, op1=0b011 bit6=0): `---- 1110 0.11 vn vd size .0.0 vm` —
        // espaço VFP CONDICIONAL comum (bits[31:28]=cond real, não 0xF), fora do gate de B14.4.
        int conditionalWord = (0xE << 28) | (0xE << 24);
        conditionalWord |= extOf(2, false) << 22;
        conditionalWord |= 0b11 << 20; // op1=0b011: bit23=0 (implícito), bit21=1, bit20=1.
        conditionalWord |= nibbleOf(0, false) << 16; // vn=0
        conditionalWord |= nibbleOf(2, false) << 12; // vd=2
        conditionalWord |= size(false) << 8;
        conditionalWord |= extOf(0, false) << 7; // vn ext
        conditionalWord |= extOf(1, false) << 5; // vm ext
        conditionalWord |= nibbleOf(1, false); // vm=1
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        assertEquals(false, isUnconditionalWordForTest(conditionalWord));
        assertEquals(true, decoder.claimsEncodingSpace(conditionalWord));
        DecodedInstruction decoded = decoder.tryDecode(conditionalWord, 0, Condition.AL);
        assertEquals(InstructionKind.VFP_ALU, decoded.kind());
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 0, 1, Condition.AL), liftSingleOp(decoded));
    }

    private static boolean isUnconditionalWordForTest(int raw) {
        return (raw >>> 28) == 0xF;
    }

    // ── 7. Caminho de execução de BLOCO (Kind-switch de `IrBlockExecutor#execute`), não só
    //        `executeOp` (fallback PER_OP) — os testes acima só exercitavam este último ──────────

    @Test
    void vselExecutesThroughPrimaryBlockDispatchNotOnlyExecuteOpFallback() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 5.0f);
        core.vfp().setSFloat(1, 9.0f);
        core.cpsr().setNzcv(false, false, false, false); // Z=0 -> EQ falso -> escolhe vm.
        IrBlock.Builder builder = IrBlock.builder(0);
        builder.add(new IrOp.VfpSelect(false, 2, 0, 1, Condition.EQ, Condition.AL));
        builder.add(new IrOp.Cycle(1));
        builder.add(new IrOp.Fetch(4, 4));
        IrBlock block = builder.endPc(4).sealed();
        new IrBlockExecutor(VFP_V8_TEST_ARCH).execute(block, core);
        assertEquals(9.0f, core.vfp().sFloat(2));
    }

    @Test
    void claimsEncodingSpaceIsTrueForTheWholeUnconditionalVfpRegionEvenWithoutFeature() {
        // G8: `claimsEncodingSpace` tem que ser `true` (recusa EXPLÍCITA), nunca deixar o espaço
        // livre para um `CoprocessorDecoder` genérico "engolir" por acidente.
        int word = vselWord(0, false, 2, 0, 1);
        assertEquals(true, new VfpDecoder(VFP_TEST_ARCH).claimsEncodingSpace(word));
        assertEquals(true, new VfpDecoder(VFP_V8_TEST_ARCH).claimsEncodingSpace(word));
    }
}
