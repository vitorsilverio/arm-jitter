import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/// E17 item 1 — números da medição sobre `cruzado.tsv` (`objdump-mnemonicos.sh`). Colunas: grupo, nome#occ,
/// palavra, assinatura, colunas, mnemônico, texto do objdump.
///
/// Imprime: linhas medidas; palavras aceitas que o objdump chama de `undefined`/`.inst` (G8); linhas cujo
/// mnemônico do objdump ≠ base do nome-QEMU (alias OU misdecode — revisão); linhas com mais de uma
/// assinatura; assinaturas produzidas por mais de uma linha. Detalhe de cada lista em `<dir>/*.txt`.
///
/// Uso: `java SignatureStats.java <cruzado.tsv> <dir-saida>`
public class SignatureStats {
    public static void main(String[] args) throws Exception {
        Map<String, Set<String>> signaturesByLine = new TreeMap<>();
        Map<String, Set<String>> mnemonicsByLine = new TreeMap<>();
        Map<String, Set<String>> linesBySignature = new TreeMap<>();
        Map<String, Set<String>> undefinedByLine = new TreeMap<>();
        Map<String, Set<String>> mismatchByLine = new TreeMap<>();
        for (String raw : Files.readAllLines(Path.of(args[0]))) {
            String[] f = raw.split("\t", -1);
            String line = f[0].replace(".decode", "") + ":" + f[1];
            String word = f[2];
            String signature = f[3];
            String mnemonic = f[5];
            signaturesByLine.computeIfAbsent(line, k -> new TreeSet<>()).add(signature);
            mnemonicsByLine.computeIfAbsent(line, k -> new TreeSet<>()).add(mnemonic);
            linesBySignature.computeIfAbsent(signature, k -> new TreeSet<>()).add(line);
            if (mnemonic.isEmpty() || mnemonic.equals("undefined") || mnemonic.startsWith(".inst")) {
                undefinedByLine.computeIfAbsent(line, k -> new TreeSet<>()).add(word + " " + signature);
            } else if (!base(f[1]).equals(normalize(mnemonic))) {
                mismatchByLine.computeIfAbsent(line, k -> new TreeSet<>())
                        .add(normalize(mnemonic) + " ← " + signature + " (" + word + ": " + f[6] + ")");
            }
        }
        Path out = Path.of(args[1]);
        dump(out.resolve("undefined.txt"), undefinedByLine);
        dump(out.resolve("mnemonico-diferente.txt"), mismatchByLine);
        Map<String, Set<String>> multiSig = new TreeMap<>();
        signaturesByLine.forEach((k, v) -> { if (v.size() > 1) multiSig.put(k, v); });
        dump(out.resolve("linha-varias-assinaturas.txt"), multiSig);
        Map<String, Set<String>> shared = new TreeMap<>();
        linesBySignature.forEach((k, v) -> { if (v.size() > 1) shared.put(k, v); });
        dump(out.resolve("assinatura-varias-linhas.txt"), shared);
        // assinatura compartilhada por linhas de mnemônico-base DIFERENTE = assinatura que não distingue
        Map<String, Set<String>> ambiguous = new TreeMap<>();
        shared.forEach((k, v) -> {
            Set<String> bases = new TreeSet<>();
            v.forEach(l -> bases.add(base(l.substring(l.indexOf(':') + 1))));
            if (bases.size() > 1) ambiguous.put(k, bases);
        });
        dump(out.resolve("assinatura-ambigua.txt"), ambiguous);

        System.out.printf(Locale.ROOT, "linhas com palavra aceita: %d%n", signaturesByLine.size());
        System.out.printf(Locale.ROOT, "linhas com palavra aceita que o objdump chama de undefined: %d (%d palavras)%n",
                undefinedByLine.size(), undefinedByLine.values().stream().mapToInt(Set::size).sum());
        System.out.printf(Locale.ROOT, "linhas com mnemônico do objdump ≠ nome-QEMU: %d%n", mismatchByLine.size());
        System.out.printf(Locale.ROOT, "linhas com mais de uma assinatura: %d%n", multiSig.size());
        System.out.printf(Locale.ROOT, "assinaturas distintas: %d · compartilhadas por >1 linha: %d · por mnemônicos diferentes: %d%n",
                linesBySignature.size(), shared.size(), ambiguous.size());
    }

    /// `FADDP_v#2` → `faddp`; `B_cond#1` → `b`.
    static String base(String nameWithOccurrence) {
        String name = nameWithOccurrence.replaceAll("#\\d+$", "");
        int underscore = name.indexOf('_');
        return (underscore > 0 ? name.substring(0, underscore) : name).toLowerCase(Locale.ROOT);
    }

    /// `b.eq` → `b`.
    static String normalize(String mnemonic) {
        int dot = mnemonic.indexOf('.');
        return dot > 0 ? mnemonic.substring(0, dot) : mnemonic;
    }

    static void dump(Path file, Map<String, Set<String>> map) throws Exception {
        StringBuilder text = new StringBuilder();
        map.forEach((k, v) -> {
            text.append(k).append('\n');
            v.forEach(item -> text.append("    ").append(item).append('\n'));
        });
        Files.writeString(file, text);
    }
}
