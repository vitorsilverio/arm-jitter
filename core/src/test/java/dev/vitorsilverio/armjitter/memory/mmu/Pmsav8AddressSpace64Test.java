package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B20.8: checagem por região de {@link Pmsav8AddressSpace64}, semântica confirmada no ARM DDI
/// 0600A.d (lido via `curl`, não parafraseado) — ver Javadoc da classe, em especial o achado que
/// DIFERE do precedente PMSAv8-32 ({@link Pmsav8AddressSpaceTest}): sobreposição de regiões é
/// `Translation fault`, não `Permission fault`.
class Pmsav8AddressSpace64Test {
    private static final int PRLAR_ENABLE = 0b1;
    /// `PRBAR_EL1`: `AP=0b01` (RW EL1+EL0), `XN=0` — bits\[3:2\], DIFERENTE de PMSAv8-32
    /// (bits\[2:1\] lá).
    private static final long PRBAR_FULL_ACCESS = 0b01L << 2;
    /// `PRBAR_EL1`: `AP=0b00` (RW só EL1 — nega acesso EL0).
    private static final long PRBAR_PRIVILEGED_ONLY = 0b00L << 2;
    /// `PRBAR_EL1`: `AP=0b11` (RO EL1+EL0).
    private static final long PRBAR_READ_ONLY = 0b11L << 2;
    private static final long PRBAR_XN_BIT = 0b10L;

