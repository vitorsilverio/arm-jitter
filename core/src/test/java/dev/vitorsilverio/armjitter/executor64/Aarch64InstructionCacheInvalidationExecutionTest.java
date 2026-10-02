package dev.vitorsilverio.armjitter.executor64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64InstructionCacheListener;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Semântica de `IC IALLU`/`IC IVAU` no executor: delegam no {@link Aarch64InstructionCacheListener}
/// do core (instalado pelo JIT); sem ouvinte (interpretador puro) são no-ops.
class Aarch64InstructionCacheInvalidationExecutionTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static final class Recorder implements Aarch64InstructionCacheListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void invalidateAll() {
            events.add("all");
        }

        @Override
        public void invalidatePhysicalPage(long physicalPage) {
            events.add("page:" + physicalPage);
        }
    }

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
    }

    @Test
    void invalidateAllReachesTheListener() {
        Aarch64Core core = newCore();
        Recorder recorder = new Recorder();
        core.setInstructionCacheListener(recorder);

        EXECUTOR.executeOp(core, new SystemOp64.SystemInstruction(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL));

        assertEquals(List.of("all"), recorder.events);
        assertTrue(core.instructionCacheListener() == recorder);
    }

    @Test
    void invalidateByVaPassesThePhysicalPageOfTheRegisterValue() {
        Aarch64Core core = newCore();
        Recorder recorder = new Recorder();
        core.setInstructionCacheListener(recorder);
        core.setX(7, 0x3_4567L);

        EXECUTOR.executeOp(core, new SystemOp64.SystemInstruction(
                Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_BY_VA, 7));

        assertEquals(List.of("page:" + 0x34), recorder.events, "sem MMU o VA é o próprio endereço físico");
    }

    @Test
    void withoutAListenerBothAreNoOps() {
        Aarch64Core core = newCore();
        core.setX(7, 0x40);

        EXECUTOR.executeOp(core, new SystemOp64.SystemInstruction(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL));
        EXECUTOR.executeOp(core, new SystemOp64.SystemInstruction(
                Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_BY_VA, 7));

        assertEquals(null, core.instructionCacheListener());
    }
}
