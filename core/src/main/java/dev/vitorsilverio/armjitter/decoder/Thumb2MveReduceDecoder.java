package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VADDV`/`VADDLV`/`VABAV_S`/`VABAV_U`/`Vimm_1r` — sub-família 4 da B16.13a (perfil M, MVE/Helium,
/// `target/isa-decode/mve.decode`, linhas 575-597, 5 encodings, bit a bit contra o arquivo real):
///
/// ```
/// {
///   VADDV   111 u:1 1110 1111 size:2 01 ... 0 1111 0 0 a:1 0 qm:3 0 rda=%rdalo
///   VADDLV  111 u:1 1110 1 ... 1001 ... 0 1111 00 a:1 0 qm:3 0 rdahi=%rdahi rdalo=%rdalo
/// }
/// VABAV_S  111 0 1110 10 .. ... 0 .... 1111 . 0 . 0 ... 1 @vabav
/// VABAV_U  111 1 1110 10 .. ... 0 .... 1111 . 0 . 0 ... 1 @vabav
/// Vimm_1r  111 . 1111 1 . 00 0 ... ... 0 .... 0 1 . 1 .... @1imm
/// ```
///
/// **`VADDV`/`VADDLV` compartilham `bits[27:24]=1110` e ficam no MESMO `{}` sobreposto** — a ordem
/// TEXTUAL decide: `VADDV` (que fixa `bits[23:20]=1111`, `bits[17:16]=01`) é tentado ANTES de
/// `VADDLV` (que só fixa `bit23=1` e `bits[19:16]=1001`, deixando `bits[22:20]` livres para
/// `%rdahi`); um raw com `rdahi_raw=0b111` (`rdahi=15`) e `size=2` satisfaz os dois literalmente —
/// `VADDV` vence por prioridade, replicada aqui checando `VADDV` primeiro.
///
/// **Sem beat-wise partido**: `accumulate=false` (`a=0`) sempre começa a soma do ZERO (o "primeiro
/// beat" do QEMU real, já que este emulador executa cada instrução atomicamente — ver Javadoc de
/// {@link IrOp.MveVectorAddAcrossVector}). `VABAV_S`/`VABAV_U` NÃO têm bit `a`: sempre acumulam
/// sobre `Rda` atual (verbatim de `DO_VABAV`).
///
/// **`Vimm_1r` reusa `AdvSimdModifiedImmediate.expand`** (RFC B13.2 D1, mesmo núcleo do `VORR`/
/// `VBIC`/`VMOV`/`VMVN` imediato do NEON A32) — o decoder já resolve `op`/`imm64`, o executor só
/// aplica com predicação por byte. `bits[31:29]=111` desta linha SÓ colide com `VADDV`/`VADDLV`/
/// `VABAV` quando `bit28`(topo do imediato)`=1`, mas `Vimm_1r` fixa `bits[21:19]=000`, distinto do
/// `bits[21:20]=01`/`bits[23:20]=10..` das outras 4 — sem overlap real (checado bit a bit, não
/// assumido).
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. TEM que ser registrado ANTES de `Thumb2NocpDecoder`
/// (`bits[27:24]` desta seção `∈ {1110,1111}` sob `M_PROFILE`).
public final class Thumb2MveReduceDecoder implements DecoderExtension {
    private static final int GPR_SP = 13;
    private static final int GPR_PC = 15;

    // ─── VADDV / VADDLV ─────────────────────────────────────────────────────────────────────────
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int U_BIT = 28;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_ADD_ACROSS = 0b1110;

