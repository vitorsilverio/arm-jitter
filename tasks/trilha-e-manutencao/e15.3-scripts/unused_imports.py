# E15.3 - remove import de interface de família que só é citada em prosa de comentário.
import re, os, sys
sys.path.insert(0, 'target/e15.3')
from split import load_mapping, PKG, LEAVES, MIDDLES
ifaces = list(LEAVES) + list(MIDDLES)
removed = 0
for root in sys.argv[1:]:
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ('target', '.git', '.claude')]
        for f in files:
            if not f.endswith('.java'):
                continue
            p = os.path.join(d, f)
            raw = open(p, encoding='utf-8', newline='').read()
            eol = '\r\n' if '\r\n' in raw else '\n'
            lines = raw.replace('\r\n', '\n').split('\n')
            code, comments = [], []
            for l in lines:
                if l.startswith('import '):
                    continue
                idx = l.find('//')
                if idx >= 0 and l[:idx].count('"') % 2 == 0:
                    code.append(l[:idx])
                    comments.append(l[idx:])
                else:
                    code.append(l)
            code = '\n'.join(code)
            comments = '\n'.join(comments)
            drop = []
            for i in ifaces:
                imp = 'import ' + PKG + '.' + i + ';'
                if imp not in lines:
                    continue
                in_code = re.search(r'(?<![\w.])' + i + r'\b', code)
                in_link = re.search(r'(\{@link(?:plain)?\s+(?:///\s*)?|@see\s+|@throws\s+)' + i + r'\b', comments)
                if not in_code and not in_link:
                    drop.append(imp)
            if drop:
                removed += len(drop)
                print(os.path.basename(p), [x.split('.')[-1] for x in drop])
                lines = [l for l in lines if l not in drop]
                open(p, 'w', encoding='utf-8', newline='').write(eol.join(lines))
print('imports removidos:', removed)
