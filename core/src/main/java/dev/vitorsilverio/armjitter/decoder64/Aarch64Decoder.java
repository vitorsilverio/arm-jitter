package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Decodifica instruções AArch64 (A64) para {@link Ir64Op} — fatia B6.1 + B6.2 + B6.3.1 + B6.3.2:
/// os grupos `data-processing immediate` (`ADD`/`SUB`/`AND`/`ORR`/`EOR`/`ANDS` imediato, `MOVZ`/
/// `MOVN`/`MOVK`, `ADR`/`ADRP`, `SBFM`/`BFM`/`UBFM` — B6.3.2), `data-processing register` (`ADD`/
/// `SUB`/`ADDS`/`SUBS` shifted e extended register — B6.3.1; `CSEL`/`CSINC`/`CSINV`/`CSNEG` —
/// B6.3.2), `branches/exception/system` (`B`/`BL`/`B.cond`/`CBZ`/`CBNZ`/`TBZ`/`TBNZ`/`BR`/`BLR`/
/// `RET`/`SVC`) e `loads and stores` de registrador geral (`LDR`/`STR`/`LDUR`/`STUR`/`LDP`/`STP`/
/// `LDR (literal)`, tamanhos B/H/W/X + sign-extend, pre/post-index, registrador+extend) — ver
/// `tasks/trilha-b-arquiteturas/b6-aarch64.md` §B6.1/§B6.2/§B6.3. Todos os campos de bit abaixo
/// foram verificados byte a byte contra a saída real de `aarch64-none-elf-as`/`objdump`
/// (devkitA64) — ver o corpus versionado em `src/test/resources/aarch64/corpus.s`.
///
/// `Extract` (`EXTR`, mesmo subgrupo de `Bitfield` dentro de `Data Processing Immediate`),
/// `2-source`/`3-source`/`Logical (shifted register)` (mesma classe `Data Processing Register` —
/// só `Add/subtract` shifted/extended e `CSEL`/... estão implementados aqui, ver B6.3.3), load/
/// store exclusivo e atômico, load/store de registrador SIMD&FP (`V=1`) e data-processing SIMD&FP
/// ficam FORA desta fatia (B6.3.3+) — qualquer encoding fora do escopo listado lança
/// {@link UnsupportedOperationException} em vez de tentar adivinhar semântica (nenhum oráculo
/// real cobre o que não foi implementado).
public final class Aarch64Decoder {
    // ── Classe top-level (ARM DDI 0487 C4.1): prefixo fixo de 3 bits em bits[28:26] (o 4º bit
    // do op0 nominal do manual, bit25, é wildcard dentro da classe e tratado nos sub-decoders) ─
    private static final int TOP_LEVEL_CLASS_SHIFT = 26;
    private static final int TOP_LEVEL_CLASS_3BIT_MASK = 0b111;
    private static final int CLASS_DATA_PROCESSING_IMMEDIATE = 0b100;
    private static final int CLASS_BRANCH_EXCEPTION_SYSTEM = 0b101;

    // ── Sub-grupos de "Data Processing Immediate" (bit 25 e bits 24:23) ─────────────────────
    private static final int BIT_25 = 1 << 25;
    private static final int BIT_24 = 1 << 24;
    private static final int SUBGROUP_24_23_SHIFT = 23;
    private static final int SUBGROUP_24_23_MASK = 0b11;
    private static final int SUBGROUP_LOGICAL_IMMEDIATE = 0b00;
    private static final int SUBGROUP_MOVE_WIDE = 0b01;
    private static final int SUBGROUP_BITFIELD = 0b10;

    // ── Logical (immediate): sf(31) opc(30:29) 100100(28:23) N(22) immr(21:16) imms(15:10) ────
    // ── Rn(9:5) Rd(4:0) ──────────────────────────────────────────────────────────────────────
    private static final int LOGICAL_IMM_OPC_SHIFT = 29;
    private static final int LOGICAL_IMM_OPC_MASK = 0b11;
    private static final int LOGICAL_IMM_OPC_AND = 0b00;
    private static final int LOGICAL_IMM_OPC_ORR = 0b01;
    private static final int LOGICAL_IMM_OPC_EOR = 0b10;
    private static final int LOGICAL_IMM_OPC_ANDS = 0b11;
    private static final int LOGICAL_IMM_N_SHIFT = 22;
    private static final int LOGICAL_IMM_IMMR_SHIFT = 16;
    private static final int LOGICAL_IMM_IMMS_SHIFT = 10;
    private static final int LOGICAL_IMM_FIELD_MASK = 0b11_1111;

    // ── PC-rel addressing (ADR/ADRP): op(31) immlo(30:29) 10000(28:24) immhi(23:5) Rd(4:0) ──
    private static final int PC_REL_OP_SHIFT = 31;
    private static final int PC_REL_IMMLO_SHIFT = 29;
    private static final int PC_REL_IMMLO_MASK = 0b11;
    private static final int PC_REL_IMMHI_SHIFT = 5;
    private static final int PC_REL_IMMHI_BITS = 19;
    private static final int PC_REL_IMM_TOTAL_BITS = 21;
    private static final int ADRP_PAGE_SHIFT = 12;

    // ── Add/sub (immediate): sf(31) op(30) S(29) 10001(28:24) shift(23:22) imm12(21:10) ─────
    // ── Rn(9:5) Rd(4:0) ──────────────────────────────────────────────────────────────────────
    private static final int SF_SHIFT = 31;
    private static final int ADD_SUB_OP_SHIFT = 30;
    private static final int SET_FLAGS_SHIFT = 29;
    private static final int ADD_SUB_SHIFT_FIELD_SHIFT = 22;
    private static final int ADD_SUB_SHIFT_FIELD_MASK = 0b11;
    private static final int ADD_SUB_SHIFT_LSL_12 = 0b01;
    private static final int IMM12_SHIFT = 10;
    private static final int IMM12_MASK = 0xFFF;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_FIELD_MASK = 0b1_1111;

    // ── Move wide (immediate): sf(31) opc(30:29) 100101(28:23) hw(22:21) imm16(20:5) Rd(4:0) ─
    private static final int MOVE_WIDE_OPC_SHIFT = 29;
    private static final int MOVE_WIDE_OPC_MASK = 0b11;
    private static final int MOVE_WIDE_OPC_MOVN = 0b00;
    private static final int MOVE_WIDE_OPC_MOVZ = 0b10;
    private static final int MOVE_WIDE_OPC_MOVK = 0b11;
    private static final int MOVE_WIDE_HW_SHIFT = 21;
    private static final int MOVE_WIDE_HW_MASK = 0b11;
    private static final int MOVE_WIDE_HW_UNIT_BITS = 16;
    private static final int IMM16_SHIFT = 5;
    private static final int IMM16_MASK = 0xFFFF;

    // ── Branch classe (bits[30:26]) ──────────────────────────────────────────────────────────
    private static final int BRANCH_SUBGROUP_SHIFT = 26;
    private static final int BRANCH_SUBGROUP_5BIT_MASK = 0b1_1111;
    private static final int SUBGROUP_UNCONDITIONAL_BRANCH_IMM = 0b00101;

    // ── B/BL (unconditional branch immediate): op(31) 00101(30:26) imm26(25:0) ──────────────
    private static final int IMM26_BITS = 26;

