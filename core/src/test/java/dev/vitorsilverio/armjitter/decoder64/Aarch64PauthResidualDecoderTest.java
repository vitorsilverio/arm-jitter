package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64PointerAuthOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.15 — resíduo de `FEAT_PAuth` (Pointer Authentication): as 10 linhas nomeadas pela task
/// (`BRAZ`/`BLRAZ`/`RETA`/`BRA`/`BLRA`/`ERETA`/`LDRA`/`AUTDA`/`XPACI`/`XPACD`) + a auditoria de
/// vizinhos (`PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/`AUTDB`, MESMO bloco `@pacaut`,
/// incluídas por coesão — decisão registrada no `## Resultado` da task). Rota (b): identidade/
/// delegação para a contraparte não autenticada, sem núcleo de autenticação real (mesma disciplina
/// de `PACGA`, B19.6 bloco C). Vetores golden calculados com `aarch64-linux-gnu-as -march=armv8.3-a`
/// (WSL Ubuntu, binutils 2.46) + `objdump -d`, conferidos bit a bit contra o layout de campos do
/// `ARM DDI 0487 C6.2.*`.
class Aarch64PauthResidualDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder PAUTH_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);

    // ── Corpus golden (aarch64-linux-gnu-as -march=armv8.3-a) ──────────────────────────────────
    private static final int BRAAZ_X1 = 0xd61f083f;
    private static final int BRABZ_X1 = 0xd61f0c3f;
    private static final int BLRAAZ_X2 = 0xd63f085f;
    private static final int BLRABZ_X2 = 0xd63f0c5f;
    private static final int RETAA = 0xd65f0bff;
    private static final int RETAB = 0xd65f0fff;
    private static final int ERETAA = 0xd69f0bff;
    private static final int ERETAB = 0xd69f0fff;
    private static final int BRAA_X1_X2 = 0xd71f0822;
    private static final int BRAB_X1_X2 = 0xd71f0c22;
    private static final int BLRAA_X1_X2 = 0xd73f0822;
    private static final int BLRAB_X1_X2 = 0xd73f0c22;
    private static final int LDRAA_X0_X1 = 0xf8200420; // [x1], offset 0, sem writeback
    private static final int LDRAA_X0_X1_8 = 0xf8201420; // [x1, #8], sem writeback
    private static final int LDRAA_X0_X1_8_PRE = 0xf8201c20; // [x1, #8]!, writeback
    private static final int LDRAB_X0_X1_M8_PRE = 0xf8fffc20; // [x1, #-8]!, writeback, imediato negativo
    private static final int AUTDA_X0_X1 = 0xdac11820;
    private static final int AUTDB_X0_X1 = 0xdac11c20;
    private static final int AUTIA_X0_X1 = 0xdac11020;
    private static final int AUTIB_X0_X1 = 0xdac11420;
    private static final int PACIA_X0_X1 = 0xdac10020;
    private static final int PACIB_X0_X1 = 0xdac10420;
    private static final int PACDA_X0_X1 = 0xdac10820;
    private static final int PACDB_X0_X1 = 0xdac10c20;
    private static final int XPACI_X0 = 0xdac143e0;
    private static final int XPACD_X0 = 0xdac147e0;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Gate por feature ────────────────────────────────────────────────────────────────────────

    @Test
    void allFormsGatedByPointerAuthentication() {
        int[] words = {BRAAZ_X1, BLRAAZ_X2, RETAA, ERETAA, BRAA_X1_X2, BLRAA_X1_X2,
                LDRAA_X0_X1, AUTDA_X0_X1, XPACI_X0, XPACD_X0, PACIA_X0_X1, AUTIB_X0_X1};
        for (int word : words) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word));
        }
    }

    // ── BRAZ/BLRAZ ──────────────────────────────────────────────────────────────────────────────

    @Test
    void braazDecodesAsUnauthenticatedBranchToRn() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, BRAAZ_X1);
        assertEquals(Ir64BranchForm.REGISTER, op.form());
        assertEquals(1, op.registerOperand());
        assertFalse(op.link());
    }

    @Test
    void brabzKeyBitDoesNotChangeDecodedBehavior() {
        // Rota (b): a chave A/B (`m`) não afeta o resultado — BRAAZ/BRABZ decodificam IGUAL.
        Ir64Op.Branch64 a = (Ir64Op.Branch64) decode(PAUTH_DECODER, BRAAZ_X1);
        Ir64Op.Branch64 b = (Ir64Op.Branch64) decode(PAUTH_DECODER, BRABZ_X1);
        assertEquals(a.form(), b.form());
        assertEquals(a.registerOperand(), b.registerOperand());
        assertEquals(a.link(), b.link());
    }

    @Test
    void blraazDecodesWithLink() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, BLRAAZ_X2);
        assertEquals(Ir64BranchForm.REGISTER, op.form());
        assertEquals(2, op.registerOperand());
        assertTrue(op.link());
    }

    @Test
    void blrabzDecodesWithLink() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, BLRABZ_X2);
        assertEquals(2, op.registerOperand());
        assertTrue(op.link());
    }

    // ── RETA/ERETA ──────────────────────────────────────────────────────────────────────────────

    @Test
    void retaaTargetsX30Implicitly() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, RETAA);
        assertEquals(Ir64BranchForm.REGISTER, op.form());
        assertEquals(30, op.registerOperand());
        assertFalse(op.link());
    }

    @Test
    void retabTargetsX30Implicitly() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, RETAB);
        assertEquals(30, op.registerOperand());
    }

    @Test
    void eretaaDecodesAsExceptionReturn() {
        Ir64Op op = decode(PAUTH_DECODER, ERETAA);
        assertTrue(op instanceof Ir64Op.ExceptionReturn);
    }

    @Test
    void eretabDecodesAsExceptionReturn() {
        Ir64Op op = decode(PAUTH_DECODER, ERETAB);
        assertTrue(op instanceof Ir64Op.ExceptionReturn);
    }

    // ── BRA/BLRA ────────────────────────────────────────────────────────────────────────────────

    @Test
    void braaDecodesToRnIgnoringModifierRm() {
        // BRAA Xn, Xm: alvo é Xn; Xm (modificador) é ignorado sob a rota (b).
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, BRAA_X1_X2);
        assertEquals(1, op.registerOperand());
        assertFalse(op.link());
    }

    @Test
    void brabDecodesSameAsBraaKeyIgnored() {
        Ir64Op.Branch64 a = (Ir64Op.Branch64) decode(PAUTH_DECODER, BRAA_X1_X2);
        Ir64Op.Branch64 b = (Ir64Op.Branch64) decode(PAUTH_DECODER, BRAB_X1_X2);
        assertEquals(a.registerOperand(), b.registerOperand());
        assertEquals(a.link(), b.link());
    }

    @Test
    void blraaDecodesWithLink() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, BLRAA_X1_X2);
        assertEquals(1, op.registerOperand());
        assertTrue(op.link());
    }

    @Test
    void blrabDecodesWithLink() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) decode(PAUTH_DECODER, BLRAB_X1_X2);
        assertTrue(op.link());
    }

    // ── LDRA ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ldraaNoOffsetNoWriteback() {
        Ir64Op.Load64 op = (Ir64Op.Load64) decode(PAUTH_DECODER, LDRAA_X0_X1);
        assertEquals(0, op.rt());
        assertEquals(1, op.rn());
        assertEquals(0L, op.immediate());
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64MemSize.DOUBLEWORD, op.size());
    }

    @Test
    void ldraaPositiveOffsetNoWriteback() {
        Ir64Op.Load64 op = (Ir64Op.Load64) decode(PAUTH_DECODER, LDRAA_X0_X1_8);
        assertEquals(8L, op.immediate());
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, op.addressingMode());
    }

    @Test
    void ldraaPreIndexedWithWriteback() {
        Ir64Op.Load64 op = (Ir64Op.Load64) decode(PAUTH_DECODER, LDRAA_X0_X1_8_PRE);
        assertEquals(8L, op.immediate());
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.PRE_INDEX, op.addressingMode());
    }

    @Test
    void ldrabNegativeOffsetPreIndexedKeyIgnored() {
        Ir64Op.Load64 op = (Ir64Op.Load64) decode(PAUTH_DECODER, LDRAB_X0_X1_M8_PRE);
        assertEquals(-8L, op.immediate());
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.PRE_INDEX, op.addressingMode());
    }

    // ── AUTDA/AUTDB/AUTIA/AUTIB/PACIA/PACIB/PACDA/PACDB (in-place, identidade) ────────────────────

    @Test
    void autdaDecodesAsPointerAuthInPlace() {
        Ir64Op.PointerAuthInPlace op = (Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, AUTDA_X0_X1);
        assertEquals(Ir64PointerAuthOp.AUTDA, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void autdbDecodesAsPointerAuthInPlace() {
        Ir64Op.PointerAuthInPlace op = (Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, AUTDB_X0_X1);
        assertEquals(Ir64PointerAuthOp.AUTDB, op.op());
    }

    @Test
    void autiaAndAutibDecode() {
        assertEquals(Ir64PointerAuthOp.AUTIA,
                ((Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, AUTIA_X0_X1)).op());
        assertEquals(Ir64PointerAuthOp.AUTIB,
                ((Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, AUTIB_X0_X1)).op());
    }

    @Test
    void paciaPacibPacdaPacdbDecode() {
        assertEquals(Ir64PointerAuthOp.PACIA,
                ((Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, PACIA_X0_X1)).op());
        assertEquals(Ir64PointerAuthOp.PACIB,
                ((Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, PACIB_X0_X1)).op());
        assertEquals(Ir64PointerAuthOp.PACDA,
                ((Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, PACDA_X0_X1)).op());
        assertEquals(Ir64PointerAuthOp.PACDB,
                ((Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, PACDB_X0_X1)).op());
    }

    // ── XPACI/XPACD ─────────────────────────────────────────────────────────────────────────────

    @Test
    void xpaciDecodesAsPointerAuthInPlaceNoModifier() {
        Ir64Op.PointerAuthInPlace op = (Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, XPACI_X0);
        assertEquals(Ir64PointerAuthOp.XPACI, op.op());
        assertEquals(0, op.rd());
        assertEquals(-1, op.rn());
    }

    @Test
    void xpacdDecodesAsPointerAuthInPlaceNoModifier() {
        Ir64Op.PointerAuthInPlace op = (Ir64Op.PointerAuthInPlace) decode(PAUTH_DECODER, XPACD_X0);
        assertEquals(Ir64PointerAuthOp.XPACD, op.op());
        assertEquals(-1, op.rn());
    }

    @Test
    void xpaciWithNonReservedRnStaysUnsupported() {
        // Rn (bits[9:5]) tem que ser fixo em `11111` — qualquer outro valor é reservado (G8).
        int malformed = XPACI_X0 & ~(0b1_1111 << 5); // zera Rn (era 11111, agora 00000)
        assertThrows(UnsupportedOperationException.class, () -> decode(PAUTH_DECODER, malformed));
    }

    // ── G8: encodings reservados no mesmo espaço não são confundidos ──────────────────────────────

    @Test
    void reservedOneSourceOpcode2StaysUnsupported() {
        // opcode2(bits[20:16]) fora de {00000,00001} é reservado neste subgrupo.
        int reserved = (AUTDA_X0_X1 & ~(0b1_1111 << 16)) | (0b00010 << 16);
        assertThrows(UnsupportedOperationException.class, () -> decode(PAUTH_DECODER, reserved));
    }

    @Test
    void narrowFormOfPauthInPlaceIsReserved() {
        // sf=0 (bit31) neste subgrupo é reservado — só existe a forma `X`.
        int narrow = AUTDA_X0_X1 & ~(1 << 31);
        assertThrows(UnsupportedOperationException.class, () -> decode(PAUTH_DECODER, narrow));
    }

    @Test
    void absStillDecodesCorrectlyAfterOpcode2Gate() {
        // Não-regressão do achado desta task: ABS (opcode2=00000) continua decodificando normal
        // sob CSSC, mesmo com o novo gate de opcode2 no topo de decodeDataProcessing1Source.
        Aarch64Decoder cssc = new Aarch64Decoder(Aarch64Architecture.ARMV8_9_A);
        // abs x0, x1 == 0xdac02020 (mesma constante usada em Aarch64Cssc2SourceResidualDecoderTest)
        Ir64Op.AbsGeneral op = (Ir64Op.AbsGeneral) decode(cssc, 0xdac02020);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void reservedBranchRegisterOp3StaysUnsupported() {
        // op3 alto (bits[15:11]) fora de {00000,00001} (ex. DRPS ou combinação reservada).
        int reserved = (BRAAZ_X1 & ~(0b1_1111 << 11)) | (0b00010 << 11);
        assertThrows(UnsupportedOperationException.class, () -> decode(PAUTH_DECODER, reserved));
    }
}
