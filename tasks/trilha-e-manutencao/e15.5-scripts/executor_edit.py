"""E15.5 — troca os dois `switch` de `IrBlockExecutor` pela ponte `IrOp#execute`.

Rodar UMA vez, da raiz do repo, depois do `gen.py`. `HOT` lista os `Kind` que continuam com caso
próprio no laço além dos 4 estruturais (vazio = forma de partida do épico); cada caso é copiado
do `switch` original, sem reescrever.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import parse  # noqa: E402

HOT = sys.argv[1:]

LOOP_HEAD = [
    '                // Caso próprio só para `Cycle`/`Fetch` (2 de cada 3 ops; o laço soma os ciclos){hot}',
    '                // Todo o resto é roteado pela própria op (`IrOp#execute`).',
    '                IrOp op = ops[i];',
    '                switch (kinds[i]) {',
    '                case IrOp.Kind.CYCLE -> cycles += cycle.executeCycle((IrOp.Cycle) op);',
    '                case IrOp.Kind.FETCH -> cycle.executeFetch(core, (IrOp.Fetch) op);',
]
LOOP_TAIL = [
    '                // ADVANCE_VPT (B16.2)/ADVANCE_ECI (B16.5) são pulados quando a instrução MVE anterior no',
    '                // MESMO bloco já mudou o PC (fault de ECI reservado) — ver Javadoc de',
    '                // IrSystemExecutor#executeAdvanceVpt. `executeOp` não tem esse gate. A checagem fica',
    '                // no `default` (e não em casos próprios) para o `switch` continuar denso (`tableswitch`).',
    '                default -> {',
    '                    if (!pcChanged || !isPredicationAdvance(kinds[i])) {',
    '                        pcChanged |= op.execute(this, core, block.endPc());',
    '                    }',
    '                }',
    '                }',
]
ADVANCE_HELPER = [
    '    /// `true` para os dois `Kind` que só avançam o estado de predicação/ECI de uma instrução MVE',
    '    /// que terminou sem fault — os únicos que {@link #execute} pula depois de o PC ter mudado.',
    '    private static boolean isPredicationAdvance(int kind) {',
    '        return kind == IrOp.Kind.ADVANCE_VPT || kind == IrOp.Kind.ADVANCE_ECI;',
    '    }',
    '',
]
NEON_ACCESSOR = [
    '',
    '    /// Executor de NEON (task E15.5): ver {@link #aluExecutor()}.',
    '    public IrNeonExecutor neonExecutor() {',
    '        return neon;',
    '    }',
]


def main():
    path = Path(parse.EXECUTOR_PATH)
    lines = parse.head_source().split('\n')

    def index(prefix, start=0):
        return next(i for i in range(start, len(lines)) if lines[i].startswith(prefix))

    comment = index('                // Dispatch O(1) por discriminador inteiro')
    switch = index('                switch (kinds[i]) {')
    default = index('                    default -> throw new IllegalStateException')
    hot_cases = []
    for kind in HOT:
        case = index(f'                case IrOp.Kind.{kind} -> ', switch)
        if not lines[case].rstrip().endswith(';'):
            raise SystemExit(f'{kind}: caso de mais de uma linha')
        hot_cases.append(lines[case])
    loop = list(LOOP_HEAD)
    if HOT:
        loop[0] = loop[0].replace('{hot}', ' e para os')
        loop[1:1] = [
            f'                // {len(HOT)} `Kind` que o gate de desempenho da E15.5 mediu como quentes (99,98% das ops em 5',
            '                // jogos de GBA): ali `tableswitch` + chamada direta é inlinável, e a ponte megamórfica',
            '                // custou até 15% de throughput.',
        ]
    else:
        loop[0] = loop[0].replace('{hot}', '.')
    loop += hot_cases + LOOP_TAIL

    owner_doc = index('    /// Endereço da instrução dona da op')
    op_start = index('        return switch (op) {')
    op_end = index('        };', op_start)
    vfp_accessor_end = index('    }', index('    public IrVfpExecutor vfpExecutor()'))

    out = (lines[:comment] + loop + lines[default + 2:owner_doc] + ADVANCE_HELPER + lines[owner_doc:op_start]
           + ['        return op.execute(this, core, blockEndPc);']
           + lines[op_end + 1:vfp_accessor_end + 1] + NEON_ACCESSOR + lines[vfp_accessor_end + 1:])
    body = '\n'.join(l for l in out if not l.startswith('import '))
    out = [l for l in out if not (l.startswith('import dev.vitorsilverio.armjitter.ir.')
                                  and not re.search(r'\b' + l.rsplit('.', 1)[1].rstrip(';') + r'\b', body))]
    path.write_text('\n'.join(out), encoding='utf-8', newline='\n')
    print(f'{path.name}: {len(out) - 1} linhas, quentes no switch: {HOT or "nenhum"}')


if __name__ == '__main__':
    main()
