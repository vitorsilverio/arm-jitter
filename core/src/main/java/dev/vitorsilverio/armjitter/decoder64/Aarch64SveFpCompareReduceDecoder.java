package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE da comparação de ponto flutuante e das reduções de ponto flutuante da B17.15: no prefixo `0x65`, as sete
/// `FCM*`/`FAC*` vetor×vetor, as seis `FCM*` com zero, as cinco reduções rápidas (`FADDV`/`FMAXNMV`/`FMINNMV`/`FMAXV`/
/// `FMINV`) e `FADDA`; no prefixo `0x64`, as cinco reduções por segmento de 128 bits (`FADDQV`/…/`FMINQV`,
/// `FEAT_SVE2p1`). Cada padrão é o `decodetree` do `sve.decode` do QEMU transcrito em `máscara`/`valor`, conferido contra
/// `aarch64-none-elf-as` (`-march=armv9.4-a+sve2+sve2p1`).
///
/// Em FP o campo `esz` é `1` = meia, `2` = simples, `3` = dupla: **`esz = 0` não é alocado em nenhuma das 24** (o QEMU
/// tem `NULL` na posição `0` de toda tabela) e devolve `null` (G8). Não existe `FCMLT`/`FCMLE` vetor×vetor (são alias
/// de operandos trocados), e `FCMNE` com zero só tem uma linha (`bit 4 = 0`); o resto do espaço fica recusado.
final class Aarch64SveFpCompareReduceDecoder {
    private static final int REGISTER_MASK = 0b11111;
    private static final int PREDICATE_DESTINATION_MASK = 0b1111;
    private static final int PREDICATE_MASK = 0b111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int OPCODE_SHIFT = 13;
    private static final int OPCODE_MASK = 0b111;
    private static final int REDUCTION_OPCODE_SHIFT = 16;
    private static final int ZERO_SELECT_SHIFT = 16;
    private static final int ZERO_SELECT_MASK = 0b11;
    private static final int LOW_BIT = 4;
    private static final int ESZ_RESERVED = 0;

    private static final int COMPARE_MASK = 0xFF200000;
    private static final int COMPARE_VALUE = 0x65000000;
    private static final int COMPARE_ZERO_MASK = 0xFF3CE000;
    private static final int COMPARE_ZERO_VALUE = 0x65102000;
    private static final int FAST_REDUCTION_MASK = 0xFF38E000;
    private static final int FAST_REDUCTION_VALUE = 0x65002000;
    private static final int SERIAL_REDUCTION_MASK = 0xFF3FE000;
    private static final int SERIAL_REDUCTION_VALUE = 0x65182000;
    private static final int QUADWORD_REDUCTION_MASK = 0xFF38E000;
    private static final int QUADWORD_REDUCTION_VALUE = 0x6410A000;

    // Bits 15:13 da comparação vetor×vetor (o bit 4 escolhe dentro do par).
    private static final int COMPARE_GE_GT = 0b010;
    private static final int COMPARE_EQ_NE = 0b011;
    private static final int COMPARE_UO_ACGE = 0b110;

    // Bits 17:16 da comparação com zero.
    private static final int ZERO_GE_GT = 0b00;
    private static final int ZERO_LT_LE = 0b01;
    private static final int ZERO_EQ = 0b10;
    private static final int ZERO_NE = 0b11;

    // Bits 18:16 das reduções.
    private static final int REDUCTION_ADD = 0b000;
    private static final int REDUCTION_MAXNM = 0b100;
    private static final int REDUCTION_MINNM = 0b101;
    private static final int REDUCTION_MAX = 0b110;
    private static final int REDUCTION_MIN = 0b111;

    private final Aarch64Architecture architecture;

    Aarch64SveFpCompareReduceDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Prefixo `0x65`. `null` = não é deste grupo (ou é um encoding não alocado, recusado).
    Ir64Op decodePrefix65(int word, long address) {
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if (esz == ESZ_RESERVED) {
            return null;
        }
        if ((word & COMPARE_ZERO_MASK) == COMPARE_ZERO_VALUE) {
            return decodeCompareZero(word, esz, address);
        }
        if ((word & FAST_REDUCTION_MASK) == FAST_REDUCTION_VALUE) {
            Ir64Op.SveFpCompareReduce.Op op = switch ((word >>> REDUCTION_OPCODE_SHIFT) & OPCODE_MASK) {
                case REDUCTION_ADD -> Ir64Op.SveFpCompareReduce.Op.FADDV;
                case REDUCTION_MAXNM -> Ir64Op.SveFpCompareReduce.Op.FMAXNMV;
                case REDUCTION_MINNM -> Ir64Op.SveFpCompareReduce.Op.FMINNMV;
                case REDUCTION_MAX -> Ir64Op.SveFpCompareReduce.Op.FMAXV;
                case REDUCTION_MIN -> Ir64Op.SveFpCompareReduce.Op.FMINV;
                default -> null;
            };
            return op == null ? null : reduction(op, word, esz, address);
        }
        if ((word & SERIAL_REDUCTION_MASK) == SERIAL_REDUCTION_VALUE) {
            // `@rdn_pg_rm`: `Vdn` (bits 4:0) é entrada e saída; `Zm` fica nos bits 9:5.
            int rd = word & REGISTER_MASK;
            return new Ir64Op.SveFpCompareReduce(Ir64Op.SveFpCompareReduce.Op.FADDA, esz, rd, rd,
                    (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> PG_SHIFT) & PREDICATE_MASK, false, address);
        }
        // Por último: os opcodes `000`/`001`/`100`/`101` do mesmo prefixo pertencem às reduções ou não são alocados.
        return (word & COMPARE_MASK) == COMPARE_VALUE ? decodeCompare(word, esz, address) : null;
    }

