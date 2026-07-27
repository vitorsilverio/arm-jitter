package dev.vitorsilverio.armjitter.jit64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;

/// Bloco A64 pronto para execução — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.jit.CompiledBlock} (32 bits), introduzido na task B6.4.
///
/// Pode ser uma closure que interpreta {@link dev.vitorsilverio.armjitter.ir64.Ir64Block} ou um
/// método JVM gerado via ASM. Em ambos os casos o retorno de {@link #execute} segue o mesmo
/// contrato do mundo 32-bit: ciclos internos ({@code Ir64Op.Cycle}) do bloco; fetch/waitstates de
/// memória vão para {@link Aarch64Core#cycles()}.
@FunctionalInterface
public interface CompiledBlock64 {
    /// Executa o bloco contra o core e retorna os ciclos internos consumidos.
    int execute(Aarch64Core core);
}
