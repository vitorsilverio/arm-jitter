# E15.3 - desfaz a linha em branco que refs.py insere (arquivo sem imports) quando o
# unused_imports.py depois remove o import que a motivou.
import subprocess, re
files = subprocess.run(['git', 'diff', '--name-only'], capture_output=True, text=True).stdout.split()
for p in files:
    if not p.endswith('.java'): continue
    raw = open(p, encoding='utf-8', newline='').read()
    eol = '\r\n' if '\r\n' in raw else '\n'
    m = re.match(r'(package [\w.]+;)' + eol + eol + eol, raw)
    if m:
        open(p, 'w', encoding='utf-8', newline='').write(raw.replace(m.group(0), m.group(1) + eol + eol, 1))
        print('corrigido', p)
