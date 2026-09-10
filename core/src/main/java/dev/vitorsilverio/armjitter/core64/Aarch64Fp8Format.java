package dev.vitorsilverio.armjitter.core64;

/// Formato de ponto flutuante de 8 bits selecionado por um campo `F8S1`/`F8S2`/`F8D` de
/// {@link Aarch64Core#fp8SourceFormat1()}/{@link Aarch64Core#fp8SourceFormat2()}/
/// {@link Aarch64Core#fp8DestinationFormat()} (`FPMR`, B19.11a, `FEAT_FPMR`) — `0b000`=E5M2,
/// `0b001`=E4M3 (`ARM DDI 0487`, campo de 3 bits; valores `0b010`-`0b111` são reservados e não têm
/// significado arquitetural documentado nesta emulação — decodificados pelo bit 0, mesma
/// disciplina "tolerante" já aplicada a outros campos `RES`/reservados deste core).
public enum Aarch64Fp8Format {
    /// `E5M2` — 5 bits de expoente, 2 de mantissa (mais alcance, menos precisão).
    E5M2,
    /// `E4M3` — 4 bits de expoente, 3 de mantissa (mais precisão, menos alcance).
    E4M3
}
