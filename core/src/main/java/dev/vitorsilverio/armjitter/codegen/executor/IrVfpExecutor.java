package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
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
            // VMAXNM/VMINNM (B14.4): variante "numérica" de max/min — se só um operando é NaN, o
            // resultado é o OUTRO; delega ao mesmo núcleo do `FMAXNM`/`FMINNM` A64 (Ir64FpExecutor),
            // NUNCA `Math.max`/`Math.min` (Armadilha 3 da task — aqueles não tratam NaN assim).
            case MAXNM -> AdvSimdLanes.maxNum(vn, vm);
            case MINNM -> AdvSimdLanes.minNum(vn, vm);
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
            case MAXNM -> AdvSimdLanes.maxNum(vn, vm);
            case MINNM -> AdvSimdLanes.minNum(vn, vm);
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

    /// `VSEL` (B14.4): `vd = selectCondition ? vn : vm` — cópia de BITS crua (nunca aritmética:
    /// não normaliza NaN, não toca `FPSCR`). {@link IrOp.VfpSelect#selectCondition} é avaliado
    /// contra o **CPSR** ({@link ArmCore#cpsr()}), nunca o FPSCR — diferente de {@link #executeVfpCompare}.
    /// {@link IrOp.VfpSelect#condition} (sempre `AL`, espaço incondicional) só gate o bloco, nunca
    /// decide `vn`/`vm` (ver Armadilha 2 da task: os dois campos não podem se confundir).
    public void executeVfpSelect(ArmCore core, IrOp.VfpSelect op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        boolean selectVn = core.cpsr().evalCond(op.selectCondition());
        VfpRegisters vfp = core.vfp();
        if (op.doublePrecision()) {
            vfp.setD(op.vd(), selectVn ? vfp.d(op.vn()) : vfp.d(op.vm()));
        } else {
            vfp.setS(op.vd(), selectVn ? vfp.s(op.vn()) : vfp.s(op.vm()));
        }
    }

    /// `VRINT{A,N,P,M}` (B14.5): arredonda `vm` para valor integral MANTENDO ponto flutuante,
    /// usando a direção da PRÓPRIA instrução ({@link IrOp.VfpRound#direction}) — nunca
    /// `FPSCR.RMode`. Delega a {@link AdvSimdLanes#roundForConversion}, mesmo núcleo do `FRINTx`
    /// A64 (`NaN`/infinito passam adiante inalterados, tratado lá). Sem flush-to-zero (mesma
    /// decisão de {@link #executeVfpConvert}, que também não aplica `FZ`).
    public void executeVfpRound(ArmCore core, IrOp.VfpRound op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        if (op.doublePrecision()) {
            double rounded = AdvSimdLanes.roundForConversion(vfp.dDouble(op.vm()), effectiveDirection(core, op.direction()));
            vfp.setDDouble(op.vd(), rounded);
        } else {
            double rounded = AdvSimdLanes.roundForConversion(vfp.sFloat(op.vm()), effectiveDirection(core, op.direction()));
            vfp.setSFloat(op.vd(), (float) rounded);
        }
    }

    /// `VCVT{A,N,P,M}{S,U}` (B14.5): converte `vm` para inteiro de 32 bits em `vd` (SEMPRE `S`),
    /// com sinal `op.signed()`, arredondando pela direção da PRÓPRIA instrução
    /// ({@link IrOp.VfpConvertRounded#direction}). Mesma composição de
    /// {@link AdvSimdLanes#roundForConversion} + {@link AdvSimdLanes#saturateToInteger} que o A64
    /// já usa para `FCVTAS`/`FCVTAU`/etc — `NaN`→`0`, fora de faixa→saturação, nunca duplicado.
    public void executeVfpConvertRounded(ArmCore core, IrOp.VfpConvertRounded op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        double value = op.doublePrecision() ? vfp.dDouble(op.vm()) : vfp.sFloat(op.vm());
        double rounded = AdvSimdLanes.roundForConversion(value, effectiveDirection(core, op.direction()));
        long saturated = AdvSimdLanes.saturateToInteger(rounded, op.signed(), false);
        vfp.setS(op.vd(), (int) saturated);
    }

    /// `VMOVX`/`VINS` (B14.6): troca CRUA de metades de 16 bits de um `S`, sem interpretar o float
    /// (nunca arredonda, nunca toca `FPSCR`) — ver Javadoc de {@link IrOp.VfpMoveHalfLane}.
    /// `VMOVX` usa `>>>` (nunca `>>`) para zerar a metade alta do destino automaticamente. `VINS`
    /// lê o `vd` ATUAL antes de escrever, para preservar `vd[15:0]`.
    public void executeVfpMoveHalfLane(ArmCore core, IrOp.VfpMoveHalfLane op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        if (op.insert()) {
            int lowHalfOfSource = vfp.s(op.vm()) & 0xFFFF;
            int lowHalfOfDestinationPreserved = vfp.s(op.vd()) & 0xFFFF;
            vfp.setS(op.vd(), (lowHalfOfSource << 16) | lowHalfOfDestinationPreserved);
        } else {
            vfp.setS(op.vd(), vfp.s(op.vm()) >>> 16);
        }
    }

    /// Direção efetiva de `VRINT*`/`VCVT*` (B22.7): a da PRÓPRIA instrução quando ela a carrega
    /// (`VRINTA/N/P/M`/`VRINTZ`/`VCVT{A,N,P,M}`, que já vêm resolvidos pelo decoder), ou — quando
    /// `direction == null` (`VRINTR`/`VRINTX`/`VCVTR`) — o modo CORRENTE de `FPSCR.RMode`. As duas
    /// tabelas de modo NÃO coincidem (a de `FPSCR.RMode` não tem "ties away"); o mapeamento é
    /// explícito, nunca por ordinal.
    private static AdvSimdLanes.RoundingMode effectiveDirection(ArmCore core, AdvSimdLanes.RoundingMode direction) {
        if (direction != null) {
            return direction;
        }
        return switch (core.fpscr().roundingMode()) {
            case ROUND_TO_NEAREST -> AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN;
            case ROUND_TOWARD_PLUS_INFINITY -> AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY;
            case ROUND_TOWARD_MINUS_INFINITY -> AdvSimdLanes.RoundingMode.TOWARD_NEGATIVE_INFINITY;
            case ROUND_TOWARD_ZERO -> AdvSimdLanes.RoundingMode.TOWARD_ZERO;
        };
    }

    /// `VCVTB`/`VCVTT` (B22.7): ver {@link IrOp.VfpConvertHalfPrecision}. As metades de 16 bits de um
    /// `S` são `bits[15:0]` (`VCVTB`) e `bits[31:16]` (`VCVTT`); as formas "para half" gravam SÓ a
    /// metade selecionada e preservam a outra (lendo o `vd` atual antes de escrever). `F64_TO_F16`
    /// arredonda UMA vez de `double` para binary16 ({@link AdvSimdLanes#doubleToHalfBits}, nunca via
    /// `float` intermediário — double rounding erraria os empates).
    public void executeVfpConvertHalfPrecision(ArmCore core, IrOp.VfpConvertHalfPrecision op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        int halfShift = op.top() ? HALF_TOP_SHIFT : 0;
        switch (op.conversion()) {
            case F16_TO_F32 -> vfp.setSFloat(op.vd(), AdvSimdLanes.halfToFloat(vfp.s(op.vm()) >>> halfShift & HALF_MASK));
            case F16_TO_F64 -> vfp.setDDouble(op.vd(), AdvSimdLanes.halfToFloat(vfp.s(op.vm()) >>> halfShift & HALF_MASK));
            case F32_TO_F16 -> insertHalf(vfp, op.vd(), halfShift, AdvSimdLanes.halfBits(vfp.sFloat(op.vm())));
            case F64_TO_F16 -> insertHalf(vfp, op.vd(), halfShift,
                    AdvSimdLanes.doubleToHalfBits(vfp.dDouble(op.vm()), false));
            case F32_TO_BF16 -> insertHalf(vfp, op.vd(), halfShift, AdvSimdLanes.bf16Bits(vfp.sFloat(op.vm())));
        }
    }

    /// Deslocamento da metade ALTA de um `S` (`VCVTT`).
    private static final int HALF_TOP_SHIFT = 16;

    /// Grava `halfBits` (16 bits) em `Sd` deslocado por `halfShift`, preservando a outra metade.
    private static void insertHalf(VfpRegisters vfp, int vd, int halfShift, long halfBits) {
        int preserved = vfp.s(vd) & ~(HALF_MASK << halfShift);
        vfp.setS(vd, preserved | (((int) halfBits & HALF_MASK) << halfShift));
    }

    /// `VJCVT` (B22.7, `FEAT_JSCVT`): `Sd = ToInt32(Dm)` (módulo 2³², ver
    /// {@link AdvSimdLanes#javascriptToInt32}) e `FPSCR.{N,Z,C,V} = 0,exato,0,0` — QEMU
    /// `HELPER(vjcvt)`: `Z` recebe a EXATIDÃO da conversão e os outros três são zerados. Os demais
    /// bits do `FPSCR` permanecem intactos.
    public void executeVfpJavascriptConvert(ArmCore core, IrOp.VfpJavascriptConvert op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        double value = vfp.dDouble(op.vm());
        vfp.setS(op.vd(), AdvSimdLanes.javascriptToInt32(value));
        int zeroFlag = AdvSimdLanes.javascriptToInt32IsExact(value) ? FpscrRegister.ZERO_FLAG : 0;
        core.fpscr().setNzcv(zeroFlag);
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
            // B14.6b: `VCVT_int_hp`/`VCVT_hp_int` — ponte pelo mesmo núcleo FP16 do NEON
            // ({@link AdvSimdLanes#halfBits}/{@link AdvSimdLanes#halfToFloat}), nunca conversão
            // de bits nova (RFC B13.2 D1).
            case S32_TO_F16 -> vfp.setS(op.vd(), (int) AdvSimdLanes.halfBits((float) vfp.s(op.vm())) & 0xFFFF);
            case U32_TO_F16 -> vfp.setS(op.vd(),
                    (int) AdvSimdLanes.halfBits((float) Integer.toUnsignedLong(vfp.s(op.vm()))) & 0xFFFF);
            case F16_TO_S32 -> vfp.setS(op.vd(), (int) AdvSimdLanes.halfToFloat(vfp.s(op.vm()) & 0xFFFF));
            case F16_TO_U32 -> vfp.setS(op.vd(), toUnsignedInt32((double) AdvSimdLanes.halfToFloat(vfp.s(op.vm()) & 0xFFFF)));
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
        if (op.isLaneTransfer()) {
            executeVfpLaneTransfer(core, op);
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

    /// B22.10 — `VMOV.{S8,U8,S16,U16}` (`Rt = Dd[lane]`) e `VMOV.{8,16}` (`Dd[lane] = Rt`) do NEON: transfere
    /// UM elemento de `D<vn>` (`0`-`31`). Leitura estende por sinal ou zero conforme `signExtend`;
    /// escrita altera só o elemento, preservando o resto do `D`.
    private void executeVfpLaneTransfer(ArmCore core, IrOp.VfpCoreTransfer op) {
        int elementBits = op.laneBits();
        int shift = op.lane() * elementBits;
        long mask = (1L << elementBits) - 1;
        long doubleword = core.vfp().d(op.vn());
        if (op.toArmRegister()) {
            long element = (doubleword >>> shift) & mask;
            int value = (int) element;
            if (op.signExtend()) {
                value = (value << (Integer.SIZE - elementBits)) >> (Integer.SIZE - elementBits);
            }
            core.setRegister(op.armRegister(), value);
        } else {
            long inserted = core.register(op.armRegister()) & mask;
            core.vfp().setD(op.vn(), (doubleword & ~(mask << shift)) | (inserted << shift));
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

    /// `VLDR_sysreg`/`VSTR_sysreg` (perfil M, B15.3): move o valor bruto de `ArmCore.fpscr()` de/para
    /// `[base {+,-}offsetBytes]` — mesma aritmética de endereço de {@link IrMemoryExecutor#executeLoad}/
    /// {@link IrMemoryExecutor#executeStore} (`postIndexed ? base : base + offsetBytes`, seguido de
    /// `base + offsetBytes` quando {@code writeback}), só que o destino/origem é `ArmCore.fpscr()`,
    /// não um GPR.
    public void executeVfpSysregMemoryTransfer(ArmCore core, IrOp.VfpSysregMemoryTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int base = core.register(op.base());
        int address = op.postIndexed() ? base : base + op.offsetBytes();
        if (op.load()) {
            core.fpscr().setValue(support.read32Arm7(core, address));
        } else {
            support.write32Arm7(core, address, core.fpscr().value());
        }
        if (op.writeback()) {
            core.setRegister(op.base(), base + op.offsetBytes());
        }
    }

    /// `VSCCLRM` (perfil M, B15.5): zera `D<primeiro>`..`D<último>` ou `S<primeiro>`..`S<último>`
    /// (conforme {@link IrOp.Vscclrm#doublePrecision()}), recortando o limite superior ao tamanho
    /// real do banco (`lastRegister` pode vir de um `imm` grande, encoding `UNPREDICTABLE`) — nunca
    /// lança, só ignora os registradores fora do banco. Nenhuma dependência de FPU real: o
    /// armazenamento `D`/`S` já existe incondicionalmente desde a B3.3.
    public void executeVscclrm(ArmCore core, IrOp.Vscclrm op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        int bankLimit = (op.doublePrecision() ? VfpRegisters.DOUBLE_COUNT : VfpRegisters.SINGLE_COUNT) - 1;
        int last = Math.min(op.lastRegister(), bankLimit);
        if (op.doublePrecision()) {
            for (int d = op.firstRegister(); d <= last; d++) {
                vfp.setD(d, 0L);
            }
        } else {
            for (int s = op.firstRegister(); s <= last; s++) {
                vfp.setS(s, 0);
            }
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
                vfp.setD(op.vd(), doubleToFixed(vfp.dDouble(op.vd()) * scale, op.fixedPointIs32Bit(), op.unsignedFixedPoint()));
            } else {
                vfp.setDDouble(op.vd(), fixedToDouble(vfp.d(op.vd()), op.fixedPointIs32Bit(), op.unsignedFixedPoint()) / scale);
            }
        } else if (op.toFixedPoint()) {
            vfp.setS(op.vd(), (int) doubleToFixed((double) vfp.sFloat(op.vd()) * scale, op.fixedPointIs32Bit(), op.unsignedFixedPoint()));
        } else {
            vfp.setSFloat(op.vd(), (float) (fixedToDouble(vfp.s(op.vd()), op.fixedPointIs32Bit(), op.unsignedFixedPoint()) / scale));
        }
    }

    /// `VCVT_fix_hp` (B14.6b) — espelho de {@link #executeVfpConvertFixed} em meia precisão (só a
    /// forma `sp`-like existe, ver {@link IrOp.VfpConvertFixedHalf}).
    public void executeVfpConvertFixedHalf(ArmCore core, IrOp.VfpConvertFixedHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        double scale = Math.scalb(1.0, op.fractionBits());
        if (op.toFixedPoint()) {
            double scaled = AdvSimdLanes.halfToFloat(vfp.s(op.vd()) & 0xFFFF) * scale;
            vfp.setS(op.vd(), (int) doubleToFixed(scaled, op.fixedPointIs32Bit(), op.unsignedFixedPoint()));
        } else {
            double value = fixedToDouble(vfp.s(op.vd()), op.fixedPointIs32Bit(), op.unsignedFixedPoint()) / scale;
            vfp.setS(op.vd(), (int) AdvSimdLanes.halfBits((float) value) & 0xFFFF);
        }
    }

    /// Extrai o inteiro fixo (com/sem sinal, 16/32 bits) empacotado nos bits baixos de `raw` e
    /// devolve seu valor `double` já sem a escala (o `/scale` fica a cargo do chamador). `raw` é o
    /// registrador INTEIRO (32 bits em `sp`, 64 em `dp`) — só os `fixedPointIs32Bit ? 32 : 16` bits
    /// baixos importam, o resto é ignorado (QEMU passa o registrador cheio e o `itype` do helper
    /// trunca; nunca lançamos por excesso de bits altos, mesma tolerância do hardware real).
    private static double fixedToDouble(long raw, boolean fixedPointIs32Bit, boolean unsignedFixedPoint) {
        if (fixedPointIs32Bit) {
            return unsignedFixedPoint ? (double) (raw & 0xFFFF_FFFFL) : (double) (int) raw;
        }
        long masked = raw & 0xFFFFL;
        return unsignedFixedPoint ? (double) masked : (double) (short) masked;
    }

    /// Converte `scaledValue` (já multiplicado por `2^fractionBits`) para o inteiro fixo de 16/32
    /// bits, truncando para zero e saturando na largura/sinal do campo — devolve o valor NUMÉRICO
    /// já sinalizado/sem sinal corretamente (não os bits empacotados): para `sp`, `(int)` do
    /// resultado já trunca certo; para `dp`, o `long` já é a extensão de sinal/zero de 64 bits
    /// correta para {@link VfpRegisters#setD} (QEMU: o helper devolve um `int16_t`/`int32_t`
    /// implicitamente convertido para o `uint64_t` de retorno — sign/zero-extend, nunca zero alto).
    private static long doubleToFixed(double scaledValue, boolean fixedPointIs32Bit, boolean unsignedFixedPoint) {
        int bits = fixedPointIs32Bit ? 32 : 16;
        double minValue = unsignedFixedPoint ? 0.0 : -Math.scalb(1.0, bits - 1);
        double maxValue = unsignedFixedPoint ? Math.scalb(1.0, bits) - 1.0 : Math.scalb(1.0, bits - 1) - 1.0;
        double truncated = scaledValue < 0 ? Math.ceil(scaledValue) : Math.floor(scaledValue);
        if (Double.isNaN(truncated)) {
            truncated = 0.0;
        }
        return (long) Math.max(minValue, Math.min(maxValue, truncated));
    }

    // ── B14.6b: aritmética `_hp` (FEAT_FP16) — helpers de leitura/escrita com flush-to-zero em
    // meia precisão (denormal-as-zero é definido sobre o formato half, nunca sobre o float
    // intermediário usado para computar) + os 9 `execute*Half`. ──

    private static final int HALF_SIGN_BIT = 0x8000;
    private static final int HALF_EXPONENT_MASK = 0x7C00;
    private static final int HALF_MANTISSA_MASK = 0x03FF;

    /// Zera `bits` (preservando o sinal) quando `flushToZero` e `bits` representa um subnormal
    /// binary16 (expoente zero, mantissa não-zero) — mesma decisão de {@link #flushSingle}/
    /// {@link #flushDouble}, aplicada ao formato half.
    private static int flushHalfBits(int bits, boolean flushToZero) {
        if (flushToZero && (bits & HALF_EXPONENT_MASK) == 0 && (bits & HALF_MANTISSA_MASK) != 0) {
            return bits & HALF_SIGN_BIT;
        }
        return bits;
    }

    /// Lê `Sx[15:0]` como `float` (widening exato, {@link AdvSimdLanes#halfToFloat}), aplicando
    /// flush-to-zero à ENTRADA antes de converter (denormal-as-zero).
    private static float readHalfOperand(VfpRegisters vfp, int reg, boolean flushToZero) {
        return AdvSimdLanes.halfToFloat(flushHalfBits(vfp.s(reg) & 0xFFFF, flushToZero));
    }

    /// `VADD_hp`…`VFNMA_hp`/`VABS_hp`/`VNEG_hp`/`VSQRT_hp`/`VMAXNM_hp`/`VMINNM_hp` (B14.6b).
    /// `NEG`/`ABS` são manipulação de BITS crua (bit de sinal na posição 15 do half) — não passam
    /// por flush/arredondamento, mesma decisão de {@link #computeSingle} para as formas `sp`.
    public void executeVfpAluHalf(ArmCore core, IrOp.VfpAluHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        boolean flushToZero = core.fpscr().flushToZero();
        int vmBits = vfp.s(op.vm()) & 0xFFFF;
        int resultBits = switch (op.op()) {
            case NEG -> vmBits ^ HALF_SIGN_BIT;
            case ABS -> vmBits & ~HALF_SIGN_BIT;
            default -> computeHalfArithmeticBits(vfp, op, flushToZero);
        };
        vfp.setS(op.vd(), resultBits);
    }

    /// Ramo aritmético de {@link #executeVfpAluHalf} — espelha {@link #computeSingleArithmetic},
    /// mas cada resultado é narrowed a binary16 via {@link AdvSimdLanes#halfBits} (arredondamento
    /// ties-to-even do JDK, `Float.float16ToFloat`/`floatToFloat16`, mesmo núcleo do FP16 NEON já
    /// validado — Armadilha 7 da task: NÃO é o mesmo que arredondar em `float` e converter, mas
    /// como aqui a aritmética JÁ é feita em `float` a partir de operandos widened exatamente de
    /// `half`, o único arredondamento real é este narrow final — mesma composição de
    /// {@link AdvSimdLanes#halfThreeSame}). `MLA`/`MLS`/`NMLA`/`NMLS` (NÃO fundidos) narrowam o
    /// PRODUTO a half antes de acumular (dois arredondamentos), igual ao núcleo NEON.
    private static int computeHalfArithmeticBits(VfpRegisters vfp, IrOp.VfpAluHalf op, boolean flushToZero) {
        float vn = op.vn() >= 0 ? readHalfOperand(vfp, op.vn(), flushToZero) : 0f;
        float vm = readHalfOperand(vfp, op.vm(), flushToZero);
        float result = switch (op.op()) {
            case ADD -> vn + vm;
            case SUB -> vn - vm;
            case MUL -> vn * vm;
            case DIV -> vn / vm;
            case MLA -> {
                float vd = readHalfOperand(vfp, op.vd(), flushToZero);
                float product = AdvSimdLanes.halfToFloat(AdvSimdLanes.halfBits(vn * vm));
                yield vd + product;
            }
            case MLS -> {
                float vd = readHalfOperand(vfp, op.vd(), flushToZero);
                float product = AdvSimdLanes.halfToFloat(AdvSimdLanes.halfBits(vn * vm));
                yield vd - product;
            }
            case NMLA -> {
                float vd = -readHalfOperand(vfp, op.vd(), flushToZero);
                float product = -AdvSimdLanes.halfToFloat(AdvSimdLanes.halfBits(vn * vm));
                yield vd + product;
            }
            case NMLS -> {
                float vd = -readHalfOperand(vfp, op.vd(), flushToZero);
                float product = AdvSimdLanes.halfToFloat(AdvSimdLanes.halfBits(vn * vm));
                yield vd + product;
            }
            case NMUL -> -(vn * vm);
            case SQRT -> (float) Math.sqrt((double) vm);
            case FMA -> {
                float vd = readHalfOperand(vfp, op.vd(), flushToZero);
                yield Math.fma(vn, vm, vd);
            }
            case FMS -> {
                float vd = readHalfOperand(vfp, op.vd(), flushToZero);
                yield Math.fma(-vn, vm, vd);
            }
            case FNMA -> {
                float vd = readHalfOperand(vfp, op.vd(), flushToZero);
                yield Math.fma(-vn, vm, -vd);
            }
            case FNMS -> {
                float vd = readHalfOperand(vfp, op.vd(), flushToZero);
                yield Math.fma(vn, vm, -vd);
            }
            case MAXNM -> AdvSimdLanes.maxNum(vn, vm);
            case MINNM -> AdvSimdLanes.minNum(vn, vm);
            case NEG, ABS, COPY -> throw new IllegalStateException("tratado em executeVfpAluHalf");
        };
        return flushHalfBits((int) AdvSimdLanes.halfBits(result) & 0xFFFF, flushToZero);
    }

    /// `VMOV.F16 Vd,#imm` (B14.6b).
    public void executeVfpMoveImmediateHalf(ArmCore core, IrOp.VfpMoveImmediateHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        core.vfp().setS(op.vd(), op.immediateBits() & 0xFFFF);
    }

    /// `VCMP_hp`/`VCMPE_hp` (B14.6b) — mesma tabela de {@link #executeVfpCompare}.
    public void executeVfpCompareHalf(ArmCore core, IrOp.VfpCompareHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        boolean flushToZero = core.fpscr().flushToZero();
        float a = readHalfOperand(vfp, op.vd(), flushToZero);
        float b = op.compareWithZero() ? 0f : readHalfOperand(vfp, op.vm(), flushToZero);
        boolean unordered = Float.isNaN(a) || Float.isNaN(b);
        boolean equal = !unordered && a == b;
        boolean less = !unordered && a < b;
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

    /// `VSEL_hp` (B14.6b) — cópia de BITS crua dos 16 bits baixos (zero-estendida ao escrever,
    /// mesma convenção de {@link #executeVfpMoveHalfLane}), nunca aritmética.
    public void executeVfpSelectHalf(ArmCore core, IrOp.VfpSelectHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        boolean selectVn = core.cpsr().evalCond(op.selectCondition());
        VfpRegisters vfp = core.vfp();
        int sourceBits = (selectVn ? vfp.s(op.vn()) : vfp.s(op.vm())) & 0xFFFF;
        vfp.setS(op.vd(), sourceBits);
    }

    /// `VRINT{A,N,P,M}_hp` (B14.6b) — mesmo núcleo de {@link #executeVfpRound}.
    public void executeVfpRoundHalf(ArmCore core, IrOp.VfpRoundHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        float vm = AdvSimdLanes.halfToFloat(vfp.s(op.vm()) & 0xFFFF);
        double rounded = AdvSimdLanes.roundForConversion(vm, effectiveDirection(core, op.direction()));
        vfp.setS(op.vd(), (int) AdvSimdLanes.halfBits((float) rounded) & 0xFFFF);
    }

    /// `VCVT{A,N,P,M}{S,U}_hp` (B14.6b) — mesmo núcleo de {@link #executeVfpConvertRounded}.
    public void executeVfpConvertRoundedHalf(ArmCore core, IrOp.VfpConvertRoundedHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        VfpRegisters vfp = core.vfp();
        double value = AdvSimdLanes.halfToFloat(vfp.s(op.vm()) & 0xFFFF);
        double rounded = AdvSimdLanes.roundForConversion(value, effectiveDirection(core, op.direction()));
        long saturated = AdvSimdLanes.saturateToInteger(rounded, op.signed(), false);
        vfp.setS(op.vd(), (int) saturated);
    }

    /// `VLDR_hp` (B14.6b) — carrega um halfword (2 bytes), zero-estendendo `Vd[31:16]`.
    public void executeVfpLoadHalf(ArmCore core, IrOp.VfpLoadHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int address = support.registerValue(core, op.base(), op.baseValueOverride()) + op.offsetBytes();
        core.vfp().setS(op.vd(), support.read16Arm7(core, address, false) & 0xFFFF);
    }

    /// `VSTR_hp` (B14.6b) — grava só os 16 bits baixos de `Vd` (ver {@link #executeVfpLoadHalf}).
    public void executeVfpStoreHalf(ArmCore core, IrOp.VfpStoreHalf op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int address = support.registerValue(core, op.base(), op.baseValueOverride()) + op.offsetBytes();
        support.write16Arm7(core, address, core.vfp().s(op.vd()) & 0xFFFF);
    }
}
