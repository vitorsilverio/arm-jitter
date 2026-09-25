package dev.vitorsilverio.armjitter.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A **porta que a B16.14 fecharia se existisse antes**: curadoria de `docs/isa-nao-aplicavel.tsv`
/// sem a coluna `grupo` pode apagar uma célula `✅` REAL de qualquer um dos grupos de 32 bits — a
/// mesma classe de bug (não um caso isolado) que a B16.14 encontrou e corrigiu duas vezes na mesma
/// sessão: uma exclusão pensada para `VDUP`/`VRINTZ*`/`VRINTX*` em `vfp.decode` também alcançava o
/// mnemônico HOMÔNIMO, mas DISTINTO, de `mve.decode` — sem `grupo`, a exclusão não sabe diferenciar.
///
/// Mesmo papel do `IsaCoverageReportA64CurationGuardTest` (que a E12 escreveu para o lado A64) —
/// este é o equivalente para as 11 colunas de 32 bits (`v4T`...`v7-M`, `ARMv8.1-M+MVE`, `v8-A/32` —
/// acrescentada pela B14.7 —, `v7-R` — acrescentada pela B20.6). Verifica o
/// RESULTADO medido (a tabela versionada), não a mecânica da exclusão — mesma filosofia de "a
/// tabela é medição, não opinião" que rege `IsaCoverageReport` inteiro.
///
/// CI-safe: lê só `docs/COBERTURA-ISA.md` (versionado), nunca `target/isa-decode/*.decode`
/// (gitignored, ausente no CI).
class IsaCoverageReport32BitCurationGuardTest {

    private static final Path TABLE = Path.of("..", "docs", "COBERTURA-ISA.md");

    /// As 13 colunas de 32 bits, na ordem fixa que toda seção de grupo usa (`v4T`...`v7-M` são as 7
    /// originais; `v7-A+NEON` é a coluna que a B13.22 acrescentou; `ARMv8.1-M+MVE` é a coluna que a
    /// B16.14 acrescentou; `v8-A/32` é a coluna que a B14.7 acrescentou; `v7-R` é a coluna que a
    /// B20.6 acrescentou; `v8-R` é a coluna que a B20.7 acrescentou, logo depois de `v7-R`).
    private static final List<String> COLUMNS = List.of(
            "v4T", "v5TE", "v6K", "MPCore", "v7-A", "v7-A+NEON", "v7-R", "v8-R", "v6-M", "v7-M",
            "ARMv8.1-M+MVE", "v8-A/32", "v8.6-A/32");

    private static final String SUPPORTED = "✅";
    private static final String FALLBACK = "⚠️";

    /// Uma linha de uma tabela de grupo de 32 bits: mnemônico e as células, na ordem de
    /// {@link #COLUMNS}. Só linhas com EXATAMENTE o número de colunas casam — isso descarta, de
    /// propósito, as tabelas A64 (16 colunas) e SVE/SME (1 coluna monolítica `A64`,
    /// `NOT_IN_ANY_PRESET`).
    private record Row(String name, List<String> cells) {
    }

    private static List<String> lines;

    @BeforeAll
    static void loadTable() throws IOException {
        lines = Files.readAllLines(TABLE, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "docs/COBERTURA-ISA.md vazio ou ausente em " + TABLE.toAbsolutePath());
    }

