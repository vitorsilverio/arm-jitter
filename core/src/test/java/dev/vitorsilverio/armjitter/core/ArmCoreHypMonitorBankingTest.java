package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/// B9.8.1: `CpuMode.HYP`/`CpuMode.MONITOR` reconhecidos e bancados corretamente por {@link ArmCore}.
class ArmCoreHypMonitorBankingTest {

    @Test
    void cpuModeFromBitsRecognizesHypAndMonitorInsteadOfThrowing() {
        assertEquals(CpuMode.HYP, CpuMode.fromBits(0b11010));
        assertEquals(CpuMode.MONITOR, CpuMode.fromBits(0b10110));
    }

    @Test
    void spInHypModeIsBankedSeparatelyFromEveryOtherMode() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(13, 0x1000);
        core.switchMode(CpuMode.HYP);
        core.setRegister(13, 0x2000);
        core.switchMode(CpuMode.SUPERVISOR);

        assertEquals(0x1000, core.register(13));
        assertEquals(0x2000, core.bankedRegister(CpuMode.HYP, 13));
    }

    @Test
    void lrInHypModeIsTheSameSharedRegisterAsUsrAndSys() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.switchMode(CpuMode.USER);
        core.setRegister(14, 0xAAAA);
        core.switchMode(CpuMode.HYP);

        // LR não é bancado por Hyp mode: o valor escrito em USER continua visível aqui.
        assertEquals(0xAAAA, core.register(14));

        core.setRegister(14, 0xBBBB);
        core.switchMode(CpuMode.SYSTEM);

        // E a escrita feita EM Hyp mode "vaza" de volta para o banco usr/sys compartilhado.
        assertEquals(0xBBBB, core.register(14));
    }

    @Test
    void elrHypIsAStandaloneRegisterNotConfusedWithLr() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.switchMode(CpuMode.HYP);
        core.setRegister(14, 0x1111);
        core.setElrHyp(0x2222);
        core.switchMode(CpuMode.SYSTEM);
        core.switchMode(CpuMode.HYP);

        assertEquals(0x1111, core.register(14));
        assertEquals(0x2222, core.elrHyp());
    }

    @Test
    void spAndLrInMonitorModeAreBankedNormally() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(13, 0x1000);
        core.setRegister(14, 0x1004);

        core.switchMode(CpuMode.MONITOR);
        core.setRegister(13, 0x3000);
        core.setRegister(14, 0x3004);

        core.switchMode(CpuMode.SUPERVISOR);
        assertEquals(0x1000, core.register(13));
        assertEquals(0x1004, core.register(14));

        core.switchMode(CpuMode.MONITOR);
        assertEquals(0x3000, core.register(13));
        assertEquals(0x3004, core.register(14));
    }

    @Test
    void hypAndMonitorSpsrAreIndependentFromEveryOtherSpsr() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.setSpsr(CpuMode.SUPERVISOR, 0x10);
        core.setSpsr(CpuMode.HYP, 0x20);
        core.setSpsr(CpuMode.MONITOR, 0x30);

        assertEquals(0x10, core.spsr(CpuMode.SUPERVISOR));
        assertEquals(0x20, core.spsr(CpuMode.HYP));
        assertEquals(0x30, core.spsr(CpuMode.MONITOR));
    }

    @Test
    void saveAndLoadStateRoundTripsTheHypAndMonitorBanks() throws IOException {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.switchMode(CpuMode.HYP);
        core.setRegister(13, 0x4000);
        core.setElrHyp(0x4004);
        core.setSpsr(CpuMode.HYP, 0x4008);
        core.switchMode(CpuMode.MONITOR);
        core.setRegister(13, 0x5000);
        core.setRegister(14, 0x5004);
        core.setSpsr(CpuMode.MONITOR, 0x5008);
        core.switchMode(CpuMode.SYSTEM);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        core.saveState(new DataOutputStream(buffer));

        ArmCore restored = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(0x4000, restored.bankedRegister(CpuMode.HYP, 13));
        assertEquals(0x4004, restored.elrHyp());
        assertEquals(0x4008, restored.spsr(CpuMode.HYP));
        assertEquals(0x5000, restored.bankedRegister(CpuMode.MONITOR, 13));
        assertEquals(0x5004, restored.bankedRegister(CpuMode.MONITOR, 14));
        assertEquals(0x5008, restored.spsr(CpuMode.MONITOR));
    }

    @Test
    void loadingAPreB981StreamZeroesTheHypAndMonitorBanksInsteadOfThrowing() throws IOException {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.setRegister(0, 42);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeLegacyPreB981State(core, new DataOutputStream(buffer));

        ArmCore restored = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        restored.setElrHyp(0x1234); // valor não-zero antes de carregar, prova que o reset ocorreu
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(42, restored.register(0));
        assertEquals(0, restored.elrHyp());
        assertEquals(0, restored.bankedRegister(CpuMode.HYP, 13));
        assertEquals(0, restored.spsr(CpuMode.HYP));
        assertEquals(0, restored.bankedRegister(CpuMode.MONITOR, 13));
        assertEquals(0, restored.spsr(CpuMode.MONITOR));
    }

    /// Reproduz o layout de {@code ArmCore.saveState} da versão 2 (B3.3, sem banco Hyp/Monitor).
    private static void writeLegacyPreB981State(ArmCore core, DataOutputStream out) throws IOException {
        out.writeInt(2); // STATE_VERSION legado
        for (int i = 0; i < 16; i++) {
            out.writeInt(core.register(i));
        }
        for (int i = 0; i < 5; i++) {
            out.writeInt(0); // commonR8ToR12
        }
        for (int i = 0; i < 2; i++) {
            out.writeInt(0); // userSystemSpLr
        }
        for (int i = 0; i < 7; i++) {
            out.writeInt(0); // fiqR8ToR14
        }
        for (int i = 0; i < 2; i++) {
            out.writeInt(0); // irqSpLr
        }
        for (int i = 0; i < 2; i++) {
            out.writeInt(0); // supervisorSpLr
        }
        for (int i = 0; i < 2; i++) {
            out.writeInt(0); // abortSpLr
        }
        for (int i = 0; i < 2; i++) {
            out.writeInt(0); // undefinedSpLr
        }
        out.writeInt(0); // supervisorSpsr
        out.writeInt(0); // irqSpsr
        out.writeInt(0); // fiqSpsr
        out.writeInt(0); // abortSpsr
        out.writeInt(0); // undefinedSpsr
        out.writeInt(core.cpsr().get());
        out.writeLong(core.cycles());
        out.writeBoolean(core.interruptLine());
        out.writeInt(core.mode().ordinal());
        out.writeInt(core.sleepState().ordinal());
        core.vfp().saveState(out);
        core.fpscr().saveState(out);
    }
}
