package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Vector 2-op inteiro (perfil M, B16.6, MVE/Helium, `target/isa-decode/mve.decode`, seção "Vector
/// 2-op", linhas 211-219/281-366, 48 encodings) — todas as formas `@2op`/`@2op_nosz`/`@2op_rev`/
/// `@2op_sz28` desta seção, medidas verbatim contra o arquivo real (vendorizado em
/// `target/isa-decode/mve.decode`):
///
/// ```
/// VAND  1110 1111 0 . 00 ... 0 ... 0 0001 . 1 . 1 ... 0 @2op_nosz
/// VBIC  1110 1111 0 . 01 ... 0 ... 0 0001 . 1 . 1 ... 0 @2op_nosz
/// VORR  1110 1111 0 . 10 ... 0 ... 0 0001 . 1 . 1 ... 0 @2op_nosz
/// VORN  1110 1111 0 . 11 ... 0 ... 0 0001 . 1 . 1 ... 0 @2op_nosz
/// VEOR  1111 1111 0 . 00 ... 0 ... 0 0001 . 1 . 1 ... 0 @2op_nosz
/// VADD  1110 1111 0 . .. ... 0 ... 0 1000 . 1 . 0 ... 0 @2op
/// VSUB  1111 1111 0 . .. ... 0 ... 0 1000 . 1 . 0 ... 0 @2op
/// VMUL  1110 1111 0 . .. ... 0 ... 0 1001 . 1 . 1 ... 0 @2op
/// VMAX_S/VMAX_U/VMIN_S/VMIN_U/VABD_S/VABD_U/VHADD_S/VHADD_U/VHSUB_S/VHSUB_U        111 U 1111 0 . .. ... 0 ... 0 NNNN . 1 . s ... 0 @2op
/// VMULLP_B/VMULL_BS/VMULL_BU (bloco {})   111 . 1110 0 . 11 ... 1 ... 0 1110 . 0 . 0 ... 0 @2op_sz28 / 111 U 1110 0 . .. ... 1 ... 0 1110 . 0 . 0 ... 0 @2op
/// VMULLP_T/VMULL_TS/VMULL_TU (bloco {})   111 . 1110 0 . 11 ... 1 ... 1 1110 . 0 . 0 ... 0 @2op_sz28 / 111 U 1110 0 . .. ... 1 ... 1 1110 . 0 . 0 ... 0 @2op
/// VQDMULH   1110 1111 0 . .. ... 0 ... 0 1011 . 1 . 0 ... 0 @2op
/// VQRDMULH  1111 1111 0 . .. ... 0 ... 0 1011 . 1 . 0 ... 0 @2op
/// VQADD_S/VQADD_U/VQSUB_S/VQSUB_U         111 U 1111 0 . .. ... 0 ... 0 NNNN . 1 . 1 ... 0 @2op
/// VSHL_S/VSHL_U/VRSHL_S/VRSHL_U/VQSHL_S/VQSHL_U/VQRSHL_S/VQRSHL_U   111 U 1111 0 . .. ... 0 ... 0 NNNN . 1 . s ... 0 @2op_rev
/// VRHADD_S/VRHADD_U   111 U 1111 0 . .. ... 0 ... 0 0001 . 1 . 0 ... 0 @2op
/// VADC/VADCI (bloco {})     1110 1110 0 . 11 ... 0 ... T 1111 . 0 . 0 ... 0 @2op_nosz
/// VHCADD90/VHCADD270 (bloco {}) 1110 1110 0 . .. ... 0 ... T 1111 . 0 . 0 ... 0 @2op
/// VSBC/VSBCI (bloco {})     1111 1110 0 . 11 ... 0 ... T 1111 . 0 . 0 ... 0 @2op_nosz
/// VCADD90/VCADD270 (bloco {})   1111 1110 0 . .. ... 0 ... T 1111 . 0 . 0 ... 0 @2op
/// ```
///
/// **Achado central (Armadilha 1, mesma da B16.2-B16.5)**: `bits[27:25] = 111` cobre TODA esta seção
/// — colide com `Thumb2NocpDecoder` (que reivindica `bits[27:24] ∈ {1110,1111}` sob `M_PROFILE`).
/// Este decoder TEM que ser registrado ANTES dele em {@code ArmArchitecture#ARMV8_1M_MVE}.
///
/// **Achado que corrige a suposição inicial da task (Armadilha 2)**: `VMULL_B*`/`VMULLP_B`
/// (`top=false`) e `VMULL_T*`/`VMULLP_T` (`top=true`) NÃO selecionam metade CONTÍGUA baixa/alta da
/// fonte (padrão `SMULL2`/`UMULL2` do A64) — selecionam lanes PARES/ÍMPARES intercaladas (`le*2 +
/// top`, verbatim de `DO_2OP_L`, `target/arm/tcg/mve_helper.c`, confirmado via `WebFetch`). Ver
/// Javadoc de {@link IrOp.MveVector2OpWidening}.
///
/// **Achado que corrige o tamanho de elemento de `VMULLP_*`**: o campo `%size_28` (`bit28+1`)
/// produz `1`/`2`, mas o tamanho REAL do elemento FONTE (o que este decoder grava em
/// {@link IrOp.MveVector2OpWidening#esz}) é `bit28` diretamente (`0`=byte→halfword, `1`=halfword→
/// word — QEMU nomeia as duas formas `vmullpbh`/`vmullpbw`, confirmado via `WebFetch`).
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. Usa o escape hatch de lifting
/// ({@link DecodedInstruction#lifted}) — a forma de operando (`Qd`/`Qn`/`Qm`/`esz`/`op`) não cabe
/// nos campos neutros de {@link DecodedInstruction}, mesmo precedente de
/// {@link Thumb2MveLoadStoreDecoder}.
public final class Thumb2MveVector2opDecoder implements DecoderExtension {
    /// `bits[31:29]` fixo em TODA a seção (`111`), `bit23` fixo em `0` — gate comum antes de
    /// qualquer ramo.
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int BIT23 = 23;

    private static final int U_BIT = 28;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_MULL_CARRY = 0b1110;
    private static final int TOP24_LOGIC_ARITH = 0b1111;

    private static final int QD_HIGH_BIT = 22;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    private static final int QN_HIGH_BIT = 7;
    private static final int QN_LOW_SHIFT = 17;
    private static final int QN_LOW_MASK = 0x7;
    private static final int QM_HIGH_BIT = 5;
    private static final int QM_LOW_SHIFT = 1;
    private static final int QM_LOW_MASK = 0x7;

    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    private static final int SIZE_INVALID = 3;

    private static final int BIT16 = 16;
    private static final int BIT12 = 12;
    private static final int OPCODE_NIBBLE_SHIFT = 8;
    private static final int OPCODE_NIBBLE_MASK = 0xF;
    private static final int BIT7 = 7;
    private static final int BIT6 = 6;
    private static final int BIT5 = 5;
    private static final int BIT4 = 4;
    private static final int BIT0 = 0;

    private final ArmArchitecture architecture;

    public Thumb2MveVector2opDecoder(ArmArchitecture architecture) {
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
        if (((raw >>> BIT23) & 1) != 0) {
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
        boolean u = ((raw >>> U_BIT) & 1) != 0;
        int top24 = (raw >>> TOP24_SHIFT) & TOP24_MASK;
        int nibble = (raw >>> OPCODE_NIBBLE_SHIFT) & OPCODE_NIBBLE_MASK;
        boolean bit16 = ((raw >>> BIT16) & 1) != 0;
        boolean bit12 = ((raw >>> BIT12) & 1) != 0;
        boolean bit6 = ((raw >>> BIT6) & 1) != 0;
        boolean bit4 = ((raw >>> BIT4) & 1) != 0;
        boolean bit0 = ((raw >>> BIT0) & 1) != 0;
        if (bit0) {
            return null; // bit0 é literal 0 em TODA a seção.
        }

        if (top24 == TOP24_MULL_CARRY) {
            return bit16 ? decodeMull(raw, address, condition, qd, qn, qm, u, nibble, bit12, bit6, bit4)
                    : decodeCarryOrComplexAdd(raw, address, condition, qd, qn, qm, u, nibble, bit12, bit6, bit4);
        }
        if (top24 == TOP24_LOGIC_ARITH) {
            if (bit16) {
                return null; // bit16 é literal 0 na seção lógica/aritmética.
            }
            return decodeLogicOrArithmetic(raw, address, condition, qd, qn, qm, u, nibble, bit12, bit6, bit4);
        }
        return null;
    }

    /// Bloco lógico/aritmético (`bits[27:24]=1111`): 8 linhas (`@2op_nosz`) + 24 linhas (`@2op`/
    /// `@2op_rev` do bloco2, nibble != `0001`/`1000`/`1001` reservado à lógica/add/sub/mul) + 2
    /// linhas `VRHADD` (nibble `0001`, `bit4=0`, distinto da lógica que usa `bit4=1` no MESMO
    /// nibble — achado medido, não presumido).
    private static DecodedInstruction decodeLogicOrArithmetic(int raw, int address, Condition condition, int qd,
            int qn, int qm, boolean u, int nibble, boolean bit12, boolean bit6, boolean bit4) {
        if (bit12) {
            return null; // bit12 é literal 0 em toda a seção lógica/aritmética.
        }
        if (nibble == 0b0001 && bit4) {
            return decodeLogic(raw, address, condition, qd, qn, qm, u, bit6);
        }
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        if (size == SIZE_INVALID) {
            return null;
        }
        AdvSimdThreeSameOp op = switch (nibble) {
            case 0b1000 -> !bit4 ? (u ? AdvSimdThreeSameOp.SUB : AdvSimdThreeSameOp.ADD) : null;
            case 0b1001 -> bit4 && !u ? AdvSimdThreeSameOp.MUL : null;
            case 0b0000 -> bit4 ? (u ? AdvSimdThreeSameOp.UQADD : AdvSimdThreeSameOp.SQADD)
                    : (u ? AdvSimdThreeSameOp.UHADD : AdvSimdThreeSameOp.SHADD);
            case 0b0010 -> bit4 ? (u ? AdvSimdThreeSameOp.UQSUB : AdvSimdThreeSameOp.SQSUB)
                    : (u ? AdvSimdThreeSameOp.UHSUB : AdvSimdThreeSameOp.SHSUB);
            case 0b0110 -> bit4 ? (u ? AdvSimdThreeSameOp.UMIN : AdvSimdThreeSameOp.SMIN)
                    : (u ? AdvSimdThreeSameOp.UMAX : AdvSimdThreeSameOp.SMAX);
            case 0b0111 -> !bit4 ? (u ? AdvSimdThreeSameOp.UABD : AdvSimdThreeSameOp.SABD) : null;
            case 0b1011 -> !bit4 ? (u ? AdvSimdThreeSameOp.SQRDMULH : AdvSimdThreeSameOp.SQDMULH) : null;
            case 0b0100 -> bit4 ? (u ? AdvSimdThreeSameOp.UQSHL : AdvSimdThreeSameOp.SQSHL)
                    : (u ? AdvSimdThreeSameOp.USHL : AdvSimdThreeSameOp.SSHL);
            case 0b0101 -> bit4 ? (u ? AdvSimdThreeSameOp.UQRSHL : AdvSimdThreeSameOp.SQRSHL)
                    : (u ? AdvSimdThreeSameOp.URSHL : AdvSimdThreeSameOp.SRSHL);
            case 0b0001 -> u ? AdvSimdThreeSameOp.URHADD : AdvSimdThreeSameOp.SRHADD;
            default -> null;
        };
        if (op == null || !bit6) {
            return null;
        }
        boolean reversed = nibble == 0b0100 || nibble == 0b0101; // @2op_rev: VSHL/VRSHL/VQSHL/VQRSHL.
        int irQn = reversed ? qm : qn;
        int irQm = reversed ? qn : qm;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVector2Op(op, size, qd, irQn, irQm, condition));
    }

    /// `bits[11:8]=0001`, `bit4=1` — `VAND`/`VBIC`/`VORR`/`VORN` (`@2op_nosz`, `bits[21:20]` é o
    /// SELETOR de operação, não `size`) / `VEOR` (`bit28=1`, MESMO seletor `00` de `VAND`).
    private static DecodedInstruction decodeLogic(int raw, int address, Condition condition, int qd, int qn,
            int qm, boolean u, boolean bit6) {
        if (!bit6) {
            return null;
        }
        int opSelect = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        AdvSimdThreeSameOp op = switch (opSelect) {
            case 0b00 -> u ? AdvSimdThreeSameOp.EOR : AdvSimdThreeSameOp.AND;
            case 0b01 -> u ? null : AdvSimdThreeSameOp.BIC;
            case 0b10 -> u ? null : AdvSimdThreeSameOp.ORR;
            case 0b11 -> u ? null : AdvSimdThreeSameOp.ORN;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        final int nosz = 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVector2Op(op, nosz, qd, qn, qm, condition));
    }

    /// `bits[27:24]=1110`, `bit16=1` — `VMULLP_B`/`VMULLP_T` (`@2op_sz28`, `bits[21:20]="11"`
    /// literal) e `VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (`@2op`, `bits[21:20]` = `size` real,
    /// `0`-`2`). `bit12`: `false`=`_B` (lanes PARES da fonte), `true`=`_T` (lanes ÍMPARES) — ver
    /// Javadoc da classe (achado: não é metade contígua).
    private static DecodedInstruction decodeMull(int raw, int address, Condition condition, int qd, int qn, int qm,
            boolean u, int nibble, boolean bit12, boolean bit6, boolean bit4) {
        if (nibble != 0b1110 || bit6 || bit4) {
            return null;
        }
        int sizeField = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        boolean top = bit12;
        if (sizeField == 0b11) {
            // VMULLP_B/VMULLP_T: esz FONTE real = bit28 (0=byte→halfword, 1=halfword→word) — ver
            // Javadoc da classe.
            int esz = u ? 1 : 0;
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveVector2OpWidening(AdvSimdWideningOp.PMULL, esz, top, qd, qn, qm, condition));
        }
        AdvSimdWideningOp op = u ? AdvSimdWideningOp.UMULL : AdvSimdWideningOp.SMULL;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVector2OpWidening(op, sizeField, top, qd, qn, qm, condition));
    }

    /// `bits[27:24]=1110`, `bit16=0` — dois blocos `{}`: `VADC`/`VADCI`/`VHCADD90`/`VHCADD270`
    /// (`bits[31:28]=1110`) e `VSBC`/`VSBCI`/`VCADD90`/`VCADD270` (`bits[31:28]=1111`). `bits[21:20]
    /// ="11"` literal → carry (`@2op_nosz`); senão → soma complexa (`@2op`, `size` real). `bit12`:
    /// carry → `false`=`VADC`/`VSBC`, `true`=`VADCI`/`VSBCI`; complexAdd → `false`=`90`,
    /// `true`=`270`.
    private static DecodedInstruction decodeCarryOrComplexAdd(int raw, int address, Condition condition, int qd,
            int qn, int qm, boolean u, int nibble, boolean bit12, boolean bit6, boolean bit4) {
        if (nibble != 0b1111 || bit6 || bit4) {
            return null;
        }
        int sizeField = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        boolean add = !u; // bits[31:28]=1110 (add-family) vs 1111 (sub-family).
        if (sizeField == 0b11) {
            boolean immediateCarry = bit12;
            return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                    new IrOp.MveVectorCarry(add, immediateCarry, qd, qn, qm, condition));
        }
        boolean rotate90 = !bit12;
        boolean halving = add; // VHCADD90/270 vivem no bloco 1110 (add-family); VCADD90/270 no 1111.
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorComplexAdd(rotate90, halving, sizeField, qd, qn, qm, condition));
    }
}
