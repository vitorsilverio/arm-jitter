package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B19.14 — semântica de `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G`/`STGM`/`LDGM`/`STZGM`/`STGP`/`SUBP`/
/// `SUBPS`/`IRG`/`GMI`/`SETGP`/`SETGM`/`SETGE` (interpretador = oráculo, G1). Decisão de escopo
/// registrada na task: armazenamento de tags FUNCIONAL (via {@link Aarch64Core#memoryTag}), SEM
/// checagem de tag em `LDR`/`STR` comuns (G8).
class Ir64MemoryTagExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(1024)));
    }

    @Test
    void stgAndLdgRoundtripTheSameTag() {
        Aarch64Core core = newCore();
        core.setX(1, 256L); // rn (endereço base)
        core.setX(0, Aarch64Core.withAllocationTag(256L, 0xB)); // rt: ponteiro com tag lógica 0xB
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, false, 1, 0, 1, Ir64AddressingMode.OFFSET, 0L));

        core.setX(2, 256L); // rn
        core.setX(3, 0L); // rt: ponteiro sem tag nenhuma, a ser preenchido pela leitura
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.LOAD, false, 1, 3, 2, Ir64AddressingMode.OFFSET, 0L));

        assertEquals(0xB, Aarch64Core.allocationTagFromAddress(core.x(3)));
    }

    @Test
    void stzgZeroesDataAndGravesTagTogether() {
        Aarch64Core core = newCore();
        long address = 320L;
        for (int i = 0; i < 16; i++) {
            core.memory().write8(address + i, 0xFF);
        }
        core.setX(1, address);
        core.setX(0, Aarch64Core.withAllocationTag(address, 0x7));
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, true, 1, 0, 1, Ir64AddressingMode.OFFSET, 0L));

        for (int i = 0; i < 16; i++) {
            assertEquals(0, core.memory().read8(address + i));
        }
        assertEquals(0x7, core.memoryTag(address));
    }

    @Test
    void st2gAndStz2gAffectBothGranules() {
        Aarch64Core core = newCore();
        long address = 400L;
        core.setX(1, address);
        core.setX(0, Aarch64Core.withAllocationTag(address, 0x3));
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, false, 2, 0, 1, Ir64AddressingMode.OFFSET, 0L));

        assertEquals(0x3, core.memoryTag(address));
        assertEquals(0x3, core.memoryTag(address + 16));
        assertEquals(0, core.memoryTag(address + 32)); // fora do alcance, não tocado

        long zeroAddress = 500L;
        for (int i = 0; i < 32; i++) {
            core.memory().write8(zeroAddress + i, 0xAA);
        }
        core.setX(3, zeroAddress);
        core.setX(2, Aarch64Core.withAllocationTag(zeroAddress, 0x9));
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, true, 2, 2, 3, Ir64AddressingMode.OFFSET, 0L));
        for (int i = 0; i < 32; i++) {
            assertEquals(0, core.memory().read8(zeroAddress + i));
        }
        assertEquals(0x9, core.memoryTag(zeroAddress));
        assertEquals(0x9, core.memoryTag(zeroAddress + 16));
    }

    @Test
    void memoryTagHonorsWritebackAddressingModes() {
        Aarch64Core core = newCore();
        core.setX(1, 100L);
        core.setX(0, 100L);
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, false, 1, 0, 1, Ir64AddressingMode.PRE_INDEX, 32L));
        assertEquals(0x0, core.memoryTag(132L));
        assertEquals(132L, core.x(1)); // pre-index escreve Rn+imm de volta

        core.setX(2, 200L);
        core.setX(3, 200L);
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTag(
                Ir64Op.Ir64MemoryTagOperation.STORE, false, 1, 3, 2, Ir64AddressingMode.POST_INDEX, -16L));
        assertEquals(184L, core.x(2)); // post-index: acesso em Rn, writeback Rn+imm
    }

    @Test
    void stgmAndLdgmRoundtripAWholeBlockOfDistinctTags() {
        Aarch64Core core = newCore();
        long blockBase = 512L; // alinhado a 256 bytes
        // Bitmap não trivial: granule i tem tag i (i=0..15).
        long bitmap = 0L;
        for (int i = 0; i < 16; i++) {
            bitmap |= (long) i << (i * 4);
        }
        core.setX(0, bitmap);
        core.setX(1, blockBase);
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTagMultiple(
                Ir64Op.Ir64MemoryTagMultipleOperation.STORE_TAGS, 0, 1));

        for (int i = 0; i < 16; i++) {
            assertEquals(i, core.memoryTag(blockBase + i * 16L));
        }

        core.setX(3, blockBase);
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTagMultiple(
                Ir64Op.Ir64MemoryTagMultipleOperation.LOAD_TAGS, 2, 3));
        assertEquals(bitmap, core.x(2));
    }

    @Test
    void stzgmZeroesDataBlockAndSetsUniformTag() {
        Aarch64Core core = newCore();
        long blockBase = 768L; // alinhado a 64 bytes
        for (int i = 0; i < 64; i++) {
            core.memory().write8(blockBase + i, 0x55);
        }
        core.setX(0, 0xDL); // tag nos 4 bits baixos, direto (não bits[59:56])
        core.setX(1, blockBase);
        EXECUTOR.executeOp(core, new Ir64Op.MemoryTagMultiple(
                Ir64Op.Ir64MemoryTagMultipleOperation.STORE_ZERO_DATA_TAGS, 0, 1));

        for (int i = 0; i < 64; i++) {
            assertEquals(0, core.memory().read8(blockBase + i));
        }
        for (int g = 0; g < 4; g++) {
            assertEquals(0xD, core.memoryTag(blockBase + g * 16L));
        }
    }

    @Test
    void stgpStoresBothRegistersAndTagsTheDestinationAddress() {
        Aarch64Core core = newCore();
        long address = 896L;
        core.setX(1, address);
        core.setX(0, 0x1111111111111111L);
        core.setX(2, 0x2222222222222222L);
        EXECUTOR.executeOp(core, new Ir64Op.StorePairTag(0, 2, 1, Ir64AddressingMode.OFFSET, 0L));

        assertEquals(0x1111111111111111L, core.memory().read64(address));
        assertEquals(0x2222222222222222L, core.memory().read64(address + 8));
        assertEquals(Aarch64Core.allocationTagFromAddress(address), core.memoryTag(address));
    }

    @Test
    void subpIgnoresTagBitsButSubpsAlsoSetsFlags() {
        Aarch64Core core = newCore();
        long base = 0x0000_1000_0000_1000L;
        core.setX(1, Aarch64Core.withAllocationTag(base, 0x3));
        core.setX(2, Aarch64Core.withAllocationTag(base, 0xC)); // MESMO endereço, tag DIFERENTE
        EXECUTOR.executeOp(core, new Ir64Op.SubtractPointer(false, 0, 1, 2));
        assertEquals(0L, core.x(0));

        core.setX(4, Aarch64Core.withAllocationTag(base + 40, 0x1));
        core.setX(5, Aarch64Core.withAllocationTag(base, 0xE));
        EXECUTOR.executeOp(core, new Ir64Op.SubtractPointer(true, 3, 4, 5));
        assertEquals(40L, core.x(3));
        assertEquals(false, core.pstate().negative());
        assertEquals(false, core.pstate().zero());
        assertEquals(true, core.pstate().carry());
        assertEquals(false, core.pstate().overflow());
    }

    @Test
    void gmiAccumulatesTagsAndIrgNeverGeneratesAnExcludedTag() {
        Aarch64Core core = newCore();
        long pointerWithTag5 = Aarch64Core.withAllocationTag(0x2000L, 5);
        core.setX(1, pointerWithTag5);
        core.setX(2, 0L); // máscara acumulada vazia
        EXECUTOR.executeOp(core, new Ir64Op.TagMaskInsert(0, 1, 2));
        assertEquals(1L << 5, core.x(0));

        // Exclui TODAS as tags menos a 5 -> IRG tem que gerar sempre 5, determinístico.
        long excludeAllButFive = (~(1L << 5)) & 0xFFFFL;
        core.setX(4, 0x3000L);
        core.setX(5, excludeAllButFive);
        for (int i = 0; i < 5; i++) {
            EXECUTOR.executeOp(core, new Ir64Op.InsertRandomTag(3, 4, 5));
            assertEquals(5, Aarch64Core.allocationTagFromAddress(core.x(3)));
        }
    }

    @Test
    void setgpSetgmSetgeSequenceFillsRegionAndTagsEachGranule() {
        Aarch64Core core = newCore();
        long address = 640L; // alinhado a 16
        core.setX(1, 48L); // rn: 3 granules
        core.setX(2, 0xABL); // rs: byte de preenchimento (a tag vem de Rd, não de Rs)
        core.setX(0, Aarch64Core.withAllocationTag(address, 0x6)); // rd: endereço + tag lógica
        for (Ir64Op.Ir64MopsPhase phase : Ir64Op.Ir64MopsPhase.values()) {
            EXECUTOR.executeOp(core, new Ir64Op.MemorySetTagged(phase, 0, 1, 2));
        }

        for (int i = 0; i < 48; i++) {
            assertEquals(0xAB, core.memory().read8(address + i) & 0xFF);
        }
        for (int g = 0; g < 3; g++) {
            assertEquals(0x6, core.memoryTag(address + g * 16L));
        }
        assertEquals(0L, core.x(1));
        assertNotEquals(address, core.x(0));
    }
}
