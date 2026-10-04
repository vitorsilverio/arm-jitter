package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.10: a classe "Data Processing — Immediate" como tabela ({@link DataProcessingImmediateRows}) —
/// invariantes da tabela, roteamento pelo {@link Aarch64Decoder}, os 3 bugs G8 que a versão em cascata
/// tinha e a impressão do conjunto aceito do espaço inteiro.
class DataProcessingImmediateRowsTest {
    private static final Aarch64Architecture BASE = Aarch64Architecture.ARMV8_0_A;
    private static final int ROW_COUNT = 28;
    /// `imms<1:0>=00`: com esse valor nenhuma bitmask do logical imediato é reservada (as 8 reservadas
    /// têm no máximo um `0` em `imms`, e nunca nos dois bits baixos ao mesmo tempo).
    private static final int IMMS_LOW_TWO_BITS = 0b11 << 10;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, DataProcessingImmediateRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(DataProcessingImmediateRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(DataProcessingImmediateRows.ROWS, BASE);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, IMMS_LOW_TWO_BITS, 0xE1510L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        SplittableRandom random = new SplittableRandom(0xE1510L);
        for (DecodeRow<Ir64Op> row : DataProcessingImmediateRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask() & ~IMMS_LOW_TWO_BITS);
            assertEquals(row.build().decode(word, 0L), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void pcRelativeKeepsTheInstructionAddressAndSignExtends() {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        TestAddressSpace raw = new TestAddressSpace(0x40);
        raw.put32(0x24, 0x10000041); // adr x1, .+8
        raw.put32(0x28, 0xb0000041); // adrp x1, página+0x9000
        raw.put32(0x2C, 0x10ffffe1); // adr x1, .-4
        AddressSpace64 memory = AddressSpace64.wrapping(raw);
        assertEquals(new IntegerOp64.PcRelative(1, 0x24, 8, false), decoder.decode(memory, 0x24));
        assertEquals(new IntegerOp64.PcRelative(1, 0x28, 0x9000, true), decoder.decode(memory, 0x28));
        assertEquals(new IntegerOp64.PcRelative(1, 0x2C, -4, false), decoder.decode(memory, 0x2C));
    }

    @Test
    void addSubImmediateStackPointerFormsFollowTheSetFlagsBit() {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        // add x1, x2, #0x3, lsl #12 — Rd|SP; subs x1, x2, #0x3, lsl #12 — Rd normal. Rn|SP nos dois.
        assertEquals(new IntegerOp64.Alu64(Ir64AluOp.ADD, 1, 2, 0x3000, true, false, true, true),
                decodeWord(decoder, 0x91400c41));
        assertEquals(new IntegerOp64.Alu64(Ir64AluOp.SUB, 1, 2, 0x3000, true, true, false, true),
                decodeWord(decoder, 0xf1400c41));
    }

    @Test
    void logicalImmediateHasNoStackPointerForm() {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        // ands w1, w2, #0xf
        assertEquals(new IntegerOp64.Alu64(Ir64AluOp.AND, 1, 2, Aarch64LogicalImmediate.decodeBitMasks(0, 3, 0),
                false, true, false, false), decodeWord(decoder, 0x72000c41));
    }

    /// Coluna 1 = `undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha, como ele a desmonta.
    /// As 6 primeiras eram aceitas pela versão em cascata (G8); as outras já eram recusadas.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            52c2468a | 52a2468a | mov w10, #0x12340000 — MOVZ W com hw=10
            12e2468a | 12a2468a | mov w10, #0xedcbffff — MOVN W com hw=11
            72c2468a | 72a2468a | movk w10, #0x1234, lsl #16 — MOVK W com hw=10
            13207c41 | 13017c41 | asr w1, w2, #1 — SBFM W com immr<5>=1
            5300fc41 | 53007c41 | lsr w1, w2, #0 — UBFM W com imms<5>=1
            33800c41 | 13800c41 | extr w1, w2, w0, #3 — EXTR com op21=01
            d3c10c41 | 93c10c41 | extr x1, x2, x1, #3 — EXTR X com op21=10
            33208c41 | 33010c41 | bfxil w1, w2, #1, #3 — BFM W com immr<5>=1 e imms<5>=1
            13808c41 | 13800c41 | extr w1, w2, w0, #3 — EXTR W com imms<5>=1
            9381fc41 | 93c1fc41 | extr x1, x2, x1, #63 — EXTR X com N=0
            f3400c41 | b3400c41 | bfxil x1, x2, #0, #4 — bitfield com opc=11
            12400c41 | 92400c41 | and x1, x2, #0xf — logical W com N=1
            9240fc41 | 9240f841 | and x1, x2, #0x7fffffffffffffff — bitmask reservada (N=1, imms=111111)
            1200fc41 | 72000c41 | ands w1, w2, #0xf — bitmask reservada (N=0, imms=111111)
            32a2468a | 52a2468a | mov w10, #0x12340000 — move wide com opc=01
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }

    private static final int CLASS_BITS = 0b100 << 26;
    private static final int HIGH_BITS_SHIFT = 29;
    private static final int MIDDLE_BITS_SHIFT = 10;
    private static final int MIDDLE_BITS = 16;
    private static final int HIGH_BITS = 3;
    /// Conferidos contra o `objdump` 2.46 do devkitA64 em 2026-10-03 (E15.10): nenhuma das palavras
    /// aceitas é `undefined`.
    private static final int ACCEPTED_COUNT = 297_216;
    private static final String ACCEPTED_SHA256 = "dc0c1c16a558707f83983e66d44fc01b488b17020e7dfc62c186dd7645c76356";

    /// Guarda permanente do G8 nesta classe (padrão da E15.9c): enumera o espaço de
    /// `e15.10-scripts/DpImmediateOracle.java` (bits 31:29 e 25:10; `Rn`/`Rd` em `0`, que não mudam a
    /// aceitação aqui) e compara contagem + SHA-256 das palavras ACEITAS pela ISA base. Se falhar, o
    /// conjunto aceito mudou: rodar o oráculo e cruzar as aceitas com o `objdump`
    /// (`e15.9b-scripts/objdump-residuo.sh` serve com o filtro de preset trocado) — 0 `undefined` — e só
    /// então atualizar as duas constantes. A B19.30 (linhas `bit23=1`) mede com preset sem `MTE`/`CSSC`
    /// e não muda nada aqui.
    @Test
    void acceptedSpaceMatchesTheObjdumpVerifiedFingerprint() throws Exception {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
        int accepted = 0;
        for (int high = 0; high < 1 << HIGH_BITS; high++) {
            for (int middle = 0; middle < 1 << MIDDLE_BITS; middle++) {
                int word = (high << HIGH_BITS_SHIFT) | CLASS_BITS | (middle << MIDDLE_BITS_SHIFT);
                try {
                    decodeWord(decoder, word);
                } catch (UnsupportedOperationException refused) {
                    continue;
                }
                accepted++;
                digest.update(buffer.clear().putInt(word).array());
            }
        }
        assertEquals(ACCEPTED_COUNT, accepted);
        assertEquals(ACCEPTED_SHA256, HexFormat.of().formatHex(digest.digest()));
    }
}
