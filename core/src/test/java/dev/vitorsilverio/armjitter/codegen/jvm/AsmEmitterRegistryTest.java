package dev.vitorsilverio.armjitter.codegen.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;
import dev.vitorsilverio.armjitter.support.IrOpSamples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// E15.7 — o registro de emissores ASM de 32 bits: montagem, ausência de entrada e os predicados de
/// VFP que o `IrOpContractTest` não amostra (os carve-outs inteiro/memória/branch estão lá).
class AsmEmitterRegistryTest {
    private static final int BLOCK_START = 0x8000;
    private static final int INSTRUCTION_BYTES = 4;

    @Test
    void recordRegisteredTwiceFailsTheBuild() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AsmEmitterRegistry.build(List.of(AsmAluEmitter::register, AsmAluEmitter::register)));
        assertTrue(failure.getMessage().contains(IntegerOp.Alu.class.getName()), failure.getMessage());
    }

    @Test
    void everyFamilyRegistersDisjointRecords() {
        Map<Class<?>, AsmEmission<?>> emissions = AsmEmitterRegistry.build(List.of(
                AsmAluEmitter::register,
                AsmIntegerEmitter::register,
                AsmMemoryEmitter::register,
                AsmControlEmitter::register,
                AsmVfpEmitter::register));
        emissions.forEach((type, emission) -> assertEquals(type, emission.type()));
        assertTrue(emissions.containsKey(IrOp.Cycle.class));
        assertFalse(emissions.containsKey(IntegerOp.Crc32.class));
    }

    /// Record sem entrada: interpretado na política, erro no `compile` sem PER_OP, fallback no PER_OP.
    @Test
    void recordWithoutEmitterIsInterpreted() {
        IrOp crc = IrOpSamples.sample(IntegerOp.Crc32.class);
        assertNull(AsmEmitterRegistry.lookup(crc));
        assertFalse(AsmNativePolicy.supports(crc));
        IrBlock block = new IrBlock(BLOCK_START, BLOCK_START + INSTRUCTION_BYTES, List.of(crc));
        AsmBlockCompiler compiler = new AsmBlockCompiler();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> compiler.compile("RegistryNoEmitter", block));
        assertTrue(failure.getMessage().startsWith("Unsupported IR op in native compile"), failure.getMessage());
        assertTrue(compiler.compilePerOp("RegistryNoEmitter", block).length > 0);
    }

    @ParameterizedTest
    @MethodSource("vfpCarveOuts")
    void vfpCarveOuts(Class<? extends IrOp> recordClass, Map<String, Object> fields, boolean expectedSupport) {
        assertEquals(expectedSupport, AsmNativePolicy.supports(IrOpSamples.sample(recordClass, fields)));
    }

    static Stream<Arguments> vfpCarveOuts() {
        return Stream.of(
                Arguments.of(VfpOp.Alu.class, Map.of("op", VfpOp.VfpOperation.ADD), true),
                Arguments.of(VfpOp.Alu.class, Map.of("op", VfpOp.VfpOperation.MAXNM), false),
                Arguments.of(VfpOp.Alu.class, Map.of("op", VfpOp.VfpOperation.MINNM), false),
                Arguments.of(VfpOp.CoreTransfer.class, Map.of(), true),
                Arguments.of(VfpOp.CoreTransfer.class, Map.of("halfWidth", true), false),
                Arguments.of(VfpOp.CoreTransfer.class, Map.of("laneBits", 8), false));
    }
}
