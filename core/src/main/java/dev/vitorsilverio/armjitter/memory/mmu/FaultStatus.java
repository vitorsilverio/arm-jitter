package dev.vitorsilverio.armjitter.memory.mmu;

/// Código de status de falha (`FS[3:0]`) do formato short-descriptor ARMv6, usado para
/// preencher `DFSR`/`IFSR` quando a B4.1.2/B4.1.3 conectar este wrapper ao coprocessador CP15 e
/// à entrada de exceção de abort. Valores conforme ARM DDI 0100I, tabela B4-19.
public enum FaultStatus {
    /// Seção (1MiB) sem descritor L1 válido (`L1 type == 0b00` ou `0b11`).
    SECTION_TRANSLATION(0x5),
    /// Página (4KiB/64KiB) sem descritor L2 válido (`L2 type == 0b00`).
    PAGE_TRANSLATION(0x7),
    /// Domínio da seção configurado como {@link DomainAccess#NO_ACCESS} ou
    /// {@link DomainAccess#RESERVED} no `DACR`.
    SECTION_DOMAIN(0x9),
    /// Domínio da página configurado como {@link DomainAccess#NO_ACCESS} ou
    /// {@link DomainAccess#RESERVED} no `DACR`.
    PAGE_DOMAIN(0xB),
    /// Domínio em modo {@link DomainAccess#CLIENT} mas os bits AP da seção negam o acesso
    /// pedido (leitura/escrita, usuário/privilegiado).
    SECTION_PERMISSION(0xD),
    /// Domínio em modo {@link DomainAccess#CLIENT} mas os bits AP da página negam o acesso
    /// pedido.
    PAGE_PERMISSION(0xF);

    private final int code;

    FaultStatus(int code) {
        this.code = code;
    }

    /// Valor de 4 bits pronto para ser gravado em `DFSR`/`IFSR` (campo `FS[3:0]`).
    public int code() {
        return code;
    }
}
