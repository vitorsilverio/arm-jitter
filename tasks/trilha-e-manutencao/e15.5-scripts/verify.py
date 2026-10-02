"""E15.5 — confere, contra o `HEAD`, que a task só acrescentou pontes (rodar da raiz do repo).

1. Em cada `ir/*Op.java`: nenhuma linha removida; as acrescentadas são os 2 imports e uma ponte por
   `kind()`, e a ponte é exatamente a que `parse.bridge_line` deriva do `case` do mesmo `Kind` nos
   dois `switch` do `IrBlockExecutor` do `HEAD` (em `IrOp.java`, mais o método abstrato com Javadoc).
2. Em `IrBlockExecutor`: todo `case` que sobrou no laço é uma linha idêntica à do `HEAD`.
"""
import difflib
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import parse  # noqa: E402
import gen  # noqa: E402

BRIDGE_PREFIX = '        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) {'


def head(path):
    return subprocess.run(['git', 'show', 'HEAD:' + path.as_posix()], capture_output=True, check=True,
                          encoding='utf-8').stdout.split('\n')


def main():
    bridges = parse.bridges()
    total = 0
    for path in sorted(parse.IR.glob('*Op.java')):
        before, after = head(path), path.read_text(encoding='utf-8').split('\n')
        added, removed = [], []
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, before, after, autojunk=False).get_opcodes():
            if tag in ('replace', 'delete'):
                removed += before[i1:i2]
            if tag in ('replace', 'insert'):
                added += after[j1:j2]
        if removed:
            raise SystemExit(f'{path.name}: linhas removidas: {removed[:3]}')
        rest = [l for l in added if not l.startswith(BRIDGE_PREFIX)]
        count = 0
        for i, line in enumerate(after):
            m = gen.KIND_LINE.match(line)
            if not m:
                continue
            if after[i + 1] != parse.bridge_line(bridges[m.group(1)]):
                raise SystemExit(f'{path.name}: ponte de {m.group(1)} não é a do case original')
            count += 1
        if count != len(added) - len(rest):
            raise SystemExit(f'{path.name}: {len(added) - len(rest)} pontes acrescentadas para {count} kind()')
        if count and rest[:2] != gen.NEW_IMPORTS:
            raise SystemExit(f'{path.name}: imports inesperados {rest[:2]}')
        extra = rest[2:] if count else rest
        if path.name == 'IrOp.java':
            code = [l for l in extra if l and not l.startswith('    ///')]
            if code != ['    boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc);']:
                raise SystemExit(f'IrOp.java: acréscimo inesperado {code}')
        elif extra:
            raise SystemExit(f'{path.name}: acréscimo inesperado {extra}')
        total += count
        if count:
            print(f'{path.name}: {count} pontes, {len(after) - 1} linhas')
    print('pontes conferidas:', total, 'de', len(bridges))

    original = set(parse.head_source().split('\n'))
    executor = Path(parse.EXECUTOR_PATH).read_text(encoding='utf-8').split('\n')
    cases = [l for l in executor if re.match(r'\s+case IrOp\.Kind\.', l)]
    foreign = [l for l in cases if l not in original]
    if foreign:
        raise SystemExit(f'case que não existia no HEAD: {foreign}')
    print('cases no laço:', len(cases), '(todos idênticos aos do HEAD);', 'IrBlockExecutor', len(executor) - 1, 'linhas')


if __name__ == '__main__':
    main()
