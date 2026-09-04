package dev.vitorsilverio.armjitter.tools;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A **porta fechada pela E12**: curadoria de versão A64 vive no mapa de features de
/// {@link IsaCoverageReport}, nunca em `docs/isa-nao-aplicavel.tsv`.
///
/// ## O problema que estes testes impedem de voltar
///
/// `docs/COBERTURA-ISA.md` tem 16 colunas de versão A64 (`ARMv8.0-A`…`ARMv9.5-A`), mas a TSV nasceu
/// quando o A64 era uma coluna monolítica. Uma linha de TSV não sabe dizer "existe a partir da
/// versão X" — ela apaga a instrução das colunas que casa. Havia **dois** mecanismos fazendo isso:
///
/// 1. arquitetura `A64`, que casava com TODAS as 16 colunas de versão (111 linhas ⇒ 134 da tabela);
/// 2. arquitetura `*`, que casa com toda coluna de qualquer arquitetura (`CRC32*` e `SEVL`,
///    ⇒ 9 linhas da tabela) — este nem estava na spec original da E12, e era o pior dos dois: a
///    linha de `SEVL` apagava **16 células `✅` reais**.
///
/// Os testes abaixo cobrem os dois, porque fechar só o primeiro deixaria a próxima sessão recriar o
/// problema com um `*`. Mesmo papel do `JitCoverageReportGuardTest` da C12.1.
///
/// Todos são CI-safe: leem `docs/COBERTURA-ISA.md` e `docs/isa-nao-aplicavel.tsv` (versionados),
/// nunca `target/isa-decode/a64.decode` (gitignored, ausente no CI).
class IsaCoverageReportA64CurationGuardTest {

    /// Uma linha da seção `## A64 — AArch64` da tabela: mnemônico, ocorrência 1-based e as 16
    /// células, na ordem das colunas de {@link #COLUMNS}.
    private record TableRow(String name, int occurrence, List<String> cells) {
    }

    private static final Path TABLE = Path.of("..", "docs", "COBERTURA-ISA.md");
    private static final Path TSV = Path.of("..", "docs", "isa-nao-aplicavel.tsv");

    private static final List<String> COLUMNS = List.of(
            "ARMv8.0-A", "ARMv8.1-A", "ARMv8.2-A", "ARMv8.3-A", "ARMv8.4-A", "ARMv8.5-A",
            "ARMv8.6-A", "ARMv8.7-A", "ARMv8.8-A", "ARMv8.9-A", "ARMv9.0-A", "ARMv9.1-A",
            "ARMv9.2-A", "ARMv9.3-A", "ARMv9.4-A", "ARMv9.5-A");

    private static final Map<String, Aarch64Architecture> ARCHITECTURES = new LinkedHashMap<>();

    static {
        ARCHITECTURES.put("ARMv8.0-A", Aarch64Architecture.ARMV8_0_A);
        ARCHITECTURES.put("ARMv8.1-A", Aarch64Architecture.ARMV8_1_A);
        ARCHITECTURES.put("ARMv8.2-A", Aarch64Architecture.ARMV8_2_A);
        ARCHITECTURES.put("ARMv8.3-A", Aarch64Architecture.ARMV8_3_A);
        ARCHITECTURES.put("ARMv8.4-A", Aarch64Architecture.ARMV8_4_A);
        ARCHITECTURES.put("ARMv8.5-A", Aarch64Architecture.ARMV8_5_A);
        ARCHITECTURES.put("ARMv8.6-A", Aarch64Architecture.ARMV8_6_A);
        ARCHITECTURES.put("ARMv8.7-A", Aarch64Architecture.ARMV8_7_A);
        ARCHITECTURES.put("ARMv8.8-A", Aarch64Architecture.ARMV8_8_A);
        ARCHITECTURES.put("ARMv8.9-A", Aarch64Architecture.ARMV8_9_A);
        ARCHITECTURES.put("ARMv9.0-A", Aarch64Architecture.ARMV9_0_A);
        ARCHITECTURES.put("ARMv9.1-A", Aarch64Architecture.ARMV9_1_A);
        ARCHITECTURES.put("ARMv9.2-A", Aarch64Architecture.ARMV9_2_A);
        ARCHITECTURES.put("ARMv9.3-A", Aarch64Architecture.ARMV9_3_A);
        ARCHITECTURES.put("ARMv9.4-A", Aarch64Architecture.ARMV9_4_A);
        ARCHITECTURES.put("ARMv9.5-A", Aarch64Architecture.ARMV9_5_A);
    }

    private static final String SUPPORTED = "✅";
    private static final String MISSING = "❌";
    private static final String NOT_APPLICABLE = "·";
    private static final String FALLBACK = "⚠️";

    private static List<TableRow> rows;

