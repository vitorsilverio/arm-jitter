package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica `neon-shared.decode` — encodings NEON cujo bit a bit é **idêntico** em A32 e T32
/// (cabeçalho do arquivo QEMU real: *"Encodings for Neon instructions whose encoding is the same
/// for both A32 and T32"*), 23 linhas ao todo. A **B13.17** implementou as 4 de `VCMLA`/`VCADD`/
/// `VCMLA_scalar` (`FEAT_FCMA`); a **B13.18** implementou as **7** de `VSDOT`/`VUDOT`/`VUSDOT`/
/// `VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/`VSUDOT_scalar` (`FEAT_DotProd`/`FEAT_I8MM`); esta
/// task (**B13.19**) implementou as **3** matriciais `VSMMLA`/`VUMMLA`/`VUSMMLA` (`FEAT_I8MM`); esta
/// task (**B13.20**) implementa as **4** de `VFML`/`VFMSL`/`VFML_scalar`/`VFMSL_scalar`
/// (`FEAT_FHM`); as **5** restantes (`VDOT_b16`/`VMMLA_b16`/`VFMA_b16` + as formas `_scalar`/
/// `_scal` correspondentes, B13.21) ainda não têm dono.
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
/// -mfpu=neon-fp-armv8 .arch_extension fp16` (`VCMLA`/`VCADD`, B13.17),
/// `arm-none-eabi-as -march=armv8.2-a+i8mm -mfpu=neon-fp-armv8 .arch_extension dotprod` (`VSDOT`/
/// `VUDOT`/`VUSDOT`/`VSUDOT`, B13.18, devkitARM) e
/// `arm-linux-gnueabihf-as -march=armv8.6-a+i8mm -mfpu=neon-fp-armv8` (`VSMMLA`/`VUMMLA`/
/// `VUSMMLA`, B13.19, WSL — mesmo binutils/GNU assembler que a B13.18 já usara para `neon-shared`,
/// `arm-none-eabi-as` indisponível neste ambiente) e
/// `arm-linux-gnueabihf-as -march=armv8.2-a+fp16fml -mfpu=neon-fp-armv8` (`VFML`/`VFMSL` +
/// `_scalar`, B13.20, WSL) — ver `## Resultado` de cada task para o log.
///
/// Gates: {@link ArmFeature#COMPLEX_NUMBER_ARITHMETIC} (`FEAT_FCMA`, B13.17),
/// {@link ArmFeature#DOT_PRODUCT} (`FEAT_DotProd`, `VSDOT`/`VUDOT` + formas `_scalar`, B13.18),
/// {@link ArmFeature#INT8_MATRIX_MULTIPLY} (`FEAT_I8MM`, `VUSDOT`/`VUSDOT_scalar`/
/// `VSUDOT_scalar`, B13.18, e `VSMMLA`/`VUMMLA`/`VUSMMLA`, B13.19 — **quatro versões de
/// arquitetura depois de `FEAT_DotProd`, gatear junto com ele seria factualmente errado**) e
/// {@link ArmFeature#FP16_FUSED_MULTIPLY_ADD_LONG} (`FEAT_FHM`, `VFML`/`VFMSL` + formas `_scalar`,
/// B13.20 — **não** `FEAT_FP16`, feature própria). **Nenhum preset declara nenhuma das quatro** (a
/// saída de `NOT_IN_ANY_PRESET` é a B13.22), então sem a feature respectiva o encoding cai no
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

    // ── Matriz de inteiros de 8 bits (B13.19, `FEAT_I8MM`): `1111 1100 x.10 .... .... 1100 .1.0
    // ....` (`x`=bit23 separa `VUSMMLA` das outras duas; bit4 separa `VUMMLA` de `VSMMLA`/
    // `VUSMMLA`) — medida byte a byte contra `arm-linux-gnueabihf-as -march=armv8.6-a+i8mm`. Sempre
    // 128 bits (bit6 fixo em 1, sem forma `D`). ──
    private static final int MATRIX_MULTIPLY_MASK = 0xFFB0_0F50;
    private static final int MATRIX_MULTIPLY_SMMLA_VALUE = 0xFC20_0C40;
    private static final int MATRIX_MULTIPLY_UMMLA_VALUE = 0xFC20_0C50;
    private static final int MATRIX_MULTIPLY_USMMLA_VALUE = 0xFCA0_0C40;

    // ── `VFML`/`VFMSL` (B13.20, `FEAT_FHM`), forma vetorial: `1111 1100 0010 .... .... 1000 . q . 1
    // ....` — `s` (bit23, soma/subtração) e `q` (bit6, `S`+`S`→`D` ou `D`+`D`→`Q`) são DADOS, não
    // fixos; `q` discrimina qual VALUE bate (mesmo truque de `DOT_PRODUCT_MASK`). Medido byte a byte
    // contra `arm-linux-gnueabihf-as -march=armv8.2-a+fp16fml -mfpu=neon-fp-armv8` (WSL). ──
    private static final int FUSED_MULTIPLY_ADD_LONG_MASK = 0xFF30_0F50;
    private static final int FUSED_MULTIPLY_ADD_LONG_SINGLE_VALUE = 0xFC20_0810;
    private static final int FUSED_MULTIPLY_ADD_LONG_DOUBLE_VALUE = 0xFC20_0850;
    private static final int FUSED_MULTIPLY_ADD_LONG_SIGN_BIT = 23;

    // ── `VFML_scalar`/`VFMSL_scalar` (B13.20): `1111 1110 00.0 .... .... 1000 . q . 1 . rm...` —
    // `s` aqui é bit20 (posição DIFERENTE da forma vetorial, bit23 — conferido byte a byte). `rm`/
    // `index` têm extratores próprios por `q`, ver {@link #decodeFusedMultiplyAddLongScalar}. ──
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_MASK = 0xFFA0_0F50;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_SINGLE_VALUE = 0xFE00_0810;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_DOUBLE_VALUE = 0xFE00_0850;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_SIGN_BIT = 20;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_RM_MASK = 0x7;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_SINGLE_RM_EXTENSION_BIT = 5;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_INDEX_BIT = 3;
    private static final int FUSED_MULTIPLY_ADD_LONG_SCALAR_DOUBLE_INDEX_HIGH_BIT = 5;

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
            if ((raw & MATRIX_MULTIPLY_MASK) == MATRIX_MULTIPLY_SMMLA_VALUE) {
                return decodeMatrixMultiply(raw, address, condition, true, true);
            }
            if ((raw & MATRIX_MULTIPLY_MASK) == MATRIX_MULTIPLY_UMMLA_VALUE) {
                return decodeMatrixMultiply(raw, address, condition, false, false);
            }
            if ((raw & MATRIX_MULTIPLY_MASK) == MATRIX_MULTIPLY_USMMLA_VALUE) {
                return decodeMatrixMultiply(raw, address, condition, false, true);
            }
        }
        if (architecture.has(ArmFeature.FP16_FUSED_MULTIPLY_ADD_LONG)) {
            if ((raw & FUSED_MULTIPLY_ADD_LONG_MASK) == FUSED_MULTIPLY_ADD_LONG_SINGLE_VALUE) {
                return decodeFusedMultiplyAddLong(raw, address, condition, false);
            }
            if ((raw & FUSED_MULTIPLY_ADD_LONG_MASK) == FUSED_MULTIPLY_ADD_LONG_DOUBLE_VALUE) {
                return decodeFusedMultiplyAddLong(raw, address, condition, true);
            }
            if ((raw & FUSED_MULTIPLY_ADD_LONG_SCALAR_MASK) == FUSED_MULTIPLY_ADD_LONG_SCALAR_SINGLE_VALUE) {
                return decodeFusedMultiplyAddLongScalar(raw, address, condition, false);
            }
            if ((raw & FUSED_MULTIPLY_ADD_LONG_SCALAR_MASK) == FUSED_MULTIPLY_ADD_LONG_SCALAR_DOUBLE_VALUE) {
                return decodeFusedMultiplyAddLongScalar(raw, address, condition, true);
            }
        }
        // Ainda sem dono (B13.21): devolve `null` de propósito, ver javadoc da classe.
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

    /// `VSMMLA`/`VUMMLA`/`VUSMMLA` (B13.19): `signedN`/`signedM` já vêm decodificados pelo chamador
    /// ({@link #tryDecode}, um `if` por instrução — nunca inferidos do `raw` aqui). **Sempre 128
    /// bits** (não há forma `D`): `Vd`/`Vn`/`Vm` nomeiam pares `D<2n>`/`D<2n+1>` — índice ímpar em
    /// qualquer um dos três é UNDEFINED (mesma disciplina de {@link #decodeDotProductVector}, mas
    /// sem campo `quad` explícito porque a forma `D` não existe).
    private DecodedInstruction decodeMatrixMultiply(int raw, int address, Condition condition,
            boolean signedN, boolean signedM) {
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);
        if (((vd | vn | vm) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonMatrixMultiplyAccumulate(signedN, signedM, vd, vn, vm));
    }

    /// `VFML`/`VFMSL` (B13.20, forma vetorial): `s` (bit23) escolhe soma (`0`, `VFMAL`) ou
    /// subtração (`1`, `VFMSL`). `quad=false`: `Vn`/`Vm` são registradores `S` (`%vn_sp`/`%vm_sp` —
    /// metade de 32 bits vista como par de f16), `Vd` é `D` (2 lanes f32). `quad=true`: `Vn`/`Vm`
    /// são `D` (4 lanes f16), `Vd` é `Q` (`D<vd>`:`D<vd+1>`, 4 lanes f32) — índice ÍMPAR é UNDEFINED
    /// (mesma disciplina do resto do arquivo).
    private DecodedInstruction decodeFusedMultiplyAddLong(int raw, int address, Condition condition, boolean quad) {
        boolean subtract = ((raw >>> FUSED_MULTIPLY_ADD_LONG_SIGN_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = quad
                ? doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT)
                : singleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = quad
                ? doubleRegister(raw, 0, VM_EXTENSION_BIT)
                : singleRegister(raw, 0, VM_EXTENSION_BIT);
        if (quad && (vd & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonFusedMultiplyAddLong(subtract, quad, vd, vn, vm));
    }

    /// `VFML_scalar`/`VFMSL_scalar` (B13.20): `s` aqui é bit20 (posição DIFERENTE da forma
    /// vetorial). `quad=false`: `Rm` é um `S` de 4 bits (`S0`-`S15`, SEM bit de extensão —
    /// `%vfml_scalar_q0_rm = 0:3 5:1`, bits ESPALHADOS: `(bits[2:0] << 1) | bit5`) e `index` (1 bit,
    /// bit3) escolhe qual das 2 lanes f16 de `Rm`. `quad=true`: `Rm` é um `D` de 3 bits (`D0`-`D7` —
    /// `rm:3` literal em bits[2:0], SEM `%vm_dp`) e `index` (2 bits, `%vfml_scalar_q1_index = 5:1
    /// 3:1`, ESPALHADOS: `(bit5 << 1) | bit3`) escolhe qual das 4 lanes f16. `Vn` segue a mesma
    /// convenção `S`/`D` da forma vetorial — não há `Vm` "de verdade" aqui, só `Rm`.
    private DecodedInstruction decodeFusedMultiplyAddLongScalar(int raw, int address, Condition condition,
            boolean quad) {
        boolean subtract = ((raw >>> FUSED_MULTIPLY_ADD_LONG_SCALAR_SIGN_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = quad
                ? doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT)
                : singleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int rm;
        int index;
        if (quad) {
            rm = raw & FUSED_MULTIPLY_ADD_LONG_SCALAR_RM_MASK;
            index = (((raw >>> FUSED_MULTIPLY_ADD_LONG_SCALAR_DOUBLE_INDEX_HIGH_BIT) & 1) << 1)
                    | ((raw >>> FUSED_MULTIPLY_ADD_LONG_SCALAR_INDEX_BIT) & 1);
        } else {
            rm = ((raw & FUSED_MULTIPLY_ADD_LONG_SCALAR_RM_MASK) << 1)
                    | ((raw >>> FUSED_MULTIPLY_ADD_LONG_SCALAR_SINGLE_RM_EXTENSION_BIT) & 1);
            index = (raw >>> FUSED_MULTIPLY_ADD_LONG_SCALAR_INDEX_BIT) & 1;
        }
        if (quad && (vd & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonFusedMultiplyAddLongByElement(subtract, quad, vd, vn, rm, index));
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }

    /// Número de registrador `S` (`0`-`31`, `Vx:extensão` — ordem INVERTIDA de {@link
    /// #doubleRegister}, mesma convenção de `VfpDecoder#registerNumber` com `doublePrecision=false`).
    private static int singleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> nibbleShift) & NIBBLE_MASK) << 1) | ((raw >>> extensionBit) & 1);
    }
}
