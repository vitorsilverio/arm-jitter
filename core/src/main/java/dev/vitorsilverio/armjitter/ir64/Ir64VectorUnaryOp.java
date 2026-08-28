package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticUnary} (AdvSIMD "two-register miscellaneous", B8.7)
/// — um único operando de origem (`Rn`), inteiro. Cobre também a forma ESCALAR (`ABS_s`/`NEG_s`/
/// `CM**0_s`, sempre `esz=3`/`q=false`, mesmo truque de {@link Ir64Op.VectorArithmeticThreeSame}).
public enum Ir64VectorUnaryOp {
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
    /// `SignedSaturate(sext(Rd_atual) + zext(Rn))` (B8.8, "signed saturating accumulate of
    /// unsigned value") — acumula no MESMO tamanho de elemento (ao contrário de `SADALP`, que
    /// alarga). Forma escalar aceita qualquer `esz` (`0`-`3`).
    SUQADD,
    /// `UnsignedSaturate(zext(Rd_atual) + sext(Rn))` (B8.8, "unsigned saturating accumulate of
    /// signed value") — satura em `0` por baixo se `Rn` for bem negativo. Forma escalar aceita
    /// qualquer `esz`.
    USQADD,
    /// `SignedSaturate(|sext(Rn)|)` (B8.18) — MESMO slot de {@link #ABS}, opcode diferente. Forma
    /// escalar aceita qualquer `esz` (mesma observação de {@link #SUQADD}).
    SQABS,
    /// `SignedSaturate(-sext(Rn))` (B8.18) — MESMO slot de {@link #NEG}. Forma escalar aceita
    /// qualquer `esz`.
    SQNEG,
    /// `CLS` vetorial (B8.18) — conta bits à esquerda IGUAIS ao bit de sinal de cada elemento, SEM
    /// contar o próprio bit de sinal (mesma semântica do `CLS` escalar de registrador geral, B8.2,
    /// mas por lane). Sem forma escalar real.
    CLS,
    /// `CLZ` vetorial (B8.18) — conta zeros à esquerda de cada elemento, sem sinal envolvido. Sem
    /// forma escalar real.
    CLZ,
    /// `CNT` vetorial (B8.18) — população de bits setados por BYTE (arranjo `.8B`/`.16B` fixo — o
    /// campo que para o resto desta tabela seria `esz` aqui só distingue `CNT`/`NOT`/`RBIT` entre
    /// si, nunca tamanho de elemento real; sempre executado a `esz=0`). Sem forma escalar real.
    CNT,
    /// `NOT`/`MVN` vetorial (B8.18) — complemento bit a bit do registrador inteiro (mesmo slot de
    /// {@link #CNT}, `U` diferente). Sem forma escalar real.
    NOT,
    /// `RBIT` vetorial (B8.18) — inverte a ordem dos BITS dentro de cada BYTE (mesmo slot de
    /// {@link #CNT}/{@link #NOT}, discriminado pelo mesmo campo). Sem forma escalar real.
    RBIT,
    /// `REV64` (B8.20) — inverte a ORDEM dos elementos de {@code esz} bytes dentro de cada grupo de
    /// 64 bits (mantém os próprios elementos intactos, só permuta a POSIÇÃO deles; não confundir
    /// com {@link #RBIT}, que inverte bits). MESMO slot `Rm=00000` de {@link #ABS}/{@link #NEG},
    /// opcode diferente. `esz` aceita `0`-`2` (byte/half/word — doubleword seria um grupo de 1
    /// elemento, no-op, reservado). Sem forma escalar real.
    REV64,
    /// `REV32` — como {@link #REV64}, mas grupos de 32 bits. MESMO opcode de {@link #REV64} (`U`
    /// distingue). `esz` aceita só `0`-`1` (byte/half — grupo de 1 elemento word seria no-op).
    REV32,
    /// `REV16` — como {@link #REV64}, mas grupos de 16 bits. `esz` aceita só `0` (byte — grupo de 1
    /// elemento halfword seria no-op). Sem forma `U=1` real neste opcode.
    REV16,
    /// `URECPE` (B8.20) — estimativa de recíproco não assinado de 8 bits de precisão
    /// (`UnsignedRecipEstimate`, ARM DDI 0487), só arranjo `.2s`/`.4s` (`esz` fixo em word). Vive no
    /// slot `Rm=00001` (narrow/widen), MESMO opcode que `FCVTAS`/`FCVTAU` usam para `esz` `0`/`1` —
    /// discriminado pelo bit alto de `esz` (`a`), sem colisão real (conferido bit a bit). Sem forma
    /// escalar.
    URECPE,
    /// `URSQRTE` — como {@link #URECPE}, mas raiz-recíproca (`UnsignedRSqrtEstimate`). MESMO opcode
    /// de {@link #URECPE} (`U` distingue).
    URSQRTE
}
