package dev.vitorsilverio.armjitter.advsimd;

/// Vista PLANA, em palavras de 64 bits, de um banco de registradores vetoriais ARM — o substrato
/// que o núcleo vetorial compartilhado ({@link AdvSimdLanes}) usa para ler/escrever lanes sem
/// saber de qual dos dois mundos (`A64` ou `A32`) o banco veio (RFC B13.2, decisão D1).
///
/// Cada mundo mapeia seus registradores para um intervalo contíguo de palavras:
///
/// - {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters}: `V<n>` ocupa as palavras
///   `2n` (bits 63:0) e `2n+1` (bits 127:64) — 64 palavras no total.
/// - {@link dev.vitorsilverio.armjitter.core.VfpRegisters}: `D<n>` é a palavra `n`; `Q<n>` são as
///   palavras `2n` e `2n+1` — 32 palavras no total.
///
/// É essa indireção que resolve a assimetria estrutural entre os dois bancos: NEON de 32 bits
/// endereça QUALQUER `D<n>` (inclusive ímpar) como operando de 64 bits, o que a API por
/// registrador `Q`-indexado da B13.1 não consegue expressar; em palavras de 64 bits, "operando de
/// 64 bits em `D5`" é simplesmente `baseWord = 5`.
public interface AdvSimdRegisterWords {
    /// Lê a palavra de 64 bits de índice `index` (crua, sem interpretação de lane).
    long word(int index);

    /// Grava a palavra de 64 bits de índice `index`, sem afetar nenhuma outra palavra — a
    /// disciplina de escrita destrutiva de cada arquitetura (o "SIMD&FP destructive write" do A64,
    /// que zera os bits altos, e a ausência dela no VFP32) fica com o CHAMADOR, nunca aqui.
    void setWord(int index, long value);
}
