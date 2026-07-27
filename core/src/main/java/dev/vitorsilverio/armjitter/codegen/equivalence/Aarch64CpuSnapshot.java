package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;

import java.util.Arrays;
import java.util.Objects;

/// Captura observável de uma {@link Aarch64Core} após executar um bloco — sibling de
/// {@link CpuSnapshot} (32 bits), introduzido na task B6.4.
public record Aarch64CpuSnapshot(
        /// `X0`-`X30`, índice = número do registrador (`X31`/`XZR` nunca é capturado — sempre 0
        /// por convenção arquitetural, não faz parte do estado observável).
        long[] registers,
        long sp,
        long pc,
        /// Valor bruto de 4 bits de `PSTATE.{N,Z,C,V}` ({@link dev.vitorsilverio.armjitter.core64.PstateRegister#nzcv()}).
        int nzcv,
        long cycles,
        long exclusiveMonitorAddress,
        int exclusiveMonitorSizeBytes) {
    /// Fotografa o estado atual do core.
    public static Aarch64CpuSnapshot capture(Aarch64Core core) {
        long[] registers = new long[31];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = core.x(i);
        }
        return new Aarch64CpuSnapshot(
                registers,
                core.sp(),
                core.pc(),
                core.pstate().nzcv(),
                core.cycles(),
                core.exclusiveMonitorAddress(),
                core.exclusiveMonitorSizeBytes());
    }

    public Aarch64CpuSnapshot {
        registers = Arrays.copyOf(registers, registers.length);
    }

    /// Compara com outro snapshot e lança {@link EquivalenceMismatchException} se divergir.
    public void assertEqualTo(Aarch64CpuSnapshot other, String label) {
        Objects.requireNonNull(other, "other");
        if (!Arrays.equals(registers, other.registers)) {
            throw EquivalenceMismatchException.of(
                    label, "registers", Arrays.toString(registers), Arrays.toString(other.registers));
        }
        if (sp != other.sp) {
            throw EquivalenceMismatchException.of(label, "sp", Long.toHexString(sp), Long.toHexString(other.sp));
        }
        if (pc != other.pc) {
            throw EquivalenceMismatchException.of(label, "pc", Long.toHexString(pc), Long.toHexString(other.pc));
        }
        if (nzcv != other.nzcv) {
            throw EquivalenceMismatchException.of(
                    label, "nzcv", Integer.toBinaryString(nzcv), Integer.toBinaryString(other.nzcv));
        }
        if (cycles != other.cycles) {
            throw EquivalenceMismatchException.of(label, "cycles", Long.toString(cycles), Long.toString(other.cycles));
        }
        if (exclusiveMonitorAddress != other.exclusiveMonitorAddress
                || exclusiveMonitorSizeBytes != other.exclusiveMonitorSizeBytes) {
            throw EquivalenceMismatchException.of(
                    label,
                    "exclusiveMonitor",
                    exclusiveMonitorAddress + "/" + exclusiveMonitorSizeBytes,
                    other.exclusiveMonitorAddress + "/" + other.exclusiveMonitorSizeBytes);
        }
    }
}
