package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica a seção **"three-reg-different-lengths"** do espaço NEON/Advanced SIMD "two
/// registers, or three registers of different lengths" do encoding A32 (task B13.10) —
/// `VADDL`/`VSUBL`/`VABAL`/`VABDL`/`VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`/
/// `VMULL.P8` (forma **Long**), `VADDW`/`VSUBW` (forma **Wide**) e `VADDHN`/`VRADDHN`/`VSUBHN`/
/// `VRSUBHN` (forma **Narrow**/"half narrowing") — 26 linhas de
/// `target/isa-decode/neon-dp.decode:538-577`.
///
/// Layout: `1111 001 U 1 D size:2 Vn:4 Vd:4 opc:4 N 0 M 0 Vm:4`. O comentário do `.decode`
/// (`:387-398`) explica a estrutura: dentro do agrupamento "two regs or 3 diff length"
/// (`[23,4]=0b10`), `bits[21:20]` ou são parte do OPCODE (`0b11` — `VEXT`/two-reg-misc/`VTBL`/
/// dup-scalar, B13.12-14) ou um campo `size` (`!= 0b11` — este grupo e "2-regs-plus-scalar",
/// B13.11). Os dois exclusive-groups de `size != 0b11` se distinguem pelo bit6, fixo em `0` aqui
/// ("N 0 M 0") e `1` em "2-regs-plus-scalar" ("N 1 M 0", onde o bit24 é renomeado `Q`, não `U`).
///
/// **`size == 0b11`** e **bit6 == 1** NÃO são desta task: devolve **`null`** nos dois casos,
/// deixando o espaço livre para B13.12-14 e B13.11 respectivamente (G8 não se aplica — `null`
/// deixa o espaço livre; `unimplemented` o roubaria, mesma disciplina de `immh==0` na B13.7/B13.9).
///
/// `size` (`0`-`2`) é SEMPRE o `esz` do lado ESTREITO nas três formas — fonte em Long (`Vn`/`Vm`
/// são `D`), `Vm` em Wide (estreito; `Vd`/`Vn` são `Q`), destino em Narrow (`Vd` é `D`; `Vn`/`Vm`
/// são `Q`) — mesma convenção de {@code Ir64VectorWideningOp}/{@code Ir64VectorWideOp}/
/// {@code Ir64VectorNarrowOp} do A64 (B8.7). Isso é o que permite reusar UMA aritmética de campo
/// (`esz`/`wideEsz = esz+1`) para as três formas.
///
/// **O espelho A64 já implementa quase tudo** (B8.7/B8.8/B8.20): `SADDL`/`UADDL`/`SADDW`/`SSUBL`/
/// `SSUBW`, `ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN`, `SABAL`/`SABDL`, `SMULL`/`UMULL`/`PMULL`/`SMLAL`/
/// `SMLSL`, `SQDMULL`/`SQDMLAL`/`SQDMLSL` — a semântica vive no núcleo COMPARTILHADO
/// ({@code AdvSimdLanes.widening}/`wide`/`narrow`), RFC B13.2 D1; `VMULL.P8` reusa
/// {@code AdvSimdLanes.polynomialMultiply8} (mesma função do `PMULL`/`PMULL2` `.p8` A64), sem
/// forma `U=1` correspondente.
///
/// **Regra de registrador ímpar UNDEFINED por forma** (a única constante entre as formas é
/// "todo operando `Q` tem que ser par"): Long — só `Vd` (o único `Q`); Wide — `Vd` **e** `Vn`;
/// Narrow — `Vn` **e** `Vm`.
///
/// **`FPSCR.QC`** (tocado por `VQDMLAL`/`VQDMLSL`/`VQDMULL`) NÃO é modelado — paridade com o A64 e
/// com B13.5/B13.7/B13.8, task futura própria.
///
/// Gate único: {@link ArmFeature#ADVANCED_SIMD}. **Nenhum preset a declara** (B13.22 é quem fecha
/// isso), então sem a feature {@link #tryDecode} devolve `null` e o espaço continua caindo no
/// `UNIMPLEMENTED` de `ArmDecoder#decodeUnconditional` (zero-diff, G3).
public final class NeonThreeRegDifferentDecoder implements DecoderExtension {
    /// Frame do grupo: bits[31:25]=`1111001`, bit23=1 ("two regs or 3 diff length"), bit4=0
    /// ("three-reg-different-lengths"/"2-regs-plus-scalar", não "2-reg-and-shift").
    private static final int FRAME_MASK = 0xFE80_0010;
    private static final int FRAME_VALUE = 0xF280_0000;

