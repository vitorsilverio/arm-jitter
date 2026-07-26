package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.Bitfield} (`ARM DDI 0487 C6.2`, `SBFM`/`BFM`/`UBFM`, B6.3.2):
/// distinguidas pelo campo `opc` de 2 bits `[30:29]` do encoding (`00`=`SBFM`, `01`=`BFM`,
/// `10`=`UBFM` — `11` é `EXTR`, fora de escopo, ver a task). Os 11 aliases citados no épico
/// (`UBFX`/`SBFX`/`BFI`/`BFXIL`/`LSL`/`LSR`/`ASR`/`UXTB`/`UXTH`/`SXTB`/`SXTH`/`SXTW`) não têm
/// valor próprio — são o MESMO encoding com valores específicos de `immr`/`imms` (decisão D2).
public enum Ir64BitfieldOp {
    /// `SBFM` — extrai um campo de bits com sinal-estensão fora do campo copiado.
    SBFM,
    /// `BFM` — insere um campo de bits preservando os bits fora dele no destino.
    BFM,
    /// `UBFM` — extrai um campo de bits com zero-estensão fora do campo copiado.
    UBFM
}
