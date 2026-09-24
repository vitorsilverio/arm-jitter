package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Falha de acesso PMSAv8-32 (perfil não-M) detectada por {@link Pmsav8AddressSpace} durante a
/// checagem de região (B20.7). **Classe irmã** de {@link PmsaAccessException} (PMSAv7) e de
/// {@link dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException} (VMSA) — nunca uma
/// generalização de nenhuma das duas, mesmo motivo já registrado pelo Javadoc de
/// {@link PmsaAccessException}: os três formatos de falha são incompatíveis, e capturar por NOME
/// evita que o encoder de FSR errado seja chamado para o tipo de falta errado. O custo (repetido
/// aqui, pago pela segunda vez pelo mesmo motivo) é mais um `catch`/`visitTryCatchBlock` nos
/// motores (`ArmCore`/`IrBlockExecutor`/`AsmBlockCompiler`/`StandardIrBlockLifter`/`JitRuntime`).
///
/// Unchecked e sem stack trace (mesmo motivo das duas classes irmãs): faltas de PMSA são
/// esperadas e frequentes em qualquer sistema com MPU habilitada.
public final class Pmsav8AccessException extends RuntimeException {
    private final int virtualAddress;
    private final MemoryAccessType accessType;
    private final Pmsav8FaultStatus faultStatus;

    /// @param virtualAddress endereço que causou a falha
    /// @param accessType tipo de acesso (fetch de instrução, leitura ou escrita de dados)
    /// @param faultStatus código de falha PMSAv8-32, pronto para `DFSR`/`IFSR` (via
    ///                     {@link Pmsav8FaultStatus#code()})
    public Pmsav8AccessException(int virtualAddress, MemoryAccessType accessType, Pmsav8FaultStatus faultStatus) {
        super(buildMessage(virtualAddress, accessType, faultStatus), null, false, false);
        this.virtualAddress = virtualAddress;
        this.accessType = accessType;
        this.faultStatus = faultStatus;
    }

    private static String buildMessage(int virtualAddress, MemoryAccessType accessType, Pmsav8FaultStatus faultStatus) {
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

    /// Código de falha PMSAv8-32, pronto para `DFSR`/`IFSR`.
    public Pmsav8FaultStatus faultStatus() {
        return faultStatus;
    }
}
