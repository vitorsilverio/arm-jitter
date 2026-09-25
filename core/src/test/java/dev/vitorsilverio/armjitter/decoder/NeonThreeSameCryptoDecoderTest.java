package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// NEON "cripto de três registradores" A32 (task B13.23): `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/
/// `SHA256H`/`SHA256H2`/`SHA256SU1` — `neon-dp.decode` seção "3-reg-same", `opc=1100`/`op=0`,
/// discriminadas por `U`/`size`. MESMA extensão de `NeonTwoRegMiscDecoderTest` (B13.15,
/// `AESE`/`AESD`/`AESMC`/`AESIMC`/`SHA1H`/`SHA1SU1`/`SHA256SU0`).
///
/// Execução pelo núcleo vetorial COMPARTILHADO com o lado A64
/// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#shaThreeRegister}) — os vetores de
/// referência são os MESMOS de {@code Ir64CryptoShaExecutorTest} (B8.11b), reindexados de `Q<n>`
/// para o par `D<2n>`/`D<2n+1>` (mesma convenção de palavra plana de {@link VfpRegisters}).
///
/// Encoding conferido bit a bit contra `target/isa-decode/neon-dp.decode` (QEMU real, cache local
/// — sem assemblador ARM disponível nesta sessão).
class NeonThreeSameCryptoDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonNoCrypto",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static final ArmArchitecture CRYPTO_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ArmV7-TestNeonCryptoThreeReg",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32, ArmFeature.CRYPTO);

    private static final ArmArchitecture CRYPTO_ARCH =
            CRYPTO_FEATURES.withDecoderExtensions(neonFirst(CRYPTO_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonDataProcessingDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 0 D size Vn Vd 1100 N 1 M 0 Vm` — `Q` é FIXO em `1` no `.decode` real.
    private static int shaThreeReg(int u, int size, boolean quad, int vd, int vn, int vm) {
        return 0xF200_0C00
                | (u << 24) | (size << 20) | (quad ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
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

    private static IrOp liftedOf(int word) {
        DecodedInstruction decoded = decode(CRYPTO_ARCH, word);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        return liftSingleOp(decoded);
    }

    // ── Encoding conferido contra o `.decode` real (@3same_crypto, Q0=D0/D1, Q1=D2/D3, Q2=D4/D5) ──

    @Test
    void encodingMatchesTheDocumentedBitPattern() {
        assertEquals(0xF202_0C44, shaThreeReg(0, 0, true, 0, 2, 4)); // sha1c.32   q0,q1,q2
        assertEquals(0xF212_0C44, shaThreeReg(0, 1, true, 0, 2, 4)); // sha1p.32   q0,q1,q2
        assertEquals(0xF222_0C44, shaThreeReg(0, 2, true, 0, 2, 4)); // sha1m.32   q0,q1,q2
        assertEquals(0xF232_0C44, shaThreeReg(0, 3, true, 0, 2, 4)); // sha1su0.32 q0,q1,q2
        assertEquals(0xF302_0C44, shaThreeReg(1, 0, true, 0, 2, 4)); // sha256h.32   q0,q1,q2
        assertEquals(0xF312_0C44, shaThreeReg(1, 1, true, 0, 2, 4)); // sha256h2.32  q0,q1,q2
        assertEquals(0xF322_0C44, shaThreeReg(1, 2, true, 0, 2, 4)); // sha256su1.32 q0,q1,q2
    }

    // ── Zero-diff: nenhum preset declara CRYPTO (nem ADVANCED_SIMD) ──

    @Test
    void withoutCryptoTheEncodingStaysUnimplemented() {
        int word = shaThreeReg(0, 0, true, 0, 2, 4);
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, word).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, word).kind());
        // `ADVANCED_SIMD` sozinho (sem `CRYPTO`) — Armadilha 3: features SEPARADAS.
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(NEON_ARCH, word).kind());
    }

    // ── Decode com a feature ligada ──

    @Test
    void decodesAllSevenMnemonics() {
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA1C, 0, 2, 4),
                liftedOf(shaThreeReg(0, 0, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA1P, 0, 2, 4),
                liftedOf(shaThreeReg(0, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA1M, 0, 2, 4),
                liftedOf(shaThreeReg(0, 2, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA1SU0, 0, 2, 4),
                liftedOf(shaThreeReg(0, 3, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA256H, 0, 2, 4),
                liftedOf(shaThreeReg(1, 0, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA256H2, 0, 2, 4),
                liftedOf(shaThreeReg(1, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonCryptoShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA256SU1, 0, 2, 4),
                liftedOf(shaThreeReg(1, 2, true, 0, 2, 4)));
    }

    /// `U=1 size=11` não está alocado (só `SHA1SU0`, do lado `U=0`, usa `size=11`).
    @Test
    void unallocatedUAndSizeCombinationStaysUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(CRYPTO_ARCH, shaThreeReg(1, 3, true, 0, 2, 4)).kind());
    }

    /// `Q` (bit6) é FIXO em `1` no `.decode` real — com `Q=0` o encoding não é nenhuma das 7 formas.
    @Test
    void nonQuadFormStaysUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(CRYPTO_ARCH, shaThreeReg(0, 0, false, 0, 2, 4)).kind());
    }

    /// Índice de `D` ÍMPAR em qualquer um dos três operandos é UNDEFINED (todas as 7 são `Q`).
    @Test
    void oddDoublewordIndexStaysUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(CRYPTO_ARCH, shaThreeReg(0, 0, true, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(CRYPTO_ARCH, shaThreeReg(0, 0, true, 0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(CRYPTO_ARCH, shaThreeReg(0, 0, true, 0, 2, 5)).kind());
    }

    // ── Execução: MESMOS vetores de referência de `Ir64CryptoShaExecutorTest` (B8.11b) ──

    private static long pack(int lowWord, int highWord) {
        return (lowWord & 0xFFFFFFFFL) | ((highWord & 0xFFFFFFFFL) << 32);
    }

    private static ArmCore coreWithThreeRegisterOperands() {
        ArmCore core = new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), CRYPTO_ARCH);
        VfpRegisters vfp = core.vfp();
        // Q0(D0/D1) = [1,2,3,4], Q1(D2/D3) = [5,6,7,8] (só a palavra 0 importa para SHA1C/P/M),
        // Q2(D4/D5) = [0x11111111,0x22222222,0x33333333,0x44444444].
        vfp.setD(0, pack(1, 2));
        vfp.setD(1, pack(3, 4));
        vfp.setD(2, pack(5, 6));
        vfp.setD(3, pack(7, 8));
        vfp.setD(4, pack(0x11111111, 0x22222222));
        vfp.setD(5, pack(0x33333333, 0x44444444));
        return core;
    }

    private static void run(ArmCore core, int word) {
        new IrBlockExecutor(CRYPTO_ARCH).executeOp(core, liftSingleOp(decode(CRYPTO_ARCH, word)), 0);
    }

    private static void assertQWords(VfpRegisters vfp, int w0, int w1, int w2, int w3) {
        assertEquals(pack(w0, w1), vfp.d(0));
        assertEquals(pack(w2, w3), vfp.d(1));
    }

    @Test
    void sha1c() {
        ArmCore core = coreWithThreeRegisterOperands();
        run(core, shaThreeReg(0, 0, true, 0, 2, 4));
        assertQWords(core.vfp(), 0x40159415, 0x3bbc687e, 0x9111126a, 0x0444444f);
    }

    @Test
    void sha1p() {
        ArmCore core = coreWithThreeRegisterOperands();
        run(core, shaThreeReg(0, 1, true, 0, 2, 4));
        assertQWords(core.vfp(), 0x9df30b39, 0x8ccd75c9, 0xb1111262, 0xc444444e);
    }

    @Test
    void sha1m() {
        ArmCore core = coreWithThreeRegisterOperands();
        run(core, shaThreeReg(0, 2, true, 0, 2, 4));
        assertQWords(core.vfp(), 0x80139023, 0xbbbc585e, 0x5111124a, 0x0444444e);
    }

    @Test
    void sha256h() {
        ArmCore core = coreWithThreeRegisterOperands();
        run(core, shaThreeReg(1, 0, true, 0, 2, 4));
        assertQWords(core.vfp(), 0xd0183254, 0xa3565ffc, 0x02c1a7e4, 0x65b917a2);
    }

    @Test
    void sha256h2() {
        ArmCore core = coreWithThreeRegisterOperands();
        run(core, shaThreeReg(1, 1, true, 0, 2, 4));
        assertQWords(core.vfp(), 0x67a8d5a0, 0x2145e5fc, 0xf960d01b, 0x1531119f);
    }

    @Test
    void sha256su1() {
        ArmCore core = coreWithThreeRegisterOperands();
        run(core, shaThreeReg(1, 2, true, 0, 2, 4));
        assertQWords(core.vfp(), 0xfff3333a, 0xaabbbbc4, 0xffc5dcd6, 0xbbc17ff9);
    }

    @Test
    void sha1su0() {
        // Q0(D0/D1) = [1,2,3,4], Q1(D2/D3) = [5,...] (só a palavra 0 importa), Q2(D4/D5) = [7,8,9,10].
        ArmCore core = new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), CRYPTO_ARCH);
        VfpRegisters vfp = core.vfp();
        vfp.setD(0, pack(1, 2));
        vfp.setD(1, pack(3, 4));
        vfp.setD(2, pack(5, 6));
        vfp.setD(3, pack(7, 8));
        vfp.setD(4, pack(7, 8));
        vfp.setD(5, pack(9, 10));

        run(core, shaThreeReg(0, 3, true, 0, 2, 4));

        assertEquals(0x0000000e00000005L, vfp.d(0));
        assertEquals(0x000000080000000fL, vfp.d(1));
    }
}
