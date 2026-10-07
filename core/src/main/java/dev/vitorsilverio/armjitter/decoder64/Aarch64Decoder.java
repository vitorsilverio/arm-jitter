package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
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
    /// E15.9/E15.15a–g: o AdvSIMD inteiro (e a criptografia de prefixo `11001110`) — o espaço `bit21=0` inteiro
    /// ({@link AdvSimdBit21ZeroRows}, {@link AdvSimdPermuteCopyRows}), a criptografia fora do
    /// `bit21=1` ({@link CryptoRows}) e o "three same" inteiro e FP ({@link AdvSimdThreeSameRows},
    /// {@link AdvSimdThreeSameFpRows}); E15.15d: "three different" ({@link AdvSimdThreeDifferentRows}),
    /// across lanes e scalar pairwise ({@link AdvSimdAcrossLanesRows}) e AES/SHA de dois registradores
    /// (também em {@link CryptoRows}); E15.15e: two-register misc ({@link AdvSimdTwoRegisterMiscRows}); E15.15f:
    /// shift by immediate e modified immediate ({@link AdvSimdShiftImmediateRows}); E15.15g: indexed element
    /// ({@link AdvSimdIndexedElementRows}) — já filtrados por
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
                concat(AdvSimdShiftImmediateRows.ROWS, AdvSimdIndexedElementRows.ROWS))))), architecture);
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
        // E15.15g: o resto da classe é o AdvSIMD inteiro mais a criptografia de prefixo `11001110` — a
        // {@link #advSimdTable}. Toda linha fixa `bit31`, o prefixo `bits[28:24]` e, no escalar, `bit30=1`
        // (`AdvSimdIndexedElementRowsTest#advSimdTableRowsFixTheClassPrefix`): o que não casa é recusado (G8).
        return decodeAdvSimdTable(word, address);
    }

    /// E15.15a: {@link #advSimdTable}; o que não casa nenhuma linha é recusado (G8).
    private Ir64Op decodeAdvSimdTable(int word, long address) {
        Ir64Op op = advSimdTable.decode(word, address);
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
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
