package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B9.13 — `MCR`/`MRC` sob `ARMV4T`: são ARMv3+ (mais antigas que o próprio ARMv4T), então
/// `ArmArchitecture.ARMV4T` deve decodificá-las, ao contrário de `MCRR`/`MRRC` (ARMv5TE/extensão
/// "E"), que continuam exclusivas de `ARMV5TE`+ — a separação entre {@link CoprocessorRegisterDecoder}
/// (simples) e {@link CoprocessorDecoder} (duplo, delega ao primeiro) é o que evita `ARMV4T` ganhar
/// `MCRR`/`MRRC` por acidente (violaria G2).
class CoprocessorRegisterDecoderTest {
    // MCR p15, 0, r1, c9, c1, 0 e MRC p15, 0, r2, c9, c1, 0 — mesmo layout de CoprocessorTest.
    private static final int MCR_P15_R1_C9_C1_0 = 0xEE09_1F11;
    private static final int MRC_P15_R2_C9_C1_0 = 0xEE19_2F11;
    private static final int MCRR_P15_R1_R2_C6 = 0xEC42_1F06;
    private static final int MRRC_P15_R1_R2_C6 = 0xEC52_1F06;

    @Test
    void armv4tDecodesMcrAsCoprocessor() {
        DecodedInstruction decoded = decode(ArmArchitecture.ARMV4T, MCR_P15_R1_C9_C1_0);

        assertEquals(InstructionKind.COPROCESSOR, decoded.kind());
        assertEquals(1, decoded.destinationRegister()); // Rt/Rd = r1
        assertEquals(9, decoded.sourceRegister()); // CRn
        assertEquals(1, decoded.secondSourceRegister()); // CRm
    }

    @Test
    void armv4tDecodesMrcAsCoprocessor() {
        DecodedInstruction decoded = decode(ArmArchitecture.ARMV4T, MRC_P15_R2_C9_C1_0);

        assertEquals(InstructionKind.COPROCESSOR, decoded.kind());
        assertEquals(2, decoded.destinationRegister());
    }

    @Test
    void armv4tStillRejectsMcrrAndMrrc() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV4T, MCRR_P15_R1_R2_C6).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV4T, MRRC_P15_R1_R2_C6).kind());
    }

    @Test
    void armv5teStillDecodesAllFourUnchanged() {
        assertEquals(InstructionKind.COPROCESSOR, decode(ArmArchitecture.ARMV5TE, MCR_P15_R1_C9_C1_0).kind());
        assertEquals(InstructionKind.COPROCESSOR, decode(ArmArchitecture.ARMV5TE, MRC_P15_R2_C9_C1_0).kind());
        assertEquals(InstructionKind.COPROCESSOR_DOUBLE, decode(ArmArchitecture.ARMV5TE, MCRR_P15_R1_R2_C6).kind());
        assertEquals(InstructionKind.COPROCESSOR_DOUBLE, decode(ArmArchitecture.ARMV5TE, MRRC_P15_R1_R2_C6).kind());
    }

    private static DecodedInstruction decode(ArmArchitecture architecture, int raw) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, raw);
        return new ArmDecoder(architecture).decode(memory, 0);
    }
}
