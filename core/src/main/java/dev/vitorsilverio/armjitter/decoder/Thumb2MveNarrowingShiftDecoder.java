package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Deslocamentos estreitantes (só `b`/`h`) e `VSHLC` (perfil M, B16.11, MVE/Helium,
/// `target/isa-decode/mve.decode`, linhas 656-696, 33 encodings — confirmadas bit a bit contra o
/// arquivo real, não contra a transcrição da spec):
///
/// ```
/// VSHRNB/T, VRSHRNB/T, VQSHRNB/T_S, VQSHRNB/T_U, VQSHRUNB/T, VQRSHRNB/T_S, VQRSHRNB/T_U,
/// VQRSHRUNB/T (b/h)   111 U 1110 1 . ... ... ... T 1111 A 1 . 0 ... R  @2_shr_b / @2_shr_h
/// VSHLC               111 0 1110 1 . 1 imm:5 ... 0 1111 1100 rdm:4    qd=%qd
/// ```
///
/// **MESMO `top24` (`bits[27:24]=1110`, `bit23=1`) de {@link Thumb2MveShiftImmediateDecoder}**
/// (`VSHLL` T1) — as três famílias são disjuntas por `bit21`: `VSHLL` exige `bit21=1` E `bit7=0`
/// (`Thumb2MveShiftImmediateDecoder#decodeVshll`); esta família de deslocamentos estreitantes exige
/// `bit21=0` (o espaço `@2_shr_w`, que ela NÃO usa — só `b`/`h`); `VSHLC` **também** exige `bit21=1`
/// (ocupa literalmente o espaço `@2_shr_w` que a família estreitante deixa livre), mas com `bit7=1`
/// (`Thumb2MveShiftImmediateDecoder#decodeVshll` já recusa `bit7=1` explicitamente — ver Javadoc de
/// lá), disjunto de `VSHLL` por construção. Ordem de registro entre os dois decoders não importa
/// (Armadilha 3 da task: o que importa é o `bit21`/`bit7` cobrirem os três subespaços sem overlap).
///
/// **Os 16 mnemônicos estreitantes diferem em 3 bits** (Armadilha 1 da task, tabela completa
/// medida contra o arquivo real — os 8 valores de `(U, bit7, bit0)` cobrem EXATAMENTE os 8 valores
/// de {@link AdvSimdShiftNarrowOp}, nenhuma combinação sobra):
///
/// | `U` (bit28) | `bit7` | `bit0` | Operação |
/// |---|---|---|---|
/// | 0 | 1 | 1 | `VSHRNB/T` → {@link AdvSimdShiftNarrowOp#SHRN} |
/// | 1 | 1 | 1 | `VRSHRNB/T` → {@link AdvSimdShiftNarrowOp#RSHRN} |
/// | 0 | 0 | 0 | `VQSHRNB/T_S` → {@link AdvSimdShiftNarrowOp#SQSHRN} |
/// | 1 | 0 | 0 | `VQSHRNB/T_U` → {@link AdvSimdShiftNarrowOp#UQSHRN} |
/// | 0 | 1 | 0 | `VQSHRUNB/T` → {@link AdvSimdShiftNarrowOp#SQSHRUN} |
/// | 1 | 1 | 0 | `VQRSHRUNB/T` → {@link AdvSimdShiftNarrowOp#SQRSHRUN} |
/// | 0 | 0 | 1 | `VQRSHRNB/T_S` → {@link AdvSimdShiftNarrowOp#SQRSHRN} |
/// | 1 | 0 | 1 | `VQRSHRNB/T_U` → {@link AdvSimdShiftNarrowOp#UQRSHRN} |
///
/// `bit6=1`/`bit4=0` são literais fixos da família (gate, não distinguem mnemônico — checados para
/// G8: fora deles, `null`). `bit12` é o eixo `B`(0)/`T`(1) — Armadilha 2 da task: a metade NÃO
/// escrita de `Qd` é PRESERVADA pelo núcleo compartilhado
/// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftNarrowInterleavedMasked}, mesma
/// disciplina de {@link Thumb2MveVectorOverlapDecoder} para `VMOVN`/`VQMOVN`).
///
/// **Largura por PREFIXO `bits[21:16]`** (achado 3 da B16.10, reusado aqui): `bits[21:19]=001` →
/// byte (`shift`=`bits[18:16]`, 3 bits, `%rshift_i3`, `N=8`); `bits[21:20]=01` → halfword
/// (`shift`=`bits[19:16]`, 4 bits, `%rshift_i4`, `N=16`); QUALQUER outro valor de `bits[21:16]`
/// (incluindo `bit21=1`, que pertence a `VSHLC`/`VSHLL`) é `null` aqui.
///
/// **`VSHLC`** (Armadilha 3/6 da task, transcrito verbatim de `trans_VSHLC`/
/// `HELPER(mve_vshlc)`, `target/arm/tcg/{translate,mve_helper}.c` — NÃO derivado por analogia):
/// `imm:5` cru (`bits[20:16]`), NUNCA pré-resolvido para "N - raw" (o helper real trata `imm==0`
/// como "desloca por 32", achado que corrige a suposição do Inclui #2 da task — não é `UNDEF` nem
/// no-op). `Rdm ∈ {13, 15}` é `UNDEF` (confirmado: "CONSTRAINED UNPREDICTABLE: we UNDEF" no QEMU
/// real). **NÃO é predicada lane-a-lane** — opera em 4 elementos de 32 bits (granularidade de
/// BEAT), ver Javadoc de {@link IrOp.MveVectorShiftLeftCarry}.
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. TEM que ser registrado ANTES de `Thumb2NocpDecoder` (mesmo
/// espaço `bits[27:24]=1110` sob `M_PROFILE`, Armadilha 4 da task).
public final class Thumb2MveNarrowingShiftDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int BIT23 = 23;

    private static final int U_BIT = 28;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_NARROWING_SHIFT = 0b1110;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int BIT21 = 21;
    private static final int PREFIX_SHIFT = 16;
    private static final int PREFIX_MASK = 0x3F;
    private static final int PREFIX_BIT21 = 0x20;
    private static final int PREFIX_BITS_21_20_MASK = 0x30;
    private static final int PREFIX_BITS_21_20_H = 0x10; // bit21=0, bit20=1
    private static final int PREFIX_BITS_21_19_MASK = 0x38;
    private static final int PREFIX_BITS_21_19_B = 0x08; // bit21=0, bit20=0, bit19=1
    private static final int HALFWORD_SHIFT_MASK = 0xF;
    private static final int BYTE_SHIFT_MASK = 0x7;

    private static final int BIT12 = 12;
    private static final int NIBBLE_SHIFT = 8;
    private static final int NIBBLE_MASK = 0xF;
    private static final int NIBBLE_VALUE = 0b1111;
    private static final int BIT7 = 7;
    private static final int BIT6 = 6;
    private static final int BIT4 = 4;

    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALFWORD = 1;

    private static final int VSHLC_IMM_SHIFT = 16;
    private static final int VSHLC_IMM_MASK = 0x1F;
    private static final int VSHLC_NIBBLE_LOW = 0xC; // bits[7:4] = 1100
    private static final int VSHLC_NIBBLE_LOW_MASK = 0xF;
    private static final int VSHLC_RDM_MASK = 0xF;
    private static final int VSHLC_UNPREDICTABLE_SP = 13;
    private static final int VSHLC_UNPREDICTABLE_PC = 15;

    private final ArmArchitecture architecture;

    public Thumb2MveNarrowingShiftDecoder(ArmArchitecture architecture) {
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
        int top24 = (raw >>> TOP24_SHIFT) & TOP24_MASK;
        if (top24 != TOP24_NARROWING_SHIFT) {
            return null;
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        if (((raw >>> BIT21) & 1) != 0) {
            return decodeVshlc(raw, address, condition, qd);
        }
        return decodeNarrowingShift(raw, address, condition, qd);
    }

    /// `bit21=0` — os 32 encodings `VSHRNB/T`...`VQRSHRUNB/T`. Literais `bits[11:8]=1111`/`bit6=1`/
    /// `bit4=0` excluem qualquer outra família do MESMO `top24` (gate, não distinguem mnemônico).
    private DecodedInstruction decodeNarrowingShift(int raw, int address, Condition condition, int qd) {
        int nibble = (raw >>> NIBBLE_SHIFT) & NIBBLE_MASK;
        if (nibble != NIBBLE_VALUE || ((raw >>> BIT6) & 1) == 0 || ((raw >>> BIT4) & 1) != 0) {
            return null;
        }
        int[] widthAndRaw = decodeWidthAndRawShift(raw);
        if (widthAndRaw == null) {
            return null;
        }
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int esz = widthAndRaw[0];
        int rawShift = widthAndRaw[1];
        int shift = (8 << esz) - rawShift;
        boolean u = ((raw >>> U_BIT) & 1) != 0;
        boolean bit7 = ((raw >>> BIT7) & 1) != 0;
        boolean bit0 = (raw & 1) != 0;
        AdvSimdShiftNarrowOp op = narrowingOperation(u, bit7, bit0);
        boolean top = ((raw >>> BIT12) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorShiftNarrowImmediateInterleaved(op, esz, shift, top, qd, qm, condition));
    }

    /// Tabela COMPLETA dos 8 `(U, bit7, bit0)` → {@link AdvSimdShiftNarrowOp} (Armadilha 1 da task) —
    /// todas as 8 combinações são válidas, nenhuma sobra (G8 satisfeito por construção nesta família,
    /// diferente de outras onde alguma combinação de bits fica sem mnemônico).
    private static AdvSimdShiftNarrowOp narrowingOperation(boolean u, boolean bit7, boolean bit0) {
        if (bit7) {
            if (bit0) {
                return u ? AdvSimdShiftNarrowOp.RSHRN : AdvSimdShiftNarrowOp.SHRN;
            }
            return u ? AdvSimdShiftNarrowOp.SQRSHRUN : AdvSimdShiftNarrowOp.SQSHRUN;
        }
        if (bit0) {
            return u ? AdvSimdShiftNarrowOp.UQRSHRN : AdvSimdShiftNarrowOp.SQRSHRN;
        }
        return u ? AdvSimdShiftNarrowOp.UQSHRN : AdvSimdShiftNarrowOp.SQSHRN;
    }

    /// Deriva `{esz, rawShift}` do prefixo `bits[21:16]` (achado 3 da B16.10, restrito a `b`/`h` —
    /// esta família não tem forma `w`): `bits[21:20]=01` → halfword (`shift`=`bits[19:16]`, 4 bits);
    /// `bits[21:19]=001` → byte (`shift`=`bits[18:16]`, 3 bits); qualquer outro valor (incluindo
    /// `bit21=1`, que não deveria chegar aqui — já filtrado pelo chamador) é `null`.
    private static int[] decodeWidthAndRawShift(int raw) {
        int prefix = (raw >>> PREFIX_SHIFT) & PREFIX_MASK;
        if ((prefix & PREFIX_BIT21) != 0) {
            return null;
        }
        if ((prefix & PREFIX_BITS_21_20_MASK) == PREFIX_BITS_21_20_H) {
            return new int[] {ESZ_HALFWORD, prefix & HALFWORD_SHIFT_MASK};
        }
        if ((prefix & PREFIX_BITS_21_19_MASK) == PREFIX_BITS_21_19_B) {
            return new int[] {ESZ_BYTE, prefix & BYTE_SHIFT_MASK};
        }
        return null;
    }

    /// `bit21=1` — `VSHLC` (única instrução da família, `bit28=0` LITERAL — não tem forma `U`,
    /// confirmado contra o arquivo real). Literais `bits[11:8]=1111`/`bits[7:4]=1100`/`bit12=0`
    /// excluem `VSHLL` (que também exige `bit21=1`, mas `bit7=0`, Armadilha 3 da task).
    private static DecodedInstruction decodeVshlc(int raw, int address, Condition condition, int qd) {
        if (((raw >>> U_BIT) & 1) != 0 || ((raw >>> BIT12) & 1) != 0) {
            return null;
        }
        int nibble = (raw >>> NIBBLE_SHIFT) & NIBBLE_MASK;
        if (nibble != NIBBLE_VALUE) {
            return null;
        }
        if (((raw >>> BIT4) & VSHLC_NIBBLE_LOW_MASK) != VSHLC_NIBBLE_LOW) {
            return null;
        }
        int rdm = raw & VSHLC_RDM_MASK;
        if (rdm == VSHLC_UNPREDICTABLE_SP || rdm == VSHLC_UNPREDICTABLE_PC) {
            // CONSTRAINED UNPREDICTABLE: o QEMU real UNDEF (trans_VSHLC, "we UNDEF").
            return null;
        }
        int imm = (raw >>> VSHLC_IMM_SHIFT) & VSHLC_IMM_MASK;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorShiftLeftCarry(imm, qd, rdm, condition));
    }
}
