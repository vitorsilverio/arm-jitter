package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B16.1 — `ArmCore#vpr()` e o stream de save-state À PARTE (`saveStateVpr`/`loadStateVpr`), que
/// NUNCA é chamado como efeito colateral de {@link ArmCore#saveState}/{@link ArmCore#loadState}
/// (G3: mesma disciplina de {@link ArmCoreVfpStateTest} para `vfp()`/`fpscr()`, só que aqui o
/// formato principal fica byte a byte intocado — `VPR` é persistido por método novo à parte).
class ArmCoreVprStateTest {

    @Test
    void vprSavesAndLoadsThroughItsOwnStreamRoundTrip() throws IOException {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.vpr().setP0(0xBEEF);
        core.vpr().setMask01(0b0110);
        core.vpr().setMask23(0b1001);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        core.saveStateVpr(new DataOutputStream(buffer));

        ArmCore restored = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        restored.loadStateVpr(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(0xBEEF, restored.vpr().p0());
        assertEquals(0b0110, restored.vpr().mask01());
        assertEquals(0b1001, restored.vpr().mask23());
    }

    @Test
    void mainSaveStateFormatIsUntouchedByVpr() throws IOException {
        // O `VPR` NÃO participa de `saveState`/`loadState` (G3) — grava-se um valor não-zero no
        // `VPR` de um core, salva-se pelo caminho PRINCIPAL, e o `VPR` restaurado continua zerado
        // (não foi persistido ali) sem que o formato do restante mude de tamanho/layout.
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        core.vpr().setValue(-1);
        core.setRegister(0, 42);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        core.saveState(new DataOutputStream(buffer));

        ArmCore restored = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        restored.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

        assertEquals(42, restored.register(0));
        assertEquals(0, restored.vpr().value(), "VPR não é gravado por saveState (método novo à parte)");
    }
}
