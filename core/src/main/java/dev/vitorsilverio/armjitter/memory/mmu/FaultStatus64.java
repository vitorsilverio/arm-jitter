package dev.vitorsilverio.armjitter.memory.mmu;

/// Código de falha de tradução VMSA64 (`ESR_EL1.ISS` — campo `DFSC`/`IFSC`, "Data/Instruction
/// Fault Status Code"), sibling de {@link FaultStatus} (ARMv6 short-descriptor) para o mundo
/// A64. **Diferente do precedente 32-bit**: `ESR_EL1` não é uma enumeração plana de 4 bits — o
/// código de falha aqui já embute o NÍVEL do page-walk (`0b0001xx` = translation fault, `xx` =
/// nível; `0b0011xx` = permission fault, `xx` = nível), então cada categoria de falha tem uma
/// variante por nível (0-3 para translation fault — VMSA64 tem 4 níveis, L0-L3; 1-3 para
/// permission fault — L0 nunca é folha, então nunca gera permission fault).
///
/// **Pin de versão**: valores conforme `ARM DDI 0487` D17.2 (tabela do campo `DFSC`); não
/// verificados contra código nesta rodada (mesma ressalva de sempre, ver Armadilhas da task
/// B6.6.2).
public enum FaultStatus64 {
    /// Nível 0 sem descritor válido, ou bloco/página com tipo reservado nesse nível.
    TRANSLATION_FAULT_L0(0x04),
    /// Nível 1 sem descritor válido.
    TRANSLATION_FAULT_L1(0x05),
    /// Nível 2 sem descritor válido.
    TRANSLATION_FAULT_L2(0x06),
    /// Nível 3 sem descritor válido.
    TRANSLATION_FAULT_L3(0x07),
    /// Bloco de 1GiB (nível 1) nega o acesso pedido (`AP`/`PXN`/`UXN`).
    PERMISSION_FAULT_L1(0x0D),
    /// Bloco de 2MiB (nível 2) nega o acesso pedido.
    PERMISSION_FAULT_L2(0x0E),
    /// Página de 4KiB (nível 3) nega o acesso pedido.
    PERMISSION_FAULT_L3(0x0F);

    private final int code;

    FaultStatus64(int code) {
        this.code = code;
    }

    /// Valor de 6 bits pronto para `ESR_EL1.ISS[5:0]` (`DFSC`/`IFSC`).
    public int code() {
        return code;
    }

    /// Falha de tradução (descritor inválido) no nível informado (0-3).
    public static FaultStatus64 translationFault(int level) {
        return switch (level) {
            case 0 -> TRANSLATION_FAULT_L0;
            case 1 -> TRANSLATION_FAULT_L1;
            case 2 -> TRANSLATION_FAULT_L2;
            case 3 -> TRANSLATION_FAULT_L3;
            default -> throw new IllegalArgumentException("nível de tradução inválido: " + level);
        };
    }

    /// Falha de permissão (`AP`/`PXN`/`UXN`) na folha resolvida no nível informado (1-3 — o
    /// nível 0 nunca é folha em VMSA64 4KiB/48-bit, ver {@link TranslatingAddressSpace64}).
    public static FaultStatus64 permissionFault(int level) {
        return switch (level) {
            case 1 -> PERMISSION_FAULT_L1;
            case 2 -> PERMISSION_FAULT_L2;
            case 3 -> PERMISSION_FAULT_L3;
            default -> throw new IllegalArgumentException("nível de permissão inválido: " + level);
        };
    }
}
