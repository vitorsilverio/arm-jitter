package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SveGather.Op;

/// Decoder SVE dos gather loads da B17.19: o fim de `### SVE Memory - 32-bit Gather and Unsized Contiguous Group`
/// (prefixos `0x84`/`0x85`) e `### SVE Memory 64-bit Gather Group` inteiro (`0xC4`/`0xC5`) do `sve.decode` do QEMU:
/// `LD1_zprz` (escalar + vetor), `LD1_zpiz` (vetor + imediato), `LD1Q` (vetor + escalar) e os `PRF_ns` de 64 bits.
///
/// **Cada linha do `.decode` são DUAS instruções**: o bit 13 (`ff`) escolhe entre `LD1*` e `LDFF1*` em todos os formatos.
/// As cinco dimensões (`esz`, `msz`, `u`, `xs`, `scale`) vêm dos campos; o `.decode` enumera só as combinações válidas e
/// as regras abaixo são a forma compacta desse enumerado — o teste `Aarch64SveGatherTest` compara o conjunto aceito com
/// o transcrito literalmente do arquivo, para as 8192 palavras dos dois prefixos, em vez de confiar nesta derivação:
///
/// - `msz > esz` não existe (o gather de 32 bits não tem `msz = 3`);
/// - `msz == esz` fixa `u = 1` (`@rprr_g_load_xs_sc`/`@rprr_g_load_sc`: o `.` no lugar de `u` é opcode, não campo) — não
///   há extensão possível, e `LD1SW` de elemento de 32 bits não existe;
/// - `msz = 0` fixa `scale = 0` (deslocar um byte por zero bits não tem forma escalada).
///
/// Recusas (G8, devolvem `null`): tudo que sobra dessas regras e dos buracos do `decodetree`, e `LD1Q` sem `FEAT_SVE2p1`.
/// Os `PRF_ns` de 32 bits moram em `Aarch64SveLoadDecoder` (mesmo prefixo `0x84`, decodificados antes deste).
final class Aarch64SveGatherDecoder {
    private static final int PREFIX_SHIFT = 25;
    private static final int PREFIX_GATHER_32 = 0b1000010;
    private static final int PREFIX_GATHER_64 = 0b1100010;

    private static final int REGISTER_MASK = 0b11111;
    private static final int PREDICATE_MASK = 0b111;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int RM_SHIFT = 16;
    private static final int MSZ_SHIFT = 23;
    private static final int MSZ_MASK = 0b11;
    private static final int SEL_SHIFT = 21;
    private static final int SEL_MASK = 0b11;
    private static final int IMM5_MASK = 0b11111;
    private static final int PRF_BIT4_MASK = 0x10;

    private static final int BIT_OFFSET_64_OR_IMMEDIATE = 15;
    private static final int BIT_UNSIGNED = 14;
    private static final int BIT_FIRST_FAULT = 13;
    private static final int BIT_XS = 22;
    private static final int BIT_SCALE = 21;
    private static final int FIELD_PREFETCH_LOW = 13;
    private static final int FIELD_PREFETCH_MASK = 0b111;

    // `bits[22:21]` das formas com `bit 15 = 1`.
    private static final int SEL_QUADWORD_OR_PREFETCH_VECTOR = 0b00;
    private static final int SEL_VECTOR_IMMEDIATE = 0b01;
    private static final int SEL_PREFETCH_SCALED_64 = 0b11;
    // `bits[15:13]` de `LD1Q` e do `PRF_ns` vetor + imediato (`bits[24:21] = 0000` / `bits[22:21] = 00`).
    private static final int OPCODE_LD1Q = 0b101;
    private static final int OPCODE_PREFETCH_VECTOR = 0b111;
    private static final int LD1Q_TOP_BITS_SHIFT = 21;
    private static final int LD1Q_TOP_BITS_MASK = 0b1111;

    private static final int MSZ_BYTE = 0;
    private static final int MSZ_QUAD = 4;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLE = 3;
    private static final int ESZ_QUAD = 4;

    private final Aarch64Architecture architecture;

