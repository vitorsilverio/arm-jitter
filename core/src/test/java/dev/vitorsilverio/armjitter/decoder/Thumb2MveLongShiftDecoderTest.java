package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp.WideShiftOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.16 — `Thumb2MveLongShiftDecoder`: os 19 "long shifts" (`target/isa-decode/t32.decode`, linhas
/// 93-131). Raw montado bit a bit a partir dos formatos `@mve_sh_ri`/`@mve_shl_ri`/`@mve_sh_rr`/
/// `@mve_shl_rr` do arquivo real (`bits[31:20] = 1110_1010_0101`).
class Thumb2MveLongShiftDecoderTest {
    private static final int TOP12 = 0b1110_1010_0101 << 20;
    private static final int OP_LEFT = 0b00;
    private static final int OP_LOGICAL_RIGHT = 0b01;
    private static final int OP_ARITHMETIC_RIGHT = 0b10;
    private static final int OP_SATURATING = 0b11;

    /// `@mve_sh_ri`: `Rda`(19:16), `0`(15), imm3(14:12), `1111`(11:8), imm2(7:6), op(5:4), `1111`.
    private static int wordImmediate(int op, int rda, int shim) {
        return TOP12 | (rda << 16) | ((shim >>> 2) << 12) | (0xF << 8) | ((shim & 3) << 6) | (op << 4) | 0xF;
    }

    /// `@mve_shl_ri`: rdalo/2 (19:17), hi(16), `0`(15), imm3, rdahi>>1 (11:9), `1`(8), imm2, op, `1111`.
    private static int pairImmediate(int op, boolean high, int rdaLo, int rdaHi, int shim) {
        return TOP12 | ((rdaLo >>> 1) << 17) | ((high ? 1 : 0) << 16) | ((shim >>> 2) << 12)
                | ((rdaHi >>> 1) << 9) | (1 << 8) | ((shim & 3) << 6) | (op << 4) | 0xF;
    }

    /// `@mve_sh_rr`: `Rda`(19:16), `Rm`(15:12), `1111`(11:8), op(7:4), `1101`.
    private static int wordRegister(int op, int rda, int rm) {
        return TOP12 | (rda << 16) | (rm << 12) | (0xF << 8) | (op << 4) | 0xD;
    }

    /// `@mve_shl_rr`: rdalo/2 (19:17), hi(16), `Rm`(15:12), rdahi>>1 (11:9), `1`(8), op(7:4), `1101`.
    private static int pairRegister(int op, boolean high, int rdaLo, int rdaHi, int rm) {
        return TOP12 | ((rdaLo >>> 1) << 17) | ((high ? 1 : 0) << 16) | (rm << 12) | ((rdaHi >>> 1) << 9)
                | (1 << 8) | (op << 4) | 0xD;
    }

    private static DecodedInstruction decode(int raw) {
        return new Thumb2MveLongShiftDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0x100, Condition.AL);
    }

    private static void assertDecodes(int raw, WideShiftOperation operation, int shim, int rm, int rdaLo, int rdaHi) {
        DecodedInstruction instruction = decode(raw);
        assertNotNull(instruction, operation.name());
        assertEquals(InstructionKind.MVE_WIDE_SHIFT, instruction.kind(), operation.name());
        assertEquals(operation.ordinal(), instruction.immediate() & 0xFF, operation.name());
        assertEquals(shim, instruction.immediate() >>> 8, operation.name() + " shim");
        assertEquals(rm, instruction.secondSourceRegister(), operation.name() + " rm");
        assertEquals(rdaLo, instruction.destinationRegister(), operation.name() + " rdaLo");
        assertEquals(rdaHi, instruction.sourceRegister(), operation.name() + " rdaHi");
        assertEquals(Condition.AL, instruction.condition());
    }

    private static void assertUndefined(int raw) {
        DecodedInstruction instruction = decode(raw);
        assertNotNull(instruction, "UNDEFINED explícito, não fallthrough para MOV/ORR");
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── as 11 formas por imediato ───────────────────────────────────────────────────────────────

    @Test
    void decodesTheFourWordImmediateForms() {
        assertDecodes(wordImmediate(OP_LEFT, 3, 5), WideShiftOperation.UQSHL_RI, 5, -1, 3, -1);
        assertDecodes(wordImmediate(OP_LOGICAL_RIGHT, 4, 7), WideShiftOperation.URSHR_RI, 7, -1, 4, -1);
        assertDecodes(wordImmediate(OP_ARITHMETIC_RIGHT, 5, 31), WideShiftOperation.SRSHR_RI, 31, -1, 5, -1);
        assertDecodes(wordImmediate(OP_SATURATING, 6, 1), WideShiftOperation.SQSHL_RI, 1, -1, 6, -1);
    }

    @Test
    void decodesTheSevenPairImmediateForms() {
        assertDecodes(pairImmediate(OP_LEFT, false, 2, 5, 3), WideShiftOperation.LSLL_RI, 3, -1, 2, 5);
        assertDecodes(pairImmediate(OP_LEFT, true, 2, 5, 3), WideShiftOperation.UQSHLL_RI, 3, -1, 2, 5);
        assertDecodes(pairImmediate(OP_LOGICAL_RIGHT, false, 0, 1, 9), WideShiftOperation.LSRL_RI, 9, -1, 0, 1);
        assertDecodes(pairImmediate(OP_LOGICAL_RIGHT, true, 0, 1, 9), WideShiftOperation.URSHRL_RI, 9, -1, 0, 1);
        assertDecodes(pairImmediate(OP_ARITHMETIC_RIGHT, false, 14, 11, 20), WideShiftOperation.ASRL_RI, 20, -1, 14, 11);
        assertDecodes(pairImmediate(OP_ARITHMETIC_RIGHT, true, 14, 11, 20), WideShiftOperation.SRSHRL_RI, 20, -1, 14, 11);
        assertDecodes(pairImmediate(OP_SATURATING, true, 4, 7, 12), WideShiftOperation.SQSHLL_RI, 12, -1, 4, 7);
    }

    @Test
    void shimZeroMeansThirtyTwo() {
        assertDecodes(wordImmediate(OP_LEFT, 3, 0), WideShiftOperation.UQSHL_RI, 32, -1, 3, -1);
        assertDecodes(pairImmediate(OP_LEFT, false, 2, 5, 0), WideShiftOperation.LSLL_RI, 32, -1, 2, 5);
    }

    @Test
    void shimUsesBothImm3AndImm2() {
        // shim = imm3:imm2 (bits 14:12 e 7:6) — 0b10111 = 23.
        assertDecodes(wordImmediate(OP_LEFT, 1, 0b10111), WideShiftOperation.UQSHL_RI, 23, -1, 1, -1);
    }

    // ── as 8 formas por registrador ─────────────────────────────────────────────────────────────

    @Test
    void decodesTheTwoWordRegisterForms() {
        assertDecodes(wordRegister(0b0000, 3, 4), WideShiftOperation.UQRSHL_RR, 0, 4, 3, -1);
        assertDecodes(wordRegister(0b0010, 3, 4), WideShiftOperation.SQRSHR_RR, 0, 4, 3, -1);
    }

    @Test
    void decodesTheSixPairRegisterForms() {
        assertDecodes(pairRegister(0b0000, false, 2, 5, 8), WideShiftOperation.LSLL_RR, 0, 8, 2, 5);
        assertDecodes(pairRegister(0b0010, false, 2, 5, 8), WideShiftOperation.ASRL_RR, 0, 8, 2, 5);
        assertDecodes(pairRegister(0b0000, true, 2, 5, 8), WideShiftOperation.UQRSHLL64_RR, 0, 8, 2, 5);
        assertDecodes(pairRegister(0b0010, true, 2, 5, 8), WideShiftOperation.SQRSHRL64_RR, 0, 8, 2, 5);
        assertDecodes(pairRegister(0b1000, true, 2, 5, 8), WideShiftOperation.UQRSHLL48_RR, 0, 8, 2, 5);
        assertDecodes(pairRegister(0b1010, true, 2, 5, 8), WideShiftOperation.SQRSHRL48_RR, 0, 8, 2, 5);
    }

    @Test
    void decodesEveryOneOfTheNineteenOperationsExactlyOnce() {
        int[] raws = {
                wordImmediate(OP_LEFT, 3, 5), wordImmediate(OP_LOGICAL_RIGHT, 3, 5),
                wordImmediate(OP_ARITHMETIC_RIGHT, 3, 5), wordImmediate(OP_SATURATING, 3, 5),
                pairImmediate(OP_LEFT, false, 2, 5, 3), pairImmediate(OP_LOGICAL_RIGHT, false, 2, 5, 3),
                pairImmediate(OP_ARITHMETIC_RIGHT, false, 2, 5, 3), pairImmediate(OP_LOGICAL_RIGHT, true, 2, 5, 3),
                pairImmediate(OP_ARITHMETIC_RIGHT, true, 2, 5, 3), pairImmediate(OP_LEFT, true, 2, 5, 3),
                pairImmediate(OP_SATURATING, true, 2, 5, 3),
                wordRegister(0b0000, 3, 4), wordRegister(0b0010, 3, 4),
                pairRegister(0b0000, false, 2, 5, 8), pairRegister(0b0010, false, 2, 5, 8),
                pairRegister(0b0000, true, 2, 5, 8), pairRegister(0b0010, true, 2, 5, 8),
                pairRegister(0b1000, true, 2, 5, 8), pairRegister(0b1010, true, 2, 5, 8)};
        boolean[] seen = new boolean[WideShiftOperation.values().length];
        for (int raw : raws) {
            seen[decode(raw).immediate() & 0xFF] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertEquals(true, seen[i], "operação sem encoding: " + WideShiftOperation.values()[i]);
        }
        assertEquals(19, raws.length);
    }

    // ── desambiguação 32 × 64 bits ──────────────────────────────────────────────────────────────

    @Test
    void pairFormWithRdaHiFifteenIsTheWordForm() {
        // bits[11:8]=1111 casa o padrão de 32 bits PRIMEIRO (grupo `{}` do .decode): o que pareceria
        // `LSLL` com rdahi==15 é `UQSHL`, e o bit 16 (LSB de `Rda`) passa a fazer parte de `Rda`.
        assertDecodes(pairImmediate(OP_LEFT, false, 2, 15, 3), WideShiftOperation.UQSHL_RI, 3, -1, 2, -1);
        assertDecodes(pairImmediate(OP_LEFT, true, 2, 15, 3), WideShiftOperation.UQSHL_RI, 3, -1, 3, -1);
    }

    @Test
    void fortyEightBitFormsWithRdaHiFifteenFallThrough() {
        // UQRSHLL48/SQRSHRL48 não têm par de 32 bits: `rdahi == 15` => `return false` no QEMU => MOV/ORR.
        assertNull(decode(pairRegister(0b1000, true, 2, 15, 8)));
        assertNull(decode(pairRegister(0b1010, true, 2, 15, 8)));
    }

    // ── UNDEFINED explícito (unallocated_encoding do QEMU) ──────────────────────────────────────

    @Test
    void pairImmediateRefusesRdaHiThirteen() {
        assertUndefined(pairImmediate(OP_LEFT, false, 2, 13, 3));
        assertUndefined(pairImmediate(OP_SATURATING, true, 2, 13, 3));
    }

    @Test
    void pairImmediateAllowsRdaLoLinkRegister() {
        assertDecodes(pairImmediate(OP_LEFT, false, 14, 11, 3), WideShiftOperation.LSLL_RI, 3, -1, 14, 11);
    }

    @Test
    void wordImmediateRefusesRdaSpAndPc() {
        assertUndefined(wordImmediate(OP_LEFT, 13, 3));
        assertUndefined(wordImmediate(OP_LEFT, 15, 3));
    }

    @Test
    void pairRegisterRefusesEveryForbiddenRegisterCombination() {
        assertUndefined(pairRegister(0b0000, false, 2, 13, 8)); // rdahi == 13
        assertUndefined(pairRegister(0b0000, false, 2, 5, 13)); // rm == 13
        assertUndefined(pairRegister(0b0000, false, 2, 5, 15)); // rm == 15
        assertUndefined(pairRegister(0b0000, false, 2, 5, 5)); // rm == rdahi
        assertUndefined(pairRegister(0b0000, false, 2, 5, 2)); // rm == rdalo
        assertUndefined(pairRegister(0b1000, true, 2, 5, 5));
        assertUndefined(pairRegister(0b1010, true, 2, 5, 2));
    }

    @Test
    void wordRegisterRefusesEveryForbiddenRegisterCombination() {
        assertUndefined(wordRegister(0b0000, 13, 4)); // rda == 13
        assertUndefined(wordRegister(0b0000, 15, 4)); // rda == 15
        assertUndefined(wordRegister(0b0000, 3, 13)); // rm == 13
        assertUndefined(wordRegister(0b0000, 3, 15)); // rm == 15
        assertUndefined(wordRegister(0b0010, 3, 3)); // rm == rda
    }

    // ── o que NÃO é long shift continua com MOV/ORR ─────────────────────────────────────────────

    @Test
    void ordinaryShiftedRegisterEncodingsAreLeftAlone() {
        assertNull(decode(0xEA5F_0001)); // MOVS.W r0, r1
        assertNull(decode(0xEA51_0002)); // ORRS.W r0, r1, r2
        assertNull(decode(0xEA5F_000D)); // MOVS r0, sp  (Rm=13 mas Rd != 15 => bit 8 == 0)
        assertNull(decode(0xEA4F_0001)); // MOV.W r0, r1 (S=0: fora do espaço 0xEA5x)
    }

    @Test
    void undefinedCombinationsInTheOpcodeFieldsFallThrough() {
        assertNull(decode(pairImmediate(OP_SATURATING, false, 2, 5, 3)), "SQSHLL só existe com bit16=1");
        assertNull(decode(pairRegister(0b1000, false, 2, 5, 8)), "48 bits só existe com bit16=1");
        assertNull(decode(pairRegister(0b1010, false, 2, 5, 8)), "SQRSHRL48 também só existe com bit16=1");
        assertNull(decode(pairRegister(0b0100, false, 2, 5, 8)));
        assertNull(decode(wordRegister(0b1000, 3, 4)), "as formas de 48 bits não têm par de 32 bits");
        assertNull(decode(wordRegister(0b0100, 3, 4)));
        // bit 15 deve ser 0 nas formas por imediato; bit 8 deve ser 1 nas de 64 bits.
        assertNull(decode(wordImmediate(OP_LEFT, 3, 5) | (1 << 15)));
        assertNull(decode(pairImmediate(OP_LEFT, false, 2, 5, 3) & ~(1 << 8)));
        assertNull(decode(pairRegister(0b0000, false, 2, 5, 8) & ~(1 << 8)));
    }

    @Test
    void withoutMveIntegerEveryFormFallsThroughToMovOrr() {
        Thumb2MveLongShiftDecoder withoutMve = new Thumb2MveLongShiftDecoder(ArmArchitecture.ARMV8_1M);
        assertNull(withoutMve.tryDecode(wordImmediate(OP_LEFT, 3, 5), 0, Condition.AL));
        assertNull(withoutMve.tryDecode(pairRegister(0b0000, true, 2, 5, 8), 0, Condition.AL));
        Thumb2MveLongShiftDecoder armv7m = new Thumb2MveLongShiftDecoder(ArmArchitecture.ARMV7M);
        assertNull(armv7m.tryDecode(wordImmediate(OP_LEFT, 3, 5), 0, Condition.AL));
    }
}
