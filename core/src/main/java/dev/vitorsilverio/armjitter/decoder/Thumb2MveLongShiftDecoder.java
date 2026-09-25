package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp.WideShiftOperation;

/// MVE "long shift" sobre registradores de propósito geral (B16.16, perfil M, `FEAT_MVE_INTEGER`,
/// `target/isa-decode/t32.decode`, linhas 93-131, 19 encodings, bit a bit contra o arquivo real):
/// `LSLL`/`LSRL`/`ASRL`/`URSHRL`/`SRSHRL`/`UQSHLL`/`SQSHLL` (par `RdaLo:RdaHi`), `UQSHL`/`URSHR`/
/// `SRSHR`/`SQSHL` (`Rda`) por imediato, e `LSLL`/`ASRL`/`UQRSHLL64`/`SQRSHRL64`/`UQRSHLL48`/
/// `SQRSHRL48`/`UQRSHL`/`SQRSHR` por registrador.
///
/// Vivem no espaço de *Data-processing (shifted register)* (`1110 1010 0101 ...`), EM CIMA de
/// `MOV`/`ORR` com `Rm` ∈ {13,15} (casos UNPREDICTABLE de `MOVS`/`ORRS`), então este decoder TEM que
/// ser registrado ANTES de {@link Thumb2DataProcessingDecoder}. Só toma o que o `.decode` do QEMU
/// toma; todo o resto devolve `null` (segue para `MOV`/`ORR`).
///
/// **Desambiguação 32 × 64 bits** (grupos `{}` do `.decode`, primeira que casa vence): com
/// `bits[11:8] == 1111` o padrão de 32 bits (`rdahi == 15` na forma de 64) casa PRIMEIRO. Nas formas
/// de 64 bits, `rdahi == 15` significa portanto "é a outra instrução", não erro — e é por isso que
/// `UQRSHLL48`/`SQRSHRL48` (que não têm par de 32 bits) devolvem `null` nesse caso.
///
/// **UNDEFINED explícito** (`unallocated_encoding` do QEMU — NÃO cai em `MOV`/`ORR`, G8): `rdahi ==
/// 13`; nas formas por registrador também `Rm ∈ {13,15}`, `Rm == RdaHi`, `Rm == RdaLo`; nas de 32 bits
/// `Rda ∈ {13,15}` (e `Rm ∈ {13,15}`, `Rm == Rda` no registrador). `shim == 0` significa `32`.
///
/// Gate: {@link ArmFeature#MVE_INTEGER} (MVE só existe no perfil M mainline, que é o que o QEMU
/// exige com `ARM_FEATURE_M_MAIN`); sem ele todas as formas seguem como `MOV`/`ORR`.
public final class Thumb2MveLongShiftDecoder implements DecoderExtension {
    private static final int TOP12_SHIFT = 20;
    private static final int TOP12_VALUE = 0b1110_1010_0101;

    private static final int LOW4_MASK = 0xF;
    private static final int LOW4_IMMEDIATE = 0b1111;
    private static final int LOW4_REGISTER = 0b1101;

    private static final int BIT15 = 15;
    private static final int BIT16 = 16;
    private static final int BIT8 = 8;
    private static final int WORD_FORM_MASK = 0xF;
    private static final int WORD_FORM_SHIFT = 8;
    private static final int WORD_FORM_VALUE = 0b1111;

    private static final int OPCODE_SHIFT = 4;
    private static final int OPCODE_MASK = 0x3;
    private static final int OPCODE_LEFT = 0b00;
    private static final int OPCODE_LOGICAL_RIGHT = 0b01;
    private static final int OPCODE_ARITHMETIC_RIGHT = 0b10;
    private static final int OPCODE_SATURATING_LEFT = 0b11;

    private static final int REGISTER_OPCODE_SHIFT = 4;
    private static final int REGISTER_OPCODE_MASK = 0xF;
    private static final int REGISTER_OP_LEFT = 0b0000;
    private static final int REGISTER_OP_RIGHT = 0b0010;
    private static final int REGISTER_OP_LEFT_48 = 0b1000;
    private static final int REGISTER_OP_RIGHT_48 = 0b1010;

    private static final int RDA_SHIFT = 16;
    private static final int RDA_MASK = 0xF;
    private static final int RM_SHIFT = 12;
    private static final int RM_MASK = 0xF;
    private static final int RDALO_SHIFT = 17;
    private static final int RDAHI_SHIFT = 9;
    private static final int PAIR_INDEX_MASK = 0x7;

    private static final int IMM3_SHIFT = 12;
    private static final int IMM3_MASK = 0x7;
    private static final int IMM2_SHIFT = 6;
    private static final int IMM2_MASK = 0x3;
    private static final int IMM2_BITS = 2;
    private static final int SHIM_ZERO_MEANS = 32;

    private static final int GPR_SP = 13;
    private static final int GPR_PC = 15;

    private static final int PACKED_AMOUNT_SHIFT = 8;
    private static final int NO_REGISTER = -1;

    private final ArmArchitecture architecture;

