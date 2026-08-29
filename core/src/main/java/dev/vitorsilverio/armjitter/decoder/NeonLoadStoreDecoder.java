package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica o espaço NEON/Advanced SIMD de LOAD/STORE do encoding A32 (task B13.3) — as 5 linhas
/// de `target/isa-decode/neon-ls.decode`: `VLDST_multiple` (estruturas múltiplas), `VLD_all_lanes`
/// (`VLD1R`-`VLD4R`) e `VLDST_single` (uma lane, 3 encodings por `size`). Irmão do
/// {@link NeonDataProcessingDecoder} de B13.2.
///
/// Oráculo: QEMU `target/arm/tcg/translate-neon.c` (`trans_VLDST_multiple`/`trans_VLDST_single`/
/// `trans_VLD_all_lanes` + a tabela `neon_ls_element_type`) e ARM DDI 0406C A7.7. **Achado da
/// B13.3**: a tabela `itype` transcrita na spec da task marcava `itype=3` como
/// `nregs=4, interleave=1, spacing=2`; o QEMU real tem `{2, 2, 2}` (`VLD2` de 4 registradores,
/// entrelaçado, double-spacing). A tabela abaixo segue o QEMU real.
///
/// Espaço comum: `1111 0100 xxx0 ...` (`bits[31:24]=0xF4`, `bit20=0`). `bit23=0` é
/// `VLDST_multiple`; `bit23=1` reparte por `bits[11:10]` (`0b11` = `VLD_all_lanes`, `0b00`/`0b01`/
/// `0b10` = `VLDST_single` com `size` `0`/`1`/`2`).
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** — sem a feature,
/// {@link #tryDecode} devolve `null` e o espaço continua caindo no `UNIMPLEMENTED` de
/// `ArmDecoder#decodeUnconditional` (zero-diff, G3). COM a feature, todo sub-encoding do espaço
/// que esta task não reconhece vira `DecodedInstruction.unimplemented` EXPLÍCITO (G8), nunca
/// `null` nem outra instrução.
public final class NeonLoadStoreDecoder implements DecoderExtension {
    /// `bits[31:24]=0xF4` e `bit20=0` — o espaço inteiro coberto por `neon-ls.decode`.
    private static final int SPACE_MASK = 0xFF10_0000;
    private static final int SPACE_VALUE = 0xF400_0000;

    private static final int BIT_A23 = 1 << 23;
    private static final int BIT_L21 = 1 << 21;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int RN_SHIFT = 16;
    private static final int NIBBLE_MASK = 0xF;
    private static final int PC_REGISTER = 15;

    /// `nregs` (repetições) / `interleave` (registradores por estrutura) / `spacing` (stride entre
    /// registradores `D`) por `itype` — `neon_ls_element_type[11]` do QEMU real. `itype > 10` é
    /// UNDEFINED.
    private static final int[][] ELEMENT_TYPE = {
            {1, 4, 1}, {1, 4, 2}, {4, 1, 1}, {2, 2, 2}, {1, 3, 1}, {1, 3, 2},
            {3, 1, 1}, {1, 1, 1}, {1, 2, 1}, {1, 2, 2}, {2, 1, 1},
    };
    private static final int MAX_ITYPE = 10;

    /// Maior índice de registrador `D` (`0`-`31`); ultrapassá-lo é UNDEFINED (NEON de 32 bits NÃO
    /// faz wrap-around módulo 32, ao contrário do A64).
    private static final int MAX_DOUBLE_REGISTER = 31;

    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — consulta {@link ArmFeature#ADVANCED_SIMD}.
    public NeonLoadStoreDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if ((raw & SPACE_MASK) != SPACE_VALUE) {
            return null;
        }
        if (!architecture.has(ArmFeature.ADVANCED_SIMD)) {
            return null;
        }
        if ((raw & BIT_A23) == 0) {
            return decodeMultiple(raw, address, condition);
        }
        int sizeMarker = (raw >>> 10) & 0x3;
        if (sizeMarker == 0x3) {
            return decodeAllLanes(raw, address, condition);
        }
        return decodeSingle(raw, address, condition, sizeMarker);
    }

