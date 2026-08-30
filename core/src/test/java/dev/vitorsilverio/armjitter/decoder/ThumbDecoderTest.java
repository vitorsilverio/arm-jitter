package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbDecoderTest {
    @Test
    void decodesThumbMovImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x202A);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionSet.THUMB, instruction.instructionSet());
        assertEquals(InstructionKind.MOV, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(42, instruction.immediate());
        assertTrue(instruction.setFlags());
    }

    @Test
    void decodesThumbUnconditionalBranchWithPipelineOffset() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xE001);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.BRANCH, instruction.kind());
        assertEquals(6, instruction.immediate());
    }

    /// `UDF #0` de 16 bits (B9.4, ARM DDI 0406C A8.8.247): mesmo padrão de bits que o nibble de
    /// condição RESERVADO (`cond=0b1110`) do `B<cond>` de 16 bits — não é coincidência, é o mesmo
    /// espaço de encoding (`t16.decode`: `B_cond_thumb` exclui explicitamente `cond=1110`/`1111`,
    /// que viram `UDF`/`SVC`). Reconhecida com {@link InstructionKind#UDF} explícito desde a B9.4
    /// (era {@link InstructionKind#UNIMPLEMENTED} genérico antes — mesmo comportamento observável,
    /// só muda a classificação para a tabela de cobertura, ver B9.1 para o precedente do A32).
    /// Encoding real conferido com `arm-none-eabi-as -mfpu=vfpv4` (devkitARM): `udf #0` → `0xde00`.
    @Test
    void decodesThumbUdf() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xDE00);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.UDF, instruction.kind());
    }

    /// `UDF #42` — o imediato não afeta a classificação (sempre indefinida, sem side effect
    /// observável distinto por valor). Encoding real: `udf #42` → `0xde2a`.
    @Test
    void decodesThumbUdfWithNonZeroImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xDE2A);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.UDF, instruction.kind());
    }

    // ── HLT #imm6 (B22.1, ARM DDI 0487 — Halting debug, ARMv8-A / ARMv8-M) ───────────────────
    // `1011 1010 10 imm6` (0xBA80, máscara 0xFFC0). Ocupa o buraco `..10..` da família
    // REV/REV16/REVSH. Nenhum preset atual declara ArmFeature#HALT — o encoding é RECONHECIDO e
    // recusado por gate (higiene de G8, não mais fallthrough para UNDEFINED).

    /// Arquitetura de teste que declara `ArmFeature#HALT` — nenhum preset real a tem (B14 criará
    /// o preset ARMv8-A de 32 bits que a habilita).
    private static final ArmArchitecture WITH_HALT =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv8A32-TestHalt", ArmFeature.HALT);

    @Test
    void decodesThumbHltWithHaltFeature() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xBAAA); // HLT #0x2A

        DecodedInstruction instruction = new ThumbDecoder(WITH_HALT).decode(memory, 0);

        assertEquals(InstructionKind.HALT, instruction.kind());
        assertEquals(0x2A, instruction.immediate());
    }

    @Test
    void decodesThumbHltImmediateBounds() {
        TestAddressSpace low = new TestAddressSpace(16);
        low.put16(0, 0xBA80); // HLT #0
        assertEquals(0, new ThumbDecoder(WITH_HALT).decode(low, 0).immediate());

        TestAddressSpace high = new TestAddressSpace(16);
        high.put16(0, 0xBABF); // HLT #0x3F
        assertEquals(0x3F, new ThumbDecoder(WITH_HALT).decode(high, 0).immediate());
    }

    @Test
    void thumbHltIsUnimplementedOnEveryCurrentPreset() {
        for (ArmArchitecture architecture : new ArmArchitecture[]{
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV6K,
                ArmArchitecture.ARM11_MPCORE, ArmArchitecture.ARMV7A,
                ArmArchitecture.ARMV6M, ArmArchitecture.ARMV7M}) {
            TestAddressSpace memory = new TestAddressSpace(16);
            memory.put16(0, 0xBAAA);

            DecodedInstruction instruction = new ThumbDecoder(architecture).decode(memory, 0);

            assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind(),
                    "HLT deve ser recusada explicitamente em " + architecture.name());
        }
    }

    @Test
    void thumbHltDoesNotDisturbRevFamilyNeighbours() {
        // REV/REV16/REVSH moram no MESMO prefixo `1011 1010 xx` — 0xBA80 é só o buraco `..10..`.
        int[][] revEncodings = {
                {0xBA08, 0}, // REV  r0, r1  -> BYTE_REVERSE variant 0
                {0xBA48, 1}, // REV16 r0, r1 -> variant 1
                {0xBAC8, 2}, // REVSH r0, r1 -> variant 2
        };
        for (int[] pair : revEncodings) {
            TestAddressSpace memory = new TestAddressSpace(16);
            memory.put16(0, pair[0]);

            DecodedInstruction instruction = new ThumbDecoder(WITH_HALT).decode(memory, 0);

            assertEquals(InstructionKind.BYTE_REVERSE, instruction.kind(),
                    "encoding 0x" + Integer.toHexString(pair[0]) + " não deve virar HALT");
            assertEquals(pair[1], instruction.immediate());
        }
    }
}
