"""E15.5 — leitura dos dois `switch` de `IrBlockExecutor` (rodar da raiz do repo).

`bridges(text)` devolve, para cada `Kind`, o record e o alvo que os DOIS dispatchers chamam hoje
(`execute` por `Kind`, `executeOp` por tipo), depois de conferir que os dois concordam.
"""
import re
import subprocess
from pathlib import Path

ROOT = Path('core/src/main/java/dev/vitorsilverio/armjitter')
EXECUTOR_PATH = 'core/src/main/java/dev/vitorsilverio/armjitter/codegen/executor/IrBlockExecutor.java'
IR = ROOT / 'ir'

# Campo de `IrBlockExecutor` -> accessor público (o de `neon` nasce nesta task).
ACCESSOR = {
    'alu': 'aluExecutor', 'memory': 'memoryExecutor', 'branch': 'branchExecutor',
    'transfer': 'transferExecutor', 'system': 'systemExecutor', 'cycle': 'cycleExecutor',
    'vfp': 'vfpExecutor', 'neon': 'neonExecutor',
}
# Os dois dispatchers divergem DE PROPÓSITO nestes `Kind` (o laço do bloco soma ciclos e pula o
# avanço de VPT/ECI quando o PC já mudou); a ponte segue o `executeOp`, o laço mantém o seu caso.
BLOCK_ONLY = {'CYCLE', 'FETCH', 'ADVANCE_VPT', 'ADVANCE_ECI'}

KIND_CASE = re.compile(
    r'case IrOp\.Kind\.(\w+) -> (?:\{ if \(!pcChanged\) \{ )?(cycles \+= |pcChanged \|= )?'
    r'(\w+) ?\.(\w+)\(\s*(core, )?\(([\w.]+)\) op(, block\.endPc\(\))?\);')
TYPE_CASE = re.compile(
    r'case ([\w.]+) (\w+) -> (\{ )?(\w+) ?\.(\w+)\(\s*(core, )?(\w+)(, blockEndPc)?\);( yield false; \})?')


def head_source():
    """`IrBlockExecutor.java` como está no `HEAD` — a fonte da verdade antes da task."""
    return subprocess.run(['git', 'show', 'HEAD:' + EXECUTOR_PATH], capture_output=True, check=True,
                          encoding='utf-8').stdout


def _flat(text, start_marker, end_marker):
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    lines = [l.strip() for l in text[start:end].split('\n') if not l.strip().startswith('//')]
    return ' '.join(lines)


def kind_cases(text):
    """`Kind` -> dict(record, field, method, core, end_pc, pc) do `switch (kinds[i])`."""
    flat = _flat(text, 'switch (kinds[i]) {', 'default -> throw')
    cases = {}
    for m in KIND_CASE.finditer(flat):
        kind, prefix, field, method, core, record, end_pc = m.groups()
        cases[kind] = {'record': record, 'field': field, 'method': method, 'core': bool(core),
                       'end_pc': bool(end_pc), 'pc': prefix == 'pcChanged |= '}
    if flat.count('case IrOp.Kind.') != len(cases):
        raise SystemExit(f'switch por Kind: {flat.count("case IrOp.Kind.")} casos, {len(cases)} lidos')
    return cases


def type_cases(text):
    """record -> dict(field, method, core, end_pc, pc) do `switch (op)` de `executeOp`."""
    flat = _flat(text, 'return switch (op) {', '        };')
    cases = {}
    for m in TYPE_CASE.finditer(flat):
        record, binding, brace, field, method, core, argument, end_pc, yields = m.groups()
        if argument != binding or bool(brace) != bool(yields):
            raise SystemExit(f'caso inesperado em executeOp: {m.group(0)}')
        cases[record] = {'field': field, 'method': method, 'core': bool(core), 'end_pc': bool(end_pc),
                         'pc': not yields}
    if flat.count('case ') != len(cases):
        raise SystemExit(f'switch por tipo: {flat.count("case ")} casos, {len(cases)} lidos')
    return cases


def bridges(text=None):
    """`Kind` -> dict(record, field, method, core, end_pc, pc), conferido nos dois dispatchers."""
    text = text or head_source()
    by_kind = kind_cases(text)
    by_type = type_cases(text)
    if {c['record'] for c in by_kind.values()} != set(by_type):
        raise SystemExit('os dois switch não cobrem os mesmos records')
    result = {}
    for kind, block in by_kind.items():
        record = block['record']
        op = by_type[record]
        same_target = all(block[k] == op[k] for k in ('field', 'method', 'core', 'end_pc'))
        if not same_target or (kind not in BLOCK_ONLY and block['pc'] != op['pc']):
            raise SystemExit(f'{kind}: execute e executeOp divergem ({block} x {op})')
        result[kind] = dict(op, record=record)
    return result


def bridge_line(b):
    """A ponte de uma linha de um record (8 espaços de recuo, logo depois do `kind()`)."""
    arguments = ('core, ' if b['core'] else '') + 'this' + (', blockEndPc' if b['end_pc'] else '')
    call = f'executor.{ACCESSOR[b["field"]]}().{b["method"]}({arguments});'
    body = f'return {call}' if b['pc'] else f'{call} return false;'
    return ('        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) '
            f'{{ {body} }}')


if __name__ == '__main__':
    found = bridges()
    print('kinds', len(found), 'devolvem pcChanged', sum(b['pc'] for b in found.values()),
          'usam blockEndPc', sum(b['end_pc'] for b in found.values()))
    fields = {}
    for b in found.values():
        fields[b['field']] = fields.get(b['field'], 0) + 1
    print(fields)
