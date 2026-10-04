import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// E15.11 — referência do espaço branch/exceção/sistema a partir dos padrões do `a64.decode` do QEMU
/// (`target/isa-decode/a64.decode`, inventário de medição, nunca copiado para o código). O `objdump`
/// não serve de oráculo em `op0=00`: ele imprime qualquer palavra não alocada ali como
/// `msr s0_<op1>_c<n>_c<m>_<op2>, xN` em vez de `undefined`.
///
/// Lê a saída do `BranchSystemOracle` e lista, para um preset, as palavras ACEITAS pelo decoder que
/// nenhum padrão do QEMU da faixa de linhas dada casa (G8), agrupadas por classe de op e campos.
///
/// Uso: `java QemuReference.java <a64.decode> <primeira> <ultima> <oraculo.txt> <preset> [palavras.txt]`
public class QemuReference {
    record Pattern(String name, int mask, int value) { }

    static List<Pattern> parse(Path decode, int first, int last) throws Exception {
        List<String> lines = Files.readAllLines(decode);
        List<Pattern> patterns = new ArrayList<>();
        for (int n = first; n <= last; n++) {
            String line = lines.get(n - 1).strip();
            // E15.12: linha de continuação (`\` no fim da anterior) não é padrão.
            if (n > first && lines.get(n - 2).strip().endsWith("\\")) {
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("%") || line.startsWith("&")
                    || line.startsWith("@") || line.startsWith("{") || line.startsWith("}")
                    || line.startsWith("[") || line.startsWith("]")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            StringBuilder bits = new StringBuilder();
            for (int t = 1; t < tokens.length && bits.length() < 32; t++) {
                String token = tokens[t];
                if (token.startsWith("#")) {
                    break;
                }
                if (token.matches("[01.\\-]+")) {
                    bits.append(token);
                } else if (token.matches("[a-z0-9_]+:s?\\d+")) {
                    bits.append(".".repeat(Integer.parseInt(token.replaceAll(".*:s?", ""))));
                }
            }
            if (bits.length() != 32) {
                throw new IllegalStateException("linha " + n + " sem 32 bits: " + line);
            }
            int mask = 0;
            int value = 0;
            for (int i = 0; i < 32; i++) {
                char c = bits.charAt(i);
                int bit = 1 << (31 - i);
                if (c == '0' || c == '1') {
                    mask |= bit;
                    if (c == '1') {
                        value |= bit;
                    }
                }
            }
            patterns.add(new Pattern(tokens[0], mask, value));
        }
        return patterns;
    }

    public static void main(String[] args) throws Exception {
        List<Pattern> patterns = parse(Path.of(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        String preset = args[4];
        Map<String, Integer> groups = new TreeMap<>();
        List<String> words = new ArrayList<>();
        int accepted = 0;
        for (String line : Files.readAllLines(Path.of(args[3]))) {
            String[] parts = line.split(" ", 3);
            // `endsWith`: `ExceptionReturn[]` é op aceita, não exceção.
            if (!parts[0].equals(preset) || parts[2].endsWith("Exception")) {
                continue;
            }
            accepted++;
            int word = Integer.parseUnsignedInt(parts[1], 16);
            boolean known = false;
            for (Pattern p : patterns) {
                if ((word & p.mask()) == p.value()) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                String op = parts[2].replaceAll("\\[.*", "");
                words.add(parts[1] + " " + parts[2]);
                groups.merge(op + " " + fields(word), 1, Integer::sum);
            }
        }
        System.out.println("padrões: " + patterns.size() + " · aceitas: " + accepted + " · sem padrão QEMU: " + words.size());
        groups.forEach((k, v) -> System.out.printf("%7d %s%n", v, k));
        if (args.length > 5) {
            Files.write(Path.of(args[5]), words);
        }
    }

    /// E15.12: campos da classe load/store — `bits[31:21]` em binário e `bits[15:10]`.
    static String fields(int word) {
        String top = String.format("%11s", Integer.toBinaryString(word >>> 21)).replace(' ', '0');
        String mid = String.format("%6s", Integer.toBinaryString((word >>> 10) & 0x3F)).replace(' ', '0');
        return "t=" + top + " m=" + mid;
    }
}
