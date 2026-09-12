package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.11b (`FEAT_FP8FMA`): `FMLAL_hb_v`/`FMLALL_sb_v`/`FMLAL_hb_vi`/`FMLALL_sb_vi` — decoder.
///
/// O assembler WSL disponível nesta sessão (`aarch64-linux-gnu-as`/binutils 2.46, `.arch
/// armv9.5-a+fp8fma`) rejeita os quatro mnemônicos (`fmlal`/`fmlal2`/`fmlall`/`fmlall2` com
/// operandos `.16b`/`.8h`/`.4s`) com "operand mismatch", sugerindo só as formas `FEAT_FHM`
/// (`.2s,.2h,.2h`) — o binutils desta imagem ainda não implementa a forma AdvSIMD (não-SME) de
/// `FEAT_FP8FMA`. Os words abaixo foram MONTADOS À MÃO bit a bit a partir de
/// `target/isa-decode/a64.decode:1219-1223` (`FMLAL_hb_v`/`FMLALL_sb_v`) e `:1350-1354`
/// (`FMLAL_hb_vi`/`FMLALL_sb_vi`, ver `## Resultado` da task para o script de codificação) e
/// validados por round-trip contra a MESMA lógica de bits desta task (não contra um oráculo
/// independente) — registrado aqui como o "não conseguiu montar" que o `tasks/README.md` pede.
/// Cross-check indireto: `FMLAL_hb_v`/`FAMAX_h` compartilham o MESMO prefixo `bit23=1`/`bit22=1`/
/// `bit21=0` (`0xc2` no terceiro byte com `rm=2`) — o byte 2 de {@link #fmlalHbV} bate exatamente
/// com o de `FAMAX_h` (`0x...c2...`, ver `Aarch64AdvSimdFaminmaxDecoderTest`), confirmando que a
/// mesma convenção de bits foi reaproveitada corretamente.
class Aarch64AdvSimdFp8FusedMultiplyAddDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_5_A);
    private static final Aarch64Decoder NO_FEATURE_DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op decodeWord(int word) {
        return decodeWord(DECODER, word);
    }

    // ── FMLAL_hb_v ───────────────────────────────────────────────────────────────────────────────

    @Test
    void fmlalHbV() {
        // idxn=0, rm=2, rn=1, rd=0.
        Ir64Op.VectorFp8FusedMultiplyAddLong op =
                (Ir64Op.VectorFp8FusedMultiplyAddLong) decodeWord(0x0ec2fc20);
        assertEquals(false, op.wideDestination());
        assertEquals(0, op.sourceByteSelect());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fmlalHbVIdxnOne() {
        // idxn=1 (bit30), resto igual — prova que o bit30 vira `sourceByteSelect`, não `Q`.
        Ir64Op.VectorFp8FusedMultiplyAddLong op =
                (Ir64Op.VectorFp8FusedMultiplyAddLong) decodeWord(0x4ec2fc20);
        assertEquals(false, op.wideDestination());
        assertEquals(1, op.sourceByteSelect());
    }

    // ── FMLALL_sb_v ──────────────────────────────────────────────────────────────────────────────

    @Test
    void fmlallSbV() {
        // idxn=(0,0)=0, rm=5, rn=4, rd=3.
        Ir64Op.VectorFp8FusedMultiplyAddLong op =
                (Ir64Op.VectorFp8FusedMultiplyAddLong) decodeWord(0x0e05c483);
        assertEquals(true, op.wideDestination());
        assertEquals(0, op.sourceByteSelect());
        assertEquals(3, op.rd());
        assertEquals(4, op.rn());
        assertEquals(5, op.rm());
    }

    @Test
    void fmlallSbVIdxnCombinesBit30AndBit22() {
        // idxn=(1,1)=0b11=3 — prova que bit30(alto)/bit22(baixo) combinam em UM campo de 2 bits.
        Ir64Op.VectorFp8FusedMultiplyAddLong op =
                (Ir64Op.VectorFp8FusedMultiplyAddLong) decodeWord(0x4e45c483);
        assertEquals(3, op.sourceByteSelect());
    }

    @Test
    void fmlallSbVIdxnHighOnly() {
        // idxn=(1,0)=0b10=2 — a metade ALTA sozinha, prova que a ordem (alto:baixo) está certa.
        Ir64Op.VectorFp8FusedMultiplyAddLong op =
                (Ir64Op.VectorFp8FusedMultiplyAddLong) decodeWord(0x4e05c483);
        assertEquals(2, op.sourceByteSelect());
    }

    // ── FMLAL_hb_vi ──────────────────────────────────────────────────────────────────────────────

    @Test
    void fmlalHbVi() {
        // idxn=0, index=0(h=0,low3=0), rm=6, rn=7, rd=6.
        Ir64Op.VectorFp8FusedMultiplyAddLongByElement op =
                (Ir64Op.VectorFp8FusedMultiplyAddLongByElement) decodeWord(0x0fc600e6);
        assertEquals(false, op.wideDestination());
        assertEquals(0, op.sourceByteSelect());
        assertEquals(0, op.index());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
        assertEquals(6, op.rm());
    }

    @Test
    void fmlalHbViMaxIndexAndSelect() {
        // idxn=1, index=15(h=1,low3=7) — prova que o índice de 4 bits (H:bits[21:19]) é lido certo.
        Ir64Op.VectorFp8FusedMultiplyAddLongByElement op =
                (Ir64Op.VectorFp8FusedMultiplyAddLongByElement) decodeWord(0x4ffe08e6);
        assertEquals(1, op.sourceByteSelect());
        assertEquals(15, op.index());
        assertEquals(6, op.rm());
    }

    // ── FMLALL_sb_vi ─────────────────────────────────────────────────────────────────────────────

    @Test
    void fmlallSbVi() {
        // idxn=(0,0)=0, index=0, rm=2, rn=10, rd=9.
        Ir64Op.VectorFp8FusedMultiplyAddLongByElement op =
                (Ir64Op.VectorFp8FusedMultiplyAddLongByElement) decodeWord(0x2f028149);
        assertEquals(true, op.wideDestination());
        assertEquals(0, op.sourceByteSelect());
        assertEquals(0, op.index());
        assertEquals(9, op.rd());
        assertEquals(10, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void fmlallSbViMaxIndexAndIdxn() {
        // idxn=(1,1)=3, index=15 — combina os dois campos de 2/4 bits no valor máximo de cada um.
        Ir64Op.VectorFp8FusedMultiplyAddLongByElement op =
                (Ir64Op.VectorFp8FusedMultiplyAddLongByElement) decodeWord(0x6f7a8949);
        assertEquals(3, op.sourceByteSelect());
        assertEquals(15, op.index());
    }

    // ── Vizinhos que NÃO podem ser afetados ─────────────────────────────────────────────────────

    @Test
    void bfmlalViStillDecodesCorrectly() {
        // BFMLAL_vi (`FEAT_BF16`) hijacka o MESMO slot sizeField=DOUBLEWORD/opcode=1111 — nunca
        // deve ser confundido com `FMLAL_hb_vi` (opcode=0000). Constrói o word real a partir do
        // MESMO layout de `FMLAL_hb_vi` trocando só o opcode (bits[15:12]) para `1111`.
        int word = 0x0fc600e6 | (0b1111 << 12);
        Ir64Op op = decodeWord(new Aarch64Decoder(dev.vitorsilverio.armjitter.arch64.Aarch64Architecture.of(
                "test", dev.vitorsilverio.armjitter.arch64.Aarch64Feature.BFLOAT16)), word);
        assertEquals(Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement.class, op.getClass());
    }

    // ── Feature gating ──────────────────────────────────────────────────────────────────────────

    @Test
    void fmlalHbVRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0ec2fc20));
    }

    @Test
    void fmlallSbVRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0e05c483));
    }

    @Test
    void fmlalHbViRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x0fc600e6));
    }

    @Test
    void fmlallSbViRejectedWithoutFeature() {
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(NO_FEATURE_DECODER, 0x2f028149));
    }
}
