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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A **porta da B17.26**: guarda o resultado medido da coluna por versão de `sve.decode` — mesma
/// disciplina de {@link IsaCoverageReportV8A32ColumnTest} (lê só `docs/COBERTURA-ISA.md`, versionado
/// e CI-safe; `IsaCoverageReport` em si roda só via `gerar-cobertura-isa.sh`, fora do `mvn test`,
/// porque usa `target/isa-decode/*.decode`, gitignored).
///
/// **O que esta task mudou e por que precisa de guarda**: `sve.decode` deixou de ser "não se aplica
/// a nenhum preset atual" e ganhou coluna por versão A64 via `probeSveApplicability` — uma sonda
/// dupla (arquitetura real vs. arquitetura com toda sub-feature SVE2 ligada), não uma curadoria por
/// mnemônico como `AARCH64_VERSION_REQUIREMENTS`. Dois jeitos de regredir silenciosamente:
/// 1. esquecer uma feature "irmã" (`FEAT_I8MM`/`FEAT_BF16`/`FEAT_FAMINMAX`/`FEAT_FP8*`/`FEAT_LUT`) na
///    lista `SVE_PROBE_EXTRA_FEATURES` faz a sonda "máxima" concluir "gap real" (`❌`) quando é só
///    "esta versão ainda não tem a sub-feature" (`·`) — `FAMAX`/`FAMIN` é o caso que pegou isso.
/// 2. remover `SVE_FEATURE_GATE_BUG_NAMES` (o registro de `SMMLA`/`USMMLA`/`UMMLA`) deixa a sonda
///    dupla concluir "·" para as 3 em TODAS as colunas — um bug real de decoder (gate `SVE2` amplo
///    demais em `Aarch64Sve2IntegerDecoder#decodePrefix45`) escondido atrás de "não aplicável".
class IsaCoverageReportSveColumnTest {

    private static final Path TABLE = Path.of("..", "docs", "COBERTURA-ISA.md");

    private static final List<String> AARCH64_COLUMNS = List.of(
            "ARMv8.0-A", "ARMv8.1-A", "ARMv8.2-A", "ARMv8.3-A", "ARMv8.4-A", "ARMv8.5-A",
            "ARMv8.6-A", "ARMv8.7-A", "ARMv8.8-A", "ARMv8.9-A", "ARMv9.0-A", "ARMv9.1-A",
            "ARMv9.2-A", "ARMv9.3-A", "ARMv9.4-A", "ARMv9.5-A");
    private static final int ARMV9_0_A = AARCH64_COLUMNS.indexOf("ARMv9.0-A");
    private static final int ARMV9_4_A = AARCH64_COLUMNS.indexOf("ARMv9.4-A");

    private static final String SUPPORTED = "✅";
    private static final String MISSING = "❌";
    private static final String NOT_APPLICABLE = "·";
    private static final String FALLBACK = "⚠️";

    private record Row(String name, List<String> cells) {
    }

    private static List<Row> sveRows;

