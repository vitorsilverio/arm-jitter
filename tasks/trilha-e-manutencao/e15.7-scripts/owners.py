"""E15.7 fase 2 — troca `HELPERS, "nome"` pelo owner da família do helper nos emissores/compilador.

Lê `target/e15.7/helper-owner.tsv` (gerado por split_helpers.py). Nomes dinâmicos (variável no lugar
do literal) são listados para edição à mão.
"""
import re
from pathlib import Path

PKG = Path('core/src/main/java/dev/vitorsilverio/armjitter/codegen/jvm')
CONST = {'AsmFlagHelpers': 'FLAG_HELPERS', 'AsmMemoryHelpers': 'MEMORY_HELPERS',
         'AsmIntegerHelpers': 'INTEGER_HELPERS', 'AsmSystemHelpers': 'SYSTEM_HELPERS',
         'AsmVfpHelpers': 'VFP_HELPERS'}
owner = dict(l.split('\t') for l in Path('target/e15.7/helper-owner.tsv').read_text(encoding='utf-8').split('\n') if l)
for f in ['AsmEmitterBase', 'AsmAluEmitter', 'AsmIntegerEmitter', 'AsmMemoryEmitter', 'AsmControlEmitter',
          'AsmVfpEmitter', 'AsmBlockCompiler']:
    p = PKG / f'{f}.java'
    text = p.read_text(encoding='utf-8')
    text = re.sub(r'HELPERS, "(\w+)"', lambda m: CONST[owner[m.group(1)]] + ', "' + m.group(1) + '"', text)
    p.write_text(text, encoding='utf-8')
    for i, l in enumerate(text.split('\n')):
        if re.search(r'(?<!_)HELPERS\b', l):
            print(f'{f}:{i + 1}: {l.strip()}')
