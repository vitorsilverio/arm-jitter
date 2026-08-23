package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

/// B2.3 — load/store Thumb-2 de 32 bits: `LDR`/`STR`/`LDRB`/`STRB`/`LDRH`/`STRH`/`LDRSB`/`LDRSH`
/// nas formas T2 (registrador com shift)/T3 (imediato de 12 bits)/T4 (imediato de 8 bits, P/U/W) e
/// literal, `LDRD`/`STRD` (par arbitrário) e `LDM.W`/`STM.W`/`PUSH.W`/`POP.W`. Ver
/// `Thumb2LoadStoreDecoder` para o layout de bits (confirmado contra o QEMU
/// `target/arm/tcg/t32.decode`).
class Thumb2LoadStoreDecoderTest extends BlockEquivalenceTest {
    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.extending(
                    ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-LoadStore", ArmFeature.THUMB2)
            .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                    ArmArchitecture.extending(ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-LoadStore-Inner",
                            ArmFeature.THUMB2))));

    // ── Encoders (ver Thumb2LoadStoreDecoder para o layout de bits) ────────────────────────

    private static final int UNSIGNED_TOP8 = 0b1111_1000;
    private static final int SIGNED_TOP8 = 0b1111_1001;
    private static final int SIZE_L_STRB = 0b000;
    private static final int SIZE_L_LDRB = 0b001;
    private static final int SIZE_L_STRH = 0b010;
    private static final int SIZE_L_LDRH = 0b011;
    private static final int SIZE_L_STR = 0b100;
    private static final int SIZE_L_LDR = 0b101;
    private static final int SIZE_L_LDRSB = 0b001;
    private static final int SIZE_L_LDRSH = 0b011;

    private static int t3(int topByte, int sizeL, int rn, int rt, int imm12) {
        return (topByte << 24) | (1 << 23) | (sizeL << 20) | (rn << 16) | (rt << 12) | (imm12 & 0xFFF);
    }

    private static int t4(int topByte, int sizeL, int rn, int rt, boolean p, boolean u, boolean w, int imm8) {
        return (topByte << 24) | (sizeL << 20) | (rn << 16) | (rt << 12) | (1 << 11)
                | ((p ? 1 : 0) << 10) | ((u ? 1 : 0) << 9) | ((w ? 1 : 0) << 8) | (imm8 & 0xFF);
    }

    private static int t2Register(int topByte, int sizeL, int rn, int rt, int imm2, int rm) {
        return (topByte << 24) | (sizeL << 20) | (rn << 16) | (rt << 12) | ((imm2 & 0x3) << 4) | (rm & 0xF);
    }

    private static int literal(int topByte, int sizeL, boolean up, int rt, int imm12) {
        return (topByte << 24) | ((up ? 1 : 0) << 23) | (sizeL << 20) | (0xF << 16) | (rt << 12) | (imm12 & 0xFFF);
    }

    private static int ldrd(boolean p, boolean u, boolean w, boolean load, int rn, int rt, int rt2, int imm8) {
        return (0b1110100 << 25) | ((p ? 1 : 0) << 24) | ((u ? 1 : 0) << 23) | (1 << 22) | ((w ? 1 : 0) << 21)
                | ((load ? 1 : 0) << 20) | (rn << 16) | (rt << 12) | (rt2 << 8) | (imm8 & 0xFF);
    }

    private static int ldmStm(boolean db, boolean writeback, boolean load, int rn, int mask) {
        boolean p = db;
        boolean upOrFixed = !db;
        return (0b1110100 << 25) | ((p ? 1 : 0) << 24) | ((upOrFixed ? 1 : 0) << 23)
                | ((writeback ? 1 : 0) << 21) | ((load ? 1 : 0) << 20) | (rn << 16) | (mask & 0xFFFF);
    }

    // ── LDREX/STREX de 32 bits (B2.7 PR3) — raw[22]=1,P=0,W=0 dentro de EXTRA_TOP7 ──────────

    /// `LDREX rt,[rn,#imm8*4]` — word, com o offset que o ARM clássico NÃO tem.
    private static int ldrexWordT(int rn, int rt, int imm8) {
        return (0b1110100 << 25) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (imm8 & 0xFF);
    }

    /// `STREX rd,rt,[rn,#imm8*4]` — word, com o offset que o ARM clássico NÃO tem.
    private static int strexWordT(int rn, int rt, int rd, int imm8) {
        return (0b1110100 << 25) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12)
                | (rd << 8) | (imm8 & 0xFF);
    }

    private static final int EXCLUSIVE_OP_BYTE = 0b0100;
    private static final int EXCLUSIVE_OP_HALF = 0b0101;
    private static final int EXCLUSIVE_OP_DOUBLE = 0b0111;

    /// `LDREXB/H rt,[rn]` (op4 ∈ {byte,half}).
    private static int ldrexSizedT(int op4, int rn, int rt) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (op4 << 4) | 0xF;
    }

    /// `STREXB/H rd,rt,[rn]` (op4 ∈ {byte,half}).
    private static int strexSizedT(int op4, int rn, int rt, int rd) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (op4 << 4) | rd;
    }

    /// `LDREXD rt,rt2,[rn]` (`Rt2` campo independente, mas `Rt2==Rt+1` continua obrigatório).
    private static int ldrexdT(int rn, int rt, int rt2) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12)
                | (rt2 << 8) | (EXCLUSIVE_OP_DOUBLE << 4) | 0xF;
    }

    /// `STREXD rd,rt,rt2,[rn]`.
    private static int strexdT(int rn, int rt, int rt2, int rd) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12)
                | (rt2 << 8) | (EXCLUSIVE_OP_DOUBLE << 4) | rd;
    }

    // ── Encoders ARM clássico (mesmos layouts de ArmV6ExclusiveAccessTest) ─────────────────

    /// `LDREX{,B,H,D} rd, [rn]` (cond AL). `sz`: 00=word, 01=doubleword, 10=byte, 11=halfword.
    private static int armLdrex(int sz, int rd, int rn) {
        return 0xE190_0F9F | (sz << 21) | (rn << 16) | (rd << 12);
    }

    /// `STREX{,B,H,D} rd, rm, [rn]` (cond AL).
    private static int armStrex(int sz, int rd, int rn, int rm) {
        return 0xE180_0F90 | (sz << 21) | (rn << 16) | (rd << 12) | rm;
    }

    private static ArmCore newArmCore() {
        return new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    private static void runArm(ArmCore core, int word) {
        core.memory().write32(core.programCounter(), word);
        core.step();
    }

    @Test
    void ldrexWordThumb2WithZeroOffsetMatchesArmClassic() {
        ArmCore thumb2Core = newCore();
        thumb2Core.setRegister(1, 0x10);
        thumb2Core.memory().write32(0x10, 0x12345678);
        int raw = ldrexWordT(1, 0, 0); // LDREX r0,[r1]
        run(thumb2Core, hi(raw), lo(raw));

        ArmCore armCore = newArmCore();
        armCore.setRegister(1, 0x10);
        armCore.memory().write32(0x10, 0x12345678);
        runArm(armCore, armLdrex(0, 0, 1)); // LDREX r0,[r1]

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0x12345678, thumb2Core.register(0));
    }

    @Test
    void strexWordThumb2WithZeroOffsetMatchesArmClassicSuccessAndFailure() {
        // Sucesso: LDREX antes.
        ArmCore thumb2Success = newCore();
        thumb2Success.setRegister(1, 0x10);
        thumb2Success.setRegister(0, 0xCAFEBABE);
        thumb2Success.memory().write32(0x10, 0x11111111);
        run(thumb2Success, hi(ldrexWordT(1, 2, 0)), lo(ldrexWordT(1, 2, 0)));
        int strex = strexWordT(1, 0, 3, 0); // STREX r3,r0,[r1]
        run(thumb2Success, hi(strex), lo(strex));

        ArmCore armSuccess = newArmCore();
        armSuccess.setRegister(1, 0x10);
        armSuccess.setRegister(0, 0xCAFEBABE);
        armSuccess.memory().write32(0x10, 0x11111111);
        runArm(armSuccess, armLdrex(0, 2, 1));
        runArm(armSuccess, armStrex(0, 3, 1, 0));

        assertEquals(armSuccess.register(3), thumb2Success.register(3), "status de sucesso deve bater");
        assertEquals(0, thumb2Success.register(3));
        assertEquals(0xCAFEBABE, thumb2Success.memory().read32(0x10));

        // Falha: sem LDREX antes.
        ArmCore thumb2Fail = newCore();
        thumb2Fail.setRegister(1, 0x10);
        thumb2Fail.setRegister(0, 0xDEADBEEF);
        thumb2Fail.memory().write32(0x10, 0x22222222);
        run(thumb2Fail, hi(strex), lo(strex));

        assertEquals(1, thumb2Fail.register(3), "sem LDREX efetivo, STREX deve falhar");
        assertEquals(0x22222222, thumb2Fail.memory().read32(0x10), "memória intacta na falha");
    }

    @Test
    void strexWordThumb2WithNonZeroOffsetIsTheFormThatDoesNotExistInArmClassic() {
        // A armadilha da task: STREX.W tem offset imm8×4 (ARM clássico não tem — sempre [Rn]).
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.setRegister(0, 0xAABBCCDD);
        int ldrexAtOffset = ldrexWordT(1, 2, 4); // LDREX r2,[r1,#16]
        run(core, hi(ldrexAtOffset), lo(ldrexAtOffset));
        int strex = strexWordT(1, 0, 3, 4); // STREX r3,r0,[r1,#16]
        run(core, hi(strex), lo(strex));

        assertEquals(0, core.register(3), "sucesso: LDREX e STREX miraram o MESMO endereço via offset");
        assertEquals(0xAABBCCDD, core.memory().read32(0x110), "escrita foi em base+offset, não em base");
        assertEquals(0, core.memory().read32(0x100), "base sozinha (sem offset) nunca foi tocada");
    }

    @Test
    void ldrexbHalfDoubleThumb2MatchesArmClassic() {
        // LDREXB
        ArmCore thumb2B = newCore();
        thumb2B.setRegister(1, 0x10);
        thumb2B.memory().write8(0x10, 0x7F);
        int rawB = ldrexSizedT(EXCLUSIVE_OP_BYTE, 1, 0);
        run(thumb2B, hi(rawB), lo(rawB));
        ArmCore armB = newArmCore();
        armB.setRegister(1, 0x10);
        armB.memory().write8(0x10, 0x7F);
        runArm(armB, armLdrex(0b10, 0, 1));
        assertEquals(armB.register(0), thumb2B.register(0));

        // LDREXH
        ArmCore thumb2H = newCore();
        thumb2H.setRegister(1, 0x10);
        thumb2H.memory().write16(0x10, 0xBEEF);
        int rawH = ldrexSizedT(EXCLUSIVE_OP_HALF, 1, 0);
        run(thumb2H, hi(rawH), lo(rawH));
        ArmCore armH = newArmCore();
        armH.setRegister(1, 0x10);
        armH.memory().write16(0x10, 0xBEEF);
        runArm(armH, armLdrex(0b11, 0, 1));
        assertEquals(armH.register(0), thumb2H.register(0));

        // LDREXD (Rt2 independente no encoding, mas Rt2==Rt+1 continua exigido)
        ArmCore thumb2D = newCore();
        thumb2D.setRegister(1, 0x10);
        thumb2D.memory().write32(0x10, 0x11111111);
        thumb2D.memory().write32(0x14, 0x22222222);
        int rawD = ldrexdT(1, 2, 3);
        run(thumb2D, hi(rawD), lo(rawD));
        ArmCore armD = newArmCore();
        armD.setRegister(1, 0x10);
        armD.memory().write32(0x10, 0x11111111);
        armD.memory().write32(0x14, 0x22222222);
        runArm(armD, armLdrex(0b01, 2, 1));
        assertEquals(armD.register(2), thumb2D.register(2));
        assertEquals(armD.register(3), thumb2D.register(3));
    }

    @Test
    void strexbHalfDoubleThumb2MatchesArmClassic() {
        // STREXB
        ArmCore thumb2B = newCore();
        thumb2B.setRegister(1, 0x10);
        thumb2B.setRegister(0, 0xFF);
        thumb2B.memory().write8(0x10, 0);
        run(thumb2B, hi(ldrexSizedT(EXCLUSIVE_OP_BYTE, 1, 2)), lo(ldrexSizedT(EXCLUSIVE_OP_BYTE, 1, 2)));
        int strexB = strexSizedT(EXCLUSIVE_OP_BYTE, 1, 0, 3);
        run(thumb2B, hi(strexB), lo(strexB));

        ArmCore armB = newArmCore();
        armB.setRegister(1, 0x10);
        armB.setRegister(0, 0xFF);
        armB.memory().write8(0x10, 0);
        runArm(armB, armLdrex(0b10, 2, 1));
        runArm(armB, armStrex(0b10, 3, 1, 0));

        assertEquals(armB.register(3), thumb2B.register(3));
        assertEquals(0, thumb2B.register(3));
        assertEquals(0xFF, thumb2B.memory().read8(0x10) & 0xFF);

        // STREXD
        ArmCore thumb2D = newCore();
        thumb2D.setRegister(1, 0x10);
        thumb2D.setRegister(4, 0xAAAAAAAA);
        thumb2D.setRegister(5, 0xBBBBBBBB);
        run(thumb2D, hi(ldrexdT(1, 2, 3)), lo(ldrexdT(1, 2, 3)));
        int strexD = strexdT(1, 4, 5, 6);
        run(thumb2D, hi(strexD), lo(strexD));

        ArmCore armD = newArmCore();
        armD.setRegister(1, 0x10);
        armD.setRegister(4, 0xAAAAAAAA);
        armD.setRegister(5, 0xBBBBBBBB);
        runArm(armD, armLdrex(0b01, 2, 1));
        runArm(armD, armStrex(0b01, 6, 1, 4));

        assertEquals(armD.register(6), thumb2D.register(6));
        assertEquals(0, thumb2D.register(6));
        assertEquals(0xAAAAAAAA, thumb2D.memory().read32(0x10));
        assertEquals(0xBBBBBBBB, thumb2D.memory().read32(0x14));
    }

    @Test
    void exclusiveGroupsAreUndefinedWithoutTheirGates() {
        ArmArchitecture noExclusive = ArmArchitecture.extending(
                        ArmArchitecture.ARMV4T, "NoExclusive", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV4T, "NoExclusive-Inner", ArmFeature.THUMB2))));
        int[] encodings = {
                ldrexWordT(1, 0, 0), strexWordT(1, 0, 3, 0),
                ldrexSizedT(EXCLUSIVE_OP_BYTE, 1, 0), strexSizedT(EXCLUSIVE_OP_HALF, 1, 0, 3),
                ldrexdT(1, 2, 3), strexdT(1, 2, 3, 6),
        };
        for (int raw : encodings) {
            TestAddressSpace memory = new TestAddressSpace(16);
            memory.put16(0, hi(raw));
            memory.put16(2, lo(raw));
            assertEquals(InstructionKind.UNIMPLEMENTED, new ThumbDecoder(noExclusive).decode(memory, 0).kind(),
                    () -> "sem EXCLUSIVE_WORD/EXCLUSIVE_SIZED deve ser UNDEFINED: 0x" + Integer.toHexString(raw));
        }
    }

    @Test
    void wordExclusiveDecodesWithOnlyExclusiveWordFeature() {
        // Preset só com EXCLUSIVE_WORD (sem EXCLUSIVE_SIZED) — mesma cobertura de
        // ArmV6ExclusiveAccessTest#wordFormsDecodeOnArmv6kButSizedVariantsRequireExclusiveSized,
        // do lado Thumb-2.
        ArmArchitecture wordOnly = ArmArchitecture.extending(
                        ArmArchitecture.ARMV5TE, "WordOnlyThumb2", ArmFeature.THUMB2, ArmFeature.EXCLUSIVE_WORD)
                .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "WordOnlyThumb2-Inner",
                                ArmFeature.THUMB2, ArmFeature.EXCLUSIVE_WORD))));
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = ldrexWordT(1, 0, 0);
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        assertEquals(InstructionKind.LOAD_EXCLUSIVE, new ThumbDecoder(wordOnly).decode(memory, 0).kind());

        TestAddressSpace memoryB = new TestAddressSpace(16);
        int rawB = ldrexSizedT(EXCLUSIVE_OP_BYTE, 1, 0);
        memoryB.put16(0, hi(rawB));
        memoryB.put16(2, lo(rawB));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ThumbDecoder(wordOnly).decode(memoryB, 0).kind());
    }

    @Test
    void tbbTbhStillDecodedByBranchDecoderNotSwallowedByExclusiveSpace() {
        // op4∈{0,1} no mesmo prefixo de 7 bits — TBB/TBH continuam responsabilidade de
        // Thumb2BranchDecoder (B2.4); esta extensão sozinha (sem Thumb2BranchDecoder plugado) NÃO
        // deve reivindicá-los.
        TestAddressSpace memory = new TestAddressSpace(16);
        // TBB [r1, r2]: raw[22]=1,P=0,W=0,U/fixed=1,L=1,op4=0.
        int raw = (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b101 << 20) | (1 << 16) | (0xF << 12) | 2;
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.LOAD_EXCLUSIVE, instruction.kind());
    }

    private static ArmCore newCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void run(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    private static int hi(int raw) {
        return raw >>> 16;
    }

    private static int lo(int raw) {
        return raw & 0xFFFF;
    }

    // ── T3: imediato de 12 bits, sempre para cima ───────────────────────────────────────────

    @Test
    void t3LdrWordUsesTwelveBitPositiveOffset() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.memory().write32(0x100 + 0x344, 0xCAFEBABE);
        int raw = t3(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, 0x344);
        run(core, hi(raw), lo(raw));
        assertEquals(0xCAFEBABE, core.register(0));
    }

    @Test
    void t3StrByteStoresLowByteAtOffset() {
        ArmCore core = newCore();
        core.setRegister(1, 0x200);
        core.setRegister(0, 0xAABBCCDD);
        int raw = t3(UNSIGNED_TOP8, SIZE_L_STRB, 1, 0, 0x10);
        run(core, hi(raw), lo(raw));
        assertEquals(0xDD, core.memory().read8(0x210) & 0xFF);
    }

    // ── T4: imediato de 8 bits, 4 modos pré/pós-indexados + offset negativo sem writeback ──

    @Test
    void t4PreIndexedWithWritebackUpdatesBaseAndReadsAtNewAddress() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.memory().write32(0x108, 0x12345678);
        int raw = t4(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, true, true, true, 8); // LDR r0,[r1,#8]!
        run(core, hi(raw), lo(raw));
        assertEquals(0x12345678, core.register(0));
        assertEquals(0x108, core.register(1));
    }

    @Test
    void t4PostIndexedWritesBackAfterReadingAtOriginalAddress() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.memory().write32(0x100, 0xDEADBEEF);
        int raw = t4(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, false, true, true, 8); // LDR r0,[r1],#8
        run(core, hi(raw), lo(raw));
        assertEquals(0xDEADBEEF, core.register(0));
        assertEquals(0x108, core.register(1));
    }

    @Test
    void t4NegativeOffsetWithoutWritebackReadsBelowBaseAndKeepsBase() {
        ArmCore core = newCore();
        core.setRegister(1, 0x200);
        core.memory().write32(0x1F8, 0x99887766);
        int raw = t4(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, true, false, false, 8); // LDR r0,[r1,#-8]
        run(core, hi(raw), lo(raw));
        assertEquals(0x99887766, core.register(0));
        assertEquals(0x200, core.register(1));
    }

    @Test
    void t4UnprivilegedFormIsNotClaimedAsLoad() {
        // P=1,U=1,W=0 (STRT/LDRT) — fora do escopo desta task; o decoder devolve `null` (não
        // reivindica esse sub-encoding). B2.2.2: como `top8` continua batendo com
        // `SINGLE_UNSIGNED_TOP8` (`claimsEncodingSpace`), o `ThumbDecoder` agora reconhece que é
        // estruturalmente o espaço desta classe e vira UNDEFINED explícito em vez de cair no
        // caminho legado BL/BLX — o importante continua sendo que NUNCA vira `LOAD`/`STORE`
        // (nenhuma semântica de load/store incorreta é executada).
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = t4(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, true, true, false, 4);
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.LOAD, instruction.kind());
        assertNotEquals(InstructionKind.STORE, instruction.kind());
    }

    // ── PLD/PLDW/PLI (B2.8) — Rt=1111 no espaço de load vira hint, nunca um load real para o PC ──

    @Test
    void pldT3ImmediateHasNoObservableEffectAndDoesNotAccessTheAddress() {
        ArmCore core = newCore();
        core.setRegister(0, 0x1234_5678);
        core.setRegister(1, 0x2000); // além dos 4096 bytes mapeados por newCore() -> endereço não mapeado
        int cpsrBefore = core.cpsr().get();
        int raw = t3(UNSIGNED_TOP8, SIZE_L_LDRB, 1, 15, 0x10); // PLD [r1,#0x10] -> 0x2010, não mapeado
        run(core, hi(raw), lo(raw)); // não deve lançar exceção (endereço nunca é acessado)
        assertEquals(0x1234_5678, core.register(0));
        assertEquals(0x2000, core.register(1));
        assertEquals(cpsrBefore, core.cpsr().get());
    }

    @Test
    void pldT4RegisterAndPliVariantsHaveNoObservableEffect() {
        int[] rawEncodings = {
                t3(UNSIGNED_TOP8, SIZE_L_LDRH, 1, 15, 0x10), // PLDW [r1,#0x10] (LDRH-shaped -> PLDW)
                t4(UNSIGNED_TOP8, SIZE_L_LDRB, 1, 15, true, false, false, 8), // PLD [r1,#-8] (T4)
                t2Register(UNSIGNED_TOP8, SIZE_L_LDRB, 1, 15, 0, 2), // PLD [r1,r2] (T2 registrador)
                literal(UNSIGNED_TOP8, SIZE_L_LDRB, true, 15, 0x10), // PLD literal (Rn=PC, Rt=PC)
                t3(SIGNED_TOP8, SIZE_L_LDRSB, 1, 15, 0x10), // PLI [r1,#0x10]
        };
        for (int raw : rawEncodings) {
            ArmCore core = newCore();
            core.setRegister(0, 0xCAFEBABE);
            core.setRegister(1, 0x100);
            core.setRegister(2, 4);
            int cpsrBefore = core.cpsr().get();
            run(core, hi(raw), lo(raw));
            assertEquals(0xCAFEBABE, core.register(0), () -> "0x" + Integer.toHexString(raw) + " não deve tocar r0");
            assertEquals(0x100, core.register(1), () -> "0x" + Integer.toHexString(raw) + " não deve tocar a base");
            assertEquals(cpsrBefore, core.cpsr().get(), () -> "0x" + Integer.toHexString(raw) + " não deve tocar CPSR");
        }
    }

    @Test
    void ordinaryLdrWithPcDestinationIsUnaffectedByThePreloadCarveOut() {
        // LDR Rt,PC (sizeL=SIZE_L_LDR=101) continua um load real para o PC (interworking) — a
        // armadilha da task: o carve-out só vale para sizeL LDRB/LDRH (unsigned) e LDRSB (signed).
        ArmCore core = newCore(); // ARMV6K -> LOAD_PC_INTERWORKING presente
        core.memory().write32(0x110, 0x201); // bit0=1 -> permanece THUMB, alvo 0x200
        int raw = t3(UNSIGNED_TOP8, SIZE_L_LDR, 1, 15, 0x10);
        core.setRegister(1, 0x100);
        run(core, hi(raw), lo(raw));
        assertEquals(0x200, core.programCounter(), "LDR Rt=PC deve continuar sendo um load real, não um hint");
        assertTrue(core.cpsr().isThumbMode());
    }

    @Test
    void preloadHintsAreUndefinedWithoutTheGate() {
        ArmArchitecture noHints = ArmArchitecture.extending(
                        ArmArchitecture.ARMV4T, "NoPreloadHints", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV4T, "NoPreloadHints-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = t3(UNSIGNED_TOP8, SIZE_L_LDRB, 1, 15, 0x10); // PLD
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ThumbDecoder(noHints).decode(memory, 0).kind());
    }

    @Test
    void preloadHintBlockMatchesInterpretedReferenceThroughAsmEmitter() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int pld = t3(UNSIGNED_TOP8, SIZE_L_LDRB, 1, 15, 0x10); // PLD [r1,#0x10]
        int ldr = t3(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, 0x20);   // LDR r0,[r1,#0x20]
        memory.put16(0, hi(pld));
        memory.put16(2, lo(pld));
        memory.put16(4, hi(ldr));
        memory.put16(6, lo(ldr));
        memory.write32(0x20, 0x1122_3344);

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 2);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter thumb2Reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        harness.assertEquivalent(thumb2Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(1, 0x0)));
    }

    // ── T2: [Rn, Rm, LSL #imm2] — comparado byte-a-byte com o ARM clássico ─────────────────

    @Test
    void t2RegisterFormWithShiftMatchesArmClassicEquivalent() {
        ArmCore thumb2Core = newCore();
        thumb2Core.setRegister(1, 0x100);
        thumb2Core.setRegister(2, 1);
        thumb2Core.memory().write32(0x100 + 4, 0x11223344); // Rm=1 shiftado LSL#2 = offset 4
        int raw = t2Register(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, 2, 2); // LDR r0,[r1,r2,LSL#2]
        run(thumb2Core, hi(raw), lo(raw));

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(1, 0x100);
        armCore.setRegister(2, 1);
        armCore.memory().write32(0x104, 0x11223344);
        armCore.memory().write32(0, 0xE791_0102); // LDR r0,[r1,r2,LSL#2]
        armCore.step();

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0x11223344, thumb2Core.register(0));
    }

    // ── Formas signed: LDRSB/LDRSH estendem sinal ───────────────────────────────────────────

    @Test
    void ldrsbSignExtendsNegativeByte() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.memory().write8(0x104, 0x80);
        int raw = t3(SIGNED_TOP8, SIZE_L_LDRSB, 1, 0, 4);
        run(core, hi(raw), lo(raw));
        assertEquals(0xFFFFFF80, core.register(0));
    }

    @Test
    void ldrshSignExtendsNegativeHalfword() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.memory().write16(0x104, 0x8000);
        int raw = t3(SIGNED_TOP8, SIZE_L_LDRSH, 1, 0, 4);
        run(core, hi(raw), lo(raw));
        assertEquals(0xFFFF8000, core.register(0));
    }

    // ── Literal: LDR Rt,[PC,#imm] com PC alinhado a 4 bytes ────────────────────────────────

    @Test
    void literalLoadUsesFourByteAlignedPcEvenWhenMisaligned() {
        ArmCore core = newCore();
        // Coloca a instrução num endereço com bit 1 setado (PC final, após +4, também desalinhado).
        int address = 0x102;
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int raw = literal(UNSIGNED_TOP8, SIZE_L_LDR, true, 0, 0x10);
        memory.put16(address, hi(raw));
        memory.put16(address + 2, lo(raw));
        int literalWordAddress = ((address + 4) & ~3) + 0x10;
        memory.write32(literalWordAddress, 0x600DF00D);
        core.setProgramCounter(address);
        core.step();
        assertEquals(0x600DF00D, core.register(0));
    }

    @Test
    void literalLoadSupportsNegativeOffsetViaExplicitUBit() {
        ArmCore core = newCore();
        int address = 0x200;
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int raw = literal(UNSIGNED_TOP8, SIZE_L_LDR, false, 0, 0x10);
        memory.put16(address, hi(raw));
        memory.put16(address + 2, lo(raw));
        int literalWordAddress = ((address + 4) & ~3) - 0x10;
        memory.write32(literalWordAddress, 0x11111111);
        core.setProgramCounter(address);
        core.step();
        assertEquals(0x11111111, core.register(0));
    }

    // ── LDRD/STRD: par arbitrário Rt/Rt2 (não-adjacente, diferente do ARM clássico) ─────────

    @Test
    void ldrdSupportsNonAdjacentRegisterPair() {
        // LDRD r2, r7, [r1, #8] — Rt=2, Rt2=7, longe de ser Rt+1 (diferença do ARM clássico).
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.memory().write32(0x108, 0xAAAA_AAAA);
        core.memory().write32(0x10C, 0xBBBB_BBBB);
        int raw = ldrd(true, true, false, true, 1, 2, 7, 2); // imm8=2 -> ×4 = 8
        run(core, hi(raw), lo(raw));
        assertEquals(0xAAAA_AAAA, core.register(2));
        assertEquals(0xBBBB_BBBB, core.register(7));
        assertEquals(0x100, core.register(1)); // sem writeback (P=1,W=0)
    }

    @Test
    void strdWritesNonAdjacentPairAndWritesBackPreIndexed() {
        // STRD r5, r0, [r1, #4]! — Rt=5, Rt2=0, P=1,W=1 (pré-indexado com writeback).
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.setRegister(5, 0x1234);
        core.setRegister(0, 0x5678);
        int raw = ldrd(true, true, true, false, 1, 5, 0, 1); // imm8=1 -> ×4 = 4
        run(core, hi(raw), lo(raw));
        assertEquals(0x1234, core.memory().read32(0x104));
        assertEquals(0x5678, core.memory().read32(0x108));
        assertEquals(0x104, core.register(1));
    }

    @Test
    void ldrdIsUndefinedWithoutLdrdStrdFeature() {
        ArmArchitecture withoutLdrd = ArmArchitecture.extending(
                        ArmArchitecture.ARMV4T, "NoLdrd", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV4T, "NoLdrd-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = ldrd(true, true, false, true, 1, 2, 7, 2);
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(withoutLdrd).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── LDM.W/STM.W/PUSH.W/POP.W: lista completa de 16 registradores ───────────────────────

    @Test
    void ldmWideLoadsFullSixteenRegisterList() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        int mask = 0xFFFF & ~(1 << 13) & ~(1 << 15) & ~(1 << 1); // sem SP, PC nem a própria base
        int address = 0x100;
        for (int register = 0; register <= 15; register++) {
            if ((mask & (1 << register)) != 0) {
                core.memory().write32(address, 0x1000 + register);
                address += 4;
            }
        }
        int raw = ldmStm(false, true, true, 1, mask); // LDM.W r1!, {lista}
        run(core, hi(raw), lo(raw));
        for (int register = 0; register <= 15; register++) {
            if ((mask & (1 << register)) != 0) {
                assertEquals(0x1000 + register, core.register(register), "r" + register);
            }
        }
        assertEquals(address, core.register(1));
    }

    @Test
    void pushWideAliasIsStmdbOnStackPointer() {
        ArmCore core = newCore();
        core.setRegister(13, 0x200);
        core.setRegister(0, 0xAAAA);
        core.setRegister(4, 0xBBBB);
        int mask = (1 << 0) | (1 << 4);
        int raw = ldmStm(true, true, false, 13, mask); // STMDB SP!, {r0,r4} == PUSH.W {r0,r4}
        run(core, hi(raw), lo(raw));
        assertEquals(0x1F8, core.register(13));
        assertEquals(0xAAAA, core.memory().read32(0x1F8));
        assertEquals(0xBBBB, core.memory().read32(0x1FC));
    }

    @Test
    void popWideAliasIsLdmiaOnStackPointerAndInterworksWithPc() {
        ArmCore core = newCore(); // ARMV6K -> LOAD_PC_INTERWORKING presente
        core.setRegister(13, 0x200);
        core.memory().write32(0x200, 0xAAAA);
        core.memory().write32(0x204, 0x1001); // PC alvo com bit0=1 -> permanece THUMB
        int mask = (1 << 0) | (1 << 15);
        int raw = ldmStm(false, true, true, 13, mask); // LDMIA SP!, {r0,pc} == POP.W {r0,pc}
        run(core, hi(raw), lo(raw));
        assertEquals(0xAAAA, core.register(0));
        assertEquals(0x1000, core.programCounter());
        assertTrue(core.cpsr().isThumbMode());
    }

    @Test
    void popWideAlignsToArmWithoutLoadPcInterworkingFeature() {
        ArmArchitecture noInterworking = ArmArchitecture.extending(
                        ArmArchitecture.ARMV4T, "NoInterworking", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV4T, "NoInterworking-Inner", ArmFeature.THUMB2))));
        ArmCore core = new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), noInterworking);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x200);
        core.memory().write32(0x200, 0xAAAA);
        core.memory().write32(0x204, 0x1005); // bit0=1 seria interworking; sem a feature é só alinhado
        int mask = (1 << 0) | (1 << 15);
        int raw = ldmStm(false, true, true, 13, mask);
        run(core, hi(raw), lo(raw));
        assertEquals(0x1004, core.programCounter()); // alinhado ao modo atual (THUMB, ~1), sem trocar modo
        assertTrue(core.cpsr().isThumbMode());
    }

    @Test
    void stmWideWithPcInListIsNotClaimedAsStoreMultiple() {
        // STM com PC na lista é UNPREDICTABLE; o decoder devolve `null`. B2.2.2: `raw` continua
        // batendo com `EXTRA_TOP7` (`claimsEncodingSpace`), então vira UNDEFINED explícito em vez
        // de cair no caminho legado — o essencial continua sendo que a semântica errada de
        // STORE_MULTIPLE nunca é executada.
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = ldmStm(false, true, false, 1, (1 << 0) | (1 << 15));
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.STORE_MULTIPLE, instruction.kind());
    }

    // ── B9.7: RFE/SRS — mesmos 2 combos (P,U) que LDM.W/STM.W NÃO usam, dentro de EXTRA_TOP7 ──

    /// `RFE{DA,DB,IA,IB} Rn{!}` — T32 só define os combos `(P,U)==(0,0)`("DA")/`(1,1)`("IB"); os
    /// outros 2 pertencem a `LDM.W`/`STM.W` (ver `ldmStm` acima). Mesmo layout de `ldmStm`, sem
    /// `mask` (tail fixo `0xC000`).
    private static int rfeT(boolean p, boolean u, boolean w, int rn) {
        return (0b1110100 << 25) | ((p ? 1 : 0) << 24) | ((u ? 1 : 0) << 23)
                | ((w ? 1 : 0) << 21) | (1 << 20) | (rn << 16) | 0xC000;
    }

    /// `SRS{DA,DB,IA,IB} #mode{!}` — `Rn`(19:16) fixo em `0b1101` (não é registrador real).
    private static int srsT(boolean p, boolean u, boolean w, int mode) {
        return (0b1110100 << 25) | ((p ? 1 : 0) << 24) | ((u ? 1 : 0) << 23)
                | ((w ? 1 : 0) << 21) | (0b1101 << 16) | 0xC000 | (mode & 0x1F);
    }

    @Test
    void rfeDecodesForBothValidPuCombinations() {
        // (P,U)==(0,0) e (1,1) — os 2 combos que pertencem a RFE/SRS neste espaço (ver javadoc).
        boolean[][] puCombos = {{false, false}, {true, true}};
        for (boolean[] pu : puCombos) {
            TestAddressSpace memory = new TestAddressSpace(16);
            int raw = rfeT(pu[0], pu[1], true, 1); // RFE r1!
            memory.put16(0, hi(raw));
            memory.put16(2, lo(raw));
            DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
            assertEquals(InstructionKind.RETURN_FROM_EXCEPTION, instruction.kind(),
                    "p=" + pu[0] + " u=" + pu[1]);
            assertEquals(1, instruction.sourceRegister());
            assertTrue(instruction.writeback());
        }
    }

    @Test
    void srsDecodesForBothValidPuCombinationsWithTargetMode() {
        boolean[][] puCombos = {{false, false}, {true, true}};
        for (boolean[] pu : puCombos) {
            TestAddressSpace memory = new TestAddressSpace(16);
            int raw = srsT(pu[0], pu[1], true, 0b10011); // SRS #0x13 (SVC)!
            memory.put16(0, hi(raw));
            memory.put16(2, lo(raw));
            DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
            assertEquals(InstructionKind.STORE_RETURN_STATE, instruction.kind(),
                    "p=" + pu[0] + " u=" + pu[1]);
            assertEquals(0b10011, instruction.immediate());
            assertTrue(instruction.writeback());
        }
    }

    @Test
    void rfeIsUndefinedWithoutModeChangeInstructionsFeature() {
        ArmArchitecture noModeChange = ArmArchitecture.extending(
                        ArmArchitecture.ARMV4T, "NoModeChange-Rfe", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2LoadStoreDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV4T, "NoModeChange-Rfe-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = rfeT(false, false, true, 1);
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(noModeChange).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void rfeAndSrsAreNotClaimedForTheOtherTwoPuCombinations() {
        // (P,U)==(0,1) e (1,0) pertencem a LDM.W/STM.W (IA/DB) — `decodeReturnFromExceptionOrStoreReturnState`
        // nunca é chamado para esses combos (branch `if`/`else if` do chamador já os desvia).
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = rfeT(false, true, true, 1); // (P=0,U=1) = IA -> pertence a LDM.W, não RFE
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertNotEquals(InstructionKind.RETURN_FROM_EXCEPTION, instruction.kind());
    }

    @Test
    void ldmWideWithStackPointerInListIsNotClaimedAsLoadMultiple() {
        // SP na lista é UNPREDICTABLE; mesma correção de B2.2.2 do teste acima.
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = ldmStm(false, true, true, 1, (1 << 0) | (1 << 13));
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.LOAD_MULTIPLE, instruction.kind());
    }

    // ── Equivalência PER_OP: sem divergência do interpretador (mesmo harness de B1.x/B2.x) ──

    @Test
    void loadStoreBlockMatchesInterpretedReferenceThroughAsmEmitter() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int ldrT3 = t3(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, 0x10);            // LDR r0,[r1,#0x10]
        int strT4 = t4(UNSIGNED_TOP8, SIZE_L_STR, 1, 0, true, true, true, 4); // STR r0,[r1,#4]!
        int ldrd = ldrd(true, true, false, true, 1, 2, 7, 0);            // LDRD r2,r7,[r1]
        memory.put16(0, hi(ldrT3));
        memory.put16(2, lo(ldrT3));
        memory.put16(4, hi(strT4));
        memory.put16(6, lo(strT4));
        memory.put16(8, hi(ldrd));
        memory.put16(10, lo(ldrd));
        memory.write32(0x10, 0xCAFEBABE);

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 3);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter thumb2Reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        harness.assertEquivalent(thumb2Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(1, 0x20)));
    }

    // ── Gating G2: sem THUMB2, o candidato cai no caminho legado (comportamento inalterado) ──

    @Test
    void presetsWithoutThumb2DoNotUseThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = t3(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, 0x10);
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.LOAD, instruction.kind());
    }
}
