package dev.vitorsilverio.armjitter.decoder;

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
}
