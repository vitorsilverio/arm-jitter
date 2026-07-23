package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B7.4 — `MRS`/`MSR` na forma SYSm e `CPS` de 16 bits do perfil M, executados fim-a-fim
/// (decode + lift + executor interpretado) sobre os presets reais `ARMV6M`/`ARMV7M` com um
/// {@link MProfileExceptionModel} instalado. Ver "Testes mínimos" de
/// `tasks/trilha-b-arquiteturas/b7.4-presets-armv6m-armv7m.md`.
class MProfileSystemRegisterTest {
    private static final int CODE_BASE = 0x100;

    private static ArmCore newCore(ArmArchitecture architecture, MProfileExceptionModel model) {
        ArmCore core = new ArmCore(new TestAddressSpace(1024), SwiDispatcher.empty(), architecture);
        core.setExceptionModel(model);
        core.cpsr().setThumbMode(true);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    /// `MRS Rd, <SYSm>` de 32 bits: hi=0xF3EF, lo=1000 Rd:4 SYSm:8.
    private static void stepMrs(ArmCore core, int rd, int sysm) {
        step32(core, 0xF3EF, 0x8000 | (rd << 8) | sysm);
    }

    /// `MSR <SYSm>, Rn` de 32 bits: hi=0xF380|Rn, lo=1000 xxxx SYSm:8 (máscara não modelada).
    private static void stepMsr(ArmCore core, int rn, int sysm) {
        step32(core, 0xF380 | rn, 0x8000 | sysm);
    }

    private static void step32(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int pc = core.programCounter();
        memory.put16(pc, hi);
        memory.put16(pc + 2, lo);
        core.step();
    }

    private static void step16(ArmCore core, int raw) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        memory.put16(core.programCounter(), raw);
        core.step();
    }

    // ── MRS/MSR ida-e-volta para cada SYSm da tabela (ARMV7M tem todos) ──────────────────────

