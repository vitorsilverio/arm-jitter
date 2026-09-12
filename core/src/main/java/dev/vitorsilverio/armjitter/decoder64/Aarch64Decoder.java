package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha512Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3TtOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaTwoRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FlagConversionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64MinMaxOp;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64PointerAuthOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPermuteOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.util.Objects;

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
/// `LDXP`/`STXP`/`CAS`/`LDAR`/`STLR` (mesmo subgrupo de exclusivo/atômico de `LDXR`/`STXR`, ver
/// B6.3.4), load/store escalar de registrador SIMD&FP (`V=1`, `LDR`/`STR`/`LDP`/`STP`) e
/// data-processing SIMD&FP ficam FORA do escopo fechado do épico B6 — qualquer encoding fora do
/// escopo listado lança {@link UnsupportedOperationException} em vez de tentar adivinhar semântica
/// (nenhum oráculo real cobre o que não foi implementado). B8.6 (dentro do mesmo `V=1`) acrescenta
/// `LD1`-`LD4`/`ST1`-`ST4`/`LD1R`-`LD4R` (AdvSIMD load/store multiple/single structures) — ver
/// {@link Ir64Op.VectorLoadStoreMultiple}/{@link Ir64Op.VectorLoadStoreSingle}/
/// {@link Ir64Op.VectorLoadSingleReplicate}.
///
/// B11.2: recebe uma {@link Aarch64Architecture} no construtor, mesmo padrão dos decoders de
/// extensão de 32 bits (ex. `Thumb2DataProcessingDecoder(ArmArchitecture)`) — {@link #architecture}
/// ainda não gateia NENHUM encoding (zero-diff comportamental, G3): é só fiação, o primeiro gate de
/// decode real é B11.4. O construtor sem argumento (preservado por compatibilidade) usa
/// {@link Aarch64Architecture#ARMV8_0_A}, que representa exatamente o que este decoder já
/// implementa incondicionalmente hoje.
public final class Aarch64Decoder {
    private final Aarch64Architecture architecture;

    /// Cria um decoder para {@link Aarch64Architecture#ARMV8_0_A} — equivalente ao comportamento
    /// deste decoder antes de B11.2 (tudo que está implementado, incondicional).
    public Aarch64Decoder() {
        this(Aarch64Architecture.ARMV8_0_A);
    }

    /// Cria um decoder para a arquitetura informada. Ainda sem efeito observável (B11.2 é só
    /// fiação) — ver o Javadoc da classe.
    public Aarch64Decoder(Aarch64Architecture architecture) {
        this.architecture = Objects.requireNonNull(architecture, "architecture");
    }

    /// Retorna a arquitetura configurada para este decoder (B11.2).
    public Aarch64Architecture architecture() {
        return architecture;
    }
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

    // ── B19.22: CB_cond/CB_cond_imm (`FEAT_CMPBR`) — MESMO campo `COMPARE_BRANCH_FIXED_SHIFT`
    // ── acima (bits[30:25]), prefixo PRÓPRIO `0b111010` (vizinho de CBZ/TBZ, nunca colide: os 3
    // ── prefixos de 6 bits são distintos). `bit24` distingue a forma registrador (`0`, `CB_cond`)
    // ── da forma imediata (`1`, `CB_cond_imm`) dentro do MESMO prefixo — reaproveita `BIT_24`.
    // ── CB_cond: sf/esz-hi(31) 1110100(30:24) cc(23:21) Rm(20:16) eszField(15:14) imm9(13:5)
    // ── Rt(4:0) — `eszField`: `00`=word(sf=0)/doubleword(sf=1), `10`=byte(CBB), `11`=halfword(CBH),
    // ── `01` reservado (sem forma no manual, G8: recusar).
    private static final int COMPARE_BRANCH_COND_FIXED_PATTERN = 0b111010;
    private static final int CB_COND_CC_SHIFT = 21;
    private static final int CB_COND_CC_MASK = 0b111;
    private static final int CB_COND_RM_SHIFT = 16;
    private static final int CB_COND_ESZ_FIELD_SHIFT = 14;
    private static final int CB_COND_ESZ_FIELD_MASK = 0b11;
    private static final int CB_COND_ESZ_FIELD_WORD_OR_DOUBLE = 0b00;
    private static final int CB_COND_ESZ_FIELD_BYTE = 0b10;
    private static final int CB_COND_ESZ_FIELD_HALF = 0b11;
    // ── CB_cond_imm: sf(31) 1110101(30:24) cc(23:21) imm6(20:15) reservado=0(14) imm9(13:5)
    // ── Rt(4:0) — `imm6` é `UInt`, NUNCA estendido com sinal (ao contrário do deslocamento).
    private static final int CB_COND_IMM6_SHIFT = 15;
    private static final int CB_COND_IMM6_BITS = 6;
    private static final int CB_COND_IMM_RESERVED_BIT14 = 1 << 14;
    // ── deslocamento de desvio comum às duas formas (`%imm9 !function=times_4` do a64.decode) —
    // ── MESMA posição de bits em ambas, mas shift/largura PRÓPRIOS (não confundir com
    // ── `SINGLE_IMM9_SHIFT`, usado por load/store unscaled, campo em posição diferente).
    private static final int CB_COND_OFFSET_IMM9_SHIFT = 5;
    private static final int CB_COND_OFFSET_IMM9_BITS = 9;

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

    // ── B19.15: `BRAZ`/`BLRAZ`/`RETA`/`BRA`/`BLRA`/`ERETA` (`FEAT_PAuth`) — MESMO prefixo fixo de ──
    // ── 7 bits (`BRANCH_REGISTER_FIXED_PATTERN`) e `op2`(`11111`) de `BR`/`BLR`/`RET`/`ERET` acima, ─
    // ── mas `op3`(bits[15:10]) tem os 5 bits altos fixos em `00001` (em vez de `000000`) — o bit
    // ── baixo de `op3` é `m` (chave A=0/B=1, não afeta o resultado sob a rota (b) registrada na
    // ── task: nenhuma autenticação real é modelada, ver `Ir64Op.PointerAuthInPlace`). `opc`
    // ── (bits[24:21]) distingue as 6 formas: `BRAZ`=0000/`BLRAZ`=0001/`RETA`=0010/`ERETA`=0100
    // ── (MESMOS valores de `BR`/`BLR`/`RET`/`ERET`, diferenciados só por `op3`) e `BRA`=1000/
    // ── `BLRA`=1001 (valores NOVOS, bit23 setado). `BRAZ`/`BLRAZ`/`RETA`/`ERETA` (modificador ZERO
    // ── implícito) têm `op4`(bits[4:0]) fixo em `11111` (sem `Rm`); `BRA`/`BLRA` (modificador
    // ── explícito) usam `op4` como `Rm` de verdade. Medido contra `ARM DDI 0487 C6.2.*`.
    private static final int PAUTH_BRANCH_OP3_UPPER_SHIFT = 11;
    private static final int PAUTH_BRANCH_OP3_UPPER_MASK = 0b1_1111;
    private static final int PAUTH_BRANCH_OP3_UPPER_FIXED = 0b0_0001;
    private static final int PAUTH_BRANCH_OPC_BRAZ = 0b0000;
    private static final int PAUTH_BRANCH_OPC_BLRAZ = 0b0001;
    private static final int PAUTH_BRANCH_OPC_RETA = 0b0010;
    private static final int PAUTH_BRANCH_OPC_ERETA = 0b0100;
    private static final int PAUTH_BRANCH_OPC_BRA = 0b1000;
    private static final int PAUTH_BRANCH_OPC_BLRA = 0b1001;
    /// `op4` fixo das formas com modificador ZERO implícito (`BRAZ`/`BLRAZ`/`RETA`/`ERETA`) — sem
    /// `Rm` no encoding real (coincide em valor com {@link #BRANCH_REGISTER_OP2_FIXED}, mas é um
    /// campo DIFERENTE — `op4`, não `op2` — mantido com nome próprio por clareza).
    private static final int PAUTH_BRANCH_OP4_ZERO_MODIFIER_FIXED = 0b1_1111;
    /// `Rn` fixo das formas sem registrador de alvo explícito (`RETA`/`ERETA` sempre operam sobre
    /// `X30`/estado de exceção implícitos — `ARM DDI 0487`, mesmo raciocínio de
    /// {@link #BRANCH_REGISTER_OPC_ERET}).
    private static final int PAUTH_BRANCH_RN_FIXED = 0b1_1111;
    /// `RETAA`/`RETAB` sempre retornam para `X30` (`ARM DDI 0487 C6.2.245`) — não há campo de
    /// registrador no encoding (diferente de `RET Xn` comum, que aceita qualquer `Xn`).
    private static final int PAUTH_RETA_TARGET_REGISTER = 30;

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
    /// `BRK` (B8.3): `opc` PRÓPRIO (`0b001`, diferente do `0b000` de `SVC`/`HVC`/`SMC`) — sem
    /// sub-forma "LL", `low5` é sempre `0b00000` fixo (`opc2`/`LL` reservados em `0`).
    private static final int EXCEPTION_GEN_OPC_BRK = 0b001;
    /// `HLT` (B8.3): `opc=0b010`, mesmo raciocínio de {@link #EXCEPTION_GEN_OPC_BRK}.
    private static final int EXCEPTION_GEN_OPC_HLT = 0b010;
    /// `low5` fixo de `BRK`/`HLT` (`ARM DDI 0487`, campos `opc2`/`LL` sempre `0` nas duas) —
    /// diferente de `SVC`/`HVC`/`SMC`, que usam `low5` para escolher entre si.
    private static final int EXCEPTION_GEN_BRK_HLT_LOW5_FIXED = 0b0_0000;

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
    private static final int SYSREG_CRN_CPACR = 1;
    private static final int SYSREG_CRM_CPACR = 0;
    private static final int SYSREG_OP2_CPACR = 2;
    // B19.14: RGSR_EL1/GCR_EL1 (FEAT_MTE2) — MESMO CRn/CRm de SCTLR_EL1/CPACR_EL1, só op2 muda.
    private static final int SYSREG_OP2_RGSR_EL1 = 5;
    private static final int SYSREG_OP2_GCR_EL1 = 6;
    private static final int SYSREG_CRN_TTBR0 = 2;
    private static final int SYSREG_CRM_TTBR0 = 0;
    private static final int SYSREG_OP2_TTBR0 = 0;
    private static final int SYSREG_CRN_TTBR1 = 2;
    private static final int SYSREG_CRM_TTBR1 = 0;
    private static final int SYSREG_OP2_TTBR1 = 1;
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

    // ── B10.2: registradores de sistema EL2 (`op0=3,op1=4`) — valores conferidos contra a tabela
    // ── de registradores de sistema real (`aarch64-none-elf-as`/`objdump`, forma genérica
    // ── `S3_4_Cn_Cm_op2`), ver corpus da task.
    private static final int SYSREG_OP1_EL2 = 4;
    private static final int SYSREG_CRN_SCTLR_EL2 = 1;
    private static final int SYSREG_CRM_SCTLR_EL2 = 0;
    private static final int SYSREG_OP2_SCTLR_EL2 = 0;
    private static final int SYSREG_CRN_HCR_EL2 = 1;
    private static final int SYSREG_CRM_HCR_EL2 = 1;
    private static final int SYSREG_OP2_HCR_EL2 = 0;
    private static final int SYSREG_CRN_MDCR_EL2 = 1;
    private static final int SYSREG_CRM_MDCR_EL2 = 1;
    private static final int SYSREG_OP2_MDCR_EL2 = 1;
    private static final int SYSREG_CRN_CPTR_EL2 = 1;
    private static final int SYSREG_CRM_CPTR_EL2 = 1;
    private static final int SYSREG_OP2_CPTR_EL2 = 2;
    private static final int SYSREG_CRN_TCR_EL2 = 2;
    private static final int SYSREG_CRM_TCR_EL2 = 0;
    private static final int SYSREG_OP2_TCR_EL2 = 2;
    // ── B10.6b: `TTBR0_EL2` (mesmo `CRn`/`CRm` de `TCR_EL2`, `op2=0` em vez de `2` — MESMA posição
    // ── relativa de `TTBR0_EL1`/`TCR_EL1` no grupo EL1&0 acima).
    private static final int SYSREG_CRN_TTBR0_EL2 = 2;
    private static final int SYSREG_CRM_TTBR0_EL2 = 0;
    private static final int SYSREG_OP2_TTBR0_EL2 = 0;
    private static final int SYSREG_CRN_VTTBR_EL2 = 2;
    private static final int SYSREG_CRM_VTTBR_EL2 = 1;
    private static final int SYSREG_OP2_VTTBR_EL2 = 0;
    private static final int SYSREG_CRN_VTCR_EL2 = 2;
    private static final int SYSREG_CRM_VTCR_EL2 = 1;
    private static final int SYSREG_OP2_VTCR_EL2 = 2;
    private static final int SYSREG_CRN_SPSR_EL2 = 4;
    private static final int SYSREG_CRM_SPSR_EL2 = 0;
    private static final int SYSREG_OP2_SPSR_EL2 = 0;
    private static final int SYSREG_CRN_ELR_EL2 = 4;
    private static final int SYSREG_CRM_ELR_EL2 = 0;
    private static final int SYSREG_OP2_ELR_EL2 = 1;
    private static final int SYSREG_CRN_FAR_EL2 = 6;
    private static final int SYSREG_CRM_FAR_EL2 = 0;
    private static final int SYSREG_OP2_FAR_EL2 = 0;
    private static final int SYSREG_CRN_ESR_EL2 = 5;
    private static final int SYSREG_CRM_ESR_EL2 = 2;
    private static final int SYSREG_OP2_ESR_EL2 = 0;
    private static final int SYSREG_CRN_CNTHCTL_EL2 = 14;
    private static final int SYSREG_CRM_CNTHCTL_EL2 = 1;
    private static final int SYSREG_OP2_CNTHCTL_EL2 = 0;
    private static final int SYSREG_CRN_VBAR_EL2 = 12;
    private static final int SYSREG_CRM_VBAR_EL2 = 0;
    private static final int SYSREG_OP2_VBAR_EL2 = 0;

    // ── B10.3: registradores de sistema EL3 (`op0=3,op1=6`) — mesma disciplina de B10.2 (valores
    // ── conferidos contra a tabela de registradores de sistema real, forma genérica
    // ── `S3_6_Cn_Cm_op2`), ver corpus da task.
    private static final int SYSREG_OP1_EL3 = 6;
    private static final int SYSREG_CRN_SCTLR_EL3 = 1;
    private static final int SYSREG_CRM_SCTLR_EL3 = 0;
    private static final int SYSREG_OP2_SCTLR_EL3 = 0;
    private static final int SYSREG_CRN_SCR_EL3 = 1;
    private static final int SYSREG_CRM_SCR_EL3 = 1;
    private static final int SYSREG_OP2_SCR_EL3 = 0;
    private static final int SYSREG_CRN_CPTR_EL3 = 1;
    private static final int SYSREG_CRM_CPTR_EL3 = 1;
    private static final int SYSREG_OP2_CPTR_EL3 = 2;
    private static final int SYSREG_CRN_MDCR_EL3 = 1;
    private static final int SYSREG_CRM_MDCR_EL3 = 3;
    private static final int SYSREG_OP2_MDCR_EL3 = 1;
    private static final int SYSREG_CRN_SPSR_EL3 = 4;
    private static final int SYSREG_CRM_SPSR_EL3 = 0;
    private static final int SYSREG_OP2_SPSR_EL3 = 0;
    private static final int SYSREG_CRN_ELR_EL3 = 4;
    private static final int SYSREG_CRM_ELR_EL3 = 0;
    private static final int SYSREG_OP2_ELR_EL3 = 1;
    private static final int SYSREG_CRN_VBAR_EL3 = 12;
    private static final int SYSREG_CRM_VBAR_EL3 = 0;
    private static final int SYSREG_OP2_VBAR_EL3 = 0;
    // ── B10.6c: `TTBR0_EL3`/`TCR_EL3` — mesma disciplina de `TTBR0_EL2`/`TCR_EL2` (B10.6b), MESMO
    // ── `CRn`/`CRm`/`op2` relativos, só `op1=6` (grupo EL3 acima).
    private static final int SYSREG_CRN_TTBR0_EL3 = 2;
    private static final int SYSREG_CRM_TTBR0_EL3 = 0;
    private static final int SYSREG_OP2_TTBR0_EL3 = 0;
    private static final int SYSREG_CRN_TCR_EL3 = 2;
    private static final int SYSREG_CRM_TCR_EL3 = 0;
    private static final int SYSREG_OP2_TCR_EL3 = 2;

    // ── B10.7: registradores de debug (`op0=2,op1=0`) — armazenamento puro, sem enforcement de
    // ── RO/WO (ver javadoc de Aarch64SystemRegisterId). Valores conferidos contra
    // ── `target/arm/debug_helper.c` real do QEMU (`WebFetch`, task B10.7), não de memória.
    private static final int SYSREG_OP0_DEBUG = 2;
    private static final int SYSREG_CRN_MDSCR = 0;
    private static final int SYSREG_CRM_MDSCR = 2;
    private static final int SYSREG_OP2_MDSCR = 2;
    private static final int SYSREG_CRN_OSLAR = 1;
    private static final int SYSREG_CRM_OSLAR = 0;
    private static final int SYSREG_OP2_OSLAR = 4;
    private static final int SYSREG_CRN_OSLSR = 1;
    private static final int SYSREG_CRM_OSLSR = 1;
    private static final int SYSREG_OP2_OSLSR = 4;
    private static final int SYSREG_CRN_DBG_BKPT_WATCH = 0;
    private static final int SYSREG_CRM_DBGBVR0 = 0;
    private static final int SYSREG_OP2_DBGBVR = 4;
    private static final int SYSREG_OP2_DBGBCR = 5;
    private static final int SYSREG_OP2_DBGWVR = 6;
    private static final int SYSREG_OP2_DBGWCR = 7;

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
    private static final int SYSREG_CRN_ID_AA64MMFR1 = 0;
    private static final int SYSREG_CRM_ID_AA64MMFR1 = 7;
    private static final int SYSREG_OP2_ID_AA64MMFR1 = 1;
    private static final int SYSREG_CRN_ID_AA64MMFR2 = 0;
    private static final int SYSREG_CRM_ID_AA64MMFR2 = 7;
    private static final int SYSREG_OP2_ID_AA64MMFR2 = 2;
    private static final int SYSREG_CRN_ID_AA64MMFR3 = 0;
    private static final int SYSREG_CRM_ID_AA64MMFR3 = 7;
    private static final int SYSREG_OP2_ID_AA64MMFR3 = 3;
    private static final int SYSREG_CRN_ID_AA64MMFR4 = 0;
    private static final int SYSREG_CRM_ID_AA64MMFR4 = 7;
    private static final int SYSREG_OP2_ID_AA64MMFR4 = 4;
    private static final int SYSREG_CRN_ID_AA64PFR1 = 0;
    private static final int SYSREG_CRM_ID_AA64PFR1 = 4;
    private static final int SYSREG_OP2_ID_AA64PFR1 = 1;
    private static final int SYSREG_CRN_ID_AA64ZFR0 = 0;
    private static final int SYSREG_CRM_ID_AA64ZFR0 = 4;
    private static final int SYSREG_OP2_ID_AA64ZFR0 = 4;
    private static final int SYSREG_CRN_ID_AA64DFR1 = 0;
    private static final int SYSREG_CRM_ID_AA64DFR1 = 5;
    private static final int SYSREG_OP2_ID_AA64DFR1 = 1;
    private static final int SYSREG_CRN_ID_AA64ISAR1 = 0;
    private static final int SYSREG_CRM_ID_AA64ISAR1 = 6;
    private static final int SYSREG_OP2_ID_AA64ISAR1 = 1;
    private static final int SYSREG_CRN_ID_AA64ISAR2 = 0;
    private static final int SYSREG_CRM_ID_AA64ISAR2 = 6;
    private static final int SYSREG_OP2_ID_AA64ISAR2 = 2;
    private static final int SYSREG_CRN_REVIDR = 0;
    private static final int SYSREG_CRM_REVIDR = 0;
    private static final int SYSREG_OP2_REVIDR = 6;
    private static final int SYSREG_CRN_ID_AA64DFR0 = 0;
    private static final int SYSREG_CRM_ID_AA64DFR0 = 5;
    private static final int SYSREG_OP2_ID_AA64DFR0 = 0;
    private static final int SYSREG_CRN_TPIDR_EL1 = 13;
    private static final int SYSREG_CRM_TPIDR_EL1 = 0;
    private static final int SYSREG_OP2_TPIDR_EL1 = 4;
    // B8.14: TPIDR_EL0/TPIDRRO_EL0 (`op0=3,op1=3,CRn=13,CRm=0`) — MESMO CRn de TPIDR_EL1, `op1`
    // diferente (grupo EL0, junto de CTR_EL0/DCZID_EL0/timer) já roteia pro branch certo em
    // decodeSystemRegisterId. Achado real (busybox aarch64/musl, crt0 sempre grava seu bloco TLS
    // aqui antes de main()): sem isso, NENHUM binário aarch64 real com libc chega a rodar.
    private static final int SYSREG_CRM_TPIDR_EL0 = 0;
    private static final int SYSREG_OP2_TPIDR_EL0 = 2;
    private static final int SYSREG_OP2_TPIDRRO_EL0 = 3;
    // B8.15/B8.16: CRn=4 hospeda 2 famílias distintas no grupo EL0 (`op1=3`) — `CRm=2` é
    // NZCV/DAIF (B8.16), `CRm=4` é FPCR/FPSR (B8.15). MESMO CRn, roteado por `CRm` em
    // decodeCrn4RegisterId (não são a mesma tabela — teria colisão se checássemos só CRn).
    private static final int SYSREG_CRN_PROCESS_STATE = 4;
    private static final int SYSREG_CRM_NZCV_DAIF = 2;
    private static final int SYSREG_OP2_NZCV = 0;
    private static final int SYSREG_OP2_DAIF = 1;
    // B8.17: DIT/SSBS/TCO reaproveitam o MESMO CRn=4,CRm=2 de NZCV/DAIF no grupo EL0 (só op2
    // muda) — armazenamento puro sem efeito real, mesma disciplina já aplicada por B8.3 à forma
    // `MSR (immediate)` destes 3 campos (nenhum consumidor modelado: sem telemetria de DIT, sem
    // Spectre/SSBS real, sem tags MTE que TCO afetaria).
    private static final int SYSREG_OP2_DIT = 5;
    private static final int SYSREG_OP2_SSBS = 6;
    private static final int SYSREG_OP2_TCO = 7;
    // B8.17: SPSel/PAN/UAO (`op0=3,op1=0,CRn=4,CRm=2`) e ALLINT (`CRm=3,op2=0`) — grupo EL1
    // "geral" (mesma disciplina de armazenamento puro, sem efeito real: `sp()` já ignora SPSel,
    // sem MMU checando PAN/UAO).
    private static final int SYSREG_CRN_PSTATE_FIELDS_EL1 = 4;
    private static final int SYSREG_CRM_SPSEL_PAN_UAO = 2;
    private static final int SYSREG_OP2_SPSEL = 0;
    private static final int SYSREG_OP2_PAN = 3;
    private static final int SYSREG_OP2_UAO = 4;
    private static final int SYSREG_CRM_ALLINT = 3;
    private static final int SYSREG_OP2_ALLINT = 0;
    private static final int SYSREG_CRM_FPCR_FPSR = 4;
    private static final int SYSREG_OP2_FPCR = 0;
    private static final int SYSREG_OP2_FPSR = 1;
    // B19.11a: FPMR reaproveita o MESMO CRn/CRm de FPCR/FPSR (só op2 muda) — `FEAT_FPMR`, que
    // `FEAT_FP8` implica (gateado pela mesma feature, ver Aarch64SystemRegisterId#FPMR).
    private static final int SYSREG_OP2_FPMR = 2;
    // B8.16: CNTVCT_EL0 reaproveita CRm=0 do timer físico (só op2 muda); CNTV_TVAL/CTL/CVAL_EL0
    // são o timer VIRTUAL, mesmo layout de CNTP_* (B6.6.7) em CRm=3 em vez de CRm=2.
    private static final int SYSREG_OP2_CNTVCT = 2;
    private static final int SYSREG_CRM_CNTV = 3;

    // ── B6.6.7: timer genérico, `op0=3`/`op1=3` (registradores acessíveis de EL0, "CNT*_EL0" —
    // ── diferente do resto da tabela, que é toda `op1=0`), CRn=0b1110 fixo (grupo Generic Timer).
    private static final int SYSREG_OP1_EL0_TIMER = 3;
    private static final int SYSREG_CRN_TIMER = 14;

    // ── B6.10: identidade de cache, MESMO `op0=3`/`op1=3` do timer acima, mas CRn=0 (grupo de
    // ── identificação EL0, distinto do grupo timer por CRn) — valores conferidos contra
    // ── `target/arm/helper.c` real do QEMU (`id_cp_reginfo`/`DCZID_EL0`), ver a task.
    private static final int SYSREG_CRN_CACHE_IDENTITY = 0;
    private static final int SYSREG_CRM_CTR = 0;
    private static final int SYSREG_OP2_CTR_EL0 = 1;
    private static final int SYSREG_CRM_DCZID = 0;
    private static final int SYSREG_OP2_DCZID_EL0 = 7;
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
    // ── B8.3: `CLREX`/`DSB` (variante `nXS`, `FEAT_XS`)/`SB` (`FEAT_SB`) — MESMO subgrupo
    // ── "Barriers" (`CRn=0b0011`), só `op2` novo. `DSB_nXS` ignora o campo `CRm` (domain, RES0
    // ── nesta variante segundo o próprio `a64.decode` do QEMU — "types always equals
    // ── MBReqTypes_All and we ignore the domain bits") — mesmo tratamento de `BARRIER` (NOP, sem
    // ── cache/pipeline modelados); `SB` idem (sem especulação modelada). `CLREX` TEM semântica
    // ── própria (fecha o monitor de exclusividade, ver {@link Ir64SystemInstructionOp#CLEAR_EXCLUSIVE}).
    private static final int SYSTEM_INSTRUCTION_BARRIER_OP2_CLREX = 0b010;
    private static final int SYSTEM_INSTRUCTION_BARRIER_OP2_DSB_NXS = 0b001;
    private static final int SYSTEM_INSTRUCTION_BARRIER_OP2_SB = 0b111;
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

    // ── B8.3: `WFET`/`WFIT` (`FEAT_WFxT`) — subgrupo PRÓPRIO "System instructions with register
    // ── argument" (`op0=0`, CRn=0b0001 — DIFERENTE do subgrupo "Hints" acima, `CRn=0b0010`), único
    // ── deste bloco inteiro em que `Rt`(4:0) é um registrador de verdade (`Xd`, valor de timeout),
    // ── não o `11111` fixo de barreiras/hints/flag-manip. O registrador é lido pelo decoder só por
    // ── completude posicional — não carregado no `Ir64Op` porque o executor ignora o timeout
    // ── (sem contador/event-stream modelado, mesma simplificação já aplicada a `WFE`/`SEV`).
    private static final int SYSTEM_INSTRUCTION_WAIT_TIMEOUT_CRN = 0b0001;
    private static final int SYSTEM_INSTRUCTION_WAIT_TIMEOUT_OP2_WFET = 0b000;
    private static final int SYSTEM_INSTRUCTION_WAIT_TIMEOUT_OP2_WFIT = 0b001;

    // ── B8.2: `CFINV`/`XAFLAG`/`AXFLAG` (`FEAT_FlagM2`, `op0=0`, CRn=0b0100 fixo — mesmo subgrupo
    // ── de encoding de barreiras/hints acima, `Rt` fixo em `XZR` não checado aqui — mesma
    // ── simplificação já aplicada às barreiras/hints, ver Fatos de referência da task).
    private static final int SYSTEM_INSTRUCTION_FLAG_MANIP_CRN = 0b0100;
    /// `op1` fixo do subgrupo `CFINV`/`XAFLAG`/`AXFLAG`/`UAO`/`PAN`/`SPSel` — **achado real desta
    /// task (G8)**: os formulários `MSR (immediate)` de `op1=0b011` (`SBSS`/`DIT`/`TCO`/
    /// `DAIFSet`/`DAIFClr`) compartilham o MESMO `CRn=0b0100` E alguns dos MESMOS valores de `op2`
    /// (`SBSS.op2=001` colide com `XAFLAG.op2=001`; `DIT.op2=010` colide com `AXFLAG.op2=010`;
    /// `ALLINT.op1=0b001,op2=000` colide com `CFINV.op2=000`) — o decoder ANTES desta task
    /// despachava só por `CRn`+`op2`, sem checar `op1`, e por isso decodificava `MSR SBSS`/`MSR
    /// DIT`/`MSR ALLINT` SILENCIOSAMENTE como `XAFLAG`/`AXFLAG`/`CFINV` (a tabela de cobertura já
    /// marcava as 3 formas `MSR_i_*` como ✅ por esse motivo — "decode teve sucesso" não é "decode
    /// correto", ver o aviso permanente no topo de `docs/COBERTURA-ISA.md`). Corrigido: `op1` agora
    /// É checado antes de despachar por `op2`.
    private static final int SYSTEM_INSTRUCTION_FLAG_MANIP_OP1 = 0b000;
    private static final int SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_CFINV = 0b000;
    private static final int SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_XAFLAG = 0b001;
    private static final int SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_AXFLAG = 0b010;
    // ── B8.3: `UAO`/`PAN`/`SPSel` — MESMO `CRn`/`op1` de `CFINV`/`XAFLAG`/`AXFLAG` acima, `op2`
    // ── novo (sem colisão: `011`/`100`/`101` nunca usados pelas 3 formas de flag). Nenhum efeito
    // ── observável neste emulador — ver javadoc de `Ir64SystemInstructionOp#PSTATE_FIELD_NOP`.
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_UAO = 0b011;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_PAN = 0b100;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_SPSEL = 0b101;
    // ── B8.3: `MSR ALLINT` — MESMO `CRn=0b0100`, `op1` PRÓPRIO (`0b001`, nem o `000` do grupo
    // ── flag/UAO/PAN/SPSel nem o `011` do grupo abaixo) — sem `op1` checado antes desta task,
    // ── colidia com `CFINV` (mesmo `op2=000`, ver o achado acima). Sem efeito observável (mesma
    // ── razão de `PSTATE_FIELD_NOP`: `ALLINT` mascara TODAS as interrupções inclusive `IRQ`, mas
    // ── nada consulta esse campo separado de `PstateRegister#irqDisabled`).
    private static final int SYSTEM_INSTRUCTION_ALLINT_OP1 = 0b001;
    // ── B8.3: `SBSS`/`DIT`/`TCO`/`DAIFSet`/`DAIFClr`/`SVCR` — MESMO `CRn=0b0100`, `op1` PRÓPRIO
    // ── (`0b011`, ver o achado acima). `DAIFSet`/`DAIFClr` TÊM semântica própria sobre
    // ── `PstateRegister#irqDisabled` (bit `I`); `SBSS`/`DIT`/`TCO` são `PSTATE_FIELD_NOP` (mesma
    // ── razão de `UAO`/`PAN`/`SPSel`). B19.28: `SVCR` (`FEAT_SME`) é decodificada explicitamente
    // ── (não cai mais no `default` genérico) — ver `SYSTEM_INSTRUCTION_PSTATE_OP2_SVCR` abaixo.
    private static final int SYSTEM_INSTRUCTION_PSTATE_IMM_OP1 = 0b011;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_SBSS = 0b001;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_DIT = 0b010;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_TCO = 0b100;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_DAIFSET = 0b110;
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_DAIFCLEAR = 0b111;
    // ── B19.28: `MSR SVCR<mask>, #imm` (`FEAT_SME`, ARMv9.2-A) reaproveita o MESMO `op2=0b011`
    // ── ainda não usado por nenhuma das formas acima; o campo `CRm` (normalmente um imediato de 4
    // ── bits para `DAIFSet`/`DAIFClr`) é reparticionado aqui em `0:mask(2):imm(1)` — `mask`
    // ── seleciona Streaming Mode (`0b01`)/estado ZA (`0b10`)/ambos (`0b11`), `imm` é o valor a
    // ── gravar (0 ou 1). Confirmado byte a byte contra `aarch64-linux-gnu-as -march=armv9-a+sme`
    // ── (WSL): `msr svcrsm, #1` monta `0xd503437f` (o assembler mostra como alias `smstart sm`).
    private static final int SYSTEM_INSTRUCTION_PSTATE_OP2_SVCR = 0b011;
    private static final int SYSTEM_INSTRUCTION_SVCR_MASK_FIELD_SHIFT = 1;
    private static final int SYSTEM_INSTRUCTION_SVCR_MASK_FIELD_MASK = 0b11;
    private static final int SYSTEM_INSTRUCTION_SVCR_IMMEDIATE_FIELD_MASK = 0b1;

    // ── TLBI (`op0=1`, `SYS` — não `SYSL`, `L=0`): CRn=0b1000 fixo (grupo TLB maintenance),
    // ── `op1` seleciona o REGIME (EL1&0, EL2 — incl. stage-2 `IPAS2E1*`/`ALLE1`/`VMALLS12E1`,
    // ── que reusam `op1=0b100` conferido contra `tlbi_el1_cp_reginfo`/`tlbi_el2_cp_reginfo` reais
    // ── do QEMU (`target/arm/tcg/tlb-insns.c`) — ou EL3). B8.3 trata QUALQUER `CRm`/`op2` do
    // ── regime EL1&0 como "invalidar tudo" (ver javadoc de `decodeSystemInstructionSys` — sem TLB
    // ── modelada, per-VA/per-ASID/per-IPA não tem como ser mais preciso que "invalidar tudo", e
    // ── over-invalidar nunca corrompe estado); B10.9 estende o MESMO raciocínio para os regimes
    // ── EL2/EL3/stage-2 — nenhum deles tem TLB própria modelada também.
    private static final int SYSTEM_INSTRUCTION_TLBI_OP1_EL1 = 0b000;
    private static final int SYSTEM_INSTRUCTION_TLBI_OP1_EL2 = 0b100;
    private static final int SYSTEM_INSTRUCTION_TLBI_OP1_EL3 = 0b110;
    private static final int SYSTEM_INSTRUCTION_TLBI_CRN = 0b1000;

    // ── Cache maintenance (B6.12, `op0=1`, `SYS`, `L=0`, CRn=0b0111 fixo — "Instruction/Data
    // ── Cache maintenance operations", ARM DDI 0487 C5.4.11/C5.4.12). B8.3 trata QUALQUER
    // ── `op1`/`CRm`/`op2` deste grupo como NOP (mesmo raciocínio de TLBI acima — sem cache
    // ── modelada, qualquer operação de manutenção É um NOP correto), EXCETO `DC ZVA`
    // ── (`CRm=0b0100,op2=0b001`): tem efeito observável real (zera memória) e já é anunciada como
    // ── indisponível via `DCZID_EL0.DZP=1` (B6.10) — se algum guest ignorar isso e emitir `DC ZVA`
    // ── mesmo assim, deve cair no `throw unsupported` (não presumir NOP silencioso).
    private static final int SYSTEM_INSTRUCTION_CACHE_CRN = 0b0111;
    private static final int SYSTEM_INSTRUCTION_CACHE_DC_ZVA_CRM = 0b0100;
    private static final int SYSTEM_INSTRUCTION_CACHE_DC_ZVA_OP2 = 0b001;

    // ── B10.6: `AT` (`op0=1`, `SYS`, `L=0`, `op1=0b000` — MESMO `CRn=0b0111` da manutenção de
    // ── cache acima, mas `CRm=0b1000` — achado real desta task: o `if` genérico de cache maintenance
    // ── de B8.3 tratava QUALQUER `CRn=0b0111` (exceto `DC ZVA`) como NOP, o que incluía `AT` por
    // ── engano (G8 — `AT` tem efeito observável real, escreve `PAR_EL1`, não é um NOP). O carve-out
    // ── de `AT` precisa vir ANTES do bucket genérico de cache no decoder.
    private static final int SYSTEM_INSTRUCTION_AT_STAGE1_CRM = 0b1000;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E1R = 0b000;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E1W = 0b001;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E0R = 0b010;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E0W = 0b011;

    // ── B10.8/B10.6b: `AT S1E2R`/`S1E2W`/`S12E1R`/`S12E1W`/`S12E0R`/`S12E0W` — MESMO `op1=0b100`
    // ── (regime EL2, `SYSTEM_INSTRUCTION_TLBI_OP1_EL2`, reusado aqui), distinguidas só por `op2`
    // ── (conferido contra `cpregs-at.c` real do QEMU: `AT_S1E2R`/`AT_S1E2W` = op2 0/1;
    // ── `AT_S12E1R`/`AT_S12E1W`/`AT_S12E0R`/`AT_S12E0W` = op2 4/5/6/7 — op2 2/3 são reservados).
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E2R = 0b000;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E2W = 0b001;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S12E1R = 0b100;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S12E1W = 0b101;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S12E0R = 0b110;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S12E0W = 0b111;

    // ── B10.6c: `AT S1E3R`/`S1E3W` — `op1=0b110` (regime EL3, `SYSTEM_INSTRUCTION_TLBI_OP1_EL3`),
    // ── MESMO `CRn=0b0111`/`CRm=0b1000` de `AT` acima; `op2` 0/1 (conferido contra `cpregs-at.c`
    // ── real do QEMU: `AT_S1E3R`/`AT_S1E3W`, sem formas combinadas `S12E3*` — EL3 não tem stage-2).
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E3R = 0b000;
    private static final int SYSTEM_INSTRUCTION_AT_OP2_S1E3W = 0b001;

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

    // ── B11.3 (auditoria de versão A64): dentro de SUBCLASS_LITERAL, `LDR (literal)`/`LDRSW ────
    // ── (literal)`/`PRFM (literal)` reais exigem bit24=0 (ARM DDI 0487 C4.1.3, `@ldlit`/`LD_lit` do
    // ── `a64.decode` real do QEMU) — bit24=1 no mesmo bucket é o espaço "Memory Copy and Memory
    // ── Set"/"Atomic 128-bit memory operations" (FEAT_MOPS/FEAT_LSE128, `CPYFP`/`CPYFM`/`CPYFE`/
    // ── `SETP`/`SETM`/`SETE`/`LDCLRP`/`LDSETP`/`SWPP`), que `decodeLoadLiteral` não modela — sem
    // ── este bit checado, esses 9 mnemônicos eram silenciosamente misdecodificados como `LDR
    // ── (literal)` (bug real achado auditando `docs/COBERTURA-ISA.md`, confirmado por probe direto
    // ── no decoder). G8: recusar em vez de confundir.
    private static final int LITERAL_SUBCLASS_RESERVED_BIT_SHIFT = 24;

    // ── B19.16: dentro do bucket acima (bit24=1), bit21 separa "Memory Copy and Memory Set" ──────
    // ── (`SETP`/`SETM`/`SETE`/`CPYFP`/`CPYFM`/`CPYFE`/`CPYP`/`CPYM`/`CPYE`, este grupo) de ────────
    // ── "Atomic 128-bit memory operations" (`LDCLRP`/`LDSETP`/`SWPP`, `FEAT_LSE128`, fora do ──────
    // ── escopo — B19.25); dentro de "Memory Copy and Memory Set", bits[23:22] valem `11` SÓ nas ───
    // ── 3 formas `SET*` (`SETP`/`SETM`/`SETE`/`SETGP`/`SETGM`/`SETGE` — nunca em `CPY*`, que usa ───
    // ── os mesmos 2 bits como campo de FASE prólogo/principal/epílogo); bit26 (MESMA posição do ────
    // ── bit `V` de SIMD&FP em outros buckets, mas aqui é parte do OPCODE, não um seletor real) ────
    // ── distingue `SETP` (bit26=0, esta task) de `SETGP` com tag (bit26=1, B19.14, ainda ──────────
    // ── recusado aqui) e `CPYF*` forward-only (bit26=0) de `CPY*` genérico (bit26=1, direção por ───
    // ── sobreposição) — os 2 últimos são AMBOS desta task. Confirmado byte a byte contra
    // ── `a64.decode` real do QEMU (`@set`/`@cpy`, `QEMU_REV` fixado por E11).
    private static final int MOPS_ATOMIC128_BIT_SHIFT = 21;
    private static final int MOPS_PHASE_FIELD_SHIFT = 22; // bits[23:22]
    private static final int MOPS_PHASE_FIELD_MASK = 0b11;
    private static final int MOPS_SET_MARKER_PHASE_FIELD = 0b11; // só SET*, nunca CPY*
    private static final int MOPS_TAG_OR_GENERIC_DIRECTION_BIT_SHIFT = 26; // mesma posição de VECTOR_FORM_BIT_SHIFT
    private static final int MOPS_RS_SHIFT = 16;
    private static final int MOPS_SET_PHASE_SHIFT = 14; // bits[15:14], só usado pela família SET*
    private static final int MOPS_PHASE_PROLOGUE = 0b00;
    private static final int MOPS_PHASE_MAIN = 0b01;
    private static final int MOPS_PHASE_EPILOGUE = 0b10;
    // ── bits[11:10] fixo "01" em `SETP`/.../`CPYE` — DISCRIMINADOR contra `LDAPR_i`/`STLR_i` ──────
    // ── (`FEAT_LRCPC2`, `@ldapr_stlr_i`, B19.19), que fixam "00" no MESMO campo e por acaso ────────
    // ── compartilham o prefixo `011001` de bits[29:24] com a família `SET*`/`CPYF*` (bit26=0) ──────
    // ── inteira — achado real da B19.16, confirmado contra `a64.decode` real do QEMU.
    private static final int MOPS_FIXED_BITS_11_10_SHIFT = 10;
    private static final int MOPS_FIXED_BITS_11_10_MASK = 0b11;
    private static final int MOPS_FIXED_BITS_11_10_VALUE = 0b01;
    /// `LDAPR_i`/`STLR_i` (`FEAT_LRCPC2`, B19.19) fixam bits[11:10]="00" no MESMO campo que
    /// discrimina MOPS acima — ver o Javadoc de {@link #decodeLoadAcquireOrStoreReleaseUnscaledImmediate}.
    private static final int LDAPR_STLR_I_FIXED_BITS_11_10_VALUE = 0b00;

    // ── B19.14: `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G`/`STGM`/`LDGM`/`STZGM` (`FEAT_MTE2`) — MESMO ────────
    // ── prefixo de 6 bits[29:24]="011001" da família `SET*`/`CPYF*`/`LDAPR_i` acima, mas
    // ── bits[31:30]="11" (a família MOPS/`LDAPR_i`/atomic128 nunca tem essa combinação) — checado
    // ── ANTES de `MOPS_ATOMIC128_BIT_SHIFT` porque o bit21 desta família é "1" fixo por
    // ── coincidência de encoding, e colidiria com o gate de `FEAT_LSE128` (B19.25) sem esta
    // ── checagem (achado real desta task).
    private static final int MOPS_SIZE_FIELD_SHIFT = 30; // bits[31:30]
    private static final int MOPS_SIZE_FIELD_MASK = 0b11;
    private static final int MEMORY_TAG_FAMILY_SIZE_FIELD_VALUE = 0b11;
    private static final int TAG_FAMILY_SIZE_SHIFT = 22; // bits[23:22]
    private static final int TAG_FAMILY_SIZE_MASK = 0b11;
    private static final int TAG_FAMILY_VARIANT_SHIFT = 10; // bits[11:10]
    private static final int TAG_FAMILY_VARIANT_MASK = 0b11;
    private static final int TAG_FAMILY_VARIANT_MULTIPLE = 0b00;
    private static final int TAG_FAMILY_VARIANT_POST_INDEX = 0b01;
    private static final int TAG_FAMILY_VARIANT_OFFSET = 0b10;
    private static final int TAG_FAMILY_VARIANT_PRE_INDEX = 0b11;
    private static final int TAG_FAMILY_IMM9_SHIFT = 12;
    private static final int TAG_FAMILY_IMM9_BITS = 9;
    private static final int MEMORY_TAG_GRANULE_SCALE_BYTES = 16;
    private static final int TAG_FAMILY_SIZE_STZGM = 0b00;
    private static final int TAG_FAMILY_SIZE_LDG = 0b01;
    private static final int TAG_FAMILY_SIZE_STGM = 0b10;
    private static final int TAG_FAMILY_SIZE_LDGM = 0b11;

    // ── B19.14: `SUBP`/`SUBPS`/`IRG`/`GMI` (`FEAT_MTE2`) — "Data-processing (2 source)"
    // ── (`opc2`=`0b00`, MESMO subgrupo de `PACGA`/`CRC32*`, opcode(15:10) reservado que nenhum
    // ── deles usa); `SUBPS` é a ÚNICA das 4 com `opc2`=`0b01` (achado real: o comentário antigo
    // ── desta função dizia "opc2=01/11: SUBP/SUBPS/IRG/GMI", mas só `SUBPS` mede `opc2=01` de
    // ── verdade — confirmado byte a byte contra `a64.decode` real do QEMU).
    private static final int DP_SOURCE_OPC2_SUBPS = 0b01;
    private static final int MTE_REGISTER_OPCODE_SHIFT = 10; // bits[15:10], MESMO campo de pacgaOpcode6
    private static final int MTE_REGISTER_OPCODE_MASK = 0b11_1111;
    private static final int SUBP_OPCODE_PATTERN = 0b00_0000;
    private static final int IRG_OPCODE_PATTERN = 0b00_0100;
    private static final int GMI_OPCODE_PATTERN = 0b00_0101;

    // ── B19.21: `SMAX`/`SMIN`/`UMAX`/`UMIN` (`FEAT_CSSC`) — MESMO subgrupo "Data-processing
    // ── (2 source)" (opc2=00) de SUBP/IRG/GMI/PACGA/CRC32* acima, campo de 6 bits reaproveitado
    // ── (`MTE_REGISTER_OPCODE_SHIFT`/`_MASK`, mesma posição bits[15:10]).
    private static final int MIN_MAX_OPCODE_SMAX = 0b01_1000;
    private static final int MIN_MAX_OPCODE_UMAX = 0b01_1001;
    private static final int MIN_MAX_OPCODE_SMIN = 0b01_1010;
    private static final int MIN_MAX_OPCODE_UMIN = 0b01_1011;

    // ── B19.25: "Atomic 128-bit memory operations" (`LDCLRP`/`LDSETP`/`SWPP`, `FEAT_LSE128`), o ────
    // ── outro lado do MESMO `MOPS_ATOMIC128_BIT_SHIFT` (bit21=1) que a B19.16 deixou recusado. ──────
    // ── Layout confirmado byte a byte contra `a64.decode` real do QEMU (`&atomic128 rn rt rt2 a r`,
    // ── `@atomic128 ........ a:1 r:1 . rt2:5 ...... rn:5 rt:5`) e contra `do_atomic128_ld` em
    // ── `target/arm/tcg/translate-a64.c` (mesma revisão fixada por E11): `a`(bit23)/`r`(bit22) são
    // ── acquire/release, NOP observável (mesmo motivo de `AtomicMemoryOp#acquire`/`#release`);
    // ── `rt2` (bits[20:16]) e `rt` (bits[4:0]) formam o par de 128 bits — AO CONTRÁRIO de `CASP`
    // ── (que deriva o companheiro como `rs|1`/`rt|1`), aqui os DOIS registradores são campos
    // ── explícitos do encoding, sem relação par/ímpar alguma (confirmado: o assembler aceita
    // ── `ldclrp x2, x4, [x5]`, registradores não consecutivos).
    private static final int ATOMIC128_ACQUIRE_SHIFT = 23;
    private static final int ATOMIC128_RELEASE_SHIFT = 22;
    private static final int ATOMIC128_RT2_SHIFT = 16;
    private static final int ATOMIC128_OPCODE_SHIFT = 10;
    private static final int ATOMIC128_OPCODE_MASK = 0b11_1111;
    private static final int ATOMIC128_OPCODE_LDCLRP = 0b00_0100;
    private static final int ATOMIC128_OPCODE_LDSETP = 0b00_1100;
    private static final int ATOMIC128_OPCODE_SWPP = 0b10_0000;
    /// `do_atomic128_ld` recusa (`return false`, encoding não-alocado) quando `rt`/`rt2` é `XZR`
    /// (`31`) ou quando `rt == rt2` — o par de 128 bits precisa de dois registradores de
    /// armazenamento distintos e reais (o valor antigo é escrito de volta neles). G8: recusar em
    /// vez de executar com semântica indefinida.
    private static final int ATOMIC128_XZR_INDEX = 0b1_1111;

    // ── GCSSTR/GCSSTTR (`FEAT_GCS`, B19.27, `a64.decode` linha 588): `11011001 000 11111 000 ────
    // ── unpriv:1 11 rn:5 rt:5` — mesmo bucket bit24=1 de MOPS/LSE128 acima, distinguido por um ────
    // ── prefixo fixo próprio nos bits[31:13]+[11:10] (bit12=`unpriv`, bits[9:5]=`rn`, ─────────────
    // ── bits[4:0]=`rt` livres). Mask/value com `unpriv=rn=rt=0`. ────────────────────────────────
    private static final int GCSSTR_FIXED_MASK = 0xFFFFEC00;
    private static final int GCSSTR_FIXED_VALUE = 0xD91F0C00;

    // ── AdvSIMD load/store multiple/single structures (`LD1`-`LD4`/`ST1`-`ST4`/`LD1R`-`LD4R`, ────
    // ── B8.6): V=1 dentro da classe Loads-and-Stores, bit31 fixo=0, bit30=Q, bits[29:24] fixo ─────
    // ── "001100"(múltiplas)/"001101"(única) — fatos conferidos contra `a64.decode`/ ───────────────
    // ── `translate-a64.c` reais do QEMU (`@ldst_mult`/`@ldst_single_*`/`LD_single_repl`, ver ───────
    // ── `trans_LD_mult`/`trans_ST_mult`/`trans_LD_single`/`trans_ST_single`/`trans_LD_single_repl`)
    private static final int ADVSIMD_LDST_FIXED_SHIFT = 24;
    private static final int ADVSIMD_LDST_FIXED_MASK = 0b11_1111;
    private static final int ADVSIMD_LDST_MULTIPLE_PATTERN = 0b00_1100;
    private static final int ADVSIMD_LDST_SINGLE_PATTERN = 0b00_1101;
    private static final int ADVSIMD_LDST_Q_SHIFT = 30;
    private static final int ADVSIMD_LDST_POST_INDEX_SHIFT = 23; // p
    private static final int ADVSIMD_LDST_LOAD_SHIFT = 22; // L
    private static final int ADVSIMD_LDST_RM_SHIFT = 16;
    /// `Rm=11111`: pós-índice IMEDIATO (avança pelo tamanho total transferido) em vez de um
    /// registrador `X` real — mesma convenção de `LDR`/`STR` pós-indexado por registrador.
    private static final int ADVSIMD_LDST_RM_IMMEDIATE_ENCODING = 0b1_1111;

    // ── Multiple structures: opcode(15:12) escolhe rpt/selem (`@ldst_mult`), sz(11:10) é o ────────
    // ── log2 do tamanho do elemento — nome de cada constante segue (rpt × selem), não o mnemônico ─
    // ── `LDn`/`STn` (que depende de `selem` sozinho: `selem=1` é sempre `LD1`/`ST1`, mesmo com ─────
    // ── `rpt>1` para múltiplos registradores). ──────────────────────────────────────────────────
    private static final int ADVSIMD_LDST_MULT_OPCODE_SHIFT = 12;
    private static final int ADVSIMD_LDST_MULT_OPCODE_MASK = 0b1111;
    private static final int ADVSIMD_LDST_MULT_SIZE_SHIFT = 10;
    private static final int ADVSIMD_LDST_MULT_SIZE_MASK = 0b11;
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM4 = 0b0000; // rpt=1 selem=4 (LD4/ST4)
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM1_X4REG = 0b0010; // rpt=4 selem=1
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM3 = 0b0100; // rpt=1 selem=3 (LD3/ST3)
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM1_X3REG = 0b0110; // rpt=3 selem=1
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM1_X1REG = 0b0111; // rpt=1 selem=1
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM2 = 0b1000; // rpt=1 selem=2 (LD2/ST2)
    private static final int ADVSIMD_LDST_MULT_OPCODE_SELEM1_X2REG = 0b1010; // rpt=2 selem=1
    /// `elementSizeLog2` de doubleword — combinado com `!Q` e `selem!=1` é UNALLOCATED
    /// (`.1D` não existe fora de `LD1`/`ST1`; só o arranjo `.2D` de 128 bits comporta o elemento de
    /// 8 bytes quando há mais de 1 estrutura entrelaçada).
    private static final int ADVSIMD_LDST_ELEMENT_SIZE_LOG2_DOUBLEWORD = 3;

    // ── Single structure (`@ldst_single_*`/`LD_single_repl`): selem vem de 2 bits ESPALHADOS ───────
    // ── (bit13 alto, bit21/`S` baixo, `%ldst_single_selem` real do QEMU) — mesmos 2 bits nas 3 ─────
    // ── formas (byte/half/word-ou-double, replicar). bits[15:14] escolhem a família de tamanho; ────
    // ── dentro dela, os bits restantes codificam `scale`/`index` de um jeito ESPECÍFICO por ────────
    // ── tamanho (ver `decodeAdvancedSimdLoadStoreSingle`). ─────────────────────────────────────────
    private static final int ADVSIMD_LDST_SINGLE_SELEM_LOW_SHIFT = 13;
    private static final int ADVSIMD_LDST_SINGLE_SELEM_HIGH_SHIFT = 21; // S
    private static final int ADVSIMD_LDST_SINGLE_OPC_HIGH_SHIFT = 14;
    private static final int ADVSIMD_LDST_SINGLE_OPC_HIGH_MASK = 0b11;
    private static final int ADVSIMD_LDST_SINGLE_OPC_HIGH_BYTE = 0b00;
    private static final int ADVSIMD_LDST_SINGLE_OPC_HIGH_HALF = 0b01;
    private static final int ADVSIMD_LDST_SINGLE_OPC_HIGH_WORD_OR_DOUBLE = 0b10;
    private static final int ADVSIMD_LDST_SINGLE_OPC_HIGH_REPLICATE = 0b11;
    private static final int ADVSIMD_LDST_SINGLE_INDEX_LOW_BYTE_SHIFT = 10;
    private static final int ADVSIMD_LDST_SINGLE_INDEX_LOW_BYTE_MASK = 0b111;
    private static final int ADVSIMD_LDST_SINGLE_HALF_RESERVED_BIT_SHIFT = 10;
    private static final int ADVSIMD_LDST_SINGLE_INDEX_LOW_HALF_SHIFT = 11;
    private static final int ADVSIMD_LDST_SINGLE_INDEX_LOW_HALF_MASK = 0b11;
    private static final int ADVSIMD_LDST_SINGLE_WORD_OR_DOUBLE_BIT_SHIFT = 10; // 0=word, 1=double
    private static final int ADVSIMD_LDST_SINGLE_WORD_RESERVED_BIT_SHIFT = 11;
    private static final int ADVSIMD_LDST_SINGLE_INDEX_WORD_BIT_SHIFT = 12;
    private static final int ADVSIMD_LDST_SINGLE_DOUBLE_RESERVED_SHIFT = 11;
    private static final int ADVSIMD_LDST_SINGLE_DOUBLE_RESERVED_MASK = 0b11;
    private static final int ADVSIMD_LDST_SINGLE_REPL_RESERVED_BIT_SHIFT = 12;
    private static final int ADVSIMD_LDST_SINGLE_REPL_SCALE_SHIFT = 10;
    private static final int ADVSIMD_LDST_SINGLE_REPL_SCALE_MASK = 0b11;

    // ── LDXR/LDAXR/STXR/STLXR (`@stxr`, B6.3.4): sz(31:30) 001000(29:24) form(23:21) rs(20:16) ──
    // ── lasr(15) rt2(14:10, fixo 11111 nesta forma não-par) rn(9:5) rt(4:0) — Fatos de ─────────
    // ── referência #1 da task b6.3.4-aarch64-exclusive-monitor.md. `sz` reaproveita a MESMA ─────
    // ── codificação/posição de bit de `SINGLE_SIZE_SHIFT`/`SIZE_BYTE`/... (LDR/STR normal). ─────
    private static final int EXCLUSIVE_FORM_SHIFT = 21;
    private static final int EXCLUSIVE_FORM_MASK = 0b111;
    private static final int EXCLUSIVE_FORM_STXR = 0b000; // inclui STLXR (lasr=1)
    private static final int EXCLUSIVE_FORM_LDXR = 0b010; // inclui LDAXR (lasr=1)
    private static final int EXCLUSIVE_FORM_STLR = 0b100; // inclui STLLR
    private static final int EXCLUSIVE_FORM_LDAR = 0b110; // inclui LDLAR
    /// Bit22 (bit central de {@link #EXCLUSIVE_FORM_SHIFT}) dentro do grupo par
    /// `STXP`/`LDXP` (B8.1): `0`=`STXP`, `1`=`LDXP` — mesma posição relativa de
    /// {@link #EXCLUSIVE_FORM_LDXR} vs. {@link #EXCLUSIVE_FORM_STXR}.
    private static final int EXCLUSIVE_FORM_PAIR_LOAD_BIT = 0b010;
    /// Máscara que ignora bit22 (o bit `L`/acquire de `CASP`/`CAS`, não distinguido nesta
    /// implementação — ver javadoc de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.CompareAndSwap}):
    /// isola bit23+bit21 para reconhecer o GRUPO (`STXP`/`LDXP`/`CASP` vs. `CAS`) antes de
    /// desambiguar por {@link #EXCLUSIVE_PAIR_FIXED_BIT31_SHIFT}.
    private static final int EXCLUSIVE_FORM_MASK_IGNORE_L = 0b101;
    /// `STXP`/`LDXP`/`CASP` compartilham bit23=0,bit21=1 (`form & MASK_IGNORE_L == 0b001`);
    /// distinguidos entre si só por bit31 (fixo `1` em `STXP`/`LDXP`, fixo `0` em `CASP` — ver
    /// {@link #EXCLUSIVE_PAIR_FIXED_BIT31_SHIFT}).
    private static final int EXCLUSIVE_FORM_PAIR_OR_CASP = 0b001;
    /// `CAS` tem bit23=1,bit21=1 (`form & MASK_IGNORE_L == 0b101`) — sem restrição de bit31 (o
    /// `sz` de `CAS` usa as 2 bits completos, byte/half/word/doubleword).
    private static final int EXCLUSIVE_FORM_CAS = 0b101;
    /// Bit31: fixo `1` em `STXP`/`LDXP` (restringe `sz` efetivo a `WORD`/`DOUBLEWORD`, já que o
    /// campo de tamanho reaproveita {@link #SINGLE_SIZE_SHIFT}), fixo `0` em `CASP` — único jeito
    /// de desambiguar as duas famílias quando `form & MASK_IGNORE_L == PAIR_OR_CASP` (fatos
    /// conferidos contra `a64.decode` real do QEMU, task B8.1).
    private static final int EXCLUSIVE_PAIR_FIXED_BIT31_SHIFT = 31;
    private static final int EXCLUSIVE_RS_SHIFT = 16;
    private static final int EXCLUSIVE_LASR_SHIFT = 15;
    /// Posição de `Rt2` nas formas de PAR (`STXP`/`LDXP`, B8.1) — mesmo deslocamento de
    /// {@link #PAIR_RT2_SHIFT} (coincidência de layout, não reaproveitado por serem campos de
    /// classes de encoding diferentes).
    private static final int EXCLUSIVE_RT2_SHIFT = 10;

    // ── LDR (literal): opc(31:30) 011 V 00 imm19(23:5) Rt(4:0) ──────────────────────────────
    private static final int LITERAL_OPC_SHIFT = 30;
    private static final int LITERAL_OPC_MASK = 0b11;
    private static final int LITERAL_OPC_32BIT = 0b00;
    private static final int LITERAL_OPC_64BIT = 0b01;
    private static final int LITERAL_OPC_LDRSW = 0b10;
    private static final int LITERAL_OPC_PRFM = 0b11;
    private static final int LITERAL_IMM19_SHIFT = 5;
    private static final int LITERAL_IMM19_BITS = 19;
    private static final int LITERAL_BYTES_PER_UNIT = 4;

    // ── LDP/STP: opc(31:30) 101 V(26) addrMode(24:23) L(22) imm7(21:15) Rt2(14:10) Rn(9:5) ───
    // ── Rt(4:0) ──────────────────────────────────────────────────────────────────────────────
    private static final int PAIR_OPC_SHIFT = 30;
    private static final int PAIR_OPC_MASK = 0b11;
    private static final int PAIR_OPC_32BIT = 0b00;
    /// `opc=01`: `LDPSW` quando `L=1` (par de 32 bits com sinal, único par com sinal — não existe
    /// `STP` correspondente); `STGP` quando `L=0` (ARMv8.5 MTE, fora do Cortex-A53 alvo — B8.1).
    private static final int PAIR_OPC_32BIT_SIGNED = 0b01;
    private static final int PAIR_OPC_64BIT = 0b10;
    private static final int PAIR_ADDR_MODE_SHIFT = 23;
    private static final int PAIR_ADDR_MODE_MASK = 0b11;
    /// "Sem índice" (`STNP`/`LDNP` no manual — dica de não-alocação em cache): mesmo
    /// endereçamento funcional de {@link #PAIR_ADDR_MODE_OFFSET} (sem writeback), já que este
    /// emulador não modela cache/hints (B8.1).
    private static final int PAIR_ADDR_MODE_NO_ALLOC_HINT = 0b00;
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
    /// Bit21: literal fixo `1` na forma REGISTER_OFFSET (junto de `idx=10`) e fixo `0` na forma
    /// "unprivileged" `LDTR`/`STTR` (também `idx=10`) — sem checar este bit, `idx=10` sozinho é
    /// AMBÍGUO entre as duas (bug real corrigido pela B8.1: antes deste bit ser lido, `LDTR`/
    /// `STTR` eram silenciosamente decodificadas como se fossem `LDR`/`STR` de registrador,
    /// interpretando o `imm9` como `Rm`/`option`/`S`). Também distingue `idx=00`
    /// (`LDUR`/`STUR`, bit21=0) das operações atômicas `LDADD`/`LDCLR`/.../`SWP` (bit21=1,
    /// extensão LSE fora do escopo da B8.1).
    private static final int SINGLE_BIT21_SHIFT = 21;
    private static final int SINGLE_IMM9_SHIFT = 12;
    private static final int SINGLE_IMM9_BITS = 9;

    // ── B19.15: `LDRA`(`LDRAA`/`LDRAB`, `FEAT_PAuth`) — MESMO espaço `idx`(bits[11:10])∈{01,11} +
    // ── `bit21`=1 já interceptado acima (achado de tasks anteriores, ver comentário de
    // ── "idx==POST_INDEX/PRE_INDEX com bit21=1"); `size`(bits[31:30]) é SEMPRE `DOUBLEWORD` (só
    // ── existe a forma `X`, ponteiro de 64 bits). `M`(bit23, chave A/B) não afeta o resultado sob
    // ── a rota (b) da task (mesmo precedente de `PointerAuthInPlace`/`decodePauthBranchRegister`).
    // ── `S`(bit22)+`imm9`(bits[20:12]) formam um imediato de 10 bits com sinal, escalado por 8
    // ── bytes (largura de um ponteiro) — `ARM DDI 0487 C6.2.133/134`.
    private static final int LDRA_S_BIT_SHIFT = 22;
    private static final int LDRA_IMM_TOTAL_BITS = SINGLE_IMM9_BITS + 1;
    private static final int LDRA_SCALE_BYTES = 8;
    /// `W`(writeback): mesma posição de bit que `idx`'s bit alto (`bit11`) — `idx=PRE_INDEX`
    /// (`0b11`) tem `W=1` (endereço volta para `Xn`), `idx=POST_INDEX` (`0b01`) tem `W=0` (`Xn`
    /// não muda, mero deslocamento) — nomes de `idx` aqui são só os já existentes de
    /// {@link #decodeLoadStoreSingle}, reaproveitados por posição de bit, não por semântica.
    private static final int SINGLE_RM_SHIFT = 16;
    private static final int SINGLE_OPTION_SHIFT = 13;
    private static final int SINGLE_OPTION_MASK = 0b111;
    private static final int OPTION_UXTW = 0b010;
    private static final int OPTION_LSL = 0b011;
    private static final int OPTION_SXTW = 0b110;
    private static final int OPTION_SXTX = 0b111;
    private static final int SINGLE_SHIFT_FLAG_SHIFT = 12;

    // ── Atomic memory operations (ARM DDI 0487 C3.2.5, B19.1): sub-espaço de `decodeLoadStoreSingle`
    // ── em `idx`(bits[11:10])==00 & bit21==1 & bit24==0. `size`/`Rs`/`Rn`/`Rt` reaproveitam os
    // ── shifts genéricos (SINGLE_SIZE_SHIFT / SINGLE_RM_SHIFT / RN_SHIFT / REGISTER_FIELD_MASK).
    private static final int ATOMIC_ACQUIRE_SHIFT = 23; // `A`
    private static final int ATOMIC_RELEASE_SHIFT = 22; // `R`
    private static final int ATOMIC_O3_SHIFT = 15;
    private static final int ATOMIC_OPC_SHIFT = 12;
    private static final int ATOMIC_OPC_MASK = 0b111;
    private static final int ATOMIC_OPC_LDADD = 0b000;
    private static final int ATOMIC_OPC_LDCLR = 0b001;
    private static final int ATOMIC_OPC_LDEOR = 0b010;
    private static final int ATOMIC_OPC_LDSET = 0b011;
    private static final int ATOMIC_OPC_LDSMAX = 0b100;
    private static final int ATOMIC_OPC_LDSMIN = 0b101;
    private static final int ATOMIC_OPC_LDUMAX = 0b110;
    private static final int ATOMIC_OPC_LDUMIN = 0b111;
    private static final int ATOMIC_O3_OPC_SWP = 0b000;
    private static final int ATOMIC_O3_OPC_LDAPR = 0b100;
    /// `LDAPR` (forma registrador) exige `Rs`=`11111` (`XZR`), `A`=1, `R`=0 fixos no encoding.
    private static final int ATOMIC_LDAPR_RS_FIXED = 0b1_1111;

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
    // ── bits[30:24]="0011110" e bit21=1.
    // ── ⚠️ ARMADILHA REAL (achada na B8.4 rodando o corpus inteiro, não só vizinhos escolhidos à
    // ── mão): usar só bits[28:24]="11110" (5 bits, sem bit30) NÃO basta — "Advanced SIMD scalar
    // ── two-register miscellaneous" (ex. `SQABS_s`/`SQNEG_s`/`SQXTN_s`/`FCVTXN_s`) tem o MESMO
    // ── prefixo de 5 bits E bit21=1, distinguido só pelo bit30 (fixo=0 aqui, fixo=1 lá) —
    // ── CONFERIDO contra `a64.decode` real do QEMU. Esta task ESTENDE `decodeFpTwoSource` para
    // ── opcodes que antes ficavam `unsupported` (4-8); alguns desses valores numéricos coincidem
    // ── com o que instruções SIMD escalares genuínas produzem nesse mesmo campo de bits — sem o
    // ── bit30 no prefixo, elas eram misdecodificadas em silêncio como `FMAX`/`FMINNM`/etc (G8).
    // ── Advanced SIMD vetorial (mesmo bit26=1, prefixo(30:24) DIFERENTE) e Data-processing
    // ── (3-source, prefixo(31:24)="00011111", constante própria abaixo) ficam fora só por não
    // ── bater esse prefixo; `FCCMP`/`FCSEL`/conversões FP<->inteiro (mesmo prefixo+bit21) ficam
    // ── fora por não bater nenhum dos 4 padrões de sub-grupo específicos abaixo.
    private static final int FP_SIMD_CLASS_BIT26_SHIFT = 26;
    private static final int SCALAR_FP_FIXED_PREFIX_SHIFT = 24;
    private static final int SCALAR_FP_FIXED_PREFIX_MASK = 0b111_1111;
    private static final int SCALAR_FP_FIXED_PREFIX_PATTERN = 0b001_1110;
    private static final int SCALAR_FP_BIT21_SHIFT = 21;

    // ── `type` (2 bits, bits[23:22]): MESMA posição nos 4 subgrupos decodificados aqui (2-source/
    // ── 1-source/imediato/compare) — conferido campo a campo contra o assembler real em todos os
    // ── 4, ao contrário do aviso da task de "não presumir a mesma posição sem checar" (Fatos de
    // ── referência #4). `10`/`11` são meia-precisão/reservado, fora de escopo.
    private static final int FP_TYPE_SHIFT = 22;
    private static final int FP_TYPE_MASK = 0b11;
    private static final int FP_TYPE_SINGLE = 0b00;
    private static final int FP_TYPE_DOUBLE = 0b01;
    /// `type` fixo de `FMOV Vd.D[1],Xn`/`FMOV Xd,Vn.D[1]` (B19.6 bloco F) — não é meia-precisão
    /// nem reservado nesta classe específica, ao contrário do que
    /// {@link #decodeFpDoublePrecision} assume para o resto da classe.
    private static final int FP_TYPE_HIGH_HALF_MOVE = 0b10;
    /// `type` fixo de `FMOV_hx`/`FMOV_xh`/`FCVT_s_sh`/`FCVT_s_dh` (`FEAT_FP16`, B19.26) — a forma
    /// "reservado" da tabela ARM DDI 0487 é literalmente meia-precisão nestas classes (CONFERIDO
    /// contra `aarch64-none-elf-as -march=armv8.2-a+fp16`); checar ANTES de
    /// {@link #decodeFpDoublePrecision}, mesmo padrão de {@link #FP_TYPE_HIGH_HALF_MOVE}.
    private static final int FP_TYPE_HALF_PRECISION = 0b11;

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
    /// B8.4: `FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`FNMUL` — mesmo campo `opcode`, valores CONFERIDOS
    /// contra `a64.decode` real do QEMU (`FMUL_s`/`FDIV_s`/`FADD_s`/`FSUB_s`/`FMAX_s`/`FMIN_s`/
    /// `FMAXNM_s`/`FMINNM_s`/`FNMUL_s`, todos `@rrr_hsd`).
    private static final int FP_TWO_SOURCE_OPCODE_FMAX = 0b0100;
    private static final int FP_TWO_SOURCE_OPCODE_FMIN = 0b0101;
    private static final int FP_TWO_SOURCE_OPCODE_FMAXNM = 0b0110;
    private static final int FP_TWO_SOURCE_OPCODE_FMINNM = 0b0111;
    private static final int FP_TWO_SOURCE_OPCODE_FNMUL = 0b1000;
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
    /// B8.4: `FSQRT` — CONFERIDO contra `a64.decode` real do QEMU (`FSQRT_s`, opcode `000011`,
    /// mesmo grupo `@rr_hsd` de `FMOV`/`FABS`/`FNEG`/`FCVT`).
    private static final int FP_ONE_SOURCE_OPCODE_FSQRT = 0b00_0011;
    /// B19.7 (`FEAT_BF16`): `BFCVT` — MESMO `opcode`(bits[20:15]) desta classe, mas `type`(23:22)
    /// fixo em `FP_TYPE_DOUBLE` sem ser conversão double-real (mesmo padrão de `FMOV Vn.D[1]` em
    /// B19.6: checar ANTES de {@link #decodeFpDoublePrecision}).
    private static final int FP_ONE_SOURCE_OPCODE_BFCVT = 0b00_0110;
    /// B19.26 (`FEAT_FP16`): `FCVT_s_hs`/`FCVT_s_hd` — destino meia-precisão a partir de
    /// `type`=SINGLE/DOUBLE; a direção inversa (`FCVT_s_sh`/`FCVT_s_dh`, fonte meia-precisão) usa
    /// os opcodes `FCVT_TO_SINGLE`/`FCVT_TO_DOUBLE` já existentes com `type`={@link
    /// #FP_TYPE_HALF_PRECISION}, ver `decodeFpOneSource`.
    private static final int FP_ONE_SOURCE_OPCODE_FCVT_TO_HALF = 0b00_0111;
    // FRINTx (8+): fora de escopo, ver decodeFpOneSource.

    // ── Floating-point data-processing (3 source) — FMADD/FMSUB/FNMADD/FNMSUB, B8.4. ─────────────
    // ── ARMADILHA (achada rodando o corpus real): bits[28:24]="11111" sozinho NÃO basta — o ──────
    // ── espaço "Advanced SIMD scalar x indexed element"/"scalar shift by immediate" (ex. ──────────
    // ── `FMUL_si`/`SSHR_s`) tem o MESMO padrão de 5 bits em bits[28:24], só bit30 os separa ────────
    // ── (fixo=0 em FMADD/FMSUB/FNMADD/FNMSUB, fixo=1 nos dois grupos SIMD escalares) — CONFERIDO ───
    // ── contra `a64.decode` real do QEMU (única entrada com prefixo de 8 bits "0001 1111"). Por ────
    // ── isso o prefixo aqui usa os 8 bits inteiros (bits[31:24]), não só 5. type(23:22) na MESMA ───
    // ── posição das outras 3 sub-classes escalares; bit21="o1" (nega Va, FNMADD/FNMSUB); ───────────
    // ── Rm(20:16); bit15="o0" (nega Vn, FMSUB/FNMSUB); Ra(14:10); Rn(9:5); Rd(4:0).
    private static final int FP_THREE_SOURCE_FIXED_PREFIX_SHIFT = 24;
    private static final int FP_THREE_SOURCE_FIXED_PREFIX_MASK = 0xFF;
    private static final int FP_THREE_SOURCE_FIXED_PREFIX_PATTERN = 0b0001_1111;
    private static final int FP_THREE_SOURCE_NEGATE_ADDEND_BIT_SHIFT = 21;
    private static final int FP_THREE_SOURCE_O0_BIT_SHIFT = 15;
    private static final int FP_THREE_SOURCE_RA_SHIFT = 10;

    // ── AdvSIMD inteiro — aritmética/comparação (B8.7): "three same"/"three same pairwise" ────────
    // ── (bit10=1), "three different" alargando/largo+estreito/estreitando + "across lanes" + ──────
    // ── "two-register miscellaneous" (bit10=0) — todos dentro do MESMO prefixo bits[28:24]="01110" ──
    // ── (vetorial, `Q`=bit30 real) OU bits[28:24]="11110"+bit30=1 (escalar D-only, mesmo truque de
    // ── prefixo que já distingue "Advanced SIMD scalar two-register miscellaneous" de
    // ── `decodeFpTwoSource` acima). Fatos de referência conferidos contra `a64.decode`/
    // ── `translate-a64.c` reais do QEMU + corpus `aarch64-none-elf-as`/`objdump` (devkitA64).
    private static final int ADVSIMD_INT_PREFIX_SHIFT = 24;
    private static final int ADVSIMD_INT_PREFIX_MASK = 0b1_1111;
    private static final int ADVSIMD_INT_PREFIX_VECTOR_PATTERN = 0b0_1110;
    private static final int ADVSIMD_INT_PREFIX_SCALAR_PATTERN = 0b1_1110;
    private static final int ADVSIMD_INT_SCALAR_BIT30_SHIFT = 30;
    private static final int ADVSIMD_INT_Q_SHIFT = 30;
    private static final int ADVSIMD_INT_U_SHIFT = 29;
    private static final int ADVSIMD_INT_BIT21_SHIFT = 21;
    private static final int ADVSIMD_INT_SIZE_SHIFT = 22;
    private static final int ADVSIMD_INT_SIZE_MASK = 0b11;
    private static final int ADVSIMD_INT_RM_SHIFT = 16;
    private static final int ADVSIMD_INT_RM_MASK = 0b1_1111;
    private static final int ADVSIMD_INT_OPCODE_SHIFT = 11;
    private static final int ADVSIMD_INT_OPCODE_MASK = 0b1_1111;
    private static final int ADVSIMD_INT_BIT10_SHIFT = 10;
    /// B11.4 (`FEAT_RDM`): `SQRDMLAH`/`SQRDMLSH` vetorial/escalar não-indexado vivem no MESMO
    /// prefixo "01110" e MESMO valor de `opcode`(bits[15:11]) que `ADD_v`/`SUB_v` (`0b10000`), mas
    /// com `bit21=0` (`ADD_v`/`SUB_v` têm `bit21=1`) — conferido bit a bit contra `a64.decode` real
    /// do QEMU e corpus devkitA64 (`.arch armv8.1-a`). `SQRDMLSH` usa `0b10001`. `U=1` sempre (não
    /// há forma `U=0` real neste opcode+`bit21=0`).
    private static final int ADVSIMD_RDM_OPCODE_SQRDMLAH = 0b1_0000;
    private static final int ADVSIMD_RDM_OPCODE_SQRDMLSH = 0b1_0001;
    /// Tamanho fixo do escalar D-only (`esz=3`) — a forma vetorial NUNCA produz `esz=3`/`q=false`
    /// de verdade (doubleword exige `q=true` no hardware real, só existe `.2d`), então este par é
    /// reaproveitado como sentinela da forma escalar sem ambiguidade (ver javadoc de
    /// {@link Ir64Op.VectorArithmeticThreeSame}).
    private static final int ADVSIMD_INT_SCALAR_ESZ = 3;
    /// `Rm` fixo="00000" — "two-register miscellaneous" (`ABS`/`NEG`/`CM**0`/`SADDLP`/...).
    private static final int ADVSIMD_INT_RM_TWO_REG_MISC = 0b0_0000;
    /// B8.18: opcode (bits[15:11]) compartilhado por `CNT`/`NOT`/`RBIT` dentro do slot
    /// "two-register miscellaneous" — ver {@link #decodeVectorUnaryByteOnlyOpcode}.
    private static final int ADVSIMD_TWO_REG_MISC_BYTE_ONLY_OPCODE = 0b0_1011;
    /// `Rm` fixo="00001" — narrow/widen unário (`SQXTN`/...), fora de escopo (B8.8).
    private static final int ADVSIMD_INT_RM_NARROW_UNARY = 0b0_0001;
    /// B8.20: opcode (bits[15:11]) de `SHLL`/`SHLL2` dentro do slot narrow/widen (`Rm=00001`,
    /// sempre `U=1`) — ver o desvio em {@link #decodeAdvancedSimdInteger}.
    private static final int ADVSIMD_TWO_REG_MISC_SHLL_OPCODE = 0b0_0111;
    /// B8.20: opcode (bits[15:11]) de `URECPE`/`URSQRTE` dentro do MESMO slot narrow/widen
    /// (`Rm=00001`) — MESMO opcode que `FCVTAS`/`FCVTAU` usam para `esz` `0`/`1` (`a=0`); só
    /// colide na aparência, nunca no valor real (`URECPE`/`URSQRTE` exigem `esz=`
    /// {@link #ADVSIMD_ESZ_WORD}, `a=1`, conferido contra corpus real).
    private static final int ADVSIMD_URECPE_URSQRTE_OPCODE = 0b1_1001;
    /// B19.3: opcode (bits[15:11]) de `FRECPX_s` dentro do slot narrow/widen (`Rm=00001`) — só
    /// forma AdvSIMD-escalar real. MESMO valor que `SQRT` vetorial (`decodeVectorFpUnaryRmOneOpcode`,
    /// `key==0b11`), por isso tratado com `if` EXPLÍCITO no ramo `scalar`, NUNCA pela tabela
    /// compartilhada (senão um encoding vetorial `0b1_1111`/`a=1` decodificaria sem executor — G8).
    private static final int ADVSIMD_FRECPX_OPCODE = 0b1_1111;
    /// B19.3: opcode (bits[15:11]) de `FCVTN`/`FCVTXN`/`BFCVTN` dentro do slot narrow/widen
    /// (`Rm=00001`) — MESMO opcode que `SQXTN`-família (`decodeVectorNarrowUnaryOpcode` devolve
    /// `null` aqui); tratado com `if` EXPLÍCITO, NUNCA pela tabela compartilhada (poluí-la faria um
    /// encoding escalar decodificar para um op sem executor escalar). B19.3 usa este valor para
    /// `FCVTXN_s`; B19.4 o reaproveita para as formas VETORIAIS `FCVTN_v`/`FCVTXN_v` (`bit23`=`a`
    /// separa de `BFCVTN_v`).
    private static final int ADVSIMD_FCVTXN_OPCODE = 0b0_1101;
    /// B19.4: opcode (bits[15:11]) de `FCVTL`/`BF*CVTL`/`F*CVTL` dentro do slot narrow/widen
    /// (`Rm=00001`) — `!u` = `FCVTL_v` (ISA base); `u` = `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL`
    /// (`FEAT_FP8`, B19.11).
    private static final int ADVSIMD_FCVTL_OPCODE = 0b0_1111;
    /// B19.11 (`FEAT_FP8`): opcode (bits[15:11]) de `FCVTN_bh`/`FCVTN_bs` ("AdvSIMD three same
    /// (FP8 convert)", `Rm` é um registrador REAL, não este slot narrow/widen) — MESMO valor que
    /// `FRECPX_s` (`ADVSIMD_FRECPX_OPCODE`) e que `SQRT` vetorial (`decodeVectorFpUnaryRmOneOpcode`,
    /// `key==0b11`) em espaços DIFERENTES (aquele exige `bit21=1`; este exige `bit21=0`, o espaço de
    /// "AdvSIMD three same (FP16)"/RDM) — nunca colidem. Confirmado byte a byte contra `a64.decode`
    /// real (`target/isa-decode/a64.decode:1216-1217`).
    private static final int ADVSIMD_FP8_THREE_SAME_CONVERT_OPCODE = 0b1_1110;
    /// B19.11e (`FEAT_FP8`): opcode (bits[15:11]) de `FSCALE_h` — MESMO valor que, OR'ado com
    /// {@link #ADVSIMD_FP16_OPCODE_TO_SD_BIT}, produz `0b1_1111` (o opcode de `DIV`/`RECPS`/
    /// `RSQRTS`/`FSCALE_sd` em {@link #decodeVectorFpThreeSameOpcode}) — a key `(u=1,a=1)=0b110`
    /// não é mapeada por nenhum dos três, então esta forma nunca colide. Confirmado byte a byte
    /// contra `aarch64-linux-gnu-as -march=armv8.2-a+fp8` (WSL): `fscale v0.8h,v1.8h,v2.8h` monta
    /// `0x6ec23c20`.
    private static final int ADVSIMD_FP8_SCALE_OPCODE_H = 0b0_0111;
    /// B19.11e (`FEAT_FP8`): opcode (bits[15:11]) de `FSCALE_sd` dentro de
    /// {@link #decodeVectorFpThreeSameOpcode} — MESMO valor de `DIV`/`RECPS`/`RSQRTS`, discriminado
    /// pela key `(u,a)=0b110` que nenhum dos três usa (ver Javadoc de
    /// {@link #ADVSIMD_FP8_SCALE_OPCODE_H}).
    private static final int ADVSIMD_FP8_SCALE_OPCODE_SD = 0b1_1111;
    /// B19.5.4 (`FEAT_FP16`): MESMO slot "two-register miscellaneous" de
    /// {@link #ADVSIMD_INT_RM_TWO_REG_MISC} (`0b0_0000`), com `Rm[4:3]=0b11` em vez de `0b00` — o
    /// encoding real fixa `bit22` em `1` para marcar o grupo de meia precisão (nos `_sd` irmãos,
    /// `bit22` é o `sz` de tamanho; aqui não é tamanho nenhum, é só o discriminador do grupo — ver
    /// Armadilha 2 da task). `Rm[2:0]` reproduz o slot original (`000`), por isso
    /// `Rm_h = Rm_sd | 0b1_1000`. `FABS_v`/`FNEG_v`/`FCM**0_{v,s}` vivem aqui.
    private static final int ADVSIMD_INT_RM_FP16_TWO_REG_MISC = 0b1_1000;
    /// B19.5.4 (`FEAT_FP16`): MESMO slot narrow/widen de {@link #ADVSIMD_INT_RM_NARROW_UNARY}
    /// (`0b0_0001`), com `Rm[4:3]=0b11` (`Rm_h = Rm_sd | 0b1_1000`, mesma relação do slot irmão
    /// acima). `FSQRT_v`/`FRINTx_v`/`FRECPE`/`FRSQRTE`/`FRECPX_s`/`SCVTF`/`UCVTF`/as conversões
    /// `@icvt` vivem aqui — `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL`/`BFCVTN_v` (BF16/FP8, B19.7) moram
    /// no slot `0b0_0001` de VERDADE, não neste (Armadilha 4 da task).
    private static final int ADVSIMD_INT_RM_FP16_NARROW_UNARY = 0b1_1001;
    /// B19.3: opcodes (bits[15:11]) da classe "AdvSIMD shift by immediate" que na verdade são
    /// conversão FP↔ponto fixo (`@fcvt_fixed`): `0b1_1100` = `SCVTF`/`UCVTF` (int→FP),
    /// `0b1_1111` = `FCVTZS`/`FCVTZU` (FP→int). `u` (bit29) distingue assinado/não.
    private static final int ADVSIMD_SHIFT_FCVT_FIXED_TO_FLOAT_OPCODE = 0b1_1100;
    private static final int ADVSIMD_SHIFT_FCVT_FIXED_TO_INT_OPCODE = 0b1_1111;
    /// `Rm[4:1]` fixo="1000" (`Rm=0b10000`/`0b10001`, bit0 livre) — "across lanes" (`ADDV`/...)/
    /// `ADDP_s`. E8: só bit4 setado NÃO basta (`Rm=0b11000`/`0b11000+` também tem bit4 setado e é um
    /// registrador livre válido de "three different" — ver o achado de bit11 em
    /// {@link #decodeAdvancedSimdInteger}); a máscara/padrão exigem os 4 bits altos exatos.
    private static final int ADVSIMD_INT_RM_ACROSS_LANES_MASK = 0b1_1110;
    private static final int ADVSIMD_INT_RM_ACROSS_LANES_PATTERN = 0b1_0000;
    /// B8.11: `Rm` fixo="01000" — `AESE`/`AESD`/`AESMC`/`AESIMC` ("Cryptographic AES" do
    /// `a64.decode` real, que aliasa neste mesmo espaço de bits — ver
    /// {@link #decodeAdvancedSimdInteger}).
    private static final int ADVSIMD_AES_RM = 0b0_1000;
    /// B8.11b: campo `opcode` de "Cryptographic three-register SHA" — 6 bits em bits[15:10]
    /// (diferente do `opcode` de 5 bits em bits[15:11] usado pelo resto de `decodeAdvancedSimdInteger`,
    /// porque esta forma nunca checa `bit10` como "threeSameShape" — layout próprio, conferido
    /// contra corpus real).
    private static final int CRYPTO_SHA_THREE_REG_OPCODE_SHIFT = 10;
    private static final int CRYPTO_SHA_THREE_REG_OPCODE_MASK = 0b11_1111;
    /// B19.6 bloco E: `DUP_element_s` (Advanced SIMD scalar copy) — MESMO campo `opcode` de 6 bits
    /// do comentário acima, valor `0b000001` (bit10=1, único ponto do espaço "escalar bit21=0" com
    /// esse bit setado — as 7 opcodes SHA reais são todas pares).
    private static final int ADVSIMD_SCALAR_COPY_OPCODE = 0b00_0001;
    /// B8.9: bit `a` do encoding real de "AdvSIMD three same (FP)"/"two-register misc (FP)" —
    /// posição IDÊNTICA ao bit alto de {@link #ADVSIMD_INT_SIZE_SHIFT} (`esz` inteiro reaproveita
    /// essa posição como campo livre de 2 bits; nas formas FP, só o bit BAIXO — `bit22`, "sz" — é o
    /// tamanho do elemento; o bit ALTO — `bit23`, "a" — é mais um bit de discriminação de opcode,
    /// nunca tamanho). Conferido contra `a64.decode` real do QEMU (`@qrrr_sd`/`@qrr_sd`: `esz=%esz_sd`
    /// deriva só de `sz`, bit22).
    private static final int ADVSIMD_FP_A_BIT_SHIFT = 23;
    /// B19.5.5 (`FEAT_FP16`): relação entre o `opcode` (bits[15:11]) da forma `_h` de "AdvSIMD three
    /// same (FP)" e o da forma `_sd` já tabelada por {@link #decodeVectorFpThreeSameOpcode} —
    /// `opcode_sd = opcode_h | 0b1_1000` (medido bit a bit contra `a64.decode`; MESMA relação
    /// numérica de {@link #ADVSIMD_INT_RM_FP16_TWO_REG_MISC}, mas aplicada ao campo `opcode`, não a
    /// `Rm` — por isso uma constante própria em vez de reusar aquela).
    private static final int ADVSIMD_FP16_OPCODE_TO_SD_BIT = 0b1_1000;
    /// B19.13 (`FEAT_FHM`): `opcode` (bits[15:11]) de "AdvSIMD three same (FP)" que `FMLAL_v`/
    /// `FMLSL_v` (`u=0`) reaproveitam — medido bit a bit contra `a64.decode`/corpus real
    /// (`arm-linux-gnu-as -march=armv8.2-a+fp16+fp16fml`).
    private static final int ADVSIMD_FHM_THREE_SAME_OPCODE_LOW = 0b1_1101;
    /// Idem, para `FMLAL2_v`/`FMLSL2_v` (`u=1`).
    private static final int ADVSIMD_FHM_THREE_SAME_OPCODE_HIGH = 0b1_1001;
    /// B19.13: bits[1:0] do `opcode` indexado (4 bits) reservados/sempre `0` em
    /// `FMLAL_vi`/`FMLSL_vi`/`FMLAL2_vi`/`FMLSL2_vi` — qualquer valor fora de
    /// `0000`/`0100`/`1000`/`1100` não é desta família.
    private static final int ADVSIMD_FHM_INDEXED_OPCODE_RESERVED_MASK = 0b0011;
    /// Bit3 do `opcode` indexado: `0`=`FMLAL_vi`/`FMLSL_vi`, `1`=`FMLAL2_vi`/`FMLSL2_vi`.
    private static final int ADVSIMD_FHM_INDEXED_OPCODE_TOP_BIT = 0b1000;
    /// Bit2 do `opcode` indexado: `0`=soma (`FMLAL*`), `1`=subtração (`FMLSL*`).
    private static final int ADVSIMD_FHM_INDEXED_OPCODE_SUBTRACT_BIT = 0b0100;

    // ── B11.12 (`FEAT_SHA3`): `EOR3`/`BCAX` ("Cryptographic four-register") e `RAX1`/`XAR`
    // ── ("Cryptographic three-register, imm2") — espaço de encoding PRÓPRIO, nunca examinado por
    // ── nenhuma task anterior (achado desta sessão: `Aarch64Feature.SHA3`/B11.3/B11.11 diziam
    // ── "implementado sem gate desde B8.11b", mas B8.11b só cobriu SHA1/SHA256 — EOR3/BCAX/RAX1/
    // ── XAR nunca tiveram decoder algum). Prefixo fixo de 8 bits em bits[31:24] ("11001110"),
    // ── DIFERENTE do prefixo de 5 bits (bits[28:24]) que {@link #decodeAdvancedSimdInteger} usa
    // ── para distinguir vetorial/escalar — mas bits[28:24]="01110" sozinho colide de fato com o
    // ── prefixo "vetorial" de lá (`bit31`/`bit29` nunca são checados naquele método). Por isso este
    // ── prefixo precisa ser checado ANTES de cair em {@link #decodeAdvancedSimdInteger}, senão o
    // ── encoding real do SHA3 seria silenciosamente tratado como AdvSIMD comum (G8) — conferido bit
    // ── a bit contra corpus real (`aarch64-linux-gnu-as`/`objdump`, `.arch armv8.2-a+sha3`).
    private static final int CRYPTO_SHA3_PREFIX_SHIFT = 24;
    private static final int CRYPTO_SHA3_PREFIX_MASK = 0xFF;
    private static final int CRYPTO_SHA3_PREFIX_PATTERN = 0b1100_1110;
    /// Campo `Op0` (bits[23:21], 3 bits) que discrimina as 4 operações dentro do prefixo acima.
    private static final int CRYPTO_SHA3_OP0_SHIFT = 21;
    private static final int CRYPTO_SHA3_OP0_MASK = 0b111;
    private static final int CRYPTO_SHA3_OP0_EOR3 = 0b000;
    private static final int CRYPTO_SHA3_OP0_BCAX = 0b001;
    private static final int CRYPTO_SHA3_OP0_RAX1 = 0b011;
    private static final int CRYPTO_SHA3_OP0_XAR = 0b100;
    /// `Ra` de `EOR3`/`BCAX` (bits[13:10]) — campo de SÓ 4 bits no encoding real (`V0`-`V15`),
    /// diferente de `Rd`/`Rn`/`Rm` (5 bits, `REGISTER_FIELD_MASK`) — confirmado bit a bit contra
    /// corpus real (assembler recusa `Va` fora de `V0`-`V15` com erro "operand out of range").
    private static final int CRYPTO_SHA3_RA_SHIFT = 10;
    private static final int CRYPTO_SHA3_RA_MASK = 0b1111;
    /// `EOR3`/`BCAX`: bits[15:14] fixo="00" (posição ocupada por `imm6`/opcode nas outras formas
    /// deste prefixo) — G8, recusar se não bater em vez de ignorar.
    private static final int CRYPTO_SHA3_FOUR_REG_BIT15_14_SHIFT = 14;
    private static final int CRYPTO_SHA3_FOUR_REG_BIT15_14_MASK = 0b11;
    /// `RAX1`: bits[15:10] fixo="100011" — sem `Ra`/imediato (só 2 registradores fonte), este campo
    /// de 6 bits é puro preenchimento fixo do encoding, não um opcode livre.
    private static final int CRYPTO_SHA3_RAX1_BIT15_10_SHIFT = 10;
    private static final int CRYPTO_SHA3_RAX1_BIT15_10_MASK = 0b11_1111;
    private static final int CRYPTO_SHA3_RAX1_BIT15_10_PATTERN = 0b10_0011;
    /// `XAR`: `imm6` (bits[15:10], rotação à direita, `0`-`63`).
    private static final int CRYPTO_SHA3_XAR_IMM6_SHIFT = 10;
    private static final int CRYPTO_SHA3_XAR_IMM6_MASK = 0b11_1111;
    /// `RAX1` não tem campo de imediato real — o decoder passa `0` para
    /// {@link Ir64Op.CryptoSha3TwoSourceRotate#rotateAmount()} (nunca lido pelo executor para esta
    /// operação, ver o javadoc do record).
    private static final int CRYPTO_SHA3_RAX1_UNUSED_ROTATE_AMOUNT = 0;

    // ── B19.10 (`FEAT_SHA512`/`FEAT_SM3`/`FEAT_SM4`): as 13 linhas vizinhas que a B11.12 deixou no
    // ── MESMO prefixo 0xCE, sem dono. `Op0` ganha 2 valores novos: `0b010` (`SM3SS1`, layout
    // ── IDÊNTICO a `EOR3`/`BCAX` — 4 registradores com `Ra` de 4 bits — e `SM3TT1A/1B/2A/2B`,
    // ── layout PRÓPRIO com `op`+`imm2`, discriminados por bits[15:14]) e `0b110` (`SHA512SU0`/
    // ── `SM4E`, forma de 2 registradores com `Rm`(bits[20:16]) fixo em zero). `Op0=0b011` (já
    // ── reivindicado por `RAX1`) ganha 6 vizinhos NOVOS no mesmo campo de opcode de 6 bits
    // ── (bits[15:10]): `SHA512H`/`SHA512H2`/`SHA512SU1` e `SM3PARTW1`/`SM3PARTW2`/`SM4EKEY`.
    private static final int CRYPTO_SHA3_OP0_SM3_MIX = 0b010;
    private static final int CRYPTO_SHA3_OP0_TWO_REGISTER = 0b110;
    /// Campo de opcode de 6 bits (bits[15:10]) dentro de `Op0=0b011` — MESMA posição/largura de
    /// {@link #CRYPTO_SHA3_RAX1_BIT15_10_SHIFT}/{@link #CRYPTO_SHA3_RAX1_BIT15_10_MASK}, generalizada
    /// aqui porque agora discrimina 7 operações, não só `RAX1`.
    private static final int CRYPTO_THREE_REGISTER_OPCODE6_SHIFT = 10;
    private static final int CRYPTO_THREE_REGISTER_OPCODE6_MASK = 0b11_1111;
    private static final int CRYPTO_OPCODE6_SHA512H = 0b10_0000;
    private static final int CRYPTO_OPCODE6_SHA512H2 = 0b10_0001;
    private static final int CRYPTO_OPCODE6_SHA512SU1 = 0b10_0010;
    private static final int CRYPTO_OPCODE6_SM3PARTW1 = 0b11_0000;
    private static final int CRYPTO_OPCODE6_SM3PARTW2 = 0b11_0001;
    private static final int CRYPTO_OPCODE6_SM4EKEY = 0b11_0010;
    /// `Op0=0b110`: `Rm` (bits[20:16], normalmente o 3º registrador fonte) é fixo em zero — só 2
    /// registradores reais (`Rn`/`Rd`), MESMO truque de `AESE`/`AESD` (B8.11) mas aqui o campo
    /// sobra no encoding em vez de simplesmente não existir.
    private static final int CRYPTO_TWO_REGISTER_RM_FIXED_SHIFT = 16;
    private static final int CRYPTO_TWO_REGISTER_RM_FIXED_MASK = 0b1_1111;
    private static final int CRYPTO_TWO_REGISTER_RM_FIXED_PATTERN = 0;
    /// Opcode de 6 bits (bits[15:10]) dentro de `Op0=0b110` — só 2 valores usados dos 64 possíveis.
    private static final int CRYPTO_TWO_REGISTER_OPCODE6_SHIFT = 10;
    private static final int CRYPTO_TWO_REGISTER_OPCODE6_MASK = 0b11_1111;
    private static final int CRYPTO_OPCODE6_SHA512SU0 = 0b10_0000;
    private static final int CRYPTO_OPCODE6_SM4E = 0b10_0001;
    /// `Op0=0b010`: MESMO bits[15:14] de `EOR3`/`BCAX` (`CRYPTO_SHA3_FOUR_REG_BIT15_14_*`) — `00`
    /// seleciona `SM3SS1` (forma de 4 registradores, `Ra` no mesmo campo de 4 bits); `10` seleciona
    /// `SM3TT1A/1B/2A/2B` (forma própria, `op`+`imm2`).
    private static final int CRYPTO_SM3_MIX_BIT15_14_SM3SS1 = 0b00;
    private static final int CRYPTO_SM3_MIX_BIT15_14_SM3TT = 0b10;
    /// `SM3TT*`: `op` (bits[11:10], seleciona a variante — `00`=`TT1A`, `01`=`TT1B`, `10`=`TT2A`,
    /// `11`=`TT2B`) e `imm2` (bits[13:12], operando real da instrução — CAMPOS DIFERENTES, ver a
    /// Armadilha 3 da task B19.10; confirmado empiricamente contra corpus real: `sm3tt1a v5,v6,v7[3]`
    /// codifica bits[13:12]=`11` e bits[11:10]=`00` — se fosse o inverso, mudar só o `imm2` teria
    /// trocado o MNEMÔNICO para `SM3TT2B`, o que o assembler nunca faria).
    private static final int CRYPTO_SM3TT_OP_SHIFT = 10;
    private static final int CRYPTO_SM3TT_OP_MASK = 0b11;
    private static final int CRYPTO_SM3TT_IMM2_SHIFT = 12;
    private static final int CRYPTO_SM3TT_IMM2_MASK = 0b11;

    /// B8.10: bit alto (bit15, único bit de {@link #ADVSIMD_INT_OPCODE_SHIFT} acima do campo de 4
    /// bits de `EXT`/permute/`TBL`/`TBX`) — quando setado (com `bit10=0`), o encoding é reservado
    /// dentro do espaço EXT/permute/TBL — ver {@link #decodeAdvancedSimdExtractPermuteTable}.
    /// B8.12: usado também dentro de `INS_element` (`bit10=1`), onde `bit15` fixo em `1` é
    /// igualmente reservado — ver {@link #decodeAdvancedSimdCopy}.
    private static final int ADVSIMD_EXTRACT_PERMUTE_BIT15_MASK = 0b1_0000;
    /// B8.10: `imm4` de `EXT` (bits[14:11]) — igual ao campo `opcode` inteiro quando
    /// {@link #ADVSIMD_EXTRACT_PERMUTE_BIT15_MASK} não está setado. B8.12: MESMA posição de bits
    /// reaproveitada por `imm4` de `DUP`/`INS_general`/`SMOV`/`UMOV` e por `si` (índice fonte) de
    /// `INS_element` — ver {@link #decodeAdvancedSimdCopy}.
    private static final int ADVSIMD_EXTRACT_IMM_MASK = 0b1111;
    /// B8.10: bit alto de `imm4` (bit14) — só existe na forma `Q` real (`imm3`, 3 bits, na forma
    /// `D`); setado sem `Q` é reservado (G8).
    private static final int ADVSIMD_EXTRACT_IMM_Q_BIT = 0b1000;
    /// B8.12: valores de `imm4` (bits[14:11], ver {@link #ADVSIMD_EXTRACT_IMM_MASK}) que
    /// selecionam cada instrução de "AdvSIMD copy" com `U=0` — conferidos bit a bit contra corpus
    /// real (`aarch64-none-elf-as`/`objdump`, devkitA64). Valores fora desta lista são reservados.
    private static final int ADVSIMD_COPY_DUP_ELEMENT = 0b0000;
    private static final int ADVSIMD_COPY_DUP_GENERAL = 0b0001;
    private static final int ADVSIMD_COPY_INS_GENERAL = 0b0011;
    private static final int ADVSIMD_COPY_SMOV = 0b0101;
    private static final int ADVSIMD_COPY_UMOV = 0b0111;

    /// B19.8 (`FEAT_LUT`): `LUTI2`/`LUTI4` vivem no MESMO espaço `bit21=0`/`u=0`/`bit10=0`/`bit15=0`
    /// de {@link #decodeAdvancedSimdExtractPermuteTable} que `EXT`(`u=1`)/permute(`bit11=1`)/`TBL`
    /// (`bit11=0`,`esz=00` fixo) — nunca colidem hoje: as 4 linhas caem em `esz!=0` dentro do ramo
    /// `TBL` (bits[23:22] ∈ {`10`,`11`,`01`}, nunca `00`) e continuam `unsupported` sem a feature,
    /// mesma prova de G3/G8 que a B11.4 já fez para `FEAT_RDM`. Discriminador: bits[23:21] (3 bits,
    /// bit21 já é `0` pelo chamador) separa as 3 famílias; dentro de `LUTI4` (`0b010`), bits[14:10]
    /// (`idx`+bits fixos, ver abaixo) separam `_1b` de `_2h`.
    private static final int ADVSIMD_LUTI_HIGH_BITS_SHIFT = 21;
    private static final int ADVSIMD_LUTI_HIGH_BITS_MASK = 0b111;
    private static final int ADVSIMD_LUTI2_1B_PATTERN = 0b100;
    private static final int ADVSIMD_LUTI2_1H_PATTERN = 0b110;
    private static final int ADVSIMD_LUTI4_PATTERN = 0b010;
    /// Campo bits[14:10] (5 bits): `idx` + os bits fixos que o encoding real intercala com ele —
    /// ler como bloco único evita separar `idx` dos bits fixos do meio (armadilha 1 da task:
    /// `LUTI4_1b`/`LUTI4_2h` só diferem aqui, nunca em bits[23:22]).
    private static final int ADVSIMD_LUTI_LOW_FIELD_SHIFT = 10;
    private static final int ADVSIMD_LUTI_LOW_FIELD_MASK = 0b1_1111;
    /// `LUTI2_1b`: `idx`(2 bits, alto) + `100` fixo (3 bits, baixo) — `idx = low >>> 3`.
    private static final int ADVSIMD_LUTI2_1B_FIXED_MASK = 0b111;
    private static final int ADVSIMD_LUTI2_1B_FIXED_VALUE = 0b100;
    private static final int ADVSIMD_LUTI2_1B_IDX_SHIFT = 3;
    /// `LUTI2_1h`: `idx`(3 bits, alto) + `00` fixo (2 bits, baixo) — `idx = low >>> 2`.
    private static final int ADVSIMD_LUTI2_1H_FIXED_MASK = 0b11;
    private static final int ADVSIMD_LUTI2_1H_FIXED_VALUE = 0b00;
    private static final int ADVSIMD_LUTI2_1H_IDX_SHIFT = 2;
    /// `LUTI4_1b`: `idx`(1 bit, alto) + `1000` fixo (4 bits, baixo) — `idx = low >>> 4`.
    private static final int ADVSIMD_LUTI4_1B_FIXED_MASK = 0b1111;
    private static final int ADVSIMD_LUTI4_1B_FIXED_VALUE = 0b1000;
    private static final int ADVSIMD_LUTI4_1B_IDX_SHIFT = 4;
    /// `LUTI4_2h`: `idx`(2 bits, alto) + `100` fixo (3 bits, baixo) — `idx = low >>> 3` (MESMA
    /// máscara/deslocamento fixo de `LUTI2_1b`, discriminados só pelo `ADVSIMD_LUTI4_PATTERN`
    /// externo, nunca pelo campo baixo sozinho).
    private static final int ADVSIMD_LUTI4_2H_FIXED_MASK = 0b111;
    private static final int ADVSIMD_LUTI4_2H_FIXED_VALUE = 0b100;
    private static final int ADVSIMD_LUTI4_2H_IDX_SHIFT = 3;
    private static final int ADVSIMD_LUTI_ESZ_BYTE = 0;
    private static final int ADVSIMD_LUTI_ESZ_HALFWORD = 1;

    // ── "Advanced SIMD shift by immediate" (B8.8): prefixo bits[28:24]="01111" (vetorial, `Q`=bit30
    // ── real) OU "11111"+bit30=1 (escalar) — UM BIT A MAIS que o prefixo de "three same"/
    // ── "two-register miscellaneous" acima ("01110"/"11110": bit24 é o único bit que muda,
    // ── `0`→three-same, `1`→shift-immediate; conferido bit a bit contra `a64.decode` real, mesma
    // ── técnica de {@link #ADVSIMD_INT_PREFIX_SHIFT}). Layout de campos totalmente diferente:
    // ── `U`(29)/`Q`(30, só vetorial)/`immh`(22:19)/`immb`(18:16)/`opcode`(15:11)/bit10=1 fixo/
    // ── `Rn`(9:5)/`Rd`(4:0) — SEM `size`/`Rm` (o "tamanho do elemento" é DERIVADO do bit mais alto
    // ── setado de `immh`, nunca um campo de 2 bits solto; a forma D-only escalar EXIGE `immh`
    // ── com bit3 setado, senão é UNALLOCATED — diferente do truque `esz=3` fixo usado acima).
    private static final int ADVSIMD_SHIFT_PREFIX_VECTOR_PATTERN = 0b0_1111;
    private static final int ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN = 0b1_1111;
    private static final int ADVSIMD_SHIFT_IMMH_SHIFT = 19;
    private static final int ADVSIMD_SHIFT_IMMH_MASK = 0b1111;
    private static final int ADVSIMD_SHIFT_IMMB_SHIFT = 16;
    private static final int ADVSIMD_SHIFT_IMMB_MASK = 0b111;

    // ── B19.6 bloco G: `Vimm`/`FMOVI_v_h` (`%abcdefgh` real do `a64.decode`: bits[18:16] = "abc" ────
    // ── (topo de `imm8`), bits[9:5] = "defgh" (base) — MESMA posição que `immb`/`Rn` ocupariam se ────
    // ── esta fosse mesmo uma instrução "shift by immediate" de verdade, achado que também vale do ───
    // ── lado A32 para `Vimm_1r`, B13.7). ─────────────────────────────────────────────────────────
    private static final int ADVSIMD_MODIFIED_IMM_TOP_SHIFT = 16;
    private static final int ADVSIMD_MODIFIED_IMM_BOTTOM_SHIFT = 5;
    /// bits[15:10] cru — `FMOVI_v_h` exige os 6 bits fixos em `1`; `Vimm` usa os 4 bits altos como
    /// `cmode` e exige os 2 baixos fixos em `01` (checado separadamente, ver
    /// {@link #ADVSIMD_MODIFIED_IMM_FIXED2_SHIFT}).
    private static final int ADVSIMD_MODIFIED_IMM_SUFFIX_SHIFT = 10;
    private static final int ADVSIMD_MODIFIED_IMM_FMOVI_H_SUFFIX = 0b11_1111;
    private static final int ADVSIMD_MODIFIED_IMM_FIXED2_SHIFT = 10;
    private static final int ADVSIMD_MODIFIED_IMM_FIXED2_PATTERN = 0b01;
    private static final int ADVSIMD_MODIFIED_IMM_CMODE_SHIFT = 12;
    private static final int ADVSIMD_MODIFIED_IMM_OP_SHIFT = 29;

    // ── "Advanced SIMD vector/scalar × indexed element" (B8.19): MESMO prefixo bits[28:24] de ────
    // ── "shift by immediate" acima ("01111"/"11111") — discriminados só por bit10 (`1`=shift-
    // ── immediate, `0`=indexed-element; conferido contra `a64.decode` real do QEMU, seção
    // ── "AdvSIMD {scalar,vector} x indexed element"). Campo `size`(23:22, MESMO
    // ── {@link #ADVSIMD_INT_SIZE_SHIFT}/{@link #ADVSIMD_INT_SIZE_MASK} de "three same") escolhe o
    // ── tamanho do elemento: `00`=meia-precisão (`FEAT_FP16`, só ponto flutuante — B19.5.6), `01`=
    // ── halfword (só inteiro), `10`=word (inteiro E ponto flutuante, discriminados pelo opcode
    // ── nibble+`U`), `11`=doubleword (só ponto flutuante, `bit21` fixo em `0`). `opcode`(15:12, 4
    // ── bits) + `U`(29, MESMO {@link #ADVSIMD_INT_U_SHIFT}) escolhem a operação — tabela própria,
    // ── ver {@link #decodeAdvancedSimdIndexedFpOpcode}/{@link #decodeAdvancedSimdIndexedIntOpcode}.
    // ── Índice do elemento de `Rm` é MONTADO a partir de bits espalhados (`H`=bit11 sempre;
    // ── `L`=bit21 para word/doubleword; `L:M`=bits[21:20] para halfword E meia-precisão, `M`
    // ── também estreita `Rm` a `V0`-`V15`) — ver {@link #decodeAdvancedSimdIndexedElementIndex}.
    private static final int ADVSIMD_INDEXED_OPCODE_SHIFT = 12;
    private static final int ADVSIMD_INDEXED_OPCODE_MASK = 0b1111;
    private static final int ADVSIMD_INDEXED_SIZE_HALF_PRECISION = 0b00;
    private static final int ADVSIMD_INDEXED_SIZE_HALFWORD = 0b01;
    private static final int ADVSIMD_INDEXED_SIZE_WORD = 0b10;
    private static final int ADVSIMD_INDEXED_SIZE_DOUBLEWORD = 0b11;
    private static final int ADVSIMD_INDEXED_RM_H_MASK = 0b1111;
    private static final int ADVSIMD_INDEXED_H_SHIFT = 11;
    private static final int ADVSIMD_INDEXED_L_SHIFT = 21;
    private static final int ADVSIMD_INDEXED_LM_SHIFT = 20;
    private static final int ADVSIMD_INDEXED_LM_MASK = 0b11;
    /// B19.20 (`FEAT_FCMA`): unidade de rotação em graus — `rot` (2 bits, `FCMLA`) ou 1 bit
    /// (`FCADD`) sempre significa "× 90°" (`0`/`90`/`180`/`270`).
    private static final int ADVSIMD_FCMA_ROTATION_UNIT_DEGREES = 90;
    /// B19.20: `FCMLA_v` ("three same") tem `opcode`(bits[15:11], 5 bits) `0b110rr` — `rr` é a
    /// rotação (`0`-`3`, × {@link #ADVSIMD_FCMA_ROTATION_UNIT_DEGREES}). Máscara/padrão dos 3 bits
    /// altos, confirmados bit a bit contra `aarch64-linux-gnu-as -march=armv8.3-a` (WSL): `fcmla
    /// v0.4h,v1.4h,v2.4h,#0` → opcode=`0b11000`; `#90`→`0b11001`; `#180`→`0b11010`; `#270`→
    /// `0b11011`.
    private static final int ADVSIMD_FCMA_OPCODE_PREFIX_MASK = 0b1_1100;
    private static final int ADVSIMD_FCMA_OPCODE_PREFIX_PATTERN = 0b1_1000;
    private static final int ADVSIMD_FCMA_ROTATION_MASK = 0b11;
    /// B19.20: `FCADD_90`/`FCADD_270` ("three same") — opcode FIXO (sem campo `rot`, só as duas
    /// rotações existem: `0°`/`180°` já são `FADD`/`FSUB` comuns). Confirmado contra corpus real:
    /// `fcadd v0.4h,v1.4h,v2.4h,#90` → opcode=`0b11100`; `#270` → `0b11110`.
    private static final int ADVSIMD_FCADD_OPCODE_90 = 0b1_1100;
    private static final int ADVSIMD_FCADD_OPCODE_270 = 0b1_1110;
    /// B19.20: `FCMLA_vi` ("vector × indexed element") tem `opcode`(bits[15:12], 4 bits) `0b0rr1` —
    /// MESMA rotação de 2 bits (`rr`) que a forma "three same", só reempacotada em 4 bits em vez de
    /// 5 (o bit baixo fixo `1` ocupa o lugar do `bit10=1`/`bit11` fixos da forma vetorial).
    /// Confirmado contra corpus real: `fcmla v0.4h,v1.4h,v2.h[0],#0` → opcode=`0b0001`; `#90` →
    /// `0b0011`; `#180` → `0b0101`; `#270` → `0b0111` — nenhum desses 4 valores colide com
    /// `MLA`/`MLS`/`MUL`/`MULX`/RDM/etc. de {@link #decodeAdvancedSimdIndexedFp}/{@link
    /// #decodeAdvancedSimdIndexedInt} porque todos exigem `U=0`, e `FCMLA_vi` tem `U=1` sempre
    /// (checado exaustivamente, ver `## Resultado` da task).
    private static final int ADVSIMD_FCMA_INDEXED_OPCODE_MASK = 0b1001;
    private static final int ADVSIMD_FCMA_INDEXED_OPCODE_PATTERN = 0b0001;
    private static final int ADVSIMD_FCMA_INDEXED_ROTATION_SHIFT = 1;
    /// B19.7 (`FEAT_BF16`): `opcode`(bits[15:12]) de `BFDOT_vi`/`BFMLAL_vi` — MESMO valor nos dois
    /// `size` diferentes (`01`=`BFDOT_vi`, `11`=`BFMLAL_vi`), ver
    /// {@link #decodeAdvancedSimdIndexedElement}.
    private static final int ADVSIMD_BFLOAT16_INDEXED_OPCODE = 0b1111;

    // ── B19.7 (`FEAT_BF16`): `BFDOT_v`/`BFMLAL_v`/`BFMMLA` vetorial/matricial (não-indexado) — ver
    // ── {@link #decodeAdvancedSimdExtractPermuteTable}. Vivem no MESMO espaço `bit21=0` de EXT/
    // ── permute/TBL/copy (`U`=1, `bit10`=1, `opcode`(bits[15:11], 5 bits) distinto).
    private static final int ADVSIMD_BF16_OPCODE_DOT_OR_MLAL = 0b1_1111;
    private static final int ADVSIMD_BFMMLA_OPCODE = 0b1_1101;
    private static final int ADVSIMD_BF16_SIZE_HALFWORD = 0b01;
    private static final int ADVSIMD_BF16_SIZE_DOUBLEWORD = 0b11;

    // ── B19.12 (`FEAT_I8MM`): `USDOT_v`/`SMMLA`/`UMMLA`/`USMMLA` vetorial/matricial (não-indexado) —
    // ── ver {@link #decodeAdvancedSimdExtractPermuteTable}. MESMO espaço `bit21=0` de EXT/permute/
    // ── TBL/copy/`BFDOT_v`/`BFMLAL_v`/`BFMMLA` acima, mas com `U=0` (ao contrário do BF16, que usa
    // ── `U=1`) — sem colisão, conferido bit a bit contra corpus real (`aarch64-linux-gnu-as
    // ── -march=armv8.6-a+i8mm`). `size`(23:22) é sempre `10` ("S", o resultado é sempre uma lane
    // ── `int32`) nas três; `Q` é livre em `USDOT_v` mas FIXO em `1` em `SMMLA`/`UMMLA`/`USMMLA`
    // ── (não há forma de 64 bits, mesma disciplina de `BFMMLA`).
    private static final int ADVSIMD_I8MM_OPCODE_USDOT = 0b1_0011;
    private static final int ADVSIMD_I8MM_OPCODE_MMLA = 0b1_0100;
    private static final int ADVSIMD_I8MM_OPCODE_USMMLA = 0b1_0101;
    private static final int ADVSIMD_I8MM_SIZE_WORD = 0b10;

    /// B19.23 (`FEAT_DotProd` residual): `opcode`(bits[15:11], 5 bits) de `SDOT_v`/`UDOT_v` — vizinho
    /// direto de {@link #ADVSIMD_I8MM_OPCODE_USDOT} (`0b1_0011`), MESMO espaço `bit21=0`/`bit10=1`
    /// de {@link #decodeAdvancedSimdExtractPermuteTable}. `U` distingue `SDOT`(`u=0`, sinal nos dois
    /// operandos) de `UDOT`(`u=1`, sem sinal nos dois) — `size`(23:22) sempre
    /// {@link #ADVSIMD_I8MM_SIZE_WORD} (`10`), mesma disciplina de `USDOT_v`.
    private static final int ADVSIMD_DOTPRODUCT_OPCODE = 0b1_0010;

    /// B19.12 (`FEAT_I8MM`): `opcode`(bits[15:12]) de `USDOT_vi`/`SUDOT_vi` — MESMO valor cru de
    /// {@link #ADVSIMD_BFLOAT16_INDEXED_OPCODE} (nomeado à parte para não confundir as duas
    /// features, que nunca colidem: BF16 usa `size` `01`/`11`, I8MM usa `00`/`10`). `U`(bit29) é
    /// sempre `0` nas duas formas — **não** é o discriminador (ver {@link #ADVSIMD_I8MM_INDEXED_SIZE_SUDOT}).
    private static final int ADVSIMD_I8MM_INDEXED_OPCODE = 0b1111;
    /// `size`(23:22) de `SUDOT_vi` (`Rn` COM sinal, `Rm` SEM sinal) — `USDOT_vi` usa
    /// {@link #ADVSIMD_I8MM_SIZE_WORD} (`10`). Discriminador real, CONFERIDO bit a bit contra corpus
    /// real: os dois encodings têm `U=0` idêntico, só o `size` muda.
    private static final int ADVSIMD_I8MM_INDEXED_SIZE_SUDOT = 0b00;

    /// B19.23 (`FEAT_DotProd` residual): `opcode`(bits[15:12]) de `SDOT_vi`/`UDOT_vi` — vizinho
    /// direto de {@link #ADVSIMD_I8MM_INDEXED_OPCODE} (`0b1111`), mas com `Rm` de 5 bits LIVRES
    /// (`@qrrx_s`, ao contrário do `Rm` restrito a `V0`-`V15` de `USDOT_vi`/`SUDOT_vi`) — índice
    /// continua `H:L` (2 bits), MESMA fórmula do ramo `WORD` genérico. `U` distingue `SDOT_vi`
    /// (`u=0`) de `UDOT_vi` (`u=1`).
    private static final int ADVSIMD_DOTPRODUCT_INDEXED_OPCODE = 0b1110;

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

    // ── B8.5: `FCSEL`/`FCCMP` — MESMO prefixo(30:24)+bit21=1 das 4 sub-classes acima; distinguidas
    // ── por bits[11:10], que nas 4 sub-classes acima é sempre "00" (compare/1-source, dentro de um
    // ── campo fixo maior) ou "10" (2-source) — "11"/"01" nunca colidem (CONFERIDO: bit10=1 nas duas
    // ── formas novas, bit10=0 em TODAS as 4 anteriores). `cond`(15:12) e `Rm`(20:16) compartilhados;
    // ── `FCCMP` tem `E`(bit4)+`nzcv`(3:0) onde `FCSEL` tem `Rn`(9:5, mesma posição)+`Rd`(4:0).
    private static final int FP_SELECT_COMPARE_FIXED_SHIFT = 10;
    private static final int FP_SELECT_COMPARE_FIXED_MASK = 0b11;
    private static final int FP_CSEL_FIXED_PATTERN = 0b11;
    private static final int FP_CCMP_FIXED_PATTERN = 0b01;
    private static final int FP_COND_SHIFT = 12;
    private static final int FP_CCMP_E_BIT_SHIFT = 4;
    private static final int FP_CCMP_NZCV_MASK = 0b1111;

    // ── B8.5: "Conversion between floating-point and fixed-point (general register)" — MESMO
    // ── prefixo(30:24), mas bit21=0 (ao contrário das sub-classes acima, todas bit21=1): `sf`(31)
    // ── largura do registrador geral, opcode(21:16) só 4 valores válidos (`SCVTF`/`UCVTF`/`FCVTZS`/
    // ── `FCVTZU` — únicos mnemônicos deste grupo, "Z" já é o nome: sempre trunca p/ zero no sentido
    // ── float->inteiro), `shift`(15:10, 6 bits) = `N - raw` (`N`=32 quando `!sf`, exige bit15=1;
    // ── `N`=64 quando `sf`, usa os 6 bits inteiros) — CONFERIDO contra `a64.decode` real do QEMU
    // ── (`@fcvt32`/`@fcvt64`, `%fcvt_shift32`/`%fcvt_shift64` = `rsub_32`/`rsub_64`).
    private static final int FP_FIXED_CONVERT_OPCODE_SHIFT = 16;
    private static final int FP_FIXED_CONVERT_OPCODE_MASK = 0b11_1111;
    private static final int FP_FIXED_CONVERT_OPCODE_SCVTF = 0b00_0010;
    private static final int FP_FIXED_CONVERT_OPCODE_UCVTF = 0b00_0011;
    private static final int FP_FIXED_CONVERT_OPCODE_FCVTZS = 0b01_1000;
    private static final int FP_FIXED_CONVERT_OPCODE_FCVTZU = 0b01_1001;
    private static final int FP_FIXED_CONVERT_SHIFT_FIELD_SHIFT = 10;
    private static final int FP_FIXED_CONVERT_SHIFT_FIELD_MASK = 0b11_1111;
    private static final int FP_FIXED_CONVERT_NARROW_MARKER_BIT = 1 << 15;
    private static final int FP_FIXED_CONVERT_NARROW_RAW_MASK = 0b1_1111;

    // ── B8.5: "Conversion between floating-point and integer (general register)" — MESMO
    // ── prefixo(30:24)+bit21=1 de `FCSEL`/`FCCMP`/2-source/1-source/imediato/compare, mas
    // ── bits[15:10] fixo="000000" (nenhuma das 6 sub-classes anteriores tem esse valor ali —
    // ── `FCSEL`/`FCCMP` têm bit10=1 sempre; compare/1-source exigem bits[14:10]≠0; 2-source exige
    // ── bits[11:10]="10"≠"00"). Opcode(21:16) 6 bits: 12 valores válidos (`SCVTF`/`UCVTF`/8
    // ── arredondamentos `FCVTxS`/`FCVTxU` + `FCVTAS`/`FCVTAU`) + 1 valor próprio (`FJCVTZS`,
    // ── `FEAT_JSCVT`, B19.29); os demais valores deste mesmo campo (`_g_simd`/`_simd`,
    // ── `FEAT_FPRCVT`) são extensões POSTERIORES, CONFERIDAS contra `translate-a64.c` real
    // ── (`TRANS_FEAT(..., aa64_fprcvt, ...)`), ficam de fora por não bater nenhum `case` (ver
    // ── `docs/isa-nao-aplicavel.tsv`).
    private static final int FP_INT_CONVERT_SUFFIX_SHIFT = 10;
    private static final int FP_INT_CONVERT_SUFFIX_MASK = 0b11_1111;
    private static final int FP_INT_CONVERT_SUFFIX_PATTERN = 0;
    private static final int FP_INT_CONVERT_OPCODE_SCVTF = 0b10_0010;
    private static final int FP_INT_CONVERT_OPCODE_UCVTF = 0b10_0011;
    private static final int FP_INT_CONVERT_OPCODE_FCVTNS = 0b10_0000;
    private static final int FP_INT_CONVERT_OPCODE_FCVTNU = 0b10_0001;
    private static final int FP_INT_CONVERT_OPCODE_FCVTPS = 0b10_1000;
    private static final int FP_INT_CONVERT_OPCODE_FCVTPU = 0b10_1001;
    private static final int FP_INT_CONVERT_OPCODE_FCVTMS = 0b11_0000;
    private static final int FP_INT_CONVERT_OPCODE_FCVTMU = 0b11_0001;
    private static final int FP_INT_CONVERT_OPCODE_FCVTZS = 0b11_1000;
    private static final int FP_INT_CONVERT_OPCODE_FCVTZU = 0b11_1001;
    private static final int FP_INT_CONVERT_OPCODE_FCVTAS = 0b10_0100;
    private static final int FP_INT_CONVERT_OPCODE_FCVTAU = 0b10_0101;
    /// B19.29 (`FEAT_JSCVT`): `FJCVTZS` — MESMO campo `opcode`(21:16) do grupo acima, valor não
    /// usado por nenhum dos 12 arredondamentos comuns; só definida para `sf=0`+`type=DOUBLE`
    /// (confirmado byte a byte contra `aarch64-linux-gnu-as -march=armv8.3-a`).
    private static final int FP_INT_CONVERT_OPCODE_FJCVTZS = 0b11_1110;

    // ── B8.5: `FMOV` registrador-geral<->FP (cópia crua de bits) — mesmo `@rr`/bits[15:10]="000000"
    // ── de "Conversion (general register)" acima (MESMO valor de sufixo!), mas opcode(21:16) só
    // ── "100110"/"100111" — CONFERIDO: não colide com nenhum dos 12 opcodes de conversão inteira
    // ── acima (nenhum tem valor 100110/100111). `type`(23:22) é `sf?01:00` na forma `W`↔`S`/`X`↔`D`
    // ── (basta ler `sf` e ignorar `type`); `type`={@link #FP_TYPE_HALF_PRECISION} com os MESMOS
    // ── opcodes é `FMOV_hx`/`FMOV_xh` (B19.26, `FEAT_FP16`) — checado ANTES, ver
    // ── `decodeFpIntegerConvertOrGeneralRegisterMove`. `type`={@link #FP_TYPE_HIGH_HALF_MOVE} usa
    // ── opcodes DIFERENTES (`FP_GP_MOVE_OPCODE_HIGH_TO_*`, abaixo).
    private static final int FP_GP_MOVE_OPCODE_TO_FLOAT = 0b10_0111;
    private static final int FP_GP_MOVE_OPCODE_TO_GP = 0b10_0110;
    /// `FMOV Vd.D[1],Xn`/`FMOV Xd,Vn.D[1]` (B19.6 bloco F) — MESMO campo `opcode`, valores
    /// `0b101110`/`0b101111`; `type`(23:22) fixo em `0b10` para esta forma (ver
    /// {@link #FP_TYPE_HIGH_HALF_MOVE}), medido bit a bit contra corpus real
    /// (`aarch64-none-elf-as`, `.arch armv8.5-a`).
    private static final int FP_GP_MOVE_OPCODE_HIGH_TO_FLOAT = 0b10_1111;
    private static final int FP_GP_MOVE_OPCODE_HIGH_TO_GP = 0b10_1110;

    // ── B8.5: "Floating-point data-processing (1 source)" — `FRINTx`, opcode(20:15) 6 bits,
    // ── MESMO grupo/bits[14:10]="10000" de `FMOV`/`FABS`/`FNEG`/`FSQRT`/`FCVT` (F32<->F64) já
    // ── decodificados — só o valor do opcode muda. `BFCVT_s`(`FEAT_BF16`) é extensão POSTERIOR,
    // ── CONFERIDA contra `translate-a64.c` (`TRANS_FEAT(..., aa64_bf16, ...)`). `FRINT32*`/
    // ── `FRINT64*` (`FEAT_FRINTTS`) eram extensão posterior também, mas ganharam decoder na B19.18.
    private static final int FP_ROUND_OPCODE_FRINTN = 0b00_1000;
    private static final int FP_ROUND_OPCODE_FRINTP = 0b00_1001;
    private static final int FP_ROUND_OPCODE_FRINTM = 0b00_1010;
    private static final int FP_ROUND_OPCODE_FRINTZ = 0b00_1011;
    private static final int FP_ROUND_OPCODE_FRINTA = 0b00_1100;
    private static final int FP_ROUND_OPCODE_FRINTX = 0b00_1110;
    private static final int FP_ROUND_OPCODE_FRINTI = 0b00_1111;
    /// B19.18 (`FEAT_FRINTTS`): opcodes(20:15) NOVOS, mesmo grupo/campo dos `FRINTx` acima —
    /// `FRINT32Z_s`/`FRINT32X_s`/`FRINT64Z_s`/`FRINT64X_s`. Conferidos bit a bit contra
    /// `aarch64-linux-gnu-as -march=armv8.5-a` (WSL).
    private static final int FP_ROUND_RANGE_OPCODE_FRINT32Z = 0b01_0000;
    private static final int FP_ROUND_RANGE_OPCODE_FRINT32X = 0b01_0001;
    private static final int FP_ROUND_RANGE_OPCODE_FRINT64Z = 0b01_0010;
    private static final int FP_ROUND_RANGE_OPCODE_FRINT64X = 0b01_0011;

    // ── Add/subtract (shifted/extended register), subgrupo de Data Processing — Register: ─────
    // ── bits[28:24] fixo=01011 nas duas formas; bit21 distingue shifted(0)/extended(1, com ────
    // ── bits[23:22]=00 fixo também) — Fatos de referência #4/#5 da task B6.3.1. ────────────────
    private static final int ADDSUB_REGISTER_GROUP_SHIFT = 24;
    private static final int ADDSUB_REGISTER_GROUP_5BIT_MASK = 0b1_1111;
    private static final int ADDSUB_REGISTER_GROUP_PATTERN = 0b01011;
    private static final int ADDSUB_REGISTER_EXTENDED_BIT_SHIFT = 21;
    private static final int ADDSUB_REGISTER_RM_SHIFT = 16;

    // ── Logical (shifted register), B6.9: mesmo grupo de 5 bits em bits[28:24] que Add/subtract
    // ── (shifted/extended register) acima, mas com padrão `01010` em vez de `01011` — só esse
    // ── bit (bit24) distingue as duas famílias dentro de "Data Processing — Register". Reaproveita
    // ── ADDSUB_SHIFTED_TYPE_SHIFT (campo `st`), ADDSUB_REGISTER_EXTENDED_BIT_SHIFT (aqui é o bit
    // ── de inversão `n`, mesma posição bit21), ADDSUB_REGISTER_RM_SHIFT e ADDSUB_SHIFTED_AMOUNT_*
    // ── (campo `sa`) já declarados acima — ver B6.9 Fatos de referência #1. ──────────────────────
    private static final int LOGICAL_SHIFTED_REGISTER_GROUP_PATTERN = 0b01010;
    private static final int LOGICAL_SHIFTED_REGISTER_INVERT_BIT_SHIFT = ADDSUB_REGISTER_EXTENDED_BIT_SHIFT;

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

    // ── B19.6 bloco C: `PACGA` (`FEAT_PAuth`) — MESMO subgrupo "Data-processing (2 source)" de ────
    // ── SDIV/UDIV/LSLV/.../CRC32* (opc2=00), opcode(15:10)=`0b001100`, fixo (`Rm`/`Rn`/`Rd` normais, ─
    // ── `@rrr`). Medido bit a bit contra corpus real (`aarch64-none-elf-as`, `.arch armv8.3-a`). ────
    // ── B19.17: `CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}` (`FEAT_CRC32`) — MESMO subgrupo "Data-processing
    // ── (2 source)" (opc2=00). `top4` (bits[15:12], os 4 bits altos do campo opcode de 6 bits que
    // ── PACGA já lê como `pacgaOpcode6`) distingue `CRC32`(`0b0100`) de `CRC32C`(`0b0101`); `size`
    // ── (bits[11:10]) distingue B/H/W/`X`(sf=1). Medido bit a bit contra corpus real
    // ── (`aarch64-linux-gnu-as -march=armv8.1-a`, WSL). ──────────────────────────────────────────
    private static final int CRC32_TOP4_SHIFT = 2;
    private static final int CRC32_TOP4_MASK = 0b1111;
    private static final int CRC32_TOP4_PATTERN = 0b0100;
    private static final int CRC32C_TOP4_PATTERN = 0b0101;
    private static final int CRC32_SIZE_SHIFT = 10;
    private static final int CRC32_SIZE_MASK = 0b11;
    private static final int CRC32_SIZE_BYTE = 0b00;
    private static final int CRC32_SIZE_HALFWORD = 0b01;
    private static final int CRC32_SIZE_WORD = 0b10;
    private static final int CRC32_SIZE_DOUBLEWORD = 0b11;

    private static final int PACGA_OPCODE_SHIFT = 10;
    private static final int PACGA_OPCODE_6BIT_MASK = 0b11_1111;
    private static final int PACGA_OPCODE_PATTERN = 0b00_1100;

    // ── LSLV/LSRV/ASRV/RORV, mesmo subgrupo "Data-processing (2 source)" de SDIV/UDIV (B6.11): ───
    // ── opcode(15:11)=00100(LSLV/LSRV) ou 00101(ASRV/RORV), bit10 distingue dentro do par ─────────
    // ── (0=esquerda/aritmético,1=direita/rotação) — CONFERIDO contra `a64.decode` real do QEMU ────
    // ── (Fatos de referência da task B6.11): os bits[11:10] tomados como par batem exatamente com ─
    // ── a ordem de Ir64LogicalShiftType (LSL=00,LSR=01,ASR=10,ROR=11), reaproveitado em vez de um ─
    // ── enum próprio (mesma decisão de reuso já validada por B6.9 para a MESMA tabela de 4 tipos). ─
    private static final int SHIFT_VARIABLE_OPCODE_SHIFT = 12;
    private static final int SHIFT_VARIABLE_OPCODE_4BIT_MASK = 0b1111;
    private static final int SHIFT_VARIABLE_OPCODE_PATTERN = 0b0010;
    private static final int SHIFT_VARIABLE_TYPE_SHIFT = 10;
    private static final int SHIFT_VARIABLE_TYPE_2BIT_MASK = 0b11;

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

    // ── Data-processing (1 source)/(2 source), B8.2: MESMO grupo de 8 bits fixos em bits[28:21] ──
    // ── ("11010110") — só bits[30:29] (`opc2`) distingue os dois subgrupos (`00`=2-source, já ────
    // ── tratado acima por decodeDivide/decodeShiftVariable; `10`=1-source, RBIT/REV16/CLZ/CLS/ ───
    // ── CNT). SEM checar `opc2`, o decoder pré-B8.2 confundia REV32/REV64/CLZ/etc com SDIV/UDIV ──
    // ── (bit[15:11] de REV32/REV64 casa por acaso com o opcode de SDIV/UDIV) — bug real corrigido ─
    // ── por esta task, ver "Bugs reais achados e corrigidos" na task. ───────────────────────────
    private static final int DP_SOURCE_OPC2_SHIFT = 29;
    private static final int DP_SOURCE_OPC2_MASK = 0b11;
    private static final int DP_SOURCE_OPC2_TWO_SOURCE = 0b00;
    private static final int DP_SOURCE_OPC2_ONE_SOURCE = 0b10;
    // ── opcode (bits[15:10], 6 bits) do subgrupo 1-source — os 7 valores em escopo desta task. ────
    // ── CTZ(6)/ABS(8)/PACIA.../XPAC... (opcode ≥6, bit[21]=1 do subgrupo PAC/AUT) ficam fora ──────
    // ── (extensões posteriores, isa-nao-aplicavel.tsv).
    private static final int ONE_SOURCE_OPCODE_SHIFT = 10;
    private static final int ONE_SOURCE_OPCODE_MASK = 0b11_1111;
    private static final int ONE_SOURCE_OPCODE_RBIT = 0b00_0000;
    private static final int ONE_SOURCE_OPCODE_REV16 = 0b00_0001;
    /// `REV`(`sf=0`)/`REV32`(`sf=1`) — MESMO opcode, `sf` livre (ver {@link Ir64OneSourceOp#REV32}).
    private static final int ONE_SOURCE_OPCODE_REV32 = 0b00_0010;
    /// `REV64` — `sf=1` FIXO no encoding (só existe a forma `X`), checado à parte.
    private static final int ONE_SOURCE_OPCODE_REV64 = 0b00_0011;
    private static final int ONE_SOURCE_OPCODE_CLZ = 0b00_0100;
    private static final int ONE_SOURCE_OPCODE_CLS = 0b00_0101;
    private static final int ONE_SOURCE_OPCODE_CNT = 0b00_0111;
    /// `ABS` de registrador geral (B19.6 bloco D, `FEAT_CSSC`) — medido bit a bit contra corpus
    /// real (`aarch64-none-elf-as`, `.arch armv8.9-a`).
    private static final int ONE_SOURCE_OPCODE_ABS = 0b00_1000;
    /// `CTZ` (B19.21, `FEAT_CSSC`) — MESMO subgrupo/gate de {@link #ONE_SOURCE_OPCODE_ABS}, medido
    /// bit a bit contra corpus real (`aarch64-linux-gnu-as`, `.arch armv8.9-a`).
    private static final int ONE_SOURCE_OPCODE_CTZ = 0b00_0110;
    /// `opcode2`(bits[20:16] — mesma posição de `Rm`/{@link #ADDSUB_REGISTER_RM_SHIFT}) do subgrupo
    /// "Data-processing (1 source)": `00000`=RBIT/REV*/CLZ/CLS/CNT/ABS/CTZ, `00001`=PAC/AUT/XPAC de
    /// propósito geral (B19.15, `FEAT_PAuth`). Demais valores são reservados.
    private static final int ONE_SOURCE_OPCODE2_STANDARD = 0b0_0000;
    private static final int ONE_SOURCE_OPCODE2_PAUTH = 0b0_0001;
    /// `opcode`(bits[15:10]) bits[5:4]=`00`: as 8 formas "assinar"/"autenticar" de propósito geral
    /// (`PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/`AUTDA`/`AUTDB`, bits[2:0] escolhem o
    /// mnemônico — B19.15).
    private static final int PAUTH_IN_PLACE_TOP2_GENERAL = 0b00;
    /// `opcode` bits[5:4]=`01`: `XPACI`(`opcode=010000`)/`XPACD`(`opcode=010001`) — sem `Rn` real
    /// (fixo em `11111` no encoding).
    private static final int PAUTH_IN_PLACE_TOP2_XPAC = 0b01;
    private static final int PAUTH_IN_PLACE_XPAC_RN_FIXED = 0b1_1111;

    // ── Data-processing (3 source), B8.2: SMADDL/SMSUBL/UMADDL/UMSUBL/SMULH/UMULH — mesmo campo ──
    // ── de 8 bits fixos em bits[28:21] de MADD/MSUB (MADD_MSUB_FIXED_PATTERN), mas com valores ────
    // ── DIFERENTES (`sf` faz parte do prefixo fixo aqui, sempre `1` — só existe a forma `X`). ─────
    private static final int MULDIV_LONG_FIXED_SIGNED = 0b1101_1001;
    private static final int MULDIV_LONG_FIXED_UNSIGNED = 0b1101_1101;
    private static final int MULH_FIXED_SIGNED = 0b1101_1010;
    private static final int MULH_FIXED_UNSIGNED = 0b1101_1110;
    /// `Ra` fixo em `XZR`(`11111`) no encoding de `SMULH`/`UMULH` — não é um acumulador real (ver
    /// Javadoc de {@link Ir64Op.MultiplyHigh}), só validado para recusar combinações reservadas.
    private static final int MULH_RA_FIXED = 0b1_1111;

    // ── Add/subtract (carry) + Rotate/Evaluate into flags, B8.2: MESMO campo de 8 bits fixos em ───
    // ── bits[28:21]="11010000" para os 3 subgrupos (ADC/SBC, RMIF, SETF8/SETF16) — distinguidos ───
    // ── pelos bits[15:10] (opcode2) e, para RMIF/SETF, por `sf`/`opc` fixos adicionais (Fatos de ───
    // ── referência da task, conferidos contra `a64.decode` real do QEMU). ───────────────────────
    private static final int ADD_SUB_CARRY_FIXED_PATTERN = 0b1101_0000;
    private static final int ADD_SUB_CARRY_OPCODE2_SHIFT = 10;
    private static final int ADD_SUB_CARRY_OPCODE2_MASK = 0b11_1111;
    private static final int ADD_SUB_CARRY_OPCODE2_ADC_SBC = 0b00_0000;
    // ── RMIF: sf=1 fixo, opc(30:29)="01" fixo, imm6(20:15), "00001" fixo(14:10), rn(9:5), ─────────
    // ── "0" fixo(bit4), mask(3:0). ───────────────────────────────────────────────────────────────
    private static final int RMIF_FIXED_TAIL_SHIFT = 10;
    private static final int RMIF_FIXED_TAIL_MASK = 0b1_1111;
    private static final int RMIF_FIXED_TAIL_PATTERN = 0b0_0001;
    private static final int RMIF_BIT4_MASK = 1 << 4;
    private static final int RMIF_IMM6_SHIFT = 15;
    private static final int RMIF_IMM6_MASK = 0b11_1111;
    private static final int RMIF_MASK_FIELD_MASK = 0xF;
    // ── SETF8/SETF16: sf=0 fixo, opc(30:29)="01" fixo, bits[20:16]="00000" fixo (MESMA posição de ─
    // ── ADDSUB_REGISTER_RM_SHIFT/REGISTER_FIELD_MASK, reaproveitados), opcode2(15:10) distingue ───
    // ── SETF8(0b000010)/SETF16(0b010010), rn(9:5), "01101" fixo(4:0). ───────────────────────────
    private static final int SETF_OPCODE2_SETF8 = 0b00_0010;
    private static final int SETF_OPCODE2_SETF16 = 0b01_0010;
    private static final int SETF_LOW5_MASK = 0b1_1111;
    private static final int SETF_LOW5_PATTERN = 0b0_1101;
    /// `SETF8` avalia o BYTE baixo (`ARM DDI 0487`, "Evaluate into flags") — ver
    /// {@link Ir64Op.EvaluateIntoFlags#sizeBits}.
    private static final int EVALUATE_FLAGS_SIZE_8 = 8;
    /// `SETF16` avalia o HALFWORD baixo.
    private static final int EVALUATE_FLAGS_SIZE_16 = 16;

    // ── Extract (EXTR), subgrupo `11` de Data Processing Immediate (B8.2) — MESMA posição de bit ──
    // ── `N` (bit22) que Bitfield (BITFIELD_N_SHIFT, reaproveitado: deve ser igual a `sf`), bit21 ───
    // ── fixo em `0` (`op0` do subgrupo, distinto do `1` reservado que indicaria outra família). ───
    private static final int EXTRACT_OP21_SHIFT = 21;
    /// Forma de 32 bits (`sf=0`) não tem os 6 bits completos de `imm` — bit15 é fixo em `0`
    /// (`imm5`, não `imm6`); combinação `sf=0`+bit15=1 é reservada.
    private static final int EXTRACT_NARROW_RESERVED_BIT = 1 << 15;
    private static final int EXTRACT_SHIFT_FIELD_SHIFT = 10;
    private static final int EXTRACT_IMM6_MASK = 0b11_1111;
    private static final int EXTRACT_IMM5_MASK = 0b1_1111;

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
        int subclass = (word >>> LOAD_STORE_SUBCLASS_SHIFT) & LOAD_STORE_SUBCLASS_MASK;
        if (subclass == SUBCLASS_LITERAL
                && ((word >>> LITERAL_SUBCLASS_RESERVED_BIT_SHIFT) & 1) != 0) {
            // B19.16 (achado real): este bucket bit24=1 (GCS/MOPS/atomic128) tem que ser
            // interceptado ANTES do ramo `vectorForm` abaixo, não depois — `CPYP`/`CPYM`/`CPYE`/
            // `SETGP`/`SETGM`/`SETGE` (B19.14) têm bit26=1 "por acidente" do encoding (aqui bit26
            // é parte do opcode MOPS/tag, não o seletor SIMD&FP de verdade), e checar só depois do
            // `if (vectorForm)` fazia esses 6 mnemônicos caírem em `decodeFpLoadLiteral` e
            // misdecodificar como `LDR (literal)` SIMD&FP (G8, ver `IsaCoverageReport.
            // AARCH64_MISDECODED`, entradas `CPYP#1`/`CPYM#1`/`CPYE#1` — removidas por esta task).
            // `GCSSTR`/`GCSSTTR` (`FEAT_GCS`, B19.27) tem prefixo fixo próprio dentro do MESMO
            // bucket — checado primeiro.
            if ((word & GCSSTR_FIXED_MASK) == GCSSTR_FIXED_VALUE) {
                return decodeGcsstr(word, address);
            }
            return decodeMemoryCopyAndSet(word, address);
        }
        if (((word >>> VECTOR_FORM_BIT_SHIFT) & 1) != 0) {
            // AdvSIMD load/store multiple/single structures (B8.6): bit31 fixo=0 nas duas formas
            // reais (não existe eixo W/X de 32/64 bits aqui, Q assume esse papel).
            boolean bit31Clear = (word >>> 31) == 0;
            int advSimdFixed = (word >>> ADVSIMD_LDST_FIXED_SHIFT) & ADVSIMD_LDST_FIXED_MASK;
            if (bit31Clear && advSimdFixed == ADVSIMD_LDST_MULTIPLE_PATTERN) {
                return decodeAdvancedSimdLoadStoreMultiple(word, address);
            }
            if (bit31Clear && advSimdFixed == ADVSIMD_LDST_SINGLE_PATTERN) {
                return decodeAdvancedSimdLoadStoreSingle(word, address);
            }
            // B8.13: `LDR`/`STR`/`LDP`/`STP`/`LDR (literal)` escalar SIMD&FP — MESMO campo de 2
            // bits (`subclass`, bits[29:28]) que já discrimina o lado GPR (V=0) abaixo; os 2
            // patterns AdvSIMD acima ocupam `subclass=00` com um prefixo de 6 bits mais específico
            // (checados primeiro), então o `default` aqui é só o resto de `subclass=00` (espaço
            // atômico/LSE, que SIMD&FP não tem no hardware real) — recusado de propósito (G8). O
            // caso `SUBCLASS_LITERAL` com bit24=1 (MOPS/GCS/atomic128) já foi interceptado ANTES
            // deste `if` (ver acima) — `decodeFpLoadLiteral` só vê bit24=0 daqui em diante.
            return switch (subclass) {
                case SUBCLASS_LITERAL -> decodeFpLoadLiteral(word, address);
                case SUBCLASS_PAIR -> decodeFpLoadStorePair(word, address);
                case SUBCLASS_SINGLE -> decodeFpLoadStoreSingle(word, address);
                default -> throw unsupported(word, address);
            };
        }
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

    /// `LDXR`/`LDAXR`/`STXR`/`STLXR`/`LDXP`/`LDAXP`/`STXP`/`STLXP`/`LDAR`/`LDLAR`/`STLR`/`STLLR`/
    /// `CAS*`/`CASP*` (B6.3.4 + B8.1): todas compartilham o subgrupo `Load/store exclusive and
    /// atomic` (`SUBCLASS_EXCLUSIVE_ATOMIC`), diferindo pelo campo `form` de 3 bits — ver as
    /// constantes `EXCLUSIVE_FORM_*` para a tabela completa e a desambiguação bit31 entre
    /// `STXP`/`LDXP` e `CASP` (mesmo `form` mascarado). `LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/
    /// `LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP` (extensão LSE opcional, `op0=1`+`opc` de operação
    /// aritmética no mesmo espaço de `CAS`) ficam FORA do escopo da B8.1 (decisão explícita do
    /// plano `b7-plano-cobertura-isa.md`: só `CAS`/`CASP` foram pedidos, apesar de todos serem da
    /// mesma extensão) — não alcançáveis por este método porque `form`+bit31 já esgotam o espaço
    /// de `CAS`/`CASP`/`STXR`/`LDXR`/`STXP`/`LDXP`/`STLR`/`LDAR` nas 8 combinações do campo.
    /// `CAS`/`CASP` (B11.11): gateadas por {@link Aarch64Feature#LSE} (`ARMv8.1-A`) — os demais
    /// ramos (`STXR`/`LDXR`/`STLR`/`LDAR`/`STXP`/`LDXP`) continuam baseline, sem gate.
    private Ir64Op decodeExclusive(int word, long address) {
        int form = (word >>> EXCLUSIVE_FORM_SHIFT) & EXCLUSIVE_FORM_MASK;
        if (form == EXCLUSIVE_FORM_STXR) {
            return decodeExclusiveSingle(word, true);
        }
        if (form == EXCLUSIVE_FORM_LDXR) {
            return decodeExclusiveSingle(word, false);
        }
        if (form == EXCLUSIVE_FORM_STLR) {
            return decodeOrderedSingle(word, true);
        }
        if (form == EXCLUSIVE_FORM_LDAR) {
            return decodeOrderedSingle(word, false);
        }
        int formIgnoringL = form & EXCLUSIVE_FORM_MASK_IGNORE_L;
        if (formIgnoringL == EXCLUSIVE_FORM_PAIR_OR_CASP) {
            boolean bit31 = ((word >>> EXCLUSIVE_PAIR_FIXED_BIT31_SHIFT) & 1) != 0;
            if (bit31) {
                boolean pairLoad = (form & EXCLUSIVE_FORM_PAIR_LOAD_BIT) != 0;
                return decodeExclusivePair(word, pairLoad);
            }
            // B11.11: FEAT_LSE (ARMv8.1-A) — um Cortex-A53 (ARMv8.0-A) não tem CASP.
            if (!architecture.has(Aarch64Feature.LSE)) {
                throw unsupported(word, address);
            }
            return decodeCompareAndSwapPair(word);
        }
        // formIgnoringL == EXCLUSIVE_FORM_CAS: as 8 combinações do campo de 3 bits já foram
        // esgotadas pelos ramos acima (000/010/100/110/001/011), só resta 101/111 = CAS.
        // B11.11: FEAT_LSE (ARMv8.1-A) — um Cortex-A53 (ARMv8.0-A) não tem CAS.
        if (!architecture.has(Aarch64Feature.LSE)) {
            throw unsupported(word, address);
        }
        return decodeCompareAndSwap(word);
    }

    private Ir64Op decodeExclusiveSingle(int word, boolean store) {
        Ir64MemSize size = decodeExclusiveSize(word);
        boolean acquireRelease = ((word >>> EXCLUSIVE_LASR_SHIFT) & 1) != 0;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        if (store) {
            int rs = (word >>> EXCLUSIVE_RS_SHIFT) & REGISTER_FIELD_MASK;
            return new Ir64Op.StoreExclusive(rs, rt, rn, size, acquireRelease);
        }
        return new Ir64Op.LoadExclusive(rt, rn, size, acquireRelease);
    }

    /// `LDAR`/`STLR` (B8.1): mesma semântica de {@link Ir64Op.Load64}/{@link Ir64Op.Store64} com
    /// endereçamento `[Rn]` (sem deslocamento) — o monitor de exclusividade NÃO se aplica aqui
    /// (diferente de `LDXR`/`STXR`), e a ordenação `acquire`/`release` é NOP observável neste
    /// interpretador (single-thread por construção), então reaproveitar os records comuns de
    /// load/store evita um `Kind` novo só para isso.
    private Ir64Op decodeOrderedSingle(int word, boolean store) {
        Ir64MemSize size = decodeExclusiveSize(word);
        boolean wide = size == Ir64MemSize.DOUBLEWORD;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        if (store) {
            return new Ir64Op.Store64(rt, rn, size, wide, Ir64AddressingMode.OFFSET, 0L, -1, null, 0);
        }
        return new Ir64Op.Load64(rt, rn, size, false, wide, Ir64AddressingMode.OFFSET, 0L, -1, null, 0);
    }

    /// `GCSSTR`/`GCSSTTR` (`FEAT_GCS`, B19.27): grava `Xt` em `[Xn]`. **Escopo isolado** — só o
    /// armazenamento em si, sem o resto do mecanismo de Guarded Control Stack (`GCSPR_EL0`/
    /// `GCSPR_EL1`, integração automática com `BL`/`BLR`/`RET`, exceção de violação); ver
    /// `## Resultado` da task para a task-mãe futura que cobre o restante da família. O bit
    /// `unpriv` distingue `GCSSTR`(0) de `GCSSTTR`(1, forma "unprivileged") — NOP observável
    /// neste emulador de espaço de endereço único, mesma simplificação já documentada para
    /// `LDTR`/`STTR` em {@link #decodeLoadStoreSingle} e para `LDAR`/`STLR` em
    /// {@link #decodeOrderedSingle}.
    private Ir64Op decodeGcsstr(int word, long address) {
        if (!architecture.has(Aarch64Feature.GUARDED_CONTROL_STACK)) {
            throw unsupported(word, address);
        }
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Store64(
                rt, rn, Ir64MemSize.DOUBLEWORD, true, Ir64AddressingMode.OFFSET, 0L, -1, null, 0);
    }

    /// `SETP`/`SETM`/`SETE` + `CPYFP`/`CPYFM`/`CPYFE`/`CPYP`/`CPYM`/`CPYE` (`FEAT_MOPS`, B19.16) —
    /// ver o comentário de `MOPS_ATOMIC128_BIT_SHIFT` para a árvore de decisão completa. `SETGP`/
    /// `SETGM`/`SETGE` (bit26=1 na família `SET*`, B19.14) continuam `unsupported` aqui de
    /// propósito — esta task não os implementa. `unpriv`/`nontemp` (`SET*`) e `options` (`CPY*`)
    /// não são modelados (mesma decisão de `PRFM`/`FPCR.RMode`: sem MMU de permissão nem cache
    /// para os hints afetarem). `LDAPR_i`/`STLR_i` (`FEAT_LRCPC2`, `@ldapr_stlr_i`, B19.19)
    /// compartilham o MESMO prefixo de 6 bits `011001` (bits[29:24]) que `SETP`/`SETM`/`SETE`/
    /// `CPYFP`/`CPYFM`/`CPYFE` (bit26=0 nos dois grupos) E o mesmo bit21=0 — só divergem em
    /// bits[11:10] (`01` fixo em MOPS, `00` fixo em `LDAPR_i`/`STLR_i`, confirmado byte a byte
    /// contra `a64.decode` real do QEMU). Checar esse campo é OBRIGATÓRIO antes de aceitar como
    /// MOPS, senão `LDAPR_i` com `opc=01/10/11` misdecodificaria como `CPYFM`/`CPYFE`/`SETP` (G8);
    /// a B19.19 despacha para {@link #decodeLoadAcquireOrStoreReleaseUnscaledImmediate} ANTES de
    /// checar {@link Aarch64Feature#MEMORY_COPY_SET}.
    private Ir64Op decodeMemoryCopyAndSet(int word, long address) {
        if (((word >>> MOPS_ATOMIC128_BIT_SHIFT) & 1) != 0) {
            // bit21=1: OU "Atomic 128-bit" (LDCLRP/LDSETP/SWPP, FEAT_LSE128, B19.25, bits[31:30]
            // real="00") OU a família de tag MTE (STG/LDG/..., FEAT_MTE2, B19.14, bits[31:30]="11"
            // — achado real desta task: bit21=1 é fixo por coincidência de encoding nas DUAS
            // famílias, e `LDAPR_i`/`STLR_i` (que também alcança bits[31:30]="11" na sua forma `X`
            // de 64 bits) tem bit21=0 sempre, então NUNCA colide aqui — só depois de checar bit21.
            int mopsSizeField = (word >>> MOPS_SIZE_FIELD_SHIFT) & MOPS_SIZE_FIELD_MASK;
            if (mopsSizeField == MEMORY_TAG_FAMILY_SIZE_FIELD_VALUE) {
                return decodeMemoryTagFamily(word, address);
            }
            // LDCLRP/LDSETP/SWPP (FEAT_LSE128, B19.25) — feature PRÓPRIA, independente de
            // FEAT_MOPS (checada abaixo só para o resto deste bucket); checar aqui, não depois.
            return decodeAtomic128(word, address);
        }
        int fixedBits1110 = (word >>> MOPS_FIXED_BITS_11_10_SHIFT) & MOPS_FIXED_BITS_11_10_MASK;
        if (fixedBits1110 == LDAPR_STLR_I_FIXED_BITS_11_10_VALUE) {
            // LDAPR_i/STLR_i (FEAT_LRCPC2, B19.19) — mesmo prefixo de 6 bits que SETP/CPYFx,
            // discriminado só por bits[11:10] (ver comentário de MOPS_FIXED_BITS_11_10_SHIFT).
            return decodeLoadAcquireOrStoreReleaseUnscaledImmediate(word, address);
        }
        if (!architecture.has(Aarch64Feature.MEMORY_COPY_SET)) {
            throw unsupported(word, address);
        }
        if (fixedBits1110 != MOPS_FIXED_BITS_11_10_VALUE) {
            throw unsupported(word, address); // reservado
        }
        int rd = word & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rs = (word >>> MOPS_RS_SHIFT) & REGISTER_FIELD_MASK;
        int phaseField = (word >>> MOPS_PHASE_FIELD_SHIFT) & MOPS_PHASE_FIELD_MASK;
        boolean tagOrGenericDirection = ((word >>> MOPS_TAG_OR_GENERIC_DIRECTION_BIT_SHIFT) & 1) != 0;
        if (phaseField == MOPS_SET_MARKER_PHASE_FIELD) {
            int phaseBits = (word >>> MOPS_SET_PHASE_SHIFT) & MOPS_PHASE_FIELD_MASK;
            if (tagOrGenericDirection) {
                // SETGP/SETGM/SETGE (variantes com tag, FEAT_MTE2, B19.14) — MESMO campo `@set` de
                // SETP/SETM/SETE, discriminadas só por este bit (B19.16 já corrigiu o misdecode que
                // fazia essas 3 caírem em decodeFpLoadLiteral — aqui já chegam separadas de verdade).
                if (!architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
                    throw unsupported(word, address);
                }
                return new Ir64Op.MemorySetTagged(decodeMopsPhase(phaseBits, word, address), rd, rn, rs);
            }
            return new Ir64Op.MemorySet(decodeMopsPhase(phaseBits, word, address), rd, rn, rs);
        }
        boolean forwardOnly = !tagOrGenericDirection;
        return new Ir64Op.MemoryCopy(decodeMopsPhase(phaseField, word, address), forwardOnly, rd, rs, rn);
    }

    /// `LDAPR_i`/`STLR_i` (`FEAT_LRCPC2`, ARMv8.4-A, B19.19) — a forma com **offset imediato de 9
    /// bits com sinal** de `LDAPR`/`STLR` (que só existiam com endereçamento por registrador,
    /// `FEAT_LRCPC`/B19.1). Campos `sz`(bits[31:30])/`opc`(bits[23:22])/`imm9`(bits[20:12])/
    /// `Rn`(bits[9:5])/`Rt`(bits[4:0]) caem EXATAMENTE nas mesmas posições que
    /// {@link #SINGLE_SIZE_SHIFT}/{@link #SINGLE_OPC_SHIFT}/{@link #SINGLE_IMM9_SHIFT}/
    /// {@link #RN_SHIFT} de {@link #decodeLoadStoreSingle} — reusa {@link #decodeSingleForm} e
    /// {@link #buildSingle} sem nenhuma lógica nova: os mesmos combos reservados de `size`×`opc`
    /// (`WORD`+`SIGN_EXTEND_TO_W`, `DOUBLEWORD`+`SIGN_EXTEND_TO_X`/`SIGN_EXTEND_TO_W`) já excluem
    /// exatamente as combinações que o `a64.decode` real não lista para `LDAPR_i`. Ordenação
    /// acquire/release é NOP observável neste interpretador single-thread — mesma decisão herdada
    /// de `LDAR`/`STLR`/`LDAPR` sem offset (Javadoc de {@link Aarch64Feature#LRCPC2}), não
    /// revisitada aqui.
    private Ir64Op decodeLoadAcquireOrStoreReleaseUnscaledImmediate(int word, long address) {
        if (!architecture.has(Aarch64Feature.LRCPC2)) {
            throw unsupported(word, address);
        }
        int sizeField = (word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK;
        int opcField = (word >>> SINGLE_OPC_SHIFT) & SINGLE_OPC_MASK;
        SingleForm form = decodeSingleForm(sizeField, opcField, word, address);
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        int imm9 = (word >>> SINGLE_IMM9_SHIFT) & (int) bitMask(SINGLE_IMM9_BITS);
        long immediate = signExtend(imm9, SINGLE_IMM9_BITS);
        return buildSingle(form, rt, rn, Ir64AddressingMode.OFFSET, immediate, -1, null, 0);
    }

    private Ir64Op.Ir64MopsPhase decodeMopsPhase(int phaseBits, int word, long address) {
        return switch (phaseBits) {
            case MOPS_PHASE_PROLOGUE -> Ir64Op.Ir64MopsPhase.PROLOGUE;
            case MOPS_PHASE_MAIN -> Ir64Op.Ir64MopsPhase.MAIN;
            case MOPS_PHASE_EPILOGUE -> Ir64Op.Ir64MopsPhase.EPILOGUE;
            default -> throw unsupported(word, address); // reservado
        };
    }

    /// `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G`/`STGM`/`LDGM`/`STZGM` (`FEAT_MTE2`, ARMv8.5-A, B19.14) —
    /// `size`(bits[23:22]) escolhe o mnemônico, `variant`(bits[11:10]) escolhe a forma: `00`="forma
    /// multiple" (`STZGM`/`STGM`/`LDGM`) OU `LDG` (única exceção — `LDG` também mede `variant=00`,
    /// mas com `size=01`, e é uma instrução "single" comum, não "multiple"; confirmado byte a byte
    /// contra `a64.decode` real do QEMU: só existe UMA linha de `LDG`, sem forma pre/post-index).
    /// `01`/`10`/`11` são sempre a forma "single/pair" (`STG`/`STZG`/`ST2G`/`STZ2G`) com endereçamento
    /// `POST_INDEX`/`OFFSET`/`PRE_INDEX` respectivamente — **achado real**: essa correspondência é
    /// INVERTIDA em relação à convenção `p`/`w` normal de `LDR`/`STR` (aqui `variant=01` mede
    /// `p=1,w=1` no `a64.decode`, mas o efeito observável de `do_STG`/`trans_LDG` do QEMU, lido byte
    /// a byte, é POST-index: sem aplicar o imediato ao endereço acessado, só ao valor escrito de
    /// volta em `Rn`) — ver `## Resultado` da task para o raciocínio completo.
    private Ir64Op decodeMemoryTagFamily(int word, long address) {
        if (!architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
            throw unsupported(word, address);
        }
        int size = (word >>> TAG_FAMILY_SIZE_SHIFT) & TAG_FAMILY_SIZE_MASK;
        int variant = (word >>> TAG_FAMILY_VARIANT_SHIFT) & TAG_FAMILY_VARIANT_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        long imm9 = (word >>> TAG_FAMILY_IMM9_SHIFT) & bitMask(TAG_FAMILY_IMM9_BITS);
        long immediate = signExtend(imm9, TAG_FAMILY_IMM9_BITS) * MEMORY_TAG_GRANULE_SCALE_BYTES;
        if (variant == TAG_FAMILY_VARIANT_MULTIPLE) {
            return switch (size) {
                case TAG_FAMILY_SIZE_STZGM -> new Ir64Op.MemoryTagMultiple(
                        Ir64Op.Ir64MemoryTagMultipleOperation.STORE_ZERO_DATA_TAGS, rt, rn);
                case TAG_FAMILY_SIZE_LDG -> new Ir64Op.MemoryTag(Ir64Op.Ir64MemoryTagOperation.LOAD,
                        false, 1, rt, rn, Ir64AddressingMode.OFFSET, immediate);
                case TAG_FAMILY_SIZE_STGM -> new Ir64Op.MemoryTagMultiple(
                        Ir64Op.Ir64MemoryTagMultipleOperation.STORE_TAGS, rt, rn);
                case TAG_FAMILY_SIZE_LDGM -> new Ir64Op.MemoryTagMultiple(
                        Ir64Op.Ir64MemoryTagMultipleOperation.LOAD_TAGS, rt, rn);
                default -> throw new AssertionError("size de 2 bits só tem 4 valores possíveis");
            };
        }
        Ir64AddressingMode addressingMode = switch (variant) {
            case TAG_FAMILY_VARIANT_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case TAG_FAMILY_VARIANT_OFFSET -> Ir64AddressingMode.OFFSET;
            case TAG_FAMILY_VARIANT_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        boolean zeroData = (size & 0b01) != 0; // STZG(01)/STZ2G(11) zeram dados; STG(00)/ST2G(10) não.
        int granules = (size & 0b10) != 0 ? 2 : 1; // ST2G(10)/STZ2G(11) cobrem 2 granules.
        return new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, zeroData, granules, rt, rn, addressingMode, immediate);
    }

    /// `LDCLRP`/`LDSETP`/`SWPP` (`FEAT_LSE128`, ARMv9.4-A, B19.25) — ver o comentário de
    /// {@link #ATOMIC128_XZR_INDEX} para a árvore de decisão completa e {@link Ir64Op.AtomicMemoryOpPair}
    /// para a semântica de execução.
    private Ir64Op decodeAtomic128(int word, long address) {
        if (!architecture.has(Aarch64Feature.LSE128)) {
            throw unsupported(word, address);
        }
        int opcodeField = (word >>> ATOMIC128_OPCODE_SHIFT) & ATOMIC128_OPCODE_MASK;
        Ir64AtomicOp operation = switch (opcodeField) {
            case ATOMIC128_OPCODE_LDCLRP -> Ir64AtomicOp.CLR;
            case ATOMIC128_OPCODE_LDSETP -> Ir64AtomicOp.SET;
            case ATOMIC128_OPCODE_SWPP -> Ir64AtomicOp.SWP;
            default -> throw unsupported(word, address); // reservado
        };
        boolean acquire = ((word >>> ATOMIC128_ACQUIRE_SHIFT) & 1) != 0;
        boolean release = ((word >>> ATOMIC128_RELEASE_SHIFT) & 1) != 0;
        int rt2 = (word >>> ATOMIC128_RT2_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        if (rt == ATOMIC128_XZR_INDEX || rt2 == ATOMIC128_XZR_INDEX || rt == rt2) {
            throw unsupported(word, address);
        }
        return new Ir64Op.AtomicMemoryOpPair(rt, rt2, rn, operation, acquire, release);
    }

    private Ir64Op decodeExclusivePair(int word, boolean load) {
        boolean wide = ((word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK) == SIZE_DOUBLEWORD;
        boolean acquireRelease = ((word >>> EXCLUSIVE_LASR_SHIFT) & 1) != 0;
        int rt2 = (word >>> EXCLUSIVE_RT2_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        if (load) {
            return new Ir64Op.LoadExclusivePair(rt, rt2, rn, wide, acquireRelease);
        }
        int rs = (word >>> EXCLUSIVE_RS_SHIFT) & REGISTER_FIELD_MASK;
        return new Ir64Op.StoreExclusivePair(rs, rt, rt2, rn, wide, acquireRelease);
    }

    private Ir64Op decodeCompareAndSwapPair(int word) {
        boolean wide = ((word >>> PAIR_OPC_SHIFT) & 1) != 0; // bit30; bit31 fixo=0 em CASP
        int rs = (word >>> EXCLUSIVE_RS_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.CompareAndSwapPair(rs, rt, rn, wide);
    }

    private Ir64Op decodeCompareAndSwap(int word) {
        Ir64MemSize size = decodeExclusiveSize(word);
        int rs = (word >>> EXCLUSIVE_RS_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.CompareAndSwap(rs, rt, rn, size);
    }

    /// `LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP` (`FEAT_LSE`,
    /// ARMv8.1-A) + `LDAPR`/`LDAPRB`/`LDAPRH` forma registrador (`FEAT_LRCPC`, ARMv8.3-A) — B19.1,
    /// sub-espaço "Atomic memory operations" de {@link #decodeLoadStoreSingle} (`ARM DDI 0487
    /// C3.2.5`, `target/isa-decode/a64.decode` linhas 548-561). Layout: `size`(bits[31:30]),
    /// `A`(bit23), `R`(bit22), `bit21`=1, `Rs`(bits[20:16]), `o3`(bit15), `opc`(bits[14:12]),
    /// bits[11:10]=00, `Rn`(bits[9:5]), `Rt`(bits[4:0]). As 9 operações LSE gateadas por
    /// {@link Aarch64Feature#LSE}; `LDAPR` (`o3`=1, `opc`=100, `Rs`=`XZR`, `A`=1, `R`=0) por
    /// {@link Aarch64Feature#LRCPC} e reaproveita {@link Ir64Op.Load64} (endereçamento
    /// {@code OFFSET}, imediato `0`), como `LDAR` em {@link #decodeOrderedSingle}. Todo o resto do
    /// espaço `o3`:`opc` (`LD64B`/`ST64B` de `FEAT_LS64`, reservados, `LDAPR` malformado) →
    /// {@code UNSUPPORTED} explícito (G8).
    private Ir64Op decodeAtomicMemoryOp(int word, long address) {
        Ir64MemSize size = decodeExclusiveSize(word); // bits[31:30], as 4 larguras B/H/W/X
        boolean acquire = ((word >>> ATOMIC_ACQUIRE_SHIFT) & 1) != 0;
        boolean release = ((word >>> ATOMIC_RELEASE_SHIFT) & 1) != 0;
        boolean o3 = ((word >>> ATOMIC_O3_SHIFT) & 1) != 0;
        int opc = (word >>> ATOMIC_OPC_SHIFT) & ATOMIC_OPC_MASK;
        int rs = (word >>> SINGLE_RM_SHIFT) & REGISTER_FIELD_MASK; // bits[20:16], 31 => XZR
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;        // bits[9:5],  31 => SP
        int rt = word & REGISTER_FIELD_MASK;                       // bits[4:0],  31 => XZR

        if (!o3) {
            Ir64AtomicOp operation = switch (opc) {
                case ATOMIC_OPC_LDADD -> Ir64AtomicOp.ADD;
                case ATOMIC_OPC_LDCLR -> Ir64AtomicOp.CLR;
                case ATOMIC_OPC_LDEOR -> Ir64AtomicOp.EOR;
                case ATOMIC_OPC_LDSET -> Ir64AtomicOp.SET;
                case ATOMIC_OPC_LDSMAX -> Ir64AtomicOp.SMAX;
                case ATOMIC_OPC_LDSMIN -> Ir64AtomicOp.SMIN;
                case ATOMIC_OPC_LDUMAX -> Ir64AtomicOp.UMAX;
                case ATOMIC_OPC_LDUMIN -> Ir64AtomicOp.UMIN;
                default -> throw new IllegalStateException("unreachable"); // opc é 3 bits, 8 casos
            };
            if (!architecture.has(Aarch64Feature.LSE)) {
                throw unsupported(word, address);
            }
            return new Ir64Op.AtomicMemoryOp(rs, rt, rn, size, operation, acquire, release);
        }
        // o3 == 1
        if (opc == ATOMIC_O3_OPC_SWP) {
            if (!architecture.has(Aarch64Feature.LSE)) {
                throw unsupported(word, address);
            }
            return new Ir64Op.AtomicMemoryOp(rs, rt, rn, size, Ir64AtomicOp.SWP, acquire, release);
        }
        if (opc == ATOMIC_O3_OPC_LDAPR) {
            // `LDAPR` exige `Rs`=`XZR`, `A`=1, `R`=0 fixos — qualquer outra combinação neste
            // slot não é `LDAPR` nem nada (G8, não confundir com `LDUR`/`STUR`).
            if (rs != ATOMIC_LDAPR_RS_FIXED || !acquire || release) {
                throw unsupported(word, address);
            }
            if (!architecture.has(Aarch64Feature.LRCPC)) {
                throw unsupported(word, address);
            }
            boolean wide = size == Ir64MemSize.DOUBLEWORD;
            return new Ir64Op.Load64(rt, rn, size, false, wide,
                    Ir64AddressingMode.OFFSET, 0L, -1, null, 0);
        }
        // `o3`=1 com `opc` em {001,010,011} = `LD64B`/`ST64B`/`LD64BV`/`ST64BV0` (`FEAT_LS64`,
        // fora do escopo de B19.1); {101,110,111} = reservado. G8: recusar.
        throw unsupported(word, address);
    }

    private static Ir64MemSize decodeExclusiveSize(int word) {
        return switch ((word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK) {
            case SIZE_BYTE -> Ir64MemSize.BYTE;
            case SIZE_HALF -> Ir64MemSize.HALF;
            case SIZE_WORD -> Ir64MemSize.WORD;
            case SIZE_DOUBLEWORD -> Ir64MemSize.DOUBLEWORD;
            default -> throw new IllegalStateException("unreachable");
        };
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store MULTIPLE structures, B8.6) — ver
    /// {@link Ir64Op.VectorLoadStoreMultiple}.
    private Ir64Op decodeAdvancedSimdLoadStoreMultiple(int word, long address) {
        boolean q = ((word >>> ADVSIMD_LDST_Q_SHIFT) & 1) != 0;
        boolean postIndex = ((word >>> ADVSIMD_LDST_POST_INDEX_SHIFT) & 1) != 0;
        boolean load = ((word >>> ADVSIMD_LDST_LOAD_SHIFT) & 1) != 0;
        int rawRm = (word >>> ADVSIMD_LDST_RM_SHIFT) & REGISTER_FIELD_MASK;
        if (!postIndex && rawRm != 0) {
            // "For non-postindexed accesses the Rm field must be 0" (trans_LD_mult/trans_ST_mult
            // reais do QEMU) — G8: recusar a combinação reservada em vez de ignorar o campo.
            throw unsupported(word, address);
        }
        int opcode = (word >>> ADVSIMD_LDST_MULT_OPCODE_SHIFT) & ADVSIMD_LDST_MULT_OPCODE_MASK;
        int rpt;
        int selem;
        switch (opcode) {
            case ADVSIMD_LDST_MULT_OPCODE_SELEM4 -> { rpt = 1; selem = 4; }
            case ADVSIMD_LDST_MULT_OPCODE_SELEM1_X4REG -> { rpt = 4; selem = 1; }
            case ADVSIMD_LDST_MULT_OPCODE_SELEM3 -> { rpt = 1; selem = 3; }
            case ADVSIMD_LDST_MULT_OPCODE_SELEM1_X3REG -> { rpt = 3; selem = 1; }
            case ADVSIMD_LDST_MULT_OPCODE_SELEM1_X1REG -> { rpt = 1; selem = 1; }
            case ADVSIMD_LDST_MULT_OPCODE_SELEM2 -> { rpt = 1; selem = 2; }
            case ADVSIMD_LDST_MULT_OPCODE_SELEM1_X2REG -> { rpt = 2; selem = 1; }
            // Demais valores de `opcode` são UNALLOCATED (G8).
            default -> throw unsupported(word, address);
        }
        int elementSizeLog2 = (word >>> ADVSIMD_LDST_MULT_SIZE_SHIFT) & ADVSIMD_LDST_MULT_SIZE_MASK;
        if (elementSizeLog2 == ADVSIMD_LDST_ELEMENT_SIZE_LOG2_DOUBLEWORD && !q && selem != 1) {
            throw unsupported(word, address);
        }
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        int rm = postIndex ? (rawRm == ADVSIMD_LDST_RM_IMMEDIATE_ENCODING ? -1 : rawRm) : -1;
        return new Ir64Op.VectorLoadStoreMultiple(load, rt, rn, rm, q, postIndex, elementSizeLog2, rpt, selem);
    }

    /// `LD1`-`LD4`/`ST1`-`ST4`/`LD1R`-`LD4R` (AdvSIMD load/store SINGLE structure, B8.6) — ver
    /// {@link Ir64Op.VectorLoadStoreSingle}/{@link Ir64Op.VectorLoadSingleReplicate}. `selem` usa
    /// os mesmos 2 bits espalhados (`bit13`+`bit21`) nas 3 famílias de tamanho E na forma de
    /// replicar; o resto dos bits de `opcode`/`S`/`size` é interpretado de um jeito DIFERENTE por
    /// família (byte/half/word/double/replicar) — fatos conferidos contra `a64.decode` real do
    /// QEMU (ver bloco de constantes `ADVSIMD_LDST_SINGLE_*`).
    private Ir64Op decodeAdvancedSimdLoadStoreSingle(int word, long address) {
        boolean q = ((word >>> ADVSIMD_LDST_Q_SHIFT) & 1) != 0;
        boolean postIndex = ((word >>> ADVSIMD_LDST_POST_INDEX_SHIFT) & 1) != 0;
        boolean load = ((word >>> ADVSIMD_LDST_LOAD_SHIFT) & 1) != 0;
        int rawRm = (word >>> ADVSIMD_LDST_RM_SHIFT) & REGISTER_FIELD_MASK;
        if (!postIndex && rawRm != 0) {
            throw unsupported(word, address);
        }
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        int rm = postIndex ? (rawRm == ADVSIMD_LDST_RM_IMMEDIATE_ENCODING ? -1 : rawRm) : -1;
        int selemLow = (word >>> ADVSIMD_LDST_SINGLE_SELEM_LOW_SHIFT) & 1;
        int selemHigh = (word >>> ADVSIMD_LDST_SINGLE_SELEM_HIGH_SHIFT) & 1;
        int selem = ((selemLow << 1) | selemHigh) + 1;
        int opcHigh = (word >>> ADVSIMD_LDST_SINGLE_OPC_HIGH_SHIFT) & ADVSIMD_LDST_SINGLE_OPC_HIGH_MASK;
        if (opcHigh == ADVSIMD_LDST_SINGLE_OPC_HIGH_REPLICATE) {
            // `LD1R`-`LD4R`: não existe forma `ST` (bit22=`L` sempre `1` no encoding real) nem
            // combinação com o bit reservado setado (`trans_LD_single_repl` real do QEMU).
            boolean reservedBitSet = ((word >>> ADVSIMD_LDST_SINGLE_REPL_RESERVED_BIT_SHIFT) & 1) != 0;
            if (!load || reservedBitSet) {
                throw unsupported(word, address);
            }
            int elementSizeLog2 = (word >>> ADVSIMD_LDST_SINGLE_REPL_SCALE_SHIFT) & ADVSIMD_LDST_SINGLE_REPL_SCALE_MASK;
            return new Ir64Op.VectorLoadSingleReplicate(rt, rn, rm, q, postIndex, elementSizeLog2, selem);
        }
        int elementSizeLog2;
        int index;
        switch (opcHigh) {
            case ADVSIMD_LDST_SINGLE_OPC_HIGH_BYTE -> {
                elementSizeLog2 = 0;
                int indexLow = (word >>> ADVSIMD_LDST_SINGLE_INDEX_LOW_BYTE_SHIFT) & ADVSIMD_LDST_SINGLE_INDEX_LOW_BYTE_MASK;
                index = ((q ? 1 : 0) << 3) | indexLow;
            }
            case ADVSIMD_LDST_SINGLE_OPC_HIGH_HALF -> {
                boolean reservedBitSet = ((word >>> ADVSIMD_LDST_SINGLE_HALF_RESERVED_BIT_SHIFT) & 1) != 0;
                if (reservedBitSet) {
                    throw unsupported(word, address);
                }
                elementSizeLog2 = 1;
                int indexLow = (word >>> ADVSIMD_LDST_SINGLE_INDEX_LOW_HALF_SHIFT) & ADVSIMD_LDST_SINGLE_INDEX_LOW_HALF_MASK;
                index = ((q ? 1 : 0) << 2) | indexLow;
            }
            case ADVSIMD_LDST_SINGLE_OPC_HIGH_WORD_OR_DOUBLE -> {
                boolean isDoubleword = ((word >>> ADVSIMD_LDST_SINGLE_WORD_OR_DOUBLE_BIT_SHIFT) & 1) != 0;
                if (!isDoubleword) {
                    boolean reservedBitSet = ((word >>> ADVSIMD_LDST_SINGLE_WORD_RESERVED_BIT_SHIFT) & 1) != 0;
                    if (reservedBitSet) {
                        throw unsupported(word, address);
                    }
                    elementSizeLog2 = 2;
                    index = ((q ? 1 : 0) << 1) | ((word >>> ADVSIMD_LDST_SINGLE_INDEX_WORD_BIT_SHIFT) & 1);
                } else {
                    int reserved = (word >>> ADVSIMD_LDST_SINGLE_DOUBLE_RESERVED_SHIFT) & ADVSIMD_LDST_SINGLE_DOUBLE_RESERVED_MASK;
                    if (reserved != 0) {
                        throw unsupported(word, address);
                    }
                    elementSizeLog2 = 3;
                    // Doubleword: `Q` reaproveitado DIRETAMENTE como índice (`@ldst_single_d` real
                    // nomeia esse mesmo bit `index`, não `q` — só 2 lanes de 8 bytes existem).
                    index = q ? 1 : 0;
                }
            }
            default -> throw new IllegalStateException("unreachable");
        }
        return new Ir64Op.VectorLoadStoreSingle(load, rt, rn, rm, postIndex, elementSizeLog2, selem, index);
    }

    private Ir64Op decodeLoadLiteral(int word, long address) {
        int opc = (word >>> LITERAL_OPC_SHIFT) & LITERAL_OPC_MASK;
        // `PRFM (literal)` (B19.6 bloco B): não escreve registrador — `Rt` codifica um `prfop`, não
        // um destino real — mesma semântica NOP puro da forma registrador de `PRFM` (B8.1, linha
        // 1906 acima: este emulador não modela cache nenhum).
        if (opc == LITERAL_OPC_PRFM) {
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.NOP_HINT);
        }
        boolean wide;
        boolean signExtend;
        switch (opc) {
            case LITERAL_OPC_32BIT -> { wide = false; signExtend = false; }
            case LITERAL_OPC_64BIT -> { wide = true; signExtend = false; }
            case LITERAL_OPC_LDRSW -> { wide = true; signExtend = true; }
            default -> throw new IllegalStateException("unreachable");
        }
        long imm19 = (word >>> LITERAL_IMM19_SHIFT) & bitMask(LITERAL_IMM19_BITS);
        long offset = signExtend(imm19, LITERAL_IMM19_BITS) * LITERAL_BYTES_PER_UNIT;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.LoadLiteral64(rt, address + offset, wide, signExtend);
    }

    private Ir64Op decodeLoadStorePair(int word, long address) {
        int opc = (word >>> PAIR_OPC_SHIFT) & PAIR_OPC_MASK;
        boolean load = ((word >>> PAIR_LOAD_BIT_SHIFT) & 1) != 0;
        boolean wide;
        boolean ldpsw = false;
        if (opc == PAIR_OPC_64BIT) {
            wide = true;
        } else if (opc == PAIR_OPC_32BIT) {
            wide = false;
        } else if (opc == PAIR_OPC_32BIT_SIGNED && load) {
            wide = false;
            ldpsw = true;
        } else if (opc == PAIR_OPC_32BIT_SIGNED) {
            // opc=01 com load=false é STGP (FEAT_MTE2, B19.14) — mesmo formato @ldstpair, offset
            // escalado por 16 (granule), não por 8.
            return decodeStorePairTag(word, address);
        } else {
            // opc=11 é reservado.
            throw unsupported(word, address);
        }
        int addrModeField = (word >>> PAIR_ADDR_MODE_SHIFT) & PAIR_ADDR_MODE_MASK;
        Ir64AddressingMode addressingMode = switch (addrModeField) {
            case PAIR_ADDR_MODE_NO_ALLOC_HINT, PAIR_ADDR_MODE_OFFSET -> Ir64AddressingMode.OFFSET;
            case PAIR_ADDR_MODE_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case PAIR_ADDR_MODE_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        long imm7 = (word >>> PAIR_IMM7_SHIFT) & bitMask(PAIR_IMM7_BITS);
        int scale = wide ? PAIR_DOUBLEWORD_SCALE_BYTES : PAIR_WORD_SCALE_BYTES;
        long immediate = signExtend(imm7, PAIR_IMM7_BITS) * scale;
        int rt2 = (word >>> PAIR_RT2_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.LoadStorePair(load, rt, rt2, rn, wide, addressingMode, immediate, ldpsw);
    }

    /// `STGP` (`FEAT_MTE2`, B19.14) — MESMO layout de campo que {@link #decodeLoadStorePair}
    /// (`@ldstpair`), só o escalonamento do imediato muda: granule de 16 bytes, não doubleword de 8
    /// (ver Javadoc de {@link Ir64Op.StorePairTag}).
    private Ir64Op decodeStorePairTag(int word, long address) {
        if (!architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
            throw unsupported(word, address);
        }
        int addrModeField = (word >>> PAIR_ADDR_MODE_SHIFT) & PAIR_ADDR_MODE_MASK;
        Ir64AddressingMode addressingMode = switch (addrModeField) {
            case PAIR_ADDR_MODE_NO_ALLOC_HINT, PAIR_ADDR_MODE_OFFSET -> Ir64AddressingMode.OFFSET;
            case PAIR_ADDR_MODE_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case PAIR_ADDR_MODE_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        long imm7 = (word >>> PAIR_IMM7_SHIFT) & bitMask(PAIR_IMM7_BITS);
        long immediate = signExtend(imm7, PAIR_IMM7_BITS) * MEMORY_TAG_GRANULE_SCALE_BYTES;
        int rt2 = (word >>> PAIR_RT2_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.StorePairTag(rt, rt2, rn, addressingMode, immediate);
    }

    private Ir64Op decodeLoadStoreSingle(int word, long address) {
        int sizeField = (word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK;
        int opcField = (word >>> SINGLE_OPC_SHIFT) & SINGLE_OPC_MASK;
        // `LDRA` (B19.15, `FEAT_PAuth`): `idx`(bits[11:10])∈{POST_INDEX,PRE_INDEX} & `bit21`=1 &
        // `size`=DOUBLEWORD — o mesmo espaço que o guard "idx==POST_INDEX/PRE_INDEX com bit21=1"
        // abaixo já reservava (recusando com `unsupported`, tasks anteriores). Interceptado ANTES
        // do caso PRFM abaixo: sem isso, `LDRAB` com `M=1,S=0` (`opcField` bits[23:22] igual a
        // `OPC_LOAD_SIGN_EXTEND_TO_X`) misdecodificaria como PRFM (bug real achado nesta task — o
        // check de PRFM não olha `idx`/`bit21`, só `size`/`opc`).
        int idxField = (word >>> SINGLE_IDX_SHIFT) & SINGLE_IDX_MASK;
        boolean bit21Early = ((word >>> SINGLE_BIT21_SHIFT) & 1) != 0;
        if (bit21Early && sizeField == SIZE_DOUBLEWORD
                && (idxField == IDX_POST_INDEX || idxField == IDX_PRE_INDEX)) {
            if (!architecture.has(Aarch64Feature.POINTER_AUTHENTICATION)) {
                throw unsupported(word, address);
            }
            return decodeLoadRegisterAuthenticated(word, address, idxField == IDX_PRE_INDEX);
        }
        // Atomic memory operations (LSE `LDADD`/.../`SWP` + `LDAPR`, B19.1): `idx`==UNSCALED &
        // bit21==1, fora da forma "unsigned offset" (bit24==0). Interceptado ANTES do caso PRFM
        // abaixo — senão `LDADDA`/`SWPA`/... de largura `X` (`size`=11, `A`=1, `R`=0) casariam
        // `sizeField==DOUBLEWORD && opcField==OPC_LOAD_SIGN_EXTEND_TO_X` e virariam PRFM (G8).
        boolean unsignedOffset = ((word >>> SINGLE_SCALED_OFFSET_BIT_SHIFT) & 1) != 0;
        if (!unsignedOffset
                && ((word >>> SINGLE_IDX_SHIFT) & SINGLE_IDX_MASK) == IDX_UNSCALED
                && ((word >>> SINGLE_BIT21_SHIFT) & 1) != 0) {
            return decodeAtomicMemoryOp(word, address);
        }
        if (sizeField == SIZE_DOUBLEWORD && opcField == OPC_LOAD_SIGN_EXTEND_TO_X) {
            // PRFM (B8.1, as 3 formas de endereçamento): hint puro, `Rt` codifica um `prfop` em
            // vez de um registrador real — NOP observável, este emulador não modela cache. Tinha
            // que ser interceptado ANTES de chamar decodeSingleForm/decodeSingleForm, cujo guard
            // presumia (errado) que esta combinação era só a forma SIMD&FP de 128 bits — essa
            // exige V=1, já filtrado bem antes em decodeLoadsAndStores.
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.NOP_HINT);
        }
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
        boolean bit21 = ((word >>> SINGLE_BIT21_SHIFT) & 1) != 0;
        if (idx == IDX_REGISTER_OFFSET && bit21) {
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
        if (idx == IDX_UNSCALED && bit21) {
            // Atomic memory operations (LDADD/.../SWP + LDAPR): já interceptadas no topo deste
            // método (B19.1), antes do caso PRFM. Este ramo fica como defesa (G8) caso a ordem
            // dos checks acima mude — nunca deve ser alcançado hoje.
            throw unsupported(word, address);
        }
        if (bit21) {
            // `idx==POST_INDEX`/`PRE_INDEX` com bit21=1 (B11.3, achado real): os formatos
            // `@ldst_imm`/`@ldst_imm_post`/`@ldst_imm_pre`/`@ldst_imm_user` reais (`a64.decode` do
            // QEMU) exigem bit21=0 — bit21=1 nesse espaço é `LDRA`/`LDRAB` ("Load/store register
            // (pointer authentication)", `size`=DOUBLEWORD), já interceptado no topo deste método
            // (B19.15). O que sobra aqui é a combinação RESERVADA `bit21=1` com `size`≠DOUBLEWORD
            // (`LDRA*` só existe na forma de 64 bits) — G8: recusar.
            throw unsupported(word, address);
        }
        Ir64AddressingMode addressingMode = switch (idx) {
            // bit21=1 já foi tratado acima para os três casos em que existe; aqui só sobra
            // bit21=0: idx=00 é LDUR/STUR, idx=10 é a forma "unprivileged" LDTR/STTR — mesmo
            // endereçamento funcional de LDUR/STUR (este emulador não modela EL0/EL1 de um jeito
            // que distinga o modo de acesso, mesma simplificação documentada para LDAR/STLR em
            // decodeOrderedSingle).
            case IDX_UNSCALED, IDX_REGISTER_OFFSET -> Ir64AddressingMode.OFFSET;
            case IDX_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case IDX_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        int imm9 = (word >>> SINGLE_IMM9_SHIFT) & (int) bitMask(SINGLE_IMM9_BITS);
        long immediate = signExtend(imm9, SINGLE_IMM9_BITS);
        return buildSingle(form, rt, rn, addressingMode, immediate, -1, null, 0);
    }

    /// `LDRAA`/`LDRAB Xt, [Xn{, #imm}]{!}` (B19.15, `FEAT_PAuth`, `ARM DDI 0487 C6.2.133/134`) —
    /// já confirmado `size`=DOUBLEWORD/`idx`/`bit21` pelo chamador. Rota (b) registrada na task:
    /// nenhuma autenticação real é modelada, então `Xn` é usado DIRETO como endereço-base (a
    /// "autenticação" de `Xn` é identidade, mesmo precedente de {@link Ir64Op.PointerAuthInPlace})
    /// — o resultado observável é IDÊNTICO a um `LDR Xt, [Xn, #imm]` comum, com ou sem
    /// `writeback` conforme `W`. Reaproveita {@link Ir64Op.Load64} diretamente (nenhum record
    /// novo necessário: mesma forma de endereçamento pré-indexada/offset já suportada).
    private Ir64Op decodeLoadRegisterAuthenticated(int word, long address, boolean writeback) {
        boolean signBit = ((word >>> LDRA_S_BIT_SHIFT) & 1) != 0;
        int imm9 = (word >>> SINGLE_IMM9_SHIFT) & (int) bitMask(SINGLE_IMM9_BITS);
        int combinedImm = (signBit ? 1 << SINGLE_IMM9_BITS : 0) | imm9;
        long immediate = signExtend(combinedImm, LDRA_IMM_TOTAL_BITS) * LDRA_SCALE_BYTES;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        Ir64AddressingMode addressingMode =
                writeback ? Ir64AddressingMode.PRE_INDEX : Ir64AddressingMode.OFFSET;
        return new Ir64Op.Load64(rt, rn, Ir64MemSize.DOUBLEWORD, false, true, addressingMode,
                immediate, -1, null, 0);
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

    /// `LDR`/`STR` SIMD&FP registrador-imediato (`ARM DDI 0487 C4.1.5`, `V=1` — B8.13): MESMO
    /// layout de campos de {@link #decodeLoadStoreSingle} (`size`/`opc`/`imm12`/`imm9`/`Rm`+
    /// `option`+`S`) — só a tabela `size`→tamanho e a ausência de sinal mudam. Espelha
    /// {@link #decodeLoadStoreSingle} quase linha a linha de propósito (mesmos nomes de campo do
    /// encoding real), não abstraído num helper comum para não arriscar o lado GPR já testado.
    private Ir64Op decodeFpLoadStoreSingle(int word, long address) {
        int sizeField = (word >>> SINGLE_SIZE_SHIFT) & SINGLE_SIZE_MASK;
        int opcField = (word >>> SINGLE_OPC_SHIFT) & SINGLE_OPC_MASK;
        FpSingleForm form = decodeFpSingleForm(sizeField, opcField, word, address);
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vt = word & REGISTER_FIELD_MASK;

        boolean scaledOffset = ((word >>> SINGLE_SCALED_OFFSET_BIT_SHIFT) & 1) != 0;
        if (scaledOffset) {
            int imm12 = (word >>> SINGLE_IMM12_SHIFT) & SINGLE_IMM12_MASK;
            long immediate = (long) imm12 * form.size.bytes();
            return buildFpSingle(form, vt, rn, Ir64AddressingMode.OFFSET, immediate, -1, null, 0);
        }
        int idx = (word >>> SINGLE_IDX_SHIFT) & SINGLE_IDX_MASK;
        boolean bit21 = ((word >>> SINGLE_BIT21_SHIFT) & 1) != 0;
        if (idx == IDX_REGISTER_OFFSET && bit21) {
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
            int shiftAmount = shiftFlag ? form.size.sizeLog2() : 0;
            return buildFpSingle(form, vt, rn, Ir64AddressingMode.REGISTER_OFFSET, 0, rm, extendType, shiftAmount);
        }
        if (idx == IDX_UNSCALED && bit21) {
            // Espaço atômico/LSE (mesmo bit de decodeLoadStoreSingle) — SIMD&FP não tem forma
            // atômica no hardware real (LSE é só GPR); recusar em vez de confundir com LDUR/STUR.
            throw unsupported(word, address);
        }
        if (bit21) {
            // idx=POST_INDEX/PRE_INDEX com bit21=1 (mesmo achado de B11.3 de decodeLoadStoreSingle,
            // aplicado por simetria): fora do espaço `@ldst_imm*` real (SIMD&FP não tem forma de
            // pointer authentication, então isso é sempre reservado neste lado) — G8: recusar.
            throw unsupported(word, address);
        }
        Ir64AddressingMode addressingMode = switch (idx) {
            case IDX_UNSCALED, IDX_REGISTER_OFFSET -> Ir64AddressingMode.OFFSET;
            case IDX_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case IDX_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        int imm9 = (word >>> SINGLE_IMM9_SHIFT) & (int) bitMask(SINGLE_IMM9_BITS);
        long immediate = signExtend(imm9, SINGLE_IMM9_BITS);
        return buildFpSingle(form, vt, rn, addressingMode, immediate, -1, null, 0);
    }

    /// Resultado de `size`+`opc` já resolvidos (`ARM DDI 0487 C4.1.5`, tabela por `size`/`opc` das
    /// formas `LDR`/`STR` `B`/`H`/`S`/`D`/`Q`) — compartilhado pelas 4 formas de endereçamento de
    /// {@link #decodeFpLoadStoreSingle}. Irmão de {@link SingleForm} (GPR), sem `signExtend`/`wide`
    /// (SIMD&FP não tem forma com sinal nem eixo `W`/`X`).
    private record FpSingleForm(Ir64FpMemSize size, boolean store) {
    }

    private FpSingleForm decodeFpSingleForm(int sizeField, int opcField, int word, long address) {
        // opc[1] (bit mais significativo de `opcField`) = 0 → tamanho vem de `size` (B/H/S/D);
        // opc[1] = 1 → forma `Q` (128 bits), que exige `size=00` (as outras 3 combinações de
        // `size` com `opc[1]=1` são reservadas). opc[0] = L (1=carga, 0=armazenamento) nas duas
        // famílias — confirmado contra `target/arm/tcg/a64.decode` real do QEMU antes de codificar.
        boolean opcHigh = (opcField & 0b10) != 0;
        boolean load = (opcField & 0b01) != 0;
        if (opcHigh) {
            if (sizeField != SIZE_BYTE) {
                throw unsupported(word, address); // size != 00 com opc[1]=1: reservado
            }
            return new FpSingleForm(Ir64FpMemSize.QUAD, !load);
        }
        Ir64FpMemSize size = switch (sizeField) {
            case SIZE_BYTE -> Ir64FpMemSize.BYTE;
            case SIZE_HALF -> Ir64FpMemSize.HALF;
            case SIZE_WORD -> Ir64FpMemSize.SINGLE;
            case SIZE_DOUBLEWORD -> Ir64FpMemSize.DOUBLE;
            default -> throw new IllegalStateException("unreachable");
        };
        return new FpSingleForm(size, !load);
    }

    private Ir64Op buildFpSingle(FpSingleForm form, int vt, int rn, Ir64AddressingMode addressingMode,
            long immediate, int rm, Ir64ExtendType extendType, int shiftAmount) {
        if (form.store) {
            return new Ir64Op.FpStore64(vt, rn, form.size, addressingMode, immediate, rm, extendType, shiftAmount);
        }
        return new Ir64Op.FpLoad64(vt, rn, form.size, addressingMode, immediate, rm, extendType, shiftAmount);
    }

    /// `LDP`/`STP` SIMD&FP (`ARM DDI 0487 C6.2.127`/`C6.2.338`, `V=1` — B8.13): MESMO layout de
    /// {@link #decodeLoadStorePair}, só a tabela `opc`→tamanho muda (`S`/`D`/`Q` em vez de `W`/`X`,
    /// sem forma com sinal — não existe `LDPSW` para SIMD&FP).
    private Ir64Op decodeFpLoadStorePair(int word, long address) {
        int opc = (word >>> PAIR_OPC_SHIFT) & PAIR_OPC_MASK;
        boolean load = ((word >>> PAIR_LOAD_BIT_SHIFT) & 1) != 0;
        Ir64FpMemSize size = switch (opc) {
            case 0b00 -> Ir64FpMemSize.SINGLE;
            case 0b01 -> Ir64FpMemSize.DOUBLE;
            case 0b10 -> Ir64FpMemSize.QUAD;
            default -> throw unsupported(word, address); // opc=11 reservado
        };
        int addrModeField = (word >>> PAIR_ADDR_MODE_SHIFT) & PAIR_ADDR_MODE_MASK;
        Ir64AddressingMode addressingMode = switch (addrModeField) {
            case PAIR_ADDR_MODE_NO_ALLOC_HINT, PAIR_ADDR_MODE_OFFSET -> Ir64AddressingMode.OFFSET;
            case PAIR_ADDR_MODE_POST_INDEX -> Ir64AddressingMode.POST_INDEX;
            case PAIR_ADDR_MODE_PRE_INDEX -> Ir64AddressingMode.PRE_INDEX;
            default -> throw new IllegalStateException("unreachable");
        };
        long imm7 = (word >>> PAIR_IMM7_SHIFT) & bitMask(PAIR_IMM7_BITS);
        long immediate = signExtend(imm7, PAIR_IMM7_BITS) * size.bytes();
        int vt2 = (word >>> PAIR_RT2_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.FpLoadStorePair(load, vt, vt2, rn, size, addressingMode, immediate);
    }

    /// `LDR (literal)` SIMD&FP (`ARM DDI 0487 C6.2.122`, `V=1` — B8.13): MESMO layout de
    /// {@link #decodeLoadLiteral}, só a tabela `opc`→tamanho muda (`S`/`D`/`Q`, `opc=11`
    /// reservado — não existe forma com sinal).
    private Ir64Op decodeFpLoadLiteral(int word, long address) {
        int opc = (word >>> LITERAL_OPC_SHIFT) & LITERAL_OPC_MASK;
        Ir64FpMemSize size = switch (opc) {
            case 0b00 -> Ir64FpMemSize.SINGLE;
            case 0b01 -> Ir64FpMemSize.DOUBLE;
            case 0b10 -> Ir64FpMemSize.QUAD;
            default -> throw unsupported(word, address); // opc=11 reservado
        };
        long imm19 = (word >>> LITERAL_IMM19_SHIFT) & bitMask(LITERAL_IMM19_BITS);
        long offset = signExtend(imm19, LITERAL_IMM19_BITS) * LITERAL_BYTES_PER_UNIT;
        int vt = word & REGISTER_FIELD_MASK;
        return new Ir64Op.FpLoadLiteral64(vt, address + offset, size);
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
        // subgroup == 0b11: Extract (EXTR, B8.2).
        return decodeExtract(word, address);
    }

    /// `EXTR` (B8.2) — MESMA posição de bit `N`(22) que {@link #decodeBitfield} (deve ser igual a
    /// `sf`); a forma de 32 bits não tem os 6 bits completos de `imm` (bit15 fixo em `0`).
    private Ir64Op decodeExtract(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int n = (word >>> BITFIELD_N_SHIFT) & 1;
        if (n != (wide ? 1 : 0)) {
            throw unsupported(word, address);
        }
        if (((word >>> EXTRACT_OP21_SHIFT) & 1) != 0) {
            throw unsupported(word, address);
        }
        if (!wide && (word & EXTRACT_NARROW_RESERVED_BIT) != 0) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int lsb = wide
                ? (word >>> EXTRACT_SHIFT_FIELD_SHIFT) & EXTRACT_IMM6_MASK
                : (word >>> EXTRACT_SHIFT_FIELD_SHIFT) & EXTRACT_IMM5_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Extract(rd, rn, rm, lsb, wide);
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
    /// B6.3.2 com `CSEL`/`CSINC`/`CSINV`/`CSNEG`, por B6.9 com `Logical (shifted register)`):
    /// `2-source`/`3-source` (B6.3.3) entram como `case`s adicionais aqui, adicionados por essa
    /// task subsequente.
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
        if (addSubGroup == LOGICAL_SHIFTED_REGISTER_GROUP_PATTERN) {
            return decodeLogicalShiftedRegister(word, address);
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
            // `opc2`(bits[30:29]) distingue "2 source" (`00`, SDIV/UDIV/LSLV/.../CRC32*) de
            // "1 source" (`10`, RBIT/REV16/REV32/REV64/CLZ/CLS/CNT) — MESMOS bits[28:21] fixos
            // para os dois subgrupos (B8.2, achado desta task: sem esta checagem, REV32/REV64/CLZ/
            // etc caíam por acaso no dispatch de SDIV/UDIV — ver "Bugs reais achados e corrigidos"
            // da task).
            int opc2 = (word >>> DP_SOURCE_OPC2_SHIFT) & DP_SOURCE_OPC2_MASK;
            if (opc2 == DP_SOURCE_OPC2_ONE_SOURCE) {
                return decodeDataProcessing1Source(word, address);
            }
            if (opc2 != DP_SOURCE_OPC2_TWO_SOURCE) {
                // `opc2`=`01`: SUBPS (`FEAT_MTE2`, B19.14) — a ÚNICA das 4 instruções MTE deste
                // subgrupo que mede `opc2=01` de verdade (achado real, ver comentário de
                // DP_SOURCE_OPC2_SUBPS); `opc2=11` é reservado.
                if (opc2 == DP_SOURCE_OPC2_SUBPS) {
                    return decodeSubtractPointer(word, address, true);
                }
                throw unsupported(word, address);
            }
            int divideOpcode = (word >>> DIVIDE_OPCODE_SHIFT) & DIVIDE_OPCODE_5BIT_MASK;
            if (divideOpcode == DIVIDE_OPCODE_PATTERN) {
                return decodeDivide(word);
            }
            int shiftOpcode4 = (word >>> SHIFT_VARIABLE_OPCODE_SHIFT) & SHIFT_VARIABLE_OPCODE_4BIT_MASK;
            if (shiftOpcode4 == SHIFT_VARIABLE_OPCODE_PATTERN) {
                return decodeShiftVariable(word);
            }
            // B19.6 bloco C: `PACGA` (`FEAT_PAuth`) — MESMO subgrupo "Data-processing (2 source)"
            // (opc2=00), opcode(15:10)=`0b001100` (bits[11:10]="00", diferente de SDIV/UDIV/LSLV/.../
            // que exigem bit10=1 ou já foram checados acima). Medido bit a bit contra corpus real
            // (`aarch64-none-elf-as`/`objdump`, devkitA64, `.arch armv8.3-a`) — o comentário antigo
            // desta função ("opc2=01/11: SUBP/SUBPS/IRG/GMI/PACGA") estava ERRADO: `PACGA` mede
            // `opc2=00` de verdade, não `01`/`11` (esses seguem MTE, fora de escopo).
            int pacgaOpcode6 = (word >>> PACGA_OPCODE_SHIFT) & PACGA_OPCODE_6BIT_MASK;
            if (pacgaOpcode6 == PACGA_OPCODE_PATTERN) {
                return decodePacga(word, address);
            }
            // B19.17: `CRC32*`/`CRC32C*` — `top4` reaproveita o MESMO campo `pacgaOpcode6` (bits
            // [15:10]) que `PACGA` já lê acima; os 4 bits altos (`>>>2`) escolhem o polinômio.
            int crc32Top4 = (pacgaOpcode6 >>> CRC32_TOP4_SHIFT) & CRC32_TOP4_MASK;
            if (crc32Top4 == CRC32_TOP4_PATTERN || crc32Top4 == CRC32C_TOP4_PATTERN) {
                return decodeCrc32(word, address, crc32Top4 == CRC32C_TOP4_PATTERN);
            }
            // B19.14: SUBP/IRG/GMI (`FEAT_MTE2`) — MESMO subgrupo "Data-processing (2 source)"
            // (opc2=00) de PACGA/CRC32* acima, campo de 6 bits reaproveitado (`mteRegisterOpcode`).
            int mteRegisterOpcode = (word >>> MTE_REGISTER_OPCODE_SHIFT) & MTE_REGISTER_OPCODE_MASK;
            if (mteRegisterOpcode == SUBP_OPCODE_PATTERN) {
                return decodeSubtractPointer(word, address, false);
            }
            if (mteRegisterOpcode == IRG_OPCODE_PATTERN) {
                return decodeInsertRandomTag(word, address);
            }
            if (mteRegisterOpcode == GMI_OPCODE_PATTERN) {
                return decodeTagMaskInsert(word, address);
            }
            // B19.21: SMAX/SMIN/UMAX/UMIN (`FEAT_CSSC`) — MESMO campo `mteRegisterOpcode` acima.
            Ir64MinMaxOp minMaxOp = switch (mteRegisterOpcode) {
                case MIN_MAX_OPCODE_SMAX -> Ir64MinMaxOp.SMAX;
                case MIN_MAX_OPCODE_SMIN -> Ir64MinMaxOp.SMIN;
                case MIN_MAX_OPCODE_UMAX -> Ir64MinMaxOp.UMAX;
                case MIN_MAX_OPCODE_UMIN -> Ir64MinMaxOp.UMIN;
                default -> null;
            };
            if (minMaxOp != null) {
                if (!architecture.has(Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION)) {
                    throw unsupported(word, address);
                }
                boolean minMaxWide = ((word >>> SF_SHIFT) & 1) != 0;
                int minMaxRm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
                int minMaxRn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
                int minMaxRd = word & REGISTER_FIELD_MASK;
                return new Ir64Op.MinMaxGeneral(minMaxOp, minMaxRd, minMaxRn, minMaxRm, minMaxWide);
            }
            throw unsupported(word, address);
        }
        if (muldivFixed8 == ADD_SUB_CARRY_FIXED_PATTERN) {
            return decodeAddSubtractCarryOrFlags(word, address);
        }
        if (muldivFixed8 == MULDIV_LONG_FIXED_SIGNED || muldivFixed8 == MULDIV_LONG_FIXED_UNSIGNED) {
            return decodeMultiplyAccumulateLong(word, muldivFixed8 == MULDIV_LONG_FIXED_SIGNED);
        }
        if (muldivFixed8 == MULH_FIXED_SIGNED || muldivFixed8 == MULH_FIXED_UNSIGNED) {
            return decodeMultiplyHigh(word, address, muldivFixed8 == MULH_FIXED_SIGNED);
        }
        throw unsupported(word, address);
    }

    /// `LSLV`/`LSRV`/`ASRV`/`RORV` (B6.11) — deslocamento variável, quantidade tomada de `Rm`
    /// (não um imediato do encoding, ao contrário de {@link #decodeLogicalShiftedRegister}).
    private Ir64Op decodeShiftVariable(int word) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int shiftTypeOrdinal = (word >>> SHIFT_VARIABLE_TYPE_SHIFT) & SHIFT_VARIABLE_TYPE_2BIT_MASK;
        Ir64LogicalShiftType shiftType = Ir64LogicalShiftType.values()[shiftTypeOrdinal];
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.ShiftVariable(rd, rn, rm, shiftType, wide);
    }

    /// `PACGA Xd, Xn, Xm` (B19.6 bloco C, `FEAT_PAuth`) — este emulador não modela autenticação de
    /// ponteiro de verdade (ver javadoc de {@link Ir64Op.PointerAuthGeneric}); gateado do mesmo modo
    /// que os outros primeiros gates reais de A64 (B11.4 em diante).
    private Ir64Op decodePacga(int word, long address) {
        if (!architecture.has(Aarch64Feature.POINTER_AUTHENTICATION)) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.PointerAuthGeneric(rd, rn, rm);
    }

    /// `SUBP`/`SUBPS Xd, Xn, Xm` (`FEAT_MTE2`, B19.14) — sempre 64 bits (não existe forma de 32
    /// bits, `sf` fixo em `1` no encoding real, não checado aqui pois a classe já garante
    /// `bit31`=1 — ver `MULDIV_FIXED_SHIFT`).
    private Ir64Op decodeSubtractPointer(int word, long address, boolean setFlags) {
        if (!architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.SubtractPointer(setFlags, rd, rn, rm);
    }

    /// `IRG Xd, Xn, Xm` (`FEAT_MTE2`, B19.14) — ver
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#insertRandomTag} para o algoritmo
    /// determinístico.
    private Ir64Op decodeInsertRandomTag(int word, long address) {
        if (!architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.InsertRandomTag(rd, rn, rm);
    }

    /// `GMI Xd, Xn, Xm` (`FEAT_MTE2`, B19.14).
    private Ir64Op decodeTagMaskInsert(int word, long address) {
        if (!architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.TagMaskInsert(rd, rn, rm);
    }

    /// `CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}` (B19.17, `FEAT_CRC32`) — `size`(bits[11:10]) escolhe a
    /// largura do DADO (`Rm`); a forma `X` (`size=0b11`) é a ÚNICA que exige `sf=1` (lê `Rm` como
    /// `X`) — qualquer outra combinação `sf`/`size` é encoding reservado (G8: recusar, não
    /// confundir com uma forma válida).
    private Ir64Op decodeCrc32(int word, long address, boolean castagnoli) {
        if (!architecture.has(Aarch64Feature.CRC32)) {
            throw unsupported(word, address);
        }
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int size = (word >>> CRC32_SIZE_SHIFT) & CRC32_SIZE_MASK;
        int dataWidthBits = switch (size) {
            case CRC32_SIZE_BYTE -> Byte.SIZE;
            case CRC32_SIZE_HALFWORD -> Short.SIZE;
            case CRC32_SIZE_WORD -> Integer.SIZE;
            case CRC32_SIZE_DOUBLEWORD -> Long.SIZE;
            default -> throw new AssertionError("size de 2 bits só tem 4 valores possíveis");
        };
        boolean doublewordForm = size == CRC32_SIZE_DOUBLEWORD;
        if (wide != doublewordForm) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Crc32(rd, rn, rm, dataWidthBits, castagnoli);
    }

    /// `RBIT`/`REV16`/`REV`(`W`)/`REV32`(`X`)/`REV64`/`CLZ`/`CLS`/`CNT` (B8.2, "Data-processing
    /// (1 source)" — só chega aqui depois do gate de `opc2` em {@link #decodeDataProcessingRegister}).
    private Ir64Op decodeDataProcessing1Source(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int opcode = (word >>> ONE_SOURCE_OPCODE_SHIFT) & ONE_SOURCE_OPCODE_MASK;
        // `opcode2`(bits[20:16], mesma posição de `Rm`/`ADDSUB_REGISTER_RM_SHIFT`) distingue os DOIS
        // subgrupos reais de "Data-processing (1 source)": `00000`=RBIT/REV*/CLZ/CLS/CNT/ABS/CTZ
        // (já implementados) e `00001`=PAC/AUT/XPAC de propósito geral (B19.15, `FEAT_PAuth`). B19.21
        // já tinha descoberto a checagem para `CTZ` isoladamente (colisão de opcode de 6 bits com
        // `AUTDA`, ver `docs/COBERTURA-ISA.md`); esta task generaliza o gate para TODO o subgrupo —
        // sem ele, `ABS`(opcode=`0b001000`) também colidiria por acaso com `PACIZA`(`opcode2=00001`,
        // MESMO opcode de 6 bits), um bug latente da mesma família nunca antes exercitado (G8).
        int oneSourceOpcode2 = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        if (oneSourceOpcode2 == ONE_SOURCE_OPCODE2_PAUTH) {
            if (!architecture.has(Aarch64Feature.POINTER_AUTHENTICATION)) {
                throw unsupported(word, address);
            }
            return decodePointerAuthInPlace(word, address, wide, opcode);
        }
        if (oneSourceOpcode2 != ONE_SOURCE_OPCODE2_STANDARD) {
            throw unsupported(word, address); // reservado
        }
        // B19.6 bloco D: `ABS Xd, Xn` (`FEAT_CSSC`) — MESMO subgrupo "Data-processing (1 source)",
        // opcode=`0b001000`. Gateado ANTES do `switch` de `Ir64OneSourceOp` (record próprio, ver
        // {@link Ir64Op.AbsGeneral} — não é o `ABS` vetorial AdvSIMD, já `✅` desde B8.7).
        if (opcode == ONE_SOURCE_OPCODE_ABS) {
            if (!architecture.has(Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION)) {
                throw unsupported(word, address);
            }
            int absRn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int absRd = word & REGISTER_FIELD_MASK;
            return new Ir64Op.AbsGeneral(absRd, absRn, wide);
        }
        // B19.21: `CTZ Xd, Xn` (`FEAT_CSSC`) — MESMO gate/subgrupo de `ABS` acima. `opcode2==0` já
        // garantido pelo gate no topo desta função (B19.15) — a checagem isolada de `Rm==00000` que
        // existia aqui antes virou redundante e foi removida.
        if (opcode == ONE_SOURCE_OPCODE_CTZ) {
            if (!architecture.has(Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION)) {
                throw unsupported(word, address);
            }
            int ctzRn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int ctzRd = word & REGISTER_FIELD_MASK;
            return new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CTZ, ctzRd, ctzRn, wide);
        }
        Ir64OneSourceOp op = switch (opcode) {
            case ONE_SOURCE_OPCODE_RBIT -> Ir64OneSourceOp.RBIT;
            case ONE_SOURCE_OPCODE_REV16 -> Ir64OneSourceOp.REV16;
            case ONE_SOURCE_OPCODE_REV32 -> Ir64OneSourceOp.REV32;
            case ONE_SOURCE_OPCODE_REV64 -> {
                if (!wide) {
                    // REV64 só existe com sf=1 — sf=0 com este opcode é reservado.
                    throw unsupported(word, address);
                }
                yield Ir64OneSourceOp.REV64;
            }
            case ONE_SOURCE_OPCODE_CLZ -> Ir64OneSourceOp.CLZ;
            case ONE_SOURCE_OPCODE_CLS -> Ir64OneSourceOp.CLS;
            case ONE_SOURCE_OPCODE_CNT -> Ir64OneSourceOp.CNT;
            default -> throw unsupported(word, address);
        };
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.DataProcessing1Source(op, rd, rn, wide);
    }

    /// `PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/`AUTDA`/`AUTDB`/`XPACI`/`XPACD` (B19.15,
    /// `FEAT_PAuth`) — já confirmado `sf`/`opcode2`=`00001` pelo chamador. `opcode`(bits[15:10])
    /// tem dois sub-espaços: bits[5:4]=`00` (as 8 formas "assinar"/"autenticar" de propósito geral,
    /// `bits[2:0]` escolhem o mnemônico, bit[3] escolhe a variante "Z"/modificador-zero — não
    /// diferenciada aqui, rota (b) da task ignora o modificador de qualquer forma) e bits[5:4]=`01`
    /// (`XPACI`=`010000`/`XPACD`=`010001`, sem `Rn`). Auditoria de vizinhos da task: as 8 formas de
    /// propósito geral NÃO tinham decoder algum antes desta task (só `AUTDA`/`XPACI`/`XPACD` eram
    /// exigidas pelas 10 linhas residuais) — incluídas aqui por coesão de MESMO bloco de encoding
    /// `@pacaut`, decisão registrada no `## Resultado` da task.
    private Ir64Op decodePointerAuthInPlace(int word, long address, boolean wide, int opcode) {
        if (!wide) {
            throw unsupported(word, address); // `sf=0` é reservado neste subgrupo (só existe `X`)
        }
        int rd = word & REGISTER_FIELD_MASK;
        int top2 = (opcode >>> 4) & 0b11;
        if (top2 == PAUTH_IN_PLACE_TOP2_GENERAL) {
            int low3 = opcode & 0b111;
            Ir64PointerAuthOp op = switch (low3) {
                case 0 -> Ir64PointerAuthOp.PACIA;
                case 1 -> Ir64PointerAuthOp.PACIB;
                case 2 -> Ir64PointerAuthOp.PACDA;
                case 3 -> Ir64PointerAuthOp.PACDB;
                case 4 -> Ir64PointerAuthOp.AUTIA;
                case 5 -> Ir64PointerAuthOp.AUTIB;
                case 6 -> Ir64PointerAuthOp.AUTDA;
                case 7 -> Ir64PointerAuthOp.AUTDB;
                default -> throw new AssertionError("low3 de 3 bits só tem 8 valores possíveis");
            };
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            return new Ir64Op.PointerAuthInPlace(op, rd, rn);
        }
        if (top2 == PAUTH_IN_PLACE_TOP2_XPAC) {
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            if (rn != PAUTH_IN_PLACE_XPAC_RN_FIXED) {
                throw unsupported(word, address); // reservado
            }
            Ir64PointerAuthOp op = switch (opcode & 0b1111) {
                case 0b0000 -> Ir64PointerAuthOp.XPACI;
                case 0b0001 -> Ir64PointerAuthOp.XPACD;
                default -> throw unsupported(word, address); // reservado (ex. XPACLRI vive noutro espaço)
            };
            return new Ir64Op.PointerAuthInPlace(op, rd, -1);
        }
        throw unsupported(word, address); // reservado
    }

    /// `ADC`/`ADCS`/`SBC`/`SBCS` + `RMIF` + `SETF8`/`SETF16` (B8.2) — 3 subgrupos que
    /// compartilham o MESMO campo de 8 bits fixos em bits[28:21] ("11010000"), distinguidos por
    /// `opcode2`(bits[15:10]) e, para `RMIF`/`SETF`, por `sf`/`opc` fixos adicionais (ver Fatos de
    /// referência da task).
    private Ir64Op decodeAddSubtractCarryOrFlags(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean subtract = ((word >>> ADD_SUB_OP_SHIFT) & 1) != 0;
        boolean setFlags = ((word >>> SET_FLAGS_SHIFT) & 1) != 0;
        int opcode2 = (word >>> ADD_SUB_CARRY_OPCODE2_SHIFT) & ADD_SUB_CARRY_OPCODE2_MASK;
        if (opcode2 == ADD_SUB_CARRY_OPCODE2_ADC_SBC) {
            int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int rd = word & REGISTER_FIELD_MASK;
            return new Ir64Op.AluWithCarry(subtract, rd, rn, rm, wide, setFlags);
        }
        boolean rmifFixedTail = ((word >>> RMIF_FIXED_TAIL_SHIFT) & RMIF_FIXED_TAIL_MASK) == RMIF_FIXED_TAIL_PATTERN;
        boolean rmifBit4Clear = (word & RMIF_BIT4_MASK) == 0;
        if (wide && !subtract && setFlags && rmifFixedTail && rmifBit4Clear) {
            // B11.7: gate real — ARMv8.4-A introduziu FEAT_FlagM, um Cortex-A53 (ARMv8.0-A) não
            // tem este encoding; sem a feature, cai em unsupported (G8) em vez de decodificar.
            if (!architecture.has(Aarch64Feature.FLAG_MANIPULATION)) {
                throw unsupported(word, address);
            }
            int shift = (word >>> RMIF_IMM6_SHIFT) & RMIF_IMM6_MASK;
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int mask = word & RMIF_MASK_FIELD_MASK;
            return new Ir64Op.RotateIntoFlags(rn, shift, mask);
        }
        boolean setfRmFieldZero = ((word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK) == 0;
        boolean setfLow5Fixed = (word & SETF_LOW5_MASK) == SETF_LOW5_PATTERN;
        if (!wide && !subtract && setFlags && setfRmFieldZero && setfLow5Fixed) {
            // B11.7: mesmo gate de FEAT_FlagM que RMIF acima — SETF8/SETF16 são a outra metade
            // da mesma extensão (ARMv8.4-A).
            if (!architecture.has(Aarch64Feature.FLAG_MANIPULATION)) {
                throw unsupported(word, address);
            }
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            if (opcode2 == SETF_OPCODE2_SETF8) {
                return new Ir64Op.EvaluateIntoFlags(rn, EVALUATE_FLAGS_SIZE_8);
            }
            if (opcode2 == SETF_OPCODE2_SETF16) {
                return new Ir64Op.EvaluateIntoFlags(rn, EVALUATE_FLAGS_SIZE_16);
            }
        }
        throw unsupported(word, address);
    }

    /// `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` (B8.2) — `sf` é fixo em `1` no encoding (só existe a
    /// forma `X`), por isso {@link Ir64Op.MultiplyAccumulateLong} não carrega `wide`.
    private Ir64Op decodeMultiplyAccumulateLong(int word, boolean signed) {
        boolean subtract = ((word >>> MADD_MSUB_O0_SHIFT) & 1) != 0;
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int ra = (word >>> MADD_MSUB_RA_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.MultiplyAccumulateLong(subtract, signed, rd, rn, rm, ra);
    }

    /// `SMULH`/`UMULH` (B8.2) — `Ra` fixo em `XZR` (não é campo real, ver Javadoc de
    /// {@link Ir64Op.MultiplyHigh}); recusa qualquer combinação que viole isso (G8: em vez de
    /// silenciosamente ignorar `Ra`, confere o valor fixo).
    private Ir64Op decodeMultiplyHigh(int word, long address, boolean signed) {
        int ra = (word >>> MADD_MSUB_RA_SHIFT) & REGISTER_FIELD_MASK;
        if (ra != MULH_RA_FIXED) {
            throw unsupported(word, address);
        }
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.MultiplyHigh(signed, rd, rn, rm);
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

    /// `AND`/`ORR`/`EOR`/`ANDS` (`n=0`) e `BIC`/`ORN`/`EON`/`BICS` (`n=1`), forma "shifted
    /// register" (B6.9). Reaproveita o mesmo mapeamento de `opc` já usado por
    /// {@link #decodeLogicalImmediate} (`LOGICAL_IMM_OPC_*`: `00`=`AND`,`01`=`ORR`,`10`=`EOR`,
    /// `11`=`ANDS`) e os mesmos deslocamentos de bit de {@link #decodeAddSubShiftedRegister}
    /// (`st`/`sa`/`Rm`/`Rn`/`Rd` caem nas MESMAS posições — só o padrão de grupo em bits[28:24]
    /// muda). Ao contrário daquele método, `st=11` (`ROR`) é VÁLIDO aqui (Fatos de referência #2
    /// da task — não portar a checagem de reservado). `MOV`/`MVN` (registrador) não têm `case`
    /// próprio (D3 da task) — o caminho geral com `Rn=31` já produz o resultado correto.
    private Ir64Op decodeLogicalShiftedRegister(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int shiftAmount = (word >>> ADDSUB_SHIFTED_AMOUNT_SHIFT) & ADDSUB_SHIFTED_AMOUNT_MASK;
        if (!wide && (shiftAmount & ADDSUB_SHIFTED_AMOUNT_BIT5) != 0) {
            // sf=0 (W) só aceita quantidade 0-31 — bit5 setado significa >= 32, UNDEFINED.
            throw unsupported(word, address);
        }
        int shiftTypeField = (word >>> ADDSUB_SHIFTED_TYPE_SHIFT) & ADDSUB_SHIFTED_TYPE_MASK;
        Ir64LogicalShiftType shiftType = switch (shiftTypeField) {
            case ADDSUB_SHIFTED_TYPE_LSL -> Ir64LogicalShiftType.LSL;
            case ADDSUB_SHIFTED_TYPE_LSR -> Ir64LogicalShiftType.LSR;
            case ADDSUB_SHIFTED_TYPE_ASR -> Ir64LogicalShiftType.ASR;
            case ADDSUB_SHIFTED_TYPE_RESERVED_ROR -> Ir64LogicalShiftType.ROR;
            default -> throw new IllegalStateException("unreachable");
        };
        boolean invert = ((word >>> LOGICAL_SHIFTED_REGISTER_INVERT_BIT_SHIFT) & 1) != 0;
        int opc = (word >>> LOGICAL_IMM_OPC_SHIFT) & LOGICAL_IMM_OPC_MASK;
        Ir64AluOp opcode = switch (opc) {
            case LOGICAL_IMM_OPC_AND, LOGICAL_IMM_OPC_ANDS -> Ir64AluOp.AND;
            case LOGICAL_IMM_OPC_ORR -> Ir64AluOp.ORR;
            case LOGICAL_IMM_OPC_EOR -> Ir64AluOp.EOR;
            default -> throw new IllegalStateException("unreachable");
        };
        boolean setFlags = opc == LOGICAL_IMM_OPC_ANDS;
        int rm = (word >>> ADDSUB_REGISTER_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.LogicalShiftedRegister(
                opcode, rd, rn, rm, shiftType, shiftAmount, invert, wide, setFlags);
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
    /// (`bit26=1`, D1 da task B6.5.3): o subconjunto ESCALAR de B6.5.2
    /// (`FADD`/`FSUB`/`FMUL`/`FDIV`/`FNEG`/`FABS`/`FMOV` registrador/imediato/`FCMP`/`FCMPE`/
    /// `FCVT` F32↔F64), estendido pela B8.4 com o resto de "2 source" (`FMAX`/`FMIN`/`FMAXNM`/
    /// `FMINNM`/`FNMUL`), `FSQRT` ("1 source") e "3 source" (`FMADD`/`FMSUB`/`FNMADD`/`FNMSUB`,
    /// prefixo próprio, ver abaixo), e pela B8.5 com `FCSEL`/`FCCMP`, `FRINTx` ("1 source"),
    /// conversão FP↔ponto-fixo/inteiro (registrador geral) e `FMOV` registrador-geral↔FP.
    /// Advanced SIMD vetorial continua fora, reconhecida só pela AUSÊNCIA de qualquer um dos
    /// padrões fixos abaixo (nunca por um `case` próprio que tentaria decodificá-la).
    private Ir64Op decodeDataProcessingScalarFpSimd(int word, long address) {
        // B8.4: "Floating-point data-processing (3 source)" checado ANTES do resto — usa um
        // prefixo de 8 bits próprio (não os 5 bits de SCALAR_FP_FIXED_PREFIX abaixo), porque
        // bits[28:24] sozinho colide com "Advanced SIMD scalar x indexed element"/"scalar shift by
        // immediate" (ver comentário da constante). G8: melhor um `if` a mais aqui do que um
        // encoding SIMD escalar sendo misdecodificado como FMADD/FMSUB/FNMADD/FNMSUB.
        int threeSourcePrefix =
                (word >>> FP_THREE_SOURCE_FIXED_PREFIX_SHIFT) & FP_THREE_SOURCE_FIXED_PREFIX_MASK;
        if (threeSourcePrefix == FP_THREE_SOURCE_FIXED_PREFIX_PATTERN) {
            return decodeFpThreeSource(word, address);
        }
        // B11.12: `EOR3`/`BCAX`/`RAX1`/`XAR` (`FEAT_SHA3`) — prefixo de 8 bits próprio, checado
        // ANTES do resto (ver o comentário de `CRYPTO_SHA3_PREFIX_PATTERN`: sem isto, o encoding
        // real cairia silenciosamente em `decodeAdvancedSimdInteger`, G8).
        int crypto3Prefix = (word >>> CRYPTO_SHA3_PREFIX_SHIFT) & CRYPTO_SHA3_PREFIX_MASK;
        if (crypto3Prefix == CRYPTO_SHA3_PREFIX_PATTERN) {
            return decodeCryptoSha3(word, address);
        }
        int fixedPrefix = (word >>> SCALAR_FP_FIXED_PREFIX_SHIFT) & SCALAR_FP_FIXED_PREFIX_MASK;
        if (fixedPrefix != SCALAR_FP_FIXED_PREFIX_PATTERN) {
            // B8.7: Advanced SIMD vetorial (prefixo(28:24)="01110") ou escalar D-only inteiro
            // (prefixo(28:24)="11110" com bit30=1, mesmo truque de prefixo de
            // "Advanced SIMD scalar two-register miscellaneous" citado acima) — aritmética/
            // comparação inteira. Bits21=0 dentro desses prefixos (`AdvSIMD modified immediate`/
            // `shift by immediate`, formas com registrador indexado, FP vetorial) ficam fora,
            // reconhecidos só pela AUSÊNCIA de qualquer combinação da tabela (G8).
            return decodeAdvancedSimdInteger(word, address);
        }
        boolean bit21Set = ((word >>> SCALAR_FP_BIT21_SHIFT) & 1) != 0;
        if (!bit21Set) {
            // B8.5: "Conversion between floating-point and fixed-point (general register)" —
            // ÚNICO subgrupo deste prefixo com bit21=0 (todos os outros abaixo têm bit21=1).
            return decodeFpFixedPointConvert(word, address);
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
        // B8.5: `FCSEL`(bits[11:10]="11")/`FCCMP`(bits[11:10]="01") — CONFERIDO que bit10=1 nunca
        // ocorre em nenhum dos 4 padrões já checados acima (compare/1-source exigem bits[14:10]
        // fixo terminando em "00"; 2-source exige bits[11:10]="10").
        int selectCompareFixed = (word >>> FP_SELECT_COMPARE_FIXED_SHIFT) & FP_SELECT_COMPARE_FIXED_MASK;
        if (selectCompareFixed == FP_CSEL_FIXED_PATTERN) {
            return decodeFpConditionalSelect(word, address);
        }
        if (selectCompareFixed == FP_CCMP_FIXED_PATTERN) {
            return decodeFpConditionalCompare(word, address);
        }
        // B8.5: "Conversion between floating-point and integer (general register)" e `FMOV`
        // registrador-geral↔FP — MESMO sufixo bits[15:10]="000000", discriminados só pelo opcode.
        int intConvertSuffix = (word >>> FP_INT_CONVERT_SUFFIX_SHIFT) & FP_INT_CONVERT_SUFFIX_MASK;
        if (intConvertSuffix == FP_INT_CONVERT_SUFFIX_PATTERN) {
            return decodeFpIntegerConvertOrGeneralRegisterMove(word, address);
        }
        throw unsupported(word, address);
    }

    /// `EOR3`/`BCAX`/`RAX1`/`XAR` (`FEAT_SHA3`, ARMv8.2-A, B11.12) e, desde a B19.10, os vizinhos que
    /// moram no MESMO prefixo de 8 bits (`FEAT_SHA512`/`FEAT_SM3`/`FEAT_SM4`) — ver o comentário de
    /// {@link #CRYPTO_SHA3_PREFIX_PATTERN}. Cada família tem seu PRÓPRIO gate (G3/G8: sem a feature
    /// correta, `unsupported` — nunca uma checagem em bloco, ver o Aceite da B19.10 sobre
    /// independência dos gates).
    private Ir64Op decodeCryptoSha3(int word, long address) {
        int op0 = (word >>> CRYPTO_SHA3_OP0_SHIFT) & CRYPTO_SHA3_OP0_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rm = (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        return switch (op0) {
            case CRYPTO_SHA3_OP0_EOR3, CRYPTO_SHA3_OP0_BCAX -> {
                if (!architecture.has(Aarch64Feature.SHA3)) {
                    throw unsupported(word, address);
                }
                int bit15_14 = (word >>> CRYPTO_SHA3_FOUR_REG_BIT15_14_SHIFT) & CRYPTO_SHA3_FOUR_REG_BIT15_14_MASK;
                if (bit15_14 != 0) {
                    throw unsupported(word, address);
                }
                int ra = (word >>> CRYPTO_SHA3_RA_SHIFT) & CRYPTO_SHA3_RA_MASK;
                Ir64CryptoSha3Op op = op0 == CRYPTO_SHA3_OP0_EOR3 ? Ir64CryptoSha3Op.EOR3 : Ir64CryptoSha3Op.BCAX;
                yield new Ir64Op.CryptoSha3FourRegister(op, rd, rn, rm, ra);
            }
            // B19.10: `SM3SS1` (4 registradores, MESMO layout de `EOR3`/`BCAX` — `Ra` de 4 bits) e
            // `SM3TT1A/1B/2A/2B` (layout próprio) convivem em `op0=0b010`, discriminados por
            // bits[15:14] — nenhum dos dois grupos existia antes da B19.10 (espaço inteiro `null`
            // no `default` do switch antigo, aqui a primeira vez que é reivindicado).
            case CRYPTO_SHA3_OP0_SM3_MIX -> {
                int bit15_14 = (word >>> CRYPTO_SHA3_FOUR_REG_BIT15_14_SHIFT) & CRYPTO_SHA3_FOUR_REG_BIT15_14_MASK;
                if (bit15_14 == CRYPTO_SM3_MIX_BIT15_14_SM3SS1) {
                    if (!architecture.has(Aarch64Feature.SM3)) {
                        throw unsupported(word, address);
                    }
                    int ra = (word >>> CRYPTO_SHA3_RA_SHIFT) & CRYPTO_SHA3_RA_MASK;
                    yield new Ir64Op.CryptoSm3FourRegister(rd, rn, rm, ra);
                }
                if (bit15_14 == CRYPTO_SM3_MIX_BIT15_14_SM3TT) {
                    if (!architecture.has(Aarch64Feature.SM3)) {
                        throw unsupported(word, address);
                    }
                    int ttOp = (word >>> CRYPTO_SM3TT_OP_SHIFT) & CRYPTO_SM3TT_OP_MASK;
                    int imm2 = (word >>> CRYPTO_SM3TT_IMM2_SHIFT) & CRYPTO_SM3TT_IMM2_MASK;
                    Ir64CryptoSm3TtOp op = switch (ttOp) {
                        case 0 -> Ir64CryptoSm3TtOp.TT1A;
                        case 1 -> Ir64CryptoSm3TtOp.TT1B;
                        case 2 -> Ir64CryptoSm3TtOp.TT2A;
                        default -> Ir64CryptoSm3TtOp.TT2B;
                    };
                    yield new Ir64Op.CryptoSm3ThreeRegisterImm2(op, rd, rn, rm, imm2);
                }
                throw unsupported(word, address);
            }
            // B19.10: `op0=0b011` era só `RAX1` (`bits[15:10]="100011"`) — os outros 63 valores do
            // mesmo campo de 6 bits caíam em `unsupported` porque nada mais existia. Agora 6
            // vizinhos reais (SHA-512/SM3/SM4) usam o MESMO campo, cada um com feature própria.
            case CRYPTO_SHA3_OP0_RAX1 -> {
                int opcode6 = (word >>> CRYPTO_THREE_REGISTER_OPCODE6_SHIFT) & CRYPTO_THREE_REGISTER_OPCODE6_MASK;
                if (opcode6 == CRYPTO_SHA3_RAX1_BIT15_10_PATTERN) {
                    if (!architecture.has(Aarch64Feature.SHA3)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.CryptoSha3TwoSourceRotate(
                            Ir64CryptoSha3Op.RAX1, rd, rn, rm, CRYPTO_SHA3_RAX1_UNUSED_ROTATE_AMOUNT);
                }
                Ir64CryptoSha512Op sha512Op = switch (opcode6) {
                    case CRYPTO_OPCODE6_SHA512H -> Ir64CryptoSha512Op.SHA512H;
                    case CRYPTO_OPCODE6_SHA512H2 -> Ir64CryptoSha512Op.SHA512H2;
                    case CRYPTO_OPCODE6_SHA512SU1 -> Ir64CryptoSha512Op.SHA512SU1;
                    default -> null;
                };
                if (sha512Op != null) {
                    if (!architecture.has(Aarch64Feature.SHA512)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.CryptoSha512ThreeRegister(sha512Op, rd, rn, rm);
                }
                Ir64CryptoSm3Op sm3Op = switch (opcode6) {
                    case CRYPTO_OPCODE6_SM3PARTW1 -> Ir64CryptoSm3Op.PARTW1;
                    case CRYPTO_OPCODE6_SM3PARTW2 -> Ir64CryptoSm3Op.PARTW2;
                    default -> null;
                };
                if (sm3Op != null) {
                    if (!architecture.has(Aarch64Feature.SM3)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.CryptoSm3ThreeRegister(sm3Op, rd, rn, rm);
                }
                if (opcode6 == CRYPTO_OPCODE6_SM4EKEY) {
                    if (!architecture.has(Aarch64Feature.SM4)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.CryptoSm4KeyUpdate(rd, rn, rm);
                }
                throw unsupported(word, address);
            }
            case CRYPTO_SHA3_OP0_XAR -> {
                if (!architecture.has(Aarch64Feature.SHA3)) {
                    throw unsupported(word, address);
                }
                int imm6 = (word >>> CRYPTO_SHA3_XAR_IMM6_SHIFT) & CRYPTO_SHA3_XAR_IMM6_MASK;
                yield new Ir64Op.CryptoSha3TwoSourceRotate(Ir64CryptoSha3Op.XAR, rd, rn, rm, imm6);
            }
            // B19.10: `SHA512SU0`/`SM4E` — forma de 2 registradores, `Rm`(bits[20:16]) fixo em zero.
            case CRYPTO_SHA3_OP0_TWO_REGISTER -> {
                int rmFixed = (word >>> CRYPTO_TWO_REGISTER_RM_FIXED_SHIFT) & CRYPTO_TWO_REGISTER_RM_FIXED_MASK;
                if (rmFixed != CRYPTO_TWO_REGISTER_RM_FIXED_PATTERN) {
                    throw unsupported(word, address);
                }
                int opcode6 = (word >>> CRYPTO_TWO_REGISTER_OPCODE6_SHIFT) & CRYPTO_TWO_REGISTER_OPCODE6_MASK;
                if (opcode6 == CRYPTO_OPCODE6_SHA512SU0) {
                    if (!architecture.has(Aarch64Feature.SHA512)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.CryptoSha512TwoRegister(rd, rn);
                }
                if (opcode6 == CRYPTO_OPCODE6_SM4E) {
                    if (!architecture.has(Aarch64Feature.SM4)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.CryptoSm4Encrypt(rd, rn);
                }
                throw unsupported(word, address);
            }
            default -> throw unsupported(word, address);
        };
    }

    /// Sub-dispatch de "AdvSIMD inteiro — aritmética e comparação" (B8.7): entra já sabendo que
    /// `bit26=1` e que o prefixo escalar-FP (D1 acima) NÃO bateu. Distingue vetorial (prefixo
    /// bits[28:24]="01110", `Q`=bit30 real) de escalar D-only (prefixo="11110"+bit30=1, mesmo
    /// truque de {@link #decodeDataProcessingScalarFpSimd}); dentro de cada um, `bit21=1` (senão
    /// "AdvSIMD modified immediate"/outras formas fora de escopo) e depois `bit10` separam
    /// "three same"/"three same pairwise" (`1`) de "three different"/"across lanes"/"two-register
    /// miscellaneous" (`0`, sub-roteado por `Rm`, ver as constantes `ADVSIMD_INT_RM_*`).
    private Ir64Op decodeAdvancedSimdInteger(int word, long address) {
        int prefix = (word >>> ADVSIMD_INT_PREFIX_SHIFT) & ADVSIMD_INT_PREFIX_MASK;
        // B8.8: "Advanced SIMD shift by immediate" tem prefixo PRÓPRIO (bits[28:24], um bit a mais
        // que o das tabelas acima: `01111` vetorial/`11111` escalar, contra `01110`/`11110` das
        // demais) — checado ANTES do resto porque usa um layout de campos totalmente diferente
        // (`immh:immb` em vez de `size`+`Rm`).
        boolean shiftPrefixVector = prefix == ADVSIMD_SHIFT_PREFIX_VECTOR_PATTERN;
        boolean shiftPrefixScalar = prefix == ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN
                && ((word >>> ADVSIMD_INT_SCALAR_BIT30_SHIFT) & 1) != 0;
        if (shiftPrefixVector || shiftPrefixScalar) {
            // B8.19: MESMO prefixo de "shift by immediate", discriminado por bit10 (`1`=shift,
            // `0`=indexed-element) — ver o comentário de {@link #ADVSIMD_INDEXED_OPCODE_SHIFT}.
            if (((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0) {
                return decodeAdvancedSimdShiftByImmediate(word, address);
            }
            return decodeAdvancedSimdIndexedElement(word, address, shiftPrefixScalar);
        }
        boolean scalar;
        if (prefix == ADVSIMD_INT_PREFIX_VECTOR_PATTERN) {
            scalar = false;
        } else if (prefix == ADVSIMD_INT_PREFIX_SCALAR_PATTERN
                && ((word >>> ADVSIMD_INT_SCALAR_BIT30_SHIFT) & 1) != 0) {
            scalar = true;
        } else {
            throw unsupported(word, address);
        }
        boolean q = !scalar && ((word >>> ADVSIMD_INT_Q_SHIFT) & 1) != 0;
        if (((word >>> ADVSIMD_INT_BIT21_SHIFT) & 1) == 0) {
            // B11.4 (`FEAT_RDM`, primeiro gate real de feature A64): `SQRDMLAH`/`SQRDMLSH`
            // vetorial/escalar não-indexado também vivem neste espaço `bit21=0` (ver
            // {@link #ADVSIMD_RDM_OPCODE_SQRDMLAH}) — checados ANTES do resto (EXT/permute/TBL/
            // copy/SHA abaixo) porque, sem a feature, o encoding precisa continuar caindo no
            // `unsupported` de sempre (G3), e `decodeAdvancedSimdCopy` já devolve `null` para esses
            // bits mesmo com a feature ausente (sem colisão), então a ordem aqui só importa para
            // não fazer trabalho à toa quando a feature ESTÁ presente.
            if (architecture.has(Aarch64Feature.RDM)) {
                Ir64Op rdmOp = decodeAdvancedSimdRoundingDoublingMultiplyAccumulate(word, scalar, q);
                if (rdmOp != null) {
                    return rdmOp;
                }
            }
            // B19.5.5 (`FEAT_FP16`): "AdvSIMD three same (FP)" de meia precisão vive no MESMO
            // espaço `bit21=0`, assinatura `bit22=1 && bit10=1 && bit15=0` — checado DEPOIS do RDM
            // (que compartilha os mesmos 3 bits e só se separa pelo `opcode`, ver o Javadoc do
            // método) e ANTES de EXT/permute/copy/SHA (que exigem `bit22=0` ou `bit10=0`, nunca
            // colidem). Sem a feature, o bloco é pulado inteiro — comportamento byte a byte o de
            // hoje.
            if (architecture.has(Aarch64Feature.FP16)) {
                Ir64Op fp16ThreeSameOp = decodeAdvancedSimdFp16ThreeSame(word, scalar, q);
                if (fp16ThreeSameOp != null) {
                    return fp16ThreeSameOp;
                }
            }
            // B19.11 (`FEAT_FP8`): `FCVTN_bh`/`FCVTN_bs` vivem no MESMO espaço `bit21=0`,
            // discriminadas por `opcode=0b1_1110` (`bit15=1`, o valor que
            // {@link #decodeAdvancedSimdFp16ThreeSame} trata como reservado/RDM e devolve `null`) —
            // checado DEPOIS do FP16 (nunca colide: FP16 exige `bit15=0`) e ANTES de EXT/permute/
            // copy/SHA. Sem a feature, pulado inteiro (byte a byte igual a antes desta task).
            if (architecture.has(Aarch64Feature.FP8)) {
                Ir64Op fp8ThreeSameOp = decodeAdvancedSimdFp8ThreeSame(word, scalar, q);
                if (fp8ThreeSameOp != null) {
                    return fp8ThreeSameOp;
                }
                // B19.11e: `FSCALE_h` — mesma feature, opcode DIFERENTE de FCVTN_bh/FCVTN_bs
                // (nunca colide, ver Javadoc de {@link #decodeAdvancedSimdFp8Scale}).
                Ir64Op fp8ScaleOp = decodeAdvancedSimdFp8Scale(word, scalar, q);
                if (fp8ScaleOp != null) {
                    return fp8ScaleOp;
                }
            }
            // B19.20 (`FEAT_FCMA`): `FCADD_90`/`FCADD_270`/`FCMLA_v` também vivem no MESMO espaço
            // `bit21=0` (`U=1`+`bit10=1` fixos, opcode nunca colide com RDM/FP16/FP8 acima —
            // conferido exaustivamente, ver o Javadoc das constantes `ADVSIMD_FCMA_*`) — checado
            // DEPOIS do FP8 e ANTES de EXT/permute/copy/SHA. Sem a feature, pulado inteiro.
            if (architecture.has(Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC)) {
                Ir64Op fcmaOp = decodeAdvancedSimdComplexNumberArithmetic(word, scalar, q);
                if (fcmaOp != null) {
                    return fcmaOp;
                }
            }
            // B8.10: `EXT`/`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`/`TBL`/`TBX` vivem no MESMO
            // prefixo vetorial "01110", `bit21=0` — espaço que B8.7-B8.9 nunca examinaram (só
            // tratavam `bit21=1`, lançando `unsupported` direto para o resto). B8.12: `DUP`/`INS`/
            // `SMOV`/`UMOV` (AdvSIMD copy) TAMBÉM vivem aqui (`bit10=1`, oposto das famílias
            // acima) — ver {@link #decodeAdvancedSimdCopy}.
            if (!scalar) {
                Ir64Op op = decodeAdvancedSimdExtractPermuteTable(word, address, q);
                if (op != null) {
                    return op;
                }
            } else {
                // B8.11b: "Cryptographic three-register SHA" (`SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/
                // `SHA256H`/`SHA256H2`/`SHA256SU1`) vive no MESMO prefixo "escalar" que `AESE`/etc
                // (bit30=1/bit21=0), espaço nunca examinado antes (B8.11 só tratava `bit21=1`).
                // CONFERIDO bit a bit contra corpus real (`aarch64-none-elf-as`/`objdump`,
                // devkitA64, `.arch armv8-a+crypto`): `Rm`(20:16)/`opcode`(15:10, 6 bits)/`Rn`(9:5)/
                // `Rd`(4:0) — layout PRÓPRIO, diferente do resto de `decodeAdvancedSimdInteger`
                // (sem `size`/`U` reais nesta forma).
                int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
                int opcode = (word >>> CRYPTO_SHA_THREE_REG_OPCODE_SHIFT) & CRYPTO_SHA_THREE_REG_OPCODE_MASK;
                int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
                int rd = word & REGISTER_FIELD_MASK;
                // B19.6 bloco E: `DUP_element_s` (Advanced SIMD scalar copy) vive no MESMO prefixo
                // escalar, discriminada de SHA-three-register por `opcode==0b000001` (bit10=1 —
                // todas as 7 opcodes SHA reais têm bit10=0, ver os valores pares de
                // `decodeCryptoShaThreeRegisterOpcode`). `rm` aqui não é `Rm`: é `imm5` cru (bits
                // [20:16], MESMA posição que {@link #decodeAdvancedSimdCopy} usa para a forma
                // vetorial).
                if (opcode == ADVSIMD_SCALAR_COPY_OPCODE) {
                    Ir64Op dup = decodeAdvancedSimdScalarDuplicateElement(rm, rn, rd);
                    if (dup != null) {
                        return dup;
                    }
                }
                Ir64CryptoShaThreeRegisterOp shaOp = decodeCryptoShaThreeRegisterOpcode(opcode);
                if (shaOp != null) {
                    return new Ir64Op.CryptoShaThreeRegister(shaOp, rd, rn, rm);
                }
            }
            throw unsupported(word, address);
        }
        // B8.8: campo `size` real SEMPRE lido, mesmo escalar — B8.7 assumia `esz=3` fixo para TODO
        // escalar (válido só para `ADD_s`/`SUB_s`/`CM**_s`/`ABS_s`/`NEG_s`/`CM**0_s`, que exigem
        // literalmente `11` nesses bits no encoding real). `SQADD_s`/`SQSHL_s`/`SUQADD_s`/etc
        // aceitam QUALQUER tamanho (`@rrr_e`/`@r2r_e` reais, não `@rrr_d`) — ler o campo cru e
        // validar por OPCODE (não por prefixo) é o único jeito de decodificar os dois corretamente
        // sem duplicar o dispatch. Achado: isso também CORRIGE um bug latente da B8.7 — antes desta
        // task, `esz` era forçado a `3` mesmo quando os bits reais não eram `11`, então um encoding
        // reservado (`ADD_s` com `size!=11`) era silenciosamente decodificado como `ADD_s` válido
        // em vez de cair em `UNIMPLEMENTED` (G8); agora `decodeAdvancedSimdThreeSameShape`/
        // `decodeVectorUnaryOpcode` validam o `esz` real contra o que cada opcode aceita.
        int esz = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        boolean threeSameShape = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (threeSameShape) {
            return decodeAdvancedSimdThreeSameShape(word, address, scalar, q, esz, u, opcode, rn, rd, rm);
        }
        // E8: bug pré-existente (achado na B8.11, não corrigido lá) — o discriminador REAL entre
        // "three different" (`SMULL`/`PMULL`/..., `Rm` é um registrador livre `0`-`31`) e as formas
        // que reaproveitam `Rm` como opcode disfarçado (two-register misc/narrow-unário/across-lanes/
        // AES/`ADDP_s` escalar) é o bit11 do word (LSB do campo `opcode` de 5 bits lido acima), NUNCA
        // o valor de `Rm`: no encoding real, "three different" tem um campo opcode de só 4 bits em
        // bits[15:12] com bit11 fixo em `0`, enquanto as outras formas sempre têm bit11=`1`.
        // Confirmado bit a bit via corpus real (`aarch64-none-elf-as`/`objdump`, devkitA64):
        // `smull v0.8h,v1.8b,v16.8b` (`Rm=0b10000`) tem `Rm` IDÊNTICO ao `Rm` fixo de `SADDLV`, e
        // `smull v0.8h,v1.8b,v0.8b` (`Rm=0`) tem `Rm` IDÊNTICO ao `Rm` fixo de `ABS`/two-reg-misc —
        // nos dois casos bit11 vale `0` (three-different) contra bit11=`1` das outras (SADDLV/ABS),
        // provando que `Rm` sozinho é ambíguo e bit11 nunca é. A checagem antiga (`Rm`&bit4) tratava
        // só o subconjunto `Rm=16-31`; o caso `Rm=0`/`Rm=1` (colidindo com two-reg-misc/narrow-unário)
        // era um bug igualmente real, não documentado pela B8.11.
        boolean rmEncodesFixedOpcode = (opcode & 1) != 0;
        if (!rmEncodesFixedOpcode) {
            if (scalar) {
                // B8.20: `SQDMULL`/`SQDMLAL`/`SQDMLSL` ESCALARES sem índice — os ÚNICOS mnemônicos
                // de "three different" com forma escalar real (ARM DDI 0487); todo o resto deste
                // espaço (`SMULL`/`SADDL`/`SABAL`/.../`PMULL`) só existe vetorial (G8). `U=1` não
                // tem forma real aqui (não existe `UQDMULL`), e a forma escalar só aceita `H`→`S`/
                // `S`→`D` (nunca `B`/`D` — `esz=0` já é rejeitado dentro de
                // {@link #decodeAdvancedSimdThreeDifferent}, `esz=3` rejeitado aqui).
                boolean isSaturatingDoublingOpcode = !u
                        && (opcode == 0b1_1010 || opcode == 0b1_0010 || opcode == 0b1_0110);
                if (!isSaturatingDoublingOpcode || esz == ADVSIMD_INT_SCALAR_ESZ) {
                    throw unsupported(word, address);
                }
                return decodeAdvancedSimdThreeDifferent(word, address, true, false, esz, u, opcode, rn, rd, rm);
            }
            // "three different" vetorial. B8.11: EXCEÇÃO — `PMULL_p64` (`opcode=0b11100`) é a ÚNICA
            // "three different" real com `esz=3`/doubleword (produz o registro `Q` inteiro, não um
            // elemento `esz+1` comum).
            if (esz == ADVSIMD_INT_SCALAR_ESZ && opcode != 0b1_1100) {
                // Doubleword não tem forma alargada/estreitada real (ARM DDI 0487) — exceto
                // PMULL_p64 acima; G8.
                throw unsupported(word, address);
            }
            return decodeAdvancedSimdThreeDifferent(word, address, false, q, esz, u, opcode, rn, rd, rm);
        }
        // B8.11: `AESE`/`AESD`/`AESMC`/`AESIMC` (Cryptographic Extension) vivem no MESMO espaço de
        // bits que "three different" (prefixo vetorial `01110`, `bit21=1`, `bit10=0`) — no
        // encoding real (`a64.decode` do QEMU, seção "Cryptographic AES") são `Q=1`/`U=0`/`size=00`
        // sempre fixos e `Rm=0b01000` sempre fixo (a diferença entre as 4 instruções mora inteira
        // no campo `opcode`, `9`/`11`/`13`/`15` — nenhum desses 4 valores colide com os opcodes
        // PARES já usados por `VectorArithmeticWidening`/`Wide`/`Narrow` em
        // {@link #decodeAdvancedSimdThreeDifferent}, conferido exaustivamente). Checado ANTES do
        // resto do dispatch de "three different" porque usa um record totalmente diferente (2
        // registradores reais, não 3).
        if (q && !u && esz == 0 && rm == ADVSIMD_AES_RM) {
            Ir64CryptoAesOp aesOp = decodeCryptoAesOpcode(opcode);
            if (aesOp != null) {
                return new Ir64Op.CryptoAes(aesOp, rd, rn);
            }
        }
        // B8.11b: `SHA1H`/`SHA1SU1`/`SHA256SU0` ("Cryptographic two-register SHA") vivem no MESMO
        // slot `Rm`/`opcode` que `AESE`/etc, mas do lado ESCALAR do dispatch (`q` acima é forçado a
        // `false` para qualquer forma escalar — ver a definição de `q` no início desta função — por
        // isso não pode reaproveitar a checagem `q && ...` de cima, que só é alcançável quando
        // `!scalar`). Achado real: `AESE`/etc exigem `!scalar` (`Q=1` vetorial de verdade); este
        // slot exige `scalar` (mesmo `Rm`/`esz`/`U` fixos, discriminados só pelo `opcode`, ver
        // decodeCryptoShaTwoRegisterOpcode) — os dois são mutuamente exclusivos, nunca colidem.
        if (scalar && !u && esz == 0 && rm == ADVSIMD_AES_RM) {
            Ir64CryptoShaTwoRegisterOp shaOp = decodeCryptoShaTwoRegisterOpcode(opcode);
            if (shaOp != null) {
                return new Ir64Op.CryptoShaTwoRegister(shaOp, rd, rn);
            }
        }
        if (rm == ADVSIMD_INT_RM_TWO_REG_MISC) {
            // B8.18: `CNT`/`NOT`/`RBIT` compartilham o MESMO opcode (`0b0_1011`) neste slot — o
            // campo que para o resto desta tabela é `esz` aqui só desambigua as 3 mnemônicas entre
            // si (byte a byte sempre, arranjo `.8B`/`.16B` fixo no encoding real; NUNCA um tamanho
            // de elemento livre), então precisam de despacho próprio ANTES do genérico abaixo, com
            // `esz` forçado a `0` no record — devolver o `esz` cru quebraria `RBIT` (que reverte
            // bits por BYTE, não pelo tamanho que o campo pareceria indicar). Sem forma escalar
            // real (G8: `scalar` cai no `throw` genérico do fim deste bloco).
            if (!scalar && opcode == ADVSIMD_TWO_REG_MISC_BYTE_ONLY_OPCODE) {
                Ir64VectorUnaryOp byteOp = decodeVectorUnaryByteOnlyOpcode(u, esz);
                if (byteOp != null) {
                    return new Ir64Op.VectorArithmeticUnary(byteOp, false, q, 0, rd, rn);
                }
                throw unsupported(word, address);
            }
            Ir64VectorUnaryOp op = decodeVectorUnaryOpcode(u, opcode, scalar);
            if (op != null) {
                validateScalarUnaryEsz(word, address, scalar, op, esz);
                validateVectorUnaryEsz(word, address, op, esz);
                return new Ir64Op.VectorArithmeticUnary(op, scalar, q, esz, rd, rn);
            }
            // B8.9 (vetorial: `FABS_v`/`FNEG_v`/`FCM**0_v`) + B19.3 (escalar: só as 5
            // comparações-contra-zero `FCMGT0_s`/`FCMGE0_s`/`FCMEQ0_s`/`FCMLE0_s`/`FCMLT0_s`) —
            // MESMO slot `Rm=00000` do inteiro (achado real da triagem, ver javadoc de
            // {@link Ir64VectorFpUnaryOp}). `FABS`/`FNEG` FP NÃO têm forma escalar aqui (os
            // escalares já são {@link Ir64Op.Fp64Alu} desde B8.4) ⇒ com prefixo escalar são
            // reservados ⇒ `unsupported` (G8, via {@link #fpUnaryOpHasScalarForm}).
            boolean fpRmZeroA = ((esz >>> 1) & 1) != 0;
            int fpRmZeroEsz = 2 + (esz & 1);
            Ir64VectorFpUnaryOp fpRmZeroOp = decodeVectorFpUnaryRmZeroOpcode(u, fpRmZeroA, opcode);
            if (fpRmZeroOp != null) {
                if (scalar && !fpUnaryOpHasScalarForm(fpRmZeroOp)) {
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorFpArithmeticUnary(fpRmZeroOp, scalar, q, fpRmZeroEsz, rd, rn);
            }
            throw unsupported(word, address);
        }
        if (rm == ADVSIMD_INT_RM_NARROW_UNARY) {
            // B8.8: `SQXTN`/`SQXTUN`/`UQXTN` (narrow unário saturante); B8.20: `XTN` reaproveita o
            // MESMO opcode de `SQXTUN` (`U=0`, sem forma escalar — `decodeVectorNarrowUnaryOpcode`
            // nega explicitamente).
            Ir64VectorNarrowUnaryOp narrowOp = decodeVectorNarrowUnaryOpcode(u, opcode, scalar);
            if (narrowOp != null) {
                if (esz == ADVSIMD_INT_SCALAR_ESZ) {
                    // Doubleword não tem forma estreitada real (não há `Q`→`D`); G8.
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorArithmeticNarrowUnary(narrowOp, scalar, q, esz, rd, rn);
            }
            // B8.20: `SHLL`/`SHLL2` — MESMO slot, opcode `0b0_0111`/`U=1`; reaproveita 100%
            // `Ir64Op.VectorShiftWidenImmediate`/`USHLL` (B8.8) — `SHLL` É literalmente "zero-extend
            // e desloca à esquerda pela largura INTEIRA do elemento estreito" (`8<<esz`, quantidade
            // FIXA, não um imediato genérico), mas a fórmula do executor (`zext(Rn) << shift`) é
            // idêntica; sem forma escalar/doubleword real (G8).
            if (!scalar && u && opcode == ADVSIMD_TWO_REG_MISC_SHLL_OPCODE) {
                if (esz == ADVSIMD_INT_SCALAR_ESZ) {
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorShiftWidenImmediate(Ir64VectorShiftWidenOp.USHLL, q, esz, 8 << esz, rd, rn);
            }
            // B8.20: `URECPE`/`URSQRTE` — MESMO slot/opcode (`0b1_1001`) que `FCVTAS`/`FCVTAU` usam
            // no dispatch FP abaixo, discriminados pelo bit ALTO de `esz` (`a`, ver
            // {@link #decodeVectorFpUnaryRmOneOpcode}): `esz==` {@link #ADVSIMD_ESZ_WORD} (`a=1`)
            // cai aqui, sem colisão com `FCVTAS`(`a=0`)/`FCVTAU` — conferido exaustivamente contra
            // corpus real (devkitA64). Só arranjo `.2s`/`.4s` (sem forma escalar/doubleword, G8).
            if (!scalar && opcode == ADVSIMD_URECPE_URSQRTE_OPCODE && esz == ADVSIMD_ESZ_WORD) {
                Ir64VectorUnaryOp recipOp = u ? Ir64VectorUnaryOp.URSQRTE : Ir64VectorUnaryOp.URECPE;
                return new Ir64Op.VectorArithmeticUnary(recipOp, false, q, esz, rd, rn);
            }
            // B19.3: `FRECPX_s` — só forma AdvSIMD-escalar; colide de opcode com `SQRT` vetorial
            // (`decodeVectorFpUnaryRmOneOpcode`, `key==0b11`) ⇒ `if` EXPLÍCITO ANTES da tabela
            // compartilhada, NUNCA nela (Armadilha 2 da task).
            if (scalar && !u && opcode == ADVSIMD_FRECPX_OPCODE && ((esz >>> 1) & 1) != 0) {
                return new Ir64Op.VectorFpArithmeticUnary(
                        Ir64VectorFpUnaryOp.FRECPX, true, false, 2 + (esz & 1), rd, rn);
            }
            // B19.3: `FCVTXN_s` — `f64`→`f32` round-to-odd; só escalar (`FCVTXN_v` é B19.4).
            // `esz` do record = ENTRADA `f64` (doubleword); os `bits[23:22]` crus do encoding
            // `@rr_s` valem `01` e NÃO representam tamanho aqui.
            if (scalar && u && opcode == ADVSIMD_FCVTXN_OPCODE) {
                return new Ir64Op.VectorFpArithmeticUnary(
                        Ir64VectorFpUnaryOp.FCVTXN, true, false, ADVSIMD_INT_SCALAR_ESZ, rd, rn);
            }
            // B19.4: `FCVTN_v`/`FCVTXN_v`/`FCVTL_v` — conversões de PRECISÃO vetoriais (`f16`↔`f32`↔
            // `f64`). MESMO slot/opcode que `SQXTN`-família; `if`s EXPLÍCITOS ANTES da tabela
            // compartilhada, NUNCA nela (Armadilha 3 da task). `bit23` é o discriminador `a` (NÃO
            // tamanho): separa `FCVTN_v`(a=0) de `BFCVTN_v`(a=1) e `FCVTL_v`(!u) das 4 variantes
            // FP8/BF16 (u). `esz` do record = lado ESTREITO (`1 + sz`), convenção idêntica a
            // `VectorShiftNarrow/WidenImmediate` (B8.8) — o `.decode` dá o DESTINO, que é estreito em
            // `FCVTN`/`FCVTXN` e LARGO em `FCVTL` (Armadilha 2).
            if (!scalar && (opcode == ADVSIMD_FCVTXN_OPCODE || opcode == ADVSIMD_FCVTL_OPCODE)) {
                int precisionA = (esz >>> 1) & 1;
                int precisionSz = esz & 1;
                if (opcode == ADVSIMD_FCVTXN_OPCODE && !u && precisionA == 0) {
                    return new Ir64Op.VectorFpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.FCVTN, q, 1 + precisionSz, rd, rn);
                }
                if (opcode == ADVSIMD_FCVTXN_OPCODE && u && precisionA == 0 && precisionSz == 1) {
                    return new Ir64Op.VectorFpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.FCVTXN, q, ADVSIMD_ESZ_WORD, rd, rn);
                }
                if (opcode == ADVSIMD_FCVTL_OPCODE && !u && precisionA == 0) {
                    return new Ir64Op.VectorFpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.FCVTL, q, 1 + precisionSz, rd, rn);
                }
                // B19.7 (`FEAT_BF16`): `BFCVTN_v`/`BFCVTN2` — MESMO slot, `!u && a==1` (o caso que
                // este `if` deixava cair no `unsupported` de baixo antes desta task). `esz` do
                // record é sempre `1` (`bf16` tem a mesma largura de `f16`; não há forma `f64`→
                // `bf16`, por isso `precisionSz` não entra na condição).
                if (opcode == ADVSIMD_FCVTXN_OPCODE && !u && precisionA == 1
                        && architecture.has(Aarch64Feature.BFLOAT16)) {
                    return new Ir64Op.VectorFpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.BFCVTN, q, 1, rd, rn);
                }
                // B19.11 (`FEAT_FP8`): `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL` — MESMO slot/opcode,
                // `u=1` (o ramo que B19.4/B19.7 deixavam cair no `unsupported` de baixo). `precisionA`
                // (bit23) distingue `F*CVTL`(0, destino `binary16`) de `BF*CVTL`(1, destino
                // `bfloat16`); `precisionSz` (bit22) distingue `1`(0, `FPMR.F8S1`/`LSCALE`) de
                // `2`(1, `FPMR.F8S2`/`LSCALE2`) — confirmado byte a byte contra `a64.decode` real
                // (`target/isa-decode/a64.decode:1971-1975`) e o pseudocódigo ARM real de
                // `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL` (`developer.arm.com`/`scs.stanford.edu`).
                if (opcode == ADVSIMD_FCVTL_OPCODE && u) {
                    if (!architecture.has(Aarch64Feature.FP8)) {
                        throw unsupported(word, address);
                    }
                    boolean bfloat16Destination = precisionA == 1;
                    boolean secondStream = precisionSz == 1;
                    return new Ir64Op.VectorFpConvertFromFp8(secondStream, bfloat16Destination, q, rd, rn);
                }
                // Toda combinação restante ⇒ reservado ⇒ `unsupported` (G8).
                throw unsupported(word, address);
            }
            // B8.9 (vetorial: `FSQRT_v`/`FRINTx_v`/`FRECPE_v`/`FRSQRTE_v`/`SCVTF_vi`/...) + B19.3
            // (escalar: `RECPE`/`RSQRTE` + as 12 conversões `@icvt` int↔FP escala 0). `SQRT`/
            // `FRINTx` FP NÃO têm forma escalar aqui (os escalares já são {@link Ir64Op.Fp64Alu}/
            // {@link Ir64Op.Fp64Round} desde B8.4/B8.5) ⇒ com prefixo escalar são reservados ⇒
            // `unsupported` (G8, via {@link #fpUnaryOpHasScalarForm}).
            boolean fpRmOneA = ((esz >>> 1) & 1) != 0;
            int fpRmOneEsz = 2 + (esz & 1);
            Ir64VectorFpUnaryOp fpRmOneOp = decodeVectorFpUnaryRmOneOpcode(u, fpRmOneA, opcode);
            if (fpRmOneOp != null) {
                if (scalar && !fpUnaryOpHasScalarForm(fpRmOneOp)) {
                    throw unsupported(word, address);
                }
                // B19.18 (`FEAT_FRINTTS`): `RINT32Z`/`RINT32X`/`RINT64Z`/`RINT64X` só existem sob
                // `ARMv8.5-A`+ — MESMO slot/opcode que o resto desta tabela (base ISA, sempre
                // disponível), então o gate mora aqui, não na tabela (que não tem acesso a
                // `architecture`).
                if (isDirectedRoundingToIntegral(fpRmOneOp)
                        && !architecture.has(Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL)) {
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorFpArithmeticUnary(fpRmOneOp, scalar, q, fpRmOneEsz, rd, rn);
            }
            throw unsupported(word, address);
        }
        if (rm == ADVSIMD_INT_RM_FP16_TWO_REG_MISC) {
            // B19.5.4 (`FEAT_FP16`): MESMA tabela de opcode do slot `Rm=00000` (achado medido bit a
            // bit contra `a64.decode`: `FABS_v` é `opcode=11111` nos dois, `FCMGT0_*` é `11001` nos
            // dois, ...) — reaproveitada sem duplicar (item 3 de "Inclui" da task). `esz` do record
            // é SEMPRE `ADVSIMD_ESZ_HALFWORD`: `bit22` aqui é fixo em `1` e NÃO é tamanho (Armadilha
            // 2) — só `bit23` (`a`) continua real e é o que a tabela usa para desambiguar.
            if (!architecture.has(Aarch64Feature.FP16)) {
                throw unsupported(word, address);
            }
            boolean fpHalfZeroA = ((esz >>> 1) & 1) != 0;
            Ir64VectorFpUnaryOp fpHalfZeroOp = decodeVectorFpUnaryRmZeroOpcode(u, fpHalfZeroA, opcode);
            if (fpHalfZeroOp != null) {
                if (scalar && !fpUnaryOpHasScalarForm(fpHalfZeroOp)) {
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorFpArithmeticUnary(fpHalfZeroOp, scalar, q, ADVSIMD_ESZ_HALFWORD, rd, rn);
            }
            throw unsupported(word, address);
        }
        if (rm == ADVSIMD_INT_RM_FP16_NARROW_UNARY) {
            // B19.5.4 (`FEAT_FP16`): MESMO slot `Rm=00001`. `FRECPX_s` continua exigindo o `if`
            // EXPLÍCITO antes da tabela compartilhada (mesma colisão de opcode com `SQRT`, U=0 x
            // U=1, que a B19.3 documentou) — o resto (`FSQRT_v`/`FRINTx_v`/`FRECPE`/`FRSQRTE`/
            // `SCVTF`/`UCVTF`/as 10 conversões `FCVTx{S,U}` vetoriais+escalares) reusa
            // `decodeVectorFpUnaryRmOneOpcode` sem tabela nova. BF16/FP8 (`F1CVTL`/`F2CVTL`/
            // `BF1CVTL`/`BF2CVTL`/`BFCVTN_v`) moram no slot `0b0_0001` de verdade (Armadilha 4) —
            // este bloco nunca os alcança.
            if (!architecture.has(Aarch64Feature.FP16)) {
                throw unsupported(word, address);
            }
            if (scalar && !u && opcode == ADVSIMD_FRECPX_OPCODE && ((esz >>> 1) & 1) != 0) {
                return new Ir64Op.VectorFpArithmeticUnary(
                        Ir64VectorFpUnaryOp.FRECPX, true, false, ADVSIMD_ESZ_HALFWORD, rd, rn);
            }
            boolean fpHalfOneA = ((esz >>> 1) & 1) != 0;
            Ir64VectorFpUnaryOp fpHalfOneOp = decodeVectorFpUnaryRmOneOpcode(u, fpHalfOneA, opcode);
            if (fpHalfOneOp != null) {
                if (scalar && !fpUnaryOpHasScalarForm(fpHalfOneOp)) {
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorFpArithmeticUnary(fpHalfOneOp, scalar, q, ADVSIMD_ESZ_HALFWORD, rd, rn);
            }
            throw unsupported(word, address);
        }
        if ((rm & ADVSIMD_INT_RM_ACROSS_LANES_MASK) == ADVSIMD_INT_RM_ACROSS_LANES_PATTERN) {
            if (scalar) {
                // Único mnemônico escalar desta forma: `ADDP_s` (`U=0`,`Rm=0b10001`,`opcode=0b10111`
                // — valores conferidos contra o corpus real, não decompostos mais finamente). B8.8:
                // `esz` agora é lido cru (ver comentário acima) — `ADDP_s` exige literalmente
                // `size=11` no encoding real, então valida aqui (G8; antes o hardcode de B8.7
                // tornava essa checagem desnecessária).
                if (!u && rm == 0b1_0001 && opcode == 0b1_0111 && esz == ADVSIMD_INT_SCALAR_ESZ) {
                    return new Ir64Op.VectorScalarPairwiseAdd(rd, rn);
                }
                // B19.2: AdvSIMD "scalar pairwise (FP)" (`FADDP_s`/`FMAXP_s`/`FMINP_s`/`FMAXNMP_s`/
                // `FMINNMP_s`) vive neste MESMO espaço (`rm`=`0b1_0000` fixo, `bit10=0`), ao lado do
                // `ADDP_s` inteiro. `esz` bits[23:22] → `a`(bit23, discriminador)/`sz`(bit22).
                // B19.5.3 (`FEAT_FP16`): a MESMA tabela de opcodes vale para as formas `_h`
                // (conferido bit a bit contra `a64.decode`) — só o `U` inverte (`_sd`→`u=1`,
                // `_h`→`u=0`) e `esz` da forma `_h` é SEMPRE `HALFWORD` (o `sz`/bit22 lido acima
                // não existe de verdade nesse encoding, é bit fixo em `0`).
                boolean fpA = ((esz >>> 1) & 1) != 0;
                Ir64VectorFpPairwiseOp fpPairwiseOp = decodeVectorFpScalarPairwiseOpcode(fpA, opcode);
                if (fpPairwiseOp != null) {
                    if (u) {
                        int fpFloatEsz = 2 + (esz & 1);
                        return new Ir64Op.VectorFpArithmeticPairwise(fpPairwiseOp, true, false, fpFloatEsz, rd, rn, rn);
                    }
                    if (architecture.has(Aarch64Feature.FP16)) {
                        return new Ir64Op.VectorFpArithmeticPairwise(
                                fpPairwiseOp, true, false, ADVSIMD_ESZ_HALFWORD, rd, rn, rn);
                    }
                }
                throw unsupported(word, address);
            }
            Ir64VectorAcrossLanesOp op = decodeVectorAcrossLanesOpcode(u, rm, opcode);
            if (op != null) {
                if (esz == ADVSIMD_INT_SCALAR_ESZ) {
                    // Nenhuma destas operações reduz doubleword (ARM DDI 0487, "across lanes"; G8).
                    throw unsupported(word, address);
                }
                return new Ir64Op.VectorAcrossLanes(op, q, esz, rd, rn);
            }
            // B8.10: `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` vivem no MESMO slot `Rm[4]=1` do inteiro
            // "across lanes" — `U=1` sempre (nunca colide com os `U`s usados pelo inteiro acima,
            // conferido contra `a64.decode` real) e `Q=1` sempre (só existe arranjo `4S`, sem forma
            // doubleword). B19.5.3: as formas `_h` (`FEAT_FP16`) vivem no MESMO `if`, `U=0` — o
            // MESMO `opcode`/`a` (bit23) do `decodeVectorFpAcrossLanesOpcode` acima, mas `Q` é
            // LIVRE (arranjo `4H`/`8H`, ao contrário de `_s`, que só tem `4S`).
            if (u && q) {
                Ir64VectorFpAcrossLanesOp fpOp = decodeVectorFpAcrossLanesOpcode(opcode, esz);
                if (fpOp != null) {
                    return new Ir64Op.VectorFpAcrossLanes(fpOp, true, ADVSIMD_ESZ_WORD, rd, rn);
                }
            }
            if (!u && architecture.has(Aarch64Feature.FP16)) {
                Ir64VectorFpAcrossLanesOp fpOp = decodeVectorFpAcrossLanesOpcode(opcode, esz);
                if (fpOp != null) {
                    return new Ir64Op.VectorFpAcrossLanes(fpOp, q, ADVSIMD_ESZ_HALFWORD, rd, rn);
                }
            }
            throw unsupported(word, address);
        }
        // E8: bit11=1 mas `Rm` não bate com nenhum dos padrões fixos conhecidos (two-reg-misc/
        // narrow-unário/AES/across-lanes) — encoding reservado, não "three different" (esse já foi
        // tratado acima, antes de bit11=1 sequer ser checado); G8.
        throw unsupported(word, address);
    }

    /// `SQRDMLAH`/`SQRDMLSH` vetorial/escalar não-indexado (B11.4, `FEAT_RDM`) — entra já sabendo
    /// que `bit21=0` e que a feature está presente (checado pelo chamador). Devolve `null` (nunca
    /// lança) para qualquer combinação que não bata, deixando o chamador continuar tentando
    /// EXT/permute/TBL/copy/SHA no mesmo espaço (G8: o `unsupported` final continua vindo de lá).
    private Ir64Op decodeAdvancedSimdRoundingDoublingMultiplyAccumulate(int word, boolean scalar, boolean q) {
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        if (!u) {
            return null; // sem forma `U=0` real neste opcode+`bit21=0` (ver constante).
        }
        boolean bit10 = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (!bit10) {
            return null;
        }
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        Ir64VectorThreeSameOp op = switch (opcode) {
            case ADVSIMD_RDM_OPCODE_SQRDMLAH -> Ir64VectorThreeSameOp.SQRDMLAH;
            case ADVSIMD_RDM_OPCODE_SQRDMLSH -> Ir64VectorThreeSameOp.SQRDMLSH;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        int esz = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
        if (esz != ADVSIMD_ESZ_HALFWORD && esz != ADVSIMD_ESZ_WORD) {
            // Só `H`/`S` são reais nesta família (mesma restrição de `SQDMULH`/`SQRDMULH`) — `B`/`D`
            // ficam reservados aqui, não silenciosamente aceitos (G8).
            return null;
        }
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.VectorArithmeticThreeSame(op, scalar, q, esz, rd, rn, rm);
    }

    /// B19.5.5 (`FEAT_FP16`): "AdvSIMD three same (FP)"/"AdvSIMD scalar three same (FP)" de meia
    /// precisão (`FADD_v`/`FSUB_v`/`FMAX_v`/`FMIN_v`/`FCMEQ_v` vetoriais + `FMULX_s`/`FCMEQ_s`/
    /// `FCMGE_s`/`FCMGT_s`/`FACGE_s`/`FACGT_s`/`FABD_s`/`FRECPS_s`/`FRSQRTS_s` escalares, 14 linhas)
    /// — MESMO subespaço `bit21=0` de `FEAT_RDM`/EXT-permute-TBL/copy/cripto SHA (auditoria de
    /// colisão medida bit a bit contra `a64.decode`, ver `## Contexto` da task): assinatura
    /// `bit22=1 && bit10=1 && bit15=0`. Só `FEAT_RDM` compartilha os 3 primeiros bits (`esz` livre
    /// em bits[23:22]) — separado exclusivamente pelo `opcode` (`bit15=1` no RDM, checado ANTES por
    /// este método via {@link #ADVSIMD_EXTRACT_PERMUTE_BIT15_MASK}); EXT/permute/TBL exigem
    /// `bit10=0` e copy/SHA exigem `bit22=0`, nenhum alcança este método. Reusa a MESMA tabela de
    /// opcode `(u,a,opcode)` da forma `_sd` ({@link #decodeVectorFpThreeSameOpcode}):
    /// `opcode_sd = opcode_h | `{@link #ADVSIMD_FP16_OPCODE_TO_SD_BIT} (achado medido bit a bit
    /// contra `a64.decode`, os 14 mnemônicos convertem sem exceção). `null` para qualquer
    /// combinação fora das 14 linhas reais (G8) — o chamador decide o que fazer.
    private Ir64Op decodeAdvancedSimdFp16ThreeSame(int word, boolean scalar, boolean q) {
        boolean bit22 = ((word >>> ADVSIMD_INT_SIZE_SHIFT) & 1) != 0;
        boolean bit10 = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (!bit22 || !bit10) {
            return null;
        }
        int opcodeH = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        boolean bit15 = (opcodeH & ADVSIMD_EXTRACT_PERMUTE_BIT15_MASK) != 0;
        if (bit15) {
            // `FEAT_RDM` (`SQRDMLAH`/`SQRDMLSH` de halfword, `esz=01`) — o vizinho perigoso, ver a
            // "Armadilha 1" da task. Já foi tentado primeiro pelo chamador; aqui só garante que este
            // método nunca o engole se a ordem de chamada mudar no futuro.
            return null;
        }
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        boolean a = ((word >>> ADVSIMD_FP_A_BIT_SHIFT) & 1) != 0;
        int opcodeSd = opcodeH | ADVSIMD_FP16_OPCODE_TO_SD_BIT;
        Ir64VectorFpThreeSameOp fpOp = decodeVectorFpThreeSameOpcode(u, a, opcodeSd);
        if (fpOp == null) {
            return null;
        }
        if (scalar && !fpThreeSameOpHasScalarForm(fpOp)) {
            return null;
        }
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.VectorFpArithmeticThreeSame(fpOp, scalar, q, ADVSIMD_ESZ_HALFWORD, rd, rn, rm);
    }

    /// B19.11 (`FEAT_FP8`): `FCVTN_bh`/`FCVTN_bs` — vivem no MESMO espaço que
    /// {@link #decodeAdvancedSimdFp16ThreeSame} (`bit21=0`, `bit10=1`), mas com `opcode=0b1_1110`
    /// (`bit15=1`, que aquele método trata como reservado/RDM e devolve `null` — nunca colidem,
    /// checado APÓS ele pelo chamador). `bit22` aqui NÃO é o discriminador fp16-vs-sd de sempre: é
    /// o próprio seletor de largura de ORIGEM (`FCVTN_bh`=`1`/`FCVTN_bs`=`0`, Armadilha 6 da task —
    /// as duas mnemônicas diferem SÓ nesse bit). `U`(bit29)/`a`(bit23) são fixos em `0` para ambas;
    /// a família de acumulação vizinha (`FDOT_hb_v`/`FMLAL_hb_v`/`FMLALL_sb_v`, `opcode=0b1_1111`,
    /// fora do escopo desta task — ver "Não inclui") não bate este `opcode` e continua caindo no
    /// `unsupported` de sempre (G8). Sem forma escalar real (`a64.decode` só lista `@qrrr_h`
    /// vetorial). Confirmado byte a byte contra `a64.decode` real
    /// (`target/isa-decode/a64.decode:1216-1217`).
    private Ir64Op decodeAdvancedSimdFp8ThreeSame(int word, boolean scalar, boolean q) {
        if (scalar) {
            return null;
        }
        boolean bit10 = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (!bit10) {
            return null;
        }
        int opcodeH = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        if (opcodeH != ADVSIMD_FP8_THREE_SAME_CONVERT_OPCODE) {
            return null;
        }
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        boolean a = ((word >>> ADVSIMD_FP_A_BIT_SHIFT) & 1) != 0;
        if (u || a) {
            return null;
        }
        boolean halfSource = ((word >>> ADVSIMD_INT_SIZE_SHIFT) & 1) != 0;
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.VectorFpConvertToFp8(halfSource, q, rd, rn, rm);
    }

    /// B19.11e (`FEAT_FP8`): `FSCALE_h` — vive no MESMO espaço `bit21=0` de `FEAT_RDM`/FP16/
    /// FP8-convert/FCMA/EXT-permute-copy/SHA, discriminado por `U`=1 fixo + `a`(bit23)=1 fixo +
    /// `bit22`=1 fixo (marca meia precisão, nunca um `sz` livre aqui) + `bit10`=1 fixo +
    /// `opcode`(bits[15:11])={@link #ADVSIMD_FP8_SCALE_OPCODE_H}. **Achado real desta task**: sem
    /// este método, o encoding caía até {@link #decodeAdvancedSimdCopy} (fallback EXT/permute/
    /// copy), que leu `Rm`(bits[20:16], o registrador `Vm` de VERDADE aqui) como se fosse `imm5`
    /// de `INS_element`/`DUP` — produzindo `VectorInsertElement`/`VectorInsertGeneral` sempre que
    /// os bits baixos de `Rm` calhassem de formar um `esz` válido (o `⚠️` medido pela task).
    /// {@link #decodeAdvancedSimdFp16ThreeSame} e {@link #decodeAdvancedSimdFp8ThreeSame} são
    /// tentados ANTES (mesma ordem do chamador) e devolvem `null` para este opcode sem ambiguidade
    /// (conferido bit a bit: nenhuma key mapeada por eles bate `(u=1,a=1)`). Sem forma escalar
    /// real (`a64.decode` só lista `@qrrr_h`).
    private Ir64Op decodeAdvancedSimdFp8Scale(int word, boolean scalar, boolean q) {
        if (scalar) {
            return null;
        }
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        boolean a = ((word >>> ADVSIMD_FP_A_BIT_SHIFT) & 1) != 0;
        boolean bit22 = ((word >>> ADVSIMD_INT_SIZE_SHIFT) & 1) != 0;
        boolean bit10 = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (!u || !a || !bit22 || !bit10) {
            return null;
        }
        int opcodeH = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        if (opcodeH != ADVSIMD_FP8_SCALE_OPCODE_H) {
            return null;
        }
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.VectorFpScaleByInt(q, ADVSIMD_ESZ_HALFWORD, rd, rn, rm);
    }

    /// B19.20 (`FEAT_FCMA`): `FCADD_90`/`FCADD_270`/`FCMLA_v` — vivem no MESMO espaço `bit21=0` que
    /// `FEAT_RDM`/FP16/FP8-three-same/EXT-permute-TBL/copy/SHA (checado ANTES de EXT/permute, mesma
    /// disciplina de {@link #decodeAdvancedSimdRoundingDoublingMultiplyAccumulate}), discriminados
    /// por `U`=1 fixo + `bit10`=1 fixo + `opcode`(bits[15:11], ver as constantes `ADVSIMD_FCMA_*`).
    /// `esz`(bits[23:22]) livre em `{1,2,3}` (`0` reservado, G8); `esz=3`(dupla) EXIGE `q=true` no
    /// encoding real — não cabe um par complexo de dupla precisão em 64 bits (confirmado: o
    /// assembler real só produz `.2d`, nunca `.1d`, para `FCADD`/`FCMLA`). Sem forma escalar (ARM
    /// DDI 0487: só vetorial). Núcleo reaproveitado 100% de `advsimd/AdvSimdLanes`
    /// (`fpComplexAdd`/`fpComplexMultiplyAccumulate`, já escrito pela B13.17 para o NEON de 32
    /// bits). `null` (nunca `unsupported`) para qualquer combinação fora das 6 formas reais — G8,
    /// deixa o chamador continuar tentando EXT/permute/TBL/copy/SHA no mesmo espaço.
    private Ir64Op decodeAdvancedSimdComplexNumberArithmetic(int word, boolean scalar, boolean q) {
        if (scalar) {
            return null;
        }
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        boolean bit10 = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (!u || !bit10) {
            return null;
        }
        int esz = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
        if (esz == 0 || (esz == ADVSIMD_INT_SCALAR_ESZ && !q)) {
            return null; // `esz=0` reservado; `esz=3&&!q` UNDEFINED (par complexo não cabe em D).
        }
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        if ((opcode & ADVSIMD_FCMA_OPCODE_PREFIX_MASK) == ADVSIMD_FCMA_OPCODE_PREFIX_PATTERN) {
            int rotation = (opcode & ADVSIMD_FCMA_ROTATION_MASK) * ADVSIMD_FCMA_ROTATION_UNIT_DEGREES;
            return new Ir64Op.VectorFpComplexMultiplyAccumulate(q, esz, rotation, rd, rn, rm);
        }
        if (opcode == ADVSIMD_FCADD_OPCODE_90) {
            return new Ir64Op.VectorFpComplexAdd(q, esz, ADVSIMD_FCMA_ROTATION_UNIT_DEGREES, rd, rn, rm);
        }
        if (opcode == ADVSIMD_FCADD_OPCODE_270) {
            return new Ir64Op.VectorFpComplexAdd(q, esz, 3 * ADVSIMD_FCMA_ROTATION_UNIT_DEGREES, rd, rn, rm);
        }
        return null;
    }

    /// `EXT`(`U=1`)/`UZP1``UZP2``TRN1``TRN2``ZIP1``ZIP2`(`U=0`,`bit11=1`)/`TBL``TBX`(`U=0`,
    /// `bit11=0`) — B8.10: as três famílias que vivem no prefixo vetorial "01110", `bit21=0`,
    /// `bit10=0` (espaço nunca examinado por B8.7-B8.9, que só tratavam `bit21=1` e lançavam
    /// `unsupported` direto para o resto). B8.12: `DUP`/`INS`/`SMOV`/`UMOV` (AdvSIMD copy) vivem
    /// no MESMO prefixo com `bit10=1` — despachadas para {@link #decodeAdvancedSimdCopy} antes de
    /// qualquer checagem deste método (ver ali). Este método continua devolvendo `null` (nunca um
    /// encoding ERRADO, G8) para qualquer combinação reservada dentro do seu próprio espaço
    /// (`bit10=0`); o chamador lança `unsupported`. Discriminadores conferidos linha a linha
    /// contra `a64.decode` real do QEMU (seções "Advanced SIMD extract"/"permute"/"table
    /// lookup").
    private Ir64Op decodeAdvancedSimdExtractPermuteTable(int word, long address, boolean q) {
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        // `opcode` reaproveita o MESMO campo bits[15:11] de {@link #decodeAdvancedSimdInteger}
        // (`bit15` é o bit mais alto, `bit11` o mais baixo) — as três famílias abaixo o
        // desmontam de formas diferentes (EXT: `imm4` cru; permute: 3 bits de opcode + `bit11=1`
        // fixo; TBL/TBX: `len`+`tbx` + `bit11=0` fixo).
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        boolean bit15 = (opcode & ADVSIMD_EXTRACT_PERMUTE_BIT15_MASK) != 0;
        boolean bit11 = (opcode & 1) != 0;
        boolean bit10 = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        // B19.8 (`FEAT_LUT`): `LUTI2`/`LUTI4` checados ANTES do resto (EXT/permute/TBL/copy),
        // mesmo padrão de {@link #decodeAdvancedSimdRoundingDoublingMultiplyAccumulate} (B11.4) —
        // sem a feature, os 4 padrões já caem em `unsupported` pelo fallback de `TBL` abaixo
        // (`esz` nunca `0` para eles), então a ordem só evita trabalho à toa quando a feature ESTÁ
        // presente.
        if (architecture.has(Aarch64Feature.LOOKUP_TABLE) && !u && !bit10 && !bit15) {
            Ir64Op lutiOp = decodeAdvancedSimdLookupTable(word, rm, rn, rd);
            if (lutiOp != null) {
                return lutiOp;
            }
        }
        // B19.7 (`FEAT_BF16`): `BFDOT_v`/`BFMLAL_v`/`BFMMLA` também vivem em `bit10=1` (MESMO
        // espaço que "AdvSIMD copy" abaixo usa), discriminados por `U`=1 + `opcode`(bits[15:11])
        // fixo — checados ANTES de `decodeAdvancedSimdCopy` (senão nunca seriam alcançados: `bit10`
        // desviaria para lá primeiro). Sem a feature, ou sem bater `opcode`/`size`, cai no mesmo
        // `unsupported` de sempre (G8) — `decodeAdvancedSimdCopy` já rejeitava estes bits antes
        // desta task, então a ordem não regride nada.
        if (u && bit10 && architecture.has(Aarch64Feature.BFLOAT16)) {
            int size = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
            if (opcode == ADVSIMD_BF16_OPCODE_DOT_OR_MLAL) {
                if (size == ADVSIMD_BF16_SIZE_HALFWORD) {
                    return new Ir64Op.VectorFpDotProductBFloat16(q, rd, rn, rm);
                }
                if (size == ADVSIMD_BF16_SIZE_DOUBLEWORD) {
                    // `q` aqui é o seletor `B`/`T` (`top`) — `Vd.4S` é sempre 128 bits nesta
                    // família, ver Javadoc de {@link Ir64Op.VectorFpMultiplyAddLongBFloat16#top}.
                    return new Ir64Op.VectorFpMultiplyAddLongBFloat16(q, rd, rn, rm);
                }
            } else if (opcode == ADVSIMD_BFMMLA_OPCODE && size == ADVSIMD_BF16_SIZE_HALFWORD && q) {
                // `Q` é FIXO em `1` no encoding real de `BFMMLA` (não há forma de 64 bits) —
                // `q=false` aqui é combinação reservada (G8), cai no `unsupported` de sempre.
                return new Ir64Op.VectorFpMatrixMultiplyAccumulateBFloat16(rd, rn, rm);
            }
        }
        // B19.12 (`FEAT_I8MM`): `USDOT_v`/`SMMLA`/`UMMLA`/`USMMLA` também vivem em `bit10=1` (MESMO
        // espaço) — checados ANTES de `decodeAdvancedSimdCopy` pelo MESMO motivo do bloco `BFLOAT16`
        // acima (senão `bit10` desviaria para lá primeiro). Ao contrário do BF16 (`U` fixo em `1`),
        // aqui `U` varia por instrução: `USDOT`/`USMMLA` só existem com `U=0` (`U=1` no MESMO opcode
        // é reservado — `UDOT_v`/`FEAT_DotProd` vive no opcode VIZINHO `0b10010`, não neste, ver
        // {@link #ADVSIMD_DOTPRODUCT_OPCODE}, B19.23), `UMMLA` é `U=1`, `SMMLA` é `U=0` no MESMO
        // opcode de `UMMLA`.
        if (bit10 && architecture.has(Aarch64Feature.INT8_MATRIX_MULTIPLY)) {
            int size = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
            if (size == ADVSIMD_I8MM_SIZE_WORD) {
                if (!u && opcode == ADVSIMD_I8MM_OPCODE_USDOT) {
                    // `USDOT`: `Rn` sem sinal, `Rm` com sinal (não existe `SUDOT` vetorial).
                    return new Ir64Op.VectorIntegerDotProduct(q, false, true, rd, rn, rm);
                }
                // `SMMLA`/`UMMLA`/`USMMLA`: `Q` FIXO em `1` no encoding real (sem forma de 64 bits)
                // — `q=false` aqui é combinação reservada (G8), cai no `unsupported` de sempre.
                if (q && opcode == ADVSIMD_I8MM_OPCODE_MMLA) {
                    // `U` distingue `SMMLA`(assinado/assinado, `u=0`) de `UMMLA`(sem sinal/sem
                    // sinal, `u=1`).
                    return new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(!u, !u, rd, rn, rm);
                }
                if (q && !u && opcode == ADVSIMD_I8MM_OPCODE_USMMLA) {
                    // `USMMLA`: `Rn` sem sinal, `Rm` com sinal (não existe `SUMMLA`, `u=1` reservado).
                    return new Ir64Op.VectorIntegerMatrixMultiplyAccumulate(false, true, rd, rn, rm);
                }
            }
        }
        // B19.23 (`FEAT_DotProd` residual): `SDOT_v`/`UDOT_v` vivem no MESMO espaço `bit10=1` que
        // `USDOT_v` acima, `opcode`=0b10010 vizinho do 0b10011 de `USDOT_v`, `size` sempre
        // {@link #ADVSIMD_I8MM_SIZE_WORD} — checados ANTES de `decodeAdvancedSimdCopy` pelo mesmo
        // motivo do bloco `INT8_MATRIX_MULTIPLY` acima. Reusa o MESMO record `VectorIntegerDotProduct`
        // do `USDOT_v` (B19.12), com sinal IGUAL nos dois operandos em vez de misto.
        if (bit10 && architecture.has(Aarch64Feature.DOT_PRODUCT)) {
            int size = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
            if (size == ADVSIMD_I8MM_SIZE_WORD && opcode == ADVSIMD_DOTPRODUCT_OPCODE) {
                // `SDOT`: os dois operandos com sinal (`u=0`); `UDOT`: os dois sem sinal (`u=1`).
                return new Ir64Op.VectorIntegerDotProduct(q, !u, !u, rd, rn, rm);
            }
        }
        if (bit10) {
            // B8.12: `bit10=1` é exatamente o espaço de "AdvSIMD copy" (`DUP`/`INS`/`SMOV`/
            // `UMOV`) — oposto de EXT/permute/TBL/TBX abaixo, que exigem `bit10=0`. Layout de
            // campos (imm5/imm4/si) não tem nada a ver com o resto desta função — método próprio.
            return decodeAdvancedSimdCopy(word, address, q);
        }
        if (u) {
            // `EXT`: único mnemônico desta forma; `imm` lido cru como 4 bits (bits[14:11], que são
            // exatamente `opcode` quando `bit15=0`) — válido sem checar `q` separadamente porque a
            // forma D (`q=false`) exige literalmente bit14=0 no encoding real (campo `imm3`, não
            // `imm4`); violar isso é reservado (G8).
            if (bit15) {
                return null;
            }
            int imm = opcode & ADVSIMD_EXTRACT_IMM_MASK;
            if (!q && (imm & ADVSIMD_EXTRACT_IMM_Q_BIT) != 0) {
                // `imm` >= 8 sem `Q`: bit14 real não existe na forma D (reservado), G8.
                return null;
            }
            return new Ir64Op.VectorExtract(q, imm, rd, rn, rm);
        }
        if (bit15) {
            // Reservado dentro do espaço EXT/permute/TBL (`bit10=0`) — `AdvSIMD copy` já foi
            // desviada acima antes de chegar aqui.
            return null;
        }
        if (bit11) {
            // `UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`: `bits[11:10]="10"` fixo (`bit11=1`,
            // `bit10=0` já checado acima); `esz` é o campo `size` livre de sempre; os 3 bits de
            // opcode (`bits[14:12]`) selecionam a operação.
            int esz = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
            int permuteOpcode = (opcode >>> 1) & 0b111;
            Ir64VectorPermuteOp op = switch (permuteOpcode) {
                case 0b001 -> Ir64VectorPermuteOp.UZP1;
                case 0b101 -> Ir64VectorPermuteOp.UZP2;
                case 0b010 -> Ir64VectorPermuteOp.TRN1;
                case 0b110 -> Ir64VectorPermuteOp.TRN2;
                case 0b011 -> Ir64VectorPermuteOp.ZIP1;
                case 0b111 -> Ir64VectorPermuteOp.ZIP2;
                default -> null;
            };
            if (op == null) {
                return null;
            }
            return new Ir64Op.VectorPermute(op, q, esz, rd, rn, rm);
        }
        // `TBL`/`TBX`: `bits[11:10]="00"` fixo (`bit11=0`,`bit10=0`); `bits[23:22]="00"` fixo
        // (parte do padrão real "000" junto com `bit21`, já garantido `0` pelo chamador) —
        // encoding reservado se `esz!=0` aqui (G8).
        int esz = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
        if (esz != 0) {
            return null;
        }
        int len = (opcode >>> 2) & 0b11;
        boolean tbx = ((opcode >>> 1) & 1) != 0;
        return new Ir64Op.VectorTableLookup(tbx, len, q, rd, rn, rm);
    }

    /// `LUTI2`/`LUTI4` (AdvSIMD lookup table, `FEAT_LUT`, B19.8) — chamado só quando `u=0`,
    /// `bit10=0`, `bit15=0` (checado pelo chamador). Discrimina as 3 famílias por bits[23:21]
    /// (`rn`/`rm`/`rd` já extraídos pelo chamador — layout idêntico ao resto de
    /// {@link #decodeAdvancedSimdExtractPermuteTable}) e, dentro de `LUTI4`, as 2 formas por
    /// bits[14:10] — ver as constantes `ADVSIMD_LUTI_*`. Retorna `null` (nunca decodifica errado,
    /// G8) para qualquer combinação que não bata EXATAMENTE um dos 4 padrões reais.
    private static Ir64Op decodeAdvancedSimdLookupTable(int word, int rm, int rn, int rd) {
        int highBits = (word >>> ADVSIMD_LUTI_HIGH_BITS_SHIFT) & ADVSIMD_LUTI_HIGH_BITS_MASK;
        int lowField = (word >>> ADVSIMD_LUTI_LOW_FIELD_SHIFT) & ADVSIMD_LUTI_LOW_FIELD_MASK;
        return switch (highBits) {
            case ADVSIMD_LUTI2_1B_PATTERN -> {
                if ((lowField & ADVSIMD_LUTI2_1B_FIXED_MASK) != ADVSIMD_LUTI2_1B_FIXED_VALUE) {
                    yield null;
                }
                int idx = lowField >>> ADVSIMD_LUTI2_1B_IDX_SHIFT;
                yield new Ir64Op.VectorLookupTable(false, ADVSIMD_LUTI_ESZ_BYTE, idx, rd, rn, rm);
            }
            case ADVSIMD_LUTI2_1H_PATTERN -> {
                if ((lowField & ADVSIMD_LUTI2_1H_FIXED_MASK) != ADVSIMD_LUTI2_1H_FIXED_VALUE) {
                    yield null;
                }
                int idx = lowField >>> ADVSIMD_LUTI2_1H_IDX_SHIFT;
                yield new Ir64Op.VectorLookupTable(false, ADVSIMD_LUTI_ESZ_HALFWORD, idx, rd, rn, rm);
            }
            case ADVSIMD_LUTI4_PATTERN -> {
                if ((lowField & ADVSIMD_LUTI4_1B_FIXED_MASK) == ADVSIMD_LUTI4_1B_FIXED_VALUE) {
                    int idx = lowField >>> ADVSIMD_LUTI4_1B_IDX_SHIFT;
                    yield new Ir64Op.VectorLookupTable(true, ADVSIMD_LUTI_ESZ_BYTE, idx, rd, rn, rm);
                }
                if ((lowField & ADVSIMD_LUTI4_2H_FIXED_MASK) == ADVSIMD_LUTI4_2H_FIXED_VALUE) {
                    int idx = lowField >>> ADVSIMD_LUTI4_2H_IDX_SHIFT;
                    yield new Ir64Op.VectorLookupTable(true, ADVSIMD_LUTI_ESZ_HALFWORD, idx, rd, rn, rm);
                }
                yield null;
            }
            default -> null;
        };
    }

    /// `DUP`/`INS`/`SMOV`/`UMOV` (AdvSIMD copy, B8.12) — quarta família do prefixo vetorial
    /// "01110", `bit21=0`, discriminada de EXT/permute/TBL/TBX (que ficam em
    /// {@link #decodeAdvancedSimdExtractPermuteTable}) por `bit10=1`. `U`(bit29) separa
    /// `INS_element` (`u=1`, dois registradores `V`, índice fonte em `si`) das outras quatro
    /// (`u=0`, `imm4` em bits[14:11] seleciona a instrução —
    /// {@link #ADVSIMD_COPY_DUP_ELEMENT}/{@link #ADVSIMD_COPY_DUP_GENERAL}/
    /// {@link #ADVSIMD_COPY_INS_GENERAL}/{@link #ADVSIMD_COPY_SMOV}/{@link #ADVSIMD_COPY_UMOV}).
    /// `esz`/índice vêm SEMPRE de `imm5` (bits[20:16], mesma posição de `Rm`/`di` alhures) pelo
    /// truque padrão do ARM DDI 0487 "AdvSIMD copy": `esz = LowestSetBit(imm5)`, `index =
    /// imm5 >>> (esz+1)` — `imm5==0` ou `esz>3` é reservado (G8). Encodings conferidos bit a bit
    /// contra corpus real (`aarch64-none-elf-as`/`objdump`, devkitA64).
    /// `DUP <V><d>, <Vn>.<T>[<index>]` (B19.6 bloco E, "Advanced SIMD scalar copy") — MESMO truque
    /// de `esz`/`index` de {@link #decodeAdvancedSimdCopy} (`esz = LowestSetBit(imm5)`, `index =
    /// imm5 >>> (esz+1)`), mas nunca `esz==3` exige `Q` aqui (não existe conceito de `Q` na forma
    /// escalar — todo `esz` é válido). `imm5==0` ou `esz>3` é reservado (`null`, G8 — o chamador
    /// decide o que fazer, mesmo padrão de {@link #decodeAdvancedSimdExtractPermuteTable}).
    private static Ir64Op decodeAdvancedSimdScalarDuplicateElement(int imm5, int rn, int rd) {
        int esz = imm5 == 0 ? -1 : Integer.numberOfTrailingZeros(imm5);
        if (esz < 0 || esz > ADVSIMD_INT_SCALAR_ESZ) {
            return null;
        }
        int index = imm5 >>> (esz + 1);
        return new Ir64Op.VectorDuplicateElementScalar(esz, rd, rn, index);
    }

    private Ir64Op decodeAdvancedSimdCopy(int word, long address, boolean q) {
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int imm5 = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        int esz = imm5 == 0 ? -1 : Integer.numberOfTrailingZeros(imm5);
        if (esz < 0 || esz > ADVSIMD_INT_SCALAR_ESZ) {
            // `imm5==0` (nenhum bit de tamanho marcado) ou `esz==4` (bit4 sozinho — tamanho maior
            // que doubleword não existe em AdvSIMD) — reservado.
            return null;
        }
        int index = imm5 >>> (esz + 1);
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        boolean bit15 = (opcode & ADVSIMD_EXTRACT_PERMUTE_BIT15_MASK) != 0;
        if (u) {
            // `INS_element`: `Q` é fixo em `1` no encoding real (literal, não uma escolha de
            // arranjo — inserir um elemento sempre referencia o registrador `V` inteiro); `bit15`
            // fixo em `0` (`si` ocupa só os 4 bits baixos de `opcode`, bits[14:11]).
            if (!q || bit15) {
                return null;
            }
            int srcIndex = (opcode & ADVSIMD_EXTRACT_IMM_MASK) >>> esz;
            return new Ir64Op.VectorInsertElement(esz, rd, rn, index, srcIndex);
        }
        if (bit15) {
            return null;
        }
        int imm4 = opcode & ADVSIMD_EXTRACT_IMM_MASK;
        return switch (imm4) {
            case ADVSIMD_COPY_DUP_ELEMENT -> (esz == ADVSIMD_INT_SCALAR_ESZ && !q)
                    ? null // doubleword exige `Q=1` (não existe arranjo "1D"), G8
                    : new Ir64Op.VectorDuplicateElement(q, esz, rd, rn, index);
            case ADVSIMD_COPY_DUP_GENERAL -> (esz == ADVSIMD_INT_SCALAR_ESZ && !q)
                    ? null
                    : new Ir64Op.VectorDuplicateGeneral(q, esz, rd, rn);
            case ADVSIMD_COPY_INS_GENERAL -> !q
                    ? null // `Q=1` fixo no encoding real, mesma regra de `INS_element`
                    : new Ir64Op.VectorInsertGeneral(esz, rd, rn, index);
            case ADVSIMD_COPY_SMOV -> (esz == ADVSIMD_INT_SCALAR_ESZ || (esz == 2 && !q))
                    ? null // `SMOV` não existe p/ doubleword; `esz=2`(word) só sign-estende p/ `Xd`
                    : new Ir64Op.VectorMoveElement(true, q, esz, rd, rn, index);
            case ADVSIMD_COPY_UMOV -> q != (esz == ADVSIMD_INT_SCALAR_ESZ)
                    ? null // `Q` é sempre `esz==3` p/ `UMOV` (sem forma "estendida" redundante)
                    : new Ir64Op.VectorMoveElement(false, q, esz, rd, rn, index);
            default -> null;
        };
    }

    /// "AdvSIMD three same"/"three same pairwise" (`bit10=1`): opcodes das duas famílias NUNCA
    /// colidem entre si (conferido contra `a64.decode` real), então um único `switch` resolve as
    /// duas. A forma ESCALAR só aceita o subconjunto realmente definido pelo manual, e cada
    /// subconjunto tem uma restrição de `esz` DIFERENTE (`ADD_s`/`CM**_s`/`SSHL_s`/`SRSHL_s` são
    /// D-only; `SQADD_s`/`SQSHL_s`/`SQRSHL_s` aceitam qualquer tamanho; `SQDMULH_s`/`SQRDMULH_s`
    /// só H/S) — validado por {@link #validateScalarThreeSameEsz}, não mais um simples booleano
    /// "tem forma escalar" (B8.7 só precisava do booleano porque todo escalar que tratava era
    /// D-only).
    private Ir64Op decodeAdvancedSimdThreeSameShape(int word, long address, boolean scalar, boolean q, int esz,
            boolean u, int opcode, int rn, int rd, int rm) {
        // B8.18: "AdvSIMD three same" LÓGICO (`AND`/`BIC`/`ORR`/`ORN`/`EOR`/`BSL`/`BIT`/`BIF`) vive
        // no MESMO opcode fixo (`ADVSIMD_THREE_SAME_LOGICAL_OPCODE`) deste slot, mas o campo que
        // para o resto da tabela é `esz` aqui é só mais opcode (bitwise não distingue lane; `esz=0`
        // fixo no record, ver {@link #decodeVectorLogicalOpcode}). Sem forma escalar real (G8:
        // `scalar` cai no `throw unsupported` do fim deste método).
        if (!scalar && opcode == ADVSIMD_THREE_SAME_LOGICAL_OPCODE) {
            Ir64VectorThreeSameOp logicalOp = decodeVectorLogicalOpcode(u, esz);
            if (logicalOp != null) {
                return new Ir64Op.VectorArithmeticThreeSame(logicalOp, false, q, 0, rd, rn, rm);
            }
            throw unsupported(word, address);
        }
        Ir64VectorThreeSameOp threeSameOp = decodeVectorThreeSameOpcode(u, opcode);
        if (threeSameOp != null) {
            if (scalar) {
                validateScalarThreeSameEsz(word, address, threeSameOp, esz);
            }
            if ((threeSameOp == Ir64VectorThreeSameOp.MUL || threeSameOp == Ir64VectorThreeSameOp.MLA
                    || threeSameOp == Ir64VectorThreeSameOp.MLS) && esz == ADVSIMD_INT_SCALAR_ESZ) {
                // `MUL`/`MLA`/`MLS` não têm forma doubleword real (ARM DDI 0487); G8.
                throw unsupported(word, address);
            }
            if (threeSameOp == Ir64VectorThreeSameOp.PMUL && esz != 0) {
                // `PMUL` só existe em `byte` — o campo `size` desta instrução é FIXO em `00` no
                // encoding real, não um campo livre de 2 bits como o resto desta tabela (G8).
                throw unsupported(word, address);
            }
            return new Ir64Op.VectorArithmeticThreeSame(threeSameOp, scalar, q, esz, rd, rn, rm);
        }
        if (!scalar) {
            Ir64VectorPairwiseOp pairwiseOp = decodeVectorPairwiseOpcode(u, opcode);
            if (pairwiseOp != null) {
                return new Ir64Op.VectorArithmeticPairwise(pairwiseOp, q, esz, rd, rn, rm);
            }
        }
        // B8.9: "AdvSIMD three same (FP)"/"three same pairwise (FP)" — MESMO prefixo/bit10 do
        // inteiro, opcodes NUNCA colidem com a tabela inteira (conferido exaustivamente contra
        // `a64.decode` real: os opcodes FP começam sempre em `0b11000`, acima do maior opcode
        // inteiro desta tabela). `esz` aqui é ainda o valor cru bits[23:22], que para FP precisa
        // ser desmontado em `a`(bit23, discriminador de opcode)/`sz`(bit22, tamanho real).
        boolean a = ((esz >>> 1) & 1) != 0;
        int floatEsz = 2 + (esz & 1);
        // B19.13 (`FEAT_FHM`): `FMLAL_v`/`FMLSL_v` (`opcode=0b1_1101`, `u=0`) e `FMLAL2_v`/
        // `FMLSL2_v` (`opcode=0b1_1001`, `u=1`) reaproveitam o MESMO espaço "three same (FP)"
        // normal, em chaves `(u,opcode)` que `decodeVectorFpThreeSameOpcode` não usa (conferido bit
        // a bit: opcode `0b1_1101` só mapeia `key=0b100/0b110` = `FACGE`/`FACGT`, opcode `0b1_1001`
        // só mapeia `key=0b000/0b010` = `MLA`/`MLS` — nenhum bate `key=0/0b010`/`0b100`/`0b110` nas
        // combinações que `u`/`a` produzem aqui, sem colisão real). `a`(bit23) escolhe soma
        // (`FMLAL`/`FMLAL2`) ou subtração (`FMLSL`/`FMLSL2`); `sz`(bit22, `esz&1`) é sempre `0` no
        // encoding real — `1` é reservado (G8, cai no `throw` do fim deste método). Sem forma
        // escalar real. `q` aqui controla largura de VERDADE (`Vd.2S`/`Vd.4S`), diferente de
        // `BFMLALB`/`BFMLALT` (B19.7) — ver Javadoc de {@link Ir64Op.VectorFpMultiplyAddLong}.
        if (!scalar && (esz & 1) == 0 && architecture.has(Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG)) {
            if (opcode == ADVSIMD_FHM_THREE_SAME_OPCODE_LOW && !u) {
                return new Ir64Op.VectorFpMultiplyAddLong(q, false, a, rd, rn, rm);
            }
            if (opcode == ADVSIMD_FHM_THREE_SAME_OPCODE_HIGH && u) {
                return new Ir64Op.VectorFpMultiplyAddLong(q, true, a, rd, rn, rm);
            }
        }
        // B19.2: a forma "three same (FP)" TAMBÉM tem forma AdvSIMD-escalar (`FMULX_s`/`FCMEQ_s`/
        // `FCMGE_s`/`FCMGT_s`/`FACGE_s`/`FACGT_s`/`FABD_s`/`FRECPS_s`/`FRSQRTS_s`) — MESMO triplo
        // `(u,a,opcode)` da vetorial (conferido contra corpus real devkitA64). As demais entradas de
        // `decodeVectorFpThreeSameOpcode` (`ADD`/`SUB`/`DIV`/`MUL`/`MAX`/`MIN`/`MAXNM`/`MINNM`/`MLA`/
        // `MLS`) NÃO têm forma escalar real: com prefixo escalar, esses encodings são reservados ⇒
        // `unsupported` (G8), nunca `VectorFpArithmeticThreeSame` nem a forma vetorial.
        // B19.11e (`FEAT_FP8`): `FSCALE_sd` vive no MESMO opcode ({@link
        // #ADVSIMD_FP8_SCALE_OPCODE_SD}) que `DIV`/`RECPS`/`RSQRTS` em
        // {@link #decodeVectorFpThreeSameOpcode}, discriminado pela key `(u=1,a=1)=0b110` que
        // NENHUM dos três usa (medido bit a bit: `decodeVectorFpThreeSameOpcode` já devolve `null`
        // para essa key — não é um misdecode a corrigir ali, é decode ausente a acrescentar aqui).
        // Sem forma escalar real.
        if (!scalar && opcode == ADVSIMD_FP8_SCALE_OPCODE_SD && u && a
                && architecture.has(Aarch64Feature.FP8)) {
            return new Ir64Op.VectorFpScaleByInt(q, floatEsz, rd, rn, rm);
        }
        Ir64VectorFpThreeSameOp fpOp = decodeVectorFpThreeSameOpcode(u, a, opcode);
        if (fpOp != null) {
            if (scalar && !fpThreeSameOpHasScalarForm(fpOp)) {
                throw unsupported(word, address);
            }
            return new Ir64Op.VectorFpArithmeticThreeSame(fpOp, scalar, q, floatEsz, rd, rn, rm);
        }
        // A "three same pairwise (FP)" vetorial NÃO tem forma escalar aqui — a `FADDP_s`/etc mora na
        // classe "AdvSIMD scalar pairwise" (`bit10=0`), tratada em {@link #decodeAdvancedSimdInteger}.
        if (!scalar) {
            Ir64VectorFpPairwiseOp fpPairwiseOp = decodeVectorFpPairwiseOpcode(u, a, opcode);
            if (fpPairwiseOp != null) {
                return new Ir64Op.VectorFpArithmeticPairwise(fpPairwiseOp, false, q, floatEsz, rd, rn, rm);
            }
        }
        throw unsupported(word, address);
    }

    /// AdvSIMD "three same (FP)" — tabela `(u, a, opcode)` conferida linha a linha contra
    /// `a64.decode` real do QEMU (`FADD_v`/.../`FRSQRTS_v`, formas `sd` só — `h`/meia-precisão
    /// excluída, `FEAT_FP16`). `a` é o bit23 (ver {@link #ADVSIMD_FP_A_BIT_SHIFT}), NUNCA um
    /// tamanho de elemento.
    private static Ir64VectorFpThreeSameOp decodeVectorFpThreeSameOpcode(boolean u, boolean a, int opcode) {
        int key = (u ? 0b100 : 0) | (a ? 0b010 : 0);
        return switch (opcode) {
            case 0b1_1010 -> switch (key) {
                case 0b000 -> Ir64VectorFpThreeSameOp.ADD;
                case 0b010 -> Ir64VectorFpThreeSameOp.SUB;
                case 0b110 -> Ir64VectorFpThreeSameOp.ABD;
                default -> null;
            };
            case 0b1_1111 -> switch (key) {
                case 0b100 -> Ir64VectorFpThreeSameOp.DIV;
                case 0b000 -> Ir64VectorFpThreeSameOp.RECPS;
                case 0b010 -> Ir64VectorFpThreeSameOp.RSQRTS;
                default -> null;
            };
            case 0b1_1011 -> switch (key) {
                case 0b100 -> Ir64VectorFpThreeSameOp.MUL;
                case 0b000 -> Ir64VectorFpThreeSameOp.MULX;
                default -> null;
            };
            case 0b1_1110 -> switch (key) {
                case 0b000 -> Ir64VectorFpThreeSameOp.MAX;
                case 0b010 -> Ir64VectorFpThreeSameOp.MIN;
                default -> null;
            };
            case 0b1_1000 -> switch (key) {
                case 0b000 -> Ir64VectorFpThreeSameOp.MAXNM;
                case 0b010 -> Ir64VectorFpThreeSameOp.MINNM;
                default -> null;
            };
            case 0b1_1001 -> switch (key) {
                case 0b000 -> Ir64VectorFpThreeSameOp.MLA;
                case 0b010 -> Ir64VectorFpThreeSameOp.MLS;
                default -> null;
            };
            case 0b1_1100 -> switch (key) {
                case 0b000 -> Ir64VectorFpThreeSameOp.CMEQ;
                case 0b100 -> Ir64VectorFpThreeSameOp.CMGE;
                case 0b110 -> Ir64VectorFpThreeSameOp.CMGT;
                default -> null;
            };
            case 0b1_1101 -> switch (key) {
                case 0b100 -> Ir64VectorFpThreeSameOp.FACGE;
                case 0b110 -> Ir64VectorFpThreeSameOp.FACGT;
                default -> null;
            };
            default -> null;
        };
    }

    /// AdvSIMD "three same pairwise (FP)" — mesma disciplina de
    /// {@link #decodeVectorFpThreeSameOpcode}. Opcodes reaproveitados (`26`/`30`/`24`) só colidem
    /// com combinações `(u,a)` NÃO usadas pela tabela não-pareada (conferido acima).
    private static Ir64VectorFpPairwiseOp decodeVectorFpPairwiseOpcode(boolean u, boolean a, int opcode) {
        if (!u) {
            return null;
        }
        return switch (opcode) {
            case 0b1_1010 -> a ? null : Ir64VectorFpPairwiseOp.ADD;
            case 0b1_1110 -> a ? Ir64VectorFpPairwiseOp.MIN : Ir64VectorFpPairwiseOp.MAX;
            case 0b1_1000 -> a ? Ir64VectorFpPairwiseOp.MINNM : Ir64VectorFpPairwiseOp.MAXNM;
            default -> null;
        };
    }

    /// B19.2: quais operações de {@link #decodeVectorFpThreeSameOpcode} têm forma AdvSIMD-ESCALAR
    /// real (`ARM DDI 0487`, "Advanced SIMD scalar three same FP"). As de fora (`ADD`/`SUB`/`DIV`/
    /// `MUL`/`MAX`/`MIN`/`MAXNM`/`MINNM`/`MLA`/`MLS`) só existem vetoriais — um encoding escalar que
    /// case uma delas é reservado ⇒ `unsupported` (G8).
    private static boolean fpThreeSameOpHasScalarForm(Ir64VectorFpThreeSameOp op) {
        return switch (op) {
            case MULX, ABD, RECPS, RSQRTS, CMEQ, CMGE, CMGT, FACGE, FACGT -> true;
            case ADD, SUB, MUL, DIV, MAX, MIN, MAXNM, MINNM, MLA, MLS -> false;
        };
    }

    /// B19.3: quais operações de {@link #decodeVectorFpUnaryRmZeroOpcode}/
    /// {@link #decodeVectorFpUnaryRmOneOpcode} têm forma AdvSIMD-ESCALAR real ("two-register
    /// miscellaneous" escalar + conversões `@icvt` escalares). As de fora (`ABS`/`NEG` FP,
    /// `SQRT`, `RINTx`) só existem vetoriais — ou já são {@link Ir64Op.Fp64Alu}/
    /// {@link Ir64Op.Fp64Round} escalares por outro encoding — então um encoding escalar que
    /// case uma delas é reservado ⇒ `unsupported` (G8). `FRECPX`/`FCVTXN` NUNCA passam por aqui
    /// (têm `if` explícito no decoder), logo `false`.
    private static boolean fpUnaryOpHasScalarForm(Ir64VectorFpUnaryOp op) {
        return switch (op) {
            case CMGT0, CMGE0, CMEQ0, CMLE0, CMLT0,
                 RECPE, RSQRTE,
                 SCVTF, UCVTF,
                 FCVTNS, FCVTNU, FCVTPS, FCVTPU, FCVTMS, FCVTMU, FCVTZS, FCVTZU, FCVTAS, FCVTAU -> true;
            case ABS, NEG, SQRT, RINTN, RINTM, RINTP, RINTZ, RINTA, RINTX, RINTI, FRECPX, FCVTXN,
                 RINT32Z, RINT32X, RINT64Z, RINT64X -> false;
        };
    }

    /// B19.2: AdvSIMD "scalar pairwise (FP)" (`FADDP_s`/`FMAXP_s`/`FMINP_s`/`FMAXNMP_s`/`FMINNMP_s`,
    /// `ARM DDI 0487` C4.1.95 `01 U 11110 0 sz 11000 opcode 10 Rn Rd`). Classe PRÓPRIA — `opcode`
    /// (bits[15:11]) tem valores diferentes de {@link #decodeVectorFpPairwiseOpcode} (não-pareada),
    /// então tabela separada. `a` é o bit23 (`FMAXP`/`FMAXNMP` → `a=0`, `FMINP`/`FMINNMP` → `a=1`).
    /// Conferido bit a bit contra corpus real (devkitA64). **B19.5.3**: esta tabela vale IGUAL para
    /// as formas `_h` (`FEAT_FP16`, `U=0`) — o `U` só distingue `_sd`/`_h`, nunca o `opcode`/`a`;
    /// por isso o parâmetro `u` saiu da assinatura e o chamador decide o gate.
    private static Ir64VectorFpPairwiseOp decodeVectorFpScalarPairwiseOpcode(boolean a, int opcode) {
        return switch (opcode) {
            case 0b1_1011 -> a ? null : Ir64VectorFpPairwiseOp.ADD;
            case 0b1_1111 -> a ? Ir64VectorFpPairwiseOp.MIN : Ir64VectorFpPairwiseOp.MAX;
            case 0b1_1001 -> a ? Ir64VectorFpPairwiseOp.MINNM : Ir64VectorFpPairwiseOp.MAXNM;
            default -> null;
        };
    }

    /// `esz` mínimo/máximo aceito por cada subconjunto ESCALAR de "three same" (B8.8) — nomeado em
    /// vez de literal solto (G6): `H`/`S` são os únicos tamanhos reais de `SQDMULH_s`/`SQRDMULH_s`.
    private static final int ADVSIMD_ESZ_HALFWORD = 1;
    private static final int ADVSIMD_ESZ_WORD = 2;

    private void validateScalarThreeSameEsz(int word, long address, Ir64VectorThreeSameOp op, int esz) {
        switch (op) {
            case ADD, SUB, CMGT, CMHI, CMGE, CMHS, CMTST, CMEQ, SSHL, USHL, SRSHL, URSHL -> {
                if (esz != ADVSIMD_INT_SCALAR_ESZ) {
                    // Real: `ADD_s`/`SSHL_s`/... exigem `size=11` literalmente no encoding — G8.
                    throw unsupported(word, address);
                }
            }
            case SQADD, UQADD, SQSUB, UQSUB, SQSHL, UQSHL, SQRSHL, UQRSHL -> {
                // Aceitam B/H/S/D — sem restrição adicional (`@rrr_e` real, campo `size` livre).
            }
            case SQDMULH, SQRDMULH -> {
                if (esz != ADVSIMD_ESZ_HALFWORD && esz != ADVSIMD_ESZ_WORD) {
                    throw unsupported(word, address);
                }
            }
            default ->
                // `SHADD`/`SMAX`/`MUL`/... não têm equivalente escalar puro real.
                throw unsupported(word, address);
        }
    }

    private static Ir64VectorThreeSameOp decodeVectorThreeSameOpcode(boolean u, int opcode) {
        return switch (opcode) {
            case 0b1_0000 -> u ? Ir64VectorThreeSameOp.SUB : Ir64VectorThreeSameOp.ADD;
            case 0b0_0110 -> u ? Ir64VectorThreeSameOp.CMHI : Ir64VectorThreeSameOp.CMGT;
            case 0b0_0111 -> u ? Ir64VectorThreeSameOp.CMHS : Ir64VectorThreeSameOp.CMGE;
            case 0b1_0001 -> u ? Ir64VectorThreeSameOp.CMEQ : Ir64VectorThreeSameOp.CMTST;
            case 0b0_0000 -> u ? Ir64VectorThreeSameOp.UHADD : Ir64VectorThreeSameOp.SHADD;
            case 0b0_0100 -> u ? Ir64VectorThreeSameOp.UHSUB : Ir64VectorThreeSameOp.SHSUB;
            case 0b0_0010 -> u ? Ir64VectorThreeSameOp.URHADD : Ir64VectorThreeSameOp.SRHADD;
            case 0b0_1100 -> u ? Ir64VectorThreeSameOp.UMAX : Ir64VectorThreeSameOp.SMAX;
            case 0b0_1101 -> u ? Ir64VectorThreeSameOp.UMIN : Ir64VectorThreeSameOp.SMIN;
            case 0b0_1110 -> u ? Ir64VectorThreeSameOp.UABD : Ir64VectorThreeSameOp.SABD;
            case 0b0_1111 -> u ? Ir64VectorThreeSameOp.UABA : Ir64VectorThreeSameOp.SABA;
            case 0b1_0011 -> u ? Ir64VectorThreeSameOp.PMUL : Ir64VectorThreeSameOp.MUL;
            case 0b1_0010 -> u ? Ir64VectorThreeSameOp.MLS : Ir64VectorThreeSameOp.MLA;
            // B8.8: saturante/deslocamento por registrador/multiplicação dobrada.
            case 0b0_0001 -> u ? Ir64VectorThreeSameOp.UQADD : Ir64VectorThreeSameOp.SQADD;
            case 0b0_0101 -> u ? Ir64VectorThreeSameOp.UQSUB : Ir64VectorThreeSameOp.SQSUB;
            case 0b0_1000 -> u ? Ir64VectorThreeSameOp.USHL : Ir64VectorThreeSameOp.SSHL;
            case 0b0_1010 -> u ? Ir64VectorThreeSameOp.URSHL : Ir64VectorThreeSameOp.SRSHL;
            case 0b0_1001 -> u ? Ir64VectorThreeSameOp.UQSHL : Ir64VectorThreeSameOp.SQSHL;
            case 0b0_1011 -> u ? Ir64VectorThreeSameOp.UQRSHL : Ir64VectorThreeSameOp.SQRSHL;
            case 0b1_0110 -> u ? Ir64VectorThreeSameOp.SQRDMULH : Ir64VectorThreeSameOp.SQDMULH;
            default -> null;
        };
    }

    /// B8.18: opcode fixo (bits[15:11]) compartilhado por toda a família "AdvSIMD three same
    /// (lógico)" — ver {@link #decodeVectorLogicalOpcode}.
    private static final int ADVSIMD_THREE_SAME_LOGICAL_OPCODE = 0b0_0011;

    /// `AND`/`BIC`/`ORR`/`ORN` (`u=0`)/`EOR`/`BSL`/`BIT`/`BIF` (`u=1`) — B8.18. O campo que para o
    /// resto de {@link #decodeVectorThreeSameOpcode} é `esz` (bits[23:22]) aqui é a ÚNICA coisa que
    /// distingue as 4 mnemônicas de cada `u` (conferido bit a bit contra `a64.decode` real do QEMU:
    /// `AND_v`=`u0,size00`; `BIC_v`=`u0,size01`; `ORR_v`=`u0,size10`; `ORN_v`=`u0,size11`;
    /// `EOR_v`=`u1,size00`; `BSL_v`=`u1,size01`; `BIT_v`=`u1,size10`; `BIF_v`=`u1,size11`) — nenhuma
    /// combinação de `(u,esz)` neste opcode é reservada, `default` nunca dispara de verdade.
    private static Ir64VectorThreeSameOp decodeVectorLogicalOpcode(boolean u, int esz) {
        if (!u) {
            return switch (esz) {
                case 0 -> Ir64VectorThreeSameOp.AND;
                case 1 -> Ir64VectorThreeSameOp.BIC;
                case 2 -> Ir64VectorThreeSameOp.ORR;
                case 3 -> Ir64VectorThreeSameOp.ORN;
                default -> null;
            };
        }
        return switch (esz) {
            case 0 -> Ir64VectorThreeSameOp.EOR;
            case 1 -> Ir64VectorThreeSameOp.BSL;
            case 2 -> Ir64VectorThreeSameOp.BIT;
            case 3 -> Ir64VectorThreeSameOp.BIF;
            default -> null;
        };
    }

    private static Ir64VectorPairwiseOp decodeVectorPairwiseOpcode(boolean u, int opcode) {
        return switch (opcode) {
            case 0b1_0111 -> u ? null : Ir64VectorPairwiseOp.ADD;
            case 0b1_0100 -> u ? Ir64VectorPairwiseOp.UMAX : Ir64VectorPairwiseOp.SMAX;
            case 0b1_0101 -> u ? Ir64VectorPairwiseOp.UMIN : Ir64VectorPairwiseOp.SMIN;
            default -> null;
        };
    }

    private static Ir64VectorUnaryOp decodeVectorUnaryOpcode(boolean u, int opcode, boolean scalar) {
        Ir64VectorUnaryOp op = switch (opcode) {
            case 0b1_0111 -> u ? Ir64VectorUnaryOp.NEG : Ir64VectorUnaryOp.ABS;
            case 0b1_0001 -> u ? Ir64VectorUnaryOp.CMGE0 : Ir64VectorUnaryOp.CMGT0;
            case 0b1_0011 -> u ? Ir64VectorUnaryOp.CMLE0 : Ir64VectorUnaryOp.CMEQ0;
            case 0b1_0101 -> u ? null : Ir64VectorUnaryOp.CMLT0;
            case 0b0_0101 -> u ? Ir64VectorUnaryOp.UADDLP : Ir64VectorUnaryOp.SADDLP;
            case 0b0_1101 -> u ? Ir64VectorUnaryOp.UADALP : Ir64VectorUnaryOp.SADALP;
            // B8.8: acumulação saturante — `SUQADD`/`USQADD`.
            case 0b0_0111 -> u ? Ir64VectorUnaryOp.USQADD : Ir64VectorUnaryOp.SUQADD;
            // B8.18: `SQABS`/`SQNEG` (MESMO slot de `ABS`/`NEG` acima, opcode diferente) e
            // `CLS`/`CLZ` vetoriais — os dois aceitam `esz` livre (`0`-`3`), sem restrição
            // adicional além da já aplicada pelo resto desta tabela.
            case 0b0_1111 -> u ? Ir64VectorUnaryOp.SQNEG : Ir64VectorUnaryOp.SQABS;
            case 0b0_1001 -> u ? Ir64VectorUnaryOp.CLZ : Ir64VectorUnaryOp.CLS;
            // B8.20: `REV64`(`u=0`)/`REV32`(`u=1`) compartilham o MESMO opcode; `REV16` só existe
            // `u=0` (`u=1` reservado, conferido contra corpus real).
            case 0b0_0001 -> u ? Ir64VectorUnaryOp.REV32 : Ir64VectorUnaryOp.REV64;
            case 0b0_0011 -> u ? null : Ir64VectorUnaryOp.REV16;
            default -> null;
        };
        if (scalar && op != null && (op == Ir64VectorUnaryOp.SADDLP || op == Ir64VectorUnaryOp.UADDLP
                || op == Ir64VectorUnaryOp.SADALP || op == Ir64VectorUnaryOp.UADALP
                || op == Ir64VectorUnaryOp.REV64 || op == Ir64VectorUnaryOp.REV32
                || op == Ir64VectorUnaryOp.REV16)) {
            // `SADDLP`/`UADDLP`/`SADALP`/`UADALP`/`REV64`/`REV32`/`REV16` não têm forma escalar
            // real (ARM DDI 0487).
            return null;
        }
        return op;
    }

    /// `esz` mínimo/máximo aceito pelas 3 famílias `REV*` (B8.20, sempre VETORIAL — a forma escalar
    /// já foi negada por {@link #decodeVectorUnaryOpcode} antes de chegar aqui, então este método
    /// não precisa checar `scalar`). Demais operações de {@link Ir64VectorUnaryOp} não têm restrição
    /// adicional além da já validada por {@link #validateScalarUnaryEsz}.
    private void validateVectorUnaryEsz(int word, long address, Ir64VectorUnaryOp op, int esz) {
        boolean valid = switch (op) {
            // Grupo de 64 bits — elemento word (esz=2) é o maior que cabe mais de uma vez; `esz=3`
            // seria um grupo de 1 elemento (no-op), reservado.
            case REV64 -> esz <= ADVSIMD_ESZ_WORD;
            // Grupo de 32 bits — só byte/half cabem mais de uma vez.
            case REV32 -> esz <= ADVSIMD_ESZ_HALFWORD;
            // Grupo de 16 bits — só byte cabe mais de uma vez.
            case REV16 -> esz == 0;
            default -> true;
        };
        if (!valid) {
            throw unsupported(word, address);
        }
    }

    /// `CNT`/`NOT`/`RBIT` (B8.18) — MESMO opcode (`ADVSIMD_TWO_REG_MISC_BYTE_ONLY_OPCODE`) dentro
    /// do slot "two-register miscellaneous", discriminados por `(u, esz)`: `esz` aqui NÃO é
    /// tamanho de elemento (as 3 só existem no arranjo byte), é só o resto do campo real que
    /// desambigua as mnemônicas — conferido bit a bit contra `a64.decode` real do QEMU
    /// (`CNT_v`=`u0,size00`; `NOT_v`=`u1,size00`; `RBIT_v`=`u1,size01`; `size1x` com qualquer `u`
    /// é reservado).
    private static Ir64VectorUnaryOp decodeVectorUnaryByteOnlyOpcode(boolean u, int esz) {
        if (!u) {
            return esz == 0 ? Ir64VectorUnaryOp.CNT : null;
        }
        return switch (esz) {
            case 0 -> Ir64VectorUnaryOp.NOT;
            case 1 -> Ir64VectorUnaryOp.RBIT;
            default -> null;
        };
    }

    /// `esz` mínimo/máximo aceito por cada subconjunto ESCALAR de "two-register miscellaneous"
    /// (B8.8): `ABS_s`/`NEG_s`/`CM**0_s` são D-only (herdado de B8.7); `SUQADD_s`/`USQADD_s`
    /// aceitam qualquer tamanho (`@r2r_e` real).
    private void validateScalarUnaryEsz(int word, long address, boolean scalar, Ir64VectorUnaryOp op, int esz) {
        if (!scalar) {
            return;
        }
        switch (op) {
            case ABS, NEG, CMEQ0, CMGT0, CMGE0, CMLT0, CMLE0 -> {
                if (esz != ADVSIMD_INT_SCALAR_ESZ) {
                    throw unsupported(word, address);
                }
            }
            case SUQADD, USQADD, SQABS, SQNEG -> {
                // Aceitam B/H/S/D — sem restrição adicional (B8.18: `SQABS_s`/`SQNEG_s` seguem a
                // mesma regra de `SUQADD_s`/`USQADD_s`, `@rr_e` real).
            }
            default ->
                // `SADDLP`/`UADDLP`/`SADALP`/`UADALP` já voltam `null` de
                // `decodeVectorUnaryOpcode` antes de chegar aqui quando `scalar`. `CLS`/`CLZ`/
                // `CNT`/`NOT`/`RBIT` (B8.18) não têm forma escalar real — cair aqui é o
                // comportamento CORRETO (G8) para uma tentativa de `scalar` inválida.
                throw unsupported(word, address);
        }
    }

    /// `SQXTN`/`SQXTUN`/`UQXTN` (B8.8, narrow unário saturante) — vive no MESMO opcode `01001` de
    /// `SQXTN`/`UQXTN` (`U` distingue) e `00101` só para `SQXTUN` (`U=1`). `U=0` nesse MESMO opcode
    /// `00101` é `XTN` (B8.20, SEM saturação) — achado real: o comentário original desta task
    /// (B8.8) supunha que fosse `FCVTN`, mas o corpus real (devkitA64) confirma `XTN`; `FCVTN`
    /// (conversão FP) vive em outro opcode, fora de escopo. `XTN` não tem forma escalar (`scalar`
    /// devolve `null`, G8).
    private static Ir64VectorNarrowUnaryOp decodeVectorNarrowUnaryOpcode(boolean u, int opcode, boolean scalar) {
        return switch (opcode) {
            case 0b0_1001 -> u ? Ir64VectorNarrowUnaryOp.UQXTN : Ir64VectorNarrowUnaryOp.SQXTN;
            case 0b0_0101 -> u ? Ir64VectorNarrowUnaryOp.SQXTUN : (scalar ? null : Ir64VectorNarrowUnaryOp.XTN);
            default -> null;
        };
    }

    /// AdvSIMD "two-register misc (FP)", slot `Rm=00000` (B8.9) — `FABS_v`/`FNEG_v`/`FCM**0_v`.
    /// Todas têm `a`(bit23)`=1` no encoding real; a tabela ainda recebe `a` explícito (em vez de
    /// assumir) para deixar claro que combinações com `a=0` neste slot são reservadas (G8, não
    /// alcançáveis por nenhuma linha do `switch`).
    private static Ir64VectorFpUnaryOp decodeVectorFpUnaryRmZeroOpcode(boolean u, boolean a, int opcode) {
        if (!a) {
            return null;
        }
        return switch (opcode) {
            case 0b1_1111 -> u ? Ir64VectorFpUnaryOp.NEG : Ir64VectorFpUnaryOp.ABS;
            case 0b1_1001 -> u ? Ir64VectorFpUnaryOp.CMGE0 : Ir64VectorFpUnaryOp.CMGT0;
            case 0b1_1011 -> u ? Ir64VectorFpUnaryOp.CMLE0 : Ir64VectorFpUnaryOp.CMEQ0;
            case 0b1_1101 -> u ? null : Ir64VectorFpUnaryOp.CMLT0;
            default -> null;
        };
    }

    /// AdvSIMD "two-register misc (FP)", slot `Rm=00001` (B8.9) — `FSQRT_v`/`FRINTx_v`/
    /// `FRECPE_v`/`FRSQRTE_v`/`SCVTF_vi`/`UCVTF_vi`/`FCVTxS_vi`/`FCVTxU_vi`. Tabela `(u,a,opcode)`
    /// conferida linha a linha contra `a64.decode` real do QEMU, formas `sd` só.
    private static Ir64VectorFpUnaryOp decodeVectorFpUnaryRmOneOpcode(boolean u, boolean a, int opcode) {
        int key = (u ? 0b10 : 0) | (a ? 0b01 : 0);
        return switch (opcode) {
            case 0b1_0001 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.RINTN;
                case 0b01 -> Ir64VectorFpUnaryOp.RINTP;
                case 0b10 -> Ir64VectorFpUnaryOp.RINTA;
                default -> null;
            };
            case 0b1_0011 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.RINTM;
                case 0b01 -> Ir64VectorFpUnaryOp.RINTZ;
                case 0b10 -> Ir64VectorFpUnaryOp.RINTX;
                case 0b11 -> Ir64VectorFpUnaryOp.RINTI;
                default -> null;
            };
            case 0b1_0101 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.FCVTNS;
                case 0b10 -> Ir64VectorFpUnaryOp.FCVTNU;
                case 0b01 -> Ir64VectorFpUnaryOp.FCVTPS;
                case 0b11 -> Ir64VectorFpUnaryOp.FCVTPU;
                default -> null;
            };
            case 0b1_0111 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.FCVTMS;
                case 0b10 -> Ir64VectorFpUnaryOp.FCVTMU;
                case 0b01 -> Ir64VectorFpUnaryOp.FCVTZS;
                case 0b11 -> Ir64VectorFpUnaryOp.FCVTZU;
                default -> null;
            };
            case 0b1_1001 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.FCVTAS;
                case 0b10 -> Ir64VectorFpUnaryOp.FCVTAU;
                default -> null;
            };
            case 0b1_1011 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.SCVTF;
                case 0b10 -> Ir64VectorFpUnaryOp.UCVTF;
                case 0b01 -> Ir64VectorFpUnaryOp.RECPE;
                case 0b11 -> Ir64VectorFpUnaryOp.RSQRTE;
                default -> null;
            };
            // B19.18 (`FEAT_FRINTTS`): `FRINT32Z_v`/`FRINT32X_v` — MESMO opcode nunca usado antes
            // desta task neste slot (`0b1_1101`), `a`(bit23) fixo em `0` no encoding real (só `u`
            // discrimina `Z`(0)/`X`(1); `esz`/tamanho vem do bit22, já extraído fora desta tabela
            // como `fpRmOneEsz`) — conferido bit a bit contra `a64.decode` real (achado registrado
            // no `## Contexto` da task).
            case 0b1_1101 -> switch (key) {
                case 0b00 -> Ir64VectorFpUnaryOp.RINT32Z;
                case 0b10 -> Ir64VectorFpUnaryOp.RINT32X;
                default -> null;
            };
            // B19.18: `FRINT64Z_v`/`FRINT64X_v` compartilham o MESMO opcode de `SQRT` (`0b1_1111`)
            // — `SQRT` exige `key==0b11` (`a=1`), `FRINT64*` exige `a=0` (`key==0b00`/`0b10`);
            // nunca colidem (conferido bit a bit).
            case 0b1_1111 -> switch (key) {
                case 0b11 -> Ir64VectorFpUnaryOp.SQRT;
                case 0b00 -> Ir64VectorFpUnaryOp.RINT64Z;
                case 0b10 -> Ir64VectorFpUnaryOp.RINT64X;
                default -> null;
            };
            default -> null;
        };
    }

    /// B19.18: quais valores de {@link Ir64VectorFpUnaryOp} são `FRINT32*`/`FRINT64*`
    /// (`FEAT_FRINTTS`) — únicos que precisam do gate de arquitetura dentro do slot narrow/widen
    /// (o resto da tabela é base ISA).
    private static boolean isDirectedRoundingToIntegral(Ir64VectorFpUnaryOp op) {
        return switch (op) {
            case RINT32Z, RINT32X, RINT64Z, RINT64X -> true;
            default -> false;
        };
    }

    /// B8.11: `AESE`(`opcode=9`)/`AESD`(`11`)/`AESMC`(`13`)/`AESIMC`(`15`) — os 4 valores ÍMPARES
    /// de `opcode` dentro do slot `Rm=`{@link #ADVSIMD_AES_RM}, conferidos bit a bit contra
    /// `a64.decode` real (seção "Cryptographic AES"/"Cryptographic two-register SHA" do QEMU).
    private static Ir64CryptoAesOp decodeCryptoAesOpcode(int opcode) {
        return switch (opcode) {
            case 0b0_1001 -> Ir64CryptoAesOp.AESE;
            case 0b0_1011 -> Ir64CryptoAesOp.AESD;
            case 0b0_1101 -> Ir64CryptoAesOp.AESMC;
            case 0b0_1111 -> Ir64CryptoAesOp.AESIMC;
            default -> null;
        };
    }

    /// B8.11b: `SHA1H`(`opcode=1`)/`SHA1SU1`(`3`)/`SHA256SU0`(`5`) — "Cryptographic two-register
    /// SHA" vive no MESMO slot `Rm=`{@link #ADVSIMD_AES_RM} que `AESE`/etc (`q=1`/`u=0`/`esz=0`,
    /// checado pelo chamador), distinguido pelos mesmos 5 bits de `opcode` de
    /// {@link #decodeCryptoAesOpcode} — valores ÍMPARES baixos (`1`/`3`/`5`), nenhum colide com os
    /// 4 valores ÍMPARES altos (`9`/`11`/`13`/`15`) de AES. Conferido contra corpus real
    /// (`aarch64-none-elf-as`/`objdump`, devkitA64, `.arch armv8-a+crypto`).
    private static Ir64CryptoShaTwoRegisterOp decodeCryptoShaTwoRegisterOpcode(int opcode) {
        return switch (opcode) {
            case 0b0_0001 -> Ir64CryptoShaTwoRegisterOp.SHA1H;
            case 0b0_0011 -> Ir64CryptoShaTwoRegisterOp.SHA1SU1;
            case 0b0_0101 -> Ir64CryptoShaTwoRegisterOp.SHA256SU0;
            default -> null;
        };
    }

    /// B8.11b: `SHA1C`(`0`)/`SHA1P`(`4`)/`SHA1M`(`8`)/`SHA1SU0`(`12`)/`SHA256H`(`16`)/
    /// `SHA256H2`(`20`)/`SHA256SU1`(`24`) — os 7 valores do campo `opcode` de 6 bits (bits[15:10])
    /// de "Cryptographic three-register SHA", conferidos contra corpus real (mesma sessão de
    /// `decodeCryptoShaTwoRegisterOpcode`).
    private static Ir64CryptoShaThreeRegisterOp decodeCryptoShaThreeRegisterOpcode(int opcode) {
        return switch (opcode) {
            case 0b00_0000 -> Ir64CryptoShaThreeRegisterOp.SHA1C;
            case 0b00_0100 -> Ir64CryptoShaThreeRegisterOp.SHA1P;
            case 0b00_1000 -> Ir64CryptoShaThreeRegisterOp.SHA1M;
            case 0b00_1100 -> Ir64CryptoShaThreeRegisterOp.SHA1SU0;
            case 0b01_0000 -> Ir64CryptoShaThreeRegisterOp.SHA256H;
            case 0b01_0100 -> Ir64CryptoShaThreeRegisterOp.SHA256H2;
            case 0b01_1000 -> Ir64CryptoShaThreeRegisterOp.SHA256SU1;
            default -> null;
        };
    }

    private static Ir64VectorAcrossLanesOp decodeVectorAcrossLanesOpcode(boolean u, int rm, int opcode) {
        return switch (opcode) {
            case 0b1_0111 -> u ? null : Ir64VectorAcrossLanesOp.ADDV;
            case 0b0_0111 -> u ? Ir64VectorAcrossLanesOp.UADDLV : Ir64VectorAcrossLanesOp.SADDLV;
            // `SMAXV`/`SMINV`/`UMAXV`/`UMINV` compartilham `opcode=0b10101` — só o bit baixo de
            // `Rm` distingue MAX (`0`) de MIN (`1`), conferido contra o corpus real.
            case 0b1_0101 -> switch ((rm & 1) << 1 | (u ? 1 : 0)) {
                case 0b00 -> Ir64VectorAcrossLanesOp.SMAXV;
                case 0b01 -> Ir64VectorAcrossLanesOp.UMAXV;
                case 0b10 -> Ir64VectorAcrossLanesOp.SMINV;
                default -> Ir64VectorAcrossLanesOp.UMINV;
            };
            default -> null;
        };
    }

    /// AdvSIMD "across lanes (FP)" (B8.10): `opcode` distingue NM (`0b1_1001`) de não-NM
    /// (`0b1_1111`); `esz` bit1 (`a`, mesma técnica de {@link #ADVSIMD_FP_A_BIT_SHIFT}) distingue
    /// MAX (`0`) de MIN (`1`) — conferido linha a linha contra `a64.decode` real do QEMU
    /// (`FMAXNMV_s`/`FMINNMV_s`/`FMAXV_s`/`FMINV_s`). Só a forma simples-precisão (`esz` bit0
    /// sempre `0` no encoding real desta família — `Q`/`U`=1 fixos, checados pelo chamador).
    private static Ir64VectorFpAcrossLanesOp decodeVectorFpAcrossLanesOpcode(int opcode, int esz) {
        boolean a = ((esz >>> 1) & 1) != 0;
        return switch (opcode) {
            case 0b1_1001 -> a ? Ir64VectorFpAcrossLanesOp.FMINNMV : Ir64VectorFpAcrossLanesOp.FMAXNMV;
            case 0b1_1111 -> a ? Ir64VectorFpAcrossLanesOp.FMINV : Ir64VectorFpAcrossLanesOp.FMAXV;
            default -> null;
        };
    }

    /// "AdvSIMD three different" (`bit10=0`, `Rm` livre): alargando (`Rd` em `esz+1` cheio),
    /// largo+estreito (`Rd`/`Rn` já em `esz+1`, só `Rm` estreito) ou estreitando (`Rn`/`Rm` em
    /// `esz+1`, `Rd` em `esz`) — os 3 conjuntos de opcodes NUNCA colidem entre si (conferido contra
    /// `a64.decode` real).
    private Ir64Op decodeAdvancedSimdThreeDifferent(int word, long address, boolean scalar, boolean q, int esz,
            boolean u, int opcode, int rn, int rd, int rm) {
        Ir64VectorWideningOp wideningOp = switch (opcode) {
            case 0b1_1000 -> u ? Ir64VectorWideningOp.UMULL : Ir64VectorWideningOp.SMULL;
            case 0b1_0000 -> u ? Ir64VectorWideningOp.UMLAL : Ir64VectorWideningOp.SMLAL;
            case 0b1_0100 -> u ? Ir64VectorWideningOp.UMLSL : Ir64VectorWideningOp.SMLSL;
            case 0b0_0000 -> u ? Ir64VectorWideningOp.UADDL : Ir64VectorWideningOp.SADDL;
            case 0b0_0100 -> u ? Ir64VectorWideningOp.USUBL : Ir64VectorWideningOp.SSUBL;
            case 0b0_1010 -> u ? Ir64VectorWideningOp.UABAL : Ir64VectorWideningOp.SABAL;
            case 0b0_1110 -> u ? Ir64VectorWideningOp.UABDL : Ir64VectorWideningOp.SABDL;
            // B8.8: `SQDMULL`/`SQDMLAL`/`SQDMLSL` — SEM forma `U=1` (não existe `UQDMULL`, `U=1`
            // com estes opcodes é reservado, `u?null:...` rejeita).
            case 0b1_1010 -> u ? null : Ir64VectorWideningOp.SQDMULL;
            case 0b1_0010 -> u ? null : Ir64VectorWideningOp.SQDMLAL;
            case 0b1_0110 -> u ? null : Ir64VectorWideningOp.SQDMLSL;
            default -> null;
        };
        if (wideningOp != null) {
            boolean isSaturatingDoubling = wideningOp == Ir64VectorWideningOp.SQDMULL
                    || wideningOp == Ir64VectorWideningOp.SQDMLAL || wideningOp == Ir64VectorWideningOp.SQDMLSL;
            if (isSaturatingDoubling && esz == 0) {
                // `SQDMULL`/`SQDMLAL`/`SQDMLSL` só existem H→S/S→D — sem forma `byte` (G8).
                throw unsupported(word, address);
            }
            return new Ir64Op.VectorArithmeticWidening(wideningOp, scalar, q, esz, rd, rn, rm);
        }
        Ir64VectorWideOp wideOp = switch (opcode) {
            case 0b0_0010 -> u ? Ir64VectorWideOp.UADDW : Ir64VectorWideOp.SADDW;
            case 0b0_0110 -> u ? Ir64VectorWideOp.USUBW : Ir64VectorWideOp.SSUBW;
            default -> null;
        };
        if (wideOp != null) {
            return new Ir64Op.VectorArithmeticWide(wideOp, q, esz, rd, rn, rm);
        }
        Ir64VectorNarrowOp narrowOp = switch (opcode) {
            case 0b0_1000 -> u ? Ir64VectorNarrowOp.RADDHN : Ir64VectorNarrowOp.ADDHN;
            case 0b0_1100 -> u ? Ir64VectorNarrowOp.RSUBHN : Ir64VectorNarrowOp.SUBHN;
            default -> null;
        };
        if (narrowOp != null) {
            return new Ir64Op.VectorArithmeticNarrow(narrowOp, q, esz, rd, rn, rm);
        }
        // B8.11: `PMULL`/`PMULL2` (`opcode=0b11100`, `u` sempre `false` no encoding real — mesmo
        // slot que a triagem original desta task previa, conferido bit a bit contra `a64.decode`
        // real). `esz` distingue `p8` (`00`, 8 lanes de byte→halfword) de `p64` (`11`,
        // doubleword→128 bits inteiro); `01`/`10` são reservados (G8).
        if (opcode == 0b1_1100 && !u) {
            if (esz == 0) {
                return new Ir64Op.VectorPolynomialMultiplyLong(false, q, rd, rn, rm);
            }
            if (esz == 3) {
                return new Ir64Op.VectorPolynomialMultiplyLong(true, q, rd, rn, rm);
            }
        }
        throw unsupported(word, address);
    }

    /// "AdvSIMD vector/scalar × indexed element" (B8.19) — entra já sabendo que o prefixo bateu e
    /// `bit10=0` (ver o desvio em {@link #decodeAdvancedSimdInteger}). Escopo ARMv8.0/Cortex-A53:
    /// `MUL`/`MLA`/`MLS`/`SQDMULH`/`SQRDMULH` (não-alargante), `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/
    /// `SMLSL`/`UMLSL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL` (alargante) e `FMUL`/`FMLA`/`FMLS`/`FMULX`
    /// (ponto flutuante, só simples/dupla). `SQRDMLAH`/`SQRDMLSH` (`FEAT_RDM`) decodificam desde
    /// B11.4, gateadas por {@link Aarch64Architecture#has} — ver
    /// {@link #decodeAdvancedSimdIndexedInt}. `FMUL`/`FMLA`/`FMLS`/`FMULX` de meia-precisão
    /// (`FEAT_FP16`, `size=00`) decodificam desde B19.5.6, reusando 100% o esquema de índice
    /// `H:L:M`/`Rm` estreitado de `size=01`. `SDOT_vi`/`UDOT_vi` (`FEAT_DotProd`, B19.23),
    /// `USDOT_vi`/`SUDOT_vi` (`FEAT_I8MM`, B19.12), `BFDOT_vi`/`BFMLAL_vi` (`FEAT_BF16`, B19.7) e
    /// `FMLAL_vi`/`FMLSL_vi`/`FMLAL2_vi`/`FMLSL2_vi` (`FEAT_FHM`, B19.13) decodificam desde as tasks
    /// citadas (interceptados ANTES do `switch` abaixo, ver os blocos correspondentes). EXCLUI
    /// (posterior ao Cortex-A53, candidata a task própria): `FCMLA` (`FEAT_FCMA`).
    private Ir64Op decodeAdvancedSimdIndexedElement(int word, long address, boolean scalar) {
        boolean q = !scalar && ((word >>> ADVSIMD_INT_Q_SHIFT) & 1) != 0;
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int sizeField = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
        int opcode = (word >>> ADVSIMD_INDEXED_OPCODE_SHIFT) & ADVSIMD_INDEXED_OPCODE_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        boolean l = ((word >>> ADVSIMD_INDEXED_L_SHIFT) & 1) != 0;
        boolean h = ((word >>> ADVSIMD_INDEXED_H_SHIFT) & 1) != 0;
        // B19.20 (`FEAT_FCMA`): `FCMLA_vi` hijacka `opcode`(bits[15:12]) `0b0rr1` (`rr`=rotação) nos
        // slots `sizeField=HALFWORD`(H,`esz=1`, hoje só inteiro) e `sizeField=WORD`(S,`esz=2`) — `U`
        // sempre `1` (nenhuma chave real de `decodeAdvancedSimdIndexedFp`/`Int` usa `U=1` com este
        // opcode, ver `ADVSIMD_FCMA_INDEXED_*`), checado ANTES do `switch` genérico pelo MESMO
        // motivo dos blocos `BFLOAT16`/`I8MM`/`FHM`/`DotProd` abaixo. Sem forma `D` real (ARM DDI
        // 0487): a forma `S` também exige `Q=1` sempre (não existe `.2s` indexado, só `.4s`,
        // confirmado contra corpus real — `l`/bit21 fixo `0` nessa forma). Na forma `H`, `!q`
        // (`.4h`) usa só `L`(bit21) como índice (`H`/bit11 fixo `0`); `q` (`.8h`) usa `H:L`.
        if (!scalar && u && (opcode & ADVSIMD_FCMA_INDEXED_OPCODE_MASK) == ADVSIMD_FCMA_INDEXED_OPCODE_PATTERN
                && architecture.has(Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC)
                && (sizeField == ADVSIMD_INDEXED_SIZE_HALFWORD || sizeField == ADVSIMD_INDEXED_SIZE_WORD)) {
            int rotation = ((opcode >>> ADVSIMD_FCMA_INDEXED_ROTATION_SHIFT) & ADVSIMD_FCMA_ROTATION_MASK)
                    * ADVSIMD_FCMA_ROTATION_UNIT_DEGREES;
            if (sizeField == ADVSIMD_INDEXED_SIZE_WORD && q && !l) {
                int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
                int index = h ? 1 : 0;
                return new Ir64Op.VectorFpComplexMultiplyAccumulateByElement(true, 2, rotation, rd, rn, rm, index);
            }
            if (sizeField == ADVSIMD_INDEXED_SIZE_HALFWORD && (q || !h)) {
                int rmH = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
                int index = q ? ((h ? 0b10 : 0) | (l ? 0b01 : 0)) : (l ? 1 : 0);
                return new Ir64Op.VectorFpComplexMultiplyAccumulateByElement(q, 1, rotation, rd, rn, rmH, index);
            }
            // Combinações reservadas (`sizeField=WORD` com `!q`/`l=1`; `sizeField=HALFWORD` com
            // `!q&&h`) nunca são reais (G8) — caem na cadeia normal abaixo, que também não as trata,
            // terminando em `unsupported`.
        }
        // B19.7 (`FEAT_BF16`): `BFDOT_vi`/`BFMLAL_vi` hijacham o MESMO `opcode`(bits[15:12])=`1111`
        // em DOIS slots de `size` diferentes (`01`=`BFDOT_vi`, `11`=`BFMLAL_vi`) — checados ANTES
        // do `switch` genérico porque cada um colide com um caso existente: `BFDOT_vi` cairia no
        // `switch` inteiro de {@link #decodeAdvancedSimdIndexedInt} (que já devolve `null` para essa
        // chave, sem risco, mas não é o lugar certo pra tratar FP) e `BFMLAL_vi` tem `L`(bit21)=`1`
        // real, que o ramo `DOUBLEWORD` de baixo REJEITA explicitamente (`if (l) yield null`) por
        // assumir a convenção FP `d` (`L` sempre `0`) — sem este intercepto, `BFMLAL_vi` nunca
        // alcançaria um decoder, mesmo com a feature presente. Nenhuma das duas tem forma escalar.
        if (!scalar && !u && opcode == ADVSIMD_BFLOAT16_INDEXED_OPCODE
                && architecture.has(Aarch64Feature.BFLOAT16)) {
            int rmH = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
            if (sizeField == ADVSIMD_INDEXED_SIZE_HALFWORD) {
                // `BFDOT_vi`: `Vm.2H[index]` só tem 2 grupos — o índice é `L` sozinho (bit21); `H`
                // (bit11) e `M` (bit20) são reservados/não usados nesta forma (medido contra corpus
                // real, devkitA64 `.arch armv8.6-a+bf16`).
                return new Ir64Op.VectorFpDotProductBFloat16ByElement(q, rd, rn, rmH, l ? 1 : 0);
            }
            if (sizeField == ADVSIMD_INDEXED_SIZE_DOUBLEWORD) {
                // `BFMLAL_vi`: índice `H:L:M` completo (3 bits), MESMA fórmula do ramo `HALFWORD`
                // abaixo — `q` aqui é o seletor `B`/`T` (`top`), NÃO largura (ver Javadoc de
                // {@link Ir64Op.VectorFpMultiplyAddLongBFloat16#top}).
                int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
                int index = (h ? 0b100 : 0) | lm;
                return new Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement(q, rd, rn, rmH, index);
            }
        }
        // B19.12 (`FEAT_I8MM`): `USDOT_vi`/`SUDOT_vi` hijacham o MESMO `opcode`(bits[15:12])=`1111`
        // do bloco `BFLOAT16` acima, em DOIS slots de `size` diferentes (`10`=`USDOT_vi`,
        // `00`=`SUDOT_vi` — sem colisão, BF16 usa `01`/`11`) — checados ANTES do `switch` genérico
        // pelo MESMO motivo (`USDOT_vi` cairia no `switch` de {@link #decodeAdvancedSimdIndexedInt},
        // sem risco mas sem tratamento; `size=00` nem alcança um `switch` real, cai direto no `case
        // default -> null` de baixo). `Rm` restrito a 4 bits (`V0`-`V15`), índice = `H:L` (2 bits) —
        // MESMA fórmula do ramo `WORD` de baixo, mas com `Rm` restrito em vez de 5 bits livres;
        // `M`(bit20) é reservado/não usado nesta família — CONFERIDO bit a bit contra corpus real
        // (`aarch64-linux-gnu-as -march=armv8.6-a+i8mm`). `U`(bit29) é sempre `0` nas duas formas —
        // **não** é o discriminador (task B19.12, achado central): `USDOT_vi`×`SUDOT_vi` se separam
        // só pelo `size`. Nenhuma das duas tem forma escalar real.
        if (!scalar && !u && opcode == ADVSIMD_I8MM_INDEXED_OPCODE
                && architecture.has(Aarch64Feature.INT8_MATRIX_MULTIPLY)) {
            int rmH = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
            int index = (h ? 0b10 : 0) | (l ? 0b01 : 0);
            if (sizeField == ADVSIMD_I8MM_SIZE_WORD) {
                // `USDOT_vi`: `Rn` sem sinal, `Rm` com sinal.
                return new Ir64Op.VectorIntegerDotProductByElement(q, false, true, rd, rn, rmH, index);
            }
            if (sizeField == ADVSIMD_I8MM_INDEXED_SIZE_SUDOT) {
                // `SUDOT_vi`: `Rn` com sinal, `Rm` sem sinal (só existe indexada — não há `SUDOT_v`).
                return new Ir64Op.VectorIntegerDotProductByElement(q, true, false, rd, rn, rmH, index);
            }
        }
        // B19.13 (`FEAT_FHM`): `FMLAL_vi`/`FMLSL_vi`/`FMLAL2_vi`/`FMLSL2_vi` hijacham o MESMO slot
        // `sizeField=WORD` que `FMUL_vi`/`FMLA_vi`/`FMLS_vi`/`FMULX_vi`/`MUL_vi`/`MLA_vi`/`MLS_vi`
        // usam no `switch` abaixo, mas com o layout `H:L:M`/`Rm` de 4 bits do ramo `HALFWORD`
        // (MESMO truque de `BFMLAL_vi` acima, achado real: sem isto, `FMLAL_vi` cairia no `switch`
        // de `WORD` com `Rm` de 5 bits/índice `H:L` errados) — checado ANTES do `switch` genérico
        // por isso, não por colisão de `(u,opcode)` (os 4 valores abaixo não colidem com nenhuma
        // chave de `decodeAdvancedSimdIndexedFp`/`Int` para `size=WORD`, conferido exaustivamente).
        // `opcode`(bits[15:12]): `0000`=`FMLAL_vi`, `0100`=`FMLSL_vi`, `1000`=`FMLAL2_vi`,
        // `1100`=`FMLSL2_vi` — bit3 é `top`, bit2 é `subtract`, bits[1:0] sempre `00` no encoding
        // real (não checados como valor `U` separado: `U` sempre replica o bit `top`, achado medido
        // contra corpus real, então basta o `opcode` cru). Sem forma escalar. Medido bit a bit
        // contra `arm-linux-gnu-as -march=armv8.2-a+fp16+fp16fml` (WSL).
        if (!scalar && sizeField == ADVSIMD_INDEXED_SIZE_WORD
                && (opcode & ADVSIMD_FHM_INDEXED_OPCODE_RESERVED_MASK) == 0
                && architecture.has(Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG)) {
            int rmH = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
            int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
            int index = (h ? 0b100 : 0) | lm;
            boolean top = (opcode & ADVSIMD_FHM_INDEXED_OPCODE_TOP_BIT) != 0;
            boolean subtract = (opcode & ADVSIMD_FHM_INDEXED_OPCODE_SUBTRACT_BIT) != 0;
            return new Ir64Op.VectorFpMultiplyAddLongByElement(q, top, subtract, rd, rn, rmH, index);
        }
        // B19.23 (`FEAT_DotProd` residual): `SDOT_vi`/`UDOT_vi` hijacham `opcode`(bits[15:12])=`1110`
        // (vizinho do `1111` de `USDOT_vi`/`SUDOT_vi`/`BFDOT_vi`/`BFMLAL_vi` acima), `size=WORD`,
        // `Rm` de 5 bits LIVRES (`@qrrx_s` — diferente do `Rm` restrito a `V0`-`V15` de `USDOT_vi`),
        // índice `H:L` (2 bits, MESMA fórmula do ramo `WORD` do `switch` abaixo). `U` distingue
        // `SDOT_vi`(`u=0`) de `UDOT_vi`(`u=1`). Checado ANTES do `switch` genérico: `opcode=1110`
        // não colide com nenhuma chave de {@link #decodeAdvancedSimdIndexedInt} (conferido
        // exaustivamente contra `a64.decode`), mas nada trataria `FEAT_DotProd` sem este intercepto.
        if (!scalar && opcode == ADVSIMD_DOTPRODUCT_INDEXED_OPCODE && sizeField == ADVSIMD_INDEXED_SIZE_WORD
                && architecture.has(Aarch64Feature.DOT_PRODUCT)) {
            int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
            int index = (h ? 0b10 : 0) | (l ? 0b01 : 0);
            return new Ir64Op.VectorIntegerDotProductByElement(q, !u, !u, rd, rn, rm, index);
        }
        Ir64Op result = switch (sizeField) {
            // Doubleword: só ponto flutuante (`FMUL`/`FMLA`/`FMLS`/`FMULX` "d") — `Rm` de 5 bits,
            // índice = só `H` (`L`/bit21 é fixo `0` no encoding real, ver `@rrx_d`/`@qrrx_d`).
            case ADVSIMD_INDEXED_SIZE_DOUBLEWORD -> {
                if (l) {
                    yield null; // reservado (G8): bit21 nunca é `1` nas formas D reais
                }
                int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
                int index = h ? 1 : 0;
                yield decodeAdvancedSimdIndexedFp(scalar, q, ADVSIMD_INT_SCALAR_ESZ, u, opcode, rn, rd, rm, index);
            }
            // Word: COMPARTILHADO entre ponto flutuante ("s") e inteiro ("s") — `Rm` de 5 bits,
            // índice = `H:L` (2 bits). Tenta FP primeiro; sem colisão real de `(U,opcode)` com
            // inteiro (conferido exaustivamente contra `a64.decode`).
            case ADVSIMD_INDEXED_SIZE_WORD -> {
                int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
                int index = (h ? 0b10 : 0) | (l ? 0b01 : 0);
                Ir64Op fpOp = decodeAdvancedSimdIndexedFp(scalar, q, 2, u, opcode, rn, rd, rm, index);
                yield fpOp != null ? fpOp : decodeAdvancedSimdIndexedInt(scalar, q, 2, u, opcode, rn, rd, rm, index);
            }
            // Halfword: só inteiro — `Rm` restrito a 4 bits (`V0`-`V15`), índice = `H:L:M` (3
            // bits, `L:M` lidos como par de bits[21:20] via {@link #ADVSIMD_INDEXED_LM_SHIFT}).
            case ADVSIMD_INDEXED_SIZE_HALFWORD -> {
                int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
                int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
                int index = (h ? 0b100 : 0) | lm;
                yield decodeAdvancedSimdIndexedInt(scalar, q, 1, u, opcode, rn, rd, rm, index);
            }
            // Meia-precisão (`FEAT_FP16`, B19.5.6): só ponto flutuante — MESMO esquema de índice e
            // de estreitamento de `Rm` do ramo `HALFWORD` acima (`Rm` a 4 bits, `H:L:M`). Sem a
            // feature, `yield null` cai no G8 de baixo — byte a byte o comportamento de antes desta
            // task. Opcodes inteiros não existem em `size=00` (o inteiro de halfword é `size=01`) —
            // `decodeAdvancedSimdIndexedFp` devolve `null` para eles, que também cai no G8 de baixo.
            case ADVSIMD_INDEXED_SIZE_HALF_PRECISION -> {
                if (!architecture.has(Aarch64Feature.FP16)) {
                    yield null;
                }
                int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
                int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
                int index = (h ? 0b100 : 0) | lm;
                yield decodeAdvancedSimdIndexedFp(scalar, q, ADVSIMD_ESZ_HALFWORD, u, opcode, rn, rd, rm, index);
            }
            default -> null;
        };
        if (result == null) {
            throw unsupported(word, address);
        }
        return result;
    }

    /// Tabela `(U,opcode)` → {@link Ir64VectorFpThreeSameOp} para
    /// {@link #decodeAdvancedSimdIndexedElement} — `null` se não bater (deixa
    /// {@link #decodeAdvancedSimdIndexedInt} tentar, ou G8 lançar). `MUL`/`MLA`/`MLS`/`MULX` têm
    /// forma escalar E vetorial reais, sem restrição adicional (diferente do lado inteiro).
    private Ir64Op decodeAdvancedSimdIndexedFp(
            boolean scalar, boolean q, int esz, boolean u, int opcode, int rn, int rd, int rm, int index) {
        Ir64VectorFpThreeSameOp op = switch ((u ? 0b1_0000 : 0) | opcode) {
            case 0b0_1001 -> Ir64VectorFpThreeSameOp.MUL;
            case 0b1_1001 -> Ir64VectorFpThreeSameOp.MULX;
            case 0b0_0001 -> Ir64VectorFpThreeSameOp.MLA;
            case 0b0_0101 -> Ir64VectorFpThreeSameOp.MLS;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        return new Ir64Op.VectorFpArithmeticThreeSameByElement(op, scalar, q, esz, rd, rn, rm, index);
    }

    /// Tabela `(U,opcode)` → {@link Ir64VectorThreeSameOp}/{@link Ir64VectorWideningOp} para
    /// {@link #decodeAdvancedSimdIndexedElement} — `null` se não bater (G8 lança no chamador).
    /// `MUL`/`MLA`/`MLS`/`SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL` NÃO têm forma escalar real
    /// (sem encoding no manual) — `scalar=true` para esses é UNALLOCATED, devolvido como `null`
    /// (G8) em vez de silenciosamente aceito.
    private Ir64Op decodeAdvancedSimdIndexedInt(
            boolean scalar, boolean q, int esz, boolean u, int opcode, int rn, int rd, int rm, int index) {
        int key = (u ? 0b1_0000 : 0) | opcode;
        // B11.4 (`FEAT_RDM`): `SQRDMLAH_{vi,si}`/`SQRDMLSH_{vi,si}` reaproveitam este MESMO espaço
        // `(U,opcode)` — `key=0b1_1101`/`0b1_1111`, nenhum dos dois usado pelo `switch` de
        // `threeSameOp`/`wideningOp` abaixo (conferido bit a bit contra `a64.decode` real do QEMU e
        // corpus devkitA64, `.arch armv8.1-a`). Ambas têm forma escalar real (`_si`).
        if (architecture.has(Aarch64Feature.RDM)) {
            Ir64VectorThreeSameOp rdmOp = switch (key) {
                case 0b1_1101 -> Ir64VectorThreeSameOp.SQRDMLAH;
                case 0b1_1111 -> Ir64VectorThreeSameOp.SQRDMLSH;
                default -> null;
            };
            if (rdmOp != null) {
                return new Ir64Op.VectorArithmeticThreeSameByElement(rdmOp, scalar, q, esz, rd, rn, rm, index);
            }
        }
        Ir64VectorThreeSameOp threeSameOp = switch (key) {
            case 0b0_1000 -> Ir64VectorThreeSameOp.MUL;
            case 0b1_0000 -> Ir64VectorThreeSameOp.MLA;
            case 0b1_0100 -> Ir64VectorThreeSameOp.MLS;
            case 0b0_1100 -> Ir64VectorThreeSameOp.SQDMULH;
            case 0b0_1101 -> Ir64VectorThreeSameOp.SQRDMULH;
            default -> null;
        };
        if (threeSameOp != null) {
            boolean scalarAllowed = threeSameOp == Ir64VectorThreeSameOp.SQDMULH
                    || threeSameOp == Ir64VectorThreeSameOp.SQRDMULH;
            if (scalar && !scalarAllowed) {
                return null;
            }
            return new Ir64Op.VectorArithmeticThreeSameByElement(threeSameOp, scalar, q, esz, rd, rn, rm, index);
        }
        Ir64VectorWideningOp wideningOp = switch (key) {
            case 0b0_1010 -> Ir64VectorWideningOp.SMULL;
            case 0b1_1010 -> Ir64VectorWideningOp.UMULL;
            case 0b0_0010 -> Ir64VectorWideningOp.SMLAL;
            case 0b1_0010 -> Ir64VectorWideningOp.UMLAL;
            case 0b0_0110 -> Ir64VectorWideningOp.SMLSL;
            case 0b1_0110 -> Ir64VectorWideningOp.UMLSL;
            case 0b0_1011 -> Ir64VectorWideningOp.SQDMULL;
            case 0b0_0011 -> Ir64VectorWideningOp.SQDMLAL;
            case 0b0_0111 -> Ir64VectorWideningOp.SQDMLSL;
            default -> null;
        };
        if (wideningOp == null) {
            return null;
        }
        boolean scalarAllowed = wideningOp == Ir64VectorWideningOp.SQDMULL
                || wideningOp == Ir64VectorWideningOp.SQDMLAL || wideningOp == Ir64VectorWideningOp.SQDMLSL;
        if (scalar && !scalarAllowed) {
            return null;
        }
        return new Ir64Op.VectorArithmeticWideningByElement(wideningOp, scalar, q, esz, rd, rn, rm, index);
    }

    /// "AdvSIMD shift by immediate" (B8.8) — entra já sabendo que o prefixo bateu
    /// ({@link #ADVSIMD_SHIFT_PREFIX_VECTOR_PATTERN}/{@link #ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN}).
    /// O tamanho do elemento é DERIVADO do bit mais alto setado de `immh` (`0001`=byte,`001x`=
    /// halfword,`01xx`=word,`1xxx`=doubleword; `0000` é UNALLOCATED — G8), e o deslocamento é
    /// resolvido AQUI a partir de `immh:immb` (7 bits, `esize=8<<esz`): à DIREITA
    /// `shift=2*esize-combined` (`1`-`esize`); à ESQUERDA `shift=combined-esize` (`0`-`esize-1`) —
    /// fórmula conferida contra o pseudocódigo real do manual (`ARM DDI 0487`, "shift amount").
    private Ir64Op decodeAdvancedSimdShiftByImmediate(int word, long address) {
        int prefix = (word >>> ADVSIMD_INT_PREFIX_SHIFT) & ADVSIMD_INT_PREFIX_MASK;
        boolean scalar = prefix == ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN;
        if (((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) == 0) {
            throw unsupported(word, address);
        }
        boolean q = !scalar && ((word >>> ADVSIMD_INT_Q_SHIFT) & 1) != 0;
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int immh = (word >>> ADVSIMD_SHIFT_IMMH_SHIFT) & ADVSIMD_SHIFT_IMMH_MASK;
        int immb = (word >>> ADVSIMD_SHIFT_IMMB_SHIFT) & ADVSIMD_SHIFT_IMMB_MASK;
        int esz = highestSetImmhBit(immh);
        if (esz < 0) {
            // B19.6 bloco G: `immh=0000` é EXATAMENTE onde `Vimm`/`FMOVI_v_h` moram (mesmo achado
            // já registrado do lado A32, B13.7/B13.9: "1-reg-and-modified-immediate" reusa o MESMO
            // prefixo "shift by immediate", `immh=0` é o marcador).
            Ir64Op modifiedImmediate = decodeAdvSimdModifiedImmediate(word, address, scalar, q);
            if (modifiedImmediate != null) {
                return modifiedImmediate;
            }
            throw unsupported(word, address); // UNALLOCATED real (G8).
        }
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        int combined = (immh << 3) | immb;
        int esize = 8 << esz;
        int rightShift = 2 * esize - combined;
        int leftShift = combined - esize;

        // Grupo estreitando (`SHRN`/.../`SQRSHRUN`) e alargando (`SSHLL`/`USHLL`) — `esz` variável
        // real mas restrito a `0`-`2` (não há forma `Q`↔`D`); checado ANTES do grupo geral porque
        // usa records/enums diferentes.
        Ir64VectorShiftNarrowOp narrowOp = switch (opcode) {
            case 0b1_0000 -> u ? Ir64VectorShiftNarrowOp.SQSHRUN : Ir64VectorShiftNarrowOp.SHRN;
            case 0b1_0001 -> u ? Ir64VectorShiftNarrowOp.SQRSHRUN : Ir64VectorShiftNarrowOp.RSHRN;
            case 0b1_0010 -> u ? Ir64VectorShiftNarrowOp.UQSHRN : Ir64VectorShiftNarrowOp.SQSHRN;
            case 0b1_0011 -> u ? Ir64VectorShiftNarrowOp.UQRSHRN : Ir64VectorShiftNarrowOp.SQRSHRN;
            default -> null;
        };
        if (narrowOp != null) {
            if (esz == ADVSIMD_INT_SCALAR_ESZ) {
                throw unsupported(word, address);
            }
            if (scalar && (narrowOp == Ir64VectorShiftNarrowOp.SHRN || narrowOp == Ir64VectorShiftNarrowOp.RSHRN)) {
                // `SHRN`/`RSHRN` não têm forma escalar real (só as saturantes têm) — G8.
                throw unsupported(word, address);
            }
            return new Ir64Op.VectorShiftNarrowImmediate(narrowOp, scalar, q, esz, rightShift, rd, rn);
        }
        if (opcode == 0b1_0100) {
            // `SSHLL`/`USHLL` — sem forma escalar real (G8).
            if (scalar || esz == ADVSIMD_INT_SCALAR_ESZ) {
                throw unsupported(word, address);
            }
            Ir64VectorShiftWidenOp widenOp = u ? Ir64VectorShiftWidenOp.USHLL : Ir64VectorShiftWidenOp.SSHLL;
            return new Ir64Op.VectorShiftWidenImmediate(widenOp, q, esz, leftShift, rd, rn);
        }

        Ir64VectorShiftOp op = switch (opcode) {
            case 0b0_0000 -> u ? Ir64VectorShiftOp.USHR : Ir64VectorShiftOp.SSHR;
            case 0b0_0010 -> u ? Ir64VectorShiftOp.USRA : Ir64VectorShiftOp.SSRA;
            case 0b0_0100 -> u ? Ir64VectorShiftOp.URSHR : Ir64VectorShiftOp.SRSHR;
            case 0b0_0110 -> u ? Ir64VectorShiftOp.URSRA : Ir64VectorShiftOp.SRSRA;
            case 0b0_1000 -> u ? Ir64VectorShiftOp.SRI : null;
            case 0b0_1010 -> u ? Ir64VectorShiftOp.SLI : Ir64VectorShiftOp.SHL;
            case 0b0_1100 -> u ? Ir64VectorShiftOp.SQSHLU : null;
            case 0b0_1110 -> u ? Ir64VectorShiftOp.UQSHL : Ir64VectorShiftOp.SQSHL;
            default -> null;
        };
        if (op == null) {
            // B19.3: `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` na forma FP↔ponto fixo (`@fcvt_fixed`,
            // com `#fbits`) moram nesta MESMA classe "shift by immediate" (`bit10=1`),
            // discriminadas pelos opcodes `0b1_1100`/`0b1_1111` que a tabela acima devolve `null`.
            // Só forma ESCALAR nesta task — a vetorial `_vf` é B19.4.
            if (opcode == ADVSIMD_SHIFT_FCVT_FIXED_TO_FLOAT_OPCODE
                    || opcode == ADVSIMD_SHIFT_FCVT_FIXED_TO_INT_OPCODE) {
                // B19.5.3: `esz==1` (meia precisão) só é aceito com `FEAT_FP16` presente — sem a
                // feature, byte a byte o `throw` de sempre (G3/zero-diff em v8.0/v8.1). `esz==0`
                // continua UNDEFINED sempre (G8: não é conversão FP↔fixo real).
                if (esz == ADVSIMD_ESZ_HALFWORD) {
                    if (!architecture.has(Aarch64Feature.FP16)) {
                        throw unsupported(word, address);
                    }
                } else if (esz != ADVSIMD_ESZ_WORD && esz != ADVSIMD_INT_SCALAR_ESZ) {
                    throw unsupported(word, address);
                }
                // B19.4: a forma VETORIAL (`_vf`, `!scalar`) reaproveita o MESMO record com `q` real.
                // `immh<3>==1 && Q==0` (`esz==3 && !q`) é UNDEFINED nesta classe (ARM DDI 0487) — não
                // há arranjo `.1d` de elemento de 64 bits (G8).
                if (!scalar && esz == ADVSIMD_INT_SCALAR_ESZ && !q) {
                    throw unsupported(word, address);
                }
                boolean toFloat = opcode == ADVSIMD_SHIFT_FCVT_FIXED_TO_FLOAT_OPCODE;
                // `rightShift` (`2*esize - immh:immb`, já calculado) é EXATAMENTE o `#fbits` do
                // `@fcvt_fixed`/`@fcvtq_{s,d}` (faixa `1..esize`); `!u` = variante assinada.
                return new Ir64Op.VectorFpConvertFixedPoint(scalar, q, esz, rightShift, toFloat, !u, rd, rn);
            }
            throw unsupported(word, address);
        }
        boolean isRightShift = op == Ir64VectorShiftOp.SSHR || op == Ir64VectorShiftOp.USHR
                || op == Ir64VectorShiftOp.SSRA || op == Ir64VectorShiftOp.USRA
                || op == Ir64VectorShiftOp.SRSHR || op == Ir64VectorShiftOp.URSHR
                || op == Ir64VectorShiftOp.SRSRA || op == Ir64VectorShiftOp.URSRA
                || op == Ir64VectorShiftOp.SRI;
        // `SQSHL`/`UQSHL`/`SQSHLU` aceitam qualquer `esz` (`0`-`3`); o resto desta tabela é D-only
        // na forma escalar (`@shri_d`/`@shli_d` reais — nunca `@shri_b/h/s`/`@shli_b/h/s`).
        boolean acceptsAnyScalarEsz = op == Ir64VectorShiftOp.SQSHL || op == Ir64VectorShiftOp.UQSHL
                || op == Ir64VectorShiftOp.SQSHLU;
        if (scalar && !acceptsAnyScalarEsz && esz != ADVSIMD_INT_SCALAR_ESZ) {
            throw unsupported(word, address);
        }
        int shift = isRightShift ? rightShift : leftShift;
        return new Ir64Op.VectorShiftImmediate(op, scalar, q, esz, shift, rd, rn);
    }

    /// Posição (`0`-`3`) do bit mais alto setado de `immh` (4 bits) — `-1` se `immh=0000`
    /// (UNALLOCATED). `0`=byte,`1`=halfword,`2`=word,`3`=doubleword (`ARM DDI 0487`, "shift by
    /// immediate": o tamanho do elemento é sempre derivado assim, nunca um campo `size` solto).
    private static int highestSetImmhBit(int immh) {
        for (int bit = 3; bit >= 0; bit--) {
            if (((immh >>> bit) & 1) != 0) {
                return bit;
            }
        }
        return -1;
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

    /// `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB` (Floating-point data-processing, 3 source, B8.4) —
    /// `type=10`/`11` (meia-precisão/reservado) são UNDEFINED aqui, mesmo padrão de
    /// {@link #decodeFpDoublePrecision}, mas SEM reaproveitar aquele método: ali o campo é lido
    /// isolado (`Fp64Alu`/`Fp64Convert`/`Fp64Compare` não têm mais nada nos bits vizinhos), aqui
    /// os bits21/15 (negação) ficam ENTRE o `type` e os campos de registrador — inlinar evita um
    /// método que devolveria só metade do que esta forma precisa.
    private Ir64Op decodeFpThreeSource(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        boolean negateAddend = ((word >>> FP_THREE_SOURCE_NEGATE_ADDEND_BIT_SHIFT) & 1) != 0;
        // CONFERIDO contra `do_fmadd`/`TRANS` reais do QEMU (`translate-a64.c`): o bit21 fixo do
        // encoding ("o1") mapeia direto para `neg_a`, mas o bit15 fixo ("o0") NÃO mapeia direto
        // para `neg_n` — `FNMADD` tem bit21=1/bit15=0 e ainda assim `neg_n=true`
        // (`TRANS(FNMADD, do_fmadd, a, true, true)`), e `FNMSUB` tem bit21=1/bit15=1 com
        // `neg_n=false` (`TRANS(FNMSUB, do_fmadd, a, true, false)`). A relação real é
        // `neg_n = bit21 XOR bit15` (conferida nas 4 combinações): `FMADD`(0,0)→false,
        // `FMSUB`(0,1)→true, `FNMADD`(1,0)→true, `FNMSUB`(1,1)→false.
        boolean bit15Set = ((word >>> FP_THREE_SOURCE_O0_BIT_SHIFT) & 1) != 0;
        boolean negateProduct = negateAddend ^ bit15Set;
        int vm = (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        int va = (word >>> FP_THREE_SOURCE_RA_SHIFT) & REGISTER_FIELD_MASK;
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Fp64MultiplyAdd(doublePrecision, negateAddend, negateProduct, vd, vn, vm, va);
    }

    /// `FADD`/`FSUB`/`FMUL`/`FDIV`/`FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`FNMUL` (Floating-point
    /// data-processing, 2 source) — as 5 últimas adicionadas pela B8.4 (herdadas fora de escopo
    /// da B6.5.2).
    private Ir64Op decodeFpTwoSource(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int opcode = (word >>> FP_TWO_SOURCE_OPCODE_SHIFT) & FP_TWO_SOURCE_OPCODE_MASK;
        Ir64Op.Fp64Operation op = switch (opcode) {
            case FP_TWO_SOURCE_OPCODE_FMUL -> Ir64Op.Fp64Operation.MUL;
            case FP_TWO_SOURCE_OPCODE_FDIV -> Ir64Op.Fp64Operation.DIV;
            case FP_TWO_SOURCE_OPCODE_FADD -> Ir64Op.Fp64Operation.ADD;
            case FP_TWO_SOURCE_OPCODE_FSUB -> Ir64Op.Fp64Operation.SUB;
            case FP_TWO_SOURCE_OPCODE_FMAX -> Ir64Op.Fp64Operation.MAX;
            case FP_TWO_SOURCE_OPCODE_FMIN -> Ir64Op.Fp64Operation.MIN;
            case FP_TWO_SOURCE_OPCODE_FMAXNM -> Ir64Op.Fp64Operation.MAXNM;
            case FP_TWO_SOURCE_OPCODE_FMINNM -> Ir64Op.Fp64Operation.MINNM;
            case FP_TWO_SOURCE_OPCODE_FNMUL -> Ir64Op.Fp64Operation.NMUL;
            // Opcodes 1001-1111 são reservados nesta classe ("Floating-point data-processing,
            // 2 source") — FMULX (que soa parecido) vive em outro espaço de encoding (Advanced
            // SIMD escalar, `neon-dp.decode`), fora de escopo desta task.
            default -> throw unsupported(word, address);
        };
        int vm = (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Fp64Alu(op, doublePrecision, vd, vn, vm);
    }

    /// `FMOV`/`FABS`/`FNEG`/`FSQRT` (unárias), `FCVT` F32↔F64/F16↔F32/F16↔F64 (B19.26,
    /// `FEAT_FP16`) e (B8.5) `FRINTN`/`FRINTP`/`FRINTM`/`FRINTZ`/`FRINTA`/`FRINTX`/`FRINTI`
    /// (Floating-point data-processing, 1 source) — opcode(20:15) distingue as formas cobertas;
    /// `BFCVT_s`/`FRINT32*`/`FRINT64*` (extensões POSTERIORES) ficam fora, ver
    /// `isa-nao-aplicavel.tsv`.
    private Ir64Op decodeFpOneSource(int word, long address) {
        int opcode = (word >>> FP_ONE_SOURCE_OPCODE_SHIFT) & FP_ONE_SOURCE_OPCODE_MASK;
        // B19.7 (`FEAT_BF16`): `BFCVT` — `type`(23:22) EXIGE `FP_TYPE_DOUBLE` aqui, mas NÃO é
        // double precision real (mesmo padrão de `FMOV Vn.D[1]`, B19.6 bloco F): checar ANTES de
        // {@link #decodeFpDoublePrecision}, que trataria `type=01` como "double" genérico.
        if (opcode == FP_ONE_SOURCE_OPCODE_BFCVT) {
            int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
            if (type != FP_TYPE_DOUBLE || !architecture.has(Aarch64Feature.BFLOAT16)) {
                throw unsupported(word, address);
            }
            int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int vd = word & REGISTER_FIELD_MASK;
            return new Ir64Op.Fp64ConvertToBf16(vd, vn);
        }
        // B19.26 (`FEAT_FP16`): `FCVT_s_hs`/`FCVT_s_hd` (destino meia-precisão, opcode=7) e
        // `FCVT_s_sh`/`FCVT_s_dh` (fonte meia-precisão, opcodes 4/5 com
        // `type`={@link #FP_TYPE_HALF_PRECISION}) — mesmo padrão do `BFCVT` acima: checar ANTES de
        // {@link #decodeFpDoublePrecision}, que trataria `type`=meia-precisão como reservado.
        if (opcode == FP_ONE_SOURCE_OPCODE_FCVT_TO_HALF) {
            int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
            Ir64Op.Fp64HalfPrecisionConversion conversion = switch (type) {
                case FP_TYPE_SINGLE -> Ir64Op.Fp64HalfPrecisionConversion.SINGLE_TO_HALF;
                case FP_TYPE_DOUBLE -> Ir64Op.Fp64HalfPrecisionConversion.DOUBLE_TO_HALF;
                default -> throw unsupported(word, address);
            };
            if (!architecture.has(Aarch64Feature.FP16)) {
                throw unsupported(word, address);
            }
            int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int vd = word & REGISTER_FIELD_MASK;
            return new Ir64Op.Fp64ConvertHalfPrecision(conversion, vd, vn);
        }
        if (opcode == FP_ONE_SOURCE_OPCODE_FCVT_TO_SINGLE || opcode == FP_ONE_SOURCE_OPCODE_FCVT_TO_DOUBLE) {
            int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
            if (type == FP_TYPE_HALF_PRECISION) {
                if (!architecture.has(Aarch64Feature.FP16)) {
                    throw unsupported(word, address);
                }
                int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
                int vd = word & REGISTER_FIELD_MASK;
                Ir64Op.Fp64HalfPrecisionConversion conversion =
                        opcode == FP_ONE_SOURCE_OPCODE_FCVT_TO_SINGLE
                                ? Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_SINGLE
                                : Ir64Op.Fp64HalfPrecisionConversion.HALF_TO_DOUBLE;
                return new Ir64Op.Fp64ConvertHalfPrecision(conversion, vd, vn);
            }
        }
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vd = word & REGISTER_FIELD_MASK;
        return switch (opcode) {
            case FP_ONE_SOURCE_OPCODE_FMOV ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MOV, doublePrecision, vd, 0, vn);
            case FP_ONE_SOURCE_OPCODE_FABS ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ABS, doublePrecision, vd, 0, vn);
            case FP_ONE_SOURCE_OPCODE_FNEG ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NEG, doublePrecision, vd, 0, vn);
            case FP_ONE_SOURCE_OPCODE_FSQRT ->
                    new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.SQRT, doublePrecision, vd, 0, vn);
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
            case FP_ROUND_OPCODE_FRINTN -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, doublePrecision, vd, vn);
            case FP_ROUND_OPCODE_FRINTP -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY, doublePrecision, vd, vn);
            case FP_ROUND_OPCODE_FRINTM -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY, doublePrecision, vd, vn);
            case FP_ROUND_OPCODE_FRINTZ -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, doublePrecision, vd, vn);
            case FP_ROUND_OPCODE_FRINTA -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, doublePrecision, vd, vn);
            // FRINTX/FRINTI: MESMA direção de FRINTN — ver Javadoc de Ir64Op.Fp64Round (FPCR.RMode
            // não modelado em A64).
            case FP_ROUND_OPCODE_FRINTX -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, doublePrecision, vd, vn);
            case FP_ROUND_OPCODE_FRINTI -> new Ir64Op.Fp64Round(
                    Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, doublePrecision, vd, vn);
            // B19.18 (`FEAT_FRINTTS`): `Z` sempre `TOWARD_ZERO` (real no hardware, não
            // simplificação); `X` degenera para `NEAREST_TIES_EVEN` (mesma decisão herdada de
            // `FRINTX`/`FRINTI` acima — `FPCR.RMode` não modelado, B8.5/B8.15).
            case FP_ROUND_RANGE_OPCODE_FRINT32Z -> requireDirectedRoundingToIntegral(word, address,
                    new Ir64Op.Fp64RoundRangeLimited(
                            Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, doublePrecision, vd, vn));
            case FP_ROUND_RANGE_OPCODE_FRINT32X -> requireDirectedRoundingToIntegral(word, address,
                    new Ir64Op.Fp64RoundRangeLimited(
                            Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, false, doublePrecision, vd, vn));
            case FP_ROUND_RANGE_OPCODE_FRINT64Z -> requireDirectedRoundingToIntegral(word, address,
                    new Ir64Op.Fp64RoundRangeLimited(
                            Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, true, doublePrecision, vd, vn));
            case FP_ROUND_RANGE_OPCODE_FRINT64X -> requireDirectedRoundingToIntegral(word, address,
                    new Ir64Op.Fp64RoundRangeLimited(
                            Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, doublePrecision, vd, vn));
            default -> throw unsupported(word, address);
        };
    }

    /// B19.18 (`FEAT_FRINTTS`): gate comum às 4 formas escalares — devolve `op` se a feature
    /// estiver presente, senão recusa (G8).
    private Ir64Op requireDirectedRoundingToIntegral(int word, long address, Ir64Op op) {
        if (!architecture.has(Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL)) {
            throw unsupported(word, address);
        }
        return op;
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

    /// `FCSEL` (B8.5) — `Rm`(20:16)/`cond`(15:12) compartilhados com {@link #decodeFpConditionalCompare},
    /// `Rn`(9:5)/`Rd`(4:0) na posição normal de operandos FP.
    private Ir64Op decodeFpConditionalSelect(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int vm = (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        Ir64Condition condition = Ir64Condition.decode((word >>> FP_COND_SHIFT) & COND_FIELD_MASK);
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int vd = word & REGISTER_FIELD_MASK;
        return new Ir64Op.Fp64ConditionalSelect(doublePrecision, vd, vn, vm, condition);
    }

    /// `FCCMP`/`FCCMPE` (B8.5) — `Rn`(9:5)/`Vm`(20:16)/`cond`(15:12) na MESMA posição de `FCSEL`;
    /// `E`(bit4)/`nzcv`(3:0) onde `FCSEL` tem `Rd`.
    private Ir64Op decodeFpConditionalCompare(int word, long address) {
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int vm = (word >>> FP_RM_SHIFT) & REGISTER_FIELD_MASK;
        Ir64Condition condition = Ir64Condition.decode((word >>> FP_COND_SHIFT) & COND_FIELD_MASK);
        int vn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        boolean signalOnQuietNaN = ((word >>> FP_CCMP_E_BIT_SHIFT) & 1) != 0;
        int nzcv = word & FP_CCMP_NZCV_MASK;
        return new Ir64Op.Fp64ConditionalCompare(doublePrecision, signalOnQuietNaN, vn, vm, condition, nzcv);
    }

    /// "Conversion between floating-point and fixed-point (general register)" (B8.5): SÓ
    /// `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` existem neste grupo (`Z` já é o nome — sempre trunca p/
    /// zero). `shift` é `N - raw` (`N`=32 quando `!sf`, exigindo bit15=1 e só os 5 bits baixos do
    /// campo — CONFERIDO contra `%fcvt_shift32` real; `N`=64 quando `sf`, campo de 6 bits inteiro).
    private Ir64Op decodeFpFixedPointConvert(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int opcode = (word >>> FP_FIXED_CONVERT_OPCODE_SHIFT) & FP_FIXED_CONVERT_OPCODE_MASK;
        boolean toFloat;
        boolean signed;
        switch (opcode) {
            case FP_FIXED_CONVERT_OPCODE_SCVTF -> { toFloat = true; signed = true; }
            case FP_FIXED_CONVERT_OPCODE_UCVTF -> { toFloat = true; signed = false; }
            case FP_FIXED_CONVERT_OPCODE_FCVTZS -> { toFloat = false; signed = true; }
            case FP_FIXED_CONVERT_OPCODE_FCVTZU -> { toFloat = false; signed = false; }
            default -> throw unsupported(word, address);
        }
        int rawShift = (word >>> FP_FIXED_CONVERT_SHIFT_FIELD_SHIFT) & FP_FIXED_CONVERT_SHIFT_FIELD_MASK;
        int fractionBits;
        if (wide) {
            fractionBits = 64 - rawShift;
        } else {
            if ((word & FP_FIXED_CONVERT_NARROW_MARKER_BIT) == 0) {
                // Forma de 32 bits exige bit15=1 (marcador fixo do encoding real, ver
                // %fcvt_shift32) — sem ele, este não é um encoding válido desta classe.
                throw unsupported(word, address);
            }
            fractionBits = 32 - (rawShift & FP_FIXED_CONVERT_NARROW_RAW_MASK);
        }
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        int fpReg = toFloat ? rd : rn;
        int gpReg = toFloat ? rn : rd;
        return new Ir64Op.Fp64IntegerConvert(toFloat, signed,
                Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, doublePrecision, wide, fractionBits, fpReg, gpReg);
    }

    /// "Conversion between floating-point and integer (general register)" e `FMOV` registrador-
    /// geral↔FP (B8.5) — MESMO sufixo bits[15:10]="000000", discriminados pelo opcode(21:16).
    private Ir64Op decodeFpIntegerConvertOrGeneralRegisterMove(int word, long address) {
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int opcode = (word >>> FP_FIXED_CONVERT_OPCODE_SHIFT) & FP_FIXED_CONVERT_OPCODE_MASK;
        // B19.6 bloco F: `FMOV Xd,Vn.D[1]`/`FMOV Vd.D[1],Xn` — MESMO `opcode`(21:16) desta classe,
        // mas `type`(23:22)="10" (que {@link #decodeFpDoublePrecision} trataria como UNDEFINED
        // para TODO O RESTO da classe). Checar `opcode`+`type` ANTES de chamar
        // `decodeFpDoublePrecision` — senão esta forma nunca decodificaria (G8). `sf=0` não existe
        // nesta forma (só `X`/`V.D[1]`, achado medido contra corpus real).
        if (opcode == FP_GP_MOVE_OPCODE_HIGH_TO_GP || opcode == FP_GP_MOVE_OPCODE_HIGH_TO_FLOAT) {
            int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
            if (!wide || type != FP_TYPE_HIGH_HALF_MOVE) {
                throw unsupported(word, address);
            }
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int rd = word & REGISTER_FIELD_MASK;
            boolean toFloat = opcode == FP_GP_MOVE_OPCODE_HIGH_TO_FLOAT;
            return new Ir64Op.Fp64HighHalfMove(toFloat, toFloat ? rd : rn, toFloat ? rn : rd);
        }
        // B19.29 (`FEAT_JSCVT`): `FJCVTZS` — MESMO padrão de `BFCVT`/`FMOV Vn.D[1]` acima: `type`
        // EXIGIDO=`DOUBLE` faz parte do encoding fixo da instrução (não existe forma de precisão
        // simples), não uma escolha genérica de {@link #decodeFpDoublePrecision} — checar ANTES,
        // senão `type=SINGLE` cairia no `default` do switch abaixo mesmo com a feature presente.
        if (opcode == FP_INT_CONVERT_OPCODE_FJCVTZS) {
            int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
            if (wide || type != FP_TYPE_DOUBLE || !architecture.has(Aarch64Feature.JAVASCRIPT_CONVERT)) {
                throw unsupported(word, address);
            }
            int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
            int rd = word & REGISTER_FIELD_MASK;
            return new Ir64Op.Fp64JavascriptConvert(rd, rn);
        }
        // B19.26 (`FEAT_FP16`): `FMOV_hx`/`FMOV_xh` — MESMOS opcodes de `FMOV` registrador-geral↔FP
        // comum (`W`↔`S`/`X`↔`D`), mas `type`={@link #FP_TYPE_HALF_PRECISION} em vez de
        // `sf?DOUBLE:SINGLE` — checar ANTES de {@link #decodeFpDoublePrecision} (que trataria este
        // `type` como reservado). `sf` é ignorado de propósito (ver Javadoc de
        // {@link Ir64Op.Fp64HalfPrecisionGeneralRegisterMove} — resultado idêntico nos dois valores,
        // confirmado contra o corpus real).
        if (opcode == FP_GP_MOVE_OPCODE_TO_FLOAT || opcode == FP_GP_MOVE_OPCODE_TO_GP) {
            int type = (word >>> FP_TYPE_SHIFT) & FP_TYPE_MASK;
            if (type == FP_TYPE_HALF_PRECISION) {
                if (!architecture.has(Aarch64Feature.FP16)) {
                    throw unsupported(word, address);
                }
                int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
                int rd = word & REGISTER_FIELD_MASK;
                boolean toFloat = opcode == FP_GP_MOVE_OPCODE_TO_FLOAT;
                return new Ir64Op.Fp64HalfPrecisionGeneralRegisterMove(
                        toFloat, toFloat ? rd : rn, toFloat ? rn : rd);
            }
        }
        boolean doublePrecision = decodeFpDoublePrecision(word, address);
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        if (opcode == FP_GP_MOVE_OPCODE_TO_FLOAT) {
            return new Ir64Op.Fp64GeneralRegisterMove(true, wide, rd, rn);
        }
        if (opcode == FP_GP_MOVE_OPCODE_TO_GP) {
            return new Ir64Op.Fp64GeneralRegisterMove(false, wide, rn, rd);
        }
        boolean toFloat;
        boolean signed;
        Ir64Op.Fp64RoundingDirection rounding;
        switch (opcode) {
            case FP_INT_CONVERT_OPCODE_SCVTF -> {
                toFloat = true; signed = true; rounding = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
            }
            case FP_INT_CONVERT_OPCODE_UCVTF -> {
                toFloat = true; signed = false; rounding = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
            }
            case FP_INT_CONVERT_OPCODE_FCVTNS -> {
                toFloat = false; signed = true; rounding = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
            }
            case FP_INT_CONVERT_OPCODE_FCVTNU -> {
                toFloat = false; signed = false; rounding = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN;
            }
            case FP_INT_CONVERT_OPCODE_FCVTPS -> {
                toFloat = false; signed = true; rounding = Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY;
            }
            case FP_INT_CONVERT_OPCODE_FCVTPU -> {
                toFloat = false; signed = false; rounding = Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY;
            }
            case FP_INT_CONVERT_OPCODE_FCVTMS -> {
                toFloat = false; signed = true; rounding = Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY;
            }
            case FP_INT_CONVERT_OPCODE_FCVTMU -> {
                toFloat = false; signed = false; rounding = Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY;
            }
            case FP_INT_CONVERT_OPCODE_FCVTZS -> {
                toFloat = false; signed = true; rounding = Ir64Op.Fp64RoundingDirection.TOWARD_ZERO;
            }
            case FP_INT_CONVERT_OPCODE_FCVTZU -> {
                toFloat = false; signed = false; rounding = Ir64Op.Fp64RoundingDirection.TOWARD_ZERO;
            }
            case FP_INT_CONVERT_OPCODE_FCVTAS -> {
                toFloat = false; signed = true; rounding = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY;
            }
            case FP_INT_CONVERT_OPCODE_FCVTAU -> {
                toFloat = false; signed = false; rounding = Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY;
            }
            // `_g_simd`/`_simd` (FEAT_FPRCVT): extensão POSTERIOR, CONFERIDA contra
            // translate-a64.c — ver isa-nao-aplicavel.tsv. `FJCVTZS` (FEAT_JSCVT) já foi
            // interceptada ANTES deste switch (B19.29).
            default -> throw unsupported(word, address);
        }
        int fpReg = toFloat ? rd : rn;
        int gpReg = toFloat ? rn : rd;
        return new Ir64Op.Fp64IntegerConvert(toFloat, signed, rounding, doublePrecision, wide, 0, fpReg, gpReg);
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

    /// `MOVI`/`MVNI`/`ORR`/`BIC` imediato (`Vimm`) + `FMOV` de meia precisão imediato
    /// (`FMOVI_v_h`, `FEAT_FP16`) — B19.6 bloco G, irmão A64 direto de `Vimm_1r`/B13.9. `imm8` é
    /// remontado pelo `%abcdefgh` real do `a64.decode` (posição física diferente da usada por
    /// {@link #decodeFpMoveImmediate}, MESMO valor semântico). Devolve `null` (nunca um encoding
    /// ERRADO, G8) para os dois casos em que o chamador deve continuar tratando como UNALLOCATED:
    /// forma escalar (Vimm/FMOVI_v_h só existem sob o prefixo vetorial) e `bits[11:10]` reservado
    /// dentro deste subespaço.
    private Ir64Op decodeAdvSimdModifiedImmediate(int word, long address, boolean scalar, boolean q) {
        if (scalar) {
            return null;
        }
        int imm8 = (((word >>> ADVSIMD_MODIFIED_IMM_TOP_SHIFT) & 0b111) << 5)
                | ((word >>> ADVSIMD_MODIFIED_IMM_BOTTOM_SHIFT) & 0b1_1111);
        int rd = word & REGISTER_FIELD_MASK;
        int suffix6 = (word >>> ADVSIMD_MODIFIED_IMM_SUFFIX_SHIFT) & 0b11_1111;
        if (suffix6 == ADVSIMD_MODIFIED_IMM_FMOVI_H_SUFFIX) {
            // `FMOVI_v_h` (`FEAT_FP16`): imediato de meia precisão replicado por todas as lanes
            // `H` de 64 bits (4 cópias) — igual à disciplina do MOV puro, nunca recalculado depois.
            if (!architecture.has(Aarch64Feature.FP16)) {
                throw unsupported(word, address);
            }
            long half16 = expandFpImmediateHalf(imm8);
            long imm64 = half16 | (half16 << 16) | (half16 << 32) | (half16 << 48);
            return new Ir64Op.AdvSimdModifiedImmediate64(AdvSimdModifiedImmediateOp.MOV, q, rd, imm64);
        }
        int fixedTwoBits = (word >>> ADVSIMD_MODIFIED_IMM_FIXED2_SHIFT) & 0b11;
        if (fixedTwoBits != ADVSIMD_MODIFIED_IMM_FIXED2_PATTERN) {
            return null;
        }
        int cmode = (word >>> ADVSIMD_MODIFIED_IMM_CMODE_SHIFT) & 0b1111;
        int op = (word >>> ADVSIMD_MODIFIED_IMM_OP_SHIFT) & 1;
        if (AdvSimdModifiedImmediate.isReservedInAarch32(cmode, op)) {
            // `cmode=1111,op=1`: reservado em AArch32, em AArch64 é `FMOV` (vector, immediate) de
            // 64 bits — reaproveita {@link #expandFpImmediate} (MESMO algoritmo VFPExpandImm de
            // {@link #decodeFpMoveImmediate}, só o `imm8` já reconstruído acima).
            long imm64 = expandFpImmediate(imm8, true);
            return new Ir64Op.AdvSimdModifiedImmediate64(AdvSimdModifiedImmediateOp.MOV, q, rd, imm64);
        }
        AdvSimdModifiedImmediate.Expanded expanded = AdvSimdModifiedImmediate.expand(imm8, cmode, op);
        return new Ir64Op.AdvSimdModifiedImmediate64(expanded.op(), q, rd, expanded.imm64());
    }

    /// `VFPExpandImm`-equivalente de meia precisão (`FMOVI_v_h`, B19.6 bloco G) — mesmo pseudocódigo
    /// geral do manual (`ARM DDI 0487`, `VFPExpandImm`) que {@link #expandFpImmediate} já aplica
    /// para simples/dupla, generalizado para `binary16` (`E=5` bits de expoente, `F=10` de
    /// mantissa): `exp = NOT(imm8<6>):Replicate(imm8<6>,E-3):imm8<5:4>`, `frac =
    /// imm8<3:0>:Zeros(F-4)`. **Achado desta task**: a primeira versão desta função reusava
    /// ERRADAMENTE os 6 bits inteiros de `imm8<5:0>` como mantissa (padrão válido só quando
    /// `E-3` bits de replicação bastam para completar o expoente sozinhos, o que NÃO é o caso aqui
    /// — `imm8<5:4>` completa o expoente, só `imm8<3:0>` é mantissa) — pego pelo teste diferencial
    /// contra `#1.0` (`imm8=0x70`), que dava `1.75` em vez de `1.0` antes da correção.
    private static long expandFpImmediateHalf(int imm8) {
        boolean sign = (imm8 & 0x80) != 0;
        boolean bit6 = (imm8 & 0x40) != 0;
        long exponentReplicate = bit6 ? 0b11L : 0b00L;
        int top2 = (imm8 >>> 4) & 0b11;
        int bottom4 = imm8 & 0b1111;
        return (sign ? 0x8000L : 0)
                | (bit6 ? 0 : (1L << 14))
                | (exponentReplicate << 12)
                | ((long) top2 << 10)
                | ((long) bottom4 << 6);
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
        if (sixBitFixed == COMPARE_BRANCH_COND_FIXED_PATTERN) {
            return decodeCompareAndBranchConditional(word, address);
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

    /// `CB_cond`/`CB_cond_imm` (`FEAT_CMPBR`, B19.22) — `bit24` distingue as duas formas dentro do
    /// MESMO prefixo de 6 bits `COMPARE_BRANCH_COND_FIXED_PATTERN`.
    private Ir64Op decodeCompareAndBranchConditional(int word, long address) {
        if (!architecture.has(Aarch64Feature.COMPARE_AND_BRANCH)) {
            throw unsupported(word, address);
        }
        boolean immediateForm = (word & BIT_24) != 0;
        return immediateForm
                ? decodeCompareAndBranchImmediate(word, address)
                : decodeCompareAndBranchRegister(word, address);
    }

    /// Mapeamento de `cc` (3 bits) da forma REGISTRADOR de `CB_cond` — confirmado contra
    /// `trans_CB_cond` do QEMU real (`cb_cond[8]`): `4`/`5` são reservados (G8, recusar).
    private static Ir64CompareBranchCondition decodeCompareBranchRegisterCondition(
            int cc, int word, long address) {
        return switch (cc) {
            case 0 -> Ir64CompareBranchCondition.GREATER_THAN;
            case 1 -> Ir64CompareBranchCondition.GREATER_OR_EQUAL;
            case 2 -> Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED;
            case 3 -> Ir64CompareBranchCondition.GREATER_OR_EQUAL_UNSIGNED;
            case 6 -> Ir64CompareBranchCondition.EQUAL;
            case 7 -> Ir64CompareBranchCondition.NOT_EQUAL;
            default -> throw unsupported(word, address); // cc==4/5: reservado
        };
    }

    /// Mapeamento de `cc` (3 bits) da forma IMEDIATA de `CB_cond_imm` — confirmado contra
    /// `trans_CB_cond_imm` do QEMU real (`cb_cond[8]`), DIFERENTE do mapeamento da forma
    /// registrador (`LT`/`LTU` aqui onde a forma registrador teria `GE`/`GEU` no MESMO valor de
    /// `cc`; achado documentado explicitamente no comentário-fonte do QEMU: "CB imm and CB encode
    /// the condition differently").
    private static Ir64CompareBranchCondition decodeCompareBranchImmediateCondition(
            int cc, int word, long address) {
        return switch (cc) {
            case 0 -> Ir64CompareBranchCondition.GREATER_THAN;
            case 1 -> Ir64CompareBranchCondition.LESS_THAN;
            case 2 -> Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED;
            case 3 -> Ir64CompareBranchCondition.LESS_THAN_UNSIGNED;
            case 6 -> Ir64CompareBranchCondition.EQUAL;
            case 7 -> Ir64CompareBranchCondition.NOT_EQUAL;
            default -> throw unsupported(word, address); // cc==4/5: reservado
        };
    }

    private Ir64Op decodeCompareAndBranchRegister(int word, long address) {
        int cc = (word >>> CB_COND_CC_SHIFT) & CB_COND_CC_MASK;
        Ir64CompareBranchCondition condition = decodeCompareBranchRegisterCondition(cc, word, address);
        int eszField = (word >>> CB_COND_ESZ_FIELD_SHIFT) & CB_COND_ESZ_FIELD_MASK;
        Ir64MemSize size = switch (eszField) {
            case CB_COND_ESZ_FIELD_WORD_OR_DOUBLE ->
                    ((word >>> SF_SHIFT) & 1) != 0 ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
            case CB_COND_ESZ_FIELD_BYTE -> Ir64MemSize.BYTE;
            case CB_COND_ESZ_FIELD_HALF -> Ir64MemSize.HALF;
            default -> throw unsupported(word, address); // eszField==0b01: reservado
        };
        int rm = (word >>> CB_COND_RM_SHIFT) & REGISTER_FIELD_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        long imm9 = (word >>> CB_COND_OFFSET_IMM9_SHIFT) & bitMask(CB_COND_OFFSET_IMM9_BITS);
        long offset = signExtend(imm9, CB_COND_OFFSET_IMM9_BITS) * BYTES_PER_BRANCH_UNIT;
        long target = address + offset;
        return new Ir64Op.CompareAndBranchRegister(condition, rt, rm, size, target);
    }

    private Ir64Op decodeCompareAndBranchImmediate(int word, long address) {
        if ((word & CB_COND_IMM_RESERVED_BIT14) != 0) {
            // bit[14] reservado: nenhuma forma no manual o usa em `1` — G8, recusar.
            throw unsupported(word, address);
        }
        int cc = (word >>> CB_COND_CC_SHIFT) & CB_COND_CC_MASK;
        Ir64CompareBranchCondition condition = decodeCompareBranchImmediateCondition(cc, word, address);
        boolean wide = ((word >>> SF_SHIFT) & 1) != 0;
        int immediate = (word >>> CB_COND_IMM6_SHIFT) & (int) bitMask(CB_COND_IMM6_BITS);
        int rt = word & REGISTER_FIELD_MASK;
        long imm9 = (word >>> CB_COND_OFFSET_IMM9_SHIFT) & bitMask(CB_COND_OFFSET_IMM9_BITS);
        long offset = signExtend(imm9, CB_COND_OFFSET_IMM9_BITS) * BYTES_PER_BRANCH_UNIT;
        long target = address + offset;
        return new Ir64Op.CompareAndBranchImmediate(condition, rt, wide, immediate, target);
    }

    private Ir64Op decodeBranchRegister(int word, long address) {
        int op2 = (word >>> BRANCH_REGISTER_OP2_SHIFT) & BRANCH_REGISTER_OP2_MASK;
        if (op2 != BRANCH_REGISTER_OP2_FIXED) {
            // DRPS ou combinação reservada: fora da fatia B6.1/B6.6.4.
            throw unsupported(word, address);
        }
        int op3 = (word >>> BRANCH_REGISTER_OP3_SHIFT) & BRANCH_REGISTER_OP3_MASK;
        int op4 = word & BRANCH_REGISTER_OP4_MASK;
        int opc = (word >>> BRANCH_REGISTER_OPC_SHIFT) & BRANCH_REGISTER_OPC_MASK;
        if (op3 == BRANCH_REGISTER_OP3_FIXED && op4 == BRANCH_REGISTER_OP4_FIXED) {
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
        int op3Upper = (op3 >>> 1) & PAUTH_BRANCH_OP3_UPPER_MASK;
        if (op3Upper == PAUTH_BRANCH_OP3_UPPER_FIXED) {
            // B19.15: `BRAZ`/`BLRAZ`/`RETA`/`BRA`/`BLRA`/`ERETA` (`FEAT_PAuth`) — MESMO prefixo/op2
            // de BR/BLR/RET/ERET acima, `op3` alto reaproveitado como sub-seletor (ver comentário de
            // `PAUTH_BRANCH_OP3_UPPER_FIXED`). `m` (bit10 de `op3`, chave A/B) é lido só por
            // fidelidade — rota (b) da task não diferencia (nenhuma autenticação real é modelada).
            if (!architecture.has(Aarch64Feature.POINTER_AUTHENTICATION)) {
                throw unsupported(word, address);
            }
            return decodePauthBranchRegister(word, address, opc, op4);
        }
        // Demais combinações de `op3`/`op4` (ex. `DRPS`): fora da fatia B6.1/B6.6.4/B19.15.
        throw unsupported(word, address);
    }

    /// `BRAZ`/`BLRAZ`/`RETA`/`BRA`/`BLRA`/`ERETA` (B19.15, `FEAT_PAuth`) — já confirmado
    /// `op2`=`11111`/`op3` alto=`00001` pelo chamador. Rota (b) registrada na task (mesmo
    /// precedente de {@link Ir64Op.PointerAuthGeneric}/{@link Ir64Op.PointerAuthInPlace}): nenhuma
    /// autenticação real é modelada, então cada forma delega DIRETO para a contraparte não
    /// autenticada (`BR`/`BLR`/`RET`/`ERET` comuns) — o modificador (`Xm` ou zero implícito) e a
    /// chave A/B são decodificados só para validar o encoding, nunca usados para alterar o alvo.
    private Ir64Op decodePauthBranchRegister(int word, long address, int opc, int op4) {
        return switch (opc) {
            case PAUTH_BRANCH_OPC_BRAZ, PAUTH_BRANCH_OPC_BLRAZ -> {
                if (op4 != PAUTH_BRANCH_OP4_ZERO_MODIFIER_FIXED) {
                    throw unsupported(word, address); // reservado
                }
                int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
                boolean link = opc == PAUTH_BRANCH_OPC_BLRAZ;
                yield new Ir64Op.Branch64(Ir64BranchForm.REGISTER, address, 0L, rn, link, Ir64Condition.AL);
            }
            case PAUTH_BRANCH_OPC_RETA -> {
                if (op4 != PAUTH_BRANCH_OP4_ZERO_MODIFIER_FIXED
                        || ((word >>> RN_SHIFT) & REGISTER_FIELD_MASK) != PAUTH_BRANCH_RN_FIXED) {
                    throw unsupported(word, address); // reservado
                }
                yield new Ir64Op.Branch64(Ir64BranchForm.REGISTER, address, 0L,
                        PAUTH_RETA_TARGET_REGISTER, false, Ir64Condition.AL);
            }
            case PAUTH_BRANCH_OPC_ERETA -> {
                if (op4 != PAUTH_BRANCH_OP4_ZERO_MODIFIER_FIXED
                        || ((word >>> RN_SHIFT) & REGISTER_FIELD_MASK) != PAUTH_BRANCH_RN_FIXED) {
                    throw unsupported(word, address); // reservado
                }
                yield new Ir64Op.ExceptionReturn();
            }
            case PAUTH_BRANCH_OPC_BRA, PAUTH_BRANCH_OPC_BLRA -> {
                int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
                boolean link = opc == PAUTH_BRANCH_OPC_BLRA;
                yield new Ir64Op.Branch64(Ir64BranchForm.REGISTER, address, 0L, rn, link, Ir64Condition.AL);
            }
            default -> throw unsupported(word, address); // reservado
        };
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
            // HVC (B10.4): entra em EL2 de verdade, ver Aarch64Core#enterHypervisorCall. SMC
            // (B10.5): entra em EL3 de verdade, ver Aarch64Core#enterSecureMonitorCall. O `imm16`
            // é ignorado pela semântica em ambos os casos (mesmo padrão real de hardware: o
            // imediato só importa para o handler em EL2/EL3, que lê a própria instrução — este
            // emulador não modela isso).
            return new Ir64Op.PrivilegedCall(low5 == EXCEPTION_GEN_HVC_LOW5_FIXED);
        }
        if (opc == EXCEPTION_GEN_OPC_BRK && low5 == EXCEPTION_GEN_BRK_HLT_LOW5_FIXED) {
            // BRK (B8.3): imm16 é o único operando.
            return new Ir64Op.Breakpoint(imm16);
        }
        if (opc == EXCEPTION_GEN_OPC_HLT && low5 == EXCEPTION_GEN_BRK_HLT_LOW5_FIXED) {
            // HLT (B8.3): sem estado de debug externo modelado, vira UNDEFINED (ver javadoc de
            // Ir64Op.UndefinedInstructionTrap) — o imm16 do encoding só teria sentido para um host
            // de debug, que não existe aqui.
            return new Ir64Op.UndefinedInstructionTrap();
        }
        // DCPS1/DCPS2/DCPS3: sempre UNDEF fora de "halting debug state" (não implementado, mesmo
        // achado documentado pelo próprio a64.decode do QEMU) — fora do escopo desta task.
        throw unsupported(word, address);
    }

    /// `DSB`/`ISB`/`DMB`/`DSB(nXS)`/`SB` (B6.6.3 + B8.3) e os "Hints" `NOP`/`YIELD`/`WFE`/`WFI`/
    /// `SEV`/`SEVL`/`WFET`/`WFIT` (B6.6.7 + B8.3) — mesmo subgrupo de encoding `op0=0` (`CRn`
    /// distingue barreira de hint de "wait with timeout" de `MSR (immediate)`). Barreiras e a
    /// maior parte dos hints viram NOP observável, mesmo precedente de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.MemoryBarrier} 32-bit; `WFI`/`WFIT` têm
    /// semântica própria (ver {@link Ir64SystemInstructionOp#WFI}); `CLREX` fecha o monitor de
    /// exclusividade (ver {@link Ir64SystemInstructionOp#CLEAR_EXCLUSIVE}); `MSR (immediate)`
    /// (`CRn=0b0100`, junto de `CFINV`/`XAFLAG`/`AXFLAG`) delega em
    /// {@link #decodeFlagOrPstateImmediate}.
    private Ir64Op decodeSystemInstructionBarrier(int word, long address) {
        int crn = (word >>> SYSTEM_REGISTER_CRN_SHIFT) & SYSTEM_REGISTER_CRN_MASK;
        int op1 = (word >>> SYSTEM_REGISTER_OP1_SHIFT) & SYSTEM_REGISTER_OP1_MASK;
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        if (crn == SYSTEM_INSTRUCTION_BARRIER_CRN) {
            return switch (op2) {
                case SYSTEM_INSTRUCTION_BARRIER_OP2_DSB, SYSTEM_INSTRUCTION_BARRIER_OP2_DMB,
                        SYSTEM_INSTRUCTION_BARRIER_OP2_ISB, SYSTEM_INSTRUCTION_BARRIER_OP2_DSB_NXS,
                        SYSTEM_INSTRUCTION_BARRIER_OP2_SB ->
                        new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.BARRIER);
                case SYSTEM_INSTRUCTION_BARRIER_OP2_CLREX ->
                        new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.CLEAR_EXCLUSIVE);
                default -> throw unsupported(word, address);
            };
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
        if (crn == SYSTEM_INSTRUCTION_WAIT_TIMEOUT_CRN) {
            // WFET/WFIT (B8.3, FEAT_WFxT): mesmo tratamento de WFE (NOP)/WFI (dorme até IRQ) sem
            // timeout — Rt (registrador com o valor de comparação) é ignorado, ver constante.
            // B11.6: gate real — ARMv8.7-A introduziu FEAT_WFxT, um Cortex-A53 (ARMv8.0-A) não tem
            // esses 2 encodings; sem a feature, cai em unsupported (G8) em vez de decodificar.
            if (!architecture.has(Aarch64Feature.WFXT)) {
                throw unsupported(word, address);
            }
            return switch (op2) {
                case SYSTEM_INSTRUCTION_WAIT_TIMEOUT_OP2_WFET ->
                        new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.NOP_HINT);
                case SYSTEM_INSTRUCTION_WAIT_TIMEOUT_OP2_WFIT ->
                        new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.WFI);
                default -> throw unsupported(word, address);
            };
        }
        if (crn == SYSTEM_INSTRUCTION_FLAG_MANIP_CRN) {
            return decodeFlagOrPstateImmediate(word, address, op1, op2);
        }
        throw unsupported(word, address);
    }

    /// `CFINV`/`XAFLAG`/`AXFLAG` (B8.2) + `MSR (immediate)` inteiro (B8.3): TODAS compartilham
    /// `CRn=0b0100`, distinguidas por `op1` (ver o achado real documentado em
    /// {@link #SYSTEM_INSTRUCTION_FLAG_MANIP_OP1}) e depois por `op2` dentro de cada grupo de
    /// `op1`. `DAIFSet`/`DAIFClr` são as únicas com semântica própria além de flags NZCV (mascaram
    /// IRQ) — o resto vira {@link Ir64SystemInstructionOp#PSTATE_FIELD_NOP} (sem estado modelado).
    /// B11.9: `CFINV` (`FEAT_FlagM`) e `XAFLAG`/`AXFLAG` (`FEAT_FlagM2`) gateados — achado real: B11.7
    /// só tinha gateado `RMIF`/`SETF8`/`SETF16` (método diferente), deixando `CFINV` aceito
    /// incondicionalmente por engano.
    private Ir64Op decodeFlagOrPstateImmediate(int word, long address, int op1, int op2) {
        if (op1 == SYSTEM_INSTRUCTION_FLAG_MANIP_OP1) {
            Ir64FlagConversionOp flagOp = switch (op2) {
                case SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_CFINV -> {
                    if (!architecture.has(Aarch64Feature.FLAG_MANIPULATION)) {
                        throw unsupported(word, address);
                    }
                    yield Ir64FlagConversionOp.INVERT_CARRY;
                }
                case SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_XAFLAG -> {
                    if (!architecture.has(Aarch64Feature.FLAG_MANIPULATION_2)) {
                        throw unsupported(word, address);
                    }
                    yield Ir64FlagConversionOp.EXTERNAL_TO_ARM;
                }
                case SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_AXFLAG -> {
                    if (!architecture.has(Aarch64Feature.FLAG_MANIPULATION_2)) {
                        throw unsupported(word, address);
                    }
                    yield Ir64FlagConversionOp.ARM_TO_EXTERNAL;
                }
                case SYSTEM_INSTRUCTION_PSTATE_OP2_UAO -> {
                    if (!architecture.has(Aarch64Feature.UAO)) {
                        throw unsupported(word, address);
                    }
                    yield null;
                }
                case SYSTEM_INSTRUCTION_PSTATE_OP2_PAN -> {
                    if (!architecture.has(Aarch64Feature.PAN)) {
                        throw unsupported(word, address);
                    }
                    yield null;
                }
                case SYSTEM_INSTRUCTION_PSTATE_OP2_SPSEL -> null;
                default -> throw unsupported(word, address);
            };
            return flagOp != null
                    ? new Ir64Op.ConvertFlags(flagOp)
                    : new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.PSTATE_FIELD_NOP);
        }
        if (op1 == SYSTEM_INSTRUCTION_ALLINT_OP1 && op2 == SYSTEM_INSTRUCTION_FLAG_MANIP_OP2_CFINV) {
            // MSR ALLINT (op2 reaproveita o mesmo valor 0b000 de CFINV — só o op1 distingue, e já
            // foi checado acima). B11.8: FEAT_NMI (ARMv8.8-A) gateada.
            if (!architecture.has(Aarch64Feature.NMI)) {
                throw unsupported(word, address);
            }
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.PSTATE_FIELD_NOP);
        }
        if (op1 == SYSTEM_INSTRUCTION_PSTATE_IMM_OP1) {
            int imm = (word >>> SYSTEM_REGISTER_CRM_SHIFT) & SYSTEM_REGISTER_CRM_MASK;
            return switch (op2) {
                case SYSTEM_INSTRUCTION_PSTATE_OP2_SBSS, SYSTEM_INSTRUCTION_PSTATE_OP2_TCO ->
                        new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.PSTATE_FIELD_NOP);
                case SYSTEM_INSTRUCTION_PSTATE_OP2_DIT -> {
                    if (!architecture.has(Aarch64Feature.DIT)) {
                        throw unsupported(word, address);
                    }
                    yield new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.PSTATE_FIELD_NOP);
                }
                case SYSTEM_INSTRUCTION_PSTATE_OP2_DAIFSET -> new Ir64Op.InterruptMask(true, imm);
                case SYSTEM_INSTRUCTION_PSTATE_OP2_DAIFCLEAR -> new Ir64Op.InterruptMask(false, imm);
                case SYSTEM_INSTRUCTION_PSTATE_OP2_SVCR -> {
                    // B19.28: sem arquitetura declarando FEAT_SME, isto cai no MESMO
                    // `unsupported` genérico de qualquer outra feature ausente — comportamento
                    // idêntico ao de antes desta task. A diferença só aparece quando a feature
                    // ESTÁ presente (ARMV9_2_A): aí a recusa passa a ser NOMEADA — "reconhecida,
                    // mas sem estado ZA/streaming-SVE modelado" — em vez de indistinguível de um
                    // encoding realmente desconhecido (G8).
                    if (!architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION)) {
                        throw unsupported(word, address);
                    }
                    int svcrMask = (imm >>> SYSTEM_INSTRUCTION_SVCR_MASK_FIELD_SHIFT)
                            & SYSTEM_INSTRUCTION_SVCR_MASK_FIELD_MASK;
                    int svcrImmediate = imm & SYSTEM_INSTRUCTION_SVCR_IMMEDIATE_FIELD_MASK;
                    throw unsupportedScalableMatrixExtension(word, address, svcrMask, svcrImmediate);
                }
                default -> throw unsupported(word, address);
            };
        }
        throw unsupported(word, address);
    }

    /// `TLBI`/manutenção de cache (`op0=1`, `SYS` — achado real da rodada de pesquisa de B6.6.3:
    /// `TLBI` NÃO é `MRS`/`MSR`, é uma forma de `SYS` com o mesmo formato de campos).
    ///
    /// **B8.3 amplia os dois grupos** (achado real desta task): este emulador não modela NENHUMA
    /// TLB nem cache — a spec original (B6.6.3) só reconhecia `TLBI VMALLE1(IS)` (invalidação
    /// total) e um subconjunto de 10 operações `IC`/`DC` por engano de escopo, mas como não existe
    /// TLB/cache nenhuma para invalidar de forma diferenciada, QUALQUER `TLBI` do regime EL1
    /// (`op1=0b000`, qualquer `CRm`/`op2` dentro de `CRn=0b1000`) e QUALQUER manutenção `IC`/`DC`
    /// (`CRn=0b0111`, qualquer `op1`/`CRm`/`op2`) é um NOP igualmente seguro — invalidar/limpar
    /// "demais" nunca corrompe estado (ao contrário de invalidar "de menos"). `DC ZVA` continua
    /// EXCLUÍDA explicitamente (tem efeito observável real — zera memória — e já é anunciada como
    /// indisponível via `DCZID_EL0.DZP=1`, B6.10; se um guest ignorar isso e emitir mesmo assim,
    /// deve cair no `throw unsupported`, não silenciosamente virar NOP).
    ///
    /// **B10.6**: `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` (address translation, escreve `PAR_EL1`) —
    /// MESMO `CRn=0b0111` da manutenção de cache, distinguida por `CRm=0b1000` — precisa de
    /// carve-out ANTES do bucket genérico de cache abaixo, ou cairia incorretamente em
    /// `CACHE_MAINTENANCE_NOP` (achado real desta task: era exatamente isso que acontecia antes,
    /// já que o `if` de cache de B8.3 não checava `CRm`, ver javadoc da task). `AT S1E2*`/`S1E3*`
    /// (B10.6b/B10.6c, stage-1 pura dos regimes EL2/EL3) e `S12E*` (B10.8, combinadas
    /// stage-1+stage-2) também são reconhecidas, cada uma no seu `op1`; `op2` reservado dentro de
    /// cada regime cai em `throw unsupported` como qualquer encoding não reconhecido (G8). O resto de
    /// `SYS`/`SYSL` (`TLBI` per-VA/per-ASID como instrução ENDEREÇÁVEL individualmente — aqui
    /// tratada igual a "invalidar tudo", não byte a byte — debug registers via `SYSL`, `op0=2`)
    /// fica fora do escopo desta task, documentado como próximo passo, não presumido desnecessário
    /// (ver a task, "Não inclui").
    ///
    /// **B10.9**: `TLBI` dos regimes EL2 (`op1=0b100`, cobre também as formas stage-2
    /// `IPAS2E1*`/`IPAS2LE1*` e as combinadas `ALLE1*`/`VMALLS12E1*` — todas vivem no MESMO
    /// `op1=0b100` no hardware real, conferido contra `tlbi_el1_cp_reginfo`/`tlbi_el2_cp_reginfo`
    /// reais do QEMU) e EL3 (`op1=0b110`) passam a ser aceitas aqui, com a MESMA simplificação
    /// "invalidar tudo" já aplicada ao regime EL1&0 por B8.3 — nenhum dos três regimes tem TLB
    /// própria modelada, então não há como (nem por que) diferenciar `ALLE2` de `VAE2` de
    /// `IPAS2E1`, etc. (Nota histórica desta task, B10.9: na época, `AT S1E2*`/`S1E3*` ainda
    /// estavam fora — implementadas depois por B10.6b/B10.6c, ver o carve-out de `CRn=0b0111`
    /// acima.)
    private Ir64Op decodeSystemInstructionSys(int word, long address) {
        boolean isSysl = ((word >>> SYSTEM_REGISTER_L_SHIFT) & 1) != 0;
        int op1 = (word >>> SYSTEM_REGISTER_OP1_SHIFT) & SYSTEM_REGISTER_OP1_MASK;
        int crn = (word >>> SYSTEM_REGISTER_CRN_SHIFT) & SYSTEM_REGISTER_CRN_MASK;
        if (!isSysl && crn == SYSTEM_INSTRUCTION_TLBI_CRN && isTlbiRegime(op1)) {
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.TLBI_ALL);
        }
        if (!isSysl && crn == SYSTEM_INSTRUCTION_CACHE_CRN && isAddressTranslateStage1Crm(word)) {
            // CRm=0b1000 é SEMPRE `AT` (nunca manutenção de cache), para QUALQUER `op1` — os 3
            // regimes (`op1=0b000` EL1&0 B10.6, `op1=0b100` EL2 B10.6b/B10.8, `op1=0b110` EL3
            // B10.6c) agora têm forma implementada; `op2` reservado dentro de cada regime cai em
            // `unsupported` abaixo, nunca no bucket de NOP genérico (G8 — ver Armadilhas).
            if (op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL1) {
                return decodeAddressTranslate(word, address);
            }
            if (op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL2) {
                return decodeAddressTranslateEl2(word, address);
            }
            if (op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL3) {
                return decodeAddressTranslateEl3(word, address);
            }
            throw unsupported(word, address);
        }
        if (!isSysl && crn == SYSTEM_INSTRUCTION_CACHE_CRN) {
            if (isDataCacheZva(word)) {
                throw unsupported(word, address);
            }
            return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP);
        }
        // B19.6 bloco A: resto do espaço `SYS`/`SYSL` (`op0=1`) fora de TLBI/AT/manutenção de
        // cache — sem hospedeiro que trate manutenções desconhecidas, NOP explícito (ver javadoc
        // de Ir64SystemInstructionOp#MAINTENANCE_UNMODELED_NOP).
        return new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.MAINTENANCE_UNMODELED_NOP);
    }

    /// Confere se `op1` é um dos 3 regimes de `TLBI` que este emulador aceita (B10.9): EL1&0, EL2
    /// (incl. stage-2, mesmo `op1` no hardware real) e EL3 — ver javadoc de
    /// {@link #decodeSystemInstructionSys}.
    private static boolean isTlbiRegime(int op1) {
        return op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL1 || op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL2
                || op1 == SYSTEM_INSTRUCTION_TLBI_OP1_EL3;
    }

    /// Confere se `CRm` bate com o grupo `AT` (`CRn=0b0111` já checado pelo chamador).
    private static boolean isAddressTranslateStage1Crm(int word) {
        int crm = (word >>> SYSTEM_REGISTER_CRM_SHIFT) & SYSTEM_REGISTER_CRM_MASK;
        return crm == SYSTEM_INSTRUCTION_AT_STAGE1_CRM;
    }

    /// `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` (B10.6) — `op2` seleciona a forma; `Rt` carrega o VA de
    /// origem (mesma convenção de {@link #decodeSystemRegister}, `31`=`XZR`). Chamado só quando
    /// `op1`/`CRn`/`CRm` já bateram com o grupo `AT` EL1&0 (ver {@link #decodeSystemInstructionSys}).
    private Ir64Op decodeAddressTranslate(int word, long address) {
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        Aarch64AddressTranslateForm form = switch (op2) {
            case SYSTEM_INSTRUCTION_AT_OP2_S1E1R -> Aarch64AddressTranslateForm.S1E1R;
            case SYSTEM_INSTRUCTION_AT_OP2_S1E1W -> Aarch64AddressTranslateForm.S1E1W;
            case SYSTEM_INSTRUCTION_AT_OP2_S1E0R -> Aarch64AddressTranslateForm.S1E0R;
            case SYSTEM_INSTRUCTION_AT_OP2_S1E0W -> Aarch64AddressTranslateForm.S1E0W;
            default -> throw unsupported(word, address);
        };
        return new Ir64Op.AddressTranslate(form, rt);
    }

    /// `AT S1E2R`/`S1E2W` (B10.6b, stage-1 pura do regime EL2) e `AT S12E1R`/`S12E1W`/`S12E0R`/
    /// `S12E0W` (B10.8, formas combinadas stage-1+stage-2) — `op2` seleciona a forma; `op2`
    /// reservado (`2`/`3`) cai em `unsupported`. Chamado só quando `op1`/`CRn`/`CRm` já bateram com
    /// o grupo `AT` EL2 (ver {@link #decodeSystemInstructionSys}).
    private Ir64Op decodeAddressTranslateEl2(int word, long address) {
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        Aarch64AddressTranslateForm form = switch (op2) {
            case SYSTEM_INSTRUCTION_AT_OP2_S1E2R -> Aarch64AddressTranslateForm.S1E2R;
            case SYSTEM_INSTRUCTION_AT_OP2_S1E2W -> Aarch64AddressTranslateForm.S1E2W;
            case SYSTEM_INSTRUCTION_AT_OP2_S12E1R -> Aarch64AddressTranslateForm.S12E1R;
            case SYSTEM_INSTRUCTION_AT_OP2_S12E1W -> Aarch64AddressTranslateForm.S12E1W;
            case SYSTEM_INSTRUCTION_AT_OP2_S12E0R -> Aarch64AddressTranslateForm.S12E0R;
            case SYSTEM_INSTRUCTION_AT_OP2_S12E0W -> Aarch64AddressTranslateForm.S12E0W;
            default -> throw unsupported(word, address);
        };
        return new Ir64Op.AddressTranslate(form, rt);
    }

    /// `AT S1E3R`/`S1E3W` (B10.6c, stage-1 pura do regime EL3) — `op2` seleciona a forma; sem
    /// formas combinadas `S12E3*` (EL3 não tem stage-2 no hardware real). Chamado só quando
    /// `op1`/`CRn`/`CRm` já bateram com o grupo `AT` EL3 (ver {@link #decodeSystemInstructionSys}).
    private Ir64Op decodeAddressTranslateEl3(int word, long address) {
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        int rt = word & REGISTER_FIELD_MASK;
        Aarch64AddressTranslateForm form = switch (op2) {
            case SYSTEM_INSTRUCTION_AT_OP2_S1E3R -> Aarch64AddressTranslateForm.S1E3R;
            case SYSTEM_INSTRUCTION_AT_OP2_S1E3W -> Aarch64AddressTranslateForm.S1E3W;
            default -> throw unsupported(word, address);
        };
        return new Ir64Op.AddressTranslate(form, rt);
    }

    /// Confere se `{CRm, op2}` bate com `DC ZVA` (`CRn=0b0111` já checado pelo chamador) — único
    /// membro do grupo "manutenção de cache" excluído do NOP amplo de B8.3 (ver javadoc de
    /// {@link #decodeSystemInstructionSys}).
    private static boolean isDataCacheZva(int word) {
        int crm = (word >>> SYSTEM_REGISTER_CRM_SHIFT) & SYSTEM_REGISTER_CRM_MASK;
        int op2 = (word >>> SYSTEM_REGISTER_OP2_SHIFT) & SYSTEM_REGISTER_OP2_MASK;
        return crm == SYSTEM_INSTRUCTION_CACHE_DC_ZVA_CRM && op2 == SYSTEM_INSTRUCTION_CACHE_DC_ZVA_OP2;
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
        // B11.8: ALLINT (forma registrador) é FEAT_NMI (ARMv8.8-A) — gateada.
        if (register == Aarch64SystemRegisterId.ALLINT && !architecture.has(Aarch64Feature.NMI)) {
            throw unsupported(word, address);
        }
        // B11.10: PAN/UAO/DIT (forma registrador) são FEAT_PAN/FEAT_UAO/FEAT_DIT (ARMv8.1-A/
        // ARMv8.2-A/ARMv8.4-A) — gateadas, mesmo padrão de ALLINT acima.
        if (register == Aarch64SystemRegisterId.PAN && !architecture.has(Aarch64Feature.PAN)) {
            throw unsupported(word, address);
        }
        if (register == Aarch64SystemRegisterId.UAO && !architecture.has(Aarch64Feature.UAO)) {
            throw unsupported(word, address);
        }
        if (register == Aarch64SystemRegisterId.DIT && !architecture.has(Aarch64Feature.DIT)) {
            throw unsupported(word, address);
        }
        // B19.11a: FPMR é FEAT_FPMR, que FEAT_FP8 implica — mesma feature que gateia as 12
        // instruções de conversão da B19.11 (não há preset que precise de FPMR sem FEAT_FP8).
        if (register == Aarch64SystemRegisterId.FPMR && !architecture.has(Aarch64Feature.FP8)) {
            throw unsupported(word, address);
        }
        // B19.14: RGSR_EL1/GCR_EL1 são FEAT_MTE2 — gateados, mesmo padrão de FPMR acima.
        if ((register == Aarch64SystemRegisterId.RGSR_EL1 || register == Aarch64SystemRegisterId.GCR_EL1)
                && !architecture.has(Aarch64Feature.MEMORY_TAGGING)) {
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
        if (op0 == SYSREG_OP0_DEBUG) {
            Aarch64SystemRegisterId debugRegister =
                    op1 == SYSREG_OP1_EL1 ? decodeDebugRegisterId(crn, crm, op2) : null;
            // B19.6 bloco A: qualquer combinação `op0=2` fora do subconjunto nomeado de B10.7 cai
            // no escaninho tolerante único (nunca `null` aqui — `null` viraria `throw unsupported`
            // no chamador, o que travaria o boot de qualquer kernel real com suporte a debug).
            return debugRegister != null ? debugRegister : Aarch64SystemRegisterId.DEBUG_UNMODELED;
        }
        if (op0 != SYSREG_OP0_EL1) {
            return null;
        }
        if (op1 == SYSREG_OP1_EL0_TIMER) {
            if (crn == SYSREG_CRN_CACHE_IDENTITY) {
                return decodeCacheIdentityRegisterId(crm, op2);
            }
            if (crn == SYSREG_CRN_TPIDR_EL1) {
                return decodeThreadPointerEl0RegisterId(crm, op2);
            }
            if (crn == SYSREG_CRN_PROCESS_STATE) {
                return decodeCrn4RegisterId(crm, op2);
            }
            return decodeGenericTimerRegisterId(crn, crm, op2);
        }
        if (op1 == SYSREG_OP1_EL2) {
            return decodeEl2RegisterId(crn, crm, op2);
        }
        if (op1 == SYSREG_OP1_EL3) {
            return decodeEl3RegisterId(crn, crm, op2);
        }
        if (op1 != SYSREG_OP1_EL1) {
            return null;
        }
        if (crn == SYSREG_CRN_CURRENT_EL && crm == SYSREG_CRM_CURRENT_EL && op2 == SYSREG_OP2_CURRENT_EL) {
            return Aarch64SystemRegisterId.CURRENT_EL;
        }
        // B8.17: SPSel/PAN/UAO/ALLINT — armazenamento puro, mesma disciplina de DIT/SSBS/TCO.
        if (crn == SYSREG_CRN_PSTATE_FIELDS_EL1 && crm == SYSREG_CRM_SPSEL_PAN_UAO) {
            if (op2 == SYSREG_OP2_SPSEL) {
                return Aarch64SystemRegisterId.SPSEL;
            }
            if (op2 == SYSREG_OP2_PAN) {
                return Aarch64SystemRegisterId.PAN;
            }
            if (op2 == SYSREG_OP2_UAO) {
                return Aarch64SystemRegisterId.UAO;
            }
            return null;
        }
        if (crn == SYSREG_CRN_PSTATE_FIELDS_EL1 && crm == SYSREG_CRM_ALLINT && op2 == SYSREG_OP2_ALLINT) {
            return Aarch64SystemRegisterId.ALLINT;
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
        if (crn == SYSREG_CRN_ID_AA64MMFR1 && crm == SYSREG_CRM_ID_AA64MMFR1 && op2 == SYSREG_OP2_ID_AA64MMFR1) {
            return Aarch64SystemRegisterId.ID_AA64MMFR1_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64MMFR2 && crm == SYSREG_CRM_ID_AA64MMFR2 && op2 == SYSREG_OP2_ID_AA64MMFR2) {
            return Aarch64SystemRegisterId.ID_AA64MMFR2_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64MMFR3 && crm == SYSREG_CRM_ID_AA64MMFR3 && op2 == SYSREG_OP2_ID_AA64MMFR3) {
            return Aarch64SystemRegisterId.ID_AA64MMFR3_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64MMFR4 && crm == SYSREG_CRM_ID_AA64MMFR4 && op2 == SYSREG_OP2_ID_AA64MMFR4) {
            return Aarch64SystemRegisterId.ID_AA64MMFR4_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64PFR1 && crm == SYSREG_CRM_ID_AA64PFR1 && op2 == SYSREG_OP2_ID_AA64PFR1) {
            return Aarch64SystemRegisterId.ID_AA64PFR1_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64ZFR0 && crm == SYSREG_CRM_ID_AA64ZFR0 && op2 == SYSREG_OP2_ID_AA64ZFR0) {
            return Aarch64SystemRegisterId.ID_AA64ZFR0_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64DFR1 && crm == SYSREG_CRM_ID_AA64DFR1 && op2 == SYSREG_OP2_ID_AA64DFR1) {
            return Aarch64SystemRegisterId.ID_AA64DFR1_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64ISAR1 && crm == SYSREG_CRM_ID_AA64ISAR1 && op2 == SYSREG_OP2_ID_AA64ISAR1) {
            return Aarch64SystemRegisterId.ID_AA64ISAR1_EL1;
        }
        if (crn == SYSREG_CRN_ID_AA64ISAR2 && crm == SYSREG_CRM_ID_AA64ISAR2 && op2 == SYSREG_OP2_ID_AA64ISAR2) {
            return Aarch64SystemRegisterId.ID_AA64ISAR2_EL1;
        }
        if (crn == SYSREG_CRN_REVIDR && crm == SYSREG_CRM_REVIDR && op2 == SYSREG_OP2_REVIDR) {
            return Aarch64SystemRegisterId.REVIDR_EL1;
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
        if (crn == SYSREG_CRN_CPACR && crm == SYSREG_CRM_CPACR && op2 == SYSREG_OP2_CPACR) {
            return Aarch64SystemRegisterId.CPACR_EL1;
        }
        if (crn == SYSREG_CRN_SCTLR && crm == SYSREG_CRM_SCTLR && op2 == SYSREG_OP2_RGSR_EL1) {
            return Aarch64SystemRegisterId.RGSR_EL1;
        }
        if (crn == SYSREG_CRN_SCTLR && crm == SYSREG_CRM_SCTLR && op2 == SYSREG_OP2_GCR_EL1) {
            return Aarch64SystemRegisterId.GCR_EL1;
        }
        if (crn == SYSREG_CRN_TTBR0 && crm == SYSREG_CRM_TTBR0 && op2 == SYSREG_OP2_TTBR0) {
            return Aarch64SystemRegisterId.TTBR0_EL1;
        }
        if (crn == SYSREG_CRN_TTBR1 && crm == SYSREG_CRM_TTBR1 && op2 == SYSREG_OP2_TTBR1) {
            return Aarch64SystemRegisterId.TTBR1_EL1;
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
    /// `TPIDR_EL0`/`TPIDRRO_EL0` (B8.14) — mesmo `CRn`/`CRm` de `TPIDR_EL1`, só `op1` (grupo EL0)
    /// e `op2` mudam.
    private static Aarch64SystemRegisterId decodeThreadPointerEl0RegisterId(int crm, int op2) {
        if (crm != SYSREG_CRM_TPIDR_EL0) {
            return null;
        }
        if (op2 == SYSREG_OP2_TPIDR_EL0) {
            return Aarch64SystemRegisterId.TPIDR_EL0;
        }
        if (op2 == SYSREG_OP2_TPIDRRO_EL0) {
            return Aarch64SystemRegisterId.TPIDRRO_EL0;
        }
        return null;
    }

    /// `CRn=4` do grupo EL0 (`op1=3`) — 2 famílias distintas por `CRm`: `NZCV`/`DAIF` (B8.16,
    /// `CRm=2`) e `FPCR`/`FPSR` (B8.15, `CRm=4`).
    private static Aarch64SystemRegisterId decodeCrn4RegisterId(int crm, int op2) {
        if (crm == SYSREG_CRM_NZCV_DAIF) {
            if (op2 == SYSREG_OP2_NZCV) {
                return Aarch64SystemRegisterId.NZCV;
            }
            if (op2 == SYSREG_OP2_DAIF) {
                return Aarch64SystemRegisterId.DAIF;
            }
            if (op2 == SYSREG_OP2_DIT) {
                return Aarch64SystemRegisterId.DIT;
            }
            if (op2 == SYSREG_OP2_SSBS) {
                return Aarch64SystemRegisterId.SSBS;
            }
            if (op2 == SYSREG_OP2_TCO) {
                return Aarch64SystemRegisterId.TCO;
            }
            return null;
        }
        if (crm == SYSREG_CRM_FPCR_FPSR) {
            if (op2 == SYSREG_OP2_FPCR) {
                return Aarch64SystemRegisterId.FPCR;
            }
            if (op2 == SYSREG_OP2_FPSR) {
                return Aarch64SystemRegisterId.FPSR;
            }
            if (op2 == SYSREG_OP2_FPMR) {
                return Aarch64SystemRegisterId.FPMR;
            }
            return null;
        }
        return null;
    }

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
        // B8.16: CNTVCT_EL0 (contador VIRTUAL, mesmo CRm=0 do físico, só op2 muda).
        if (crm == SYSREG_CRM_CNTPCT && op2 == SYSREG_OP2_CNTVCT) {
            return Aarch64SystemRegisterId.CNTVCT_EL0;
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
        // B8.16: timer VIRTUAL (CNTV_*), mesmo layout do físico (CNTP_*) em CRm=3 em vez de CRm=2.
        if (crm == SYSREG_CRM_CNTV && op2 == SYSREG_OP2_CNTP_TVAL) {
            return Aarch64SystemRegisterId.CNTV_TVAL_EL0;
        }
        if (crm == SYSREG_CRM_CNTV && op2 == SYSREG_OP2_CNTP_CTL) {
            return Aarch64SystemRegisterId.CNTV_CTL_EL0;
        }
        if (crm == SYSREG_CRM_CNTV && op2 == SYSREG_OP2_CNTP_CVAL) {
            return Aarch64SystemRegisterId.CNTV_CVAL_EL0;
        }
        return null;
    }

    /// Tabela de registradores de sistema EL2 (`op0=3,op1=4`, B10.2) — armazenamento puro, sem
    /// roteamento real (ver javadoc de `Aarch64SystemRegisterId`).
    private static Aarch64SystemRegisterId decodeEl2RegisterId(int crn, int crm, int op2) {
        if (crn == SYSREG_CRN_SCTLR_EL2 && crm == SYSREG_CRM_SCTLR_EL2 && op2 == SYSREG_OP2_SCTLR_EL2) {
            return Aarch64SystemRegisterId.SCTLR_EL2;
        }
        if (crn == SYSREG_CRN_HCR_EL2 && crm == SYSREG_CRM_HCR_EL2 && op2 == SYSREG_OP2_HCR_EL2) {
            return Aarch64SystemRegisterId.HCR_EL2;
        }
        if (crn == SYSREG_CRN_MDCR_EL2 && crm == SYSREG_CRM_MDCR_EL2 && op2 == SYSREG_OP2_MDCR_EL2) {
            return Aarch64SystemRegisterId.MDCR_EL2;
        }
        if (crn == SYSREG_CRN_CPTR_EL2 && crm == SYSREG_CRM_CPTR_EL2 && op2 == SYSREG_OP2_CPTR_EL2) {
            return Aarch64SystemRegisterId.CPTR_EL2;
        }
        if (crn == SYSREG_CRN_TCR_EL2 && crm == SYSREG_CRM_TCR_EL2 && op2 == SYSREG_OP2_TCR_EL2) {
            return Aarch64SystemRegisterId.TCR_EL2;
        }
        if (crn == SYSREG_CRN_TTBR0_EL2 && crm == SYSREG_CRM_TTBR0_EL2 && op2 == SYSREG_OP2_TTBR0_EL2) {
            return Aarch64SystemRegisterId.TTBR0_EL2;
        }
        if (crn == SYSREG_CRN_VTTBR_EL2 && crm == SYSREG_CRM_VTTBR_EL2 && op2 == SYSREG_OP2_VTTBR_EL2) {
            return Aarch64SystemRegisterId.VTTBR_EL2;
        }
        if (crn == SYSREG_CRN_VTCR_EL2 && crm == SYSREG_CRM_VTCR_EL2 && op2 == SYSREG_OP2_VTCR_EL2) {
            return Aarch64SystemRegisterId.VTCR_EL2;
        }
        if (crn == SYSREG_CRN_SPSR_EL2 && crm == SYSREG_CRM_SPSR_EL2 && op2 == SYSREG_OP2_SPSR_EL2) {
            return Aarch64SystemRegisterId.SPSR_EL2;
        }
        if (crn == SYSREG_CRN_ELR_EL2 && crm == SYSREG_CRM_ELR_EL2 && op2 == SYSREG_OP2_ELR_EL2) {
            return Aarch64SystemRegisterId.ELR_EL2;
        }
        if (crn == SYSREG_CRN_FAR_EL2 && crm == SYSREG_CRM_FAR_EL2 && op2 == SYSREG_OP2_FAR_EL2) {
            return Aarch64SystemRegisterId.FAR_EL2;
        }
        if (crn == SYSREG_CRN_ESR_EL2 && crm == SYSREG_CRM_ESR_EL2 && op2 == SYSREG_OP2_ESR_EL2) {
            return Aarch64SystemRegisterId.ESR_EL2;
        }
        if (crn == SYSREG_CRN_CNTHCTL_EL2 && crm == SYSREG_CRM_CNTHCTL_EL2 && op2 == SYSREG_OP2_CNTHCTL_EL2) {
            return Aarch64SystemRegisterId.CNTHCTL_EL2;
        }
        if (crn == SYSREG_CRN_VBAR_EL2 && crm == SYSREG_CRM_VBAR_EL2 && op2 == SYSREG_OP2_VBAR_EL2) {
            return Aarch64SystemRegisterId.VBAR_EL2;
        }
        return null;
    }

    /// Tabela de registradores de sistema EL3 (`op0=3,op1=6`, B10.3) — armazenamento puro, sem
    /// roteamento real (ver javadoc de `Aarch64SystemRegisterId`).
    private static Aarch64SystemRegisterId decodeEl3RegisterId(int crn, int crm, int op2) {
        if (crn == SYSREG_CRN_SCTLR_EL3 && crm == SYSREG_CRM_SCTLR_EL3 && op2 == SYSREG_OP2_SCTLR_EL3) {
            return Aarch64SystemRegisterId.SCTLR_EL3;
        }
        if (crn == SYSREG_CRN_SCR_EL3 && crm == SYSREG_CRM_SCR_EL3 && op2 == SYSREG_OP2_SCR_EL3) {
            return Aarch64SystemRegisterId.SCR_EL3;
        }
        if (crn == SYSREG_CRN_CPTR_EL3 && crm == SYSREG_CRM_CPTR_EL3 && op2 == SYSREG_OP2_CPTR_EL3) {
            return Aarch64SystemRegisterId.CPTR_EL3;
        }
        if (crn == SYSREG_CRN_MDCR_EL3 && crm == SYSREG_CRM_MDCR_EL3 && op2 == SYSREG_OP2_MDCR_EL3) {
            return Aarch64SystemRegisterId.MDCR_EL3;
        }
        if (crn == SYSREG_CRN_SPSR_EL3 && crm == SYSREG_CRM_SPSR_EL3 && op2 == SYSREG_OP2_SPSR_EL3) {
            return Aarch64SystemRegisterId.SPSR_EL3;
        }
        if (crn == SYSREG_CRN_ELR_EL3 && crm == SYSREG_CRM_ELR_EL3 && op2 == SYSREG_OP2_ELR_EL3) {
            return Aarch64SystemRegisterId.ELR_EL3;
        }
        if (crn == SYSREG_CRN_VBAR_EL3 && crm == SYSREG_CRM_VBAR_EL3 && op2 == SYSREG_OP2_VBAR_EL3) {
            return Aarch64SystemRegisterId.VBAR_EL3;
        }
        if (crn == SYSREG_CRN_TTBR0_EL3 && crm == SYSREG_CRM_TTBR0_EL3 && op2 == SYSREG_OP2_TTBR0_EL3) {
            return Aarch64SystemRegisterId.TTBR0_EL3;
        }
        if (crn == SYSREG_CRN_TCR_EL3 && crm == SYSREG_CRM_TCR_EL3 && op2 == SYSREG_OP2_TCR_EL3) {
            return Aarch64SystemRegisterId.TCR_EL3;
        }
        return null;
    }

    /// Tabela de registradores de debug (`op0=2,op1=0`, B10.7) — armazenamento puro, sem
    /// enforcement de `RO`/`WO` (ver javadoc de `Aarch64SystemRegisterId`). Só `n=0` de
    /// `DBGBVR`/`DBGBCR`/`DBGWVR`/`DBGWCR` (decisão de escopo da task).
    private static Aarch64SystemRegisterId decodeDebugRegisterId(int crn, int crm, int op2) {
        if (crn == SYSREG_CRN_MDSCR && crm == SYSREG_CRM_MDSCR && op2 == SYSREG_OP2_MDSCR) {
            return Aarch64SystemRegisterId.MDSCR_EL1;
        }
        if (crn == SYSREG_CRN_OSLAR && crm == SYSREG_CRM_OSLAR && op2 == SYSREG_OP2_OSLAR) {
            return Aarch64SystemRegisterId.OSLAR_EL1;
        }
        if (crn == SYSREG_CRN_OSLSR && crm == SYSREG_CRM_OSLSR && op2 == SYSREG_OP2_OSLSR) {
            return Aarch64SystemRegisterId.OSLSR_EL1;
        }
        if (crn == SYSREG_CRN_DBG_BKPT_WATCH && crm == SYSREG_CRM_DBGBVR0) {
            if (op2 == SYSREG_OP2_DBGBVR) {
                return Aarch64SystemRegisterId.DBGBVR0_EL1;
            }
            if (op2 == SYSREG_OP2_DBGBCR) {
                return Aarch64SystemRegisterId.DBGBCR0_EL1;
            }
            if (op2 == SYSREG_OP2_DBGWVR) {
                return Aarch64SystemRegisterId.DBGWVR0_EL1;
            }
            if (op2 == SYSREG_OP2_DBGWCR) {
                return Aarch64SystemRegisterId.DBGWCR0_EL1;
            }
        }
        return null;
    }

    /// `CTR_EL0`/`DCZID_EL0` (B6.10, `op0=3,op1=3,CRn=0`) — identidade de cache CONSTANTE da CPU,
    /// resolvida DIRETO pelo {@link dev.vitorsilverio.armjitter.core64.Aarch64Core} (mesma
    /// disciplina de {@link Aarch64SystemRegisterId#MIDR_EL1}, não passa pelo
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus} do hospedeiro como o
    /// timer genérico vizinho — ver javadoc da classe).
    private static Aarch64SystemRegisterId decodeCacheIdentityRegisterId(int crm, int op2) {
        if (crm == SYSREG_CRM_CTR && op2 == SYSREG_OP2_CTR_EL0) {
            return Aarch64SystemRegisterId.CTR_EL0;
        }
        if (crm == SYSREG_CRM_DCZID && op2 == SYSREG_OP2_DCZID_EL0) {
            return Aarch64SystemRegisterId.DCZID_EL0;
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

    /// B19.28: recusa NOMEADA para `MSR SVCR`, distinta de {@link #unsupported} — o encoding FOI
    /// reconhecido (`FEAT_SME` presente na arquitetura), mas nenhum estado ZA/streaming-SVE é
    /// modelado ainda (ver o Javadoc de {@link Aarch64Feature#SCALABLE_MATRIX_EXTENSION}). G8:
    /// diagnóstico rastreável em vez de indistinguível de "encoding desconhecido".
    private static UnsupportedOperationException unsupportedScalableMatrixExtension(
            int word, long address, int mask, int imm) {
        return new UnsupportedOperationException(
                "AArch64: MSR SVCR (mask=0b" + Integer.toBinaryString(mask) + ", imm=" + imm
                        + ") reconhecida, mas FEAT_SME (estado ZA/streaming-SVE) ainda não é"
                        + " modelado em 0x" + Long.toHexString(address)
                        + ": 0x" + Integer.toHexString(word));
    }

    /// Marcador de tamanho fixo de instrução A64, exposto para os chamadores que precisam avançar
    /// o PC sem re-hardcodar o literal `4`.
    public static int instructionSizeBytes() {
        return INSTRUCTION_SIZE_BYTES;
    }
}
