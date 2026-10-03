# E15.8 (adaptado da E15.7) - compara os .class de antes (cópia de core/target/classes em HEAD) com os da árvore de
# trabalho. Uso: python classdiff.py <classes-antes> <classes-depois>
# Só podem mudar: IrSystemExecutor, as classes IrMve* novas, IrBlockExecutor e os records MVE (pontes).
import hashlib
import os
import sys

before, after = sys.argv[1], sys.argv[2]
import re
ALLOWED = re.compile(r'dev/vitorsilverio/armjitter/(codegen/executor/(IrSystemExecutor|IrMve\w*|IrBlockExecutor)|ir/Mve\w*Op\$\w+)\.class$')


def scan(root):
    out = {}
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class'):
                p = os.path.join(d, f)
                out[os.path.relpath(p, root).replace(os.sep, '/')] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    return out


a, b = scan(before), scan(after)
changed = [k for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
print('classes:', len(a), 'antes,', len(b), 'depois')
print('mudaram (permitido):', len([k for k in changed if ALLOWED.search(k)]))
print('mudaram FORA:', [k for k in changed if not ALLOWED.search(k)])
