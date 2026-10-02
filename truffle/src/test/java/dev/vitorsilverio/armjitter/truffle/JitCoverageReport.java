package dev.vitorsilverio.armjitter.truffle;

import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/// Gera `docs/COBERTURA-JIT.md`: a tabela de **cobertura de EMISSÃO NATIVA** do arm-jitter,
/// operação de IR por operação de IR, por backend.
///
/// ## Por que existe
///
/// `docs/COBERTURA-ISA.md` ({@link dev.vitorsilverio.armjitter.tools.IsaCoverageReport}) mede a
/// **dimensão 1** do `tasks/ROADMAP-100-ARM.md`: o decoder reconhece o encoding e o interpretador
/// executa. Uma instrução pode estar `✅` lá e **nunca ter sido compilada nativamente por backend
/// nenhum** — foi assim que se descobriu, em 2026-09-02, que o Javadoc de
/// {@link Ir64NativePolicy} afirmava cobertura exaustiva quando na verdade a política parou de
/// crescer na task B6.5.4 (24 `Kind`) e desde então entraram B6.6.x, toda a AdvSIMD (B8.6-B8.20),
/// EL2/EL3 (B10), o gating (B11) e B19 — nada com emissão nativa.
///
/// Esta ferramenta é o espelho do {@code IsaCoverageReport} para a **dimensão 2** (e mostra, de
/// quebra, a **dimensão 3**: o backend Truffle).
///
/// ## Como funciona (é medição, não inventário escrito à mão)
///
/// Para cada `record` de {@link IrOp} (77) e cada `Ir64Op.Kind` (96) monta-se uma instância
/// representativa por REFLEXÃO — construtor canônico com valores default por tipo — e chama-se a
/// política de verdade:
///
/// - **ASM 32 bits** — {@link AsmNativePolicy#supports(IrOp)} (casa por `record`).
/// - **Truffle 32 bits** — {@link IrOpNodeFactory#supports(IrOp)} (casa por `Kind`).
/// - **ASM 64 bits** — {@link Ir64NativePolicy#supports(Ir64Op)} (casa por `Kind`).
/// - **Truffle 64 bits** — inexistente: o módulo `truffle/` não tem NENHUM nó de 64 bits (a coluna
///   fica na tabela para tornar a ausência visível — é a task A10.8).
///
/// O lado ASM de 32 bits tem carve-outs CONDICIONAIS (`Load.unprivileged`, `BranchExchange.link`,
/// `Alu` com `dst=PC && setFlags`, ...): a política recusa a op só num caso específico e
/// documentado, sendo nativa no caminho comum. Para não inflar nem esconder o gap, esses casos são
/// medidos com uma SEGUNDA instância — a variante adversa — e marcados `⚠️` com a condição legível.
///
/// ## Limite honesto
///
/// Mede se a política ACEITA emitir a op, não se o bytecode emitido está correto (isso é o
/// {@code BlockEquivalenceHarness}). E a política é `WHOLE_BLOCK` nos dois pipelines: UMA op `❌`/
/// `⚠️`-no-caso derruba o BLOCO inteiro para o interpretador.
///
/// ## Uso
///
/// ```
/// ./gerar-cobertura-jit.sh
/// ```
///
/// (ou, na mão: {@code mvn -o -q -pl core,truffle -am test-compile} e então
/// {@code java -cp <core+truffle classes> dev.vitorsilverio.armjitter.truffle.JitCoverageReport
/// docs/COBERTURA-JIT.md}). Não baixa nada — a fonte é o próprio código.
public final class JitCoverageReport {

    private JitCoverageReport() {
    }

    /// Estado de uma célula backend × operação.
    enum Emission {
        /// A política emite bytecode nativo para a op (no caminho comum).
        NATIVE("✅"),
        /// Nativa no caminho comum, mas recusada num caso específico e documentado — o bloco
        /// inteiro cai no interpretador quando esse caso ocorre.
        CONDITIONAL("⚠️"),
        /// A política recusa a op: o bloco que a contém roda inteiro no interpretador.
        INTERPRETED("❌");

        final String mark;

        Emission(String mark) {
            this.mark = mark;
        }
    }

