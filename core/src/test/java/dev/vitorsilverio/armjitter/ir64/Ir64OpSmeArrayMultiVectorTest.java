package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeArrayMultiVector;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeArrayMultiVector.Op;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Predicados do record `SmeArrayMultiVector` (B18.9-B18.12) que os testes de decode/execução não alcançam
/// diretamente: `indexed()` nas duas formas e `Op#bfloat16()`.
class Ir64OpSmeArrayMultiVectorTest {
    @Test
    void indexedDistinguishesTheIndexedFormFromTheOthers() {
        SmeArrayMultiVector plain = new SmeArrayMultiVector(Op.ADD_S, 2, 8, 0, 4, 5, 0x40);
        SmeArrayMultiVector indexed = new SmeArrayMultiVector(Op.FMLA_S, 2, 8, 0, 4, 5, 0x40, false, 3);
        assertFalse(plain.indexed());
        assertEquals(SmeArrayMultiVector.NOT_INDEXED, plain.index());
        assertTrue(indexed.indexed());
        assertEquals(3, indexed.index());
    }

    @Test
    void onlyBFMlaAndBFMlsAreBFloat16ArrayVectors() {
        assertTrue(Op.BFMLA.bfloat16());
        assertTrue(Op.BFMLS.bfloat16());
        assertFalse(Op.FMLA_H.bfloat16());
        assertFalse(Op.BFADD.bfloat16());
    }
}
