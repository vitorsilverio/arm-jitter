package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
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
}
