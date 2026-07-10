package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// As transferências de doubleword ARMv5TE LDRD/STRD (um par de registradores Rd, Rd+1 em duas words).
class LoadStoreDoubleTest {

    private static ArmCore core(TestAddressSpace memory) {
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
    }

    @Test
    void ldrdLoadsTheRegisterPair() {
        TestAddressSpace memory = new TestAddressSpace(0x80);
        memory.put32(0, 0xE1C4_00D0); // LDRD r0, [r4]
        memory.put32(0x40, 0x1111_1111);
        memory.put32(0x44, 0x2222_2222);
        ArmCore core = core(memory);
        core.setRegister(4, 0x40);
        core.step();
        assertEquals(0x1111_1111, core.register(0));
        assertEquals(0x2222_2222, core.register(1));
    }

    @Test
    void ldrdWithImmediateOffset() {
        TestAddressSpace memory = new TestAddressSpace(0x80);
        memory.put32(0, 0xE1C4_00D8); // LDRD r0, [r4, #8]
        memory.put32(0x48, 0x0BAD_F00D);
        memory.put32(0x4C, 0xFEED_BEEF);
        ArmCore core = core(memory);
        core.setRegister(4, 0x40);
        core.step();
        assertEquals(0x0BAD_F00D, core.register(0));
        assertEquals(0xFEED_BEEF, core.register(1));
    }

    @Test
    void strdStoresTheRegisterPair() {
        TestAddressSpace memory = new TestAddressSpace(0x80);
        memory.put32(0, 0xE1C4_00F0); // STRD r0, [r4]
        ArmCore core = core(memory);
        core.setRegister(0, 0xAAAA_AAAA);
        core.setRegister(1, 0xBBBB_BBBB);
        core.setRegister(4, 0x40);
        core.step();
        assertEquals(0xAAAA_AAAA, memory.read32(0x40));
        assertEquals(0xBBBB_BBBB, memory.read32(0x44));
    }

    @Test
    void doubleTransferDecodesOnlyOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE1C4_00D0); // LDRD r0, [r4]
        assertNotEquals(InstructionKind.DOUBLE_TRANSFER,
                new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        assertEquals(InstructionKind.DOUBLE_TRANSFER,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }
}
