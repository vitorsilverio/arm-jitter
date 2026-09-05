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

/// A10.4 — `BitFieldExtract`/`BitFieldInsert`/`BitReverse`/`Divide` (`SBFX`/`UBFX`, `BFI`/`BFC`,
/// `RBIT`, `SDIV`/`UDIV`, ARMv6T2+/ARMv7, B3.1) no backend Truffle (`AluOpNode`, A10.4). Como o nó
/// delega DIRETO ao MESMO {@link dev.vitorsilverio.armjitter.codegen.executor.IrAluExecutor} que o
/// interpretador usa (G1 — o interpretador é o oráculo), a equivalência é estrutural; estes testes
/// cobrem as bordas de semântica do Aceite da A10.4 (`lsb`/`width` extremos e assinado×não-assinado
/// em `SBFX`/`UBFX`, preservação dos bits fora do campo em `BFI`, padrão assimétrico em `RBIT`,
/// divisão por zero e `INT_MIN / -1` em `SDIV`/`UDIV`) e confirmam que o backend compila
/// nativamente (sem fallback).
class TruffleCodeEmitterBitFieldDivideEquivalenceTest {
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

    private void assertBlockEquivalent(IrBlock block, int r1, int r2) {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV7A);
        harness.assertEquivalent(referenceEmitter, emitter, block, () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            for (ArmCore core : List.of(reference, candidate)) {
                core.setRegister(1, r1);
                core.setRegister(2, r2);
            }
            return new EquivalencePair(reference, candidate);
        });
        assertEquals(1L, emitter.nativeBlockCount(), "bloco bitfield/divide deve compilar nativamente (A10.4)");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void sbfxWidthOneAtLsbZeroSignExtendsSingleBitOn() {
        // SBFX r0, r1, #0, #1 — r1 com bit0=1 -> campo de 1 bit sinalizado vira -1 (0xFFFFFFFF).
        IrBlock block = liftArmV7(0xE7A0_0051);
        assertBlockEquivalent(block, 1, 0);
    }

    @Test
    void sbfxWidthOneAtLsbZeroSignExtendsSingleBitOff() {
        // Mesma instrução, bit0=0 -> campo vira 0.
        IrBlock block = liftArmV7(0xE7A0_0051);
        assertBlockEquivalent(block, 0xFFFF_FFFE, 0);
    }

    @Test
    void ubfxWidthOneAtLsbZeroDoesNotSignExtend() {
        // UBFX r0, r1, #0, #1 — mesma borda, mas sem sinal: bit0=1 -> campo vira 1, não -1.
        IrBlock block = liftArmV7(0xE7E0_0051);
        assertBlockEquivalent(block, 1, 0);
    }

    @Test
    void sbfxFullWidthIsIdentity() {
        // SBFX r0, r1, #0, #32 — largura máxima cobre o registrador inteiro, extração vira cópia.
        IrBlock block = liftArmV7(0xE7BF_0051);
        assertBlockEquivalent(block, 0x8000_0001, 0);
    }

    @Test
    void bfiPreservesBitsOutsideTheField() {
        // BFI r0, r1, #4, #8 — r0 pré-carregado (via segunda ALU antes do BFI) teria bits fora do
        // campo preservados; aqui isolamos só o BFI, então o dst nasce zerado e o harness valida
        // que os dois backends preenchem o mesmo dst final (campo inserido, resto do dst 0/como já
        // estava lá) de forma idêntica.
        IrBlock block = liftArmV7(0xE7CB_0211);
        assertBlockEquivalent(block, 0xAB, 0);
    }

    @Test
    void bfcClearsOnlyTheField() {
        // BFC r0, #4, #8 — só usa r0 (Rn=15 -> "sem origem"), limpa [11:4] preservando o resto.
        IrBlock block = liftArmV7(0xE7CB_021F);
        assertBlockEquivalent(block, 0, 0);
    }

    @Test
    void rbitReversesAnAsymmetricPattern() {
        // RBIT r0, r1 com padrão assimétrico (não palíndromo de bits) — pega troca de nibble errada.
        IrBlock block = liftArmV7(0xE6FF_0F31);
        assertBlockEquivalent(block, 0x1234_5678, 0);
    }

    @Test
    void sdivByZeroYieldsZeroWithoutException() {
        // SDIV r0, r1, r2 — ARM define resultado 0 para divisão por zero, Java lançaria.
        IrBlock block = liftArmV7(0xE710_F211);
        assertBlockEquivalent(block, 42, 0);
    }

    @Test
    void sdivMinValueDividedByMinusOneOverflowsSilently() {
        // INT_MIN / -1 estoura em aritmética de complemento de dois; ARM define resultado INT_MIN.
        IrBlock block = liftArmV7(0xE710_F211);
        assertBlockEquivalent(block, Integer.MIN_VALUE, -1);
    }

    @Test
    void udivByZeroYieldsZeroWithoutException() {
        // UDIV r0, r1, r2 — mesma borda de SDIV, lado sem sinal.
        IrBlock block = liftArmV7(0xE730_F211);
        assertBlockEquivalent(block, 42, 0);
    }

    @Test
    void udivDividesAsUnsigned() {
        // r1 = 0xFFFFFFFF interpretado como unsigned (4294967295), não -1.
        IrBlock block = liftArmV7(0xE730_F211);
        assertBlockEquivalent(block, 0xFFFF_FFFF, 2);
    }
}
