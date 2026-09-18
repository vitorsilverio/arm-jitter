package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Deslocamentos por imediato, shift-and-insert e `VSHLL` forma **T1** (perfil M, B16.10, MVE/Helium,
/// `target/isa-decode/mve.decode`, linhas 601-655, 38 encodings — confirmadas bit a bit contra o
/// arquivo real vendorizado em `target/isa-decode/mve.decode`, não contra a transcrição da spec):
///
/// ```
/// @2_shl_b .... .... .. 001 shift:3 .... .... .... .... &2shift qd=%qd qm=%qm size=0
/// @2_shl_h .... .... .. 01  shift:4 .... .... .... .... &2shift qd=%qd qm=%qm size=1
/// @2_shl_w .... .... .. 1   shift:5 .... .... .... .... &2shift qd=%qd qm=%qm size=2
///
/// @2_shll_b .... .... ... 01 shift:3 .... .... .... .... &2shift qd=%qd qm=%qm size=0
/// @2_shll_h .... .... ... 1  shift:4 .... .... .... .... &2shift qd=%qd qm=%qm size=1
///
/// # Right shifts are encoded as N - shift, where N is the element size in bits.
/// %rshift_i5  16:5 !function=rsub_32
/// %rshift_i4  16:4 !function=rsub_16
/// %rshift_i3  16:3 !function=rsub_8
///
/// @2_shr_b .... .... .. 001 ... .... .... .... .... &2shift qd=%qd qm=%qm size=0 shift=%rshift_i3
/// @2_shr_h .... .... .. 01 .... .... .... .... .... &2shift qd=%qd qm=%qm size=1 shift=%rshift_i4
/// @2_shr_w .... .... .. 1 ..... .... .... .... .... &2shift qd=%qd qm=%qm size=2 shift=%rshift_i5
///
/// VSHLI/VQSHLI_S/VQSHLI_U/VQSHLUI/VSHRI_S/VSHRI_U/VRSHRI_S/VRSHRI_U   111 U 1111 1 . ... ... ... 0 NNNN 0 1 . 1 ... 0
/// VSHLL_BS/VSHLL_BU/VSHLL_TS/VSHLL_TU (b/h só)                       111 U 1110 1 . 1 .. ... ... T 1111 0 1 . 0 ... 0
/// VSRI/VSLI                                                         111 1 1111 1 . ... ... ... 0 01NN 0 1 . 1 ... 0
/// ```
///
/// **Bit a bit confirmado por contagem de tokens**: `bits[31:29]=111` fixo, `bit28=U`,
/// `bits[27:24]∈{1110,1111}` (`1110`=`VSHLL` T1, `1111`=resto), `bit23=1` fixo (gate comum, disjunto
/// do espaço `bit23=0` de {@link Thumb2MveVector2opDecoder}/{@link Thumb2MveVectorScalarDecoder}),
/// `bit22`=`Qd` alto, `bits[15:13]`=`Qd` baixo (`%qd`), `bit5`=`Qm` alto, `bits[3:1]`=`Qm` baixo
/// (`%qm`) — MESMAS posições de {@link Thumb2MveVector2opDecoder}.
///
/// **Largura por PREFIXO de `bits[21:16]`, não por campo `size`** (achado 3 da task): prefixo
/// `bits[21:19]=001` → byte (`shift`=`bits[18:16]`, 3 bits); `bits[21:20]=01` → halfword
/// (`shift`=`bits[19:16]`, 4 bits); `bit21=1` → word (`shift`=`bits[20:16]`, 5 bits — só existe na
/// família `VSHLI`/`VSHRI`/`VRSHRI`/`VSRI`/`VSLI`, `VSHLL` não tem forma `w`); `bits[21:19]=000` é
/// RECUSADO (`null`, G8) — é o mesmo prefixo que `Vimm_1r` (B16.13, `bits[21:19]` sempre `000`)
/// reivindica no MESMO frame, então devolver `null` (não `unimplemented`) deixa o espaço livre para
/// aquela task, mesmo precedente de {@link NeonShiftImmediateDecoder} (`immh==0`).
///
/// **Deslocamentos à DIREITA são `N - raw`** (achado 1 da task, `%rshift_i3/i4/i5`): as 3 formas
/// `VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/`VSRI` (nibble `0000`/`0010`/`0100`) aplicam `shift =
/// (8 << esz) - raw`; as demais (`VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSLI`, nibble
/// `0101`/`0110`/`0111`) usam `raw` diretamente. **Reuso confirmado**: o mapeamento `(nibble, U) →
/// {@link AdvSimdShiftImmediateOp}` é EXATAMENTE a tabela `opc`/`U` de
/// {@link NeonShiftImmediateDecoder#shiftOperation} (NEON A32) — MESMOS 6 valores de `opc` (dos 14
/// que aquela tabela cobre; os 8 restantes — `SSRA`/`USRA`/`SRSRA`/`URSRA`/`SQSHL`/`UQSHL` já cobertos
/// mas via `opc=0111`/`SQSHLU` via `0110` — não aparecem nesta família MVE, que não tem RMW-acumulado
/// `_SRA`), reusada diretamente (RFC B13.2 D1: núcleo compartilhado, nunca reimplementado).
///
/// **`VSHLL` T1 × narrowing shifts (B16.11) × T2 (B16.7) — achado 2/Armadilha 2 da task**: `VSHRNB`/
/// `VSHRNT`/`VQSHRN*`/`VQSHRUN*` (B16.11) vivem no MESMO `bits[27:24]=1110`/`bit23=1`, mas com
/// `bit7=1`/`bit0=1` (esta classe exige `bit7=0`/`bit0=0`, literais confirmados contra o arquivo
/// real) — checados EXPLICITAMENTE para não engolir aquele espaço antes de B16.11 existir. A forma
/// T2 (B16.7, `Thumb2MveVectorOverlapDecoder`) vive em `bit23=0` — já disjunta pelo gate comum.
///
/// **`Qd == Qm` NÃO é UNDEF em `VSHLL`** (achado que corrige a suposição do Inclui #1 da task,
/// confirmado via `WebFetch` de `target/arm/tcg/translate-mve.c`, `do_2shift_vec`: só chama
/// `mve_check_qreg_bank(s, a->qd | a->qm)`, que valida CADA índice `<8` isoladamente — nunca compara
/// `qd` com `qm`). Nenhuma recusa adicional além de `Q > 7` (via
/// {@link VfpRegisters#isValidMveQuadRegister}).
///
/// **`VMOVL` não tem `Kind` próprio** (achado 2 da task, comentário literal do arquivo real: "we
/// implement it that way rather than special-casing it in the decode") — um encoding `VSHLL_BS`/
/// `VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` com `shift == 0` É o `VMOVL`; o núcleo compartilhado
/// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftWidenInterleavedMasked}, verbatim
/// de `DO_VSHLL`/`mve_helper.c`: `m[H_ESIZE(le*2+TOP)] << shift`) já produz o alargamento correto
/// sem deslocar quando `shift=0`.
///
/// **`VSHLL` T1 reusa o núcleo INTERCALADO da T2** (mesmo padrão `le*2 + (top?1:0)` de
/// {@link IrOp.MveVectorShiftWidenInterleaved}, confirmado verbatim contra `DO_VSHLL` — a ÚNICA
/// diferença real é que aqui `shift` vem do encoding, não fixo em `esize`) — ver
/// {@link IrOp.MveVectorShiftWidenImmediateInterleaved}.
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. TEM que ser registrado ANTES de `Thumb2NocpDecoder` (mesmo
/// espaço `bits[27:24] ∈ {1110,1111}` sob `M_PROFILE`). Usa o escape hatch de lifting
/// (mesmo precedente de {@link Thumb2MveVector2opDecoder}).
public final class Thumb2MveShiftImmediateDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int BIT23 = 23;

    private static final int U_BIT = 28;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_VSHLL = 0b1110;
    private static final int TOP24_SHIFT_IMMEDIATE = 0b1111;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int BIT21 = 21;
    private static final int BIT20 = 20;
    private static final int BIT19 = 19;
    private static final int PREFIX_SHIFT = 16;
    private static final int PREFIX_MASK = 0x3F;
    private static final int PREFIX_BIT21 = 0x20;
    private static final int PREFIX_BITS_21_20_MASK = 0x30;
    private static final int PREFIX_BITS_21_20_H = 0x10; // bit21=0, bit20=1
    private static final int PREFIX_BITS_21_19_MASK = 0x38;
    private static final int PREFIX_BITS_21_19_B = 0x08; // bit21=0, bit20=0, bit19=1
    private static final int WORD_SHIFT_MASK = 0x1F;
    private static final int HALFWORD_SHIFT_MASK = 0xF;
    private static final int BYTE_SHIFT_MASK = 0x7;

    private static final int BIT12 = 12;
    private static final int OPCODE_NIBBLE_SHIFT = 8;
    private static final int OPCODE_NIBBLE_MASK = 0xF;
    private static final int BIT7 = 7;
    private static final int BIT6 = 6;
    private static final int BIT4 = 4;
    private static final int BIT0 = 0;

    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;

    private final ArmArchitecture architecture;

    public Thumb2MveShiftImmediateDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP3_SHIFT) & TOP3_MASK) != TOP3_VALUE) {
            return null;
        }
        if (((raw >>> BIT23) & 1) == 0) {
            return null;
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int top24 = (raw >>> TOP24_SHIFT) & TOP24_MASK;
        boolean u = ((raw >>> U_BIT) & 1) != 0;
        if (top24 == TOP24_VSHLL) {
            return decodeVshll(raw, address, condition, qd, qm, u);
        }
        if (top24 == TOP24_SHIFT_IMMEDIATE) {
            return decodeShiftImmediate(raw, address, condition, qd, qm, u);
        }
        return null;
    }

    /// `bits[27:24]=1111` — `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/
    /// `VRSHRI_U`/`VSRI`/`VSLI`. Literais `bit12=0`/`bit7=0`/`bit6=1`/`bit4=1`/`bit0=0` excluem
    /// `VCVT_*_fixed` (B16.12, nibble `11xx`, mesma área de prefixo) por CONSTRUÇÃO: o nibble delas
    /// nunca bate nos 6 valores conhecidos, então o `switch` abaixo já devolve `null` sozinho — os
    /// literais aqui são só o gate de forma, não a exclusão real.
    private static DecodedInstruction decodeShiftImmediate(int raw, int address, Condition condition, int qd,
            int qm, boolean u) {
        if (((raw >>> BIT12) & 1) != 0 || ((raw >>> BIT7) & 1) != 0 || ((raw >>> BIT6) & 1) == 0
                || ((raw >>> BIT4) & 1) == 0 || (raw & 1) != 0) {
            return null;
        }
        int nibble = (raw >>> OPCODE_NIBBLE_SHIFT) & OPCODE_NIBBLE_MASK;
        AdvSimdShiftImmediateOp op = NeonShiftImmediateDecoder.shiftOperation(nibble, u ? 1 : 0);
        if (op == null) {
            return null;
        }
        int[] widthAndRaw = decodeWidthAndRawShift(raw);
        if (widthAndRaw == null) {
            return null;
        }
        int esz = widthAndRaw[0];
        int rawShift = widthAndRaw[1];
        boolean rightShift = isRightShiftNibble(nibble);
        int shift = rightShift ? (8 << esz) - rawShift : rawShift;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorShiftImmediate(op, esz, shift, qd, qm, condition));
    }

    /// Os 3 nibbles `_shr`/`VSRI` (`N - raw`); os demais (`_shl`/`VSLI`/`VQSHLUI`) usam `raw` direto.
    private static boolean isRightShiftNibble(int nibble) {
        return switch (nibble) {
            case 0b0000, 0b0010, 0b0100 -> true; // SHR/RSHR/SRI
            case 0b0101, 0b0110, 0b0111 -> false; // SHL(SLI)/SQSHLU/QSHL
            default -> false; // nunca alcançado — shiftOperation já devolveu null antes.
        };
    }

    /// `bits[27:24]=1110` — `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` T1. Literais `bit21=1`/
    /// `bit7=0`/`bit0=0` excluem as narrowing shifts (B16.11, mesmo `top24`, mas `bit7=1`/`bit0=1` e
    /// `bit21` livre — ver Javadoc da classe).
    private static DecodedInstruction decodeVshll(int raw, int address, Condition condition, int qd, int qm,
            boolean u) {
        if (((raw >>> BIT21) & 1) == 0 || ((raw >>> BIT7) & 1) != 0 || (raw & 1) != 0) {
            return null;
        }
        int nibble = (raw >>> OPCODE_NIBBLE_SHIFT) & OPCODE_NIBBLE_MASK;
        if (nibble != 0b1111 || ((raw >>> BIT6) & 1) == 0 || ((raw >>> BIT4) & 1) != 0) {
            return null;
        }
        boolean top = ((raw >>> BIT12) & 1) != 0;
        int esz;
        int shift;
        if (((raw >>> BIT20) & 1) != 0) {
            esz = ESZ_HALFWORD;
            shift = (raw >>> PREFIX_SHIFT) & HALFWORD_SHIFT_MASK;
        } else if (((raw >>> BIT19) & 1) != 0) {
            esz = ESZ_BYTE;
            shift = (raw >>> PREFIX_SHIFT) & BYTE_SHIFT_MASK;
        } else {
            return null; // reservado (bits[20:19] == 00) — não existe forma "w" para VSHLL.
        }
        boolean signed = !u;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorShiftWidenImmediateInterleaved(signed, esz, shift, top, qd, qm, condition));
    }

    /// Deriva `{esz, rawShift}` do prefixo `bits[21:16]` (6 bits) das formas `VSHLI`/`VSHRI`/
    /// `VRSHRI`/`VSRI`/`VSLI` (achado 3 da task): `bit21=1` → word (`shift`=`bits[20:16]`, 5 bits);
    /// `bits[21:20]=01` → halfword (`shift`=`bits[19:16]`, 4 bits); `bits[21:19]=001` → byte
    /// (`shift`=`bits[18:16]`, 3 bits); `bits[21:19]=000` → `null` (reservado, mesmo frame de
    /// `Vimm_1r`/B16.13).
    private static int[] decodeWidthAndRawShift(int raw) {
        int prefix = (raw >>> PREFIX_SHIFT) & PREFIX_MASK;
        if ((prefix & PREFIX_BIT21) != 0) {
            return new int[] {ESZ_WORD, prefix & WORD_SHIFT_MASK};
        }
        if ((prefix & PREFIX_BITS_21_20_MASK) == PREFIX_BITS_21_20_H) {
            return new int[] {ESZ_HALFWORD, prefix & HALFWORD_SHIFT_MASK};
        }
        if ((prefix & PREFIX_BITS_21_19_MASK) == PREFIX_BITS_21_19_B) {
            return new int[] {ESZ_BYTE, prefix & BYTE_SHIFT_MASK};
        }
        return null;
    }
}
