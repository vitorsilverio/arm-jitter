package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Sub-família 2 da B16.7 (perfil M, MVE/Helium, `target/isa-decode/mve.decode`, linhas 325-350, 14
/// encodings) — o `{}` sobreposto de complexos FP + dual-accumulate inteiros:
///
/// ```
/// { VCMUL0    111 . 1110 0 . 11 ... 0 ... 0 1110 . 0 . 0 ... 0 @2op_sz28
///   VQDMLADH  1110  1110 0 . .. ... 0 ... 0 1110 . 0 . 0 ... 0 @2op
///   VQDMLSDH  1111  1110 0 . .. ... 0 ... 0 1110 . 0 . 0 ... 0 @2op }
/// { VCMUL180 / VQDMLADHX / VQDMLSDHX }
/// { VCMUL90  / VQRDMLADH / VQRDMLSDH }
/// { VCMUL270 / VQRDMLADHX / VQRDMLSDHX }
/// VQDMULLB  111 . 1110 0 . 11 ... 0 ... 0 1111 . 0 . 0 ... 1 @2op_sz28
/// VQDMULLT  111 . 1110 0 . 11 ... 1 1111 . 0 . 0 ... 1 @2op_sz28
/// ```
///
/// **Achado central (mesma classe de achado da Armadilha 2 da B16.7/{@link Thumb2MveVectorOverlapDecoder})**:
/// `bits[21:20]` distingue as DUAS famílias dentro de CADA bloco `{}` — `VCMUL*`/`VQDMULL*` reivindicam
/// o valor LITERAL `0b11` ali (o `.decode` real escreve `11` fixo, não `size`), enquanto
/// `VQDMLADH`/`VQDMLSDH` (e variantes `X`/`R`) usam `bits[21:20]` como `size` REAL (`0`-`2`,
/// `byte`/`halfword`/`word` — todas as três larguras têm helper real, `do_vqdmladh_b/h/w`). Dentro do
/// slot `VCMUL*`/`VQDMLADH*`, `bit28` também muda de sentido: é `size_28` (`%size_28`, `bit28+1`) para
/// `VCMUL*`, mas é `U` (add/sub) para `VQDMLADH*`/`VQDMLSDH*` — sempre como parte do BYTE alto LITERAL
/// completo (`0xEE`=`VQDMLADH*`, `0xFE`=`VQDMLSDH*`), não um bit isolado.
///
/// `bit16` e `bit0` codificam, para `VCMUL*`, a rotação (`(bit16<<1)|bit0` → `0`/`90`/`180`/`270`,
/// tabela `ROT` de `DO_VCMLA` real) e, para `VQDMLADH*`/`VQDMLSDH*`, `exchange` (`bit16`, `XCHG` de
/// `DO_VQDMLADH_OP`) e `rounded` (`bit0`, `ROUND`) — ver Javadoc de
/// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dualMultiplyAddHighMasked}.
///
/// `VQDMULLB`/`VQDMULLT` vivem FORA do `{}` (nibble `bits[11:8]=1111`, nunca colide com o `1110` do
/// `{}`), discriminados de `VCMUL*` pelo MESMO literal `bits[21:20]=0b11` mas nibble diferente.
/// **Achado real transcrito de `trans_VQDMULLB`/`trans_VQDMULLT` (`translate-mve.c`)**: quando
/// `size==MO_32` (`esz=2`, fonte WORD) E `Qd` coincide com `Qn` OU `Qm`, a instrução é recusada
/// (`return false` no QEMU real — G8: não confundir com outra instrução, decodifica como
/// `UNIMPLEMENTED`) — restrição que só existe para a largura WORD (a lane larga de destino, de 64
/// bits/2 palavras, sobreporia a leitura da fonte de 32 bits ainda não consumida; halfword→word não
/// tem esse problema, mesma largura de palavra).
///
/// Gate por LINHA (`MVE_FLOAT` para `VCMUL*`; `MVE_INTEGER` para `VQDMLADH*`/`VQDMLSDH*`/`VQDMULL*`).
/// Usa o escape hatch de lifting (mesmo precedente de {@link Thumb2MveVector2opDecoder}).
public final class Thumb2MveComplexDualAccumulateDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_VALUE = 0b1110;
    private static final int BIT23 = 23;
    private static final int BIT12 = 12;
    private static final int BIT6 = 6;
    private static final int BIT4 = 4;
    private static final int BIT0 = 0;
    private static final int U_BIT = 28;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QN_HIGH_BIT = 7;
    private static final int QN_LOW_SHIFT = 17;
    private static final int QN_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int FIELD_21_20_SHIFT = 20;
    private static final int FIELD_21_20_MASK = 0x3;
    private static final int FIELD_21_20_RESERVED = 0b11;
    private static final int BIT16 = 16;

    private static final int NIBBLE_SHIFT = 8;
    private static final int NIBBLE_MASK = 0xF;
    private static final int NIBBLE_ADD_ROT = 0b1110;
    private static final int NIBBLE_DOUBLING_WIDEN = 0b1111;

    /// `%size_28`: `size = bit28 + 1` — só `1` (halfword) ou `2` (word), nunca `0`/`3`.
    private static final int SIZE_28_OFFSET = 1;

    /// `MO_32`: `esz` da forma WORD de `VQDMULLB`/`VQDMULLT` — ver Armadilha de `Qd == Qn || Qd == Qm`.
    private static final int DOUBLING_WIDEN_WORD_ESZ = 2;

    private final ArmArchitecture architecture;

    public Thumb2MveComplexDualAccumulateDecoder(ArmArchitecture architecture) {
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
        if (((raw >>> BIT12) & 1) != 0 || ((raw >>> BIT6) & 1) != 0 || ((raw >>> BIT4) & 1) != 0) {
            return null;
        }
        int nibble = (raw >>> NIBBLE_SHIFT) & NIBBLE_MASK;
        if (nibble != NIBBLE_ADD_ROT && nibble != NIBBLE_DOUBLING_WIDEN) {
            return null;
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qn = ((raw >>> QN_HIGH_BIT) & 1) << 3 | ((raw >>> QN_LOW_SHIFT) & QN_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qn)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        boolean bit16 = ((raw >>> BIT16) & 1) != 0;
        boolean bit0 = ((raw >>> BIT0) & 1) != 0;
        int field2120 = (raw >>> FIELD_21_20_SHIFT) & FIELD_21_20_MASK;

        if (nibble == NIBBLE_DOUBLING_WIDEN) {
            if (!bit0 || field2120 != FIELD_21_20_RESERVED) {
                return null; // Só bit0=1/bits[21:20]=11 são alocados para VQDMULLB/T nesta seção.
            }
            return decodeDoublingWideningMultiply(address, raw, condition, qd, qn, qm, bit16);
        }
        if (field2120 == FIELD_21_20_RESERVED) {
            return decodeComplexMultiply(address, raw, condition, qd, qn, qm, bit16, bit0);
        }
        return decodeDualMultiplyAddHigh(address, raw, condition, qd, qn, qm, field2120, bit16, bit0);
    }

    /// `VCMUL0`/`VCMUL90`/`VCMUL180`/`VCMUL270` — `rotation = (bit16<<1)|bit0`, `size = bit28+1`
    /// (`1`/`2`) — `FEAT_MVE_FP`.
    private DecodedInstruction decodeComplexMultiply(int address, int raw, Condition condition, int qd, int qn,
            int qm, boolean bit16, boolean bit0) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        int rotation = (bit16 ? 2 : 0) | (bit0 ? 1 : 0);
        int esz = ((raw >>> U_BIT) & 1) + SIZE_28_OFFSET;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpComplexMultiply(rotation, esz, qd, qn, qm, condition));
    }

    /// `VQDMLADH`/`VQDMLSDH` e variantes `X`(`bit16`)/`R`(`bit0`) — `add = !U` (`bit28`), `size` real
    /// (`bits[21:20]`, `0`-`2`) — `MVE_INTEGER`.
    private DecodedInstruction decodeDualMultiplyAddHigh(int address, int raw, Condition condition, int qd, int qn,
            int qm, int size, boolean exchange, boolean rounded) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        boolean add = ((raw >>> U_BIT) & 1) == 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorDualMultiplyAddHigh(add, exchange, rounded, size, qd, qn, qm, condition));
    }

    /// `VQDMULLB`(`bit16=0`)/`VQDMULLT`(`bit16=1`) — `size = bit28+1` (`1`/`2`) — `MVE_INTEGER`.
    /// Achado real de `trans_VQDMULLB`/`trans_VQDMULLT`: na forma WORD (`esz=2`), `Qd` colidindo com
    /// `Qn` OU `Qm` é recusado (não alocado, G8).
    private DecodedInstruction decodeDoublingWideningMultiply(int address, int raw, Condition condition, int qd,
            int qn, int qm, boolean top) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        int esz = ((raw >>> U_BIT) & 1) + SIZE_28_OFFSET;
        if (esz == DOUBLING_WIDEN_WORD_ESZ && (qd == qn || qd == qm)) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorDoublingWideningMultiply(esz, top, qd, qn, qm, condition));
    }
}
