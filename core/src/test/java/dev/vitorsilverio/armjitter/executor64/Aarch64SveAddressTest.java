package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B17.12 — endereçamento SVE: `ADDVL`/`ADDPL`/`RDVL` e as 4 formas de `ADR` vetorial. As palavras foram montadas com
/// `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1+sme`) a partir do TEXTO de cada linha; os valores
/// esperados vêm da fórmula do manual (`Xn + imm × VL/8`, `Xn + imm × VL/64`, `Zn[i] + (ext(Zm[i]) << msz)`) e são
/// conferidos em três `VL` — nesta task o `VL` é o próprio resultado.
class Aarch64SveAddressTest {
    private static final int[] VECTOR_LENGTHS = {128, 256, 512};
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final long INITIAL_SP = 0x10_0000L;

    private record Row(int word, String asm, Ir64Op.SveAddress.Op op, int rd, int rn, int rm, int imm, int msz) {
        Ir64Op.SveAddress expected() {
            return new Ir64Op.SveAddress(op, rd, rn, rm, imm, msz, 0L);
        }
    }

    private static Row row(int word, String asm, String op, int rd, int rn, int rm, int imm, int msz) {
        return new Row(word, asm, Ir64Op.SveAddress.Op.valueOf(op), rd, rn, rm, imm, msz);
    }

    private static Stream<Row> rows() {
        return Stream.of(
                row(0x04215780, "addvl x0, x1, #-4", "ADDVL", 0, 1, 0, -4, 0),
                row(0x043f579f, "addvl sp, sp, #-4", "ADDVL", 31, 31, 0, -4, 0),
                row(0x043f53e2, "addvl x2, sp, #31", "ADDVL", 2, 31, 0, 31, 0),
                row(0x04245403, "addvl x3, x4, #-32", "ADDVL", 3, 4, 0, -32, 0),
                row(0x046150a0, "addpl x0, x1, #5", "ADDPL", 0, 1, 0, 5, 0),
                row(0x047f57bf, "addpl sp, sp, #-3", "ADDPL", 31, 31, 0, -3, 0),
                row(0x047f5405, "addpl x5, sp, #-32", "ADDPL", 5, 31, 0, -32, 0),
                row(0x04bf5020, "rdvl x0, #1", "RDVL", 0, 0, 0, 1, 0),
                row(0x04bf57e1, "rdvl x1, #-1", "RDVL", 1, 0, 0, -1, 0),
                row(0x04bf53e2, "rdvl x2, #31", "RDVL", 2, 0, 0, 31, 0),
                row(0x04bf5403, "rdvl x3, #-32", "RDVL", 3, 0, 0, -32, 0),
                row(0x04bf501e, "rdvl x30, #0", "RDVL", 30, 0, 0, 0, 0),
                row(0x0422a020, "adr z0.d, [z1.d, z2.d, sxtw]", "ADR_S32", 0, 1, 2, 0, 0),
                row(0x0422a820, "adr z0.d, [z1.d, z2.d, sxtw #2]", "ADR_S32", 0, 1, 2, 0, 2),
                row(0x0465a483, "adr z3.d, [z4.d, z5.d, uxtw #1]", "ADR_U32", 3, 4, 5, 0, 1),
                row(0x0462ac20, "adr z0.d, [z1.d, z2.d, uxtw #3]", "ADR_U32", 0, 1, 2, 0, 3),
                row(0x04a2a020, "adr z0.s, [z1.s, z2.s]", "ADR_P32", 0, 1, 2, 0, 0),
                row(0x04a2ac20, "adr z0.s, [z1.s, z2.s, lsl #3]", "ADR_P32", 0, 1, 2, 0, 3),
                row(0x04e8a8e6, "adr z6.d, [z7.d, z8.d, lsl #2]", "ADR_P64", 6, 7, 8, 0, 2),
                row(0x04e2a020, "adr z0.d, [z1.d, z2.d]", "ADR_P64", 0, 1, 2, 0, 0));
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), SVE, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSp(INITIAL_SP);
        return core;
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(SVE).step(core);
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    private static void fill(Aarch64Core core, int reg, int esz, long... values) {
        for (int e = 0; e < values.length; e++) {
            SvePredicateOps.setElementOf(core.scalable(), reg, e, esz, values[e]);
        }
    }

