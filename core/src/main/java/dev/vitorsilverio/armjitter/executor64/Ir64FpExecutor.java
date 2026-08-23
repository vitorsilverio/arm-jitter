package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Executa a IR de FP escalar de A64 (B6.5.2): aritmética/unárias, comparação, conversão — sibling
/// de {@link dev.vitorsilverio.armjitter.codegen.executor.IrVfpExecutor} (VFP32), mas sem estado
/// próprio (nenhuma operação desta fatia toca memória — B6.5.2 D3: load/store FP ficam de fora),
/// por isso métodos estáticos em vez de uma instância com {@code IrExecutionSupport}. É o oráculo
/// semântico (G1) até que B6.5.4 emita nativamente.
final class Ir64FpExecutor {
    private Ir64FpExecutor() {
    }

    /// `FADD`/`FSUB`/`FMUL`/`FDIV`/`FNEG`/`FABS`/`FMOV` registrador↔registrador.
    static boolean executeFpAlu(Aarch64Core core, Ir64Op.Fp64Alu op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.doublePrecision()) {
            fp.setD(op.vd(), computeDouble(fp, op));
        } else {
            fp.setS(op.vd(), computeSingle(fp, op));
        }
        return false;
    }

    private static int computeSingle(Aarch64FpRegisters fp, Ir64Op.Fp64Alu op) {
        return switch (op.op()) {
            case ADD -> Float.floatToRawIntBits(fp.sFloat(op.vn()) + fp.sFloat(op.vm()));
            case SUB -> Float.floatToRawIntBits(fp.sFloat(op.vn()) - fp.sFloat(op.vm()));
            case MUL -> Float.floatToRawIntBits(fp.sFloat(op.vn()) * fp.sFloat(op.vm()));
            case DIV -> Float.floatToRawIntBits(fp.sFloat(op.vn()) / fp.sFloat(op.vm()));
            // NMUL: nega o PRODUTO (`vn*vm`), não um dos fatores — resultado idêntico em valor,
            // mas o sinal de um NaN de entrada só é preservado negando o produto já calculado
            // (mesma leitura que o precedente VFP32, IrVfpExecutor#computeSingle/NMUL).
            case NMUL -> Float.floatToRawIntBits(-(fp.sFloat(op.vn()) * fp.sFloat(op.vm())));
            case SQRT -> Float.floatToRawIntBits((float) Math.sqrt(fp.sFloat(op.vm())));
            // MAX/MIN: `Math.max`/`Math.min` já implementam a semântica ARM exata para float —
            // propagam NaN de qualquer operando e preferem +0.0 sobre -0.0 (MAX)/-0.0 sobre +0.0
            // (MIN) quando os dois são zero com sinais diferentes (ARM DDI 0487 FPMax/FPMin).
            case MAX -> Float.floatToRawIntBits(Math.max(fp.sFloat(op.vn()), fp.sFloat(op.vm())));
            case MIN -> Float.floatToRawIntBits(Math.min(fp.sFloat(op.vn()), fp.sFloat(op.vm())));
            // MAXNM/MINNM: variante "numérica" — se só um operando é NaN, o resultado é o OUTRO
            // (não NaN), diferente de MAX/MIN puro. Só quando os dois são NaN o resultado é NaN.
            case MAXNM -> Float.floatToRawIntBits(maxNum(fp.sFloat(op.vn()), fp.sFloat(op.vm())));
            case MINNM -> Float.floatToRawIntBits(minNum(fp.sFloat(op.vn()), fp.sFloat(op.vm())));
            // NEG/ABS manipulam o bit de sinal diretamente (mesma armadilha crítica de
            // IrVfpExecutor: `0-x`/Math.abs canonicalizam NaN e quebram `-0.0`).
            case NEG -> fp.s(op.vm()) ^ Integer.MIN_VALUE;
            case ABS -> fp.s(op.vm()) & Integer.MAX_VALUE;
            // MOV (registrador↔registrador): cópia de bits crus, nunca via view float/double.
            case MOV -> fp.s(op.vm());
        };
    }

    private static long computeDouble(Aarch64FpRegisters fp, Ir64Op.Fp64Alu op) {
        return switch (op.op()) {
            case ADD -> Double.doubleToRawLongBits(fp.dDouble(op.vn()) + fp.dDouble(op.vm()));
            case SUB -> Double.doubleToRawLongBits(fp.dDouble(op.vn()) - fp.dDouble(op.vm()));
            case MUL -> Double.doubleToRawLongBits(fp.dDouble(op.vn()) * fp.dDouble(op.vm()));
            case DIV -> Double.doubleToRawLongBits(fp.dDouble(op.vn()) / fp.dDouble(op.vm()));
            case NMUL -> Double.doubleToRawLongBits(-(fp.dDouble(op.vn()) * fp.dDouble(op.vm())));
            case SQRT -> Double.doubleToRawLongBits(Math.sqrt(fp.dDouble(op.vm())));
            case MAX -> Double.doubleToRawLongBits(Math.max(fp.dDouble(op.vn()), fp.dDouble(op.vm())));
            case MIN -> Double.doubleToRawLongBits(Math.min(fp.dDouble(op.vn()), fp.dDouble(op.vm())));
            case MAXNM -> Double.doubleToRawLongBits(maxNum(fp.dDouble(op.vn()), fp.dDouble(op.vm())));
            case MINNM -> Double.doubleToRawLongBits(minNum(fp.dDouble(op.vn()), fp.dDouble(op.vm())));
            case NEG -> fp.d(op.vm()) ^ Long.MIN_VALUE;
            case ABS -> fp.d(op.vm()) & Long.MAX_VALUE;
            case MOV -> fp.d(op.vm());
        };
    }

    /// `FPMaxNum` (`ARM DDI 0487`): se exatamente um operando é NaN, devolve o OUTRO; se os dois
    /// são NaN, devolve NaN; senão, `Math.max` normal (mesma semântica de sinal de zero do `MAX`).
    private static float maxNum(float a, float b) {
        if (Float.isNaN(a)) {
            return Float.isNaN(b) ? a : b;
        }
        return Float.isNaN(b) ? a : Math.max(a, b);
    }

    private static double maxNum(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? a : b;
        }
        return Double.isNaN(b) ? a : Math.max(a, b);
    }

    /// `FPMinNum` — espelho de {@link #maxNum(float, float)} com `Math.min`.
    private static float minNum(float a, float b) {
        if (Float.isNaN(a)) {
            return Float.isNaN(b) ? a : b;
        }
        return Float.isNaN(b) ? a : Math.min(a, b);
    }

    private static double minNum(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? a : b;
        }
        return Double.isNaN(b) ? a : Math.min(a, b);
    }

    /// `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB` (B8.4): multiplicação-acumulação fundida com
    /// arredondamento único (`Math.fma`). As negações acontecem no BIT DE SINAL antes da `fma`
    /// (nunca `-x` aritmético) — mesma armadilha de {@link Ir64Op.Fp64Operation#NEG}, ver
    /// javadoc de {@link Ir64Op.Fp64MultiplyAdd}.
    static boolean executeFpMultiplyAdd(Aarch64Core core, Ir64Op.Fp64MultiplyAdd op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.doublePrecision()) {
            long vnBits = op.negateProduct() ? fp.d(op.vn()) ^ Long.MIN_VALUE : fp.d(op.vn());
            long vaBits = op.negateAddend() ? fp.d(op.va()) ^ Long.MIN_VALUE : fp.d(op.va());
            double n = Double.longBitsToDouble(vnBits);
            double m = fp.dDouble(op.vm());
            double a = Double.longBitsToDouble(vaBits);
            fp.setDDouble(op.vd(), Math.fma(n, m, a));
        } else {
            int vnBits = op.negateProduct() ? fp.s(op.vn()) ^ Integer.MIN_VALUE : fp.s(op.vn());
            int vaBits = op.negateAddend() ? fp.s(op.va()) ^ Integer.MIN_VALUE : fp.s(op.va());
            float n = Float.intBitsToFloat(vnBits);
            float m = fp.sFloat(op.vm());
            float a = Float.intBitsToFloat(vaBits);
            fp.setSFloat(op.vd(), Math.fma(n, m, a));
        }
        return false;
    }

    /// `FMOV Sd, #imm`/`FMOV Dd, #imm`: os bits já vêm expandidos do decoder (B6.5.3) — o
    /// executor só grava.
    static boolean executeFpMoveImmediate(Aarch64Core core, Ir64Op.Fp64MoveImmediate op) {
        if (op.doublePrecision()) {
            core.fp().setD(op.vd(), op.immediateBits());
        } else {
            core.fp().setS(op.vd(), (int) op.immediateBits());
        }
        return false;
    }

    /// `FCMP`/`FCMPE`: grava `PSTATE.NZCV` diretamente (ver javadoc de {@link Ir64Op.Fp64Compare}
    /// — diferente do precedente VFP32, não existe um segundo passo de transferência de flags em
    /// A64). `signalOnQuietNaN` é carregado sem efeito observável (mesmo precedente de
    /// {@code IrVfpExecutor#executeVfpCompare}, que também não o consulta — este core não modela
    /// traps de exceção de ponto flutuante).
    static boolean executeFpCompare(Aarch64Core core, Ir64Op.Fp64Compare op) {
        Aarch64FpRegisters fp = core.fp();
        boolean unordered;
        boolean equal;
        boolean less;
        if (op.doublePrecision()) {
            double a = fp.dDouble(op.vn());
            double b = op.compareWithZero() ? 0.0 : fp.dDouble(op.vm());
            unordered = Double.isNaN(a) || Double.isNaN(b);
            equal = !unordered && a == b;
            less = !unordered && a < b;
        } else {
            float a = fp.sFloat(op.vn());
            float b = op.compareWithZero() ? 0f : fp.sFloat(op.vm());
            unordered = Float.isNaN(a) || Float.isNaN(b);
            equal = !unordered && a == b;
            less = !unordered && a < b;
        }
        // Mesma tabela de IrVfpExecutor#executeVfpCompare (família de resultado de comparação
        // IEEE compartilhada entre os dois mundos, Fatos de referência #2 da task).
        boolean negative;
        boolean zero;
        boolean carry;
        boolean overflow;
        if (unordered) {
            negative = false;
            zero = false;
            carry = true;
            overflow = true;
        } else if (equal) {
            negative = false;
            zero = true;
            carry = true;
            overflow = false;
        } else if (less) {
            negative = true;
            zero = false;
            carry = false;
            overflow = false;
        } else {
            negative = false;
            zero = false;
            carry = true;
            overflow = false;
        }
        core.pstate().setNzcv(negative, zero, carry, overflow);
        return false;
    }

    /// `FCVT` F32↔F64 (leitura literal do épico — ver javadoc de {@link Ir64Op.Fp64Convert}):
    /// cast direto do Java já é round-to-nearest correto para os dois sentidos (widening exato
    /// F32→F64, narrowing corretamente arredondado F64→F32 — mesma garantia que
    /// {@code IrVfpExecutor} já usa para o precedente `VCVT`/`SQRT`).
    static boolean executeFpConvert(Aarch64Core core, Ir64Op.Fp64Convert op) {
        Aarch64FpRegisters fp = core.fp();
        switch (op.conversion()) {
            case F32_TO_F64 -> fp.setDDouble(op.vd(), fp.sFloat(op.vm()));
            case F64_TO_F32 -> fp.setSFloat(op.vd(), (float) fp.dDouble(op.vm()));
        }
        return false;
    }

    /// `FCSEL` (B8.5) — só LÊ `PSTATE` (nunca escreve), mesmo padrão de
    /// {@code Ir64BlockExecutor#executeConditionalSelect} no mundo inteiro (`CSEL`).
    static boolean executeFpConditionalSelect(Aarch64Core core, Ir64Op.Fp64ConditionalSelect op) {
        Aarch64FpRegisters fp = core.fp();
        boolean useVn = core.pstate().evalCond(op.condition());
        if (op.doublePrecision()) {
            fp.setD(op.vd(), useVn ? fp.d(op.vn()) : fp.d(op.vm()));
        } else {
            fp.setS(op.vd(), useVn ? fp.s(op.vn()) : fp.s(op.vm()));
        }
        return false;
    }

    /// `FCCMP`/`FCCMPE` (B8.5) — reaproveita a MESMA tabela de resultado de
    /// {@link #executeFpCompare} quando {@link Ir64Op.Fp64ConditionalCompare#condition} é
    /// verdadeira; senão, `NZCV` recebe os 4 bits crus de {@link Ir64Op.Fp64ConditionalCompare#nzcv}
    /// diretamente, SEM ler {@link Ir64Op.Fp64ConditionalCompare#vn}/{@link
    /// Ir64Op.Fp64ConditionalCompare#vm} — mesma armadilha de `CCMP`/`CCMN`.
    static boolean executeFpConditionalCompare(Aarch64Core core, Ir64Op.Fp64ConditionalCompare op) {
        if (core.pstate().evalCond(op.condition())) {
            executeFpCompare(core, new Ir64Op.Fp64Compare(
                    op.doublePrecision(), false, op.signalOnQuietNaN(), op.vn(), op.vm()));
        } else {
            core.pstate().setNzcv(op.nzcv());
        }
        return false;
    }

    /// `FRINTN`/`FRINTP`/`FRINTM`/`FRINTZ`/`FRINTA`/`FRINTX`/`FRINTI` (B8.5) — arredonda para um
    /// valor integral, mantendo o resultado em ponto flutuante. `NaN`/infinito passam intocados
    /// (mesma convenção do restante do executor A64: não há valor integral "mais próximo" de um
    /// deles).
    static boolean executeFpRound(Aarch64Core core, Ir64Op.Fp64Round op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.doublePrecision()) {
            fp.setDDouble(op.vd(), roundToIntegral(fp.dDouble(op.vn()), op.direction()));
        } else {
            fp.setSFloat(op.vd(), (float) roundToIntegral(fp.sFloat(op.vn()), op.direction()));
        }
        return false;
    }

    private static double roundToIntegral(double value, Ir64Op.Fp64RoundingDirection direction) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return switch (direction) {
            // `Math.rint` já implementa "mais próximo, par" (ties-to-even) — IDÊNTICO ao
            // arredondamento IEEE 754 `roundToIntegralTiesToEven` que `FRINTN` pede.
            case NEAREST_TIES_EVEN -> Math.rint(value);
            case TOWARD_POSITIVE_INFINITY -> Math.ceil(value);
            case TOWARD_NEGATIVE_INFINITY -> Math.floor(value);
            case TOWARD_ZERO -> value < 0 ? Math.ceil(value) : Math.floor(value);
            case NEAREST_TIES_AWAY -> roundTiesAway(value);
        };
    }

    /// "Mais próximo, empate afasta de zero" (`FPRoundInt` com `RMode`=`FPRounding_TIEAWAY`) —
    /// `Math.round` NÃO serve (arredonda meio PARA CIMA sempre, não "para longe de zero": `Math
    /// .round(-2.5)` devolve `-2`, não `-3`). Preserva o sinal de `-0.0` (só entra no ramo de
    /// empate quando a fração é exatamente `0.5`; um valor já integral tem fração `0.0` e cai no
    /// ramo `< 0.5`, devolvendo `floor` — que é o próprio valor).
    private static double roundTiesAway(double value) {
        double floor = Math.floor(value);
        double fraction = value - floor;
        if (fraction > 0.5) {
            return floor + 1.0;
        }
        if (fraction < 0.5) {
            return floor;
        }
        return value >= 0 ? floor + 1.0 : floor;
    }

    /// `SCVTF`/`UCVTF`/`FCVTxS`/`FCVTxU` (forma registrador-geral, B8.5) — nos dois sentidos.
    static boolean executeFpIntegerConvert(Aarch64Core core, Ir64Op.Fp64IntegerConvert op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.toFloat()) {
            long raw = core.xForWidth(op.gpReg(), op.wide());
            double intAsDouble;
            if (!op.wide() && op.signed()) {
                intAsDouble = (int) raw; // sinal-estende os 32 bits baixos (xForWidth zero-estende).
            } else if (op.wide() && !op.signed()) {
                intAsDouble = unsignedLongToDouble(raw);
            } else {
                intAsDouble = (double) raw; // signed64 (já correto) ou unsigned32 (já não-negativo).
            }
            double scaled = op.fixedPointFractionBits() == 0
                    ? intAsDouble : Math.scalb(intAsDouble, -op.fixedPointFractionBits());
            if (op.doublePrecision()) {
                fp.setDDouble(op.fpReg(), scaled);
            } else {
                fp.setSFloat(op.fpReg(), (float) scaled);
            }
        } else {
            double value = op.doublePrecision() ? fp.dDouble(op.fpReg()) : fp.sFloat(op.fpReg());
            double scaled = op.fixedPointFractionBits() == 0
                    ? value : Math.scalb(value, op.fixedPointFractionBits());
            double rounded = roundToIntegralForConversion(scaled, op.rounding());
            long result = saturateToInteger(rounded, op.signed(), op.wide());
            core.setXForWidth(op.gpReg(), result, op.wide());
        }
        return false;
    }

    /// Mesma direção de {@link #roundToIntegral}, mas SEM o curto-circuito NaN/infinito — quem
    /// chama ({@link #saturateToInteger}) precisa do `NaN`/infinito intactos para saturar
    /// corretamente (`FPToFixed`: `NaN`→`0`, infinito→limite da largura).
    private static double roundToIntegralForConversion(double value, Ir64Op.Fp64RoundingDirection direction) {
        if (Double.isNaN(value)) {
            return value;
        }
        if (Double.isInfinite(value)) {
            return value;
        }
        return roundToIntegral(value, direction);
    }

    /// Converte um `long` de 64 bits SEM SINAL (bit mais alto pode estar setado) para o `double`
    /// mais próximo — truque padrão (deslocar 1 bit sem sinal, escalar de volta, somar o bit
    /// perdido) já que `(double) long` do Java sempre assume sinal.
    private static double unsignedLongToDouble(long value) {
        if (value >= 0) {
            return (double) value;
        }
        return ((double) (value >>> 1)) * 2.0 + (value & 1L);
    }

    /// `FPToFixed`: arredonda+satura `rounded` (já na direção certa) para a largura/sinal pedida.
    /// `NaN`→`0`; fora da faixa→o limite mais próximo (mesma convenção de
    /// {@code IrVfpExecutor#doubleToFixed}, o precedente VFP32 desta mesma técnica).
    private static long saturateToInteger(double rounded, boolean signed, boolean wide) {
        if (Double.isNaN(rounded)) {
            return 0L;
        }
        int bits = wide ? 64 : 32;
        double minValue = signed ? -Math.scalb(1.0, bits - 1) : 0.0;
        double maxValue = signed ? Math.scalb(1.0, bits - 1) - 1.0 : Math.scalb(1.0, bits) - 1.0;
        double clamped = Math.max(minValue, Math.min(maxValue, rounded));
        if (wide && !signed) {
            return doubleToUnsignedLongBits(clamped);
        }
        // signed64: o cast (double->long) do Java já satura em Long.MIN/MAX_VALUE por JLS 5.1.3 —
        // signed32/unsigned32 cabem folgados na faixa de `long`, {@link
        // Aarch64Core#setXForWidth} zera/mascara os 32 bits altos ao escrever.
        return (long) clamped;
    }

    /// `clamped` já está em `[0, 2^64-1]` — para a metade superior (`>= 2^63`), o cast direto
    /// `(long)` do Java satura em `Long.MAX_VALUE` (não produz o padrão de bits sem sinal
    /// correto); desloca para baixo de `2^63` primeiro, converte, e soma de volta em aritmética de
    /// complemento de dois (que "dá a volta" corretamente para o padrão de bits desejado).
    private static long doubleToUnsignedLongBits(double clamped) {
        double twoToThe63 = Math.scalb(1.0, 63);
        if (clamped < twoToThe63) {
            return (long) clamped;
        }
        return Long.MIN_VALUE + (long) (clamped - twoToThe63);
    }

    /// `FMOV` registrador-geral↔FP escalar (B8.5) — cópia CRUA de bits, sem conversão de valor.
    static boolean executeFpGeneralRegisterMove(Aarch64Core core, Ir64Op.Fp64GeneralRegisterMove op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.toFloat()) {
            long raw = core.xForWidth(op.gpReg(), op.wide());
            if (op.wide()) {
                fp.setD(op.fpReg(), raw);
            } else {
                fp.setS(op.fpReg(), (int) raw);
            }
        } else {
            long raw = op.wide() ? fp.d(op.fpReg()) : (fp.s(op.fpReg()) & 0xFFFF_FFFFL);
            core.setXForWidth(op.gpReg(), raw, op.wide());
        }
        return false;
    }
}
