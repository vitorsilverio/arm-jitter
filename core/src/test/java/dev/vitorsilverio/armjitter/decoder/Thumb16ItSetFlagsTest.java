package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/// Regressão: instruções Thumb de 16 bits de processamento de dados (encodings T1/T2 com
/// `setflags = !InITBlock()`) NÃO atualizam flags dentro de um IT block, mas atualizam fora dele.
/// `CMP`/`CMN`/`TST` de 16 bits atualizam SEMPRE. Verificado no interpretador (`step()`), no
/// runtime interpretado por blocos e nos backends JVM/ASM.
class Thumb16ItSetFlagsTest {
    private static final ArmArchitecture ARCH = ArmArchitecture.ARMV7A;
    private static final int IT_AL = 0xBF00 | (0xE << 4) | 0b1000; // IT AL (1 instrução)
    private static final int IT_STATE_AL = (0xE << 4) | 0b1000;
    private static final int B_SELF = 0xE7FE;
    private static final int NZCV_INICIAL = 0b1011; // N=1 Z=0 C=1 V=1

    /// @param rd registrador de destino conferido (-1 = nenhum, CMP/CMN/TST)
    private record Case(String name, int opcode, int r0, int r1, int r2, int rd, int expected, boolean alwaysFlags) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Case c(String name, int opcode, int r0, int r1, int r2, int rd, int expected) {
        return new Case(name, opcode, r0, r1, r2, rd, expected, false);
    }

    private static Case f(String name, int opcode, int r0, int r1) {
        return new Case(name, opcode, r0, r1, 0, -1, 0, true);
    }

    private static int dp(int op) { // dp op, rd=r0, rs=r1
        return 0x4000 | (op << 6) | (1 << 3);
    }

    private static final List<Case> CASES = List.of(
            c("movs imm8", 0x2000, 0, 0, 0, 0, 0),
            c("adds imm8", 0x3000, 0, 0, 0, 0, 0),
            c("subs imm8", 0x3805, 5, 0, 0, 0, 0),
            c("lsrs imm5", 0x0848, 0, 1, 0, 0, 0),
            c("lsls imm5", 0x0048, 0, 0x80000000, 0, 0, 0),
            c("asrs imm5", 0x1048, 0, 1, 0, 0, 0),
            c("adds reg", 0x1888, 0, 1, -1, 0, 0),
            c("subs reg", 0x1A88, 0, 3, 3, 0, 0),
            c("adds imm3", 0x1C08, 9, 0, 0, 0, 0),
            c("subs imm3", 0x1EC8, 0, 3, 0, 0, 0),
            c("ands", dp(0x0), 0xF0, 0x0F, 0, 0, 0),
            c("eors", dp(0x1), 5, 5, 0, 0, 0),
            c("lsls reg", dp(0x2), 1, 32, 0, 0, 0),
            c("lsrs reg", dp(0x3), 1, 1, 0, 0, 0),
            c("asrs reg", dp(0x4), 1, 1, 0, 0, 0),
            c("adcs", dp(0x5), 0, 0, 0, 0, 1), // C inicial = 1
            c("sbcs", dp(0x6), 5, 4, 0, 0, 1),
            c("rors", dp(0x7), 0, 1, 0, 0, 0),
            c("rsbs (neg)", dp(0x9), 0, 1, 0, 0, -1),
            c("orrs", dp(0xC), 0, 0, 0, 0, 0),
            c("muls", dp(0xD), 0, 7, 0, 0, 0),
            c("bics", dp(0xE), 0xF, 0xF, 0, 0, 0),
            c("mvns", dp(0xF), 0, -1, 0, 0, 0),
            f("tst", dp(0x8), 5, 2),
            f("cmp reg", dp(0xA), 5, 5),
            f("cmn reg", dp(0xB), 1, -1),
            f("cmp imm8", 0x2800, 0, 0));

