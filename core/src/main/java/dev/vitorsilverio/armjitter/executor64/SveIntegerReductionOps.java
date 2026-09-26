package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.Arrays;

/// Semântica das reduções inteiras SVE (B17.7): as 9 escalares (`ORV`/`EORV`/`ANDV`/`SADDV`/`UADDV`/
/// `SMAXV`/`UMAXV`/`SMINV`/`UMINV`) e as 8 por segmento de 128 bits (`*QV`, `FEAT_SVE2p1`).
///
/// - A redução escalar acumula NO TAMANHO do elemento e devolve `V<d>` de `esz` (o resto do registrador é
///   zerado) — só `SADDV`/`UADDV` acumulam e devolvem 64 bits (`saddv d0, …` / `uaddv d0, …`).
/// - Elemento inativo não entra na conta; sem nenhum elemento ativo o resultado é o valor neutro da
///   operação NO TAMANHO do elemento (`ANDV`/`UMINV` = todos-1 de `esz` bits, `SMAXV` = mínimo com sinal de
///   `esz`, `SMINV` = máximo com sinal…), medido em `DO_VPZ` do `sve_helper.c` do QEMU.
/// - A redução por segmento NÃO produz um escalar por 128 bits: ela devolve UM `V<d>` de 128 bits em que a
///   posição `l` do segmento é a redução da posição `l` de TODOS os segmentos (`DO_VPQ`). Com `VL = 128`
///   coincide com a escalar em vetor; com `VL >= 256` só o teste distingue.
///
/// O predicado é lido no bit do byte mais baixo de cada elemento (`P` guarda um bit por byte).
final class SveIntegerReductionOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int SEGMENT_BYTES = 16;

    private SveIntegerReductionOps() {
    }

    /// Executa uma redução. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveIntegerReduction op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        if (isSegment(op.op())) {
            segment(core, op);
        } else {
            scalar(core, op);
        }
        return false;
    }

    private static boolean isSegment(Ir64Op.SveIntegerReduction.Op kind) {
        return switch (kind) {
            case ORQV, EORQV, ANDQV, ADDQV, SMAXQV, UMAXQV, SMINQV, UMINQV -> true;
            default -> false;
        };
    }

    private static boolean active(Aarch64ScalableRegisters regs, int pg, int element, int esz) {
        int bit = element << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    private static void scalar(Aarch64Core core, Ir64Op.SveIntegerReduction op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        boolean sum64 = op.op() == Ir64Op.SveIntegerReduction.Op.SADDV || op.op() == Ir64Op.SveIntegerReduction.Op.UADDV;
        long accumulator = neutral(op.op(), esz);
        for (int e = 0; e < elements; e++) {
            if (active(regs, op.pg(), e, esz)) {
                long element = SveIntegerOps.get(regs, op.rn(), e, esz);
                accumulator = switch (op.op()) {
                    case SADDV -> accumulator + SveIntegerOps.signExtend(element, esz);
                    case UADDV -> accumulator + element;
                    default -> combine(op.op(), accumulator, element, esz);
                };
            }
        }
        core.fp().setScalar(op.rd(), sum64 ? ESZ_DOUBLEWORD : esz, accumulator);
    }

    private static void segment(Aarch64Core core, Ir64Op.SveIntegerReduction op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int perSegment = SEGMENT_BYTES >> esz;
        int segments = core.vectorLengthBytes() / SEGMENT_BYTES;
        int bits = SveIntegerOps.elementBits(esz);
        long[] accumulator = new long[perSegment];
        Arrays.fill(accumulator, neutral(op.op(), esz));
        for (int s = 0; s < segments; s++) {
            for (int lane = 0; lane < perSegment; lane++) {
                int element = s * perSegment + lane;
                if (active(regs, op.pg(), element, esz)) {
                    accumulator[lane] = combine(op.op(), accumulator[lane],
                            SveIntegerOps.get(regs, op.rn(), element, esz), esz);
                }
            }
        }
        long[] words = new long[SEGMENT_BYTES / Long.BYTES];
        for (int lane = 0; lane < perSegment; lane++) {
            int bitOffset = lane * bits;
            words[bitOffset / Long.SIZE] |= accumulator[lane] << (bitOffset % Long.SIZE);
        }
        core.fp().setQ(op.rd(), words[0], words[1]);
    }

    /// Valor neutro (predicado vazio) no tamanho do elemento, já mascarado.
    private static long neutral(Ir64Op.SveIntegerReduction.Op kind, int esz) {
        long mask = SveIntegerOps.elementMask(esz);
        long signBit = 1L << (SveIntegerOps.elementBits(esz) - 1);
        return switch (kind) {
            case ANDV, ANDQV, UMINV, UMINQV -> mask;
            case SMAXV, SMAXQV -> signBit;
            case SMINV, SMINQV -> (signBit - 1L) & mask;
            default -> 0L; // ORV/EORV/ADDV/UMAXV e os `*QV` equivalentes
        };
    }

    /// Um passo da redução no tamanho do elemento (`accumulator` e `element` já mascarados).
    private static long combine(Ir64Op.SveIntegerReduction.Op kind, long accumulator, long element, int esz) {
        long mask = SveIntegerOps.elementMask(esz);
        long signedAccumulator = SveIntegerOps.signExtend(accumulator, esz);
        long signedElement = SveIntegerOps.signExtend(element, esz);
        return switch (kind) {
            case ORV, ORQV -> accumulator | element;
            case EORV, EORQV -> accumulator ^ element;
            case ANDV, ANDQV -> accumulator & element;
            case ADDQV -> (accumulator + element) & mask;
            case SMAXV, SMAXQV -> signedAccumulator >= signedElement ? accumulator : element;
            case SMINV, SMINQV -> signedAccumulator <= signedElement ? accumulator : element;
            case UMAXV, UMAXQV -> Long.compareUnsigned(accumulator, element) >= 0 ? accumulator : element;
            default -> Long.compareUnsigned(accumulator, element) <= 0 ? accumulator : element; // UMINV/UMINQV
        };
    }
}