    // ── VLDST_multiple: 1111 0100 0 D l 0 rn:4 vd:4 itype:4 size:2 align:2 rm:4 ──
    private DecodedInstruction decodeMultiple(int raw, int address, Condition condition) {
        int rn = (raw >>> RN_SHIFT) & NIBBLE_MASK;
        int vd = doubleRegister(raw);
        boolean load = (raw & BIT_L21) != 0;
        int itype = (raw >>> 8) & NIBBLE_MASK;
        int esz = (raw >>> 6) & 0x3;
        int align = (raw >>> 4) & 0x3;
        int rm = raw & NIBBLE_MASK;

        if (rn == PC_REGISTER) {
            return undefined(address, raw, condition);
        }
        if (itype > MAX_ITYPE) {
            return undefined(address, raw, condition);
        }
        // Valores inválidos do campo `align` por `itype` (QEMU `trans_VLDST_multiple`).
        switch (itype & 0xC) {
            case 0x4 -> {
                if (align >= 2) {
                    return undefined(address, raw, condition);
                }
            }
            case 0x8 -> {
                if (align == 3) {
                    return undefined(address, raw, condition);
                }
            }
            default -> { /* sem restrição */ }
        }
        int nregs = ELEMENT_TYPE[itype][0];
        int interleave = ELEMENT_TYPE[itype][1];
        int spacing = ELEMENT_TYPE[itype][2];
        if (esz == 3 && (interleave | spacing) != 1) {
            return undefined(address, raw, condition);
        }
        int lastRegister = vd + (nregs - 1) + spacing * (interleave - 1);
        if (lastRegister > MAX_DOUBLE_REGISTER) {
            return undefined(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonLoadStoreMultiple(load, vd, rn, rm, esz, nregs, interleave, spacing));
    }

    // ── VLDST_single: 1111 0100 1 D l 0 rn:4 vd:4 <size marker> n:2 <reg_idx/stride/align> rm:4 ──
    private DecodedInstruction decodeSingle(int raw, int address, Condition condition, int esz) {
        int rn = (raw >>> RN_SHIFT) & NIBBLE_MASK;
        int vd = doubleRegister(raw);
        boolean load = (raw & BIT_L21) != 0;
        int selem = ((raw >>> 8) & 0x3) + 1;
        int rm = raw & NIBBLE_MASK;

        int index;
        int stride;
        int align;
        switch (esz) {
            case 0 -> {
                index = (raw >>> 5) & 0x7;
                align = (raw >>> 4) & 0x1;
                stride = 1;
            }
            case 1 -> {
                index = (raw >>> 6) & 0x3;
                stride = ((raw >>> 5) & 0x1) + 1;
                align = (raw >>> 4) & 0x1;
            }
            default -> { // esz == 2
                index = (raw >>> 7) & 0x1;
                stride = ((raw >>> 6) & 0x1) + 1;
                align = (raw >>> 4) & 0x3;
            }
        }

        if (rn == PC_REGISTER) {
            return undefined(address, raw, condition);
        }
        switch (selem) {
            case 1 -> {
                if (stride != 1) {
                    return undefined(address, raw, condition);
                }
                if (((align & (1 << esz)) != 0) || (esz == 2 && (align == 1 || align == 2))) {
                    return undefined(address, raw, condition);
                }
            }
            case 2 -> {
                if (esz == 2 && (align & 2) != 0) {
                    return undefined(address, raw, condition);
                }
            }
            case 3 -> {
                if (align != 0) {
                    return undefined(address, raw, condition);
                }
            }
            default -> { // selem == 4
                if (esz == 2 && align == 3) {
                    return undefined(address, raw, condition);
                }
            }
        }
        int lastRegister = vd + stride * (selem - 1);
        if (lastRegister > MAX_DOUBLE_REGISTER) {
            return undefined(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonLoadStoreSingle(load, vd, rn, rm, esz, selem, stride, index));
    }

    // ── VLD_all_lanes: 1111 0100 1 D 1 0 rn:4 vd:4 11 n:2 size:2 t a rm:4 ──
    private DecodedInstruction decodeAllLanes(int raw, int address, Condition condition) {
        if ((raw & BIT_L21) == 0) {
            // bit21 é fixo `1` neste encoding — não existe "store to all lanes".
            return undefined(address, raw, condition);
        }
        int rn = (raw >>> RN_SHIFT) & NIBBLE_MASK;
        int vd = doubleRegister(raw);
        int selem = ((raw >>> 8) & 0x3) + 1;
        int size = (raw >>> 6) & 0x3;
        boolean t = ((raw >>> 5) & 0x1) != 0;
        boolean a = ((raw >>> 4) & 0x1) != 0;
        int rm = raw & NIBBLE_MASK;

        if (rn == PC_REGISTER) {
            return undefined(address, raw, condition);
        }
        int esz;
        if (size == 3) {
            // VLD4 "32 bits a 16-byte alignment" — só válido com nregs=4 e a=1 (QEMU).
            if (selem != 4 || !a) {
                return undefined(address, raw, condition);
            }
            esz = 2;
        } else {
            esz = size;
            if (a) {
                if (selem == 1 && size == 0) {
                    return undefined(address, raw, condition);
                }
                if (selem == 3) {
                    return undefined(address, raw, condition);
                }
            }
        }
        int stride = t ? 2 : 1;
        boolean quad = selem == 1 && t;
        int lastRegister = selem == 1
                ? vd + stride - 1
                : vd + stride * (selem - 1);
        if (lastRegister > MAX_DOUBLE_REGISTER) {
            return undefined(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonLoadAllLanes(vd, rn, rm, esz, selem, stride, quad));
    }

    private static int doubleRegister(int raw) {
        return (((raw >>> VD_EXTENSION_BIT) & 1) << 4) | ((raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK);
    }

    private static DecodedInstruction undefined(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }
}
