package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// C12.1 — guarda contra o apodrecimento da tabela `docs/COBERTURA-JIT.md`.
///
/// Foi exatamente assim que o Javadoc de `Ir64NativePolicy` ficou desatualizado por ~70 `Kind`:
/// nada falhava quando um `Kind` novo entrava. Este teste falha se:
/// <ul>
///   <li>um `record` de {@link IrOp} ou um `Ir64Op.Kind` novo não tiver linha na tabela gerada
///       (cobertura por reflexão — o {@link JitCoverageReport} sempre inclui todos, então na
///       prática isto pega uma constante `Kind` órfã / um `record` que o gerador não conseguiu
///       instanciar);</li>
///   <li>o `docs/COBERTURA-JIT.md` versionado estiver defasado em relação à medição atual
///       (regenerar tem de dar diff vazio — mesma regra do `docs/COBERTURA-ISA.md`).</li>
/// </ul>
class JitCoverageReportGuardTest {

    @Test
    void every32BitRecordHasExactlyOneRowMappedToANamedKind() {
        var rows = JitCoverageReport.measure32();
        assertEquals(IrOp.class.getPermittedSubclasses().length, rows.size(),
                "toda subclasse selada de IrOp tem de virar uma linha");

        Set<Integer> rowKinds = rows.stream().map(JitCoverageReport.Row32::kind)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(kindConstants(IrOp.Kind.class), rowKinds,
                "o conjunto de Kind das linhas tem de ser exatamente as constantes de IrOp.Kind");
        assertTrue(rows.stream().allMatch(r -> r.kindName() != null && !r.kindName().isBlank()),
                "toda linha tem de mapear para uma constante Kind nomeada");
    }

    @Test
    void every64BitKindHasExactlyOneRow() {
        var rows = JitCoverageReport.measure64();
        Set<Integer> rowKinds = rows.stream().map(JitCoverageReport.Row64::kind)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(kindConstants(Ir64Op.Kind.class), rowKinds,
                "o conjunto de Kind das linhas tem de ser exatamente as constantes de Ir64Op.Kind");
        assertEquals(kindConstants(Ir64Op.Kind.class).size(), rows.size(), "sem linha duplicada nem faltando");
    }

    @Test
    void committedDocMatchesCurrentMeasurement() throws IOException {
        Path doc = repoRoot().resolve("docs/COBERTURA-JIT.md");
        assertTrue(Files.exists(doc), "docs/COBERTURA-JIT.md não existe — rode ./gerar-cobertura-jit.sh");
        String committed = Files.readString(doc, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String current = JitCoverageReport.render().replace("\r\n", "\n");
        assertEquals(current, committed,
                "docs/COBERTURA-JIT.md está defasado — rode ./gerar-cobertura-jit.sh e commite");
    }

    private static Set<Integer> kindConstants(Class<?> kindHolder) {
        Set<Integer> values = new LinkedHashSet<>();
        for (Field field : kindHolder.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                try {
                    values.add(field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return new TreeSet<>(values);
    }

    /// Sobe a partir do diretório de trabalho do surefire (o módulo `truffle/`) enquanto o pai
    /// tiver um `pom.xml` — o topo é a raiz do repositório multi-módulo.
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir.getParent() != null && Files.exists(dir.getParent().resolve("pom.xml"))) {
            dir = dir.getParent();
        }
        return dir;
    }
}