    /// Uma linha da tabela de 32 bits.
    record Row32(String operation, int kind, String kindName, Emission asm, Emission truffle, String condition) {
    }

    /// Uma linha da tabela de 64 bits.
    record Row64(String kindName, int kind, Emission asm, Emission truffle) {
    }

    // ---------------------------------------------------------------------------------------------
    // Carve-outs condicionais do lado ASM 32 bits: instância adversa (que a política recusa) +
    // a condição legível. Curados a partir do fonte de AsmNativePolicy#supports — se algum deixar
    // de "virar" (primária aceita, adversa também), o gerador emite um aviso (é achado, não ruído).
    // ---------------------------------------------------------------------------------------------

    private static final Condition AL = Condition.AL;

    private record Conditional(IrOp adverse, String condition) {
    }

    private static final Map<Class<? extends IrOp>, Conditional> ASM32_CONDITIONALS = new LinkedHashMap<>();

    static {
        ASM32_CONDITIONALS.put(IntegerOp.Alu.class, new Conditional(
                new IntegerOp.Alu(IrOpCode.ORN, 15, 0, -1, new IrOperand.Immediate(0), true, AL),
                "nativa exceto `dst=PC` com `setFlags` (restaura o CPSR a partir do SPSR) e "
                        + "`opcode=ORN` (Thumb-2, sem emissão nativa ainda)"));
        ASM32_CONDITIONALS.put(IntegerOp.Saturating.class, new Conditional(
                new IntegerOp.Saturating(15, 0, 0, 0, AL),
                "nativa exceto `dst=PC` (`UNPREDICTABLE`/troca de bloco)"));
        ASM32_CONDITIONALS.put(IntegerOp.DspMultiply.class, new Conditional(
                new IntegerOp.DspMultiply(15, 0, 0, 0, 0, 0, 0, AL),
                "nativa exceto `dst=PC`, ou `SMLAWx`/`SMULWx` (`op2=2`) com `Rn=PC`"));
        ASM32_CONDITIONALS.put(MemoryOp.DoubleTransfer.class, new Conditional(
                new MemoryOp.DoubleTransfer(true, 15, 1, 0, -1, new IrOperand.Immediate(0), false, false, AL),
                "STRD (só lê registradores) sempre nativa; LDRD nativa exceto com `PC` no par "
                        + "carregado (sem tratamento de interworking no emissor)"));
        ASM32_CONDITIONALS.put(MemoryOp.Load.class, new Conditional(
                new MemoryOp.Load(0, 1, -1, new IrOperand.Immediate(0), 4, false, false, false, true, AL),
                "nativa exceto `LDRxT` (`unprivileged`: precisa de `AddressSpace#withUnprivilegedAccess`)"));
        ASM32_CONDITIONALS.put(MemoryOp.Store.class, new Conditional(
                new MemoryOp.Store(0, -1, 1, -1, new IrOperand.Immediate(0), 4, false, false, true, AL),
                "nativa exceto `STRxT` (`unprivileged`)"));
        ASM32_CONDITIONALS.put(BranchOp.BranchExchange.class, new Conditional(
                new BranchOp.BranchExchange(0, -1, true, 0, AL),
                "nativa exceto `BLX` (`link`: interworking + link register)"));
        ASM32_CONDITIONALS.put(BranchOp.ThumbBlSuffix.class, new Conditional(
                new BranchOp.ThumbBlSuffix(0, 0, true, AL),
                "nativa exceto a forma `BLX` (`exchange`: alinha o destino e troca para ARM)"));
        ASM32_CONDITIONALS.put(VfpOp.CoreTransfer.class, new Conditional(
                new VfpOp.CoreTransfer(true, 0, 0, true, AL),
                "nativa exceto `VMOV.F16` (`halfWidth`: transferência de 16 bits, sem preset com "
                        + "`HALF_PRECISION_FP` hoje)"));
    }

    // ---------------------------------------------------------------------------------------------
    // Medição
    // ---------------------------------------------------------------------------------------------

