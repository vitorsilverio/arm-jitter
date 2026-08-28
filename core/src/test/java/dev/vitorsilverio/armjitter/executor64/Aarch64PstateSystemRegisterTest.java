package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `NZCV`/`DAIF` via `MRS`/`MSR` (B8.16) — ao contrário de `TPIDR_EL0`/`FPCR`/`FPSR`, estes NÃO são
/// escaninhos paralelos: `NZCV` é a MESMA fonte de verdade que toda ALU/`B.cond` já usa
/// (`Aarch64Core#pstate()`), e o bit `I` de `DAIF` é o MESMO que {@code enterIrq} consulta (B6.6.7).
/// Corpus real via `aarch64-none-elf-as`/`objdump` (devkitA64).
class Aarch64PstateSystemRegisterTest {
    private static final dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder DECODER =
            new dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder();
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(8);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    private static Ir64Op.SystemRegister decode(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemRegister) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void mrsNzcvDecodes() {
        // d53b4200: mrs x0, nzcv
        Ir64Op.SystemRegister op = decode(0xd53b4200);
        assertEquals(true, op.read());
        assertEquals(Aarch64SystemRegisterId.NZCV, op.register());
    }

    @Test
    void msrDaifDecodes() {
        // d51b4221: msr daif, x1
        Ir64Op.SystemRegister op = decode(0xd51b4221);
        assertEquals(false, op.read());
        assertEquals(Aarch64SystemRegisterId.DAIF, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsNzcvReflectsRealConditionFlags() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(true, false, true, false); // N=1 Z=0 C=1 V=0

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.NZCV, 0));

        assertEquals(0xA0000000L, core.x(0) & 0xF000_0000L, "N=1(bit31) C=1(bit29) -> 0xA em [31:28]");
    }

    @Test
    void msrNzcvChangesRealFlagsSeenByConditionalBranch() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(false, false, false, false);
        assertFalse(core.pstate().evalCond(Ir64Condition.EQ), "Z=0 antes do MSR");

        core.setX(0, 1L << 30); // Z bit em [31:28] -> bit30 = Z
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.NZCV, 0));

        assertTrue(core.pstate().evalCond(Ir64Condition.EQ),
                "MSR NZCV muda o MESMO estado que B.cond consulta, não um escaninho paralelo");
    }

    @Test
    void msrDaifSetsRealIrqMask() {
        Aarch64Core core = newCore();
        assertFalse(core.pstate().irqDisabled());

        core.setX(1, 1L << 7); // bit I
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.DAIF, 1));

        assertTrue(core.pstate().irqDisabled(), "MSR DAIF muda o MESMO irqDisabled que enterIrq consulta");

        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.DAIF, 2));
        assertEquals(1L << 7, core.x(2));
    }

    @Test
    void cntvctEl0DecodesAsDistinctFromCntpctEl0() {
        // d53be042: mrs x2, cntvct_el0
        Ir64Op.SystemRegister op = decode(0xd53be042);
        assertEquals(Aarch64SystemRegisterId.CNTVCT_EL0, op.register());
    }

    @Test
    void cntvTvalCtlCvalDecode() {
        // d53be303: mrs x3, cntv_tval_el0 / d53be324: mrs x4, cntv_ctl_el0 / d53be345: mrs x5, cntv_cval_el0
        assertEquals(Aarch64SystemRegisterId.CNTV_TVAL_EL0, decode(0xd53be303).register());
        assertEquals(Aarch64SystemRegisterId.CNTV_CTL_EL0, decode(0xd53be324).register());
        assertEquals(Aarch64SystemRegisterId.CNTV_CVAL_EL0, decode(0xd53be345).register());
    }
}
