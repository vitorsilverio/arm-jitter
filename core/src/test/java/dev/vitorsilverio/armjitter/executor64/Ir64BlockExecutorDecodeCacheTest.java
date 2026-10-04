package dev.vitorsilverio.armjitter.executor64;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// E15.10b: o cache de decode do {@link Ir64BlockExecutor#step} tem que ser invisível — o `step`
/// executa sempre o que a memória contém AGORA no `pc`, mesmo sem manutenção de cache de instruções.
class Ir64BlockExecutorDecodeCacheTest {
    private static final int MOVZ_X1_1 = 0xd2800021;
    private static final int MOVZ_X1_2 = 0xd2800041;
    private static final int ADR_X0_HERE = 0x10000000;
    private static final int INSTRUCTION_BYTES = 4;
    /// Primeiro endereço que cai na mesma entrada do cache que o endereço 0.
    private static final long COLLIDING_PC = (long) Ir64BlockExecutor.DECODE_CACHE_ENTRIES * INSTRUCTION_BYTES;
    /// Laço do `StepBench` da E15.10 (DP-imediato, DP-registrador e um `B` de volta ao início).
    private static final int[] LOOP = {
            0xd2824681, 0x91000442, 0xd37df043, 0x92401c64, 0x8b010085, 0xca0200a6,
            0xd10010c7, 0xaa0300e9, 0xd3442d2a, 0xeb08005f, 0x17fffff6
    };
    private static final int LOOP_ROUNDS = 10;
    private static final int GENERAL_REGISTERS = 31;

    private static Aarch64Core newCore(int memorySizeBytes) {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(memorySizeBytes)));
    }

    @Test
    void overwrittenInstructionIsDecodedAgainWithoutCacheMaintenance() {
        Aarch64Core core = newCore(0x100);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        core.memory().write32(0, MOVZ_X1_1);
        executor.step(core);
        assertEquals(1L, core.x(1));

        core.memory().write32(0, MOVZ_X1_2);
        core.setProgramCounter(0);
        executor.step(core);

        assertEquals(2L, core.x(1), "a palavra nova no mesmo pc tem que ser executada");
    }

    @Test
    void sameWordAtCollidingPcIsDecodedForItsOwnPc() {
        Aarch64Core core = newCore((int) COLLIDING_PC + 0x100);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        core.memory().write32(0, ADR_X0_HERE);
        core.memory().write32(COLLIDING_PC, ADR_X0_HERE);

        executor.step(core);
        assertEquals(0L, core.x(0));
        core.setProgramCounter(COLLIDING_PC);
        executor.step(core);
        assertEquals(COLLIDING_PC, core.x(0), "ADR depende do pc: a entrada do pc 0 não serve");
        core.setProgramCounter(0);
        executor.step(core);
        assertEquals(0L, core.x(0), "a colisão substituiu a entrada; o pc 0 decodifica de novo");
    }

    @Test
    void cachedStepMatchesAColdExecutorEveryStep() {
        Aarch64Core cached = newCore(0x100);
        Aarch64Core cold = newCore(0x100);
        for (int i = 0; i < LOOP.length; i++) {
            cached.memory().write32((long) i * INSTRUCTION_BYTES, LOOP[i]);
            cold.memory().write32((long) i * INSTRUCTION_BYTES, LOOP[i]);
        }
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        for (int step = 0; step < LOOP_ROUNDS * LOOP.length; step++) {
            int cachedCycles = executor.step(cached);
            int coldCycles = new Ir64BlockExecutor().step(cold);
            assertEquals(coldCycles, cachedCycles);
            assertEquals(cold.pc(), cached.pc(), "pc no passo " + step);
            assertEquals(cold.pstate().nzcv(), cached.pstate().nzcv(), "NZCV no passo " + step);
            for (int r = 0; r < GENERAL_REGISTERS; r++) {
                assertEquals(cold.x(r), cached.x(r), "x" + r + " no passo " + step);
            }
        }
        assertEquals(cold.cycles(), cached.cycles());
    }
}
