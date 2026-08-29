package dev.vitorsilverio.armjitter.arch64;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;

/// Uma descrição imutável de uma versão de arquitetura AArch64 como um **conjunto de features**
/// (`ARMv8.0-A`...`ARMv8.9-A`, `ARMv9.0-A`...`ARMv9.5-A`) — mirror de
/// {@link dev.vitorsilverio.armjitter.arch.ArmArchitecture} para o lado de 64 bits (B11.1, ver
/// `tasks/trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`).
///
/// **Escopo desta task**: só a estrutura. Nenhum decoder/executor A64 consulta {@link #has} ainda —
/// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core}/
/// {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder} continuam do jeito que estão,
/// implementando tudo incondicionalmente (G3, comportamento observável idêntico). A fiação real
/// (`Aarch64Core`/`Aarch64Decoder` passando a consultar uma instância desta classe) é B11.2; o
/// mapeamento de cada instrução/registrador já implementado para a versão real que o introduz é
/// B11.3; o primeiro gate de decode de verdade é B11.4.
///
/// Correspondência de versão ARMv9.x → baseline mandatório ARMv8.(x+4)-A: o próprio manual ARM
/// (ARM DDI 0487, introdução da arquitetura ARMv9-A) define que toda ARMv9.x-A tem, como conjunto
/// mínimo obrigatório, exatamente as features non-SVE/SME da ARMv8.(x+4)-A correspondente — por
/// isso os presets `ARMV9_x_A` abaixo estendem o `ARMV8_(x+4)_A` equivalente em vez de serem
/// declarados do zero.
public final class Aarch64Architecture {
    /// Baseline atual do projeto: tudo que `Aarch64Core`/`Aarch64Decoder` já implementam
    /// incondicionalmente hoje (o Cortex-A53 do `virtual-arm-box`/raspi3-64, confirmado ARMv8.0-A
    /// pela ficha técnica real do núcleo). Nenhuma feature opcional de `Aarch64Feature` está
    /// presente aqui de propósito — são todas extensões posteriores a ARMv8.0-A ainda não
    /// implementadas (ver `docs/isa-nao-aplicavel.tsv`).
    public static final Aarch64Architecture ARMV8_0_A = of("ARMv8.0-A");

    /// ARMv8.1-A: acrescenta {@link Aarch64Feature#RDM}, `FEAT_LSE` (`CAS`/`CASP`) e `FEAT_PAN`
    /// (B11.5, auditoria de B11.3: os dois últimos já eram implementados sem gate desde B8.1/B8.3).
    public static final Aarch64Architecture ARMV8_1_A = extending(ARMV8_0_A, "ARMv8.1-A",
            Aarch64Feature.RDM,
            Aarch64Feature.LSE,
            Aarch64Feature.PAN);

    /// ARMv8.2-A: acrescenta meia-precisão, dot-product, FHM, as 4 famílias de criptografia
    /// adicionadas nesta versão (SHA-512/SM3/SM4/SHA3) e `FEAT_UAO` (B11.5: as 4 últimas já eram
    /// implementadas sem gate desde B8.2/B8.3/B8.11b).
    public static final Aarch64Architecture ARMV8_2_A = extending(ARMV8_1_A, "ARMv8.2-A",
            Aarch64Feature.FP16,
            Aarch64Feature.DOT_PRODUCT,
            Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG,
            Aarch64Feature.SHA512,
            Aarch64Feature.SM3,
            Aarch64Feature.SM4,
            Aarch64Feature.SHA3,
            Aarch64Feature.UAO);

    /// ARMv8.3-A: acrescenta conversão com semântica Javascript, aritmética de número complexo,
    /// autenticação de ponteiro real e `FEAT_LRCPC` (`LDAPR`, B19.1).
    public static final Aarch64Architecture ARMV8_3_A = extending(ARMV8_2_A, "ARMv8.3-A",
            Aarch64Feature.JAVASCRIPT_CONVERT,
            Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC,
            Aarch64Feature.POINTER_AUTHENTICATION,
            Aarch64Feature.LRCPC);

    /// ARMv8.4-A: acrescenta `FEAT_FlagM` (`RMIF`/`SETF8`/`SETF16`) e `FEAT_DIT` (B11.5: os dois
    /// já eram implementados sem gate desde B8.2/B8.3).
    public static final Aarch64Architecture ARMV8_4_A = extending(ARMV8_3_A, "ARMv8.4-A",
            Aarch64Feature.FLAG_MANIPULATION,
            Aarch64Feature.DIT);

    /// ARMv8.5-A: acrescenta arredondamento dirigido para inteiro de 32/64 bits, a Memory Tagging
    /// Extension e `FEAT_FlagM2` (`AXFLAG`/`XAFLAG`, B11.5: já implementada sem gate desde B8.2).
    public static final Aarch64Architecture ARMV8_5_A = extending(ARMV8_4_A, "ARMv8.5-A",
            Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL,
            Aarch64Feature.MEMORY_TAGGING,
            Aarch64Feature.FLAG_MANIPULATION_2);

