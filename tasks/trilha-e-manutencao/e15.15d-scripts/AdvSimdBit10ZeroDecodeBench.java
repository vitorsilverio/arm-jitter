import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.15d — tempo de decode do espaço AdvSIMD `bit21=1`/`bit10=0`, para A/B entre classpaths (antigo × novo), com
/// `ARMV8_0_A`. Três misturas de 4096 palavras: "three different" e reduções/AES/SHA (o que virou tabela) e o
/// two-register misc (que continua na cascata, agora depois da checagem de slot). Mesma técnica do
/// `e15.15b-scripts/AdvSimdThreeSameDecodeBench.java`: a op ESCAPA e a rodada é método próprio.
///
/// Uso: `java -cp <classes> AdvSimdBit10ZeroDecodeBench.java` — imprime `ns/decode` (melhor de 15) por mistura.
public class AdvSimdBit10ZeroDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// `saddl v.8h` · `umull2 v.4s` · `sqdmlal v.4s` · `addhn v.8b` · `pmull v.8h` · `uaddw2 v.4s` · `sqdmull s`.
    private static final int[] THREE_DIFFERENT = {0x0e200000, 0x6e60c000, 0x0e609000, 0x0e204000, 0x0e20e000,
            0x6e601000, 0x5e60d000};
    /// `saddlv h` · `addv h` · `smaxv b` · `fmaxv s` · `faddp s` · `addp d` · `aese` · `sha1h`.
    private static final int[] REDUCTIONS_CRYPTO = {0x0e303800, 0x4e71b800, 0x4e30a800, 0x6e30f800, 0x7e30d800,
            0x5ef1b800, 0x4e284800, 0x5e280800};
    /// `abs v.16b` · `cmeq v.8h, #0` · `fabs v.4s` · `sqxtn v.8b` · `fcvtzs v.4s` · `rev64 v.8b`.
    private static final int[] TWO_REGISTER_MISC = {0x4e20b800, 0x4e609800, 0x4ea0f800, 0x0e214800, 0x4ea1b800,
            0x0e200800};
    private static final int REGS_NM = 0x1F_03FF;
    private static final int REGS_N = 0x03FF;
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
        SplittableRandom random = new SplittableRandom(0xE1515DL);
        for (int i = 0; i < WORDS; i++) {
            words[i] = templates[random.nextInt(templates.length)] | (random.nextInt() & registers);
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
        measure("three different", decoder, THREE_DIFFERENT, REGS_NM);
        measure("reducoes/cripto", decoder, REDUCTIONS_CRYPTO, REGS_N);
        measure("two-register misc", decoder, TWO_REGISTER_MISC, REGS_N);
    }
}
