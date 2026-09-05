package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// AdvSIMD — deslocamento, saturação e estreitamento (B8.8): `SQ*`/`UQ*` (three same reaproveitado
/// de B8.7), `SUQADD`/`USQADD` (two-register misc reaproveitado), `SQXTN`/`SQXTUN`/`UQXTN` (narrow
/// unário, novo), `SQDMULL`/`SQDMLAL`/`SQDMLSL` (three different, novo), e o espaço de encoding
/// PRÓPRIO "shift by immediate" (`SSHR`/.../`SSHLL`/`USHLL`). Sem `aarch64-none-elf-as`/`objdump`
/// disponíveis nesta sessão (toolchain devkitA64 ausente) — palavras construídas por FÓRMULA a
/// partir dos campos do encoding real (`a64.decode`/`translate-a64.c`, ver Javadoc do decoder),
/// mesmo fallback de B10.2/B10.3. Cada `@Test` documenta os campos de origem usados na fórmula.
class Aarch64AdvSimdShiftSaturateDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Three same reaproveitado: SQADD/UQADD/SQSUB/UQSUB/SSHL/USHL/SRSHL/URSHL/SQSHL/UQSHL/ ────
    // ── SQRSHL/UQRSHL/SQDMULH/SQRDMULH (vetorial e escalar) ─────────────────────────────────────

    @Test
    void sqaddUqaddVectorByte() {
        // Q=0,U=0/1,size=00,Rm=2,opcode=00001,bit10=1,Rn=1,Rd=0
        Ir64Op.VectorArithmeticThreeSame sqadd = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e220c20);
        assertEquals(Ir64VectorThreeSameOp.SQADD, sqadd.op());
        assertEquals(false, sqadd.scalar());
        assertEquals(0, sqadd.esz());
        assertEquals(0, sqadd.rd());
        assertEquals(1, sqadd.rn());
        assertEquals(2, sqadd.rm());

        Ir64Op.VectorArithmeticThreeSame uqadd = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e220c20);
        assertEquals(Ir64VectorThreeSameOp.UQADD, uqadd.op());
    }

    @Test
    void sqsubUqsubVectorByte() {
        assertEquals(Ir64VectorThreeSameOp.SQSUB, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e222c20)).op());
        assertEquals(Ir64VectorThreeSameOp.UQSUB, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e222c20)).op());
    }

    @Test
    void shiftByRegisterVectorByte() {
        assertEquals(Ir64VectorThreeSameOp.SSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e224420)).op());
        assertEquals(Ir64VectorThreeSameOp.USHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e224420)).op());
        assertEquals(Ir64VectorThreeSameOp.SRSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e225420)).op());
        assertEquals(Ir64VectorThreeSameOp.URSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e225420)).op());
        assertEquals(Ir64VectorThreeSameOp.SQSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e224c20)).op());
        assertEquals(Ir64VectorThreeSameOp.UQSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e224c20)).op());
        assertEquals(Ir64VectorThreeSameOp.SQRSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e225c20)).op());
        assertEquals(Ir64VectorThreeSameOp.UQRSHL, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e225c20)).op());
    }

    @Test
    void sqdmulhSqrdmulhVectorHalfword() {
        Ir64Op.VectorArithmeticThreeSame sqdmulh = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x0e62b420);
        assertEquals(Ir64VectorThreeSameOp.SQDMULH, sqdmulh.op());
        assertEquals(1, sqdmulh.esz());
        assertEquals(Ir64VectorThreeSameOp.SQRDMULH, ((Ir64Op.VectorArithmeticThreeSame) decodeWord(0x2e62b420)).op());
    }

    @Test
    void sqaddScalarAcceptsAnyEsz() {
        // Prefixo escalar (bit30=1,bit28=1), size=00, opcode=00001 (SQADD), U=0
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5e220c20);
        assertEquals(Ir64VectorThreeSameOp.SQADD, op.op());
        assertEquals(true, op.scalar());
        assertEquals(0, op.esz(), "SQADD_s aceita esz=0 (byte) — diferente de ADD_s, que é D-only");
    }

    @Test
    void addScalarRejectsWrongEszButAcceptsD() {
        // ADD_s (opcode=10000) com size=00 (errado, deveria ser 11) — G8: achado real da B8.8,
        // B8.7 forçava esz=3 e mascarava este caso (aceitava silenciosamente).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e228420));
        // Mesma instrução com size=11 (correto) decodifica normalmente.
        Ir64Op.VectorArithmeticThreeSame op = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5ee28420);
        assertEquals(Ir64VectorThreeSameOp.ADD, op.op());
        assertEquals(3, op.esz());
    }

    @Test
    void sqdmulhScalarRejectsByteAcceptsHalfword() {
        Ir64Op.VectorArithmeticThreeSame ok = (Ir64Op.VectorArithmeticThreeSame) decodeWord(0x5e62b420);
        assertEquals(Ir64VectorThreeSameOp.SQDMULH, ok.op());
        assertEquals(1, ok.esz());
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e22b420),
                "SQDMULH_s só existe H/S — byte é reservado");
    }

    // ── Two-register misc reaproveitado: SUQADD/USQADD ──────────────────────────────────────────

    @Test
    void suqaddUsqaddVectorAndScalar() {
        Ir64Op.VectorArithmeticUnary v = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0e203820);
        assertEquals(Ir64VectorUnaryOp.SUQADD, v.op());
        assertEquals(false, v.scalar());
        assertEquals(Ir64VectorUnaryOp.USQADD, ((Ir64Op.VectorArithmeticUnary) decodeWord(0x2e203820)).op());

        Ir64Op.VectorArithmeticUnary s = (Ir64Op.VectorArithmeticUnary) decodeWord(0x5e203820);
        assertEquals(Ir64VectorUnaryOp.SUQADD, s.op());
        assertEquals(true, s.scalar());
        assertEquals(0, s.esz(), "SUQADD_s aceita esz variável — diferente de ABS_s/NEG_s, D-only");
    }

    // ── Narrow unário saturante: SQXTN/SQXTUN/UQXTN (novo, Kind 64) ─────────────────────────────

    @Test
    void sqxtnUqxtnVectorHalfwordToByte() {
        Ir64Op.VectorArithmeticNarrowUnary sqxtn = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x0e214820);
        assertEquals(Ir64VectorNarrowUnaryOp.SQXTN, sqxtn.op());
        assertEquals(false, sqxtn.scalar());
        assertEquals(0, sqxtn.esz());

        Ir64Op.VectorArithmeticNarrowUnary uqxtn = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x2e214820);
        assertEquals(Ir64VectorNarrowUnaryOp.UQXTN, uqxtn.op());
    }

    @Test
    void sqxtunVectorHalfwordToByte() {
        Ir64Op.VectorArithmeticNarrowUnary op = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x2e212820);
        assertEquals(Ir64VectorNarrowUnaryOp.SQXTUN, op.op());
    }

    @Test
    void sqxtnScalar() {
        Ir64Op.VectorArithmeticNarrowUnary op = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x5e214820);
        assertEquals(Ir64VectorNarrowUnaryOp.SQXTN, op.op());
        assertEquals(true, op.scalar());
    }

    // ── Three different: SQDMULL/SQDMLAL/SQDMLSL (novo, reaproveita Kind existente) ─────────────

    @Test
    void sqdmullSqdmlalSqdmlslVectorHalfword() {
        Ir64Op.VectorArithmeticWidening mull = (Ir64Op.VectorArithmeticWidening) decodeWord(0x0e62d020);
        assertEquals(Ir64VectorWideningOp.SQDMULL, mull.op());
        assertEquals(1, mull.esz());
        assertEquals(Ir64VectorWideningOp.SQDMLAL, ((Ir64Op.VectorArithmeticWidening) decodeWord(0x0e629020)).op());
        assertEquals(Ir64VectorWideningOp.SQDMLSL, ((Ir64Op.VectorArithmeticWidening) decodeWord(0x0e62b020)).op());
    }

    @Test
    void sqdmullRejectsByte() {
        // Mesmo encoding de sqdmullSqdmlalSqdmlslVectorHalfword mas com size=00 (byte) — SQDMULL
        // só existe H→S/S→D (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x0e22d020));
    }

    // ── "Shift by immediate" (espaço de encoding próprio, novo) ─────────────────────────────────

    @Test
    void sshrUshrVectorWordShift4() {
        // Q=0,U=0/1,immh=0111,immb=100 (combined=60,esize=32,rightShift=64-60=4),opcode=00000
        Ir64Op.VectorShiftImmediate sshr = (Ir64Op.VectorShiftImmediate) decodeWord(0x0f3c0420);
        assertEquals(Ir64VectorShiftOp.SSHR, sshr.op());
        assertEquals(false, sshr.scalar());
        assertEquals(2, sshr.esz());
        assertEquals(4, sshr.shift());
        Ir64Op.VectorShiftImmediate ushr = (Ir64Op.VectorShiftImmediate) decodeWord(0x2f3c0420);
        assertEquals(Ir64VectorShiftOp.USHR, ushr.op());
    }

    @Test
    void srshrVectorWordShift4() {
        Ir64Op.VectorShiftImmediate op = (Ir64Op.VectorShiftImmediate) decodeWord(0x0f3c2420);
        assertEquals(Ir64VectorShiftOp.SRSHR, op.op());
        assertEquals(4, op.shift());
    }

    @Test
    void ssraUrsraVectorWordShift1() {
        // combined=63,esize=32,rightShift=64-63=1
        Ir64Op.VectorShiftImmediate ssra = (Ir64Op.VectorShiftImmediate) decodeWord(0x0f3f1420);
        assertEquals(Ir64VectorShiftOp.SSRA, ssra.op());
        assertEquals(1, ssra.shift());
        Ir64Op.VectorShiftImmediate ursra = (Ir64Op.VectorShiftImmediate) decodeWord(0x2f3f3420);
        assertEquals(Ir64VectorShiftOp.URSRA, ursra.op());
    }

    @Test
    void sriVectorByteShift4() {
        // esz0(byte,esize8),combined=12,rightShift=16-12=4, opcode=01000,U=1
        Ir64Op.VectorShiftImmediate op = (Ir64Op.VectorShiftImmediate) decodeWord(0x2f0c4420);
        assertEquals(Ir64VectorShiftOp.SRI, op.op());
        assertEquals(0, op.esz());
        assertEquals(4, op.shift());
    }

    @Test
    void shlSliVectorByte() {
        // esquerda: leftShift=combined-esize
        Ir64Op.VectorShiftImmediate shl = (Ir64Op.VectorShiftImmediate) decodeWord(0x0f0b5420);
        assertEquals(Ir64VectorShiftOp.SHL, shl.op());
        assertEquals(3, shl.shift(), "combined=11,esize=8,leftShift=3");
        Ir64Op.VectorShiftImmediate sli = (Ir64Op.VectorShiftImmediate) decodeWord(0x2f0c5420);
        assertEquals(Ir64VectorShiftOp.SLI, sli.op());
        assertEquals(4, sli.shift(), "combined=12,esize=8,leftShift=4");
    }

    @Test
    void sqshlImmediateAndSqshluVectorByte() {
        Ir64Op.VectorShiftImmediate sqshl = (Ir64Op.VectorShiftImmediate) decodeWord(0x0f0a7420);
        assertEquals(Ir64VectorShiftOp.SQSHL, sqshl.op());
        assertEquals(2, sqshl.shift());
        Ir64Op.VectorShiftImmediate sqshlu = (Ir64Op.VectorShiftImmediate) decodeWord(0x2f096420);
        assertEquals(Ir64VectorShiftOp.SQSHLU, sqshlu.op());
        assertEquals(1, sqshlu.shift());
    }

    @Test
    void sshrScalarAtShift64Boundary() {
        // Fronteira: immh=1000,immb=000 -> combined=64,esize=64(D),rightShift=128-64=64 — testa
        // especificamente o guarda-corpo do deslocamento Java (`>>`/`>>>` por 64 não são 0/replica
        // de sinal nativamente).
        Ir64Op.VectorShiftImmediate op = (Ir64Op.VectorShiftImmediate) decodeWord(0x5f400420);
        assertEquals(Ir64VectorShiftOp.SSHR, op.op());
        assertEquals(true, op.scalar());
        assertEquals(3, op.esz());
        assertEquals(64, op.shift());
    }

    @Test
    void reservedImmhZeroFallsIntoModifiedImmediateNotThisClass() {
        // B19.6: `immh=0000` NÃO é reservado de verdade — é exatamente onde `Vimm`/`FMOVI_v_h`
        // moram ("1-reg-and-modified-immediate" reusa o MESMO prefixo "shift by immediate"). Este
        // vetor específico (`bits[11:10]="01"`, `cmode=0000`) agora decodifica como `MOVI`; o
        // subespaço GENUINAMENTE reservado dentro de `immh=0` é `bits[11:10]` fora de `{01,11}`,
        // coberto por `Aarch64B196DiversosDecoderTest#reservedFixedTwoBitsWithinModifiedImmediateSpaceRejected`.
        assertTrue(decodeWord(0x0f000420) instanceof Ir64Op.AdvSimdModifiedImmediate64);
    }

    // ── "Shift by immediate" estreitando: SHRN/RSHRN/SQ*SHRN*/UQ*SHRN* ──────────────────────────

    @Test
    void shrnAndSaturatingNarrowVectorByte() {
        Ir64Op.VectorShiftNarrowImmediate shrn = (Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x0f0c8420);
        assertEquals(Ir64VectorShiftNarrowOp.SHRN, shrn.op());
        assertEquals(4, shrn.shift());
        assertEquals(Ir64VectorShiftNarrowOp.SQSHRUN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x2f0c8420)).op());
        assertEquals(Ir64VectorShiftNarrowOp.RSHRN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x0f0c8c20)).op());
        assertEquals(Ir64VectorShiftNarrowOp.SQRSHRUN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x2f0c8c20)).op());
        assertEquals(Ir64VectorShiftNarrowOp.SQSHRN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x0f0c9420)).op());
        assertEquals(Ir64VectorShiftNarrowOp.UQSHRN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x2f0c9420)).op());
        assertEquals(Ir64VectorShiftNarrowOp.SQRSHRN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x0f0c9c20)).op());
        assertEquals(Ir64VectorShiftNarrowOp.UQRSHRN, ((Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x2f0c9c20)).op());
    }

    @Test
    void sqshrnScalar() {
        Ir64Op.VectorShiftNarrowImmediate op = (Ir64Op.VectorShiftNarrowImmediate) decodeWord(0x5f0c9420);
        assertEquals(Ir64VectorShiftNarrowOp.SQSHRN, op.op());
        assertEquals(true, op.scalar());
    }

    @Test
    void shrnHasNoScalarForm() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5f0c8420));
    }

    // ── "Shift by immediate" alargando: SSHLL/USHLL ─────────────────────────────────────────────

    @Test
    void sshllUshllVectorByte() {
        Ir64Op.VectorShiftWidenImmediate sshll = (Ir64Op.VectorShiftWidenImmediate) decodeWord(0x0f0aa420);
        assertEquals(Ir64VectorShiftWidenOp.SSHLL, sshll.op());
        assertEquals(2, sshll.shift());
        Ir64Op.VectorShiftWidenImmediate ushll = (Ir64Op.VectorShiftWidenImmediate) decodeWord(0x2f08a420);
        assertEquals(Ir64VectorShiftWidenOp.USHLL, ushll.op());
        assertEquals(0, ushll.shift());
    }

    @Test
    void sshllHasNoScalarForm() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5f0aa420));
    }
}
