package dev.vitorsilverio.armjitter.decoder64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Property test de {@link Aarch64LogicalImmediate#decodeBitMasks} contra uma SEGUNDA
/// implementação independente ({@link #referenceDecodeBitMasks}) — não a mesma fórmula de
/// bit-trick reescrita, uma expansão literal por array de bits (monta o elemento, rotaciona por
/// indexação módulo, replica por um laço bit a bit). "`DecodeBitMasks` é o encoding mais
/// traiçoeiro da ISA A64" (aviso do épico B6) — a task B6.3.1 exige explicitamente não confiar na
/// transcrição do algoritmo do QEMU sozinha.
///
/// Cobertura: EXAUSTIVA sobre as `2 × 64 × 64 = 8192` combinações de `(N, imms, immr)` — inclui,
/// portanto, os casos de canto pedidos pela task (elemento de 64 bits com `N=1`, elemento de 2
/// bits, rotações não triviais) sem precisar escolhê-los manualmente.
class Aarch64LogicalImmediateTest {
    private static final int FIELD_WIDTH_BITS = 6;
    private static final int FIELD_SIZE = 1 << FIELD_WIDTH_BITS; // 64
    private static final int REGISTER_WIDTH_BITS = 64;

    @Test
    void exhaustiveAgreementWithIndependentReferenceImplementation() {
        int agreementCount = 0;
        int reservedCount = 0;
        for (int n = 0; n <= 1; n++) {
            for (int imms = 0; imms < FIELD_SIZE; imms++) {
                for (int immr = 0; immr < FIELD_SIZE; immr++) {
                    Long expected = tryReference(n, imms, immr);
                    Long actual = tryMain(n, imms, immr);
                    if (expected == null || actual == null) {
                        assertEquals(expected == null, actual == null,
                                "discordância sobre reservado em N=" + n + " imms=" + imms + " immr=" + immr);
                        reservedCount++;
                        continue;
                    }
                    assertEquals(expected, actual,
                            "N=" + n + " imms=" + imms + " immr=" + immr
                                    + ": esperado 0x" + Long.toHexString(expected)
                                    + " obtido 0x" + Long.toHexString(actual));
                    agreementCount++;
                }
            }
        }
        // Sanidade: o property test realmente exercitou casos válidos E reservados (não é um
        // teste vazio que só confirma "sempre reservado" ou "nunca reservado").
        assertEquals(8192, agreementCount + reservedCount);
        org.junit.jupiter.api.Assertions.assertTrue(agreementCount > 0, "nenhuma combinação válida encontrada");
        org.junit.jupiter.api.Assertions.assertTrue(reservedCount > 0, "nenhuma combinação reservada encontrada");
    }

    @Test
    void elementSize64WithSingleBitMatchesReference() {
        // N=1, imms=0 (s=0, corrida de 1 bit), immr=0 (sem rotação) -> só o bit 0 setado.
        assertEquals(0x1L, Aarch64LogicalImmediate.decodeBitMasks(1, 0, 0));
        assertEquals(referenceDecodeBitMasks(1, 0, 0), Aarch64LogicalImmediate.decodeBitMasks(1, 0, 0));
    }

    @Test
    void elementSize2ReplicatedMatchesReference() {
        // N=0, imms=0b111100 (~imms&0x3f=0b000011, highest bit em 1 -> e=2; s=imms&1=0),
        // immr=0: elemento "01" replicado (0x5555555555555555).
        int imms = 0b111100;
        long main = Aarch64LogicalImmediate.decodeBitMasks(0, imms, 0);
        assertEquals(0x5555_5555_5555_5555L, main);
        assertEquals(referenceDecodeBitMasks(0, imms, 0), main);
    }

    @Test
    void nonTrivialRotationMatchesReference() {
        // N=1, imms=62 (s=62, corrida de 63 uns), immr=1 (rotação não trivial): 63 uns a partir
        // do bit0, rotacionados 1 posição à direita dentro de 64 bits -> 0xbfffffffffffffff.
        long main = Aarch64LogicalImmediate.decodeBitMasks(1, 62, 1);
        assertEquals(0xbfff_ffff_ffff_ffffL, main);
        assertEquals(referenceDecodeBitMasks(1, 62, 1), main);
    }

    @Test
    void allOnesConcatenationIsReserved() {
        // N=0, imms=0b111111: concatenação N:~imms é toda zero -> reservado.
        assertThrows(UnsupportedOperationException.class,
                () -> Aarch64LogicalImmediate.decodeBitMasks(0, 0b111111, 0));
    }

    @Test
    void runOccupyingWholeElementIsReservedForMultipleElementSizes() {
        // s == levels (corrida ocupa o elemento inteiro) para 2 tamanhos de elemento diferentes.
        // Elemento de 8 bits (len=3, e=8, levels=7): imms com 3 bits baixos = 111 (s=7=levels).
        int imms8 = 0b110_111; // concat=(0<<6)|~imms8 tem highest bit em posição 3 -> e=8; s=7
        assertThrows(UnsupportedOperationException.class,
                () -> Aarch64LogicalImmediate.decodeBitMasks(0, imms8, 0));
        // Elemento de 64 bits (N=1, levels=63): imms=63 (s=levels).
        assertThrows(UnsupportedOperationException.class,
                () -> Aarch64LogicalImmediate.decodeBitMasks(1, 63, 0));
    }

    private static Long tryMain(int n, int imms, int immr) {
        try {
            return Aarch64LogicalImmediate.decodeBitMasks(n, imms, immr);
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    private static Long tryReference(int n, int imms, int immr) {
        try {
            return referenceDecodeBitMasks(n, imms, immr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /// Segunda implementação, DERIVADA de forma diferente da principal: em vez da fórmula de
    /// deslocar-e-OR (`bitfield_replicate` do QEMU), monta o padrão do elemento como um array de
    /// booleanos, rotaciona por indexação módulo (`(i + r) % e`, não shift+OR), e replica por um
    /// laço bit a bit explícito sobre os 64 bits de saída (não dobra o tamanho a cada passo).
    private static long referenceDecodeBitMasks(int n, int imms, int immr) {
        if (n < 0 || n > 1 || imms < 0 || imms >= FIELD_SIZE || immr < 0 || immr >= FIELD_SIZE) {
            throw new IllegalArgumentException("fora do domínio de 6 bits");
        }
        int highestSetBit = -1;
        int concat = (n << FIELD_WIDTH_BITS) | ((~imms) & (FIELD_SIZE - 1));
        for (int bit = FIELD_WIDTH_BITS; bit >= 0; bit--) {
            if (((concat >>> bit) & 1) != 0) {
                highestSetBit = bit;
                break;
            }
        }
        if (highestSetBit < 1) {
            throw new IllegalArgumentException("reservado: concatenação N:~imms é toda zero");
        }
        int elementSizeBits = 1 << highestSetBit;
        int levels = elementSizeBits - 1;
        int runLengthMinusOne = imms & levels;
        int rotation = immr & levels;
        if (runLengthMinusOne == levels) {
            throw new IllegalArgumentException("reservado: corrida ocupa o elemento inteiro");
        }

        boolean[] element = new boolean[elementSizeBits];
        for (int i = 0; i <= runLengthMinusOne; i++) {
            element[i] = true;
        }
        boolean[] rotated = new boolean[elementSizeBits];
        for (int i = 0; i < elementSizeBits; i++) {
            rotated[i] = element[(i + rotation) % elementSizeBits];
        }
        long result = 0;
        for (int bit = 0; bit < REGISTER_WIDTH_BITS; bit++) {
            if (rotated[bit % elementSizeBits]) {
                result |= (1L << bit);
            }
        }
        return result;
    }
}
