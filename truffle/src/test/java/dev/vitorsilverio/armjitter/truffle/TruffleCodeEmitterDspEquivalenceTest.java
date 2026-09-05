package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A10.6 — `DspDualMultiply`/`DspTopWordMultiply` (`SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`/
/// `SMUAD`/`SMUSD` e `SMMLA{R}`/`SMMLS{R}`/`SMMUL`, ARMv6, B1.3/B3.1) no backend Truffle
/// (`MultiplyOpNode`, A10.6). Como o nó delega ao MESMO {@code IrAluExecutor} que o interpretador
/// usa (G1 — o interpretador é o oráculo), a equivalência é estrutural; estes testes cobrem as
/// bordas de semântica listadas no Aceite da A10.6 (variante `X`, acumulador, forma longa, `Q`
/// sticky, arredondamento `R`) e confirmam que o backend compila nativamente (sem fallback).
class TruffleCodeEmitterDspEquivalenceTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    private final CodeEmitter referenceEmitter = new InterpretedCodeEmitter();

    private static IrBlock liftArmV6(int... words) {
        ByteArrayAddressSpace memory = new ByteArrayAddressSpace(words.length * 4);
        for (int i = 0; i < words.length; i++) {
            memory.write32(i * 4, words[i]);
        }
        return new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder())
                .lift(memory, 0, words.length);
    }

    private void assertDspBlockEquivalent(IrBlock block, int r1, int r2, int r3) {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV6K);
        harness.assertEquivalent(referenceEmitter, emitter, block, () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            for (ArmCore core : List.of(reference, candidate)) {
                core.setRegister(1, r1);
                core.setRegister(2, r2);
                core.setRegister(3, r3);
            }
            return new EquivalencePair(reference, candidate);
        });
        assertEquals(1L, emitter.nativeBlockCount(), "bloco DSP deve compilar nativamente (A10.6)");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void smuadWithoutAccumulatorMatchesInterpreter() {
        // SMUAD r0, r1, r2 (Ra=15, sentinela "sem acumulador")
        IrBlock block = liftArmV6(0xE700_F211);
        assertDspBlockEquivalent(block, 0x0002_0003, 0x0004_0005, 0);
    }

    @Test
    void smladAccumulatesMatchesInterpreter() {
        // SMLAD r0, r1, r2, r3 (forma curta, acumula em Ra=r3)
        IrBlock block = liftArmV6(0xE700_3211);
        assertDspBlockEquivalent(block, 0x0002_0003, 0x0004_0005, 100);
    }

    @Test
    void smladxExchangesHalfwordsMatchesInterpreter() {
        // SMLADX r0, r1, r2, r3 — variante X troca os halfwords de Rm antes de multiplicar
        IrBlock block = liftArmV6(0xE700_3231);
        assertDspBlockEquivalent(block, 0x0002_0003, 0x0004_0005, 100);
    }

    @Test
    void smlsdSubtractsProductsMatchesInterpreter() {
        // SMLSD r0, r1, r2, r3
        IrBlock block = liftArmV6(0xE700_3251);
        assertDspBlockEquivalent(block, 0x0002_0003, 0x0004_0005, 100);
    }

    @Test
    void smladSetsStickyQOnOverflowMatchesInterpreter() {
        // SMLAD r0, r1, r2, r3 com produtos e acumulador dimensionados para estourar 32 bits.
        IrBlock block = liftArmV6(0xE700_3211);
        assertDspBlockEquivalent(block, 0x7FFF_7FFF, 0x7FFF_7FFF, 0x7FFF_FFFF);
    }

    @Test
    void smlaldLongFormAccumulatesIn64BitsMatchesInterpreter() {
        // SMLALD r0, r3, r1, r2 (RdHi=r0, RdLo=r3, forma longa: {r0:r3} += produtos)
        IrBlock block = liftArmV6(0xE744_5211);
        assertDspBlockEquivalent(block, 0x0002_0003, 0x0004_0005, 0x1);
    }

    @Test
    void smlaldxLongFormExchangedMatchesInterpreter() {
        // SMLALDX r0, r3, r1, r2
        IrBlock block = liftArmV6(0xE744_5231);
        assertDspBlockEquivalent(block, 0x0002_0003, 0x0004_0005, 0x1);
    }

    @Test
    void smmulWithoutAccumulatorMatchesInterpreter() {
        // SMMUL r0, r1, r2 (Ra=15, sentinela "sem acumulador")
        IrBlock block = liftArmV6(0xE750_F211);
        assertDspBlockEquivalent(block, 0x1234_5678, 0x0000_0002, 0);
    }

    @Test
    void smmlaWithoutRoundingMatchesInterpreter() {
        // SMMLA r0, r1, r2, r3
        IrBlock block = liftArmV6(0xE750_3211);
        assertDspBlockEquivalent(block, 0x1234_5678, 0x0000_0002, 5);
    }

    @Test
    void smmlarWithRoundingDiffersButMatchesInterpreter() {
        // SMMLAR r0, r1, r2, r3 — o bit de arredondamento (R) é o que mais se erra na família.
        IrBlock block = liftArmV6(0xE750_3231);
        assertDspBlockEquivalent(block, 0x1234_5678, 0x0000_0002, 5);
    }

    @Test
    void smmlsSubtractsMatchesInterpreter() {
        // SMMLS r0, r1, r2, r3
        IrBlock block = liftArmV6(0xE750_3251);
        assertDspBlockEquivalent(block, 0x1234_5678, 0x0000_0002, 5);
    }
}
