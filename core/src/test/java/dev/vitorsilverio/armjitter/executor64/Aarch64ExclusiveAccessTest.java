package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `LDXR`/`LDAXR`/`STXR`/`STLXR` (B6.3.4) e o `Aarch64ExclusiveMonitor` novo — espelha os vetores
/// de {@code ArmV6ExclusiveAccessTest} (o precedente de 32 bits, B1.4): sucesso/falha do
/// `STXR`/`STLXR`, tamanho incompatível, loop de incremento atômico, `LDAXR`/`STLXR` como NOP de
/// barreira observável e a integração de `notifyOrdinaryWrite` com `STR` comum (a auditoria da
/// Especificação #2 da task — sem essa chamada este teste falha). Palavras codificadas à mão a
/// partir do formato `@stxr` verificado byte a byte contra `aarch64-none-elf-as`/`objdump`
/// (devkitA64) em {@code Aarch64DecoderCorpusTest} — ver `corpus.s`/`corpus.objdump.txt`.
class Aarch64ExclusiveAccessTest {
    /// `sz` (bits31:30, mesma codificação de `Ir64MemSize`/`SINGLE_SIZE_*` do decoder): `00`=byte,
    /// `01`=half, `10`=word, `11`=doubleword.
    private static final int SIZE_BYTE = 0b00;
    private static final int SIZE_HALF = 0b01;
    private static final int SIZE_WORD = 0b10;
    private static final int SIZE_DOUBLEWORD = 0b11;
    /// `Rt2`/`Rs` fixos (`XZR`) na forma não-par que esta task decodifica — ver a task b6.3.4.
    private static final int FIXED_XZR_FIELD = 0b11111;
    private static final int FORM_STXR = 0b000;
    private static final int FORM_LDXR = 0b010;

    private static int encodeExclusive(int sizeField, int form, int rs, boolean lasr, int rn, int rt) {
        return (sizeField << 30) | (0b001000 << 24) | (form << 21) | (rs << 16)
                | ((lasr ? 1 : 0) << 15) | (FIXED_XZR_FIELD << 10) | (rn << 5) | rt;
    }

    private static int ldxr(int sizeField, int rt, int rn) {
        return encodeExclusive(sizeField, FORM_LDXR, FIXED_XZR_FIELD, false, rn, rt);
    }

    private static int ldaxr(int sizeField, int rt, int rn) {
        return encodeExclusive(sizeField, FORM_LDXR, FIXED_XZR_FIELD, true, rn, rt);
    }

    private static int stxr(int sizeField, int rs, int rt, int rn) {
        return encodeExclusive(sizeField, FORM_STXR, rs, false, rn, rt);
    }

    private static int stlxr(int sizeField, int rs, int rt, int rn) {
        return encodeExclusive(sizeField, FORM_STXR, rs, true, rn, rt);
    }

    /// `STR` (unsigned offset, doubleword) — mesmo encoding real do corpus (`str x4, [x5, #16]` =
    /// `0xf90008a4`), parametrizado para o teste de `notifyOrdinaryWrite`.
    private static int strXUnsignedOffset(int rt, int rn, int imm12) {
        return 0xf9000000 | (imm12 << 10) | (rn << 5) | rt;
    }

    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    private static void run(Aarch64Core core, int... instructions) {
        long base = core.pc();
        for (int i = 0; i < instructions.length; i++) {
            core.memory().write32(base + i * 4L, instructions[i]);
        }
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        for (int i = 0; i < instructions.length; i++) {
            executor.step(core);
        }
    }

    // ── Sucesso / falha básicos ──────────────────────────────────────────────────

