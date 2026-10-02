"""E15.4 — move os métodos privados de `Ir64BlockExecutor` para executores por família e gera a
ponte `execute` em cada record de `ir64`. Determinístico; rodar UMA vez, da raiz do repo, com a
árvore limpa (`python tasks/trilha-e-manutencao/e15.4-scripts/split.py [--plan]`).

`--plan` só imprime a distribuição (classe de cada membro e tamanho de cada arquivo).
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import parse  # noqa: E402

EXEC_DIR = parse.ROOT / 'executor64'
STAY = 'Ir64BlockExecutor'
WRAP_COLUMN = 120

FAMILY_CLASS = {
    'IntegerOp64': 'Ir64IntegerExecutor',
    'MemoryOp64': 'Ir64MemoryExecutor',
    'BranchOp64': 'Ir64BranchExecutor',
    'SystemOp64': 'Ir64SystemExecutor',
    'FpOp64': 'Ir64FpMemoryExecutor',
    'AdvSimdMoveOp64': 'Ir64VectorMemoryExecutor',
}
# Membros sem record no parâmetro, ou usados por mais de uma classe nova: casa fixa.
EXPLICIT = {
    'executeUndefinedInstructionTrap': 'Ir64SystemExecutor',
    'executeFetch': STAY,
    'executeCycle': STAY,
    'CYCLES_PER_INSTRUCTION': STAY,
    # Endereçamento/acesso à memória, compartilhado por inteiro/FP/vetor/desvio.
    'readBaseRegister': 'Ir64MemoryExecutor',
    'writeBaseRegister': 'Ir64MemoryExecutor',
    'readMemory': 'Ir64MemoryExecutor',
    'writeMemory': 'Ir64MemoryExecutor',
    'transferAddress': 'Ir64MemoryExecutor',
    'writeback': 'Ir64MemoryExecutor',
    'signExtendFromSize': 'Ir64MemoryExecutor',
    'zeroTruncateToSize': 'Ir64MemoryExecutor',
}
CLASS_DOC = {
    'Ir64IntegerExecutor': [
        '/// Semântica das operações inteiras A64 ({@link IntegerOp64}): ALU, deslocamentos, bitfield,',
        '/// multiplicação/divisão, operações sobre flags, autenticação de ponteiro e tags de endereço.',
    ],
    'Ir64MemoryExecutor': [
        '/// Semântica dos acessos à memória A64 de registrador geral ({@link MemoryOp64}): load/store, pares,',
        '/// exclusivos, atômicos (LSE), cópia/preenchimento (MOPS) e tags de memória (MTE). Hospeda também',
        '/// os auxiliares de registrador-base, writeback e leitura/escrita que os load/store SIMD&FP reusam.',
    ],
    'Ir64BranchExecutor': [
        '/// Semântica dos desvios A64 ({@link BranchOp64}): `B`/`BL`/`BR`/`RET`, `B.cond`, `CBZ`/`TBZ` e as',
        '/// formas compare-and-branch.',
    ],
    'Ir64SystemExecutor': [
        '/// Semântica das operações de sistema A64 ({@link SystemOp64}): `SVC`/`HVC`/`SMC`, `MSR`/`MRS`,',
        '/// instruções de sistema, `ERET`, `BRK` e controle do modo streaming.',
    ],
    'Ir64FpMemoryExecutor': [
        '/// Semântica dos load/store SIMD&FP escalares A64 (records de memória de {@link FpOp64}) — a',
        '/// aritmética de ponto flutuante fica em {@link Ir64FpExecutor}.',
    ],
    'Ir64VectorMemoryExecutor': [
        '/// Semântica dos load/store de estruturas AdvSIMD (`LD1`-`LD4`/`ST1`-`ST4`, `LDnR`) e das formas de',
        '/// movimento vetorial de {@link AdvSimdMoveOp64} que viviam em {@link Ir64BlockExecutor}.',
    ],
}
CLASS_DOC_TAIL = [
    '///',
    '/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}',
    '/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.',
]
OP_PARAM = re.compile(r'\((?:Aarch64Core core, )?((\w+Op64)\.\w+) op\)')


def ident(name):
    return re.compile(r'(?<![\w.#])' + re.escape(name) + r'\b')


def assign(lines):
    """Devolve (itens em ordem de arquivo, dono por nome). Itens = constantes do topo + membros."""
    items = parse.constants(lines) + parse.members(lines)
    for c in items:
        c.setdefault('kind', 'constant')
        c.setdefault('declaration', c['start'] + sum(1 for l in c['lines'] if l.startswith('    ///')))
    owner = dict(EXPLICIT)
    record_of = {}
    for item in items:
        m = OP_PARAM.search(lines[item['declaration']])
        if item['kind'] == 'method' and m and item['name'].startswith('execute'):
            record_of[item['name']] = m.group(1)
            owner.setdefault(item['name'], FAMILY_CLASS[m.group(2)])
    body = {i['name']: '\n'.join(i['lines']) for i in items}
    pending = [i['name'] for i in items if i['name'] not in owner]
    while pending:
        progressed = False
        for name in list(pending):
            users = [u for u in body if u != name and ident(name).search(body[u])]
            if any(u not in owner for u in users):
                continue
            homes = {owner[u] for u in users}
            if len(homes) != 1:
                raise SystemExit(f'{name}: usado por {sorted(homes)} — declarar em EXPLICIT')
            owner[name] = homes.pop()
            pending.remove(name)
            progressed = True
        if not progressed:
            raise SystemExit(f'sem dono: {pending}')
    return items, owner, record_of


def cross_class(items, owner):
    """Nomes referenciados a partir de outra classe (precisam deixar de ser `private`)."""
    shared = set()
    for target in items:
        for user in items:
            if owner[user['name']] != owner[target['name']] and ident(target['name']).search('\n'.join(user['lines'])):
                shared.add(target['name'])
    return shared


def transform(item, items, owner, shared, record_of):
    """Linhas do membro já na classe nova: visibilidade + referências qualificadas."""
    home = owner[item['name']]
    out = []
    offset = item['declaration'] - item['start']
    for index, line in enumerate(item['lines']):
        if index == offset:
            if item['name'] in record_of or item['name'] == 'executeUndefinedInstructionTrap':
                line = re.sub(r'^    private boolean ', '    public static boolean ', line)
            elif item['name'] in shared:
                line = re.sub(r'^    private ', '    ', line)
            if line.startswith('    private boolean '):
                raise SystemExit(f'método de instância sem ponte: {item["name"]}')
        for other in items:
            name = other['name']
            if owner[name] == home or name == item['name']:
                continue
            line = ident(name).sub(owner[name] + '.' + name, line)
            if line.lstrip().startswith('///'):
                line = re.sub(r'(?<!\w)#' + re.escape(name) + r'\b', owner[name] + '#' + name, line)
        if line.lstrip().startswith('///'):
            line = re.sub(r'(?<!\w)#(step|executeBlock|executeOp)\b', r'Ir64BlockExecutor#\1', line)
        out.append(line)
    if (item['name'] in record_of or item['name'] == 'executeUndefinedInstructionTrap') and offset == 0:
        record = record_of.get(item['name'], 'SystemOp64.UndefinedInstructionTrap')
        out.insert(0, '    /// Executa {@link ' + record + '}.')
    return out


def used_imports(imports, body):
    kept = []
    for line in imports:
        simple = line.rstrip(';').rsplit('.', 1)[1]
        if re.search(r'(?<![\w.])' + simple + r'\b', body):
            kept.append(line)
    return kept


def render_class(name, items, owner, shared, record_of, imports):
    mine = [i for i in items if owner[i['name']] == name]
    top = [i for i in mine if i['kind'] == 'constant' and i['top']]
    rest = [i for i in mine if not (i['kind'] == 'constant' and i['top'])]
    body = []
    for item in top:
        body += transform(item, items, owner, shared, record_of)
    if top:
        body.append('')
    body += ['    private ' + name + '() {', '    }']
    for item in rest:
        body.append('')
        body += transform(item, items, owner, shared, record_of)
    doc = CLASS_DOC[name] + CLASS_DOC_TAIL
    text = '\n'.join(doc + body)
    kept = used_imports(imports, text)
    dev = [l for l in kept if l.startswith('import dev.')]
    java = [l for l in kept if not l.startswith('import dev.')]
    out = ['package dev.vitorsilverio.armjitter.executor64;', ''] + dev
    if java:
        out += [''] + java
    out += [''] + doc + ['public final class ' + name + ' {'] + body + ['}', '']
    return '\n'.join(out)


def rewrite_executor(lines, items, owner, imports):
    """`Ir64BlockExecutor` sem o `switch`, sem os membros movidos e sem os imports órfãos."""
    start, end = parse.switch_range(lines)
    drop = set(range(start, end + 2))
    for item in items:
        if owner[item['name']] != STAY:
            drop.update(range(item['start'], item['end'] + 1))
            following = item['end'] + 1
            if lines[following].strip() == '':
                drop.add(following)
    kept = [l for i, l in enumerate(lines) if i not in drop]
    text = '\n'.join(kept)
    text = text.replace('boolean pcChanged = execute(core, op);', 'boolean pcChanged = op.execute(core);')
    text = text.replace('        return execute(core, op);', '        return op.execute(core);')
    text = text.replace('boolean pcChanged = executeOp(core, ops[i]);', 'boolean pcChanged = ops[i].execute(core);')
    text = re.sub(r'\n\n\n+', '\n\n', text)
    text = re.sub(r'\n\n}\n$', '\n}\n', text)
    body = text.split('public final class', 1)[1]
    doc_and_body = text.split('\n/// ', 1)[1]
    for line in imports:
        simple = line.rstrip(';').rsplit('.', 1)[1]
        if not re.search(r'(?<![\w.])' + simple + r'\b', doc_and_body):
            text = text.replace(line + '\n', '')
    text = re.sub(r'\n\n\n+', '\n\n', text)
    del body
    return text


def bridge_target(kind, calls, items_owner):
    cls, method, has_core, _ = calls[kind]
    cls = cls or items_owner[method]
    return cls, method + ('(core, this)' if has_core else '(this)' if calls[kind][3] else '()')


def add_bridges(calls, owner):
    """Insere a ponte depois do `kind()` de cada record e os imports no arquivo da família."""
    mapping = []
    by_file = {}
    for record in parse.records():
        by_file.setdefault(record['file'], []).append(record)
    for path, recs in by_file.items():
        lines = parse.source_lines(path)
        classes = set()
        for record in sorted(recs, key=lambda r: -r['kind_line']):
            if record['kind'] not in calls:
                continue
            cls, call = bridge_target(record['kind'], calls, owner)
            classes.add(cls)
            one = '        @Override public boolean execute(Aarch64Core core) { return ' + cls + '.' + call + '; }'
            if len(one) <= WRAP_COLUMN:
                new = [one]
            else:
                new = ['        @Override public boolean execute(Aarch64Core core) {',
                       '            return ' + cls + '.' + call + ';',
                       '        }']
            lines[record['kind_line'] + 1:record['kind_line'] + 1] = new
            mapping.append((record['kind'], record['interface'] + '.' + record['name'], cls + '.' + call))
        if not classes:
            continue
        wanted = ['import dev.vitorsilverio.armjitter.core64.Aarch64Core;'] + [
            'import dev.vitorsilverio.armjitter.executor64.' + c + ';' for c in classes]
        package = next(i for i, l in enumerate(lines) if l.startswith('package '))
        existing = [i for i, l in enumerate(lines) if l.startswith('import ')]
        if existing:
            block = sorted(set(lines[existing[0]:existing[-1] + 1]) - {''} | set(wanted))
            dev = [l for l in block if l.startswith('import dev.')]
            other = [l for l in block if not l.startswith('import dev.')]
            lines[existing[0]:existing[-1] + 1] = dev + ([''] + other if other else [])
        else:
            lines[package + 1:package + 1] = [''] + sorted(wanted)
        path.write_text('\n'.join(lines), encoding='utf-8', newline='\n')
    return mapping


def publish_targets(calls):
    """Nas classes de `executor64` que já existiam: classe e método de entrada viram `public`."""
    by_class = {}
    for kind, (cls, method, _, record) in calls.items():
        if cls:
            by_class.setdefault(cls, []).append((method, record))
    for cls, targets in sorted(by_class.items()):
        path = EXEC_DIR / (cls + '.java')
        lines = parse.source_lines(path)
        index = next(i for i, l in enumerate(lines) if l.startswith('final class ' + cls))
        lines[index] = 'public ' + lines[index]
        for method, record in targets:
            found = None
            for i, l in enumerate(lines):
                if l.startswith('    static boolean ' + method + '('):
                    signature = ' '.join(x.strip() for x in lines[i:i + 3])
                    if re.search(r'\b' + re.escape(record) + r' op\)', signature):
                        found = i
                        break
            if found is None:
                raise SystemExit(f'{cls}.{method}({record}) não encontrado')
            lines[found] = '    public ' + lines[found][4:]
            if not lines[found - 1].startswith('    ///') and not lines[found - 1].startswith('    @'):
                lines.insert(found, '    /// Executa {@link ' + record + '}.')
        path.write_text('\n'.join(lines), encoding='utf-8', newline='\n')


def main():
    lines = parse.source_lines()
    calls = parse.kind_calls(lines)
    switch_start, _ = parse.switch_range(lines)
    items, owner, record_of = assign(lines)
    for item in items:
        item['top'] = item['start'] < switch_start
    shared = cross_class(items, owner)
    imports = [l for l in lines if l.startswith('import ')]
    classes = sorted(set(owner.values()) - {STAY})
    rendered = {c: render_class(c, items, owner, shared, record_of, imports) for c in classes}
    executor = rewrite_executor(lines, items, owner, imports)
    if '--plan' in sys.argv:
        for item in items:
            print(f"{owner[item['name']]:28s} {item['kind']:8s} {item['name']}{' [shared]' if item['name'] in shared else ''}")
        for c in classes:
            print(c, rendered[c].count('\n'))
        print(STAY, executor.count('\n'))
        return
    for c in classes:
        (EXEC_DIR / (c + '.java')).write_text(rendered[c], encoding='utf-8', newline='\n')
    parse.EXECUTOR.write_text(executor, encoding='utf-8', newline='\n')
    publish_targets(calls)
    mapping = add_bridges(calls, owner)
    out = Path(__file__).parent / 'mapping.tsv'
    out.write_text('kind\trecord\talvo\n' + '\n'.join('\t'.join(m) for m in sorted(mapping)) + '\n',
                   encoding='utf-8', newline='\n')
    print('pontes', len(mapping), 'classes novas', classes)


if __name__ == '__main__':
    main()
