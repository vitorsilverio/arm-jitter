package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regressão (F11, Raspberry Pi 3 `kernel8.img`): `LDR`/`STR` X de offset imediato "unsigned" com
/// `imm12` grande tem os bits `[11:10]` (parte baixa do `imm12`) e `bit21` (MSB do `imm12`)
/// coincidindo com o que os modos pré/pós-indexado e `LDRAA/LDRAB` usam em OUTRA forma. A
/// interceptação de `LDRA` não olhava `bit24` (forma "unsigned offset") e `ldr x0, [x0, #31624]`
/// (`0xf97dc400`) era recusada como `LDRA` sem `FEAT_PAuth`. Os offsets abaixo conferidos com
/// `aarch64-none-elf-objdump` (devkitA64).
class Aarch64UnsignedOffsetLoadStoreDecoderTest {
    private static final Aarch64Architecture[] ARCHITECTURES = {
            Aarch64Architecture.ARMV8_0_A, Aarch64Architecture.ARMV8_3_A};

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return new Aarch64Decoder(architecture).decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void largeUnsignedOffsetLoadWithBit21AndPostIndexLookalikeBitsIsAPlainLdr() {
        for (Aarch64Architecture architecture : ARCHITECTURES) {
            // ldr x0, [x0, #31624]: imm12=0xF71 -> bits[11:10]=01, bit21=1 (parecia LDRA post-index)
            Ir64Op.Load64 load = (Ir64Op.Load64) decode(architecture, 0xf97dc400);
            assertEquals(Ir64AddressingMode.OFFSET, load.addressingMode(), architecture.toString());
            assertEquals(31624L, load.immediate());
            assertEquals(0, load.rt());
            assertEquals(0, load.rn());
        }
    }

    @Test
    void largeUnsignedOffsetLoadWithPreIndexLookalikeBitsIsAPlainLdr() {
        for (Aarch64Architecture architecture : ARCHITECTURES) {
            // ldr x0, [x0, #31640]: imm12=0xF73 -> bits[11:10]=11 (parecia LDRA pre-index)
            Ir64Op.Load64 load = (Ir64Op.Load64) decode(architecture, 0xf97dcc00);
            assertEquals(Ir64AddressingMode.OFFSET, load.addressingMode(), architecture.toString());
            assertEquals(31640L, load.immediate());
        }
    }

    @Test
    void sameShapeStoresAreStillStores() {
        for (Aarch64Architecture architecture : ARCHITECTURES) {
            // str x0, [x0, #31624] / #31640
            Ir64Op.Store64 first = (Ir64Op.Store64) decode(architecture, 0xf93dc400);
            Ir64Op.Store64 second = (Ir64Op.Store64) decode(architecture, 0xf93dcc00);
            assertEquals(31624L, first.immediate());
            assertEquals(31640L, second.immediate());
            assertEquals(Ir64AddressingMode.OFFSET, first.addressingMode());
        }
    }

    @Test
    void ldraaOutsideTheUnsignedOffsetFormStillNeedsPointerAuthentication() {
        // ldraa x0, [x0] (0xf8200400): bit24=0, bit21=1, idx=01 — continua sendo LDRA
        assertTrue(decode(Aarch64Architecture.ARMV8_3_A, 0xf8200400) != null);
        // ldraa x0, [x0, #0]! (0xf8200c00): idx=11 (pre-index/writeback) idem
        assertTrue(decode(Aarch64Architecture.ARMV8_3_A, 0xf8200c00) != null);
        try {
            decode(Aarch64Architecture.ARMV8_0_A, 0xf8200400);
            throw new AssertionError("LDRAA sem FEAT_PAuth deveria ser recusado");
        } catch (UnsupportedOperationException expected) {
            // correto
        }
    }
}