    private static final int VADDV_BITS23_20_SHIFT = 20;
    private static final int VADDV_BITS23_20_MASK = 0xF;
    private static final int VADDV_BITS23_20_VALUE = 0b1111;
    private static final int VADDV_SIZE_SHIFT = 18;
    private static final int VADDV_SIZE_MASK = 0x3;
    private static final int VADDV_SIZE_INVALID = 3;
    private static final int VADDV_BITS17_16_SHIFT = 16;
    private static final int VADDV_BITS17_16_MASK = 0x3;
    private static final int VADDV_BITS17_16_VALUE = 0b01;
    private static final int VADDV_RDALO_SHIFT = 13;
    private static final int VADDV_RDALO_MASK = 0x7;
    private static final int VADDV_BIT12 = 12;
    private static final int VADDV_BITS11_8_SHIFT = 8;
    private static final int VADDV_BITS11_8_MASK = 0xF;
    private static final int VADDV_BITS11_8_VALUE = 0b1111;
    private static final int VADDV_BITS7_6_SHIFT = 6;
    private static final int VADDV_BITS7_6_MASK = 0x3;
    private static final int A_BIT = 5;
    private static final int VADDV_BIT4 = 4;
    private static final int QM_LOW3_SHIFT = 1;
    private static final int QM_LOW3_MASK = 0x7;

    private static final int VADDLV_BIT23 = 23;
    private static final int VADDLV_RDAHI_RAW_SHIFT = 20;
    private static final int VADDLV_RDAHI_RAW_MASK = 0x7;
    private static final int VADDLV_BITS19_16_SHIFT = 16;
    private static final int VADDLV_BITS19_16_MASK = 0xF;
    private static final int VADDLV_BITS19_16_VALUE = 0b1001;
    private static final int VADDLV_RDALO_RAW_SHIFT = 13;
    private static final int VADDLV_RDALO_RAW_MASK = 0x7;

    // ─── VABAV ───────────────────────────────────────────────────────────────────────────────────
    private static final int VABAV_BITS23_22_SHIFT = 22;
    private static final int VABAV_BITS23_22_MASK = 0x3;
    private static final int VABAV_BITS23_22_VALUE = 0b10;
    private static final int VABAV_SIZE_SHIFT = 20;
    private static final int VABAV_SIZE_MASK = 0x3;
    private static final int VABAV_SIZE_INVALID = 3;
    private static final int VABAV_QN_LOW_SHIFT = 17;
    private static final int VABAV_QN_LOW_MASK = 0x7;
    private static final int VABAV_BIT16 = 16;
    private static final int VABAV_RDA_SHIFT = 12;
    private static final int VABAV_RDA_MASK = 0xF;
    private static final int VABAV_BITS11_8_SHIFT = 8;
    private static final int VABAV_BITS11_8_MASK = 0xF;
    private static final int VABAV_BITS11_8_VALUE = 0b1111;
    private static final int VABAV_QN_HIGH_BIT = 7;
    private static final int VABAV_BIT6 = 6;
    private static final int VABAV_QM_HIGH_BIT = 5;
    private static final int VABAV_BIT4 = 4;
    private static final int VABAV_QM_LOW_SHIFT = 1;
    private static final int VABAV_QM_LOW_MASK = 0x7;

    // ─── Vimm_1r ─────────────────────────────────────────────────────────────────────────────────
    private static final int VIMM_BITS27_23_SHIFT = 23;
    private static final int VIMM_BITS27_23_MASK = 0x1F;
    private static final int VIMM_BITS27_23_VALUE = 0b11111;
    private static final int VIMM_BITS21_19_SHIFT = 19;
    private static final int VIMM_BITS21_19_MASK = 0x7;
    private static final int VIMM_BITS21_19_VALUE = 0b000;
    private static final int VIMM_BIT12 = 12;
    private static final int VIMM_BIT7 = 7;
    private static final int VIMM_BIT6 = 6;
    private static final int VIMM_BIT4 = 4;
    private static final int VIMM_QD_HIGH_BIT = 22;
    private static final int VIMM_QD_LOW_SHIFT = 13;
    private static final int VIMM_QD_LOW_MASK = 0x7;
    private static final int VIMM_IMM_HIGH_BIT = 28;
    private static final int VIMM_IMM_MID_SHIFT = 16;
    private static final int VIMM_IMM_MID_MASK = 0x7;
    private static final int VIMM_IMM_LOW_MASK = 0xF;
    private static final int VIMM_CMODE_SHIFT = 8;
    private static final int VIMM_CMODE_MASK = 0xF;
    private static final int VIMM_OP_BIT = 5;

    private final ArmArchitecture architecture;

