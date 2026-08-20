package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Vetores de instrução ponta a ponta (decode real + execução) para `LSLV`/`LSRV`/`ASRV`/`RORV`
/// (B6.11) — as palavras usadas aqui vêm do mesmo corpus assemblado por `aarch64-none-elf-as`
/// usado em {@code Aarch64DecoderCorpusTest} (mesmos offsets/encodings), mas com valores de
/// registrador escolhidos para provar semântica (quantidade tomada de `Rm` EM TEMPO DE EXECUÇÃO,
/// mascarada `mod` largura, `ROR` completo, zero-extensão em `W`, `NZCV` intocado).
class Aarch64ShiftVariableExecutorTest {
    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        AddressSpace64 memory = AddressSpace64.wrapping(raw);
        return new Aarch64Core(memory);
    }

    private static void putWord(Aarch64Core core, long address, int word) {
        core.memory().write32(address, word);
    }

    @Test
    void lslShiftAmountComesFromRegisterAtRuntime() {
        // lsl x2, x2, x3 (0x9ac32042) — o vetor LITERAL da F11 (0x38fd4 do kernel8.img real):
        // a quantidade NÃO está no encoding, vem do valor de x3 em tempo de execução.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9ac32042);
        core.setX(2, 0x1L);
        core.setX(3, 8L);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x100L, core.x(2), "quantidade de deslocamento lida de x3, não do encoding");
    }

    @Test
    void lslShiftAmountIsMaskedModuloWidth() {
        // lsr x4, x5, x6 (0x9ac624a4, offset 0x454 do corpus) — x6=70 deveria ser mascarado para
        // 70 mod 64 = 6 (ARM DDI 0487, pseudocódigo de LSRV: "shift_amount = X[m] MOD 64").
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9ac624a4);
        long value = 1L << 62;
        core.setX(5, value);
        core.setX(6, 70L);

        new Ir64BlockExecutor().step(core);

        assertEquals(value >>> 6, core.x(4), "70 mod 64 = 6, não deslocamento por 70 (indefinido em Java)");
    }

    @Test
    void rorShiftWrapsAroundFullWidth() {
        // ror x10, x11, x12 (0x9acc2d6a, offset 0x45c do corpus).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9acc2d6a);
        core.setX(11, 0x1L);
        core.setX(12, 4L); // ROR #4 de 0x1 em 64 bits -> 0x1000000000000000

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1000_0000_0000_0000L, core.x(10));
    }

    @Test
    void narrowFormMasksAmountModulo32AndZeroExtendsResult() {
        // lsl w13, w14, w15 (0x1acf21cd, offset 0x460 do corpus) — mod 32, não mod 64, e o
        // resultado é zero-estendido para os 64 bits de x13.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x1acf21cd);
        core.setX(14, 0xDEAD_BEEF_0000_0001L); // w14 = 0x1, lixo alto ignorado
        core.setX(15, 0x1234_5678_0000_0024L); // w15 = 36 (mod 32 = 4), lixo alto ignorado

        new Ir64BlockExecutor().step(core);

        assertEquals(0x10L, core.x(13), "36 mod 32 = 4; resultado zero-estendido");
    }

    @Test
    void neverAffectsNzcv() {
        // asr x7, x8, x9 (0x9ac92907, offset 0x458 do corpus) — LSLV/LSRV/ASRV/RORV nunca tocam
        // NZCV, ao contrário das formas `S` de ALU.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9ac92907);
        core.setX(8, -1L);
        core.setX(9, 4L);
        core.pstate().setNzcv(true, true, true, true); // sentinela: todos setados antes

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(7), "ASR de -1 permanece -1 (sinal preservado)");
        assertTrue(core.pstate().negative(), "sentinela N deveria continuar intocada");
        assertTrue(core.pstate().zero(), "sentinela Z deveria continuar intocada");
        assertTrue(core.pstate().carry(), "sentinela C deveria continuar intocada");
        assertTrue(core.pstate().overflow(), "sentinela V deveria continuar intocada");
    }
}
