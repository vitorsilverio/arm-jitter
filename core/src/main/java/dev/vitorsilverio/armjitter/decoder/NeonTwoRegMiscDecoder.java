package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica o sub-grupo **`size == 0b11`** (bits[21:20]) do frame "two regs, or three regs of
/// different lengths" do NEON/Advanced SIMD A32 (task B13.12) — especificamente o layout
/// "2-reg-misc" dentro dele: `VREV64`/`VREV32`/`VREV16` (reversão), `VPADDL`/`VPADAL` (pareamento
/// largo, `S`/`U`), `VCLS`/`VCLZ`/`VCNT`/`VMVN`, `VQABS`/`VQNEG`, as 5 comparações-com-zero
/// inteiras e as 5 FP, `VABS`/`VNEG` (inteiro e FP), `VMOVN`/`VQMOVUN`/`VQMOVN_S`/`VQMOVN_U`,
/// `VSHLL` (deslocamento fixo por `esize`) e `VRECPE`/`VRSQRTE` (inteiro e FP). Oráculo: QEMU
/// `target/arm/tcg/neon-dp.decode` (comentário "2-reg-misc grouping") + `translate-neon.c`
/// (`do_2misc_vec`/`do_2misc`/`do_vmovn`/`trans_VSHLL`); ARM DDI 0406C A7.4.6.
///
/// **Layout PRÓPRIO**, diferente de tudo em B13.4-B13.11: `size` = bits[19:18] (`esz`, NÃO
/// bits[21:20] — esses ficam fixos em `11` para chegar até aqui), `opc1` = bits[17:16], `opc2` =
/// bits[10:7], `q` = bit6. Frame: `1111 001 11 . 11 size:2 opc1:2 Vd:4 0 opc2:4 q M 0 Vm:4`
/// (bits[31:24]=`11110011`, bit23=`1`, bits[21:20]=`11`, bit11=`0`, bit4=`0` — os dois últimos
/// distinguem este layout de `VEXT`(bit24=`0`)/`VTBL`/`VDUP_scalar`(bit11=`1`), que vivem no MESMO
/// `size==0b11` mas fora do sub-layout "2-reg-misc").
///
/// **`size==0b11` hospeda QUATRO grupos** (B13.12-B13.15): este decoder reconhece só as 36 linhas
/// do Escopo da B13.12 e devolve **`null`** (não `unimplemented`) para o resto do espaço
/// "2-reg-misc" — conversões/arredondamento (`VRINT*`/`VCVT*`, B13.13) e cripto (`AESE`/`AESD`/
/// `AESMC`/`AESIMC`/`SHA1H`/`SHA1SU1`/`SHA256SU0`, B13.15) — para que essas tasks possam registrar
/// os próprios decoders depois. **Exceção deliberada à disciplina G8** (mesma de B13.7 para
/// `Vimm_1r` e de B13.10 para `size==0b11`): quando B13.13/B13.15 fecharem, o último a chegar deve
/// trocar o `null` por `unimplemented` neste frame.
///
/// A SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
/// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#unary}/{@code narrowUnary}/{@code fpUnary}),
/// RFC B13.2 D1 — migração completa em B13.12 (ver Javadoc de {@link AdvSimdUnaryOp}/
/// {@link AdvSimdFpUnaryOp}). `VSHLL` reaproveita 100% {@link IrOp.NeonShiftWidenImmediate}
/// (B13.8): desloca por `esize` FIXO (`8 << esz`), não por um imediato — mesmo mecanismo de `SHLL`
/// do A64 (B8.20).
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** (B13.22 fecha isso),
/// então sem a feature {@link #tryDecode} devolve `null` e o espaço cai no `UNIMPLEMENTED` de
/// `ArmDecoder#decodeUnconditional` (zero-diff, G3).
///
/// `FPSCR.QC` (tocado por `VQABS`/`VQNEG`/`VQMOVN*`) NÃO é modelado — paridade com o A64 e com
/// B13.5/B13.7/B13.8, task futura própria. F16 (`VCGT0_F`/... com `size==1`) e `size==3` (doubleword,
/// exceto onde documentado) são `UNIMPLEMENTED`, não `null` — pertencem ao escopo desta task
/// (encoding reconhecido, largura fora do suportado), não ao de outra.
public final class NeonTwoRegMiscDecoder implements DecoderExtension {
    /// Frame: bits[31:24]=`11110011` (prefixo NEON + bit24=1, exclui `VEXT`), bit23=1,
    /// bits[21:20]=`11` (size OUTER fixo), bit11=0 (exclui `VTBL`/`VDUP_scalar`, que têm bit11=1),
    /// bit4=0 (frame geral "two regs or 3 diff length" da B13.10/B13.11).
    private static final int FRAME_MASK = 0xFFB0_0810;
    private static final int FRAME_VALUE = 0xF3B0_0000;

