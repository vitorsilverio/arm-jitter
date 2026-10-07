import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.15g — tempo de decode do "× indexed element", para A/B entre classpaths (antigo × novo), com `ARMV8_0_A`. Mais
/// três misturas de controle de outras famílias da `advSimdTable` (o roteamento do AdvSIMD virou só a tabela). Mesma
/// técnica do `e15.15f-scripts/AdvSimdShiftImmediateDecodeBench.java`: a op ESCAPA e a rodada é método próprio.
///
/// Uso: `java -cp <classes> AdvSimdIndexedElementDecodeBench.java` — imprime `ns/decode` (melhor de 15) por mistura.
public class AdvSimdIndexedElementDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// `fmla v.4s` · `fmls d` · `mul v.4h` · `umlsl2 v.4s` · `sqdmull d` · `sqdmlal s` · `fmul v.2d`.
    private static final int[] INDEXED = {0x4fbf1820, 0x5fd15820, 0x0f5f8820, 0x6f636820, 0x5fbfb020, 0x5f7f3820,
            0x4fc19020};
    /// `sshr v.8b` · `ushr v.2d` · `shl v.4h` · `sqshl s` · `shrn v.8b` · `sqshrn s` · `sshll v.8h` · `scvtf v.4s`.
    private static final int[] SHIFT = {0x0f0d0420, 0x6f400420, 0x0f1f5420, 0x5f3f7420, 0x0f088420, 0x5f209420,
            0x0f08a420, 0x4f3de420};
    /// `add v.16b` · `cmeq v.8h` · `fadd v.4s` · `and v.16b` (three same).
    private static final int[] THREE_SAME = {0x4e208400, 0x6e608c00, 0x4e20d400, 0x4e201c00};
    /// `abs v.16b` · `cmeq v.8h, #0` · `fabs v.4s` · `sqxtn v.8b` · `fcvtzs v.4s` · `rev64 v.8b`.
    private static final int[] TWO_REGISTER_MISC = {0x4e20b800, 0x4e609800, 0x4ea0f800, 0x0e214800, 0x4ea1b800,
            0x0e200800};
    private static final int REGS_N = 0x03FF;
    private static final int REGS_NM = 0x1F_03FF;
    static Object[] escaped = new Object[WORDS];

    private static long round(Aarch64Decoder decoder, int[] words) {
        long sink = 0;
        for (int r = 0; r < REPEATS; r++) {
            for (int i = 0; i < words.length; i++) {
                Object op = decoder.decode(words[i], 0);
                escaped[i] = op;
                sink += op.hashCode();
            }
        }
        return sink;
    }

    private static void measure(String name, Aarch64Decoder decoder, int[] templates, int registers) {
        int[] words = new int[WORDS];
        SplittableRandom random = new SplittableRandom(0xE1515AL);
        for (int i = 0; i < WORDS; i++) {
            words[i] = templates[random.nextInt(templates.length)] & ~registers | (random.nextInt() & registers);
        }
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            sink += round(decoder, words);
            best = Math.min(best, (System.nanoTime() - start) / (double) (REPEATS * words.length));
        }
        System.out.printf("%s v8.0 %.2f ns/decode (sink %d)%n", name, best, sink & 1);
    }

    public static void main(String[] args) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        measure("indexed element", decoder, INDEXED, REGS_N);
        measure("shift by immediate", decoder, SHIFT, REGS_N);
        measure("three same", decoder, THREE_SAME, REGS_NM);
        measure("two-register misc", decoder, TWO_REGISTER_MISC, REGS_N);
    }
}
