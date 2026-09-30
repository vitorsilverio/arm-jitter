package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.24 — SVE2 Crypto Extensions: `AESE`/`AESD`/`AESMC`/`AESIMC`/`SM4E`/`SM4EKEY`/`RAX1` (7 encodings).
///
/// As 7 palavras foram medidas byte a byte contra `aarch64-none-elf-as
/// -march=armv9.5-a+sve2+sve-aes+sve2-sm4+sve2-sha3` real (devkitA64 disponível nesta sessão), não montadas só
/// pela transcrição do `.decode` — ver `## Resultado` da task para o comando exato e a saída do `objdump`.
///
/// A prova de reuso (Aceite): cada teste roda a forma SVE (`VL=128`) e a forma A64 escalar EQUIVALENTE sobre o
/// MESMO conteúdo inicial e compara os 128 bits resultado a resultado — nenhuma tabela criptográfica nova, nenhuma
/// fórmula reimplementada.
class SveCryptoOpsTest {
    private static final int Z0 = 0;
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int Z4 = 4;
    private static final int Z5 = 5;
    /// `SVCR.SM` (bit 0) — modo streaming ligado.
    private static final long STREAMING_MODE_BIT = 1L;

    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_0_A,
            "teste-SVE2", Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE_AES = Aarch64Architecture.extending(SVE2, "teste-SVE-AES",
            Aarch64Feature.SVE_AES);
    private static final Aarch64Architecture SVE_SM4 = Aarch64Architecture.extending(SVE2, "teste-SVE-SM4",
            Aarch64Feature.SVE_SM4);
    private static final Aarch64Architecture SVE_SHA3 = Aarch64Architecture.extending(SVE2, "teste-SVE-SHA3",
            Aarch64Feature.SVE_SHA3);
    private static final Aarch64Architecture SVE_AES_SME = Aarch64Architecture.extending(SVE_AES, "teste-SVE-AES-SME",
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION);

    // ── Palavras reais, medidas contra `aarch64-none-elf-as` (ver javadoc da classe) ────────────────
    // `aesmc z4.b, z4.b`
    private static final int WORD_AESMC = 0x4520E004;
    // `aesimc z4.b, z4.b`
    private static final int WORD_AESIMC = 0x4520E404;
    // `aese z4.b, z4.b, z5.b`
    private static final int WORD_AESE = 0x4522E0A4;
    // `aesd z4.b, z4.b, z5.b`
    private static final int WORD_AESD = 0x4522E4A4;
    // `sm4e z4.s, z4.s, z5.s`
    private static final int WORD_SM4E = 0x4523E0A4;
    // `sm4ekey z4.s, z5.s, z6.s`
    private static final int WORD_SM4EKEY = 0x4526F0A4;
    // `rax1 z4.d, z5.d, z6.d`
    private static final int WORD_RAX1 = 0x4526F4A4;

    private static final long VBAR = 0x400L;

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    /// `CPACR_EL1.ZEN = 0`: nega o acesso SVE (`sveAccessCheck` toma a exceção síncrona em vez de liberar).
    private static final class DenyingCpacr implements Aarch64SystemRegisterBus {
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

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static void setZQ(Aarch64Core core, int reg, long lo, long hi) {
        core.scalable().setZWord(reg, 0, lo);
        core.scalable().setZWord(reg, 1, hi);
    }

    // ── Decode: aceita com a feature certa, recusa sem ───────────────────────────────────────────

    @Test
    void decodeAcceptsAllSevenUnderTheirFeatures() {
        assertNotNull(decodeOrNull(SVE_AES, WORD_AESMC));
        assertNotNull(decodeOrNull(SVE_AES, WORD_AESIMC));
        assertNotNull(decodeOrNull(SVE_AES, WORD_AESE));
        assertNotNull(decodeOrNull(SVE_AES, WORD_AESD));
        assertNotNull(decodeOrNull(SVE_SM4, WORD_SM4E));
        assertNotNull(decodeOrNull(SVE_SM4, WORD_SM4EKEY));
        assertNotNull(decodeOrNull(SVE_SHA3, WORD_RAX1));
    }

