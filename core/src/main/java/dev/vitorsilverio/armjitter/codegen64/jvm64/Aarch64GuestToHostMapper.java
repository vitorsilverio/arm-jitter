package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.codegen.jvm.HostMethodBinding;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Mapeia {@link Aarch64Core}/{@link AddressSpace64} para chamadas JVM emitidas pelo
/// {@code Ir64BlockCompiler} — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.jvm.GuestToHostMapper} (32 bits), introduzido na
/// task B6.4 (PR1). Reaproveita {@link HostMethodBinding} diretamente do pacote 32-bit (D4 da
/// spec): é um `record(owner, name, descriptor)` genérico, sem NADA específico de ARM-32.
///
/// PR1 só precisa das ligações usadas por `Alu64`/`MoveWide`/`PcRelative`/`Branch64`/
/// `CompareBranch64`/`Cycle`/`Fetch` — loads/stores (B6.2) entram em PR2.
public final class Aarch64GuestToHostMapper {
    public static final String AARCH64_CORE = "dev/vitorsilverio/armjitter/core64/Aarch64Core";
    public static final String ADDRESS_SPACE_64 = "dev/vitorsilverio/armjitter/memory/AddressSpace64";
    public static final String MEMORY_ACCESS_TYPE = "dev/vitorsilverio/armjitter/memory/MemoryAccessType";

    private static final String AARCH64_CORE_REF = "L" + AARCH64_CORE + ";";
    private static final String ADDRESS_SPACE_64_REF = "L" + ADDRESS_SPACE_64 + ";";

    private Aarch64GuestToHostMapper() {
    }

    /// {@link Aarch64Core#memory()}
    public static HostMethodBinding memory() {
        return new HostMethodBinding(AARCH64_CORE, "memory", "()" + ADDRESS_SPACE_64_REF);
    }

    /// {@link Aarch64Core#addCycles(long)}
    public static HostMethodBinding addCycles() {
        return new HostMethodBinding(AARCH64_CORE, "addCycles", "(J)V");
    }

    /// {@link Aarch64Core#setProgramCounter(long)}
    public static HostMethodBinding setProgramCounter() {
        return new HostMethodBinding(AARCH64_CORE, "setProgramCounter", "(J)V");
    }

    /// {@link AddressSpace64#accessCycles(long, int, MemoryAccessType)}
    public static HostMethodBinding memoryAccessCycles() {
        return new HostMethodBinding(
                ADDRESS_SPACE_64,
                "accessCycles",
                "(JIL" + MEMORY_ACCESS_TYPE + ";)I");
    }
}
