package dev.vitorsilverio.armjitter.decoder64;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// `IC IALLUIS`/`IC IALLU`/`IC IVAU` deixam de ser NOP genérico e passam a ser decodificadas como
/// invalidação do cache de instruções (F11); o resto de `IC`/`DC` continua NOP. Encodings
/// conferidos com `aarch64-none-elf-as`/`objdump` (devkitA64).
class Aarch64InstructionCacheMaintenanceDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op.SystemInstruction decode(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return (Ir64Op.SystemInstruction) DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void icIalluisAndIalluInvalidateEverything() {
        assertEquals(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL, decode(0xd508711f).opcode()); // ic ialluis
        assertEquals(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL, decode(0xd508751f).opcode()); // ic iallu
        assertEquals(Ir64Op.SystemInstruction.NO_REGISTER, decode(0xd508751f).rt());
    }

    @Test
    void icIvauCarriesItsAddressRegister() {
        Ir64Op.SystemInstruction op = decode(0xd50b7527); // ic ivau, x7
        assertEquals(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_BY_VA, op.opcode());
        assertEquals(7, op.rt());
        assertEquals(31, decode(0xd50b753f).rt(), "ic ivau, xzr"); // rt=31
    }

    @Test
    void otherCacheMaintenanceStaysANop() {
        // dc civac, x0 (d50b7e20); dc cvau, x0 (d50b7b20); ic ivau com op2 trocado (d50b7547);
        // op1=0 com CRm=5 mas op2=1 (d508753f... usa op2=1); op1=3 CRm=1 op2=0; op1=0 CRm=2 op2=0
        for (int word : new int[] {0xd50b7e20, 0xd50b7b20, 0xd50b7547, 0xd508753f, 0xd50b7120, 0xd508721f}) {
            assertEquals(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP, decode(word).opcode(),
                    Integer.toHexString(word));
        }
    }
}