    @Test
    void primaskRoundTrip() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        core.setRegister(0, 1);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_PRIMASK);
        assertEquals(1, model.primask(), "MSR PRIMASK, r0(=1) deve setar PRIMASK");
        stepMrs(core, 1, MProfileExceptionModel.SYSM_PRIMASK);
        assertEquals(1, core.register(1), "MRS r1, PRIMASK deve ler 1 de volta");
    }

    @Test
    void basepriRoundTrip() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        core.setRegister(0, 0x30);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_BASEPRI);
        assertEquals(0x30, model.basepri());
        stepMrs(core, 1, MProfileExceptionModel.SYSM_BASEPRI);
        assertEquals(0x30, core.register(1));
    }

    @Test
    void basepriMaxOnlyLowersTheLimit() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        core.setRegister(0, 0x40);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_BASEPRI_MAX); // 0x00 -> 0x40 (abaixa a partir de "desligado")
        assertEquals(0x40, model.basepri());
        core.setRegister(0, 0x20);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_BASEPRI_MAX); // 0x20 < 0x40 -> abaixa
        assertEquals(0x20, model.basepri());
        core.setRegister(0, 0x50);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_BASEPRI_MAX); // 0x50 > 0x20 -> NÃO sobe
        assertEquals(0x20, model.basepri());
        core.setRegister(0, 0x00);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_BASEPRI_MAX); // 0 -> NÃO mexe
        assertEquals(0x20, model.basepri());
    }

    @Test
    void faultmaskRoundTrip() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        core.setRegister(0, 1);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_FAULTMASK);
        assertEquals(1, model.faultmask());
        stepMrs(core, 1, MProfileExceptionModel.SYSM_FAULTMASK);
        assertEquals(1, core.register(1));
    }

    @Test
    void controlRoundTripAndSpselSwapsActiveStackInThreadMode() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        int msp = 0x300;
        int psp = 0x280;
        core.setRegister(13, msp);       // MSP ativo (SPSEL=0)
        model.setProcessStackPointer(psp);
        core.setRegister(0, MProfileExceptionModel.CONTROL_SPSEL_BIT);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_CONTROL);
        assertTrue(model.spsel(), "CONTROL.SPSEL deve virar 1");
        assertEquals(psp, core.register(13), "em Thread mode, SPSEL 0->1 troca o SP ativo p/ o PSP");
        stepMrs(core, 1, MProfileExceptionModel.SYSM_CONTROL);
        assertEquals(MProfileExceptionModel.CONTROL_SPSEL_BIT, core.register(1), "MRS CONTROL lê o valor escrito");
    }

    @Test
    void controlSpselWriteIsIgnoredInHandlerMode() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        core.setRegister(13, 0x300);
        // Entra numa exceção (Handler mode). Vetor SVCALL aponta para código Thumb qualquer.
        ((TestAddressSpace) core.memory()).put32(4 * MProfileException.SVCALL.number(), CODE_BASE | 1);
        model.enterException(core, MProfileException.SVCALL);
        int spInHandler = core.register(13);
        assertTrue(model.handlerModeActive());

        core.setProgramCounter(core.programCounter());
        core.setRegister(0, MProfileExceptionModel.CONTROL_SPSEL_BIT);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_CONTROL);
        assertEquals(spInHandler, core.register(13), "em Handler mode a escrita de CONTROL.SPSEL não troca o SP ativo");
    }

    @Test
    void mspAndPspRoundTripViaShadow() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        // Thread/SPSEL=0: MSP é o ativo (=reg13), PSP é a sombra.
        core.setRegister(13, 0x300);
        core.setRegister(0, 0x2A0);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_PSP); // escreve a sombra do PSP
        assertEquals(0x300, core.register(13), "escrever o PSP inativo não toca o SP ativo (MSP)");
        stepMrs(core, 1, MProfileExceptionModel.SYSM_PSP);
        assertEquals(0x2A0, core.register(1));
        stepMrs(core, 2, MProfileExceptionModel.SYSM_MSP);
        assertEquals(0x300, core.register(2), "MRS MSP lê o SP ativo em Thread/SPSEL=0");
    }

    @Test
    void msrApsrWritesOnlyApplicationFlagsNeverIpsr() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        // Valor com NZCV setados E bits de IPSR (8:0) setados: só NZCV devem ir para o CPSR.
        int value = CpsrRegister.NEGATIVE_FLAG | CpsrRegister.CARRY_FLAG | 0x1FF;
        core.setRegister(0, value);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_XPSR);
        assertTrue(core.cpsr().negative());
        assertTrue(core.cpsr().carry());
        assertFalse(core.cpsr().zero());
        assertFalse(core.cpsr().overflow());
        assertEquals(0, model.currentException(), "MSR XPSR nunca escreve o IPSR (segue Thread mode)");
    }

    @Test
    void mrsXpsrComposesApsrAndIpsr() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        ((TestAddressSpace) core.memory()).put32(4 * MProfileException.SVCALL.number(), CODE_BASE | 1);
        core.setRegister(13, 0x300);
        core.cpsr().setNzcv(false, true, false, false); // Z
        model.enterException(core, MProfileException.SVCALL); // IPSR = 11
        stepMrs(core, 0, MProfileExceptionModel.SYSM_XPSR);
        int expected = CpsrRegister.ZERO_FLAG | MProfileException.SVCALL.number();
        assertEquals(expected, core.register(0), "XPSR = APSR(Z) | IPSR(11)");
    }

    @Test
    void ipsrIsReadOnly() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        core.setRegister(0, 5);
        stepMsr(core, 0, MProfileExceptionModel.SYSM_IPSR); // RAZ/WI
        assertEquals(0, model.currentException(), "MSR IPSR é RAZ/WI (não muda a exceção ativa)");
        stepMrs(core, 1, MProfileExceptionModel.SYSM_IPSR);
        assertEquals(0, core.register(1), "IPSR lê 0 em Thread mode");
    }

    // ── CPS de 16 bits ──────────────────────────────────────────────────────────────────────

    @Test
    void cpsidISetsPrimaskCpsieIClearsIt() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV6M, model);
        step16(core, 0xB672); // CPSID i
        assertEquals(1, model.primask(), "CPSID i deve setar PRIMASK");
        step16(core, 0xB662); // CPSIE i
        assertEquals(0, model.primask(), "CPSIE i deve limpar PRIMASK");
    }

    @Test
    void cpsidFSetsFaultmaskOnArmv7m() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(ArmArchitecture.ARMV7M, model);
        step16(core, 0xB671); // CPSID f
        assertEquals(1, model.faultmask(), "CPSID f deve setar FAULTMASK no ARMv7-M");
        assertEquals(0, model.primask(), "CPSID f não toca PRIMASK");
    }

    // ── G1: interpretado × ASM (o bloco cai no interpretado, mas prova convergência) ─────────

    @Test
    void interpretedAndAsmBackendsAgreeOnMsrPrimask() {
        MProfileExceptionModel interpModel = new MProfileExceptionModel();
        MProfileExceptionModel asmModel = new MProfileExceptionModel();
        ArmCore interp = newCore(ArmArchitecture.ARMV7M, interpModel);
        ArmCore asm = newCore(ArmArchitecture.ARMV7M, asmModel);
        interp.setRegister(0, 1);
        asm.setRegister(0, 1);

        // MRS/MSR SYSm cai no interpretado (AsmNativePolicy.supports == false), então o AsmCodeEmitter
        // usa o fallback — este teste garante que o estado resultante é idêntico ao interpretado puro.
        IrBlock block = IrBlock.builder(CODE_BASE)
                .add(new IrOp.MProfileSystemRegister(false, 0, MProfileExceptionModel.SYSM_PRIMASK, Condition.AL))
                .endPc(CODE_BASE + 4)
                .sealed();
        new InterpretedCodeEmitter(ArmArchitecture.ARMV7M).emit(block).execute(interp);
        new AsmCodeEmitter(ArmArchitecture.ARMV7M).emit(block).execute(asm);

        assertEquals(interpModel.primask(), asmModel.primask());
        assertEquals(interp.programCounter(), asm.programCounter());
        assertNotEquals(0, interpModel.primask());
    }
}
