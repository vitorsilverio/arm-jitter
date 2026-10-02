import re, sys, os, collections
sys.path.insert(0, 'target/e15.3')
import parse
from split import load_mapping, IR, LEAVES, STRUCTURAL
mapping = load_mapping()
parse.SRC = 'target/e15.3/IrOp.orig.java'
_, orig = parse.members()
orig = {n: t for _, n, t in orig}
def chunks(path):
    lines = open(path, encoding='utf-8').read().split('\n')
    d0 = next(k for k, l in enumerate(lines) if l.startswith('public sealed interface'))
    i = next(k for k in range(d0, len(lines)) if lines[k].endswith('{')) + 1
    if os.path.basename(path) == 'IrOp.java':
        i = next(k for k, l in enumerate(lines) if l.startswith('    final class Kind'))
        while lines[i] != '    }': i += 1
        i += 1
    out, cur = {}, []
    order = []
    for l in lines[i:]:
        if l == '}': break
        cur.append(l)
        if l == '    }':
            while cur and cur[0] == '': cur = cur[1:]
            d = [t for t in cur if re.match(r'    (record|enum) ', t)]
            assert len(d) == 1
            n = re.match(r'    (record|enum) (\w+)', d[0]).group(2)
            out[n] = cur; order.append(n); cur = []
    return out, order
new = {}
orders = {}
for leaf in list(LEAVES) + ['IrOp']:
    c, o = chunks(os.path.join(IR, leaf + '.java'))
    orders[leaf] = o
    for n, t in c.items(): new[(leaf, n)] = t
diffs = collections.Counter(); total = 0; shown = 0
for old, text in orig.items():
    key = ('IrOp', old) if old in STRUCTURAL else (mapping[old][0], mapping[old][1])
    nt = new.pop(key)
    assert len(nt) == len(text), old
    for a, b in zip(text, nt):
        if a != b:
            total += 1
            if '{@link' in a or (a.lstrip().startswith('///') ): cat = 'javadoc-link'
            elif re.match(r'    record ', a): cat = 'record-decl'
            elif 'implements IrOp' in a: cat = 'implements'
            elif re.match(r'\s+public \w+\(', a): cat = 'constructor'
            else: cat = 'OUTRO'
            diffs[cat] += 1
            if cat == 'OUTRO' or (cat == 'javadoc-link' and shown < 6):
                shown += 1; print(cat, '|', a.strip()[:120], '\n    =>', b.strip()[:120])
assert not new, new.keys()
print(dict(diffs), 'linhas diferentes:', total, 'de', sum(len(t) for t in orig.values()))
# ordem relativa preservada
names = list(orig)
for leaf, o in orders.items():
    idx = [names.index(next(k for k in names if (k in STRUCTURAL and leaf == 'IrOp' and k == n) or (k not in STRUCTURAL and mapping[k][:2] == (leaf, n)))) for n in o]
    assert idx == sorted(idx), leaf
print('ordem relativa preservada em', len(orders), 'arquivos')
