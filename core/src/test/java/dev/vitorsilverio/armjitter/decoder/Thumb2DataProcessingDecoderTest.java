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

/// B2.2 — data-processing Thumb-2 de 32 bits: modified immediate (ThumbExpandImm_C, incl. carry),
/// `MOVW`/`MOVT`, `ADD`/`SUB` plain binary (incl. `ADR` via `Rn=PC`), e a forma registrador com
/// shift imediato. Ver `Thumb2DataProcessingDecoder` para o layout de bits.
class Thumb2DataProcessingDecoderTest extends BlockEquivalenceTest {
    private static final ArmArchitecture THUMB2_ARCH_FEATURES = ArmArchitecture.extending(
                    ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-DataProcessing", ArmFeature.THUMB2,
                    ArmFeature.MOVW_MOVT);
    private static final ArmArchitecture THUMB2_ARCH = THUMB2_ARCH_FEATURES
            .withThumb32DecoderExtensions(List.of(new Thumb2DataProcessingDecoder(THUMB2_ARCH_FEATURES)));

    // ── Builders de encoding (ver Thumb2DataProcessingDecoder para o layout) ───────────────

    private static int modifiedImmediateHi(int i, int op4, boolean s, int rn) {
        return (0b11110 << 11) | (i << 10) | (op4 << 5) | ((s ? 1 : 0) << 4) | rn;
    }

    private static int dataProcessingLo(int imm3, int rd, int imm8) {
        return (imm3 << 12) | (rd << 8) | imm8;
    }

    private static int registerFormHi(int op4, boolean s, int rn) {
        return (0b1110101 << 9) | (op4 << 5) | ((s ? 1 : 0) << 4) | rn;
    }

    private static int registerFormLo(int imm3, int rd, int imm2, int shiftType, int rm) {
        return (imm3 << 12) | (rd << 8) | (imm2 << 6) | (shiftType << 4) | rm;
    }

    private static int plainBinaryHi(int i, int opNibble, int rn) {
        return (0b11110 << 11) | (i << 10) | (1 << 9) | (opNibble << 5) | rn;
    }

    private static int moveWideHi(int i, int opNibble, int imm4) {
        return (0b11110 << 11) | (i << 10) | (1 << 9) | (opNibble << 5) | imm4;
    }

    private static final int OP4_AND = 0b0000;
    private static final int OP4_ORR = 0b0010;
    private static final int OP4_ORN = 0b0011;
    private static final int OP4_ADD = 0b1000;
    private static final int PLAIN_OP_ADD = 0b0000;
    private static final int PLAIN_OP_SUB = 0b0101;
    private static final int PLAIN_OP_MOVW = 0b0010;
    private static final int PLAIN_OP_MOVT = 0b0110;
    private static final int SHIFT_LSL = 0;
    private static final int SHIFT_ROR = 3;

    // ── B3.2: SBFX/UBFX/BFI/BFC — mesmo grupo "plain binary immediate" ─────────────────────

    private static final int PLAIN_OP_SBFX = 0b1010;
    private static final int PLAIN_OP_BFI_BFC = 0b1011;
    private static final int PLAIN_OP_UBFX = 0b1110;
    private static final int PROGRAM_COUNTER = 15;

    private static final ArmArchitecture BITFIELD_ARCH_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-BitField", ArmFeature.THUMB2, ArmFeature.BIT_FIELD);
    private static final ArmArchitecture BITFIELD_ARCH = BITFIELD_ARCH_FEATURES
            .withThumb32DecoderExtensions(List.of(new Thumb2DataProcessingDecoder(BITFIELD_ARCH_FEATURES)));
    /// `ArmDecoder` (encoding ARM clássico) também exige `BIT_FIELD` — `ArmArchitecture.ARMV6K`
    /// puro NÃO tem essa feature (só chega em `ARMV7A`, B3.7), então as comparações ida-e-volta
    /// desta seção usam esta arquitetura, não `ArmArchitecture.ARMV6K` diretamente.
    private static final ArmArchitecture ARM_CLASSIC_BITFIELD_ARCH = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARM-TestClassic-BitField", ArmFeature.BIT_FIELD);

