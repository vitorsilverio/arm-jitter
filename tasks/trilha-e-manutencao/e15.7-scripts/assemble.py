"""E15.7 fase 1 — monta os arquivos das famílias a partir de `target/e15.7/split/*.members`.

Cada família ganha cabeçalho (package, imports usados, Javadoc, construtor) + um marcador
`    //@@REGISTRO@@` onde o registro/predicados/contadores escritos à mão entram (via Edit), + os
membros movidos. Na base, `cache.` vira `state.cache.` (o register cache é estado compartilhado).
"""
import re
from pathlib import Path

SPLIT = Path('target/e15.7/split')
PKG = Path('core/src/main/java/dev/vitorsilverio/armjitter/codegen/jvm')

IMPORTS = {
    'ArmFeature': 'dev.vitorsilverio.armjitter.arch.ArmFeature',
    'IrBlockExecutor': 'dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor',
    'BlockTransferMode': 'dev.vitorsilverio.armjitter.decoder.BlockTransferMode',
    'BranchOp': 'dev.vitorsilverio.armjitter.ir.BranchOp',
    'IntegerOp': 'dev.vitorsilverio.armjitter.ir.IntegerOp',
    'IrOp': 'dev.vitorsilverio.armjitter.ir.IrOp',
    'IrOpCode': 'dev.vitorsilverio.armjitter.ir.IrOpCode',
    'IrOperand': 'dev.vitorsilverio.armjitter.ir.IrOperand',
    'MemoryOp': 'dev.vitorsilverio.armjitter.ir.MemoryOp',
    'SystemOp': 'dev.vitorsilverio.armjitter.ir.SystemOp',
    'VfpOp': 'dev.vitorsilverio.armjitter.ir.VfpOp',
    'MemoryAccessType': 'dev.vitorsilverio.armjitter.memory.MemoryAccessType',
    'Label': 'org.objectweb.asm.Label',
    'MethodVisitor': 'org.objectweb.asm.MethodVisitor',
    'Opcodes': 'org.objectweb.asm.Opcodes',
}

DOCS = {
    'AsmAluEmitter': '/// Emissão nativa de {@link IntegerOp.Alu} (todos os opcodes de `IrOpCode` menos `ORN`).',
    'AsmIntegerEmitter': '/// Emissão nativa do inteiro fora da ALU: multiplicação, ARMv5TE (saturação/DSP), ARMv6\n'
                         '/// (paralelas/SEL/saturação/USAD) e ARMv7 (bitfield/RBIT/divisão/MOVT).',
    'AsmMemoryEmitter': '/// Emissão nativa de load/store: LDR/STR, literal, LDM/STM/PUSH/POP (desenrolados no caso\n'
                        '/// comum), LDRD/STRD e acessos exclusivos.',
    'AsmControlEmitter': '/// Emissão nativa de desvio e sistema: branches, PSR, SWI, coprocessador, undefined, barreiras\n'
                         '/// e o par `Cycle`/`Fetch` de toda instrução.',
    'AsmVfpEmitter': '/// Emissão nativa do VFP (B3.6, PR2).',
}


def imports_for(text):
    used = sorted(v for k, v in IMPORTS.items() if re.search(r'\b' + k + r'\b', text))
    return '\n'.join(f'import {i};' for i in used)


def family(cls):
    members = (SPLIT / f'{cls}.members').read_text(encoding='utf-8')
    body = f'''    {cls}(AsmEmitState state) {{
        super(state);
    }}

    //@@REGISTRO@@

{members}'''
    text = f'''package dev.vitorsilverio.armjitter.codegen.jvm;

{imports_for(body)}

{DOCS[cls]}
///
/// Os records e as condições em que cada um é nativo estão em {{@link #register}}; o laço de
/// compilação e o register cache, em {{@link AsmBlockCompiler}}.
final class {cls} extends AsmEmitterBase {{
{body}}}
'''
    (PKG / f'{cls}.java').write_text(text, encoding='utf-8')
    print(cls, text.count('\n'))


for c in DOCS:
    family(c)
base = (SPLIT / 'AsmEmitterBase.members').read_text(encoding='utf-8').replace('cache.', 'state.cache.')
(SPLIT / 'AsmEmitterBase.members.fixed').write_text(base, encoding='utf-8')
