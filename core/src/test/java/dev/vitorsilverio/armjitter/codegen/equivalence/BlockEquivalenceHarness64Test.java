package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Prova o Aceite do PR1 da task B6.4: {@link InterpretedIr64CodeEmitter} (oráculo) e
/// {@link Asm64CodeEmitter} (backend nativo) produzem o MESMO {@link Aarch64CpuSnapshot} e os
/// MESMOS ciclos internos para todo o conjunto de ops coberto pelo PR1 (`Alu64`/`MoveWide`/
/// `PcRelative`/`Branch64`/`CompareBranch64` — reta + desvios da B6.1).
class BlockEquivalenceHarness64Test {
    private final BlockEquivalenceHarness64 harness = new BlockEquivalenceHarness64();
    private final InterpretedIr64CodeEmitter interpreted = new InterpretedIr64CodeEmitter();
    private final Asm64CodeEmitter asm = new Asm64CodeEmitter();

    private static EquivalencePairFactory64 pair() {
        return () -> new EquivalencePair64(newCore(), newCore());
    }

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
    }

    /// Empacota `ops` num bloco com `Cycle`/`Fetch` incondicionais antes de cada op real —
    /// mesma disciplina do lifter real (G4).
    private static Ir64Block blockOf(long startPc, Ir64Op... ops) {
        Ir64Block.Builder builder = Ir64Block.builder(startPc);
        long pc = startPc;
        for (Ir64Op op : ops) {
            builder.add(new Ir64Op.Fetch(pc, 4));
            builder.add(new Ir64Op.Cycle(1));
            builder.add(op);
            pc += 4;
        }
        builder.endPc(pc);
        return builder.sealed();
    }

    @Test
    void everyOpInBlockIsNativelySupported() {
        Ir64Block block = blockOf(0,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 5, true, false, false, false));
        assertTrue(Ir64NativePolicy.supports(block));
    }

    @Test
    void aluAddImmediateWithFlags() {
        Ir64Block block = blockOf(0x1000,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 4, 5, 0x123, true, true, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void aluSubNarrowSetsFlagsAndZeroExtends() {
        Ir64Block block = blockOf(0x2000,
                new Ir64Op.Alu64(Ir64AluOp.SUB, 2, 3, 1, false, true, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void aluLogicalNeverTouchesCarryOverflow() {
        Ir64Block block = blockOf(0x3000,
                new Ir64Op.Alu64(Ir64AluOp.AND, 0, 1, 0xFF, true, true, false, false),
                new Ir64Op.Alu64(Ir64AluOp.ORR, 1, 2, 0xF0, true, false, false, false),
                new Ir64Op.Alu64(Ir64AluOp.EOR, 2, 3, 0x0F, true, false, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void aluDestinationStackPointer() {
        Ir64Block block = blockOf(0x4000,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 31, 31, 0x10, true, false, true, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void moveWideMovzMovnMovk() {
        Ir64Block block = blockOf(0x5000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1234, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 0, 0x5678, 16, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 1, 0x0F0F, 0, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void pcRelativeAdrAndAdrp() {
        Ir64Block block = blockOf(0x6000,
                new Ir64Op.PcRelative(0, 0x6000L, 0x40L, false),
                new Ir64Op.PcRelative(1, 0x6004L, 0x1000L, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void branchUnconditionalWithLink() {
        Ir64Block block = blockOf(0x7000,
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x7000L, 0x7100L, -1, true, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void branchConditionalTakenAndNotTaken() {
        // EQ tomado (candidato/referência começam com Z=1 via um SUBS que zera antes do branch).
        Ir64Block taken = blockOf(0x8000,
                new Ir64Op.Alu64(Ir64AluOp.SUB, 2, 0, 0, true, true, false, false), // X0-0 == 0 -> Z=1
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x8004L, 0x8100L, -1, false, Ir64Condition.EQ));
        harness.assertEquivalent(interpreted, asm, taken, pair());

        Ir64Block notTaken = blockOf(0x9000,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 2, 0, 1, true, true, false, false), // X0+1 != 0 -> Z=0
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x9004L, 0x9100L, -1, false, Ir64Condition.EQ));
        harness.assertEquivalent(interpreted, asm, notTaken, pair());
    }

    @Test
    void branchRegisterFormsBrBlrRet() {
        // BR: registrador puro, sem link.
        Ir64Block br = blockOf(0xA000,
                new Ir64Op.Branch64(Ir64BranchForm.REGISTER, 0xA000L, -1L, 2, false, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, br, () -> {
            Aarch64Core reference = newCore();
            reference.setX(2, 0xA200L);
            Aarch64Core candidate = newCore();
            candidate.setX(2, 0xA200L);
            return new EquivalencePair64(reference, candidate);
        });

        // BLR: grava o link register.
        Ir64Block blr = blockOf(0xB000,
                new Ir64Op.Branch64(Ir64BranchForm.REGISTER, 0xB000L, -1L, 3, true, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, blr, () -> {
            Aarch64Core reference = newCore();
            reference.setX(3, 0xB200L);
            Aarch64Core candidate = newCore();
            candidate.setX(3, 0xB200L);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void compareBranchCbzCbnzTakenAndNotTaken() {
        Ir64Block cbzTaken = blockOf(0xC000,
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 5, true, -1, false, 0xC100L));
        harness.assertEquivalent(interpreted, asm, cbzTaken, pair()); // X5 = 0 por padrão -> CBZ toma

        Ir64Block cbnzTaken = blockOf(0xD000,
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 6, true, -1, true, 0xD100L));
        harness.assertEquivalent(interpreted, asm, cbnzTaken, () -> {
            Aarch64Core reference = newCore();
            reference.setX(6, 1L);
            Aarch64Core candidate = newCore();
            candidate.setX(6, 1L);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void compareBranchTbzTbnz() {
        Ir64Block tbnzTaken = blockOf(0xE000,
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.TBZ_TBNZ, 7, true, 3, true, 0xE100L));
        harness.assertEquivalent(interpreted, asm, tbnzTaken, () -> {
            Aarch64Core reference = newCore();
            reference.setX(7, 1L << 3);
            Aarch64Core candidate = newCore();
            candidate.setX(7, 1L << 3);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void multiInstructionStraightLineBlockThenBranch() {
        Ir64Block block = blockOf(0xF000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x0001, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 0, 0x0002, 16, true),
                new Ir64Op.Alu64(Ir64AluOp.ADD, 1, 0, 0x10, true, false, false, false),
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0xF00CL, 0xF100L, -1, false, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void listOfPr1KindsAllNativelySupported() {
        List<Ir64Op> ops = List.of(
                new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 1, true, false, false, false),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true),
                new Ir64Op.PcRelative(0, 0, 0, false),
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0, 4, -1, false, Ir64Condition.AL),
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 0, true, -1, false, 4));
        for (Ir64Op op : ops) {
            assertTrue(Ir64NativePolicy.supports(op), op.getClass().getSimpleName());
        }
    }
}
