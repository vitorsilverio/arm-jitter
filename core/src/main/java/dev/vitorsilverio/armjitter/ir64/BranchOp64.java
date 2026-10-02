package dev.vitorsilverio.armjitter.ir64;

/// Operações A64 de desvio: `B`/`BL`/`B.cond`/`BR`/`BLR`/`RET`, `CBZ`/`CBNZ`/`TBZ`/`TBNZ` e as
/// formas fundidas de comparação + desvio do `FEAT_CMPBR`.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface BranchOp64 extends Ir64Op permits BranchOp64.Branch64,
        BranchOp64.CompareBranch64, BranchOp64.CompareAndBranchRegister,
        BranchOp64.CompareAndBranchImmediate {

    /// `B`/`BL`/`B.cond` (destino imediato) e `BR`/`BLR`/`RET` (destino em registrador) — ver
    /// {@link Ir64BranchForm}. É o único `Ir64Op` com uma condição de fato (`B.cond`); as demais
    /// formas sempre carregam {@link Ir64Condition#AL}.
    record Branch64(
            /// Forma do desvio (destino imediato ou em registrador).
            Ir64BranchForm form,
            /// Endereço da própria instrução de desvio — usado para calcular o link register
            /// (`instructionAddress + 4`) quando {@link #link}; para `B`/`B.cond` sem link, não
            /// tem efeito observável.
            long instructionAddress,
            /// Destino absoluto já resolvido pelo decoder, válido só quando
            /// {@link #form} é {@link Ir64BranchForm#IMMEDIATE}.
            long target,
            /// Registrador que contém o destino, válido só quando {@link #form} é
            /// {@link Ir64BranchForm#REGISTER}; `-1` na forma imediata. Nunca é `SP` — sempre um
            /// registrador `X` normal (`31` é `XZR`, um `BR`/`BLR`/`RET xzr` salta para `0`).
            int registerOperand,
            /// `true` para `BL`/`BLR` (grava `instructionAddress + 4` em `X30`).
            boolean link,
            /// Condição necessária para tomar o desvio (`AL` em todas as formas exceto
            /// `B.cond`).
            Ir64Condition condition) implements BranchOp64 {
        @Override public int kind() { return Kind.BRANCH64; }
    }

    /// `CBZ`/`CBNZ`/`TBZ`/`TBNZ` (`ARM DDI 0487 C6.2.36/38/369/370`) — ver
    /// {@link Ir64CompareBranchForm}. Sempre incondicional (não existe `CBZ.cond`); por isso não
    /// carrega {@link Ir64Condition}, ao contrário de {@link Branch64}.
    record CompareBranch64(
            /// Forma do teste (registrador inteiro contra zero, ou um único bit).
            Ir64CompareBranchForm form,
            /// Registrador testado (índice `0`-`31`; `31` é `XZR` — `CBZ xzr` é sempre tomado,
            /// `CBNZ xzr` nunca é).
            int rn,
            /// Largura do registrador testado (`CBZ`/`CBNZ`: `true`=`X`, `false`=`W` — só os 32
            /// bits baixos são comparados contra zero). Irrelevante para
            /// {@link Ir64CompareBranchForm#TBZ_TBNZ} (o bit testado sempre vem do registrador
            /// `X` completo — ver {@link #bitPosition}).
            boolean wide,
            /// Posição do bit testado (`0`-`63`), só para {@link Ir64CompareBranchForm#TBZ_TBNZ};
            /// `-1` para {@link Ir64CompareBranchForm#CBZ_CBNZ}.
            int bitPosition,
            /// `true` para `CBNZ`/`TBNZ` (desvia quando a condição testada é não-zero); `false`
            /// para `CBZ`/`TBZ` (desvia quando é zero).
            boolean branchIfNonZero,
            /// Destino absoluto já resolvido pelo decoder.
            long target) implements BranchOp64 {
        @Override public int kind() { return Kind.COMPARE_BRANCH64; }
    }

    /// `CB_cond Rt, Rm, <cc>, label` (`FEAT_CMPBR`, B19.22, `ARM DDI 0487 C6.2.53`) — funde
    /// comparação + desvio condicional numa única instrução, o que hoje o compilador sintetiza como
    /// `CMP`/`SUBS` + `B.cond`. **Sem efeito em `NZCV`** (a comparação é interna à instrução) — ao
    /// contrário de {@link Branch64} (`B.cond`), que lê `NZCV` já calculado por uma instrução
    /// anterior. O conjunto de condições é o subconjunto de 6 valores de
    /// {@link Ir64CompareBranchCondition} (mapeamento próprio da forma registrador — diferente de
    /// {@link CompareAndBranchImmediate}).
    record CompareAndBranchRegister(
            /// Condição da comparação `Rt <cond> Rm`.
            Ir64CompareBranchCondition condition,
            /// Registrador comparado (índice `0`-`31`; `31` é `XZR`, lê `0`).
            int rt,
            /// Registrador comparado (índice `0`-`31`; `31` é `XZR`, lê `0`).
            int rm,
            /// Largura da comparação (`esz` do encoding): `BYTE`/`HALF` comparam só os bits baixos
            /// de `Rt`/`Rm` (sem mascarar antes, ao contrário de instruções normais de 32 bits);
            /// `WORD`/`DOUBLEWORD` comparam a largura inteira selecionada por `sf`.
            Ir64MemSize size,
            /// Destino absoluto já resolvido pelo decoder.
            long target) implements BranchOp64 {
        @Override public int kind() { return Kind.COMPARE_AND_BRANCH_REGISTER; }
    }

    /// `CB_cond_imm Rt, #imm, <cc>, label` (`FEAT_CMPBR`, B19.22, `ARM DDI 0487 C6.2.54`) — mesma
    /// ideia de {@link CompareAndBranchRegister}, mas compara `Rt` contra um imediato de 6 bits SEM
    /// SINAL (`0`-`63`) em vez de outro registrador. **Sem efeito em `NZCV`**. O conjunto de
    /// condições é o subconjunto de 6 valores de {@link Ir64CompareBranchCondition} (mapeamento
    /// próprio da forma imediata — usa `LT`/`LTU` onde a forma registrador usaria `GE`/`GEU` no
    /// MESMO valor de campo `cc`, achado confirmado contra o QEMU real).
    record CompareAndBranchImmediate(
            /// Condição da comparação `Rt <cond> #immediate`.
            Ir64CompareBranchCondition condition,
            /// Registrador comparado (índice `0`-`31`; `31` é `XZR`, lê `0`).
            int rt,
            /// Largura da comparação (`sf`: `true`=`X` 64 bits, `false`=`W` 32 bits).
            boolean wide,
            /// Imediato de comparação, SEM SINAL, `0`-`63` (`UInt(imm6)` do manual — nunca
            /// estendido com sinal, ao contrário do deslocamento de desvio).
            int immediate,
            /// Destino absoluto já resolvido pelo decoder.
            long target) implements BranchOp64 {
        @Override public int kind() { return Kind.COMPARE_AND_BRANCH_IMMEDIATE; }
    }
}
