package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.11 — as 6 conversões de `FEAT_FP8` (`FCVTN_bh`/`FCVTN_bs`/`F1CVTL`/`F2CVTL`/`BF1CVTL`/
/// `BF2CVTL`). Palavras golden conferidas byte a byte contra `aarch64-linux-gnu-as`/`objdump`
/// reais (`.arch armv9-a+fp8`, WSL, binutils 2.46 — suporta os mnemônicos `FEAT_FP8` diretamente,
/// ao contrário de sessões anteriores do épico B19 que precisaram montar à mão).
class Aarch64AdvSimdFp8DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder FP8_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);

    // -- golden: aarch64-linux-gnu-as -march=armv9-a+fp8 / objdump (WSL Ubuntu, binutils 2.46) --
    private static final int FCVTN_BH_D = 0x0e42f420;       // fcvtn v0.8b, v1.4h, v2.4h
    private static final int FCVTN_BH_Q = 0x4e42f420;       // fcvtn v0.16b, v1.8h, v2.8h
    private static final int FCVTN_BS = 0x0e05f483;         // fcvtn v3.8b, v4.4s, v5.4s
    private static final int FCVTN2_BS = 0x4e05f483;        // fcvtn2 v3.16b, v4.4s, v5.4s
    private static final int F1CVTL = 0x2e2178e6;           // f1cvtl v6.8h, v7.8b
    private static final int F1CVTL2 = 0x6e2178e6;          // f1cvtl2 v6.8h, v7.16b
    private static final int F2CVTL = 0x2e617928;           // f2cvtl v8.8h, v9.8b
    private static final int F2CVTL2 = 0x6e617928;          // f2cvtl2 v8.8h, v9.16b
    private static final int BF1CVTL = 0x2ea1796a;          // bf1cvtl v10.8h, v11.8b
    private static final int BF1CVTL2 = 0x6ea1796a;         // bf1cvtl2 v10.8h, v11.16b
    private static final int BF2CVTL = 0x2ee179ac;          // bf2cvtl v12.8h, v13.8b
    private static final int BF2CVTL2 = 0x6ee179ac;         // bf2cvtl2 v12.8h, v13.16b

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutFp8() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, FCVTN_BH_D));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, FCVTN_BS));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, F1CVTL));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, F2CVTL));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, BF1CVTL));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, BF2CVTL));
    }

    @Test
    void fcvtnBhDecodesHalfSourceWithQAsElementCountNotHalfSelector() {
        Ir64Op.VectorFpConvertToFp8 d = (Ir64Op.VectorFpConvertToFp8) decodeWord(FP8_DECODER, FCVTN_BH_D);
        assertEquals(true, d.halfSource());
        assertEquals(false, d.q());
        assertEquals(0, d.rd());
        assertEquals(1, d.rn());
        assertEquals(2, d.rm());

        Ir64Op.VectorFpConvertToFp8 q = (Ir64Op.VectorFpConvertToFp8) decodeWord(FP8_DECODER, FCVTN_BH_Q);
        assertEquals(true, q.halfSource());
        assertEquals(true, q.q());
    }

    @Test
    void fcvtnBsDecodesSingleSourceWithQAsHalfSelector() {
        Ir64Op.VectorFpConvertToFp8 lower = (Ir64Op.VectorFpConvertToFp8) decodeWord(FP8_DECODER, FCVTN_BS);
        assertEquals(false, lower.halfSource());
        assertEquals(false, lower.q());
        assertEquals(3, lower.rd());
        assertEquals(4, lower.rn());
        assertEquals(5, lower.rm());

        Ir64Op.VectorFpConvertToFp8 upper = (Ir64Op.VectorFpConvertToFp8) decodeWord(FP8_DECODER, FCVTN2_BS);
        assertEquals(false, upper.halfSource());
        assertEquals(true, upper.q());
    }

    @Test
    void f1cvtlDecodesFirstStreamHalfDestination() {
        Ir64Op.VectorFpConvertFromFp8 op = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, F1CVTL);
        assertEquals(false, op.secondStream());
        assertEquals(false, op.bfloat16Destination());
        assertEquals(false, op.q());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());

        Ir64Op.VectorFpConvertFromFp8 op2 = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, F1CVTL2);
        assertEquals(false, op2.secondStream());
        assertEquals(true, op2.q());
    }

    @Test
    void f2cvtlDecodesSecondStreamHalfDestination() {
        Ir64Op.VectorFpConvertFromFp8 op = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, F2CVTL);
        assertEquals(true, op.secondStream());
        assertEquals(false, op.bfloat16Destination());
        assertEquals(8, op.rd());
        assertEquals(9, op.rn());

        Ir64Op.VectorFpConvertFromFp8 op2 = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, F2CVTL2);
        assertEquals(true, op2.secondStream());
        assertEquals(true, op2.q());
    }

    @Test
    void bf1cvtlDecodesFirstStreamBfloat16Destination() {
        Ir64Op.VectorFpConvertFromFp8 op = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, BF1CVTL);
        assertEquals(false, op.secondStream());
        assertEquals(true, op.bfloat16Destination());
        assertEquals(10, op.rd());
        assertEquals(11, op.rn());

        Ir64Op.VectorFpConvertFromFp8 op2 = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, BF1CVTL2);
        assertEquals(false, op2.secondStream());
        assertEquals(true, op2.bfloat16Destination());
        assertEquals(true, op2.q());
    }

    @Test
    void bf2cvtlDecodesSecondStreamBfloat16Destination() {
        Ir64Op.VectorFpConvertFromFp8 op = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, BF2CVTL);
        assertEquals(true, op.secondStream());
        assertEquals(true, op.bfloat16Destination());
        assertEquals(12, op.rd());
        assertEquals(13, op.rn());

        Ir64Op.VectorFpConvertFromFp8 op2 = (Ir64Op.VectorFpConvertFromFp8) decodeWord(FP8_DECODER, BF2CVTL2);
        assertEquals(true, op2.secondStream());
        assertEquals(true, op2.bfloat16Destination());
        assertEquals(true, op2.q());
    }

    @Test
    void accumulationFamilyOpcodeAndABitStayUnsupported() {
        // `FDOT_hb_v` (opcode=0b11111, vizinho de FCVTN_bh que só muda o opcode em 1 bit — B19.11
        // "Não inclui": família de acumulação fica FORA desta task, tem que continuar `unsupported`
        // mesmo com FEAT_FP8 ativa). Derivado bit a bit de FCVTN_BH_D (0x0e42f420) trocando só
        // bits[15:11] de `0b11110` para `0b11111` (bit11: 0->1).
        int fdotHbV = 0x0e42fc20;
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP8_DECODER, fdotHbV));

        // Mesmo opcode de FCVTN_bh (0b11110) mas `a`(bit23)=1 — combinação reservada dentro do
        // espaço desta task (só `u=0 && a=0` é `FCVTN_bh`/`FCVTN_bs`), tem que continuar
        // `unsupported`. Derivado de FCVTN_BH_D trocando só bit23 (byte 0x42 -> 0xc2).
        int reservedABit = 0x0ec2f420;
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP8_DECODER, reservedABit));
    }

    @Test
    void fcvtlVStillDecodesUnderFp8Architecture() {
        // fcvtl v6.4s, v7.4h (u=0, mesmo opcode/slot que F1CVTL/F2CVTL com u=1) — não pode ter sido
        // afetado pelo novo ramo `u` desta task. Golden: aarch64-linux-gnu-as -march=armv8.2-a.
        int fcvtlV = 0x0e2178e6;
        Ir64Op.VectorFpConvertPrecision op =
                (Ir64Op.VectorFpConvertPrecision) decodeWord(FP8_DECODER, fcvtlV);
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp.FCVTL, op.op());
    }
}
