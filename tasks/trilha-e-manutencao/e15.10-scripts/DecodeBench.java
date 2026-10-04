import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.util.SplittableRandom;

/// E15.10 — tempo de decode da classe DP-imediato (`bits[28:26]=100`), para A/B entre classpaths
/// (antigo × novo), com `ARMV8_0_A`. 4096 palavras aceitas pelas duas versões (sorteadas no espaço,
/// fora as 3 famílias do resíduo G8 que mudam de destino). Palavras que lançam ficam de fora (o custo
/// da exceção dominaria a medida).
///
/// Uso: `java -cp <classes> DecodeBench.java` — imprime `ns/decode` (melhor de 15).
public class DecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// A op decodificada ESCAPA (como no lifter, que a guarda no bloco): com só `hashCode()` o C2
    /// elimina a alocação do record no caminho antigo e não no novo, e o A/B mede escape analysis.
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

    /// As 3 famílias corrigidas pela E15.10 (aceitas antes, recusadas depois).
    private static boolean g8Residue(int word) {
        boolean narrow = (word >>> 31) == 0;
        int op0 = (word >>> 23) & 0b111;
        int opc = (word >>> 29) & 0b11;
        return switch (op0) {
            case 0b101 -> narrow && ((word >>> 22) & 1) != 0;
            case 0b110 -> narrow && (((word >>> 21) & 1) != 0 || ((word >>> 15) & 1) != 0);
            case 0b111 -> opc != 0;
            default -> false;
        };
    }

    /// Uma rodada num método próprio: compilado inteiro pelo C2, sem depender de OSR do `main`.
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
        SplittableRandom random = new SplittableRandom(0xBE10L);
        for (int found = 0; found < WORDS; ) {
            int word = (random.nextInt() & ~(0b111 << 26)) | (0b100 << 26);
            if (g8Residue(word)) {
                continue;
            }
            memory.word = word;
            try {
                decoder.decode(memory, 0);
            } catch (RuntimeException e) {
                continue;
            }
            words[found++] = word;
        }
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            sink += round(decoder, memory, words);
            best = Math.min(best, (System.nanoTime() - start) / (double) (REPEATS * words.length));
        }
        System.out.printf("dp-imediato v8.0 %.2f ns/decode (%d palavras, sink %d)%n", best, words.length, sink & 1);
    }
}
