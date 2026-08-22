package dev.vitorsilverio.armjitter.ir64;

/// Forma de `AT` (`ARM DDI 0487 C6.2.23`, task B10.6) reconhecida pelo decoder — só o regime
/// EL1&amp;0 (as 4 formas que `TranslatingAddressSpace64` já modela de verdade). `S1E2*`/`S1E3*`/
/// `S12E*` ficam FORA (ver `b10.6-at-address-translation.md`, "Não inclui" — exigem regimes de
/// tradução de EL2/EL3 e stage-2 que não existem ainda, tasks B10.6b/B10.6c/B10.8).
public enum Aarch64AddressTranslateForm {
    /// `AT S1E1R` (`op1=0,CRn=7,CRm=8,op2=0`) — traduz como leitura de dados em EL1.
    S1E1R,
    /// `AT S1E1W` (`op1=0,CRn=7,CRm=8,op2=1`) — traduz como escrita de dados em EL1.
    S1E1W,
    /// `AT S1E0R` (`op1=0,CRn=7,CRm=8,op2=2`) — traduz como leitura de dados em EL0 (mesmo
    /// executando a partir de EL1: "esse VA seria acessível por EL0?").
    S1E0R,
    /// `AT S1E0W` (`op1=0,CRn=7,CRm=8,op2=3`) — traduz como escrita de dados em EL0.
    S1E0W;

    /// `true` para as formas `*W` (checagem de permissão de ESCRITA); `false` para `*R` (LEITURA).
    public boolean isWrite() {
        return this == S1E1W || this == S1E0W;
    }

    /// `true` para as formas `S1E0*` (traduz "como EL0", independente do EL que executa a `AT`).
    public boolean isUnprivileged() {
        return this == S1E0R || this == S1E0W;
    }
}
