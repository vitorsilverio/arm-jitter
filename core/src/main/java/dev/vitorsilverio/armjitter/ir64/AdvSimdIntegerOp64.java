package dev.vitorsilverio.armjitter.ir64;

/// Operações AdvSIMD de aritmética inteira: "three same", pareadas, alargantes, estreitantes,
/// deslocamento por imediato, redução entre lanes, produto escalar e multiplicação polinomial.
///
/// Sub-interface selada de {@link AdvSimdOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface AdvSimdIntegerOp64 extends AdvSimdOp64 permits
        AdvSimdIntegerOp64.ArithmeticThreeSame, AdvSimdIntegerOp64.ArithmeticThreeSameByElement,
        AdvSimdIntegerOp64.ArithmeticPairwise, AdvSimdIntegerOp64.ArithmeticWidening,
        AdvSimdIntegerOp64.ArithmeticWideningByElement, AdvSimdIntegerOp64.ArithmeticWide,
        AdvSimdIntegerOp64.ArithmeticNarrow, AdvSimdIntegerOp64.AcrossLanes,
        AdvSimdIntegerOp64.ArithmeticUnary, AdvSimdIntegerOp64.ScalarPairwiseAdd,
        AdvSimdIntegerOp64.ArithmeticNarrowUnary, AdvSimdIntegerOp64.ShiftImmediate,
        AdvSimdIntegerOp64.ShiftNarrowImmediate, AdvSimdIntegerOp64.ShiftWidenImmediate,
        AdvSimdIntegerOp64.PolynomialMultiplyLong, AdvSimdIntegerOp64.IntegerDotProduct,
        AdvSimdIntegerOp64.IntegerDotProductByElement,
        AdvSimdIntegerOp64.IntegerMatrixMultiplyAccumulate {

    /// AdvSIMD "three same" inteiro (`ADD_v`/`SUB_v`/`CM**_v`/`SHADD_v`/.../`MLA_v`/`MLS_v`, B8.7)
    /// — os 3 operandos (`Rd`/`Rn`/`Rm`) têm o MESMO tamanho de elemento {@link #esz}. Também
    /// representa a forma ESCALAR (`ADD_s`/`SUB_s`/`CM**_s`/`SQADD_s`/..., B8.7+B8.8), que no
    /// encoding real vive num prefixo diferente (bit28 fixo).
    ///
    /// ⚠️ B8.8: {@link #scalar} passou a ser um `boolean` EXPLÍCITO (antes, B8.7 reaproveitava
    /// `esz=3`(doubleword)/`q=false` como sentinela implícito de "é escalar" — válido enquanto TODA
    /// forma escalar desta tabela fosse D-only, como `ADD_s`/`CM**_s`. B8.8 introduziu formas
    /// escalares de tamanho VARIÁVEL (`SQADD_s`/`SQSHL_s`/`SQDMULH_s`/...), e `sqadd v0.8b,...`
    /// (VETORIAL, `esz=0`/`q=false`) e `sqadd b0,...` (ESCALAR, `esz=0`/`q=false` TAMBÉM) ficaram
    /// indistinguíveis pelo par antigo — colisão real que exigiria zerar bits diferentes do destino
    /// (vetorial `q=false` preserva TODO o `low64`; escalar zera tudo acima do elemento único,
    /// mesmo dentro do `low64`). Corrigido threading um `scalar` explícito do decoder ao executor.
    record ArithmeticThreeSame(
            /// Operação a executar.
            Ir64VectorThreeSameOp op,
            /// `true` para a forma ESCALAR (processa só o elemento `0`; escreve destrutivamente
            /// TUDO acima de {@link #esz} bits, inclusive dentro do `low64` — ver acima).
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ou forma escalar, ver acima).
            boolean q,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword (só válido com `q=true` na forma vetorial; a forma escalar usa
            /// sempre `3`/`q=false`, ver acima).
            int esz,
            /// Registrador `V` de destino (índice `0`-`31`).
            int rd,
            /// Registrador `V` fonte 1 (índice `0`-`31`).
            int rn,
            /// Registrador `V` fonte 2 (índice `0`-`31`).
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_THREE_SAME; }
    }

    /// AdvSIMD "vector/scalar × indexed element" (B8.19), subconjunto SEM alargamento —
    /// `MUL_vi`/`MLA_vi`/`MLS_vi` (só vetorial, sem forma `_si` real) e `SQDMULH`/`SQRDMULH`
    /// (vetorial `_vi` e escalar `_si`). Reaproveita {@link Ir64VectorThreeSameOp} — MESMA
    /// semântica por elemento de {@link ArithmeticThreeSame}, só que `Rm` não é lido
    /// elemento a elemento: {@link #rm} sempre contribui o MESMO elemento {@link #index} (do banco
    /// `V0`-`V15` para `esz=1`/halfword, `V0`-`V31` para `esz=2`/word), replicado para toda
    /// operação — nunca `esz=3`/doubleword (sem forma alargante real nesta família).
    record ArithmeticThreeSameByElement(
            /// Operação a executar — só `MUL`/`MLA`/`MLS`/`SQDMULH`/`SQRDMULH`/`SQRDMLAH`/`SQRDMLSH`
            /// (`SQRDMLAH`/`SQRDMLSH` só quando `FEAT_RDM` presente, B11.4) são válidas aqui
            /// (G8: o decoder nunca produz outro valor).
            Ir64VectorThreeSameOp op,
            /// `true` para a forma ESCALAR (`SQDMULH_si`/`SQRDMULH_si`/`SQRDMLAH_si`/`SQRDMLSH_si`)
            /// — processa só o elemento `0`, mesma disciplina de
            /// {@link ArithmeticThreeSame#scalar}. `MUL`/`MLA`/`MLS` nunca são escalares
            /// (sem encoding real).
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ignorado se {@link #scalar}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes — `1` (halfword) ou `2` (word).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (lido elemento a elemento, como em `Rn` de
            /// {@link ArithmeticThreeSame}).
            int rn,
            /// Registrador `V` fonte 2 — só o elemento {@link #index} é lido, replicado.
            int rm,
            /// Índice do elemento de {@link #rm} usado em TODA a operação (`0`-`7` para halfword,
            /// `0`-`3` para word).
            int index) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_THREE_SAME_BY_ELEMENT; }
    }

    /// AdvSIMD "three same" pareado (`ADDP_v`/`SMAXP_v`/`SMINP_v`/`UMAXP_v`/`UMINP_v`, B8.7) —
    /// concatena `Rn:Rm` e combina pares adjacentes (ver {@link Ir64VectorPairwiseOp}). Não cobre
    /// `ADDP_s` (escalar D, reduz `Rn.2d` a um único elemento) — ver {@link ScalarPairwiseAdd}.
    record ArithmeticPairwise(
            /// Operação a executar.
            Ir64VectorPairwiseOp op,
            /// `true` para arranjo de 128 bits, `false` para 64 bits.
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (metade BAIXA do resultado).
            int rn,
            /// Registrador `V` fonte 2 (metade ALTA do resultado).
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_PAIRWISE; }
    }

    /// AdvSIMD "three different" alargando (`SMULL`/`UMULL`/`SMLAL`/.../`SABDL`/`UABDL`, B8.7) —
    /// `Rn`/`Rm` têm elementos de {@link #esz} bytes, `Rd` tem elementos de `esz+1` (dobro),
    /// SEMPRE preenchendo os 128 bits inteiros (nunca escrita destrutiva parcial, ao contrário da
    /// forma "three same" com `q=false`) — EXCETO na forma ESCALAR (B8.20, ver {@link #scalar}).
    record ArithmeticWidening(
            /// Operação a executar.
            Ir64VectorWideningOp op,
            /// `true` para a forma ESCALAR (B8.20: só `SQDMULL`/`SQDMLAL`/`SQDMLSL` têm forma
            /// escalar real neste espaço "three different" — os únicos 2 registradores reais, sem
            /// `Rm` livre — produz um ÚNICO elemento largo, escrita destrutiva ciente de tamanho
            /// ({@link ArithmeticThreeSame#scalar}, mesmo padrão de
            /// {@link ArithmeticWideningByElement#scalar}). `q` é ignorado quando `true`.
            boolean scalar,
            /// `false` (forma sem `2`, ex. `SMULL`): usa a metade BAIXA de `Rn`/`Rm` como entrada.
            /// `true` (forma `*2`, ex. `SMULL2`): usa a metade ALTA. Ignorado se {@link #scalar}.
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rn`/`Rm`) em bytes — `0`-`2` (byte/half/
            /// word; doubleword não tem forma alargada real). `Rd` usa `esz+1`.
            int esz,
            /// Registrador `V` de destino (elementos `esz+1`, 128 bits inteiros).
            int rd,
            /// Registrador `V` fonte 1 (elementos `esz`).
            int rn,
            /// Registrador `V` fonte 2 (elementos `esz`).
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_WIDENING; }
    }

    /// AdvSIMD "vector/scalar × indexed element" (B8.19), subconjunto ALARGANTE —
    /// `SMULL_vi`/`UMULL_vi`/`SMLAL_vi`/`UMLAL_vi`/`SMLSL_vi`/`UMLSL_vi` (só vetorial) e
    /// `SQDMULL`/`SQDMLAL`/`SQDMLSL` (vetorial `_vi` E escalar `_si`, mesma exceção de
    /// {@link ArithmeticWidening#op}: sem forma `U=1`). Reaproveita
    /// {@link Ir64VectorWideningOp} — MESMA semântica de {@link ArithmeticWidening}, exceto
    /// que `Rm` sempre contribui o elemento {@link #index}, nunca `laneOffset+i`.
    record ArithmeticWideningByElement(
            /// Operação a executar — só `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/`SMLSL`/`UMLSL`/
            /// `SQDMULL`/`SQDMLAL`/`SQDMLSL` são válidas aqui (G8).
            Ir64VectorWideningOp op,
            /// `true` para a forma ESCALAR (`SQDMULL_si`/`SQDMLAL_si`/`SQDMLSL_si`) — produz um
            /// ÚNICO elemento largo (`esz+1` bytes), escrita destrutiva ciente de tamanho (mesma
            /// disciplina de {@link ArithmeticThreeSame#scalar}). `SMULL`/`UMULL`/etc nunca
            /// são escalares (sem encoding real nesta família).
            boolean scalar,
            /// `false` (forma sem `2`): usa a metade BAIXA de `Rn` como entrada. `true` (forma
            /// `*2`): usa a metade ALTA. Ignorado se {@link #scalar}.
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rn`/`Rm`) em bytes — `1` (halfword) ou `2`
            /// (word). `Rd` usa `esz+1`.
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (lido elemento a elemento).
            int rn,
            /// Registrador `V` fonte 2 — só o elemento {@link #index} é lido, replicado.
            int rm,
            /// Índice do elemento de {@link #rm} usado em TODA a operação (`0`-`7` para halfword,
            /// `0`-`3` para word).
            int index) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_WIDENING_BY_ELEMENT; }
    }

    /// AdvSIMD "three different" largo+estreito (`SADDW`/`UADDW`/`SSUBW`/`USUBW`, B8.7) — `Rd`/
    /// `Rn` já têm elementos LARGOS (`esz+1`), só `Rm` é estreito (`esz`, metade selecionada por
    /// {@link #q}, mesma convenção de {@link ArithmeticWidening#q}).
    record ArithmeticWide(
            /// Operação a executar.
            Ir64VectorWideOp op,
            /// Metade de `Rm` usada como entrada — ver {@link ArithmeticWidening#q}.
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rm`) em bytes — `0`-`2`. `Rd`/`Rn` usam
            /// `esz+1`.
            int esz,
            /// Registrador `V` de destino (elementos `esz+1`).
            int rd,
            /// Registrador `V` fonte 1, já largo (elementos `esz+1`).
            int rn,
            /// Registrador `V` fonte 2, estreito (elementos `esz`).
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_WIDE; }
    }

    /// AdvSIMD "three different" estreitando (`ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN`, B8.7) — `Rn`/`Rm`
    /// têm elementos LARGOS (`esz+1`), `Rd` recebe elementos ESTREITOS (`esz`, metade BAIXA quando
    /// `q=false`/forma sem `2` — ZERANDO a metade alta, "SIMD&FP destructive write" —, metade ALTA
    /// quando `q=true`/forma `*2` — preservando a metade baixa já escrita por uma `HN` anterior).
    record ArithmeticNarrow(
            /// Operação a executar.
            Ir64VectorNarrowOp op,
            /// `false`=escreve a metade BAIXA de `Rd` (forma sem `2`). `true`=escreve a metade
            /// ALTA (forma `*2`).
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rd`) em bytes — `0`-`2`. `Rn`/`Rm` usam
            /// `esz+1`.
            int esz,
            /// Registrador `V` de destino (elementos `esz`).
            int rd,
            /// Registrador `V` fonte 1, largo (elementos `esz+1`).
            int rn,
            /// Registrador `V` fonte 2, largo (elementos `esz+1`).
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_NARROW; }
    }

    /// AdvSIMD "across lanes" (`ADDV`/`SADDLV`/`UADDLV`/`SMAXV`/`UMAXV`/`SMINV`/`UMINV`, B8.7) —
    /// reduz TODOS os elementos de `Rn` a um único escalar em `Rd` (escrita destrutiva, "SIMD&FP
    /// destructive write": os bits altos de `Rd` são zerados).
    record AcrossLanes(
            /// Operação a executar.
            Ir64VectorAcrossLanesOp op,
            /// `true` para reduzir 128 bits de `Rn`, `false` para reduzir só os 64 baixos.
            boolean q,
            /// `log2` do tamanho de cada elemento de ENTRADA (`Rn`) em bytes — `0`-`2` (byte/half/
            /// word; nenhuma destas operações reduz doubleword). O resultado em `Rd` usa este
            /// mesmo tamanho, exceto {@link Ir64VectorAcrossLanesOp#SADDLV}/
            /// {@link Ir64VectorAcrossLanesOp#UADDLV} (`esz+1`).
            int esz,
            /// Registrador `V` de destino (escalar).
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ACROSS_LANES; }
    }

    /// AdvSIMD "two-register miscellaneous" inteiro (`ABS_v`/`NEG_v`/`CM**0_v`/`SADDLP_v`/
    /// `UADDLP_v`/`SADALP_v`/`UADALP_v`, B8.7) — um único operando de origem (`Rn`). Também
    /// representa a forma ESCALAR (`ABS_s`/`NEG_s`/`CM**0_s`/`SUQADD_s`/`USQADD_s`, B8.7+B8.8).
    /// {@link Ir64VectorUnaryOp#SADDLP}/{@link Ir64VectorUnaryOp#UADDLP}/
    /// {@link Ir64VectorUnaryOp#SADALP}/{@link Ir64VectorUnaryOp#UADALP} não têm forma escalar real
    /// (só vetorial). Ver o javadoc de {@link #scalar} em {@link ArithmeticThreeSame} —
    /// MESMA colisão `esz`/`q` corrigida pela B8.8, MESMO motivo (`SUQADD_s`/`USQADD_s` aceitam
    /// `esz` variável, ao contrário de `ABS_s`/`NEG_s`/`CM**0_s`, que são D-only).
    record ArithmeticUnary(
            /// Operação a executar.
            Ir64VectorUnaryOp op,
            /// `true` para a forma ESCALAR — ver {@link ArithmeticThreeSame#scalar}.
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ou forma escalar, ver acima).
            boolean q,
            /// `log2` do tamanho do elemento de ENTRADA (`Rn`) em bytes (`0`-`3`, forma escalar
            /// sempre `3` exceto `SUQADD`/`USQADD`). Para {@link Ir64VectorUnaryOp#SADDLP}/{@link Ir64VectorUnaryOp#UADDLP}/
            /// {@link Ir64VectorUnaryOp#SADALP}/{@link Ir64VectorUnaryOp#UADALP} o resultado em
            /// `Rd` usa `esz+1`; para as demais, `Rd` usa o mesmo `esz`.
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_UNARY; }
    }

    /// `ADDP_s` (AdvSIMD scalar pairwise, B8.7) — único mnemônico inteiro desta forma: reduz os
    /// 2 elementos doubleword de `Rn.2d` a um único escalar D em `Rd` (`Rd = Rn[0] + Rn[1]`,
    /// escrita destrutiva, bits altos de `Rd` zerados).
    record ScalarPairwiseAdd(
            /// Registrador `V` de destino (escalar D).
            int rd,
            /// Registrador `V` fonte (lido como `.2d`, 2 elementos doubleword).
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_SCALAR_PAIRWISE_ADD; }
    }

    /// AdvSIMD "narrow unary" saturante (`SQXTN`/`SQXTUN`/`UQXTN`, B8.8) — reduz um elemento de
    /// `esz+1` bytes (`Rn`) para `esz` bytes (`Rd`), saturando. Vive no MESMO slot de encoding
    /// (`Rm=00001`) que a forma "two-register misc" usa para narrow/widen — B8.7 deixou esse slot
    /// inteiro fora de escopo. Diferente de {@link ArithmeticThreeSame}/
    /// {@link ArithmeticUnary} (que reaproveitam `esz=3`/`q=false` para a forma escalar
    /// porque essa combinação é impossível na forma vetorial real), aqui `esz` VARIA legitimamente
    /// tanto na forma vetorial quanto na escalar (`0`-`2` nas duas — nunca `3`, não existe
    /// estreitamento de `Q` para `D`), então a forma escalar precisa de um `boolean` próprio.
    record ArithmeticNarrowUnary(
            /// Operação a executar.
            Ir64VectorNarrowUnaryOp op,
            /// `true` para a forma ESCALAR (processa só o elemento `0`, `q` ignorado — ver acima).
            boolean scalar,
            /// `false`=escreve a metade BAIXA de `Rd` (forma sem `2`). `true`=escreve a metade
            /// ALTA (forma `*2`). Ignorado quando {@link #scalar}.
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rd`) em bytes — `0`-`2`. `Rn` usa `esz+1`.
            int esz,
            /// Registrador `V` de destino (elementos `esz`).
            int rd,
            /// Registrador `V` fonte, largo (elementos `esz+1`).
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_ARITHMETIC_NARROW_UNARY; }
    }

    /// AdvSIMD "shift by immediate" não-largo/não-estreito (B8.8) — `Rd`/`Rn` têm o MESMO tamanho
    /// de elemento; {@link #shift} já é a quantidade RESOLVIDA pelo decoder a partir de
    /// `immh:immb` (nunca o campo cru, mesma convenção de {@link IntegerOp64.Alu64#immediate}). Também
    /// representa a forma ESCALAR das operações que a aceitam (ver {@link Ir64VectorShiftOp}) — o
    /// DECODER valida quais operações aceitam qual `esz` na forma escalar, nunca o executor.
    /// {@link #scalar} é EXPLÍCITO (não reaproveita `esz=3`/`q=false`, mesmo motivo de
    /// {@link ArithmeticThreeSame#scalar}: `SQSHL`/`UQSHL`/`SQSHLU` aceitam `esz` variável na
    /// forma escalar, então `sqshl v0.8b,...,#imm` (vetorial) e `sqshl b0,...,#imm` (escalar) têm o
    /// MESMO par `esz=0`/`q=false`).
    record ShiftImmediate(
            /// Operação a executar.
            Ir64VectorShiftOp op,
            /// `true` para a forma ESCALAR — ver acima e {@link ArithmeticThreeSame#scalar}.
            boolean scalar,
            /// `true` para arranjo de 128 bits, `false` para 64 bits (ou forma escalar).
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Quantidade de deslocamento já resolvida: `1`-`(8<<esz)` para operações à direita
            /// (`SSHR`/`USHR`/`SRSHR`/`URSHR`/`SSRA`/`USRA`/`SRSRA`/`URSRA`/`SRI`), `0`-`(8<<esz)-1`
            /// para operações à esquerda (`SHL`/`SLI`/`SQSHL`/`UQSHL`/`SQSHLU`).
            int shift,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_SHIFT_IMMEDIATE; }
    }

    /// AdvSIMD "shift by immediate" estreitando (`SHRN`/`RSHRN`/`SQSHRN`/`UQSHRN`/`SQSHRUN`/
    /// `SQRSHRN`/`UQRSHRN`/`SQRSHRUN`, B8.8) — `Rn` tem elementos de `esz+1` bytes, `Rd` recebe
    /// elementos de `esz` bytes (metade selecionada por {@link #q}, mesma convenção "SIMD&FP
    /// destructive write" de {@link ArithmeticNarrow}). A forma ESCALAR (só as saturantes:
    /// `SHRN`/`RSHRN` não têm forma escalar real) processa um único elemento — {@link #scalar}
    /// explícito, mesmo motivo de {@link ShiftImmediate#scalar}.
    record ShiftNarrowImmediate(
            /// Operação a executar.
            Ir64VectorShiftNarrowOp op,
            /// `true` para a forma ESCALAR (processa só o elemento `0`; `q` ignorado).
            boolean scalar,
            /// `false`=escreve a metade BAIXA de `Rd`. `true`=escreve a metade ALTA (forma `*2`).
            /// Ignorado quando {@link #scalar}.
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rd`) em bytes — `0`-`2`. `Rn` usa `esz+1`.
            int esz,
            /// Quantidade de deslocamento à direita já resolvida: `1`-`(8<<esz)`.
            int shift,
            /// Registrador `V` de destino (elementos `esz`).
            int rd,
            /// Registrador `V` fonte, largo (elementos `esz+1`).
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_SHIFT_NARROW_IMMEDIATE; }
    }

    /// AdvSIMD "shift by immediate" alargando (`SSHLL`/`USHLL`, B8.8) — `Rn` tem elementos de `esz`
    /// bytes (metade selecionada por {@link #q}, mesma convenção de
    /// {@link ArithmeticWidening#q}), `Rd` recebe elementos de `esz+1` bytes, SEMPRE
    /// preenchendo os 128 bits inteiros (sem saturar).
    record ShiftWidenImmediate(
            /// Operação a executar.
            Ir64VectorShiftWidenOp op,
            /// Metade de `Rn` usada como entrada — ver {@link ArithmeticWidening#q}.
            boolean q,
            /// `log2` do tamanho do elemento ESTREITO (`Rn`) em bytes — `0`-`2`. `Rd` usa `esz+1`.
            int esz,
            /// Quantidade de deslocamento à esquerda já resolvida: `0`-`(8<<esz)-1`.
            int shift,
            /// Registrador `V` de destino (elementos `esz+1`, 128 bits inteiros).
            int rd,
            /// Registrador `V` fonte (elementos `esz`).
            int rn) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_SHIFT_WIDEN_IMMEDIATE; }
    }

    /// `PMULL`/`PMULL2` (B8.11, ARMv8-A Cryptographic Extension — `PMULL_p64` tecnicamente exige
    /// `FEAT_PMULL`, empacotado junto com AES no Cortex-A53 do raspi3): multiplicação polinomial
    /// `GF(2)` SEM redução (ao contrário de `PMUL`, que trunca — ver
    /// {@link ArithmeticThreeSame}), alargando o elemento. `p8`=`false`: 8 lanes de
    /// byte (`Rn`/`Rm` metade selecionada por {@link #q}, mesma convenção de
    /// {@link ArithmeticWidening#q}) produzindo 8 lanes de halfword em `Rd`. `p8`=`true`:
    /// UM elemento de 64 bits (a metade de `Rn`/`Rm` selecionada por {@link #q}) produzindo os 128
    /// bits inteiros de `Rd` — não cabe no formato genérico de {@link ArithmeticWidening}
    /// (que assume `esz+1` sempre um tamanho de elemento válido; aqui `64+64=128` é o registro
    /// inteiro, não um "elemento" further-alargável), por isso um record próprio.
    record PolynomialMultiplyLong(
            /// `true` para a forma de 64 bits (`PMULL_p64`, um elemento, resultado no registro
            /// inteiro); `false` para a forma de 8 bits (`PMULL_p8`, 8 lanes).
            boolean p64,
            /// `false` (forma sem `2`): usa a metade BAIXA de `Rn`/`Rm`. `true` (forma `*2`): usa
            /// a metade ALTA. Mesma convenção de {@link ArithmeticWidening#q}.
            boolean q,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_POLYNOMIAL_MULTIPLY_LONG; }
    }

    /// `USDOT`/`SDOT`/`UDOT` vetorial (`FEAT_I8MM`/`FEAT_DotProd`, B19.12/B19.23) — produto escalar
    /// de 4 bytes por lane com sinal POR OPERANDO (`USDOT`: `Rn` sem sinal, `Rm` com sinal; `SDOT`:
    /// os dois com sinal; `UDOT`: os dois sem sinal — não existe `SUDOT` vetorial, só a forma
    /// indexada, ver {@link IntegerDotProductByElement}), acumulando em `int32` com WRAP
    /// (nunca satura). Sibling inteiro do produto escalar `bf16`
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#bfDotProduct} — núcleo em
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProduct} (nasceu na B13.18 para o
    /// `neon-shared` de 32 bits).
    record IntegerDotProduct(
            /// `true` para arranjo de 128 bits (`Vd.4S`), `false` para 64 bits (`Vd.2S`).
            boolean q,
            /// Sinal de `Rn` (`USDOT`: sempre `false`; `SDOT`: sempre `true`; `UDOT`: sempre `false`).
            boolean signedN,
            /// Sinal de `Rm` (`USDOT`: sempre `true`; `SDOT`: sempre `true`; `UDOT`: sempre `false`).
            boolean signedM,
            /// Registrador `V` de destino (acumulador).
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_INTEGER_DOT_PRODUCT; }
    }

    /// `USDOT`/`SUDOT` indexados (`FEAT_I8MM`, B19.12) — como {@link IntegerDotProduct}, mas
    /// {@link #rm} sempre contribui o MESMO grupo de 4 bytes (`Vm.4B[index]`, restrito a `V0`-`V15`,
    /// índice de 2 bits `H:L` — mesma disciplina de {@link AdvSimdFpOp64.FpDotProductBFloat16ByElement},
    /// embora aqui a família tenha 4 grupos em vez de 2). `USDOT_vi` (`Rn` sem sinal, `Rm` com
    /// sinal) e `SUDOT_vi` (`Rn` com sinal, `Rm` sem sinal) se distinguem no ENCODING por
    /// bits[23:22] (`10`×`00`), nunca pelo bit `U` (os dois têm `U=0`) — o decoder já resolveu isso
    /// em {@link #signedN}/{@link #signedM}.
    record IntegerDotProductByElement(
            /// `true` para arranjo de 128 bits (`Vd.4S`), `false` para 64 bits (`Vd.2S`).
            boolean q,
            /// Sinal de `Rn` (`USDOT_vi`: sem sinal; `SUDOT_vi`: com sinal).
            boolean signedN,
            /// Sinal de `Rm` (`USDOT_vi`: com sinal; `SUDOT_vi`: sem sinal).
            boolean signedM,
            /// Registrador `V` de destino (acumulador).
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2 — só o grupo de 4 bytes {@link #index} é lido, replicado.
            int rm,
            /// Índice do grupo de 4 bytes de {@link #rm} usado em TODA a operação (`0`-`3`).
            int index) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_INTEGER_DOT_PRODUCT_BY_ELEMENT; }
    }

    /// `SMMLA`/`UMMLA`/`USMMLA` (`FEAT_I8MM`, B19.12) — multiplicação de matriz `2×8 · 8×2` de
    /// inteiros de 8 bits, acumulando em `int32` com WRAP. Irmã inteira de `BFMMLA`
    /// ({@link AdvSimdFpOp64.FpMatrixMultiplyAccumulateBFloat16}, `K=8` bytes aqui em vez de `K=4` pares
    /// `bf16`). `Q` é FIXO em `1` no encoding — não há forma de 64 bits. Não existe `SUMMLA`: a
    /// combinação `Rn` assinado/`Rm` sem sinal não tem encoding real (assimetria intencional do
    /// ISA). Núcleo em
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#matrixMultiplyAccumulate}.
    record IntegerMatrixMultiplyAccumulate(
            /// Sinal de `Rn` — ver a tabela completa no Javadoc de {@link #signedM}.
            boolean signedN,
            /// Sinal de `Rm` (`SMMLA`: com sinal; `UMMLA`/`USMMLA`: sem sinal). Tabela completa:
            /// `SMMLA`=(assinado,assinado), `UMMLA`=(sem sinal,sem sinal),
            /// `USMMLA`=(sem sinal,assinado).
            boolean signedM,
            /// Registrador `V` de destino (`Vd.4S`, matriz `2×2` de acumuladores).
            int rd,
            /// Registrador `V` fonte 1 (`Vn.16B`, matriz `2×8` — linha `r` = elementos `8r..8r+7`).
            int rn,
            /// Registrador `V` fonte 2 (`Vm.16B`, matriz `8×2` na MESMA disposição de {@link #rn} —
            /// coluna `c` = elementos `8c..8c+7`).
            int rm) implements AdvSimdIntegerOp64 {
        @Override public int kind() { return Kind.VECTOR_INTEGER_MATRIX_MULTIPLY_ACCUMULATE; }
    }
}
