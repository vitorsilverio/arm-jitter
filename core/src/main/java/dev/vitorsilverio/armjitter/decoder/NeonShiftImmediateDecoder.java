package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica a seção **"2-reg-and-shift" com deslocamento por IMEDIATO** do espaço NEON/Advanced
/// SIMD de processamento de dados do encoding A32 (task B13.7) — `VSHR`/`VSRA`/`VRSHR`/`VRSRA`/
/// `VSRI`/`VSHL`/`VSLI`/`VQSHLU`/`VQSHL` (14 famílias × 4 larguras = 56 linhas de
/// `target/isa-decode/neon-dp.decode:185-363`, parte não-estreitante/não-alargante).
///
/// Layout: `1111 001 U 1 D immH:3 immL:3 Vd:4 opc:4 L Q M 1 Vm:4`. O frame do grupo é
/// `(raw & 0xFE80_0010) == 0xF280_0010` — bits[31:25]=`1111001`, **bit23=1** (distingue de
/// "3-reg-same", bit23=0, que pertence a {@link NeonDataProcessingDecoder}) e **bit4=1** (distingue
/// de "two-reg-misc"/`VEXT`/`VTBL`/dup-scalar, bit4=0 — B13.12/B13.14).
///
/// **`L` (bit7) é o bit ALTO de `immh`**, não uma flag de largura: `immh = (L << 3) | immH`, e o
/// tamanho do elemento é SEMPRE `esz = highestSetImmhBit(immh)` (`0001`→byte, `001x`→halfword,
/// `01xx`→word, `1xxx`→doubleword). O encoding é EXATAMENTE o do A64 "AdvSIMD shift by immediate"
/// (`Aarch64Decoder#decodeAdvancedSimdShiftByImmediate`) com `L` no papel de `immh[3]`, então a
/// aritmética do deslocamento (`combined = immh:immb`, direita `2*esize-combined`, esquerda
/// `combined-esize`) e as 14 famílias são as mesmas — a semântica vive no núcleo COMPARTILHADO
/// {@code AdvSimdLanes.shiftImmediate} (RFC B13.2, D1).
///
/// **`immh == 0b0000`** (isto é `L=0 && immH=000`) NÃO é desta seção: é o `Vimm_1r`
/// (1-reg-and-modified-immediate, `VMOV`/`VORR`/`VBIC`/`VMVN` imediato, B13.9), que mora no MESMO
/// frame. Este decoder devolve **`null`** nesse caso (deixa o espaço livre para a B13.9), NÃO
/// `unimplemented` — é a ÚNICA exceção à regra "dentro do frame nunca devolvo `null`".
///
/// Fora de escopo (viram `UNIMPLEMENTED` explícito aqui, G8): estreitamento/alargamento
/// (`opc=1000`/`1001`/`1010`) e `VCVT` fixo↔float (`opc=1100`-`1111`) → **B13.8**; os 3 slots
/// UNALLOCATED reais (`opc=0100 U=0`, `opc=0110 U=0`, `opc=1011`); forma `Q` com registrador ímpar;
/// T32 → B13.16. `FPSCR.QC` (bit cumulativo de saturação de `VQSHL`/`VQSHLU`) NÃO é modelado —
/// paridade com o A64 e com B13.5, task futura própria.
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** (B13.22 é quem fecha
/// isso), então sem a feature {@link #tryDecode} devolve `null` e o espaço continua caindo no
/// `UNIMPLEMENTED` de `ArmDecoder#decodeUnconditional` (zero-diff, G3).
public final class NeonShiftImmediateDecoder implements DecoderExtension {
    /// Frame do grupo: bits[31:25]=`1111001` (U=bit24 fica de fora), bit23=1, bit4=1.
    private static final int FRAME_MASK = 0xFE80_0010;
    private static final int FRAME_VALUE = 0xF280_0010;

    // ── Campos (convenção `D:Vd` de VfpDecoder em precisão dupla) ──
    private static final int U_BIT = 24;
    private static final int IMMH_SHIFT = 19;
    private static final int IMMH_MASK = 0x7;
    private static final int IMML_SHIFT = 16;
    private static final int IMML_MASK = 0x7;
    private static final int OPC_SHIFT = 8;
    private static final int OPC_MASK = 0xF;
    private static final int L_BIT = 7;
    private static final int Q_BIT = 6;
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int NIBBLE_MASK = 0xF;

    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — consulta {@link ArmFeature#ADVANCED_SIMD}.
    public NeonShiftImmediateDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.ADVANCED_SIMD)) {
            return null;
        }
        if ((raw & FRAME_MASK) != FRAME_VALUE) {
            return null;
        }
        int u = (raw >>> U_BIT) & 1;
        int immH = (raw >>> IMMH_SHIFT) & IMMH_MASK;
        int immL = (raw >>> IMML_SHIFT) & IMML_MASK;
        int l = (raw >>> L_BIT) & 1;
        int immh = (l << 3) | immH;
        if (immh == 0) {
            // `Vimm_1r` (B13.9) — mesmo frame, ainda sem dono. NÃO reivindicar (G8 não se aplica:
            // devolver `null` deixa o espaço livre; devolver `unimplemented` o roubaria).
            return null;
        }
        // A partir daqui o frame é nosso: sempre `lifted` ou `unimplemented`, nunca `null`.
        int opc = (raw >>> OPC_SHIFT) & OPC_MASK;
        AdvSimdShiftImmediateOp op = shiftOperation(opc, u);
        if (op == null) {
            // opc de B13.8 (estreitando/alargando/`VCVT`) ou UNALLOCATED real (`opc=0100 U=0`,
            // `opc=0110 U=0`, `opc=1011`).
            return unimplemented(address, raw, condition);
        }
        boolean quad = ((raw >>> Q_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        // Forma `Q`: `vd`/`vm` nomeiam pares `D<2n>`/`D<2n+1>` — índice ímpar é UNDEFINED.
        if (quad && ((vd | vm) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        int esz = highestSetImmhBit(immh);
        int esize = 8 << esz;
        int combined = (immh << 3) | immL;
        int shift = isRightShift(op) ? (2 * esize - combined) : (combined - esize);
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonShiftImmediate(op, quad, esz, shift, vd, vm));
    }

    /// Mapeia `(opc, U)` para a família "shift by immediate" (tabela do Escopo da B13.7 — o `opc` de
    /// 4 bits do A32 é o `opcode` de 5 bits do A64 deslocado de 1). `null` para os `opc` de B13.8
    /// (`1000`/`1001`/`1010`/`1100`-`1111`) e para os 3 slots UNALLOCATED reais (`opc=0100 U=0`,
    /// `opc=0110 U=0`, `opc=1011`) — o chamador transforma `null` em `UNIMPLEMENTED` (G8).
    private static AdvSimdShiftImmediateOp shiftOperation(int opc, int u) {
        return switch (opc) {
            case 0b0000 -> u == 0 ? AdvSimdShiftImmediateOp.SSHR : AdvSimdShiftImmediateOp.USHR;
            case 0b0001 -> u == 0 ? AdvSimdShiftImmediateOp.SSRA : AdvSimdShiftImmediateOp.USRA;
            case 0b0010 -> u == 0 ? AdvSimdShiftImmediateOp.SRSHR : AdvSimdShiftImmediateOp.URSHR;
            case 0b0011 -> u == 0 ? AdvSimdShiftImmediateOp.SRSRA : AdvSimdShiftImmediateOp.URSRA;
            case 0b0100 -> u == 0 ? null : AdvSimdShiftImmediateOp.SRI;
            case 0b0101 -> u == 0 ? AdvSimdShiftImmediateOp.SHL : AdvSimdShiftImmediateOp.SLI;
            case 0b0110 -> u == 0 ? null : AdvSimdShiftImmediateOp.SQSHLU;
            case 0b0111 -> u == 0 ? AdvSimdShiftImmediateOp.SQSHL : AdvSimdShiftImmediateOp.UQSHL;
            default -> null;
        };
    }

    /// As 9 famílias de deslocamento à DIREITA (mesmo conjunto de `Aarch64Decoder`): usam
    /// `shift = 2*esize - combined` (faixa `1..esize`); as demais (`SHL`/`SLI`/`SQSHL`/`UQSHL`/
    /// `SQSHLU`) deslocam à esquerda com `shift = combined - esize` (faixa `0..esize-1`).
    private static boolean isRightShift(AdvSimdShiftImmediateOp op) {
        return switch (op) {
            case SSHR, USHR, SSRA, USRA, SRSHR, URSHR, SRSRA, URSRA, SRI -> true;
            case SHL, SLI, SQSHL, UQSHL, SQSHLU -> false;
        };
    }

    /// Posição (`0`-`3`) do bit mais alto setado de `immh` (4 bits): `0`=byte, `1`=halfword,
    /// `2`=word, `3`=doubleword. Cópia de `Aarch64Decoder#highestSetImmhBit` (privado, do pipeline
    /// A64) — o encoding A32 deriva o tamanho do elemento do mesmo jeito (`ARM DDI 0406C`, "shift by
    /// immediate"). Aqui `immh` NUNCA é `0` (o chamador já devolveu `null` nesse caso).
    private static int highestSetImmhBit(int immh) {
        for (int bit = 3; bit >= 0; bit--) {
            if (((immh >>> bit) & 1) != 0) {
                return bit;
            }
        }
        return -1;
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }
}
