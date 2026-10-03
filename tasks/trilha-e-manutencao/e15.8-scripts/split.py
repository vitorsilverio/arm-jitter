"""E15.8 — divide `IrSystemExecutor` em sistema + 5 executores MVE (rodar da raiz do repo).

Lê `IrSystemExecutor` de HEAD (determinístico), atribui cada membro a uma família pelo tipo do
parâmetro `op` (métodos `execute*`) ou pelo conjunto de famílias que o usam (helpers/constantes,
até ponto fixo), gera as classes novas e reescreve as pontes dos records MVE.

`python split.py --dry` só imprime a partição e os tamanhos.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path('core/src/main/java/dev/vitorsilverio/armjitter')
EXEC_DIR = ROOT / 'codegen' / 'executor'
SYSTEM = EXEC_DIR / 'IrSystemExecutor.java'
IR = ROOT / 'ir'

STAY = 'IrSystemExecutor'
SUPPORT = 'IrMveSupport'
FAMILIES = {
    'MvePredicationOp': 'IrMvePredicationExecutor',
    'MveMoveOp': 'IrMveMoveExecutor',
    'MveIntegerOp': 'IrMveIntegerExecutor',
    'MveFpOp': 'IrMveFpExecutor',
    'MveReductionOp': 'IrMveReductionExecutor',
}
INSTANCE = {'IrMveMoveExecutor'}  # única família que usa `support`
HEADER_DOC = {
    'IrMvePredicationExecutor': [
        '/// Executa a predicação MVE (perfil M, B16, MVE/Helium) da IR interpretada: `VPST`/`VPNOT`/`VPSEL`,',
        '/// tail-predication (`LCTP`/`VCTP`), avanço pós-instrução de `VPR`/`ECI`, `VMSR`/`VMRS` do `VPR` e as',
        '/// comparações que gravam `VPR.P0`.',
        '///',
        '/// Sem estado: os records de {@link MvePredicationOp} chamam os métodos estáticos direto (task',
        '/// E15.8), e a JVM só carrega esta classe na primeira op MVE.',
    ],
    'IrMveMoveExecutor': [
        '/// Executa os movimentos MVE (perfil M, B16, MVE/Helium) da IR interpretada: load/store contíguo,',
        '/// alargante, gather/scatter e intercalado, além de `VDUP`/`VIDUP`/`VIWDUP`, `VMOV` entre lanes e GPR',
        '/// e `VMOV` imediato.',
        '///',
        '/// É a única família MVE com estado (os acessos à memória passam por `IrExecutionSupport`, que conhece',
        '/// a arquitetura): {@link IrBlockExecutor#mveMoveExecutor()} cria a instância na primeira op (task',
        '/// E15.8).',
    ],
    'IrMveIntegerExecutor': [
        '/// Executa as operações MVE inteiras (perfil M, B16, MVE/Helium) da IR interpretada: vetor × vetor,',
        '/// vetor × escalar, alargantes, estreitantes, deslocamentos, unárias e os deslocamentos longos em GPR.',
        '///',
        '/// Sem estado: os records de {@link MveIntegerOp} chamam os métodos estáticos direto (task E15.8).',
    ],
    'IrMveFpExecutor': [
        '/// Executa as operações MVE de ponto flutuante (perfil M, B16, MVE/Helium) da IR interpretada.',
        '///',
        '/// Sem estado: os records de {@link MveFpOp} chamam os métodos estáticos direto (task E15.8).',
    ],
    'IrMveReductionExecutor': [
        '/// Executa as reduções MVE (perfil M, B16, MVE/Helium) da IR interpretada: somas, acumulações duplas',
        '/// e mínimo/máximo através do vetor.',
        '///',
        '/// Sem estado: os records de {@link MveReductionOp} chamam os métodos estáticos direto (task E15.8).',
    ],
    'IrMveSupport': [
        '/// Constantes e helpers compartilhados pelos executores MVE (task E15.8).',
    ],
}

MEMBER = re.compile(r'^    (?:private |public |protected |static |final )+[\w<>\[\], .]+? (\w+)\(')
CONSTANT = re.compile(r'^    (?:private |public )?static final [\w<>\[\]]+ (\w+) =')
OP_PARAM = re.compile(r'\bArmCore core,\s+(Mve\w+Op)\.\w+ op\)')
LINK = re.compile(r'\{@link #(\w+)')


def head_lines():
    out = subprocess.run(['git', 'show', 'HEAD:' + SYSTEM.as_posix()], capture_output=True, check=True).stdout
    return out.decode('utf-8').replace('\r\n', '\n').split('\n')


def parse(lines):
    """Devolve (prefixo, membros, sufixo). Membro = dict(name, kind, lines, decl)."""
    start = next(i for i, l in enumerate(lines) if l.startswith('    IrSystemExecutor(IrExecutionSupport'))
    end = next(i for i in range(start, len(lines)) if lines[i] == '    }')
    last = max(i for i, l in enumerate(lines) if l == '}')
    members = []
    i = end + 1
    while i < last:
        if lines[i].strip() == '':
            i += 1
            continue
        first = i
        while lines[i].startswith('    ///') or lines[i].startswith('    //') or lines[i].startswith('    @'):
            i += 1
        c = CONSTANT.match(lines[i])
        if c:
            decl = i
            while not lines[i].rstrip().endswith(';'):
                i += 1
            members.append({'name': c.group(1), 'kind': 'constant', 'lines': lines[first:i + 1],
                            'decl': lines[decl:i + 1]})
            i += 1
            continue
        m = MEMBER.match(lines[i])
        if not m:
            raise SystemExit(f'membro não reconhecido na linha {i + 1}: {lines[i]}')
        decl_start = i
        while not lines[i].rstrip().endswith('{'):
            i += 1
        decl = lines[decl_start:i + 1]
        while lines[i] != '    }':
            i += 1
        members.append({'name': m.group(1), 'kind': 'method', 'lines': lines[first:i + 1], 'decl': decl})
        i += 1
    return lines[:end + 1], members, lines[last:]


def body_text(m):
    # Sem Javadoc: uso real, não menção em comentário.
    return '\n'.join(l for l in m['lines'] if not l.lstrip().startswith('//'))


def assign(members):
    owner = {}
    for m in members:
        if m['kind'] == 'method':
            p = OP_PARAM.search(' '.join(m['decl']))
            if p:
                owner[m['name']] = FAMILIES[p.group(1)]
    names = [m['name'] for m in members]
    text = {m['name']: body_text(m) for m in members}

    def users(target):
        return [n for n in names if n != target and re.search(r'\b' + target + r'\b', text[n])]

    # Membros que não são `execute*` de uma família: tudo que é usado por algum membro que fica
    # (método sem op MVE) fica; o resto vai para a família de quem usa (ponto fixo).
    pending = [n for n in names if n not in owner]
    for n in pending:
        decl = ' '.join(next(m for m in members if m['name'] == n)['decl'])
        if n.startswith('execute') or 'public' in decl:
            owner[n] = STAY
    changed = True
    while changed:
        changed = False
        for n in pending:
            if n in owner:
                continue
            us = users(n)
            fams = {owner.get(u) for u in us}
            if None in fams:
                continue
            if not fams:
                raise SystemExit(f'{n}: sem usuário')
            owner[n] = fams.pop() if len(fams) == 1 else (STAY if STAY in fams else SUPPORT)
            changed = True
    leftover = [n for n in pending if n not in owner]
    if leftover:
        raise SystemExit(f'sem dono: {leftover}')
    # Um membro de sistema usado também por MVE seria referência cruzada: recusar.
    for n, o in owner.items():
        if o == STAY:
            for u in users(n):
                if owner[u] != STAY:
                    raise SystemExit(f'{n} fica em {STAY} mas é usado por {u} ({owner[u]})')
    return owner


def transform(m, cls, owner):
    out = []
    for line in m['lines']:
        if not line.lstrip().startswith('//'):
            if cls in FAMILIES.values() and cls not in INSTANCE and m['kind'] == 'method':
                line = re.sub(r'^    public (boolean|void) ', r'    public static \1 ', line)
            if cls == SUPPORT:
                line = re.sub(r'^    private static ', '    static ', line)
            if cls not in INSTANCE and re.search(r'\bsupport\.', line):
                raise SystemExit(f'{m["name"]} usa support. mas vai para {cls}')
            # Referências a membros compartilhados → qualificadas.
            if cls != SUPPORT:
                for name, o in owner.items():
                    if o == SUPPORT:
                        line = re.sub(r'(?<![\w.#])' + name + r'\b', SUPPORT + '.' + name, line)
        # Links `{@link #x}` para membro de outra classe → `{@link Dono#x}`.
        def fix(match):
            target = match.group(1)
            o = owner.get(target)
            if o is None or o == cls:
                return match.group(0)
            return '{@link ' + o + '#' + target
        line = LINK.sub(fix, line)
        out.append(line)
    return out


def imports_for(body, all_imports):
    used = []
    for imp in all_imports:
        simple = imp.rstrip(';').split('.')[-1]
        if re.search(r'\b' + simple + r'\b', body):
            used.append(imp)
    return used


def render(cls, members, owner, all_imports):
    body = []
    if cls in INSTANCE:
        body += ['    private final IrExecutionSupport support;', '',
                 f'    {cls}(IrExecutionSupport support) {{', '        this.support = support;', '    }', '']
    else:
        body += [f'    private {cls}() {{', '    }', '']
    for m in members:
        body += transform(m, cls, owner) + ['']
    while body and body[-1] == '':
        body.pop()
    text = '\n'.join(body + HEADER_DOC[cls])
    imports = imports_for(text, all_imports)
    visibility = 'final class' if cls == SUPPORT else 'public final class'
    lines = ['package dev.vitorsilverio.armjitter.codegen.executor;', '']
    lines += imports + [''] if imports else []
    lines += HEADER_DOC[cls] + [f'{visibility} {cls} {{'] + body + ['}', '']
    return '\n'.join(lines)


def rewrite_bridges(owner):
    count = 0
    for path in sorted(IR.glob('Mve*Op.java')):
        text = path.read_text(encoding='utf-8')
        used = set()

        def repl(match):
            nonlocal count
            method = match.group(1)
            cls = owner.get(method)
            if cls is None or cls == STAY:
                return match.group(0)
            count += 1
            used.add(cls)
            if cls in INSTANCE:
                return 'executor.mveMoveExecutor().' + method + '('
            return cls + '.' + method + '('
        text = re.sub(r'executor\.systemExecutor\(\)\.(\w+)\(', repl, text)
        for cls in sorted(used - INSTANCE):
            imp = f'import dev.vitorsilverio.armjitter.codegen.executor.{cls};\n'
            anchor = 'import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;\n'
            if anchor not in text:
                raise SystemExit(f'{path}: sem import de IrBlockExecutor')
            # Ordem alfabética: IrBlockExecutor < IrMve*.
            text = text.replace(anchor, anchor + imp, 1)
        path.write_text(text, encoding='utf-8', newline='\n')
    return count


def main():
    lines = head_lines()
    prefix, members, suffix = parse(lines)
    owner = assign(members)
    groups = {}
    for m in members:
        groups.setdefault(owner[m['name']], []).append(m)
    for cls, ms in groups.items():
        size = sum(len(m['lines']) + 1 for m in ms)
        print(f'{cls:28s} membros={len(ms):3d} linhas~{size}')
        if '--dry' in sys.argv:
            print('   ', ' '.join(m['name'] for m in ms if m['kind'] != 'method' or not m['name'].startswith('execute')))
    if '--dry' in sys.argv:
        return
    all_imports = [l for l in prefix if l.startswith('import ')]
    # IrSystemExecutor: prefixo + membros que ficam.
    stay = []
    for m in groups[STAY]:
        stay += m['lines'] + ['']
    while stay and stay[-1] == '':
        stay.pop()
    head_part = [l for l in prefix]
    new_system = head_part + [''] + stay + suffix
    text = '\n'.join(new_system)
    # Recalcula os imports de IrSystemExecutor.
    body_only = '\n'.join(new_system[next(i for i, l in enumerate(new_system) if l.startswith('///')):])
    keep = imports_for(body_only, all_imports)
    text = text.replace('\n'.join(all_imports), '\n'.join(keep), 1)
    SYSTEM.write_text(text, encoding='utf-8', newline='\n')
    for cls in list(FAMILIES.values()) + [SUPPORT]:
        if cls not in groups:
            continue
        (EXEC_DIR / f'{cls}.java').write_text(render(cls, groups[cls], owner, all_imports),
                                              encoding='utf-8', newline='\n')
    print('pontes reescritas:', rewrite_bridges(owner))


if __name__ == '__main__':
    main()
