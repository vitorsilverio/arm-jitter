package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdRegisterWords;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa a IR de NEON/Advanced SIMD de 32 bits (épico B13). A semântica de LANE (largura de
/// elemento, extensão, truncamento) NÃO vive aqui — vem do núcleo vetorial COMPARTILHADO com o
/// lado A64 ({@link AdvSimdLanes}), decisão D1 da RFC B13.2. O que mora neste executor é só o que
/// é do mundo de 32 bits: o laço de endereçamento de load/store (entrelaçamento, `stride`,
/// escrita de volta por `rm`) e o mapeamento registrador → palavra (índice de `D`).
///
/// Nenhuma operação daqui altera o PC (mesma forma de {@link IrVfpExecutor}).
public final class IrNeonExecutor {
    /// Bytes de um registrador `D` (arranjo NEON de 64 bits).
    private static final int DOUBLEWORD_BYTES = 8;
    /// Campo `rm` que sinaliza "sem escrita de volta" (ARM DDI 0406C A7.7).
    private static final int RM_NO_WRITEBACK = 15;
    /// Campo `rm` que sinaliza "escrita de volta IMEDIATA" (`Rn += bytes transferidos`).
    private static final int RM_IMMEDIATE_WRITEBACK = 13;
    /// Bytes de uma lane de produto escalar (B13.18) — sempre 32 bits, mesmo os operandos sendo
    /// bytes.
    private static final int DOT_PRODUCT_LANE_BYTES = 4;

    private final IrExecutionSupport support;

    IrNeonExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// NEON "three same" (`VADD`/`VSUB` inteiro no protótipo da RFC B13.2): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#threeSame}) — a MESMA função que o executor A64 chama
    /// para `ADD_v`/`SUB_v`. NEON nomeia registradores por índice de `D`, e a base de palavra de
    /// um operando de 64 bits é o próprio índice de `D`; nenhuma escrita destrutiva depois do
    /// laço (VFP32 nunca zera bits fora do registrador escrito).
    public void executeNeonThreeSame(ArmCore core, IrOp.NeonThreeSame op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.threeSame(vfp, op.op(), esz, lanes, op.vd(), op.vn(), op.vm());
    }

    /// NEON "pairwise" (`VPADD`/`VPMAX`/`VPMIN`, B13.4): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#pairwise}) — a MESMA função que o executor A64 chama para
    /// `ADDP_v`/`SMAXP_v`/... Só forma `D` (8 bytes); nenhuma escrita destrutiva depois (VFP32
    /// nunca zera bits fora do registrador escrito).
    public void executeNeonPairwise(ArmCore core, IrOp.NeonPairwise op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int lanes = DOUBLEWORD_BYTES >> esz;
        AdvSimdLanes.pairwise(vfp, op.op(), esz, lanes, op.vd(), op.vn(), op.vm());
    }

    /// NEON "three same" de PONTO FLUTUANTE (`VADD.F32`/`VMUL.F32`/`VFMA.F32`/`VMAX.F32`/... , B13.6):
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpThreeSame}) — a MESMA função que o
    /// executor A64 chama para `FADD_v`/`FMUL_v`/... NEON nomeia registradores por índice de `D`, e
    /// a base de palavra de um operando de 64 bits é o próprio índice de `D`; nenhuma escrita
    /// destrutiva depois do laço (VFP32 nunca zera bits fora do registrador escrito).
    public void executeNeonFpThreeSame(ArmCore core, IrOp.NeonFpThreeSame op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.fpThreeSame(vfp, op.op(), esz, lanes, op.vd(), op.vn(), op.vm());
    }

    /// NEON "pairwise" de PONTO FLUTUANTE (`VPADD.F32`/`VPMAX.F32`/`VPMIN.F32`, B13.6): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#fpPairwise}). Só forma `D` (8 bytes); nenhuma
    /// escrita destrutiva depois (VFP32 nunca zera bits fora do registrador escrito).
    public void executeNeonFpPairwise(ArmCore core, IrOp.NeonFpPairwise op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int lanes = DOUBLEWORD_BYTES >> esz;
        AdvSimdLanes.fpPairwise(vfp, op.op(), esz, lanes, op.vd(), op.vn(), op.vm());
    }

    /// NEON "2-reg-and-shift" com deslocamento por IMEDIATO (`VSHR`/`VSRA`/`VRSHR`/`VRSRA`/`VSRI`/
    /// `VSHL`/`VSLI`/`VQSHL`/`VQSHLU`, B13.7): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#shiftImmediate}) — a MESMA função que o executor A64 chama para
    /// `SSHR`/`SHL`/... `Vm` é a FONTE do valor deslocado (não há `Vn` nesta forma). NEON nomeia
    /// registradores por índice de `D`, e a base de palavra de um operando de 64 bits é o próprio
    /// índice de `D`; nenhuma escrita destrutiva depois do laço (VFP32 nunca zera bits fora do
    /// registrador escrito).
    public void executeNeonShiftImmediate(ArmCore core, IrOp.NeonShiftImmediate op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.shiftImmediate(vfp, op.op(), esz, op.shift(), lanes, op.vd(), op.vm());
    }

    /// NEON "2-reg-and-shift" ESTREITANTE (`VSHRN`/`VRSHRN`/`VQSHRUN`/`VQRSHRUN`/`VQSHRN`/`VQRSHRN`,
    /// B13.8): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#shiftNarrowImmediate}) — a MESMA
    /// função que o executor A64 chama para `SHRN`/... A fonte é o `Q` nomeado por {@link
    /// IrOp.NeonShiftNarrowImmediate#vm} (`D<vm>`:`D<vm+1>`), o destino é o `D` nomeado por `vd`
    /// (`8 >> esz` lanes estreitas). A32 não tem forma "2": `laneOffset` é sempre `0`. Índice de `D`
    /// = índice de palavra no banco VFP32; nenhuma escrita destrutiva depois.
    public void executeNeonShiftNarrowImmediate(ArmCore core, IrOp.NeonShiftNarrowImmediate op) {
        VfpRegisters vfp = core.vfp();
        int elements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.shiftNarrowImmediate(vfp, op.op(), op.esz(), op.shift(), elements, 0, op.vd(), op.vm());
    }

    /// NEON "2-reg-and-shift" ALARGANTE (`VSHLL`, B13.8): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#shiftWidenImmediate}) — a MESMA função que o executor A64 chama para
    /// `SSHLL`/`USHLL`. A fonte é o `D` nomeado por {@link IrOp.NeonShiftWidenImmediate#vm}, o
    /// destino é o `Q` nomeado por `vd` (`D<vd>`:`D<vd+1>`, `8 >> esz` lanes largas). A32 não tem
    /// forma "2": `laneOffset` é sempre `0`.
    public void executeNeonShiftWidenImmediate(ArmCore core, IrOp.NeonShiftWidenImmediate op) {
        VfpRegisters vfp = core.vfp();
        int outputElements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.shiftWidenImmediate(vfp, op.op(), op.esz(), op.shift(), outputElements, 0, op.vd(), op.vm());
    }

    /// NEON "2-reg-and-shift" `VCVT` fixo↔float F32 (B13.8): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#convertFixedPoint}) — a MESMA função que o executor A64 chama para
    /// `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` na forma `@fcvt_fixed`. `4` lanes na forma `Q`, `2` na `D`;
    /// leitura e escrita na mesma largura (`esz=2`), sem escrita destrutiva depois.
    public void executeNeonConvertFixedPoint(ArmCore core, IrOp.NeonConvertFixedPoint op) {
        VfpRegisters vfp = core.vfp();
        int elementBytes = 1 << op.esz();
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.convertFixedPoint(vfp, op.esz(), op.fractionBits(), op.toFloat(), op.signed(),
                lanes, op.vd(), op.vm());
    }

    /// NEON "1-reg-and-modified-immediate" (`VMOV`/`VMVN`/`VORR`/`VBIC` imediato, B13.9): `imm64`
    /// já vem EXPANDIDO do decoder (núcleo COMPARTILHADO `AdvSimdModifiedImmediate`, RFC B13.2 D1).
    /// `Q=0`: aplica a operação à palavra `vd`. `Q=1`: às palavras `vd` e `vd+1`. `ORR`/`BIC` LEEM a
    /// palavra atual (mesma disciplina RMW de `VSRA`/`VSRI`, B13.7); `MOV`/`MVN` sobrescrevem. Sem
    /// escrita destrutiva depois (VFP32 nunca zera bits fora do registrador escrito).
    public void executeNeonModifiedImmediate(ArmCore core, IrOp.NeonModifiedImmediate op) {
        VfpRegisters vfp = core.vfp();
        applyModifiedImmediate(vfp, op, op.vd());
        if (op.quad()) {
            applyModifiedImmediate(vfp, op, op.vd() + 1);
        }
    }

    private static void applyModifiedImmediate(VfpRegisters vfp, IrOp.NeonModifiedImmediate op, int word) {
        long current = vfp.d(word);
        long result = switch (op.op()) {
            case MOV -> op.imm64();
            case MVN -> ~op.imm64();
            case ORR -> current | op.imm64();
            case BIC -> current & ~op.imm64();
        };
        vfp.setD(word, result);
    }

    /// NEON "three-reg-different-lengths", forma **Long** ALARGANDO (`VADDL`/`VSUBL`/`VABAL`/
    /// `VABDL`/`VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`/`VMULL.P8`, B13.10): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#widening}) — a MESMA função que o executor A64
    /// chama para `SADDL`/`SMULL`/... `Vn`/`Vm` são `D` (fonte, `8 >> esz` lanes), `Vd` é `Q` (mesma
    /// contagem de lanes, largura dobrada). A32 não tem forma "2": `laneOffset` é sempre `0`.
    public void executeNeonWidening(ArmCore core, IrOp.NeonWidening op) {
        VfpRegisters vfp = core.vfp();
        int outputElements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.widening(vfp, op.op(), op.esz(), outputElements, 0, op.vd(), op.vn(), op.vm());
    }

    /// NEON "three-reg-different-lengths", forma **Wide** (`VADDW`/`VSUBW`, B13.10): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#wide}) — a MESMA função que o executor A64 chama
    /// para `SADDW`/`SSUBW`/... `Vd`/`Vn` são `Q` (`8 >> esz` lanes largas), `Vm` é `D` (mesma
    /// contagem, estreito). A32 não tem forma "2": `laneOffset` é sempre `0`.
    public void executeNeonWide(ArmCore core, IrOp.NeonWide op) {
        VfpRegisters vfp = core.vfp();
        int elements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.wide(vfp, op.op(), op.esz(), elements, 0, op.vd(), op.vn(), op.vm());
    }

    /// NEON "three-reg-different-lengths", forma **Narrow**/"half narrowing" (`VADDHN`/`VRADDHN`/
    /// `VSUBHN`/`VRSUBHN`, B13.10): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#narrow}) —
    /// a MESMA função que o executor A64 chama para `ADDHN`/`SUBHN`/... `Vn`/`Vm` são `Q` (`8 >>
    /// esz` lanes largas), `Vd` é `D` (mesma contagem, estreito). A32 não tem forma "2":
    /// `laneOffset` é sempre `0`.
    public void executeNeonNarrow(ArmCore core, IrOp.NeonNarrow op) {
        VfpRegisters vfp = core.vfp();
        int elements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.narrow(vfp, op.op(), op.esz(), elements, 0, op.vd(), op.vn(), op.vm());
    }

    /// NEON "2-regs-plus-scalar", forma **mesma largura**/"doubling high half" (`VMLA`/`VMLS`/
    /// `VMUL` inteiro e `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH`, B13.11): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#threeSameByElement}) — a MESMA função que o executor A64
    /// chama para `MUL_vi`/`SQDMULH_vi`/... `Vd`/`Vn` são `D` ou `Q` conforme {@link
    /// IrOp.NeonThreeSameByElement#quad}; nenhuma escrita destrutiva depois do laço (VFP32 nunca
    /// zera bits fora do registrador escrito).
    public void executeNeonThreeSameByElement(ArmCore core, IrOp.NeonThreeSameByElement op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elements = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.threeSameByElement(vfp, op.op(), esz, elements, op.vd(), op.vn(), op.vm(), op.index());
    }

    /// NEON "2-regs-plus-scalar", forma **alargando** (`VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/
    /// `VQDMULL`, B13.11): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#wideningByElement}) —
    /// a MESMA função que o executor A64 chama para `SMULL_vi`/... `Vn` é `D` (fonte, `8 >> esz`
    /// lanes), `Vd` é `Q` (mesma contagem, largura dobrada). A32 não tem forma "2": `laneOffset` é
    /// sempre `0`.
    public void executeNeonWideningByElement(ArmCore core, IrOp.NeonWideningByElement op) {
        VfpRegisters vfp = core.vfp();
        int outputElements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.wideningByElement(vfp, op.op(), op.esz(), outputElements, 0, op.vd(), op.vn(), op.vm(), op.index());
    }

    /// NEON "2-regs-plus-scalar" de PONTO FLUTUANTE F32 (`VMLA_F`/`VMLS_F`/`VMUL_F`, B13.11): delega
    /// ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpThreeSameByElement}) — a MESMA função que o
    /// executor A64 chama para `FMUL_vi`/... `MLA`/`MLS` chegam NÃO fundidos aqui (decisão 3 da
    /// B13.6, já resolvida no decoder). `esz` é sempre `2` (F32, único tamanho real nesta forma A32).
    public void executeNeonFpThreeSameByElement(ArmCore core, IrOp.NeonFpThreeSameByElement op) {
        VfpRegisters vfp = core.vfp();
        int esz = 2;
        int elementBytes = 1 << esz;
        int elements = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.fpThreeSameByElement(vfp, op.op(), esz, elements, op.vd(), op.vn(), op.vm(), op.index());
    }

    /// NEON "two-register miscellaneous" INTEIRA, `size==0b11` (B13.12): `VREV64`/`VREV32`/
    /// `VREV16`/`VPADDL`/`VPADAL`/`VCLS`/`VCLZ`/`VCNT`/`VMVN`/`VQABS`/`VQNEG`/as 5 comparações-com-
    /// zero/`VABS`/`VNEG`/`VRECPE`/`VRSQRTE` (inteiros): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#unary}) — a MESMA função que o A64 chama para `REV64_v`/`SADDLP`/
    /// `CLS_v`/`ABS_v`/`URECPE`/... `elements` é a contagem de lanes de ORIGEM (`esz` bytes); o
    /// núcleo já calcula a metade para `VPADDL`/`VPADAL`. Sem escrita destrutiva depois (VFP32 nunca
    /// zera bits fora do registrador escrito).
    public void executeNeonUnary(ArmCore core, IrOp.NeonUnary op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elements = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.unary(vfp, op.op(), esz, elements, op.vd(), op.vm());
    }

    /// NEON "two-register miscellaneous" narrow unário, `size==0b11` (B13.12): `VMOVN`/`VQMOVUN`/
    /// `VQMOVN_S`/`VQMOVN_U`: delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#narrowUnary}) — a
    /// MESMA função que o A64 chama para `XTN`/`SQXTN`/`SQXTUN`/`UQXTN`. `Vm` é `Q` (`8 >> esz`
    /// lanes largas), `Vd` é `D` (mesma contagem, estreito). A32 não tem forma "2":
    /// `laneOffset` é sempre `0`.
    public void executeNeonNarrowUnary(ArmCore core, IrOp.NeonNarrowUnary op) {
        VfpRegisters vfp = core.vfp();
        int elements = DOUBLEWORD_BYTES >> op.esz();
        AdvSimdLanes.narrowUnary(vfp, op.op(), op.esz(), elements, 0, op.vd(), op.vm());
    }

    /// NEON "two-register miscellaneous" de PONTO FLUTUANTE F32, `size==0b11` (B13.12): `VABS_F`/
    /// `VNEG_F`/as 5 comparações-com-zero FP/`VRECPE_F`/`VRSQRTE_F`: delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpUnary}) — a MESMA função que o A64 chama para `FABS_v`/`FCM**0_v`/
    /// `FRECPE_v`/`FRSQRTE_v`. Só F32 (`esz=2` fixo, F16 fora de escopo); nenhuma escrita destrutiva
    /// depois (VFP32 nunca zera bits fora do registrador escrito).
    public void executeNeonFpUnary(ArmCore core, IrOp.NeonFpUnary op) {
        VfpRegisters vfp = core.vfp();
        int esz = 2;
        int elementBytes = 1 << esz;
        int elements = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        for (int i = 0; i < elements; i++) {
            long inputBits = AdvSimdLanes.element(vfp, op.vm(), i, esz);
            AdvSimdLanes.setElement(vfp, op.vd(), i, esz, AdvSimdLanes.fpUnary(op.op(), esz, inputBits));
        }
    }

    /// `VLD1`-`VLD4`/`VST1`-`VST4` (multiple structures) — laço espelhando
    /// `trans_VLDST_multiple` do QEMU real: `tt = vd + reg + stride * xs`, um elemento por vez em
    /// ordem crescente de endereço, avançando `1 << esz` bytes.
    public void executeNeonLoadStoreMultiple(ArmCore core, IrOp.NeonLoadStoreMultiple op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elementsPerRegister = DOUBLEWORD_BYTES >> esz;
        int base = core.register(op.rn());
        int address = base;
        for (int reg = 0; reg < op.nregs(); reg++) {
            for (int e = 0; e < elementsPerRegister; e++) {
                for (int xs = 0; xs < op.interleave(); xs++) {
                    int d = op.vd() + reg + op.stride() * xs;
                    if (op.load()) {
                        AdvSimdLanes.setElement(vfp, d, e, esz, support.readVectorElement(core, address, esz));
                    } else {
                        support.writeVectorElement(core, address, esz, AdvSimdLanes.element(vfp, d, e, esz));
                        core.notifyOrdinaryWrite(address, elementBytes);
                    }
                    address += elementBytes;
                }
            }
        }
        writeBack(core, op.rn(), op.rm(), base, op.nregs() * op.interleave() * DOUBLEWORD_BYTES);
    }

    /// `VLD1`-`VLD4`/`VST1`-`VST4` (single structure to one lane) — um elemento na lane `index`
    /// de cada um dos `selem` registradores `vd + stride * xs`, sem tocar nenhum outro bit.
    public void executeNeonLoadStoreSingle(ArmCore core, IrOp.NeonLoadStoreSingle op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int base = core.register(op.rn());
        int address = base;
        for (int xs = 0; xs < op.selem(); xs++) {
            int d = op.vd() + op.stride() * xs;
            if (op.load()) {
                AdvSimdLanes.setElement(vfp, d, op.index(), esz, support.readVectorElement(core, address, esz));
            } else {
                support.writeVectorElement(core, address, esz, AdvSimdLanes.element(vfp, d, op.index(), esz));
                core.notifyOrdinaryWrite(address, elementBytes);
            }
            address += elementBytes;
        }
        writeBack(core, op.rn(), op.rm(), base, op.selem() * elementBytes);
    }

    /// `VLD1R`-`VLD4R` (single structure to all lanes) — lê um elemento por registrador e o
    /// replica por todas as lanes do `D` (e no `D` seguinte quando `quad`).
    public void executeNeonLoadAllLanes(ArmCore core, IrOp.NeonLoadAllLanes op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanesPerRegister = DOUBLEWORD_BYTES >> esz;
        int base = core.register(op.rn());
        int address = base;
        for (int xs = 0; xs < op.selem(); xs++) {
            int d = op.vd() + op.stride() * xs;
            long value = support.readVectorElement(core, address, esz);
            for (int lane = 0; lane < lanesPerRegister; lane++) {
                AdvSimdLanes.setElement(vfp, d, lane, esz, value);
            }
            if (op.quad()) {
                vfp.setWord(d + 1, vfp.word(d));
            }
            address += elementBytes;
        }
        writeBack(core, op.rn(), op.rm(), base, op.selem() * elementBytes);
    }

    /// Escrita de volta em `Rn` das 3 formas de NEON load/store (ARM DDI 0406C A7.7): `rm=15` =
    /// nenhuma; `rm=13` = imediata (`Rn += transferBytes`); qualquer outro = `Rn += R[rm]`.
    /// `rm=13` é o CÓDIGO da escrita imediata, NÃO o registrador `SP` — nunca ler `R13` aqui.
    private static void writeBack(ArmCore core, int rn, int rm, int base, int transferBytes) {
        if (rm == RM_NO_WRITEBACK) {
            return;
        }
        int delta = rm == RM_IMMEDIATE_WRITEBACK ? transferBytes : core.register(rm);
        core.setRegister(rn, base + delta);
    }

    /// `neon-shared`: `VCMLA`/`VCADD` (B13.17, `FEAT_FCMA`): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpComplexAdd}/{@link AdvSimdLanes#fpComplexMultiplyAccumulate}) — a
    /// MESMA função que o A64 reusará quando `FCMLA`/`FCADD` ganharem decoder. `lanes` conta
    /// elementos de `1 << esz` bytes (pares reais/imaginários adjacentes); nenhuma escrita
    /// destrutiva depois do laço (VFP32 nunca zera bits fora do registrador escrito).
    public void executeNeonComplex(ArmCore core, IrOp.NeonComplex op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        if (op.multiplyAccumulate()) {
            AdvSimdLanes.fpComplexMultiplyAccumulate(vfp, esz, lanes, op.vd(), op.vn(), op.vm(), op.rotation());
        } else {
            AdvSimdLanes.fpComplexAdd(vfp, esz, lanes, op.vd(), op.vn(), op.vm(), op.rotation());
        }
    }

    /// `neon-shared`: `VCMLA_scalar` (B13.17, `FEAT_FCMA`): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#fpComplexMultiplyAccumulateByElement}) — `vm` é sempre um `D` (nunca
    /// combinado com {@link IrOp.NeonComplexByElement#quad}).
    public void executeNeonComplexByElement(ArmCore core, IrOp.NeonComplexByElement op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.fpComplexMultiplyAccumulateByElement(
                vfp, esz, lanes, op.vd(), op.vn(), op.vm(), op.index(), op.rotation());
    }

    /// `neon-shared`: `VSDOT`/`VUDOT`/`VUSDOT` (B13.18, `FEAT_DotProd`/`FEAT_I8MM`): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#dotProduct}) — a MESMA função que o A64 reusará
    /// quando `SDOT_v`/`UDOT_v`/`USDOT`/`SUDOT` ganharem decoder (B19.12). Lanes de 32 bits sempre
    /// (o produto escalar nunca muda de largura); nenhuma escrita destrutiva depois do laço (VFP32
    /// nunca zera bits fora do registrador escrito).
    public void executeNeonDotProduct(ArmCore core, IrOp.NeonDotProduct op) {
        VfpRegisters vfp = core.vfp();
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / DOT_PRODUCT_LANE_BYTES;
        AdvSimdLanes.dotProduct(vfp, op.signedN(), op.signedM(), lanes, op.vd(), op.vn(), op.vm());
    }

    /// `neon-shared`: `VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/`VSUDOT_scalar` (B13.18):
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#dotProductByElement}) — `vm` é sempre um
    /// `D` (nunca combinado com {@link IrOp.NeonDotProductByElement#quad}).
    public void executeNeonDotProductByElement(ArmCore core, IrOp.NeonDotProductByElement op) {
        VfpRegisters vfp = core.vfp();
        int lanes = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / DOT_PRODUCT_LANE_BYTES;
        AdvSimdLanes.dotProductByElement(
                vfp, op.signedN(), op.signedM(), lanes, op.vd(), op.vn(), op.vm(), op.index());
    }

    /// `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#swapPermute}) — sem equivalente A64, a semântica nasce aqui (exceção do
    /// épico, mesma classe de {@link #executeNeonComplex}/{@link #executeNeonDotProduct}). `Vd`/`Vm`
    /// são fonte E destino; o núcleo já faz o buffer (E10).
    public void executeNeonSwapPermute(ArmCore core, IrOp.NeonSwapPermute op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elements = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / elementBytes;
        AdvSimdLanes.swapPermute(vfp, op.op(), esz, elements, op.vd(), op.vm());
    }

    /// `VEXT` (B13.14): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#extract}) — a MESMA
    /// função que o executor A64 chama para `EXT` (migração D1). NEON nunca zera bits fora do
    /// registrador escrito (ao contrário do A64, que zera `[127:64]` na forma `D` — disciplina do
    /// CHAMADOR, aqui não se aplica).
    public void executeNeonExtract(ArmCore core, IrOp.NeonExtract op) {
        VfpRegisters vfp = core.vfp();
        int datasizeBytes = op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES;
        AdvSimdLanes.extract(vfp, datasizeBytes, op.imm(), op.vd(), op.vn(), op.vm());
    }

    /// `VTBL`/`VTBX` (B13.14): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#tableLookup}) —
    /// a MESMA função que o executor A64 chama para `TBL`/`TBX` (migração D1). A tabela A32 é feita
    /// de registradores `D` (`wordsPerTableRegister=1`, ao contrário do `V` de 128 bits do A64,
    /// `=2`); sempre forma `D` (`indexCount=8`, sem forma `Q` neste encoding).
    public void executeNeonTableLookup(ArmCore core, IrOp.NeonTableLookup op) {
        VfpRegisters vfp = core.vfp();
        AdvSimdLanes.tableLookup(vfp, op.tbx(), op.len(), DOUBLEWORD_BYTES,
                1, VfpRegisters.DOUBLE_COUNT, op.vd(), op.vn(), op.vm());
    }

    /// `VDUP` escalar (B13.14, `VDUP_scalar`): lê o elemento {@link IrOp.NeonDuplicateScalar#index}
    /// de `Vm` e replica por todas as lanes de `Vd` — mesma disciplina de leitura/escrita do núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#element}/{@link AdvSimdLanes#setElement}), mas sem
    /// função própria em {@link AdvSimdLanes} (replicação é trivial demais para justificar um novo
    /// símbolo compartilhado — nenhum consumidor A64 usaria esta assinatura, que é toda em índice de
    /// `D`).
    public void executeNeonDuplicateScalar(ArmCore core, IrOp.NeonDuplicateScalar op) {
        VfpRegisters vfp = core.vfp();
        int esz = op.esz();
        long value = AdvSimdLanes.element(vfp, op.vm(), op.index(), esz);
        int elements = (op.quad() ? 2 * DOUBLEWORD_BYTES : DOUBLEWORD_BYTES) / (1 << esz);
        for (int i = 0; i < elements; i++) {
            AdvSimdLanes.setElement(vfp, op.vd(), i, esz, value);
        }
    }

    /// `AESE`/`AESD`/`AESMC`/`AESIMC` (B13.15): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdCrypto#aes}) — a MESMA função que o executor A64 chama desde esta task
    /// (migração D1). `Vd`/`Vm` já são índice de `D` PAR que inicia o `Q` — em
    /// {@link AdvSimdRegisterWords}, `D<n>` É a palavra `n`, então nenhuma conversão é necessária
    /// (ao contrário do lado A64, que multiplica por `WORDS_PER_REGISTER`).
    public void executeNeonCryptoAes(ArmCore core, IrOp.NeonCryptoAes op) {
        AdvSimdCrypto.aes(core.vfp(), op.op(), op.vd(), op.vm());
    }

    /// `SHA1H`/`SHA1SU1`/`SHA256SU0` (B13.15): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdCrypto#shaTwoRegister}) — a MESMA função que o executor A64 chama desde esta
    /// task (migração D1).
    public void executeNeonCryptoSha(ArmCore core, IrOp.NeonCryptoSha op) {
        AdvSimdCrypto.shaTwoRegister(core.vfp(), op.op(), op.vd(), op.vm());
    }
}
