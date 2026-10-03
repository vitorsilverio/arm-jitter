# E15.7 - compara os .class de antes (cópia de core/target/classes em HEAD) com os da árvore de
# trabalho. Uso: python classdiff.py <classes-antes> <classes-depois>
# Só podem mudar as classes `codegen/jvm/Asm*` (compilador, política, emissores, helpers).
import hashlib
import os
import sys

before, after = sys.argv[1], sys.argv[2]
ALLOWED = 'dev/vitorsilverio/armjitter/codegen/jvm/Asm'


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
print('mudaram em codegen/jvm/Asm*:', sorted(k.rsplit('/', 1)[1] for k in changed if k.startswith(ALLOWED)))
print('mudaram FORA:', [k for k in changed if not k.startswith(ALLOWED)])
