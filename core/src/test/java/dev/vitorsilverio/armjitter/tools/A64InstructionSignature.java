package dev.vitorsilverio.armjitter.tools;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Set;

/// **E17** — a identidade de uma instrução A64 decodificada, para conferir se uma palavra saiu como a
/// instrução da linha do inventário e não como outra: nome do record (com o tipo que o aninha, ex.
/// `AdvSimdIntegerOp64.ArithmeticThreeSameByElement`) + `/` + o valor de cada componente `enum` de
/// **operação** (ex. `/MUL`).
///
/// Enums de **operando** ficam de fora ({@link #OPERAND_ENUMS}): tamanho de acesso, tipo de shift/extend,
/// condição e registrador de sistema variam com os campos que a estratégia de preenchimento escolhe
/// dentro da MESMA linha do `.decode` (medido na E17: 67 linhas com "várias assinaturas" só por isso).
/// O embrulho `StreamingRestricted` (presets com `FEAT_SME`) é desfeito — ele não muda a instrução.
final class A64InstructionSignature {

    /// Enums que descrevem operando, não a operação.
    static final Set<Class<?>> OPERAND_ENUMS = Set.of(
            Ir64MemSize.class, Ir64FpMemSize.class, Ir64ShiftType.class, Ir64LogicalShiftType.class,
            Ir64ExtendType.class, Ir64AluExtendType.class, Ir64Condition.class,
            Ir64CompareBranchCondition.class, Aarch64SystemRegisterId.class);

    private A64InstructionSignature() {
    }

    /// A assinatura de `op`. Records sem enum de operação ficam só com o nome.
    static String of(Ir64Op op) {
        Ir64Op unwrapped = op;
        while (unwrapped instanceof Ir64Op.StreamingRestricted restricted) {
            unwrapped = restricted.inner();
        }
        Class<?> type = unwrapped.getClass();
        StringBuilder signature = new StringBuilder(nestedName(type));
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getType().isEnum() && !OPERAND_ENUMS.contains(component.getType())) {
                signature.append('/').append(read(component, unwrapped));
            }
        }
        return signature.toString();
    }

    private static String nestedName(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("componente ilegível: " + component, e);
        }
    }
}
