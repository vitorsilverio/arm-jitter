import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/// E15.9 — tempo de decode do subespaço AdvSIMD `bit21=0`, para A/B entre classpaths (antigo ×
/// novo). Duas cargas, cada uma com o preset das 8 features do piloto e com `ARMV8_0_A`:
///
/// - `table`: palavras que caem numa linha da tabela (antes: num dos 10 sub-decoders);
/// - `miss`: palavras que passam pela tabela sem casar e terminam em EXT/permute/TBL/copy/SHA.
///
/// Palavras que lançam exceção ficam de fora (o custo da exceção dominaria a medida), assim como
/// as do bug G8 do FP16 (`bit14=1`), que mudam de destino entre as versões.
///
/// Uso: `java -cp <classes> DecodeBench.java` — imprime `carga preset ns/decode` (melhor de 15).
public class DecodeBench {
    private static final Aarch64Feature[] PILOT = {
            Aarch64Feature.RDM, Aarch64Feature.FP16, Aarch64Feature.FP8,
            Aarch64Feature.FP_ABSOLUTE_MAX_MIN, Aarch64Feature.FP8_FUSED_MULTIPLY_ADD,
            Aarch64Feature.FP8_DOT_PRODUCT_2WAY, Aarch64Feature.FP8_DOT_PRODUCT_4WAY,
            Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC
    };
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;

    private static final class OneWord implements AddressSpace64 {
        int word;
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return word; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    private static boolean fp16Bit14Bug(int word) {
        return ((word >>> 21) & 1) == 0 && ((word >>> 22) & 1) == 1 && ((word >>> 10) & 1) == 1
                && ((word >>> 14) & 0b11) == 0b01;
    }

    private static boolean isPilotOp(Object op) {
        String name = op.getClass().getSimpleName();
        return switch (name) {
            case "FpArithmeticThreeSame", "FpConvertToFp8", "FpScaleByInt", "FpAbsoluteMaxMin",
                 "Fp8FusedMultiplyAddLong", "Fp8DotProduct", "FpComplexMultiplyAccumulate", "FpComplexAdd",
                 "ArithmeticThreeSame" -> true; // em bit21=0, ArithmeticThreeSame só vem do RDM
            default -> false;
        };
    }

    public static void main(String[] args) {
        Aarch64Decoder pilot = new Aarch64Decoder(Aarch64Architecture.of("piloto", PILOT));
        Aarch64Decoder base = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        OneWord memory = new OneWord();
        List<Integer> tableWords = new ArrayList<>();
        List<Integer> missWords = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(0xBE9CL);
        for (int i = 0; i < 2_000_000 && (tableWords.size() < 4096 || missWords.size() < 4096); i++) {
            int prefix = random.nextBoolean() ? 0b0_1110 : 0b1_1110;
            int word = (random.nextInt() & ~(0b1_1111 << 24) & ~(1 << 21) & ~(1 << 31)) | (prefix << 24);
            if (fp16Bit14Bug(word)) {
                continue;
            }
            memory.word = word;
            Object op;
            try {
                op = pilot.decode(memory, 0);
            } catch (RuntimeException e) {
                continue;
            }
            if (isPilotOp(op)) {
                if (tableWords.size() < 4096) {
                    tableWords.add(word);
                }
            } else if (missWords.size() < 4096) {
                missWords.add(word);
            }
        }
        for (String load : List.of("table", "miss")) {
            int[] words = (load.equals("table") ? tableWords : missWords).stream().mapToInt(Integer::intValue).toArray();
            for (Aarch64Decoder decoder : List.of(pilot, base)) {
                String preset = decoder == pilot ? "piloto" : "v8.0";
                if (decoder == base && load.equals("table")) {
                    continue; // sem as features, estas palavras lançam
                }
                double best = Double.MAX_VALUE;
                long sink = 0;
                for (int round = 0; round < ROUNDS; round++) {
                    long start = System.nanoTime();
                    for (int r = 0; r < REPEATS; r++) {
                        for (int word : words) {
                            memory.word = word;
                            sink += decoder.decode(memory, 0).hashCode();
                        }
                    }
                    double ns = (System.nanoTime() - start) / (double) (REPEATS * words.length);
                    best = Math.min(best, ns);
                }
                System.out.printf("%s %s %.2f ns/decode (%d palavras, sink %d)%n", load, preset, best, words.length, sink & 1);
            }
        }
    }
}
