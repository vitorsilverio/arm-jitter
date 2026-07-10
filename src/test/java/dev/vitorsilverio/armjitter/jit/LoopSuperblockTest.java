package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.equivalence.CpuSnapshot;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Task C0.3: o loop-superbloco deve ser INDISTINGUÍVEL do chain loop, chamada a
/// chamada (invariante S1) — validado em lockstep manual ON×OFF com runtimes
/// síncronos (`jvmArmThumb`), mais as saídas por IRQ e a invalidação por SMC.
class LoopSuperblockTest {
    private static final int BLOCK_A = 0x0000_0000;
    private static final int BLOCK_B = 0x0000_0100;
    private static final int BLOCK_C = 0x0000_0200;
    private static final int BLOCK_D = 0x0000_0300;
    private static final int MOV_R0_1 = 0xE3A00001;
    private static final int ADD_R1_R1_1 = 0xE2811001;
    /// `b <+0x100>` a partir do offset 0x4 de cada bloco: (0x100 - 0xC) / 4 = 61.
    private static final int B_FORWARD_100 = 0xEA00003D;
    /// `b 0x0` a partir de 0x104: (0 - 0x10C) / 4 = -67.
    private static final int B_0X104_TO_0 = 0xEAFFFFBD;
    /// `b 0x0` a partir de 0x304: (0 - 0x30C) / 4 = -195.
    private static final int B_0X304_TO_0 = 0xEAFFFF3D;
    private static final int CHAIN_BUDGET = 40;

    /// Memória gravável (para o teste de SMC) com o programa pedido.
    private static final class RamSpace implements AddressSpace {
        private final int[] words = new int[0x400 / 4];

