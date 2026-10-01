package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `LD1`/`ST1` de tile, `LDR`/`STR` de `ZA` e de `ZT0` (B18.4). **Toda palavra abaixo foi conferida contra
/// `aarch64-none-elf-as -march=armv9.4-a+sme+sme2` (devkitA64)** — nenhuma calculada à mão.
class Aarch64SmeMemoryDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_2_A, "teste-mem-dec-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);

    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder SME_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);
    private static final Aarch64Decoder SME2_DECODER = new Aarch64Decoder(SME2);

    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return decoder.decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    private static Ir64Op.SmeTileLoadStore tile(int word) {
        return assertInstanceOf(Ir64Op.SmeTileLoadStore.class, decode(SME_DECODER, word));
    }

    @Test
    void ld1bHorizontalEsz0() {
        // ld1b {za0h.b[w12, 0]}, p0/z, [x0, x1]
        Ir64Op.SmeTileLoadStore op = tile(0xe0010000);
        assertFalse(op.store());
        assertFalse(op.vertical());
        assertEquals(0, op.esz());
        assertEquals(0, op.tile());
        assertEquals(0, op.pg());
        assertEquals(0, op.rn());
        assertEquals(1, op.rm());
        assertEquals(12, op.registerIndex());
        assertEquals(0, op.offset());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @Test
    void ld1bVerticalWithStackPointerBaseAndOffset() {
        // ld1b {za0v.b[w13, 5]}, p1/z, [sp, x2]
        Ir64Op.SmeTileLoadStore op = tile(0xe002a7e5);
        assertFalse(op.store());
        assertTrue(op.vertical());
        assertEquals(1, op.pg());
        assertEquals(31, op.rn());
        assertEquals(2, op.rm());
        assertEquals(13, op.registerIndex());
        assertEquals(5, op.offset());
    }

    @Test
    void st1bSelectsStoreAndAcceptsXzrIndex() {
        // st1b {za0v.b[w15, 15]}, p7, [x3, xzr]
        Ir64Op.SmeTileLoadStore op = tile(0xe03ffc6f);
        assertTrue(op.store());
        assertTrue(op.vertical());
        assertEquals(7, op.pg());
        assertEquals(3, op.rn());
        assertEquals(31, op.rm());
        assertEquals(15, op.registerIndex());
        assertEquals(15, op.offset());
    }

    @Test
    void halfwordFieldWidthsAreOneTileBitAndThreeOffsetBits() {
        // ld1h {za1h.h[w12, 0]}, p0/z, [x0, x1, lsl #1]
        Ir64Op.SmeTileLoadStore load = tile(0xe0410008);
        assertEquals(1, load.esz());
        assertEquals(1, load.tile());
        assertEquals(0, load.offset());
        // st1h {za0v.h[w14, 7]}, p2, [x4, x5, lsl #1]
        Ir64Op.SmeTileLoadStore store = tile(0xe065c887);
        assertTrue(store.store());
        assertTrue(store.vertical());
        assertEquals(0, store.tile());
        assertEquals(7, store.offset());
        assertEquals(14, store.registerIndex());
    }

    @Test
    void wordFieldWidthsAreTwoTileBitsAndTwoOffsetBits() {
        // ld1w {za3h.s[w12, 0]}, p0/z, [x0, x1, lsl #2]
        Ir64Op.SmeTileLoadStore load = tile(0xe081000c);
        assertEquals(2, load.esz());
        assertEquals(3, load.tile());
        assertEquals(0, load.offset());
        // st1w {za2v.s[w13, 3]}, p3, [x4, x5, lsl #2]
        Ir64Op.SmeTileLoadStore store = tile(0xe0a5ac8b);
        assertEquals(2, store.tile());
        assertEquals(3, store.offset());
    }

    @Test
    void doublewordFieldWidthsAreThreeTileBitsAndOneOffsetBit() {
        // ld1d {za7h.d[w12, 0]}, p0/z, [x0, x1, lsl #3]
        Ir64Op.SmeTileLoadStore load = tile(0xe0c1000e);
        assertEquals(3, load.esz());
        assertEquals(7, load.tile());
        assertEquals(0, load.offset());
        // st1d {za5v.d[w15, 1]}, p6, [x4, x5, lsl #3]
        Ir64Op.SmeTileLoadStore store = tile(0xe0e5f88b);
        assertEquals(5, store.tile());
        assertEquals(1, store.offset());
        assertEquals(6, store.pg());
    }

    @Test
    void quadwordHasFourTileBitsAndNoOffsetAndALargerBit24Prefix() {
        // ld1q {za15h.q[w12, 0]}, p0/z, [x0, x1, lsl #4]
        Ir64Op.SmeTileLoadStore load = tile(0xe1c1000f);
        assertEquals(4, load.esz());
        assertEquals(15, load.tile());
        assertEquals(0, load.offset());
        assertFalse(load.store());
        // st1q {za9v.q[w13, 0]}, p4, [sp, x5, lsl #4]
        Ir64Op.SmeTileLoadStore store = tile(0xe1e5b3e9);
        assertTrue(store.store());
        assertTrue(store.vertical());
        assertEquals(9, store.tile());
        assertEquals(31, store.rn());
        assertEquals(4, store.pg());
    }

    @Test
    void ldrAndStrOfAZaVectorUseW12ToW15ForTheIndexRegister() {
        // ldr za[w12, 0], [x0]
        Ir64Op.SmeArrayLoadStore load = assertInstanceOf(Ir64Op.SmeArrayLoadStore.class,
                decode(SME_DECODER, 0xe1000000));
        assertFalse(load.store());
        assertEquals(12, load.registerIndex());
        assertEquals(0, load.rn());
        assertEquals(0, load.imm());
        // ldr za[w15, 15], [sp, #15, mul vl]
        Ir64Op.SmeArrayLoadStore max = assertInstanceOf(Ir64Op.SmeArrayLoadStore.class,
                decode(SME_DECODER, 0xe10063ef));
        assertEquals(15, max.registerIndex());
        assertEquals(31, max.rn());
        assertEquals(15, max.imm());
        // str za[w13, 7], [x1, #7, mul vl]
        Ir64Op.SmeArrayLoadStore store = assertInstanceOf(Ir64Op.SmeArrayLoadStore.class,
                decode(SME_DECODER, 0xe1202027));
        assertTrue(store.store());
        assertEquals(13, store.registerIndex());
        assertEquals(1, store.rn());
        assertEquals(7, store.imm());
    }

    @Test
    void ldrAndStrOfZt0RequireSme2() {
        // ldr zt0, [x0]
        Ir64Op.SmeZt0LoadStore load = assertInstanceOf(Ir64Op.SmeZt0LoadStore.class, decode(SME2_DECODER, 0xe11f8000));
        assertFalse(load.store());
        assertEquals(0, load.rn());
        // str zt0, [sp]
        Ir64Op.SmeZt0LoadStore store = assertInstanceOf(Ir64Op.SmeZt0LoadStore.class, decode(SME2_DECODER, 0xe13f83e0));
        assertTrue(store.store());
        assertEquals(31, store.rn());
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xe11f8000));
    }

    @Test
    void rejectedGenericallyWithoutSme() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, 0xe0010000));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, 0xe1000000));
    }

    @Test
    void reservedNeighboursStayUnimplemented() {
        // bit 4 != 0 em LD1 (campo reservado)
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xe0010010));
        // bit 24 = 1 com esz != 3 (só o Q usa bit 24)
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xe1010000));
        // LDR ZA com bit 15 != 0 e que não é ZT0 (bits[20:15] != 111111)
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xe1008000));
        // LDR ZA com bits[12:10] != 0
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xe1000400));
        // LDR ZT0 com rn... bits[4:0] != 0
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2_DECODER, 0xe11f8001));
    }
}
