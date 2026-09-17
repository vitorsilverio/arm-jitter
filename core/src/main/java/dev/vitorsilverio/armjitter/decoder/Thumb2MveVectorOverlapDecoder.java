package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Sub-família 1 da B16.7 (perfil M, MVE/Helium, `target/isa-decode/mve.decode`, linhas 220-279, 30
/// encodings) — os quatro blocos `{}` sobrepostos que o comentário do arquivo real descreve assim:
///
/// ```
/// # The VSHLL T2 encoding is not a @2op pattern, but is here because it
/// # overlaps what would be size=0b11 VMULH/VRMULH
/// {
///   VCVTB_SH   111 0 1110 0 . 11 1111 ... 0 1110 0 0 . 0 ... 1 @1op_nosz
///   VMAXNMA    111 0 1110 0 . 11 1111 ... 0 1110 1 0 . 0 ... 1 @vmaxnma size=2
///   VSHLL_BS   111 0 1110 0 . 11 .. 01 ... 0 1110 0 0 . 0 ... 1 @2_shll_esize_b/_h
///   VQMOVUNB   111 0 1110 0 . 11 .. 01 ... 0 1110 1 0 . 0 ... 1 @1op
///   VQMOVN_BS  111 0 1110 0 . 11 .. 11 ... 0 1110 0 0 . 0 ... 1 @1op
///   VMAXA      111 0 1110 0 . 11 .. 11 ... 0 1110 1 0 . 0 ... 1 @1op
///   VMULH_S    111 0 1110 0 . .. ...1 ... 0 1110 . 0 . 0 ... 1 @2op
/// }
/// ```
/// e os três blocos gêmeos (`VCVTB_HS`/`VSHLL_BU`/`VMOVNB`/`VQMOVN_BU`/`VMULH_U`;
/// `VCVTT_SH`/`VMINNMA`/`VSHLL_TS`/`VQMOVUNT`/`VQMOVN_TS`/`VMINA`/`VRMULH_S`;
/// `VCVTT_HS`/`VMINNMA`/`VSHLL_TU`/`VMOVNT`/`VQMOVN_TU`/`VRMULH_U`), discriminados por `bit28`(`U`,
/// signed/unsigned em `VMULH`/`VSHLL`/`VQMOVN`, meia/simples precisão em `VCVT`/`VMAXNMA`/`VMINNMA`)
/// e `bit12` (bottom/top em `VCVT`/`VSHLL`/`VMOVN`/`VQMOVN`, max/min em `VMAXNMA`/`VMINNMA`, não-
/// arredondado/arredondado em `VMULH`/`VRMULH`).
///
/// **Achado central (Armadilha 1 da task, prioridade dentro do `{}`)**: `bits[21:20]="11"` é comum
/// às SEIS formas específicas (`VCVTB/T_*`, `VMAXNMA`/`VMINNMA`, `VSHLL_*`, `VQMOVUNB/T`,
/// `VQMOVN_*`, `VMAXA`/`VMINA`) — `VMULH_S`/`VMULH_U`/`VRMULH_S`/`VRMULH_U` são o CATCH-ALL do
/// bloco: `size` (`bits[21:20]`) livre (real), discriminado só por `bit16=1` (as seis formas
/// específicas TAMBÉM têm `bit16=1` nos seus sub-padrões, mas são tentadas PRIMEIRO — mesma
/// prioridade textual do `.decode` real). Dentro das seis: `bits[19:16]=1111` (`VCVT*`/`VMAXNMA`/
/// `VMINNMA`, discriminados por `bit7`: `0`=`VCVT*`, `1`=`VMAXNMA`/`VMINNMA` — nenhum dos dois usa
/// `Qn`, `bit7` é literal aqui, não o bit alto de `Qn`); `bits[17:16]=01` (`VSHLL_*`/`VQMOVUNB/T`,
/// discriminados por `bit7`: `0`=`VSHLL_*`, `1`=`VQMOVUNB/T`); `bits[17:16]=11` (`VQMOVN_*`/
/// `VMAXA`/`VMINA`, discriminados por `bit7`: `0`=`VQMOVN_*`, `1`=`VMAXA`/`VMINA`).
///
/// **Achado que corrige a Armadilha 3 da task**: `VSHLL` forma **T2** (esta classe) e forma **T1**
/// (B16.10) são encodings COMPLETAMENTE diferentes com o mesmo mnemônico — esta classe só reivindica
/// `bit0=1` (T2), nunca o espaço de `bit0=0` que a B16.10 usará.
///
/// **Achado que corrige a Armadilha 2 da task**: `bit28` muda de significado dentro do MESMO bloco —
/// é `U` (signed/unsigned) em `VMULH`/`VSHLL`/`VQMOVN`/`VQMOVUN`, mas é PRECISÃO (`1`=meia,`0`=simples)
/// em `VCVT*`/`VMAXNMA`/`VMINNMA`. Extrair `U` cru e reusá-lo como precisão seria misdecode
/// silencioso — por isso os dois usos vivem em ramos de código separados, nunca um `boolean` só.
///
/// Gate por LINHA (Armadilha 1 da task, `MVE_INTEGER` para `VSHLL`/`VQMOVN*`/`VMOVN*`/`VMAXA`/
/// `VMINA`/`VMULH`/`VRMULH`; `MVE_FLOAT` para `VCVT*`/`VMAXNMA`/`VMINNMA`, que exigem `FEAT_MVE_FP`).
/// Usa o escape hatch de lifting (mesmo precedente de {@link Thumb2MveVector2opDecoder}).
public final class Thumb2MveVectorOverlapDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int U_BIT = 28;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_VALUE = 0b1110;
    private static final int BIT23 = 23;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int FIELD_21_20_SHIFT = 20;
    private static final int FIELD_21_20_MASK = 0x3;
    private static final int FIELD_21_20_RESERVED = 0b11;

    private static final int FIELD_19_16_SHIFT = 16;
    private static final int FIELD_19_16_MASK = 0xF;
    private static final int FIELD_19_16_CVT_MAXNMA = 0b1111;
    private static final int FIELD_17_16_MASK = 0x3;
    private static final int FIELD_17_16_SHLL_MOVUN = 0b01;
    private static final int FIELD_17_16_QMOVN_MAXA = 0b11;
    private static final int BIT16 = 16;

    private static final int BIT12 = 12;
    private static final int NIBBLE_SHIFT = 8;
    private static final int NIBBLE_MASK = 0xF;
    private static final int NIBBLE_VALUE = 0b1110;
    private static final int BIT7 = 7;
    private static final int BIT6 = 6;
    private static final int BIT4 = 4;
    private static final int BIT0 = 0;

    /// `VSHLL` T2: deslocamento = tamanho do elemento FONTE em bits (`8`=byte, `16`=halfword).
    private static final int SHLL_SHIFT_BYTE = 8;
    private static final int SHLL_SHIFT_HALFWORD = 16;
    /// `VMAXNMA`/`VMINNMA`: `esz` `1`=binary16 (`U=1`), `2`=binary32 (`U=0`) — literal por bloco.
    private static final int FP_ESZ_HALF = 1;
    private static final int FP_ESZ_SINGLE = 2;

    private final ArmArchitecture architecture;

    public Thumb2MveVectorOverlapDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (((raw >>> TOP3_SHIFT) & TOP3_MASK) != TOP3_VALUE) {
            return null;
        }
        if (((raw >>> TOP24_SHIFT) & TOP24_MASK) != TOP24_VALUE) {
            return null;
        }
        if (((raw >>> BIT23) & 1) != 0) {
            return null;
        }
        if (((raw >>> NIBBLE_SHIFT) & NIBBLE_MASK) != NIBBLE_VALUE) {
            return null;
        }
        if (((raw >>> BIT6) & 1) != 0 || ((raw >>> BIT4) & 1) != 0) {
            return null;
        }
        if (((raw >>> BIT0) & 1) == 0) {
            return null; // bit0=0 é a seção "Vector 2-op"/B16.6, não esta.
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        boolean u = ((raw >>> U_BIT) & 1) != 0;
        boolean top = ((raw >>> BIT12) & 1) != 0;
        boolean bit7 = ((raw >>> BIT7) & 1) != 0;
        int field2120 = (raw >>> FIELD_21_20_SHIFT) & FIELD_21_20_MASK;

        if (field2120 == FIELD_21_20_RESERVED) {
            int field1916 = (raw >>> FIELD_19_16_SHIFT) & FIELD_19_16_MASK;
            if (field1916 == FIELD_19_16_CVT_MAXNMA) {
                return bit7 ? decodeMaxMinNma(address, raw, condition, qd, qm, u, top)
                        : decodeCvtPrecision(address, raw, condition, qd, qm, u, top);
            }
            int field1716 = field1916 & FIELD_17_16_MASK;
            if (field1716 == FIELD_17_16_SHLL_MOVUN) {
                return bit7 ? decodeQmovunOrMovn(address, raw, condition, qd, qm, u, top)
                        : decodeShllT2(address, raw, condition, qd, qm, u, top);
            }
            if (field1716 == FIELD_17_16_QMOVN_MAXA) {
                return bit7 ? decodeMaxMinA(address, raw, condition, qd, qm, u, top)
                        : decodeQmovn(address, raw, condition, qd, qm, u, top);
            }
            return null; // bits[17:16] ∈ {00,10} com bits[21:20]="11": não alocado nesta seção.
        }
        // Catch-all VMULH/VRMULH — exige bit16=1 (as seis formas específicas acima também o têm,
        // mas são tentadas primeiro, mesma prioridade textual do `.decode` real).
        if (((raw >>> BIT16) & 1) == 0) {
            return null;
        }
        return decodeMulh(address, raw, condition, qd, qm, u, top, field2120);
    }

    /// `VCVTB_SH`/`VCVTT_SH` (`U=0`, `_SH`, single→half) / `VCVTB_HS`/`VCVTT_HS` (`U=1`, `_HS`,
    /// half→single) — `FEAT_MVE_FP`.
    private DecodedInstruction decodeCvtPrecision(int address, int raw, Condition condition, int qd, int qm,
            boolean u, boolean top) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        boolean widen = u; // U=0: _SH (estreita); U=1: _HS (alarga).
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpConvertPrecision(widen, top, qd, qm, condition));
    }

    /// `VMAXNMA` (`bit12=0`) / `VMINNMA` (`bit12=1`) — `esz` `1`(`U=1`,binary16)/`2`(`U=0`,binary32),
    /// literal por bloco — `FEAT_MVE_FP`.
    private DecodedInstruction decodeMaxMinNma(int address, int raw, Condition condition, int qd, int qm, boolean u,
            boolean min) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        int esz = u ? FP_ESZ_HALF : FP_ESZ_SINGLE;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpAbsAccumulate(!min, esz, qd, qm, condition));
    }

    /// `VSHLL_BS`/`VSHLL_TS` (`U=0`) / `VSHLL_BU`/`VSHLL_TU` (`U=1`), forma T2 — `size` real
    /// (`bits[19:18]`, `0`=byte/`shift=8`, `1`=halfword/`shift=16`; `2`/`3` não alocados aqui).
    private DecodedInstruction decodeShllT2(int address, int raw, Condition condition, int qd, int qm, boolean u,
            boolean top) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int esz = (raw >>> (FIELD_21_20_SHIFT - 2)) & 0x3; // bits[19:18].
        if (esz > 1) {
            return null;
        }
        int shift = esz == 0 ? SHLL_SHIFT_BYTE : SHLL_SHIFT_HALFWORD;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorShiftWidenInterleaved(!u, esz, top, qd, qm, condition));
    }

    /// `VQMOVUNB`/`VQMOVUNT` (`U=0`, narrow saturante SIGNED→UNSIGNED, `SQXTUN`) / `VMOVNB`/`VMOVNT`
    /// (`U=1`, narrow PURO sem saturação, `XTN`) — achado real: este slot NÃO é sempre `VQMOVUN*`;
    /// o bloco gêmeo `U=1` troca para `VMOVN*` (confirmado contra as 4 instanciações reais do
    /// `.decode`: só `VQMOVUNB`/`VQMOVUNT` existem para `U=0`, só `VMOVNB`/`VMOVNT` para `U=1` —
    /// não há forma `VQMOVUN` não-assinada nem `VMOVN` "signed"). `size` real (`bits[19:18]`, `0`/`1`
    /// válidos).
    private DecodedInstruction decodeQmovunOrMovn(int address, int raw, Condition condition, int qd, int qm,
            boolean u, boolean top) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int esz = (raw >>> (FIELD_21_20_SHIFT - 2)) & 0x3;
        if (esz > 1) {
            return null;
        }
        AdvSimdNarrowUnaryOp op = u ? AdvSimdNarrowUnaryOp.XTN : AdvSimdNarrowUnaryOp.SQXTUN;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorNarrowInterleaved(op, esz, top, qd, qm, condition));
    }

    /// `VQMOVN_BS`/`VQMOVN_TS` (`U=0`, `SQXTN`) / `VQMOVN_BU`/`VQMOVN_TU` (`U=1`, `UQXTN`) — `size`
    /// real (`bits[19:18]`, `0`/`1` válidos).
    private DecodedInstruction decodeQmovn(int address, int raw, Condition condition, int qd, int qm, boolean u,
            boolean top) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int esz = (raw >>> (FIELD_21_20_SHIFT - 2)) & 0x3;
        if (esz > 1) {
            return null;
        }
        AdvSimdNarrowUnaryOp op = u ? AdvSimdNarrowUnaryOp.UQXTN : AdvSimdNarrowUnaryOp.SQXTN;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorNarrowInterleaved(op, esz, top, qd, qm, condition));
    }

    /// `VMAXA`/`VMINA` (`bit12` do bloco discrimina — aqui `min` é o parâmetro do bloco gêmeo) — `Qd`
    /// é fonte E destino; `size` real (`bits[19:18]`, `0`-`2` válidos, `3` recusado). **Achado real:
    /// só existe para `U=0`** — o bloco gêmeo `U=1` NÃO tem forma `VMAXA`/`VMINA` (confirmado contra
    /// as instanciações reais do `.decode`: `DO_VMAXMINA` não tem variante "unsigned source", `Qm`
    /// é sempre assinado por construção — não há "U" para trocar). `U=1` nesta posição fica
    /// não-alocado.
    private DecodedInstruction decodeMaxMinA(int address, int raw, Condition condition, int qd, int qm, boolean u,
            boolean min) {
        if (u) {
            return null; // Não alocado — VMAXA/VMINA não têm forma "unsigned source".
        }
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int esz = (raw >>> (FIELD_21_20_SHIFT - 2)) & 0x3; // bits[19:18].
        if (esz == 0x3) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorAbsAccumulate(!min, esz, qd, qm, condition));
    }

    /// `VMULH_S`/`VMULH_U` (`bit12=0`) / `VRMULH_S`/`VRMULH_U` (`bit12=1`) — catch-all do bloco,
    /// `size` livre (`0`-`2`, garantido `!=3` pelo chamador). Extrai `Qn` (não usado pelas seis
    /// formas específicas acima, só por esta).
    private DecodedInstruction decodeMulh(int address, int raw, Condition condition, int qd, int qm, boolean u,
            boolean rounded, int size) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        final int qnLowShift = 17;
        final int qnLowMask = 0x7;
        final int qnHighBit = 7;
        int qn = ((raw >>> qnHighBit) & 1) << 3 | ((raw >>> qnLowShift) & qnLowMask);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qn)) {
            return null;
        }
        AdvSimdThreeSameOp op = rounded ? (u ? AdvSimdThreeSameOp.URMULH : AdvSimdThreeSameOp.SRMULH)
                : (u ? AdvSimdThreeSameOp.UMULH : AdvSimdThreeSameOp.SMULH);
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVector2Op(op, size, qd, qn, qm, condition));
    }
}
