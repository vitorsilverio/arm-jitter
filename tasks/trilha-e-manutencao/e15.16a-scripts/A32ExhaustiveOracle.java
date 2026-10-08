import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.LegacyArmDecoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/// E15.16a — oráculo EXAUSTIVO do `ArmDecoder`: as 2³² palavras A32, decoder antigo
/// (`LegacyArmDecoder`, cópia verbatim de antes da task) × decoder por tabela, `equals` do
/// `DecodedInstruction`, em todo preset público de `ArmArchitecture` e num preset com todas as
/// `ArmFeature` (sem extensões).
///
/// Compilar o `LegacyArmDecoder.java` e este arquivo contra `core/target/classes` e rodar:
/// `java -cp <classes>;<saida> A32ExhaustiveOracle [preset...]`. Saída: uma linha por preset com o
/// número de palavras diferentes (aceite = 0) e as primeiras divergências.
public class A32ExhaustiveOracle {
    private static final int CHUNK_BITS = 20;
    private static final int CHUNKS = 1 << (Integer.SIZE - CHUNK_BITS);
    private static final int ADDRESS = 0x0800_0100;
    private static final int MAX_REPORTED = 12;

    /// Memória de uma palavra só: o decoder lê a instrução por `fetch32`.
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

    public static void main(String[] args) throws Exception {
        Map<String, ArmArchitecture> presets = new LinkedHashMap<>();
        IdentityHashMap<ArmArchitecture, String> seen = new IdentityHashMap<>();
        for (Field field : ArmArchitecture.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ArmArchitecture.class) {
                ArmArchitecture architecture = (ArmArchitecture) field.get(null);
                if (seen.putIfAbsent(architecture, field.getName()) == null) {
                    presets.put(field.getName(), architecture);
                }
            }
        }
        presets.put("ALL_FEATURES", ArmArchitecture.of("all", ArmFeature.values()));
        List<String> wanted = List.of(args);
        long total = 0;
        for (Map.Entry<String, ArmArchitecture> preset : presets.entrySet()) {
            if (!wanted.isEmpty() && !wanted.contains(preset.getKey())) {
                continue;
            }
            long start = System.nanoTime();
            InstructionDecoder legacy = new LegacyArmDecoder(preset.getValue());
            InstructionDecoder table = new ArmDecoder(preset.getValue());
            AtomicLong differences = new AtomicLong();
            ConcurrentLinkedQueue<String> reported = new ConcurrentLinkedQueue<>();
            IntStream.range(0, CHUNKS).parallel().forEach(chunk -> {
                OneWord memory = new OneWord();
                int base = chunk << CHUNK_BITS;
                long local = 0;
                for (int low = 0; low < 1 << CHUNK_BITS; low++) {
                    memory.word = base | low;
                    DecodedInstruction expected = legacy.decode(memory, ADDRESS);
                    DecodedInstruction actual = table.decode(memory, ADDRESS);
                    if (!expected.equals(actual)) {
                        local++;
                        if (reported.size() < MAX_REPORTED) {
                            reported.add(String.format("  %08x%n    antes : %s%n    depois: %s", memory.word, expected,
                                    actual));
                        }
                    }
                }
                differences.addAndGet(local);
            });
            total += differences.get();
            System.out.printf("%-20s diferentes=%d (%.0f s)%n", preset.getKey(), differences.get(),
                    (System.nanoTime() - start) / 1e9);
            List<String> lines = new ArrayList<>(reported);
            lines.stream().sorted().limit(MAX_REPORTED).forEach(System.out::println);
        }
        System.out.println("TOTAL diferentes=" + total);
    }
}
