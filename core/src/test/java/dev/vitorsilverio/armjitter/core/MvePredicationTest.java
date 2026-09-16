package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.2 — `VPST`/`VPNOT`/`VPSEL` + avanço pós-instrução do `VPR`/`ECI` + `VMSR`/`VMRS reg=12`
/// (`VPR`), fim-a-fim (decode + lift + executor interpretado) sobre o preset real
/// `ARMV8_1M_MVE`. Mesmo padrão de {@code NocpTest}/{@code VscclrmTest}.
class MvePredicationTest {
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    /// Bit `INVSTATE` do `UFSR` — `bit[1]` do `UFSR`, byte alto do `CFSR`.
    private static final int CFSR_UFSR_INVSTATE_BIT = 1 << 17;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        memory.put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1); // vetor Thumb
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int hi, int lo) {
        ((TestAddressSpace) core.memory()).put16(address, hi);
        ((TestAddressSpace) core.memory()).put16(address + 2, lo);
    }

    // ── VPST: pipeline completo, grava MASK01/MASK23 e AVANÇA o ECI depois (achado central: o
    // avanço roda mesmo para a instrução que ela mesma acabou de configurar) ──────────────────────

    @Test
    void vpstSetsBothMaskFieldsThenTheAdvanceShiftsThemLeft() {
        ArmCore core = newCore();
        // mask=0b1010 (bit22=1,bits15-13=010): raw 0xFE71_4F4D. ECI_NONE (default) -> eciMask
        // pleno (0xFFFF) -> a etapa de avanço (que roda DEPOIS de VPST já ter gravado mask=0b1010
        // nos dois campos) desloca cada um 1 bit à esquerda: 0b1010<<1 = 0b10100, truncado a 4
        // bits do campo = 0b0100 = 4.
        put32(core, CODE_BASE, 0xFE71, 0x4F4D);

        core.step();

        int vpr = core.vpr().value();
        assertEquals(4, (vpr & VprRegister.MASK01_MASK) >>> VprRegister.MASK01_SHIFT);
        assertEquals(4, (vpr & VprRegister.MASK23_MASK) >>> VprRegister.MASK23_SHIFT);
        // P0 (inicialmente 0) é invertido pelas lanes correspondentes ao eciMask pleno (0xFFFF).
        assertEquals(0xFFFF, core.vpr().p0());
    }

    @Test
    void vpstWrapsEciFromA0A1A2B0ToA0Afterwards() {
        ArmCore core = newCore();
        core.cpsr().setEci(MveVptState.ECI_A0A1A2B0);
        put32(core, CODE_BASE, 0xFE71, 0x4F4D); // mask != 0 -> não é VPNOT.

        core.step();

        assertEquals(MveVptState.ECI_A0, core.cpsr().eci());
    }

    // ── VPNOT: inverte P0 nas lanes correspondentes a beats ainda não executados (ECI_NONE ->
    // todas as 16 lanes) ──────────────────────────────────────────────────────────────────────────

    @Test
    void vpnotInvertsP0Fully() {
        ArmCore core = newCore();
        core.vpr().setP0(0x00FF);
        put32(core, CODE_BASE, 0xFE31, 0x0F4D);

        core.step();

        assertEquals(0xFF00, core.vpr().p0());
    }

    // ── VPSEL: seleção lane a lane (byte a byte) segundo P0, mascarada por MASK01/MASK23 ─────────

    @Test
    void vpselSelectsBetweenQnAndQmPerByteAccordingToP0() {
        ArmCore core = newCore();
        int qn = 2;
        int qm = 4;
        int qd = 6;
        core.vfp().setQ(qn, 0x1111_1111_1111_1111L, 0x1111_1111_1111_1111L);
        core.vfp().setQ(qm, 0x2222_2222_2222_2222L, 0x2222_2222_2222_2222L);
        // P0 = 0x00FF: byte 0-7 selecionam Qn (bit=1), byte 8-15 selecionam Qm (bit=0).
        core.vpr().setP0(0x00FF);
        // qd=6, qn=2, qm=4: bit22=1(qd high=1,low=6&0x7=6->wait qd=6 -> high=0,low=6)
        int rawVpsel = vpselRaw(qd, qn, qm);
        put32(core, CODE_BASE, rawVpsel >>> 16, rawVpsel & 0xFFFF);

        core.step();

        long lowResult = core.vfp().low64(qd);
        long highResult = core.vfp().high64(qd);
        assertEquals(0x1111_1111_1111_1111L, lowResult, "P0 baixo=1 -> byte de Qn");
        assertEquals(0x2222_2222_2222_2222L, highResult, "P0 alto=0 -> byte de Qm");
    }

    @Test
    void vpselFullyMaskedOutStillAdvancesVpr() {
        ArmCore core = newCore();
        int qn = 1;
        int qm = 2;
        int qd = 3;
        core.vfp().setQ(qd, 0xAAAA_AAAA_AAAA_AAAAL, 0xAAAA_AAAA_AAAA_AAAAL);
        core.vfp().setQ(qn, 0x1111_1111_1111_1111L, 0x1111_1111_1111_1111L);
        core.vfp().setQ(qm, 0x2222_2222_2222_2222L, 0x2222_2222_2222_2222L);
        // P0=0 e MASK01/MASK23 != 0 (VPT ativo) -> elementMask = 0 inteiro (G4: totalmente
        // predicado, Qd não deve mudar, mas o avanço ainda roda).
        core.vpr().setP0(0);
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int rawVpsel = vpselRaw(qd, qn, qm);
        put32(core, CODE_BASE, rawVpsel >>> 16, rawVpsel & 0xFFFF);

        core.step();

        assertEquals(0xAAAA_AAAA_AAAA_AAAAL, core.vfp().low64(qd), "totalmente predicado, Qd intocado");
        assertEquals(0xAAAA_AAAA_AAAA_AAAAL, core.vfp().high64(qd));
        // G4: o avanço ainda roda (MASK01/MASK23 avançam mesmo sem nenhuma lane escrita).
        assertEquals(4, core.vpr().mask01(), "avanço incondicional: 2<<1=4");
        assertEquals(4, core.vpr().mask23());
    }

    private static int vpselRaw(int qd, int qn, int qm) {
        int base = 0xFE310F01;
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return base | (qdHigh << 22) | (qdLow << 13) | (qnHigh << 7) | (qnLow << 17) | (qmHigh << 5) | (qmLow << 1);
    }

    // ── ECI reservado: USAGE_FAULT com UFSR.INVSTATE (via IrOp direto — inalcançável pelo decode
    // real deste emulador, ver `## Resultado` da task) ───────────────────────────────────────────

    @Test
    void reservedEciEntersUsageFaultWithInvstateSet() {
        ArmCore core = newCore();
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setEci(3); // valor reservado.
        int pcBefore = core.programCounter();

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new IrOp.Vpnot(Condition.AL), pcBefore);

        assertTrue(pcChanged);
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(CFSR_UFSR_INVSTATE_BIT, model.cfsr() & CFSR_UFSR_INVSTATE_BIT);
    }

    // ── Condição falsa: nenhuma das 3 avança o VPR/ECI (mesmo padrão de VscclrmTest) ─────────────

    @Test
    void conditionalVpstSkippedWhenConditionFalse() {
        ArmCore core = newCore();
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG); // Z=0 -> EQ falsa
        int vprBefore = core.vpr().value();

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new IrOp.Vpst(0b1111, Condition.EQ), core.programCounter());

        assertTrue(!pcChanged);
        assertEquals(vprBefore, core.vpr().value());
    }

    // ── VMSR/VMRS reg=12 (VPR): armazenamento puro, sem aliasing, NÃO avança ECI ──────────────────

    @Test
    void vmsrWritesRawRegisterIntoVpr() {
        ArmCore core = newCore();
        core.setRegister(3, 0x0001_0203);
        // VMSR reg=12, rt=3: VMSR_VMRS_VALUE | (12<<16) | (3<<12), L=0 (escrita).
        int raw = 0xEEE0_0A10 | (0xC << 16) | (3 << 12);
        put32(core, CODE_BASE, raw >>> 16, raw & 0xFFFF);

        core.step();

        assertEquals(0x0001_0203, core.vpr().value());
    }

    @Test
    void vmrsReadsVprIntoRawRegister() {
        ArmCore core = newCore();
        core.vpr().setValue(0x0405_0607);
        // VMRS reg=12, rt=3: VMSR_VMRS_VALUE | (12<<16) | (3<<12) | L(bit20).
        int raw = 0xEEE0_0A10 | (0xC << 16) | (3 << 12) | (1 << 20);
        put32(core, CODE_BASE, raw >>> 16, raw & 0xFFFF);

        core.step();

        assertEquals(0x0405_0607, core.register(3));
    }

    @Test
    void vprTransferDoesNotAdvanceEci() {
        ArmCore core = newCore();
        core.cpsr().setEci(MveVptState.ECI_A0A1);
        int raw = 0xEEE0_0A10 | (0xC << 16) | (3 << 12) | (1 << 20); // VMRS reg=12, rt=3.
        put32(core, CODE_BASE, raw >>> 16, raw & 0xFFFF);

        core.step();

        assertEquals(MveVptState.ECI_A0A1, core.cpsr().eci(), "VMSR_VMRS nunca é beatwise");
    }
}
