package dev.vitorsilverio.armjitter.capi;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.word.PointerBase;

/// Assinatura do callback C `aj_read_fn` (`aj_set_mmio_callbacks`): chamado para todo
/// endereço fora de qualquer região mapeada por `aj_map_ram`. Invocado NA thread que chamou
/// `aj_run_cycles` (documentado no README — sem concorrência entre callback e emulação).
interface MmioReadFunctionPointer extends CFunctionPointer {
    @InvokeCFunctionPointer
    int invoke(IsolateThread thread, PointerBase userData, int address, int sizeBytes);
}