    @BeforeAll
    static void loadTableAndExclusions() throws IOException {
        rows = readA64Section();
        if (IsaCoverageReport.EXCLUSIONS.isEmpty()) {
            IsaCoverageReport.loadExclusions(TSV);
        }
    }

    /// Lê a seção `## A64 — AArch64` da tabela versionada. A ordem das linhas é a ordem do
    /// inventário, e a ocorrência é o contador 1-based por nome — a mesma conta que
    /// `IsaCoverageReport#appendGroup` faz.
    private static List<TableRow> readA64Section() throws IOException {
        List<String> lines = Files.readAllLines(TABLE, StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## A64")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "seção '## A64' não encontrada em " + TABLE.toAbsolutePath());

        Pattern row = Pattern.compile("^\\| `([^`]+)` \\|(.*)\\|\\s*$");
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<TableRow> parsed = new ArrayList<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) {
            Matcher matcher = row.matcher(lines.get(i));
            if (!matcher.matches()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : matcher.group(2).split("\\|", -1)) {
                cells.add(cell.trim());
            }
            if (cells.size() != COLUMNS.size()) {
                continue; // linha de outra largura: não é a tabela versionada
            }
            String name = matcher.group(1);
            parsed.add(new TableRow(name, occurrences.merge(name, 1, Integer::sum), cells));
        }
        assertFalse(parsed.isEmpty(), "nenhuma linha lida da seção A64");
        return parsed;
    }

