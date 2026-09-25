package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder;
import dev.vitorsilverio.armjitter.decoder.Thumb2LowOverheadBranchDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.15 — tail-predication (`DLSTP`/`WLSTP`/`LETP`/`LCTP`/`VCTP`), `BF*` e `CLRM` fim-a-fim
/// (decode + lift + executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Os valores
/// esperados seguem `trans_DLS`/`trans_WLS`/`trans_LE`/`trans_LCTP`/`HELPER(mve_vctp)`/`trans_CLRM`
/// do QEMU real (`target/qemu-src`), não a dedução dos nomes.
class TailPredicationTest {
    private static final int LINK_REGISTER = 14;
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    private static final int STEP_LIMIT = 64;
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 6 * 4;
    private static final int USAGE_FAULT_HANDLER_PC = 0x400;

    private static ArmCore newCore(ArmArchitecture architecture) {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), architecture);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static ArmCore newMveCore() {
        return newCore(ArmArchitecture.ARMV8_1M_MVE);
    }

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static void put16(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value);
    }

    private static void stepUntil(ArmCore core, int pc) {
        for (int i = 0; i < STEP_LIMIT && core.programCounter() != pc; i++) {
            core.step();
        }
        assertEquals(pc, core.programCounter(), "o programa deveria ter chegado a 0x" + Integer.toHexString(pc));
    }

    // ── DLSTP / WLSTP / LETP / LCTP ──────────────────────────────────────────────────────────────

    @Test
    void ltpsizeResetsToNone() {
        assertEquals(FpscrRegister.LTPSIZE_NONE, newMveCore().fpscr().ltpsize());
    }

    @Test
    void dlstpSetsLrAndLtpsize() {
        ArmCore core = newMveCore();
        core.setRegister(2, 7);
        // DLSTP.16 R2: size=1 em bits[21:20].
        put32(core, CODE_BASE, 0xF000E001 | (1 << 20) | (2 << 16));

        core.step();

        assertEquals(CODE_BASE + 4, core.programCounter());
        assertEquals(7, core.register(LINK_REGISTER));
        assertEquals(1, core.fpscr().ltpsize());
    }

    @Test
    void wlstpWithZeroCountSkipsWithoutTouchingLrOrLtpsize() {
        ArmCore core = newMveCore();
        core.setRegister(1, 0);
        core.setRegister(LINK_REGISTER, 0x77);
        // WLSTP.32 R1, #6: size=2, imm=3 (x2).
        put32(core, CODE_BASE, 0xF000C001 | (2 << 20) | (1 << 16) | (3 << 1));

        core.step();

        assertEquals(CODE_BASE + 4 + 6, core.programCounter());
        assertEquals(0x77, core.register(LINK_REGISTER), "WLSTP com Rn==0 NÃO grava LR (trans_WLS)");
        assertEquals(FpscrRegister.LTPSIZE_NONE, core.fpscr().ltpsize());
    }

    @Test
    void wlstpWithNonZeroCountStartsTheLoop() {
        ArmCore core = newMveCore();
        core.setRegister(1, 9);
        put32(core, CODE_BASE, 0xF000C001 | (2 << 20) | (1 << 16) | (3 << 1));

        core.step();

        assertEquals(CODE_BASE + 4, core.programCounter());
        assertEquals(9, core.register(LINK_REGISTER));
        assertEquals(2, core.fpscr().ltpsize());
    }

    @Test
    void dlstpLoopRunsUntilLrDropsBelowOneVectorAndRestoresLtpsize() {
        ArmCore core = newMveCore();
        // 16 bits por elemento => 8 elementos por iteração (decremento 1 << (4 - 1)).
        core.setRegister(2, 20);
        put32(core, CODE_BASE, 0xF000E001 | (1 << 20) | (2 << 16)); // DLSTP.16 R2
        put16(core, CODE_BASE + 4, 0x3301); //                          ADDS R3, #1 (corpo)
        put32(core, CODE_BASE + 6, 0xF01FC001 | (3 << 1)); //            LETP -> 0x104

        stepUntil(core, CODE_BASE + 10);

        assertEquals(3, core.register(3), "20 -> 12 -> 4: três iterações");
        assertEquals(4, core.register(LINK_REGISTER), "a saída não decrementa (LR <= decremento)");
        assertEquals(FpscrRegister.LTPSIZE_NONE, core.fpscr().ltpsize(), "saída de LETP restaura LTPSIZE=4");
    }

    @Test
    void letpForeverBitCombinationIsUndefined() {
        DecodedInstruction decoded = new Thumb2LowOverheadBranchDecoder(ArmArchitecture.ARMV8_1M_MVE)
                .tryDecode(0xF03FC001 | 6, CODE_BASE, Condition.AL);
        assertNull(decoded, "f=1 com tp=1 é UNDEFINED");
    }

    @Test
    void lctpRestoresLtpsizeAndOnlyThat() {
        ArmCore core = newMveCore();
        core.fpscr().setLtpsize(1);
        core.setRegister(LINK_REGISTER, 0x55);
        put32(core, CODE_BASE, 0xF00FE001);

        core.step();

        assertEquals(CODE_BASE + 4, core.programCounter());
        assertEquals(FpscrRegister.LTPSIZE_NONE, core.fpscr().ltpsize());
        assertEquals(0x55, core.register(LINK_REGISTER));
    }

    @Test
    void tailPredicatedFormsNeedMve() {
        for (int raw : new int[] {0xF012E001 | (1 << 20), 0xF00FE001, 0xF01FC007, 0xF001E801}) {
            DecodedInstruction decoded = new Thumb2LowOverheadBranchDecoder(ArmArchitecture.ARMV8_1M)
                    .tryDecode(raw, CODE_BASE, Condition.AL);
            assertNull(decoded, "sem MVE não decodifica: 0x" + Integer.toHexString(raw));
        }
    }

    @Test
    void loopRegistersSpAndPcAreUndefined() {
        for (int rn : new int[] {13, 15}) {
            DecodedInstruction dls = new Thumb2LowOverheadBranchDecoder(ArmArchitecture.ARMV8_1M_MVE)
                    .tryDecode(0xF040E001 | (rn << 16), CODE_BASE, Condition.AL);
            if (rn == 13) {
                assertEquals(InstructionKind.UNIMPLEMENTED, dls.kind());
            }
            DecodedInstruction vctp = new Thumb2LowOverheadBranchDecoder(ArmArchitecture.ARMV8_1M_MVE)
                    .tryDecode(0xF000E801 | (rn << 16), CODE_BASE, Condition.AL);
            assertEquals(InstructionKind.UNIMPLEMENTED, vctp.kind());
        }
    }

    // ── VCTP ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void vctpMasksTheFirstElementsBytes() {
        int[][] cases = {
                // {size, Rn value, expected P0}
                {0, 5, 0x001F}, // 5 elementos de 8 bits = 5 bytes
                {2, 3, 0x0FFF}, // 3 elementos de 32 bits = 12 bytes
                {2, 4, 0xFFFF}, // exatamente um vetor
                {2, 5, 0xFFFF}, // mais que um vetor: satura em 16 bytes
                {1, 0, 0x0000}, // zero elementos
        };
        for (int[] c : cases) {
            ArmCore core = newMveCore();
            core.setRegister(1, c[1]);
            put32(core, CODE_BASE, 0xF000E801 | (c[0] << 20) | (1 << 16));

            core.step();

            assertEquals(c[2], core.vpr().p0(), "VCTP size=" + c[0] + " Rn=" + c[1]);
            assertEquals(CODE_BASE + 4, core.programCounter());
        }
    }

    @Test
    void vctpIsMaskedByTheLoopTailPredicateAlreadyInForce() {
        ArmCore core = newMveCore();
        core.setRegister(2, 2); // DLSTP.32: sobram 2 elementos de 32 bits = 8 bytes ativos
        core.setRegister(1, 16);
        put32(core, CODE_BASE, 0xF000E001 | (2 << 20) | (2 << 16)); // DLSTP.32 R2
        put32(core, CODE_BASE + 4, 0xF000E801 | (0 << 20) | (1 << 16)); // VCTP.8 R1 (16 bytes)

        core.step();
        core.step();

        assertEquals(0x00FF, core.vpr().p0(), "VCTP também obedece a máscara de cauda do LTPSIZE/LR correntes");
    }

    @Test
    void vctpOnlyRewritesBeatsNotYetExecutedByEci() {
        ArmCore core = newMveCore();
        core.vpr().setP0(0xFFFF);
        core.cpsr().setEci(MveVptState.ECI_A0); // beat 0 (bytes 0-3) já executado
        core.setRegister(1, 0);
        put32(core, CODE_BASE, 0xF000E801 | (1 << 16)); // VCTP.8 R1 (zero elementos)

        core.step();

        assertEquals(0x000F, core.vpr().p0(), "só os bytes dos beats pendentes são zerados");
    }

    @Test
    void vctpFaultsOnReservedEciWithoutTouchingVpr() {
        ArmCore core = newMveCore();
        ((TestAddressSpace) core.memory()).put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setEci(3); // valor reservado (mve_eci_check)
        core.vpr().setP0(0xFFFF);

        boolean pcChanged = new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(
                ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new dev.vitorsilverio.armjitter.ir.IrOp.Vctp(1, 0, Condition.AL),
                        core.programCounter());

        assertEquals(true, pcChanged);
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(0xFFFF, core.vpr().p0());
    }

    @Test
    void refusedLoopEncodings() {
        Thumb2LowOverheadBranchDecoder mve = new Thumb2LowOverheadBranchDecoder(ArmArchitecture.ARMV8_1M_MVE);
        int[] refused = {
                0xF040C001 | (13 << 16),                    // WLS Rn=SP
                0xF000C001 | (1 << 20) | (13 << 16),        // WLSTP Rn=SP
                0xF000E001 | (1 << 20) | (13 << 16),        // DLSTP Rn=SP
                0xF00FE001 | (1 << 20),                     // Rn=15 só é LCTP com size=0
        };
        for (int raw : refused) {
            assertEquals(InstructionKind.UNIMPLEMENTED, mve.tryDecode(raw, CODE_BASE, Condition.AL).kind(),
                    "0x" + Integer.toHexString(raw));
        }
        // Prefixo tail-predicated sem forma conhecida na metade baixa.
        assertNull(mve.tryDecode(0xF000E401, CODE_BASE, Condition.AL));
    }

    /// G4: com a condição falsa (IT NE + Z=1) nenhuma das instruções novas tem efeito, mas o PC avança.
    @Test
    void newInstructionsAreNoOpsWhenTheirConditionFails() {
        int itNe = 0xBF18;
        int[] raws = {
                0xF042E001,                                   // DLS R2
                0xF042E001 | (1 << 20),                       // DLSTP.16 R2
                0xF00FC001 | (3 << 1),                        // LE
                0xF01FC001 | (3 << 1),                        // LETP
                0xF00FE001,                                   // LCTP
                0xF001E801,                                   // VCTP.8 R1
                0xE89F0000 | 1,                               // CLRM {R0}
        };
        for (int raw : raws) {
            ArmCore core = newMveCore();
            core.cpsr().setNzcv(false, true, false, false);
            core.setRegister(0, 5);
            core.setRegister(1, 3);
            core.setRegister(2, 9);
            core.setRegister(LINK_REGISTER, 0x40);
            core.fpscr().setLtpsize(1);
            core.vpr().setP0(0xFFFF);
            put16(core, CODE_BASE, itNe);
            put32(core, CODE_BASE + 2, raw);

            core.step();
            core.step();

            String id = "0x" + Integer.toHexString(raw);
            assertEquals(CODE_BASE + 6, core.programCounter(), id);
            assertEquals(0x40, core.register(LINK_REGISTER), id);
            assertEquals(5, core.register(0), id);
            assertEquals(1, core.fpscr().ltpsize(), id);
            assertEquals(0xFFFF, core.vpr().p0(), id);
        }
    }

    /// Os IrOp novos são interpretados apenas (sem emissão nativa), e o `executeOp` por padrão de
    /// tipo (usado no fallback por op do ASM) os executa igual ao despacho por `Kind`.
    @Test
    void newOpsAreInterpretedOnlyAndRunThroughExecuteOp() {
        dev.vitorsilverio.armjitter.ir.IrOp lctp =
                new dev.vitorsilverio.armjitter.ir.IrOp.LoopClearTailPredication(Condition.AL);
        dev.vitorsilverio.armjitter.ir.IrOp vctp = new dev.vitorsilverio.armjitter.ir.IrOp.Vctp(1, 0, Condition.AL);
        dev.vitorsilverio.armjitter.ir.IrOp clrm = new dev.vitorsilverio.armjitter.ir.IrOp.ClearMultiple(1, Condition.AL);
        for (dev.vitorsilverio.armjitter.ir.IrOp op : new dev.vitorsilverio.armjitter.ir.IrOp[] {lctp, vctp, clrm}) {
            assertFalse(dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy.supports(op), op.toString());
        }
        ArmCore core = newMveCore();
        core.fpscr().setLtpsize(2);
        core.setRegister(0, 7);
        var executor = new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE);
        assertFalse(executor.executeOp(core, lctp, CODE_BASE));
        assertEquals(FpscrRegister.LTPSIZE_NONE, core.fpscr().ltpsize());
        assertFalse(executor.executeOp(core, clrm, CODE_BASE));
        assertEquals(0, core.register(0));
    }

    @Test
    void pureLoopStartConstructorKeepsLtpsizeUntouched() {
        var pure = new dev.vitorsilverio.armjitter.ir.IrOp.LoopStart(2, 0, false, Condition.AL);
        assertEquals(dev.vitorsilverio.armjitter.ir.IrOp.LoopStart.NO_LTPSIZE, pure.ltpsize());
        var pureEnd = new dev.vitorsilverio.armjitter.ir.IrOp.LoopEnd(0, false, Condition.AL);
        assertFalse(pureEnd.tailPredicated());
    }

    // ── BF* ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void branchFutureFormsAreNops() {
        int boff = 1 << 23;
        for (int raw : new int[] {0xF000C001 | boff, 0xF000E001 | boff, 0xF040E001 | boff, 0xF060E001 | boff}) {
            ArmCore core = newMveCore();
            core.setRegister(0, 0xAB);
            put32(core, CODE_BASE, raw);

            core.step();

            assertEquals(CODE_BASE + 4, core.programCounter(), "BF* não desvia: 0x" + Integer.toHexString(raw));
            assertEquals(0xAB, core.register(0));
        }
    }

    @Test
    void branchFutureNeedsLowOverheadBranch() {
        DecodedInstruction decoded = new Thumb2LowOverheadBranchDecoder(ArmArchitecture.ARMV8M_MAINLINE)
                .tryDecode(0xF000C001 | (1 << 23), CODE_BASE, Condition.AL);
        assertNull(decoded);
    }

    // ── CLRM ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void clrmZeroesOnlyTheListedRegisters() {
        ArmCore core = newCore(ArmArchitecture.ARMV8_1M);
        core.setRegister(0, 1);
        core.setRegister(1, 2);
        core.setRegister(2, 3);
        core.setRegister(LINK_REGISTER, 9);
        put32(core, CODE_BASE, 0xE89F0000 | (1 << 0) | (1 << 2) | (1 << 14));

        core.step();

        assertEquals(0, core.register(0));
        assertEquals(2, core.register(1), "R1 fora da lista");
        assertEquals(0, core.register(2));
        assertEquals(0, core.register(LINK_REGISTER));
        assertEquals(CODE_BASE + 4, core.programCounter());
    }

    @Test
    void clrmWithApsrBitClearsFlagsSaturationAndGe() {
        ArmCore core = newCore(ArmArchitecture.ARMV8_1M);
        core.cpsr().setNzcv(true, true, true, true);
        core.cpsr().setSaturation(true);
        core.cpsr().setGe(0xF);
        put32(core, CODE_BASE, 0xE89F0000 | (1 << 15));

        core.step();

        assertFalse(core.cpsr().negative() || core.cpsr().zero() || core.cpsr().carry() || core.cpsr().overflow());
        assertFalse(core.cpsr().saturation());
        assertEquals(0, core.cpsr().ge());
    }

    @Test
    void clrmWithSpOrEmptyListIsUndefined() {
        for (int list : new int[] {0, 1 << 13}) {
            DecodedInstruction decoded = new Thumb2LoadStoreDecoder(ArmArchitecture.ARMV8_1M)
                    .tryDecode(0xE89F0000 | list, CODE_BASE, Condition.AL);
            assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
        }
    }

    @Test
    void clrmNeedsTheSecurityExtensionAndLeavesLdmAlone() {
        // Sem M_PROFILE_SECURITY o encoding NÃO é CLRM (cai no caminho normal de LDM.W).
        DecodedInstruction withoutSecurity = new Thumb2LoadStoreDecoder(ArmArchitecture.ARMV7M)
                .tryDecode(0xE89F0005, CODE_BASE, Condition.AL);
        if (withoutSecurity != null) {
            assertFalse(withoutSecurity.kind() == InstructionKind.CLEAR_MULTIPLE);
        }
        // `LDM.W R1!, {R0, R2}` continua LDM sob o preset que TEM CLRM.
        DecodedInstruction ldm = new Thumb2LoadStoreDecoder(ArmArchitecture.ARMV8_1M)
                .tryDecode(0xE8B10005, CODE_BASE, Condition.AL);
        assertNotNull(ldm);
        assertEquals(InstructionKind.LOAD_MULTIPLE, ldm.kind());
    }
}
