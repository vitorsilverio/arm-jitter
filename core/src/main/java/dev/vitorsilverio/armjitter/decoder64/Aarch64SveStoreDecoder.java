package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64.Store.Op;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;

/// Decoder SVE dos stores da B17.18, nos prefixos `0xE4`/`0xE5` (`### SVE Memory Store Group` inteiro do `sve.decode`
/// do QEMU: `STR` de vetor e de predicado, `ST1`/`STNT1` contíguos, `ST[234]`, `ST[234]Q` e o scatter `ST1_zprz`/
/// `ST1_zpiz`/`ST1Q`). Cada padrão é o `decodetree` transcrito e conferido contra `aarch64-none-elf-as`.
///
/// **O store NÃO usa `dtype`** (o load usa): o par (`msz`, `esz`) é enumerado linha a linha no `.decode` — o próprio
/// arquivo diz *"Enumerate msz lest we conflict with STR_zri"* — e só valem os pares com `msz <= esz`. A tabela abaixo
/// (`bits[24:23] = msz`, `bits[22:21] = esz`) é a transcrição desse enumerado, nunca derivada do load. `esz = 4` (elemento
/// de 128 bits) não cabe em 2 bits e por isso só nasce do padrão do encoding (`msz = 2`/`3` com `bits[22:21] = 00`/`10`).
///
/// Recusas (G8, devolvem `null`): `Rm = 31` em toda forma escalar+escalar (no `ST1Q` ele é `XZR`); `msz > esz`; scatter com
/// `msz = 0` e deslocamento escalado; as formas SVE2.1 (`ST1` `.Q`, `ST[234]Q`, `ST1Q`) sem `FEAT_SVE2p1`; os buracos do
/// `decodetree`. O scatter não-temporal (`STNT1_zprz`, opcode `001`) é da B17.25 e cai aqui em `null`.
final class Aarch64SveStoreDecoder {
    private static final int PREFIX_SHIFT = 25;
    private static final int PREFIX_STORES = 0b1110010;

    private static final int REGISTER_MASK = 0b11111;
    private static final int PREDICATE_MASK = 0b111;
    private static final int PD_MASK = 0b1111;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int RM_SHIFT = 16;
    private static final int OPCODE_SHIFT = 13;
    private static final int OPCODE_MASK = 0b111;
    private static final int MSZ_SHIFT = 23;
    private static final int FIELD_MASK = 0b11;
    private static final int LOW_FIELD_SHIFT = 21;
    private static final int NREG_QUAD_SHIFT = 22;
    private static final int TOP_BIT = 24;
    private static final int BIT_22 = 22;
    private static final int BIT_21 = 21;
    private static final int BIT_20 = 20;
    private static final int SIGNED_IMMEDIATE_BITS = 4;
    private static final int IMM4_MASK = 0b1111;
    private static final int IMM5_MASK = 0b11111;
    private static final int RM_UNALLOCATED = 31;
    private static final int STORE_PREDICATE_BIT4_MASK = 0x10;

    // Bits 15:13.
    private static final int OP_STORE_PREDICATE_OR_QUAD = 0b000;
    private static final int OP_SCATTER_QUAD = 0b001;
    private static final int OP_STORE_REGISTER = 0b010;
    private static final int OP_STRUCTURE_REGISTER = 0b011;
    private static final int OP_SCATTER_UNSIGNED_OFFSET = 0b100;
    private static final int OP_SCATTER_64_OR_IMMEDIATE = 0b101;
    private static final int OP_SCATTER_SIGNED_OFFSET = 0b110;
    private static final int OP_STORE_IMMEDIATE = 0b111;

    /// `ST1Q`: bits `31:21` = `11100100001`.
    // `bits[22:21]` do `STNT1_zprz`.
    private static final int NT_FIELD_DOUBLE = 0b00;
    private static final int NT_FIELD_WORD = 0b10;
    private static final int ST1Q_TOP_SHIFT = 21;
    private static final int ST1Q_TOP_VALUE = 0b11100100001;

    private static final int MSZ_BYTE = 0;
    private static final int MSZ_HALF = 1;
    private static final int MSZ_WORD = 2;
    private static final int MSZ_DOUBLE = 3;
    private static final int MSZ_QUAD = 4;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLE = 3;
    private static final int ESZ_QUAD = 4;

