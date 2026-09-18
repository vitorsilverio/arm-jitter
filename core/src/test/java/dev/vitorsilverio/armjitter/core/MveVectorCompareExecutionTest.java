package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.8 — `VCMP*`/`VCMP*_fp`/`VCMP*_scalar`/`VCMP*_fp_scalar`, fim-a-fim (decode + lift +
/// executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Cobre os itens do Aceite que exigem
/// execução: escrita de `P0` com o espaçamento de bits por largura de elemento, `VCMP` com
/// `mask == 0` NÃO abre `VPT` / `mask != 0` abre, um bloco `VPT` real ponta a ponta, `size` fixo
/// (não do bit 28) em `@vcmp_fp_scalar`, e `Rm == 15` como constante zero. Mesmo padrão de
/// {@code MveVector2opExecutionTest}/{@code MvePredicationTest}.
class MveVectorCompareExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static final int VALUE_VCMPEQ = 0xFE010F00;
    private static final int VALUE_VCMPGE = 0xFE011F00;
    private static final int VALUE_VCMPGE_FP = 0xEE311F00;
    private static final int VALUE_VCMPEQ_SCALAR = 0xFE010F40;
    private static final int VALUE_VCMPGE_FP_SCALAR_SIZE2 = 0xEE311F40;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static int quad(int value, int shiftHigh, int shiftLow) {
        int high = (value >>> 3) & 1;
        int low = value & 0x7;
        return (high << shiftHigh) | (low << shiftLow);
    }

    private static int vectorRaw(int value, int qn, int qm, int size, int mask) {
        return value | (qn << 17) | quad(qm, 5, 1) | (size << 20) | quad(mask, 22, 13);
    }

    private static int scalarRaw(int value, int qn, int rm, int size, int mask) {
        return value | (qn << 17) | rm | (size << 20) | quad(mask, 22, 13);
    }

    // ── Espaçamento de bits em P0 por largura de elemento (Aceite / Armadilha 2) ────────────────

    @Test
    void byteWidthWritesOneP0BitPerLaneDirectly() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0101_0101_0101_0101L, 0x0101_0101_0101_0101L); // Qn: todo byte = 0x01.
        // Qm: byte 0 IGUAL (0x01); todos os outros DIFERENTES (0x02).
        core.vfp().setQ(3, 0x0202_0202_0202_0201L, 0x0202_0202_0202_0202L);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 0, 0));

        core.step();

        assertEquals(0x0001, core.vpr().p0(), "só o bit do byte 0 (lane igual) fica setado");
    }

    @Test
    void halfwordWidthReplicatesTheResultAcrossTwoBits() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0005_0005_0005_0005L, 0x0005_0005_0005_0005L); // Qn: todo halfword = 5.
        // Qm: halfword 0 IGUAL (5); os outros DIFERENTES (9).
        core.vfp().setQ(3, 0x0009_0009_0009_0005L, 0x0009_0009_0009_0009L);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 1, 0));

        core.step();

        assertEquals(0x0003, core.vpr().p0(), "halfword 0 igual: bits 0 E 1 setados (replicado)");
    }

    @Test
    void wordWidthReplicatesTheResultAcrossFourBits() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0007_0000_0007L, 0x0000_0007_0000_0007L); // Qn: toda word = 7.
        // Qm: word 0 IGUAL (7); as outras DIFERENTES (2).
        core.vfp().setQ(3, 0x0000_0002_0000_0007L, 0x0000_0002_0000_0002L);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 2, 0));

        core.step();

        assertEquals(0x000F, core.vpr().p0(), "word 0 igual: bits 0-3 setados (replicado)");
    }

    // ── P0 é SOBRESCRITO por inteiro quando o VPT está inativo, NUNCA mesclado como um destino
    // vetorial comum (Armadilha 2/3) — diferente de Qd, que preserva bytes mascarados ──────────

    @Test
    void p0IsFullyOverwrittenNeverMergedLikeAnOrdinaryVectorDestination() {
        ArmCore core = newCore();
        core.vpr().setP0(0xFFFF); // "lixo" pré-existente — uma implementação que preservasse
        // bytes "mascarados" incorretamente deixaria isto sobreviver.
        // MASK01=MASK23=0 (VPT INATIVO): elementMask = 0xFFFF cheio, independente do P0 atual.
        core.vfp().setQ(2, 0x1111_1111_1111_1111L, 0x1111_1111_1111_1111L); // Qn: todo byte = 0x11.
        core.vfp().setQ(3, 0x2222_2222_2222_2222L, 0x2222_2222_2222_2222L); // Qm: todo byte = 0x22 -> EQ falso em TODA lane.
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 0, 0));

        core.step();

        assertEquals(0, core.vpr().p0(), "comparação falsa em toda lane: P0 vira 0 por inteiro, "
                + "o 0xFFFF anterior não sobrevive em NENHUM bit (P0 não é um destino que preserva)");
    }

    // ── EQ/NE independem de sinal; GE/LT/GT/LE COM sinal; CS/HI SEM sinal ───────────────────────

    @Test
    void signedGreaterEqualTreatsHighBitAsNegative() {
        ArmCore core = newCore();
        core.vfp().setElement(2, 0, 2, 0xFFFF_FFFFL); // -1 (word, com sinal).
        core.vfp().setElement(3, 0, 2, 1L);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPGE, 2, 3, 2, 0));

        core.step();

        assertEquals(0, core.vpr().p0() & 0xF, "-1 >= 1 é FALSO com sinal (word 0)");
    }

    @Test
    void unsignedCsTreatsTheSameBitsAsAVeryLargeValue() {
        ArmCore core = newCore();
        core.vfp().setElement(2, 0, 2, 0xFFFF_FFFFL); // 0xFFFFFFFF sem sinal = maior valor possível.
        core.vfp().setElement(3, 0, 2, 1L);
        // VCMPCS: mesmo layout de VCMPGE, valor 0xFE010F01.
        put32(core, CODE_BASE, vectorRaw(0xFE010F01, 2, 3, 2, 0));

        core.step();

        assertEquals(0xF, core.vpr().p0() & 0xF, "0xFFFFFFFF >= 1 é VERDADEIRO sem sinal (CS)");
    }

    // ── VCMP com mask==0 NÃO abre VPT; mask!=0 abre (Aceite) ────────────────────────────────────

    @Test
    void compareWithZeroMaskWritesP0ButNeverOpensVpt() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 1L, 0L);
        core.vfp().setQ(3, 1L, 0L);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 2, 0));

        core.step();

        assertEquals(0, core.vpr().mask01(), "mask=0: nenhum VPT estabelecido");
        assertEquals(0, core.vpr().mask23());
        assertTrue((core.vpr().p0() & 0xF) != 0, "P0 ainda é escrito por uma VCMP pura");
    }

    @Test
    void compareWithNonZeroMaskOpensVpt() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 1L, 0L);
        core.vfp().setQ(3, 1L, 0L);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 2, 0b1111));

        core.step();

        // ECI_NONE no início -> vpstMask grava mask nos DOIS campos; a etapa de avanço (que roda
        // ANTES do Vpst desta VCMP, ver Javadoc de IrOp.MveVectorCompare) não mexe em MASK01/
        // MASK23 porque eles ainda estavam zerados quando o avanço rodou.
        assertEquals(0b1111, core.vpr().mask01());
        assertEquals(0b1111, core.vpr().mask23());
    }

    // ── Bloco VPT ponta a ponta: VCMP (=VPT) seguida de uma VADD, lanes corretas mascaradas ─────

    @Test
    void aRealVptBlockMasksTheFollowingVectorInstructionCorrectly() {
        ArmCore core = newCore();
        // VPT: compara Qn=4/Qm=5 (word), só a word 0 é igual -> P0 pré-avanço = 0x000F.
        core.vfp().setElement(4, 0, 2, 7L);
        core.vfp().setElement(5, 0, 2, 7L);
        core.vfp().setElement(4, 1, 2, 7L);
        core.vfp().setElement(5, 1, 2, 8L); // word 1 diferente.
        core.vfp().setElement(4, 2, 2, 1L);
        core.vfp().setElement(5, 2, 2, 2L); // word 2 diferente.
        core.vfp().setElement(4, 3, 2, 1L);
        core.vfp().setElement(5, 3, 2, 2L); // word 3 diferente — só a word 0 é igual.
        // VPT (VCMPEQ com mask=0b1111), size=2 (word).
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 4, 5, 2, 0b1111));
        core.step();
        assertEquals(0b1111, core.vpr().mask01(), "VPT estabelecido");

        // Segunda instrução: VADD (Thumb2MveVector2opDecoder), esz=2 (word), Qd=1,Qn=2,Qm=3 — só a
        // word 0 deve somar (P0 herdado da VCMP = 0x000F antes do avanço da PRÓPRIA VADD).
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L); // Qd inicial todo-1, para ver preservação.
        core.vfp().setElement(2, 0, 2, 3L);
        core.vfp().setElement(3, 0, 2, 4L);
        core.vfp().setElement(2, 1, 2, 100L);
        core.vfp().setElement(3, 1, 2, 200L);
        // Raw de VADD (mesmo layout de MveVector2opExecutionTest): bits[27:24]=1111,
        // bits[21:20]=size, nibble8=0b1000 (VADD), bit4=0.
        int vaddRaw = (0b111 << 29) | (0 << 28) | (0b1111 << 24) | quad(1, 22, 13) | (2 << 20) | (2 << 17)
                | (0b1000 << 8) | (1 << 6) | quad(3, 5, 1);
        put32(core, CODE_BASE + 4, vaddRaw);

        core.step();

        assertEquals(7, core.vfp().element(1, 0, 2), "word 0 ativa (predicada pelo VPT): 3+4=7");
        assertEquals(0xFFFF_FFFFL, core.vfp().element(1, 1, 2), "word 1 mascarada: preserva o valor antigo");
    }

    // ── @vcmp_fp_scalar: size FIXO por linha, não o bit 28 ──────────────────────────────────────

    @Test
    void fpScalarFormWithFixedSizeTwoComparesAsBinary32NotBinary16() {
        ArmCore core = newCore();
        // Linha 499 (size=2 literal, `mve.decode` real): esta linha sempre compara como binary32,
        // independente de qualquer bit do raw — a distinção size=1 vs size=2 vem de QUAL linha
        // (top byte `1111`/740s vs `1110`/499), não de um bit lido em tempo de execução (achado
        // central da B16.2/B16.9/B16.8, "não decodifica o bit 28"). Se o executor reinterpretasse
        // os mesmos 32 bits como DOIS binary16 em vez de UM binary32, o resultado divergiria: os
        // bits baixos de 3.0f (`0x40400000`) como binary16 seriam `0x0000` (=0.0h) comparado a
        // `0x4040` (bits altos) — nada bate com "3.0f == 3.0f".
        float value = 3.0f;
        core.vfp().setElement(2, 0, 2, Integer.toUnsignedLong(Float.floatToRawIntBits(value)));
        core.setRegister(5, Float.floatToRawIntBits(value));
        int raw = scalarRaw(VALUE_VCMPGE_FP_SCALAR_SIZE2, 2, 5, 0, 0);
        put32(core, CODE_BASE, raw);

        core.step();

        assertEquals(0xF, core.vpr().p0() & 0xF, "3.0f >= 3.0f (binary32, size da LINHA) é verdadeiro");
    }

    // ── Rm == 15: "constante zero" (achado medido, diverge da leitura inicial da spec) ──────────

    @Test
    void scalarFormWithRmFifteenComparesAgainstZero() {
        ArmCore core = newCore();
        core.vfp().setElement(2, 0, 2, 0L); // Qn word0 = 0 (igual à constante zero).
        core.vfp().setElement(2, 1, 2, 1L); // Qn word1 = 1 (diferente).
        // Rm=15 é resolvido pelo DECODER como "constante zero" (bit pattern fixo do encoding),
        // nunca como leitura de um GPR real — não há registrador 15/PC a corromper aqui.
        put32(core, CODE_BASE, scalarRaw(VALUE_VCMPEQ_SCALAR, 2, 15, 2, 0));

        core.step();

        assertEquals(0xF, core.vpr().p0() & 0xF, "Qn word0 (0) == constante zero (Rm=15) é verdadeiro");
        assertEquals(0, (core.vpr().p0() >>> 4) & 0xF, "Qn word1 (1) != constante zero");
    }

    // ── Beats já executados (fora do eciMask) ficam INTOCADOS ───────────────────────────────────

    @Test
    void bitsOutsideTheEciMaskAreLeftUntouched() {
        ArmCore core = newCore();
        // ECI_A0: beat A0 (bytes 0-3, a word inteira) já executado -> eciMask = 0xFFF0 (bits 0-3
        // FORA da máscara = intocados; bits 4-15 DENTRO = recomputados).
        core.cpsr().setEci(MveVptState.ECI_A0);
        core.vpr().setP0(0x0001); // valor pré-existente: byte 0 = 1 (os demais, 0).
        // Byte 0 (fora do eciMask) DIFERENTE — compararia falso, se fosse recomputado.
        // Byte 4 (dentro do eciMask) IGUAL — deve virar 1 de verdade.
        core.vfp().setQ(2, 0x0000_0000_0000_00AAL, 0L);
        core.vfp().setQ(3, 0x0000_0000_0000_00BBL, 0L);
        core.vfp().setElement(2, 4, 0, 5);
        core.vfp().setElement(3, 4, 0, 5);
        put32(core, CODE_BASE, vectorRaw(VALUE_VCMPEQ, 2, 3, 0, 0));

        core.step();

        assertEquals(1, core.vpr().p0() & 1,
                "byte 0 (fora do eciMask, beat já executado): INTOCADO — preserva 1 mesmo a comparação real dando falso");
        assertEquals(1, (core.vpr().p0() >>> 4) & 1, "byte 4 (dentro do eciMask): recebe o resultado (igual)");
    }

    // ── Condição falsa: nada muda (mesmo padrão de MvePredicationTest) ──────────────────────────

    // ── Cobertura exaustiva dos 8 avaliadores inteiros e dos 6 FP (achado de disciplina: os testes
    // acima só exercitavam EQ/GE/CS — NE/LT/GT/LE/HI e quase toda a família FP nunca passavam por
    // nenhum teste fim-a-fim, deixando metade de `evaluateMveIntCompare`/`evaluateMveFpCompare`
    // sem cobertura real) ─────────────────────────────────────────────────────────────────────────

    private record ConditionCase(String name, int value, boolean expectedTrueForThreeVsFive) {
    }

    private static final ConditionCase[] INT_CONDITIONS = {
            new ConditionCase("VCMPEQ", 0xFE010F00, false),
            new ConditionCase("VCMPNE", 0xFE010F80, true),
            new ConditionCase("VCMPGE", 0xFE011F00, false),
            new ConditionCase("VCMPLT", 0xFE011F80, true),
            new ConditionCase("VCMPGT", 0xFE011F01, false),
            new ConditionCase("VCMPLE", 0xFE011F81, true),
            new ConditionCase("VCMPCS", 0xFE010F01, false),
            new ConditionCase("VCMPHI", 0xFE010F81, false),
    };

    @Test
    void allEightIntegerConditionsProduceTheCorrectBooleanForThreeVersusFive() {
        for (ConditionCase c : INT_CONDITIONS) {
            ArmCore core = newCore();
            core.vfp().setElement(2, 0, 2, 3L); // Qn word0 = 3.
            core.vfp().setElement(3, 0, 2, 5L); // Qm word0 = 5.
            put32(core, CODE_BASE, vectorRaw(c.value(), 2, 3, 2, 0));

            core.step();

            int expected = c.expectedTrueForThreeVsFive() ? 0xF : 0;
            assertEquals(expected, core.vpr().p0() & 0xF, c.name() + ": 3 vs 5");
        }
    }

    private static final ConditionCase[] FP_CONDITIONS = {
            new ConditionCase("VCMPEQ_fp", 0xEE310F00, false),
            new ConditionCase("VCMPNE_fp", 0xEE310F80, true),
            new ConditionCase("VCMPGE_fp", 0xEE311F00, false),
            new ConditionCase("VCMPLT_fp", 0xEE311F80, true),
            new ConditionCase("VCMPGT_fp", 0xEE311F01, false),
            new ConditionCase("VCMPLE_fp", 0xEE311F81, true),
    };

    @Test
    void allSixFloatingPointConditionsProduceTheCorrectBooleanForThreeVersusFive() {
        for (ConditionCase c : FP_CONDITIONS) {
            ArmCore core = newCore();
            core.vfp().setElement(2, 0, 2, Integer.toUnsignedLong(Float.floatToRawIntBits(3.0f)));
            core.vfp().setElement(3, 0, 2, Integer.toUnsignedLong(Float.floatToRawIntBits(5.0f)));
            put32(core, CODE_BASE, vectorRaw(c.value(), 2, 3, 0, 0));

            core.step();

            int expected = c.expectedTrueForThreeVsFive() ? 0xF : 0;
            assertEquals(expected, core.vpr().p0() & 0xF, c.name() + ": 3.0f vs 5.0f");
        }
    }

    // ── Espaçamento halfword-width com sinal negativo (esz=1) — cobre AdvSimdLanes#signExtend com
    // largura != word, único ponto do executor onde esz=1 alimenta um compare COM sinal ───────────

    @Test
    void signedHalfwordCompareSignExtendsAtTheCorrectWidth() {
        ArmCore core = newCore();
        core.vfp().setElement(2, 0, 1, 0xFFFFL); // -1 como halfword de 16 bits.
        core.vfp().setElement(3, 0, 1, 1L);
        // VCMPLT, halfword (esz=1).
        put32(core, CODE_BASE, vectorRaw(0xFE011F80, 2, 3, 1, 0));

        core.step();

        assertEquals(0x3, core.vpr().p0() & 0x3, "-1 (halfword) < 1 é verdadeiro com sinal");
    }

    @Test
    void conditionFalseSkipsEverything() {
        ArmCore core = newCore();
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG); // Z=0 -> EQ falsa.
        int vprBefore = core.vpr().value();
        // Condição no encoding Thumb-2 vem do IT block corrente, não de bits fixos do próprio
        // raw — testado via IrOp direto (mesmo padrão de MvePredicationTest#conditionalVpstSkipped...).
        boolean pcChanged = new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV8_1M_MVE)
                .executeOp(core, new dev.vitorsilverio.armjitter.ir.IrOp.MveVectorCompare(
                        dev.vitorsilverio.armjitter.advsimd.MveCompareCondition.EQ, false, 0, 0, 0, 0b1111,
                        Condition.EQ), core.programCounter());

        assertFalse(pcChanged);
        assertEquals(vprBefore, core.vpr().value(), "condição falsa: VPR intocado, VPT NÃO se abre");
    }
}
