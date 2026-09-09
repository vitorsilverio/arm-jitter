package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// AdvSIMD "vector/scalar × indexed element" (B8.19) — `MUL`/`MLA`/`MLS`/`SQDMULH`/`SQRDMULH`
/// (não-alargante), `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL`
/// (alargante) e `FMUL`/`FMLA`/`FMLS`/`FMULX` (ponto flutuante). Corpus REAL via
/// `aarch64-none-elf-as`/`objdump` (devkitA64, `.arch armv8-a`). As formas de meia-precisão
/// (`FEAT_FP16`, `size=00`, B19.5.6) estão numa seção própria mais abaixo, com o corpus real
/// separado via `aarch64-linux-gnu-as -march=armv8.2-a+fp16` (WSL).
class Aarch64AdvSimdIndexedElementDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder FP16_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);
    private static final Aarch64Decoder ARMV8_1_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_1_A);

    private static Ir64Op decodeWord(int word) {
        return decodeWord(DECODER, word);
    }

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Vetorial, não-alargante ─────────────────────────────────────────────────────────────────

    @Test
    void mulVector4h() {
        // 0f728020: mul v0.4h, v1.4h, v2.h[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x0f728020);
        assertEquals(Ir64VectorThreeSameOp.MUL, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.index());
    }

    @Test
    void mulVector4s() {
        // 4fa58083: mul v3.4s, v4.4s, v5.s[1]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x4fa58083);
        assertEquals(Ir64VectorThreeSameOp.MUL, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(1, op.index());
        assertEquals(5, op.rm());
    }

    @Test
    void mlaVector8h() {
        // 6f420020: mla v0.8h, v1.8h, v2.h[0]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x6f420020);
        assertEquals(Ir64VectorThreeSameOp.MLA, op.op());
        assertEquals(true, op.q());
        assertEquals(0, op.index());
    }

    @Test
    void mlsVector4h() {
        // 2f724820: mls v0.4h, v1.4h, v2.h[7]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x2f724820);
        assertEquals(Ir64VectorThreeSameOp.MLS, op.op());
        assertEquals(7, op.index());
    }

    @Test
    void sqdmulhVector4h() {
        // 0f72c020: sqdmulh v0.4h, v1.4h, v2.h[3]
        assertEquals(Ir64VectorThreeSameOp.SQDMULH,
                ((Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x0f72c020)).op());
    }

    @Test
    void sqrdmulhVector8h() {
        // 4f52d820: sqrdmulh v0.8h, v1.8h, v2.h[5]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x4f52d820);
        assertEquals(Ir64VectorThreeSameOp.SQRDMULH, op.op());
        assertEquals(5, op.index());
        assertEquals(2, op.rm());
    }

    @Test
    void mulHasNoScalarForm() {
        // MESMO opcode/esz de `mul v0.4h,...` (0x0f728020), com o prefixo forçado para ESCALAR
        // (bits[28:24]="11111": bit30 E bit28 setados — MESMA transformação usada pelo assembler
        // real entre `mul v0.4h,...` e `sqdmulh h0,...`, ver `sqdmulhScalarHalfword`): `MUL_si`
        // não existe (G8) — recusar em vez de decodificar como MUL escalar inventado.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(0x0f728020 | (1 << 30) | (1 << 28)));
    }

    // ── Vetorial, alargante ─────────────────────────────────────────────────────────────────────

    @Test
    void smullVectorH() {
        // 0f72a020: smull v0.4s, v1.4h, v2.h[3]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x0f72a020);
        assertEquals(Ir64VectorWideningOp.SMULL, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void smull2VectorH() {
        // 4f45a083: smull2 v3.4s, v4.8h, v5.h[0]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x4f45a083);
        assertEquals(Ir64VectorWideningOp.SMULL, op.op());
        assertEquals(true, op.q());
        assertEquals(0, op.index());
    }

    @Test
    void umullVectorS() {
        // 2fa2a020: umull v0.2d, v1.2s, v2.s[1]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x2fa2a020);
        assertEquals(Ir64VectorWideningOp.UMULL, op.op());
        assertEquals(2, op.esz());
        assertEquals(1, op.index());
    }

    @Test
    void smlalVectorH() {
        // 0f622020: smlal v0.4s, v1.4h, v2.h[2]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x0f622020);
        assertEquals(Ir64VectorWideningOp.SMLAL, op.op());
        assertEquals(2, op.index());
    }

    @Test
    void umlal2VectorS() {
        // 6f852083: umlal2 v3.2d, v4.4s, v5.s[0]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x6f852083);
        assertEquals(Ir64VectorWideningOp.UMLAL, op.op());
        assertEquals(true, op.q());
        assertEquals(0, op.index());
    }

    @Test
    void smlslVectorH() {
        // 0f626820: smlsl v0.4s, v1.4h, v2.h[6]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x0f626820);
        assertEquals(Ir64VectorWideningOp.SMLSL, op.op());
        assertEquals(6, op.index());
    }

    @Test
    void sqdmullVectorH() {
        // 0f72b020: sqdmull v0.4s, v1.4h, v2.h[3]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x0f72b020);
        assertEquals(Ir64VectorWideningOp.SQDMULL, op.op());
        assertEquals(false, op.scalar());
    }

    @Test
    void sqdmull2VectorS() {
        // 4fa5b083: sqdmull2 v3.2d, v4.4s, v5.s[1]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x4fa5b083);
        assertEquals(Ir64VectorWideningOp.SQDMULL, op.op());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
    }

    @Test
    void sqdmlalVectorH() {
        // 0f423020: sqdmlal v0.4s, v1.4h, v2.h[0]
        assertEquals(Ir64VectorWideningOp.SQDMLAL,
                ((Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x0f423020)).op());
    }

    @Test
    void sqdmlsl2VectorS() {
        // 4fa57883: sqdmlsl2 v3.2d, v4.4s, v5.s[3]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x4fa57883);
        assertEquals(Ir64VectorWideningOp.SQDMLSL, op.op());
        assertEquals(3, op.index());
    }

    @Test
    void smullHasNoScalarForm() {
        // MESMO opcode/esz de `smull v0.4s,...` (0x0f72a020), prefixo forçado para ESCALAR (mesma
        // transformação de {@link #mulHasNoScalarForm}): `SMULL_si` não existe (G8) — sem forma
        // escalar real nesta família.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(0x0f72a020 | (1 << 30) | (1 << 28)));
    }

    // ── Escalar, inteiro ────────────────────────────────────────────────────────────────────────

    @Test
    void sqdmulhScalarHalfword() {
        // 5f72c020: sqdmulh h0, h1, v2.h[3]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x5f72c020);
        assertEquals(Ir64VectorThreeSameOp.SQDMULH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void sqdmulhScalarWord() {
        // 5fa2c020: sqdmulh s0, s1, v2.s[1]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x5fa2c020);
        assertEquals(Ir64VectorThreeSameOp.SQDMULH, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
        assertEquals(1, op.index());
    }

    @Test
    void sqrdmulhScalarHalfword() {
        // 5f72d820: sqrdmulh h0, h1, v2.h[7]
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(0x5f72d820);
        assertEquals(Ir64VectorThreeSameOp.SQRDMULH, op.op());
        assertEquals(7, op.index());
    }

    @Test
    void sqdmullScalarHalfword() {
        // 5f72b020: sqdmull s0, h1, v2.h[3] — escalar, ESTREITO h→s.
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x5f72b020);
        assertEquals(Ir64VectorWideningOp.SQDMULL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void sqdmullScalarWord() {
        // 5fa2b020: sqdmull d0, s1, v2.s[1] — escalar, ESTREITO s→d.
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x5fa2b020);
        assertEquals(Ir64VectorWideningOp.SQDMULL, op.op());
        assertEquals(2, op.esz());
    }

    @Test
    void sqdmlalScalarHalfword() {
        // 5f423020: sqdmlal s0, h1, v2.h[0]
        assertEquals(Ir64VectorWideningOp.SQDMLAL,
                ((Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x5f423020)).op());
    }

    @Test
    void sqdmlslScalarWord() {
        // 5fa27820: sqdmlsl d0, s1, v2.s[3]
        Ir64Op.VectorArithmeticWideningByElement op =
                (Ir64Op.VectorArithmeticWideningByElement) decodeWord(0x5fa27820);
        assertEquals(Ir64VectorWideningOp.SQDMLSL, op.op());
        assertEquals(3, op.index());
    }

    // ── Vetorial, ponto flutuante ───────────────────────────────────────────────────────────────

    @Test
    void fmulVectorSingle() {
        // 0fa29820: fmul v0.2s, v1.2s, v2.s[3]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x0fa29820);
        assertEquals(Ir64VectorFpThreeSameOp.MUL, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void fmulVectorDouble() {
        // 4fc59883: fmul v3.2d, v4.2d, v5.d[1]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x4fc59883);
        assertEquals(Ir64VectorFpThreeSameOp.MUL, op.op());
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(1, op.index());
    }

    @Test
    void fmlaVectorSingle() {
        // 4f821020: fmla v0.4s, v1.4s, v2.s[0]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x4f821020);
        assertEquals(Ir64VectorFpThreeSameOp.MLA, op.op());
        assertEquals(true, op.q());
        assertEquals(0, op.index());
    }

    @Test
    void fmlsVectorSingle() {
        // 0f825820: fmls v0.2s, v1.2s, v2.s[2]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x0f825820);
        assertEquals(Ir64VectorFpThreeSameOp.MLS, op.op());
        assertEquals(2, op.index());
    }

    @Test
    void fmulxVectorSingle() {
        // 6fa29020: fmulx v0.4s, v1.4s, v2.s[1]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x6fa29020);
        assertEquals(Ir64VectorFpThreeSameOp.MULX, op.op());
        assertEquals(1, op.index());
    }

    // ── Escalar, ponto flutuante ────────────────────────────────────────────────────────────────

    @Test
    void fmulScalarSingle() {
        // 5fa29820: fmul s0, s1, v2.s[3]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x5fa29820);
        assertEquals(Ir64VectorFpThreeSameOp.MUL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void fmulScalarDouble() {
        // 5fc29820: fmul d0, d1, v2.d[1]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x5fc29820);
        assertEquals(Ir64VectorFpThreeSameOp.MUL, op.op());
        assertEquals(3, op.esz());
        assertEquals(1, op.index());
    }

    @Test
    void fmlaScalarSingle() {
        // 5f821020: fmla s0, s1, v2.s[0]
        assertEquals(Ir64VectorFpThreeSameOp.MLA,
                ((Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x5f821020)).op());
    }

    @Test
    void fmlsScalarDouble() {
        // 5fc25820: fmls d0, d1, v2.d[1]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x5fc25820);
        assertEquals(Ir64VectorFpThreeSameOp.MLS, op.op());
        assertEquals(3, op.esz());
    }

    @Test
    void fmulxScalarDouble() {
        // 7fc29020: fmulx d0, d1, v2.d[0]
        Ir64Op.VectorFpArithmeticThreeSameByElement op =
                (Ir64Op.VectorFpArithmeticThreeSameByElement) decodeWord(0x7fc29020);
        assertEquals(Ir64VectorFpThreeSameOp.MULX, op.op());
        assertEquals(true, op.scalar());
        assertEquals(0, op.index());
    }

    // ── Negativos (G8) ──────────────────────────────────────────────────────────────────────────

    @Test
    void doublewordWithLBitSetIsReserved() {
        // MESMO word de `fmul v3.2d,...` (0x4fc59883), com bit21 forçado a 1 — reservado: a forma
        // D real sempre tem bit21=0 (índice só usa `H`/bit11).
        int reserved = 0x4fc59883 | (1 << 21);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(reserved));
    }

    @Test
    void halfPrecisionSizeFieldIsUnsupportedWithoutFeature() {
        // `size=00` (bits[23:22]) é meia-precisão (`FEAT_FP16`, B19.5.6) — sem a feature (decoder
        // default = `ARMV8_0_A`), continua recusado byte a byte como antes desta task. MESMO resto
        // do encoding de `fmul v0.2s,...` (0x0fa29820) com `size` zerado.
        int halfPrecision = 0x0fa29820 & ~(0b11 << 22);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(halfPrecision));
    }

    // ── B19.5.6: `FEAT_FP16` — MESMO esquema de índice/estreitamento de `Rm` de `size=01`, agora
    // ── sob `size=00`. Corpus REAL via `aarch64-linux-gnu-as -march=armv8.2-a+fp16` (WSL).

    @Test
    void fmulScalarHalfPrecisionIndexSeven() {
        // 5f329820: fmul h0, h1, v2.h[7] — índice máximo (H:L:M = 1:1:1), prova a ordem dos bits.
        Ir64Op.VectorFpArithmeticThreeSameByElement op = (Ir64Op.VectorFpArithmeticThreeSameByElement)
                decodeWord(FP16_DECODER, 0x5f329820);
        assertEquals(Ir64VectorFpThreeSameOp.MUL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(7, op.index());
    }

    @Test
    void fmlaVectorHalfPrecisionEightLanes() {
        // 4f121820: fmla v0.8h, v1.8h, v2.h[5]
        Ir64Op.VectorFpArithmeticThreeSameByElement op = (Ir64Op.VectorFpArithmeticThreeSameByElement)
                decodeWord(FP16_DECODER, 0x4f121820);
        assertEquals(Ir64VectorFpThreeSameOp.MLA, op.op());
        assertEquals(false, op.scalar());
        assertEquals(true, op.q());
        assertEquals(1, op.esz());
        assertEquals(5, op.index());
        assertEquals(2, op.rm());
    }

    @Test
    void fmlsScalarHalfPrecisionRmNarrowedToV15() {
        // 5f0f5020: fmls h0, h1, v15.h[0] — `Rm=15` prova o estreitamento a `V0`-`V15` (M vira bit
        // baixo do índice); `index=0` prova o outro extremo do teste anterior (`H` é o bit alto).
        Ir64Op.VectorFpArithmeticThreeSameByElement op = (Ir64Op.VectorFpArithmeticThreeSameByElement)
                decodeWord(FP16_DECODER, 0x5f0f5020);
        assertEquals(Ir64VectorFpThreeSameOp.MLS, op.op());
        assertEquals(15, op.rm());
        assertEquals(0, op.index());
    }

    @Test
    void fmulxVectorHalfPrecisionFourLanes() {
        // 2f329020: fmulx v0.4h, v1.4h, v2.h[3]
        Ir64Op.VectorFpArithmeticThreeSameByElement op = (Ir64Op.VectorFpArithmeticThreeSameByElement)
                decodeWord(FP16_DECODER, 0x2f329020);
        assertEquals(Ir64VectorFpThreeSameOp.MULX, op.op());
        assertEquals(false, op.q());
        assertEquals(1, op.esz());
        assertEquals(3, op.index());
    }

    @Test
    void mulVectorHalfwordIntegerStillDecodesUnderFp16Decoder() {
        // 4f728020: mul v0.8h, v1.8h, v2.h[3] — `size=01` (halfword INTEIRO), regressão: continua
        // decodificando idêntico mesmo com `FEAT_FP16` ligada (a feature não muda `size=01`).
        Ir64Op.VectorArithmeticThreeSameByElement op =
                (Ir64Op.VectorArithmeticThreeSameByElement) decodeWord(FP16_DECODER, 0x4f728020);
        assertEquals(Ir64VectorThreeSameOp.MUL, op.op());
        assertEquals(true, op.q());
        assertEquals(3, op.index());
    }

    @Test
    void halfPrecisionRejectedUnderArmv80AndArmv81() {
        // MESMO word de `fmul h0,...` (0x5f329820) sob decoders sem `FEAT_FP16` — recusado nos
        // dois (a feature só entra em `ARMV8_2_A`).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5f329820));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, 0x5f329820));
    }

    @Test
    void halfPrecisionIntegerOpcodeIsUnsupportedEvenWithFeature() {
        // MESMO word de `mul v0.4h,...` (0x0f728020, `size=01`, opcode=`1000`) com `size` forçado
        // para `00`: em meia-precisão só existem os 4 opcodes de ponto flutuante — `MUL` inteiro
        // (`opcode=1000`) não bate em `decodeAdvancedSimdIndexedFp` (G8), mesmo sob `FEAT_FP16`.
        int forcedHalfPrecision = 0x0f728020 & ~(1 << 22);
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(FP16_DECODER, forcedHalfPrecision));
    }

    @Test
    void sudotIndexedI8mmDoesNotLeakThroughHalfPrecisionSlot() {
        // 4f22f820: sudot v0.4s, v1.16b, v2.4b[3] (`FEAT_I8MM`) também usa `size=00`, mas
        // `opcode=1111` (interceptado ANTES do switch, B19.12) — nunca alcança a tabela de FP desta
        // task (`MUL`/`MLA`/`MLS`/`MULX`=`1001`/`0001`/`0101`). Sob `FEAT_FP16` SEM `FEAT_I8MM`,
        // continua recusado.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x4f22f820));
    }

    @Test
    void fmlalIndexedFeatFhmDoesNotLeakThroughHalfPrecisionSlot() {
        // 4fb20020: fmlal v0.4s, v1.4h, v2.h[3] (`FEAT_FHM`) — achado que corrige a spec: NÃO usa
        // `size=00` (usa `size=10`, `opcode=0000`, que não bate em nenhuma tabela) — recusado sob
        // `FEAT_FP16` de qualquer forma, sem intersecção real com o slot desta task.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x4fb20020));
    }
}
