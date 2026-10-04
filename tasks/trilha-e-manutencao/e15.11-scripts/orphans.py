"""E15.11 — limpeza depois do `splice.py`: apaga constantes `private static final` sem uso em CÓDIGO
(menção só em comentário `//` não conta; `{@link #X}` em `///` conta) e os blocos de comentário de seção
(`// ──`) que ficaram sem nenhuma declaração embaixo. Itera até ponto fixo.

Uso: python orphans.py <Arquivo.java>
"""
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in text else "\n"
lines = text.split(nl)
decl = re.compile(r"^\s*private static final \w+(?:\[\])? ([A-Z][A-Z0-9_]*) =")


def is_line_comment(line):
    s = line.strip()
    return s.startswith("//") and not s.startswith("///")


def code_body():
    out = []
    for l in lines:
        if is_line_comment(l):
            continue
        if not l.strip().startswith("///"):
            l = re.sub(r"//.*", "", l)
        out.append(l)
    return "\n".join(out)


removed = []
changed = True
while changed:
    changed = False
    body = code_body()
    for i, l in enumerate(lines):
        m = decl.match(l)
        if not m or len(re.findall(r"\b" + m.group(1) + r"\b", body)) != 1:
            continue
        stop = i
        while not lines[stop].rstrip().endswith(";"):
            stop += 1
        first = i
        while lines[first - 1].strip().startswith("///"):
            first -= 1
        removed.append(m.group(1))
        del lines[first:stop + 1]
        changed = True
        break

# blocos `//` de seção seguidos de linha em branco, `}` ou outro bloco `//` — sem declaração embaixo
comments = 0
i = 0
while i < len(lines):
    if is_line_comment(lines[i]) and (i == 0 or not is_line_comment(lines[i - 1])):
        j = i
        while j < len(lines) and is_line_comment(lines[j]):
            j += 1
        nxt = lines[j].strip() if j < len(lines) else ""
        if nxt == "" or nxt == "}":
            end = j + 1 if nxt == "" and i > 0 and lines[i - 1].strip() == "" else j
            del lines[i:end]
            comments += 1
            continue
        i = j
    else:
        i += 1

open(path, "w", encoding="utf-8", newline="").write(nl.join(lines))
print("constantes órfãs: %d %s; blocos de comentário soltos: %d" % (len(removed), removed, comments))
