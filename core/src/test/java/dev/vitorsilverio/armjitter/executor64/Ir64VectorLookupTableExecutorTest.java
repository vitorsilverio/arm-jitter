package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Semântica de `LUTI2`/`LUTI4` (B19.8, `FEAT_LUT`) direto no executor (interpretador = oráculo,
/// G1) — complementa {@code Aarch64LutiDecoderTest} (decode). Cada caso usa um grupo de índices
/// (`idx`) DIFERENTE do grupo `0` e um padrão de índice distinto por grupo (`(e+g) mod
/// tableEntries`), para que uma seleção de grupo errada (bug de {@link Ir64Op.VectorLookupTable#idx})
/// produza um resultado observável, não coincidentemente igual. Valores esperados/palavras de
/// índice pré-calculados fora do teste (empacotamento bit a bit conferido por script), não deduzidos
/// do código sob teste.
class Ir64VectorLookupTableExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void luti2_1bSelectsPackedGroupByIdx() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Tabela (Rn=1): bytes 0-3 = 0x10..0x13 (únicos endereçáveis por índice de 2 bits), resto
        // sentinela 0xFF (nunca deve aparecer no resultado).
        fp.setQ(1, 0xffffffff13121110L, 0xffffffffffffffffL);
        // Rm=2: 4 grupos de 16 índices de 2 bits cada, padrão `(e+g) mod 4` — grupo 2 selecionado.
        fp.setQ(2, 0x39393939e4e4e4e4L, 0x939393934e4e4e4eL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLookupTable(false, 0, 2, 0, 1, 2));

        assertEquals(0x1110131211101312L, fp.low64(0));
        assertEquals(0x1110131211101312L, fp.high64(0));
    }

    @Test
    void luti2_1hSelectsPackedGroupByIdx() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Tabela (Rn=1): halfwords 0-3 = 0x2000..0x2003, resto sentinela 0xFFFF.
        fp.setQ(1, 0x2003200220012000L, 0xffffffffffffffffL);
        // Rm=2: 8 grupos de 8 índices de 2 bits cada, padrão `(e+g) mod 4` — grupo 5 selecionado.
        fp.setQ(2, 0x93934e4e3939e4e4L, 0x93934e4e3939e4e4L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLookupTable(false, 1, 5, 0, 1, 2));

        assertEquals(0x2000200320022001L, fp.low64(0));
        assertEquals(0x2000200320022001L, fp.high64(0));
    }

    @Test
    void luti4_1bSelectsPackedGroupByIdx() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Tabela (Rn=1): as 16 entradas de byte (índice de 4 bits usa TODAS), 0x30..0x3F.
        fp.setQ(1, 0x3736353433323130L, 0x3f3e3d3c3b3a3938L);
        // Rm=2: 2 grupos de 16 índices de 4 bits cada, padrão `(e+4*g) mod 16` — grupo 1 selecionado.
        fp.setQ(2, 0xfedcba9876543210L, 0x3210fedcba987654L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLookupTable(true, 0, 1, 0, 1, 2));

        assertEquals(0x3b3a393837363534L, fp.low64(0));
        assertEquals(0x333231303f3e3d3cL, fp.high64(0));
    }

    @Test
    void luti4_2hSelectsPackedGroupByIdxAndCrossesTableRegisterBoundary() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Tabela em 2 registradores (Rn=1 e Rn+1=2): 16 halfwords 0x4000..0x400F, metade baixa
        // (índices 0-7) em Rn, metade alta (índices 8-15) em Rn+1 — o grupo selecionado abaixo usa
        // índices dos DOIS lados (6,7 de Rn; 8-13 de Rn+1), provando a travessia de registrador.
        fp.setQ(1, 0x4003400240014000L, 0x4007400640054004L);
        fp.setQ(2, 0x400b400a40094008L, 0x400f400e400d400cL);
        // Rm=3: 4 grupos de 8 índices de 4 bits cada, padrão `(e+2*g) mod 16` — grupo 3 selecionado.
        fp.setQ(3, 0x9876543276543210L, 0xdcba9876ba987654L);

        EXECUTOR.executeOp(core, new Ir64Op.VectorLookupTable(true, 1, 3, 0, 1, 3));

        assertEquals(0x4009400840074006L, fp.low64(0));
        assertEquals(0x400d400c400b400aL, fp.high64(0));
    }
}
