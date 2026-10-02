"""E15.4 — atualiza menções em prosa/Javadoc a `Ir64BlockExecutor#metodo` para a classe que
recebeu o método (rodar da raiz do repo, depois do `split.py`)."""
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import split  # noqa: E402

old = subprocess.run(['git', 'show', 'HEAD:core/src/main/java/dev/vitorsilverio/armjitter/executor64/Ir64BlockExecutor.java'],
                     capture_output=True, check=True).stdout.decode('utf-8').replace('\r\n', '\n').split('\n')
_, owner, _ = split.assign(old)
moved = {name: cls for name, cls in owner.items() if cls != split.STAY}
pattern = re.compile(r'Ir64BlockExecutor#(' + '|'.join(sorted(moved, key=len, reverse=True)) + r')\b')
total = 0
for root in ('core/src', 'truffle/src', 'docs'):
    for path in Path(root).rglob('*'):
        if path.suffix not in ('.java', '.md') or not path.is_file():
            continue
        raw = path.read_bytes().decode('utf-8')
        new, count = pattern.subn(lambda m: moved[m.group(1)] + '#' + m.group(1), raw)
        if count:
            path.write_bytes(new.encode('utf-8'))
            total += count
            print(count, path.as_posix())
print(total, 'menções atualizadas')
