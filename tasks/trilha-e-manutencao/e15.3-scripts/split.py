# E15.3 - divide IrOp.java em sub-interfaces seladas por familia (puro deslocamento).
import re, sys, os, collections
sys.path.insert(0, 'target/e15.3')
from parse import members, SRC

IR = os.path.dirname(SRC)
PKG = 'dev.vitorsilverio.armjitter.ir'
ROOT = 'IrOp'
TASK = 'E15.3'
STRUCTURAL = ['Cycle', 'Fetch']

INTEGER = '''Alu Multiply LongMultiply Saturating Crc32 DspMultiply DspDualMultiply DspTopWordMultiply ParallelAlu
Sel Saturate AbsDiffSum MoveTop BitFieldExtract BitFieldInsert BitReverse Divide ClearMultiple'''.split()
MEMORY = '''Load Store LoadExclusive StoreExclusive ClearExclusive DoubleTransfer Swap LoadLiteral
MultipleTransfer Push Pop'''.split()
BRANCH = '''Branch BranchExchange ThumbBlPrefix ThumbBlSuffix TableBranch CompareBranchZero
SecureBranchExchange LoopStart LoopEnd'''.split()
SYSTEM = '''PsrTransfer Hvc Smc Eret MrsBank MsrBank Swi Breakpoint Coprocessor CoprocessorDouble Undefined
ChangeProcessorState SetEndianness StoreReturnState ReturnFromException WaitForInterrupt MemoryBarrier
SetItState MProfileSystemRegister Nocp SecureGateway'''.split()
VFP_EXTRA = 'VlldmVlstm Vscclrm HalfPrecisionConversion'.split()
NEON_MOVE = '''NeonLoadStoreMultiple NeonLoadStoreSingle NeonLoadAllLanes NeonModifiedImmediate NeonSwapPermute
NeonExtract NeonTableLookup NeonDuplicateScalar'''.split()
NEON_FP = '''NeonFpThreeSame NeonFpPairwise NeonConvertFixedPoint NeonFpThreeSameByElement NeonFpUnary
NeonFpConvertPrecision NeonComplex NeonComplexByElement NeonFusedMultiplyAddLong
NeonFusedMultiplyAddLongByElement NeonDotProductBFloat16 NeonDotProductByElementBFloat16
NeonMatrixMultiplyAccumulateBFloat16 NeonFusedMultiplyAddLongBFloat16
NeonFusedMultiplyAddLongByElementBFloat16'''.split()
MVE_PREDICATION = '''LoopClearTailPredication Vctp AdvanceVpt Vpst Vpnot Vpsel VprTransfer AdvanceEci
MveVectorCompare MveVectorCompareScalar'''.split()
MVE_MOVE = '''MveLoadStore MveWideningLoadStore MveGatherScatterOffset MveGatherScatterImmediate
MveInterleavedLoadStore MveIncrementDup MveWrappingIncrementDup MveVectorDup MveMoveLanesGpr
MveVectorModifiedImmediate'''.split()
MVE_REDUCTION = '''MveVectorAddAcrossVector MveVectorAddAcrossVectorLong MveVectorAbsoluteDifferenceAccumulate
MveVectorDualAccumulate MveVectorDualAccumulateLong MveVectorRoundingDualAccumulateHigh
MveVectorMinMaxAcrossVector MveVectorFpMinMaxAcrossVector'''.split()
MVE_INTEGER_EXTRA = ['WideShiftOperation']

