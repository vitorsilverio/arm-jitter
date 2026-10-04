import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.tools.DecodeTreeSpec;
import dev.vitorsilverio.armjitter.tools.IsaCoverageReport;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/// E17 item 1 — medição: para cada linha de `a64.decode`/`sve.decode`/`sme.decode` e cada palavra de amostra
/// (as `FILL_STRATEGIES` do `IsaCoverageReport`) que o `Aarch64Decoder` ACEITA numa célula que a tabela
/// mede, grava `grupo · nome#ocorrência · palavra · assinatura · colunas`. A assinatura é a classe do
/// record + enum de operação, a MESMA de `A64InstructionSignature` (a primeira medição usava todo `enum`).
///
/// A aplicabilidade espelha `IsaCoverageReport#appendGroup` (a64 = mapa de versão + exclusões; sve = coluna
/// com `SVE`, sonda direta e depois a máxima; sme = colunas SME com a sonda máxima) — só células que a tabela
/// realmente sonda entram.
///
/// Uso: `java -cp <core/target/classes;core/target/test-classes> A64SignatureProbe.java <dir-decode> <saida.tsv>`
public class A64SignatureProbe {
    private record OneWord(int word) implements AddressSpace64 {
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return word; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    private static final Set<String> SME_COLUMNS = Set.of("ARMv9.2-A", "ARMv9.3-A", "ARMv9.4-A", "ARMv9.5-A");

    static Method encode;
    static Method maximal;
    static Method applicable;
    static Method excluded;
    static int[][] strategies;
    static Method officialSignature;
    static final Map<Aarch64Architecture, Aarch64Decoder> decoders = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class<?> report = IsaCoverageReport.class;
        officialSignature = Class.forName("dev.vitorsilverio.armjitter.tools.A64InstructionSignature")
                .getDeclaredMethod("of", Ir64Op.class);
        officialSignature.setAccessible(true);
        encode = report.getDeclaredMethod("encode", DecodeTreeSpec.Instruction.class, int[].class);
        encode.setAccessible(true);
        maximal = report.getDeclaredMethod("maximalSveProbeArchitecture", Aarch64Architecture.class);
        maximal.setAccessible(true);
        applicable = report.getDeclaredMethod("isApplicableToAarch64Version", String.class, int.class,
                Aarch64Architecture.class);
        applicable.setAccessible(true);
        excluded = report.getDeclaredMethod("isExcluded", String.class, String.class, String.class, int.class);
        excluded.setAccessible(true);
        Field fill = report.getDeclaredField("FILL_STRATEGIES");
        fill.setAccessible(true);
        strategies = (int[][]) fill.get(null);
        Field archField = report.getDeclaredField("AARCH64_ARCHITECTURES");
        archField.setAccessible(true);
        Map<String, Aarch64Architecture> columns = (Map<String, Aarch64Architecture>) archField.get(null);
        Method loadExclusions = report.getDeclaredMethod("loadExclusions", Path.class);
        loadExclusions.setAccessible(true);
        loadExclusions.invoke(null, Path.of("docs/isa-nao-aplicavel.tsv"));

        Path dir = Path.of(args[0]);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            for (String file : List.of("a64.decode", "sve.decode", "sme.decode")) {
                Map<String, Integer> occurrences = new LinkedHashMap<>();
                for (DecodeTreeSpec.Instruction instruction : DecodeTreeSpec.parse(dir.resolve(file), 32)) {
                    int occurrence = occurrences.merge(instruction.name(), 1, Integer::sum);
                    if (instruction.name().equals("INVALID")) {
                        continue;
                    }
                    // (palavra, assinatura) → colunas
                    Map<String, Set<String>> seen = new LinkedHashMap<>();
                    for (Map.Entry<String, Aarch64Architecture> column : columns.entrySet()) {
                        Aarch64Architecture arch = probeArchitecture(file, instruction, occurrence, column.getKey(),
                                column.getValue());
                        if (arch == null) {
                            continue;
                        }
                        for (int[] strategy : strategies) {
                            int word = (int) encode.invoke(null, instruction, strategy);
                            String signature = signature(arch, word);
                            if (signature != null) {
                                seen.computeIfAbsent(String.format("%08x\t%s", word, signature),
                                        k -> new TreeSet<>()).add(column.getKey());
                            }
                        }
                    }
                    for (Map.Entry<String, Set<String>> entry : seen.entrySet()) {
                        out.printf("%s\t%s#%d\t%s\t%s%n", file, instruction.name(), occurrence, entry.getKey(),
                                String.join(",", entry.getValue()));
                    }
                }
            }
        }
    }

    /// A arquitetura sob a qual a tabela sonda a célula, ou `null` se ela não é sondada (`·`).
    private static Aarch64Architecture probeArchitecture(String file, DecodeTreeSpec.Instruction instruction,
                                                         int occurrence, String column, Aarch64Architecture arch)
            throws Exception {
        String name = instruction.name();
        switch (file) {
            case "a64.decode" -> {
                if (!(boolean) applicable.invoke(null, name, occurrence, arch)
                        || (boolean) excluded.invoke(null, name, column, file, occurrence)) {
                    return null;
                }
                return arch;
            }
            case "sve.decode" -> {
                if (!arch.has(Aarch64Feature.SVE) || (boolean) excluded.invoke(null, name, column, file, occurrence)) {
                    return null;
                }
                if (acceptsAny(arch, instruction)) {
                    return arch;
                }
                return null; // sonda máxima aceita ⇒ `·`; nenhuma aceita ⇒ `❌` (nada a assinar)
            }
            default -> {
                if (!SME_COLUMNS.contains(column) || (boolean) excluded.invoke(null, name, column, file, occurrence)) {
                    return null;
                }
                return (Aarch64Architecture) maximal.invoke(null, arch);
            }
        }
    }

    private static boolean acceptsAny(Aarch64Architecture arch, DecodeTreeSpec.Instruction instruction)
            throws Exception {
        for (int[] strategy : strategies) {
            if (signature(arch, (int) encode.invoke(null, instruction, strategy)) != null) {
                return true;
            }
        }
        return false;
    }

    private static String signature(Aarch64Architecture arch, int word) throws Exception {
        Ir64Op op;
        try {
            op = decoders.computeIfAbsent(arch, Aarch64Decoder::new).decode(new OneWord(word), 0x1000L);
        } catch (RuntimeException e) {
            return null;
        }
        if (op == null) {
            return null;
        }
        // Mesma assinatura que a tabela usa (`A64InstructionSignature`, pacote-privado: via reflexão).
        return (String) officialSignature.invoke(null, op);
    }
}
