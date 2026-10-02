package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Varredura determinística (semente fixa) dos três espaços NEON de 32 bits — `neon-dp` A32
/// (`1111 001x`), `neon-ls` (`1111 0100`) e o espelho T32 — contra {@link
/// ArmArchitecture#ARMV8_6A_32_NEON} (a única coluna com TODAS as features irmãs, B13.25).
///
/// O que ela garante, para cada palavra: (1) decodificar nunca lança; (2) o `raw` volta intacto;
/// (3) o que vira IR tem `liftedOp` não nulo; (4) sempre que o A32 vira IR, o T32 correspondente
/// (transformação mecânica da B13.16) produz o MESMO `IrOp`. Serve também para exercitar os ramos de
/// encodings RESERVADOS/não alocados (G8) que os testes dirigidos por instrução não tocam: é a
/// ferramenta que o JaCoCo pediu na B13.25 para os decoders `Neon*` (campos `size`/`opc`/registrador
/// ímpar inválidos) — sem ela, cada combinação inválida exigiria um teste de uma linha.
class NeonDecoderSweepTest {
    private static final ArmArchitecture ARCH = ArmArchitecture.ARMV8_6A_32_NEON;
    private static final int SAMPLES_PER_SPACE = 600_000;
    private static final long SEED = 0x13_25L;
    private static final int LOW_24_MASK = 0x00FF_FFFF;

    private static DecodedInstruction decodeArm(int word) {
        return decodeArm(ARCH, word);
    }

    private static DecodedInstruction decodeArm(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decodeThumb(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, (word >>> 16) & 0xFFFF);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(ARCH).decode(memory, 0);
    }

    /// Mesma transformação A32→T32 de `ArmV7aNeonPresetTest` (inverso do que `Thumb2NeonDecoder` desfaz).
    private static int dpToThumb(int a32) {
        int p = (a32 >>> 24) & 1;
        return 0xEF00_0000 | (p << 28) | (a32 & LOW_24_MASK);
    }

    private static int lsToThumb(int a32) {
        return 0xF900_0000 | (a32 & LOW_24_MASK);
    }

    private static void sweep(int highByteA, int highByteB, boolean loadStore) {
        SplittableRandom random = new SplittableRandom(SEED ^ highByteA);
        for (int i = 0; i < SAMPLES_PER_SPACE; i++) {
            int high = (i & 1) == 0 ? highByteA : highByteB;
            int word = (high << 24) | (random.nextInt() & LOW_24_MASK);
            DecodedInstruction a32 = decodeArm(word);
            assertNotNull(a32, Integer.toHexString(word));
            assertEquals(word, a32.raw(), Integer.toHexString(word));
            if (a32.kind() != InstructionKind.LIFTED_IR_OP) {
                continue;
            }
            assertNotNull(a32.liftedOp(), Integer.toHexString(word));
            int thumbWord = loadStore ? lsToThumb(word) : dpToThumb(word);
            DecodedInstruction t32 = decodeThumb(thumbWord);
            assertEquals(InstructionKind.LIFTED_IR_OP, t32.kind(), Integer.toHexString(word));
            assertEquals(a32.liftedOp(), t32.liftedOp(), Integer.toHexString(word));
        }
    }

    @Test
    void dataProcessingSpaceNeverThrowsAndThumbMirrorsArm() {
        sweep(0xF2, 0xF3, false);
    }

    @Test
    void loadStoreSpaceNeverThrowsAndThumbMirrorsArm() {
        sweep(0xF4, 0xF4, true);
    }

    @Test
    void sharedSpaceNeverThrows() {
        SplittableRandom random = new SplittableRandom(SEED);
        for (int i = 0; i < SAMPLES_PER_SPACE; i++) {
            int word = ((i & 1) == 0 ? 0xFC00_0000 : 0xFE00_0000) | (random.nextInt() & 0x01FF_FFFF);
            DecodedInstruction decoded = decodeArm(word);
            assertNotNull(decoded, Integer.toHexString(word));
            assertEquals(word, decoded.raw(), Integer.toHexString(word));
            assertEquals(Condition.AL, decoded.condition(), Integer.toHexString(word));
        }
    }

    /// Executa pelo `IrNeonExecutor` TUDO o que a varredura de dados/compartilhado decodifica como IR
    /// (sem tocar memória, por isso `neon-ls` fica de fora): exercita as formas D (64 bits) e Q
    /// (128 bits) de cada operação — os ramos `quad ? 16 : 8` que os testes dirigidos cobrem só de um
    /// lado. O invariante é "executar nunca lança" sobre um núcleo de registradores zerados.
    @Test
    void everyLiftedDataProcessingOpExecutesWithoutThrowing() {
        ArmCore core = new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), ARCH);
        IrBlockExecutor executor = new IrBlockExecutor(ARCH);
        SplittableRandom random = new SplittableRandom(SEED);
        int executed = 0;
        for (int i = 0; i < SAMPLES_PER_SPACE; i++) {
            int high = switch (i % 4) {
                case 0 -> 0xF2;
                case 1 -> 0xF3;
                case 2 -> 0xFC;
                default -> 0xFE;
            };
            int word = (high << 24) | (random.nextInt() & LOW_24_MASK);
            DecodedInstruction decoded = decodeArm(word);
            if (decoded.kind() != InstructionKind.LIFTED_IR_OP) {
                continue;
            }
            IrBlock.Builder block = IrBlock.builder(decoded.address());
            new StandardIrBuilder().lift(decoded, block);
            for (IrOp op : block.sealed().operations()) {
                executor.executeOp(core, op, 0);
            }
            executed++;
        }
        assertTrue(executed > 10_000, "a varredura precisa produzir IR suficiente: " + executed);
    }

    /// Mesmos espaços sob `ARMV7A_NEON` (NEON SEM RDM/cripto/FCMA/DotProd/I8MM/FHM/BF16): cobre os
    /// ramos "feature irmã ausente" — o encoding existe mas o preset não o declara, então tem que
    /// virar `UNIMPLEMENTED` ou outra coisa, nunca lançar (G8). Também executa os decoders sem
    /// `ADVANCED_SIMD` (`ARMV7A`), que têm de devolver `null`/recusar tudo.
    @Test
    void presetsWithoutSiblingFeaturesNeverThrow() {
        SplittableRandom random = new SplittableRandom(SEED + 1);
        for (int i = 0; i < SAMPLES_PER_SPACE; i++) {
            int high = switch (i % 5) {
                case 0 -> 0xF2;
                case 1 -> 0xF3;
                case 2 -> 0xF4;
                case 3 -> 0xFC;
                default -> 0xFE;
            };
            int word = (high << 24) | (random.nextInt() & LOW_24_MASK);
            for (ArmArchitecture architecture : new ArmArchitecture[] {
                    ArmArchitecture.ARMV7A_NEON, ArmArchitecture.ARMV7A}) {
                assertEquals(word, decodeArm(architecture, word).raw(), Integer.toHexString(word));
            }
        }
    }

    /// Decoders NEON ligados a um preset sem `ADVANCED_SIMD` devolvem `null` (deixam o próximo tentar).
    @Test
    void neonDecodersStayOutOfTheWayWithoutAdvancedSimd() {
        ArmArchitecture withoutNeon = ArmArchitecture.ARMV7A;
        assertNull(new NeonExtractTableDuplicateDecoder(withoutNeon).tryDecode(0xF2B1_0302, 0, Condition.AL));
    }
}
