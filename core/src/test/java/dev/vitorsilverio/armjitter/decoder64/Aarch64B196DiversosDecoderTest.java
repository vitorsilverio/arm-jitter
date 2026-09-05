package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.6 — os 10 encodings avulsos do épico B19 (`SYS`/`SYSL` `op0=1,2`, `PRFM` literal, `PACGA`,
/// `ABS` geral, `DUP` escalar, `FMOV` `Vn.D[1]`, `Vimm`/`FMOVI_v_h`). Vetores golden conferidos com
/// `aarch64-none-elf-as`/`objdump` (devkitA64, via WSL — `.arch` por bloco, ver a task).
class Aarch64B196DiversosDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder PAUTH_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);
    private static final Aarch64Decoder CSSC_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_9_A);
    private static final Aarch64Decoder FP16_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);

    // -- golden: aarch64-none-elf-as/objdump (devkitA64, WSL) --
    private static final int SYS_OP0_1_GENERIC = 0xD5091265;      // sys #1, c1, c2, #3, x5
    private static final int SYS_OP0_2_GENERIC_WRITE = 0xD5111265; // msr S2_1_C1_C2_3, x5
    private static final int SYS_OP0_2_GENERIC_READ = 0xD5311266;  // mrs x6, S2_1_C1_C2_3
    private static final int TLBI_VMALLE1 = 0xD508871F;
    private static final int IC_IALLUIS = 0xD508711F;
    private static final int PRFM_LITERAL = 0xD8000020;           // prfm pldl1keep, <label>
    private static final int PACGA_X0_X1_X2 = 0x9AC23020;
    private static final int ABS_X0_X1 = 0xDAC02020;
    private static final int DUP_B0_V1_B7 = 0x5E0F0420;           // dup b0, v1.b[7]
    private static final int DUP_D0_V1_D1 = 0x5E180420;           // dup d0, v1.d[1]
    private static final int FMOV_X0_V1_D1 = 0x9EAE0020;
    private static final int FMOV_V0_D1_X1 = 0x9EAF0020;
    private static final int MOVI_V0_2D = 0x6F02E6A0;             // movi v0.2d, #0xff00ff00ff00ff
    private static final int MOVI_V0_4H = 0x0F008640;             // movi v0.4h, #0x12
    private static final int MVNI_V0_4S_SHIFT = 0x6F002420;       // mvni v0.4s, #0x1, lsl #8
    private static final int ORR_V0_4H = 0x0F009420;              // orr v0.4h, #0x1
    private static final int BIC_V0_4H = 0x2F009420;              // bic v0.4h, #0x1
    private static final int FMOV_V0_2D_IMM = 0x6F00F400;         // fmov v0.2d, #2.0
    private static final int FMOVI_V0_4H = 0x0F03FE00;            // fmov v0.4h, #1.0 (FEAT_FP16)

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Bloco A: SYS/SYSL op0=1/op0=2 ──────────────────────────────────────────────────────────

    @Test
    void sysOp0_1GenericIsNopNotException() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) decode(DEFAULT_DECODER, SYS_OP0_1_GENERIC);
        assertEquals(Ir64SystemInstructionOp.MAINTENANCE_UNMODELED_NOP, op.opcode());
    }

    @Test
    void sysOp0_2GenericWriteAndReadResolveToDebugUnmodeled() {
        Ir64Op.SystemRegister write = (Ir64Op.SystemRegister) decode(DEFAULT_DECODER, SYS_OP0_2_GENERIC_WRITE);
        assertEquals(Aarch64SystemRegisterId.DEBUG_UNMODELED, write.register());
        assertFalse(write.read());
        Ir64Op.SystemRegister read = (Ir64Op.SystemRegister) decode(DEFAULT_DECODER, SYS_OP0_2_GENERIC_READ);
        assertEquals(Aarch64SystemRegisterId.DEBUG_UNMODELED, read.register());
        assertTrue(read.read());
    }

    @Test
    void tlbiAndIcStillWorkUnchanged() {
        Ir64Op.SystemInstruction tlbi = (Ir64Op.SystemInstruction) decode(DEFAULT_DECODER, TLBI_VMALLE1);
        assertEquals(Ir64SystemInstructionOp.TLBI_ALL, tlbi.opcode());
        Ir64Op.SystemInstruction ic = (Ir64Op.SystemInstruction) decode(DEFAULT_DECODER, IC_IALLUIS);
        assertEquals(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP, ic.opcode());
    }

    // ── Bloco B: PRFM (literal) ─────────────────────────────────────────────────────────────────

    @Test
    void prfmLiteralIsNopHint() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) decode(DEFAULT_DECODER, PRFM_LITERAL);
        assertEquals(Ir64SystemInstructionOp.NOP_HINT, op.opcode());
    }

    // ── Bloco C: PACGA ──────────────────────────────────────────────────────────────────────────

    @Test
    void pacgaGatedByPointerAuthentication() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, PACGA_X0_X1_X2));
        Ir64Op.PointerAuthGeneric op = (Ir64Op.PointerAuthGeneric) decode(PAUTH_DECODER, PACGA_X0_X1_X2);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    // ── Bloco D: ABS geral ──────────────────────────────────────────────────────────────────────

    @Test
    void absGatedByCssc() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, ABS_X0_X1));
        Ir64Op.AbsGeneral op = (Ir64Op.AbsGeneral) decode(CSSC_DECODER, ABS_X0_X1);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertTrue(op.wide());
    }

    // ── Bloco E: DUP escalar ────────────────────────────────────────────────────────────────────

    @Test
    void dupScalarByteAndDoubleword() {
        Ir64Op.VectorDuplicateElementScalar b = (Ir64Op.VectorDuplicateElementScalar) decode(DEFAULT_DECODER, DUP_B0_V1_B7);
        assertEquals(0, b.esz());
        assertEquals(0, b.rd());
        assertEquals(1, b.rn());
        assertEquals(7, b.index());
        Ir64Op.VectorDuplicateElementScalar d = (Ir64Op.VectorDuplicateElementScalar) decode(DEFAULT_DECODER, DUP_D0_V1_D1);
        assertEquals(3, d.esz());
        assertEquals(1, d.index());
    }

    @Test
    void dupScalarNeverMisdecodedAsShaThreeRegister() {
        // opcode==1 (bit10=1) nunca deve cair no dispatch de SHA (todas as opcodes SHA são pares).
        assertTrue(decode(DEFAULT_DECODER, DUP_B0_V1_B7) instanceof Ir64Op.VectorDuplicateElementScalar);
    }

    // ── Bloco F: FMOV Vn.D[1] ───────────────────────────────────────────────────────────────────

    @Test
    void fmovHighHalfBothDirections() {
        Ir64Op.Fp64HighHalfMove toGp = (Ir64Op.Fp64HighHalfMove) decode(DEFAULT_DECODER, FMOV_X0_V1_D1);
        assertFalse(toGp.toFloat());
        assertEquals(0, toGp.gpReg());
        assertEquals(1, toGp.fpReg());
        Ir64Op.Fp64HighHalfMove toFp = (Ir64Op.Fp64HighHalfMove) decode(DEFAULT_DECODER, FMOV_V0_D1_X1);
        assertTrue(toFp.toFloat());
        assertEquals(0, toFp.fpReg());
        assertEquals(1, toFp.gpReg());
    }

    @Test
    void fmovHighHalfRequiresSf1() {
        int sf0 = FMOV_X0_V1_D1 & ~(1 << 31);
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, sf0));
    }

    // ── Bloco G: Vimm/FMOVI_v_h ─────────────────────────────────────────────────────────────────

    @Test
    void movi2dSixtyFourBitPattern() {
        Ir64Op.AdvSimdModifiedImmediate64 op = (Ir64Op.AdvSimdModifiedImmediate64) decode(DEFAULT_DECODER, MOVI_V0_2D);
        assertEquals(AdvSimdModifiedImmediateOp.MOV, op.op());
        assertTrue(op.q());
        assertEquals(0, op.rd());
        assertEquals(0x00FF00FF00FF00FFL, op.imm64());
    }

    @Test
    void movi4hReplicatesHalfword() {
        Ir64Op.AdvSimdModifiedImmediate64 op = (Ir64Op.AdvSimdModifiedImmediate64) decode(DEFAULT_DECODER, MOVI_V0_4H);
        assertEquals(AdvSimdModifiedImmediateOp.MOV, op.op());
        assertFalse(op.q());
        assertEquals(0x0012001200120012L, op.imm64());
    }

    @Test
    void mvniShiftedByte() {
        Ir64Op.AdvSimdModifiedImmediate64 op = (Ir64Op.AdvSimdModifiedImmediate64) decode(DEFAULT_DECODER, MVNI_V0_4S_SHIFT);
        assertEquals(AdvSimdModifiedImmediateOp.MVN, op.op());
        assertEquals(0x0000_0100_0000_0100L, op.imm64());
    }

    @Test
    void orrAndBicImmediate() {
        Ir64Op.AdvSimdModifiedImmediate64 orr = (Ir64Op.AdvSimdModifiedImmediate64) decode(DEFAULT_DECODER, ORR_V0_4H);
        assertEquals(AdvSimdModifiedImmediateOp.ORR, orr.op());
        Ir64Op.AdvSimdModifiedImmediate64 bic = (Ir64Op.AdvSimdModifiedImmediate64) decode(DEFAULT_DECODER, BIC_V0_4H);
        assertEquals(AdvSimdModifiedImmediateOp.BIC, bic.op());
        assertEquals(orr.imm64(), bic.imm64(), "MESMO imm64 expandido, operação diferente");
    }

    @Test
    void fmov64ImmediateIsMovWithDoublePrecisionValue() {
        Ir64Op.AdvSimdModifiedImmediate64 op = (Ir64Op.AdvSimdModifiedImmediate64) decode(DEFAULT_DECODER, FMOV_V0_2D_IMM);
        assertEquals(AdvSimdModifiedImmediateOp.MOV, op.op());
        assertEquals(Double.doubleToLongBits(2.0), op.imm64());
    }

    @Test
    void fmoviHalfGatedByFp16() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FMOVI_V0_4H));
        Ir64Op.AdvSimdModifiedImmediate64 op = (Ir64Op.AdvSimdModifiedImmediate64) decode(FP16_DECODER, FMOVI_V0_4H);
        assertEquals(AdvSimdModifiedImmediateOp.MOV, op.op());
        long half1_0 = 0x3C00L; // binary16(1.0)
        assertEquals(half1_0 | (half1_0 << 16) | (half1_0 << 32) | (half1_0 << 48), op.imm64());
    }

    @Test
    void reservedFixedTwoBitsWithinModifiedImmediateSpaceRejected() {
        // bits[11:10] != "01" (e != "11", que é FMOVI_v_h com cmode=1111) — reservado.
        int reserved = MOVI_V0_4H & ~(0b11 << 10); // força bits[11:10]="00"
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, reserved));
    }
}
