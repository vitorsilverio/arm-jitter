package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Mapeia registradores e barramento guest para chamadas JVM emitidas pelo codegen ASM.
///
/// Os descritores seguem o formato ASM e devem permanecer alinhados às APIs públicas de
/// {@link ArmCore}, {@link CpsrRegister} e {@link AddressSpace}.
public final class GuestToHostMapper {
    public static final String ARM_CORE = "dev/vitorsilverio/armjitter/core/ArmCore";
    public static final String CPSR = "dev/vitorsilverio/armjitter/core/CpsrRegister";
    public static final String ADDRESS_SPACE = "dev/vitorsilverio/armjitter/memory/AddressSpace";
    public static final String MEMORY_ACCESS_TYPE = "dev/vitorsilverio/armjitter/memory/MemoryAccessType";
    public static final String CPU_MODE = "dev/vitorsilverio/armjitter/core/CpuMode";

    private static final String ARM_CORE_REF = "L" + ARM_CORE + ";";
    private static final String CPSR_REF = "L" + CPSR + ";";
    private static final String ADDRESS_SPACE_REF = "L" + ADDRESS_SPACE + ";";

    private GuestToHostMapper() {
    }

    /// {@link ArmCore#register(int)}
    public static HostMethodBinding registerRead() {
        return new HostMethodBinding(ARM_CORE, "register", "(I)I");
    }

    /// {@link ArmCore#setRegister(int, int)}
    public static HostMethodBinding registerWrite() {
        return new HostMethodBinding(ARM_CORE, "setRegister", "(II)V");
    }

    /// {@link ArmCore#programCounter()}
    public static HostMethodBinding programCounterRead() {
        return new HostMethodBinding(ARM_CORE, "programCounter", "()I");
    }

    /// {@link ArmCore#setProgramCounter(int)}
    public static HostMethodBinding programCounterWrite() {
        return new HostMethodBinding(ARM_CORE, "setProgramCounter", "(I)V");
    }

    /// {@link ArmCore#cpsr()}
    public static HostMethodBinding cpsr() {
        return new HostMethodBinding(ARM_CORE, "cpsr", "()" + CPSR_REF);
    }

    /// {@link ArmCore#setCpsr(int)}
    public static HostMethodBinding setCpsr() {
        return new HostMethodBinding(ARM_CORE, "setCpsr", "(I)V");
    }

    /// {@link ArmCore#mode()}
    public static HostMethodBinding mode() {
        return new HostMethodBinding(ARM_CORE, "mode", "()L" + CPU_MODE + ";");
    }

    /// {@link ArmCore#memory()}
    public static HostMethodBinding memory() {
        return new HostMethodBinding(ARM_CORE, "memory", "()" + ADDRESS_SPACE_REF);
    }

    /// {@link ArmCore#addCycles(long)}
    public static HostMethodBinding addCycles() {
        return new HostMethodBinding(ARM_CORE, "addCycles", "(J)V");
    }

    /// {@link ArmCore#addMemoryCycles(int, int, MemoryAccessType)}
    public static HostMethodBinding addMemoryCycles() {
        return new HostMethodBinding(
                ARM_CORE,
                "addMemoryCycles",
                "(IIL" + MEMORY_ACCESS_TYPE + ";)V");
    }

    /// {@link CpsrRegister#get()}
    public static HostMethodBinding cpsrGet() {
        return new HostMethodBinding(CPSR, "get", "()I");
    }

    /// {@link CpsrRegister#set(int)}
    public static HostMethodBinding cpsrSet() {
        return new HostMethodBinding(CPSR, "set", "(I)V");
    }

    /// {@link CpsrRegister#setNzcv(boolean, boolean, boolean, boolean)}
    public static HostMethodBinding cpsrSetNzcv() {
        return new HostMethodBinding(CPSR, "setNzcv", "(ZZZZ)V");
    }

    /// {@link CpsrRegister#carry()}
    public static HostMethodBinding cpsrCarry() {
        return new HostMethodBinding(CPSR, "carry", "()Z");
    }

    /// {@link CpsrRegister#setThumbMode(boolean)}
    public static HostMethodBinding cpsrSetThumbMode() {
        return new HostMethodBinding(CPSR, "setThumbMode", "(Z)V");
    }

    /// {@link AddressSpace#read8(int)}
    public static HostMethodBinding memoryRead8() {
        return new HostMethodBinding(ADDRESS_SPACE, "read8", "(I)I");
    }

    /// {@link AddressSpace#read16(int)}
    public static HostMethodBinding memoryRead16() {
        return new HostMethodBinding(ADDRESS_SPACE, "read16", "(I)I");
    }

    /// {@link AddressSpace#read32(int)}
    public static HostMethodBinding memoryRead32() {
        return new HostMethodBinding(ADDRESS_SPACE, "read32", "(I)I");
    }

    /// {@link AddressSpace#write8(int, int)}
    public static HostMethodBinding memoryWrite8() {
        return new HostMethodBinding(ADDRESS_SPACE, "write8", "(II)V");
    }

    /// {@link AddressSpace#write16(int, int)}
    public static HostMethodBinding memoryWrite16() {
        return new HostMethodBinding(ADDRESS_SPACE, "write16", "(II)V");
    }

    /// {@link AddressSpace#write32(int, int)}
    public static HostMethodBinding memoryWrite32() {
        return new HostMethodBinding(ADDRESS_SPACE, "write32", "(II)V");
    }

    /// {@link AddressSpace#accessCycles(int, int, MemoryAccessType)}
    public static HostMethodBinding memoryAccessCycles() {
        return new HostMethodBinding(
                ADDRESS_SPACE,
                "accessCycles",
                "(IIL" + MEMORY_ACCESS_TYPE + ";)I");
    }
}
