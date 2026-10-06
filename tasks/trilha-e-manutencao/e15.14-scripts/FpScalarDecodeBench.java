import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.14 — tempo de decode do FP escalar, para A/B entre classpaths (antigo × novo), com `ARMV8_0_A`. 4096
/// palavras numa mistura de código FP real (pesos aproximados): 35% 2-source (`FADD`/`FMUL`/…), 15% 3-source
/// (`FMADD`/…), 15% 1-source (`FMOV`/`FABS`/`FNEG`/`FSQRT`/`FCVT`), 10% `FCMP`, 10% conversões inteiro
/// (`SCVTF`/`FCVTZS`/`FMOV` geral), 10% `FCSEL`, 5% `FMOV` imediato. Mesma técnica da E15.10–E15.13: a op
/// ESCAPA e a rodada é método próprio.
///
/// Uso: `java -cp <classes> FpScalarDecodeBench.java` — imprime `ns/decode` (melhor de 15).
public class FpScalarDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// `FMUL` `FDIV` `FADD` `FSUB` `FMAX` `FMIN` `FMAXNM` `FMINNM` `FNMUL` (`bits[15:12]`).
    private static final int[] TWO_SOURCE = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    /// `FMOV` `FABS` `FNEG` `FSQRT` (`opcode` do 1-source).
    private static final int[] ONE_SOURCE = {0, 1, 2, 3};
    /// `SCVTF` `UCVTF` `FCVTZS` `FCVTZU` `FMOV` geral (`rmode:opcode`).
    private static final int[] INT_CONVERT = {0b00010, 0b00011, 0b11000, 0b11001};
    static Object[] escaped = new Object[WORDS];

    private static int word(SplittableRandom random) {
        int p = random.nextInt(100);
        int regs = random.nextInt(1 << 10);
        int rm = random.nextInt(32) << 16;
        int type = random.nextInt(2) << 22;
        if (p < 35) {
            return 0x1e200800 | type | rm | TWO_SOURCE[random.nextInt(TWO_SOURCE.length)] << 12 | regs;
        } else if (p < 50) {
            return 0x1f000000 | type | random.nextInt(2) << 21 | rm | random.nextInt(2) << 15
                    | random.nextInt(32) << 10 | regs;
        } else if (p < 65) {
            if (random.nextInt(5) == 0) {
                // FCVT s↔d: type é a fonte, opcode o destino
                return random.nextBoolean() ? 0x1e22c000 | regs : 0x1e624000 | regs;
            }
            return 0x1e204000 | type | ONE_SOURCE[random.nextInt(ONE_SOURCE.length)] << 15 | regs;
        } else if (p < 75) {
            return 0x1e202000 | type | rm | (regs & ~0x1F) | random.nextInt(4) << 3;
        } else if (p < 85) {
            int sf = random.nextInt(2);
            if (random.nextInt(4) == 0) {
                // FMOV W↔S / X↔D
                return 0x1e260000 | sf << 31 | sf << 22 | random.nextInt(2) << 16 | regs;
            }
            return 0x1e200000 | sf << 31 | type | INT_CONVERT[random.nextInt(INT_CONVERT.length)] << 16 | regs;
        } else if (p < 95) {
            return 0x1e200c00 | type | rm | random.nextInt(16) << 12 | regs;
        }
        return 0x1e201000 | type | random.nextInt(256) << 13 | random.nextInt(32);
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
        SplittableRandom random = new SplittableRandom(0xE1514L);
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
        System.out.printf("fp-escalar v8.0 %.2f ns/decode (%d palavras, sink %d)%n", best, words.length, sink & 1);
    }
}
