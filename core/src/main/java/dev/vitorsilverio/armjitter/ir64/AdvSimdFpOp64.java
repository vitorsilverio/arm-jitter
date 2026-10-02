package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64VectorFpArithmeticExecutor;

/// Operações AdvSIMD de ponto flutuante: aritmética, conversão, números complexos (`FEAT_FCMA`),
/// `BFloat16` e `FP8`.
///
/// Sub-interface selada de {@link AdvSimdOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface AdvSimdFpOp64 extends AdvSimdOp64 permits
        AdvSimdFpOp64.FpArithmeticThreeSame, AdvSimdFpOp64.FpArithmeticThreeSameByElement,
        AdvSimdFpOp64.FpComplexAdd, AdvSimdFpOp64.FpScaleByInt, AdvSimdFpOp64.FpAbsoluteMaxMin,
        AdvSimdFpOp64.Fp8FusedMultiplyAddLong, AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement,
        AdvSimdFpOp64.Fp8DotProduct, AdvSimdFpOp64.Fp8DotProductByElement,
        AdvSimdFpOp64.FpComplexMultiplyAccumulate,
        AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement, AdvSimdFpOp64.FpArithmeticPairwise,
        AdvSimdFpOp64.FpArithmeticUnary, AdvSimdFpOp64.FpConvertFixedPoint,
        AdvSimdFpOp64.FpConvertPrecision, AdvSimdFpOp64.FpConvertToFp8,
        AdvSimdFpOp64.FpConvertFromFp8, AdvSimdFpOp64.FpAcrossLanes,
        AdvSimdFpOp64.FpDotProductBFloat16, AdvSimdFpOp64.FpDotProductBFloat16ByElement,
        AdvSimdFpOp64.FpMultiplyAddLongBFloat16, AdvSimdFpOp64.FpMultiplyAddLongBFloat16ByElement,
        AdvSimdFpOp64.FpMultiplyAddLong, AdvSimdFpOp64.FpMultiplyAddLongByElement,
        AdvSimdFpOp64.FpMatrixMultiplyAccumulateBFloat16 {

    /// AdvSIMD "three same" de ponto flutuante (`FADD_v`/`FSUB_v`/`FMUL_v`/.../`FRSQRTS_v`, B8.9) —
    /// os 3 operandos (`Rd`/`Rn`/`Rm`) têm o MESMO tamanho de elemento {@link #esz}, sempre `2`
    /// (simples) ou `3` (dupla) — meia-precisão (`FEAT_FP16`) fica fora (`docs/isa-nao-aplicavel.tsv`).
    /// Cobre a forma VETORIAL (B8.9) e a forma ESCALAR (`FMULX_s`/`FCMEQ_s`/`FCMGE_s`/`FCMGT_s`/
    /// `FACGE_s`/`FACGT_s`/`FABD_s`/`FRECPS_s`/`FRSQRTS_s`, B19.2) — ver {@link #scalar} e
    /// {@link Ir64VectorFpThreeSameOp}.
    record FpArithmeticThreeSame(
            /// Operação a executar.
            Ir64VectorFpThreeSameOp op,
            /// `true` para a forma ESCALAR AdvSIMD — processa só o elemento `0` e {@link #q} é
            /// ignorado, mesma disciplina de {@link FpArithmeticThreeSameByElement#scalar}.
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ignorado se {@link #scalar}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes: sempre `2` (single, 32 bits) ou `3`
            /// (double, 64 bits).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_ARITHMETIC_THREE_SAME; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeThreeSame(core, this);
        }
    }

    /// AdvSIMD "vector/scalar × indexed element" de ponto flutuante (B8.19) — `FMUL`/`FMLA`/
    /// `FMLS`/`FMULX`, vetorial `_vi` E escalar `_si`, só simples/dupla (`esz` `2`/`3` — meia-
    /// precisão é `FEAT_FP16`, fora do Cortex-A53). Reaproveita {@link Ir64VectorFpThreeSameOp} —
    /// MESMA semântica de {@link FpArithmeticThreeSame}, exceto que `Rm` sempre contribui o
    /// elemento {@link #index}, nunca `i`.
    record FpArithmeticThreeSameByElement(
            /// Operação a executar — só `MUL`/`MLA`/`MLS`/`MULX` são válidas aqui (G8).
            Ir64VectorFpThreeSameOp op,
            /// `true` para a forma ESCALAR (`FMUL_si`/`FMLA_si`/`FMLS_si`/`FMULX_si`) — processa
            /// só o elemento `0`, mesma disciplina de {@link AdvSimdIntegerOp64.ArithmeticThreeSame#scalar}.
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ignorado se {@link #scalar}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes: sempre `2` (simples) ou `3` (dupla).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (lido elemento a elemento).
            int rn,
            /// Registrador `V` fonte 2 — só o elemento {@link #index} é lido, replicado.
            int rm,
            /// Índice do elemento de {@link #rm} usado em TODA a operação (`0`-`3` para simples,
            /// `0`-`1` para dupla).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_ARITHMETIC_THREE_SAME_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeThreeSameByElement(core, this);
        }
    }

    /// `FCADD_90`/`FCADD_270` (B19.20, `FEAT_FCMA`) — trata PARES de lanes adjacentes de {@link
    /// #rn}/{@link #rm} como números complexos (lane par = parte real, ímpar = imaginária) e soma
    /// `Rn` com `Rm` ROTACIONADO no plano complexo antes de somar. Sem forma escalar real (ARM DDI
    /// 0487: só vetorial). Núcleo reaproveitado 100% de {@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexAdd} — o MESMO já escrito pela
    /// B13.17 para `VCADD` (NEON de 32 bits), sem semântica nova aqui.
    record FpComplexAdd(
            /// `true` para arranjo de 128 bits, `false` para 64 bits. `esz`=`3` (dupla) EXIGE
            /// `q=true` no encoding real (uma `D` de 64 bits não cabe um par complexo de dupla
            /// precisão) — o decoder já recusa a combinação `esz=3 && !q` (G8).
            boolean q,
            /// `log2` do tamanho de CADA componente (real/imaginário) em bytes: `1`(meia
            /// precisão)/`2`(simples)/`3`(dupla).
            int esz,
            /// Rotação em graus aplicada a {@link #rm} antes de somar: `90` ou `270` (ver {@link
            /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#COMPLEX_ROTATE_90}/{@code
            /// COMPLEX_ROTATE_270}).
            int rotation,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2 (rotacionado antes de somar).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_COMPLEX_ADD; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeComplexAdd(core, this);
        }
    }

    /// `FSCALE` (B19.11e, `FEAT_FP8`, `a64.decode:1213-1214`) — escala cada lane de ponto
    /// flutuante de {@link #rn} por `2^Rm[i]`, lendo a lane de {@link #rm} como um INTEIRO COM
    /// SINAL (não um valor de ponto flutuante), largura igual à do elemento — análogo a
    /// `scalbn`/`ldexp` da libc com o expoente vindo lane a lane de outro vetor. Vive no MESMO
    /// espaço de encoding "AdvSIMD three same (FP)" que {@link FpArithmeticThreeSame}
    /// (`opcode=0b1_1111`, o mesmo de `DIV`/`RECPS`/`RSQRTS`, discriminado só por `(U,a)`), mas
    /// NÃO reusa aquele record: o núcleo genérico de "three same (FP)" ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpThreeSame}) interpreta AMBOS os
    /// operandos como ponto flutuante, o que produziria semântica errada para `Rm` aqui. Sem
    /// forma escalar real (ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpScaleByInt}). Achado real desta
    /// task: a forma
    /// `_h` (meia precisão) vive num espaço de encoding DIFERENTE (`bit21=0`, prefixo fixo
    /// completo, discriminado de `INS`/`DUP` por opcode — ver o decoder), mas produz o MESMO
    /// record (`esz` distingue).
    record FpScaleByInt(
            /// `true` para arranjo de 128 bits, `false` para 64 bits.
            boolean q,
            /// `log2` do tamanho do elemento em bytes: `1`(meia precisão)/`2`(simples)/`3`(dupla).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte do valor a escalar.
            int rn,
            /// Registrador `V` fonte do expoente (inteiro com sinal, não ponto flutuante).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_SCALE_BY_INT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeScaleByInt(core, this);
        }
    }

    /// `FAMAX`/`FAMIN` (B19.24, `FEAT_FAMINMAX`, `a64.decode:1208-1211`) — `Vd[i] = |Vn[i]| >=
    /// |Vm[i]| ? Vn[i] : Vm[i]` ({@link #max}) ou o mínimo pelo mesmo critério — compara os
    /// operandos pelo VALOR ABSOLUTO mas devolve o operando ORIGINAL do vencedor, com sinal
    /// preservado (não `Math.abs` do vencedor). Vive no MESMO espaço de encoding "AdvSIMD three
    /// same (FP)" que {@link FpArithmeticThreeSame} (forma `_sd`, `opcode=0b1_1011`, o mesmo
    /// de `MUL`/`MULX`, discriminado só por `(U,a)`) e no MESMO espaço `bit21=0` que
    /// {@link FpScaleByInt} usa para `FSCALE_h` (forma `_h`, achado real desta task: MESMA
    /// classe de bug de misdecode que a B19.11e já documentou — `decodeAdvancedSimdCopy` lia `Rm`
    /// como `imm5` de `INS`/`DUP` antes de existir um decoder dedicado aqui). NÃO reusa
    /// {@link FpArithmeticThreeSame}: o núcleo genérico `MAX`/`MIN` compara com sinal, não
    /// por valor absoluto — reusar produziria semântica errada.
    record FpAbsoluteMaxMin(
            /// `true` para `FAMAX`, `false` para `FAMIN`.
            boolean max,
            /// `true` para arranjo de 128 bits, `false` para 64 bits.
            boolean q,
            /// `log2` do tamanho do elemento em bytes: `1`(meia precisão)/`2`(simples)/`3`(dupla).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_ABSOLUTE_MAX_MIN; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeAbsoluteMaxMin(core, this);
        }
    }

    /// `FMLAL_hb_v`/`FMLALL_sb_v` (B19.11b, `FEAT_FP8FMA`) — multiply-accumulate FP8 FUNDIDO num
    /// acumulador de meia precisão (`FMLAL_hb`, {@link #wideDestination}=`false`) ou precisão
    /// simples (`FMLALL_sb`, `true`). "long-long" é sobre a LARGURA do destino, NÃO sobre combinar
    /// vários elementos por lane (achado real desta task, corrige a hipótese da spec que especulava
    /// 4 elementos por analogia com `FEAT_I8MM`: o `HELPER(gvec_fmla_sb)` real do QEMU funde só UM
    /// produto FP8×FP8 por lane — `f8dotadd_s(e0, e1, n=1, ...)`). SEMPRE opera nos 128 bits inteiros
    /// de `Rd` (`Vd.8H`/`Vd.4S`) — não existe forma de 64 bits (`do_fmla_fp8` do QEMU fixa
    /// `oprsz=16` incondicionalmente, sem bit `Q` no encoding real — achado que corrige a leitura
    /// inicial da spec, que tratava o campo `idxn` como se fosse `Q`). `Rn`/`Rm` contribuem só
    /// METADE dos seus 16 bytes FP8 cada, selecionados por {@link #sourceByteSelect} — achado real
    /// (QEMU `n[H1(stride*i+select)]`/`m[H1(stride*i+select)]`, MESMO seletor para os dois
    /// operandos, ao contrário de `FMLAL2`/`FMLSL2` (B19.13) onde `top` seleciona um bloco CONTÍGUO
    /// em vez de bytes intercalados). Formatos/escala/`OSM` vêm de `FPMR` em tempo de EXECUÇÃO —
    /// mesma disciplina de {@link FpConvertFromFp8}.
    record Fp8FusedMultiplyAddLong(
            /// `false`=`FMLAL_hb` (destino `binary16`, seleção de `Rn`/`Rm` por PARIDADE `0`/`1` a
            /// cada 2 bytes); `true`=`FMLALL_sb` (destino `binary32`, seleção por FASE `0`-`3` a
            /// cada 4 bytes).
            boolean wideDestination,
            /// Seleciona QUAIS bytes FP8 de `Rn`/`Rm` entram nesta instrução — `idxn` do encoding
            /// real (`1` bit, `0`-`1`, se {@code !wideDestination}; `2` bits, `0`-`3`, se
            /// {@code wideDestination} — campo `%fmlall_idxn` do QEMU, `(bit30<<1)|bit22`).
            int sourceByteSelect,
            /// Registrador `V` de destino/acumulador (`Vd.8H`/`Vd.4S`, lido e escrito — RMW).
            int rd,
            /// Registrador `V` fonte 1 (`Vn.16B`, só a metade selecionada por
            /// {@link #sourceByteSelect} é lida).
            int rn,
            /// Registrador `V` fonte 2 (`Vm.16B`, mesma seleção de {@link #sourceByteSelect}).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFp8FusedMultiplyAddLong(core, this);
        }
    }

    /// `FMLAL_hb_vi`/`FMLALL_sb_vi` (B19.11b, `FEAT_FP8FMA`) — como
    /// {@link Fp8FusedMultiplyAddLong}, mas {@link #rm} contribui um ÚNICO byte FP8 fixo
    /// ({@link #index}, `0`-`15` — campo `%hlm4` do QEMU, `(bit11<<3)|bits[21:19]`), lido UMA vez e
    /// replicado para todas as lanes (achado real: `HELPER(gvec_fmla_idx_*)` calcula o byte de `Rm`
    /// FORA do laço de lanes). `Rn` continua contribuindo {@code elements} bytes, selecionados por
    /// {@link #sourceByteSelect} exatamente como na forma vetorial. `Rm` é restrito a 3 bits
    /// (`V0`-`V7`) — MENOS que os 4/5 bits de outras formas indexadas deste executor (`BFMLAL_vi`/
    /// `FMLAL_vi`), porque o índice de 4 bits rouba um bit a mais do encoding.
    record Fp8FusedMultiplyAddLongByElement(
            /// Ver {@link Fp8FusedMultiplyAddLong#wideDestination}.
            boolean wideDestination,
            /// Ver {@link Fp8FusedMultiplyAddLong#sourceByteSelect} — aplicado só a
            /// {@link #rn} aqui ({@link #rm} usa {@link #index} em vez disso).
            int sourceByteSelect,
            /// Registrador `V` de destino/acumulador.
            int rd,
            /// Registrador `V` fonte 1 (`Vn.16B`, seleção por {@link #sourceByteSelect}).
            int rn,
            /// Registrador `V` fonte 2 (`V0`-`V7`) — só o byte FP8 {@link #index} é lido, replicado.
            int rm,
            /// Índice do byte FP8 de {@link #rm} usado em TODA a operação (`0`-`15`).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP8_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFp8FusedMultiplyAddLongByElement(core, this);
        }
    }

    /// `FDOT_hb_v`/`FDOT_sb_v` (B19.11c/B19.11d, `FEAT_FP8DOT2`/`FEAT_FP8DOT4`) — produto escalar
    /// FP8 FUNDIDO (arredondamento ÚNICO) de 2 (`FDOT_hb`) ou 4 (`FDOT_sb`) pares FP8×FP8 por lane,
    /// acumulado em meia precisão (`FDOT_hb`, {@link #wideDestination}=`false`) ou precisão simples
    /// (`FDOT_sb`, `true`) — análogo estrutural a `SDOT`/`UDOT` (inteiro, B19.23), mas com conversão
    /// FP8→float obrigatória de cada operando antes de multiplicar/acumular. **AO CONTRÁRIO** de
    /// {@link Fp8FusedMultiplyAddLong} (que ignora `Q` e sempre processa os 128 bits inteiros
    /// de `Rd`), `FDOT` usa `Q` normalmente (`do_f8dot` do QEMU real passa `a->q ? 16 : 8` —
    /// forma AdvSIMD "three same" padrão, confirmado em `target/arm/tcg/translate-a64.c`, revisão
    /// fixada pela E11). Formatos FP8 de `Rn`/`Rm` (`FPMR.F8S1`/`F8S2`) e a escala/`OSM` da
    /// multiplicação vêm de `FPMR` em tempo de EXECUÇÃO — mesma disciplina de
    /// {@link Fp8FusedMultiplyAddLong}. `FDOT_hb_v` ({@link #wideDestination}=`false`,
    /// B19.11c) e `FDOT_sb_v` (`true`, B19.11d) reusam este MESMO record/núcleo
    /// (`AdvSimdLanes.fp8DotProduct`), sem duplicação.
    record Fp8DotProduct(
            /// `false`=`FDOT_hb` (2 elementos FP8/lane, destino `binary16`); `true`=`FDOT_sb` (4
            /// elementos FP8/lane, destino `binary32`).
            boolean wideDestination,
            /// `Q` — `false`=64 bits (`Vd.4H`/`Vd.2S`), `true`=128 bits (`Vd.8H`/`Vd.4S`).
            boolean q,
            /// Registrador `V` de destino/acumulador (lido e escrito — RMW).
            int rd,
            /// Registrador `V` fonte 1 — cada lane fornece os elementos FP8 empacotados nos bytes
            /// baixos (byte `k` de cada grupo em `[8k, 8k+8)`).
            int rn,
            /// Registrador `V` fonte 2 — mesma disposição de {@link #rn}.
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP8_DOT_PRODUCT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFp8DotProduct(core, this);
        }
    }

    /// `FDOT_hb_vi`/`FDOT_sb_vi` (B19.11c/B19.11d) — como {@link Fp8DotProduct}, mas
    /// {@link #rm} contribui um ÚNICO grupo FP8 FIXO (selecionado por {@link #index}), lido UMA vez
    /// e replicado para todas as lanes (achado real: `HELPER(gvec_fdot_idx_*)` do QEMU calcula o
    /// grupo de `Rm` FORA do laço de lanes). **Ao contrário de**
    /// {@link Fp8FusedMultiplyAddLongByElement} (que precisou de um layout de `Rm`/índice
    /// PRÓPRIO), `FDOT_hb_vi`/`FDOT_sb_vi` reusam o esquema `H:L:M`/`H:L` GENÉRICO já usado por
    /// `FMUL_vi`/`SDOT_vi` para o mesmo tamanho de elemento (achado real desta task).
    record Fp8DotProductByElement(
            /// Ver {@link Fp8DotProduct#wideDestination}.
            boolean wideDestination,
            /// Ver {@link Fp8DotProduct#q}.
            boolean q,
            /// Registrador `V` de destino/acumulador.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2 — só o grupo FP8 {@link #index} é lido, replicado.
            int rm,
            /// Índice do grupo FP8 de {@link #rm} usado em TODA a operação (`0`-`7` se
            /// {@code !wideDestination}, `0`-`3` se {@code wideDestination}).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP8_DOT_PRODUCT_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFp8DotProductByElement(core, this);
        }
    }

    /// `FCMLA_v` (B19.20, `FEAT_FCMA`) — multiplicação-acumulação complexa FUNDIDA: como {@link
    /// FpComplexAdd}, mas multiplica `Rn` pelo par complexo de `Rm` (com uma das 4 rotações)
    /// e ACUMULA em {@link #rd} (lido E escrito). As 4 rotações combinadas (`0`/`90`/`180`/`270`,
    /// cada uma contribuindo uma parcela) reproduzem a multiplicação complexa completa `(a+bi)*
    /// (c+di)` — padrão real de geração de código do GCC/LLVM para `_Complex`. Núcleo reaproveitado
    /// de {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpComplexMultiplyAccumulate}
    /// (B13.17, NEON de 32 bits).
    record FpComplexMultiplyAccumulate(
            /// `true` para arranjo de 128 bits, `false` para 64 bits. `esz`=`3` (dupla) EXIGE
            /// `q=true`, mesma restrição de {@link FpComplexAdd#q}.
            boolean q,
            /// `log2` do tamanho de CADA componente em bytes (`1`/`2`/`3`).
            int esz,
            /// Rotação em graus: `0`/`90`/`180`/`270`.
            int rotation,
            /// Registrador `V` de destino — lido (acumulador) E escrito.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeComplexMultiplyAccumulate(core, this);
        }
    }

    /// `FCMLA_vi` (B19.20, `FEAT_FCMA`) — como {@link FpComplexMultiplyAccumulate}, mas
    /// {@link #rm} contribui SEMPRE o MESMO par complexo, escolhido por {@link #index} (lido UMA
    /// vez, replicado) — análogo a `FMLA`/`FMUL` indexado comum, mas em pares. Só `esz` `1`(meia
    /// precisão) e `2`(simples) têm forma indexada real; não há forma `D` indexada (ARM DDI 0487) —
    /// o `esz=2` real também exige `q=true` sempre (não existe `.2s` indexado, só `.4s`).
    record FpComplexMultiplyAccumulateByElement(
            /// `true` para arranjo de 128 bits. Para {@link #esz}`=2` é SEMPRE `true` no encoding
            /// real (decoder já recusa `esz=2 && !q`, G8).
            boolean q,
            /// `log2` do tamanho de CADA componente em bytes: `1`(meia precisão) ou `2`(simples).
            int esz,
            /// Rotação em graus: `0`/`90`/`180`/`270`.
            int rotation,
            /// Registrador `V` de destino — lido (acumulador) E escrito.
            int rd,
            /// Registrador `V` fonte 1 (lido par a par).
            int rn,
            /// Registrador `V` fonte 2 — só o PAR complexo em {@link #index} é lido, replicado.
            int rm,
            /// Índice do PAR complexo de {@link #rm} (não do elemento individual real/imaginário).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeComplexMultiplyAccumulateByElement(core, this);
        }
    }

    /// AdvSIMD "three same" de ponto flutuante, pareado (`FADDP_v`/`FMAXP_v`/`FMINP_v`/
    /// `FMAXNMP_v`/`FMINNMP_v`, B8.9) — concatena `Rn:Rm` e combina pares adjacentes, mesmo esquema
    /// de {@link AdvSimdIntegerOp64.ArithmeticPairwise} (inteiro). A forma ESCALAR (`FADDP_s`/`FMAXP_s`/`FMINP_s`/
    /// `FMAXNMP_s`/`FMINNMP_s`, B19.2, classe "AdvSIMD scalar pairwise") reduz os DOIS elementos de
    /// tamanho {@link #esz} de `Rn` (lanes `0` e `1`) a um único escalar em `Rd` lane `0`; `Rm` é
    /// ignorado — ver {@link #scalar}.
    record FpArithmeticPairwise(
            /// Operação a executar.
            Ir64VectorFpPairwiseOp op,
            /// `true` para a forma ESCALAR — reduz `Rn` lanes `0`/`1` a `Rd` lane `0`; {@link #rm}
            /// e {@link #q} ignorados.
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ignorado se {@link #scalar}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`2` ou `3`).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (metade BAIXA do resultado).
            int rn,
            /// Registrador `V` fonte 2 (metade ALTA do resultado).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_ARITHMETIC_PAIRWISE; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executePairwise(core, this);
        }
    }

    /// AdvSIMD "two-register miscellaneous" de ponto flutuante (`FABS_v`/`FNEG_v`/`FSQRT_v`/
    /// `FRINTx_v`/`FRECPE_v`/`FRSQRTE_v`/`FCM**0_v`/`SCVTF_vi`/`UCVTF_vi`/`FCVTxS_vi`/`FCVTxU_vi`,
    /// B8.9) — um único operando de origem (`Rn`). Vive em DOIS slots de encoding diferentes do
    /// mesmo grupo (achado da triagem — ver {@link Ir64VectorFpUnaryOp}), resolvido pelo decoder,
    /// transparente para este record: sempre `Rd`/`Rn`/`esz`/`q`. Cobre também a forma
    /// AdvSIMD-ESCALAR genuína (B19.3) — ver {@link #scalar}.
    record FpArithmeticUnary(
            /// Operação a executar.
            Ir64VectorFpUnaryOp op,
            /// `true` para a forma ESCALAR AdvSIMD — processa só o elemento `0`; {@link #q} é
            /// ignorado; a escrita zera TODO o `Rd` acima do `esz` de saída (mesma disciplina de
            /// {@link FpArithmeticThreeSame#scalar}).
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ignorado se {@link #scalar}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`2` ou `3`) — para
            /// {@link Ir64VectorFpUnaryOp#SCVTF}/{@link Ir64VectorFpUnaryOp#UCVTF}, é o tamanho do
            /// elemento INTEIRO de entrada (mesmo tamanho do resultado FP); para as demais
            /// `FCVTxS`/`FCVTxU`, é o tamanho do elemento FP de entrada (mesmo tamanho do inteiro
            /// de saída); para {@link Ir64VectorFpUnaryOp#FCVTXN}, é o tamanho da ENTRADA
            /// (`3`/`f64`) — a saída é sempre `f32`.
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_ARITHMETIC_UNARY; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeUnary(core, this);
        }
    }

    /// AdvSIMD conversão FP↔ponto fixo (`@fcvt_fixed`, B19.3) — `SCVTF`/`UCVTF` (inteiro→FP) e
    /// `FCVTZS`/`FCVTZU` (FP→inteiro, sempre arredondando para zero). Difere de
    /// {@link FpArithmeticUnary} (formas `@icvt`, sem escala) por carregar
    /// {@link #fractionBits}: o resultado é escalado por `2^fractionBits` (dividido na direção
    /// inteiro→FP, multiplicado na direção FP→inteiro). Difere de {@link FpOp64.IntegerConvert}
    /// (forma registrador-geral, `Wn`/`Xn` ↔ `Sn`/`Dn`) por operar `V`↔`V`. Nesta task só a forma
    /// ESCALAR ({@link #scalar} sempre `true`); a forma vetorial `_vf` chega em B19.4 reaproveitando
    /// este record com `scalar=false`/`q` real.
    record FpConvertFixedPoint(
            /// `true` para a forma ESCALAR — processa só o elemento `0`; {@link #q} ignorado; a
            /// escrita zera TODO o `Rd` acima de {@link #esz}.
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ignorado se {@link #scalar}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes: `2` (`s`, inteiro/FP de 32 bits) ou `3`
            /// (`d`, 64 bits). Meia precisão (`1`) é `FEAT_FP16` — B19.5.
            int esz,
            /// Número de bits fracionários (`#fbits` do encoding): `1`-`32` para `s`, `1`-`64` para
            /// `d`. O fator de escala é `2^fractionBits`.
            int fractionBits,
            /// `true` → `SCVTF`/`UCVTF` (inteiro `esz`-wide → FP `esz`-wide, depois `/ 2^fbits`);
            /// `false` → `FCVTZS`/`FCVTZU` (FP `* 2^fbits`, arredonda para zero, satura → inteiro).
            boolean toFloat,
            /// `true` para as variantes assinadas (`SCVTF`/`FCVTZS`), `false` para as não
            /// assinadas (`UCVTF`/`FCVTZU`).
            boolean signed,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_CONVERT_FIXED_POINT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeConvertFixedPoint(core, this);
        }
    }

    /// AdvSIMD conversão de PRECISÃO vetorial (`FCVTL`/`FCVTN`/`FCVTXN`, B19.4) — origem e destino
    /// têm larguras de elemento DIFERENTES. Record separado (não {@link FpArithmeticUnary}) pela
    /// MESMA razão que {@link AdvSimdIntegerOp64.ShiftNarrowImmediate}/{@link AdvSimdIntegerOp64.ShiftWidenImmediate} são
    /// separados de {@link AdvSimdIntegerOp64.ShiftImmediate}: duas larguras + deslocamento de lane. Cobre `f16`↔
    /// `f32` — conversão de meia precisão é ISA base ARMv8.0-A (a ARITMÉTICA em meia precisão,
    /// `FEAT_FP16`, é B19.5).
    record FpConvertPrecision(
            /// Operação a executar.
            Ir64VectorFpConvertPrecisionOp op,
            /// Seleciona a METADE, NÃO a largura do registrador: em {@link
            /// Ir64VectorFpConvertPrecisionOp#FCVTL} `false`=lê a metade baixa de `Rn`/`true`=lê a
            /// alta (forma `FCVTL2`); nas que estreitam `false`=escreve a metade baixa de `Rd` e zera
            /// a alta/`true`=escreve a metade alta e preserva a baixa (forma `*2`).
            boolean q,
            /// `log2` do tamanho, em bytes, do elemento ESTREITO (`1`=`f16`, `2`=`f32`). O lado largo
            /// usa `esz + 1`. Para `FCVTL`/`FCVTN` ∈ {1,2}; para `FCVTXN` sempre `2`.
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_CONVERT_PRECISION; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeConvertPrecision(core, this);
        }
    }

    /// `FCVTN_bh`/`FCVTN_bs` (AdvSIMD "three same (FP8 convert)", `FEAT_FP8`, B19.11) — ESTREITA
    /// dois vetores FONTE (`Rn`/`Rm`) em elementos FP8 de 1 byte, um por operando, intercalados
    /// (`Rn` nos bytes BAIXOS do resultado, `Rm` nos ALTOS — ARM DDI 0487, `FCVTN`/`FCVTN2`
    /// "8-bit floating-point convert and interleave"). O formato FP8 de DESTINO e a escala vêm de
    /// `FPMR.F8D`/`FPMR.NSCALE` (lidos em tempo de EXECUÇÃO pelo executor — nenhum campo estático
    /// aqui, ao contrário de {@link FpConvertPrecision}), e o overflow satura conforme
    /// `FPMR.OSC` ({@code core.fp8OverflowSaturatesToMaxNormal()}). Duas formas de origem com
    /// convenções de {@link #q} DIFERENTES (medido bit a bit, `bit22` do encoding real): com
    /// {@link #halfSource}, `Rn`/`Rm` são `f16` e `q` dobra o NÚMERO de elementos (`4H+4H→8B` /
    /// `8H+8H→16B`, convenção comum de "three same", sem conceito de metade); sem
    /// {@link #halfSource}, `Rn`/`Rm` são `f32` (sempre 4 elementos) e `q` seleciona a METADE do
    /// destino (convenção `FCVTN`/`FCVTN2` de {@link FpConvertPrecision}).
    record FpConvertToFp8(
            /// `true`=fonte `f16` (`FCVTN_bh`, `q` dobra o total de elementos); `false`=fonte `f32`
            /// (`FCVTN_bs`, `q` seleciona a metade do destino, sempre 4 elementos por operando).
            boolean halfSource,
            /// Ver o Javadoc da classe — significado depende de {@link #halfSource}.
            boolean q,
            /// Registrador `V` de destino (FP8, 1 byte por elemento).
            int rd,
            /// Registrador `V` fonte 1 — preenche os bytes BAIXOS do resultado.
            int rn,
            /// Registrador `V` fonte 2 — preenche os bytes ALTOS do resultado.
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_CONVERT_TO_FP8; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeConvertToFp8(core, this);
        }
    }

    /// `F1CVTL`/`F2CVTL`/`BF1CVTL`/`BF2CVTL` (AdvSIMD "two-register miscellaneous (FP8 widen)",
    /// `FEAT_FP8`, B19.11) — ALARGA 8 elementos FP8 de 1 byte (metade de `Rn` selecionada por
    /// {@link #q}, convenção `FCVTL`/`FCVTL2` de {@link FpConvertPrecision}) para 8 elementos
    /// de 2 bytes, preenchendo os 128 bits inteiros de `Rd` — SEMPRE exato (FP8 tem no máximo 3 bits
    /// de mantissa, `binary16`/`bfloat16` têm 10/7; o único jeito de perder precisão seria a escala
    /// empurrar o valor para o alcance subnormal do destino, comportamento correto de hardware, não
    /// um bug de arredondamento duplo). Formato de ORIGEM e escala vêm de `FPMR.F8S1`/`FPMR.LSCALE`
    /// (`F1CVTL`/`BF1CVTL`, {@link #secondStream}=`false`) ou `FPMR.F8S2`/`FPMR.LSCALE2`
    /// (`F2CVTL`/`BF2CVTL`, {@link #secondStream}=`true`), lidos em tempo de EXECUÇÃO. `BF1CVTL`/
    /// `BF2CVTL` produzem `bfloat16` — MESMO núcleo de conversão de {@link FpOp64.ConvertToBf16}/
    /// {@code AdvSimdLanes#bf16Bits} (B19.7), nunca `binary16`, apesar do prefixo `F` do mnemônico
    /// irmão sugerir o contrário.
    record FpConvertFromFp8(
            /// `false`=lê `FPMR.F8S1`/`FPMR.LSCALE` (`F1CVTL`/`BF1CVTL`); `true`=lê
            /// `FPMR.F8S2`/`FPMR.LSCALE2` (`F2CVTL`/`BF2CVTL`).
            boolean secondStream,
            /// `true`=destino `bfloat16` (`BF1CVTL`/`BF2CVTL`); `false`=destino `binary16`
            /// (`F1CVTL`/`F2CVTL`).
            boolean bfloat16Destination,
            /// Seleciona a metade de `Rn` que contém os 8 elementos FP8 fonte — mesma convenção de
            /// {@link FpConvertPrecision#q()} em {@link Ir64VectorFpConvertPrecisionOp#FCVTL}.
            boolean q,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_CONVERT_FROM_FP8; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeConvertFromFp8(core, this);
        }
    }

    /// `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` (AdvSIMD across lanes de ponto flutuante) — reduz os
    /// elementos de `Rn` a um único escalar em `Rd`. B8.10: forma `_s`, sempre `q=true`/
    /// `esz=WORD` (arranjo `4S`, único real em precisão simples). B19.5.3: forma `_h`
    /// (`FEAT_FP16`), `esz=HALFWORD` com `q` LIVRE (arranjo `4H` ou `8H`, ao contrário da `_s`).
    record FpAcrossLanes(
            /// Operação a executar.
            Ir64VectorFpAcrossLanesOp op,
            /// `true` para reduzir as 8 lanes de `Rn` (`8H`, só existe em `_h`), `false` para as 4
            /// baixas (`4H`/`4S`).
            boolean q,
            /// `log2` do tamanho de cada elemento em bytes — `1` (halfword, `_h`) ou `2` (word,
            /// `_s`).
            int esz,
            /// Registrador `V` de destino (escalar).
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_ACROSS_LANES; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpAcrossLanes(core, this);
        }
    }

    /// `BFDOT` vetorial (`FEAT_BF16`, B19.7) — produto escalar de PARES `bf16`, acumulando em `f32`
    /// (`Vd.2S`/`Vd.4S`). Sibling FP do produto escalar inteiro
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProduct} (B13.18/B19.12) — núcleo
    /// em {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfDotProduct}.
    record FpDotProductBFloat16(
            /// `true` para arranjo de 128 bits (`Vd.4S`), `false` para 64 bits (`Vd.2S`).
            boolean q,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (lido par a par de `bf16`).
            int rn,
            /// Registrador `V` fonte 2 (lido par a par de `bf16`).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_DOT_PRODUCT_BFLOAT16; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpDotProductBFloat16(core, this);
        }
    }

    /// `BFDOT` indexado (`BFDOT_vi`, `FEAT_BF16`, B19.7) — como {@link FpDotProductBFloat16},
    /// mas {@link #rm} sempre contribui o MESMO par `bf16` (`Vm.2H[index]`, restrito a `V0`-`V15`,
    /// mesma disciplina de índice halfword de B8.19/B19.12).
    record FpDotProductBFloat16ByElement(
            /// `true` para arranjo de 128 bits (`Vd.4S`), `false` para 64 bits (`Vd.2S`).
            boolean q,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (lido par a par de `bf16`).
            int rn,
            /// Registrador `V` fonte 2 — só o par `bf16` {@link #index} é lido, replicado.
            int rm,
            /// Índice do par `bf16` de {@link #rm} usado em TODA a operação (`0`-`1`).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_DOT_PRODUCT_BFLOAT16_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpDotProductBFloat16ByElement(core, this);
        }
    }

    /// `BFMLALB`/`BFMLALT` (`FEAT_BF16`, B19.7) — multiply-accumulate LONG: lanes `bf16` de `Rn`/
    /// `Rm` (pares/ímpares conforme {@link #top}) multiplicadas em `binary32` e ACUMULADAS (soma
    /// simples, sem fusão) nas 4 lanes `f32` de `Rd`. `Vd.4S` é sempre 128 bits inteiros (nunca há
    /// forma de 64 bits nesta família).
    record FpMultiplyAddLongBFloat16(
            /// `false`=`BFMLALB` (elementos PARES de `Rn`/`Rm`, índice `2e`), `true`=`BFMLALT`
            /// (ÍMPARES, índice `2e+1`) — o bit `Q` do encoding É este seletor aqui, NÃO largura/
            /// metade (ao contrário de {@link AdvSimdIntegerOp64.ArithmeticWidening#q}): `Vd.4S` é sempre 128
            /// bits nesta família.
            boolean top,
            /// Registrador `V` de destino (`Vd.4S`).
            int rd,
            /// Registrador `V` fonte 1 (`Vn.8H`, lido elemento a elemento).
            int rn,
            /// Registrador `V` fonte 2 (`Vm.8H`, lido elemento a elemento).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpMultiplyAddLongBFloat16(core, this);
        }
    }

    /// `BFMLALB`/`BFMLALT` indexado (`BFMLAL_vi`, `FEAT_BF16`, B19.7) — como
    /// {@link FpMultiplyAddLongBFloat16}, mas {@link #rm} sempre contribui o MESMO elemento
    /// `bf16` {@link #index} (restrito a `V0`-`V15`, mesma disciplina de B8.19/B19.12).
    record FpMultiplyAddLongBFloat16ByElement(
            /// `false`=`BFMLALB`, `true`=`BFMLALT` — ver {@link FpMultiplyAddLongBFloat16#top}.
            boolean top,
            /// Registrador `V` de destino (`Vd.4S`).
            int rd,
            /// Registrador `V` fonte 1 (`Vn.8H`, lido elemento a elemento).
            int rn,
            /// Registrador `V` fonte 2 — só o elemento `bf16` {@link #index} é lido, replicado.
            int rm,
            /// Índice do elemento `bf16` de {@link #rm} usado em TODA a operação (`0`-`7`).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_MULTIPLY_ADD_LONG_BFLOAT16_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpMultiplyAddLongBFloat16ByElement(core, this);
        }
    }

    /// `FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2` (`FEAT_FHM`, B19.13) — multiply-accumulate LONG FUNDIDO
    /// (um único arredondamento, ao contrário de `BFMLALB`/`BFMLALT` acima): lanes `f16` de `Rn`/
    /// `Rm` multiplicadas em `binary32` e acumuladas em `Vd`. Diferente de
    /// {@link FpMultiplyAddLongBFloat16}: aqui `Vd` NÃO é sempre `.4S` — `Vn`/`Rm` sempre
    /// contribuem `lanes` elementos (`2` se `!q`, `4` se `q`) começando em {@link #top}`?lanes:0`
    /// (bloco BAIXO/ALTO, mesma convenção de {@link Ir64VectorFpConvertPrecisionOp#FCVTN}
    /// `2`-suffix), e `q` controla largura de VERDADE (`Vd.2S` ou `Vd.4S`), não a metade — medido
    /// bit a bit contra `arm-linux-gnu-as -march=armv8.2-a+fp16+fp16fml` (WSL). Núcleo:
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpFusedMultiplyAddLong} (nasceu na
    /// B13.20 para `VFML`/`VFMSL` de 32 bits, generalizada ali com `laneOffsetN`/`laneOffsetM`
    /// independentes justamente para esta task reusar).
    record FpMultiplyAddLong(
            /// `true` para `Vd.4S`/`Vn.4H`/`Vm.4H` (`lanes=4`), `false` para `Vd.2S`/`Vn.2H`/
            /// `Vm.2H` (`lanes=2`).
            boolean q,
            /// `false`=`FMLAL`/`FMLSL` (bloco BAIXO de `Rn`/`Rm`), `true`=`FMLAL2`/`FMLSL2`
            /// (bloco ALTO).
            boolean top,
            /// `false`=`FMLAL`/`FMLAL2` (soma), `true`=`FMLSL`/`FMLSL2` (subtração, acumula
            /// `-a*b`).
            boolean subtract,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_MULTIPLY_ADD_LONG; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpMultiplyAddLong(core, this);
        }
    }

    /// `FMLAL_vi`/`FMLSL_vi`/`FMLAL2_vi`/`FMLSL2_vi` (`FEAT_FHM`, B19.13) — como
    /// {@link FpMultiplyAddLong}, mas {@link #rm} sempre contribui o MESMO elemento `f16`
    /// {@link #index} (restrito a `V0`-`V15`, mesma disciplina de índice halfword de B8.19/B19.7/
    /// B19.12).
    record FpMultiplyAddLongByElement(
            /// Ver {@link FpMultiplyAddLong#q}.
            boolean q,
            /// Ver {@link FpMultiplyAddLong#top}.
            boolean top,
            /// Ver {@link FpMultiplyAddLong#subtract}.
            boolean subtract,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2 — só o elemento `f16` {@link #index} é lido, replicado.
            int rm,
            /// Índice do elemento `f16` de {@link #rm} usado em TODA a operação (`0`-`7`).
            int index) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_MULTIPLY_ADD_LONG_BY_ELEMENT; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpMultiplyAddLongByElement(core, this);
        }
    }

    /// `BFMMLA` (`FEAT_BF16`, B19.7) — multiplicação de matriz `2×4 · 4×2` de pares `bf16`,
    /// acumulando em `f32`. Irmã de ponto flutuante de `SMMLA`/`UMMLA`/`USMMLA` (B19.12/B13.19,
    /// `K=8` em vez de `K=4`). `Q` é FIXO em `1` no encoding — não há forma de 64 bits (mesma
    /// disciplina de `SMMLA`).
    record FpMatrixMultiplyAccumulateBFloat16(
            /// Registrador `V` de destino (`Vd.4S`, matriz `2×2` de acumuladores).
            int rd,
            /// Registrador `V` fonte 1 (`Vn.8H`, matriz `2×4` — linha `r` = elementos `4r..4r+3`).
            int rn,
            /// Registrador `V` fonte 2 (`Vm.8H`, matriz `4×2` na MESMA disposição de {@link #rn} —
            /// coluna `c` = elementos `4c..4c+3`).
            int rm) implements AdvSimdFpOp64 {
        @Override public int kind() { return Kind.VECTOR_FP_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64VectorFpArithmeticExecutor.executeFpMatrixMultiplyAccumulateBFloat16(core, this);
        }
    }
}
