package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64MinMaxOp;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.21 — resíduo de `FEAT_CSSC` em registrador geral: `CTZ` (subgrupo "Data-processing
/// (1 source)", mesmo gate de `ABS`/B19.6) e `SMAX`/`SMIN`/`UMAX`/`UMIN` (subgrupo
/// "Data-processing (2 source)", mesmo campo de opcode de `SUBP`/`IRG`/`GMI`/`PACGA`/`CRC32*`).
/// Vetores golden calculados a partir do layout de bits confirmado byte a byte contra
/// `PACGA_X0_X1_X2`/`ABS_X0_X1` (`Aarch64B196DiversosDecoderTest`, medidos com
/// `aarch64-none-elf-as`/`objdump`, devkitA64) — mesmos campos (`sf`(31), fixo `11010110`(28:21),
/// `Rm`(20:16), opcode(15:10), `Rn`(9:5), `Rd`(4:0)), só o campo de opcode muda.
class Aarch64Cssc2SourceResidualDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder CSSC_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_9_A);

    // ctz x0, x1 (opcode 1-source = 0b000110, MESMO subgrupo/gate de ABS_X0_X1=0xDAC02020)
    private static final int CTZ_X0_X1 = 0xDAC01820;
    // ctz w0, w1 (sf=0)
    private static final int CTZ_W0_W1 = 0x5AC01820;

    // smax/smin/umax/umin x0, x1, x2 (opcode 2-source distingue os 4; MESMO campo de SUBP/PACGA)
    private static final int SMAX_X0_X1_X2 = 0x9AC26020;
    private static final int UMAX_X0_X1_X2 = 0x9AC26420;
    private static final int SMIN_X0_X1_X2 = 0x9AC26820;
    private static final int UMIN_X0_X1_X2 = 0x9AC26C20;
    // smax w0, w1, w2 (sf=0)
    private static final int SMAX_W0_W1_W2 = 0x1AC26020;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── CTZ ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ctzGatedByCsscSameAsAbs() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CTZ_X0_X1));
        Ir64Op.DataProcessing1Source op =
                (Ir64Op.DataProcessing1Source) decode(CSSC_DECODER, CTZ_X0_X1);
        assertEquals(Ir64OneSourceOp.CTZ, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src());
        assertTrue(op.wide());
    }

    @Test
    void ctzNarrowForm() {
        Ir64Op.DataProcessing1Source op =
                (Ir64Op.DataProcessing1Source) decode(CSSC_DECODER, CTZ_W0_W1);
        assertEquals(Ir64OneSourceOp.CTZ, op.opcode());
        assertFalse(op.wide());
    }

    @Test
    void ctzOpcodeWithNonZeroRmIsAutdaNotCtz() {
        // Bug real achado nesta task: `AUTDA` (`FEAT_PAuth`, ainda não implementada) mede
        // Rm(bits[20:16])=00001 e o MESMO opcode de 6 bits de `CTZ` (com Z=0) — sem checar Rm==0,
        // CTZ misdecodificaria essa forma de AUTDA. Precisa continuar recusando (G8), mesmo sob
        // CSSC.
        int autdaShaped = (CTZ_X0_X1 & ~(0b1_1111 << 16)) | (1 << 16);
        assertThrows(UnsupportedOperationException.class, () -> decode(CSSC_DECODER, autdaShaped));
    }

    // ── SMAX/SMIN/UMAX/UMIN ─────────────────────────────────────────────────────────────────────

    @Test
    void minMaxGatedByCssc() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SMAX_X0_X1_X2));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, UMAX_X0_X1_X2));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SMIN_X0_X1_X2));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, UMIN_X0_X1_X2));
    }

    @Test
    void decodesAllFourOperationsWithCorrectRegisters() {
        Ir64Op.MinMaxGeneral smax = (Ir64Op.MinMaxGeneral) decode(CSSC_DECODER, SMAX_X0_X1_X2);
        assertEquals(Ir64MinMaxOp.SMAX, smax.op());
        assertEquals(0, smax.dst());
        assertEquals(1, smax.src1());
        assertEquals(2, smax.src2());
        assertTrue(smax.wide());

        Ir64Op.MinMaxGeneral umax = (Ir64Op.MinMaxGeneral) decode(CSSC_DECODER, UMAX_X0_X1_X2);
        assertEquals(Ir64MinMaxOp.UMAX, umax.op());

        Ir64Op.MinMaxGeneral smin = (Ir64Op.MinMaxGeneral) decode(CSSC_DECODER, SMIN_X0_X1_X2);
        assertEquals(Ir64MinMaxOp.SMIN, smin.op());

        Ir64Op.MinMaxGeneral umin = (Ir64Op.MinMaxGeneral) decode(CSSC_DECODER, UMIN_X0_X1_X2);
        assertEquals(Ir64MinMaxOp.UMIN, umin.op());
    }

    @Test
    void minMaxNarrowForm() {
        Ir64Op.MinMaxGeneral op = (Ir64Op.MinMaxGeneral) decode(CSSC_DECODER, SMAX_W0_W1_W2);
        assertEquals(Ir64MinMaxOp.SMAX, op.op());
        assertFalse(op.wide());
    }

    @Test
    void reservedOpcodeInSameFieldStaysUnsupported() {
        // bits[15:10] fora de SUBP/IRG/GMI/PACGA/CRC32*/SMAX/SMIN/UMAX/UMIN é reservado (G8) —
        // ex.: 0b011100 (28), vizinho não usado por nenhuma das instruções deste campo.
        int reserved = (SMAX_X0_X1_X2 & ~(0b11_1111 << 10)) | (0b01_1100 << 10);
        assertThrows(UnsupportedOperationException.class, () -> decode(CSSC_DECODER, reserved));
    }
}
