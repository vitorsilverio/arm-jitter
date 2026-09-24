package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// B20.3 (Aceite "Equivalência G1"): o mesmo bloco IR roda pelo {@link
/// dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter} (oráculo) e pelo {@link
/// AsmCodeEmitter} (JIT nativo) sobre cores independentes com o MESMO {@link PmsaAddressSpace},
/// provando que os dois catches de `PmsaAccessException` (Armadilha 4 da B20.3) produzem o MESMO
/// estado observável — espelha `dev.vitorsilverio.armjitter.codegen.MemoryAbortEquivalenceTest`
/// (B4.1.3), trocando VMSA por PMSA.
class PmsaAccessEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7R);

    private static ArmCore pmsaCore(TestAddressSpace physical) {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(2);
        // Região 0 (índice menor): 8KiB cobrindo tudo, RWX plena — cobre código/registradores do
        // teste (0xFF8/0xFFC).
        mpu.setRgnr(0);
        mpu.setDrbar(0);
        mpu.setDrsr(0b1 | (12 << 1)); // EN=1, RSIZE=12 -> 8192 bytes
        mpu.setDracr(0b011 << 8); // AP=3 (RWX)
        // Região 1 (índice maior, VENCE a região 0 por sobreposição): 32 bytes em 0x1000, sem
        // acesso algum — o alvo específico da falta.
        mpu.setRgnr(1);
        mpu.setDrbar(0x1000);
        mpu.setDrsr(0b1 | (4 << 1)); // EN=1, RSIZE=4 -> 32 bytes
        mpu.setDracr(0); // AP=0, sem acesso
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        ArmCore core = new ArmCore(space, SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, core, ArmArchitecture.ARMV7R);
        mpu.setMpuEnabled(true);
        core.setCoprocessorBus(cp15);
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(space);
        return core;
    }

    @Test
    void dataAbortOnLoadMatchesBetweenInterpretedAndNativeAsm() {
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0, 0xE591_0000); // LDR r0, [r1]
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV7R), new StandardIrBuilder())
                .lift(physical, 0, 1);
        assertTrue(asmEmitter.isNativeSupported(block));

        assertBlockEquivalent(asmEmitter, block, () -> {
            ArmCore reference = pmsaCore(physical.copy());
            ArmCore candidate = pmsaCore(physical.copy());
            reference.setRegister(1, 0x1000);
            candidate.setRegister(1, 0x1000);
            reference.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
            candidate.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
            return new EquivalencePair(reference, candidate);
        });
    }

    @Test
    void ldmAbortingMidTransferMatchesBetweenInterpretedAndNativeAsm() {
        TestAddressSpace physical = new TestAddressSpace(0x2000);
        physical.put32(0, 0xE8B4_0007); // LDMIA r4!, {r0,r1,r2}
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV7R), new StandardIrBuilder())
                .lift(physical, 0, 1);
        assertTrue(asmEmitter.isNativeSupported(block));

        assertBlockEquivalent(asmEmitter, block, () -> {
            ArmCore reference = pmsaCore(physical.copy());
            ArmCore candidate = pmsaCore(physical.copy());
            reference.setRegister(4, 0x0FF8); // duas words antes da região negada (0x1000)
            candidate.setRegister(4, 0x0FF8);
            return new EquivalencePair(reference, candidate);
        });
    }
}
