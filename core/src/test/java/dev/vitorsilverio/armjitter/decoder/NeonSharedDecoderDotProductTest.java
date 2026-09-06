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

/// `neon-shared.decode` — `VSDOT`/`VUDOT`/`VUSDOT`/`VSDOT_scalar`/`VUDOT_scalar`/`VUSDOT_scalar`/
/// `VSUDOT_scalar` (task B13.18, `FEAT_DotProd`/`FEAT_I8MM`) → {@link IrOp.NeonDotProduct}/
/// {@link IrOp.NeonDotProductByElement} → execução pelo núcleo vetorial COMPARTILHADO
/// ({@code AdvSimdLanes.dotProduct}/`dotProductByElement`).
///
/// Encodings golden conferidos com `arm-none-eabi-as -march=armv8.2-a+i8mm -mfpu=neon-fp-armv8
/// .arch_extension dotprod` (devkitARM) — ver `## Resultado` da task para o log.
class NeonSharedDecoderDotProductTest {
    /// Arquitetura com AS DUAS features — usada para a maioria dos testes de decode/execução.
    private static final ArmArchitecture BOTH_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonDotProductBoth",
                    ArmFeature.DOT_PRODUCT, ArmFeature.INT8_MATRIX_MULTIPLY, ArmFeature.VFPV3_D32,
                    ArmFeature.THUMB2);

    private static final ArmArchitecture BOTH_ARCH =
            BOTH_FEATURES.withDecoderExtensions(neonSharedFirst(BOTH_FEATURES))
                    .withThumb32DecoderExtensions(thumbNeonSharedFirst(BOTH_FEATURES));

    /// Arquitetura com SÓ `DOT_PRODUCT` (sem `INT8_MATRIX_MULTIPLY`) — prova o gate independente:
    /// `VSDOT`/`VUDOT` decodificam, `VUSDOT`/`VSUDOT` continuam `UNIMPLEMENTED`.
    private static final ArmArchitecture DOT_PRODUCT_ONLY_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ArmV7-TestNeonDotProductOnly",
                    ArmFeature.DOT_PRODUCT);

    private static final ArmArchitecture DOT_PRODUCT_ONLY_ARCH =
            DOT_PRODUCT_ONLY_FEATURES.withDecoderExtensions(neonSharedFirst(DOT_PRODUCT_ONLY_FEATURES));

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

    // ── Encoders (campos conferidos golden contra `arm-none-eabi-as`, ver o javadoc da classe) ──

    /// `VSDOT`/`VUDOT` (vetorial, família bits[24:23]=00): `signBit` `0`=`VSDOT`(assinado/assinado),
    /// `1`=`VUDOT`(sem-sinal/sem-sinal).
    private static int dotSameSignVector(int signBit, boolean quad, int vd, int vn, int vm) {
        return 0xFC20_0D00
                | (signBit << 4)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `VUSDOT` (vetorial, família bits[24:23]=01) — **não existe `VSUDOT` vetorial**.
    private static int vusdotVector(boolean quad, int vd, int vn, int vm) {
        return 0xFCA0_0D00
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `VSDOT_scalar`/`VUDOT_scalar` (família bit23=0, bits[21:20]=10): `vm` nibble DIRETO
    /// (`D0`-`D15`, sem bit de extensão).
    private static int dotSameSignScalar(int signBit, boolean quad, int vd, int vn, int vmNibble, int index) {
        return 0xFE20_0D00
                | (signBit << 4)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | (index << 5)
                | (vmNibble & 0xF);
    }

    /// `VUSDOT_scalar`/`VSUDOT_scalar` (família bit23=1, bits[21:20]=00): `signBit` `0`=`VUSDOT`
    /// (sem-sinal/assinado), `1`=`VSUDOT` (assinado/sem-sinal).
    private static int dotMixedScalar(int signBit, boolean quad, int vd, int vn, int vmNibble, int index) {
        return 0xFE80_0D00
                | (signBit << 4)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | (index << 5)
                | (vmNibble & 0xF);
    }

    private static DecodedInstruction decodeArm(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decodeArm(int word) {
        return decodeArm(BOTH_ARCH, word);
    }

    /// Decodifica o MESMO `raw32` como Thumb-2 (mesmo esquema de `NeonSharedDecoderTest`).
    private static DecodedInstruction decodeThumb(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, (word >>> 16) & 0xFFFF);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(BOTH_ARCH).decode(memory, 0);
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
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), BOTH_ARCH);
    }

    private static void run(ArmCore core, int word) {
        new IrBlockExecutor(BOTH_ARCH).executeOp(core, liftSingleOp(decodeArm(word)), 0);
    }

    /// Empacota até 4 bytes (índice `0` = menos significativo) numa ÚNICA lane de 32 bits (os
    /// operandos de byte do produto escalar).
    private static long lane32(int b0, int b1, int b2, int b3) {
        return (b0 & 0xFFL) | ((b1 & 0xFFL) << 8) | ((b2 & 0xFFL) << 16) | ((b3 & 0xFFL) << 24);
    }

    /// Empacota DUAS lanes de 32 bits distintas num `D` de 64 bits — para testar seleção de lane
    /// (`_scalar`, `index`) sem se importar com o conteúdo byte a byte de cada uma.
    private static long twoLanes32(int lane0, int lane1) {
        return (lane0 & 0xFFFF_FFFFL) | ((lane1 & 0xFFFF_FFFFL) << 32);
    }

    // ── Encoding golden (assembler real, `arm-none-eabi-as -march=armv8.2-a+i8mm`) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xFC22_0D44, dotSameSignVector(0, true, 0, 2, 4));   // vsdot.s8 q0,q1,q2
        assertEquals(0xFC22_0D54, dotSameSignVector(1, true, 0, 2, 4));   // vudot.u8 q0,q1,q2
        assertEquals(0xFC21_0D02, dotSameSignVector(0, false, 0, 1, 2)); // vsdot.s8 d0,d1,d2
        assertEquals(0xFC21_0D12, dotSameSignVector(1, false, 0, 1, 2)); // vudot.u8 d0,d1,d2
        assertEquals(0xFCA2_0D44, vusdotVector(true, 0, 2, 4));           // vusdot.s8 q0,q1,q2
        assertEquals(0xFCA1_0D02, vusdotVector(false, 0, 1, 2));         // vusdot.s8 d0,d1,d2
        assertEquals(0xFE22_0D44, dotSameSignScalar(0, true, 0, 2, 4, 0));  // vsdot.s8 q0,q1,d4[0]
        assertEquals(0xFE22_0D74, dotSameSignScalar(1, true, 0, 2, 4, 1));  // vudot.u8 q0,q1,d4[1]
        assertEquals(0xFE21_0D02, dotSameSignScalar(0, false, 0, 1, 2, 0)); // vsdot.s8 d0,d1,d2[0]
        assertEquals(0xFE21_0D32, dotSameSignScalar(1, false, 0, 1, 2, 1)); // vudot.u8 d0,d1,d2[1]
        assertEquals(0xFE82_0D44, dotMixedScalar(0, true, 0, 2, 4, 0));   // vusdot.s8 q0,q1,d4[0]
        assertEquals(0xFE81_0D22, dotMixedScalar(0, false, 0, 1, 2, 1)); // vusdot.s8 d0,d1,d2[1]
        assertEquals(0xFE82_0D54, dotMixedScalar(1, true, 0, 2, 4, 0));   // vsudot.u8 q0,q1,d4[0]
        assertEquals(0xFE81_0D32, dotMixedScalar(1, false, 0, 1, 2, 1)); // vsudot.u8 d0,d1,d2[1]
    }

    // ── Zero-diff: nenhum preset declara DOT_PRODUCT/INT8_MATRIX_MULTIPLY ──
    //
    // **Achado real**: `VUDOT_scalar`/`VSUDOT_scalar` (sinal=1 na forma `_scalar`) têm bits[27:24]
    // = `1110` e bit4 = `1` — a MESMA forma estrutural que `CoprocessorRegisterDecoder` (`MCR`/`MRC`,
    // anexado desde `ARMV4T`) já reivindica incondicionalmente para QUALQUER número de coprocessador,
    // e essa colisão é PRÉ-EXISTENTE (nada no B13.18 muda o comportamento desses dois raws quando a
    // feature está ausente — `CoprocessorRegisterDecoder` já rodava antes desta task e continua
    // rodando OUTRO decoder, não o `NeonSharedDecoder`). Sem a feature, esses dois casos decodificam
    // como `COPROCESSOR` (coprocessador 13, que nenhum `CoprocessorBus` real atende — mesmo raciocínio
    // do MRC2/CDP2 real para um coprocessador não implementado), não `UNIMPLEMENTED`; os outros 5
    // casos não colidem com nenhum decoder pré-existente e seguem `UNIMPLEMENTED`.
    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] unimplementedWords = {
                dotSameSignVector(0, true, 0, 2, 4),
                dotSameSignVector(1, true, 0, 2, 4),
                vusdotVector(true, 0, 2, 4),
                dotSameSignScalar(0, true, 0, 2, 4, 0),
                dotMixedScalar(0, true, 0, 2, 4, 0),
        };
        for (int w : unimplementedWords) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
        int[] preExistingCoprocessorCollisionWords = {
                dotSameSignScalar(1, true, 0, 2, 4, 1),  // VUDOT_scalar
                dotMixedScalar(1, true, 0, 2, 4, 0),     // VSUDOT_scalar
        };
        for (int w : preExistingCoprocessorCollisionWords) {
            assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.COPROCESSOR, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Gate independente: DOT_PRODUCT sem INT8_MATRIX_MULTIPLY aceita VSDOT/VUDOT e recusa
    // VUSDOT/VSUDOT — prova de que são DUAS features, não uma. ──

    @Test
    void dotProductAloneAcceptsSameSignButRejectsMixed() {
        assertEquals(InstructionKind.LIFTED_IR_OP,
                decodeArm(DOT_PRODUCT_ONLY_ARCH, dotSameSignVector(0, true, 0, 2, 4)).kind());
        assertEquals(InstructionKind.LIFTED_IR_OP,
                decodeArm(DOT_PRODUCT_ONLY_ARCH, dotSameSignVector(1, true, 0, 2, 4)).kind());
        assertEquals(InstructionKind.LIFTED_IR_OP,
                decodeArm(DOT_PRODUCT_ONLY_ARCH, dotSameSignScalar(0, true, 0, 2, 4, 0)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(DOT_PRODUCT_ONLY_ARCH, vusdotVector(true, 0, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(DOT_PRODUCT_ONLY_ARCH, dotMixedScalar(0, true, 0, 2, 4, 0)).kind());
        // VSUDOT_scalar (sinal=1) colide com o espaço PRÉ-EXISTENTE de `CoprocessorRegisterDecoder`
        // (ver o javadoc de `withoutTheFeatureEveryEncodingStaysUnimplemented`) — sem
        // `INT8_MATRIX_MULTIPLY`, o fallback que reivindica este raw NÃO é o `NeonSharedDecoder`.
        assertEquals(InstructionKind.COPROCESSOR,
                decodeArm(DOT_PRODUCT_ONLY_ARCH, dotMixedScalar(1, true, 0, 2, 4, 0)).kind());
    }

    // ── Espaço livre: siblings ainda sem dono (B13.19-B13.21) caem em UNIMPLEMENTED, não `null` ──

    @Test
    void unclaimedSiblingsStillFallThroughToUnimplemented() {
        // VDOT_b16: 1111 110 00 . 00 .... .... 1101 . q . 0 .... — bits[21:20]=00, não 10.
        int vdotB16 = 0xFC20_0D00 & ~(1 << 21);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vdotB16).kind());
    }

    // ── Decode: campos ──

    @Test
    void vectorFormDecodesSignsAndRegisters() {
        assertEquals(new IrOp.NeonDotProduct(true, true, true, 0, 2, 4),
                liftedOf(dotSameSignVector(0, true, 0, 2, 4)));   // VSDOT: assinado/assinado
        assertEquals(new IrOp.NeonDotProduct(false, false, true, 0, 2, 4),
                liftedOf(dotSameSignVector(1, true, 0, 2, 4)));   // VUDOT: sem-sinal/sem-sinal
        assertEquals(new IrOp.NeonDotProduct(false, true, false, 0, 1, 2),
                liftedOf(vusdotVector(false, 0, 1, 2)));          // VUSDOT: sem-sinal/assinado
    }

    @Test
    void scalarFormDecodesSignsIndexAndDirectVm() {
        assertEquals(new IrOp.NeonDotProductByElement(true, true, true, 0, 2, 4, 0),
                liftedOf(dotSameSignScalar(0, true, 0, 2, 4, 0)));  // VSDOT_scalar
        assertEquals(new IrOp.NeonDotProductByElement(false, false, true, 0, 2, 4, 1),
                liftedOf(dotSameSignScalar(1, true, 0, 2, 4, 1)));  // VUDOT_scalar
        assertEquals(new IrOp.NeonDotProductByElement(false, true, false, 0, 1, 2, 1),
                liftedOf(dotMixedScalar(0, false, 0, 1, 2, 1)));    // VUSDOT_scalar: sem-sinal/assinado
        assertEquals(new IrOp.NeonDotProductByElement(true, false, false, 0, 1, 2, 1),
                liftedOf(dotMixedScalar(1, false, 0, 1, 2, 1)));    // VSUDOT_scalar: assinado/sem-sinal
        // vm é nibble DIRETO: D15 é alcançável sem bit de extensão.
        assertEquals(new IrOp.NeonDotProductByElement(true, true, false, 0, 1, 15, 0),
                liftedOf(dotSameSignScalar(0, false, 0, 1, 15, 0)));
    }

    // ── Forma Q com índice ímpar é UNDEFINED (mesma disciplina do resto do arquivo) ──

    @Test
    void quadFormWithOddRegisterIsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(dotSameSignVector(0, true, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(dotSameSignVector(0, true, 0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vusdotVector(true, 0, 2, 5)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(dotSameSignScalar(0, true, 0, 1, 4, 0)).kind());
    }

    // ── A MESMA palavra decodifica igual em A32 e T32 (encoding compartilhado) ──

    @Test
    void sameWordDecodesIdenticallyInArmAndThumb() {
        int[] words = {
                dotSameSignVector(0, true, 0, 2, 4),
                dotSameSignVector(1, false, 0, 1, 2),
                vusdotVector(true, 0, 2, 4),
                dotSameSignScalar(0, true, 0, 2, 4, 0),
                dotMixedScalar(1, false, 0, 1, 2, 1),
        };
        for (int w : words) {
            DecodedInstruction arm = decodeArm(w);
            DecodedInstruction thumb = decodeThumb(w);
            assertEquals(arm.kind(), thumb.kind());
            assertEquals(InstructionKind.LIFTED_IR_OP, arm.kind());
            assertEquals(liftSingleOp(arm), liftSingleOp(thumb));
        }
    }

    // ── Execução: um byte com bit 7 setado produz QUATRO resultados diferentes (prova de que o
    // sinal é por operando, não por instrução como um todo) ──

    @Test
    void oneHighBitByteProducesFourDifferentResultsAcrossTheFourSignCombinations() {
        // Só o byte 0 de cada lane é não-zero: Vn=0x80 (-128 assinado/128 sem sinal),
        // Vm=0x81 (-127 assinado/129 sem sinal).
        long vnLane = lane32(0x80, 0, 0, 0);
        long vmLane = lane32(0x81, 0, 0, 0);

        ArmCore sdot = newCore();
        sdot.vfp().setD(0, 0L);
        sdot.vfp().setD(1, vnLane);
        sdot.vfp().setD(2, vmLane);
        run(sdot, dotSameSignVector(0, false, 0, 1, 2));
        assertEquals((-128) * (-127), (int) sdot.vfp().d(0)); // VSDOT: assinado*assinado = 16256

        ArmCore udot = newCore();
        udot.vfp().setD(0, 0L);
        udot.vfp().setD(1, vnLane);
        udot.vfp().setD(2, vmLane);
        run(udot, dotSameSignVector(1, false, 0, 1, 2));
        assertEquals(128 * 129, (int) udot.vfp().d(0)); // VUDOT: sem-sinal*sem-sinal = 16512

        ArmCore usdot = newCore();
        usdot.vfp().setD(0, 0L);
        usdot.vfp().setD(1, vnLane);
        usdot.vfp().setD(2, vmLane);
        run(usdot, vusdotVector(false, 0, 1, 2));
        assertEquals(128 * (-127), (int) usdot.vfp().d(0)); // VUSDOT: Vn sem-sinal*Vm assinado = -16256

        // VSUDOT só existe na forma `_scalar`: `Vn` na lane de `Vn`, `Vm` FIXO num `D` próprio
        // (`d3`, índice 0) com os MESMOS bytes de `vmLane`.
        ArmCore vsudot = newCore();
        vsudot.vfp().setD(0, 0L);
        vsudot.vfp().setD(1, vnLane); // vn
        vsudot.vfp().setD(3, vmLane); // vm = d3, índice 0
        run(vsudot, dotMixedScalar(1, false, 0, 1, 3, 0));
        assertEquals((-128) * 129, (int) vsudot.vfp().d(0)); // VSUDOT: Vn assinado*Vm sem-sinal = -16512

        // As quatro combinações produzem QUATRO resultados diferentes.
        assertEquals(16256, (int) sdot.vfp().d(0));
        assertEquals(16512, (int) udot.vfp().d(0));
        assertEquals(-16256, (int) usdot.vfp().d(0));
        assertEquals(-16512, (int) vsudot.vfp().d(0));
    }

    // ── Execução: acumula em Vd (pré-preenchido) ──

    @Test
    void vectorFormAccumulatesIntoThePrefilledDestination() {
        ArmCore core = newCore();
        core.vfp().setD(0, lane32(10, 0, 0, 0)); // acumulador pré-preenchido com 10 na lane 0
        core.vfp().setD(1, lane32(2, 0, 0, 0));  // Vn: só byte0=2
        core.vfp().setD(2, lane32(3, 0, 0, 0));  // Vm: só byte0=3
        run(core, dotSameSignVector(0, false, 0, 1, 2)); // VSDOT
        assertEquals(10 + 2 * 3, (int) core.vfp().d(0));
    }

    // ── Execução: overflow ACUMULA COM WRAP, nunca satura ──

    @Test
    void accumulationWrapsInsteadOfSaturating() {
        ArmCore core = newCore();
        core.vfp().setD(0, Integer.toUnsignedLong(0x7FFF_FFFF)); // acumulador = INT_MAX
        core.vfp().setD(1, lane32(0x7F, 0, 0, 0)); // Vn byte0 = 127
        core.vfp().setD(2, lane32(2, 0, 0, 0));    // Vm byte0 = 2 ⇒ produto = 254
        run(core, dotSameSignVector(0, false, 0, 1, 2)); // VSDOT
        // 0x7FFFFFFF + 254 = 0x800000FD — WRAP para negativo, não satura em 0x7FFFFFFF.
        assertEquals(0x8000_00FD, (int) core.vfp().d(0));
    }

    // ── Execução: as 4 lanes de uma soma completa (todos os 4 bytes contribuem) ──

    @Test
    void allFourBytesOfALaneContribute() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L);
        core.vfp().setD(1, lane32(1, 2, 3, 4));
        core.vfp().setD(2, lane32(10, 10, 10, 10));
        run(core, dotSameSignVector(0, false, 0, 1, 2)); // VSDOT
        assertEquals(1 * 10 + 2 * 10 + 3 * 10 + 4 * 10, (int) core.vfp().d(0));
    }

    // ── Execução: forma Q (2 lanes independentes) ──

    @Test
    void quadFormComputesTwoIndependentLanes() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L);
        core.vfp().setD(1, 0L);
        core.vfp().setD(2, lane32(1, 0, 0, 0));
        core.vfp().setD(3, lane32(2, 0, 0, 0));
        core.vfp().setD(4, lane32(5, 0, 0, 0));
        core.vfp().setD(5, lane32(7, 0, 0, 0));
        run(core, dotSameSignVector(0, true, 0, 2, 4)); // VSDOT q0,q1,q2
        assertEquals(1 * 5, (int) core.vfp().d(0));
        assertEquals(2 * 7, (int) core.vfp().d(1));
    }

    // ── Execução: VSDOT_scalar usa a lane FIXA de Vm, replicada para todas as lanes de Vn ──

    @Test
    void scalarFormReplicatesTheFixedElement() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L); // Vd = q0 (d0,d1)
        core.vfp().setD(1, 0L);
        core.vfp().setD(2, lane32(1, 0, 0, 0)); // Vn lane0 (d2)
        core.vfp().setD(3, lane32(2, 0, 0, 0)); // Vn lane1 (d3)
        core.vfp().setD(4, lane32(10, 0, 0, 0)); // Vm = d4, índice fixo 0
        run(core, dotSameSignScalar(0, true, 0, 2, 4, 0)); // VSDOT q0,q1,d4[0]
        assertEquals(1 * 10, (int) core.vfp().d(0));
        assertEquals(2 * 10, (int) core.vfp().d(1));
    }

    @Test
    void scalarFormSelectsTheIndexedElementOfVm() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0L);
        core.vfp().setD(1, lane32(3, 0, 0, 0)); // Vn
        core.vfp().setD(2, twoLanes32(10, 20)); // Vm: lane0=10, lane1=20 (d2)
        run(core, dotSameSignScalar(0, false, 0, 1, 2, 1)); // VSDOT d0,d1,d2[1] ⇒ usa lane1=20
        assertEquals(3 * 20, (int) core.vfp().d(0));
    }
}
