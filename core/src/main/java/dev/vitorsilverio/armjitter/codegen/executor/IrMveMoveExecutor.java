package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.MveVptState;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.MveMoveOp;
import dev.vitorsilverio.armjitter.ir.MvePredicationOp;

/// Executa os movimentos MVE (perfil M, B16, MVE/Helium) da IR interpretada: load/store contíguo,
/// alargante, gather/scatter e intercalado, além de `VDUP`/`VIDUP`/`VIWDUP`, `VMOV` entre lanes e GPR
/// e `VMOV` imediato.
///
/// É a única família MVE com estado (os acessos à memória passam por `IrExecutionSupport`, que conhece
/// a arquitetura): {@link IrBlockExecutor#mveMoveExecutor()} cria a instância na primeira op (task
/// E15.8).
public final class IrMveMoveExecutor {
    private final IrExecutionSupport support;

    IrMveMoveExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// `VLDR_VSTR` (perfil M, B16.3, MVE/Helium): move os 128 bits de `Qd` de/para memória, BYTE a
    /// BYTE (Armadilha 5 da task — predicação por byte de memória, nunca um único acesso de 128
    /// bits quando a máscara não é cheia), usando {@link FpscrRegister#ltpsize()} (mesma
    /// convenção de {@link IrMvePredicationExecutor#executeVpsel}: `LTPSIZE`/`LR` correntes, B16.15).
    /// Mesma checagem de `ECI` reservado que toda instrução MVE beatwise faz ({@code
    /// mve_eci_check} real) ANTES de tocar memória/registrador — ver {@link IrMvePredicationExecutor#executeVpst}.
    ///
    /// Writeback de `Rn` é SEMPRE incondicional (G4, Armadilha 6 da task): roda mesmo quando a
    /// instrução inteira está mascarada (`elementMask == 0`) — o QEMU real trata o cálculo de
    /// endereço/writeback como efeito ESCALAR da instrução, fora do laço "por beat" que aplica a
    /// máscara.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}) — {@link
    ///         dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} usa isto para pular o
    ///         {@link MvePredicationOp.AdvanceVpt} seguinte no mesmo bloco.
    public boolean executeMveLoadStore(ArmCore core, MveMoveOp.LoadStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int base = core.register(op.rn());
        int accessAddress = op.postIndexed() ? base : base + op.offset();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        VfpRegisters vfp = core.vfp();
        for (int byteIndex = 0; byteIndex < 16; byteIndex++) {
            if (((mask >>> byteIndex) & 1) == 0) {
                continue;
            }
            int byteAddress = accessAddress + byteIndex;
            if (op.load()) {
                int value = support.read8Arm7(core, byteAddress);
                vfp.setElement(op.qd(), byteIndex, 0, value);
            } else {
                long value = vfp.element(op.qd(), byteIndex, 0);
                support.write8Arm7(core, byteAddress, (int) value);
            }
        }
        if (op.writeback()) {
            core.setRegister(op.rn(), base + op.offset());
        }
        return false;
    }

