# E15.2 - nomes internos de bytecode (`IR64_OP + "$Xxx"`) no Ir64BlockCompiler.
import re, sys
sys.path.insert(0, 'target/e15.2')
from split import load_mapping
mapping = load_mapping()
P = 'core/src/main/java/dev/vitorsilverio/armjitter/codegen64/jvm64/Ir64BlockCompiler.java'
raw = open(P, encoding='utf-8', newline='').read()
count = 0


def sub(m):
    global count
    count += 1
    leaf, new, _ = mapping[m.group(1)]
    return 'IR64_PACKAGE + "' + leaf + '$' + new + '"'


out = re.sub(r'IR64_OP \+ "\$(\w+)"', sub, raw)
old_decl = 'private static final String IR64_OP = "dev/vitorsilverio/armjitter/ir64/Ir64Op";'
assert old_decl in out
out = out.replace(old_decl,
                  '/// Prefixo do nome interno das classes do pacote `ir64` — os records vivem aninhados na\n'
                  '    /// sub-interface selada da sua família (`IntegerOp64$Alu64`, `FpOp64$Alu`, ...; task E15.2).\n'
                  '    private static final String IR64_PACKAGE = "dev/vitorsilverio/armjitter/ir64/";\n'
                  '    private static final String IR64_OP = IR64_PACKAGE + "Ir64Op";'.replace('\n', '\r\n' if '\r\n' in raw else '\n'))
open(P, 'w', encoding='utf-8', newline='').write(out)
print('substituicoes:', count)
