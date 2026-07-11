package dev.vitorsilverio.armjitter.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Task C3: map/unmap, espelhamento (mirrors), alinhamento inválido, fallback de handler e o
/// `WriteListener` disparando só em páginas marcadas com {@link PagedAddressSpace#setContainsCode}.
class PagedAddressSpaceTest {
    private static final int PAGE_SHIFT = 8; // páginas de 256 bytes, fáceis de testar
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;

    /// Barramento de open-bus trivial: sempre devolve/aceita um valor fixo, só para os testes
    /// distinguirem "caiu no open bus" de "caiu num handler real".
    private static final class FixedAddressSpace implements AddressSpace {
        private final int fixedValue;
        final List<Integer> writes = new ArrayList<>();

        FixedAddressSpace(int fixedValue) {
            this.fixedValue = fixedValue;
        }

        @Override public int read8(int address) { return fixedValue & 0xFF; }
        @Override public int read16(int address) { return fixedValue & 0xFFFF; }
        @Override public int read32(int address) { return fixedValue; }
        @Override public void write8(int address, int value) { writes.add(address); }
        @Override public void write16(int address, int value) { writes.add(address); }
        @Override public void write32(int address, int value) { writes.add(address); }
    }

    private static PagedAddressSpace newSpace(AddressSpace openBus, PagedAddressSpace.WriteListener listener) {
        return new PagedAddressSpace(PAGE_SHIFT, openBus, listener);
    }

    // ── map/unmap ────────────────────────────────────────────────────────────────

    @Test
    void mapRamReadsAndWritesGoDirectlyToThePagedBacking() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0xEE), null);
        byte[] backing = new byte[PAGE_SIZE * 2];
        backing[0] = 0x12;
        backing[PAGE_SIZE] = 0x34;
        space.mapRam(0x1000, backing);

        assertEquals(0x12, space.read8(0x1000));
        assertEquals(0x34, space.read8(0x1000 + PAGE_SIZE));

        space.write32(0x1004, 0xCAFEBABE);
        assertEquals(0xCAFEBABE, space.read32(0x1004));
    }

    @Test
    void mapRamCopiesInsteadOfAliasingTheOriginalBackingArray() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), null);
        byte[] backing = new byte[PAGE_SIZE];
        space.mapRam(0x2000, backing);

        space.write8(0x2000, 0x7F);
        assertEquals(0, backing[0], "o array original não deve ser alterado por escritas na paged space");
        assertEquals(0x7F, space.read8(0x2000));
    }

    @Test
    void unmapFallsBackToOpenBus() {
        FixedAddressSpace openBus = new FixedAddressSpace(0x5A);
        PagedAddressSpace space = newSpace(openBus, null);
        space.mapRam(0x3000, new byte[PAGE_SIZE]);
        assertEquals(0, space.read8(0x3000));

        space.unmap(0x3000, PAGE_SIZE);
        assertEquals(0x5A, space.read8(0x3000));
    }

    // ── handler / open bus ───────────────────────────────────────────────────────

    @Test
    void mapHandlerDelegatesReadsAndWrites() {
        FixedAddressSpace handler = new FixedAddressSpace(0x77);
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0x00), null);
        space.mapHandler(0x4000, PAGE_SIZE, handler);

        assertEquals(0x77, space.read8(0x4000));
        space.write16(0x4010, 0x1234);
        assertEquals(1, handler.writes.size());
        assertEquals(0x4010, handler.writes.get(0));
    }

    @Test
    void unmappedAddressFallsBackToOpenBus() {
        FixedAddressSpace openBus = new FixedAddressSpace(0x99);
        PagedAddressSpace space = newSpace(openBus, null);

        assertEquals(0x99, space.read32(0x9999_0000));
        space.write8(0x9999_0000, 1);
        assertEquals(1, openBus.writes.size());
    }

    // ── espelhamento (mirrors) ───────────────────────────────────────────────────

    @Test
    void mapMirrorSharesTheSameBackingArraySoWritesAreVisibleThroughEitherWindow() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), null);
        byte[] backing = new byte[PAGE_SIZE * 2];
        space.mapRam(0x0200_0000, backing);
        space.mapMirror(0x0300_0000, 0x0200_0000, backing.length);

        space.write32(0x0200_0000, 0x11223344);
        assertEquals(0x11223344, space.read32(0x0300_0000), "escrita na janela primaria deve aparecer no espelho");

        space.write32(0x0300_0004, 0x55667788);
        assertEquals(0x55667788, space.read32(0x0200_0004), "escrita no espelho deve aparecer na janela primaria");
    }

    // ── alinhamento inválido ─────────────────────────────────────────────────────

    @Test
    void mapRamRejectsUnalignedBaseWithAClearMessage() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> space.mapRam(0x1001, new byte[PAGE_SIZE]));
        assertTrue(failure.getMessage().contains("alinhado"), failure.getMessage());
    }

    @Test
    void mapRamRejectsBackingLengthNotMultipleOfPageSize() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), null);
        assertThrows(IllegalArgumentException.class, () -> space.mapRam(0x1000, new byte[PAGE_SIZE + 1]));
    }

    @Test
    void mapHandlerRejectsUnalignedSize() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), null);
        assertThrows(IllegalArgumentException.class,
                () -> space.mapHandler(0x1000, PAGE_SIZE - 1, new FixedAddressSpace(0)));
    }

    @Test
    void constructorRejectsUnallocatablePageShift() {
        assertThrows(IllegalArgumentException.class, () -> new PagedAddressSpace(1, new FixedAddressSpace(0)));
    }

    // ── WriteListener por página ─────────────────────────────────────────────────

    @Test
    void writeListenerFiresOnlyOnPagesMarkedAsContainingCode() {
        List<Integer> notified = new ArrayList<>();
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), notified::add);
        space.mapRam(0x1000, new byte[PAGE_SIZE * 2]);

        space.write8(0x1000, 1); // página 0 (0x1000..0x10FF): sem código -> sem notificação
        assertTrue(notified.isEmpty());

        space.setContainsCode(0x1000 + PAGE_SIZE, true); // marca só a segunda página
        space.write8(0x1000, 2); // ainda a primeira página -> continua sem notificação
        assertTrue(notified.isEmpty());

        space.write8(0x1000 + PAGE_SIZE + 4, 3);
        assertEquals(List.of(0x1000 + PAGE_SIZE + 4), notified);

        space.setContainsCode(0x1000 + PAGE_SIZE, false);
        space.write8(0x1000 + PAGE_SIZE, 4);
        assertEquals(1, notified.size(), "desmarcar a página deve parar as notificações");
    }

    @Test
    void writeListenerAlsoFiresForHandlerPagesAndCrossPageWrites() {
        List<Integer> notified = new ArrayList<>();
        FixedAddressSpace handler = new FixedAddressSpace(0);
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), notified::add);
        space.mapHandler(0x5000, PAGE_SIZE, handler);
        space.setContainsCode(0x5000, true);

        space.write32(0x5000, 0xAABBCCDD);
        assertEquals(List.of(0x5000), notified);
    }

    @Test
    void providesAccessCyclesReflectsWhetherAnyWaitstateWasSet() {
        PagedAddressSpace space = newSpace(new FixedAddressSpace(0), null);
        assertFalse(space.providesAccessCycles());

        space.setWaitstates(0x1000, PAGE_SIZE, 2);
        assertTrue(space.providesAccessCycles());
        assertEquals(2, space.accessCycles(0x1000, 4, MemoryAccessType.DATA_READ));
        assertEquals(0, space.accessCycles(0x2000, 4, MemoryAccessType.DATA_READ));
    }
}
