package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `LDR`/`STR`/`LDP`/`STP`/`LDR (literal)` SIMD&FP (B8.13) direto no executor
/// (interpretador = oráculo, G1) — complementa {@code Aarch64FpLoadStoreDecoderTest} (decode).
class Ir64BlockExecutorB813Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void loadByteZeroesRestOfRegister() {
        // "Destructive write" real (ARM DDI 0487): LDR B/H/S/D zera os bits altos do V<t>, mesmo
        // comportamento já testado para setS/setD (B6.5.1) — aqui via o caminho de load de memória.
        Aarch64Core core = newCore(16);
        core.fp().setQ(0, -1L, -1L); // V0 todo 1 antes
        core.setX(1, 0x8L);
        core.memory().write8(0x8L, 0x7F);

        EXECUTOR.executeOp(core, new Ir64Op.FpLoad64(0, 1, Ir64FpMemSize.BYTE,
                Ir64AddressingMode.OFFSET, 0L, -1, null, 0));

        assertEquals(0x7FL, core.fp().low64(0));
        assertEquals(0L, core.fp().high64(0), "os 120 bits acima do byte carregado têm que zerar");
    }

    @Test
    void storeDoubleWritesLow64WithoutTouchingMemoryAboveSize() {
        Aarch64Core core = newCore(24);
        core.fp().setD(2, 0x1122_3344_5566_7788L);
        core.setX(3, 0x8L);
        core.memory().write64(0x10L, 0xDEAD_BEEF_DEAD_BEEFL); // sentinela logo após o slot de 8 bytes

        EXECUTOR.executeOp(core, new Ir64Op.FpStore64(2, 3, Ir64FpMemSize.DOUBLE,
                Ir64AddressingMode.OFFSET, 0L, -1, null, 0));

        assertEquals(0x1122_3344_5566_7788L, core.memory().read64(0x8L));
        assertEquals(0xDEAD_BEEF_DEAD_BEEFL, core.memory().read64(0x10L), "STR D não escreve além de 8 bytes");
    }

    @Test
    void loadStoreQuadRoundTrip128Bits() {
        Aarch64Core core = newCore(32);
        core.fp().setQ(4, 0x1111_2222_3333_4444L, 0x5555_6666_7777_8888L);
        core.setX(5, 0x0L);

        EXECUTOR.executeOp(core, new Ir64Op.FpStore64(4, 5, Ir64FpMemSize.QUAD,
                Ir64AddressingMode.OFFSET, 0L, -1, null, 0));
        assertEquals(0x1111_2222_3333_4444L, core.memory().read64(0x0L));
        assertEquals(0x5555_6666_7777_8888L, core.memory().read64(0x8L));

        EXECUTOR.executeOp(core, new Ir64Op.FpLoad64(6, 5, Ir64FpMemSize.QUAD,
                Ir64AddressingMode.OFFSET, 0L, -1, null, 0));
        assertEquals(0x1111_2222_3333_4444L, core.fp().low64(6));
        assertEquals(0x5555_6666_7777_8888L, core.fp().high64(6));
    }

    @Test
    void registerOffsetWithShiftAppliesExtendBeforeAddressing() {
        // ldr d0, [x1, w2, uxtw #3] — mesma resolução de endereço de Load64 (transferAddress),
        // aqui só confirmando que FpLoad64 delega corretamente.
        Aarch64Core core = newCore(64);
        core.setX(1, 0x10L);
        core.setX(2, 2L); // índice, extend UXTW então lido como W (32 bits) antes do shift
        core.memory().write64(0x10L + (2L << 3), 0x99887766_55443322L);

        EXECUTOR.executeOp(core, new Ir64Op.FpLoad64(7, 1, Ir64FpMemSize.DOUBLE,
                Ir64AddressingMode.REGISTER_OFFSET, 0L, 2, Ir64ExtendType.UXTW, 3));

        assertEquals(0x99887766_55443322L, core.fp().d(7));
    }

    @Test
    void preIndexWritesBackBase() {
        Aarch64Core core = newCore(32);
        core.setX(1, 0x8L);
        core.fp().setD(0, 0xABCDL);

        EXECUTOR.executeOp(core, new Ir64Op.FpStore64(0, 1, Ir64FpMemSize.DOUBLE,
                Ir64AddressingMode.PRE_INDEX, 8L, -1, null, 0));

        assertEquals(0x10L, core.x(1), "pre-index escreve o novo endereço de volta em Rn");
        assertEquals(0xABCDL, core.memory().read64(0x10L));
    }

    @Test
    void postIndexUsesOriginalBaseThenWritesBack() {
        Aarch64Core core = newCore(32);
        core.setX(1, 0x8L);
        core.fp().setS(0, 0x1234);

        EXECUTOR.executeOp(core, new Ir64Op.FpStore64(0, 1, Ir64FpMemSize.SINGLE,
                Ir64AddressingMode.POST_INDEX, 4L, -1, null, 0));

        assertEquals(0xC, core.x(1), "post-index atualiza Rn DEPOIS de usar o endereço original");
        assertEquals(0x1234, core.memory().read32(0x8L), "o acesso em si usa o endereço ORIGINAL");
    }

    @Test
    void loadStorePairDoubleRoundTrip() {
        Aarch64Core core = newCore(32);
        core.fp().setD(0, 0x1111_1111_1111_1111L);
        core.fp().setD(1, 0x2222_2222_2222_2222L);
        core.setX(2, 0x0L);

        EXECUTOR.executeOp(core, new Ir64Op.FpLoadStorePair(false, 0, 1, 2, Ir64FpMemSize.DOUBLE,
                Ir64AddressingMode.OFFSET, 0L));
        assertEquals(0x1111_1111_1111_1111L, core.memory().read64(0x0L));
        assertEquals(0x2222_2222_2222_2222L, core.memory().read64(0x8L));

        EXECUTOR.executeOp(core, new Ir64Op.FpLoadStorePair(true, 3, 4, 2, Ir64FpMemSize.DOUBLE,
                Ir64AddressingMode.OFFSET, 0L));
        assertEquals(0x1111_1111_1111_1111L, core.fp().d(3));
        assertEquals(0x2222_2222_2222_2222L, core.fp().d(4));
    }

    @Test
    void loadStorePairQuadRoundTrip() {
        Aarch64Core core = newCore(64);
        core.fp().setQ(0, 0x1L, 0x2L);
        core.fp().setQ(1, 0x3L, 0x4L);
        core.setX(2, 0x0L);

        EXECUTOR.executeOp(core, new Ir64Op.FpLoadStorePair(false, 0, 1, 2, Ir64FpMemSize.QUAD,
                Ir64AddressingMode.OFFSET, 0L));
        // Q0 nos primeiros 16 bytes, Q1 nos próximos 16 (stride = 16, não 8).
        assertEquals(0x1L, core.memory().read64(0x0L));
        assertEquals(0x2L, core.memory().read64(0x8L));
        assertEquals(0x3L, core.memory().read64(0x10L));
        assertEquals(0x4L, core.memory().read64(0x18L));

        EXECUTOR.executeOp(core, new Ir64Op.FpLoadStorePair(true, 5, 6, 2, Ir64FpMemSize.QUAD,
                Ir64AddressingMode.OFFSET, 0L));
        assertEquals(0x1L, core.fp().low64(5));
        assertEquals(0x2L, core.fp().high64(5));
        assertEquals(0x3L, core.fp().low64(6));
        assertEquals(0x4L, core.fp().high64(6));
    }

    @Test
    void loadLiteralQuadReadsBothHalves() {
        Aarch64Core core = newCore(32);
        core.memory().write64(0x10L, 0xAAAA_BBBB_CCCC_DDDDL);
        core.memory().write64(0x18L, 0xEEEE_FFFF_0000_1111L);

        EXECUTOR.executeOp(core, new Ir64Op.FpLoadLiteral64(9, 0x10L, Ir64FpMemSize.QUAD));

        assertEquals(0xAAAA_BBBB_CCCC_DDDDL, core.fp().low64(9));
        assertEquals(0xEEEE_FFFF_0000_1111L, core.fp().high64(9));
    }

    @Test
    void loadLiteralSingleZeroesRest() {
        Aarch64Core core = newCore(16);
        core.fp().setQ(2, -1L, -1L);
        core.memory().write32(0x4L, 0x3F800000); // 1.0f

        EXECUTOR.executeOp(core, new Ir64Op.FpLoadLiteral64(2, 0x4L, Ir64FpMemSize.SINGLE));

        assertEquals(0x3F800000L, core.fp().s(2) & 0xFFFF_FFFFL);
        assertEquals(0L, core.fp().high64(2));
    }
}
