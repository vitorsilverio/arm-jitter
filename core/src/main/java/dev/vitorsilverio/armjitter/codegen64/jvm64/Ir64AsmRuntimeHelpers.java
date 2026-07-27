package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Alvo de `INVOKESTATIC` do bytecode gerado por {@code Ir64BlockCompiler} — espelho estrutural
/// (bem mais enxuto) de {@link dev.vitorsilverio.armjitter.codegen.jvm.AsmRuntimeHelpers} (32
/// bits), introduzido na task B6.4 (PR1, decisão D-ASM).
///
/// Decisão explícita (D-ASM da spec): em vez de reimplementar a aritmética de `Alu64`/
/// `MoveWide`/`PcRelative`/`Branch64`/`CompareBranch64` em bytecode bruto (o que duplicaria
/// lógica já auditada pelos corpus tests de B6.1-B6.3.4 e teria que ser re-testada do zero), o
/// bytecode gerado reconstrói o {@link Ir64Op} exato (record imutável, campos conhecidos em
/// tempo de compilação) e delega a {@link Ir64BlockExecutor#executeOp} — o MESMO caminho que o
/// interpretador usa. Isto garante G1 (interpretador é o oráculo) POR CONSTRUÇÃO: qualquer bug
/// corrigido no executor beneficia os dois backends automaticamente, sem re-sincronização manual.
///
/// **Custo documentado** (candidato natural de otimização de uma PR futura de registrador-cache,
/// não desta): cada chamada reconstrói uma instância nova do record — barato (sem reflexão), mas
/// soma num loop quente. Ver Armadilhas de `b6.4-aarch64-asm-backend.md`.
public final class Ir64AsmRuntimeHelpers {
    /// Instância compartilhada, stateless para fins de {@link Ir64BlockExecutor#executeOp} (o
    /// único campo de instância da classe, um `Aarch64Decoder`, não é tocado por esse método).
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private Ir64AsmRuntimeHelpers() {
    }

    /// Despacha `op` através do interpretador compartilhado. Alvo de `INVOKESTATIC` no bytecode
    /// gerado — ver a classe para a decisão de desenho completa.
    ///
    /// @param core core a executar
    /// @param op operação já reconstruída pelo bytecode gerado (nunca `Cycle`/`Fetch`)
    /// @return `true` se a própria operação já alterou o PC (desvio tomado)
    public static boolean executeOp(Aarch64Core core, Ir64Op op) {
        return EXECUTOR.executeOp(core, op);
    }
}