    // `STR` (bits 21:16 e 12:10 formam o imediato de 9 bits).
    private static final int STR_IMM_HIGH_SHIFT = 16;
    private static final int STR_IMM_HIGH_MASK = 0b111111;
    private static final int STR_IMM_LOW_SHIFT = 10;
    private static final int STR_IMM_LOW_MASK = 0b111;
    private static final int STR_IMM_LOW_BITS = 3;
    private static final int STR_IMMEDIATE_BITS = 9;
    /// `esz` das linhas `msz = 2`/`3` de `bits[22:21]` (`ST1W`/`ST1D` com elemento maior que o acesso): `-1` = buraco.
    private static final int[] WORD_ESZ_BY_FIELD = {ESZ_QUAD, -1, ESZ_WORD, ESZ_DOUBLE};
    private static final int[] DOUBLE_ESZ_BY_FIELD = {-1, -1, ESZ_QUAD, ESZ_DOUBLE};

    private final Aarch64Architecture architecture;

    Aarch64SveStoreDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra dos prefixos `0xE4`/`0xE5`. `null` = não é deste grupo (ou é um encoding recusado).
    Ir64Op decode(int word, long address) {
        if (word >>> PREFIX_SHIFT != PREFIX_STORES) {
            return null;
        }
        return switch ((word >>> OPCODE_SHIFT) & OPCODE_MASK) {
            case OP_STORE_PREDICATE_OR_QUAD -> decodeOpcode000(word, address);
            case OP_SCATTER_QUAD -> decodeOpcode001(word, address);
            case OP_STORE_REGISTER -> decodeOpcode010(word, address);
            case OP_STRUCTURE_REGISTER -> structure(word, true, address);
            case OP_STORE_IMMEDIATE -> decodeOpcode111(word, address);
            case OP_SCATTER_UNSIGNED_OFFSET -> scatterIndex(word, SveMemoryOp64.Store.OFFSET_UXTW, address);
            case OP_SCATTER_SIGNED_OFFSET -> scatterIndex(word, SveMemoryOp64.Store.OFFSET_SXTW, address);
            default -> decodeOpcode101(word, address); // OP_SCATTER_64_OR_IMMEDIATE
        };
    }

    // ── Opcode 000: STR de predicado e ST[234]Q ──────────────────────────────────────────────────

    private Ir64Op decodeOpcode000(int word, long address) {
        if (bit(word, TOP_BIT)) {
            // `STR_pri`: bits 24:22 = 110, bit 4 = 0.
            return isStrPredicate(word) ? str(Op.STR_P, word, word & PD_MASK, address) : null;
        }
        int nreg = (word >>> NREG_QUAD_SHIFT) & FIELD_MASK;
        if (nreg == 0) {
            return null;
        }
        if (!bit(word, BIT_21)) {
            // `ST[234]Q` escalar+imediato: bits 21:20 = 00.
            return bit(word, BIT_20) ? null : structureQuad(nreg, false, word, address);
        }
        return structureQuad(nreg, true, word, address);
    }

    private static boolean isStrPredicate(int word) {
        return ((word >>> MSZ_SHIFT) & FIELD_MASK) == FIELD_MASK && !bit(word, BIT_22)
                && (word & STORE_PREDICATE_BIT4_MASK) == 0;
    }

    private static boolean isStrVector(int word) {
        return ((word >>> MSZ_SHIFT) & FIELD_MASK) == FIELD_MASK && !bit(word, BIT_22);
    }

    private Ir64Op str(Op op, int word, int rt, long address) {
        int high = (word >>> STR_IMM_HIGH_SHIFT) & STR_IMM_HIGH_MASK;
        int low = (word >>> STR_IMM_LOW_SHIFT) & STR_IMM_LOW_MASK;
        long immediate = signExtend(high << STR_IMM_LOW_BITS | low, STR_IMMEDIATE_BITS);
        return new SveMemoryOp64.Store(op, 0, 0, 0, rt, (word >>> RN_SHIFT) & REGISTER_MASK, 0, false, immediate, 0, 0,
                false, false, address);
    }

    // ── Opcode 010: STR de vetor e ST1 escalar+escalar ───────────────────────────────────────────

