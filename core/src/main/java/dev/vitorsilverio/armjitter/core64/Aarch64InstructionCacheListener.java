package dev.vitorsilverio.armjitter.core64;

/// Ponte entre a manutenção do cache de instruções feita pelo guest (`IC IALLU`/`IALLUIS`/`IVAU`)
/// e o backend que guarda código derivado da memória do guest — o JIT. Instalada por
/// {@link Aarch64Core#setInstructionCacheListener}; o backend interpretado, que decodifica da
/// memória a cada passo, não precisa de nenhuma.
public interface Aarch64InstructionCacheListener {
    /// `IC IALLU`/`IC IALLUIS`: todo código compilado é obsoleto.
    void invalidateAll();

    /// `IC IVAU`: o código compilado que cobre a página física {@code physicalPage}
    /// (`endereço físico >>> 12`) é obsoleto.
    void invalidatePhysicalPage(long physicalPage);
}
