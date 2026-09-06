package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoAesOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp;
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
/// **`size==0b11` hospeda QUATRO grupos** (B13.12-B13.15). Este decoder reconhece as 36 linhas do
/// Escopo da B13.12 **e**, desde a B13.15, as 7 de cripto (`AESE`/`AESD`/`AESMC`/`AESIMC`/`SHA1H`/
/// `SHA1SU1`/`SHA256SU0`, gate PRÓPRIO {@link ArmFeature#CRYPTO} — ver
/// {@link #cryptoAesOperation}/{@link #cryptoShaOperation}); ainda devolve **`null`** (não
/// `unimplemented`) para o que falta — conversões/arredondamento (`VRINT*`/`VCVT*`, B13.13, ainda
/// não fechada nesta sessão) — para essa task poder registrar o próprio decoder depois.
/// **Exceção deliberada à disciplina G8** (mesma de B13.7 para `Vimm_1r` e de B13.10 para
/// `size==0b11`): quando B13.13 fechar (ÚLTIMO grupo pendente do sub-espaço), o `null` residual
/// deve virar `unimplemented` neste frame — dívida que a B13.15 NÃO conseguiu pagar (B13.13 segue
/// aberta), ver `## Resultado` da B13.15.
///
/// A SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
/// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#unary}/{@code narrowUnary}/{@code fpUnary} e,
/// desde B13.15, {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#aes}/
/// {@code shaTwoRegister}), RFC B13.2 D1 — migração completa em B13.12/B13.15 (ver Javadoc de
/// {@link AdvSimdUnaryOp}/{@link AdvSimdFpUnaryOp}/{@link AdvSimdCryptoAesOp}/
/// {@link AdvSimdCryptoShaOp}). `VSHLL` reaproveita 100% {@link IrOp.NeonShiftWidenImmediate}
/// (B13.8): desloca por `esize` FIXO (`8 << esz`), não por um imediato — mesmo mecanismo de `SHLL`
/// do A64 (B8.20).
///
/// Gate: {@link ArmFeature#ADVANCED_SIMD} (checado no topo de {@link #tryDecode}, comum a todo o
/// decoder) **e**, só para as 7 de cripto, {@link ArmFeature#CRYPTO} À PARTE — um núcleo pode ter
/// NEON sem a extensão cripto opcional (Armadilha 3 da B13.15), então essas 7 linhas ficam
/// `UNIMPLEMENTED` explícito (não `null`) quando reconhecidas sem `CRYPTO`. **Nenhum preset declara
/// nenhuma das duas** (B13.22 fecha isso), então sem `ADVANCED_SIMD` {@link #tryDecode} devolve
/// `null` inteiro e o espaço cai no `UNIMPLEMENTED` de `ArmDecoder#decodeUnconditional` (zero-diff,
/// G3).
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
    /// `opc1` de `VSHLL`/`VMOVN`-família **e** `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14, `opc2` `0000`-
    /// `0011`, reconhecidos por este decoder) **e** `VRINT*`/`VCVT_F16_F32`/... (B13.13, `opc2`
    /// `1000`-`1111`, `null` — não reconhecidos aqui).
    private static final int OPC1_NARROW_SHLL = 0b10;
    private static final int OPC2_VSHLL = 0b0110;
    /// `opc2` de `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14) — MESMO `opc1` de `VSHLL`/`VMOVN`-família.
    private static final int OPC2_VSWP = 0b0000;
    private static final int OPC2_VTRN = 0b0001;
    private static final int OPC2_VUZP = 0b0010;
    private static final int OPC2_VZIP_MAX = 0b0011;

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

        if (opc1 == OPC1_NARROW_SHLL && opc2 <= OPC2_VZIP_MAX) {
            // `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14, MESMO `opc1=0b10` de `VSHLL`/`VMOVN`-família,
            // discriminados por `opc2` 0-3) — sem equivalente A64 (ver Javadoc de
            // `AdvSimdSwapPermuteOp`), `Vd`/`Vm` são fonte E destino.
            AdvSimdSwapPermuteOp swapOp = switch (opc2) {
                case OPC2_VSWP -> AdvSimdSwapPermuteOp.SWAP;
                case OPC2_VTRN -> AdvSimdSwapPermuteOp.TRN;
                case OPC2_VUZP -> AdvSimdSwapPermuteOp.UZP;
                default -> AdvSimdSwapPermuteOp.ZIP;
            };
            // `VSWP` real sempre encodifica `size=00` (o campo é ignorado pela operação, mas
            // qualquer outro valor é combinação reservada — confirmado contra `arm-none-eabi-as`
            // real: `vswp` sem sufixo de tamanho só produz `size=00`). `VTRN`/`VUZP`/`VZIP` aceitam
            // `size` 0-2 (`size==3` reservado, mesmo padrão do resto do sub-grupo).
            boolean sizeValid = swapOp == AdvSimdSwapPermuteOp.SWAP ? size == 0 : size != ESZ_DOUBLEWORD;
            if (!sizeValid || (bit6 && ((vd | vm) & 1) != 0)) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonSwapPermute(swapOp, bit6, size, vd, vm));
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

        // `AESE`/`AESD`/`AESMC`/`AESIMC` (B13.15) — `size` fixo em `0b00` (QEMU `DO_2M_CRYPTO`:
        // `a->size != 0` -> UNDEFINED), `Q` sempre `1` (confirmado via `arm-linux-gnueabihf-as -march
        // =armv8-a+crypto`: `aese.8 q0,q1` -> `0xf3b00302`, nenhum sufixo de tamanho diferente de
        // `.8` existe no assembler real).
        AdvSimdCryptoAesOp aesOp = cryptoAesOperation(opc1, opc2, bit6);
        if (aesOp != null) {
            if (!architecture.has(ArmFeature.CRYPTO) || size != 0 || ((vd | vm) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonCryptoAes(aesOp, vd, vm));
        }

        // `SHA1H`/`SHA1SU1`/`SHA256SU0` (B13.15) — `size` fixo em `0b10` (QEMU `DO_2M_CRYPTO`:
        // `a->size != 2` -> UNDEFINED), `Q` sempre `1` (confirmado via `arm-linux-gnueabihf-as`:
        // `sha1h.32 q0,q1` -> `0xf3b902c2`, `sha1su1.32 q0,q1` -> `0xf3ba0382`, `sha256su0.32 q0,q1`
        // -> `0xf3ba03c2`).
        AdvSimdCryptoShaOp shaOp = cryptoShaOperation(opc1, opc2, bit6);
        if (shaOp != null) {
            if (!architecture.has(ArmFeature.CRYPTO) || size != ESZ_WORD || ((vd | vm) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonCryptoSha(shaOp, vd, vm));
        }

        // Resto do sub-grupo `size==0b11` "2-reg-misc": conversões/arredondamento (B13.13, ainda
        // aberta) — `null` para não roubar o espaço dela (G8 não se aplica aqui, ver Javadoc da
        // classe).
        return null;
    }

    /// `(opc1, opc2, bit6)` → operação de {@link AdvSimdCryptoAesOp} — `opc1=00`, `opc2` `0110`
    /// (`AESE`/`bit6=0`, `AESD`/`bit6=1`) ou `0111` (`AESMC`/`bit6=0`, `AESIMC`/`bit6=1`), confirmado
    /// bit a bit contra o assembler real (ver comentário do chamador). `null` para o resto.
    private static AdvSimdCryptoAesOp cryptoAesOperation(int opc1, int opc2, boolean bit6) {
        if (opc1 != 0b00) {
            return null;
        }
        return switch (opc2) {
            case 0b0110 -> bit6 ? AdvSimdCryptoAesOp.AESD : AdvSimdCryptoAesOp.AESE;
            case 0b0111 -> bit6 ? AdvSimdCryptoAesOp.AESIMC : AdvSimdCryptoAesOp.AESMC;
            default -> null;
        };
    }

    /// `(opc1, opc2, bit6)` → operação de {@link AdvSimdCryptoShaOp} — `SHA1H` vive em
    /// `opc1=01`/`opc2=0101`/`bit6=1` (MESMO opc1 das comparações-com-zero inteiras, mas `opc2`
    /// nunca colide: `0000`-`0100` são as 5 comparações/`ABS`/`NEG`, `0101` é só `SHA1H`);
    /// `SHA1SU1`/`SHA256SU0` vivem em `opc1=10`/`opc2=0111` (MESMO `opc1` de `VSWP`-família/`VSHLL`/
    /// `VMOVN`-família, discriminados ANTES de chegar aqui — nenhum deles usa `opc2=0111`),
    /// discriminados entre si por `bit6` (`0`=`SHA1SU1`, `1`=`SHA256SU0`). `null` para o resto.
    private static AdvSimdCryptoShaOp cryptoShaOperation(int opc1, int opc2, boolean bit6) {
        if (opc1 == 0b01 && opc2 == 0b0101 && bit6) {
            return AdvSimdCryptoShaOp.SHA1H;
        }
        if (opc1 == 0b10 && opc2 == 0b0111) {
            return bit6 ? AdvSimdCryptoShaOp.SHA256SU0 : AdvSimdCryptoShaOp.SHA1SU1;
        }
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
                // `0110`/`0111`: `AESE`/`AESD`/`AESMC`/`AESIMC` — tratados ANTES de chegar aqui,
                // ver {@link #cryptoAesOperation} (B13.15).
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
                // `0101`: `SHA1H` — tratado ANTES de chegar aqui, ver {@link #cryptoShaOperation}
                // (B13.15).
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
            // `opc1=10`: `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14), `VMOVN`-família/`VSHLL`,
            // `SHA1SU1`/`SHA256SU0` (B13.15) — todos tratados ANTES de chegar aqui — e
            // `VRINT*`/`VCVT_F16_F32` (B13.13, ainda aberta).
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
