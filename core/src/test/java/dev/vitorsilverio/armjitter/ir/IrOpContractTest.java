package dev.vitorsilverio.armjitter.ir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.CpuSnapshot;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.ir.opt.DeadCodeEliminationPass;
import dev.vitorsilverio.armjitter.support.IrOpSamples;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Rede de segurança da E15 (task E15.1): TODO `record` permitido de {@link IrOp} passa por cada
/// ponto de roteamento do pipeline de 32 bits — os dois dispatchers do interpretador
/// ({@link IrBlockExecutor#execute} por `Kind` e {@link IrBlockExecutor#executeOp} por tipo), a
/// política de emissão nativa ({@link AsmNativePolicy}) contra o compilador que ela autoriza
/// ({@link AsmBlockCompiler}) e a {@link DeadCodeEliminationPass}.
///
/// A lista de records vem de {@link IrOpSamples} por reflexão: um `record` novo ganha os três
/// casos sem ninguém registrá-lo aqui. O que estes testes fixam é **roteamento** (a op chega ao
/// executor certo pelos dois caminhos e produz o mesmo estado), não a semântica de cada instrução
/// — essa continua nos testes de cada família.
class IrOpContractTest {
    private static final int MEMORY_SIZE = 0x1_0000;
    private static final int BLOCK_START = 0x100;
    private static final int INSTRUCTION_SIZE = 4;
    private static final int BLOCK_END = BLOCK_START + INSTRUCTION_SIZE;
    /// Cada registrador geral começa apontando para uma janela própria de memória válida, de modo
    /// que a amostra com todos os campos em `0` (base = `R0`) acesse um endereço mapeado.
    private static final int REGISTER_SEED_BASE = 0x1000;
    private static final int REGISTER_SEED_STRIDE = 0x100;
    private static final int GENERAL_REGISTER_COUNT = 15;
    /// Padrão de preenchimento da memória (palavras distintas por endereço, nenhuma zero).
    private static final int MEMORY_PATTERN_MULTIPLIER = 0x0101_0101;
    private static final int MEMORY_PATTERN_OFFSET = 0x9E37_79B9;
    /// CPSR válido (modo System, estado ARM) — o que `RFE` lê da memória e `ERET` do SPSR tem que
    /// ser um modo existente; o padrão de preenchimento não é.
    private static final int VALID_SAVED_CPSR = CpuMode.SYSTEM.bits();
    /// Palavras em volta de `R0` que recebem {@link #VALID_SAVED_CPSR} (cobre os quatro modos de
    /// endereçamento de `RFE`: a dupla lida fica em `[R0-8, R0+8)`).
    private static final int SAVED_STATE_WINDOW_BYTES = 8;
    private static final int WORD_BYTES = 4;
    /// Valor fora de `IrOp.Kind` — nenhum `record` devolve isso de `kind()`.
    private static final int UNKNOWN_KIND = -1;
    /// Índice do PC (`R15`) — escrever nele é o que tira várias ops da emissão nativa.
    private static final int PC = 15;
    /// `op2` de `SMLAWy`/`SMULWy` em {@link IntegerOp.DspMultiply} — a única forma que também lê `Rn`.
    private static final int DSP_MULTIPLY_WORD_BY_HALFWORD = 2;

    private static final ArmArchitecture A_PROFILE = ArmArchitecture.ARMV8_6A_32_NEON;
    private static final ArmArchitecture M_PROFILE = ArmArchitecture.ARMV8_1M_MVE;

    static Stream<Named<IrOp>> samples() {
        return IrOpSamples.irOps().stream().map(op -> Named.of(displayName(op.getClass()), op));
    }

    /// `Família.Record` — o nome simples sozinho se repete entre famílias (`IntegerOp.Alu` ×
    /// `VfpOp.Alu`).
    private static String displayName(Class<?> recordClass) {
        return recordClass.getEnclosingClass().getSimpleName() + "." + recordClass.getSimpleName();
    }

    private static ArmArchitecture architectureOf(IrOp op) {
        return IrOpSamples.requiresMProfile(op) ? M_PROFILE : A_PROFILE;
    }

    /// `[Fetch, Cycle, op]`, a forma que o lifter emite por instrução (G4). `AdvanceVpt`/
    /// `AdvanceEci` só existem depois de uma op MVE na mesma instrução, então ganham uma antes.
    private static IrBlock blockOf(IrOp op) {
        List<IrOp> ops = new ArrayList<>();
        ops.add(new IrOp.Fetch(BLOCK_START, INSTRUCTION_SIZE));
        ops.add(new IrOp.Cycle(1));
        if (op instanceof MvePredicationOp.AdvanceVpt || op instanceof MvePredicationOp.AdvanceEci) {
            ops.add(IrOpSamples.sample(MveIntegerOp.Vector2Op.class));
        }
        ops.add(op);
        return new IrBlock(BLOCK_START, BLOCK_END, ops);
    }

