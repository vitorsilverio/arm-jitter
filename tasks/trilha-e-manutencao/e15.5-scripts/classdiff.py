# E15.5 - compara os .class de antes (cópia de core/target/classes em HEAD) com os da árvore de
# trabalho. Uso: python classdiff.py <classes-antes> <classes-depois>
# Só podem mudar as classes do pacote `ir` (ponte `execute`) e `IrBlockExecutor`.
import hashlib
import os
import sys

before, after = sys.argv[1], sys.argv[2]
PREFIX = 'dev/vitorsilverio/armjitter/'
ALLOWED_PACKAGE = PREFIX + 'ir/'
ALLOWED_CLASS = PREFIX + 'codegen/executor/IrBlockExecutor.class'


def scan(root):
    out = {}
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class'):
                p = os.path.join(d, f)
                out[os.path.relpath(p, root).replace('\\', '/')] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
    return out


a, b = scan(before), scan(after)
changed = [k for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
inside = [k for k in changed if (k.startswith(ALLOWED_PACKAGE) and '/' not in k[len(ALLOWED_PACKAGE):])
          or k == ALLOWED_CLASS]
outside = [k for k in changed if k not in inside]
print('classes:', len(a), 'antes,', len(b), 'depois; iguais:', len(set(a) & set(b)) - len([k for k in changed if k in a and k in b]))
print('diferentes em ir/ + IrBlockExecutor:', len(inside))
print('diferentes FORA:', outside)
