package dev.vitorsilverio.armjitter.support;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;

/// Decorator de teste (B4.1.3) que injeta uma {@link MemoryTranslationException} determinística
/// num endereço configurado, sem precisar montar page tables reais — essas já são cobertas por
/// `TranslatingAddressSpaceTest` (B4.1.1). Existe para exercitar a entrada de abort em
/// `ArmCore`/`Cp15VmsaCoprocessor`/`AsmBlockCompiler` isoladamente do page-walk.
public final class FaultingAddressSpace implements AddressSpace {
    private final AddressSpace delegate;
    private int faultAddress = -1;
    private MemoryAccessType faultAccessType;
    private FaultStatus faultStatus = FaultStatus.SECTION_TRANSLATION;

    public FaultingAddressSpace(AddressSpace delegate) {
        this.delegate = delegate;
    }

    /// Faz o próximo acesso do tipo `accessType` em `address` lançar `MemoryTranslationException`.
    public void faultOn(int address, MemoryAccessType accessType) {
        faultOn(address, accessType, FaultStatus.SECTION_TRANSLATION);
    }

    /// Como {@link #faultOn(int, MemoryAccessType)}, mas com um {@link FaultStatus} específico.
    public void faultOn(int address, MemoryAccessType accessType, FaultStatus status) {
        this.faultAddress = address;
        this.faultAccessType = accessType;
        this.faultStatus = status;
    }

    private void check(int address, MemoryAccessType accessType) {
        if (address == faultAddress && accessType == faultAccessType) {
            throw new MemoryTranslationException(address, accessType, faultStatus);
        }
    }

    @Override
    public int fetch16(int address) {
        check(address, MemoryAccessType.INSTRUCTION_FETCH);
        return delegate.fetch16(address);
    }

    @Override
    public int fetch32(int address) {
        check(address, MemoryAccessType.INSTRUCTION_FETCH);
        return delegate.fetch32(address);
    }

    @Override
    public int read8(int address) {
        check(address, MemoryAccessType.DATA_READ);
        return delegate.read8(address);
    }

    @Override
    public int read16(int address) {
        check(address, MemoryAccessType.DATA_READ);
        return delegate.read16(address);
    }

    @Override
    public int read32(int address) {
        check(address, MemoryAccessType.DATA_READ);
        return delegate.read32(address);
    }

    @Override
    public void write8(int address, int value) {
        check(address, MemoryAccessType.DATA_WRITE);
        delegate.write8(address, value);
    }

    @Override
    public void write16(int address, int value) {
        check(address, MemoryAccessType.DATA_WRITE);
        delegate.write16(address, value);
    }

    @Override
    public void write32(int address, int value) {
        check(address, MemoryAccessType.DATA_WRITE);
        delegate.write32(address, value);
    }
}
