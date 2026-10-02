# E15.4 - compara os .class de antes (cópia de core/target/classes em HEAD) com os da árvore de
# trabalho, por pacote. Uso: python classdiff.py <classes-antes> <classes-depois>
import collections
import hashlib
import os
import sys

before, after = sys.argv[1], sys.argv[2]
# E15.4: só o IR A64 (ponte `execute`) e os executores A64 podem mudar.
TOUCHED = ('ir64', 'executor64')


def scan(root):
    out = {}
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class'):
                p = os.path.join(d, f)
                out[os.path.relpath(p, root).replace('\\', '/')] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    return out


a, b = scan(before), scan(after)
stats = collections.defaultdict(lambda: [0, 0, 0, 0])   # iguais, diferentes, só antes, só depois
for k in sorted(set(a) | set(b)):
    pkg = k.split('/')[3] if k.count('/') >= 4 else '(raiz)'
    if k in a and k in b:
        stats[pkg][0 if a[k] == b[k] else 1] += 1
    elif k in a:
        stats[pkg][2] += 1
    else:
        stats[pkg][3] += 1
print('%-14s %7s %10s %8s %8s' % ('pacote', 'iguais', 'diferentes', 'so-antes', 'so-depois'))
for pkg, s in sorted(stats.items()):
    print('%-14s %7d %10d %8d %8d' % (pkg, *s))
other = [k for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k) and k.split('/')[3] not in TOUCHED]
print('diferentes fora de', TOUCHED, ':', other)
