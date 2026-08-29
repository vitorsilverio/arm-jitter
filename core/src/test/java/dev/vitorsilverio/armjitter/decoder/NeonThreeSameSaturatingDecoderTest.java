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

/// NEON 3-reg-same SATURANTE / deslocamento por registrador A32 (task B13.5): `VQADD`/`VQSUB`/
/// `VSHL`/`VQSHL`/`VRSHL`/`VQRSHL`/`VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH` da seção "3-reg-same"
/// de `neon-dp.decode` → `IrOp.NeonThreeSame` → execução pelo núcleo vetorial COMPARTILHADO com o
/// lado A64 ({@code AdvSimdLanes}).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mcpu=cortex-a8 -mfpu=neon` (e
/// `-march=armv8.1-a` para as duas de `FEAT_RDM`) do devkitARM.
class NeonThreeSameSaturatingDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonSat",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static final ArmArchitecture RDM_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonRdm",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32, ArmFeature.ADVANCED_SIMD_RDM);

    private static final ArmArchitecture RDM_ARCH = RDM_FEATURES.withDecoderExtensions(neonFirst(RDM_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonDataProcessingDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 0 D sz Vn Vd opc N Q M op Vm` — `vn`/`vm` são os campos CRUS do encoding (para as
    /// 4 famílias `@3same_rev` o decoder os troca ao montar o record).
    private static int neon3s(int u, int size, int opc, int op, boolean quad, int vd, int vn, int vm) {
        return 0xF200_0000
                | (u << 24) | (size << 20) | (opc << 8) | (op << 4) | (quad ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    private static DecodedInstruction decode(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decode(int word) {
        return decode(NEON_ARCH, word);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static IrOp liftedOf(ArmArchitecture architecture, int word) {
        DecodedInstruction decoded = decode(architecture, word);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        return liftSingleOp(decoded);
    }

    private static IrOp liftedOf(int word) {
        return liftedOf(NEON_ARCH, word);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), NEON_ARCH);
    }

    private static void run(ArmCore core, int word) {
        new IrBlockExecutor(NEON_ARCH).executeOp(core, liftSingleOp(decode(word)), 0);
    }

    // ── Encoding golden (assembler real) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xF201_0012, neon3s(0, 0, 0b0000, 1, false, 0, 1, 2));   // vqadd.s8   d0,d1,d2
        assertEquals(0xF312_0054, neon3s(1, 1, 0b0000, 1, true, 0, 2, 4));    // vqadd.u16  q0,q1,q2
        assertEquals(0xF226_5217, neon3s(0, 2, 0b0010, 1, false, 5, 6, 7));   // vqsub.s32  d5,d6,d7
        assertEquals(0xF304_3215, neon3s(1, 0, 0b0010, 1, false, 3, 4, 5));   // vqsub.u8   d3,d4,d5
        assertEquals(0xF231_0012, neon3s(0, 3, 0b0000, 1, false, 0, 1, 2));   // vqadd.s64  d0,d1,d2
        // shifts (@3same_rev): campos crus Vn=shift, Vm=valor
        assertEquals(0xF222_0401, neon3s(0, 2, 0b0100, 0, false, 0, 2, 1));   // vshl.s32   d0,d1,d2
        assertEquals(0xF304_0442, neon3s(1, 0, 0b0100, 0, true, 0, 4, 2));    // vshl.u8    q0,q1,q2
        assertEquals(0xF212_0411, neon3s(0, 1, 0b0100, 1, false, 0, 2, 1));   // vqshl.s16  d0,d1,d2
        assertEquals(0xF232_0501, neon3s(0, 3, 0b0101, 0, false, 0, 2, 1));   // vrshl.s64  d0,d1,d2
        assertEquals(0xF322_0511, neon3s(1, 2, 0b0101, 1, false, 0, 2, 1));   // vqrshl.u32 d0,d1,d2
        assertEquals(0xF211_0B02, neon3s(0, 1, 0b1011, 0, false, 0, 1, 2));   // vqdmulh.s16  d0,d1,d2
        assertEquals(0xF322_0B44, neon3s(1, 2, 0b1011, 0, true, 0, 2, 4));    // vqrdmulh.s32 q0,q1,q2
        assertEquals(0xF311_0B12, neon3s(1, 1, 0b1011, 1, false, 0, 1, 2));   // vqrdmlah.s16 d0,d1,d2
        assertEquals(0xF322_0B54, neon3s(1, 2, 0b1011, 1, true, 0, 2, 4));    // vqrdmlah.s32 q0,q1,q2
        assertEquals(0xF321_0C12, neon3s(1, 2, 0b1100, 1, false, 0, 1, 2));   // vqrdmlsh.s32 d0,d1,d2
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                neon3s(0, 0, 0b0000, 1, false, 0, 1, 2), // vqadd
                neon3s(0, 2, 0b0010, 1, false, 0, 1, 2), // vqsub
                neon3s(0, 2, 0b0100, 0, false, 0, 2, 1), // vshl
                neon3s(0, 1, 0b0101, 1, false, 0, 2, 1), // vqrshl
                neon3s(0, 1, 0b1011, 0, false, 0, 1, 2), // vqdmulh
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Decode: cada família → AdvSimdThreeSameOp certo ──

    @Test
    void saturatingAddSubFamiliesDecode() {
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQADD, false, 0, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b0000, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UQADD, true, 1, 0, 2, 4),
                liftedOf(neon3s(1, 1, 0b0000, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQSUB, false, 2, 5, 6, 7),
                liftedOf(neon3s(0, 2, 0b0010, 1, false, 5, 6, 7)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UQSUB, false, 0, 3, 4, 5),
                liftedOf(neon3s(1, 0, 0b0010, 1, false, 3, 4, 5)));
        // VQADD/VQSUB aceitam .i64 (size==3):
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQADD, false, 3, 0, 1, 2),
                liftedOf(neon3s(0, 3, 0b0000, 1, false, 0, 1, 2)));
    }

    @Test
    void shiftFamiliesDecodeWithOperandSwap() {
        // Campos crus Vn=2 (quantidade), Vm=1 (valor) → record vn=1 (valor), vm=2 (quantidade).
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SSHL, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b0100, 0, false, 0, 2, 1)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.USHL, true, 0, 0, 2, 4),
                liftedOf(neon3s(1, 0, 0b0100, 0, true, 0, 4, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQSHL, false, 1, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b0100, 1, false, 0, 2, 1)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UQSHL, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b0100, 1, false, 0, 2, 1)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SRSHL, false, 3, 0, 1, 2),
                liftedOf(neon3s(0, 3, 0b0101, 0, false, 0, 2, 1)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.URSHL, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b0101, 0, false, 0, 2, 1)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQRSHL, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b0101, 1, false, 0, 2, 1)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UQRSHL, false, 2, 0, 1, 2),
                liftedOf(neon3s(1, 2, 0b0101, 1, false, 0, 2, 1)));
    }

    @Test
    void doublingMultiplyFamiliesDecode() {
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQDMULH, false, 1, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b1011, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQRDMULH, true, 2, 0, 2, 4),
                liftedOf(neon3s(1, 2, 0b1011, 0, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQRDMLAH, false, 1, 0, 1, 2),
                liftedOf(RDM_ARCH, neon3s(1, 1, 0b1011, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SQRDMLSH, false, 2, 0, 1, 2),
                liftedOf(RDM_ARCH, neon3s(1, 2, 0b1100, 1, false, 0, 1, 2)));
    }

    // ── UNDEFINED / gating (G8) ──

    @Test
    void rdmInstructionsNeedTheirOwnFeature() {
        // Sob ADVANCED_SIMD mas sem ADVANCED_SIMD_RDM → UNIMPLEMENTED (não misdecode).
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 1, 0b1011, 1, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 2, 0b1100, 1, false, 0, 1, 2)).kind());
        // Com a feature → decodifica.
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(RDM_ARCH, neon3s(1, 1, 0b1011, 1, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(RDM_ARCH, neon3s(1, 2, 0b1100, 1, false, 0, 1, 2)).kind());
    }

    @Test
    void doublingMultiplyIsHalfwordOrWordOnly() {
        // {opc, op, u, arch} — VQDMULH/VQRDMULH em qualquer arch NEON; VQRDMLAH/VQRDMLSH só sob RDM.
        Object[][] cases = {
                {0b1011, 0, 0, NEON_ARCH}, {0b1011, 0, 1, NEON_ARCH},
                {0b1011, 1, 1, RDM_ARCH}, {0b1100, 1, 1, RDM_ARCH},
        };
        for (Object[] c : cases) {
            int opc = (int) c[0], op = (int) c[1], u = (int) c[2];
            ArmArchitecture arch = (ArmArchitecture) c[3];
            assertEquals(InstructionKind.LIFTED_IR_OP,
                    decode(arch, neon3s(u, 1, opc, op, false, 0, 1, 2)).kind(), "size=1");
            assertEquals(InstructionKind.LIFTED_IR_OP,
                    decode(arch, neon3s(u, 2, opc, op, false, 0, 1, 2)).kind(), "size=2");
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(arch, neon3s(u, 0, opc, op, false, 0, 1, 2)).kind(), "size=0");
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(arch, neon3s(u, 3, opc, op, false, 0, 1, 2)).kind(), "size=3");
        }
    }

    @Test
    void quadFormWithOddRegisterIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 2, 0b0000, 1, true, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 2, 0b0100, 0, true, 0, 3, 2)).kind());
    }

    @Test
    void vqdmulhFamilyStaysUnimplementedInPlainArmv7a() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(ArmArchitecture.ARMV7A, neon3s(0, 1, 0b1011, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(ArmArchitecture.ARM11_MPCORE, neon3s(0, 2, 0b0101, 1, false, 0, 2, 1)).kind());
    }

    // ── Execução: núcleo compartilhado com o A64 ──

    @Test
    void vqaddSignedSaturatesAtTheUpperBound() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_007FL); // 0x7F (s8 máx)
        core.vfp().setD(2, 0x0000_0000_0000_0001L);
        run(core, neon3s(0, 0, 0b0000, 1, false, 0, 1, 2)); // vqadd.s8
        assertEquals(0x0000_0000_0000_007FL, core.vfp().d(0)); // satura, NÃO 0x80
    }

    @Test
    void vqsubUnsignedSaturatesAtZero() {
        ArmCore core = newCore();
        core.vfp().setD(4, 0x0000_0000_0000_0000L);
        core.vfp().setD(5, 0x0000_0000_0000_0001L);
        run(core, neon3s(1, 0, 0b0010, 1, false, 3, 4, 5)); // vqsub.u8 d3,d4,d5
        assertEquals(0x0000_0000_0000_0000L, core.vfp().d(3)); // 0 - 1 satura em 0, NÃO 0xFF
    }

    @Test
    void vshlShiftsTheValueRegisterByTheAmountRegister() {
        // Prova a TROCA de operando: d1 = valor, d2 = quantidade. Registradores e valores distintos.
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_0003L); // valor
        core.vfp().setD(2, 0x0000_0000_0000_0004L); // quantidade = 4
        run(core, neon3s(0, 2, 0b0100, 0, false, 0, 2, 1)); // vshl.s32 d0,d1,d2
        assertEquals(0x0000_0000_0000_0030L, core.vfp().d(0)); // 3 << 4 = 0x30 (NÃO 4 << 3 = 0x20)
    }

    @Test
    void vshlWithNegativeAmountShiftsRight() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_0080L); // valor s32 = 128
        core.vfp().setD(2, 0x0000_0000_0000_00FEL); // quantidade = -2 (byte baixo)
        run(core, neon3s(0, 2, 0b0100, 0, false, 0, 2, 1)); // vshl.s32
        assertEquals(0x0000_0000_0000_0020L, core.vfp().d(0)); // 128 >> 2 = 32
    }

    @Test
    void vrshlRoundsOnRightShiftUnlikeVshl() {
        ArmCore vshl = newCore();
        vshl.vfp().setD(1, 0x0000_0000_0000_0007L); // 7
        vshl.vfp().setD(2, 0x0000_0000_0000_00FFL); // -1
        run(vshl, neon3s(0, 2, 0b0100, 0, false, 0, 2, 1)); // vshl.s32 : 7 >> 1 = 3
        assertEquals(3L, vshl.vfp().d(0));

        ArmCore vrshl = newCore();
        vrshl.vfp().setD(1, 0x0000_0000_0000_0007L);
        vrshl.vfp().setD(2, 0x0000_0000_0000_00FFL);
        run(vrshl, neon3s(0, 2, 0b0101, 0, false, 0, 2, 1)); // vrshl.s32 : (7 + 1) >> 1 = 4
        assertEquals(4L, vrshl.vfp().d(0));
    }

    @Test
    void vqshlSaturatesOnLeftOverflow() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_4000L); // 0x4000 (s16)
        core.vfp().setD(2, 0x0000_0000_0000_0002L); // shift left 2 → 0x10000 > s16 máx
        run(core, neon3s(0, 1, 0b0100, 1, false, 0, 2, 1)); // vqshl.s16
        assertEquals(0x0000_0000_0000_7FFFL, core.vfp().d(0)); // satura em 0x7FFF
    }

    @Test
    void vqdmulhSaturatesTheDoubledProduct() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_8000L); // -32768 (s16)
        core.vfp().setD(2, 0x0000_0000_0000_8000L); // -32768
        run(core, neon3s(0, 1, 0b1011, 0, false, 0, 1, 2)); // vqdmulh.s16
        // 2 * (-32768)*(-32768) >> 16 = 0x8000 → satura em 0x7FFF.
        assertEquals(0x0000_0000_0000_7FFFL, core.vfp().d(0));
    }

    @Test
    void vqrdmlahReadsDestinationAndSaturatesTwice() {
        ArmCore core = new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), RDM_ARCH);
        core.vfp().setD(0, 0x0000_0000_0000_7FFFL); // Rd pré-preenchido (s16 máx)
        core.vfp().setD(1, 0x0000_0000_0000_4000L);
        core.vfp().setD(2, 0x0000_0000_0000_4000L);
        // SQRDMULH(0x4000,0x4000) já satura em 0x7FFF (o produto dobrado arredondado = 0x8000);
        // 0x7FFF + 0x7FFF satura DE NOVO em 0x7FFF (as duas saturações de FEAT_RDM).
        new IrBlockExecutor(RDM_ARCH).executeOp(core,
                liftSingleOp(decode(RDM_ARCH, neon3s(1, 1, 0b1011, 1, false, 0, 1, 2))), 0);
        assertEquals(0x0000_0000_0000_7FFFL, core.vfp().d(0));
    }

    @Test
    void quadFormExecutesBothDoublewords() {
        ArmCore core = newCore();
        core.vfp().setD(2, 0x0000_0000_0000_007FL);
        core.vfp().setD(3, 0x007F_0000_0000_0000L);
        core.vfp().setD(4, 0x0000_0000_0000_0001L);
        core.vfp().setD(5, 0x0001_0000_0000_0000L);
        run(core, neon3s(0, 0, 0b0000, 1, true, 0, 2, 4)); // vqadd.s8 q0,q1,q2
        assertEquals(0x0000_0000_0000_007FL, core.vfp().d(0));
        assertEquals(0x007F_0000_0000_0000L, core.vfp().d(1));
    }
}
