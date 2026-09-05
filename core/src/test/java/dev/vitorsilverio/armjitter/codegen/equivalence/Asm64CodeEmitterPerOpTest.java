package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.Asm64FallbackPolicy;
import dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Aceite da task C12.2: {@link Asm64FallbackPolicy#PER_OP} produz o MESMO
/// {@link Aarch64CpuSnapshot}/ciclos que {@link InterpretedIr64CodeEmitter} (G1) para blocos MISTOS
/// (ops nativas + pelo menos uma fora de {@link Ir64NativePolicy}), inclusive quando a op
/// interpretada lança uma das 5 exceções de controle. {@link Ir64Op.Fp64ConditionalSelect}
/// (`FCSEL`) é a op "fora da política" usada nos testes de fluxo normal — não suportada
/// nativamente hoje (C12.4 é quem fecha esse gap, FP escalar restante), não lança, escreve um
/// registrador só. **Achado da C12.3**: a versão original desta task usava
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp#RBIT} (`DataProcessing1Source`) para
/// isso — a C12.3 passou a suportar esse `Kind` nativamente, o que quebrou a premissa (ver o
/// commentário original, que já previa isso); trocado por `Fp64ConditionalSelect`.
class Asm64CodeEmitterPerOpTest {
    private final BlockEquivalenceHarness64 harness = new BlockEquivalenceHarness64();
    private final InterpretedIr64CodeEmitter interpreted = new InterpretedIr64CodeEmitter();
    private final Asm64CodeEmitter asmPerOp = new Asm64CodeEmitter(Asm64FallbackPolicy.PER_OP);

    private static EquivalencePairFactory64 pair() {
        return () -> new EquivalencePair64(newCore(), newCore());
    }

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
    }

    /// Empacota `ops` num bloco com `Cycle`/`Fetch` incondicionais antes de cada op real — mesma
    /// disciplina do lifter real (G4), espelha `BlockEquivalenceHarness64Test#blockOf`.
    private static Ir64Block blockOf(long startPc, Ir64Op... ops) {
        Ir64Block.Builder builder = Ir64Block.builder(startPc);
        long pc = startPc;
        for (Ir64Op op : ops) {
            builder.add(new Ir64Op.Fetch(pc, 4));
            builder.add(new Ir64Op.Cycle(1));
            builder.add(op);
            pc += 4;
        }
        builder.endPc(pc);
        return builder.sealed();
    }

    @Test
    void fp64ConditionalSelectIsNotNativelySupportedToday() {
        // Ancora a premissa do arquivo: se C12.4 passar a suportar FP64_CONDITIONAL_SELECT, estes
        // testes deixam de exercitar o caminho PER_OP e precisam trocar de op — falha aqui é
        // sinal de que isso aconteceu.
        assertFalse(Ir64NativePolicy.supports(
                new Ir64Op.Fp64ConditionalSelect(true, 1, 0, 0, Ir64Condition.EQ)));
    }

    /// Op não suportada NO MEIO do bloco — prova que as ops nativas depois dela continuam corretas.
    @Test
    void unsupportedOpInTheMiddleThenNativeOpsContinueCorrectly() {
        Ir64Block block = blockOf(0x1000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1234, 0, true),          // nativo: X0=0x1234
                new Ir64Op.Fp64ConditionalSelect(true, 1, 0, 0, Ir64Condition.EQ),     // interpretado: FCSEL
                new Ir64Op.Alu64(Ir64AluOp.ADD, 2, 1, 1, true, true, false, false));   // nativo: X2=X1+1, flags
        harness.assertEquivalent(interpreted, asmPerOp, block, pair());
    }

    /// Op não suportada na ÚLTIMA posição do bloco.
    @Test
    void unsupportedOpAtLastPosition() {
        Ir64Block block = blockOf(0x2000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 3, 0xABCD, 0, true),
                new Ir64Op.Fp64ConditionalSelect(false, 4, 0, 0, Ir64Condition.NE));
        harness.assertEquivalent(interpreted, asmPerOp, block, pair());
    }

    /// Op interpretada MUDA O PC — prova o contrato de `pcChanged` (`ERET`, `EXCEPTION_RETURN`, não
    /// suportada nativamente): se a ponte devolvesse sempre `false`, o PC final ficaria em
    /// `nextPc` sequencial em vez do `ELR_EL1` que o `ERET` de verdade grava.
    @Test
    void unsupportedOpThatChangesPc() {
        Ir64Block block = blockOf(0x3000, new Ir64Op.ExceptionReturn());
        harness.assertEquivalent(interpreted, asmPerOp, block, () -> {
            Aarch64Core reference = newCore();
            Aarch64Core candidate = newCore();
            for (Aarch64Core core : new Aarch64Core[]{reference, candidate}) {
                core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
                core.exceptionState().setElr1(0x4444L);
                core.exceptionState().setSpsr1(0L); // M[3:0]=0 -> volta para EL0t
            }
            return new EquivalencePair64(reference, candidate);
        });
    }

    /// `Cycle` acompanhando uma op interpretada — contagens DIFERENTES antes/depois provam que não
    /// é coincidência de "1 == 1" (`blockOf` sempre usa `Cycle(1)`); a soma tem que bater mesmo
    /// assim, porque a ponte PER_OP não toca `LOCAL_CYCLES` (quem soma é o `Cycle` nativo vizinho,
    /// nunca a chamada interpretada em si).
    @Test
    void cycleAccompanyingInterpretedOpIsCountedCorrectly() {
        Ir64Block.Builder builder = Ir64Block.builder(0x4000);
        builder.add(new Ir64Op.Fetch(0x4000, 4));
        builder.add(new Ir64Op.Cycle(3));
        builder.add(new Ir64Op.Fp64ConditionalSelect(true, 0, 0, 0, Ir64Condition.EQ));
        builder.add(new Ir64Op.Fetch(0x4004, 4));
        builder.add(new Ir64Op.Cycle(5));
        builder.add(new Ir64Op.Alu64(Ir64AluOp.ADD, 1, 0, 1, true, false, false, false));
        builder.endPc(0x4008);
        Ir64Block block = builder.sealed();

        BlockEquivalenceHarness64.ExecutionResult reference =
                harness.run(interpreted, block, newCore());
        assertEquals(8, reference.internalCycles(), "3 + 5, mesma soma que Ir64BlockExecutor#executeBlock faria");
        harness.assertEquivalent(interpreted, asmPerOp, block, pair());
    }

    /// Uma op interpretada que lança {@code MemoryTranslationException64} é capturada pelo handler
    /// do bloco compilado — mesmo `LOCAL_FAULT_PC`/ciclos parciais que uma op nativa já tem
    /// (`Ir64BlockCompilerMemoryAbortTest`). `FpLoad64` (SIMD&FP load/store, B8.13) não é suportada
    /// nativamente hoje (C12.5 fecha esse gap) e toca memória via `core.memory().read64`.
    @Test
    void interpretedOpMemoryTranslationFaultEntersGuestHandler() {
        assertFalse(Ir64NativePolicy.supports(
                new Ir64Op.FpLoad64(0, 0, dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.DOUBLE,
                        dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0)));

        long faultingVa = 0x5000L;
        Ir64Block block = blockOf(0x1000,
                new Ir64Op.FpLoad64(0, 0, dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.DOUBLE,
                        dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0));
        harness.assertEquivalent(interpreted, asmPerOp, block, () -> {
            Aarch64Core reference = newFaultingCore();
            reference.setX(0, faultingVa);
            Aarch64Core candidate = newFaultingCore();
            candidate.setX(0, faultingVa);
            return new EquivalencePair64(reference, candidate);
        });
    }

    /// Uma op interpretada que lança a exceção de `HVC` (`PrivilegedCall`, não suportada
    /// nativamente — a escada de sistema do C12.10) é capturada pelo mesmo handler que uma futura
    /// op nativa de sistema teria.
    @Test
    void interpretedOpHypervisorCallEntersEl2() {
        assertFalse(Ir64NativePolicy.supports(new Ir64Op.PrivilegedCall(true)));

        Ir64Block block = blockOf(0x6000, new Ir64Op.PrivilegedCall(true));
        harness.assertEquivalent(interpreted, asmPerOp, block, pair());
    }

    /// Core sobre uma {@link TranslatingAddressSpace64} sem NENHUM descritor de página válido — a
    /// tabela L0 aponta para uma página física zerada, então QUALQUER acesso de dados falta
    /// (`DESC_VALID` no bit 0, zero = inválido). Não precisa da caminhada L0->L1->L2->L3 completa
    /// de {@code Ir64BlockCompilerMemoryAbortTest} porque a falta já acontece no nível 0.
    private static Aarch64Core newFaultingCore() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x2000));
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(0x1000L);
        return new Aarch64Core(mmu);
    }

    // ---- contadores + políticas (Aceite: "Contadores") ----

    @Test
    void perOpCountersTrackMixedBlockCorrectly() {
        Asm64CodeEmitter emitter = new Asm64CodeEmitter(Asm64FallbackPolicy.PER_OP);
        Ir64Block block = blockOf(0x7000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true),
                new Ir64Op.Fp64ConditionalSelect(true, 1, 0, 0, Ir64Condition.EQ),
                new Ir64Op.Fp64ConditionalSelect(false, 2, 0, 0, Ir64Condition.NE));
        emitter.emit(block).execute(newCore());

        assertEquals(1, emitter.nativeBlockCount());
        assertEquals(0, emitter.fallbackBlockCount());
        assertEquals(2, emitter.perOpFallbackOpCount(), "as 2 Fp64ConditionalSelect não suportadas");

        emitter.resetCounters();
        assertEquals(0, emitter.nativeBlockCount());
        assertEquals(0, emitter.fallbackBlockCount());
        assertEquals(0, emitter.perOpFallbackOpCount());
    }

    @Test
    void wholeBlockCountersTrackTheSameMixedBlockAsFullFallback() {
        Asm64CodeEmitter emitter = new Asm64CodeEmitter(); // WHOLE_BLOCK, default (G3)
        assertEquals(Asm64FallbackPolicy.WHOLE_BLOCK, emitter.policy());
        Ir64Block block = blockOf(0x7100,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true),
                new Ir64Op.Fp64ConditionalSelect(true, 1, 0, 0, Ir64Condition.EQ));
        emitter.emit(block).execute(newCore());

        assertEquals(0, emitter.nativeBlockCount());
        assertEquals(1, emitter.fallbackBlockCount());
        assertEquals(0, emitter.perOpFallbackOpCount());
    }

    /// Espelho do precedente 32-bit (`AsmCodeEmitter` default): construtor sem argumento continua
    /// `WHOLE_BLOCK` (G3) — um bloco totalmente nativo compila igual nas duas políticas.
    @Test
    void fullyNativeBlockCompilesIdenticallyUnderBothPolicies() {
        Ir64Block block = blockOf(0x7200,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 5, true, false, false, false));
        Asm64CodeEmitter wholeBlock = new Asm64CodeEmitter();
        Asm64CodeEmitter perOp = new Asm64CodeEmitter(Asm64FallbackPolicy.PER_OP);
        harness.assertEquivalent(wholeBlock, perOp, block, pair());
        assertTrue(wholeBlock.isNativeSupported(block));
    }
}
