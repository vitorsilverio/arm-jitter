package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B11.2: fiação de {@link Aarch64Architecture} no {@link Aarch64Decoder} — sem gate de decode
/// ainda (isso é B11.4), então esta task só prova que a arquitetura é aceita/exposta e que o
/// comportamento de decode é IDÊNTICO independente de qual arquitetura foi passada (zero-diff, G3).
class Aarch64DecoderArchitectureWiringTest {
    /// `MOVZ X0, #0x1234` (`aarch64-none-elf-as`) — qualquer encoding simples já coberto pelo
    /// corpus de B6.1 serve, o ponto aqui não é decode novo, é confirmar zero-diff.
    private static final int MOVZ_X0_0x1234 = 0xD2824680;

    @Test
    void noArgConstructorDefaultsToArmv8_0A() {
        Aarch64Decoder decoder = new Aarch64Decoder();
        assertEquals(Aarch64Architecture.ARMV8_0_A, decoder.architecture());
    }

    @Test
    void explicitArchitectureIsExposed() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);
        assertEquals(Aarch64Architecture.ARMV9_5_A, decoder.architecture());
    }

    @Test
    void constructorRejectsNullArchitecture() {
        assertThrows(NullPointerException.class, () -> new Aarch64Decoder(null));
    }

    @Test
    void decodeIsIdenticalRegardlessOfArchitecture() {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(4));
        memory.write32(0, MOVZ_X0_0x1234);

        Ir64Op fromDefault = new Aarch64Decoder().decode(memory, 0);
        Ir64Op fromArmv8_0A = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A).decode(memory, 0);
        Ir64Op fromArmv9_5A = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A).decode(memory, 0);

        assertEquals(fromDefault, fromArmv8_0A);
        assertEquals(fromDefault, fromArmv9_5A);
    }
}