    /// Lê todas as linhas de dados de 32 bits entre `## <title>` (exclusive) e o próximo `## `
    /// (exclusive) — mesma técnica de corte por seção do guard A64, generalizada para qualquer
    /// grupo em vez de só `## A64`.
    private static List<Row> readSection(String sectionHeaderPrefix) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(sectionHeaderPrefix)) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "seção '" + sectionHeaderPrefix + "' não encontrada em " + TABLE.toAbsolutePath());

        Pattern row = Pattern.compile("^\\| `([^`]+)` \\|(.*)\\|\\s*$");
        List<Row> parsed = new ArrayList<>();
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
                continue; // outra largura: A64 (16) ou SVE/SME (1) — não é o que este guard mede
            }
            parsed.add(new Row(matcher.group(1), cells));
        }
        return parsed;
    }

    /// **O teste que define a task fechada pela B16.14**: as 352 células de `mve.decode` medem
    /// `✅` na coluna `ARMv8.1-M+MVE`. Se uma sessão futura reintroduzir uma exclusão de TSV sem
    /// `grupo` que colida com um mnemônico MVE (a classe exata do bug que a B16.14 corrigiu duas
    /// vezes), ou reverter a `Applicability` de volta para `NOT_IN_ANY_PRESET`, alguma célula aqui
    /// vira `·`/`❌` e este teste falha.
    @Test
    void mveGroupMeasuresAllThreeHundredFiftyTwoEncodingsAsSupported() {
        List<Row> mve = readSection("## MVE (Helium)");
        assertEquals(352, mve.size(), "inventário de mve.decode mudou de tamanho (352 esperado)");
        int mveColumn = COLUMNS.indexOf("ARMv8.1-M+MVE");
        List<String> offenders = new ArrayList<>();
        for (Row rowEntry : mve) {
            String cell = rowEntry.cells().get(mveColumn);
            if (!SUPPORTED.equals(cell)) {
                offenders.add(rowEntry.name() + " = " + cell);
            }
        }
        assertTrue(offenders.isEmpty(),
                "mve.decode deixou de medir 352/352 ✅ na coluna ARMv8.1-M+MVE — achado B16.14 "
                        + "reaberto (curadoria de TSV sem `grupo`, ou Applicability revertida): "
                        + offenders);
    }

    /// Nenhuma célula do grupo `mve.decode` pode ser `⚠️` (FALLBACK) — a Armadilha 1 da B16.14:
    /// um `⚠️` aqui significaria um decoder genérico (`Thumb2NocpDecoder`/`VfpDecoder`) engolindo um
    /// encoding MVE, bug de ORDEM de decoder, nunca cobertura parcial aceitável.
    @Test
    void mveGroupNeverMeasuresFallback() {
        List<Row> mve = readSection("## MVE (Helium)");
        int mveColumn = COLUMNS.indexOf("ARMv8.1-M+MVE");
        List<String> offenders = new ArrayList<>();
        for (Row rowEntry : mve) {
            if (FALLBACK.equals(rowEntry.cells().get(mveColumn))) {
                offenders.add(rowEntry.name());
            }
        }
        assertTrue(offenders.isEmpty(), "mve.decode com FALLBACK — decoder genérico capturando MVE: " + offenders);
    }

    /// **A porta, mecanismo 2**: nenhuma célula das 7 colunas ORIGINAIS (`v4T`...`v7-M`) pode virar
    /// `⚠️` em NENHUM grupo de 32 bits — mede que acrescentar a coluna nova (e as correções de TSV
    /// que a acompanharam) não introduziu um FALLBACK novo em nenhuma das 7 colunas antigas.
    @Test
    void noFallbackAppearsInAnyOfTheSevenOriginalColumns() {
        List<String> sectionPrefixes = List.of(
                "## A32", "## T16", "## T32", "## VFP — ponto flutuante", "## VFP — formas incondicionais",
                "## NEON — processamento", "## NEON — load/store", "## NEON — formas compartilhadas",
                "## ARMv7-M", "## MVE (Helium)");
        List<String> originalColumns = List.of("v4T", "v5TE", "v6K", "MPCore", "v7-A", "v6-M", "v7-M");
        List<String> offenders = new ArrayList<>();
        for (String prefix : sectionPrefixes) {
            for (Row rowEntry : readSection(prefix)) {
                for (String column : originalColumns) {
                    int i = COLUMNS.indexOf(column);
                    if (FALLBACK.equals(rowEntry.cells().get(i))) {
                        offenders.add(rowEntry.name() + " @ " + column + " (seção " + prefix + ")");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "FALLBACK novo nas 7 colunas originais: " + offenders);
    }

    /// **A porta, mecanismo 3**: as 7 colunas ORIGINAIS continuam **100%** no resumo de
    /// `## Progresso global` — a curadoria de TSV que a B16.14 escreveu para a coluna nova
    /// (`ARMv8.1-M+MVE`) tem que ser aditiva, nunca regredir uma célula `✅` das 7 colunas que já
    /// eram 100% antes da B16.14 (o bug real que a B16.14 encontrou e corrigiu ANTES do commit:
    /// escopar `grupo` para o arquivo ERRADO regredia MPCore/v7-A de 100% para ~98%/99%).
    @Test
    void theSevenOriginalArchitecturesRemainFullyCovered() {
        Pattern summary = Pattern.compile("^\\| (v4T|v5TE|v6K|MPCore|v7-A|v6-M|v7-M) \\| \\*\\*(\\d+)%\\*\\*");
        List<String> found = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = summary.matcher(line);
            if (matcher.find()) {
                found.add(matcher.group(1));
                if (!"100".equals(matcher.group(2))) {
                    offenders.add(matcher.group(1) + " = " + matcher.group(2) + "%");
                }
            }
        }
        assertEquals(7, found.size(), "linha de resumo de uma das 7 arquiteturas originais não encontrada");
        assertTrue(offenders.isEmpty(), "arquitetura original regrediu de 100%: " + offenders);
    }
}
