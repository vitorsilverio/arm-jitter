package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/// `CRC32{B,H,W}`/`CRC32C{B,H,W}` (A32+T32, ARMv8-A, B14.3) — espelho de 32 bits da B19.17
/// (`Ir64Crc32ExecutorTest`). O algoritmo em si (laço refletido bit-a-bit) é o MESMO núcleo
/// compartilhado ({@link dev.vitorsilverio.armjitter.advsimd.Crc32Checksum}) que o lado A64 já
/// validou — os testes aqui cobrem decode A32/T32, gating por {@code ArmFeature.CRC32}, o vizinho
/// de um bit (`QADD`/`QSUB`/`QDADD`/`QDSUB`) não regredir, e equivalência explícita com o
/// caminho A64 para os mesmos operandos.
class Crc32ArithmeticTest {
    private static final int COND_AL = 0xE000_0000;
    private static final byte[] CHECK_VECTOR = "123456789".getBytes(StandardCharsets.US_ASCII);
    private static final int CRC32_ISO_GOLDEN = 0xCBF4_3926;
    private static final int CRC32C_CASTAGNOLI_GOLDEN = 0xE306_9283;

    // ── Encoders A32 ─────────────────────────────────────────────────────────────────────────

    /// `sizeCode`: 0=B,1=H,2=W. `cccc 0001 0ss0 nnnn dddd 00c0 0100 mmmm`.
    private static int armCrc32(int sizeCode, boolean castagnoli, int rn, int rd, int rm) {
        return COND_AL | 0x0100_0040 | (sizeCode << 21) | (rn << 16) | (rd << 12) | (castagnoli ? 0x200 : 0) | rm;
    }

    private static int armSaturating(int op, int rn, int rd, int rm) {
        return COND_AL | 0x0100_0050 | (op << 21) | (rn << 16) | (rd << 12) | rm;
    }

    // ── Encoders T32 (mesmo layout de Thumb2RegisterDataProcessingDecoderTest) ─────────────────

    private static int twoSourceHi(int family, int rn) {
        return 0xFA00 | (family << 4) | rn;
    }

    private static int twoSourceLo(int rd, int op, int rm) {
        return 0xF000 | (rd << 8) | (op << 4) | rm;
    }

    // ── Runners ──────────────────────────────────────────────────────────────────────────────

