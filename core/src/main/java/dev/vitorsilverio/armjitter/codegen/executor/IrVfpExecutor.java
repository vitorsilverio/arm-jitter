package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpRoundingMode;
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
    private final IrNeonExecutor neon;

    IrVfpExecutor(IrExecutionSupport support, IrNeonExecutor neon) {
        this.support = support;
        this.neon = neon;
    }

    /// NEON "three same" (`VADD`/`VSUB` inteiro): DELEGA a {@link IrNeonExecutor} — B13.3 extraiu
    /// a execução vetorial de 32 bits para lá. O método público continua existindo (G3): o
    /// dispatch de {@link IrBlockExecutor} para `NEON_THREE_SAME` ainda entra por aqui.
    public void executeNeonThreeSame(ArmCore core, IrOp.NeonThreeSame op) {
        neon.executeNeonThreeSame(core, op);
    }

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`/`VMLA`/`VMLS`/`VNMUL`/`VNEG`/`VABS`/`VSQRT`/`VMOV` registrador.
    public void executeVfpAlu(ArmCore core, IrOp.VfpAlu op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        FpscrRegister fpscr = core.fpscr();
        if (op.doublePrecision()) {
            vfp.setDDouble(op.vd(), computeDouble(vfp, op, fpscr.roundingMode(), fpscr.flushToZero()));
        } else {
            vfp.setSFloat(op.vd(), computeSingle(vfp, op, fpscr.roundingMode(), fpscr.flushToZero()));
        }
    }

    /// Bit de sinal de um `float` em forma de bits crus (ARM DDI 0406C A2.9: `VNEG`/`VABS`
    /// manipulam esse bit diretamente, nunca `0-x`/`Math.abs`, que canonicalizam NaN e quebram
    /// `-0.0` — armadilha documentada na task B3.4).
    private static final int SINGLE_SIGN_BIT = Integer.MIN_VALUE;
    /// @see #SINGLE_SIGN_BIT
    private static final long DOUBLE_SIGN_BIT = Long.MIN_VALUE;

    private static float computeSingle(VfpRegisters vfp, IrOp.VfpAlu op, FpRoundingMode mode, boolean flushToZero) {
        return switch (op.op()) {
            // NEG/ABS/COPY são manipulação de bits, não aritmética — RMode/FZ não se aplicam
            // (ARM DDI 0406C A2.7.2: FZ só afeta instruções de processamento de dados aritmético).
            case NEG -> Float.intBitsToFloat(Float.floatToRawIntBits(vfp.sFloat(op.vm())) ^ SINGLE_SIGN_BIT);
            case ABS -> Float.intBitsToFloat(Float.floatToRawIntBits(vfp.sFloat(op.vm())) & ~SINGLE_SIGN_BIT);
            case COPY -> vfp.sFloat(op.vm());
            default -> computeSingleArithmetic(vfp, op, mode, flushToZero);
        };
    }

    private static float computeSingleArithmetic(VfpRegisters vfp, IrOp.VfpAlu op, FpRoundingMode mode,
            boolean flushToZero) {
        // `SQRT` é unário (só `Vm`) — o decoder marca `vn=-1` (sentinel "sem Vn", mesmo valor
        // usado para NEG/ABS/COPY em `VfpDecoder`) porque a instrução real não tem esse campo.
        // Ler incondicionalmente `vfp.sFloat(op.vn())` aqui (achado real, `Armv7TortureTest`:
        // `ArrayIndexOutOfBoundsException` em `VSQRT.F32`) explodia nesse índice negativo antes
        // do `switch` sequer chegar ao case `SQRT`, que nunca usa `vn`.
        float vn = op.vn() >= 0 ? flushSingle(vfp.sFloat(op.vn()), flushToZero) : 0f;
        float vm = flushSingle(vfp.sFloat(op.vm()), flushToZero);
        // "Exato" de ADD/SUB/MUL de float cabe sem perda em double (ver DirectedFpRounding);
        // DIV/SQRT usam double como aproximação de altíssima precisão (idem).
        float result = switch (op.op()) {
            case ADD -> DirectedFpRounding.roundFloat(vn + vm, (double) vn + (double) vm, mode);
            case SUB -> DirectedFpRounding.roundFloat(vn - vm, (double) vn - (double) vm, mode);
            case MUL -> DirectedFpRounding.roundFloat(vn * vm, (double) vn * (double) vm, mode);
            case DIV -> DirectedFpRounding.roundFloat(vn / vm, (double) vn / (double) vm, mode);
            // VMLA/VMLS NÃO fundidos: o produto é arredondado primeiro (uma operação `float`),
            // depois somado/subtraído (outra operação `float`) — NUNCA Math.fma, que arredondaria
            // uma única vez e divergiria do VFP real (armadilha da task B3.4). Cada um dos dois
            // passos passa pelo arredondamento dirigido de RMode independentemente.
            case MLA -> {
                float vd = flushSingle(vfp.sFloat(op.vd()), flushToZero);
                float product = DirectedFpRounding.roundFloat(vn * vm, (double) vn * (double) vm, mode);
                yield DirectedFpRounding.roundFloat(vd + product, (double) vd + (double) product, mode);
            }
            case MLS -> {
                float vd = flushSingle(vfp.sFloat(op.vd()), flushToZero);
                float product = DirectedFpRounding.roundFloat(vn * vm, (double) vn * (double) vm, mode);
                yield DirectedFpRounding.roundFloat(vd - product, (double) vd - (double) product, mode);
            }
            // VNMLA/VNMLS negam o ACUMULADOR, não o produto — e a negação é exata (troca de
            // sinal), então os dois passos arredondados continuam sendo produto e soma.
            case NMLA -> {
                float vd = -flushSingle(vfp.sFloat(op.vd()), flushToZero);
                float product = -DirectedFpRounding.roundFloat(vn * vm, (double) vn * (double) vm, mode);
                yield DirectedFpRounding.roundFloat(vd + product, (double) vd + (double) product, mode);
            }
            case NMLS -> {
                float vd = -flushSingle(vfp.sFloat(op.vd()), flushToZero);
                float product = DirectedFpRounding.roundFloat(vn * vm, (double) vn * (double) vm, mode);
                yield DirectedFpRounding.roundFloat(vd + product, (double) vd + (double) product, mode);
            }
            case NMUL -> DirectedFpRounding.roundFloat(-(vn * vm), -((double) vn * (double) vm), mode);
            // (float) Math.sqrt((double) x) é corretamente arredondado (IEEE 754) mesmo após o
            // narrowing final para float — ver Inclui da task B3.4; Math.sqrt(double) já é o
            // "exato" de altíssima precisão para o arredondamento dirigido.
            case SQRT -> DirectedFpRounding.roundFloat((float) Math.sqrt((double) vm), Math.sqrt((double) vm), mode);
            // FMA/FMS/FNMA/FNMS (B9.6, VFPv4): FUNDIDO — um único arredondamento para produto+soma
            // (`Math.fma`), ao contrário de MLA/MLS/NMLA/NMLS acima. Mesma convenção de sinal
            // (produto negado para *MS, acumulador negado para *NMA/*NMS — ver IrOp.VfpOperation).
            case FMA -> {
                float vd = flushSingle(vfp.sFloat(op.vd()), flushToZero);
                yield DirectedFpRounding.roundFloat(Math.fma(vn, vm, vd),
                        DirectedFpRounding.exactFma(vn, vm, vd).doubleValue(), mode);
            }
            case FMS -> {
                float vd = flushSingle(vfp.sFloat(op.vd()), flushToZero);
                yield DirectedFpRounding.roundFloat(Math.fma(-vn, vm, vd),
                        DirectedFpRounding.exactFma(-vn, vm, vd).doubleValue(), mode);
            }
            case FNMA -> {
                float vd = flushSingle(vfp.sFloat(op.vd()), flushToZero);
                yield DirectedFpRounding.roundFloat(Math.fma(-vn, vm, -vd),
                        DirectedFpRounding.exactFma(-vn, vm, -vd).doubleValue(), mode);
            }
            case FNMS -> {
                float vd = flushSingle(vfp.sFloat(op.vd()), flushToZero);
                yield DirectedFpRounding.roundFloat(Math.fma(vn, vm, -vd),
                        DirectedFpRounding.exactFma(vn, vm, -vd).doubleValue(), mode);
            }
            case NEG, ABS, COPY -> throw new IllegalStateException("tratado em computeSingle");
        };
        return flushSingle(result, flushToZero);
    }

    private static double computeDouble(VfpRegisters vfp, IrOp.VfpAlu op, FpRoundingMode mode, boolean flushToZero) {
        return switch (op.op()) {
            case NEG -> Double.longBitsToDouble(Double.doubleToRawLongBits(vfp.dDouble(op.vm())) ^ DOUBLE_SIGN_BIT);
            case ABS -> Double.longBitsToDouble(Double.doubleToRawLongBits(vfp.dDouble(op.vm())) & ~DOUBLE_SIGN_BIT);
            case COPY -> vfp.dDouble(op.vm());
            default -> computeDoubleArithmetic(vfp, op, mode, flushToZero);
        };
    }

    private static double computeDoubleArithmetic(VfpRegisters vfp, IrOp.VfpAlu op, FpRoundingMode mode,
            boolean flushToZero) {
        // Mesma proteção de {@link #computeSingleArithmetic} — `SQRT` (`VSQRT.F64`) também não
        // tem `Vn` real, `vn=-1` do decoder.
        double vn = op.vn() >= 0 ? flushDouble(vfp.dDouble(op.vn()), flushToZero) : 0.0;
        double vm = flushDouble(vfp.dDouble(op.vm()), flushToZero);
        double result = switch (op.op()) {
            case ADD -> DirectedFpRounding.roundDouble(vn + vm, DirectedFpRounding.exactAdd(vn, vm), mode);
            case SUB -> DirectedFpRounding.roundDouble(vn - vm, DirectedFpRounding.exactSub(vn, vm), mode);
            case MUL -> DirectedFpRounding.roundDouble(vn * vm, DirectedFpRounding.exactMul(vn, vm), mode);
            case DIV -> DirectedFpRounding.roundDouble(vn / vm, DirectedFpRounding.approxDiv(vn, vm), mode);
            case MLA -> {
                double vd = flushDouble(vfp.dDouble(op.vd()), flushToZero);
                double product = DirectedFpRounding.roundDouble(vn * vm, DirectedFpRounding.exactMul(vn, vm), mode);
                yield DirectedFpRounding.roundDouble(vd + product, DirectedFpRounding.exactAdd(vd, product), mode);
            }
            case MLS -> {
                double vd = flushDouble(vfp.dDouble(op.vd()), flushToZero);
                double product = DirectedFpRounding.roundDouble(vn * vm, DirectedFpRounding.exactMul(vn, vm), mode);
                yield DirectedFpRounding.roundDouble(vd - product, DirectedFpRounding.exactSub(vd, product), mode);
            }
            case NMLA -> {
                double vd = -flushDouble(vfp.dDouble(op.vd()), flushToZero);
                double product = -DirectedFpRounding.roundDouble(vn * vm, DirectedFpRounding.exactMul(vn, vm), mode);
                yield DirectedFpRounding.roundDouble(vd + product, DirectedFpRounding.exactAdd(vd, product), mode);
            }
            case NMLS -> {
                double vd = -flushDouble(vfp.dDouble(op.vd()), flushToZero);
                double product = DirectedFpRounding.roundDouble(vn * vm, DirectedFpRounding.exactMul(vn, vm), mode);
                yield DirectedFpRounding.roundDouble(vd + product, DirectedFpRounding.exactAdd(vd, product), mode);
            }
            case NMUL -> DirectedFpRounding.roundDouble(-(vn * vm), DirectedFpRounding.exactMul(vn, vm).negate(), mode);
            case SQRT -> DirectedFpRounding.roundDouble(Math.sqrt(vm), DirectedFpRounding.approxSqrt(vm), mode);
            // FMA/FMS/FNMA/FNMS (B9.6, VFPv4): FUNDIDO — ver o espelho em computeSingleArithmetic.
            case FMA -> {
                double vd = flushDouble(vfp.dDouble(op.vd()), flushToZero);
                yield DirectedFpRounding.roundDouble(Math.fma(vn, vm, vd), DirectedFpRounding.exactFma(vn, vm, vd), mode);
            }
            case FMS -> {
                double vd = flushDouble(vfp.dDouble(op.vd()), flushToZero);
                yield DirectedFpRounding.roundDouble(Math.fma(-vn, vm, vd), DirectedFpRounding.exactFma(-vn, vm, vd), mode);
            }
            case FNMA -> {
                double vd = flushDouble(vfp.dDouble(op.vd()), flushToZero);
                yield DirectedFpRounding.roundDouble(Math.fma(-vn, vm, -vd), DirectedFpRounding.exactFma(-vn, vm, -vd), mode);
            }
            case FNMS -> {
                double vd = flushDouble(vfp.dDouble(op.vd()), flushToZero);
                yield DirectedFpRounding.roundDouble(Math.fma(vn, vm, -vd), DirectedFpRounding.exactFma(vn, vm, -vd), mode);
            }
            case NEG, ABS, COPY -> throw new IllegalStateException("tratado em computeDouble");
        };
        return flushDouble(result, flushToZero);
    }

    /// Flush-to-zero (VFPv2, `FZ`=1): valores subnormais não-zero viram zero com o mesmo sinal.
    /// Aplicado às entradas (denormal-as-zero) e ao resultado das operações aritméticas — nunca
    /// a `NEG`/`ABS`/`COPY`, que são manipulação de bits, não aritmética.
    private static float flushSingle(float value, boolean flushToZero) {
        if (flushToZero && value != 0f && Math.abs(value) < Float.MIN_NORMAL) {
            return Math.copySign(0f, value);
        }
        return value;
    }

    /// @see #flushSingle(float, boolean)
    private static double flushDouble(double value, boolean flushToZero) {
        if (flushToZero && value != 0.0 && Math.abs(value) < Double.MIN_NORMAL) {
            return Math.copySign(0.0, value);
        }
        return value;
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
        boolean flushToZero = core.fpscr().flushToZero();
        boolean unordered;
        boolean equal;
        boolean less;
        if (op.doublePrecision()) {
            double a = flushDouble(vfp.dDouble(op.vd()), flushToZero);
            double b = op.compareWithZero() ? 0.0 : flushDouble(vfp.dDouble(op.vm()), flushToZero);
            unordered = Double.isNaN(a) || Double.isNaN(b);
            equal = !unordered && a == b;
            less = !unordered && a < b;
        } else {
            float a = flushSingle(vfp.sFloat(op.vd()), flushToZero);
            float b = op.compareWithZero() ? 0f : flushSingle(vfp.sFloat(op.vm()), flushToZero);
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

    private static final int HALF_MASK = 0xFFFF;

    /// `VMOV Rt,Sn` / `VMOV Sn,Rt` (`FMRS`/`FMSR`): bits crus, sem conversão de tipo. Com
    /// {@link IrOp.VfpCoreTransfer#halfWidth} (`VMOV_half`, B22.2) a transferência é de 16 bits:
    /// leitura zero-estende `Sn[15:0]`; escrita altera só `Sn[15:0]`, preservando `Sn[31:16]`.
    public void executeVfpCoreTransfer(ArmCore core, IrOp.VfpCoreTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.toArmRegister()) {
            int value = core.vfp().s(op.vn());
            core.setRegister(op.armRegister(), op.halfWidth() ? value & HALF_MASK : value);
        } else if (op.halfWidth()) {
            int current = core.vfp().s(op.vn());
            core.vfp().setS(op.vn(), (current & ~HALF_MASK) | (core.register(op.armRegister()) & HALF_MASK));
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

    /// `VMOV_64_sp`: `armLow`/`armHigh` de/para `Sm`/`Sm+1` (par consecutivo, NAO via `d()`/`setD()`
    /// — `m` pode ser ímpar, caso em que as duas metades pertencem a `D` diferentes).
    public void executeVfpCorePairTransferSingle(ArmCore core, IrOp.VfpCorePairTransferSingle op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        if (op.toArmRegisters()) {
            core.setRegister(op.armLow(), vfp.s(op.vm()));
            core.setRegister(op.armHigh(), vfp.s(op.vm() + 1));
        } else {
            vfp.setS(op.vm(), core.register(op.armLow()));
            vfp.setS(op.vm() + 1, core.register(op.armHigh()));
        }
    }

    /// `VCVT_fix_{sp,dp}`: converte, NO MESMO `vd`, entre float e um inteiro fixo de 16/32 bits
    /// com `fractionBits` bits fracionários (ver Javadoc de {@link IrOp.VfpConvertFixed}). Fixo →
    /// float SEMPRE arredonda ao mais próximo (par); float → fixo SEMPRE trunca para zero e satura
    /// (QEMU `vfp_helper.c` `VFP_CONV_FIX*`, conferido antes de implementar).
    public void executeVfpConvertFixed(ArmCore core, IrOp.VfpConvertFixed op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        double scale = Math.scalb(1.0, op.fractionBits());
        if (op.doublePrecision()) {
            if (op.toFixedPoint()) {
                vfp.setD(op.vd(), doubleToFixed(vfp.dDouble(op.vd()) * scale, op));
            } else {
                vfp.setDDouble(op.vd(), fixedToDouble(vfp.d(op.vd()), op) / scale);
            }
        } else if (op.toFixedPoint()) {
            vfp.setS(op.vd(), (int) doubleToFixed((double) vfp.sFloat(op.vd()) * scale, op));
        } else {
            vfp.setSFloat(op.vd(), (float) (fixedToDouble(vfp.s(op.vd()), op) / scale));
        }
    }

    /// Extrai o inteiro fixo (com/sem sinal, 16/32 bits) empacotado nos bits baixos de `raw` e
    /// devolve seu valor `double` já sem a escala (o `/scale` fica a cargo do chamador). `raw` é o
    /// registrador INTEIRO (32 bits em `sp`, 64 em `dp`) — só os `fixedPointIs32Bit ? 32 : 16` bits
    /// baixos importam, o resto é ignorado (QEMU passa o registrador cheio e o `itype` do helper
    /// trunca; nunca lançamos por excesso de bits altos, mesma tolerância do hardware real).
    private static double fixedToDouble(long raw, IrOp.VfpConvertFixed op) {
        if (op.fixedPointIs32Bit()) {
            return op.unsignedFixedPoint() ? (double) (raw & 0xFFFF_FFFFL) : (double) (int) raw;
        }
        long masked = raw & 0xFFFFL;
        return op.unsignedFixedPoint() ? (double) masked : (double) (short) masked;
    }

    /// Converte `scaledValue` (já multiplicado por `2^fractionBits`) para o inteiro fixo de 16/32
    /// bits, truncando para zero e saturando na largura/sinal do campo — devolve o valor NUMÉRICO
    /// já sinalizado/sem sinal corretamente (não os bits empacotados): para `sp`, `(int)` do
    /// resultado já trunca certo; para `dp`, o `long` já é a extensão de sinal/zero de 64 bits
    /// correta para {@link VfpRegisters#setD} (QEMU: o helper devolve um `int16_t`/`int32_t`
    /// implicitamente convertido para o `uint64_t` de retorno — sign/zero-extend, nunca zero alto).
    private static long doubleToFixed(double scaledValue, IrOp.VfpConvertFixed op) {
        int bits = op.fixedPointIs32Bit() ? 32 : 16;
        double minValue = op.unsignedFixedPoint() ? 0.0 : -Math.scalb(1.0, bits - 1);
        double maxValue = op.unsignedFixedPoint() ? Math.scalb(1.0, bits) - 1.0 : Math.scalb(1.0, bits - 1) - 1.0;
        double truncated = scaledValue < 0 ? Math.ceil(scaledValue) : Math.floor(scaledValue);
        if (Double.isNaN(truncated)) {
            truncated = 0.0;
        }
        return (long) Math.max(minValue, Math.min(maxValue, truncated));
    }
}
