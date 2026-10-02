# E15.2 - divide Ir64Op.java em sub-interfaces seladas por familia (puro deslocamento).
import re, sys, os, collections
sys.path.insert(0, 'target/e15.2')
from parse import members, SRC

IR64 = os.path.dirname(SRC)
PKG = 'dev.vitorsilverio.armjitter.ir64'
STRUCTURAL = ['Cycle', 'Fetch', 'StreamingRestricted']

INTEGER = '''Alu64 MoveWide PcRelative AluShiftedRegister AluExtendedRegister LogicalShiftedRegister ShiftVariable
ConditionalSelect Bitfield MultiplyAccumulate Divide ConditionalCompare AluWithCarry Extract DataProcessing1Source
MultiplyAccumulateLong MultiplyHigh EvaluateIntoFlags RotateIntoFlags ConvertFlags PointerAuthGeneric
PointerAuthInPlace AbsGeneral Crc32 SubtractPointer InsertRandomTag TagMaskInsert MinMaxGeneral'''.split()
MEMORY = '''Load64 Store64 LoadStorePair LoadLiteral64 LoadExclusive StoreExclusive LoadExclusivePair
StoreExclusivePair CompareAndSwap CompareAndSwapPair AtomicMemoryOp AtomicMemoryOpPair Ir64MopsPhase MemorySet
MemoryCopy Ir64MemoryTagOperation MemoryTag Ir64MemoryTagMultipleOperation MemoryTagMultiple StorePairTag
MemorySetTagged'''.split()
BRANCH = 'Branch64 CompareBranch64 CompareAndBranchRegister CompareAndBranchImmediate'.split()
SYSTEM = '''Svc SystemRegister SystemInstruction ExceptionReturn PrivilegedCall AddressTranslate InterruptMask
Breakpoint UndefinedInstructionTrap StreamingModeControl'''.split()
ADVSIMD_MOVE = '''VectorLoadStoreMultiple VectorLoadStoreSingle VectorLoadSingleReplicate VectorExtract
VectorPermute VectorTableLookup VectorDuplicateElement VectorDuplicateGeneral VectorInsertGeneral
VectorInsertElement VectorMoveElement VectorDuplicateElementScalar AdvSimdModifiedImmediate64
VectorLookupTable'''.split()
SVE_PREDICATE = '''SvePredicateLogical SvePredicateMisc SvePartitionBreak SvePredicateCount SveCompare
SveScalarCompare SveCounterPredicate SveMatch SvePredicateSelect'''.split()
SVE_MEMORY = 'SveLoad SveStore SveGather SveMultiVectorMemory'.split()

