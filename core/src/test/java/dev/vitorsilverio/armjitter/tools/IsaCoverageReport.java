package dev.vitorsilverio.armjitter.tools;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Gera `docs/COBERTURA-ISA.md`: a tabela de **cobertura de decode** do arm-jitter, instrução por
/// instrução, por arquitetura.
///
/// ## Por que existe
///
/// Até aqui, cada instrução ARM faltante era descoberta do jeito caro: um guest travava, alguém
/// investigava por horas e no fim era "de novo a CPU não suporta X" (B6.8, B6.9, B6.10, B6.11,
/// B6.12, B3.9 — seis tasks, uma instrução por vez). Esta ferramenta troca a adivinhação por uma
/// varredura: lista TODAS as instruções de cada arquitetura e marca o que já decodifica.
///
/// ## Como funciona (é medição, não inventário escrito à mão)
///
/// 1. O inventário de instruções vem dos arquivos `decodetree` do QEMU (`target/arm/tcg/*.decode`),
///    lidos por {@link DecodeTreeSpec}.
/// 2. Para cada instrução, monta-se **um encoding representativo**: os bits fixos do padrão, e os
///    campos preenchidos com valores plausíveis (registradores baixos e distintos, `cond`=`AL`).
///    Várias estratégias de preenchimento são tentadas, porque um valor específico pode cair num
///    caso `UNPREDICTABLE` que o decoder rejeita com razão — basta UMA decodificar.
/// 3. Esse encoding é passado para o decoder REAL do arm-jitter, uma vez por arquitetura.
///
/// Ou seja: a coluna "temos?" não é opinião, é o que o decoder respondeu.
///
/// ## Limite honesto desta tabela
///
/// Ela mede **decode**, não correção semântica. Uma instrução marcada ✅ pode estar implementada
/// errada — foi exatamente o caso do `STREX` (task E3) e do `LDR/STR` alinhado (F3). O valor aqui é
/// eliminar a classe "não suporta" da lista de suspeitos, não provar que o resto está certo.
///
/// ## Licença
///
/// Os `.decode` do QEMU são **GPL** e este repositório é **BSD-3-Clause**: eles NÃO são versionados
/// aqui. O relatório os baixa para `target/isa-decode/` (ignorado pelo git) na primeira execução.
/// O que fica versionado é só a tabela gerada — mnemônicos e status, fatos do manual da ARM.
///
/// ## Uso
///
/// ```
/// mvn -o -pl core test-compile
/// java -cp core/target/classes;core/target/test-classes \
///      dev.vitorsilverio.armjitter.tools.IsaCoverageReport <dir-com-os-.decode> docs/COBERTURA-ISA.md
/// ```
public final class IsaCoverageReport {

    /// Um grupo do relatório: o arquivo de inventário, a largura do encoding e como sondá-lo.
    private record Group(String decodeFile, String title, int width, Probe probe, boolean simd, String note) {
    }

    /// Resultado da sondagem de uma instrução.
    private enum Status {
        /// O decoder reconheceu o encoding.
        SUPPORTED("✅"),
        /// O decoder devolveu `UNIMPLEMENTED` (ou lançou) — não suporta.
        MISSING("❌"),
        /// Decodificou, mas como OUTRA coisa: o encoding caiu num caminho genérico (tipicamente
        /// `COPROCESSOR`, que engole todo o espaço `cp10`/`cp11` de VFP/NEON). Não conta como
        /// suporte — conta como "o decoder não sabe recusar isto", que é um problema à parte.
        FALLBACK("⚠️");

        private final String mark;

        Status(String mark) {
            this.mark = mark;
        }
    }

    private enum Probe {
        /// Palavra ARM de 32 bits.
        ARM32,
        /// Halfword Thumb de 16 bits.
        THUMB16,
        /// Par de halfwords Thumb-2 (a primeira é a mais significativa do padrão).
        THUMB32,
        /// Palavra AArch64 de 32 bits.
        A64
    }

