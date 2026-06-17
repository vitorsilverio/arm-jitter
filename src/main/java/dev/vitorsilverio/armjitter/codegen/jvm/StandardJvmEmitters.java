package dev.vitorsilverio.armjitter.codegen.jvm;

/// Implementação padrão de {@link FlagEmitter} e {@link MemAccessEmitter} via {@link GuestToHostMapper}.
public final class StandardJvmEmitters implements FlagEmitter, MemAccessEmitter {
    public static final StandardJvmEmitters INSTANCE = new StandardJvmEmitters();

    private StandardJvmEmitters() {
    }

    @Override
    public HostMethodBinding cpsr() {
        return GuestToHostMapper.cpsr();
    }

    @Override
    public HostMethodBinding setNzcv() {
        return GuestToHostMapper.cpsrSetNzcv();
    }

    @Override
    public HostMethodBinding carry() {
        return GuestToHostMapper.cpsrCarry();
    }

    @Override
    public HostMethodBinding memory() {
        return GuestToHostMapper.memory();
    }

    @Override
    public HostMethodBinding read8() {
        return GuestToHostMapper.memoryRead8();
    }

    @Override
    public HostMethodBinding read16() {
        return GuestToHostMapper.memoryRead16();
    }

    @Override
    public HostMethodBinding read32() {
        return GuestToHostMapper.memoryRead32();
    }

    @Override
    public HostMethodBinding write8() {
        return GuestToHostMapper.memoryWrite8();
    }

    @Override
    public HostMethodBinding write16() {
        return GuestToHostMapper.memoryWrite16();
    }

    @Override
    public HostMethodBinding write32() {
        return GuestToHostMapper.memoryWrite32();
    }

    @Override
    public HostMethodBinding accessCycles() {
        return GuestToHostMapper.memoryAccessCycles();
    }

    @Override
    public HostMethodBinding addMemoryCycles() {
        return GuestToHostMapper.addMemoryCycles();
    }
}
