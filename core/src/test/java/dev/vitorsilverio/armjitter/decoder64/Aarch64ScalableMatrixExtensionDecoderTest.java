package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `MSR SVCR<mask>, #imm` (`FEAT_SME`, ARMv9.2-A) — os aliases `SMSTART`/`SMSTOP`. B19.28 só os decodificava
/// e recusava; a B18.2 os transforma em {@link Ir64Op.StreamingModeControl}. Sem `FEAT_SME` nada muda (G3):
/// o mesmo `unsupported` genérico de qualquer feature ausente. Todas as palavras foram conferidas contra
/// `aarch64-none-elf-as -march=armv9.2-a+sme` (devkitA64), nunca calculadas à mão.
class Aarch64ScalableMatrixExtensionDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder SME_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);

    private static final int SMSTART_SM = 0xd503437f;
    private static final int SMSTOP_SM = 0xd503427f;
    private static final int SMSTART_ZA = 0xd503457f;
    private static final int SMSTOP_ZA = 0xd503447f;
    private static final int SMSTART_BOTH = 0xd503477f;
    private static final int SMSTOP_BOTH = 0xd503467f;
    /// `msr svcr<mask=0>, #0/#1` — `mask = 0b00` é reservado.
    private static final int SVCR_MASK_ZERO_IMM0 = 0xd503407f;
    private static final int SVCR_MASK_ZERO_IMM1 = 0xd503417f;

    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return decoder.decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {SMSTART_SM, SMSTOP_SM, SMSTART_ZA, SMSTOP_ZA, SMSTART_BOTH, SMSTOP_BOTH,
            SVCR_MASK_ZERO_IMM0, SVCR_MASK_ZERO_IMM1})
    void rejectedGenericallyWithoutSme(int word) {
        UnsupportedOperationException exception =
                assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word));
        assertTrue(exception.getMessage().contains("fora da fatia B6.1"),
                "esperava a mensagem genérica de feature ausente, obteve: " + exception.getMessage());
    }

    @Test
    void smstartAndSmstopDecodeToTheEnableAndTargetBits() {
        assertControl(SMSTART_SM, true, true, false);
        assertControl(SMSTOP_SM, false, true, false);
        assertControl(SMSTART_ZA, true, false, true);
        assertControl(SMSTOP_ZA, false, false, true);
        assertControl(SMSTART_BOTH, true, true, true);
        assertControl(SMSTOP_BOTH, false, true, true);
    }

    private static void assertControl(int word, boolean enable, boolean streamingMode, boolean za) {
        Ir64Op.StreamingModeControl op =
                assertInstanceOf(Ir64Op.StreamingModeControl.class, decode(SME_DECODER, word));
        assertEquals(enable, op.enable(), "enable de 0x" + Integer.toHexString(word));
        assertEquals(streamingMode, op.streamingMode(), "SM de 0x" + Integer.toHexString(word));
        assertEquals(za, op.za(), "ZA de 0x" + Integer.toHexString(word));
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @ParameterizedTest
    @ValueSource(ints = {SVCR_MASK_ZERO_IMM0, SVCR_MASK_ZERO_IMM1})
    void reservedMaskZeroIsRefusedEvenWithSme(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, word));
    }

    // ── Instruções ilegais em modo streaming (`sme-fa64.decode`, listas FAIL/OK) ────────────────

    private static final int ADD_VECTOR = 0x4ea28420; // add v0.4s, v1.4s, v2.4s
    private static final int LD1_STRUCTURE = 0x4c407800; // ld1 {v0.4s}, [x0]
    private static final int AESE = 0x4e284820; // aese v0.16b, v1.16b
    private static final int FJCVTZS = 0x1e7e0000; // fjcvtzs w0, d0
    private static final int ADD_SCALAR_D = 0x5ee28420; // add d0, d1, d2
    private static final int SHA512H = 0xce628020; // sha512h q0, q1, v2.2d
    private static final int UMOV_LANE0 = 0x0e043c00; // umov w0, v0.s[0]
    private static final int SMOV_LANE0 = 0x4e042c20; // smov x0, v1.s[0]
    private static final int FMULX_SCALAR = 0x5e22dc20; // fmulx s0, s1, s2
    private static final int FRECPE_SCALAR = 0x5ea1d820; // frecpe s0, s1
    private static final int FADD_SCALAR = 0x1e222820; // fadd s0, s1, s2
    private static final int LDR_Q = 0x3dc00000; // ldr q0, [x0]
    private static final int MOV_X0 = 0xd2800020; // mov x0, #1

    @ParameterizedTest
    @ValueSource(ints = {ADD_VECTOR, LD1_STRUCTURE, AESE, FJCVTZS, ADD_SCALAR_D, SHA512H})
    void illegalInStreamingModeIsWrappedUnderSme(int word) {
        Ir64Op.StreamingRestricted op =
                assertInstanceOf(Ir64Op.StreamingRestricted.class, decode(SME_DECODER, word));
        assertFalse(op.inner() instanceof Ir64Op.StreamingRestricted, "embrulha uma vez só");
    }

    @ParameterizedTest
    @ValueSource(ints = {UMOV_LANE0, SMOV_LANE0, FMULX_SCALAR, FRECPE_SCALAR, FADD_SCALAR, LDR_Q, MOV_X0})
    void legalInStreamingModeIsNeverWrapped(int word) {
        assertFalse(decode(SME_DECODER, word) instanceof Ir64Op.StreamingRestricted);
    }

    @ParameterizedTest
    @ValueSource(ints = {ADD_VECTOR, LD1_STRUCTURE, AESE, FJCVTZS, ADD_SCALAR_D, SHA512H})
    void noWrapWithoutSme(int word) {
        // ARMV9_0_A tem SVE mas não SME: a restrição de streaming nem existe (G3).
        Aarch64Decoder armv9 = new Aarch64Decoder(Aarch64Architecture.ARMV9_0_A);
        assertFalse(decode(armv9, word) instanceof Ir64Op.StreamingRestricted);
    }

    @Test
    void patternWithWrongWidthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StreamingModeRestrictions.Pattern.of("0101"));
    }
}
