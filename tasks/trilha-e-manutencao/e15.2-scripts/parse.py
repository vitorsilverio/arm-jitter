import re, sys, io

SRC = 'core/src/main/java/dev/vitorsilverio/armjitter/ir64/Ir64Op.java'
def members():
    lines = open(SRC, encoding='utf-8').read().split('\n')
    # find end of Kind
    i = next(k for k, l in enumerate(lines) if l.startswith('    final class Kind'))
    while lines[i] != '    }': i += 1
    i += 1
    out = []
    cur = []
    while i < len(lines):
        l = lines[i]
        if l == '}':
            break
        cur.append(l)
        if l == '    }':
            text = cur
            while text and text[0] == '': text = text[1:]
            decl = [t for t in text if re.match(r'    (record|enum) ', t)]
            assert len(decl) == 1, text[:5]
            m = re.match(r'    (record|enum) (\w+)', decl[0])
            out.append((m.group(1), m.group(2), text))
            cur = []
        i += 1
    assert all(c == '' for c in cur), cur
    return lines, out
if __name__ == '__main__':
    lines, ms = members()
    for kind, name, text in ms:
        print(kind, name, len(text) + 1)
    print(len([m for m in ms if m[0] == 'record']), 'records', len(ms), 'members')
