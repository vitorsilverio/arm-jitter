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

/// E15.14 — oráculo diferencial do FP escalar (`bits[30:24] = 0011110`, `bit31` = `M`/`sf` livre, e o
/// 3-source `bits[31:24] = 00011111`). Campos de opcode ENUMERADOS, registradores sorteados (semente fixa):
///
/// - `bit31`, `type` (`bits[23:22]`), `bit21`, `bits[20:16]` (`Rm`, que é `rmode:opcode` nas conversões,
///   `opcode` no 1-source e metade do `imm8` do `FMOV` imediato) e `bits[15:10]` enumerados;
/// - `Rn` ∈ {0, 31, sorteado} (`FMOV` imediato fixa `bits[9:5]=00000`);
/// - `Rd` ∈ {`00000`, `01000`, `10000`, `11000`, sorteado} (`FCMP` fixa `bits[2:0]`; `e`/`z` nos bits 4/3).
///
/// 3-source: `M` (`bit31`) × `type` × `o1` × `o0` enumerados, `Rm`/`Ra`/`Rn`/`Rd` sorteados (4 por combinação).
///
/// Três presets: `ARMv8.0-A`, todas as features menos SME, todas. Uma linha por palavra×preset; rodado
/// com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> FpScalarOracle.java <saida.txt>`
public class FpScalarOracle {
    private static final int SCALAR_FP_PREFIX = 0b001_1110 << 24;
    private static final int THREE_SOURCE_PREFIX = 0b0001_1111 << 24;
    private static final int[] RD_CHOICES = {0b00000, 0b01000, 0b10000, 0b11000};

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1514L);
        Set<Integer> words = new LinkedHashSet<>();
        for (int top = 0; top < 1 << 4; top++) {
            int high = SCALAR_FP_PREFIX | ((top >>> 3) << 31) | ((top & 0b111) << 21);
            for (int rm = 0; rm < 32; rm++) {
                for (int middle = 0; middle < 1 << 6; middle++) {
                    int base = high | (rm << 16) | (middle << 10);
                    for (int rn : new int[] {0, 31, 1 + random.nextInt(30)}) {
                        for (int rd : RD_CHOICES) {
                            words.add(base | (rn << 5) | rd);
                        }
                        words.add(base | (rn << 5) | random.nextInt(32));
                    }
                }
            }
        }
        for (int m = 0; m < 2; m++) {
            for (int type = 0; type < 4; type++) {
                for (int o1 = 0; o1 < 2; o1++) {
                    for (int o0 = 0; o0 < 2; o0++) {
                        int base = THREE_SOURCE_PREFIX | (m << 31) | (type << 22) | (o1 << 21) | (o0 << 15);
                        for (int i = 0; i < 4; i++) {
                            words.add(base | (random.nextInt(32) << 16) | (random.nextInt(32) << 10)
                                    | (random.nextInt(32) << 5) | random.nextInt(32));
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
