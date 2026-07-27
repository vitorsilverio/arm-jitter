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
            case NEG -> fp.d(op.vm()) ^ Long.MIN_VALUE;
            case ABS -> fp.d(op.vm()) & Long.MAX_VALUE;
            case MOV -> fp.d(op.vm());
        };
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
