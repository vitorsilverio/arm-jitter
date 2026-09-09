package dev.vitorsilverio.armjitter.decoder;

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

/// `neon-shared.decode` — `VSMMLA`/`VUMMLA`/`VUSMMLA` (task B13.19, `FEAT_I8MM`, MESMA feature
/// que `VUSDOT`/`VSUDOT_scalar` da B13.18) → {@link IrOp.NeonMatrixMultiplyAccumulate} → execução
/// pelo núcleo vetorial COMPARTILHADO ({@code AdvSimdLanes.matrixMultiplyAccumulate}, criado pela
/// B19.12, a task irmã A64) — reusado sem nenhuma mudança.
///
/// Encodings golden conferidos com `arm-linux-gnueabihf-as -march=armv8.6-a+i8mm
/// -mfpu=neon-fp-armv8` (WSL Ubuntu, `arm-none-eabi-as` indisponível neste ambiente — mesmo
/// binutils/GNU assembler, mesma ISA real, encoding idêntico) — ver `## Resultado` da task para o
/// log completo (`vsmmla.s8 q0,q1,q2` → `0xFC220C44`, `vummla.u8 q0,q1,q2` → `0xFC220C54`,
/// `vusmmla.s8 q0,q1,q2` → `0xFCA20C44`, `vsmmla.s8 q3,q4,q5` → `0xFC286C4A`).
class NeonSharedDecoderMatrixMultiplyTest {
    /// Arquitetura com `INT8_MATRIX_MULTIPLY` — usada para todos os testes de decode/execução
    /// (a mesma feature que já gateia `VUSDOT`/`VUSDOT_scalar`/`VSUDOT_scalar` na B13.18).
    private static final ArmArchitecture FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonMatrixMultiply",
                    ArmFeature.INT8_MATRIX_MULTIPLY, ArmFeature.VFPV3_D32, ArmFeature.THUMB2);

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
    // classe) — todos SEMPRE 128 bits (sem bit `quad`, mesma disciplina do `.decode` real). ──

    private static final int SMMLA_VALUE = 0xFC20_0C40;
    private static final int UMMLA_VALUE = 0xFC20_0C50;
    private static final int USMMLA_VALUE = 0xFCA0_0C40;

    private static int matrixMultiply(int baseValue, int vd, int vn, int vm) {
        return baseValue
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    private static int vsmmla(int vd, int vn, int vm) {
        return matrixMultiply(SMMLA_VALUE, vd, vn, vm);
    }

    private static int vummla(int vd, int vn, int vm) {
        return matrixMultiply(UMMLA_VALUE, vd, vn, vm);
    }

    private static int vusmmla(int vd, int vn, int vm) {
        return matrixMultiply(USMMLA_VALUE, vd, vn, vm);
    }

    private static DecodedInstruction decodeArm(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decodeArm(int word) {
        return decodeArm(ARCH, word);
    }

    /// Decodifica o MESMO `raw32` como Thumb-2 (mesmo esquema de `NeonSharedDecoderDotProductTest`).
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

    /// Empacota até 8 bytes (índice `0` = menos significativo) num `D` de 64 bits — uma linha/
    /// coluna inteira da matriz `2x8`/`8x2`.
    private static long row(int... bytes) {
        long value = 0;
        for (int i = 0; i < bytes.length; i++) {
            value |= (bytes[i] & 0xFFL) << (i * 8);
        }
        return value;
    }

    // ── Encoding golden (assembler real, `arm-linux-gnueabihf-as -march=armv8.6-a+i8mm`) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xFC22_0C44, vsmmla(0, 2, 4));   // vsmmla.s8 q0,q1,q2
        assertEquals(0xFC22_0C54, vummla(0, 2, 4));   // vummla.u8 q0,q1,q2
        assertEquals(0xFCA2_0C44, vusmmla(0, 2, 4));  // vusmmla.s8 q0,q1,q2
        assertEquals(0xFC28_6C4A, vsmmla(6, 8, 10));  // vsmmla.s8 q3,q4,q5
    }

    // ── Zero-diff: nenhum preset declara INT8_MATRIX_MULTIPLY ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = { vsmmla(0, 2, 4), vummla(0, 2, 4), vusmmla(0, 2, 4) };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Espaço livre: siblings ainda sem dono (B13.20-B13.21) caem em UNIMPLEMENTED, não `null` ──

    @Test
    void unclaimedSiblingsStillFallThroughToUnimplemented() {
        // VMMLA_b16 (B13.21): mesmo prefixo, size(23:20)=0b1000 em vez de 0b0010/0b1010.
        int vmmlaB16 = (SMMLA_VALUE & ~0x0030_0000) | 0x0080_0000;
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vmmlaB16).kind());
    }

    // ── Decode: campos + sinal por operando ──

    @Test
    void decodesSignsAndRegisters() {
        assertEquals(new IrOp.NeonMatrixMultiplyAccumulate(true, true, 0, 2, 4),
                liftedOf(vsmmla(0, 2, 4)));   // VSMMLA: assinado/assinado
        assertEquals(new IrOp.NeonMatrixMultiplyAccumulate(false, false, 0, 2, 4),
                liftedOf(vummla(0, 2, 4)));   // VUMMLA: sem-sinal/sem-sinal
        assertEquals(new IrOp.NeonMatrixMultiplyAccumulate(false, true, 0, 2, 4),
                liftedOf(vusmmla(0, 2, 4))); // VUSMMLA: sem-sinal/assinado
        assertEquals(new IrOp.NeonMatrixMultiplyAccumulate(true, true, 6, 8, 10),
                liftedOf(vsmmla(6, 8, 10)));
    }

    // ── Não existe VSUMMLA: as três VALUEs conhecidas nunca colidem entre si ──

    @Test
    void thereIsNoFourthSignCombination() {
        assertEquals(InstructionKind.LIFTED_IR_OP, decodeArm(vsmmla(0, 2, 4)).kind());
        assertEquals(InstructionKind.LIFTED_IR_OP, decodeArm(vummla(0, 2, 4)).kind());
        assertEquals(InstructionKind.LIFTED_IR_OP, decodeArm(vusmmla(0, 2, 4)).kind());
    }

    // ── Índice de registrador ímpar (forma D inexistente) é UNDEFINED ──

    @Test
    void oddRegisterIndexIsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vsmmla(1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vsmmla(0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vsmmla(0, 2, 5)).kind());
    }

    // ── A MESMA palavra decodifica igual em A32 e T32 (encoding compartilhado) ──

    @Test
    void sameWordDecodesIdenticallyInArmAndThumb() {
        int[] words = { vsmmla(0, 2, 4), vummla(6, 8, 10), vusmmla(0, 2, 4) };
        for (int w : words) {
            DecodedInstruction arm = decodeArm(w);
            DecodedInstruction thumb = decodeThumb(w);
            assertEquals(arm.kind(), thumb.kind());
            assertEquals(InstructionKind.LIFTED_IR_OP, arm.kind());
            assertEquals(liftSingleOp(arm), liftSingleOp(thumb));
        }
    }

    // ── Execução: matriz ASSIMÉTRICA conhecida, conferida elemento a elemento ──
    //
    // Vn = 2 linhas de 8 bytes (linha0 = D2, linha1 = D3); Vm = 2 colunas de 8 bytes (coluna0 = D4,
    // coluna1 = D5). Vd[2*linha+coluna] += Σ ext(Vn[linha][k]) * ext(Vm[coluna][k]).
    @Test
    void executesAsymmetricMatrixMultiplyElementByElement() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L);
        core.vfp().setD(1, 0L);
        // Vn: linha0 = [1,0,0,0,0,0,0,0], linha1 = [0,1,0,0,0,0,0,0]
        core.vfp().setD(2, row(1, 0, 0, 0, 0, 0, 0, 0));
        core.vfp().setD(3, row(0, 1, 0, 0, 0, 0, 0, 0));
        // Vm: coluna0 = [10,0,...], coluna1 = [0,20,0,...]
        core.vfp().setD(4, row(10, 0, 0, 0, 0, 0, 0, 0));
        core.vfp().setD(5, row(0, 20, 0, 0, 0, 0, 0, 0));
        run(core, vsmmla(0, 2, 4)); // VSMMLA q0,q1,q2
        int[] words = { (int) core.vfp().d(0), (int) (core.vfp().d(0) >>> 32),
                (int) core.vfp().d(1), (int) (core.vfp().d(1) >>> 32) };
        // linha0 · coluna0 = 1*10 = 10; linha0 · coluna1 = 0; linha1 · coluna0 = 0; linha1 · coluna1 = 1*20 = 20.
        assertEquals(10, words[0]);
        assertEquals(0, words[1]);
        assertEquals(0, words[2]);
        assertEquals(20, words[3]);
    }

    @Test
    void ummlaAndUsmmlaProduceDifferentResultsThanSmmlaOnTheSameBits() {
        // Um byte com bit 7 setado: assinado=-128, sem-sinal=128.
        long nRow = row(0x80, 0, 0, 0, 0, 0, 0, 0);
        long mRow = row(0x81, 0, 0, 0, 0, 0, 0, 0);

        ArmCore smmla = newCore();
        smmla.vfp().setD(0, 0L); smmla.vfp().setD(1, 0L);
        smmla.vfp().setD(2, nRow); smmla.vfp().setD(3, 0L);
        smmla.vfp().setD(4, mRow); smmla.vfp().setD(5, 0L);
        run(smmla, vsmmla(0, 2, 4));
        assertEquals((-128) * (-127), (int) smmla.vfp().d(0)); // assinado*assinado = 16256

        ArmCore ummla = newCore();
        ummla.vfp().setD(0, 0L); ummla.vfp().setD(1, 0L);
        ummla.vfp().setD(2, nRow); ummla.vfp().setD(3, 0L);
        ummla.vfp().setD(4, mRow); ummla.vfp().setD(5, 0L);
        run(ummla, vummla(0, 2, 4));
        assertEquals(128 * 129, (int) ummla.vfp().d(0)); // sem-sinal*sem-sinal = 16512

        ArmCore usmmla = newCore();
        usmmla.vfp().setD(0, 0L); usmmla.vfp().setD(1, 0L);
        usmmla.vfp().setD(2, nRow); usmmla.vfp().setD(3, 0L);
        usmmla.vfp().setD(4, mRow); usmmla.vfp().setD(5, 0L);
        run(usmmla, vusmmla(0, 2, 4));
        assertEquals(128 * (-127), (int) usmmla.vfp().d(0)); // Vn sem-sinal*Vm assinado = -16256

        assertEquals(16256, (int) smmla.vfp().d(0));
        assertEquals(16512, (int) ummla.vfp().d(0));
        assertEquals(-16256, (int) usmmla.vfp().d(0));
    }

    // ── Execução: acumula em Vd (pré-preenchido) ──

    @Test
    void accumulatesIntoThePrefilledDestination() {
        ArmCore core = newCore();
        core.vfp().setD(0, 10L); // acumulador lane0 pré-preenchido com 10
        core.vfp().setD(1, 0L);
        core.vfp().setD(2, row(2, 0, 0, 0, 0, 0, 0, 0));
        core.vfp().setD(3, 0L);
        core.vfp().setD(4, row(3, 0, 0, 0, 0, 0, 0, 0));
        core.vfp().setD(5, 0L);
        run(core, vsmmla(0, 2, 4));
        assertEquals(10 + 2 * 3, (int) core.vfp().d(0));
    }

    // ── Execução: overflow ACUMULA COM WRAP, nunca satura ──

    @Test
    void accumulationWrapsInsteadOfSaturating() {
        ArmCore core = newCore();
        core.vfp().setD(0, Integer.toUnsignedLong(0x7FFF_FFFF)); // acumulador lane0 = INT_MAX
        core.vfp().setD(1, 0L);
        core.vfp().setD(2, row(0x7F, 0, 0, 0, 0, 0, 0, 0)); // linha0 byte0 = 127
        core.vfp().setD(3, 0L);
        core.vfp().setD(4, row(2, 0, 0, 0, 0, 0, 0, 0)); // coluna0 byte0 = 2 ⇒ produto = 254
        core.vfp().setD(5, 0L);
        run(core, vsmmla(0, 2, 4));
        // 0x7FFFFFFF + 254 = 0x800000FD — WRAP para negativo, não satura em 0x7FFFFFFF.
        assertEquals(0x8000_00FD, (int) core.vfp().d(0));
    }

    // ── Execução: todos os 8 bytes de uma linha/coluna contribuem ──

    @Test
    void allEightBytesOfARowContribute() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L);
        core.vfp().setD(1, 0L);
        core.vfp().setD(2, row(1, 2, 3, 4, 5, 6, 7, 8));
        core.vfp().setD(3, 0L);
        core.vfp().setD(4, row(1, 1, 1, 1, 1, 1, 1, 1));
        core.vfp().setD(5, 0L);
        run(core, vsmmla(0, 2, 4));
        assertEquals(1 + 2 + 3 + 4 + 5 + 6 + 7 + 8, (int) core.vfp().d(0));
    }
}
