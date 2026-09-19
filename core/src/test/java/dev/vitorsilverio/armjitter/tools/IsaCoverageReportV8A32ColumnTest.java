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

/// A **porta da B14.7**: guarda o resultado medido da coluna `v8-A/32` (`ArmArchitecture.ARMV8A_32`)
/// que fechou o épico B14. Mesmo papel de {@link IsaCoverageReportA64CurationGuardTest}/
/// {@link IsaCoverageReport32BitCurationGuardTest} — verifica o RESULTADO na tabela versionada, não
/// a mecânica interna do medidor (`IsaCoverageReport` roda só via `gerar-cobertura-isa.sh`, fora do
/// `mvn test`, e usa `target/isa-decode/*.decode`, gitignored/ausente no CI).
///
/// **Regressão específica que este arquivo existe para pegar**: a primeira medição desta task deu
/// `vfp-uncond.decode` em 14/17 (não 17/17) por um FALSO POSITIVO no próprio
/// `IsaCoverageReport#decodesTheSameIgnoringCondition` — `VMAXNM_hp`/`_sp`/`_dp` mediam `⚠️`
/// porque o heurístico comparava só `DecodedInstruction.kind()`, e `VMAXNM` (`cond=1111`) e `VDIV`
/// (MESMO padrão de bits com `cond=1110`, uma instrução real e DISTINTA) compartilham o `Kind`
/// guarda-chuva `VFP_ALU` com `immediate` diferente. O fix (`sameSemantics`, comparando todos os
/// campos semânticos) não tinha nenhum teste automatizado cobrindo o CAMINHO que ele corrige — só
/// foi encontrado rodando `gerar-cobertura-isa.sh` manualmente. Este teste fecha essa lacuna: se
/// alguém reverter `sameSemantics` para comparar só `kind()`, ele falha.
///
/// CI-safe: lê só `docs/COBERTURA-ISA.md` (versionado), nunca `target/isa-decode/*.decode`.
class IsaCoverageReportV8A32ColumnTest {

    private static final Path TABLE = Path.of("..", "docs", "COBERTURA-ISA.md");

    /// As 9 colunas de 32 bits, na mesma ordem fixa que
    /// {@link IsaCoverageReport32BitCurationGuardTest} usa — `v8-A/32` (B14.7) é a última.
    private static final List<String> COLUMNS = List.of(
            "v4T", "v5TE", "v6K", "MPCore", "v7-A", "v6-M", "v7-M", "ARMv8.1-M+MVE", "v8-A/32");
    private static final int V8A32_COLUMN = COLUMNS.indexOf("v8-A/32");

    private static final String SUPPORTED = "✅";
    private static final String MISSING = "❌";
    private static final String NOT_APPLICABLE = "·";

    private record Row(String name, List<String> cells) {
    }

    private static List<String> lines;

    @BeforeAll
    static void loadTable() throws IOException {
        lines = Files.readAllLines(TABLE, StandardCharsets.UTF_8);
        assertTrue(!lines.isEmpty(), "docs/COBERTURA-ISA.md vazio ou ausente em " + TABLE.toAbsolutePath());
    }

