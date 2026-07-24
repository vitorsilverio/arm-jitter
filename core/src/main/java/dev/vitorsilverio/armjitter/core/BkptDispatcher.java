package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.swi.CpuState;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Tabela de handlers de `BKPT` com fallback opcional (B7.5) — mesmo padrão de
/// {@link dev.vitorsilverio.armjitter.swi.SwiDispatcher}, usado pelo host (ex. armbox) para
/// semihosting sem depender de um debugger real conectado.
public final class BkptDispatcher {
    private final Map<Integer, BkptHandler> handlers = new HashMap<>();
    private BkptFallbackHandler fallback;

    /// Cria um dispatcher sem handlers registrados.
    public static BkptDispatcher empty() {
        return new BkptDispatcher();
    }

    /// Registra ou substitui o handler de um imediato de `BKPT`.
    public void register(int immediate, BkptHandler handler) {
        handlers.put(immediate, Objects.requireNonNull(handler, "handler"));
    }

    /// Define um handler fallback usado quando o imediato não tem handler dedicado.
    public void fallback(BkptHandler handler) {
        Objects.requireNonNull(handler, "handler");
        this.fallback = (immediate, state) -> handler.handle(state);
    }

    /// Define um handler fallback que também recebe o imediato do `BKPT`.
    public void fallbackWithImmediate(BkptFallbackHandler handler) {
        this.fallback = Objects.requireNonNull(handler, "handler");
    }

    /// Executa o handler registrado para `immediate`.
    public CpuState dispatch(int immediate, CpuState state) {
        BkptHandler handler = handlers.get(immediate);
        if (handler != null) {
            return handler.handle(state);
        }
        if (fallback != null) {
            return fallback.handle(immediate, state);
        }
        throw new IllegalStateException("No BKPT handler registered for #" + immediate);
    }

    /// Retorna {@code true} quando existe handler dedicado ou fallback para `immediate`.
    public boolean canDispatch(int immediate) {
        return handlers.containsKey(immediate) || fallback != null;
    }
}
