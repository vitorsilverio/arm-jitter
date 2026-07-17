package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// Monitor de exclusividade COMPARTILHADO entre 2 {@link ArmCore} (task B5.1, 3DS: os 2 ARM11
/// do MPCore dividem memória). Espelha o estilo de {@link ArmV6ExclusiveAccessTest}, mas com
/// dois cores sobre o MESMO {@link ExclusiveMonitor} em vez de um só.
class ExclusiveMonitorSharedTest {
    /// `LDREX r1, [r0]` (cond AL, sz=word). Mesma codificação de {@code ArmV6ExclusiveAccessTest}.
    private static final int LDREX_R1_R0 = 0xE190_0F9F | (1 << 12);

    /// `STREX r3, r2, [r0]` (cond AL, sz=word).
    private static final int STREX_R3_R2_R0 = 0xE180_0F90 | (3 << 12) | 2;

    /// `STR r2, [r0]` (cond AL): `1110 01 0 1 1 0 0 0 rn rd 000000000000`.
    private static final int STR_R2_R0 = 0xE580_2000;

    private static ArmCore newCore(AddressSpace sharedMemory, ExclusiveMonitor sharedMonitor) {
        ArmCore core = new ArmCore(sharedMemory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setExclusiveMonitor(sharedMonitor);
        return core;
    }

    private static void run(ArmCore core, int address, int instruction) {
        core.setProgramCounter(address);
        core.memory().write32(address, instruction);
        core.step();
    }

    // ── (a) core A LDREX, core B STREX no mesmo endereço → B falha, A falha depois ──────────

    @Test
    void otherCoreStrexOnSameAddressFailsAndAlsoDropsTheOriginalReservation() {
        TestAddressSpace memory = new TestAddressSpace(64);
        ExclusiveMonitor monitor = new ExclusiveMonitor();
        ArmCore coreA = newCore(memory, monitor);
        ArmCore coreB = newCore(memory, monitor);

        coreA.setRegister(0, 0x10);
        memory.write32(0x10, 0x11111111);
        run(coreA, 0x00, LDREX_R1_R0); // A reserva 0x10

        coreB.setRegister(0, 0x10);
        coreB.setRegister(2, 0xCAFEBABE);
        run(coreB, 0x00, STREX_R3_R2_R0); // B tenta, não é o dono
        assertEquals(1, coreB.register(3), "B nao e o dono da reserva: STREX deve falhar");
        assertEquals(0x11111111, memory.read32(0x10), "memoria intacta apos falha de B");

        coreA.setRegister(2, 0xDEADBEEF);
        run(coreA, 0x04, STREX_R3_R2_R0); // A tenta depois: reserva ja foi consumida por B
        assertEquals(1, coreA.register(3), "reserva de A foi derrubada pela tentativa de B");
        assertEquals(0x11111111, memory.read32(0x10), "memoria continua intacta");
    }

    // ── (b) core A LDREX, core B STR na região → STREX de A falha ───────────────────────────

    @Test
    void otherCoreOrdinaryStoreOverReservedRegionDropsTheReservation() {
        TestAddressSpace memory = new TestAddressSpace(64);
        ExclusiveMonitor monitor = new ExclusiveMonitor();
        ArmCore coreA = newCore(memory, monitor);
        ArmCore coreB = newCore(memory, monitor);

        coreA.setRegister(0, 0x10);
        memory.write32(0x10, 0x11111111);
        run(coreA, 0x00, LDREX_R1_R0); // A reserva 0x10

        coreB.setRegister(0, 0x10);
        coreB.setRegister(2, 0x22222222);
        run(coreB, 0x00, STR_R2_R0); // B escreve normalmente na mesma regiao

        coreA.setRegister(2, 0xCAFEBABE);
        run(coreA, 0x04, STREX_R3_R2_R0);
        assertEquals(1, coreA.register(3), "STR de outro core sobre a regiao derruba a reserva");
        assertEquals(0x22222222, memory.read32(0x10), "escrita comum de B ficou de pe");
    }

    // ── (c) reservas em endereços distintos não interferem ───────────────────────────────────

    @Test
    void reservationsAtDistinctAddressesDoNotInterfere() {
        TestAddressSpace memory = new TestAddressSpace(64);
        ExclusiveMonitor monitor = new ExclusiveMonitor();
        ArmCore coreA = newCore(memory, monitor);
        ArmCore coreB = newCore(memory, monitor);

        coreA.setRegister(0, 0x10);
        memory.write32(0x10, 0x11111111);
        run(coreA, 0x00, LDREX_R1_R0); // A reserva 0x10

        coreB.setRegister(0, 0x20);
        memory.write32(0x20, 0x33333333);
        run(coreB, 0x00, LDREX_R1_R0); // B reserva 0x20 (nao interfere em A)

        coreB.setRegister(2, 0x44444444);
        run(coreB, 0x04, STREX_R3_R2_R0); // B sobre a PROPRIA reserva (0x20)
        assertEquals(0, coreB.register(3), "B e dono de 0x20: STREX deve suceder");
        assertEquals(0x44444444, memory.read32(0x20));

        coreA.setRegister(2, 0x55555555);
        run(coreA, 0x04, STREX_R3_R2_R0); // A ainda dona de 0x10, intocada por B
        assertEquals(0, coreA.register(3), "reserva de A em endereco distinto continua valida");
        assertEquals(0x55555555, memory.read32(0x10));
    }

    // ── (d) regressão: core sozinho com monitor default = comportamento B1.4 intacto ────────

    @Test
    void singleCoreWithDefaultMonitorKeepsB1_4Behavior() {
        TestAddressSpace memory = new TestAddressSpace(64);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setRegister(0, 0x10);
        memory.write32(0x10, 0x11111111);
        core.setRegister(2, 0xCAFEBABE);

        run(core, 0x00, LDREX_R1_R0);
        run(core, 0x04, STREX_R3_R2_R0);

        assertEquals(0, core.register(3), "sem contencao, STREX deve suceder como na B1.4");
        assertEquals(0xCAFEBABE, memory.read32(0x10));
    }
}
