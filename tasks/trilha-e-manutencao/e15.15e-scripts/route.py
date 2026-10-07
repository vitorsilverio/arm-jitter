# E15.15e — troca o fim de `decodeAdvancedSimdInteger` (do `boolean scalar;` até a chave que fecha o método)
# pela consulta à tabela. Ancorado por conteúdo conferido: a linha de início e a de fim têm que bater exatamente.
import sys

PATH = sys.argv[1]
START = "        boolean scalar;"
END_NEXT = "    /// E15.15d: os 4 slots de `Rm` do two-register misc — `0000x` (inteiro e FP `_sd`) e `1100x` (FP16)."
NEW = """        // E15.15a–E15.15e: vetorial (prefixo `01110`) ou escalar (`11110` com `bit30=1`) — o espaço inteiro
        // (`bit21=0`, "three same", "three different", reduções, AES/SHA de dois registradores e two-register misc)
        // é a {@link #advSimdTable}, já filtrada pelo preset; o que não casa é recusado (G8).
        boolean vector = prefix == ADVSIMD_INT_PREFIX_VECTOR_PATTERN;
        boolean scalar = prefix == ADVSIMD_INT_PREFIX_SCALAR_PATTERN
                && ((word >>> ADVSIMD_INT_SCALAR_BIT30_SHIFT) & 1) != 0;
        if (!vector && !scalar) {
            throw unsupported(word, address);
        }
        return decodeAdvSimdTable(word, address);
    }
"""

lines = open(PATH, encoding="utf-8", newline="").read().split("\n")
starts = [i for i, l in enumerate(lines) if l == START]
ends = [i for i, l in enumerate(lines) if l == END_NEXT]
if len(starts) != 1 or len(ends) != 1:
    sys.exit(f"âncora não única: {len(starts)} {len(ends)}")
start, end_next = starts[0], ends[0]
# fim do método = a linha "    }" logo antes da linha em branco que precede END_NEXT
end = end_next - 1
while lines[end] != "    }":
    end -= 1
print(f"substituindo linhas {start + 1}-{end + 1}")
lines[start:end + 1] = NEW.rstrip("\n").split("\n")
open(PATH, "w", encoding="utf-8", newline="").write("\n".join(lines))
