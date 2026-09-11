package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.PointerAuthInPlace} (B19.15, subgrupo "Data-processing (1 source)"
/// de "Data Processing — Register", `opcode2=00001`, `FEAT_PAuth`) — as formas de propósito geral
/// que assinam/autenticam/removem a assinatura de um ponteiro IN-PLACE (`Xd`). Só documentam qual
/// mnemônico foi decodificado (fidelidade de desmontagem): sob a rota (b) registrada na task (mesmo
/// precedente de {@link Ir64Op.PointerAuthGeneric}, `PACGA`), nenhuma autenticação real é modelada
/// — a chave A-vs-B e o modificador (`Xn`, quando existe) não afetam o resultado observável.
public enum Ir64PointerAuthOp {
    /// `PACIA Xd, Xn` — assina `Xd` (ponteiro de INSTRUÇÃO) com a chave A e modificador `Xn`.
    PACIA,
    /// `PACIB Xd, Xn` — mesmo que {@link #PACIA}, chave B.
    PACIB,
    /// `PACDA Xd, Xn` — assina `Xd` (ponteiro de DADO) com a chave A e modificador `Xn`.
    PACDA,
    /// `PACDB Xd, Xn` — mesmo que {@link #PACDA}, chave B.
    PACDB,
    /// `AUTIA Xd, Xn` — autentica `Xd` (ponteiro de INSTRUÇÃO) com a chave A e modificador `Xn`.
    AUTIA,
    /// `AUTIB Xd, Xn` — mesmo que {@link #AUTIA}, chave B.
    AUTIB,
    /// `AUTDA Xd, Xn` (uma das 10 linhas residuais nomeadas pela task) — autentica `Xd` (ponteiro
    /// de DADO) com a chave A e modificador `Xn`.
    AUTDA,
    /// `AUTDB Xd, Xn` — mesmo que {@link #AUTDA}, chave B.
    AUTDB,
    /// `XPACI Xd` (uma das 10 linhas residuais) — remove o campo de autenticação de um ponteiro de
    /// INSTRUÇÃO, sem verificar (usado por unwinders/depuradores).
    XPACI,
    /// `XPACD Xd` (uma das 10 linhas residuais) — mesmo que {@link #XPACI}, ponteiro de DADO.
    XPACD
}
