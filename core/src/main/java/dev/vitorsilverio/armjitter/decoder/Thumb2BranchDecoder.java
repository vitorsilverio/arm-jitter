package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica `B.W` (condicional T3 / incondicional T4) e `TBB`/`TBH` Thumb-2 de 32 bits (B2.4).
/// Anexado via {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando
/// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#THUMB2} está habilitado (o chamador,
/// {@link ThumbDecoder}, só invoca as extensões de 32 bits com essa feature ligada).
///
/// `BL`/`BLX_i` Thumb-2 NÃO são decodificados aqui (decisão D2 de `b2.4-thumb2-branches-it.md`):
/// são bit-a-bit os mesmos 32 bits do par legado prefixo/sufixo ARMv4T/v5T (`hw2[15:14]==0b11`),
/// já desviado para esse caminho por {@link ThumbDecoder#tryDecodeThumb32} ANTES de qualquer
/// extensão ser consultada — este decoder só recebe candidatos com `hw2[15:14]` ∈ {00,01,10}.
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`, hi =
/// primeiro halfword nos bits altos), igual a {@link Thumb2DataProcessingDecoder}.
public final class Thumb2BranchDecoder implements DecoderExtension {
    /// `top5` de `B.W` (condicional T3 / incondicional T4): `1111 0 ...` (ARM DDI 0406C A5.3.4).
    private static final int TOP5_BW_MASK = 0xF800;
    private static final int TOP5_BW_VALUE = 0xF000; // 0b11110 << 11

    /// `hi[10]` = S (bit de sinal do offset), `hi[9:6]` = `cond` (forma T3) ou parte de `imm10`
    /// (forma T4), `hi[5:0]` = `imm6` (T3) ou parte baixa de `imm10` (T4).
    private static final int HI_S_SHIFT = 10;
    private static final int HI_COND_SHIFT = 6;
    private static final int HI_COND_MASK = 0xF;
    private static final int HI_IMM6_MASK = 0x3F;
    private static final int HI_IMM10_MASK = 0x3FF;
    /// `cond` (T3) só é válido para 0x0-0xD; `0xE`/`0xF` nesta posição pertencem ao grupo
    /// "hints/CPS/MSR" (B2.5) ou à forma T4 incondicional — ver o javadoc de item 4 da spec.
    private static final int COND_RESERVED_THRESHOLD = 0xE;

    /// `lo[15:14]` fixo `10` (distingue de `BL`/`BLX_i`, `hw2[15:14]==11`, já desviado antes de
    /// chegar aqui) para AMBAS T3/T4; `lo[12]` distingue as duas: `0`=T3 (condicional, usa `cond`
    /// de `hi[9:6]`), `1`=T4 (incondicional, `hi[9:0]` inteiro é `imm10`) — mesmo padrão de
    /// `BL`(`lo[12]=1`)/`BLX`(`lo[12]=0`) sob `lo[15:14]==11`, espelhado aqui para `lo[15:14]==10`.
    private static final int LO_FAMILY_MASK = 0xD000;
    private static final int LO_T3_VALUE = 0x8000;
    private static final int LO_T4_VALUE = 0x9000;
    private static final int LO_J1_SHIFT = 13;
    private static final int LO_J2_SHIFT = 11;
    private static final int LO_IMM11_MASK = 0x7FF;

    /// `TBB`/`TBH` (ARM DDI 0406C A5.3.8, mesmo prefixo de 7 bits de
    /// {@link Thumb2LoadStoreDecoder} para "Load/store dual/exclusive/table branch", mas o
    /// sub-caso `raw[22]=1,raw[24]=0,raw[21]=0` — deliberadamente excluído de lá, ver B2.4).
    private static final int TOP7_SHIFT = 25;
    private static final int TOP7_MASK = 0x7F;
    private static final int EXTRA_TOP7 = 0b1110_100;
    private static final int P_BIT = 24;
    private static final int LDRD_MARKER_BIT = 22;
    private static final int W_BIT = 21;
    private static final int RN_MASK = 0xF; // Rn ocupa hi[3:0] diretamente (sem deslocamento)
    /// `lo[15:8]` fixo `1111 0000` (Rt=1111 sentinela + Rt2=0000) do sub-caso `TBB`/`TBH`.
    private static final int TBX_LO_TOP_BYTE_MASK = 0xFF00;
    private static final int TBX_LO_TOP_BYTE_VALUE = 0xF000;
    /// `lo[7:5]` fixo `000`; `lo[4]` = `H` (seletor `TBH`); `lo[3:0]` = Rm.
    private static final int TBX_LO_RESERVED_MASK = 0xE0;
    private static final int TBX_H_BIT = 4;
    private static final int TBX_RM_MASK = 0xF;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        int hi = raw >>> 16;
        int lo = raw & 0xFFFF;
        if ((hi & TOP5_BW_MASK) == TOP5_BW_VALUE) {
            DecodedInstruction branch = decodeBranchWide(raw, address, condition, hi, lo);
            if (branch != null) {
                return branch;
            }
        }
        return decodeTableBranch(raw, address, condition, hi, lo);
    }

    @Override
    public boolean claimsEncodingSpace(int raw) {
        // TBB/TBH: mesmo top7 que Thumb2LoadStoreDecoder já reconhece estruturalmente (LDRD/STRD/
        // LDM/STM/LDREX/STREX/TBB/TBH) — não precisa reivindicar aqui de novo (evita duplicar a
        // decisão UNDEFINED-vs-legado de B2.2.2); B.W usa o mesmo top5 que Thumb2DataProcessing/
        // Thumb2Misc já reivindicam estruturalmente também.
        return false;
    }

    // ── B.W condicional (T3) / incondicional (T4) ───────────────────────────────────────────

    private DecodedInstruction decodeBranchWide(int raw, int address, Condition condition, int hi, int lo) {
        int family = lo & LO_FAMILY_MASK;
        boolean s = ((hi >>> HI_S_SHIFT) & 1) != 0;
        int j1 = (lo >>> LO_J1_SHIFT) & 1;
        int j2 = (lo >>> LO_J2_SHIFT) & 1;
        int imm11 = lo & LO_IMM11_MASK;
        if (family == LO_T3_VALUE) {
            int cond4 = (hi >>> HI_COND_SHIFT) & HI_COND_MASK;
            if (cond4 >= COND_RESERVED_THRESHOLD) {
                return null; // hints/CPS/MSR (B2.5) ou reservado, não um B.W condicional
            }
            int imm6 = hi & HI_IMM6_MASK;
            int offset = signExtend21(pack(s, j2, j1, imm6, imm11));
            int target = address + 4 + offset;
            Condition branchCondition = ArmDecoder.decodeCondition(cond4);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, branchCondition, InstructionKind.BRANCH,
                    -1, -1, -1, target, true, false, false);
        }
        if (family == LO_T4_VALUE) {
            int imm10 = hi & HI_IMM10_MASK;
            int i1 = (s ? 1 : 0) ^ j1 ^ 1;
            int i2 = (s ? 1 : 0) ^ j2 ^ 1;
            int offset = signExtend25(pack25(s, i1, i2, imm10, imm11));
            int target = address + 4 + offset;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.BRANCH,
                    -1, -1, -1, target, true, false, false);
        }
        return null;
    }

    private static int pack(boolean s, int j2, int j1, int imm6, int imm11) {
        return ((s ? 1 : 0) << 20) | (j2 << 19) | (j1 << 18) | (imm6 << 12) | (imm11 << 1);
    }

    private static int signExtend21(int value) {
        int shift = Integer.SIZE - 21;
        return (value << shift) >> shift;
    }

    private static int pack25(boolean s, int i1, int i2, int imm10, int imm11) {
        return ((s ? 1 : 0) << 24) | (i1 << 23) | (i2 << 22) | (imm10 << 12) | (imm11 << 1);
    }

    private static int signExtend25(int value) {
        int shift = Integer.SIZE - 25;
        return (value << shift) >> shift;
    }

    // ── TBB/TBH ──────────────────────────────────────────────────────────────────────────────

    private DecodedInstruction decodeTableBranch(int raw, int address, Condition condition, int hi, int lo) {
        int top7 = (raw >>> TOP7_SHIFT) & TOP7_MASK;
        if (top7 != EXTRA_TOP7) {
            return null;
        }
        boolean p = ((raw >>> P_BIT) & 1) != 0;
        boolean w = ((raw >>> W_BIT) & 1) != 0;
        boolean ldrdMarker = ((raw >>> LDRD_MARKER_BIT) & 1) != 0;
        if (!ldrdMarker || p || w) {
            return null; // não é o sub-caso TBB/TBH (P=0,W=0,marker=1)
        }
        if ((lo & TBX_LO_TOP_BYTE_MASK) != TBX_LO_TOP_BYTE_VALUE || (lo & TBX_LO_RESERVED_MASK) != 0) {
            return null;
        }
        int rn = hi & RN_MASK; // Rn ocupa hi[3:0] (hi já isolado a 16 bits, sem deslocamento)
        int rm = lo & TBX_RM_MASK;
        boolean halfword = ((lo >>> TBX_H_BIT) & 1) != 0;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.TABLE_BRANCH,
                -1, rn, rm, halfword ? 1 : 0, false, false, false);
    }
}
