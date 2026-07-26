package dev.vitorsilverio.armjitter.memory.mmu;

/// Valor do campo de 2 bits de um domínio no `DACR` (Domain Access Control Register), ARM DDI
/// 0100I §B4.9. Um `TranslatingAddressSpace` guarda, por entrada de TLB, apenas o NÚMERO do
/// domínio (0-15) resolvido pelo page-walk — a política de acesso é sempre relida do `DACR`
/// atual a cada acesso, então mudar o `DACR` em tempo de execução tem efeito imediato sem exigir
/// invalidação de TLB (comportamento real de hardware).
public enum DomainAccess {
    /// Qualquer acesso ao domínio gera *domain fault*, mesmo que os bits AP permitissem.
    NO_ACCESS(0b00),
    /// Acesso sujeito à checagem normal dos bits AP da entrada de tradução.
    CLIENT(0b01),
    /// Reservado (ARMv6): tratado de forma conservadora como {@link #NO_ACCESS} — o ARM ARM
    /// deixa o comportamento como implementation-defined/UNPREDICTABLE para este valor.
    RESERVED(0b10),
    /// Acesso total, ignorando os bits AP — falhas de tradução (seção/página com tipo *fault*)
    /// continuam valendo, só a checagem de permissão é ignorada.
    MANAGER(0b11);

    private static final DomainAccess[] BY_BITS = buildLookup();

    private final int bits;

    DomainAccess(int bits) {
        this.bits = bits;
    }

    private static DomainAccess[] buildLookup() {
        DomainAccess[] table = new DomainAccess[4];
        for (DomainAccess value : values()) {
            table[value.bits] = value;
        }
        return table;
    }

    /// Os dois bits usados para representar este valor dentro do `DACR`.
    public int bits() {
        return bits;
    }

    /// Decodifica os dois bits (0-3) de um campo de domínio do `DACR`.
    public static DomainAccess fromBits(int bits) {
        return BY_BITS[bits & 0b11];
    }
}
