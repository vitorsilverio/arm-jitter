package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `CLIDR_EL1`/`CCSIDR_EL1`/`CSSELR_EL1`/`AIDR_EL1` — achado real da F11: o `cacheinfo` do
/// Linux/arm64 (`kernel8.img` do Raspberry Pi 3) lê `CLIDR_EL1` logo depois do `percpu`. Encodings
/// conferidos com `aarch64-none-elf-as`/`objdump`; valores do Cortex-A53 conferidos contra
/// `target/arm/tcg/cpu64.c` do QEMU (`CLIDR=0x0a200023`, `make_ccsidr(legacy, 4, 64, 32KiB, 7)` etc.).
class Aarch64CacheSizeIdRegistersTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(8)));
    }

    private static Ir64Op decode(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static void assertDecodes(int word, boolean read, Aarch64SystemRegisterId register, int rt) {
        SystemOp64.SystemRegister op = (SystemOp64.SystemRegister) decode(word);
        assertEquals(read, op.read());
        assertEquals(register, op.register());
        assertEquals(rt, op.rt());
    }

    private static long read(Aarch64Core core, Aarch64SystemRegisterId register) {
        EXECUTOR.executeOp(core, new SystemOp64.SystemRegister(true, register, 7));
        return core.x(7);
    }

    private static void select(Aarch64Core core, long csselr) {
        core.setX(6, csselr);
        EXECUTOR.executeOp(core, new SystemOp64.SystemRegister(false, Aarch64SystemRegisterId.CSSELR_EL1, 6));
    }

    @Test
    void decodesTheFourRegisters() {
        assertDecodes(0xd5390023, true, Aarch64SystemRegisterId.CLIDR_EL1, 3);   // mrs x3, clidr_el1
        assertDecodes(0xd5390003, true, Aarch64SystemRegisterId.CCSIDR_EL1, 3);  // mrs x3, ccsidr_el1
        assertDecodes(0xd53900e3, true, Aarch64SystemRegisterId.AIDR_EL1, 3);    // mrs x3, aidr_el1
        assertDecodes(0xd53a0003, true, Aarch64SystemRegisterId.CSSELR_EL1, 3);  // mrs x3, csselr_el1
        assertDecodes(0xd51a0004, false, Aarch64SystemRegisterId.CSSELR_EL1, 4); // msr csselr_el1, x4
    }

    @Test
    void sameCrnCrmWithOp1ZeroStillDecodesMidr() {
        assertDecodes(0xd5380003, true, Aarch64SystemRegisterId.MIDR_EL1, 3); // mrs x3, midr_el1
    }

    @Test
    void neighbouringEncodingsAreNotCacheSizeRegisters() {
        // op1=1 com op2=2 (d5390043), op1=2 com op2=1 (d53a0023), op1=1 com CRm=1 (d5390123)
        for (int word : new int[] {0xd5390043, 0xd53a0023, 0xd5390123}) {
            assertThrows(UnsupportedOperationException.class, () -> decode(word));
        }
    }

    @Test
    void coreResolvesThemIntrinsically() {
        Aarch64Core core = newCore();
        for (Aarch64SystemRegisterId id : new Aarch64SystemRegisterId[] {Aarch64SystemRegisterId.CLIDR_EL1,
                Aarch64SystemRegisterId.CCSIDR_EL1, Aarch64SystemRegisterId.CSSELR_EL1,
                Aarch64SystemRegisterId.AIDR_EL1}) {
            assertTrue(core.handlesSystemRegisterIntrinsically(id), id.name());
        }
    }

    @Test
    void clidrAndAidrAreCortexA53Constants() {
        Aarch64Core core = newCore();
        assertEquals(0x0a20_0023L, read(core, Aarch64SystemRegisterId.CLIDR_EL1));
        assertEquals(0L, read(core, Aarch64SystemRegisterId.AIDR_EL1));
    }

    @Test
    void ccsidrFollowsTheSelectedCache() {
        Aarch64Core core = newCore();
        select(core, 0); // L1 dados
        assertEquals(0x700F_E01AL, read(core, Aarch64SystemRegisterId.CCSIDR_EL1));
        select(core, 1); // L1 instruções
        assertEquals(0x200F_E01AL, read(core, Aarch64SystemRegisterId.CCSIDR_EL1));
        select(core, 2); // L2 unificado
        assertEquals(0x703F_E07AL, read(core, Aarch64SystemRegisterId.CCSIDR_EL1));
        select(core, 4); // nível 3 não existe
        assertEquals(0L, read(core, Aarch64SystemRegisterId.CCSIDR_EL1));
    }

    @Test
    void csselrRoundTrips() {
        Aarch64Core core = newCore();
        select(core, 2);
        assertEquals(2L, read(core, Aarch64SystemRegisterId.CSSELR_EL1));
    }

    @Test
    void reservedIdSpaceReadsAsZero() {
        // d5380662: mrs x2, id_aa64isar3_el1 (CRm=6, op2=3); d5380263: mrs x3, S3_0_C0_C2_3 (ID_ISAR3)
        assertDecodes(0xd5380662, true, Aarch64SystemRegisterId.ID_RESERVED_RAZ, 2);
        assertDecodes(0xd5380263, true, Aarch64SystemRegisterId.ID_RESERVED_RAZ, 3);
        Aarch64Core core = newCore();
        assertTrue(core.handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId.ID_RESERVED_RAZ));
        core.setX(7, 0xFFFF);
        assertEquals(0L, read(core, Aarch64SystemRegisterId.ID_RESERVED_RAZ));
    }

    @Test
    void idSpaceBoundariesStayUnsupported() {
        // CRn=0 mas CRm=8 (d5380803) e CRn=1 (d5381663 → fora do espaço de ID) não são RAZ.
        assertThrows(UnsupportedOperationException.class, () -> decode(0xd5380803));
        // CRm=0 com op2 sem registrador (d5380023: S3_0_C0_C0_1) fica fora do RAZ (CRm começa em 1).
        assertThrows(UnsupportedOperationException.class, () -> decode(0xd5380023));
    }
}
