package dev.vitorsilverio.armjitter.ir64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.codegen.equivalence.Aarch64CpuSnapshot;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64BlockCompiler;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core64.Aarch64BreakpointException;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64HypervisorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64SecureMonitorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.IrOpSamples;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Rede de segurança da E15 (task E15.1), lado A64: TODO `record` permitido de {@link Ir64Op}
/// passa pelos dois pontos de entrada do interpretador ({@link Ir64BlockExecutor#executeBlock} e
/// {@link Ir64BlockExecutor#executeOp}) e pela política de emissão nativa
/// ({@link Ir64NativePolicy}) contra o compilador que ela autoriza ({@link Ir64BlockCompiler}).
/// Mesmo formato de `IrOpContractTest` (32 bits); a lista de records vem de {@link IrOpSamples}
/// por reflexão.
class Ir64OpContractTest {
    private static final int MEMORY_SIZE = 0x1_0000;
    private static final long BLOCK_START = 0x100;
    private static final int INSTRUCTION_SIZE = 4;
    private static final long BLOCK_END = BLOCK_START + INSTRUCTION_SIZE;
    /// Cada registrador geral começa apontando para uma janela própria de memória válida, de modo
    /// que a amostra com todos os campos em `0` (base = `X0`) acesse um endereço mapeado.
    private static final long REGISTER_SEED_BASE = 0x1000;
    private static final long REGISTER_SEED_STRIDE = 0x100;
    private static final int GENERAL_REGISTER_COUNT = 31;
    /// Padrão de preenchimento da memória (palavras distintas por endereço, nenhuma zero).
    private static final int MEMORY_PATTERN_MULTIPLIER = 0x0101_0101;
    private static final int MEMORY_PATTERN_OFFSET = 0x9E37_79B9;
    private static final int WORD_BYTES = 4;
    /// Toda feature declarada: nenhuma amostra é recusada por falta de extensão no preset, e o
    /// core aloca o estado escalável (SVE) e matricial (SME) que as famílias `Sve*`/`Sme*` leem.
    private static final Aarch64Architecture ALL_FEATURES = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_5_A, "ARMv9.5-A + todas as features", Aarch64Feature.values());

    /// Core de uma amostra e a memória física por trás dele (para comparar o que foi escrito).
    private record Machine(Aarch64Core core, TestAddressSpace memory) {
    }

    static Stream<Named<Ir64Op>> samples() {
        return IrOpSamples.ir64Ops().stream().map(op -> Named.of(op.getClass().getSimpleName(), op));
    }

    private static Ir64Block blockOf(Ir64Op... ops) {
        return new Ir64Block(BLOCK_START, BLOCK_END, List.of(ops));
    }

    /// Core em `EL1` (as ops de sistema — `ERET`, `MSR`/`MRS`, `AT` — não existem em `EL0`) sobre
    /// uma MMU desligada: o `TranslatingAddressSpace64` é quem hospeda os registradores de sistema
    /// do regime de tradução e atende `AT`.
    private static Machine newMachine() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        for (int address = 0; address < MEMORY_SIZE; address += WORD_BYTES) {
            memory.put32(address, address * MEMORY_PATTERN_MULTIPLIER + MEMORY_PATTERN_OFFSET);
        }
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(memory));
        mmu.setMmuEnabled(false);
        Aarch64Core core = new Aarch64Core(mmu, ALL_FEATURES);
        core.setSystemRegisterBus(new Aarch64VmsaSystemRegisters(mmu, core));
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        for (int r = 0; r < GENERAL_REGISTER_COUNT; r++) {
            core.setX(r, REGISTER_SEED_BASE + r * REGISTER_SEED_STRIDE);
        }
        core.setSp(REGISTER_SEED_BASE + GENERAL_REGISTER_COUNT * REGISTER_SEED_STRIDE);
        core.setProgramCounter(BLOCK_START);
        return new Machine(core, memory);
    }

    private static byte[] memoryOf(Machine machine) {
        byte[] bytes = new byte[MEMORY_SIZE];
        for (int address = 0; address < MEMORY_SIZE; address++) {
            bytes[address] = (byte) machine.memory().read8(address);
        }
        return bytes;
    }

    /// {@link Ir64BlockExecutor#executeBlock} sobre `[Fetch, Cycle, op]` e
    /// {@link Ir64BlockExecutor#executeOp} sobre a op sozinha produzem o mesmo estado. As cinco
    /// exceções de controle que `executeOp` propaga (falta de tradução, `BRK`, indefinida, `HVC`,
    /// `SMC`) são caso válido: o chamador as entrega ao core, e compara-se o estado depois da
    /// entrada na exceção. `Fetch`/`Cycle` não são instrução — `executeOp` os recusa.
    @ParameterizedTest
    @MethodSource("samples")
    void executeBlockAndExecuteOpAgree(Ir64Op op) {
        Ir64BlockExecutor executor = new Ir64BlockExecutor(ALL_FEATURES);
        Ir64Op.Fetch fetch = new Ir64Op.Fetch(BLOCK_START, INSTRUCTION_SIZE);
        Ir64Op.Cycle cycle = new Ir64Op.Cycle(1);

        Machine viaBlock = newMachine();
        executor.executeBlock(viaBlock.core(), blockOf(fetch, cycle, op));

        if (op instanceof Ir64Op.Fetch || op instanceof Ir64Op.Cycle) {
            assertThrows(IllegalStateException.class, () -> executor.executeOp(newMachine().core(), op));
            return;
        }

        Machine viaOp = newMachine();
        Aarch64Core core = viaOp.core();
        executor.executeBlock(core, blockOf(fetch, cycle));
        try {
            if (!executor.executeOp(core, op)) {
                core.setProgramCounter(BLOCK_END);
            }
        } catch (MemoryTranslationException64 fault) {
            core.enterMemoryAbort(BLOCK_START, fault);
        } catch (Aarch64BreakpointException brk) {
            core.enterBreakpointException(BLOCK_START, brk.immediate());
        } catch (Aarch64UndefinedInstructionException undefined) {
            core.enterUndefinedInstructionException(BLOCK_START);
        } catch (Aarch64HypervisorCallException hvc) {
            core.enterHypervisorCall(BLOCK_START);
        } catch (Aarch64SecureMonitorCallException smc) {
            core.enterSecureMonitorCall(BLOCK_START);
        }

        Aarch64CpuSnapshot.capture(viaBlock.core())
                .assertEqualTo(Aarch64CpuSnapshot.capture(core), op.getClass().getSimpleName());
        assertEquals(viaBlock.core().exceptionState().currentEl(), core.exceptionState().currentEl());
        assertArrayEquals(memoryOf(viaBlock), memoryOf(viaOp));
    }

    /// As ops que só existem para lançar a exceção de controle: `executeOp` propaga exatamente a
    /// exceção que o chamador (`executeBlock`, `step`, o bytecode gerado) converte em entrada de
    /// exceção do guest. O executor delas nunca retorna, então o JaCoCo não marca o `case` como
    /// coberto — este teste é a prova de que o dispatch chega lá.
    @ParameterizedTest
    @MethodSource("controlExceptions")
    void controlExceptionOpsPropagateTheirException(Ir64Op op, Class<? extends RuntimeException> expected) {
        Ir64BlockExecutor executor = new Ir64BlockExecutor(ALL_FEATURES);
        assertThrows(expected, () -> executor.executeOp(newMachine().core(), op));
    }

    static Stream<Arguments> controlExceptions() {
        return Stream.of(
                Arguments.of(Named.of("Breakpoint", IrOpSamples.<Ir64Op>sample(Ir64Op.Breakpoint.class)),
                        Aarch64BreakpointException.class),
                Arguments.of(Named.of("UndefinedInstructionTrap",
                        IrOpSamples.<Ir64Op>sample(Ir64Op.UndefinedInstructionTrap.class)),
                        Aarch64UndefinedInstructionException.class),
                Arguments.of(Named.of("PrivilegedCall[hvc]",
                        IrOpSamples.<Ir64Op>sample(Ir64Op.PrivilegedCall.class, Map.of("isHvc", true))),
                        Aarch64HypervisorCallException.class),
                Arguments.of(Named.of("PrivilegedCall[smc]",
                        IrOpSamples.<Ir64Op>sample(Ir64Op.PrivilegedCall.class)),
                        Aarch64SecureMonitorCallException.class));
    }

    /// Política e compilador não divergem: o que {@link Ir64NativePolicy#supports(Ir64Op)} aceita,
    /// o {@link Ir64BlockCompiler} compila.
    @ParameterizedTest
    @MethodSource("samples")
    void nativePolicyAndCompilerAgree(Ir64Op op) {
        boolean supported = assertDoesNotThrow(() -> Ir64NativePolicy.supports(op));
        if (supported) {
            byte[] bytecode = new Ir64BlockCompiler().compile("Ir64OpContract",
                    blockOf(new Ir64Op.Fetch(BLOCK_START, INSTRUCTION_SIZE), new Ir64Op.Cycle(1), op));
            assertTrue(bytecode.length > 0);
        }
    }
}
