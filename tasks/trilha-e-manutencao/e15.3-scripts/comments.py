# E15.3 - menções em comentário/Javadoc (prosa) aos records que mudaram de nome.
import re, os, sys
sys.path.insert(0, 'target/e15.3')
from split import load_mapping, IR, LEAVES, MIDDLES
m = load_mapping()
changed = [o for o, (l, n, k) in m.items() if o != n]
pat = re.compile(r'(?<![\w.$])(' + '|'.join(sorted(changed, key=len, reverse=True)) + r')\b')
skip = set()  # E15.3: a prosa dos arquivos gerados também é atualizada
total = 0
for root in sys.argv[1:]:
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ('target', '.git', '.claude')]
        for f in files:
            p = os.path.join(d, f).replace('\\', '/')
            if not f.endswith('.java') or p in skip:
                continue
            raw = open(p, encoding='utf-8', newline='').read()
            eol = '\r\n' if '\r\n' in raw else '\n'
            out = []
            n = 0
            for line in raw.replace('\r\n', '\n').split('\n'):
                idx = line.find('//')
                if idx >= 0 and line[:idx].count('"') % 2 == 0:
                    comment, k = pat.subn(lambda mm: m[mm.group(1)][0] + '.' + m[mm.group(1)][1], line[idx:])
                    n += k
                    line = line[:idx] + comment
                out.append(line)
            if n:
                total += n
                open(p, 'w', encoding='utf-8', newline='').write(eol.join(out))
                print(n, p)
print('total', total)
