"""E15.4 — leitura de `Ir64BlockExecutor` e dos records de `ir64` (rodar da raiz do repo).

Fornece para `split.py`/`verify.py`:
- `kind_calls()`: `Kind` -> expressão que o `switch` de `execute` chama hoje;
- `members()`: os membros (Javadoc + declaração + corpo) depois do `switch`;
- `records()`: cada record de `ir64` com o `Kind` e a linha do `kind()`.
"""
import re
from pathlib import Path

ROOT = Path('core/src/main/java/dev/vitorsilverio/armjitter')
EXECUTOR = ROOT / 'executor64' / 'Ir64BlockExecutor.java'
IR64 = ROOT / 'ir64'

CASE = re.compile(r'case Ir64Op\.Kind\.(\w+) ->\s*((?:\w+\.)?\w+)\(\s*(core, )?(?:\(([\w.]+)\) op)?\);')
MEMBER_START = re.compile(r'^    (?:private |public |static |final )+[\w<>\[\], .]+? (\w+)\(')
RECORD_START = re.compile(r'^    private record (\w+)\(')
CONSTANT = re.compile(r'^    private static final [\w<>\[\]]+ (\w+) =')
RECORD_DECL = re.compile(r'^    record (\w+)\(')
KIND_LINE = re.compile(r'^        @Override public int kind\(\) \{ return Kind\.(\w+); \}$')


def source_lines(path=EXECUTOR):
    return path.read_text(encoding='utf-8').split('\n')


def switch_range(lines):
    start = next(i for i, l in enumerate(lines) if l.startswith('    private boolean execute(Aarch64Core core, Ir64Op op)'))
    end = next(i for i in range(start, len(lines)) if lines[i] == '    }')
    return start, end


def kind_calls(lines=None):
    """`Kind` -> (classe ou None, método, recebe core, tipo do record ou None)."""
    lines = lines or source_lines()
    start, end = switch_range(lines)
    text = ' '.join(l.strip() for l in lines[start:end + 1])
    calls = {}
    for m in CASE.finditer(text):
        kind, target, core, record = m.groups()
        cls, _, method = target.rpartition('.')
        calls[kind] = (cls or None, method, bool(core), record)
    return calls


def members(lines=None):
    """Membros depois do `switch`: dict(name, start, end, kind='method'|'record', lines)."""
    lines = lines or source_lines()
    _, switch_end = switch_range(lines)
    last = max(i for i, l in enumerate(lines) if l == '}')
    result = []
    i = switch_end + 1
    while i < last:
        if lines[i].strip() == '':
            i += 1
            continue
        start = i
        while lines[i].startswith('    ///') or lines[i].startswith('    //') or lines[i].startswith('    @'):
            i += 1
        c = CONSTANT.match(lines[i])
        if c:
            declaration = i
            while not lines[i].rstrip().endswith(';'):
                i += 1
            result.append({'name': c.group(1), 'start': start, 'declaration': declaration, 'end': i,
                           'kind': 'constant', 'lines': lines[start:i + 1]})
            i += 1
            continue
        m = MEMBER_START.match(lines[i]) or RECORD_START.match(lines[i])
        if not m:
            raise SystemExit(f'membro não reconhecido na linha {i + 1}: {lines[i]}')
        declaration = i
        while lines[i] != '    }':
            i += 1
        result.append({
            'name': m.group(1),
            'start': start,
            'declaration': declaration,
            'end': i,
            'kind': 'record' if RECORD_START.match(lines[declaration]) else 'method',
            'lines': lines[start:i + 1],
        })
        i += 1
    return result


def constants(lines=None):
    """Constantes privadas do topo da classe: dict(name, start, end, lines) (Javadoc incluso)."""
    lines = lines or source_lines()
    result = []
    switch_start, _ = switch_range(lines)
    for i, l in enumerate(lines[:switch_start]):
        m = CONSTANT.match(l)
        if not m:
            continue
        start = i
        while lines[start - 1].startswith('    ///'):
            start -= 1
        end = i
        while not lines[end].rstrip().endswith(';'):
            end += 1
        result.append({'name': m.group(1), 'start': start, 'end': end, 'lines': lines[start:end + 1]})
    return result


def records():
    """Records de família: dict(file, interface, name, kind, kind_line (índice))."""
    result = []
    for path in sorted(IR64.glob('*Op64.java')) + [IR64 / 'Ir64Op.java']:
        lines = source_lines(path)
        current = None
        for i, l in enumerate(lines):
            m = RECORD_DECL.match(l)
            if m:
                current = m.group(1)
                continue
            k = KIND_LINE.match(l)
            if k:
                if current is None:
                    raise SystemExit(f'{path}:{i + 1}: kind() sem record')
                result.append({'file': path, 'interface': path.stem, 'name': current,
                               'kind': k.group(1), 'kind_line': i})
                current = None
    return result


if __name__ == '__main__':
    lines = source_lines()
    calls = kind_calls(lines)
    recs = records()
    print('cases', len(calls), 'records', len(recs))
    missing = [r['kind'] for r in recs if r['kind'] not in calls]
    print('records sem case:', missing)
    mem = members(lines)
    names = [m['name'] for m in mem]
    consts = constants(lines)
    print('members', len(mem), 'constants', len(consts))
    param = re.compile(r'\((?:Aarch64Core core, )?(\w+Op64)\.\w+ op\)')
    owner = {}
    for m in mem:
        p = param.search(lines[m['declaration']])
        owner[m['name']] = p.group(1) if p else None
    body = {m['name']: '\n'.join(lines[m['declaration']:m['end'] + 1]) for m in mem}
    for target in names + [c['name'] for c in consts]:
        if owner.get(target):
            continue
        users = sorted({n for n in names if n != target and re.search(r'\b' + target + r'\b', body[n])})
        fams = sorted({str(owner.get(u)) for u in users})
        print(f'{target:40s} fams={fams} users={users[:6]}{"..." if len(users) > 6 else ""}')
    sizes = {}
    for m in mem:
        sizes[owner[m['name']]] = sizes.get(owner[m['name']], 0) + len(m['lines']) + 1
    print(sizes)