    // ── B.cond (conditional branch immediate): 0101010(31:25) o1(24) imm19(23:5) o0(4) ──────
    // ── cond(3:0) ────────────────────────────────────────────────────────────────────────────
    private static final int COND_BRANCH_FIXED_SHIFT = 25;
    private static final int COND_BRANCH_FIXED_7BIT_MASK = 0b111_1111;
    private static final int COND_BRANCH_FIXED_PATTERN = 0b0101010;
    private static final int COND_BRANCH_O1_BIT = BIT_24;
    private static final int COND_BRANCH_O0_BIT = 1 << 4;
    private static final int COND_FIELD_MASK = 0xF;

    // ── CBZ/CBNZ: sf(31) 011010(30:25) op(24) imm19(23:5) Rt(4:0) ──────────────────────────
    private static final int COMPARE_BRANCH_FIXED_SHIFT = 25;
    private static final int COMPARE_BRANCH_FIXED_6BIT_MASK = 0b11_1111;
    private static final int CBZ_FIXED_PATTERN = 0b011010;
    private static final int TBZ_FIXED_PATTERN = 0b011011;
    private static final int IMM19_SHIFT = 5;
    private static final int IMM19_BITS = 19;

    // ── TBZ/TBNZ: b5(31) 011011(30:25) op(24) b40(23:19) imm14(18:5) Rt(4:0) ────────────────
    private static final int TBZ_B5_SHIFT = 31;
    private static final int TBZ_B40_SHIFT = 19;
    private static final int TBZ_B40_MASK = 0b1_1111;
    private static final int TBZ_B40_BITS = 5;
    private static final int IMM14_SHIFT = 5;
    private static final int IMM14_BITS = 14;

    // ── BR/BLR/RET: 1101011(31:25) opc(24:21) op2=11111(20:16) op3=000000(15:10) Rn(9:5) ────
    // ── op4=00000(4:0) ──────────────────────────────────────────────────────────────────────
    private static final int BRANCH_REGISTER_FIXED_SHIFT = 25;
    private static final int BRANCH_REGISTER_FIXED_7BIT_MASK = 0b111_1111;
    private static final int BRANCH_REGISTER_FIXED_PATTERN = 0b1101011;
    private static final int BRANCH_REGISTER_OPC_SHIFT = 21;
    private static final int BRANCH_REGISTER_OPC_MASK = 0xF;
    private static final int BRANCH_REGISTER_OPC_BR = 0b0000;
    private static final int BRANCH_REGISTER_OPC_BLR = 0b0001;
    private static final int BRANCH_REGISTER_OPC_RET = 0b0010;
    private static final int BRANCH_REGISTER_OP2_SHIFT = 16;
    private static final int BRANCH_REGISTER_OP2_MASK = 0b1_1111;
    private static final int BRANCH_REGISTER_OP2_FIXED = 0b1_1111;
    private static final int BRANCH_REGISTER_OP3_SHIFT = 10;
    private static final int BRANCH_REGISTER_OP3_MASK = 0b11_1111;
    private static final int BRANCH_REGISTER_OP3_FIXED = 0b00_0000;
    private static final int BRANCH_REGISTER_OP4_MASK = 0b1_1111;
    private static final int BRANCH_REGISTER_OP4_FIXED = 0b0_0000;

    // ── SVC: 11010100(31:24) opc=000(23:21) imm16(20:5) opc2=000(4:2) LL=01(1:0) ────────────
    private static final int EXCEPTION_GEN_FIXED_SHIFT = 24;
    private static final int EXCEPTION_GEN_FIXED_8BIT_MASK = 0xFF;
    private static final int EXCEPTION_GEN_FIXED_PATTERN = 0b1101_0100;
    private static final int EXCEPTION_GEN_OPC_SHIFT = 21;
    private static final int EXCEPTION_GEN_OPC_MASK = 0b111;
    private static final int EXCEPTION_GEN_OPC_SVC = 0b000;
    private static final int EXCEPTION_GEN_LOW5_MASK = 0b1_1111;
    private static final int EXCEPTION_GEN_SVC_LOW5_FIXED = 0b0_0001;

    // ── Loads and Stores (classe `x1x0`, ARM DDI 0487 C4.1.3): bit27 fixo=1, bit25 fixo=0 ─────
    private static final int LOAD_STORE_CLASS_BIT27_SHIFT = 27;
    private static final int LOAD_STORE_CLASS_BIT25_SHIFT = 25;
    private static final int VECTOR_FORM_BIT_SHIFT = 26; // V: SIMD&FP em vez de registrador geral
    private static final int LOAD_STORE_SUBCLASS_SHIFT = 28;
    private static final int LOAD_STORE_SUBCLASS_MASK = 0b11;
    private static final int SUBCLASS_EXCLUSIVE_ATOMIC = 0b00;
    private static final int SUBCLASS_LITERAL = 0b01;
    private static final int SUBCLASS_PAIR = 0b10;
    private static final int SUBCLASS_SINGLE = 0b11;

    // ── LDR (literal): opc(31:30) 011 V 00 imm19(23:5) Rt(4:0) ──────────────────────────────
    private static final int LITERAL_OPC_SHIFT = 30;
    private static final int LITERAL_OPC_MASK = 0b11;
    private static final int LITERAL_OPC_32BIT = 0b00;
    private static final int LITERAL_OPC_64BIT = 0b01;
    private static final int LITERAL_OPC_LDRSW = 0b10;
    private static final int LITERAL_IMM19_SHIFT = 5;
    private static final int LITERAL_IMM19_BITS = 19;
    private static final int LITERAL_BYTES_PER_UNIT = 4;

    // ── LDP/STP: opc(31:30) 101 V(26) addrMode(24:23) L(22) imm7(21:15) Rt2(14:10) Rn(9:5) ───
    // ── Rt(4:0) ──────────────────────────────────────────────────────────────────────────────
    private static final int PAIR_OPC_SHIFT = 30;
    private static final int PAIR_OPC_MASK = 0b11;
    private static final int PAIR_OPC_32BIT = 0b00;
    private static final int PAIR_OPC_64BIT = 0b10;
    private static final int PAIR_ADDR_MODE_SHIFT = 23;
    private static final int PAIR_ADDR_MODE_MASK = 0b11;
    private static final int PAIR_ADDR_MODE_OFFSET = 0b10;
    private static final int PAIR_ADDR_MODE_POST_INDEX = 0b01;
    private static final int PAIR_ADDR_MODE_PRE_INDEX = 0b11;
    private static final int PAIR_LOAD_BIT_SHIFT = 22;
    private static final int PAIR_IMM7_SHIFT = 15;
    private static final int PAIR_IMM7_BITS = 7;
    private static final int PAIR_RT2_SHIFT = 10;
    private static final int PAIR_WORD_SCALE_BYTES = 4;
    private static final int PAIR_DOUBLEWORD_SCALE_BYTES = 8;

