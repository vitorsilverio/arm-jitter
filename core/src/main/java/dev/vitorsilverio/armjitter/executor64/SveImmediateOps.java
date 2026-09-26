package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica das operações SVE com imediato (B17.8): bitmask (`AND`/`ORR`/`EOR`/`DUPM`), cópia predicada
/// (`CPY`/`FCPY`), broadcast (`DUP`/`FDUP`), aritmética com imediato de 8 bits (`ADD`/`SUB`/`SUBR`/`SQADD`/
/// `UQADD`/`SQSUB`/`UQSUB`), `SMAX`/`UMAX`/`SMIN`/`UMIN` e `MUL`.
///
/// O imediato já chega **expandido** do decoder (bitmask de 64 bits, `sh8` aplicado, `VFPExpandImm` feito). Aqui
/// ele só é truncado ao tamanho do elemento — e, nas saturantes, tratado como o **inteiro sem sinal** que a
/// arquitetura define (`SQADD Zdn.B, Zdn.B, #255` satura em 127 para elemento não negativo; o valor NÃO vira `-1`).
/// As formas `_m` (`CPY_m_i`/`FCPY`) preservam o elemento inativo; `CPY_z_i` o zera. Um elemento está ativo quando
/// o bit do byte MAIS BAIXO dele em `P[pg]` está ligado. Os auxiliares de elemento vêm de {@link SveIntegerOps}.
final class SveImmediateOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int ESZ_DOUBLEWORD = 3;

    private SveImmediateOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveImmediate op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        long imm = op.imm();
        boolean predicated = op.op() == Ir64Op.SveImmediate.Op.CPY_MERGING
                || op.op() == Ir64Op.SveImmediate.Op.CPY_ZEROING || op.op() == Ir64Op.SveImmediate.Op.FCPY;
        for (int e = 0; e < elements; e++) {
            if (predicated && !active(regs, op.pg(), e, esz)) {
                if (op.op() == Ir64Op.SveImmediate.Op.CPY_ZEROING) {
                    SveIntegerOps.set(regs, op.rd(), e, esz, 0L);
                }
                continue;
            }
            long n = SveIntegerOps.get(regs, op.rd(), e, esz);
            SveIntegerOps.set(regs, op.rd(), e, esz, result(op.op(), n, imm, esz));
        }
        return false;
    }

    private static boolean active(Aarch64ScalableRegisters regs, int pg, int element, int esz) {
        int bit = element << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    private static long result(Ir64Op.SveImmediate.Op kind, long n, long imm, int esz) {
        long sn = SveIntegerOps.signExtend(n, esz);
        long mask = SveIntegerOps.elementMask(esz);
        long unsignedImm = imm & mask;
        return switch (kind) {
            case AND -> n & imm;
            case ORR -> n | imm;
            case EOR -> n ^ imm;
            case DUPM, CPY_MERGING, CPY_ZEROING, FCPY, DUP, FDUP -> imm;
            case ADD -> n + imm;
            case SUB -> n - imm;
            case SUBR -> imm - n;
            case SQADD -> saturateSigned(sn, imm, esz, false);
            case SQSUB -> saturateSigned(sn, imm, esz, true);
            case UQADD -> saturateUnsigned(n, unsignedImm, esz, false);
            case UQSUB -> saturateUnsigned(n, unsignedImm, esz, true);
            case SMAX -> Math.max(sn, imm);
            case SMIN -> Math.min(sn, imm);
            case UMAX -> Long.compareUnsigned(n, unsignedImm) >= 0 ? n : unsignedImm;
            case UMIN -> Long.compareUnsigned(n, unsignedImm) >= 0 ? unsignedImm : n;
            default -> n * imm; // MUL
        };
    }

    /// `imm` é o inteiro sem sinal do encoding (`0..0xFF00`); somado/subtraído do elemento COM sinal e saturado
    /// ao intervalo do tamanho. Em 64 bits a soma pode estourar `long`, então testa-se o estouro explicitamente.
    private static long saturateSigned(long signedElement, long imm, int esz, boolean subtract) {
        if (esz == ESZ_DOUBLEWORD) {
            long result = subtract ? signedElement - imm : signedElement + imm;
            boolean overflow = subtract ? result > signedElement : result < signedElement;
            return overflow ? (subtract ? Long.MIN_VALUE : Long.MAX_VALUE) : result;
        }
        long max = (1L << (SveIntegerOps.elementBits(esz) - 1)) - 1L;
        long result = subtract ? signedElement - imm : signedElement + imm;
        return Math.max(-max - 1L, Math.min(max, result));
    }

    private static long saturateUnsigned(long element, long imm, int esz, boolean subtract) {
        if (esz == ESZ_DOUBLEWORD) {
            if (subtract) {
                return Long.compareUnsigned(element, imm) < 0 ? 0L : element - imm;
            }
            long sum = element + imm;
            return Long.compareUnsigned(sum, element) < 0 ? -1L : sum;
        }
        long result = subtract ? element - imm : element + imm;
        return Math.max(0L, Math.min(SveIntegerOps.elementMask(esz), result));
    }
}
