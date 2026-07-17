package dev.vitorsilverio.armjitter.support;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

public final class TestAddressSpace implements AddressSpace {
    private final byte[] data;

    public TestAddressSpace(int size) {
        data = new byte[size];
    }

    public void put16(int address, int value) {
        data[address] = (byte) value;
        data[address + 1] = (byte) (value >>> 8);
    }

    public void put32(int address, int value) {
        data[address] = (byte) value;
        data[address + 1] = (byte) (value >>> 8);
        data[address + 2] = (byte) (value >>> 16);
        data[address + 3] = (byte) (value >>> 24);
    }

    /// Copia o conteúdo da memória para um novo barramento independente.
    public TestAddressSpace copy() {
        TestAddressSpace copy = new TestAddressSpace(data.length);
        System.arraycopy(data, 0, copy.data, 0, data.length);
        return copy;
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
        notifyWrite(address);
    }

    @Override
    public void write16(int address, int value) {
        // Modela RAM normal: um store de halfword força alinhamento (bit 0 ignorado).
        int aligned = address & ~1;
        write8(aligned, value);
        write8(aligned + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        write16(address, value);
        write16(address + 2, value >>> 16);
    }
}
