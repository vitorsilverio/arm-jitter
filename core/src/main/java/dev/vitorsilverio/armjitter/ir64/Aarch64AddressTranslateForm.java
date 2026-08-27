package dev.vitorsilverio.armjitter.ir64;

/// Forma de `AT` (`ARM DDI 0487 C6.2.23`) reconhecida pelo decoder — o regime EL1&amp;0 (B10.6,
/// stage-1 só), as 4 formas combinadas stage-1+stage-2 (B10.8, `S12E*`) e as formas puras de EL2/EL3
/// (B10.6b/B10.6c, `S1E2*`/`S1E3*` — stage-1 dos próprios regimes EL2/EL3, sem stage-2).
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
    S12E0W,
    /// `AT S1E2R` (`op1=4,CRn=7,CRm=8,op2=0`, B10.6b) — traduz como leitura de dados no regime
    /// EL2 (stage-1 própria de EL2,
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64PrivilegedStage1TranslatingAddressSpace64} —
    /// SEM stage-2, diferente de {@link #S12E1R}).
    S1E2R,
    /// `AT S1E2W` (`op2=1`) — idem, escrita de dados no regime EL2.
    S1E2W,
    /// `AT S1E3R` (`op1=6,CRn=7,CRm=8,op2=0`, B10.6c) — traduz como leitura de dados no regime
    /// EL3 (stage-1 própria de EL3, mesma classe de {@link #S1E2R}).
    S1E3R,
    /// `AT S1E3W` (`op2=1`) — idem, escrita de dados no regime EL3.
    S1E3W;

    /// `true` para as formas `*W` (checagem de permissão de ESCRITA); `false` para `*R` (LEITURA).
    public boolean isWrite() {
        return this == S1E1W || this == S1E0W || this == S12E1W || this == S12E0W || this == S1E2W
                || this == S1E3W;
    }

    /// `true` para as formas `S1E0*`/`S12E0*` (traduz "como EL0", independente do EL que executa a
    /// `AT`).
    public boolean isUnprivileged() {
        return this == S1E0R || this == S1E0W || this == S12E0R || this == S12E0W;
    }
}
