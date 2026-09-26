package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SveLoad.Op;

/// Decoder SVE dos loads contíguos da B17.17, nos prefixos `0x84`/`0x85` (`LDR` de predicado e de vetor, `LD1R*`, os
/// quatro `PRF*`) e `0xA4`/`0xA5` (`### SVE Memory Contiguous Load Group` inteiro do `sve.decode` do QEMU: `LD1*`,
/// `LD[234]*`, `LDNT1*`, `LDFF1*`, `LDNF1*`, `LD1RQ*`, `LD1RO*`, e as formas de elemento de 128 bits). Cada padrão é o
/// `decodetree` transcrito em `máscara`/`valor` e conferido contra `aarch64-none-elf-as`.
///
/// **A tabela `dtype` foi transcrita do QEMU (`dtype_mop`/`dtype_esz`), nunca derivada**: os 16 valores dão
/// (tamanho do acesso, tamanho do elemento, extensão) e as 16 combinações são todas válidas; os valores artificiais
/// `16`/`17`/`18` (elemento de 128 bits) não vêm do campo — vêm do padrão do encoding. `dtype = 16`/`17` são `LD1W`/`LD1D`
/// com elemento `.Q` (`FEAT_SVE2p1`, ilegais em modo streaming) e `dtype = 18` é `LD[234]Q` (`FEAT_SVE2p1`; o QEMU também
/// aceita `FEAT_SME2p1`, que este projeto ainda não tem como feature).
///
/// Recusas (G8, devolvem `null`): `Rm = 31` em toda forma escalar+escalar exceto `LDFF1` (lá `31` é `XZR`); `LD1RO` sem
/// `FEAT_F64MM`; os buracos do `decodetree` (por exemplo `bits[22:21] = 1x` em `LD1RQ`/`LD1RO`).
final class Aarch64SveLoadDecoder {
    private static final int PREFIX_SHIFT = 25;
    private static final int PREFIX_CONTIGUOUS_LOADS = 0b1010010;
    private static final int PREFIX_UNSIZED = 0b1000010;

    private static final int REGISTER_MASK = 0b11111;
    private static final int PREDICATE_MASK = 0b111;
    private static final int PD_MASK = 0b1111;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int RM_SHIFT = 16;
    private static final int OPCODE_SHIFT = 13;
    private static final int OPCODE_MASK = 0b111;
    private static final int DTYPE_SHIFT = 21;
    private static final int DTYPE_MASK = 0b1111;
    private static final int MSZ_SHIFT = 23;
    private static final int MSZ_MASK = 0b11;
    private static final int NREG_SHIFT = 21;
    private static final int NREG_MASK = 0b11;
    private static final int SIGNED_IMMEDIATE_BITS = 4;
    private static final int IMM4_SHIFT = 16;
    private static final int IMM4_MASK = 0b1111;
    private static final int BIT_NON_FAULT = 20;
    private static final int RM_UNALLOCATED = 31;
    private static final int RM_MASK = 0b11111;

    // Bits 15:13 do grupo `0xA4`/`0xA5`.
    private static final int OP_BROADCAST_QUADWORD_REGISTER = 0b000;
    private static final int OP_IMMEDIATE_SPECIAL = 0b001;
    private static final int OP_LOAD_REGISTER = 0b010;
    private static final int OP_FIRST_FAULT = 0b011;
    private static final int OP_REGISTER_SPECIAL = 0b100;
    private static final int OP_LOAD_IMMEDIATE = 0b101;
    private static final int OP_STRUCTURE_REGISTER = 0b110;
    private static final int OP_STRUCTURE_IMMEDIATE = 0b111;

    // Bits 22:21 (`sel`) dos grupos de quadword/octaword.
    private static final int SEL_QUADWORD = 0b00;
    private static final int SEL_OCTAWORD = 0b01;
    // Bits 24:21 das formas artificiais de elemento de 128 bits (`dtype` 16/17).
    private static final int TOP_WORD_QUAD_ELEMENT = 0b1000;
    private static final int TOP_DOUBLE_QUAD_ELEMENT = 0b1100;
    private static final int SEL_STRUCTURE_QUAD = 0b01;

    // `dtype` artificiais (elemento de 128 bits).
    private static final int MSZ_WORD = 2;
    private static final int MSZ_DOUBLE = 3;
    private static final int MSZ_QUAD = 4;
    private static final int ESZ_QUAD = 4;

