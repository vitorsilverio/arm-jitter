package dev.vitorsilverio.armjitter.tools;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// **E17** — guarda de `docs/isa-a64-assinaturas.tsv`, a assinatura revisada de cada linha A64 que
/// `IsaCoverageReport#probeAarch64` confere antes de marcar `✅`.
///
/// CI-safe como o `IsaCoverageReportA64CurationGuardTest`: lê só arquivos versionados (o TSV e
/// `docs/COBERTURA-ISA.md`), nunca o `.decode` do QEMU em `target/`.
///
/// O teste da amostra é o **oráculo por linha** das migrações do `Aarch64Decoder` para tabela (E15.10+):
/// qualquer mudança de decoder que faça uma linha implementada sair como outra instrução quebra aqui, no
/// `mvn test`, sem precisar regenerar a tabela.
class IsaA64SignatureGuardTest {

    private static final Path SIGNATURES = Path.of("..", "docs", "isa-a64-assinaturas.tsv");
    private static final Path TABLE = Path.of("..", "docs", "COBERTURA-ISA.md");
    /// Seção da tabela → inventário (`## <título>` do grupo em `IsaCoverageReport`).
    private static final Map<String, String> SECTIONS = Map.of(
            "## A64", "a64.decode",
            "## SVE", IsaCoverageReport.SVE_DECODE_FILE,
            "## SME", IsaCoverageReport.SME_DECODE_FILE);
    private static final String SUPPORTED = "✅";
    private static final String FALLBACK = "⚠️";
    private static final String RESERVED = "INVALID";

    /// Linha do TSV: chave (`grupo\tnome#occ`), assinaturas e amostra `<coluna>:<palavra>`.
    private record Entry(String key, Set<String> signatures, String column, int word) {
    }

    private static List<Entry> entries;

    @BeforeAll
    static void readTsv() throws IOException {
        entries = new ArrayList<>();
        for (String line : Files.readAllLines(SIGNATURES, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t");
            assertEquals(4, columns.length, "linha malformada no TSV: " + line);
            String[] sample = columns[3].split(":");
            entries.add(new Entry(columns[0] + "\t" + columns[1], new TreeSet<>(List.of(columns[2].split(" "))),
                    sample[0], Integer.parseUnsignedInt(sample[1], 16)));
        }
        assertFalse(entries.isEmpty(), "TSV de assinaturas vazio");
    }

    /// Cada amostra decodifica, na coluna anotada, com uma das assinaturas da sua linha.
    @Test
    void everySampleDecodesWithItsLineSignature() {
        List<String> offenders = new ArrayList<>();
        Map<Aarch64Architecture, Aarch64Decoder> decoders = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Aarch64Architecture column = IsaCoverageReport.AARCH64_ARCHITECTURES.get(entry.column());
            assertNotNull(column, "coluna desconhecida na amostra de " + entry.key());
            Aarch64Architecture probed = entry.key().startsWith(IsaCoverageReport.SME_DECODE_FILE + "\t")
                    ? IsaCoverageReport.maximalSveProbeArchitecture(column)
                    : column;
            String signature;
            try {
                TestAddressSpace raw = new TestAddressSpace(8);
                raw.put32(0, entry.word());
                signature = A64InstructionSignature.of(
                        decoders.computeIfAbsent(probed, Aarch64Decoder::new).decode(AddressSpace64.wrapping(raw), 0));
            } catch (RuntimeException e) {
                signature = "recusada (" + e.getClass().getSimpleName() + ")";
            }
            if (!entry.signatures().contains(signature)) {
                offenders.add(String.format("%s @ %s: %08x → %s (esperado: %s)", entry.key().replace('\t', ' '),
                        entry.column(), entry.word(), signature, entry.signatures()));
            }
        }
        assertTrue(offenders.isEmpty(), "linhas A64 que passaram a decodificar como OUTRA instrução (ou a "
                + "recusar) — misdecode novo, ou TSV a regravar com `./gerar-cobertura-isa.sh --assinaturas` "
                + "e revisar: " + offenders);
    }

