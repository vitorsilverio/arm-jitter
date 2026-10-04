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

/// E15.12 — oráculo diferencial da classe "Loads and Stores" (`op0 = x1x0`: `bit27=1`, `bit25=0`).
/// Campos de opcode ENUMERADOS, imediatos sorteados (semente fixa):
///
/// - bits 31:28, 26, 24:21 (os 9 bits livres do topo da classe) × bits 15:10 (`option`/`S`/`idx`,
///   `o3`/`opc` atômico, `Rt2`, `opcode`/`size` do AdvSIMD) enumerados;
/// - bits 20:16 (`Rm`/`Rs`/`imm9` alto) ∈ {0, 31, sorteado};
/// - `Rt` ∈ {31, par sorteado, ímpar sorteado} (`Rt=31` é campo fixo de `GCSSTR`/`LDAPR`; `CASP` exige
///   `Rt` par); `Rn` sorteado.
///
/// Três presets: `ARMv8.0-A`, todas as features menos SME, todas. Uma linha por palavra×preset;
/// rodado com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> LoadStoreOracle.java <saida.txt>`
public class LoadStoreOracle {
    private static final int CLASS_BITS = 1 << 27;
    private static final int[] FREE_TOP_BITS = {31, 30, 29, 28, 26, 24, 23, 22, 21};

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1512L);
        Set<Integer> words = new LinkedHashSet<>();
        for (int top = 0; top < 1 << FREE_TOP_BITS.length; top++) {
            int high = CLASS_BITS;
            for (int i = 0; i < FREE_TOP_BITS.length; i++) {
                if (((top >>> i) & 1) != 0) {
                    high |= 1 << FREE_TOP_BITS[i];
                }
            }
            for (int middle = 0; middle < 1 << 6; middle++) {
                int base = high | (middle << 10);
                for (int rm : new int[] {0, 31, 1 + random.nextInt(30)}) {
                    for (int rt : new int[] {31, random.nextInt(15) * 2, random.nextInt(15) * 2 + 1}) {
                        words.add(base | (rm << 16) | (random.nextInt(32) << 5) | rt);
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
