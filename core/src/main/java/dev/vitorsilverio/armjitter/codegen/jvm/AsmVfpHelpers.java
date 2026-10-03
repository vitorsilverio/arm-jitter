package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.VfpOp;

/// Helpers estáticos invocados pelo bytecode ASM gerado: VFP (B3.6, PR2).
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}.
/// Públicos porque o bytecode gerado roda em outro class loader.
public final class AsmVfpHelpers {
    private AsmVfpHelpers() {
    }

    // ── VFP (B3.6, PR2) ──────────────────────────────────────────────────────────
    // Espelham IrVfpExecutor (B3.4/B3.5), o oráculo (G1). O caminho quente (VADD/VSUB/VMUL/
    // VDIV/VNEG/VABS/VMOV registrador, VLDR/VSTR, VMOV Rt<->Sn, VMOV(imm)) é bytecode direto no
    // AsmBlockCompiler (sem passar por aqui); só as formas mais raras chamam um helper.

    /// Cacheado: {@link VfpOp.VfpOperation#values()} clona o array a cada chamada.
    private static final VfpOp.VfpOperation[] VFP_OPERATIONS = VfpOp.VfpOperation.values();
    /// Cacheado: {@link VfpOp.VfpConversion#values()} clona o array a cada chamada.
    private static final VfpOp.VfpConversion[] VFP_CONVERSIONS = VfpOp.VfpConversion.values();
    /// Índice do registrador ARM que, em `VMRS Rt, FPSCR` com `Rt=15`, sinaliza o caso especial
    /// `VMRS APSR_nzcv, FPSCR` (ver {@link #executeVfpSystemTransfer} e
    /// {@link dev.vitorsilverio.armjitter.codegen.executor.IrVfpExecutor#APSR_NZCV_ENCODING}).
    private static final int VFP_APSR_NZCV_ENCODING = 15;
    /// Cota superior EXCLUSIVA do intervalo `uint32` (`2^32`), usada por {@link #toUnsignedInt32}
    /// (mesma constante de {@code IrVfpExecutor}).
    private static final double VFP_UNSIGNED_32_EXCLUSIVE_UPPER_BOUND = 4_294_967_296.0;
    /// Valor saturado (todos os bits 1) de um `uint32` que estourou `[0, 2^32-1]`.
    private static final int VFP_UINT32_ALL_ONES = 0xFFFF_FFFF;

    /// Combina duas words little-endian (`low` no endereço menor) num `long` — mesmo layout de
    /// {@code VfpRegisters#d}/`LDRD`/`VLDM` dupla precisão. Usado pelo caminho direto de
    /// {@code AsmBlockCompiler#emitVfpLoad} (dupla precisão).
    public static long packDoubleWords(int low, int high) {
        return (((long) high) << 32) | (low & 0xFFFF_FFFFL);
    }

