package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPermuteOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `EXT`/`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`/`TBL`/`TBX` (B8.10) direto no
/// executor (interpretador = oráculo, G1) — complementa {@code
/// Aarch64AdvSimdPermuteTableDecoderTest} (decode).
class Ir64VectorExtractPermuteTableExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void extDFormConcatenatesRnLowRmHighAndShiftsByBytes() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x0706050403020100L, 0L); // Rn byte i = i
        fp.setQ(2, 0x0F0E0D0C0B0A0908L, 0L); // Rm byte i = 8+i

        EXECUTOR.executeOp(core, new Ir64Op.VectorExtract(false, 3, 0, 1, 2));

        // datasize=8: janela começa no byte 3 de Rn, ultrapassa para Rm nos últimos bytes.
        assertEquals(0x0A09080706050403L, fp.low64(0));
        assertEquals(0L, fp.high64(0));
    }

    @Test
    void extQFormUsesFull128BitWindow() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x0706050403020100L, 0x0F0E0D0C0B0A0908L); // Rn byte i = i (0..15)
        fp.setQ(2, 0x1716151413121110L, 0x1F1E1D1C1B1A1918L); // Rm byte i = 16+i (16..31)

        EXECUTOR.executeOp(core, new Ir64Op.VectorExtract(true, 11, 0, 1, 2));

        // byte j do resultado = byte (11+j) da concatenação {Rm,Rn} (32 bytes lógicos):
        // j=0..4 vêm de Rn[11..15], j=5..15 vêm de Rm[0..10].
        assertEquals(0x1211100F0E0D0C0BL, fp.low64(0));
        assertEquals(0x1A19181716151413L, fp.high64(0));
    }

    @Test
    void uzp1TakesEvenElementsFromEachRegister() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 0x10 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorPermute(Ir64VectorPermuteOp.UZP1, false, 0, 0, 1, 2));

        for (int i = 0; i < 4; i++) {
            assertEquals(2 * i, fp.element(0, i, 0));
            assertEquals(0x10 + 2 * i, fp.element(0, 4 + i, 0));
        }
    }

    @Test
    void uzp2TakesOddElementsFromEachRegister() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 0x10 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorPermute(Ir64VectorPermuteOp.UZP2, false, 0, 0, 1, 2));

        for (int i = 0; i < 4; i++) {
            assertEquals(2 * i + 1, fp.element(0, i, 0));
            assertEquals(0x10 + 2 * i + 1, fp.element(0, 4 + i, 0));
        }
    }

    @Test
    void trn1InterleavesEvenIndexedPairs() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 0x10 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorPermute(Ir64VectorPermuteOp.TRN1, false, 0, 0, 1, 2));

        long[] expected = {0, 0x10, 2, 0x12, 4, 0x14, 6, 0x16};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], fp.element(0, i, 0));
        }
    }

    @Test
    void trn2InterleavesOddIndexedPairs() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 0x10 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorPermute(Ir64VectorPermuteOp.TRN2, false, 0, 0, 1, 2));

        long[] expected = {1, 0x11, 3, 0x13, 5, 0x15, 7, 0x17};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], fp.element(0, i, 0));
        }
    }

    @Test
    void zip1InterleavesLowHalves() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 0x10 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorPermute(Ir64VectorPermuteOp.ZIP1, false, 0, 0, 1, 2));

        long[] expected = {0, 0x10, 1, 0x11, 2, 0x12, 3, 0x13};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], fp.element(0, i, 0));
        }
    }

    @Test
    void zip2InterleavesHighHalves() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, i);
            fp.setElement(2, i, 0, 0x10 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorPermute(Ir64VectorPermuteOp.ZIP2, false, 0, 0, 1, 2));

        long[] expected = {4, 0x14, 5, 0x15, 6, 0x16, 7, 0x17};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], fp.element(0, i, 0));
        }
    }

    @Test
    void tblSelectsBytesFromSingleTableRegisterAndZeroesOutOfRange() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(1, 0x0706050403020100L, 0x0F0E0D0C0B0A0908L); // tabela (1 registrador): byte i = i
        // índices: 0 (in range->0), 15 (in range->15), 16 (fora, len=0->tableBytes=16), 255 (fora)
        fp.setQ(2, 0x00000000FF100F00L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorTableLookup(false, 0, true, 0, 1, 2));

        assertEquals(0L, fp.element(0, 0, 0));
        assertEquals(15L, fp.element(0, 1, 0));
        assertEquals(0L, fp.element(0, 2, 0), "índice 16 fora da tabela → 0 (TBL)");
        assertEquals(0L, fp.element(0, 3, 0), "índice 255 fora da tabela → 0 (TBL)");
    }

    @Test
    void tbxPreservesDestinationForOutOfRangeIndex() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x1111111111111111L, 0x2222222222222222L); // Rd pré-existente
        fp.setQ(1, 0x0706050403020100L, 0x0F0E0D0C0B0A0908L); // tabela (1 registrador, len=0)
        fp.setQ(2, 0xFF00000000000000L, 0L); // índice 0 no byte 0, índice 0xFF (fora) no byte 7

        EXECUTOR.executeOp(core, new Ir64Op.VectorTableLookup(true, 0, true, 0, 1, 2));

        assertEquals(0L, fp.element(0, 0, 0), "índice 0 → tabela[0]=0");
        assertEquals(0x11L, fp.element(0, 7, 0), "índice fora da tabela → byte ATUAL de Rd preservado (TBX)");
    }

    @Test
    void tblTwoRegisterTableWrapsAcrossRegisterBoundary() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Tabela de 2 registradores (len=1): v1 = bytes 0..15, v2 = bytes 16..31.
        fp.setQ(1, 0x0706050403020100L, 0x0F0E0D0C0B0A0908L);
        fp.setQ(2, 0x1716151413121110L, 0x1F1E1D1C1B1A1918L);
        fp.setQ(3, 20L, 0L); // índice 20 no byte 0, resto 0

        EXECUTOR.executeOp(core, new Ir64Op.VectorTableLookup(false, 1, true, 0, 1, 3));

        assertEquals(20L, fp.element(0, 0, 0), "índice 20 cai em v2 (rn+1), byte 4 (20-16=4), valor=20");
    }
}
