package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Família "loop and branch insns" do perfil M (ARMv8.1-M, `target/isa-decode/t32.decode`):
/// `DLS`/`WLS`/`LE` (B15.6, Low Overhead Branch Extension) e, desde a B16.15, também as formas
/// tail-predicated (`DLSTP`/`WLSTP`/`LETP`/`LCTP`, que exigem MVE) + `VCTP` + os `BF*` (branch
/// future):
///
/// ```
/// DLS   1111 0 0000 100     rn:4 1110 0000 0000 0001 size=4
/// WLS   1111 0 0000 100     rn:4 1100 . .......... 1 imm=%lob_imm size=4
/// LE    1111 0 0000 0 f:1 tp:1 1111 1100 . .......... 1 imm=%lob_imm
/// WLSTP 1111 0 0000 0 size:2 rn:4 1100 . .......... 1 imm=%lob_imm
/// LCTP  1111 0 0000 000     1111 1110 0000 0000 0001
/// DLSTP 1111 0 0000 0 size:2 rn:4 1110 0000 0000 0001
/// VCTP  1111 0 0000 0 size:2 rn:4 1110 1000 0000 0001
/// BF*   1111 0 boff:4 ...   (boff != 0; BFL/BFCSEL/BF/BFX/BFLX, todos NOP)
/// %lob_imm 1:10 11:1 !function=times_2
/// ```
///
/// **Semântica medida contra `translate.c`/`mve_helper.c` reais do QEMU** (não deduzida dos
/// nomes): `LTPSIZE` (`FPSCR.LTPSIZE`, ver {@link dev.vitorsilverio.armjitter.core.FpscrRegister})
/// é gravado por `DLSTP`/`WLSTP` (`size`), restaurado a `4` por `LCTP` e pela saída de `LETP`, e
/// consumido por {@link dev.vitorsilverio.armjitter.core.MveVptState#elementMask} junto com `LR`.
/// **As formas `*TP`/`LCTP`/`VCTP` exigem `FEAT_MVE`** ({@link ArmFeature#MVE_INTEGER}) — sob um
/// preset só com `LOW_OVERHEAD_BRANCH` (sem MVE) continuam caindo em `UNIMPLEMENTED`, o mesmo que o
/// hardware real dá. `DLS`/`WLS`/`DLSTP`/`WLSTP`/`VCTP` com `Rn` = `SP`/`PC` são UNPREDICTABLE e o
/// QEMU escolhe UNDEF (idem aqui, G8); `Rn=15` nas formas `*TP` é, na verdade, outra instrução
/// (`LE`/`LCTP`), por isso a prioridade abaixo.
///
/// **`BF`/`BFL`/`BFCSEL`/`BFX`/`BFLX` são NOP**: a arquitetura permite descartar imediatamente o
/// cache `LO_BRANCH_INFO`, e o QEMU escolhe exatamente isso (`trans_BF`). `boff == 0` é o espaço
/// das instruções de loop acima ("SEE Related encodings"), nunca um `BF`.
///
/// **Achado central sobre o bit `f` de `LE`** (medido contra `trans_LE` do QEMU, não deduzido do
/// nome): `f=1` ("loop-forever") NÃO decrementa `LR` nem testa nada — é um branch incondicional de
/// volta ao início do loop. Só `f=0` decrementa/testa `LR`, e o teste ocorre ANTES do decremento:
/// se `LR` (não-assinado) `<=` decremento, a instrução NÃO desvia nem decrementa (a última
/// iteração já rodou); senão `LR -= decremento` e desvia de volta. O decremento é `1` em `LE` e
/// `1 << (4 - LTPSIZE)` em `LETP`. `f=1` com `tp=1` é combinação inválida (`UNDEFINED`).
///
/// **`LR`(R14) é literalmente o contador** — não há registrador oculto: `DLS Rn`/`WLS Rn` gravam
/// `Rn` em `LR`, `LE` decrementa `LR` direto. Um `BL`/`BLX` dentro do loop sobrescreve `LR` (mesmo
/// comportamento arquitetural do hardware real, não um bug deste projeto).
public final class Thumb2LowOverheadBranchDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    // ── `DLS` pura (`size=4`, sentinela sem forma tail-predicated): `1111 0000 0100 rn:4 1110
    // 0000 0000 0001` — bits[19:16]=Rn livre, resto fixo. ─────────────────────────────────────
    private static final int DLS_MASK = 0xFFF0_FFFF;
    private static final int DLS_VALUE = 0xF040_E001;

    // ── `WLS` pura (`size=4`): `1111 0000 0100 rn:4 1100 H imm10 1` — bits[19:16]=Rn,
    // bits[11]=H, bits[10:1]=imm10 livres. ─────────────────────────────────────────────────────
    private static final int WLS_MASK = 0xFFF0_F001;
    private static final int WLS_VALUE = 0xF040_C001;

    // ── `LE`/`LETP`: `1111 0000 00 f tp 1111 1100 H imm10 1` — bits[21]=f, bits[20]=tp,
    // bits[11]=H, bits[10:1]=imm10 livres. Checada ANTES de `WLSTP`/`DLSTP`/`LCTP` porque seu
    // padrão fixo (`Rn`-position=1111) é MAIS ESPECÍFICO — mesma prioridade que o `.decode` real
    // usa (`LE` listado antes do comentário "This is WLSTP"). ───────────────────────────────────
    private static final int LE_MASK = 0xFFCF_F001;
    private static final int LE_VALUE = 0xF00F_C001;
    private static final int LE_FOREVER_BIT = 21;
    private static final int LE_TAIL_PREDICATED_BIT = 20;

    // ── Formas tail-predicated com `size:2` em bits[21:20] e `Rn` em bits[19:16] (bit 22 = 0). ──
    // WLSTP: `... 1100 H imm10 1`; DLSTP: `... 1110 0000 0000 0001`; VCTP: `... 1110 1000 0000 0001`.
    private static final int WLSTP_MASK = 0xFFC0_F001;
    private static final int WLSTP_VALUE = 0xF000_C001;
    private static final int TAIL_PREDICATED_LOW_HALF_MASK = 0xFFC0_FFFF;
    private static final int DLSTP_VALUE = 0xF000_E001;
    private static final int VCTP_VALUE = 0xF000_E801;
    private static final int TAIL_SIZE_SHIFT = 20;
    private static final int TAIL_SIZE_MASK = 0x3;
    /// `LCTP` é o `DLSTP` com `size=0` e `Rn=15` (`1111 0000 0001 1111 1110 0000 0000 0001`).
    private static final int LCTP_RN = 15;

    // ── `BF*`: `1111 0 boff:4 ...` com `boff != 0` (bits[26:23]). Todas as formas terminam com
    // bit0=1 e hw2[15:12] = 1100 (`BFL`) ou 1110 (demais). ─────────────────────────────────────
    private static final int BF_COMMON_MASK = 0xF800_F001;
    private static final int BFL_VALUE = 0xF000_C001;
    private static final int BFCSEL_MASK = BF_COMMON_MASK | 0x0040_0000; // bit22 = 0
    private static final int BFCSEL_VALUE = 0xF000_E001;
    private static final int BF_MASK = BF_COMMON_MASK | 0x0060_0000; // bits[22:21] = 10
    private static final int BF_VALUE = 0xF040_E001;
    private static final int BFX_MASK = BF_COMMON_MASK | 0x0060_0FFE; // bits[22:21] = 11, resto zero
    private static final int BFX_VALUE = 0xF060_E001;
    private static final int BOFF_SHIFT = 23;
    private static final int BOFF_MASK = 0xF;

    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0xF;
    private static final int STACK_POINTER = 13;
    private static final int PROGRAM_COUNTER = 15;
    private static final int IMM_H_BIT = 11;
    private static final int IMM_LOW_SHIFT = 1;
    private static final int IMM_LOW_MASK = 0x3FF;
    private static final int IMM_LOW_BITS = 10;
    private static final int LOB_IMM_SCALE = 2; // `%lob_imm !function=times_2`

    /// Endereço de origem da instrução + 4 (leitura de `PC` em Thumb, ARM DDI 0553 A5.1) — base do
    /// destino de `WLS`/`LE`, mesma convenção de `ThumbDecoder` para `CBZ`/`B.W` (`address + 4 +
    /// offset`).
    private static final int PC_READ_OFFSET = 4;

    public Thumb2LowOverheadBranchDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.LOW_OVERHEAD_BRANCH)) {
            return null;
        }
        int pcBase = address + PC_READ_OFFSET;
        if (((raw >>> BOFF_SHIFT) & BOFF_MASK) != 0) {
            return isBranchFuture(raw) ? branchFutureNop(raw, address, condition) : null;
        }
        if ((raw & DLS_MASK) == DLS_VALUE) {
            int rn = (raw >>> RN_SHIFT) & RN_MASK;
            if (isUnpredictableLoopRegister(rn)) {
                return refused(raw, address, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_START, -1, rn, -1, 0, false, false, false);
        }
        if ((raw & WLS_MASK) == WLS_VALUE) {
            int rn = (raw >>> RN_SHIFT) & RN_MASK;
            if (isUnpredictableLoopRegister(rn)) {
                return refused(raw, address, condition);
            }
            int target = pcBase + decodeLobImmediate(raw);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_START, -1, rn, -1, target, false, false, true);
        }
        if ((raw & LE_MASK) == LE_VALUE) {
            boolean tailPredicated = ((raw >>> LE_TAIL_PREDICATED_BIT) & 1) != 0;
            boolean forever = ((raw >>> LE_FOREVER_BIT) & 1) != 0;
            if (tailPredicated && (forever || !architecture.has(ArmFeature.MVE_INTEGER))) {
                // `LETP` exige MVE, e `f=1` com `tp=1` é UNDEFINED (`trans_LE`).
                return null;
            }
            int target = pcBase - decodeLobImmediate(raw);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_END, -1, -1, -1, target, tailPredicated, false, forever);
        }
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        return decodeTailPredicated(raw, address, condition, pcBase);
    }

    /// `WLSTP`/`DLSTP`/`LCTP`/`VCTP` — só sob MVE (o chamador já checou).
    private DecodedInstruction decodeTailPredicated(int raw, int address, Condition condition, int pcBase) {
        int rn = (raw >>> RN_SHIFT) & RN_MASK;
        int size = (raw >>> TAIL_SIZE_SHIFT) & TAIL_SIZE_MASK;
        if ((raw & WLSTP_MASK) == WLSTP_VALUE) {
            if (isUnpredictableLoopRegister(rn)) {
                return refused(raw, address, condition);
            }
            int target = pcBase + decodeLobImmediate(raw);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_START, size, rn, -1, target, false, false, true);
        }
        int lowHalfForm = raw & TAIL_PREDICATED_LOW_HALF_MASK;
        if (lowHalfForm == DLSTP_VALUE) {
            if (rn == LCTP_RN) {
                // `LCTP` é o `DLSTP` com `Rn=15`, e só existe com `size=0`: o `.decode` real só lista
                // `000` (bits[22:20]) — as demais combinações são UNPREDICTABLE (QEMU: UNDEF).
                return size == 0
                        ? new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                                InstructionKind.LOOP_CLEAR_TAIL_PREDICATION, -1, -1, -1, 0, false, false, false)
                        : refused(raw, address, condition);
            }
            if (isUnpredictableLoopRegister(rn)) {
                return refused(raw, address, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_START, size, rn, -1, 0, false, false, false);
        }
        if (lowHalfForm == VCTP_VALUE) {
            if (isUnpredictableLoopRegister(rn)) {
                return refused(raw, address, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.VCTP, -1, rn, -1, size, false, false, false);
        }
        return null;
    }

    private static boolean isUnpredictableLoopRegister(int rn) {
        return rn == STACK_POINTER || rn == PROGRAM_COUNTER;
    }

    private static DecodedInstruction refused(int raw, int address, Condition condition) {
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
    }

    private static boolean isBranchFuture(int raw) {
        return (raw & BF_COMMON_MASK) == BFL_VALUE
                || (raw & BFCSEL_MASK) == BFCSEL_VALUE
                || (raw & BF_MASK) == BF_VALUE
                || (raw & BFX_MASK) == BFX_VALUE;
    }

    /// `BF*` = NOP (ver Javadoc da classe): `MSR` com máscara de campo vazia, o mesmo NOP que os
    /// hints de `Thumb2MiscDecoder` usam.
    private static DecodedInstruction branchFutureNop(int raw, int address, Condition condition) {
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MSR,
                0, -1, -1, 0, true, false, false);
    }

    /// `%lob_imm 1:10 11:1 !function=times_2`: `(bit[11]<<10) | bits[10:1]`, depois `*2`.
    private static int decodeLobImmediate(int raw) {
        int h = (raw >>> IMM_H_BIT) & 1;
        int low = (raw >>> IMM_LOW_SHIFT) & IMM_LOW_MASK;
        return ((h << IMM_LOW_BITS) | low) * LOB_IMM_SCALE;
    }
}
