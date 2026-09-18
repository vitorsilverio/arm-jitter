package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Conversões `VCVT` (int↔fp, ponto fixo, modo de arredondamento) e `VRINT*` (perfil M, B16.12,
/// MVE/Helium, `target/isa-decode/mve.decode`, linhas 791-832, 26 encodings — confirmadas bit a bit
/// contra o arquivo real, não contra a transcrição da spec):
///
/// ```
/// # fp <-> fixed-point (8), @vcvt (bit21=1,bit9=1,size=2) / @vcvt_f16 (bit21=1,bit20=1,bit9=0,size=1)
/// VCVT_SH_fixed/VCVT_UH_fixed/VCVT_HS_fixed/VCVT_HU_fixed/VCVT_SF_fixed/VCVT_UF_fixed/
/// VCVT_FS_fixed/VCVT_FU_fixed   111 U 1111 1 . [shift] . . 11 . 0 01 . 1 ... 0
///
/// # VCVT simples/RMODE/VRINT (18), @1op — bits[21:20]=11 fixo, size=bits[19:18]
/// VCVT_SF/UF/FS/FU                1111 1111 1 . 11 .. 11 ... 0 011 <dir:1><u:1> 1 . 0 ... 0
/// VCVTA/N/P/M{S,U}                1111 1111 1 . 11 .. 11 ... 000 <rm:2> <u:1> 1 . 0 ... 0
/// VRINTN/X/A/Z/M/P                1111 1111 1 . 11 .. 10 ... 001 <mode:3> 1 . 0 ... 0
/// ```
///
/// **Bit 4 separa as duas famílias top-level** (achado desta rodada, resolvendo a Armadilha 2 da
/// task — o prefixo `111x 1111 1.` é compartilhado com `VSHLI`/`VQSHLI`/`VQSHLUI`/`VSHRI`/`VRSHRI`/
/// `VSRI`/`VSLI` da {@link Thumb2MveShiftImmediateDecoder}, B16.10): as 26 linhas desta task têm
/// SEMPRE `bit4=0`; a família B16.10 tem SEMPRE `bit4=1` (conferido bit a bit contra as duas seções
/// do arquivo real — nenhuma combinação de `Q`/`shift`/`size` produz colisão, porque os dois grupos
/// nunca concordam em `bit4`). **Bits[11:10]=`11` é EXCLUSIVO da família de ponto fixo** desta task
/// (nenhum opcode de `VSHLI`/`VQSHLI`/`VQSHLUI`/`VSHRI`/`VRSHRI`/`VSRI`/`VSLI` começa com `11` em
/// `bits[11:8]`) — usado aqui como o dispatch primário entre as duas sub-famílias de 26 (8 vs 18),
/// mais barato que checar `bit4` duas vezes.
///
/// **`@vcvt` × `@vcvt_f16` diferem em bit9 (Armadilha 1 da task, "conferir posição exata" — medido
/// bit a bit)**: os 4 pares de mnemônicos (`_SH`/`_SF`, `_UH`/`_UF`, `_HS`/`_FS`, `_HU`/`_FU`) têm
/// texto de linha IDÊNTICO; a diferença real está em qual `@formato` cada linha usa — `@vcvt` fixa
/// `bit21=1`,`bit9=1`,`shift`=`bits[20:16]` (5 bits, `%rshift_i5`, `N=32`); `@vcvt_f16` fixa
/// `bit21=1`,`bit20=1`,`bit9=0`,`shift`=`bits[19:16]` (4 bits, `%rshift_i4`, `N=16`). `bit8`
/// distingue direção (`0`=int→fp, `1`=fp→int); `bit28`(`U`) distingue sinal.
///
/// **`VCVT_FS`/`VCVT_FU`/as formas `_fixed` de fp→int SEMPRE arredondam para zero, IGNORANDO
/// `FPSCR.RMode`** (achado que CORRIGE a hipótese "conferir" do achado 4 da spec da task — verbatim
/// contra `DO_VCVT`/`mve_helper.c` real do QEMU: `vcvt_hs`/`vcvt_fs`/`vcvt_hu`/`vcvt_fu` usam os
/// helpers `helper_vfp_to*_round_to_zero`, e a forma simples passa `shift=0` para o MESMO helper da
/// forma `_fixed` — são literalmente a mesma operação). `VCVTA`/`N`/`P`/`M` carregam o modo no
/// PRÓPRIO encoding, também sem consultar `FPSCR.RMode` (`DO_VCVT_RMODE` troca o modo temporariamente
/// e restaura). Nenhuma das 26 consulta `FPSCR.RMode` de verdade — reuso 1:1 de
/// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp}/{@link
/// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#convertFixedPoint}, que já tinham exatamente esse
/// comportamento para A64/NEON de 32 bits.
///
/// `VRINT*` só define 6 dos 8 valores de `mode`(`bits[9:7]`) — `100`/`110` caem em `null` (G8).
/// `size`(`bits[19:18]`) só `1`(binary16)/`2`(binary32) válidos para a família `@1op` — `0`/`3` caem
/// em `null`.
///
/// Gate: {@link ArmFeature#MVE_FLOAT}. TEM que ser registrado ANTES de `Thumb2NocpDecoder` (mesmo
/// espaço `bits[27:24]=1111` sob `M_PROFILE`).
public final class Thumb2MveFpConvertDecoder implements DecoderExtension {
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int BIT23 = 23;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_VALUE = 0b1111;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int BITS_11_10_SHIFT = 10;
    private static final int BITS_11_10_MASK = 0x3;
    private static final int BITS_11_10_FIXED_FAMILY = 0b11;

    // ─── Família de ponto fixo (@vcvt / @vcvt_f16, 8 encodings) ───────────────────────────────────
    private static final int U_BIT = 28;
    private static final int BIT21 = 21;
    private static final int BIT12 = 12;
    private static final int BITS_7_6_SHIFT = 6;
    private static final int BITS_7_6_MASK = 0x3;
    private static final int BITS_7_6_FIXED = 0b01;
    private static final int BIT4 = 4;
    private static final int BIT9 = 9;
    private static final int BIT8 = 8;
    private static final int SINGLE_SHIFT_SHIFT = 16;
    private static final int SINGLE_SHIFT_MASK = 0x1F; // bits[20:16], %rshift_i5
    private static final int HALF_SHIFT_SHIFT = 16;
    private static final int HALF_SHIFT_MASK = 0xF; // bits[19:16], %rshift_i4
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int HALF_WIDTH_BITS = 16;
    private static final int SINGLE_WIDTH_BITS = 32;

    // ─── Família @1op (VCVT simples/RMODE/VRINT, 18 encodings) ─────────────────────────────────────
    private static final int BIT20 = 20;
    private static final int BIT6 = 6;
    private static final int SIZE_SHIFT = 18;
    private static final int SIZE_MASK = 0x3;
    private static final int BITS_17_16_SHIFT = 16;
    private static final int BITS_17_16_MASK = 0x3;
    private static final int BITS_17_16_VCVT = 0b11;
    private static final int BITS_17_16_VRINT = 0b10;
    private static final int BIT11 = 11;
    private static final int BIT10 = 10;
    private static final int BITS_9_8_SHIFT = 8;
    private static final int BITS_9_8_MASK = 0x3;
    private static final int BIT7 = 7;
    private static final int BITS_12_10_SHIFT = 10;
    private static final int BITS_12_10_MASK = 0x7;
    private static final int BITS_12_10_VRINT_FIXED = 0b001;
    private static final int MODE_SHIFT = 7;
    private static final int MODE_MASK = 0x7;
    private static final int MODE_RESERVED_1 = 0b100;
    private static final int MODE_RESERVED_2 = 0b110;

    private final ArmArchitecture architecture;

    public Thumb2MveFpConvertDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        if (((raw >>> TOP3_SHIFT) & TOP3_MASK) != TOP3_VALUE) {
            return null;
        }
        if (((raw >>> BIT23) & 1) == 0) {
            return null;
        }
        if (((raw >>> TOP24_SHIFT) & TOP24_MASK) != TOP24_VALUE) {
            return null;
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        int bits11_10 = (raw >>> BITS_11_10_SHIFT) & BITS_11_10_MASK;
        if (bits11_10 == BITS_11_10_FIXED_FAMILY) {
            return decodeFixedPoint(raw, address, condition, qd);
        }
        return decodeOneOp(raw, address, condition, qd);
    }

    /// As 8 linhas `@vcvt`/`@vcvt_f16` (ponto fixo↔ponto flutuante). `bits[11:10]=11` já confirmado
    /// pelo chamador.
    private DecodedInstruction decodeFixedPoint(int raw, int address, Condition condition, int qd) {
        if (((raw >>> BIT21) & 1) == 0) {
            return null;
        }
        if (((raw >>> BIT12) & 1) != 0) {
            return null;
        }
        if (((raw >>> BITS_7_6_SHIFT) & BITS_7_6_MASK) != BITS_7_6_FIXED) {
            return null;
        }
        if (((raw >>> BIT4) & 1) == 0) {
            return null;
        }
        if ((raw & 1) != 0) {
            return null;
        }
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        boolean half = ((raw >>> BIT9) & 1) == 0;
        int esz = half ? ESZ_HALF : ESZ_SINGLE;
        int rawShift = half ? (raw >>> HALF_SHIFT_SHIFT) & HALF_SHIFT_MASK
                : (raw >>> SINGLE_SHIFT_SHIFT) & SINGLE_SHIFT_MASK;
        int widthBits = half ? HALF_WIDTH_BITS : SINGLE_WIDTH_BITS;
        int fractionBits = widthBits - rawShift;
        boolean toInt = ((raw >>> BIT8) & 1) != 0;
        boolean signed = ((raw >>> U_BIT) & 1) == 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpConvertFixed(!toInt, signed, esz, fractionBits, qd, qm, condition));
    }

    /// As 18 linhas `@1op` (VCVT simples, VCVT com modo de arredondamento explícito, VRINT).
    /// `bits[11:10]!=11` já confirmado pelo chamador.
    private DecodedInstruction decodeOneOp(int raw, int address, Condition condition, int qd) {
        if (((raw >>> BIT4) & 1) != 0) {
            return null;
        }
        if (((raw >>> BIT21) & 1) == 0 || ((raw >>> BIT20) & 1) == 0) {
            return null;
        }
        if (((raw >>> BIT6) & 1) == 0) {
            return null;
        }
        if ((raw & 1) != 0) {
            return null;
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size != ESZ_HALF && size != ESZ_SINGLE) {
            return null;
        }
        int qm = ((raw >>> QM_HIGH_BIT) & 1) << 3 | ((raw >>> QM_LOW_SHIFT) & QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int bits17_16 = (raw >>> BITS_17_16_SHIFT) & BITS_17_16_MASK;
        AdvSimdFpUnaryOp op = switch (bits17_16) {
            case BITS_17_16_VCVT -> decodeVcvtOperation(raw);
            case BITS_17_16_VRINT -> decodeVrintOperation(raw);
            default -> null;
        };
        if (op == null) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpConvert(op, size, qd, qm, condition));
    }

    /// `bits[17:16]=11` — VCVT simples (`bit10=1`, `bit9=1` fixo) ou com modo explícito (`bit10=0`,
    /// `bits[12:10]=000`).
    private static AdvSimdFpUnaryOp decodeVcvtOperation(int raw) {
        if (((raw >>> BIT12) & 1) != 0 || ((raw >>> BIT11) & 1) != 0) {
            return null;
        }
        boolean plain = ((raw >>> BIT10) & 1) != 0;
        boolean unsignedForm = ((raw >>> BIT7) & 1) != 0;
        if (plain) {
            if (((raw >>> BIT9) & 1) == 0) {
                return null;
            }
            boolean toInt = ((raw >>> BIT8) & 1) != 0;
            if (toInt) {
                return unsignedForm ? AdvSimdFpUnaryOp.FCVTZU : AdvSimdFpUnaryOp.FCVTZS;
            }
            return unsignedForm ? AdvSimdFpUnaryOp.UCVTF : AdvSimdFpUnaryOp.SCVTF;
        }
        int rm = (raw >>> BITS_9_8_SHIFT) & BITS_9_8_MASK;
        return switch (rm) {
            case 0b00 -> unsignedForm ? AdvSimdFpUnaryOp.FCVTAU : AdvSimdFpUnaryOp.FCVTAS;
            case 0b01 -> unsignedForm ? AdvSimdFpUnaryOp.FCVTNU : AdvSimdFpUnaryOp.FCVTNS;
            case 0b10 -> unsignedForm ? AdvSimdFpUnaryOp.FCVTPU : AdvSimdFpUnaryOp.FCVTPS;
            default -> unsignedForm ? AdvSimdFpUnaryOp.FCVTMU : AdvSimdFpUnaryOp.FCVTMS;
        };
    }

    /// `bits[17:16]=10` — `VRINT*`: `bits[12:10]=001` fixo, `mode`=`bits[9:7]` (`100`/`110`
    /// reservados, G8).
    private static AdvSimdFpUnaryOp decodeVrintOperation(int raw) {
        if (((raw >>> BITS_12_10_SHIFT) & BITS_12_10_MASK) != BITS_12_10_VRINT_FIXED) {
            return null;
        }
        int mode = (raw >>> MODE_SHIFT) & MODE_MASK;
        return switch (mode) {
            case 0b000 -> AdvSimdFpUnaryOp.RINTN;
            case 0b001 -> AdvSimdFpUnaryOp.RINTX;
            case 0b010 -> AdvSimdFpUnaryOp.RINTA;
            case 0b011 -> AdvSimdFpUnaryOp.RINTZ;
            case 0b101 -> AdvSimdFpUnaryOp.RINTM;
            case 0b111 -> AdvSimdFpUnaryOp.RINTP;
            case MODE_RESERVED_1, MODE_RESERVED_2 -> null;
            default -> null;
        };
    }
}
