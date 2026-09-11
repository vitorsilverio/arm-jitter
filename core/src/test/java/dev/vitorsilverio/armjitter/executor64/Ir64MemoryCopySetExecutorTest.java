package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.16 — semântica de `SETP`/`SETM`/`SETE` e `CPYFP`/`CPYFM`/`CPYFE`/`CPYP`/`CPYM`/`CPYE`
/// (interpretador = oráculo, G1). Ver javadoc de {@link Ir64Op.MemorySet}/{@link Ir64Op.MemoryCopy}
/// para a simplificação registrada na task: a fase que encontra o contador (`Rn`) diferente de zero
/// faz o trabalho INTEIRO; as fases seguintes, com o contador já zerado, são NOP funcional — os
/// testes abaixo rodam as 3 fases em sequência, exatamente como software real emite.
class Ir64MemoryCopySetExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int DEST_REG = 0;
    private static final int COUNT_REG = 1;
    private static final int VALUE_OR_SOURCE_REG = 2;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void runSetSequence(Aarch64Core core, long dest, long count, long fillByte) {
        core.setX(DEST_REG, dest);
        core.setX(COUNT_REG, count);
        core.setX(VALUE_OR_SOURCE_REG, fillByte);
        for (Ir64Op.Ir64MopsPhase phase : Ir64Op.Ir64MopsPhase.values()) {
            EXECUTOR.executeOp(core, new Ir64Op.MemorySet(phase, DEST_REG, COUNT_REG, VALUE_OR_SOURCE_REG));
        }
    }

    private static void runCopySequence(Aarch64Core core, boolean forwardOnly, long dest, long source, long count) {
        core.setX(DEST_REG, dest);
        core.setX(VALUE_OR_SOURCE_REG, source);
        core.setX(COUNT_REG, count);
        for (Ir64Op.Ir64MopsPhase phase : Ir64Op.Ir64MopsPhase.values()) {
            EXECUTOR.executeOp(core,
                    new Ir64Op.MemoryCopy(phase, forwardOnly, DEST_REG, VALUE_OR_SOURCE_REG, COUNT_REG));
        }
    }

    @Test
    void setpSetmSeteSequenceFillsRegionAndZeroesCounter() {
        Aarch64Core core = newCore();
        runSetSequence(core, 16L, 10L, 0xAB);

        for (int i = 0; i < 10; i++) {
            assertEquals(0xAB, core.memory().read8(16L + i));
        }
        assertEquals(0L, core.x(COUNT_REG));
        assertEquals(16L + 10L, core.x(DEST_REG));
    }

    @Test
    void setSequenceOnlyFillsTheByteWidthValue() {
        // Xs=0x1AB: só o byte baixo (0xAB) preenche — o resto de Xs é ignorado.
        Aarch64Core core = newCore();
        runSetSequence(core, 32L, 4L, 0x1AB);
        for (int i = 0; i < 4; i++) {
            assertEquals(0xAB, core.memory().read8(32L + i));
        }
    }

    @Test
    void memsetIsNoOpWhenPhaseCalledAloneWithZeroCounter() {
        Aarch64Core core = newCore();
        core.setX(DEST_REG, 64L);
        core.setX(COUNT_REG, 0L);
        core.setX(VALUE_OR_SOURCE_REG, 0xFF);
        EXECUTOR.executeOp(core, new Ir64Op.MemorySet(Ir64Op.Ir64MopsPhase.MAIN, DEST_REG, COUNT_REG, VALUE_OR_SOURCE_REG));
        assertEquals(0, core.memory().read8(64L)); // TestAddressSpace nasce zerada — nada escrito
        assertEquals(64L, core.x(DEST_REG)); // registrador não avança num phase NOP
    }

    @Test
    void cpyfSequenceCopiesForwardWithoutOverlap() {
        Aarch64Core core = newCore();
        long src = 8L;
        long dst = 64L;
        for (int i = 0; i < 12; i++) {
            core.memory().write8(src + i, i + 1);
        }
        runCopySequence(core, true, dst, src, 12L);

        for (int i = 0; i < 12; i++) {
            assertEquals(i + 1, core.memory().read8(dst + i));
        }
        assertEquals(0L, core.x(COUNT_REG));
        assertEquals(dst + 12, core.x(DEST_REG));
        assertEquals(src + 12, core.x(VALUE_OR_SOURCE_REG));
    }

    @Test
    void cpySequenceHandlesOverlappingRegionsLikeMemmove() {
        // dst > src, regiões se sobrepõem — memmove precisa copiar de trás para frente, senão a
        // escrita destrói bytes de origem ainda não lidos.
        Aarch64Core core = newCore();
        long src = 10L;
        long dst = 14L; // sobrepõe src+[0..11] com dst+[0..11] (overlap de 6 bytes)
        int length = 12;
        int[] expected = new int[length];
        for (int i = 0; i < length; i++) {
            int value = i + 1;
            core.memory().write8(src + i, value);
            expected[i] = value;
        }
        runCopySequence(core, false, dst, src, length);

        for (int i = 0; i < length; i++) {
            assertEquals(expected[i], core.memory().read8(dst + i),
                    "byte " + i + " do memmove deve refletir o valor ORIGINAL de src, não um já sobrescrito");
        }
        assertEquals(0L, core.x(COUNT_REG));
    }

    @Test
    void cpySequenceCopiesForwardWhenNoOverlap() {
        Aarch64Core core = newCore();
        long src = 100L;
        long dst = 10L; // dst < src, sem sobreposição possível na direção "forward"
        for (int i = 0; i < 8; i++) {
            core.memory().write8(src + i, 0x40 + i);
        }
        runCopySequence(core, false, dst, src, 8L);

        for (int i = 0; i < 8; i++) {
            assertEquals(0x40 + i, core.memory().read8(dst + i));
        }
    }
}
