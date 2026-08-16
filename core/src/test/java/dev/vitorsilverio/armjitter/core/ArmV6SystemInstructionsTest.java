package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// CPS, SRS, RFE, SETEND e os hints WAIT_HINTS (WFI) da task B1.5: troca de modo/A-I-F via CPS,
/// ida-e-volta de SRS/RFE nos 4 modos de endereçamento (com/sem writeback), RFE trocando para
/// THUMB, WFI colocando o core em HALT, SETEND expondo o bit E do CPSR, e o gating UNDEFINED em
/// ARMv4T/v5TE (G2). O suporte real a acesso de dados com E=1 (BE8) é da task B1.8 — ver
/// {@code ArmV6Be8DataEndiannessTest}.
class ArmV6SystemInstructionsTest {
    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    private static ArmCore run(ArmCore core, int... instructions) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        for (int i = 0; i < instructions.length; i++) {
            memory.put32(base + i * 4, instructions[i]);
        }
        for (int i = 0; i < instructions.length; i++) {
            core.step();
        }
        return core;
    }

    /// `CPS{IE,ID} <iflags>{,#<mode>}` (cond=1111, forçado AL).
    private static int cps(int imod, boolean modeChange, boolean a, boolean i, boolean f, int mode) {
        return 0xF100_0000
                | (imod << 18)
                | (modeChange ? 1 << 17 : 0)
                | (a ? 1 << 8 : 0)
                | (i ? 1 << 7 : 0)
                | (f ? 1 << 6 : 0)
                | (mode & 0x1F);
    }

    /// `SETEND <endianness>` (cond=1111, forçado AL).
    private static int setend(boolean bigEndian) {
        return 0xF101_0000 | (bigEndian ? 1 << 9 : 0);
    }

    /// `SRS<mode4S> #<p_mode>{!}` (cond=1111, forçado AL).
    private static int srs(boolean p, boolean u, boolean w, int mode) {
        return 0xF84D_0500 | (p ? 1 << 24 : 0) | (u ? 1 << 23 : 0) | (w ? 1 << 21 : 0) | (mode & 0x1F);
    }

    /// `RFE<mode4L> Rn{!}` (cond=1111, forçado AL).
    private static int rfe(boolean p, boolean u, boolean w, int rn) {
        return 0xF810_0A00 | (p ? 1 << 24 : 0) | (u ? 1 << 23 : 0) | (w ? 1 << 21 : 0) | (rn << 16);
    }

    /// `WFI{cond}` (disfarçado de MSR-registrador com Rm=PC e máscara vazia).
    private static final int WFI = 0xE320_F003;

    // ── CPS ──────────────────────────────────────────────────────────────────────

    @Test
    void cpsDisablesAndEnablesInterruptFlags() {
        ArmCore core = newCore();
        core.cpsr().setIrqDisabled(false);
        core.cpsr().setFiqDisabled(false);
        run(core, cps(0b11, false, false, true, true, 0)); // CPSID if
        assertTrue(core.cpsr().irqDisabled());
        assertTrue(core.cpsr().fiqDisabled());

        run(core, cps(0b10, false, false, true, true, 0)); // CPSIE if
        assertFalse(core.cpsr().irqDisabled());
        assertFalse(core.cpsr().fiqDisabled());
    }

    @Test
    void cpsChangesModeAndRebanksSpAndLr() {
        ArmCore core = newCore();
        assertEquals(CpuMode.SUPERVISOR, core.mode());
        core.setBankedRegister(CpuMode.SYSTEM, 13, 0x1000);
        core.setBankedRegister(CpuMode.SYSTEM, 14, 0x2000);
        core.setRegister(13, 0xAAAA);
        core.setRegister(14, 0xBBBB);

        run(core, cps(0b00, true, false, false, false, CpuMode.SYSTEM.bits())); // CPS #System, sem imod

        assertEquals(CpuMode.SYSTEM, core.mode());
        assertEquals(0x1000, core.register(13), "SP deve vir do banco SYSTEM");
        assertEquals(0x2000, core.register(14), "LR deve vir do banco SYSTEM");
    }

    @Test
    void cpsInUserModeIsANoOp() {
        ArmCore core = newCore();
        core.setBankedRegister(CpuMode.USER, 13, 0);
        run(core, cps(0b00, true, false, false, false, CpuMode.USER.bits())); // entra em USER
        assertEquals(CpuMode.USER, core.mode());
        int before = core.cpsr().get();

        run(core, cps(0b11, false, false, true, true, 0)); // CPSID if, em USER -> NOP

        assertEquals(before, core.cpsr().get(), "CPS em modo User nao deve alterar o CPSR");
    }

    // ── SETEND ───────────────────────────────────────────────────────────────────

    @Test
    void setendTogglesTheEBitVisibleInCpsr() {
        ArmCore core = newCore();
        assertFalse(core.cpsr().isBigEndian());
        run(core, setend(true));
        assertTrue(core.cpsr().isBigEndian());
        run(core, setend(false));
        assertFalse(core.cpsr().isBigEndian());
    }

    @Test
    void dataAccessWithBigEndianNoLongerThrowsSinceB18() {
        // A B1.5 lançava UnsupportedOperationException aqui (decisão de escopo MVP); a B1.8
        // implementa BE8 de verdade — ver ArmV6Be8DataEndiannessTest para a cobertura completa
        // de valor trocado. Este teste só prova que a exceção não volta por acidente.
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        run(core, setend(true));
        int ldr = 0xE590_1000; // LDR r1, [r0]
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        memory.put32(core.programCounter(), ldr);
        core.step();
        assertEquals(0, core.register(1), "sem valor gravado em 0x10, o load só não deve lançar");
    }

    // ── WFI ──────────────────────────────────────────────────────────────────────

    @Test
    void wfiHaltsAndInterruptLineWakesTheCore() {
        ArmCore core = newCore();
        core.cpsr().setIrqDisabled(false);
        core.setBankedRegister(CpuMode.IRQ, 13, 0x4000);
        run(core, WFI);
        assertEquals(CpuSleepState.HALTED, core.sleepState());

        core.setInterruptLine(true);
        core.step();
        assertEquals(CpuSleepState.RUNNING, core.sleepState());
        assertEquals(CpuMode.IRQ, core.mode());
    }

    // ── SRS / RFE ────────────────────────────────────────────────────────────────

    @Test
    void srsThenRfeRoundTripsLrAndSpsrInAllAddressingModes() {
        for (boolean p : new boolean[] {false, true}) {
            for (boolean u : new boolean[] {false, true}) {
                for (boolean writeback : new boolean[] {false, true}) {
                    ArmCore core = newCore();
                    core.setBankedRegister(CpuMode.SYSTEM, 13, 0x100);
                    // Entra em IRQ (tem SPSR) com um LR e SPSR conhecidos.
                    core.switchMode(CpuMode.IRQ);
                    core.setRegister(14, 0xCAFEBABE);
                    core.setSpsr(CpuMode.IRQ, 0x6000_0012); // NZCV=0110, modo IRQ (retorna para IRQ)

                    run(core, srs(p, u, writeback, CpuMode.SYSTEM.bits()));

                    // O endereço onde os dois words foram gravados: recomputa igual ao startAddress.
                    int startAddress;
                    if (u) {
                        startAddress = p ? 0x100 + 4 : 0x100;
                    } else {
                        startAddress = p ? 0x100 - 8 : 0x100 - 4;
                    }
                    assertEquals(0xCAFEBABE, core.memory().read32(startAddress & ~3), () -> "LR atual em p=" + p + " u=" + u + " w=" + writeback);
                    assertEquals(0x6000_0012, core.memory().read32((startAddress & ~3) + 4));
                    if (writeback) {
                        int expectedWriteback = u ? 0x100 + 8 : 0x100 - 8;
                        assertEquals(expectedWriteback, core.bankedRegister(CpuMode.SYSTEM, 13));
                    }

                    // Agora RFE de volta com Rn apontando para a MESMA base (0x100): RFE recalcula
                    // o mesmo startAddress aplicando o mesmo (p,u), então precisa da base crua, não
                    // do endereço já deslocado. Em outro modo privilegiado (SVC), restaura PC e
                    // CPSR (-> IRQ de novo).
                    core.switchMode(CpuMode.SUPERVISOR);
                    core.setRegister(0, 0x100);
                    core.setRegister(15, 0x50);
                    run(core, rfe(p, u, false, 0));

                    assertEquals(CpuMode.IRQ, core.mode(), "RFE deve restaurar o modo do SPSR gravado");
                    assertEquals(0xCAFEBABE & ~3, core.programCounter() & ~3, "RFE deve restaurar o PC (alinhado)");
                }
            }
        }
    }

    @Test
    void rfeRestoresThumbStateFromCpsr() {
        ArmCore core = newCore();
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(0, 0x40);
        core.memory().write32(0x40, 0x1000); // PC alvo
        core.memory().write32(0x44, CpuMode.SYSTEM.bits() | CpsrRegister.THUMB_FLAG); // CPSR -> System + Thumb
        run(core, rfe(false, true, false, 0)); // RFEIA r0

        assertEquals(CpuMode.SYSTEM, core.mode());
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x1000, core.programCounter());
    }

    @Test
    void rfeWithWritebackUpdatesBase() {
        ArmCore core = newCore();
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(0, 0x80);
        core.memory().write32(0x80, 0x2000);
        core.memory().write32(0x84, CpuMode.SYSTEM.bits());
        run(core, rfe(false, true, true, 0)); // RFEIA r0!

        assertEquals(0x88, core.register(0));
    }

    @Test
    void srsAndRfeInUserModeAreUndefined() {
        ArmCore core = newCore();
        core.switchMode(CpuMode.USER);
        run(core, srs(false, true, false, CpuMode.SYSTEM.bits()));
        assertEquals(CpuMode.UNDEFINED, core.mode(), "SRS em User deve entrar na exceção UNDEFINED");

        ArmCore core2 = newCore();
        core2.switchMode(CpuMode.USER);
        core2.setRegister(0, 0x10);
        run(core2, rfe(false, true, false, 0));
        assertEquals(CpuMode.UNDEFINED, core2.mode(), "RFE em User deve entrar na exceção UNDEFINED");
    }

    // ── Gating de arquitetura (G2) ───────────────────────────────────────────────

    @Test
    void systemInstructionsStayUndefinedOnArmv4tAndArmv5te() {
        int[] encodings = {
                cps(0b11, false, false, true, true, 0),
                setend(true),
                srs(false, true, false, CpuMode.SYSTEM.bits()),
                rfe(false, true, false, 0),
                WFI,
        };
        for (int encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind(),
                    () -> "ARMv4T deve manter UNDEFINED: 0x" + Integer.toHexString(encoding));
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind(),
                    () -> "ARMv5TE deve manter UNDEFINED: 0x" + Integer.toHexString(encoding));
        }
    }

    @Test
    void systemInstructionsDecodeOnArmv6k() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, cps(0b11, false, false, true, true, 0));
        memory.put32(4, setend(true));
        memory.put32(8, srs(false, true, false, CpuMode.SYSTEM.bits()));
        memory.put32(12, rfe(false, true, false, 0));
        memory.put32(16, WFI);
        assertEquals(InstructionKind.CPS, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.SETEND, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.STORE_RETURN_STATE, decoder.decode(memory, 8).kind());
        assertEquals(InstructionKind.RETURN_FROM_EXCEPTION, decoder.decode(memory, 12).kind());
        assertEquals(InstructionKind.WAIT_FOR_INTERRUPT, decoder.decode(memory, 16).kind());
    }
}