    /// `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (perfil M, B16.4, MVE/Helium): load que alarga (extensão de
    /// sinal/zero) ou store que estreita (truncamento), predicados por ELEMENTO (não por byte —
    /// diferente de {@link #executeMveLoadStore}, ver Javadoc de {@link MveMoveOp.WideningLoadStore}).
    /// Usa {@link IrExecutionSupport#readVectorElement}/{@link IrExecutionSupport#writeVectorElement}
    /// (sem o quirk de rotação de acesso desalinhado do ARM7 — `LDR`/`LDRH`, não aplicável a
    /// elemento vetorial, mesmo precedente NEON da B13.3) em vez de {@code read8Arm7}, já que aqui o
    /// elemento tem largura real (`1`/`2` bytes), não sempre `1` como em {@link #executeMveLoadStore}.
    ///
    /// No load, distingue duas máscaras (verbatim de `DO_VLDR`, `target/arm/tcg/mve_helper.c`):
    /// {@link MveVptState#eciMask} sozinho decide se a lane é tocada (beat abandonado = não
    /// tocada, comportamento UNKNOWN implementado como preservar); dentro disso,
    /// {@link MveVptState#elementMask} (que já inclui `eciMask`) decide entre carregar de verdade
    /// ou gravar ZERO (predicado `VPT` falhou, mas o beat está ativo). No store, só
    /// {@link MveVptState#elementMask} importa (mesmo padrão de {@link #executeMveLoadStore}).
    ///
    /// Writeback de `Rn` é SEMPRE incondicional (G4), mesma regra de {@link #executeMveLoadStore}.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveWideningLoadStore(ArmCore core, MveMoveOp.WideningLoadStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int base = core.register(op.rn());
        int accessAddress = op.postIndexed() ? base : base + op.offset();
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int fullMask = MveVptState.elementMask(vpr, itState, core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int eciMask = MveVptState.eciMask(itState);
        VfpRegisters vfp = core.vfp();
        int registerSizeLog2 = op.registerSizeLog2();
        int memorySizeLog2 = op.memorySizeLog2();
        int memoryElementBytes = 1 << memorySizeLog2;
        int elementCount = 16 >>> registerSizeLog2;
        int address = accessAddress;
        for (int e = 0; e < elementCount; e++) {
            int b = e << registerSizeLog2;
            if (op.load()) {
                if (((eciMask >>> b) & 1) != 0) {
                    long value;
                    if (((fullMask >>> b) & 1) != 0) {
                        long raw = support.readVectorElement(core, address, memorySizeLog2);
                        value = op.signed() ? AdvSimdLanes.signExtend(raw, memorySizeLog2) : raw;
                        value = AdvSimdLanes.truncate(value, registerSizeLog2);
                    } else {
                        value = 0;
                    }
                    vfp.setElement(op.qd(), e, registerSizeLog2, value);
                }
            } else if (((fullMask >>> b) & 1) != 0) {
                long narrowed = AdvSimdLanes.truncate(vfp.element(op.qd(), e, registerSizeLog2), memorySizeLog2);
                support.writeVectorElement(core, address, memorySizeLog2, narrowed);
                core.notifyOrdinaryWrite(address, memoryElementBytes);
            }
            address += memoryElementBytes;
        }
        if (op.writeback()) {
            core.setRegister(op.rn(), base + op.offset());
        }
        return false;
    }

    /// Log2 do tamanho de um registrador `Q` inteiro em palavras de 32 bits (`16 / 4`) — usado só
    /// pelo laço de 4 iterações de {@link #executeMveGatherScatterOffset} no ramo `registerSizeLog2
    /// == 3` (`DO_VLDR64_SG`/`DO_VSTR64_SG` reais SEMPRE iteram em passos de 4 bytes, mesmo movendo
    /// dados de 64 bits — ver Javadoc de {@link MveMoveOp.GatherScatterOffset#registerSizeLog2}).
    private static final int DOUBLEWORD_LOG2 = 3;

    private static final int WORD_LOG2 = 2;

