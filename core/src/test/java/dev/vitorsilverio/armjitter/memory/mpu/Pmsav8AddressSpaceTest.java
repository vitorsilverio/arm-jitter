package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B20.7: checagem por região de {@link Pmsav8AddressSpace}, semântica confirmada contra o QEMU
/// real (`target/arm/ptw.c`, `pmsav8_mpu_lookup`) — ver Javadoc da classe.
class Pmsav8AddressSpaceTest {
    private static final int PRLAR_ENABLE = 0b1;
    /// `PRBAR`: `AP=0b01` (RW privilegiado+usuário), `XN=0`.
    private static final int PRBAR_FULL_ACCESS = 0b01 << 1;
    /// `PRBAR`: `AP=0b00` (RW só privilegiado — nega acesso de usuário).
    private static final int PRBAR_PRIVILEGED_ONLY = 0b00 << 1;
    /// `PRBAR`: `AP=0b11` (RO privilegiado+usuário), `XN=0`.
    private static final int PRBAR_READ_ONLY = 0b11 << 1;
    private static final int PRBAR_XN_BIT = 1;

    private static void program(Pmsav8MpuRegisters mpu, int region, int base, int prbarFlags, boolean enabled) {
        mpu.setPrselr(region);
        mpu.setPrbar(base | prbarFlags);
        mpu.setPrlar((base + 0x3F) | (enabled ? PRLAR_ENABLE : 0));
    }

    private static Pmsav8AddressSpace spaceWithRegions(int regionCount, TestAddressSpace physical) {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(regionCount, 1);
        mpu.setMpuEnabled(true);
        return new Pmsav8AddressSpace(physical, mpu);
    }

    // ── limite inclusivo (granule de 64 bytes) ──────────────────────────────────────

    @Test
    void limitIsInclusiveUpToLastByteOfSixtyFourByteGranule() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        // Região [0x100, 0x13F] (base alinhada a 64, limite = base + 0x3F).
        mpu.setPrselr(0);
        mpu.setPrbar(0x100 | PRBAR_FULL_ACCESS);
        mpu.setPrlar(0x13F | PRLAR_ENABLE);

