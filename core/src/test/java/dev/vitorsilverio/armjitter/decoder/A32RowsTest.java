package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// E15.16a: o {@link ArmDecoder} como tabela — invariantes de cada camada de {@link A32Layers} e da
/// tabela incondicional, e os pontos em que a ordem entre camadas e a coluna de feature decidem o
/// resultado. A equivalência com a cascata antiga nas 2³² palavras é do oráculo
/// `tasks/trilha-e-manutencao/e15.16a-scripts/A32ExhaustiveOracle.java`.
class A32RowsTest {
    private static final ArmArchitecture ALL = ArmArchitecture.of("all", ArmFeature.values());
    private static final ArmArchitecture NONE = ArmArchitecture.of("none");
    private static final int SPECIFIC_ROWS = 69;
    private static final int UNCONDITIONAL_ROWS = 16;
    private static final long SEED = 0xE1516AL;

    private static DecodedInstruction decode(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    @Test
    void rowsOfALayerNeverOverlap() {
        assertEquals(4, A32Layers.CONDITIONAL_LAYERS.size());
        assertEquals(SPECIFIC_ROWS, A32Layers.SPECIFIC.size());
        for (List<DecodeRow<ArmFeature, DecodedInstruction>> layer : A32Layers.CONDITIONAL_LAYERS) {
            DecodeTableInvariants.assertNoOverlap(layer);
        }
        assertEquals(UNCONDITIONAL_ROWS, A32UnconditionalRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(A32UnconditionalRows.ROWS);
    }

    @Test
    void everyRowIsReachableWithEveryFeature() {
        for (List<DecodeRow<ArmFeature, DecodedInstruction>> layer : A32Layers.CONDITIONAL_LAYERS) {
            DecodeTable<ArmFeature, DecodedInstruction> table = DecodeTable.forFeatures(layer, ALL::has);
            assertEquals(layer.size(), table.rows().size());
            DecodeTableInvariants.assertReachable(table, 0, SEED);
        }
        DecodeTableInvariants.assertReachable(DecodeTable.forFeatures(A32UnconditionalRows.ROWS, ALL::has), 0, SEED);
    }

    /// Sem feature nenhuma: as linhas `optional` somem, as `claimed` ficam e recusam.
    @Test
    void claimedRowsStayAndRefuseWithoutTheFeature() {
        DecodeTable<ArmFeature, DecodedInstruction> specific = DecodeTable.forFeatures(A32Layers.SPECIFIC, NONE::has);
        long optional = A32Layers.SPECIFIC.stream()
                .filter(row -> row.requires() != null && row.whenAbsent() == null).count();
        assertEquals(A32Layers.SPECIFIC.size() - optional, specific.rows().size());
        DecodeTableInvariants.assertReachable(specific, 0, SEED);
        for (DecodeRow<ArmFeature, DecodedInstruction> row : specific.rows()) {
            if (row.requires() != null) {
                assertEquals(InstructionKind.UNIMPLEMENTED, row.build().decode(row.value(), 0).kind());
            }
        }

        DecodeTable<ArmFeature, DecodedInstruction> unconditional =
                DecodeTable.forFeatures(A32UnconditionalRows.ROWS, NONE::has);
        assertEquals(UNCONDITIONAL_ROWS, unconditional.rows().size());
        for (DecodeRow<ArmFeature, DecodedInstruction> row : unconditional.rows()) {
            DecodedInstruction refused = unconditional.decode(row.value(), 0);
            assertEquals(InstructionKind.UNIMPLEMENTED, refused.kind());
            assertEquals(Condition.AL, refused.condition());
        }
    }

    /// A ordem entre camadas: `WFI` (camada 1) é um recorte do `MSR` registrador com `bit25=1`
    /// (camada 2), que é um recorte do `MSR` imediato (camada 3), que é um recorte do `TEQ`
    /// imediato de `S=0` (camada 4).
    @Test
    void layersAnswerInOrder() {
        ArmArchitecture v6k = ArmArchitecture.ARMV6K;
        assertEquals(InstructionKind.WAIT_FOR_INTERRUPT, decode(v6k, 0xE320_F003).kind());

        DecodedInstruction shadow = decode(v6k, 0xE328_F005);
        assertEquals(InstructionKind.MSR, shadow.kind());
        assertEquals(5, shadow.sourceRegister());
        assertTrue(!shadow.immediateOperand(), "MSR imediato de imm8 < 16 sai como registrador (E23)");

        DecodedInstruction immediate = decode(v6k, 0xE328_F0F1);
        assertEquals(InstructionKind.MSR, immediate.kind());
        assertTrue(immediate.immediateOperand());
        assertEquals(0xF1, immediate.immediate());

        DecodedInstruction teq = decode(v6k, 0xE320_00F1);
        assertEquals(InstructionKind.TEQ, teq.kind());
        assertTrue(teq.setFlags());
    }

    /// `optional`: sem a feature a palavra segue para a camada de baixo; `claimed`: é recusada.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "QADD r0 r1 r2,        e1020051, SATURATING,           TST",
            "CRC32B r0 r1 r2,      e1010042, CRC32,                TST",
            "SMULBB r0 r1 r2,      e1600281, DSP_MULTIPLY,         CMN",
            "UMAAL r0 r1 r2 r3,    e0410392, UMAAL,                UNIMPLEMENTED",
            "SADD16 r0 r1 r2,      e6110f12, PARALLEL_ALU,         UNIMPLEMENTED",
            "REV r0 r1,            e6bf0f31, BYTE_REVERSE,         UNIMPLEMENTED",
            "SMMLA r0 r1 r2 r3,    e7503211, DSP_TOP_WORD_MULTIPLY, UNIMPLEMENTED",
            "CLZ r0 r1,            e16f0f11, CLZ,                  UNIMPLEMENTED",
            "BLX r1,               e12fff31, BRANCH_EXCHANGE,      UNIMPLEMENTED",
            "MOVW r0 #0x1234,      e3010234, MOV,                  UNIMPLEMENTED",
            "LDRD r0 [r1],         e1c100d0, DOUBLE_TRANSFER,      UNIMPLEMENTED",
            "LDREX r0 [r1],        e1910f9f, LOAD_EXCLUSIVE,       UNIMPLEMENTED",
            "SDIV r0 r1 r2,        e710f211, DIVIDE,               UNIMPLEMENTED"})
    void featureColumnDecidesWhoAnswers(String name, String word, InstructionKind with, InstructionKind without) {
        int raw = Integer.parseUnsignedInt(word, 16);
        assertEquals(with, decode(ALL, raw).kind(), name);
        assertEquals(without, decode(NONE, raw).kind(), name);
    }

