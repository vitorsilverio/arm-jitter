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

/// A **porta da B20.6**: guarda o resultado medido da coluna `v7-R` (`ArmArchitecture.ARMV7R`,
/// perfil R, B20.1) que fecha o catálogo `ArmProcessor.CORTEX_R4`/`R5`/`R7`/`R8`. Mesmo papel de
/// {@link IsaCoverageReportV8A32ColumnTest}/{@link IsaCoverageReport32BitCurationGuardTest} —
/// verifica o RESULTADO medido na tabela versionada, não a mecânica interna do medidor.
///
/// **O que este teste fecha**: a B20.6 auditou manualmente (script descartável, fora do repo) que
/// nenhuma célula de `HVC`/`SMC`/`ERET` vazasse `✅` sob `v7-R` por engano — a Armadilha 2 nomeada
/// na spec ("esta task é, de fato, a primeira medição independente do acerto da B20.1"). Sem um
/// teste automatizado, essa auditoria não protege contra regressão (ex.: alguém reintroduzindo
/// `HYPERVISOR_CALL`/`SECURE_MONITOR_CALL` em `ARMV7R_FEATURES` por engano). Este arquivo torna
/// permanente o que a sessão da B20.6 confirmou só uma vez.
///
/// CI-safe: lê só `docs/COBERTURA-ISA.md` (versionado), nunca `target/isa-decode/*.decode`.
class IsaCoverageReportV7RColumnTest {

    private static final Path TABLE = Path.of("..", "docs", "COBERTURA-ISA.md");

    /// As 13 colunas de 32 bits, na mesma ordem fixa que
    /// {@link IsaCoverageReport32BitCurationGuardTest} usa — `v7-R` (B20.6) fica entre
    /// `v7-A+NEON` e `v8-R`; `v8-R` (B20.7) fica entre `v7-R` e `v6-M`.
    private static final List<String> COLUMNS = List.of(
            "v4T", "v5TE", "v6K", "MPCore", "v7-A", "v7-A+NEON", "v7-R", "v8-R", "v6-M", "v7-M",
            "ARMv8.1-M+MVE", "v8-A/32", "v8.6-A/32");
    private static final int V7R_COLUMN = COLUMNS.indexOf("v7-R");

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
    /// `## ` (exclusive), mantendo só linhas de dados com EXATAMENTE 11 células (descarta A64/SVE/SME).
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

    /// **A Armadilha 2 da B20.6, travada**: `HVC`/`SMC` (A32 e T32) e `ERET` (A32) medem `·` (B22.9: não existem em ARMv7-R)
    /// HONESTO sob `v7-R` — nunca `✅`. `ARMV7R_FEATURES` (B20.1) não declara
    /// `HYPERVISOR_CALL`/`VIRTUALIZATION_EXTENSIONS`/`SECURE_MONITOR_CALL`; se alguma sessão futura
    /// reintroduzir uma dessas features na lista por engano, o decoder passaria a aceitar o
    /// encoding e uma destas células viraria `✅` sem task própria.
    @Test
    void hypervisorAndSecureMonitorFormsMeasureMissingUnderV7r() {
        List<String> offenders = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (String sectionPrefix : List.of("## A32", "## T32")) {
            for (Row rowEntry : readSection(sectionPrefix)) {
                boolean isEretA32 = rowEntry.name().equals("ERET") && sectionPrefix.equals("## A32");
                boolean isHvcOrSmc = rowEntry.name().equals("HVC") || rowEntry.name().equals("SMC");
                if (!isEretA32 && !isHvcOrSmc) {
                    continue;
                }
                found.add(sectionPrefix + "/" + rowEntry.name());
                String cell = rowEntry.cells().get(V7R_COLUMN);
                if (!NOT_APPLICABLE.equals(cell)) {
                    offenders.add(sectionPrefix + "/" + rowEntry.name() + " = " + cell);
                }
            }
        }
        assertEquals(5, found.size(),
                "nem todas as formas esperadas (HVC/SMC em A32+T32, ERET em A32) foram encontradas: " + found);
        assertTrue(offenders.isEmpty(),
                "HVC/SMC/ERET deixaram de medir `·` (curadoria B22.9) sob v7-R — ARMV7R_FEATURES pode ter ganhado "
                        + "HYPERVISOR_CALL/VIRTUALIZATION_EXTENSIONS/SECURE_MONITOR_CALL sem task própria: "
                        + offenders);
    }

