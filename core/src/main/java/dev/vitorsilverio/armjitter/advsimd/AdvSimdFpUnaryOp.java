package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "two-register miscellaneous" de PONTO FLUTUANTE, um só operando (`Rn`) — núcleo
/// COMPARTILHADO (RFC B13.2, D1), subconjunto de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp} migrado nas tasks B13.12/B13.13.
///
/// **Subconjunto deliberado**: os valores que o NEON de 32 bits produz em B13.12
/// (`VABS_F`/`VNEG_F`/as 5 comparações-com-zero/`VRECPE_F`/`VRSQRTE_F`) e em B13.13 (`VRINT*`,
/// `VCVTA/N/P/M{S,U}`, `VCVT_{SF,UF,FS,FU}`). O resto do enum A64 (`SQRT`/`RINTI`/`FRECPX`/
/// `FCVTXN`) não tem forma equivalente no NEON de 32 bits (`RINTI` é idêntico a {@link #RINTX}
/// neste emulador, mas sem mnemônico A32 correspondente) e continua no `switch` local de
/// {@link dev.vitorsilverio.armjitter.executor64.Ir64VectorFpArithmeticExecutor}.
public enum AdvSimdFpUnaryOp {
    /// `|Rn|` — manipula o bit de sinal direto.
    ABS,
    /// `-Rn` — manipula o bit de sinal direto.
    NEG,
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
    /// Aproximação inicial de recíproco (`FPRecipEstimate`) — sem tabela de hardware real
    /// modelada, `1.0 / Rn`.
    RECPE,
    /// Aproximação inicial de raiz recíproca (`FPRSqrtEstimate`) — mesma decisão de {@link #RECPE},
    /// `1.0 / sqrt(Rn)`.
    RSQRTE,
    /// `VRINTN` — arredonda para inteiro (mantendo FP), "mais próximo, par" (ties-to-even).
    RINTN,
    /// `VRINTM` — arredonda para `-Infinito` (floor).
    RINTM,
    /// `VRINTP` — arredonda para `+Infinito` (ceil).
    RINTP,
    /// `VRINTZ` — arredonda para zero (truncamento).
    RINTZ,
    /// `VRINTA` — arredonda "mais próximo, afasta de zero em empate" (ties-away).
    RINTA,
    /// `VRINTX` — idêntico a {@link #RINTN} neste emulador (sem modelo de exceção de inexatidão,
    /// mesma decisão do A64).
    RINTX,
    /// `VCVT_SF` — converte inteiro ASSINADO para ponto flutuante do MESMO tamanho de elemento.
    SCVTF,
    /// `VCVT_UF` — converte inteiro NÃO assinado para ponto flutuante.
    UCVTF,
    /// `VCVTNS` — `FPToFixed`, arredondamento "mais próximo, par", ASSINADO.
    FCVTNS,
    /// `VCVTNU` — `FPToFixed`, "mais próximo, par", NÃO assinado.
    FCVTNU,
    /// `VCVTPS` — `FPToFixed`, arredondamento para `+Infinito` (ceil), assinado.
    FCVTPS,
    /// `VCVTPU` — `FPToFixed`, para `+Infinito`, não assinado.
    FCVTPU,
    /// `VCVTMS` — `FPToFixed`, arredondamento para `-Infinito` (floor), assinado.
    FCVTMS,
    /// `VCVTMU` — `FPToFixed`, para `-Infinito`, não assinado.
    FCVTMU,
    /// `VCVT_FS` — `FPToFixed`, arredondamento para zero (truncamento; `VCVT` sem sufixo de modo
    /// SEMPRE trunca, independente de qualquer `FPSCR.RMode` — ARM DDI 0406C), assinado.
    FCVTZS,
    /// `VCVT_FU` — `FPToFixed`, para zero, não assinado.
    FCVTZU,
    /// `VCVTAS` — `FPToFixed`, arredondamento "mais próximo, afasta de zero" (ties-away), assinado.
    FCVTAS,
    /// `VCVTAU` — `FPToFixed`, "mais próximo, afasta de zero", não assinado.
    FCVTAU
}
