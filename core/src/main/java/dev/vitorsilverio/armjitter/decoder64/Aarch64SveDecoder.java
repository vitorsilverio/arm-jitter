package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.SvePredicateOp64;

/// Decoder das instruções SVE (`FEAT_SVE`, classe `op0 = 0010` do A64), fatiado por grupo do
/// `sve.decode` do QEMU. Hoje cobre os predicados da B17.4: lógica de predicado, "misc"
/// (`PTEST`/`PTRUE`/`FFR`/`PFIRST`/`PNEXT`), partition break, contagem por predicado e contagem de
/// elementos — 38 dos 40 encodings do recorte (as duas exceções estão abaixo).
///
/// **Cada padrão abaixo é o `decodetree` transcrito em `máscara`/`valor`** (bits fixos de cada linha
/// de `sve.decode`, medidos contra `aarch64-none-elf-as`); nada é derivado por analogia. Tudo que não
/// bate exatamente devolve `null` e o chamador recusa a instrução (G8) — em particular:
///
/// - `PTRUE` e `CNTP` na forma predicado-como-contador (`PTRUE_cnt`, `CNTP_c`, `PN8`-`PN15`) são SVE2.1 e vivem em
///   {@link Aarch64SveCounterDecoder} (B17.28): o contador NÃO é uma máscara de bits (Armadilha 4 da B17.4);
/// - `SEL` com `S = 1` (não alocado) e `INCP`/`SQINCP` vetoriais com `esz = 0` (não alocados).
///
/// `FIRSTP`/`LASTP` exigem `FEAT_SVE2p2` ({@link Aarch64Feature#SVE2_2}); o resto exige só
/// {@link Aarch64Feature#SVE}. Presets sem SVE recusam o espaço inteiro.
final class Aarch64SveDecoder {
    // ── Classe de encoding SVE: prefixos de 8 bits em bits[31:24] ────────────────────────────────
    private static final int PREFIX_SHIFT = 24;
    private static final int PREFIX_MASK = 0xFF;
    private static final int PREFIX_PREDICATE = 0x25;
    private static final int PREFIX_ELEMENT_COUNT = 0x04;
    private static final int PREFIX_IMMEDIATE = 0x05;
    private static final int PREFIX_MULTIPLY = 0x44;
    private static final int PREFIX_SVE2_ACCUMULATE = 0x45;
    private static final int PREFIX_COMPARE = 0x24;
    private static final int PREFIX_FP_ARITHMETIC = 0x65;
    private static final int PREFIX_FP_INDEXED_COMPLEX = 0x64;
    private static final int PREFIX_LOAD_UNSIZED_LOW = 0x84;
    private static final int PREFIX_LOAD_UNSIZED_HIGH = 0x85;
    private static final int PREFIX_LOAD_CONTIGUOUS_LOW = 0xA4;
    private static final int PREFIX_LOAD_CONTIGUOUS_HIGH = 0xA5;
    private static final int PREFIX_STORE_LOW = 0xE4;
    private static final int PREFIX_STORE_HIGH = 0xE5;
    private static final int PREFIX_GATHER_64_LOW = 0xC4;
    private static final int PREFIX_GATHER_64_HIGH = 0xC5;

    // ── Campos comuns ─────────────────────────────────────────────────────────────────────────────
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int PREDICATE_FIELD_MASK = 0b1111;
    private static final int REGISTER_FIELD_MASK = 0b11111;
    private static final int PD_SHIFT = 0;
    private static final int PN_SHIFT = 5;
    private static final int PG_LOGICAL_SHIFT = 10;
    private static final int PM_SHIFT = 16;
    private static final int BIT_OPERATION_SELECT = 23;
    private static final int BIT_SET_FLAGS = 22;
    private static final int BIT_O2 = 9;
    private static final int BIT_O3 = 4;
    private static final int PATTERN_SHIFT = 5;
    private static final int PATTERN_MASK = 0b11111;
    private static final int PTRUE_PATTERN_SHIFT = 5;
    private static final int PTRUE_SET_FLAGS_BIT = 16;
    private static final int COUNT_PG_SHIFT = 10;
    private static final int INCDECP_PG_SHIFT = 5;
    private static final int INCDECP_D_BIT = 16;
    private static final int SINCDECP_D_BIT = 17;
    private static final int SINCDECP_U_BIT = 16;
    private static final int ELEMENT_COUNT_IMM4_SHIFT = 16;
    private static final int ELEMENT_COUNT_IMM4_MASK = 0b1111;
    private static final int ELEMENT_COUNT_D_BIT_UNSIGNED_FORMS = 11;
    private static final int ELEMENT_COUNT_U_BIT_UNSIGNED_FORMS = 10;
    private static final int ELEMENT_COUNT_D_BIT_PLAIN_FORMS = 10;
    private static final int RESERVED_ESZ_BYTE = 0;

    // ── B17.5: inteiro sem predicado (prefixo 0x04) ──────────────────────────────────────────────
    private static final int OPCODE_SHIFT = 10;
    private static final int OPCODE_FIELD_MASK = 0b111111;
    private static final int OP_ADD = 0b000000;
    private static final int OP_SUB = 0b000001;
    private static final int OP_SQADD = 0b000100;
    private static final int OP_UQADD = 0b000101;
    private static final int OP_SQSUB = 0b000110;
    private static final int OP_UQSUB = 0b000111;
    private static final int OP_LOGICAL = 0b001100;
    private static final int OP_XAR = 0b001101;
    private static final int OP_TERNARY_EOR3_BCAX = 0b001110;
    private static final int OP_TERNARY_BSL = 0b001111;
    private static final int OP_SHIFT_WIDE_ASR = 0b100000;
    private static final int OP_SHIFT_WIDE_LSR = 0b100001;
    private static final int OP_SHIFT_WIDE_LSL = 0b100011;
    private static final int OP_SHIFT_IMM_ASR = 0b100100;
    private static final int OP_SHIFT_IMM_LSR = 0b100101;
    private static final int OP_SHIFT_IMM_LSL = 0b100111;
    private static final int OP_INDEX_II = 0b010000;
    private static final int OP_INDEX_RI = 0b010001;
    private static final int OP_INDEX_IR = 0b010010;
    private static final int OP_INDEX_RR = 0b010011;
    private static final int OP_FTSSEL = 0b101100;
    private static final int FEXPA_MASK = 0xFF3FFC00;
    private static final int FEXPA_VALUE = 0x0420B800;
    private static final int MOVPRFX_MASK = 0xFFFFFC00;
    private static final int MOVPRFX_VALUE = 0x0420BC00;
    private static final int MULTIPLY_ADD_MASK = 0xFF200000;
    private static final int MULTIPLY_ADD_BASE = 0x04000000;
    private static final int MULTIPLY_ADD_OPCODE_SHIFT = 13;
    private static final int MULTIPLY_ADD_OPCODE_MASK = 0b111;
    private static final int MULTIPLY_ADD_MLA = 0b010;
    private static final int MULTIPLY_ADD_MLS = 0b011;
    private static final int MULTIPLY_ADD_MAD = 0b110;
    private static final int MULTIPLY_ADD_MSB = 0b111;
    private static final int PG_MULTIPLY_SHIFT = 10;
    private static final int PG_MULTIPLY_MASK = 0b111;
    private static final int RM_SHIFT = 16;
    private static final int RA_SHIFT = 5;
    private static final int TSZ_HIGH_SHIFT = 22;
    private static final int TSZ_LOW_SHIFT = 16;
    private static final int TSZ_HIGH_MASK = 0b11;
    private static final int TSZ_LOW_MASK = 0b11111;
    private static final int TSZ_HIGH_FIELD_SHIFT = 5;
    private static final int TSZ_ESZ_SHIFT = 3;
    private static final int SIGNED_IMMEDIATE_BITS = 5;
    private static final int INDEX_IMM_HIGH_SHIFT = 16;
    private static final int INDEX_IMM_LOW_SHIFT = 5;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int ESIZE_BITS_BASE = 8;
    private static final int ESIZE_SHR_BASE = 16;