    /// TSV × tabela nos dois sentidos: toda linha que mede `✅`/`⚠️` tem assinatura revisada, e toda
    /// entrada do TSV é uma linha da tabela que decodifica algo (sem órfã).
    @Test
    void tsvAndTableAgreeOnWhichLinesDecode() throws IOException {
        Set<String> decoding = decodingRowsOfTable();
        Set<String> tsvKeys = new LinkedHashSet<>();
        entries.forEach(entry -> tsvKeys.add(entry.key()));
        List<String> missing = decoding.stream().filter(key -> !tsvKeys.contains(key)).toList();
        List<String> orphans = tsvKeys.stream().filter(key -> !decoding.contains(key)).toList();
        assertTrue(missing.isEmpty(), "linhas que decodificam sem assinatura revisada no TSV: " + missing);
        assertTrue(orphans.isEmpty(), "entradas do TSV sem linha que decodifique na tabela (renomeada pelo "
                + "QEMU ou passou a ❌?): " + orphans);
    }

    /// Regressão do aceite: com a assinatura TROCADA (o record que o código anterior à E15.9b devolvia para
    /// `fmaxnmp v28.8h`), a célula mede `⚠️`; com a assinatura certa, `✅`; sem entrada, `⚠️`.
    @Test
    void probeMarksWrongOrMissingSignatureAsFallback() {
        int fmaxnmpH = 0x6e57041c;
        DecodeTreeSpec.Instruction line = new DecodeTreeSpec.Instruction("FMAXNMP_v", fmaxnmpH, ~fmaxnmpH,
                Map.of(), 32);
        String key = IsaCoverageReport.signatureKey("a64.decode", "FMAXNMP_v", 1);
        TestAddressSpace raw = new TestAddressSpace(8);
        raw.put32(0, fmaxnmpH);
        String actual = A64InstructionSignature.of(new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A)
                .decode(AddressSpace64.wrapping(raw), 0));
        Map<String, Set<String>> saved = new LinkedHashMap<>(IsaCoverageReport.A64_SIGNATURES);
        try {
            IsaCoverageReport.A64_SIGNATURES.put(key, Set.of("AdvSimdMoveOp64.InsertElement"));
            assertEquals(IsaCoverageReport.Status.FALLBACK, probe(line));
            IsaCoverageReport.A64_SIGNATURES.put(key, Set.of(actual));
            assertEquals(IsaCoverageReport.Status.SUPPORTED, probe(line));
            IsaCoverageReport.A64_SIGNATURES.remove(key);
            assertEquals(IsaCoverageReport.Status.FALLBACK, probe(line));
        } finally {
            IsaCoverageReport.A64_SIGNATURES.clear();
            IsaCoverageReport.A64_SIGNATURES.putAll(saved);
        }
    }

    private static IsaCoverageReport.Status probe(DecodeTreeSpec.Instruction line) {
        return IsaCoverageReport.probeAarch64(line, 1, Aarch64Architecture.ARMV9_5_A, "a64.decode", "ARMv9.5-A");
    }

    /// Chaves `grupo\tnome#occ` das linhas A64/SVE/SME da tabela com alguma célula `✅`/`⚠️` (linhas
    /// `INVALID` medem o contrário — `✅` é recusar — e não têm assinatura).
    private static Set<String> decodingRowsOfTable() throws IOException {
        Pattern row = Pattern.compile("^\\| `([^`]+)` \\|(.*)\\|\\s*$");
        Set<String> keys = new LinkedHashSet<>();
        String decodeFile = null;
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String line : Files.readAllLines(TABLE, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                decodeFile = SECTIONS.entrySet().stream().filter(s -> line.startsWith(s.getKey()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                occurrences.clear();
                continue;
            }
            Matcher matcher = row.matcher(line);
            if (decodeFile == null || !matcher.matches()) {
                continue;
            }
            String name = matcher.group(1);
            int occurrence = occurrences.merge(name, 1, Integer::sum);
            String cells = matcher.group(2);
            if (!name.equals(RESERVED) && (cells.contains(SUPPORTED) || cells.contains(FALLBACK))) {
                keys.add(IsaCoverageReport.signatureKey(decodeFile, name, occurrence));
            }
        }
        assertFalse(keys.isEmpty(), "nenhuma linha A64 lida de " + TABLE.toAbsolutePath());
        return keys;
    }
}