    @Test
    void decodeRejectsWithoutTheFeature() {
        assertNull(decodeOrNull(SVE2, WORD_AESMC));
        assertNull(decodeOrNull(SVE2, WORD_AESE));
        assertNull(decodeOrNull(SVE2, WORD_SM4E));
        assertNull(decodeOrNull(SVE2, WORD_SM4EKEY));
        assertNull(decodeOrNull(SVE2, WORD_RAX1));
        // AES não libera SM4/SHA3, e vice-versa (features separadas, Achado 2 da task).
        assertNull(decodeOrNull(SVE_AES, WORD_SM4E));
        assertNull(decodeOrNull(SVE_SM4, WORD_AESE));
    }

    // ── Cross-test com o A64 escalar (a prova de reuso) ──────────────────────────────────────────

    @Test
    void aeseMatchesScalarA64AtVl128() {
        Aarch64Core sve = core(SVE_AES, 128);
        setZQ(sve, Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        setZQ(sve, Z1, 0x0F0F0F0F0F0F0F0FL, 0xF0F0F0F0F0F0F0F0L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESE, Z0, Z1, 0));

        Aarch64Core scalar = core(SVE_AES, 128);
        scalar.fp().setQ(Z2, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        scalar.fp().setQ(Z3, 0x0F0F0F0F0F0F0F0FL, 0xF0F0F0F0F0F0F0F0L);
        new Ir64BlockExecutor().executeOp(scalar, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESE, Z2, Z3));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z0));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z0));
    }

    @Test
    void aesdMatchesScalarA64AtVl128() {
        Aarch64Core sve = core(SVE_AES, 128);
        setZQ(sve, Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        setZQ(sve, Z1, 0x0F0F0F0F0F0F0F0FL, 0xF0F0F0F0F0F0F0F0L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESD, Z0, Z1, 0));

        Aarch64Core scalar = core(SVE_AES, 128);
        scalar.fp().setQ(Z2, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        scalar.fp().setQ(Z3, 0x0F0F0F0F0F0F0F0FL, 0xF0F0F0F0F0F0F0F0L);
        new Ir64BlockExecutor().executeOp(scalar, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESD, Z2, Z3));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z0));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z0));
    }

    /// `AESMC`/`AESIMC` não têm operando de origem separado (Achado 3 da task): o decoder resolve `rn = rd`. Este
    /// teste confirma que o resultado bate com o escalar `AESMC Vd, Vn` chamado com `Vd == Vn` — a mesma
    /// auto-referência.
    @Test
    void aesmcHasNoSeparateSourceOperand() {
        Aarch64Core sve = core(SVE_AES, 128);
        setZQ(sve, Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESMC, Z0, Z0, 0));

        Aarch64Core scalar = core(SVE_AES, 128);
        scalar.fp().setQ(Z2, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        new Ir64BlockExecutor().executeOp(scalar, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESMC, Z2, Z2));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z0));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z0));
    }

    @Test
    void aesimcMatchesScalarA64AtVl128() {
        Aarch64Core sve = core(SVE_AES, 128);
        setZQ(sve, Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESIMC, Z0, Z0, 0));

        Aarch64Core scalar = core(SVE_AES, 128);
        scalar.fp().setQ(Z2, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        new Ir64BlockExecutor().executeOp(scalar, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESIMC, Z2, Z2));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z0));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z0));
    }

    @Test
    void sm4eMatchesScalarA64AtVl128() {
        Aarch64Core sve = core(SVE_SM4, 128);
        setZQ(sve, Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        setZQ(sve, Z1, 0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoSm4Encrypt(Z0, Z1, 0));

        Aarch64Core scalar = core(SVE_SM4, 128);
        scalar.fp().setQ(Z2, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        scalar.fp().setQ(Z3, 0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        new Ir64BlockExecutor().executeOp(scalar, new Ir64Op.CryptoSm4Encrypt(Z2, Z3));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z0));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z0));
    }

    @Test
    void sm4ekeyMatchesScalarA64AtVl128() {
        Aarch64Core sve = core(SVE_SM4, 128);
        setZQ(sve, Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        setZQ(sve, Z1, 0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoSm4KeyUpdate(Z2, Z0, Z1, 0));

        Aarch64Core scalar = core(SVE_SM4, 128);
        scalar.fp().setQ(Z0, 0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        scalar.fp().setQ(Z1, 0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        new Ir64BlockExecutor().executeOp(scalar, new Ir64Op.CryptoSm4KeyUpdate(Z2, Z0, Z1));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z2));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z2));
    }

    @Test
    void rax1MatchesScalarA64AtVl128() {
        Aarch64Core sve = core(SVE_SHA3, 128);
        setZQ(sve, Z0, 0x1111111111111111L, 0x2222222222222222L);
        setZQ(sve, Z1, 0x3333333333333333L, 0x4444444444444444L);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoRax1(Z2, Z0, Z1, 0));

        Aarch64Core scalar = core(SVE_SHA3, 128);
        scalar.fp().setQ(Z0, 0x1111111111111111L, 0x2222222222222222L);
        scalar.fp().setQ(Z1, 0x3333333333333333L, 0x4444444444444444L);
        new Ir64BlockExecutor().executeOp(scalar,
                new Ir64Op.CryptoSha3TwoSourceRotate(Ir64CryptoSha3Op.RAX1, Z2, Z0, Z1, 0));

        assertEquals(scalar.fp().low64(Z2), sve.fp().low64(Z2));
        assertEquals(scalar.fp().high64(Z2), sve.fp().high64(Z2));
    }

    // ── VL >= 256: cada segmento de 128 bits processado independentemente ────────────────────────

    @Test
    void aeseProcessesEachSegmentIndependentlyAtVl256() {
        Aarch64Core sve = core(SVE_AES, 256);
        long block1Lo = 0x1122334455667788L;
        long block1Hi = 0x99AABBCCDDEEFF00L;
        long block2Lo = 0xAABBCCDDEEFF0011L;
        long block2Hi = 0x2233445566778899L;
        sve.scalable().setZWord(Z0, 0, block1Lo);
        sve.scalable().setZWord(Z0, 1, block1Hi);
        sve.scalable().setZWord(Z0, 2, block2Lo);
        sve.scalable().setZWord(Z0, 3, block2Hi);
        long keyLo = 0x0F0F0F0F0F0F0F0FL;
        long keyHi = 0xF0F0F0F0F0F0F0F0L;
        sve.scalable().setZWord(Z1, 0, keyLo);
        sve.scalable().setZWord(Z1, 1, keyHi);
        sve.scalable().setZWord(Z1, 2, keyLo);
        sve.scalable().setZWord(Z1, 3, keyHi);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESE, Z0, Z1, 0));

        Aarch64Core scalarBlock1 = core(SVE_AES, 128);
        scalarBlock1.fp().setQ(Z2, block1Lo, block1Hi);
        scalarBlock1.fp().setQ(Z3, keyLo, keyHi);
        new Ir64BlockExecutor().executeOp(scalarBlock1, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESE, Z2, Z3));

        Aarch64Core scalarBlock2 = core(SVE_AES, 128);
        scalarBlock2.fp().setQ(Z2, block2Lo, block2Hi);
        scalarBlock2.fp().setQ(Z3, keyLo, keyHi);
        new Ir64BlockExecutor().executeOp(scalarBlock2, new Ir64Op.CryptoAes(Ir64CryptoAesOp.AESE, Z2, Z3));

        assertEquals(scalarBlock1.fp().low64(Z2), sve.scalable().zWord(Z0, 0));
        assertEquals(scalarBlock1.fp().high64(Z2), sve.scalable().zWord(Z0, 1));
        assertEquals(scalarBlock2.fp().low64(Z2), sve.scalable().zWord(Z0, 2));
        assertEquals(scalarBlock2.fp().high64(Z2), sve.scalable().zWord(Z0, 3));
    }

    @Test
    void sm4eProcessesEachSegmentIndependentlyAtVl256() {
        Aarch64Core sve = core(SVE_SM4, 256);
        long block1Lo = 0x1122334455667788L;
        long block1Hi = 0x99AABBCCDDEEFF00L;
        long block2Lo = 0xAABBCCDDEEFF0011L;
        long block2Hi = 0x2233445566778899L;
        sve.scalable().setZWord(Z0, 0, block1Lo);
        sve.scalable().setZWord(Z0, 1, block1Hi);
        sve.scalable().setZWord(Z0, 2, block2Lo);
        sve.scalable().setZWord(Z0, 3, block2Hi);
        long keyLo = 0x0102030405060708L;
        long keyHi = 0x090A0B0C0D0E0F10L;
        sve.scalable().setZWord(Z1, 0, keyLo);
        sve.scalable().setZWord(Z1, 1, keyHi);
        sve.scalable().setZWord(Z1, 2, keyLo);
        sve.scalable().setZWord(Z1, 3, keyHi);
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoSm4Encrypt(Z0, Z1, 0));

        Aarch64Core scalarBlock1 = core(SVE_SM4, 128);
        scalarBlock1.fp().setQ(Z2, block1Lo, block1Hi);
        scalarBlock1.fp().setQ(Z3, keyLo, keyHi);
        new Ir64BlockExecutor().executeOp(scalarBlock1, new Ir64Op.CryptoSm4Encrypt(Z2, Z3));

        Aarch64Core scalarBlock2 = core(SVE_SM4, 128);
        scalarBlock2.fp().setQ(Z2, block2Lo, block2Hi);
        scalarBlock2.fp().setQ(Z3, keyLo, keyHi);
        new Ir64BlockExecutor().executeOp(scalarBlock2, new Ir64Op.CryptoSm4Encrypt(Z2, Z3));

        assertEquals(scalarBlock1.fp().low64(Z2), sve.scalable().zWord(Z0, 0));
        assertEquals(scalarBlock1.fp().high64(Z2), sve.scalable().zWord(Z0, 1));
        assertEquals(scalarBlock2.fp().low64(Z2), sve.scalable().zWord(Z0, 2));
        assertEquals(scalarBlock2.fp().high64(Z2), sve.scalable().zWord(Z0, 3));
    }

    /// `RAX1` opera em doubleword por TODA a largura de `VL`, sem segmentação (Achado 4 da task): com `VL=256`
    /// (4 elementos de 64 bits), cada elemento bate com a fórmula `n XOR rotateLeft(m, 1)` isolada — não com o
    /// escalar de 128 bits, que só processa os 2 primeiros.
    @Test
    void rax1OperatesElementByElementAtVl256() {
        Aarch64Core sve = core(SVE_SHA3, 256);
        long[] n = {0x1111111111111111L, 0x2222222222222222L, 0x3333333333333333L, 0x4444444444444444L};
        long[] m = {0x5555555555555555L, 0x6666666666666666L, 0x7777777777777777L, 0x8888888888888888L};
        for (int i = 0; i < 4; i++) {
            sve.scalable().setZWord(Z0, i, n[i]);
            sve.scalable().setZWord(Z1, i, m[i]);
        }
        new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoRax1(Z2, Z0, Z1, 0));

        for (int i = 0; i < 4; i++) {
            long expected = n[i] ^ Long.rotateLeft(m[i], 1);
            assertEquals(expected, sve.scalable().zWord(Z2, i), "elemento " + i);
        }
    }

    // ── Acesso negado (`CPACR_EL1.ZEN = 0`): a exceção é tomada, não executa ─────────────────────

    @Test
    void aesTrapsWithSveAccessExceptionWhenCpacrDeniesIt() {
        Aarch64Core sve = core(SVE_AES, 128);
        sve.setSystemRegisterBus(new DenyingCpacr());
        boolean trapped = new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESE, Z0, Z1, 0));
        assertTrue(trapped);
    }

    @Test
    void sm4eTrapsWithSveAccessExceptionWhenCpacrDeniesIt() {
        Aarch64Core sve = core(SVE_SM4, 128);
        sve.setSystemRegisterBus(new DenyingCpacr());
        boolean trapped = new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoSm4Encrypt(Z0, Z1, 0));
        assertTrue(trapped);
    }

    @Test
    void sm4ekeyTrapsWithSveAccessExceptionWhenCpacrDeniesIt() {
        Aarch64Core sve = core(SVE_SM4, 128);
        sve.setSystemRegisterBus(new DenyingCpacr());
        boolean trapped =
                new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoSm4KeyUpdate(Z2, Z0, Z1, 0));
        assertTrue(trapped);
    }

    @Test
    void rax1TrapsWithSveAccessExceptionWhenCpacrDeniesIt() {
        Aarch64Core sve = core(SVE_SHA3, 128);
        sve.setSystemRegisterBus(new DenyingCpacr());
        boolean trapped = new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoRax1(Z2, Z0, Z1, 0));
        assertTrue(trapped);
    }

    // ── Modo streaming: recusado (pendência nomeada, ver javadoc de SveCryptoOps) ────────────────

    @Test
    void aeseIsUndefinedInStreamingMode() {
        Aarch64Core sve = core(SVE_AES_SME, 128);
        sve.setSvcr(STREAMING_MODE_BIT);
        setZQ(sve, Z0, 1L, 1L);
        setZQ(sve, Z1, 1L, 1L);
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException.class,
                () -> new Ir64BlockExecutor().executeOp(sve, new Ir64Op.SveCryptoAes(Ir64CryptoAesOp.AESE, Z0, Z1, 0)));
    }
}
