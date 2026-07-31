package dev.vitorsilverio.armjitter.capi;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.WordFactory;

/// Estado de um handle opaco exposto pela API C (task A9 PR1). Nunca cruza a
/// fronteira C — só o `long` da tabela de {@link ArmJitterCApi#HANDLES} cruza.
final class CoreHandle {
    /// Tamanho fixo do buffer nativo de `aj_last_error`, incluindo o terminador `\0`.
    static final int ERROR_BUFFER_SIZE = 512;

    final ArmCore core;
    final JitRuntime runtime;
    final PagedAddressSpace memory;
    final CallbackOpenBus openBus;
    /// Buffer nativo persistente (fora do heap Java) para a última mensagem de erro deste
    /// handle, alocado em runtime (nunca em inicializador estático — teria um endereço de
    /// build-time inválido sob native-image).
    final CCharPointer errorBuffer;

    /// Guarda de reentrância: `aj_run_cycles` não pode ser chamado de novo, no mesmo handle,
    /// de dentro de um callback MMIO disparado pela mesma chamada (armadilha documentada na
    /// spec da task).
    volatile boolean running;

    CoreHandle(ArmCore core, JitRuntime runtime, PagedAddressSpace memory, CallbackOpenBus openBus) {
        this.core = core;
        this.runtime = runtime;
        this.memory = memory;
        this.openBus = openBus;
        this.errorBuffer = UnmanagedMemory.malloc(WordFactory.unsigned(ERROR_BUFFER_SIZE));
        this.errorBuffer.write(0, (byte) 0);
    }

    void destroy() {
        UnmanagedMemory.free(errorBuffer);
    }
}