# leaf -> (parent, prefixos a remover, javadoc)
LEAVES = collections.OrderedDict([
    ('IntegerOp64', ('Ir64Op', [], [
        'Operações A64 de processamento de dados em registrador geral: aritmética, lógica, deslocamento,',
        'seleção/comparação condicional, multiplicação/divisão, manipulação de `NZCV`, PAuth, CRC32 e as',
        'formas de MTE que só tocam registradores (`SUBP`/`IRG`/`GMI`).'])),
    ('MemoryOp64', ('Ir64Op', [], [
        'Operações A64 de acesso à memória por registrador geral: load/store (simples, par, literal),',
        'exclusivos, `CAS`, atômicas (`FEAT_LSE`), `FEAT_MOPS` (`SET*`/`CPY*`) e as formas de MTE que',
        'leem ou gravam tags de alocação.'])),
    ('BranchOp64', ('Ir64Op', [], [
        'Operações A64 de desvio: `B`/`BL`/`B.cond`/`BR`/`BLR`/`RET`, `CBZ`/`CBNZ`/`TBZ`/`TBNZ` e as',
        'formas fundidas de comparação + desvio do `FEAT_CMPBR`.'])),
    ('SystemOp64', ('Ir64Op', [], [
        'Operações A64 de sistema: geração e retorno de exceção, acesso a registrador de sistema,',
        'instruções de sistema (`SYS`/`AT`/hints), máscara de interrupção e controle do modo streaming.'])),
    ('FpOp64', ('Ir64Op', ['Fp64', 'Fp'], [
        'Operações A64 de ponto flutuante escalar: aritmética, comparação, conversão, arredondamento,',
        '`FMOV` e os load/store de registrador SIMD&amp;FP.'])),
    ('AdvSimdIntegerOp64', ('AdvSimdOp64', ['Vector'], [
        'Operações AdvSIMD de aritmética inteira: "three same", pareadas, alargantes, estreitantes,',
        'deslocamento por imediato, redução entre lanes, produto escalar e multiplicação polinomial.'])),
    ('AdvSimdFpOp64', ('AdvSimdOp64', ['Vector'], [
        'Operações AdvSIMD de ponto flutuante: aritmética, conversão, números complexos (`FEAT_FCMA`),',
        '`BFloat16` e `FP8`.'])),
    ('AdvSimdMoveOp64', ('AdvSimdOp64', ['Vector', 'AdvSimd'], [
        'Operações AdvSIMD de movimentação de dados: load/store de estruturas, `DUP`/`INS`/`SMOV`/`UMOV`,',
        'permutação, extração, consulta a tabela e imediato modificado.'])),
    ('CryptoOp64', ('AdvSimdOp64', ['Crypto'], [
        'Operações da Cryptographic Extension do A64 (AES, SHA-1/SHA-2, SHA-3, SHA-512, SM3, SM4).'])),
    ('SvePredicateOp64', ('SveOp64', ['Sve'], [
        'Operações SVE cujo resultado é um predicado (ou que só manipulam predicados): lógica, quebra de',
        'partição, contagem, comparações vetoriais e escalares (`WHILE*`), `MATCH`/`NMATCH` e `PSEL`.'])),
    ('SveIntegerOp64', ('SveOp64', ['Sve'], [
        'Operações SVE/SVE2 inteiras: aritmética predicada e não predicada, reduções, imediatos,',
        'multiplicação indexada, endereçamento, permutação, contagem de elementos e as formas de',
        'criptografia do SVE2.'])),
    ('SveFpOp64', ('SveOp64', ['Sve'], [
        'Operações SVE/SVE2 de ponto flutuante: aritmética, multiply-add, comparação e redução, unárias,',
        'conversões, `BFloat16` e `FP8`.'])),
    ('SveMemoryOp64', ('SveOp64', ['Sve'], [
        'Operações SVE de acesso à memória: load, store, gather/scatter e as formas multi-vetor.'])),
    ('SmeOp64', ('Ir64Op', ['Sme'], [
        'Operações SME/SME2: acesso ao array `ZA` e a `ZT0`, produtos externos e as formas multi-vetor.'])),
])
MIDDLES = collections.OrderedDict([
    ('AdvSimdOp64', ['Operações AdvSIMD (NEON de 64 bits) e da Cryptographic Extension do A64, agrupadas por',
                     'sub-família.']),
    ('SveOp64', ['Operações SVE/SVE2 do A64, agrupadas por sub-família.']),
])
TOP = ['IntegerOp64', 'MemoryOp64', 'BranchOp64', 'SystemOp64', 'FpOp64', 'AdvSimdOp64', 'SveOp64', 'SmeOp64']


def leaf_of(name):
    if name in INTEGER: return 'IntegerOp64'
    if name in MEMORY: return 'MemoryOp64'
    if name in BRANCH: return 'BranchOp64'
    if name in SYSTEM: return 'SystemOp64'
    if name in ADVSIMD_MOVE: return 'AdvSimdMoveOp64'
    if name in SVE_PREDICATE: return 'SvePredicateOp64'
    if name in SVE_MEMORY: return 'SveMemoryOp64'
    if name.startswith('SveFp'): return 'SveFpOp64'
    if name.startswith('Sve'): return 'SveIntegerOp64'
    if name.startswith('Sme'): return 'SmeOp64'
    if name.startswith('Crypto'): return 'CryptoOp64'
    if name.startswith('VectorFp'): return 'AdvSimdFpOp64'
    if name.startswith('Vector'): return 'AdvSimdIntegerOp64'
    if name.startswith('Fp'): return 'FpOp64'
    raise SystemExit('sem familia: ' + name)


def build_mapping(ms):
    mapping = collections.OrderedDict()   # old -> (leaf, new, kind)
    for kind, name, _ in ms:
        if name in STRUCTURAL: continue
        leaf = leaf_of(name)
        new = name
        if kind == 'record':
            for p in LEAVES[leaf][1]:
                if name.startswith(p):
                    new = name[len(p):]
                    break
        assert new[0].isupper(), (name, new)
        mapping[name] = (leaf, new, kind)
    seen = set()
    for old, (leaf, new, _) in mapping.items():
        assert (leaf, new) not in seen, (leaf, new)
        seen.add((leaf, new))
    return mapping


def load_mapping():
    mapping = collections.OrderedDict()
    for line in open('target/e15.2/mapping.tsv', encoding='utf-8').read().split('\n'):
        if line:
            old, leaf, new, kind = line.split('\t')
            mapping[old] = (leaf, new, kind)
    return mapping


def alternation(mapping):
    return '|'.join(sorted(mapping, key=len, reverse=True))


