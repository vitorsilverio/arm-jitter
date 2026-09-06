package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica `neon-shared.decode` — encodings NEON cujo bit a bit é **idêntico** em A32 e T32
/// (cabeçalho do arquivo QEMU real: *"Encodings for Neon instructions whose encoding is the same
/// for both A32 and T32"*), 23 linhas ao todo. A **B13.17** implementou as 4 de `VCMLA`/`VCADD`/
/// `VCMLA_scalar` (`FEAT_FCMA`); esta task (**B13.18**) implementa as **7** de `VSDOT`/`VUDOT`/
/// `VUSDOT`/`VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/`VSUDOT_scalar` (`FEAT_DotProd`/
/// `FEAT_I8MM`); as **12** restantes (`VDOT_b16`/`VFML`/`VSMMLA`/`VUMMLA`/`VUSMMLA`/`VMMLA_b16`/
/// `VFMA_b16` + as formas `_scalar`/`_scal` correspondentes, B13.19-B13.21) ainda não têm dono.
///
/// **Este decoder devolve `null` (não `unimplemented`) para o que ainda não tem dono** — decisão
/// registrada na task B13.17: como o arquivo cresce ao longo de 5 tasks (B13.17-B13.21), reivindicar
/// o frame inteiro agora faria as linhas restantes virarem `UNIMPLEMENTED` prematuramente, antes
/// de alguém as implementar. A B13.21 (última do arquivo) troca este comportamento por
/// `unimplemented` explícito (G8) quando fechar o arquivo inteiro. Até lá, o `null` cai no
/// fallback de `ArmDecoder#decodeUnconditional`/`decodeUnconditionalThumb`, que já devolve
/// `UNIMPLEMENTED` no fim da cadeia de extensões — o comportamento observável para as linhas ainda
/// não implementadas não muda.
///
/// Layout comum (`neon-shared.decode`, `%vd_dp`/`%vn_dp`/`%vm_dp`, mesma convenção `D:Vd` de
/// `VfpDecoder`/`NeonDataProcessingDecoder` em precisão dupla): `Vd` = bit22:bits[15:12], `Vn` =
/// bit7:bits[19:16], `Vm` = bit5:bits[3:0] (exceto nas formas indexadas — `VCMLA_scalar size=1` e
/// TODAS as 4 `*_scalar` de produto escalar — onde `Vm` é um nibble DIRETO em bits[3:0], sem bit de
/// extensão — ver {@link #decodeComplexScalar}/{@link #decodeDotProductScalar}).
///
/// Encodings golden conferidos com `arm-none-eabi-as -march=armv8.3-a -fpu neon-fp-armv8
/// -mfpu=neon-fp-armv8 .arch_extension fp16` (`VCMLA`/`VCADD`, B13.17) e
/// `arm-none-eabi-as -march=armv8.2-a+i8mm -mfpu=neon-fp-armv8 .arch_extension dotprod` (`VSDOT`/
/// `VUDOT`/`VUSDOT`/`VSUDOT`, B13.18, devkitARM) — ver `## Resultado` de cada task para o log.
///
/// Gates: {@link ArmFeature#COMPLEX_NUMBER_ARITHMETIC} (`FEAT_FCMA`, B13.17),
/// {@link ArmFeature#DOT_PRODUCT} (`FEAT_DotProd`, `VSDOT`/`VUDOT` + formas `_scalar`, B13.18) e
/// {@link ArmFeature#INT8_MATRIX_MULTIPLY} (`FEAT_I8MM`, `VUSDOT` + `VUSDOT_scalar`/
/// `VSUDOT_scalar`, B13.18 — **quatro versões de arquitetura depois de `FEAT_DotProd`, gatear as 7
/// juntas seria factualmente errado**). **Nenhum preset declara nenhuma das três** (a saída de
/// `NOT_IN_ANY_PRESET` é a B13.22), então sem a feature respectiva o encoding cai no
/// `UNIMPLEMENTED` de `ArmDecoder#decodeUnconditional` (zero-diff).
public final class NeonSharedDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar
    /// {@link ArmFeature#COMPLEX_NUMBER_ARITHMETIC}.
    public NeonSharedDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    // ── Campos comuns (convenção `D:Vd` de VfpDecoder em precisão dupla) ──
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VN_NIBBLE_SHIFT = 16;
    private static final int VN_EXTENSION_BIT = 7;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int NIBBLE_MASK = 0xF;
    private static final int QUAD_BIT = 6;

    // ── `VCMLA` (vetorial): `1111 110 rot:2 . 1 . size .... .... 1000 . q . 0 ....` ──
    private static final int VCMLA_MASK = 0xFE20_0F10;
    private static final int VCMLA_VALUE = 0xFC20_0800;
    private static final int VCMLA_ROTATE_SHIFT = 23;
    private static final int VCMLA_ROTATE_MASK = 0x3;
    private static final int VCMLA_SIZE_BIT = 20;

    // ── `VCADD` (vetorial): `1111 110 rot:1 1 . 0 size .... .... 1000 . q . 0 ....` ──
    private static final int VCADD_MASK = 0xFEA0_0F10;
    private static final int VCADD_VALUE = 0xFC80_0800;
    private static final int VCADD_ROTATE_BIT = 24;
    private static final int VCADD_SIZE_BIT = 20;

    // ── `VCMLA_scalar`, prefixo comum (`1111 1110 ....`) — as duas formas de `size` divergem no
    // resto do encoding (ver {@link #decodeComplexScalar}).
    private static final int VCMLA_SCALAR_COMMON_MASK = 0xFF00_0F10;
    private static final int VCMLA_SCALAR_COMMON_VALUE = 0xFE00_0800;
    private static final int VCMLA_SCALAR_SIZE_BIT = 23;
    private static final int VCMLA_SCALAR_ROTATE_SHIFT = 20;
    private static final int VCMLA_SCALAR_ROTATE_MASK = 0x3;
    private static final int VCMLA_SCALAR_INDEX_BIT = 5;

    // ── Produto escalar (B13.18): MASK compartilhada (mesmas posições fixas nas 7 linhas — só o
    // byte de prefixo, a "família" bits[24:23]/[21:20] e o bit de sinal bit4 mudam de VALUE),
    // medida byte a byte contra `arm-none-eabi-as -march=armv8.2-a+i8mm -mfpu=neon-fp-armv8
    // .arch_extension dotprod` (devkitARM). ──
    private static final int DOT_PRODUCT_MASK = 0xFFB0_0F10;
    // `VSDOT`/`VUDOT` (vetorial): `1111 110 00 . 10 .... .... 1101 . q . 0 sign ....`.
    private static final int DOT_PRODUCT_VECTOR_SDOT_VALUE = 0xFC20_0D00;
    private static final int DOT_PRODUCT_VECTOR_UDOT_VALUE = 0xFC20_0D10;
    // `VUSDOT` (vetorial, `FEAT_I8MM`) — bits[24:23]=01; não existe `VSUDOT` vetorial.
    private static final int DOT_PRODUCT_VECTOR_USDOT_VALUE = 0xFCA0_0D00;
    // `VSDOT_scalar`/`VUDOT_scalar`: `1111 1110 0 . 10 .... .... 1101 . q index 0 sign vm:4`.
    private static final int DOT_PRODUCT_SCALAR_SDOT_VALUE = 0xFE20_0D00;
    private static final int DOT_PRODUCT_SCALAR_UDOT_VALUE = 0xFE20_0D10;
    // `VUSDOT_scalar`/`VSUDOT_scalar` (`FEAT_I8MM`) — bit23=1, bits[21:20]=00.
    private static final int DOT_PRODUCT_SCALAR_USDOT_VALUE = 0xFE80_0D00;
    private static final int DOT_PRODUCT_SCALAR_SUDOT_VALUE = 0xFE80_0D10;
    private static final int DOT_PRODUCT_INDEX_BIT = 5;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (architecture.has(ArmFeature.COMPLEX_NUMBER_ARITHMETIC)) {
            if ((raw & VCMLA_MASK) == VCMLA_VALUE) {
                return decodeComplexVector(raw, address, condition, true);
            }
            if ((raw & VCADD_MASK) == VCADD_VALUE) {
                return decodeComplexVector(raw, address, condition, false);
            }
            if ((raw & VCMLA_SCALAR_COMMON_MASK) == VCMLA_SCALAR_COMMON_VALUE) {
                return decodeComplexScalar(raw, address, condition);
            }
        }
        if (architecture.has(ArmFeature.DOT_PRODUCT)) {
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_VECTOR_SDOT_VALUE) {
                return decodeDotProductVector(raw, address, condition, true, true);
            }
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_VECTOR_UDOT_VALUE) {
                return decodeDotProductVector(raw, address, condition, false, false);
            }
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_SCALAR_SDOT_VALUE) {
                return decodeDotProductScalar(raw, address, condition, true, true);
            }
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_SCALAR_UDOT_VALUE) {
                return decodeDotProductScalar(raw, address, condition, false, false);
            }
        }
        if (architecture.has(ArmFeature.INT8_MATRIX_MULTIPLY)) {
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_VECTOR_USDOT_VALUE) {
                return decodeDotProductVector(raw, address, condition, false, true);
            }
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_SCALAR_USDOT_VALUE) {
                return decodeDotProductScalar(raw, address, condition, false, true);
            }
            if ((raw & DOT_PRODUCT_MASK) == DOT_PRODUCT_SCALAR_SUDOT_VALUE) {
                return decodeDotProductScalar(raw, address, condition, true, false);
            }
        }
        // Ainda sem dono (B13.19-B13.21): devolve `null` de propósito, ver javadoc da classe.
        return null;
    }

    /// `VCMLA`/`VCADD` (forma vetorial, 3 registradores): rotação já convertida para GRAUS
    /// (`código * 90`) — `VCMLA` tem 2 bits de rotação (bits[24:23]), `VCADD` só 1 (bit24, `0`=90°/
    /// `1`=270°). `size` (bit20, `%vcadd_size = 20:1 !function=plus_1`) é o MESMO campo/convenção
    /// nas duas: `0`⇒`esz=1` (F16), `1`⇒`esz=2` (F32) — sentido INVERTIDO do `sz` de `3same_fp`
    /// (o `.decode` avisa, ver Armadilha 1 da task). Forma `Q`: os 3 registradores nomeiam pares
    /// `D<2n>`/`D<2n+1>` — índice ímpar é UNDEFINED (mesma disciplina de `NeonDataProcessingDecoder`).
    private DecodedInstruction decodeComplexVector(int raw, int address, Condition condition, boolean cmla) {
        int rotation = cmla
                ? ((raw >>> VCMLA_ROTATE_SHIFT) & VCMLA_ROTATE_MASK) * 90
                : (((raw >>> VCADD_ROTATE_BIT) & 1) == 0 ? 90 : 270);
        int sizeBit = cmla ? VCMLA_SIZE_BIT : VCADD_SIZE_BIT;
        int esz = ((raw >>> sizeBit) & 1) + 1;
        boolean quad = ((raw >>> QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        if (quad && ((vd | vn | vm) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonComplex(cmla, rotation, quad, esz, vd, vn, vm));
    }

    /// `VCMLA_scalar` (forma indexada — não existe `VCADD_scalar`): discriminada por bit23 (`0`⇒
    /// `size=1`/F16, `1`⇒`size=2`/F32; campo LITERAL no `.decode`, não extraído por fórmula). Rotação
    /// = `bits[21:20] * 90` nas DUAS formas. F16: `Vm` é um nibble DIRETO em bits[3:0] (`D0`-`D15`,
    /// sem bit de extensão) e `índice` é o bit5 (`0`/`1` — um `D` guarda 2 complexos de meia
    /// precisão). F32: `Vm` segue `%vm_dp` padrão e `índice` é sempre `0` (um complexo de precisão
    /// simples ocupa o `D` inteiro — não há bit de índice no encoding real). Forma `Q`: `Vd`/`Vn`
    /// nomeiam pares `D<2n>`/`D<2n+1>` — índice ímpar é UNDEFINED; `Vm` NUNCA é `Q` nesta forma (não
    /// entra na checagem).
    private DecodedInstruction decodeComplexScalar(int raw, int address, Condition condition) {
        boolean singlePrecision = ((raw >>> VCMLA_SCALAR_SIZE_BIT) & 1) != 0;
        int esz = singlePrecision ? 2 : 1;
        int rotation = ((raw >>> VCMLA_SCALAR_ROTATE_SHIFT) & VCMLA_SCALAR_ROTATE_MASK) * 90;
        boolean quad = ((raw >>> QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm;
        int index;
        if (singlePrecision) {
            vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
            index = 0;
        } else {
            vm = raw & NIBBLE_MASK;
            index = (raw >>> VCMLA_SCALAR_INDEX_BIT) & 1;
        }
        if (quad && ((vd | vn) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonComplexByElement(rotation, quad, esz, vd, vn, vm, index));
    }

    /// `VSDOT`/`VUDOT`/`VUSDOT` (forma vetorial, 3 registradores): `signedN`/`signedM` já vêm
    /// decodificados pelo chamador ({@link #tryDecode}, um `if` por instrução — nunca inferidos do
    /// `raw` aqui). Forma `Q`: os 3 registradores nomeiam pares `D<2n>`/`D<2n+1>` — índice ímpar é
    /// UNDEFINED (mesma disciplina de {@link #decodeComplexVector}).
    private DecodedInstruction decodeDotProductVector(int raw, int address, Condition condition,
            boolean signedN, boolean signedM) {
        boolean quad = ((raw >>> QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        if (quad && ((vd | vn | vm) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonDotProduct(signedN, signedM, quad, vd, vn, vm));
    }

    /// `VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/`VSUDOT_scalar`: `Vm` é um nibble DIRETO
    /// (`D0`-`D15`, sem bit de extensão — nunca combinado com `quad`, mesma disciplina de
    /// {@link #decodeComplexScalar} na forma F16) e `índice` é o bit5 (seleciona qual lane de 32
    /// bits de `Vm` é o operando FIXO).
    private DecodedInstruction decodeDotProductScalar(int raw, int address, Condition condition,
            boolean signedN, boolean signedM) {
        boolean quad = ((raw >>> QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = raw & NIBBLE_MASK;
        int index = (raw >>> DOT_PRODUCT_INDEX_BIT) & 1;
        if (quad && ((vd | vn) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonDotProductByElement(signedN, signedM, quad, vd, vn, vm, index));
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }
}
