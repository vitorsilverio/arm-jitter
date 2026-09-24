package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    void reconfiguringTcmBumpsTranslationGeneration() {
        TcmAddressSpace tcm = new TcmAddressSpace(new TestAddressSpace(0x1000),
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        int before = tcm.translationGeneration();
        tcm.reconfigureAtcm(0, true);
        assertNotEquals(before, tcm.translationGeneration(), "reprogramar a TCM muda a geração de tradução");
    }
}
