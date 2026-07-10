package dev.vitorsilverio.armjitter.swi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Tabela de handlers de SWI com fallback opcional.
public final class SwiDispatcher {
    private final Map<Integer, SwiHandler> handlers = new HashMap<>();
    private SwiFallbackHandler fallback;

    /// Cria um dispatcher sem handlers registrados.
    public static SwiDispatcher empty() {
        return new SwiDispatcher();
    }

    /// Registra ou substitui o handler de uma SWI.
    public void register(int swi, SwiHandler handler) {
        handlers.put(swi, Objects.requireNonNull(handler, "handler"));
    }

    /// Define um handler fallback usado quando a SWI nao tem handler dedicado.
    public void fallback(SwiHandler handler) {
        Objects.requireNonNull(handler, "handler");
        fallback = (swi, state) -> handler.handle(state);
    }

    /// Define um handler fallback que tambem recebe o numero da SWI.
    public void fallbackWithNumber(SwiFallbackHandler handler) {
        fallback = Objects.requireNonNull(handler, "handler");
    }

    /// Executa o handler registrado para `swi`.
    public CpuState dispatch(int swi, CpuState state) {
        SwiHandler handler = handlers.get(swi);
        if (handler != null) {
            return handler.handle(state);
        }
        if (fallback != null) {
            return fallback.handle(swi, state);
        }
        throw new IllegalStateException("No SWI handler registered for 0x" + Integer.toHexString(swi));
    }

    /// Retorna `true` quando existe handler dedicado ou fallback para `swi`.
    public boolean canDispatch(int swi) {
        return handlers.containsKey(swi) || fallback != null;
    }
}
