package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.util.ArrayList;
import java.util.List;
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
/// {@link AdvSimdMoveOp64.LoadStoreMultiple}/{@link AdvSimdMoveOp64.LoadStoreSingle}/
/// {@link AdvSimdMoveOp64.LoadSingleReplicate}.
///
/// B11.2: recebe uma {@link Aarch64Architecture} no construtor, mesmo padrão dos decoders de
/// extensão de 32 bits (ex. `Thumb2DataProcessingDecoder(ArmArchitecture)`) — {@link #architecture}
/// ainda não gateia NENHUM encoding (zero-diff comportamental, G3): é só fiação, o primeiro gate de
/// decode real é B11.4. O construtor sem argumento (preservado por compatibilidade) usa
/// {@link Aarch64Architecture#ARMV8_0_A}, que representa exatamente o que este decoder já
/// implementa incondicionalmente hoje.
public final class Aarch64Decoder {
    private final Aarch64Architecture architecture;
    /// `true` quando a arquitetura declara `FEAT_SME` (B18.2): só então as instruções ilegais em modo
    /// streaming são embrulhadas em `Ir64Op.StreamingRestricted`.
    private final boolean streamingModeRestrictions;
    private final Aarch64SveDecoder sveDecoder;
    private final Aarch64SmeDecoder smeDecoder;
    /// E15.9/E15.15a–c: o AdvSIMD já migrado para tabela — o espaço `bit21=0` inteiro
    /// ({@link AdvSimdBit21ZeroRows}, {@link AdvSimdPermuteCopyRows}), a criptografia fora do
    /// `bit21=1` ({@link CryptoRows}) e o "three same" inteiro e FP ({@link AdvSimdThreeSameRows},
    /// {@link AdvSimdThreeSameFpRows}); E15.15d: "three different" ({@link AdvSimdThreeDifferentRows}),
    /// across lanes e scalar pairwise ({@link AdvSimdAcrossLanesRows}) e AES/SHA de dois registradores
    /// (também em {@link CryptoRows}) — já filtrados por {@link #architecture}.
    private final DecodeTable<Ir64Op> advSimdTable;
    /// E15.10: a classe "Data Processing — Immediate" inteira (`bits[28:26]=100`).
    private final DecodeTable<Ir64Op> dataProcessingImmediateTable;
    /// E15.13: a classe "Data Processing — Register" (`op0 = x101`, `bit26=0`).
    private final DecodeTable<Ir64Op> dataProcessingRegisterTable;
    /// E15.14: o FP escalar (`bits[30:24]=0011110` e o 3-source `bits[31:24]=00011111`).
    private final DecodeTable<Ir64Op> scalarFpTable;
    /// E15.12: a classe "Loads and Stores" inteira (`op0 = x1x0`).
    private final DecodeTable<Ir64Op> loadStoreTable;
    /// E15.11: classe branch/exceção/sistema fora do espaço `1101010100`.
    private final DecodeTable<Ir64Op> branchExceptionTable;
    /// E15.11: espaço de sistema `1101010100` — instruções (`op0=0x`) e registradores (`op0=1x`).
    private final DecodeTable<Ir64Op> systemTable;
    /// E15.11: restos de espaço do sistema (hint/cache/`SYS` NOP, escaninhos de debug e de ID),
    /// consultados só quando {@link #systemTable} não casa.
    private final DecodeTable<Ir64Op> systemFallbackTable;

    /// Cria um decoder para {@link Aarch64Architecture#ARMV8_0_A} — equivalente ao comportamento
    /// deste decoder antes de B11.2 (tudo que está implementado, incondicional).
    public Aarch64Decoder() {
        this(Aarch64Architecture.ARMV8_0_A);
    }

