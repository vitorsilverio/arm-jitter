import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/// E15.10 — oráculo diferencial da classe "Data Processing — Immediate" (`bits[28:26]=100`): os
/// bits 31:29 e 25:10 ENUMERADOS (todos os campos de opcode e de imediato que podem ser reservados:
/// `sf`/`opc`/`N`/`immr`/`imms`/`hw`/`sh`/`o0`) e `Rn`/`Rd` sorteados (semente fixa) — 524 288
/// palavras. Roda sob o preset base `ARMv8.0-A` e sob "todas as features menos SME" (`MTE` e `CSSC`
/// moram no mesmo espaço). Uma linha por palavra×preset; rodado com o classpath antigo e com o novo,
/// o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> DpImmediateOracle.java <saida.txt>`
public class DpImmediateOracle {
    private static final int CLASS_BITS = 0b100 << 26;

    /// Memória de uma palavra só: toda leitura de 32 bits devolve a instrução sob teste.
    private record OneWord(int word) implements AddressSpace64 {
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return word; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    public static void main(String[] args) throws Exception {
        SplittableRandom random = new SplittableRandom(0xE1510L);
        int[] words = new int[1 << 19];
        for (int i = 0; i < words.length; i++) {
            int high = i >>> 16;           // bits 31:29
            int middle = i & 0xFFFF;       // bits 25:10
            int regs = random.nextInt(1 << 10);
            words[i] = (high << 29) | CLASS_BITS | (middle << 10) | regs;
        }
        List<Aarch64Architecture> presets = List.of(
                Aarch64Architecture.ARMV8_0_A,
                Aarch64Architecture.of("all", Arrays.stream(Aarch64Feature.values())
                        .filter(f -> !f.name().startsWith("SME") && !f.name().startsWith("SCALABLE_MATRIX"))
                        .toArray(Aarch64Feature[]::new)));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            for (Aarch64Architecture preset : presets) {
                Aarch64Decoder decoder = new Aarch64Decoder(preset);
                String name = preset == Aarch64Architecture.ARMV8_0_A ? "v80" : "all";
                for (int word : words) {
                    String result;
                    try {
                        result = String.valueOf(decoder.decode(new OneWord(word), 0x1000L));
                    } catch (RuntimeException e) {
                        result = e.getClass().getSimpleName();
                    }
                    out.printf("%s %08x %s%n", name, word, result);
                }
            }
        }
    }
}