    /// `VMLA`/`VMLS`/`VNMUL`/`VSQRT` (formas de {@link VfpOp.Alu} FORA do caminho quente
    /// ADD/SUB/MUL/DIV/NEG/ABS/COPY, que o {@code AsmBlockCompiler} emite em bytecode direto).
    /// Espelha exatamente o subconjunto correspondente de
    /// {@code IrVfpExecutor#computeSingle}/{@code #computeDouble}: `VMLA`/`VMLS` NUNCA usam
    /// {@code Math.fma} (duas operações arredondadas separadamente).
    public static void vfpAluCold(ArmCore core, int opOrdinal, boolean doublePrecision, int vd, int vn, int vm) {
        VfpRegisters vfp = core.vfp();
        VfpOp.VfpOperation op = VFP_OPERATIONS[opOrdinal];
        if (doublePrecision) {
            double result = switch (op) {
                case MLA -> vfp.dDouble(vd) + (vfp.dDouble(vn) * vfp.dDouble(vm));
                case MLS -> vfp.dDouble(vd) - (vfp.dDouble(vn) * vfp.dDouble(vm));
                case NMLA -> -vfp.dDouble(vd) - (vfp.dDouble(vn) * vfp.dDouble(vm));
                case NMLS -> -vfp.dDouble(vd) + (vfp.dDouble(vn) * vfp.dDouble(vm));
                case NMUL -> -(vfp.dDouble(vn) * vfp.dDouble(vm));
                case SQRT -> Math.sqrt(vfp.dDouble(vm));
                // FMA/FMS/FNMA/FNMS (B9.6, VFPv4): FUNDIDO — `Math.fma` de verdade (um único
                // arredondamento), mesma convenção de sinal de MLA/MLS/NMLA/NMLS acima. Sob
                // FPSCR default (RMode=nearest, FZ=0) equivale byte a byte ao que
                // IrVfpExecutor/DirectedFpRounding calculam (RMode!=nearest cai no interpretado,
                // AsmNativePolicy só cobre o caminho comum — mesma limitação pré-existente dos
                // vizinhos MLA/MLS/NMLA/NMLS/NMUL/SQRT deste helper, não introduzida por B9.6).
                case FMA -> Math.fma(vfp.dDouble(vn), vfp.dDouble(vm), vfp.dDouble(vd));
                case FMS -> Math.fma(-vfp.dDouble(vn), vfp.dDouble(vm), vfp.dDouble(vd));
                case FNMA -> Math.fma(-vfp.dDouble(vn), vfp.dDouble(vm), -vfp.dDouble(vd));
                case FNMS -> Math.fma(vfp.dDouble(vn), vfp.dDouble(vm), -vfp.dDouble(vd));
                default -> throw new IllegalStateException("vfpAluCold: op inesperado " + op);
            };
            vfp.setDDouble(vd, result);
        } else {
            float result = switch (op) {
                case MLA -> vfp.sFloat(vd) + (vfp.sFloat(vn) * vfp.sFloat(vm));
                case MLS -> vfp.sFloat(vd) - (vfp.sFloat(vn) * vfp.sFloat(vm));
                case NMLA -> -vfp.sFloat(vd) - (vfp.sFloat(vn) * vfp.sFloat(vm));
                case NMLS -> -vfp.sFloat(vd) + (vfp.sFloat(vn) * vfp.sFloat(vm));
                case NMUL -> -(vfp.sFloat(vn) * vfp.sFloat(vm));
                case SQRT -> (float) Math.sqrt((double) vfp.sFloat(vm));
                case FMA -> Math.fma(vfp.sFloat(vn), vfp.sFloat(vm), vfp.sFloat(vd));
                case FMS -> Math.fma(-vfp.sFloat(vn), vfp.sFloat(vm), vfp.sFloat(vd));
                case FNMA -> Math.fma(-vfp.sFloat(vn), vfp.sFloat(vm), -vfp.sFloat(vd));
                case FNMS -> Math.fma(vfp.sFloat(vn), vfp.sFloat(vm), -vfp.sFloat(vd));
                default -> throw new IllegalStateException("vfpAluCold: op inesperado " + op);
            };
            vfp.setSFloat(vd, result);
        }
    }

