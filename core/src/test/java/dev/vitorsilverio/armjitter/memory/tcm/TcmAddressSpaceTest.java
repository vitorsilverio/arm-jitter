package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B20.4: `TcmAddressSpace` sobrepondo o barramento — "Aceite" da task.
class TcmAddressSpaceTest {
    private static final int SIXTY_FOUR_KB = 64 * 1024;

    @Test
    void enabledAtcmOverlaysBusAndOutOfRangeStaysExternal() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        physical.put32(0, 0xDEAD_BEEF); // conteúdo do barramento externo em 0x0
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0x1234_5678); // conteúdo da TCM
        TestAddressSpace btcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB, btcmMemory, SIXTY_FOUR_KB);

        // Antes de programar: ATCM desabilitada, endereço 0 ainda é o barramento.
        assertEquals(0xDEAD_BEEF, tcm.read32(0));

        tcm.reconfigureAtcm(0, true);
        assertEquals(0x1234_5678, tcm.read32(0), "ATCM habilitada em 0x0 deve sobrepor o barramento");

        // Fora da faixa da TCM (64KB): continua acessível via barramento.
        assertEquals(0, tcm.read32(0x0001_0000));
        physical.put32(0x0001_0000, 0x0BAD_F00D);
        assertEquals(0x0BAD_F00D, tcm.read32(0x0001_0000));
    }

    @Test
    void repositioningTcmMovesTheOverlayAndFreesTheOldAddress() {
        TestAddressSpace physical = new TestAddressSpace(0x0004_0000);
        physical.put32(0, 0xAAAA_AAAA);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0x1111_1111);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);

        tcm.reconfigureAtcm(0, true);
        assertEquals(0x1111_1111, tcm.read32(0));

        tcm.reconfigureAtcm(0x1000, true);
        assertEquals(0x1111_1111, tcm.read32(0x1000), "TCM reposicionada aparece no novo endereço");
        assertEquals(0xAAAA_AAAA, tcm.read32(0), "endereço antigo volta a ser o barramento externo");
    }

    @Test
    void disabledEnableBitLeavesWrapperTransparent() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        physical.put32(0, 0x7777_7777);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0x8888_8888);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);

        tcm.reconfigureAtcm(0, false); // programada mas NÃO habilitada
        assertEquals(0x7777_7777, tcm.read32(0), "EN=0 deve deixar o wrapper transparente");
    }

    @Test
    void instructionFetchAlsoSeesTheTcm() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0xE3A0_000A); // MOV R0, #10
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        tcm.reconfigureAtcm(0, true);

        assertEquals(0xE3A0_000A, tcm.fetch32(0), "busca de instrução deve ver a TCM, não só leitura de dados");
    }

    @Test
    void atcmTakesPrecedenceOverOverlappingBtcm() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0xAAAA_0000);
        TestAddressSpace btcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        btcmMemory.put32(0, 0xBBBB_0000);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB, btcmMemory, SIXTY_FOUR_KB);

        // Configuração inválida (TRM real: "must not overlap") — ambas programadas no MESMO endereço.
        tcm.reconfigureAtcm(0, true);
        tcm.reconfigureBtcm(0, true);

        assertEquals(0xAAAA_0000, tcm.read32(0), "ATCM tem precedência documentada sobre BTCM sobreposta");
    }

    @Test
    void byteAndHalfwordReadsSeeTheTcmAndTheBus() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        physical.put32(0x0001_0000, 0x1122_3344);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, 0xAABB_CCDD);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        tcm.reconfigureAtcm(0, true);

        assertEquals(0xDD, tcm.read8(0), "read8 deve ver a TCM");
        assertEquals(0xCCDD, tcm.read16(0), "read16 deve ver a TCM");
        assertEquals(0x44, tcm.read8(0x0001_0000), "read8 fora da faixa continua no barramento");
        assertEquals(0x3344, tcm.read16(0x0001_0000), "read16 fora da faixa continua no barramento");
    }

    @Test
    void byteHalfwordAndWordWritesTargetTheTcmAndTheBus() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        tcm.reconfigureAtcm(0, true);

        tcm.write8(0, 0xAB);
        assertEquals(0xAB, atcmMemory.read8(0), "write8 na faixa da TCM deve ir para a memória da TCM");
        tcm.write16(0x10, 0x1234);
        assertEquals(0x1234, atcmMemory.read16(0x10), "write16 na faixa da TCM deve ir para a memória da TCM");
        tcm.write32(0x20, 0x89AB_CDEF);
        assertEquals(0x89AB_CDEF, atcmMemory.read32(0x20), "write32 na faixa da TCM deve ir para a memória da TCM");

        tcm.write8(0x0001_0000, 0x11);
        assertEquals(0x11, physical.read8(0x0001_0000), "write8 fora da faixa deve ir para o barramento");
        tcm.write16(0x0001_0010, 0x2222);
        assertEquals(0x2222, physical.read16(0x0001_0010), "write16 fora da faixa deve ir para o barramento");
        tcm.write32(0x0001_0020, 0x3333_3333);
        assertEquals(0x3333_3333, physical.read32(0x0001_0020), "write32 fora da faixa deve ir para o barramento");
    }

    @Test
    void halfwordInstructionFetchAlsoSeesTheTcm() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        physical.put16(0x0001_0000, 0x4770); // BX LR (Thumb)
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put16(0, 0xB500); // PUSH {LR} (Thumb)
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        tcm.reconfigureAtcm(0, true);

        assertEquals(0xB500, tcm.fetch16(0), "fetch16 deve ver a TCM");
        assertEquals(0x4770, tcm.fetch16(0x0001_0000), "fetch16 fora da faixa continua no barramento");
    }

    @Test
    void onlyBtcmEnabledResolvesThroughBtcm() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        physical.put32(0, 0xFFFF_FFFF);
        TestAddressSpace btcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        btcmMemory.put32(0, 0xBBBB_0000);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB,
                btcmMemory, SIXTY_FOUR_KB);
        tcm.reconfigureBtcm(0, true);

        assertEquals(0xBBBB_0000, tcm.read32(0), "sem ATCM habilitada, BTCM deve resolver o endereço");
    }

    @Test
    void accessCyclesAndProvidesAccessCyclesDelegateToPhysicalBus() {
        AccessCyclesAddressSpace physical = new AccessCyclesAddressSpace(0x0002_0000, 7, false);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);

        assertEquals(7, tcm.accessCycles(0, 4, MemoryAccessType.DATA_READ),
                "B20.4 não modela latência de TCM: accessCycles delega ao barramento físico");
        assertFalse(tcm.providesAccessCycles(), "providesAccessCycles delega ao barramento físico");
    }

    @Test
    void notifyWriteReachesTheTcmMemoryOrThePhysicalBus() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        tcm.reconfigureAtcm(0, true);

        tcm.notifyWrite(0);
        assertEquals(0, atcmMemory.read32(0), "notifyWrite na faixa da TCM não deve lançar/afetar leitura normal");
        tcm.notifyWrite(0x0001_0000);
        assertEquals(0, physical.read32(0x0001_0000), "notifyWrite fora da faixa deve delegar ao barramento");
    }

    /// Barramento de teste que reporta ciclos/`providesAccessCycles` configuráveis, para provar que
    /// `TcmAddressSpace` delega (não modela latência própria — "Não inclui" da B20.4).
    private record AccessCyclesAddressSpace(int size, int cycles, boolean provides)
            implements dev.vitorsilverio.armjitter.memory.AddressSpace {
        @Override
        public int read8(int address) {
            return 0;
        }

        @Override
        public int read16(int address) {
            return 0;
        }

        @Override
        public int read32(int address) {
            return 0;
        }

        @Override
        public void write8(int address, int value) {
        }

        @Override
        public void write16(int address, int value) {
        }

        @Override
        public void write32(int address, int value) {
        }

        @Override
        public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
            return cycles;
        }

        @Override
        public boolean providesAccessCycles() {
            return provides;
        }
    }

    @Test
    void reconfiguringTcmBumpsTranslationGeneration() {
        TcmAddressSpace tcm = new TcmAddressSpace(new TestAddressSpace(0x1000),
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        int before = tcm.translationGeneration();
        tcm.reconfigureAtcm(0, true);
        assertNotEquals(before, tcm.translationGeneration(), "reprogramar a TCM muda a geração de tradução");
    }
}
