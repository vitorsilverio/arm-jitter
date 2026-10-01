package dev.vitorsilverio.armjitter.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Leitor do formato **decodetree** (`target/arm/tcg/*.decode` do QEMU) — usado por
/// {@link IsaCoverageReport} como INVENTÁRIO de instruções ARM por arquitetura.
///
/// O QEMU é GPL e o arm-jitter é BSD-3-Clause, então **nenhum arquivo `.decode` é versionado neste
/// repositório**: o relatório os baixa para `target/` em tempo de execução (ver o Javadoc de
/// {@link IsaCoverageReport}). O que este leitor extrai — mnemônico e quais bits do encoding são
/// fixos — são fatos do próprio manual da ARM, não expressão criativa do QEMU.
///
/// Do formato, só interessa o suficiente para montar UM encoding representativo por instrução:
///
/// - linhas `@nome <padrão>` definem um **formato** (template) reutilizável;
/// - linhas `NOME <padrão> [@formato]` definem uma **instrução**;
/// - um padrão é uma sequência de tokens: corridas de `0`/`1`/`.`/`-` (bits literais, onde `.` e
///   `-` são "não especificado") ou campos nomeados `nome:largura` / `nome:slargura`;
/// - `%extratores`, `&conjuntos-de-argumentos` e atribuições `campo=valor` não afetam quais bits
///   são fixos e são ignorados;
/// - `\` no fim continua a linha; `#` comenta; `{`/`}`/`[`/`]` agrupam formas sobrepostas e não
///   mudam os padrões contidos.
public final class DecodeTreeSpec {

    /// Prefixo dos campos sintéticos que {@link #addFreeBitFields} cria — nunca colide com um campo
    /// real do `decodetree` (nenhum começa com `~`) nem com `IsaCoverageReport.REGISTER_FIELDS`.
    /// {@link IsaCoverageReport#encode} usa este prefixo para dar a estes campos um slot de
    /// preenchimento PRÓPRIO, separado do campo não-registrador NOMEADO — ver o Javadoc de
    /// `FILL_STRATEGIES` para o porquê.
    static final String SYNTHETIC_FIELD_PREFIX = "~free";

    /// Uma instrução do inventário: o mnemônico do QEMU e o encoding com os bits fixos aplicados.
    ///
    /// @param name        mnemônico (ex.: `AND_rrri`)
    /// @param fixedOnes   bits que TÊM que ser 1
    /// @param fixedZeros  bits que TÊM que ser 0
    /// @param fields      campos nomeados → posições de bit ocupadas (para preencher com valores
    ///                    plausíveis; ver {@link IsaCoverageReport})
    /// @param width       largura do encoding em bits (16 ou 32)
    public record Instruction(String name, int fixedOnes, int fixedZeros, Map<String, int[]> fields, int width) {
    }

    private DecodeTreeSpec() {
    }

    public static List<Instruction> parse(Path file, int width) throws IOException {
        List<String> logicalLines = joinContinuations(Files.readAllLines(file, StandardCharsets.UTF_8));
        Map<String, Pattern> formats = new HashMap<>();
        List<Instruction> instructions = new ArrayList<>();
        for (String line : logicalLines) {
            String text = stripCommentAndGrouping(line);
            if (text.isBlank()) {
                continue;
            }
            String[] tokens = text.trim().split("\\s+");
            String head = tokens[0];
            if (head.startsWith("%") || head.startsWith("&")) {
                continue; // extrator / conjunto de argumentos: não define bits
            }
            if (head.startsWith("@")) {
                formats.put(head.substring(1), readPattern(tokens, 1, width));
                continue;
            }
            Pattern own = readPattern(tokens, 1, width);
            if (own == null) {
                continue;
            }
            Pattern merged = merge(own, referencedFormat(tokens, formats), width);
            addFreeBitFields(merged, width);
            instructions.add(new Instruction(head, merged.ones, merged.zeros, merged.fields, width));
        }
        return instructions;
    }

    private static Pattern referencedFormat(String[] tokens, Map<String, Pattern> formats) {
        for (String token : tokens) {
            if (token.startsWith("@")) {
                return formats.get(token.substring(1));
            }
        }
        return null;
    }

    /// Bits fixos do formato completam os do padrão próprio; campos nomeados dos dois se somam.
    private static Pattern merge(Pattern own, Pattern format, int width) {
        if (format == null) {
            return own;
        }
        Pattern merged = new Pattern();
        merged.ones = own.ones | format.ones;
        merged.zeros = own.zeros | format.zeros;
        merged.fields = new LinkedHashMap<>(format.fields);
        merged.fields.putAll(own.fields);
        return merged;
    }

    private static final class Pattern {
        int ones;
        int zeros;
        Map<String, int[]> fields = new LinkedHashMap<>();
    }

