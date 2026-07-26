package dev.vitorsilverio.armjitter.ir64;

/// Operação selecionada por {@link Ir64Op.Alu64} (forma imediata) e por
/// {@link Ir64Op.AluShiftedRegister}/{@link Ir64Op.AluExtendedRegister} (formas registrador,
/// só `ADD`/`SUB` — não há `AND`/`ORR`/`EOR` por registrador nesta ISA sem um SEGUNDO registrador
/// de operando, que é `Logical (shifted register)`, fora do escopo fechado do épico B6, ver a
/// task B6.3.1).
///
/// `AND`/`ORR`/`EOR` (imediato) — decode do campo `dbm` (`N:immr:imms` → bitmask via
/// `DecodeBitMasks`) chegou em B6.3.1. `ANDS` (imediato) reaproveita {@link #AND} com
/// `Ir64Op.Alu64#setFlags() setFlags=true` (D2 da task B6.3.1) — não há uma constante `ANDS`
/// dedicada: os flags `C=0,V=0` sempre, para as 3 operações lógicas, já são resolvidos pelo
/// EXECUTOR (`Ir64BlockExecutor#logicalWithFlags`), não pelo opcode.
public enum Ir64AluOp {
    /// `ADD` (imediato, shifted register, extended register).
    ADD,
    /// `SUB` (imediato, shifted register, extended register).
    SUB,
    /// `AND`/`ANDS` (imediato) — `ANDS` é este mesmo valor com `setFlags=true` (D2).
    AND,
    /// `ORR` (imediato).
    ORR,
    /// `EOR` (imediato).
    EOR
}
