package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica a seção **"1-reg-and-modified-immediate"** do espaço NEON/Advanced SIMD de
/// processamento de dados do encoding A32 (task B13.9) — `VMOV`/`VMVN`/`VORR`/`VBIC` imediato.
///
/// Layout: `1111 001 i 1 D 000 imm3 Vd:4 cmode:4 0 Q op 1 imm4` (`neon-dp.decode:384`, `Vimm_1r`).
/// O frame é o BURACO `immh == 0` (`L`=bit7=0 **e** `immH`=bits[21:19]=`000`) que
/// {@link NeonShiftImmediateDecoder} (B13.7) recusa DE PROPÓSITO, deixando o espaço livre para este
/// decoder — os DOIS decoders compartilham o MESMO frame de bits[31:25]/bit23/bit4, e só a checagem
/// de `immh==0` distingue quem reivindica cada palavra.
///
/// **Não há `Vm`/`Vn`**: bits[3:0] são a metade baixa do imediato (`imm4`), não um registrador —
/// diferente de TODA task NEON anterior (B13.4-B13.8), onde esse campo é `Vm`.
///
/// `cmode`/`op` (não a tabela de bits do frame) discriminam as 4 famílias reais — a classificação e
/// a expansão do imediato vivem no núcleo COMPARTILHADO {@link AdvSimdModifiedImmediate} (RFC B13.2,
/// decisão D1 aplicada ANTES da duplicação: o mesmo núcleo serve o lado A64/`Vimm` da B19.6).
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** (B13.22 é quem fecha
/// isso), então sem a feature {@link #tryDecode} devolve `null` e o espaço continua caindo no
/// `UNIMPLEMENTED` de `ArmDecoder#decodeUnconditional` (zero-diff, G3).
public final class NeonModifiedImmediateDecoder implements DecoderExtension {
    /// Frame do grupo: bits[31:25]=`1111001`, bit23=1, bit7=0, bit4=1, bits[21:19]=`000`
    /// (`immh==0` — o buraco que B13.7 deixa). Diferente de {@link NeonShiftImmediateDecoder}, o
    /// `FRAME_MASK` aqui INCLUI bits[21:19]: é exatamente o que restringe este decoder ao buraco.
    private static final int FRAME_MASK = 0xFEB8_0090;
    private static final int FRAME_VALUE = 0xF280_0010;

    // ── Campos (convenção `D:Vd` de VfpDecoder em precisão dupla) ──
    private static final int I_BIT = 24;
    private static final int IMM3_SHIFT = 16;
    private static final int IMM3_MASK = 0x7;
    private static final int CMODE_SHIFT = 8;
    private static final int CMODE_MASK = 0xF;
    private static final int Q_BIT = 6;
    private static final int OP_BIT = 5;
    private static final int IMM4_MASK = 0xF;
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int NIBBLE_MASK = 0xF;

    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — consulta {@link ArmFeature#ADVANCED_SIMD}.
    public NeonModifiedImmediateDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.ADVANCED_SIMD)) {
            return null;
        }
        if ((raw & FRAME_MASK) != FRAME_VALUE) {
            // Fora do buraco `immh==0`: o resto do frame tem dono (B13.7/B13.8). Devolver `null`,
            // nunca `unimplemented` (G8) — um `unimplemented` aqui roubaria decode que já funciona.
            return null;
        }
        int i = (raw >>> I_BIT) & 1;
        int imm3 = (raw >>> IMM3_SHIFT) & IMM3_MASK;
        int imm4 = raw & IMM4_MASK;
        int imm8 = (i << 7) | (imm3 << 4) | imm4;
        int cmode = (raw >>> CMODE_SHIFT) & CMODE_MASK;
        int op = (raw >>> OP_BIT) & 1;
        if (AdvSimdModifiedImmediate.isReservedInAarch32(cmode, op)) {
            return unimplemented(address, raw, condition);
        }
        boolean quad = ((raw >>> Q_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        if (quad && (vd & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        AdvSimdModifiedImmediate.Expanded expanded = AdvSimdModifiedImmediate.expand(imm8, cmode, op);
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonModifiedImmediate(expanded.op(), quad, expanded.imm64(), vd));
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }
}