    /// Cria um decoder para a arquitetura informada. Ainda sem efeito observável (B11.2 é só
    /// fiação) — ver o Javadoc da classe.
    public Aarch64Decoder(Aarch64Architecture architecture) {
        this.architecture = Objects.requireNonNull(architecture, "architecture");
        this.streamingModeRestrictions = architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
        this.sveDecoder = new Aarch64SveDecoder(architecture);
        this.smeDecoder = new Aarch64SmeDecoder(architecture);
        this.advSimdTable = DecodeTable.forArchitecture(concat(concat(AdvSimdBit21ZeroRows.ROWS,
                AdvSimdPermuteCopyRows.ROWS), concat(concat(CryptoRows.ROWS, concat(AdvSimdThreeSameRows.ROWS,
                AdvSimdThreeSameFpRows.ROWS)), concat(AdvSimdThreeDifferentRows.ROWS, AdvSimdAcrossLanesRows.ROWS))),
                architecture);
        this.dataProcessingImmediateTable = DecodeTable.forArchitecture(DataProcessingImmediateRows.ROWS, architecture);
        this.dataProcessingRegisterTable = DecodeTable.forArchitecture(DataProcessingRegisterRows.ROWS, architecture);
        this.scalarFpTable = DecodeTable.forArchitecture(ScalarFpRows.ROWS, architecture);
        this.loadStoreTable = DecodeTable.forArchitecture(concat(concat(LoadStoreRegisterRows.ROWS,
                LoadStoreExclusiveRows.ROWS), concat(MemoryOperationRows.ROWS, AdvSimdLoadStoreRows.ROWS)), architecture);
        this.branchExceptionTable = DecodeTable.forArchitecture(BranchExceptionRows.ROWS, architecture);
        this.systemTable = DecodeTable.forArchitecture(
                concat(SystemInstructionRows.ROWS, SystemRegisterRows.ROWS), architecture);
        this.systemFallbackTable = DecodeTable.forArchitecture(
                concat(SystemInstructionRows.FALLBACK_ROWS, SystemRegisterRows.FALLBACK_ROWS), architecture);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
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
    /// E15.11: `bits[31:22]=1101010100` — o espaço de sistema (`MRS`/`MSR`/`SYS`/hints/barreiras/PSTATE).
    private static final int SYSTEM_SPACE_MASK = 0b1111111111 << 22;
    private static final int SYSTEM_SPACE_VALUE = 0b1101010100 << 22;
    /// B17.4: classe SVE (`op0 = 0010`, bits[28:26] = `001`). Roteada para {@link Aarch64SveDecoder}.
    private static final int CLASS_SVE = 0b001;
    /// B17.28: `bits[28:26] = 000` — o espaço da SME (com `bit31 = 1`), onde moram os `LD1`/`ST1` multi-vetor de SVE2.1.
    private static final int CLASS_SME_SPACE = 0b000;

    // ── Campos comuns de Data Processing: sf(31) op(30) S(29) ... Rn(9:5) Rd(4:0) ─────────────
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_FIELD_MASK = 0b1_1111;

    // ── Loads and Stores (classe `x1x0`, ARM DDI 0487 C4.1.3): bit27 fixo=1, bit25 fixo=0 ─────
    private static final int LOAD_STORE_CLASS_BIT27_SHIFT = 27;
    private static final int LOAD_STORE_CLASS_BIT25_SHIFT = 25;

    // ── Data Processing — Register (ARM DDI 0487 C4.1, B6.3.1): bit27 fixo=1, bit25 fixo=1 ────
    // ── (mesma ambiguidade dos bits[28:26] que Loads-and-Stores já resolveu com bit25 fora do ──
    // ── switch de 3 bits — Fatos de referência #1 da task; distingue de Loads-and-Stores só ───
    // ── por bit25, e de Data Processing SIMD&FP por bits fora do escopo desta fatia). ─────────
    private static final int DP_REGISTER_CLASS_BIT27_SHIFT = 27;
    private static final int DP_REGISTER_CLASS_BIT25_SHIFT = 25;

    // ── Data Processing — Scalar Floating-Point and Advanced SIMD (B6.5.3): classe IRMÃ de "Data
    // ── Processing — Register" (bit27=1/bit25=1), distinguida por bit26=1. O FP escalar é o prefixo
    // ── bits[30:24]="0011110" (tabela {@link ScalarFpRows}, E15.14). ⚠️ bits[28:24]="11110" sem o
    // ── bit30 NÃO basta: "Advanced SIMD scalar two-register miscellaneous" (`SQABS_s`/`FCVTXN_s`…)
    // ── tem o mesmo prefixo de 5 bits, separado só pelo bit30 (B8.4, G8).
    private static final int FP_SIMD_CLASS_BIT26_SHIFT = 26;
    private static final int SCALAR_FP_FIXED_PREFIX_SHIFT = 24;
    private static final int SCALAR_FP_FIXED_PREFIX_MASK = 0b111_1111;
    private static final int SCALAR_FP_FIXED_PREFIX_PATTERN = 0b001_1110;

    // ── Floating-point data-processing (3 source), B8.4: o prefixo usa os 8 bits (bits[31:24]="00011111")
    // ── porque bits[28:24]="11111" é também "Advanced SIMD scalar x indexed element"/"scalar shift by
    // ── immediate" (`FMUL_si`/`SSHR_s`), separados só pelo bit30 — CONFERIDO contra o `a64.decode`.
    private static final int FP_THREE_SOURCE_FIXED_PREFIX_SHIFT = 24;
    private static final int FP_THREE_SOURCE_FIXED_PREFIX_MASK = 0xFF;
    private static final int FP_THREE_SOURCE_FIXED_PREFIX_PATTERN = 0b0001_1111;

    // ── AdvSIMD inteiro — aritmética/comparação (B8.7): "three same"/"three same pairwise" ────────
    // ── (bit10=1), "three different" alargando/largo+estreito/estreitando + "across lanes" + ──────
    // ── "two-register miscellaneous" (bit10=0) — todos dentro do MESMO prefixo bits[28:24]="01110" ──
    // ── (vetorial, `Q`=bit30 real) OU bits[28:24]="11110"+bit30=1 (escalar D-only, mesmo truque de
    // ── prefixo que já distingue "Advanced SIMD scalar two-register miscellaneous" de
    // ── o FP escalar acima). Fatos de referência conferidos contra `a64.decode`/
    // ── `translate-a64.c` reais do QEMU + corpus `aarch64-none-elf-as`/`objdump` (devkitA64).
    private static final int ADVSIMD_INT_PREFIX_SHIFT = 24;
    private static final int ADVSIMD_INT_PREFIX_MASK = 0b1_1111;
    private static final int ADVSIMD_INT_PREFIX_VECTOR_PATTERN = 0b0_1110;
    private static final int ADVSIMD_INT_PREFIX_SCALAR_PATTERN = 0b1_1110;
    private static final int ADVSIMD_INT_SCALAR_BIT30_SHIFT = 30;
    // ── E15.9b: `bit31` (o bit alto de `op0` em "Data Processing — Scalar FP and Advanced SIMD")
    // ── é `0` em TODA classe AdvSIMD (`op0 = 0xx0`/`01x1`); com `bit31=1` estes prefixos são cripto
    // ── (`11001110`, já desviado antes por `CRYPTO_SHA3_PREFIX_PATTERN`) ou não alocados.
    private static final int ADVSIMD_INT_BIT31_SHIFT = 31;
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
    /// Tamanho fixo do escalar D-only (`esz=3`) — a forma vetorial NUNCA produz `esz=3`/`q=false`
    /// de verdade (doubleword exige `q=true` no hardware real, só existe `.2d`), então este par é
    /// reaproveitado como sentinela da forma escalar sem ambiguidade (ver javadoc de
    /// {@link AdvSimdIntegerOp64.ArithmeticThreeSame}).
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
    /// E15.9c: `FCVTXN_s` é `01 1 11110 0 1 100001 01101 10` — `bits[23:22]` fixos em `01`.
    private static final int ADVSIMD_FCVTXN_SCALAR_SIZE = 0b01;
    /// B19.4: opcode (bits[15:11]) de `FCVTL`/`BF*CVTL`/`F*CVTL` dentro do slot narrow/widen
    /// (`Rm=00001`) — `!u` = `FCVTL_v` (ISA base); `u` = `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL`
    /// (`FEAT_FP8`, B19.11).
    private static final int ADVSIMD_FCVTL_OPCODE = 0b0_1111;
    /// B19.11b (`FEAT_FP8FMA`): opcode (bits[15:12]) de `FMLAL_hb_vi` dentro de
    /// {@link #decodeAdvancedSimdIndexedElement} — hijacka o MESMO slot `sizeField=DOUBLEWORD`
    /// (`0b11`) que `BFMLAL_vi` usa (opcode `0b1111`), nunca colide. Confirmado byte a byte contra
    /// `target/isa-decode/a64.decode:1350`.
    private static final int ADVSIMD_FP8_FMA_INDEXED_OPCODE_HB = 0b0000;
    /// B19.11b (`FEAT_FP8FMA`): opcode (bits[15:12]) de `FMLALL_sb_vi` — `U`(bit29)=`1` fixo,
    /// `bit23`=`0` fixo (mas bit22 aqui NÃO é `sizeField`: é metade de `idxn`, ver Javadoc de
    /// {@link #decodeAdvancedSimdIndexedElement}). Confirmado byte a byte contra
    /// `target/isa-decode/a64.decode:1353`.
    private static final int ADVSIMD_FP8_FMA_INDEXED_OPCODE_SB = 0b1000;
    /// B19.11b (`FEAT_FP8FMA`): `Rm` de `FMLAL_hb_vi`/`FMLALL_sb_vi` é restrito a 3 bits (`V0`-`V7`)
    /// — MENOS que os 4 bits (`V0`-`V15`) de `BFMLAL_vi`/`FMLAL_vi`, porque o campo `%hlm4` desta
    /// família rouba um bit A MAIS do encoding para o índice (4 bits, `0`-`15`, contra os 3 bits de
    /// `BFMLAL_vi`/`FMLAL_vi`, `0`-`7`) — achado real medido bit a bit contra
    /// `target/isa-decode/a64.decode:1350-1354` (`%hlm4  11:1 19:3`, MESMO nome que `BFMLAL_vi` usa,
    /// mas layout de bits DIFERENTE: aqui a parte baixa é `bits[21:19]`, 3 bits, não `bits[21:20]`,
    /// 2 bits — não reusar {@link #ADVSIMD_INDEXED_LM_SHIFT}/{@link #ADVSIMD_INDEXED_LM_MASK}).
    private static final int ADVSIMD_FP8_FMA_INDEXED_RM_MASK = 0b111;
    private static final int ADVSIMD_FP8_FMA_INDEXED_INDEX_SHIFT = 19;
    private static final int ADVSIMD_FP8_FMA_INDEXED_INDEX_MASK = 0b111;
    /// B19.11c (`FEAT_FP8DOT2`): opcode (bits[15:12]) de `FDOT_hb_vi` dentro de
    /// {@link #decodeAdvancedSimdIndexedElement} — `sizeField=HALFWORD`(`0b01`), `U`=`0` fixo,
    /// MESMO valor usado por `FDOT_sb_vi` (`sizeField=HALF_PRECISION`(`0b00`), B19.11d)
    /// — nunca colidem, discriminados só por `sizeField`. Confirmado byte a byte contra
    /// `target/isa-decode/a64.decode:1356-1357`.
    private static final int ADVSIMD_FP8_DOT_INDEXED_OPCODE = 0b0000;
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
    /// B8.9: bit `a` do encoding real de "AdvSIMD three same (FP)"/"two-register misc (FP)" —
    /// posição IDÊNTICA ao bit alto de {@link #ADVSIMD_INT_SIZE_SHIFT} (`esz` inteiro reaproveita
    /// essa posição como campo livre de 2 bits; nas formas FP, só o bit BAIXO — `bit22`, "sz" — é o
    /// tamanho do elemento; o bit ALTO — `bit23`, "a" — é mais um bit de discriminação de opcode,
    /// nunca tamanho). Conferido contra `a64.decode` real do QEMU (`@qrrr_sd`/`@qrr_sd`: `esz=%esz_sd`
    /// deriva só de `sz`, bit22).
    private static final int ADVSIMD_FP_A_BIT_SHIFT = 23;
    /// B19.13: bits[1:0] do `opcode` indexado (4 bits) reservados/sempre `0` em
    /// `FMLAL_vi`/`FMLSL_vi`/`FMLAL2_vi`/`FMLSL2_vi` — qualquer valor fora de
    /// `0000`/`0100`/`1000`/`1100` não é desta família.
    private static final int ADVSIMD_FHM_INDEXED_OPCODE_RESERVED_MASK = 0b0011;
    /// Bit3 do `opcode` indexado: `0`=`FMLAL_vi`/`FMLSL_vi`, `1`=`FMLAL2_vi`/`FMLSL2_vi`.
    private static final int ADVSIMD_FHM_INDEXED_OPCODE_TOP_BIT = 0b1000;
    /// Bit2 do `opcode` indexado: `0`=soma (`FMLAL*`), `1`=subtração (`FMLSL*`).
    private static final int ADVSIMD_FHM_INDEXED_OPCODE_SUBTRACT_BIT = 0b0100;

    // ── B11.12/B19.10: prefixo fixo de 8 bits (`11001110`) da criptografia SHA3/SHA-512/SM3/SM4
    // ── ({@link CryptoRows}). `bits[28:24]="01110"` sozinho colide com o prefixo vetorial do AdvSIMD
    // ── (`bit31` decide), por isso o prefixo inteiro é testado antes de {@link #decodeAdvancedSimdInteger}.
    private static final int CRYPTO_SHA3_PREFIX_SHIFT = 24;
    private static final int CRYPTO_SHA3_PREFIX_MASK = 0xFF;
    private static final int CRYPTO_SHA3_PREFIX_PATTERN = 0b1100_1110;

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
    /// E15.9c: bit logo acima de `immh` — fixo em `0` em "shift by immediate" e em "modified
    /// immediate" (`0 Q U 011110 0 immh immb opcode 1`); com `1` e `bit10=1` não há nada alocado.
    private static final int ADVSIMD_SHIFT_BIT23_SHIFT = 23;
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
    /// B19.20: rotação `rr` (2 bits) de `FCMLA_vi`, em unidades de {@link #ADVSIMD_FCMA_ROTATION_UNIT_DEGREES}.
    private static final int ADVSIMD_FCMA_ROTATION_MASK = 0b11;
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

    /// B19.12 (`FEAT_I8MM`): `size`(23:22) `10` ("S") de `USDOT_vi` — as formas não indexadas
    /// (`USDOT_v`/`SMMLA`/`UMMLA`/`USMMLA`) são linhas de {@link AdvSimdBit21ZeroRows} desde a E15.15a.
    private static final int ADVSIMD_I8MM_SIZE_WORD = 0b10;

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


    private static final int INSTRUCTION_SIZE_BYTES = 4;

    /// Decodifica a instrução de 4 bytes no endereço informado.
    ///
    /// @param memory barramento de onde a instrução é lida
    /// @param address endereço (múltiplo de 4) da instrução
    /// @return operação IR-64 correspondente
    /// @throws UnsupportedOperationException quando o encoding está fora da fatia B6.1
    public Ir64Op decode(AddressSpace64 memory, long address) {
        return decode(memory.read32(address), address);
    }

    /// Mesmo que {@link #decode(AddressSpace64, long)} com a palavra já lida: função pura de `word` e
    /// `address` (o `Ir64BlockExecutor#step` guarda o resultado por `pc`, E15.10b).
    public Ir64Op decode(int word, long address) {
        Ir64Op op = decodeWord(word, address);
        // B18.2: só presets com FEAT_SME ganham o embrulho (G3) — a decisão depende de `PSTATE.SM`, que
        // só existe na execução.
        return streamingModeRestrictions && StreamingModeRestrictions.isIllegalInStreamingMode(word)
                ? new Ir64Op.StreamingRestricted(op)
                : op;
    }

    private Ir64Op decodeWord(int word, long address) {
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
            case CLASS_SVE -> decodeSve(word, address);
            case CLASS_SME_SPACE -> decodeMultiVector(word, address);
            default -> throw unsupported(word, address);
        };
    }

