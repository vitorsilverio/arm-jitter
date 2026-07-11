package dev.vitorsilverio.armjitter.truffle.support;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// `AddressSpace` mínima para os testes deste módulo — o equivalente local ao
/// `TestAddressSpace` do core (que vive em `src/test`, não publicado como test-jar, então não
/// é importável daqui). Só precisa existir para satisfazer o construtor de `ArmCore`; os
/// blocos exercitados por `TruffleCodeEmitterEquivalenceTest` são só ALU/Cycle/Fetch e nunca
/// tocam memória.
public final class ByteArrayAddressSpace implements AddressSpace {
    private final byte[] data;

    public ByteArrayAddressSpace(int size) {
        this.data = new byte[size];
    }

    @Override
    public int read8(int address) {
        return data[address] & 0xFF;
    }

    @Override
    public int read16(int address) {
        return read8(address) | (read8(address + 1) << 8);
    }

    @Override
    public int read32(int address) {
        return read16(address) | (read16(address + 2) << 16);
    }

    @Override
    public void write8(int address, int value) {
        data[address] = (byte) value;
    }

    @Override
    public void write16(int address, int value) {
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        write16(address, value);
        write16(address + 2, value >>> 16);
    }
}
