package dev.vitorsilverio.armjitter.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/// {@link AddressSpace64#wrapping} — achado real da task F11 (`virtual-arm-box`,
/// `Raspi364Machine`, kernel `kernel8.img` real): o adapter usava `Math.toIntExact`, que rejeita
/// `[0x8000_0000, 0xFFFF_FFFF]` (não cabe num `long` assinado maior que {@link Integer#MAX_VALUE})
/// mesmo essa faixa fazendo parte dos "4 GiB baixos" que o próprio Javadoc de {@link
/// AddressSpace64#wrapping} promete suportar — endereço legítimo (unsigned 32-bit) virava
/// `ArithmeticException` espúria em vez de chegar ao barramento de 32 bits. Corrigido para truncar
/// (`(int)`, preservando o padrão de bits, mesma convenção "endereço = int sem sinal" que o resto
/// de {@link AddressSpace} já usa) e só lançar de verdade acima de `0xFFFF_FFFF`.
class AddressSpace64WrappingTest {
    /// Barramento de 32 bits trivial que só grava o último endereço `int` recebido — suficiente
    /// para provar que o padrão de bits (não o valor `long` original) chega ao barramento.
    private static final class RecordingAddressSpace implements AddressSpace {
        int lastAddress;

        @Override public int read8(int address) { lastAddress = address; return 0; }
        @Override public int read16(int address) { lastAddress = address; return 0; }
        @Override public int read32(int address) { lastAddress = address; return 0; }
        @Override public void write8(int address, int value) { lastAddress = address; }
        @Override public void write16(int address, int value) { lastAddress = address; }
        @Override public void write32(int address, int value) { lastAddress = address; }
    }

    @Test
    void readsTopHalfOfLow4GibWithoutThrowing() {
        RecordingAddressSpace inner = new RecordingAddressSpace();
        AddressSpace64 wrapping = AddressSpace64.wrapping(inner);

        wrapping.read32(0x8000_0000L);
        assertEquals(0x8000_0000, inner.lastAddress, "padrão de bits deve chegar intacto (int negativo == 0x80000000 unsigned)");

        wrapping.read32(0xFFFF_FFFFL);
        assertEquals(0xFFFF_FFFF, inner.lastAddress);
    }

    @Test
    void writesTopHalfOfLow4GibWithoutThrowing() {
        RecordingAddressSpace inner = new RecordingAddressSpace();
        AddressSpace64 wrapping = AddressSpace64.wrapping(inner);

        wrapping.write32(0xC000_0000L, 0x1234);
        assertEquals(0xC000_0000, inner.lastAddress);
    }

    @Test
    void rejectsAddressAbove4Gib() {
        AddressSpace64 wrapping = AddressSpace64.wrapping(new RecordingAddressSpace());

        assertThrows(ArithmeticException.class, () -> wrapping.read32(0x1_0000_0000L));
    }

    @Test
    void rejectsNegativeAddress() {
        AddressSpace64 wrapping = AddressSpace64.wrapping(new RecordingAddressSpace());

        assertThrows(ArithmeticException.class, () -> wrapping.read32(-1L));
    }
}
