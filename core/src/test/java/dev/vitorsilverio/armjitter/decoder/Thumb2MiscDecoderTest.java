package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void itBlockMaskIsLeftUndefinedByThisTask() {
        // mask != 0000 no mesmo opcode de 16 bits: IT block, B2.4 — deliberadamente fora do
        // escopo de B2.5 (ver Thumb2MiscDecoderTest/ThumbDecoder). Deve continuar UNDEFINED.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hint16(0x0) | 0x8); // firstcond=0000, mask=1000 (!=0) -> "IT" no real HW
        DecodedInstruction instruction = new ThumbDecoder(THUMB2_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
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
    void hint16SpaceIsUndefinedWithoutThumb2() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, hint16(0x00));

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }
}