    // ── B17.6: inteiro predicado (prefixo 0x04, bit 21 = 0) ──────────────────────────────────────
    private static final int BIT_PREDICATED_EXCLUDED = 21;
    private static final int PREDICATED_GROUP_SHIFT = 13;
    private static final int PREDICATED_GROUP_MASK = 0b111;
    private static final int PREDICATED_GROUP_BINARY = 0b000;
    private static final int PREDICATED_GROUP_SHIFT_OPS = 0b100;
    private static final int PREDICATED_GROUP_UNARY = 0b101;
    private static final int PREDICATED_GROUP_REDUCTION = 0b001;
    // B17.7: opcode (bits 20:16) do grupo de redução.
    private static final int RED_SADDV = 0x00;
    private static final int RED_UADDV = 0x01;
    private static final int RED_ADDQV = 0x05;
    private static final int RED_SMAXV = 0x08;
    private static final int RED_UMAXV = 0x09;
    private static final int RED_SMINV = 0x0A;
    private static final int RED_UMINV = 0x0B;
    private static final int RED_SMAXQV = 0x0C;
    private static final int RED_UMAXQV = 0x0D;
    private static final int RED_SMINQV = 0x0E;
    private static final int RED_UMINQV = 0x0F;
    private static final int RED_MOVPRFX_Z = 0x10;
    private static final int RED_MOVPRFX_M = 0x11;
    private static final int RED_ORV = 0x18;
    private static final int RED_EORV = 0x19;
    private static final int RED_ANDV = 0x1A;
    private static final int RED_ORQV = 0x1C;
    private static final int RED_EORQV = 0x1D;
    private static final int RED_ANDQV = 0x1E;
    private static final int PREDICATED_OPCODE_SHIFT = 16;
    private static final int PREDICATED_OPCODE_MASK = 0b11111;
    private static final int PG_PREDICATED_SHIFT = 10;
    private static final int PG_PREDICATED_MASK = 0b111;
    private static final int RN_PREDICATED_SHIFT = 5;
    private static final int TSZ_PREDICATED_LOW_SHIFT = 5;
    private static final int UNARY_MERGING_BIT = 4;
    private static final int UNARY_BIT_OPERATIONS = 3;
    private static final int UNARY_SELECTOR_MASK = 0b111;
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;
    // Binário predicado: bits 20:16 (bit 21 = 0 já garantido).
    private static final int PRED_ADD = 0x00;
    private static final int PRED_SUB = 0x01;
    private static final int PRED_SUBR = 0x03;
    private static final int PRED_SMAX = 0x08;
    private static final int PRED_UMAX = 0x09;
    private static final int PRED_SMIN = 0x0A;
    private static final int PRED_UMIN = 0x0B;
    private static final int PRED_SABD = 0x0C;
    private static final int PRED_UABD = 0x0D;
    private static final int PRED_MUL = 0x10;
    private static final int PRED_SMULH = 0x12;
    private static final int PRED_UMULH = 0x13;
    private static final int PRED_SDIV = 0x14;
    private static final int PRED_UDIV = 0x15;
    private static final int PRED_SDIVR = 0x16;
    private static final int PRED_UDIVR = 0x17;
    private static final int PRED_ORR = 0x18;
    private static final int PRED_EOR = 0x19;
    private static final int PRED_AND = 0x1A;
    private static final int PRED_BIC = 0x1B;
    // Shift predicado.
    private static final int PRED_SHIFT_ASR_IMM = 0x00;
    private static final int PRED_SHIFT_LSR_IMM = 0x01;
    private static final int PRED_SHIFT_LSL_IMM = 0x03;
    private static final int PRED_SHIFT_ASRD = 0x04;
    private static final int PRED_SHIFT_SQSHL_IMM = 0x06;
    private static final int PRED_SHIFT_UQSHL_IMM = 0x07;
    private static final int PRED_SHIFT_SRSHR = 0x0C;
    private static final int PRED_SHIFT_URSHR = 0x0D;
    private static final int PRED_SHIFT_SQSHLU = 0x0F;
    private static final int PRED_SHIFT_ASR = 0x10;
    private static final int PRED_SHIFT_LSR = 0x11;
    private static final int PRED_SHIFT_LSL = 0x13;
    private static final int PRED_SHIFT_ASRR = 0x14;
    private static final int PRED_SHIFT_LSRR = 0x15;
    private static final int PRED_SHIFT_LSLR = 0x17;
    private static final int PRED_SHIFT_ASR_WIDE = 0x18;
    private static final int PRED_SHIFT_LSR_WIDE = 0x19;
    private static final int PRED_SHIFT_LSL_WIDE = 0x1B;

    // ── Máscaras/valores (bits fixos de cada linha de sve.decode) ───────────────────────────────
    private static final int LOGICAL_MASK = 0xFF30C000;
    private static final int LOGICAL_VALUE = 0x25004000;
    private static final int BRKP_MASK = 0xFF30C200;
    private static final int BRKP_VALUE = 0x2500C000;
    private static final int PTEST_MASK = 0xFFFFC21F;
    private static final int PTEST_VALUE = 0x2550C000;
    private static final int PTRUE_MASK = 0xFF3EFC10;
    private static final int PTRUE_VALUE = 0x2518E000;
    private static final int SETFFR_WORD = 0x252C9000;
    private static final int PFALSE_MASK = 0xFFFFFFF0;
    private static final int PFALSE_VALUE = 0x2518E400;
    private static final int RDFFR_MASK = 0xFFFFFFF0;
    private static final int RDFFR_VALUE = 0x2519F000;
    private static final int RDFFR_PREDICATED_MASK = 0xFFBFFE10;
    private static final int RDFFR_PREDICATED_VALUE = 0x2518F000;
    private static final int WRFFR_MASK = 0xFFFFFE1F;
    private static final int WRFFR_VALUE = 0x25289000;
    private static final int PFIRST_MASK = 0xFFFFFE10;
    private static final int PFIRST_VALUE = 0x2558C000;
    private static final int PNEXT_MASK = 0xFF3FFE10;
    private static final int PNEXT_VALUE = 0x2519C400;
    private static final int BRKAB_MASK = 0xFF3FC200;
    private static final int BRKAB_VALUE = 0x25104000;
    private static final int BRKN_MASK = 0xFFBFC210;
    private static final int BRKN_VALUE = 0x25184000;
    private static final int CNTP_MASK = 0xFF3FC200;
    private static final int CNTP_VALUE = 0x25208000;
    private static final int FIRSTP_VALUE = 0x25218000;
    private static final int LASTP_VALUE = 0x25228000;
    private static final int INCDECP_SCALAR_MASK = 0xFF3EFE00;
    private static final int INCDECP_SCALAR_VALUE = 0x252C8800;
    private static final int INCDECP_VECTOR_MASK = 0xFF3EFE00;
    private static final int INCDECP_VECTOR_VALUE = 0x252C8000;
    private static final int SINCDECP_SCALAR_32_MASK = 0xFF3CFE00;
    private static final int SINCDECP_SCALAR_32_VALUE = 0x25288800;
    private static final int SINCDECP_SCALAR_64_MASK = 0xFF3CFE00;
    private static final int SINCDECP_SCALAR_64_VALUE = 0x25288C00;
    private static final int SINCDECP_VECTOR_MASK = 0xFF3CFE00;
    private static final int SINCDECP_VECTOR_VALUE = 0x25288000;
    private static final int CNT_R_MASK = 0xFF30FC00;
    private static final int CNT_R_VALUE = 0x0420E000;
    private static final int INCDEC_R_MASK = 0xFF30F800;
    private static final int INCDEC_R_VALUE = 0x0430E000;
    private static final int SINCDEC_R_32_MASK = 0xFF30F000;
    private static final int SINCDEC_R_32_VALUE = 0x0420F000;
    private static final int SINCDEC_R_64_VALUE = 0x0430F000;
    private static final int INCDEC_V_MASK = 0xFF30F800;
    private static final int INCDEC_V_VALUE = 0x0430C000;
    private static final int SINCDEC_V_MASK = 0xFF30F000;
    private static final int SINCDEC_V_VALUE = 0x0420C000;

    // ── B17.12: endereçamento (prefixo 0x04, bit 21 = 1) ──────────────────────────────────────────────
    private static final int ADDRESSING_IMM_SHIFT = 5;
    private static final int ADDRESSING_IMM_MASK = 0b111111;
    private static final int ADDRESSING_IMM_BITS = 6;
    private static final int ADDVL_MASK = 0xFFE0F800;
    private static final int ADDVL_VALUE = 0x04205000;
    private static final int ADDPL_VALUE = 0x04605000;
    private static final int RDVL_MASK = 0xFFFFF800;
    private static final int RDVL_VALUE = 0x04BF5000;
    /// Formas SME (`SVL`): os mesmos bits das SVE com `bit 11 = 1`.
    private static final int ADDSVL_VALUE = 0x04205800;
    private static final int ADDSPL_VALUE = 0x04605800;
    private static final int RDSVL_VALUE = 0x04BF5800;
    private static final int ADR_MASK = 0xFF20F000;
    private static final int ADR_VALUE = 0x0420A000;
    private static final int ADR_OPCODE_S32 = 0b00;
    private static final int ADR_OPCODE_U32 = 0b01;
    private static final int ADR_OPCODE_P32 = 0b10;
    private static final int ADR_MSZ_SHIFT = 10;
    private static final int ADR_MSZ_MASK = 0b11;

