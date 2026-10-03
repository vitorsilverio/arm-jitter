package dev.vitorsilverio.armjitter.ir;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/// E15.6b — guarda da vivência de registradores da `DeadCodeEliminationPass`.
///
/// Um record de {@link IrOp} que lê um GPR sem declarar em {@link IrOp#regUse()} deixa a DCE apagar
/// a escrita anterior desse registrador: o resultado só fica errado no JIT. Este teste falha se:
/// <ul>
///   <li>um record com componente `int` de nome de GPR ({@link #GPR_COMPONENT_NAMES}) não declarar
///       `regUse()` no próprio record, salvo se estiver em {@link #EXCEPTIONS};</li>
///   <li>uma entrada de {@link #EXCEPTIONS} já declarar `regUse()` ou não tiver mais componente de
///       nome de GPR — a entrada tem de ser removida (a lista só encolhe).</li>
/// </ul>
///
/// Leitura de GPR implícita (sem componente: o `LR` da tail-predication MVE, o `SP` do `PUSH`) não
/// é vista por este teste — fica com o `IrOpRegisterMaskTest`.
class IrOpRegisterUseGuardTest {
    /// Nomes de componente que designam um registrador de propósito geral nos records de hoje.
    private static final Set<String> GPR_COMPONENT_NAMES = Set.of(
            "dst", "src", "src1", "rn", "rm", "rs", "ra", "rt", "rt2", "rdm", "rda", "rdaLo", "rdaHi",
            "rdalo", "rdahi", "dstLow", "dstHigh", "dividend", "divisor", "base", "first", "second",
            "register", "armRegister", "armLow", "armHigh", "sourceRegister", "bankedRegister",
            "registerMask", "list");

    /// Records com componente de nome de GPR que, com justificativa, não declaram `regUse()`.
    /// **Só encolhe.**
    private static final Map<String, String> EXCEPTIONS = Map.of(
            "IntegerOp.ClearMultiple", "`CLRM` só zera os registradores de `list`, não lê nenhum",
            "MemoryOp.LoadLiteral", "`dst` só é escrito; o endereço é constante resolvida no lift",
            "NeonFpOp.FusedMultiplyAddLongByElement", "`rm` é registrador vetorial (`S`/`D`), não GPR");

    @Test
    void everyRecordWithAGprComponentDeclaresRegUse() {
        Set<String> missing = new TreeSet<>();
        for (Class<?> record : irOpRecords()) {
            String name = displayName(record);
            if (!gprComponents(record).isEmpty() && !declaresRegUse(record) && !EXCEPTIONS.containsKey(name)) {
                missing.add(name + " " + gprComponents(record));
            }
        }
        assertTrue(missing.isEmpty(), "record lê GPR sem declarar regUse() — a DCE pode apagar a escrita "
                + "anterior do registrador: " + missing);
    }

    @Test
    void exceptionListOnlyHoldsRecordsThatStillNeedIt() {
        Map<String, String> stale = new TreeMap<>();
        Map<String, Class<?>> byName = new TreeMap<>();
        irOpRecords().forEach(record -> byName.put(displayName(record), record));
        EXCEPTIONS.keySet().forEach(name -> {
            Class<?> record = byName.get(name);
            if (record == null) {
                stale.put(name, "record não existe mais");
            } else if (declaresRegUse(record)) {
                stale.put(name, "já declara regUse()");
            } else if (gprComponents(record).isEmpty()) {
                stale.put(name, "sem componente de nome de GPR");
            }
        });
        assertTrue(stale.isEmpty(), "remover da lista de exceções: " + stale);
    }

    private static List<Class<?>> irOpRecords() {
        List<Class<?>> records = new ArrayList<>();
        collect(IrOp.class, records);
        return records;
    }

    private static void collect(Class<?> type, List<Class<?>> records) {
        if (type.isRecord()) {
            records.add(type);
            return;
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) {
            Arrays.stream(permitted).forEach(subtype -> collect(subtype, records));
        }
    }

    private static List<String> gprComponents(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .filter(component -> component.getType() == int.class)
                .map(RecordComponent::getName)
                .filter(GPR_COMPONENT_NAMES::contains)
                .toList();
    }

    private static boolean declaresRegUse(Class<?> record) {
        try {
            record.getDeclaredMethod("regUse");
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static String displayName(Class<?> record) {
        return record.getEnclosingClass().getSimpleName() + "." + record.getSimpleName();
    }
}