        assertEquals(0, space.read8(0x13F), "último byte do bloco de 64: dentro do limite");
        assertThrows(Pmsav8AccessException.class, () -> space.read8(0x140), "primeiro byte FORA do limite");
    }

    // ── sobreposição: PMSAv8 nunca tem prioridade (Achado 2 do épico) ───────────────

    @Test
    void overlappingRegionsAlwaysFaultRegardlessOfIndexOrder() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        // Duas regiões cobrindo o MESMO endereço 0x100 — mesmo com AP permissivo nas duas, é falha.
        program(mpu, 0, 0x100, PRBAR_FULL_ACCESS, true);
        program(mpu, 1, 0x100, PRBAR_FULL_ACCESS, true);

        Pmsav8AccessException fault = assertThrows(Pmsav8AccessException.class, () -> space.read8(0x100));
        assertEquals(Pmsav8FaultStatus.PERMISSION, fault.faultStatus());
    }

    @Test
    void nonOverlappingRegionsAtDifferentBasesBothWork() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_FULL_ACCESS, true);
        program(mpu, 1, 0x200, PRBAR_FULL_ACCESS, true);

        assertEquals(0, space.read8(0x100));
        assertEquals(0, space.read8(0x200));
    }

    @Test
    void disabledRegionIsIgnoredEntirely() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(2, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        // Região 0: mapa de fundo explícito, permissiva.
        program(mpu, 0, 0x000, PRBAR_FULL_ACCESS, true);
        // Região 1: cobriria 0x100, mas está DESABILITADA (PRLAR.EN=0) — ignorada, sem contar
        // como sobreposição.
        program(mpu, 1, 0x100, PRBAR_FULL_ACCESS, false);

        assertEquals(0, space.read8(0x100), "cai na região 0 (mapa de fundo explícito), nunca conflita com a 1 desabilitada");
    }

    // ── região de fundo ──────────────────────────────────────────────────────────────

    @Test
    void noRegionMatchesAndBackgroundDisabledFaultsWithPermissionStatus() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8AddressSpace space = spaceWithRegions(1, physical);

        Pmsav8AccessException fault = assertThrows(Pmsav8AccessException.class, () -> space.read32(0x800));
        assertEquals(Pmsav8FaultStatus.PERMISSION, fault.faultStatus(),
                "PMSAv8-32 perfil não-M não tem código BACKGROUND próprio (achado real, ver Javadoc)");
        assertEquals(0x800, fault.virtualAddress());
        assertEquals(MemoryAccessType.DATA_READ, fault.accessType());
    }

    @Test
    void privilegedAccessWithBackgroundRegionEnabledUsesDefaultMap() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        space.setPrivileged(true);

        assertEquals(0, space.read32(0x1000));
    }

    @Test
    void userModeNeverUsesBackgroundRegionEvenWhenEnabled() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        space.setPrivileged(false);

        assertThrows(Pmsav8AccessException.class, () -> space.read32(0x800));
    }

    @Test
    void backgroundDefaultMapDeniesExecuteInUpperHalfOfAddressSpace() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);

        assertEquals(0, space.fetch32(0x1000));
        assertThrows(Pmsav8AccessException.class, () -> space.fetch32(0x8000_1000));
    }

    // ── permissão (AP/XN, 2 bits — não 3 como no PMSAv7) ────────────────────────────

    @Test
    void apZeroIsPrivilegedReadWriteOnlyNoUserAccess() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_PRIVILEGED_ONLY, true);

        space.setPrivileged(true);
        space.write32(0x100, 0x1234);
        assertEquals(0x1234, space.read32(0x100));

        space.setPrivileged(false);
        assertThrows(Pmsav8AccessException.class, () -> space.read8(0x100));
    }

    @Test
    void apThreeIsReadOnlyForBothPrivilegeLevels() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_READ_ONLY, true);

        space.setPrivileged(true);
        assertEquals(0, space.read8(0x100));
        Pmsav8AccessException fault = assertThrows(Pmsav8AccessException.class, () -> space.write32(0x100, 1));
        assertEquals(Pmsav8FaultStatus.PERMISSION, fault.faultStatus());
        assertEquals(MemoryAccessType.DATA_WRITE, fault.accessType());

        space.setPrivileged(false);
        assertEquals(0, space.read8(0x100), "AP=3 dá leitura para usuário também");
        assertThrows(Pmsav8AccessException.class, () -> space.write8(0x100, 1));
    }

    @Test
    void executeNeverDeniesFetchButNotDataReadAtSameAddress() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        physical.put32(0x100, 0xE320_F000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_FULL_ACCESS | PRBAR_XN_BIT, true);

        Pmsav8AccessException fault = assertThrows(Pmsav8AccessException.class, () -> space.fetch32(0x100));
        assertEquals(MemoryAccessType.INSTRUCTION_FETCH, fault.accessType());
        assertEquals(0xE320_F000, space.read32(0x100), "leitura de DADOS não é afetada por XN");
    }

    // ── SCTLR.M=0: bypass total ──────────────────────────────────────────────────

    @Test
    void mpuDisabledIsTotalBypassEvenWithRestrictiveRegionsProgrammed() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(false);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_PRIVILEGED_ONLY, true);
        space.setPrivileged(false);

        space.write32(0x100, 0xABCD);
        assertEquals(0xABCD, space.read32(0x100));
    }

    // ── ModeChangeListener / withUnprivilegedAccess ─────────────────────────────────

    @Test
    void onModeChangedToUserDisablesPrivilegedPermission() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_PRIVILEGED_ONLY, true);

        space.onModeChanged(dev.vitorsilverio.armjitter.core.CpuMode.USER);
        assertThrows(Pmsav8AccessException.class, () -> space.read8(0x100));

        space.onModeChanged(dev.vitorsilverio.armjitter.core.CpuMode.SUPERVISOR);
        assertEquals(0, space.read8(0x100));
    }

    @Test
    void withUnprivilegedAccessTemporarilySwitchesToUserPermissionAndRestores() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_PRIVILEGED_ONLY, true);
        space.setPrivileged(true);

        assertThrows(Pmsav8AccessException.class, () -> space.withUnprivilegedAccess(() -> space.read8(0x100)));
        assertEquals(0, space.read8(0x100), "privilégio original restaurado depois do escopo");
    }

    @Test
    void withUnprivilegedAccessRunsActionToCompletionAndRestoresPrivilegeOnSuccess() {
        // Caso "feliz" (action.run() retorna sem lançar) — sem este teste, o único caminho
        // exercitado por withUnprivilegedAccessTemporarilySwitchesToUserPermissionAndRestores
        // sempre lança, e o retorno normal do método (fora do finally, sem exceção) fica sem
        // cobertura (achado real via JaCoCo, sessão de auditoria pós-B20.7).
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(true);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        program(mpu, 0, 0x100, PRBAR_FULL_ACCESS, true); // usuário também tem acesso — não lança
        space.setPrivileged(true);

        int[] valueSeenInsideAction = new int[1];
        space.withUnprivilegedAccess(() -> valueSeenInsideAction[0] = space.read8(0x100));

        assertEquals(0, valueSeenInsideAction[0], "action.run() completou normalmente dentro do escopo");
        space.write32(0x100, 0xCAFE); // fora do escopo, privilégio restaurado (AP permite escrita privilegiada)
        assertEquals(0xCAFE, space.read32(0x100));
    }

    @Test
    void allSizesReadWriteAndFetchDelegateToPhysicalWhenBypassed() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(false);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);

        space.write8(0x10, 0xAB);
        assertEquals(0xAB, space.read8(0x10));
        space.write16(0x20, 0x1234);
        assertEquals(0x1234, space.read16(0x20));
        physical.put16(0x30, 0x5678);
        assertEquals(0x5678, space.fetch16(0x30));
        assertEquals(0, space.translationGeneration());
    }

    @Test
    void notifyWriteAndAccessCyclesDelegateToPhysical() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        mpu.setMpuEnabled(false);
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);

        space.notifyWrite(0x10);
        assertEquals(physical.providesAccessCycles(), space.providesAccessCycles());
        assertEquals(physical.accessCycles(0x10, 4, MemoryAccessType.DATA_READ),
                space.accessCycles(0x10, 4, MemoryAccessType.DATA_READ));
    }
}
