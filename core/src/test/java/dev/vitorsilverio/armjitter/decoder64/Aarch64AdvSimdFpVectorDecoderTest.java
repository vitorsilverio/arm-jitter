package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPermuteOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// AdvSIMD FP vetorial (B8.9): "three same"/"three same pairwise"/"two-register miscellaneous" de
/// ponto flutuante, só precisão simples/dupla. Sem `aarch64-none-elf-as`/`objdump` disponíveis
/// nesta sessão (toolchain devkitA64 ausente) — palavras construídas por FÓRMULA a partir dos
/// campos do encoding real (`a64.decode` do QEMU, via `WebFetch`), mesmo fallback de
/// {@link Aarch64AdvSimdShiftSaturateDecoderTest} (B8.8). Cada `@Test` documenta os campos usados.
class Aarch64AdvSimdFpVectorDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();
    /// B19.5.3: `FEAT_FP16` — golden CONFERIDO com `aarch64-none-elf-as`/`objdump` (devkitA64, via
    /// WSL, `.arch armv8.2-a+fp16`), ao contrário do resto do arquivo (que usa fórmula porque o
    /// toolchain não estava disponível na sessão original).
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

    // ── Three same (FP): Q=1,Rn=1,Rm=2,Rd=0 ──────────────────────────────────────────────────────

    @Test
    void faddFsubVector() {
        // Q=1,U=0,a=0,sz=0,Rm=2,opcode=11010,bit10=1,Rn=1,Rd=0 → FADD_v.4s
        Ir64Op.VectorFpArithmeticThreeSame add = (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22d420);
        assertEquals(Ir64VectorFpThreeSameOp.ADD, add.op());
        assertEquals(true, add.q());
        assertEquals(2, add.esz());
        assertEquals(0, add.rd());
        assertEquals(1, add.rn());
        assertEquals(2, add.rm());

        // sz=1 → .2d (double)
        Ir64Op.VectorFpArithmeticThreeSame addD = (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e62d420);
        assertEquals(Ir64VectorFpThreeSameOp.ADD, addD.op());
        assertEquals(3, addD.esz());

        // a=1 → FSUB_v.4s
        assertEquals(Ir64VectorFpThreeSameOp.SUB, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4ea2d420)).op());
    }

    @Test
    void fdivFmulFmulxVector() {
        assertEquals(Ir64VectorFpThreeSameOp.DIV, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6e22fc20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MUL, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6e22dc20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MULX, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22dc20)).op());
    }

    @Test
    void maxMinMaxnmMinnmVector() {
        assertEquals(Ir64VectorFpThreeSameOp.MAX, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22f420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MIN, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4ea2f420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MAXNM, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22c420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MINNM, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4ea2c420)).op());
    }

    @Test
    void mlaMlsVector() {
        assertEquals(Ir64VectorFpThreeSameOp.MLA, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22cc20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MLS, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4ea2cc20)).op());
    }

    @Test
    void compareVector() {
        assertEquals(Ir64VectorFpThreeSameOp.CMEQ, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22e420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.CMGE, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6e22e420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.CMGT, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6ea2e420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.FACGE, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6e22ec20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.FACGT, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6ea2ec20)).op());
    }

    @Test
    void abdRecpsRsqrtsVector() {
        assertEquals(Ir64VectorFpThreeSameOp.ABD, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x6ea2d420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.RECPS, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4e22fc20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.RSQRTS, ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(0x4ea2fc20)).op());
    }

    // ── Three same pairwise (FP) ─────────────────────────────────────────────────────────────────

    @Test
    void pairwiseVector() {
        Ir64Op.VectorFpArithmeticPairwise add = (Ir64Op.VectorFpArithmeticPairwise) decodeWord(0x6e22d420);
        assertEquals(Ir64VectorFpPairwiseOp.ADD, add.op());
        assertEquals(0, add.rd());
        assertEquals(1, add.rn());
        assertEquals(2, add.rm());
        assertEquals(Ir64VectorFpPairwiseOp.MAX, ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(0x6e22f420)).op());
        assertEquals(Ir64VectorFpPairwiseOp.MIN, ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(0x6ea2f420)).op());
        assertEquals(Ir64VectorFpPairwiseOp.MAXNM, ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(0x6e22c420)).op());
        assertEquals(Ir64VectorFpPairwiseOp.MINNM, ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(0x6ea2c420)).op());
    }

    // ── Two-register misc (FP), slot Rm=00000: ABS/NEG/CM**0 ────────────────────────────────────

    @Test
    void absNegVector() {
        Ir64Op.VectorFpArithmeticUnary abs = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea0f820);
        assertEquals(Ir64VectorFpUnaryOp.ABS, abs.op());
        assertEquals(true, abs.q());
        assertEquals(2, abs.esz());
        assertEquals(0, abs.rd());
        assertEquals(1, abs.rn());
        assertEquals(Ir64VectorFpUnaryOp.NEG, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea0f820)).op());
    }

    @Test
    void compareZeroVector() {
        assertEquals(Ir64VectorFpUnaryOp.CMGT0, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea0c820)).op());
        assertEquals(Ir64VectorFpUnaryOp.CMGE0, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea0c820)).op());
        assertEquals(Ir64VectorFpUnaryOp.CMEQ0, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea0d820)).op());
        assertEquals(Ir64VectorFpUnaryOp.CMLE0, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea0d820)).op());
        assertEquals(Ir64VectorFpUnaryOp.CMLT0, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea0e820)).op());
    }

    // ── Two-register misc (FP), slot Rm=00001: FSQRT/FRINTx/FRECPE/FRSQRTE/SCVTF/UCVTF/FCVTx ─────

    @Test
    void sqrtVector() {
        Ir64Op.VectorFpArithmeticUnary sqrt = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea1f820);
        assertEquals(Ir64VectorFpUnaryOp.SQRT, sqrt.op());
        assertEquals(2, sqrt.esz());
    }

    @Test
    void rintVector() {
        assertEquals(Ir64VectorFpUnaryOp.RINTN, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e218820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTP, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea18820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTA, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e218820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTM, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e219820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTZ, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea19820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTX, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e219820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTI, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea19820)).op());
    }

    @Test
    void fcvtWithRoundingModeVector() {
        assertEquals(Ir64VectorFpUnaryOp.FCVTNS, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e21a820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTNU, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e21a820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTPS, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea1a820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTPU, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea1a820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTMS, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e21b820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTMU, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e21b820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTZS, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea1b820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTZU, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea1b820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTAS, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e21c820)).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTAU, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e21c820)).op());
    }

    @Test
    void scvtfUcvtfRecpeRsqrteVector() {
        assertEquals(Ir64VectorFpUnaryOp.SCVTF, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e21d820)).op());
        assertEquals(Ir64VectorFpUnaryOp.UCVTF, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e21d820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RECPE, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4ea1d820)).op());
        assertEquals(Ir64VectorFpUnaryOp.RSQRTE, ((Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ea1d820)).op());
    }

    // ── Fora de escopo: meia-precisão (FEAT_FP16) ──────────────────────────────────────────────

    @Test
    void halfPrecisionThreeSameIsUnimplemented() {
        // FADD_v.8h: Q=1,U=0,bit23=0,bit22=1(sz-slot da forma "h"),bit21=0 (prefixo "h", não
        // "sd" — a forma "sd" real exige bit21=1) — cai no guard `bit21==0` já existente
        // (G8: UNIMPLEMENTED, não ✅ por acaso).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e421420));
    }

    // ── B19.2: AdvSIMD FP ESCALAR "three same" + pairwise escalar (golden devkitA64) ────────────

    private static Ir64Op.VectorFpArithmeticThreeSame scalarThreeSame(int word) {
        Ir64Op.VectorFpArithmeticThreeSame op = (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(word);
        assertEquals(true, op.scalar(), "forma escalar");
        assertEquals(false, op.q());
        return op;
    }

    @Test
    void scalarThreeSameSd() {
        // s=single (floatEsz 2) / d=double (floatEsz 3), golden aarch64-none-elf-as
        assertEquals(Ir64VectorFpThreeSameOp.MULX, scalarThreeSame(0x5e22dc20).op()); // fmulx s0,s1,s2
        assertEquals(3, scalarThreeSame(0x5e62dc20).esz());                            // fmulx d0,d1,d2
        assertEquals(2, scalarThreeSame(0x5e22dc20).esz());
        assertEquals(Ir64VectorFpThreeSameOp.CMEQ, scalarThreeSame(0x5e22e420).op());  // fcmeq s
        assertEquals(Ir64VectorFpThreeSameOp.CMGE, scalarThreeSame(0x7e22e420).op());  // fcmge s
        assertEquals(Ir64VectorFpThreeSameOp.CMGT, scalarThreeSame(0x7ea2e420).op());  // fcmgt s
        assertEquals(Ir64VectorFpThreeSameOp.FACGE, scalarThreeSame(0x7e22ec20).op()); // facge s
        assertEquals(Ir64VectorFpThreeSameOp.FACGT, scalarThreeSame(0x7ea2ec20).op()); // facgt s
        assertEquals(Ir64VectorFpThreeSameOp.ABD, scalarThreeSame(0x7ea2d420).op());   // fabd s
        assertEquals(Ir64VectorFpThreeSameOp.RECPS, scalarThreeSame(0x5e22fc20).op()); // frecps s
        assertEquals(Ir64VectorFpThreeSameOp.RSQRTS, scalarThreeSame(0x5ea2fc20).op());// frsqrts s
        // double variants of the compares/steps
        assertEquals(Ir64VectorFpThreeSameOp.CMEQ, scalarThreeSame(0x5e62e420).op());  // fcmeq d
        assertEquals(Ir64VectorFpThreeSameOp.ABD, scalarThreeSame(0x7ee2d420).op());   // fabd d
        assertEquals(Ir64VectorFpThreeSameOp.RSQRTS, scalarThreeSame(0x5ee2fc20).op());// frsqrts d
    }

    private static Ir64Op.VectorFpArithmeticPairwise scalarPairwise(int word) {
        Ir64Op.VectorFpArithmeticPairwise op = (Ir64Op.VectorFpArithmeticPairwise) decodeWord(word);
        assertEquals(true, op.scalar(), "forma escalar");
        assertEquals(false, op.q());
        return op;
    }

    @Test
    void scalarPairwiseSd() {
        assertEquals(Ir64VectorFpPairwiseOp.ADD, scalarPairwise(0x7e30d820).op());   // faddp s0,v1.2s
        assertEquals(2, scalarPairwise(0x7e30d820).esz());
        assertEquals(Ir64VectorFpPairwiseOp.ADD, scalarPairwise(0x7e70d820).op());   // faddp d0,v1.2d
        assertEquals(3, scalarPairwise(0x7e70d820).esz());
        assertEquals(Ir64VectorFpPairwiseOp.MAX, scalarPairwise(0x7e30f820).op());   // fmaxp s
        assertEquals(Ir64VectorFpPairwiseOp.MIN, scalarPairwise(0x7eb0f820).op());   // fminp s
        assertEquals(Ir64VectorFpPairwiseOp.MAXNM, scalarPairwise(0x7e30c820).op()); // fmaxnmp s
        assertEquals(Ir64VectorFpPairwiseOp.MINNM, scalarPairwise(0x7eb0c820).op()); // fminnmp s
        assertEquals(Ir64VectorFpPairwiseOp.MIN, scalarPairwise(0x7ef0f820).op());   // fminp d
    }

    @Test
    void scalarPairwiseKeepsAddpSInteger() {
        // ADDP_s D0,V1.2D (0x5ef1b820) continua VectorScalarPairwiseAdd — o ramo FP novo vem DEPOIS.
        assertEquals(Ir64Op.VectorScalarPairwiseAdd.class, decodeWord(0x5ef1b820).getClass());
    }

    // ── B19.2 regressão negativa: meia-precisão (_h, FEAT_FP16) recusada pela ESTRUTURA ────────

    @Test
    void halfPrecisionScalarThreeSameIsUnimplemented() {
        // fmulx h0,h1,h2 (0x5e421c20): bit21=0 no encoding "_h" ⇒ nem chega no dispatch three-same.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e421c20));
    }

    @Test
    void halfPrecisionScalarPairwiseIsUnimplemented() {
        // faddp h0,v1.2h (0x5e30d820): U=0 no encoding "_h" ⇒ decodeVectorFpScalarPairwiseOpcode
        // devolve null (só U=1 é _sd).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e30d820));
    }

    // ── B19.2 G8: operação vetorial-only com prefixo escalar / (u,a,opcode) reservado ─────────

    @Test
    void scalarPrefixWithVectorOnlyOpIsUnimplemented() {
        // prefixo escalar + opcode 0b11010 / (u=0,a=0) = FADD (só vetorial) ⇒ unsupported, nunca
        // vira VectorFpArithmeticThreeSame nem cai na forma vetorial.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e22d420));
        // reservado: opcode 0b11011 (slot MUL/MULX) com (u=0,a=1) não mapeia nada.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5ea2dc20));
    }

    // ── B19.3: AdvSIMD FP ESCALAR "two-register misc" + conversões escalares (golden devkitA64) ──

    private static Ir64Op.VectorFpArithmeticUnary scalarUnary(int word) {
        Ir64Op.VectorFpArithmeticUnary op = (Ir64Op.VectorFpArithmeticUnary) decodeWord(word);
        assertEquals(true, op.scalar(), "forma escalar");
        assertEquals(false, op.q());
        return op;
    }

    @Test
    void scalarCompareAgainstZero() {
        // s (floatEsz 2) / d (floatEsz 3), golden aarch64-none-elf-as `.arch armv8-a`
        assertEquals(Ir64VectorFpUnaryOp.CMGT0, scalarUnary(0x5ea0c820).op()); // fcmgt s0,s1,#0.0
        assertEquals(2, scalarUnary(0x5ea0c820).esz());
        assertEquals(Ir64VectorFpUnaryOp.CMGT0, scalarUnary(0x5ee0c820).op()); // fcmgt d0,d1,#0.0
        assertEquals(3, scalarUnary(0x5ee0c820).esz());
        assertEquals(Ir64VectorFpUnaryOp.CMGE0, scalarUnary(0x7ea0c820).op()); // fcmge s
        assertEquals(Ir64VectorFpUnaryOp.CMGE0, scalarUnary(0x7ee0c820).op()); // fcmge d
        assertEquals(Ir64VectorFpUnaryOp.CMEQ0, scalarUnary(0x5ea0d820).op()); // fcmeq s
        assertEquals(Ir64VectorFpUnaryOp.CMEQ0, scalarUnary(0x5ee0d820).op()); // fcmeq d
        assertEquals(Ir64VectorFpUnaryOp.CMLE0, scalarUnary(0x7ea0d820).op()); // fcmle s
        assertEquals(Ir64VectorFpUnaryOp.CMLE0, scalarUnary(0x7ee0d820).op()); // fcmle d
        assertEquals(Ir64VectorFpUnaryOp.CMLT0, scalarUnary(0x5ea0e820).op()); // fcmlt s
        assertEquals(Ir64VectorFpUnaryOp.CMLT0, scalarUnary(0x5ee0e820).op()); // fcmlt d
    }

    @Test
    void scalarReciprocalsAndNarrow() {
        assertEquals(Ir64VectorFpUnaryOp.RECPE, scalarUnary(0x5ea1d820).op());  // frecpe s
        assertEquals(Ir64VectorFpUnaryOp.RECPE, scalarUnary(0x5ee1d820).op());  // frecpe d
        assertEquals(Ir64VectorFpUnaryOp.RSQRTE, scalarUnary(0x7ea1d820).op()); // frsqrte s
        assertEquals(Ir64VectorFpUnaryOp.RSQRTE, scalarUnary(0x7ee1d820).op()); // frsqrte d
        Ir64Op.VectorFpArithmeticUnary frecpxS = scalarUnary(0x5ea1f820);      // frecpx s0,s1
        assertEquals(Ir64VectorFpUnaryOp.FRECPX, frecpxS.op());
        assertEquals(2, frecpxS.esz());
        assertEquals(3, scalarUnary(0x5ee1f820).esz());                        // frecpx d0,d1
        Ir64Op.VectorFpArithmeticUnary fcvtxn = scalarUnary(0x7e616820);      // fcvtxn s0,d1
        assertEquals(Ir64VectorFpUnaryOp.FCVTXN, fcvtxn.op());
        assertEquals(3, fcvtxn.esz(), "esz do record = ENTRADA f64");
    }

    @Test
    void scalarIcvtConversions() {
        assertEquals(Ir64VectorFpUnaryOp.SCVTF, scalarUnary(0x5e21d820).op());  // scvtf s0,s1
        assertEquals(2, scalarUnary(0x5e21d820).esz());
        assertEquals(Ir64VectorFpUnaryOp.SCVTF, scalarUnary(0x5e61d820).op());  // scvtf d0,d1
        assertEquals(3, scalarUnary(0x5e61d820).esz());
        assertEquals(Ir64VectorFpUnaryOp.UCVTF, scalarUnary(0x7e21d820).op());  // ucvtf s
        assertEquals(Ir64VectorFpUnaryOp.FCVTNS, scalarUnary(0x5e21a820).op()); // fcvtns s
        assertEquals(Ir64VectorFpUnaryOp.FCVTNU, scalarUnary(0x7e21a820).op()); // fcvtnu s
        assertEquals(Ir64VectorFpUnaryOp.FCVTPS, scalarUnary(0x5ea1a820).op()); // fcvtps s
        assertEquals(Ir64VectorFpUnaryOp.FCVTPU, scalarUnary(0x7ea1a820).op()); // fcvtpu s
        assertEquals(Ir64VectorFpUnaryOp.FCVTMS, scalarUnary(0x5e21b820).op()); // fcvtms s
        assertEquals(Ir64VectorFpUnaryOp.FCVTMU, scalarUnary(0x7e21b820).op()); // fcvtmu s
        assertEquals(Ir64VectorFpUnaryOp.FCVTZS, scalarUnary(0x5ea1b820).op()); // fcvtzs s
        assertEquals(Ir64VectorFpUnaryOp.FCVTZU, scalarUnary(0x7ea1b820).op()); // fcvtzu s
        assertEquals(Ir64VectorFpUnaryOp.FCVTAS, scalarUnary(0x5e21c820).op()); // fcvtas s
        assertEquals(Ir64VectorFpUnaryOp.FCVTAU, scalarUnary(0x7e21c820).op()); // fcvtau s
        // uma variante double para provar o floatEsz
        assertEquals(3, scalarUnary(0x5ee1b820).esz());                        // fcvtzs d0,d1
    }

    private static Ir64Op.VectorFpConvertFixedPoint scalarFixed(int word) {
        Ir64Op.VectorFpConvertFixedPoint op = (Ir64Op.VectorFpConvertFixedPoint) decodeWord(word);
        assertEquals(true, op.scalar(), "forma escalar");
        assertEquals(false, op.q());
        return op;
    }

    @Test
    void scalarFcvtFixedConversions() {
        Ir64Op.VectorFpConvertFixedPoint scvtfS = scalarFixed(0x5f3ce420); // scvtf s0,s1,#4
        assertEquals(true, scvtfS.toFloat());
        assertEquals(true, scvtfS.signed());
        assertEquals(2, scvtfS.esz());
        assertEquals(4, scvtfS.fractionBits());
        Ir64Op.VectorFpConvertFixedPoint scvtfD = scalarFixed(0x5f5fe420); // scvtf d0,d1,#33
        assertEquals(3, scvtfD.esz());
        assertEquals(33, scvtfD.fractionBits());
        Ir64Op.VectorFpConvertFixedPoint ucvtfS = scalarFixed(0x7f3ce420); // ucvtf s0,s1,#4
        assertEquals(true, ucvtfS.toFloat());
        assertEquals(false, ucvtfS.signed());
        Ir64Op.VectorFpConvertFixedPoint fcvtzsS = scalarFixed(0x5f3cfc20); // fcvtzs s0,s1,#4
        assertEquals(false, fcvtzsS.toFloat());
        assertEquals(true, fcvtzsS.signed());
        assertEquals(4, fcvtzsS.fractionBits());
        Ir64Op.VectorFpConvertFixedPoint fcvtzuD = scalarFixed(0x7f5ffc20); // fcvtzu d0,d1,#33
        assertEquals(false, fcvtzuD.toFloat());
        assertEquals(false, fcvtzuD.signed());
        assertEquals(3, fcvtzuD.esz());
        assertEquals(33, fcvtzuD.fractionBits());
    }

    // ── B19.3 regressão negativa: meia-precisão (_h, FEAT_FP16) recusada pela ESTRUTURA ─────────

    @Test
    void halfPrecisionScalarTwoRegMiscAndConvertUnimplemented() {
        // fcmgt h0,h1,#0.0 (0x5ef8c820): Rm≠00000/00001 no encoding "_h" ⇒ não chega no dispatch FP.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5ef8c820));
        // frecpx h0,h1 (0x5ef9f820): idem.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5ef9f820));
        // scvtf h0,h1 (0x5e79d820): idem (@icvt_h).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e79d820));
        // scvtf h0,h1,#4 (0x5f1ce420): @fcvt_fixed_h ⇒ esz==1 recusado pelo check esz∈{2,3}.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5f1ce420));
    }

    // ── B19.3 G8: op vetorial-only com prefixo escalar / opcode reservado ──────────────────────

    @Test
    void scalarPrefixWithVectorOnlyFpUnaryOpIsUnimplemented() {
        // prefixo escalar, Rm=00001, opcode 0b1_1111, (u=1,a=1) = SQRT (só vetorial) ⇒ unsupported.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x7ee1f820));
        // prefixo escalar, Rm=00000, opcode 0b1_1101, u=1 = (u,a,opcode) reservado ⇒ unsupported.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x7ea0e820));
        // classe "shift by immediate" escalar, bit10=1, opcode 0b1_1101 (não é shift nem conversão
        // FP↔fixo) ⇒ unsupported.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5f3cec20));
    }

    // ── B19.4: conversões de PRECISÃO vetoriais (FCVTL/FCVTN/FCVTXN) ────────────────────────────
    // Golden: aarch64-none-elf-as/objdump (devkitA64, .arch armv8-a).

    private static Ir64Op.VectorFpConvertPrecision precision(int word) {
        return (Ir64Op.VectorFpConvertPrecision) decodeWord(word);
    }

    @Test
    void fcvtlVector() {
        // fcvtl v0.4s, v1.4h (0x0e217820): f16 -> f32, esz(estreito)=1, q=false
        Ir64Op.VectorFpConvertPrecision l4h = precision(0x0e217820);
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTL, l4h.op());
        assertEquals(1, l4h.esz());
        assertEquals(false, l4h.q());
        assertEquals(0, l4h.rd());
        assertEquals(1, l4h.rn());
        // fcvtl2 v0.4s, v1.8h (0x4e217820): q=true, esz ainda 1
        Ir64Op.VectorFpConvertPrecision l8h = precision(0x4e217820);
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTL, l8h.op());
        assertEquals(1, l8h.esz());
        assertEquals(true, l8h.q());
        // fcvtl v0.2d, v1.2s (0x0e617820): f32 -> f64, esz(estreito)=2
        assertEquals(2, precision(0x0e617820).esz());
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTL, precision(0x0e617820).op());
        // fcvtl2 v0.2d, v1.4s (0x4e617820): q=true, esz=2
        assertEquals(true, precision(0x4e617820).q());
        assertEquals(2, precision(0x4e617820).esz());
    }

    @Test
    void fcvtnVector() {
        // fcvtn v0.4h, v1.4s (0x0e216820): f32 -> f16, esz(estreito)=1, q=false
        Ir64Op.VectorFpConvertPrecision n4h = precision(0x0e216820);
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTN, n4h.op());
        assertEquals(1, n4h.esz());
        assertEquals(false, n4h.q());
        // fcvtn2 v0.8h, v1.4s (0x4e216820): q=true
        assertEquals(true, precision(0x4e216820).q());
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTN, precision(0x4e216820).op());
        // fcvtn v0.2s, v1.2d (0x0e616820): f64 -> f32, esz(estreito)=2
        assertEquals(2, precision(0x0e616820).esz());
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTN, precision(0x0e616820).op());
        // fcvtn2 v0.4s, v1.2d (0x4e616820)
        assertEquals(true, precision(0x4e616820).q());
        assertEquals(2, precision(0x4e616820).esz());
    }

    @Test
    void fcvtxnVector() {
        // fcvtxn v0.2s, v1.2d (0x2e616820): f64 -> f32 round-to-odd, esz=2, q=false
        Ir64Op.VectorFpConvertPrecision xn = precision(0x2e616820);
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTXN, xn.op());
        assertEquals(2, xn.esz());
        assertEquals(false, xn.q());
        // fcvtxn2 v0.4s, v1.2d (0x6e616820): q=true
        assertEquals(true, precision(0x6e616820).q());
        assertEquals(Ir64VectorFpConvertPrecisionOp.FCVTXN, precision(0x6e616820).op());
    }

    private static Ir64Op.VectorFpConvertFixedPoint vectorFixed(int word) {
        Ir64Op.VectorFpConvertFixedPoint op = (Ir64Op.VectorFpConvertFixedPoint) decodeWord(word);
        assertEquals(false, op.scalar(), "forma vetorial");
        return op;
    }

    @Test
    void fcvtFixedPointVector() {
        // scvtf v0.4s, v1.4s, #4 (0x4f3ce420)
        Ir64Op.VectorFpConvertFixedPoint scvtf4s = vectorFixed(0x4f3ce420);
        assertEquals(true, scvtf4s.toFloat());
        assertEquals(true, scvtf4s.signed());
        assertEquals(2, scvtf4s.esz());
        assertEquals(4, scvtf4s.fractionBits());
        assertEquals(true, scvtf4s.q());
        // ucvtf v0.4s, v1.4s, #4 (0x6f3ce420)
        assertEquals(false, vectorFixed(0x6f3ce420).signed());
        assertEquals(true, vectorFixed(0x6f3ce420).toFloat());
        // scvtf v0.2s, v1.2s, #4 (0x0f3ce420): q=false
        assertEquals(false, vectorFixed(0x0f3ce420).q());
        assertEquals(2, vectorFixed(0x0f3ce420).esz());
        // scvtf v0.2d, v1.2d, #33 (0x4f5fe420): esz=3, q=true, fbits=33
        Ir64Op.VectorFpConvertFixedPoint scvtf2d = vectorFixed(0x4f5fe420);
        assertEquals(3, scvtf2d.esz());
        assertEquals(33, scvtf2d.fractionBits());
        assertEquals(true, scvtf2d.q());
        // fcvtzs v0.4s, v1.4s, #4 (0x4f3cfc20)
        Ir64Op.VectorFpConvertFixedPoint fcvtzs = vectorFixed(0x4f3cfc20);
        assertEquals(false, fcvtzs.toFloat());
        assertEquals(true, fcvtzs.signed());
        // fcvtzu v0.2d, v1.2d, #8 (0x6f78fc20): esz=3, fbits=8, unsigned
        Ir64Op.VectorFpConvertFixedPoint fcvtzu = vectorFixed(0x6f78fc20);
        assertEquals(false, fcvtzu.toFloat());
        assertEquals(false, fcvtzu.signed());
        assertEquals(3, fcvtzu.esz());
        assertEquals(8, fcvtzu.fractionBits());
        assertEquals(true, fcvtzu.q());
        // fcvtzu v0.2d, v1.2d, #33 (0x6f5ffc20)
        assertEquals(33, vectorFixed(0x6f5ffc20).fractionBits());
    }

    // ── B19.4 regressão negativa (G8) ─────────────────────────────────────────────────────────────

    @Test
    void b194NegativeRegressions() {
        // bfcvtn v0.4h, v1.4s (0x0ea16820): opcode 0b0_1101, !u, a=1 ⇒ B19.7 ⇒ unsupported.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0ea16820));
        // F*CVTL/BF*CVTL: opcode 0b0_1111, u=1 ⇒ B19.7 ⇒ unsupported (formula: 0x0e217820 | U bit).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x2e217820));
        // scvtf v0.4h, v1.4h, #2 (0x0f1ee420): @fcvtq_h ⇒ esz==1 ⇒ meia precisão (B19.5) ⇒ unsupported.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0f1ee420));
        // fcvtzs v0.8h, v1.8h, #3 (0x4f1dfc20): idem.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4f1dfc20));
        // fcvtzu ...2d... com Q=0 (0x2f78fc20 = 0x6f78fc20 sem bit30): immh<3>==1 && Q==0 ⇒ UNDEFINED.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x2f78fc20));
    }

    // ── B19.5.3: FEAT_FP16 — pairwise escalar, across-lanes e ponto fixo _h (golden devkitA64) ──

    @Test
    void halfPrecisionScalarPairwiseDecodesUnderFp16() {
        // faddp h0,v1.2h / fmaxp / fminp / fmaxnmp / fminnmp — golden devkitA64.
        Ir64Op.VectorFpArithmeticPairwise add = (Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x5e30d820);
        assertEquals(Ir64VectorFpPairwiseOp.ADD, add.op());
        assertEquals(true, add.scalar());
        assertEquals(false, add.q());
        assertEquals(1, add.esz());
        assertEquals(0, add.rd());
        assertEquals(1, add.rn());

        assertEquals(Ir64VectorFpPairwiseOp.MAX,
                ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x5e30f820)).op());
        assertEquals(Ir64VectorFpPairwiseOp.MIN,
                ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x5eb0f820)).op());
        assertEquals(Ir64VectorFpPairwiseOp.MAXNM,
                ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x5e30c820)).op());
        assertEquals(Ir64VectorFpPairwiseOp.MINNM,
                ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x5eb0c820)).op());
    }

    @Test
    void halfPrecisionScalarPairwiseUnsupportedBelowFp16() {
        // Mesmas 5 palavras golden, sem a feature (ARMv8.0-A e ARMv8.1-A) — regressão negativa
        // por linha (padrão B11.4-B11.12): a estrutura já recusava por `!u`, e continua recusando.
        for (int word : new int[] {0x5e30d820, 0x5e30f820, 0x5eb0f820, 0x5e30c820, 0x5eb0c820}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DECODER, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, word));
        }
    }

    @Test
    void halfPrecisionScalarPairwiseSiblingSdUnaffected() {
        // As formas _sd que compartilham o MESMO `if` continuam idênticas com a feature presente.
        assertEquals(Ir64VectorFpPairwiseOp.ADD,
                ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x7e30d820)).op());
        assertEquals(2, ((Ir64Op.VectorFpArithmeticPairwise) decodeWord(FP16_DECODER, 0x7e30d820)).esz());
    }

    private static Ir64Op.VectorFpAcrossLanes acrossLanesHalf(int word) {
        return (Ir64Op.VectorFpAcrossLanes) decodeWord(FP16_DECODER, word);
    }

    @Test
    void halfPrecisionAcrossLanesDecodesUnderFp16() {
        // fmaxnmv h0,v1.4h / fmaxnmv h0,v1.8h / fminnmv h0,v1.4h / fmaxv h0,v1.4h / fmaxv h0,v1.8h /
        // fminv h0,v1.4h — golden devkitA64.
        Ir64Op.VectorFpAcrossLanes maxnm4h = acrossLanesHalf(0x0e30c820);
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXNMV, maxnm4h.op());
        assertEquals(false, maxnm4h.q());
        assertEquals(1, maxnm4h.esz());
        assertEquals(0, maxnm4h.rd());
        assertEquals(1, maxnm4h.rn());

        Ir64Op.VectorFpAcrossLanes maxnm8h = acrossLanesHalf(0x4e30c820);
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXNMV, maxnm8h.op());
        assertEquals(true, maxnm8h.q());
        assertEquals(1, maxnm8h.esz());

        assertEquals(Ir64VectorFpAcrossLanesOp.FMINNMV, acrossLanesHalf(0x0eb0c820).op());
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXV, acrossLanesHalf(0x0e30f820).op());
        assertEquals(true, acrossLanesHalf(0x4e30f820).q());
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXV, acrossLanesHalf(0x4e30f820).op());
        assertEquals(Ir64VectorFpAcrossLanesOp.FMINV, acrossLanesHalf(0x0eb0f820).op());
    }

    @Test
    void halfPrecisionAcrossLanesUnsupportedBelowFp16() {
        for (int word : new int[] {0x0e30c820, 0x4e30c820, 0x0eb0c820, 0x0e30f820, 0x4e30f820, 0x0eb0f820}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DECODER, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, word));
        }
    }

    @Test
    void acrossLanesSiblingSUnaffected() {
        // fmaxnmv s0,v1.4s (0x6e30c820) continua Q=true/esz=WORD com a feature presente.
        Ir64Op.VectorFpAcrossLanes maxnmS = acrossLanesHalf(0x6e30c820);
        assertEquals(Ir64VectorFpAcrossLanesOp.FMAXNMV, maxnmS.op());
        assertEquals(true, maxnmS.q());
        assertEquals(2, maxnmS.esz());
    }

    private static Ir64Op.VectorFpConvertFixedPoint fixedHalf(int word) {
        return (Ir64Op.VectorFpConvertFixedPoint) decodeWord(FP16_DECODER, word);
    }

    @Test
    void halfPrecisionScalarFixedPointDecodesUnderFp16() {
        // scvtf h0,h1,#4 / ucvtf / fcvtzs / fcvtzu — golden devkitA64.
        Ir64Op.VectorFpConvertFixedPoint scvtf = fixedHalf(0x5f1ce420);
        assertEquals(true, scvtf.scalar());
        assertEquals(true, scvtf.toFloat());
        assertEquals(true, scvtf.signed());
        assertEquals(1, scvtf.esz());
        assertEquals(4, scvtf.fractionBits());

        Ir64Op.VectorFpConvertFixedPoint ucvtf = fixedHalf(0x7f1ce420);
        assertEquals(true, ucvtf.toFloat());
        assertEquals(false, ucvtf.signed());

        Ir64Op.VectorFpConvertFixedPoint fcvtzs = fixedHalf(0x5f1cfc20);
        assertEquals(false, fcvtzs.toFloat());
        assertEquals(true, fcvtzs.signed());

        Ir64Op.VectorFpConvertFixedPoint fcvtzu = fixedHalf(0x7f1cfc20);
        assertEquals(false, fcvtzu.toFloat());
        assertEquals(false, fcvtzu.signed());
    }

    @Test
    void halfPrecisionVectorFixedPointDecodesUnderFp16() {
        // scvtf v0.4h,v1.4h,#3 / scvtf v0.8h,v1.8h,#3 / ucvtf v0.4h,#3 / fcvtzs v0.4h,#3 / fcvtzu v0.4h,#3.
        Ir64Op.VectorFpConvertFixedPoint scvtf4h = fixedHalf(0x0f1de420);
        assertEquals(false, scvtf4h.scalar());
        assertEquals(false, scvtf4h.q());
        assertEquals(1, scvtf4h.esz());
        assertEquals(3, scvtf4h.fractionBits());

        Ir64Op.VectorFpConvertFixedPoint scvtf8h = fixedHalf(0x4f1de420);
        assertEquals(true, scvtf8h.q());

        assertEquals(false, fixedHalf(0x2f1de420).signed());
        assertEquals(false, fixedHalf(0x0f1dfc20).toFloat());
        assertEquals(false, fixedHalf(0x2f1dfc20).signed());
    }

    @Test
    void halfPrecisionFixedPointUnsupportedBelowFp16() {
        for (int word : new int[] {0x5f1ce420, 0x7f1ce420, 0x5f1cfc20, 0x7f1cfc20,
                0x0f1de420, 0x4f1de420, 0x2f1de420, 0x0f1dfc20, 0x2f1dfc20}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DECODER, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, word));
        }
    }

    @Test
    void fixedPointSiblingWordAndDoubleUnaffected() {
        // As formas `_s`/`_d` (esz 2/3) continuam idênticas com a feature FP16 presente.
        Ir64Op.VectorFpConvertFixedPoint s = (Ir64Op.VectorFpConvertFixedPoint) decodeWord(FP16_DECODER, 0x5f3ce420);
        assertEquals(2, s.esz());
        assertEquals(true, s.scalar());
    }

    // ── B19.5.4: FEAT_FP16 — two-reg-misc + conversões escalares (slots `Rm=0b1_1000`/`0b1_1001`,
    // golden `aarch64-linux-gnu-as -march=armv8.2-a+fp16`) ─────────────────────────────────────────

    private static Ir64Op.VectorFpArithmeticUnary unaryHalf(int word) {
        return (Ir64Op.VectorFpArithmeticUnary) decodeWord(FP16_DECODER, word);
    }

    @Test
    void halfPrecisionTwoRegMiscVectorDecodesUnderFp16() {
        // fabs/fneg/fcmgt/fcmge/fcmeq/fcmle/fcmlt v0.8h,v1.8h,#0 — golden.
        Ir64Op.VectorFpArithmeticUnary abs = unaryHalf(0x4ef8f820);
        assertEquals(Ir64VectorFpUnaryOp.ABS, abs.op());
        assertEquals(false, abs.scalar());
        assertEquals(true, abs.q());
        assertEquals(1, abs.esz());
        assertEquals(0, abs.rd());
        assertEquals(1, abs.rn());

        assertEquals(Ir64VectorFpUnaryOp.NEG, unaryHalf(0x6ef8f820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMGT0, unaryHalf(0x4ef8c820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMGE0, unaryHalf(0x6ef8c820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMEQ0, unaryHalf(0x4ef8d820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMLE0, unaryHalf(0x6ef8d820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMLT0, unaryHalf(0x4ef8e820).op());

        // q=0 (.4h): FABS_v continua com esz=HALFWORD, só `q` muda.
        Ir64Op.VectorFpArithmeticUnary abs4h = unaryHalf(0x0ef8f820);
        assertEquals(Ir64VectorFpUnaryOp.ABS, abs4h.op());
        assertEquals(false, abs4h.q());
        assertEquals(1, abs4h.esz());
    }

    @Test
    void halfPrecisionTwoRegMiscScalarDecodesUnderFp16() {
        // fcmgt/fcmge/fcmeq/fcmle/fcmlt h0,h1,#0 — golden. FABS/FNEG NÃO têm forma escalar aqui
        // (G8, mesma restrição de {@link #fpUnaryOpHasScalarForm} dos `_sd`).
        Ir64Op.VectorFpArithmeticUnary cmgt = unaryHalf(0x5ef8c820);
        assertEquals(Ir64VectorFpUnaryOp.CMGT0, cmgt.op());
        assertEquals(true, cmgt.scalar());
        assertEquals(1, cmgt.esz());

        assertEquals(Ir64VectorFpUnaryOp.CMGE0, unaryHalf(0x7ef8c820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMEQ0, unaryHalf(0x5ef8d820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMLE0, unaryHalf(0x7ef8d820).op());
        assertEquals(Ir64VectorFpUnaryOp.CMLT0, unaryHalf(0x5ef8e820).op());
    }

    @Test
    void halfPrecisionTwoRegMiscUnsupportedBelowFp16() {
        for (int word : new int[] {0x4ef8f820, 0x6ef8f820, 0x4ef8c820, 0x6ef8c820, 0x4ef8d820,
                0x6ef8d820, 0x4ef8e820, 0x5ef8c820, 0x7ef8c820, 0x5ef8d820, 0x7ef8d820, 0x5ef8e820}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DECODER, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, word));
        }
    }

    @Test
    void halfPrecisionNarrowUnaryVectorDecodesUnderFp16() {
        // fsqrt/frintn/frintm/frintp/frintz/frinta/frintx/frinti v0.8h,v1.8h — golden.
        assertEquals(Ir64VectorFpUnaryOp.SQRT, unaryHalf(0x6ef9f820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTN, unaryHalf(0x4e798820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTM, unaryHalf(0x4e799820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTP, unaryHalf(0x4ef98820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTZ, unaryHalf(0x4ef99820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTA, unaryHalf(0x6e798820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTX, unaryHalf(0x6e799820).op());
        assertEquals(Ir64VectorFpUnaryOp.RINTI, unaryHalf(0x6ef99820).op());

        // scvtf/ucvtf/fcvtns/fcvtnu/fcvtps/fcvtpu/fcvtms/fcvtmu/fcvtzs/fcvtzu/fcvtas/fcvtau (_vi) — golden.
        assertEquals(Ir64VectorFpUnaryOp.SCVTF, unaryHalf(0x4e79d820).op());
        assertEquals(Ir64VectorFpUnaryOp.UCVTF, unaryHalf(0x6e79d820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTNS, unaryHalf(0x4e79a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTNU, unaryHalf(0x6e79a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTPS, unaryHalf(0x4ef9a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTPU, unaryHalf(0x6ef9a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTMS, unaryHalf(0x4e79b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTMU, unaryHalf(0x6e79b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTZS, unaryHalf(0x4ef9b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTZU, unaryHalf(0x6ef9b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTAS, unaryHalf(0x4e79c820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTAU, unaryHalf(0x6e79c820).op());

        assertEquals(Ir64VectorFpUnaryOp.RECPE, unaryHalf(0x4ef9d820).op());
        assertEquals(Ir64VectorFpUnaryOp.RSQRTE, unaryHalf(0x6ef9d820).op());

        // q=0 (.4h) numa amostra de cada subfamília.
        assertEquals(false, unaryHalf(0x0e798820).q());
        assertEquals(false, unaryHalf(0x0ef9b820).q());
        assertEquals(false, unaryHalf(0x0e79c820).q());
    }

    @Test
    void halfPrecisionNarrowUnaryScalarDecodesUnderFp16() {
        // frecpe/frsqrte/frecpx h0,h1 — golden.
        Ir64Op.VectorFpArithmeticUnary recpe = unaryHalf(0x5ef9d820);
        assertEquals(Ir64VectorFpUnaryOp.RECPE, recpe.op());
        assertEquals(true, recpe.scalar());
        assertEquals(1, recpe.esz());
        assertEquals(Ir64VectorFpUnaryOp.RSQRTE, unaryHalf(0x7ef9d820).op());
        assertEquals(Ir64VectorFpUnaryOp.FRECPX, unaryHalf(0x5ef9f820).op());

        // scvtf/ucvtf/fcvtns/fcvtnu/fcvtps/fcvtpu/fcvtms/fcvtmu/fcvtzs/fcvtzu/fcvtas/fcvtau h0,h1
        // (`@icvt_h`, MESMA tabela/`opcode` das formas `_vi`, só `scalar` muda) — golden.
        assertEquals(Ir64VectorFpUnaryOp.SCVTF, unaryHalf(0x5e79d820).op());
        assertEquals(Ir64VectorFpUnaryOp.UCVTF, unaryHalf(0x7e79d820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTNS, unaryHalf(0x5e79a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTNU, unaryHalf(0x7e79a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTPS, unaryHalf(0x5ef9a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTPU, unaryHalf(0x7ef9a820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTMS, unaryHalf(0x5e79b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTMU, unaryHalf(0x7e79b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTZS, unaryHalf(0x5ef9b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTZU, unaryHalf(0x7ef9b820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTAS, unaryHalf(0x5e79c820).op());
        assertEquals(Ir64VectorFpUnaryOp.FCVTAU, unaryHalf(0x7e79c820).op());
    }

    @Test
    void halfPrecisionNarrowUnaryUnsupportedBelowFp16() {
        for (int word : new int[] {0x6ef9f820, 0x4e798820, 0x4e79d820, 0x6e79d820, 0x4e79a820,
                0x5ef9d820, 0x7ef9d820, 0x5ef9f820, 0x5e79d820, 0x7e79d820, 0x5e79a820}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DECODER, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, word));
        }
    }

    @Test
    void halfPrecisionSiblingSdUnaffected() {
        // As formas `_sd` irmãs (mesmos slots `Rm=0b0_0000`/`0b0_0001`) continuam idênticas com a
        // feature FP16 presente — prova de que abrir os slots novos não mexeu nos antigos.
        Ir64Op.VectorFpArithmeticUnary absS = (Ir64Op.VectorFpArithmeticUnary) decodeWord(FP16_DECODER, 0x4ea0f820);
        assertEquals(Ir64VectorFpUnaryOp.ABS, absS.op());
        assertEquals(2, absS.esz());
        Ir64Op.VectorFpArithmeticUnary sqrtD = (Ir64Op.VectorFpArithmeticUnary) decodeWord(FP16_DECODER, 0x6ee1f820);
        assertEquals(Ir64VectorFpUnaryOp.SQRT, sqrtD.op());
        assertEquals(3, sqrtD.esz());
    }

    @Test
    void halfPrecisionTwoRegMiscRegressionNegative() {
        // G8: opcode inexistente no slot `Rm=0b1_1000` (mesma palavra de FABS_v com `opcode` zerado)
        // e `Rm=0b1_1010` (fora dos dois slots FP16 e fora do padrão "across lanes") — os dois
        // continuam `unsupported` MESMO com a feature ligada.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x4ef80020));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x4efaf820));
    }

    @Test
    void bfcvtnStaysInOriginalSlotUnaffectedByFp16Slots() {
        // BFCVTN_v (B19.7, `FEAT_BF16`) vive no slot `Rm=0b0_0001` DE VERDADE (`opcode=0b0_1101`,
        // `a=1`, medido bit a bit via `aarch64-linux-gnu-as -march=armv8.2-a+fp16+bf16`) — Rm
        // inteiro DIFERENTE de `ADVSIMD_INT_RM_FP16_NARROW_UNARY` (`0b1_1001`), então não pode ser
        // alcançado pelos blocos novos desta task (Armadilha 4). Sem `FEAT_BF16` (só FP16), continua
        // `unsupported` — comportamento IDÊNTICO ao de antes desta task.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x0ea16820));
    }

    // ── B19.5.5: FEAT_FP16 — "three same" vetorial/escalar (`bit22=1 && bit10=1 && bit15=0`,
    // golden CONFERIDO com `aarch64-linux-gnu-as -march=armv8.2-a+fp16`, WSL) ──────────────────────

    @Test
    void halfPrecisionThreeSameVectorDecodesUnderFp16() {
        // fadd v0.8h, v1.8h, v2.8h
        Ir64Op.VectorFpArithmeticThreeSame add8h =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x4e421420);
        assertEquals(Ir64VectorFpThreeSameOp.ADD, add8h.op());
        assertEquals(false, add8h.scalar());
        assertEquals(true, add8h.q());
        assertEquals(1, add8h.esz());
        assertEquals(0, add8h.rd());
        assertEquals(1, add8h.rn());
        assertEquals(2, add8h.rm());

        // fadd v0.4h, v1.4h, v2.4h (Q=0)
        Ir64Op.VectorFpArithmeticThreeSame add4h =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x0e421420);
        assertEquals(Ir64VectorFpThreeSameOp.ADD, add4h.op());
        assertEquals(false, add4h.q());

        // fsub/fmax/fmin/fcmeq v0.8h, v1.8h, v2.8h — `bit23`(`a`) e `opcode` selecionam a operação,
        // exatamente como nos `_sd` (Armadilha 3).
        assertEquals(Ir64VectorFpThreeSameOp.SUB,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x4ec21420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MAX,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x4e423420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.MIN,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x4ec23420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.CMEQ,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x4e422420)).op());
    }

    @Test
    void halfPrecisionThreeSameScalarDecodesUnderFp16() {
        // fmulx h0, h1, h2
        Ir64Op.VectorFpArithmeticThreeSame mulx =
                (Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x5e421c20);
        assertEquals(Ir64VectorFpThreeSameOp.MULX, mulx.op());
        assertEquals(true, mulx.scalar());
        assertEquals(1, mulx.esz());
        assertEquals(0, mulx.rd());
        assertEquals(1, mulx.rn());
        assertEquals(2, mulx.rm());

        // fcmeq/fcmge/fcmgt/facge/facgt/fabd/frecps/frsqrts h0, h1, h2
        assertEquals(Ir64VectorFpThreeSameOp.CMEQ,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x5e422420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.CMGE,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x7e422420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.CMGT,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x7ec22420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.FACGE,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x7e422c20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.FACGT,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x7ec22c20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.ABD,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x7ec21420)).op());
        assertEquals(Ir64VectorFpThreeSameOp.RECPS,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x5e423c20)).op());
        assertEquals(Ir64VectorFpThreeSameOp.RSQRTS,
                ((Ir64Op.VectorFpArithmeticThreeSame) decodeWord(FP16_DECODER, 0x5ec23c20)).op());
    }

    @Test
    void halfPrecisionThreeSameUnsupportedBelowFp16() {
        // As mesmas 14 linhas continuam `unsupported` sem a feature — G8/Armadilha 7.
        for (int word : new int[] {0x4e421420, 0x0e421420, 0x4ec21420, 0x4e423420, 0x4ec23420,
                0x4e422420, 0x5e421c20, 0x5e422420, 0x7e422420, 0x7ec22420, 0x7e422c20, 0x7ec22c20,
                0x7ec21420, 0x5e423c20, 0x5ec23c20}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(DECODER, word));
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(ARMV8_1_DECODER, word));
        }
    }

    @Test
    void halfPrecisionThreeSameRegressionNegativeRdm() {
        // O teste mais importante da task: `sqrdmlah`/`sqrdmlsh v0.8h, v1.8h, v2.8h` (`esz=01`)
        // continuam decodificando como RDM sob `ARMV8_2_A` (que tem `FEAT_RDM` E `FEAT_FP16`) — só
        // `bit15` (dentro do `opcode`) separa os dois grupos (Armadilha 1).
        Ir64Op.VectorArithmeticThreeSame sqrdmlah =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(FP16_DECODER, 0x6e428420);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLAH, sqrdmlah.op());
        assertEquals(1, sqrdmlah.esz());
        Ir64Op.VectorArithmeticThreeSame sqrdmlsh =
                (Ir64Op.VectorArithmeticThreeSame) decodeWord(FP16_DECODER, 0x6e428c20);
        assertEquals(Ir64VectorThreeSameOp.SQRDMLSH, sqrdmlsh.op());
    }

    @Test
    void halfPrecisionThreeSameRegressionNegativePermuteAndCopy() {
        // `uzp1`/`zip1`/`tbl` (`bit10=0` no espaço deles) e `dup`/`umov` (`bit22=0`) continuam
        // decodificando sob `FEAT_FP16` ligada — os vizinhos que a auditoria de colisão mediu como
        // separados por bit FIXO (ver `## Contexto` da task).
        assertEquals(Ir64VectorPermuteOp.UZP1,
                ((Ir64Op.VectorPermute) decodeWord(FP16_DECODER, 0x4e421820)).op());
        assertEquals(Ir64VectorPermuteOp.ZIP1,
                ((Ir64Op.VectorPermute) decodeWord(FP16_DECODER, 0x4e423820)).op());
        Ir64Op.VectorTableLookup tbl = (Ir64Op.VectorTableLookup) decodeWord(FP16_DECODER, 0x0e020020);
        assertEquals(false, tbl.tbx());
        Ir64Op.VectorDuplicateElement dup =
                (Ir64Op.VectorDuplicateElement) decodeWord(FP16_DECODER, 0x4e0a0420);
        assertEquals(1, dup.esz());
        Ir64Op.VectorMoveElement umov =
                (Ir64Op.VectorMoveElement) decodeWord(FP16_DECODER, 0x0e0a3c20);
        assertEquals(false, umov.signed());
    }

    @Test
    void halfPrecisionThreeSameDoesNotSwallowFhmNeighbor() {
        // `fmlal`/`fmlsl v0.4s, v1.4h, v2.4h` (`FEAT_FHM`, também `@qrrr_h`) vivem em `bit21=1`
        // (medido: `0x4e22ec20`/`0x4ea2ec20`), fora do subespaço `bit21=0` que esta task abriu —
        // continuam `unsupported` sob `FEAT_FP16` sem `FEAT_FHM` (G8, "Não inclui" da task).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x4e22ec20));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16_DECODER, 0x4ea2ec20));
    }
}