    public Thumb2MveLongShiftDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if ((raw >>> TOP12_SHIFT) != TOP12_VALUE) {
            return null;
        }
        int low4 = raw & LOW4_MASK;
        if (low4 == LOW4_IMMEDIATE) {
            return decodeImmediate(raw, address, condition);
        }
        if (low4 == LOW4_REGISTER) {
            return decodeRegister(raw, address, condition);
        }
        return null;
    }

    private DecodedInstruction decodeImmediate(int raw, int address, Condition condition) {
        if (bit(raw, BIT15) != 0) {
            return null;
        }
        int opcode = (raw >>> OPCODE_SHIFT) & OPCODE_MASK;
        int shim = ((raw >>> IMM3_SHIFT) & IMM3_MASK) << IMM2_BITS | ((raw >>> IMM2_SHIFT) & IMM2_MASK);
        if (shim == 0) {
            shim = SHIM_ZERO_MEANS;
        }
        if (isWordForm(raw)) {
            int rda = (raw >>> RDA_SHIFT) & RDA_MASK;
            WideShiftOperation operation = switch (opcode) {
                case OPCODE_LEFT -> WideShiftOperation.UQSHL_RI;
                case OPCODE_LOGICAL_RIGHT -> WideShiftOperation.URSHR_RI;
                case OPCODE_ARITHMETIC_RIGHT -> WideShiftOperation.SRSHR_RI;
                default -> WideShiftOperation.SQSHL_RI;
            };
            if (rda == GPR_SP || rda == GPR_PC) {
                return refused(raw, address, condition);
            }
            return pack(raw, address, condition, operation, shim, NO_REGISTER, rda, NO_REGISTER);
        }
        if (bit(raw, BIT8) == 0) {
            return null;
        }
        boolean high = bit(raw, BIT16) != 0;
        WideShiftOperation operation;
        if (opcode == OPCODE_SATURATING_LEFT) {
            if (!high) {
                return null;
            }
            operation = WideShiftOperation.SQSHLL_RI;
        } else if (opcode == OPCODE_LEFT) {
            operation = high ? WideShiftOperation.UQSHLL_RI : WideShiftOperation.LSLL_RI;
        } else if (opcode == OPCODE_LOGICAL_RIGHT) {
            operation = high ? WideShiftOperation.URSHRL_RI : WideShiftOperation.LSRL_RI;
        } else {
            operation = high ? WideShiftOperation.SRSHRL_RI : WideShiftOperation.ASRL_RI;
        }
        int rdaLo = pairLow(raw);
        int rdaHi = pairHigh(raw);
        if (rdaHi == GPR_SP) {
            return refused(raw, address, condition);
        }
        return pack(raw, address, condition, operation, shim, NO_REGISTER, rdaLo, rdaHi);
    }

    private DecodedInstruction decodeRegister(int raw, int address, Condition condition) {
        int op = (raw >>> REGISTER_OPCODE_SHIFT) & REGISTER_OPCODE_MASK;
        int rm = (raw >>> RM_SHIFT) & RM_MASK;
        if (isWordForm(raw)) {
            WideShiftOperation operation;
            if (op == REGISTER_OP_LEFT) {
                operation = WideShiftOperation.UQRSHL_RR;
            } else if (op == REGISTER_OP_RIGHT) {
                operation = WideShiftOperation.SQRSHR_RR;
            } else {
                return null;
            }
            int rda = (raw >>> RDA_SHIFT) & RDA_MASK;
            if (rda == GPR_SP || rda == GPR_PC || rm == GPR_SP || rm == GPR_PC || rm == rda) {
                return refused(raw, address, condition);
            }
            return pack(raw, address, condition, operation, 0, rm, rda, NO_REGISTER);
        }
        if (bit(raw, BIT8) == 0) {
            return null;
        }
        boolean high = bit(raw, BIT16) != 0;
        WideShiftOperation operation;
        if (op == REGISTER_OP_LEFT) {
            operation = high ? WideShiftOperation.UQRSHLL64_RR : WideShiftOperation.LSLL_RR;
        } else if (op == REGISTER_OP_RIGHT) {
            operation = high ? WideShiftOperation.SQRSHRL64_RR : WideShiftOperation.ASRL_RR;
        } else if (op == REGISTER_OP_LEFT_48 && high) {
            operation = WideShiftOperation.UQRSHLL48_RR;
        } else if (op == REGISTER_OP_RIGHT_48 && high) {
            operation = WideShiftOperation.SQRSHRL48_RR;
        } else {
            return null;
        }
        int rdaLo = pairLow(raw);
        int rdaHi = pairHigh(raw);
        if (rdaHi == GPR_SP || rm == GPR_SP || rm == GPR_PC || rm == rdaHi || rm == rdaLo) {
            return refused(raw, address, condition);
        }
        return pack(raw, address, condition, operation, 0, rm, rdaLo, rdaHi);
    }

    private static DecodedInstruction pack(int raw, int address, Condition condition, WideShiftOperation operation,
            int shim, int rm, int rdaLo, int rdaHi) {
        int immediate = (shim << PACKED_AMOUNT_SHIFT) | operation.ordinal();
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MVE_WIDE_SHIFT,
                rdaLo, rdaHi, rm, immediate, false, false, false);
    }

    private static DecodedInstruction refused(int raw, int address, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
    }

    /// `bits[11:8] == 1111`: o padrão de 32 bits (`rdahi == 15` na forma de 64) casa primeiro.
    private static boolean isWordForm(int raw) {
        return ((raw >>> WORD_FORM_SHIFT) & WORD_FORM_MASK) == WORD_FORM_VALUE;
    }

    /// `%rdalo_17`: `2 * bits[19:17]`.
    private static int pairLow(int raw) {
        return ((raw >>> RDALO_SHIFT) & PAIR_INDEX_MASK) << 1;
    }

    /// `%rdahi_9`: `2 * bits[11:9] + 1`.
    private static int pairHigh(int raw) {
        return (((raw >>> RDAHI_SHIFT) & PAIR_INDEX_MASK) << 1) + 1;
    }

    private static int bit(int raw, int index) {
        return (raw >>> index) & 1;
    }
}