    Aarch64SveGatherDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra dos prefixos `0x84`/`0x85`/`0xC4`/`0xC5`. `null` = não é deste grupo (ou é um encoding
    /// recusado).
    Ir64Op decode(int word, long address) {
        return switch (word >>> PREFIX_SHIFT) {
            case PREFIX_GATHER_32 -> decode32(word, address);
            case PREFIX_GATHER_64 -> decode64(word, address);
            default -> null;
        };
    }

    // ── Gather de 32 bits ────────────────────────────────────────────────────────────────────────

    /// `bit 15 = 0`: escalar + vetor de deslocamentos de 32 bits (`xs` no bit 22, `scale` no 21). `bit 15 = 1`: só o
    /// vetor + imediato (`bits[22:21] = 01`).
    private Ir64Op decode32(int word, long address) {
        if (!bit(word, BIT_OFFSET_64_OR_IMMEDIATE)) {
            return scalarPlusVector(word, ESZ_WORD, bit(word, BIT_XS) ? Ir64Op.SveGather.OFFSET_SXTW
                    : Ir64Op.SveGather.OFFSET_UXTW, address);
        }
        int sel = (word >>> SEL_SHIFT) & SEL_MASK;
        if (sel == SEL_QUADWORD_OR_PREFETCH_VECTOR) {
            // `LDNT1_zprz` de 32 bits: `10 u` em `bits[15:13]` — o `u` é o bit 13 e o 14 é opcode (0).
            return bit(word, BIT_UNSIGNED) ? null : nonTemporal(word, ESZ_WORD, bit(word, BIT_FIRST_FAULT), address);
        }
        return sel == SEL_VECTOR_IMMEDIATE ? vectorPlusImmediate(word, ESZ_WORD, address) : null;
    }

    // ── Gather de 64 bits ────────────────────────────────────────────────────────────────────────

    private Ir64Op decode64(int word, long address) {
        if (!bit(word, BIT_OFFSET_64_OR_IMMEDIATE)) {
            if (isUnpackedScaledPrefetch(word)) {
                return prefetch(address);
            }
            return scalarPlusVector(word, ESZ_DOUBLE, bit(word, BIT_XS) ? Ir64Op.SveGather.OFFSET_SXTW
                    : Ir64Op.SveGather.OFFSET_UXTW, address);
        }
        int sel = (word >>> SEL_SHIFT) & SEL_MASK;
        int opcode = (word >>> FIELD_PREFETCH_LOW) & FIELD_PREFETCH_MASK;
        return switch (sel) {
            case SEL_VECTOR_IMMEDIATE -> vectorPlusImmediate(word, ESZ_DOUBLE, address);
            case SEL_QUADWORD_OR_PREFETCH_VECTOR -> opcode == OPCODE_LD1Q && isLd1qTop(word) ? quadword(word, address)
                    : opcode == OPCODE_PREFETCH_VECTOR && (word & PRF_BIT4_MASK) == 0 ? prefetch(address)
                    // `LDNT1_zprz` de 64 bits: `1 u 0` em `bits[15:13]` — o `u` é o bit 14 e o 13 é opcode (0).
                    : !bit(word, BIT_FIRST_FAULT) ? nonTemporal(word, ESZ_DOUBLE, bit(word, BIT_UNSIGNED), address) : null;
            case SEL_PREFETCH_SCALED_64 -> isScaledPrefetch(word) ? prefetch(address)
                    : scalarPlusVector(word, ESZ_DOUBLE, Ir64Op.SveGather.OFFSET_64, address);
            default -> scalarPlusVector(word, ESZ_DOUBLE, Ir64Op.SveGather.OFFSET_64, address); // `10`: bit 22 = 1
        };
    }

    /// `PRF_ns` (`1100010 00 -1 ----- 0-- --- ----- 0 ----`): `msz = 0`, `bit 21 = 1`, `bit 15 = 0`. Um `LD1_zprz` com
    /// `msz = 0` exige `bit 21 = 0`, então as duas formas não se sobrepõem.
    private static boolean isUnpackedScaledPrefetch(int word) {
        return ((word >>> MSZ_SHIFT) & MSZ_MASK) == MSZ_BYTE && bit(word, BIT_SCALE) && (word & PRF_BIT4_MASK) == 0;
    }

    /// `PRF_ns` (`1100010 00 11 ----- 1-- --- ----- 0 ----`): `msz = 0` e `bits[22:21] = 11`.
    private static boolean isScaledPrefetch(int word) {
        return ((word >>> MSZ_SHIFT) & MSZ_MASK) == MSZ_BYTE && (word & PRF_BIT4_MASK) == 0;
    }

    /// `LD1Q`: `bits[31:21] = 11000100000` (`bits[24:21] = 0000`).
    private static boolean isLd1qTop(int word) {
        return ((word >>> LD1Q_TOP_BITS_SHIFT) & LD1Q_TOP_BITS_MASK) == 0;
    }

    // ── Montagem do IR ───────────────────────────────────────────────────────────────────────────

    /// `LD1_zprz`: `Xn|SP + (Zm[e] estendido << (scaled ? msz : 0))`. `bit 22` só é `xs` nas formas de 32 bits; nas
    /// de 64 bits (`OFFSET_64`) o chamador já garantiu que ele é opcode e vale 1.
    private Ir64Op scalarPlusVector(int word, int esz, int offsetExtend, long address) {
        int msz = (word >>> MSZ_SHIFT) & MSZ_MASK;
        boolean scaled = bit(word, BIT_SCALE);
        if (!validSizes(msz, esz, bit(word, BIT_UNSIGNED)) || msz == MSZ_BYTE && scaled) {
            return null;
        }
        return new Ir64Op.SveGather(Op.SCALAR_PLUS_VECTOR, msz, esz, !bit(word, BIT_UNSIGNED),
                bit(word, BIT_FIRST_FAULT), word & REGISTER_MASK, (word >>> RN_SHIFT) & REGISTER_MASK,
                (word >>> RM_SHIFT) & REGISTER_MASK, 0, (word >>> PG_SHIFT) & PREDICATE_MASK, offsetExtend, scaled,
                address);
    }

    /// `LD1_zpiz`: `Zn[e] + (imm5 << msz)`. O `trans_LD1_zpiz` do QEMU recusa `esz < msz` e `esz == msz` sem `u`.
    private Ir64Op vectorPlusImmediate(int word, int esz, long address) {
        int msz = (word >>> MSZ_SHIFT) & MSZ_MASK;
        if (!validSizes(msz, esz, bit(word, BIT_UNSIGNED))) {
            return null;
        }
        return new Ir64Op.SveGather(Op.VECTOR_PLUS_IMMEDIATE, msz, esz, !bit(word, BIT_UNSIGNED),
                bit(word, BIT_FIRST_FAULT), word & REGISTER_MASK, (word >>> RN_SHIFT) & REGISTER_MASK, 0,
                (word >>> RM_SHIFT) & IMM5_MASK, (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, address);
    }

    /// `LD1Q` (SVE2.1): a base é o VETOR `Zn` (`bits[9:5]`) e o deslocamento é o ESCALAR `Xm` (`bits[20:16]`, `31` =
    /// `XZR`) — o contrário de `LD1_zprz`. Não tem forma first-fault.
    private Ir64Op quadword(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2_1)) {
            return null;
        }
        return new Ir64Op.SveGather(Op.LD1Q, MSZ_QUAD, ESZ_QUAD, false, false, word & REGISTER_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> RM_SHIFT) & REGISTER_MASK, 0,
                (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, address);
    }