    /// `VCMP`/`VCMPE`: grava só `FPSCR.NZCV`, nunca o CPSR (mesma tabela de
    /// {@code IrVfpExecutor#executeVfpCompare}).
    public static void executeVfpCompare(ArmCore core, boolean doublePrecision, boolean compareWithZero, int vd, int vm) {
        VfpRegisters vfp = core.vfp();
        boolean unordered;
        boolean equal;
        boolean less;
        if (doublePrecision) {
            double a = vfp.dDouble(vd);
            double b = compareWithZero ? 0.0 : vfp.dDouble(vm);
            unordered = Double.isNaN(a) || Double.isNaN(b);
            equal = !unordered && a == b;
            less = !unordered && a < b;
        } else {
            float a = vfp.sFloat(vd);
            float b = compareWithZero ? 0f : vfp.sFloat(vm);
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

    /// `VCVT` (forma default, round-toward-zero para inteiro) — espelha
    /// {@code IrVfpExecutor#executeVfpConvert}.
    public static void executeVfpConvert(ArmCore core, int conversionOrdinal, int vd, int vm) {
        VfpRegisters vfp = core.vfp();
        switch (VFP_CONVERSIONS[conversionOrdinal]) {
            case F32_TO_F64 -> vfp.setDDouble(vd, vfp.sFloat(vm));
            case F64_TO_F32 -> vfp.setSFloat(vd, (float) vfp.dDouble(vm));
            case S32_TO_F32 -> vfp.setSFloat(vd, (float) vfp.s(vm));
            case S32_TO_F64 -> vfp.setDDouble(vd, (double) vfp.s(vm));
            case U32_TO_F32 -> vfp.setSFloat(vd, (float) Integer.toUnsignedLong(vfp.s(vm)));
            case U32_TO_F64 -> vfp.setDDouble(vd, (double) Integer.toUnsignedLong(vfp.s(vm)));
            case F32_TO_S32 -> vfp.setS(vd, (int) vfp.sFloat(vm));
            case F64_TO_S32 -> vfp.setS(vd, (int) vfp.dDouble(vm));
            case F32_TO_U32 -> vfp.setS(vd, toUnsignedInt32((double) vfp.sFloat(vm)));
            case F64_TO_U32 -> vfp.setS(vd, toUnsignedInt32(vfp.dDouble(vm)));
        }
    }

    /// Converte para `uint32` com arredondamento para zero e saturação em `[0, 2^32-1]` (NaN e
    /// negativos → `0`; overflow → todos os bits 1) — mesma regra de {@code IrVfpExecutor}.
    private static int toUnsignedInt32(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0;
        }
        if (value >= VFP_UNSIGNED_32_EXCLUSIVE_UPPER_BOUND) {
            return VFP_UINT32_ALL_ONES;
        }
        return (int) (long) value;
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: espelha {@code IrVfpExecutor#executeVfpMultipleTransfer}.
    /// Toca {@code base} via {@link ArmCore#register(int)}/{@link ArmCore#setRegister(int, int)}
    /// diretamente (não pelo register cache) — o {@code AsmBlockCompiler} cerca esta chamada com
    /// flush+reload ({@code emitSpilled}), mesmo tratamento de {@code AsmSystemHelpers#executePsrRead}/
    /// {@code AsmMemoryHelpers#executeMultipleTransfer}.
    public static void executeVfpMultipleTransfer(
            ArmCore core, boolean load, boolean doublePrecision, int base, int baseValueOverride,
            int firstRegister, int count, boolean writeback, boolean decrementBefore) {
        VfpRegisters vfp = core.vfp();
        int registerSizeBytes = doublePrecision ? 8 : 4;
        int totalBytes = count * registerSizeBytes;
        // `baseValueOverride` (`-1` = ausente): mesmo idioma de {@code MemoryOp.Load#baseValueOverride}
        // — `VLDM`/`VSTM Rn=pc` (raro, mas legal quando `W=0`) precisa do viés `+8` do `PC`
        // arquitetural, que `core.register(15)` NÃO reflete durante a execução deste helper (só
        // depois que o bloco termina — ver {@code IrVfpExecutor#executeVfpMultipleTransfer}).
        int baseValue = baseValueOverride != -1 ? baseValueOverride : core.register(base);
        int address = decrementBefore ? baseValue - totalBytes : baseValue;
        for (int i = 0; i < count; i++) {
            int reg = firstRegister + i;
            if (load) {
                if (doublePrecision) {
                    int low = AsmMemoryHelpers.loadWord(core, address);
                    int high = AsmMemoryHelpers.loadWord(core, address + 4);
                    vfp.setD(reg, packDoubleWords(low, high));
                } else {
                    vfp.setS(reg, AsmMemoryHelpers.loadWord(core, address));
                }
            } else {
                if (doublePrecision) {
                    long bits = vfp.d(reg);
                    AsmMemoryHelpers.storeWord(core, address, (int) bits);
                    AsmMemoryHelpers.storeWord(core, address + 4, (int) (bits >>> 32));
                } else {
                    AsmMemoryHelpers.storeWord(core, address, vfp.s(reg));
                }
            }
            address += registerSizeBytes;
        }
        if (writeback) {
            core.setRegister(base, decrementBefore ? baseValue - totalBytes : baseValue + totalBytes);
        }
    }

    /// `VMOV Rt,Rt2,Dm` / `VMOV Dm,Rt,Rt2` (`FMRRD`/`FMDRR`): espelha
    /// {@code IrVfpExecutor#executeVfpCorePairTransfer}. Toca `armLow`/`armHigh` diretamente —
    /// cercado por {@code emitSpilled}, mesma razão de {@link #executeVfpMultipleTransfer}.
    public static void executeVfpCorePairTransfer(ArmCore core, boolean toArmRegisters, int armLow, int armHigh, int vm) {
        VfpRegisters vfp = core.vfp();
        if (toArmRegisters) {
            long bits = vfp.d(vm);
            core.setRegister(armLow, (int) bits);
            core.setRegister(armHigh, (int) (bits >>> 32));
        } else {
            int low = core.register(armLow);
            int high = core.register(armHigh);
            vfp.setD(vm, packDoubleWords(low, high));
        }
    }

    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`): espelha {@code IrVfpExecutor#executeVfpSystemTransfer},
    /// incl. o caso especial `VMRS APSR_nzcv, FPSCR` (`read=true, armRegister=15`), que copia só
    /// `FPSCR.NZCV` para `CPSR.NZCV` sem escrever `R15`. Toca `armRegister` diretamente — cercado
    /// por {@code emitSpilled}, mesma razão de {@link #executeVfpMultipleTransfer}.
    public static void executeVfpSystemTransfer(ArmCore core, boolean read, int armRegister) {
        if (read) {
            if (armRegister == VFP_APSR_NZCV_ENCODING) {
                FpscrRegister fpscr = core.fpscr();
                core.cpsr().setNzcv(fpscr.n(), fpscr.z(), fpscr.c(), fpscr.v());
            } else {
                core.setRegister(armRegister, core.fpscr().value());
            }
        } else {
            core.fpscr().setValue(core.register(armRegister));
        }
    }
}