    // ── LDR/STR/LDUR/STUR (registrador geral): size(31:30) 111 V 0 opc(23:22) ... ───────────
    private static final int SINGLE_SIZE_SHIFT = 30;
    private static final int SINGLE_SIZE_MASK = 0b11;
    private static final int SIZE_BYTE = 0b00;
    private static final int SIZE_HALF = 0b01;
    private static final int SIZE_WORD = 0b10;
    private static final int SIZE_DOUBLEWORD = 0b11;
    private static final int SINGLE_OPC_SHIFT = 22;
    private static final int SINGLE_OPC_MASK = 0b11;
    private static final int OPC_STORE = 0b00;
    private static final int OPC_LOAD_ZERO_EXTEND = 0b01;
    private static final int OPC_LOAD_SIGN_EXTEND_TO_X = 0b10;
    private static final int OPC_LOAD_SIGN_EXTEND_TO_W = 0b11;
    /// Bit24 = 1 na forma "unsigned offset" (imediato de 12 bits escalado); 0 nas formas
    /// unscaled/pre-index/post-index/registrador (distintas então por {@link #SINGLE_IDX_SHIFT}).
    private static final int SINGLE_SCALED_OFFSET_BIT_SHIFT = 24;
    private static final int SINGLE_IMM12_SHIFT = 10;
    private static final int SINGLE_IMM12_MASK = 0xFFF;
    private static final int SINGLE_IDX_SHIFT = 10;
    private static final int SINGLE_IDX_MASK = 0b11;
    private static final int IDX_UNSCALED = 0b00;
    private static final int IDX_POST_INDEX = 0b01;
    private static final int IDX_REGISTER_OFFSET = 0b10;
    private static final int IDX_PRE_INDEX = 0b11;
    private static final int SINGLE_IMM9_SHIFT = 12;
    private static final int SINGLE_IMM9_BITS = 9;
    private static final int SINGLE_RM_SHIFT = 16;
    private static final int SINGLE_OPTION_SHIFT = 13;
    private static final int SINGLE_OPTION_MASK = 0b111;
    private static final int OPTION_UXTW = 0b010;
    private static final int OPTION_LSL = 0b011;
    private static final int OPTION_SXTW = 0b110;
    private static final int OPTION_SXTX = 0b111;
    private static final int SINGLE_SHIFT_FLAG_SHIFT = 12;

    // ── Data Processing — Register (ARM DDI 0487 C4.1, B6.3.1): bit27 fixo=1, bit25 fixo=1 ────
    // ── (mesma ambiguidade dos bits[28:26] que Loads-and-Stores já resolveu com bit25 fora do ──
    // ── switch de 3 bits — Fatos de referência #1 da task; distingue de Loads-and-Stores só ───
    // ── por bit25, e de Data Processing SIMD&FP por bits fora do escopo desta fatia). ─────────
    private static final int DP_REGISTER_CLASS_BIT27_SHIFT = 27;
    private static final int DP_REGISTER_CLASS_BIT25_SHIFT = 25;

    // ── Add/subtract (shifted/extended register), subgrupo de Data Processing — Register: ─────
    // ── bits[28:24] fixo=01011 nas duas formas; bit21 distingue shifted(0)/extended(1, com ────
    // ── bits[23:22]=00 fixo também) — Fatos de referência #4/#5 da task B6.3.1. ────────────────
    private static final int ADDSUB_REGISTER_GROUP_SHIFT = 24;
    private static final int ADDSUB_REGISTER_GROUP_5BIT_MASK = 0b1_1111;
    private static final int ADDSUB_REGISTER_GROUP_PATTERN = 0b01011;
    private static final int ADDSUB_REGISTER_EXTENDED_BIT_SHIFT = 21;
    private static final int ADDSUB_REGISTER_RM_SHIFT = 16;

    // ── Add/subtract (shifted register): shift(23:22) 0(21) Rm(20:16) imm6(15:10) Rn(9:5) ─────
    // ── Rd(4:0) ──────────────────────────────────────────────────────────────────────────────
    private static final int ADDSUB_SHIFTED_TYPE_SHIFT = 22;
    private static final int ADDSUB_SHIFTED_TYPE_MASK = 0b11;
    private static final int ADDSUB_SHIFTED_TYPE_LSL = 0b00;
    private static final int ADDSUB_SHIFTED_TYPE_LSR = 0b01;
    private static final int ADDSUB_SHIFTED_TYPE_ASR = 0b10;
    private static final int ADDSUB_SHIFTED_TYPE_RESERVED_ROR = 0b11;
    private static final int ADDSUB_SHIFTED_AMOUNT_SHIFT = 10;
    private static final int ADDSUB_SHIFTED_AMOUNT_MASK = 0b11_1111;
    /// Bit 5 do campo de quantidade (6 bits): setado significa quantidade `>= 32`, UNDEFINED
    /// quando a operação não é `wide` (`sf=0`) — só `0`-`31` é válido em `W`.
    private static final int ADDSUB_SHIFTED_AMOUNT_BIT5 = 1 << 5;

    // ── Add/subtract (extended register): bits[23:22]=00 fixo, Rm(20:16) option(15:13) ────────
    // ── imm3(12:10) Rn(9:5) Rd(4:0) ──────────────────────────────────────────────────────────
    private static final int ADDSUB_EXTENDED_FIXED_SHIFT = 22;
    private static final int ADDSUB_EXTENDED_FIXED_MASK = 0b11;
    private static final int ADDSUB_EXTENDED_FIXED_PATTERN = 0b00;
    private static final int ADDSUB_EXTENDED_OPTION_SHIFT = 13;
    private static final int ADDSUB_EXTENDED_OPTION_MASK = 0b111;
    private static final int ADDSUB_EXTENDED_AMOUNT_SHIFT = 10;
    private static final int ADDSUB_EXTENDED_AMOUNT_MASK = 0b111;
    private static final int ADDSUB_EXTENDED_MAX_SHIFT_AMOUNT = 4;

    // ── Conditional select (CSEL/CSINC/CSINV/CSNEG), subgrupo de Data Processing — Register ────
    // ── (B6.3.2): sf(31) else_inv(30) 011010100(29:21) rm(20:16) cond(15:12) 0(11) ─────────────
    // ── else_inc(10) rn(9:5) rd(4:0) — Fatos de referência #1 da task B6.3.2. ───────────────────
    private static final int CSEL_FIXED_SHIFT = 21;
    private static final int CSEL_FIXED_9BIT_MASK = 0b1_1111_1111;
    private static final int CSEL_FIXED_PATTERN = 0b0_1101_0100;
    private static final int CSEL_RESERVED_BIT11_MASK = 1 << 11;
    private static final int CSEL_ELSE_INV_SHIFT = 30;
    private static final int CSEL_COND_SHIFT = 12;
    private static final int CSEL_ELSE_INC_SHIFT = 10;

    // ── Data-processing (3 source): MADD/MSUB (B6.3.3): sf(31) 00(30:29) 11011000(28:21) ────────
    // ── Rm(20:16) o0(15) Ra(14:10) Rn(9:5) Rd(4:0) — Fatos de referência #1 da task. `op31`/──────
    // ── `op0` fixos distinguem de SMADDL/SMSUBL/UMADDL/UMSUBL (fora de escopo, ver Armadilhas). ──
    private static final int MULDIV_FIXED_SHIFT = 21;
    private static final int MULDIV_FIXED_8BIT_MASK = 0b1111_1111;
    private static final int MADD_MSUB_FIXED_PATTERN = 0b1101_1000;
    private static final int MADD_MSUB_O0_SHIFT = 15;
    private static final int MADD_MSUB_RA_SHIFT = 10;

