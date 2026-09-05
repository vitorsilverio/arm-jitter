package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// C12.7 — os 20 records que a `AsmNativePolicy` passou a aceitar (todos mexem em estado
/// global — modo/banco/CPSR/IT/exceção de guest — ou são raros demais para valer bytecode direto
/// dedicado). Cada um é emitido via {@link dev.vitorsilverio.armjitter.codegen.jvm.IrOpInterop}
/// (o MESMO interpretado que já é o oráculo G1), cercado de flush/reload do register cache —
/// mesmo mecanismo já usado por `PsrTransfer`/`Coprocessor`/`Undefined` (ver
/// `AsmBlockCompiler#emitSpilled`). O ganho não é acelerar estas 20 ops — é parar de derrubar o
/// BLOCO INTEIRO para o interpretado só por conterem uma delas.
///
/// Cada teste prova (1) que {@code isNativeSupported} agora aceita o bloco e (2) equivalência
/// byte-a-byte contra o interpretado (G1), incluindo a borda citada no Aceite da task. O teste
/// {@link #registerCacheFlushesBeforeHelperAndReloadsAfter()} é o que pega a classe de bug mais
/// provável desta task (Armadilha 1): esquecer o flush/reload em volta do helper.
class C127NativeEquivalenceTest {
    private static final int COND_AL = 0xE;
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();

    private static IrBlock liftArm(ArmArchitecture architecture, TestAddressSpace memory, int count) {
        return new StandardIrBlockLifter(new ArmDecoder(architecture), new StandardIrBuilder())
                .lift(memory, 0, count);
    }

    private static IrBlock liftThumb(ArmArchitecture architecture, TestAddressSpace memory, int count) {
        return new StandardIrBlockLifter(new ThumbDecoder(architecture), new StandardIrBuilder())
                .lift(memory, 0, count);
    }

    // ── Swap (SWP/SWPB) ──────────────────────────────────────────────────────────

