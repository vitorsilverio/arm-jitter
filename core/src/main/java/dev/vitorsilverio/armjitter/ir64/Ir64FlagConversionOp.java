package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.ConvertFlags} (B8.2, `FEAT_FlagM2`) — as 3 instruções da classe
/// "System" que manipulam `PSTATE.{N,Z,C,V}` diretamente, sem operando de registrador geral.
public enum Ir64FlagConversionOp {
    /// `CFINV` (`ARM DDI 0487 C6.2.43`): inverte `PSTATE.C`, os outros 3 flags não mudam.
    INVERT_CARRY,
    /// `XAFLAG` (`ARM DDI 0487 C6.2.416`, "conversão de flags eXternal para Arm"): recalcula
    /// `PSTATE.{N,Z,C,V}` a partir do formato "eXternal" (usado por sequências vetoriais de
    /// comparação lane-a-lane que produzem flags per-lane e depois reduzem para um resultado
    /// escalar via `AND`/`ORR`) — ver {@code Ir64BlockExecutor#executeConvertFlags}.
    EXTERNAL_TO_ARM,
    /// `AXFLAG` (`ARM DDI 0487 C6.2.16`, "conversão de flags Arm para eXternal"): inverso de
    /// {@link #EXTERNAL_TO_ARM}.
    ARM_TO_EXTERNAL
}