    /// Nome legível da constante `IrOp.Kind` de um dado valor inteiro.
    private static final Map<Integer, String> IR_OP_KIND_NAMES = kindConstantNames(IrOp.Kind.class);
    /// Nome legível da constante `Ir64Op.Kind` de um dado valor inteiro.
    private static final Map<Integer, String> IR64_OP_KIND_NAMES = kindConstantNames(Ir64Op.Kind.class);

    /// Linhas de 32 bits, ordenadas por `Kind` (ordem de definição no fonte).
    static List<Row32> measure32() {
        List<Row32> rows = new ArrayList<>();
        List<String> anomalies = new ArrayList<>();
        for (Class<? extends IrOp> recordClass : recordsOf(IrOp.class)) {
            IrOp primary = instantiate(recordClass);
            int kind = primary.kind();

            boolean asmPrimary = AsmNativePolicy.supports(primary);
            Emission asm;
            String condition = "";
            Conditional conditional = ASM32_CONDITIONALS.get(recordClass);
            if (conditional != null) {
                boolean asmAdverse = AsmNativePolicy.supports(conditional.adverse());
                if (asmPrimary && !asmAdverse) {
                    asm = Emission.CONDITIONAL;
                    condition = conditional.condition();
                } else {
                    asm = asmPrimary ? Emission.NATIVE : Emission.INTERPRETED;
                    anomalies.add(operationName(recordClass) + ": carve-out curado não se confirmou "
                            + "(primária=" + asmPrimary + ", adversa=" + asmAdverse + ") — revisar AsmNativePolicy");
                }
            } else {
                asm = asmPrimary ? Emission.NATIVE : Emission.INTERPRETED;
            }

            Emission truffle = IrOpNodeFactory.supports(primary) ? Emission.NATIVE : Emission.INTERPRETED;
            rows.add(new Row32(operationName(recordClass), kind, IR_OP_KIND_NAMES.get(kind), asm, truffle, condition));
        }
        rows.sort((a, b) -> Integer.compare(a.kind(), b.kind()));
        if (!anomalies.isEmpty()) {
            throw new IllegalStateException("anomalias na medição de 32 bits: " + anomalies);
        }
        return rows;
    }

    /// `Família.Record` (task E15.3) — o nome simples sozinho se repete entre famílias
    /// (`IntegerOp.Alu` × `VfpOp.Alu`).
    private static String operationName(Class<?> recordClass) {
        return recordClass.getEnclosingClass().getSimpleName() + "." + recordClass.getSimpleName();
    }

    /// Linhas de 64 bits, ordenadas por `Kind`.
    static List<Row64> measure64() {
        Map<Integer, Row64> byKind = new TreeMap<>();
        for (Class<? extends Ir64Op> recordClass : recordsOf(Ir64Op.class)) {
            Ir64Op primary = instantiate(recordClass);
            int kind = primary.kind();
            Emission asm = Ir64NativePolicy.supports(primary) ? Emission.NATIVE : Emission.INTERPRETED;
            // Truffle 64 bits não existe (A10.8): nenhum nó de 64 bits no módulo `truffle/`.
            byKind.put(kind, new Row64(IR64_OP_KIND_NAMES.getOrDefault(kind, "?"), kind, asm, Emission.INTERPRETED));
        }
        return new ArrayList<>(byKind.values());
    }

    // ---------------------------------------------------------------------------------------------
    // Instanciação reflexiva
    // ---------------------------------------------------------------------------------------------

    /// Todos os `record` permitidos de uma interface selada, na ordem do `permits`, descendo as
    /// sub-interfaces seladas por família (task E15.2: `Ir64Op` → `SveOp64` → `SveFpOp64` → records).
    static <T> List<Class<? extends T>> recordsOf(Class<T> sealedType) {
        List<Class<? extends T>> records = new ArrayList<>();
        collectRecords(sealedType, records);
        return records;
    }

    @SuppressWarnings("unchecked")
    private static <T> void collectRecords(Class<?> sealedType, List<Class<? extends T>> into) {
        for (Class<?> permitted : sealedType.getPermittedSubclasses()) {
            if (permitted.isRecord()) {
                into.add((Class<? extends T>) permitted);
            } else {
                collectRecords(permitted, into);
            }
        }
    }