    private Ir64Op decodeOpcode010(int word, long address) {
        if (isStrVector(word)) {
            return str(Op.STR_Z, word, word & REGISTER_MASK, address);
        }
        return contiguous(word, true, address);
    }

    // ── Opcode 111: ST1 escalar+imediato, STNT1 e ST[234] com imediato ───────────────────────────

    private Ir64Op decodeOpcode111(int word, long address) {
        if (bit(word, BIT_20)) {
            return structure(word, false, address);
        }
        return contiguous(word, false, address);
    }

    /// `ST1` (`nreg = 0`): `bits[24:23] = msz`, `bits[22:21]` = enumerado do `esz` (só `msz <= esz`).
    private Ir64Op contiguous(int word, boolean registerOffset, long address) {
        int msz = (word >>> MSZ_SHIFT) & FIELD_MASK;
        int field = (word >>> LOW_FIELD_SHIFT) & FIELD_MASK;
        int esz = switch (msz) {
            case MSZ_BYTE -> field;
            case MSZ_HALF -> field == 0 ? -1 : field;
            case MSZ_WORD -> WORD_ESZ_BY_FIELD[field];
            default -> DOUBLE_ESZ_BY_FIELD[field]; // MSZ_DOUBLE: `bits[22:21] = 0x` é `STR_zri`/buraco
        };
        if (esz < 0) {
            return null;
        }
        if (esz == ESZ_QUAD && !architecture.has(Aarch64Feature.SVE2_1)) {
            return null;
        }
        return build(Op.ST1, msz, esz, 0, registerOffset, word, esz == ESZ_QUAD, address);
    }

    /// `STNT1` (`nreg = 0`) e `ST2`-`ST4`: `msz = esz = bits[24:23]`, `nreg = bits[22:21]`. Em `opcode = 011` o
    /// deslocamento é escalar+escalar; em `111` (com `bit 20 = 1`) é escalar+imediato.
    private Ir64Op structure(int word, boolean registerOffset, long address) {
        int size = (word >>> MSZ_SHIFT) & FIELD_MASK;
        int nreg = (word >>> LOW_FIELD_SHIFT) & FIELD_MASK;
        return build(Op.ST1, size, size, nreg, registerOffset, word, false, address);
    }

