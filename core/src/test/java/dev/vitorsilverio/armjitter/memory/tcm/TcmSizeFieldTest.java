package dev.vitorsilverio.armjitter.memory.tcm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B20.4: decodificação do campo `Size` (Tabela 4-43/4-44 da Cortex-R5 TRM real, ARM DDI 0460D).
class TcmSizeFieldTest {
    @Test
    void decodesEntireRealSizeTable() {
        assertEquals(0, TcmSizeField.decodeBytes(0b00000));
        assertEquals(4 * 1024, TcmSizeField.decodeBytes(0b00011));
        assertEquals(8 * 1024, TcmSizeField.decodeBytes(0b00100));
        assertEquals(16 * 1024, TcmSizeField.decodeBytes(0b00101));
        assertEquals(32 * 1024, TcmSizeField.decodeBytes(0b00110));
        assertEquals(64 * 1024, TcmSizeField.decodeBytes(0b00111));
        assertEquals(128 * 1024, TcmSizeField.decodeBytes(0b01000));
        assertEquals(256 * 1024, TcmSizeField.decodeBytes(0b01001));
        assertEquals(512 * 1024, TcmSizeField.decodeBytes(0b01010));
        assertEquals(1024 * 1024, TcmSizeField.decodeBytes(0b01011));
        assertEquals(2 * 1024 * 1024, TcmSizeField.decodeBytes(0b01100));
        assertEquals(4 * 1024 * 1024, TcmSizeField.decodeBytes(0b01101));
        assertEquals(8 * 1024 * 1024, TcmSizeField.decodeBytes(0b01110));
    }

    @Test
    void reservedCodesDecodeToZero() {
        assertEquals(0, TcmSizeField.decodeBytes(0b00001));
        assertEquals(0, TcmSizeField.decodeBytes(0b00010));
        assertEquals(0, TcmSizeField.decodeBytes(0b01111));
        assertEquals(0, TcmSizeField.decodeBytes(0b11111));
    }

    @Test
    void extractsRawCodeFromBits6To2() {
        int value = 0xABCD_0000 | (0b01011 << 2) | 0b1;
        assertEquals(0b01011, TcmSizeField.extractRawCode(value));
    }

    @Test
    void encodeIsTheInverseOfDecodeForEveryRealSize() {
        for (int code = 0b00011; code <= 0b01110; code++) {
            int bytes = TcmSizeField.decodeBytes(code);
            assertEquals(code, TcmSizeField.encodeRawCode(bytes));
        }
        assertEquals(0, TcmSizeField.encodeRawCode(0));
    }

    @Test
    void encodeRejectsSizesOutsideTheRealTable() {
        assertThrows(IllegalArgumentException.class, () -> TcmSizeField.encodeRawCode(3 * 1024));
        assertThrows(IllegalArgumentException.class, () -> TcmSizeField.encodeRawCode(16 * 1024 * 1024));
    }
}