    private static final List<Group> GROUPS = List.of(
            new Group("a32.decode", "A32 — instruções ARM de 32 bits", 32, Probe.ARM32, false, ""),
            new Group("t16.decode", "T16 — Thumb clássico", 16, Probe.THUMB16, false, ""),
            new Group("t32.decode", "T32 — Thumb-2", 32, Probe.THUMB32, false, ""),
            new Group("vfp.decode", "VFP — ponto flutuante (condicional)", 32, Probe.ARM32, true, ""),
            new Group("vfp-uncond.decode", "VFP — formas incondicionais", 32, Probe.ARM32, true, ""),
            new Group("neon-dp.decode", "NEON — processamento de dados", 32, Probe.ARM32, true,
                    "SIMD Advanced (ARMv7-A NEON)."),
            new Group("neon-ls.decode", "NEON — load/store", 32, Probe.ARM32, true, ""),
            new Group("neon-shared.decode", "NEON — formas compartilhadas VFP/NEON", 32, Probe.ARM32, true, ""),
            new Group("m-nocp.decode", "ARMv7-M — coprocessador ausente", 32, Probe.THUMB32, true, ""),
            new Group("mve.decode", "MVE (Helium) — ARMv8.1-M", 32, Probe.THUMB32, true, ""),
            new Group("a64.decode", "A64 — AArch64", 32, Probe.A64, false, ""),
            new Group("sve.decode", "SVE/SVE2 — vetor escalável", 32, Probe.A64, false, ""),
            new Group("sme.decode", "SME — extensão matricial", 32, Probe.A64, false, ""));

    /// Arquiteturas de 32 bits sondadas, na ordem das colunas da tabela.
    private static final Map<String, ArmArchitecture> ARM_ARCHITECTURES = new LinkedHashMap<>();

    static {
        ARM_ARCHITECTURES.put("v4T", ArmArchitecture.ARMV4T);
        ARM_ARCHITECTURES.put("v5TE", ArmArchitecture.ARMV5TE);
        ARM_ARCHITECTURES.put("v6K", ArmArchitecture.ARMV6K);
        ARM_ARCHITECTURES.put("MPCore", ArmArchitecture.ARM11_MPCORE);
        ARM_ARCHITECTURES.put("v7-A", ArmArchitecture.ARMV7A);
        ARM_ARCHITECTURES.put("v6-M", ArmArchitecture.ARMV6M);
        ARM_ARCHITECTURES.put("v7-M", ArmArchitecture.ARMV7M);
    }

    /// Campos que são NÚMERO DE REGISTRADOR: preenchidos com valores baixos e distintos para não
    /// cair em `r15`/`UNPREDICTABLE`, que muitos encodings rejeitam legitimamente.
    private static final List<String> REGISTER_FIELDS = List.of(
            "rd", "rn", "rm", "rt", "rt2", "ra", "rs", "rdlo", "rdhi", "rd2", "vd", "vn", "vm",
            "qd", "qn", "qm", "zd", "zn", "zm", "za", "pd", "pn", "pm", "pg");

    /// Estratégias de preenchimento dos campos livres. Uma instrução conta como suportada se
    /// QUALQUER uma decodificar — ver o Javadoc da classe.
    private static final int[][] FILL_STRATEGIES = {
            {1, 2, 3, 4, 0},
            {1, 2, 3, 4, 1},
            {0, 0, 0, 0, 0},
            {2, 4, 6, 8, 2},
    };

