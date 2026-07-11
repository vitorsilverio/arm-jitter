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
        // reivindica). Devido à ambiguidade conhecida e já rastreada em B2.2.2 (`top5=0b11111`
        // também é o formato exato do sufixo standalone de um `BL`, ver `ThumbDecoder`), o
        // `ThumbDecoder` cai no caminho legado em vez de UNDEFINED explícito — o importante aqui é
        // que NUNCA vira `LOAD`/`STORE` (nenhuma semântica de load/store incorreta é executada).
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = t4(UNSIGNED_TOP8, SIZE_L_LDR, 1, 0, true, true, false, 4);
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertNotEquals(InstructionKind.LOAD, instruction.kind());
        assertNotEquals(InstructionKind.STORE, instruction.kind());
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
        // STM com PC na lista é UNPREDICTABLE; o decoder devolve `null`. Mesma limitação conhecida
        // de `t4UnprivilegedFormIsNotClaimedAsLoad` acima (B2.2.2) — o essencial é que a semântica
        // errada de STORE_MULTIPLE nunca é executada.
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = ldmStm(false, true, false, 1, (1 << 0) | (1 << 15));
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertNotEquals(InstructionKind.STORE_MULTIPLE, instruction.kind());
    }

    @Test
    void ldmWideWithStackPointerInListIsNotClaimedAsLoadMultiple() {
        // SP na lista é UNPREDICTABLE; mesma limitação conhecida acima.
        TestAddressSpace memory = new TestAddressSpace(16);
        int raw = ldmStm(false, true, true, 1, (1 << 0) | (1 << 13));
        memory.put16(0, hi(raw));
        memory.put16(2, lo(raw));
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
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
