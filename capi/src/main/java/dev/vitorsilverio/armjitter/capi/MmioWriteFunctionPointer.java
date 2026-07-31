package dev.vitorsilverio.armjitter.capi;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.word.PointerBase;

/// Assinatura do callback C `aj_write_fn` (`aj_set_mmio_callbacks`). Ver {@link MmioReadFunctionPointer}.
interface MmioWriteFunctionPointer extends CFunctionPointer {
    @InvokeCFunctionPointer
    void invoke(IsolateThread thread, PointerBase userData, int address, int sizeBytes, int value);
}
