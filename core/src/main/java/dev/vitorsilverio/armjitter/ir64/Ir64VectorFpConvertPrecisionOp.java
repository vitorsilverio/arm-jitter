package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpConvertPrecision} (AdvSIMD conversão de PRECISÃO vetorial,
/// B19.4) — `FCVTL`/`FCVTN`/`FCVTXN`, um único operando de origem (`Rn`) e um elemento de largura
/// DIFERENTE no destino (`Rd`). Espelha o precedente INTEIRO de `VectorShiftNarrowImmediate`/
/// `VectorShiftWidenImmediate` (B8.8), que também são records separados por operarem entre duas
/// larguras: {@link Ir64Op.VectorFpConvertPrecision#esz} é SEMPRE o lado ESTREITO, o lado largo é
/// `esz + 1`. Meia precisão AQUI é CONVERSÃO (`f16`↔`f32`), ISA base ARMv8.0-A — a ARITMÉTICA em
/// meia precisão (`FEAT_FP16`) é a B19.5. Sem modelo de `FPCR.RMode`/`FZ`/exceções FP (paridade
/// consciente com B8.5/B8.9/B19.3).
public enum Ir64VectorFpConvertPrecisionOp {
    /// `FCVTL`/`FCVTL2` — ALARGA: lê elementos de `esz` bytes (metade selecionada por
    /// {@link Ir64Op.VectorFpConvertPrecision#q}, `false`=baixa/`true`=alta) e escreve elementos de
    /// `esz + 1` bytes, preenchendo os 128 bits inteiros de `Rd`. `esz=1` ⇒ `f16`→`f32` (exato);
    /// `esz=2` ⇒ `f32`→`f64` (exato).
    FCVTL,
    /// `FCVTN`/`FCVTN2` — ESTREITA com arredondamento "mais próximo, par" (o default; `FPCR.RMode`
    /// não é modelado). `esz=1` ⇒ `f32`→`f16`; `esz=2` ⇒ `f64`→`f32`. Com {@link
    /// Ir64Op.VectorFpConvertPrecision#q} `false` escreve a metade BAIXA de `Rd` e ZERA a alta; com
    /// `true` escreve a metade ALTA e PRESERVA a baixa.
    FCVTN,
    /// `FCVTXN`/`FCVTXN2` — ESTREITA `f64`→`f32` com arredondamento "round to odd" (jamming), que
    /// impede o arredondamento duplo. Só `esz=2` (nunca há forma `f32`→`f16` deste mnemônico). Mesma
    /// disciplina de metade que {@link #FCVTN}.
    FCVTXN,
    /// `BFCVTN`/`BFCVTN2` (`FEAT_BF16`, B19.7) — ESTREITA `f32`→`bf16` com arredondamento
    /// round-to-nearest-even (ver {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bf16Bits}).
    /// Só `esz=1` (`bf16` tem a mesma largura de `f16`, mas nunca há forma `f64`→`bf16`). MESMA
    /// disciplina de metade que {@link #FCVTN} — vive no MESMO slot de encoding (`a==1` em vez de
    /// `a==0`), não um mnemônico à parte.
    BFCVTN
}
