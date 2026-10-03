package dev.vitorsilverio.armjitter.ir;

/// Máscaras de registradores de propósito geral (`r0..r15`, um bit por registrador) usadas por
/// {@link IrOp#regUse()}/{@link IrOp#regDef()} (task E15.6).
final class GprMask {
    private GprMask() {
    }

    /// Índice do `PC` (`R15`).
    static final int PC_INDEX = 15;
    /// Bit do `SP` (`R13`).
    static final int SP = 1 << 13;
    /// Bit do `LR` (`R14`).
    static final int LR = 1 << 14;
    /// Bit do `PC` (`R15`).
    static final int PC = 1 << PC_INDEX;
    /// Todos os registradores `r0..r15`.
    static final int ALL = 0xFFFF;
    /// `r8..r14`: os registradores que a troca de modo pode bancar.
    static final int BANKED_R8_R14 = 0x7F00;
    /// `r0..r7`: os registradores que nenhum modo banca.
    static final int UNBANKED_R0_R7 = 0xFF;

    /// `rm` de NEON load/store (`VLDn`/`VSTn`, ARM DDI 0406C A7.7) sem writeback.
    static final int NEON_RM_NO_WRITEBACK = 15;
    /// `rm` de NEON load/store com writeback imediato (`Rn += bytes transferidos`) — código, não o `SP`.
    static final int NEON_RM_IMMEDIATE_WRITEBACK = 13;

    /// `regUse` de NEON load/store: a base `rn` e, no writeback por registrador, `rm`.
    static int neonLoadStoreUse(int rn, int rm) {
        boolean registerWriteback = rm != NEON_RM_NO_WRITEBACK && rm != NEON_RM_IMMEDIATE_WRITEBACK;
        return (1 << rn) | (registerWriteback ? (1 << rm) : 0);
    }

    /// `regDef` de NEON load/store: `rn` quando há writeback.
    static int neonLoadStoreDef(int rn, int rm) {
        return rm == NEON_RM_NO_WRITEBACK ? 0 : 1 << rn;
    }

    /// `regUse` de toda op MVE que consulta o predicado de elementos: o `LR` é o contador de
    /// tail-predication que `MveVptState#elementMask` lê quando `FPSCR.LTPSIZE < 4` (B16.15).
    static final int MVE_TAIL_PREDICATION = LR;
}
