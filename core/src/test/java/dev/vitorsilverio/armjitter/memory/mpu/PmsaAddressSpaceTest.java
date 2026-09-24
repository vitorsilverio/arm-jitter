package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B20.3: checagem por região de {@link PmsaAddressSpace}, semântica confirmada contra o QEMU
/// real (`target/arm/ptw.c`, `get_phys_addr_pmsav7`) — ver Javadoc da classe. `Pmsav7MpuCoprocessor`
/// não entra aqui (a via `MCR`/`MRC` já é testada por `Pmsav7MpuCoprocessorTest`, B20.2); os
/// registradores são programados diretamente no {@link Pmsav7MpuRegisters}.
class PmsaAddressSpaceTest {
    /// `DRACR`: `AP=3` (RWX plena, privilegiado e usuário), `XN=0`.
    private static final int DRACR_FULL_ACCESS = 0b011 << 8;
    /// `DRACR`: `AP=0` (sem acesso algum).
    private static final int DRACR_NO_ACCESS = 0;
    /// `DRACR`: `AP=6` (RX privilegiado e usuário, sem escrita para ninguém — ao contrário de
    /// `AP=2`, que dá RWX pleno em modo PRIVILEGIADO no perfil R real), `XN=0`.
    private static final int DRACR_READ_EXEC = 0b110 << 8;
    private static final int DRACR_XN_BIT = 1 << 12;

    private static int drsrEnabled(int rawRsize) {
        return 0b1 | (rawRsize << 1);
    }

    private static int drsrWithSubregionDisabled(int rawRsize, int subregionIndex) {
        return drsrEnabled(rawRsize) | (1 << (8 + subregionIndex));
    }

    private static void program(Pmsav7MpuRegisters mpu, int region, int base, int drsr, int dracr) {
        mpu.setRgnr(region);
        mpu.setDrbar(base);
        mpu.setDrsr(drsr);
        mpu.setDracr(dracr);
    }

    private static PmsaAddressSpace spaceWithRegions(int regionCount, TestAddressSpace physical) {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(regionCount);
        mpu.setMpuEnabled(true);
        return new PmsaAddressSpace(physical, mpu);
    }

    // ── prioridade invertida (armadilha nº 1 do épico) ──────────────────────────────

    @Test
    void higherIndexRegionWinsOverlapAndDeniesAccess() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(2);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // Região 0 (índice menor): 4KiB cobrindo tudo, RWX plena.
        program(mpu, 0, 0, drsrEnabled(11), DRACR_FULL_ACCESS); // 2^12=4096
        // Região 1 (índice maior): 32 bytes em 0x100, sem acesso — deve VENCER a região 0.
        program(mpu, 1, 0x100, drsrEnabled(4), DRACR_NO_ACCESS); // 2^5=32

