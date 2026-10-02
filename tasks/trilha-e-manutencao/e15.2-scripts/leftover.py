import re, os, sys, collections
sys.path.insert(0, 'target/e15.2')
from split import load_mapping
m = load_mapping()
changed = [o for o, (l, n, k) in m.items() if o != n]
pat = re.compile(r'(?<![\w])(' + '|'.join(sorted(changed, key=len, reverse=True)) + r')\b(?!\w)')
hits = collections.Counter()
ex = []
for root in sys.argv[1:]:
    if os.path.isfile(root):
        paths = [root]
    else:
        paths = [os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs if f.endswith(('.java', '.md'))]
    for p in paths:
        name = os.path.basename(p)
        for i, l in enumerate(open(p, encoding='utf-8').read().split('\n')):
            for mm in pat.finditer(l):
                hits[name] += 1
                ex.append((name, i + 1, l.strip()[:170]))
print(sum(hits.values()), 'ocorrencias em', len(hits), 'arquivos')
seen = set()
for e in ex:
    if e[:2] not in seen:
        seen.add(e[:2])
        print(e)
