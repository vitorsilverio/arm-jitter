"""E15.7 fase 1 — move os membros de `AsmBlockCompiler` para as famílias (rodar da raiz do repo).

Lê o `AsmBlockCompiler.java` de HEAD (passado em argv[1], para poder rodar de novo), separa os membros
(Javadoc/comentários + declaração + corpo) e grava em `target/e15.7/split/<Classe>.members` os corpos
que vão para cada família, com `private ` removido da declaração (as famílias são package-private e o
registro referencia os emissores). Imprime o que sobra no compilador e os membros não atribuídos.
"""
import re
import sys
from pathlib import Path

SOURCE = Path(sys.argv[1])
OUT = Path('target/e15.7/split')

MEMBER_START = re.compile(r'^    (?:private |public |static |final )+[\w<>\[\], .]+? (\w+)\(')

BASE = ['emitLoadToPcFromMemory', 'emitCachePrologue', 'emitCacheFlush', 'emitCacheReload', 'emitSpilled',
        'emitReadRegister', 'emitPerOpFallback', 'emitSrc1', 'emitOperand', 'emitShiftedOperandArgs',
        'emitStoreRegister', 'emitConditionalSetPcChanged']
ALU = ['emitAlu', 'emitAluMov', 'emitAluMvn', 'emitAluAdd', 'emitAluAdc', 'emitAluSub', 'emitAluSbc', 'emitAluRsb',
       'emitAluRsc', 'emitAluNeg', 'emitAluCmp', 'emitAluCmn', 'emitAluLogic', 'emitAluBic', 'emitAluTest',
       'emitAluClz', 'emitAluShift', 'emitAluExtend', 'emitAluExtendByte16', 'emitAluReverse', 'emitAluPack',
       'emitCpsrCarryAsInt', 'emitLogicFlagsCarry', 'aluUsesSrc1', 'aluWritesDst']
INTEGER = ['emitSaturating', 'emitReadHalf', 'emitDspMultiply', 'emitParallelAlu', 'emitSel', 'emitSaturate',
           'emitAbsDiffSum', 'emitMultiply', 'emitLongMultiply', 'emitBitFieldExtract', 'emitBitFieldInsert',
           'emitBitReverse', 'emitDivide', 'emitMoveTop', 'emitAsLong']
MEMORY = ['emitDoubleTransfer', 'canInlineMultipleTransfer', 'startOffset', 'writebackOffset',
          'emitMultipleTransferInline', 'emitPushInline', 'emitPopInline', 'emitLoadExclusive',
          'emitStoreExclusive', 'emitClearExclusive', 'emitLoad', 'emitStore', 'emitLoadLiteral',
          'emitMultipleTransfer']
CONTROL = ['emitBranch', 'emitBranchExchange', 'emitThumbBlPrefix', 'emitThumbBlSuffix', 'emitPsrTransfer',
           'emitSwi', 'emitCoprocessor', 'emitCoprocessorDouble', 'emitUndefined', 'emitCycle', 'emitFetch']
VFP_PREFIX = 'emitVfp'
VFP = ['singleArithOpcode', 'doubleArithOpcode']
DEAD = ['emitPush', 'emitPop']
# Ficam no compilador (ou são reescritos à mão: countAccesses/count* viram AsmAccessCounter + countXxx).
STAY = ['emitLoadToPcFromMemory_', 'condHelperName', 'compile', 'computeInstructionAddresses', 'buildRegCache',
        'countAccesses', 'countOperand', 'countRead', 'countWrite', 'emitConstructor', 'emitExecuteBridge',
        'emitProgramCounterFixup']

TARGETS = {'AsmEmitterBase': BASE, 'AsmAluEmitter': ALU, 'AsmIntegerEmitter': INTEGER,
           'AsmMemoryEmitter': MEMORY, 'AsmControlEmitter': CONTROL}


def members(lines):
    first = next(i for i, l in enumerate(lines) if l.startswith('    private void emitLoadToPcFromMemory('))
    while lines[first - 1].startswith('    ///'):
        first -= 1
    last = max(i for i, l in enumerate(lines) if l.rstrip() == '}')
    result = []
    i = first
    while i < last:
        if lines[i].strip() == '':
            i += 1
            continue
        start = i
        while lines[i].startswith('    ///') or lines[i].startswith('    //') or lines[i].strip() == '':
            i += 1
        m = MEMBER_START.match(lines[i])
        if not m:
            raise SystemExit(f'membro não reconhecido na linha {i + 1}: {lines[i]}')
        declaration = i
        while lines[i].rstrip() != '    }':
            i += 1
        result.append({'name': m.group(1), 'start': start, 'declaration': declaration, 'end': i})
        i += 1
    return first, result


def target_of(name):
    for cls, names in TARGETS.items():
        if name in names:
            return cls
    if name.startswith(VFP_PREFIX) or name in VFP:
        return 'AsmVfpEmitter'
    if name in DEAD:
        return 'DEAD'
    return None


def main():
    lines = SOURCE.read_text(encoding='utf-8').replace('\r\n', '\n').split('\n')
    first, mem = members(lines)
    OUT.mkdir(parents=True, exist_ok=True)
    buckets = {}
    remaining = []
    for m in mem:
        cls = target_of(m['name'])
        body = lines[m['start']:m['end'] + 1]
        if cls is None:
            remaining.append(m['name'])
            buckets.setdefault('AsmBlockCompiler', []).append(body)
            continue
        decl = m['declaration'] - m['start']
        body[decl] = body[decl].replace('    private ', '    ', 1)
        buckets.setdefault(cls, []).append(body)
    for cls, bodies in buckets.items():
        text = '\n\n'.join('\n'.join(b) for b in bodies) + '\n'
        (OUT / f'{cls}.members').write_text(text, encoding='utf-8')
        print(f'{cls:22s} membros={len(bodies):3d} linhas={text.count(chr(10))}')
    (OUT / 'head.txt').write_text('\n'.join(lines[:first]) + '\n', encoding='utf-8')
    print('ficam no compilador:', remaining)
    missing = [n for names in TARGETS.values() for n in names if n not in {m['name'] for m in mem}]
    print('nomes da tabela sem membro:', missing)


main()