        assertThrows(PmsaAccessException.class, () -> space.read8(0x100));
        // Fora da região restritiva: a região 0 (menos específica) continua valendo.
        assertEquals(0, space.read8(0x200));
    }

    @Test
    void lowerIndexRestrictiveRegionLosesToHigherIndexPermissiveRegion() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(2);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // Região 0 (índice menor): restritiva.
        program(mpu, 0, 0, drsrEnabled(11), DRACR_NO_ACCESS);
        // Região 1 (índice maior): a MESMA faixa, permissiva — deve VENCER.
        program(mpu, 1, 0, drsrEnabled(11), DRACR_FULL_ACCESS);

        assertEquals(0, space.read8(0x100));
    }

    // ── sub-regiões ──────────────────────────────────────────────────────────────

    @Test
    void disabledSubregionFallsThroughToLowerIndexRegion() {
        TestAddressSpace physical = new TestAddressSpace(8192);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(2);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // Região 0: mapa de fundo explícito (4KiB inteiro, permissivo) para o "continue" ter onde cair.
        program(mpu, 0, 0, drsrEnabled(11), DRACR_FULL_ACCESS);
        // Região 1: 4KiB (rsize=11) com a sub-região 0 (primeiros 512 bytes) desabilitada.
        program(mpu, 1, 0, drsrWithSubregionDisabled(11, 0), DRACR_NO_ACCESS);

        // Dentro da sub-região 0 desabilitada: NÃO conta como acerto da região 1 -> cai na região 0.
        assertEquals(0, space.read8(0x10));
        // Fora da sub-região 0 (na sub-região 1, 512-1023): a região 1 casa e nega.
        assertThrows(PmsaAccessException.class, () -> space.read8(0x200));
    }

    @Test
    void regionSmallerThan256BytesIgnoresSubregionDisableBits() {
        TestAddressSpace physical = new TestAddressSpace(256);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // 128 bytes (rsize=6, < 8): SRD é ignorado mesmo com o bit 0 ligado.
        program(mpu, 0, 0, drsrWithSubregionDisabled(6, 0), DRACR_FULL_ACCESS);

        assertEquals(0, space.read8(0x10));
    }

    // ── região de fundo ("background") ──────────────────────────────────────────────

    @Test
    void noRegionMatchesAndBackgroundDisabledFaultsWithBackgroundStatus() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        PmsaAddressSpace space = spaceWithRegions(1, physical);
        // Região desabilitada (DRSR.EN=0): nunca casa com nada.

        PmsaAccessException fault = assertThrows(PmsaAccessException.class, () -> space.read32(0x1000));
        assertEquals(PmsaFaultStatus.BACKGROUND, fault.faultStatus());
        assertEquals(0x1000, fault.virtualAddress());
        assertEquals(MemoryAccessType.DATA_READ, fault.accessType());
    }

    @Test
    void privilegedAccessWithBackgroundRegionEnabledUsesDefaultMap() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        space.setPrivileged(true);

        assertEquals(0, space.read32(0x1000), "mapa de fundo dá leitura em qualquer endereço privilegiado");
    }

    @Test
    void userModeNeverUsesBackgroundRegionEvenWhenEnabled() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        space.setPrivileged(false);

        PmsaAccessException fault = assertThrows(PmsaAccessException.class, () -> space.read32(0x1000));
        assertEquals(PmsaFaultStatus.BACKGROUND, fault.faultStatus());
    }

    @Test
    void backgroundDefaultMapDeniesExecuteInUpperHalfOfAddressSpace() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);

        // Metade inferior (bit 31 = 0): mapa de fundo permite fetch.
        assertEquals(0, space.fetch32(0x1000));
        // Metade superior (bit 31 = 1): simplificação nomeada desta task nega execução (ver Javadoc).
        PmsaAccessException fault = assertThrows(PmsaAccessException.class, () -> space.fetch32(0x8000_1000));
        assertEquals(PmsaFaultStatus.PERMISSION, fault.faultStatus());
        assertEquals(MemoryAccessType.INSTRUCTION_FETCH, fault.accessType());
    }

    @Test
    void backgroundDefaultMapAllowsWriteInPrivilegedMode() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        mpu.setBackgroundRegionEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);

        space.write32(0x1000, 0x1234);
        assertEquals(0x1234, space.read32(0x1000));
    }

    // ── permissão (AP/XN) ────────────────────────────────────────────────────────

    @Test
    void permissionDeniedOnWriteRaisesPermissionFaultWithFaultingAddress() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        program(mpu, 0, 0, drsrEnabled(11), DRACR_READ_EXEC); // read-only para todo mundo

        PmsaAccessException fault = assertThrows(PmsaAccessException.class, () -> space.write32(0x100, 1));
        assertEquals(PmsaFaultStatus.PERMISSION, fault.faultStatus());
        assertEquals(0x100, fault.virtualAddress());
        assertEquals(MemoryAccessType.DATA_WRITE, fault.accessType());
        // Leitura no MESMO endereço continua permitida (AP=2 é RX, não nega leitura).
        assertEquals(0, space.read32(0x100));
    }

    @Test
    void executeNeverDeniesFetchButNotDataReadAtSameAddress() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        physical.put32(0x100, 0xE320_F000); // NOP, irrelevante ao teste (só bytes a ler)
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        program(mpu, 0, 0, drsrEnabled(11), DRACR_FULL_ACCESS | DRACR_XN_BIT);

        PmsaAccessException fault = assertThrows(PmsaAccessException.class, () -> space.fetch32(0x100));
        assertEquals(MemoryAccessType.INSTRUCTION_FETCH, fault.accessType());
        assertEquals(0xE320_F000, space.read32(0x100), "leitura de DADOS não é afetada por XN");
    }

    @Test
    void reservedApValueDeniesAllAccessForBothPrivilegeLevels() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // AP=7: reservado no perfil R (ao contrário do que a tabela "leitura desta spec" supunha).
        program(mpu, 0, 0, drsrEnabled(11), 0b111 << 8);

        space.setPrivileged(true);
        assertThrows(PmsaAccessException.class, () -> space.read8(0x10));
        space.setPrivileged(false);
        assertThrows(PmsaAccessException.class, () -> space.read8(0x10));
    }

    // ── SCTLR.M=0: bypass total ──────────────────────────────────────────────────

    @Test
    void mpuDisabledIsTotalBypassEvenWithRestrictiveRegionsProgrammed() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(false);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        program(mpu, 0, 0, drsrEnabled(11), DRACR_NO_ACCESS);

        space.write32(0x10, 0xABCD);
        assertEquals(0xABCD, space.read32(0x10));
        assertEquals(0, space.fetch32(0x10) & 0xFFFF_0000); // não lança, só verifica que não abortou
    }

    // ── DRSR/DRBAR inválidos: região ignorada por inteiro ───────────────────────────

    @Test
    void rsizeBelowMinimumIsIgnoredEntirely() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(2);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        program(mpu, 0, 0, drsrEnabled(11), DRACR_FULL_ACCESS);
        // RSIZE=0 (< rsize_min=1 do perfil R): região 1 ignorada por inteiro, mesmo com EN=1.
        program(mpu, 1, 0, 0b1, DRACR_NO_ACCESS);

        assertEquals(0, space.read8(0x10));
    }

    @Test
    void misalignedBaseIsIgnoredEntirely() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(2);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        program(mpu, 0, 0, drsrEnabled(11), DRACR_FULL_ACCESS);
        // Base desalinhada ao tamanho da região (32 bytes): ignorada por inteiro (erro de guest).
        program(mpu, 1, 0x101, drsrEnabled(4), DRACR_NO_ACCESS);

        assertEquals(0, space.read8(0x101));
    }

    // ── withUnprivilegedAccess / ModeChangeListener ────────────────────────────────

    @Test
    void allSizesReadWriteAndFetchAreCheckedAndDelegateToPhysical() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters regs = new Pmsav7MpuRegisters(1);
        regs.setMpuEnabled(false); // bypass total: cobre read16/write8/write16/fetch16 sem AP/XN
        PmsaAddressSpace bypass = new PmsaAddressSpace(physical, regs);

        bypass.write8(0x10, 0xAB);
        assertEquals(0xAB, bypass.read8(0x10));
        bypass.write16(0x20, 0x1234);
        assertEquals(0x1234, bypass.read16(0x20));
        physical.put16(0x30, 0x5678);
        assertEquals(0x5678, bypass.fetch16(0x30));
    }

    @Test
    void withUnprivilegedAccessRestoresOriginalPrivilegeAfterActionCompletesNormally() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // AP=1: RW só privilegiado -- dentro do escopo (usuário) nega; fora (privilegiado) permite.
        program(mpu, 0, 0, drsrEnabled(11), 0b001 << 8);
        space.setPrivileged(true);

        space.withUnprivilegedAccess(() -> { }); // não toca memória: só prova o retorno normal do escopo
        assertEquals(0, space.read8(0x10), "privilégio original restaurado, leitura privilegiada permitida");
    }

    @Test
    void withUnprivilegedAccessTemporarilySwitchesToUserPermission() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        // AP=1: RW privilegiado, sem acesso algum em modo usuário.
        program(mpu, 0, 0, drsrEnabled(11), 0b001 << 8);
        space.setPrivileged(true);

        assertThrows(PmsaAccessException.class, () -> space.withUnprivilegedAccess(() -> space.read8(0x10)));
        // Privilégio original restaurado depois do escopo.
        assertEquals(0, space.read8(0x10));
    }

    @Test
    void onModeChangedToUserDisablesPrivilegedPermission() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(true);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        program(mpu, 0, 0, drsrEnabled(11), 0b001 << 8);

        space.onModeChanged(dev.vitorsilverio.armjitter.core.CpuMode.USER);
        assertThrows(PmsaAccessException.class, () -> space.read8(0x10));

        space.onModeChanged(dev.vitorsilverio.armjitter.core.CpuMode.SUPERVISOR);
        assertEquals(0, space.read8(0x10));
    }

    // ── delegação transparente ao físico ────────────────────────────────────────────

    @Test
    void notifyWriteAndAccessCyclesDelegateToPhysical() {
        TestAddressSpace physical = new TestAddressSpace(4096);
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setMpuEnabled(false);
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);

        space.notifyWrite(0x10);
        assertEquals(physical.providesAccessCycles(), space.providesAccessCycles());
        assertEquals(physical.accessCycles(0x10, 4, MemoryAccessType.DATA_READ),
                space.accessCycles(0x10, 4, MemoryAccessType.DATA_READ));
        assertEquals(0, space.translationGeneration());
    }
}
