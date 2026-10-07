import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.15f — tempo de decode do "shift by immediate" e do "modified immediate", para A/B entre classpaths (antigo ×
/// novo), com `ARMV8_0_A`. Mais duas misturas de controle de outras famílias da `advSimdTable` (para ver se as linhas
/// novas pesam nos baldes delas). Mesma técnica do `e15.15d-scripts/AdvSimdBit10ZeroDecodeBench.java`: a op ESCAPA e
/// a rodada é método próprio.
///
/// Uso: `java -cp <classes> AdvSimdShiftImmediateDecodeBench.java` — imprime `ns/decode` (melhor de 15) por mistura.
public class AdvSimdShiftImmediateDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// `sshr v.8b` · `ushr v.2d` · `shl v.4h` · `sqshl s` · `shrn v.8b` · `sqshrn s` · `sshll v.8h` · `scvtf v.4s` ·
    /// `urshr d`.
    private static final int[] SHIFT = {0x0f0d0420, 0x6f400420, 0x0f1f5420, 0x5f3f7420, 0x0f088420, 0x5f209420,
            0x0f08a420, 0x4f3de420, 0x7f7b2420};
    /// `movi v.2s, lsl` · `mvni v.4h` · `orr v.4s` · `bic v.8h` · `movi v.16b` · `movi d` · `fmov v.4s`.
    private static final int[] MODIFIED_IMMEDIATE = {0x0f052560, 0x2f00a640, 0x4f015680, 0x6f0296c0, 0x4f04e740,
            0x2f05e540, 0x4f03f600};
    /// `add v.16b` · `cmeq v.8h` · `fadd v.4s` · `and v.16b` (three same).
    private static final int[] THREE_SAME = {0x4e208400, 0x6e608c00, 0x4e20d400, 0x4e201c00};
    /// `abs v.16b` · `cmeq v.8h, #0` · `fabs v.4s` · `sqxtn v.8b` · `fcvtzs v.4s` · `rev64 v.8b`.
    private static final int[] TWO_REGISTER_MISC = {0x4e20b800, 0x4e609800, 0x4ea0f800, 0x0e214800, 0x4ea1b800,
            0x0e200800};
    private static final int REGS_N = 0x03FF;
    private static final int REGS_D = 0x001F;
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
        SplittableRandom random = new SplittableRandom(0xE1515FL);
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
        measure("shift by immediate", decoder, SHIFT, REGS_N);
        measure("modified immediate", decoder, MODIFIED_IMMEDIATE, REGS_D);
        measure("three same", decoder, THREE_SAME, REGS_NM);
        measure("two-register misc", decoder, TWO_REGISTER_MISC, REGS_N);
    }
}
