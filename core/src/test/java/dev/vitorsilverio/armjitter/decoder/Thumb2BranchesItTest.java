package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.ItState;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.jit.BlockCache;
import dev.vitorsilverio.armjitter.jit.BlockKey;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

/// B2.4 — Thumb-2 `IT` block + `B.W`/`CBZ`/`CBNZ`/`TBB`/`TBH`. Ver `b2.4-thumb2-branches-it.md`
/// para as decisões D1/D2/D3 e os fatos de referência (algoritmo do ITSTATE, encodings).
class Thumb2BranchesItTest {
    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.ARMV6K_THUMB2;
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    private static ArmCore newCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void put16(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value);
    }

    // ── Encoders ─────────────────────────────────────────────────────────────────────────────

    private static int it(int firstCond, int mask) {
        return 0xBF00 | ((firstCond & 0xF) << 4) | (mask & 0xF);
    }

    private static int cbz(boolean nonZero, int imm6, int rn) {
        boolean iBit = (imm6 & (1 << 6)) != 0;
        int imm5 = (imm6 >>> 1) & 0x1F;
        return 0xB100 | ((nonZero ? 1 : 0) << 11) | ((iBit ? 1 : 0) << 9) | (imm5 << 3) | (rn & 0x7);
    }

    private static int[] tableBranch(int rn, int rm, boolean halfword) {
        int hi = 0xE8D0 | (rn & 0xF);
        int lo = 0xF000 | ((halfword ? 1 : 0) << 4) | (rm & 0xF);
        return new int[]{hi, lo};
    }

    private static int[] branchWideT3(int cond, int offset) {
        int field20 = (offset >> 1) & 0xFFFFF;
        int s = (field20 >>> 19) & 1;
        int j2 = (field20 >>> 18) & 1;
        int j1 = (field20 >>> 17) & 1;
        int imm6 = (field20 >>> 11) & 0x3F;
        int imm11 = field20 & 0x7FF;
        int hi = 0xF000 | (s << 10) | (cond << 6) | imm6;
        int lo = 0x8000 | (j1 << 13) | (j2 << 11) | imm11;
        return new int[]{hi, lo};
    }

    private static int[] branchWideT4(int offset) {
        int field24 = (offset >> 1) & 0xFFFFFF;
        int s = (field24 >>> 23) & 1;
        int i1 = (field24 >>> 22) & 1;
        int i2 = (field24 >>> 21) & 1;
        int imm10 = (field24 >>> 11) & 0x3FF;
        int imm11 = field24 & 0x7FF;
        int j1 = i1 ^ s ^ 1;
        int j2 = i2 ^ s ^ 1;
        int hi = 0xF000 | (s << 10) | imm10;
        int lo = 0x9000 | (j1 << 13) | (j2 << 11) | imm11;
        return new int[]{hi, lo};
    }

    // ── Item 1: expansão da máscara IT contra o oráculo secundário (tabela x/y/z do bit mais
    // baixo setado do campo mask de 4 bits — ARM DDI 0406C A7.7.38 "IT" — formulação
    // INDEPENDENTE do shift-register de ItState) ────────────────────────────────────────────

    /// Oráculo SECUNDÁRIO independente de {@link ItState}: o comprimento do bloco (1-4) é dado
    /// pela posição do bit mais baixo SETADO no campo `mask` de 4 bits (bit3->1 instrução,
    /// bit2->2, bit1->3, bit0->4 — o sentinela de término); os bits ACIMA dessa posição (lidos do
    /// mais significativo ao menos significativo) são os seletores x/y/z — cada um se torna
    /// DIRETAMENTE a LSB da condição daquela instrução (top 3 bits = `firstcond`, inalterados).
    /// Este É o formato real do campo `mask` (ARM DDI 0406C A7.7.38): o *assembler* já grava
    /// `firstcond<0>` para "Then" e `NOT firstcond<0>` para "Else" diretamente nesses bits — não
    /// um XOR/inversão relativa que o decoder precisaria recalcular.
    private static List<Condition> maskTableExpansion(int firstCond4Bits, int maskField4Bits) {
        int normalized = ItState.normalizeFirstCond(firstCond4Bits);
        int lowestSetBit = Integer.numberOfTrailingZeros(maskField4Bits & 0xF);
        List<Condition> sequence = new java.util.ArrayList<>();
        sequence.add(conditionOf(normalized));
        for (int bitPos = 3; bitPos > lowestSetBit; bitPos--) {
            int lsb = (maskField4Bits >>> bitPos) & 1;
            int cond = (normalized & 0xE) | lsb;
            sequence.add(conditionOf(cond));
        }
        return sequence;
    }

    private static Condition conditionOf(int cond4Bits) {
        return cond4Bits >= 0xE ? Condition.AL : Condition.values()[cond4Bits];
    }

    private static List<Condition> itStateExpansion(int firstCond4Bits, int maskFieldFromInstruction) {
        int normalized = ItState.normalizeFirstCond(firstCond4Bits);
        int state = ItState.entryState(normalized, maskFieldFromInstruction);
        List<Condition> sequence = new java.util.ArrayList<>();
        while (ItState.inProgress(state)) {
            sequence.add(ItState.condition(state));
            state = ItState.advance(state);
        }
        return sequence;
    }

    @Test
    void itStateExpansionMatchesSecondaryOracleForAllMasksAndConditions() {
        for (int firstCond = 0; firstCond <= 0xD; firstCond++) { // AL/NV ficam fora (sem 2ª instr. útil)
            for (int mask = 1; mask <= 0xF; mask++) {
                List<Condition> viaItState = itStateExpansion(firstCond, mask);
                List<Condition> viaMaskTable = maskTableExpansion(firstCond, mask);
                assertEquals(viaMaskTable, viaItState,
                        "firstCond=" + firstCond + " mask=" + Integer.toBinaryString(mask));
                assertTrue(viaItState.size() >= 1 && viaItState.size() <= 4);
                assertEquals(Condition.values()[firstCond], viaItState.get(0));
            }
        }
    }

    @Test
    void reservedFirstCondNvIsNormalizedToAlwaysExecutes() {
        // firstcond=0b1111 (NV): CONSTRAINED UNPREDICTABLE, normalizado para AL.
        List<Condition> sequence = itStateExpansion(0xF, 0b0100); // 2 instruções
        assertEquals(List.of(Condition.AL, Condition.AL), sequence);
    }

    // ── Item 2: bloco IT completo, condição base verdadeira/falsa ───────────────────────────

    @Test
    void itBlockSkipsGuardedInstructionWhenConditionFalse() {
        ArmCore core = newCore();
        // IT EQ ; ADDEQ r0, r0, #1  -- Z limpo (condição falsa): ADD não deve executar.
        put16(core, 0x00, it(0x0, 0b1000)); // IT EQ, 1 instrução
        put16(core, 0x02, 0x3001); // ADD r0,#1 (Thumb-1 "ADD Rd,#imm", rd=0)
        core.cpsr().setNzcv(false, false, false, false); // Z=0 -> EQ falso
        core.setRegister(0, 5);
        core.step(); // IT
        core.step(); // ADDEQ (pulado)
        assertEquals(5, core.register(0), "ADDEQ não deveria ter executado com Z=0");
    }

    @Test
    void itBlockExecutesGuardedInstructionWhenConditionTrue() {
        ArmCore core = newCore();
        put16(core, 0x00, it(0x0, 0b1000)); // IT EQ
        put16(core, 0x02, 0x3001); // ADD r0,#1
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro
        core.setRegister(0, 5);
        core.step(); // IT
        core.step(); // ADDEQ
        assertEquals(6, core.register(0), "ADDEQ deveria ter executado com Z=1");
    }

    @Test
    void skippedInstructionInsideItStillConsumesCycleAndFetch() {
        // G4: instrução com condição falsa dentro de IT ainda consome ciclo/fetch — mesma
        // garantia do guard condicional ARM clássico.
        ArmCore core = newCore();
        put16(core, 0x00, it(0x0, 0b1000)); // IT EQ
        put16(core, 0x02, 0x3001); // ADD r0,#1
        core.cpsr().setNzcv(false, false, false, false); // EQ falso
        long before = core.cycles();
        core.step(); // IT
        long afterIt = core.cycles();
        core.step(); // ADDEQ pulado
        long afterSkipped = core.cycles();
        assertTrue(afterIt > before, "IT deve consumir ciclo/fetch");
        assertTrue(afterSkipped > afterIt, "instrução pulada dentro do IT ainda consome ciclo/fetch");
    }

    @Test
    void itBlockDoesNotAffectMemoryWhenSkipped() {
        ArmCore core = newCore();
        core.setRegister(1, 0x100);
        core.setRegister(0, 0xAA);
        put16(core, 0x00, it(0x0, 0b1000)); // IT EQ
        put16(core, 0x02, 0x6008); // STR r0,[r1] (Thumb-1 STR imm5=0)
        core.cpsr().setNzcv(false, false, false, false); // EQ falso
        core.memory().write32(0x100, 0);
        core.step();
        core.step();
        assertEquals(0, core.memory().read32(0x100), "STREQ pulado não deve escrever memória");
    }

    // ── Aceite explícito: o guard `evalCond` por-op do ASM já existe (conditional execution),
    // mas validar que ele funciona com condições vindas de IT (não só do campo `cond` do ARM). ──

    @Test
    void asmBackendMatchesInterpretedForItGuardedAluOps() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, it(0x0, 0b1000)); // IT EQ (1 instrução coberta)
        memory.put16(2, 0x4608); // MOVEQ r0,r1 (hi-register, não seta flags)
        IrBlock block = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder()).lift(memory, 0, 2, 0);

        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        // Guard verdadeiro (Z=1 -> EQ): MOVEQ deve executar nos dois backends.
        harness.assertEquivalent(reference, asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.cpsr().setNzcv(false, true, false, false);
            core.setRegister(1, 0x55);
        }));
        // Guard falso (Z=0 -> EQ falso): MOVEQ deve ser pulado nos dois backends.
        harness.assertEquivalent(reference, asmEmitter, block, EquivalenceTestSupport.independentPair(memory, core -> {
            core.cpsr().setThumbMode(true);
            core.cpsr().setNzcv(false, false, false, false);
            core.setRegister(1, 0x55);
        }));
    }

    // ── Item 3 (D1): mesmo PC lifted com ITSTATE de entrada diferente -> blocos distintos ───

    @Test
    void blockKeyDiffersByItStateWhenNonZero() {
        BlockKey a = new BlockKey(0x1000, InstructionSet.THUMB, 0);
        BlockKey b = new BlockKey(0x1000, InstructionSet.THUMB, it(0x0, 0b1000) & 0xFF);
        assertNotEquals(a, b);
        assertEquals(new BlockKey(0x1000, InstructionSet.THUMB), a); // construtor de compat = itState 0
    }

    @Test
    void sameProgramCounterLiftedWithDifferentItStateProducesDifferentBakedConditions() {
        // Simula o cenário do enunciado: um PC no MEIO de um IT block real, visitado com dois
        // ITSTATE de entrada diferentes (ex.: dois branches externos apontando pro mesmo PC em
        // momentos distintos de dois IT blocks diferentes — CONSTRAINED UNPREDICTABLE, mas deve
        // produzir blocos corretos e DISTINTOS, não um sobrescrevendo o outro incorretamente).
        TestAddressSpace memory = new TestAddressSpace(4096);
        memory.put16(0x10, 0x3001); // ADD r0,#1 -- decodifica sempre com condition=AL "natural"
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(
                new ThumbDecoder(THUMB2_ARCH), new StandardIrBuilder());

        int itStateEq = it(0x0, 0b1000) & 0xFF; // EQ, 1 instrução (já consumida antes de 0x10:
        // aqui simulamos que 0x10 é a ÚLTIMA instrução do IT, então o ITSTATE de ENTRADA nele
        // ainda é o valor cru — o que importa para o teste é que a condição baked difira.
        int itStateNe = it(0x1, 0b1000) & 0xFF; // NE, 1 instrução

        IrBlock blockEq = lifter.lift(memory, 0x10, 4, itStateEq);
        IrBlock blockNe = lifter.lift(memory, 0x10, 4, itStateNe);

        Condition condEq = firstAluCondition(blockEq);
        Condition condNe = firstAluCondition(blockNe);
        assertEquals(Condition.EQ, condEq);
        assertEquals(Condition.NE, condNe);
        assertNotEquals(condEq, condNe);

        // As duas entradas, cacheadas por BlockKey com itState, coexistem sem colisão.
        BlockCache cache = new BlockCache(8);
        BlockKey keyEq = new BlockKey(0x10, InstructionSet.THUMB, itStateEq);
        BlockKey keyNe = new BlockKey(0x10, InstructionSet.THUMB, itStateNe);
        CompiledBlock compiledEq = c -> 1;
        CompiledBlock compiledNe = c -> 2;
        cache.put(keyEq, compiledEq, blockEq.startPc(), blockEq.endPc());
        cache.put(keyNe, compiledNe, blockNe.startPc(), blockNe.endPc());
        assertEquals(compiledEq, cache.getOrNull(keyEq));
        assertEquals(compiledNe, cache.getOrNull(keyNe));
        assertNotEquals(cache.getOrNull(keyEq), cache.getOrNull(keyNe));
    }

    private static Condition firstAluCondition(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (op instanceof IrOp.Alu alu) {
                return alu.condition();
            }
        }
        throw new AssertionError("no Alu op found");
    }

    // ── Item 4: exceção no meio de um IT block ──────────────────────────────────────────────

    @Test
    void exceptionMidItBlockSavesAndRestoresItStateViaSpsr() {
        ArmCore core = newCore();
        // ITT EQ: 2 instruções cobertas, ambas EQ (mask=0100). A 1ª usa MOV Rd,Rs (forma
        // hi-register, 0x4400) em vez de ADD/#imm: no Thumb-1 as formas com imediato SEMPRE
        // setam flags, o que clobbraria o Z usado pelo guard EQ da 2ª instrução (SWI) — MOV
        // hi-register não afeta NZCV.
        put16(core, 0x00, it(0x0, 0b0100));
        put16(core, 0x02, 0x4608); // MOV r0,r1 (1ª instrução coberta, EQ) -- não toca flags
        put16(core, 0x04, 0xDF00); // SWI 0 (2ª instrução coberta, EQ) -- termina o bloco
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro
        core.setRegister(1, 0x77);
        core.step(); // IT
        int itStateAfterIt = core.cpsr().itState();
        assertTrue(ItState.inProgress(itStateAfterIt));
        core.step(); // MOVEQ r0,r1 (1ª instrução): ITSTATE avança
        assertEquals(0x77, core.register(0), "MOVEQ deveria ter executado (Z=1) sem afetar flags");
        int itStateBeforeSwi = core.cpsr().itState();
        assertEquals(Condition.EQ, ItState.condition(itStateBeforeSwi));
        core.step(); // SWI (2ª instrução): dispara exceção -- SPSR(SVC) deve capturar o CPSR
        // (incl. ITSTATE) tal como estava ANTES do SWI se retirar, exatamente o que
        // `ArmCore#enterException` já faz (SPSR = CPSR completo no momento da entrada).
        int spsr = core.spsr(CpuMode.SUPERVISOR);
        int itStateInSpsr = ((spsr >>> 10) & 0x3F) << 2 | ((spsr >>> 25) & 0x3);
        assertEquals(itStateBeforeSwi, itStateInSpsr);
        // "Retorno": restaurar CPSR a partir do SPSR reproduz o mesmo ITSTATE.
        core.setCpsr(spsr);
        assertEquals(itStateBeforeSwi, core.cpsr().itState());
    }

    // ── Item 5: B.W (T3/T4) — alcance maior que o B de 16 bits ──────────────────────────────

    @Test
    void conditionalBranchWideTakesShortPositiveOffset() {
        ArmCore core = newCore();
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro
        int[] enc = branchWideT3(0x0, 8); // EQ, target = pc+4+8
        put16(core, 0x00, enc[0]);
        put16(core, 0x02, enc[1]);
        core.step();
        assertEquals(0x00 + 4 + 8, core.programCounter());
    }

    @Test
    void conditionalBranchWideNotTakenFallsThrough() {
        ArmCore core = newCore();
        core.cpsr().setNzcv(false, false, false, false); // Z=0 -> EQ falso
        int[] enc = branchWideT3(0x0, 8);
        put16(core, 0x00, enc[0]);
        put16(core, 0x02, enc[1]);
        core.step();
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void unconditionalBranchWideReachesRangeBeyondSixteenBitEncoding() {
        ArmCore core = newCore();
        int offset = 100_000; // muito além de ±2048 (alcance de B de 16 bits, T2)
        int[] enc = branchWideT4(offset);
        put16(core, 0x00, enc[0]);
        put16(core, 0x02, enc[1]);
        core.step();
        assertEquals(0x00 + 4 + offset, core.programCounter());
    }

    @Test
    void unconditionalBranchWideReachesNegativeRange() {
        ArmCore core = new ArmCore(new TestAddressSpace(300_000), SwiDispatcher.empty(), THUMB2_ARCH);
        core.cpsr().setThumbMode(true);
        core.setProgramCounter(200_000);
        int offset = -150_000;
        int[] enc = branchWideT4(offset);
        ((TestAddressSpace) core.memory()).put16(200_000, enc[0]);
        ((TestAddressSpace) core.memory()).put16(200_002, enc[1]);
        core.step();
        assertEquals(200_000 + 4 + offset, core.programCounter());
    }

    // ── D2 de B2.4 REVOGADA por B2.6 sob THUMB2: BL/BLX vira decode único de 32 bits em vez de
    // reaproveitar o caminho legado de dois halfwords — ver b2.6-thumb2-preset-fechamento.md. ──

    @Test
    void blThumb2DecodesAsSingleInstructionAndProducesSameFinalStateAsLegacyPair() {
        ArmCore core = newCore();
        // BL de +4 (prefixo highOffset=0, sufixo lowOffset=4): sob THUMB2, um ÚNICO core.step()
        // decodifica e executa o par inteiro (B2.6) — não dois.
        put16(core, 0x00, 0xF000); // prefixo: highOffset=0
        put16(core, 0x02, 0xF802); // sufixo BL: lowOffset = 2<<1=4
        core.step();
        assertEquals(0x00 + 4 + 4, core.programCounter());
        assertEquals((0x02 + 2) | 1, core.register(14)); // LR = endereço de retorno com bit0=1
    }

    // ── Item CBZ/CBNZ ────────────────────────────────────────────────────────────────────────

    @Test
    void cbzBranchesWhenRegisterIsZeroWithoutTouchingFlags() {
        ArmCore core = newCore();
        core.setRegister(0, 0);
        core.cpsr().setNzcv(true, true, true, true); // marca flags para provar que não mudam
        int flagsBefore = core.cpsr().get() & 0xF000_0000;
        put16(core, 0x00, cbz(false, 10, 0)); // CBZ r0, +10
        core.step();
        assertEquals(0x00 + 4 + 10, core.programCounter());
        assertEquals(flagsBefore, core.cpsr().get() & 0xF000_0000);
    }

    @Test
    void cbzDoesNotBranchWhenRegisterIsNonZero() {
        ArmCore core = newCore();
        core.setRegister(0, 1);
        put16(core, 0x00, cbz(false, 10, 0));
        core.step();
        assertEquals(0x02, core.programCounter());
    }

    @Test
    void cbnzBranchesWhenRegisterIsNonZero() {
        ArmCore core = newCore();
        core.setRegister(1, 42);
        put16(core, 0x00, cbz(true, 20, 1)); // CBNZ r1, +20
        core.step();
        assertEquals(0x00 + 4 + 20, core.programCounter());
    }

    @Test
    void cbnzDoesNotBranchWhenRegisterIsZero() {
        ArmCore core = newCore();
        core.setRegister(1, 0);
        put16(core, 0x00, cbz(true, 20, 1));
        core.step();
        assertEquals(0x02, core.programCounter());
    }

    // ── TBB/TBH ──────────────────────────────────────────────────────────────────────────────

    @Test
    void tbbReadsByteTableAndBranchesWithoutExposingTheValueInAnyRegister() {
        ArmCore core = newCore();
        int instrAddress = 0x00;
        int[] enc = tableBranch(0, 1, false); // TBB [r0, r1]
        put16(core, instrAddress, enc[0]);
        put16(core, instrAddress + 2, enc[1]);
        int pcAfterInstr = instrAddress + 4; // base da tabela (Rn=r0 aponta pra cá)
        core.setRegister(0, pcAfterInstr);
        core.setRegister(1, 2); // índice 2
        core.memory().write8(pcAfterInstr, 0);
        core.memory().write8(pcAfterInstr + 1, 1);
        core.memory().write8(pcAfterInstr + 2, 5); // entrada usada: valor 5
        int[] registersBefore = core.registersSnapshot();
        core.step();
        assertEquals(pcAfterInstr + 2 * 5, core.programCounter());
        int[] registersAfter = core.registersSnapshot();
        for (int i = 0; i < 15; i++) {
            if (i == 0 || i == 1) continue; // base/índice, não tocados por TBB
            assertEquals(registersBefore[i], registersAfter[i], "TBB não deve escrever registrador " + i);
        }
    }

    @Test
    void tbhUsesDoubledOffsetForHalfwordEntries() {
        ArmCore core = newCore();
        int instrAddress = 0x00;
        int[] enc = tableBranch(0, 1, true); // TBH [r0, r1, LSL #1]
        put16(core, instrAddress, enc[0]);
        put16(core, instrAddress + 2, enc[1]);
        int pcAfterInstr = instrAddress + 4;
        core.setRegister(0, pcAfterInstr);
        core.setRegister(1, 1); // índice 1 -> lê halfword em pcAfterInstr+2
        core.memory().write16(pcAfterInstr, 0);
        core.memory().write16(pcAfterInstr + 2, 7);
        core.step();
        assertEquals(pcAfterInstr + 2 * 7, core.programCounter());
    }

    // ── G2: preset sem THUMB2 preserva UNDEFINED ────────────────────────────────────────────

    @Test
    void itHintOpcodeUndefinedWithoutThumb2() {
        ArmCore core = new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), ArmArchitecture.ARMV4T);
        core.cpsr().setThumbMode(true);
        put16(core, 0x00, it(0x0, 0b1000));
        core.setInterruptLine(false);
        core.step();
        // Sem THUMB2 o opcode 0xBF08 cai no UNDEFINED controlado (dispara exceção) — o PC deve
        // ter ido para o vetor 0x04, não ter avançado normalmente para 0x02.
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void itDoesNotLeakItStateBitsIntoArmv6kWithoutThumb2() {
        // Regressão explícita (não só grep): ARMV6K (sem THUMB2) não decodifica IT, e os bits do
        // CPSR usados por ITSTATE continuam disponíveis para o resto do CPSR sem interferência
        // especial nesse preset (round-trip simples de set/get).
        ArmCore core = new ArmCore(new TestAddressSpace(4096), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.cpsr().setItState(0xAB); // manipulação direta do registrador, não via decode
        assertEquals(0xAB, core.cpsr().itState());
        core.cpsr().setNzcv(true, true, true, true);
        assertEquals(0xAB, core.cpsr().itState(), "NZCV não deve pisar no ITSTATE (bits distintos)");
    }

    // ── Armadilhas: as 3 CONSTRAINED UNPREDICTABLE do IT block "continuam normalmente" (não
    // viram UNDEFINED) — decisão explícita desta task, seguindo o precedente do QEMU. ──────────

    @Test
    void conditionalBranchAsNonLastInstructionOfItBlockContinuesNormallyInsteadOfUndefined() {
        // CONSTRAINED UNPREDICTABLE (branch que não é a última instrução de um IT block): a
        // decisão desta task é continuar normalmente. O branch (aqui: TBB, sempre terminal) some
        // não-último-em-teoria; como qualquer branch já é terminal em isTerminal(), o lift termina
        // ali mesmo — o resto do IT block (se houvesse) resumiria no PRÓXIMO lift, guiado pelo
        // ITSTATE já persistido no CPSR (D1). Aqui validamos que a condição do branch em si é
        // corretamente herdada do IT (não gera UNDEFINED nem ignora o guard).
        ArmCore core = newCore();
        put16(core, 0x00, it(0x0, 0b0100)); // ITT EQ EQ (2 instruções cobertas)
        int[] tbb = tableBranch(1, 2, false); // TBB [r1,r2] -- 1ª instrução coberta (NÃO é a última do IT)
        put16(core, 0x02, tbb[0]);
        put16(core, 0x04, tbb[1]);
        core.cpsr().setNzcv(false, false, false, false); // Z=0 -> EQ falso: TBB não deve executar
        int pcAfterTbb = 0x02 + 4;
        core.setRegister(1, pcAfterTbb);
        core.setRegister(2, 0);
        core.memory().write8(pcAfterTbb, 9); // se o TBB executasse, desviaria para pcAfterTbb+18
        core.step(); // IT
        core.step(); // TBB pulado (EQ falso) -- não deve lançar UNDEFINED nem desviar
        assertEquals(0x06, core.programCounter(), "TBBEQ com guard falso deve só avançar, sem desviar");
    }

    @Test
    void nestedItInheritsOuterConditionAndContinuesNormallyInsteadOfUndefined() {
        // CONSTRAINED UNPREDICTABLE (IT dentro de outro IT block): decisão desta task é herdar a
        // condição do bloco externo, como qualquer outra instrução coberta (ver
        // StandardIrBlockLifter#lift) -- não UNDEFINED.
        ArmCore core = newCore();
        put16(core, 0x00, it(0x0, 0b1000)); // IT EQ (externo, 1 instrução coberta: o IT interno)
        put16(core, 0x02, it(0x1, 0b1000)); // IT NE (interno, aninhado) -- coberto pelo EQ externo
        put16(core, 0x04, 0x4608); // MOV r0,r1 -- coberto pelo IT interno (NE)
        core.cpsr().setNzcv(false, true, false, false); // Z=1: EQ verdadeiro (externo), NE falso (interno)
        core.setRegister(1, 0x99);
        core.step(); // IT EQ (externo)
        core.step(); // IT NE (interno): guard EQ verdadeiro -> executa, abre um NOVO IT block (NE)
        assertTrue(ItState.inProgress(core.cpsr().itState()), "IT interno deveria ter aberto um novo IT block");
        assertEquals(Condition.NE, ItState.condition(core.cpsr().itState()));
        core.step(); // MOVNE r0,r1: guard NE falso (Z=1) -> pulado
        assertEquals(0, core.register(0), "MOVNE não deveria ter executado (Z=1 -> NE falso)");
    }
}
