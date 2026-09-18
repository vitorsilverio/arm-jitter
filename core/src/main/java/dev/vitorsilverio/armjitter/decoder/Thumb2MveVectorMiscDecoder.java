package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VCLS`/`VCLZ`/`VREV16`/`VREV32`/`VREV64`/`VMVN`/`VABS`/`VABS_fp`/`VNEG`/`VNEG_fp`/`VQABS`/
/// `VQNEG`/`VDUP` — sub-família 2 da B16.13a (perfil M, MVE/Helium, `target/isa-decode/mve.decode`,
/// linhas 369-395, 15 encodings, bit a bit contra o arquivo real):
///
/// ```
/// VCLS     1111 1111 1 . 11 .. 00 ... 0 0100 01 . 0 ... 0 @1op
/// VCLZ     1111 1111 1 . 11 .. 00 ... 0 0100 11 . 0 ... 0 @1op
/// VREV16   1111 1111 1 . 11 .. 00 ... 0 0001 01 . 0 ... 0 @1op
/// VREV32   1111 1111 1 . 11 .. 00 ... 0 0000 11 . 0 ... 0 @1op
/// VREV64   1111 1111 1 . 11 .. 00 ... 0 0000 01 . 0 ... 0 @1op
/// VMVN     1111 1111 1 . 11 00 00 ... 0 0101 11 . 0 ... 0 @1op_nosz
/// VABS     1111 1111 1 . 11 .. 01 ... 0 0011 01 . 0 ... 0 @1op
/// VABS_fp  1111 1111 1 . 11 .. 01 ... 0 0111 01 . 0 ... 0 @1op
/// VNEG     1111 1111 1 . 11 .. 01 ... 0 0011 11 . 0 ... 0 @1op
/// VNEG_fp  1111 1111 1 . 11 .. 01 ... 0 0111 11 . 0 ... 0 @1op
/// VQABS    1111 1111 1 . 11 .. 00 ... 0 0111 01 . 0 ... 0 @1op
/// VQNEG    1111 1111 1 . 11 .. 00 ... 0 0111 11 . 0 ... 0 @1op
/// VDUP     1110 1110 1 1 10 ... 0 .... 1011 . 0 0 1 0000 @vdup size=0
/// VDUP     1110 1110 1 0 10 ... 0 .... 1011 . 0 1 1 0000 @vdup size=1
/// VDUP     1110 1110 1 0 10 ... 0 .... 1011 . 0 0 1 0000 @vdup size=2
/// ```
///
/// **`bits[17:16]` (não `bits[19:18]`) é o discriminador PRIMÁRIO das 12 formas `@1op`** — `00`
/// (`VCLS`/`VCLZ`/`VREV*`/`VMVN`/`VQABS`/`VQNEG`) vs `01` (`VABS`/`VABS_fp`/`VNEG`/`VNEG_fp`); `10`/
/// `11` pertencem a `VCVT`/`VRINT` ({@link Thumb2MveFpConvertDecoder}, B16.12) e nunca chegam aqui.
/// Dentro de cada grupo, `nibble=bits[11:8]` + `sub2=bits[7:6]` escolhem a operação — medido bit a
/// bit, não deduzido do nome do mnemônico (ex.: `VQABS`/`VABS_fp` partilham `nibble=0111,sub2=01`,
/// mas em grupos diferentes).
///
/// **`VMVN` usa `@1op_nosz`**: os bits que seriam `size` (`bits[19:18]`) são LITERAIS `00` na
/// própria linha, não um campo herdado — um raw com `nibble=0101,sub2=11,group=00` e `bits[19:18]
/// != 00` NÃO é `VMVN` (não tem mnemônico real, G8), então este decoder rejeita explicitamente em
/// vez de tratar como `size` genérico.
///
/// **`VDUP`: `Qd` vem de `%qn`, não `%qd`** (comentário literal do arquivo: "Qd is in the fields
/// usually named Qn") — mesma classe de armadilha de "Qm is in the fields usually labeled Qn" da
/// B16.5. `size` vem de `(B,E) = (bit22,bit5)`: `(1,0)`=byte, `(0,1)`=halfword, `(0,0)`=word;
/// `(1,1)` não corresponde a nenhuma das 3 linhas (cai em `null`).
///
/// Gate: {@link ArmFeature#MVE_INTEGER} (`VABS_fp`/`VNEG_fp` exigem também {@link
/// ArmFeature#MVE_FLOAT}). TEM que ser registrado ANTES de `Thumb2NocpDecoder` (`bits[27:24]` desta
/// seção `∈ {1110,1111}` sob `M_PROFILE`).
public final class Thumb2MveVectorMiscDecoder implements DecoderExtension {
    // ─── @1op / @1op_nosz (12 encodings) ───────────────────────────────────────────────────────
    private static final int ONE_OP_TOP9_SHIFT = 23;
    private static final int ONE_OP_TOP9_MASK = 0x1FF;
    private static final int ONE_OP_TOP9_VALUE = 0b111111111;

