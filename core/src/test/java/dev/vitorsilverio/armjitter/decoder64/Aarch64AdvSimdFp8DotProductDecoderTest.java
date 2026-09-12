package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.11c/B19.11d (`FEAT_FP8DOT2`/`FEAT_FP8DOT4`): `FDOT_hb_v`/`FDOT_hb_vi`/`FDOT_sb_v`/
/// `FDOT_sb_vi` — decoder.
///
/// Ao contrário da B19.11b (`FEAT_FP8FMA`, sem suporte no binutils desta imagem), o assembler WSL
/// disponível nas sessões destas duas tasks (`aarch64-linux-gnu-as`/binutils 2.46, `.arch
/// armv9.5-a+fp8dot2+fp8dot4`) ACEITA os mnemônicos `fdot` de verdade (`.4h`/`.8h`/`.2s`/`.4s`,
/// forma vetorial e indexada `.2b[idx]`/`.4b[idx]`) — todos os words abaixo vêm de `objdump` real
/// sobre esse assembler, não montados à mão. `FDOT_sb_v`/`FDOT_sb_vi` (`.2s`/`.4s`) foram
/// assemblados na sessão da B19.11c (então só usados em regressão negativa ali) — a B19.11d
/// implementou o decode e virou os dois testes abaixo em positivos.
class Aarch64AdvSimdFp8DotProductDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);
    private static final Aarch64Decoder NO_FEATURE_DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op decodeWord(int word) {
        return decodeWord(DECODER, word);
    }

    // ── FDOT_hb_v ────────────────────────────────────────────────────────────────────────────────

    @Test
    void fdotHbVNotQuad() {
        // `fdot v0.4h, v1.8b, v2.8b` (objdump real, WSL binutils 2.46).
        Ir64Op.VectorFp8DotProduct op = (Ir64Op.VectorFp8DotProduct) decodeWord(0x0e42fc20);
        assertFalse(op.wideDestination());
        assertFalse(op.q());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fdotHbVQuad() {
        // `fdot v3.8h, v4.16b, v5.16b` (objdump real) — prova que `Q` é lido NORMALMENTE, ao
        // contrário de `FMLAL_hb_v` (que ignora `Q`).
        Ir64Op.VectorFp8DotProduct op = (Ir64Op.VectorFp8DotProduct) decodeWord(0x4e45fc83);
        assertFalse(op.wideDestination());
        assertTrue(op.q());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    // ── FDOT_hb_vi ───────────────────────────────────────────────────────────────────────────────

    @Test
    void fdotHbViIndexZero() {
        // `fdot v12.4h, v13.8b, v14.2b[0]` (objdump real).
        Ir64Op.VectorFp8DotProductByElement op = (Ir64Op.VectorFp8DotProductByElement) decodeWord(0x0f4e01ac);
        assertFalse(op.wideDestination());
        assertFalse(op.q());
        assertEquals(12, op.rd());
        assertEquals(13, op.rn());
        assertEquals(14, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void fdotHbViIndexThree() {
        // `fdot v15.8h, v16.16b, v14.2b[3]` (objdump real) — prova `L:M`(bits[21:20]) sem `H`.
        Ir64Op.VectorFp8DotProductByElement op = (Ir64Op.VectorFp8DotProductByElement) decodeWord(0x4f7e020f);
        assertTrue(op.q());
        assertEquals(15, op.rd());
        assertEquals(16, op.rn());
        assertEquals(14, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void fdotHbViIndexSeven() {
        // `fdot v15.8h, v16.16b, v14.2b[7]` (objdump real) — prova `H`(bit11) somado a `L:M`.
        Ir64Op.VectorFp8DotProductByElement op = (Ir64Op.VectorFp8DotProductByElement) decodeWord(0x4f7e0a0f);
        assertEquals(7, op.index());
        assertEquals(14, op.rm());
    }

    // ── Vizinhos que NÃO podem ser afetados ─────────────────────────────────────────────────────

    @Test
    void fmlalHbVStillDecodesCorrectly() {
        // `FMLAL_hb_v` (B19.11b) reusa o MESMO opcode, discriminado por `a`(bit23) — nunca deve ser
        // confundido com `FDOT_hb_v` mesmo com as duas features presentes.
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.of("test",
                dev.vitorsilverio.armjitter.arch64.Aarch64Feature.FP8,
                dev.vitorsilverio.armjitter.arch64.Aarch64Feature.FP8_FUSED_MULTIPLY_ADD,
                dev.vitorsilverio.armjitter.arch64.Aarch64Feature.FP8_DOT_PRODUCT_2WAY));
        Ir64Op fmlal = decodeWord(decoder, 0x0ec2fc20);
        assertEquals(Ir64Op.VectorFp8FusedMultiplyAddLong.class, fmlal.getClass());
        Ir64Op fdot = decodeWord(decoder, 0x0e42fc20);
        assertEquals(Ir64Op.VectorFp8DotProduct.class, fdot.getClass());
    }

    // ── FDOT_sb_v (B19.11d) ──────────────────────────────────────────────────────────────────────

    @Test
    void fdotSbVDecodes() {
        // `fdot v6.2s, v7.8b, v8.8b` (`FDOT_sb_v`, objdump real) — `bit22=0`, discriminado de
        // `FDOT_hb_v` (bit22=1, mesmo opcode).
        Ir64Op.VectorFp8DotProduct op = (Ir64Op.VectorFp8DotProduct) decodeWord(0x0e08fce6);
        assertTrue(op.wideDestination());
        assertFalse(op.q());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(8, op.rm());
    }

    // ── FDOT_sb_vi (B19.11d) ─────────────────────────────────────────────────────────────────────

    @Test
    void fdotSbViDecodes() {
        // `fdot v18.2s, v19.8b, v20.4b[0]` (`FDOT_sb_vi`, objdump real) —
        // `sizeField=HALF_PRECISION`, layout de `Rm`(5 bits)/`H:L` do ramo `WORD`, não o `H:L:M` de
        // 4 bits que `FDOT_hb_vi` usa.
        Ir64Op.VectorFp8DotProductByElement op = (Ir64Op.VectorFp8DotProductByElement) decodeWord(0x0f140272);
        assertTrue(op.wideDestination());
        assertFalse(op.q());
        assertEquals(18, op.rd());
        assertEquals(19, op.rn());
        assertEquals(20, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void fdotSbVAndFdotHbVDoNotCollide() {
        // As duas famílias (B19.11c/B19.11d) reusam o MESMO opcode/espaço, discriminadas só por
        // `bit22` — prova que nenhuma das duas rouba a outra mesmo com as duas features presentes.
        Ir64Op hb = decodeWord(0x0e42fc20); // `fdot v0.4h, v1.8b, v2.8b`
        assertFalse(((Ir64Op.VectorFp8DotProduct) hb).wideDestination());
        Ir64Op sb = decodeWord(0x0e08fce6); // `fdot v6.2s, v7.8b, v8.8b`
        assertTrue(((Ir64Op.VectorFp8DotProduct) sb).wideDestination());
    }

    @Test
    void fdotSbVRejectedWithoutFeature() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.of("test",
                dev.vitorsilverio.armjitter.arch64.Aarch64Feature.FP8,
                dev.vitorsilverio.armjitter.arch64.Aarch64Feature.FP8_DOT_PRODUCT_2WAY));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0x0e08fce6));
    }

    @Test
    void fdotSbViRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0f140272));
    }

    // ── Feature gating ──────────────────────────────────────────────────────────────────────────

    @Test
    void fdotHbVRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0e42fc20));
    }

    @Test
    void fdotHbViRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0f4e01ac));
    }
}
