package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B2.1 — infra de fetch/gate Thumb-2 (`ArmFeature#THUMB2`): reconhecimento de candidatos de
/// 32 bits (hw1[15:11] em {0b11101, 0b11110, 0b11111}) e UNDEFINED controlado quando nenhuma
/// extensão de B2.2+ está registrada ainda. **B2.6**: `BL`/`BLX` imediato agora decodifica como
/// instrução única de 32 bits (`LONG_BRANCH_32`) sob `THUMB2` — os testes de regressão do par
/// legado abaixo foram atualizados para essa semântica (ver `b2.6-thumb2-preset-fechamento.md`);
/// os dois testes de reprodução do "fantasma" ficam no fim do arquivo.
class ThumbTwoDecoderTest {
    private static final ArmArchitecture THUMB2_ARCH =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K, "ArmJitterTest-Thumb2-B2.1", ArmFeature.THUMB2);

    @Test
    void blImmediateDecodesAsSingle32BitInstructionUnderThumb2() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // hw1: S=0, imm10=0 -> highOffset=0
        memory.put16(2, 0xF801); // hw2: BL (bit14=1), imm11=1 -> lowOffset=2
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.LONG_BRANCH_32, instruction.kind());
        assertEquals(0xF000F801, instruction.raw());
        assertTrue(instruction.link());
    }

    @Test
    void blImmediateEndToEndMatchesLegacyTwoHalfwordPairFinalState() {
        // B2.6, Passo 3: revisão da semântica observável, não da forma da instrução — a mesma
        // BL, executada pelo caminho legado (dois halfwords, dois `core.step()`) numa arquitetura
        // sem THUMB2, e pelo decode único de 32 bits (um `core.step()`) sob THUMB2, deve produzir
        // exatamente o mesmo LR e o mesmo PC final.
        int prefixHi = 0xF000;
        int suffixLo = 0xF801;

        ArmCore legacyCore = newCore(ArmArchitecture.ARMV6K);
        put16(legacyCore, 0, prefixHi);
        put16(legacyCore, 2, suffixLo);
        legacyCore.step();
        legacyCore.step();

        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        put16(thumb2Core, 0, prefixHi);
        put16(thumb2Core, 2, suffixLo);
        thumb2Core.step();

        assertEquals(legacyCore.register(14), thumb2Core.register(14));
        assertEquals(legacyCore.programCounter(), thumb2Core.programCounter());
        assertEquals(legacyCore.cpsr().isThumbMode(), thumb2Core.cpsr().isThumbMode());
    }

    @Test
    void blxImmediateEndToEndMatchesLegacyTwoHalfwordPairFinalState() {
        int prefixHi = 0xF000;
        int suffixLo = 0xE801; // H=01 -> BLX (bit14=0), imm11=1 -> lowOffset=2

        ArmCore legacyCore = newCore(ArmArchitecture.ARMV6K);
        put16(legacyCore, 0, prefixHi);
        put16(legacyCore, 2, suffixLo);
        legacyCore.step();
        legacyCore.step();

        ArmCore thumb2Core = newCore(THUMB2_ARCH);
        put16(thumb2Core, 0, prefixHi);
        put16(thumb2Core, 2, suffixLo);
        thumb2Core.step();

        assertEquals(legacyCore.register(14), thumb2Core.register(14));
        assertEquals(legacyCore.programCounter(), thumb2Core.programCounter());
        assertEquals(legacyCore.cpsr().isThumbMode(), thumb2Core.cpsr().isThumbMode());
        assertFalse(thumb2Core.cpsr().isThumbMode(), "BLX troca para ARM");
    }

    @Test
    void genuineThumb2DataProcessingImmediateCandidateIsUndefinedWithoutExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // hw1[15:11] = 0b11110
        memory.put16(2, 0x0000); // hw2[15] = 0 -> Data processing: immediate (not BL/BLX)
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertEquals(InstructionSet.THUMB, instruction.instructionSet());
        assertEquals(0, instruction.address());
        assertEquals(0xF0000000, instruction.raw());
    }

    @Test
    void genuineThumb2BranchWithoutLinkCandidateIsUndefinedWithoutExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // hw1[15:11] = 0b11110
        memory.put16(2, 0x8000); // hw2[15:14] = 0b10 -> Branches/misc, not "with link"
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertEquals(0xF0008000, instruction.raw());
    }

    @Test
    void isolatedSuffixCandidateIsUndefinedUnderThumb2NoLongerDelegatesToLegacy() {
        // B2.6 §2: chamar decode() DIRETAMENTE no endereço de um sufixo (top5=0b11111, formato de
        // BL) sob THUMB2 não delega mais ao caminho legado — vira UNDEFINED controlado. Em código
        // são isso nunca acontece mais (o lifter nunca chama decode() nesse endereço, ver o teste
        // de reprodução do fantasma abaixo); este teste documenta o comportamento de fronteira.
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(2, 0xF801);
        memory.put16(4, 0x0000);
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction instruction = decoder.decode(memory, 2);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void presetsWithoutThumb2DecodeSameCandidateAsLegacyPrefixNoRegression() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0x0000);

        for (ArmArchitecture architecture : new ArmArchitecture[] {
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV6K}) {
            assertFalse(architecture.has(ArmFeature.THUMB2), architecture.name());
            DecodedInstruction instruction = new ThumbDecoder(architecture).decode(memory, 0);

            assertEquals(InstructionKind.LONG_BRANCH_PREFIX, instruction.kind(), architecture.name());
            assertEquals(0, instruction.immediate(), architecture.name());
            assertEquals(0xF000, instruction.raw(), architecture.name());
        }
    }

    // ── Passo 1 da spec: reprodução do bug do "fantasma" BL/BLX — devem falhar contra o decode
    // de dois halfwords independentes (comportamento pré-B2.6 quando as extensões colidentes
    // estão plugadas) e passar com o decode único de 32 bits (pós-fix). ─────────────────────────

    @Test
    void ghostBlSuffixIsNotSwallowedByLoadStoreExtension() {
        // Sufixo BL 0xF801 tem top8=0xF8 — exatamente o prefixo `SINGLE_UNSIGNED_TOP8` que
        // `Thumb2LoadStoreDecoder` reivindica para LDR/STR T3. Pré-B2.6, decodificar o endereço 2
        // isoladamente (como o lifter fazia, avançando 2 bytes por halfword) formava um "fantasma"
        // de 32 bits com o halfword de dados em 4 e o Thumb2LoadStoreDecoder o engolia como um
        // LOAD genuíno. Com o preset real de 4 extensões (`ARMV6K_THUMB2`) e o decode único de
        // B2.6, o endereço 0 decodifica o par INTEIRO como uma instrução de 4 bytes — o endereço 2
        // nunca é visitado independentemente, e os halfwords de "dados" em 4/6 nunca são tocados.
        ArmCore core = newCore(ArmArchitecture.ARMV6K_THUMB2);
        put16(core, 0, 0xF000); // prefix: highOffset=0
        put16(core, 2, 0xF801); // suffix: BL, lowOffset=2 -- também bate com SINGLE_UNSIGNED_TOP8
        put16(core, 4, 0x0000); // "dados" que seriam o resto do load fantasma
        put16(core, 6, 0x0000);

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K_THUMB2).decode(core.memory(), 0);
        assertEquals(InstructionKind.LONG_BRANCH_32, instruction.kind(),
                "o sufixo BL não deve ser reivindicado como LOAD pelo Thumb2LoadStoreDecoder");

        core.step();

        assertEquals(5, core.register(14), "LR = (endereço do sufixo + 2) | 1");
        assertEquals(6, core.programCounter(), "PC = LR-antigo(4) + lowOffset(2)");
        assertTrue(core.cpsr().isThumbMode(), "BL simples não troca de modo");
    }

    @Test
    void ghostBlxSuffixIsNotSwallowedByDataProcessingExtension() {
        // Sufixo BLX 0xEA01 tem hi[15:9]=0b1110101 -- exatamente `REGISTER_FORM_PREFIX` de
        // `Thumb2DataProcessingDecoder` (já presente desde B2.2, ANTES desta task). Esta colisão
        // já era real no preset de produção antigo (2 extensões), só nunca exercitada por teste
        // algum -- documentado explicitamente no Contexto de b2.6-thumb2-preset-fechamento.md.
        ArmCore core = newCore(ArmArchitecture.ARMV6K_THUMB2);
        put16(core, 0, 0xF000); // prefix: highOffset=0
        put16(core, 2, 0xEA01); // suffix: BLX, lowOffset=2 -- também bate com REGISTER_FORM_PREFIX
        put16(core, 4, 0x0000);
        put16(core, 6, 0x0000);

        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV6K_THUMB2).decode(core.memory(), 0);
        assertEquals(InstructionKind.LONG_BRANCH_32, instruction.kind(),
                "o sufixo BLX não deve ser reivindicado como data-processing register-form");

        core.step();

        assertEquals(5, core.register(14), "LR = (endereço do sufixo + 2) | 1");
        assertFalse(core.cpsr().isThumbMode(), "BLX troca para ARM");
    }

    private static ArmCore newCore(ArmArchitecture architecture) {
        ArmCore core = new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), architecture);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void put16(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value);
    }
}
