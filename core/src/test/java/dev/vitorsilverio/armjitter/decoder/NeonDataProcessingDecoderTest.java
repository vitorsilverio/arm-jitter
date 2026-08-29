package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Protótipo de ponta a ponta da RFC B13.2: encoding A32 de NEON (`VADD`/`VSUB` inteiro) →
/// {@link IrOp.NeonThreeSame} → execução pelo núcleo vetorial COMPARTILHADO com o lado A64.
///
/// Prova também o zero-diff: sem {@link ArmFeature#ADVANCED_SIMD} (isto é, em TODO preset que
/// existe hoje) o mesmo encoding continua caindo em `UNIMPLEMENTED`, exatamente como antes.
class NeonDataProcessingDecoderTest {
    private static final ArmArchitecture NEON_TEST_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeon",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_TEST_ARCH =
            NEON_TEST_FEATURES.withDecoderExtensions(neonFirst());

    private static List<DecoderExtension> neonFirst() {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonDataProcessingDecoder(NEON_TEST_FEATURES));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 0 D sz Vn Vd 1000 N Q M 0 Vm` — 3-reg-same, opcode `1000`, `op=0`.
    private static int neonAddSubWord(boolean subtract, int size, int vd, int vn, int vm, boolean quad) {
        return 0xF200_0800
                | (subtract ? 1 << 24 : 0)
                | ((vd >> 4) << 22)
                | (size << 20)
                | ((vn & 0xF) << 16)
                | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7)
                | (quad ? 1 << 6 : 0)
                | ((vm >> 4) << 5)
                | (vm & 0xF);
    }

    private static DecodedInstruction decode(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    // ── Encoding conferido contra o oráculo (QEMU `neon-dp.decode`, seção 3-reg-same) ──

    @Test
    void encodingMatchesTheDocumentedBitPattern() {
        // vadd.i32 d0, d1, d2
        assertEquals(0xF221_0802, neonAddSubWord(false, 2, 0, 1, 2, false));
        // vsub.i8 q0, q1, q2 (Q<n> nomeado pelo D par: q1=d2, q2=d4)
        assertEquals(0xF302_0844, neonAddSubWord(true, 0, 0, 2, 4, true));
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutAdvancedSimdTheEncodingStaysUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(ArmArchitecture.ARMV7A, neonAddSubWord(false, 2, 0, 1, 2, false)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(ArmArchitecture.ARM11_MPCORE, neonAddSubWord(false, 2, 0, 1, 2, false)).kind());
    }

    // ── Decode com a feature ligada ──

    @Test
    void vaddIntegerDecodesToNeonThreeSame() {
        DecodedInstruction decoded = decode(NEON_TEST_ARCH, neonAddSubWord(false, 2, 0, 1, 2, false));
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 2, 0, 1, 2),
                liftSingleOp(decoded));
    }

    @Test
    void vsubIntegerQuadFormDecodesToNeonThreeSame() {
        DecodedInstruction decoded = decode(NEON_TEST_ARCH, neonAddSubWord(true, 0, 0, 2, 4, true));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SUB, true, 0, 0, 2, 4),
                liftSingleOp(decoded));
    }

    @Test
    void doubleRegisterAboveD15IsAddressableWithTheFeature() {
        DecodedInstruction decoded = decode(NEON_TEST_ARCH, neonAddSubWord(false, 3, 31, 16, 17, false));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 3, 31, 16, 17),
                liftSingleOp(decoded));
    }

    @Test
    void quadFormWithOddRegisterIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(NEON_TEST_ARCH, neonAddSubWord(false, 2, 1, 2, 4, true)).kind());
    }

    // ── Execução (o mesmo núcleo de lane que o executor A64 usa) ──

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), NEON_TEST_ARCH);
    }

    @Test
    void vaddI16ExecutesLaneByLane() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0001_0002_0003_FFFFL);
        core.vfp().setD(2, 0x0010_0020_0030_0001L);
        IrOp op = liftSingleOp(decode(NEON_TEST_ARCH, neonAddSubWord(false, 1, 0, 1, 2, false)));
        new IrBlockExecutor(NEON_TEST_ARCH).executeOp(core, op, 0);
        // A lane baixa dá a volta (0xFFFF + 1) sem vazar carry para a vizinha.
        assertEquals(0x0011_0022_0033_0000L, core.vfp().d(0));
    }

    @Test
    void vsubI32ExecutesOnTheQuadForm() {
        ArmCore core = newCore();
        core.vfp().setD(2, 0x0000_0005_0000_0003L);
        core.vfp().setD(3, 0x0000_0007_0000_0009L);
        core.vfp().setD(4, 0x0000_0001_0000_0001L);
        core.vfp().setD(5, 0x0000_0001_0000_0001L);
        IrOp op = liftSingleOp(decode(NEON_TEST_ARCH, neonAddSubWord(true, 2, 0, 2, 4, true)));
        new IrBlockExecutor(NEON_TEST_ARCH).executeOp(core, op, 0);
        assertEquals(0x0000_0004_0000_0002L, core.vfp().d(0));
        assertEquals(0x0000_0006_0000_0008L, core.vfp().d(1));
    }
}
