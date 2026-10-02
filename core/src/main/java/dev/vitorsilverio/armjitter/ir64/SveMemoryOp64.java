package dev.vitorsilverio.armjitter.ir64;

/// Operações SVE de acesso à memória: load, store, gather/scatter e as formas multi-vetor.
///
/// Sub-interface selada de {@link SveOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface SveMemoryOp64 extends SveOp64 permits SveMemoryOp64.Load,
        SveMemoryOp64.Store, SveMemoryOp64.Gather, SveMemoryOp64.MultiVectorMemory {

    /// Load SVE contíguo (B17.17) — 27 encodings: `LD1` (todos os `dtype`) e `LD2`/`LD3`/`LD4` desentrelaçados (as formas
    /// `LDNT1`, sem modelo de cache, são `LD1`), `LD1R*` (replicante), `LD1RQ`/`LD1RO`, `LDFF1`/`LDNF1` (first-fault e
    /// non-fault, **classe própria**: a falha vai para o `FFR` em vez de abortar), `LDR` de vetor e de predicado, e
    /// `PRF*` (hint: no-op). Semântica em `SveLoadOps`.
    ///
    /// O `immediate` é o valor JÁ decodificado do encoding (sem escala): cada operação o multiplica pelo tamanho que lhe
    /// cabe (`VL`, `PL`, `VL/elemento × acesso`, `16`, `32` ou o tamanho do elemento).
    record Load(
            Op op,
            /// Log2 do tamanho do acesso à memória por elemento (`0`-`3`; `4` = 128 bits em `LD[234]Q`).
            int msz,
            /// Log2 do tamanho do elemento do vetor (`0`-`3`; `4` = elemento de 128 bits, formas `.Q`).
            int esz,
            /// `true` = o dado da memória é estendido com sinal até o elemento (`LD1SB`/`LD1SH`/`LD1SW`); senão, zero.
            boolean signExtend,
            /// Número de registradores menos um (`0` = `LD1`, `1`-`3` = `LD2`-`LD4`).
            int nreg,
            /// Vetor de destino `Zt` (`Pt` em `LDR` de predicado; sem significado em `PRF`).
            int rd,
            /// Base `Xn|SP`.
            int rn,
            /// Registrador de índice `Xm` (`31` = `XZR`, só em `LDFF1`); vale quando {@code registerOffset}.
            int rm,
            /// `true` = escalar mais escalar (`Xn + (Xm << msz)`); `false` = escalar mais imediato.
            boolean registerOffset,
            /// Imediato decodificado, sem escala (ver acima).
            long immediate,
            /// Predicado governante `P0`-`P7` (sem significado em `LDR`).
            int pg,
            /// `true` = ilegal em modo streaming (a menos que `FEAT_SME_FA64` esteja efetivo).
            boolean nonStreaming,
            /// Endereço da instrução.
            long instructionAddress) implements SveMemoryOp64 {
        /// Operação do grupo.
        public enum Op {
            LD1, LDFF1, LDNF1, LD1R, LD1RQ, LD1RO, LDR_Z, LDR_P, PRF
        }
        @Override public int kind() { return Kind.SVE_LOAD; }
    }

    /// Store SVE (B17.18) — 37 encodings: `ST1` contíguo (todos os pares `msz`/`esz`, escalar+escalar e escalar+imediato,
    /// incl. `STNT1`, que sem modelo de cache é `ST1`), `ST2`/`ST3`/`ST4` entrelaçados, `STR` de vetor e de predicado e
    /// o **scatter** (`ST1_zprz`, `ST1_zpiz`, `ST1Q`). Semântica em `SveStoreOps`.
    ///
    /// O `immediate` é o valor JÁ decodificado do encoding (sem escala). Diferente do load, o store não tem `dtype`: o
    /// tamanho do acesso (`msz`) e o do elemento (`esz`) são enumerados no `.decode`, com `msz <= esz`.
    record Store(
            Op op,
            /// Log2 do tamanho do acesso à memória por elemento (`0`-`3`; `4` = 128 bits em `ST[234]Q`).
            int msz,
            /// Log2 do tamanho do elemento do vetor (`0`-`3`; `4` = elemento de 128 bits, formas `.Q`).
            int esz,
            /// Número de registradores menos um (`0` = `ST1`, `1`-`3` = `ST2`-`ST4`).
            int nreg,
            /// Vetor de origem `Zt` (`Pt` em `STR` de predicado).
            int rt,
            /// {@link Op#ST1}/`STR`: base `Xn|SP`. {@link Op#SCATTER_VECTOR_INDEX}: base `Xn|SP`. {@link Op#SCATTER_VECTOR_BASE}
            /// e {@link Op#ST1Q}: o vetor `Zn` que carrega os endereços.
            int rn,
            /// {@link Op#ST1} escalar+escalar: índice `Xm` (`31` recusado). {@link Op#SCATTER_VECTOR_INDEX}: vetor de
            /// deslocamentos `Zm`. {@link Op#ST1Q}: `Xm` escalar (`31` = `XZR`).
            int rm,
            /// `true` = escalar mais escalar (`Xn + (Xm << msz)`); `false` = escalar mais imediato.
            boolean registerOffset,
            /// Imediato decodificado, sem escala (ver acima).
            long immediate,
            /// Predicado governante `P0`-`P7` (sem significado em `STR`).
            int pg,
            /// Só no {@link Op#SCATTER_VECTOR_INDEX}: extensão do deslocamento (`OFFSET_UXTW`, `OFFSET_SXTW` ou `OFFSET_64`).
            int offsetExtend,
            /// Só no {@link Op#SCATTER_VECTOR_INDEX}: `true` = deslocamento escalado por `msz`.
            boolean scaled,
            /// `true` = ilegal em modo streaming (a menos que `FEAT_SME_FA64` esteja efetivo).
            boolean nonStreaming,
            /// Endereço da instrução.
            long instructionAddress) implements SveMemoryOp64 {
        /// `xs` = `0`: os 32 bits baixos de cada elemento do vetor de deslocamentos, sem sinal (`UXTW`).
        public static final int OFFSET_UXTW = 0;
        /// `xs` = `1`: os 32 bits baixos de cada elemento do vetor de deslocamentos, com sinal (`SXTW`).
        public static final int OFFSET_SXTW = 1;
        /// `xs` = `2`: elemento de 64 bits inteiro.
        public static final int OFFSET_64 = 2;

        /// Operação do grupo.
        public enum Op {
            /// `ST1`/`ST2`/`ST3`/`ST4`/`STNT1` contíguo.
            ST1,
            /// `STR` de vetor.
            STR_Z,
            /// `STR` de predicado.
            STR_P,
            /// Scatter escalar + vetor (`ST1_zprz`).
            SCATTER_VECTOR_INDEX,
            /// Scatter vetor + imediato (`ST1_zpiz`).
            SCATTER_VECTOR_BASE,
            /// `ST1Q` (SVE2.1): base VETORIAL, deslocamento ESCALAR.
            ST1Q,
            /// `STNT1_zprz` (SVE2, B17.25): `Zn[e] + Xm` — base VETORIAL, deslocamento ESCALAR. "Non-temporal" é só um
            /// hint de cache: o acesso é o de um `ST1*`.
            SCATTER_VECTOR_PLUS_SCALAR
        }
        @Override public int kind() { return Kind.SVE_STORE; }
    }

    /// Gather load SVE (B17.19) — um acesso à memória por ELEMENTO, cada um com endereço próprio: `LD1_zprz` (escalar +
    /// vetor de deslocamentos), `LD1_zpiz` (vetor de endereços + imediato) e `LD1Q` (SVE2.1, vetor + escalar), cada uma
    /// nas duas formas `LD1*` e first-fault `LDFF1*` (o bit `ff` do encoding). Semântica em `SveGatherOps`. Os `PRF*` de
    /// gather são hints e vêm como {@link Load} com {@code Op.PRF}. Todo gather é ilegal em modo streaming.
    record Gather(
            Op op,
            /// Log2 do tamanho do acesso à memória por elemento (`0`-`3`; `4` = 128 bits em `LD1Q`).
            int msz,
            /// Log2 do tamanho do elemento do vetor (`2` = 32 bits, `3` = 64 bits, `4` = elemento de 128 bits em `LD1Q`).
            int esz,
            /// `true` = o dado da memória é estendido com sinal até o elemento (`LD1SB`/`LD1SH`/`LD1SW`); senão, zero.
            boolean signExtend,
            /// `true` = forma first-fault (`LDFF1*`): o primeiro elemento ativo aborta, os demais escrevem o `FFR`.
            boolean firstFault,
            /// Vetor de destino `Zt`.
            int rd,
            /// {@link Op#SCALAR_PLUS_VECTOR}: base `Xn|SP`. {@link Op#VECTOR_PLUS_IMMEDIATE}, {@link Op#LD1Q} e
            /// {@link Op#VECTOR_PLUS_SCALAR}: o vetor `Zn` que carrega os endereços.
            int rn,
            /// {@link Op#SCALAR_PLUS_VECTOR}: o vetor de deslocamentos `Zm`. {@link Op#LD1Q} e {@link Op#VECTOR_PLUS_SCALAR}:
            /// o escalar `Xm` (`31` = `XZR`).
            int rm,
            /// Só em {@link Op#VECTOR_PLUS_IMMEDIATE}: o `imm5` decodificado, sem escala e sem sinal (o endereço soma
            /// `imm5 << msz`).
            long immediate,
            /// Predicado governante `P0`-`P7`.
            int pg,
            /// Só em {@link Op#SCALAR_PLUS_VECTOR}: extensão do deslocamento (`OFFSET_UXTW`, `OFFSET_SXTW` ou `OFFSET_64`).
            int offsetExtend,
            /// Só em {@link Op#SCALAR_PLUS_VECTOR}: `true` = deslocamento escalado por `msz`.
            boolean scaled,
            /// Endereço da instrução.
            long instructionAddress) implements SveMemoryOp64 {
        /// `xs` = `0`: os 32 bits baixos de cada elemento do vetor de deslocamentos, sem sinal (`UXTW`).
        public static final int OFFSET_UXTW = 0;
        /// `xs` = `1`: os 32 bits baixos de cada elemento do vetor de deslocamentos, com sinal (`SXTW`).
        public static final int OFFSET_SXTW = 1;
        /// `xs` = `2`: elemento de 64 bits inteiro.
        public static final int OFFSET_64 = 2;

        /// Forma de endereçamento — as três são estruturalmente diferentes.
        public enum Op {
            /// `LD1_zprz`: `Xn|SP + (Zm[e] estendido << escala)`.
            SCALAR_PLUS_VECTOR,
            /// `LD1_zpiz`: `Zn[e] + (imm5 << msz)`.
            VECTOR_PLUS_IMMEDIATE,
            /// `LD1Q` (SVE2.1): `Zn.D[2 × segmento] + Xm`, um quadword por segmento de 128 bits.
            LD1Q,
            /// `LDNT1_zprz` (SVE2, B17.25): `Zn[e] + Xm` — base VETORIAL, deslocamento ESCALAR (o inverso de
            /// {@link #SCALAR_PLUS_VECTOR}). "Non-temporal" é só um hint de cache: o acesso é o de um `LD1*`.
            VECTOR_PLUS_SCALAR
        }
        @Override public int kind() { return Kind.SVE_GATHER; }
    }

    /// `LD1`/`ST1` (e `LDNT1`/`STNT1`, sem modelo de cache) de 2 ou 4 registradores CONSECUTIVOS de memória contígua,
    /// governados por um predicado-como-contador `PN8`-`PN15` (B17.28) — 16 encodings: {escalar+escalar, escalar+imediato}
    /// × {consecutivo, `_stride`} × {2, 4 registradores} × {load, store}. Semântica em `SveCounterOps`.
    ///
    /// No `_stride` (SME2) os registradores são `Zt`, `Zt + s`, … com `s = 8` (2 registradores) ou `4` (4 registradores);
    /// no consecutivo, `s = 1`. O decoder JÁ desembaralhou o `rd` cru (o bit não temporal e o bit 0/2 misturados).
    record MultiVectorMemory(
            /// `true` = `ST1`.
            boolean store,
            /// Log2 do tamanho do elemento (`0` = byte … `3` = doubleword); o acesso à memória tem o mesmo tamanho.
            int esz,
            /// Número de registradores: `2` ou `4`.
            int registers,
            /// Primeiro registrador `Zt`.
            int rt,
            /// Distância entre os registradores (`1`, `8` ou `4`).
            int registerStride,
            /// Índice `8`-`15` do `PNg` governante.
            int pg,
            /// Base `Xn|SP`.
            int rn,
            /// Índice `Xm` (`31` = `XZR`), quando {@code registerOffset}.
            int rm,
            /// `true` = escalar mais escalar (`Xn + (Xm << esz)`); `false` = escalar mais imediato.
            boolean registerOffset,
            /// Imediato com sinal de 4 bits, sem escala (multiplicado por `registers × VL`).
            long immediate,
            /// `true` = a instrução exige modo streaming (formas `_stride`, ou sem `FEAT_SVE2p1`).
            boolean streamingOnly,
            /// Endereço da instrução.
            long instructionAddress) implements SveMemoryOp64 {
        @Override public int kind() { return Kind.SVE_MULTI_VECTOR_MEMORY; }
    }
}
