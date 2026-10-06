package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.CryptoOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha512Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3TtOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static dev.vitorsilverio.armjitter.decoder64.LoadStoreRowsTest.NO_SME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15a: a criptografia fora do `bit21=1` como tabela ({@link CryptoRows}) — invariantes, roteamento pelo
/// {@link Aarch64Decoder}, campos e features como coluna (cada família com a SUA feature).
class CryptoRowsTest {
    private static final long ADDRESS = 0x1000L;
    private static final int ROW_COUNT = 24;
    /// Só "three-register SHA" não tem coluna de feature.
    private static final int BASE_ROW_COUNT = 7;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        return decoder.decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, CryptoRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(CryptoRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(CryptoRows.ROWS, NO_SME);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515AL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        SplittableRandom random = new SplittableRandom(0xE1515AL);
        for (DecodeRow<Ir64Op> row : CryptoRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void baseArchitectureKeepsOnlyTheRowsWithoutFeature() {
        assertEquals(BASE_ROW_COUNT, DecodeTable.forArchitecture(CryptoRows.ROWS,
                Aarch64Architecture.ARMV8_0_A).rows().size());
    }

    /// Conferidos contra o `objdump` 2.46 do devkitA64.
    @Test
    void fieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // sha256su1 v0.4s, v1.4s, v2.4s · sha1m q0, s1, v2.4s
        assertEquals(new CryptoOp64.ShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA256SU1, 0, 1, 2),
                decodeWord(decoder, 0x5e026020));
        assertEquals(new CryptoOp64.ShaThreeRegister(Ir64CryptoShaThreeRegisterOp.SHA1M, 0, 1, 2),
                decodeWord(decoder, 0x5e022020));
        // eor3 v0.16b, v1.16b, v2.16b, v20.16b · sm3ss1 v0.4s, v1.4s, v2.4s, v17.4s
        assertEquals(new CryptoOp64.Sha3FourRegister(Ir64CryptoSha3Op.EOR3, 0, 1, 2, 20), decodeWord(decoder, 0xce025020));
        assertEquals(new CryptoOp64.Sm3FourRegister(0, 1, 2, 17), decodeWord(decoder, 0xce424420));
        // sm3tt2b v0.4s, v1.4s, v2.s[3] · sm3tt1a v0.4s, v1.4s, v2.s[1]
        assertEquals(new CryptoOp64.Sm3ThreeRegisterImm2(Ir64CryptoSm3TtOp.TT2B, 0, 1, 2, 3), decodeWord(decoder, 0xce42bc20));
        assertEquals(new CryptoOp64.Sm3ThreeRegisterImm2(Ir64CryptoSm3TtOp.TT1A, 0, 1, 2, 1), decodeWord(decoder, 0xce429020));
        // xar v0.2d, v1.2d, v2.2d, #37 · rax1 v0.2d, v1.2d, v2.2d
        assertEquals(new CryptoOp64.Sha3TwoSourceRotate(Ir64CryptoSha3Op.XAR, 0, 1, 2, 37), decodeWord(decoder, 0xce829420));
        assertEquals(new CryptoOp64.Sha3TwoSourceRotate(Ir64CryptoSha3Op.RAX1, 0, 1, 2, 0), decodeWord(decoder, 0xce628c20));
        // sha512h q0, q1, v2.2d · sha512su0 v0.2d, v1.2d · sm4e v0.4s, v1.4s · sm4ekey · sm3partw2
        assertEquals(new CryptoOp64.Sha512ThreeRegister(Ir64CryptoSha512Op.SHA512H, 0, 1, 2), decodeWord(decoder, 0xce628020));
        assertEquals(new CryptoOp64.Sha512TwoRegister(0, 1), decodeWord(decoder, 0xcec08020));
        assertEquals(new CryptoOp64.Sm4Encrypt(0, 1), decodeWord(decoder, 0xcec08420));
        assertEquals(new CryptoOp64.Sm4KeyUpdate(0, 1, 2), decodeWord(decoder, 0xce62c820));
        assertEquals(new CryptoOp64.Sm3ThreeRegister(Ir64CryptoSm3Op.PARTW2, 0, 1, 2), decodeWord(decoder, 0xce62c420));
    }

    /// Cada família só existe com a SUA feature: um preset com só uma delas não aceita as outras.
    @ParameterizedTest(name = "{1} só com {2}")
    @CsvSource(delimiter = '|', textBlock = """
            ce025020 | eor3 v0.16b, v1.16b, v2.16b, v20.16b | SHA3
            ce829420 | xar v0.2d, v1.2d, v2.2d, #37         | SHA3
            ce628020 | sha512h q0, q1, v2.2d                | SHA512
            cec08020 | sha512su0 v0.2d, v1.2d               | SHA512
            ce424420 | sm3ss1 v0.4s, v1.4s, v2.4s, v17.4s   | SM3
            ce62c420 | sm3partw2 v0.4s, v1.4s, v2.4s        | SM3
            cec08420 | sm4e v0.4s, v1.4s                    | SM4
            ce62c820 | sm4ekey v0.4s, v1.4s, v2.4s          | SM4
            """)
    void eachFamilyNeedsItsOwnFeature(String word, String objdump, String feature) {
        int value = Integer.parseUnsignedInt(word, 16);
        for (String other : new String[] {"SHA3", "SHA512", "SM3", "SM4"}) {
            Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.of(other,
                    Aarch64Feature.valueOf(other)));
            if (other.equals(feature)) {
                assertDoesNotThrow(() -> decodeWord(decoder, value), objdump);
            } else {
                assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, value), objdump + " com " + other);
            }
        }
    }
}
