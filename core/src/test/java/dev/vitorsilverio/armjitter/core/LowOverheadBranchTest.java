package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B15.6 — `DLS`/`WLS`/`LE` fim-a-fim (decode + lift + executor interpretado) sobre o preset real
/// `ARMV8_1M`: `LR`(R14) como contador de loop, sem registrador oculto.
class LowOverheadBranchTest {
    private static final int LOOP_COUNTER_REGISTER = 14;
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int hi, int lo) {
        ((TestAddressSpace) core.memory()).put16(address, hi);
        ((TestAddressSpace) core.memory()).put16(address + 2, lo);
    }

    private static void put16(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value);
    }

    @Test
    void wlsSkipsLoopWhenCounterIsZero() {
        ArmCore core = newCore();
        core.setRegister(1, 0);
        // WLS R1, #6 (0x100 -> alvo 0x10A) seguido de um corpo que NÃO deve rodar.
        put32(core, CODE_BASE, 0xF041, 0xC007);

        core.step();

        assertEquals(CODE_BASE + 4 + 6, core.programCounter(), "R1==0 deve pular direto para depois do loop");
        assertEquals(0, core.register(LOOP_COUNTER_REGISTER), "WLS com Rn==0 só desvia: LR fica como estava (trans_WLS do QEMU)");
    }

    @Test
    void wlsFallsThroughWhenCounterIsNonZero() {
        ArmCore core = newCore();
        core.setRegister(1, 3);
        put32(core, CODE_BASE, 0xF041, 0xC007);

        core.step();

        assertEquals(CODE_BASE + 4, core.programCounter(), "R1!=0 continua na próxima instrução (corpo do loop)");
        assertEquals(3, core.register(LOOP_COUNTER_REGISTER));
    }

    @Test
    void dlsNeverBranchesAndAlwaysInitializesCounter() {
        ArmCore core = newCore();
        core.setRegister(2, 5);
        // DLS R2: 0xF040E001 | (2<<16).
        put32(core, CODE_BASE, 0xF042, 0xE001);

        core.step();

        assertEquals(CODE_BASE + 4, core.programCounter(), "DLS nunca desvia");
        assertEquals(5, core.register(LOOP_COUNTER_REGISTER));
    }

    @Test
    void wlsAndLeCloseALoopExactlyNTimes() {
        ArmCore core = newCore();
        int iterations = 3;
        core.setRegister(0, iterations);
        // 0x100: WLS R0, #6  (pula para 0x10A se R0==0)
        put32(core, CODE_BASE, 0xF040, 0xC007);
        // 0x104: ADDS R3, R3, #1  (Thumb-1, corpo do loop, 2 bytes)
        put16(core, CODE_BASE + 4, 0x3301);
        // 0x106: LE #6  (f=0,tp=0; volta para 0x104)
        put32(core, CODE_BASE + 6, 0xF00F, 0xC007);

        // WLS (R0=3 != 0): fall-through para o corpo.
        core.step();
        assertEquals(CODE_BASE + 4, core.programCounter());
        assertEquals(iterations, core.register(LOOP_COUNTER_REGISTER));

        int bodyRuns = 0;
        while (core.programCounter() == CODE_BASE + 4) {
            core.step(); // corpo: ADDS R3,#1
            bodyRuns++;
            core.step(); // LE: decrementa/testa LR e desvia de volta ou sai
        }

        assertEquals(iterations, bodyRuns, "o corpo do loop deve rodar exatamente N vezes");
        assertEquals(iterations, core.register(3), "R3 incrementado uma vez por iteração");
        assertEquals(CODE_BASE + 6 + 4, core.programCounter(), "sai do loop na instrução seguinte ao LE");
        assertEquals(1, core.register(LOOP_COUNTER_REGISTER), "LE não decrementa a última iteração (LR<=1 checado antes)");
    }

    @Test
    void leForeverFormBranchesUnconditionallyWithoutTouchingLr() {
        ArmCore core = newCore();
        core.setRegister(LOOP_COUNTER_REGISTER, 0x1234);
        // LE (f=1) #6, em 0x100 -> volta para 0x100+4-6 = 0xFE.
        put32(core, CODE_BASE, 0xF02F, 0xC007);

        core.step();

        assertEquals(CODE_BASE + 4 - 6, core.programCounter(), "f=1 desvia incondicionalmente");
        assertEquals(0x1234, core.register(LOOP_COUNTER_REGISTER), "f=1 nunca toca LR");
    }

    @Test
    void blInsideLoopOverwritesLoopCounterRegister() {
        // Armadilha 4 da task: LR é compartilhado com o link register comum -- um BL dentro do
        // loop sobrescreve o contador, comportamento arquitetural real, não um bug deste projeto.
        ArmCore core = newCore();
        core.setRegister(2, 7);
        put32(core, CODE_BASE, 0xF042, 0xE001); // DLS R2 -> LR=7
        core.step();
        assertEquals(7, core.register(LOOP_COUNTER_REGISTER));

        core.setRegister(LOOP_COUNTER_REGISTER, 0xDEAD);
        assertEquals(0xDEAD, core.register(LOOP_COUNTER_REGISTER), "nada protege LR de ser sobrescrito");
    }
}