    // `LDR`/`PRF` no prefixo `0x84`/`0x85`.
    private static final int LDR_MASK = 0xFFC0E000;
    private static final int LDR_PREDICATE_VALUE = 0x85800000;
    private static final int LDR_VECTOR_VALUE = 0x85804000;
    private static final int LDR_PREDICATE_BIT4_MASK = 0x10;
    private static final int LDR_IMM_HIGH_SHIFT = 16;
    private static final int LDR_IMM_HIGH_MASK = 0b111111;
    private static final int LDR_IMM_LOW_SHIFT = 10;
    private static final int LDR_IMM_LOW_MASK = 0b111;
    private static final int LDR_IMM_LOW_BITS = 3;
    private static final int LDR_IMMEDIATE_BITS = 9;
    private static final int LD1R_MASK = 0xFE408000;
    private static final int LD1R_VALUE = 0x84408000;
    private static final int LD1R_DTYPE_HIGH_SHIFT = 23;
    private static final int LD1R_DTYPE_LOW_SHIFT = 13;
    private static final int LD1R_DTYPE_FIELD_MASK = 0b11;
    private static final int LD1R_DTYPE_HIGH_TO_BITS = 2;
    private static final int LD1R_IMM_MASK = 0b111111;
    private static final int PRF_NS_GATHER_MASK = 0xFFA08010;
    private static final int PRF_NS_GATHER_VALUE = 0x84200000;
    private static final int PRF_NS_VECTOR_MASK = 0xFE60E010;
    private static final int PRF_NS_VECTOR_VALUE = 0x8400E000;
    private static final int PRF_MASK = 0xFFC08010;
    private static final int PRF_VALUE = 0x85C00000;
    private static final int PRF_RR_MASK = 0xFE60E010;
    private static final int PRF_RR_VALUE = 0x8400C000;

    /// `msz` de cada `dtype` `0`-`15` (`dtype_mop & MO_SIZE`).
    private static final int[] DTYPE_MSZ = {0, 0, 0, 0, 2, 1, 1, 1, 1, 1, 2, 2, 0, 0, 0, 3};
    /// `esz` de cada `dtype` `0`-`15` (`dtype_esz`).
    private static final int[] DTYPE_ESZ = {0, 1, 2, 3, 3, 1, 2, 3, 3, 2, 2, 3, 3, 2, 1, 3};
    /// Extensão com sinal de cada `dtype` `0`-`15` (`MO_SL`/`MO_SW`/`MO_SB` em `dtype_mop`).
    private static final boolean[] DTYPE_SIGNED = {
        false, false, false, false, true, false, false, false,
        true, true, false, false, true, true, true, false};
    /// `msz_dtype` do QEMU para `msz` `0`-`3`: o `dtype` sem extensão de tamanho `msz` no próprio tamanho.
    private static final int[] MSZ_TO_DTYPE = {0, 5, 10, 15};

    private final Aarch64Architecture architecture;

