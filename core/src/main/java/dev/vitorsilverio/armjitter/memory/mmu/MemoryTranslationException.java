package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Falha de tradução de endereço (translation/domain/permission fault) detectada por
/// `TranslatingAddressSpace` durante um page-walk ou uma checagem de permissão em cache de TLB.
///
/// Unchecked e sem stack trace (RFC-SOFTMMU §3): faltas de tradução são esperadas e frequentes
/// em qualquer sistema com MMU habilitada (ex.: kernel tratando *demand paging*), então o custo
/// de capturar o stack trace de toda falha seria desperdiçado — quem trata isto (B4.1.3) só
/// precisa de `virtualAddress`, `accessType` e `faultStatus` para preencher `DFAR`/`DFSR` (ou
/// `IFAR`/`IFSR`) e entrar em `ArmException.DATA_ABORT`/`PREFETCH_ABORT`.
public final class MemoryTranslationException extends RuntimeException {
    private final int virtualAddress;
    private final MemoryAccessType accessType;
    private final FaultStatus faultStatus;

    /// @param virtualAddress endereço virtual que causou a falha
    /// @param accessType tipo de acesso (fetch de instrução, leitura ou escrita de dados)
    /// @param faultStatus código de falha no formato `FS[3:0]` do ARMv6
    public MemoryTranslationException(int virtualAddress, MemoryAccessType accessType, FaultStatus faultStatus) {
        super(buildMessage(virtualAddress, accessType, faultStatus), null, false, false);
        this.virtualAddress = virtualAddress;
        this.accessType = accessType;
        this.faultStatus = faultStatus;
    }

    private static String buildMessage(int virtualAddress, MemoryAccessType accessType, FaultStatus faultStatus) {
        return faultStatus + " em 0x" + Integer.toHexString(virtualAddress) + " (" + accessType + ")";
    }

    /// Endereço virtual (MVA) que causou a falha.
    public int virtualAddress() {
        return virtualAddress;
    }

    /// Tipo de acesso que causou a falha.
    public MemoryAccessType accessType() {
        return accessType;
    }

    /// Código de falha (`FS[3:0]`), pronto para `DFSR`/`IFSR`.
    public FaultStatus faultStatus() {
        return faultStatus;
    }
}
