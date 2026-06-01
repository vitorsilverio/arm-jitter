package dev.vitorsilverio.armjitter.swi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwiDispatcherTest {
    @Test
    void dispatchesRegisteredHandler() {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.register(0x08, state -> state.withR0(42));

        CpuState result = dispatcher.dispatch(0x08, new CpuState(9, 0, 0, 0, 0, 0, 0, 0));

        assertEquals(42, result.r0());
    }

    @Test
    void usesFallbackWhenNoDedicatedHandlerExists() {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.fallback(state -> state.withPc(0x18));

        CpuState result = dispatcher.dispatch(0xff, new CpuState(0, 0, 0, 0, 0, 0, 0, 0));

        assertEquals(0x18, result.pc());
    }

    @Test
    void fallbackCanInspectSwiNumber() {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.fallbackWithNumber((swi, state) -> state.withR0(swi));

        CpuState result = dispatcher.dispatch(0xab, new CpuState(0, 0, 0, 0, 0, 0, 0, 0));

        assertEquals(0xab, result.r0());
    }
}
