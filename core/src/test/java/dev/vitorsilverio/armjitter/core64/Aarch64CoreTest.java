package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Aarch64CoreTest {
    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)));
    }

    @Test
    void xzrAlwaysReadsZeroRegardlessOfPriorState() {
        Aarch64Core core = newCore();
        core.setX(31, 0x1234); // deve ser descartado
        assertEquals(0L, core.x(31));
    }

    @Test
    void wideWriteZerosUpperHalf() {
        // Armadilha documentada em b6-aarch64.md: "W (32-bit) escreve zerando os 32 altos do X
        // — SEMPRE". Escrevemos primeiro o registrador X inteiro e depois uma escrita W deve
        // apagar a metade alta, não preservá-la.
        Aarch64Core core = newCore();
        core.setX(0, 0xFFFF_FFFF_FFFF_FFFFL);

        core.setXForWidth(0, 0x0000_0000_ABCD_1234L, false);

        assertEquals(0x0000_0000_ABCD_1234L, core.x(0),
                "escrita W deve zerar os 32 bits altos, nunca preservá-los");
    }

    @Test
    void wideWritePreservesFullValue() {
        Aarch64Core core = newCore();
        core.setXForWidth(0, 0x1122_3344_5566_7788L, true);
        assertEquals(0x1122_3344_5566_7788L, core.x(0));
    }

    @Test
    void xForWidthNarrowMasksHighBits() {
        Aarch64Core core = newCore();
        core.setX(3, 0xFFFF_FFFF_0000_0001L);
        assertEquals(1L, core.xForWidth(3, false));
        assertEquals(0xFFFF_FFFF_0000_0001L, core.xForWidth(3, true));
    }

    @Test
    void registerIndexOutOfRangeThrows() {
        Aarch64Core core = newCore();
        assertThrows(IndexOutOfBoundsException.class, () -> core.x(32));
        assertThrows(IndexOutOfBoundsException.class, () -> core.x(-1));
    }

    @Test
    void spIsIndependentFromGeneralRegisters() {
        Aarch64Core core = newCore();
        core.setSp(0x7FFF_0000L);
        core.setX(0, 0);
        assertEquals(0x7FFF_0000L, core.sp());
    }
}