    private static final int BITS_21_20_SHIFT = 20;
    private static final int BITS_21_20_MASK = 0x3;
    private static final int BITS_21_20_VALUE = 0b11;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int SIZE_SHIFT = 18;
    private static final int SIZE_MASK = 0x3;
    private static final int SIZE_INVALID = 3;
    private static final int GROUP_SHIFT = 16;
    private static final int GROUP_MASK = 0x3;
    private static final int GROUP_MISC = 0b00;
    private static final int GROUP_ABS_NEG = 0b01;

    private static final int BIT12 = 12;
    private static final int NIBBLE_SHIFT = 8;
    private static final int NIBBLE_MASK = 0xF;
    private static final int SUB2_SHIFT = 6;
    private static final int SUB2_MASK = 0x3;
    private static final int BIT4 = 4;

    private static final int NIBBLE_CLS_CLZ = 0b0100;
    private static final int NIBBLE_REV16 = 0b0001;
    private static final int NIBBLE_REV32_64 = 0b0000;
    private static final int NIBBLE_MVN = 0b0101;
    private static final int NIBBLE_QABS_QNEG = 0b0111;
    private static final int NIBBLE_ABS_NEG_INT = 0b0011;
    private static final int NIBBLE_ABS_NEG_FP = 0b0111;
    private static final int SUB2_FIRST = 0b01;
    private static final int SUB2_SECOND = 0b11;

    // ─── @vdup (3 encodings) ────────────────────────────────────────────────────────────────────
    private static final int VDUP_TOP8_SHIFT = 24;
    private static final int VDUP_TOP8_MASK = 0xFF;
    private static final int VDUP_TOP8_VALUE = 0b1110_1110;
    private static final int VDUP_BIT23 = 23;
    private static final int VDUP_BITS_21_20_VALUE = 0b10;
    private static final int VDUP_QN_HIGH_BIT = 7;
    private static final int VDUP_QN_LOW_SHIFT = 17;
    private static final int VDUP_QN_LOW_MASK = 0x7;
    private static final int VDUP_RT_SHIFT = 12;
    private static final int VDUP_RT_MASK = 0xF;
    private static final int VDUP_NIBBLE_SHIFT = 8;
    private static final int VDUP_NIBBLE_MASK = 0xF;
    private static final int VDUP_NIBBLE_VALUE = 0b1011;
    private static final int VDUP_BIT6 = 6;
    private static final int VDUP_BOTTOM4_MASK = 0xF;
    private static final int VDUP_BIT22_B = 22;
    private static final int VDUP_BIT5_E = 5;
    private static final int VDUP_SIZE_BYTE = 0;
    private static final int VDUP_SIZE_HALFWORD = 1;
    private static final int VDUP_SIZE_WORD = 2;

    private static final int GPR_SP = 13;
    private static final int GPR_PC = 15;

    private final ArmArchitecture architecture;