    private final Aarch64Architecture architecture;
    private final Aarch64SveMultiplyDecoder multiply;
    private final Aarch64SvePermuteDecoder permute;
    private final Aarch64SvePredicatedPermuteDecoder predicatedPermute;
    private final Aarch64SveCompareDecoder compare;
    private final Aarch64SveFpArithmeticDecoder floatingPoint;
    private final Aarch64SveFpMultiplyAddDecoder floatingPointMultiplyAdd;
    private final Aarch64SveFpCompareReduceDecoder floatingPointCompareReduce;
    private final Aarch64SveFpUnaryDecoder floatingPointUnary;
    private final Aarch64SveLoadDecoder load;
    private final Aarch64SveStoreDecoder store;
    private final Aarch64SveGatherDecoder gather;
    private final Aarch64SveCounterDecoder counter;
    private final Aarch64Sve2IntegerDecoder sve2Integer;
    private final Aarch64Sve2MiscDecoder sve2Misc;
    private final Aarch64SveCryptoDecoder sve2Crypto;
    private final Aarch64SveFpConvertFp8Decoder floatingPointConvertFp8;
    private final Aarch64SveFpMatrixDecoder floatingPointMatrix;
    private final Aarch64SveFpConvertOddDecoder floatingPointConvertOdd;
    private final Aarch64SveFp8MultiplyDecoder fp8Multiply;
    private final Aarch64SveFpWidenDecoder floatingPointWiden;