    @BeforeAll
    static void loadSveSection() throws IOException {
        List<String> lines = Files.readAllLines(TABLE, StandardCharsets.UTF_8);
        assertTrue(!lines.isEmpty(), "docs/COBERTURA-ISA.md vazio ou ausente em " + TABLE.toAbsolutePath());

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## SVE/SVE2")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "seção 'SVE/SVE2' não encontrada — B17.26 deveria tê-la mantido");

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
            if (cells.size() != AARCH64_COLUMNS.size()) {
                continue;
            }
            parsed.add(new Row(matcher.group(1), cells));
        }
        sveRows = parsed;
    }

    /// `sve.decode` tem exatamente 929 encodings (partição normativa da B17.1) — se este número
    /// mudar, o inventário do QEMU mudou e a tabela precisa ser remedida/recurada, não só aceita.
    @Test
    void inventoryHasTheNormativePartitionSize() {
        assertEquals(929, sveRows.size(), "inventário de sve.decode mudou de tamanho (929 esperado)");
    }

    /// **A Aceite central da B17.26**: nenhuma das 16 colunas de `ARMv8.0-A`-`ARMv8.9-A` tem CÉLULA
    /// alguma diferente de `·` para `sve.decode` — nenhum desses presets declara `FEAT_SVE` (B17.1),
    /// então o grupo inteiro não é aplicável ali. Só a partir de `ARMv9.0-A` (índice
    /// {@link #ARMV9_0_A}) é que alguma célula pode deixar de ser `·`.
    @Test
    void everyArmv8ColumnStaysNotApplicableForTheWholeGroup() {
        List<String> offenders = new ArrayList<>();
        for (Row rowEntry : sveRows) {
            for (int i = 0; i < ARMV9_0_A; i++) {
                String cell = rowEntry.cells().get(i);
                if (!NOT_APPLICABLE.equals(cell)) {
                    offenders.add(rowEntry.name() + " @ " + AARCH64_COLUMNS.get(i) + " = " + cell);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "sve.decode mediu algo fora de '·' numa coluna ARMv8.x (nenhuma declara SVE): " + offenders);
    }

    /// Nenhuma célula `⚠️` em `sve.decode` — ver o Javadoc da classe sobre o que `⚠️` significaria
    /// aqui (bug de ordem/abrangência de decoder, nunca cobertura parcial).
    @Test
    void noFallbackCellsAnywhereInTheGroup() {
        List<String> offenders = new ArrayList<>();
        for (Row rowEntry : sveRows) {
            for (int i = ARMV9_0_A; i < AARCH64_COLUMNS.size(); i++) {
                if (FALLBACK.equals(rowEntry.cells().get(i))) {
                    offenders.add(rowEntry.name() + " @ " + AARCH64_COLUMNS.get(i));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "sve.decode tem ⚠️ (bug de decoder, investigar): " + offenders);
    }

    /// **Regressão-alvo 1**: `FAMAX`/`FAMIN` (`FEAT_FAMINMAX`) são `·` em `ARMv9.0-A`-`ARMv9.3-A`
    /// (a feature ainda não existe nesses presets) e `✅` a partir de `ARMv9.4-A` — nunca `❌`.
    /// Se `SVE_PROBE_EXTRA_FEATURES` perder `FP_ABSOLUTE_MAX_MIN`, isto quebra (a sonda "máxima"
    /// deixa de enxergar a feature e conclui "gap real" nas colunas que não a têm).
    @Test
    void famaxAndFaminAreNotApplicableBeforeArmv94aAndSupportedFromThen() {
        List<String> offenders = new ArrayList<>();
        boolean foundFamax = false;
        boolean foundFamin = false;
        for (Row rowEntry : sveRows) {
            if (!rowEntry.name().equals("FAMAX") && !rowEntry.name().equals("FAMIN")) {
                continue;
            }
            foundFamax |= rowEntry.name().equals("FAMAX");
            foundFamin |= rowEntry.name().equals("FAMIN");
            for (int i = ARMV9_0_A; i < ARMV9_4_A; i++) {
                if (!NOT_APPLICABLE.equals(rowEntry.cells().get(i))) {
                    offenders.add(rowEntry.name() + " @ " + AARCH64_COLUMNS.get(i) + " = " + rowEntry.cells().get(i));
                }
            }
            for (int i = ARMV9_4_A; i < AARCH64_COLUMNS.size(); i++) {
                if (!SUPPORTED.equals(rowEntry.cells().get(i))) {
                    offenders.add(rowEntry.name() + " @ " + AARCH64_COLUMNS.get(i) + " = " + rowEntry.cells().get(i));
                }
            }
        }
        assertTrue(foundFamax && foundFamin, "FAMAX/FAMIN não encontrados em sve.decode");
        assertTrue(offenders.isEmpty(), "FAMAX/FAMIN fora do esperado: " + offenders);
    }

    /// **Regressão-alvo 2**: `SMMLA`/`USMMLA`/`UMMLA` ficam **visíveis** como `❌` (aplicável,
    /// pendente) em toda coluna `ARMv9.x-A`, nunca `·` — ver `SVE_FEATURE_GATE_BUG_NAMES`. Um `·`
    /// aqui significaria que o registro do bug foi removido e o gap de decoder voltou a ficar
    /// invisível atrás de "não aplicável".
    @Test
    void theConfirmedDecoderGateBugStaysVisibleAsMissingNeverHiddenAsNotApplicable() {
        List<String> names = List.of("SMMLA", "USMMLA", "UMMLA");
        List<String> offenders = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (Row rowEntry : sveRows) {
            if (!names.contains(rowEntry.name())) {
                continue;
            }
            found.add(rowEntry.name());
            for (int i = ARMV9_0_A; i < AARCH64_COLUMNS.size(); i++) {
                if (!MISSING.equals(rowEntry.cells().get(i))) {
                    offenders.add(rowEntry.name() + " @ " + AARCH64_COLUMNS.get(i) + " = " + rowEntry.cells().get(i));
                }
            }
        }
        assertEquals(names.size(), found.size(), "SMMLA/USMMLA/UMMLA não encontrados em sve.decode: " + found);
        assertTrue(offenders.isEmpty(),
                "SMMLA/USMMLA/UMMLA deixaram de medir ❌ visível (achado B17.26 escondido de novo): " + offenders);
    }
}
