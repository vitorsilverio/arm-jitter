package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "two-register miscellaneous" INTEIRA de um só operando (`Rn`) — núcleo
/// COMPARTILHADO (RFC B13.2, D1) migrado de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp} (B8.7/B8.18/B8.20) na task B13.12.
///
/// **Subconjunto deliberado**: cobre só os valores que o NEON de 32 bits (B13.12) realmente produz.
/// {@code SUQADD}/{@code USQADD} (B8.8, acumulação saturante) e {@code RBIT} (B8.18) NÃO têm
/// encoding correspondente no grupo "2-reg-misc" de `size==0b11` do A32 (`neon-dp.decode`) — ficam
/// de fora deste enum e continuam no `switch` local de
/// {@link dev.vitorsilverio.armjitter.executor64.Ir64VectorArithmeticExecutor} (mesma disciplina de
/// duplicação mínima já aceita pela B13.5/B13.10 para os helpers de saturação).
public enum AdvSimdUnaryOp {
    /// `|sext(Rn)|`.
    ABS,
    /// `-Rn` truncado.
    NEG,
    /// `Rn == 0` — elemento vira todos-1 ou `0`.
    CMEQ0,
    /// `sext(Rn) > 0` — elemento vira todos-1 ou `0`.
    CMGT0,
    /// `sext(Rn) >= 0` — elemento vira todos-1 ou `0`.
    CMGE0,
    /// `sext(Rn) < 0` — elemento vira todos-1 ou `0`.
    CMLT0,
    /// `sext(Rn) <= 0` — elemento vira todos-1 ou `0`.
    CMLE0,
    /// Pareamento largo assinado: `sext(Rn[2i]) + sext(Rn[2i+1])`, resultado em `esz+1`.
    SADDLP,
    /// Pareamento largo não assinado: `zext(Rn[2i]) + zext(Rn[2i+1])`, resultado em `esz+1`.
    UADDLP,
    /// Como {@link #SADDLP}, mas ACUMULA no `Rd` ATUAL (já em `esz+1`) em vez de sobrescrever.
    SADALP,
    /// Como {@link #UADDLP}, mas ACUMULA no `Rd` ATUAL (já em `esz+1`) em vez de sobrescrever.
    UADALP,
    /// `SignedSaturate(|sext(Rn)|)`.
    SQABS,
    /// `SignedSaturate(-sext(Rn))`.
    SQNEG,
    /// Conta bits à esquerda IGUAIS ao bit de sinal de cada elemento, sem contar o próprio bit de
    /// sinal.
    CLS,
    /// Conta zeros à esquerda de cada elemento, sem sinal envolvido.
    CLZ,
    /// População de bits setados por BYTE (arranjo sempre byte).
    CNT,
    /// Complemento bit a bit do registrador inteiro.
    NOT,
    /// Inverte a ORDEM dos elementos de {@code esz} bytes dentro de cada grupo de 64 bits.
    REV64,
    /// Como {@link #REV64}, mas grupos de 32 bits.
    REV32,
    /// Como {@link #REV64}, mas grupos de 16 bits.
    REV16,
    /// Estimativa de recíproco não assinado de 8 bits de precisão (`UnsignedRecipEstimate`), só
    /// arranjo de elemento word (`esz` fixo em word pelo chamador).
    URECPE,
    /// Como {@link #URECPE}, mas raiz-recíproca (`UnsignedRSqrtEstimate`).
    URSQRTE
}
