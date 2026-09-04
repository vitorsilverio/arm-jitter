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
/// ## Reprodutibilidade (E11)
///
/// O inventário é medido contra uma **revisão fixada** do QEMU (um SHA de commit, não `master`),
/// definida por `QEMU_REV` em `gerar-cobertura-isa.sh` e gravada em `target/isa-decode/.rev`. A
/// revisão usada é recebida como 4º argumento (ou lida do `.rev`) e escrita no cabeçalho de
/// `docs/COBERTURA-ISA.md`, para toda sessão saber contra o que a tabela vigente foi medida.
///
/// ## Uso
///
/// ```
/// mvn -o -pl core test-compile
/// java -cp core/target/classes;core/target/test-classes \
///      dev.vitorsilverio.armjitter.tools.IsaCoverageReport <dir-com-os-.decode> docs/COBERTURA-ISA.md [<tsv>] [<qemu-rev>]
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
    ///
    /// Este mapa casa pelo NOME do mnemônico — vale quando TODAS as linhas do mnemônico exigem a
    /// mesma feature (`CAS`/`LDADD`/`RMIF`/…). Quando um mesmo nome tem uma linha de feature e
    /// outra de ISA base (o caso de `FEAT_FP16`: `FADD_v` `_h` é ARMv8.2-A, `FADD_v` `_sd` é
    /// baseline), o requisito precisa ser por LINHA — ver
    /// {@link #AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE} (B19.5.2, espelho A64 da coluna
    /// `ocorrencia` que a B9.17 acrescentou ao lado de 32 bits).
    static final Map<String, Aarch64Feature> AARCH64_VERSION_REQUIREMENTS = new LinkedHashMap<>();

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

    /// Registra o requisito de versão de vários mnemônicos de uma vez (E12). Recusa registrar o
    /// MESMO nome com features DIFERENTES — seria um erro de curadoria silencioso, e a última
    /// chamada venceria sem ninguém perceber.
    private static void require(Aarch64Feature feature, String... names) {
        for (String name : names) {
            Aarch64Feature previous = AARCH64_VERSION_REQUIREMENTS.put(name, feature);
            if (previous != null && previous != feature) {
                throw new IllegalStateException(
                        "requisito conflitante para " + name + ": " + previous + " vs " + feature);
            }
        }
    }

    // ── E12 — a curadoria de versão A64 que vivia em `docs/isa-nao-aplicavel.tsv` ────────────────
    //
    // As 111 linhas com arquitetura `A64` daquele arquivo mediam `·` nas 16 colunas de versão,
    // inclusive naquelas em que a feature EXISTE e a instrução é trabalho PENDENTE — porque a TSV
    // nasceu quando o A64 era uma coluna monolítica e `isAarch64VersionColumn` (removido por esta
    // task) fazia `A64` casar com qualquer coluna nova. Elas cobriam 134 linhas da tabela.
    //
    // Aqui o requisito deriva as colunas via `architecture.has(feature)`, então continua correto
    // sozinho quando uma coluna de versão nova é acrescentada — que é a razão de esta ser a única
    // fonte da verdade para versão A64 (mesma decisão da B19.5.2, agora aplicada ao resto).
    //
    // Casar por NOME é seguro para TODAS estas linhas — medido pela E12: nenhuma tem irmã de mesmo
    // nome com célula `✅` que seria apagada, e nenhuma usava a coluna `ocorrencia` da TSV. NÃO é
    // uma regra geral: a B19.5.2 precisou de OCORRÊNCIA porque 84 das 96 linhas `_h` dela TÊM irmã
    // `✅` (ver `AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE`). Se esta lista mudar, remedir.
    //
    // `SHA512SU0`/`SM3SS1`/`SM3TT1A`/`SM3TT1B`/`SM3TT2A`/`SM3TT2B`/`SM4E` NÃO aparecem abaixo: já
    // estavam no mapa acima, e a linha TSV correspondente era pura redundância (removida).
    static {
        // FEAT_CRC32 (ARMv8.1-A) — checksum CRC-32/CRC-32C sobre GPR (E12: estava escondida por uma linha `*`)
        require(Aarch64Feature.CRC32, "CRC32", "CRC32C");
        // FEAT_FP16 (ARMv8.2-A) — meia precisão FORA do escopo `_h` da B19.5.2 (FMOV/FCVT escalares)
        require(Aarch64Feature.FP16, "FMOV_xh", "FMOV_hx", "FCVT_s_hs", "FCVT_s_hd", "FCVT_s_sh",
                "FCVT_s_dh");
        // FEAT_DotProd (ARMv8.2-A) — produto escalar SDOT/UDOT
        require(Aarch64Feature.DOT_PRODUCT, "SDOT_v", "SDOT_vi", "UDOT_v", "UDOT_vi");
        // FEAT_JSCVT (ARMv8.3-A) — FJCVTZS (conversão com semântica de Javascript)
        require(Aarch64Feature.JAVASCRIPT_CONVERT, "FJCVTZS");
        // FEAT_FCMA (ARMv8.3-A) — aritmética de número complexo (FCADD/FCMLA)
        require(Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC, "FCADD_90", "FCADD_270", "FCMLA_v", "FCMLA_vi");
        // FEAT_PAuth (ARMv8.3-A) — autenticação de ponteiro (formas com efeito REAL; as hint-space
        // `PACIASP`/`AUTDZA`/… são RES NOP sem a feature e já decodificam desde B6.6.7)
        require(Aarch64Feature.POINTER_AUTHENTICATION, "BRAZ", "BLRAZ", "RETA", "BRA", "BLRA", "ERETA",
                "AUTDA", "XPACD", "XPACI", "LDRA");
        // FEAT_LRCPC2 (ARMv8.4-A) — LDAPUR/STLUR (RCpc com offset imediato). `LDAPR_i` tem 6 linhas
        // no inventário e todas as 6 são desta feature — por isso casar por nome está certo aqui.
        require(Aarch64Feature.LRCPC2, "LDAPR_i", "STLR_i");
        // FEAT_FRINTTS (ARMv8.5-A) — arredondamento dirigido p/ inteiro de 32/64 bits
        require(Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL, "FRINT32Z_s", "FRINT32X_s", "FRINT64Z_s",
                "FRINT64X_s", "FRINT32Z_v", "FRINT32X_v", "FRINT64Z_v", "FRINT64X_v");
        // FEAT_MTE2 (ARMv8.5-A) — Memory Tagging Extension
        require(Aarch64Feature.MEMORY_TAGGING, "STG", "LDG", "STZG", "ST2G", "STZ2G", "STGM", "LDGM",
                "STZGM", "STGP", "GMI", "IRG", "SUBP", "SUBPS", "SETGP", "SETGM", "SETGE");
        // FEAT_BF16 (ARMv8.6-A) — bfloat16
        require(Aarch64Feature.BFLOAT16, "BFCVT_s", "BFDOT_v", "BFDOT_vi", "BFMMLA", "BFMLAL_vi");
        // FEAT_I8MM (ARMv8.6-A) — matriz inteira de 8 bits
        require(Aarch64Feature.INT8_MATRIX_MULTIPLY, "USDOT_v", "USDOT_vi", "SUDOT_vi", "SMMLA", "UMMLA",
                "USMMLA");
        // FEAT_MOPS (ARMv8.8-A) — memcpy/memset acelerados
        require(Aarch64Feature.MEMORY_COPY_SET, "CPYE", "CPYM", "CPYP", "SETP", "SETM", "SETE", "CPYFP",
                "CPYFM", "CPYFE");
        // FEAT_CSSC (ARMv8.9-A) — Common Short Sequence Compression
        require(Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION, "CTZ", "SMAX", "SMIN", "UMAX", "UMIN");
        // FEAT_SME (ARMv9.2-A) — MSR SVCR (estado streaming-SVE/ZA)
        require(Aarch64Feature.SCALABLE_MATRIX_EXTENSION, "MSR_i_SVCR");
        // FEAT_GCS (ARMv9.4-A) — Guarded Control Stack
        require(Aarch64Feature.GUARDED_CONTROL_STACK, "GCSSTR");
        // FEAT_FAMINMAX (ARMv9.4-A) — máximo/mínimo de valor absoluto
        require(Aarch64Feature.FP_ABSOLUTE_MAX_MIN, "FAMAX", "FAMIN");
        // FEAT_LSE128 (Armv9.4-A) — atômicos de 128 bits. ⚠️ a TSV dizia `ARMv8.9-A`: ERRADO, e a
        // diferença é observável porque `ARMV9_4_A` estende `ARMV8_9_A`.
        require(Aarch64Feature.LSE128, "LDCLRP", "LDSETP", "SWPP");
        // FEAT_FP8 (Armv9.5-A) — conversão/escala fp8. `FSCALE` tem 2 linhas (`@qrrr_h`/`@qrrr_sd`),
        // as duas desta feature.
        require(Aarch64Feature.FP8, "FSCALE");
        // FEAT_FP8DOT2 (Armv9.5-A) — produto escalar fp8 2 vias → f16 (a TSV dizia `FEAT_F8DP2`,
        // nome inventado)
        require(Aarch64Feature.FP8_DOT_PRODUCT_2WAY, "FDOT_hb_v", "FDOT_hb_vi");
        // FEAT_FP8DOT4 (Armv9.5-A) — produto escalar fp8 4 vias → f32 (a TSV dizia `FEAT_F8DP4`)
        require(Aarch64Feature.FP8_DOT_PRODUCT_4WAY, "FDOT_sb_v", "FDOT_sb_vi");
        // FEAT_CMPBR (ARMv9.5-A) — CB<cc> compare-and-branch condicional
        require(Aarch64Feature.COMPARE_AND_BRANCH, "CB_cond", "CB_cond_imm");
        // FEAT_FPRCVT (Armv9.6-A) — conversão FP↔int só em registrador SIMD&FP escalar. Nenhum
        // preset declara esta feature (a tabela vai até ARMv9.5-A) ⇒ `·` nas 16 colunas, pelo
        // mecanismo certo: viram `❌` sozinhas quando existir uma coluna ARMv9.6-A.
        require(Aarch64Feature.FP_INTEGER_CONVERT_SCALAR, "SCVTF_simd", "UCVTF_simd", "FCVTAS_g_simd",
                "FCVTAU_g_simd", "FCVTMS_g_simd", "FCVTMU_g_simd", "FCVTNS_g_simd", "FCVTNU_g_simd",
                "FCVTPS_g_simd", "FCVTPU_g_simd", "FCVTZS_g_simd", "FCVTZU_g_simd");
        // FEAT_F8F16MM (Armv9.6-A) — matriz fp8 → f16 (a TSV dizia `FEAT_F8MM8`, nome inventado)
        require(Aarch64Feature.FP8_MATRIX_MULTIPLY_FP16, "FMMLA_hb");
        // FEAT_F8F32MM (Armv9.6-A) — matriz fp8 → f32 (a TSV dizia `FEAT_F8MM4`, nome inventado)
        require(Aarch64Feature.FP8_MATRIX_MULTIPLY_FP32, "FMMLA_sb");
    }

    /// Requisito de versão A64 casado por **(NOME, OCORRÊNCIA)** — a chave é `"NOME#n"`, onde `n` é
    /// a posição 1-based da linha entre as de mesmo nome no inventário `a64.decode` (a MESMA
    /// ocorrência que {@link #appendGroup} já computa para as exclusões da TSV). É o espelho A64 da
    /// coluna `ocorrencia` que a B9.17 acrescentou à `docs/isa-nao-aplicavel.tsv` do lado de 32
    /// bits, e resolve a limitação de {@link #AARCH64_VERSION_REQUIREMENTS}: um mesmo mnemônico
    /// pode ter uma linha `_h` de meia precisão (ARMv8.2-A/`FEAT_FP16`) e uma linha `_sd`/`_s` de
    /// ISA base — marcar por nome derrubaria a linha base, que hoje é `✅`.
    ///
    /// **`_h` de TEMPLATE ≠ meia precisão** (B19.5.2, medido): das 157 linhas cujo template termina
    /// em `_h` (`@rr_h`/`@rrr_h`/`@rrx_h`/`@qrr_h`/`@qrrr_h`/`@qrrx_h`/`@icvt_h`/`@fcvt_fixed_h`/
    /// `@fcvtq_h`), **45 já são `✅`** porque ali `_h` significa "elemento halfword INTEIRO"
    /// (`MUL_vi`, `SMULL_vi`, `SQDMULH_vi`, `SQDMULL_v`…), não `binary16`. Uma regra por
    /// `endsWith("_h")` corromperia a tabela nos dois sentidos: apagaria as 45 já implementadas e
    /// marcaria como v8.2 linhas que na verdade são `FEAT_BF16`/FP8/v9. Por isso a lista abaixo é
    /// **enumerada**, linha a linha, e não derivada de um padrão de nome.
    static final Map<String, Aarch64Feature> AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE = new LinkedHashMap<>();

    /// Registra o requisito de versão da `occurrence`-ésima linha de `name` (1-based).
    private static void requireAtOccurrence(String name, int occurrence, Aarch64Feature feature) {
        AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE.put(name + "#" + occurrence, feature);
    }

    /// Como todas as linhas curadas por B19.5.2 são a **1ª** ocorrência do nome (exceção: as 4 de
    /// `@fcvt_fixed_h`, que são a 3ª), este helper cobre o caso comum sem repetir `1`.
    private static void requireFirst(Aarch64Feature feature, String... names) {
        for (String name : names) {
            requireAtOccurrence(name, 1, feature);
        }
    }

    static {
        // ── B19.5.2 — `FEAT_FP16` (aritmética de meia precisão), 88 linhas ──────────────────────
        // Ver a tabela "As 96 linhas" da task; a ordem/agrupamento aqui espelha os templates de
        // `a64.decode`. Todas occ=1 salvo as 4 de `@fcvt_fixed_h` (occ=3).
        // @rrr_h — three-same FP escalar (bit21=0)
        requireFirst(Aarch64Feature.FP16, "FMULX_s", "FCMEQ_s", "FCMGE_s", "FCMGT_s", "FACGE_s",
                "FACGT_s", "FABD_s", "FRECPS_s", "FRSQRTS_s");
        // @rr_h — pairwise escalar (U=0)
        requireFirst(Aarch64Feature.FP16, "FADDP_s", "FMAXP_s", "FMINP_s", "FMAXNMP_s", "FMINNMP_s");
        // @rr_h — two-register-miscellaneous escalar
        requireFirst(Aarch64Feature.FP16, "FCMGT0_s", "FCMGE0_s", "FCMEQ0_s", "FCMLE0_s", "FCMLT0_s",
                "FRECPE_s", "FRECPX_s", "FRSQRTE_s");
        // @qrrr_h — three-same FP vetorial (bit21=0)
        requireFirst(Aarch64Feature.FP16, "FADD_v", "FSUB_v", "FMAX_v", "FMIN_v", "FCMEQ_v");
        // @qrr_h — reduções across-lanes de meia precisão (U=0; hoje escondidas pela TSV)
        requireFirst(Aarch64Feature.FP16, "FMAXNMV_h", "FMINNMV_h", "FMAXV_h", "FMINV_h");
        // @qrr_h — two-register-miscellaneous vetorial (unário)
        requireFirst(Aarch64Feature.FP16, "FABS_v", "FNEG_v", "FSQRT_v", "FRINTN_v", "FRINTM_v",
                "FRINTP_v", "FRINTZ_v", "FRINTA_v", "FRINTX_v", "FRINTI_v");
        // @qrr_h — conversões int↔FP vetoriais
        requireFirst(Aarch64Feature.FP16, "SCVTF_vi", "UCVTF_vi", "FCVTNS_vi", "FCVTNU_vi",
                "FCVTPS_vi", "FCVTPU_vi", "FCVTMS_vi", "FCVTMU_vi", "FCVTZS_vi", "FCVTZU_vi",
                "FCVTAS_vi", "FCVTAU_vi");
        // @qrr_h — comparação-com-zero vetorial + recíprocos
        requireFirst(Aarch64Feature.FP16, "FCMGT0_v", "FCMGE0_v", "FCMEQ0_v", "FCMLE0_v", "FCMLT0_v",
                "FRECPE_v", "FRSQRTE_v");
        // @icvt_h — conversões int↔FP escalares
        requireFirst(Aarch64Feature.FP16, "SCVTF_f", "UCVTF_f", "FCVTNS_f", "FCVTNU_f", "FCVTPS_f",
                "FCVTPU_f", "FCVTMS_f", "FCVTMU_f", "FCVTZS_f", "FCVTZU_f", "FCVTAS_f", "FCVTAU_f");
        // @rrx_h — indexadas escalares
        requireFirst(Aarch64Feature.FP16, "FMUL_si", "FMLA_si", "FMLS_si", "FMULX_si");
        // @qrrx_h — indexadas vetoriais
        requireFirst(Aarch64Feature.FP16, "FMUL_vi", "FMLA_vi", "FMLS_vi", "FMULX_vi");
        // @fcvt_fixed_h — ponto fixo escalar (3ª ocorrência: occ=1 é @icvt_h acima, occ=2 é @icvt_sd)
        requireAtOccurrence("SCVTF_f", 3, Aarch64Feature.FP16);
        requireAtOccurrence("UCVTF_f", 3, Aarch64Feature.FP16);
        requireAtOccurrence("FCVTZS_f", 3, Aarch64Feature.FP16);
        requireAtOccurrence("FCVTZU_f", 3, Aarch64Feature.FP16);
        // @fcvtq_h — ponto fixo vetorial
        requireFirst(Aarch64Feature.FP16, "SCVTF_vf", "UCVTF_vf", "FCVTZS_vf", "FCVTZU_vf");

        // ── B19.5.2 — `FEAT_FHM` (`FMLAL`/`FMLSL` "FP16 fused multiply-add long"), 8 linhas ──────
        // Feature PRÓPRIA (`Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG`), não `FEAT_FP16`, embora
        // as duas sejam ARMv8.2-A. Hoje escondidas em TODAS as colunas pela TSV (removida junto).
        // @qrrr_h
        requireFirst(Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG, "FMLAL_v", "FMLSL_v", "FMLAL2_v",
                "FMLSL2_v");
        // @qrrx_h
        requireFirst(Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG, "FMLAL_vi", "FMLSL_vi", "FMLAL2_vi",
                "FMLSL2_vi");
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
    static final List<Exclusion> EXCLUSIONS = new ArrayList<>();
    /// Acumulador do progresso global: arquitetura → `{suportadas, aplicáveis}`.
    private static final Map<String, int[]> globalPerArchitecture = new LinkedHashMap<>();
    private static int totalSupported;
    private static int totalApplicable;
    /// Revisão do inventário do QEMU contra a qual esta execução mede (E11) — 4º argumento de
    /// {@link #main}, ou lida de `<dir>/.rev`, ou `"desconhecida"` se nenhum dos dois existir.
    private static String inventoryRevision = "desconhecida";

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
        /// **A porta que a E12 fechou**: até ela, uma exclusão com arquitetura `A64` casava com
        /// QUALQUER uma das 16 colunas de versão A64 (via um `isAarch64VersionColumn` que existia só
        /// por compatibilidade com a tabela monolítica pré-B11.5). O efeito era esconder trabalho
        /// pendente exatamente nas versões em que a feature EXISTE — 134 linhas da tabela. Hoje
        /// `A64` casa apenas a coluna monolítica LITERAL `A64`, que é o que `sve.decode`/
        /// `sme.decode` usam (grupos `NOT_IN_ANY_PRESET`, nada decodifica ainda).
        ///
        /// Curadoria de versão A64 vive em {@link #AARCH64_VERSION_REQUIREMENTS} (por nome) e em
        /// {@link #AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE} (por linha) — nunca aqui. O
        /// `IsaCoverageReportA64CurationGuardTest` falha se uma linha de TSV nova voltar a excluir
        /// um mnemônico de `a64.decode` numa coluna de versão, por `A64` **ou por `*`** (os dois
        /// mecanismos que a E12 encontrou).
        boolean matches(String instruction, String column, String decodeFile, int occurrence) {
            boolean nameMatches = pattern.endsWith("*")
                    ? instruction.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.startsWith("*")
                        ? instruction.endsWith(pattern.substring(1))
                        : instruction.equals(pattern);
            boolean columnMatches = architectures.contains("*") || architectures.contains(column);
            boolean groupMatches = grupo.isEmpty() || grupo.equals(decodeFile);
            boolean occurrenceMatches = ocorrencia.isEmpty() || ocorrencia.equals(Integer.toString(occurrence));
            return nameMatches && columnMatches && groupMatches && occurrenceMatches;
        }
    }

    static void loadExclusions(Path file) throws IOException {
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

    static boolean isExcluded(String instruction, String column, String decodeFile, int occurrence) {
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
        inventoryRevision = resolveInventoryRevision(args, decodeDirectory);
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

    /// A revisão do inventário: 4º argumento se presente, senão o conteúdo de `<dir>/.rev`
    /// (gravado por `gerar-cobertura-isa.sh`), senão `"desconhecida"`.
    private static String resolveInventoryRevision(String[] args, Path decodeDirectory) {
        if (args.length > 3 && !args[3].isBlank()) {
            return args[3].trim();
        }
        Path revFile = decodeDirectory.resolve(".rev");
        if (Files.exists(revFile)) {
            try {
                String content = Files.readString(revFile, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            } catch (IOException ignored) {
                // sem `.rev` legível: cai no default abaixo.
            }
        }
        return "desconhecida";
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
                | · | **não se aplica**: o grupo não faz parte daquela arquitetura, ou a instrução é de uma versão POSTERIOR (lista curada em `docs/isa-nao-aplicavel.tsv`, com a versão que a introduziu). Não conta como falta. Ver ali a regra de curadoria: na dúvida a instrução fica ❌ e vira trabalho |
                | ⚠️ | decodifica como OUTRA coisa: o encoding de SIMD caiu no caminho genérico de coprocessador (`MCR`/`CDP`), que ocupa o mesmo espaço `cp10`/`cp11`. Não é suporte — é o decoder não sabendo recusar |

                **`⚠️` voltou a ocorrer na E12**, e a previsão de que ele voltaria "ao abrir um novo
                espaço de encoding" se cumpriu de um jeito inesperado: não por encoding novo, mas por
                **parar de esconder** encodings que o decoder A64 já reivindicava errado. 10 linhas
                (`LDRA`, `SETGP`/`SETGM`/`SETGE`, `CPYP`/`CPYM`/`CPYE`, `FAMAX`/`FAMIN`/`FSCALE`)
                estavam em `docs/isa-nao-aplicavel.tsv` medindo `·` nas 16 colunas; ao migrar a
                curadoria para o mapa de versão elas mediriam `✅`, e a sondagem direta mostrou que o
                decoder devolve OUTRA instrução (`FpLoadLiteral64`, `SystemInstruction[NOP_HINT]`,
                `VectorInsert*`). São dívida do invariante **G8**, listadas em
                `IsaCoverageReport.AARCH64_MISDECODED`. As ocorrências antigas de 32 bits
                (`VMOV_half` em MPCore/v7-A) seguem eliminadas pela B22.2.

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

                """);
        report.append("> **Inventário medido contra a revisão do QEMU `")
                .append(inventoryRevision)
                .append("`** — fixada em `gerar-cobertura-isa.sh` (variável `QEMU_REV`). ")
                .append("A tabela só é reproduzível contra ESSA revisão; um bump de `QEMU_REV` ")
                .append("é um commit próprio, com o diff lido linha a linha (ver o cabeçalho do script).\n\n");
        report.append("<!--CORPO-->\n");
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
                                ? isApplicableToAarch64Version(instruction.name(), occurrence, aarch64Architecture)
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
                        ? probeAarch64(instruction, occurrence, aarch64Architecture)
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

    /// Linhas do inventário A64 cujo encoding o `Aarch64Decoder` REIVINDICA mas decodifica como
    /// **outra instrução** — dívida do invariante **G8**, medida pela E12. Chave `"NOME#ocorrência"`.
    ///
    /// Elas medem `⚠️`, não `✅`: o símbolo existe desde a E5 exatamente para isto ("decodifica como
    /// OUTRA coisa … não é suporte — é o decoder não sabendo recusar"), e o cabeçalho da tabela já
    /// previa que ele voltasse a ocorrer "ao abrir um novo espaço de encoding". `⚠️` conta no
    /// denominador e NÃO no numerador, igual a `❌` — é trabalho pendente.
    ///
    /// **Como a E12 as encontrou**: as 10 estavam em `docs/isa-nao-aplicavel.tsv` com arquitetura
    /// `A64`, medindo `·` nas 16 colunas. Ao migrar a curadoria para o mapa de versão, elas
    /// passariam a medir `✅` — um falso positivo PIOR que o `·` anterior, porque afirmaria trabalho
    /// concluído. A sondagem direta mostrou o que o decoder devolve de verdade (a classe citada em
    /// cada linha abaixo).
    ///
    /// **Isto NÃO é uma exclusão** (regra máxima do `tasks/README.md`): a instrução continua contando
    /// como falta, e a entrada some sozinha do relatório quando alguém consertar o decoder —
    /// `IsaCoverageReportA64CurationGuardTest` falha se uma destas voltar a decodificar de verdade,
    /// obrigando a remover a linha.
    static final Map<String, String> AARCH64_MISDECODED = new LinkedHashMap<>();

    static {
        // `FEAT_PAuth` (ARMv8.3-A): `LDRAA`/`LDRAB` caem no catch-all de hint-space.
        AARCH64_MISDECODED.put("LDRA#1", "SystemInstruction[NOP_HINT]");
        // `FEAT_MTE2` (ARMv8.5-A) e `FEAT_MOPS` (ARMv8.8-A): mesma classe de bug que a B11.3
        // corrigiu para o `LDR (literal)` INTEIRO (`LITERAL_SUBCLASS_RESERVED_BIT_SHIFT`) — sobrou
        // o caminho de literal de PONTO FLUTUANTE.
        AARCH64_MISDECODED.put("SETGP#1", "FpLoadLiteral64");
        AARCH64_MISDECODED.put("SETGM#1", "FpLoadLiteral64");
        AARCH64_MISDECODED.put("SETGE#1", "FpLoadLiteral64");
        AARCH64_MISDECODED.put("CPYP#1", "FpLoadLiteral64");
        AARCH64_MISDECODED.put("CPYM#1", "FpLoadLiteral64");
        AARCH64_MISDECODED.put("CPYE#1", "FpLoadLiteral64");
        // `FEAT_FAMINMAX` (ARMv9.4-A) e `FEAT_FP8` (Armv9.5-A): a 1ª ocorrência colide com o espaço
        // de `INS`/`MOV` vetorial. A 2ª ocorrência de cada uma já mede `❌` honestamente.
        AARCH64_MISDECODED.put("FAMAX#1", "VectorInsertGeneral");
        AARCH64_MISDECODED.put("FAMIN#1", "VectorInsertElement");
        AARCH64_MISDECODED.put("FSCALE#1", "VectorInsertElement");
    }

    /// `architecture` é `null` para os grupos A64 não versionados ainda (`sve.decode`/
    /// `sme.decode`, ver {@link #appendGroup}) — nesse caso usa o decoder default (B11.2:
    /// equivalente a `ARMV8_0_A`, mesmo comportamento de antes de B11.5).
    ///
    /// `occurrence` serve só para consultar {@link #AARCH64_MISDECODED} — ver o Javadoc de lá.
    private static Status probeAarch64(DecodeTreeSpec.Instruction instruction, int occurrence,
                                        Aarch64Architecture architecture) {
        Aarch64Decoder decoder = architecture == null ? new Aarch64Decoder() : new Aarch64Decoder(architecture);
        for (int[] strategy : FILL_STRATEGIES) {
            int word = encode(instruction, strategy);
            try {
                TestAddressSpace raw = new TestAddressSpace(8);
                raw.put32(0, word);
                if (decoder.decode(AddressSpace64.wrapping(raw), 0) != null) {
                    return AARCH64_MISDECODED.containsKey(instruction.name() + "#" + occurrence)
                            ? Status.FALLBACK
                            : Status.SUPPORTED;
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
    ///
    /// Consulta primeiro o requisito por **(nome, ocorrência)** (B19.5.2,
    /// {@link #AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE}) — o mais específico vence — e só então
    /// o requisito por nome. `occurrence` é a posição 1-based desta linha entre as de mesmo nome.
    static boolean isApplicableToAarch64Version(String instruction, int occurrence,
                                                        Aarch64Architecture architecture) {
        Aarch64Feature required = AARCH64_VERSION_REQUIREMENTS_BY_OCCURRENCE.get(instruction + "#" + occurrence);
        if (required == null) {
            required = AARCH64_VERSION_REQUIREMENTS.get(instruction);
        }
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
