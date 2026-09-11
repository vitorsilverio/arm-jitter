package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.14 — `FEAT_MTE2` (`STG`/`LDG`/`STZG`/`ST2G`/`STZ2G`/`STGM`/`LDGM`/`STZGM`/`STGP`/`SUBP`/
/// `SUBPS`/`IRG`/`GMI`, 23 células deste arquivo — `SETGP`/`SETGM`/`SETGE` ficam em
/// `Aarch64MemoryCopySetDecoderTest`, mesmo espaço `@set` de `SETP`/`SETM`/`SETE`). Vetores golden
/// conferidos com `aarch64-linux-gnu-as -march=armv8.8-a+memtag+mops` (WSL Ubuntu).
class Aarch64MemoryTagDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder MTE_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_5_A);

    private static final int STG_OFFSET = 0xd9200820;      // stg x0, [x1]
    private static final int STG_PRE = 0xd9202c62;         // stg x2, [x3, #32]!
    private static final int STG_POST = 0xd93ff4a4;        // stg x4, [x5], #-16
    private static final int LDG = 0xd96030e6;             // ldg x6, [x7, #48]
    private static final int STZG_OFFSET = 0xd9600928;     // stzg x8, [x9]
    private static final int STZG_PRE = 0xd9601d6a;        // stzg x10, [x11, #16]!
    private static final int STZG_POST = 0xd97fe5ac;       // stzg x12, [x13], #-32
    private static final int ST2G_OFFSET = 0xd9a009ee;     // st2g x14, [x15]
    private static final int ST2G_PRE = 0xd9a02e30;        // st2g x16, [x17, #32]!
    private static final int ST2G_POST = 0xd9bff672;       // st2g x18, [x19], #-16
    private static final int STZ2G_OFFSET = 0xd9e00ab4;    // stz2g x20, [x21]
    private static final int STZ2G_PRE = 0xd9e01ef6;       // stz2g x22, [x23, #16]!
    private static final int STZ2G_POST = 0xd9ffe738;      // stz2g x24, [x25], #-32
    private static final int STGM = 0xd9a00020;            // stgm x0, [x1]
    private static final int LDGM = 0xd9e00062;            // ldgm x2, [x3]
    private static final int STZGM = 0xd92000a4;           // stzgm x4, [x5]
    private static final int STGP_OFFSET = 0x69000440;     // stgp x0, x1, [x2]
    private static final int STGP_PRE = 0x698110a3;        // stgp x3, x4, [x5, #32]!
    private static final int STGP_POST = 0x68bf9d06;       // stgp x6, x7, [x8], #-16
    private static final int SUBP = 0x9ac20020;            // subp x0, x1, x2
    private static final int SUBPS = 0xbac50083;           // subps x3, x4, x5
    private static final int IRG = 0x9ac21020;             // irg x0, x1, x2
    private static final int GMI = 0x9ac21420;             // gmi x0, x1, x2

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByMemoryTaggingFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, STG_OFFSET));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, LDG));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, STGM));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, STGP_OFFSET));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SUBP));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, SUBPS));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, IRG));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, GMI));
    }

    @Test
    void decodesStgOffsetPreAndPostIndex() {
        Ir64Op.MemoryTag offset = (Ir64Op.MemoryTag) decode(MTE_DECODER, STG_OFFSET);
        assertEquals(Ir64Op.Ir64MemoryTagOperation.STORE, offset.operation());
        assertEquals(false, offset.zeroData());
        assertEquals(1, offset.granules());
        assertEquals(0, offset.rt());
        assertEquals(1, offset.rn());
        assertEquals(Ir64AddressingMode.OFFSET, offset.addressingMode());
        assertEquals(0L, offset.immediate());

        Ir64Op.MemoryTag pre = (Ir64Op.MemoryTag) decode(MTE_DECODER, STG_PRE);
        assertEquals(2, pre.rt());
        assertEquals(3, pre.rn());
        assertEquals(Ir64AddressingMode.PRE_INDEX, pre.addressingMode());
        assertEquals(32L, pre.immediate());

        Ir64Op.MemoryTag post = (Ir64Op.MemoryTag) decode(MTE_DECODER, STG_POST);
        assertEquals(4, post.rt());
        assertEquals(5, post.rn());
        assertEquals(Ir64AddressingMode.POST_INDEX, post.addressingMode());
        assertEquals(-16L, post.immediate());
    }

    @Test
    void decodesLdgAsSingleOffsetFormWithoutWriteback() {
        Ir64Op.MemoryTag ldg = (Ir64Op.MemoryTag) decode(MTE_DECODER, LDG);
        assertEquals(Ir64Op.Ir64MemoryTagOperation.LOAD, ldg.operation());
        assertEquals(false, ldg.zeroData());
        assertEquals(1, ldg.granules());
        assertEquals(6, ldg.rt());
        assertEquals(7, ldg.rn());
        assertEquals(Ir64AddressingMode.OFFSET, ldg.addressingMode());
        assertEquals(48L, ldg.immediate());
    }

    @Test
    void decodesStzgZeroingFlagAndAllAddressingModes() {
        Ir64Op.MemoryTag offset = (Ir64Op.MemoryTag) decode(MTE_DECODER, STZG_OFFSET);
        assertEquals(true, offset.zeroData());
        assertEquals(1, offset.granules());
        assertEquals(8, offset.rt());
        assertEquals(9, offset.rn());
        assertEquals(Ir64AddressingMode.OFFSET, offset.addressingMode());

        Ir64Op.MemoryTag pre = (Ir64Op.MemoryTag) decode(MTE_DECODER, STZG_PRE);
        assertEquals(Ir64AddressingMode.PRE_INDEX, pre.addressingMode());
        assertEquals(16L, pre.immediate());

        Ir64Op.MemoryTag post = (Ir64Op.MemoryTag) decode(MTE_DECODER, STZG_POST);
        assertEquals(Ir64AddressingMode.POST_INDEX, post.addressingMode());
        assertEquals(-32L, post.immediate());
    }

    @Test
    void decodesSt2gAndStz2gAsTwoGranuleForms() {
        Ir64Op.MemoryTag st2g = (Ir64Op.MemoryTag) decode(MTE_DECODER, ST2G_OFFSET);
        assertEquals(false, st2g.zeroData());
        assertEquals(2, st2g.granules());
        assertEquals(14, st2g.rt());
        assertEquals(15, st2g.rn());

        assertEquals(Ir64AddressingMode.PRE_INDEX, ((Ir64Op.MemoryTag) decode(MTE_DECODER, ST2G_PRE)).addressingMode());
        assertEquals(Ir64AddressingMode.POST_INDEX, ((Ir64Op.MemoryTag) decode(MTE_DECODER, ST2G_POST)).addressingMode());

        Ir64Op.MemoryTag stz2g = (Ir64Op.MemoryTag) decode(MTE_DECODER, STZ2G_OFFSET);
        assertEquals(true, stz2g.zeroData());
        assertEquals(2, stz2g.granules());
        assertEquals(20, stz2g.rt());
        assertEquals(21, stz2g.rn());

        assertEquals(Ir64AddressingMode.PRE_INDEX, ((Ir64Op.MemoryTag) decode(MTE_DECODER, STZ2G_PRE)).addressingMode());
        assertEquals(Ir64AddressingMode.POST_INDEX, ((Ir64Op.MemoryTag) decode(MTE_DECODER, STZ2G_POST)).addressingMode());
    }

    @Test
    void decodesMultipleFormsStgmLdgmStzgm() {
        Ir64Op.MemoryTagMultiple stgm = (Ir64Op.MemoryTagMultiple) decode(MTE_DECODER, STGM);
        assertEquals(Ir64Op.Ir64MemoryTagMultipleOperation.STORE_TAGS, stgm.operation());
        assertEquals(0, stgm.rt());
        assertEquals(1, stgm.rn());

        Ir64Op.MemoryTagMultiple ldgm = (Ir64Op.MemoryTagMultiple) decode(MTE_DECODER, LDGM);
        assertEquals(Ir64Op.Ir64MemoryTagMultipleOperation.LOAD_TAGS, ldgm.operation());
        assertEquals(2, ldgm.rt());
        assertEquals(3, ldgm.rn());

        Ir64Op.MemoryTagMultiple stzgm = (Ir64Op.MemoryTagMultiple) decode(MTE_DECODER, STZGM);
        assertEquals(Ir64Op.Ir64MemoryTagMultipleOperation.STORE_ZERO_DATA_TAGS, stzgm.operation());
        assertEquals(4, stzgm.rt());
        assertEquals(5, stzgm.rn());
    }

    @Test
    void decodesStgpWithAllAddressingModes() {
        Ir64Op.StorePairTag offset = (Ir64Op.StorePairTag) decode(MTE_DECODER, STGP_OFFSET);
        assertEquals(0, offset.rt());
        assertEquals(1, offset.rt2());
        assertEquals(2, offset.rn());
        assertEquals(Ir64AddressingMode.OFFSET, offset.addressingMode());
        assertEquals(0L, offset.immediate());

        Ir64Op.StorePairTag pre = (Ir64Op.StorePairTag) decode(MTE_DECODER, STGP_PRE);
        assertEquals(3, pre.rt());
        assertEquals(4, pre.rt2());
        assertEquals(5, pre.rn());
        assertEquals(Ir64AddressingMode.PRE_INDEX, pre.addressingMode());
        assertEquals(32L, pre.immediate());

        Ir64Op.StorePairTag post = (Ir64Op.StorePairTag) decode(MTE_DECODER, STGP_POST);
        assertEquals(6, post.rt());
        assertEquals(7, post.rt2());
        assertEquals(8, post.rn());
        assertEquals(Ir64AddressingMode.POST_INDEX, post.addressingMode());
        assertEquals(-16L, post.immediate());
    }

    @Test
    void decodesSubpSubpsIrgGmi() {
        Ir64Op.SubtractPointer subp = (Ir64Op.SubtractPointer) decode(MTE_DECODER, SUBP);
        assertEquals(false, subp.setFlags());
        assertEquals(0, subp.rd());
        assertEquals(1, subp.rn());
        assertEquals(2, subp.rm());

        Ir64Op.SubtractPointer subps = (Ir64Op.SubtractPointer) decode(MTE_DECODER, SUBPS);
        assertEquals(true, subps.setFlags());
        assertEquals(3, subps.rd());
        assertEquals(4, subps.rn());
        assertEquals(5, subps.rm());

        Ir64Op.InsertRandomTag irg = (Ir64Op.InsertRandomTag) decode(MTE_DECODER, IRG);
        assertEquals(0, irg.rd());
        assertEquals(1, irg.rn());
        assertEquals(2, irg.rm());

        Ir64Op.TagMaskInsert gmi = (Ir64Op.TagMaskInsert) decode(MTE_DECODER, GMI);
        assertEquals(0, gmi.rd());
        assertEquals(1, gmi.rn());
        assertEquals(2, gmi.rm());
    }
}
