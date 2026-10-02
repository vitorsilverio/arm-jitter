package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.SmeArrayMultiVectorOps;
import dev.vitorsilverio.armjitter.executor64.SmeConstructiveOps;
import dev.vitorsilverio.armjitter.executor64.SmeMemoryOps;
import dev.vitorsilverio.armjitter.executor64.SmeMop4Ops;
import dev.vitorsilverio.armjitter.executor64.SmeMovaOps;
import dev.vitorsilverio.armjitter.executor64.SmeMultiVectorOps;
import dev.vitorsilverio.armjitter.executor64.SmeOuterProductOps;
import dev.vitorsilverio.armjitter.executor64.SmeZt0Ops;

/// Operações SME/SME2: acesso ao array `ZA` e a `ZT0`, produtos externos e as formas multi-vetor.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface SmeOp64 extends Ir64Op permits SmeOp64.Zero, SmeOp64.ZeroZt0, SmeOp64.Mova,
        SmeOp64.TileLoadStore, SmeOp64.ArrayLoadStore, SmeOp64.Zt0LoadStore, SmeOp64.OuterProduct,
        SmeOp64.Mop4, SmeOp64.Tmop, SmeOp64.ZeroArray, SmeOp64.Movt, SmeOp64.Lut,
        SmeOp64.MultiVectorSingle, SmeOp64.ArrayMultiVector, SmeOp64.Constructive {

    /// SME `ZERO` (B18.3, `FEAT_SME`): zera linhas de `ZA` por máscara de 8 bits — a linha `i`
    /// (`0`..`SVL-1`) é zerada quando o bit `i MOD 8` de {@link #imm8} está ligado (`helper_sme_zero`
    /// do QEMU: `i % 8`, sempre 8 grupos, independente de `esz` — não há campo `esz` nesta
    /// instrução). `imm8 == 0xFF` zera `ZA` inteiro.
    record Zero(
            /// Máscara de 8 bits; o bit `n` governa as linhas `n`, `n+8`, `n+16`, ….
            int imm8,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_ZERO; }
        @Override public boolean execute(Aarch64Core core) { return SmeMovaOps.execute(core, this); }
    }

    /// SME2 `ZERO_zt0` (B18.3, `FEAT_SME2`): zera o registrador `ZT0` (512 bits) inteiro.
    record ZeroZt0(long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_ZERO_ZT0; }
        @Override public boolean execute(Aarch64Core core) { return SmeMovaOps.execute(core, this); }
    }

    /// SME `MOVA`/`MOVAZ` (B18.3): move entre o array `ZA` e o banco `Z` — três formas no mesmo record
    /// (campos não usados por uma forma ficam no valor documentado, mesma disciplina de
    /// {@link SvePredicateOp64.CounterPredicate}):
    ///
    /// - **Predicada de 1 vetor** ({@link #predicated} `true`, `FEAT_SME`): `MOVA_tz`/`MOVA_zt`. Elemento
    ///   com {@link #pg} falso não é escrito (merging).
    /// - **Multi-vetor de tile** ({@link #predicated} `false`, {@link #tile} `≥ 0`, `FEAT_SME2`):
    ///   `MOVA_tz2`/`MOVA_zt2`/`MOVA_tz4`/`MOVA_zt4` e, com {@link #zero} `true` (`FEAT_SME2p1`),
    ///   `MOVAZ_zt`/`MOVAZ_zt2`/`MOVAZ_zt4` — sem predicado, {@link #count} vetores `Z<zr×count>`..
    ///   `Z<zr×count+count-1>` consecutivos.
    /// - **Array-vetor** ({@link #predicated} `false`, {@link #tile} `-1`, `FEAT_SME2`): `MOVA_az2`/
    ///   `MOVA_az4`/`MOVA_za2`/`MOVA_za4` e, com {@link #zero} `true` (`FEAT_SME2p1`), `MOVAZ_za2`/
    ///   `MOVAZ_za4` — move {@link #count} LINHAS INTEIRAS de `ZA` (sem tile/`esz`/eixo), endereçadas por
    ///   `W8`-`W11` (`Aarch64MatrixTileAddressing#resolveArrayBaseRow`, `core64`).
    ///
    /// `MOVAZ` sempre tem {@link #toVector} `true` (lê `ZA`, nunca escreve) e zera a origem lida — a
    /// slice/linha INTEIRA acessada, não só os elementos que a instrução lê (ver `zero` nos `TRANS_FEAT`
    /// de `translate-sme.c`: o mesmo span lido é zerado, estenda-se ele por uma linha inteira ou por uma
    /// coluna inteira do tile).
    record Mova(
            /// `true` = resultado vai para `Z<zr>` (`..._zt...`); `false` = vai para `ZA` (`..._tz...`/
            /// `..._az...`).
            boolean toVector,
            /// `true` = zera a origem lida (`MOVAZ`, `FEAT_SME2p1`); implica {@link #toVector}.
            boolean zero,
            /// `true` = forma predicada de 1 vetor (`MOVA_tz`/`MOVA_zt`); `false` = multi-vetor ou
            /// array-vetor (sem predicado — {@link #pg} vale `-1`).
            boolean predicated,
            /// Índice do predicado `Pg` governante (só {@link #predicated}); `-1` nas outras formas.
            int pg,
            /// `1` (forma predicada), `2` ou `4` — número de vetores `Z`/linhas de `ZA` movidos.
            int count,
            /// Tamanho de elemento (`0` = `B` … `4` = `Q`); `-1` nas formas array-vetor (sempre linha
            /// inteira, granularidade de byte).
            int esz,
            /// Índice do tile `ZAn` (`0`..`(1 << esz) - 1`); `-1` nas formas array-vetor.
            int tile,
            /// `true` = slice vertical (`ZAn.V`, coluna do tile); `false` = horizontal (`ZAn.H`, linha).
            /// Sempre `false` nas formas array-vetor (não têm eixo).
            boolean vertical,
            /// Primeiro registrador `Z` do grupo (o grupo é `Z<zr×count>`..`Z<zr×count+count-1>` quando
            /// `count > 1`; o próprio `Z<zr>` quando `count == 1`).
            int zr,
            /// Índice do registrador geral que fornece o deslocamento dinâmico: `W12`-`W15`
            /// (`%mova_rs`, formas predicada/multi-vetor de tile) ou `W8`-`W11` (`%mova_rv`, formas
            /// array-vetor) — já resolvido pelo decoder.
            int registerIndex,
            /// O campo `off` do encoding.
            int offset,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_MOVA; }
        @Override public boolean execute(Aarch64Core core) { return SmeMovaOps.execute(core, this); }
    }

    /// SME `LD1B`/`LD1H`/`LD1W`/`LD1D`/`LD1Q` e `ST1*` de slice de tile (B18.4, `FEAT_SME`) — uma única linha
    /// de `sme.decode` por `esz`; `store` escolhe load/store e `vertical` o eixo (4 mnemônicos por linha).
    /// Endereço = `X<rn>|SP + (X<rm> << esz)` (`rm = 31` é `XZR`); slice `(W<registerIndex> + off) MOD
    /// (SVL >> esz)` do tile (`Aarch64MatrixTileAddressing`). **Load: elemento com `pg` falso vira ZERO na
    /// slice** (QEMU `sme_ld1`; a spec original dizia "não escreve" — errada); **store: elemento com `pg`
    /// falso não toca a memória.**
    record TileLoadStore(
            /// `true` = `ST1*` (`ZA` → memória); `false` = `LD1*`.
            boolean store,
            /// Tamanho de elemento (`0` = `B` … `4` = `Q`).
            int esz,
            /// Índice do tile `ZAn` (`0`..`(1 << esz) - 1`).
            int tile,
            /// `true` = slice vertical (coluna); `false` = horizontal (linha).
            boolean vertical,
            /// Predicado `P0`-`P7` governante.
            int pg,
            /// Registrador-base (`31` = `SP`).
            int rn,
            /// Registrador de índice (`31` = `XZR`), escalado por `esz`.
            int rm,
            /// `W12`-`W15` (`%mova_rs`), já resolvido.
            int registerIndex,
            /// O campo `off` do encoding.
            int offset,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_TILE_LOAD_STORE; }
        @Override public boolean execute(Aarch64Core core) { return SmeMemoryOps.execute(core, this); }
    }

    /// SME `LDR`/`STR` (B18.4, `FEAT_SME`) de UM vetor do array `ZA` (`SVL/8` bytes): `ZA[(W<registerIndex> +
    /// imm) MOD (SVL/8)]` de/para `X<rn>|SP + imm × SVL/8`. Sem predicado, sem exigir modo streaming (só
    /// `ZA` habilitado). Atenção: o registrador de índice é `W12`-`W15` (`%mova_rs`) apesar do campo se
    /// chamar `rv`.
    record ArrayLoadStore(
            /// `true` = `STR`; `false` = `LDR`.
            boolean store,
            int rn,
            /// `W12`-`W15`, já resolvido.
            int registerIndex,
            int imm,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_ARRAY_LOAD_STORE; }
        @Override public boolean execute(Aarch64Core core) { return SmeMemoryOps.execute(core, this); }
    }

    /// SME2 `LDR ZT0`/`STR ZT0` (B18.4, `FEAT_SME2`): 64 bytes de/para `X<rn>|SP`, sem offset.
    record Zt0LoadStore(
            /// `true` = `STR`; `false` = `LDR`.
            boolean store,
            int rn,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_ZT0_LOAD_STORE; }
        @Override public boolean execute(Aarch64Core core) { return SmeMemoryOps.execute(core, this); }
    }

    /// SME `ADDHA`/`ADDVA` e produto externo acumulado sobre UM tile inteiro de `ZA` (B18.5): `ZA<tile>[i][j]` é
    /// atualizado a partir de `Zn[i]` e `Zm[j]` (`Zn` sozinho em `ADDHA`/`ADDVA`) onde `Pn[i]` e `Pm[j]` permitem.
    /// **Tile inteiro, não slice** — não há `rs`/`off`/`v` nestes encodings. `subtract` escolhe `…OPS` (subtrai) em
    /// vez de `…OPA` (acumula); `ADDHA`/`ADDVA` e `FMOPA_sb`/`_hb` não têm esse bit (sempre `false`).
    record OuterProduct(
            Op op,
            /// Índice do tile `ZAn` (largura do campo depende do acumulador: 1/2/3 bits ⇒ 2/4/8 tiles).
            int tile,
            int zn,
            /// `Zm` (ignorado em `ADDHA`/`ADDVA`).
            int zm,
            /// Predicado de linhas (`P0`-`P7`).
            int pn,
            /// Predicado de colunas (`P0`-`P7`).
            int pm,
            boolean subtract,
            long instructionAddress) implements SmeOp64 {
        /// Os 23 mnemônicos de `sme.decode` (`### SME Add Vector to Array` + `### SME Outer Product`), com o
        /// tamanho de elemento do ACUMULADOR (que fixa quantos tiles existem e o formato do `ZA`).
        public enum Op {
            ADDHA_S(2), ADDVA_S(2), ADDHA_D(3), ADDVA_D(3),
            FMOPA_H(1), BFMOPA(1), FMOPA_S(2), FMOPA_D(3), FMOPA_W_H(2), BFMOPA_W(2), FMOPA_SB(2), FMOPA_HB(1),
            SMOPA_S(2), SUMOPA_S(2), USMOPA_S(2), UMOPA_S(2),
            SMOPA_D(3), SUMOPA_D(3), USMOPA_D(3), UMOPA_D(3),
            BMOPA(2), SMOPA2_S(2), UMOPA2_S(2);

            private final int accumulatorEsz;

            Op(int accumulatorEsz) {
                this.accumulatorEsz = accumulatorEsz;
            }

            /// `0` = byte … `3` = doubleword — o elemento do tile que o produto atualiza.
            public int accumulatorEsz() {
                return accumulatorEsz;
            }
        }

        @Override public int kind() { return Kind.SME_OUTER_PRODUCT; }
        @Override public boolean execute(Aarch64Core core) { return SmeOuterProductOps.execute(core, this); }
    }

    /// SME `MOP4` — produto externo de QUARTO de tile, **sem predicado** (B18.5b, `FEAT_SME_MOP4`): o tile
    /// `ZA<tile>` é dividido em 4 quadrantes `(linhaMetade, colunaMetade)`; o quadrante usa `Zn` (ou `Zn+1` se
    /// `nPair` e a coluna é a metade alta) para a linha e `Zm` (ou `Zm+1` se `mPair` e a linha é a metade alta)
    /// para a coluna. `zn` já vem de `%mop4_zn` (par, `0`..`14`) e `zm` de `%mop4_zm` (`16`..`30`).
    record Mop4(
            Op op,
            /// Índice do tile `ZAn` (`0`..`(1 << accumulatorEsz) - 1`).
            int tile,
            int zn,
            int zm,
            /// `true` = `…MOP4S` (subtrai); `false` = `…MOP4A` (acumula). Sempre `false` em `FMOP4A_sb`/`_hb`.
            boolean subtract,
            /// Bit `n` do encoding: a metade alta de colunas lê `Zn+1`.
            boolean nPair,
            /// Bit `m` do encoding: a metade alta de linhas lê `Zm+1`.
            boolean mPair,
            long instructionAddress) implements SmeOp64 {
        /// Os 18 mnemônicos de `# SME MOP4 Quarter-tile outer products`, com o tamanho de elemento do
        /// ACUMULADOR (`0` = byte … `3` = doubleword).
        public enum Op {
            FMOP4_HH(1), BFMOP4_HH(1), FMOP4_SS(2), FMOP4_DD(3), BFMOP4_SH(2), FMOP4_SH(2), FMOP4A_SB(2), FMOP4A_HB(1),
            SMOP4_SH(2), UMOP4_SH(2), SMOP4_SB(2), SMOP4_DH(3), SUMOP4_SB(2), SUMOP4_DH(3), UMOP4_SB(2), UMOP4_DH(3),
            USMOP4_SB(2), USMOP4_DH(3);

            private final int accumulatorEsz;

            Op(int accumulatorEsz) {
                this.accumulatorEsz = accumulatorEsz;
            }

            public int accumulatorEsz() {
                return accumulatorEsz;
            }
        }

        @Override public int kind() { return Kind.SME_MOP4; }
        @Override public boolean execute(Aarch64Core core) { return SmeMop4Ops.execute(core, this); }
    }

    /// SME `TMOP` — produto externo ESPARSO (B18.5b, `FEAT_SME_TMOP`): a linha vem do par `Zn`/`Zn+1` (`zn` par,
    /// de `%zn_ax2`), a coluna de `Zm`, e os bits de controle de `Zk` (`Z20`-`Z23`/`Z28`-`Z31`, `expand_tmop_zk`)
    /// escolhem, por elemento, qual das origens entra (2 bits em `hh`/`ss`, 4 em `sh`/`hb`, 8 em `sb`), no segmento
    /// `idx` (bit inicial `idx × elementos × bits por elemento`). Segue a ARM DDI 0602 (`FTMOPA`/`STMOPA`), que
    /// diverge do QEMU em dois pontos: lê `Zk` (`op3 = Z[k]`), não `Zm`; e o segmento começa em `idx × csize`, não em
    /// `(idx × VL em bytes) >> 1`. Só acumula (não existe `…TMOPS`).
    record Tmop(
            Op op,
            int tile,
            int zn,
            int zm,
            /// Registrador de controle, já expandido por `expand_tmop_zk`.
            int zk,
            /// Segmento (`0`..`3`) do vetor de controle.
            int index,
            long instructionAddress) implements SmeOp64 {
        /// Os 13 mnemônicos de `# SME TMOP Sparse outer products`, com o tamanho de elemento do acumulador.
        public enum Op {
            BFTMOPA_HH(1), FTMOPA_HH(1), FTMOPA_SS(2), BFTMOPA_SH(2), FTMOPA_SH(2), FTMOPA_HB(1), FTMOPA_SB(2),
            STMOPA_SH(2), UTMOPA_SH(2), STMOPA_SB(2), SUTMOPA_SB(2), USTMOPA_SB(2), UTMOPA_SB(2);

            private final int accumulatorEsz;

            Op(int accumulatorEsz) {
                this.accumulatorEsz = accumulatorEsz;
            }

            public int accumulatorEsz() {
                return accumulatorEsz;
            }
        }

        @Override public int kind() { return Kind.SME_TMOP; }
        @Override public boolean execute(Aarch64Core core) { return SmeMop4Ops.execute(core, this); }
    }

    /// SME `ZERO` multi-vetor de `ZA` (B18.6, `FEAT_SME2p1`): zera {@link #ngrp} grupos de {@link #nvec} linhas de `ZA`.
    /// A linha-base é `((W<rv> & ~(nvec-1)) + off) MOD (SVL_B / ngrp)` (`get_zarray` do QEMU) e o grupo `r` começa
    /// em `base + r × (SVL_B / ngrp)`. `off` já vem ESCALADO pelo decoder (`%off3_x2`/`%off2_x2`/`%off2_x4`/`%off1_x4`).
    record ZeroArray(
            int ngrp,
            int nvec,
            /// `W8`-`W11` (`%mova_rv`).
            int registerIndex,
            int off,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_ZERO_ARRAY; }
        @Override public boolean execute(Aarch64Core core) { return SmeZt0Ops.execute(core, this); }
    }

    /// SME2 `MOVT` (B18.6): move entre `ZT0` e um registrador. `ZT_TO_X`/`X_TO_ZT` (`FEAT_SME2`) transferem a palavra
    /// de 64 bits `off` (`0`-`7`) de `ZT0`; `Rt = 31` é `XZR`. `VECTOR_TO_ZT` (`FEAT_SME_LUTv2`, exige modo streaming)
    /// copia `MIN(SVL_B, 64)` bytes de `Z<rt>` para o segmento `off MOD (64 / tsize)` de `ZT0` e, com `off = 0`,
    /// zera o resto de `ZT0` (`trans_MOVT_ztz` do QEMU, `maxsz = offset ? tsize : 64`).
    record Movt(
            Form form,
            int rt,
            int off,
            long instructionAddress) implements SmeOp64 {
        /// As três formas do `.decode`: `MOVT_rzt`, `MOVT_ztr` e `MOVT_ztz`.
        public enum Form { ZT_TO_X, X_TO_ZT, VECTOR_TO_ZT }

        @Override public int kind() { return Kind.SME_MOVT; }
        @Override public boolean execute(Aarch64Core core) { return SmeZt0Ops.execute(core, this); }
    }

    /// SME2 `LUTI2`/`LUTI4` (B18.6): para cada elemento do destino, um índice de 2/4 bits tirado de `Zn` escolhe uma
    /// entrada de 32 bits de `ZT0` (a tabela, 16 entradas) e o destino recebe os {@link #esz} bytes baixos dela.
    /// {@link #count} destinos (`1`/`2`/`4`); consecutivos (`zd`, `zd+1`, …) ou, em {@link #strided}, espaçados de
    /// `8` (2 vetores) / `4` (4 vetores). `index` escolhe o segmento (`index & (segmentos - 1)`, `segmentos =
    /// bitsElemento / (bitsIndice × count)`); `LUTI4_*_4b` lê os índices de `Zn`/`Zn+1` e tem `index = 0` fixo
    /// (`helper_sme2_luti4_4b`).
    record Lut(
            /// `false` = `LUTI2` (índice de 2 bits), `true` = `LUTI4` (4 bits).
            boolean fourBit,
            /// Tamanho do elemento de destino: `0` = byte, `1` = half, `2` = word.
            int esz,
            int count,
            boolean strided,
            int zd,
            int zn,
            int index,
            long instructionAddress) implements SmeOp64 {
        @Override public int kind() { return Kind.SME_LUT; }
        @Override public boolean execute(Aarch64Core core) { return SmeZt0Ops.execute(core, this); }
    }

    /// SME2 multi-vetor "multiple-and-single" destrutivo (B18.7, `FEAT_SME2`): `Zdn[i] = op(Zdn[i], Zm)` para cada um
    /// dos {@link #count} (`2`/`4`) registradores `Z` CONSECUTIVOS a partir de {@link #zdn}, com UM `Zm` avulso
    /// (`Z0`-`Z15`). **Sem predicado.** `esz` é o tamanho do elemento (`0` = byte … `3` = doubleword; nas operações de
    /// ponto flutuante `0` não existe — é o espaço de `BFMAX_n1` e afins). Exige modo streaming (`SVL`, nunca `VL`).
    ///
    /// **B18.8 reusa o mesmo record** para a forma "multiple vectors" (`_nn`, grupo × grupo): com
    /// {@link #zmIsGroup} ligado, {@link #zm} é o primeiro registrador de um SEGUNDO grupo de {@link #count}
    /// registradores (`%zm_ax2`/`%zm_ax4`, já multiplicado) e o membro `i` de `Zdn` opera contra o membro `i` dele.
    record MultiVectorSingle(
            Op op,
            int esz,
            int count,
            /// Primeiro registrador do grupo — JÁ multiplicado por `count` (`%zd_ax2`/`%zd_ax4`).
            int zdn,
            int zm,
            /// `false` = `_n1` (um `Zm` avulso, `Z0`-`Z15`); `true` = `_nn` (`zm` é a base de outro grupo).
            boolean zmIsGroup,
            long instructionAddress) implements SmeOp64 {
        /// As 13 operações da seção `### SME2 Multi-vector Multiple and Single SVE Destructive` mais `FAMAX`/`FAMIN`
        /// (só existem na forma `_nn`, `FEAT_FAMINMAX`) da `Multiple Vectors SVE Destructive`.
        public enum Op {
            SMAX, UMAX, SMIN, UMIN, ADD, SRSHL, URSHL, SQDMULH, FMAX, FMIN, FMAXNM, FMINNM, FSCALE, FAMAX, FAMIN;

            /// `true` nas operações de ponto flutuante (`esz = 0` não existe nelas).
            public boolean isFloatingPoint() {
                return ordinal() >= FMAX.ordinal();
            }
        }

        @Override public int kind() { return Kind.SME_MULTI_VECTOR_SINGLE; }
        @Override public boolean execute(Aarch64Core core) { return SmeMultiVectorOps.execute(core, this); }
    }

    /// SME2 multi-vetor "multiple and single, array vectors" (B18.9, `FEAT_SME2`): grupo de {@link #count}
    /// (`2`/`4`; `1` nas formas widening/FMA/dot) registradores `Z` a partir de {@link #zn} (SEM alinhamento — pode
    /// dar a volta em `Z31`), UM `Zm` avulso (`Z0`-`Z15`), resultado em vetores do array `ZA`. **Sem predicado.**
    ///
    /// **Endereçamento** (`get_zarray` do QEMU, não o de tile da B18.3): o membro `r` do grupo escreve os
    /// {@link Op#vectorsPerMember()} vetores `ZA[base + r × (SVL/count) + i]`, `base = ((W<rv> arredondado para baixo a
    /// um múltiplo de vectorsPerMember) + off) MOD (SVL/count)`. Os vetores de membros diferentes **não são
    /// consecutivos** — ficam `SVL/count` linhas distantes.
    ///
    /// `ADD`/`SUB` ESCREVEM `Zn ± Zm` no vetor (`do_azz_n1`); todas as demais ACUMULAM (`ZA += f(Zn, Zm)`).
    record ArrayMultiVector(
            Op op,
            int count,
            /// Índice do registrador geral `W8`-`W11` que seleciona o vetor (`%mova_rv`, já somado a 8).
            int registerIndex,
            /// `off` JÁ escalado pelo número de vetores escritos por membro (`%off3_x2`, `%off2_x4`, …).
            int off,
            int zn,
            /// `Zm` (`Z0`-`Z15` nas formas `_n1`; base ALINHADA do grupo nas `_nn`).
            int zm,
            long instructionAddress,
            /// `true` nas formas `_nn` (B18.10): `Zm` também é um grupo e o membro `r` usa `Z(zm + r)`; `false` nas `_n1`
            /// (B18.9), onde `Zm` é um vetor único.
            boolean multipleZm,
            /// Forma `_nx` (B18.11): índice do elemento de `Zm` dentro de cada segmento de 128 bits (já montado dos bits
            /// não contíguos do `.decode`); {@link #NOT_INDEXED} nas formas `_n1`/`_nn`.
            int index) implements SmeOp64 {
        /// Valor de {@link #index} das formas SEM índice.
        public static final int NOT_INDEXED = -1;

        /// Forma `_n1` (B18.9): `Zm` é um vetor único.
        public ArrayMultiVector(Op op, int count, int registerIndex, int off, int zn, int zm,
                long instructionAddress) {
            this(op, count, registerIndex, off, zn, zm, instructionAddress, false, NOT_INDEXED);
        }

        /// Formas `_n1`/`_nn` (B18.9/B18.10): sem índice.
        public ArrayMultiVector(Op op, int count, int registerIndex, int off, int zn, int zm,
                long instructionAddress, boolean multipleZm) {
            this(op, count, registerIndex, off, zn, zm, instructionAddress, multipleZm, NOT_INDEXED);
        }

        /// `true` na forma `_nx` (B18.11): `Zm` é UM vetor e o segundo operando é o elemento {@link #index} de cada
        /// segmento de 128 bits dele.
        public boolean indexed() {
            return index != NOT_INDEXED;
        }

        /// Os 50 mnemônicos das seções `### SME2 Multi-vector Multiple and Single Array Vectors` (B18.9) e
        /// `### SME2 Multi-vector Multiple Array Vectors` (B18.10), com o tamanho de elemento do vetor de `ZA` e o
        /// número de vetores de `ZA` escritos POR MEMBRO do grupo. `FADD`/`FSUB`/`BFADD`/`BFSUB` só existem na forma
        /// `_nn` e NÃO têm `Zn`: `ZA ±= Zm` (o campo {@link #zn} fica `0` e é ignorado).
        public enum Op {
            ADD_S(2, 1), ADD_D(3, 1), SUB_S(2, 1), SUB_D(3, 1),
            FMLAL(2, 2), FMLSL(2, 2), BFMLAL(2, 2), BFMLSL(2, 2),
            FDOT(2, 1), BFDOT(2, 1), USDOT(2, 1), SUDOT(2, 1),
            SDOT_4B(2, 1), SDOT_4H(3, 1), SDOT_2H(2, 1), UDOT_4B(2, 1), UDOT_4H(3, 1), UDOT_2H(2, 1),
            SMLAL(2, 2), SMLSL(2, 2), UMLAL(2, 2), UMLSL(2, 2),
            SMLALL_S(2, 4), SMLALL_D(3, 4), SMLSLL_S(2, 4), SMLSLL_D(3, 4),
            UMLALL_S(2, 4), UMLALL_D(3, 4), UMLSLL_S(2, 4), UMLSLL_D(3, 4), USMLALL(2, 4), SUMLALL(2, 4),
            BFMLA(1, 1), BFMLS(1, 1), FMLA_H(1, 1), FMLA_S(2, 1), FMLA_D(3, 1), FMLS_H(1, 1), FMLS_S(2, 1),
            FMLS_D(3, 1),
            FMLALL_B(2, 4), FDOT_SB(2, 1), FMLAL_HB(1, 2), FDOT_HB(1, 1),
            FADD_H(1, 1), FADD_S(2, 1), FADD_D(3, 1), BFADD(1, 1), FSUB_H(1, 1), FSUB_S(2, 1), FSUB_D(3, 1),
            BFSUB(1, 1),
            // ── B18.12: `ADD_aaz`/`SUB_aaz` — acumula `Zm` INTEIRO sobre o vetor de `ZA` (`ZA ±= Zm`, sem `Zn`) ──
            ADD_AAZ_S(2, 1), ADD_AAZ_D(3, 1), SUB_AAZ_S(2, 1), SUB_AAZ_D(3, 1),
            // ── B18.11: dot VERTICAL (só existe na forma indexada) ──
            SVDOT_2H(2, 1), SVDOT_4B(2, 1), SVDOT_4H(3, 1), UVDOT_2H(2, 1), UVDOT_4B(2, 1), UVDOT_4H(3, 1),
            SUVDOT(2, 1), USVDOT(2, 1), FVDOT_SH(2, 1), BFVDOT(2, 1), FVDOTB(2, 1), FVDOTT(2, 1), FVDOT_HB(1, 1);

            private final int accumulatorEsz;
            private final int vectorsPerMember;

            Op(int accumulatorEsz, int vectorsPerMember) {
                this.accumulatorEsz = accumulatorEsz;
                this.vectorsPerMember = vectorsPerMember;
            }

            /// Tamanho do elemento do vetor de `ZA` (`1` = half/`bfloat16`, `2` = word, `3` = doubleword).
            public int accumulatorEsz() {
                return accumulatorEsz;
            }

            /// Quantos vetores de `ZA` cada membro do grupo escreve (`nsel` do QEMU: `1` = aritmética/dot/FMA, `2` =
            /// widening ×2, `4` = widening ×4).
            public int vectorsPerMember() {
                return vectorsPerMember;
            }

            /// Dot VERTICAL (B18.11): o produto escalar atravessa os registradores do grupo de `Zn` (um elemento de
            /// cada), e o membro `r` do grupo de `ZA` consome o elemento `k*e + r` de cada um — não os elementos de
            /// UM vetor.
            public boolean vertical() {
                return ordinal() >= SVDOT_2H.ordinal();
            }

            /// `BFMLA`/`BFMLS`: o vetor de `ZA` é `bfloat16`, não `binary16` (mesma largura, formato diferente).
            public boolean bfloat16() {
                return this == BFMLA || this == BFMLS;
            }
        }

        @Override public int kind() { return Kind.SME_ARRAY_MULTI_VECTOR; }
        @Override public boolean execute(Aarch64Core core) { return SmeArrayMultiVectorOps.execute(core, this); }
    }

    /// SME2 multi-vetor SVE "constructive" (B18.12, `FEAT_SME2` + o gate de cada linha): grupos de `2`/`4` registradores
    /// `Z` CONSECUTIVOS (`%zd_ax2`/`%zd_ax4`/`%zn_ax*`, já multiplicados) lidos e escritos como UM operando só, sem
    /// predicado (a exceção é {@link Op#SEL}, governado por `PN8`-`PN15`). **Não toca `ZA`** — exige modo streaming
    /// (`SVL`, nunca `VL`), exceto as três `*RSHRN_sh` compartilhadas com `FEAT_SVE2p1`.
    ///
    /// {@link #sources}/{@link #destinations} são quantos registradores a instrução LÊ/ESCREVE (`1`/`2`, `2`/`1`,
    /// `4`/`1`, `1`/`2`, `2`/`4`, `n`/`n`) — o `n` do `&zz_n` do `.decode` NÃO é isso (vale `1` nos formatos de
    /// estreitar/alargar). {@link #esz} é o tamanho do elemento da operação (o do ELEMENTO LARGO nas estreitas, o do
    /// DESTINO nas `*UNPK`, `4` = 128 bits só em `ZIP`/`UZP`).
    record Constructive(
            Op op,
            int esz,
            int sources,
            int destinations,
            /// Primeiro registrador de destino — JÁ multiplicado pelo alinhamento do grupo.
            int zd,
            /// Primeiro registrador do grupo de origem (ou o `Zn` único nas formas `zn:5`).
            int zn,
            /// `Zm` (`ZIP_2`/`UZP_2`/`*CLAMP`: registrador único; `SEL`: base do grupo); `0` onde não existe.
            int zm,
            /// Deslocamento à direita (`*RSHR*`, JÁ calculado como `N - campo`); `0` nas demais.
            int shift,
            /// `PNg` de `SEL` (`8`-`15`); `0` nas demais.
            int pg,
            long instructionAddress) implements SmeOp64 {
        /// As operações das seções `### SME2 Multi-vector SVE Constructive Unary`/`Binary`/`Select`.
        public enum Op {
            // conversão FP: precisão (estreitar sequencial/intercalado e alargar sequencial/intercalado)
            BFCVT, BFCVTN, FCVT_N, FCVTN, FCVT_W, FCVTL,
            // FP ↔ inteiro e arredondamento (mesma largura, `binary32`)
            FCVTZS, FCVTZU, SCVTF, UCVTF, FRINTN, FRINTP, FRINTM, FRINTA,
            // estreitamento inteiro saturante: `*CVT*` sem deslocamento, `*RSHR*` com; `N` = intercalado
            SQCVT, UQCVT, SQCVTU, SQCVTN, UQCVTN, SQCVTUN,
            SQRSHR, UQRSHR, SQRSHRU, SQRSHRN, UQRSHRN, SQRSHRUN,
            // alargamento inteiro
            SUNPK, UUNPK,
            // FP8
            F1CVT, F2CVT, F1CVTL, F2CVTL, BF1CVT, BF2CVT, BF1CVTL, BF2CVTL, FCVT_BH, FCVT_BS, FCVTN_BS,
            // permutação, clamp e seleção
            ZIP, UZP, FCLAMP, SCLAMP, UCLAMP, SEL
        }

        @Override public int kind() { return Kind.SME_CONSTRUCTIVE; }
        @Override public boolean execute(Aarch64Core core) { return SmeConstructiveOps.execute(core, this); }
    }
}
