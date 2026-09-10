package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.13 — semântica de `FEAT_FHM` direto no executor (interpretador = oráculo, G1). Núcleo em
/// {@code AdvSimdLanes#fpFusedMultiplyAddLong}/{@code fpFusedMultiplyAddLongByElement} (nasceu na
/// B13.20 para `VFML`/`VFMSL` de 32 bits); aqui só a ponte registrador↔núcleo A64 de cada uma das
/// 8 linhas + o Aceite de fusão/aliasing exigido pela task.
class Ir64VectorFpArithmeticExecutorFhmTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static long f16(float value) {
        return Float.floatToFloat16(value) & 0xFFFFL;
    }

    private static float f32At(Aarch64FpRegisters fp, int reg, int lane) {
        return Float.intBitsToFloat((int) fp.element(reg, lane, 2));
    }

    // ── FMLAL/FMLSL (bloco BAIXO) ───────────────────────────────────────────────────────────────────

    @Test
    void fmlalVector4sUsesLowBlockAndAccumulates() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, i, 1, f16(i + 1)); // Vn.4H = [1,2,3,4]
            fp.setElement(2, i, 1, f16(2));     // Vm.4H = [2,2,2,2]
        }
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(0.5f)); // acumulador pré-existente na lane0
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(true, false, false, 0, 1, 2));
        assertEquals(2.5f, f32At(fp, 0, 0), "0.5 + 1*2");
        assertEquals(4.0f, f32At(fp, 0, 1), "0 + 2*2");
        assertEquals(6.0f, f32At(fp, 0, 2), "0 + 3*2");
        assertEquals(8.0f, f32At(fp, 0, 3), "0 + 4*2");
    }

    @Test
    void fmlalVector2sZeroesHighHalfOfVd() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, f16(3));
        fp.setElement(2, 0, 1, f16(3));
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(0f)); // acumulador lane0 = 0
        // sujar só os 64 bits ALTOS (`word(1)`, `WORDS_PER_REGISTER=2` ⇒ "word" aqui é de 64 bits) —
        // sujar os 64 bits BAIXOS corromperia o próprio acumulador que a op vai ler.
        fp.setQ(0, fp.low64(0), 0xFFFF_FFFF_FFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(false, false, false, 0, 1, 2));
        assertEquals(9.0f, f32At(fp, 0, 0));
        assertEquals(0L, fp.word(1), "q=false zera os 64 bits altos de Vd (escrita destructive)");
    }

    @Test
    void fmlslVectorSubtractsProduct() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, f16(3));
        fp.setElement(2, 0, 1, f16(4));
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(20f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(false, false, true, 0, 1, 2));
        assertEquals(8f, f32At(fp, 0, 0), "20 - 3*4");
    }

    // ── FMLAL2/FMLSL2 (bloco ALTO) ───────────────────────────────────────────────────────────────────

    @Test
    void fmlal2VectorUsesHighBlock() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, i, 1, f16(100)); // bloco baixo, NÃO deve ser lido por FMLAL2
        }
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, 4 + i, 1, f16(i + 1)); // bloco alto = [1,2,3,4]
            fp.setElement(2, 4 + i, 1, f16(2));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(true, true, false, 0, 1, 2));
        assertEquals(2.0f, f32At(fp, 0, 0));
        assertEquals(4.0f, f32At(fp, 0, 1));
        assertEquals(6.0f, f32At(fp, 0, 2));
        assertEquals(8.0f, f32At(fp, 0, 3));
    }

    @Test
    void fmlsl2VectorSubtractsFromHighBlock() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 4, 1, f16(5));
        fp.setElement(2, 4, 1, f16(2));
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(100f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(true, true, true, 0, 1, 2));
        assertEquals(90f, f32At(fp, 0, 0), "100 - 5*2");
    }

    // ── Aliasing (E10): Rd==Rn e Rd==Rm ─────────────────────────────────────────────────────────────

    @Test
    void fmlalVectorAliasingRdEqualsRnReadsOriginalAccumulatorBeforeWriting() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vd/Vn é o MESMO registrador V0 (`q=false`, 2 lanes): `Vn.H[0]`/`Vn.H[1]` (16 bits cada)
        // ocupam os MESMOS 32 bits que a escrita f32 da lane0 de `Vd` vai sobrescrever — sem
        // buffer, a leitura de `Vn.H[1]` para a lane1 enxergaria bits já corrompidos pela escrita
        // da lane0. O valor da própria lane0 (acumulador inicial = reinterpretação dos bits de
        // `Vn.H[0]`/`Vn.H[1]` empacotados) não é previsível de forma simples — só a lane1 prova o
        // Aceite (ordem de leitura/escrita).
        fp.setElement(0, 0, 1, f16(2)); // Vn.H[0]
        fp.setElement(0, 1, 1, f16(3)); // Vn.H[1] — MESMOS bits que a escrita da lane0 sobrescreve
        fp.setElement(2, 0, 1, f16(10));
        fp.setElement(2, 1, 1, f16(10));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(false, false, false, 0, 0, 2));
        assertEquals(30f, f32At(fp, 0, 1),
                "3*10 — Vn.H[1] lido ANTES da escrita da lane0 corromper os bits (Rd==Rn)");
    }

    @Test
    void fmlalVectorAliasingRdEqualsRmReadsOriginalAccumulatorBeforeWriting() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, f16(2));
        fp.setElement(1, 1, 1, f16(3));
        fp.setElement(0, 0, 1, f16(10)); // Vm.H[0] == Vd, mesmos bits da escrita f32 da lane0
        fp.setElement(0, 1, 1, f16(10)); // Vm.H[1] == Vd, idem
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLong(false, false, false, 0, 1, 0));
        assertEquals(30f, f32At(fp, 0, 1),
                "3*10 — Vm.H[1] lido ANTES da escrita da lane0 corromper os bits (Rd==Rm)");
    }

    // ── Fusão (Aceite revisado, mesmo achado da B13.20 para `VFML`/`VFMSL`) ─────────────────────────
    //
    // O Aceite da task pede "um caso em que fundir × não fundir dá resultados diferentes". Isso é
    // IMPOSSÍVEL para FMLAL/FMLSL: os dois operandos alargam de `f16` (10 bits de mantissa) para
    // `float`, então o produto tem no máximo ~22 bits de mantissa — sempre representável EXATO em
    // `float` (24 bits). Sem arredondamento intermediário, `Math.fma(a,b,acc)` e `(a*b)+acc`
    // produzem SEMPRE o mesmo bit a bit. Mesmo achado documentado pela B13.20 para `VFML`/`VFMSL`
    // de 32 bits (`NeonSharedDecoder`, "fundir×não-fundir NUNCA difere") — reusa a MESMA conclusão
    // em vez de forçar um teste sintético que não pode existir. O teste abaixo prova exatamente
    // isso: para QUALQUER par de valores `f16`, os dois caminhos coincidem.

    @Test
    void fusedAndTwoStepAccumulationAlwaysCoincideForF16SourcedOperands() {
        float[] values = {1.0f, 3.0f, -7.5f, 0x1.ffcP9f, 0x1.804P-3f};
        for (float rawA : values) {
            for (float rawB : values) {
                float a = Float.float16ToFloat(Float.floatToFloat16(rawA));
                float b = Float.float16ToFloat(Float.floatToFloat16(rawB));
                float acc = 12.25f;
                assertEquals(Math.fma(a, b, acc), (a * b) + acc,
                        "produto de dois f16 alargados sempre exato em float — nunca há diferença "
                                + "entre fundir e não fundir (mesmo achado da B13.20)");
            }
        }
    }

    // ── Indexado: FMLAL_vi/FMLSL_vi/FMLAL2_vi/FMLSL2_vi ─────────────────────────────────────────────

    @Test
    void fmlalByElementReplicatesFixedElementFromLowBlock() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, i, 1, f16(i + 1)); // Vn.4H = [1,2,3,4]
        }
        fp.setElement(2, 3, 1, f16(10)); // Vm elemento fixo índice 3
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongByElement(true, false, false, 0, 1, 2, 3));
        assertEquals(10f, f32At(fp, 0, 0));
        assertEquals(20f, f32At(fp, 0, 1));
        assertEquals(30f, f32At(fp, 0, 2));
        assertEquals(40f, f32At(fp, 0, 3));
    }

    @Test
    void fmlal2ByElementReadsNFromHighBlockAndMFromFullRange() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, i, 1, f16(100)); // bloco baixo, ignorado por FMLAL2_vi
        }
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, 4 + i, 1, f16(i + 1)); // bloco alto = [1,2,3,4]
        }
        fp.setElement(2, 7, 1, f16(2)); // Vm elemento fixo índice 7 (só existe com Q real, mas
        // aqui testamos só a leitura do índice cru — decoder já garante 0-7 real)
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongByElement(true, true, false, 0, 1, 2, 7));
        assertEquals(2f, f32At(fp, 0, 0));
        assertEquals(4f, f32At(fp, 0, 1));
        assertEquals(6f, f32At(fp, 0, 2));
        assertEquals(8f, f32At(fp, 0, 3));
    }

    @Test
    void fmlslByElementSubtracts() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, f16(3));
        fp.setElement(2, 0, 1, f16(4));
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(20f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongByElement(false, false, true, 0, 1, 2, 0));
        assertEquals(8f, f32At(fp, 0, 0), "20 - 3*4");
    }

    @Test
    void fmlalByElementAliasingRdEqualsRn() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 1, f16(2)); // Vn.H[0] == Vd (lido)
        fp.setElement(2, 0, 1, f16(5));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongByElement(false, false, false, 0, 0, 2, 0));
        assertEquals(10f, f32At(fp, 0, 0), "2*5, lido ANTES da escrita (Rd==Rn)");
    }

    @Test
    void fmlalByElementAliasingRdEqualsRm() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 1, f16(2));
        fp.setElement(0, 0, 1, f16(5)); // Vm.H[0] == Vd (lido, elemento fixo)
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongByElement(false, false, false, 0, 1, 0, 0));
        assertEquals(10f, f32At(fp, 0, 0), "2*5, lido ANTES da escrita (Rd==Rm)");
    }
}