    // ── B9.7: PKH — mesmo grupo "Data-processing (register)" (`op4=0110`, `S` sempre 0) ────

    private static final ArmArchitecture PKH_ARCH_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-Pkh", ArmFeature.THUMB2, ArmFeature.PACK_SATURATE);
    private static final ArmArchitecture PKH_ARCH = PKH_ARCH_FEATURES
            .withThumb32DecoderExtensions(List.of(new Thumb2DataProcessingDecoder(PKH_ARCH_FEATURES)));
    private static final ArmArchitecture ARM_CLASSIC_PKH_ARCH = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K, "ARM-TestClassic-Pkh", ArmFeature.PACK_SATURATE);

    private static ArmCore newPkhCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), PKH_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    /// `lo` das formas de bitfield: `0 imm3 Rd imm2 0 widthOrMsb` — layout DIFERENTE de
    /// {@link #dataProcessingLo}, não contíguo (`imm3` em `raw[14:12]`, `imm2` em `raw[7:6]`).
    private static int bitFieldLo(int imm3, int rd, int imm2, int widthMinusOneOrMsb) {
        return (imm3 << 12) | (rd << 8) | (imm2 << 6) | widthMinusOneOrMsb;
    }

    private static ArmCore newBitFieldCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), BITFIELD_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void runBitField(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    private static ArmCore newCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void run(ArmCore core, int... halfwords) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        for (int i = 0; i < halfwords.length; i++) {
            memory.put16(base + i * 2, halfwords[i]);
        }
        for (int i = 0; i < halfwords.length; i += 2) {
            core.step();
        }
    }

    // ── ThumbExpandImm: os 4 casos de decodificação ─────────────────────────────────────────

    @Test
    void thumbExpandImmZeroExtendsWhenSelectorIsZero() {
        // i=0, imm3<1:0>=00 (byte simples): AND r0, r0, #0xAB
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b000, 0, 0xAB));
        assertEquals(0x0000_00AB, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmReplicatesToAlternatingHalfwordsWhenSelectorIsOne() {
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b001, 0, 0xAB));
        assertEquals(0x00AB_00AB, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmReplicatesToOddHalfwordsWhenSelectorIsTwo() {
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b010, 0, 0xAB));
        assertEquals(0xAB00_AB00, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmReplicatesToAllFourBytesWhenSelectorIsThree() {
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b011, 0, 0xAB));
        assertEquals(0xABAB_ABAB, decoder.decode(memory, 0).immediate());
    }

    @Test
    void thumbExpandImmRotatesAndProducesCarryOutWhenImm3Top2BitsAreNonzero() {
        // i=0, imm3=100, imm8=0x01 -> imm12<11:10> = 0,1 (rotaciona): unrotated=0x81,
        // rotateAmount=8 -> 0x81000000 (bit31=1 -> carry esperado = 1).
        ArmCore core = newCore();
        core.setRegister(1, 0xFFFF_FFFF); // ANDS preserva só os bits do imediato
        run(core, modifiedImmediateHi(0, OP4_AND, true, 1) /* ANDS r0, r1, #imm */,
                dataProcessingLo(0b100, 0, 0x01));
        assertEquals(0x8100_0000, core.register(0));
        assertTrue(core.cpsr().carry(), "carry-out deve refletir o bit 31 do imediato rotacionado");
        assertTrue(core.cpsr().negative());
    }

    // ── MOVW / MOVT ──────────────────────────────────────────────────────────────────────

    @Test
    void movwThenMovtBuildsFullThirtyTwoBitConstant() {
        ArmCore core = newCore();
        run(core, moveWideHi(0, PLAIN_OP_MOVW, 1), dataProcessingLo(0b010, 0, 0x34)); // MOVW r0, #0x1234
        assertEquals(0x0000_1234, core.register(0));
        run(core, moveWideHi(0, PLAIN_OP_MOVT, 5), dataProcessingLo(0b110, 0, 0x78)); // MOVT r0, #0x5678
        assertEquals(0x5678_1234, core.register(0));
    }

    @Test
    void movtPreservesLowHalfOfExistingRegisterValue() {
        ArmCore core = newCore();
        core.setRegister(0, 0xFFFF_CAFE);
        run(core, moveWideHi(0, PLAIN_OP_MOVT, 0), dataProcessingLo(0b000, 0, 0x00)); // MOVT r0, #0
        assertEquals(0x0000_CAFE, core.register(0));
    }

    // ── ADD/SUB (SP/PC) e ADR ────────────────────────────────────────────────────────────

    @Test
    void addSpPlusImmediatePlainBinaryFormComputesStackRelativeAddress() {
        ArmCore core = newCore();
        core.setRegister(13, 0x1000); // SP
        run(core, plainBinaryHi(0, PLAIN_OP_ADD, 13), dataProcessingLo(0b000, 2, 0x20)); // ADDW r2, SP, #0x20
        assertEquals(0x1020, core.register(2));
    }

    @Test
    void addPcPlusImmediateIsAdrWithFourByteAlignedPc() {
        ArmCore core = newCore();
        int base = core.programCounter();
        run(core, plainBinaryHi(0, PLAIN_OP_ADD, 15), dataProcessingLo(0b000, 3, 0x10)); // ADR r3, +0x10
        int expected = ((base + 4) & ~3) + 0x10;
        assertEquals(expected, core.register(3));
    }

    @Test
    void subPcMinusImmediateIsAdrNegative() {
        ArmCore core = newCore();
        int base = core.programCounter();
        run(core, plainBinaryHi(0, PLAIN_OP_SUB, 15), dataProcessingLo(0b000, 3, 0x10)); // ADR r3, -0x10
        int expected = ((base + 4) & ~3) - 0x10;
        assertEquals(expected, core.register(3));
    }

    // ── Forma registrador com shift imediato ────────────────────────────────────────────

    @Test
    void registerFormWithLslShiftMatchesArmClassicEquivalent() {
        // Thumb-2: ADD r2, r0, r1, LSL #4
        ArmCore thumb2Core = newCore();
        thumb2Core.setRegister(0, 0x1000);
        thumb2Core.setRegister(1, 0x0001);
        run(thumb2Core, registerFormHi(OP4_ADD, true, 0), registerFormLo(0b001, 2, 0b00, SHIFT_LSL, 1));

        // ARM classico: ADDS r2, r0, r1, LSL #4 (E0902201)
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(0, 0x1000);
        armCore.setRegister(1, 0x0001);
        armCore.memory().write32(0, 0xE090_2201);
        armCore.step();

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(armCore.cpsr().negative(), thumb2Core.cpsr().negative());
        assertEquals(armCore.cpsr().zero(), thumb2Core.cpsr().zero());
        assertEquals(armCore.cpsr().carry(), thumb2Core.cpsr().carry());
        assertEquals(armCore.cpsr().overflow(), thumb2Core.cpsr().overflow());
    }

    @Test
    void registerFormRrxMatchesArmClassicEquivalent() {
        // Thumb-2: ANDS r2, r0, r1, RRX
        ArmCore thumb2Core = newCore();
        thumb2Core.setRegister(0, 0x8000_0001);
        thumb2Core.setRegister(1, 0x0000_0003);
        thumb2Core.cpsr().setNzcv(false, false, true, false); // carry-in = 1
        run(thumb2Core, registerFormHi(OP4_AND, true, 0), registerFormLo(0b000, 2, 0b00, SHIFT_ROR, 1));

        // ARM classico: ANDS r2, r0, r1, RRX (E0102061)
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(0, 0x8000_0001);
        armCore.setRegister(1, 0x0000_0003);
        armCore.cpsr().setNzcv(false, false, true, false);
        armCore.memory().write32(0, 0xE010_2061);
        armCore.step();

        assertEquals(armCore.register(2), thumb2Core.register(2));
        assertEquals(armCore.cpsr().carry(), thumb2Core.cpsr().carry());
    }

    // ── PER_OP fallback: sem divergência do interpretador (mesmo harness de B1.2-B1.5) ─────

    @Test
    void dataProcessingBlockFallsBackToPerOpAndMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, true, 0));  // ANDS r0, r0, #0xFF
        memory.put16(2, dataProcessingLo(0b000, 0, 0xFF));
        memory.put16(4, modifiedImmediateHi(0, OP4_ORN, false, 0)); // ORN r1, r0, #0xFF (B2.2:
        memory.put16(6, dataProcessingLo(0b000, 1, 0xFF));          // opcode novo, sem emissão nativa ainda)
        memory.put16(8, registerFormHi(OP4_ORR, false, 0));         // ORR r2, r0, r1, LSL #2
        memory.put16(10, registerFormLo(0b000, 2, 0b10, SHIFT_LSL, 1));

        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 3);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter thumb2Reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        assertTrue(asmEmitter.perOpFallbackOpCount() >= 0); // sanity: contador existe antes do run
        harness.assertEquivalent(thumb2Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x0000_FF00)));
        assertTrue(asmEmitter.perOpFallbackOpCount() > 0,
                "AND/MOVW/ORR de Thumb-2 (B2.2) ainda não têm emissão ASM nativa");
    }

    // ── B2.2.2: op4 reservado sob o prefixo 0b1110101 vira UNDEFINED, não LONG_BRANCH_SUFFIX ──

    @Test
    void reservedOp4UnderRegisterFormPrefixIsUndefinedNotLongBranchSuffix() {
        // op4 = 0b1100 (PKH na tabela A5-10 real, não implementado por esta classe — ver
        // Thumb2DataProcessingDecoder#decodeRegisterForm): estruturalmente dentro do prefixo de 7
        // bits que esta extensão reconhece (0b1110101), mas devolve `null` porque `plainKindFor`
        // não cobre esse op4. Antes de B2.2.2, `ThumbDecoder` delegava isso ao caminho legado
        // BL/BLX de 16 bits (LONG_BRANCH_SUFFIX) por ausência de outra alternativa.
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(16);
        int reservedOp4 = 0b1100;
        memory.put16(0, registerFormHi(reservedOp4, false, 0));
        memory.put16(2, registerFormLo(0b000, 0, 0b00, SHIFT_LSL, 1));

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.LONG_BRANCH_SUFFIX, instruction.kind());
    }

    // ── Gating G2: sem THUMB2, o candidato cai no caminho legado (comportamento inalterado) ─

    @Test
    void presetsWithoutThumb2DoNotUseThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, modifiedImmediateHi(0, OP4_AND, false, 0));
        memory.put16(2, dataProcessingLo(0b000, 0, 0xAB));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.AND, instruction.kind());
    }

    // ── B3.2: SBFX/UBFX — ida-e-volta contra o ARM clássico (mesmos vetores de B3.1) ────────

    @Test
    void ubfxExtractsFieldWithoutSignExtensionMatchesArmClassic() {
        // UBFX r0, r1, #4, #8 com r1=0xABCD1234 -> r0=0x23 (mesmo exemplo de B3.1).
        ArmCore thumb2Core = newBitFieldCore();
        thumb2Core.setRegister(1, 0xABCD_1234);
        runBitField(thumb2Core, plainBinaryHi(0, PLAIN_OP_UBFX, 1), bitFieldLo(0b001, 0, 0b00, 7));

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_BITFIELD_ARCH);
        armCore.setRegister(1, 0xABCD_1234);
        armCore.memory().write32(0, 0xE7E7_0251); // UBFX r0, r1, #4, #8
        armCore.step();

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0x23, thumb2Core.register(0));
    }

    @Test
    void sbfxSignExtendsTheExtractedFieldMatchesArmClassic() {
        // SBFX r0, r1, #0, #8 com r1=0x80 -> r0=0xFFFFFF80 (com sinal); UBFX no mesmo raw base ->
        // 0x80 (sem sinal) — mesmo exemplo de B3.1, provando a diferença entre as duas formas.
        ArmCore signedCore = newBitFieldCore();
        signedCore.setRegister(1, 0x80);
        runBitField(signedCore, plainBinaryHi(0, PLAIN_OP_SBFX, 1), bitFieldLo(0b000, 0, 0b00, 7));
        assertEquals(0xFFFF_FF80, signedCore.register(0));

        ArmCore unsignedCore = newBitFieldCore();
        unsignedCore.setRegister(1, 0x80);
        runBitField(unsignedCore, plainBinaryHi(0, PLAIN_OP_UBFX, 1), bitFieldLo(0b000, 0, 0b00, 7));
        assertEquals(0x80, unsignedCore.register(0));

        ArmCore armSignedCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_BITFIELD_ARCH);
        armSignedCore.setRegister(1, 0x80);
        armSignedCore.memory().write32(0, 0xE7A7_0051); // SBFX r0, r1, #0, #8
        armSignedCore.step();
        assertEquals(armSignedCore.register(0), signedCore.register(0));
    }

    // ── B3.2: BFI/BFC — ida-e-volta contra o ARM clássico (mesmos vetores de B3.1) ──────────

    @Test
    void bfcClearsTheFieldLeavingTheRestUntouchedMatchesArmClassic() {
        // BFC r0, #4, #8 com r0=0xFFFFFFFF -> limpa bits [11:4] -> 0xFFFFF00F (mesmo exemplo de B3.1).
        ArmCore thumb2Core = newBitFieldCore();
        thumb2Core.setRegister(0, 0xFFFF_FFFF);
        runBitField(thumb2Core, plainBinaryHi(0, PLAIN_OP_BFI_BFC, PROGRAM_COUNTER), bitFieldLo(0b001, 0, 0b00, 11));

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_BITFIELD_ARCH);
        armCore.setRegister(0, 0xFFFF_FFFF);
        armCore.memory().write32(0, 0xE7CB_021F); // BFC r0, #4, #8
        armCore.step();

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0xFFFF_F00F, thumb2Core.register(0));
    }

    @Test
    void bfiInsertsWithoutTouchingTheRestOfDstMatchesArmClassic() {
        // BFI r0, r1, #4, #8 com r0=0xFFFFFFFF, r1=0xAB -> insere 0xAB em [11:4], resto intacto
        // (mesmo exemplo de B3.1).
        ArmCore thumb2Core = newBitFieldCore();
        thumb2Core.setRegister(0, 0xFFFF_FFFF);
        thumb2Core.setRegister(1, 0xAB);
        runBitField(thumb2Core, plainBinaryHi(0, PLAIN_OP_BFI_BFC, 1), bitFieldLo(0b001, 0, 0b00, 11));

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_BITFIELD_ARCH);
        armCore.setRegister(0, 0xFFFF_FFFF);
        armCore.setRegister(1, 0xAB);
        armCore.memory().write32(0, 0xE7CB_0211); // BFI r0, r1, #4, #8
        armCore.step();

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0xFFFF_FABF, thumb2Core.register(0));
    }

    // ── B3.2: gate ausente e G2 ──────────────────────────────────────────────────────────────

    @Test
    void bitFieldOpsAreUndefinedWithoutBitFieldFeature() {
        ArmArchitecture noBitField = ArmArchitecture.extending(ArmArchitecture.ARMV6K, "NoBitField",
                ArmFeature.THUMB2);
        ArmArchitecture arch = noBitField.withThumb32DecoderExtensions(
                List.of(new Thumb2DataProcessingDecoder(noBitField)));
        int[][] encodings = {
            {plainBinaryHi(0, PLAIN_OP_SBFX, 1), bitFieldLo(0b000, 0, 0b00, 7)},
            {plainBinaryHi(0, PLAIN_OP_UBFX, 1), bitFieldLo(0b000, 0, 0b00, 7)},
            {plainBinaryHi(0, PLAIN_OP_BFI_BFC, 1), bitFieldLo(0b001, 0, 0b00, 11)},
        };
        for (int[] encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(16);
            memory.put16(0, encoding[0]);
            memory.put16(2, encoding[1]);
            DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
            assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind(),
                    "hi=" + Integer.toHexString(encoding[0]) + " deveria ser UNDEFINED sem BIT_FIELD");
        }
    }

    @Test
    void bitFieldOpsDoNotDecodeUnderArmv6kPlainPreset() {
        // G2: sem THUMB2 (preset público ARMV6K), o candidato Thumb-2 de 32 bits nem chega a este
        // decoder — cai no caminho legado de 16 bits, nunca em BIT_FIELD_EXTRACT/INSERT.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, plainBinaryHi(0, PLAIN_OP_SBFX, 1));
        memory.put16(2, bitFieldLo(0b000, 0, 0b00, 7));
        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);
        assertNotEquals(InstructionKind.BIT_FIELD_EXTRACT, instruction.kind());
    }

    // ── B9.7: PKH ────────────────────────────────────────────────────────────────────────────

    @Test
    void pkhBtWithNoShiftMatchesArmClassicEquivalent() {
        // PKH r0, r1, r2 (BT, sem shift): Rd[31:16] = (Rm LSL 0)[31:16], Rd[15:0] = Rn[15:0]
        // (ARM DDI 0406C A8.8.125) -> r0 = 0xAAAA0000 | 0x0000BBBB.
        ArmCore thumb2Core = newPkhCore();
        thumb2Core.setRegister(1, 0xFFFF_BBBB);
        thumb2Core.setRegister(2, 0xAAAA_FFFF);
        runBitField(thumb2Core, 0xEAC1, 0x0002); // PKHBT r0,r1,r2 (imm5=0,tb=0)

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_PKH_ARCH);
        armCore.setRegister(1, 0xFFFF_BBBB);
        armCore.setRegister(2, 0xAAAA_FFFF);
        armCore.memory().write32(0, 0xE681_0012); // PKHBT r0,r1,r2
        armCore.step();

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0xAAAA_BBBB, thumb2Core.register(0));
    }

    @Test
    void pkhTbWithShiftSixteenMatchesArmClassicEquivalent() {
        // PKH r0, r1, r2, ASR #16 (TB): Rd = Rn[31:16] | (Rm ASR #16)[15:0] -> r0 = 0xFFFF0000 | 0x0000AAAA.
        ArmCore thumb2Core = newPkhCore();
        thumb2Core.setRegister(1, 0xFFFF_BBBB);
        thumb2Core.setRegister(2, 0xAAAA_0000);
        runBitField(thumb2Core, 0xEAC1, 0x4022); // PKHTB r0,r1,r2,ASR #16 (imm5=16,tb=1)

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ARM_CLASSIC_PKH_ARCH);
        armCore.setRegister(1, 0xFFFF_BBBB);
        armCore.setRegister(2, 0xAAAA_0000);
        armCore.memory().write32(0, 0xE681_0852); // PKHTB r0,r1,r2,ASR #16
        armCore.step();

        assertEquals(armCore.register(0), thumb2Core.register(0));
        assertEquals(0xFFFF_AAAA, thumb2Core.register(0));
    }

    @Test
    void pkhIsUndefinedWithoutPackSaturateFeature() {
        // `ArmArchitecture.ARMV6K` já TEM `PACK_SATURATE` por padrão (mesmo achado de
        // `dspMultiplyFamiliesAreUndefinedWithoutDspMultiplyFeature` em `Thumb2MultiplyDecoderTest`
        // para `SIGNED_MULTIPLY_MEDIA`) — precisa de um preset "vazio" (`ArmArchitecture.of`), não
        // `extending(ARMV6K, ...)`.
        ArmArchitecture noPackSaturate = ArmArchitecture.of("NoPackSaturate-Pkh", ArmFeature.THUMB2);
        ArmArchitecture arch = noPackSaturate.withThumb32DecoderExtensions(
                List.of(new Thumb2DataProcessingDecoder(noPackSaturate)));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xEAC1);
        memory.put16(2, 0x0002);
        DecodedInstruction instruction = new ThumbDecoder(arch).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }
}
