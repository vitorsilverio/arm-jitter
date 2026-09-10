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

/// `neon-shared.decode` — `VDOT_b16`/`VMMLA_b16`/`VFMA_b16` + as formas `_scal`/`_scalar`
/// correspondentes (task B13.21, `FEAT_BF16`, a MESMA feature que a B13.13 criou para
/// `VCVT_B16_F32`) → {@link IrOp.NeonDotProductBFloat16}/{@link IrOp.NeonDotProductByElementBFloat16}/
/// {@link IrOp.NeonMatrixMultiplyAccumulateBFloat16}/{@link IrOp.NeonFusedMultiplyAddLongBFloat16}/
/// {@link IrOp.NeonFusedMultiplyAddLongByElementBFloat16} → execução pelo núcleo `bfloat16`
/// COMPARTILHADO ({@code AdvSimdLanes.bfDotProduct}/`bfDotProductByElement`/
/// `bfMatrixMultiplyAccumulate`/`bfMultiplyAddLong`/`bfMultiplyAddLongByElement`), criado pela
/// B19.7 (a task irmã A64) — reusado sem NENHUMA mudança. **Última task de `neon-shared`: fecha o
/// arquivo (23 linhas, B13.17-B13.21 todas com dono).**
///
/// Encodings golden conferidos com `arm-linux-gnueabihf-as -march=armv8.2-a+bf16 -mfpu=neon-fp-armv8`
/// (WSL Ubuntu) — ver `## Resultado` da task para o log completo do `objdump`. **Achado real**: ao
/// contrário do que o `.decode` sugere (campo nomeado `q`), a forma vetorial/escalar de `VFMA_b16`
/// (mnemônicos `VFMAB`/`VFMAT`) NÃO tem forma não-`Q` — GAS recusa `vfmab.bf16 d0,d1,d2`
/// (`invalid instruction shape`) — e o bit é, na prática, o seletor BOTTOM/TOP, estrutura idêntica
/// ao A64 `BFMLALB`/`BFMLALT`.
class NeonSharedDecoderBFloat16Test {
    /// Arquitetura com `BFLOAT16` — usada para todos os testes de decode/execução.
    private static final ArmArchitecture FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonBFloat16",
                    ArmFeature.BFLOAT16, ArmFeature.VFPV3_D32, ArmFeature.THUMB2);

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

    private static final int VDOT_B16_VALUE = 0xFC00_0D00;
    private static final int VDOT_B16_SCAL_VALUE = 0xFE00_0D00;
    private static final int VMMLA_B16_VALUE = 0xFC00_0C40;
    private static final int VFMA_B16_VALUE = 0xFC30_0810;
    private static final int VFMA_B16_SCAL_VALUE = 0xFE30_0810;

    private static int vdot(boolean quad, int vd, int vn, int vm) {
        return VDOT_B16_VALUE | (quad ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    private static int vdotScal(boolean quad, int vd, int vn, int vm, int index) {
        return VDOT_B16_SCAL_VALUE | (quad ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (index << 5) | (vm & 0xF);
    }

    /// `VMMLA_b16`: SEMPRE 128 bits, sem campo `quad`.
    private static int vmmla(int vd, int vn, int vm) {
        return VMMLA_B16_VALUE
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `VFMA_b16` (`VFMAB`/`VFMAT`): SEMPRE `Q`, `top` no bit6 (achado real, ver javadoc da classe).
    private static int vfma(boolean top, int vd, int vn, int vm) {
        return VFMA_B16_VALUE | (top ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `VFMA_b16_scal`: `vm` de 3 bits (`D0`-`D7`), `index` de 2 bits ESPALHADOS (`bit5:bit3`).
    private static int vfmaScal(boolean top, int vd, int vn, int vm, int index) {
        return VFMA_B16_SCAL_VALUE | (top ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (((index >> 1) & 1) << 5) | ((index & 1) << 3) | vm;
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

    /// Empacota 4 elementos `bf16` (índice `0` = lane menos significativa) num `D` de 64 bits.
    private static void setD16(ArmCore core, int d, float e0, float e1, float e2, float e3) {
        long bits = AdvSimdLanes.bf16Bits(e0) | (AdvSimdLanes.bf16Bits(e1) << 16)
                | (AdvSimdLanes.bf16Bits(e2) << 32) | (AdvSimdLanes.bf16Bits(e3) << 48);
        core.vfp().setD(d, bits);
    }

    // ── Encoding golden (assembler real, WSL `arm-linux-gnueabihf-as -march=armv8.2-a+bf16`) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xFC02_0D44, vdot(true, 0, 2, 4));       // vdot.bf16 q0, q1, q2
        assertEquals(0xFC08_6D4A, vdot(true, 6, 8, 10));      // vdot.bf16 q3, q4, q5
        assertEquals(0xFC01_0D02, vdot(false, 0, 1, 2));      // vdot.bf16 d0, d1, d2
        assertEquals(0xFC04_3D05, vdot(false, 3, 4, 5));      // vdot.bf16 d3, d4, d5
        assertEquals(0xFC02_0C44, vmmla(0, 2, 4));            // vmmla.bf16 q0, q1, q2
        assertEquals(0xFC08_6C4A, vmmla(6, 8, 10));           // vmmla.bf16 q3, q4, q5
        assertEquals(0xFC32_0814, vfma(false, 0, 2, 4));      // vfmab.bf16 q0, q1, q2
        assertEquals(0xFC32_0854, vfma(true, 0, 2, 4));       // vfmat.bf16 q0, q1, q2
        assertEquals(0xFC38_681A, vfma(false, 6, 8, 10));     // vfmab.bf16 q3, q4, q5
        assertEquals(0xFC3E_C850, vfma(true, 12, 14, 0));     // vfmat.bf16 q6, q7, q0
        assertEquals(0xFE01_0D22, vdotScal(false, 0, 1, 2, 1)); // vdot.bf16 d0, d1, d2[1]
        assertEquals(0xFE01_0D02, vdotScal(false, 0, 1, 2, 0)); // vdot.bf16 d0, d1, d2[0]
        assertEquals(0xFE02_0D42, vdotScal(true, 0, 2, 2, 0));  // vdot.bf16 q0, q1, d2[0]
        assertEquals(0xFE02_0D62, vdotScal(true, 0, 2, 2, 1));  // vdot.bf16 q0, q1, d2[1]
        assertEquals(0xFE32_0812, vfmaScal(false, 0, 2, 2, 0)); // vfmab.bf16 q0, q1, d2[0]
        assertEquals(0xFE32_083A, vfmaScal(false, 0, 2, 2, 3)); // vfmab.bf16 q0, q1, d2[3]
        assertEquals(0xFE32_0872, vfmaScal(true, 0, 2, 2, 2));  // vfmat.bf16 q0, q1, d2[2]
    }

    // ── Zero-diff: nenhum preset declara BFLOAT16 (nem para `neon-shared`, ver B13.22) ──
    //
    // **Achado real, mesma classe das B13.18/B13.20**: `VFMA_b16_scal` (`VFMAB`/`VFMAT` indexados,
    // bits[27:24]=`1110`, bit4=`1` fixo) colide estruturalmente com `CoprocessorRegisterDecoder`
    // (`MCR`/`MRC`, anexado desde `ARMV4T`) — colisão PRÉ-EXISTENTE, não introduzida por esta task.
    // `VDOT_b16_scal` (bit4=`0`, sinal sempre `0` — `bf16` não tem variante assinada) NÃO colide.
    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] unimplementedWords = {
                vdot(true, 0, 2, 4), vdot(false, 0, 1, 2), vmmla(0, 2, 4),
                vfma(false, 0, 2, 4), vfma(true, 0, 2, 4),
                vdotScal(false, 0, 1, 2, 1), vdotScal(true, 0, 2, 2, 0),
        };
        for (int w : unimplementedWords) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
        int[] preExistingCoprocessorCollisionWords =
                { vfmaScal(false, 0, 2, 2, 0), vfmaScal(true, 0, 2, 2, 2) };
        for (int w : preExistingCoprocessorCollisionWords) {
            assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── O arquivo `neon-shared` fecha aqui: um encoding não reconhecido dentro do frame agora é
    // UNIMPLEMENTED de verdade (G8), não mais `null` (a dívida que a B13.17 registrou) ──

    @Test
    void unrecognizedEncodingWithinTheFrameIsUnimplementedNotNull() {
        // Dentro do frame `neon-shared` (prefixo 0xFC/0xFE), mas não bate em nenhuma das 23 linhas
        // conhecidas: bits[21:20]=01 no espaço de `VFMA_b16`/produto escalar não existe em nenhuma
        // VALUE cadastrada.
        int unclaimed = (VFMA_B16_VALUE & ~0x0030_0000) | 0x0010_0000;
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(unclaimed).kind());
    }

    // ── Decode: VDOT_b16, campos + quad ──

    @Test
    void decodesDotProductVectorFields() {
        assertEquals(new IrOp.NeonDotProductBFloat16(true, 0, 2, 4), liftedOf(vdot(true, 0, 2, 4)));
        assertEquals(new IrOp.NeonDotProductBFloat16(true, 6, 8, 10), liftedOf(vdot(true, 6, 8, 10)));
        assertEquals(new IrOp.NeonDotProductBFloat16(false, 0, 1, 2), liftedOf(vdot(false, 0, 1, 2)));
        assertEquals(new IrOp.NeonDotProductBFloat16(false, 3, 4, 5), liftedOf(vdot(false, 3, 4, 5)));
    }

    @Test
    void oddRegisterInQuadFormIsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vdot(true, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vdot(true, 0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vdot(true, 0, 2, 5)).kind());
        // Forma D (não-quad): índice ímpar é VÁLIDO (não há restrição de par nesta forma).
        assertEquals(InstructionKind.LIFTED_IR_OP, decodeArm(vdot(false, 1, 3, 5)).kind());
    }

    // ── Decode: VDOT_b16_scal, campos + índice + quad ──

    @Test
    void decodesDotProductScalarFields() {
        assertEquals(new IrOp.NeonDotProductByElementBFloat16(false, 0, 1, 2, 1),
                liftedOf(vdotScal(false, 0, 1, 2, 1)));
        assertEquals(new IrOp.NeonDotProductByElementBFloat16(false, 0, 1, 2, 0),
                liftedOf(vdotScal(false, 0, 1, 2, 0)));
        assertEquals(new IrOp.NeonDotProductByElementBFloat16(true, 0, 2, 2, 0),
                liftedOf(vdotScal(true, 0, 2, 2, 0)));
        assertEquals(new IrOp.NeonDotProductByElementBFloat16(true, 0, 2, 2, 1),
                liftedOf(vdotScal(true, 0, 2, 2, 1)));
    }

    // ── Decode: VMMLA_b16, campos, SEMPRE 128 bits ──

    @Test
    void decodesMatrixMultiplyFields() {
        assertEquals(new IrOp.NeonMatrixMultiplyAccumulateBFloat16(0, 2, 4), liftedOf(vmmla(0, 2, 4)));
        assertEquals(new IrOp.NeonMatrixMultiplyAccumulateBFloat16(6, 8, 10), liftedOf(vmmla(6, 8, 10)));
    }

    @Test
    void matrixMultiplyOddRegisterIndexIsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vmmla(1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vmmla(0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vmmla(0, 2, 5)).kind());
    }

    // ── Decode: VFMA_b16, campos + top (SEMPRE 128 bits — não há forma D,D,D) ──

    @Test
    void decodesFusedMultiplyAddLongFields() {
        assertEquals(new IrOp.NeonFusedMultiplyAddLongBFloat16(false, 0, 2, 4), liftedOf(vfma(false, 0, 2, 4)));
        assertEquals(new IrOp.NeonFusedMultiplyAddLongBFloat16(true, 0, 2, 4), liftedOf(vfma(true, 0, 2, 4)));
        assertEquals(new IrOp.NeonFusedMultiplyAddLongBFloat16(false, 6, 8, 10), liftedOf(vfma(false, 6, 8, 10)));
        assertEquals(new IrOp.NeonFusedMultiplyAddLongBFloat16(true, 12, 14, 0), liftedOf(vfma(true, 12, 14, 0)));
    }

    @Test
    void fusedMultiplyAddLongOddRegisterIndexIsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfma(false, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfma(false, 0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfma(false, 0, 2, 5)).kind());
    }

    // ── Decode: VFMA_b16_scal, `vm` restrito a D0-D7 + índice espalhado ──

    @Test
    void decodesFusedMultiplyAddLongScalarFields() {
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElementBFloat16(false, 0, 2, 2, 0),
                liftedOf(vfmaScal(false, 0, 2, 2, 0)));
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElementBFloat16(false, 0, 2, 2, 3),
                liftedOf(vfmaScal(false, 0, 2, 2, 3)));
        assertEquals(new IrOp.NeonFusedMultiplyAddLongByElementBFloat16(true, 0, 2, 2, 2),
                liftedOf(vfmaScal(true, 0, 2, 2, 2)));
    }

    // ── A MESMA palavra decodifica igual em A32 e T32 (encoding compartilhado) ──

    @Test
    void sameWordDecodesIdenticallyInArmAndThumb() {
        int[] words = {
                vdot(true, 0, 2, 4), vdot(false, 3, 4, 5), vmmla(6, 8, 10),
                vfma(false, 0, 2, 4), vfma(true, 6, 8, 10),
                vdotScal(true, 0, 2, 2, 1), vfmaScal(false, 0, 2, 2, 3),
        };
        for (int w : words) {
            DecodedInstruction arm = decodeArm(w);
            DecodedInstruction thumb = decodeThumb(w);
            assertEquals(arm.kind(), thumb.kind());
            assertEquals(InstructionKind.LIFTED_IR_OP, arm.kind());
            assertEquals(liftSingleOp(arm), liftSingleOp(thumb));
        }
    }

    // ── Execução: VDOT_b16 acumula pares bf16 em f32 (2 lanes, forma D) ──

    @Test
    void executesDotProductAccumulatingPairs() {
        ArmCore core = newCore();
        setD16(core, 2, 1.0f, 2.0f, 0.0f, 0.0f); // Vn = D2: par0 = (1.0, 2.0)
        setD16(core, 4, 3.0f, 4.0f, 0.0f, 0.0f); // Vm = D4: par0 = (3.0, 4.0)
        setF32(core, 0, 10.0f, 0.0f); // Vd = D0, acumulador pré-preenchido
        run(core, vdot(false, 0, 2, 4)); // VDOT d0, d2, d4
        // 10.0 + (1.0*3.0 + 2.0*4.0) = 10.0 + 11.0 = 21.0
        assertEquals(21.0f, f32(core, 0, 0));
    }

    // ── Execução: VDOT_b16 forma Q, 4 lanes ──

    @Test
    void executesDotProductQuadFormWithFourLanes() {
        ArmCore core = newCore();
        setD16(core, 2, 1.0f, 1.0f, 2.0f, 2.0f); // Vn = D2 (lanes 0-1)
        setD16(core, 3, 3.0f, 3.0f, 4.0f, 4.0f); // Vn = D3 (lanes 2-3, = Q1 par)
        setD16(core, 4, 1.0f, 1.0f, 1.0f, 1.0f); // Vm = D4
        setD16(core, 5, 1.0f, 1.0f, 1.0f, 1.0f); // Vm = D5
        setF32(core, 0, 0.0f, 0.0f);
        setF32(core, 1, 0.0f, 0.0f);
        run(core, vdot(true, 0, 2, 4)); // VDOT q0, q1, q2
        assertEquals(2.0f, f32(core, 0, 0));  // 1.0*1.0 + 1.0*1.0
        assertEquals(4.0f, f32(core, 0, 1));  // 2.0*1.0 + 2.0*1.0
        assertEquals(6.0f, f32(core, 1, 0));  // 3.0*1.0 + 3.0*1.0
        assertEquals(8.0f, f32(core, 1, 1));  // 4.0*1.0 + 4.0*1.0
    }

    // ── Execução: VDOT_b16_scal, `b` fixo (par `index` de `Vm`), replicado ──

    @Test
    void dotProductScalarReplicatesTheFixedPair() {
        ArmCore core = newCore();
        setD16(core, 1, 1.0f, 2.0f, 3.0f, 4.0f);  // Vn = D1: par0=(1.0,2.0), par1=(3.0,4.0)
        setD16(core, 2, 100.0f, 5.0f, 6.0f, 7.0f); // Vm = D2: par0=(100.0,5.0), par1=(6.0,7.0)
        setF32(core, 0, 0.0f, 0.0f); // Vd = D0
        run(core, vdotScal(false, 0, 1, 2, 1)); // VDOT d0, d1, d2[1] -> b fixo = par1 = (6.0,7.0)
        // lane0 usa Vn par0 (1.0,2.0): 1.0*6.0 + 2.0*7.0 = 20.0
        assertEquals(20.0f, f32(core, 0, 0));
    }

    // ── Execução: VMMLA_b16, matriz ASSIMÉTRICA conhecida ──

    @Test
    void executesAsymmetricMatrixMultiplyElementByElement() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L);
        core.vfp().setD(1, 0L);
        // Vn: linha0 (D2) = [1,0,0,0], linha1 (D3) = [0,1,0,0]
        setD16(core, 2, 1.0f, 0.0f, 0.0f, 0.0f);
        setD16(core, 3, 0.0f, 1.0f, 0.0f, 0.0f);
        // Vm: coluna0 (D4) = [10,0,0,0], coluna1 (D5) = [0,20,0,0]
        setD16(core, 4, 10.0f, 0.0f, 0.0f, 0.0f);
        setD16(core, 5, 0.0f, 20.0f, 0.0f, 0.0f);
        run(core, vmmla(0, 2, 4)); // VMMLA q0, q1, q2
        // linha0·coluna0=10, linha0·coluna1=0, linha1·coluna0=0, linha1·coluna1=20.
        assertEquals(10.0f, f32(core, 0, 0));
        assertEquals(0.0f, f32(core, 0, 1));
        assertEquals(0.0f, f32(core, 1, 0));
        assertEquals(20.0f, f32(core, 1, 1));
    }

    @Test
    void matrixMultiplyAccumulatesIntoThePrefilledDestination() {
        ArmCore core = newCore();
        setF32(core, 0, 100.0f, 0.0f); // acumulador lane0 pré-preenchido
        core.vfp().setD(1, 0L);
        setD16(core, 2, 2.0f, 0.0f, 0.0f, 0.0f);
        core.vfp().setD(3, 0L);
        setD16(core, 4, 3.0f, 0.0f, 0.0f, 0.0f);
        core.vfp().setD(5, 0L);
        run(core, vmmla(0, 2, 4));
        assertEquals(100.0f + 2.0f * 3.0f, f32(core, 0, 0));
    }

    // ── Execução: VFMA_b16 (`VFMAB`), top=false seleciona elementos PARES (0,2,4,6) ──

    @Test
    void vfmabSelectsEvenElements() {
        ArmCore core = newCore();
        // Vn = Q1 (D2/D3): elementos 0..7 = 1,2,3,4,5,6,7,8 (pares = índices 0,2,4,6 = 1,3,5,7)
        setD16(core, 2, 1.0f, 2.0f, 3.0f, 4.0f);
        setD16(core, 3, 5.0f, 6.0f, 7.0f, 8.0f);
        // Vm = Q2 (D4/D5): todos os elementos = 10
        setD16(core, 4, 10.0f, 10.0f, 10.0f, 10.0f);
        setD16(core, 5, 10.0f, 10.0f, 10.0f, 10.0f);
        setF32(core, 0, 0.0f, 0.0f);
        setF32(core, 1, 0.0f, 0.0f);
        run(core, vfma(false, 0, 2, 4)); // VFMAB q0, q1, q2
        // lane e usa elemento par 2e: e=0->elem0=1, e=1->elem2=3, e=2->elem4=5, e=3->elem6=7.
        assertEquals(10.0f, f32(core, 0, 0));
        assertEquals(30.0f, f32(core, 0, 1));
        assertEquals(50.0f, f32(core, 1, 0));
        assertEquals(70.0f, f32(core, 1, 1));
    }

    // ── Execução: VFMA_b16 (`VFMAT`), top=true seleciona elementos ÍMPARES (1,3,5,7) ──

    @Test
    void vfmatSelectsOddElements() {
        ArmCore core = newCore();
        setD16(core, 2, 1.0f, 2.0f, 3.0f, 4.0f);
        setD16(core, 3, 5.0f, 6.0f, 7.0f, 8.0f);
        setD16(core, 4, 10.0f, 10.0f, 10.0f, 10.0f);
        setD16(core, 5, 10.0f, 10.0f, 10.0f, 10.0f);
        setF32(core, 0, 0.0f, 0.0f);
        setF32(core, 1, 0.0f, 0.0f);
        run(core, vfma(true, 0, 2, 4)); // VFMAT q0, q1, q2
        // lane e usa elemento ímpar 2e+1: e=0->elem1=2, e=1->elem3=4, e=2->elem5=6, e=3->elem7=8.
        assertEquals(20.0f, f32(core, 0, 0));
        assertEquals(40.0f, f32(core, 0, 1));
        assertEquals(60.0f, f32(core, 1, 0));
        assertEquals(80.0f, f32(core, 1, 1));
    }

    // ── Execução: `Vd == Vn` (aliasing, E10) — largura mista, destino f32 sobre fonte bf16 ──

    @Test
    void aliasingDestinationWithSourceIsSafe() {
        ArmCore core = newCore();
        setD16(core, 0, 1.0f, 2.0f, 3.0f, 4.0f); // Vn = Vd = Q0 (D0/D1)
        setD16(core, 1, 5.0f, 6.0f, 7.0f, 8.0f);
        setD16(core, 4, 10.0f, 10.0f, 10.0f, 10.0f); // Vm = Q2
        setD16(core, 5, 10.0f, 10.0f, 10.0f, 10.0f);
        float acc0 = f32(core, 0, 0);
        float acc1 = f32(core, 0, 1);
        run(core, vfma(false, 0, 0, 4)); // VFMAB q0, q0, q2 (Vd=Vn=Q0)
        assertEquals(acc0 + 10.0f, f32(core, 0, 0));
        assertEquals(acc1 + 30.0f, f32(core, 0, 1));
    }

    // ── Execução: VFMA_b16_scal, `b` fixo (elemento `index` de `Vm`, restrito a D0-D7) ──

    @Test
    void fusedMultiplyAddLongScalarReplicatesTheFixedElement() {
        ArmCore core = newCore();
        setD16(core, 2, 1.0f, 2.0f, 3.0f, 4.0f); // Vn = Q1 (D2/D3)
        setD16(core, 3, 5.0f, 6.0f, 7.0f, 8.0f);
        setD16(core, 7, 0.0f, 0.0f, 0.0f, 9.0f); // D7 (vm): index3 = 9.0
        setF32(core, 0, 0.0f, 0.0f);
        setF32(core, 1, 0.0f, 0.0f);
        run(core, vfmaScal(false, 0, 2, 7, 3)); // VFMAB q0, q1, d7[3] -> b fixo = 9.0
        // top=false: lane e usa elemento par 2e de Vn: 1,3,5,7.
        assertEquals(1.0f * 9.0f, f32(core, 0, 0));
        assertEquals(3.0f * 9.0f, f32(core, 0, 1));
        assertEquals(5.0f * 9.0f, f32(core, 1, 0));
        assertEquals(7.0f * 9.0f, f32(core, 1, 1));
    }
}
