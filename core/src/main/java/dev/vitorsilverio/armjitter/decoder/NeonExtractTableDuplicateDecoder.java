package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica os **3 grupos de `size == 0b11`** (bits[21:20]) que vivem FORA do sub-layout
/// "2-reg-misc" da {@link NeonTwoRegMiscDecoder} (B13.12/B13.14): `VEXT` (bit24=`0`, único
/// mnemônico deste layout diferente de tudo em B13.4-B13.13), e `VTBL`/`VTBX`/`VDUP_scalar`
/// (bit24=`1`, bit11=`1` — o MESMO bit que a B13.12 usa para excluir este espaço do dela). Oráculo:
/// QEMU `target/arm/tcg/neon-dp.decode:399-418` (bloco "Miscellaneous size=0b11 insns", ANTES do
/// comentário "2-reg-misc grouping") + `translate-neon.c` (`trans_VEXT`/`trans_VTBL`/
/// `trans_VDUP_scalar`); ARM DDI 0406C A7.4.4/A7.4.5/A7.4.3. Frames e campos confirmados byte a
/// byte contra `arm-none-eabi-as -mfpu=neon -march=armv8-a` real (B13.14, ver `## Resultado` da
/// task).
///
/// A SEMÂNTICA de `VEXT`/`VTBL`/`VTBX` vem do núcleo COMPARTILHADO ({@link
/// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#extract}/{@code tableLookup}), RFC B13.2 D1 —
/// migração do MESMO algoritmo que o A64 usa para `EXT`/`TBL`/`TBX` (B8.10). `VDUP_scalar` fica
/// direto no executor (replicação trivial demais para justificar símbolo compartilhado).
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** (B13.22 fecha isso),
/// então sem a feature {@link #tryDecode} devolve `null` e o espaço cai no `UNIMPLEMENTED` de
/// `ArmDecoder#decodeUnconditional` (zero-diff, G3).
public final class NeonExtractTableDuplicateDecoder implements DecoderExtension {
    /// `VEXT`: `1111 001 0 1 . 11 nnnn dddd imm:4 . q:1 . 0 mmmm` — bit24=`0` é o único bit que o
    /// distingue de `VTBL`/`VTBX`/`VDUP_scalar`/2-reg-misc (todos com bit24=`1`).
    private static final int EXT_FRAME_MASK = 0xFFB0_0010;
    private static final int EXT_FRAME_VALUE = 0xF2B0_0000;
    private static final int EXT_IMM_SHIFT = 8;
    private static final int EXT_IMM_MASK = 0xF;
    private static final int EXT_IMM_Q_BIT = 0x8;
    private static final int QUAD_BIT = 6;

    /// `VTBL`/`VTBX`: `1111 001 1 1 . 11 nnnn dddd 10 len:2 . op:1 . 0 mmmm` — bit24=`1`,bit11=`1`,
    /// bit10=`0` (o bit10=`1` fixo é o que distingue de `VDUP_scalar`, que também tem bit11=`1`).
    private static final int TBL_FRAME_MASK = 0xFFB0_0C10;
    private static final int TBL_FRAME_VALUE = 0xF3B0_0800;
    private static final int TBL_LEN_SHIFT = 8;
    private static final int TBL_LEN_MASK = 0x3;
    private static final int TBL_OP_BIT = 6;

