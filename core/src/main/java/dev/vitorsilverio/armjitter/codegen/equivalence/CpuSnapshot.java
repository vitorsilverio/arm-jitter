package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;

import java.util.Arrays;
import java.util.Objects;

/// Captura observável da CPU após executar um bloco.
public record CpuSnapshot(int[] registers, int cpsr, long cycles, CpuMode mode,
        long exclusiveMonitorAddress, int exclusiveMonitorSizeBytes,
        int[] vfpSingles, int fpscr) {
    /// Fotografa o estado atual do core.
    public static CpuSnapshot capture(ArmCore core) {
        return new CpuSnapshot(
                core.registersSnapshot(),
                core.cpsr().get(),
                core.cycles(),
                core.mode(),
                core.exclusiveMonitorAddress(),
                core.exclusiveMonitorSizeBytes(),
                core.vfp().snapshot(),
                core.fpscr().value());
    }

    public CpuSnapshot {
        registers = Arrays.copyOf(registers, registers.length);
        vfpSingles = Arrays.copyOf(vfpSingles, vfpSingles.length);
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
        if (exclusiveMonitorAddress != other.exclusiveMonitorAddress
                || exclusiveMonitorSizeBytes != other.exclusiveMonitorSizeBytes) {
            throw EquivalenceMismatchException.of(
                    label,
                    "exclusiveMonitor",
                    exclusiveMonitorAddress + "/" + exclusiveMonitorSizeBytes,
                    other.exclusiveMonitorAddress + "/" + other.exclusiveMonitorSizeBytes);
        }
        if (!Arrays.equals(vfpSingles, other.vfpSingles)) {
            throw EquivalenceMismatchException.of(
                    label,
                    "vfpSingles",
                    Arrays.toString(vfpSingles),
                    Arrays.toString(other.vfpSingles));
        }
        if (fpscr != other.fpscr) {
            throw EquivalenceMismatchException.of(
                    label,
                    "fpscr",
                    String.format("0x%08X", fpscr),
                    String.format("0x%08X", other.fpscr));
        }
    }
}