    private static long get(Aarch64Core core, int reg, int esz, int index) {
        return SvePredicateOps.elementOf(core.scalable(), reg, index, esz);
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("rows")
    void everyEncodingDecodesToTheFieldsOfItsText(Row row) {
        assertEquals(row.expected(), decode(SVE, row.word()), row.asm());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x04215840, 0x04615840, 0x04bf5820})
    void theStreamingFormsAreRefusedUntilSme(int word) {
        // ADDSVL / ADDSPL / RDSVL usam SVL, não VL: decodificá-las como ADDVL daria o valor errado (G8).
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE, word));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void theGroupIsRefusedWithoutSve(Row row) {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_5_A, row.word()));
    }

    // ── ADDVL / ADDPL / RDVL ─────────────────────────────────────────────────────────────────────

    @Test
    void rdvlReturnsTheVectorLengthInBytesTimesTheSignedImmediate() {
        for (int vl : VECTOR_LENGTHS) {
            long bytes = vl / 8;
            Aarch64Core core = core(vl);
            run(core, 0x04bf5020); // rdvl x0, #1
            assertEquals(bytes, core.x(0), "VL=" + vl);
            run(core, 0x04bf57e1); // rdvl x1, #-1
            assertEquals(-bytes, core.x(1), "VL=" + vl);
            run(core, 0x04bf53e2); // rdvl x2, #31
            assertEquals(31 * bytes, core.x(2), "VL=" + vl);
            run(core, 0x04bf5403); // rdvl x3, #-32
            assertEquals(-32 * bytes, core.x(3), "VL=" + vl);
        }
    }

    @Test
    void rdvlWithRdEqual31DiscardsTheResultInsteadOfWritingSp() {
        Aarch64Core core = core(256);
        run(core, 0x04bf501f); // rdvl xzr, #0
        run(core, 0x04bf57ff); // rdvl xzr, #-1
        assertEquals(INITIAL_SP, core.sp(), "o destino de RDVL é XZR, nunca SP");
    }

    @Test
    void addvlOnTheStackPointerAllocatesAFrame() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(vl);
            run(core, 0x043f579f); // addvl sp, sp, #-4
            assertEquals(INITIAL_SP - 4L * (vl / 8), core.sp(), "VL=" + vl);
            run(core, 0x043f53e2); // addvl x2, sp, #31 — Xn = 31 é SP, não XZR
            assertEquals(INITIAL_SP - 4L * (vl / 8) + 31L * (vl / 8), core.x(2), "VL=" + vl);
            assertEquals(INITIAL_SP - 4L * (vl / 8), core.sp(), "ler SP como base não o altera");
        }
    }

    @Test
    void addvlOnGeneralRegistersAddsTheScaledSignedImmediate() {
        Aarch64Core core = core(512);
        core.setX(1, 1000L);
        run(core, 0x04215780); // addvl x0, x1, #-4 → 1000 - 4*64
        assertEquals(1000L - 256L, core.x(0));
        core.setX(4, 0L);
        run(core, 0x04245403); // addvl x3, x4, #-32
        assertEquals(-32L * 64L, core.x(3));
    }

    @Test
    void addplUsesThePredicateLengthNotTheVectorLength() {
        for (int vl : VECTOR_LENGTHS) {
            long predicateBytes = vl / 64;
            Aarch64Core core = core(vl);
            core.setX(1, 1000L);
            run(core, 0x046150a0); // addpl x0, x1, #5
            assertEquals(1000L + 5L * predicateBytes, core.x(0), "VL=" + vl);
            run(core, 0x047f57bf); // addpl sp, sp, #-3
            assertEquals(INITIAL_SP - 3L * predicateBytes, core.sp(), "VL=" + vl);
            run(core, 0x047f5405); // addpl x5, sp, #-32
            assertEquals(INITIAL_SP - 3L * predicateBytes - 32L * predicateBytes, core.x(5), "VL=" + vl);
        }
    }

    // ── ADR ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void adrSigned32ExtendsTheLow32BitsOfTheOffsetWithSign() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(vl);
            int elements = vl / 64;
            long[] base = new long[elements];
            long[] offset = new long[elements];
            for (int i = 0; i < elements; i++) {
                base[i] = 0x1000_0000_0000L + i;
                // bits altos sujos + bit 31 ligado: só o baixo de 32 conta, e conta com sinal.
                offset[i] = 0xDEAD_BEEF_8000_0000L + i;
            }
            fill(core, 1, 3, base);
            fill(core, 2, 3, offset);
            run(core, 0x0422a820); // adr z0.d, [z1.d, z2.d, sxtw #2]
            for (int i = 0; i < elements; i++) {
                long sext = (long) (int) (0x8000_0000L + i);
                assertEquals(base[i] + (sext << 2), get(core, 0, 3, i), "VL=" + vl + " elemento " + i);
            }
        }
    }

    @Test
    void adrUnsigned32ZeroExtendsTheLow32BitsOfTheOffset() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(vl);
            int elements = vl / 64;
            long[] base = new long[elements];
            long[] offset = new long[elements];
            for (int i = 0; i < elements; i++) {
                base[i] = 0x2000L * (i + 1);
                offset[i] = 0xDEAD_BEEF_FFFF_FFF0L + i;
            }
            fill(core, 4, 3, base);
            fill(core, 5, 3, offset);
            run(core, 0x0465a483); // adr z3.d, [z4.d, z5.d, uxtw #1]
            for (int i = 0; i < elements; i++) {
                long zext = 0xFFFF_FFF0L + i;
                assertEquals(base[i] + (zext << 1), get(core, 3, 3, i), "VL=" + vl + " elemento " + i);
            }
        }
    }

    @Test
    void adrPacked32AddsWordElementsAndWrapsAt32Bits() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(vl);
            int elements = vl / 32;
            long[] base = new long[elements];
            long[] offset = new long[elements];
            for (int i = 0; i < elements; i++) {
                base[i] = 0xFFFF_FF00L + i;
                offset[i] = 0x40L + i;
            }
            fill(core, 1, 2, base);
            fill(core, 2, 2, offset);
            run(core, 0x04a2ac20); // adr z0.s, [z1.s, z2.s, lsl #3]
            for (int i = 0; i < elements; i++) {
                assertEquals((base[i] + (offset[i] << 3)) & 0xFFFF_FFFFL, get(core, 0, 2, i),
                        "VL=" + vl + " elemento " + i);
            }
        }
    }

    @Test
    void adrPacked64UsesTheWholeDoublewordOffset() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(vl);
            int elements = vl / 64;
            long[] base = new long[elements];
            long[] offset = new long[elements];
            for (int i = 0; i < elements; i++) {
                base[i] = 0x1_0000_0000L * (i + 1);
                offset[i] = 0x1_0000_0001L + i;
            }
            fill(core, 7, 3, base);
            fill(core, 8, 3, offset);
            run(core, 0x04e8a8e6); // adr z6.d, [z7.d, z8.d, lsl #2]
            for (int i = 0; i < elements; i++) {
                assertEquals(base[i] + (offset[i] << 2), get(core, 6, 3, i), "VL=" + vl + " elemento " + i);
            }
        }
    }

    @Test
    void adrWithDestinationEqualToASourceReadsBeforeWriting() {
        Aarch64Core core = core(256);
        fill(core, 1, 3, 10L, 20L, 30L, 40L);
        fill(core, 2, 3, 1L, 2L, 3L, 4L);
        run(core, 0x04e2a022); // adr z2.d, [z1.d, z2.d] (rd = rm)
        assertEquals(11L, get(core, 2, 3, 0));
        assertEquals(22L, get(core, 2, 3, 1));
        assertEquals(33L, get(core, 2, 3, 2));
        assertEquals(44L, get(core, 2, 3, 3));
    }

    // ── Acesso negado ────────────────────────────────────────────────────────────────────────────

    private static final class Cpacr implements Aarch64SystemRegisterBus {
        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return 0L;
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long newValue) {
            throw new UnsupportedOperationException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x04215780, 0x046150a0, 0x04bf5020, 0x0422a020})
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(256);
        core.setSystemRegisterBus(new Cpacr());
        core.setX(0, 0x1234L);
        run(core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, core.x(0), "a instrução não executou");
    }
}
