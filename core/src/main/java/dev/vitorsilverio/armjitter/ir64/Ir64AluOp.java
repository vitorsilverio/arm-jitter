package dev.vitorsilverio.armjitter.ir64;

/// Operação selecionada por {@link Ir64Op.Alu64}.
///
/// `AND`/`ORR`/`EOR` fazem parte do formato desde já (o registro precisa expressá-las), mas o
/// decode da forma imediata ("logical immediate", `N:immr:imms` → bitmask) fica FORA da fatia
/// B6.1 — ver a task: é o encoding mais traiçoeiro do A64 e entra só em B6.3, transcrito do
/// pseudocódigo `DecodeBitMasks` do manual. {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder}
/// só produz {@link #ADD}/{@link #SUB} nesta task.
public enum Ir64AluOp {
    /// `ADD (immediate)`.
    ADD,
    /// `SUB (immediate)`.
    SUB,
    /// `AND (immediate)` — decode fora da fatia B6.1 (ver acima).
    AND,
    /// `ORR (immediate)` — decode fora da fatia B6.1 (ver acima).
    ORR,
    /// `EOR (immediate)` — decode fora da fatia B6.1 (ver acima).
    EOR
}
