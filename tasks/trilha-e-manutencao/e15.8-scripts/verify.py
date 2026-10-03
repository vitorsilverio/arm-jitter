"""E15.8 — confere que o split foi puro deslocamento (rodar da raiz do repo, depois do `split.py`).

1. Cada membro de `IrSystemExecutor` em HEAD aparece exatamente uma vez no conjunto
   {`IrSystemExecutor`, `IrMve*`} de hoje, com as mesmas linhas, fora de: `public` → `public static`,
   `private static` → `static` (em `IrMveSupport`), qualificação `IrMveSupport.` e links `{@link #x}`
   qualificados com a classe dona.
2. Nenhum membro novo além dos construtores.
3. Cada ponte dos records MVE chama o mesmo nome de método que chamava em HEAD.
"""
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import split  # noqa: E402

FILES = ['IrSystemExecutor', 'IrMveSupport', 'IrMvePredicationExecutor', 'IrMveMoveExecutor',
         'IrMveIntegerExecutor', 'IrMveFpExecutor', 'IrMveReductionExecutor']


def git_head(path):
    out = subprocess.run(['git', 'show', 'HEAD:' + path.as_posix()], capture_output=True, check=True).stdout
    return out.decode('utf-8').replace('\r\n', '\n')


def normalize(line):
    line = re.sub(r'^    public static (boolean|void) ', r'    public \1 ', line)
    line = re.sub(r'^    static ', '    private static ', line)
    line = line.replace('IrMveSupport.', '')
    line = re.sub(r'\{@link Ir(?:System\w*|Mve\w*)#', '{@link #', line)
    return line


def current_members():
    found = {}
    for cls in FILES:
        lines = (split.EXEC_DIR / f'{cls}.java').read_text(encoding='utf-8').replace('\r\n', '\n').split('\n')
        ctor = next(i for i, l in enumerate(lines) if re.match(r'^    (private )?' + cls + r'\(', l))
        end = next(i for i in range(ctor, len(lines)) if lines[i] == '    }')
        last = max(i for i, l in enumerate(lines) if l == '}')
        # Reaproveita o parser: troca o construtor pelo formato que ele procura.
        fake = lines[:ctor] + ['    IrSystemExecutor(IrExecutionSupport x) {'] + lines[ctor + 1:end + 1] + lines[end + 1:last + 1]
        _, members, _ = split.parse(fake)
        for m in members:
            if m['name'] in found:
                raise SystemExit(f'{m["name"]} duplicado em {cls} e {found[m["name"]][0]}')
            found[m['name']] = (cls, m)
    return found


def main():
    _, old, _ = split.parse(split.head_lines())
    new = current_members()
    errors = 0
    for m in old:
        if m['name'] not in new:
            print('SUMIU:', m['name'])
            errors += 1
            continue
        cls, n = new[m['name']]
        a = m['lines']
        b = [normalize(l) for l in n['lines']]
        if a != b:
            errors += 1
            print(f'DIFERE: {m["name"]} ({cls})')
            for x, y in zip(a, b):
                if x != y:
                    print('  -', x)
                    print('  +', y)
                    break
    extra = set(new) - {m['name'] for m in old}
    if extra:
        print('NOVOS:', sorted(extra))
        errors += 1
    # Pontes.
    call = re.compile(r'(record (\w+)\(|\.(\w+)\(core, this\))')
    bridges = 0
    for path in sorted(split.IR.glob('Mve*Op.java')):
        def pairs(text):
            out, rec = [], None
            for line in text.split('\n'):
                r = re.match(r'^    record (\w+)\(', line)
                if r:
                    rec = r.group(1)
                b = re.search(r'public boolean execute\(IrBlockExecutor executor.*?\.(\w+)\(core, this\)', line)
                if b:
                    out.append((rec, b.group(1)))
            return out
        before = pairs(git_head(path))
        after = pairs(path.read_text(encoding='utf-8').replace('\r\n', '\n'))
        if before != after:
            print('PONTES DIFEREM em', path.name)
            errors += 1
        bridges += len(after)
    print(f'membros: {len(old)} conferidos; pontes: {bridges}; erros: {errors}')
    sys.exit(1 if errors else 0)


if __name__ == '__main__':
    main()
