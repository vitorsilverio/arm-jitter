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

/// LDREX/STREX e o monitor de exclusividade da task B1.4: sucesso e falha do STREX, limpeza
/// por CLREX/exceção, tamanhos incompatíveis, LDREXD/STREXD com par de registradores, e o
/// gating UNDEFINED em ARMv4T/v5TE (G2) e nos presets ARMv6 sem EXCLUSIVE_SIZED.
class ArmV6ExclusiveAccessTest {
    /// Codifica `LDREX{,B,H,D} rd, [rn]` (cond AL). `sz`: 00=word, 01=doubleword, 10=byte, 11=halfword.
    /// Base `0xE190_0F9F`: bits 27:20 = `0001 1001` (L=1 no bit 20; sz nas bits 22:21).
    private static int ldrex(int sz, int rd, int rn) {
        return 0xE190_0F9F | (sz << 21) | (rn << 16) | (rd << 12);
    }

    /// Codifica `STREX{,B,H,D} rd, rm, [rn]` (cond AL).
    /// Base `0xE180_0F90`: bits 27:20 = `0001 1000` (L=0).
    private static int strex(int sz, int rd, int rn, int rm) {
        return 0xE180_0F90 | (sz << 21) | (rn << 16) | (rd << 12) | rm;
    }

    private static final int CLREX = 0xF57F_F01F;

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    /// Escreve as instruções a partir do PC ATUAL do core (não de um endereço fixo): chamadas
    /// sucessivas de `run` — inclusive após uma exceção mover o PC para o vetor — continuam a
    /// escrever onde a execução realmente está.
    private static ArmCore run(ArmCore core, int... instructions) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        for (int i = 0; i < instructions.length; i++) {
            memory.put32(base + i * 4, instructions[i]);
        }
        for (int i = 0; i < instructions.length; i++) {
            core.step();
        }
        return core;
    }

    // ── Sucesso / falha básicos ──────────────────────────────────────────────────

    @Test
    void ldrexThenStrexToSameAddressSucceeds() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10); // base
        core.setRegister(2, 0xCAFEBABE); // valor a armazenar
        core.memory().write32(0x10, 0x11111111);
        run(core, ldrex(0, 1, 0), strex(0, 3, 0, 2));

        assertEquals(0, core.register(3), "Rd de status deve ser 0 (sucesso)");
        assertEquals(0xCAFEBABE, core.memory().read32(0x10), "memoria deve ter sido escrita");
    }

    @Test
    void strexWithoutPriorLdrexFailsAndLeavesMemoryIntact() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xDEADBEEF);
        core.memory().write32(0x10, 0x11111111);
        run(core, strex(0, 3, 0, 2));

        assertEquals(1, core.register(3), "Rd de status deve ser 1 (falha)");
        assertEquals(0x11111111, core.memory().read32(0x10), "memoria NAO pode ter sido tocada");
    }

    @Test
    void ldrexLoadsTheCurrentMemoryValue() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 0x12345678);
        run(core, ldrex(0, 1, 0));

        assertEquals(0x12345678, core.register(1));
    }

    // ── Limpeza do monitor ───────────────────────────────────────────────────────

    @Test
    void exceptionEntryClearsTheMonitorSoStrexAfterReturnFails() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        run(core, ldrex(0, 1, 0));
        core.requestException(ArmException.SWI); // simula LDREX -> exceção -> retorno
        core.setRegister(3, 0xFFFFFFFF);
        run(core, strex(0, 3, 0, 2));

        assertEquals(1, core.register(3), "STREX apos excecao deve falhar");
        assertEquals(0x11111111, core.memory().read32(0x10));
    }

    @Test
    void clrexOpensTheMonitorSoStrexFails() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        run(core, ldrex(0, 1, 0), CLREX, strex(0, 3, 0, 2));

        assertEquals(1, core.register(3));
        assertEquals(0x11111111, core.memory().read32(0x10));
    }

    // ── Tamanho incompatível ─────────────────────────────────────────────────────

    @Test
    void ldrexWordThenStrexByteAtSameAddressFails() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xFF);
        core.memory().write32(0x10, 0x11111111);
        run(core, ldrex(0, 1, 0), strex(0b10, 3, 0, 2));

        assertEquals(1, core.register(3), "tamanhos diferentes devem falhar");
        assertEquals(0x11111111, core.memory().read32(0x10));
    }

    // ── LDREXD / STREXD (par de registradores) ──────────────────────────────────

    @Test
    void ldrexdLoadsThePairAndStrexdStoresBoth() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 0x11111111);
        core.memory().write32(0x14, 0x22222222);
        run(core, ldrex(0b01, 2, 0)); // LDREXD r2:r3, [r0]

        assertEquals(0x11111111, core.register(2), "RdLo");
        assertEquals(0x22222222, core.register(3), "RdHi");

        core.setRegister(4, 0xAAAAAAAA);
        core.setRegister(5, 0xBBBBBBBB);
        run(core, strex(0b01, 6, 0, 4)); // STREXD r6, r4:r5, [r0]

        assertEquals(0, core.register(6), "sucesso");
        assertEquals(0xAAAAAAAA, core.memory().read32(0x10));
        assertEquals(0xBBBBBBBB, core.memory().read32(0x14));
    }

    // ── Loop funcional de incremento atômico ────────────────────────────────────

    @Test
    void atomicIncrementLoopConverges() {
        // LDREX r1,[r0]; ADD r1,r1,#1; STREX r2,r1,[r0]; sem contenção, converge em 1 iteração.
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 41);
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        memory.put32(0, ldrex(0, 1, 0));
        memory.put32(4, 0xE281_1001); // ADD r1, r1, #1
        memory.put32(8, strex(0, 2, 0, 1));
        core.step();
        core.step();
        core.step();

        assertEquals(0, core.register(2), "STREX deve suceder sem contencao");
        assertEquals(42, core.memory().read32(0x10));
    }

    // ── Condição falsa não toca o monitor nem a memória ─────────────────────────

    @Test
    void falseConditionSkipsLdrexAndDoesNotMarkTheMonitor() {
        ArmCore core = newCore();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 0x11111111);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> NE falha
        int ldrexNe = (ldrex(0, 1, 0) & 0x0FFF_FFFF) | 0x1000_0000;
        run(core, ldrexNe);

        core.setRegister(2, 0xCAFEBABE);
        run(core, strex(0, 3, 0, 2));
        assertEquals(1, core.register(3), "sem LDREX efetivo, o STREX deve falhar");
    }

    // ── Gating de arquitetura (G2) ───────────────────────────────────────────────

    @Test
    void exclusiveEncodingsStayUndefinedOnArmv4tAndArmv5te() {
        int[] encodings = {
                ldrex(0, 2, 0), ldrex(0b10, 2, 0), ldrex(0b11, 2, 0), ldrex(0b01, 2, 0),
                strex(0, 1, 0, 2), strex(0b10, 1, 0, 2), strex(0b11, 1, 0, 2), strex(0b01, 1, 0, 2),
                CLREX,
        };
        for (int encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind(),
                    () -> "ARMv4T deve manter UNDEFINED: 0x" + Integer.toHexString(encoding));
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind(),
                    () -> "ARMv5TE deve manter UNDEFINED: 0x" + Integer.toHexString(encoding));
        }
    }

    @Test
    void wordFormsDecodeOnArmv6kButSizedVariantsRequireExclusiveSized() {
        // Um preset ARMv6 só com EXCLUSIVE_WORD (sem EXCLUSIVE_SIZED) deve decodificar
        // LDREX/STREX (word) mas manter B/H/D/CLREX indefinidos.
        ArmArchitecture armv6WordOnly = ArmArchitecture.extending(
                ArmArchitecture.ARMV5TE, "ARMv6-word-only", dev.vitorsilverio.armjitter.arch.ArmFeature.EXCLUSIVE_WORD);
        ArmDecoder decoder = new ArmDecoder(armv6WordOnly);

        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, ldrex(0, 2, 0));
        assertEquals(InstructionKind.LOAD_EXCLUSIVE, decoder.decode(memory, 0).kind());

        int[] sizedOnly = {ldrex(0b10, 2, 0), ldrex(0b11, 2, 0), ldrex(0b01, 2, 0), CLREX};
        for (int encoding : sizedOnly) {
            TestAddressSpace mem = new TestAddressSpace(8);
            mem.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(mem, 0).kind(),
                    () -> "forma sized deve exigir EXCLUSIVE_SIZED: 0x" + Integer.toHexString(encoding));
        }
    }

    @Test
    void exclusiveEncodingsDecodeOnArmv6k() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, ldrex(0, 2, 0));
        memory.put32(4, ldrex(0b10, 2, 0));
        memory.put32(8, strex(0, 1, 0, 2));
        memory.put32(12, strex(0b01, 1, 0, 2));
        memory.put32(16, CLREX);
        assertEquals(InstructionKind.LOAD_EXCLUSIVE, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.LOAD_EXCLUSIVE, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.STORE_EXCLUSIVE, decoder.decode(memory, 8).kind());
        assertEquals(InstructionKind.STORE_EXCLUSIVE, decoder.decode(memory, 12).kind());
        assertEquals(InstructionKind.CLEAR_EXCLUSIVE, decoder.decode(memory, 16).kind());
    }

    // ── Restrições UNPREDICTABLE (registrador) ──────────────────────────────────

    @Test
    void unpredictableRegisterCombinationsDecodeAsUndefined() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV6K);
        int[] encodings = {
                ldrex(0, 15, 0),      // Rd=PC
                ldrex(0, 2, 15),      // Rn=PC
                strex(0, 1, 0, 15),   // Rm=PC
                strex(0, 15, 0, 2),   // Rd=PC
                strex(0, 0, 0, 2),    // Rd == Rn
                strex(0, 2, 0, 2),    // Rd == Rm
                ldrex(0b01, 3, 0),    // LDREXD com Rd impar
        };
        for (int encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(memory, 0).kind(),
                    () -> "combinacao UNPREDICTABLE deve virar UNDEFINED: 0x" + Integer.toHexString(encoding));
        }
    }
}
