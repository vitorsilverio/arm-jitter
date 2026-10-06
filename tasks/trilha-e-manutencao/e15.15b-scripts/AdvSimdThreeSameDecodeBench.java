import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.util.SplittableRandom;

/// E15.15b — tempo de decode do "three same", para A/B entre classpaths (antigo × novo), com `ARMV8_0_A`. Duas
/// misturas de 4096 palavras: o inteiro (o que virou tabela) e o FP (que agora passa pela tabela, erra, e cai na
/// cascata — mede o custo da consulta a mais até a E15.15c). Mesma técnica das E15.10–E15.15a: a op ESCAPA e a
/// rodada é método próprio.
///
/// Uso: `java -cp <classes> AdvSimdThreeSameDecodeBench.java` — imprime `ns/decode` (melhor de 15) por mistura.
public class AdvSimdThreeSameDecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// `add v.2d` · `sub v.8b` · `cmhi v.4s` · `shadd v.8h` · `uaba v.2s` · `mls v.4h` · `sqrdmulh v.8h` ·
    /// `and v.16b` · `bsl v.8b` · `addp v.2d` · `uminp v.4s` · `add d` · `sqadd b` · `uqshl h` (Rm/Rn/Rd livres).
    private static final int[] INTEGER = {0x4ee08400, 0x2e208400, 0x6ea03400, 0x4e600400, 0x2ea07c00, 0x2e609400,
            0x6e60b400, 0x4e201c00, 0x2e601c00, 0x4ee0bc00, 0x6ea0ac00, 0x5ee08400, 0x5e200c00, 0x7e604c00};
    /// `fadd v.4s` · `fmul v.2d` · `fcmeq v.2s` · `fmaxnm v.4s` · `faddp v.4s` · `fabd s` (Rm/Rn/Rd livres).
    private static final int[] FP = {0x4e20d400, 0x6e60dc00, 0x0e20e400, 0x4e20c400, 0x6e20d400, 0x7ea0d400};
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

    private static void measure(String name, Aarch64Decoder decoder, int[] templates) {
        int[] words = new int[WORDS];
        SplittableRandom random = new SplittableRandom(0xE1515BL);
        for (int i = 0; i < WORDS; i++) {
            words[i] = templates[random.nextInt(templates.length)] | (random.nextInt() & REGS_NM);
        }
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            sink += round(decoder, words);
            best = Math.min(best, (System.nanoTime() - start) / (double) (REPEATS * words.length));
        }
        System.out.printf("three same %s v8.0 %.2f ns/decode (sink %d)%n", name, best, sink & 1);
    }

    public static void main(String[] args) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        measure("inteiro", decoder, INTEGER);
        measure("fp", decoder, FP);
    }
}