    /// `LDNT1_zprz` (SVE2): a base é o VETOR `Zn` e o deslocamento é o ESCALAR `Xm` (`31` = `XZR`), como no `LD1Q` e ao
    /// contrário do `LD1_zprz`. Sem forma first-fault (`ff = 0` fixo no `.decode`). A validade de (`esz`, `msz`, `u`) é a do
    /// `trans_LDNT1_zprz` do QEMU (`esz >= msz + !u`); sem `FEAT_SVE2` o encoding é recusado.
    private Ir64Op nonTemporal(int word, int esz, boolean unsigned, long address) {
        int msz = (word >>> MSZ_SHIFT) & MSZ_MASK;
        if (!architecture.has(Aarch64Feature.SVE2) || !validSizes(msz, esz, unsigned)) {
            return null;
        }
        return new Ir64Op.SveGather(Op.VECTOR_PLUS_SCALAR, msz, esz, !unsigned, false, word & REGISTER_MASK,
                (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> RM_SHIFT) & REGISTER_MASK, 0,
                (word >>> PG_SHIFT) & PREDICATE_MASK, 0, false, address);
    }

    /// `PRF_ns` é hint: o IR só carrega a checagem de acesso e a de modo streaming (todo gather é ilegal nele).
    private static Ir64Op prefetch(long address) {
        return new Ir64Op.SveLoad(Ir64Op.SveLoad.Op.PRF, 0, 0, false, 0, 0, 0, 0, false, 0, 0, true, address);
    }

    /// `msz <= esz`, e `msz == esz` fixa `u = 1` (não há o que estender).
    private static boolean validSizes(int msz, int esz, boolean unsigned) {
        return msz < esz || msz == esz && unsigned;
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }
}