    /// **B17.29 — achado**: um bit coberto só por `.`/`-` (nem literal `0`/`1`, nem campo `nome:largura`) não gera
    /// NENHUM campo — nem no padrão próprio nem no `@formato` — quando o valor real da instrução é montado por um
    /// `%extrator` (ex. `imm=%tszimm_shl`, ignorado de propósito pelo cabeçalho desta classe). Sem isto,
    /// {@link IsaCoverageReport#encode} nunca escreve nada nessas posições: ficam `0` SEMPRE, em TODAS as
    /// estratégias de preenchimento — e várias famílias de `sve.decode` (`SSHLLB`/`SSRA`/`SRI`/`XAR`/`DUP_x`/os
    /// narrowing `*NB`/`*NT`/...) recusam `tsz = 0` por desenho (`UNALLOCATED` real do manual), medindo `❌` mesmo
    /// já implementadas e testadas com encoding real — falso negativo do MEDIDOR, mesma classe do achado B9.7.
    ///
    /// Em vez de reproduzir cada `%extrator` (acoplaria o leitor à semântica do QEMU, o que o cabeçalho da classe
    /// rejeita de propósito), esta correção é estrutural: qualquer bit que sobrar sem dono depois do merge
    /// própria+formato vira um campo SINTÉTICO (nome `~freeAA_BB`, nunca colide com `REGISTER_FIELDS` nem com um
    /// campo real, já que nenhum campo real do `decodetree` começa com `~`) agrupado por posição CONTÍGUA, preenchido pelas
    /// mesmas estratégias genéricas dos demais campos não-registrador. Como o `decodetree` já garante que um `.`
    /// solto é "qualquer valor serve" (nunca um bit reservado à parte), isto só pode tornar a medição mais fiel —
    /// nunca pode fazer uma célula já `✅`/`⚠️` regredir (a estratégia que já decodificava continua decodificando).
    private static void addFreeBitFields(Pattern pattern, int width) {
        int claimed = pattern.ones | pattern.zeros;
        for (int[] positions : pattern.fields.values()) {
            for (int bit : positions) {
                claimed |= 1 << bit;
            }
        }
        int bit = width - 1;
        while (bit >= 0) {
            if ((claimed & (1 << bit)) != 0) {
                bit--;
                continue;
            }
            int high = bit;
            while (bit >= 0 && (claimed & (1 << bit)) == 0) {
                bit--;
            }
            int low = bit + 1;
            int[] positions = new int[high - low + 1];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = high - i; // MSB primeiro, mesma convenção de `readPattern`.
            }
            pattern.fields.put(SYNTHETIC_FIELD_PREFIX + high + "_" + low, positions);
        }
    }

    /// Lê os tokens de bits da esquerda (bit mais significativo) para a direita. Devolve `null` se
    /// os tokens não somam exatamente `width` bits — a linha não é um padrão de encoding.
    private static Pattern readPattern(String[] tokens, int from, int width) {
        Pattern pattern = new Pattern();
        int bitsConsumed = 0;
        for (int i = from; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.startsWith("@") || token.startsWith("&") || token.startsWith("%")
                    || token.startsWith("!") || token.contains("=")) {
                continue;
            }
            if (token.matches("[01.\\-]+")) {
                for (int c = 0; c < token.length(); c++) {
                    int bit = width - 1 - (bitsConsumed + c);
                    if (bit < 0) {
                        return null;
                    }
                    char value = token.charAt(c);
                    if (value == '1') {
                        pattern.ones |= 1 << bit;
                    } else if (value == '0') {
                        pattern.zeros |= 1 << bit;
                    }
                }
                bitsConsumed += token.length();
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String length = token.substring(colon + 1);
            boolean signed = length.startsWith("s");
            int bits = parseIntOrZero(signed ? length.substring(1) : length);
            if (bits <= 0) {
                continue;
            }
            int[] positions = new int[bits];
            for (int c = 0; c < bits; c++) {
                int bit = width - 1 - (bitsConsumed + c);
                if (bit < 0) {
                    return null;
                }
                positions[c] = bit;
            }
            pattern.fields.put(token.substring(0, colon), positions);
            bitsConsumed += bits;
        }
        return bitsConsumed == width ? pattern : null;
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> joinContinuations(List<String> rawLines) {
        List<String> joined = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String raw : rawLines) {
            String line = raw;
            if (line.endsWith("\\")) {
                pending.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            pending.append(line);
            joined.add(pending.toString());
            pending.setLength(0);
        }
        if (pending.length() > 0) {
            joined.add(pending.toString());
        }
        return joined;
    }

    private static String stripCommentAndGrouping(String line) {
        int comment = line.indexOf('#');
        String text = comment >= 0 ? line.substring(0, comment) : line;
        return text.replace("{", " ").replace("}", " ").replace("[", " ").replace("]", " ");
    }
}
