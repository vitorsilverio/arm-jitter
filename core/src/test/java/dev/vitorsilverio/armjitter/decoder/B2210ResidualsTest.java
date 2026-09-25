package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B22.10 — resíduos de decode reais: `SB` (`FEAT_SB`, A32 + T32), `VMOV_half` sob `FEAT_FP16` e as formas
/// NEON de 8/16 bits de `VMOV_to_gp`/`VMOV_from_gp` (`VMOV.{S8,U8,S16,U16,8,16}`). Oráculo: ARM DDI 0406C
/// A8.8.343/A8.8.344 e QEMU `translate-vfp.c` (`trans_VMOV_to_gp`/`trans_VMOV_from_gp`).
class B2210ResidualsTest {
    private static final int SB_A32 = 0xF57F_F070;
    private static final int SB_T32 = 0xF3BF_8F70;
    private static final int COND_AL = 0xE;

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

    private static IrOp lift(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    /// `VMOV_to_gp`/`VMOV_from_gp` NEON: `cond 1110 U b22 b21 L Vn Rt 1011 N b6 b5 1 0000`.
    private static int vmovLane(boolean toArm, boolean unsigned, boolean byteForm, int index, int d, int rt) {
        int word = (COND_AL << 28) | (0xE << 24) | ((unsigned ? 1 : 0) << 23) | ((toArm ? 1 : 0) << 20);
        word |= ((d & 0xF) << 16) | (rt << 12) | (0xB << 8) | (((d >> 4) & 1) << 7) | (1 << 4);
        if (byteForm) {
            word |= (1 << 22) | (((index >> 2) & 1) << 21) | (((index >> 1) & 1) << 6) | ((index & 1) << 5);
        } else {
            word |= (((index >> 1) & 1) << 21) | ((index & 1) << 6) | (1 << 5);
        }
        return word;
    }

    /// `VMOV_half`: `cond 1110 000 l Vn Rt 1001 N 001 0000`.
    private static int vmovHalf(boolean toArm, int rt, int s) {
        return (COND_AL << 28) | (0xE << 24) | ((toArm ? 1 : 0) << 20) | ((s >> 1) << 16) | (rt << 12)
                | (0x9 << 8) | ((s & 1) << 7) | (1 << 4);
    }

    private static void run(ArmArchitecture arch, DecodedInstruction decoded, ArmCore core) {
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
        new IrBlockExecutor(arch).executeOp(core, lift(decoded), 0);
    }

    private static ArmCore core(ArmArchitecture arch) {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), arch);
    }

    // ── SB ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void sbDecodesAsBarrierWhereDeclaredInBothInstructionSets() {
        for (ArmArchitecture arch : List.of(ArmArchitecture.ARMV8A_32, ArmArchitecture.ARMV8_6A_32,
                ArmArchitecture.ARMV8R_32)) {
            assertEquals(InstructionKind.MEMORY_BARRIER, decodeArm(arch, SB_A32).kind(), arch.name() + " A32");
            assertEquals(InstructionKind.MEMORY_BARRIER, decodeThumb32(arch, SB_T32).kind(), arch.name() + " T32");
        }
    }

    @Test
    void sbIsRefusedWhereFeatureIsAbsent() {
        for (ArmArchitecture arch : List.of(ArmArchitecture.ARMV7A, ArmArchitecture.ARMV7A_NEON,
                ArmArchitecture.ARMV7R, ArmArchitecture.ARMV8_1M_MVE)) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeThumb32(arch, SB_T32).kind(), arch.name() + " T32");
        }
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, SB_A32).kind());
    }

    @Test
    void sbHasNoObservableEffect() {
        ArmCore core = core(ArmArchitecture.ARMV8A_32);
        core.setRegister(3, 0x1234_5678);
        run(ArmArchitecture.ARMV8A_32, decodeThumb32(ArmArchitecture.ARMV8A_32, SB_T32), core);
        assertEquals(0x1234_5678, core.register(3));
    }

    // ── VMOV_half sob FEAT_FP16 ────────────────────────────────────────────────────────────────

    @Test
    void vmovHalfDecodesUnderFp16PresetsAndIsRefusedInArmv7() {
        for (ArmArchitecture arch : List.of(ArmArchitecture.ARMV8A_32, ArmArchitecture.ARMV8_6A_32)) {
            IrOp op = lift(decodeArm(arch, vmovHalf(true, 2, 5)));
            assertEquals(new IrOp.VfpCoreTransfer(true, 2, 5, true, Condition.AL), op, arch.name());
        }
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(ArmArchitecture.ARMV7A_NEON, vmovHalf(true, 2, 5)).kind());
    }

    @Test
    void vmovHalfCopiesSixteenBitsPreservingUpperHalfOnWrite() {
        ArmArchitecture arch = ArmArchitecture.ARMV8A_32;
        ArmCore core = core(arch);
        core.vfp().setS(5, 0xDEAD_BEEF);
        run(arch, decodeArm(arch, vmovHalf(true, 2, 5)), core);
        assertEquals(0x0000_BEEF, core.register(2));
        core.setRegister(3, 0x9999_1234);
        core.vfp().setS(6, 0xCAFE_0000);
        run(arch, decodeArm(arch, vmovHalf(false, 3, 6)), core);
        assertEquals(0xCAFE_1234, core.vfp().s(6));
    }

    // ── VMOV.{S8,U8,S16,U16,8,16} (NEON) ───────────────────────────────────────────────────────

    @Test
    void neonLaneTransfersNeedAdvancedSimd() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(ArmArchitecture.ARMV8A_32, vmovLane(true, false, true, 5, 20, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(ArmArchitecture.ARMV7A, vmovLane(true, false, false, 2, 3, 1)).kind());
    }

    @Test
    void rt15IsRefused() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(ArmArchitecture.ARMV7A_NEON, vmovLane(true, true, true, 0, 0, 15)).kind());
    }

    @Test
    void byteReadSignAndZeroExtendEveryLaneOfHighRegister() {
        ArmArchitecture arch = ArmArchitecture.ARMV7A_NEON;
        for (int lane = 0; lane < 8; lane++) {
            ArmCore core = core(arch);
            // Cada byte = 0x90 + lane (bit 7 ligado → o sinal é observável), D20 (registrador alto).
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= (long) (0x90 + i) << (i * 8);
            }
            core.vfp().setD(20, bits);
            run(arch, decodeArm(arch, vmovLane(true, true, true, lane, 20, 1)), core);
            assertEquals(0x90 + lane, core.register(1), "U8 lane " + lane);
            run(arch, decodeArm(arch, vmovLane(true, false, true, lane, 20, 2)), core);
            assertEquals((int) (byte) (0x90 + lane), core.register(2), "S8 lane " + lane);
        }
    }

    @Test
    void halfwordReadSignAndZeroExtend() {
        ArmArchitecture arch = ArmArchitecture.ARMV7A_NEON;
        ArmCore core = core(arch);
        core.vfp().setD(3, 0x0004_8003_0002_0001L);
        run(arch, decodeArm(arch, vmovLane(true, true, false, 2, 3, 1)), core);
        assertEquals(0x8003, core.register(1));
        run(arch, decodeArm(arch, vmovLane(true, false, false, 2, 3, 2)), core);
        assertEquals(0xFFFF_8003, core.register(2));
        run(arch, decodeArm(arch, vmovLane(true, false, false, 3, 3, 4)), core);
        assertEquals(4, core.register(4));
    }

    @Test
    void byteAndHalfwordWritesTouchOnlyTheirLane() {
        ArmArchitecture arch = ArmArchitecture.ARMV7A_NEON;
        ArmCore core = core(arch);
        core.vfp().setD(31, 0x1111_1111_1111_1111L);
        core.setRegister(7, 0xABCD_EF5A);
        run(arch, decodeArm(arch, vmovLane(false, false, true, 6, 31, 7)), core);
        assertEquals(0x115A_1111_1111_1111L, core.vfp().d(31));
        run(arch, decodeArm(arch, vmovLane(false, false, false, 1, 31, 7)), core);
        assertEquals(0x115A_1111_EF5A_1111L, core.vfp().d(31));
    }
}
