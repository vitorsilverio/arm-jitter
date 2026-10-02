"""E15.5 — insere a ponte `execute` em cada record de `ir/` e grava `mapping.tsv`.

Determinístico; rodar UMA vez, da raiz do repo, com `ir/` limpo:
`python tasks/trilha-e-manutencao/e15.5-scripts/gen.py`
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import parse  # noqa: E402

KIND_LINE = re.compile(r'^        @Override public int kind\(\) \{ return Kind\.(\w+); \}$')
ANCHOR_IMPORT = 'import dev.vitorsilverio.armjitter.core.Condition;'
NEW_IMPORTS = [
    'import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;',
    'import dev.vitorsilverio.armjitter.core.ArmCore;',
]


def main():
    bridges = parse.bridges()
    seen = set()
    for path in sorted(parse.IR.glob('*Op.java')):
        lines = path.read_text(encoding='utf-8').split('\n')
        if any('boolean execute(IrBlockExecutor' in l for l in lines):
            raise SystemExit(f'{path}: já tem ponte — rodar com ir/ limpo')
        out = []
        added = 0
        for line in lines:
            out.append(line)
            m = KIND_LINE.match(line)
            if m:
                out.append(parse.bridge_line(bridges[m.group(1)]))
                seen.add(m.group(1))
                added += 1
        if not added:
            continue
        anchor = out.index(ANCHOR_IMPORT)
        out[anchor:anchor] = NEW_IMPORTS
        path.write_text('\n'.join(out), encoding='utf-8', newline='\n')
        print(f'{path.name}: {added} pontes, {len(out) - 1} linhas')
    missing = sorted(set(bridges) - seen)
    if missing:
        raise SystemExit(f'Kind sem record: {missing}')
    rows = ['kind\trecord\texecutor\tmétodo\tdevolve pcChanged\trecebe blockEndPc']
    for kind, b in bridges.items():
        rows.append('\t'.join([kind, b['record'], parse.ACCESSOR[b['field']], b['method'],
                               'sim' if b['pc'] else 'não', 'sim' if b['end_pc'] else 'não']))
    (Path(__file__).parent / 'mapping.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
