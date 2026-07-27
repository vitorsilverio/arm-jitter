package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Falha de tradução VMSA64 (translation/permission fault) detectada por
/// `TranslatingAddressSpace64` durante um page-walk ou uma checagem de permissão em cache de TLB.
/// Sibling de {@link MemoryTranslationException} (32-bit) para o mundo A64 — endereço virtual de
/// 64 bits, {@link FaultStatus64} em vez de {@link FaultStatus}.
///
/// Unchecked e sem stack trace (mesmo raciocínio do precedente 32-bit, RFC-SOFTMMU §3): faltas de
/// tradução são esperadas e frequentes sob MMU habilitada.
public final class MemoryTranslationException64 extends RuntimeException {
    private final long virtualAddress;
    private final MemoryAccessType accessType;
    private final FaultStatus64 faultStatus;

    /// @param virtualAddress endereço virtual de 64 bits que causou a falha
    /// @param accessType tipo de acesso (fetch de instrução, leitura ou escrita de dados)
    /// @param faultStatus código de falha VMSA64 (`ESR_EL1.ISS` `DFSC`/`IFSC`)
    public MemoryTranslationException64(long virtualAddress, MemoryAccessType accessType, FaultStatus64 faultStatus) {
        super(buildMessage(virtualAddress, accessType, faultStatus), null, false, false);
        this.virtualAddress = virtualAddress;
        this.accessType = accessType;
        this.faultStatus = faultStatus;
    }

    private static String buildMessage(long virtualAddress, MemoryAccessType accessType, FaultStatus64 faultStatus) {
        return faultStatus + " em 0x" + Long.toHexString(virtualAddress) + " (" + accessType + ")";
    }

    /// Endereço virtual de 64 bits que causou a falha.
    public long virtualAddress() {
        return virtualAddress;
    }

    /// Tipo de acesso que causou a falha.
    public MemoryAccessType accessType() {
        return accessType;
    }

    /// Código de falha (`ESR_EL1.ISS` `DFSC`/`IFSC`), pronto para uma futura entrada de exceção
    /// síncrona (B6.6.4, fora de escopo desta task).
    public FaultStatus64 faultStatus() {
        return faultStatus;
    }
}
