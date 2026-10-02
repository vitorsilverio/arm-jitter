"""E15.4 — confere que o split foi puro deslocamento (rodar da raiz do repo, depois do `split.py`).

1. Cada membro que saiu de `Ir64BlockExecutor` (lido de `git show HEAD:`) aparece numa classe nova
   com as mesmas linhas, fora de: visibilidade na declaração, chamadas/links qualificados com o nome
   da outra classe, e a linha `/// Executa {@link …}.` acrescentada onde não havia Javadoc.
2. Cada `case` do `switch` antigo virou uma ponte no record certo, chamando o mesmo alvo.
3. Nos executores que já existiam, o diff contra HEAD é só `public` + a linha de Javadoc.
"""
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import parse  # noqa: E402
import split  # noqa: E402

EXEC = 'core/src/main/java/dev/vitorsilverio/armjitter/executor64/'
QUALIFIER = re.compile(r'\bIr64\w+Executor[.#]')
ADDED_DOC = re.compile(r'^    /// Executa \{@link [\w.]+\}\.$')
# O único método novo (não movido): `Ir64SystemExecutor.executeStreamingRestricted`, que recebeu o
# corpo do `case STREAMING_RESTRICTED` (escrito por `hand_edits.py`).
HAND_ADDED = {
    '    /// {@link Ir64Op.StreamingRestricted} (B18.2): `UNDEFINED` quando a restrição do modo streaming se',
    '    /// aplica (`PSTATE.SM = 1` sem `FEAT_SME_FA64` efetivo); senão executa a operação embrulhada.',
    '    public static boolean executeStreamingRestricted(Aarch64Core core, Ir64Op.StreamingRestricted op) {',
    '        if (core.streamingRestrictionApplies()) {',
    '            throw new Aarch64UndefinedInstructionException();',
    '        }',
    '        return op.inner().execute(core);',
}


def head(path):
    out = subprocess.run(['git', 'show', 'HEAD:' + path], capture_output=True, check=True).stdout
    return out.decode('utf-8').replace('\r\n', '\n').split('\n')


def normalize(line):
    line = QUALIFIER.sub(lambda m: '#' if m.group(0).endswith('#') else '', line)
    line = re.sub(r'^    public static boolean ', '    private boolean ', line)
    return line


def check_moved():
    old = head(EXEC + 'Ir64BlockExecutor.java')
    items, owner, _ = split.assign(old)
    moved = [i for i in items if owner[i['name']] != split.STAY]
    total = changed = 0
    for cls in sorted({owner[i['name']] for i in moved}):
        new = parse.source_lines(Path(EXEC + cls + '.java'))
        cursor = new.index('public final class ' + cls + ' {') + 1
        for item in [i for i in moved if owner[i['name']] == cls]:
            for line in item['lines']:
                total += 1
                while True:
                    if cursor >= len(new):
                        raise SystemExit(f'{cls}: linha de {item["name"]} não encontrada: {line}')
                    candidate = new[cursor]
                    cursor += 1
                    if candidate == line:
                        break
                    relaxed = re.sub(r'^    (private )?(static |record )', r'    \2', line)
                    if normalize(candidate) in (line, relaxed) or re.sub(r'^    private ', '    ', line) == candidate:
                        changed += 1
                        break
                    if candidate.strip() and not ADDED_DOC.match(candidate) and candidate not in HAND_ADDED \
                            and not candidate.startswith('    private ' + cls + '()') and candidate != '    }':
                        raise SystemExit(f'{cls}: linha inesperada antes de {item["name"]}: {candidate!r}\n'
                                         f'esperava: {line!r}')
    print(f'movidos: {len(moved)} membros, {total} linhas, {changed} diferentes (visibilidade/qualificação)')


def check_bridges():
    old = head(EXEC + 'Ir64BlockExecutor.java')
    calls = parse.kind_calls(old)
    _, owner, _ = split.assign(old)
    seen = 0
    for record in parse.records():
        lines = parse.source_lines(record['file'])
        bridge = ' '.join(l.strip() for l in lines[record['kind_line'] + 1:record['kind_line'] + 4])
        if record['kind'] not in calls:
            continue
        cls, call = split.bridge_target(record['kind'], calls, owner)
        expected = '@Override public boolean execute(Aarch64Core core) { return ' + cls + '.' + call + '; }'
        if not bridge.startswith(expected):
            raise SystemExit(f'{record["interface"]}.{record["name"]}: ponte errada: {bridge}')
        declared = calls[record['kind']][3]
        if declared and declared != record['interface'] + '.' + record['name']:
            raise SystemExit(f'{record["kind"]}: case era de {declared}, ponte em {record["interface"]}.{record["name"]}')
        seen += 1
    print(f'pontes: {seen} conferidas contra os {len(calls)} cases do switch antigo')


def check_existing():
    names = subprocess.run(['git', 'diff', '--name-only', 'HEAD', '--', EXEC], capture_output=True, check=True,
                           text=True).stdout.split()
    files = lines = 0
    for path in names:
        if path.endswith('Ir64BlockExecutor.java'):
            continue
        old, new = head(path), parse.source_lines(Path(path))
        j = 0
        for line in new:
            if j < len(old) and line == old[j]:
                j += 1
            elif j < len(old) and line == 'public ' + old[j] or j < len(old) and line == '    public ' + old[j][4:]:
                j += 1
                lines += 1
            elif ADDED_DOC.match(line):
                lines += 1
            elif j < len(old) and re.sub(r'Ir64\w+Executor#', 'Ir64BlockExecutor#', line) == old[j]:
                j += 1      # menção em prosa atualizada pelo comments.py
                lines += 1
            else:
                raise SystemExit(f'{path}: mudança inesperada: {line!r}')
        if j != len(old):
            raise SystemExit(f'{path}: linhas de HEAD sobrando a partir de {j + 1}')
        files += 1
    print(f'executores pré-existentes: {files} arquivos, {lines} linhas mudadas (só `public` e Javadoc de 1 linha)')


if __name__ == '__main__':
    check_moved()
    check_bridges()
    check_existing()
