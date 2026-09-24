package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B20.5: `AProfileExceptionModel(false)` — o gate de Hyp/Monitor do perfil R. O decoder já
/// recusa `HVC`/`SMC` sob `ARMV7R` (B20.1); estes testes cobrem o caminho que o decode não
/// alcança, a chamada direta de {@link ArmCore#requestException}.
class AProfileExceptionModelRProfileGateTest {

    private static ArmCore newRProfileCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.setExceptionModel(new AProfileExceptionModel(false));
        return core;
    }

    @Test
    void hvcRequestedDirectlyIsDowngradedToUndefinedInsteadOfEnteringHyp() {
        ArmCore core = newRProfileCore();

        core.requestException(ArmException.HVC);

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void smcRequestedDirectlyIsDowngradedToUndefinedInsteadOfEnteringMonitor() {
        ArmCore core = newRProfileCore();

        core.requestException(ArmException.SMC);

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
    }

    @Test
    void everyOtherExceptionBehavesIdenticallyToTheAProfileDefault() {
        ArmCore aProfileCore = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        ArmCore rProfileCore = newRProfileCore();

        for (ArmException exception : new ArmException[] {
                ArmException.RESET, ArmException.UNDEFINED, ArmException.SWI,
                ArmException.PREFETCH_ABORT, ArmException.DATA_ABORT, ArmException.IRQ,
                ArmException.FIQ}) {
            aProfileCore.requestException(exception);
            rProfileCore.requestException(exception);

            assertEquals(aProfileCore.mode(), rProfileCore.mode(), exception.toString());
            assertEquals(aProfileCore.programCounter(), rProfileCore.programCounter(), exception.toString());
        }
    }

    @Test
    void noArgConstructorPreservesTheDefaultAProfileBehavior() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.setExceptionModel(new AProfileExceptionModel());

        core.requestException(ArmException.HVC);

        assertEquals(CpuMode.HYP, core.mode());
        assertEquals(0x14, core.programCounter());
    }

    @Test
    void highVectorsStillRelocatesTheUndefinedVectorUnderTheRProfileGate() {
        ArmCore core = newRProfileCore();
        core.setHighVectors(true);

        core.requestException(ArmException.SMC);

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0xFFFF_0004, core.programCounter());
    }
}
