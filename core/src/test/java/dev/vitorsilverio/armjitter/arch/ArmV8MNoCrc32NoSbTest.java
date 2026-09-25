package dev.vitorsilverio.armjitter.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B22.8 — `CRC32*` e `SB` não existem no perfil M (ARM DDI 0553B.y não cita nenhum dos dois, e
/// `ID_ISAR5` só define `PACBTI`). A curadoria vive em `docs/isa-nao-aplicavel.tsv`; aqui se prova
/// que o encoding sob os presets ARMv8-M é RECUSADO (G8), não confundido com outra instrução.
class ArmV8MNoCrc32NoSbTest {
    private static final List<ArmArchitecture> V8_M_PRESETS = List.of(ArmArchitecture.ARMV8M_BASELINE,
            ArmArchitecture.ARMV8M_MAINLINE, ArmArchitecture.ARMV8_1M, ArmArchitecture.ARMV8_1M_MVE);

    /// `CRC32B r0, r1, r2` — T32 `1111 1010 1100 Rn | 1111 Rd 10 sz Rm`.
    private static final int CRC32B_HW1 = 0xFAC1;
    private static final int CRC32B_HW2 = 0xF082;
    /// `SB` — T32 `1111 0011 1011 1111 | 1000 1111 0111 0000`.
    private static final int SB_HW1 = 0xF3BF;
    private static final int SB_HW2 = 0x8F70;

    private static InstructionKind decodeKind(ArmArchitecture architecture, int hw1, int hw2) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put16(0, hw1);
        memory.put16(2, hw2);
        return new ThumbDecoder(architecture).decode(memory, 0).kind();
    }

    @Test
    void armv8MPresetsDoNotDeclareCrc32() {
        for (ArmArchitecture architecture : V8_M_PRESETS) {
            assertFalse(architecture.has(ArmFeature.CRC32), architecture.name());
        }
    }

    @Test
    void crc32AndSbAreRefusedUnderEveryArmv8MPreset() {
        for (ArmArchitecture architecture : V8_M_PRESETS) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeKind(architecture, CRC32B_HW1, CRC32B_HW2),
                    "CRC32B " + architecture.name());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeKind(architecture, SB_HW1, SB_HW2),
                    "SB " + architecture.name());
        }
    }
}
