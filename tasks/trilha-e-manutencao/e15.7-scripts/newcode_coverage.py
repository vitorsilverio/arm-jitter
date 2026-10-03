"""E15.7 — cobertura JaCoCo por método do código novo (registro, predicados, contadores, emissão).

Lê `core/target/site/jacoco/jacoco.xml`; imprime todo método de `codegen/jvm/Asm*` cujo nome é
`register`/`accepts*`/`count*`/`emitInterop`/`emitMemoryBarrier`/`emitMultipleTransferOp`/lambda, ou de
classe nova inteira (`AsmEmission`, `AsmEmitterRegistry`, `AsmEmitters`, `AsmEmitState`,
`AsmAccessCounter`, `AsmRegCache`, `AsmNativePolicy`), com linha/branch perdidos.
"""
import re
import xml.etree.ElementTree as ET

NEW_CLASSES = {'AsmEmission', 'AsmEmitterRegistry', 'AsmEmitterRegistry$Builder', 'AsmEmitters', 'AsmEmitState',
               'AsmAccessCounter', 'AsmRegCache', 'AsmNativePolicy'}
NEW_METHOD = re.compile(r'^(register|accepts\w*|count\w*|emitInterop|emitMemoryBarrier|emitMultipleTransferOp|lambda\$.*|buildRegCache|emitAbortHandler)$')

root = ET.parse('core/target/site/jacoco/jacoco.xml').getroot()
for pkg in root.iter('package'):
    if pkg.get('name') != 'dev/vitorsilverio/armjitter/codegen/jvm':
        continue
    for cls in pkg.iter('class'):
        simple = cls.get('name').rsplit('/', 1)[1]
        if not simple.startswith('Asm'):
            continue
        for m in cls.iter('method'):
            if simple not in NEW_CLASSES and not NEW_METHOD.match(m.get('name')):
                continue
            c = {x.get('type'): (int(x.get('missed')), int(x.get('covered'))) for x in m.iter('counter')}
            lm = c.get('LINE', (0, 0))[0]
            bm = c.get('BRANCH', (0, 0))[0]
            if lm or bm:
                print(f'{simple}.{m.get("name")} linha={lm} branch={bm} (linha {m.get("line")})')
print('fim')
