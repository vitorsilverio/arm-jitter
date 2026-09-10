package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B19.17 — semântica de `CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}` (interpretador = oráculo, G1).
///
/// A instrução em si NÃO complementa entrada/saída (ver javadoc de {@link Ir64Op.Crc32}); os
/// vetores de teste clássicos (RFC 3720 §12.1 para Castagnoli; o vetor "123456789" padrão de
/// CRC-32/ISO-HDLC) assumem `init=0xFFFFFFFF` e complemento final por fora — reproduzidos aqui
/// encadeando `CRC32B` byte a byte sobre `"123456789"`, iniciando `Wn=~0` e invertendo o `Wd`
/// final, exatamente como o pseudocódigo de referência da ARM descreve o uso da instrução para
/// reproduzir o CRC-32 "clássico".
class Ir64Crc32ExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final byte[] CHECK_VECTOR = "123456789".getBytes(StandardCharsets.US_ASCII);
    private static final int CRC32_ISO_GOLDEN = 0xCBF43926;
    private static final int CRC32C_CASTAGNOLI_GOLDEN = 0xE3069283;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static int crc32OfCheckVector(boolean castagnoli) {
        Aarch64Core core = newCore();
        core.setX(0, 0xFFFF_FFFFL); // Wn = ~0 (init clássico)
        for (byte b : CHECK_VECTOR) {
            core.setX(2, b & 0xFFL);
            EXECUTOR.executeOp(core, new Ir64Op.Crc32(0, 0, 2, 8, castagnoli));
        }
        return (int) (~core.x(0) & 0xFFFF_FFFFL);
    }

    @Test
    void iso8023CheckVectorMatchesKnownGolden() {
        assertEquals(CRC32_ISO_GOLDEN, crc32OfCheckVector(false));
    }

    @Test
    void castagnoliCheckVectorMatchesKnownGolden() {
        assertEquals(CRC32C_CASTAGNOLI_GOLDEN, crc32OfCheckVector(true));
    }

    @Test
    void crc32AndCrc32cDifferForSameInput() {
        assertNotEquals(crc32OfCheckVector(false), crc32OfCheckVector(true),
                "polinômios diferentes não podem produzir o mesmo checksum aqui");
    }

    @Test
    void doublewordFormMatchesChainingEightBytes() {
        // CRC32X consome os 8 bytes de Xm numa só instrução; deve produzir o MESMO resultado que
        // encadear CRC32B byte a byte na mesma ordem (byte 0 = bits[7:0], ..., byte 7 = bits[63:56]).
        Aarch64Core chained = newCore();
        chained.setX(0, 123L);
        long packed = 0;
        for (int i = 0; i < 8; i++) {
            int value = (i + 1) * 7;
            packed |= (value & 0xFFL) << (i * 8);
            chained.setX(2, value & 0xFFL);
            EXECUTOR.executeOp(chained, new Ir64Op.Crc32(0, 0, 2, 8, false));
        }

        Aarch64Core single = newCore();
        single.setX(0, 123L);
        single.setX(2, packed);
        EXECUTOR.executeOp(single, new Ir64Op.Crc32(0, 0, 2, 64, false));

        assertEquals(chained.x(0), single.x(0));
    }

    @Test
    void widthOnlyAffectsDataBytesConsumedNotAccumulatorWidth() {
        Aarch64Core core = newCore();
        core.setX(0, 0);
        core.setX(2, 0x1234L);
        EXECUTOR.executeOp(core, new Ir64Op.Crc32(0, 0, 2, 8, false)); // só o byte 0x34
        long crcAfterByte = core.x(0);

        Aarch64Core core2 = newCore();
        core2.setX(0, 0);
        core2.setX(2, 0x1234L);
        EXECUTOR.executeOp(core2, new Ir64Op.Crc32(0, 0, 2, 16, false)); // 0x34 seguido de 0x12
        assertNotEquals(crcAfterByte, core2.x(0), "CRC32H processa 2 bytes, não pode bater com CRC32B");
    }
}