    /// **A porta, mecanismo 1**: nenhuma linha de TSV pode excluir um mnemônico de `a64.decode`
    /// numa das 16 colunas de VERSÃO. Cobre `A64`, `*` e nome de coluna explícito de uma vez — é a
    /// afirmação que importa, independente de COMO a linha foi escrita.
    @Test
    void noTsvLineMayExcludeAnA64MnemonicFromAVersionedColumn() {
        List<String> offenders = new ArrayList<>();
        for (TableRow row : rows) {
            for (String column : COLUMNS) {
                if (IsaCoverageReport.isExcluded(row.name(), column, "a64.decode", row.occurrence())) {
                    offenders.add(row.name() + "#" + row.occurrence() + " @ " + column);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "curadoria de versão A64 voltou para docs/isa-nao-aplicavel.tsv — ela tem que viver em "
                        + "IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS[_BY_OCCURRENCE], que deriva as "
                        + "colunas via architecture.has(feature). Linhas ofensoras: " + offenders);
    }

    /// **A porta, mecanismo 2**: nenhuma linha de TSV pode declarar a arquitetura `A64`. Depois da
    /// E12 a string só faria sentido para a coluna monolítica de `sve.decode`/`sme.decode` — e
    /// aqueles grupos são `NOT_IN_ANY_PRESET`, então já medem `·` sem precisar de TSV nenhuma.
    @Test
    void noTsvLineUsesTheLegacyA64Architecture() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String line : Files.readAllLines(TSV, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t");
            if (columns.length >= 2 && columns[1].trim().equals("A64")) {
                offenders.add(columns[0].trim());
            }
        }
        assertTrue(offenders.isEmpty(),
                "linhas com arquitetura `A64` reapareceram na TSV (E12 migrou as 111 para o mapa de "
                        + "features): " + offenders);
    }

    /// O teste que **define** a task: nenhuma instrução pode medir `·` numa coluna cuja arquitetura
    /// TEM a feature curada para ela. `·` só é legítimo ANTES da versão que introduz a feature.
    @Test
    void noInstructionIsNotApplicableInAColumnWhoseArchitectureHasItsFeature() {
        List<String> offenders = new ArrayList<>();
        for (TableRow row : rows) {
            Aarch64Feature required = requirementFor(row);
            for (int i = 0; i < COLUMNS.size(); i++) {
                if (!NOT_APPLICABLE.equals(row.cells().get(i))) {
                    continue;
                }
                Aarch64Architecture architecture = ARCHITECTURES.get(COLUMNS.get(i));
                boolean hasFeature = required == null || architecture.has(required);
                if (hasFeature) {
                    offenders.add(row.name() + "#" + row.occurrence() + " @ " + COLUMNS.get(i)
                            + " (requisito=" + required + ")");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "instruções medindo `·` numa versão em que a feature EXISTE — trabalho pendente "
                        + "escondido: " + offenders);
    }

    /// O inverso: nenhuma instrução com requisito de versão pode medir `✅`/`❌`/`⚠️` numa coluna
    /// ANTERIOR à feature. Ali ela não existe, e cobrar dela seria inflar o denominador.
    @Test
    void noInstructionIsMeasuredInAColumnBeforeItsFeatureExists() {
        List<String> offenders = new ArrayList<>();
        for (TableRow row : rows) {
            Aarch64Feature required = requirementFor(row);
            if (required == null) {
                continue;
            }
            for (int i = 0; i < COLUMNS.size(); i++) {
                if (ARCHITECTURES.get(COLUMNS.get(i)).has(required)) {
                    continue;
                }
                if (!NOT_APPLICABLE.equals(row.cells().get(i))) {
                    offenders.add(row.name() + "#" + row.occurrence() + " @ " + COLUMNS.get(i)
                            + " = " + row.cells().get(i) + " (requisito=" + required + ")");
                }
            }
        }
        assertTrue(offenders.isEmpty(), "instruções medidas antes da versão que as introduz: " + offenders);
    }

    /// A dívida G8 medida pela E12 é `⚠️`, nunca `✅`: publicar `✅` para um encoding que o decoder
    /// devolve como OUTRA instrução afirmaria trabalho concluído que não existe — pior que o `·`
    /// que a TSV produzia antes.
    @Test
    void everyKnownMisdecodedLineIsMarkedFallbackNeverSupported() {
        Set<String> pending = new LinkedHashSet<>(IsaCoverageReport.AARCH64_MISDECODED.keySet());
        List<String> offenders = new ArrayList<>();
        for (TableRow row : rows) {
            String key = row.name() + "#" + row.occurrence();
            if (!IsaCoverageReport.AARCH64_MISDECODED.containsKey(key)) {
                continue;
            }
            pending.remove(key);
            for (int i = 0; i < COLUMNS.size(); i++) {
                String cell = row.cells().get(i);
                if (SUPPORTED.equals(cell)) {
                    offenders.add(key + " @ " + COLUMNS.get(i) + " mede ✅");
                }
            }
        }
        assertTrue(offenders.isEmpty(), "misdecode publicado como suporte real: " + offenders);
        assertTrue(pending.isEmpty(),
                "entradas de AARCH64_MISDECODED que não casam nenhuma linha do inventário "
                        + "(mnemônico renomeado pelo QEMU?): " + pending);
    }

    /// Cada entrada de `AARCH64_MISDECODED` tem que produzir pelo menos uma célula `⚠️` de verdade.
    /// Se o decoder for consertado, esta asserção falha e obriga a REMOVER a entrada — é o que
    /// impede a lista de virar uma exclusão permanente disfarçada (regra máxima do `tasks/README.md`).
    @Test
    void everyKnownMisdecodedLineStillProducesAWarningCell() {
        List<String> stale = new ArrayList<>();
        for (TableRow row : rows) {
            String key = row.name() + "#" + row.occurrence();
            if (!IsaCoverageReport.AARCH64_MISDECODED.containsKey(key)) {
                continue;
            }
            if (row.cells().stream().noneMatch(FALLBACK::equals)) {
                stale.add(key);
            }
        }
        assertTrue(stale.isEmpty(),
                "estas linhas não misdecodificam mais — remova-as de AARCH64_MISDECODED: " + stale);
    }

    /// `SEVL` é o achado que justifica o mecanismo 2 da porta: ISA base do A64, JÁ implementado, e
    /// a linha `SEVL	*	…` da TSV (correta para as 7 colunas de 32 bits) apagava 16 células `✅`
    /// reais. Se este teste falhar, alguém devolveu o `*`.
    @Test
    void sevlIsSupportedInAllSixteenA64Columns() {
        TableRow sevl = rows.stream().filter(r -> r.name().equals("SEVL")).findFirst().orElseThrow();
        assertEquals(List.of(SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED,
                        SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED,
                        SUPPORTED, SUPPORTED, SUPPORTED, SUPPORTED),
                sevl.cells(), "SEVL é ISA base A64 e já decodifica — não pode voltar a medir `·`");
    }

    /// `CRC32`/`CRC32C` são o outro lado do mesmo achado: `·` só em `ARMv8.0-A` (onde `FEAT_CRC32` é
    /// opcional) e trabalho pendente de `ARMv8.1-A` em diante — não `·` nas 16, como o `*` fazia.
    @Test
    void crc32IsPendingWorkFromArmv81aOnwards() {
        List<TableRow> crc = rows.stream()
                .filter(r -> r.name().equals("CRC32") || r.name().equals("CRC32C"))
                .toList();
        assertEquals(8, crc.size(), "o inventário tem 4 linhas de CRC32 e 4 de CRC32C");
        for (TableRow row : crc) {
            assertEquals(NOT_APPLICABLE, row.cells().get(0),
                    row.name() + ": FEAT_CRC32 é opcional em ARMv8.0-A");
            for (int i = 1; i < COLUMNS.size(); i++) {
                assertEquals(MISSING, row.cells().get(i),
                        row.name() + " @ " + COLUMNS.get(i) + ": obrigatória de ARMv8.1-A em diante");
            }
        }
    }

    private static Aarch64Feature requirementFor(TableRow row) {
        Aarch64Feature byOccurrence = IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE
                .get(row.name() + "#" + row.occurrence());
        return byOccurrence != null
                ? byOccurrence
                : IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS.get(row.name());
    }
}
