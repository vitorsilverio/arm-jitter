import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.LegacyArmDecoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/// E15.16a — tempo de decode A32, cascata antiga (`LegacyArmDecoder`) × tabela, na mesma JVM, com
/// rodadas intercaladas. As palavras são código ARM de verdade: os primeiros 16 KiB de um binário
/// (a BIOS do GBA é ARM puro).
///
/// Uso: `java -cp <classes>;<saida do javac> A32DecodeBench <binario.bin>` — imprime `ns/decode`
/// (melhor de 15) por preset.
public class A32DecodeBench {
    private static final int ROUNDS = 15;
    private static final int REPEATS = 400;
    private static final int WORDS = 4096;
    /// A instrução decodificada ESCAPA (como no lifter): senão o A/B mede escape analysis.
    static Object[] escaped = new Object[WORDS];

    private static final class OneWord implements AddressSpace {
        int word;

        @Override public int read8(int address) { return word & 0xFF; }
        @Override public int read16(int address) { return word & 0xFFFF; }
        @Override public int read32(int address) { return word; }
        @Override public int fetch32(int address) { return word; }
        @Override public void write8(int address, int value) { }
        @Override public void write16(int address, int value) { }
        @Override public void write32(int address, int value) { }
    }

    private static long round(InstructionDecoder decoder, int[] words, OneWord memory) {
        long start = System.nanoTime();
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            for (int i = 0; i < words.length; i++) {
                memory.word = words[i];
                escaped[i] = decoder.decode(memory, i << 2);
            }
        }
        return System.nanoTime() - start;
    }

    public static void main(String[] args) throws Exception {
        ByteBuffer binary = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[WORDS];
        for (int i = 0; i < WORDS; i++) {
            words[i] = binary.getInt(i << 2);
        }
        OneWord memory = new OneWord();
        for (ArmArchitecture architecture : new ArmArchitecture[] {ArmArchitecture.ARMV4T, ArmArchitecture.ARMV7A}) {
            InstructionDecoder legacy = new LegacyArmDecoder(architecture);
            InstructionDecoder table = new ArmDecoder(architecture);
            long bestLegacy = Long.MAX_VALUE;
            long bestTable = Long.MAX_VALUE;
            for (int i = 0; i < ROUNDS; i++) {
                bestLegacy = Math.min(bestLegacy, round(legacy, words, memory));
                bestTable = Math.min(bestTable, round(table, words, memory));
            }
            double perDecode = (double) REPEATS * WORDS;
            System.out.printf("%-8s cascata %.1f ns  tabela %.1f ns%n", architecture.name(), bestLegacy / perDecode,
                    bestTable / perDecode);
        }
    }
}
