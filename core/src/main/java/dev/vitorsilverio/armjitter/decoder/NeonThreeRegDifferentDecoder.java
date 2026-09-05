package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica as seções **"three-reg-different-lengths"** (task B13.10) e **"2-regs-plus-scalar"**
/// (task B13.11) do espaço NEON/Advanced SIMD "two registers, or three registers of different
/// lengths" do encoding A32 — as duas compartilham o mesmo frame e discriminador (`size != 0b11`),
/// diferindo só pelo {@link #LONG_FORM_BIT}, por isso UM decoder só (ver "Decisão" da B13.11).
///
/// **"three-reg-different-lengths"** (B13.10): `VADDL`/`VSUBL`/`VABAL`/`VABDL`/`VMLAL`/`VMLSL`/
/// `VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`/`VMULL.P8` (forma **Long**), `VADDW`/`VSUBW` (forma
/// **Wide**) e `VADDHN`/`VRADDHN`/`VSUBHN`/`VRSUBHN` (forma **Narrow**/"half narrowing") — 26
/// linhas de `target/isa-decode/neon-dp.decode:538-577`.
///
/// **"2-regs-plus-scalar"** (B13.11): `VMLA`/`VMLS`/`VMUL` inteiro (mesma largura, sem variante de
/// sinal) e `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` ("doubling high half"), `VMLAL`/`VMLSL`/
/// `VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL` (forma **alargando**) e `VMLA_F`/`VMLS_F`/`VMUL_F` (F32,
/// NÃO fundido) — 19 linhas de `target/isa-decode/neon-dp.decode:583-619`. O escalar é montado
/// diferente do índice `H:L:M` do A64 (B8.19): `Vm` restrito a `D0`-`D7` (halfword, índice
/// `M:Vm[3]`, 2 bits) ou `D0`-`D15` (word, índice `M`, 1 bit) — `size==0b00` não existe nesta
/// classe (G8).
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
    /// `size==0b00` — não existe na classe "2-regs-plus-scalar" (B13.11, G8).
    private static final int HALFWORD_SIZE_MISSING = 0x0;
    /// `size==0b01` — escalar halfword (`Vm` restrito a `D0`-`D7`, índice `M:Vm[3]`).
    private static final int HALFWORD_SIZE = 0x1;
    /// `size==0b10` — escalar word/F32 (`Vm` restrito a `D0`-`D15`, índice `M`).
    private static final int WORD_SIZE = 0x2;
    /// Bit `M` do escalar do "2-regs-plus-scalar" (B13.11) — mesma posição de {@link
    /// #VM_EXTENSION_BIT}, papel diferente (parte do ÍNDICE, não do registrador).
    private static final int SCALAR_M_SHIFT = 5;
    private static final int SCALAR_VM_NIBBLE_MASK = 0xF;

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
            return decodeTwoRegsPlusScalar(raw, address, condition, size);
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

    /// "2-regs-plus-scalar" (B13.11) — entra já sabendo que o frame/`size` batem (`size` `1` ou `2`,
    /// `size==0b11` já filtrado por {@link #tryDecode} antes de rotear aqui). Bit24 é **`Q`** para
    /// as formas de mesma largura/"doubling high half" (nenhuma tem variante de sinal) e **`U`**
    /// para a forma alargando — o `opc` decide qual tabela bate, nunca os dois ao mesmo tempo (as 16
    /// combinações de `opc` são cobertas de forma EXCLUSIVA pelas 3 tabelas abaixo). A partir daqui o
    /// espaço é nosso: sempre `lifted` ou `unimplemented`, nunca `null` (G8).
    private DecodedInstruction decodeTwoRegsPlusScalar(int raw, int address, Condition condition, int size) {
        if (size == HALFWORD_SIZE_MISSING) {
            // `size==0b00` não existe nesta classe (G8) — só halfword(`1`)/word(`2`) têm forma real.
            return unimplemented(address, raw, condition);
        }
        int opc = (raw >>> OPC_SHIFT) & OPC_MASK;
        int bitQU = (raw >>> U_BIT) & 1;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int mBit = (raw >>> SCALAR_M_SHIFT) & 1;
        int vmNibble = raw & SCALAR_VM_NIBBLE_MASK;
        int vm = scalarRegister(size, vmNibble);
        int index = scalarIndex(size, mBit, vmNibble);

        AdvSimdWideningOp wideningOp = scalarWideningOperation(opc, bitQU);
        if (wideningOp != null) {
            if ((vd & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonWideningByElement(wideningOp, size, vd, vn, vm, index));
        }
        boolean quad = bitQU != 0;
        AdvSimdThreeSameOp intOp = scalarThreeSameOperation(opc);
        if (intOp != null) {
            boolean requiresRdm = intOp == AdvSimdThreeSameOp.SQRDMLAH || intOp == AdvSimdThreeSameOp.SQRDMLSH;
            if (requiresRdm && !architecture.has(ArmFeature.ADVANCED_SIMD_RDM)) {
                return unimplemented(address, raw, condition);
            }
            if (quad && ((vd | vn) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonThreeSameByElement(intOp, size, quad, vd, vn, vm, index));
        }
        AdvSimdFpThreeSameOp fpOp = scalarFpThreeSameOperation(opc);
        if (fpOp != null) {
            if (size != WORD_SIZE) {
                // `size==0b01`: forma F16 (`sz=1`), fora de escopo — task irmã "NEON FP16 AArch32".
                return unimplemented(address, raw, condition);
            }
            if (quad && ((vd | vn) & 1) != 0) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonFpThreeSameByElement(fpOp, quad, vd, vn, vm, index));
        }
        // As 3 tabelas acima cobrem exaustivamente os 16 valores de `opc` — inalcançável, mas
        // documenta o contrato (G8) caso uma tabela futura vire incompleta por engano.
        return unimplemented(address, raw, condition);
    }

    /// `(opc, U)` → família **alargando** do "2-regs-plus-scalar" (`VMLAL`/`VMLSL`/`VMULL`/
    /// `VQDMLAL`/`VQDMLSL`/`VQDMULL`) — `VQDMLAL`/`VQDMLSL`/`VQDMULL` só têm forma `U=0` (mesma regra
    /// de {@link #longOperation}).
    private static AdvSimdWideningOp scalarWideningOperation(int opc, int u) {
        return switch (opc) {
            case 0b0010 -> u == 0 ? AdvSimdWideningOp.SMLAL : AdvSimdWideningOp.UMLAL;
            case 0b0011 -> u == 0 ? AdvSimdWideningOp.SQDMLAL : null;
            case 0b0110 -> u == 0 ? AdvSimdWideningOp.SMLSL : AdvSimdWideningOp.UMLSL;
            case 0b0111 -> u == 0 ? AdvSimdWideningOp.SQDMLSL : null;
            case 0b1010 -> u == 0 ? AdvSimdWideningOp.SMULL : AdvSimdWideningOp.UMULL;
            case 0b1011 -> u == 0 ? AdvSimdWideningOp.SQDMULL : null;
            default -> null;
        };
    }

    /// `opc` → família **mesma largura**/"doubling high half" INTEIRA do "2-regs-plus-scalar"
    /// (`VMLA`/`VMLS`/`VMUL`/`VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH`) — nenhuma tem variante de
    /// sinal (bit24 é `Q`, não `U`, aqui). `VQRDMLAH`/`VQRDMLSH` exigem
    /// {@link ArmFeature#ADVANCED_SIMD_RDM} (checado pelo chamador).
    private static AdvSimdThreeSameOp scalarThreeSameOperation(int opc) {
        return switch (opc) {
            case 0b0000 -> AdvSimdThreeSameOp.MLA;
            case 0b0100 -> AdvSimdThreeSameOp.MLS;
            case 0b1000 -> AdvSimdThreeSameOp.MUL;
            case 0b1100 -> AdvSimdThreeSameOp.SQDMULH;
            case 0b1101 -> AdvSimdThreeSameOp.SQRDMULH;
            case 0b1110 -> AdvSimdThreeSameOp.SQRDMLAH;
            case 0b1111 -> AdvSimdThreeSameOp.SQRDMLSH;
            default -> null;
        };
    }

    /// `opc` → família **mesma largura** de PONTO FLUTUANTE F32 do "2-regs-plus-scalar"
    /// (`VMLA_F`/`VMLS_F`/`VMUL_F`, NÃO fundido — decisão 3 da B13.6).
    private static AdvSimdFpThreeSameOp scalarFpThreeSameOperation(int opc) {
        return switch (opc) {
            case 0b0001 -> AdvSimdFpThreeSameOp.MLA;
            case 0b0101 -> AdvSimdFpThreeSameOp.MLS;
            case 0b1001 -> AdvSimdFpThreeSameOp.MUL;
            default -> null;
        };
    }

    /// Registrador do ESCALAR do "2-regs-plus-scalar": `Vm[2:0]` (`D0`-`D7`) na forma halfword,
    /// `Vm[3:0]` (`D0`-`D15`) na forma word — diferente do A64 (`H:L:M` com `Rm` estreitado a
    /// `V0`-`V15`), que usa TODOS os bits de `Rm` como registrador e nenhum como índice.
    private static int scalarRegister(int size, int vmNibble) {
        return size == HALFWORD_SIZE ? (vmNibble & 0b111) : vmNibble;
    }

    /// Índice do elemento do "2-regs-plus-scalar": `M:Vm[3]` (2 bits) na forma halfword, `M` (1 bit)
    /// na forma word.
    private static int scalarIndex(int size, int mBit, int vmNibble) {
        return size == HALFWORD_SIZE ? ((mBit << 1) | ((vmNibble >>> 3) & 1)) : mBit;
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }
}
