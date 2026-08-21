package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.DataProcessing1Source} (B8.2, subgrupo "Data-processing
/// (1 source)" de "Data Processing — Register"). `CTZ`/`ABS`/`PACIA`/`AUTIA`/etc do MESMO
/// subgrupo ficam fora (extensões posteriores — ver `docs/isa-nao-aplicavel.tsv`).
public enum Ir64OneSourceOp {
    /// `RBIT` (`ARM DDI 0487 C6.2.240`): inverte a ordem dos BITS do registrador inteiro.
    RBIT,
    /// `REV16` (`ARM DDI 0487 C6.2.241`): inverte a ordem dos BYTES dentro de cada halfword de
    /// 16 bits do registrador (2 halfwords em `W`, 4 em `X`) — diferente do `REV16` de 32 bits do
    /// ARM32 (`AsmRuntimeHelpers#reverseHalfwords`), que só tem 1 halfword para inverter.
    REV16,
    /// `REV`(`W`)/`REV32`(`X`) — MESMO campo de opcode do encoding (`ARM DDI 0487 C6.2.239/238`):
    /// inverte a ordem dos BYTES dentro de cada palavra de 32 bits, mantendo a ORDEM das
    /// palavras (1 palavra em `W` — equivale a inverter o registrador inteiro —, 2 em `X`, cada
    /// metade revertida independentemente). Achado desta task (B8.2): ANTES da checagem de
    /// `opc2`(bits`[30:29]`) que esta task introduziu, este opcode caía por acaso no caminho de
    /// `SDIV`/`UDIV` (bug real, ver "Bugs reais achados e corrigidos" da task) — a tabela de
    /// cobertura marcava ✅ por engano (media decode bem-sucedido, não decode CORRETO).
    REV32,
    /// `REV64` (alias de disassembly de `REV Xd,Xn` com este opcode específico, `sf=1` fixo no
    /// encoding — só existe a forma de 64 bits): inverte a ordem dos 8 bytes do registrador
    /// inteiro. MESMO achado de {@link #REV32} (misdecode pré-B8.2 corrigido por esta task).
    REV64,
    /// `CLZ` (`ARM DDI 0487 C6.2.51`): conta zeros à esquerda (`0`-`32`/`0`-`64`).
    CLZ,
    /// `CLS` (`ARM DDI 0487 C6.2.50`): conta bits à esquerda IGUAIS ao bit de sinal, SEM contar o
    /// próprio bit de sinal (`0`-`31`/`0`-`63`).
    CLS,
    /// `CNT` (forma escalar de registrador geral, `FEAT_CSSC`/ARMv8.9 — incluída nesta task por
    /// decisão explícita do escopo B8.2, ver a task): população de bits setados (`popcount`).
    CNT
}