    /// Restrição de campo que na cascata fazia a palavra seguir é padrão exato: os buracos da
    /// aritmética paralela (`ppp=000/100`, `ttt=101/110`) e do extend (`ff=01`) não têm linha.
    @ParameterizedTest
    @CsvSource({"e6010f12", "e6410f12", "e6110fb2", "e6110fd2", "e6910071", "e6d10071"})
    void fieldHolesHaveNoRow(String word) {
        int raw = Integer.parseUnsignedInt(word, 16);
        assertEquals(null, DecodeTable.forFeatures(A32Layers.SPECIFIC, ALL::has).decode(raw, 0));
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ALL, raw).kind());
    }

    /// `UDF` só existe com `cond = AL`.
    @Test
    void permanentlyUndefinedFixesTheCondition() {
        assertEquals(InstructionKind.UDF, decode(ALL, 0xE7F0_00F0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ALL, 0x07F0_00F0).kind());
    }

    /// Restrições de valor ficam no construtor e recusam (registrador PC, campo reservado).
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "MRS_bank Rd=PC,          e100f200",
            "MRS_bank sysm reservado, e10f0200",
            "MSR_bank Rn=PC,          e120f20f",
            "MSR_bank sysm reservado, e12ff200",
            "MOVW Rd=PC,              e300f000",
            "CRC32 ss=11,             e1610042",
            "MLS Rd=PC,               e06f1392",
            "MLS Ra=PC,               e060f392",
            "MLS Rm=PC,               e0601f92",
            "MLS Rn=PC,               e060139f",
            "SMLAD Rm=PC,             e7001f12",
            "SMLAD Rn=PC,             e700131f",
            "SMMLA Rd=PC,             e75f1312",
            "SMMLA Rn=PC,             e750131f",
            "RBIT Rd=PC,              e6ffff31",
            "LDREXD Rt=r14,           e1b1ef9f",
            "STREXD Rt=r14,           e1a10f9e",
            "SMLAD Rd=PC,             e70f1312",
            "SMLALD Ra=PC,            e740f312",
            "SMMLA Rm=PC,             e7501f12",
            "SBFX Rd=PC,              e7a0f051",
            "SBFX lsb+width>32,       e7bf0fd1",
            "BFI msb<lsb,             e7c00f91",
            "RBIT Rm=PC,              e6ff0f3f",
            "UDIV Rn=PC,              e730f11f",
            "LDRH registrador com bits 11:8, e19101b2",
            "LDA sem bits 3:0 = 1111, e1910c90",
            "STL sem bits 15:12 = 1111, e1810c92",
            "LDAD (doubleword plain), e1b10c9f",
            "LDREX Rn=PC,             e19f0f9f",
            "LDREXD Rt ímpar,         e1b11f9f",
            "STREX Rm=PC,             e1810f9f",
            "STREXD Rt ímpar,         e1a10f93",
            "STREX Rd=Rn,             e1811f92",
            "STREX Rd=Rt,             e1812f92",
            "STREXD Rd=Rt2,           e1a13f92",
            "LDA Rn=PC,               e19f0c9f",
            "LDA Rt=PC,               e191fc9f",
            "STL Rt=PC,               e181fc9f"})
    void valueRestrictionsRefuse(String name, String word) {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ALL, Integer.parseUnsignedInt(word, 16)).kind(), name);
    }

    /// As formas válidas vizinhas das recusas acima.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "MRS r0 r8_usr,    e1000200, MRS_BANK",
            "MSR r8_usr r1,    e120f201, MSR_BANK",
            "SMLALD,           e7401312, DSP_DUAL_MULTIPLY",
            "BFC r0,           e7c7001f, BIT_FIELD_INSERT",
            "LDA r0 [r1],      e1910c9f, LOAD",
            "STL r2 [r1],      e181fc92, STORE",
            "LDREXD r2 [r1],   e1b12f9f, LOAD_EXCLUSIVE",
            "STREXD r0 r2 [r1], e1a10f92, STORE_EXCLUSIVE",
            "STREX r3 r2 [r1], e1813f92, STORE_EXCLUSIVE",
            "LDAEXB r0 [r1],   e1d10e9f, LOAD_EXCLUSIVE"})
    void validNeighbours(String name, String word, InstructionKind kind) {
        assertEquals(kind, decode(ALL, Integer.parseUnsignedInt(word, 16)).kind(), name);
    }
}
