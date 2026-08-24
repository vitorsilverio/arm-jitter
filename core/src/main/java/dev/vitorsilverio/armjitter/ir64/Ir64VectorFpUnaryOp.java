package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpArithmeticUnary} (AdvSIMD "two-register miscellaneous" de
/// ponto flutuante, B8.9) — um único operando de origem (`Rn`), precisão simples/dupla. Vive em
/// DOIS slots de encoding diferentes do mesmo grupo "two-register misc" (`Rm=00000`, o mesmo slot
/// do inteiro {@link Ir64VectorUnaryOp}, para {@link #ABS}/{@link #NEG}/as comparações-contra-zero;
/// `Rm=00001`, o mesmo slot do inteiro {@link Ir64VectorNarrowUnaryOp}, para o resto) — achado real
/// da triagem desta task, o decoder resolve isso, não o executor. Não cobre a forma escalar
/// (`FABS_s`/`FSQRT_s`/... não existem — `FABS`/`FNEG`/`FSQRT` escalares já são {@link
/// Ir64Op.Fp64Alu} desde B8.4; mas `FCMGT0_s`/`FRECPE_s`/`FRECPX_s`/`FRSQRTE_s`/conversão
/// inteiro↔float escalar via registrador `V`, que SÃO formas AdvSIMD-scalar genuínas, ficam fora
/// desta task).
public enum Ir64VectorFpUnaryOp {
    /// `|Rn|` — manipula o bit de sinal direto (nunca `Math.abs` double/float, mesma armadilha de
    /// `ABS` em {@link Ir64Op.Fp64Operation}).
    ABS,
    /// `-Rn` — manipula o bit de sinal direto.
    NEG,
    /// `sqrt(Rn)` (IEEE 754, elemento a elemento).
    SQRT,
    /// Arredonda para inteiro (mantendo representação FP), "mais próximo, par" (ties-to-even).
    RINTN,
    /// Arredonda para `-Infinito` (floor).
    RINTM,
    /// Arredonda para `+Infinito` (ceil).
    RINTP,
    /// Arredonda para zero (truncamento).
    RINTZ,
    /// Arredonda "mais próximo, afasta de zero em empate" (ties-away).
    RINTA,
    /// Idêntico a {@link #RINTN} neste emulador (sem modelo de exceção de inexatidão) — ver
    /// javadoc de {@link Ir64Op.Fp64Round#direction()}.
    RINTX,
    /// Idêntico a {@link #RINTN} neste emulador (arredondamento do "modo de arredondamento
    /// corrente" — sempre `RN` aqui, sem `FPCR.RMode` modelado).
    RINTI,
    /// Aproximação inicial de recíproco (`FPRecipEstimate`) — usada com
    /// {@link Ir64VectorFpThreeSameOp#RECPS} num refinamento Newton-Raphson. Sem tabela de
    /// hardware real modelada: `1.0 / Rn` já converge no mesmo ponto fixo após os passos de
    /// refinamento usuais do software guest (ver Armadilhas da task).
    RECPE,
    /// Aproximação inicial de raiz recíproca (`FPRSqrtEstimate`) — mesma decisão de
    /// {@link #RECPE}, `1.0 / sqrt(Rn)`.
    RSQRTE,
    /// `Rn > 0.0` — elemento vira todos-1 ou `0` (`NaN` sempre falso).
    CMGT0,
    /// `Rn >= 0.0`.
    CMGE0,
    /// `Rn == 0.0`.
    CMEQ0,
    /// `Rn <= 0.0`.
    CMLE0,
    /// `Rn < 0.0`.
    CMLT0,
    /// Converte inteiro ASSINADO (elemento de `esz` bytes) para ponto flutuante do MESMO `esz`.
    SCVTF,
    /// Converte inteiro NÃO assinado para ponto flutuante.
    UCVTF,
    /// `FPToFixed`, arredondamento "mais próximo, par", ASSINADO.
    FCVTNS,
    /// `FPToFixed`, "mais próximo, par", NÃO assinado.
    FCVTNU,
    /// `FPToFixed`, arredondamento para `+Infinito` (ceil), assinado.
    FCVTPS,
    /// `FPToFixed`, para `+Infinito`, não assinado.
    FCVTPU,
    /// `FPToFixed`, arredondamento para `-Infinito` (floor), assinado.
    FCVTMS,
    /// `FPToFixed`, para `-Infinito`, não assinado.
    FCVTMU,
    /// `FPToFixed`, arredondamento para zero (truncamento), assinado.
    FCVTZS,
    /// `FPToFixed`, para zero, não assinado.
    FCVTZU,
    /// `FPToFixed`, arredondamento "mais próximo, afasta de zero" (ties-away), assinado.
    FCVTAS,
    /// `FPToFixed`, "mais próximo, afasta de zero", não assinado.
    FCVTAU
}
