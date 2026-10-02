package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SvePredicateOp64;

/// Decoder das instruções que leem ou escrevem um predicado-COMO-CONTADOR (`PN8`-`PN15`) e não são `WHILE` (B17.28):
/// `PTRUE_cnt`, `CNTP_c`, `PEXT_1`/`PEXT_2` (prefixo `0x25`) e os 16 `LD1`/`ST1` multi-vetor contíguos (`0xA0`/`0xA1`).
/// Os `WHILE_*_cnt*` ficam em {@link Aarch64SveCompareDecoder}, junto dos demais `WHILE`.
///
/// **Cada padrão é o `decodetree` transcrito em `máscara`/`valor`**, conferido contra `aarch64-none-elf-as`. Todo `PNn`
/// é `8 +` um campo de 3 bits: nenhuma destas instruções aceita `P0`-`P7`. Gate por linha: as formas contíguas e as de
/// predicado exigem `FEAT_SVE2p1` **ou** `FEAT_SME2` (o `aa64_sme2_or_sve2p1` do QEMU; sem `SVE2p1` a instrução só existe em
/// modo streaming, o que o executor confere); as quatro `_stride` são SME2 puras e sempre streaming.
///
/// Recusas (G8, `null`): `CNTP_c` com o bit 8 zerado (o campo `rn:4` do `sve.decode` admitiria `P0`-`P7`, mas o manual
/// codifica `PNn` com `bits[9:8] = 11`); `LD1`/`ST1` de 4 registradores `_stride` com o bit 2 do `Zt` ligado (`Zt` deve ser
/// múltiplo de 4 dentro do bloco; o `gen_ldst_c` do QEMU devolve `false`); `LD1`/`ST1` contíguos de 4 registradores com o
/// bit 1 ligado (o `-` do `sve.decode` só cobre o bit 0, o bit não temporal).
final class Aarch64SveCounterDecoder {
    private static final int COUNTER_BASE = 8;
    private static final int COUNTER_FIELD_MASK = 0b111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;

    // ── Prefixo 0x25 ──────────────────────────────────────────────────────────────────────────────
    private static final int PTRUE_MASK = 0xFF3FFFF8;
    private static final int PTRUE_VALUE = 0x25207810;
    private static final int CNTP_MASK = 0xFF3FFB00;
    private static final int CNTP_VALUE = 0x25208300;
    private static final int CNTP_PN_SHIFT = 5;
    private static final int CNTP_VL_BIT = 10;
    private static final int CNTP_RD_MASK = 0b11111;
    private static final int PEXT_1_MASK = 0xFF3FFC10;
    private static final int PEXT_1_VALUE = 0x25207010;
    private static final int PEXT_1_IMM_MASK = 0b11;
    private static final int PEXT_2_MASK = 0xFF3FFE10;
    private static final int PEXT_2_VALUE = 0x25207410;
    private static final int PEXT_2_IMM_MASK = 0b1;
    private static final int PEXT_IMM_SHIFT = 8;
    private static final int PEXT_PN_SHIFT = 5;
    private static final int PEXT_PD_MASK = 0b1111;

    // ── Prefixos 0xA0/0xA1 (identificados pelos bits 31:21 — escalar+escalar — ou 31:20 — escalar+imediato) ─────
    private static final int REGISTER_FORM_SHIFT = 21;
    private static final int IMMEDIATE_FORM_SHIFT = 20;
    private static final int LD1_REGISTER = 0b10100000000;
    private static final int ST1_REGISTER = 0b10100000001;
    private static final int LD1_REGISTER_STRIDE = 0b10100001000;
    private static final int ST1_REGISTER_STRIDE = 0b10100001001;
    private static final int LD1_IMMEDIATE = 0b101000000100;
    private static final int ST1_IMMEDIATE = 0b101000000110;
    private static final int LD1_IMMEDIATE_STRIDE = 0b101000010100;
    private static final int ST1_IMMEDIATE_STRIDE = 0b101000010110;
    private static final int FOUR_REGISTERS_BIT = 15;
    private static final int ESZ_MULTI_SHIFT = 13;
    private static final int PG_SHIFT = 10;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b11111;
    private static final int RM_SHIFT = 16;
    private static final int IMM4_SHIFT = 16;
    private static final int IMM4_MASK = 0b1111;
    private static final int IMM4_BITS = 4;
    private static final int ZT_TWO_SHIFT = 1;
    private static final int ZT_TWO_FIELD_MASK = 0b1111;
    private static final int ZT_FOUR_SHIFT = 2;
    private static final int ZT_FOUR_FIELD_MASK = 0b111;
    private static final int CONTIGUOUS_FOUR_RESERVED_BIT = 0b10;
    private static final int NON_TEMPORAL_BIT = 0b01000;
    private static final int STRIDED_FOUR_MISALIGNED_BIT = 0b00100;
    private static final int STRIDE_TWO_REGISTERS = 8;
    private static final int STRIDE_FOUR_REGISTERS = 4;
    private static final int TWO_REGISTERS = 2;
    private static final int FOUR_REGISTERS = 4;

    private final Aarch64Architecture architecture;

    Aarch64SveCounterDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// `FEAT_SVE2p1` ou `FEAT_SME2` (o `aa64_sme2_or_sve2p1` do QEMU).
    private boolean hasCounterFeature() {
        return architecture.has(Aarch64Feature.SVE2_1) || architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    }

