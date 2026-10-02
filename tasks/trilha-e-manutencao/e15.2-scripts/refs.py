# E15.2 - reescreve as referencias `Ir64Op.Xxx` fora de Ir64Op.java para o nome novo e ajusta imports.
import re, sys, os
sys.path.insert(0, 'target/e15.2')
from split import load_mapping, alternation, IR64, PKG, LEAVES, MIDDLES

mapping = load_mapping()
ALT = alternation(mapping)
REF = re.compile(r'\bIr64Op\.(' + ALT + r')\b')
GENERATED = {os.path.join(IR64, n + '.java').replace('\\', '/') for n in list(LEAVES) + list(MIDDLES) + ['Ir64Op']}
IFACES = sorted(set(v[0] for v in mapping.values()))
PREFIX = 'import dev.vitorsilverio.armjitter.'


def rewrite(path):
    raw = open(path, encoding='utf-8', newline='').read()
    eol = '\r\n' if '\r\n' in raw else '\n'
    text = raw.replace('\r\n', '\n')
    if not REF.search(text):
        return False
    # import direto de um record (`import ...Ir64Op.Xxx;`): os usos sem qualificador mudam junto
    direct = re.findall(r'^import ' + re.escape(PKG) + r'\.Ir64Op\.(' + ALT + r');$', text, re.M)
    text = REF.sub(lambda m: mapping[m.group(1)][0] + '.' + mapping[m.group(1)][1], text)
    for old in direct:
        new = mapping[old][1]
        text = re.sub(r'(?<![\w.])' + old + r'\b', new, text)
    if path.endswith('.java'):
        pkg = re.search(r'^package ([\w.]+);', text, re.M).group(1)
        if pkg != PKG and not re.search(r'^import ' + re.escape(PKG) + r'\.\*;', text, re.M):
            lines = text.split('\n')
            body = '\n'.join(l for l in lines if not l.startswith('import '))
            needed = [i for i in IFACES if re.search(r'(?<![\w.])' + i + r'\b', body)]
            if not re.search(r'(?<![\w.])Ir64Op\b', body):
                lines = [l for l in lines if l != 'import ' + PKG + '.Ir64Op;']
            for iface in needed:
                imp = 'import ' + PKG + '.' + iface + ';'
                if imp in lines:
                    continue
                own = [k for k, l in enumerate(lines) if l.startswith(PREFIX)]
                if own:
                    after = [k for k in own if lines[k] > imp]
                    pos = after[0] if after else own[-1] + 1
                else:
                    anyimp = [k for k, l in enumerate(lines) if l.startswith('import ')]
                    if anyimp:
                        pos = anyimp[0]
                    else:
                        pos = next(k for k, l in enumerate(lines) if l.startswith('package ')) + 2
                        lines.insert(pos, '')
                lines.insert(pos, imp)
            text = '\n'.join(lines)
    open(path, 'w', encoding='utf-8', newline='').write(text.replace('\n', eol))
    return True


def main(roots):
    changed = 0
    for root in roots:
        if os.path.isfile(root):
            changed += rewrite(root)
            continue
        for d, dirs, files in os.walk(root):
            dirs[:] = [x for x in dirs if x not in ('target', '.git', '.claude')]
            for f in files:
                p = os.path.join(d, f).replace('\\', '/')
                if f.endswith('.java') and p not in GENERATED:
                    changed += rewrite(p)
    print('arquivos alterados:', changed)


if __name__ == '__main__':
    main(sys.argv[1:])