    Aarch64SveDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
        this.multiply = new Aarch64SveMultiplyDecoder(architecture);
        this.permute = new Aarch64SvePermuteDecoder(architecture);
        this.predicatedPermute = new Aarch64SvePredicatedPermuteDecoder(architecture);
        this.compare = new Aarch64SveCompareDecoder(architecture);
        this.floatingPoint = new Aarch64SveFpArithmeticDecoder(architecture);
        this.floatingPointMultiplyAdd = new Aarch64SveFpMultiplyAddDecoder(architecture);
        this.floatingPointCompareReduce = new Aarch64SveFpCompareReduceDecoder(architecture);
        this.floatingPointUnary = new Aarch64SveFpUnaryDecoder(architecture);
        this.load = new Aarch64SveLoadDecoder(architecture);
        this.store = new Aarch64SveStoreDecoder(architecture);
        this.gather = new Aarch64SveGatherDecoder(architecture);
        this.counter = new Aarch64SveCounterDecoder(architecture);
        this.sve2Integer = new Aarch64Sve2IntegerDecoder(architecture);
        this.sve2Misc = new Aarch64Sve2MiscDecoder(architecture);
        this.sve2Crypto = new Aarch64SveCryptoDecoder(architecture);
        this.floatingPointConvertFp8 = new Aarch64SveFpConvertFp8Decoder(architecture);
        this.floatingPointMatrix = new Aarch64SveFpMatrixDecoder(architecture);
        this.floatingPointConvertOdd = new Aarch64SveFpConvertOddDecoder(architecture);
        this.fp8Multiply = new Aarch64SveFp8MultiplyDecoder(architecture);
        this.floatingPointWiden = new Aarch64SveFpWidenDecoder(architecture);
    }

    /// Os `LD1`/`ST1` multi-vetor governados por predicado-como-contador (B17.28) moram fora da classe `001` (prefixos
    /// `0xA0`/`0xA1`, `bits[28:26] = 000`, o espaço que a SME compartilha): o `Aarch64Decoder` chama este ponto de entrada.
    Ir64Op decodeMultiVector(int word, long address) {
        return counter.decodeMultiVector(word, address);
    }

    /// Decodifica uma palavra da classe SVE. Devolve `null` quando a palavra não é (ainda) uma
    /// instrução SVE reconhecida, ou quando a arquitetura não declara `FEAT_SVE` — o chamador a
    /// recusa como qualquer encoding desconhecido (G8).
    Ir64Op decode(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE)) {
            return decodeStreamingAddressing(word, address);
        }
        return switch ((word >>> PREFIX_SHIFT) & PREFIX_MASK) {
            case PREFIX_PREDICATE -> {
                Ir64Op counterPredicate = counter.decodePrefix25(word, address);
                if (counterPredicate != null) {
                    yield counterPredicate;
                }
                Ir64Op predicate = decodePredicateGroup(word, address);
                if (predicate != null) {
                    yield predicate;
                }
                Ir64Op comparison = compare.decodePrefix25(word, address);
                if (comparison != null) {
                    yield comparison;
                }
                Ir64Op immediate25 = Aarch64SveImmediateDecoder.decodePrefix25(word, address);
                yield immediate25 != null ? immediate25 : sve2Misc.decodePrefix25(word, address);
            }
            case PREFIX_COMPARE -> compare.decodePrefix24(word, address);
            case PREFIX_FP_ARITHMETIC -> {
                Ir64Op arithmetic = floatingPoint.decodePrefix65(word, address);
                if (arithmetic != null) {
                    yield arithmetic;
                }
                Ir64Op multiplyAdd = floatingPointMultiplyAdd.decodePrefix65(word, address);
                if (multiplyAdd != null) {
                    yield multiplyAdd;
                }
                Ir64Op compareReduce = floatingPointCompareReduce.decodePrefix65(word, address);
                if (compareReduce != null) {
                    yield compareReduce;
                }
                Ir64Op unaryBase = floatingPointUnary.decode(word, address);
                if (unaryBase != null) {
                    yield unaryBase;
                }
                Ir64Op convertFp8 = floatingPointConvertFp8.decode(word, address);
                if (convertFp8 != null) {
                    yield convertFp8;
                }
                yield floatingPointConvertOdd.decodePrefix65(word, address);
            }
            case PREFIX_FP_INDEXED_COMPLEX -> {
                Ir64Op indexed = floatingPointMultiplyAdd.decodePrefix64(word, address);
                if (indexed != null) {
                    yield indexed;
                }
                Ir64Op quadword = floatingPointCompareReduce.decodePrefix64(word, address);
                if (quadword != null) {
                    yield quadword;
                }
                Ir64Op unary = floatingPointUnary.decode(word, address);
                if (unary != null) {
                    yield unary;
                }
                Ir64Op misc = sve2Misc.decodePrefix64(word, address);
                if (misc != null) {
                    yield misc;
                }
                Ir64Op matrix = floatingPointMatrix.decode(word, address);
                if (matrix != null) {
                    yield matrix;
                }
                Ir64Op convertOdd = floatingPointConvertOdd.decodePrefix64(word, address);
                if (convertOdd != null) {
                    yield convertOdd;
                }
                Ir64Op fp8 = fp8Multiply.decode(word, address);
                yield fp8 != null ? fp8 : floatingPointWiden.decode(word, address);
            }
            case PREFIX_IMMEDIATE -> {
                Ir64Op immediate = Aarch64SveImmediateDecoder.decodePrefix05(word, address);
                if (immediate != null) {
                    yield immediate;
                }
                Ir64Op permutation = permute.decodePrefix05(word, address);
                yield permutation != null ? permutation : predicatedPermute.decodePrefix05(word, address);
            }
            case PREFIX_MULTIPLY -> {
                Ir64Op product = multiply.decode(word, address);
                if (product == null) {
                    product = sve2Integer.decodePrefix44(word, address);
                }
                yield product != null ? product : permute.decodePrefix44(word, address);
            }
            case PREFIX_SVE2_ACCUMULATE -> {
                Ir64Op accumulate = sve2Integer.decodePrefix45(word, address);
                if (accumulate != null) {
                    yield accumulate;
                }
                Ir64Op misc = sve2Misc.decodePrefix45(word, address);
                yield misc != null ? misc : sve2Crypto.decodePrefix45(word, address);
            }
            case PREFIX_ELEMENT_COUNT -> {
                Ir64Op addressing = decodeAddressing(word, address);
                if (addressing != null) {
                    yield addressing;
                }
                Ir64Op integer = decodeIntegerUnpredicated(word, address);
                if (integer == null) {
                    integer = decodeIntegerPredicated(word, address);
                }
                if (integer == null) {
                    integer = sve2Integer.decodePrefix04(word, address);
                }
                yield integer != null ? integer : decodeElementCount(word, address);
            }
            case PREFIX_LOAD_UNSIZED_LOW, PREFIX_LOAD_UNSIZED_HIGH -> {
                Ir64Op unsized = load.decode(word, address);
                yield unsized != null ? unsized : gather.decode(word, address);
            }
            case PREFIX_LOAD_CONTIGUOUS_LOW, PREFIX_LOAD_CONTIGUOUS_HIGH -> load.decode(word, address);
            case PREFIX_GATHER_64_LOW, PREFIX_GATHER_64_HIGH -> gather.decode(word, address);
            case PREFIX_STORE_LOW, PREFIX_STORE_HIGH -> store.decode(word, address);
            default -> null;
        };
    }

    private Ir64Op decodePredicateGroup(int word, long address) {
        if ((word & LOGICAL_MASK) == LOGICAL_VALUE) {
            return decodeLogical(word, address);
        }
        if ((word & BRKP_MASK) == BRKP_VALUE) {
            return decodeBrkp(word, address);
        }
        if ((word & PTEST_MASK) == PTEST_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.PTEST, 0, 0, field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK),
                    field(word, PN_SHIFT, PREDICATE_FIELD_MASK), true, 0, address);
        }
        if ((word & PTRUE_MASK) == PTRUE_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.PTRUE, field(word, ESZ_SHIFT, ESZ_MASK),
                    field(word, PD_SHIFT, PREDICATE_FIELD_MASK), 0, 0, bit(word, PTRUE_SET_FLAGS_BIT),
                    field(word, PTRUE_PATTERN_SHIFT, PATTERN_MASK), address);
        }
        if (word == SETFFR_WORD) {
            return misc(SvePredicateOp64.PredicateMisc.Op.SETFFR, 0, 0, 0, 0, false, 0, address);
        }
        if ((word & PFALSE_MASK) == PFALSE_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.PFALSE, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK), 0, 0,
                    false, 0, address);
        }
        if ((word & RDFFR_MASK) == RDFFR_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.RDFFR, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK), 0, 0,
                    false, 0, address);
        }
        if ((word & RDFFR_PREDICATED_MASK) == RDFFR_PREDICATED_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.RDFFR_PREDICATED, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                    field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0, bit(word, BIT_SET_FLAGS), 0, address);
        }
        if ((word & WRFFR_MASK) == WRFFR_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.WRFFR, 0, 0, 0, field(word, PN_SHIFT, PREDICATE_FIELD_MASK), false,
                    0, address);
        }
        if ((word & PFIRST_MASK) == PFIRST_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.PFIRST, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                    field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0, true, 0, address);
        }
        if ((word & PNEXT_MASK) == PNEXT_VALUE) {
            return misc(SvePredicateOp64.PredicateMisc.Op.PNEXT, field(word, ESZ_SHIFT, ESZ_MASK),
                    field(word, PD_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0, true,
                    0, address);
        }
        if ((word & BRKAB_MASK) == BRKAB_VALUE) {
            return decodeBrkAb(word, address);
        }
        if ((word & BRKN_MASK) == BRKN_VALUE) {
            int pd = field(word, PD_SHIFT, PREDICATE_FIELD_MASK);
            return new SvePredicateOp64.PartitionBreak(SvePredicateOp64.PartitionBreak.Op.BRKN, pd,
                    field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK),
                    pd, bit(word, BIT_SET_FLAGS), false, address);
        }
        return decodePredicateCount(word, address);
    }

    private Ir64Op decodeLogical(int word, long address) {
        boolean secondGroup = bit(word, BIT_OPERATION_SELECT);
        boolean setFlags = bit(word, BIT_SET_FLAGS);
        int selector = (bit(word, BIT_O2) ? 2 : 0) | (bit(word, BIT_O3) ? 1 : 0);
        SvePredicateOp64.PredicateLogical.Op op = switch (selector) {
            case 0 -> secondGroup ? SvePredicateOp64.PredicateLogical.Op.ORR : SvePredicateOp64.PredicateLogical.Op.AND;
            case 1 -> secondGroup ? SvePredicateOp64.PredicateLogical.Op.ORN : SvePredicateOp64.PredicateLogical.Op.BIC;
            case 2 -> secondGroup ? SvePredicateOp64.PredicateLogical.Op.NOR : SvePredicateOp64.PredicateLogical.Op.EOR;
            default -> secondGroup ? SvePredicateOp64.PredicateLogical.Op.NAND : SvePredicateOp64.PredicateLogical.Op.SEL;
        };
        if (op == SvePredicateOp64.PredicateLogical.Op.SEL && setFlags) {
            return null; // SEL não tem forma que seta flags: não alocado
        }
        return new SvePredicateOp64.PredicateLogical(op, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PM_SHIFT, PREDICATE_FIELD_MASK), setFlags, address);
    }

    private Ir64Op decodeBrkp(int word, long address) {
        if (bit(word, BIT_OPERATION_SELECT)) {
            return null; // `BRKPA`/`BRKPB` só existem com bit 23 = 0
        }
        SvePredicateOp64.PartitionBreak.Op op = bit(word, BIT_O3)
                ? SvePredicateOp64.PartitionBreak.Op.BRKPB
                : SvePredicateOp64.PartitionBreak.Op.BRKPA;
        return new SvePredicateOp64.PartitionBreak(op, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PM_SHIFT, PREDICATE_FIELD_MASK), bit(word, BIT_SET_FLAGS), false, address);
    }

    private Ir64Op decodeBrkAb(int word, long address) {
        boolean merging = bit(word, BIT_O3);
        boolean setFlags = bit(word, BIT_SET_FLAGS);
        if (merging && setFlags) {
            return null; // as formas /M não têm sufixo S (`@pd_pg_pn_s0`: s = 0 forçado)
        }
        SvePredicateOp64.PartitionBreak.Op op = bit(word, BIT_OPERATION_SELECT)
                ? SvePredicateOp64.PartitionBreak.Op.BRKB
                : SvePredicateOp64.PartitionBreak.Op.BRKA;
        return new SvePredicateOp64.PartitionBreak(op, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0,
                setFlags, merging, address);
    }

    private Ir64Op decodePredicateCount(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if ((word & CNTP_MASK) == CNTP_VALUE) {
            return count(SvePredicateOp64.PredicateCount.Op.CNTP, esz, word, address);
        }
        if ((word & CNTP_MASK) == FIRSTP_VALUE) {
            return architecture.has(Aarch64Feature.SVE2_2)
                    ? count(SvePredicateOp64.PredicateCount.Op.FIRSTP, esz, word, address)
                    : null;
        }
        if ((word & CNTP_MASK) == LASTP_VALUE) {
            return architecture.has(Aarch64Feature.SVE2_2)
                    ? count(SvePredicateOp64.PredicateCount.Op.LASTP, esz, word, address)
                    : null;
        }
        int pg = field(word, INCDECP_PG_SHIFT, PREDICATE_FIELD_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        if ((word & INCDECP_SCALAR_MASK) == INCDECP_SCALAR_VALUE) {
            return incdecp(SvePredicateOp64.PredicateCount.Op.INCDECP_SCALAR, esz, rd, pg, bit(word, INCDECP_D_BIT), false,
                    address);
        }
        if ((word & INCDECP_VECTOR_MASK) == INCDECP_VECTOR_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : incdecp(SvePredicateOp64.PredicateCount.Op.INCDECP_VECTOR, esz, rd, pg, bit(word, INCDECP_D_BIT), false,
                            address);
        }
        boolean decrement = bit(word, SINCDECP_D_BIT);
        boolean unsigned = bit(word, SINCDECP_U_BIT);
        if ((word & SINCDECP_SCALAR_32_MASK) == SINCDECP_SCALAR_32_VALUE) {
            return incdecp(SvePredicateOp64.PredicateCount.Op.SINCDECP_SCALAR_32, esz, rd, pg, decrement, unsigned, address);
        }
        if ((word & SINCDECP_SCALAR_64_MASK) == SINCDECP_SCALAR_64_VALUE) {
            return incdecp(SvePredicateOp64.PredicateCount.Op.SINCDECP_SCALAR_64, esz, rd, pg, decrement, unsigned, address);
        }
        if ((word & SINCDECP_VECTOR_MASK) == SINCDECP_VECTOR_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : incdecp(SvePredicateOp64.PredicateCount.Op.SINCDECP_VECTOR, esz, rd, pg, decrement, unsigned, address);
        }
        return null;
    }

    private Ir64Op decodeElementCount(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        int pattern = field(word, PATTERN_SHIFT, PATTERN_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int multiplier = field(word, ELEMENT_COUNT_IMM4_SHIFT, ELEMENT_COUNT_IMM4_MASK) + 1;
        if ((word & CNT_R_MASK) == CNT_R_VALUE) {
            return elementCount(SveIntegerOp64.ElementCount.Op.CNT, esz, rd, pattern, multiplier, false, true, address);
        }
        if ((word & INCDEC_R_MASK) == INCDEC_R_VALUE) {
            return elementCount(SveIntegerOp64.ElementCount.Op.INCDEC_SCALAR, esz, rd, pattern, multiplier,
                    bit(word, ELEMENT_COUNT_D_BIT_PLAIN_FORMS), true, address);
        }
        boolean decrement = bit(word, ELEMENT_COUNT_D_BIT_UNSIGNED_FORMS);
        boolean unsigned = bit(word, ELEMENT_COUNT_U_BIT_UNSIGNED_FORMS);
        if ((word & SINCDEC_R_32_MASK) == SINCDEC_R_32_VALUE) {
            return elementCount(SveIntegerOp64.ElementCount.Op.SINCDEC_SCALAR_32, esz, rd, pattern, multiplier, decrement,
                    unsigned, address);
        }
        if ((word & SINCDEC_R_32_MASK) == SINCDEC_R_64_VALUE) {
            return elementCount(SveIntegerOp64.ElementCount.Op.SINCDEC_SCALAR_64, esz, rd, pattern, multiplier, decrement,
                    unsigned, address);
        }
        if ((word & INCDEC_V_MASK) == INCDEC_V_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : elementCount(SveIntegerOp64.ElementCount.Op.INCDEC_VECTOR, esz, rd, pattern, multiplier,
                            bit(word, ELEMENT_COUNT_D_BIT_PLAIN_FORMS), true, address);
        }
        if ((word & SINCDEC_V_MASK) == SINCDEC_V_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : elementCount(SveIntegerOp64.ElementCount.Op.SINCDEC_VECTOR, esz, rd, pattern, multiplier, decrement,
                            unsigned, address);
        }
        return null;
    }

    /// Endereçamento da B17.12: `ADDVL`/`ADDPL`/`RDVL` e as 4 formas de `ADR`, mais `ADDSVL`/`ADDSPL`/`RDSVL`
    /// (SME, usam o `SVL`), que têm os MESMOS bits com `bit 11 = 1` e vão para `Op` PRÓPRIOS — reusar os da SVE
    /// daria o comprimento errado em modo streaming.
    private Ir64Op decodeAddressing(int word, long address) {
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int rn = field(word, PN_SHIFT, REGISTER_FIELD_MASK);
        int stackBase = field(word, RM_SHIFT, REGISTER_FIELD_MASK);
        int imm = (field(word, ADDRESSING_IMM_SHIFT, ADDRESSING_IMM_MASK) << (Integer.SIZE - ADDRESSING_IMM_BITS))
                >> (Integer.SIZE - ADDRESSING_IMM_BITS);
        if ((word & ADDVL_MASK) == ADDVL_VALUE) {
            return new SveIntegerOp64.Address(SveIntegerOp64.Address.Op.ADDVL, rd, stackBase, 0, imm, 0, address);
        }
        if ((word & ADDVL_MASK) == ADDPL_VALUE) {
            return new SveIntegerOp64.Address(SveIntegerOp64.Address.Op.ADDPL, rd, stackBase, 0, imm, 0, address);
        }
        if ((word & RDVL_MASK) == RDVL_VALUE) {
            return new SveIntegerOp64.Address(SveIntegerOp64.Address.Op.RDVL, rd, 0, 0, imm, 0, address);
        }
        Ir64Op streaming = decodeStreamingAddressing(word, address);
        if (streaming != null) {
            return streaming;
        }
        if ((word & ADR_MASK) == ADR_VALUE) {
            SveIntegerOp64.Address.Op op = switch (field(word, ESZ_SHIFT, ESZ_MASK)) {
                case ADR_OPCODE_S32 -> SveIntegerOp64.Address.Op.ADR_S32;
                case ADR_OPCODE_U32 -> SveIntegerOp64.Address.Op.ADR_U32;
                case ADR_OPCODE_P32 -> SveIntegerOp64.Address.Op.ADR_P32;
                default -> SveIntegerOp64.Address.Op.ADR_P64;
            };
            return new SveIntegerOp64.Address(op, rd, rn, field(word, RM_SHIFT, REGISTER_FIELD_MASK), 0,
                    field(word, ADR_MSZ_SHIFT, ADR_MSZ_MASK), address);
        }
        return null;
    }

    /// `ADDSVL`/`ADDSPL`/`RDSVL`: pertencem à `FEAT_SME` (não exigem `FEAT_SVE`), então {@link #decode} as tenta
    /// mesmo quando a arquitetura não declara SVE.
    private Ir64Op decodeStreamingAddressing(int word, long address) {
        if (!architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION)) {
            return null;
        }
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int stackBase = field(word, RM_SHIFT, REGISTER_FIELD_MASK);
        int imm = (field(word, ADDRESSING_IMM_SHIFT, ADDRESSING_IMM_MASK) << (Integer.SIZE - ADDRESSING_IMM_BITS))
                >> (Integer.SIZE - ADDRESSING_IMM_BITS);
        if ((word & ADDVL_MASK) == ADDSVL_VALUE) {
            return new SveIntegerOp64.Address(SveIntegerOp64.Address.Op.ADDSVL, rd, stackBase, 0, imm, 0, address);
        }
        if ((word & ADDVL_MASK) == ADDSPL_VALUE) {
            return new SveIntegerOp64.Address(SveIntegerOp64.Address.Op.ADDSPL, rd, stackBase, 0, imm, 0, address);
        }
        if ((word & RDVL_MASK) == RDSVL_VALUE) {
            return new SveIntegerOp64.Address(SveIntegerOp64.Address.Op.RDSVL, rd, 0, 0, imm, 0, address);
        }
        return null;
    }

    /// Grupo inteiro sem predicado da B17.5 (34 encodings). Devolve `null` para o que não reconhece
    /// (o chamador então tenta a contagem de elementos e, por fim, recusa — G8).
    private Ir64Op decodeIntegerUnpredicated(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int rn = field(word, PN_SHIFT, REGISTER_FIELD_MASK);
        int rm = field(word, RM_SHIFT, REGISTER_FIELD_MASK);
        if ((word & MULTIPLY_ADD_MASK) == MULTIPLY_ADD_BASE) {
            return decodeMultiplyAdd(word, address, esz, rd, rn, rm);
        }
        if ((word & MOVPRFX_MASK) == MOVPRFX_VALUE) {
            return integer(SveIntegerOp64.IntegerUnpredicated.Op.MOVPRFX, 0, rd, rn, 0, 0, 0, 0, 0, address);
        }
        if ((word & FEXPA_MASK) == FEXPA_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : integer(SveIntegerOp64.IntegerUnpredicated.Op.FEXPA, esz, rd, rn, 0, 0, 0, 0, 0, address);
        }
        // Daqui em diante bit 21 = 1: com bit 21 = 0 a palavra já foi consumida (ou recusada) pelo teste do MLA acima.
        int opcode = field(word, OPCODE_SHIFT, OPCODE_FIELD_MASK);
        SveIntegerOp64.IntegerUnpredicated.Op arithmetic = switch (opcode) {
            case OP_ADD -> SveIntegerOp64.IntegerUnpredicated.Op.ADD;
            case OP_SUB -> SveIntegerOp64.IntegerUnpredicated.Op.SUB;
            case OP_SQADD -> SveIntegerOp64.IntegerUnpredicated.Op.SQADD;
            case OP_UQADD -> SveIntegerOp64.IntegerUnpredicated.Op.UQADD;
            case OP_SQSUB -> SveIntegerOp64.IntegerUnpredicated.Op.SQSUB;
            case OP_UQSUB -> SveIntegerOp64.IntegerUnpredicated.Op.UQSUB;
            default -> null;
        };
        if (arithmetic != null) {
            return integer(arithmetic, esz, rd, rn, rm, 0, 0, 0, 0, address);
        }
        return switch (opcode) {
            case OP_LOGICAL -> integer(switch (esz) {
                case 0 -> SveIntegerOp64.IntegerUnpredicated.Op.AND;
                case 1 -> SveIntegerOp64.IntegerUnpredicated.Op.ORR;
                case 2 -> SveIntegerOp64.IntegerUnpredicated.Op.EOR;
                default -> SveIntegerOp64.IntegerUnpredicated.Op.BIC;
            }, 0, rd, rn, rm, 0, 0, 0, 0, address);
            case OP_XAR -> decodeXar(word, address, rd);
            case OP_TERNARY_EOR3_BCAX -> decodeTernary(esz, rd, rm, word, address, false);
            case OP_TERNARY_BSL -> decodeTernary(esz, rd, rm, word, address, true);
            case OP_SHIFT_IMM_ASR ->
                    decodeShiftImmediate(SveIntegerOp64.IntegerUnpredicated.Op.ASR_IMM, word, address, false);
            case OP_SHIFT_IMM_LSR ->
                    decodeShiftImmediate(SveIntegerOp64.IntegerUnpredicated.Op.LSR_IMM, word, address, false);
            case OP_SHIFT_IMM_LSL ->
                    decodeShiftImmediate(SveIntegerOp64.IntegerUnpredicated.Op.LSL_IMM, word, address, true);
            case OP_SHIFT_WIDE_ASR -> wideShift(SveIntegerOp64.IntegerUnpredicated.Op.ASR_WIDE, esz, rd, rn, rm, address);
            case OP_SHIFT_WIDE_LSR -> wideShift(SveIntegerOp64.IntegerUnpredicated.Op.LSR_WIDE, esz, rd, rn, rm, address);
            case OP_SHIFT_WIDE_LSL -> wideShift(SveIntegerOp64.IntegerUnpredicated.Op.LSL_WIDE, esz, rd, rn, rm, address);
            case OP_INDEX_II -> integer(SveIntegerOp64.IntegerUnpredicated.Op.INDEX_II, esz, rd, 0, 0, 0, 0,
                    signedImmediate(word, INDEX_IMM_LOW_SHIFT), signedImmediate(word, INDEX_IMM_HIGH_SHIFT), address);
            case OP_INDEX_IR -> integer(SveIntegerOp64.IntegerUnpredicated.Op.INDEX_IR, esz, rd, 0, rm, 0, 0,
                    signedImmediate(word, INDEX_IMM_LOW_SHIFT), 0, address);
            case OP_INDEX_RI -> integer(SveIntegerOp64.IntegerUnpredicated.Op.INDEX_RI, esz, rd, rn, 0, 0, 0,
                    signedImmediate(word, INDEX_IMM_HIGH_SHIFT), 0, address);
            case OP_INDEX_RR ->
                    integer(SveIntegerOp64.IntegerUnpredicated.Op.INDEX_RR, esz, rd, rn, rm, 0, 0, 0, 0, address);
            case OP_FTSSEL -> esz == RESERVED_ESZ_BYTE
                    ? null
                    : integer(SveIntegerOp64.IntegerUnpredicated.Op.FTSSEL, esz, rd, rn, rm, 0, 0, 0, 0, address);
            default -> null;
        };
    }

    /// Grupo inteiro predicado da B17.6 (68 encodings): aritmética binária (`bits[15:13] = 000`), shifts
    /// (`100`) e unárias (`101`) e, desde a B17.7, as reduções (`001`), todos com `bit 21 = 0`. Devolve `null` para o que não reconhece — e para
    /// os buracos que o `decodetree` deixa passar mas o tradutor do QEMU recusa (G8).
    private Ir64Op decodeIntegerPredicated(int word, long address) {
        if (bit(word, BIT_PREDICATED_EXCLUDED)) {
            return null;
        }
        int opcode = field(word, PREDICATED_OPCODE_SHIFT, PREDICATED_OPCODE_MASK);
        return switch (field(word, PREDICATED_GROUP_SHIFT, PREDICATED_GROUP_MASK)) {
            case PREDICATED_GROUP_BINARY -> decodePredicatedBinary(word, address, opcode);
            case PREDICATED_GROUP_SHIFT_OPS -> decodePredicatedShift(word, address, opcode);
            case PREDICATED_GROUP_REDUCTION -> decodeReduction(word, address, opcode);
            // `bits[15:13]` = `010`/`011`/`110`/`111` (MLA/MLS/MAD/MSB) já foram consumidos por
            // `decodeIntegerUnpredicated` (as 4 estão alocadas com `bit 21 = 0`), então só `101` chega aqui.
            default -> decodePredicatedUnary(word, address, opcode);
        };
    }

    /// Grupo de redução (`bits[15:13] = 001`, 19 encodings): 9 reduções escalares, 8 por segmento (`FEAT_SVE2p1`) e
    /// os 2 `MOVPRFX` predicados — que escrevem `Zd`, não `Vd`, e por isso viram {@link SveIntegerOp64.IntegerPredicated}.
    private Ir64Op decodeReduction(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int rn = field(word, RN_PREDICATED_SHIFT, REGISTER_FIELD_MASK);
        int pg = field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK);
        if (opcode == RED_MOVPRFX_Z || opcode == RED_MOVPRFX_M) {
            return new SveIntegerOp64.IntegerPredicated(SveIntegerOp64.IntegerPredicated.Op.MOVPRFX, esz, rd, rn, 0, pg, 0,
                    opcode == RED_MOVPRFX_Z, address);
        }
        SveIntegerOp64.IntegerReduction.Op op = switch (opcode) {
            case RED_ORV -> SveIntegerOp64.IntegerReduction.Op.ORV;
            case RED_EORV -> SveIntegerOp64.IntegerReduction.Op.EORV;
            case RED_ANDV -> SveIntegerOp64.IntegerReduction.Op.ANDV;
            case RED_SADDV -> SveIntegerOp64.IntegerReduction.Op.SADDV;
            case RED_UADDV -> SveIntegerOp64.IntegerReduction.Op.UADDV;
            case RED_SMAXV -> SveIntegerOp64.IntegerReduction.Op.SMAXV;
            case RED_UMAXV -> SveIntegerOp64.IntegerReduction.Op.UMAXV;
            case RED_SMINV -> SveIntegerOp64.IntegerReduction.Op.SMINV;
            case RED_UMINV -> SveIntegerOp64.IntegerReduction.Op.UMINV;
            case RED_ORQV -> SveIntegerOp64.IntegerReduction.Op.ORQV;
            case RED_EORQV -> SveIntegerOp64.IntegerReduction.Op.EORQV;
            case RED_ANDQV -> SveIntegerOp64.IntegerReduction.Op.ANDQV;
            case RED_ADDQV -> SveIntegerOp64.IntegerReduction.Op.ADDQV;
            case RED_SMAXQV -> SveIntegerOp64.IntegerReduction.Op.SMAXQV;
            case RED_UMAXQV -> SveIntegerOp64.IntegerReduction.Op.UMAXQV;
            case RED_SMINQV -> SveIntegerOp64.IntegerReduction.Op.SMINQV;
            case RED_UMINQV -> SveIntegerOp64.IntegerReduction.Op.UMINQV;
            default -> null;
        };
        if (op == null || op == SveIntegerOp64.IntegerReduction.Op.SADDV && esz == ESZ_DOUBLEWORD) {
            return null; // SADDV exige esz != 3 (não há como alargar a soma com sinal além de 64 bits)
        }
        if (isSegmentReduction(op) && !architecture.has(Aarch64Feature.SVE2_1)
                && !architecture.has(Aarch64Feature.SVE2_2)) {
            return null; // `aa64_sme2p1_or_sve2p1`; SVE2p2 implica SVE2p1
        }
        return new SveIntegerOp64.IntegerReduction(op, esz, rd, rn, pg, address);
    }

    private static boolean isSegmentReduction(SveIntegerOp64.IntegerReduction.Op op) {
        return switch (op) {
            case ORQV, EORQV, ANDQV, ADDQV, SMAXQV, UMAXQV, SMINQV, UMINQV -> true;
            default -> false;
        };
    }

    /// Binárias `_zpzz`. As formas reversas (`SUBR`/`SDIVR`/`UDIVR`) usam o mesmo `Op` com `rn`/`rm`
    /// trocados (`@rdm_pg_rn`): o campo 9:5 é o PRIMEIRO operando e `Zdn` o segundo.
    private Ir64Op decodePredicatedBinary(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        boolean reverse = opcode == PRED_SUBR || opcode == PRED_SDIVR || opcode == PRED_UDIVR;
        SveIntegerOp64.IntegerPredicated.Op op = switch (opcode) {
            case PRED_ORR -> SveIntegerOp64.IntegerPredicated.Op.ORR;
            case PRED_EOR -> SveIntegerOp64.IntegerPredicated.Op.EOR;
            case PRED_AND -> SveIntegerOp64.IntegerPredicated.Op.AND;
            case PRED_BIC -> SveIntegerOp64.IntegerPredicated.Op.BIC;
            case PRED_ADD -> SveIntegerOp64.IntegerPredicated.Op.ADD;
            case PRED_SUB, PRED_SUBR -> SveIntegerOp64.IntegerPredicated.Op.SUB;
            case PRED_SMAX -> SveIntegerOp64.IntegerPredicated.Op.SMAX;
            case PRED_UMAX -> SveIntegerOp64.IntegerPredicated.Op.UMAX;
            case PRED_SMIN -> SveIntegerOp64.IntegerPredicated.Op.SMIN;
            case PRED_UMIN -> SveIntegerOp64.IntegerPredicated.Op.UMIN;
            case PRED_SABD -> SveIntegerOp64.IntegerPredicated.Op.SABD;
            case PRED_UABD -> SveIntegerOp64.IntegerPredicated.Op.UABD;
            case PRED_MUL -> SveIntegerOp64.IntegerPredicated.Op.MUL;
            case PRED_SMULH -> SveIntegerOp64.IntegerPredicated.Op.SMULH;
            case PRED_UMULH -> SveIntegerOp64.IntegerPredicated.Op.UMULH;
            case PRED_SDIV, PRED_SDIVR -> SveIntegerOp64.IntegerPredicated.Op.SDIV;
            case PRED_UDIV, PRED_UDIVR -> SveIntegerOp64.IntegerPredicated.Op.UDIV;
            default -> null;
        };
        boolean divide = op == SveIntegerOp64.IntegerPredicated.Op.SDIV || op == SveIntegerOp64.IntegerPredicated.Op.UDIV;
        if (op == null || (divide && esz < ESZ_WORD)) {
            return null; // divisão exige esz >= 2; abaixo disso é não alocado
        }
        return predicated(op, esz, word, address, reverse, 0, false);
    }

    private Ir64Op decodePredicatedShift(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        SveIntegerOp64.IntegerPredicated.Op op = switch (opcode) {
            case PRED_SHIFT_ASR_IMM -> SveIntegerOp64.IntegerPredicated.Op.ASR_IMM;
            case PRED_SHIFT_LSR_IMM -> SveIntegerOp64.IntegerPredicated.Op.LSR_IMM;
            case PRED_SHIFT_LSL_IMM -> SveIntegerOp64.IntegerPredicated.Op.LSL_IMM;
            case PRED_SHIFT_ASRD -> SveIntegerOp64.IntegerPredicated.Op.ASRD;
            case PRED_SHIFT_SQSHL_IMM -> SveIntegerOp64.IntegerPredicated.Op.SQSHL_IMM;
            case PRED_SHIFT_UQSHL_IMM -> SveIntegerOp64.IntegerPredicated.Op.UQSHL_IMM;
            case PRED_SHIFT_SRSHR -> SveIntegerOp64.IntegerPredicated.Op.SRSHR;
            case PRED_SHIFT_URSHR -> SveIntegerOp64.IntegerPredicated.Op.URSHR;
            case PRED_SHIFT_SQSHLU -> SveIntegerOp64.IntegerPredicated.Op.SQSHLU;
            case PRED_SHIFT_ASR, PRED_SHIFT_ASRR -> SveIntegerOp64.IntegerPredicated.Op.ASR;
            case PRED_SHIFT_LSR, PRED_SHIFT_LSRR -> SveIntegerOp64.IntegerPredicated.Op.LSR;
            case PRED_SHIFT_LSL, PRED_SHIFT_LSLR -> SveIntegerOp64.IntegerPredicated.Op.LSL;
            case PRED_SHIFT_ASR_WIDE -> SveIntegerOp64.IntegerPredicated.Op.ASR_WIDE;
            case PRED_SHIFT_LSR_WIDE -> SveIntegerOp64.IntegerPredicated.Op.LSR_WIDE;
            case PRED_SHIFT_LSL_WIDE -> SveIntegerOp64.IntegerPredicated.Op.LSL_WIDE;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        switch (op) {
            case ASR_IMM, LSR_IMM, LSL_IMM, ASRD, SRSHR, URSHR, SQSHL_IMM, UQSHL_IMM, SQSHLU -> {
                boolean sve2Only = op == SveIntegerOp64.IntegerPredicated.Op.SQSHL_IMM
                        || op == SveIntegerOp64.IntegerPredicated.Op.UQSHL_IMM
                        || op == SveIntegerOp64.IntegerPredicated.Op.SRSHR
                        || op == SveIntegerOp64.IntegerPredicated.Op.URSHR
                        || op == SveIntegerOp64.IntegerPredicated.Op.SQSHLU;
                if (sve2Only && !architecture.has(Aarch64Feature.SVE2)) {
                    return null;
                }
                int tszimm = tszimm(word, TSZ_PREDICATED_LOW_SHIFT);
                int immEsz = tszimmEsz(tszimm);
                if (immEsz < 0) {
                    return null; // tsz = 0: não alocado
                }
                boolean left = op == SveIntegerOp64.IntegerPredicated.Op.LSL_IMM
                        || op == SveIntegerOp64.IntegerPredicated.Op.SQSHL_IMM
                        || op == SveIntegerOp64.IntegerPredicated.Op.UQSHL_IMM
                        || op == SveIntegerOp64.IntegerPredicated.Op.SQSHLU;
                long amount = left ? tszimm - (ESIZE_BITS_BASE << immEsz) : (ESIZE_SHR_BASE << immEsz) - tszimm;
                return predicatedImmediate(op, immEsz, word, address, amount);
            }
            case ASR_WIDE, LSR_WIDE, LSL_WIDE -> {
                return esz == ESZ_DOUBLEWORD ? null : predicated(op, esz, word, address, false, 0, false);
            }
            default -> {
                boolean reverse = opcode == PRED_SHIFT_ASRR || opcode == PRED_SHIFT_LSRR || opcode == PRED_SHIFT_LSLR;
                return predicated(op, esz, word, address, reverse, 0, false);
            }
        }
    }

    /// Unárias predicadas: `bit 20` = 1 é a forma merging (`_m`), 0 a forma zeroing (`_z`, `FEAT_SVE2p2`);
    /// `bit 19` escolhe entre as bit-ops (`CLS`…`NOT`) e as inteiras (`SXTB`…`NEG`).
    private Ir64Op decodePredicatedUnary(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        boolean zeroing = ((opcode >>> UNARY_MERGING_BIT) & 1) == 0;
        if (zeroing && !architecture.has(Aarch64Feature.SVE2_2)) {
            return null;
        }
        int selector = opcode & UNARY_SELECTOR_MASK;
        boolean bitOperations = ((opcode >>> UNARY_BIT_OPERATIONS) & 1) != 0;
        SveIntegerOp64.IntegerPredicated.Op op = bitOperations ? switch (selector) {
            case 0 -> SveIntegerOp64.IntegerPredicated.Op.CLS;
            case 1 -> SveIntegerOp64.IntegerPredicated.Op.CLZ;
            case 2 -> SveIntegerOp64.IntegerPredicated.Op.CNT;
            case 3 -> SveIntegerOp64.IntegerPredicated.Op.CNOT;
            case 4 -> SveIntegerOp64.IntegerPredicated.Op.FABS;
            case 5 -> SveIntegerOp64.IntegerPredicated.Op.FNEG;
            case 6 -> SveIntegerOp64.IntegerPredicated.Op.NOT;
            default -> null;
        } : switch (selector) {
            case 0 -> SveIntegerOp64.IntegerPredicated.Op.SXTB;
            case 1 -> SveIntegerOp64.IntegerPredicated.Op.UXTB;
            case 2 -> SveIntegerOp64.IntegerPredicated.Op.SXTH;
            case 3 -> SveIntegerOp64.IntegerPredicated.Op.UXTH;
            case 4 -> SveIntegerOp64.IntegerPredicated.Op.SXTW;
            case 5 -> SveIntegerOp64.IntegerPredicated.Op.UXTW;
            case 6 -> SveIntegerOp64.IntegerPredicated.Op.ABS;
            default -> SveIntegerOp64.IntegerPredicated.Op.NEG;
        };
        if (op == null || esz < minimumUnaryEsz(op) || (op == SveIntegerOp64.IntegerPredicated.Op.SXTW
                || op == SveIntegerOp64.IntegerPredicated.Op.UXTW) && esz != ESZ_DOUBLEWORD) {
            return null;
        }
        return new SveIntegerOp64.IntegerPredicated(op, esz, field(word, PD_SHIFT, REGISTER_FIELD_MASK),
                field(word, RN_PREDICATED_SHIFT, REGISTER_FIELD_MASK), 0,
                field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK), 0, zeroing, address);
    }

    /// Menor `esz` permitido de cada unária: as extensões precisam de um elemento maior que a origem
    /// (`SXTB`/`UXTB` ≥ half, `SXTH`/`UXTH` ≥ word) e `FABS`/`FNEG` não existem em bytes.
    private static int minimumUnaryEsz(SveIntegerOp64.IntegerPredicated.Op op) {
        return switch (op) {
            case SXTB, UXTB, FABS, FNEG -> ESZ_HALFWORD;
            case SXTH, UXTH -> ESZ_WORD;
            default -> 0;
        };
    }

    private static Ir64Op predicated(SveIntegerOp64.IntegerPredicated.Op op, int esz, int word, long address,
            boolean reverse, long imm, boolean zeroing) {
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int other = field(word, RN_PREDICATED_SHIFT, REGISTER_FIELD_MASK);
        return new SveIntegerOp64.IntegerPredicated(op, esz, rd, reverse ? other : rd, reverse ? rd : other,
                field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK), imm, zeroing, address);
    }

    private static Ir64Op predicatedImmediate(SveIntegerOp64.IntegerPredicated.Op op, int esz, int word, long address,
            long amount) {
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        return new SveIntegerOp64.IntegerPredicated(op, esz, rd, rd, 0, field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK),
                amount, false, address);
    }

    /// `MLA`/`MLS` (escrevem o acumulador `Zda`) e `MAD`/`MSB` (escrevem o multiplicando `Zdn`), sempre
    /// com predicado: o grupo se chama "não predicado" pela partição do épico, mas estas 4 linhas têm `pg`.
    private Ir64Op decodeMultiplyAdd(int word, long address, int esz, int rd, int rn, int rm) {
        int pg = field(word, PG_MULTIPLY_SHIFT, PG_MULTIPLY_MASK);
        return switch (field(word, MULTIPLY_ADD_OPCODE_SHIFT, MULTIPLY_ADD_OPCODE_MASK)) {
            case MULTIPLY_ADD_MLA ->
                    integer(SveIntegerOp64.IntegerUnpredicated.Op.MLA, esz, rd, rn, rm, 0, pg, 0, 0, address);
            case MULTIPLY_ADD_MLS ->
                    integer(SveIntegerOp64.IntegerUnpredicated.Op.MLS, esz, rd, rn, rm, 0, pg, 0, 0, address);
            case MULTIPLY_ADD_MAD ->
                    integer(SveIntegerOp64.IntegerUnpredicated.Op.MAD, esz, rd, rd, rm, rn, pg, 0, 0, address);
            case MULTIPLY_ADD_MSB ->
                    integer(SveIntegerOp64.IntegerUnpredicated.Op.MSB, esz, rd, rd, rm, rn, pg, 0, 0, address);
            default -> null;
        };
    }

    /// Lógica ternária SVE2 (`EOR3`/`BCAX`/`BSL`/`BSL1N`/`BSL2N`/`NBSL`): bits 23:22 escolhem a
    /// operação (não são `esz`), `rn = rd` (destrutiva), `Zk` em 9:5. Exige `FEAT_SVE2`.
    private Ir64Op decodeTernary(int selector, int rd, int rm, int word, long address, boolean bitSelect) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        SveIntegerOp64.IntegerUnpredicated.Op op;
        if (bitSelect) {
            op = switch (selector) {
                case 0 -> SveIntegerOp64.IntegerUnpredicated.Op.BSL;
                case 1 -> SveIntegerOp64.IntegerUnpredicated.Op.BSL1N;
                case 2 -> SveIntegerOp64.IntegerUnpredicated.Op.BSL2N;
                default -> SveIntegerOp64.IntegerUnpredicated.Op.NBSL;
            };
        } else {
            op = switch (selector) {
                case 0 -> SveIntegerOp64.IntegerUnpredicated.Op.EOR3;
                case 1 -> SveIntegerOp64.IntegerUnpredicated.Op.BCAX;
                default -> null;
            };
        }
        return op == null
                ? null
                : integer(op, 0, rd, rd, rm, field(word, RA_SHIFT, REGISTER_FIELD_MASK), 0, 0, 0, address);
    }

    /// `XAR` (SVE2): `Zm` em 9:5, `Zdn` destrutivo, `esz` e a rotação vêm do `tszimm` (bits 23:22 e 20:16).
    private Ir64Op decodeXar(int word, long address, int rd) {
        int tszimm = tszimm(word, TSZ_LOW_SHIFT);
        int esz = tszimmEsz(tszimm);
        if (esz < 0 || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        return integer(SveIntegerOp64.IntegerUnpredicated.Op.XAR, esz, rd, rd, field(word, RA_SHIFT, REGISTER_FIELD_MASK), 0,
                0, (ESIZE_SHR_BASE << esz) - tszimm, 0, address);
    }

    /// `ASR`/`LSR`/`LSL` por imediato: `esz` e a contagem derivam do MESMO campo `tszimm` (não há `size`).
    private static Ir64Op decodeShiftImmediate(SveIntegerOp64.IntegerUnpredicated.Op op, int word, long address,
            boolean left) {
        int tszimm = tszimm(word, TSZ_LOW_SHIFT);
        int esz = tszimmEsz(tszimm);
        if (esz < 0) {
            return null;
        }
        long amount = left ? tszimm - (ESIZE_BITS_BASE << esz) : (ESIZE_SHR_BASE << esz) - tszimm;
        return integer(op, esz, field(word, PD_SHIFT, REGISTER_FIELD_MASK), field(word, PN_SHIFT, REGISTER_FIELD_MASK),
                0, 0, 0, amount, 0, address);
    }

    /// Shift por elemento largo (`_zzw`): `esz = 3` não existe (o `Zm` já é de doublewords).
    private static Ir64Op wideShift(SveIntegerOp64.IntegerUnpredicated.Op op, int esz, int rd, int rn, int rm,
            long address) {
        return esz == ESZ_DOUBLEWORD ? null : integer(op, esz, rd, rn, rm, 0, 0, 0, 0, address);
    }

    /// `tszh:tszl:imm3` de 7 bits: bits 23:22 e o campo baixo de 5 bits em `lowShift` (`%tszimm16_*`: 20:16;
    /// `%tszimm_*` dos shifts predicados: 9:5).
    private static int tszimm(int word, int lowShift) {
        return (field(word, TSZ_HIGH_SHIFT, TSZ_HIGH_MASK) << TSZ_HIGH_FIELD_SHIFT)
                | field(word, lowShift, TSZ_LOW_MASK);
    }

    /// `tszimm_esz` do QEMU: posição do bit mais alto de `tsz` (`-1` quando `tsz = 0`, não alocado).
    private static int tszimmEsz(int tszimm) {
        int tsz = tszimm >>> TSZ_ESZ_SHIFT;
        return tsz == 0 ? -1 : Integer.SIZE - 1 - Integer.numberOfLeadingZeros(tsz);
    }

    private static long signedImmediate(int word, int shift) {
        int raw = field(word, shift, REGISTER_FIELD_MASK);
        return (raw << (Integer.SIZE - SIGNED_IMMEDIATE_BITS)) >> (Integer.SIZE - SIGNED_IMMEDIATE_BITS);
    }

    private static Ir64Op integer(SveIntegerOp64.IntegerUnpredicated.Op op, int esz, int rd, int rn, int rm, int ra, int pg,
            long imm, long imm2, long address) {
        return new SveIntegerOp64.IntegerUnpredicated(op, esz, rd, rn, rm, ra, pg, imm, imm2, address);
    }

    private static Ir64Op misc(SvePredicateOp64.PredicateMisc.Op op, int esz, int pd, int pg, int pn, boolean setFlags,
            int pattern, long address) {
        // `PTEST`/`PFIRST`/`PNEXT` usam o argumento `pn` como o predicado lido e `pg` como o
        // governante; os chamadores acima já passaram cada campo na posição certa.
        return new SvePredicateOp64.PredicateMisc(op, esz, pd, pg, pn, setFlags, pattern, address);
    }

    private static Ir64Op count(SvePredicateOp64.PredicateCount.Op op, int esz, int word, long address) {
        return new SvePredicateOp64.PredicateCount(op, esz, field(word, PD_SHIFT, REGISTER_FIELD_MASK),
                field(word, COUNT_PG_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK), false,
                false, address);
    }

    private static Ir64Op incdecp(SvePredicateOp64.PredicateCount.Op op, int esz, int rd, int pg, boolean decrement,
            boolean unsigned, long address) {
        return new SvePredicateOp64.PredicateCount(op, esz, rd, pg, pg, decrement, unsigned, address);
    }

    private static Ir64Op elementCount(SveIntegerOp64.ElementCount.Op op, int esz, int rd, int pattern, int multiplier,
            boolean decrement, boolean unsigned, long address) {
        return new SveIntegerOp64.ElementCount(op, esz, rd, pattern, multiplier, decrement, unsigned, address);
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }
}