    /// `VDUP_scalar`: `1111 001 1 1 . 11 imm4:4 dddd 11 000 q:1 . 0 mmmm` — bit24=`1`,bit11=`1`,
    /// bit10=`1` (fixo, distingue de `VTBL`/`VTBX` acima). `imm4` (bits[19:16]) empacota tamanho E
    /// índice: o tamanho é a posição do bit `1` mais BAIXO de `imm4` (padrão clássico ARM de
    /// "imm4/lowest set bit"), não um campo `size` livre — ver `## Resultado` da B13.14 para a
    /// derivação confirmada contra `arm-none-eabi-as` real (`vdup.8 d0,d1[3]` → `imm4=0b0111`,
    /// `vdup.16 d0,d1[1]` → `imm4=0b0110`, `vdup.32 d0,d1[0]` → `imm4=0b0100`).
    private static final int DUP_FRAME_MASK = 0xFFB0_0F90;
    private static final int DUP_FRAME_VALUE = 0xF3B0_0C00;
    private static final int IMM4_SHIFT = 16;
    private static final int IMM4_MASK = 0xF;
    private static final int DUP_QUAD_BIT = 6;
    /// `esz` máximo que `VDUP_scalar` alcança (word, `imm4` bit16=`1` já indica byte — `esz` `0`-`2`
    /// são os únicos reais, `imm4==0000` é reservado).
    private static final int ESZ_MAX = 2;

    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int VN_EXTENSION_BIT = 7;
    private static final int NIBBLE_MASK = 0xF;
    /// Último registrador `D` real (ARM DDI 0406C: `Vn+len` não pode passar de `D31`, `VfpRegisters
    /// .DOUBLE_COUNT-1`).
    private static final int LAST_DOUBLE_REGISTER = 31;

    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — consulta {@link ArmFeature#ADVANCED_SIMD}.
    public NeonExtractTableDuplicateDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.ADVANCED_SIMD)) {
            return null;
        }
        if ((raw & EXT_FRAME_MASK) == EXT_FRAME_VALUE) {
            return decodeExt(raw, address, condition);
        }
        if ((raw & TBL_FRAME_MASK) == TBL_FRAME_VALUE) {
            return decodeTbl(raw, address, condition);
        }
        if ((raw & DUP_FRAME_MASK) == DUP_FRAME_VALUE) {
            return decodeDupScalar(raw, address, condition);
        }
        return null;
    }

    /// `VEXT`: `imm` (bits[11:8]) é contagem de BYTES. Sem `Q` (`q=false`), só `imm<8` é um encoding
    /// real — bit14 (o bit alto de `imm`, {@link #EXT_IMM_Q_BIT}) faz parte do campo `imm3` na forma
    /// `D` real, não existe; violar isso é reservado (G8).
    private static DecodedInstruction decodeExt(int raw, int address, Condition condition) {
        boolean q = ((raw >>> QUAD_BIT) & 1) != 0;
        int imm = (raw >>> EXT_IMM_SHIFT) & EXT_IMM_MASK;
        if (!q && (imm & EXT_IMM_Q_BIT) != 0) {
            return unimplemented(address, raw, condition);
        }
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, 16, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonExtract(q, imm, vd, vn, vm));
    }

    /// `VTBL`/`VTBX`: `len` (bits[9:8]) são `len+1` registradores `D` consecutivos a partir de `Vn`
    /// — `Vn+len` não pode passar de `D31` (ARM DDI 0406C), checado aqui (G8: fora daqui seria
    /// UNPREDICTABLE silencioso).
    private static DecodedInstruction decodeTbl(int raw, int address, Condition condition) {
        int len = (raw >>> TBL_LEN_SHIFT) & TBL_LEN_MASK;
        boolean tbx = ((raw >>> TBL_OP_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, 16, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        if (vn + len > LAST_DOUBLE_REGISTER) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonTableLookup(tbx, len, vd, vn, vm));
    }

    /// `VDUP_scalar`: `imm4` (bits[19:16]) empacota `esz`+`index` — `esz` é a posição do bit `1`
    /// mais BAIXO de `imm4` (`0`=byte com `imm4=xxx1`, `1`=halfword com `imm4=xx10`, `2`=word com
    /// `imm4=x100`), `index` são os bits ACIMA desse `1`. `imm4==0000` é reservado (G8).
    private static DecodedInstruction decodeDupScalar(int raw, int address, Condition condition) {
        int imm4 = (raw >>> IMM4_SHIFT) & IMM4_MASK;
        if (imm4 == 0) {
            return unimplemented(address, raw, condition);
        }
        int esz = Integer.numberOfTrailingZeros(imm4);
        if (esz > ESZ_MAX) {
            return unimplemented(address, raw, condition);
        }
        int index = imm4 >>> (esz + 1);
        boolean q = ((raw >>> DUP_QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        if (q && (vd & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonDuplicateScalar(esz, index, q, vd, vm));
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }
}
