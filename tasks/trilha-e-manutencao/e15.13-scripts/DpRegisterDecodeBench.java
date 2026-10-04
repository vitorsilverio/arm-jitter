import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.13 — tempo de decode da classe "Data Processing — Register", para A/B entre classpaths (antigo × novo),
/// com `ARMV8_0_A`. 4096 palavras numa mistura de código real (pesos aproximados): 30% add/sub deslocado
/// (`CMP`/`NEG` incluídos), 20% logical deslocado (`MOV`/`TST`), 10% add/sub estendido, 15% `CSEL`/`CSINC`/…,
/// 10% `CCMP`/`CCMN`, 10% `MADD`/`MUL`, 5% `UDIV`/`LSLV`/`CLZ`/`REV`. Mesma técnica da E15.10–E15.12: a op
/// ESCAPA e a rodada é método próprio.
///
/// Uso: `java -cp <classes> DpRegisterDecodeBench.java` — imprime `ns/decode` (melhor de 15).
public class DpRegisterDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    private static final int[] MISC = {0x9ac00800, 0x9ac02000, 0xdac01000, 0xdac00c00};
    static Object[] escaped = new Object[WORDS];

    private static int word(SplittableRandom random) {
        int p = random.nextInt(100);
        int regs = random.nextInt(1 << 10);
        int rm = random.nextInt(32) << 16;
        int opS = random.nextInt(4) << 29;
        if (p < 30) {
            return 0x8b000000 | opS | random.nextInt(3) << 22 | rm | random.nextInt(64) << 10 | regs;
        } else if (p < 50) {
            return 0x8a000000 | opS | random.nextInt(4) << 22 | random.nextInt(2) << 21 | rm
                    | random.nextInt(64) << 10 | regs;
        } else if (p < 60) {
            return 0x8b200000 | opS | rm | random.nextInt(8) << 13 | random.nextInt(5) << 10 | regs;
        } else if (p < 75) {
            return 0x9a800000 | random.nextInt(2) << 30 | rm | random.nextInt(16) << 12 | random.nextInt(2) << 10 | regs;
        } else if (p < 85) {
            return 0xba400000 | random.nextInt(2) << 30 | rm | random.nextInt(16) << 12 | random.nextInt(2) << 11
                    | (regs & ~0x1F) | random.nextInt(16);
        } else if (p < 95) {
            return 0x9b000000 | rm | random.nextInt(2) << 15 | random.nextInt(32) << 10 | regs;
        }
        int misc = random.nextInt(MISC.length);
        // UDIV/LSLV têm Rm; CLZ/REV fixam opcode2=00000 no lugar dele.
        return MISC[misc] | (misc < 2 ? rm : 0) | regs;
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
        SplittableRandom random = new SplittableRandom(0xE1513L);
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
        System.out.printf("dp-registrador v8.0 %.2f ns/decode (%d palavras, sink %d)%n", best, words.length, sink & 1);
    }
}
