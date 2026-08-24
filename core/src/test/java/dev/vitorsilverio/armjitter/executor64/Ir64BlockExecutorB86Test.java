package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Semântica dos ops novos da B8.6 (AdvSIMD load/store multiple/single structures) direto no
/// executor (interpretador = oráculo, G1) — complementa
/// {@code Aarch64AdvSimdLoadStoreDecoderTest} (decode).
class Ir64BlockExecutorB86Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void loadMultipleDeinterleavesTwoRegisters() {
        // ld2 {v0.4s, v1.4s}, [x0]: 8 words na memória, intercalados (par/ímpar) entre v0/v1.
        Aarch64Core core = newCore(64);
        core.setX(0, 0x0L);
        for (int i = 0; i < 8; i++) {
            core.memory().write32(i * 4L, 0x1000 + i);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreMultiple(true, 0, 0, -1, true, false, 2, 1, 2));

        Aarch64FpRegisters fp = core.fp();
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(0x1000 + lane * 2, fp.element(0, lane, 2), "v0 recebe os elementos PARES");
            assertEquals(0x1000 + lane * 2 + 1, fp.element(1, lane, 2), "v1 recebe os elementos ÍMPARES");
        }
    }

    @Test
    void storeMultipleInterleavesTwoRegisters() {
        // st2 {v0.4s, v1.4s}, [x0]: caminho inverso do teste anterior.
        Aarch64Core core = newCore(64);
        core.setX(0, 0x0L);
        Aarch64FpRegisters fp = core.fp();
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(0, lane, 2, 0x2000 + lane * 2);
            fp.setElement(1, lane, 2, 0x2000 + lane * 2 + 1);
        }

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreMultiple(false, 0, 0, -1, true, false, 2, 1, 2));

        for (int i = 0; i < 8; i++) {
            assertEquals(0x2000 + i, core.memory().read32(i * 4L));
        }
    }

    @Test
    void loadMultipleNonQuadZeroesHighBits() {
        // ld1 {v0.8b}, [x0]: forma não-quad — "SIMD&FP destructive write" (B6.5.1 D3) tem que
        // zerar os 64 bits altos de v0, mesmo que só os baixos tenham sido escritos.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.fp().setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL); // "sujeira" pré-existente nos bits altos
        core.memory().write64(0x0L, 0x0102_0304_0506_0708L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreMultiple(true, 0, 0, -1, false, false, 0, 1, 1));

        assertEquals(0x0102_0304_0506_0708L, core.fp().low64(0));
        assertEquals(0L, core.fp().high64(0), "bits altos devem ser zerados (não-quad)");
    }

    @Test
    void loadMultiplePostIndexImmediateAdvancesByTotalBytes() {
        // ld4 {v0.16b-v3.16b}, [x0], #64 — total = rpt(1) * selem(4) * (q?16:8) = 64.
        Aarch64Core core = newCore(80);
        core.setX(0, 0x0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreMultiple(true, 0, 0, -1, true, true, 0, 1, 4));

        assertEquals(64L, core.x(0));
    }

    @Test
    void loadMultiplePostIndexRegisterUsesRmValue() {
        Aarch64Core core = newCore(32);
        core.setX(0, 0x0L);
        core.setX(1, 0x30L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreMultiple(true, 0, 0, 1, true, true, 0, 1, 1));

        assertEquals(0x30L, core.x(0), "pós-índice por registrador soma Xm, ignora o total transferido");
    }

    @Test
    void loadSingleWritesOnlyTargetLane() {
        // ld1 {v0.s}[2], [x0]: só a lane 2 (32 bits) muda; o resto de v0 permanece intacto.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.fp().setQ(0, 0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
        core.memory().write32(0x0L, 0xCAFEBABE);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreSingle(true, 0, 0, -1, false, 2, 1, 2));

        assertEquals(0xCAFEBABEL, core.fp().element(0, 2, 2));
        assertEquals(0x1111_1111, core.fp().element(0, 0, 2), "lane 0 intacta");
        assertEquals(0x1111_1111, core.fp().element(0, 1, 2), "lane 1 intacta");
        assertEquals(0x2222_2222, core.fp().element(0, 3, 2), "lane 3 intacta");
    }

    @Test
    void storeSingleReadsTargetLane() {
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.fp().setQ(0, 0x0000_0000_ABCD_1234L, 0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreSingle(false, 0, 0, -1, false, 1, 1, 1));

        assertEquals(0xABCD, core.memory().read16(0x0L) & 0xFFFF);
    }

    @Test
    void loadSingleMultiRegisterAdvancesThroughConsecutiveVectors() {
        // ld3 {v0.h, v1.h, v2.h}[0], [x0]: 3 halfwords consecutivos na memória, um por registrador.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.memory().write16(0x0L, 0x1111);
        core.memory().write16(0x2L, 0x2222);
        core.memory().write16(0x4L, 0x3333);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreSingle(true, 0, 0, -1, false, 1, 3, 0));

        assertEquals(0x1111, core.fp().element(0, 0, 1));
        assertEquals(0x2222, core.fp().element(1, 0, 1));
        assertEquals(0x3333, core.fp().element(2, 0, 1));
    }

    @Test
    void loadSinglePostIndexImmediateAdvancesBySelemTimesScale() {
        // ld2 {v0.s, v1.s}[0], [x0], #8 — total = selem(2) << scale(2) = 8.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadStoreSingle(true, 0, 0, -1, true, 2, 2, 0));

        assertEquals(8L, core.x(0));
    }

    @Test
    void replicateFillsAllLanesQuad() {
        // ld1r {v0.4s}, [x0]: replica o word lido pelas 4 lanes do registrador de 128 bits.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.memory().write32(0x0L, 0xDEADBEEF);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadSingleReplicate(0, 0, -1, true, false, 2, 1));

        for (int lane = 0; lane < 4; lane++) {
            assertEquals(0xDEADBEEFL, core.fp().element(0, lane, 2));
        }
    }

    @Test
    void replicateNonQuadZeroesHighBits() {
        // ld1r {v0.2s}, [x0]: só os 64 bits baixos são preenchidos (2 lanes); os altos zeram.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.fp().setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL);
        core.memory().write32(0x0L, 0x12345678);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadSingleReplicate(0, 0, -1, false, false, 2, 1));

        assertEquals(0x12345678L, core.fp().element(0, 0, 2));
        assertEquals(0x12345678L, core.fp().element(0, 1, 2));
        assertEquals(0L, core.fp().high64(0));
    }

    @Test
    void replicateMultiRegisterAdvancesThroughConsecutiveVectors() {
        // ld2r {v0.4h, v1.4h}, [x0]: 2 halfwords consecutivos, um replicado por registrador.
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.memory().write16(0x0L, 0xAAAA);
        core.memory().write16(0x2L, 0xBBBB);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadSingleReplicate(0, 0, -1, false, false, 1, 2));

        for (int lane = 0; lane < 4; lane++) {
            assertEquals(0xAAAAL, core.fp().element(0, lane, 1));
            assertEquals(0xBBBBL, core.fp().element(1, lane, 1));
        }
    }

    @Test
    void replicatePostIndexRegisterUsesRmValue() {
        Aarch64Core core = newCore(16);
        core.setX(0, 0x0L);
        core.setX(2, 0x8L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLoadSingleReplicate(0, 0, 2, true, true, 0, 1));

        assertEquals(0x8L, core.x(0));
    }
}
