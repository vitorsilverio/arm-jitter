package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B20.4: `Cp15TcmCoprocessor` — via `MCR`/`MRC` de `TCMTR`/`ATCM`/`BTCM Region Register`,
/// encodings confirmados contra a Cortex-R5 TRM real (ARM DDI 0460D, ver Javadoc da classe).
class Cp15TcmCoprocessorTest {
    private static final int SIXTY_FOUR_KB = 64 * 1024;
    private static final int CP15 = 15;

    private static TcmAddressSpace newTcmSpace() {
        return new TcmAddressSpace(new TestAddressSpace(0x0002_0000),
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
    }

    @Test
    void tcmtrReportsOneAtcmAndOneBtcmAndIsReadOnly() {
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), CoprocessorBus.none());

        assertTrue(cp15.handles(CP15, 0, 0, 0, 2), "TCMTR (c0,c0,2)");
        assertEquals(0b1 | (0b1 << 16), cp15.read(CP15, 0, 0, 0, 2), "1 ATCM (bits[2:0]) + 1 BTCM (bits[18:16])");

        cp15.write(CP15, 0, 0, 0, 2, 0xFFFF_FFFF);
        assertEquals(0b1 | (0b1 << 16), cp15.read(CP15, 0, 0, 0, 2), "TCMTR é só leitura");
    }

    @Test
    void atcmRegionRegisterEncodingMatchesRealTrm() {
        // ARM DDI 0460D §4.3.24: "MRC p15, 0, <Rd>, c9, c1, 1 ; Read ATCM Region Register"
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), CoprocessorBus.none());
        assertTrue(cp15.handles(CP15, 0, 9, 1, 1), "ATCM Region Register (c9,c1,1)");
    }

    @Test
    void btcmRegionRegisterEncodingMatchesRealTrm() {
        // ARM DDI 0460D §4.3.23: "MRC p15, 0, <Rd>, c9, c1, 0 ; Read BTCM Region Register"
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), CoprocessorBus.none());
        assertTrue(cp15.handles(CP15, 0, 9, 1, 0), "BTCM Region Register (c9,c1,0)");
    }

    @Test
    void writingRegionRegisterProgramsBaseAndEnableButIgnoresSizeField() {
        TcmAddressSpace tcm = newTcmSpace();
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(tcm, CoprocessorBus.none());

        // Base=0x1000_0000, Enable=1, Size=0b11111 (lixo, TRM real: "On writes this field is ignored").
        int written = 0x1000_0000 | (0b1_1111 << 2) | 0b1;
        cp15.write(CP15, 0, 9, 1, 1, written); // ATCM

        assertTrue(tcm.atcm().enabled());
        assertEquals(0x1000_0000, tcm.atcm().baseAddress());
        assertEquals(SIXTY_FOUR_KB, tcm.atcm().sizeBytes(), "Size é fixo (config de hardware), nunca vem da escrita");

        // Releitura reconstrói o Size REAL (64KB = código 0b00111), não o lixo escrito.
        int expectedSizeCode = TcmSizeField.encodeRawCode(SIXTY_FOUR_KB);
        int expected = 0x1000_0000 | (expectedSizeCode << 2) | 0b1;
        assertEquals(expected, cp15.read(CP15, 0, 9, 1, 1));
    }

    @Test
    void unrecognizedRegisterDelegatesToInnerBus() {
        java.util.concurrent.atomic.AtomicBoolean delegateCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        CoprocessorBus delegate = new CoprocessorBus() {
            @Override
            public boolean handles(int coprocessor) {
                return coprocessor == CP15;
            }

            @Override
            public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
                return crn == 6; // registrador de MPU, por exemplo
            }

            @Override
            public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
                delegateCalled.set(true);
                return 0x4242;
            }

            @Override
            public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            }
        };
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), delegate);

        assertFalse(cp15.handles(CP15, 0, 9, 1, 5), "opcode2 não reconhecido por TCM nem MPU");
        assertTrue(cp15.handles(CP15, 0, 6, 0, 0), "delega para o bus interno (MPU)");
        assertEquals(0x4242, cp15.read(CP15, 0, 6, 0, 0));
        assertTrue(delegateCalled.get());
    }
}
