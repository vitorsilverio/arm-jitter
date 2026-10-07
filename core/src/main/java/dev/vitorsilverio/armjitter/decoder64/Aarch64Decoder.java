package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
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
    /// (também em {@link CryptoRows}); E15.15e: two-register misc ({@link AdvSimdTwoRegisterMiscRows}); E15.15f:
    /// shift by immediate e modified immediate ({@link AdvSimdShiftImmediateRows}) — já filtrados por
    /// {@link #architecture}.
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
                AdvSimdThreeSameFpRows.ROWS)), concat(concat(AdvSimdThreeDifferentRows.ROWS,
                AdvSimdAcrossLanesRows.ROWS), concat(AdvSimdTwoRegisterMiscRows.ROWS,
                AdvSimdShiftImmediateRows.ROWS)))), architecture);
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
    private static final int ADVSIMD_INT_SIZE_SHIFT = 22;
    private static final int ADVSIMD_INT_SIZE_MASK = 0b11;
    private static final int ADVSIMD_INT_RM_SHIFT = 16;
    private static final int ADVSIMD_INT_RM_MASK = 0b1_1111;
    private static final int ADVSIMD_INT_BIT10_SHIFT = 10;
    /// Tamanho fixo do escalar D-only (`esz=3`) — a forma vetorial NUNCA produz `esz=3`/`q=false`
    /// de verdade (doubleword exige `q=true` no hardware real, só existe `.2d`), então este par é
    /// reaproveitado como sentinela da forma escalar sem ambiguidade (ver javadoc de
    /// {@link AdvSimdIntegerOp64.ArithmeticThreeSame}).
    private static final int ADVSIMD_INT_SCALAR_ESZ = 3;
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

    // ── "Advanced SIMD shift by immediate" (B8.8) e "× indexed element" (B8.19): prefixo bits[28:24]="01111"
    // ── (vetorial, `Q`=bit30 real) OU "11111"+bit30=1 (escalar) — UM BIT A MAIS que o prefixo de "three same"/
    // ── "two-register miscellaneous" acima ("01110"/"11110"). O shift by immediate (e o modified immediate,
    // ── `immh=0000`) é {@link AdvSimdShiftImmediateRows} desde a E15.15f.
    private static final int ADVSIMD_SHIFT_PREFIX_VECTOR_PATTERN = 0b0_1111;
    private static final int ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN = 0b1_1111;

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
    /// truque de {@link #decodeDataProcessingScalarFpSimd}). Só o indexed element (prefixo `x1111`
    /// com `bit10=0`) ainda é cascata; o resto é a {@link #advSimdTable} (E15.15a–E15.15f).
    private Ir64Op decodeAdvancedSimdInteger(int word, long address) {
        // E15.9b: um ponto só, antes de qualquer desvio (shift/indexed, `bit21=0`/`1`) — toda forma
        // AdvSIMD tem `bit31=0`; sem isto metade do espaço `bit31=1` saía como a instrução de
        // `bit31=0` correspondente (G8).
        if (((word >>> ADVSIMD_INT_BIT31_SHIFT) & 1) != 0) {
            throw unsupported(word, address);
        }
        int prefix = (word >>> ADVSIMD_INT_PREFIX_SHIFT) & ADVSIMD_INT_PREFIX_MASK;
        // B8.8: "shift by immediate" e "indexed element" têm prefixo PRÓPRIO (`01111` vetorial/`11111` escalar,
        // um bit a mais que o `01110`/`11110` das demais), discriminados por `bit10` (`1`=shift, `0`=indexed).
        boolean shiftPrefix = prefix == ADVSIMD_SHIFT_PREFIX_VECTOR_PATTERN
                || prefix == ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN;
        boolean vector = prefix == ADVSIMD_INT_PREFIX_VECTOR_PATTERN || prefix == ADVSIMD_SHIFT_PREFIX_VECTOR_PATTERN;
        boolean scalar = (prefix == ADVSIMD_INT_PREFIX_SCALAR_PATTERN || prefix == ADVSIMD_SHIFT_PREFIX_SCALAR_PATTERN)
                && ((word >>> ADVSIMD_INT_SCALAR_BIT30_SHIFT) & 1) != 0;
        if (!vector && !scalar) {
            throw unsupported(word, address);
        }
        if (shiftPrefix && ((word >>> ADVSIMD_INT_BIT10_SHIFT) & 1) == 0) {
            return decodeAdvancedSimdIndexedElement(word, address, scalar);
        }
        // E15.15a–E15.15f: o resto do espaço (`bit21=0`, "three same", "three different", reduções, AES/SHA de dois
        // registradores, two-register misc, shift by immediate e modified immediate) é a {@link #advSimdTable}, já
        // filtrada pelo preset; o que não casa é recusado (G8).
        return decodeAdvSimdTable(word, address);
    }

    /// Valor de `esz` com nome (G6): elemento de 16 bits.
    private static final int ADVSIMD_ESZ_HALFWORD = 1;

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
