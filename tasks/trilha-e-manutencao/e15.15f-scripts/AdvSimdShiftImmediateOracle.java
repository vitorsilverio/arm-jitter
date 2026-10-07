import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.Set;

/// E15.15f — oráculo diferencial do espaço AdvSIMD de prefixo `x1111` com `bit10=1`: "shift by immediate"
/// (vetorial `01111`, escalar `11111`) e "modified immediate" (`immh=0000` do vetorial).
///
/// `bits[31:29]`, o prefixo e `bits[23:11]` (`immh:immb:opcode`, ou `a:b:c:cmode:o2` do modified immediate)
/// enumerados; `Rn`/`Rd` sorteados (4 por combinação, semente fixa — no modified immediate `Rn` é `defgh` do
/// imediato). Mesmo desenho do `e15.15d-scripts/AdvSimdBit10ZeroOracle.java`.
///
/// Uso: `java -cp <classes> AdvSimdShiftImmediateOracle.java <saida.txt>`
public class AdvSimdShiftImmediateOracle {
    private static final int[] SHIFT_PREFIXES = {0b0_1111, 0b1_1111};
    private static final int BIT10 = 1 << 10;
    private static final int REGISTER_SAMPLES = 4;

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1515FL);
        Set<Integer> words = new LinkedHashSet<>();
        for (int prefix : SHIFT_PREFIXES) {
            for (int high = 0; high < 8; high++) { // bits 31, 30, 29
                for (int middle = 0; middle < (1 << 13); middle++) { // bits 23:11
                    int base = (high << 29) | (prefix << 24) | (middle << 11) | BIT10;
                    for (int i = 0; i < REGISTER_SAMPLES; i++) {
                        words.add(base | random.nextInt(1 << 10));
                    }
                }
            }
        }
        return words.stream().mapToInt(Integer::intValue).toArray();
    }

    public static void main(String[] args) throws Exception {
        int[] words = words();
        List<Aarch64Feature> noSme = Arrays.stream(Aarch64Feature.values())
                .filter(f -> !f.name().startsWith("SME") && !f.name().startsWith("SCALABLE_MATRIX"))
                .toList();
        List<Aarch64Architecture> presets = List.of(
                Aarch64Architecture.ARMV8_0_A,
                Aarch64Architecture.of("nosme", noSme.toArray(Aarch64Feature[]::new)),
                Aarch64Architecture.of("all", Aarch64Feature.values()));
        List<String> names = List.of("v80", "nosme", "all");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            for (int p = 0; p < presets.size(); p++) {
                Aarch64Decoder decoder = new Aarch64Decoder(presets.get(p));
                for (int word : words) {
                    String result;
                    try {
                        result = String.valueOf(decoder.decode(word, 0x1000L));
                    } catch (RuntimeException e) {
                        result = e.getClass().getSimpleName();
                    }
                    out.printf("%s %08x %s%n", names.get(p), word, result);
                }
            }
        }
    }
}
