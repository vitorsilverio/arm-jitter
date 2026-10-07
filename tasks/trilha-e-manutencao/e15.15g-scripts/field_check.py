"""E15.15g — confere rd/rn/rm/index dos records "ByElement" contra o texto do `objdump`.

Uso: python field_check.py <saida-do-oraculo.txt> <preset> <preset-aceitas.txt do objdump-oraculo.sh>

Junta, por palavra, o record completo do oráculo (`Nome[campo=valor, ...]`) com a desmontagem e imprime as
divergências agrupadas por (record, mnemônico).
"""
import re
import sys
from collections import Counter, defaultdict

oracle_path, preset, objdump_path = sys.argv[1:4]

records = {}
with open(oracle_path, encoding="utf-8") as f:
    for line in f:
        p, word, rest = line.rstrip("\r\n").split(" ", 2)
        if p == preset and "ByElement" in rest:
            records[word] = rest

REG = re.compile(r"\b[vqdsbh](\d+)\b")
ELEMENT = re.compile(r"v(\d+)\.\d*[bhsd]\[(\d+)\]")
FIELD = re.compile(r"(\w+)=([\w-]+)")

mismatch = Counter()
examples = defaultdict(list)
checked = Counter()
with open(objdump_path, encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\r\n").split(" ", 3)
        if len(parts) < 4 or parts[0] not in records:
            continue
        # linha do objdump-oraculo.sh: "<palavra> <record sem campos> <mnemônico> <operandos>"
        word, text = parts[0], parts[2] + " " + parts[3]
        rec = records[word]
        fields = dict(FIELD.findall(rec[rec.index("["):]))
        mnemonic = text.split()[0]
        operands = text[len(mnemonic):]
        element = ELEMENT.search(operands)
        regs = [int(r) for r in REG.findall(operands.split("[")[0])]
        key = (rec[:rec.index("[")], mnemonic)
        checked[key] += 1
        expected = {"rd": regs[0], "rn": regs[1]}
        if element:
            expected["rm"] = int(element.group(1))
            expected["index"] = int(element.group(2))
        bad = [k for k, v in expected.items() if k in fields and int(fields[k]) != v]
        if bad:
            mismatch[key] += 1
            if len(examples[key]) < 3:
                examples[key].append(f"{word} {rec}  <>  {text}  [{','.join(bad)}]")

for key, n in sorted(checked.items()):
    print(f"{key[0]:45s} {key[1]:10s} conferidas {n:6d} divergentes {mismatch[key]}")
for key, ex in examples.items():
    print("\n" + " / ".join(key))
    for e in ex:
        print("  " + e)
