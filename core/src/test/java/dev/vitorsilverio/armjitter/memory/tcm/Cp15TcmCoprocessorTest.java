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
    void writingBtcmRegionRegisterProgramsBaseAndEnable() {
        TcmAddressSpace tcm = newTcmSpace();
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(tcm, CoprocessorBus.none());

        int written = 0x2000_0000 | 0b1;
        cp15.write(CP15, 0, 9, 1, 0, written); // BTCM

        assertTrue(tcm.btcm().enabled());
        assertEquals(0x2000_0000, tcm.btcm().baseAddress());
    }

    @Test
    void enableBitIsForcedToZeroWhenTcmIsPhysicallyAbsent() {
        // ATCM com tamanho ZERO ("sem TCM", ver Javadoc de TcmRegion) — RAZ do bit Enable (Javadoc
        // da classe: "RAZ se a TCM correspondente não existir fisicamente").
        TcmAddressSpace tcm = new TcmAddressSpace(new TestAddressSpace(0x0002_0000),
                new TestAddressSpace(0), 0,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(tcm, CoprocessorBus.none());

        cp15.write(CP15, 0, 9, 1, 1, 0x1000_0000 | 0b1); // tenta habilitar a ATCM ausente

        assertFalse(tcm.atcm().enabled(), "Enable deve ficar RAZ quando a TCM não existe fisicamente");
        assertEquals(0b0 | (0b1 << 16), cp15.read(CP15, 0, 0, 0, 2), "TCMTR reporta 0 ATCM, 1 BTCM");
    }

    @Test
    void writingRegionRegisterWithEnableBitClearLeavesTcmDisabledAndRazInReadback() {
        TcmAddressSpace tcm = newTcmSpace();
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(tcm, CoprocessorBus.none());

        // Base programado, Enable=0 — TCM presente mas não habilitada.
        cp15.write(CP15, 0, 9, 1, 1, 0x1000_0000); // ATCM, bit Enable claro
        assertFalse(tcm.atcm().enabled());

        int expectedSizeCode = TcmSizeField.encodeRawCode(SIXTY_FOUR_KB);
        int expected = 0x1000_0000 | (expectedSizeCode << 2); // enableBit=0
        assertEquals(expected, cp15.read(CP15, 0, 9, 1, 1), "Enable=0 deve refletir na releitura mesmo com TCM presente");
    }

    @Test
    void enableBitIsForcedToZeroWhenBtcmIsPhysicallyAbsent() {
        TcmAddressSpace tcm = new TcmAddressSpace(new TestAddressSpace(0x0002_0000),
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB,
                new TestAddressSpace(0), 0);
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(tcm, CoprocessorBus.none());

        cp15.write(CP15, 0, 9, 1, 0, 0x2000_0000 | 0b1); // tenta habilitar a BTCM ausente

        assertFalse(tcm.btcm().enabled(), "Enable deve ficar RAZ quando a BTCM não existe fisicamente");
        assertEquals(0b1, cp15.read(CP15, 0, 0, 0, 2), "TCMTR reporta 1 ATCM, 0 BTCM");
    }

    @Test
    void coprocessorLevelHandlesReportsCp15AndDelegates() {
        CoprocessorBus delegate = new CoprocessorBus() {
            @Override
            public boolean handles(int coprocessor) {
                return coprocessor == 14;
            }

            @Override
            public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
                return 0;
            }

            @Override
            public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            }
        };
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), delegate);

        assertTrue(cp15.handles(CP15), "CP15 sempre reconhecido por esta classe");
        assertTrue(cp15.handles(14), "coprocessador não-CP15 delega ao bus interno");
        assertFalse(cp15.handles(13), "nem esta classe nem o delegate reconhecem CP13");
        assertTrue(cp15.handles(14, 0, 0, 0, 2), "handles fino (5 args) delega quando coprocessador != CP15");
    }

    @Test
    void unrecognizedCoprocessorDelegatesReadAndWrite() {
        java.util.concurrent.atomic.AtomicBoolean writeCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        CoprocessorBus delegate = new CoprocessorBus() {
            @Override
            public boolean handles(int coprocessor) {
                return coprocessor == 14;
            }

            @Override
            public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
                return 0x99;
            }

            @Override
            public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
                writeCalled.set(true);
            }
        };
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), delegate);

        assertEquals(0x99, cp15.read(14, 0, 0, 0, 0), "coprocessador != CP15 delega leitura direto");
        assertEquals(0x99, cp15.read(14, 0, 0, 0, 2), "coprocessador != CP15 mesmo com crn/opcode2 iguais ao TCMTR");
        cp15.write(14, 0, 0, 0, 0, 0x1);
        assertTrue(writeCalled.get(), "coprocessador != CP15 delega escrita direto");
    }

    @Test
    void doubleRegisterOperationsAlwaysDelegate() {
        java.util.concurrent.atomic.AtomicBoolean writeDoubleCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        CoprocessorBus delegate = new CoprocessorBus() {
            @Override
            public boolean handles(int coprocessor) {
                return true;
            }

            @Override
            public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
                return 0;
            }

            @Override
            public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            }

            @Override
            public boolean handlesDouble(int coprocessor, int opcode1, int crm) {
                return true;
            }

            @Override
            public long readDouble(int coprocessor, int opcode1, int crm) {
                return 0x1234_5678_9ABCL;
            }

            @Override
            public void writeDouble(int coprocessor, int opcode1, int crm, int rt, int rt2) {
                writeDoubleCalled.set(true);
            }
        };
        Cp15TcmCoprocessor cp15 = new Cp15TcmCoprocessor(newTcmSpace(), delegate);

        assertTrue(cp15.handlesDouble(CP15, 0, 0), "Cp15TcmCoprocessor não tem registrador duplo próprio");
        assertEquals(0x1234_5678_9ABCL, cp15.readDouble(CP15, 0, 0));
        cp15.writeDouble(CP15, 0, 0, 1, 2);
        assertTrue(writeDoubleCalled.get());
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

        // Outras combinações de crn/crm que não são TCMTR nem TCM Region — cobrem os ramos restantes
        // de `ownsRegister` (crn=0 com crm!=0 ou opcode2!=2; crn=9 com crm!=1).
        assertFalse(cp15.handles(CP15, 0, 0, 1, 2), "crn=0 (identificação) mas crm != TCMTR");
        assertFalse(cp15.handles(CP15, 0, 0, 0, 3), "crn=0,crm=0 mas opcode2 != TCMTR");
        assertFalse(cp15.handles(CP15, 0, 9, 2, 0), "crn=9 (TCM) mas crm != região de TCM");
        cp15.write(CP15, 0, 6, 0, 0, 0x1); // registrador não-TCM sob CP15 — escrita também delega
    }
}
