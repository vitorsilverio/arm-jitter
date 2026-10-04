import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// E15.10 — custo de ponta a ponta no backend interpretado A64 dos consumidores
/// (`Ir64BlockExecutor#step`, que decodifica TODA instrução executada), A/B entre classpaths. Laço de
/// 11 instruções montado pelo `as` do devkitA64: 7 de DP-imediato (`MOVZ`/`ADD`/`LSL`/`AND`/`SUB`/
/// `UBFX`), 3 de DP-registrador, 1 `B`.
///
/// Uso: `java -cp <classes> StepBench.java` — imprime `ns/step` (melhor de 15).
public class StepBench {
    private static final int[] LOOP = {
            0xd2824681, // mov  x1, #0x1234
            0x91000442, // add  x2, x2, #1
            0xd37df043, // lsl  x3, x2, #3
            0x92401c64, // and  x4, x3, #0xff
            0x8b010085, // add  x5, x4, x1
            0xca0200a6, // eor  x6, x5, x2
            0xd10010c7, // sub  x7, x6, #4
            0xaa0300e9, // orr  x9, x7, x3
            0xd3442d2a, // ubfx x10, x9, #4, #8
            0xeb08005f, // cmp  x2, x8
            0x17fffff6  // b    loop
    };
    private static final int ROUNDS = 15;
    private static final int STEPS = 2_000_000;

    /// RAM de palavras só com o laço, no endereço 0.
    private static final class Rom implements AddressSpace64 {
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return LOOP[(int) (address >>> 2)]; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    private static long round(Ir64BlockExecutor executor, Aarch64Core core) {
        long cycles = 0;
        for (int i = 0; i < STEPS; i++) {
            cycles += executor.step(core);
        }
        return cycles;
    }

    public static void main(String[] args) {
        Aarch64Core core = new Aarch64Core(new Rom());
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        core.setProgramCounter(0);
        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int r = 0; r < ROUNDS; r++) {
            long start = System.nanoTime();
            sink += round(executor, core);
            best = Math.min(best, (System.nanoTime() - start) / (double) STEPS);
        }
        System.out.printf("step interpretado A64 %.2f ns/instrução (pc final 0x%x, sink %d)%n", best, core.pc(), sink & 1);
    }
}