    /// A forma-base `SUBS PC,LR,#0` (T32, decodificada como `InstructionKind#SUB` — ver Javadoc de
    /// `Thumb2MiscDecoder`, "EXCEPTION_RETURN_SUB") mede `✅` sob `v7-R`, IGUAL a `v7-A` — não é uma
    /// aprovação de `ERET` real (o projeto não distingue as duas formas em T32 por design, desde
    /// antes da B20.6), só o comportamento pré-existente se repetindo no preset novo.
    @Test
    void t32SubsPcLrFormMeasuresSupportedUnderV7rSameAsArmv7a() {
        List<Row> t32 = readSection("## T32");
        Row eret = t32.stream().filter(rowEntry -> rowEntry.name().equals("ERET")).findFirst()
                .orElseThrow(() -> new AssertionError("ERET não encontrado em t32.decode"));
        int v7aColumn = COLUMNS.indexOf("v7-A");
        assertEquals(SUPPORTED, eret.cells().get(v7aColumn), "pré-condição mudou: ERET T32 não é mais ✅ em v7-A");
        assertEquals(SUPPORTED, eret.cells().get(V7R_COLUMN),
                "ERET T32 divergiu entre v7-A e v7-R — comportamento deveria ser idêntico (mesmo decode, "
                        + "sem feature nova envolvida)");
    }

    /// Os grupos condicionados a VFP/NEON/perfil M/MVE (`vfp.decode`, `vfp-uncond.decode`,
    /// `neon-*.decode`, `m-nocp.decode`, `mve.decode`) medem `·` em TODA linha sob `v7-R` —
    /// `ARMV7R_FEATURES` não declara `VFPV2`/`ADVANCED_SIMD`/`ARMV8_FP`/`M_PROFILE`/`MVE_INTEGER`,
    /// então nenhum desses grupos entra no denominador da coluna (mesma disciplina de
    /// `Applicability` que mantém a coluna nova sem bloco de `❌` cru, conforme a spec previu).
    @Test
    void vfpNeonMProfileAndMveGroupsStayNotApplicableUnderV7r() {
        List<String> sectionPrefixes = List.of(
                "## VFP — ponto flutuante", "## VFP — formas incondicionais", "## NEON — processamento",
                "## NEON — load/store", "## NEON — formas compartilhadas", "## ARMv7-M", "## MVE (Helium)");
        List<String> offenders = new ArrayList<>();
        int rowsChecked = 0;
        for (String prefix : sectionPrefixes) {
            for (Row rowEntry : readSection(prefix)) {
                rowsChecked++;
                String cell = rowEntry.cells().get(V7R_COLUMN);
                if (!NOT_APPLICABLE.equals(cell)) {
                    offenders.add(rowEntry.name() + " @ v7-R (seção " + prefix + ") = " + cell);
                }
            }
        }
        assertTrue(rowsChecked > 0, "nenhuma linha lida das seções VFP/NEON/M/MVE — seções mudaram de nome?");
        assertTrue(offenders.isEmpty(),
                "VFP/NEON/perfil M/MVE deixaram de medir `·` sob v7-R — ARMV7R_FEATURES pode ter ganhado "
                        + "uma feature dessas sem task própria: " + offenders);
    }

    /// **Os números da coluna nova, travados** (B22.7: `HLT` curado como `·` em v7-R): `a32.decode` 240/240, `t16.decode` 83/83,
    /// `t32.decode` 265/265 (B22.9: instruções ARMv8 curadas como `·`) — os únicos três grupos cuja `Applicability` (`CLASSIC_ARM`/`ALWAYS`/
    /// `THUMB2`) `ARMV7R` satisfaz. Ver `## Resultado` da B20.6 para a explicação linha a linha.
    @Test
    void a32T16T32GroupsMeasureTheCountsRecordedInB20_6() {
        Pattern summaryRow = Pattern.compile("^\\| (A32[^|]*|T16[^|]*|T32[^|]*) \\| \\d+ \\|(.*)$");
        java.util.Map<String, String> byGroup = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = summaryRow.matcher(line);
            if (matcher.find()) {
                byGroup.put(matcher.group(1).trim(), matcher.group(2));
            }
        }
        assertEquals(3, byGroup.size(), "linha de resumo de A32/T16/T32 não encontrada (todas as 3): " + byGroup.keySet());

        java.util.Map<String, String> expectedFragment = java.util.Map.of(
                "A32 — instruções ARM de 32 bits", "v7-R 100% (240/240)",
                "T16 — Thumb clássico", "v7-R 100% (83/83)",
                "T32 — Thumb-2", "v7-R 100% (265/265)");
        List<String> offenders = new ArrayList<>();
        expectedFragment.forEach((group, fragment) -> {
            String cells = byGroup.get(group);
            if (cells == null || !cells.contains(fragment)) {
                offenders.add(group + " esperava conter \"" + fragment + "\", achou: " + cells);
            }
        });
        assertTrue(offenders.isEmpty(), "coluna v7-R mudou de número sem atualizar este teste/o ## Resultado da "
                + "B20.6: " + offenders);
    }
}
