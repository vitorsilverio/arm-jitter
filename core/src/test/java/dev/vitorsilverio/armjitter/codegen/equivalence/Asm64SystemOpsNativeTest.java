package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Aceite da task C12.10: os 8 `Kind` de sistema são aceitos por {@link Ir64NativePolicy}, o bloco
/// compila como bytecode (política padrão `WHOLE_BLOCK`, sem cair no interpretado) e o estado final
/// é idêntico ao de {@link InterpretedIr64CodeEmitter} (G1). As 4 ops que lançam exceção de guest
/// (`HVC`/`BRK`/`UNDEFINED`) ficam DENTRO do `try` do bloco — o teste "no meio" é o aceite central
/// (classe de bug da E7: exceção de guest escapando para o host).
class Asm64SystemOpsNativeTest {
    private final BlockEquivalenceHarness64 harness = new BlockEquivalenceHarness64();
    private final InterpretedIr64CodeEmitter interpreted = new InterpretedIr64CodeEmitter();

    private static Aarch64Core newEl1Core() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        return core;
    }

    private static Aarch64Core newMmuCore() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x2000));
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(0x1000L);
        Aarch64Core core = new Aarch64Core(mmu);
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        return core;
    }

    private static EquivalencePairFactory64 pair() {
        return () -> new EquivalencePair64(newEl1Core(), newEl1Core());
    }

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

    /// Compara com o interpretado e garante que o bloco foi para bytecode, não para o fallback.
    private void assertNativeAndEquivalent(Ir64Block block, EquivalencePairFactory64 pairFactory) {
        Asm64CodeEmitter asm = new Asm64CodeEmitter();
        assertTrue(asm.isNativeSupported(block), "bloco com op de sistema deve ser nativo");
        harness.assertEquivalent(interpreted, asm, block, pairFactory);
        assertEquals(1, asm.nativeBlockCount());
        assertEquals(0, asm.fallbackBlockCount());
    }

    @Test
    void allEightSystemKindsAreSupported() {
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.CURRENT_EL, 0)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.TLBI_ALL)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.ExceptionReturn()));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.PrivilegedCall(true)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.InterruptMask(true, 0b0010)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Breakpoint(0x1234)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.UndefinedInstructionTrap()));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1R, 0)));
    }

    @Test
    void systemRegisterReadAndWriteMatchInterpreter() {
        assertNativeAndEquivalent(blockOf(0x1000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0xBEEF, 0, true),
                new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.TPIDR_EL0, 0),
                new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.TPIDR_EL0, 1),
                new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.CURRENT_EL, 2)), pair());
    }

    @Test
    void systemInstructionMatchesInterpreter() {
        assertNativeAndEquivalent(blockOf(0x1100,
                new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.TLBI_ALL),
                new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.BARRIER)), pair());
    }

    @Test
    void interruptMaskMatchesInterpreter() {
        assertNativeAndEquivalent(blockOf(0x1200,
                new Ir64Op.InterruptMask(true, 0b0010),
                new Ir64Op.InterruptMask(false, 0b0010)), pair());
    }

    @Test
    void addressTranslateMatchesInterpreter() {
        // Sem descritor válido a tradução falha: PAR_EL1.F=1, sem exceção para o guest.
        assertNativeAndEquivalent(blockOf(0x1300,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x2000, 0, true),
                new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1R, 0)), () -> new EquivalencePair64(newMmuCore(), newMmuCore()));
    }

    /// `ERET` de EL1 para EL0: PC e PSTATE vêm de `ELR_EL1`/`SPSR_EL1` pelo mesmo caminho do interpretado.
    @Test
    void exceptionReturnMatchesInterpreter() {
        assertNativeAndEquivalent(blockOf(0x3000, new Ir64Op.ExceptionReturn()), () -> {
            Aarch64Core reference = newEl1Core();
            Aarch64Core candidate = newEl1Core();
            for (Aarch64Core core : new Aarch64Core[]{reference, candidate}) {
                core.exceptionState().setElr1(0x4444L);
                core.exceptionState().setSpsr1(0L);
            }
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void hypervisorAndSecureMonitorCallAreCapturedByBlockHandler() {
        assertNativeAndEquivalent(blockOf(0x6000, new Ir64Op.PrivilegedCall(true)), pair());
        assertNativeAndEquivalent(blockOf(0x6100, new Ir64Op.PrivilegedCall(false)), pair());
    }

    @Test
    void breakpointAndUndefinedTrapAreCapturedByBlockHandler() {
        assertNativeAndEquivalent(blockOf(0x6200, new Ir64Op.Breakpoint(0x1234)), pair());
        assertNativeAndEquivalent(blockOf(0x6300, new Ir64Op.UndefinedInstructionTrap()), pair());
    }

    /// Aceite central (E7): a op que lança está no MEIO — as anteriores executaram, as posteriores
    /// não, e os ciclos parciais batem com o interpretado.
    @Test
    void throwingOpInTheMiddleKeepsPartialStateIdentical() {
        for (Ir64Op thrower : new Ir64Op[]{
                new Ir64Op.Breakpoint(0x42),
                new Ir64Op.UndefinedInstructionTrap(),
                new Ir64Op.PrivilegedCall(true),
                new Ir64Op.PrivilegedCall(false)}) {
            assertNativeAndEquivalent(blockOf(0x7000,
                    new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1111, 0, true),
                    new Ir64Op.Alu64(Ir64AluOp.ADD, 2, 0, 1, true, true, false, false),
                    thrower,
                    new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x2222, 0, true)), pair());
        }
    }
}
