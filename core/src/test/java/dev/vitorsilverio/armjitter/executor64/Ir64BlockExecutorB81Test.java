package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Semântica dos ops novos da B8.1 (`LDXP`/`STXP`, `CAS`/`CASP`, `LDPSW`) direto no executor
/// (interpretador = oráculo, G1) — `LDAR`/`STLR` já são cobertos indiretamente por reaproveitarem
/// {@link Ir64Op.Load64}/{@link Ir64Op.Store64} (sem semântica nova). Complementa
/// {@code Aarch64DecoderCorpusTest} (decode) e {@code BlockEquivalenceHarness64Test} (ASM×interp,
/// só para os `Kind`s nativamente suportados).
class Ir64BlockExecutorB81Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void loadExclusivePairThenStoreExclusivePairSucceeds() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10L); // Rn
        core.memory().write64(0x10L, 0x1111_1111_1111_1111L);
        core.memory().write64(0x18L, 0x2222_2222_2222_2222L);

        EXECUTOR.executeOp(core, new Ir64Op.LoadExclusivePair(1, 2, 0, true, false));
        assertEquals(0x1111_1111_1111_1111L, core.x(1));
        assertEquals(0x2222_2222_2222_2222L, core.x(2));

        core.setX(3, 0x3333_3333_3333_3333L);
        core.setX(4, 0x4444_4444_4444_4444L);
        EXECUTOR.executeOp(core, new Ir64Op.StoreExclusivePair(5, 3, 4, 0, true, false));
        assertEquals(0L, core.x(5), "monitor cobria os 2 slots — status de sucesso");
        assertEquals(0x3333_3333_3333_3333L, core.memory().read64(0x10L));
        assertEquals(0x4444_4444_4444_4444L, core.memory().read64(0x18L));
    }

    @Test
    void storeExclusivePairWithoutReservationFails() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10L);
        core.memory().write64(0x10L, 0xAAAAL);
        core.memory().write64(0x18L, 0xBBBBL);

        EXECUTOR.executeOp(core, new Ir64Op.StoreExclusivePair(5, 3, 4, 0, true, false));

        assertEquals(1L, core.x(5), "sem LDXP/LDAXP antes — deve falhar sem tocar a memória");
        assertEquals(0xAAAAL, core.memory().read64(0x10L));
        assertEquals(0xBBBBL, core.memory().read64(0x18L));
    }

    @Test
    void storeExclusivePairPartialOverlapFails() {
        // Reserva só o PRIMEIRO slot (LoadExclusive simples, size=DOUBLEWORD) não cobre o par
        // inteiro (2×8 bytes) — StoreExclusivePair deve falhar mesmo com o endereço batendo.
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10L);
        EXECUTOR.executeOp(core, new Ir64Op.LoadExclusive(1, 0, Ir64MemSize.DOUBLEWORD, false));

        EXECUTOR.executeOp(core, new Ir64Op.StoreExclusivePair(5, 3, 4, 0, true, false));

        assertEquals(1L, core.x(5), "monitor de par exige cobertura dos 2 slots, não só 1");
    }

    @Test
    void compareAndSwapSucceedsWhenValuesMatch() {
        Aarch64Core core = newCore(16);
        core.setX(0, 0x8L); // Rn
        core.memory().write32(0x8L, 0x1234);
        core.setX(1, 0x1234L); // Rs = expected
        core.setX(2, 0x5678L); // Rt = new value

        EXECUTOR.executeOp(core, new Ir64Op.CompareAndSwap(1, 2, 0, Ir64MemSize.WORD));

        assertEquals(0x5678, core.memory().read32(0x8L), "comparação bateu — escreve Rt");
        assertEquals(0x1234L, core.x(1), "Rs sempre recebe o valor ANTIGO lido");
    }

    @Test
    void compareAndSwapFailsWhenValuesDiffer() {
        Aarch64Core core = newCore(16);
        core.setX(0, 0x8L);
        core.memory().write32(0x8L, 0x1234);
        core.setX(1, 0x9999L); // Rs = expected ERRADO
        core.setX(2, 0x5678L);

        EXECUTOR.executeOp(core, new Ir64Op.CompareAndSwap(1, 2, 0, Ir64MemSize.WORD));

        assertEquals(0x1234, core.memory().read32(0x8L), "comparação falhou — memória intacta");
        assertEquals(0x1234L, core.x(1), "Rs recebe o valor antigo mesmo na falha (semântica CMPXCHG)");
    }

    @Test
    void compareAndSwapByteOnlyComparesLowByte() {
        // Rs=0x9999_9934 (byte baixo = 0x34) contra memória=0x34 (byte) deve bater mesmo com o
        // resto de Rs "sujo" — CAS de byte só compara/grava o byte baixo.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x8L);
        core.memory().write8(0x8L, 0x34);
        core.setX(1, 0x9999_9934L);
        core.setX(2, 0x9999_9956L);

        EXECUTOR.executeOp(core, new Ir64Op.CompareAndSwap(1, 2, 0, Ir64MemSize.BYTE));

        assertEquals(0x56, core.memory().read8(0x8L) & 0xFF);
    }

    @Test
    void compareAndSwapPairSucceedsWhenBothHalvesMatch() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x20L); // Rn
        core.memory().write64(0x20L, 0x1111L);
        core.memory().write64(0x28L, 0x2222L);
        core.setX(2, 0x1111L); // Rs
        core.setX(3, 0x2222L); // Rs+1
        core.setX(4, 0xAAAAL); // Rt
        core.setX(5, 0xBBBBL); // Rt+1

        EXECUTOR.executeOp(core, new Ir64Op.CompareAndSwapPair(2, 4, 0, true));

        assertEquals(0xAAAAL, core.memory().read64(0x20L));
        assertEquals(0xBBBBL, core.memory().read64(0x28L));
        assertEquals(0x1111L, core.x(2), "par antigo sempre gravado de volta em Rs/Rs+1");
        assertEquals(0x2222L, core.x(3));
    }

    @Test
    void compareAndSwapPairFailsWhenOnlySecondHalfDiffers() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x20L);
        core.memory().write64(0x20L, 0x1111L);
        core.memory().write64(0x28L, 0x2222L);
        core.setX(2, 0x1111L); // Rs bate
        core.setX(3, 0x9999L); // Rs+1 NÃO bate
        core.setX(4, 0xAAAAL);
        core.setX(5, 0xBBBBL);

        EXECUTOR.executeOp(core, new Ir64Op.CompareAndSwapPair(2, 4, 0, true));

        assertEquals(0x1111L, core.memory().read64(0x20L), "1 metade só já falha a comparação inteira");
        assertEquals(0x2222L, core.memory().read64(0x28L));
    }

    @Test
    void loadStorePairSignedWordSignExtendsNegativeValues() {
        Aarch64Core core = newCore(32);
        core.setX(0, 0x8L);
        core.memory().write32(0x8L, 0xFFFF_FFFF); // -1 como word
        core.memory().write32(0xCL, 0x8000_0000); // Integer.MIN_VALUE como word

        EXECUTOR.executeOp(core, new Ir64Op.LoadStorePair(
                true, 1, 2, 0, false, Ir64AddressingMode.OFFSET, 0, true));

        assertEquals(-1L, core.x(1));
        assertEquals((long) Integer.MIN_VALUE, core.x(2));
    }

    @Test
    void storeNoAllocHintPairIsFunctionallyOffsetStore() {
        // STNP (addrModeField=00) não faz writeback e usa o mesmo cálculo de endereço de STP
        // offset — este teste prova que o Ir64Op.LoadStorePair resultante (addressingMode=OFFSET)
        // se comporta assim no executor, sem precisar de um Kind/campo dedicado para o hint.
        Aarch64Core core = newCore(32);
        core.setX(0, 0x8L);
        core.setX(1, 0x1234L);
        core.setX(2, 0x5678L);

        EXECUTOR.executeOp(core, new Ir64Op.LoadStorePair(
                false, 1, 2, 0, true, Ir64AddressingMode.OFFSET, 0, false));

        assertEquals(0x1234L, core.memory().read64(0x8L));
        assertEquals(0x5678L, core.memory().read64(0x10L));
        assertEquals(0x8L, core.x(0), "sem writeback — Rn intacto");
    }
}
