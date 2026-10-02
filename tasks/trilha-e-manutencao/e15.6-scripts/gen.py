# E15.6 - insere regUse()/regDef() nos records de ir/*Op.java, logo depois da ponte `execute`.
# Os corpos são os `case` de DeadCodeEliminationPass (70b049e), com o padrão trocado pelo próprio
# componente. A equivalência é conferida pelo LegacyDceOracleTest (não por este script).
# Uso: python gen.py <raiz do repo>
import os
import re
import sys

ROOT = sys.argv[1]
IR = os.path.join(ROOT, 'core/src/main/java/dev/vitorsilverio/armjitter/ir')


def one(name, expr):
    return [f'        @Override public int {name}() {{ return {expr}; }}']


def block(name, *body):
    return [f'        @Override public int {name}() {{'] + ['            ' + b if b else '' for b in body] + ['        }']


U, D = 'regUse', 'regDef'
SPEC = {
    'IntegerOp.Alu': block(U,
        '// MOV/MVN/NEG usam só src2; todos os outros também leem src1',
        'boolean usesSrc1 = switch (opcode) {',
        '    case MOV, MVN, NEG -> false;',
        '    default -> true;',
        '};',
        'int mask = (usesSrc1 && src1ValueOverride < 0) ? (1 << src1) : 0;',
        'return mask | src2.regUse();') + block(D,
        'return switch (opcode) {',
        '    // Ops de comparação só atualizam o CPSR — nenhum registrador de propósito geral escrito',
        '    case CMP, CMN, TST, TEQ -> 0;',
        '    default -> 1 << dst;',
        '};'),
    'IntegerOp.Multiply': block(U,
        'int mask = rmValueOverride < 0 ? (1 << rm) : 0;',
        'if (rsValueOverride < 0) mask |= 1 << rs;',
        'if (accumulate && rnValueOverride < 0) mask |= 1 << rn;',
        'return mask;') + one(D, '1 << dst'),
    'IntegerOp.LongMultiply': block(U,
        'int mask = rmValueOverride < 0 ? (1 << rm) : 0;',
        'if (rsValueOverride < 0) mask |= 1 << rs;',
        'if (accumulate || accumulateDouble) {',
        '    if (dstHighValueOverride < 0) mask |= 1 << dstHigh;',
        '    if (dstLowValueOverride < 0) mask |= 1 << dstLow;',
        '}',
        'return mask;') + one(D, '(1 << dstLow) | (1 << dstHigh)'),
    'IntegerOp.Saturating': one(U, '(1 << rm) | (1 << rn)') + one(D, '1 << dst'),
    'IntegerOp.DspMultiply': one(U, '(1 << rm) | (1 << rs) | (1 << rn)')
        + ['        /// `SMLALxy` (`op2 == 2`) — única forma que também escreve `rn` (RdLo).']
        + ['        private static final int SMLAL_XY = 2;']
        + one(D, '(1 << dst) | (op2 == SMLAL_XY ? (1 << rn) : 0)'),
    'IntegerOp.DspDualMultiply': one(U, '(1 << rm) | (1 << rn) | (1 << ra)')
        + one(D, '(1 << dst) | (longForm ? (1 << ra) : 0)'),
    'IntegerOp.DspTopWordMultiply': one(U, '(1 << rn) | (1 << rm) | (1 << ra)') + one(D, '1 << dst'),
    'IntegerOp.ParallelAlu': one(U, '(1 << rn) | (1 << rm)') + one(D, '1 << dst'),
    'IntegerOp.Sel': one(U, '(1 << rn) | (1 << rm)') + one(D, '1 << dst'),
    'IntegerOp.Saturate': one(U, 'operand.regUse()') + one(D, '1 << dst'),
    'IntegerOp.AbsDiffSum': one(U, '(1 << rm) | (1 << rs) | (rn >= 0 ? (1 << rn) : 0)') + one(D, '1 << dst'),
    # SBFX/UBFX/RBIT/SDIV/UDIV (B3.1) leem só seus operandos-fonte, nunca o destino.
    'IntegerOp.BitFieldExtract': one(U, '1 << src') + one(D, '1 << dst'),
    'IntegerOp.BitFieldInsert':
        ['        // BFI/BFC (B3.1) também lê `dst` para preservar os bits fora do campo; BFC (src=-1)',
         '        // não lê nenhum registrador-fonte.']
        + one(U, '(1 << dst) | (src >= 0 ? (1 << src) : 0)') + one(D, '1 << dst'),
    'IntegerOp.BitReverse': one(U, '1 << src') + one(D, '1 << dst'),
    'IntegerOp.Divide': one(U, '(1 << dividend) | (1 << divisor)') + one(D, '1 << dst'),

    'MemoryOp.Load': one(U, '(baseValueOverride < 0 ? (1 << base) : 0) | offset.regUse()')
        + one(D, '(1 << dst) | (writeback ? (1 << base) : 0)'),
    'MemoryOp.Store': block(U,
        'int mask = baseValueOverride < 0 ? (1 << base) : 0;',
        'if (srcValueOverride < 0) mask |= 1 << src;',
        'return mask | offset.regUse();') + one(D, 'writeback ? (1 << base) : 0'),
    'MemoryOp.LoadExclusive':
        ['        /// Tamanho de acesso de `LDREXD`, que carrega o par `dst`, `dst+1`.',
         '        private static final int DOUBLEWORD_BYTES = 8;']
        + one(U, '1 << base')
        + one(D, '(1 << dst) | (sizeBytes == DOUBLEWORD_BYTES ? (1 << (dst + 1)) : 0)'),
    'MemoryOp.StoreExclusive':
        ['        /// Tamanho de acesso de `STREXD`, que grava o par `src`, `src+1`.',
         '        private static final int DOUBLEWORD_BYTES = 8;']
        + one(U, '(1 << base) | (1 << src) | (sizeBytes == DOUBLEWORD_BYTES ? (1 << (src + 1)) : 0)')
        + one(D, '1 << dst'),
    'MemoryOp.DoubleTransfer': block(U,
        'int mask = baseValueOverride < 0 ? (1 << base) : 0;',
        'mask |= offset.regUse();',
        'if (!load) mask |= (1 << first) | (1 << second); // STRD lê o par',
        'return mask;') + block(D,
        'int mask = load ? (1 << first) | (1 << second) : 0;',
        'if (writeback) mask |= 1 << base;',
        'return mask;'),
    'MemoryOp.MultipleTransfer': ['        // Store lê todos os registradores da lista.']
        + one(U, '(1 << base) | (load ? 0 : registerMask)')
        + block(D,
        'int mask = load ? registerMask : 0;',
        'if (writeback) mask |= 1 << base;',
        '// LDM user-mode (^ sem PC) carrega no banco USER/SYS de r8-r14, não no r8-r14',
        '// bancado do modo atual. Exclui r8-r14 do conjunto def para que a DCE não elimine',
        '// escritas no r8-r14 do modo atual que precedem essa op.',
        'if (load && userMode && (registerMask & GprMask.PC) == 0) mask &= GprMask.UNBANKED_R0_R7;',
        'return mask;'),
    'MemoryOp.Push': one(U, 'GprMask.SP | registerMask | (includeLr ? GprMask.LR : 0)') + one(D, 'GprMask.SP'),
    'MemoryOp.Pop': one(U, 'GprMask.SP') + one(D, 'registerMask | GprMask.SP | (includePc ? GprMask.PC : 0)'),
    'MemoryOp.Swap': block(U,
        'int mask = baseValueOverride < 0 ? (1 << base) : 0;',
        'if (srcValueOverride < 0) mask |= 1 << src;',
        'return mask;') + one(D, '1 << dst'),
    'MemoryOp.LoadLiteral': one(D, '1 << dst'),

    'BranchOp.Branch': one(D, 'GprMask.PC | (link ? GprMask.LR : 0)'),
    'BranchOp.BranchExchange': one(U, 'sourceValueOverride < 0 ? (1 << sourceRegister) : 0')
        + one(D, 'GprMask.PC | (link ? GprMask.LR : 0)'),
    'BranchOp.ThumbBlPrefix': one(D, 'GprMask.LR'),
    'BranchOp.ThumbBlSuffix': one(U, 'GprMask.LR') + one(D, 'GprMask.PC'),
    # TBB/TBH (B2.4) leem rn/rm para calcular o endereço da tabela.
    'BranchOp.TableBranch': one(U, '(rnValueOverride < 0 ? (1 << rn) : 0) | (rmValueOverride < 0 ? (1 << rm) : 0)'),
    'BranchOp.CompareBranchZero':
        ['        // CBZ/CBNZ (B2.4) lê rn (nunca tem value override — sempre R0-R7).'] + one(U, '1 << rn'),

    'SystemOp.PsrTransfer': block(U,
        'if (read) return 0;',
        'int mask = !immediateOperand && registerValueOverride < 0 ? (1 << register) : 0;',
        '// Escrever o campo de controle (bit 0) do CPSR pode trocar o modo da CPU, o que salva',
        '// o banco de registradores r8-r14 atual. Trata todos os r0-r14 como vivos para que a',
        '// DCE não elimine escritas em registradores bancados que parecem mortas só porque o',
        '// registrador de mesmo índice do novo modo é escrito depois no mesmo bloco.',
        'if (!spsr && (fieldMask & 1) != 0) mask |= GprMask.BANKED_R8_R14;',
        'return mask;') + one(D, 'read ? (1 << register) : 0'),
    'SystemOp.Coprocessor': one(U, 'load ? 0 : (1 << register)')
        + one(D, 'load && register != GprMask.PC_INDEX ? (1 << register) : 0'),
    'SystemOp.CoprocessorDouble':
        ['        // MCRR (F3) lê os dois registradores ARM (Rt/Rt2) e MRRC escreve os dois.']
        + one(U, 'load ? 0 : (1 << rt) | (1 << rt2)') + one(D, 'load ? (1 << rt) | (1 << rt2) : 0'),
    'SystemOp.Swi': ['        // SWI dispara exceção — todos os registradores podem ser inspecionados'] + one(U, 'GprMask.ALL'),
    'SystemOp.Undefined': ['        // Undefined dispara exceção — todos os registradores podem ser inspecionados'] + one(U, 'GprMask.ALL'),
    'SystemOp.MProfileSystemRegister':
        ['        // MSR lê o registrador ARM fonte e MRS escreve o destino; o registrador especial fica',
         '        // fora deste bitmask (B7.4).']
        + one(U, 'read ? 0 : (1 << armRegister)') + one(D, 'read ? (1 << armRegister) : 0'),

    'VfpOp.Load': one(U, '1 << base'),
    'VfpOp.Store': one(U, '1 << base'),
    'VfpOp.MultipleTransfer': one(U, '1 << base') + one(D, 'writeback ? (1 << base) : 0'),
    'VfpOp.CoreTransfer': one(U, 'toArmRegister ? 0 : (1 << armRegister)') + one(D, 'toArmRegister ? (1 << armRegister) : 0'),
    'VfpOp.CorePairTransfer': one(U, 'toArmRegisters ? 0 : (1 << armLow) | (1 << armHigh)')
        + one(D, 'toArmRegisters ? (1 << armLow) | (1 << armHigh) : 0'),
    'VfpOp.SystemTransfer':
        ['        // VMRS Rt,FPSCR escreve Rt — exceto o caso especial APSR_nzcv (Rt=15), que escreve o',
         '        // CPSR.NZCV em vez de R15.']
        + one(U, 'read ? 0 : (1 << armRegister)')
        + one(D, 'read && armRegister != GprMask.PC_INDEX ? (1 << armRegister) : 0'),
    # VLDR_sysreg/VSTR_sysreg (B15.3): `base` sempre lido; destino/origem é FPSCR, fora do bitmask.
    'VfpOp.SysregMemoryTransfer': one(U, '1 << base') + one(D, 'writeback ? (1 << base) : 0'),
}

done = set()
for fam in sorted({k.split('.')[0] for k in SPEC}):
    path = os.path.join(IR, fam + '.java')
    lines = open(path, encoding='utf-8').read().split('\n')
    out = []
    current = None
    for line in lines:
        m = re.match(r'^    record (\w+)\(', line)
        if m:
            current = fam + '.' + m.group(1)
        out.append(line)
        if current in SPEC and re.match(r'^        @Override public boolean execute\(', line):
            out.extend(SPEC[current])
            done.add(current)
            current = None
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
missing = set(SPEC) - done
print('inseridos:', len(done), 'faltando:', sorted(missing))