    private IsaCoverageReport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: IsaCoverageReport <dir-com-os-.decode> <arquivo-markdown-de-saida>");
            System.exit(2);
            return;
        }
        Path decodeDirectory = Path.of(args[0]);
        StringBuilder report = new StringBuilder();
        appendHeader(report);
        List<String> summary = new ArrayList<>();

        for (Group group : GROUPS) {
            Path file = decodeDirectory.resolve(group.decodeFile());
            if (!Files.exists(file)) {
                summary.add("| " + group.title() + " | — | inventário ausente (`" + group.decodeFile() + "`) |");
                continue;
            }
            List<DecodeTreeSpec.Instruction> instructions = DecodeTreeSpec.parse(file, group.width());
            summary.add(appendGroup(report, group, instructions));
        }

        StringBuilder full = new StringBuilder();
        appendHeader(full);
        full.append("## Resumo\n\n");
        full.append("| Grupo | Instruções | Cobertura |\n|---|---:|---|\n");
        summary.forEach(line -> full.append(line).append('\n'));
        full.append('\n');
        full.append(report.substring(report.indexOf("<!--CORPO-->") + "<!--CORPO-->".length()));

        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, full.toString(), StandardCharsets.UTF_8);
        System.out.println("escrito: " + output.toAbsolutePath());
    }

    private static void appendHeader(StringBuilder report) {
        if (report.length() > 0) {
            return;
        }
        report.append("""
                # Cobertura de ISA do arm-jitter

                Tabela **gerada por medição**, não escrita à mão: cada instrução do inventário vira um
                encoding representativo que é passado para o decoder REAL do arm-jitter, uma vez por
                arquitetura. Regenerar com `dev.vitorsilverio.armjitter.tools.IsaCoverageReport` (ver o
                Javadoc dessa classe para o comando e para a questão de licença do inventário).

                | | significado |
                |---|---|
                | ✅ | o decoder reconhece o encoding |
                | ❌ | o decoder devolve `UNIMPLEMENTED` — falta implementar |
                | ⚠️ | decodifica como OUTRA coisa: o encoding de SIMD caiu no caminho genérico de coprocessador (`MCR`/`CDP`), que ocupa o mesmo espaço `cp10`/`cp11`. Não é suporte — é o decoder não sabendo recusar |

                **O que ✅ NÃO significa:** que a semântica está certa. `STREX` (E3) e `LDR/STR` alinhado
                (F3) decodificavam e estavam errados. Esta tabela elimina "não suporta" da lista de
                suspeitos; não prova o resto.

                **Colunas** — as arquiteturas que o `ArmArchitecture` oferece como preset: `v4T` (GBA),
                `v5TE` (NDS), `v6K`/`MPCore` (3DS), `v7-A` (armbox/virtual-arm-box), `v6-M`/`v7-M`
                (microcontrolador). `A64` é um decoder separado, sem presets.

                <!--CORPO-->
                """);
    }

    private static String appendGroup(StringBuilder report, Group group,
                                       List<DecodeTreeSpec.Instruction> instructions) {
        boolean aarch64 = group.probe() == Probe.A64;
        List<String> columns = aarch64 ? List.of("A64") : List.copyOf(ARM_ARCHITECTURES.keySet());

        Map<String, Integer> supportedPerColumn = new LinkedHashMap<>();
        columns.forEach(column -> supportedPerColumn.put(column, 0));

        StringBuilder rows = new StringBuilder();
        for (DecodeTreeSpec.Instruction instruction : instructions) {
            rows.append("| `").append(instruction.name()).append("` |");
            for (String column : columns) {
                Status status = aarch64
                        ? probeAarch64(instruction)
                        : probeArm(instruction, ARM_ARCHITECTURES.get(column), group);
                if (status == Status.SUPPORTED) {
                    supportedPerColumn.merge(column, 1, Integer::sum);
                }
                rows.append(' ').append(status.mark).append(" |");
            }
            rows.append('\n');
        }

        report.append("\n## ").append(group.title()).append('\n');
        if (!group.note().isEmpty()) {
            report.append('\n').append(group.note()).append('\n');
        }
        report.append("\nInventário: `").append(group.decodeFile()).append("` · ")
                .append(instructions.size()).append(" instruções.\n\n");
        report.append("| Instrução |");
        columns.forEach(column -> report.append(' ').append(column).append(" |"));
        report.append("\n|---|");
        columns.forEach(column -> report.append("---|"));
        report.append('\n').append(rows);

        StringBuilder coverage = new StringBuilder();
        for (String column : columns) {
            int supported = supportedPerColumn.get(column);
            int percent = instructions.isEmpty() ? 0 : supported * 100 / instructions.size();
            coverage.append(String.format(Locale.ROOT, "%s %d%% (%d) · ", column, percent, supported));
        }
        String trimmed = coverage.length() > 3 ? coverage.substring(0, coverage.length() - 3) : "—";
        return "| " + group.title() + " | " + instructions.size() + " | " + trimmed + " |";
    }

    /// A melhor de todas as estratégias de preenchimento: basta UMA decodificar (ver Javadoc da
    /// classe). `SUPPORTED` ganha de `FALLBACK`, que ganha de `MISSING`.
    private static Status probeArm(DecodeTreeSpec.Instruction instruction, ArmArchitecture architecture,
                                    Group group) {
        Status best = Status.MISSING;
        for (int[] strategy : FILL_STRATEGIES) {
            Status status = probeOnce(encode(instruction, strategy), architecture, group);
            if (status == Status.SUPPORTED) {
                return status;
            }
            if (status == Status.FALLBACK) {
                best = status;
            }
        }
        return best;
    }

    private static Status probeOnce(int word, ArmArchitecture architecture, Group group) {
        try {
            TestAddressSpace memory = new TestAddressSpace(8);
            DecodedInstruction decoded;
            if (group.probe() == Probe.THUMB16) {
                memory.put16(0, word & 0xFFFF);
                decoded = new ThumbDecoder(architecture).decode(memory, 0);
            } else if (group.probe() == Probe.THUMB32) {
                // Thumb-2: o padrão do decodetree é `hw1:hw2`, e na memória hw1 vem primeiro.
                memory.put16(0, (word >>> 16) & 0xFFFF);
                memory.put16(2, word & 0xFFFF);
                decoded = new ThumbDecoder(architecture).decode(memory, 0);
                // Sem Thumb-2 o decoder consome só a PRIMEIRA halfword e devolve outra instrução,
                // de 16 bits — isso não é suporte a esta instrução de 32 bits.
                if (decoded != null && decoded.raw() != word) {
                    return Status.MISSING;
                }
            } else {
                memory.put32(0, word);
                decoded = new ArmDecoder(architecture).decode(memory, 0);
                if (isUnconditionalSpace(word) && decodesTheSameIgnoringCondition(word, architecture)) {
                    // `cond == 0b1111` é o ESPAÇO INCONDICIONAL (NEON, PLD, BLX imediato, CPS...),
                    // não "condição 1111". Se trocar o nibble de condição por `AL` dá o mesmo
                    // resultado, o decoder simplesmente ignorou o campo e leu o encoding como se
                    // fosse a instrução condicional de mesmo padrão — misdecode, não suporte.
                    return Status.FALLBACK;
                }
            }
            if (decoded == null || decoded.kind() == InstructionKind.UNIMPLEMENTED) {
                return Status.MISSING;
            }
            // O espaço `cp10`/`cp11` (VFP/NEON/MVE) é o mesmo dos coprocessadores genéricos: um
            // encoding de SIMD que "decodifica" como COPROCESSOR caiu no caminho de MCR/CDP, não
            // é suporte de verdade.
            boolean genericCoprocessor = decoded.kind() == InstructionKind.COPROCESSOR
                    || decoded.kind() == InstructionKind.COPROCESSOR_DOUBLE;
            if (group.simd() && genericCoprocessor) {
                return Status.FALLBACK;
            }
            return Status.SUPPORTED;
        } catch (RuntimeException e) {
            return Status.MISSING;
        }
    }

    private static boolean isUnconditionalSpace(int word) {
        return (word >>> 28) == 0xF;
    }

    private static boolean decodesTheSameIgnoringCondition(int word, ArmArchitecture architecture) {
        try {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, word);
            DecodedInstruction unconditional = new ArmDecoder(architecture).decode(memory, 0);
            TestAddressSpace asAlways = new TestAddressSpace(8);
            asAlways.put32(0, (word & 0x0FFFFFFF) | (0xE << 28));
            DecodedInstruction always = new ArmDecoder(architecture).decode(asAlways, 0);
            return unconditional != null && always != null && unconditional.kind() == always.kind();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Status probeAarch64(DecodeTreeSpec.Instruction instruction) {
        for (int[] strategy : FILL_STRATEGIES) {
            int word = encode(instruction, strategy);
            try {
                TestAddressSpace raw = new TestAddressSpace(8);
                raw.put32(0, word);
                if (new Aarch64Decoder().decode(AddressSpace64.wrapping(raw), 0) != null) {
                    return Status.SUPPORTED;
                }
            } catch (RuntimeException e) {
                // `unsupported`: encoding fora da fatia implementada — tenta a próxima estratégia.
            }
        }
        return Status.MISSING;
    }

    /// Monta o encoding: bits fixos do padrão + campos preenchidos pela estratégia. `cond` é sempre
    /// `AL` (`0b1110`) quando livre — um `cond` inválido faria o decoder rejeitar por outro motivo.
    private static int encode(DecodeTreeSpec.Instruction instruction, int[] strategy) {
        int word = instruction.fixedOnes();
        int registerIndex = 0;
        for (Map.Entry<String, int[]> field : instruction.fields().entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            int value;
            if (name.equals("cond")) {
                value = 0b1110;
            } else if (REGISTER_FIELDS.contains(name)) {
                value = strategy[Math.min(registerIndex++, strategy.length - 2)];
            } else {
                value = strategy[strategy.length - 1];
            }
            word = writeField(word, field.getValue(), value, instruction.fixedZeros());
        }
        return word & ~instruction.fixedZeros() | instruction.fixedOnes();
    }

    /// `positions` está do bit mais significativo do campo para o menos significativo.
    private static int writeField(int word, int[] positions, int value, int fixedZeros) {
        int result = word;
        for (int i = 0; i < positions.length; i++) {
            int bit = positions[positions.length - 1 - i];
            if ((fixedZeros & (1 << bit)) != 0) {
                continue; // o padrão fixa este bit em 0: o campo não manda nele
            }
            if (((value >>> i) & 1) != 0) {
                result |= 1 << bit;
            } else {
                result &= ~(1 << bit);
            }
        }
        return result;
    }
}
