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

class ArmCoreVfpStateTest {
    @Test
    void saveAndLoadStateRoundTripsTheVfpBankAndFpscr() throws IOException {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.vfp().setS(3, 0xDEADBEEF);
        core.fpscr().setValue(FpscrRegister.NEGATIVE_FLAG);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        core.saveState(new DataOutputStream(buffer));

        ArmCore restored = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(0xDEADBEEF, restored.vfp().s(3));
        assertEquals(FpscrRegister.NEGATIVE_FLAG, restored.fpscr().value());
    }

    @Test
    void loadingAPreB33StreamZeroesTheVfpBankInsteadOfThrowing() throws IOException {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.setRegister(0, 42);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeLegacyPreB33State(core, new DataOutputStream(buffer));

        ArmCore restored = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        restored.vfp().setS(1, 0x1234); // valor não-zero antes de carregar, prova que o reset ocorreu
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(42, restored.register(0));
        for (int i = 0; i < VfpRegisters.SINGLE_COUNT; i++) {
            assertEquals(0, restored.vfp().s(i));
        }
        assertEquals(0, restored.fpscr().value());
    }

    /// Reproduz o layout de {@code ArmCore.saveState} anterior à B3.3 (versão 1: sem banco VFP).
    private static void writeLegacyPreB33State(ArmCore core, DataOutputStream out) throws IOException {
        out.writeInt(1); // STATE_VERSION legado
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
    }
}