    /// `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (perfil M, B16.5, MVE/Helium): gather/scatter por vetor de
    /// offsets. Dois ramos, verbatim do QEMU real (`target/arm/tcg/mve_helper.c`): `registerSizeLog2
    /// < 3` usa `DO_VLDR_SG`/`DO_VSTR_SG` (offset lido de `qm` na MESMA largura do registrador,
    /// endereço por lane independente); `registerSizeLog2 == 3` usa `DO_VLDR64_SG`/`DO_VSTR64_SG`
    /// (par de acessos de 32 bits, offset lido só das lanes PARES de 32 bits de `qm` — Javadoc do
    /// `IrOp` explica o porquê). Mesma disciplina de duas máscaras de {@link
    /// #executeMveWideningLoadStore}: `eciMask` sozinho decide se a lane é tocada; `elementMask`
    /// decide entre carregar de verdade ou gravar ZERO (load) — no store só `elementMask` importa.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveGatherScatterOffset(ArmCore core, MveMoveOp.GatherScatterOffset op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int base = core.register(op.rn());
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int fullMask = MveVptState.elementMask(vpr, itState, core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int eciMask = MveVptState.eciMask(itState);
        VfpRegisters vfp = core.vfp();
        int memorySizeLog2 = op.memorySizeLog2();
        int registerSizeLog2 = op.registerSizeLog2();
        if (registerSizeLog2 == DOUBLEWORD_LOG2) {
            for (int e = 0; e < 4; e++) {
                int b = e << WORD_LOG2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                int offsetLane = e & ~1;
                long offsetValue = vfp.element(op.qm(), offsetLane, WORD_LOG2);
                int addr = base + (op.offsetScaled()
                        ? (int) (offsetValue << memorySizeLog2) : (int) offsetValue);
                addr += 4 * (e & 1);
                if (op.load()) {
                    long value = ((fullMask >>> b) & 1) != 0
                            ? support.readVectorElement(core, addr, WORD_LOG2) : 0;
                    vfp.setElement(op.qd(), e, WORD_LOG2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long value = vfp.element(op.qd(), e, WORD_LOG2);
                    support.writeVectorElement(core, addr, WORD_LOG2, value);
                    core.notifyOrdinaryWrite(addr, 1 << WORD_LOG2);
                }
            }
        } else {
            int elementCount = 16 >>> registerSizeLog2;
            for (int e = 0; e < elementCount; e++) {
                int b = e << registerSizeLog2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                long offsetValue = vfp.element(op.qm(), e, registerSizeLog2);
                int addr = base + (op.offsetScaled()
                        ? (int) (offsetValue << memorySizeLog2) : (int) offsetValue);
                if (op.load()) {
                    long value;
                    if (((fullMask >>> b) & 1) != 0) {
                        long raw = support.readVectorElement(core, addr, memorySizeLog2);
                        value = op.signedLoad() ? AdvSimdLanes.signExtend(raw, memorySizeLog2) : raw;
                        value = AdvSimdLanes.truncate(value, registerSizeLog2);
                    } else {
                        value = 0;
                    }
                    vfp.setElement(op.qd(), e, registerSizeLog2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long narrowed = AdvSimdLanes.truncate(
                            vfp.element(op.qd(), e, registerSizeLog2), memorySizeLog2);
                    support.writeVectorElement(core, addr, memorySizeLog2, narrowed);
                    core.notifyOrdinaryWrite(addr, 1 << memorySizeLog2);
                }
            }
        }
        return false;
    }

    /// `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (perfil M, B16.5, MVE/Helium):
    /// gather/scatter com base vetorial `qm` (cada lane já é um ENDEREÇO) mais {@code op.offset()}
    /// escalar somado a todas as lanes — mesma fórmula de endereço de {@link
    /// #executeMveGatherScatterOffset} com os papéis de "base"/"offset" trocados (`do_ldst_sg_imm`
    /// real). Writeback é POR LANE, gated só por `eciMask` (roda mesmo quando `elementMask` zera a
    /// lane) — diferente do writeback escalar único de {@link #executeMveLoadStore}.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveGatherScatterImmediate(ArmCore core, MveMoveOp.GatherScatterImmediate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int fullMask = MveVptState.elementMask(vpr, itState, core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int eciMask = MveVptState.eciMask(itState);
        VfpRegisters vfp = core.vfp();
        int sizeLog2 = op.sizeLog2();
        if (sizeLog2 == DOUBLEWORD_LOG2) {
            for (int e = 0; e < 4; e++) {
                int b = e << WORD_LOG2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                int offsetLane = e & ~1;
                int laneBase = (int) vfp.element(op.qm(), offsetLane, WORD_LOG2);
                int addr = laneBase + op.offset() + (4 * (e & 1));
                if (op.load()) {
                    long value = ((fullMask >>> b) & 1) != 0
                            ? support.readVectorElement(core, addr, WORD_LOG2) : 0;
                    vfp.setElement(op.qd(), e, WORD_LOG2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long value = vfp.element(op.qd(), e, WORD_LOG2);
                    support.writeVectorElement(core, addr, WORD_LOG2, value);
                    core.notifyOrdinaryWrite(addr, 1 << WORD_LOG2);
                }
                if (op.writeback() && (e & 1) != 0) {
                    vfp.setElement(op.qm(), offsetLane, WORD_LOG2, Integer.toUnsignedLong(addr - 4));
                }
            }
        } else {
            int elementCount = 16 >>> sizeLog2;
            for (int e = 0; e < elementCount; e++) {
                int b = e << sizeLog2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                int laneBase = (int) vfp.element(op.qm(), e, sizeLog2);
                int addr = laneBase + op.offset();
                if (op.load()) {
                    long value = ((fullMask >>> b) & 1) != 0
                            ? support.readVectorElement(core, addr, sizeLog2) : 0;
                    vfp.setElement(op.qd(), e, sizeLog2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long value = vfp.element(op.qd(), e, sizeLog2);
                    support.writeVectorElement(core, addr, sizeLog2, value);
                    core.notifyOrdinaryWrite(addr, 1 << sizeLog2);
                }
                if (op.writeback()) {
                    vfp.setElement(op.qm(), e, sizeLog2, Integer.toUnsignedLong(addr));
                }
            }
        }
        return false;
    }

    /// Tabelas `off[]` de {@link #executeMveInterleavedLoadStore}, transcritas verbatim de
    /// `DO_VLD2B`/`DO_VLD4B`/`DO_VLD2H`/`DO_VLD4H`/`DO_VLD2W`/`DO_VLD4W` (idênticas para as formas
    /// `VST*`, `target/arm/tcg/mve_helper.c`) — cada linha é um `pat` (`0`-`3`; grupo `2` só usa
    /// `pat` `0`/`1`).
    private static final int[][] INTERLEAVE_OFF_BYTE_4 = {
            {0, 1, 10, 11}, {2, 3, 12, 13}, {4, 5, 14, 15}, {6, 7, 8, 9}
    };

    private static final int[][] INTERLEAVE_OFF_BYTE_2 = {
            {0, 2, 12, 14}, {4, 6, 8, 10}
    };

    /// `DO_VLD4H(op, O1, O2)` monta `off[4] = {O1, O1, O2, O2}` a partir de só 2 valores por `pat`.
    private static final int[][] INTERLEAVE_HALFWORD_PAIR_4 = {
            {0, 5}, {1, 6}, {2, 7}, {3, 4}
    };

    private static final int[][] INTERLEAVE_OFF_HALFWORD_2 = {
            {0, 1, 6, 7}, {2, 3, 4, 5}
    };

    private static final int[][] INTERLEAVE_OFF_WORD_2 = {
            {0, 4, 24, 28}, {8, 12, 16, 20}
    };

    private static int[] interleaveOffsetTable(int groupSize, int sizeLog2, int pat) {
        return switch (sizeLog2) {
            case 0 -> groupSize == 4 ? INTERLEAVE_OFF_BYTE_4[pat] : INTERLEAVE_OFF_BYTE_2[pat];
            case 1 -> groupSize == 4
                    ? expandHalfwordPair(INTERLEAVE_HALFWORD_PAIR_4[pat])
                    : INTERLEAVE_OFF_HALFWORD_2[pat];
            case 2 -> groupSize == 4 ? INTERLEAVE_OFF_BYTE_4[pat] : INTERLEAVE_OFF_WORD_2[pat];
            default -> throw new IllegalArgumentException("sizeLog2 inválido para VLD2/VLD4: " + sizeLog2);
        };
    }

    private static int[] expandHalfwordPair(int[] pair) {
        return new int[] {pair[0], pair[0], pair[1], pair[1]};
    }

    /// `VLD2`/`VLD4`/`VST2`/`VST4` (perfil M, B16.5, MVE/Helium): desentrelaçamento/entrelaçamento
    /// em 4 beats de 32 bits, transcrito verbatim de `DO_VLD2*`/`DO_VLD4*`/`DO_VST2*`/`DO_VST4*`
    /// (`target/arm/tcg/mve_helper.c`) — três formas de endereço/empacotamento por
    /// {@link MveMoveOp.InterleavedLoadStore#sizeLog2} (byte/halfword/word), cada uma com sua própria
    /// aritmética de deslocamento de bits (ver os comentários por `case`, fiéis ao C original).
    /// **Só `eciMask` gate cada beat** (nenhum `elementMask`/`VPT` — comentário literal do QEMU
    /// real: "beatwise but not predicated"). Writeback incondicional (G4), soma
    /// `groupSize * 16` bytes.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveInterleavedLoadStore(ArmCore core, MveMoveOp.InterleavedLoadStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int base = core.register(op.rn());
        int eciMask = MveVptState.eciMask(core.cpsr().itState());
        int[] off = interleaveOffsetTable(op.groupSize(), op.sizeLog2(), op.pat());
        VfpRegisters vfp = core.vfp();
        int qnidx = op.qd();
        int o1 = off[0];
        switch (op.sizeLog2()) {
            case 0 -> { // DO_VLD4B/DO_VLD2B/DO_VST4B/DO_VST2B: uma word por beat, 4 bytes empacotados
                int byteScale = op.groupSize() == 4 ? 4 : 2;
                for (int beat = 0; beat < 4; beat++) {
                    if (((eciMask >>> (beat << 2)) & 1) == 0) {
                        continue;
                    }
                    int addr = base + off[beat] * byteScale;
                    if (op.load()) {
                        long data = support.readVectorElement(core, addr, WORD_LOG2);
                        for (int e = 0; e < 4; e++, data >>>= 8) {
                            int qd = op.groupSize() == 4 ? qnidx + e : qnidx + (e & 1);
                            int lane = op.groupSize() == 4 ? off[beat] : off[beat] + (e >> 1);
                            vfp.setElement(qd, lane, 0, data & 0xFF);
                        }
                    } else {
                        long data = 0;
                        for (int e = 3; e >= 0; e--) {
                            int qd = op.groupSize() == 4 ? qnidx + e : qnidx + (e & 1);
                            int lane = op.groupSize() == 4 ? off[beat] : off[beat] + (e >> 1);
                            data = (data << 8) | vfp.element(qd, lane, 0);
                        }
                        support.writeVectorElement(core, addr, WORD_LOG2, data);
                        core.notifyOrdinaryWrite(addr, 4);
                    }
                }
            }
            case 1 -> { // DO_VLD4H/DO_VLD2H/DO_VST4H/DO_VST2H
                int y = 0;
                for (int beat = 0; beat < 4; beat++) {
                    if (((eciMask >>> (beat << 2)) & 1) == 0) {
                        y ^= op.groupSize() == 4 ? 2 : 0;
                        continue;
                    }
                    if (op.groupSize() == 4) {
                        int addr = base + off[beat] * 8 + (beat & 1) * 4;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qnidx + y, off[beat], 1, data & 0xFFFF);
                            vfp.setElement(qnidx + y + 1, off[beat], 1, (data >>> 16) & 0xFFFF);
                        } else {
                            long data = vfp.element(qnidx + y, off[beat], 1)
                                    | (vfp.element(qnidx + y + 1, off[beat], 1) << 16);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                        y ^= 2;
                    } else {
                        int addr = base + off[beat] * 4;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qnidx, off[beat], 1, data & 0xFFFF);
                            vfp.setElement(qnidx + 1, off[beat], 1, (data >>> 16) & 0xFFFF);
                        } else {
                            long data = vfp.element(qnidx, off[beat], 1)
                                    | (vfp.element(qnidx + 1, off[beat], 1) << 16);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                    }
                }
            }
            case 2 -> { // DO_VLD4W/DO_VLD2W/DO_VST4W/DO_VST2W
                for (int beat = 0; beat < 4; beat++) {
                    if (((eciMask >>> (beat << 2)) & 1) == 0) {
                        continue;
                    }
                    if (op.groupSize() == 4) {
                        int addr = base + off[beat] * 4;
                        int y = (beat + (o1 & 2)) & 3;
                        int lane = off[beat] >>> 2;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qnidx + y, lane, WORD_LOG2, data);
                        } else {
                            long data = vfp.element(qnidx + y, lane, WORD_LOG2);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                    } else {
                        int addr = base + off[beat];
                        int qd = qnidx + (beat & 1);
                        int lane = off[beat] >>> 3;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qd, lane, WORD_LOG2, data);
                        } else {
                            long data = vfp.element(qd, lane, WORD_LOG2);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("sizeLog2 inválido para VLD2/VLD4: " + op.sizeLog2());
        }
        if (op.writeback()) {
            core.setRegister(op.rn(), base + op.groupSize() * 16);
        }
        return false;
    }

    /// `VIDUP`/`VDDUP` (perfil M, B16.5, MVE/Helium): verbatim de `DO_VIDUP` — grava `Rn` (truncado
    /// ao elemento) em cada lane sucessiva de `Qd`, mascarado por `elementMask` (`mergemask`, lane
    /// mascarada preserva o valor atual), e acumula `Rn += imm` livremente (SEM truncar o valor
    /// escalar) a cada lane, gravando o resultado final de volta em `Rn`.
    public boolean executeMveIncrementDup(ArmCore core, MveMoveOp.IncrementDup op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        VfpRegisters vfp = core.vfp();
        int sizeLog2 = op.sizeLog2();
        int offset = core.register(op.rn());
        int elementCount = 16 >>> sizeLog2;
        for (int e = 0; e < elementCount; e++) {
            int b = e << sizeLog2;
            if (((mask >>> b) & 1) != 0) {
                vfp.setElement(op.qd(), e, sizeLog2, AdvSimdLanes.truncate(offset, sizeLog2));
            }
            offset += op.imm();
        }
        core.setRegister(op.rn(), offset);
        return false;
    }

    /// `VIWDUP`/`VDWDUP` (perfil M, B16.5, MVE/Helium): como {@link #executeMveIncrementDup}, mas
    /// o passo envolve (wrap) contra `Rm` — `do_add_wrap`/`do_sub_wrap` verbatim.
    public boolean executeMveWrappingIncrementDup(ArmCore core, MveMoveOp.WrappingIncrementDup op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        VfpRegisters vfp = core.vfp();
        int sizeLog2 = op.sizeLog2();
        int offset = core.register(op.rn());
        int wrap = core.register(op.rm());
        int elementCount = 16 >>> sizeLog2;
        for (int e = 0; e < elementCount; e++) {
            int b = e << sizeLog2;
            if (((mask >>> b) & 1) != 0) {
                vfp.setElement(op.qd(), e, sizeLog2, AdvSimdLanes.truncate(offset, sizeLog2));
            }
            if (op.decrement()) {
                if (offset == 0) {
                    offset = wrap;
                }
                offset -= op.imm();
            } else {
                offset += op.imm();
                if (offset == wrap) {
                    offset = 0;
                }
            }
        }
        core.setRegister(op.rn(), offset);
        return false;
    }

    /// `VDUP` (perfil M, B16.13a, MVE/Helium): replica `Rt` (truncado a `1 << esz` bytes) em todas
    /// as lanes ATIVAS de `Qd` (ver Javadoc de {@link MveMoveOp.VectorDup} para o achado `%qn`, já
    /// resolvido pelo decoder — aqui `op.qd()` já é o índice correto).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public boolean executeMveVectorDup(ArmCore core, MveMoveOp.VectorDup op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int esz = op.esz();
        int elementBytes = 1 << esz;
        int elementByteMask = elementBytes == 4 ? 0xF : (elementBytes == 2 ? 0x3 : 0x1);
        int lanes = 16 / elementBytes;
        long value = core.register(op.rt()) & 0xFFFF_FFFFL;
        for (int lane = 0; lane < lanes; lane++) {
            int laneMask = (byteMask >>> (lane * elementBytes)) & elementByteMask;
            if (laneMask == 0) {
                continue;
            }
            long current = vfp.element(op.qd(), lane, esz);
            vfp.setElement(op.qd(), lane, esz, AdvSimdLanes.mergeMaskedBytes(current, value, laneMask, elementBytes));
        }
        return false;
    }