    private static final int U_BIT = 24;
    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    private static final int VN_NIBBLE_SHIFT = 16;
    private static final int VN_EXTENSION_BIT = 7;
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int OPC_SHIFT = 8;
    private static final int OPC_MASK = 0xF;
    /// Bit que distingue "three-reg-different-lengths" (`0`, esta task) de "2-regs-plus-scalar"
    /// (`1`, B13.11) dentro do subgrupo `size != 0b11`.
    private static final int LONG_FORM_BIT = 6;
    private static final int UNALLOCATED_SIZE = 0x3;
    private static final int NIBBLE_MASK = 0xF;

    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — consulta {@link ArmFeature#ADVANCED_SIMD}.
    public NeonThreeRegDifferentDecoder(ArmArchitecture architecture) {
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
        if (size == UNALLOCATED_SIZE) {
            // `VEXT`/two-reg-misc/`VTBL`/dup-scalar — B13.12-14, ainda sem dono.
            return null;
        }
        if (((raw >>> LONG_FORM_BIT) & 1) != 0) {
            // "2-regs-plus-scalar" — B13.11, ainda sem dono.
            return null;
        }
        // A partir daqui o frame é nosso: sempre `lifted` ou `unimplemented`, nunca `null`.
        int u = (raw >>> U_BIT) & 1;
        int opc = (raw >>> OPC_SHIFT) & OPC_MASK;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);

        AdvSimdWideningOp longOp = longOperation(opc, u);
        if (longOp != null) {
            if ((vd & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonWidening(longOp, size, vd, vn, vm));
        }
        AdvSimdWideOp wideOp = wideOperation(opc, u);
        if (wideOp != null) {
            if (((vd | vn) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonWide(wideOp, size, vd, vn, vm));
        }
        AdvSimdNarrowOp narrowOp = narrowOperation(opc, u);
        if (narrowOp != null) {
            if (((vn | vm) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonNarrow(narrowOp, size, vd, vn, vm));
        }
        // `opc=1111` (reservado) ou `U=1` combinado com um `opc` só-`U=0` (`VQDMLAL`/`VQDMLSL`/
        // `VQDMULL`/`VMULL.P8`, opc `1001`/`1011`/`1101`/`1110`).
        return unimplemented(address, raw, condition);
    }

    /// `(opc, U)` → família **Long** (tabela do Escopo). `VQDMLAL`/`VQDMLSL`/`VQDMULL`/`VMULL.P8`
    /// só têm forma `U=0` (não existe variante "não assinada" para saturante dobrado/polinomial).
    private static AdvSimdWideningOp longOperation(int opc, int u) {
        return switch (opc) {
            case 0b0000 -> u == 0 ? AdvSimdWideningOp.SADDL : AdvSimdWideningOp.UADDL;
            case 0b0010 -> u == 0 ? AdvSimdWideningOp.SSUBL : AdvSimdWideningOp.USUBL;
            case 0b0101 -> u == 0 ? AdvSimdWideningOp.SABAL : AdvSimdWideningOp.UABAL;
            case 0b0111 -> u == 0 ? AdvSimdWideningOp.SABDL : AdvSimdWideningOp.UABDL;
            case 0b1000 -> u == 0 ? AdvSimdWideningOp.SMLAL : AdvSimdWideningOp.UMLAL;
            case 0b1001 -> u == 0 ? AdvSimdWideningOp.SQDMLAL : null;
            case 0b1010 -> u == 0 ? AdvSimdWideningOp.SMLSL : AdvSimdWideningOp.UMLSL;
            case 0b1011 -> u == 0 ? AdvSimdWideningOp.SQDMLSL : null;
            case 0b1100 -> u == 0 ? AdvSimdWideningOp.SMULL : AdvSimdWideningOp.UMULL;
            case 0b1101 -> u == 0 ? AdvSimdWideningOp.SQDMULL : null;
            case 0b1110 -> u == 0 ? AdvSimdWideningOp.PMULL : null;
            default -> null;
        };
    }

    /// `(opc, U)` → família **Wide** (`VADDW`/`VSUBW`).
    private static AdvSimdWideOp wideOperation(int opc, int u) {
        return switch (opc) {
            case 0b0001 -> u == 0 ? AdvSimdWideOp.SADDW : AdvSimdWideOp.UADDW;
            case 0b0011 -> u == 0 ? AdvSimdWideOp.SSUBW : AdvSimdWideOp.USUBW;
            default -> null;
        };
    }

    /// `(opc, U)` → família **Narrow**/"half narrowing" (`VADDHN`/`VRADDHN`/`VSUBHN`/`VRSUBHN`).
    /// Aqui `U` escolhe arredondado × não arredondado, NÃO assinado × não assinado (não há
    /// variante de sinal nesta família).
    private static AdvSimdNarrowOp narrowOperation(int opc, int u) {
        return switch (opc) {
            case 0b0100 -> u == 0 ? AdvSimdNarrowOp.ADDHN : AdvSimdNarrowOp.RADDHN;
            case 0b0110 -> u == 0 ? AdvSimdNarrowOp.SUBHN : AdvSimdNarrowOp.RSUBHN;
            default -> null;
        };
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }
}