    /// Mesma técnica de corte por seção dos outros guards: `## <título>` (exclusive) até o próximo
    /// `## ` (exclusive), mantendo só linhas de dados com EXATAMENTE 9 células (descarta A64/SVE/SME).
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
                continue;
            }
            parsed.add(new Row(matcher.group(1), cells));
        }
        return parsed;
    }

    /// **O teste que define a Aceite da B14.7**: as 17 células de `vfp-uncond.decode` medem `✅`
    /// na coluna `v8-A/32` — nenhuma `⚠️` (o falso positivo `VMAXNM`≡`VDIV`), nenhuma `❌`/`·`.
    @Test
    void vfpUncondGroupMeasuresAllSeventeenEncodingsAsSupported() {
        List<Row> vfpUncond = readSection("## VFP — formas incondicionais");
        assertEquals(17, vfpUncond.size(), "inventário de vfp-uncond.decode mudou de tamanho (17 esperado)");
        List<String> offenders = new ArrayList<>();
        for (Row rowEntry : vfpUncond) {
            String cell = rowEntry.cells().get(V8A32_COLUMN);
            if (!SUPPORTED.equals(cell)) {
                offenders.add(rowEntry.name() + " = " + cell);
            }
        }
        assertTrue(offenders.isEmpty(),
                "vfp-uncond.decode deixou de medir 17/17 ✅ na coluna v8-A/32 — achado B14.7 "
                        + "reaberto (falso positivo VMAXNM≡VDIV em decodesTheSameIgnoringCondition, "
                        + "ou tsv/Applicability revertidos): " + offenders);
    }

    /// As 8 colunas ANTIGAS (`v4T`...`ARMv8.1-M+MVE`) continuam `·` para todo `vfp-uncond.decode` —
    /// o grupo é `NOT_IN_ANY_PRESET` para elas, a coluna nova é estritamente aditiva.
    @Test
    void vfpUncondGroupStaysNotApplicableInAllEightOlderColumns() {
        List<Row> vfpUncond = readSection("## VFP — formas incondicionais");
        List<String> offenders = new ArrayList<>();
        for (Row rowEntry : vfpUncond) {
            for (int i = 0; i < V8A32_COLUMN; i++) {
                if (!NOT_APPLICABLE.equals(rowEntry.cells().get(i))) {
                    offenders.add(rowEntry.name() + " @ " + COLUMNS.get(i) + " = " + rowEntry.cells().get(i));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "vfp-uncond.decode passou a medir algo fora de v8-A/32: " + offenders);
    }

    /// `HLT` (`t16.decode`/`a32.decode`, `ArmFeature.HALT` desde B14.1) mede `✅` na coluna nova —
    /// regressão direta do estreitamento de `*` para as 7 colunas pré-v8-A no tsv.
    @Test
    void hltMeasuresSupportedInV8a32Column() {
        List<String> offenders = new ArrayList<>();
        for (String sectionPrefix : List.of("## A32", "## T16")) {
            for (Row rowEntry : readSection(sectionPrefix)) {
                if (!rowEntry.name().equals("HLT")) {
                    continue;
                }
                String cell = rowEntry.cells().get(V8A32_COLUMN);
                if (!SUPPORTED.equals(cell)) {
                    offenders.add(sectionPrefix + "/" + rowEntry.name() + " = " + cell);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "HLT deixou de medir ✅ em v8-A/32: " + offenders);
    }

    /// `VJCVT`/`VRINTR*`/`VRINTZ*`/`VRINTX*` (`vfp.decode`, condicional) medem `❌` HONESTO na
    /// coluna nova — nunca `·` (o tsv teria voltado a esconder trabalho pendente atrás de `*`) nem
    /// `✅` (ninguém implementou por acidente sem passar pela task própria, ver `## Resultado` B14.7).
    @Test
    void unimplementedArmv8FormsMeasureMissingNeverHiddenInV8a32Column() {
        List<Row> vfp = readSection("## VFP — ponto flutuante");
        List<String> pendingNames = List.of("VJCVT", "VRINTR_hp", "VRINTR_sp", "VRINTR_dp",
                "VRINTZ_hp", "VRINTZ_sp", "VRINTZ_dp", "VRINTX_hp", "VRINTX_sp", "VRINTX_dp");
        List<String> offenders = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (Row rowEntry : vfp) {
            if (!pendingNames.contains(rowEntry.name())) {
                continue;
            }
            found.add(rowEntry.name());
            String cell = rowEntry.cells().get(V8A32_COLUMN);
            if (!MISSING.equals(cell)) {
                offenders.add(rowEntry.name() + " = " + cell);
            }
        }
        assertEquals(pendingNames.size(), found.size(),
                "nem todos os mnemônicos pendentes esperados foram encontrados em vfp.decode: " + found);
        assertTrue(offenders.isEmpty(),
                "trabalho pendente ARMv8-A escondido (`·`) ou implementado sem task própria (`✅`) "
                        + "na coluna v8-A/32: " + offenders);
    }
}
