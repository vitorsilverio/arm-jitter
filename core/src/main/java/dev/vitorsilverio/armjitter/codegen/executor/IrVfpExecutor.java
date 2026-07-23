package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa a IR de VFP (B3.4): aritmética/unárias, comparação, conversão, load/store, transferência
/// múltipla e transferências de/para o banco de registradores ARM/FPSCR. Espelha
/// {@link IrMemoryExecutor} na forma; é o oráculo semântico (G1) até que B3.6 emita nativamente.
///
/// Nenhuma operação daqui altera o PC — não há valor de retorno booleano como em
/// {@link IrMemoryExecutor#executeLoad}.
public final class IrVfpExecutor {
    /// Cota superior EXCLUSIVA do intervalo `uint32` (`2^32`), usada para saturar as conversões
    /// `VCVT` para inteiro sem sinal (ver {@link #toUnsignedInt32}).
    private static final double UNSIGNED_32_EXCLUSIVE_UPPER_BOUND = 4_294_967_296.0;
    /// Valor saturado (todos os bits 1) de um `uint32` que estourou o intervalo `[0, 2^32-1]`.
    private static final int UINT32_ALL_ONES = 0xFFFF_FFFF;
    /// Índice do registrador ARM que, em `VMRS Rt, FPSCR` com `Rt=15`, sinaliza o caso especial
    /// `VMRS APSR_nzcv, FPSCR` (copia só NZCV para o CPSR, não escreve R15).
    private static final int APSR_NZCV_ENCODING = 15;

    private final IrExecutionSupport support;

    IrVfpExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`/`VMLA`/`VMLS`/`VNMUL`/`VNEG`/`VABS`/`VSQRT`/`VMOV` registrador.
    public void executeVfpAlu(ArmCore core, IrOp.VfpAlu op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        if (op.doublePrecision()) {
            vfp.setDDouble(op.vd(), computeDouble(vfp, op));
        } else {
            vfp.setSFloat(op.vd(), computeSingle(vfp, op));
        }
    }

    private static float computeSingle(VfpRegisters vfp, IrOp.VfpAlu op) {
        float vm = vfp.sFloat(op.vm());
        return switch (op.op()) {
            case ADD -> vfp.sFloat(op.vn()) + vm;
            case SUB -> vfp.sFloat(op.vn()) - vm;
            case MUL -> vfp.sFloat(op.vn()) * vm;
            case DIV -> vfp.sFloat(op.vn()) / vm;
            // VMLA/VMLS NÃO fundidos: o produto é arredondado primeiro (uma operação `float`),
            // depois somado/subtraído (outra operação `float`) — NUNCA Math.fma, que arredondaria
            // uma única vez e divergiria do VFP real (armadilha da task B3.4).
            case MLA -> vfp.sFloat(op.vd()) + (vfp.sFloat(op.vn()) * vm);
            case MLS -> vfp.sFloat(op.vd()) - (vfp.sFloat(op.vn()) * vm);
            case NMUL -> -(vfp.sFloat(op.vn()) * vm);
            // NEG/ABS manipulam o bit de sinal diretamente (ARM DDI 0406C A2.9), nunca `0-x`/
            // `Math.abs` — que canonicalizam NaN e quebram no caso `-0.0` (armadilha da task).
            case NEG -> Float.intBitsToFloat(Float.floatToRawIntBits(vm) ^ Integer.MIN_VALUE);
            case ABS -> Float.intBitsToFloat(Float.floatToRawIntBits(vm) & Integer.MAX_VALUE);
            // (float) Math.sqrt((double) x) é corretamente arredondado (IEEE 754) mesmo após o
            // narrowing final para float — ver Inclui da task B3.4.
            case SQRT -> (float) Math.sqrt((double) vm);
            case COPY -> vm;
        };
    }

    private static double computeDouble(VfpRegisters vfp, IrOp.VfpAlu op) {
        double vm = vfp.dDouble(op.vm());
        return switch (op.op()) {
            case ADD -> vfp.dDouble(op.vn()) + vm;
            case SUB -> vfp.dDouble(op.vn()) - vm;
            case MUL -> vfp.dDouble(op.vn()) * vm;
            case DIV -> vfp.dDouble(op.vn()) / vm;
            case MLA -> vfp.dDouble(op.vd()) + (vfp.dDouble(op.vn()) * vm);
            case MLS -> vfp.dDouble(op.vd()) - (vfp.dDouble(op.vn()) * vm);
            case NMUL -> -(vfp.dDouble(op.vn()) * vm);
            case NEG -> Double.longBitsToDouble(Double.doubleToRawLongBits(vm) ^ Long.MIN_VALUE);
            case ABS -> Double.longBitsToDouble(Double.doubleToRawLongBits(vm) & Long.MAX_VALUE);
            case SQRT -> Math.sqrt(vm);
            case COPY -> vm;
        };
    }

    /// `VMOV.F32`/`VMOV.F64 Vd, #imm`.
    public void executeVfpMoveImmediate(ArmCore core, IrOp.VfpMoveImmediate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.doublePrecision()) {
            core.vfp().setD(op.vd(), op.immediateBits());
        } else {
            core.vfp().setS(op.vd(), (int) op.immediateBits());
        }
    }

    /// `VCMP`/`VCMPE`: grava só `FPSCR.NZCV`, nunca o CPSR (ver {@link IrOp.VfpCompare}).
    public void executeVfpCompare(ArmCore core, IrOp.VfpCompare op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        boolean unordered;
        boolean equal;
        boolean less;
        if (op.doublePrecision()) {
            double a = vfp.dDouble(op.vd());
            double b = op.compareWithZero() ? 0.0 : vfp.dDouble(op.vm());
            unordered = Double.isNaN(a) || Double.isNaN(b);
            equal = !unordered && a == b;
            less = !unordered && a < b;
        } else {
            float a = vfp.sFloat(op.vd());
            float b = op.compareWithZero() ? 0f : vfp.sFloat(op.vm());
            unordered = Float.isNaN(a) || Float.isNaN(b);
            equal = !unordered && a == b;
            less = !unordered && a < b;
        }
        int packed;
        if (unordered) {
            packed = FpscrRegister.CARRY_FLAG | FpscrRegister.OVERFLOW_FLAG;
        } else if (equal) {
            packed = FpscrRegister.ZERO_FLAG | FpscrRegister.CARRY_FLAG;
        } else if (less) {
            packed = FpscrRegister.NEGATIVE_FLAG;
        } else {
            packed = FpscrRegister.CARRY_FLAG;
        }
        core.fpscr().setNzcv(packed);
    }

    /// `VCVT` (forma default, round-toward-zero para inteiro).
    public void executeVfpConvert(ArmCore core, IrOp.VfpConvert op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        switch (op.conversion()) {
            case F32_TO_F64 -> vfp.setDDouble(op.vd(), vfp.sFloat(op.vm()));
            case F64_TO_F32 -> vfp.setSFloat(op.vd(), (float) vfp.dDouble(op.vm()));
            case S32_TO_F32 -> vfp.setSFloat(op.vd(), (float) vfp.s(op.vm()));
            case S32_TO_F64 -> vfp.setDDouble(op.vd(), (double) vfp.s(op.vm()));
            case U32_TO_F32 -> vfp.setSFloat(op.vd(), (float) Integer.toUnsignedLong(vfp.s(op.vm())));
            case U32_TO_F64 -> vfp.setDDouble(op.vd(), (double) Integer.toUnsignedLong(vfp.s(op.vm())));
            // O narrowing double/float→int do Java já implementa a semântica do VCVT para inteiro
            // COM sinal: round-toward-zero, satura em MIN/MAX, NaN→0 (conferido na Armadilha da
            // task B3.4 — `(int)Float.NaN==0` e `(int)1e30f==Integer.MAX_VALUE`).
            case F32_TO_S32 -> vfp.setS(op.vd(), (int) vfp.sFloat(op.vm()));
            case F64_TO_S32 -> vfp.setS(op.vd(), (int) vfp.dDouble(op.vm()));
            // SEM sinal: Java não tem `int` sem sinal nativo, então o clamp é manual em `long`.
            case F32_TO_U32 -> vfp.setS(op.vd(), toUnsignedInt32((double) vfp.sFloat(op.vm())));
            case F64_TO_U32 -> vfp.setS(op.vd(), toUnsignedInt32(vfp.dDouble(op.vm())));
        }
    }

    /// Converte para `uint32` com arredondamento para zero e saturação em `[0, 2^32-1]`
    /// (NaN e valores negativos → `0`; overflow → todos os bits 1).
    private static int toUnsignedInt32(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0;
        }
        if (value >= UNSIGNED_32_EXCLUSIVE_UPPER_BOUND) {
            return UINT32_ALL_ONES;
        }
        return (int) (long) value;
    }

    /// `VLDR`: dupla precisão lê 2 palavras little-endian consecutivas (metade baixa no endereço
    /// menor — igual a `LDRD`/{@link IrMemoryExecutor#executeDoubleTransfer}).
    public void executeVfpLoad(ArmCore core, IrOp.VfpLoad op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int address = support.registerValue(core, op.base(), op.baseValueOverride()) + op.offsetBytes();
        if (op.doublePrecision()) {
            int low = support.read32Arm7(core, address);
            int high = support.read32Arm7(core, address + 4);
            core.vfp().setD(op.vd(), (((long) high) << 32) | (low & 0xFFFF_FFFFL));
        } else {
            core.vfp().setS(op.vd(), support.read32Arm7(core, address));
        }
    }

    /// `VSTR`: ver {@link #executeVfpLoad}.
    public void executeVfpStore(ArmCore core, IrOp.VfpStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int address = support.registerValue(core, op.base(), op.baseValueOverride()) + op.offsetBytes();
        if (op.doublePrecision()) {
            long bits = core.vfp().d(op.vd());
            support.write32Arm7(core, address, (int) bits);
            support.write32Arm7(core, address + 4, (int) (bits >>> 32));
        } else {
            support.write32Arm7(core, address, core.vfp().s(op.vd()));
        }
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: registradores consecutivos, só formas `IA`/`DB`.
    public void executeVfpMultipleTransfer(ArmCore core, IrOp.VfpMultipleTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int registerSizeBytes = op.doublePrecision() ? 8 : 4;
        int totalBytes = op.count() * registerSizeBytes;
        int baseValue = support.registerValue(core, op.base(), op.baseValueOverride());
        int address = op.decrementBefore() ? baseValue - totalBytes : baseValue;
        for (int i = 0; i < op.count(); i++) {
            int reg = op.firstRegister() + i;
            if (op.load()) {
                if (op.doublePrecision()) {
                    int low = support.read32Arm7(core, address);
                    int high = support.read32Arm7(core, address + 4);
                    core.vfp().setD(reg, (((long) high) << 32) | (low & 0xFFFF_FFFFL));
                } else {
                    core.vfp().setS(reg, support.read32Arm7(core, address));
                }
            } else {
                if (op.doublePrecision()) {
                    long bits = core.vfp().d(reg);
                    support.write32Arm7(core, address, (int) bits);
                    support.write32Arm7(core, address + 4, (int) (bits >>> 32));
                } else {
                    support.write32Arm7(core, address, core.vfp().s(reg));
                }
            }
            address += registerSizeBytes;
        }
        if (op.writeback()) {
            core.setRegister(op.base(), op.decrementBefore() ? baseValue - totalBytes : baseValue + totalBytes);
        }
    }

    /// `VMOV Rt,Sn` / `VMOV Sn,Rt` (`FMRS`/`FMSR`): bits crus, sem conversão de tipo.
    public void executeVfpCoreTransfer(ArmCore core, IrOp.VfpCoreTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.toArmRegister()) {
            core.setRegister(op.armRegister(), core.vfp().s(op.vn()));
        } else {
            core.vfp().setS(op.vn(), core.register(op.armRegister()));
        }
    }

    /// `VMOV Rt,Rt2,Dm` / `VMOV Dm,Rt,Rt2` (`FMRRD`/`FMDRR`): `armLow` = metade baixa,
    /// `armHigh` = metade alta (mesmo layout de {@link #executeVfpLoad}/{@link #executeVfpStore}).
    public void executeVfpCorePairTransfer(ArmCore core, IrOp.VfpCorePairTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.toArmRegisters()) {
            long bits = core.vfp().d(op.vm());
            core.setRegister(op.armLow(), (int) bits);
            core.setRegister(op.armHigh(), (int) (bits >>> 32));
        } else {
            int low = core.register(op.armLow());
            int high = core.register(op.armHigh());
            core.vfp().setD(op.vm(), (((long) high) << 32) | (low & 0xFFFF_FFFFL));
        }
    }

    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`). Caso especial obrigatório: `VMRS APSR_nzcv, FPSCR`
    /// (`read=true, armRegister=15`) copia só `FPSCR.NZCV` para `CPSR.NZCV`, sem tocar Q/GE/IT/modo
    /// e sem escrever `R15` (decisão nº 4 do épico B3).
    public void executeVfpSystemTransfer(ArmCore core, IrOp.VfpSystemTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.read()) {
            if (op.armRegister() == APSR_NZCV_ENCODING) {
                FpscrRegister fpscr = core.fpscr();
                core.cpsr().setNzcv(fpscr.n(), fpscr.z(), fpscr.c(), fpscr.v());
            } else {
                core.setRegister(op.armRegister(), core.fpscr().value());
            }
        } else {
            core.fpscr().setValue(core.register(op.armRegister()));
        }
    }
}
