package dev.vitorsilverio.armjitter.ir64;

/// Operações SVE cujo resultado é um predicado (ou que só manipulam predicados): lógica, quebra de
/// partição, contagem, comparações vetoriais e escalares (`WHILE*`), `MATCH`/`NMATCH` e `PSEL`.
///
/// Sub-interface selada de {@link SveOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface SvePredicateOp64 extends SveOp64 permits SvePredicateOp64.PredicateLogical,
        SvePredicateOp64.PredicateMisc, SvePredicateOp64.PartitionBreak,
        SvePredicateOp64.PredicateCount, SvePredicateOp64.Compare, SvePredicateOp64.ScalarCompare,
        SvePredicateOp64.CounterPredicate, SvePredicateOp64.Match,
        SvePredicateOp64.PredicateSelect {

    /// Lógica de predicado SVE (`FEAT_SVE`, B17.4): `AND`/`BIC`/`EOR`/`SEL`/`ORR`/`ORN`/`NOR`/`NAND`
    /// sobre `P<n>`, byte a byte, governada por `pg`. Com `setFlags` (sufixo `S`) seta `NZCV` por
    /// `PredTest` (granularidade de byte). `SEL` não tem forma `S`.
    record PredicateLogical(
            Op op,
            /// Predicado destino.
            int pd,
            /// Predicado governante.
            int pg,
            /// Primeiro operando.
            int pn,
            /// Segundo operando.
            int pm,
            /// `true` nas formas `ANDS`/`ORRS`/….
            boolean setFlags,
            /// Endereço da instrução (exceção de acesso SVE, `ELR_ELx`).
            long instructionAddress) implements SvePredicateOp64 {
        /// Operação lógica.
        public enum Op { AND, BIC, EOR, SEL, ORR, ORN, NOR, NAND }
        @Override public int kind() { return Kind.SVE_PREDICATE_LOGICAL; }
    }

    /// Grupo "misc" de predicados SVE (B17.4): `PTEST`, `PTRUE`/`PTRUES`, `PFALSE`, `SETFFR`,
    /// `RDFFR`/`RDFFRS`, `WRFFR`, `PFIRST`, `PNEXT`. Os campos que a operação não usa ficam `0`.
    record PredicateMisc(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword) de `PTRUE`/`PNEXT`.
            int esz,
            /// Predicado destino (`Pd`; em `PFIRST`/`PNEXT` é também a fonte `Pdn`).
            int pd,
            /// Predicado governante (`PTEST`, `RDFFR` predicado, `PFIRST`, `PNEXT`).
            int pg,
            /// Predicado fonte (`PTEST` e `WRFFR`).
            int pn,
            /// `true` em `PTRUES`/`RDFFRS`.
            boolean setFlags,
            /// Padrão `pat:5` de `PTRUE`.
            int pattern,
            /// Endereço da instrução.
            long instructionAddress) implements SvePredicateOp64 {
        /// Operação do grupo.
        public enum Op { PTEST, PTRUE, PFALSE, SETFFR, RDFFR, RDFFR_PREDICATED, WRFFR, PFIRST, PNEXT }
        @Override public int kind() { return Kind.SVE_PREDICATE_MISC; }
    }

    /// Partition break SVE (B17.4): `BRKA`/`BRKB` (zeroing ou merging), `BRKPA`/`BRKPB`, `BRKN`.
    /// Sempre em granularidade de byte.
    record PartitionBreak(
            Op op,
            /// Predicado destino (em `BRKN` é também `Pdm`, o segundo operando).
            int pd,
            /// Predicado governante.
            int pg,
            /// Primeiro operando.
            int pn,
            /// Segundo operando (`BRKPA`/`BRKPB`).
            int pm,
            /// `true` nas formas `BRKAS`/`BRKBS`/`BRKPAS`/`BRKPBS`/`BRKNS`.
            boolean setFlags,
            /// `true` em `BRKA`/`BRKB` com predicado `/M` (elementos inativos preservam `Pd`).
            boolean merging,
            /// Endereço da instrução.
            long instructionAddress) implements SvePredicateOp64 {
        /// Operação do grupo.
        public enum Op { BRKA, BRKB, BRKPA, BRKPB, BRKN }
        @Override public int kind() { return Kind.SVE_PARTITION_BREAK; }
    }

    /// Contagem por predicado SVE (B17.4): `CNTP`, `FIRSTP`/`LASTP` (`FEAT_SVE2p2`), `INCP`/`DECP`
    /// (escalar e vetor) e `SQINCP`/`UQINCP`/`SQDECP`/`UQDECP` (32/64 bits e vetor).
    record PredicateCount(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword).
            int esz,
            /// Registrador destino: `Xd` (escalar) ou `Zdn` (vetor).
            int rd,
            /// Predicado governante (nas formas `INCP`/`SQINCP` é o único predicado).
            int pg,
            /// Predicado contado (`CNTP`/`FIRSTP`/`LASTP`).
            int pn,
            /// `true` nas formas de decremento.
            boolean decrement,
            /// `true` nas formas saturantes sem sinal (`UQINCP`/`UQDECP`).
            boolean unsigned,
            /// Endereço da instrução.
            long instructionAddress) implements SvePredicateOp64 {
        /// Operação do grupo.
        public enum Op {
            CNTP, FIRSTP, LASTP, INCDECP_SCALAR, INCDECP_VECTOR,
            SINCDECP_SCALAR_32, SINCDECP_SCALAR_64, SINCDECP_VECTOR
        }
        @Override public int kind() { return Kind.SVE_PREDICATE_COUNT; }
    }

    /// Comparação SVE inteira que PRODUZ UM PREDICADO (B17.9): `Pd[e] = Pg[e] && (Zn[e] <cond> operando)`, e as flags
    /// `NZCV` saem SEMPRE (não existe forma sem `S`) pelo mesmo `PredTest` da B17.4. O operando é `Zm[e]` (vetor),
    /// o elemento de 64 bits de `Zm` que cobre o elemento (largo, `esz != 3`) ou um imediato.
    ///
    /// `LT`/`LE`/`LO`/`LS` existem como instrução real nas formas larga e imediata (o operando não pode ser trocado); na
    /// forma vetorial são só alias de assembler e o decoder nunca as produz.
    record Compare(
            Cond cond,
            Form form,
            /// Tamanho do elemento comparado (`0` = byte … `3` = doubleword; nunca `3` na forma larga).
            int esz,
            /// Predicado de destino (`P0`-`P15`).
            int pd,
            /// Predicado governante (`P0`-`P7`).
            int pg,
            /// Vetor `Zn`.
            int rn,
            /// Vetor `Zm` (formas vetorial e larga); sem significado no imediato.
            int rm,
            /// Imediato: COM sinal de 5 bits nas comparações com sinal (`-16..15`), SEM sinal de 7 bits nas sem sinal
            /// (`0..127`); sem significado nas demais formas.
            int imm,
            /// Endereço da instrução.
            long instructionAddress) implements SvePredicateOp64 {
        /// A condição. `EQ`/`NE` não distinguem sinal no resultado, mas o QEMU/manual comparam com sinal estendido nas formas
        /// largas (ver o executor); `GE`/`GT`/`LT`/`LE` são COM sinal e `HS`/`HI`/`LO`/`LS` SEM sinal.
        public enum Cond { EQ, NE, GE, GT, LT, LE, HS, HI, LO, LS }
        /// A origem do segundo operando.
        public enum Form { VECTOR, WIDE, IMMEDIATE }
        @Override public int kind() { return Kind.SVE_COMPARE; }
    }

    /// Comparação SVE de ESCALARES (B17.9): `WHILE*` produz um predicado a partir de dois `Xn`/`Wn` (o que faz um laço VLA
    /// terminar sem conhecer o `VL`), `CTERM` só seta flags. `PEXT` e `WHILE*` que escrevem predicado-como-contador
    /// (`PN8`-`PN15`) entram aqui desde a B17.28 (`WHILE_*_CNT2`/`CNT4`, com `rd` = o índice `8`-`15` do `PN`); `PEXT` fica em {@link CounterPredicate}.
    record ScalarCompare(
            Op op,
            /// Tamanho do elemento do predicado (`0` = byte … `3` = doubleword); sem significado em `CTERM`.
            int esz,
            /// Predicado de destino (`P0`-`P15`); nas formas `_PAIR`, o PRIMEIRO do par (sempre par). Sem significado em `CTERM`.
            int rd,
            /// Primeiro escalar (`Xn`; `31` = `XZR`).
            int rn,
            /// Segundo escalar (`Xm`; `31` = `XZR`).
            int rm,
            /// `true` = operandos de 64 bits; `false` = 32 bits. Sempre `true` nas `_PAIR`; sem significado em `WHILE_PTR`.
            boolean sf,
            /// `true` = comparação SEM sinal (`WHILELO`/`WHILELS`/`WHILEHI`/`WHILEHS`). Sem significado em `WHILE_PTR`/`CTERM`.
            boolean unsigned,
            /// O bit 4 do encoding, que muda de nome conforme a operação: `eq` em `WHILE_LT`/`WHILE_GT` (`LT`≠`LE`; em `GT`
            /// o sentido é INVERTIDO: `eq = 0` é `GE`), `rw` em `WHILE_PTR` (`1` = `WHILERW`), `ne` em `CTERM`.
            boolean flag,
            /// Endereço da instrução.
            long instructionAddress) implements SvePredicateOp64 {
        /// Operação do grupo. `WHILE_GT` (`WHILEGE`/`WHILEGT`/`WHILEHS`/`WHILEHI`) é SVE2; as `_PAIR`, SVE2.1.
        public enum Op {
            WHILE_LT, WHILE_GT, WHILE_PTR, WHILE_LT_PAIR, WHILE_GT_PAIR, CTERM,
            /// SVE2.1: `WHILE` que escreve um predicado-COMO-CONTADOR (`PN8`-`PN15`) sobre 2 (`CNT2`) ou 4 (`CNT4`) vetores.
            WHILE_LT_CNT2, WHILE_LT_CNT4, WHILE_GT_CNT2, WHILE_GT_CNT4
        }
        @Override public int kind() { return Kind.SVE_SCALAR_COMPARE; }
    }

    /// Predicado-como-contador SVE2.1 (B17.28), o que NÃO é `WHILE`: `PTRUE` (`PN8`-`PN15`), `CNTP` (conta os elementos que
    /// um `PNn` descreve) e `PEXT` (extrai uma máscara comum de um `PNn`). Um `PNn` é o registrador `Pn` (`n = 8..15`)
    /// lido como CONTADOR — não como máscara de bits — nos 16 bits baixos (ver `SveCounterOps`). Os campos que a operação
    /// não usa ficam `0`.
    record CounterPredicate(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword).
            int esz,
            /// Destino predicado: `PN8`-`PN15` em `PTRUE` (índice `8`-`15`); `Pd` em `PEXT` (o PRIMEIRO do par em `PEXT_2`).
            int pd,
            /// Fonte `PNn` (índice `8`-`15`) de `CNTP` e `PEXT`.
            int pn,
            /// Destino `Xd` de `CNTP` (`31` = `XZR`).
            int rd,
            /// `CNTP`: log2 do número de vetores contados (`1` = `vlx2`, `2` = `vlx4`). `PEXT`: o índice `imm` do segmento.
            int index,
            /// `true` = sem `FEAT_SVE2p1` (só `FEAT_SME2`): a instrução exige modo streaming (`CNTP`).
            boolean streamingOnly,
            /// Endereço da instrução.
            long instructionAddress) implements SvePredicateOp64 {
        /// Operação. `PEXT_1` escreve um predicado; `PEXT_2`, o par `Pd`, `Pd+1`.
        public enum Op { PTRUE, CNTP, PEXT_1, PEXT_2 }
        @Override public int kind() { return Kind.SVE_COUNTER_PREDICATE; }
    }

    /// `MATCH`/`NMATCH` (B17.22, `FEAT_SVE2`): busca de caractere vetorial. Para cada elemento ATIVO (por `pg`) de
    /// `Zn`, o bit do predicado destino é `1` se aquele valor aparece em QUALQUER elemento do MESMO segmento de
    /// 128 bits de `Zm` (`Zm` não é filtrado por predicado nenhum — todo o segmento é varrido); `NMATCH` inverte.
    /// `esz ∈ {0, 1}` só (byte/halfword — `helper_sve2_{,n}match_ppzz_{b,h}` do QEMU, não existem formas `.S`/`.D`).
    /// Sempre seta `NZCV` por `predTest` (é uma comparação). Ilegal em modo streaming sem `FEAT_SME_FA64`
    /// (`TRANS_FEAT_NONSTREAMING`).
    record Match(
            /// `true` = `NMATCH`.
            boolean invert,
            int esz,
            /// Predicado destino (`Pd`).
            int pd,
            /// Predicado governante (`Pg`) — filtra só `Zn`.
            int pg,
            /// `Zn`: o valor buscado, elemento a elemento.
            int rn,
            /// `Zm`: o "alfabeto" (segmento de 128 bits inteiro, ignorando `pg`).
            int rm,
            long instructionAddress) implements SvePredicateOp64 {
        @Override public int kind() { return Kind.SVE_MATCH; }
    }

    /// `PSEL` (B17.22, `FEAT_SME`/`FEAT_SVE2p1`): decodificação posicional pura (Achado análogo ao `PMOV` da
    /// B17.10) — NÃO escreve vetor nenhum, só predicado. `Pd = Pm[(Wrv + imm) mod elements] ? Pn : 0` (o bit
    /// TESTADO de `Pm`, no elemento calculado, escolhe entre copiar `Pn` inteiro ou zerar `Pd` inteiro — não é uma
    /// seleção elemento-a-elemento entre `Pn`/`Pm`, é um "AND" de `Pn` por UM bit de `Pm` replicado). `elements =
    /// VL >> esz`. `%psel_rv` restringe o GPR a `W12`-`W15` (a extração real é `16:2 !function=plus_12` — ler
    /// `rv:2` cru daria `X0`-`X3`, Armadilha 5 da task). Medido em `trans_PSEL` do QEMU.
    record PredicateSelect(
            int esz,
            /// Predicado destino (`Pd`).
            int pd,
            /// `Pn`: o predicado copiado (ou zerado).
            int pn,
            /// `Pm`: de onde vem o bit testado.
            int pm,
            /// `Wrv` — já resolvido para `W12`-`W15` pelo decoder (`%psel_rv`).
            int rv,
            /// Deslocamento somado a `Wrv` antes do `mod elements` (`%psel_imm_*`, largura depende de `esz`).
            int imm,
            long instructionAddress) implements SvePredicateOp64 {
        @Override public int kind() { return Kind.SVE_PREDICATE_SELECT; }
    }
}
