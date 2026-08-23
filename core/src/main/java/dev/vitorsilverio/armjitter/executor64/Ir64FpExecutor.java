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
}