    // ── Data-processing (2 source): SDIV/UDIV (B6.3.3): sf(31) 00(30:29) 11010110(28:21) ─────────
    // ── Rm(20:16) opcode(15:11)=00001 o1(10, 0=UDIV/1=SDIV) Rn(9:5) Rd(4:0) — Fatos de ───────────
    // ── referência #2 da task; opcode diferente distingue de LSLV/LSRV/ASRV/RORV/CRC32* (fora ────
    // ── de escopo, mesmo subgrupo). ────────────────────────────────────────────────────────────
    private static final int DIVIDE_FIXED_PATTERN = 0b1101_0110;
    private static final int DIVIDE_OPCODE_SHIFT = 11;
    private static final int DIVIDE_OPCODE_5BIT_MASK = 0b1_1111;
    private static final int DIVIDE_OPCODE_PATTERN = 0b0_0001;
    private static final int DIVIDE_SIGNED_BIT_SHIFT = 10;

    // ── Bitfield (SBFM/BFM/UBFM), subgrupo `10` de Data Processing Immediate (B6.3.2): ─────────
    // ── sf(31) opc(30:29) 100110(28:23) N(22) immr(21:16) imms(15:10) Rn(9:5) Rd(4:0) — MESMAS ──
    // ── posições de bit de Logical (immediate), reaproveitadas com nomes próprios (G6). ─────────
    private static final int BITFIELD_OPC_SHIFT = 29;
    private static final int BITFIELD_OPC_MASK = 0b11;
    private static final int BITFIELD_OPC_SBFM = 0b00;
    private static final int BITFIELD_OPC_BFM = 0b01;
    private static final int BITFIELD_OPC_UBFM = 0b10;
    private static final int BITFIELD_OPC_RESERVED_EXTR = 0b11;
    private static final int BITFIELD_N_SHIFT = 22;
    private static final int BITFIELD_IMMR_SHIFT = 16;
    private static final int BITFIELD_IMMS_SHIFT = 10;
    private static final int BITFIELD_FIELD_MASK = 0b11_1111;

    private static final int INSTRUCTION_SIZE_BYTES = 4;
    private static final int BYTES_PER_BRANCH_UNIT = 4;

    /// Decodifica a instrução de 4 bytes no endereço informado.
    ///
    /// @param memory barramento de onde a instrução é lida
    /// @param address endereço (múltiplo de 4) da instrução
    /// @return operação IR-64 correspondente
    /// @throws UnsupportedOperationException quando o encoding está fora da fatia B6.1
    public Ir64Op decode(AddressSpace64 memory, long address) {
        int word = memory.read32(address);
        // Loads and Stores (`x1x0`): bit27 fixo=1 e bit25 fixo=0 — único jeito de distinguir
        // esta classe do prefixo de 3 bits usado pelas outras (bit28 e bit26 são livres aqui,
        // então não cabe no switch de 3 bits abaixo sem risco de colisão com Data Processing
        // Register/SIMD&FP, que também têm bit27=1 mas bit25=1 — ver Aarch64Decoder javadoc).
        if (isLoadsAndStoresClass(word)) {
            return decodeLoadsAndStores(word, address);
        }
        // Data Processing — Register (B6.3.1): mesma ambiguidade de bits[28:26] que Loads-and-
        // Stores já resolveu com uma checagem própria fora do switch de 3 bits — precisa do
        // mesmo tratamento (bit27=1 && bit25=1, ver Fatos de referência #1 da task).
        if (isDataProcessingRegisterClass(word)) {
            return decodeDataProcessingRegister(word, address);
        }
        int topLevelClass = (word >>> TOP_LEVEL_CLASS_SHIFT) & TOP_LEVEL_CLASS_3BIT_MASK;
        return switch (topLevelClass) {
            case CLASS_DATA_PROCESSING_IMMEDIATE -> decodeDataProcessingImmediate(word, address);
            case CLASS_BRANCH_EXCEPTION_SYSTEM -> decodeBranchExceptionSystem(word, address);
            default -> throw unsupported(word, address);
        };
    }

    private static boolean isLoadsAndStoresClass(int word) {
        boolean bit27Set = ((word >>> LOAD_STORE_CLASS_BIT27_SHIFT) & 1) != 0;
        boolean bit25Clear = ((word >>> LOAD_STORE_CLASS_BIT25_SHIFT) & 1) == 0;
        return bit27Set && bit25Clear;
    }

    private static boolean isDataProcessingRegisterClass(int word) {
        boolean bit27Set = ((word >>> DP_REGISTER_CLASS_BIT27_SHIFT) & 1) != 0;
        boolean bit25Set = ((word >>> DP_REGISTER_CLASS_BIT25_SHIFT) & 1) != 0;
        return bit27Set && bit25Set;
    }

