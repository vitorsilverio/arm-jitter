package dev.vitorsilverio.armjitter.tools;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
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
    private record Group(String decodeFile, String title, int width, Probe probe, boolean simd,
                          Applicability applicability, String note) {
    }

    /// A qual arquitetura este grupo de encodings **pertence**. Sem isto a tabela conta Thumb-2,
    /// NEON e MVE como "faltando" num ARM11 MPCore, que simplesmente NÃO TEM essas extensões — o
    /// denominador fica errado e a meta de "tudo ✅" vira inalcançável por construção.
    @FunctionalInterface
    private interface Applicability {
        boolean appliesTo(ArmArchitecture architecture);
    }

    /// Perfil A/R clássico: instruções ARM de 32 bits não existem no perfil M (só Thumb).
    private static final Applicability CLASSIC_ARM = arch -> !arch.has(ArmFeature.M_PROFILE);
    private static final Applicability ALWAYS = arch -> true;
    private static final Applicability THUMB2 = arch -> arch.has(ArmFeature.THUMB2);
    private static final Applicability VFP = arch -> arch.has(ArmFeature.VFPV2);
    private static final Applicability M_PROFILE = arch -> arch.has(ArmFeature.M_PROFILE);
    /// Extensões que NENHUM preset atual do `ArmArchitecture` declara — ficam na tabela para o
    /// inventário ser completo (não presumir que algo nunca será necessário), mas marcadas como
    /// não aplicáveis em vez de "faltando".
    private static final Applicability NOT_IN_ANY_PRESET = arch -> false;

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
            new Group("a32.decode", "A32 — instruções ARM de 32 bits", 32, Probe.ARM32, false, CLASSIC_ARM, ""),
            new Group("t16.decode", "T16 — Thumb clássico", 16, Probe.THUMB16, false, ALWAYS, ""),
            new Group("t32.decode", "T32 — Thumb-2", 32, Probe.THUMB32, false, THUMB2,
                    "Só existe a partir do ARMv6T2 — um ARM11 MPCore (ARMv6K) NÃO tem Thumb-2."),
            new Group("vfp.decode", "VFP — ponto flutuante (condicional)", 32, Probe.ARM32, true, VFP,
                    "As formas `_hp` (meia precisão) são ARMv8.2-FP16, não VFPv2/v3."),
            new Group("vfp-uncond.decode", "VFP — formas incondicionais (ARMv8-A)", 32, Probe.ARM32, true,
                    NOT_IN_ANY_PRESET, "`VSEL`/`VMAXNM`/`VMINNM`/`VRINT`/`VCVTA` são ARMv8-A de 32 bits."),
            new Group("neon-dp.decode", "NEON — processamento de dados", 32, Probe.ARM32, true,
                    NOT_IN_ANY_PRESET, "Advanced SIMD: extensão OPCIONAL do ARMv7-A; nenhum preset a declara hoje."),
            new Group("neon-ls.decode", "NEON — load/store", 32, Probe.ARM32, true, NOT_IN_ANY_PRESET, ""),
            new Group("neon-shared.decode", "NEON — formas compartilhadas VFP/NEON", 32, Probe.ARM32, true,
                    NOT_IN_ANY_PRESET, ""),
            new Group("m-nocp.decode", "ARMv7-M — coprocessador ausente", 32, Probe.THUMB32, true, M_PROFILE, ""),
            new Group("mve.decode", "MVE (Helium) — ARMv8.1-M", 32, Probe.THUMB32, true, NOT_IN_ANY_PRESET, ""),
            new Group("a64.decode", "A64 — AArch64", 32, Probe.A64, false, ALWAYS, ""),
            new Group("sve.decode", "SVE/SVE2 — vetor escalável", 32, Probe.A64, false, NOT_IN_ANY_PRESET,
                    "Extensão opcional do ARMv8.2+; o Cortex-A53 do Raspberry Pi 3 NÃO tem SVE."),
            new Group("sme.decode", "SME — extensão matricial", 32, Probe.A64, false, NOT_IN_ANY_PRESET,
                    "Extensão opcional do ARMv9; nenhum alvo atual a tem."));

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

    /// Arquiteturas A64 sondadas, uma coluna por versão ARM (B11.5) — mesma UX de
    /// {@link #ARM_ARCHITECTURES} para o lado de 32 bits, usando os presets de
    /// {@link Aarch64Architecture} (B11.1) e o mapeamento mnemônico→feature de
    /// {@link #AARCH64_VERSION_REQUIREMENTS} (curadoria de B11.3).
    private static final Map<String, Aarch64Architecture> AARCH64_ARCHITECTURES = new LinkedHashMap<>();

    static {
        AARCH64_ARCHITECTURES.put("ARMv8.0-A", Aarch64Architecture.ARMV8_0_A);
        AARCH64_ARCHITECTURES.put("ARMv8.1-A", Aarch64Architecture.ARMV8_1_A);
        AARCH64_ARCHITECTURES.put("ARMv8.2-A", Aarch64Architecture.ARMV8_2_A);
        AARCH64_ARCHITECTURES.put("ARMv8.3-A", Aarch64Architecture.ARMV8_3_A);
        AARCH64_ARCHITECTURES.put("ARMv8.4-A", Aarch64Architecture.ARMV8_4_A);
        AARCH64_ARCHITECTURES.put("ARMv8.5-A", Aarch64Architecture.ARMV8_5_A);
        AARCH64_ARCHITECTURES.put("ARMv8.6-A", Aarch64Architecture.ARMV8_6_A);
        AARCH64_ARCHITECTURES.put("ARMv8.7-A", Aarch64Architecture.ARMV8_7_A);
        AARCH64_ARCHITECTURES.put("ARMv8.8-A", Aarch64Architecture.ARMV8_8_A);
        AARCH64_ARCHITECTURES.put("ARMv8.9-A", Aarch64Architecture.ARMV8_9_A);
        AARCH64_ARCHITECTURES.put("ARMv9.0-A", Aarch64Architecture.ARMV9_0_A);
        AARCH64_ARCHITECTURES.put("ARMv9.1-A", Aarch64Architecture.ARMV9_1_A);
        AARCH64_ARCHITECTURES.put("ARMv9.2-A", Aarch64Architecture.ARMV9_2_A);
        AARCH64_ARCHITECTURES.put("ARMv9.3-A", Aarch64Architecture.ARMV9_3_A);
        AARCH64_ARCHITECTURES.put("ARMv9.4-A", Aarch64Architecture.ARMV9_4_A);
        AARCH64_ARCHITECTURES.put("ARMv9.5-A", Aarch64Architecture.ARMV9_5_A);
    }

    /// Mnemônico → feature mínima real que o exige (curadoria de B11.3, `## Achados`, seção 4).
    /// Um mnemônico ausente daqui é ARMv8.0-A baseline — mesma regra de "só exceções entram na
    /// lista" que `docs/isa-nao-aplicavel.tsv` já usa. **Isto não gateia o decoder** (B11.5 não
    /// inclui gatear — só `FEAT_RDM`, via B11.4, é consultado de verdade pelo `Aarch64Decoder`):
    /// serve só para decidir em qual COLUNA de versão esta instrução é aplicável/contada, igual à
    /// curadoria de `docs/isa-nao-aplicavel.tsv` para as arquiteturas de 32 bits.
    private static final Map<String, Aarch64Feature> AARCH64_VERSION_REQUIREMENTS = new LinkedHashMap<>();

    static {
        AARCH64_VERSION_REQUIREMENTS.put("CAS", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("CASP", Aarch64Feature.LSE);
        // B19.1: atômicos de memória `FEAT_LSE` (ARMv8.1-A) + `LDAPR` `FEAT_LRCPC` (ARMv8.3-A).
        AARCH64_VERSION_REQUIREMENTS.put("LDADD", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDCLR", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDEOR", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDSET", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDSMAX", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDSMIN", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDUMAX", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDUMIN", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("SWP", Aarch64Feature.LSE);
        AARCH64_VERSION_REQUIREMENTS.put("LDAPR", Aarch64Feature.LRCPC);
        AARCH64_VERSION_REQUIREMENTS.put("MSR_i_PAN", Aarch64Feature.PAN);
        AARCH64_VERSION_REQUIREMENTS.put("MSR_i_UAO", Aarch64Feature.UAO);
        AARCH64_VERSION_REQUIREMENTS.put("MSR_i_DIT", Aarch64Feature.DIT);
        AARCH64_VERSION_REQUIREMENTS.put("MSR_i_ALLINT", Aarch64Feature.NMI);
        AARCH64_VERSION_REQUIREMENTS.put("CFINV", Aarch64Feature.FLAG_MANIPULATION);
        AARCH64_VERSION_REQUIREMENTS.put("RMIF", Aarch64Feature.FLAG_MANIPULATION);
        AARCH64_VERSION_REQUIREMENTS.put("SETF8", Aarch64Feature.FLAG_MANIPULATION);
        AARCH64_VERSION_REQUIREMENTS.put("SETF16", Aarch64Feature.FLAG_MANIPULATION);
        AARCH64_VERSION_REQUIREMENTS.put("AXFLAG", Aarch64Feature.FLAG_MANIPULATION_2);
        AARCH64_VERSION_REQUIREMENTS.put("XAFLAG", Aarch64Feature.FLAG_MANIPULATION_2);
        AARCH64_VERSION_REQUIREMENTS.put("WFET", Aarch64Feature.WFXT);
        AARCH64_VERSION_REQUIREMENTS.put("WFIT", Aarch64Feature.WFXT);
        AARCH64_VERSION_REQUIREMENTS.put("SHA512H", Aarch64Feature.SHA512);
        AARCH64_VERSION_REQUIREMENTS.put("SHA512H2", Aarch64Feature.SHA512);
        AARCH64_VERSION_REQUIREMENTS.put("SHA512SU0", Aarch64Feature.SHA512);
        AARCH64_VERSION_REQUIREMENTS.put("SHA512SU1", Aarch64Feature.SHA512);
        AARCH64_VERSION_REQUIREMENTS.put("SM3SS1", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM3TT1A", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM3TT1B", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM3TT2A", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM3TT2B", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM3PARTW1", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM3PARTW2", Aarch64Feature.SM3);
        AARCH64_VERSION_REQUIREMENTS.put("SM4E", Aarch64Feature.SM4);
        AARCH64_VERSION_REQUIREMENTS.put("SM4EKEY", Aarch64Feature.SM4);
        AARCH64_VERSION_REQUIREMENTS.put("EOR3", Aarch64Feature.SHA3);
        AARCH64_VERSION_REQUIREMENTS.put("RAX1", Aarch64Feature.SHA3);
        AARCH64_VERSION_REQUIREMENTS.put("XAR", Aarch64Feature.SHA3);
        AARCH64_VERSION_REQUIREMENTS.put("BCAX", Aarch64Feature.SHA3);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLAH_v", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLSH_v", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLAH_s", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLSH_s", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLAH_vi", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLSH_vi", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLAH_si", Aarch64Feature.RDM);
        AARCH64_VERSION_REQUIREMENTS.put("SQRDMLSH_si", Aarch64Feature.RDM);
    }

    /// `docs/COBERTURA-ISA.md` pré-B11.5 tratava A64 como uma única coluna chamada `A64` — vários
    /// `docs/isa-nao-aplicavel.tsv` legados usam essa string. Para não reescrever essas linhas,
    /// uma exclusão `A64` casa com QUALQUER coluna de versão A64 nova (uma extensão sem entrada em
    /// {@link #AARCH64_VERSION_REQUIREMENTS} — ainda não mapeada por versão real — fica de fora de
    /// toda a tabela por versão, igual ficava fora da coluna monolítica antiga).
    private static boolean isAarch64VersionColumn(String column) {
        return AARCH64_ARCHITECTURES.containsKey(column);
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
            // B9.7: achado real — nenhuma das 4 estratégias acima produz >=2 bits setados num
            // campo NÃO-registrador (ex. `list:16` de LDM/STM), então qualquer instrução cuja
            // UNPREDICTABLE-check exija >=2 bits (`Thumb2LoadStoreDecoder#decodeMultipleTransfer`,
            // `Integer.bitCount(mask) < 2`) sempre reportava ❌ mesmo já implementada e testada
            // (`STM_t32`/`LDM_t32`, confirmado por `Thumb2LoadStoreDecoderTest` linhas 668+) — falso
            // negativo do MEDIDOR, não da implementação. `6` (0b110) garante 2 bits num campo de
            // 16 bits sem quebrar campos de 1 bit já cobertos por outras estratégias (0/1 acima).
            {1, 2, 3, 4, 6},
    };

    private IsaCoverageReport() {
    }

    /// Exclusões curadas (`docs/isa-nao-aplicavel.tsv`): instrução → arquiteturas em que ela NÃO
    /// existe. Ver o cabeçalho daquele arquivo para o formato e a regra de curadoria.
    private static final List<Exclusion> EXCLUSIONS = new ArrayList<>();
    /// Acumulador do progresso global: arquitetura → `{suportadas, aplicáveis}`.
    private static final Map<String, int[]> globalPerArchitecture = new LinkedHashMap<>();
    private static int totalSupported;
    private static int totalApplicable;

    /// `grupo` restringe a exclusão a um `Group#decodeFile()` específico (ex.: `t32.decode`) —
    /// necessário porque um mnemônico pode existir em MAIS de um arquivo `.decode` com o MESMO
    /// nome (ex.: `REV`/`NOP`/`B_cond_thumb` existem tanto em `t16.decode` quanto em `t32.decode`,
    /// como formas de 16 e 32 bits distintas). Sem essa coluna, a exclusão casa por nome sozinho e
    /// excluiria as duas formas juntas — ver B9.15.
    ///
    /// `ocorrencia` restringe a exclusão a uma linha ESPECÍFICA entre várias com o MESMO nome no
    /// MESMO arquivo (ex.: `VMOV_to_gp`/`VMOV_from_gp` em `vfp.decode` têm 3 linhas cada — byte/
    /// halfword/word — todas com o mesmo mnemônico; `grupo` sozinho não distingue porque as 3 estão
    /// no mesmo `.decode`, ver B9.17). Valor é a posição 1-based da linha entre as de mesmo nome+
    /// arquivo, na ordem em que aparecem no `.decode`. `null`/vazio = casa em QUALQUER ocorrência
    /// (formato legado, mantido para as linhas existentes que não precisam distinguir).
    private record Exclusion(String pattern, List<String> architectures, String reason, String grupo,
                              String ocorrencia) {
        boolean matches(String instruction, String column, String decodeFile, int occurrence) {
            boolean nameMatches = pattern.endsWith("*")
                    ? instruction.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.startsWith("*")
                        ? instruction.endsWith(pattern.substring(1))
                        : instruction.equals(pattern);
            boolean columnMatches = architectures.contains("*") || architectures.contains(column)
                    || (architectures.contains("A64") && isAarch64VersionColumn(column));
            boolean groupMatches = grupo.isEmpty() || grupo.equals(decodeFile);
            boolean occurrenceMatches = ocorrencia.isEmpty() || ocorrencia.equals(Integer.toString(occurrence));
            return nameMatches && columnMatches && groupMatches && occurrenceMatches;
        }
    }

    private static void loadExclusions(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t");
            if (columns.length < 3) {
                continue;
            }
            EXCLUSIONS.add(new Exclusion(columns[0].trim(),
                    List.of(columns[1].trim().split(",")), columns[2].trim(),
                    columns.length > 3 ? columns[3].trim() : "",
                    columns.length > 4 ? columns[4].trim() : ""));
        }
    }

    private static boolean isExcluded(String instruction, String column, String decodeFile, int occurrence) {
        return EXCLUSIONS.stream().anyMatch(exclusion -> exclusion.matches(instruction, column, decodeFile, occurrence));
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: IsaCoverageReport <dir-com-os-.decode> <arquivo-markdown-de-saida>");
            System.exit(2);
            return;
        }
        Path decodeDirectory = Path.of(args[0]);
        globalPerArchitecture.clear();
        totalSupported = 0;
        totalApplicable = 0;
        loadExclusions(Path.of(args.length > 2 ? args[2] : "docs/isa-nao-aplicavel.tsv"));
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
        full.append("## Progresso global\n\n");
        full.append("Contadas todas as células (instrução × arquitetura) **aplicáveis**. É este número\n");
        full.append("que dispara o release do arm-jitter no Maven Central — ver `tasks/README.md`,\n");
        full.append("secão \"Marcos de cobertura de ISA\".\n\n");
        full.append(String.format(Locale.ROOT, "> **%d%%** — %d de %d células aplicáveis decodificam.%n%n",
                totalApplicable == 0 ? 0 : totalSupported * 100 / totalApplicable,
                totalSupported, totalApplicable));
        full.append("Por arquitetura:\n\n| Arquitetura | Cobertura |\n|---|---|\n");
        for (Map.Entry<String, int[]> entry : globalPerArchitecture.entrySet()) {
            int[] counters = entry.getValue();
            full.append(String.format(Locale.ROOT, "| %s | **%d%%** (%d/%d) |%n", entry.getKey(),
                    counters[1] == 0 ? 0 : counters[0] * 100 / counters[1], counters[0], counters[1]));
        }
        full.append("\n## Resumo\n\n");
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
                | · | **não se aplica**: o grupo não faz parte daquela arquitetura, ou a instrução é de uma versão POSTERIOR (lista curada em `docs/isa-nao-aplicavel.tsv`, com a versão que a introduziu). Não conta como falta. Ver ali a regra de curadoria: na dúvida a instrução fica ❌ e vira trabalho | ⚠️ | decodifica como OUTRA coisa: o encoding de SIMD caiu no caminho genérico de coprocessador (`MCR`/`CDP`), que ocupa o mesmo espaço `cp10`/`cp11`. Não é suporte — é o decoder não sabendo recusar |

                **O que ✅ NÃO significa:** que a semântica está certa. `STREX` (E3) e `LDR/STR` alinhado
                (F3) decodificavam e estavam errados. Esta tabela elimina "não suporta" da lista de
                suspeitos; não prova o resto.

                **Colunas** — as arquiteturas que o `ArmArchitecture` oferece como preset: `v4T` (GBA),
                `v5TE` (NDS), `v6K`/`MPCore` (3DS), `v7-A` (armbox/virtual-arm-box), `v6-M`/`v7-M`
                (microcontrolador). O grupo `a64.decode` (B11.5) tem uma coluna por versão real
                (`ARMv8.0-A`...`ARMv9.5-A`, presets de `Aarch64Architecture`, B11.1); um mnemônico só
                é aplicável a partir da versão que a introduz (curadoria de B11.3, não o decoder —
                só `FEAT_RDM` é gateado de verdade hoje, ver B11.4). `sve.decode`/`sme.decode`
                continuam uma coluna monolítica `A64` (nada decodifica ainda).

                <!--CORPO-->
                """);
    }

    private static String appendGroup(StringBuilder report, Group group,
                                       List<DecodeTreeSpec.Instruction> instructions) {
        boolean aarch64 = group.probe() == Probe.A64;
        // B11.5: só `a64.decode` (o único grupo A64 com `applicability() != NOT_IN_ANY_PRESET`,
        // ver GROUPS) ganha colunas por versão — `sve.decode`/`sme.decode` continuam "não se
        // aplica a nenhum preset" (nada decodifica hoje, versionar não traria informação nova).
        boolean aarch64Versioned = aarch64 && group.applicability() != NOT_IN_ANY_PRESET;
        List<String> columns = aarch64Versioned ? List.copyOf(AARCH64_ARCHITECTURES.keySet())
                : aarch64 ? List.of("A64") : List.copyOf(ARM_ARCHITECTURES.keySet());

        Map<String, Integer> supportedPerColumn = new LinkedHashMap<>();
        Map<String, Integer> applicablePerColumn = new LinkedHashMap<>();
        columns.forEach(column -> {
            supportedPerColumn.put(column, 0);
            applicablePerColumn.put(column, 0);
        });

        StringBuilder rows = new StringBuilder();
        Map<String, Integer> occurrenceCounts = new LinkedHashMap<>();
        for (DecodeTreeSpec.Instruction instruction : instructions) {
            int occurrence = occurrenceCounts.merge(instruction.name(), 1, Integer::sum);
            rows.append("| `").append(instruction.name()).append("` |");
            for (String column : columns) {
                ArmArchitecture architecture = aarch64 ? null : ARM_ARCHITECTURES.get(column);
                Aarch64Architecture aarch64Architecture = aarch64Versioned ? AARCH64_ARCHITECTURES.get(column) : null;
                boolean applicable = aarch64
                        ? (aarch64Versioned
                                ? isApplicableToAarch64Version(instruction.name(), aarch64Architecture)
                                : group.applicability() != NOT_IN_ANY_PRESET)
                        : group.applicability().appliesTo(architecture);
                if (!applicable || isExcluded(instruction.name(), column, group.decodeFile(), occurrence)) {
                    rows.append(" · |");
                    continue;
                }
                applicablePerColumn.merge(column, 1, Integer::sum);
                globalPerArchitecture.computeIfAbsent(column, unused -> new int[2])[1]++;
                totalApplicable++;
                Status status = aarch64
                        ? probeAarch64(instruction, aarch64Architecture)
                        : probeArm(instruction, architecture, group);
                if (status == Status.SUPPORTED) {
                    supportedPerColumn.merge(column, 1, Integer::sum);
                    globalPerArchitecture.get(column)[0]++;
                    totalSupported++;
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
            int applicable = applicablePerColumn.get(column);
            if (applicable == 0) {
                continue; // grupo não pertence a esta arquitetura: não entra na conta
            }
            int supported = supportedPerColumn.get(column);
            coverage.append(String.format(Locale.ROOT, "%s %d%% (%d/%d) · ",
                    column, supported * 100 / applicable, supported, applicable));
        }
        String trimmed = coverage.length() > 3 ? coverage.substring(0, coverage.length() - 3)
                : "não se aplica a nenhum preset atual";
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

    /// `architecture` é `null` para os grupos A64 não versionados ainda (`sve.decode`/
    /// `sme.decode`, ver {@link #appendGroup}) — nesse caso usa o decoder default (B11.2:
    /// equivalente a `ARMV8_0_A`, mesmo comportamento de antes de B11.5).
    private static Status probeAarch64(DecodeTreeSpec.Instruction instruction, Aarch64Architecture architecture) {
        Aarch64Decoder decoder = architecture == null ? new Aarch64Decoder() : new Aarch64Decoder(architecture);
        for (int[] strategy : FILL_STRATEGIES) {
            int word = encode(instruction, strategy);
            try {
                TestAddressSpace raw = new TestAddressSpace(8);
                raw.put32(0, word);
                if (decoder.decode(AddressSpace64.wrapping(raw), 0) != null) {
                    return Status.SUPPORTED;
                }
            } catch (RuntimeException e) {
                // `unsupported`: encoding fora da fatia implementada — tenta a próxima estratégia.
            }
        }
        return Status.MISSING;
    }

    /// Aplicabilidade curada por versão A64 (B11.5, ver {@link #AARCH64_VERSION_REQUIREMENTS}):
    /// um mnemônico sem entrada é ARMv8.0-A baseline (aplicável a toda coluna); um mnemônico
    /// mapeado só é aplicável a partir da versão que introduziu a feature real que ele exige.
    private static boolean isApplicableToAarch64Version(String instruction, Aarch64Architecture architecture) {
        Aarch64Feature required = AARCH64_VERSION_REQUIREMENTS.get(instruction);
        return required == null || architecture.has(required);
    }

    /// Monta o encoding: bits fixos do padrão + campos preenchidos pela estratégia. `cond` é sempre
    /// `AL` (`0b1110`) quando livre — um `cond` inválido faria o decoder rejeitar por outro motivo.
    ///
    /// **Exceção real, achada pela B9.4**: `B_cond_thumb` (`t16.decode`) também tem um campo
    /// chamado `cond`, mas ali o nibble NÃO é o campo de condição de 4 bits do resto da ISA — é o
    /// codificador do Thumb1 `B<cond>` de 16 bits (ARM DDI 0406C A8.8.4), cujos únicos valores
    /// válidos são `0b0000`-`0b1101` (as 14 condições reais); `0b1110` é reservado e permanentemente
    /// indefinido (é, byte a byte, o mesmo encoding de `UDF #0`, ARM DDI 0406C A8.8.247 — por isso
    /// `ThumbDecoder` intercepta `0xDE00`-`0xDEFF` ANTES de chegar no dispatch de `B_cond_thumb`) e
    /// `0b1111` é `SVC`. Forçar `cond=AL` (`0b1110`) para esta instrução específica não sonda "branch
    /// sempre", sonda o encoding reservado de `UDF` — falso negativo puro da ferramenta, não um gap
    /// de decode real (`ThumbDecoder` já decodifica `B<cond>` corretamente para as 14 condições
    /// válidas, ver o teste de regressão). `EQ` (`0b0000`) é sempre válido para este mnemônico.
    private static int encode(DecodeTreeSpec.Instruction instruction, int[] strategy) {
        int word = instruction.fixedOnes();
        int registerIndex = 0;
        boolean thumbConditionalBranch = instruction.name().equalsIgnoreCase("B_cond_thumb");
        for (Map.Entry<String, int[]> field : instruction.fields().entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            int value;
            if (name.equals("cond")) {
                value = thumbConditionalBranch ? 0b0000 : 0b1110;
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
