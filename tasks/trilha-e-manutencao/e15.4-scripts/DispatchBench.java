import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;

/// Medição avulsa da E15.4: `executeBlock` sobre um bloco A64 com 7 tipos de op distintos (o call
/// site de dispatch fica megamórfico), ANTES × DEPOIS do `Ir64Op#execute`.
public final class DispatchBench {
    private static final long START = 0x100;
    private static final int[] PROGRAM = {
            0x91000400, // add  x0, x0, #1          Alu64
            0xCA000021, // eor  x1, x1, x0          LogicalShiftedRegister
            0x9B010C03, // madd x3, x0, x1, x3      MultiplyAccumulate
            0xD343FC05, // lsr  x5, x0, #3          Bitfield
            0x9A811006, // csel x6, x0, x1, ne      ConditionalSelect
            0x8B050CC7, // add  x7, x6, x5, lsl #3  AluShiftedRegister
            0xF1000442, // subs x2, x2, #1          Alu64 (flags)
            0x54FFFF21, // b.ne START               Branch64
    };
    private static final int ITERATIONS = 40_000_000;
    private static final int ROUNDS = 8;

    public static void main(String[] args) {
        TestAddressSpace memory = new TestAddressSpace(0x1000);
        for (int i = 0; i < PROGRAM.length; i++) {
            memory.put32((int) START + 4 * i, PROGRAM[i]);
        }
        AddressSpace64 bus = AddressSpace64.wrapping(memory);
        Ir64Block block = new StandardIr64BlockLifter().lift(bus, START, 64);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Aarch64Core core = new Aarch64Core(bus);
        long best = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            core.setProgramCounter(START);
            core.setX(2, ITERATIONS);
            long t0 = System.nanoTime();
            long cycles = 0;
            for (int i = 0; i < ITERATIONS; i++) {
                cycles += executor.executeBlock(core, block);
            }
            long elapsed = System.nanoTime() - t0;
            if (core.pc() != START + 4L * PROGRAM.length || core.x(2) != 0) {
                throw new IllegalStateException("laço não terminou: pc=" + Long.toHexString(core.pc()));
            }
            best = Math.min(best, elapsed);
            System.out.printf("round %d: %d ms (%.2f ns/op) cycles=%d x3=%x%n", round, elapsed / 1_000_000,
                    elapsed / (double) cycles, cycles, core.x(3));
        }
        System.out.printf("BEST %.3f ns/op (ops=%d)%n", best / (double) ((long) ITERATIONS * PROGRAM.length),
                block.operationsArray().length);
    }
}
