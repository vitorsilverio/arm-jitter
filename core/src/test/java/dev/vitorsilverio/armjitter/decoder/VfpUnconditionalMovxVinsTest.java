package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B14.6 — `VMOVX`/`VINS` (espaço VFP incondicional, `bits[31:28]=0xF`, `vfp-uncond.decode`),
/// primeira fatia do épico `FEAT_FP16` de 32 bits. Oráculo: QEMU `target/arm/tcg/translate-vfp.c`
/// (`trans_VMOVX`/`trans_VINS`). Ver `VfpDecoder#decodeMovxVins` e a task
/// `b14.6-fp16-32bit-vmovx-vins.md`. Mesma base de arquitetura de teste de
/// `VfpUnconditionalRoundConvertTest` (B14.5).
class VfpUnconditionalMovxVinsTest {
    private static final ArmArchitecture VFP_TEST_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K_THUMB2, "ARMv7-TestVfpUncondMovxVins", ArmFeature.VFPV2);
    private static final ArmArchitecture VFP_TEST_ARCH = VFP_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(VFP_TEST_FEATURES));

    private static final ArmArchitecture VFP_V8_TEST_FEATURES =
            ArmArchitecture.extending(VFP_TEST_FEATURES, "ARMv8-TestVfpUncondMovxVinsNoFp16", ArmFeature.ARMV8_FP);
    private static final ArmArchitecture VFP_V8_TEST_ARCH = VFP_V8_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_V8_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(VFP_V8_TEST_FEATURES));

    private static final ArmArchitecture FP16_TEST_FEATURES = ArmArchitecture.extending(VFP_V8_TEST_FEATURES,
            "ARMv8-TestVfpUncondMovxVinsFp16", ArmFeature.FP16_ARITHMETIC);
    private static final ArmArchitecture FP16_TEST_ARCH = FP16_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(FP16_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(FP16_TEST_FEATURES));

    private static List<DecoderExtension> thumb32Extensions(ArmArchitecture features) {
        return List.of(new Thumb2VfpDecoder(features), new Thumb2CoprocessorDecoder());
    }

    // ── Encoder manual (vfp-uncond.decode) ────────────────────────────────────────────────────

    private static int nibbleOf(int combined) {
        return combined >>> 1;
    }

    private static int extOf(int combined) {
        return combined & 1;
    }

    /// `VMOVX`/`VINS`: `1111 1110 1.11 0000 vd(4) 1010 op:1 1 . 0 vm(4)` — `op`=1 é `VINS`, `op`=0
    /// é `VMOVX`; sempre `S` (`vd`/`vm` já são o índice de registrador `S0`-`S31`).
    private static int movxVinsWord(boolean insert, int vd, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd) << 22;
        word |= nibbleOf(vd) << 12;
        word |= 0xA << 8; // size=1010 (SIZE_SINGLE) — a marca "single", não "1001".
        word |= (insert ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= extOf(vm) << 5;
        word |= nibbleOf(vm);
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
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), FP16_TEST_ARCH);
    }

    // ── 1. Decodificação/lift corretos sob FP16_ARITHMETIC (A32 e T32) ────────────────────────

    @Test
    void vmovxDecodesToVfpMoveHalfLaneWithInsertFalse() {
        DecodedInstruction decoded = decodeArm(FP16_TEST_ARCH, movxVinsWord(false, 2, 1));
        assertEquals(InstructionKind.VFP_MOVE_HALF_LANE, decoded.kind());
        assertEquals(new IrOp.VfpMoveHalfLane(false, 2, 1, Condition.AL), liftSingleOp(decoded));
    }

    @Test
    void vinsDecodesToVfpMoveHalfLaneWithInsertTrue() {
        DecodedInstruction decoded = decodeArm(FP16_TEST_ARCH, movxVinsWord(true, 5, 3));
        assertEquals(InstructionKind.VFP_MOVE_HALF_LANE, decoded.kind());
        assertEquals(new IrOp.VfpMoveHalfLane(true, 5, 3, Condition.AL), liftSingleOp(decoded));
    }

    @Test
    void vmovxVinsRoundTripArmAndThumb2() {
        int word = movxVinsWord(false, 7, 4);
        DecodedInstruction armDecoded = decodeArm(FP16_TEST_ARCH, word);
        DecodedInstruction thumbDecoded = decodeThumb32(FP16_TEST_ARCH, word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    // ── 2. Não-misdecode: sem FP16_ARITHMETIC (mesmo com ARMV8_FP) e vizinhos intactos ─────────

    @Test
    void vmovxVinsUnimplementedWithoutFp16Arithmetic() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_V8_TEST_ARCH, movxVinsWord(false, 2, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_V8_TEST_ARCH, movxVinsWord(true, 2, 1)).kind());
    }

    @Test
    void vmovxVinsUnimplementedWithoutArmv8FpAtAll() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, movxVinsWord(false, 2, 1)).kind());
    }

    @Test
    void claimsEncodingSpaceIsTrueForMovxVinsRegionEvenWithoutFeature() {
        int word = movxVinsWord(false, 2, 1);
        assertEquals(true, new VfpDecoder(VFP_TEST_ARCH).claimsEncodingSpace(word));
        assertEquals(true, new VfpDecoder(FP16_TEST_ARCH).claimsEncodingSpace(word));
    }

    /// `VMOV_half` (B22.2) mora no MESMO `bits[11:8]=1001`, espaço DIFERENTE de `VMOVX`/`VINS`
    /// (`bits[11:8]=1010`, Armadilha 3/4 da task B14.6). B22.10: `FEAT_FP16` (`FP16_ARITHMETIC`) IMPLICA a
    /// transferência de 16 bits (como `VCVTB`/`VCVTT`), então com a feature ele decodifica; sem nenhuma das
    /// duas (`VFP_TEST_ARCH`) continua `UNIMPLEMENTED`.
    @Test
    void vmovHalfEncodingFollowsFp16Arithmetic() {
        // VMOV Sn, Rt (l=0): `---- 1110 000 0 vn(4) rt(4) 1001 . 001 0000`.
        int vmovHalf = (0xE << 24) | (0 << 20) | (3 << 16) | (0 << 12) | (0x9 << 8) | (1 << 4);
        assertEquals(InstructionKind.VFP_CORE_TRANSFER, decodeArm(FP16_TEST_ARCH, vmovHalf).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, vmovHalf).kind());
    }

    // ── 3. Execução: troca CRUA de metades, sem tocar FPSCR ─────────────────────────────────────

    @Test
    void vmovxMovesHighHalfToLowHalfZeroingRest() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0x1234_5678);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpMoveHalfLane(false, 1, 0, Condition.AL), 0);
        assertEquals(0x0000_1234, core.vfp().s(1));
    }

    @Test
    void vinsInsertsLowHalfIntoHighHalfPreservingLowHalf() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0x1234_5678); // vm: metade baixa 0x5678 vai para vd[31:16].
        core.vfp().setS(1, 0x0000_ABCD); // vd ANTES: metade baixa 0xABCD deve ser preservada.
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpMoveHalfLane(true, 1, 0, Condition.AL), 0);
        assertEquals(0x5678_ABCD, core.vfp().s(1));
    }

    @Test
    void vmovxVinsDoNotModifyFpscr() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0xFFFF_0000);
        core.fpscr().setValue(0x2468);
        int before = core.fpscr().value();
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpMoveHalfLane(false, 1, 0, Condition.AL), 0);
        assertEquals(before, core.fpscr().value());
    }

    @Test
    void vmovxVinsWithFalseBlockConditionSkipsEntirely() {
        ArmCore core = newCore();
        core.vfp().setS(1, -1); // sentinela: não deve mudar.
        core.vfp().setS(0, 0x1234_5678);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> NE falso.
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpMoveHalfLane(false, 1, 0, Condition.NE), 0);
        assertEquals(-1, core.vfp().s(1));
    }

    // ── 4. Caminho de execução de BLOCO (Kind-switch de `IrBlockExecutor#execute`) ─────────────

    @Test
    void vmovxExecutesThroughPrimaryBlockDispatchNotOnlyExecuteOpFallback() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0xABCD_1234);
        IrBlock.Builder builder = IrBlock.builder(0);
        builder.add(new IrOp.VfpMoveHalfLane(false, 1, 0, Condition.AL));
        builder.add(new IrOp.Cycle(1));
        builder.add(new IrOp.Fetch(4, 4));
        IrBlock block = builder.endPc(4).sealed();
        new IrBlockExecutor(FP16_TEST_ARCH).execute(block, core);
        assertEquals(0x0000_ABCD, core.vfp().s(1));
    }

    /// Prova DIRETA de que `AsmNativePolicy` recusa `VfpMoveHalfLane` ("Não inclui" da task —
    /// decode + interpretado apenas, mesmo padrão de `VfpSelect`/`VfpRound`/`VfpConvertRounded`).
    @Test
    void asmNativePolicyRefusesVfpMoveHalfLane() {
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpMoveHalfLane(false, 0, 1, Condition.AL)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpMoveHalfLane(true, 0, 1, Condition.AL)));
    }

    // ── 5. Fechamento G8: combinações reservadas nunca viram null/misdecode ────────────────────

    @Test
    void reservedBitCombinationsAreUnimplementedNeverMisdecoded() {
        VfpDecoder decoder = new VfpDecoder(FP16_TEST_ARCH);
        int bit6Clear = movxVinsWord(false, 2, 1) & ~(1 << 6);
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, bit6Clear).kind());
        int bits1816Set = movxVinsWord(false, 2, 1) | (1 << 16);
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, bits1816Set).kind());
    }

    /// Prova de que `VFP_MOVE_HALF_LANE` NÃO termina o bloco JIT (`StandardIrBlockLifter#isTerminal`
    /// — `VMOVX`/`VINS` compartilham a mesma linha `-> true`/`-> false` de outros `Kind` VFP já
    /// existentes, então a cobertura de linha do JaCoCo nessa linha não prova nada especificamente
    /// sobre `VFP_MOVE_HALF_LANE`: só o LIFTER DE BLOCO de verdade — decodificando duas instruções
    /// em sequência via `ArmDecoder`, não `liftSingleOp` — mostra que a segunda instrução continua
    /// sendo elevada no MESMO bloco).
    @Test
    void vmovxDoesNotTerminateBlockSecondInstructionStillLifted() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, movxVinsWord(false, 1, 0));
        memory.put32(4, movxVinsWord(true, 2, 1));
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(FP16_TEST_ARCH), new StandardIrBuilder());
        IrBlock block = lifter.lift(memory, 0, 2, 0);
        assertEquals(8, block.endPc(), "bloco deveria conter as DUAS instruções, não terminar na primeira");
        long moveHalfLaneOps = block.operations().stream().filter(op -> op instanceof IrOp.VfpMoveHalfLane).count();
        assertEquals(2, moveHalfLaneOps);
    }

    private static DecodedInstruction notNullDecode(VfpDecoder decoder, int word) {
        DecodedInstruction decoded = decoder.tryDecode(word, 0, Condition.AL);
        assertNotNull(decoded, "tryDecode devolveu null apesar de claimsEncodingSpace=true (G8)");
        return decoded;
    }
}