    @Test
    void swapWithDestinationEqualToBaseIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV4T);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV4T);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE1030091); // SWP r0, r1, [r3] -- Rd==Rn (base), leitura ANTES da escrita
        IrBlock block = liftArm(ArmArchitecture.ARMV4T, memory, 1);

        assertTrue(new AsmCodeEmitter(ArmArchitecture.ARMV4T).isNativeSupported(block),
                "SWP ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(3, 0x10);
                    core.setRegister(1, 0xCAFEBABE);
                    core.memory().write32(0x10, 0x1234);
                }));
    }

    // ── ChangeProcessorState / SetEndianness / WaitForInterrupt (ARMv6, B1.5) ────

    private static int cps(int imod, boolean modeChange, boolean a, boolean i, boolean f, int mode) {
        return 0xF100_0000 | (imod << 18) | (modeChange ? 1 << 17 : 0)
                | (a ? 1 << 8 : 0) | (i ? 1 << 7 : 0) | (f ? 1 << 6 : 0) | (mode & 0x1F);
    }

    private static int setend(boolean bigEndian) {
        return 0xF101_0000 | (bigEndian ? 1 << 9 : 0);
    }

    @Test
    void changeProcessorStateChangingModeAndMasksIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        // CPSID aif, #SYSTEM -- muda modo E desabilita A/I/F juntos (o caso mais perigoso: troca
        // de banco físico dos registradores seguida de leitura pelo cache).
        memory.put32(0, cps(0b11, true, true, true, true, CpuMode.SYSTEM.bits()));
        memory.put32(4, 0xE3A00042); // MOV r0, #0x42 -- lido pelo cache DEPOIS da troca de banco
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 2);

        assertTrue(asmEmitter.isNativeSupported(block), "CPS ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.cpsr().setIrqDisabled(false);
                    core.cpsr().setFiqDisabled(false);
                }));
    }

    @Test
    void setEndiannessIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, setend(true)); // SETEND BE
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "SETEND ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void waitForInterruptHaltsRatherThanBecomingNop() {
        // Não pode virar NOP (armadilha 4): o estado de sleep observável tem que bater.
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE320_F003); // WFI
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "WFI ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.cpsr().setIrqDisabled(false);
                }));
    }

    // ── StoreReturnState / ReturnFromException (SRS/RFE, ARMv6, B1.5) ────────────

    private static int srs(boolean p, boolean u, boolean w, int mode) {
        return 0xF84D_0500 | (p ? 1 << 24 : 0) | (u ? 1 << 23 : 0) | (w ? 1 << 21 : 0) | (mode & 0x1F);
    }

    private static int rfe(boolean p, boolean u, boolean w, int rn) {
        return 0xF810_0A00 | (p ? 1 << 24 : 0) | (u ? 1 << 23 : 0) | (w ? 1 << 21 : 0) | (rn << 16);
    }

    @Test
    void storeReturnStateWritesToTargetModeBankIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, srs(true, true, true, CpuMode.SYSTEM.bits())); // SRSIB SYS!
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "SRS ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.switchMode(CpuMode.IRQ);
                    core.setBankedRegister(CpuMode.SYSTEM, 13, 0x20);
                    core.setRegister(14, 0xCAFEBABE);
                    core.setSpsr(CpuMode.IRQ, 0x6000_0012);
                }));
    }

    @Test
    void returnFromExceptionRestoresModeAndPcIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, rfe(true, true, false, 0)); // RFEIB r0
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "RFE ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x20);
                    core.memory().write32(0x24, 0xCAFEBABE); // PC de retorno (RFEIB: base+4)
                    core.memory().write32(0x28, CpuMode.IRQ.bits());
                }));
    }

    // ── Hvc / Smc / Eret / MrsBank / MsrBank (B9.8, virtualização) ───────────────

    @Test
    void hvcEntersHypModeIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE141_2374); // HVC #0x1234
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "HVC ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void smcEntersMonitorModeIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE160_0075); // SMC #5
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "SMC ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> { }));
    }

    @Test
    void eretReturnsViaElrHypIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE160_006E); // ERET
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "ERET ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.switchMode(CpuMode.HYP);
                    core.setElrHyp(0x9000);
                    core.setSpsr(CpuMode.HYP, (core.cpsr().get() & ~0x1F) | CpuMode.SUPERVISOR.bits());
                }));
    }

    @Test
    void mrsBankReadsOtherModeBankIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE100_0200); // MRS r0, R8_usr
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "MRS bancado ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setBankedRegister(CpuMode.USER, 8, 0xCAFEBABE);
                }));
    }

    @Test
    void msrBankWritesOtherModeBankIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE125_F200); // MSR SP_usr, r0
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "MSR bancado ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0xCAFEBABE);
                }));
    }

    // ── SetItState / CompareBranchZero / TableBranch (Thumb-2, B2.4) ─────────────

    private static final ArmArchitecture THUMB2_ARCH = ArmArchitecture.ARMV6K_THUMB2;

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

    @Test
    void setItStateSurvivesAcrossTheCoveredInstructionIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, it(0x0, 0b1000)); // IT EQ (1 instrução coberta)
        memory.put16(2, 0x2064);          // MOVEQ r0, #0x64 -- guard consulta o ITSTATE gravado
        IrBlock block = liftThumb(THUMB2_ARCH, memory, 2);

        assertTrue(asmEmitter.isNativeSupported(block), "IT (SetItState) ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.cpsr().setThumbMode(true);
                    core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeira
                }));
    }

    @Test
    void compareBranchZeroTakenAndNotTakenAreNativeAndMatchInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);
        for (boolean nonZero : new boolean[]{false, true}) {
            for (int r0 : new int[]{0, 5}) {
                TestAddressSpace memory = new TestAddressSpace(32);
                memory.put16(0, cbz(nonZero, 10, 0)); // CBZ/CBNZ r0, +10
                memory.put16(2, 0x46C0);               // NOP (MOV r8,r8) preenchendo o fall-through
                IrBlock block = liftThumb(THUMB2_ARCH, memory, 2);

                assertTrue(asmEmitter.isNativeSupported(block),
                        "CBZ/CBNZ ganhou emissão nativa na task C12.7");
                int register = r0;
                harness.assertEquivalent(reference, asmEmitter, block,
                        EquivalenceTestSupport.independentPair(memory, core -> {
                            core.cpsr().setThumbMode(true);
                            core.setRegister(0, register);
                        }));
            }
        }
    }

    @Test
    void tableBranchByteAndHalfwordExtremesAreNativeAndMatchInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);

        // TBB [r0, r1] com índice no extremo (byte=0xFF).
        TestAddressSpace tbbMemory = new TestAddressSpace(1024);
        int[] tbb = tableBranch(0, 1, false);
        tbbMemory.put16(0, tbb[0]);
        tbbMemory.put16(2, tbb[1]);
        tbbMemory.write8(4 + 0xFF, 4); // tabela: índice 0xFF -> desvio de 2*4 bytes
        IrBlock tbbBlock = liftThumb(THUMB2_ARCH, tbbMemory, 1);
        assertTrue(asmEmitter.isNativeSupported(tbbBlock), "TBB ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, tbbBlock,
                EquivalenceTestSupport.independentPair(tbbMemory, core -> {
                    core.cpsr().setThumbMode(true);
                    core.setRegister(0, 4); // base da tabela = pc+4
                    core.setRegister(1, 0xFF);
                }));

        // TBH [r0, r1, LSL #1] com índice no extremo (halfword=0xFFFF).
        TestAddressSpace tbhMemory = new TestAddressSpace(0x2_0010);
        int[] tbh = tableBranch(0, 1, true);
        tbhMemory.put16(0, tbh[0]);
        tbhMemory.put16(2, tbh[1]);
        tbhMemory.put16(4 + 2 * 0xFFFF, 4);
        IrBlock tbhBlock = liftThumb(THUMB2_ARCH, tbhMemory, 1);
        assertTrue(asmEmitter.isNativeSupported(tbhBlock), "TBH ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, tbhBlock,
                EquivalenceTestSupport.independentPair(tbhMemory, core -> {
                    core.cpsr().setThumbMode(true);
                    core.setRegister(0, 4);
                    core.setRegister(1, 0xFFFF);
                }));
    }

    // ── MProfileSystemRegister (MRS/MSR SYSm, B7.4) ──────────────────────────────

    @Test
    void mProfileSystemRegisterMspRoundTripIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7M);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7M);
        TestAddressSpace memory = new TestAddressSpace(32);
        // MSR PRIMASK, r0 ; MRS r1, PRIMASK -- hi=0xF3EF/0xF380|Rn, lo=0x8000|(Rd<<8)|SYSm.
        memory.put16(0, 0xF380);
        memory.put16(2, 0x8800 | MProfileExceptionModel.SYSM_PRIMASK);
        memory.put16(4, 0xF3EF);
        memory.put16(6, 0x8100 | MProfileExceptionModel.SYSM_PRIMASK);
        IrBlock block = liftThumb(ArmArchitecture.ARMV7M, memory, 2);

        assertTrue(asmEmitter.isNativeSupported(block),
                "MRS/MSR SYSm (perfil M) ganharam emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setExceptionModel(new MProfileExceptionModel());
                    core.cpsr().setThumbMode(true);
                    core.setRegister(0, 1);
                }));
    }

    // ── VfpCorePairTransferSingle / VfpConvertFixed (VFPv3, B9.5) ────────────────

    private static int nibbleOf(int combined, boolean doublePrecision) {
        return doublePrecision ? combined & 0xF : combined >>> 1;
    }

    private static int extOf(int combined, boolean doublePrecision) {
        return doublePrecision ? (combined >>> 4) & 1 : combined & 1;
    }

    private static int vmov64SpWord(boolean toArmRegisters, int rt, int rt2, int vm) {
        int word = (COND_AL << 28) | (0xC << 24) | (0b010 << 21) | (toArmRegisters ? 1 : 0) << 20;
        word |= rt2 << 16;
        word |= rt << 12;
        word |= 0xA << 8;
        word |= extOf(vm, false) << 5;
        word |= 1 << 4;
        word |= nibbleOf(vm, false);
        return word;
    }

    private static int vfpConvertFixedWord(boolean toFixedPoint, boolean unsignedFixedPoint, boolean is32Bit,
            int imm5, boolean doublePrecision, int vd) {
        int word = (COND_AL << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd, doublePrecision) << 22;
        word |= 1 << 19;
        word |= (toFixedPoint ? 1 : 0) << 18;
        word |= 1 << 17;
        word |= (unsignedFixedPoint ? 1 : 0) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= 0xA << 8;
        word |= (is32Bit ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= ((imm5 >>> 4) & 1) << 5;
        word |= (imm5 & 0xF);
        return word;
    }

    @Test
    void vfpCorePairTransferSingleBothDirectionsAreNativeAndMatchInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, vmov64SpWord(true, 1, 2, 8)); // VMOV r1, r2, S8, S9
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block),
                "VMOV_64_sp ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.vfp().setS(8, 0x1111_1111);
                    core.vfp().setS(9, 0x2222_2222);
                }));
    }

    @Test
    void vfpConvertFixedIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7A);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);
        TestAddressSpace memory = new TestAddressSpace(32);
        // VCVT.S32.F32 (float -> fixo, com sinal, 32 bits, imm=0 -> 32 bits fracionários), S1.
        memory.put32(0, vfpConvertFixedWord(true, false, true, 0, false, 1));
        IrBlock block = liftArm(ArmArchitecture.ARMV7A, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block),
                "VCVT_fix ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.vfp().setS(1, Float.floatToRawIntBits(0.5f));
                }));
    }

    // ── DspDualMultiply / DspTopWordMultiply (ARMv6, B9.1) ───────────────────────

    private static int armDspDualMultiply(boolean subtract, boolean exchange, boolean longForm,
            int rd, int ra, int rm, int rn) {
        return (COND_AL << 28) | 0x0700_0010 | (longForm ? 1 << 22 : 0) | (subtract ? 1 << 6 : 0)
                | (exchange ? 1 << 5 : 0) | (rd << 16) | (ra << 12) | (rm << 8) | rn;
    }

    private static int armDspTopWordMultiply(boolean subtract, boolean round, int rd, int ra, int rn, int rm) {
        return (COND_AL << 28) | 0x0750_0010 | (subtract ? 1 << 6 : 0) | (round ? 1 << 5 : 0)
                | (rd << 16) | (ra << 12) | (rm << 8) | rn;
    }

    @Test
    void dspDualMultiplyWithSaturatingAccumulateIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        // SMLAD r0, r1, r2, r3 -- overflow do acumulador satura (Q sticky), mesma borda do Aceite.
        memory.put32(0, armDspDualMultiply(false, false, false, 0, 3, 2, 1));
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "SMLAD ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(1, 0x8000_8000); // dois halfwords no mínimo negativo
                    core.setRegister(2, 0x8000_8000);
                    core.setRegister(3, 0x7FFF_FFFF); // acumulador no máximo -> overflow/saturação
                }));
    }

    @Test
    void dspTopWordMultiplyRoundedIsNativeAndMatchesInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, armDspTopWordMultiply(false, true, 0, 3, 1, 2)); // SMMLAR r0, r1, r2, r3
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "SMMLAR ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(1, 0x0002_0003);
                    core.setRegister(2, 0x0005_0007);
                    core.setRegister(3, 100);
                }));
    }

    // ── Breakpoint (BKPT, B7.5) ───────────────────────────────────────────────────

    @Test
    void breakpointRaisesSameExceptionAsInterpreted() {
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(THUMB2_ARCH);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(THUMB2_ARCH);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0xBE12); // BKPT #0x12 -- sem BkptDispatcher instalado, vira UNDEFINED
        IrBlock block = liftThumb(THUMB2_ARCH, memory, 1);

        assertTrue(asmEmitter.isNativeSupported(block), "BKPT ganhou emissão nativa na task C12.7");
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.cpsr().setThumbMode(true);
                }));
    }

    // ── Cache de registradores: flush antes do helper, reload depois (armadilha 1) ─

    @Test
    void registerCacheFlushesBeforeHelperAndReloadsAfter() {
        // MOV r0, #7 (r0 escrito pelo cache) ; CPS muda de modo (invalida o banco físico de r13)
        // ; MOV r1, r13 (lido pelo cache DEPOIS da troca -- tem que ver o banco NOVO, não um valor
        // obsoleto do prólogo). Prova flush (a escrita de r0 tem que estar visível ao helper caso
        // ele leia o core, o que CPS não faz, mas o padrão é o mesmo de MSR/PsrTransfer) e reload
        // (r13 pós-CPS tem que vir do banco físico correto).
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A00007); // MOV r0, #7
        memory.put32(4, cps(0b11, true, false, false, false, CpuMode.IRQ.bits())); // CPSID , #IRQ
        memory.put32(8, 0xE1A0100D); // MOV r1, r13 -- deve ler SP_irq, não SP_svc do prólogo
        IrBlock block = liftArm(ArmArchitecture.ARMV6K, memory, 3);

        assertTrue(asmEmitter.isNativeSupported(block));
        harness.assertEquivalent(reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(13, 0xAAAA);                 // SP do modo inicial (SVC)
                    core.setBankedRegister(CpuMode.IRQ, 13, 0xBBBB); // SP_irq, banco DIFERENTE
                }));
    }
}
