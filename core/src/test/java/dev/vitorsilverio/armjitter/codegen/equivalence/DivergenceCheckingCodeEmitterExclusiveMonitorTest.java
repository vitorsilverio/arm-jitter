package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Regressão E3: `STREX` que segue um `LDREX` de um BLOCO IR ANTERIOR — padrão real de
/// spinlock/retry, onde o branch de retry sempre separa os dois em blocos distintos — precisa
/// suceder sob {@link DivergenceCheckingCodeEmitter} exatamente como sucede rodando só o
/// oráculo. `ArmV6ExclusiveNativeEquivalenceTest` (B1.6) já cobre LDREX+STREX no MESMO bloco via
/// {@link BlockEquivalenceHarness} e não pega este bug: aqui o candidato roda num `scratchCore`
/// isolado por bloco (via {@code ArmCore#loadState}, que limpa o monitor de propósito para
/// save-states reais) — sem transferir a reserva feita pelo bloco anterior, o candidato sempre
/// via o monitor aberto.
class DivergenceCheckingCodeEmitterExclusiveMonitorTest {
    private static IrBlock lift(TestAddressSpace memory, int startPc, int count) {
        return new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV6K), new StandardIrBuilder())
                .lift(memory, startPc, count);
    }

    @Test
    void strexInLaterBlockStillSeesMonitorMarkedByEarlierBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE190_2F9F);  // LDREX r2, [r0]
        memory.put32(4, 0xE180_1F92);  // STREX r1, r2, [r0]  (deve suceder: r1=0)
        IrBlock ldrexBlock = lift(memory, 0, 1);
        IrBlock strexBlock = lift(memory, 4, 1);

        CodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV6K);
        CodeEmitter candidate = new AsmCodeEmitter(ArmArchitecture.ARMV6K);
        CodeEmitter checking =
                new DivergenceCheckingCodeEmitter(reference, candidate, ArmArchitecture.ARMV6K);

        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);

        CompiledBlock compiledLdrex = checking.emit(ldrexBlock);
        compiledLdrex.execute(core);

        CompiledBlock compiledStrex = checking.emit(strexBlock);
        assertDoesNotThrow(() -> compiledStrex.execute(core),
                "STREX no bloco seguinte deveria concordar com o oráculo (reserva do bloco anterior)");
        assertEquals(0, core.register(1), "STREX deveria suceder: reserva feita no bloco anterior");
    }
}
