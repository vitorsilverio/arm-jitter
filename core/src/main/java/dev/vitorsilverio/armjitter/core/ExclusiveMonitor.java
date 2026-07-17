package dev.vitorsilverio.armjitter.core;

import java.util.IdentityHashMap;
import java.util.Map;

/// Monitor de exclusividade LDREX/STREX (ARMv6+) compartilhável entre múltiplos {@link ArmCore}
/// (task B5.1, 3DS: os 2 ARM11 do MPCore dividem memória e precisam de uma reserva com visão
/// global, não uma por core). Guarda, POR core, `(endereço, tamanho)` da reserva mais recente
/// desse core — cada core tem sua própria reserva ativa ao mesmo tempo, mas `STREX`/escritas
/// comuns de QUALQUER core resolvem contra as reservas de TODOS.
///
/// Um {@link ArmCore} sem monitor compartilhado explícito ({@link ArmCore#setExclusiveMonitor})
/// usa uma instância própria — o comportamento single-core da task B1.4 fica intacto.
public final class ExclusiveMonitor {
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

    /// Reserva ativa de cada core que já fez `LDREX`, indexada por identidade (não por
    /// `equals`/`hashCode` do core). Ausência de entrada = monitor aberto para aquele core.
    private final Map<ArmCore, Reservation> reservations = new IdentityHashMap<>();

    /// Marca a reserva de `owner` no endereço (sem sinal) e tamanho de um `LDREX{,B,H,D}`,
    /// substituindo a reserva anterior desse core, se houver.
    void markExclusive(ArmCore owner, long address, int sizeBytes) {
        reservations.put(owner, new Reservation(address, sizeBytes));
    }

    /// Consulta um `STREX{,B,H,D}` de `requester`. Exige marcação exata (mesmo endereço e mesmo
    /// tamanho, mesma granularidade single-core da B1.4). QUALQUER reserva — de `requester` ou de
    /// outro core — cujo endereço/tamanho bata exatamente é consumida (aberta), porque no
    /// hardware um STREX perdedor de outro core também derruba a reserva de quem a fez. Só
    /// devolve `true` (sucesso: o chamador deve efetivamente escrever) quando a PRÓPRIA reserva
    /// de `requester` batia.
    boolean consumeIfCovered(ArmCore requester, long address, int sizeBytes) {
        Reservation own = reservations.get(requester);
        boolean requesterOwnedIt = own != null && own.matches(address, sizeBytes);
        reservations.values().removeIf(reservation -> reservation.matches(address, sizeBytes));
        return requesterOwnedIt;
    }

    /// Escrita comum (`STR`/`STRH`/`STRB`, de QUALQUER core) que sobrepõe uma reserva pendente —
    /// de qualquer core — abre essa reserva. Chamado no caminho de store apenas quando
    /// {@link #isArmed()} (hook barato: um `isEmpty` quando não há nenhuma reserva pendente).
    void notifyOrdinaryWrite(long address, int sizeBytes) {
        if (reservations.isEmpty()) {
            return;
        }
        reservations.values().removeIf(reservation -> reservation.overlaps(address, sizeBytes));
    }

    /// Abre a reserva de `owner`: `CLREX`, entrada de exceção ou `STREX` bem-sucedido desse core.
    /// Não afeta a reserva de outros cores compartilhando este monitor.
    void clear(ArmCore owner) {
        reservations.remove(owner);
    }

    /// `true` quando HÁ alguma reserva pendente, de qualquer core.
    boolean isArmed() {
        return !reservations.isEmpty();
    }

    /// Endereço marcado na reserva de `core`, ou `-1` quando aberta para ele. Exposto por
    /// {@link ArmCore#exclusiveMonitorAddress} para o harness de equivalência (`CpuSnapshot`).
    long address(ArmCore core) {
        Reservation reservation = reservations.get(core);
        return reservation == null ? -1L : reservation.address();
    }

    /// Tamanho em bytes marcado na reserva de `core` (0 quando aberta para ele).
    int sizeBytes(ArmCore core) {
        Reservation reservation = reservations.get(core);
        return reservation == null ? 0 : reservation.sizeBytes();
    }
}