    public Thumb2MveReduceDecoder(ArmArchitecture architecture) {
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
        if (((raw >>> TOP24_SHIFT) & TOP24_MASK) == TOP24_ADD_ACROSS) {
            DecodedInstruction addV = tryDecodeAddV(raw, address, condition);
            if (addV != null) {
                return addV;
            }
            DecodedInstruction addLv = tryDecodeAddLv(raw, address, condition);
            if (addLv != null) {
                return addLv;
            }
            return tryDecodeAbav(raw, address, condition);
        }
        return tryDecodeImm(raw, address, condition);
    }

    /// `VADDV` — TEM que ser tentado antes de {@link #tryDecodeAddLv} (ver Javadoc da classe).
    private DecodedInstruction tryDecodeAddV(int raw, int address, Condition condition) {
        if (((raw >>> VADDV_BITS23_20_SHIFT) & VADDV_BITS23_20_MASK) != VADDV_BITS23_20_VALUE) {
            return null;
        }
        if (((raw >>> VADDV_BITS17_16_SHIFT) & VADDV_BITS17_16_MASK) != VADDV_BITS17_16_VALUE) {
            return null;
        }
        if (((raw >>> VADDV_BIT12) & 1) != 0) {
            return null;
        }
        if (((raw >>> VADDV_BITS11_8_SHIFT) & VADDV_BITS11_8_MASK) != VADDV_BITS11_8_VALUE) {
            return null;
        }
        if (((raw >>> VADDV_BITS7_6_SHIFT) & VADDV_BITS7_6_MASK) != 0) {
            return null;
        }
        if (((raw >>> VADDV_BIT4) & 1) != 0 || (raw & 1) != 0) {
            return null;
        }
        int size = (raw >>> VADDV_SIZE_SHIFT) & VADDV_SIZE_MASK;
        if (size == VADDV_SIZE_INVALID) {
            return null;
        }
        // `%rdalo` (times_2) só produz valores PARES (0-14) — `Rda` nunca pode ser 13/15 por
        // construção, mesma razão pela qual `trans_VADDV` real não checa `a->rda` (só `VADDLV`
        // checa `a->rdahi`, que É ímpar por construção). Nenhuma recusa aqui, verbatim.
        int rda = ((raw >>> VADDV_RDALO_SHIFT) & VADDV_RDALO_MASK) * 2;
        int qm = (raw >>> QM_LOW3_SHIFT) & QM_LOW3_MASK;
        boolean unsignedForm = ((raw >>> U_BIT) & 1) != 0;
        boolean accumulate = ((raw >>> A_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorAddAcrossVector(unsignedForm, accumulate, size, qm, rda, condition));
    }

    private DecodedInstruction tryDecodeAddLv(int raw, int address, Condition condition) {
        if (((raw >>> VADDLV_BIT23) & 1) == 0) {
            return null;
        }
        if (((raw >>> VADDLV_BITS19_16_SHIFT) & VADDLV_BITS19_16_MASK) != VADDLV_BITS19_16_VALUE) {
            return null;
        }
        if (((raw >>> VADDV_BIT12) & 1) != 0) {
            return null;
        }
        if (((raw >>> VADDV_BITS11_8_SHIFT) & VADDV_BITS11_8_MASK) != VADDV_BITS11_8_VALUE) {
            return null;
        }
        if (((raw >>> VADDV_BITS7_6_SHIFT) & VADDV_BITS7_6_MASK) != 0) {
            return null;
        }
        if (((raw >>> VADDV_BIT4) & 1) != 0 || (raw & 1) != 0) {
            return null;
        }
        int rdahiRaw = (raw >>> VADDLV_RDAHI_RAW_SHIFT) & VADDLV_RDAHI_RAW_MASK;
        int rdahi = rdahiRaw * 2 + 1;
        // `rdahi == GPR_PC` (15) É INALCANÇÁVEL por construção: só ocorre quando `rdahiRaw == 0b111`,
        // que força `bits[23:20] = 1111` — o MESMO literal de `VADDV`, que sempre vence a prioridade
        // textual do `{}` (ver Javadoc da classe) antes de `tryDecodeAddLv` ser tentado. Mantido
        // verbatim ao QEMU real (`trans_VADDLV` checa os dois), documentado como cobertura
        // impossível em vez de testado artificialmente.
        if (rdahi == GPR_SP || rdahi == GPR_PC) {
            return null;
        }
        int rdalo = ((raw >>> VADDLV_RDALO_RAW_SHIFT) & VADDLV_RDALO_RAW_MASK) * 2;
        int qm = (raw >>> QM_LOW3_SHIFT) & QM_LOW3_MASK;
        boolean unsignedForm = ((raw >>> U_BIT) & 1) != 0;
        boolean accumulate = ((raw >>> A_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorAddAcrossVectorLong(unsignedForm, accumulate, qm, rdahi, rdalo, condition));
    }

    private DecodedInstruction tryDecodeAbav(int raw, int address, Condition condition) {
        if (((raw >>> VABAV_BITS23_22_SHIFT) & VABAV_BITS23_22_MASK) != VABAV_BITS23_22_VALUE) {
            return null;
        }
        if (((raw >>> VABAV_BIT16) & 1) != 0) {
            return null;
        }
        if (((raw >>> VABAV_BITS11_8_SHIFT) & VABAV_BITS11_8_MASK) != VABAV_BITS11_8_VALUE) {
            return null;
        }
        if (((raw >>> VABAV_BIT6) & 1) != 0 || ((raw >>> VABAV_BIT4) & 1) != 0) {
            return null;
        }
        if ((raw & 1) == 0) {
            return null;
        }
        int size = (raw >>> VABAV_SIZE_SHIFT) & VABAV_SIZE_MASK;
        if (size == VABAV_SIZE_INVALID) {
            return null;
        }
        int qn = ((raw >>> VABAV_QN_HIGH_BIT) & 1) << 3 | ((raw >>> VABAV_QN_LOW_SHIFT) & VABAV_QN_LOW_MASK);
        int qm = ((raw >>> VABAV_QM_HIGH_BIT) & 1) << 3 | ((raw >>> VABAV_QM_LOW_SHIFT) & VABAV_QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qn)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        int rda = (raw >>> VABAV_RDA_SHIFT) & VABAV_RDA_MASK;
        if (rda == GPR_SP || rda == GPR_PC) {
            return null;
        }
        boolean unsignedForm = ((raw >>> U_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorAbsoluteDifferenceAccumulate(unsignedForm, size, qn, qm, rda, condition));
    }

    private DecodedInstruction tryDecodeImm(int raw, int address, Condition condition) {
        if (((raw >>> VIMM_BITS27_23_SHIFT) & VIMM_BITS27_23_MASK) != VIMM_BITS27_23_VALUE) {
            return null;
        }
        if (((raw >>> VIMM_BITS21_19_SHIFT) & VIMM_BITS21_19_MASK) != VIMM_BITS21_19_VALUE) {
            return null;
        }
        if (((raw >>> VIMM_BIT12) & 1) != 0) {
            return null;
        }
        if (((raw >>> VIMM_BIT7) & 1) != 0) {
            return null;
        }
        if (((raw >>> VIMM_BIT6) & 1) == 0) {
            return null;
        }
        if (((raw >>> VIMM_BIT4) & 1) == 0) {
            return null;
        }
        int qd = ((raw >>> VIMM_QD_HIGH_BIT) & 1) << 3 | ((raw >>> VIMM_QD_LOW_SHIFT) & VIMM_QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null;
        }
        int cmode = (raw >>> VIMM_CMODE_SHIFT) & VIMM_CMODE_MASK;
        int op = (raw >>> VIMM_OP_BIT) & 1;
        if (AdvSimdModifiedImmediate.isReservedInAarch32(cmode, op)) {
            return null;
        }
        int imm8 = ((raw >>> VIMM_IMM_HIGH_BIT) & 1) << 7
                | ((raw >>> VIMM_IMM_MID_SHIFT) & VIMM_IMM_MID_MASK) << 4
                | (raw & VIMM_IMM_LOW_MASK);
        AdvSimdModifiedImmediate.Expanded expanded = AdvSimdModifiedImmediate.expand(imm8, cmode, op);
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorModifiedImmediate(expanded.op(), expanded.imm64(), qd, condition));
    }
}