    private static final int SIZE_SHIFT = 18;
    private static final int SIZE_MASK = 0x3;
    private static final int OPC1_SHIFT = 16;
    private static final int OPC1_MASK = 0x3;
    private static final int OPC2_SHIFT = 7;
    private static final int OPC2_MASK = 0xF;
    private static final int QUAD_BIT = 6;
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int NIBBLE_MASK = 0xF;
    /// `esz` do elemento word/F32 — único tamanho aceito pelas formas FP e por `VRECPE`/`VRSQRTE`
    /// inteiros.
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    /// `opc1` de `VSHLL`/`VMOVN`-família (também hospeda `VSWP`/`VTRN`/`VUZP`/`VZIP`, B13.14, e
    /// `VRINT*`/`VCVT_F16_F32`/..., B13.13 — este decoder só reconhece `opc2` `0100`-`0110`).
    private static final int OPC1_NARROW_SHLL = 0b10;
    private static final int OPC2_VSHLL = 0b0110;

    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — consulta {@link ArmFeature#ADVANCED_SIMD}.
    public NeonTwoRegMiscDecoder(ArmArchitecture architecture) {
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
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        int opc1 = (raw >>> OPC1_SHIFT) & OPC1_MASK;
        int opc2 = (raw >>> OPC2_SHIFT) & OPC2_MASK;
        boolean bit6 = ((raw >>> QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);

        if (opc1 == OPC1_NARROW_SHLL && opc2 == OPC2_VSHLL) {
            // `VSHLL` (2-reg-misc): desloca por `esize` FIXO — mesmo mecanismo de `SHLL` do A64
            // (B8.20), reaproveitando 100% `NeonShiftWidenImmediate`/`AdvSimdShiftWidenOp.USHLL`
            // (`widenfns` do QEMU só tem 3 entradas: `size` 0-2, `size==3` é UNDEFINED).
            if (size == ESZ_DOUBLEWORD || (vd & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonShiftWidenImmediate(AdvSimdShiftWidenOp.USHLL, size, 8 << size, vd, vm));
        }

        AdvSimdNarrowUnaryOp narrowOp = narrowUnaryOperation(opc1, opc2, bit6);
        if (narrowOp != null) {
            // `do_vmovn`: só `size` 0-2 (o array `narrowfn` do QEMU tem 3 entradas); `Vm` (fonte
            // `Q`) tem que ser par.
            if (size == ESZ_DOUBLEWORD || (vm & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonNarrowUnary(narrowOp, size, vd, vm));
        }

        boolean quad = bit6;
        AdvSimdFpUnaryOp fpOp = fpUnaryOperation(opc1, opc2);
        if (fpOp != null) {
            // `DO_2MISC_FP_VEC`: só `size==2` (F32) sem `FEAT_FP16` — `size==1` é F16, fora de
            // escopo (task futura irmã da B19.5); `size` `0`/`3` são reservados.
            if (size != ESZ_WORD) {
                return unimplemented(address, raw, condition);
            }
            if (quad && ((vd | vm) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonFpUnary(fpOp, quad, vd, vm));
        }

        AdvSimdUnaryOp intOp = integerUnaryOperation(opc1, opc2);
        if (intOp != null) {
            if (!validIntegerEsz(intOp, size)) {
                return unimplemented(address, raw, condition);
            }
            if (quad && ((vd | vm) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonUnary(intOp, quad, size, vd, vm));
        }

        // Resto do sub-grupo `size==0b11` "2-reg-misc": conversões/arredondamento (B13.13) e
        // cripto (B13.15) — `null` para não roubar o espaço delas (G8 não se aplica aqui, ver
        // Javadoc da classe).
        return null;
    }

    /// `(opc1, opc2)` → operação INTEIRA de {@link AdvSimdUnaryOp} — `opc1=00` (reversão/
    /// pareamento largo/`CLS`/`CLZ`/`CNT`/`NOT`/saturantes unárias) e o subconjunto INTEIRO de
    /// `opc1=01` (comparações-com-zero/`ABS`/`NEG`) e `opc1=11` (`URECPE`/`URSQRTE`). `null` para
    /// o resto (cripto/FP/conversões/permuta/`VSHLL`/`VMOVN`, tratados antes ou em outra task).
    private static AdvSimdUnaryOp integerUnaryOperation(int opc1, int opc2) {
        return switch (opc1) {
            case 0b00 -> switch (opc2) {
                case 0b0000 -> AdvSimdUnaryOp.REV64;
                case 0b0001 -> AdvSimdUnaryOp.REV32;
                case 0b0010 -> AdvSimdUnaryOp.REV16;
                case 0b0100 -> AdvSimdUnaryOp.SADDLP;
                case 0b0101 -> AdvSimdUnaryOp.UADDLP;
                // `0110`/`0111`: `AESE`/`AESD`/`AESMC`/`AESIMC` — B13.15.
                case 0b1000 -> AdvSimdUnaryOp.CLS;
                case 0b1001 -> AdvSimdUnaryOp.CLZ;
                case 0b1010 -> AdvSimdUnaryOp.CNT;
                case 0b1011 -> AdvSimdUnaryOp.NOT;
                case 0b1100 -> AdvSimdUnaryOp.SADALP;
                case 0b1101 -> AdvSimdUnaryOp.UADALP;
                case 0b1110 -> AdvSimdUnaryOp.SQABS;
                case 0b1111 -> AdvSimdUnaryOp.SQNEG;
                default -> null;
            };
            case 0b01 -> switch (opc2) {
                case 0b0000 -> AdvSimdUnaryOp.CMGT0;
                case 0b0001 -> AdvSimdUnaryOp.CMGE0;
                case 0b0010 -> AdvSimdUnaryOp.CMEQ0;
                case 0b0011 -> AdvSimdUnaryOp.CMLE0;
                case 0b0100 -> AdvSimdUnaryOp.CMLT0;
                // `0101`: `SHA1H` — B13.15.
                case 0b0110 -> AdvSimdUnaryOp.ABS;
                case 0b0111 -> AdvSimdUnaryOp.NEG;
                // `1000`-`1111` (exceto `1101`, reservado): formas FP — {@link #fpUnaryOperation}.
                default -> null;
            };
            case 0b11 -> switch (opc2) {
                case 0b1000 -> AdvSimdUnaryOp.URECPE;
                case 0b1001 -> AdvSimdUnaryOp.URSQRTE;
                // `0000`-`0111`: `VCVTA/N/P/M{S,U}` — B13.13. `1010`-`1111`: `VRECPE_F`/`VRSQRTE_F`
                // ({@link #fpUnaryOperation}) e `VCVT_{FS,FU,SF,UF}` — B13.13.
                default -> null;
            };
            // `opc1=10`: `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14), `VMOVN`-família/`VSHLL` (tratados
            // antes de chegar aqui) e `VRINT*`/`VCVT_F16_F32`/`SHA1SU1`/`SHA256SU0` (B13.13/B13.15).
            default -> null;
        };
    }

    /// `(opc1, opc2)` → operação FP de {@link AdvSimdFpUnaryOp} — `opc1=01` (comparações-com-zero/
    /// `ABS`/`NEG`) e `opc1=11` (`RECPE`/`RSQRTE`). `null` para o resto.
    private static AdvSimdFpUnaryOp fpUnaryOperation(int opc1, int opc2) {
        if (opc1 == 0b01) {
            return switch (opc2) {
                case 0b1000 -> AdvSimdFpUnaryOp.CMGT0;
                case 0b1001 -> AdvSimdFpUnaryOp.CMGE0;
                case 0b1010 -> AdvSimdFpUnaryOp.CMEQ0;
                case 0b1011 -> AdvSimdFpUnaryOp.CMLE0;
                case 0b1100 -> AdvSimdFpUnaryOp.CMLT0;
                // `1101`: reservado (nenhuma linha do `.decode` real o preenche).
                case 0b1110 -> AdvSimdFpUnaryOp.ABS;
                case 0b1111 -> AdvSimdFpUnaryOp.NEG;
                default -> null;
            };
        }
        if (opc1 == 0b11) {
            return switch (opc2) {
                case 0b1010 -> AdvSimdFpUnaryOp.RECPE;
                case 0b1011 -> AdvSimdFpUnaryOp.RSQRTE;
                default -> null;
            };
        }
        return null;
    }

    /// `(opc1, opc2, bit6)` → operação de {@link AdvSimdNarrowUnaryOp} (`VMOVN`/`VQMOVUN`/
    /// `VQMOVN_S`/`VQMOVN_U`) — `opc1=10`, `opc2` `0100`/`0101`; `bit6` aqui NÃO é `quad` (esta
    /// família não tem forma `Q`, o encoding real força `q=0`), é o discriminador `U`-como-bit
    /// entre a forma não-saturante/saturante-assinada (`bit6=0`) e a saturante-de-sinal-oposto
    /// (`bit6=1`).
    private static AdvSimdNarrowUnaryOp narrowUnaryOperation(int opc1, int opc2, boolean bit6) {
        if (opc1 != OPC1_NARROW_SHLL) {
            return null;
        }
        return switch (opc2) {
            case 0b0100 -> bit6 ? AdvSimdNarrowUnaryOp.SQXTUN : AdvSimdNarrowUnaryOp.XTN;
            case 0b0101 -> bit6 ? AdvSimdNarrowUnaryOp.UQXTN : AdvSimdNarrowUnaryOp.SQXTN;
            default -> null;
        };
    }

    /// `esz` (`size`) aceito por cada {@link AdvSimdUnaryOp} desta task — conferido contra
    /// `translate-neon.c`: `REV64` `0`-`2` (`do_2misc_vec` genérico), `REV32` `0`-`1`, `REV16`/
    /// `CNT`/`NOT` só `0`, `URECPE`/`URSQRTE` só `2` (word); o resto (`SADDLP`/`UADDLP`/`SADALP`/
    /// `UADALP`/`CLS`/`CLZ`/`SQABS`/`SQNEG`/as 5 comparações/`ABS`/`NEG`) aceita `0`-`2`
    /// (`do_2misc_vec` genérico ou `do_2misc` com array de 3 entradas — `size==3` sempre inválido).
    private static boolean validIntegerEsz(AdvSimdUnaryOp op, int esz) {
        return switch (op) {
            case REV64 -> esz <= ESZ_WORD;
            case REV32 -> esz <= 1;
            case REV16, CNT, NOT -> esz == 0;
            case URECPE, URSQRTE -> esz == ESZ_WORD;
            default -> esz <= ESZ_WORD;
        };
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }
}