    private static ArmCore newCore(TestAddressSpace memory, int pc, int itState, Case t) {
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ARCH);
        core.cpsr().setThumbMode(true);
        core.cpsr().setNzcv(true, false, true, true);
        core.cpsr().setItState(itState);
        core.setRegister(0, t.r0());
        core.setRegister(1, t.r1());
        core.setRegister(2, t.r2());
        core.setRegister(15, pc);
        return core;
    }

    private static int nzcv(ArmCore core) {
        return (core.cpsr().negative() ? 8 : 0) | (core.cpsr().zero() ? 4 : 0)
                | (core.cpsr().carry() ? 2 : 0) | (core.cpsr().overflow() ? 1 : 0);
    }

    private static TestAddressSpace program(boolean inIt, Case t) {
        TestAddressSpace memory = new TestAddressSpace(64);
        int a = 0;
        if (inIt) {
            memory.put16(a, IT_AL);
            a += 2;
        }
        memory.put16(a, t.opcode());
        memory.put16(a + 2, B_SELF);
        return memory;
    }

    private interface Runner {
        void run(ArmCore core, boolean inIt);
    }

    private static void stepper(ArmCore core, boolean inIt) {
        core.step();
        if (inIt) {
            core.step();
        }
    }

    private static Runner block(Supplier<JitRuntime> runtime) {
        return (core, inIt) -> core.runBlocks(runtime.get(), 1);
    }

    private void verify(Runner runner) {
        for (Case t : CASES) {
            ArmCore outside = newCore(program(false, t), 0, 0, t);
            runner.run(outside, false);
            ArmCore inside = newCore(program(true, t), 0, 0, t);
            runner.run(inside, true);

            if (t.alwaysFlags()) {
                assertNotEquals(NZCV_INICIAL, nzcv(outside), t + " fora de IT deve atualizar flags");
                assertEquals(nzcv(outside), nzcv(inside), t + " dentro de IT deve atualizar flags igual a fora");
            } else {
                assertEquals(t.expected(), outside.register(t.rd()), t + " resultado fora de IT");
                assertEquals(t.expected(), inside.register(t.rd()), t + " resultado dentro de IT");
                assertNotEquals(NZCV_INICIAL, nzcv(outside), t + " fora de IT deve atualizar flags");
                assertEquals(NZCV_INICIAL, nzcv(inside), t + " dentro de IT NAO pode alterar flags");
            }
        }
    }

    @Test
    void interpretedStep() {
        verify(Thumb16ItSetFlagsTest::stepper);
    }

    @Test
    void interpretedBlocks() {
        verify(block(() -> JitRuntimeFactory.interpretedThumb(16, 1, ARCH)));
    }

    @Test
    void jvmBackend() {
        verify(block(() -> JitRuntimeFactory.jvmArmThumb(16, 1, ARCH)));
    }

    @Test
    void asmPerOpBackend() {
        verify(block(() -> JitRuntimeFactory.armThumb(16, 1, ARCH)));
    }

    /// O caso reportado: `moveq` dentro de `ite eq` não pode apagar Z; `movne` não executa.
    @Test
    void movsInsideIteDoesNotClobberZ() {
        int[] words = {0x2000, 0x2800, 0xBF0C, 0x2301, 0x2302};
        for (int mode = 0; mode < 2; mode++) {
            TestAddressSpace memory = new TestAddressSpace(64);
            for (int i = 0; i < words.length; i++) {
                memory.put16(i * 2, words[i]);
            }
            memory.put16(words.length * 2, B_SELF);
            ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ARCH);
            core.cpsr().setThumbMode(true);
            if (mode == 0) {
                for (int i = 0; i < 5; i++) {
                    core.step();
                }
            } else {
                core.runBlocks(JitRuntimeFactory.jvmArmThumb(16, 1, ARCH), 1);
            }
            assertEquals(1, core.register(3), "modo " + mode);
            assertEquals(true, core.cpsr().zero(), "modo " + mode);
        }
    }

    /// A mesma palavra no MESMO runtime fora e dentro de IT (cache de blocos por `itState`):
    /// entrada no meio de um IT block não pode reutilizar o bloco com `setFlags`.
    @Test
    void sameRuntimeSamePcWithAndWithoutItState() {
        Case t = CASES.get(0); // movs r0,#0
        TestAddressSpace memory = program(true, t);
        JitRuntime rt = JitRuntimeFactory.jvmArmThumb(16, 1, ARCH);

        ArmCore outside = newCore(memory, 2, 0, t);
        outside.runBlocks(rt, 1);
        assertNotEquals(NZCV_INICIAL, nzcv(outside));

        ArmCore inside = newCore(memory, 2, IT_STATE_AL, t);
        inside.runBlocks(rt, 1);
        assertEquals(NZCV_INICIAL, nzcv(inside));
    }
}
