package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Task C12.2, Armadilha 3: o registro é `(op, executor)` PAREADO, nunca um executor estático
/// global — espelho do achado real do precedente 32-bit (`IrOpInterop`, ver seu Javadoc), que
/// causava corrupção não-determinística quando o ÚLTIMO emissor construído vencia. `Ir64BlockExecutor`
/// não gateia `executeOp` por arquitetura hoje (ver seu Javadoc — "ainda sem efeito observável"),
/// então este teste prova o que É observável agora: cada `(op, executor)` registrado por uma
/// chamada de {@link Ir64OpInterop#register} mantém sua PRÓPRIA entrada — ids sequenciais, sem
/// colisão/sobrescrita — mesmo quando registros de dois executores DIFERENTES se intercalam,
/// exatamente o padrão de dois `Asm64CodeEmitter`/`Ir64BlockCompiler` compilando em paralelo.
class Ir64OpInteropTest {
    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x100)));
    }

    @Test
    void interleavedRegistrationsFromDifferentExecutorsKeepDistinctIdsAndDispatchTheRegisteredOp() {
        Ir64BlockExecutor executorA = new Ir64BlockExecutor();
        Ir64BlockExecutor executorB = new Ir64BlockExecutor();

        Ir64Op opA = new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 0x10, true, false, false, false);
        Ir64Op opB = new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 0x20, true, false, false, false);

        int idA = Ir64OpInterop.register(opA, executorA);
        int idB = Ir64OpInterop.register(opB, executorB);
        assertNotEquals(idA, idB, "cada registro tem que ganhar seu PRÓPRIO id, mesmo op-classe igual");

        Aarch64Core coreForA = newCore();
        coreForA.setX(1, 5L);
        Ir64OpInterop.executeInterpreted(coreForA, idA);
        assertEquals(0x15L, coreForA.x(0), "idA tem que executar opA (immediate 0x10), não opB");

        Aarch64Core coreForB = newCore();
        coreForB.setX(1, 5L);
        Ir64OpInterop.executeInterpreted(coreForB, idB);
        assertEquals(0x25L, coreForB.x(0), "idB tem que executar opB (immediate 0x20), não opA");
    }

    @Test
    void registerIsAppendOnlyAndSequential() {
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op op = new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 1, true, false, false, false);
        int first = Ir64OpInterop.register(op, executor);
        int second = Ir64OpInterop.register(op, executor);
        assertTrue(second > first, "ids crescem monotonicamente (lista append-only)");
    }
}
