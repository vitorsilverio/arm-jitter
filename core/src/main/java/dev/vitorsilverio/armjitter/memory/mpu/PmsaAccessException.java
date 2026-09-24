package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Falha de acesso PMSAv7 (background fault ou permission fault) detectada por
/// {@link PmsaAddressSpace} durante a checagem de região (B20.3). **Classe irmã** de
/// {@link dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException} (VMSA) — nunca uma
/// generalização dela: `MemoryTranslationException` é tipada com
/// {@link dev.vitorsilverio.armjitter.memory.mmu.FaultStatus} (VMSA), e capturada por NOME nos
/// motores (`ArmCore`/`IrBlockExecutor`/`AsmBlockCompiler`/`StandardIrBlockLifter`/`JitRuntime`) —
/// generalizá-la para aceitar qualquer código de falha tornaria o tipo `FaultStatus` inconsistente
/// e arriscaria o encoder de FSR do VMSA ser chamado por engano para uma falta de PMSA (a armadilha
/// nº 2 do épico B20: "um kernel de tempo real diagnosticando a falha errada"). O custo desta
/// decisão é que cada motor precisa de um `catch`/`visitTryCatchBlock` A MAIS, mirando este tipo —
/// pago nesta task, mesmo padrão da B4.1.3.
///
/// Unchecked e sem stack trace (mesmo motivo de `MemoryTranslationException`): faltas de PMSA são
/// esperadas e frequentes em qualquer sistema com MPU habilitada.
public final class PmsaAccessException extends RuntimeException {
    private final int virtualAddress;
    private final MemoryAccessType accessType;
    private final PmsaFaultStatus faultStatus;

    /// @param virtualAddress endereço que causou a falha
    /// @param accessType tipo de acesso (fetch de instrução, leitura ou escrita de dados)
    /// @param faultStatus código de falha PMSAv7, pronto para `DFSR`/`IFSR` (via
    ///                     {@link PmsaFaultStatus#code()})
    public PmsaAccessException(int virtualAddress, MemoryAccessType accessType, PmsaFaultStatus faultStatus) {
        super(buildMessage(virtualAddress, accessType, faultStatus), null, false, false);
        this.virtualAddress = virtualAddress;
        this.accessType = accessType;
        this.faultStatus = faultStatus;
    }

    private static String buildMessage(int virtualAddress, MemoryAccessType accessType, PmsaFaultStatus faultStatus) {
        return faultStatus + " em 0x" + Integer.toHexString(virtualAddress) + " (" + accessType + ")";
    }

    /// Endereço que causou a falha.
    public int virtualAddress() {
        return virtualAddress;
    }

    /// Tipo de acesso que causou a falha.
    public MemoryAccessType accessType() {
        return accessType;
    }

    /// Código de falha PMSAv7, pronto para `DFSR`/`IFSR`.
    public PmsaFaultStatus faultStatus() {
        return faultStatus;
    }
}
