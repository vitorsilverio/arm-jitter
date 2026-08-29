package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica o espaço de coprocessador VFP (CP10 single-precision / CP11 double-precision) do
/// encoding ARM (B3.5). Oráculo: QEMU `target/arm/tcg/vfp.decode` — cada bloco de bits abaixo cita
/// o nome QEMU correspondente.
///
/// O espaço VFP inteiro (CP10/CP11) é `bits[27:24] ∈ {1100,1101,1110}` (o mesmo espaço genérico de
/// coprocessador `LDC`/`STC`/`CDP`/`MCR`/`MRC` do ARM clássico) com `bits[11:8] ∈ {1010,1011}` (o
/// campo de coprocessador clássico, aqui reaproveitado pelo VFP como seletor de precisão: `1010`
/// single, `1011` double). `bits[11:8] == 1001` (meia precisão) NÃO passa por
/// {@link #isVfpCoprocessorSpace} — mas a B22.2 acrescentou o reconhecimento explícito de
/// `VMOV_half` ({@link #isVmovHalfEncoding}), checado ANTES daquele gate: sem
/// {@link ArmFeature#HALF_PRECISION_FP} o encoding é recusado com `UNIMPLEMENTED` (nunca mais um
/// `MCR`/`MRC` genérico espúrio para o {@link CoprocessorDecoder} — a violação de G8 que a B22.2
/// fechou, o único `⚠️` que a tabela de cobertura tinha). Como {@link #isVfpCoprocessorSpace} e
/// {@link #isVmovHalfEncoding} só olham `bits[27:24]`/`bits[11:8]`/campos internos (nunca
/// `bits[31:28]`), a MESMA lógica decodifica tanto o `raw`
/// ARM (`bits[31:28]`=condição real) quanto o `raw32` Thumb-2 (`bits[31:28]` sempre `1110`, prefixo
/// fixo do hw1 — QEMU confirma layout idêntico em `t32.decode`, seção coprocessor) — por isso esta
/// classe sempre marca {@link InstructionSet#ARM}; {@link Thumb2VfpDecoder} é a casca fina que a
/// reusa para Thumb-2, mesmo padrão de {@link CoprocessorDecoder}/{@link Thumb2CoprocessorDecoder}.
///
/// Gate único: {@link ArmFeature#VFPV2}. Sem a feature, {@link #tryDecode} devolve `null` e
/// {@link #claimsEncodingSpace} devolve `false` — o espaço volta a ser do {@link CoprocessorDecoder}
/// (MCR/MRC genérico) ou UNDEFINED, exatamente como antes desta task (G3).
///
/// Ordem de registro: esta classe precisa vir ANTES de {@link CoprocessorDecoder} na lista de
/// extensões — `VMOV_single`/`VMSR_VMRS` e `VMOV_64_dp` usam o MESMO formato de bits (`bit4=1`) que
/// `MRC`/`MRRC` genérico; sem a ordem correta, `CoprocessorDecoder` capturaria essas instruções
/// primeiro e as decodificaria (erradamente) como `IrOp.Coprocessor` para o `CoprocessorBus`.
public final class VfpDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar {@link ArmFeature#VFPV2}.
    public VfpDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    // ── Campos de registrador VFP (vfp.decode: %vd_dp/%vd_sp/%vn_dp/%vn_sp/%vm_dp/%vm_sp) ──
    // Single: combinado = (nibble<<1)|extensão ("Vd:D"). Double: combinado = (extensão<<4)|nibble
    // ("D:Vd") — a ordem INVERTE entre os dois, erro clássico nº 1 do VFP (ver Armadilhas da task).
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VN_NIBBLE_SHIFT = 16;
    private static final int VN_EXTENSION_BIT = 7;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int NIBBLE_MASK = 0xF;

