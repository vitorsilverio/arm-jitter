package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Decodifica o espaço NEON/Advanced SIMD de processamento de dados do encoding A32, seção
/// **"3-reg-same"** — a parte INTEIRA (aritmética / comparação / lógica / pairwise, B13.4) e as
/// famílias **saturantes / de deslocamento por registrador** (`VQADD`/`VQSUB`/`VSHL`/`VQSHL`/
/// `VRSHL`/`VQRSHL`/`VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH`, B13.5). Oráculo: QEMU
/// `target/arm/tcg/neon-dp.decode` seção "3-reg-same" + `translate-neon.c`; ARM DDI 0406C A7.4.5.
///
/// Layout: `1111 001 U 0 D sz:2 Vn:4 Vd:4 opc:4 N Q M op Vm:4`. O frame do grupo é
/// `(raw & 0xFE80_0000) == 0xF200_0000` (bits[31:25]=`1111001`, bit23=0) — EXCLUSIVO desta seção
/// (as outras de `neon-dp.decode` têm bit23=1). Por isso, com a feature ligada, este decoder
/// reivindica o frame INTEIRO: devolve um `IrOp` OU `UNIMPLEMENTED` explícito, nunca `null` (G8) —
/// o espaço incondicional NEON é o historicamente mal decodificado (achado E6).
///
/// As 4 famílias de deslocamento (`opc=0100`/`0101`) usam o encoding `@3same_rev` do QEMU: `Vn` e
/// `Vm` ficam TROCADOS em relação à posição usual (o VALOR deslocado é `Vm`, a QUANTIDADE vem de
/// `Vn`). O núcleo compartilhado `AdvSimdLanes.threeSame` espera valor=`Rn`/quantidade=`Rm` (o A64
/// não tem essa inversão), então este decoder monta o record com `vn`↔`vm` trocados.
///
/// A seção de PONTO FLUTUANTE (`@3same_fp`/`@3same_fp_q0`: `VADD_fp`/`VFMA_fp`/`VMLA_fp`/`VCEQ_fp`/
/// `VMAX_fp`/`VRECPS`/`VPADD_fp`/... , B13.6) também é decodificada aqui, forma F32 apenas — F16
/// (`FEAT_FP16`) vira `UNIMPLEMENTED` (task futura irmã da B19.5).
///
/// Fora de escopo (viram `UNIMPLEMENTED` aqui, com destino registrado): cripto (`SHA1*`/`SHA256*`
/// → B13.15). T32 é B13.16. `FPSCR.QC`/`FPSR.QC` (bit cumulativo de saturação) e `FPSCR.RMode`/`FZ`
/// NÃO são modelados (paridade com o A64) — task futura própria.
///
/// Gates: {@link ArmFeature#ADVANCED_SIMD} (todo o frame) e, ADEMAIS,
/// {@link ArmFeature#ADVANCED_SIMD_RDM} para `VQRDMLAH`/`VQRDMLSH` (`FEAT_RDM`). **Nenhum preset os
/// declara** (B13.22 é quem fecha isso), então sem a feature o frame volta a cair no
/// `UNIMPLEMENTED` de `ArmDecoder#decodeUnconditional` (zero-diff).
public final class NeonDataProcessingDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar
    /// {@link ArmFeature#ADVANCED_SIMD}.
    public NeonDataProcessingDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    // ── Frame da seção "3-reg-same" (bits[31:25]=1111001, bit23=0; U=bit24 fica de fora) ──
    private static final int THREE_SAME_FRAME_MASK = 0xFE80_0000;
    private static final int THREE_SAME_FRAME_VALUE = 0xF200_0000;

    // ── Campos (convenção `D:Vd` de VfpDecoder em precisão dupla) ──
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_EXTENSION_BIT = 22;
    private static final int VN_NIBBLE_SHIFT = 16;
    private static final int VN_EXTENSION_BIT = 7;
    private static final int VM_EXTENSION_BIT = 5;
    private static final int NIBBLE_MASK = 0xF;
    private static final int U_BIT = 24;
    private static final int SIZE_SHIFT = 20;
    private static final int SIZE_MASK = 0x3;
    // Subespaço FP (B13.6): bit21 (`a`) discrimina sub-opcode, bit20 (`sz`) é a largura
    // (`0`=F32/`esz=2`, `1`=F16 → recusado). NÃO confundir `a` com largura (Armadilha 1).
    private static final int FP_A_BIT = 21;
    private static final int FP_SZ_BIT = 20;
    private static final int FP_ELEMENT_ESZ_F32 = 2;
    private static final int OPC_SHIFT = 8;
    private static final int OPC_MASK = 0xF;
    private static final int OP_BIT = 4;
    private static final int QUAD_BIT = 6;
    private static final int DOUBLEWORD_SIZE = 3;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.ADVANCED_SIMD)) {
            return null;
        }
        if ((raw & THREE_SAME_FRAME_MASK) != THREE_SAME_FRAME_VALUE) {
            return null;
        }
        // A partir daqui o frame é nosso: sempre `lifted` ou `unimplemented`, nunca `null` (G8).
        int u = (raw >>> U_BIT) & 1;
        int size = (raw >>> SIZE_SHIFT) & SIZE_MASK;
        int opc = (raw >>> OPC_SHIFT) & OPC_MASK;
        int op = (raw >>> OP_BIT) & 1;
        boolean quad = ((raw >>> QUAD_BIT) & 1) != 0;
        int vd = doubleRegister(raw, VD_NIBBLE_SHIFT, VD_EXTENSION_BIT);
        int vn = doubleRegister(raw, VN_NIBBLE_SHIFT, VN_EXTENSION_BIT);
        int vm = doubleRegister(raw, 0, VM_EXTENSION_BIT);

        // Subespaço de ponto flutuante (B13.6) — reivindicado ANTES do fluxo inteiro (que trata
        // `opc` `1101`/`1110`/`1111` como `null`→`UNIMPLEMENTED` e `opc=1100 op=1 u=0` idem).
        if (isFloatingPoint(opc, op, u)) {
            return decodeFloatingPoint(raw, address, condition, u, opc, op, quad, vd, vn, vm);
        }

        AdvSimdPairwiseOp pairwise = pairwiseOperation(opc, op, u);
        if (pairwise != null) {
            // `@3same_q0`: pairwise A32 é só forma `D`. Não há forma inteira de 64 bits.
            if (quad || size == DOUBLEWORD_SIZE) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonPairwise(pairwise, size, vd, vn, vm));
        }

        AdvSimdThreeSameOp threeSame = threeSameOperation(opc, op, u, size);
        if (threeSame == null) {
            return unimplemented(address, raw, condition);
        }
        // `VQRDMLAH`/`VQRDMLSH` exigem `FEAT_RDM` ALÉM de `ADVANCED_SIMD` — sem ela viram
        // `UNIMPLEMENTED` (o frame já bateu, não é `null`).
        if ((threeSame == AdvSimdThreeSameOp.SQRDMLAH || threeSame == AdvSimdThreeSameOp.SQRDMLSH)
                && !architecture.has(ArmFeature.ADVANCED_SIMD_RDM)) {
            return unimplemented(address, raw, condition);
        }
        boolean logical = opc == 0b0001 && op == 1;
        // `VADD`/`VSUB` (`.i64`), a família lógica (`sz` discriminador, não largura) e as famílias
        // saturantes/de deslocamento por registrador de B13.5 (`VQADD`/`VQSUB`/`VSHL`/`VQSHL`/
        // `VRSHL`/`VQRSHL`) aceitam `size==3`; o resto do inteiro é 8/16/32 (ARM DDI 0406C).
        if (size == DOUBLEWORD_SIZE && !logical && !allowsDoublewordElement(opc, op)) {
            return unimplemented(address, raw, condition);
        }
        // Forma `Q`: os 3 registradores nomeiam pares `D<2n>`/`D<2n+1>` — índice ímpar é UNDEFINED.
        if (quad && ((vd | vn | vm) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        // Lógica: `sz` é discriminador, o elemento é sempre byte (`esz=0`).
        int esz = logical ? 0 : size;
        // `@3same_rev` (`opc=0100`/`0101`): valor = `Vm`, quantidade = `Vn`. O núcleo espera
        // valor=`Rn`/quantidade=`Rm`, então troca-se `vn`↔`vm` ao montar o record.
        boolean reversed = opc == 0b0100 || opc == 0b0101;
        int recordVn = reversed ? vm : vn;
        int recordVm = reversed ? vn : vm;
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonThreeSame(threeSame, quad, esz, vd, recordVn, recordVm));
    }

    /// `size==3` (doubleword) só é válido para `VADD`/`VSUB` (`opc=1000 op=0`, `.i64`) e para as
    /// famílias de B13.5 que o ARM permite em 64 bits: `VQADD`/`VQSUB` (`opc=0000`/`0010 op=1`) e
    /// os 4 deslocamentos por registrador (`opc=0100`/`0101`). A lógica (`opc=0001 op=1`) é tratada
    /// à parte no chamador. `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` são H/S apenas — já filtrados
    /// por {@link #threeSameOperation}.
    private static boolean allowsDoublewordElement(int opc, int op) {
        return switch (opc) {
            case 0b1000 -> op == 0;
            case 0b0000, 0b0010 -> op == 1;
            case 0b0100, 0b0101 -> true;
            default -> false;
        };
    }

    private static DecodedInstruction unimplemented(int address, int raw, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    // ─────────────────────────── Ponto flutuante (B13.6) ───────────────────────────

    /// O subespaço FP de "3-reg-same" (`@3same_fp`/`@3same_fp_q0` do `neon-dp.decode`): `opc` em
    /// `{1101, 1110, 1111}` (todas as combinações são FP) OU `opc=1100` só na forma `VFMA`/`VFMS`
    /// (`op=1 u=0`) — `opc=1100 op=0` é cripto (B13.15) e `opc=1100 op=1 u=1` é `VQRDMLSH` (B13.5).
    private static boolean isFloatingPoint(int opc, int op, int u) {
        if (opc == 0b1101 || opc == 0b1110 || opc == 0b1111) {
            return true;
        }
        return opc == 0b1100 && op == 1 && u == 0;
    }

    /// Decodifica as 22 linhas F32 de `@3same_fp`/`@3same_fp_q0`. F16 (`sz`=bit20=1) → `unimplemented`
    /// (decisão 1 da task: `FEAT_FP16` é task futura, paridade com o A64). `sz`=0 → `esz=2` (F32).
    private static DecodedInstruction decodeFloatingPoint(int raw, int address, Condition condition,
            int u, int opc, int op, boolean quad, int vd, int vn, int vm) {
        int sz = (raw >>> FP_SZ_BIT) & 1;
        if (sz == 1) {
            return unimplemented(address, raw, condition); // F16 → task futura (irmã da B19.5)
        }
        int a = (raw >>> FP_A_BIT) & 1;
        int esz = FP_ELEMENT_ESZ_F32;

        AdvSimdFpPairwiseOp pairwise = fpPairwiseOperation(opc, op, u, a);
        if (pairwise != null) {
            // `@3same_fp_q0`: pairwise FP A32 é só forma `D` (`Q` forçado a 0 no encoding).
            if (quad) {
                return unimplemented(address, raw, condition);
            }
            return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                    new IrOp.NeonFpPairwise(pairwise, esz, vd, vn, vm));
        }

        AdvSimdFpThreeSameOp threeSame = fpThreeSameOperation(opc, op, u, a);
        if (threeSame == null) {
            return unimplemented(address, raw, condition); // combinação FP não alocada (G8)
        }
        // Forma `Q`: os 3 registradores nomeiam pares `D<2n>`/`D<2n+1>` — índice ímpar é UNDEFINED.
        if (quad && ((vd | vn | vm) & 1) != 0) {
            return unimplemented(address, raw, condition);
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.ARM, Condition.AL,
                new IrOp.NeonFpThreeSame(threeSame, quad, esz, vd, vn, vm));
    }

    /// Os 3 pairwise FP (`@3same_fp_q0`): `VPADD.F32` (`u1 a0 opc1101 op0`), `VPMAX.F32`
    /// (`u1 a0 opc1111 op0`), `VPMIN.F32` (`u1 a1 opc1111 op0`). `null` para o resto (cai em
    /// {@link #fpThreeSameOperation}).
    private static AdvSimdFpPairwiseOp fpPairwiseOperation(int opc, int op, int u, int a) {
        if (op != 0 || u != 1) {
            return null;
        }
        if (opc == 0b1101 && a == 0) {
            return AdvSimdFpPairwiseOp.ADD;
        }
        if (opc == 0b1111 && a == 0) {
            return AdvSimdFpPairwiseOp.MAX;
        }
        if (opc == 0b1111 && a == 1) {
            return AdvSimdFpPairwiseOp.MIN;
        }
        return null;
    }

    /// Mapeia `(opc, op, u, a)` para a operação FP "three same" (tabela do Escopo da B13.6), ou
    /// `null` para as combinações FP não alocadas — o chamador transforma `null` em `UNIMPLEMENTED`
    /// (G8). As entradas pairwise (`VPADD`/`VPMAX`/`VPMIN`) já foram tratadas por
    /// {@link #fpPairwiseOperation} e não chegam aqui.
    private static AdvSimdFpThreeSameOp fpThreeSameOperation(int opc, int op, int u, int a) {
        return switch (opc) {
            // `VFMA`/`VFMS` (FUNDIDO) — só `op=1 u=0` chega aqui (garantido por isFloatingPoint).
            case 0b1100 -> a == 0 ? AdvSimdFpThreeSameOp.FMLA : AdvSimdFpThreeSameOp.FMLS;
            case 0b1101 -> op == 0
                    ? (u == 0 ? (a == 0 ? AdvSimdFpThreeSameOp.ADD : AdvSimdFpThreeSameOp.SUB)
                             : (a == 1 ? AdvSimdFpThreeSameOp.ABD : null))
                    // `op=1`: NÃO fundido (`VMLA.F32`/`VMLS.F32`, dois arredondamentos).
                    : (u == 0 ? (a == 0 ? AdvSimdFpThreeSameOp.MLA : AdvSimdFpThreeSameOp.MLS)
                             : (a == 0 ? AdvSimdFpThreeSameOp.MUL : null));
            case 0b1110 -> op == 0
                    ? (u == 0 ? (a == 0 ? AdvSimdFpThreeSameOp.CMEQ : null)
                             : (a == 0 ? AdvSimdFpThreeSameOp.CMGE : AdvSimdFpThreeSameOp.CMGT))
                    : (u == 1 ? (a == 0 ? AdvSimdFpThreeSameOp.FACGE : AdvSimdFpThreeSameOp.FACGT) : null);
            case 0b1111 -> op == 0
                    ? (u == 0 ? (a == 0 ? AdvSimdFpThreeSameOp.MAX : AdvSimdFpThreeSameOp.MIN) : null)
                    : (u == 0 ? (a == 0 ? AdvSimdFpThreeSameOp.RECPS : AdvSimdFpThreeSameOp.RSQRTS)
                             : (a == 0 ? AdvSimdFpThreeSameOp.MAXNM : AdvSimdFpThreeSameOp.MINNM));
            default -> null;
        };
    }

    /// Mapeia `(opc, op, U, size)` para a operação "three same" de B13.4 (inteira) ou B13.5
    /// (saturante / deslocamento por registrador), ou `null` quando o encoding está fora de escopo
    /// (cripto/FP → outras sub-tasks; `VPADD` → tratado por {@link #pairwiseOperation}) — o chamador
    /// transforma `null` em `UNIMPLEMENTED`.
    private static AdvSimdThreeSameOp threeSameOperation(int opc, int op, int u, int size) {
        return switch (opc) {
            case 0b0000 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SHADD : AdvSimdThreeSameOp.UHADD)
                    : (u == 0 ? AdvSimdThreeSameOp.SQADD : AdvSimdThreeSameOp.UQADD);
            case 0b0001 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SRHADD : AdvSimdThreeSameOp.URHADD)
                    : logicalOperation(u, size);
            case 0b0010 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SHSUB : AdvSimdThreeSameOp.UHSUB)
                    : (u == 0 ? AdvSimdThreeSameOp.SQSUB : AdvSimdThreeSameOp.UQSUB);
            case 0b0011 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.CMGT : AdvSimdThreeSameOp.CMHI)
                    : (u == 0 ? AdvSimdThreeSameOp.CMGE : AdvSimdThreeSameOp.CMHS);
            // `@3same_rev` — a troca `vn`↔`vm` é feita no chamador, não aqui.
            case 0b0100 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SSHL : AdvSimdThreeSameOp.USHL)
                    : (u == 0 ? AdvSimdThreeSameOp.SQSHL : AdvSimdThreeSameOp.UQSHL);
            case 0b0101 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SRSHL : AdvSimdThreeSameOp.URSHL)
                    : (u == 0 ? AdvSimdThreeSameOp.SQRSHL : AdvSimdThreeSameOp.UQRSHL);
            case 0b0110 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SMAX : AdvSimdThreeSameOp.UMAX)
                    : (u == 0 ? AdvSimdThreeSameOp.SMIN : AdvSimdThreeSameOp.UMIN);
            case 0b0111 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.SABD : AdvSimdThreeSameOp.UABD)
                    : (u == 0 ? AdvSimdThreeSameOp.SABA : AdvSimdThreeSameOp.UABA);
            case 0b1000 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.ADD : AdvSimdThreeSameOp.SUB)
                    : (u == 0 ? AdvSimdThreeSameOp.CMTST : AdvSimdThreeSameOp.CMEQ);
            case 0b1001 -> op == 0
                    ? (u == 0 ? AdvSimdThreeSameOp.MLA : AdvSimdThreeSameOp.MLS)
                    // `VMUL.P` só existe em byte (`translate-neon.c` só tem fn para MO_8).
                    : (u == 0 ? AdvSimdThreeSameOp.MUL : (size == 0 ? AdvSimdThreeSameOp.PMUL : null));
            // `opc=1011 op=1 u=0` é `VPADD` (pairwise, já resolvido antes); as demais combinações
            // são as multiplicações dobradas — só `esz` `1`(H)/`2`(S).
            case 0b1011 -> switch ((op << 1) | u) {
                case 0b00 -> halfOrWord(size, AdvSimdThreeSameOp.SQDMULH);
                case 0b01 -> halfOrWord(size, AdvSimdThreeSameOp.SQRDMULH);
                case 0b11 -> halfOrWord(size, AdvSimdThreeSameOp.SQRDMLAH);
                default -> null;
            };
            // `opc=1100 op=0` é cripto (`SHA1*`/`SHA256*` → B13.15); `op=1 u=0` é `VFMA_fp` (B13.6);
            // só `op=1 u=1` é desta task (`VQRDMLSH`, H/S apenas).
            case 0b1100 -> (op == 1 && u == 1) ? halfOrWord(size, AdvSimdThreeSameOp.SQRDMLSH) : null;
            default -> null;
        };
    }

    /// `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` existem só em halfword (`size==1`) e word
    /// (`size==2`); `size` `0`(B)/`3`(D) é UNDEFINED (ARM DDI 0406C).
    private static AdvSimdThreeSameOp halfOrWord(int size, AdvSimdThreeSameOp operation) {
        return (size == 1 || size == 2) ? operation : null;
    }

    /// `opc=0001 op=1`: família lógica, onde `sz` (2 bits) é o discriminador — `@3same_logic`
    /// força `size=0` no operando.
    private static AdvSimdThreeSameOp logicalOperation(int u, int size) {
        if (u == 0) {
            return switch (size) {
                case 0 -> AdvSimdThreeSameOp.AND;
                case 1 -> AdvSimdThreeSameOp.BIC;
                case 2 -> AdvSimdThreeSameOp.ORR;
                default -> AdvSimdThreeSameOp.ORN;
            };
        }
        return switch (size) {
            case 0 -> AdvSimdThreeSameOp.EOR;
            case 1 -> AdvSimdThreeSameOp.BSL;
            case 2 -> AdvSimdThreeSameOp.BIT;
            default -> AdvSimdThreeSameOp.BIF;
        };
    }

    /// `opc=1010` (`VPMAX`/`VPMIN`) e `opc=1011 op=1 U=0` (`VPADD`) — as formas "pairwise" INTEIRAS
    /// (`@3same_q0`). `null` para tudo o mais (o chamador vai então tentar `threeSameOperation`).
    private static AdvSimdPairwiseOp pairwiseOperation(int opc, int op, int u) {
        if (opc == 0b1010) {
            return op == 0
                    ? (u == 0 ? AdvSimdPairwiseOp.SMAX : AdvSimdPairwiseOp.UMAX)
                    : (u == 0 ? AdvSimdPairwiseOp.SMIN : AdvSimdPairwiseOp.UMIN);
        }
        if (opc == 0b1011 && op == 1 && u == 0) {
            return AdvSimdPairwiseOp.ADD;
        }
        return null;
    }

    private static int doubleRegister(int raw, int nibbleShift, int extensionBit) {
        return (((raw >>> extensionBit) & 1) << 4) | ((raw >>> nibbleShift) & NIBBLE_MASK);
    }
}