    /// `ST[234]Q` (SVE2.1, legal em streaming): elemento e acesso de 128 bits.
    private Ir64Op structureQuad(int nreg, boolean registerOffset, int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2_1)) {
            return null;
        }
        return build(Op.ST1, MSZ_QUAD, ESZ_QUAD, nreg, registerOffset, word, false, address);
    }

    /// Monta o IR de uma forma escalar+escalar (`Xm` em `bits[20:16]`, `31` recusado) ou escalar+imediato (`imm4` em
    /// `bits[19:16]`, com sinal).
    private static Ir64Op build(Op op, int msz, int esz, int nreg, boolean registerOffset, int word,
            boolean nonStreaming, long address) {
        int rm = (word >>> RM_SHIFT) & REGISTER_MASK;
        if (registerOffset && rm == RM_UNALLOCATED) {
            return null;
        }
        long immediate = registerOffset ? 0 : signExtend((word >>> RM_SHIFT) & IMM4_MASK, SIGNED_IMMEDIATE_BITS);
        return new SveMemoryOp64.Store(op, msz, esz, nreg, word & REGISTER_MASK, (word >>> RN_SHIFT) & REGISTER_MASK, rm,
                registerOffset, immediate, (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, nonStreaming, address);
    }

    // ── Scatter ──────────────────────────────────────────────────────────────────────────────────

    /// `opcode = 100` (`xs = 0`, `UXTW`) e `110` (`xs = 1`, `SXTW`): `bits[22:21]` = `11` (32 bits, escalado), `10` (32
    /// bits), `01` (64 bits desempacotado, escalado), `00` (64 bits desempacotado).
    private Ir64Op scatterIndex(int word, int offsetExtend, long address) {
        int field = (word >>> LOW_FIELD_SHIFT) & FIELD_MASK;
        int esz = field >= FIELD_MASK - 1 ? ESZ_WORD : ESZ_DOUBLE;
        boolean scaled = (field & 1) != 0;
        return scatter(Op.SCATTER_VECTOR_INDEX, esz, scaled, offsetExtend, word, address);
    }

    /// `opcode = 101`: `bits[22:21]` = `01`/`00` (64 bits, deslocamento de 64 bits, escalado ou não), `10` (vetor de
    /// 64 bits + imediato), `11` (vetor de 32 bits + imediato).
    private Ir64Op decodeOpcode101(int word, long address) {
        int field = (word >>> LOW_FIELD_SHIFT) & FIELD_MASK;
        return switch (field) {
            case 0b00, 0b01 -> scatter(Op.SCATTER_VECTOR_INDEX, ESZ_DOUBLE, field == 0b01,
                    SveMemoryOp64.Store.OFFSET_64, word, address);
            case 0b10 -> scatterImmediate(ESZ_DOUBLE, word, address);
            default -> scatterImmediate(ESZ_WORD, word, address);
        };
    }

    /// Escalar mais vetor: `msz > esz` e `msz = 0` com deslocamento escalado são recusados (o `trans_ST1_zprz` do QEMU).
    private static Ir64Op scatter(Op op, int esz, boolean scaled, int offsetExtend, int word, long address) {
        int msz = (word >>> MSZ_SHIFT) & FIELD_MASK;
        if (msz > esz || msz == MSZ_BYTE && scaled) {
            return null;
        }
        return new SveMemoryOp64.Store(op, msz, esz, 0, word & REGISTER_MASK, (word >>> RN_SHIFT) & REGISTER_MASK,
                (word >>> RM_SHIFT) & REGISTER_MASK, false, 0, (word >>> PG_SHIFT) & PREDICATE_MASK, offsetExtend,
                scaled, true, address);
    }

    /// Vetor mais imediato (`imm5` sem sinal, escalado por `msz`); `msz > esz` recusado.
    private static Ir64Op scatterImmediate(int esz, int word, long address) {
        int msz = (word >>> MSZ_SHIFT) & FIELD_MASK;
        if (msz > esz) {
            return null;
        }
        return new SveMemoryOp64.Store(Op.SCATTER_VECTOR_BASE, msz, esz, 0, word & REGISTER_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, 0, false, (word >>> RM_SHIFT) & IMM5_MASK,
                (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, true, address);
    }

    /// `opcode = 001`: `ST1Q` (`bits[31:21]` = `11100100001`) e o `STNT1_zprz` (`bits[22:21]` = `10` para elemento de 32
    /// bits, `00` para 64 bits, `msz` livre).
    private Ir64Op decodeOpcode001(int word, long address) {
        if (word >>> ST1Q_TOP_SHIFT == ST1Q_TOP_VALUE) {
            return decodeSt1q(word, address);
        }
        return switch ((word >>> LOW_FIELD_SHIFT) & FIELD_MASK) {
            case NT_FIELD_DOUBLE -> nonTemporal(ESZ_DOUBLE, word, address);
            case NT_FIELD_WORD -> nonTemporal(ESZ_WORD, word, address);
            default -> null;
        };
    }

    /// `STNT1_zprz` (SVE2): a base é o VETOR `Zn` e o deslocamento é o ESCALAR `Xm` (`31` = `XZR`) — o contrário de
    /// `ST1_zprz`. `msz > esz` recusado, como no `trans_STNT1_zprz` do QEMU; sem `FEAT_SVE2` o encoding é recusado.
    private Ir64Op nonTemporal(int esz, int word, long address) {
        int msz = (word >>> MSZ_SHIFT) & FIELD_MASK;
        if (!architecture.has(Aarch64Feature.SVE2) || msz > esz) {
            return null;
        }
        return new SveMemoryOp64.Store(Op.SCATTER_VECTOR_PLUS_SCALAR, msz, esz, 0, word & REGISTER_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> RM_SHIFT) & REGISTER_MASK, false, 0,
                (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, true, address);
    }

    /// `ST1Q` (SVE2.1): a base é o VETOR `Zn` (`bits[9:5]`) e o deslocamento é o ESCALAR `Xm` (`bits[20:16]`, `31` =
    /// `XZR`) — o contrário de `ST1_zprz`.
    private Ir64Op decodeSt1q(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2_1)) {
            return null;
        }
        return new SveMemoryOp64.Store(Op.ST1Q, MSZ_QUAD, ESZ_QUAD, 0, word & REGISTER_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> RM_SHIFT) & REGISTER_MASK, false, 0,
                (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, true, address);
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }

    private static long signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }
}