    @Test
    void ldxrThenStxrToSameAddressSucceeds() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10); // base
        core.setX(2, 0xCAFEBABEL); // valor a armazenar
        core.memory().write64(0x10, 0x1111111111111111L);
        run(core, ldxr(SIZE_DOUBLEWORD, 1, 0), stxr(SIZE_DOUBLEWORD, 3, 2, 0));

        assertEquals(0, core.x(3), "Rs de status deve ser 0 (sucesso)");
        assertEquals(0xCAFEBABEL, core.memory().read64(0x10), "memória deve ter sido escrita");
    }

    @Test
    void stxrWithoutPriorLdxrFailsAndLeavesMemoryIntact() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0xDEADBEEFL);
        core.memory().write64(0x10, 0x1111111111111111L);
        run(core, stxr(SIZE_DOUBLEWORD, 3, 2, 0));

        assertEquals(1, core.x(3), "Rs de status deve ser 1 (falha)");
        assertEquals(0x1111111111111111L, core.memory().read64(0x10), "memória NÃO pode ter sido tocada");
    }

    @Test
    void ldxrLoadsTheCurrentMemoryValue() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.memory().write64(0x10, 0x123456789ABCDEF0L);
        run(core, ldxr(SIZE_DOUBLEWORD, 1, 0));

        assertEquals(0x123456789ABCDEF0L, core.x(1));
    }

    // ── Tamanho incompatível ─────────────────────────────────────────────────────

    @Test
    void ldxrWordThenStxrByteAtSameAddressFails() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0xFF);
        core.memory().write32(0x10, 0x11111111);
        run(core, ldxr(SIZE_WORD, 1, 0), stxr(SIZE_BYTE, 3, 2, 0));

        assertEquals(1, core.x(3), "tamanhos diferentes devem falhar");
        assertEquals(0x11111111, core.memory().read32(0x10));
    }

    // ── Gating de tamanho (byte/half/word/doubleword) ───────────────────────────

    @Test
    void byteSizeRoundTripsThroughTheMonitor() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0xAB);
        core.memory().write8(0x10, 0x11);
        run(core, ldxr(SIZE_BYTE, 1, 0), stxr(SIZE_BYTE, 3, 2, 0));

        assertEquals(0, core.x(3));
        assertEquals(0xAB, core.memory().read8(0x10) & 0xFF);
    }

    @Test
    void halfSizeRoundTripsThroughTheMonitor() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0xABCD);
        core.memory().write16(0x10, 0x1111);
        run(core, ldxr(SIZE_HALF, 1, 0), stxr(SIZE_HALF, 3, 2, 0));

        assertEquals(0, core.x(3));
        assertEquals(0xABCD, core.memory().read16(0x10) & 0xFFFF);
    }

    @Test
    void wordSizeRoundTripsThroughTheMonitor() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0xABCDEF01L);
        core.memory().write32(0x10, 0x11111111);
        run(core, ldxr(SIZE_WORD, 1, 0), stxr(SIZE_WORD, 3, 2, 0));

        assertEquals(0, core.x(3));
        assertEquals(0xABCDEF01L, Integer.toUnsignedLong(core.memory().read32(0x10)));
    }

    @Test
    void doublewordSizeRoundTripsThroughTheMonitor() {
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0xABCDEF0123456789L);
        core.memory().write64(0x10, 0x1111111111111111L);
        run(core, ldxr(SIZE_DOUBLEWORD, 1, 0), stxr(SIZE_DOUBLEWORD, 3, 2, 0));

        assertEquals(0, core.x(3));
        assertEquals(0xABCDEF0123456789L, core.memory().read64(0x10));
    }

    // ── Loop funcional de incremento atômico ────────────────────────────────────

    @Test
    void atomicIncrementLoopConverges() {
        // ldxr x1,[x0]; add x1,x1,x3 (x3=1, forma SHIFTED REGISTER); stxr w2,x1,[x0]; sem
        // contenção, converge em 1 iteração. Usa a forma "shifted register" (não a forma
        // "immediate") deliberadamente: ADD/SUB imediato tem o bug pendente #7 registrado em
        // tasks/README.md (executeAlu lê `core.sp()` incondicionalmente quando
        // src1IsStackPointer=true, e o DECODER seta esse campo `true` sempre para ADD/SUB
        // imediato — inclusive quando Rn != 31 — então operand1 vira SP em vez de X1, fora do
        // escopo desta task). A forma shifted-register não tem esse problema (Rn/Rd nunca são SP
        // nela, `executeAluShiftedRegister` lê `Rn` sempre por índice normal).
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(3, 1L); // incremento, somado via ADD (shifted register)
        core.memory().write64(0x10, 41L);
        core.memory().write32(0, ldxr(SIZE_DOUBLEWORD, 1, 0));
        // add x1, x1, x3, lsl #0 (ADD shifted register): 0x8b030021.
        core.memory().write32(4, 0x8b030021);
        core.memory().write32(8, stxr(SIZE_DOUBLEWORD, 2, 1, 0));

        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.run(core, 3);

        assertEquals(0, core.x(2), "STXR deve suceder sem contenção");
        assertEquals(42L, core.memory().read64(0x10));
    }

    // ── LDAXR/STLXR: NOP de barreira observável, mesmo resultado que LDXR/STXR ─────

    @Test
    void ldaxrAndStlxrBehaveIdenticallyToLdxrAndStxr() {
        Aarch64Core plain = newCore(64);
        plain.setX(0, 0x10);
        plain.setX(2, 0xCAFEBABEL);
        plain.memory().write64(0x10, 0x1111111111111111L);
        run(plain, ldxr(SIZE_DOUBLEWORD, 1, 0), stxr(SIZE_DOUBLEWORD, 3, 2, 0));

        Aarch64Core ordered = newCore(64);
        ordered.setX(0, 0x10);
        ordered.setX(2, 0xCAFEBABEL);
        ordered.memory().write64(0x10, 0x1111111111111111L);
        run(ordered, ldaxr(SIZE_DOUBLEWORD, 1, 0), stlxr(SIZE_DOUBLEWORD, 3, 2, 0));

        assertEquals(plain.x(1), ordered.x(1), "LDAXR deve carregar o mesmo valor que LDXR");
        assertEquals(plain.x(3), ordered.x(3), "STLXR deve produzir o mesmo status que STXR");
        assertEquals(plain.memory().read64(0x10), ordered.memory().read64(0x10),
                "STLXR deve escrever o mesmo valor de memória que STXR");
    }

    // ── Escrita comum (STR) abre o monitor ──────────────────────────────────────

    @Test
    void ordinaryStoreOverlappingPendingReservationOpensTheMonitor() {
        // ldxr x1,[x0]; str x2,[x0] (escrita comum sobrepondo a reserva); stxr w3,x4,[x0] deve
        // falhar — auditoria da Especificação #2 da task: sem a chamada a notifyOrdinaryWrite em
        // Ir64BlockExecutor#executeStore, este teste falharia (o STXR sucederia indevidamente).
        Aarch64Core core = newCore(64);
        core.setX(0, 0x10);
        core.setX(2, 0x2222222222222222L);
        core.setX(4, 0x3333333333333333L);
        core.memory().write64(0x10, 0x1111111111111111L);
        run(core,
                ldxr(SIZE_DOUBLEWORD, 1, 0),
                strXUnsignedOffset(2, 0, 0), // str x2, [x0]
                stxr(SIZE_DOUBLEWORD, 3, 4, 0));

        assertEquals(1, core.x(3), "STXR apos escrita comum sobrepondo a reserva deve falhar");
        assertEquals(0x2222222222222222L, core.memory().read64(0x10),
                "memória deve ter só o valor do STR comum, não do STXR");
    }
}