# leaf -> (parent, prefixos a remover, javadoc)
LEAVES = collections.OrderedDict([
    ('IntegerOp', ('IrOp', [], [
        'Operações A32/T32 de processamento de dados em registrador geral: aritmética e lógica (`ALU`),',
        'multiplicação e divisão, saturação e DSP (ARMv5TE), SIMD paralelo em GPR (ARMv6), campo de bits,',
        '`MOVT`, `CRC32` e `CLRM`.'])),
    ('MemoryOp', ('IrOp', [], [
        'Operações A32/T32 de acesso à memória por registrador geral: load/store (simples, duplo,',
        'literal, múltiplo), `PUSH`/`POP`, `SWP` e os acessos exclusivos.'])),
    ('BranchOp', ('IrOp', [], [
        'Operações A32/T32 de desvio: `B`/`BL`/`BX`/`BLX`, as duas metades do `BL` do Thumb, `TBB`/`TBH`,',
        '`CBZ`/`CBNZ`, `BXNS`/`BLXNS` e os laços de baixo overhead (`WLS`/`DLS`/`LE`).'])),
    ('SystemOp', ('IrOp', [], [
        'Operações A32/T32 de sistema: geração e retorno de exceção, acesso a `CPSR`/`SPSR` e a',
        'registradores bancados, coprocessador, estado de execução (`CPS`/`SETEND`/`IT`), hints e os',
        'registradores especiais do perfil M.'])),
    ('VfpOp', ('IrOp', ['Vfp'], [
        'Operações VFP (ponto flutuante escalar de 32 bits): aritmética, comparação, conversão,',
        'arredondamento, transferências com registrador geral e memória, as formas de meia precisão e o',
        'contexto de ponto flutuante do perfil M (`VLLDM`/`VLSTM`/`VSCCLRM`).'])),
    ('NeonIntegerOp', ('NeonOp', ['Neon'], [
        'Operações NEON de aritmética inteira: "three same", pareadas, alargantes, estreitantes,',
        'deslocamento por imediato, por elemento, unárias, produto escalar e multiplicação de matriz.'])),
    ('NeonFpOp', ('NeonOp', ['Neon'], [
        'Operações NEON de ponto flutuante: aritmética, conversão, números complexos (`FEAT_FCMA`),',
        '`VFMAL`/`VFMSL` (`FEAT_FHM`) e `BFloat16`.'])),
    ('NeonMoveOp', ('NeonOp', ['Neon'], [
        'Operações NEON de movimentação de dados: load/store de estruturas, permutação, extração,',
        'consulta a tabela, `VDUP` e imediato modificado.'])),
    ('NeonCryptoOp', ('NeonOp', ['NeonCrypto'], [
        'Operações da Cryptographic Extension do AArch32 (AES, SHA-1, SHA-256).'])),
    ('MvePredicationOp', ('MveOp', ['Mve'], [
        'Operações MVE de predicação e controle: máquina `VPT` (`VPST`/`VPNOT`/`VPSEL`/`VPR`), predicação',
        'de cauda (`VCTP`/`LCTP`), continuação de exceção (`ECI`) e as comparações que escrevem `VPR.P0`.'])),
    ('MveMoveOp', ('MveOp', ['Mve'], [
        'Operações MVE de movimentação de dados: load/store (contíguo, alargante, gather/scatter,',
        'intercalado), `VDUP`/`VIDUP`/`VIWDUP`, `VMOV` entre lanes e GPR e imediato modificado.'])),
    ('MveIntegerOp', ('MveOp', ['Mve'], [
        'Operações MVE inteiras: aritmética vetor × vetor e vetor × escalar, alargantes, estreitantes,',
        'deslocamentos, unárias e os deslocamentos longos em GPR (`ASRL`/`LSLL`/`SQSHL`/...).'])),
    ('MveFpOp', ('MveOp', ['Mve'], [
        'Operações MVE de ponto flutuante: aritmética vetor × vetor e vetor × escalar, números',
        'complexos, conversões e unárias.'])),
    ('MveReductionOp', ('MveOp', ['Mve'], [
        'Operações MVE de redução para registrador geral: soma, mínimo/máximo e produtos acumulados ao',
        'longo do vetor (`VADDV`/`VMINV`/`VMAXV`/`VABAV`/`VMLADAV`/...).'])),
])
MIDDLES = collections.OrderedDict([
    ('NeonOp', ['Operações NEON (Advanced SIMD de 32 bits) e da Cryptographic Extension do AArch32, agrupadas',
                'por sub-família.']),
    ('MveOp', ['Operações MVE (Helium, ARMv8.1-M), agrupadas por sub-família.']),
])
TOP = ['IntegerOp', 'MemoryOp', 'BranchOp', 'SystemOp', 'VfpOp', 'NeonOp', 'MveOp']


def leaf_of(name):
    if name in INTEGER: return 'IntegerOp'
    if name in MEMORY: return 'MemoryOp'
    if name in BRANCH: return 'BranchOp'
    if name in SYSTEM: return 'SystemOp'
    if name in VFP_EXTRA: return 'VfpOp'
    if name in NEON_MOVE: return 'NeonMoveOp'
    if name in NEON_FP: return 'NeonFpOp'
    if name in MVE_PREDICATION: return 'MvePredicationOp'
    if name in MVE_MOVE: return 'MveMoveOp'
    if name in MVE_REDUCTION: return 'MveReductionOp'
    if name in MVE_INTEGER_EXTRA: return 'MveIntegerOp'
    if name.startswith('NeonCrypto'): return 'NeonCryptoOp'
    if name.startswith('Neon'): return 'NeonIntegerOp'
    if name.startswith('MveVectorFp'): return 'MveFpOp'
    if name.startswith('Mve'): return 'MveIntegerOp'
    if name.startswith('Vfp'): return 'VfpOp'
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
    listed = (INTEGER + MEMORY + BRANCH + SYSTEM + VFP_EXTRA + NEON_MOVE + NEON_FP + MVE_PREDICATION + MVE_MOVE
              + MVE_REDUCTION + MVE_INTEGER_EXTRA)
    assert all(n in mapping for n in listed), [n for n in listed if n not in mapping]
    return mapping


def load_mapping():
    mapping = collections.OrderedDict()
    for line in open('target/e15.3/mapping.tsv', encoding='utf-8').read().split('\n'):
        if line:
            old, leaf, new, kind = line.split('\t')
            mapping[old] = (leaf, new, kind)
    return mapping


