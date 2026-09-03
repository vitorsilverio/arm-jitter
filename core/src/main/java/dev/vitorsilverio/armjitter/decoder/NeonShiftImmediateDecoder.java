package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
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
/// **B13.8** acrescentou o ESTREITAMENTO (`opc=1000`/`1001` — `VSHRN`/`VRSHRN`/`VQSHRUN`/
/// `VQRSHRUN`/`VQSHRN`/`VQRSHRN`), o ALARGAMENTO (`opc=1010` — `VSHLL`) e o `VCVT` fixo↔float **F32**
/// (`opc=1110`/`1111`), todos migrados para o núcleo COMPARTILHADO
/// ({@code AdvSimdLanes.shiftNarrowImmediate}/`shiftWidenImmediate`/`convertFixedPoint}).
///
/// Fora de escopo (viram `UNIMPLEMENTED` explícito aqui, G8): `VCVT` fixo↔float **F16**
/// (`opc=1100`/`1101`) → task irmã "NEON FP16 AArch32" (depende de B19.5.1); os slots UNALLOCATED
/// reais (`opc=0100 U=0`, `opc=0110 U=0`, `opc=1011`, `opc=1010` com `Q=1`); forma `Q` com
/// registrador ímpar; T32 → B13.16. `FPSCR.QC` (bit cumulativo de saturação de `VQSHL`/`VQSHLU`/
/// `VQSHRN`/...) NÃO é modelado — paridade com o A64 e com B13.5, task futura própria.
/// `FPSCR.RMode`: `VCVT` para inteiro arredonda SEMPRE toward-zero (o encoding desta forma não tem
/// variante de direção), `VCVT` para float usa round-to-nearest-even — mesma simplificação de
/// B8.5/B19.3.
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
            // ou é a seção "shift by immediate" estreitando/alargando/`VCVT` (B13.8), ou um slot
            // UNALLOCATED real (`opc=0100 U=0`, `opc=0110 U=0`, `opc=1011`).
            return decodeNarrowWidenConvert(raw, address, condition, u, immh, immL, opc);
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

    /// Seção "2-reg-and-shift" ESTREITANTE / ALARGANTE / `VCVT` fixo↔float (B13.8) — os `opc` que
    /// {@link #shiftOperation} devolve `null`. A aritmética do deslocamento é a MESMA de B13.7
    /// (`combined = immh:immb`, direita `2*esize-combined`, esquerda `combined-esize`), só que aqui
    /// `esz` é o lado ESTREITO (destino no estreitamento, fonte no alargamento) e `L` (bit7) é
    /// sempre `0`, então `esz==3` ⇒ o encoding não existe no `.decode` (UNALLOCATED).
    private DecodedInstruction decodeNarrowWidenConvert(int raw, int address, Condition condition,
            int u, int immh, int immL, int opc) {
        int esz = highestSetImmhBit(immh);
        int esize = 8 << esz;
        int combined = (immh << 3) | immL;
        int rightShift = 2 * esize - combined;
        int leftShift = combined - esize;
        int q = (raw >>> Q_BIT) & 1;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        return switch (opc) {
            case 0b1000, 0b1001 -> decodeNarrowing(raw, address, condition, opc, u, q, esz, rightShift, vd, vm);
            case 0b1010 -> decodeWidening(raw, address, condition, u, q, esz, leftShift, vd, vm);
            case 0b1110, 0b1111 -> decodeConvertFixedF32(raw, address, condition, opc, u, esz, rightShift, q, vd, vm);
            // `opc=1100`/`1101` = `VCVT` F16 (task irmã, depende de B19.5.1); `opc=1011` = UNALLOCATED.
            default -> unimplemented(address, raw, condition);
        };
    }

    /// `opc=1000`/`1001` — estreitamento. Fonte `Q` (elementos de `esz+1`), destino `D` (elementos
    /// de `esz`). O bit `Q` (bit6) é OPCODE, não largura (`.decode`: "here the Q bit is part of the
    /// opcode decode"). `esz==3` ⇒ `L=1`, sem linha no `.decode` ⇒ UNALLOCATED. Fonte `Q` ímpar ⇒
    /// UNDEFINED.
    private DecodedInstruction decodeNarrowing(int raw, int address, Condition condition, int opc,
            int u, int q, int esz, int rightShift, int vd, int vm) {
        if (esz == 3 || (vm & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        AdvSimdShiftNarrowOp op = narrowOperation(opc, u, q);
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonShiftNarrowImmediate(op, esz, rightShift, vd, vm));
    }

    /// `opc=1010` — alargamento (`VSHLL`). Fonte `D` (elementos de `esz`), destino `Q` (elementos
    /// de `esz+1`). `Q=1` é UNALLOCATED (não existe no `.decode`). `esz==3` (`L=1`) idem. Destino
    /// `Q` ímpar ⇒ UNDEFINED. `U` escolhe assinado (`SSHLL`) × não assinado (`USHLL`).
    private DecodedInstruction decodeWidening(int raw, int address, Condition condition,
            int u, int q, int esz, int leftShift, int vd, int vm) {
        if (q != 0 || esz == 3 || (vd & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        AdvSimdShiftWidenOp op = u == 0 ? AdvSimdShiftWidenOp.SSHLL : AdvSimdShiftWidenOp.USHLL;
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonShiftWidenImmediate(op, esz, leftShift, vd, vm));
    }

    /// `opc=1110`/`1111` — `VCVT` fixo↔float **F32**. `@2reg_vcvt` fixa bit21=1 ⇒ `esz==2`; qualquer
    /// outro `immH` (ou `L=1`) ⇒ UNALLOCATED. `Q` (bit6) é largura REAL aqui. `fractionBits =
    /// 2*32 - combined` (faixa `1..32`). `toFloat = (opc == 1110)`, `signed = (U == 0)`.
    private DecodedInstruction decodeConvertFixedF32(int raw, int address, Condition condition,
            int opc, int u, int esz, int rightShift, int q, int vd, int vm) {
        boolean quad = q != 0;
        if (esz != 2 || (quad && ((vd | vm) & 1) != 0)) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonConvertFixedPoint(quad, 2, rightShift, opc == 0b1110, u == 0, vd, vm));
    }

    /// `(opc, U, Q)` → família de estreitamento (tabela do Escopo da B13.8). `opc∈{1000,1001}`.
    private static AdvSimdShiftNarrowOp narrowOperation(int opc, int u, int q) {
        if (opc == 0b1000) {
            return u == 0
                    ? (q == 0 ? AdvSimdShiftNarrowOp.SHRN : AdvSimdShiftNarrowOp.RSHRN)
                    : (q == 0 ? AdvSimdShiftNarrowOp.SQSHRUN : AdvSimdShiftNarrowOp.SQRSHRUN);
        }
        return u == 0
                ? (q == 0 ? AdvSimdShiftNarrowOp.SQSHRN : AdvSimdShiftNarrowOp.SQRSHRN)
                : (q == 0 ? AdvSimdShiftNarrowOp.UQSHRN : AdvSimdShiftNarrowOp.UQRSHRN);
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