    private static ArmCore newCore(ArmArchitecture architecture) {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        for (int address = 0; address < MEMORY_SIZE; address += WORD_BYTES) {
            memory.put32(address, address * MEMORY_PATTERN_MULTIPLIER + MEMORY_PATTERN_OFFSET);
        }
        for (int offset = -SAVED_STATE_WINDOW_BYTES; offset < SAVED_STATE_WINDOW_BYTES; offset += WORD_BYTES) {
            memory.put32(REGISTER_SEED_BASE + offset, VALID_SAVED_CPSR);
        }
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), architecture);
        if (architecture == M_PROFILE) {
            core.setExceptionModel(new MProfileExceptionModel());
            core.cpsr().setThumbMode(true);
        } else {
            core.setSpsr(core.mode(), VALID_SAVED_CPSR);
        }
        for (int r = 0; r < GENERAL_REGISTER_COUNT; r++) {
            core.setRegister(r, REGISTER_SEED_BASE + r * REGISTER_SEED_STRIDE);
        }
        core.setProgramCounter(BLOCK_START);
        return core;
    }

    private static byte[] memoryOf(ArmCore core) {
        byte[] bytes = new byte[MEMORY_SIZE];
        for (int address = 0; address < MEMORY_SIZE; address++) {
            bytes[address] = (byte) core.memory().read8(address);
        }
        return bytes;
    }

    /// Os dois dispatchers do interpretador levam a op ao MESMO executor: mesmo estado de core e
    /// de memória partindo do mesmo estado inicial. Ops que entram em exceção de guest (`Undefined`,
    /// `Swi`, `Hvc`, ...) são caso válido — compara-se o estado depois da entrada na exceção.
    @ParameterizedTest
    @MethodSource("samples")
    void executeBlockAndExecuteOpAgree(IrOp op) {
        ArmArchitecture architecture = architectureOf(op);
        IrBlockExecutor executor = new IrBlockExecutor(architecture);
        IrBlock block = blockOf(op);

        ArmCore viaBlock = newCore(architecture);
        int cycles = executor.execute(block, viaBlock);

        ArmCore viaOp = newCore(architecture);
        boolean pcChanged = false;
        for (IrOp each : block.operations()) {
            pcChanged |= executor.executeOp(viaOp, each, block.endPc());
        }
        if (!pcChanged) {
            viaOp.setProgramCounter(block.endPc());
        }

        int expectedCycles = block.operations().stream()
                .mapToInt(each -> each instanceof IrOp.Cycle cycle ? cycle.count() : 0).sum();
        assertEquals(expectedCycles, cycles);
        CpuSnapshot.capture(viaBlock).assertEqualTo(CpuSnapshot.capture(viaOp), displayName(op.getClass()));
        assertEquals(viaBlock.vpr().value(), viaOp.vpr().value());
        assertArrayEquals(memoryOf(viaBlock), memoryOf(viaOp));
    }

    /// Política e compilador não divergem: o que {@link AsmNativePolicy#supports(IrOp)} aceita, o
    /// {@link AsmBlockCompiler} compila (sem cair no `default -> throw` do `switch` de emissão).
    @ParameterizedTest
    @MethodSource("samples")
    void nativePolicyAndCompilerAgree(IrOp op) {
        boolean supported = assertDoesNotThrow(() -> AsmNativePolicy.supports(op));
        if (supported) {
            byte[] bytecode = new AsmBlockCompiler().compile("IrOpContract", blockOf(op));
            assertTrue(bytecode.length > 0);
        }
    }

    /// Os carve-outs condicionais de {@link AsmNativePolicy}: a MESMA op é nativa no caminho comum
    /// e recusada num caso específico (escrita em PC, BLX, acesso não privilegiado, `ORN`). Cada
    /// lado de cada condição aparece aqui; o lado aceito também tem de compilar.
    @ParameterizedTest
    @MethodSource("carveOuts")
    void nativePolicyCarveOuts(IrOp variant, boolean expectedSupport) {
        assertEquals(expectedSupport, AsmNativePolicy.supports(variant));
        if (expectedSupport) {
            assertTrue(new AsmBlockCompiler().compile("IrOpContract", blockOf(variant)).length > 0);
        }
    }

    static Stream<Arguments> carveOuts() {
        return Stream.of(
                carveOut(false, IntegerOp.Alu.class, Map.of("dst", PC, "setFlags", true)),
                carveOut(true, IntegerOp.Alu.class, Map.of("dst", PC)),
                carveOut(true, IntegerOp.Alu.class, Map.of("setFlags", true)),
                carveOut(false, IntegerOp.Alu.class, Map.of("opcode", IrOpCode.ORN)),
                carveOut(false, IntegerOp.Saturating.class, Map.of("dst", PC)),
                carveOut(false, IntegerOp.DspMultiply.class, Map.of("dst", PC)),
                carveOut(false, IntegerOp.DspMultiply.class, Map.of("op2", DSP_MULTIPLY_WORD_BY_HALFWORD, "rn", PC)),
                carveOut(true, IntegerOp.DspMultiply.class, Map.of("op2", DSP_MULTIPLY_WORD_BY_HALFWORD)),
                carveOut(false, MemoryOp.DoubleTransfer.class, Map.of("load", true, "first", PC)),
                carveOut(false, MemoryOp.DoubleTransfer.class, Map.of("load", true, "second", PC)),
                carveOut(true, MemoryOp.DoubleTransfer.class, Map.of("load", true, "second", 1)),
                carveOut(true, MemoryOp.DoubleTransfer.class, Map.of("first", PC)),
                carveOut(false, MemoryOp.Load.class, Map.of("unprivileged", true)),
                carveOut(false, MemoryOp.Store.class, Map.of("unprivileged", true)),
                carveOut(false, BranchOp.BranchExchange.class, Map.of("link", true)),
                carveOut(false, BranchOp.ThumbBlSuffix.class, Map.of("exchange", true)));
    }

    private static Arguments carveOut(boolean expectedSupport, Class<? extends IrOp> recordClass,
            Map<String, Object> fields) {
        IrOp variant = IrOpSamples.sample(recordClass, fields);
        return Arguments.of(Named.of(displayName(recordClass) + fields, variant), expectedSupport);
    }

    /// A DCE conhece a op (uso/definição de registradores sem lançar) e não a remove: na saída do
    /// bloco todo registrador é vivo, então nada isolado num bloco de uma instrução é código morto.
    @ParameterizedTest
    @MethodSource("samples")
    void deadCodeEliminationKeepsTheOp(IrOp op) {
        IrBlock block = blockOf(op);
        assertSame(block, new DeadCodeEliminationPass().optimize(block));
    }

    /// `AdvanceVpt`/`AdvanceEci` são pulados em {@link IrBlockExecutor#execute} quando a op anterior
    /// do mesmo bloco já mudou o PC (fault de ECI reservado): o estado é o de quem nunca avançou.
    @ParameterizedTest
    @MethodSource("advanceOps")
    void advanceIsSkippedAfterThePcChanged(IrOp advance) {
        IrBlockExecutor executor = new IrBlockExecutor(M_PROFILE);
        List<IrOp> prefix = List.of(
                new IrOp.Fetch(BLOCK_START, INSTRUCTION_SIZE), new IrOp.Cycle(1), IrOpSamples.sample(SystemOp.Undefined.class));
        List<IrOp> withAdvance = new ArrayList<>(prefix);
        withAdvance.add(advance);

        ArmCore without = newCore(M_PROFILE);
        executor.execute(new IrBlock(BLOCK_START, BLOCK_END, prefix), without);
        ArmCore with = newCore(M_PROFILE);
        executor.execute(new IrBlock(BLOCK_START, BLOCK_END, withAdvance), with);

        CpuSnapshot.capture(without).assertEqualTo(CpuSnapshot.capture(with), displayName(advance.getClass()));
        assertEquals(without.vpr().value(), with.vpr().value());
    }

    static Stream<Named<IrOp>> advanceOps() {
        return Stream.of(MvePredicationOp.AdvanceVpt.class, MvePredicationOp.AdvanceEci.class)
                .map(recordClass -> Named.of(displayName(recordClass), IrOpSamples.<IrOp>sample(recordClass)));
    }

    /// Um `Kind` que nenhum `record` devolve é recusado, nunca despachado para o executor errado.
    @Test
    void unknownKindIsRejected() {
        IrBlock block = blockOf(IrOpSamples.sample(SystemOp.MemoryBarrier.class));
        block.kindsArray()[block.kindsArray().length - 1] = UNKNOWN_KIND;

        assertThrows(IllegalStateException.class,
                () -> new IrBlockExecutor(A_PROFILE).execute(block, newCore(A_PROFILE)));
    }
}
