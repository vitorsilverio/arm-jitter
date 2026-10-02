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
}
