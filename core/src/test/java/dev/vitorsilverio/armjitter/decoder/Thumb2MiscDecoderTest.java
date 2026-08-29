package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

/// B2.5 — misc Thumb-2: hints (`NOP`/`YIELD`/`WFE`/`WFI`/`SEV`) nas formas de 16 e 32 bits,
/// barreiras de memória (`DMB`/`DSB`/`ISB`, `ArmFeature#MEMORY_BARRIERS` novo) e `MRS`/`MSR` de 32
/// bits. Ver `Thumb2MiscDecoder` para o layout de bits (confirmado contra o QEMU
/// `target/arm/tcg/t32.decode` e `t16.decode`).
class Thumb2MiscDecoderTest {
    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.extending(
                    ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-Misc", ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS)
            .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                    ArmArchitecture.extending(ArmArchitecture.ARMV6K, "ARMv7-TestThumb2-Misc-Inner",
                            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS))));

    // ── Encodings de 32 bits (ver Thumb2MiscDecoder) ────────────────────────────────────────

    private static final int HINTS_HI = 0xF3AF;

    private static int hintLo(int selector) {
        return 0x8000 | selector;
    }

    private static final int MISC_CONTROL_HI = 0xF3BF;

    private static int barrierLo(int op, int option) {
        return 0x8F00 | (op << 4) | option;
    }

    private static int mrsHi(boolean spsr) {
        return spsr ? 0xF3FF : 0xF3EF;
    }

    private static int mrsLo(int rd) {
        return 0x8000 | (rd << 8);
    }

    private static int msrHi(boolean spsr, int rn) {
        return (spsr ? 0xF39 : 0xF38) << 4 | rn;
    }

    private static int msrLo(int fieldMask) {
        return 0x8000 | (fieldMask << 8);
    }

    // ── CLREX de 32 bits (B2.7 PR3) — encoding fixo, mesmo hi/top-byte das barreiras ────────

    private static final int CLREX_LO = 0x8F2F;

    // ── CPS de 32 bits (B2.7 PR3) — mesmo hi de HINTS_HI, imod/M != 0 ───────────────────────

    private static int cpsLo(int imod, boolean modeChange, boolean a, boolean i, boolean f, int mode) {
        return 0x8000 | (imod << 9) | ((modeChange ? 1 : 0) << 8)
                | ((a ? 1 : 0) << 7) | ((i ? 1 : 0) << 6) | ((f ? 1 : 0) << 5) | (mode & 0x1F);
    }

    /// `CPS{IE,ID} <iflags>{,#<mode>}` ARM clássico (cond=1111, forçado AL) — MESMO layout de
    /// `ArmV6SystemInstructionsTest#cps`, usado aqui só para o "ida-e-volta" contra a forma
    /// Thumb-2.
    private static int armCps(int imod, boolean modeChange, boolean a, boolean i, boolean f, int mode) {
        return 0xF100_0000
                | (imod << 18)
                | (modeChange ? 1 << 17 : 0)
                | (a ? 1 << 8 : 0)
                | (i ? 1 << 7 : 0)
                | (f ? 1 << 6 : 0)
                | (mode & 0x1F);
    }

    // ── Encoding de 16 bits (hint space, ver ThumbDecoder) ──────────────────────────────────

    private static int hint16(int selector) {
        return 0xBF00 | (selector << 4);
    }

    private static ArmCore newCore(ArmArchitecture architecture) {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), architecture);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void run32(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    private static void run16(ArmCore core, int raw) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        memory.put16(core.programCounter(), raw);
        core.step();
    }

    // ── Hints de 32 bits: YIELD/WFE/SEV/NOP não têm efeito observável ───────────────────────

    @Test
    void yieldWfeSevAndNopHave32BitFormsWithNoObservableEffect() {
        for (int selector : new int[] {0x00, 0x01, 0x02, 0x04, 0x7F}) { // NOP, YIELD, WFE, SEV, reservado
            ArmCore core = newCore(THUMB2_ARCH);
            core.setRegister(0, 0x1234_5678);
            core.cpsr().setNzcv(true, true, true, true);
            int cpsrBefore = core.cpsr().get();
            run32(core, HINTS_HI, hintLo(selector));
            assertEquals(0x1234_5678, core.register(0), "seletor " + selector + " não deve tocar registradores");
            assertEquals(cpsrBefore, core.cpsr().get(), "seletor " + selector + " não deve tocar CPSR");
            assertEquals(CpuSleepState.RUNNING, core.sleepState());
        }
    }

    @Test
    void wfi32BitFormHaltsTheCoreLikeArmClassic() {
        ArmCore core = newCore(THUMB2_ARCH);
        run32(core, HINTS_HI, hintLo(0x03));
        assertEquals(CpuSleepState.HALTED, core.sleepState());
    }

    @Test
    void wfi32BitFormIsUndefinedWithoutWaitHintsFeature() {
        ArmArchitecture withoutWaitHints = ArmArchitecture.extending(
                        ArmArchitecture.ARMV5TE, "NoWaitHints", ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS)
                .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoWaitHints-Inner",
                                ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HINTS_HI);
        memory.put16(2, hintLo(0x03));
        DecodedInstruction instruction = new ThumbDecoder(withoutWaitHints).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── Hints de 16 bits: mesmo efeito observável que a forma de 32 bits ────────────────────

    @Test
    void yieldWfeSevAndNop16BitFormsHaveNoObservableEffect() {
        for (int selector : new int[] {0x00, 0x01, 0x02, 0x04, 0x7}) {
            ArmCore core = newCore(THUMB2_ARCH);
            core.setRegister(0, 0x1234_5678);
            core.cpsr().setNzcv(true, true, true, true);
            int cpsrBefore = core.cpsr().get();
            run16(core, hint16(selector));
            assertEquals(0x1234_5678, core.register(0));
            assertEquals(cpsrBefore, core.cpsr().get());
            assertEquals(CpuSleepState.RUNNING, core.sleepState());
        }
    }

    @Test
    void wfi16BitFormHaltsTheCore() {
        ArmCore core = newCore(THUMB2_ARCH);
        run16(core, hint16(0x03));
        assertEquals(CpuSleepState.HALTED, core.sleepState());
    }

    @Test
    void itBlockMaskIsNowHandledByB24() {
        // mask != 0000 no mesmo opcode de 16 bits: IT block — fora do escopo de B2.5 (ver
        // Thumb2MiscDecoderTest/ThumbDecoder), mas implementado pela task B2.4 no mesmo
        // ThumbDecoder (não é responsabilidade de Thumb2MiscDecoder).
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hint16(0x0) | 0x8); // firstcond=0000, mask=1000 (!=0) -> "IT"
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.IT, instruction.kind());
    }

    // ── DMB/DSB/ISB: nenhum efeito observável além de consumir ciclo/fetch (G4) ─────────────

    @Test
    void dmbDsbIsbHaveNoObservableEffectBeyondCycleAndFetch() {
        for (int op : new int[] {0x4, 0x5, 0x6}) { // DSB, DMB, ISB
            ArmCore core = newCore(THUMB2_ARCH);
            core.setRegister(0, 0xCAFE_BABE);
            core.memory().write32(0x40, 0xDEAD_BEEF);
            core.cpsr().setNzcv(true, false, true, false);
            int cpsrBefore = core.cpsr().get();
            run32(core, MISC_CONTROL_HI, barrierLo(op, 0xF));
            assertEquals(0xCAFE_BABE, core.register(0));
            assertEquals(0xDEAD_BEEF, core.memory().read32(0x40));
            assertEquals(cpsrBefore, core.cpsr().get());
        }
    }

    @Test
    void dmbDsbIsbAreUndefinedWithoutMemoryBarriersFeature() {
        ArmArchitecture withoutBarriers = ArmArchitecture.extending(
                        ArmArchitecture.ARMV6K, "NoBarriers", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV6K, "NoBarriers-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, MISC_CONTROL_HI);
        memory.put16(2, barrierLo(0x5, 0xF));
        DecodedInstruction instruction = new ThumbDecoder(withoutBarriers).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── CLREX de 32 bits: abre o monitor, mesmo efeito que o ARM clássico ──────────────────

    @Test
    void clrex32BitFormOpensTheMonitorSoStrexFailsAfterward() {
        ArmCore core = newCore(THUMB2_ARCH); // ARMV6K -> EXCLUSIVE_SIZED herdado
        core.setRegister(0, 0x40);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x40, 0x11111111);
        // LDREX r1,[r0] no espaço de Thumb2LoadStoreDecoder não está plugado aqui (esta classe
        // testa só Thumb2MiscDecoder), então marca o monitor direto via ArmCore para isolar o
        // efeito do CLREX.
        core.markExclusive(0x40L, 4);
        run32(core, MISC_CONTROL_HI, CLREX_LO);
        assertFalse(core.exclusiveMonitorCovers(0x40L, 4), "CLREX.W deve abrir o monitor");
    }

    @Test
    void clrex32BitFormIsUndefinedWithoutExclusiveSizedFeature() {
        ArmArchitecture withoutExclusive = ArmArchitecture.extending(
                        ArmArchitecture.ARMV5TE, "NoExclusiveSized", ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS)
                .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoExclusiveSized-Inner",
                                ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, MISC_CONTROL_HI);
        memory.put16(2, CLREX_LO);
        DecodedInstruction instruction = new ThumbDecoder(withoutExclusive).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── CPS de 32 bits: ida-e-volta comparado com o ARM clássico ────────────────────────────

    @Test
    void cpsThumb2ChangingIandFMatchesArmClassic() {
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.cpsr().setMode(CpuMode.SYSTEM);
        run32(thumb2Core, HINTS_HI, cpsLo(0b11 /* imod=11 -> ID (disable) */, false, false, true, true, 0));

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.cpsr().setMode(CpuMode.SYSTEM);
        armCore.memory().write32(0, armCps(0b11, false, false, true, true, 0)); // CPSID if
        armCore.step();

        assertEquals(armCore.cpsr().get() & ~CpsrRegister.THUMB_FLAG,
                thumb2Core.cpsr().get() & ~CpsrRegister.THUMB_FLAG);
        assertTrue(thumb2Core.cpsr().irqDisabled());
        assertTrue(thumb2Core.cpsr().fiqDisabled());
    }

    @Test
    void cpsThumb2ChangingModeMatchesArmClassic() {
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.cpsr().setMode(CpuMode.SYSTEM);
        run32(thumb2Core, HINTS_HI, cpsLo(0, true, false, false, false, CpuMode.IRQ.bits()));

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.cpsr().setMode(CpuMode.SYSTEM);
        armCore.memory().write32(0, armCps(0, true, false, false, false, CpuMode.IRQ.bits())); // CPS #IRQ
        armCore.step();

        assertEquals(armCore.cpsr().mode(), thumb2Core.cpsr().mode());
        assertEquals(CpuMode.IRQ, thumb2Core.cpsr().mode());
    }

    @Test
    void cps32BitFormIsUndefinedWithoutModeChangeInstructionsFeature() {
        ArmArchitecture withoutModeChange = ArmArchitecture.extending(
                        ArmArchitecture.ARMV5TE, "NoModeChange", ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS)
                .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "NoModeChange-Inner",
                                ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HINTS_HI);
        memory.put16(2, cpsLo(0b10, false, false, true, true, 0));
        DecodedInstruction instruction = new ThumbDecoder(withoutModeChange).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    // ── MRS/MSR: ida-e-volta comparado byte a byte com o ARM clássico ───────────────────────

    @Test
    void mrsThumb2MatchesArmClassicForCpsr() {
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.cpsr().setNzcv(true, false, true, false);
        run32(thumb2Core, mrsHi(false), mrsLo(3)); // MRS r3, CPSR

        // O core de referência tem que ficar em ARM (T=0) para decodificar a instrução ARM
        // clássica abaixo (setar T=1 nele faria o ArmCore buscar via ThumbDecoder, quebrando o
        // fetch). O bit T é, por definição, o único bit que pode legitimamente divergir entre os
        // dois cores aqui (um executa THUMB, o outro ARM) — mascarado na comparação abaixo.
        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.cpsr().setNzcv(true, false, true, false);
        armCore.memory().write32(0, 0xE10F_3000); // MRS r3, CPSR
        armCore.step();

        assertEquals(armCore.register(3) & ~CpsrRegister.THUMB_FLAG, thumb2Core.register(3) & ~CpsrRegister.THUMB_FLAG);
    }

    @Test
    void mrsThumb2MatchesArmClassicForSpsr() {
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.cpsr().setMode(CpuMode.IRQ);
        thumb2Core.setSpsr(CpuMode.IRQ, 0x0000_00D3);
        run32(thumb2Core, mrsHi(true), mrsLo(5)); // MRS r5, SPSR

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.cpsr().setMode(CpuMode.IRQ);
        armCore.setSpsr(CpuMode.IRQ, 0x0000_00D3);
        armCore.memory().write32(0, 0xE14F_5000); // MRS r5, SPSR
        armCore.step();

        assertEquals(armCore.register(5), thumb2Core.register(5));
    }

    @Test
    void msrThumb2MatchesArmClassicRoundTripThroughCpsr() {
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.setRegister(2, 0xF000_00D3); // NZCV + modo SYSTEM
        run32(thumb2Core, msrHi(false, 2), msrLo(0b1001)); // MSR CPSR_fc, r2

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(2, 0xF000_00D3);
        armCore.memory().write32(0, 0xE129_F002); // MSR CPSR_fc, r2
        armCore.step();

        assertEquals(armCore.cpsr().get(), thumb2Core.cpsr().get());
    }

    @Test
    void msrThumb2RespectsFieldMaskLikeArmClassic() {
        // MSR CPSR_f (só o campo de flags, mask=1000): escreve NZCV, preserva controle (T/mode/I/F).
        // Comparado com o ARM clássico via mergePsr/cpsrWriteFieldMask compartilhados (mesmo
        // IrOp.PsrTransfer) na task msrThumb2MatchesArmClassicRoundTripThroughCpsr acima (mask=fc,
        // ida-e-volta completa) — aqui o foco é só a preservação seletiva do campo, direto no
        // mesmo core, para não misturar o estado inicial de dois cores diferentes.
        ArmCore core = newCore(THUMB2_ARCH);
        int before = core.cpsr().get();
        core.setRegister(2, 0xF000_0000);
        run32(core, msrHi(false, 2), msrLo(0b1000)); // MSR CPSR_f, r2

        assertTrue(core.cpsr().negative());
        assertTrue(core.cpsr().zero());
        assertTrue(core.cpsr().carry());
        assertTrue(core.cpsr().overflow());
        // Bits de controle (T/mode/I/F) do CPSR original preservados (mask=1000 não cobre `c`).
        assertEquals(before & 0xFF, core.cpsr().get() & 0xFF);
    }

    // ── B9.7: BXJ/UDF.W/SUBS PC,LR,#imm (alias T5 de exception return) ──────────────────────

    private static int bxjHi(int rm) {
        return 0xF3C0 | rm;
    }

    private static final int BXJ_LO = 0x8F00;
    private static final int UDF_HI = 0xF7F0;
    private static final int UDF_LO = 0xA000;
    private static final int EXCEPTION_RETURN_SUB_HI = 0xF3DE;

    private static int exceptionReturnSubLo(int imm8) {
        return 0x8F00 | (imm8 & 0xFF);
    }

    @Test
    void bxjMatchesArmClassicBxSameRegister() {
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.setRegister(3, 0x2001); // bit0=1 -> permanece THUMB
        run32(thumb2Core, bxjHi(3), BXJ_LO); // BXJ r3

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.setRegister(3, 0x2001);
        armCore.memory().write32(0, 0xE12F_FF13); // BX r3
        armCore.step();

        assertEquals(armCore.programCounter(), thumb2Core.programCounter());
        assertEquals(armCore.cpsr().isThumbMode(), thumb2Core.cpsr().isThumbMode());
        assertEquals(0x2000, thumb2Core.programCounter());
    }

    @Test
    void bxjIsUndefinedUnderMProfile() {
        ArmArchitecture mProfile = ArmArchitecture.extending(
                        ArmArchitecture.ARMV6M, "BxjMProfile", ArmFeature.THUMB2)
                .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV6M, "BxjMProfile-Inner", ArmFeature.THUMB2))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, bxjHi(3));
        memory.put16(2, BXJ_LO);
        DecodedInstruction instruction = new ThumbDecoder(mProfile).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.BRANCH_EXCHANGE, instruction.kind());
    }

    @Test
    void udfWDecodesAsUdfRegardlessOfIgnoredBits() {
        // `hi` nibble baixo e `lo` bits 11:0 são ignorados pelo hardware real (QEMU `t32.decode`:
        // `----`) — duas variantes com esses bits diferentes têm que decodificar igual.
        int[][] variants = {{UDF_HI, UDF_LO}, {UDF_HI | 0x3, UDF_LO | 0x3F1}};
        for (int[] variant : variants) {
            TestAddressSpace memory = new TestAddressSpace(16);
            memory.put16(0, variant[0]);
            memory.put16(2, variant[1]);
            DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
            assertEquals(InstructionKind.UDF, instruction.kind());
        }
    }

    @Test
    void subRriExceptionReturnAliasMatchesArmClassicInPrivilegedMode() {
        // SUBS PC,LR,#4 executada em modo IRQ (com SPSR válido) restaura CPSR<-SPSR e PC<-LR-4 —
        // mesmo caminho genérico de `IrAluExecutor#executeAlu` (`Rd==PC && setFlags`) que
        // `MOVS PC,LR` ARM clássico já usa (G1, nenhuma IR nova).
        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        thumb2Core.switchMode(CpuMode.IRQ);
        thumb2Core.setRegister(14, 0x1008);
        thumb2Core.setSpsr(CpuMode.IRQ, 0x6000_001F); // NZCV=0110, modo SYSTEM (0x1F)
        run32(thumb2Core, EXCEPTION_RETURN_SUB_HI, exceptionReturnSubLo(4)); // SUBS PC,LR,#4

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        armCore.switchMode(CpuMode.IRQ);
        armCore.setRegister(14, 0x1008);
        armCore.setSpsr(CpuMode.IRQ, 0x6000_001F);
        armCore.memory().write32(0, 0xE25E_F004); // SUBS PC, LR, #4

        armCore.step();

        assertEquals(armCore.programCounter(), thumb2Core.programCounter());
        assertEquals(armCore.mode(), thumb2Core.mode());
        assertEquals(CpuMode.SYSTEM, thumb2Core.mode());
        assertEquals(0x1004, thumb2Core.programCounter());
    }

    // ── B9.11 (achado colateral da B9.10): ARMv6-M não tem hints/CPS/UDF largos nem o alias de
    // exception-return — só ARMv7-M tem (ArmFeature#M_PROFILE_WIDE_MISC_CONTROL) ─────────────

    @Test
    void wideHintDecodesUnderArmV7MButNotUnderArmV6M() {
        // WFI.W (seletor 0b011 dentro do subgrupo "Hints, and CPS").
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HINTS_HI);
        memory.put16(2, hintLo(0b011));

        assertEquals(InstructionKind.WAIT_FOR_INTERRUPT,
                new ThumbDecoder(ArmArchitecture.ARMV7M).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV6M).decode(memory, 0).kind());
    }

    @Test
    void reservedHintNopWDecodesUnderArmV7MButNotUnderArmV6M() {
        // Seletor reservado (0xFF) — "reserved hint, behaves as nop" no hardware real.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HINTS_HI);
        memory.put16(2, hintLo(0xFF));

        assertNotEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV7M).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV6M).decode(memory, 0).kind());
    }

    @Test
    void cpsWIsUndefinedUnderBothMProfilePresets() {
        // Diferente de WFI.W/hints reservados/UDF.W, `CPS.W` (forma A/R de 32 bits com
        // imod/mode) fica UNIMPLEMENTED nos DOIS presets M-profile: nenhum dos dois habilita
        // ArmFeature#MODE_CHANGE_INSTRUCTIONS (decodeCps32 exige) — o perfil M só tem o `CPS`
        // 16-bit T1 (`imod iflags`, sem campo `mode`), mesmo caso do alias de exception-return.
        // O ✅ que a medição de cobertura mostrava para v6-M/v7-M antes desta task era um
        // artefato do PROBE: um encoding de teste com imod=0/M=0 cai no ramo "reserved hint"
        // (agora corretamente bloqueado para v6-M pelo gate de decodeHintsOrCps), não em
        // decodeCps32 de verdade.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HINTS_HI);
        memory.put16(2, cpsLo(0b11 /* imod=11 -> ID */, false, false, true, true, 0));

        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV6M).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV7M).decode(memory, 0).kind());
    }

    @Test
    void udfWDecodesUnderArmV7MButNotUnderArmV6M() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, UDF_HI);
        memory.put16(2, UDF_LO);

        assertEquals(InstructionKind.UDF,
                new ThumbDecoder(ArmArchitecture.ARMV7M).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV6M).decode(memory, 0).kind());
    }

    @Test
    void subRriExceptionReturnAliasIsUndefinedUnderBothMProfilePresets() {
        // Diferente de hints/CPS/UDF, o alias T5 de exception-return não existe em NENHUM perfil
        // M (v6-M ou v7-M) — não tem gate por ArmFeature#M_PROFILE_WIDE_MISC_CONTROL, é rejeitado
        // por ArmFeature#M_PROFILE puro (mesma categoria de bxjIsUndefinedUnderMProfile).
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, EXCEPTION_RETURN_SUB_HI);
        memory.put16(2, exceptionReturnSubLo(4));

        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV6M).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV7M).decode(memory, 0).kind());
    }

    // ── B9.8.2: HVC.W ────────────────────────────────────────────────────────────────────

    private static final int HVC_HI = 0xF7E1;
    private static final int HVC_LO = 0x8234;

    @Test
    void hvcWDecodesImm16WithHypervisorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HVC_HI); // HVC.W #0x1234 (encoding real, arm-none-eabi-as -march=armv7ve)
        memory.put16(2, HVC_LO);

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);

        assertEquals(InstructionKind.HVC, instruction.kind());
        assertEquals(0x1234, instruction.immediate());
    }

    @Test
    void hvcWIsUnimplementedWithoutHypervisorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HVC_HI);
        memory.put16(2, HVC_LO);

        // THUMB2_ARCH tem THUMB2/MEMORY_BARRIERS mas não HYPERVISOR_CALL.
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.HVC, instruction.kind());
    }

    @Test
    void hvcWEntersHypModeMatchingArmClassic() {
        ArmCore thumb2Core = newCore(ArmArchitecture.ARMV7A);
        thumb2Core.switchMode(CpuMode.SYSTEM);
        thumb2Core.setRegister(14, 0xCAFE);
        thumb2Core.switchMode(CpuMode.SUPERVISOR);
        run32(thumb2Core, HVC_HI, HVC_LO); // HVC.W #0x1234

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        armCore.switchMode(CpuMode.SYSTEM);
        armCore.setRegister(14, 0xCAFE);
        armCore.switchMode(CpuMode.SUPERVISOR);
        armCore.memory().write32(0, 0xE141_2374); // HVC #0x1234
        armCore.step();

        assertEquals(armCore.mode(), thumb2Core.mode());
        assertEquals(CpuMode.HYP, thumb2Core.mode());
        assertEquals(armCore.elrHyp(), thumb2Core.elrHyp());
        assertEquals(armCore.programCounter(), thumb2Core.programCounter());
        assertEquals(0xCAFE, thumb2Core.register(14));
    }

    // ── B9.8.3: SMC.W ────────────────────────────────────────────────────────────────────

    private static final int SMC_HI = 0xF7F5;
    private static final int SMC_LO = 0x8000;

    @Test
    void smcWDecodesImm4WithSecureMonitorCallFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, SMC_HI); // SMC.W #5 (encoding real, arm-none-eabi-as -march=armv7ve)
        memory.put16(2, SMC_LO);

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);

        assertEquals(InstructionKind.SMC, instruction.kind());
        assertEquals(5, instruction.immediate());
    }

    @Test
    void smcWIsUnimplementedWithoutSecureMonitorCallFeature() {
        // THUMB2_ARCH herda de ARMV6K, que já tem SECURE_MONITOR_CALL (B9.8.3, gate ARMv6K) —
        // diferente de HYPERVISOR_CALL, não dá para reusá-la para testar a ausência da feature.
        // Constrói um preset Thumb-2 a partir de ARMV5TE (sem SECURE_MONITOR_CALL) só para isto.
        ArmArchitecture noSmcThumb2 = ArmArchitecture.extending(
                        ArmArchitecture.ARMV5TE, "Thumb2-NoSmc", ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS)
                .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                        ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "Thumb2-NoSmc-Inner",
                                ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS))));
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, SMC_HI);
        memory.put16(2, SMC_LO);

        DecodedInstruction instruction = new ThumbDecoder(noSmcThumb2).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.SMC, instruction.kind());
    }

    @Test
    void smcWEntersMonitorModeMatchingArmClassic() {
        ArmCore thumb2Core = newCore(ArmArchitecture.ARMV7A);
        run32(thumb2Core, SMC_HI, SMC_LO); // SMC.W #5

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        armCore.memory().write32(0, 0xE160_0075); // SMC #5
        armCore.step();

        assertEquals(armCore.mode(), thumb2Core.mode());
        assertEquals(CpuMode.MONITOR, thumb2Core.mode());
        assertEquals(armCore.programCounter(), thumb2Core.programCounter());
        assertEquals(armCore.register(14), thumb2Core.register(14));
    }

    @Test
    void udfWStillDecodesAsUdfInTheSameHiRangeAsSmc() {
        // UDF.W e SMC.W compartilham o mesmo prefixo de hi (0xF7Fx, ver Javadoc de
        // Thumb2MiscDecoder#decodeSmc) — regressão do bug de dispatch corrigido nesta task: UDF
        // continua sendo tentada primeiro e reconhecida normalmente.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, UDF_HI);
        memory.put16(2, UDF_LO);

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);

        assertEquals(InstructionKind.UDF, instruction.kind());
        assertNotEquals(InstructionKind.SMC, instruction.kind());
    }

    // ── Gating G2: sem THUMB2, cai no caminho legado (UNDEFINED, comportamento inalterado) ──

    @Test
    void presetsWithoutThumb2DoNotUseThisExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, HINTS_HI);
        memory.put16(2, hintLo(0x00));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertNotEquals(InstructionKind.MSR, instruction.kind());
    }

    @Test
    void hint16SpaceDecodesOnArmv6kWithoutThumb2ButItDoesNot() {
        // B9.14: achado real — a sub-forma "hint" (mask==0000) do opcode 0xBF00 exige só
        // ArmFeature#WAIT_HINTS (ARMv6K já tem, sem Thumb-2; QEMU real gateia por
        // ARM_FEATURE_V6K, não ARM_FEATURE_THUMB2). Só a sub-forma `IT` (mask!=0000, mesmo
        // opcode) exige Thumb-2 de verdade — nome antigo deste teste ("...IsUndefinedWithoutThumb2")
        // refletia a premissa errada, corrigida por esta task.
        TestAddressSpace hintMemory = new TestAddressSpace(16);
        hintMemory.put16(0, hint16(0x00));
        DecodedInstruction hint = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(hintMemory, 0);
        assertEquals(InstructionKind.MSR, hint.kind());

        TestAddressSpace itMemory = new TestAddressSpace(16);
        itMemory.put16(0, hint16(0x0) | 0x8); // mask=1000 (!=0) -> IT
        DecodedInstruction it = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(itMemory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, it.kind());
    }

    // ── MRS/MSR bancado (B9.8.5, T32, ARM DDI 0406C A8.8.64/A8.8.66) ────────────────────────

    /// B22.5: `ARMV7A` passou a declarar `VIRTUALIZATION_EXTENSIONS` (o `Thumb2MiscDecoder` interno
    /// dele é construído com `ARMV7A_FEATURES`, que agora tem a feature). Este preset dedicado vira
    /// redundante mas é mantido (== `ARMV7A` + feature idempotente) para não reescrever as
    /// referências abaixo; a cobertura do preset público real está em
    /// {@code armv7aPresetDecodesBankedMrsAndMsrT32}.
    private static final ArmArchitecture ARMV7VE_THUMB2 = ArmArchitecture.extending(
                    ArmArchitecture.ARMV7A, "ARMv7VE-Thumb2", ArmFeature.VIRTUALIZATION_EXTENSIONS)
            .withThumb32DecoderExtensions(List.of(new Thumb2MiscDecoder(
                    ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7VE-Thumb2-Inner",
                            ArmFeature.VIRTUALIZATION_EXTENSIONS))));

    private static ArmCore newVirtualizationCore() {
        return newCore(ARMV7VE_THUMB2);
    }

    @Test
    void armv7aPresetDecodesBankedMrsAndMsrT32() {
        ThumbDecoder decoder = new ThumbDecoder(ArmArchitecture.ARMV7A);

        TestAddressSpace mrs = new TestAddressSpace(16);
        mrs.put16(0, 0xF3E0); // MRS r0, R8_usr
        mrs.put16(2, 0x8020);
        assertEquals(InstructionKind.MRS_BANK, decoder.decode(mrs, 0).kind());

        TestAddressSpace msr = new TestAddressSpace(16);
        msr.put16(0, 0xF380); // MSR SP_usr, r0 (encoding real, ver msrBankWDecodesWith...)
        msr.put16(2, 0x8520);
        assertEquals(InstructionKind.MSR_BANK, decoder.decode(msr, 0).kind());
    }

    @Test
    void mrsBankWDecodesWithVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF3E0); // MRS r0, R8_usr (encoding real, arm-none-eabi-as -march=armv7ve)
        memory.put16(2, 0x8020);

        DecodedInstruction instruction = new ThumbDecoder(ARMV7VE_THUMB2).decode(memory, 0);

        assertEquals(InstructionKind.MRS_BANK, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
    }

    @Test
    void mrsBankWIsUnimplementedWithoutVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF3E0);
        memory.put16(2, 0x8020);

        // THUMB2_ARCH tem THUMB2/MEMORY_BARRIERS mas não VIRTUALIZATION_EXTENSIONS.
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.MRS_BANK, instruction.kind());
    }

    @Test
    void mrsBankWWithUnallocatedSysmIsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        // sysm=0x7 (entre r14_usr=0x6 e r8_fiq=0x8): nenhuma entrada real na tabela.
        memory.put16(0, 0xF3E7);
        memory.put16(2, 0x8020);

        DecodedInstruction instruction = new ThumbDecoder(ARMV7VE_THUMB2).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void mrsBankWWithRdEqualToProgramCounterIsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF3E0);
        memory.put16(2, 0x8F20); // MRS pc, R8_usr — UNPREDICTABLE

        DecodedInstruction instruction = new ThumbDecoder(ARMV7VE_THUMB2).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void mrsBankWEntersUndefinedInUserModeMatchingArmClassic() {
        ArmCore thumb2Core = newVirtualizationCore();
        thumb2Core.switchMode(CpuMode.USER);
        run32(thumb2Core, 0xF3E0, 0x8020); // MRS r0, R8_usr

        ArmCore armCore = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(),
                ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7VE-A32", ArmFeature.VIRTUALIZATION_EXTENSIONS));
        armCore.switchMode(CpuMode.USER);
        armCore.memory().write32(0, 0xE100_0200); // MRS r0, R8_usr
        armCore.step();

        assertEquals(armCore.mode(), thumb2Core.mode());
        assertEquals(CpuMode.UNDEFINED, thumb2Core.mode());
        assertEquals(armCore.programCounter(), thumb2Core.programCounter());
    }

    @Test
    void mrsBankWReadsElrHypDistinctFromLr() {
        ArmCore core = newVirtualizationCore();
        core.setElrHyp(0x9000);
        core.setRegister(14, 0xBAD); // LR_usr/sys compartilhado — ELR_hyp é registrador à parte
        run32(core, 0xF3EE, 0x8030); // MRS r0, ELR_hyp

        assertEquals(0x9000, core.register(0), "ELR_hyp não é o mesmo registrador que LR");
    }

    @Test
    void mrsBankWReadsSpsrOfAnotherMode() {
        ArmCore core = newVirtualizationCore();
        core.setSpsr(CpuMode.HYP, 0xBEEF);
        run32(core, 0xF3FE, 0x8030); // MRS r0, SPSR_hyp

        assertEquals(0xBEEF, core.register(0));
    }

    @Test
    void msrBankWDecodesWithVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF380); // MSR SP_usr, r0
        memory.put16(2, 0x8520);

        DecodedInstruction instruction = new ThumbDecoder(ARMV7VE_THUMB2).decode(memory, 0);

        assertEquals(InstructionKind.MSR_BANK, instruction.kind());
        assertEquals(0, instruction.sourceRegister());
    }

    @Test
    void msrBankWIsUnimplementedWithoutVirtualizationExtensionsFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF380);
        memory.put16(2, 0x8520);

        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertNotEquals(InstructionKind.MSR_BANK, instruction.kind());
    }

    @Test
    void msrBankWWithRnEqualToProgramCounterIsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF38F); // MSR SP_usr, pc — UNPREDICTABLE
        memory.put16(2, 0x8520);

        DecodedInstruction instruction = new ThumbDecoder(ARMV7VE_THUMB2).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void msrBankWWritesGeneralRegisterOfAnotherModeWithoutChangingActiveMode() {
        ArmCore core = newVirtualizationCore();
        core.setRegister(0, 0x1234);
        run32(core, 0xF380, 0x8520); // MSR SP_usr, r0

        core.switchMode(CpuMode.SYSTEM);
        assertEquals(0x1234, core.register(13));
    }

    @Test
    void msrBankWWritesElrHyp() {
        ArmCore core = newVirtualizationCore();
        core.setRegister(0, 0x9000);
        run32(core, 0xF380, 0x8E30); // MSR ELR_hyp, r0

        assertEquals(0x9000, core.elrHyp());
    }

    @Test
    void msrBankWWritesSpsrOfAnotherMode() {
        ArmCore core = newVirtualizationCore();
        core.setRegister(0, 0xBEEF);
        run32(core, 0xF390, 0x8E30); // MSR SPSR_hyp, r0

        assertEquals(0xBEEF, core.spsr(CpuMode.HYP));
    }
}
