package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Vetores de instrução ponta a ponta (decode real + execução) para `Logical (shifted register)`
/// (B6.9) — as palavras usadas aqui vêm do mesmo corpus assemblado por `aarch64-none-elf-as`
/// usado em {@code Aarch64DecoderCorpusTest} (mesmos offsets/encodings), mas com valores de
/// registrador escolhidos para provar semântica (ordem invert-antes-de-combinar, `ROR`,
/// `C=0,V=0` sempre, zero-extensão em `W`), não só campos decodificados.
class Aarch64LogicalShiftedRegisterExecutorTest {
    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        AddressSpace64 memory = AddressSpace64.wrapping(raw);
        return new Aarch64Core(memory);
    }

    private static void putWord(Aarch64Core core, long address, int word) {
        core.memory().write32(address, word);
    }

    @Test
    void andShiftedLslCombinesAfterShift() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8a031041); // and x1, x2, x3, lsl #4 (offset 0x3a8 do corpus)
        core.setX(2, 0xFFFF_FFFF_FFFF_FFFFL);
        core.setX(3, 0x1L);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x10L, core.x(1), "x3 deslocado 4 bits ANTES de combinar com x2");
    }

    @Test
    void bicInvertsOperandBeforeCombining() {
        // bic x13, x14, x15, lsl #4 (offset 0x3e8) — se a inversão fosse aplicada DEPOIS da
        // combinação (bug comum, ver Armadilhas da task), o resultado seria diferente.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8a2f11cd);
        core.setX(14, 0xFFL);
        core.setX(15, 0x1L); // shiftType=lsl, sa=4 -> shifted=0x10; invertido -> ~0x10

        new Ir64BlockExecutor().step(core);

        assertEquals(0xEFL, core.x(13), "bit4 de x14 deve ser mascarado pelo NOT do operando deslocado");
    }

    @Test
    void rorShiftWrapsAroundFullWidth() {
        // and x1, x2, x3, ror #4 (offset 0x3b4) — ROR só existe nesta forma (RESERVADO em
        // AluShiftedRegister, mesma checagem já feita pelo decoder).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8ac31041);
        core.setX(2, 0xFFFF_FFFF_FFFF_FFFFL);
        core.setX(3, 0x1L); // ROR #4 de 0x1 em 64 bits -> 0x1000000000000000

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1000_0000_0000_0000L, core.x(1));
    }

    @Test
    void andsAlwaysClearsCarryAndOverflow() {
        // ands x10, x11, x12, lsl #16 (offset 0x3d8) — mesmo com operandos "todo-uns" (que
        // pareceriam gerar carry numa soma), C e V nunca são calculados para operação lógica.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xea0c416a);
        core.setX(11, 0xFFFF_FFFF_FFFF_FFFFL);
        core.setX(12, 0xFFFF_FFFF_FFFF_FFFFL);
        core.pstate().setNzcv(false, false, true, true); // sentinela: C/V pré-existentes em 1

        new Ir64BlockExecutor().step(core);

        assertEquals(0xFFFF_FFFF_FFFF_0000L, core.x(10));
        assertTrue(core.pstate().negative(), "bit mais alto do resultado está setado");
        assertFalse(core.pstate().zero());
        assertFalse(core.pstate().carry(), "operação lógica nunca seta C");
        assertFalse(core.pstate().overflow(), "operação lógica nunca seta V");
    }

    @Test
    void movRegisterAliasCopiesSourceRegister() {
        // mov x21, x0 (0xaa0003f5) — o vetor literal da F11, alias puro de `orr x21, xzr, x0`.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xaa0003f5);
        core.setX(0, 0x1234_5678_9ABC_DEF0L);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1234_5678_9ABC_DEF0L, core.x(21));
    }

    @Test
    void mvnRegisterAliasInvertsSourceRegister() {
        // mvn x22, x1 (0xaa2103f6) — alias de `orn x22, xzr, x1`.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xaa2103f6);
        core.setX(1, 0x0L);

        new Ir64BlockExecutor().step(core);

        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, core.x(22));
    }

    @Test
    void narrowFormZeroExtendsResultAndIgnoresHighGarbage() {
        // and w1, w2, w3, lsl #4 (0x0a031041, offset 0x428) — só os 32 bits baixos de w2/w3
        // participam; o resultado é zero-estendido para os 64 bits de x1.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x0a031041);
        core.setX(2, 0xDEAD_BEEF_FFFF_FFFFL); // w2 = 0xFFFFFFFF, lixo alto ignorado
        core.setX(3, 0x1234_5678_0000_0001L); // w3 = 0x1, lixo alto ignorado

        new Ir64BlockExecutor().step(core);

        assertEquals(0x10L, core.x(1), "resultado zero-estendido, sem vazamento dos 32 bits altos");
    }
}
