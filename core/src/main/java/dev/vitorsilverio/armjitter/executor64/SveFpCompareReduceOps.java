package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

import java.util.Arrays;

/// Semântica da comparação de ponto flutuante SVE (que produz predicado) e das reduções de ponto flutuante (B17.15):
/// `FCMGE`/`FCMGT`/`FCMEQ`/`FCMNE`/`FCMUO`/`FACGE`/`FACGT` (vetor×vetor), as seis `FCM*` com zero, `FADDV`/`FMAXNMV`/
/// `FMINNMV`/`FMAXV`/`FMINV` (árvore), as cinco `*QV` (por segmento de 128 bits) e `FADDA` (serial). A matemática vive
/// em {@link SveFloat}; aqui só há o laço por elemento.
///
/// **A comparação FP NÃO seta `NZCV`** (a spec da task dizia o contrário; o `do_fp_cmp`/`do_ppz_fp` do QEMU só escreve o
/// predicado, e o manual não tem forma `S` das `FCM*`): só a comparação inteira da B17.9 usa `predtest`. O que a
/// comparação FP pode sujar é o `FPSR` — e só para elementos ATIVOS, porque o resultado de um elemento inativo é `0` sem
/// nem olhar os operandos. `FCMGE`/`FCMGT`/`FCMLE`/`FCMLT`/`FACGE`/`FACGT` são sinalizantes (qualquer NaN levanta `IOC`)
/// e `FCMEQ`/`FCMNE`/`FCMUO` são quietas (só sNaN); `FCMNE` é a ÚNICA que dá verdadeiro para um par não ordenado além de
/// `FCMUO`.
///
/// **`FADDV` (e `FMAXV`/…) reduz em ÁRVORE, `FADDA` em série.** A árvore é a recursiva do manual (`ReducePredicated`) e do
/// QEMU (`DO_REDUCE`): o vetor é completado com o valor neutro até uma potência de dois de elementos (`+0` em `FADDV`,
/// `+∞` em `FMINV`, `-∞` em `FMAXV`, o NaN padrão em `FMAXNMV`/`FMINNMV`), elemento inativo vira o neutro, e cada nó é
/// `op(metade baixa, metade alta)`. Somar da esquerda para a direita dá outro resultado quando as magnitudes diferem;
/// `FADDA` é exatamente essa soma serial, começando pelo escalar `Vdn`, e NÃO é paralelizável (relevante para uma futura
/// emissão nativa). O escalar sai em `V<d>` com o resto do registrador zerado.
///
/// As `*QV` reduzem, para cada posição de elemento dentro do segmento de 128 bits, TODOS os segmentos (não um escalar por
/// segmento): o resultado é um `V<d>` de 128 bits. Em `VL = 128` coincidem com as escalares. Com número de segmentos que
/// não é potência de dois a árvore também é completada com o neutro (o `DO_REDUCE` do QEMU descarta o último; o manual
/// não).
///
/// `FADDA` é ilegal em modo streaming (a menos que `FEAT_SME_FA64` esteja efetivo); o resto vale nos dois modos.
public final class SveFpCompareReduceOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int SEGMENT_BYTES = 16;
    private static final int RELATION_LESS = -1;

    private SveFpCompareReduceOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    public static boolean execute(Aarch64Core core, SveFpOp64.FpCompareReduce op) {
        if (op.op() == SveFpOp64.FpCompareReduce.Op.FADDA) {
            SvePredicateOps.requireNonStreaming(core);
        }
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        SveFloat.Env env = SveFloat.Env.of(core, op.esz());
        switch (op.op()) {
            case FCMGE, FCMGT, FCMLT, FCMLE, FCMEQ, FCMNE, FCMUO, FACGE, FACGT -> compare(core, op, env);
            case FADDA -> serialAdd(core, op, env);
            case FADDQV, FMAXNMQV, FMINNMQV, FMAXQV, FMINQV -> segmentReduce(core, op, env);
            default -> treeReduce(core, op, env);
        }
        env.commit(core);
        return false;
    }

    private static boolean active(Aarch64ScalableRegisters regs, int pg, int element, int esz) {
        int bit = element << esz;
        return ((regs.pWord(pg, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    // ── Comparação ───────────────────────────────────────────────────────────────────────────────

    private static void compare(Aarch64Core core, SveFpOp64.FpCompareReduce op, SveFloat.Env env) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        long[] pg = SvePredicateOps.read(regs, op.pg());
        long[] result = new long[pg.length];
        boolean absolute = op.op() == SveFpOp64.FpCompareReduce.Op.FACGE || op.op() == SveFpOp64.FpCompareReduce.Op.FACGT;
        boolean signaling = switch (op.op()) {
            case FCMEQ, FCMNE, FCMUO -> false;
            default -> true;
        };
        for (int e = 0; e < elements; e++) {
            int index = e << esz;
            if (!SvePredicateOps.bit(pg, index)) {
                continue;
            }
            long n = SveIntegerOps.get(regs, op.rn(), e, esz);
            long m = op.zero() ? 0L : SveIntegerOps.get(regs, op.rm(), e, esz);
            if (absolute) {
                n = env.abs(n);
                m = env.abs(m);
            }
            if (holds(op.op(), SveFloat.relation(n, m, signaling, env))) {
                SvePredicateOps.setBit(result, index, true);
            }
        }
        SvePredicateOps.write(regs, op.rd(), result);
    }

    /// A condição sobre a relação `n ? m`; um par não ordenado só satisfaz `FCMNE` e `FCMUO`.
    private static boolean holds(SveFpOp64.FpCompareReduce.Op kind, int relation) {
        boolean unordered = relation == SveFloat.RELATION_UNORDERED;
        return switch (kind) {
            case FCMGE, FACGE -> !unordered && relation >= 0;
            case FCMGT, FACGT -> !unordered && relation > 0;
            case FCMLE -> !unordered && relation <= 0;
            case FCMLT -> !unordered && relation == RELATION_LESS;
            case FCMEQ -> !unordered && relation == 0;
            case FCMNE -> unordered || relation != 0;
            default -> unordered; // FCMUO
        };
    }

    // ── Reduções ─────────────────────────────────────────────────────────────────────────────────

    /// Valor neutro (elemento inativo e completamento até potência de dois).
    private static long identity(SveFpOp64.FpCompareReduce.Op kind, SveFloat.Env env) {
        return switch (kind) {
            case FADDV, FADDQV -> env.zero(false);
            case FMINV, FMINQV -> env.infinity(false);
            case FMAXV, FMAXQV -> env.infinity(true);
            default -> env.defaultNanBits(); // FMAXNMV/FMINNMV e as QV
        };
    }

    /// Um nó da árvore: `op(baixo, alto)`.
    private static long combine(SveFpOp64.FpCompareReduce.Op kind, long low, long high, SveFloat.Env env) {
        return switch (kind) {
            case FADDV, FADDQV -> SveFloat.add(low, high, false, env);
            case FMAXNMV, FMAXNMQV -> SveFloat.maxMinNumber(low, high, true, env);
            case FMINNMV, FMINNMQV -> SveFloat.maxMinNumber(low, high, false, env);
            case FMAXV, FMAXQV -> SveFloat.maxMin(low, high, true, env);
            default -> SveFloat.maxMin(low, high, false, env); // FMINV/FMINQV
        };
    }

    /// `Reduce` recursiva do manual sobre `count` elementos (potência de dois): metade baixa, metade alta, `op`.
    private static long reduce(SveFpOp64.FpCompareReduce.Op kind, long[] data, int start, int count, SveFloat.Env env) {
        if (count == 1) {
            return data[start];
        }
        int half = count / 2;
        long low = reduce(kind, data, start, half, env);
        long high = reduce(kind, data, start + half, half, env);
        return combine(kind, low, high, env);
    }

    private static int powerOfTwoCeiling(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    private static void treeReduce(Aarch64Core core, SveFpOp64.FpCompareReduce op, SveFloat.Env env) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        long identity = identity(op.op(), env);
        long[] data = new long[powerOfTwoCeiling(elements)];
        Arrays.fill(data, identity);
        for (int e = 0; e < elements; e++) {
            if (active(regs, op.pg(), e, esz)) {
                data[e] = SveIntegerOps.get(regs, op.rn(), e, esz);
            }
        }
        core.fp().setScalar(op.rd(), esz, reduce(op.op(), data, 0, data.length, env));
    }

    private static void segmentReduce(Aarch64Core core, SveFpOp64.FpCompareReduce op, SveFloat.Env env) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int perSegment = SEGMENT_BYTES >> esz;
        int segments = core.vectorLengthBytes() / SEGMENT_BYTES;
        int bits = SveIntegerOps.elementBits(esz);
        long identity = identity(op.op(), env);
        long[] words = new long[SEGMENT_BYTES / Long.BYTES];
        for (int lane = 0; lane < perSegment; lane++) {
            long[] data = new long[powerOfTwoCeiling(segments)];
            Arrays.fill(data, identity);
            for (int s = 0; s < segments; s++) {
                int element = s * perSegment + lane;
                if (active(regs, op.pg(), element, esz)) {
                    data[s] = SveIntegerOps.get(regs, op.rn(), element, esz);
                }
            }
            int bitOffset = lane * bits;
            words[bitOffset / Long.SIZE] |= reduce(op.op(), data, 0, data.length, env) << (bitOffset % Long.SIZE);
        }
        core.fp().setQ(op.rd(), words[0], words[1]);
    }

    /// `FADDA`: soma em ordem estrita dos elementos ativos, a partir do escalar `Vdn` (elemento `0` de `Zdn`).
    private static void serialAdd(Aarch64Core core, SveFpOp64.FpCompareReduce op, SveFloat.Env env) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        int elements = core.vectorLengthBytes() >> esz;
        long accumulator = SveIntegerOps.get(regs, op.rn(), 0, esz);
        for (int e = 0; e < elements; e++) {
            if (active(regs, op.pg(), e, esz)) {
                accumulator = SveFloat.add(accumulator, SveIntegerOps.get(regs, op.rm(), e, esz), false, env);
            }
        }
        core.fp().setScalar(op.rd(), esz, accumulator);
    }
}
