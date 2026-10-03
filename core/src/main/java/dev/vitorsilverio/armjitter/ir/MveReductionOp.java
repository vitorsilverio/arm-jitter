package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações MVE de redução para registrador geral: soma, mínimo/máximo e produtos acumulados ao
/// longo do vetor (`VADDV`/`VMINV`/`VMAXV`/`VABAV`/`VMLADAV`/...).
///
/// Sub-interface selada de {@link MveOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface MveReductionOp extends MveOp permits MveReductionOp.VectorAddAcrossVector,
        MveReductionOp.VectorAddAcrossVectorLong, MveReductionOp.VectorAbsoluteDifferenceAccumulate,
        MveReductionOp.VectorDualAccumulate, MveReductionOp.VectorDualAccumulateLong,
        MveReductionOp.VectorRoundingDualAccumulateHigh, MveReductionOp.VectorMinMaxAcrossVector,
        MveReductionOp.VectorFpMinMaxAcrossVector {

    /// `VADDV` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode`,
    /// linhas 578-582, sub-família 4): soma horizontal das lanes ATIVAS de `Qm` (`size`: byte/
    /// halfword/word, `unsignedForm` controla a extensão de sinal antes da soma) em `Rda` — verbatim
    /// de `DO_VADDV`/`mve_helper.c`: lane mascarada simplesmente NÃO contribui (nem soma zero, nem
    /// interrompe as demais). `accumulate=false` (`a=0`) começa a soma do ZERO (o modelo de "beat"
    /// do QEMU só herda `Rda` no meio de uma instrução partida — este emulador executa cada
    /// instrução atomicamente, então SEMPRE está no "primeiro beat"); `accumulate=true` (`a=1`)
    /// começa a soma do valor ATUAL de `Rda`. Com TODAS as lanes mascaradas, `Rda` fica inalterado
    /// (`a=1`) ou vira `0` (`a=0`) — o laço real simplesmente não executa nenhuma iteração.
    record VectorAddAcrossVector(
            /// `true` para a forma não assinada (`u=1`).
            boolean unsignedForm,
            /// `true` acumula sobre `Rda` atual; `false` começa do zero.
            boolean accumulate,
            /// `0`(byte)/`1`(halfword)/`2`(word); `3` recusado no decode.
            int size,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `Rda` (registrador de destino/acumulador).
            int rda,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_ADD_ACROSS_VECTOR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorAddAcrossVector(core, this); }
        @Override public int regUse() { return (accumulate ? (1 << rda) : 0) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return 1 << rda; }
    }

    /// `VADDLV` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode`,
    /// linhas 584-588, sub-família 4): como {@link VectorAddAcrossVector}, mas os elementos são
    /// SEMPRE de 32 bits e o acumulador é um PAR de GPRs de 64 bits (`RdaHi:RdaLo`) — verbatim de
    /// `DO_VADDLV`/`mve_helper.c`. Mesma semântica de `accumulate`/máscara-toda-zero de {@link
    /// VectorAddAcrossVector}.
    record VectorAddAcrossVectorLong(
            /// `true` para a forma não assinada (`u=1`).
            boolean unsignedForm,
            /// `true` acumula sobre `RdaHi:RdaLo` atual; `false` começa do zero.
            boolean accumulate,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `RdaHi` (`13`/`15` recusados no decode; `%rdahi` — ímpar).
            int rdahi,
            /// `RdaLo` (`%rdalo` — par).
            int rdalo,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_ADD_ACROSS_VECTOR_LONG; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorAddAcrossVectorLong(core, this); }
        @Override public int regUse() { return (accumulate ? (1 << rdahi) | (1 << rdalo) : 0) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return (1 << rdahi) | (1 << rdalo); }
    }

    /// `VABAV_S`/`VABAV_U` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`,
    /// `target/isa-decode/mve.decode`, linhas 591-594, sub-família 4): soma horizontal, nas lanes
    /// ATIVAS, da diferença absoluta `|Qn[i] - Qm[i]|` (assinada/não assinada por `unsignedForm`,
    /// `size` byte/halfword/word) em `Rda` — verbatim de `DO_VABAV`/`mve_helper.c`. **Sem bit `a`**:
    /// ao contrário de {@link VectorAddAcrossVector}, SEMPRE acumula sobre o `Rda` ATUAL (o
    /// helper real recebe `ra` já carregado do registrador, sem ramo de "começar do zero").
    record VectorAbsoluteDifferenceAccumulate(
            /// `true` para a forma não assinada.
            boolean unsignedForm,
            /// `0`(byte)/`1`(halfword)/`2`(word); `3` recusado no decode.
            int size,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `Rda` (sempre acumulador, nunca `13`/`15` — recusados no decode).
            int rda,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorAbsoluteDifferenceAccumulate(core, this); }
        @Override public int regUse() { return (1 << rda) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return 1 << rda; }
    }

    /// `VMLADAV_S`/`VMLADAV_U`/`VMLSDAV` (perfil M, B16.13b, MVE/Helium, `FEAT_MVE_INTEGER`,
    /// `target/isa-decode/mve.decode`, linhas 443-462, sub-família 3): produto-soma horizontal, nas
    /// lanes ATIVAS, em `Rda` (32 bits) — verbatim de `DO_DAV`/`mve_helper.c`. Para a lane `e`: se
    /// `e` PAR, `Rda += n[e+x]*m[e]` (SEMPRE soma); se `e` ÍMPAR, `Rda += n[e-x]*m[e]` (`VMLADAV_*`)
    /// ou `Rda -= n[e-x]*m[e]` (`VMLSDAV`, {@code subtract=true}). `x` (`exchange`) troca o índice de
    /// `Qn` só nas lanes onde é lido: PAR usa vizinho SEGUINTE, ÍMPAR usa vizinho ANTERIOR — nunca
    /// troca o índice de `Qm`. `VMLADAV_U` não tem forma `exchange` (rejeitada no decode: sem helper
    /// real, `fns[size][1]=NULL`).
    record VectorDualAccumulate(
            /// `true` para a forma não assinada (só `VMLADAV_U`).
            boolean unsignedForm,
            /// `true` para `VMLSDAV` (lane ímpar subtrai em vez de somar).
            boolean subtract,
            /// `true` quando o encoding troca o vizinho de `Qn` lido nas lanes (`x=1`).
            boolean exchange,
            /// `true` acumula sobre `Rda` atual; `false` começa do zero.
            boolean accumulate,
            /// `0`(byte)/`1`(halfword)/`2`(word).
            int size,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `Rda` (registrador de destino/acumulador).
            int rda,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DUAL_ACCUMULATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorDualAccumulate(core, this); }
        @Override public int regUse() { return (accumulate ? (1 << rda) : 0) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return 1 << rda; }
    }

    /// `VMLALDAV_S`/`VMLALDAV_U`/`VMLSLDAV` (perfil M, B16.13b, MVE/Helium, `FEAT_MVE_INTEGER`,
    /// `target/isa-decode/mve.decode`, linhas 443-462, sub-família 3): como {@link
    /// VectorDualAccumulate}, mas `size` só `1`(halfword)/`2`(word) e o acumulador é um PAR de
    /// GPRs de 64 bits (`RdaHi:RdaLo`) — verbatim de `DO_LDAV`/`mve_helper.c`. `VMLALDAV_U` não tem
    /// forma `exchange` (mesma restrição de {@link VectorDualAccumulate}).
    record VectorDualAccumulateLong(
            /// `true` para a forma não assinada (`VMLALDAV_U`).
            boolean unsignedForm,
            /// `true` para `VMLSLDAV` (lane ímpar subtrai em vez de somar).
            boolean subtract,
            /// `true` quando o encoding troca o vizinho de `Qn` lido nas lanes (`x=1`).
            boolean exchange,
            /// `true` acumula sobre `RdaHi:RdaLo` atual; `false` começa do zero.
            boolean accumulate,
            /// `1`(halfword)/`2`(word).
            int size,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `RdaHi` (`13`/`15` recusados no decode; `%rdahi` — ímpar).
            int rdahi,
            /// `RdaLo` (`%rdalo` — par).
            int rdalo,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DUAL_ACCUMULATE_LONG; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorDualAccumulateLong(core, this); }
        @Override public int regUse() { return (accumulate ? (1 << rdahi) | (1 << rdalo) : 0) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return (1 << rdahi) | (1 << rdalo); }
    }

    /// `VRMLALDAVH_S`/`VRMLALDAVH_U`/`VRMLSLDAVH` (perfil M, B16.13b, MVE/Helium,
    /// `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode`, linhas 477-493, sub-família 3):
    /// "rounding multiply add/subtract long dual accumulate HIGH" — elementos SEMPRE de 32 bits (4
    /// lanes fixas), produto de 64 bits arredondado (`(mul>>8) + ((mul>>7)&1)`) antes de somar ao
    /// acumulador de 64 bits `RdaHi:RdaLo` — verbatim de `DO_LDAVH`/`mve_helper.c`. Diferença de
    /// {@link VectorDualAccumulateLong}: a negação (`subtract`) se aplica ao PRODUTO antes do
    /// arredondamento (só na lane ímpar), não ao acumulador. `VRMLALDAVH_U` não tem forma `exchange`.
    record VectorRoundingDualAccumulateHigh(
            /// `true` para a forma não assinada (`VRMLALDAVH_U`).
            boolean unsignedForm,
            /// `true` para `VRMLSLDAVH` (lane ímpar nega o produto antes do arredondamento).
            boolean subtract,
            /// `true` quando o encoding troca o vizinho de `Qn` lido nas lanes (`x=1`).
            boolean exchange,
            /// `true` acumula sobre `RdaHi:RdaLo` atual; `false` começa do zero.
            boolean accumulate,
            /// `Qn` (`0`-`7`).
            int qn,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `RdaHi` (`13`/`15` recusados no decode; `%rdahi` — ímpar).
            int rdahi,
            /// `RdaLo` (`%rdalo` — par).
            int rdalo,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorRoundingDualAccumulateHigh(core, this); }
        @Override public int regUse() { return (accumulate ? (1 << rdahi) | (1 << rdalo) : 0) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return (1 << rdahi) | (1 << rdalo); }
    }

    /// `VMAXV_S`/`VMAXV_U`/`VMINV_S`/`VMINV_U`/`VMAXAV`/`VMINAV` (perfil M, B16.13b, MVE/Helium,
    /// `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode`, linhas 464-493, sub-família 3): min/max
    /// horizontal, nas lanes ATIVAS, entre o `Rda` ATUAL (sempre lido como valor inicial — SEM bit
    /// `a`, mesma categoria de {@link VectorAbsoluteDifferenceAccumulate}) e cada elemento de
    /// `Qm` — verbatim de `DO_VMAXMINV`/`mve_helper.c`. `VMAXAV`/`VMINAV` (`absoluteForm=true`) leem
    /// `Rda` como NÃO ASSINADO e o elemento como ASSINADO, tomando `|elemento|` antes de comparar
    /// (`do_maxa`/`do_mina`) — não têm forma `unsignedForm` própria.
    record VectorMinMaxAcrossVector(
            /// `true` para `VMAXV_*`/`VMAXAV`; `false` para `VMINV_*`/`VMINAV`.
            boolean max,
            /// `true` para `VMAXV_U`/`VMINV_U` (ignorado quando `absoluteForm=true`).
            boolean unsignedForm,
            /// `true` para `VMAXAV`/`VMINAV` (compara `|Qm[i]|`, `Rda` não assinado).
            boolean absoluteForm,
            /// `0`(byte)/`1`(halfword)/`2`(word); `3` recusado no decode.
            int size,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `Rda` (`13`/`15` recusados no decode; sempre lido/escrito, nunca "começa do zero").
            int rda,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_MIN_MAX_ACROSS_VECTOR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorMinMaxAcrossVector(core, this); }
        // O valor inicial da redução é sempre o `rda` atual.
        @Override public int regUse() { return (1 << rda) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return 1 << rda; }
    }

    /// `VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` (perfil M, B16.13b, MVE/Helium, `FEAT_MVE_FP`,
    /// `target/isa-decode/mve.decode`, linhas 464-493, sub-família 3): como {@link
    /// VectorMinMaxAcrossVector}, mas de ponto flutuante (binary16/binary32,
    /// `float16_maxnum`/`minnum`/`float32_maxnum`/`minnum` verbatim de `DO_FP_VMAXMINV`,
    /// `mve_helper.c`) — sem modelo de exceção de NaN sinalizador (G8, mesma simplificação
    /// consciente do resto do núcleo FP MVE, ver
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#fpAbsAccumulateMasked}).
    /// `VMAXNMAV`/`VMINNMAV` (`absoluteForm=true`) tomam `|Qm[i]|` antes de comparar; `Rda` nunca é
    /// tomado em valor absoluto (só o elemento fonte, mesma regra de {@link
    /// VectorMinMaxAcrossVector}). Resultado de meia precisão ZERA os 16 bits altos de `Rda`
    /// (verbatim: o helper real retorna `uint32_t` truncado do `float16` de 16 bits).
    record VectorFpMinMaxAcrossVector(
            /// `true` para `VMAXNMV`/`VMAXNMAV`; `false` para `VMINNMV`/`VMINNMAV`.
            boolean max,
            /// `true` para `VMAXNMAV`/`VMINNMAV` (compara `|Qm[i]|`).
            boolean absoluteForm,
            /// `1`(binary16)/`2`(binary32).
            int esz,
            /// `Qm` (`0`-`7`).
            int qm,
            /// `Rda` (`13`/`15` recusados no decode).
            int rda,
            /// Condição necessária para executar.
            Condition condition) implements MveReductionOp {
        @Override public int kind() { return Kind.MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorFpMinMaxAcrossVector(core, this); }
        @Override public int regUse() { return (1 << rda) | GprMask.MVE_TAIL_PREDICATION; }
        @Override public int regDef() { return 1 << rda; }
    }
}
