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
/// single, `1011` double — `1001`, meia precisão, fica FORA do escopo desta task e portanto fora do
/// gate abaixo, então nunca chega ao corpo de {@link #tryDecode}). Como {@link #isVfpCoprocessorSpace}
/// só olha `bits[27:24]`/`bits[11:8]` (nunca `bits[31:28]`), a MESMA lógica decodifica tanto o `raw`
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

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.VFPV2) || !isVfpCoprocessorSpace(raw)) {
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
        return architecture.has(ArmFeature.VFPV2) && isVfpCoprocessorSpace(raw);
    }

    private static boolean isVfpCoprocessorSpace(int raw) {
        int bits2724 = (raw >>> COPROCESSOR_SPACE_SHIFT) & COPROCESSOR_SPACE_MASK;
        boolean coprocessorSpace = bits2724 == 0xC || bits2724 == 0xD || bits2724 == 0xE;
        int size = (raw >>> SIZE_FIELD_SHIFT) & SIZE_FIELD_MASK;
        return coprocessorSpace && (size == SIZE_SINGLE || size == SIZE_DOUBLE);
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
            // op1==0b001 é VNMLA/VNMLS — fora do escopo desta task (não há VfpOperation para eles).
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
            default -> null; // VRINT*/VCVT_f16*/VJCVT/VCVT_fix — fora de escopo (Não inclui).
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
        return null; // VMOV_to_gp/VMOV_from_gp/VDUP (formas NEON escalares) — fora de escopo.
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
        if (size != SIZE_DOUBLE) {
            return null; // VMOV_64_sp (par de registradores S, forma depreciada) — fora de escopo.
        }
        // VMOV_64_dp: `---- 1100 010 op rt2(4) rt(4) 1011 00 . 1 vm(4)`.
        boolean toArmRegisters = (raw & BIT20_MASK) != 0; // op=1: Dm -> (Rt,Rt2) (FMRRD).
        int rt2 = (raw >>> VN_NIBBLE_SHIFT) & NIBBLE_MASK;
        int rt = (raw >>> VD_NIBBLE_SHIFT) & NIBBLE_MASK;
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
    /// VFPv3-D32 (fora do escopo do épico) e é UNDEFINED aqui, não um `D16`+ silencioso. Sempre
    /// `true` para registradores `S` (32 deles, `0`-`31`, cabem inteiros nos 5 bits do campo).
    private static boolean validDoubleRegister(int combined, boolean doublePrecision) {
        return !doublePrecision || combined <= MAX_D16_REGISTER;
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
