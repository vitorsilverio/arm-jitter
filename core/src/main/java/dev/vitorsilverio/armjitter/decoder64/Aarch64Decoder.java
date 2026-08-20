package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
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
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Decodifica instruções AArch64 (A64) para {@link Ir64Op} — fatia B6.1 + B6.2 + B6.3.1 + B6.3.2 +
/// B6.3.3 + B6.3.4: os grupos `data-processing immediate` (`ADD`/`SUB`/`AND`/`ORR`/`EOR`/`ANDS`
/// imediato, `MOVZ`/`MOVN`/`MOVK`, `ADR`/`ADRP`, `SBFM`/`BFM`/`UBFM` — B6.3.2), `data-processing
/// register` (`ADD`/`SUB`/`ADDS`/`SUBS` shifted e extended register — B6.3.1; `CSEL`/`CSINC`/
/// `CSINV`/`CSNEG` — B6.3.2; `MADD`/`MSUB`/`SDIV`/`UDIV` — B6.3.3), `branches/exception/system`
/// (`B`/`BL`/`B.cond`/`CBZ`/`CBNZ`/`TBZ`/`TBNZ`/`BR`/`BLR`/`RET`/`SVC`) e `loads and stores` de
/// registrador geral (`LDR`/`STR`/`LDUR`/`STUR`/`LDP`/`STP`/`LDR (literal)`, tamanhos B/H/W/X +
/// sign-extend, pre/post-index, registrador+extend — B6.2; `LDXR`/`LDAXR`/`STXR`/`STLXR` — B6.3.4)
/// — ver `tasks/trilha-b-arquiteturas/b6-aarch64.md` §B6.1-§B6.3. Todos os campos de bit abaixo
/// foram verificados byte a byte contra a saída real de `aarch64-none-elf-as`/`objdump`
/// (devkitA64) — ver o corpus versionado em `src/test/resources/aarch64/corpus.s`.
///
/// `Extract` (`EXTR`, mesmo subgrupo de `Bitfield` dentro de `Data Processing Immediate`),
/// `Logical (shifted register)` (mesma classe `Data Processing Register`), `LDXP`/`STXP`/`CAS`/
/// `LDAR`/`STLR` (mesmo subgrupo de exclusivo/atômico de `LDXR`/`STXR`, ver B6.3.4), load/store de
/// registrador SIMD&FP (`V=1`) e data-processing SIMD&FP ficam FORA do escopo fechado do épico B6
/// — qualquer encoding fora do escopo listado lança {@link UnsupportedOperationException} em vez
/// de tentar adivinhar semântica (nenhum oráculo real cobre o que não foi implementado).
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
    /// `ERET` (`ARM DDI 0487 C6.2.111`, task B6.6.4) — mesmo formato fixo de `op2`/`op3`/`op4` de
    /// `BR`/`BLR`/`RET`, mas com `Rn` (bits 9:5) TAMBÉM fixo em `11111` (não um registrador
    /// variável — CONFERIDO via `aarch64-none-elf-as`: `eret` monta para `0xD69F03E0`).
    private static final int BRANCH_REGISTER_OPC_ERET = 0b0100;
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
    /// `HVC` (B6.6.7): mesmo `opc=000` de `SVC`/`SMC`, `LL(1:0)=10` — `opc2(4:2)=000` fixo, então
    /// `low5 = (opc2<<2)|LL = 0b00010` (CONFERIDO via `aarch64-none-elf-as`: `hvc #0`).
    private static final int EXCEPTION_GEN_HVC_LOW5_FIXED = 0b0_0010;
    /// `SMC` (B6.6.7): `LL(1:0)=11`, mesmo raciocínio de {@link #EXCEPTION_GEN_HVC_LOW5_FIXED}
    /// (CONFERIDO via `aarch64-none-elf-as`: `smc #0`).
    private static final int EXCEPTION_GEN_SMC_LOW5_FIXED = 0b0_0011;

    // ── MRS/MSR (register)/SYS family (B6.6.1 + B6.6.3, ARM DDI 0487 C5.2.3 / QEMU a64.decode
    // ── `SYS`): prefixo fixo(31:22)=1101010100 L(21) op0(20:19) op1(18:16) CRn(15:12) CRm(11:8)
    // ── op2(7:5) Rt(4:0). `op0` distingue 3 subgrupos: `0b00` = barreiras/hints/MSR-imediato
    // ── (B6.6.3 só decodifica `DSB`/`ISB`/`DMB`, resto fora de escopo), `0b01` = `SYS`/`SYSL`
    // ── (B6.6.3 só decodifica `TLBI VMALLE1`/`VMALLE1IS`), `0b10`/`0b11` = `MRS`/`MSR` (B6.6.1,
    // ── ver decodeSystemRegisterId).
    private static final int SYSTEM_REGISTER_FIXED_SHIFT = 22;
    private static final int SYSTEM_REGISTER_FIXED_10BIT_MASK = 0b11_1111_1111;
    private static final int SYSTEM_REGISTER_FIXED_PATTERN = 0b11_0101_0100;
    private static final int SYSTEM_REGISTER_L_SHIFT = 21;
    private static final int SYSTEM_REGISTER_OP0_SHIFT = 19;
    private static final int SYSTEM_REGISTER_OP0_MASK = 0b11;
    private static final int SYSTEM_REGISTER_OP1_SHIFT = 16;
    private static final int SYSTEM_REGISTER_OP1_MASK = 0b111;
    private static final int SYSTEM_REGISTER_CRN_SHIFT = 12;
    private static final int SYSTEM_REGISTER_CRN_MASK = 0b1111;
    private static final int SYSTEM_REGISTER_CRM_SHIFT = 8;
    private static final int SYSTEM_REGISTER_CRM_MASK = 0b1111;
    private static final int SYSTEM_REGISTER_OP2_SHIFT = 5;
    private static final int SYSTEM_REGISTER_OP2_MASK = 0b111;

    // ── Registradores de sistema cobertos (Fatos de referência #2 da task B6.6.1): todos
    // ── `op0=3`/`op1=0` (registradores "gerais" de EL1) — valores conferidos contra
    // ── `aarch64-none-elf-as`/`objdump` reais (devkitA64), ver corpus.
    private static final int SYSREG_OP0_EL1 = 3;
    private static final int SYSREG_OP1_EL1 = 0;
    private static final int SYSREG_CRN_SCTLR = 1;
    private static final int SYSREG_CRM_SCTLR = 0;
    private static final int SYSREG_OP2_SCTLR = 0;
    private static final int SYSREG_CRN_TTBR0 = 2;
    private static final int SYSREG_CRM_TTBR0 = 0;
    private static final int SYSREG_OP2_TTBR0 = 0;
    private static final int SYSREG_CRN_TCR = 2;
    private static final int SYSREG_CRM_TCR = 0;
    private static final int SYSREG_OP2_TCR = 2;
    private static final int SYSREG_CRN_MAIR = 10;
    private static final int SYSREG_CRM_MAIR = 2;
    private static final int SYSREG_OP2_MAIR = 0;
    private static final int SYSREG_CRN_ESR = 5;
    private static final int SYSREG_CRM_ESR = 2;
    private static final int SYSREG_OP2_ESR = 0;
    private static final int SYSREG_CRN_FAR = 6;
    private static final int SYSREG_CRM_FAR = 0;
    private static final int SYSREG_OP2_FAR = 0;
    private static final int SYSREG_CRN_VBAR = 12;
    private static final int SYSREG_CRM_VBAR = 0;
    private static final int SYSREG_OP2_VBAR = 0;
    private static final int SYSREG_CRN_ELR = 4;
    private static final int SYSREG_CRM_ELR = 0;
    private static final int SYSREG_OP2_ELR = 1;
    private static final int SYSREG_CRN_SPSR = 4;
    private static final int SYSREG_CRM_SPSR = 0;
    private static final int SYSREG_OP2_SPSR = 0;

    // ── B6.6.7: identidade da CPU, ainda `op0=3`/`op1=0` (mesma tabela EL1 "geral" acima) —
    // ── valores conferidos contra `aarch64-none-elf-as`/`objdump` reais (devkitA64), ver corpus.
    private static final int SYSREG_CRN_CURRENT_EL = 4;
    private static final int SYSREG_CRM_CURRENT_EL = 2;
    private static final int SYSREG_OP2_CURRENT_EL = 2;
    private static final int SYSREG_CRN_MPIDR = 0;
    private static final int SYSREG_CRM_MPIDR = 0;
    private static final int SYSREG_OP2_MPIDR = 5;
    private static final int SYSREG_CRN_MIDR = 0;
    private static final int SYSREG_CRM_MIDR = 0;
    private static final int SYSREG_OP2_MIDR = 0;
    private static final int SYSREG_CRN_ID_AA64PFR0 = 0;
    private static final int SYSREG_CRM_ID_AA64PFR0 = 4;
    private static final int SYSREG_OP2_ID_AA64PFR0 = 0;
    private static final int SYSREG_CRN_ID_AA64ISAR0 = 0;
    private static final int SYSREG_CRM_ID_AA64ISAR0 = 6;
    private static final int SYSREG_OP2_ID_AA64ISAR0 = 0;
    private static final int SYSREG_CRN_ID_AA64MMFR0 = 0;
    private static final int SYSREG_CRM_ID_AA64MMFR0 = 7;
    private static final int SYSREG_OP2_ID_AA64MMFR0 = 0;
    private static final int SYSREG_CRN_ID_AA64DFR0 = 0;
    private static final int SYSREG_CRM_ID_AA64DFR0 = 5;
    private static final int SYSREG_OP2_ID_AA64DFR0 = 0;
    private static final int SYSREG_CRN_TPIDR_EL1 = 13;
    private static final int SYSREG_CRM_TPIDR_EL1 = 0;
    private static final int SYSREG_OP2_TPIDR_EL1 = 4;

    // ── B6.6.7: timer genérico, `op0=3`/`op1=3` (registradores acessíveis de EL0, "CNT*_EL0" —
    // ── diferente do resto da tabela, que é toda `op1=0`), CRn=0b1110 fixo (grupo Generic Timer).
    private static final int SYSREG_OP1_EL0_TIMER = 3;
    private static final int SYSREG_CRN_TIMER = 14;
    private static final int SYSREG_CRM_CNTFRQ = 0;
    private static final int SYSREG_OP2_CNTFRQ = 0;
    private static final int SYSREG_CRM_CNTPCT = 0;
    private static final int SYSREG_OP2_CNTPCT = 1;
    private static final int SYSREG_CRM_CNTP = 2;
    private static final int SYSREG_OP2_CNTP_TVAL = 0;
    private static final int SYSREG_OP2_CNTP_CTL = 1;
    private static final int SYSREG_OP2_CNTP_CVAL = 2;

    // ── `op0` do grupo System (B6.6.3): valores fixos que selecionam o subgrupo (op0=2/3 são
    // ── MRS/MSR, tratados por SYSREG_OP0_EL1 acima).
    private static final int SYSTEM_INSTRUCTION_OP0_BARRIER = 0;
    private static final int SYSTEM_INSTRUCTION_OP0_SYS = 1;

    // ── Barreiras (`op0=0`, CRn=0b0011 fixo — "Barriers" no grupo hint/barreira/CLREX/PSTATE-imm)
    // ── valores conferidos contra `aarch64-none-elf-as`/`objdump` reais (devkitA64), ver corpus.
    private static final int SYSTEM_INSTRUCTION_BARRIER_CRN = 0b0011;
    private static final int SYSTEM_INSTRUCTION_BARRIER_OP2_DSB = 0b100;
    private static final int SYSTEM_INSTRUCTION_BARRIER_OP2_DMB = 0b101;
    private static final int SYSTEM_INSTRUCTION_BARRIER_OP2_ISB = 0b110;

    // ── B6.6.7: "Hints" (`op0=0`, CRn=0b0010 fixo — mesmo subgrupo de encoding das barreiras
    // ── acima, `CRm`(11:8) reservado/`RES0` na forma canônica de cada hint, não checado aqui,
    // ── mesma simplificação já aplicada às barreiras) — `NOP`/`YIELD`/`SEV`/`SEVL` viram NOP puro
    // ── (mesmo tratamento das barreiras); `WFE` também NOP nesta task (sem event-stream modelado,
    // ── ver a task B6.6.7 "Não inclui"); só `WFI` tem semântica própria (durma até IRQ).
    private static final int SYSTEM_INSTRUCTION_HINT_CRN = 0b0010;
    private static final int SYSTEM_INSTRUCTION_HINT_OP2_WFI = 0b011;

    // ── `TLBI VMALLE1`/`TLBI VMALLE1IS` (`op0=1`, `SYS` — não `SYSL`, `L=0`): op1=EL1 "geral",
    // ── CRn=0b1000 fixo (grupo TLB maintenance), CRm distingue IS/não-IS, op2=0 (ALL, sem VA).
    private static final int SYSTEM_INSTRUCTION_TLBI_OP1_EL1 = 0b000;
    private static final int SYSTEM_INSTRUCTION_TLBI_CRN = 0b1000;
    private static final int SYSTEM_INSTRUCTION_TLBI_CRM_VMALLE1 = 0b0111;
    private static final int SYSTEM_INSTRUCTION_TLBI_CRM_VMALLE1IS = 0b0011;
    private static final int SYSTEM_INSTRUCTION_TLBI_OP2_ALL = 0b000;

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

    // ── LDXR/LDAXR/STXR/STLXR (`@stxr`, B6.3.4): sz(31:30) 001000(29:24) form(23:21) rs(20:16) ──
    // ── lasr(15) rt2(14:10, fixo 11111 nesta forma não-par) rn(9:5) rt(4:0) — Fatos de ─────────
    // ── referência #1 da task b6.3.4-aarch64-exclusive-monitor.md. `sz` reaproveita a MESMA ─────
    // ── codificação/posição de bit de `SINGLE_SIZE_SHIFT`/`SIZE_BYTE`/... (LDR/STR normal). ─────
    private static final int EXCLUSIVE_FORM_SHIFT = 21;
    private static final int EXCLUSIVE_FORM_MASK = 0b111;
    private static final int EXCLUSIVE_FORM_STXR = 0b000; // inclui STLXR (lasr=1)
    private static final int EXCLUSIVE_FORM_LDXR = 0b010; // inclui LDAXR (lasr=1)
    private static final int EXCLUSIVE_RS_SHIFT = 16;
    private static final int EXCLUSIVE_LASR_SHIFT = 15;

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

    // ── Data Processing — Scalar Floating-Point (B6.5.3): classe IRMÃ de "Data Processing — ────
    // ── Register" dentro do mesmo prefixo bit27=1/bit25=1, distinguida por bit26=1 (Fatos de ────
    // ── referência #1 da task). Verificado byte a byte contra `aarch64-none-elf-as`/`objdump` ────
    // ── reais (devkitA64) — ver o apêndice B6.5.3 do corpus versionado. Todos os subgrupos ───────
    // ── decodificados aqui (2-source/1-source/imediato/compare) compartilham prefixo fixo ────────
    // ── bits[28:24]="11110" e bit21=1 — Advanced SIMD vetorial (mesmo bit26=1, prefixo(28:24) ────
    // ── DIFERENTE, ex. "01110") e Data-processing (3-source, prefixo(28:24)="11111") ficam fora ───
    // ── só por não bater esse prefixo; FCCMP/FCSEL/conversões FP<->inteiro (mesmo prefixo+bit21) ──
    // ── ficam fora por não bater nenhum dos 4 padrões de sub-grupo específicos abaixo — ───────────
    // ── CONFERIDO empiricamente montando vizinhos (fmadd/fccmp/fcsel/frintn/scvtf/fcvtzs/fmov ─────
    // ── core<->FP) e comparando os bits reais, não só por leitura do manual.
    private static final int FP_SIMD_CLASS_BIT26_SHIFT = 26;
    private static final int SCALAR_FP_FIXED_PREFIX_SHIFT = 24;
    private static final int SCALAR_FP_FIXED_PREFIX_MASK = 0b1_1111;
    private static final int SCALAR_FP_FIXED_PREFIX_PATTERN = 0b1_1110;
    private static final int SCALAR_FP_BIT21_SHIFT = 21;

    // ── `type` (2 bits, bits[23:22]): MESMA posição nos 4 subgrupos decodificados aqui (2-source/
    // ── 1-source/imediato/compare) — conferido campo a campo contra o assembler real em todos os
    // ── 4, ao contrário do aviso da task de "não presumir a mesma posição sem checar" (Fatos de
    // ── referência #4). `10`/`11` são meia-precisão/reservado, fora de escopo.
    private static final int FP_TYPE_SHIFT = 22;
    private static final int FP_TYPE_MASK = 0b11;
    private static final int FP_TYPE_SINGLE = 0b00;
    private static final int FP_TYPE_DOUBLE = 0b01;

    // ── Floating-point data-processing (2 source) — FADD/FSUB/FMUL/FDIV: bits[11:10] fixo="10",
    // ── opcode(15:12) 4 bits, Rm(20:16), Rn(9:5), Rd(4:0).
    private static final int FP_TWO_SOURCE_FIXED_SHIFT = 10;
    private static final int FP_TWO_SOURCE_FIXED_MASK = 0b11;
    private static final int FP_TWO_SOURCE_FIXED_PATTERN = 0b10;
    private static final int FP_TWO_SOURCE_OPCODE_SHIFT = 12;
    private static final int FP_TWO_SOURCE_OPCODE_MASK = 0b1111;
    private static final int FP_TWO_SOURCE_OPCODE_FMUL = 0b0000;
    private static final int FP_TWO_SOURCE_OPCODE_FDIV = 0b0001;
    private static final int FP_TWO_SOURCE_OPCODE_FADD = 0b0010;
    private static final int FP_TWO_SOURCE_OPCODE_FSUB = 0b0011;
    // FMAX/FMIN/FMAXNM/FMINNM (0100-0111) e FNMUL (1000): fora de escopo, ver decodeFpTwoSource.
    private static final int FP_RM_SHIFT = 16;

    // ── Floating-point data-processing (1 source) — FMOV/FABS/FNEG/FCVT(F32<->F64): bits[14:10]
    // ── fixo="10000", opcode(20:15) 6 bits, Rn(9:5, fonte única), Rd(4:0).
    private static final int FP_ONE_SOURCE_FIXED_SHIFT = 10;
    private static final int FP_ONE_SOURCE_FIXED_MASK = 0b1_1111;
    private static final int FP_ONE_SOURCE_FIXED_PATTERN = 0b1_0000;
    private static final int FP_ONE_SOURCE_OPCODE_SHIFT = 15;
    private static final int FP_ONE_SOURCE_OPCODE_MASK = 0b11_1111;
    private static final int FP_ONE_SOURCE_OPCODE_FMOV = 0b00_0000;
    private static final int FP_ONE_SOURCE_OPCODE_FABS = 0b00_0001;
    private static final int FP_ONE_SOURCE_OPCODE_FNEG = 0b00_0010;
    /// `FCVT` fonte double (`type`=01) para destino single — opcode=4, CONFERIDO via
    /// `aarch64-none-elf-as` (`fcvt s29, d29` monta com esse opcode).
    private static final int FP_ONE_SOURCE_OPCODE_FCVT_TO_SINGLE = 0b00_0100;
    /// `FCVT` fonte single (`type`=00) para destino double — opcode=5, CONFERIDO via
    /// `aarch64-none-elf-as` (`fcvt d28, s28` monta com esse opcode).
    private static final int FP_ONE_SOURCE_OPCODE_FCVT_TO_DOUBLE = 0b00_0101;
    // FSQRT (3), FCVT de/para meia-precisão (6/7), FRINTx (8+): fora de escopo, ver decodeFpOneSource.

    // ── Floating-point immediate — `FMOV Sd,#imm`/`FMOV Dd,#imm`: bits[12:5] fixo="10000000",
    // ── imm8(20:13) — CONFERIDO: campo contíguo em A64 (diferente do VFP32, que espalha imm8 em
    // ── dois pedaços de 4 bits — b3.5-vfp-decoder.md); o algoritmo de expansão (VFPExpandImm) é
    // ── o MESMO conceito IEEE, só a posição do campo muda (Fatos de referência #3/Armadilhas).
    private static final int FP_IMMEDIATE_FIXED_SHIFT = 5;
    private static final int FP_IMMEDIATE_FIXED_MASK = 0xFF;
    private static final int FP_IMMEDIATE_FIXED_PATTERN = 0b1000_0000;
    private static final int FP_IMMEDIATE_IMM8_SHIFT = 13;
    private static final int FP_IMMEDIATE_IMM8_MASK = 0xFF;

    // ── Floating-point compare — FCMP/FCMPE (com/sem comparação-com-zero): bit15 fixo=0, ────────
    // ── bits[14:10] fixo="01000", bits[2:0] fixo="000", Rm(20:16, fixo=00000 na forma zero — ─────
    // ── CONFERIDO: não é coincidência, é parte do encoding fixo), bit4=E (FCMPE), bit3=zero, ──────
    // ── Rn(9:5).
    private static final int FP_COMPARE_BIT15_SHIFT = 15;
    private static final int FP_COMPARE_FIXED_SHIFT = 10;
    private static final int FP_COMPARE_FIXED_MASK = 0b1_1111;
    private static final int FP_COMPARE_FIXED_PATTERN = 0b0_1000;
    private static final int FP_COMPARE_LOW3_MASK = 0b111;
    private static final int FP_COMPARE_E_BIT_SHIFT = 4;
    private static final int FP_COMPARE_ZERO_BIT_SHIFT = 3;

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

    // ── Conditional compare (CCMP/CCMN), subgrupo de Data Processing — Register (B6.8): ─────────
    // ── sf(31) op(30) 1(29,"S") 11010010(28:21) y(20:16) cond(15:12) imm(11) 0(10) rn(9:5) ───────
    // ── 0(4) nzcv(3:0) — CONFERIDO contra `a64.decode`/`translate-a64.c` do QEMU (Fatos de ────────
    // ── referência da task, ver o Javadoc de `Ir64Op.ConditionalCompare`). `S`(bit29) faz parte ──
    // ── do prefixo fixo de 9 bits (não é um campo): com `S=0` este mesmo prefixo de 8 bits não ───
    // ── casa com NENHUMA outra instrução no `a64.decode` real — cai no `unsupported` genérico. ───
    private static final int CCMP_FIXED_SHIFT = 21;
    private static final int CCMP_FIXED_9BIT_MASK = 0b1_1111_1111;
    private static final int CCMP_FIXED_PATTERN = 0b1_1101_0010;
    private static final int CCMP_IMM_FORM_BIT_SHIFT = 11;
    private static final int CCMP_IMM5_MASK = 0b1_1111;
    private static final int CCMP_COND_SHIFT = 12;
    private static final int CCMP_NZCV_MASK = 0xF;
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
            // Load/store exclusivo e atômico: só LDXR/LDAXR/STXR/STLXR (B6.3.4) — LDXP/STXP/CAS/
            // LDAR/STLR ficam unsupported dentro de decodeExclusive (ver Armadilhas da task).
            case SUBCLASS_EXCLUSIVE_ATOMIC -> decodeExclusive(word, address);
            default -> throw new IllegalStateException("unreachable");
        };
    }

    /// `LDXR`/`LDAXR`/`STXR`/`STLXR` (D0/D2 da task b6.3.4-aarch64-exclusive-monitor.md): as 4
    /// mnemônicas compartilham o MESMO encoding (`@stxr` do QEMU), diferindo só pelo bit `lasr`
    /// (acquire/release) — decodificar `LDAXR`/`STLXR` sem `LDXR`/`STXR` decodificaria só METADE
    /// dos valores desse bit, o que não é uma opção coerente. `LDXP`/`STXP`/`CAS`/`LDAR`/`STLR`
    /// vivem no MESMO subgrupo de encoding (valores diferentes de `form`) mas ficam fora do
    /// escopo fechado do épico — ver Armadilhas da task.
    private Ir64Op decodeExclusive(int word, long address) {
        int form = (word >>> EXCLUSIVE_FORM_SHIFT) & EXCLUSIVE_FORM_MASK;
        boolean store;
        if (form == EXCLUSIVE_FORM_STXR) {
            store = true;
        } else if (form == EXCLUSIVE_FORM_LDXR) {
            store = false;
        } else {
            // LDXP/STXP/CAS/LDAR/STLR (mesmo subgrupo, valores diferentes de `form`): fora do
            // escopo fechado do épico B6 (ver Armadilhas da task b6.3.4).
            throw unsupported(word, address);
        }
        Ir64MemSize size = switch ((word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK) {
            case SIZE_BYTE -> Ir64MemSize.BYTE;
            case SIZE_HALF -> Ir64MemSize.HALF;
            case SIZE_WORD -> Ir64MemSize.WORD;
            case SIZE_DOUBLEWORD -> Ir64MemSize.DOUBLEWORD;
            default -> throw new IllegalStateException("unreachable");
        };
        boolean acquireRelease = ((word >>> EXCLUSIVE_LASR_SHIFT) & 1) != 0;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        if (store) {
            int rs = (word >>> EXCLUSIVE_RS_SHIFT) & REGISTER_FIELD_MASK;
            return new Ir64Op.StoreExclusive(rs, rt, rn, size, acquireRelease);
        }
        return new Ir64Op.LoadExclusive(rt, rn, size, acquireRelease);
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
    ///
    /// `bit26=1` (B6.5.3, D1 da task): a MESMA checagem de entrada (bit27=1 && bit25=1) também
    /// aceita a classe irmã "Data Processing — Scalar Floating-Point and Advanced SIMD" — só
    /// falta ramificar por `bit26` logo no topo, antes de qualquer lógica que já assume
    /// implicitamente `bit26=0` (Fatos de referência #1 da task).
    private Ir64Op decodeDataProcessingRegister(int word, long address) {
        if (((word >>> FP_SIMD_CLASS_BIT26_SHIFT) & 1) != 0) {
            return decodeDataProcessingScalarFpSimd(word, address);
        }
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
        int ccmpFixed9 = (word >>> CCMP_FIXED_SHIFT) & CCMP_FIXED_9BIT_MASK;
        if (ccmpFixed9 == CCMP_FIXED_PATTERN) {
            return decodeConditionalCompare(word);
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

    /// `CCMP`/`CCMN`, forma registrador E forma imediato (B6.8) — `op`(bit30) `0`=`CCMN`
    /// (`Ir64AluOp.ADD`), `1`=`CCMP` (`Ir64AluOp.SUB`), mesma semântica de bit de
    /// {@link #decodeAddSubShiftedRegister}. Sem campo `Rd` (bits `[4:0]` são fixos em `0`, não
    /// decodificados — ver Armadilhas da task B6.8).
    private Ir64Op decodeConditionalCompare(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean isSub = ((word >>> ADD_SUB_OP_SHIFT) & 1) != 0;
        Ir64AluOp opcode = isSub ? Ir64AluOp.SUB : Ir64AluOp.ADD;
        boolean immediateForm = ((word >>> CCMP_IMM_FORM_BIT_SHIFT) & 1) != 0;
        int rm = immediateForm ? -1 : (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int immediate = immediateForm ? (word >>> ADDSUB_REGISTER_RM_SHIFT) & CCMP_IMM5_MASK : -1;
        Ir64Condition condition = Ir64Condition.decode((word >>> CCMP_COND_SHIFT) & COND_FIELD_MASK);
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int nzcv = word & CCMP_NZCV_MASK;
        return new Ir64Op.ConditionalCompare(opcode, rn, immediateForm, rm, immediate, wide, condition, nzcv);
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

    /// Sub-dispatch da classe "Data Processing — Scalar Floating-Point and Advanced SIMD"
    /// (`bit26=1`, D1 da task B6.5.3): só o subconjunto ESCALAR herdado de B6.5.2
    /// (`FADD`/`FSUB`/`FMUL`/`FDIV`/`FNEG`/`FABS`/`FMOV` registrador/imediato/`FCMP`/`FCMPE`/
    /// `FCVT` F32↔F64) — Advanced SIMD vetorial, `FCCMP`/`FCSEL`, conversões FP↔inteiro e
    /// data-processing (3-source) ficam fora (Não inclui da task), reconhecidos aqui só pela
    /// AUSÊNCIA de qualquer um dos 4 padrões fixos abaixo (nunca por um `case` próprio que
    /// tentaria decodificá-los).
    private Ir64Op decodeDataProcessingScalarFpSimd(int word, long address) {
        int fixedPrefix = (word >>> SCALAR_FP_FIXED_PREFIX_SHIFT) & SCALAR_FP_FIXED_PREFIX_MASK;
        boolean bit21Set = ((word >>> SCALAR_FP_BIT21_SHIFT) & 1) != 0;
        if (fixedPrefix != SCALAR_FP_FIXED_PREFIX_PATTERN || !bit21Set) {
            // Advanced SIMD vetorial (prefixo(28:24) diferente, ex. "01110"), data-processing
            // (3-source, prefixo="11111") ou conversões FP<->fixed-point (bit21=0 na forma com
            // shift): fora do escopo fechado desta task.
            throw unsupported(word, address);
        }
        int immediateFixed = (word >>> FP_IMMEDIATE_FIXED_SHIFT) & FP_IMMEDIATE_FIXED_MASK;
        if (immediateFixed == FP_IMMEDIATE_FIXED_PATTERN) {
            return decodeFpMoveImmediate(word, address);
        }
        boolean compareBit15Clear = ((word >>> FP_COMPARE_BIT15_SHIFT) & 1) == 0;
        int compareFixed = (word >>> FP_COMPARE_FIXED_SHIFT) & FP_COMPARE_FIXED_MASK;
        boolean compareLow3Clear = (word & FP_COMPARE_LOW3_MASK) == 0;
        if (compareBit15Clear && compareFixed == FP_COMPARE_FIXED_PATTERN && compareLow3Clear) {
            return decodeFpCompare(word, address);
        }
        int oneSourceFixed = (word >>> FP_ONE_SOURCE_FIXED_SHIFT) & FP_ONE_SOURCE_FIXED_MASK;
        if (oneSourceFixed == FP_ONE_SOURCE_FIXED_PATTERN) {
            return decodeFpOneSource(word, address);
        }
        int twoSourceFixed = (word >>> FP_TWO_SOURCE_FIXED_SHIFT) & FP_TWO_SOURCE_FIXED_MASK;
        if (twoSourceFixed == FP_TWO_SOURCE_FIXED_PATTERN) {
            return decodeFpTwoSource(word, address);
        }
        // FCCMP/FCSEL (conditional compare/select) e conversões FP<->inteiro (SCVTF/UCVTF/
        // FCVTZS/FCVTZU): mesmo prefixo(28:24)/bit21, discriminados por outros campos — fora do
        // escopo fechado desta task (herdado de B6.5.2, ver Não inclui).
        throw unsupported(word, address);
    }

    /// `type` (bits[23:22], Fatos de referência #4): posição idêntica nos 4 subgrupos escalares
    /// decodificados por esta task — `10`/`11` (meia-precisão/reservado) são UNDEFINED reais
    /// aqui, não "não implementado" (mesmo padrão de outros campos reservados do arquivo).
    private boolean decodeFpDoublePrecision(int word, long address) {
        int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
        return switch (type) {
            case FP_TYPE_SINGLE -> false;
            case FP_TYPE_DOUBLE -> true;
            default -> throw unsupported(word, address);
        };
    }

    /// `FADD`/`FSUB`/`FMUL`/`FDIV` (Floating-point data-processing, 2 source).
    private Ir64Op decodeFpTwoSource(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int opcode = (word >>> FP_TWO_SOURCE_OPCODE_SHIFT) & FP_TWO_SOURCE_OPCODE_MASK;
        Ir64Op.Fp64Operation op = switch (opcode) {
            case FP_TWO_SOURCE_OPCODE_FMUL -> Ir64Op.Fp64Operation.MUL;
            case FP_TWO_SOURCE_OPCODE_FDIV -> Ir64Op.Fp64Operation.DIV;
            case FP_TWO_SOURCE_OPCODE_FADD -> Ir64Op.Fp64Operation.ADD;
            case FP_TWO_SOURCE_OPCODE_FSUB -> Ir64Op.Fp64Operation.SUB;
            // FMAX/FMIN/FMAXNM/FMINNM/FNMUL: fora de escopo, herdado de B6.5.2.
            default -> throw unsupported(word, address);
        };
        int vm = (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Fp64Alu(op, doublePrecision, vd, vn, vm);
    }

    /// `FMOV`/`FABS`/`FNEG` (unárias) e `FCVT` F32↔F64 (Floating-point data-processing,
    /// 1 source) — opcode(20:15) distingue as 5 formas cobertas; demais valores (`FSQRT`,
    /// `FCVT` de/para meia-precisão, `FRINTx`) ficam fora do escopo fechado desta task.
    private Ir64Op decodeFpOneSource(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int opcode = (word >>> FP_ONE_SOURCE_OPCODE_SHIFT) & FP_ONE_SOURCE_OPCODE_MASK;
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vd = word & REGISTER_FIELD_MASK;
        return switch (opcode) {
            case FP_ONE_SOURCE_OPCODE_FMOV ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MOV, doublePrecision, vd, 0, vn);
            case FP_ONE_SOURCE_OPCODE_FABS ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ABS, doublePrecision, vd, 0, vn);
            case FP_ONE_SOURCE_OPCODE_FNEG ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NEG, doublePrecision, vd, 0, vn);
            case FP_ONE_SOURCE_OPCODE_FCVT_TO_DOUBLE -> {
                if (doublePrecision) {
                    // opcode=5 (FCVT-para-double) exige type=00 (fonte single) — a combinação
                    // contrária (type=01) é outra instrução (FCVT-para-half a partir de double),
                    // fora de escopo.
                    throw unsupported(word, address);
                }
                yield new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F32_TO_F64, vd, vn);
            }
            case FP_ONE_SOURCE_OPCODE_FCVT_TO_SINGLE -> {
                if (!doublePrecision) {
                    // opcode=4 (FCVT-para-single) exige type=01 (fonte double) — a combinação
                    // contrária é outra instrução, fora de escopo (mesma simetria do case acima).
                    throw unsupported(word, address);
                }
                yield new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F64_TO_F32, vd, vn);
            }
            default -> throw unsupported(word, address);
        };
    }

    /// `FMOV Sd,#imm`/`FMOV Dd,#imm` (Floating-point immediate) — o imediato de 8 bits é expandido
    /// AQUI (VFPExpandImm-equivalente, {@link #expandFpImmediate}), nunca no executor.
    private Ir64Op decodeFpMoveImmediate(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int imm8 = (word >>> FP_IMMEDIATE_IMM8_SHIFT) & FP_IMMEDIATE_IMM8_MASK;
        long immediateBits = expandFpImmediate(imm8, doublePrecision);
        int vd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Fp64MoveImmediate(doublePrecision, vd, immediateBits);
    }

    /// `FCMP`/`FCMPE`, com ou sem comparação-com-zero (`Rm` é fixo em `00000` na forma zero —
    /// ignorado aqui, nunca lido, já que o valor não importaria de qualquer forma).
    private Ir64Op decodeFpCompare(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        boolean signalOnQuietNaN = ((word >>> FP_COMPARE_E_BIT_SHIFT) & 1) != 0;
        boolean compareWithZero = ((word >>> FP_COMPARE_ZERO_BIT_SHIFT) & 1) != 0;
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vm = compareWithZero ? 0 : (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        return new Ir64Op.Fp64Compare(doublePrecision, compareWithZero, signalOnQuietNaN, vn, vm);
    }

    /// `VFPExpandImm`-equivalente de A64 (Armadilhas da task B6.5.3): MESMO algoritmo conceitual
    /// do precedente VFP32 (`StandardIrBuilder#vfpExpandImm`, `vfp_expand_imm` do QEMU) — sinal
    /// (bit7) + expoente replicado (bit6 invertido, {@code notBit6}) + mantissa (bits5:0) — mas
    /// duplicado aqui (não reaproveitado por chamada direta) porque o campo `imm8` de origem já
    /// chega CONTÍGUO do encoding A64 (bits[20:13], ver {@link #decodeFpMoveImmediate}), diferente
    /// do VFP32 que precisa remontar `imm8` a partir de dois pedaços de 4 bits antes de expandir —
    /// os dois mundos não compartilham decoder (G2/G3), e a assinatura já recebe o valor pronto.
    private static long expandFpImmediate(int imm8, boolean doublePrecision) {
        boolean sign = (imm8 & 0x80) != 0;
        boolean notBit6 = (imm8 & 0x40) == 0;
        int low6 = imm8 & 0x3F;
        if (doublePrecision) {
            long high16 = (sign ? 0x8000L : 0) | (notBit6 ? 0x4000L : 0x3fc0L) | low6;
            return high16 << 48;
        }
        long high16 = (sign ? 0x8000L : 0) | (notBit6 ? 0x4000L : 0x3e00L) | ((long) low6 << 3);
        return (high16 << 16) & 0xFFFF_FFFFL;
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
        int systemRegisterFixed = (word >>> SYSTEM_REGISTER_FIXED_SHIFT) & SYSTEM_REGISTER_FIXED_10BIT_MASK;
        if (systemRegisterFixed == SYSTEM_REGISTER_FIXED_PATTERN) {
            int systemRegisterOp0 = (word >>> SYSTEM_REGISTER_OP0_SHIFT) & SYSTEM_REGISTER_OP0_MASK;
            if (systemRegisterOp0 == SYSTEM_INSTRUCTION_OP0_BARRIER) {
                return decodeSystemInstructionBarrier(word, address);
            }
            if (systemRegisterOp0 == SYSTEM_INSTRUCTION_OP0_SYS) {
                return decodeSystemInstructionSys(word, address);
            }
            return decodeSystemRegister(word, address);
        }
        // Demais formas do grupo Branch/Exception/System (ERET/DRPS): fora da fatia B6.1/B6.6.x.
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
            // DRPS ou combinação reservada: fora da fatia B6.1/B6.6.4.
            throw unsupported(word, address);
        }
        int opc = (word >>> BRANCH_REGISTER_OPC_SHIFT) & BRANCH_REGISTER_OPC_MASK;
        if (opc == BRANCH_REGISTER_OPC_ERET) {
            // ERET (B6.6.4): Rn (bits 9:5) é fixo em `11111`, não um registrador — ignorado.
            return new Ir64Op.ExceptionReturn();
        }
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
        int imm16 = (word >>> IMM16_SHIFT) & IMM16_MASK;
        if (opc == EXCEPTION_GEN_OPC_SVC && low5 == EXCEPTION_GEN_SVC_LOW5_FIXED) {
            return new Ir64Op.Svc(imm16);
        }
        if (opc == EXCEPTION_GEN_OPC_SVC
                && (low5 == EXCEPTION_GEN_HVC_LOW5_FIXED || low5 == EXCEPTION_GEN_SMC_LOW5_FIXED)) {
            // HVC/SMC (B6.6.7): sem EL2/EL3 modelados (fora de escopo do épico B6) — tratadas como
            // um "host call" inerte que sempre devolve PSCI_RET_NOT_SUPPORTED em X0 (ver
            // Ir64BlockExecutor#executePrivilegedCall e a task, "Não inclui"). O `imm16` é ignorado
            // pela semântica (mesmo padrão real de hardware: o imediato de HVC/SMC só importa para
            // o handler em EL2/EL3, que este emulador não tem).
            return new Ir64Op.PrivilegedCall();
        }
        // BRK/HLT/DCPS*: fora da fatia B6.1/B6.6.7.
        throw unsupported(word, address);
    }

    /// `DSB`/`ISB`/`DMB` (B6.6.3) e os "Hints" `NOP`/`YIELD`/`WFE`/`WFI`/`SEV`/`SEVL` (B6.6.7) —
    /// mesmo subgrupo de encoding `op0=0` (`CRn` distingue barreira de hint). Barreiras e a maior
    /// parte dos hints viram NOP observável, mesmo precedente de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.MemoryBarrier} 32-bit; só `WFI` tem semântica
    /// própria (ver {@link Ir64SystemInstructionOp#WFI}). Demais formas do subgrupo `op0=0`
    /// (`CLREX`, `MSR (immediate, PSTATE)` incl. `DAIFSet`/`DAIFClr`, `SB`): fora do escopo desta
    /// task (ver a task B6.6.7, "Não inclui" — `DAIFSet`/`DAIFClr` ficam para uma sub-task futura
    /// se um consumidor real precisar mascarar IRQ explicitamente por software).
    private Ir64Op decodeSystemInstructionBarrier(int word, long address) {
        int crn = (word >>> SYSTEM_REGISTER_CRN_SHIFT) & SYSTEM_REGISTER_CRN_MASK;
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        if (crn == SYSTEM_INSTRUCTION_BARRIER_CRN
                && (op2 == SYSTEM_INSTRUCTION_BARRIER_OP2_DSB
                        || op2 == SYSTEM_INSTRUCTION_BARRIER_OP2_DMB
                        || op2 == SYSTEM_INSTRUCTION_BARRIER_OP2_ISB)) {
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.BARRIER);
        }
        if (crn == SYSTEM_INSTRUCTION_HINT_CRN) {
            if (op2 == SYSTEM_INSTRUCTION_HINT_OP2_WFI) {
                return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.WFI);
            }
            // NOP/YIELD/WFE/SEV/SEVL (e qualquer combinação reservada de CRm/op2 dentro do
            // subgrupo "Hints" — RES NOP por definição arquitetural, `ARM DDI 0487 C6.2.132`):
            // NOP puro, mesmo tratamento das barreiras (D2 da task B6.6.7 — sem event-stream
            // modelado, `WFE`/`SEV`/`SEVL` não têm efeito observável neste emulador).
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.NOP_HINT);
        }
        throw unsupported(word, address);
    }

    /// `TLBI VMALLE1`/`TLBI VMALLE1IS` (B6.6.3, `op0=1`, `SYS` — achado real da rodada de
    /// pesquisa: `TLBI` NÃO é `MRS`/`MSR`, é uma forma de `SYS` com o mesmo formato de campos).
    /// Demais formas de `SYS`/`SYSL` (`TLBI` per-VA como `VAE1`, `DC`/`IC`/`AT`, `SYSL`
    /// propriamente dito com `L=1`): fora do escopo desta task, mesma simplificação
    /// "sem per-ASID/per-VA" do precedente 32-bit (`Cp15VmsaCoprocessor`, `TLBIALL`).
    private Ir64Op decodeSystemInstructionSys(int word, long address) {
        boolean isSysl = ((word >>> SYSTEM_REGISTER_L_SHIFT) & 1) != 0;
        int op1 = (word >>> SYSTEM_REGISTER_OP1_SHIFT) & SYSTEM_REGISTER_OP1_MASK;
        int crn = (word >>> SYSTEM_REGISTER_CRN_SHIFT) & SYSTEM_REGISTER_CRN_MASK;
        int crm = (word >>> SYSTEM_REGISTER_CRM_SHIFT) & SYSTEM_REGISTER_CRM_MASK;
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        if (!isSysl && op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL1 && crn == SYSTEM_INSTRUCTION_TLBI_CRN
                && (crm == SYSTEM_INSTRUCTION_TLBI_CRM_VMALLE1 || crm == SYSTEM_INSTRUCTION_TLBI_CRM_VMALLE1IS)
                && op2 == SYSTEM_INSTRUCTION_TLBI_OP2_ALL) {
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.TLBI_ALL);
        }
        throw unsupported(word, address);
    }

    /// `MRS`/`MSR (register)` (B6.6.1) — resolve a 5-upla `op0:op1:CRn:CRm:op2` crua para um
    /// {@link Aarch64SystemRegisterId} AQUI (decisão D1 da task: resolução única no decoder,
    /// nunca no executor a partir dos bits crus). Não existe forma `W`: o bit mais alto da
    /// instrução é parte do prefixo fixo (não um `sf`), então `Rt` é sempre `X`.
    private Ir64Op decodeSystemRegister(int word, long address) {
        boolean read = ((word >>> SYSTEM_REGISTER_L_SHIFT) & 1) != 0;
        int op0 = (word >>> SYSTEM_REGISTER_OP0_SHIFT) & SYSTEM_REGISTER_OP0_MASK;
        int op1 = (word >>> SYSTEM_REGISTER_OP1_SHIFT) & SYSTEM_REGISTER_OP1_MASK;
        int crn = (word >>> SYSTEM_REGISTER_CRN_SHIFT) & SYSTEM_REGISTER_CRN_MASK;
        int crm = (word >>> SYSTEM_REGISTER_CRM_SHIFT) & SYSTEM_REGISTER_CRM_MASK;
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        Aarch64SystemRegisterId register = decodeSystemRegisterId(op0, op1, crn, crm, op2);
        if (register == null) {
            // Combinação op0:op1:CRn:CRm:op2 válida arquiteturalmente, mas fora do subconjunto
            // desta task (não é UNDEFINED real — ver Armadilhas da task B6.6.1).
            throw unsupported(word, address);
        }
        return new Ir64Op.SystemRegister(read, register, rt);
    }

    /// Tabela de registradores de sistema cobertos (Fatos de referência #2 da task B6.6.1,
    /// estendida por B6.6.7): `op0=3`/`op1=0` (EL1 "geral", incl. os novos registradores de
    /// identidade da CPU) OU `op0=3`/`op1=3` (timer genérico, acessível de EL0 — B6.6.7); qualquer
    /// outra combinação devolve `null` (não implementada ainda, tratado pelo chamador como
    /// {@link UnsupportedOperationException}).
    private static Aarch64SystemRegisterId decodeSystemRegisterId(
            int op0, int op1, int crn, int crm, int op2) {
        if (op0 != SYSREG_OP0_EL1) {
            return null;
        }
        if (op1 == SYSREG_OP1_EL0_TIMER) {
            return decodeGenericTimerRegisterId(crn, crm, op2);
        }
        if (op1 != SYSREG_OP1_EL1) {
            return null;
        }
        if (crn == SYSREG_CRN_CURRENT_EL && crm == SYSREG_CRM_CURRENT_EL && op2 == SYSREG_OP2_CURRENT_EL) {
            return Aarch64SystemRegisterId.CURRENT_EL;
        }
        if (crn == SYSREG_CRN_MPIDR && crm == SYSREG_CRM_MPIDR && op2 == SYSREG_OP2_MPIDR) {
            return Aarch64SystemRegisterId.MPIDR_EL1;
        }
        if (crn == SYSREG_CRN_MIDR && crm == SYSREG_CRM_MIDR && op2 == SYSREG_OP2_MIDR) {
            return Aarch64SystemRegisterId.MIDR_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64PFR0 && crm == SYSREG_CRM_ID_AA64PFR0 && op2 == SYSREG_OP2_ID_AA64PFR0) {
            return Aarch64SystemRegisterId.ID_AA64PFR0_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64ISAR0 && crm == SYSREG_CRM_ID_AA64ISAR0 && op2 == SYSREG_OP2_ID_AA64ISAR0) {
            return Aarch64SystemRegisterId.ID_AA64ISAR0_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64MMFR0 && crm == SYSREG_CRM_ID_AA64MMFR0 && op2 == SYSREG_OP2_ID_AA64MMFR0) {
            return Aarch64SystemRegisterId.ID_AA64MMFR0_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64DFR0 && crm == SYSREG_CRM_ID_AA64DFR0 && op2 == SYSREG_OP2_ID_AA64DFR0) {
            return Aarch64SystemRegisterId.ID_AA64DFR0_EL1;
        }
        if (crn == SYSREG_CRN_TPIDR_EL1 && crm == SYSREG_CRM_TPIDR_EL1 && op2 == SYSREG_OP2_TPIDR_EL1) {
            return Aarch64SystemRegisterId.TPIDR_EL1;
        }
        if (crn == SYSREG_CRN_SCTLR && crm == SYSREG_CRM_SCTLR && op2 == SYSREG_OP2_SCTLR) {
            return Aarch64SystemRegisterId.SCTLR_EL1;
        }
        if (crn == SYSREG_CRN_TTBR0 && crm == SYSREG_CRM_TTBR0 && op2 == SYSREG_OP2_TTBR0) {
            return Aarch64SystemRegisterId.TTBR0_EL1;
        }
        if (crn == SYSREG_CRN_TCR && crm == SYSREG_CRM_TCR && op2 == SYSREG_OP2_TCR) {
            return Aarch64SystemRegisterId.TCR_EL1;
        }
        if (crn == SYSREG_CRN_MAIR && crm == SYSREG_CRM_MAIR && op2 == SYSREG_OP2_MAIR) {
            return Aarch64SystemRegisterId.MAIR_EL1;
        }
        if (crn == SYSREG_CRN_ESR && crm == SYSREG_CRM_ESR && op2 == SYSREG_OP2_ESR) {
            return Aarch64SystemRegisterId.ESR_EL1;
        }
        if (crn == SYSREG_CRN_FAR && crm == SYSREG_CRM_FAR && op2 == SYSREG_OP2_FAR) {
            return Aarch64SystemRegisterId.FAR_EL1;
        }
        if (crn == SYSREG_CRN_VBAR && crm == SYSREG_CRM_VBAR && op2 == SYSREG_OP2_VBAR) {
            return Aarch64SystemRegisterId.VBAR_EL1;
        }
        if (crn == SYSREG_CRN_ELR && crm == SYSREG_CRM_ELR && op2 == SYSREG_OP2_ELR) {
            return Aarch64SystemRegisterId.ELR_EL1;
        }
        if (crn == SYSREG_CRN_SPSR && crm == SYSREG_CRM_SPSR && op2 == SYSREG_OP2_SPSR) {
            return Aarch64SystemRegisterId.SPSR_EL1;
        }
        return null;
    }

    /// Subconjunto do timer genérico coberto por B6.6.7 (`op0=3,op1=3,CRn=0b1110` fixo) — só o
    /// comparador FÍSICO (`CNTP_*`), não o virtual (`CNTV_*`, sem consumidor real conhecido ainda,
    /// nem `CNTKCTL_EL1`/`CNTHCTL_EL2`). Roteados para o {@link
    /// dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus} do hospedeiro (ver javadoc de
    /// {@link Aarch64SystemRegisterId}), diferente das identidades resolvidas acima.
    private static Aarch64SystemRegisterId decodeGenericTimerRegisterId(int crn, int crm, int op2) {
        if (crn != SYSREG_CRN_TIMER) {
            return null;
        }
        if (crm == SYSREG_CRM_CNTFRQ && op2 == SYSREG_OP2_CNTFRQ) {
            return Aarch64SystemRegisterId.CNTFRQ_EL0;
        }
        if (crm == SYSREG_CRM_CNTPCT && op2 == SYSREG_OP2_CNTPCT) {
            return Aarch64SystemRegisterId.CNTPCT_EL0;
        }
        if (crm == SYSREG_CRM_CNTP && op2 == SYSREG_OP2_CNTP_TVAL) {
            return Aarch64SystemRegisterId.CNTP_TVAL_EL0;
        }
        if (crm == SYSREG_CRM_CNTP && op2 == SYSREG_OP2_CNTP_CTL) {
            return Aarch64SystemRegisterId.CNTP_CTL_EL0;
        }
        if (crm == SYSREG_CRM_CNTP && op2 == SYSREG_OP2_CNTP_CVAL) {
            return Aarch64SystemRegisterId.CNTP_CVAL_EL0;
        }
        return null;
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
