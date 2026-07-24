package dev.vitorsilverio.armjitter.core64;

/// Recebe chamadas `SVC` do interpretador A64 — mesmo papel de
/// {@link dev.vitorsilverio.armjitter.swi.SwiDispatcher} no mundo de 32 bits, mas próprio deste
/// runtime (G2/G3: nenhum tipo do pacote `swi/` é reaproveitado aqui). Tradução de syscalls Linux
/// arm64 (números diferentes do ARM 32-bit) é escopo de B6.2/armbox — aqui só o gancho existe.
@FunctionalInterface
public interface Aarch64SvcHandler {
    /// Trata uma instrução `SVC` decodificada.
    ///
    /// @param core core que disparou a chamada
    /// @param immediate imediato de 16 bits da instrução `SVC`
    void handle(Aarch64Core core, int immediate);

    /// Handler padrão: não faz nada (mesmo idioma aditivo de
    /// {@link dev.vitorsilverio.armjitter.core.BkptDispatcher#empty()} — instalar um handler real
    /// não exige mudar a assinatura do construtor do core).
    static Aarch64SvcHandler none() {
        return (core, immediate) -> { };
    }
}
