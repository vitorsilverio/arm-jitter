import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.util.SplittableRandom;

/// E15.11 — tempo de decode da classe branch/exceção/sistema, para A/B entre classpaths (antigo × novo),
/// com `ARMV8_0_A`. 4096 palavras numa mistura de código real (pesos aproximados): 30% `B.cond`, 15% `B`,
/// 15% `BL`, 10% `CBZ`/`CBNZ`, 10% `TBZ`/`TBNZ`, 10% `RET`/`BR`/`BLR`, 5% `MRS`/`MSR` (`TPIDR_EL0`/`NZCV`/
/// `FPCR`), 5% `NOP`/`DMB`/`ISB`. Mesma técnica da E15.10: a op ESCAPA e a rodada é método próprio.
///
/// Uso: `java -cp <classes> BranchDecodeBench.java` — imprime `ns/decode` (melhor de 15).
public class BranchDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    static Object[] escaped = new Object[WORDS];

    private static final class OneWord implements AddressSpace64 {
        int word;
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return word; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    private static final int[] SYSTEM = {
            0xd53bd040, 0xd51bd040, 0xd53b4200, 0xd51b4200, 0xd53b4400, // mrs/msr tpidr_el0, nzcv, fpcr
            0xd503201f, 0xd5033bbf, 0xd5033fdf}; // nop, dmb ish, isb

    private static int word(SplittableRandom random) {
        int p = random.nextInt(100);
        int r = random.nextInt();
        if (p < 30) {
            return 0x54000000 | (r & 0x00FFFFE0) | random.nextInt(15);   // b.cond (cond≠1111 não importa)
        } else if (p < 45) {
            return 0x14000000 | (r & 0x03FFFFFF);                         // b
        } else if (p < 60) {
            return 0x94000000 | (r & 0x03FFFFFF);                         // bl
        } else if (p < 70) {
            return 0x34000000 | (r & 0x81FFFFFF);                         // cbz/cbnz
        } else if (p < 80) {
            return 0x36000000 | (r & 0x81FFFFFF);                         // tbz/tbnz
        } else if (p < 90) {
            int[] opc = {0xd61f0000, 0xd63f0000, 0xd65f0000};             // br, blr, ret
            return opc[random.nextInt(3)] | random.nextInt(31) << 5;
        }
        return SYSTEM[random.nextInt(SYSTEM.length)];
    }

    private static long round(Aarch64Decoder decoder, OneWord memory, int[] words) {
        long sink = 0;
        for (int r = 0; r < REPEATS; r++) {
            for (int i = 0; i < words.length; i++) {
                memory.word = words[i];
                Object op = decoder.decode(memory, 0);
                escaped[i] = op;
                sink += op.hashCode();
            }
        }
        return sink;
    }

    public static void main(String[] args) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        OneWord memory = new OneWord();
        int[] words = new int[WORDS];
        SplittableRandom random = new SplittableRandom(0xBE11L);
        for (int i = 0; i < WORDS; i++) {
            words[i] = word(random);
        }
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            sink += round(decoder, memory, words);
            best = Math.min(best, (System.nanoTime() - start) / (double) (REPEATS * words.length));
        }
        System.out.printf("branch/sistema v8.0 %.2f ns/decode (%d palavras, sink %d)%n", best, words.length, sink & 1);
    }
}
