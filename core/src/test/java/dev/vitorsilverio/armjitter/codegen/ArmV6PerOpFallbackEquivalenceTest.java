package dev.vitorsilverio.armjitter.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// Histórico da task B1.6: prova, op-a-op, que cada grupo de ops ARMv6 (B1.2-B1.4) que caía no
/// interpretado inline do modo {@code PER_OP} ganhou emissão nativa sem regressão de estado
/// (invariante G1). Todas as ops aqui cobertas já são nativas — a cobertura exaustiva (14
/// condições × 16 NZCV) de cada grupo vive em {@code ArmV6ExtendReverseNativeEquivalenceTest},
/// {@code ArmV6ParallelSaturateNativeEquivalenceTest} e {@code ArmV6ExclusiveNativeEquivalenceTest}.
/// As únicas ops ARMv6 que ainda passam por {@code PER_OP} são as instruções de sistema da B1.5
/// (CPS/SETEND/SRS/RFE/WFI — raras, kernel-only, ver {@code AsmNativePolicy}).
class ArmV6PerOpFallbackEquivalenceTest extends BlockEquivalenceTest {
    private final AsmCodeEmitter asmEmitter = new AsmCodeEmitter(
            ArmArchitecture.ARMV6K, AsmFallbackPolicy.PER_OP, IrOptimizer.identity());
    private final CodeEmitter v6Reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);

    @Test
    void extendReverseUmaalBlockIsNativeAndMatchesInterpreted() {
        // B1.6: SXT*/UXT*/REV*/UMAAL agora são nativos — este bloco não passa mais pelo fallback.
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE6AF_1470);  // SXTB r1, r0, ROR #8
        memory.put32(4, 0xE6BF_4F30);  // REV r4, r0
        memory.put32(8, 0xE6F3_5072);  // UXTAH r5, r3, r2
        memory.put32(12, 0xE043_2190); // UMAAL r2, r3, r0, r1
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder()).lift(memory, 0, 4);

        assertTrue(asmEmitter.isNativeSupported(block),
                "extend/reverse/UMAAL (B1.2) ganharam emissão nativa na task B1.6");
        harness.assertEquivalent(v6Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x0000FF00);
                    core.setRegister(1, 0x12345678);
                    core.setRegister(2, 0xFFFF0001);
                    core.setRegister(3, 0x8000FFFF);
                }));
        assertEquals(0, asmEmitter.perOpFallbackOpCount(),
                "extend/reverse/UMAAL não devem mais passar pelo fallback por-op");
    }

    @Test
    void parallelSimdBlockIsNativeAndMatchesInterpreted() {
        // B1.6: aritmética paralela ARMv6 (B1.3) agora é nativa — sem fallback por-op.
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE610_2F11);  // SADD16 r2, r0, r1
        memory.put32(4, 0xE660_3FF1);  // UQSUB8 r3, r0, r1
        memory.put32(8, 0xE650_4F31);  // UASX r4, r0, r1
        memory.put32(12, 0xE630_5F71); // SHSUB16 r5, r0, r1
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder()).lift(memory, 0, 4);

        assertTrue(asmEmitter.isNativeSupported(block),
                "a aritmética paralela ARMv6 (B1.3) ganhou emissão nativa na task B1.6");
        harness.assertEquivalent(v6Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x8000_FFFF);
                    core.setRegister(1, 0x0001_7FFF);
                }));
        assertEquals(0, asmEmitter.perOpFallbackOpCount(),
                "as ops paralelas não devem mais passar pelo fallback por-op");
    }

    @Test
    void packSaturateSelBlockIsNativeAndMatchesInterpreted() {
        // B1.6: SEL/PKH/SSAT/USAD (B1.3) agora são nativas — sem fallback por-op.
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE650_3F91);  // UADD8 r3, r0, r1 (produz GE)
        memory.put32(4, 0xE680_4FB1);  // SEL r4, r0, r1 (consome GE)
        memory.put32(8, 0xE680_5451);  // PKHTB r5, r0, r1, ASR #8
        memory.put32(12, 0xE6A7_6011); // SSAT r6, #8, r1
        memory.put32(16, 0xE787_2110); // USADA8 r7, r0, r1, r2
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder()).lift(memory, 0, 5);

        assertTrue(asmEmitter.isNativeSupported(block),
                "SEL/PKH/SSAT/USAD (B1.3) ganharam emissão nativa na task B1.6");
        harness.assertEquivalent(v6Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0xFF80_1234);
                    core.setRegister(1, 0x017F_4321);
                    core.setRegister(2, 1000);
                }));
        assertEquals(0, asmEmitter.perOpFallbackOpCount(),
                "as ops de pack/saturação não devem mais passar pelo fallback por-op");
    }

    @Test
    void exclusiveAccessBlockIsNativeAndMatchesInterpreted() {
        // B1.6: LDREX/STREX/CLREX (B1.4) agora são nativos — sem fallback por-op.
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE190_2F9F);  // LDREX r2, [r0]
        memory.put32(4, 0xE180_1F92);  // STREX r1, r2, [r0] (deve suceder)
        memory.put32(8, 0xF57F_F01F);  // CLREX
        memory.put32(12, 0xE180_3F91); // STREX r3, r1, [r0] (deve falhar: monitor aberto)
        IrBlock block = new StandardIrBlockLifter(
                new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder()).lift(memory, 0, 4);

        assertTrue(asmEmitter.isNativeSupported(block),
                "acessos exclusivos (B1.4) ganharam emissão nativa na task B1.6");
        harness.assertEquivalent(v6Reference, asmEmitter, block,
                EquivalenceTestSupport.independentPair(memory, core -> {
                    core.setRegister(0, 0x10);
                    core.setRegister(2, 0xCAFEBABE);
                }));
        assertEquals(0, asmEmitter.perOpFallbackOpCount(),
                "os acessos exclusivos não devem mais passar pelo fallback por-op");
    }
}
