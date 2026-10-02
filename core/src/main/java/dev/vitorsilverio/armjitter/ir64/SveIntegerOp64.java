package dev.vitorsilverio.armjitter.ir64;

/// Operações SVE/SVE2 inteiras: aritmética predicada e não predicada, reduções, imediatos,
/// multiplicação indexada, endereçamento, permutação, contagem de elementos e as formas de
/// criptografia do SVE2.
///
/// Sub-interface selada de {@link SveOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface SveIntegerOp64 extends SveOp64 permits SveIntegerOp64.ElementCount,
        SveIntegerOp64.IntegerUnpredicated, SveIntegerOp64.IntegerPredicated,
        SveIntegerOp64.IntegerReduction, SveIntegerOp64.Immediate, SveIntegerOp64.MultiplyIndexed,
        SveIntegerOp64.Address, SveIntegerOp64.Permute, SveIntegerOp64.PermutePredicated,
        SveIntegerOp64.Histogram, SveIntegerOp64.LookupTable, SveIntegerOp64.Clamp,
        SveIntegerOp64.CryptoAes, SveIntegerOp64.CryptoSm4Encrypt,
        SveIntegerOp64.CryptoSm4KeyUpdate, SveIntegerOp64.CryptoRax1 {

    /// Contagem de elementos SVE (B17.4): `CNTB`/`CNTH`/`CNTW`/`CNTD`, `INC*`/`DEC*` e
    /// `SQINC*`/`UQINC*`/`SQDEC*`/`UQDEC*` sobre um padrão `pat:5` e um multiplicador `1..16`.
    record ElementCount(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword).
            int esz,
            /// Registrador destino: `Xd`/`Xdn`/`Wdn` (escalar) ou `Zdn` (vetor).
            int rd,
            /// Padrão `pat:5` (`POW2`, `VL1`…`VL256`, `MUL4`, `MUL3`, `ALL`).
            int pattern,
            /// Multiplicador `imm4 + 1` (`1..16`).
            int multiplier,
            /// `true` nas formas de decremento.
            boolean decrement,
            /// `true` nas formas saturantes sem sinal.
            boolean unsigned,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo.
        public enum Op { CNT, INCDEC_SCALAR, SINCDEC_SCALAR_32, SINCDEC_SCALAR_64, INCDEC_VECTOR, SINCDEC_VECTOR }
        @Override public int kind() { return Kind.SVE_ELEMENT_COUNT; }
    }

    /// Inteiro SVE sem predicado governante (B17.5): aritmética/lógica/shift por elemento, lógica
    /// ternária SVE2, `MLA`/`MLS`/`MAD`/`MSB` (estes COM predicado `pg`, apesar do grupo), `MOVPRFX`,
    /// `FEXPA`/`FTSSEL` e `INDEX`. Os 34 encodings do recorte colapsam num `Kind` só; {@link #op()} escolhe.
    record IntegerUnpredicated(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword). Nas formas bit-a-bit vale `0` e não é usado.
            int esz,
            /// Registrador vetorial destino (`Zd`/`Zdn`/`Zda`).
            int rd,
            /// Primeira fonte (`Zn`); nas formas destrutivas é o próprio `rd`; em `INDEX` é o `Xn` do início.
            int rn,
            /// Segunda fonte (`Zm`); em `INDEX` é o `Xm` do incremento.
            int rm,
            /// Terceira fonte (`Za`/`Zk`) das formas de três/quatro operandos.
            int ra,
            /// Predicado governante de `MLA`/`MLS`/`MAD`/`MSB` (`0`-`7`); sem significado nas demais.
            int pg,
            /// Imediato já normalizado: contagem de shift/rotação, ou (`INDEX`) o início/incremento imediato.
            long imm,
            /// Segundo imediato de `INDEX_ii` (o incremento).
            long imm2,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo (uma por linha do `sve.decode`).
        public enum Op {
            ADD, SUB, SQADD, UQADD, SQSUB, UQSUB,
            AND, ORR, EOR, BIC, XAR, EOR3, BSL, BCAX, BSL1N, BSL2N, NBSL,
            ASR_IMM, LSR_IMM, LSL_IMM, ASR_WIDE, LSR_WIDE, LSL_WIDE,
            MLA, MLS, MAD, MSB,
            MOVPRFX, FEXPA, FTSSEL,
            /// Multiply não-predicado SVE2 (B17.20): `MUL`/`SMULH`/`UMULH`/`PMUL` (polinomial, só byte)/`SQDMULH`/`SQRDMULH`.
            MUL, SMULH, UMULH, PMUL, SQDMULH, SQRDMULH,
            /// SVE2 Accumulate (B17.21a): adição complexa (`imm` = `1` na rotação 270), acumulação absoluta longa
            /// (`imm` = `1` em `*T`), add/sub long com carry (`imm` = `1` em `*T`), shifts com acumulação/inserção
            /// (`imm` = contagem) e `SABA`/`UABA`. O destino `rd` é também o acumulador (o `.decode` não modela isso).
            CADD, SQCADD, SABAL, UABAL, ADCL, SBCL, SSRA, USRA, SRSRA, URSRA, SRI, SLI, SABA, UABA,
            /// SVE2 Widening (B17.21b). {@link #esz()} é o tamanho do elemento de DESTINO (`0` só em `PMULL`, onde vale o
            /// elemento de 128 bits); as fontes têm metade dele. {@link #imm()}: bit 0 = `Zn` lê o elemento ímpar (top),
            /// bit 1 = `Zm` lê o ímpar. Nas formas largas (`*ADDW`/`*SUBW`) só o bit 1 vale (`Zn` já é largo).
            /// `SSHLL`/`USHLL`: `imm` = deslocamento, `imm2` = `1` em `*T`. `EORBT`/`EORTB` escrevem só um elemento do par.
            SADDL, UADDL, SSUBL, USUBL, SABDL, UABDL, SADDW, UADDW, SSUBW, USUBW,
            SQDMULL, SMULL, UMULL, PMULL, SSHLL, USHLL, EORBT, EORTB,
            /// Matriz 8-bit SVE (`FEAT_I8MM`): `Zda += Zn · Zm^T` por segmento de 128 bits; `BitPerm` por elemento.
            SMMLA, USMMLA, UMMLA, BEXT, BDEP, BGRP,
            /// SVE2 Narrowing (B17.21b). {@link #esz()} é o tamanho do elemento LARGO (fonte). `imm` = `1` em `*T`
            /// (escreve só o elemento ímpar do par, preservando o par); nas de shift, `imm` = deslocamento e `imm2` = `1`
            /// em `*T`. `SQCVTN`/`UQCVTN`/`SQCVTUN` (SVE2.1/SME2) leem o PAR `Zn`,`Zn+1` e intercalam.
            SQXTN, UQXTN, SQXTUN, SQCVTN, UQCVTN, SQCVTUN,
            SHRN, RSHRN, SQSHRN, SQRSHRN, UQSHRN, UQRSHRN, SQSHRUN, SQRSHRUN,
            ADDHN, RADDHN, SUBHN, RSUBHN,
            INDEX_II, INDEX_IR, INDEX_RI, INDEX_RR
        }
        @Override public int kind() { return Kind.SVE_INTEGER_UNPREDICATED; }
    }

    /// Inteiro SVE com predicado governante (B17.6): aritmética binária (`ADD`/`SDIV`/`SMULH`…), shifts
    /// (imediato, vetor e elemento largo) e unárias (`CLS`/`ABS`/`SXTB`/`FABS`…). Os 68 encodings colapsam
    /// num `Kind` só; {@link #op()} escolhe. A semântica é **merging**: elemento inativo de `Zd` fica como
    /// está — exceto nas unárias `_z` (`FEAT_SVE2p2`), cujo {@link #zeroing()} zera o elemento inativo.
    ///
    /// As formas **reversas** (`SUBR`/`SDIVR`/`UDIVR`/`ASRR`/`LSRR`/`LSLR`) usam o mesmo `Op` da forma
    /// direta: o decoder já entrega `rn`/`rm` trocados, então o resultado é sempre `op(Zn, Zm)`.
    record IntegerPredicated(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword).
            int esz,
            /// Registrador vetorial destino (`Zd`/`Zdn`).
            int rd,
            /// Primeira fonte (`Zn`); nas formas destrutivas diretas é o próprio `rd`.
            int rn,
            /// Segunda fonte (`Zm`); nas formas reversas é o próprio `rd`; sem significado nas unárias e no shift por imediato.
            int rm,
            /// Predicado governante (`P0`-`P7`).
            int pg,
            /// Contagem de shift por imediato já normalizada (`1..esize` à direita, `0..esize-1` à esquerda).
            long imm,
            /// `true` nas unárias `_z`: o elemento inativo é zerado em vez de preservado.
            boolean zeroing,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo (uma por mnemônico; as formas reversas e `_m`/`_z` são campos do record).
        public enum Op {
            ORR, EOR, AND, BIC, ADD, SUB, SMAX, UMAX, SMIN, UMIN, SABD, UABD,
            MUL, SMULH, UMULH, SDIV, UDIV,
            ASR_IMM, LSR_IMM, LSL_IMM, ASRD, SQSHL_IMM, UQSHL_IMM, SRSHR, URSHR, SQSHLU,
            ASR, LSR, LSL, ASR_WIDE, LSR_WIDE, LSL_WIDE,
            CLS, CLZ, CNT, CNOT, NOT, FABS, FNEG, ABS, NEG, SXTB, UXTB, SXTH, UXTH, SXTW, UXTW,
            /// `MOVPRFX Zd, Pg/{M,Z}, Zn` (B17.7): cópia predicada de `Zn` — o `zeroing` escolhe `_z` ou `_m`.
            MOVPRFX,
            /// SVE2 (B17.20): unárias saturantes/estimativas, soma-par acumulativa e pairwise predicado.
            SQABS, SQNEG, URECPE, URSQRTE, SADALP, UADALP, ADDP, SMAXP, UMAXP, SMINP, UMINP,
            /// SVE2 (B17.21a): shift por vetor saturante/arredondado, halving e saturating add/sub (as formas reversas
            /// chegam com `rn`/`rm` trocados, como sempre).
            SRSHL, URSHL, SQSHL_VECTOR, UQSHL_VECTOR, SQRSHL, UQRSHL,
            SHADD, UHADD, SHSUB, UHSUB, SRHADD, URHADD,
            SQADD, UQADD, SQSUB, UQSUB, SUQADD, USQADD
        }
        @Override public int kind() { return Kind.SVE_INTEGER_PREDICATED; }
    }

    /// Redução inteira SVE (B17.7, `sve.decode` `### SVE Integer Reduction Group`): reduz os elementos ATIVOS de
    /// `Zn` a um escalar `V<rd>` (`ORV`/`EORV`/`ANDV`/`SADDV`/`UADDV`/`SMAXV`/…) ou, nas 8 formas `*QV` (SVE2.1),
    /// a um `V<rd>` de 128 bits com um resultado por posição dentro do segmento. Escreve SEMPRE `V<rd>` (os bits
    /// acima são zerados) — nunca `Z<rd>`; o `MOVPRFX` predicado do mesmo grupo é {@link IntegerPredicated}.
    record IntegerReduction(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword).
            int esz,
            /// Registrador `SIMD&FP` destino (`Vd`).
            int rd,
            /// Vetor de origem (`Zn`).
            int rn,
            /// Predicado governante (`P0`-`P7`).
            int pg,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo: 9 escalares e 8 por segmento.
        public enum Op {
            ORV, EORV, ANDV, SADDV, UADDV, SMAXV, UMAXV, SMINV, UMINV,
            ORQV, EORQV, ANDQV, ADDQV, SMAXQV, UMAXQV, SMINQV, UMINQV
        }
        @Override public int kind() { return Kind.SVE_INTEGER_REDUCTION; }
    }

    /// Inteiro SVE com imediato (B17.8, `sve.decode` `### SVE Bitwise Immediate Group` e os dois `### SVE
    /// Integer Wide Immediate`): `AND`/`ORR`/`EOR` com bitmask, `DUPM`, cópia predicada (`CPY`/`FCPY`), broadcast
    /// (`DUP`/`FDUP`), `ADD`/`SUB`/`SUBR`/`SQADD`/`UQADD`/`SQSUB`/`UQSUB`, `SMAX`/`UMAX`/`SMIN`/`UMIN` e `MUL`. Os
    /// 33 encodings colapsam num `Kind` só. O decoder já entrega o imediato **expandido** (bitmask de 64 bits,
    /// `sh8` aplicado, `VFPExpandImm` feito) — o executor só o replica/mascara no tamanho do elemento.
    ///
    /// As duas formas `_m` (`CPY_m_i`/`FCPY`) preservam o elemento inativo; `CPY_z_i` o zera. Os 8 padrões `INVALID`
    /// do inventário (`esz = 0` com `sh = 1`) o decoder recusa — nunca chegam aqui.
    record Immediate(
            Op op,
            /// Tamanho de elemento (`0` = byte … `3` = doubleword). Nas formas de bitmask (`AND`/`ORR`/`EOR`/`DUPM`) vale `3`.
            int esz,
            /// Registrador vetorial destino (`Zd`/`Zdn`); a fonte, quando existe, é o próprio `rd`.
            int rd,
            /// Predicado governante de `CPY`/`FCPY` (`P0`-`P15`, 4 bits); sem significado nas demais.
            int pg,
            /// Imediato já expandido (bitmask de 64 bits; inteiro com sinal ou sem sinal já deslocado; bits do float).
            long imm,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo (uma por mnemônico do `sve.decode`).
        public enum Op {
            AND, ORR, EOR, DUPM,
            CPY_MERGING, CPY_ZEROING, FCPY, DUP, FDUP,
            ADD, SUB, SUBR, SQADD, UQADD, SQSUB, UQSUB,
            SMAX, UMAX, SMIN, UMIN, MUL
        }
        @Override public int kind() { return Kind.SVE_IMMEDIATE; }
    }

    /// Multiply SVE por elemento indexado (B17.8, `#### SVE Multiply - Indexed`) mais os dois dot-products
    /// vetoriais do grupo (`DOT_zzzz`/`CDOT_zzzz`, que dividem a semântica com as formas indexadas). Os 71 + 3
    /// encodings colapsam num `Kind` só; {@link #op()} escolhe.
    ///
    /// **O índice é por segmento de 128 bits**, não por vetor: cada segmento usa o elemento `index` DELE de `Zm`
    /// (só `VL >= 256` distingue de "índice global"). `Zm` é restrito a `Z0`-`Z7` (`esz` de 16 bits) ou `Z0`-`Z15`,
    /// o que o decoder já garante. O acumulador (`Zda`) é sempre o próprio `rd`.
    record MultiplyIndexed(
            Op op,
            /// Tamanho do elemento **de destino** (`1` = half … `3` = doubleword); nos dots e nas formas alargantes as
            /// fontes têm `esz / 2` (long) ou `esz / 4` (dot de 4 vias).
            int esz,
            /// Registrador vetorial destino/acumulador (`Zd`/`Zda`).
            int rd,
            /// Primeira fonte (`Zn`).
            int rn,
            /// Segunda fonte (`Zm`, já restrita).
            int rm,
            /// `true` nas formas indexadas; `false` em `DOT_zzzz`/`CDOT_zzzz` e nas 21 linhas não-indexadas da
            /// B17.22 (`Zm` inteiro, elemento a elemento).
            boolean indexed,
            /// Índice do elemento (ou do par/grupo, conforme a operação) dentro de cada segmento de 128 bits
            /// quando `indexed = true`. **Reusado** (B17.22) nas formas alargantes NÃO indexadas
            /// (`*MLAL`/`*MLSL`/`SQDML*L`/`SQDML*LBT`) como o bit `B`/`T` do lado de `Zm` (`0` = metade
            /// baixa, `1` = alta) — em `SQDMLALBT`/`SQDMLSLBT` esse bit é sempre `1` (`Zm` = topo)
            /// enquanto {@link #top} (o lado de `Zn`) é sempre `0` (`Zn` = base).
            int index,
            /// Rotação (`0`-`3`) de `CDOT`/`CMLA`/`SQRDCMLAH`; sem significado nas demais.
            int rot,
            /// `true` nas formas `T` (elementos ímpares de `Zn`); `false` nas `B` (pares). Só nas alargantes.
            boolean top,
            /// Vias do dot-product (`2` ou `4`); sem significado nas demais.
            int ways,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo. `MLA`/`MLS`/`MUL`/`SQDMULH`/`SQRDMULH`/`SQRDMLAH`/`SQRDMLSH` operam no tamanho do
        /// elemento; `*MLAL`/`*MLSL`/`*MULL`/`SQDML*L` alargam (`B`/`T`); as demais são dots e complexos.
        public enum Op {
            SDOT, UDOT, USDOT, SUDOT, CDOT,
            MLA, MLS, SQRDMLAH, SQRDMLSH,
            SQDMLAL, SQDMLSL, SMLAL, UMLAL, SMLSL, UMLSL,
            CMLA, SQRDCMLAH,
            SMULL, UMULL, SQDMULL, SQDMULH, SQRDMULH, MUL,
            /// B17.22: `SQDMLALBT`/`SQDMLSLBT` — a interleaved (`Zn` sempre a metade BAIXA, `Zm` sempre a
            /// metade ALTA, ao contrário de `B`/`T` normal onde as duas metades casam). Só formas
            /// não-indexadas (`indexed = false`).
            SQDMLALBT, SQDMLSLBT
        }
        @Override public int kind() { return Kind.SVE_MULTIPLY_INDEXED; }
    }

    /// Endereçamento SVE (B17.12): `ADDVL`/`ADDPL`/`RDVL` (aritmética de ponteiro em múltiplos de `VL/8` e `VL/64`
    /// bytes) e `ADR` vetorial (`Zd[i] = Zn[i] + (ext(Zm[i]) << msz)`). Não acessa memória.
    /// `ADDSVL`/`ADDSPL`/`RDSVL` (SME) são as mesmas contas com o `SVL` no lugar do `VL`.
    record Address(
            Op op,
            /// Destino: `Xd` (`SP` em `ADDVL`/`ADDPL` quando `31`; `XZR` em `RDVL`) ou `Zd`.
            int rd,
            /// Base: `Xn` (`SP` quando `31`) ou `Zn`. Sem significado em `RDVL`.
            int rn,
            /// `Zm` do `ADR`; sem significado nas demais.
            int rm,
            /// Imediato de 6 bits COM sinal (`-32..31`) de `ADDVL`/`ADDPL`/`RDVL`; sem significado em `ADR`.
            int imm,
            /// Deslocamento de escala do `ADR` (`0..3`); sem significado nas demais.
            int msz,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo. As quatro `ADR` são OPCODES (bits 23:22), não o `esz` genérico: `S32`/`U32` têm
        /// elemento de 64 bits e offset de 32 (com/sem sinal); `P32`/`P64` têm offset do tamanho do elemento.
        public enum Op { ADDVL, ADDPL, RDVL, ADDSVL, ADDSPL, RDSVL, ADR_S32, ADR_U32, ADR_P32, ADR_P64 }
        @Override public int kind() { return Kind.SVE_ADDRESS; }
    }

    /// Permutação SVE não predicada (B17.10): `EXT`/`DUP`/`DUPQ`/`EXTQ`/`INSR`/`REV`/`PMOV`/`TBL`/`TBX`/`UNPK` e as três
    /// granularidades de intercalação — vetor INTEIRO (`ZIP1`…`TRN2`), elemento de 128 bits (`*_Q`, `FEAT_F64MM`) e
    /// DENTRO de cada segmento de 128 bits (`ZIPQ1`…`UZPQ2`, `FEAT_SVE2p1`). Em `VL = 128` as três coincidem.
    record Permute(
            Op op,
            /// Tamanho do elemento (`0` = byte … `3` = doubleword; em `DUP_X` também `4` = quadword). Sem significado nas
            /// operações que não têm elemento (`EXT`, `EXTQ`, `PMOV_*`) e nas `*_Q`.
            int esz,
            /// Destino: `Zd`, ou `Pd` em `PMOV_PV`. Nas formas destrutivas (`EXT`, `EXTQ`) é também o operando baixo.
            int rd,
            /// Fonte: `Zn` (primeiro registrador da tabela em `TBL_SVE2`/`EXT_SVE2`), `Xn|SP` em `DUP_S`, `Pn` em
            /// `PMOV_VP`. Sem significado em `EXT`, `EXTQ` e `INSR_*`.
            int rn,
            /// Segundo operando: `Zm` (`EXT`/`EXTQ` o trazem no campo `9:5`), `Vm`/`Xm` do `INSR_*`.
            int rm,
            /// Imediato: deslocamento em bytes (`EXT*`), índice do elemento (`DUP_X`/`DUPQ`) ou da fatia do vetor (`PMOV_*`).
            int imm,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo.
        public enum Op {
            EXT, EXT_SVE2, EXTQ, DUP_S, DUP_X, DUPQ, INSR_F, INSR_R, REV, PMOV_PV, PMOV_VP,
            TBL, TBL_SVE2, TBX, TBLQ, TBXQ,
            SUNPKLO, SUNPKHI, UUNPKLO, UUNPKHI,
            ZIP1, ZIP2, UZP1, UZP2, TRN1, TRN2,
            ZIP1_Q, ZIP2_Q, UZP1_Q, UZP2_Q, TRN1_Q, TRN2_Q,
            ZIPQ1, ZIPQ2, UZPQ1, UZPQ2
        }
        @Override public int kind() { return Kind.SVE_PERMUTE; }
    }

    /// Permutação SVE de predicado, permutação predicada e `SEL` (B17.11) — 36 encodings. Junta três famílias que
    /// só compartilham o fato de o resultado depender de QUAIS elementos estão ativos (ou de como o predicado é
    /// embaralhado): as 9 permutações de predicado (`ZIP1_p`…`PUNPKHI`), as 26 predicadas (`COMPACT`, `LAST*`/`CLAST*`
    /// nos três destinos `Z`/`V`/`X`, `CPY` merging, `REV*`/`RBIT`/`REVD`, `SPLICE`, `EXPAND`) e `SEL_zpzz`.
    ///
    /// Os campos seguem o `sve.decode`: nas formas destrutivas (`CLAST_Z`, `SPLICE`) `rd` é também o primeiro operando
    /// (`Zdn`) e o segundo vem em `rm`; nas demais o vetor fonte é `rn`.
    record PermutePredicated(
            Op op,
            /// Tamanho do elemento (`0` = byte … `3` = doubleword). Sem significado em `REVD_*` (opera em quadwords) e
            /// em `PUNPKLO`/`PUNPKHI` (o elemento dobra sozinho).
            int esz,
            /// Destino: `Zd`/`Zdn`, `Pd`, `Vd` (`*_V`) ou `Xd` (`*_R`; `31` = `XZR`, a escrita se perde).
            int rd,
            /// Fonte: `Zn`, `Pn`, o `Vn`/`Xn|SP` copiado por `CPY_M_*`, ou o vetor lido por `LAST*`/`CLAST_V`/`CLAST_R`.
            int rn,
            /// Segundo operando: `Zm` (`CLAST_Z`, `SPLICE`, `SEL`) ou `Pm` (`ZIP`/`UZP`/`TRN` de predicado).
            int rm,
            /// Predicado que governa a operação: `P0`-`P7`, ou `P0`-`P15` em `SEL` (o único com 4 bits). Sem
            /// significado nas permutações de predicado.
            int pg,
            /// Endereço da instrução.
            long instructionAddress) implements SveIntegerOp64 {
        /// Operação do grupo.
        public enum Op {
            ZIP1_P, ZIP2_P, UZP1_P, UZP2_P, TRN1_P, TRN2_P, REV_P, PUNPKLO, PUNPKHI,
            COMPACT, EXPAND, SPLICE, SPLICE_SVE2, SEL,
            CLASTA_Z, CLASTB_Z, CLASTA_V, CLASTB_V, CLASTA_R, CLASTB_R,
            LASTA_V, LASTB_V, LASTA_R, LASTB_R, CPY_M_V, CPY_M_R,
            REVB_M, REVH_M, REVW_M, RBIT_M, REVD_M, REVB_Z, REVH_Z, REVW_Z, RBIT_Z, REVD_Z
        }
        @Override public int kind() { return Kind.SVE_PERMUTE_PREDICATED; }
    }

    /// `HISTCNT`/`HISTSEG` (B17.22, `FEAT_SVE2`): histogramas prefixo.
    ///
    /// **`HISTCNT` (`esz ∈ {2, 3}`, `.S`/`.D`) opera no VETOR INTEIRO, não por segmento** (achado que corrige a
    /// spec original da task — medido em `helper_sve2_histcnt_{s,d}` do QEMU: os laços `i`/`j` varrem `0..opr_sz`
    /// sem reiniciar a cada 16 bytes). Para cada elemento ATIVO `i` de `Zn` (por `pg`), `Zd[i]` = quantos
    /// elementos ATIVOS `j <= i` (`j` também filtrado por `pg`) têm `Zn[j] == Zm[i]`— conta só índices MENORES OU
    /// IGUAIS a `i`, nunca maiores; elemento inativo de `Zn` grava `Zd[i] = 0`. `pg` filtra tanto `Zn[i]`/`Zm[i]`
    /// (o comparado) quanto cada `Zn[j]`/`Zm[j]` candidato.
    ///
    /// **`HISTSEG` (só `esz = 0`, byte — `helper_sve2_histseg`) opera POR SEGMENTO de 128 bits, sem predicado**:
    /// `Zd[e]` = quantos bytes do MESMO segmento de `Zm` são iguais a `Zn[e]` (conta todos os 16 bytes do
    /// segmento, incluindo o próprio). Os bits de `esz` de `HISTSEG` são tecnicamente livres no `.decode` (o
    /// `trans_HISTSEG` do QEMU não os lê) — transcrito como aceito para os 4 valores, igual ao QEMU.
    record Histogram(
            /// `true` = `HISTCNT` (vetor inteiro, com predicado); `false` = `HISTSEG` (por segmento, sem predicado).
            boolean counting,
            int esz,
            /// Destino (`Zd`).
            int rd,
            /// Predicado governante (`Pg`); sem significado em `HISTSEG`.
            int pg,
            int rn,
            int rm,
            long instructionAddress) implements SveIntegerOp64 {
        @Override public int kind() { return Kind.SVE_HISTOGRAM; }
    }

    /// `LUTI2`/`LUTI4` (B17.22, `FEAT_LUT` — {@link dev.vitorsilverio.armjitter.arch64.Aarch64Feature#LOOKUP_TABLE}):
    /// tabela de consulta vetorial. `Zn` é a TABELA (só os primeiros `2^indexBits` elementos são alcançáveis —
    /// `LUTI2` = 4 entradas, `LUTI4` = 16); `Zm` guarda os índices empacotados a `indexBits` bits por elemento de
    /// SAÍDA, com `elements × indexBits × (2^indexBits / elements-por-grupo)` = `VL` bits — o campo `index` do
    /// encoding seleciona qual GRUPO de índices dentro de `Zm` (não é o índice de um elemento individual). Medido
    /// em `do_lut_b`/`do_lut_h`/`HELPER(gvec_luti{2,4}_{b,h})` do QEMU. `LUTI4_2h` concatena a tabela de DOIS
    /// registradores (`Zn` e `Zn+1 mod 32`) para alcançar as 16 entradas de halfword (256 bits) mesmo com
    /// `VL = 128` — {@link #tableRegisters} `= 2` sinaliza essa forma.
    record LookupTable(
            /// `true` = `LUTI4` (índice de 4 bits, 16 entradas); `false` = `LUTI2` (2 bits, 4 entradas).
            boolean four,
            /// `0` = byte, `1` = halfword. `LUTI4_1b`/`LUTI2_1b` só `esz = 0`; os demais só `esz = 1`.
            int esz,
            int rd,
            /// `Zn`: primeiro (ou único) registrador da tabela.
            int rn,
            /// `Zm`: os índices empacotados.
            int rm,
            /// Grupo de índices dentro de `Zm` (largura depende da forma: `2` bits em `LUTI2_1b`, `3` em
            /// `LUTI2_1h`, `1` em `LUTI4_1b`, `2` em `LUTI4_1h`/`LUTI4_2h`).
            int index,
            /// `1` (tabela num registrador só) ou `2` (`LUTI4_2h`: tabela em `Zn` e `Zn+1`).
            int tableRegisters,
            long instructionAddress) implements SveIntegerOp64 {
        @Override public int kind() { return Kind.SVE_LOOKUP_TABLE; }
    }

    /// `SCLAMP`/`UCLAMP`/`FCLAMP` (B17.22): `Zd = clamp(Zd, min = Zn, max = Zm)` — `Zd = min(max(Zd, Zn), Zm)`.
    /// `SCLAMP`/`UCLAMP` são `FEAT_SME`/`FEAT_SVE2p1` (inteiro, com/sem sinal); `FCLAMP` é `FEAT_SME2`/
    /// `FEAT_SVE2p1` (`esz ∈ {1, 2, 3}` = H/S/D) e usa `minNum`/`maxNum` (a variante NÃO propagadora de NaN — as
    /// mesmas primitivas de `FMAXNM`/`FMINNM`, `SveFloat.maxMinNumber`), medido em `FCLAMP` do `sme_helper.c` do
    /// QEMU (`TYPE_minnum(TYPE_maxnum(nn, *dd), mm)`). `esz = 0` de `FCLAMP` codifica `BFloat16`
    /// (`FEAT_SVE_B16B16`) — **pendência nomeada** (nenhuma arquitetura declara essa feature ainda; entra junto
    /// com a trilha BFloat16/B17.27).
    record Clamp(
            Op op,
            int esz,
            /// Acumulador E destino (`Zda` = `Zd`, `MOVPRFX`-construtivo).
            int rd,
            /// `Zn`: o limite MÍNIMO.
            int rn,
            /// `Zm`: o limite MÁXIMO.
            int rm,
            long instructionAddress) implements SveIntegerOp64 {
        public enum Op { SCLAMP, UCLAMP, FCLAMP }
        @Override public int kind() { return Kind.SVE_CLAMP; }
    }

    /// `AESE`/`AESD`/`AESMC`/`AESIMC` vetoriais (B17.24, `FEAT_SVE_AES`) — opera POR SEGMENTO de 128 bits
    /// (`VL/128` blocos AES independentes numa instrução só), reusando o núcleo
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto} do A64 (mesma S-box, sem tabela nova). Mirror do
    /// formato de {@link CryptoOp64.Aes}: para `AESE`/`AESD`, {@link #rn} é o SEGUNDO operando por segmento (`Zm` no
    /// encoding real, `@rdn_rm_e0` — `Zd` é lido E escrito, escrita destrutiva real); para `AESMC`/`AESIMC`,
    /// {@link #rn} é o MESMO registrador que {@link #rd} (o `.decode` não tem campo de origem, `00000` fixo — o
    /// decoder resolve essa auto-referência). Recusado em modo streaming (`TRANS_FEAT_STREAMING_IF` do QEMU real
    /// libera um subconjunto sob `FEAT_SSVE_AES`, não modelado ainda — **pendência nomeada**).
    record CryptoAes(
            Ir64CryptoAesOp op,
            /// `Zd`: destino (e, para `AESE`/`AESD`, primeiro operando lido).
            int rd,
            /// `Zm` (`AESE`/`AESD`) ou o próprio `rd` repetido (`AESMC`/`AESIMC`, sem operando de origem real).
            int rn,
            long instructionAddress) implements SveIntegerOp64 {
        @Override public int kind() { return Kind.SVE_CRYPTO_AES; }
    }

    /// `SM4E` vetorial (B17.24, `FEAT_SVE_SM4`) — rodada de cifra SM4 por segmento de 128 bits, reusando a S-box
    /// de {@link dev.vitorsilverio.armjitter.executor64} (sem tabela nova). Mesma forma de 2 operandos do A64
    /// escalar ({@link CryptoOp64.Sm4Encrypt}): {@link #rd} é o estado ATUAL do bloco (lido E escrito, destrutivo —
    /// `@rdn_rm_e0`), {@link #rn} carrega as 4 subchaves de rodada POR SEGMENTO. Sempre não-streaming
    /// (`TRANS_FEAT_NONSTREAMING` do QEMU real, sem exceção SME).
    record CryptoSm4Encrypt(
            /// `Zd`: destino (e primeiro operando — estado atual do bloco).
            int rd,
            /// `Zm`: as 4 subchaves de rodada por segmento.
            int rn,
            long instructionAddress) implements SveIntegerOp64 {
        @Override public int kind() { return Kind.SVE_CRYPTO_SM4_ENCRYPT; }
    }

    /// `SM4EKEY` vetorial (B17.24, `FEAT_SVE_SM4`) — expansão de chave SM4 por segmento de 128 bits. Mesma forma
    /// de 3 operandos do A64 escalar ({@link CryptoOp64.Sm4KeyUpdate}): função PURA de {@link #rn} (estado atual da
    /// chave) e {@link #rm} (constantes de rodada `CK`) por segmento — {@link #rd} NUNCA é lido. Sempre
    /// não-streaming (`TRANS_FEAT_NONSTREAMING`, igual a {@link CryptoSm4Encrypt}).
    record CryptoSm4KeyUpdate(
            int rd,
            /// `Zn`: estado atual da chave.
            int rn,
            /// `Zm`: as 4 constantes de rodada `CK`.
            int rm,
            long instructionAddress) implements SveIntegerOp64 {
        @Override public int kind() { return Kind.SVE_CRYPTO_SM4_KEY_UPDATE; }
    }

    /// `RAX1` vetorial (B17.24, `FEAT_SVE_SHA3`) — `Zd = Zn XOR rotateLeft(Zm, 1)`, elemento a elemento de
    /// **64 bits** (`esz` do formato é `0` mas a operação é sempre em doubleword — mesma armadilha de `REVD`,
    /// B17.11), sem predicado, por toda a largura de `VL` (não por segmento: é uma XOR/rotação elemento a
    /// elemento, sem interação entre elementos vizinhos). Mesma fórmula do caso `RAX1` de
    /// {@link CryptoOp64.Sha3TwoSourceRotate} do A64 escalar, rotação à ESQUERDA fixa em `1` (sem campo de
    /// imediato no encoding real). Recusado em modo streaming (`FEAT_SME2p1` libera um subconjunto, não modelado
    /// ainda — **pendência nomeada**, mesma disciplina de {@link CryptoAes}).
    record CryptoRax1(
            int rd,
            int rn,
            int rm,
            long instructionAddress) implements SveIntegerOp64 {
        @Override public int kind() { return Kind.SVE_CRYPTO_RAX1; }
    }
}
