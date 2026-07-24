package dev.vitorsilverio.armjitter.ir64;

/// Modo de endereçamento de uma instrução de load/store A64 (`ARM DDI 0487 C4.1.3`).
///
/// Não existe LDM/STM nem predicação em A64 (ver Armadilhas do épico B6) — os únicos modos são
/// estes quatro, compartilhados por {@link Ir64Op.Load64}/{@link Ir64Op.Store64} (os quatro) e
/// {@link Ir64Op.LoadStorePair} (só os três primeiros — LDP/STP não tem forma de registrador).
public enum Ir64AddressingMode {
    /// Deslocamento imediato sem writeback: endereço = `Rn|SP + imm`. Cobre tanto a forma
    /// "unsigned offset" (imediato escalado pelo tamanho da transferência) quanto `LDUR`/`STUR`
    /// (imediato de 9 bits cru, sem escala) — as duas colapsam na mesma semântica de execução,
    /// a diferença entre elas é só de DECODE (como o imediato foi normalizado).
    OFFSET,
    /// Pre-index: endereço = `Rn|SP + imm`; o mesmo endereço é escrito de volta em `Rn|SP` após
    /// a transferência.
    PRE_INDEX,
    /// Post-index: endereço = `Rn|SP` (valor atual, sem o imediato); `Rn|SP + imm` é escrito de
    /// volta após a transferência.
    POST_INDEX,
    /// Deslocamento por registrador com extensão opcional: endereço = `Rn|SP + extend(Rm)`. Só
    /// existe em {@link Ir64Op.Load64}/{@link Ir64Op.Store64} — sem writeback.
    REGISTER_OFFSET
}
