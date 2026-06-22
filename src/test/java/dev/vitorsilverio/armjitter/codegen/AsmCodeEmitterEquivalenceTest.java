package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.core.ArmCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsmCodeEmitterEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter();

    @Test
    void movAddBlockMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        memory.put32(8, 0xE7F0_00F0);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void subAndCmpBlockMatchesInterpreted() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0014);
        memory.put32(4, 0xE240_0004);
        memory.put32(8, 0xE200_0003);
        memory.put32(12, 0xE350_000A);
        memory.put32(16, 0xE7F0_00F0);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 4);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void fallsBackToInterpretedForUnsupportedBlocks() {
        // 0xE1A00211 = MOV r0, r1, LSL r2 — ShiftedRegister src2, always unsupported.
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE1A00211);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 2);

        assertFalse(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void reportsJvmBackendAndSupportedOpcodes() {
        assertEquals(CodegenBackend.JVM_BYTECODE, asmEmitter.backend());
        assertTrue(AsmCodeEmitter.supportedAluOpcodes().contains(IrOpCode.MOV));
        assertTrue(AsmCodeEmitter.supportedAluOpcodes().contains(IrOpCode.CMP));
    }

    // ── Phase 5 equivalence tests ──────────────────────────────────────────────

    @Test
    void loadWordMatchesInterpreted() {
        // LDR r1, [r0] = 0xE5901000
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE5901000);
        memory.put32(0x40, 0xDEADBEEF);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x40)));
    }

    @Test
    void storeWordMatchesInterpreted() {
        // STR r0, [r1] = 0xE5810000
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE5810000);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x12345678);
                    core.setRegister(1, 0x40);
                }));
    }

    @Test
    void branchMatchesInterpreted() {
        // B #0 = 0xEA000000 — target = PC + 8 + 0 = 8 (executing at address 0, PC = 8)
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xEA000000);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void multiplyMatchesInterpreted() {
        // MUL r0, r1, r2 = 0xE0000291  (r0 = r1 * r2)
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE0000291);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(1, 7);
                    core.setRegister(2, 6);
                }));
    }

    @Test
    void branchExchangeMatchesInterpreted() {
        // BX r0 = 0xE12FFF10
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12FFF10);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x100)));
    }

    @Test
    void ldmMatchesInterpreted() {
        // LDMIA r0, {r1, r2} = 0xE8900006
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8900006);
        memory.put32(0x40, 0x11111111);
        memory.put32(0x44, 0x22222222);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, 1);

        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 0x40)));
    }

    // ── Conditional execution (cond != AL) ──────────────────────────────────────
    // O ASM JIT emite um guard `evalCond` por op (espelhando o interpretador). Estes testes
    // exigem JIT≡interp para o ramo TOMADO e o PULADO, exaustivamente por código de condição
    // (EQ..LE, ARM nibble = Condition.ordinal()) e por estado de flags NZCV.

    /// O ordinal de {@link dev.vitorsilverio.armjitter.core.Condition} == nibble de condição ARM
    /// (EQ=0..LE=13); AL=14 é o caso já coberto pelos testes acima. Constrói `MOV<cond> r1, #0x42`.
    private static final int FIRST_COND = 0;   // EQ
    private static final int LAST_COND = 13;   // LE

    private static IrBlock liftArm(TestAddressSpace memory, int count) {
        return new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder()).lift(memory, 0, count);
    }

    /// Seta N,Z,C,V a partir dos 4 bits de `nzcv` (N=bit3, Z=bit2, C=bit1, V=bit0).
    private static void applyFlags(ArmCore core, int nzcv) {
        core.cpsr().setNzcv((nzcv & 8) != 0, (nzcv & 4) != 0, (nzcv & 2) != 0, (nzcv & 1) != 0);
    }

    @Test
    void conditionalMovMatchesInterpretedAcrossAllCodesAndFlags() {
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            int instruction = (cond << 28) | 0x03A01042;   // MOV<cond> r1, #0x42 (no setFlags)
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, instruction);
            IrBlock block = liftArm(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "MOV<cond> must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                assertBlockEquivalent(asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(1, 0x11111111);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalAddSettingFlagsMatchesInterpretedAcrossAllCodesAndFlags() {
        // ADD<cond>S r1, r2, #1 — exercita o caminho setFlags (TEMP/ADDR locals + updateAddFlags)
        // sob o guard; com r2=0x7FFFFFFF a soma satura → flags ricos quando tomado.
        for (int cond = FIRST_COND; cond <= LAST_COND; cond++) {
            int instruction = (cond << 28) | 0x02921001;
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(0, instruction);
            IrBlock block = liftArm(memory, 1);
            assertTrue(asmEmitter.isNativeSupported(block), "ADD<cond>S must be native: cond=" + cond);
            for (int nzcv = 0; nzcv < 16; nzcv++) {
                int flags = nzcv;
                assertBlockEquivalent(asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.setRegister(1, 0x11111111);
                            core.setRegister(2, 0x7FFFFFFF);
                            applyFlags(core, flags);
                        }));
            }
        }
    }

    @Test
    void conditionalThenUnconditionalOpMatchesInterpreted() {
        // MOVEQ r1, #0x42 ; ADD r2, r2, #1 (AL) — testa o merge do condSkip na op seguinte.
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0x03A01042);
        memory.put32(4, 0xE2822001);
        IrBlock block = liftArm(memory, 2);
        assertTrue(asmEmitter.isNativeSupported(block));
        for (int nzcv = 0; nzcv < 16; nzcv++) {
            int flags = nzcv;
            assertBlockEquivalent(asmEmitter, block,
                    EquivalenceTestSupport.independentPair(memory, core -> {
                        core.setRegister(1, 0x11111111);
                        core.setRegister(2, 100);
                        applyFlags(core, flags);
                    }));
        }
    }

    @Test
    void conditionalBranchTakenAndNotTakenMatchInterpreted() {
        // BNE #0 = 0x1A000000 — tomado quando Z limpo (PC=8); não-tomado cai sequencial (PC=endPc).
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0x1A000000);
        IrBlock block = liftArm(memory, 1);
        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> applyFlags(core, 0b0100)));  // Z=1 → não-tomado
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> applyFlags(core, 0b0000)));  // Z=0 → tomado
    }

    @Test
    void conditionalBranchLinkTakenAndNotTakenMatchInterpreted() {
        // BLEQ #0 = 0x0B000000 — quando tomado grava LR (deve estar sob o guard).
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0x0B000000);
        IrBlock block = liftArm(memory, 1);
        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(14, 0x99999999); applyFlags(core, 0b0100); }));  // Z=1 → tomado: LR sobrescrito
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(14, 0x99999999); applyFlags(core, 0b0000); }));  // Z=0 → pulado: LR intacto
    }

    @Test
    void conditionalLoadTakenAndNotTakenMatchInterpreted() {
        // LDREQ r1, [r0] = 0x05901000 — tomado carrega; pulado mantém r1 e não soma ciclos de dado.
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0x05901000);
        memory.put32(0x40, 0xDEADBEEF);
        IrBlock block = liftArm(memory, 1);
        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(0, 0x40); core.setRegister(1, 0x11111111); applyFlags(core, 0b0100); }));  // EQ true
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(0, 0x40); core.setRegister(1, 0x11111111); applyFlags(core, 0b0000); }));  // EQ false
    }

    @Test
    void conditionalStoreTakenAndNotTakenMatchInterpreted() {
        // STREQ r0, [r1] = 0x05810000 ; LDR r2, [r1] = 0xE5912000 (AL — torna o store observável em r2).
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0x05810000);
        memory.put32(4, 0xE5912000);
        memory.put32(0x40, 0x00000000);
        IrBlock block = liftArm(memory, 2);
        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(0, 0x12345678); core.setRegister(1, 0x40); applyFlags(core, 0b0100); }));  // EQ true → grava e relê
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(0, 0x12345678); core.setRegister(1, 0x40); applyFlags(core, 0b0000); }));  // EQ false → pulado, relê 0
    }

    @Test
    void conditionalMultiplyTakenAndNotTakenMatchInterpreted() {
        // MULEQ r0, r1, r2 = 0x00000291
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0x00000291);
        IrBlock block = liftArm(memory, 1);
        assertTrue(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(0, 0x11111111); core.setRegister(1, 7); core.setRegister(2, 6); applyFlags(core, 0b0100); }));  // tomado
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(0, 0x11111111); core.setRegister(1, 7); core.setRegister(2, 6); applyFlags(core, 0b0000); }));  // pulado
    }

    @Test
    void conditionalShiftedRegisterStillFallsBack() {
        // MOVEQ r0, r1, LSL r2 = 0x01A00211 — ShiftedRegister é rejeitado por motivo NÃO-condicional.
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0x01A00211);
        IrBlock block = liftArm(memory, 1);
        assertFalse(asmEmitter.isNativeSupported(block));
        assertBlockEquivalent(asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { core.setRegister(1, 0xFF); core.setRegister(2, 2); applyFlags(core, 0b0100); }));
    }
}
