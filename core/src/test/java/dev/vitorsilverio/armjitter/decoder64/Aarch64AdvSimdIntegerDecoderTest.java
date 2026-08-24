package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// AdvSIMD inteiro — aritmética e comparação (B8.7): "three same"/"three same pairwise"/"three
/// different" (alargando/largo+estreito/estreitando)/"across lanes"/"two-register miscellaneous",
/// e as formas ESCALARES `V<n>.D` que reaproveitam os mesmos records. Corpus PRÓPRIO
/// (`aarch64-none-elf-as`/`objdump` reais, devkitA64) — cada `@Test` documenta o mnemônico de
/// origem; os campos esperados vêm da semântica do MNEMÔNICO, não recalculados do decoder.
class Aarch64AdvSimdIntegerDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Three same (vetorial) ───────────────────────────────────────────────────────────────────

    @Test
    void addVector8b() {
        // 0e228420: add v0.8b, v1.8b, v2.8b
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e228420);
        assertEquals(Ir64VectorThreeSameOp.ADD, op.op());
        assertEquals(false, op.q());
        assertEquals(0, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void subVector2d() {
        // 6ee28420: sub v0.2d, v1.2d, v2.2d
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x6ee28420);
        assertEquals(Ir64VectorThreeSameOp.SUB, op.op());
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
    }

    @Test
    void cmhiVector8b() {
        // 2e223420: cmhi v0.8b, v1.8b, v2.8b
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e223420);
        assertEquals(Ir64VectorThreeSameOp.CMHI, op.op());
    }

    @Test
    void cmtstVector8b() {
        // 0e228c20: cmtst v0.8b, v1.8b, v2.8b
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e228c20);
        assertEquals(Ir64VectorThreeSameOp.CMTST, op.op());
    }

    @Test
    void shaddAndUhaddVector8b() {
        // 0e220420: shadd v0.8b, v1.8b, v2.8b / 2e220420: uhadd v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorThreeSameOp.SHADD,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e220420)).op());
        assertEquals(Ir64VectorThreeSameOp.UHADD,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e220420)).op());
    }

    @Test
    void srhaddVector8b() {
        // 0e221420: srhadd v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorThreeSameOp.SRHADD,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e221420)).op());
    }

    @Test
    void smaxSminSabdSabaVector8b() {
        assertEquals(Ir64VectorThreeSameOp.SMAX,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e226420)).op()); // smax
        assertEquals(Ir64VectorThreeSameOp.UMIN,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e226c20)).op()); // umin
        assertEquals(Ir64VectorThreeSameOp.SABD,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e227420)).op()); // sabd
        assertEquals(Ir64VectorThreeSameOp.UABA,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e227c20)).op()); // uaba
    }

    @Test
    void mulPmulMlaMlsVector8b() {
        assertEquals(Ir64VectorThreeSameOp.MUL,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e229c20)).op()); // mul
        assertEquals(Ir64VectorThreeSameOp.PMUL,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e229c20)).op()); // pmul
        assertEquals(Ir64VectorThreeSameOp.MLA,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e229420)).op()); // mla
        assertEquals(Ir64VectorThreeSameOp.MLS,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e229420)).op()); // mls
    }

    @Test
    void mulReservedForDoubleword() {
        // MUL não tem forma doubleword real — sz=11 com o mesmo opcode(10011)/U=0 é reservado.
        // Palavra construída a partir do encoding real de "mul v0.8b,v1.8b,v2.8b" (0e229c20) com
        // q=1/size=11 (equivalente a "mul v0.2d,v1.2d,v2.2d", que não existe no hardware real).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4ee29c20));
    }

    @Test
    void pmulReservedForNonByte() {
        // PMUL só existe em byte — size!=00 com opcode=10011/U=1 é reservado (campo fixo real).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x2e629c20));
    }

    // ── Three same pairwise (vetorial) ──────────────────────────────────────────────────────────

    @Test
    void addpSmaxpUminpVector8b() {
        // 0e22bc20: addp v0.8b, v1.8b, v2.8b
        Ir64Op.VectorArithmeticPairwise addp = (Ir64Op.VectorArithmeticPairwise) decodeWord(0x0e22bc20);
        assertEquals(Ir64VectorPairwiseOp.ADD, addp.op());
        assertEquals(1, addp.rn());
        assertEquals(2, addp.rm());
        // 0e22a420: smaxp v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorPairwiseOp.SMAX,
                ((Ir64Op.VectorArithmeticPairwise) decodeWord(0x0e22a420)).op());
        // 2e22ac20: uminp v0.8b, v1.8b, v2.8b
        assertEquals(Ir64VectorPairwiseOp.UMIN,
                ((Ir64Op.VectorArithmeticPairwise) decodeWord(0x2e22ac20)).op());
    }

    // ── Three different: alargando ──────────────────────────────────────────────────────────────

    @Test
    void smullUmullQSelectsHalf() {
        // 0e22c020: smull v0.8h, v1.8b, v2.8b (q=0, metade baixa)
        Ir64Op.VectorArithmeticWidening low = (Ir64Op.VectorArithmeticWidening) decodeWord(0x0e22c020);
        assertEquals(Ir64VectorWideningOp.SMULL, low.op());
        assertEquals(false, low.q());
        assertEquals(0, low.esz());
        // 4e22c020: smull2 v0.8h, v1.16b, v2.16b (q=1, metade alta)
        Ir64Op.VectorArithmeticWidening high = (Ir64Op.VectorArithmeticWidening) decodeWord(0x4e22c020);
        assertEquals(Ir64VectorWideningOp.SMULL, high.op());
        assertEquals(true, high.q());
        // 2e22c020: umull v0.8h, v1.8b, v2.8b
        assertEquals(Ir64VectorWideningOp.UMULL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x2e22c020)).op());
    }

    @Test
    void smlalUmlalSmlslUmlsl() {
        assertEquals(Ir64VectorWideningOp.SMLAL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x0e228020)).op()); // smlal
        assertEquals(Ir64VectorWideningOp.UMLAL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x2e228020)).op()); // umlal
        assertEquals(Ir64VectorWideningOp.SMLSL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x0e22a020)).op()); // smlsl
        assertEquals(Ir64VectorWideningOp.UMLSL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x2e22a020)).op()); // umlsl
    }

    @Test
    void saddlUsublSabalUabdl() {
        assertEquals(Ir64VectorWideningOp.SADDL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x0e220020)).op()); // saddl
        assertEquals(Ir64VectorWideningOp.USUBL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x2e222020)).op()); // usubl
        assertEquals(Ir64VectorWideningOp.SABAL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x0e225020)).op()); // sabal
        assertEquals(Ir64VectorWideningOp.UABDL,
                ((Ir64Op.VectorArithmeticWidening) decodeWord(0x2e227020)).op()); // uabdl
    }

    // ── Three different: largo+estreito ─────────────────────────────────────────────────────────

    @Test
    void saddwUaddwSsubwUsubw() {
        // 0e221020: saddw v0.8h, v1.8h, v2.8b
        Ir64Op.VectorArithmeticWide op = (Ir64Op.VectorArithmeticWide) decodeWord(0x0e221020);
        assertEquals(Ir64VectorWideOp.SADDW, op.op());
        assertEquals(0, op.esz());
        assertEquals(Ir64VectorWideOp.UADDW, ((Ir64Op.VectorArithmeticWide) decodeWord(0x2e221020)).op());
        assertEquals(Ir64VectorWideOp.SSUBW, ((Ir64Op.VectorArithmeticWide) decodeWord(0x0e223020)).op());
        assertEquals(Ir64VectorWideOp.USUBW, ((Ir64Op.VectorArithmeticWide) decodeWord(0x2e223020)).op());
    }

    // ── Three different: estreitando ────────────────────────────────────────────────────────────

    @Test
    void addhnRaddhnSubhnRsubhn() {
        // 0e224020: addhn v0.8b, v1.8h, v2.8h (q=0, metade baixa)
        Ir64Op.VectorArithmeticNarrow addhn = (Ir64Op.VectorArithmeticNarrow) decodeWord(0x0e224020);
        assertEquals(Ir64VectorNarrowOp.ADDHN, addhn.op());
        assertEquals(false, addhn.q());
        assertEquals(0, addhn.esz());
        assertEquals(Ir64VectorNarrowOp.RADDHN,
                ((Ir64Op.VectorArithmeticNarrow) decodeWord(0x2e224020)).op());
        assertEquals(Ir64VectorNarrowOp.SUBHN,
                ((Ir64Op.VectorArithmeticNarrow) decodeWord(0x0e226020)).op());
        assertEquals(Ir64VectorNarrowOp.RSUBHN,
                ((Ir64Op.VectorArithmeticNarrow) decodeWord(0x2e226020)).op());
    }

    // ── Across lanes ─────────────────────────────────────────────────────────────────────────────

    @Test
    void addvSaddlvUaddlv() {
        // 0e31b820: addv b0, v1.8b
        Ir64Op.VectorAcrossLanes addv = (Ir64Op.VectorAcrossLanes) decodeWord(0x0e31b820);
        assertEquals(Ir64VectorAcrossLanesOp.ADDV, addv.op());
        assertEquals(0, addv.esz());
        assertEquals(1, addv.rn());
        assertEquals(0, addv.rd());
        // 0e303820: saddlv h0, v1.8b / 2e303820: uaddlv h0, v1.8b
        assertEquals(Ir64VectorAcrossLanesOp.SADDLV,
                ((Ir64Op.VectorAcrossLanes) decodeWord(0x0e303820)).op());
        assertEquals(Ir64VectorAcrossLanesOp.UADDLV,
                ((Ir64Op.VectorAcrossLanes) decodeWord(0x2e303820)).op());
    }

    @Test
    void smaxvUminvSminvUmaxvShareOpcodeDistinguishedByRm() {
        // op=10101 nos 4 casos — só o bit baixo de Rm + U distingue.
        assertEquals(Ir64VectorAcrossLanesOp.SMAXV,
                ((Ir64Op.VectorAcrossLanes) decodeWord(0x0e30a820)).op()); // smaxv b0, v1.8b
        assertEquals(Ir64VectorAcrossLanesOp.UMAXV,
                ((Ir64Op.VectorAcrossLanes) decodeWord(0x2e30a820)).op()); // umaxv b0, v1.8b
        assertEquals(Ir64VectorAcrossLanesOp.SMINV,
                ((Ir64Op.VectorAcrossLanes) decodeWord(0x0e31a820)).op()); // sminv b0, v1.8b
        assertEquals(Ir64VectorAcrossLanesOp.UMINV,
                ((Ir64Op.VectorAcrossLanes) decodeWord(0x2e31a820)).op()); // uminv b0, v1.8b
    }

    // ── Two-register miscellaneous (vetorial) ───────────────────────────────────────────────────

    @Test
    void absNegVector8b() {
        // 0e20b820: abs v0.8b, v1.8b / 2e20b820: neg v0.8b, v1.8b
        Ir64Op.VectorArithmeticUnary abs = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0e20b820);
        assertEquals(Ir64VectorUnaryOp.ABS, abs.op());
        assertEquals(1, abs.rn());
        assertEquals(Ir64VectorUnaryOp.NEG, ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e20b820)).op());
    }

    @Test
    void compareAgainstZeroVector8b() {
        assertEquals(Ir64VectorUnaryOp.CMEQ0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x0e209820)).op()); // cmeq #0
        assertEquals(Ir64VectorUnaryOp.CMGT0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x0e208820)).op()); // cmgt #0
        assertEquals(Ir64VectorUnaryOp.CMGE0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e208820)).op()); // cmge #0
        assertEquals(Ir64VectorUnaryOp.CMLT0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x0e20a820)).op()); // cmlt #0
        assertEquals(Ir64VectorUnaryOp.CMLE0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e209820)).op()); // cmle #0
    }

    @Test
    void saddlpUaddlpSadalpUadalp() {
        // 0e202820: saddlp v0.4h, v1.8b
        Ir64Op.VectorArithmeticUnary saddlp = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0e202820);
        assertEquals(Ir64VectorUnaryOp.SADDLP, saddlp.op());
        assertEquals(Ir64VectorUnaryOp.UADDLP,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e202820)).op());
        assertEquals(Ir64VectorUnaryOp.SADALP,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x0e206820)).op());
        assertEquals(Ir64VectorUnaryOp.UADALP,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e206820)).op());
    }

    // ── Formas escalares (D-only, esz=3/q=false forçados pelo decoder) ─────────────────────────────

    @Test
    void addSubScalarD() {
        // 5ee28420: add d0, d1, d2 / 7ee28420: sub d0, d1, d2
        Ir64Op.VectorArithmeticThreeSame add = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5ee28420);
        assertEquals(Ir64VectorThreeSameOp.ADD, add.op());
        assertEquals(false, add.q());
        assertEquals(3, add.esz());
        assertEquals(Ir64VectorThreeSameOp.SUB,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x7ee28420)).op());
    }

    @Test
    void compareScalarD() {
        assertEquals(Ir64VectorThreeSameOp.CMGT,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5ee23420)).op()); // cmgt d0,d1,d2
        assertEquals(Ir64VectorThreeSameOp.CMHI,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x7ee23420)).op()); // cmhi d0,d1,d2
        assertEquals(Ir64VectorThreeSameOp.CMGE,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5ee23c20)).op()); // cmge d0,d1,d2
        assertEquals(Ir64VectorThreeSameOp.CMHS,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x7ee23c20)).op()); // cmhs d0,d1,d2
        assertEquals(Ir64VectorThreeSameOp.CMTST,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5ee28c20)).op()); // cmtst d0,d1,d2
        assertEquals(Ir64VectorThreeSameOp.CMEQ,
                ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x7ee28c20)).op()); // cmeq d0,d1,d2
    }

    @Test
    void shaddScalarIsUnsupported() {
        // SHADD_s não existe no manual real (opcode 00000 só é definido para a forma vetorial) —
        // combinação inventada (prefixo escalar real + opcode/Rm/Rn/Rd de SHADD) tem que ser
        // recusada, não silenciosamente aceita como se fosse SHADD.
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5ee20420));
    }

    @Test
    void absNegCompareZeroScalarD() {
        // 5ee0b820: abs d0, d1 / 7ee0b820: neg d0, d1
        assertEquals(Ir64VectorUnaryOp.ABS, ((Ir64Op.VectorArithmeticUnary) decodeWord(0x5ee0b820)).op());
        assertEquals(Ir64VectorUnaryOp.NEG, ((Ir64Op.VectorArithmeticUnary) decodeWord(0x7ee0b820)).op());
        assertEquals(Ir64VectorUnaryOp.CMEQ0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x5ee09820)).op()); // cmeq d0,d1,#0
        assertEquals(Ir64VectorUnaryOp.CMGT0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x5ee08820)).op()); // cmgt d0,d1,#0
        assertEquals(Ir64VectorUnaryOp.CMGE0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x7ee08820)).op()); // cmge d0,d1,#0
        assertEquals(Ir64VectorUnaryOp.CMLT0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x5ee0a820)).op()); // cmlt d0,d1,#0
        assertEquals(Ir64VectorUnaryOp.CMLE0,
                ((Ir64Op.VectorArithmeticUnary) decodeWord(0x7ee09820)).op()); // cmle d0,d1,#0
    }

    @Test
    void addpScalarD() {
        // 5ef1b820: addp d0, v1.2d
        Ir64Op.VectorScalarPairwiseAdd op = (Ir64Op.VectorScalarPairwiseAdd) decodeWord(0x5ef1b820);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }
}
