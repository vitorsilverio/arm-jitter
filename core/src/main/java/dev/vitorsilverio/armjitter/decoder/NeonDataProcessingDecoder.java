package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica o espaço NEON/Advanced SIMD de processamento de dados do encoding A32 — **protótipo
/// da RFC B13.2**: hoje só a família "3-reg-same" `VADD`/`VSUB` INTEIRO, o suficiente para a RFC
/// medir o custo real de um degrau da escada B13 de ponta a ponta (encoding → IR → execução).
/// Oráculo: QEMU `target/arm/tcg/neon-dp.decode`, seção "3-reg-same".
///
/// Layout do encoding (`1111 001 U 0 D sz Vn Vd opc N Q M op Vm`): `opc=1000` com `op=0` é
/// `VADD` (`U=0`) / `VSUB` (`U=1`) inteiro; com `op=1` seria `VTST`/`VCEQ` (B13.4, ainda não
/// decodificado). Os campos de registrador seguem a convenção `D:Vd` do VFP em precisão dupla —
/// índice de `D` (`0`-`31`); na forma `Q` (bit `Q=1`) os três índices têm que ser PARES, e o `Q`
/// nomeado é o par `D<n>`/`D<n+1>`.
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** (B13.22 é quem fecha
/// isso), então esta extensão hoje é inerte em todo consumidor — o que sobra do espaço continua
/// caindo em `UNIMPLEMENTED` pelo próprio `ArmDecoder#decodeUnconditional` (G8).
public final class NeonDataProcessingDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar
    /// {@link ArmFeature#ADVANCED_SIMD}.
    public NeonDataProcessingDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    // ── 3-reg-same, opcode `1000`, `op=0` (bits 31:23 + 11:8 + 4 fixos; `size`/registradores livres) ──
    private static final int THREE_SAME_MASK = 0xFF80_0F10;
    private static final int VADD_INTEGER_VALUE = 0xF200_0800;
    private static final int VSUB_INTEGER_VALUE = 0xF300_0800;

    // ── Campos (mesma convenção `D:Vd` de VfpDecoder em precisão dupla) ──
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VN_NIBBLE_SHIFT = 16;
    private static final int VN_EXTENSION_BIT = 7;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int NIBBLE_MASK = 0xF;
    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    private static final int QUAD_BIT = 6;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.ADVANCED_SIMD)) {
            return null;
        }
        AdvSimdThreeSameOp op = threeSameOperation(raw);
        if (op == null) {
            return null;
        }
        boolean quad = ((raw >>> QUAD_BIT) & 1) != 0;
        int esz = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        // Forma `Q`: os três registradores nomeiam pares `D<n>`/`D<n+1>`, logo índice ímpar é
        // UNDEFINED no hardware real (ARM DDI 0406C A7.3, "Q registers are encoded as D<2n>").
        if (quad && (((vd | vn | vm) & 1) != 0)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
        }
        // NEON é sempre incondicional (espaço `cond=0b1111`) — nunca herda condição.
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonThreeSame(op, quad, esz, vd, vn, vm));
    }

    private static AdvSimdThreeSameOp threeSameOperation(int raw) {
        int masked = raw & THREE_SAME_MASK;
        if (masked == VADD_INTEGER_VALUE) {
            return AdvSimdThreeSameOp.ADD;
        }
        if (masked == VSUB_INTEGER_VALUE) {
            return AdvSimdThreeSameOp.SUB;
        }
        return null;
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }
}