    /// Instancia um `record` selado pelo construtor canônico, preenchendo cada componente com o
    /// valor default do seu tipo (`0`/`0L`/`false`/primeira constante de enum/`Immediate(0)`).
    /// As políticas e a factory só inspecionam `op.kind()` e alguns campos escalares — os valores
    /// exatos não importam para a medição, só a aridade/tipo que os construtores validam.
    @SuppressWarnings("unchecked")
    private static <T> T instantiate(Class<? extends T> recordClass) {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = defaultValue(types[i]);
        }
        try {
            Constructor<?> constructor = recordClass.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return (T) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("não foi possível instanciar " + recordClass.getName(), e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == IrOperand.class) {
            return new IrOperand.Immediate(0);
        }
        if (type == Ir64Op.class) {
            return new Ir64Op.Cycle(0); // `StreamingRestricted.inner` (B18.2): qualquer operação serve
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new IllegalStateException("tipo de componente sem valor default: " + type.getName());
    }

    private static Map<Integer, String> kindConstantNames(Class<?> kindHolder) {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Field field : kindHolder.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                try {
                    names.put(field.getInt(null), field.getName());
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return names;
    }

    // ---------------------------------------------------------------------------------------------
    // Renderização
    // ---------------------------------------------------------------------------------------------

    static String render() {
        List<Row32> rows32 = measure32();
        List<Row64> rows64 = measure64();

        int asm32Native = count(rows32, r -> r.asm() == Emission.NATIVE);
        int asm32Conditional = count(rows32, r -> r.asm() == Emission.CONDITIONAL);
        int truffle32Native = count(rows32, r -> r.truffle() == Emission.NATIVE);
        int asm64Native = count(rows64, r -> r.asm() == Emission.NATIVE);

        StringBuilder md = new StringBuilder();
        md.append("""
                # Cobertura de emissão JIT do arm-jitter

                Tabela **gerada por medição**, não escrita à mão: cada `record` de IR vira uma
                instância representativa (construtor canônico, valores default) que é passada para a
                política de emissão nativa de cada backend. Regenerar com `./gerar-cobertura-jit.sh`
                (ver o Javadoc de `dev.vitorsilverio.armjitter.truffle.JitCoverageReport`).

                Esta é a **dimensão 2** (emissão JIT nativa) e a **dimensão 3** (Truffle) do
                [`tasks/ROADMAP-100-ARM.md`](../tasks/ROADMAP-100-ARM.md). **Não substitui nem
                duplica** `docs/COBERTURA-ISA.md`, que mede a dimensão 1 (decode + execução
                interpretada): uma op pode estar `✅` lá e `❌` aqui — decodifica e roda no
                interpretador, mas nenhum backend a compila.

                | | significado |
                |---|---|
                | ✅ | a política emite bytecode nativo para a op (no caminho comum) |
                | ⚠️ | nativa no caminho comum, recusada num caso específico e documentado (ver "Condicionais do lado 32 bits") — quando esse caso ocorre, o bloco INTEIRO cai no interpretador |
                | ❌ | a política recusa a op: o bloco que a contém roda inteiro no interpretador |

                **Política `WHOLE_BLOCK` nos dois pipelines.** `AsmNativePolicy.supports(IrBlock)` e
                `Ir64NativePolicy.supports(Ir64Block)` fazem `for (op : block) if (!supports(op))
                return false;` — **uma única op `❌` (ou `⚠️` no caso adverso) derruba o bloco
                inteiro** para o interpretador. Não é degradação proporcional.

                **O que ✅ NÃO significa:** que o bytecode emitido está correto. Isso é o
                `BlockEquivalenceHarness` (invariante G1). Esta tabela mede só se a política ACEITA
                emitir.

                **Coluna "Truffle (64 bits)" inteira `❌`** — o módulo `truffle/` não tem NENHUM nó
                de 64 bits; a coluna existe para tornar a ausência visível (task A10.8).

                """);

        md.append("## Progresso\n\n");
        md.append(String.format(Locale.ROOT,
                "> **ASM 32 bits: %d de %d** operações emitidas nativamente (mais %d condicionais).%n",
                asm32Native, rows32.size(), asm32Conditional));
        md.append(String.format(Locale.ROOT,
                "> **Truffle 32 bits: %d de %d** operações com nó especializado.%n",
                truffle32Native, rows32.size()));
        md.append(String.format(Locale.ROOT,
                "> **ASM 64 bits: %d de %d** `Kind` emitidos nativamente.%n",
                asm64Native, rows64.size()));
        md.append("> **Truffle 64 bits: 0 de ").append(rows64.size()).append("** — o backend não existe (A10.8).\n\n");

        md.append("A escada que fecha cada gap: `tasks/trilha-c-perf/c12-plano-jit-nativo.md` "
                + "(ASM, C12.2-C12.8) e `tasks/trilha-a-truffle/a10-plano-truffle-completo.md` "
                + "(Truffle, A10.3-A10.8).\n\n");
        md.append(String.format(Locale.ROOT,
                "> Conciliação com a medição do `ROADMAP-100-ARM.md` (2026-09-02): o `%d/73` de "
                        + "ASM 32 bits daquele documento = as `%d` `✅` incondicionais **mais** as "
                        + "`%d` `⚠️` (nativas no caminho comum); as `%d` linhas a mais aqui são os "
                        + "`Kind` de NEON por imediato de B13.7/B13.8 (todas `❌`). ASM 64 bits "
                        + "seguia `24/95`; `VECTOR_FP_CONVERT_PRECISION` (B19.4) levou o denominador "
                        + "a 96, ainda `❌` — daí `%d/96`.%n%n",
                asm32Native + asm32Conditional, asm32Native, asm32Conditional, rows32.size() - 73,
                asm64Native));

        md.append("## Tabela A — pipeline de 32 bits\n\n");
        md.append("Linhas = os ").append(rows32.size()).append(" `record` de `IrOp`, na ordem do `Kind`.\n\n");
        md.append("| Operação | `Kind` | ASM (`AsmNativePolicy`) | Truffle (`IrOpNodeFactory`) |\n");
        md.append("|---|---|---|---|\n");
        for (Row32 row : rows32) {
            md.append("| `").append(row.operation()).append("` | `").append(row.kindName()).append("` | ")
                    .append(row.asm().mark).append(" | ").append(row.truffle().mark).append(" |\n");
        }

        md.append("\n### Condicionais do lado 32 bits\n\n");
        md.append("Cada `⚠️` acima recusa a emissão nativa só no caso listado; no resto é `✅`.\n\n");
        md.append("| Operação | Condição |\n|---|---|\n");
        for (Row32 row : rows32) {
            if (row.asm() == Emission.CONDITIONAL) {
                md.append("| `").append(row.operation()).append("` | ").append(row.condition()).append(" |\n");
            }
        }

        md.append("\n## Tabela B — pipeline de 64 bits\n\n");
        md.append("Linhas = os ").append(rows64.size()).append(" `Ir64Op.Kind`. `Ir64NativePolicy` "
                + "casa por `Kind` e **não tem carve-outs condicionais** (sem `⚠️` deste lado). "
                + "A coluna Truffle é inteira `❌` (A10.8).\n\n");
        md.append("| `Kind` | ASM (`Ir64NativePolicy`) | Truffle |\n");
        md.append("|---|---|---|\n");
        for (Row64 row : rows64) {
            md.append("| `").append(row.kindName()).append("` | ").append(row.asm().mark).append(" | ")
                    .append(row.truffle().mark).append(" |\n");
        }
        md.append("\n### `Kind` de 64 bits ainda interpretados\n\n");
        md.append("Entrada da escada C12.3-C12.6.\n\n");
        for (Row64 row : rows64) {
            if (row.asm() == Emission.INTERPRETED) {
                md.append("- `").append(row.kindName()).append("`\n");
            }
        }
        md.append('\n');
        return md.toString();
    }

    private static <T> int count(List<T> rows, java.util.function.Predicate<T> predicate) {
        return (int) rows.stream().filter(predicate).count();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("uso: JitCoverageReport <arquivo-markdown-de-saida>");
            System.exit(2);
            return;
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, render(), StandardCharsets.UTF_8);
        System.out.println("escrito: " + output.toAbsolutePath());
    }
}
