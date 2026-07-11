package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePairFactory;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A2+A3 — TruffleCodeEmitter vs InterpretedCodeEmitter. Desde a A3, {@link TruffleBlockRootNode}
/// delega TODO `IrOp` (não só ALU simples) a {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor#executeOp},
/// então o backend Truffle compila nativamente qualquer bloco — cobre ALU com ShiftedRegister e
/// condição != AL, memória (Load/Store), branches, LDM/STM/Push/Pop e multiplicação, espelhando
/// as categorias que a suíte exaustiva do `AsmCodeEmitter` cobre para o backend ASM.
class TruffleCodeEmitterEquivalenceTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    private final CodeEmitter referenceEmitter = new InterpretedCodeEmitter();

    private void assertBlockEquivalent(CodeEmitter candidate, IrBlock block, EquivalencePairFactory pairFactory) {
        harness.assertEquivalent(referenceEmitter, candidate, block, pairFactory);
    }

    private static EquivalencePairFactory pairFactory(int r0, int r1) {
        return () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(256), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(256), SwiDispatcher.empty());
            reference.setRegister(0, r0);
            reference.setRegister(1, r1);
            candidate.setRegister(0, r0);
            candidate.setRegister(1, r1);
            return new EquivalencePair(reference, candidate);
        };
    }

    private static IrBlock liftArm(int... words) {
        ByteArrayAddressSpace memory = new ByteArrayAddressSpace(words.length * 4);
        for (int i = 0; i < words.length; i++) {
            memory.write32(i * 4, words[i]);
        }
        return new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, words.length);
    }

    @Test
    void pureAluBlockCompilesNativelyAndMatchesInterpreter() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
        IrBlock block = new IrBlock(0, 16, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.Alu(IrOpCode.SUB, 3, 2, -1, new IrOperand.Immediate(1), false, Condition.AL),
                new IrOp.Alu(IrOpCode.CMP, 3, 3, -1, new IrOperand.Register(0, -1), true, Condition.AL),
                new IrOp.Alu(IrOpCode.MOV, 4, -1, -1, new IrOperand.Immediate(0xCAFE), false, Condition.AL),
                new IrOp.Alu(IrOpCode.AND, 5, 4, -1, new IrOperand.Immediate(0xFF), false, Condition.AL),
                new IrOp.Cycle(3),
                new IrOp.Fetch(0, 4)));

        assertBlockEquivalent(emitter, block, pairFactory(10, 3));
        assertEquals(1L, emitter.nativeBlockCount(), "bloco ALU puro deve compilar nativamente");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void aluWithShiftedRegisterCompilesNativelyAndMatchesInterpreter() {
        // Task A3: ShiftedRegister como src2 (antes forçava fallback) agora também é nativo,
        // já que executeOp delega ao mesmo IrAluExecutor do interpretador.
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.Alu(IrOpCode.MOV, 3, -1, -1,
                        new IrOperand.ShiftedRegister(0, ShiftType.LSL, 2, -1, -1, -1, false, false),
                        false, Condition.AL),
                new IrOp.Cycle(2),
                new IrOp.Fetch(0, 4)));

        assertBlockEquivalent(emitter, block, pairFactory(10, 3));
        assertEquals(1L, emitter.nativeBlockCount(), "ShiftedRegister deve compilar nativamente desde a A3");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void emptyBlockIsEquivalentAndCompilesNatively() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
        IrBlock block = new IrBlock(0, 0, List.of());

        assertBlockEquivalent(emitter, block, pairFactory(10, 3));
        assertEquals(1L, emitter.nativeBlockCount(), "bloco vazio nao tem ops nao suportadas");
    }

    @Test
    void conditionalAluAcrossAllCodesAndFlagsMatchesInterpreter() {
        // Task A3: condição != AL — executeOp já checa cpsr().evalCond internamente, então o
        // guard funciona sem nenhuma emissão especial (ao contrário do backend ASM, que precisa
        // gerar o guard em bytecode).
        for (int cond = 0; cond <= 13; cond++) {
            int instruction = (cond << 28) | 0x02821001; // ADD<cond> r1, r2, #1
            IrBlock block = liftArm(instruction);
            TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                assertBlockEquivalent(emitter, block, () -> {
                    ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
                    ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
                    for (ArmCore core : List.of(reference, candidate)) {
                        core.setRegister(2, 0x11111111);
                        core.cpsr().setNzcv((flags & 8) != 0, (flags & 4) != 0, (flags & 2) != 0, (flags & 1) != 0);
                    }
                    return new EquivalencePair(reference, candidate);
                });
            }
            assertEquals(0L, emitter.fallbackBlockCount(), "ADD<cond> must compile natively: cond=" + cond);
        }
    }

    @Test
    void loadAndStoreCompileNativelyAndMatchInterpreter() {
        // STR r0, [r1] ; LDR r2, [r1]
        IrBlock block = liftArm(0xE581_0000, 0xE591_2000);
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);

        assertBlockEquivalent(emitter, block, () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            for (ArmCore core : List.of(reference, candidate)) {
                core.setRegister(0, 0xDEAD_BEEF);
                core.setRegister(1, 0x20);
            }
            return new EquivalencePair(reference, candidate);
        });
        assertEquals(1L, emitter.nativeBlockCount());
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void branchCompilesNativelyAndMatchesInterpreter() {
        // B #0 -> target = PC(8) + 0 = 8
        IrBlock block = liftArm(0xEA00_0000);
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);

        assertBlockEquivalent(emitter, block, pairFactory(0, 0));
        assertEquals(1L, emitter.nativeBlockCount());
    }

    @Test
    void multiplyAndLdmStmCompileNativelyAndMatchInterpreter() {
        // MUL r0, r1, r2 ; STMIA r3!, {r0, r1} ; LDMIA r3!, {r4, r5}
        IrBlock block = liftArm(0xE000_0291, 0xE8A3_0003, 0xE8B3_0030);
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);

        assertBlockEquivalent(emitter, block, () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            for (ArmCore core : List.of(reference, candidate)) {
                core.setRegister(1, 7);
                core.setRegister(2, 6);
                core.setRegister(3, 0x10);
            }
            return new EquivalencePair(reference, candidate);
        });
        assertEquals(1L, emitter.nativeBlockCount());
        assertEquals(0L, emitter.fallbackBlockCount());
    }
}
