package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE do multiply-add de ponto flutuante da B17.14: `FMLA`/`FMLS`/`FNMLA`/`FNMLS` predicados (prefixo
/// `0x65`, as oito linhas de `### SVE FP Multiply-Add Group`), e — no prefixo `0x64` — `FMLA`/`FMLS`/`FMUL` por elemento
/// indexado, `FCADD`, `FCMLA` e `FCMLA` indexado. Cada padrão é o `decodetree` do `sve.decode` do QEMU transcrito em
/// `máscara`/`valor`.
///
/// **`esz = 0` nas linhas que o aceitam NÃO é "meia precisão" nem não alocado: é BFloat16** (`FEAT_SVE_B16B16` — o
/// QEMU comenta "These insns use MO_8 to encode BFloat16" e exige `aa64_sve_b16b16`). Nenhum preset declara essa
/// feature ainda e não há semântica de BFloat16 no {@code SveFloat}, então essas linhas são RECUSADAS (`null`, G8) —
/// pendência nomeada, não exclusão. Em `FCADD`/`FCMLA` `esz = 0` é não alocado de fato.
///
/// Os formatos indexados, por linha (errar qual é silencioso, só `index`/`rm` altos mostram):
///
/// | formato | tamanho | índice | `Zm` |
/// |---|---|---|---|
/// | `@rrxr_3` | meia | `bit 22 : bits 20:19` (3 bits) | `Z0`-`Z7` |
/// | `@rrxr_2` | simples | `bits 20:19` | `Z0`-`Z7` |
/// | `@rrxr_1` | dupla | `bit 20` | `Z0`-`Z15` |
/// | `FCMLA_zzxz` (`10`) | meia (pares) | `bits 20:19` | `Z0`-`Z7` |
/// | `FCMLA_zzxz` (`11`) | simples (pares) | `bit 20` | `Z0`-`Z15` |
///
/// `FCADD`/`FCMLA` valem sob `FEAT_SVE` (o gate é do chamador): o `TRANS_FEAT` do QEMU é `aa64_sme_or_sve`, **não**
/// `FEAT_FCMA` (que é a forma AdvSIMD). A spec da task dizia o contrário; o QEMU é o oráculo.
final class Aarch64SveFpMultiplyAddDecoder {
    private static final int REGISTER_MASK = 0b11111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int PREDICATE_MASK = 0b111;
    private static final int OPCODE_SHIFT = 13;
    private static final int OPCODE_MASK = 0b111;
    private static final int ROT_MASK = 0b11;
    private static final int ROT_SHIFT = 10;
    private static final int FCMLA_PREDICATED_ROT_SHIFT = 13;
    private static final int FCADD_ROT_BIT = 16;
    private static final int RM4_MASK = 0b1111;
    private static final int RM3_MASK = 0b111;
    private static final int INDEX_LOW_SHIFT = 19;
    private static final int INDEX_HIGH_BIT = 22;
    private static final int INDEX_2_MASK = 0b11;
    private static final int INDEX_2_BITS = 2;
    private static final int INDEXED_OPCODE_SHIFT = 10;
    private static final int INDEXED_OPCODE_MASK = 0b111111;
    private static final int ESZ_FIELD_HALF_INDEXED = 0b10;
    private static final int ESZ_FIELD_DOUBLE = 0b11;

    private static final int ESZ_BFLOAT16 = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;

    private static final int PREDICATED_MASK = 0xFF200000;
    private static final int PREDICATED_VALUE = 0x65200000;
    private static final int OPCODE_WRITES_MULTIPLICAND_BIT = 0b100;
    private static final int OPCODE_KIND_MASK = 0b011;

    private static final int INDEXED_MASK = 0xFF20C000;
    private static final int INDEXED_VALUE = 0x64200000;
    private static final int FCMLA_INDEXED_VALUE = 0x64201000;
    private static final int FCMLA_INDEXED_MASK = 0xFF20F000;
    private static final int FCADD_MASK = 0xFF3EE000;
    private static final int FCADD_VALUE = 0x64008000;
    private static final int FCMLA_MASK = 0xFF208000;
    private static final int FCMLA_VALUE = 0x64000000;

    private static final int INDEXED_FMLA = 0b000000;
    private static final int INDEXED_FMLS = 0b000001;
    private static final int INDEXED_FMUL = 0b001000;

    /// Prefixo `0x65`: as oito linhas de `FMLA`/`FMLS`/`FNMLA`/`FNMLS` predicados. `null` = não é deste grupo.
    Ir64Op decodePrefix65(int word, long address) {
        if ((word & PREDICATED_MASK) != PREDICATED_VALUE) {
            return null;
        }
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if (esz == ESZ_BFLOAT16) {
            return null; // BFMLA/BFMLS/BFNMLA/BFNMLS: FEAT_SVE_B16B16 (pendência)
        }
        int opcode = field(word, OPCODE_SHIFT, OPCODE_MASK);
        Ir64Op.SveFpMultiplyAdd.Op op = switch (opcode & OPCODE_KIND_MASK) {
            case 0b00 -> Ir64Op.SveFpMultiplyAdd.Op.FMLA;
            case 0b01 -> Ir64Op.SveFpMultiplyAdd.Op.FMLS;
            case 0b10 -> Ir64Op.SveFpMultiplyAdd.Op.FNMLA;
            default -> Ir64Op.SveFpMultiplyAdd.Op.FNMLS;
        };
        int rd = field(word, 0, REGISTER_MASK);
        int pg = field(word, PG_SHIFT, PREDICATE_MASK);
        int low = field(word, RN_SHIFT, REGISTER_MASK);
        int high = field(word, RM_SHIFT, REGISTER_MASK);
        if ((opcode & OPCODE_WRITES_MULTIPLICAND_BIT) != 0) {
            // `@rdn_pg_rm_ra`: escreve o multiplicando (`FMAD`/`FMSB`/`FNMAD`/`FNMSB`): rn = Zdn, rm = bits 9:5, ra = bits 20:16.
            return new Ir64Op.SveFpMultiplyAdd(op, esz, rd, rd, low, high, pg, true, false, 0, 0, address);
        }
        // `@rda_pg_rn_rm`: escreve o acumulador: ra = Zda, rn = bits 9:5, rm = bits 20:16.
        return new Ir64Op.SveFpMultiplyAdd(op, esz, rd, low, high, rd, pg, true, false, 0, 0, address);
    }

    /// Prefixo `0x64`: `FMLA`/`FMLS`/`FMUL` indexados, `FCADD`, `FCMLA` e `FCMLA` indexado. `null` = não é deste grupo.
    Ir64Op decodePrefix64(int word, long address) {
        if ((word & FCADD_MASK) == FCADD_VALUE) {
            return decodeComplexAdd(word, address);
        }
        if ((word & FCMLA_MASK) == FCMLA_VALUE) {
            return decodeComplexMultiplyAdd(word, address);
        }
        if ((word & FCMLA_INDEXED_MASK) == FCMLA_INDEXED_VALUE) {
            return decodeComplexMultiplyAddIndexed(word, address);
        }
        if ((word & INDEXED_MASK) == INDEXED_VALUE) {
            return decodeIndexed(word, address);
        }
        return null;
    }

    private Ir64Op decodeComplexAdd(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if (esz == ESZ_BFLOAT16) {
            return null;
        }
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpMultiplyAdd(Ir64Op.SveFpMultiplyAdd.Op.FCADD, esz, rd, rd,
                field(word, RN_SHIFT, REGISTER_MASK), 0, field(word, PG_SHIFT, PREDICATE_MASK), true, false, 0,
                (word >>> FCADD_ROT_BIT) & 1, address);
    }

    private Ir64Op decodeComplexMultiplyAdd(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if (esz == ESZ_BFLOAT16) {
            return null;
        }
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpMultiplyAdd(Ir64Op.SveFpMultiplyAdd.Op.FCMLA, esz, rd,
                field(word, RN_SHIFT, REGISTER_MASK), field(word, RM_SHIFT, REGISTER_MASK), rd,
                field(word, PG_SHIFT, PREDICATE_MASK), true, false, 0,
                field(word, FCMLA_PREDICATED_ROT_SHIFT, ROT_MASK), address);
    }

    private Ir64Op decodeComplexMultiplyAddIndexed(int word, long address) {
        int size = field(word, ESZ_SHIFT, ESZ_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, RN_SHIFT, REGISTER_MASK);
        int rot = field(word, ROT_SHIFT, ROT_MASK);
        if (size == ESZ_FIELD_HALF_INDEXED) {
            return new Ir64Op.SveFpMultiplyAdd(Ir64Op.SveFpMultiplyAdd.Op.FCMLA, ESZ_HALF, rd, rn,
                    field(word, RM_SHIFT, RM3_MASK), rd, 0, false, true, field(word, INDEX_LOW_SHIFT, INDEX_2_MASK),
                    rot, address);
        }
        if (size == ESZ_FIELD_DOUBLE) {
            return new Ir64Op.SveFpMultiplyAdd(Ir64Op.SveFpMultiplyAdd.Op.FCMLA, ESZ_SINGLE, rd, rn,
                    field(word, RM_SHIFT, RM4_MASK), rd, 0, false, true, field(word, INDEX_LOW_SHIFT + 1, 1), rot,
                    address);
        }
        return null; // `00`/`01`: não alocados
    }

    private Ir64Op decodeIndexed(int word, long address) {
        Ir64Op.SveFpMultiplyAdd.Op op = switch (field(word, INDEXED_OPCODE_SHIFT, INDEXED_OPCODE_MASK)) {
            case INDEXED_FMLA -> Ir64Op.SveFpMultiplyAdd.Op.FMLA;
            case INDEXED_FMLS -> Ir64Op.SveFpMultiplyAdd.Op.FMLS;
            case INDEXED_FMUL -> Ir64Op.SveFpMultiplyAdd.Op.FMUL;
            default -> null; // inclui `000010`/`000011`/`001010`: BFMLA/BFMLS/BFMUL (FEAT_SVE_B16B16, pendência)
        };
        if (op == null) {
            return null;
        }
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, RN_SHIFT, REGISTER_MASK);
        int ra = op == Ir64Op.SveFpMultiplyAdd.Op.FMUL ? 0 : rd;
        return switch (field(word, ESZ_SHIFT, ESZ_MASK)) {
            case 0b00, 0b01 -> new Ir64Op.SveFpMultiplyAdd(op, ESZ_HALF, rd, rn, field(word, RM_SHIFT, RM3_MASK), ra,
                    0, false, true, (((word >>> INDEX_HIGH_BIT) & 1) << INDEX_2_BITS)
                            | field(word, INDEX_LOW_SHIFT, INDEX_2_MASK), 0, address);
            case ESZ_FIELD_HALF_INDEXED -> new Ir64Op.SveFpMultiplyAdd(op, ESZ_SINGLE, rd, rn,
                    field(word, RM_SHIFT, RM3_MASK), ra, 0, false, true, field(word, INDEX_LOW_SHIFT, INDEX_2_MASK),
                    0, address);
            default -> new Ir64Op.SveFpMultiplyAdd(op, ESZ_DOUBLE, rd, rn, field(word, RM_SHIFT, RM4_MASK), ra, 0,
                    false, true, field(word, INDEX_LOW_SHIFT + 1, 1), 0, address);
        };
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
