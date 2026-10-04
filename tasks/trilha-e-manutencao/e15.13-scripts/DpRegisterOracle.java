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

/// E15.13 — oráculo diferencial da classe "Data Processing — Register" inteira (`op0 = x101`:
/// `bits[27:25]=101`, `bit26=0` — o lado `bit26=1` é FP/AdvSIMD, E15.14/E15.15). Campos de opcode
/// ENUMERADOS, registradores sorteados (semente fixa):
///
/// - bits 31:28 e 24:21 (os 8 bits livres do topo da classe) × bits 15:10 (`opcode`/`sa`/`cond`/`imm6`)
///   enumerados;
/// - bits 20:16 (`Rm`, que é `opcode2` no 1-source/`SETF` e `imm6` no `RMIF`) ∈ {0, 1, 31, sorteado};
/// - `Rn` ∈ {31, sorteado} (`XPAC*`/`PAC*Z` exigem `Rn=31`);
/// - `Rd` ∈ {`01101`, 31, sorteado} (`SETF8`/`SETF16` fixam `01101`; `CCMP`/`RMIF` fixam `bit4=0`).
///
/// Três presets: `ARMv8.0-A`, todas as features menos SME, todas. Uma linha por palavra×preset;
/// rodado com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> DpRegisterOracle.java <saida.txt>`
public class DpRegisterOracle {
    private static final int CLASS_BITS = 0b101 << 25;
    private static final int[] FREE_TOP_BITS = {31, 30, 29, 28, 24, 23, 22, 21};
    private static final int SETF_RD = 0b01101;

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1513L);
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
                for (int rm : new int[] {0, 1, 31, 2 + random.nextInt(29)}) {
                    for (int rn : new int[] {31, random.nextInt(31)}) {
                        for (int rd : new int[] {SETF_RD, 31, random.nextInt(31)}) {
                            words.add(base | (rm << 16) | (rn << 5) | rd);
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