    public Thumb2MveVectorMiscDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        DecodedInstruction oneOp = tryDecodeOneOp(raw, address, condition);
        if (oneOp != null) {
            return oneOp;
        }
        return tryDecodeVdup(raw, address, condition);
    }

    private DecodedInstruction tryDecodeOneOp(int raw, int address, Condition condition) {
        if (((raw >>> ONE_OP_TOP9_SHIFT) & ONE_OP_TOP9_MASK) != ONE_OP_TOP9_VALUE) {
            return null;
        }
        if (((raw >>> BITS_21_20_SHIFT) & BITS_21_20_MASK) != BITS_21_20_VALUE) {
            return null;
        }
        if (((raw >>> BIT12) & 1) != 0 || ((raw >>> BIT4) & 1) != 0 || (raw & 1) != 0) {
            return null;
        }
        int group = (raw >>> GROUP_SHIFT) & GROUP_MASK;
        if (group != GROUP_MISC && group != GROUP_ABS_NEG) {
            return null; // bits[17:16] em {10,11}: VCVT/VRINT (B16.12), não é este decoder.
        }
        int nibble = (raw >>> NIBBLE_SHIFT) & NIBBLE_MASK;
        int sub2 = (raw >>> SUB2_SHIFT) & SUB2_MASK;
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        if (group == GROUP_MISC && nibble == NIBBLE_MVN && sub2 == SUB2_SECOND) {
            // @1op_nosz: bits[19:16] são LITERAIS "0000" na linha real (bits[21:20] já confirmados
            // "11" acima, e `group` já confirmado "00" — só falta checar bits[19:18]).
            if (((raw >>> SIZE_SHIFT) & SIZE_MASK) != 0) {
                return null;
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveVectorUnary(AdvSimdUnaryOp.NOT, 0, qd, qm, condition));
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size == SIZE_INVALID) {
            return null;
        }
        if (group == GROUP_MISC) {
            AdvSimdUnaryOp op = switch (nibble) {
                case NIBBLE_CLS_CLZ -> sub2 == SUB2_FIRST ? AdvSimdUnaryOp.CLS
                        : sub2 == SUB2_SECOND ? AdvSimdUnaryOp.CLZ : null;
                case NIBBLE_REV16 -> sub2 == SUB2_FIRST ? AdvSimdUnaryOp.REV16 : null;
                case NIBBLE_REV32_64 -> sub2 == SUB2_SECOND ? AdvSimdUnaryOp.REV32
                        : sub2 == SUB2_FIRST ? AdvSimdUnaryOp.REV64 : null;
                case NIBBLE_QABS_QNEG -> sub2 == SUB2_FIRST ? AdvSimdUnaryOp.SQABS
                        : sub2 == SUB2_SECOND ? AdvSimdUnaryOp.SQNEG : null;
                default -> null;
            };
            if (op == null) {
                return null;
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveVectorUnary(op, size, qd, qm, condition));
        }
        // group == GROUP_ABS_NEG
        if (nibble == NIBBLE_ABS_NEG_INT) {
            AdvSimdUnaryOp op = sub2 == SUB2_FIRST ? AdvSimdUnaryOp.ABS : sub2 == SUB2_SECOND ? AdvSimdUnaryOp.NEG
                    : null;
            if (op == null) {
                return null;
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveVectorUnary(op, size, qd, qm, condition));
        }
        if (nibble == NIBBLE_ABS_NEG_FP) {
            if (!architecture.has(ArmFeature.MVE_FLOAT)) {
                return null;
            }
            AdvSimdFpUnaryOp op = sub2 == SUB2_FIRST ? AdvSimdFpUnaryOp.ABS
                    : sub2 == SUB2_SECOND ? AdvSimdFpUnaryOp.NEG : null;
            if (op == null) {
                return null;
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveVectorFpUnary(op, size, qd, qm, condition));
        }
        return null;
    }

    private DecodedInstruction tryDecodeVdup(int raw, int address, Condition condition) {
        if (((raw >>> VDUP_TOP8_SHIFT) & VDUP_TOP8_MASK) != VDUP_TOP8_VALUE) {
            return null;
        }
        if (((raw >>> VDUP_BIT23) & 1) == 0) {
            return null;
        }
        if (((raw >>> BITS_21_20_SHIFT) & BITS_21_20_MASK) != VDUP_BITS_21_20_VALUE) {
            return null;
        }
        if (((raw >>> VDUP_NIBBLE_SHIFT) & VDUP_NIBBLE_MASK) != VDUP_NIBBLE_VALUE) {
            return null;
        }
        if (((raw >>> VDUP_BIT6) & 1) != 0) {
            return null;
        }
        if ((raw & VDUP_BOTTOM4_MASK) != 0) {
            return null;
        }
        boolean b = ((raw >>> VDUP_BIT22_B) & 1) != 0;
        boolean e = ((raw >>> VDUP_BIT5_E) & 1) != 0;
        int size;
        if (b && !e) {
            size = VDUP_SIZE_BYTE;
        } else if (!b && e) {
            size = VDUP_SIZE_HALFWORD;
        } else if (!b) {
            size = VDUP_SIZE_WORD;
        } else {
            return null; // B=E=1 não corresponde a nenhuma das 3 linhas reais.
        }
        int qd = ((raw >>> VDUP_QN_HIGH_BIT) & 1) << 3 | ((raw >>> VDUP_QN_LOW_SHIFT) & VDUP_QN_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        int rt = (raw >>> VDUP_RT_SHIFT) & VDUP_RT_MASK;
        if (rt == GPR_SP || rt == GPR_PC) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorDup(size, qd, rt, condition));
    }
}