    private static ArmCore newArmCore() {
        return new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV8A_32);
    }

    private static ArmCore newThumb2Core() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV8A_32);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void runArm(ArmCore core, int word) {
        core.memory().write32(core.programCounter(), word);
        core.step();
    }

    private static void runThumb2(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    // ── Vetores clássicos ("123456789"), A32: CRC32B encadeado byte a byte ─────────────────────

    private static int crc32OfCheckVectorArm(boolean castagnoli) {
        ArmCore core = newArmCore();
        core.setRegister(0, 0xFFFF_FFFF); // acumulador (rn) = ~0, init clássico
        int familyOp = castagnoli ? 0b10 : 0b00; // sizeCode=0 (B) fixo, só o polinômio varia
        for (byte b : CHECK_VECTOR) {
            core.setRegister(1, b & 0xFF); // dado (rm)
            core.memory().write32(core.programCounter(), armCrc32(0, castagnoli, 0, 0, 1));
            core.step();
        }
        return ~core.register(0);
    }

    @Test
    void armIso8023CheckVectorMatchesKnownGolden() {
        assertEquals(CRC32_ISO_GOLDEN, crc32OfCheckVectorArm(false));
    }

    @Test
    void armCastagnoliCheckVectorMatchesKnownGolden() {
        assertEquals(CRC32C_CASTAGNOLI_GOLDEN, crc32OfCheckVectorArm(true));
    }

    @Test
    void armCrc32AndCrc32cDifferForSameInput() {
        assertNotEquals(crc32OfCheckVectorArm(false), crc32OfCheckVectorArm(true));
    }

    // ── T32: mesmo vetor, mesmo encadeamento, mesma sequência de golden ────────────────────────

    private static int crc32OfCheckVectorThumb(boolean castagnoli) {
        ArmCore core = newThumb2Core();
        core.setRegister(0, 0xFFFF_FFFF);
        int family = castagnoli ? 0xD : 0xC;
        int widthOp = 0x8; // B
        for (byte b : CHECK_VECTOR) {
            core.setRegister(1, b & 0xFF);
            runThumb2(core, twoSourceHi(family, 0), twoSourceLo(0, widthOp, 1));
        }
        return ~core.register(0);
    }

    @Test
    void thumb2Iso8023CheckVectorMatchesKnownGolden() {
        assertEquals(CRC32_ISO_GOLDEN, crc32OfCheckVectorThumb(false));
    }

    @Test
    void thumb2CastagnoliCheckVectorMatchesKnownGolden() {
        assertEquals(CRC32C_CASTAGNOLI_GOLDEN, crc32OfCheckVectorThumb(true));
    }

    // ── T32 ida-e-volta contra A32, larguras B/H/W ──────────────────────────────────────────────

    @Test
    void thumb2MatchesArmClassicForAllWidthsAndPolynomials() {
        int[] sizeCodes = {0, 1, 2};
        int[] widthOps = {0x8, 0x9, 0xA};
        for (int i = 0; i < sizeCodes.length; i++) {
            for (boolean castagnoli : new boolean[] {false, true}) {
                ArmCore armCore = newArmCore();
                armCore.setRegister(0, 0x1234_5678);
                armCore.setRegister(1, 0xCAFE_BABE);
                runArm(armCore, armCrc32(sizeCodes[i], castagnoli, 0, 2, 1));

                ArmCore thumb2Core = newThumb2Core();
                thumb2Core.setRegister(0, 0x1234_5678);
                thumb2Core.setRegister(1, 0xCAFE_BABE);
                int family = castagnoli ? 0xD : 0xC;
                runThumb2(thumb2Core, twoSourceHi(family, 0), twoSourceLo(2, widthOps[i], 1));

                assertEquals(armCore.register(2), thumb2Core.register(2),
                        "sizeCode=" + sizeCodes[i] + " castagnoli=" + castagnoli);
            }
        }
    }

    // ── Equivalência explícita com o caminho A64 (mesma semântica, núcleo compartilhado) ───────

    @Test
    void matchesAarch64PathForRandomOperands() {
        Random random = new Random(0xC5C32);
        int[] dataWidths = {8, 16, 32};
        for (int trial = 0; trial < 200; trial++) {
            int acc = random.nextInt();
            int data = random.nextInt();
            int dataWidthBits = dataWidths[trial % dataWidths.length];
            boolean castagnoli = trial % 2 == 0;

            IrBlockExecutor executor32 = new IrBlockExecutor(ArmArchitecture.ARMV8A_32);
            ArmCore core32 = newArmCore();
            core32.setRegister(0, acc);
            core32.setRegister(1, data);
            executor32.executeOp(core32, new IrOp.Crc32(2, 0, 1, dataWidthBits, castagnoli, Condition.AL), 0);

            Ir64BlockExecutor executor64 = new Ir64BlockExecutor();
            Aarch64Core core64 = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)));
            core64.setX(0, Integer.toUnsignedLong(acc));
            core64.setX(1, Integer.toUnsignedLong(data));
            executor64.executeOp(core64, new Ir64Op.Crc32(2, 0, 1, dataWidthBits, castagnoli));

            assertEquals((int) core64.x(2), core32.register(2),
                    "trial=" + trial + " width=" + dataWidthBits + " castagnoli=" + castagnoli);
        }
    }

    // ── Gating por ArmFeature.CRC32 (G8) ────────────────────────────────────────────────────────

    @Test
    void armCrc32DecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, armCrc32(0, false, 0, 1, 2));
        assertNotEquals(InstructionKind.CRC32,
                new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.CRC32,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void thumb2Crc32DecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put16(0, twoSourceHi(0xC, 0));
        memory.put16(2, twoSourceLo(1, 0x8, 2));
        assertNotEquals(InstructionKind.CRC32,
                new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.CRC32,
                new ThumbDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void armSizeCode3IsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, armCrc32(0b11, false, 0, 1, 2));
        DecodedInstruction instruction = new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── O vizinho de um bit (QADD/QSUB/QDADD/QDSUB) não regride ─────────────────────────────────

    @Test
    void saturatingArithmeticStillDecodesUnderArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, armSaturating(0, 1, 0, 2)); // QADD r0,r2,r1
        assertEquals(InstructionKind.SATURATING,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void saturatingArithmeticStillExecutesUnderArmv8a32() {
        ArmCore core = newArmCore();
        core.setRegister(1, 10);
        core.setRegister(2, 20);
        runArm(core, armSaturating(0, 1, 0, 2)); // QADD r0,r2,r1 -> r0 = r2+r1
        assertEquals(30, core.register(0));
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void thumb2SaturatingStillDecodesUnderArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put16(0, twoSourceHi(0x8, 2));
        memory.put16(2, twoSourceLo(0, 0x8, 1)); // QADD r0,r1,r2
        assertEquals(InstructionKind.SATURATING,
                new ThumbDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    // ── Não complementa entrada/saída (a instrução em si, ao contrário da convenção "clássica") ─

    @Test
    void instructionItselfDoesNotComplementInputOrOutput() {
        ArmCore core = newArmCore();
        core.setRegister(1, 0); // acumulador = 0 (não ~0)
        core.setRegister(2, 0); // dado = 0
        runArm(core, armCrc32(2, false, 1, 0, 2)); // CRC32W r0,r1,r2
        assertTrue(core.register(0) == 0, "CRC(0,0) sem complementação é 0, não ~0");
    }
}
