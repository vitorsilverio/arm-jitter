package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
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

/// `neon-shared.decode` — `VFML`/`VFMSL`/`VFML_scalar`/`VFMSL_scalar` (task B13.20, `FEAT_FHM`) →
/// {@link IrOp.NeonFusedMultiplyAddLong}/{@link IrOp.NeonFusedMultiplyAddLongByElement} → execução
/// pelo núcleo vetorial COMPARTILHADO (`AdvSimdLanes.fpFusedMultiplyAddLong`/
/// `fpFusedMultiplyAddLongByElement`) — nasce nesta task (nem esta nem a irmã A64, B19.13, tinham
/// semântica prévia).
///
/// Encodings golden conferidos com `arm-linux-gnueabihf-as -march=armv8.2-a+fp16fml
/// -mfpu=neon-fp-armv8` (WSL Ubuntu, `arm-none-eabi-as` indisponível neste ambiente — mesmo
/// binutils/GNU assembler, mesma ISA real, encoding idêntico) — ver `## Resultado` da task para o
/// log completo do `objdump`.
class NeonSharedDecoderFusedMultiplyAddLongTest {
    /// Arquitetura com `FP16_FUSED_MULTIPLY_ADD_LONG` — usada para todos os testes de
    /// decode/execução.
    private static final ArmArchitecture FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonFusedMultiplyAddLong",
                    ArmFeature.FP16_FUSED_MULTIPLY_ADD_LONG, ArmFeature.VFPV3_D32, ArmFeature.THUMB2);

    private static final ArmArchitecture ARCH =
            FEATURES.withDecoderExtensions(neonSharedFirst(FEATURES))
                    .withThumb32DecoderExtensions(thumbNeonSharedFirst(FEATURES));

    private static List<DecoderExtension> neonSharedFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonSharedDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    private static List<DecoderExtension> thumbNeonSharedFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonSharedDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.thumb32DecoderExtensions());
        return extensions;
    }

    // ── Encoders (campos conferidos golden contra `arm-linux-gnueabihf-as`, ver o javadoc da
    // classe). ──

    private static final int VFML_SINGLE_VALUE = 0xFC20_0810;
    private static final int VFML_DOUBLE_VALUE = 0xFC20_0850;
    private static final int VFML_SCALAR_SINGLE_VALUE = 0xFE00_0810;
    private static final int VFML_SCALAR_DOUBLE_VALUE = 0xFE00_0850;

    /// `Vd` (`D`), `Vn`/`Vm` (`S`, `0`-`31`): forma `D,S,S` (`q=0`).
    private static int vfmlSingle(boolean subtract, int vd, int sn, int sm) {
        return (subtract ? VFML_SINGLE_VALUE | (1 << 23) : VFML_SINGLE_VALUE)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((sn & 1) << 7) | ((sn >> 1) << 16)
                | ((sm & 1) << 5) | (sm >> 1);
    }

    /// `Vd` (o `D` par que inicia `Q`), `Vn`/`Vm` (`D`): forma `Q,D,D` (`q=1`).
    private static int vfmlDouble(boolean subtract, int vd, int vn, int vm) {
        return (subtract ? VFML_DOUBLE_VALUE | (1 << 23) : VFML_DOUBLE_VALUE)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `Vd` (`D`), `Vn` (`S`), `Rm` (`S0`-`S15`), `index` (`0`-`1`): forma `D,S,S[]` (`q=0`).
    private static int vfmlScalarSingle(boolean subtract, int vd, int sn, int rm, int index) {
        return (subtract ? VFML_SCALAR_SINGLE_VALUE | (1 << 20) : VFML_SCALAR_SINGLE_VALUE)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((sn & 1) << 7) | ((sn >> 1) << 16)
                | (index << 3) | ((rm & 1) << 5) | (rm >> 1);
    }

    /// `Vd` (o `D` par que inicia `Q`), `Vn` (`D`), `Rm` (`D0`-`D7`), `index` (`0`-`3`): forma
    /// `Q,D,D[]` (`q=1`).
    private static int vfmlScalarDouble(boolean subtract, int vd, int vn, int rm, int index) {
        return (subtract ? VFML_SCALAR_DOUBLE_VALUE | (1 << 20) : VFML_SCALAR_DOUBLE_VALUE)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (((index >> 1) & 1) << 5) | ((index & 1) << 3) | rm;
    }

    private static DecodedInstruction decodeArm(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decodeArm(int word) {
        return decodeArm(ARCH, word);
    }

    private static DecodedInstruction decodeThumb(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, (word >>> 16) & 0xFFFF);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(ARCH).decode(memory, 0);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static IrOp liftedOf(int word) {
        DecodedInstruction decoded = decodeArm(word);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        return liftSingleOp(decoded);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), ARCH);
    }

    private static void run(ArmCore core, int word) {
        new IrBlockExecutor(ARCH).executeOp(core, liftSingleOp(decodeArm(word)), 0);
    }

    private static float f32(ArmCore core, int d, int lane) {
        long bits = lane == 0 ? core.vfp().d(d) : (core.vfp().d(d) >>> 32);
        return Float.intBitsToFloat((int) bits);
    }

    private static void setF32(ArmCore core, int d, float lo, float hi) {
        core.vfp().setD(d, (AdvSimdLanes.floatBits(hi) << 32) | AdvSimdLanes.floatBits(lo));
    }

    private static void setS16(ArmCore core, int s, float lo, float hi) {
        core.vfp().setS(s, (int) ((AdvSimdLanes.halfBits(hi) << 16) | AdvSimdLanes.halfBits(lo)));
    }

    private static void setD16(ArmCore core, int d, float e0, float e1, float e2, float e3) {
        long bits = AdvSimdLanes.halfBits(e0) | (AdvSimdLanes.halfBits(e1) << 16)
                | (AdvSimdLanes.halfBits(e2) << 32) | (AdvSimdLanes.halfBits(e3) << 48);
        core.vfp().setD(d, bits);
    }

    // ── Encoding golden (assembler real, WSL `arm-linux-gnueabihf-as -march=armv8.2-a+fp16fml`) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xfc200830, vfmlSingle(false, 0, 0, 1));       // vfmal.f16 d0, s0, s1
        assertEquals(0xfc212831, vfmlSingle(false, 2, 2, 3));       // vfmal.f16 d2, s2, s3
        assertEquals(0xfc200851, vfmlDouble(false, 0, 0, 1));       // vfmal.f16 q0, d0, d1
        assertEquals(0xfca00830, vfmlSingle(true, 0, 0, 1));        // vfmsl.f16 d0, s0, s1
        assertEquals(0xfca00851, vfmlDouble(true, 0, 0, 1));        // vfmsl.f16 q0, d0, d1
        assertEquals(0xfe000831, vfmlScalarSingle(false, 0, 0, 3, 0));  // vfmal.f16 d0, s0, s3[0]
        assertEquals(0xfe000839, vfmlScalarSingle(false, 0, 0, 3, 1));  // vfmal.f16 d0, s0, s3[1]
        assertEquals(0xfe000851, vfmlScalarDouble(false, 0, 0, 1, 0));  // vfmal.f16 q0, d0, d1[0]
        assertEquals(0xfe000859, vfmlScalarDouble(false, 0, 0, 1, 1));  // vfmal.f16 q0, d0, d1[1]
        assertEquals(0xfe000871, vfmlScalarDouble(false, 0, 0, 1, 2));  // vfmal.f16 q0, d0, d1[2]
        assertEquals(0xfe00087f, vfmlScalarDouble(false, 0, 0, 7, 3));  // vfmal.f16 q0, d0, d7[3]
        assertEquals(0xfe100831, vfmlScalarSingle(true, 0, 0, 3, 0));   // vfmsl.f16 d0, s0, s3[0]
        assertEquals(0xfe100851, vfmlScalarDouble(true, 0, 0, 1, 0));   // vfmsl.f16 q0, d0, d1[0]
        assertEquals(0xfe0248bb, vfmlScalarSingle(false, 4, 5, 7, 1));  // vfmal.f16 d4, s5, s7[1]
        assertEquals(0xfe06687f, vfmlScalarDouble(false, 6, 6, 7, 3));  // vfmal.f16 q3, d6, d7[3]
    }

    // ── Zero-diff: nenhum preset declara FP16_FUSED_MULTIPLY_ADD_LONG ──
    //
    // **Achado real, mesma classe da B13.18**: as DUAS formas `_scalar` (bits[27:24]=`1110`,
    // bit4=`1`) colidem estruturalmente com `CoprocessorRegisterDecoder` (`MCR`/`MRC`, anexado
    // desde `ARMV4T`, que reivindica esse espaço para QUALQUER número de coprocessador) — colisão
    // PRÉ-EXISTENTE, não introduzida por esta task (o `NeonSharedDecoder` nem roda antes dele sob
    // `ARMV7A` puro). Sem a feature, as formas `_scalar` decodificam como `COPROCESSOR`
    // (coprocessador 8, inerte), não `UNIMPLEMENTED`; a forma vetorial (bits[27:24]=`1100`) não
    // colide com nada e segue `UNIMPLEMENTED` normalmente.
    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] unimplementedWords = { vfmlSingle(false, 0, 0, 1), vfmlDouble(false, 0, 0, 1) };
        for (int w : unimplementedWords) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
        int[] preExistingCoprocessorCollisionWords =
                { vfmlScalarSingle(false, 0, 0, 3, 0), vfmlScalarDouble(false, 0, 0, 1, 0) };
        for (int w : preExistingCoprocessorCollisionWords) {
            assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── FEAT_FHM é INDEPENDENTE de FEAT_FP16 (Armadilha 1 da task) ──

    @Test
    void gateIsIndependentOfFp16() {
        ArmArchitecture fp16Only = ArmArchitecture.extending(ArmArchitecture.ARMV7A,
                "ARMv7-TestFp16Only", ArmFeature.VFPV3_D32);
        int word = vfmlSingle(false, 0, 0, 1);
        // Sem FHM (mesmo com outras features de FP): permanece UNIMPLEMENTED.
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(fp16Only, word).kind());
        // Com FHM: decodifica.
        assertEquals(InstructionKind.LIFTED_IR_OP, decodeArm(ARCH, word).kind());
    }

    // ── Decode: forma vetorial, campos + sinal ──

    @Test
    void decodesVectorFormRegistersAndSign() {
        assertEquals(new IrOp.NeonFusedMultiplyAddLong(false, false, 0, 0, 1),
                liftedOf(vfmlSingle(false, 0, 0, 1)));   // VFMAL d0,s0,s1
        assertEquals(new IrOp.NeonFusedMultiplyAddLong(true, false, 0, 0, 1),
                liftedOf(vfmlSingle(true, 0, 0, 1)));    // VFMSL d0,s0,s1
        assertEquals(new IrOp.NeonFusedMultiplyAddLong(false, true, 0, 0, 1),
                liftedOf(vfmlDouble(false, 0, 0, 1)));   // VFMAL q0,d0,d1
        assertEquals(new IrOp.NeonFusedMultiplyAddLong(false, false, 2, 2, 3),
                liftedOf(vfmlSingle(false, 2, 2, 3)));   // VFMAL d2,s2,s3
    }

    // ── Decode: forma escalar, `rm`/`index` (extratores espelhando o assembler real) ──

    @Test
    void decodesScalarFormRmAndIndex() {
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, false, 0, 0, 3, 0),
                liftedOf(vfmlScalarSingle(false, 0, 0, 3, 0)));   // d0,s0,s3[0]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, false, 0, 0, 3, 1),
                liftedOf(vfmlScalarSingle(false, 0, 0, 3, 1)));   // d0,s0,s3[1]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, false, 4, 5, 7, 1),
                liftedOf(vfmlScalarSingle(false, 4, 5, 7, 1)));   // d4,s5,s7[1]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, true, 0, 0, 1, 0),
                liftedOf(vfmlScalarDouble(false, 0, 0, 1, 0)));   // q0,d0,d1[0]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, true, 0, 0, 1, 2),
                liftedOf(vfmlScalarDouble(false, 0, 0, 1, 2)));   // q0,d0,d1[2]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, true, 0, 0, 7, 3),
                liftedOf(vfmlScalarDouble(false, 0, 0, 7, 3)));   // q0,d0,d7[3]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(false, true, 6, 6, 7, 3),
                liftedOf(vfmlScalarDouble(false, 6, 6, 7, 3)));   // q3,d6,d7[3]
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElement(true, false, 0, 0, 3, 0),
                liftedOf(vfmlScalarSingle(true, 0, 0, 3, 0)));    // vfmsl d0,s0,s3[0]
    }

    // ── A MESMA palavra decodifica igual em A32 e T32 (encoding compartilhado) ──

    @Test
    void sameWordDecodesIdenticallyInArmAndThumb() {
        int[] words = {
                vfmlSingle(false, 0, 0, 1), vfmlDouble(true, 2, 4, 6),
                vfmlScalarSingle(false, 4, 5, 7, 1), vfmlScalarDouble(true, 6, 6, 7, 3),
        };
        for (int w : words) {
            DecodedInstruction arm = decodeArm(w);
            DecodedInstruction thumb = decodeThumb(w);
            assertEquals(arm.kind(), thumb.kind());
            assertEquals(InstructionKind.LIFTED_IR_OP, arm.kind());
            assertEquals(liftSingleOp(arm), liftSingleOp(thumb));
        }
    }

    // ── Execução, forma D,S,S (`q=0`): VFMAL soma, `Vd` acumula (lê e escreve) ──
    //
    // `Vd=D1` é DIFERENTE de `Vn=S0`/`Vm=S1` (ambos metades de `D0`) de propósito — este teste
    // cobre o caso NÃO-aliased; o aliasing é coberto por {@link #aliasingDestinationWithSourceIsSafe}.
    @Test
    void executesVfmalSingleFormAccumulating() {
        ArmCore core = newCore();
        setS16(core, 0, 2.0f, 3.0f);   // Sn = S0: lane0=2.0, lane1=3.0
        setS16(core, 1, 4.0f, 5.0f);   // Sm = S1: lane0=4.0, lane1=5.0
        setF32(core, 1, 10.0f, 100.0f); // Vd = D1: lane0=10.0 (acumulador), lane1=100.0
        run(core, vfmlSingle(false, 1, 0, 1)); // VFMAL d1, s0, s1
        assertEquals(10.0f + 2.0f * 4.0f, f32(core, 1, 0));
        assertEquals(100.0f + 3.0f * 5.0f, f32(core, 1, 1));
    }

    // ── Execução: VFMSL SUBTRAI o produto (`s=1`) ──

    @Test
    void vfmslSubtractsTheProduct() {
        ArmCore core = newCore();
        setS16(core, 0, 2.0f, 0.0f);
        setS16(core, 1, 4.0f, 0.0f);
        setF32(core, 1, 10.0f, 0.0f);
        run(core, vfmlSingle(true, 1, 0, 1)); // VFMSL d1, s0, s1
        assertEquals(10.0f - 2.0f * 4.0f, f32(core, 1, 0));
    }

    // ── Execução, forma Q,D,D (`q=1`): 4 lanes ──

    @Test
    void executesVfmalDoubleFormWithFourLanes() {
        ArmCore core = newCore();
        setD16(core, 2, 1.0f, 2.0f, 3.0f, 4.0f);   // Vn = D2
        setD16(core, 4, 10.0f, 10.0f, 10.0f, 10.0f); // Vm = D4
        setF32(core, 0, 0.0f, 0.0f);
        setF32(core, 1, 0.0f, 0.0f);
        run(core, vfmlDouble(false, 0, 2, 4)); // VFMAL q0, d2, d4
        assertEquals(10.0f, f32(core, 0, 0));
        assertEquals(20.0f, f32(core, 0, 1));
        assertEquals(30.0f, f32(core, 1, 0));
        assertEquals(40.0f, f32(core, 1, 1));
    }

    // ── Execução: `Vd == Vn` na forma `Q,D,D` (aliasing, E10) ──
    //
    // `Vd` (Q0 = D0/D1) COINCIDE com `Vn` (D0) — largura mista: escrever a lane f32 0 cobriria as
    // lanes f16 0/1 de `Vn` (que também é `D0`) se o núcleo não bufferizasse antes de escrever. O
    // acumulador ANTES de rodar é lido primeiro (é o próprio `Vn` reinterpretado como f32 — não
    // zero, já que `Vd`/`Vn` são o MESMO registrador): sem buffer, a leitura de `Vn` para as lanes
    // seguintes veria bits JÁ SOBRESCRITOS pelo resultado da lane anterior, divergindo do esperado.
    @Test
    void aliasingDestinationWithSourceIsSafe() {
        ArmCore core = newCore();
        setD16(core, 0, 1.0f, 2.0f, 3.0f, 4.0f); // Vn = Vd = D0 (lane0/lane1 do Q0)
        setD16(core, 4, 10.0f, 10.0f, 10.0f, 10.0f); // Vm = D4
        float acc0 = f32(core, 0, 0);
        float acc1 = f32(core, 0, 1);
        float acc2 = f32(core, 1, 0);
        float acc3 = f32(core, 1, 1);
        run(core, vfmlDouble(false, 0, 0, 4)); // VFMAL q0, d0, d0 (Vd=Q0, Vn=D0)
        assertEquals(acc0 + 10.0f, f32(core, 0, 0));
        assertEquals(acc1 + 20.0f, f32(core, 0, 1));
        assertEquals(acc2 + 30.0f, f32(core, 1, 0));
        assertEquals(acc3 + 40.0f, f32(core, 1, 1));
    }

    // ── Execução, forma escalar: `b` é FIXO (lane `index` de `Rm`), replicado ──

    @Test
    void scalarFormReplicatesTheFixedElement() {
        ArmCore core = newCore();
        setS16(core, 0, 1.0f, 2.0f);   // Sn = S0 (Vn, metade de D0): lane0=1.0, lane1=2.0
        setS16(core, 3, 100.0f, 5.0f); // S3 (Rm, metade de D1): lane0=100.0(index0), lane1=5.0(index1)
        setF32(core, 2, 0.0f, 0.0f);   // Vd = D2 — DIFERENTE de S0 (D0) e S3 (D1)
        run(core, vfmlScalarSingle(false, 2, 0, 3, 1)); // VFMAL d2, s0, s3[1] -> b fixo = 5.0
        assertEquals(1.0f * 5.0f, f32(core, 2, 0));
        assertEquals(2.0f * 5.0f, f32(core, 2, 1));
    }

    // ── Execução, forma escalar `Q,D,D[]`: índice de 2 bits, 4 lanes ──

    @Test
    void scalarDoubleFormReplicatesAcrossFourLanes() {
        // `Vd=Q0` (D0/D1) COINCIDE com `Vn=D0` de propósito — cobre aliasing na forma escalar
        // também (a acumuladora de D0/D1 já começa zerada, core novo — não precisa pré-preencher).
        ArmCore core = newCore();
        setD16(core, 0, 1.0f, 2.0f, 3.0f, 4.0f); // Vn = D0 (= metade baixa de Vd = Q0)
        setD16(core, 7, 0.0f, 0.0f, 0.0f, 9.0f); // D7 (Rm): index3 = 9.0
        float acc0 = f32(core, 0, 0);
        float acc1 = f32(core, 0, 1);
        float acc2 = f32(core, 1, 0);
        float acc3 = f32(core, 1, 1);
        run(core, vfmlScalarDouble(false, 0, 0, 7, 3)); // VFMAL q0, d0, d7[3] -> b fixo = 9.0
        assertEquals(acc0 + 9.0f, f32(core, 0, 0));
        assertEquals(acc1 + 18.0f, f32(core, 0, 1));
        assertEquals(acc2 + 27.0f, f32(core, 1, 0));
        assertEquals(acc3 + 36.0f, f32(core, 1, 1));
    }
}
