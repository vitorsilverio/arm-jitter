import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.Set;

/// E15.11 — oráculo diferencial da classe "Branches, Exception Generating and System instructions"
/// (`bits[28:26]=101`). Campos de opcode ENUMERADOS, imediatos/registradores sorteados (semente fixa):
///
/// - `classe`: bits 31:29 e 25:12 enumerados, 11:0 sorteados (B/BL/B.cond/CBZ/TBZ/CB<cc> e o topo de
///   todos os subgrupos);
/// - `sistema`: `0xD5000000 | bits[21:5]` enumerados × `Rt` ∈ {31, sorteado} (barreiras, hints,
///   `MSR` imediato, `SYS`/`SYSL`, `MRS`/`MSR`);
/// - `excecao`: `0xD4000000 | opc(23:21) | bits[4:0]` enumerados × 16 `imm16` sorteados;
/// - `branch-reg`: `0xD6000000 | bits[24:10]` enumerados × `Rn` ∈ {31, sorteado} × `op4` ∈ {0, 31,
///   sorteado}.
///
/// Três presets: `ARMv8.0-A`, todas as features menos SME, todas. Uma linha por palavra×preset;
/// rodado com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> BranchSystemOracle.java <saida.txt>`
public class BranchSystemOracle {
    private static final int CLASS_BITS = 0b101 << 26;

    /// Memória de uma palavra só: toda leitura de 32 bits devolve a instrução sob teste.
    private record OneWord(int word) implements AddressSpace64 {
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return word; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    static int[] words() {
        SplittableRandom random = new SplittableRandom(0xE1511L);
        Set<Integer> words = new LinkedHashSet<>();
        for (int i = 0; i < 1 << 17; i++) {
            int high = i >>> 14;
            int middle = i & 0x3FFF;
            words.add((high << 29) | CLASS_BITS | (middle << 12) | random.nextInt(1 << 12));
        }
        for (int i = 0; i < 1 << 17; i++) {
            int base = 0xD5000000 | (i << 5);
            words.add(base | 31);
            words.add(base | random.nextInt(31));
        }
        for (int opc = 0; opc < 8; opc++) {
            for (int low = 0; low < 32; low++) {
                for (int k = 0; k < 16; k++) {
                    words.add(0xD4000000 | (opc << 21) | (random.nextInt(1 << 16) << 5) | low);
                }
            }
        }
        for (int i = 0; i < 1 << 15; i++) {
            int base = 0xD6000000 | (i << 10);
            for (int rn : new int[] {31, random.nextInt(31)}) {
                for (int op4 : new int[] {0, 31, 1 + random.nextInt(30)}) {
                    words.add(base | (rn << 5) | op4);
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
                        result = String.valueOf(decoder.decode(new OneWord(word), 0x1000L));
                    } catch (RuntimeException e) {
                        result = e.getClass().getSimpleName();
                    }
                    out.printf("%s %08x %s%n", names.get(p), word, result);
                }
            }
        }
    }
}
