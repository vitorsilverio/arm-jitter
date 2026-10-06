import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// E15.14 — referência do FP escalar a partir dos padrões do `a64.decode` do QEMU
/// (`target/isa-decode/a64.decode`, inventário de medição, nunca copiado para o código).
///
/// Diferenças da versão da E15.13:
///
/// - várias faixas de linhas (`a-b,c-d`): o FP escalar está espalhado pelo arquivo (2-source no meio do
///   "AdvSIMD scalar three same", `FCSEL`/3-source no meio do AdvSIMD, o resto num bloco só);
/// - os bits fixos dos FORMATOS (`@fcvt32` fixa `sf=0` e `bit15=1`) entram no padrão — a E15.12 achou que a
///   versão antiga os ignorava;
/// - `esz=%esz_hsd` (`xor_2` sobre `type`): `type=10` decodifica no `.decode` mas o `trans_` recusa
///   (`esz=0` não é tamanho FP) — tratado aqui como sem padrão.
///
/// Uso: `java QemuReference.java <a64.decode> <faixas> <oraculo.txt> <preset> [palavras.txt]`
public class QemuReference {
    record Pattern(String name, int mask, int value, boolean hsd) { }

    private static final int TYPE_SHIFT = 22;
    private static final int TYPE_RESERVED = 0b10;

    record Bits(int mask, int value) { }

    static Bits bits(String[] tokens) {
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
            return null;
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
        return new Bits(mask, value);
    }

    /// Linha lógica `n` (1-based) com as continuações (`\` no fim) coladas.
    static String logical(List<String> lines, int n) {
        StringBuilder text = new StringBuilder(lines.get(n - 1).strip());
        while (text.toString().endsWith("\\")) {
            text.setLength(text.length() - 1);
            n++;
            text.append(' ').append(lines.get(n - 1).strip());
        }
        return text.toString();
    }

    static List<Pattern> parse(Path decode, String ranges) throws Exception {
        List<String> lines = Files.readAllLines(decode);
        Map<String, Bits> formats = new HashMap<>();
        Map<String, Boolean> formatHsd = new HashMap<>();
        for (int n = 1; n <= lines.size(); n++) {
            if (lines.get(n - 1).startsWith("@") && (n == 1 || !lines.get(n - 2).strip().endsWith("\\"))) {
                String text = logical(lines, n);
                String[] tokens = text.split("\\s+");
                Bits b = bits(tokens);
                if (b != null) {
                    formats.put(tokens[0], b);
                    formatHsd.put(tokens[0], text.contains("%esz_hsd"));
                }
            }
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String range : ranges.split(",")) {
            String[] ends = range.split("-");
            int first = Integer.parseInt(ends[0]);
            int last = Integer.parseInt(ends[1]);
            for (int n = first; n <= last; n++) {
                if (n > 1 && lines.get(n - 2).strip().endsWith("\\")) {
                    continue;
                }
                String line = lines.get(n - 1).strip();
                if (line.isEmpty() || !Character.isUpperCase(line.charAt(0))) {
                    continue;
                }
                String text = logical(lines, n);
                String[] tokens = text.split("\\s+");
                Bits b = bits(tokens);
                if (b == null) {
                    throw new IllegalStateException("linha " + n + " sem 32 bits: " + line);
                }
                int mask = b.mask();
                int value = b.value();
                boolean hsd = text.contains("%esz_hsd");
                for (String token : tokens) {
                    if (token.startsWith("@")) {
                        Bits f = formats.get(token);
                        mask |= f.mask();
                        value |= f.value();
                        hsd |= formatHsd.get(token);
                    }
                }
                patterns.add(new Pattern(tokens[0], mask, value, hsd));
            }
        }
        return patterns;
    }

    static boolean matches(Pattern p, int word) {
        return (word & p.mask()) == p.value()
                && !(p.hsd() && ((word >>> TYPE_SHIFT) & 0b11) == TYPE_RESERVED);
    }

    public static void main(String[] args) throws Exception {
        List<Pattern> patterns = parse(Path.of(args[0]), args[1]);
        String preset = args[3];
        if (preset.startsWith("!")) {
            refusedWithPattern(patterns, Path.of(args[2]), preset.substring(1));
            return;
        }
        Map<String, Integer> groups = new TreeMap<>();
        List<String> words = new ArrayList<>();
        int accepted = 0;
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            String[] parts = line.split(" ", 3);
            if (!parts[0].equals(preset) || parts[2].endsWith("Exception")) {
                continue;
            }
            accepted++;
            int word = Integer.parseUnsignedInt(parts[1], 16);
            boolean known = false;
            for (Pattern p : patterns) {
                if (matches(p, word)) {
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
        if (args.length > 4) {
            Files.write(Path.of(args[4]), words);
        }
    }

    /// Inverso (preset com `!`): palavras RECUSADAS que algum padrão do QEMU casa, por nome de padrão e
    /// `type` — lacuna de cobertura (não G8).
    static void refusedWithPattern(List<Pattern> patterns, Path oracle, String preset) throws Exception {
        Map<String, Integer> groups = new TreeMap<>();
        for (String line : Files.readAllLines(oracle)) {
            String[] parts = line.split(" ", 3);
            if (!parts[0].equals(preset) || !parts[2].endsWith("Exception")) {
                continue;
            }
            int word = Integer.parseUnsignedInt(parts[1], 16);
            for (Pattern p : patterns) {
                if (matches(p, word)) {
                    groups.merge(p.name() + " type=" + bin((word >>> 22) & 3, 2), 1, Integer::sum);
                    break;
                }
            }
        }
        groups.forEach((k, v) -> System.out.printf("%7d %s%n", v, k));
    }

    /// `M/sf`, `type`, `bit21`, `bits[20:16]` e `bits[15:10]` em binário.
    static String fields(int word) {
        return "m=" + (word >>> 31) + " type=" + bin((word >>> 22) & 3, 2) + " b21=" + ((word >>> 21) & 1)
                + " op=" + bin((word >>> 16) & 31, 5) + " b15_10=" + bin((word >>> 10) & 63, 6);
    }

    static String bin(int v, int width) {
        return String.format("%" + width + "s", Integer.toBinaryString(v)).replace(' ', '0');
    }
}
