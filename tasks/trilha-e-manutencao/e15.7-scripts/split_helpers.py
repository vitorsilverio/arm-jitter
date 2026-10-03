"""E15.7 fase 2 — divide `AsmRuntimeHelpers` por seção (rodar da raiz do repo).

argv[1] = cópia do `AsmRuntimeHelpers.java` de HEAD. Cada seção `// ── x ──` vai inteira para uma
classe; chamadas sem qualificador a métodos que foram para outra classe ganham `Classe.`; links
`{@link #m}` idem. Depois troca o owner `HELPERS` dos emissores pelo da família do helper.
"""
import re
import sys
from pathlib import Path

PKG = Path('core/src/main/java/dev/vitorsilverio/armjitter/codegen/jvm')
SOURCE = Path(sys.argv[1]).read_text(encoding='utf-8').replace('\r\n', '\n').split('\n')

SECTIONS = {
    'condição': 'AsmFlagHelpers', 'flags ALU': 'AsmFlagHelpers', 'shifts': 'AsmFlagHelpers',
    'operando shifted-register': 'AsmFlagHelpers', 'shifts de opcode ALU com S': 'AsmFlagHelpers',
    'memória': 'AsmMemoryHelpers', 'LDM/STM/PUSH/POP': 'AsmMemoryHelpers',
    'ARMv6/v6K (B1.4): acessos exclusivos': 'AsmMemoryHelpers',
    'ARMv5TE (saturação / DSP)': 'AsmIntegerHelpers', 'ARMv6 (B1.2): extend/reverse': 'AsmIntegerHelpers',
    'ARMv6 (B1.3): paralelas / SEL / saturação / USAD': 'AsmIntegerHelpers',
    'branches': 'AsmSystemHelpers', 'PSR': 'AsmSystemHelpers', 'SWI': 'AsmSystemHelpers',
    'coprocessor': 'AsmSystemHelpers', 'undefined': 'AsmSystemHelpers',
    'VFP (B3.6, PR2)': 'AsmVfpHelpers',
}
DOCS = {
    'AsmFlagHelpers': 'condição de execução (guards `condXx` e `evalCond`), guard do loop-superbloco, '
                      'flags NZCV da ALU, shifts e operando shifted-register',
    'AsmMemoryHelpers': 'acesso à memória (alinhado, desalinhado atravessado, BE8), LDM/STM/PUSH/POP e '
                        'acessos exclusivos',
    'AsmIntegerHelpers': 'inteiro ARMv5TE (saturação/DSP) e ARMv6 (extend/reverse, paralelas, SEL, '
                         'saturação, USAD)',
    'AsmSystemHelpers': 'desvio/escrita em PC (interworking), PSR, SWI, coprocessador e undefined',
    'AsmVfpHelpers': 'VFP (B3.6, PR2)',
}
IMPORTS = {
    'ArmCore': 'dev.vitorsilverio.armjitter.core.ArmCore',
    'ArmException': 'dev.vitorsilverio.armjitter.core.ArmException',
    'Condition': 'dev.vitorsilverio.armjitter.core.Condition',
    'CpuMode': 'dev.vitorsilverio.armjitter.core.CpuMode',
    'FpscrRegister': 'dev.vitorsilverio.armjitter.core.FpscrRegister',
    'VfpRegisters': 'dev.vitorsilverio.armjitter.core.VfpRegisters',
    'BlockTransferMode': 'dev.vitorsilverio.armjitter.decoder.BlockTransferMode',
    'ParallelAluOp': 'dev.vitorsilverio.armjitter.ir.ParallelAluOp',
    'ParallelAluVariant': 'dev.vitorsilverio.armjitter.ir.ParallelAluVariant',
    'VfpOp': 'dev.vitorsilverio.armjitter.ir.VfpOp',
    'MemoryAccessType': 'dev.vitorsilverio.armjitter.memory.MemoryAccessType',
    'CpuState': 'dev.vitorsilverio.armjitter.swi.CpuState',
}
SECTION = re.compile(r'^    // ── (.+?) ─')
METHOD = re.compile(r'^    (?:public |private )?static [\w<>\[\]]+ (\w+)\(')


def main():
    first = next(i for i, l in enumerate(SOURCE) if SECTION.match(l))
    # Constante CONDITIONS (antes da primeira seção) vai com a condição.
    head_members = [l for l in SOURCE[SOURCE.index('public final class AsmRuntimeHelpers {') + 1:first]]
    consts = []
    i = 0
    while i < len(head_members):
        l = head_members[i]
        if 'private AsmRuntimeHelpers()' in l:
            i += 3
            continue
        consts.append(l)
        i += 1
    last = max(i for i, l in enumerate(SOURCE) if l.rstrip() == '}')
    bodies = {c: [] for c in DOCS}
    current = None
    for line in SOURCE[first:last]:
        m = SECTION.match(line)
        if m:
            title = m.group(1).strip()
            current = next(v for k, v in SECTIONS.items() if title.startswith(k))
        bodies[current].append(line)
    bodies['AsmFlagHelpers'] = [l for l in consts if l.strip()] + [''] + bodies['AsmFlagHelpers']
    owner = {}
    for cls, lines in bodies.items():
        for l in lines:
            m = METHOD.match(l)
            if m and 'public' in l:
                owner.setdefault(m.group(1), cls)
    for cls, lines in bodies.items():
        text = '\n'.join(lines).rstrip() + '\n'
        own = {n for n, c in owner.items() if c == cls}
        for name, other in owner.items():
            if name in own:
                continue
            text = re.sub(r'(?<![.\w])' + name + r'\(', other + '.' + name + '(', text)
            text = text.replace('{@link #' + name + '}', '{@link ' + other + '#' + name + '}')
            text = text.replace('{@code ' + name + '}', '{@code ' + other + '#' + name + '}')
        imports = '\n'.join(f'import {v};' for k, v in sorted(IMPORTS.items(), key=lambda kv: kv[1])
                            if re.search(r'\b' + k + r'\b', text))
        out = f'''package dev.vitorsilverio.armjitter.codegen.jvm;

{imports}

/// Helpers estáticos invocados pelo bytecode ASM gerado: {DOCS[cls]}.
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {{@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}}.
/// Públicos porque o bytecode gerado roda em outro class loader.
public final class {cls} {{
    private {cls}() {{
    }}

{text}}}
'''
        (PKG / f'{cls}.java').write_text(out, encoding='utf-8')
        print(cls, out.count('\n'))
    (Path('target/e15.7') / 'helper-owner.tsv').write_text(
        '\n'.join(f'{n}\t{c}' for n, c in sorted(owner.items())) + '\n', encoding='utf-8')


main()
