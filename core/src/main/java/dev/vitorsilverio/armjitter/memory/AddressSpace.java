package dev.vitorsilverio.armjitter.memory;

/// Barramento de memória implementado pelo dispositivo hospedeiro.
public interface AddressSpace {
    /// Lê um byte sem sinal no endereço informado.
    int read8(int address);

    /// Lê uma halfword de 16 bits no endereço informado.
    int read16(int address);

    /// Lê uma word de 32 bits no endereço informado.
    int read32(int address);

    /// Escreve os 8 bits inferiores de `value` no endereço informado.
    void write8(int address, int value);

    /// Escreve os 16 bits inferiores de `value` no endereço informado.
    void write16(int address, int value);

    /// Escreve os 32 bits de `value` no endereço informado.
    void write32(int address, int value);

    /// Retorna ciclos extras para um acesso de memória.
    ///
    /// A implementação padrão retorna `0` para manter compatibilidade com testes e
    /// barramentos simples. Emuladores GBA podem sobrescrever este método para aplicar
    /// waitstates de BIOS, IWRAM, EWRAM, VRAM e ROM.
    ///
    /// @param address endereço acessado
    /// @param sizeBytes tamanho do acesso em bytes
    /// @param type tipo de acesso realizado
    /// @return ciclos extras consumidos pelo acesso
    default int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }

    /// Indica se {@link #accessCycles} pode retornar valores diferentes de zero.
    ///
    /// O padrão conservador é `true`. Um barramento que SEMPRE retorna `0` (sem waitstates)
    /// pode sobrescrever para `false`: nesse caso o core pula inteiramente a contabilização
    /// por acesso no caminho quente (uma chamada virtual por fetch/load/store). Sobrescreva
    /// em conjunto com {@link #accessCycles} — se aquele puder ser não-zero, este DEVE
    /// permanecer `true`, sob pena de perder os waitstates.
    default boolean providesAccessCycles() {
        return true;
    }

    /// Notifica que uma escrita ocorreu, permitindo invalidação de código automodificado.
    default void notifyWrite(int address) {
    }

    /// Lê uma halfword de instrução (THUMB) no endereço informado.
    ///
    /// O padrão delega a {@link #read16} (comportamento idêntico ao pré-B4.1.3 para todo
    /// barramento que não distingue busca de instrução de leitura de dados). Um `AddressSpace`
    /// com MMU (`TranslatingAddressSpace`, B4.1.1) sobrescreve para traduzir pela TLB de
    /// INSTRUÇÃO (separada da de dados) e lançar `PREFETCH_ABORT` em vez de `DATA_ABORT` quando a
    /// tradução falha (B4.1.3, RFC-SOFTMMU §3) — os decoders ARM/Thumb chamam este método, nunca
    /// {@link #read16} diretamente, para que a distinção aconteça sem que eles precisem conhecer
    /// MMU alguma.
    ///
    /// @param address endereço virtual da instrução
    default int fetch16(int address) {
        return read16(address);
    }

    /// Lê uma word de instrução (ARM) no endereço informado. Ver {@link #fetch16} — mesma regra,
    /// delega a {@link #read32} por padrão.
    ///
    /// @param address endereço virtual da instrução
    default int fetch32(int address) {
        return read32(address);
    }

    /// Geração de tradução MMU atual (RFC-SOFTMMU §5, B4.1.4): incrementada a cada escrita de
    /// `TTBR0`/`CONTEXTIDR` (troca de tabela de páginas/ASID — uma "troca de processo") e a cada
    /// `TLBIALL`, nunca em `TLBIMVA` isolado nem em mudança de `DACR`/modo privilegiado (que não
    /// invalidam traduções já resolvidas, só a política de checagem reavaliada a cada acesso —
    /// ver `TranslatingAddressSpace`).
    ///
    /// O `JitRuntime` inclui este valor no `BlockKey` de todo bloco lifted: um mesmo VA sob
    /// mapeamentos de página DIFERENTES em duas gerações nunca reaproveita o bloco compilado da
    /// geração anterior (miss natural no `BlockCache`, mesma técnica do tag de ITSTATE da B2.4) —
    /// e o runtime também esvazia seu inline cache de dispatch quando o valor muda, para que um
    /// bloco encadeado (chaining) nunca atravesse uma troca de geração no meio da corrente.
    ///
    /// O padrão retorna `0` (constante) para todo barramento sem MMU — zero mudança de
    /// comportamento para os consumidores existentes (G3: gbaemu/ndsemu nunca envolvem seu
    /// `AddressSpace` no wrapper). Só `TranslatingAddressSpace` (B4.1.1) tem estado próprio aqui.
    default int translationGeneration() {
        return 0;
    }

    /// Executa `action` tratando qualquer leitura/escrita feita dentro dele como um acesso de
    /// memória "unprivileged" (`LDRxT`/`STRxT`, B9.9) — a mesma tradução/checagem de permissão que
    /// valeria para o modo `USER`, mesmo que o CPU esteja executando em modo privilegiado.
    ///
    /// O padrão (qualquer barramento sem MMU) apenas executa `action` sem nenhuma mudança de
    /// comportamento — memória plana não tem noção de privilégio (G3: gbaemu/ndsemu/armbox sem
    /// `TranslatingAddressSpace` continuam idênticos). Só um `AddressSpace` com MMU real
    /// (`TranslatingAddressSpace`) sobrescreve, alternando sua checagem de privilégio para o
    /// escopo de `action` e restaurando o valor original ao final (mesmo padrão de "efeito
    /// mínimo, restaurado no `finally`" já usado por
    /// `TranslatingAddressSpace64#translateForAddressTranslate`, B10.6).
    ///
    /// @param action a leitura ou escrita a executar sob permissão de `USER`
    default void withUnprivilegedAccess(Runnable action) {
        action.run();
    }
}