def rewrite_ir64(text, current, mapping):
    """Reescreve referencias a membros movidos dentro de um fonte do pacote ir64."""
    alt = alternation(mapping)

    def q(old, force=False):
        leaf, new, _ = mapping[old]
        return new if (leaf == current and not force) else leaf + '.' + new

    text = re.sub(r'(\.?)\bIr64Op\.(' + alt + r')\b',
                  lambda m: m.group(1) + q(m.group(2), force=bool(m.group(1))), text)
    text = re.sub(r'(\{@link(?:\s*\n\s*///)?\s+)(' + alt + r')\b', lambda m: m.group(1) + q(m.group(2)), text)
    out = []
    for line in text.split('\n'):
        idx = line.find('//')
        code, comment = (line, '') if idx < 0 else (line[:idx], line[idx:])
        code = re.sub(r'(?<![\w.])(' + alt + r')\b', lambda m: q(m.group(1)), code)
        out.append(code + comment)
    return '\n'.join(out)


def wrap(prefix, items, indent, width=100):
    lines, cur = [], prefix
    for i, it in enumerate(items):
        piece = it + (',' if i < len(items) - 1 else ' {')
        if len(cur) + 1 + len(piece) > width:
            lines.append(cur)
            cur = indent + piece
        else:
            cur = cur + ' ' + piece
    lines.append(cur)
    return lines


def write(path, text):
    open(path, 'w', encoding='utf-8', newline='\n').write(text)


def main():
    lines, ms = members()
    mapping = build_mapping(ms)
    by_leaf = collections.OrderedDict((l, []) for l in LEAVES)
    structural = []
    for kind, name, text in ms:
        if name in STRUCTURAL:
            structural.append(text)
        else:
            by_leaf[mapping[name][0]].append((kind, name, text))

    for leaf, (parent, _, doc) in LEAVES.items():
        body = []
        for kind, name, text in by_leaf[leaf]:
            chunk = '\n'.join(text)
            if kind == 'record':
                assert chunk.count(') implements Ir64Op {') == 1, name
                chunk = chunk.replace(') implements Ir64Op {', ') implements ' + leaf + ' {')
            body.append(rewrite_ir64(chunk, leaf, mapping))
        permits = [leaf + '.' + mapping[n][1] for k, n, _ in by_leaf[leaf] if k == 'record']
        src = ['package ' + PKG + ';', '']
        joined = '\n\n'.join(body)
        if 'AdvSimdModifiedImmediateOp' in joined:
            src += ['import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;', '']
        src += ['/// ' + d for d in doc]
        src += ['///',
                '/// Sub-interface selada de {@link ' + parent + '} (task E15.2): os records desta família vivem aqui, e',
                '/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.']
        src += wrap('public sealed interface ' + leaf + ' extends ' + parent + ' permits', permits, '        ')
        src += ['', joined, '}', '']
        write(os.path.join(IR64, leaf + '.java'), '\n'.join(src))

    for mid, doc in MIDDLES.items():
        kids = [l for l, v in LEAVES.items() if v[0] == mid]
        src = ['package ' + PKG + ';', ''] + ['/// ' + d for d in doc]
        src += ['///',
                '/// Sub-interface selada de {@link Ir64Op} (task E15.2); os records vivem nas sub-interfaces permitidas.']
        src += wrap('public sealed interface ' + mid + ' extends Ir64Op permits', kids, '        ')
        src += ['}', '']
        write(os.path.join(IR64, mid + '.java'), '\n'.join(src))

    # Ir64Op.java: cabecalho + Kind + records estruturais
    decl = next(i for i, l in enumerate(lines) if l.startswith('public sealed interface Ir64Op permits'))
    body_start = next(i for i in range(decl, len(lines)) if lines[i].endswith('{')) + 1
    kind_start = next(i for i, l in enumerate(lines) if l.startswith('    final class Kind'))
    kind_end = next(i for i in range(kind_start, len(lines)) if lines[i] == '    }')
    head = [l for l in lines[:decl] if not l.startswith('import ')]
    while head[1] == '' and head[2] == '': del head[1]
    new = head + wrap('public sealed interface Ir64Op permits',
                      TOP + ['Ir64Op.' + s for s in STRUCTURAL], '        ')
    new += lines[body_start:kind_end + 1]
    for text in structural:
        new += [''] + text
    new += ['}', '']
    write(SRC, rewrite_ir64('\n'.join(new), None, mapping))

    with open('target/e15.2/mapping.tsv', 'w', encoding='utf-8', newline='\n') as f:
        for old, (leaf, newn, kind) in mapping.items():
            f.write('\t'.join([old, leaf, newn, kind]) + '\n')
    cnt = collections.Counter(v[0] for v in mapping.values() if v[2] == 'record')
    print(dict(cnt), sum(cnt.values()) + len(STRUCTURAL))


if __name__ == '__main__':
    main()
