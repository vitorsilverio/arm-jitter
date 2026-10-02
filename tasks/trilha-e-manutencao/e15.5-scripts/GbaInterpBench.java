import dev.vitorsilverio.gbaemu.core.GbaConsole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// E15.5 — versão longa do `InterpretedThroughputBenchTest` do gbaemu (C8), para um gate de 1%.
///
/// O teste oficial roda 50 M de ciclos por jogo (130–870 ms, com o aquecimento do HotSpot dentro):
/// a variação entre rodadas passa de 1%. Aqui cada jogo roda `ROUNDS` vezes com um console novo e
/// `CYCLE_BUDGET` maior, na mesma JVM, e vale o MELHOR tempo. Comparar ANTES × DEPOIS trocando só o
/// jar do `arm-jitter` no classpath:
///
/// `java -cp <gbaemu/target/classes>;<arm-jitter.jar>;<asm jars> GbaInterpBench.java <dir das roms>`
public final class GbaInterpBench {
    private static final long CYCLE_BUDGET = 400_000_000L;
    private static final int ROUNDS = 5;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final List<String> GAMES =
            List.of("pokefirered.gba", "smw.gba", "castlevania.gba", "metroid.gba", "mariokart.gba");

    private GbaInterpBench() {
    }

    public static void main(String[] args) throws Exception {
        Path roms = Path.of(args[0]);
        long total = 0;
        for (String game : GAMES) {
            byte[] rom = Files.readAllBytes(roms.resolve(game));
            long best = Long.MAX_VALUE;
            long checksum = 0;
            for (int round = 0; round < ROUNDS; round++) {
                GbaConsole console = GbaConsole.fromRom(rom, false);
                long start = System.nanoTime();
                console.runCycles(CYCLE_BUDGET);
                best = Math.min(best, System.nanoTime() - start);
                checksum = console.cpu().cycles() * 31 + console.cpu().programCounter();
            }
            total += best;
            System.out.printf("%-18s %6d ms  estado=%x%n", game, best / NANOS_PER_MILLI, checksum);
        }
        System.out.printf("%-18s %6d ms%n", "TOTAL", total / NANOS_PER_MILLI);
    }
}