        @Override public int read8(int address) { return (read32(address & ~3) >>> ((address & 3) * 8)) & 0xFF; }
        @Override public int read16(int address) { return (read32(address & ~3) >>> ((address & 2) * 8)) & 0xFFFF; }
        @Override public int read32(int address) { return words[(address & 0x3FF) / 4]; }
        @Override public void write8(int address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(int address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(int address, int value) { words[(address & 0x3FF) / 4] = value; }
        @Override public boolean providesAccessCycles() { return false; }
    }

    private static RamSpace pingPong() {
        RamSpace memory = new RamSpace();
        memory.write32(BLOCK_A, MOV_R0_1);
        memory.write32(BLOCK_A + 4, B_FORWARD_100);
        memory.write32(BLOCK_B, ADD_R1_R1_1);
        memory.write32(BLOCK_B + 4, B_0X104_TO_0);
        return memory;
    }

    private static RamSpace fourBlockCycle() {
        RamSpace memory = new RamSpace();
        int[] blocks = {BLOCK_A, BLOCK_B, BLOCK_C, BLOCK_D};
        for (int block : blocks) {
            memory.write32(block, block == BLOCK_B ? ADD_R1_R1_1 : MOV_R0_1);
            memory.write32(block + 4, block == BLOCK_D ? B_0X304_TO_0 : B_FORWARD_100);
        }
        return memory;
    }

    @SuppressWarnings("deprecation") // jvmArmThumb: factory ASM síncrono (lockstep determinístico)
    private static JitRuntime asmRuntime(int budget) {
        JitRuntime runtime = JitRuntimeFactory.jvmArmThumb(64, 1);
        runtime.setChainCycleBudget(budget);
        return runtime;
    }

    /// Lockstep manual ON×OFF: aquece os dois lados igualmente, constrói o superbloco
    /// só no candidato e compara snapshot + ciclos por chamada dali em diante.
    private void assertOnOffLockstep(RamSpace program, int budget, int[] members, int calls) {
        JitRuntime with = asmRuntime(budget);
        JitRuntime without = asmRuntime(budget);
        ArmCore coreWith = new ArmCore(copyOf(program), SwiDispatcher.empty());
        ArmCore coreWithout = new ArmCore(copyOf(program), SwiDispatcher.empty());
        coreWith.setProgramCounter(members[0]);
        coreWithout.setProgramCounter(members[0]);

        for (int warm = 0; warm < 8; warm++) {
            step(with, coreWith, without, coreWithout, "warmup " + warm);
        }
        assertTrue(with.buildSuperblockNow(InstructionSet.ARM, members), "superbloco deve construir");
        assertTrue(with.superblockInstalled(members[0], InstructionSet.ARM));

        for (int call = 0; call < calls; call++) {
            step(with, coreWith, without, coreWithout, "lockstep " + call);
        }
    }

    private static void step(JitRuntime with, ArmCore coreWith,
                             JitRuntime without, ArmCore coreWithout, String label) {
        int cyclesWith = with.execute(coreWith.programCounter(), coreWith);
        int cyclesWithout = without.execute(coreWithout.programCounter(), coreWithout);
        assertEquals(cyclesWithout, cyclesWith, label + ": ciclos internos");
        CpuSnapshot.capture(coreWithout).assertEqualTo(CpuSnapshot.capture(coreWith), label);
    }

    private static RamSpace copyOf(RamSpace source) {
        RamSpace copy = new RamSpace();
        System.arraycopy(source.words, 0, copy.words, 0, source.words.length);
        return copy;
    }

    @Test
    void twoMemberSuperblockLockstepsWithChainLoop() {
        assertOnOffLockstep(pingPong(), CHAIN_BUDGET, new int[]{BLOCK_A, BLOCK_B}, 50);
    }

    @Test
    void fourMemberSuperblockLockstepsWithChainLoop() {
        assertOnOffLockstep(fourBlockCycle(), CHAIN_BUDGET,
                new int[]{BLOCK_A, BLOCK_B, BLOCK_C, BLOCK_D}, 50);
    }

    @Test
    void zeroBudgetExecutesHeadOnceLikeChainLoop() {
        // S3: com budget 0 o primeiro membro ainda executa — dos dois lados.
        assertOnOffLockstep(pingPong(), 0, new int[]{BLOCK_A, BLOCK_B}, 20);
    }

    @Test
    void interruptLineExitsAfterCurrentMember() {
        JitRuntime with = asmRuntime(CHAIN_BUDGET);
        JitRuntime without = asmRuntime(CHAIN_BUDGET);
        ArmCore coreWith = new ArmCore(pingPong(), SwiDispatcher.empty());
        ArmCore coreWithout = new ArmCore(pingPong(), SwiDispatcher.empty());
        for (int warm = 0; warm < 8; warm++) {
            with.execute(coreWith.programCounter(), coreWith);
            without.execute(coreWithout.programCounter(), coreWithout);
        }
        assertTrue(with.buildSuperblockNow(InstructionSet.ARM, BLOCK_A, BLOCK_B));
        // Realinha os dois lados no head antes da comparação com IRQ pendente.
        while (coreWith.programCounter() != BLOCK_A) {
            with.execute(coreWith.programCounter(), coreWith);
        }
        while (coreWithout.programCounter() != BLOCK_A) {
            without.execute(coreWithout.programCounter(), coreWithout);
        }
        coreWith.setInterruptLine(true);
        coreWithout.setInterruptLine(true);
        int cyclesWith = with.execute(BLOCK_A, coreWith);
        int cyclesWithout = without.execute(BLOCK_A, coreWithout);
        assertEquals(cyclesWithout, cyclesWith, "com IRQ pendente: um membro e sai (S1)");
        assertEquals(coreWithout.programCounter(), coreWith.programCounter());
    }

    @Test
    void smcWriteInvalidatesSuperblock() {
        JitRuntime runtime = asmRuntime(CHAIN_BUDGET);
        RamSpace ram = pingPong();
        AddressSpace bus = new InvalidationAwareAddressSpace(ram, runtime);
        ArmCore core = new ArmCore(bus, SwiDispatcher.empty());
        core.setProgramCounter(BLOCK_A);
        for (int warm = 0; warm < 8; warm++) {
            runtime.execute(core.programCounter(), core);
        }
        assertTrue(runtime.buildSuperblockNow(InstructionSet.ARM, BLOCK_A, BLOCK_B));
        runtime.execute(core.programCounter(), core);
        assertEquals(1, core.register(0), "MOV r0,#1 original");

        // Escrita no código do bloco A (dentro da envoltória) DEVE derrubar o superbloco.
        bus.write32(BLOCK_A, 0xE3A00002); // MOV r0, #2
        assertFalse(runtime.superblockInstalled(BLOCK_A, InstructionSet.ARM),
                "superbloco deve ser invalidado pela escrita");
        for (int i = 0; i < 8; i++) {
            runtime.execute(core.programCounter(), core);
        }
        assertEquals(2, core.register(0), "código novo deve valer (nada de superbloco velho)");
    }

    @Test
    void interpretedEmitterSkipsBuildGracefully() {
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(64, 1);
        runtime.setChainCycleBudget(CHAIN_BUDGET);
        ArmCore core = new ArmCore(pingPong(), SwiDispatcher.empty());
        core.setProgramCounter(BLOCK_A);
        for (int warm = 0; warm < 8; warm++) {
            runtime.execute(core.programCounter(), core);
        }
        assertFalse(runtime.buildSuperblockNow(InstructionSet.ARM, BLOCK_A, BLOCK_B),
                "emissor interpretado não compõe superbloco");
        assertEquals(1, runtime.superblockSkips);
    }
}
