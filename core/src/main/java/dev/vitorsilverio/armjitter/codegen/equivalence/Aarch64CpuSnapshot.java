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
        int exclusiveMonitorSizeBytes,
        /// Banco `V0`-`V31` completo (bits 63:0 de cada, B6.5.1) — necessário para o harness de
        /// equivalência de B6.5.4 (ASM nativo) detectar divergência de FP.
        long[] vRegisters,
        /// Estado escalável SVE (B17.3): `ZCR_EL1/2/3` + banco `Z`/`P`/`FFR` completos (vazio em
        /// presets sem `FEAT_SVE`) — o aliasing `V`≡`Z[127:0]` já está em `vRegisters`, aqui vão os bits
        /// altos e os predicados que o harness de B17.x precisa comparar.
        long[] scalableState,
        /// Estado matricial SME (B18.1): `SVCR`, `SMCR_EL1/2/3` e `ZA`/`ZT0` (vazio em presets sem
        /// `FEAT_SME`; um `ZA` nunca alocado ocupa só dois marcadores de presença).
        long[] matrixState) {
    /// Construtor pré-B18.1 (sem estado matricial) — mantém a assinatura pública (G3).
    public Aarch64CpuSnapshot(
            long[] registers,
            long sp,
            long pc,
            int nzcv,
            long cycles,
            long exclusiveMonitorAddress,
            int exclusiveMonitorSizeBytes,
            long[] vRegisters,
            long[] scalableState) {
        this(registers, sp, pc, nzcv, cycles, exclusiveMonitorAddress, exclusiveMonitorSizeBytes,
                vRegisters, scalableState, new long[0]);
    }

    /// Construtor pré-B17.3 (sem estado escalável) — mantém a assinatura pública (G3).
    public Aarch64CpuSnapshot(
            long[] registers,
            long sp,
            long pc,
            int nzcv,
            long cycles,
            long exclusiveMonitorAddress,
            int exclusiveMonitorSizeBytes,
            long[] vRegisters) {
        this(registers, sp, pc, nzcv, cycles, exclusiveMonitorAddress, exclusiveMonitorSizeBytes,
                vRegisters, new long[0], new long[0]);
    }

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
                core.exclusiveMonitorSizeBytes(),
                core.fp().snapshot(),
                core.scalableSnapshot(),
                core.matrixSnapshot());
    }

    public Aarch64CpuSnapshot {
        registers = Arrays.copyOf(registers, registers.length);
        vRegisters = Arrays.copyOf(vRegisters, vRegisters.length);
        scalableState = Arrays.copyOf(scalableState, scalableState.length);
        matrixState = Arrays.copyOf(matrixState, matrixState.length);
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
        if (!Arrays.equals(scalableState, other.scalableState)) {
            throw EquivalenceMismatchException.of(
                    label, "scalableState", Arrays.toString(scalableState), Arrays.toString(other.scalableState));
        }
        if (!Arrays.equals(matrixState, other.matrixState)) {
            throw EquivalenceMismatchException.of(
                    label, "matrixState", Arrays.toString(matrixState), Arrays.toString(other.matrixState));
        }
        if (!Arrays.equals(vRegisters, other.vRegisters)) {
            throw EquivalenceMismatchException.of(
                    label, "vRegisters", Arrays.toString(vRegisters), Arrays.toString(other.vRegisters));
        }
    }
}
