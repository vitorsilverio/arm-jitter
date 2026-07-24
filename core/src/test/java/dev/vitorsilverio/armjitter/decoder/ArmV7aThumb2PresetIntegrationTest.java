package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B4.0.3: regressão de um bug real achado validando `ARMV7A` no armbox contra um binário
/// compilado por gcc de verdade (`hello-thumb2.c`, `-march=armv7-a -mthumb`, struct com
/// bitfields) — `UBFX`/`SBFX` em encoding **Thumb-2** viravam `UNDEFINED` sob o preset público
/// `ArmArchitecture#ARMV7A`, mesmo o preset tendo `ArmFeature.BIT_FIELD` habilitada, porque
/// `ARMV7A` construía `Thumb2DataProcessingDecoder`/`Thumb2RegisterDataProcessingDecoder`/
/// `Thumb2MultiplyDecoder` passando `ARMV6K_THUMB2_FEATURES` (sem `BIT_FIELD`/`BIT_REVERSE`/
/// `MLS_MULTIPLY`/`DIVIDE`) no construtor, em vez de `ARMV7A_FEATURES` — cada decoder guarda
/// essa `ArmArchitecture` para gatear feature em tempo de decode. O encoding ARM clássico
/// (`ArmDecoder`) nunca teve esse bug, porque usa a `architecture` real passada a cada
/// chamada, não uma cópia guardada no construtor. Diferente dos testes de
/// `Thumb2DataProcessingDecoderTest`/`Thumb2RegisterDataProcessingDecoderTest`/
/// `Thumb2MultiplyDivideDecoderTest` (que constroem seus próprios presets sintéticos com as
/// features certas e por isso não pegavam esse erro de fiação), este teste decodifica direto
/// contra o preset PÚBLICO `ArmArchitecture#ARMV7A`, como um binário real faria.
class ArmV7aThumb2PresetIntegrationTest {
    private static int plainBinaryHi(int i, int opNibble, int rn) {
        return (0b11110 << 11) | (i << 10) | (1 << 9) | (opNibble << 5) | rn;
    }

    private static int bitFieldLo(int imm3, int rd, int imm2, int widthMinusOneOrMsb) {
        return (imm3 << 12) | (rd << 8) | (imm2 << 6) | widthMinusOneOrMsb;
    }

    private static final int PLAIN_OP_UBFX = 0b1110;

    private static int rbitHi(int rn) {
        return 0xFA90 | rn;
    }

    private static int rbitLo(int rd, int rm) {
        return 0xF0A0 | (rd << 8) | rm; // op=0xA (RBIT), mesmo layout de Thumb2RegisterDataProcessingDecoderTest#twoSourceLo
    }

    private static int sdivHi(int rn) {
        return 0xFB90 | rn;
    }

    private static int sdivLo(int rd, int rm) {
        return 0xF0F0 | (rd << 8) | rm;
    }

    private static void put16(TestAddressSpace memory, int address, int value) {
        memory.put16(address, value);
    }

    /// Achado real (B4.0.3): antes do fix esta decodificação virava `InstructionKind.UNIMPLEMENTED`
    /// mesmo `ARMV7A` tendo `ArmFeature.BIT_FIELD`.
    @Test
    void ubfxDecodesUnderThePublicArmv7aPresetInThumb2Encoding() {
        TestAddressSpace memory = new TestAddressSpace(16);
        put16(memory, 0, plainBinaryHi(0, PLAIN_OP_UBFX, 1)); // UBFX r0, r1, #4, #8
        put16(memory, 2, bitFieldLo(0b001, 0, 0b00, 7));
        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);
        assertEquals(InstructionKind.BIT_FIELD_EXTRACT, instruction.kind());
    }

    @Test
    void ubfxExecutesEndToEndUnderThePublicArmv7aPreset() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV7A);
        core.cpsr().setThumbMode(true);
        core.setRegister(1, 0xABCD_1234);
        put16((TestAddressSpace) core.memory(), 0, plainBinaryHi(0, PLAIN_OP_UBFX, 1));
        put16((TestAddressSpace) core.memory(), 2, bitFieldLo(0b001, 0, 0b00, 7));
        core.step();
        assertEquals(0x23, core.register(0));
    }

    @Test
    void rbitDecodesUnderThePublicArmv7aPresetInThumb2Encoding() {
        TestAddressSpace memory = new TestAddressSpace(16);
        put16(memory, 0, rbitHi(1)); // RBIT r0, r1
        put16(memory, 2, rbitLo(0, 1));
        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);
        assertEquals(InstructionKind.BIT_REVERSE, instruction.kind());
        assertNotEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }

    @Test
    void sdivDecodesUnderThePublicArmv7aPresetInThumb2Encoding() {
        TestAddressSpace memory = new TestAddressSpace(16);
        put16(memory, 0, sdivHi(1)); // SDIV r0, r1, r1
        put16(memory, 2, sdivLo(0, 1));
        DecodedInstruction instruction = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);
        assertEquals(InstructionKind.DIVIDE, instruction.kind());
        assertNotEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }
}
