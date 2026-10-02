package dev.vitorsilverio.armjitter.ir64;

/// Operações SVE/SVE2 de ponto flutuante: aritmética, multiply-add, comparação e redução, unárias,
/// conversões, `BFloat16` e `FP8`.
///
/// Sub-interface selada de {@link SveOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface SveFpOp64 extends SveOp64 permits SveFpOp64.FpArithmetic,
        SveFpOp64.FpMultiplyAdd, SveFpOp64.FpCompareReduce, SveFpOp64.FpUnary,
        SveFpOp64.FpConvertFp8, SveFpOp64.FpConvertToFp8, SveFpOp64.FpPairwise,
        SveFpOp64.FpMatrixMultiply, SveFpOp64.FpConvertOddElements, SveFpOp64.FpLogB,
        SveFpOp64.Fp8FusedMultiplyAddLong, SveFpOp64.Fp8DotProduct,
        SveFpOp64.FpMultiplyAddLongWiden, SveFpOp64.FpMultiplyAddLongWidenBFloat16,
        SveFpOp64.FpDotProductWiden, SveFpOp64.FpDotProductWidenBFloat16 {

    /// Aritmética de ponto flutuante SVE (B17.13) — 32 encodings: as 6 não predicadas (`FADD`/`FSUB`/`FMUL`/`FTSMUL`/`FRECPS`/`FRSQRTS`),
    /// `FRECPE`/`FRSQRTE`, as 15 predicadas por vetor, as 8 com imediato de UM bit e `FTMAD`. Semântica em `SveFloat`.
    ///
    /// As formas destrutivas trazem `rn` = `rd`. As reversas (`FSUBR`/`FDIVR`) NÃO trocam operandos aqui: `reversed`
    /// manda calcular `op(Zm, Zn)` — assim o registrador e o imediato usam a mesma regra.
    record FpArithmetic(
            Op op,
            /// Formato do elemento: `1` = meia, `2` = simples, `3` = dupla; `0` = `BFloat16` (`FEAT_SVE_B16B16`,
            /// B17.27) só em `ADD`/`SUB`/`MUL` não predicadas e `ADD`/`SUB`/`MUL`/`MAXNM`/`MINNM`/`MAX`/`MIN`/
            /// `SCALE` predicadas — nas demais linhas `0` continua não alocado e nunca chega aqui.
            int esz,
            int rd,
            /// Primeiro operando (`Zn`; `Zdn` nas formas destrutivas).
            int rn,
            /// Segundo operando (`Zm`); sem significado nas formas com imediato e nas unárias.
            int rm,
            /// Predicado governante `P0`-`P7`; sem significado quando {@code predicated} é falso.
            int pg,
            /// `false` nas 6 não predicadas e nas unárias (todos os elementos ativos).
            boolean predicated,
            /// Forma reversa (`FSUBR`/`FDIVR`): o resultado é `op(Zm, Zn)`.
            boolean reversed,
            /// `FADD_zpzi`…`FMIN_zpzi`: o segundo operando é a constante escolhida por {@code immediate} (`0`/`1`).
            boolean immediateForm,
            /// Bit do imediato de 1 bit (formas `_zpzi`) ou `imm3` do `FTMAD` (índice do coeficiente).
            int immediate,
            /// Endereço da instrução.
            long instructionAddress) implements SveFpOp64 {
        /// Operação do grupo.
        public enum Op {
            ADD, SUB, MUL, DIV, MAXNM, MINNM, MAX, MIN, ABD, SCALE, MULX, AMAX, AMIN,
            TSMUL, RECPS, RSQRTS, RECPE, RSQRTE, TMAD
        }
        @Override public int kind() { return Kind.SVE_FP_ARITHMETIC; }
    }

    /// Multiply-add de ponto flutuante SVE, `FMUL` indexado e aritmética complexa (B17.14) — 24 encodings:
    /// `FMLA`/`FMLS`/`FNMLA`/`FNMLS` predicados (as 4 do acumulador e as 4 do multiplicando `FMAD`/`FMSB`/`FNMAD`/`FNMSB`),
    /// `FMLA`/`FMLS`/`FMUL` indexados, `FCADD`, `FCMLA` e `FCMLA` indexado. Semântica em `SveFloat`.
    ///
    /// **A conta é sempre `Zd = ra + rn × rm`** (fundida); as formas que "escrevem o multiplicando" só mudam QUAIS
    /// campos do encoding viram `rn`/`rm`/`ra` no decoder. Nas formas destrutivas o `ra`/`rn` traz o próprio `rd`.
    record FpMultiplyAdd(
            Op op,
            /// Formato do elemento: `1` = meia, `2` = simples, `3` = dupla; `0` = `BFloat16` (`FEAT_SVE_B16B16`,
            /// B17.27) só em `FMLA`/`FMLS` predicados e `FMLA`/`FMLS`/`FMUL` indexados — `FNMLA`/`FNMLS`, as formas
            /// `FMAD`/`FMSB`/`FNMAD`/`FNMSB` e `FCADD`/`FCMLA` continuam sem BFloat16 (confirmado via
            /// `aarch64-none-elf-as`/`objdump` reais).
            int esz,
            int rd,
            /// Multiplicando (`Zn`); em `FCADD`/`FCMLA` o primeiro operando complexo.
            int rn,
            /// Multiplicador (`Zm`); nas formas indexadas o registrador do qual se lê o elemento.
            int rm,
            /// Addend (`Za`, ou `Zda` = `rd` nas destrutivas); sem significado em `FMUL` indexado e `FCADD`.
            int ra,
            /// Predicado governante `P0`-`P7`; sem significado quando {@code predicated} é falso.
            int pg,
            boolean predicated,
            /// Formas por elemento indexado (`FMLA`/`FMLS`/`FMUL`/`FCMLA`): `index` escolhe o elemento (ou o par) DENTRO de cada segmento de 128 bits.
            boolean indexed,
            /// Índice do elemento (ou do par real/imaginário, em `FCMLA` indexado).
            int index,
            /// Rotação: 1 bit em `FCADD` (`0` = 90°, `1` = 270°), 2 bits em `FCMLA` (`0`/`90`/`180`/`270` graus ÷ 90).
            int rot,
            /// Endereço da instrução.
            long instructionAddress) implements SveFpOp64 {
        /// Operação do grupo.
        public enum Op {
            FMLA, FMLS, FNMLA, FNMLS, FMUL, FCADD, FCMLA
        }
        @Override public int kind() { return Kind.SVE_FP_MULTIPLY_ADD; }
    }

    /// Comparação de ponto flutuante SVE que produz predicado e reduções de ponto flutuante (B17.15) — 24 encodings:
    /// `FCMGE`/`FCMGT`/`FCMEQ`/`FCMNE`/`FCMUO`/`FACGE`/`FACGT` (vetor×vetor) e `FCMGE`/`FCMGT`/`FCMLT`/`FCMLE`/`FCMEQ`/
    /// `FCMNE` com zero; `FADDV`/`FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` (árvore), as cinco `*QV` por segmento de 128 bits
    /// (`FEAT_SVE2p1`) e `FADDA` (serial). Semântica em `SveFloat`/`SveFpCompareReduceOps`.
    ///
    /// **A comparação FP NÃO altera `NZCV`** (só a inteira, da B17.9, tem forma que seta flags); ela pode sujar o `FPSR`.
    record FpCompareReduce(
            Op op,
            /// Formato do elemento: `1` = meia, `2` = simples, `3` = dupla (`0` nunca chega aqui).
            int esz,
            /// Predicado de destino (`P0`-`P15`) nas comparações; registrador `V` de destino nas reduções.
            int rd,
            /// Vetor `Zn` (em `FADDA`, o registrador escalar `Vdn` de entrada e de saída).
            int rn,
            /// Vetor `Zm` (comparação vetor×vetor e `FADDA`); sem significado nas demais.
            int rm,
            /// Predicado governante `P0`-`P7`.
            int pg,
            /// Comparação com zero (`FCM<cc> Pd.T, Pg/Z, Zn.T, #0.0`): o segundo operando é `+0.0`, não `Zm`.
            boolean zero,
            /// Endereço da instrução.
            long instructionAddress) implements SveFpOp64 {
        /// Operação do grupo. As comparações com zero reusam `FCMGE`/`FCMGT`/`FCMLT`/`FCMLE`/`FCMEQ`/`FCMNE` com {@code zero}.
        public enum Op {
            FCMGE, FCMGT, FCMLT, FCMLE, FCMEQ, FCMNE, FCMUO, FACGE, FACGT,
            FADDV, FMAXNMV, FMINNMV, FMAXV, FMINV,
            FADDQV, FMAXNMQV, FMINNMQV, FMAXQV, FMINQV,
            FADDA
        }
        @Override public int kind() { return Kind.SVE_FP_COMPARE_REDUCE; }
    }

    /// Operações unárias de ponto flutuante SVE predicadas (B17.16) — 105 encodings: conversões de precisão (`FCVT`,
    /// `FCVTX`, `BFCVT`), FP→inteiro (`FCVTZS`/`FCVTZU`), inteiro→FP (`SCVTF`/`UCVTF`), `FRINT*`, `FRINT32/64{X,Z}`,
    /// `FRECPX` e `FSQRT`, cada uma nas formas merging (`_m`, elemento inativo preservado) e zeroing (`_z`, elemento
    /// inativo zerado, `FEAT_SVE2p2`). Semântica em `SveFloat`/`SveFpUnaryOps`.
    ///
    /// O elemento do VETOR tem o maior dos dois tamanhos; o valor menor ocupa os bits baixos e, ao ESCREVER, o resto do
    /// elemento é zero (nunca há extensão de sinal).
    record FpUnary(
            Op op,
            /// Formato/largura de ORIGEM: `1` = 16 bits, `2` = 32, `3` = 64 (FP ou inteiro, conforme a operação).
            int source,
            /// Formato/largura de DESTINO, na mesma codificação; `0` = BFloat16 (só em `BFCVT`).
            int destination,
            /// `true` = zeroing (`_z`): elemento inativo vira zero em vez de manter o valor antigo.
            boolean zeroing,
            /// Vetor de destino `Zd`.
            int rd,
            /// Vetor de origem `Zn`.
            int rn,
            /// Predicado governante `P0`-`P7`.
            int pg,
            /// Endereço da instrução.
            long instructionAddress) implements SveFpOp64 {
        /// Operação do grupo.
        public enum Op {
            FCVT, FCVTX, BFCVT, FCVTZS, FCVTZU, SCVTF, UCVTF,
            FRINTN, FRINTP, FRINTM, FRINTZ, FRINTA, FRINTX, FRINTI,
            FRINT32X, FRINT64X, FRINT32Z, FRINT64Z,
            FRECPX, FSQRT
        }
        @Override public int kind() { return Kind.SVE_FP_UNARY; }
    }

    /// SVE2 `F1CVT`/`F2CVT`/`F1CVTLT`/`F2CVTLT`/`BF1CVT`/`BF2CVT`/`BF1CVTLT`/`BF2CVTLT` (B17.23, `FEAT_SVE_F8CVT`)
    /// — alarga `fp8` para `binary16`/`bfloat16`, **sem predicado**, lendo UM byte de cada par adjacente de
    /// `Zn.B` por elemento de destino: {@code F1CVT}/{@code F2CVT} leem o byte PAR (`2i`), {@code F1CVTLT}/
    /// {@code F2CVTLT} o byte ÍMPAR (`2i+1`, `top`). O `1`/`2` do mnemônico escolhe o formato `FPMR.F8S1`/`F8S2`
    /// (via {@link #stream2}) — inclusive a escala (`FPMR.LSCALE`/`LSCALE2`, mascarada a 4 bits para destino
    /// `binary16` e a 6 para `bfloat16`, `Aarch64Core#fp8WidenScale`/`#fp8WidenScaleForBFloat16`; medido contra
    /// `sve2_fcvt_hb`/`sve2_bfcvt` do QEMU real). `Zd` tem `VL/2` elementos (metade dos bytes de `Zn`).
    record FpConvertFp8(
            int rd,
            int rn,
            /// `false` = formato/escala do stream 1 (`FPMR.F8S1`/`LSCALE`); `true` = stream 2 (`F8S2`/`LSCALE2`).
            boolean stream2,
            /// `false` = lê o byte PAR de cada par (`F1CVT`/`F2CVT`); `true` = o ÍMPAR (`F1CVTLT`/`F2CVTLT`).
            boolean top,
            /// `false` = destino `binary16` (`F*CVT*`); `true` = `bfloat16` (`BF*CVT*`).
            boolean bfloat16Destination,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP_CONVERT_FP8; }
    }

    /// SVE2 `FCVTN`/`BFCVTN`/`FCVTNB`/`FCVTNT` (B17.23, `FEAT_SVE_F8CVT`) — estreita PARA `fp8`, **sem
    /// predicado**, lendo um PAR de registradores fonte (`Zn`, `Zn+1` — `%rn_ax2`). Formato/escala de destino
    /// vêm de `FPMR.F8D`/`NSCALE` (`Aarch64Core#fp8DestinationFormat`/`#fp8NarrowScale`, compartilhados pelas
    /// quatro), `FPMR.OSC` decide se overflow satura (`Aarch64Core#fp8OverflowSaturatesToMaxNormal`).
    ///
    /// {@code FCVTN}/{@code BFCVTN} ({@link #wideSource} falso) leem `Zn`/`Zn+1` como `VL/2` elementos
    /// `binary16`/`bfloat16` cada e enchem `Zd` por inteiro: `Zd.B[2i] = FP8(Zn[i])`, `Zd.B[2i+1] =
    /// FP8(Zn+1[i])` — medido contra `sve2_fcvtn_bh`/`sve2_bfcvtn_bh` do QEMU real. {@code FCVTNB}/
    /// {@code FCVTNT} ({@link #wideSource} verdadeiro) leem `Zn`/`Zn+1` como `VL/4` elementos `binary32` cada e
    /// empacotam DOIS `fp8` por slot de 16 bits de `Zd`: {@code FCVTNB} ({@link #top} falso) escreve o byte
    /// BAIXO de cada slot `H` e ZERA o alto; {@code FCVTNT} ({@link #top} verdadeiro) escreve só o byte ALTO,
    /// PRESERVANDO o baixo (idioma "bottom depois top" para popular `Zd` inteiro com duas instruções, medido
    /// contra `sve2_fcvtnb_bs`/`sve2_fcvtnt_bs`).
    record FpConvertToFp8(
            int rd,
            /// Base do par fonte (`Zn`; `Zn+1` é lido também).
            int rn,
            /// Só quando {@code !wideSource}: `false` = fonte `binary16` (`FCVTN`); `true` = `bfloat16` (`BFCVTN`).
            boolean bfloat16Source,
            /// `false` = fonte `binary16`/`bfloat16`, escreve `Zd` inteiro (`FCVTN`/`BFCVTN`); `true` = fonte
            /// `binary32`, escreve metade dos slots `H` de `Zd` (`FCVTNB`/`FCVTNT`).
            boolean wideSource,
            /// Só quando {@code wideSource}: `false` = byte baixo, zera o alto (`FCVTNB`); `true` = só o byte
            /// alto, preserva o baixo (`FCVTNT`).
            boolean top,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP_CONVERT_TO_FP8; }
    }

    /// SVE2 `FADDP`/`FMAXNMP`/`FMINNMP`/`FMAXP`/`FMINP` (B17.23, `FEAT_SVE2`) — soma/máximo/mínimo par a par
    /// DENTRO de cada segmento de 128 bits, forma destrutiva `@rdn_pg_rm` (`rd` = `rn` = `Zdn`). Só MERGING
    /// (não há forma `_z`): elemento inativo preserva o valor antigo de `Zdn`. Padrão de escrita, medido contra
    /// `DO_ZPZZ_PAIR_FP` do QEMU real: para cada posição de PAR `p` (`0`, `2`, `4`, … dentro do segmento),
    /// `Zdn[p] = op(Zn[p], Zn[p+1])` (par de `Zn`) e `Zdn[p+1] = op(Zm[p], Zm[p+1])` (par de `Zm`, MESMOS
    /// índices) — intercalado, não "primeira metade de `Zn`, segunda de `Zm`".
    record FpPairwise(
            Op op,
            /// Formato do elemento: `1` = meia, `2` = simples, `3` = dupla (`0` é BFloat16, `FEAT_SVE_B16B16`,
            /// pendência nomeada — nunca chega aqui).
            int esz,
            /// Acumulador e destino (`Zdn` = `Zd` = `Zn`).
            int rd,
            /// Segundo operando (`Zm`).
            int rm,
            /// Predicado governante `P0`-`P7`.
            int pg,
            long instructionAddress) implements SveFpOp64 {
        /// Operação do grupo. `FMAXP`/`FMINP` usam a variante NÃO "Number" (propaga `NaN`, `SveFloat.maxMin`);
        /// `FMAXNMP`/`FMINNMP` usam a "Number" (`SveFloat.maxMinNumber`).
        public enum Op { FADDP, FMAXNMP, FMINNMP, FMAXP, FMINP }
        @Override public int kind() { return Kind.SVE_FP_PAIRWISE; }
    }

    /// SVE2 `BFMMLA`/`FMMLA_s`/`FMMLA_d`/`FMMLA_sb`/`FMMLA_hb` (B17.23) — multiplicação de matriz `2×2`
    /// acumulada por segmento de 128 bits (`Zda += Zn × Zm`, `Zda` = `Zd`, `@rda_rn_rm_ex`). `BFMMLA`
    /// (`FEAT_SVE_BF16`) e as `fp8` (`FMMLA_sb`/`FMMLA_hb`, `FEAT_F8F32MM`/`FEAT_F8F16MM`) somam os `K` produtos
    /// FUNDIDOS (um único arredondamento, `double`/`f8dotadd_*` do QEMU real). **`FMMLA_s`/`FMMLA_d`
    /// (`FEAT_F32MM`/`FEAT_F64MM`) NÃO são fundidas**: cada produto é arredondado separadamente e a soma dos
    /// dois também — medido contra `HELPER(fmmla_s)`/`HELPER(fmmla_d)` do QEMU real (`float32_mul`/
    /// `float32_add` em sequência, nunca `muladd`).
    record FpMatrixMultiply(
            Op op,
            /// Formato do elemento de DESTINO/acumulador: `1` = meia (`FMMLA_hb`), `2` = simples (`FMMLA_s`/
            /// `FMMLA_sb`), `3` = dupla (`FMMLA_d`). `BFMMLA` também usa `2` (acumulador `f32`, fonte `bf16`).
            int esz,
            /// Acumulador e destino (`Zda` = `Zd`).
            int rd,
            int rn,
            int rm,
            long instructionAddress) implements SveFpOp64 {
        /// Operação do grupo (a fonte/formato de cada uma é fixo, não configurável em tempo de execução).
        public enum Op { BFMMLA, FMMLA_S, FMMLA_D, FMMLA_SB, FMMLA_HB }
        @Override public int kind() { return Kind.SVE_FP_MATRIX_MULTIPLY; }
    }

    /// SVE2 `FCVTNT_sh`/`FCVTLT_hs`/`FCVTNT_ds`/`FCVTLT_sd`/`FCVTXNT_ds`/`BFCVTNT` (`_m`/`_z`, B17.23) — as 12
    /// conversões de precisão "odd elements" (`FCVTX_ds_m`, a 13ª linha do recorte, reusa {@link FpUnary}
    /// direto — MESMO helper que `FCVTX_ds_z` da B17.16, `gen_helper_sve_fcvt_ds` do QEMU real, só decodificada
    /// de um layout de bits diferente). O predicado é testado na granularidade LARGA (`wideEsz`, medido contra
    /// `DO_FCVTNT`/`DO_FCVTLT` do QEMU real: o laço decrementa por `sizeof(TYPEW)`).
    ///
    /// `FCVTNT`/`BFCVTNT` (estreita: fonte larga, destino estreito) escrevem SÓ o elemento estreito de ÍNDICE
    /// ÍMPAR (topo) de cada par e PRESERVAM o par (zerar é bug); `FCVTLT` (alarga) LÊ o elemento estreito
    /// ÍMPAR e escreve o elemento largo inteiro. Merging (`!zeroing`): elemento inativo não é tocado (nem
    /// escrito, nem lido) — o `Zd` fica exatamente como estava.
    record FpConvertOddElements(
            Op op,
            /// Tamanho do elemento LARGO/contêiner: `2` = simples (`_sh`), `3` = dupla (`_ds`). O estreito é
            /// sempre {@code wideEsz - 1}.
            int wideEsz,
            /// `true` só em `FCVTXNT_ds`: usa arredondamento "para ímpar" (`FPROUNDING_ODD`) em vez do modo de
            /// `FPCR.RMode` — mesmo helper de `FCVTX_ds`, `do_frint_mode` do QEMU real.
            boolean roundToOdd,
            /// `true` só em `BFCVTNT`: destino/origem estreito é `bfloat16`, não `binary16`.
            boolean bfloat16,
            boolean zeroing,
            int rd,
            int rn,
            /// Predicado governante `P0`-`P7`.
            int pg,
            long instructionAddress) implements SveFpOp64 {
        public enum Op { FCVTNT, FCVTLT, FCVTXNT }
        @Override public int kind() { return Kind.SVE_FP_CONVERT_ODD_ELEMENTS; }
    }

    /// SVE2 `FLOGB` (`_m`/`_z`, B17.23, `FEAT_SVE2`) — expoente (base 2) de `Zn` como INTEIRO da MESMA largura
    /// (`&rpr_esz`, um único `esz` para fonte e destino). Casos especiais medidos contra
    /// `do_float{16,32,64}_logb_as_int` do QEMU real: zero e `NaN` ⇒ o mínimo `int` representável na largura
    /// (levanta `Invalid`); Infinito ⇒ o máximo `int`; subnormal com `FZ` desligado ⇒ `-viés - clz(fração)`
    /// (com `FZ` ligado, tratado como zero); normal ⇒ `expoente_não_enviesado - viés`.
    record FpLogB(
            /// Largura do elemento fonte E destino: `1` = meia, `2` = simples, `3` = dupla.
            int esz,
            boolean zeroing,
            int rd,
            int rn,
            /// Predicado governante `P0`-`P7`.
            int pg,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP_LOGB; }
    }

    /// SVE2 `FMLAL_hb`/`FMLALL_sb`, vetorial e indexado (B17.23, `FEAT_FP8FMA`) — multiply-accumulate `fp8`
    /// FUNDIDO por TODO o vetor (sem segmentação): `Zda[i] += fp8(Zn[byte i]) × fp8(Zm[byte i])`, escalado por
    /// `FPMR.LSCALE`/downscale e somado num único arredondamento (reusa {@code AdvSimdLanes#fp8FusedMultiplyAdd}).
    /// Medido contra `gvec_fmla_hb`/`gvec_fmla_sb`/`gvec_fmla_idx_hb`/`gvec_fmla_idx_sb` do QEMU real.
    ///
    /// **Não indexado**: o MESMO {@link #sourceSelect} (`idxn` do encoding) escolhe, para AMBOS `Zn` e `Zm`, o
    /// byte par (`0`) ou ímpar (`1`, `FMLAL_hb`) — ou um de 4 (`FMLALL_sb`) — de cada slot de destino.
    /// **Indexado**: {@link #sourceSelect} continua valendo só para `Zn`; `Zm` contribui um ÚNICO byte fixo
    /// por SEGMENTO de 128 bits, escolhido por {@link #index} (byte absoluto dentro do segmento).
    record Fp8FusedMultiplyAddLong(
            /// `false` = destino meia precisão (`FMLAL_hb`); `true` = simples (`FMLALL_sb`).
            boolean wideDestination,
            int rd,
            int rn,
            int rm,
            /// Seletor `idxn` do encoding — aplicado a `Zn` sempre, e a `Zm` só quando {@code !indexed}.
            int sourceSelect,
            boolean indexed,
            /// Byte fixo dentro do segmento de 128 bits de `Zm`; sem significado quando {@code !indexed}.
            int index,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP8_FUSED_MULTIPLY_ADD_LONG; }
    }

    /// SVE2 `FDOT_hb`/`FDOT_sb`, vetorial e indexado (B17.23, `FEAT_FP8DOT2`/`FEAT_FP8DOT4`) — produto escalar
    /// `fp8` (2 ou 4 vias) FUNDIDO por TODO o vetor (sem segmentação no vetorial): `Zda[i] += Σ fp8(Zn[i]byte_k)
    /// × fp8(Zm[i]byte_k)`, reusa {@code AdvSimdLanes#fp8DotProduct}. Medido contra `gvec_fdot_hb`/
    /// `gvec_fdot_sb`/`gvec_fdot_idx_hb`/`gvec_fdot_idx_sb` do QEMU real.
    ///
    /// **Indexado**: `Zm` contribui um ÚNICO grupo (2 ou 4 bytes) fixo por SEGMENTO de 128 bits, escolhido por
    /// {@link #index} (índice de grupo dentro do segmento); `Zn` continua lido elemento a elemento.
    record Fp8DotProduct(
            /// `false` = destino meia precisão, 2 vias (`FDOT_hb`); `true` = simples, 4 vias (`FDOT_sb`).
            boolean wideDestination,
            int rd,
            int rn,
            int rm,
            boolean indexed,
            /// Índice do grupo fixo de `Zm` dentro do segmento; sem significado quando {@code !indexed}.
            int index,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP8_DOT_PRODUCT; }
    }

    /// SVE2 `FMLALB`/`FMLALT`/`FMLSLB`/`FMLSLT`, vetorial (`_zzzw`) e indexado (`_zzxw`) (B17.23,
    /// `FEAT_SVE2`/`FEAT_SME`) — multiply-add-long de `binary16` para `binary32`: `Zda[i] += (float)Zn[2i+sel]
    /// × (float)Zm[…]`, elemento largo (destino) SEMPRE `esz=2`. `top` ({@code sel}) escolhe o elemento
    /// estreito PAR (`B`, `false`) ou ÍMPAR (`T`, `true`) de `Zn` — e de `Zm` também, no vetorial. **Vetorial**:
    /// `Zm` lido no MESMO índice de `Zn` (`sve2_fmlal_zzzw_s`, sem segmentação). **Indexado**: `Zm` contribui um
    /// elemento fixo por SEGMENTO de 128 bits ({@link #index}, `sve2_fmlal_zzxw_s`).
    record FpMultiplyAddLongWiden(
            Op op,
            /// `false` = elemento estreito PAR (`B`); `true` = ÍMPAR (`T`).
            boolean top,
            int rd,
            int rn,
            int rm,
            boolean indexed,
            /// Índice do elemento estreito fixo de `Zm` dentro do segmento; sem significado quando
            /// {@code !indexed}.
            int index,
            long instructionAddress) implements SveFpOp64 {
        public enum Op { FMLAL, FMLSL }
        @Override public int kind() { return Kind.SVE_FP_MULTIPLY_ADD_LONG_WIDEN; }
    }

    /// SVE2 `BFMLALB`/`BFMLALT`/`BFMLSLB`/`BFMLSLT`, vetorial (`_zzzw`) e indexado (`_zzxw`) (B17.23,
    /// `FEAT_SVE_BF16`/`FEAT_SVE2p1`) — mesma forma de {@link FpMultiplyAddLongWiden}, mas fonte `bfloat16`
    /// (`gvec_bfmlal`/`gvec_bfmlal_idx`, MESMO helper compartilhado com o AdvSIMD, B19.7/B13.21) em vez de
    /// `binary16`. **Achado**: `BFMLALB`/`BFMLALT` exigem só `FEAT_SVE_BF16`; `BFMLSLB`/`BFMLSLT` (a forma
    /// subtrativa) exigem `FEAT_SVE2p1`/`FEAT_SME2` — features DIFERENTES (`aa64_sme_sve_bf16` vs
    /// `aa64_sme2_or_sve2p1` no QEMU real), gatear por linha.
    record FpMultiplyAddLongWidenBFloat16(
            Op op,
            boolean top,
            int rd,
            int rn,
            int rm,
            boolean indexed,
            int index,
            long instructionAddress) implements SveFpOp64 {
        public enum Op { BFMLAL, BFMLSL }
        @Override public int kind() { return Kind.SVE_FP_MULTIPLY_ADD_LONG_WIDEN_BFLOAT16; }
    }

    /// SVE2.1 `FDOT_zzzz`/`FDOT_zzxz` (B17.23, `FEAT_SVE2p1`/`FEAT_SME2`) — produto escalar de DUAS vias
    /// `binary16`→`binary32` (`gen_helper_sme2_fdot_h` do QEMU real — helper NOVO, não compartilhado com o
    /// AdvSIMD `BFDOT`/inteiro). **Indexado**: `Zm` contribui um par fixo por segmento de 128 bits.
    record FpDotProductWiden(
            int rd,
            int rn,
            int rm,
            boolean indexed,
            /// Índice do par fixo de `Zm` dentro do segmento; sem significado quando {@code !indexed}.
            int index,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP_DOT_PRODUCT_WIDEN; }
    }

    /// SVE `BFDOT_zzzz`/`BFDOT_zzxz` (B17.23, `FEAT_SVE_BF16`) — produto escalar de DUAS vias
    /// `bfloat16`→`binary32` (`gvec_bfdot`/`gvec_bfdot_idx`, MESMO helper compartilhado com o AdvSIMD, B19.7).
    /// Mesma forma de {@link FpDotProductWiden}.
    record FpDotProductWidenBFloat16(
            int rd,
            int rn,
            int rm,
            boolean indexed,
            int index,
            long instructionAddress) implements SveFpOp64 {
        @Override public int kind() { return Kind.SVE_FP_DOT_PRODUCT_WIDEN_BFLOAT16; }
    }
}
