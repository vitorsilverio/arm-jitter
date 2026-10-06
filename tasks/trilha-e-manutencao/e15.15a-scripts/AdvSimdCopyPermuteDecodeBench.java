import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.15a — tempo de decode do espaço AdvSIMD `bit21=0`, para A/B entre classpaths (antigo × novo), com
/// `ARMV8_0_A`. 4096 palavras numa mistura aproximada de código real: 40% copy (`DUP`/`INS`/`UMOV`/`SMOV`), 25%
/// permute (`ZIP`/`UZP`/`TRN`), 15% `EXT`, 10% `TBL`/`TBX`, 10% SHA de três registradores. Mesma técnica das
/// E15.10–E15.14: a op ESCAPA e a rodada é método próprio.
///
/// Uso: `java -cp <classes> AdvSimdCopyPermuteDecodeBench.java` — imprime `ns/decode` (melhor de 15).
public class AdvSimdCopyPermuteDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// `dup v0.8h, v1.h[5]` · `dup v0.4s, w3` · `mov v0.b[9], w3` · `umov w0, v1.b[13]` · `smov x0, v1.s[2]` ·
    /// `mov v0.s[3], v1.s[1]` (Rn/Rd livres).
    private static final int[] COPY = {0x4e160400, 0x4e040c00, 0x4e131c00, 0x0e1b3c00, 0x4e142c00, 0x6e1c2400};
    /// `uzp2 v.8h` · `zip1 v.2d` · `trn1 v.2s` · `zip2 v.16b` (Rm/Rn/Rd livres).
    private static final int[] PERMUTE = {0x4e405800, 0x4ec03800, 0x0e802800, 0x4e007800};
    /// `ext v.16b, #13` · `ext v.8b, #5`.
    private static final int[] EXTRACT = {0x6e006800, 0x2e002800};
    /// `tbl v.16b, {3 regs}` · `tbx v.8b, {1 reg}`.
    private static final int[] TABLE = {0x4e004000, 0x0e001000};
    /// `sha256su1` · `sha1m`.
    private static final int[] SHA = {0x5e006000, 0x5e002000};
    private static final int REGS_NM = 0x1F_03FF;
    private static final int REGS_N = 0x3FF;
    static Object[] escaped = new Object[WORDS];

    private static int pick(SplittableRandom random, int[] templates, int freeRegisters) {
        return templates[random.nextInt(templates.length)] | (random.nextInt() & freeRegisters);
    }

    private static int word(SplittableRandom random) {
        int p = random.nextInt(100);
        if (p < 40) {
            return pick(random, COPY, REGS_N);
        } else if (p < 65) {
            return pick(random, PERMUTE, REGS_NM);
        } else if (p < 80) {
            return pick(random, EXTRACT, REGS_NM);
        } else if (p < 90) {
            return pick(random, TABLE, REGS_NM);
        }
        return pick(random, SHA, REGS_NM);
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
        SplittableRandom random = new SplittableRandom(0xE1515AL);
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
        System.out.printf("advsimd bit21=0 v8.0 %.2f ns/decode (%d palavras, sink %d)%n", best, words.length, sink & 1);
    }
}
