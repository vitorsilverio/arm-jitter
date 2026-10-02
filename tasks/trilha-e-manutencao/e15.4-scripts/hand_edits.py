from pathlib import Path
R = Path('core/src/main/java/dev/vitorsilverio/armjitter')

def sub(path, old, new, count=1):
    s = path.read_text(encoding='utf-8')
    assert s.count(old) == count, (path, old, s.count(old))
    path.write_text(s.replace(old, new), encoding='utf-8', newline='\n')

op = R / 'ir64/Ir64Op.java'
sub(op, """package dev.vitorsilverio.armjitter.ir64;

""", """package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64SystemExecutor;

""")
sub(op, """    int kind();

""", """    int kind();

    /// Executa a semântica desta operação sobre o core — o único dispatch do interpretador A64
    /// (task E15.4): cada record delega, numa linha, para o método estático do executor da sua
    /// família em `executor64`, e esquecer a ponte num record novo é erro de compilação. Não há
    /// parâmetro de "modo": o backend ASM não executa a op, ele emite bytecode (ou chama este mesmo
    /// método como fallback por-op).
    ///
    /// {@link Cycle} e {@link Fetch} não são instrução — quem percorre o bloco os contabiliza à
    /// parte (G4) — e lançam {@link IllegalStateException}.
    ///
    /// @param core core a executar
    /// @return `true` se a própria operação já alterou o PC (desvio tomado)
    boolean execute(Aarch64Core core);

    private static IllegalStateException notAnInstruction() {
        return new IllegalStateException("Cycle/Fetch não são decodificados como instrução");
    }

""")
sub(op, """        @Override public int kind() { return Kind.CYCLE; }
""", """        @Override public int kind() { return Kind.CYCLE; }
        @Override public boolean execute(Aarch64Core core) { throw notAnInstruction(); }
""")
sub(op, """        @Override public int kind() { return Kind.FETCH; }
""", """        @Override public int kind() { return Kind.FETCH; }
        @Override public boolean execute(Aarch64Core core) { throw notAnInstruction(); }
""")
sub(op, """        @Override public int kind() { return Kind.STREAMING_RESTRICTED; }
""", """        @Override public int kind() { return Kind.STREAMING_RESTRICTED; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64SystemExecutor.executeStreamingRestricted(core, this);
        }
""")
sub(op, "    /// em {@link Kind}, permitindo `tableswitch` no executor).", "    /// em {@link Kind}, permitindo `tableswitch` no executor). Desde a E15.4 o interpretador só o usa\n    /// para separar `Fetch`/`Cycle` das instruções; o dispatch por op é {@link #execute}.")

sy = R / 'executor64/Ir64SystemExecutor.java'
sub(sy, """import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
""", """import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
""")
sub(sy, """    /// `BRK` (B8.3) — sempre lança""", """    /// {@link Ir64Op.StreamingRestricted} (B18.2): `UNDEFINED` quando a restrição do modo streaming se
    /// aplica (`PSTATE.SM = 1` sem `FEAT_SME_FA64` efetivo); senão executa a operação embrulhada.
    public static boolean executeStreamingRestricted(Aarch64Core core, Ir64Op.StreamingRestricted op) {
        if (core.streamingRestrictionApplies()) {
            throw new Aarch64UndefinedInstructionException();
        }
        return op.inner().execute(core);
    }

    /// `BRK` (B8.3) — sempre lança""")

ex = R / 'executor64/Ir64BlockExecutor.java'
sub(ex, """/// semântica decodificada.
public final class""", """/// semântica decodificada.
///
/// Esta classe é só o laço (`step`/`run`/`executeBlock`). A semântica de cada operação vive nos
/// executores por família deste pacote e é alcançada por {@link Ir64Op#execute} — um único dispatch
/// por op, sem `switch` sobre {@link Ir64Op.Kind} (task E15.4).
public final class""")
sub(ex, """    /// — nenhuma lógica de {@code executeAlu}/{@code executeBranch}/etc. é duplicada.
""", """    /// — nenhuma lógica de {@code executeAlu}/{@code executeBranch}/etc. é duplicada. Equivale a
    /// {@link Ir64Op#execute} (E15.4); mantido como ponto de entrada estável do backend ASM.
""")
sub(ex, """    /// são tratados inline (G4: incondicionais); as demais ops são despachadas via
    /// {@link #executeOp}. Instruções""", """    /// são tratados inline (G4: incondicionais); as demais ops são despachadas via
    /// {@link Ir64Op#execute}. Instruções""")