    /// `VMOV_to_2gp`/`VMOV_from_2gp` (perfil M, B16.13a, MVE/Helium): **NÃO predicado por `VPR`**
    /// (ver Javadoc de {@link MveMoveOp.MoveLanesGpr}) — só o `ECI` reservado pode faultar.
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public boolean executeMveMoveLanesGpr(ArmCore core, MveMoveOp.MoveLanesGpr op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        final int WORD_ESZ = 2;
        int laneLow = op.idx();
        int laneHigh = op.idx() + 2;
        if (op.toGpr()) {
            core.setRegister(op.rt(), (int) vfp.element(op.qd(), laneLow, WORD_ESZ));
            core.setRegister(op.rt2(), (int) vfp.element(op.qd(), laneHigh, WORD_ESZ));
        } else {
            vfp.setElement(op.qd(), laneLow, WORD_ESZ, core.register(op.rt()) & 0xFFFF_FFFFL);
            vfp.setElement(op.qd(), laneHigh, WORD_ESZ, core.register(op.rt2()) & 0xFFFF_FFFFL);
        }
        return false;
    }

    /// `Vimm_1r` (perfil M, B16.13a, MVE/Helium): `VORR`/`VBIC`/`VMOV`/`VMVN` de imediato modificado
    /// já resolvido pelo decoder (ver Javadoc de {@link MveMoveOp.VectorModifiedImmediate}) — predicado
    /// por byte a granularidade de palavra de 64 bits (`DO_1OP_IMM`/`mergemask` real).
    ///
    /// @return `true` quando faultou (ver {@link IrMvePredicationExecutor#executeVpst}).
    public boolean executeMveVectorModifiedImmediate(ArmCore core, MveMoveOp.VectorModifiedImmediate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return IrMveSupport.faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int byteMask = MveVptState.elementMask(vpr, core.cpsr().itState(), core.fpscr().ltpsize(), core.register(IrMveSupport.LINK_REGISTER));
        int wordLow = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        applyMveModifiedImmediate(vfp, op, wordLow, byteMask & 0xFF);
        applyMveModifiedImmediate(vfp, op, wordLow + 1, (byteMask >>> 8) & 0xFF);
        return false;
    }

    private static void applyMveModifiedImmediate(VfpRegisters vfp, MveMoveOp.VectorModifiedImmediate op, int word,
            int wordByteMask) {
        if (wordByteMask == 0) {
            return;
        }
        long current = vfp.d(word);
        long result = switch (op.op()) {
            case MOV -> op.imm64();
            case MVN -> ~op.imm64();
            case ORR -> current | op.imm64();
            case BIC -> current & ~op.imm64();
        };
        final int WORD_BYTES = 8;
        vfp.setD(word, AdvSimdLanes.mergeMaskedBytes(current, result, wordByteMask, WORD_BYTES));
    }
}