    /// ARMv8.6-A: acrescenta `bfloat16` e multiplicação de matriz inteira de 8 bits.
    public static final Aarch64Architecture ARMV8_6_A = extending(ARMV8_5_A, "ARMv8.6-A",
            Aarch64Feature.BFLOAT16,
            Aarch64Feature.INT8_MATRIX_MULTIPLY);

    /// ARMv8.7-A: acrescenta `FEAT_WFxT` (`WFET`/`WFIT`, B11.5: já implementada sem gate desde
    /// B8.3).
    public static final Aarch64Architecture ARMV8_7_A = extending(ARMV8_6_A, "ARMv8.7-A",
            Aarch64Feature.WFXT);

    /// ARMv8.8-A: acrescenta as operações de memória aceleradas (`CPYE`/`CPYM`/`CPYP`) e
    /// `FEAT_NMI` (`MSR (immediate) ALLINT`, B11.5: já implementada sem gate desde B8.3).
    public static final Aarch64Architecture ARMV8_8_A = extending(ARMV8_7_A, "ARMv8.8-A",
            Aarch64Feature.MEMORY_COPY_SET,
            Aarch64Feature.NMI);

    /// ARMv8.9-A: acrescenta `FEAT_CSSC` (`CTZ`/`SMAX`/`SMIN`/`UMAX`/`UMIN` escalares GPR).
    public static final Aarch64Architecture ARMV8_9_A = extending(ARMV8_8_A, "ARMv8.9-A",
            Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION);

    /// ARMv9.0-A: baseline mandatório = ARMv8.5-A (ver nota de correspondência de versão na
    /// documentação da classe). SVE (mandatório em ARMv9.0-A real) não é modelado por nenhuma
    /// {@link Aarch64Feature} ainda — fica para uma task própria da escada B11.x.
    public static final Aarch64Architecture ARMV9_0_A = extending(ARMV8_5_A, "ARMv9.0-A");

    /// ARMv9.1-A: baseline mandatório = ARMv8.6-A.
    public static final Aarch64Architecture ARMV9_1_A = extending(ARMV8_6_A, "ARMv9.1-A");

    /// ARMv9.2-A: baseline mandatório = ARMv8.7-A, mais `FEAT_SME` (introduzida nesta versão).
    public static final Aarch64Architecture ARMV9_2_A = extending(ARMV8_7_A, "ARMv9.2-A",
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION);

    /// ARMv9.3-A: baseline mandatório = ARMv8.8-A.
    public static final Aarch64Architecture ARMV9_3_A = extending(ARMV8_8_A, "ARMv9.3-A");

    /// ARMv9.4-A: baseline mandatório = ARMv8.9-A, mais máximo/mínimo de valor absoluto em ponto
    /// flutuante e a Guarded Control Stack (introduzidas nesta versão).
    public static final Aarch64Architecture ARMV9_4_A = extending(ARMV8_9_A, "ARMv9.4-A",
            Aarch64Feature.FP_ABSOLUTE_MAX_MIN,
            Aarch64Feature.GUARDED_CONTROL_STACK);

    /// ARMv9.5-A: acrescenta `FEAT_CMPBR` (`CB<cc>`) sobre a ARMv9.4-A.
    public static final Aarch64Architecture ARMV9_5_A = extending(ARMV9_4_A, "ARMv9.5-A",
            Aarch64Feature.COMPARE_AND_BRANCH);

    private final String name;
    private final EnumSet<Aarch64Feature> features;

    private Aarch64Architecture(String name, EnumSet<Aarch64Feature> features) {
        this.name = Objects.requireNonNull(name, "name");
        this.features = features.clone();
    }

    /// Constrói uma arquitetura a partir de um nome e das features que ela suporta.
    public static Aarch64Architecture of(String name, Aarch64Feature... features) {
        EnumSet<Aarch64Feature> set = EnumSet.noneOf(Aarch64Feature.class);
        Collections.addAll(set, features);
        return new Aarch64Architecture(name, set);
    }

    /// Constrói uma arquitetura que estende uma base: herda todas as features da base,
    /// acrescentando as features extras. É como versões novas compõem sobre as anteriores (ex.
    /// ARMv8.2-A sobre ARMv8.1-A) sem repetir a lista da base.
    public static Aarch64Architecture extending(Aarch64Architecture base, String name,
            Aarch64Feature... extraFeatures) {
        EnumSet<Aarch64Feature> set = base.features.clone();
        Collections.addAll(set, extraFeatures);
        return new Aarch64Architecture(name, set);
    }

    public boolean has(Aarch64Feature feature) {
        return features.contains(feature);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
