import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;

/// E15.9c — variante do `e15.9b-scripts/AdvSimdPrefixOracle.java` com `Rm` (bits[20:16])
/// ENUMERADO em vez de sorteado: vários slots do espaço usam `Rm` como opcode (two-register misc,
/// across lanes, FP16, `immh:immb` do shift, `imm5` do copy) e 3 sorteios não os cobrem. Mesmos 4
/// prefixos e bits 31/30/29/23/22/21/15:10 enumerados; `Rn`/`Rd` sorteados (semente fixa); só o
/// preset "todas as features menos SME". Saída no formato que `e15.9b-scripts/objdump-residuo.sh`
/// lê.
///
/// Uso: `java -cp <classes> AdvSimdRmOracle.java <saida.txt>`
public class AdvSimdRmOracle {
    private static final int[] PREFIXES = {0b0_1110, 0b1_1110, 0b0_1111, 0b1_1111};

    /// Memória de uma palavra só: toda leitura de 32 bits devolve a instrução sob teste.
    private record OneWord(int word) implements AddressSpace64 {
        @Override public int read8(long address) { throw new UnsupportedOperationException(); }
        @Override public int read16(long address) { throw new UnsupportedOperationException(); }
        @Override public int read32(long address) { return word; }
        @Override public void write8(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(long address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(long address, int value) { throw new UnsupportedOperationException(); }
    }

    public static void main(String[] args) throws Exception {
        SplittableRandom random = new SplittableRandom(0xE159CL);
        Aarch64Architecture all = Aarch64Architecture.of("all", Arrays.stream(Aarch64Feature.values())
                .filter(f -> !f.name().startsWith("SME") && !f.name().startsWith("SCALABLE_MATRIX"))
                .toArray(Aarch64Feature[]::new));
        Aarch64Decoder decoder = new Aarch64Decoder(all);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            for (int prefix : PREFIXES) {
                for (int high = 0; high < 8; high++) { // bits 31, 30, 29
                    for (int size = 0; size < 8; size++) { // bits 23, 22, 21
                        for (int rm = 0; rm < 32; rm++) { // bits 20:16
                            for (int opcode = 0; opcode < 64; opcode++) { // bits 15:10
                                int regs = random.nextInt(1 << 10);
                                int word = (high << 29) | (prefix << 24) | (size << 21) | (rm << 16)
                                        | (opcode << 10) | regs;
                                String result;
                                try {
                                    result = String.valueOf(decoder.decode(new OneWord(word), 0x1000L));
                                } catch (RuntimeException e) {
                                    result = e.getClass().getSimpleName();
                                }
                                out.printf("all %08x %s%n", word, result);
                            }
                        }
                    }
                }
            }
        }
    }
}
