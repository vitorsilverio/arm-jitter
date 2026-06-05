package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * t217: THUMB STRH rd, [rb, imm5<<1] — store halfword com offset imediato.
 *
 * Sequência:
 *   MOVS r0, #0         ; r0 = 0
 *   MVN  r0, r0         ; r0 = 0xFFFFFFFF
 *   LSR  r1, r0, #16    ; r1 = 0x0000FFFF
 *   STRH r0, [r4, #4]   ; mem[r4+4..r4+5] = 0xFFFF (16 bits baixos)
 *   LDR  r2, [r4, #4]   ; r2 = mem32[r4+4] = 0x0000FFFF (16 altos zerados)
 *   CMP  r2, r1         ; 0x0000FFFF == 0x0000FFFF → passa
 *
 * r4 = 0x20 (base de dados), instruções em 0x00-0x14, dados em 0x20+.
 */
class ThumbStrhImmediateTest {

    private static TestAddressSpace buildMemory() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0x00, 0x2000); // MOVS r0, #0
        memory.put16(0x02, 0x43C0); // MVN  r0, r0         → r0 = 0xFFFFFFFF
        memory.put16(0x04, 0x0C01); // LSR  r1, r0, #16    → r1 = 0x0000FFFF
        memory.put16(0x06, 0x80A0); // STRH r0, [r4, #4]   → mem[0x24..0x25] = 0xFFFF
        memory.put16(0x08, 0x6862); // LDR  r2, [r4, #4]   → r2 = mem32[0x24]
        memory.put16(0x0A, 0x428A); // CMP  r2, r1
        memory.put16(0x0C, 0xD101); // BNE  f217           → 0x12
        memory.put16(0x0E, 0x2501); // MOVS r5, #1         ; passou
        memory.put16(0x10, 0xE7FE); // B .
        memory.put16(0x12, 0x2500); // MOVS r5, #0         ; f217
        memory.put16(0x14, 0xE7FE); // B .
        // Área de dados 0x20-0x3F já zerada
        return memory;
    }

    private static ArmCore thumbCore(TestAddressSpace memory) {
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(4, 0x20); // r4 = base de dados
        return core;
    }

    @Test
    void strhImmediateDiagnostic() {
        ArmCore core = thumbCore(buildMemory());
        TestAddressSpace mem = (TestAddressSpace) core.memory();

        core.step(1); // MOVS r0, #0
        assertEquals(0, core.register(0));

        core.step(1); // MVN r0, r0
        assertEquals(-1, core.register(0));

        core.step(1); // LSR r1, r0, #16
        assertEquals(0xFFFF, core.register(1), "r1 deve ser 0x0000FFFF");

        core.step(1); // STRH r0, [r4, #4] → addr = 0x20+4 = 0x24
        assertEquals((short) 0xFFFF, (short) mem.read16(0x24),
                "mem16[0x24] deve ser 0xFFFF após STRH");
        assertEquals(0, mem.read16(0x26),
                "mem16[0x26] deve permanecer 0 (STRH não toca bytes 6-7)");

        core.step(1); // LDR r2, [r4, #4] → r2 = mem32[0x24]
        assertEquals(0x0000FFFF, core.register(2),
                String.format("r2 deve ser 0x0000FFFF. " +
                                "mem16[0x24]=0x%04X mem16[0x26]=0x%04X. " +
                                "Se r2=0xFFFFFFFF: STRH escreveu 4 bytes em vez de 2.",
                        mem.read16(0x24), mem.read16(0x26)));
    }

    @Test
    void t217StrhImmediate() {
        ArmCore core = thumbCore(buildMemory());

        for (int i = 0; i < 10; i++) {
            core.step(1);
            int pc = core.programCounter();
            if (pc == 0x10 || pc == 0x14) break;
        }

        assertEquals(1, core.register(5),
                "t217: r5 deve ser 1 (passou). Se 0: STRH ou LDR com offset errado.");
    }
}