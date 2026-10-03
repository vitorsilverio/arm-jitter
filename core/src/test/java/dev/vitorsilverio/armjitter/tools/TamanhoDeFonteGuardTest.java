package dev.vitorsilverio.armjitter.tools;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/// E15.1 (decisão D7 do épico E15) — catraca de tamanho dos fontes de `core/src/main`.
///
/// Arquivo-fonte gigante custa contexto a cada leitura e esconde `switch`/cascatas que deveriam
/// ser dado. Este teste falha se:
/// <ul>
///   <li>um `.java` fora de {@link #EXCEPTIONS} passar de {@link #LINE_LIMIT} linhas — dividir o
///       arquivo, não acrescentá-lo à lista;</li>
///   <li>um arquivo da lista crescer além do teto individual registrado (a contagem do dia em que
///       entrou na lista, ou a do último encolhimento);</li>
///   <li>um arquivo da lista já estiver dentro do limite, ou não existir mais — a entrada tem de
///       ser removida (a lista só encolhe; a E15.22 fecha com ela vazia).</li>
/// </ul>
///
/// Quando uma sub-task da E15 encolhe um arquivo que continua acima do limite, o teto dele aqui
/// DESCE para a contagem nova no mesmo commit.
class TamanhoDeFonteGuardTest {
    private static final int LINE_LIMIT = 800;
    /// Relativo ao diretório de trabalho do surefire (o módulo `core/`).
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final String SOURCE_SUFFIX = ".java";
    private static final String PACKAGE_PREFIX = "dev/vitorsilverio/armjitter/";

    /// Arquivos acima do limite em 2026-10-02 (caminho relativo a {@link #PACKAGE_PREFIX}) e o
    /// teto de linhas de cada um. **Só encolhe**: nenhuma entrada nova, nenhum teto maior.
    ///
    /// Única exceção registrada (E15.2 e E15.3): ao dividir `Ir64Op` e `IrOp` em interfaces por
    /// família, arquivos desta lista ganharam linhas de `import` (uma por interface que referenciam)
    /// e o teto deles subiu nessa medida exata — 7 arquivos `*64`, de 1 a 14 linhas, na E15.2
    /// (a entrada de `Ir64Op`, 5784, saiu no mesmo commit); `AsmBlockCompiler` +5,
    /// `IrSystemExecutor` +8, `VfpDecoder` +1 e `IrVfpExecutor` +1 na E15.3 (saiu a de `IrOp`, 5036).
    /// Na E15.4 (saiu a de `Ir64BlockExecutor`, 2349) os métodos de entrada dos executores A64
    /// ficaram `public` e os que não tinham Javadoc ganharam uma linha (G7):
    /// `Ir64VectorArithmeticExecutor` +5 e `Ir64VectorFpArithmeticExecutor` +4. Na E15.7 saíram
    /// `AsmBlockCompiler` (2580) e `AsmRuntimeHelpers` (1161), divididos por família; na E15.8,
    /// `IrSystemExecutor` (2516), com o MVE indo para executores por família.
    private static final Map<String, Integer> EXCEPTIONS = Map.ofEntries(
            entry("decoder64/Aarch64Decoder.java", 7851),
            entry("advsimd/AdvSimdLanes.java", 3677),
            entry("core64/Aarch64Core.java", 1848),
            entry("ir/StandardIrBuilder.java", 1352),
            entry("decoder/ArmDecoder.java", 1174),
            entry("codegen64/jvm64/Ir64BlockCompiler.java", 1161),
            entry("decoder/VfpDecoder.java", 1131),
            entry("decoder64/Aarch64SveDecoder.java", 1101),
            entry("executor64/SveFloat.java", 1092),
            entry("core/ArmCore.java", 1048),
            entry("arch/ArmArchitecture.java", 1001),
            entry("executor64/Ir64VectorArithmeticExecutor.java", 968),
            entry("codegen/executor/IrVfpExecutor.java", 928),
            entry("decoder64/Aarch64SmeDecoder.java", 905),
            entry("executor64/Ir64VectorFpArithmeticExecutor.java", 846),
            entry("core/MProfileExceptionModel.java", 841),
            entry("jit/JitRuntime.java", 810));

    @Test
    void noSourceOutsideTheExceptionListExceedsTheLimit() throws IOException {
        Map<String, Integer> offenders = new TreeMap<>();
        lineCounts().forEach((file, lines) -> {
            if (lines > LINE_LIMIT && !EXCEPTIONS.containsKey(file)) {
                offenders.put(file, lines);
            }
        });
        assertTrue(offenders.isEmpty(), "fonte acima de " + LINE_LIMIT
                + " linhas fora da lista de exceções — dividir o arquivo: " + offenders);
    }

    @Test
    void exceptionsNeverGrowPastTheirCeiling() throws IOException {
        Map<String, Integer> counts = lineCounts();
        Map<String, String> grown = new TreeMap<>();
        EXCEPTIONS.forEach((file, ceiling) -> {
            int lines = counts.getOrDefault(file, 0);
            if (lines > ceiling) {
                grown.put(file, lines + " > teto " + ceiling);
            }
        });
        assertTrue(grown.isEmpty(), "arquivo da lista de exceções cresceu: " + grown);
    }

    @Test
    void exceptionListOnlyHoldsFilesStillOverTheLimit() throws IOException {
        Map<String, Integer> counts = lineCounts();
        Map<String, Integer> stale = new TreeMap<>();
        EXCEPTIONS.keySet().forEach(file -> {
            int lines = counts.getOrDefault(file, 0);
            if (lines <= LINE_LIMIT) {
                stale.put(file, lines);
            }
        });
        assertEquals(Map.of(), stale,
                "arquivo já dentro do limite (ou removido) — tirar a entrada da lista de exceções");
    }

    /// Linhas de cada `.java` de `core/src/main`, chaveado pelo caminho relativo a
    /// {@link #PACKAGE_PREFIX} com `/` (independente do separador do sistema).
    private static Map<String, Integer> lineCounts() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String relative = SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
                if (relative.endsWith(SOURCE_SUFFIX)) {
                    counts.put(relative.substring(PACKAGE_PREFIX.length()),
                            Files.readAllLines(file, StandardCharsets.UTF_8).size());
                }
            }
        }
        return counts;
    }
}