    Aarch64SveLoadDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra dos prefixos `0x84`/`0x85`/`0xA4`/`0xA5`. `null` = não é deste grupo (ou é um encoding
    /// recusado).
    Ir64Op decode(int word, long address) {
        return switch (word >>> PREFIX_SHIFT) {
            case PREFIX_CONTIGUOUS_LOADS -> decodeContiguous(word, address);
            case PREFIX_UNSIZED -> decodeUnsized(word, address);
            default -> null;
        };
    }

    // ── Prefixo 0x84/0x85 ────────────────────────────────────────────────────────────────────────

    private Ir64Op decodeUnsized(int word, long address) {
        if ((word & LDR_MASK) == LDR_VECTOR_VALUE) {
            return ldr(Op.LDR_Z, word, word & REGISTER_MASK, address);
        }
        if ((word & LDR_MASK) == LDR_PREDICATE_VALUE && (word & LDR_PREDICATE_BIT4_MASK) == 0) {
            return ldr(Op.LDR_P, word, word & PD_MASK, address);
        }
        if ((word & LD1R_MASK) == LD1R_VALUE) {
            int dtype = ((word >>> LD1R_DTYPE_HIGH_SHIFT) & LD1R_DTYPE_FIELD_MASK) << LD1R_DTYPE_HIGH_TO_BITS
                    | (word >>> LD1R_DTYPE_LOW_SHIFT) & LD1R_DTYPE_FIELD_MASK;
            return new Ir64Op.SveLoad(Op.LD1R, DTYPE_MSZ[dtype], DTYPE_ESZ[dtype], DTYPE_SIGNED[dtype], 0,
                    word & REGISTER_MASK, (word >>> RN_SHIFT) & REGISTER_MASK, 0, false,
                    (word >>> RM_SHIFT) & LD1R_IMM_MASK, (word >>> PG_SHIFT) & PREDICATE_MASK, false, address);
        }
        if ((word & PRF_NS_GATHER_MASK) == PRF_NS_GATHER_VALUE || (word & PRF_NS_VECTOR_MASK) == PRF_NS_VECTOR_VALUE) {
            return prefetch(true, word, address);
        }
        if ((word & PRF_MASK) == PRF_VALUE) {
            return prefetch(false, word, address);
        }
        if ((word & PRF_RR_MASK) == PRF_RR_VALUE && ((word >>> RM_SHIFT) & RM_MASK) != RM_UNALLOCATED) {
            return prefetch(false, word, address);
        }
        return null;
    }

    private static Ir64Op ldr(Op op, int word, int rd, long address) {
        int high = (word >>> LDR_IMM_HIGH_SHIFT) & LDR_IMM_HIGH_MASK;
        int low = (word >>> LDR_IMM_LOW_SHIFT) & LDR_IMM_LOW_MASK;
        long immediate = signExtend(high << LDR_IMM_LOW_BITS | low, LDR_IMMEDIATE_BITS);
        return new Ir64Op.SveLoad(op, 0, 0, false, 0, rd, (word >>> RN_SHIFT) & REGISTER_MASK, 0, false, immediate, 0,
                false, address);
    }

    /// `PRF*` é hint: o IR só carrega a checagem de acesso (e, em `PRF_ns`, a de modo streaming).
    private static Ir64Op prefetch(boolean nonStreaming, int word, long address) {
        return new Ir64Op.SveLoad(Op.PRF, 0, 0, false, 0, 0, 0, 0, false, 0, 0, nonStreaming, address);
    }

    // ── Prefixo 0xA4/0xA5 ────────────────────────────────────────────────────────────────────────

    private Ir64Op decodeContiguous(int word, long address) {
        int opcode = (word >>> OPCODE_SHIFT) & OPCODE_MASK;
        int top = (word >>> DTYPE_SHIFT) & DTYPE_MASK; // bits 24:21
        int sel = (word >>> NREG_SHIFT) & NREG_MASK;   // bits 22:21
        int msz = (word >>> MSZ_SHIFT) & MSZ_MASK;     // bits 24:23
        boolean bit20 = ((word >>> BIT_NON_FAULT) & 1) != 0;
        return switch (opcode) {
            case OP_LOAD_REGISTER -> load(Op.LD1, top, 0, true, word, address);
            case OP_FIRST_FAULT -> load(Op.LDFF1, top, 0, true, word, address);
            case OP_LOAD_IMMEDIATE -> load(bit20 ? Op.LDNF1 : Op.LD1, top, 0, false, word, address);
            case OP_REGISTER_SPECIAL -> registerSpecial(word, top, sel, msz, address);
            case OP_IMMEDIATE_SPECIAL -> immediateSpecial(word, top, sel, msz, bit20, address);
            case OP_STRUCTURE_REGISTER -> structure(word, msz, sel, true, address);
            case OP_STRUCTURE_IMMEDIATE -> bit20 ? structureQuadImmediate(word, msz, sel, address)
                    : structure(word, msz, sel, false, address);
            default -> broadcastQuadword(word, sel, msz, address); // OP_BROADCAST_QUADWORD_REGISTER
        };
    }

    /// `LD1`/`LDFF1`/`LDNF1` com `dtype` de 4 bits em `bits[24:21]` (imediato de 4 bits em `bits[19:16]`).
    private Ir64Op load(Op op, int dtype, int nreg, boolean registerOffset, int word, long address) {
        boolean nonStreaming = op != Op.LD1;
        return build(op, DTYPE_MSZ[dtype], DTYPE_ESZ[dtype], DTYPE_SIGNED[dtype], nreg, registerOffset, word,
                nonStreaming, address);
    }

    /// `bits[15:13] = 100`: `LD1W`/`LD1D` de elemento de 128 bits e `LD[234]Q` (escalar+escalar).
    private Ir64Op registerSpecial(int word, int top, int sel, int msz, long address) {
        if (top == TOP_WORD_QUAD_ELEMENT || top == TOP_DOUBLE_QUAD_ELEMENT) {
            return quadElement(top, true, word, address);
        }
        if (sel == SEL_STRUCTURE_QUAD && msz != 0) {
            return structureQuad(msz, true, word, address);
        }
        return null;
    }

    /// `bits[15:13] = 001`: `LD1W`/`LD1D` `.Q` com imediato, `LD1RQ` e `LD1RO` com imediato.
    private Ir64Op immediateSpecial(int word, int top, int sel, int msz, boolean bit20, long address) {
        if (bit20) {
            if (top == TOP_WORD_QUAD_ELEMENT || top == TOP_DOUBLE_QUAD_ELEMENT) {
                return quadElement(top, false, word, address);
            }
            return null;
        }
        if (sel == SEL_QUADWORD || sel == SEL_OCTAWORD) {
            return broadcast(sel, msz, false, word, address);
        }
        return null;
    }

    private Ir64Op quadElement(int top, boolean registerOffset, int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2_1)) {
            return null;
        }
        int msz = top == TOP_WORD_QUAD_ELEMENT ? MSZ_WORD : MSZ_DOUBLE;
        return build(Op.LD1, msz, ESZ_QUAD, false, 0, registerOffset, word, true, address);
    }

    private Ir64Op structureQuad(int nreg, boolean registerOffset, int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2_1)) {
            return null;
        }
        return build(Op.LD1, MSZ_QUAD, ESZ_QUAD, false, nreg, registerOffset, word, false, address);
    }

    private Ir64Op structureQuadImmediate(int word, int nreg, int sel, long address) {
        if (sel != SEL_QUADWORD || nreg == 0) {
            return null;
        }
        return structureQuad(nreg, false, word, address);
    }

    /// `LDNT1*` (`nreg = 0`) e `LD2*`-`LD4*`: `msz` em `bits[24:23]`, `nreg` em `bits[22:21]`, `dtype` = `msz_dtype`.
    private Ir64Op structure(int word, int msz, int nreg, boolean registerOffset, long address) {
        int dtype = MSZ_TO_DTYPE[msz];
        return build(Op.LD1, DTYPE_MSZ[dtype], DTYPE_ESZ[dtype], false, nreg, registerOffset, word, false, address);
    }

    private Ir64Op broadcastQuadword(int word, int sel, int msz, long address) {
        if (sel != SEL_QUADWORD && sel != SEL_OCTAWORD) {
            return null;
        }
        return broadcast(sel, msz, true, word, address);
    }

    /// `LD1RQ` (`sel = 00`) e `LD1RO` (`sel = 01`, `FEAT_F64MM`, ilegal em modo streaming).
    private Ir64Op broadcast(int sel, int msz, boolean registerOffset, int word, long address) {
        boolean octaword = sel == SEL_OCTAWORD;
        if (octaword && !architecture.has(Aarch64Feature.F64MM)) {
            return null;
        }
        int dtype = MSZ_TO_DTYPE[msz];
        return build(octaword ? Op.LD1RO : Op.LD1RQ, DTYPE_MSZ[dtype], DTYPE_ESZ[dtype], false, 0, registerOffset, word,
                octaword, address);
    }

    /// Monta o IR de uma forma escalar+escalar (`Xm` em `bits[20:16]`) ou escalar+imediato (`imm4` em `bits[19:16]`).
    /// `LDFF1` aceita `Rm = 31` (`XZR`); as demais formas escalar+escalar o recusam.
    private Ir64Op build(Op op, int msz, int esz, boolean signExtend, int nreg, boolean registerOffset, int word,
            boolean nonStreaming, long address) {
        int rm = (word >>> RM_SHIFT) & RM_MASK;
        if (registerOffset && rm == RM_UNALLOCATED && op != Op.LDFF1) {
            return null;
        }
        long immediate = registerOffset ? 0 : signExtend((word >>> IMM4_SHIFT) & IMM4_MASK, SIGNED_IMMEDIATE_BITS);
        return new Ir64Op.SveLoad(op, msz, esz, signExtend, nreg, word & REGISTER_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, rm, registerOffset, immediate,
                (word >>> PG_SHIFT) & PREDICATE_MASK, nonStreaming, address);
    }

    private static long signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }
}
