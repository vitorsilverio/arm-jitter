package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;

import java.util.Arrays;
import java.util.Objects;

/// Captura observável da CPU após executar um bloco.
public record CpuSnapshot(int[] registers, int cpsr, long cycles, CpuMode mode) {
    /// Fotografa o estado atual do core.
    public static CpuSnapshot capture(ArmCore core) {
        return new CpuSnapshot(
                core.registersSnapshot(),
                core.cpsr().get(),
                core.cycles(),
                core.mode());
    }

    public CpuSnapshot {
        registers = Arrays.copyOf(registers, registers.length);
    }

    /// Compara com outro snapshot e lança {@link EquivalenceMismatchException} se divergir.
    public void assertEqualTo(CpuSnapshot other, String label) {
        Objects.requireNonNull(other, "other");
        if (!Arrays.equals(registers, other.registers)) {
            throw EquivalenceMismatchException.of(
                    label,
                    "registers",
                    Arrays.toString(registers),
                    Arrays.toString(other.registers));
        }
        if (cpsr != other.cpsr) {
            throw EquivalenceMismatchException.of(
                    label,
                    "cpsr",
                    String.format("0x%08X", cpsr),
                    String.format("0x%08X", other.cpsr));
        }
        if (cycles != other.cycles) {
            throw EquivalenceMismatchException.of(
                    label,
                    "cycles",
                    Long.toString(cycles),
                    Long.toString(other.cycles));
        }
        if (mode != other.mode) {
            throw EquivalenceMismatchException.of(
                    label,
                    "mode",
                    mode.name(),
                    other.mode.name());
        }
    }
}
