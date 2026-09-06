package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha512Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3TtOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `SHA512H`/`SHA512H2`/`SHA512SU0`/`SHA512SU1` (`FEAT_SHA512`), `SM3PARTW1`/`SM3PARTW2`/`SM3SS1`/
/// `SM3TT1A`/`SM3TT1B`/`SM3TT2A`/`SM3TT2B` (`FEAT_SM3`) e `SM4E`/`SM4EKEY` (`FEAT_SM4`) — B19.10, as
/// 13 linhas que moram no MESMO prefixo `0xCE` que a B11.12 abriu para `FEAT_SHA3`. Palavras vêm de
/// `aarch64-linux-gnu-as`/`objdump` reais (WSL2 Ubuntu, `.arch armv8.2-a+sha3+sm4`) — corpus real,
/// não fórmula.
class Aarch64CryptoSha512Sm3Sm4DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder FULL_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── SHA512H/SHA512H2 (`sha512h Qd, Qn, Vm.2D` / `sha512h2 Qd, Qn, Vm.2D`) ─────────────────────

    @Test
    void sha512h() {
        // `sha512h q0, q1, v2.2d`
        Ir64Op.CryptoSha512ThreeRegister op =
                (Ir64Op.CryptoSha512ThreeRegister) decodeWord(FULL_DECODER, 0xce628020);
        assertEquals(Ir64CryptoSha512Op.SHA512H, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sha512hSecondForm() {
        // `sha512h q10, q11, v12.2d`
        Ir64Op.CryptoSha512ThreeRegister op =
                (Ir64Op.CryptoSha512ThreeRegister) decodeWord(FULL_DECODER, 0xce6c816a);
        assertEquals(Ir64CryptoSha512Op.SHA512H, op.op());
        assertEquals(10, op.rd());
        assertEquals(11, op.rn());
        assertEquals(12, op.rm());
    }

    @Test
    void sha512h2() {
        // `sha512h2 q0, q1, v2.2d`
        Ir64Op.CryptoSha512ThreeRegister op =
                (Ir64Op.CryptoSha512ThreeRegister) decodeWord(FULL_DECODER, 0xce628420);
        assertEquals(Ir64CryptoSha512Op.SHA512H2, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sha512h2SecondForm() {
        // `sha512h2 q13, q14, v15.2d`
        Ir64Op.CryptoSha512ThreeRegister op =
                (Ir64Op.CryptoSha512ThreeRegister) decodeWord(FULL_DECODER, 0xce6f85cd);
        assertEquals(Ir64CryptoSha512Op.SHA512H2, op.op());
        assertEquals(13, op.rd());
        assertEquals(14, op.rn());
        assertEquals(15, op.rm());
    }

    @Test
    void sha512su0() {
        // `sha512su0 v0.2d, v1.2d`
        Ir64Op.CryptoSha512TwoRegister op =
                (Ir64Op.CryptoSha512TwoRegister) decodeWord(FULL_DECODER, 0xcec08020);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void sha512su0SecondForm() {
        // `sha512su0 v20.2d, v21.2d`
        Ir64Op.CryptoSha512TwoRegister op =
                (Ir64Op.CryptoSha512TwoRegister) decodeWord(FULL_DECODER, 0xcec082b4);
        assertEquals(20, op.rd());
        assertEquals(21, op.rn());
    }

    @Test
    void sha512su1() {
        // `sha512su1 v0.2d, v1.2d, v2.2d`
        Ir64Op.CryptoSha512ThreeRegister op =
                (Ir64Op.CryptoSha512ThreeRegister) decodeWord(FULL_DECODER, 0xce628820);
        assertEquals(Ir64CryptoSha512Op.SHA512SU1, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sha512su1SecondForm() {
        // `sha512su1 v9.2d, v10.2d, v11.2d`
        Ir64Op.CryptoSha512ThreeRegister op =
                (Ir64Op.CryptoSha512ThreeRegister) decodeWord(FULL_DECODER, 0xce6b8949);
        assertEquals(Ir64CryptoSha512Op.SHA512SU1, op.op());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(11, op.rm());
    }

    // ── SM3PARTW1/SM3PARTW2 ──────────────────────────────────────────────────────────────────────

    @Test
    void sm3partw1() {
        // `sm3partw1 v0.4s, v1.4s, v2.4s`
        Ir64Op.CryptoSm3ThreeRegister op =
                (Ir64Op.CryptoSm3ThreeRegister) decodeWord(FULL_DECODER, 0xce62c020);
        assertEquals(Ir64CryptoSm3Op.PARTW1, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sm3partw1SecondForm() {
        // `sm3partw1 v17.4s, v18.4s, v19.4s`
        Ir64Op.CryptoSm3ThreeRegister op =
                (Ir64Op.CryptoSm3ThreeRegister) decodeWord(FULL_DECODER, 0xce73c251);
        assertEquals(Ir64CryptoSm3Op.PARTW1, op.op());
        assertEquals(17, op.rd());
        assertEquals(18, op.rn());
        assertEquals(19, op.rm());
    }

    @Test
    void sm3partw2() {
        // `sm3partw2 v0.4s, v1.4s, v2.4s`
        Ir64Op.CryptoSm3ThreeRegister op =
                (Ir64Op.CryptoSm3ThreeRegister) decodeWord(FULL_DECODER, 0xce62c420);
        assertEquals(Ir64CryptoSm3Op.PARTW2, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sm3partw2SecondForm() {
        // `sm3partw2 v22.4s, v23.4s, v24.4s`
        Ir64Op.CryptoSm3ThreeRegister op =
                (Ir64Op.CryptoSm3ThreeRegister) decodeWord(FULL_DECODER, 0xce78c6f6);
        assertEquals(Ir64CryptoSm3Op.PARTW2, op.op());
        assertEquals(22, op.rd());
        assertEquals(23, op.rn());
        assertEquals(24, op.rm());
    }

    // ── SM3SS1 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void sm3ss1() {
        // `sm3ss1 v0.4s, v1.4s, v2.4s, v3.4s`
        Ir64Op.CryptoSm3FourRegister op =
                (Ir64Op.CryptoSm3FourRegister) decodeWord(FULL_DECODER, 0xce420c20);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(3, op.ra());
    }

    @Test
    void sm3ss1SecondForm() {
        // `sm3ss1 v10.4s, v11.4s, v12.4s, v13.4s`
        Ir64Op.CryptoSm3FourRegister op =
                (Ir64Op.CryptoSm3FourRegister) decodeWord(FULL_DECODER, 0xce4c356a);
        assertEquals(10, op.rd());
        assertEquals(11, op.rn());
        assertEquals(12, op.rm());
        assertEquals(13, op.ra());
    }

    // ── SM3TT1A/SM3TT1B/SM3TT2A/SM3TT2B (as 4 variantes decodificam para operações distintas — o
    // ── campo que separa é bits[13:12], não o `imm2`, ver o Aceite da task) ─────────────────────

    @Test
    void sm3tt1a() {
        // `sm3tt1a v0.4s, v1.4s, v2.s[0]`
        Ir64Op.CryptoSm3ThreeRegisterImm2 op =
                (Ir64Op.CryptoSm3ThreeRegisterImm2) decodeWord(FULL_DECODER, 0xce428020);
        assertEquals(Ir64CryptoSm3TtOp.TT1A, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(0, op.imm2());
    }

    @Test
    void sm3tt1aSecondFormWithImm2Three() {
        // `sm3tt1a v5.4s, v6.4s, v7.s[3]`
        Ir64Op.CryptoSm3ThreeRegisterImm2 op =
                (Ir64Op.CryptoSm3ThreeRegisterImm2) decodeWord(FULL_DECODER, 0xce47b0c5);
        assertEquals(Ir64CryptoSm3TtOp.TT1A, op.op());
        assertEquals(5, op.rd());
        assertEquals(6, op.rn());
        assertEquals(7, op.rm());
        assertEquals(3, op.imm2());
    }

    @Test
    void sm3tt1b() {
        // `sm3tt1b v0.4s, v1.4s, v2.s[1]`
        Ir64Op.CryptoSm3ThreeRegisterImm2 op =
                (Ir64Op.CryptoSm3ThreeRegisterImm2) decodeWord(FULL_DECODER, 0xce429420);
        assertEquals(Ir64CryptoSm3TtOp.TT1B, op.op());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
        assertEquals(1, op.imm2());
    }

    @Test
    void sm3tt2a() {
        // `sm3tt2a v0.4s, v1.4s, v2.s[2]`
        Ir64Op.CryptoSm3ThreeRegisterImm2 op =
                (Ir64Op.CryptoSm3ThreeRegisterImm2) decodeWord(FULL_DECODER, 0xce42a820);
        assertEquals(Ir64CryptoSm3TtOp.TT2A, op.op());
        assertEquals(2, op.imm2());
    }

    @Test
    void sm3tt2b() {
        // `sm3tt2b v0.4s, v1.4s, v2.s[3]`
        Ir64Op.CryptoSm3ThreeRegisterImm2 op =
                (Ir64Op.CryptoSm3ThreeRegisterImm2) decodeWord(FULL_DECODER, 0xce42bc20);
        assertEquals(Ir64CryptoSm3TtOp.TT2B, op.op());
        assertEquals(3, op.imm2());
    }

    // ── SM4E/SM4EKEY ─────────────────────────────────────────────────────────────────────────────

    @Test
    void sm4e() {
        // `sm4e v0.4s, v1.4s`
        Ir64Op.CryptoSm4Encrypt op = (Ir64Op.CryptoSm4Encrypt) decodeWord(FULL_DECODER, 0xcec08420);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void sm4eSecondForm() {
        // `sm4e v25.4s, v26.4s`
        Ir64Op.CryptoSm4Encrypt op = (Ir64Op.CryptoSm4Encrypt) decodeWord(FULL_DECODER, 0xcec08759);
        assertEquals(25, op.rd());
        assertEquals(26, op.rn());
    }

    @Test
    void sm4ekey() {
        // `sm4ekey v0.4s, v1.4s, v2.4s`
        Ir64Op.CryptoSm4KeyUpdate op = (Ir64Op.CryptoSm4KeyUpdate) decodeWord(FULL_DECODER, 0xce62c820);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sm4ekeySecondForm() {
        // `sm4ekey v27.4s, v28.4s, v29.4s`
        Ir64Op.CryptoSm4KeyUpdate op = (Ir64Op.CryptoSm4KeyUpdate) decodeWord(FULL_DECODER, 0xce7dcb9b);
        assertEquals(27, op.rd());
        assertEquals(28, op.rn());
        assertEquals(29, op.rm());
    }

    // ── EOR3/BCAX/RAX1/XAR (B11.12) continuam decodificando — zero-diff, suíte da B11.12 não é
    // ── editada por esta task, aqui só uma checagem de fumaça de que o dispatch estendido não
    // ── quebrou o espaço vizinho ─────────────────────────────────────────────────────────────────

    @Test
    void eor3StillDecodes() {
        // `eor3 v0.16b, v1.16b, v2.16b, v3.16b`
        Ir64Op.CryptoSha3FourRegister op =
                (Ir64Op.CryptoSha3FourRegister) decodeWord(FULL_DECODER, 0xce020c20);
        assertEquals(0, op.rd());
    }

    @Test
    void rax1StillDecodes() {
        // `rax1 v0.2d, v1.2d, v2.2d`
        Ir64Op.CryptoSha3TwoSourceRotate op =
                (Ir64Op.CryptoSha3TwoSourceRotate) decodeWord(FULL_DECODER, 0xce628c20);
        assertEquals(0, op.rd());
    }

    // ── Reservado dentro do espaço `0xCE` novo continua `unsupported` (G8) ─────────────────────────

    @Test
    void sm3MixReservedBit15_14PatternIsUnsupported() {
        // op0=0b010 com bits[15:14]="01" (nem SM3SS1 nem SM3TT) — reservado.
        int reservedWord = 0xce424020; // mesmo esqueleto de sm3tt1a, bits[15:14] forçado a "01"
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FULL_DECODER, reservedWord));
    }

    @Test
    void threeRegisterOpcode6ReservedIsUnsupported() {
        // op0=0b011 (mesmo de RAX1/SHA512*/SM3PARTW*/SM4EKEY) com opcode6 nunca usado.
        int reservedWord = 0xce62fc20; // bits[15:10] = 111111
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FULL_DECODER, reservedWord));
    }

    @Test
    void twoRegisterNonZeroRmIsUnsupported() {
        // `Op0=0b110` exige bits[20:16]="00000" — forçar Rm != 0 deve recusar.
        int reservedWord = 0xcec18420; // sm4e com Rm=1 em vez de 0
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FULL_DECODER, reservedWord));
    }

    // ── Default architecture (`ARMv8.0-A`) rejeita as 13 ────────────────────────────────────────

    @Test
    void defaultArchitectureRejectsSha512h() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce628020));
    }

    @Test
    void defaultArchitectureRejectsSha512su0() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xcec08020));
    }

    @Test
    void defaultArchitectureRejectsSm3partw1() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce62c020));
    }

    @Test
    void defaultArchitectureRejectsSm3ss1() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce420c20));
    }

    @Test
    void defaultArchitectureRejectsSm3tt1a() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce428020));
    }

    @Test
    void defaultArchitectureRejectsSm4e() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xcec08420));
    }

    @Test
    void defaultArchitectureRejectsSm4ekey() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, 0xce62c820));
    }

    // ── Independência dos 3 gates (Aceite da task): uma arquitetura com SÓ `SHA512` aceita
    // ── `SHA512H` e recusa `SM3PARTW1`/`SM4E` — prova que os gates não são um bloco só ──────────

    @Test
    void sha512OnlyArchitectureAcceptsSha512AndRejectsSm3AndSm4() {
        Aarch64Decoder decoder = new Aarch64Decoder(
                Aarch64Architecture.of("sha512-only", Aarch64Feature.SHA512));
        assertEquals(0, ((Ir64Op.CryptoSha512ThreeRegister) decodeWord(decoder, 0xce628020)).rd());
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xce62c020)); // SM3PARTW1
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xcec08420)); // SM4E
    }

    @Test
    void sm3OnlyArchitectureAcceptsSm3AndRejectsSha512AndSm4() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.of("sm3-only", Aarch64Feature.SM3));
        assertEquals(0, ((Ir64Op.CryptoSm3ThreeRegister) decodeWord(decoder, 0xce62c020)).rd());
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xce628020)); // SHA512H
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xcec08420)); // SM4E
    }

    @Test
    void sm4OnlyArchitectureAcceptsSm4AndRejectsSha512AndSm3() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.of("sm4-only", Aarch64Feature.SM4));
        assertEquals(0, ((Ir64Op.CryptoSm4Encrypt) decodeWord(decoder, 0xcec08420)).rd());
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xce628020)); // SHA512H
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, 0xce62c020)); // SM3PARTW1
    }
}
