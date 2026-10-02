package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;

/// Operações NEON de aritmética inteira: "three same", pareadas, alargantes, estreitantes,
/// deslocamento por imediato, por elemento, unárias, produto escalar e multiplicação de matriz.
///
/// Sub-interface selada de {@link NeonOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface NeonIntegerOp extends NeonOp permits NeonIntegerOp.ThreeSame,
        NeonIntegerOp.Pairwise, NeonIntegerOp.ShiftImmediate, NeonIntegerOp.ShiftNarrowImmediate,
        NeonIntegerOp.ShiftWidenImmediate, NeonIntegerOp.Widening, NeonIntegerOp.Wide,
        NeonIntegerOp.Narrow, NeonIntegerOp.ThreeSameByElement, NeonIntegerOp.WideningByElement,
        NeonIntegerOp.Unary, NeonIntegerOp.NarrowUnary, NeonIntegerOp.DotProduct,
        NeonIntegerOp.DotProductByElement, NeonIntegerOp.MatrixMultiplyAccumulate {

    /// NEON/Advanced SIMD de 32 bits, forma "three same" (B13.2/B13.4): `Vd[i] = op(Vn[i], Vm[i])`
    /// para cada lane de `1 << esz` bytes do arranjo. Espelho de
    /// {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticThreeSame} no ENCODING/IR, mas
    /// a SEMÂNTICA de lane é a mesma dos dois lados: ambos os executores chamam
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSame} (RFC B13.2, D1).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`), então a condição é sempre
    /// {@link Condition#AL} — não há forma condicional desta instrução.
    record ThreeSame(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`, bit `Q` do encoding),
            /// `false` para o de 64 bits (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword.
            int esz,
            /// Registrador de destino, SEMPRE em índice de `D` (`0`-`31`) — na forma `quad` é o
            /// `D` par que inicia o `Q` (é assim que o encoding NEON nomeia os registradores).
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_THREE_SAME; }
    }

    /// NEON/Advanced SIMD de 32 bits, forma "pairwise" (B13.4): `VPADD`/`VPMAX`/`VPMIN`. Concatena
    /// `Vn:Vm` (`Vn` primeiro), combina pares de elementos ADJACENTES nessa sequência de
    /// `2 * (8 >> esz)` elementos e grava `8 >> esz` resultados em `Vd` (metade baixa vinda de
    /// `Vn`, metade alta de `Vm`). Só forma `D` no encoding A32 (`@3same_q0`), por isso não há
    /// campo `quad`.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticPairwise} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#pairwise}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Pairwise(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp op,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word.
            int esz,
            /// Registrador de destino, índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte 1 (metade baixa do resultado), índice de `D`.
            int vn,
            /// Registrador fonte 2 (metade alta do resultado), índice de `D`.
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_PAIRWISE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" com deslocamento por IMEDIATO (B13.7):
    /// `VSHR`/`VSRA`/`VRSHR`/`VRSRA`/`VSRI`/`VSHL`/`VSLI`/`VQSHL`/`VQSHLU` (as 14 famílias, `esz`
    /// `0`-`3`). `Vd[i] = op(Vm[i], #shift)` — e, para `VSRA`/`VRSRA`/`VSRI`/`VSLI`, ACUMULA ou
    /// INSERE no `Vd[i]` ATUAL (o campo {@link #vd} é destino E fonte nessas famílias). O
    /// deslocamento já vem resolvido do encoding (`immh:immb`), NUNCA recalculado no executor.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftImmediate} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftImmediate}), RFC B13.2 D1.
    /// **Não há `Vn`** — é forma de 2 registradores: {@link #vm} é a FONTE do valor deslocado.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ShiftImmediate(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`, bit `Q` do encoding), `false`
            /// para o de 64 bits (`D<d>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword.
            int esz,
            /// Quantidade de deslocamento já resolvida (`1..esize` para os à direita, `0..esize-1`
            /// para os à esquerda).
            int shift,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE nas famílias que acumulam/inserem
            /// (`VSRA`/`VRSRA`/`VSRI`/`VSLI`).
            int vd,
            /// Registrador fonte do valor deslocado, em índice de `D` (ver {@link #vd}).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_SHIFT_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" com deslocamento por imediato ESTREITANTE
    /// (B13.8): `VSHRN`/`VRSHRN`/`VQSHRUN`/`VQRSHRUN`/`VQSHRN`/`VQRSHRN` (as 8 famílias). A fonte é
    /// um `Q` (128 bits, elementos de `esz + 1` bytes), o destino é um `D` (64 bits, elementos de
    /// `esz` bytes) — `Vd[i] = narrow(op(Vm[i], #shift))`, deslocamento à direita já resolvido do
    /// encoding. **Sem campo `quad`**: a fonte é sempre `Q` e o destino sempre `D`; o bit `Q` do
    /// encoding faz parte do OPCODE (escolhe entre `VSHRN`/`VRSHRN`, etc.), não da largura.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftNarrowImmediate} no
    /// ENCODING/IR; a SEMÂNTICA de lane vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftNarrowImmediate}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ShiftNarrowImmediate(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp op,
            /// `log2` do tamanho do elemento de DESTINO (lado ESTREITO) em bytes: `0`=byte,
            /// `1`=halfword, `2`=word. A fonte tem elementos de `esz + 1`.
            int esz,
            /// Quantidade de deslocamento à direita já resolvida (`1..8<<esz`).
            int shift,
            /// Registrador de destino (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_SHIFT_NARROW_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-reg-and-shift" com deslocamento por imediato ALARGANTE
    /// (B13.8): `VSHLL` (fonte assinada → `SSHLL`, fonte não assinada → `USHLL`). A fonte é um `D`
    /// (64 bits, elementos de `esz` bytes), o destino é um `Q` (128 bits, elementos de `esz + 1`
    /// bytes) — `Vd[i] = ext(Vm[i]) << #shift`, nunca satura. **Sem campo `quad`** pelo mesmo
    /// motivo de {@link ShiftNarrowImmediate}: fonte `D`, destino `Q` fixos; o bit `Q` do
    /// encoding faz parte do OPCODE.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftWidenImmediate} no
    /// ENCODING/IR; a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#shiftWidenImmediate}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ShiftWidenImmediate(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp op,
            /// `log2` do tamanho do elemento de FONTE (lado ESTREITO) em bytes: `0`=byte,
            /// `1`=halfword, `2`=word. O destino tem elementos de `esz + 1`.
            int esz,
            /// Quantidade de deslocamento à esquerda já resolvida (`0..(8<<esz)-1`).
            int shift,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_SHIFT_WIDEN_IMMEDIATE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two registers, or three registers of different lengths",
    /// SUBGRUPO "three-reg-different-lengths" — forma **Long** ALARGANDO (B13.10): `VADDL`/`VSUBL`/
    /// `VABAL`/`VABDL`/`VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`/`VMULL.P8`. `Vn`/`Vm` são
    /// `D` (elementos de {@link #esz} bytes), `Vd` é `Q` (elementos de `esz+1`, DOBRO — nomeado pelo
    /// `D` par que inicia o `Q`, como o NEON encoda operandos de 128 bits). **Sem campo `quad`**: o
    /// destino é SEMPRE `Q` nesta forma (não há "3-reg-different" com destino `D`).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticWidening} no
    /// ENCODING/IR (menos `scalar`/`q`, que não existem nesta seção do A32 — sem forma "2", sem
    /// forma escalar real); a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#widening}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Widening(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vn`/`Vm`) em bytes — `0`-`2` (byte/half/
            /// word). `Vd` usa `esz+1`.
            int esz,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vn,
            /// Registrador fonte 2 (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_WIDENING; }
    }

    /// NEON/Advanced SIMD de 32 bits, "three-reg-different-lengths", forma **Wide** (B13.10):
    /// `VADDW`/`VSUBW`. `Vd`/`Vn` são `Q` (elementos de `esz+1`), `Vm` é `D` (elementos de
    /// {@link #esz}). **Sem campo `quad`**: `Vd`/`Vn` são SEMPRE `Q`, `Vm` SEMPRE `D` nesta forma.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticWide} no
    /// ENCODING/IR (menos `q`, que não existe nesta seção do A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#wide}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Wide(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vm`) em bytes — `0`-`2`. `Vd`/`Vn` usam
            /// `esz+1`.
            int esz,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (`Q`, 128 bits, já LARGO), em índice de `D` par que inicia o `Q`.
            int vn,
            /// Registrador fonte 2 (`D`, 64 bits, ESTREITO), em índice de `D` (`0`-`31`).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_WIDE; }
    }

    /// NEON/Advanced SIMD de 32 bits, "three-reg-different-lengths", forma **Narrow**/"half
    /// narrowing" (B13.10): `VADDHN`/`VRADDHN`/`VSUBHN`/`VRSUBHN`. `Vn`/`Vm` são `Q` (elementos de
    /// `esz+1`), `Vd` é `D` (elementos de {@link #esz}, a metade ALTA da soma/diferença larga).
    /// **Sem campo `quad`**: `Vn`/`Vm` são SEMPRE `Q`, `Vd` SEMPRE `D` nesta forma (A32 não tem
    /// forma "2" — `laneOffset` é sempre `0` no executor).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticNarrow} no
    /// ENCODING/IR (menos `q`, que não existe nesta seção do A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#narrow}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Narrow(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vd`) em bytes — `0`-`2`. `Vn`/`Vm` usam
            /// `esz+1`.
            int esz,
            /// Registrador de destino (`D`, 64 bits, ESTREITO), em índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte 1 (`Q`, 128 bits, LARGO), em índice de `D` par que inicia o `Q`.
            int vn,
            /// Registrador fonte 2 (`Q`, 128 bits, LARGO), em índice de `D` par que inicia o `Q`.
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_NARROW; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-regs-plus-scalar", forma **mesma largura**/"doubling high
    /// half" (B13.11): `VMLA`/`VMLS`/`VMUL` inteiro (sem variante de sinal — mesmo padrão do
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp#MUL} "three same") e
    /// `VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` (`VQRDMLAH`/`VQRDMLSH` só sob
    /// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#ADVANCED_SIMD_RDM}). `Vd`/`Vn` são `D` ou
    /// `Q` conforme {@link #quad}; {@link #vm} é o registrador do ESCALAR já restrito à faixa REAL
    /// do encoding A32 (`D0`-`D7` halfword / `D0`-`D15` word — diferente do índice `H:L:M` do A64,
    /// que estreita `Rm` a `V0`-`V15`), e {@link #index} já extraído (`M:Vm[3]` halfword / `M`
    /// word).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticThreeSameByElement}
    /// no ENCODING/IR (índice montado diferente, sem `scalar`, que não existe nesta seção do A32); a
    /// SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#threeSameByElement}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ThreeSameByElement(
            /// Operação a executar (núcleo compartilhado) — só `MUL`/`MLA`/`MLS`/`SQDMULH`/
            /// `SQRDMULH`/`SQRDMLAH`/`SQRDMLSH` são válidas aqui (G8: o decoder nunca produz outro
            /// valor).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp op,
            /// `log2` do tamanho do elemento em bytes — `1` (halfword) ou `2` (word); `0`/`3` não
            /// existem nesta classe (G8 no decoder).
            int esz,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE em `MLA`/`MLS`/`SQRDMLAH`/`SQRDMLSH` (leem `Vd` atual).
            int vd,
            /// Registrador fonte 1, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vn,
            /// Registrador do ESCALAR (`D0`-`D7` halfword / `D0`-`D15` word — já restrito pelo
            /// decoder, nunca um índice de `D` de 5 bits completo).
            int vm,
            /// Índice do elemento dentro de {@link #vm} (`M:Vm[3]`, 2 bits, halfword / `M`, 1 bit,
            /// word — já extraído pelo decoder).
            int index) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_THREE_SAME_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, "2-regs-plus-scalar", forma **alargando** (B13.11):
    /// `VMLAL`/`VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`. `Vd` é sempre `Q`, `Vn` é sempre `D`
    /// (mesma disciplina de {@link Widening} — sem forma "2", sem forma escalar real); {@link
    /// #vm} é o registrador do ESCALAR já restrito à faixa real e {@link #index} já extraído (mesma
    /// convenção de {@link ThreeSameByElement}).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticWideningByElement}
    /// no ENCODING/IR (índice montado diferente, sem `scalar`/`q`); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#wideningByElement}),
    /// RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record WideningByElement(
            /// Operação a executar (núcleo compartilhado) — só `SMULL`/`UMULL`/`SMLAL`/`UMLAL`/
            /// `SMLSL`/`UMLSL`/`SQDMULL`/`SQDMLAL`/`SQDMLSL` são válidas aqui (G8).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vn`/escalar) em bytes — `1` ou `2`. `Vd` usa
            /// `esz+1`.
            int esz,
            /// Registrador de destino (`Q`, 128 bits), em índice de `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte (`D`, 64 bits), em índice de `D` (`0`-`31`).
            int vn,
            /// Registrador do ESCALAR (`D0`-`D7` halfword / `D0`-`D15` word — já restrito pelo
            /// decoder).
            int vm,
            /// Índice do elemento dentro de {@link #vm} (`M:Vm[3]` halfword / `M` word — já
            /// extraído pelo decoder).
            int index) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_WIDENING_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous" INTEIRA, sub-grupo `size==0b11`
    /// (B13.12): `VREV64`/`VREV32`/`VREV16` (reversão de bytes), `VPADDL`/`VPADAL` (pareamento
    /// largo, `S`/`U`, o segundo ACUMULA), `VCLS`/`VCLZ`/`VCNT`/`VMVN`, `VQABS`/`VQNEG`, as 5
    /// comparações-com-zero (`VCGT0`/`VCGE0`/`VCEQ0`/`VCLE0`/`VCLT0`), `VABS`/`VNEG` e `VRECPE`/
    /// `VRSQRTE` inteiros (estimativas puro-inteiras).
    ///
    /// **Layout PRÓPRIO deste sub-grupo** (diferente de B13.4-B13.11): `size` = bits[19:18]
    /// (elemento de {@link #esz}), `opc1` = bits[17:16], `opc2` = bits[10:7], `q` = bit6 —
    /// {@link #esz} neste record é o CAMPO `size`, não uma largura fixa por forma.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticUnary} no
    /// ENCODING/IR (sem `scalar`, que não existe nesta seção do A32); a SEMÂNTICA vem do núcleo
    /// COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#unary}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Unary(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<m>`). Também é FONTE em `VPADAL` (lê `Vd` atual, já em `esz+1`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes — `0`-`2` (byte/half/word; `3` não existe
            /// neste sub-grupo, G8 no decoder). Para `VPADDL`/`VPADAL`, é o tamanho ESTREITO
            /// (`Vd` usa `esz+1`).
            int esz,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_UNARY; }
    }

    /// NEON/Advanced SIMD de 32 bits, "two-register miscellaneous", sub-grupo `size==0b11`
    /// (B13.12): `VMOVN`/`VQMOVUN`/`VQMOVN_S`/`VQMOVN_U` — narrow unário. `Vm` é `Q` (elementos de
    /// `esz+1`), `Vd` é `D` (elementos de {@link #esz}). **Sem campo `quad`**: fonte SEMPRE `Q`,
    /// destino SEMPRE `D` (a forma "2-reg-misc" força `q=0` no encoding real, ver `neon-dp.decode`).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticNarrowUnary} no
    /// ENCODING/IR (sem `scalar`/`q`); a SEMÂNTICA vem do núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#narrowUnary}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record NarrowUnary(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp op,
            /// `log2` do tamanho do elemento ESTREITO (`Vd`) em bytes — `0`-`2`. `Vm` usa `esz+1`.
            int esz,
            /// Registrador de destino (`D`, 64 bits, ESTREITO), em índice de `D` (`0`-`31`).
            int vd,
            /// Registrador fonte (`Q`, 128 bits, LARGO), em índice de `D` par que inicia o `Q`.
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_NARROW_UNARY; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VSDOT`/`VUDOT`/`VUSDOT` (B13.18,
    /// `FEAT_DotProd`/`FEAT_I8MM`): cada lane de 32 bits de {@link #vd} ACUMULA (wrap) a soma dos 4
    /// produtos de byte de {@link #vn}/{@link #vm} na mesma lane. **Não existe `VSUDOT` vetorial**
    /// (só `_scalar`) — este record nunca representa essa combinação de sinais na forma vetorial.
    ///
    /// Núcleo COMPARTILHADO ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProduct}),
    /// RFC B13.2 D1 — **exceção do épico**: nem `SDOT_v`/`UDOT_v` nem `USDOT`/`SUDOT` do A64 têm
    /// decoder ainda, então não há semântica prévia a migrar; a semântica nasce aqui para a B19.12
    /// (a task irmã A64 das formas mistas) reusar.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record DotProduct(
            /// `true` se {@link #vn} é lido como assinado (`VSDOT`/`VSUDOT`), `false` se sem sinal
            /// (`VUDOT`/`VUSDOT`).
            boolean signedN,
            /// `true` se {@link #vm} é lido como assinado (`VSDOT`/`VUSDOT`), `false` se sem sinal
            /// (`VUDOT`/`VSUDOT`).
            boolean signedM,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`/`D<m>`).
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1, em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2, em índice de `D` (ver {@link #vd}).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VSDOT_scalar`/`VUDOT_scalar`/
    /// `VUSDOT_scalar`/`VSUDOT_scalar` (B13.18): como {@link DotProduct}, mas o operando `b` é
    /// uma única lane de 32 bits FIXA lida de {@link #vm} no {@link #index}, replicada para cada
    /// lane de {@link #vn}. `VSUDOT` só existe nesta forma (não há `VSUDOT` vetorial).
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#dotProductByElement}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record DotProductByElement(
            /// `true` se {@link #vn} é lido como assinado (`VSDOT_scalar`/`VSUDOT_scalar`), `false`
            /// se sem sinal (`VUDOT_scalar`/`VUSDOT_scalar`).
            boolean signedN,
            /// `true` se {@link #vm} é lido como assinado (`VSDOT_scalar`/`VUSDOT_scalar`), `false`
            /// se sem sinal (`VUDOT_scalar`/`VSUDOT_scalar`).
            boolean signedM,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<n>`), `false` para o de 64 bits
            /// (`D<d>`/`D<n>`). `vm` é sempre um `D` (nunca `Q`), independente desta forma.
            boolean quad,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`); na forma `quad` é o
            /// `D` par que inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (varia por lane), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (FIXO, lido uma vez), em índice de `D` (`0`-`15`, **nunca**
            /// combinado com {@link #quad}).
            int vm,
            /// Índice da lane de 32 bits dentro de {@link #vm}: `0`-`1` (um `D` guarda 2 lanes).
            int index) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_DOT_PRODUCT_BY_ELEMENT; }
    }

    /// NEON/Advanced SIMD de 32 bits, `neon-shared` — `VSMMLA`/`VUMMLA`/`VUSMMLA` (B13.19,
    /// `FEAT_I8MM`, mesma feature de {@link DotProduct}/{@link DotProductByElement} para as
    /// formas mistas): multiplicação de matriz `2×8 · 8×2` de inteiros de 8 bits, acumulando em
    /// `int32` COM WRAP (nunca satura). **Sempre 128 bits** — não existe forma `D` (índice de
    /// registrador ímpar em {@link #vd}/{@link #vn}/{@link #vm} é UNDEFINED, mesma disciplina das
    /// formas `quad` das siblings deste arquivo, mas aqui sem campo `quad`: a forma `D` não existe).
    /// **Não existe `VSUMMLA`** — a assimetria (`VUSMMLA` = `Vn` sem sinal/`Vm` assinado) é
    /// intencional, espelhando o A64.
    ///
    /// Núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#matrixMultiplyAccumulate}), criado
    /// pela **B19.12** (a task irmã A64 de `SMMLA`/`UMMLA`/`USMMLA`) — reusado sem alteração.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record MatrixMultiplyAccumulate(
            /// `true` se {@link #vn} é lido como assinado (`VSMMLA`/`VUSMMLA`), `false` se sem sinal
            /// (`VUMMLA`).
            boolean signedN,
            /// `true` se {@link #vm} é lido como assinado (`VSMMLA`), `false` se sem sinal
            /// (`VUMMLA`/`VUSMMLA`).
            boolean signedM,
            /// Registrador de destino/acumulador, em índice de `D` (`0`-`31`) — o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (duas linhas de 8 bytes), em índice de `D` (ver {@link #vd}).
            int vn,
            /// Registrador fonte 2 (duas colunas de 8 bytes), em índice de `D` (ver {@link #vd}).
            int vm) implements NeonIntegerOp {
        @Override public int kind() { return Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE; }
    }
}