    /// Prefixo `0x64`: as cinco reduções por segmento (`FEAT_SVE2p1`).
    Ir64Op decodePrefix64(int word, long address) {
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if (esz == ESZ_RESERVED || (word & QUADWORD_REDUCTION_MASK) != QUADWORD_REDUCTION_VALUE
                || !architecture.has(Aarch64Feature.SVE2_1) && !architecture.has(Aarch64Feature.SVE2_2)) {
            return null; // `aa64_sme2p1_or_sve2p1`; SVE2p2 implica SVE2p1
        }
        Ir64Op.SveFpCompareReduce.Op op = switch ((word >>> REDUCTION_OPCODE_SHIFT) & OPCODE_MASK) {
            case REDUCTION_ADD -> Ir64Op.SveFpCompareReduce.Op.FADDQV;
            case REDUCTION_MAXNM -> Ir64Op.SveFpCompareReduce.Op.FMAXNMQV;
            case REDUCTION_MINNM -> Ir64Op.SveFpCompareReduce.Op.FMINNMQV;
            case REDUCTION_MAX -> Ir64Op.SveFpCompareReduce.Op.FMAXQV;
            case REDUCTION_MIN -> Ir64Op.SveFpCompareReduce.Op.FMINQV;
            default -> null;
        };
        return op == null ? null : reduction(op, word, esz, address);
    }

    private static Ir64Op reduction(Ir64Op.SveFpCompareReduce.Op op, int word, int esz, long address) {
        return new Ir64Op.SveFpCompareReduce(op, esz, word & REGISTER_MASK, (word >>> RN_SHIFT) & REGISTER_MASK, 0,
                (word >>> PG_SHIFT) & PREDICATE_MASK, false, address);
    }

    private static Ir64Op decodeCompare(int word, int esz, long address) {
        boolean bit4 = ((word >>> LOW_BIT) & 1) != 0;
        Ir64Op.SveFpCompareReduce.Op op = switch ((word >>> OPCODE_SHIFT) & OPCODE_MASK) {
            case COMPARE_GE_GT -> bit4 ? Ir64Op.SveFpCompareReduce.Op.FCMGT : Ir64Op.SveFpCompareReduce.Op.FCMGE;
            case COMPARE_EQ_NE -> bit4 ? Ir64Op.SveFpCompareReduce.Op.FCMNE : Ir64Op.SveFpCompareReduce.Op.FCMEQ;
            case COMPARE_UO_ACGE -> bit4 ? Ir64Op.SveFpCompareReduce.Op.FACGE : Ir64Op.SveFpCompareReduce.Op.FCMUO;
            default -> bit4 ? Ir64Op.SveFpCompareReduce.Op.FACGT : null; // `111`; com bit 4 = 0: não alocado
            case 0b000, 0b001, 0b100, 0b101 -> null;
        };
        if (op == null) {
            return null;
        }
        return new Ir64Op.SveFpCompareReduce(op, esz, word & PREDICATE_DESTINATION_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> RM_SHIFT) & REGISTER_MASK,
                (word >>> PG_SHIFT) & PREDICATE_MASK, false, address);
    }

    private static Ir64Op decodeCompareZero(int word, int esz, long address) {
        boolean bit4 = ((word >>> LOW_BIT) & 1) != 0;
        Ir64Op.SveFpCompareReduce.Op op = switch ((word >>> ZERO_SELECT_SHIFT) & ZERO_SELECT_MASK) {
            case ZERO_GE_GT -> bit4 ? Ir64Op.SveFpCompareReduce.Op.FCMGT : Ir64Op.SveFpCompareReduce.Op.FCMGE;
            case ZERO_LT_LE -> bit4 ? Ir64Op.SveFpCompareReduce.Op.FCMLE : Ir64Op.SveFpCompareReduce.Op.FCMLT;
            case ZERO_EQ -> bit4 ? null : Ir64Op.SveFpCompareReduce.Op.FCMEQ; // `EQ` só tem `bit 4 = 0`
            default -> bit4 ? null : Ir64Op.SveFpCompareReduce.Op.FCMNE; // `ZERO_NE`
        };
        if (op == null) {
            return null;
        }
        return new Ir64Op.SveFpCompareReduce(op, esz, word & PREDICATE_DESTINATION_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, 0, (word >>> PG_SHIFT) & PREDICATE_MASK, true, address);
    }
}
