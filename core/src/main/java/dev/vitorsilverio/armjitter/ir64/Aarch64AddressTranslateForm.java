package dev.vitorsilverio.armjitter.ir64;

/// Forma de `AT` (`ARM DDI 0487 C6.2.23`) reconhecida pelo decoder — o regime EL1&amp;0 (B10.6,
/// stage-1 só) e as 4 formas combinadas stage-1+stage-2 (B10.8, `S12E*`). `S1E2*`/`S1E3*` ficam
/// FORA (ver `b10.6-at-address-translation.md`/`b10.8-at-stage2.md`, "Não inclui" — exigem
/// `TTBR0_EL2`/`TTBR0_EL3` que não existem ainda, tasks B10.6b/B10.6c).
public enum Aarch64AddressTranslateForm {
    /// `AT S1E1R` (`op1=0,CRn=7,CRm=8,op2=0`) — traduz como leitura de dados em EL1.
    S1E1R,
    /// `AT S1E1W` (`op1=0,CRn=7,CRm=8,op2=1`) — traduz como escrita de dados em EL1.
    S1E1W,
    /// `AT S1E0R` (`op1=0,CRn=7,CRm=8,op2=2`) — traduz como leitura de dados em EL0 (mesmo
    /// executando a partir de EL1: "esse VA seria acessível por EL0?").
    S1E0R,
    /// `AT S1E0W` (`op1=0,CRn=7,CRm=8,op2=3`) — traduz como escrita de dados em EL0.
    S1E0W,
    /// `AT S12E1R` (`op1=4,CRn=7,CRm=8,op2=4`, B10.8) — traduz VA→PA como leitura de dados em EL1,
    /// passando pelas DUAS etapas (stage-1 EL1&amp;0 + stage-2, quando `HCR_EL2.VM=1`).
    S12E1R,
    /// `AT S12E1W` (`op2=5`) — idem, escrita de dados em EL1.
    S12E1W,
    /// `AT S12E0R` (`op2=6`) — idem, leitura de dados "como EL0".
    S12E0R,
    /// `AT S12E0W` (`op2=7`) — idem, escrita de dados "como EL0".
    S12E0W;

    /// `true` para as formas `*W` (checagem de permissão de ESCRITA); `false` para `*R` (LEITURA).
    public boolean isWrite() {
        return this == S1E1W || this == S1E0W || this == S12E1W || this == S12E0W;
    }

    /// `true` para as formas `S1E0*`/`S12E0*` (traduz "como EL0", independente do EL que executa a
    /// `AT`).
    public boolean isUnprivileged() {
        return this == S1E0R || this == S1E0W || this == S12E0R || this == S12E0W;
    }

    /// `true` para as formas combinadas `S12E*` (B10.8: passam pela stage-2 além da stage-1,
    /// quando `HCR_EL2.VM=1`); `false` para as formas `S1E1*`/`S1E0*` (stage-1 só, B10.6).
    public boolean isCombinedStage12() {
        return this == S12E1R || this == S12E1W || this == S12E0R || this == S12E0W;
    }
}
