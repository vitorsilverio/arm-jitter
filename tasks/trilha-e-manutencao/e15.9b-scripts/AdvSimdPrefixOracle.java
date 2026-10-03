import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/// E15.9b — oráculo diferencial do espaço que `Aarch64Decoder#decodeAdvancedSimdInteger` recebe:
/// os 4 prefixos de bits[28:24] (`01110`/`11110` e os de shift/indexed `01111`/`11111`), com os
/// bits 31, 30, 29, 23, 22, 21 e 15:10 enumerados e `Rd`/`Rn`/`Rm` sorteados (semente fixa). Roda
/// sob os 256 subconjuntos das 8 features da tabela `bit21=0` da E15.9 e sob "todas as features
/// menos SME" (SME embrulha AdvSIMD em `StreamingRestricted`). Uma linha por palavra×preset;
/// rodado com o classpath antigo e com o novo, o `diff` mostra exatamente o que a task mudou.
///
/// Uso: `java -cp <classes> AdvSimdPrefixOracle.java <saida.txt>`
public class AdvSimdPrefixOracle {
    private static final Aarch64Feature[] FEATURES = {
            Aarch64Feature.RDM, Aarch64Feature.FP16, Aarch64Feature.FP8,
            Aarch64Feature.FP_ABSOLUTE_MAX_MIN, Aarch64Feature.FP8_FUSED_MULTIPLY_ADD,
            Aarch64Feature.FP8_DOT_PRODUCT_2WAY, Aarch64Feature.FP8_DOT_PRODUCT_4WAY,
            Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC
    };
    private static final int[] PREFIXES = {0b0_1110, 0b1_1110, 0b0_1111, 0b1_1111};
    private static final int REGISTER_SAMPLES = 3;

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
        List<Integer> words = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(0xE159BL);
        for (int prefix : PREFIXES) {
            for (int high = 0; high < 8; high++) { // bits 31, 30, 29
                for (int size = 0; size < 8; size++) { // bits 23, 22, 21
                    for (int opcode = 0; opcode < 64; opcode++) { // bits 15:10
                        for (int sample = 0; sample < REGISTER_SAMPLES; sample++) {
                            int regs = random.nextInt(1 << 15);
                            int rm = regs >>> 10;
                            int rn = (regs >>> 5) & 0x1F;
                            int rd = regs & 0x1F;
                            words.add((high << 29) | (prefix << 24) | (size << 21) | (rm << 16)
                                    | (opcode << 10) | (rn << 5) | rd);
                        }
                    }
                }
            }
        }
        List<Aarch64Architecture> presets = new ArrayList<>();
        for (int subset = 0; subset < (1 << FEATURES.length); subset++) {
            List<Aarch64Feature> enabled = new ArrayList<>();
            for (int i = 0; i < FEATURES.length; i++) {
                if ((subset & (1 << i)) != 0) {
                    enabled.add(FEATURES[i]);
                }
            }
            presets.add(Aarch64Architecture.of(String.format("s%02x", subset), enabled.toArray(Aarch64Feature[]::new)));
        }
        presets.add(Aarch64Architecture.of("all", Arrays.stream(Aarch64Feature.values())
                .filter(f -> !f.name().startsWith("SME") && !f.name().startsWith("SCALABLE_MATRIX"))
                .toArray(Aarch64Feature[]::new)));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            for (Aarch64Architecture preset : presets) {
                Aarch64Decoder decoder = new Aarch64Decoder(preset);
                for (int word : words) {
                    String result;
                    try {
                        result = String.valueOf(decoder.decode(new OneWord(word), 0x1000L));
                    } catch (RuntimeException e) {
                        result = e.getClass().getSimpleName();
                    }
                    out.printf("%s %08x %s%n", preset.name(), word, result);
                }
            }
        }
    }
}
