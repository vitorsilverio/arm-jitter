package dev.vitorsilverio.armjitter.capi;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

/// `AddressSpace` de barramento aberto instalada como `openBusHandler` do
/// `PagedAddressSpace` de cada handle: cobre todo endereço fora de qualquer região mapeada
/// por `aj_map_ram` (task A9, PR1). Antes de `aj_set_mmio_callbacks`, leituras devolvem `0`
/// e escritas são ignoradas (mesmo comportamento inócuo de um barramento sem periférico
/// nenhum acoplado). Depois, delega ao callback C informado.
final class CallbackOpenBus implements AddressSpace {
    private volatile MmioReadFunctionPointer readFn = WordFactory.nullPointer();
    private volatile MmioWriteFunctionPointer writeFn = WordFactory.nullPointer();
    private volatile PointerBase userData = WordFactory.nullPointer();

    void setCallbacks(MmioReadFunctionPointer readFn, MmioWriteFunctionPointer writeFn, PointerBase userData) {
        this.readFn = readFn;
        this.writeFn = writeFn;
        this.userData = userData;
    }

    @Override
    public int read8(int address) {
        return read(address, 1);
    }

    @Override
    public int read16(int address) {
        return read(address, 2);
    }

    @Override
    public int read32(int address) {
        return read(address, 4);
    }

    @Override
    public void write8(int address, int value) {
        write(address, 1, value);
    }

    @Override
    public void write16(int address, int value) {
        write(address, 2, value);
    }

    @Override
    public void write32(int address, int value) {
        write(address, 4, value);
    }

    private int read(int address, int sizeBytes) {
        MmioReadFunctionPointer fn = readFn;
        if (fn.isNull()) {
            return 0;
        }
        return fn.invoke(CurrentIsolate.getCurrentThread(), userData, address, sizeBytes);
    }

    private void write(int address, int sizeBytes, int value) {
        MmioWriteFunctionPointer fn = writeFn;
        if (fn.isNull()) {
            return;
        }
        fn.invoke(CurrentIsolate.getCurrentThread(), userData, address, sizeBytes, value);
    }
}
