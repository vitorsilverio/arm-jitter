package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// A10.5 — `Hvc`/`Smc`/`Eret`/`MrsBank`/`MsrBank`/`Breakpoint` (Hyp/Monitor de 32 bits — B9.8 —
/// e Virtualization Extensions — B22.5, `ArmArchitecture.ARMV7A` já declara as duas desde a
/// B22.5) no backend Truffle (`SystemOpNode`, A10.5). Como o nó delega DIRETO ao MESMO
/// {@link dev.vitorsilverio.armjitter.codegen.executor.IrSystemExecutor} que o interpretador usa
/// (G1), a equivalência é estrutural; estes testes cobrem o Aceite da A10.5: os 6 `Kind` lendo/
/// escrevendo o banco de outro modo ou entrando em exceção de guest, e o cuidado central da
/// especificação — um bloco com `Hvc`/`Smc`/`Breakpoint` no MEIO (`StandardIrBlockLifter` já
/// termina o `IrBlock` logo depois desses 6 `Kind`, então "meio do bloco" aqui significa "última
/// op do MESMO bloco lifted", com uma op ANTES cujo efeito deve sobreviver) e um `Eret` que muda
/// modo/PC — e confirmam que o backend compila nativamente (sem fallback).
class TruffleCodeEmitterSystemEquivalenceTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    private final CodeEmitter referenceEmitter = new InterpretedCodeEmitter();

    private static IrBlock liftArmV7(int... words) {
        ByteArrayAddressSpace memory = new ByteArrayAddressSpace(words.length * 4);
        for (int i = 0; i < words.length; i++) {
            memory.write32(i * 4, words[i]);
        }
        return new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV7A), new StandardIrBuilder())
                .lift(memory, 0, words.length);
    }

    private void assertBlockEquivalent(IrBlock block, Consumer<ArmCore> setup) {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV7A);
        harness.assertEquivalent(referenceEmitter, emitter, block, () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
            setup.accept(reference);
            setup.accept(candidate);
            return new EquivalencePair(reference, candidate);
        });
        assertEquals(1L, emitter.nativeBlockCount(), "bloco de sistema deve compilar nativamente (A10.5)");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void hvcEntersHypModeFromSupervisorMode() {
        // HVC #0x1234, modo inicial SUPERVISOR (reset) -> entra em Hyp mode.
        IrBlock block = liftArmV7(0xE141_2374);
        assertBlockEquivalent(block, core -> { });
    }

    @Test
    void hvcIsUndefinedInUserMode() {
        IrBlock block = liftArmV7(0xE141_2374);
        assertBlockEquivalent(block, core -> core.switchMode(CpuMode.USER));
    }

    @Test
    void smcEntersMonitorModeWithLrMonAsReturnAddress() {
        // SMC #5, modo inicial SUPERVISOR (reset) -> entra em Monitor mode.
        IrBlock block = liftArmV7(0xE160_0075);
        assertBlockEquivalent(block, core -> { });
    }

    @Test
    void smcIsUndefinedInUserMode() {
        IrBlock block = liftArmV7(0xE160_0075);
        assertBlockEquivalent(block, core -> core.switchMode(CpuMode.USER));
    }

    @Test
    void eretReturnsFromHypModeViaElrHyp() {
        // ERET em Hyp mode: PC <- ELR_hyp, CPSR <- SPSR_hyp (não LR, categoria própria de RFE).
        IrBlock block = liftArmV7(0xE160_006E);
        assertBlockEquivalent(block, core -> {
            core.switchMode(CpuMode.HYP);
            core.setElrHyp(0x9000);
            core.setSpsr(CpuMode.HYP, (core.cpsr().get() & ~0x1F) | CpuMode.SUPERVISOR.bits());
        });
    }

    @Test
    void eretReturnsFromSupervisorModeViaLrSvc() {
        IrBlock block = liftArmV7(0xE160_006E);
        assertBlockEquivalent(block, core -> {
            core.setRegister(14, 0x9000);
            core.setSpsr(CpuMode.SUPERVISOR, (core.cpsr().get() & ~0x1F) | CpuMode.SYSTEM.bits());
        });
    }

    @Test
    void eretIsUndefinedInUserMode() {
        IrBlock block = liftArmV7(0xE160_006E);
        assertBlockEquivalent(block, core -> core.switchMode(CpuMode.USER));
    }

    @Test
    void mrsBankReadsSpUsrFromAnotherModeWithoutTouchingActiveMode() {
        // MRS r0, SP_usr, executado a partir de SUPERVISOR (SP_usr é o banco de outro modo).
        IrBlock block = liftArmV7(0xE105_0200);
        assertBlockEquivalent(block, core -> {
            core.switchMode(CpuMode.SYSTEM); // SP_usr/SP_sys compartilhado
            core.setRegister(13, 0x1234);
            core.switchMode(CpuMode.SUPERVISOR);
        });
    }

    @Test
    void mrsBankIsUndefinedInUserMode() {
        IrBlock block = liftArmV7(0xE105_0200);
        assertBlockEquivalent(block, core -> core.switchMode(CpuMode.USER));
    }

    @Test
    void msrBankWritesSpUsrOfAnotherModeWithoutChangingActiveMode() {
        // MSR SP_usr, r0, executado a partir de SUPERVISOR.
        IrBlock block = liftArmV7(0xE125_F200);
        assertBlockEquivalent(block, core -> core.setRegister(0, 0x5678));
    }

    @Test
    void msrBankIsUndefinedInUserMode() {
        IrBlock block = liftArmV7(0xE125_F200);
        assertBlockEquivalent(block, core -> core.switchMode(CpuMode.USER));
    }

    @Test
    void breakpointBecomesUndefinedWithoutDispatcherHandler() {
        // BKPT #0 — BkptDispatcher.empty() (default do ArmCore) não tem handler nem fallback.
        IrBlock block = liftArmV7(0xE120_0070);
        assertBlockEquivalent(block, core -> { });
    }

    @Test
    void hvcInTheMiddleOfABlockRunsThePrecedingOpFirst() {
        // ADD r0, r0, #1 ; HVC #0x1234 — StandardIrBlockLifter já termina o bloco em HVC, então
        // as duas ops vivem no MESMO IrBlock; o Aceite central da A10.5 é que o ADD executou antes
        // da entrada em Hyp mode nos dois backends de forma idêntica.
        IrBlock block = liftArmV7(0xE280_0001, 0xE141_2374);
        assertBlockEquivalent(block, core -> core.setRegister(0, 0x40));
    }

    @Test
    void smcInTheMiddleOfABlockRunsThePrecedingOpFirst() {
        IrBlock block = liftArmV7(0xE280_0001, 0xE160_0075);
        assertBlockEquivalent(block, core -> core.setRegister(0, 0x40));
    }

    @Test
    void breakpointInTheMiddleOfABlockRunsThePrecedingOpFirst() {
        IrBlock block = liftArmV7(0xE280_0001, 0xE120_0070);
        assertBlockEquivalent(block, core -> core.setRegister(0, 0x40));
    }

    @Test
    void eretInTheMiddleOfABlockRunsThePrecedingOpFirstAndEndsTheBlock() {
        // ADD r0, r0, #1 ; ERET — o ADD roda, o ERET muda modo/PC e termina o bloco (sem nó
        // seguinte a "continuar por engano": a lista de kinds que terminam bloco do lifter já
        // garante isso por construção).
        IrBlock block = liftArmV7(0xE280_0001, 0xE160_006E);
        assertBlockEquivalent(block, core -> {
            core.setRegister(0, 0x40);
            core.switchMode(CpuMode.HYP);
            core.setElrHyp(0x9000);
            core.setSpsr(CpuMode.HYP, (core.cpsr().get() & ~0x1F) | CpuMode.SUPERVISOR.bits());
        });
    }
}
