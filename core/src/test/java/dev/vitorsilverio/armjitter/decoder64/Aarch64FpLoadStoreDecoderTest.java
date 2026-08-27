package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `LDR`/`STR` SIMD&FP registrador-imediato (`B`/`H`/`S`/`D`/`Q`), `LDP`/`STP` SIMD&FP e
/// `LDR (literal)` SIMD&FP (B8.13) — última fatia da classe "Loads and stores" que faltava
/// (`decodeLoadsAndStores` recusava todo o espaço `V=1` fora da AdvSIMD estruturada da B8.6, ver
/// achado real da sessão B6.2/aarch64-busybox 2026-08-27). Corpus PRÓPRIO via
/// `aarch64-none-elf-as`/`objdump` reais (devkitA64), mesmo padrão de
/// {@code Aarch64AdvSimdLoadStoreDecoderTest} — cada `@Test` documenta a linha exata do `objdump`.
class Aarch64FpLoadStoreDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeAt(long address, int word) {
        TestAddressSpace raw = new TestAddressSpace((int) address + 4);
        raw.put32((int) address, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), address);
    }

    private static Ir64Op.FpLoad64 load(int word) {
        return (Ir64Op.FpLoad64) decodeAt(0, word);
    }

    private static Ir64Op.FpStore64 store(int word) {
        return (Ir64Op.FpStore64) decodeAt(0, word);
    }

    // ── Unsigned offset (scaled) ────────────────────────────────────────────────────────────────

    @Test
    void ldrByteUnsignedOffset() {
        // 3d401420: ldr b0, [x1, #5]
        Ir64Op.FpLoad64 op = load(0x3d401420);
        assertEquals(0, op.vt());
        assertEquals(1, op.rn());
        assertEquals(Ir64FpMemSize.BYTE, op.size());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(5L, op.immediate());
    }

    @Test
    void ldrHalfUnsignedOffset() {
        // 7d401420: ldr h0, [x1, #10]
        Ir64Op.FpLoad64 op = load(0x7d401420);
        assertEquals(Ir64FpMemSize.HALF, op.size());
        assertEquals(10L, op.immediate());
    }

    @Test
    void ldrSingleUnsignedOffset() {
        // bd401420: ldr s0, [x1, #20]
        Ir64Op.FpLoad64 op = load(0xbd401420);
        assertEquals(Ir64FpMemSize.SINGLE, op.size());
        assertEquals(20L, op.immediate());
    }

    @Test
    void ldrDoubleUnsignedOffset() {
        // fd401420: ldr d0, [x1, #40]
        Ir64Op.FpLoad64 op = load(0xfd401420);
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(40L, op.immediate());
    }

    @Test
    void ldrQuadUnsignedOffset() {
        // 3dc01420: ldr q0, [x1, #80]
        Ir64Op.FpLoad64 op = load(0x3dc01420);
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(80L, op.immediate());
    }

    @Test
    void strByteUnsignedOffset() {
        // 3d001420: str b0, [x1, #5]
        Ir64Op.FpStore64 op = store(0x3d001420);
        assertEquals(0, op.vt());
        assertEquals(1, op.rn());
        assertEquals(Ir64FpMemSize.BYTE, op.size());
        assertEquals(5L, op.immediate());
    }

    @Test
    void strHalfUnsignedOffset() {
        // 7d001420: str h0, [x1, #10]
        assertEquals(Ir64FpMemSize.HALF, store(0x7d001420).size());
    }

    @Test
    void strSingleUnsignedOffset() {
        // bd001420: str s0, [x1, #20]
        assertEquals(Ir64FpMemSize.SINGLE, store(0xbd001420).size());
    }

    @Test
    void strDoubleUnsignedOffset() {
        // fd001420: str d0, [x1, #40]
        assertEquals(Ir64FpMemSize.DOUBLE, store(0xfd001420).size());
    }

    @Test
    void strQuadUnsignedOffset() {
        // 3d801420: str q0, [x1, #80]
        Ir64Op.FpStore64 op = store(0x3d801420);
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(80L, op.immediate());
    }

    // ── Unscaled (LDUR/STUR) ────────────────────────────────────────────────────────────────────

    @Test
    void ldurByteNegativeOffset() {
        // 3c5ff062: ldur b2, [x3, #-1]
        Ir64Op.FpLoad64 op = load(0x3c5ff062);
        assertEquals(2, op.vt());
        assertEquals(3, op.rn());
        assertEquals(Ir64FpMemSize.BYTE, op.size());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(-1L, op.immediate());
    }

    @Test
    void ldurDoublePositiveOffset() {
        // fc405062: ldur d2, [x3, #5]
        Ir64Op.FpLoad64 op = load(0xfc405062);
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(5L, op.immediate());
    }

    @Test
    void ldurQuadNegativeOffset() {
        // 3cdf0062: ldur q2, [x3, #-16]
        Ir64Op.FpLoad64 op = load(0x3cdf0062);
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(-16L, op.immediate());
    }

    @Test
    void sturHalfPositiveOffset() {
        // 7c003062: stur h2, [x3, #3]
        Ir64Op.FpStore64 op = store(0x7c003062);
        assertEquals(Ir64FpMemSize.HALF, op.size());
        assertEquals(3L, op.immediate());
    }

    // ── Pre/post-index ──────────────────────────────────────────────────────────────────────────

    @Test
    void strDoublePreIndex() {
        // fc008ca4: str d4, [x5, #8]!
        Ir64Op.FpStore64 op = store(0xfc008ca4);
        assertEquals(4, op.vt());
        assertEquals(5, op.rn());
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void ldrQuadPreIndexNegative() {
        // 3cdf0ca4: ldr q4, [x5, #-16]!
        Ir64Op.FpLoad64 op = load(0x3cdf0ca4);
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(-16L, op.immediate());
    }

    @Test
    void strSinglePostIndex() {
        // bc0044e6: str s6, [x7], #4
        Ir64Op.FpStore64 op = store(0xbc0044e6);
        assertEquals(6, op.vt());
        assertEquals(7, op.rn());
        assertEquals(Ir64FpMemSize.SINGLE, op.size());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(4L, op.immediate());
    }

    @Test
    void ldrQuadPostIndex() {
        // 3cc104e6: ldr q6, [x7], #16
        Ir64Op.FpLoad64 op = load(0x3cc104e6);
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(16L, op.immediate());
    }

    // ── Registrador-offset ──────────────────────────────────────────────────────────────────────

    @Test
    void ldrDoubleRegisterOffsetPlainLsl() {
        // fc6a6928: ldr d8, [x9, x10]
        Ir64Op.FpLoad64 op = load(0xfc6a6928);
        assertEquals(8, op.vt());
        assertEquals(9, op.rn());
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(Ir64AddressingMode.REGISTER_OFFSET, op.addressingMode());
        assertEquals(10, op.rm());
        assertEquals(Ir64ExtendType.LSL, op.extendType());
        assertEquals(0, op.shiftAmount());
    }

    @Test
    void ldrQuadRegisterOffsetShifted() {
        // 3cea7928: ldr q8, [x9, x10, lsl #4]
        Ir64Op.FpLoad64 op = load(0x3cea7928);
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(Ir64ExtendType.LSL, op.extendType());
        assertEquals(4, op.shiftAmount());
    }

    @Test
    void strSingleRegisterOffsetUxtw() {
        // bc2a492b: str s11, [x9, w10, uxtw]
        Ir64Op.FpStore64 op = store(0xbc2a492b);
        assertEquals(11, op.vt());
        assertEquals(9, op.rn());
        assertEquals(Ir64FpMemSize.SINGLE, op.size());
        assertEquals(Ir64AddressingMode.REGISTER_OFFSET, op.addressingMode());
        assertEquals(10, op.rm());
        assertEquals(Ir64ExtendType.UXTW, op.extendType());
        assertEquals(0, op.shiftAmount());
    }

    @Test
    void ldrByteRegisterOffsetNoImplicitShift() {
        // 3c6a692c: ldr b12, [x9, x10] — byte nunca escala (log2Bytes=0 mesmo com bit "S" setado)
        Ir64Op.FpLoad64 op = load(0x3c6a692c);
        assertEquals(12, op.vt());
        assertEquals(Ir64FpMemSize.BYTE, op.size());
        assertEquals(0, op.shiftAmount());
    }

    @Test
    void strHalfRegisterOffsetShifted() {
        // 7c2a792d: str h13, [x9, x10, lsl #1]
        Ir64Op.FpStore64 op = store(0x7c2a792d);
        assertEquals(13, op.vt());
        assertEquals(Ir64FpMemSize.HALF, op.size());
        assertEquals(1, op.shiftAmount());
    }

    // ── LDP/STP ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void ldpSingleOffsetZero() {
        // 2d403c4e: ldp s14, s15, [x2]
        Ir64Op.FpLoadStorePair op = (Ir64Op.FpLoadStorePair) decodeAt(0, 0x2d403c4e);
        assertEquals(true, op.load());
        assertEquals(14, op.vt());
        assertEquals(15, op.vt2());
        assertEquals(2, op.rn());
        assertEquals(Ir64FpMemSize.SINGLE, op.size());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
    }

    @Test
    void stpDoubleOffset() {
        // 6d013c4e: stp d14, d15, [x2, #16]
        Ir64Op.FpLoadStorePair op = (Ir64Op.FpLoadStorePair) decodeAt(0, 0x6d013c4e);
        assertEquals(false, op.load());
        assertEquals(14, op.vt());
        assertEquals(15, op.vt2());
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(16L, op.immediate());
    }

    @Test
    void ldpQuadOffset() {
        // ad413c4e: ldp q14, q15, [x2, #32]
        Ir64Op.FpLoadStorePair op = (Ir64Op.FpLoadStorePair) decodeAt(0, 0xad413c4e);
        assertEquals(true, op.load());
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(32L, op.immediate());
    }

    @Test
    void stpSinglePreIndex() {
        // 2d814450: stp s16, s17, [x2, #8]!
        Ir64Op.FpLoadStorePair op = (Ir64Op.FpLoadStorePair) decodeAt(0, 0x2d814450);
        assertEquals(16, op.vt());
        assertEquals(17, op.vt2());
        assertEquals(Ir64FpMemSize.SINGLE, op.size());
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void ldpDoublePostIndex() {
        // 6cc14450: ldp d16, d17, [x2], #16
        Ir64Op.FpLoadStorePair op = (Ir64Op.FpLoadStorePair) decodeAt(0, 0x6cc14450);
        assertEquals(true, op.load());
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(16L, op.immediate());
    }

    // ── LDR (literal) ───────────────────────────────────────────────────────────────────────────

    @Test
    void ldrLiteralSingle() {
        // 1c000074: ldr s20, 7c <litS>  (instrução em 0x70)
        Ir64Op.FpLoadLiteral64 op = (Ir64Op.FpLoadLiteral64) decodeAt(0x70, 0x1c000074);
        assertEquals(20, op.vt());
        assertEquals(Ir64FpMemSize.SINGLE, op.size());
        assertEquals(0x7cL, op.address());
    }

    @Test
    void ldrLiteralDouble() {
        // 5c000074: ldr d20, 80 <litD>  (instrução em 0x74)
        Ir64Op.FpLoadLiteral64 op = (Ir64Op.FpLoadLiteral64) decodeAt(0x74, 0x5c000074);
        assertEquals(20, op.vt());
        assertEquals(Ir64FpMemSize.DOUBLE, op.size());
        assertEquals(0x80L, op.address());
    }

    @Test
    void ldrLiteralQuad() {
        // 9c000094: ldr q20, 88 <litQ>  (instrução em 0x78)
        Ir64Op.FpLoadLiteral64 op = (Ir64Op.FpLoadLiteral64) decodeAt(0x78, 0x9c000094);
        assertEquals(20, op.vt());
        assertEquals(Ir64FpMemSize.QUAD, op.size());
        assertEquals(0x88L, op.address());
    }

    // ── Reservado (G8): size!=00 com opc[1]=1 nunca é Q — tem que recusar, não confundir ──────────

    @Test
    void reservedSizeWithHighOpcBitIsUnsupported() {
        // Mesmo prefixo de ldr q0,[x1,#80] (3dc01420) mas com size=01 (bits[31:30]) em vez de 00 —
        // combinação reservada da tabela size:opc (só size=00 tem opc[1]=1 alocado).
        int reserved = 0x3dc01420 | (1 << 30);
        assertThrows(RuntimeException.class, () -> decodeAt(0, reserved));
    }
}