def alternation(mapping):
    return '|'.join(sorted(mapping, key=len, reverse=True))


def split_comment(line):
    idx = line.find('//')
    return (line, '') if idx < 0 else (line[:idx], line[idx:])


def rewrite_ir(text, current, mapping):
    """Reescreve referencias a membros movidos dentro de um fonte gerado do pacote ir."""
    alt = alternation(mapping)

    def q(old, force=False):
        leaf, new, _ = mapping[old]
        return new if (leaf == current and not force) else leaf + '.' + new

    text = re.sub(r'(\.?)\bIrOp\.(' + alt + r')\b',
                  lambda m: m.group(1) + q(m.group(2), force=bool(m.group(1))), text)
    text = re.sub(r'(\{@link(?:\s*\n\s*///)?\s+)(' + alt + r')\b', lambda m: m.group(1) + q(m.group(2)), text)
    out = []
    for line in text.split('\n'):
        code, comment = split_comment(line)
        code = re.sub(r'(?<![\w.])(' + alt + r')\b', lambda m: q(m.group(1)), code)
        out.append(code + comment)
    return '\n'.join(out)


def needed_imports(orig_imports, body):
    code, comments = [], []
    for line in body.split('\n'):
        c, k = split_comment(line)
        code.append(c)
        comments.append(k)
    code, comments = '\n'.join(code), '\n'.join(comments)
    out = []
    for imp in orig_imports:
        simple = imp.rstrip(';').split('.')[-1]
        if re.search(r'(?<![\w.])' + simple + r'\b', code) or \
                re.search(r'(\{@link(?:plain)?\s+(?:///\s*)?|@see\s+)' + simple + r'\b', comments):
            out.append(imp)
    return out


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
    orig_imports = [l for l in lines if l.startswith('import ')]
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
                assert chunk.count(') implements IrOp {') == 1, name
                chunk = chunk.replace(') implements IrOp {', ') implements ' + leaf + ' {')
            body.append(rewrite_ir(chunk, leaf, mapping))
        permits = [leaf + '.' + mapping[n][1] for k, n, _ in by_leaf[leaf] if k == 'record']
        joined = '\n\n'.join(body)
        src = ['package ' + PKG + ';', '']
        imps = needed_imports(orig_imports, joined)
        if imps:
            src += imps + ['']
        src += ['/// ' + d for d in doc]
        src += ['///',
                '/// Sub-interface selada de {@link ' + parent + '} (task ' + TASK + '): os records desta família vivem aqui, e o',
                '/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.']
        src += wrap('public sealed interface ' + leaf + ' extends ' + parent + ' permits', permits, '        ')
        src += ['', joined, '}', '']
        write(os.path.join(IR, leaf + '.java'), '\n'.join(src))

    for mid, doc in MIDDLES.items():
        kids = [l for l, v in LEAVES.items() if v[0] == mid]
        src = ['package ' + PKG + ';', ''] + ['/// ' + d for d in doc]
        src += ['///',
                '/// Sub-interface selada de {@link IrOp} (task ' + TASK + '); os records vivem nas sub-interfaces permitidas.']
        src += wrap('public sealed interface ' + mid + ' extends IrOp permits', kids, '        ')
        src += ['}', '']
        write(os.path.join(IR, mid + '.java'), '\n'.join(src))

    # IrOp.java: cabecalho + Kind + records estruturais
    decl = next(i for i, l in enumerate(lines) if l.startswith('public sealed interface IrOp permits'))
    body_start = next(i for i in range(decl, len(lines)) if lines[i].endswith('{')) + 1
    kind_start = next(i for i, l in enumerate(lines) if l.startswith('    final class Kind'))
    kind_end = next(i for i in range(kind_start, len(lines)) if lines[i] == '    }')
    javadoc = [l for l in lines[:decl] if l.startswith('///')]
    rest = wrap('public sealed interface IrOp permits', TOP + ['IrOp.' + s for s in STRUCTURAL], '        ')
    rest += lines[body_start:kind_end + 1]
    for text in structural:
        rest += [''] + text
    rest += ['}', '']
    rest = rewrite_ir('\n'.join(javadoc + rest), None, mapping)
    head = [lines[0], ''] + needed_imports(orig_imports, rest) + ['']
    write(SRC, '\n'.join(head) + '\n' + rest)

    with open('target/e15.3/mapping.tsv', 'w', encoding='utf-8', newline='\n') as f:
        for old, (leaf, newn, kind) in mapping.items():
            f.write('\t'.join([old, leaf, newn, kind]) + '\n')
    cnt = collections.Counter(v[0] for v in mapping.values() if v[2] == 'record')
    print(dict(cnt), sum(cnt.values()) + len(STRUCTURAL))


if __name__ == '__main__':
    main()
