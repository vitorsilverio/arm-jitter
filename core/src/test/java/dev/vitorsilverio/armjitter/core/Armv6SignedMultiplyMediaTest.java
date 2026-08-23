package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// `SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}`/`SMMLA{R}`/`SMMLS{R}` (B9.1, ARMv6) e `UDF`.
/// Encodings CONFERIDOS contra `arm-none-eabi-as`/`objdump` reais (devkitARM, `-mcpu=arm1176jzf-s`)
/// antes de escrever os testes — ver o comentário de cada caso.
class Armv6SignedMultiplyMediaTest {

    private static ArmCore core(int instruction) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, instruction);
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARM11_MPCORE);
    }

    // ── SMLAD/SMLADX/SMLSD/SMLSDX ── `smlad r0, r1, r2, r3` real: e7003211
    @Test
    void smladSumsBothProducts() {
        ArmCore c = core(0xE700_3211);
        c.setRegister(1, 0x0002_0003); // Rn: baixa=3, alta=2
        c.setRegister(2, 0x0004_0005); // Rm: baixa=5, alta=4
        c.setRegister(3, 100);          // Ra
        c.step();
        assertEquals(3 * 5 + 2 * 4 + 100, c.register(0));
        assertFalse(c.cpsr().saturation());
    }

    // `smladx r0, r1, r2, r3` real: e7003231 — troca as metades de Rm antes de multiplicar.
    @Test
    void smladxExchangesSecondOperandHalves() {
        ArmCore c = core(0xE700_3231);
        c.setRegister(1, 0x0002_0003);
        c.setRegister(2, 0x0004_0005);
        c.setRegister(3, 0);
        c.step();
        assertEquals(3 * 4 + 2 * 5, c.register(0));
    }

    // `smlsd r0, r1, r2, r3` real: e7003251 — produto1 - produto2.
    @Test
    void smlsdSubtractsSecondProduct() {
        ArmCore c = core(0xE700_3251);
        c.setRegister(1, 0x0002_0003);
        c.setRegister(2, 0x0004_0005);
        c.setRegister(3, 1);
        c.step();
        assertEquals((3 * 5) - (2 * 4) + 1, c.register(0));
    }

    @Test
    void smladSetsSaturationOnOverflow() {
        ArmCore c = core(0xE700_3211); // smlad r0, r1, r2, r3
        c.setRegister(1, 0x7FFF_7FFF); // ambas as metades = 0x7FFF (32767)
        c.setRegister(2, 0x7FFF_7FFF);
        c.setRegister(3, 0x7FFF_FFFF); // Ra grande o bastante para estourar 32 bits com sinal
        c.step();
        assertTrue(c.cpsr().saturation());
    }

    // `smlald r4, r5, r1, r2` real: e7454211 — RdHi=r5(bits19:16), RdLo=r4(bits15:12); acumula em
    // 64 bits, NUNCA seta Q mesmo quando o resultado de 32 bits sozinho estouraria.
    @Test
    void smlaldAccumulatesInto64Bits() {
        ArmCore c = core(0xE745_4211);
        c.setRegister(1, 0x0002_0003);
        c.setRegister(2, 0x0004_0005);
        c.setRegister(4, 0x7FFF_FFFF); // RdLo
        c.setRegister(5, 0);            // RdHi
        c.step();
        long acc = (0L << 32) | (0x7FFF_FFFFL & 0xFFFF_FFFFL);
        long expected = acc + (3L * 5) + (2L * 4);
        assertEquals((int) expected, c.register(4));
        assertEquals((int) (expected >>> 32), c.register(5));
        assertFalse(c.cpsr().saturation());
    }

    // `smlsld r4, r5, r1, r2` real: e7454251.
    @Test
    void smlsldSubtractsSecondProductInto64Bits() {
        ArmCore c = core(0xE745_4251);
        c.setRegister(1, 0x0002_0003);
        c.setRegister(2, 0x0004_0005);
        c.setRegister(4, 0);
        c.setRegister(5, 0);
        c.step();
        long expected = (3L * 5) - (2L * 4);
        assertEquals((int) expected, c.register(4));
        assertEquals((int) (expected >>> 32), c.register(5));
    }

    // `smuad r0, r1, r2` real: e700f211 — mesmo encoding de SMLAD com Ra=1111 (alias sem acumulador).
    @Test
    void smuadAliasHasNoAccumulator() {
        ArmCore c = core(0xE700_F211);
        c.setRegister(1, 0x0002_0003);
        c.setRegister(2, 0x0004_0005);
        c.step();
        assertEquals(3 * 5 + 2 * 4, c.register(0));
    }

    // ── SMMLA/SMMLAR/SMMLS/SMMLSR ── `smmla r0, r1, r2, r3` real: e7503211.
    // Rn*Rm = 0x0002_0000 * 0x0001_0000 = 0x2_0000_0000 (bit 33 setado, metade baixa ZERO —
    // escolhido de propósito para que o resultado não dependa de arredondamento/carry da metade
    // baixa, só da soma dos 32 bits altos): topo do produto = 2, mais Ra=10 → 12.
    @Test
    void smmlaKeepsTopWordOfProductPlusAccumulator() {
        ArmCore c = core(0xE750_3211);
        c.setRegister(1, 0x0002_0000); // Rn
        c.setRegister(2, 0x0001_0000); // Rm
        c.setRegister(3, 10);           // Ra
        c.step();
        assertEquals(12, c.register(0));
    }

    // `smmls r0, r1, r2, r3` real: e75032d1 — `Ra<<32 - Rn*Rm`; mesmos operandos: 10 - 2 = 8.
    @Test
    void smmlsSubtractsProductFromAccumulator() {
        ArmCore c = core(0xE750_32D1);
        c.setRegister(1, 0x0002_0000);
        c.setRegister(2, 0x0001_0000);
        c.setRegister(3, 10);
        c.step();
        assertEquals(8, c.register(0));
    }

    // `smmlar r0, r1, r2, r3` real: e7503231 — arredonda somando 0x8000_0000 antes de truncar.
    // Rn=0x8000_0000 (lido com SINAL: -2^31), Rm=1: produto = -2^31 = 0xFFFF_FFFF_8000_0000 (topo
    // = -1, metade baixa = 0x8000_0000, bit 31 setado). Sem arredondar o topo seria -1 (0xFFFFFFFF
    // truncado); com o carry do arredondamento vira 0.
    @Test
    void smmlarRoundsUpWhenLowHalfHasSignBitSet() {
        ArmCore c = core(0xE750_3231);
        c.setRegister(1, 0x8000_0000);
        c.setRegister(2, 1);
        c.setRegister(3, 0);
        c.step();
        assertEquals(0, c.register(0));
    }

    // `smmul r0, r1, r2` real: e750f211 — mesmo encoding de SMMLA com Ra=1111 (sem acumulador);
    // mesmos operandos de smmlaKeepsTopWordOfProductPlusAccumulator, sem o `+10` do Ra.
    @Test
    void smmulAliasHasNoAccumulator() {
        ArmCore c = core(0xE750_F211);
        c.setRegister(1, 0x0002_0000);
        c.setRegister(2, 0x0001_0000);
        c.step();
        assertEquals(2, c.register(0));
    }

    // ── Decode puro: confirma o Kind e a triagem de arquitetura (v4T/v5TE excluídos, B9.1). ──
    @Test
    void decoderRecognizesSmladOnMPCoreButNotOnArmv5Te() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE700_3211);
        assertEquals(InstructionKind.DSP_DUAL_MULTIPLY,
                new ArmDecoder(ArmArchitecture.ARM11_MPCORE).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }

    @Test
    void decoderRecognizesSmmlaOnMPCoreButNotOnArmv5Te() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE750_3211);
        assertEquals(InstructionKind.DSP_TOP_WORD_MULTIPLY,
                new ArmDecoder(ArmArchitecture.ARM11_MPCORE).decode(memory, 0).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }

    // ── UDF (ARM DDI 0406C A8.8.247) ── `udf #0`/`udf #305` reais: e7f000f0 / e7f013f1.
    // Sempre indefinida, em QUALQUER arquitetura (sem gate de feature) — mesmo comportamento
    // observável de UNIMPLEMENTED (vetor UNDEFINED), só reconhecida explicitamente (G8/B9.1).
    @Test
    void decoderRecognizesUdfExplicitly() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE7F0_00F0);
        assertEquals(InstructionKind.UDF, new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        memory.put32(0, 0xE7F0_13F1); // udf #305, imm16 não usado pela nossa emulação
        assertEquals(InstructionKind.UDF, new ArmDecoder(ArmArchitecture.ARM11_MPCORE).decode(memory, 0).kind());
    }

    @Test
    void udfEntersUndefinedVectorLikeUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE7F0_00F0);
        ArmCore c = new ArmCore(memory, SwiDispatcher.empty());
        c.setBankedRegister(CpuMode.UNDEFINED, 13, 0x5000);

        c.step();

        assertEquals(CpuMode.UNDEFINED, c.mode());
        assertEquals(0x04, c.programCounter());
        assertEquals(4, c.register(14));
        assertEquals(0x5000, c.register(13));
    }
}