    private static Pmsav8SystemRegisters64 newBus(int regionCount) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Pmsav8SystemRegisters64 bus = new Pmsav8SystemRegisters64(core, regionCount);
        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, 1L); // M=1, MPU habilitada
        return bus;
    }

    private static void program(Pmsav8SystemRegisters64 bus, int region, long base, long prbarFlags, boolean enabled) {
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, region);
        bus.write(Aarch64SystemRegisterId.PRBAR_EL1, base | prbarFlags);
        bus.write(Aarch64SystemRegisterId.PRLAR_EL1, (base + 0x3F) | (enabled ? PRLAR_ENABLE : 0));
    }

    // ── limite inclusivo (granule de 64 bytes) ──────────────────────────────────────

    @Test
    void limitIsInclusiveUpToLastByteOfSixtyFourByteGranule() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_FULL_ACCESS, true);

        assertEquals(0, space.read8(0x13F), "último byte do bloco de 64: dentro do limite");
        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class, () -> space.read8(0x140));
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0, fault.faultStatus());
    }

    // ── sobreposição: Table C1-4 do DDI 0600A.d — "Multiple" é Translation fault ────

    @Test
    void overlappingRegionsFaultWithTranslationFaultNotPermission() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(2);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_FULL_ACCESS, true);
        program(bus, 1, 0x100, PRBAR_FULL_ACCESS, true);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class, () -> space.read8(0x100));
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0, fault.faultStatus(),
                "achado real: DIFERE do PMSAv8-32 (lá é PERMISSION) — Table C1-4 do DDI 0600A.d");
    }

    @Test
    void nonOverlappingRegionsAtDifferentBasesBothWork() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(2);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_FULL_ACCESS, true);
        program(bus, 1, 0x200, PRBAR_FULL_ACCESS, true);

        assertEquals(0, space.read8(0x100));
        assertEquals(0, space.read8(0x200));
    }

    @Test
    void disabledRegionIsIgnoredEntirely() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(2);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x000, PRBAR_FULL_ACCESS, true);
        program(bus, 1, 0x100, PRBAR_FULL_ACCESS, false);

        assertEquals(0, space.read8(0x100), "cai na região 0 (mapa de fundo explícito), nunca conflita com a 1 desabilitada");
    }

    // ── "no match" (sem região de fundo): Translation fault ─────────────────────────

    @Test
    void noRegionMatchesAndBackgroundDisabledFaultsWithTranslationStatus() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class, () -> space.read32(0x800));
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0, fault.faultStatus());
        assertEquals(0x800L, fault.virtualAddress());
        assertEquals(MemoryAccessType.DATA_READ, fault.accessType());
    }

    // ── região de fundo (decisão de implementação desta task — ver Javadoc da classe) ─

    @Test
    void privilegedAccessWithBackgroundRegionEnabledAllowsReadWrite() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, (1L << 17) | 1L); // M=1, BR=1
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        space.setPrivileged(true);

        assertEquals(0, space.read32(0x1000));
    }

    @Test
    void userModeNeverUsesBackgroundRegionEvenWhenEnabled() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, (1L << 17) | 1L); // M=1, BR=1
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        space.setPrivileged(false);

        assertThrows(MemoryTranslationException64.class, () -> space.read32(0x800));
    }

    @Test
    void backgroundRegionNeverAllowsExecute() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        physical.put32(0x1000, 0xD503_201F); // NOP A64
        Pmsav8SystemRegisters64 bus = newBus(1);
        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, (1L << 17) | 1L); // M=1, BR=1
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        space.setPrivileged(true);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> space.accessCycles(0x1000, 4, MemoryAccessType.INSTRUCTION_FETCH));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L0, fault.faultStatus());
        assertEquals(0xD503_201F, space.read32(0x1000), "leitura de DADOS continua permitida pelo mapa de fundo");
    }

    // ── permissão (AP nos bits[3:2], XN no bit[1] — diferente da posição de 32 bits) ─

    @Test
    void apZeroIsPrivilegedReadWriteOnlyNoUserAccess() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_PRIVILEGED_ONLY, true);

        space.setPrivileged(true);
        space.write32(0x100, 0x1234);
        assertEquals(0x1234, space.read32(0x100));

        space.setPrivileged(false);
        assertThrows(MemoryTranslationException64.class, () -> space.read8(0x100));
    }

    @Test
    void apThreeIsReadOnlyForBothPrivilegeLevels() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_READ_ONLY, true);

        space.setPrivileged(true);
        assertEquals(0, space.read8(0x100));
        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class, () -> space.write32(0x100, 1));
        assertEquals(FaultStatus64.PERMISSION_FAULT_L0, fault.faultStatus());
        assertEquals(MemoryAccessType.DATA_WRITE, fault.accessType());

        space.setPrivileged(false);
        assertEquals(0, space.read8(0x100), "AP=0b11 dá leitura para EL0 também");
        assertThrows(MemoryTranslationException64.class, () -> space.write8(0x100, 1));
    }

    @Test
    void executeNeverDeniesFetchButNotDataReadAtSameAddress() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        physical.put32(0x100, 0xD503_201F);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_FULL_ACCESS | PRBAR_XN_BIT, true);
        space.setPrivileged(true);

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> space.accessCycles(0x100, 4, MemoryAccessType.INSTRUCTION_FETCH));
        assertEquals(MemoryAccessType.INSTRUCTION_FETCH, fault.accessType());
        assertEquals(0xD503_201F, space.read32(0x100), "leitura de DADOS não é afetada por XN");
    }

    // ── SCTLR_EL1.M=0: bypass total ──────────────────────────────────────────────────

    @Test
    void mpuDisabledIsTotalBypassEvenWithRestrictiveRegionsProgrammed() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Pmsav8SystemRegisters64 bus = new Pmsav8SystemRegisters64(core, 1); // SCTLR_EL1.M=0 (reset)
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_PRIVILEGED_ONLY, true);
        space.setPrivileged(false);

        space.write32(0x100, 0xABCD);
        assertEquals(0xABCD, space.read32(0x100));
    }

    // ── read16/write8/write16 + delegação de accessCycles/providesAccessCycles/notifyWrite ──────
    // (achado da auditoria JaCoCo: nenhum teste acima chamava estes métodos)

    @Test
    void read16Write8Write16GoThroughTheSamePermissionCheck() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_FULL_ACCESS, true);

        space.write8(0x100, 0xAB);
        assertEquals(0xAB, physical.read8(0x100));
        space.write16(0x102, 0x1234);
        assertEquals(0x1234, space.read16(0x102));

        MemoryTranslationException64 fault = assertThrows(MemoryTranslationException64.class,
                () -> space.write8(0x200, 1));
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0, fault.faultStatus());
    }

    @Test
    void accessCyclesForDataAccessSkipsThePermissionCheckAndDelegatesToPhysical() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1); // nenhuma região programada: qualquer fetch abortaria
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);

        // DATA_READ/DATA_WRITE não passam pela checagem de permissão em accessCycles (essa é feita
        // por read/write) — só delega ao físico, mesmo sem nenhuma região cobrindo o endereço.
        assertEquals(physical.accessCycles(0x800, 4, MemoryAccessType.DATA_READ),
                space.accessCycles(0x800, 4, MemoryAccessType.DATA_READ));
    }

    @Test
    void providesAccessCyclesAndNotifyWriteDelegateToPhysical() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);

        assertEquals(physical.providesAccessCycles(), space.providesAccessCycles());
        space.notifyWrite(0x10); // não deve lançar
    }

    // ── execução PERMITIDA numa região real (não a de fundo) ────────────────────────

    @Test
    void executeIsAllowedWhenApPermitsAndXnIsClear() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        physical.put32(0x100, 0xD503_201F); // NOP A64
        Pmsav8SystemRegisters64 bus = newBus(1);
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(AddressSpace64.wrapping(physical), bus);
        program(bus, 0, 0x100, PRBAR_FULL_ACCESS, true); // AP=0b01, XN=0
        space.setPrivileged(true);

        space.accessCycles(0x100, 4, MemoryAccessType.INSTRUCTION_FETCH); // não deve lançar
    }
}
