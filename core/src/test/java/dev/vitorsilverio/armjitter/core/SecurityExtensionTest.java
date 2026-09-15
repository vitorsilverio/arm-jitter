package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B15.4 — `SG`/`BXNS`/`BLXNS`/`TT` fim-a-fim (decode + lift + executor interpretado) sobre o
/// preset real `ARMV8M_MAINLINE`, mesmo padrão de {@link NocpTest} (B15.2).
class SecurityExtensionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    private static final int INITIAL_SP = 0x4000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8M_MAINLINE);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setThumbMode(true);
        core.setRegister(ArmCore.SP, INITIAL_SP);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void putHalfword(ArmCore core, int address, int value) {
        ((TestAddressSpace) core.memory()).put16(address, value & 0xFFFF);
    }

    private static void putWord(ArmCore core, int address, int hi, int lo) {
        putHalfword(core, address, hi);
        putHalfword(core, address + 2, lo);
    }

    private static MProfileExceptionModel model(ArmCore core) {
        return (MProfileExceptionModel) core.exceptionModel();
    }

    // ── SG: entra em Secure e limpa bit0 de LR ──────────────────────────────────────────────

    @Test
    void sgSwitchesToSecureAndClearsLrBit0() {
        ArmCore core = newCore();
        MProfileExceptionModel model = model(core);
        core.setRegister(ArmCore.LR, 0x2001); // bit0 setado (Thumb)
        // O modelo já nasce Secure (reset real do ARMv8-M) — este teste cobre a troca Secure->
        // Secure (idempotente) e a limpeza de LR, as duas garantias observáveis de SG aqui;
        // a troca Non-secure->Secure é coberta por bxnsWithFunctionReturnMagicUnwindsBackToSecure.
        putWord(core, CODE_BASE, 0xE97F, 0xE97F); // SG

        core.step();

        assertTrue(model.secure(), "SG deve entrar/permanecer em Secure");
        assertEquals(0x2000, core.register(ArmCore.LR), "SG deve limpar o bit0 de LR");
        assertEquals(CODE_BASE + 4, core.programCounter(), "SG não desvia — só troca de estado");
    }

    // ── BXNS: sem valor mágico, troca de estado por bit0 e desvia ───────────────────────────

    @Test
    void bxnsSwitchesToNonSecureAndBranches() {
        ArmCore core = newCore();
        MProfileExceptionModel model = model(core);
        assertTrue(model.secure(), "reset real do ARMv8-M começa Secure");
        int target = 0x3000; // bit0=0 -> Non-secure
        core.setRegister(1, target);
        putHalfword(core, CODE_BASE, 0x4704 | (1 << 3)); // BXNS r1

        core.step();

        assertFalse(model.secure(), "bit0=0 deve trocar para Non-secure");
        assertEquals(target, core.programCounter());
    }

    @Test
    void bxnsStaysSecureWhenBit0Set() {
        ArmCore core = newCore();
        MProfileExceptionModel model = model(core);
        int target = 0x3001; // bit0=1 -> permanece/entra Secure
        core.setRegister(1, target);
        putHalfword(core, CODE_BASE, 0x4704 | (1 << 3)); // BXNS r1

        core.step();

        assertTrue(model.secure());
        assertEquals(0x3000, core.programCounter());
    }

    // ── BLXNS: bit0=1 é um BLX comum dentro do domínio Secure, sem troca nem empilhamento ───

    @Test
    void blxnsWithBit0SetBehavesLikeOrdinaryBlxWithinSecure() {
        ArmCore core = newCore();
        MProfileExceptionModel model = model(core);
        int spBefore = core.register(ArmCore.SP);
        int target = 0x3001;
        core.setRegister(1, target);
        putHalfword(core, CODE_BASE, 0x4784 | (1 << 3)); // BLXNS r1

        core.step();

        assertTrue(model.secure(), "bit0=1 nunca troca de estado");
        assertEquals(0x3000, core.programCounter());
        assertEquals((CODE_BASE + 2) | 1, core.register(ArmCore.LR));
        assertEquals(spBefore, core.register(ArmCore.SP), "sem empilhamento quando não troca de estado");
    }

    // ── BLXNS: bit0=0 empilha {retorno, exceção} na pilha Secure, grava FNC_RETURN em LR e
    // troca para Non-secure; uma BXNS(LR) subsequente desfaz a chamada ─────────────────────

    @Test
    void blxnsWithBit0ClearPushesFrameAndSwitchesNonSecure() {
        ArmCore core = newCore();
        MProfileExceptionModel model = model(core);
        int spBefore = core.register(ArmCore.SP);
        int target = 0x3000; // bit0=0 -> troca para Non-secure
        core.setRegister(1, target);
        putHalfword(core, CODE_BASE, 0x4784 | (1 << 3)); // BLXNS r1

        core.step();

        assertFalse(model.secure());
        assertEquals(target, core.programCounter());
        assertEquals(0xFEFFFFFF, core.register(ArmCore.LR), "LR deve receber o marcador FNC_RETURN");
        // A troca de segurança carrega o SP ATIVO a partir da sombra Non-secure (0, nunca
        // inicializada neste teste) — o frame fica guardado na sombra Secure, só visível de volta
        // depois que uma BXNS com FNC_RETURN trocar para Secure de novo (ver o teste seguinte).
        assertEquals(spBefore - 8, model.mainStackPointer(), "sombra Secure guarda o frame empilhado");
        assertEquals((CODE_BASE + 2) | 1, core.memory().read32(spBefore - 8),
                "endereço de retorno empilhado na pilha Secure");
    }

    @Test
    void bxnsWithFunctionReturnMagicUnwindsBackToSecure() {
        ArmCore core = newCore();
        MProfileExceptionModel model = model(core);
        int returnTarget = 0x3000;
        core.setRegister(1, returnTarget); // dest Non-secure
        putHalfword(core, CODE_BASE, 0x4784 | (1 << 3)); // BLXNS r1: chama para Non-secure

        core.step(); // agora Non-secure, LR = FNC_RETURN_MAGIC

        assertFalse(model.secure());
        core.setRegister(0, core.register(ArmCore.LR));
        putHalfword(core, core.programCounter(), 0x4704 | (0 << 3)); // BXNS r0 (LR contém o mágico)

        core.step();

        assertTrue(model.secure(), "BXNS com FNC_RETURN deve voltar a Secure");
        assertEquals((CODE_BASE + 2) & ~1, core.programCounter(), "retorna ao endereço empilhado por BLXNS");
    }

    // ── TT/TTT/TTA/TTAT: sem SAU real, sempre devolve 0 (mesma simplificação do QEMU real) ──

    @Test
    void ttAlwaysReturnsZeroWithoutRealSau() {
        ArmCore core = newCore();
        core.setRegister(3, 0xFFFFFFFF); // valor prévio não-zero, garante que o MOV realmente escreveu
        core.setRegister(2, 0x1000); // Rn (irrelevante para o resultado nesta simplificação)
        // TT Rd=r3, Rn=r2: 1110 1000 0100 rn(0010) 1111 rd(0011) 0 0 000000
        putWord(core, CODE_BASE, 0xE842, 0xF300);

        core.step();

        assertEquals(0, core.register(3));
    }

    // ── G3: presets sem M_PROFILE_SECURITY não regridem — o encoding BXNS-shaped não é
    // reconhecido sob ARMV7M_PURE (comportamento anterior à B15.4 preservado byte a byte) ───

    @Test
    void armv7mPureUnaffectedBySecurityExtensionGate() {
        // Sem M_PROFILE_SECURITY o raw BXNS-shaped não é reconhecido como tal (G3) — cai no
        // UNDEFINED controlado de sempre e entra em USAGE_FAULT, exatamente como qualquer encoding
        // Thumb-16 desconhecido faria antes da B15.4 existir; o vetor precisa estar programado
        // (mesmo padrão de {@link NocpTest}) só para o teste poder observar o resultado sem lançar.
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        int usageFaultVector = 4 * MProfileException.USAGE_FAULT.number();
        memory.put32(usageFaultVector, 0x2000 | 1);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV7M_PURE);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        core.cpsr().setThumbMode(true);
        core.setRegister(ArmCore.SP, INITIAL_SP);
        core.setProgramCounter(CODE_BASE);
        putHalfword(core, CODE_BASE, 0x4704 | (1 << 3)); // BXNS-shaped word sob preset sem a feature

        core.step();

        // O estado de segurança nunca muda porque switchSecurityState nunca é chamado sob este
        // preset — mesmo comportamento de antes da B15.4 (o campo nem existia).
        assertTrue(model.secure());
        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
    }
}
