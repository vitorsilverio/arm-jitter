import re, pathlib, sys
root = pathlib.Path('core/src')
main = root/'main/java/dev/vitorsilverio/armjitter'
test = root/'test/java/dev/vitorsilverio/armjitter'

# --- DecodeTable ---
p = main/'decodetable/DecodeTable.java'
s = p.read_text(encoding='utf-8')
s = s.replace('package dev.vitorsilverio.armjitter.decoder64;', 'package dev.vitorsilverio.armjitter.decodetable;')
s = s.replace('import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;\n\n', '')
s = s.replace('import java.util.List;\n', 'import java.util.List;\nimport java.util.function.Predicate;\n')
s = s.replace('DecodeRow<T>', 'DecodeRow<F, T>').replace('DecodeRow<?>', 'DecodeRow<?, ?>')
s = s.replace('new DecodeTable<>(kept)', 'new DecodeTable<>(kept)')
s = s.replace('/// @param <T> tipo da operação decodificada\nfinal class DecodeTable<T> {',
              '/// @param <F> tipo da feature das linhas\n/// @param <T> tipo da operação decodificada\npublic final class DecodeTable<F, T> {')
s = s.replace('    static final int MAX_KEY_BITS', '    public static final int MAX_KEY_BITS')
old_factory = s[s.index('    /// Monta a tabela com as linhas de `rows` que `architecture` suporta'):s.index('    @SuppressWarnings("unchecked")')]
new_factory = '''    /// Monta a tabela com as linhas de `rows` que `has` suporta, na ordem dada. Linha sem a feature
    /// some, ou — quando declara {@link DecodeRow#whenAbsent} — fica e passa a construir por ele.
    public static <F, T> DecodeTable<F, T> forFeatures(List<DecodeRow<F, T>> rows, Predicate<? super F> has) {
        List<DecodeRow<F, T>> kept = new ArrayList<>();
        for (DecodeRow<F, T> row : rows) {
            if (row.supportedBy(has)) {
                kept.add(row);
            } else if (row.whenAbsent() != null) {
                kept.add(new DecodeRow<>(row.mask(), row.value(), row.requires(), row.alsoRequires(),
                        row.whenAbsent(), row.whenAbsent()));
            }
        }
        return new DecodeTable<>(kept);
    }

'''
s = s.replace(old_factory, new_factory)
s = s.replace('    T decode(int word, long address) {', '    public T decode(int word, long address) {')
s = s.replace('    /// As linhas mantidas, na ordem de declaração.\n    List<', '    /// As linhas mantidas (as sem feature, já com o construtor de ausência), na ordem de declaração.\n    public List<')
s = s.replace('    int keyMask() {', '    public int keyMask() {')
s = s.replace('**Construção por arquitetura:** {@link #forArchitecture}', '**Construção por arquitetura:** {@link #forFeatures}')
p.write_text(s, encoding='utf-8', newline='\n')

# --- invariants / table test ---
for name in ['DecodeTableInvariants.java', 'DecodeTableTest.java']:
    p = test/'decodetable'/name
    s = p.read_text(encoding='utf-8')
    s = s.replace('package dev.vitorsilverio.armjitter.decoder64;', 'package dev.vitorsilverio.armjitter.decodetable;')
    p.write_text(s, encoding='utf-8', newline='\n')

def fix_calls(s):
    out = []; i = 0; key = 'DecodeTable.forArchitecture('
    while True:
        j = s.find(key, i)
        if j < 0: out.append(s[i:]); break
        out.append(s[i:j]); out.append('DecodeTable.forFeatures(')
        k = j + len(key); depth = 1; start = k
        while depth:
            c = s[k]
            if c == '(': depth += 1
            elif c == ')': depth -= 1
            k += 1
        body = s[start:k-1]
        out.append(body.rstrip() + '::has)')
        i = k
    return ''.join(out)

changed = []
for base in [main/'decoder64', test/'decoder64', test/'decodetable', test/'tools', test]:
    for p in (base.glob('*.java') if base != test else []):
        s0 = p.read_text(encoding='utf-8')
        if not re.search(r'\bDecode(Row|Table|TableInvariants)\b', s0): continue
        crlf = '\r\n' in s0
        s = s0.replace('\r\n', '\n')
        s = re.sub(r'DecodeRow<(?!F, |\?, )([A-Za-z0-9]+)>', r'DecodeRow<Aarch64Feature, \1>', s)
        s = s.replace('DecodeRow<?>', 'DecodeRow<?, ?>')
        s = re.sub(r'DecodeTable<(?!F, )([A-Za-z0-9]+)>', r'DecodeTable<Aarch64Feature, \1>', s)
        s = fix_calls(s)
        pkg = re.search(r'^package ([\w.]+);\n', s, re.M)
        imports = []
        if pkg.group(1) != 'dev.vitorsilverio.armjitter.decodetable':
            for cls in ['DecodeRow', 'DecodeTable', 'DecodeTableInvariants']:
                if re.search(r'\b' + cls + r'\b(?!Invariants|Test)', s) and ('import dev.vitorsilverio.armjitter.decodetable.' + cls + ';') not in s:
                    imports.append('import dev.vitorsilverio.armjitter.decodetable.' + cls + ';')
        if 'Aarch64Feature' in s and 'import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;' not in s and pkg.group(1) != 'dev.vitorsilverio.armjitter.arch64':
            imports.append('import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;')
        if imports:
            m = re.search(r'^import ', s, re.M)
            pos = m.start() if m else pkg.end() + 1
            s = s[:pos] + '\n'.join(sorted(imports)) + '\n' + ('' if m else '\n') + s[pos:]
        if crlf: s = s.replace('\n', '\r\n')
        if s != s0:
            p.write_bytes(s.encode('utf-8')); changed.append(p.name)
print(len(changed), changed)
