package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `LD1`-`LD4`/`ST1`-`ST4`/`LD1R`-`LD4R` (AdvSIMD load/store multiple/single structures, B8.6).
/// Diferente de {@code Aarch64DecoderCorpusTest} (que compartilha um único `corpus.bin` crescido
/// por várias tasks), este arquivo usa um corpus PRÓPRIO — os opcodes abaixo vêm de
/// `aarch64-none-elf-as`/`objdump` reais (devkitA64, disponível nesta sessão), montados a partir
/// de `LD1 {v0.16b-v3.16b}, [x0]` etc.; cada `@Test` documenta a linha exata do `objdump` de
/// origem. Os valores esperados vêm da semântica do MNEMÔNICO real (não recalculados a partir dos
/// bits pelo próprio decoder), para servir de checagem independente.
class Aarch64AdvSimdLoadStoreDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op.VectorLoadStoreMultiple multiple(int word) {
        return (Ir64Op.VectorLoadStoreMultiple) decodeWord(word);
    }

    private static Ir64Op.VectorLoadStoreSingle single(int word) {
        return (Ir64Op.VectorLoadStoreSingle) decodeWord(word);
    }

    private static Ir64Op.VectorLoadSingleReplicate replicate(int word) {
        return (Ir64Op.VectorLoadSingleReplicate) decodeWord(word);
    }

    // ── Multiple structures ─────────────────────────────────────────────────────────────────────

    @Test
    void ld4Quad16b() {
        // 4c400000: ld4 {v0.16b-v3.16b}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c400000);
        assertTrue(op.load());
        assertEquals(0, op.rt());
        assertEquals(0, op.rn());
        assertEquals(-1, op.rm());
        assertTrue(op.q());
        assertFalse(op.postIndex());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(4, op.selem());
    }

    @Test
    void st4Quad16b() {
        // 4c000000: st4 {v0.16b-v3.16b}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c000000);
        assertFalse(op.load());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(4, op.selem());
    }

    @Test
    void ld1FourRegisters16b() {
        // 4c402000: ld1 {v0.16b-v3.16b}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c402000);
        assertTrue(op.load());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(4, op.rpt());
        assertEquals(1, op.selem());
    }

    @Test
    void ld3Quad8h() {
        // 4c404400: ld3 {v0.8h-v2.8h}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c404400);
        assertTrue(op.load());
        assertEquals(1, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(3, op.selem());
    }

    @Test
    void ld1ThreeRegisters8h() {
        // 4c406400: ld1 {v0.8h-v2.8h}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c406400);
        assertEquals(1, op.elementSizeLog2());
        assertEquals(3, op.rpt());
        assertEquals(1, op.selem());
    }

    @Test
    void ld2Quad4s() {
        // 4c408800: ld2 {v0.4s-v1.4s}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c408800);
        assertEquals(2, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(2, op.selem());
    }

    @Test
    void ld1TwoRegisters4s() {
        // 4c40a800: ld1 {v0.4s-v1.4s}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c40a800);
        assertEquals(2, op.elementSizeLog2());
        assertEquals(2, op.rpt());
        assertEquals(1, op.selem());
    }

    @Test
    void ld1OneRegister2d() {
        // 4c407c00: ld1 {v0.2d}, [x0] — elementSizeLog2=3 (doubleword) exige Q=1 ou selem=1; aqui
        // selem=1, então é válido mesmo sem checar Q (B8.6, guarda "size==3 && !q && selem!=1").
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c407c00);
        assertTrue(op.load());
        assertTrue(op.q());
        assertEquals(3, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(1, op.selem());
    }

    @Test
    void st1OneRegister2d() {
        // 4c007c00: st1 {v0.2d}, [x0]
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c007c00);
        assertFalse(op.load());
        assertEquals(3, op.elementSizeLog2());
    }

    @Test
    void ld1PostIndexImmediate16bytes() {
        // 4cdf7000: ld1 {v0.16b}, [x0], #16 — Rm=11111 é o sentinela de imediato (decoder resolve
        // para -1, nunca lê X31 como registrador real).
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4cdf7000);
        assertTrue(op.postIndex());
        assertEquals(-1, op.rm());
        assertTrue(op.q());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(1, op.selem());
    }

    @Test
    void ld4PostIndexImmediate32bytes() {
        // 0cdf0000: ld4 {v0.8b-v3.8b}, [x0], #32
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x0cdf0000);
        assertTrue(op.postIndex());
        assertEquals(-1, op.rm());
        assertFalse(op.q());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.rpt());
        assertEquals(4, op.selem());
    }

    @Test
    void ld1PostIndexRegister() {
        // 4cc17000: ld1 {v0.16b}, [x0], x1
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4cc17000);
        assertTrue(op.postIndex());
        assertEquals(1, op.rm());
    }

    @Test
    void st2PostIndexRegister4s() {
        // 4c818800: st2 {v0.4s-v1.4s}, [x0], x1
        Ir64Op.VectorLoadStoreMultiple op = multiple(0x4c818800);
        assertFalse(op.load());
        assertTrue(op.postIndex());
        assertEquals(1, op.rm());
        assertEquals(2, op.elementSizeLog2());
        assertEquals(2, op.selem());
    }

    // ── Single structure (sem replicar) ─────────────────────────────────────────────────────────

    @Test
    void ld1ByteIndex0() {
        // 0d400000: ld1 {v0.b}[0], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d400000);
        assertTrue(op.load());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.selem());
        assertEquals(0, op.index());
        assertFalse(op.postIndex());
    }

    @Test
    void ld1ByteIndex15() {
        // 4d401c00: ld1 {v0.b}[15], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x4d401c00);
        assertEquals(0, op.elementSizeLog2());
        assertEquals(15, op.index());
    }

    @Test
    void st1ByteIndex7() {
        // 0d001c00: st1 {v0.b}[7], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d001c00);
        assertFalse(op.load());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(7, op.index());
    }

    @Test
    void ld1HalfIndex0() {
        // 0d404000: ld1 {v0.h}[0], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d404000);
        assertEquals(1, op.elementSizeLog2());
        assertEquals(0, op.index());
    }

    @Test
    void ld1HalfIndex7() {
        // 4d405800: ld1 {v0.h}[7], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x4d405800);
        assertEquals(1, op.elementSizeLog2());
        assertEquals(7, op.index());
    }

    @Test
    void st1HalfIndex3() {
        // 0d005800: st1 {v0.h}[3], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d005800);
        assertFalse(op.load());
        assertEquals(1, op.elementSizeLog2());
        assertEquals(3, op.index());
    }

    @Test
    void ld1WordIndex0() {
        // 0d408000: ld1 {v0.s}[0], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d408000);
        assertEquals(2, op.elementSizeLog2());
        assertEquals(0, op.index());
    }

    @Test
    void ld1WordIndex3() {
        // 4d409000: ld1 {v0.s}[3], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x4d409000);
        assertEquals(2, op.elementSizeLog2());
        assertEquals(3, op.index());
    }

    @Test
    void st1WordIndex1() {
        // 0d009000: st1 {v0.s}[1], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d009000);
        assertFalse(op.load());
        assertEquals(2, op.elementSizeLog2());
        assertEquals(1, op.index());
    }

    @Test
    void ld1DoublewordIndex0() {
        // 0d408400: ld1 {v0.d}[0], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d408400);
        assertEquals(3, op.elementSizeLog2());
        assertEquals(0, op.index());
    }

    @Test
    void ld1DoublewordIndex1() {
        // 4d408400: ld1 {v0.d}[1], [x0] — Q reaproveitado DIRETAMENTE como índice.
        Ir64Op.VectorLoadStoreSingle op = single(0x4d408400);
        assertEquals(3, op.elementSizeLog2());
        assertEquals(1, op.index());
    }

    @Test
    void st1DoublewordIndex0() {
        // 0d008400: st1 {v0.d}[0], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d008400);
        assertFalse(op.load());
        assertEquals(3, op.elementSizeLog2());
        assertEquals(0, op.index());
    }

    @Test
    void ld2ByteTwoRegistersIndex4() {
        // 0d601000: ld2 {v0.b-v1.b}[4], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d601000);
        assertEquals(0, op.elementSizeLog2());
        assertEquals(2, op.selem());
        assertEquals(4, op.index());
    }

    @Test
    void ld3HalfThreeRegistersIndex2() {
        // 0d407000: ld3 {v0.h-v2.h}[2], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d407000);
        assertEquals(1, op.elementSizeLog2());
        assertEquals(3, op.selem());
        assertEquals(2, op.index());
    }

    @Test
    void ld4WordFourRegistersIndex1() {
        // 0d60b000: ld4 {v0.s-v3.s}[1], [x0]
        Ir64Op.VectorLoadStoreSingle op = single(0x0d60b000);
        assertEquals(2, op.elementSizeLog2());
        assertEquals(4, op.selem());
        assertEquals(1, op.index());
    }

    @Test
    void ld1SinglePostIndexImmediate() {
        // 0ddf8000: ld1 {v0.s}[0], [x0], #4
        Ir64Op.VectorLoadStoreSingle op = single(0x0ddf8000);
        assertTrue(op.postIndex());
        assertEquals(-1, op.rm());
        assertEquals(2, op.elementSizeLog2());
        assertEquals(0, op.index());
    }

    @Test
    void ld1SinglePostIndexRegister() {
        // 0dc18000: ld1 {v0.s}[0], [x0], x1
        Ir64Op.VectorLoadStoreSingle op = single(0x0dc18000);
        assertTrue(op.postIndex());
        assertEquals(1, op.rm());
    }

    // ── Load single structure, replicate ────────────────────────────────────────────────────────

    @Test
    void ld1rQuad16b() {
        // 4d40c000: ld1r {v0.16b}, [x0]
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x4d40c000);
        assertEquals(0, op.rt());
        assertEquals(0, op.rn());
        assertEquals(-1, op.rm());
        assertTrue(op.q());
        assertFalse(op.postIndex());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.selem());
    }

    @Test
    void ld1r8b() {
        // 0d40c000: ld1r {v0.8b}, [x0]
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x0d40c000);
        assertFalse(op.q());
        assertEquals(0, op.elementSizeLog2());
        assertEquals(1, op.selem());
    }

    @Test
    void ld2r4h() {
        // 0d60c400: ld2r {v0.4h-v1.4h}, [x0]
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x0d60c400);
        assertFalse(op.q());
        assertEquals(1, op.elementSizeLog2());
        assertEquals(2, op.selem());
    }

    @Test
    void ld3r2s() {
        // 0d40e800: ld3r {v0.2s-v2.2s}, [x0]
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x0d40e800);
        assertFalse(op.q());
        assertEquals(2, op.elementSizeLog2());
        assertEquals(3, op.selem());
    }

    @Test
    void ld4r1d() {
        // 0d60ec00: ld4r {v0.1d-v3.1d}, [x0]
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x0d60ec00);
        assertFalse(op.q());
        assertEquals(3, op.elementSizeLog2());
        assertEquals(4, op.selem());
    }

    @Test
    void ld1rPostIndexImmediate2d() {
        // 4ddfcc00: ld1r {v0.2d}, [x0], #8
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x4ddfcc00);
        assertTrue(op.postIndex());
        assertEquals(-1, op.rm());
        assertTrue(op.q());
        assertEquals(3, op.elementSizeLog2());
    }

    @Test
    void ld1rPostIndexRegister2d() {
        // 4dc1cc00: ld1r {v0.2d}, [x0], x1
        Ir64Op.VectorLoadSingleReplicate op = replicate(0x4dc1cc00);
        assertTrue(op.postIndex());
        assertEquals(1, op.rm());
    }

    // ── B8.13 fechou o gap: LDR/STR/LDP/STP escalar SIMD&FP agora decodifica (ver
    // Aarch64FpLoadStoreDecoderTest para a suíte completa) — este teste era uma recusa
    // explícita (G8) datada de quando só a família estruturada da B8.6 existia; atualizado em
    // vez de apagado, mesmo padrão de B10.6b/c ao preencher um `unsupported` documentado. ────────

    @Test
    void scalarFpLoadNowDecodesAsFpLoad64() {
        // fd400000: ldr d0, [x0] (LDR imediato, SIMD&FP, unsigned offset) — B8.13.
        Ir64Op.FpLoad64 op = (Ir64Op.FpLoad64) decodeWord(0xfd400000);
        assertEquals(0, op.vt());
        assertEquals(0, op.rn());
        assertEquals(dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.DOUBLE, op.size());
    }
}