    /// B17.28: os `LD1`/`ST1` multi-vetor governados por predicado-como-contador vivem no espaço `bits[28:26] = 000` (o da
    /// SME), fora da classe SVE. B18.3: `ZERO`/`MOVA`/`MOVAZ` (prefixo `0xC0`) também vivem aqui — tentados
    /// DEPOIS do {@link Aarch64SveDecoder} (prefixos `0xA0`/`0xA1`, sem colisão de máscara). O que nenhum
    /// dos dois reconhece é recusado (G8).
    private Ir64Op decodeMultiVector(int word, long address) {
        Ir64Op op = sveDecoder.decodeMultiVector(word, address);
        if (op == null) {
            op = smeDecoder.decode(word, address);
        }
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
    }

    /// Classe SVE (B17.4): o que o {@link Aarch64SveDecoder} não reconhece é recusado (G8).
    private Ir64Op decodeSve(int word, long address) {
        Ir64Op op = sveDecoder.decode(word, address);
        if (op == null) {
            op = smeDecoder.decodeSveSpace(word, address);
        }
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
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

    /// E15.12: tabela das quatro famílias de load/store; o que não casa nenhuma linha é recusado (G8).
    private Ir64Op decodeLoadsAndStores(int word, long address) {
        Ir64Op op = loadStoreTable.decode(word, address);
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
    }

    /// E15.10: tabela {@link DataProcessingImmediateRows}; o que não casa nenhuma linha é recusado (G8).
    private Ir64Op decodeDataProcessingImmediate(int word, long address) {
        Ir64Op op = dataProcessingImmediateTable.decode(word, address);
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
    }

    /// Classe "Data Processing — Register" (`bit27=1`, `bit25=1`). `bit26=1` é a classe irmã "Data
    /// Processing — Scalar Floating-Point and Advanced SIMD" (B6.5.3); `bit26=0` é a tabela
    /// {@link DataProcessingRegisterRows} (E15.13) — o que não casa nenhuma linha é recusado (G8).
    private Ir64Op decodeDataProcessingRegister(int word, long address) {
        if (((word >>> FP_SIMD_CLASS_BIT26_SHIFT) & 1) != 0) {
            return decodeDataProcessingScalarFpSimd(word, address);
        }
        Ir64Op op = dataProcessingRegisterTable.decode(word, address);
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
    }

    /// Sub-dispatch da classe "Data Processing — Scalar Floating-Point and Advanced SIMD"
    /// (`bit26=1`, D1 da task B6.5.3). O FP escalar (`bits[30:24]=0011110` e o 3-source,
    /// `bits[31:24]=00011111`) é a tabela {@link ScalarFpRows} (E15.14) — palavra desses prefixos
    /// que não casa nenhuma linha é recusada aqui (G8), nunca segue para o AdvSIMD.
    private Ir64Op decodeDataProcessingScalarFpSimd(int word, long address) {
        int fixedPrefix = (word >>> SCALAR_FP_FIXED_PREFIX_SHIFT) & SCALAR_FP_FIXED_PREFIX_MASK;
        int threeSourcePrefix =
                (word >>> FP_THREE_SOURCE_FIXED_PREFIX_SHIFT) & FP_THREE_SOURCE_FIXED_PREFIX_MASK;
        if (fixedPrefix == SCALAR_FP_FIXED_PREFIX_PATTERN
                || threeSourcePrefix == FP_THREE_SOURCE_FIXED_PREFIX_PATTERN) {
            Ir64Op op = scalarFpTable.decode(word, address);
            if (op == null) {
                throw unsupported(word, address);
            }
            return op;
        }
        // B11.12/B19.10: prefixo `11001110` (`FEAT_SHA3`/`SHA512`/`SM3`/`SM4`) — linhas de
        // {@link CryptoRows} na {@link #advSimdTable} (E15.15a); o que não casa é recusado (G8).
        int crypto3Prefix = (word >>> CRYPTO_SHA3_PREFIX_SHIFT) & CRYPTO_SHA3_PREFIX_MASK;
        if (crypto3Prefix == CRYPTO_SHA3_PREFIX_PATTERN) {
            return decodeAdvSimdTable(word, address);
        }
        // B8.7: Advanced SIMD vetorial (prefixo(28:24)="01110") ou escalar D-only inteiro
        // (prefixo(28:24)="11110" com bit30=1, mesmo truque de prefixo de
        // "Advanced SIMD scalar two-register miscellaneous" citado acima) — aritmética/
        // comparação inteira. Bits21=0 dentro desses prefixos (`AdvSIMD modified immediate`/
        // `shift by immediate`, formas com registrador indexado, FP vetorial) ficam fora,
        // reconhecidos só pela AUSÊNCIA de qualquer combinação da tabela (G8).
        return decodeAdvancedSimdInteger(word, address);
    }

    /// E15.15a: {@link #advSimdTable}; o que não casa nenhuma linha é recusado (G8).
    private Ir64Op decodeAdvSimdTable(int word, long address) {
        Ir64Op op = advSimdTable.decode(word, address);
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
    }

    /// Sub-dispatch de "AdvSIMD inteiro — aritmética e comparação" (B8.7): entra já sabendo que
    /// `bit26=1` e que o prefixo escalar-FP (D1 acima) NÃO bateu. Distingue vetorial (prefixo
    /// bits[28:24]="01110", `Q`=bit30 real) de escalar D-only (prefixo="11110"+bit30=1, mesmo
    /// truque de {@link #decodeDataProcessingScalarFpSimd}); dentro de cada um, `bit21=1` (senão
    /// "AdvSIMD modified immediate"/outras formas fora de escopo) e depois `bit10` separam
    /// "three same"/"three same pairwise" (`1`) de "three different"/"across lanes"/"two-register
    /// miscellaneous" (`0`, sub-roteado por `Rm`, ver as constantes `ADVSIMD_INT_RM_*`).
    private Ir64Op decodeAdvancedSimdInteger(int word, long address) {
        // E15.9b: um ponto só, antes de qualquer desvio (shift/indexed, `bit21=0`/`1`) — toda forma
        // AdvSIMD tem `bit31=0`; sem isto metade do espaço `bit31=1` saía como a instrução de
        // `bit31=0` correspondente (G8).
        if (((word >>> ADVSIMD_INT_BIT31_SHIFT) & 1) != 0) {
            throw unsupported(word, address);
        }
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
            // E15.9/E15.15a: o espaço `bit21=0` inteiro (formas com feature, EXT/permute/TBL/copy, SHA
            // de três registradores) é a {@link #advSimdTable}, já filtrada pelo preset; a exclusão
            // mútua entre as linhas é verificada por teste. O que não casa é recusado (G8).
            return decodeAdvSimdTable(word, address);
        }
        // B8.8: campo `size` real SEMPRE lido, mesmo escalar — B8.7 assumia `esz=3` fixo para TODO
        // escalar (válido só para `ADD_s`/`SUB_s`/`CM**_s`/`ABS_s`/`NEG_s`/`CM**0_s`, que exigem
        // literalmente `11` nesses bits no encoding real). `SQADD_s`/`SQSHL_s`/`SUQADD_s`/etc
        // aceitam QUALQUER tamanho (`@rrr_e`/`@r2r_e` reais, não `@rrr_d`) — ler o campo cru e
        // validar por OPCODE (não por prefixo) é o único jeito de decodificar os dois corretamente
        // sem duplicar o dispatch. Achado: isso também CORRIGE um bug latente da B8.7 — antes desta
        // task, `esz` era forçado a `3` mesmo quando os bits reais não eram `11`, então um encoding
        // reservado (`ADD_s` com `size!=11`) era silenciosamente decodificado como `ADD_s` válido
        // em vez de cair em `UNIMPLEMENTED` (G8); agora as linhas de {@link AdvSimdThreeSameRows}/
        // `decodeVectorUnaryOpcode` validam o `esz` real contra o que cada opcode aceita.
        int esz = (word >>> ADVSIMD_INT_SIZE_SHIFT) & ADVSIMD_INT_SIZE_MASK;
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
        int opcode = (word >>> ADVSIMD_INT_OPCODE_SHIFT) & ADVSIMD_INT_OPCODE_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER_FIELD_MASK;
        int rd = word & REGISTER_FIELD_MASK;
        boolean threeSameShape = ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) != 0;
        if (threeSameShape) {
            // E15.15b/E15.15c: o "three same" inteiro ({@link AdvSimdThreeSameRows}) e FP
            // ({@link AdvSimdThreeSameFpRows}); o que não casa é recusado (G8).
            return decodeAdvSimdTable(word, address);
        }
        // E15.15d: "three different" (`bit11=0`, `Rm` registrador livre — {@link AdvSimdThreeDifferentRows}), "across
        // lanes"/"scalar pairwise" (`Rm=1000x`, {@link AdvSimdAcrossLanesRows}) e AES/SHA de dois registradores
        // (`Rm=01000`, {@link CryptoRows}) são tabela. E8: o discriminador entre "three different" e as formas que
        // reaproveitam `Rm` como opcode é o `bit11` (o bit baixo do `opcode` de 5 bits lido acima), NUNCA o valor de
        // `Rm` — `smull v0.8h, v1.8b, v0.8b` tem o `Rm` do two-register misc. Só o two-register misc (`bit11=1`,
        // `Rm` `0000x`/`1100x`) segue na cascata abaixo, até a E15.15e.
        boolean rmEncodesFixedOpcode = (opcode & 1) != 0;
        if (!rmEncodesFixedOpcode || !isTwoRegisterMiscSlot(rm)) {
            return decodeAdvSimdTable(word, address);
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
                    return new AdvSimdIntegerOp64.ArithmeticUnary(byteOp, false, q, 0, rd, rn);
                }
                throw unsupported(word, address);
            }
            Ir64VectorUnaryOp op = decodeVectorUnaryOpcode(u, opcode, scalar);
            if (op != null) {
                validateScalarUnaryEsz(word, address, scalar, op, esz);
                validateVectorUnaryEsz(word, address, scalar, op, q, esz);
                return new AdvSimdIntegerOp64.ArithmeticUnary(op, scalar, q, esz, rd, rn);
            }
            // B8.9 (vetorial: `FABS_v`/`FNEG_v`/`FCM**0_v`) + B19.3 (escalar: só as 5
            // comparações-contra-zero `FCMGT0_s`/`FCMGE0_s`/`FCMEQ0_s`/`FCMLE0_s`/`FCMLT0_s`) —
            // MESMO slot `Rm=00000` do inteiro (achado real da triagem, ver javadoc de
            // {@link Ir64VectorFpUnaryOp}). `FABS`/`FNEG` FP NÃO têm forma escalar aqui (os
            // escalares já são {@link FpOp64.Alu} desde B8.4) ⇒ com prefixo escalar são
            // reservados ⇒ `unsupported` (G8, via {@link #fpUnaryOpHasScalarForm}).
            boolean fpRmZeroA = ((esz >>> 1) & 1) != 0;
            int fpRmZeroEsz = 2 + (esz & 1);
            Ir64VectorFpUnaryOp fpRmZeroOp = decodeVectorFpUnaryRmZeroOpcode(u, fpRmZeroA, opcode);
            if (fpRmZeroOp != null) {
                if (scalar ? !fpUnaryOpHasScalarForm(fpRmZeroOp) : isSingleDoublewordArrangement(fpRmZeroEsz, q)) {
                    throw unsupported(word, address);
                }
                return new AdvSimdFpOp64.FpArithmeticUnary(fpRmZeroOp, scalar, q, fpRmZeroEsz, rd, rn);
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
                return new AdvSimdIntegerOp64.ArithmeticNarrowUnary(narrowOp, scalar, q, esz, rd, rn);
            }
            // B8.20: `SHLL`/`SHLL2` — MESMO slot, opcode `0b0_0111`/`U=1`; reaproveita 100%
            // `AdvSimdIntegerOp64.ShiftWidenImmediate`/`USHLL` (B8.8) — `SHLL` É literalmente "zero-extend
            // e desloca à esquerda pela largura INTEIRA do elemento estreito" (`8<<esz`, quantidade
            // FIXA, não um imediato genérico), mas a fórmula do executor (`zext(Rn) << shift`) é
            // idêntica; sem forma escalar/doubleword real (G8).
            if (!scalar && u && opcode == ADVSIMD_TWO_REG_MISC_SHLL_OPCODE) {
                if (esz == ADVSIMD_INT_SCALAR_ESZ) {
                    throw unsupported(word, address);
                }
                return new AdvSimdIntegerOp64.ShiftWidenImmediate(Ir64VectorShiftWidenOp.USHLL, q, esz, 8 << esz, rd, rn);
            }
            // B8.20: `URECPE`/`URSQRTE` — MESMO slot/opcode (`0b1_1001`) que `FCVTAS`/`FCVTAU` usam
            // no dispatch FP abaixo, discriminados pelo bit ALTO de `esz` (`a`, ver
            // {@link #decodeVectorFpUnaryRmOneOpcode}): `esz==` {@link #ADVSIMD_ESZ_WORD} (`a=1`)
            // cai aqui, sem colisão com `FCVTAS`(`a=0`)/`FCVTAU` — conferido exaustivamente contra
            // corpus real (devkitA64). Só arranjo `.2s`/`.4s` (sem forma escalar/doubleword, G8).
            if (!scalar && opcode == ADVSIMD_URECPE_URSQRTE_OPCODE && esz == ADVSIMD_ESZ_WORD) {
                Ir64VectorUnaryOp recipOp = u ? Ir64VectorUnaryOp.URSQRTE : Ir64VectorUnaryOp.URECPE;
                return new AdvSimdIntegerOp64.ArithmeticUnary(recipOp, false, q, esz, rd, rn);
            }
            // B19.3: `FRECPX_s` — só forma AdvSIMD-escalar; colide de opcode com `SQRT` vetorial
            // (`decodeVectorFpUnaryRmOneOpcode`, `key==0b11`) ⇒ `if` EXPLÍCITO ANTES da tabela
            // compartilhada, NUNCA nela (Armadilha 2 da task).
            if (scalar && !u && opcode == ADVSIMD_FRECPX_OPCODE && ((esz >>> 1) & 1) != 0) {
                return new AdvSimdFpOp64.FpArithmeticUnary(
                        Ir64VectorFpUnaryOp.FRECPX, true, false, 2 + (esz & 1), rd, rn);
            }
            // B19.3: `FCVTXN_s` — `f64`→`f32` round-to-odd; só escalar (`FCVTXN_v` é B19.4).
            // `esz` do record = ENTRADA `f64` (doubleword); os `bits[23:22]` crus do encoding
            // `@rr_s` valem `01` e NÃO representam tamanho aqui.
            // E15.9c: só `bits[23:22]=01` — os outros 3 valores saíam como `FCVTXN` (G8).
            if (scalar && u && opcode == ADVSIMD_FCVTXN_OPCODE && esz == ADVSIMD_FCVTXN_SCALAR_SIZE) {
                return new AdvSimdFpOp64.FpArithmeticUnary(
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
                    return new AdvSimdFpOp64.FpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.FCVTN, q, 1 + precisionSz, rd, rn);
                }
                if (opcode == ADVSIMD_FCVTXN_OPCODE && u && precisionA == 0 && precisionSz == 1) {
                    return new AdvSimdFpOp64.FpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.FCVTXN, q, ADVSIMD_ESZ_WORD, rd, rn);
                }
                if (opcode == ADVSIMD_FCVTL_OPCODE && !u && precisionA == 0) {
                    return new AdvSimdFpOp64.FpConvertPrecision(
                            Ir64VectorFpConvertPrecisionOp.FCVTL, q, 1 + precisionSz, rd, rn);
                }
                // B19.7 (`FEAT_BF16`): `BFCVTN_v`/`BFCVTN2` — MESMO slot, `!u && a==1` (o caso que
                // este `if` deixava cair no `unsupported` de baixo antes desta task). `esz` do
                // record é sempre `1` (`bf16` tem a mesma largura de `f16`; não há forma `f64`→
                // `bf16`, por isso `precisionSz` não entra na condição).
                // E15.9c: `sz`(bit22)=0 fixo — com `1` saía como `BFCVTN` (G8).
                if (opcode == ADVSIMD_FCVTXN_OPCODE && !u && precisionA == 1 && precisionSz == 0
                        && architecture.has(Aarch64Feature.BFLOAT16)) {
                    return new AdvSimdFpOp64.FpConvertPrecision(
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
                    return new AdvSimdFpOp64.FpConvertFromFp8(secondStream, bfloat16Destination, q, rd, rn);
                }
                // Toda combinação restante ⇒ reservado ⇒ `unsupported` (G8).
                throw unsupported(word, address);
            }
            // B8.9 (vetorial: `FSQRT_v`/`FRINTx_v`/`FRECPE_v`/`FRSQRTE_v`/`SCVTF_vi`/...) + B19.3
            // (escalar: `RECPE`/`RSQRTE` + as 12 conversões `@icvt` int↔FP escala 0). `SQRT`/
            // `FRINTx` FP NÃO têm forma escalar aqui (os escalares já são {@link FpOp64.Alu}/
            // {@link FpOp64.Round} desde B8.4/B8.5) ⇒ com prefixo escalar são reservados ⇒
            // `unsupported` (G8, via {@link #fpUnaryOpHasScalarForm}).
            boolean fpRmOneA = ((esz >>> 1) & 1) != 0;
            int fpRmOneEsz = 2 + (esz & 1);
            Ir64VectorFpUnaryOp fpRmOneOp = decodeVectorFpUnaryRmOneOpcode(u, fpRmOneA, opcode);
            if (fpRmOneOp != null) {
                if (scalar ? !fpUnaryOpHasScalarForm(fpRmOneOp) : isSingleDoublewordArrangement(fpRmOneEsz, q)) {
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
                return new AdvSimdFpOp64.FpArithmeticUnary(fpRmOneOp, scalar, q, fpRmOneEsz, rd, rn);
            }
            throw unsupported(word, address);
        }
        if (rm == ADVSIMD_INT_RM_FP16_TWO_REG_MISC) {
            // B19.5.4 (`FEAT_FP16`): MESMA tabela de opcode do slot `Rm=00000` (achado medido bit a
            // bit contra `a64.decode`: `FABS_v` é `opcode=11111` nos dois, `FCMGT0_*` é `11001` nos
            // dois, ...) — reaproveitada sem duplicar (item 3 de "Inclui" da task). `esz` do record
            // é SEMPRE `ADVSIMD_ESZ_HALFWORD`: `bit22` aqui é fixo em `1` e NÃO é tamanho (Armadilha
            // 2) — só `bit23` (`a`) continua real e é o que a tabela usa para desambiguar.
            if (!architecture.has(Aarch64Feature.FP16) || !isFp16TwoRegisterMiscBit22Set(esz)) {
                throw unsupported(word, address);
            }
            boolean fpHalfZeroA = ((esz >>> 1) & 1) != 0;
            Ir64VectorFpUnaryOp fpHalfZeroOp = decodeVectorFpUnaryRmZeroOpcode(u, fpHalfZeroA, opcode);
            if (fpHalfZeroOp != null) {
                if (scalar && !fpUnaryOpHasScalarForm(fpHalfZeroOp)) {
                    throw unsupported(word, address);
                }
                return new AdvSimdFpOp64.FpArithmeticUnary(fpHalfZeroOp, scalar, q, ADVSIMD_ESZ_HALFWORD, rd, rn);
            }
            throw unsupported(word, address);
        }
        // O slot que sobra é {@link #ADVSIMD_INT_RM_FP16_NARROW_UNARY} (`Rm=11001`).
        // B19.5.4 (`FEAT_FP16`): MESMO slot `Rm=00001`. `FRECPX_s` continua exigindo o `if`
        // EXPLÍCITO antes da tabela compartilhada (mesma colisão de opcode com `SQRT`, U=0 x
        // U=1, que a B19.3 documentou) — o resto (`FSQRT_v`/`FRINTx_v`/`FRECPE`/`FRSQRTE`/
        // `SCVTF`/`UCVTF`/as 10 conversões `FCVTx{S,U}` vetoriais+escalares) reusa
        // `decodeVectorFpUnaryRmOneOpcode` sem tabela nova. BF16/FP8 (`F1CVTL`/`F2CVTL`/
        // `BF1CVTL`/`BF2CVTL`/`BFCVTN_v`) moram no slot `0b0_0001` de verdade (Armadilha 4) —
        // este bloco nunca os alcança.
        if (!architecture.has(Aarch64Feature.FP16) || !isFp16TwoRegisterMiscBit22Set(esz)) {
            throw unsupported(word, address);
        }
        if (scalar && !u && opcode == ADVSIMD_FRECPX_OPCODE && ((esz >>> 1) & 1) != 0) {
            return new AdvSimdFpOp64.FpArithmeticUnary(
                    Ir64VectorFpUnaryOp.FRECPX, true, false, ADVSIMD_ESZ_HALFWORD, rd, rn);
        }
        boolean fpHalfOneA = ((esz >>> 1) & 1) != 0;
        Ir64VectorFpUnaryOp fpHalfOneOp = decodeVectorFpUnaryRmOneOpcode(u, fpHalfOneA, opcode);
        if (fpHalfOneOp != null) {
            // E15.9c: `FRINT32*`/`FRINT64*` só têm `s`/`d` — a forma `_h` não existe (G8).
            if ((scalar && !fpUnaryOpHasScalarForm(fpHalfOneOp)) || isDirectedRoundingToIntegral(fpHalfOneOp)) {
                throw unsupported(word, address);
            }
            return new AdvSimdFpOp64.FpArithmeticUnary(fpHalfOneOp, scalar, q, ADVSIMD_ESZ_HALFWORD, rd, rn);
        }
        throw unsupported(word, address);
    }

    /// E15.15d: os 4 slots de `Rm` do two-register misc — `0000x` (inteiro e FP `_sd`) e `1100x` (FP16).
    private static boolean isTwoRegisterMiscSlot(int rm) {
        int slot = rm & ~ADVSIMD_INT_RM_NARROW_UNARY;
        return slot == ADVSIMD_INT_RM_TWO_REG_MISC || slot == ADVSIMD_INT_RM_FP16_TWO_REG_MISC;
    }

    /// B19.3: quais operações de {@link #decodeVectorFpUnaryRmZeroOpcode}/
    /// {@link #decodeVectorFpUnaryRmOneOpcode} têm forma AdvSIMD-ESCALAR real ("two-register
    /// miscellaneous" escalar + conversões `@icvt` escalares). As de fora (`ABS`/`NEG` FP,
    /// `SQRT`, `RINTx`) só existem vetoriais — ou já são {@link FpOp64.Alu}/
    /// {@link FpOp64.Round} escalares por outro encoding — então um encoding escalar que
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

    /// Valores de `esz` (`bits[23:22]`) com nome (G6): elemento de 16 e de 32 bits.
    private static final int ADVSIMD_ESZ_HALFWORD = 1;
    private static final int ADVSIMD_ESZ_WORD = 2;

    /// E15.9c: elemento de 64 bits sem `Q` — o arranjo `.1d`, que nenhuma instrução AdvSIMD vetorial
    /// aritmética tem.
    private static boolean isSingleDoublewordArrangement(int esz, boolean q) {
        return esz == ADVSIMD_INT_SCALAR_ESZ && !q;
    }

    /// E15.9c: "two-register misc (FP16)" é `0 Q U 01110 a 111100 opcode 10` — `bit22` (o bit baixo
    /// do campo `size` lido como `esz`) é `1` fixo; com `0` nada está alocado nos slots `Rm=1100x`.
    private static boolean isFp16TwoRegisterMiscBit22Set(int esz) {
        return (esz & 1) != 0;
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
    private void validateVectorUnaryEsz(int word, long address, boolean scalar, Ir64VectorUnaryOp op, boolean q,
            int esz) {
        if (scalar) {
            return;
        }
        boolean doubleword = esz == ADVSIMD_INT_SCALAR_ESZ;
        boolean valid = switch (op) {
            // Grupo de 64 bits — elemento word (esz=2) é o maior que cabe mais de uma vez; `esz=3`
            // seria um grupo de 1 elemento (no-op), reservado.
            case REV64 -> esz <= ADVSIMD_ESZ_WORD;
            // Grupo de 32 bits — só byte/half cabem mais de uma vez.
            case REV32 -> esz <= ADVSIMD_ESZ_HALFWORD;
            // Grupo de 16 bits — só byte cabe mais de uma vez.
            case REV16 -> esz == 0;
            // E15.9c: alargamento pareado (resultado `2*esize`) e `CLS`/`CLZ` não têm elemento de 64
            // bits; o resto tem `.2d`, nunca `.1d`.
            case SADDLP, UADDLP, SADALP, UADALP, CLS, CLZ -> !doubleword;
            default -> !isSingleDoublewordArrangement(esz, q);
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
                return new AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement(true, 2, rotation, rd, rn, rm, index);
            }
            if (sizeField == ADVSIMD_INDEXED_SIZE_HALFWORD && (q || !h)) {
                int rmH = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
                int index = q ? ((h ? 0b10 : 0) | (l ? 0b01 : 0)) : (l ? 1 : 0);
                return new AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement(q, 1, rotation, rd, rn, rmH, index);
            }
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
                return new AdvSimdFpOp64.FpDotProductBFloat16ByElement(q, rd, rn, rmH, l ? 1 : 0);
            }
            if (sizeField == ADVSIMD_INDEXED_SIZE_DOUBLEWORD) {
                // `BFMLAL_vi`: índice `H:L:M` completo (3 bits), MESMA fórmula do ramo `HALFWORD`
                // abaixo — `q` aqui é o seletor `B`/`T` (`top`), NÃO largura (ver Javadoc de
                // {@link AdvSimdFpOp64.FpMultiplyAddLongBFloat16#top}).
                int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
                int index = (h ? 0b100 : 0) | lm;
                return new AdvSimdFpOp64.FpMultiplyAddLongBFloat16ByElement(q, rd, rn, rmH, index);
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
                return new AdvSimdIntegerOp64.IntegerDotProductByElement(q, false, true, rd, rn, rmH, index);
            }
            if (sizeField == ADVSIMD_I8MM_INDEXED_SIZE_SUDOT) {
                // `SUDOT_vi`: `Rn` com sinal, `Rm` sem sinal (só existe indexada — não há `SUDOT_v`).
                return new AdvSimdIntegerOp64.IntegerDotProductByElement(q, true, false, rd, rn, rmH, index);
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
        // E17: `U` TEM de replicar o bit `top` — os 4 pares `(U, opcode)` com `U ≠ top` são
        // `MUL_vi`(0,1000)/`MLA_vi`(1,0000)/`MLS_vi`(1,0100)/`SQDMULH_vi`(0,1100) da forma `.s`, que
        // sem esta checagem saíam como `FMLAL` em todo preset com `FEAT_FHM`.
        if (!scalar && sizeField == ADVSIMD_INDEXED_SIZE_WORD
                && (opcode & ADVSIMD_FHM_INDEXED_OPCODE_RESERVED_MASK) == 0
                && u == ((opcode & ADVSIMD_FHM_INDEXED_OPCODE_TOP_BIT) != 0)
                && architecture.has(Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG)) {
            int rmH = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
            int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
            int index = (h ? 0b100 : 0) | lm;
            boolean top = (opcode & ADVSIMD_FHM_INDEXED_OPCODE_TOP_BIT) != 0;
            boolean subtract = (opcode & ADVSIMD_FHM_INDEXED_OPCODE_SUBTRACT_BIT) != 0;
            return new AdvSimdFpOp64.FpMultiplyAddLongByElement(q, top, subtract, rd, rn, rmH, index);
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
            return new AdvSimdIntegerOp64.IntegerDotProductByElement(q, !u, !u, rd, rn, rm, index);
        }
        // B19.11b (`FEAT_FP8FMA`): `FMLAL_hb_vi` hijacka o MESMO slot `sizeField=DOUBLEWORD`(`11`)
        // que `BFMLAL_vi` usa (opcode PRÓPRIO {@link #ADVSIMD_FP8_FMA_INDEXED_OPCODE_HB}, nunca
        // colide com o `1111` de `BFMLAL_vi`), com o layout `H:L:M` de 4 bits/`Rm` de 3 bits PRÓPRIO
        // desta família (ver Javadoc de {@link #ADVSIMD_FP8_FMA_INDEXED_RM_MASK} — NÃO o
        // `H:L:M`/`Rm` de 4 bits que `BFMLAL_vi`/`FMLAL_vi` usam). `U`(bit29)=`0` fixo (diferente de
        // `FMLALL_sb_vi` abaixo, que fixa `U`=`1`). Sem isto, `opcode=0000` não bate nenhuma entrada
        // de {@link #decodeAdvancedSimdIndexedFp}/{@link #decodeAdvancedSimdIndexedInt} (conferido
        // exaustivamente) e cairia direto no G8 de baixo mesmo com a feature presente.
        if (!scalar && !u && sizeField == ADVSIMD_INDEXED_SIZE_DOUBLEWORD
                && opcode == ADVSIMD_FP8_FMA_INDEXED_OPCODE_HB
                && architecture.has(Aarch64Feature.FP8_FUSED_MULTIPLY_ADD)) {
            int rmFp8 = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_FP8_FMA_INDEXED_RM_MASK;
            int index = (h ? 0b1000 : 0)
                    | ((word >>> ADVSIMD_FP8_FMA_INDEXED_INDEX_SHIFT) & ADVSIMD_FP8_FMA_INDEXED_INDEX_MASK);
            int idxn = (word >>> ADVSIMD_INT_Q_SHIFT) & 1;
            return new AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement(false, idxn, rd, rn, rmFp8, index);
        }
        // B19.11b (`FEAT_FP8FMA`): `FMLALL_sb_vi` — `U`(bit29)=`1` fixo, `opcode`(bits[15:12])=
        // {@link #ADVSIMD_FP8_FMA_INDEXED_OPCODE_SB}, `bit23`=`0` fixo — mas bit22 aqui NÃO é
        // `sizeField`: é a metade BAIXA de `idxn` (`%fmlall_idxn` do QEMU, MESMA fórmula de
        // `FMLALL_sb_v` em {@link AdvSimdBit21ZeroRows}), por isso este intercepto NÃO usa
        // `sizeField` (que mediria um valor sem sentido, `0b00`/`0b01` dependendo de `idxn`) e checa
        // `bit23` diretamente. Nunca colide com nenhuma chave de
        // {@link #decodeAdvancedSimdIndexedFp}/{@link #decodeAdvancedSimdIndexedInt} (conferido
        // exaustivamente: `key=0b1_1000` não aparece em nenhuma das duas tabelas).
        if (!scalar && u && ((word >>> ADVSIMD_FP_A_BIT_SHIFT) & 1) == 0
                && opcode == ADVSIMD_FP8_FMA_INDEXED_OPCODE_SB
                && architecture.has(Aarch64Feature.FP8_FUSED_MULTIPLY_ADD)) {
            int rmFp8 = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_FP8_FMA_INDEXED_RM_MASK;
            int index = (h ? 0b1000 : 0)
                    | ((word >>> ADVSIMD_FP8_FMA_INDEXED_INDEX_SHIFT) & ADVSIMD_FP8_FMA_INDEXED_INDEX_MASK);
            int idxnHigh = (word >>> ADVSIMD_INT_Q_SHIFT) & 1;
            int idxnLow = (word >>> ADVSIMD_INT_SIZE_SHIFT) & 1;
            int idxn = (idxnHigh << 1) | idxnLow;
            return new AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement(true, idxn, rd, rn, rmFp8, index);
        }
        // B19.11c (`FEAT_FP8DOT2`): `FDOT_hb_vi` — sizeField=`HALFWORD`(`01`), opcode
        // {@link #ADVSIMD_FP8_DOT_INDEXED_OPCODE}, `U`=`0` fixo. **Ao contrário de**
        // `FMLAL_hb_vi` (B19.11b, `Rm`/índice PRÓPRIOS), `FDOT_hb_vi` cai bit a bit no MESMO layout
        // `Rm`(4 bits)/`H:L:M`(3 bits) que o `case HALFWORD` genérico abaixo já usa para o inteiro —
        // confirmado contra `target/isa-decode/a64.decode` (`@qrrx_h`/`%hlm`, MESMA fórmula) — mas
        // precisa ser interceptado AQUI porque `opcode=0000,u=0` está livre em
        // {@link #decodeAdvancedSimdIndexedInt} (nenhuma chave `(U,opcode)` o reivindica) e cairia
        // no G8 de baixo sem este bloco, mesmo com a feature presente. `FDOT_sb_vi` (B19.11d) mede
        // `sizeField=HALF_PRECISION`(`00`) com um layout de `Rm` DIFERENTE (5 bits/`H:L`) — ver o
        // bloco espelho abaixo.
        if (!scalar && !u && sizeField == ADVSIMD_INDEXED_SIZE_HALFWORD
                && opcode == ADVSIMD_FP8_DOT_INDEXED_OPCODE
                && architecture.has(Aarch64Feature.FP8_DOT_PRODUCT_2WAY)) {
            int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INDEXED_RM_H_MASK;
            int lm = (word >>> ADVSIMD_INDEXED_LM_SHIFT) & ADVSIMD_INDEXED_LM_MASK;
            int index = (h ? 0b100 : 0) | lm;
            return new AdvSimdFpOp64.Fp8DotProductByElement(false, q, rd, rn, rm, index);
        }
        // B19.11d (`FEAT_FP8DOT4`): `FDOT_sb_vi` — mede `sizeField=HALF_PRECISION`(`00`), MESMO
        // `opcode`(bits[15:12])={@link #ADVSIMD_FP8_DOT_INDEXED_OPCODE}, `U`=`0` fixo, mas ao
        // contrário do irmão `FDOT_hb_vi` acima (`Rm`/índice `H:L:M` de 4 bits, layout do ramo
        // `HALFWORD`), `FDOT_sb_vi` cai no layout `Rm`(5 bits)/`H:L`(2 bits) do ramo `WORD`
        // (`@qrrx_s`) — confirmado contra `target/isa-decode/a64.decode` (`1356:FDOT_sb_vi 0.00
        // 1111 00 . ..... 0000 . 0 ..... ..... @qrrx_s`) e byte a byte contra `objdump` real
        // (WSL, `-march=armv9.5-a+fp8dot4`). Precisa ser interceptado AQUI porque `size=00` com
        // `opcode=0000` está livre no `case ADVSIMD_INDEXED_SIZE_HALF_PRECISION` abaixo (que só
        // reconhece os opcodes de `MUL`/`MLA`/`MLS`/`MULX` de `FEAT_FP16` via
        // {@link #decodeAdvancedSimdIndexedFp}) e cairia no G8 de baixo sem este bloco, mesmo com
        // a feature presente.
        if (!scalar && !u && sizeField == ADVSIMD_INDEXED_SIZE_HALF_PRECISION
                && opcode == ADVSIMD_FP8_DOT_INDEXED_OPCODE
                && architecture.has(Aarch64Feature.FP8_DOT_PRODUCT_4WAY)) {
            int rm = (word >>> ADVSIMD_INT_RM_SHIFT) & ADVSIMD_INT_RM_MASK;
            int index = (h ? 0b10 : 0) | (l ? 0b01 : 0);
            return new AdvSimdFpOp64.Fp8DotProductByElement(true, q, rd, rn, rm, index);
        }
        Ir64Op result = switch (sizeField) {
            // Doubleword: só ponto flutuante (`FMUL`/`FMLA`/`FMLS`/`FMULX` "d") — `Rm` de 5 bits,
            // índice = só `H` (`L`/bit21 é fixo `0` no encoding real, ver `@rrx_d`/`@qrrx_d`).
            case ADVSIMD_INDEXED_SIZE_DOUBLEWORD -> {
                if (l || (!scalar && !q)) {
                    // reservado (G8): bit21 nunca é `1` nas formas D reais; E15.9c: e a vetorial só
                    // existe `.2d` (`Q=1`).
                    yield null;
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
        return new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, scalar, q, esz, rd, rn, rm, index);
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
                return new AdvSimdIntegerOp64.ArithmeticThreeSameByElement(rdmOp, scalar, q, esz, rd, rn, rm, index);
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
            return new AdvSimdIntegerOp64.ArithmeticThreeSameByElement(threeSameOp, scalar, q, esz, rd, rn, rm, index);
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
        return new AdvSimdIntegerOp64.ArithmeticWideningByElement(wideningOp, scalar, q, esz, rd, rn, rm, index);
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
        if (((word >>> ADVSIMD_SHIFT_BIT23_SHIFT) & 1) != 0) { // `bit10=1` já garantido pelo chamador
            throw unsupported(word, address);
        }
        boolean q = !scalar && ((word >>> ADVSIMD_INT_Q_SHIFT) & 1) != 0;
        boolean u = ((word >>> ADVSIMD_INT_U_SHIFT) & 1) != 0;
        int immh = (word >>> ADVSIMD_SHIFT_IMMH_SHIFT) & ADVSIMD_SHIFT_IMMH_MASK;
        int immb = (word >>> ADVSIMD_SHIFT_IMMB_SHIFT) & ADVSIMD_SHIFT_IMMB_MASK;
        int esz = highestSetImmhBit(immh);
        if (!scalar && isSingleDoublewordArrangement(esz, q)) {
            // E15.9c: `immh<3>=1` com `Q=0` (arranjo `.1d`) é reservado em TODA a classe vetorial —
            // antes só as conversões FP↔ponto fixo e o grupo estreita/alarga recusavam (G8).
            throw unsupported(word, address);
        }
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
            return new AdvSimdIntegerOp64.ShiftNarrowImmediate(narrowOp, scalar, q, esz, rightShift, rd, rn);
        }
        if (opcode == 0b1_0100) {
            // `SSHLL`/`USHLL` — sem forma escalar real (G8).
            if (scalar || esz == ADVSIMD_INT_SCALAR_ESZ) {
                throw unsupported(word, address);
            }
            Ir64VectorShiftWidenOp widenOp = u ? Ir64VectorShiftWidenOp.USHLL : Ir64VectorShiftWidenOp.SSHLL;
            return new AdvSimdIntegerOp64.ShiftWidenImmediate(widenOp, q, esz, leftShift, rd, rn);
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
                // B19.4: a forma VETORIAL (`_vf`, `!scalar`) reaproveita o MESMO record com `q` real
                // (`immh<3>==1 && Q==0` já recusado no topo do método, E15.9c).
                boolean toFloat = opcode == ADVSIMD_SHIFT_FCVT_FIXED_TO_FLOAT_OPCODE;
                // `rightShift` (`2*esize - immh:immb`, já calculado) é EXATAMENTE o `#fbits` do
                // `@fcvt_fixed`/`@fcvtq_{s,d}` (faixa `1..esize`); `!u` = variante assinada.
                return new AdvSimdFpOp64.FpConvertFixedPoint(scalar, q, esz, rightShift, toFloat, !u, rd, rn);
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
        return new AdvSimdIntegerOp64.ShiftImmediate(op, scalar, q, esz, shift, rd, rn);
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

    /// `VFPExpandImm`-equivalente de A64 (Armadilhas da task B6.5.3): MESMO algoritmo conceitual
    /// do precedente VFP32 (`StandardIrBuilder#vfpExpandImm`, `vfp_expand_imm` do QEMU) — sinal
    /// (bit7) + expoente replicado (bit6 invertido, {@code notBit6}) + mantissa (bits5:0) — mas
    /// duplicado aqui (não reaproveitado por chamada direta) porque o campo `imm8` de origem já
    /// chega CONTÍGUO do encoding A64 (bits[20:13], ver o `FMOV` imediato de {@link ScalarFpRows}), diferente
    /// do VFP32 que precisa remontar `imm8` a partir de dois pedaços de 4 bits antes de expandir —
    /// os dois mundos não compartilham decoder (G2/G3), e a assinatura já recebe o valor pronto.
    static long expandFpImmediate(int imm8, boolean doublePrecision) {
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
    /// o `FMOV` imediato de {@link ScalarFpRows}, MESMO valor semântico). Devolve `null` (nunca um encoding
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
        int op = (word >>> ADVSIMD_MODIFIED_IMM_OP_SHIFT) & 1;
        if (suffix6 == ADVSIMD_MODIFIED_IMM_FMOVI_H_SUFFIX) {
            // E15.9c: `FMOV` de meia precisão é `op=0`; com `op=1` o slot não é alocado (G8).
            if (op != 0) {
                return null;
            }
            // `FMOVI_v_h` (`FEAT_FP16`): imediato de meia precisão replicado por todas as lanes
            // `H` de 64 bits (4 cópias) — igual à disciplina do MOV puro, nunca recalculado depois.
            if (!architecture.has(Aarch64Feature.FP16)) {
                throw unsupported(word, address);
            }
            long half16 = expandFpImmediateHalf(imm8);
            long imm64 = half16 | (half16 << 16) | (half16 << 32) | (half16 << 48);
            return new AdvSimdMoveOp64.ModifiedImmediate64(AdvSimdModifiedImmediateOp.MOV, q, rd, imm64);
        }
        int fixedTwoBits = (word >>> ADVSIMD_MODIFIED_IMM_FIXED2_SHIFT) & 0b11;
        if (fixedTwoBits != ADVSIMD_MODIFIED_IMM_FIXED2_PATTERN) {
            return null;
        }
        int cmode = (word >>> ADVSIMD_MODIFIED_IMM_CMODE_SHIFT) & 0b1111;
        if (AdvSimdModifiedImmediate.isReservedInAarch32(cmode, op)) {
            // E15.9c: só existe `.2d` (`Q=1`).
            if (!q) {
                return null;
            }
            // `cmode=1111,op=1`: reservado em AArch32, em AArch64 é `FMOV` (vector, immediate) de
            // 64 bits — reaproveita {@link #expandFpImmediate} (MESMO algoritmo VFPExpandImm de
            // o `FMOV` imediato de {@link ScalarFpRows}, só o `imm8` já reconstruído acima).
            long imm64 = expandFpImmediate(imm8, true);
            return new AdvSimdMoveOp64.ModifiedImmediate64(AdvSimdModifiedImmediateOp.MOV, q, rd, imm64);
        }
        AdvSimdModifiedImmediate.Expanded expanded = AdvSimdModifiedImmediate.expand(imm8, cmode, op);
        return new AdvSimdMoveOp64.ModifiedImmediate64(expanded.op(), q, rd, expanded.imm64());
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
    static long expandFpImmediateHalf(int imm8) {
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

    /// E15.11: no espaço `1101010100`, {@link SystemInstructionRows}/{@link SystemRegisterRows} e, se nenhuma
    /// linha casar, os restos de espaço (fallback); fora dele, {@link BranchExceptionRows}. O que não casa é
    /// recusado (G8).
    private Ir64Op decodeBranchExceptionSystem(int word, long address) {
        Ir64Op op;
        if ((word & SYSTEM_SPACE_MASK) == SYSTEM_SPACE_VALUE) {
            op = systemTable.decode(word, address);
            if (op == null) {
                op = systemFallbackTable.decode(word, address);
            }
        } else {
            op = branchExceptionTable.decode(word, address);
        }
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
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
