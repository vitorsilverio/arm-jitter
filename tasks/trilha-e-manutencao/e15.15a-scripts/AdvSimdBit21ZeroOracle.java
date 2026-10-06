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

/// E15.15a — oráculo diferencial do espaço AdvSIMD `bit21=0` (prefixos `01110` vetorial e `11110`
/// escalar) e do prefixo cripto `11001110` (`EOR3`/`BCAX`/`RAX1`/`XAR`/SHA-512/SM3/SM4).
///
/// AdvSIMD: `bits[31:29]`, o prefixo, `size` (`bits[23:22]`), `bits[20:16]` (`Rm` é `imm5` no copy e
/// registrador no resto, mas é ENUMERADO — E15.9c) e `bits[15:10]` enumerados; `Rn`/`Rd` sorteados
/// (2 por combinação, semente fixa). Cripto: `bits[23:21]`, `bits[20:16]` e `bits[15:10]` enumerados
/// (`Ra`/`imm6`/`imm2` moram aí), `Rn`/`Rd` sorteados.
///
/// Três presets: `ARMv8.0-A`, todas as features menos SME, todas. Uma linha por palavra×preset; rodado
/// com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> AdvSimdBit21ZeroOracle.java <saida.txt>`
public class AdvSimdBit21ZeroOracle {
    private static final int[] ADVSIMD_PREFIXES = {0b0_1110, 0b1_1110};
    private static final int CRYPTO_PREFIX = 0b1100_1110;
    private static final int REGISTER_SAMPLES = 2;

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1515AL);
        Set<Integer> words = new LinkedHashSet<>();
        for (int prefix : ADVSIMD_PREFIXES) {
            for (int high = 0; high < 8; high++) { // bits 31, 30, 29
                for (int size = 0; size < 4; size++) { // bits 23, 22 (bit21 = 0)
                    for (int rm = 0; rm < 32; rm++) {
                        for (int opcode = 0; opcode < 64; opcode++) {
                            int base = (high << 29) | (prefix << 24) | (size << 22) | (rm << 16) | (opcode << 10);
                            for (int i = 0; i < REGISTER_SAMPLES; i++) {
                                words.add(base | random.nextInt(1 << 10));
                            }
                        }
                    }
                }
            }
        }
        for (int op0 = 0; op0 < 8; op0++) {
            for (int rm = 0; rm < 32; rm++) {
                for (int opcode = 0; opcode < 64; opcode++) {
                    int base = (CRYPTO_PREFIX << 24) | (op0 << 21) | (rm << 16) | (opcode << 10);
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