    private Ir64Op decodeLoadsAndStores(int word, long address) {
        if (((word >>> VECTOR_FORM_BIT_SHIFT) & 1) != 0) {
            // Load/store de registrador SIMD&FP (V=1): fora da fatia B6.2.
            throw unsupported(word, address);
        }
        int subclass = (word >>> LOAD_STORE_SUBCLASS_SHIFT) & LOAD_STORE_SUBCLASS_MASK;
        return switch (subclass) {
            case SUBCLASS_LITERAL -> decodeLoadLiteral(word, address);
            case SUBCLASS_PAIR -> decodeLoadStorePair(word, address);
            case SUBCLASS_SINGLE -> decodeLoadStoreSingle(word, address);
            // Load/store exclusivo e atômico (LDXR/STXR/CAS/LDAR/STLR...): fora da fatia B6.2.
            case SUBCLASS_EXCLUSIVE_ATOMIC -> throw unsupported(word, address);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private Ir64Op decodeLoadLiteral(int word, long address) {
        int opc = (word >>> LITERAL_OPC_SHIFT) & LITERAL_OPC_MASK;
        boolean wide;
        boolean signExtend;
        switch (opc) {
            case LITERAL_OPC_32BIT -> { wide = false; signExtend = false; }
            case LITERAL_OPC_64BIT -> { wide = true; signExtend = false; }
            case LITERAL_OPC_LDRSW -> { wide = true; signExtend = true; }
            // PRFM (literal, opc=11): não escreve registrador, fora da fatia B6.2.
            default -> throw unsupported(word, address);
        }
        long imm19 = (word >>> LITERAL_IMM19_SHIFT) & bitMask(LITERAL_IMM19_BITS);
        long offset = signExtend(imm19, LITERAL_IMM19_BITS) * LITERAL_BYTES_PER_UNIT;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.LoadLiteral64(rt, address + offset, wide, signExtend);
    }

    private Ir64Op decodeLoadStorePair(int word, long address) {
        int opc = (word >>> PAIR_OPC_SHIFT) & PAIR_OPC_MASK;
        boolean wide;
        if (opc == PAIR_OPC_64BIT) {
            wide = true;
        } else if (opc == PAIR_OPC_32BIT) {
            wide = false;
        } else {
            // opc=01 (LDPSW, sem forma STP) e opc=11 (reservado): fora da fatia B6.2.
            throw unsupported(word, address);
        }
        int addrModeField = (word >>> PAIR_ADDR_MODE_SHIFT) & PAIR_ADDR_MODE_MASK;
        Ir64AddressingMode addressingMode = switch (addrModeField) {
            case PAIR_ADDR_MODE_OFFSET -> Ir64AddressingMode.OFFSET;
            case PAIR_ADDR_MODE_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case PAIR_ADDR_MODE_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw unsupported(word, address); // 00: reservado (STGP/etc., fora de escopo)
        };
        boolean load = ((word >>> PAIR_LOAD_BIT_SHIFT) & 1) != 0;
        long imm7 = (word >>> PAIR_IMM7_SHIFT) & bitMask(PAIR_IMM7_BITS);
        int scale = wide ? PAIR_DOUBLEWORD_SCALE_BYTES : PAIR_WORD_SCALE_BYTES;
        long immediate = signExtend(imm7, PAIR_IMM7_BITS) * scale;
        int rt2 = (word >>> PAIR_RT2_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.LoadStorePair(load, rt, rt2, rn, wide, addressingMode, immediate);
    }

    private Ir64Op decodeLoadStoreSingle(int word, long address) {
        int sizeField = (word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK;
        int opcField = (word >>> SINGLE_OPC_SHIFT) & SINGLE_OPC_MASK;
        SingleForm form = decodeSingleForm(sizeField, opcField, word, address);
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;

        boolean scaledOffset = ((word >>> SINGLE_SCALED_OFFSET_BIT_SHIFT) & 1) != 0;
        if (scaledOffset) {
            int imm12 = (word >>> SINGLE_IMM12_SHIFT) & SINGLE_IMM12_MASK;
            long immediate = (long) imm12 * form.size.bytes();
            return buildSingle(form, rt, rn, Ir64AddressingMode.OFFSET, immediate, -1, null, 0);
        }
        int idx = (word >>> SINGLE_IDX_SHIFT) & SINGLE_IDX_MASK;
        if (idx == IDX_REGISTER_OFFSET) {
            int rm = (word >>> SINGLE_RM_SHIFT) & REGISTER_FIELD_MASK;
            int option = (word >>> SINGLE_OPTION_SHIFT) & SINGLE_OPTION_MASK;
            Ir64ExtendType extendType = switch (option) {
                case OPTION_UXTW -> Ir64ExtendType.UXTW;
                case OPTION_LSL -> Ir64ExtendType.LSL;
                case OPTION_SXTW -> Ir64ExtendType.SXTW;
                case OPTION_SXTX -> Ir64ExtendType.SXTX;
                default -> throw unsupported(word, address); // option reservado
            };
            boolean shiftFlag = ((word >>> SINGLE_SHIFT_FLAG_SHIFT) & 1) != 0;
            int shiftAmount = shiftFlag ? form.size.log2Bytes() : 0;
            return buildSingle(form, rt, rn, Ir64AddressingMode.REGISTER_OFFSET, 0, rm, extendType, shiftAmount);
        }
        Ir64AddressingMode addressingMode = switch (idx) {
            case IDX_UNSCALED -> Ir64AddressingMode.OFFSET;
            case IDX_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case IDX_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        int imm9 = (word >>> SINGLE_IMM9_SHIFT) & (int) bitMask(SINGLE_IMM9_BITS);
        long immediate = signExtend(imm9, SINGLE_IMM9_BITS);
        return buildSingle(form, rt, rn, addressingMode, immediate, -1, null, 0);
    }

    /// Resultado de `size`+`opc` já resolvidos em campos semânticos (`ARM DDI 0487 C4.1.3`,
    /// tabela de `LDR`/`STR`/`LDRB`/`LDRSB`/... por `size`/`opc`) — compartilhado pelas 4 formas
    /// de endereçamento de {@link #decodeLoadStoreSingle}.
    private record SingleForm(Ir64MemSize size, boolean store, boolean signExtend, boolean wide) {
    }

    private SingleForm decodeSingleForm(int sizeField, int opcField, int word, long address) {
        Ir64MemSize size = switch (sizeField) {
            case SIZE_BYTE -> Ir64MemSize.BYTE;
            case SIZE_HALF -> Ir64MemSize.HALF;
            case SIZE_WORD -> Ir64MemSize.WORD;
            case SIZE_DOUBLEWORD -> Ir64MemSize.DOUBLEWORD;
            default -> throw new IllegalStateException("unreachable");
        };
        if (sizeField == SIZE_WORD || sizeField == SIZE_DOUBLEWORD) {
            // `WORD`: opc=11 reservado (GP) — só existe LDRSW (10), não LDRSW-para-W.
            // `DOUBLEWORD`: opc=10/11 são a forma SIMD&FP de 128 bits, já excluída (V=0 aqui).
            if ((sizeField == SIZE_WORD && opcField == OPC_LOAD_SIGN_EXTEND_TO_W)
                    || (sizeField == SIZE_DOUBLEWORD
                            && (opcField == OPC_LOAD_SIGN_EXTEND_TO_X || opcField == OPC_LOAD_SIGN_EXTEND_TO_W))) {
                throw unsupported(word, address);
            }
        }
        return switch (opcField) {
            case OPC_STORE -> new SingleForm(size, true, false, size == Ir64MemSize.DOUBLEWORD);
            case OPC_LOAD_ZERO_EXTEND -> new SingleForm(size, false, false, size == Ir64MemSize.DOUBLEWORD);
            case OPC_LOAD_SIGN_EXTEND_TO_X -> new SingleForm(size, false, true, true);
            case OPC_LOAD_SIGN_EXTEND_TO_W -> new SingleForm(size, false, true, false);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private Ir64Op buildSingle(SingleForm form, int rt, int rn, Ir64AddressingMode addressingMode,
            long immediate, int rm, Ir64ExtendType extendType, int shiftAmount) {
        if (form.store) {
            return new Ir64Op.Store64(rt, rn, form.size, form.wide, addressingMode, immediate,
                    rm, extendType, shiftAmount);
        }
        return new Ir64Op.Load64(rt, rn, form.size, form.signExtend, form.wide, addressingMode,
                immediate, rm, extendType, shiftAmount);
    }

    private Ir64Op decodeDataProcessingImmediate(int word, long address) {
        if ((word & BIT_25) == 0) {
            return (word & BIT_24) == 0 ? decodePcRelative(word, address) : decodeAddSubImmediate(word);
        }
        int subgroup = (word >>> SUBGROUP_24_23_SHIFT) & SUBGROUP_24_23_MASK;
        if (subgroup == SUBGROUP_LOGICAL_IMMEDIATE) {
            return decodeLogicalImmediate(word, address);
        }
        if (subgroup == SUBGROUP_MOVE_WIDE) {
            return decodeMoveWide(word);
        }
        if (subgroup == SUBGROUP_BITFIELD) {
            return decodeBitfield(word, address);
        }
        // Extract (11, EXTR): fora do escopo fechado do épico B6 — ver Armadilhas da task B6.3.2.
        throw unsupported(word, address);
    }

    /// `SBFM`/`BFM`/`UBFM` (D2 da task B6.3.2): produz {@link Ir64Op.Bitfield} sempre a partir dos
    /// campos crus `immr`/`imms` — nenhum dos 11 aliases do épico (`UBFX`/`SBFX`/`BFI`/`BFXIL`/
    /// `LSL`/`LSR`/`ASR`/`UXTB`/`UXTH`/`SXTB`/`SXTH`/`SXTW`) exige reconhecimento aqui, só valores
    /// específicos desses campos que o assembler já resolveu (Fatos de referência #2 da task).
    private Ir64Op decodeBitfield(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int opc = (word >>> BITFIELD_OPC_SHIFT) & BITFIELD_OPC_MASK;
        if (opc == BITFIELD_OPC_RESERVED_EXTR) {
            // opc=11 é EXTR (mesmo subgrupo, fora de escopo) — ver Armadilhas da task B6.3.2.
            throw unsupported(word, address);
        }
        int n = (word >>> BITFIELD_N_SHIFT) & 1;
        if (n != (wide ? 1 : 0)) {
            // N deve ser igual a sf (mesma regra de Logical (immediate), Fatos de referência #2
            // da task B6.3.1) — combinação contrária é UNDEFINED.
            throw unsupported(word, address);
        }
        Ir64BitfieldOp opcode = switch (opc) {
            case BITFIELD_OPC_SBFM -> Ir64BitfieldOp.SBFM;
            case BITFIELD_OPC_BFM -> Ir64BitfieldOp.BFM;
            case BITFIELD_OPC_UBFM -> Ir64BitfieldOp.UBFM;
            default -> throw new IllegalStateException("unreachable");
        };
        int immr = (word >>> BITFIELD_IMMR_SHIFT) & BITFIELD_FIELD_MASK;
        int imms = (word >>> BITFIELD_IMMS_SHIFT) & BITFIELD_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Bitfield(opcode, rd, rn, immr, imms, wide);
    }

    private Ir64Op decodeLogicalImmediate(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int n = (word >>> LOGICAL_IMM_N_SHIFT) & 1;
        if (n == 1 && !wide) {
            // N=1 (elemento de 64 bits) não existe em operação W (sf=0) — UNDEFINED, ver
            // Fatos de referência #2 da task B6.3.1.
            throw unsupported(word, address);
        }
        int immr = (word >>> LOGICAL_IMM_IMMR_SHIFT) & LOGICAL_IMM_FIELD_MASK;
        int imms = (word >>> LOGICAL_IMM_IMMS_SHIFT) & LOGICAL_IMM_FIELD_MASK;
        long immediate = Aarch64LogicalImmediate.decodeBitMasks(n, imms, immr);
        int opc = (word >>> LOGICAL_IMM_OPC_SHIFT) & LOGICAL_IMM_OPC_MASK;
        Ir64AluOp opcode = switch (opc) {
            case LOGICAL_IMM_OPC_AND, LOGICAL_IMM_OPC_ANDS -> Ir64AluOp.AND;
            case LOGICAL_IMM_OPC_ORR -> Ir64AluOp.ORR;
            case LOGICAL_IMM_OPC_EOR -> Ir64AluOp.EOR;
            default -> throw new IllegalStateException("unreachable");
        };
        boolean setFlags = opc == LOGICAL_IMM_OPC_ANDS;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        // AND/ORR/EOR (imediato) NUNCA têm forma SP em Rd/Rn (diferente de ADD/SUB imediato) —
        // decisão D2 da task B6.3.1, setado explicitamente aqui (não deixado implícito).
        return new Ir64Op.Alu64(opcode, rd, rn, immediate, wide, setFlags, false, false);
    }

    private Ir64Op decodePcRelative(int word, long address) {
        boolean page = ((word >>> PC_REL_OP_SHIFT) & 1) != 0;
        int immlo = (word >>> PC_REL_IMMLO_SHIFT) & PC_REL_IMMLO_MASK;
        int immhi = (word >>> PC_REL_IMMHI_SHIFT) & (int) bitMask(PC_REL_IMMHI_BITS);
        int rawImm = (immhi << 2) | immlo;
        long imm = signExtend(rawImm, PC_REL_IMM_TOTAL_BITS);
        int rd = word & REGISTER_FIELD_MASK;
        long immediate = page ? (imm << ADRP_PAGE_SHIFT) : imm;
        return new Ir64Op.PcRelative(rd, address, immediate, page);
    }

    private Ir64Op decodeAddSubImmediate(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean isSub = ((word >>> ADD_SUB_OP_SHIFT) & 1) != 0;
        boolean setFlags = ((word >>> SET_FLAGS_SHIFT) & 1) != 0;
        int shiftField = (word >>> ADD_SUB_SHIFT_FIELD_SHIFT) & ADD_SUB_SHIFT_FIELD_MASK;
        long imm12 = (word >>> IMM12_SHIFT) & IMM12_MASK;
        long immediate = shiftField == ADD_SUB_SHIFT_LSL_12 ? (imm12 << 12) : imm12;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        // ARM DDI 0487 C6.2.4/C6.2.339: sem `S` (ADD/SUB), Rd|SP; com `S` (ADDS/SUBS), Rd é
        // sempre um registrador normal (ZR quando 31). Rn é sempre Rn|SP nas duas formas.
        boolean dstIsStackPointer = !setFlags;
        return new Ir64Op.Alu64(
                isSub ? Ir64AluOp.SUB : Ir64AluOp.ADD, rd, rn, immediate, wide, setFlags,
                dstIsStackPointer, true);
    }

    private Ir64Op decodeMoveWide(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int opc = (word >>> MOVE_WIDE_OPC_SHIFT) & MOVE_WIDE_OPC_MASK;
        Ir64MoveWideOp opcode = switch (opc) {
            case MOVE_WIDE_OPC_MOVN -> Ir64MoveWideOp.MOVN;
            case MOVE_WIDE_OPC_MOVZ -> Ir64MoveWideOp.MOVZ;
            case MOVE_WIDE_OPC_MOVK -> Ir64MoveWideOp.MOVK;
            default -> throw new UnsupportedOperationException(
                    "AArch64: move-wide opc reservado (01): 0x" + Integer.toHexString(word));
        };
        int hw = (word >>> MOVE_WIDE_HW_SHIFT) & MOVE_WIDE_HW_MASK;
        int shift = hw * MOVE_WIDE_HW_UNIT_BITS;
        int imm16 = (word >>> IMM16_SHIFT) & IMM16_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.MoveWide(opcode, rd, imm16, shift, wide);
    }

    /// Sub-dispatch da classe "Data Processing — Register" (D1 da task B6.3.1, estendido por
    /// B6.3.2 com `CSEL`/`CSINC`/`CSINV`/`CSNEG`): `2-source`/`3-source` (B6.3.3) entram como
    /// `case`s adicionais aqui, adicionados por essa task subsequente. `Logical (shifted
    /// register)` fica fora do escopo fechado do épico B6 (não implementar — ver a task).
    private Ir64Op decodeDataProcessingRegister(int word, long address) {
        int addSubGroup = (word >>> ADDSUB_REGISTER_GROUP_SHIFT) & ADDSUB_REGISTER_GROUP_5BIT_MASK;
        if (addSubGroup == ADDSUB_REGISTER_GROUP_PATTERN) {
            boolean extendedForm = ((word >>> ADDSUB_REGISTER_EXTENDED_BIT_SHIFT) & 1) != 0;
            return extendedForm
                    ? decodeAddSubExtendedRegister(word, address)
                    : decodeAddSubShiftedRegister(word, address);
        }
        int fixed9 = (word >>> CSEL_FIXED_SHIFT) & CSEL_FIXED_9BIT_MASK;
        boolean reservedBit11Clear = (word & CSEL_RESERVED_BIT11_MASK) == 0;
        if (fixed9 == CSEL_FIXED_PATTERN && reservedBit11Clear) {
            return decodeConditionalSelect(word);
        }
        int muldivFixed8 = (word >>> MULDIV_FIXED_SHIFT) & MULDIV_FIXED_8BIT_MASK;
        if (muldivFixed8 == MADD_MSUB_FIXED_PATTERN) {
            return decodeMultiplyAccumulate(word);
        }
        if (muldivFixed8 == DIVIDE_FIXED_PATTERN) {
            int divideOpcode = (word >>> DIVIDE_OPCODE_SHIFT) & DIVIDE_OPCODE_5BIT_MASK;
            if (divideOpcode == DIVIDE_OPCODE_PATTERN) {
                return decodeDivide(word);
            }
            // opcode != 00001: LSLV/LSRV/ASRV/RORV/CRC32* (fora do escopo fechado do épico).
            throw unsupported(word, address);
        }
        // Logical (shifted register): fora do escopo fechado do épico (ver a task B6.3.1).
        throw unsupported(word, address);
    }

    /// `MADD`/`MSUB` (B6.3.3) — `MUL`/`MNEG` (aliases com `Ra=XZR`) chegam aqui como o mesmo
    /// opcode geral, sem `case` dedicado (Fatos de referência #1 da task).
    private Ir64Op decodeMultiplyAccumulate(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean subtract = ((word >>> MADD_MSUB_O0_SHIFT) & 1) != 0;
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int ra = (word >>> MADD_MSUB_RA_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.MultiplyAccumulate(subtract, rd, rn, rm, ra, wide);
    }

    /// `SDIV`/`UDIV` (B6.3.3).
    private Ir64Op decodeDivide(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean signed = ((word >>> DIVIDE_SIGNED_BIT_SHIFT) & 1) != 0;
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Divide(signed, rd, rn, rm, wide);
    }

    /// `CSEL`/`CSINC`/`CSINV`/`CSNEG` (D1 da task B6.3.2) — o opcode é resolvido só pelos bits
    /// `else_inv`(30)/`else_inc`(10), nunca reconhecido por alias (`CSET`/`CSETM`/`CINC`/`CINV`/
    /// `CNEG` chegam aqui como o opcode real de 4 combinações, com `src1`/`src2` já coincidentes
    /// ou `==31` e a condição já invertida pelo PRÓPRIO assembler — ver Fatos de referência #1).
    private Ir64Op decodeConditionalSelect(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean elseInv = ((word >>> CSEL_ELSE_INV_SHIFT) & 1) != 0;
        boolean elseInc = ((word >>> CSEL_ELSE_INC_SHIFT) & 1) != 0;
        Ir64ConditionalSelectOp opcode;
        if (!elseInv && !elseInc) {
            opcode = Ir64ConditionalSelectOp.CSEL;
        } else if (!elseInv) {
            opcode = Ir64ConditionalSelectOp.CSINC;
        } else if (!elseInc) {
            opcode = Ir64ConditionalSelectOp.CSINV;
        } else {
            opcode = Ir64ConditionalSelectOp.CSNEG;
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        Ir64Condition condition = Ir64Condition.decode((word >>> CSEL_COND_SHIFT) & COND_FIELD_MASK);
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.ConditionalSelect(opcode, rd, rn, rm, wide, condition);
    }

    private Ir64Op decodeAddSubShiftedRegister(int word, long address) {
        int shiftTypeField = (word >>> ADDSUB_SHIFTED_TYPE_SHIFT) & ADDSUB_SHIFTED_TYPE_MASK;
        if (shiftTypeField == ADDSUB_SHIFTED_TYPE_RESERVED_ROR) {
            // st=11 (ROR) é reservado para ADD/SUB — só existe em Logical (shifted register),
            // fora de escopo (Fatos de referência #4 da task).
            throw unsupported(word, address);
        }
        Ir64ShiftType shiftType = switch (shiftTypeField) {
            case ADDSUB_SHIFTED_TYPE_LSL -> Ir64ShiftType.LSL;
            case ADDSUB_SHIFTED_TYPE_LSR -> Ir64ShiftType.LSR;
            case ADDSUB_SHIFTED_TYPE_ASR -> Ir64ShiftType.ASR;
            default -> throw new IllegalStateException("unreachable");
        };
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int shiftAmount = (word >>> ADDSUB_SHIFTED_AMOUNT_SHIFT) & ADDSUB_SHIFTED_AMOUNT_MASK;
        if (!wide && (shiftAmount & ADDSUB_SHIFTED_AMOUNT_BIT5) != 0) {
            // sf=0 (W) só aceita quantidade 0-31 — bit5 setado significa >= 32, UNDEFINED.
            throw unsupported(word, address);
        }
        boolean isSub = ((word >>> ADD_SUB_OP_SHIFT) & 1) != 0;
        boolean setFlags = ((word >>> SET_FLAGS_SHIFT) & 1) != 0;
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.AluShiftedRegister(
                isSub ? Ir64AluOp.SUB : Ir64AluOp.ADD, rd, rn, rm, shiftType, shiftAmount, wide, setFlags);
    }

    private Ir64Op decodeAddSubExtendedRegister(int word, long address) {
        int fixedField = (word >>> ADDSUB_EXTENDED_FIXED_SHIFT) & ADDSUB_EXTENDED_FIXED_MASK;
        if (fixedField != ADDSUB_EXTENDED_FIXED_PATTERN) {
            // bits[23:22] != 00 com bit21=1: não é Add/subtract (extended register) — combinação
            // reservada dentro do subgrupo que esta task cobre.
            throw unsupported(word, address);
        }
        int shiftAmount = (word >>> ADDSUB_EXTENDED_AMOUNT_SHIFT) & ADDSUB_EXTENDED_AMOUNT_MASK;
        if (shiftAmount > ADDSUB_EXTENDED_MAX_SHIFT_AMOUNT) {
            // sa > 4 é UNDEFINED (campo tem 3 bits, cabe até 7, mas 5-7 são reservados) —
            // Fatos de referência #5 da task.
            throw unsupported(word, address);
        }
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean isSub = ((word >>> ADD_SUB_OP_SHIFT) & 1) != 0;
        boolean setFlags = ((word >>> SET_FLAGS_SHIFT) & 1) != 0;
        int option = (word >>> ADDSUB_EXTENDED_OPTION_SHIFT) & ADDSUB_EXTENDED_OPTION_MASK;
        Ir64AluExtendType extendType = switch (option) {
            case 0b000 -> Ir64AluExtendType.UXTB;
            case 0b001 -> Ir64AluExtendType.UXTH;
            case 0b010 -> Ir64AluExtendType.UXTW;
            case 0b011 -> Ir64AluExtendType.UXTX;
            case 0b100 -> Ir64AluExtendType.SXTB;
            case 0b101 -> Ir64AluExtendType.SXTH;
            case 0b110 -> Ir64AluExtendType.SXTW;
            case 0b111 -> Ir64AluExtendType.SXTX;
            default -> throw new IllegalStateException("unreachable");
        };
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        // ARM DDI 0487 C6.2.4/C6.2.339 (extended): Rn é SEMPRE Rn|SP; Rd é Rd|SP só sem `S`
        // (mesma regra da forma imediata, ver decodeAddSubImmediate) — resolvido pelo EXECUTOR
        // checando o índice, nunca incondicionalmente (ver Ir64Op.AluExtendedRegister javadoc).
        boolean dstIsStackPointer = !setFlags;
        return new Ir64Op.AluExtendedRegister(
                isSub ? Ir64AluOp.SUB : Ir64AluOp.ADD, rd, rn, rm, extendType, shiftAmount, wide,
                setFlags, dstIsStackPointer);
    }

    private Ir64Op decodeBranchExceptionSystem(int word, long address) {
        int fiveBitSubgroup = (word >>> BRANCH_SUBGROUP_SHIFT) & BRANCH_SUBGROUP_5BIT_MASK;
        if (fiveBitSubgroup == SUBGROUP_UNCONDITIONAL_BRANCH_IMM) {
            return decodeUnconditionalBranchImmediate(word, address);
        }
        int sevenBitFixed = (word >>> COND_BRANCH_FIXED_SHIFT) & COND_BRANCH_FIXED_7BIT_MASK;
        if (sevenBitFixed == COND_BRANCH_FIXED_PATTERN
                && (word & COND_BRANCH_O1_BIT) == 0
                && (word & COND_BRANCH_O0_BIT) == 0) {
            return decodeConditionalBranchImmediate(word, address);
        }
        int sixBitFixed = (word >>> COMPARE_BRANCH_FIXED_SHIFT) & COMPARE_BRANCH_FIXED_6BIT_MASK;
        if (sixBitFixed == CBZ_FIXED_PATTERN) {
            return decodeCompareBranch(word, address);
        }
        if (sixBitFixed == TBZ_FIXED_PATTERN) {
            return decodeTestBranch(word, address);
        }
        int branchRegisterFixed = (word >>> BRANCH_REGISTER_FIXED_SHIFT) & BRANCH_REGISTER_FIXED_7BIT_MASK;
        if (branchRegisterFixed == BRANCH_REGISTER_FIXED_PATTERN) {
            return decodeBranchRegister(word, address);
        }
        int exceptionGenFixed = (word >>> EXCEPTION_GEN_FIXED_SHIFT) & EXCEPTION_GEN_FIXED_8BIT_MASK;
        if (exceptionGenFixed == EXCEPTION_GEN_FIXED_PATTERN) {
            return decodeExceptionGenerating(word, address);
        }
        // System instructions (barreiras, hints, MSR/MRS...) e demais formas do grupo Branch/
        // Exception/System: fora da fatia B6.1.
        throw unsupported(word, address);
    }

    private Ir64Op decodeUnconditionalBranchImmediate(int word, long address) {
        boolean link = ((word >>> PC_REL_OP_SHIFT) & 1) != 0;
        long imm26 = word & bitMask(IMM26_BITS);
        long offset = signExtend(imm26, IMM26_BITS) * BYTES_PER_BRANCH_UNIT;
        long target = address + offset;
        return new Ir64Op.Branch64(
                Ir64BranchForm.IMMEDIATE, address, target, -1, link, Ir64Condition.AL);
    }

    private Ir64Op decodeConditionalBranchImmediate(int word, long address) {
        long imm19 = (word >>> IMM19_SHIFT) & bitMask(IMM19_BITS);
        long offset = signExtend(imm19, IMM19_BITS) * BYTES_PER_BRANCH_UNIT;
        long target = address + offset;
        Ir64Condition condition = Ir64Condition.decode(word & COND_FIELD_MASK);
        return new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, address, target, -1, false, condition);
    }

    private Ir64Op decodeCompareBranch(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean branchIfNonZero = (word & BIT_24) != 0;
        long imm19 = (word >>> IMM19_SHIFT) & bitMask(IMM19_BITS);
        long offset = signExtend(imm19, IMM19_BITS) * BYTES_PER_BRANCH_UNIT;
        long target = address + offset;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.CompareBranch64(
                Ir64CompareBranchForm.CBZ_CBNZ, rt, wide, -1, branchIfNonZero, target);
    }

    private Ir64Op decodeTestBranch(int word, long address) {
        int b5 = (word >>> TBZ_B5_SHIFT) & 1;
        int b40 = (word >>> TBZ_B40_SHIFT) & TBZ_B40_MASK;
        int bitPosition = (b5 << TBZ_B40_BITS) | b40;
        boolean branchIfNonZero = (word & BIT_24) != 0;
        long imm14 = (word >>> IMM14_SHIFT) & bitMask(IMM14_BITS);
        long offset = signExtend(imm14, IMM14_BITS) * BYTES_PER_BRANCH_UNIT;
        long target = address + offset;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.CompareBranch64(
                Ir64CompareBranchForm.TBZ_TBNZ, rt, true, bitPosition, branchIfNonZero, target);
    }

    private Ir64Op decodeBranchRegister(int word, long address) {
        int op2 = (word >>> BRANCH_REGISTER_OP2_SHIFT) & BRANCH_REGISTER_OP2_MASK;
        int op3 = (word >>> BRANCH_REGISTER_OP3_SHIFT) & BRANCH_REGISTER_OP3_MASK;
        int op4 = word & BRANCH_REGISTER_OP4_MASK;
        if (op2 != BRANCH_REGISTER_OP2_FIXED || op3 != BRANCH_REGISTER_OP3_FIXED
                || op4 != BRANCH_REGISTER_OP4_FIXED) {
            // ERET/DRPS ou combinação reservada: fora da fatia B6.1.
            throw unsupported(word, address);
        }
        int opc = (word >>> BRANCH_REGISTER_OPC_SHIFT) & BRANCH_REGISTER_OPC_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        boolean link = switch (opc) {
            case BRANCH_REGISTER_OPC_BR, BRANCH_REGISTER_OPC_RET -> false;
            case BRANCH_REGISTER_OPC_BLR -> true;
            default -> throw unsupported(word, address);
        };
        return new Ir64Op.Branch64(
                Ir64BranchForm.REGISTER, address, 0L, rn, link, Ir64Condition.AL);
    }

    private Ir64Op decodeExceptionGenerating(int word, long address) {
        int opc = (word >>> EXCEPTION_GEN_OPC_SHIFT) & EXCEPTION_GEN_OPC_MASK;
        int low5 = word & EXCEPTION_GEN_LOW5_MASK;
        if (opc != EXCEPTION_GEN_OPC_SVC || low5 != EXCEPTION_GEN_SVC_LOW5_FIXED) {
            // HVC/SMC/BRK/HLT/DCPS*: fora da fatia B6.1.
            throw unsupported(word, address);
        }
        int imm16 = (word >>> IMM16_SHIFT) & IMM16_MASK;
        return new Ir64Op.Svc(imm16);
    }

    private static long signExtend(long value, int bits) {
        long signBit = 1L << (bits - 1);
        return (value ^ signBit) - signBit;
    }

    private static long bitMask(int bits) {
        return (1L << bits) - 1;
    }

    private static UnsupportedOperationException unsupported(int word, long address) {
        return new UnsupportedOperationException(
                "AArch64: encoding fora da fatia B6.1 em 0x" + Long.toHexString(address)
                        + ": 0x" + Integer.toHexString(word));
    }

    /// Marcador de tamanho fixo de instrução A64, exposto para os chamadores que precisam avançar
    /// o PC sem re-hardcodar o literal `4`.
    public static int instructionSizeBytes() {
        return INSTRUCTION_SIZE_BYTES;
    }
}
