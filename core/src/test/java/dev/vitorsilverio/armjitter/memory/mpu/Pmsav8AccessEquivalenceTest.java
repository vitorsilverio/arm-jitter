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

/// B20.7 (mesmo Aceite "Equivalência G1" de B20.3): o mesmo bloco IR roda pelo {@link
/// dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter} (oráculo) e pelo {@link
/// AsmCodeEmitter} (JIT nativo) sobre cores independentes com o MESMO {@link Pmsav8AddressSpace},
/// provando que os dois catches de `Pmsav8AccessException` produzem o MESMO estado observável —
/// espelha `PmsaAccessEquivalenceTest` (B20.3), trocando PMSAv7 por PMSAv8-32. **Achado da auditoria
/// de cobertura JaCoCo pós-B20.7**: este espelho nunca tinha sido escrito — o handler nativo de
/// `Pmsav8AccessException` em `AsmBlockCompiler` nunca foi validado contra o oráculo interpretado
/// (G1), só contra si mesmo (ver `ArmCorePmsav8AbortTest`).
///
/// **Diferença de desenho em relação ao precedente PMSAv7**: lá, a região que nega o acesso VENCE
/// por sobreposição com uma região de RWX plena (Achado da B20.3: índice maior ganha). Em PMSAv8
/// isso não existe — sobreposição SEMPRE falha (Achado 2 da B20.7). **Segunda diferença**: o `AP`
/// de 2 bits do PMSAv8 (ao contrário do `AP` de 3 bits do PMSAv7) não tem combinação "sem acesso
/// algum" — código privilegiado sempre lê/escreve através de QUALQUER `AP`, `AP` só distingue
/// RO/RW e privilegiado-só/priv+user (`Pmsav8AddressSpace#checkRegionPermission`). Por isso, em vez
/// de uma segunda região "sem acesso" (que não existe neste modelo), a falta aqui vem de um
/// endereço que não casa com NENHUMA região — mesmo desenho de `ArmCorePmsav8AbortTest` (região
/// única cobrindo só código/pilha, alvo da falta fora dela, mapa de fundo desligado).
class Pmsav8AccessEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV8R_32);

    private static ArmCore pmsav8Core(TestAddressSpace physical) {
        Pmsav8MpuRegisters mpu = new Pmsav8MpuRegisters(1, 1);
        // Única região: [0x0000, 0x0FFF] (4KiB), RWX plena — cobre código/pilha do teste. O alvo da
        // falta (0x1000) fica fora dela; mapa de fundo continua desligado (default) — PERMISSION
        // garantido, sem depender de AP "sem acesso" (que não existe no PMSAv8, ver Javadoc acima).
        mpu.setPrselr(0);
        mpu.setPrbar(0x0000 | (0b01 << 1)); // AP=0b01 (RW priv+user), XN=0
        mpu.setPrlar(0x0FFF | 0b1); // limite, EN=1
        Pmsav8AddressSpace space = new Pmsav8AddressSpace(physical, mpu);
        ArmCore core = new ArmCore(space, SwiDispatcher.empty(), ArmArchitecture.ARMV8R_32);
        Cp15Pmsav8MpuCoprocessor cp15 = new Cp15Pmsav8MpuCoprocessor(mpu, core, ArmArchitecture.ARMV8R_32);
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
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV8R_32), new StandardIrBuilder())
                .lift(physical, 0, 1);
        assertTrue(asmEmitter.isNativeSupported(block));

        assertBlockEquivalent(asmEmitter, block, () -> {
            ArmCore reference = pmsav8Core(physical.copy());
            ArmCore candidate = pmsav8Core(physical.copy());
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
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV8R_32), new StandardIrBuilder())
                .lift(physical, 0, 1);
        assertTrue(asmEmitter.isNativeSupported(block));

        assertBlockEquivalent(asmEmitter, block, () -> {
            ArmCore reference = pmsav8Core(physical.copy());
            ArmCore candidate = pmsav8Core(physical.copy());
            reference.setRegister(4, 0x0FF8); // duas words antes da região negada (0x1000)
            candidate.setRegister(4, 0x0FF8);
            return new EquivalencePair(reference, candidate);
        });
    }
}
