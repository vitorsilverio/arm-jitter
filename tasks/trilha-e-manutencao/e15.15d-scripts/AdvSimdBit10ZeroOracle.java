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

/// E15.15d — oráculo diferencial do espaço AdvSIMD `bit21=1`, `bit10=0` dos prefixos `01110` (vetorial) e
/// `11110` (escalar): "three different" (`bit11=0`), across lanes, scalar pairwise, AES/SHA de dois
/// registradores e two-register misc (`bit11=1`, este fica no decoder até a E15.15e, mas mora no mesmo
/// espaço: o diff tem que ser vazio nele também).
///
/// `bits[31:29]`, o prefixo, `size` (`bits[23:22]`), `Rm` (`bits[20:16]`, ENUMERADO — E15.9c) e
/// `bits[15:11]` (o `opcode` de 4 bits mais o `bit11`) enumerados; `Rn`/`Rd` sorteados (2 por combinação,
/// semente fixa). Mesmo desenho do `e15.15b-scripts/AdvSimdThreeSameOracle.java`, com `bit10=0`.
///
/// Três presets: `ARMv8.0-A`, todas as features menos SME, todas. Uma linha por palavra×preset; rodado
/// com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> AdvSimdBit10ZeroOracle.java <saida.txt>`
public class AdvSimdBit10ZeroOracle {
    private static final int[] ADVSIMD_PREFIXES = {0b0_1110, 0b1_1110};
    private static final int BIT21 = 1 << 21;
    private static final int BIT10 = 1 << 10;
    private static final int REGISTER_SAMPLES = 2;

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1515DL);
        Set<Integer> words = new LinkedHashSet<>();
        for (int prefix : ADVSIMD_PREFIXES) {
            for (int high = 0; high < 8; high++) { // bits 31, 30, 29
                for (int size = 0; size < 4; size++) { // bits 23, 22
                    for (int rm = 0; rm < 32; rm++) {
                        for (int opcode = 0; opcode < 32; opcode++) { // bits 15:11
                            int base = (high << 29) | (prefix << 24) | (size << 22) | BIT21 | (rm << 16)
                                    | (opcode << 11);
                            for (int i = 0; i < REGISTER_SAMPLES; i++) {
                                words.add(base | random.nextInt(1 << 10) & ~BIT10);
                            }
                        }
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
