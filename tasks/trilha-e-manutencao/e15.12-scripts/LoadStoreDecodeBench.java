import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.12 — tempo de decode da classe Loads and Stores, para A/B entre classpaths (antigo × novo), com
/// `ARMV8_0_A`. 4096 palavras numa mistura de código real (pesos aproximados): 35% `LDR`/`STR` imm12 (`W`/`X`),
/// 15% `LDP`/`STP`, 10% `LDUR`/`STUR`/pré/pós-índice, 10% registrador, 10% literal, 5% `LDXR`/`STXR`, 5%
/// `LDAR`/`STLR`, 5% `LDR`/`STR` `Q`, 5% `LD1`/`ST1`. Mesma técnica da E15.10/E15.11: a op ESCAPA e a rodada é
/// método próprio.
///
/// Uso: `java -cp <classes> LoadStoreDecodeBench.java` — imprime `ns/decode` (melhor de 15).
public class LoadStoreDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    static Object[] escaped = new Object[WORDS];

    private static int word(SplittableRandom random) {
        int p = random.nextInt(100);
        int regs = random.nextInt(1 << 10);
        int load = random.nextInt(2) << 22;
        if (p < 35) {
            return (random.nextBoolean() ? 0xb9000000 : 0xf9000000) | load | random.nextInt(1 << 12) << 10 | regs;
        } else if (p < 50) {
            return (random.nextBoolean() ? 0x28000000 : 0xa8000000) | (random.nextInt(3) + 1) << 23 | load
                    | random.nextInt(1 << 12) << 10 | regs;
        } else if (p < 60) {
            int[] idx = {0, 1, 3};
            return 0xf8000000 | load | random.nextInt(1 << 9) << 12 | idx[random.nextInt(3)] << 10 | regs;
        } else if (p < 70) {
            return 0xf8206800 | load | random.nextInt(31) << 16 | regs;
        } else if (p < 80) {
            return 0x58000000 | random.nextInt(1 << 19) << 5 | regs & 31;
        } else if (p < 85) {
            return random.nextBoolean() ? 0xc85f7c00 | regs : 0xc8007c00 | random.nextInt(31) << 16 | regs;
        } else if (p < 90) {
            return (random.nextBoolean() ? 0xc8dffc00 : 0xc89ffc00) | regs;
        } else if (p < 95) {
            return 0x3d800000 | load | random.nextInt(1 << 12) << 10 | regs;
        }
        return 0x4c007000 | load | regs;
    }

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

    public static void main(String[] args) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        int[] words = new int[WORDS];
        SplittableRandom random = new SplittableRandom(0xE1512L);
        for (int i = 0; i < WORDS; i++) {
            words[i] = word(random);
        }
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            sink += round(decoder, words);
            best = Math.min(best, (System.nanoTime() - start) / (double) (REPEATS * words.length));
        }
        System.out.printf("load/store v8.0 %.2f ns/decode (%d palavras, sink %d)%n", best, words.length, sink & 1);
    }
}
