package dev.vitorsilverio.armjitter.tools;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.5.2 — curadoria de versão A64 por **(nome, ocorrência)** para as linhas `_h` de meia
/// precisão (`FEAT_FP16`) e `FEAT_FHM`. Trava o contrato do mecanismo novo de
/// {@link IsaCoverageReport} (`AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE`) e garante que a
/// curadoria não apaga cobertura real (`✅`).
final class Aarch64Fp16VersionCurationTest {

    /// `docs/COBERTURA-ISA.md` versionado — a partir de `core/` (basedir do surefire) ou da raiz.
    private static Path repoFile(String relative) {
        Path fromModule = Path.of(relative);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Path.of("..").resolve(relative);
    }

    // ─────────────────────────── mecanismo: requisito por ocorrência ───────────────────────────

    @Test
    void perOccurrenceRequirement_appliesOnlyFromFeatureVersion_andOnlyToTheListedOccurrence() {
        // `SCVTF_f` tem 5 linhas em `a64.decode`: occ 1 = `@icvt_h` (FP16), occ 2 = `@icvt_sd`
        // (ISA base), occ 3 = `@fcvt_fixed_h` (FP16), occ 4/5 = `@fcvt_fixed_s`/`_d` (ISA base).
        // Só 1 e 3 são curadas.
        assertFalse(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 1, Aarch64Architecture.ARMV8_0_A));
        assertFalse(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 1, Aarch64Architecture.ARMV8_1_A));
        assertTrue(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 1, Aarch64Architecture.ARMV8_2_A));
        assertTrue(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 1, Aarch64Architecture.ARMV9_5_A));

        assertFalse(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 3, Aarch64Architecture.ARMV8_0_A));
        assertTrue(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 3, Aarch64Architecture.ARMV8_2_A));

        // occ 2, 4, 5: não curadas, `SCVTF_f` não está no mapa por NOME ⇒ aplicável a toda coluna.
        for (int occurrence : new int[] {2, 4, 5}) {
            assertTrue(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", occurrence, Aarch64Architecture.ARMV8_0_A),
                    "ocorrência " + occurrence + " não é curada e deve valer em ARMv8.0-A");
        }
    }

    @Test
    void perOccurrenceRequirement_beatsNameRequirement_onTheListedOccurrence() {
        // Hoje nenhum nome está nos dois mapas; injeta-se um requisito por NOME conflitante para
        // provar que o por-OCORRÊNCIA vence na ocorrência listada e o por-nome vale nas demais.
        IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS.put("SCVTF_f", Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
        try {
            // occ 1 é curada como FP16 (ARMv8.2-A) — o por-ocorrência vence o por-nome (ARMv9.2-A).
            assertTrue(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 1, Aarch64Architecture.ARMV8_2_A));
            // occ 2 não é curada ⇒ cai no por-nome (ARMv9.2-A): indisponível em ARMv8.2-A.
            assertFalse(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 2, Aarch64Architecture.ARMV8_2_A));
            assertTrue(IsaCoverageReport.isApplicableToAarch64Version("SCVTF_f", 2, Aarch64Architecture.ARMV9_2_A));
        } finally {
            IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS.remove("SCVTF_f");
        }
    }

    // ─────────────────────────── contagem: 96 entradas (88 FP16 + 8 FHM) ───────────────────────

    @Test
    void occurrenceMap_has96Entries_88Fp16_and_8Fhm() {
        Map<String, Aarch64Feature> map = IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE;
        assertEquals(96, map.size(), "88 FEAT_FP16 + 8 FEAT_FHM");

        long fp16 = map.values().stream().filter(f -> f == Aarch64Feature.FP16).count();
        long fhm = map.values().stream().filter(f -> f == Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG).count();
        assertEquals(88, fp16);
        assertEquals(8, fhm);
        assertEquals(96, fp16 + fhm, "nenhuma outra feature entra nesta curadoria");

        for (String key : map.keySet()) {
            assertTrue(key.matches("[A-Za-z0-9_]+#\\d+"), "chave malformada: " + key);
        }
    }

    // ─── não-vazamento: nenhuma linha curada mostra ✅ numa coluna SEM a feature, e cada chave ────
    // ─── casa uma linha REAL da tabela. As colunas COM a feature podem (e hoje devem) ser ✅ reais ─
    // ─── — o decode de FEAT_FP16/FEAT_FHM foi implementado depois de B19.5.2 escrever esta ────────
    // ─── curadoria; o invariante que importa é a coluna certa, não "nunca implementado". ──────────

    /// Mesma ordem de 16 colunas de {@code docs/COBERTURA-ISA.md`} (ARMv8.0-A…ARMv9.5-A) que
    /// {@link IsaCoverageReport#AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE} indexa por posição.
    private static final List<Aarch64Architecture> COLUMN_ARCHITECTURES = List.of(
            Aarch64Architecture.ARMV8_0_A, Aarch64Architecture.ARMV8_1_A, Aarch64Architecture.ARMV8_2_A,
            Aarch64Architecture.ARMV8_3_A, Aarch64Architecture.ARMV8_4_A, Aarch64Architecture.ARMV8_5_A,
            Aarch64Architecture.ARMV8_6_A, Aarch64Architecture.ARMV8_7_A, Aarch64Architecture.ARMV8_8_A,
            Aarch64Architecture.ARMV8_9_A, Aarch64Architecture.ARMV9_0_A, Aarch64Architecture.ARMV9_1_A,
            Aarch64Architecture.ARMV9_2_A, Aarch64Architecture.ARMV9_3_A, Aarch64Architecture.ARMV9_4_A,
            Aarch64Architecture.ARMV9_5_A);

    @Test
    void curedLines_areNeverSupportedInAColumnThatLacksTheFeature_andEachMatchesARealTableRow() throws IOException {
        List<String[]> rows = readAarch64Table(); // [name, "st|st|..."], na ordem do inventário
        Map<String, Integer> occurrence = new LinkedHashMap<>();
        Map<String, List<String>> statusesByKey = new LinkedHashMap<>();
        for (String[] row : rows) {
            int occ = occurrence.merge(row[0], 1, Integer::sum);
            statusesByKey.put(row[0] + "#" + occ, List.of(row[1].split("\\|", -1)));
        }

        List<String> missingFromTable = new ArrayList<>();
        List<String> leakedIntoInapplicableColumn = new ArrayList<>();
        for (Map.Entry<String, Aarch64Feature> entry
                : IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE.entrySet()) {
            String key = entry.getKey();
            List<String> statuses = statusesByKey.get(key);
            if (statuses == null) {
                missingFromTable.add(key);
                continue;
            }
            for (int i = 0; i < COLUMN_ARCHITECTURES.size(); i++) {
                if (!COLUMN_ARCHITECTURES.get(i).has(entry.getValue()) && statuses.get(i).contains("✅")) {
                    leakedIntoInapplicableColumn.add(key + " @ col" + i + " -> " + statuses.get(i));
                }
            }
        }
        assertTrue(missingFromTable.isEmpty(), "chaves sem linha correspondente no inventário: " + missingFromTable);
        assertTrue(leakedIntoInapplicableColumn.isEmpty(),
                "curadoria deixou ✅ vazar para coluna sem a feature exigida: " + leakedIntoInapplicableColumn);
    }

    @Test
    void everyCuredLine_isNotApplicableExactlyWhereTheArchitectureLacksTheFeature() throws IOException {
        // Reforço do anterior: em toda coluna SEM a feature exigida a célula tem que ser `·`
        // (nunca ❌ cru nem ✅) — é exatamente isso que a curadoria por ocorrência existe para
        // garantir. Nas colunas COM a feature qualquer estado real (✅/❌) é aceitável.
        List<String[]> rows = readAarch64Table();
        Map<String, Integer> occurrence = new LinkedHashMap<>();
        Map<String, List<String>> statusesByKey = new LinkedHashMap<>();
        for (String[] row : rows) {
            int occ = occurrence.merge(row[0], 1, Integer::sum);
            statusesByKey.put(row[0] + "#" + occ, List.of(row[1].split("\\|", -1)));
        }
        for (Map.Entry<String, Aarch64Feature> entry
                : IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE.entrySet()) {
            List<String> statuses = statusesByKey.get(entry.getKey());
            for (int i = 0; i < COLUMN_ARCHITECTURES.size(); i++) {
                if (!COLUMN_ARCHITECTURES.get(i).has(entry.getValue())) {
                    assertEquals("·", statuses.get(i),
                            entry.getKey() + " @ col" + i + " deveria ser não-aplicável (·): " + statuses);
                }
            }
        }
    }

    // ─────────────────────────── TSV não esconde mais as 12 linhas ─────────────────────────────

    @Test
    void tsv_noLongerHidesTheTwelveGrossExclusions() throws IOException {
        if (IsaCoverageReport.EXCLUSIONS.isEmpty()) {
            IsaCoverageReport.loadExclusions(repoFile("docs/isa-nao-aplicavel.tsv"));
        }
        List<String> twelve = List.of(
                "FMLAL_v", "FMLSL_v", "FMLAL2_v", "FMLSL2_v",
                "FMLAL_vi", "FMLSL_vi", "FMLAL2_vi", "FMLSL2_vi",
                "FMAXNMV_h", "FMINNMV_h", "FMAXV_h", "FMINV_h");
        for (String name : twelve) {
            for (String column : new String[] {"ARMv8.0-A", "ARMv8.2-A", "ARMv9.5-A"}) {
                assertFalse(IsaCoverageReport.isExcluded(name, column, "a64.decode", 1),
                        name + " não pode mais estar na TSV (coluna " + column + ")");
            }
        }
    }

    // ─────────────────────────── util ───────────────────────────

    /// Lê a seção `## A64 — AArch64` de `docs/COBERTURA-ISA.md`: uma entrada por linha `| \`NOME\` |`,
    /// na ORDEM do inventário (que é a ordem de leitura do {@link DecodeTreeSpec}).
    private static List<String[]> readAarch64Table() throws IOException {
        List<String> lines = Files.readAllLines(repoFile("docs/COBERTURA-ISA.md"), StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## A64 — AArch64")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "seção A64 não encontrada");
        int header = -1;
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i).startsWith("| Instrução |")) {
                header = i;
                break;
            }
        }
        assertTrue(header >= 0);
        List<String[]> rows = new ArrayList<>();
        for (int i = header + 2; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("| `")) {
                break;
            }
            String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
            String name = cells[0].trim().replace("`", "");
            StringBuilder statuses = new StringBuilder();
            for (int c = 1; c < cells.length; c++) {
                if (c > 1) {
                    statuses.append('|');
                }
                statuses.append(cells[c].trim());
            }
            rows.add(new String[] {name, statuses.toString()});
        }
        assertEquals(1161, rows.size(), "inventário A64 esperado com 1161 linhas");
        return rows;
    }
}