    // ── Gate do espaço (todo VFP, independente do sub-encoding) ──
    private static final int COPROCESSOR_SPACE_SHIFT = 24;
    private static final int COPROCESSOR_SPACE_MASK = 0xF;
    private static final int SIZE_FIELD_SHIFT = 8;
    private static final int SIZE_FIELD_MASK = 0xF;
    private static final int SIZE_SINGLE = 0xA;
    private static final int SIZE_DOUBLE = 0xB;
    private static final int SINGLE_REGISTER_COUNT = 32;
    private static final int BIT16_MASK = 1 << 16;
    private static final int BIT18_MASK = 1 << 18;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!claimsThisDecoder(raw)) {
            return null;
        }
        if (isVmovHalfEncoding(raw)) {
            return decodeVmovHalf(raw, address, condition);
        }
        if (!isVfpCoprocessorSpace(raw)) {
            return null;
        }
        int bits2724 = (raw >>> COPROCESSOR_SPACE_SHIFT) & COPROCESSOR_SPACE_MASK;
        DecodedInstruction decoded = switch (bits2724) {
            case 0xE -> decodeCdpOrMrcSpace(raw, address, condition);
            case 0xD -> decodeLoadStoreOrLoadStoreMultipleDb(raw, address, condition);
            case 0xC -> decodeLoadStoreMultipleIaOrCorePairTransfer(raw, address, condition);
            default -> null;
        };
        // Todo CP10/CP11 não reconhecido acima é UNDEFINED explícito, não `null` — um futuro
        // NEON/VFPv3-completo não deve cair no `CoprocessorBus` (ver claimsEncodingSpace/javadoc).
        return decoded != null ? decoded : DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    @Override
    public boolean claimsEncodingSpace(int raw) {
        return claimsThisDecoder(raw);
    }

    private boolean claimsThisDecoder(int raw) {
        if (isVmovHalfEncoding(raw)) {
            // B22.2: `VMOV_half` mora fora do gate `isVfpCoprocessorSpace` (`bits[11:8]=1001`).
            // Reivindicar mesmo SEM `HALF_PRECISION_FP` (recusa explícita, G8) — basta o núcleo
            // ter VFP. `|| HALF_PRECISION_FP` cobre um preset hipotético de meia precisão sem VFPv2.
            return architecture.has(ArmFeature.VFPV2) || architecture.has(ArmFeature.HALF_PRECISION_FP);
        }
        return architecture.has(ArmFeature.VFPV2) && isVfpCoprocessorSpace(raw);
    }

    private static boolean isVfpCoprocessorSpace(int raw) {
        int bits2724 = (raw >>> COPROCESSOR_SPACE_SHIFT) & COPROCESSOR_SPACE_MASK;
        boolean coprocessorSpace = bits2724 == 0xC || bits2724 == 0xD || bits2724 == 0xE;
        int size = (raw >>> SIZE_FIELD_SHIFT) & SIZE_FIELD_MASK;
        return coprocessorSpace && (size == SIZE_SINGLE || size == SIZE_DOUBLE);
    }

    // ── B22.2: VMOV_half (`---- 1110 000 l:1 .... rt:4 1001 . 001 0000`, vn=%vn_sp) ──
    // Oráculo: `target/isa-decode/vfp.decode`. Transferência CRUA de 16 bits entre `Rt` e `Sn[15:0]`
    // (não interpreta o float). Exige a extensão de meia precisão (VFPv3-HP / `FEAT_FP16`); nenhum
    // preset a declara — o encoding é reconhecido só para ser RECUSADO (G8), matando o único `⚠️`
    // do projeto (a tabela media MPCore/v7-A decodificando isto como `MCR`/`MRC` genérico para cp9).
    private static final int VMOV_HALF_MASK = 0x0FE0_0F7F;
    private static final int VMOV_HALF_VALUE = 0x0E00_0910;

    private static boolean isVmovHalfEncoding(int raw) {
        return (raw & VMOV_HALF_MASK) == VMOV_HALF_VALUE;
    }

    /// `VMOV_half`: `l=1` → `Rt = ZeroExtend(Sn[15:0], 32)`; `l=0` → `Sn[15:0] = Rt[15:0]` com
    /// `Sn[31:16]` inalterado. Reaproveita {@link InstructionKind#VFP_CORE_TRANSFER} (mesma
    /// transferência crua de `VMOV_single`, só que 16 bits) marcando `immediate=1` — o
    /// {@link dev.vitorsilverio.armjitter.ir.StandardIrBuilder} traduz isso para
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.VfpCoreTransfer#halfWidth}. Sem
    /// {@link ArmFeature#HALF_PRECISION_FP} → `UNIMPLEMENTED` explícito.
    private DecodedInstruction decodeVmovHalf(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.HALF_PRECISION_FP)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
        }
        boolean load = (raw & BIT20_MASK) != 0; // l=1: Sn[15:0] -> Rt; l=0: Rt[15:0] -> Sn[15:0].
        int vn = registerNumber(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT, false);
        int rt = (raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_CORE_TRANSFER,
                rt, vn, -1, 1, false, false, load);
    }

    // ── bits[27:24]=1110: CDP-shape (bit4=0, aritmética/imediato/2-operando/compare/convert) ──
    // ou MRC/MRRC-shape (bit4=1, VMOV_single/VMSR_VMRS). ──

    private static final int BIT4_MASK = 1 << 4;
    private static final int BIT6_MASK = 1 << 6;
    private static final int BIT7_MASK = 1 << 7;
    private static final int BIT20_MASK = 1 << 20;
    private static final int BIT21_MASK = 1 << 21;
    private static final int BIT23_MASK = 1 << 23;

    private DecodedInstruction decodeCdpOrMrcSpace(int raw, int address, Condition condition) {
        if ((raw & BIT4_MASK) == 0) {
            return decodeDataProcessing(raw, address, condition);
        }
        return decodeCoreTransferOrSystem(raw, address, condition);
    }

    // ── Aritmética de 3 registradores (VMLA/VMLS/VNMLA/VNMLS/VMUL/VNMUL/VADD/VSUB/VDIV) e a
    // família de imediato/2-operando/compare/convert (op1==0b111, vfp.decode bits[23,21:20]) ──

    private DecodedInstruction decodeDataProcessing(int raw, int address, Condition condition) {
        int op1 = ((raw & BIT23_MASK) != 0 ? 0b100 : 0)
                | ((raw & BIT21_MASK) != 0 ? 0b010 : 0)
                | ((raw & BIT20_MASK) != 0 ? 0b001 : 0);
        boolean doublePrecision = size(raw) == SIZE_DOUBLE;
        if (op1 == 0b111) {
            // Família de imediato/2-operando/compare/convert: Vn NÃO é um registrador aqui (vira
            // opc2 ou o nibble alto do imediato) — cada sub-forma tem sua própria combinação de
            // precisão de Vd/Vm (a conversão de precisão e a de inteiro usam precisões DIFERENTES
            // nos dois lados — ver Armadilhas/vfp.decode `@vfp_dm_ds`/`@vfp_dm_sd`), então é
            // computado por {@link #decodeImmediateOrTwoOperandFamily}, não aqui.
            return decodeImmediateOrTwoOperandFamily(raw, address, condition, doublePrecision);
        }
        // Demais formas de 3 registradores: Vn/Vd/Vm sempre com a MESMA precisão (`@vfp_dnm_s`/
        // `@vfp_dnm_d`).
        int vn = registerNumber(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT, doublePrecision);
        int vd = vd(raw, doublePrecision);
        int vm = vm(raw, doublePrecision);
        if (!validDoubleRegister(vn, doublePrecision) || !validDoubleRegister(vd, doublePrecision)
                || !validDoubleRegister(vm, doublePrecision)) {
            return null;
        }
        boolean bit6 = (raw & BIT6_MASK) != 0;
        return switch (op1) {
            // VMLA_{sp,dp} (bit6=0) / VMLS_{sp,dp} (bit6=1).
            case 0b000 -> vfpAlu(bit6 ? IrOp.VfpOperation.MLS : IrOp.VfpOperation.MLA,
                    doublePrecision, vd, vn, vm, address, raw, condition);
            // VMUL_{sp,dp} (bit6=0) / VNMUL_{sp,dp} (bit6=1).
            case 0b010 -> vfpAlu(bit6 ? IrOp.VfpOperation.NMUL : IrOp.VfpOperation.MUL,
                    doublePrecision, vd, vn, vm, address, raw, condition);
            // VADD_{sp,dp} (bit6=0) / VSUB_{sp,dp} (bit6=1).
            case 0b011 -> vfpAlu(bit6 ? IrOp.VfpOperation.SUB : IrOp.VfpOperation.ADD,
                    doublePrecision, vd, vn, vm, address, raw, condition);
            // VDIV_{sp,dp}: sem forma "bit6=1" (fora de escopo se bit6 estiver setado).
            case 0b100 -> bit6 ? null
                    : vfpAlu(IrOp.VfpOperation.DIV, doublePrecision, vd, vn, vm, address, raw, condition);
            // VNMLS_{sp,dp} (bit6=0) / VNMLA_{sp,dp} (bit6=1) — ordem INVERTIDA em relação a
            // VMLA/VMLS (ARM ARM A8.8.337: `op` seleciona VNMLA quando 1). Conferido contra
            // `vfp.decode` do QEMU e contra o encoding real emitido pelo gcc do devkitARM
            // (`ee171b0c` = `vnmls.f64 d1, d7, d12`, achado no `textured_cube` dos exemplos 3DS).
            case 0b001 -> vfpAlu(bit6 ? IrOp.VfpOperation.NMLA : IrOp.VfpOperation.NMLS,
                    doublePrecision, vd, vn, vm, address, raw, condition);
            // VFMA_{sp,dp} (bit6=0) / VFMS_{sp,dp} (bit6=1) — FUNDIDAS, VFPv4 (B9.6). MESMO campo
            // bit6 dos vizinhos MLA/MLS/NMLA/NMLS acima (confirmado contra `vfp.decode` real do
            // QEMU: `MAKE_ONE_VFM_TRANS_FN(VFMA,...,false,false)`/`(VFMS,...,true,false)` — o
            // "neg_n" de VFMS é o mesmo bit6 que já seleciona "produto negado" na família não
            // fundida). Gate adicional (além de VFPV2, já checado por quem chama este método):
            // sem VFP_FUSED_MULTIPLY_ACCUMULATE, cai em `null` → UNDEFINED explícito (G8) — este
            // encoding nunca foi reivindicado por nenhum outro dispatch antes da B9.6.
            case 0b110 -> !architecture.has(ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE) ? null
                    : vfpAlu(bit6 ? IrOp.VfpOperation.FMS : IrOp.VfpOperation.FMA,
                    doublePrecision, vd, vn, vm, address, raw, condition);
            // VFNMS_{sp,dp} (bit6=0) / VFNMA_{sp,dp} (bit6=1) — FUNDIDAS, VFPv4 (B9.6). Mesma
            // ordem invertida de VNMLS/VNMLA (ver o comentário do case 0b001 acima) confirmada
            // contra `MAKE_ONE_VFM_TRANS_FN(VFNMS,...,false,true)`/`(VFNMA,...,true,true)`.
            case 0b101 -> !architecture.has(ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE) ? null
                    : vfpAlu(bit6 ? IrOp.VfpOperation.FNMA : IrOp.VfpOperation.FNMS,
                    doublePrecision, vd, vn, vm, address, raw, condition);
            default -> null;
        };
    }

    /// `op1==0b111`: `bit6==0` é `VMOV_imm` (bits[19:16]/bits[3:0] carregam o imediato, não um Vn);
    /// `bit6==1` é a família de 2 operandos/compare/convert, selecionada por `bits[19:16]` (`opc2`).
    /// `doublePrecision` (do campo `size`) só se aplica DIRETAMENTE quando Vd/Vm compartilham a
    /// mesma precisão (`VMOV_imm`/`VMOV_reg`/`VABS`/`VNEG`/`VSQRT`/`VCMP`); as conversões
    /// (`opc2` 7/8/C/D) têm precisões diferentes em cada lado — resolvidas caso a caso abaixo.
    private DecodedInstruction decodeImmediateOrTwoOperandFamily(int raw, int address, Condition condition,
            boolean doublePrecision) {
        int vd = vd(raw, doublePrecision);
        if ((raw & BIT6_MASK) == 0) {
            if (!validDoubleRegister(vd, doublePrecision)) {
                return null;
            }
            int imm8 = ((raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK) << 4 | (raw & NIBBLE_MASK);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_MOVE_IMMEDIATE,
                    vd, -1, -1, imm8, true, false, false, 0, doublePrecision);
        }
        int opc2 = (raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK;
        boolean bit7 = (raw & BIT7_MASK) != 0;
        int vmSamePrecision = vm(raw, doublePrecision);
        if ((opc2 == 0x0 || opc2 == 0x1)
                && (!validDoubleRegister(vd, doublePrecision) || !validDoubleRegister(vmSamePrecision, doublePrecision))) {
            return null;
        }
        return switch (opc2) {
            // VMOV_reg (bit7=0) / VABS (bit7=1): Vd/Vm mesma precisão (`@vfp_dm_ss`/`@vfp_dm_dd`).
            case 0x0 -> vfpAlu(bit7 ? IrOp.VfpOperation.ABS : IrOp.VfpOperation.COPY,
                    doublePrecision, vd, -1, vmSamePrecision, address, raw, condition);
            // VNEG (bit7=0) / VSQRT (bit7=1): idem.
            case 0x1 -> vfpAlu(bit7 ? IrOp.VfpOperation.SQRT : IrOp.VfpOperation.NEG,
                    doublePrecision, vd, -1, vmSamePrecision, address, raw, condition);
            // VCMP_{sp,dp} (z=0, opc2=0b0100) / VCMP_{sp,dp} #0.0 (z=1, opc2=0b0101): Vd/Vm mesma
            // precisão.
            case 0x4, 0x5 -> {
                if (!validDoubleRegister(vd, doublePrecision) || !validDoubleRegister(vmSamePrecision, doublePrecision)) {
                    yield null;
                }
                boolean compareWithZero = opc2 == 0x5;
                boolean signalOnQuietNaN = bit7;
                yield new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_COMPARE,
                        vd, -1, compareWithZero ? -1 : vmSamePrecision,
                        (compareWithZero ? 1 : 0) | (signalOnQuietNaN ? 2 : 0), false, false, false, 0, doublePrecision);
            }
            // VCVT_sp/VCVT_dp (conversão de precisão simples<->dupla, `@vfp_dm_ds`/`@vfp_dm_sd`):
            // Vd e Vm têm precisões OPOSTAS — `size`(doublePrecision) descreve o lado DESTINO.
            // Exige bit7=1 (bit7=0 é VRINTX, fora de escopo).
            case 0x7 -> {
                if (!bit7) {
                    yield null;
                }
                // `size=1010` (`doublePrecision=false`) é `VCVT_sp` (`@vfp_dm_ds`: `vm=%vm_sp`
                // fonte simples, `vd=%vd_dp` destino dobro) = F32_TO_F64. `size=1011`
                // (`doublePrecision=true`) é `VCVT_dp` (`@vfp_dm_sd`: `vm=%vm_dp`, `vd=%vd_sp`) =
                // F64_TO_F32 — Vm usa a precisão de `size` diretamente, Vd usa o OPOSTO.
                int vmConvert = vm(raw, doublePrecision);
                int vdConvert = vd(raw, !doublePrecision);
                if (!validDoubleRegister(vdConvert, !doublePrecision) || !validDoubleRegister(vmConvert, doublePrecision)) {
                    yield null;
                }
                yield vfpConvert(doublePrecision ? IrOp.VfpConversion.F64_TO_F32 : IrOp.VfpConversion.F32_TO_F64,
                        vdConvert, vmConvert, address, raw, condition);
            }
            // VCVT_int_{sp,dp} (inteiro -> ponto flutuante): Vm é SEMPRE simples (fonte inteira de
            // 32 bits); `size`(doublePrecision) só descreve Vd. bit7 = sinal (1=signed,0=unsigned).
            case 0x8 -> {
                int vm = vm(raw, false);
                if (!validDoubleRegister(vd, doublePrecision)) {
                    yield null;
                }
                IrOp.VfpConversion conversion = bit7
                        ? (doublePrecision ? IrOp.VfpConversion.S32_TO_F64 : IrOp.VfpConversion.S32_TO_F32)
                        : (doublePrecision ? IrOp.VfpConversion.U32_TO_F64 : IrOp.VfpConversion.U32_TO_F32);
                yield vfpConvert(conversion, vd, vm, address, raw, condition);
            }
            // VCVT_{sp,dp}_int (ponto flutuante -> inteiro): Vd é SEMPRE simples (destino inteiro
            // de 32 bits); `size`(doublePrecision) descreve a fonte Vm. Exige bit7=rz=1 (bit7=0 é
            // VCVTR, que usaria FPSCR.RMode — fora de escopo). opc2 bit 0 = sinal.
            case 0xC, 0xD -> {
                if (!bit7) {
                    yield null;
                }
                int vmSource = vm(raw, doublePrecision);
                if (!validDoubleRegister(vmSource, doublePrecision)) {
                    yield null;
                }
                int vdSingle = vd(raw, false);
                boolean signedConvert = opc2 == 0xD;
                IrOp.VfpConversion conversion = signedConvert
                        ? (doublePrecision ? IrOp.VfpConversion.F64_TO_S32 : IrOp.VfpConversion.F32_TO_S32)
                        : (doublePrecision ? IrOp.VfpConversion.F64_TO_U32 : IrOp.VfpConversion.F32_TO_U32);
                yield vfpConvert(conversion, vdSingle, vmSource, address, raw, condition);
            }
            // VCVT_fix_{sp,dp} (B9.5, VFPv3, ARM DDI 0406C A8.8.397): `opc2` aqui vale
            // {0xA,0xB,0xE,0xF} = nibble `1_X_1_Y` — os bits X (19-16 relativo, real bit18) e Y
            // (bit16) NÃO são um seletor fixo como nos outros `case`s: são 2 dos 3 bits do campo
            // real `opc=op:u:sx` do encoding (`vfp.decode` `%vcvt_fix_op = 18:1 16:1 7:1`), o
            // terceiro (`sx`) é `bit7` (já extraído acima). `imm`=`%vm_sp` (MESMO layout de campo
            // que um número de registrador `S`, aqui reaproveitado como imediato de 5 bits — não é
            // um registrador). Conferido em `target/arm/tcg/vfp_helper.c` (`VFP_CONV_FIX*`) antes
            // de implementar: fixo→float sempre arredonda ao mais próximo; float→fixo sempre
            // trunca para zero e satura — resolvido no `StandardIrBuilder`, não aqui.
            case 0xA, 0xB, 0xE, 0xF -> {
                if (!validDoubleRegister(vd, doublePrecision)) {
                    yield null;
                }
                boolean toFixedPoint = (raw & BIT18_MASK) != 0; // `op`.
                boolean unsignedFixedPoint = (raw & BIT16_MASK) != 0; // `u`.
                boolean fixedPointIs32Bit = bit7; // `sx`.
                int imm = vm(raw, false); // `%vm_sp`: nibble(3:0)<<1 | bit5.
                int packed = imm << 3
                        | (fixedPointIs32Bit ? 0b100 : 0)
                        | (unsignedFixedPoint ? 0b010 : 0)
                        | (toFixedPoint ? 0b001 : 0);
                yield new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                        InstructionKind.VFP_CONVERT_FIXED, vd, -1, -1, packed, false, false, false, 0, doublePrecision);
            }
            default -> null; // VRINT*/VCVT_f16*/VJCVT/VCVT_b16_f32/VCVT_hp_int — fora de escopo.
        };
    }

    // ── bits[27:24]=1110, bit4=1: VMOV_single (FMRS/FMSR) ou VMSR_VMRS (FMRX/FMXR, só FPSCR) ──

    private static final int FPSCR_REGISTER_SELECTOR = 0x1;

    private DecodedInstruction decodeCoreTransferOrSystem(int raw, int address, Condition condition) {
        int bits2321 = (raw >>> 21) & 0x7;
        int size = size(raw);
        if (bits2321 == 0b000 && size == SIZE_SINGLE) {
            // VMOV_single: `---- 1110 000 l rt2space vn(4) rt(4) 1010 . 001 0000`.
            boolean load = (raw & BIT20_MASK) != 0; // l=1: Sn -> Rt (FMRS); l=0: Rt -> Sn (FMSR).
            int vn = registerNumber(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT, false);
            int rt = (raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_CORE_TRANSFER,
                    rt, vn, -1, 0, false, false, load);
        }
        if (bits2321 == 0b111 && size == SIZE_SINGLE) {
            // VMSR_VMRS: `---- 1110 111 l reg(4) rt(4) 1010 0001 0000` — só reg==0001 (FPSCR).
            int reg = (raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK;
            if (reg != FPSCR_REGISTER_SELECTOR) {
                return null; // FPSID/FPEXC/MVFR* — fora de escopo.
            }
            boolean read = (raw & BIT20_MASK) != 0; // l=1: VMRS (FPSCR -> destino).
            int rt = (raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_SYSTEM_TRANSFER,
                    rt, -1, -1, 0, false, false, read);
        }
        if (size == SIZE_DOUBLE) {
            return decodeScalarGpTransfer(raw, address, condition);
        }
        return null; // VDUP (forma NEON) — fora de escopo.
    }

    // ── bits[27:24]=1110, bit4=1, size=1011 (double): VMOV_to_gp/VMOV_from_gp (B9.5) ──

    private static final int SCALAR_SIZE_BIT22_MASK = 1 << 22;
    private static final int SCALAR_SIZE_BIT5_MASK = 1 << 5;

    /// `VMOV_to_gp`/`VMOV_from_gp` (ARM DDI 0406C A8.8.343/A8.8.344): decodifica só a forma
    /// "word" (elemento de 32 bits, `size=2` do encoding NEON — não confundir com o `size`
    /// genérico bits[11:8]=1011 deste `switch`, que é o MESMO para as 3 formas). As formas
    /// byte/halfword (`size=0/1`) são NEON de verdade (QEMU `translate-vfp.c`
    /// `trans_VMOV_to_gp`/`trans_VMOV_from_gp`: `insn_is_neon = size != MO_32`, gate
    /// `ARM_FEATURE_NEON`) — nenhum preset deste projeto tem NEON (0% de cobertura documentado),
    /// e o ARM11 MPCore real do 3DS também não tem; ficam em `docs/isa-nao-aplicavel.tsv`.
    /// A forma word é `aa32_fpsp_v2` genuína — mesma transferência de {@link #decodeCoreTransferOrSystem}
    /// (`VMOV_single`), só endereçada via lane de um `D` combinado em vez de um `S` direto —
    /// por isso reaproveita o MESMO {@link InstructionKind#VFP_CORE_TRANSFER} sem `Kind` novo.
    private DecodedInstruction decodeScalarGpTransfer(int raw, int address, Condition condition) {
        boolean byteForm = (raw & SCALAR_SIZE_BIT22_MASK) != 0;
        boolean halfwordForm = !byteForm && (raw & SCALAR_SIZE_BIT5_MASK) != 0;
        if (byteForm || halfwordForm) {
            return null; // NEON-gated — fora de escopo (B9.5), ver `docs/isa-nao-aplicavel.tsv`.
        }
        if ((raw & BIT6_MASK) != 0) {
            return null; // combinação reservada, nenhuma das 3 formas reais usa bit6=1 aqui.
        }
        int vn = registerNumber(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT, true);
        if (!validDoubleRegister(vn, true)) {
            return null; // D16-D31 não existem neste projeto (sem VFPv3-D32/NEON).
        }
        boolean toArmRegister = (raw & BIT20_MASK) != 0; // VMOV_to_gp (1) / VMOV_from_gp (0).
        int wordIndex = (raw & BIT21_MASK) != 0 ? 1 : 0; // metade alta/baixa do D combinado.
        int sRegister = (vn << 1) | wordIndex;
        int rt = (raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_CORE_TRANSFER,
                rt, sRegister, -1, 0, false, false, toArmRegister);
    }

    // ── bits[27:24]=1101: VLDR/VSTR (bit21=0) ou VLDM/VSTM decrement-before/writeback (bit21=1) ──

    private DecodedInstruction decodeLoadStoreOrLoadStoreMultipleDb(int raw, int address, Condition condition) {
        boolean doublePrecision = size(raw) == SIZE_DOUBLE;
        int rn = (raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK;
        int vd = vd(raw, doublePrecision);
        if (!validDoubleRegister(vd, doublePrecision)) {
            return null;
        }
        if ((raw & BIT21_MASK) == 0) {
            // VLDR_VSTR_{sp,dp}: `---- 1101 u . 0 l rn(4) vd(4) size imm(8)`.
            boolean add = (raw & BIT23_MASK) != 0;
            boolean load = (raw & BIT20_MASK) != 0;
            int imm8 = raw & 0xFF;
            int offsetBytes = (add ? imm8 : -imm8) * 4;
            InstructionKind kind = load ? InstructionKind.VFP_LOAD : InstructionKind.VFP_STORE;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, kind,
                    vd, rn, -1, offsetBytes, true, false, false, 0, doublePrecision);
        }
        // VLDM_VSTM_{sp,dp} com P=1,U=0,W=1 (decrement-before, sempre com writeback — inclui os
        // aliases VPUSH/VSTMDB SP!/VPOP/VLDMIA SP!, decodificados aqui sem forma dedicada).
        boolean load = (raw & BIT20_MASK) != 0;
        return decodeMultipleTransfer(raw, address, condition, doublePrecision, rn, vd, load, true, true);
    }

    // ── bits[27:24]=1100: VLDM/VSTM increment-after (bit23=1) ou VMOV_64_dp/sp (bit23=0) ──

    private DecodedInstruction decodeLoadStoreMultipleIaOrCorePairTransfer(int raw, int address, Condition condition) {
        if ((raw & BIT23_MASK) != 0) {
            // VLDM_VSTM_{sp,dp} com P=0,U=1 (increment-after, writeback opcional):
            // `---- 1100 1 . w l rn(4) vd(4) size imm(8)`.
            boolean doublePrecision = size(raw) == SIZE_DOUBLE;
            int rn = (raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK;
            int vd = vd(raw, doublePrecision);
            if (!validDoubleRegister(vd, doublePrecision)) {
                return null;
            }
            boolean writeback = (raw & BIT21_MASK) != 0;
            boolean load = (raw & BIT20_MASK) != 0;
            return decodeMultipleTransfer(raw, address, condition, doublePrecision, rn, vd, load, writeback, false);
        }
        if ((raw & (1 << 22)) == 0 || (raw & BIT21_MASK) != 0) {
            return null; // não é o formato `010 op` de VMOV_64_{sp,dp} (bits 22:21 fixos em 1,0).
        }
        int size = size(raw);
        boolean toArmRegisters = (raw & BIT20_MASK) != 0; // op=1: fonte -> (Rt,Rt2).
        int rt2 = (raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK;
        int rt = (raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK;
        if (size == SIZE_SINGLE) {
            // VMOV_64_sp (B9.5, ARM DDI 0406C A8.8.346 — forma depreciada mas VFPv2 genuína,
            // `aa32_fpsp_v2` no QEMU real): `---- 1100 010 op rt2(4) rt(4) 1010 00 . 1 vm(4)`.
            // `Sm`/`Sm+1` (par CONSECUTIVO) só coincidem com um `D` inteiro se `m` for par — para
            // `m` ímpar as duas metades pertencem a `D` diferentes, por isso `Kind` PRÓPRIO em vez
            // de reaproveitar {@link InstructionKind#VFP_CORE_PAIR_TRANSFER} (que assume um `D`).
            int vm = registerNumber(raw, 0, VM_EXTENSION_BIT, false); // vm_sp: nibble(3:0)<<1|bit5.
            if (vm >= SINGLE_REGISTER_COUNT - 1) {
                return null; // `Sm+1` sairia do banco de 32 `S` — combinação não definida.
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    InstructionKind.VFP_CORE_PAIR_TRANSFER_SINGLE, rt, rt2, vm, 0, false, false, toArmRegisters);
        }
        if (size != SIZE_DOUBLE) {
            return null;
        }
        // VMOV_64_dp: `---- 1100 010 op rt2(4) rt(4) 1011 00 . 1 vm(4)`.
        int vm = registerNumber(raw, 0, VM_EXTENSION_BIT, true);
        if (!validDoubleRegister(vm, true)) {
            return null;
        }
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_CORE_PAIR_TRANSFER,
                rt, rt2, vm, 0, false, false, toArmRegisters);
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: converte `imm8` (unidade = words) para contagem de
    /// registradores (dupla: `imm8` é o dobro da contagem — `imm8` ímpar em dupla é a forma
    /// depreciada `FLDMX`/`FSTMX`, UNDEFINED aqui, não "arredondar", ver Armadilhas da task).
    private DecodedInstruction decodeMultipleTransfer(int raw, int address, Condition condition,
            boolean doublePrecision, int rn, int firstRegister, boolean load, boolean writeback,
            boolean decrementBefore) {
        int imm8 = raw & 0xFF;
        if (doublePrecision && (imm8 & 1) != 0) {
            return null;
        }
        int count = doublePrecision ? imm8 / 2 : imm8;
        if (doublePrecision && count > 0 && !validDoubleRegister(firstRegister + count - 1, true)) {
            return null;
        }
        int packed = (firstRegister & 0x3F) | (count << 6);
        InstructionKind kind = load ? InstructionKind.VFP_LOAD_MULTIPLE : InstructionKind.VFP_STORE_MULTIPLE;
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, kind,
                -1, rn, -1, packed, false, false, false, 0, doublePrecision, writeback, false,
                decrementBefore ? BlockTransferMode.DB : BlockTransferMode.IA);
    }

    // ── Helpers de registrador/tamanho ──

    private static int size(int raw) {
        return (raw >>> SIZE_FIELD_SHIFT) & SIZE_FIELD_MASK;
    }

    private static int vd(int raw, boolean doublePrecision) {
        return registerNumber(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT, doublePrecision);
    }

    private static int vm(int raw, boolean doublePrecision) {
        return registerNumber(raw, 0, VM_EXTENSION_BIT, doublePrecision);
    }

    private static final int MAX_D16_REGISTER = 15;

    /// VFPv2/VFPv3-d16 só têm 16 registradores `D` (`D0`-`D15`) — um `D:Vd` combinado > 15 exige
    /// {@link ArmFeature#VFPV3_D32} (banco `D0`-`D31`, B13.1). Sem essa feature o encoding é
    /// UNDEFINED aqui, não um `D16`+ silencioso (G8). Sempre `true` para registradores `S` (32
    /// deles, `0`-`31`, cabem inteiros nos 5 bits do campo). Como nenhum preset declara
    /// `VFPV3_D32` ainda (B13.1 é só a fundação), o comportamento observável é idêntico ao de
    /// antes — o gate só fica no lugar certo para a escada NEON.
    private boolean validDoubleRegister(int combined, boolean doublePrecision) {
        if (!doublePrecision || combined <= MAX_D16_REGISTER) {
            return true;
        }
        return architecture.has(ArmFeature.VFPV3_D32);
    }

    /// Combina o nibble de 4 bits (em `nibbleShift`) com o bit de extensão (em `extensionBit`) no
    /// número de registrador VFP de 5 bits. Single: `nibble:extensão` (`Vx:D`, extensão é o bit
    /// menos significativo). Double: `extensão:nibble` (`D:Vx`, extensão é o bit mais
    /// significativo) — a ordem INVERTE entre os dois (vfp.decode `%vX_sp` vs `%vX_dp`).
    private static int registerNumber(int raw, int nibbleShift, int extensionBit, boolean doublePrecision) {
        int nibble = (raw >>> nibbleShift) & NIBBLE_MASK;
        int extension = (raw >>> extensionBit) & 1;
        return doublePrecision ? (extension << 4) | nibble : (nibble << 1) | extension;
    }

    private static DecodedInstruction vfpAlu(IrOp.VfpOperation op, boolean doublePrecision, int vd, int vn, int vm,
            int address, int raw, Condition condition) {
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_ALU,
                vd, vn, vm, op.ordinal(), false, false, false, 0, doublePrecision);
    }

    private static DecodedInstruction vfpConvert(IrOp.VfpConversion conversion, int vd, int vm,
            int address, int raw, Condition condition) {
        return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.VFP_CONVERT,
                vd, vm, -1, conversion.ordinal(), false, false, false);
    }
}
