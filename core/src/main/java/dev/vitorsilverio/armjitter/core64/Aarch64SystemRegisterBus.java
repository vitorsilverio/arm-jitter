package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

/// Gancho do hospedeiro para `MRS`/`MSR (register)` (B6.6.1) — sibling ESTRUTURAL de
/// {@link dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus} (32-bit), não uma extensão dele
/// (mesma disciplina de sempre entre os dois mundos, G2/G3). Um hospedeiro instala uma
/// implementação via {@link Aarch64Core#setSystemRegisterBus} — ex. `Aarch64VmsaSystemRegisters`
/// (B6.6.3, MMU v8) ligando `TTBR0_EL1`/`TCR_EL1`/`SCTLR_EL1`/etc.
///
/// Diferente do mundo 32-bit (onde um coprocessador ausente vira Instrução Indefinida entregue ao
/// guest, via `ArmCore`): `Aarch64Core` ainda não tem modelo de exceção síncrona (EL0-only, B6.1)
/// — um registrador sem hospedeiro instalado lança {@link UnsupportedOperationException}
/// diretamente do executor, mesmo padrão de "sem hospedeiro" já usado por
/// {@link dev.vitorsilverio.armjitter.core.ArmCore} para BKPT/SWI sem dispatcher.
public interface Aarch64SystemRegisterBus {
    /// Se este barramento atende o registrador de sistema fornecido.
    boolean handles(Aarch64SystemRegisterId register);

    /// `MRS`: lê o valor de 64 bits do registrador de sistema.
    long read(Aarch64SystemRegisterId register);

    /// `MSR`: escreve um valor de 64 bits no registrador de sistema.
    void write(Aarch64SystemRegisterId register, long value);

    /// `TLBI VMALLE1`/`TLBI VMALLE1IS` (B6.6.3, `Ir64Op.SystemInstruction`) — não é `MRS`/`MSR`
    /// (achado real de B6.6.3, `SYS` é um subgrupo de encoding diferente), mas vive no MESMO
    /// barramento porque é o único gancho que o hospedeiro instala em {@link Aarch64Core} para
    /// ações de nível de sistema. Default NOP — barramentos sem MMU instalada (ex.
    /// {@link #none()}) não têm TLB para invalidar.
    default void invalidateTlbAll() {
    }

    /// Um barramento sem registradores instalados — {@link #handles} sempre `false`. Padrão até
    /// que um hospedeiro real (B6.6.3) instale um via {@link Aarch64Core#setSystemRegisterBus}.
    static Aarch64SystemRegisterBus none() {
        return NoSystemRegisters.INSTANCE;
    }
}
