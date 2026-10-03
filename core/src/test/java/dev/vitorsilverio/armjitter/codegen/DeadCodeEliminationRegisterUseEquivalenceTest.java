package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.MveMoveOp;
import dev.vitorsilverio.armjitter.ir.NeonMoveOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.ir.opt.StandardIrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

/// Regressão da task E15.6b: blocos `escrita ALU; op; sobrescrita` em que a op lê o registrador
/// escrito pela ALU. Sem a op declarar a leitura em `regUse()`, a `DeadCodeEliminationPass` do
/// pipeline de produção (`StandardIrOptimizer.gba()`) apaga a escrita e o ASM diverge do
/// interpretador (G1), que executa o bloco sem otimizar.
class DeadCodeEliminationRegisterUseEquivalenceTest extends BlockEquivalenceTest {
    private static final int MEMORY_BYTES = 64;
    /// Endereço dos dados lidos pelo `VLD1`: fora do endereço `0`, para que perder a base apareça.
    private static final int DATA_ADDRESS = 0x20;
    /// `rm` de NEON load/store sem writeback.
    private static final int NEON_NO_WRITEBACK = 15;
    private static final int WORD_ESZ = 2;
    private static final int WORD_BITS = 32;
    /// `op2` de `SMLALxy` em {@link IntegerOp.DspMultiply}.
    private static final int SMLAL_XY = 2;
    private static final int BOTTOM_HALF = 0;
    private static final int SP_INDEX = 13;
    private static final int LR_INDEX = 14;
    /// `FPSCR.LTPSIZE` de elementos de 32 bits (tail-predication ativa).
    private static final int LTPSIZE_WORD = 2;
    /// Elementos de 32 bits restantes no `LR` com a tail-predication ativa: 2 das 4 lanes.
    private static final int REMAINING_ELEMENTS = 2;
    /// `LR` grande o bastante para não predicar nenhuma lane.
    private static final int NO_TAIL = 100;

    private static IntegerOp.Alu mov(int dst, int value) {
        return new IntegerOp.Alu(IrOpCode.MOV, dst, 0, -1, new IrOperand.Immediate(value), false, Condition.AL);
    }

    private static IrBlock block(IrOp... ops) {
        IrBlock.Builder builder = IrBlock.builder(0).endPc(ops.length * 4);
        for (IrOp op : ops) {
            builder.add(op);
        }
        return builder.sealed();
    }

    private void assertAsmMatchesInterpreted(ArmArchitecture architecture, IrBlock block, TestAddressSpace memory,
            Consumer<ArmCore> init) {
        CodeEmitter reference = new InterpretedCodeEmitter(architecture);
        CodeEmitter candidate = new AsmCodeEmitter(architecture, AsmFallbackPolicy.PER_OP, StandardIrOptimizer.gba());
        harness.assertEquivalent(reference, candidate, block, EquivalenceTestSupport.independentPair(memory, init));
    }

    @Test
    void crc32KeepsTheAluWriteOfItsDataRegister() {
        // MOV r0, #dado ; CRC32W r1, r1, r0 ; MOV r0, #0
        IrBlock block = block(mov(0, 0x5678),
                new IntegerOp.Crc32(1, 1, 0, WORD_BITS, false, Condition.AL),
                mov(0, 0));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV8A_32, block, new TestAddressSpace(MEMORY_BYTES),
                core -> core.setRegister(1, 0xFFFF_FFFF));
    }

    @Test
    void smlalxyKeepsTheAluWriteOfItsHighAccumulator() {
        // MOV r1, #RdHi ; SMLALBB r0, r1, r2, r3 — RdHi é lido E escrito
        IrBlock block = block(mov(1, 7),
                new IntegerOp.DspMultiply(1, 0, 2, 3, SMLAL_XY, BOTTOM_HALF, BOTTOM_HALF, Condition.AL));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV5TE, block, new TestAddressSpace(MEMORY_BYTES), core -> {
            core.setRegister(2, 3);
            core.setRegister(3, 5);
        });
    }

    @Test
    void neonLoadKeepsTheAluWriteOfItsBase() {
        // MOV r2, #DATA ; VLD1.32 {d0}, [r2] ; MOV r2, #0
        TestAddressSpace memory = new TestAddressSpace(MEMORY_BYTES);
        memory.put32(DATA_ADDRESS, 0xCAFE_BABE);
        memory.put32(DATA_ADDRESS + 4, 0x1234_5678);
        IrBlock block = block(mov(2, DATA_ADDRESS),
                new NeonMoveOp.LoadStoreMultiple(true, 0, 2, NEON_NO_WRITEBACK, WORD_ESZ, 1, 1, 1),
                mov(2, 0));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV7A_NEON, block, memory, core -> { });
    }

    @Test
    void mveVdupKeepsTheAluWriteOfItsScalar() {
        // MOV r1, #valor ; VDUP.32 q0, r1 ; MOV r1, #0
        IrBlock block = block(mov(1, 0x5A),
                new MveMoveOp.VectorDup(WORD_ESZ, 0, 1, Condition.AL),
                mov(1, 0));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV8_1M_MVE, block, new TestAddressSpace(MEMORY_BYTES),
                core -> core.setRegister(LR_INDEX, NO_TAIL));
    }

    @Test
    void mveTailPredicationKeepsTheAluWriteOfTheLoopCounter() {
        // MOV lr, #2 ; VDUP.32 q0, r1 (LTPSIZE=2: só 2 lanes ativas) ; MOV lr, #0
        IrBlock block = block(mov(LR_INDEX, REMAINING_ELEMENTS),
                new MveMoveOp.VectorDup(WORD_ESZ, 0, 1, Condition.AL),
                mov(LR_INDEX, 0));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV8_1M_MVE, block, new TestAddressSpace(MEMORY_BYTES), core -> {
            core.setRegister(1, 0x5A);
            core.setRegister(LR_INDEX, NO_TAIL);
            core.fpscr().setLtpsize(LTPSIZE_WORD);
        });
    }

    @Test
    void cpsModeChangeKeepsTheAluWriteOfTheBankedStackPointer() {
        // MOV sp, #a (SVC) ; CPS #IRQ ; MOV sp, #b (IRQ) ; CPS #SVC — o SP_svc tem que ser `a`
        IrBlock block = block(mov(SP_INDEX, 0x40),
                cps(CpuMode.IRQ),
                mov(SP_INDEX, 0x80),
                cps(CpuMode.SUPERVISOR));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV6K, block, new TestAddressSpace(MEMORY_BYTES),
                core -> core.setCpsr(CpuMode.SUPERVISOR.bits()));
    }

    @Test
    void moveTopKeepsTheAluWriteOfItsLowHalf() {
        // MOVW r0, #lo ; MOVT r0, #hi ; MOV r1, r0 — guarda o par regUse/regDef do MOVT: um `regDef`
        // sem o `regUse` correspondente mataria o MOVW.
        IrBlock block = block(mov(0, 0xBEEF),
                new IntegerOp.MoveTop(0, 0xDEAD, Condition.AL),
                new IntegerOp.Alu(IrOpCode.MOV, 1, 0, -1, new IrOperand.Register(0), false, Condition.AL),
                mov(0, 0));
        assertAsmMatchesInterpreted(ArmArchitecture.ARMV6T2, block, new TestAddressSpace(MEMORY_BYTES), core -> { });
    }

    private static SystemOp.ChangeProcessorState cps(CpuMode mode) {
        return new SystemOp.ChangeProcessorState(true, mode.bits(), false, false, false, false, false, Condition.AL);
    }
}