    /// Só `FEAT_SME2`, sem `FEAT_SVE2p1`: a instrução existe apenas em modo streaming.
    private boolean streamingOnly() {
        return !architecture.has(Aarch64Feature.SVE2_1);
    }

    /// Prefixo `0x25`: `PTRUE_cnt`, `CNTP_c`, `PEXT_1`, `PEXT_2`. `null` = não é deste grupo.
    Ir64Op decodePrefix25(int word, long address) {
        if (!hasCounterFeature()) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if ((word & PTRUE_MASK) == PTRUE_VALUE) {
            return new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.PTRUE, esz, counter(word, 0), 0, 0, 0,
                    false, address);
        }
        if ((word & CNTP_MASK) == CNTP_VALUE) {
            int lg2Vectors = ((word >>> CNTP_VL_BIT) & 1) + 1;
            return new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.CNTP, esz, 0,
                    counter(word, CNTP_PN_SHIFT), word & CNTP_RD_MASK, lg2Vectors, streamingOnly(), address);
        }
        if ((word & PEXT_1_MASK) == PEXT_1_VALUE) {
            return pext(SvePredicateOp64.CounterPredicate.Op.PEXT_1, esz, word,
                    (word >>> PEXT_IMM_SHIFT) & PEXT_1_IMM_MASK, address);
        }
        if ((word & PEXT_2_MASK) == PEXT_2_VALUE) {
            return pext(SvePredicateOp64.CounterPredicate.Op.PEXT_2, esz, word,
                    (word >>> PEXT_IMM_SHIFT) & PEXT_2_IMM_MASK, address);
        }
        return null;
    }

    private static Ir64Op pext(SvePredicateOp64.CounterPredicate.Op op, int esz, int word, int index, long address) {
        return new SvePredicateOp64.CounterPredicate(op, esz, word & PEXT_PD_MASK, counter(word, PEXT_PN_SHIFT), 0, index, false,
                address);
    }

    /// O índice `8`-`15` do `PNn` cujo campo de 3 bits começa em `shift` (`%pnd`/`%pnn`/`%png`: `8 +` o campo).
    private static int counter(int word, int shift) {
        return COUNTER_BASE + ((word >>> shift) & COUNTER_FIELD_MASK);
    }

    /// Prefixos `0xA0`/`0xA1`: os 16 `LD1`/`ST1` multi-vetor (classe `bits[28:26] = 000` do A64, a mesma das instruções de
    /// SME). `null` = não é deste grupo, ou é uma forma recusada.
    Ir64Op decodeMultiVector(int word, long address) {
        if (!hasCounterFeature()) {
            return null;
        }
        int registerForm = word >>> REGISTER_FORM_SHIFT;
        int immediateForm = word >>> IMMEDIATE_FORM_SHIFT;
        boolean registerOffset = true;
        boolean store;
        boolean strided;
        if (registerForm == LD1_REGISTER || registerForm == ST1_REGISTER) {
            store = registerForm == ST1_REGISTER;
            strided = false;
        } else if (registerForm == LD1_REGISTER_STRIDE || registerForm == ST1_REGISTER_STRIDE) {
            store = registerForm == ST1_REGISTER_STRIDE;
            strided = true;
        } else if (immediateForm == LD1_IMMEDIATE || immediateForm == ST1_IMMEDIATE) {
            registerOffset = false;
            store = immediateForm == ST1_IMMEDIATE;
            strided = false;
        } else if (immediateForm == LD1_IMMEDIATE_STRIDE || immediateForm == ST1_IMMEDIATE_STRIDE) {
            registerOffset = false;
            store = immediateForm == ST1_IMMEDIATE_STRIDE;
            strided = true;
        } else {
            return null;
        }
        if (strided && !architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2)) {
            return null;
        }
        boolean four = ((word >>> FOUR_REGISTERS_BIT) & 1) != 0;
        int registers = four ? FOUR_REGISTERS : TWO_REGISTERS;
        int rt;
        int registerStride;
        if (strided) {
            rt = word & REGISTER_MASK;
            if (four && (rt & STRIDED_FOUR_MISALIGNED_BIT) != 0) {
                return null;
            }
            rt &= ~NON_TEMPORAL_BIT; // o bit 3 é o hint não temporal, sem modelo de cache
            registerStride = four ? STRIDE_FOUR_REGISTERS : STRIDE_TWO_REGISTERS;
        } else if (four) {
            if ((word & CONTIGUOUS_FOUR_RESERVED_BIT) != 0) {
                return null;
            }
            rt = ((word >>> ZT_FOUR_SHIFT) & ZT_FOUR_FIELD_MASK) * FOUR_REGISTERS;
            registerStride = 1;
        } else {
            rt = ((word >>> ZT_TWO_SHIFT) & ZT_TWO_FIELD_MASK) * TWO_REGISTERS;
            registerStride = 1;
        }
        long immediate = ((long) ((word >>> IMM4_SHIFT) & IMM4_MASK) << (Long.SIZE - IMM4_BITS)) >> (Long.SIZE - IMM4_BITS);
        return new SveMemoryOp64.MultiVectorMemory(store, (word >>> ESZ_MULTI_SHIFT) & ESZ_MASK, registers, rt,
                registerStride, counter(word, PG_SHIFT), (word >>> RN_SHIFT) & REGISTER_MASK,
                registerOffset ? (word >>> RM_SHIFT) & REGISTER_MASK : 0, registerOffset,
                registerOffset ? 0 : immediate, strided || streamingOnly(), address);
    }
}
