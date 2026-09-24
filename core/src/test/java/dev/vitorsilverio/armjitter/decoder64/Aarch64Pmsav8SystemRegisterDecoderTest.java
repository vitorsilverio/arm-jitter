package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B20.8: `MRS`/`MSR (register)` dos 5 registradores de PMSAv8-64 (`op0=3,op1=0`, mesmo grupo
/// "geral" de EL1). Opcodes DERIVADOS POR FÓRMULA (mesma disciplina de
/// {@code Aarch64El2SystemRegisterDecoderTest}, sem toolchain devkitA64 disponível nesta sessão):
/// `0xD5000000 | L&lt;&lt;21 | op0&lt;&lt;19 | op1&lt;&lt;16 | CRn&lt;&lt;12 | CRm&lt;&lt;8 |
/// op2&lt;&lt;5 | Rt`, conferida campo a campo contra os exemplos REAIS já existentes na classe
/// irmã (`SCTLR_EL2` etc.) antes de gerar os valores abaixo.
///
/// **Gate G8**: sob {@link Aarch64Architecture#ARMV8_0_A} (sem {@code Aarch64Feature.PMSA}), os 5
/// registradores continuam `UNDEFINED` — mesmo padrão de `RGSR_EL1`/`GCR_EL1` (`FEAT_MTE2`).
class Aarch64Pmsav8SystemRegisterDecoderTest {
    private static final Aarch64Decoder PMSA_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_R_64);
    private static final Aarch64Decoder BASELINE_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);

    private static Ir64Op.SystemRegister decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void mrsMpuirEl1IsReadOnlyRegionCount() {
        // mrs x0, mpuir_el1 (op0=3,op1=0,CRn=0,CRm=0,op2=4,Rt=0)
        Ir64Op.SystemRegister op = decodeWord(PMSA_DECODER, 0xD5380080);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.MPUIR_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrAndMrsPrselrEl1() {
        Ir64Op.SystemRegister msr = decodeWord(PMSA_DECODER, 0xD5186220);
        assertFalse(msr.read());
        assertEquals(Aarch64SystemRegisterId.PRSELR_EL1, msr.register());

        Ir64Op.SystemRegister mrs = decodeWord(PMSA_DECODER, 0xD5386220);
        assertTrue(mrs.read());
        assertEquals(Aarch64SystemRegisterId.PRSELR_EL1, mrs.register());
    }

    @Test
    void msrAndMrsPrbarEl1() {
        Ir64Op.SystemRegister msr = decodeWord(PMSA_DECODER, 0xD5186801);
        assertFalse(msr.read());
        assertEquals(Aarch64SystemRegisterId.PRBAR_EL1, msr.register());
        assertEquals(1, msr.rt());

        Ir64Op.SystemRegister mrs = decodeWord(PMSA_DECODER, 0xD5386801);
        assertTrue(mrs.read());
        assertEquals(Aarch64SystemRegisterId.PRBAR_EL1, mrs.register());
    }

    @Test
    void msrAndMrsPrlarEl1() {
        Ir64Op.SystemRegister msr = decodeWord(PMSA_DECODER, 0xD5186821);
        assertFalse(msr.read());
        assertEquals(Aarch64SystemRegisterId.PRLAR_EL1, msr.register());

        Ir64Op.SystemRegister mrs = decodeWord(PMSA_DECODER, 0xD5386821);
        assertTrue(mrs.read());
        assertEquals(Aarch64SystemRegisterId.PRLAR_EL1, mrs.register());
    }

    @Test
    void msrAndMrsPrenrEl1() {
        Ir64Op.SystemRegister msr = decodeWord(PMSA_DECODER, 0xD5186120);
        assertFalse(msr.read());
        assertEquals(Aarch64SystemRegisterId.PRENR_EL1, msr.register());

        Ir64Op.SystemRegister mrs = decodeWord(PMSA_DECODER, 0xD5386120);
        assertTrue(mrs.read());
        assertEquals(Aarch64SystemRegisterId.PRENR_EL1, mrs.register());
    }

    @Test
    void allFiveRegistersAreUndefinedUnderArmv8_0AWithoutPmsaFeature() {
        assertThrows(RuntimeException.class, () -> decodeWord(BASELINE_DECODER, 0xD5380080)); // MPUIR_EL1
        assertThrows(RuntimeException.class, () -> decodeWord(BASELINE_DECODER, 0xD5386220)); // PRSELR_EL1
        assertThrows(RuntimeException.class, () -> decodeWord(BASELINE_DECODER, 0xD5386801)); // PRBAR_EL1
        assertThrows(RuntimeException.class, () -> decodeWord(BASELINE_DECODER, 0xD5386821)); // PRLAR_EL1
        assertThrows(RuntimeException.class, () -> decodeWord(BASELINE_DECODER, 0xD5386120)); // PRENR_EL1
    }
}
