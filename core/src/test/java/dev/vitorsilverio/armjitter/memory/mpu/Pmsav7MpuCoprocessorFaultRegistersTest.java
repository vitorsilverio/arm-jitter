package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B20.3: `DFSR`/`IFSR`/`DFAR`/`IFAR` (`c5,c0,{0,1}`/`c6,c0,{0,2}`) via `MCR`/`MRC` direto —
/// software pode reler o que ele mesmo escreveu, independente de qualquer abort real (mesmo
/// contrato do `Cp15VmsaCoprocessor`, ver Javadoc de `Pmsav7MpuCoprocessor`). `onDataAbort`/
/// `onPrefetchAbort` (o outro caminho de escrita, via `MemoryAbortListener`) já são cobertos por
/// `ArmCorePmsaAbortTest`.
class Pmsav7MpuCoprocessorFaultRegistersTest {
    @Test
    void dfsrAndIfsrRoundTripIndependently() {
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(new Pmsav7MpuRegisters(1), coreWithoutCode(), ArmArchitecture.ARMV7R);

        cp15.write(15, 0, 5, 0, 0, 0x0000_000D); // DFSR
        cp15.write(15, 0, 5, 0, 1, 0x0000_0800); // IFSR

        assertEquals(0x0000_000D, cp15.read(15, 0, 5, 0, 0), "DFSR");
        assertEquals(0x0000_0800, cp15.read(15, 0, 5, 0, 1), "IFSR não deve ter sido pisado por DFSR");
    }

    @Test
    void dfarAndIfarRoundTripIndependently() {
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(new Pmsav7MpuRegisters(1), coreWithoutCode(), ArmArchitecture.ARMV7R);

        cp15.write(15, 0, 6, 0, 0, 0x9000_0000); // DFAR
        cp15.write(15, 0, 6, 0, 2, 0xA000_0000); // IFAR

        assertEquals(0x9000_0000, cp15.read(15, 0, 6, 0, 0), "DFAR");
        assertEquals(0xA000_0000, cp15.read(15, 0, 6, 0, 2), "IFAR não deve ter sido pisado por DFAR");
    }

    @Test
    void handlesRecognizesFaultAddressAndFaultStatusEncodings() {
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(new Pmsav7MpuRegisters(1), coreWithoutCode(), ArmArchitecture.ARMV7R);

        assertEquals(true, cp15.handles(15, 0, 6, 0, 0), "DFAR");
        assertEquals(true, cp15.handles(15, 0, 6, 0, 2), "IFAR");
        assertEquals(true, cp15.handles(15, 0, 5, 0, 0), "DFSR");
        assertEquals(true, cp15.handles(15, 0, 5, 0, 1), "IFSR");
        assertEquals(false, cp15.handles(15, 0, 6, 0, 1), "opcode2=1 sob c6,c0 não é DFAR nem IFAR");
        assertEquals(false, cp15.handles(15, 0, 5, 1, 0), "DFSR/IFSR só existem sob CRM_PRIMARY");
        assertEquals(false, cp15.handles(15, 0, 5, 0, 2), "opcode2=2 sob c5 não é DFSR nem IFSR");
    }

    private static ArmCore coreWithoutCode() {
        ArmCore core = new ArmCore(new TestAddressSpace(0x100), SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, false, false);
        return core;
    }
}
