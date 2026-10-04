"""E15.11 — troca o bloco de decode da classe branch/exceção/sistema do `Aarch64Decoder` pela chamada de
tabela e depois apaga as constantes `private static final` que ficaram sem uso (iterando até ponto fixo,
porque uma constante pode ser usada só por outra constante órfã).

Uso: python splice.py <Aarch64Decoder.java>
"""
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in text else "\n"
lines = text.split(nl)

start = next(i for i, l in enumerate(lines) if "private Ir64Op decodeBranchExceptionSystem(" in l)
end = next(i for i, l in enumerate(lines) if "private static long signExtend(" in l)
# sobe o javadoc/comentário imediatamente acima do primeiro método (nenhum hoje, mas por segurança)
while lines[start - 1].strip().startswith("///"):
    start -= 1

replacement = """    /// E15.11: no espaço `1101010100`, {@link SystemInstructionRows}/{@link SystemRegisterRows} e, se nenhuma
    /// linha casar, os restos de espaço (fallback); fora dele, {@link BranchExceptionRows}. O que não casa é
    /// recusado (G8).
    private Ir64Op decodeBranchExceptionSystem(int word, long address) {
        Ir64Op op;
        if ((word & SYSTEM_SPACE_MASK) == SYSTEM_SPACE_VALUE) {
            op = systemTable.decode(word, address);
            if (op == null) {
                op = systemFallbackTable.decode(word, address);
            }
        } else {
            op = branchExceptionTable.decode(word, address);
        }
        if (op == null) {
            throw unsupported(word, address);
        }
        return op;
    }
""".split("\n")
lines[start:end] = replacement

# constante nova, ao lado de CLASS_BRANCH_EXCEPTION_SYSTEM
anchor = next(i for i, l in enumerate(lines) if "private static final int CLASS_BRANCH_EXCEPTION_SYSTEM" in l)
lines[anchor + 1:anchor + 1] = [
    "    /// E15.11: `bits[31:22]=1101010100` — o espaço de sistema (`MRS`/`MSR`/`SYS`/hints/barreiras/PSTATE).",
    "    private static final int SYSTEM_SPACE_MASK = 0b1111111111 << 22;",
    "    private static final int SYSTEM_SPACE_VALUE = 0b1101010100 << 22;",
]

decl = re.compile(r"^\s*private static final \w+(?:\[\])? ([A-Z][A-Z0-9_]*) =")
removed = []
while True:
    body = nl.join(lines)
    orphan = None
    for i, l in enumerate(lines):
        m = decl.match(l)
        if not m:
            continue
        name = m.group(1)
        if len(re.findall(r"\b" + name + r"\b", body)) == 1:
            orphan = i
            break
    if orphan is None:
        break
    # a declaração pode continuar em linhas seguintes até o `;`
    stop = orphan
    while not lines[stop].rstrip().endswith(";"):
        stop += 1
    # e leva junto o javadoc `///` logo acima
    first = orphan
    while lines[first - 1].strip().startswith("///"):
        first -= 1
    removed.append(decl.match(lines[orphan]).group(1))
    del lines[first:stop + 1]

open(path, "w", encoding="utf-8", newline="").write(nl.join(lines))
print("bloco substituído: linhas %d-%d; constantes órfãs removidas: %d" % (start + 1, end, len(removed)))
