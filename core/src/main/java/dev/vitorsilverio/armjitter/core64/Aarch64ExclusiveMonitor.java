package dev.vitorsilverio.armjitter.core64;

import java.util.IdentityHashMap;
import java.util.Map;

/// Monitor de exclusividade `LDXR`/`LDAXR`/`STXR`/`STLXR` (B6.3.4) — sibling estrutural de
/// {@link dev.vitorsilverio.armjitter.core.ExclusiveMonitor} (o monitor de 32 bits, B1.4/B5.1),
/// NÃO uma generalização dele (D1 da task b6.3.4-aarch64-exclusive-monitor.md): `Aarch64Core` não é
/// um `ArmCore` (RFC-IR-64BIT.md §3.1, "irmão, não extensão"), então o tipo da chave do mapa nunca
/// convergiria sem contaminar o mundo de 32 bits com uma abstração que ele não precisa. O CONCEITO
/// é idêntico — reserva por-core de `(endereço, tamanho)`, consumida por correspondência EXATA — e
/// por isso os métodos têm os MESMOS nomes do monitor de 32 bits, facilitando auditoria cruzada.
///
/// **Simplificação deliberada (mesma do monitor de 32 bits)**: NÃO compara o VALOR da memória no
/// consumo do `STXR`/`STLXR` (QEMU `gen_store_exclusive` faz essa comparação extra para MTTCG
/// multi-thread real) — este emulador é single-thread por construção, então comparar valor seria
/// uma garantia a mais sem nenhum consumidor real (armbox não tem múltiplas threads reais).
///
/// Nasce já parametrizado por `IdentityHashMap<Aarch64Core, Reservation>` (em vez de uma única
/// reserva sem mapa) espelhando o desenho pós-B5.1 do monitor de 32 bits — o custo de já nascer
/// assim é zero e evita um retrofit futuro se `Aarch64Core` algum dia ganhar múltiplos cores
/// compartilhando memória (nenhuma RFC prevista ainda, mas o padrão já está validado).
public final class Aarch64ExclusiveMonitor {
    private record Reservation(long address, int sizeBytes) {
        boolean matches(long address, int sizeBytes) {
            return this.address == address && this.sizeBytes == sizeBytes;
        }

        boolean overlaps(long otherAddress, int otherSizeBytes) {
            long end = address + sizeBytes;
            long otherEnd = otherAddress + otherSizeBytes;
            return otherAddress < end && address < otherEnd;
        }
    }

    /// Reserva ativa de cada core que já fez `LDXR`/`LDAXR`, indexada por identidade (não por
    /// `equals`/`hashCode` do core). Ausência de entrada = monitor aberto para aquele core.
    private final Map<Aarch64Core, Reservation> reservations = new IdentityHashMap<>();

    /// Marca a reserva de `owner` no endereço e tamanho de um `LDXR`/`LDAXR`, substituindo a
    /// reserva anterior desse core, se houver.
    void markExclusive(Aarch64Core owner, long address, int sizeBytes) {
        reservations.put(owner, new Reservation(address, sizeBytes));
    }

    /// Consulta um `STXR`/`STLXR` de `requester`. Exige marcação exata (mesmo endereço e mesmo
    /// tamanho). QUALQUER reserva — de `requester` ou de outro core, quando o monitor é
    /// compartilhado — cujo endereço/tamanho bata exatamente é consumida (aberta); só devolve
    /// `true` (sucesso: o chamador deve efetivamente escrever) quando a PRÓPRIA reserva de
    /// `requester` batia.
    boolean consumeIfCovered(Aarch64Core requester, long address, int sizeBytes) {
        Reservation own = reservations.get(requester);
        boolean requesterOwnedIt = own != null && own.matches(address, sizeBytes);
        reservations.values().removeIf(reservation -> reservation.matches(address, sizeBytes));
        return requesterOwnedIt;
    }

    /// Escrita comum (`STR`/`STP`, de qualquer core) que sobrepõe uma reserva pendente — de
    /// qualquer core — abre essa reserva.
    void notifyOrdinaryWrite(long address, int sizeBytes) {
        if (reservations.isEmpty()) {
            return;
        }
        reservations.values().removeIf(reservation -> reservation.overlaps(address, sizeBytes));
    }

    /// Abre a reserva de `owner`. Sem consumidor de `CLREX`/entrada de exceção nesta fatia (B6.3.4
    /// não tem modelo de exceção síncrona/assíncrona para A64 ainda, ver a task) — exposto para uso
    /// futuro quando esse gancho existir.
    void clear(Aarch64Core owner) {
        reservations.remove(owner);
    }

    /// `true` quando HÁ alguma reserva pendente, de qualquer core.
    boolean isArmed() {
        return !reservations.isEmpty();
    }

    /// Endereço marcado na reserva de `core`, ou `-1` quando aberta para ele.
    long address(Aarch64Core core) {
        Reservation reservation = reservations.get(core);
        return reservation == null ? -1L : reservation.address();
    }

    /// Tamanho em bytes marcado na reserva de `core` (0 quando aberta para ele).
    int sizeBytes(Aarch64Core core) {
        Reservation reservation = reservations.get(core);
        return reservation == null ? 0 : reservation.sizeBytes();
    }
}
