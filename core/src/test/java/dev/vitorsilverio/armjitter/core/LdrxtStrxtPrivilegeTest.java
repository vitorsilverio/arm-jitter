package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.mmu.DomainAccess;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B9.9 (`LDRxT`/`STRxT`): prova ponta a ponta — decoder → `IrOp.Load`/`Store#unprivileged()` →
/// `IrMemoryExecutor` → `TranslatingAddressSpace#withUnprivilegedAccess` — de que um `STRT`
/// executado em modo PRIVILEGIADO sobre uma página `AP_USER_READ_ONLY` sofre a MESMA falta de
/// permissão que um `STR` comum sofreria em modo `USER`, enquanto o `STR` comum (sem o sufixo `T`)
/// continua tendo sucesso no mesmo modo privilegiado. Ver `TranslatingAddressSpaceTest` (B4.1.1)
/// para a permissão AP em si; este teste só liga a ponta que faltava (instrução real → efeito real).
class LdrxtStrxtPrivilegeTest {
    private static final int TYPE_COARSE_PAGE_TABLE = 0b01;
    private static final int TYPE_SECTION = 0b10;
    private static final int TYPE_SMALL_PAGE = 0b10;
    private static final int AP_USER_READ_ONLY = 0b10;
    private static final int AP_FULL_ACCESS = 0b11;
    private static final int L1_BASE = 0x0000_0000;
    private static final int L2_BASE = 0x0000_5000;
    private static final int CODE_VA = 0x0010_0000; // seção identidade, full access (fetch de instrução)
    private static final int DATA_VA = 0x0040_0000; // página pequena, AP_USER_READ_ONLY

    /// `STRT r0,[r1],#4` (ARM DDI 0406C A8.8.207): `I=0,P=0,U=1,B=0,W=1,L=0`.
    private static final int STRT_R0_R1_POST4 = 0xE4A1_0004;
    /// `STR r0,[r1],#4` (sem o sufixo `T`, mesmo modo de endereçamento): `I=0,P=0,U=1,B=0,W=0,L=0`.
    private static final int STR_R0_R1_POST4 = 0xE481_0004;

    private static int l1IndexOf(int va) {
        return va >>> 20;
    }

    private static TranslatingAddressSpace newMmuWithCodeAndUserReadOnlyData() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        // Código: seção identidade em CODE_VA, domínio 0, full access — fetch de instrução livre.
        physical.put32(L1_BASE + l1IndexOf(CODE_VA) * 4,
                (CODE_VA & 0xFFF0_0000) | (AP_FULL_ACCESS << 10) | TYPE_SECTION);
        // Dados: tabela L2 coarse cobrindo DATA_VA, domínio 0.
        physical.put32(L1_BASE + l1IndexOf(DATA_VA) * 4, (L2_BASE & 0xFFFF_FC00) | TYPE_COARSE_PAGE_TABLE);
        // L2[0]: página pequena em PA = DATA_VA (identidade), AP_USER_READ_ONLY.
        physical.put32(L2_BASE, (DATA_VA & 0xFFFF_F000) | (AP_USER_READ_ONLY << 4) | TYPE_SMALL_PAGE);

        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);
        mmu.setTtbr0(L1_BASE);
        mmu.setDacr(DomainAccess.CLIENT.bits());
        mmu.setPrivileged(true); // core inicia em SVC (privilegiado) — sem CP15, sincronizado à mão
        return mmu;
    }

    @Test
    void strtInPrivilegedModeFailsAsUserOnUserReadOnlyPageUnderStep() {
        TranslatingAddressSpace mmu = newMmuWithCodeAndUserReadOnlyData();
        mmu.write32(CODE_VA, STRT_R0_R1_POST4);
        ArmCore core = new ArmCore(mmu, SwiDispatcher.empty());
        core.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
        core.setProgramCounter(CODE_VA);
        core.setRegister(0, 0xDEAD_BEEF);
        core.setRegister(1, DATA_VA);

        core.step();

        assertEquals(CpuMode.ABORT, core.mode(), "STRT privilegiado deve abortar como se fosse USER");
        assertEquals(0, mmu.read32(DATA_VA), "a escrita negada não pode ter tocado a memória");
    }

    @Test
    void plainStrInPrivilegedModeSucceedsOnTheSameUserReadOnlyPageUnderStep() {
        TranslatingAddressSpace mmu = newMmuWithCodeAndUserReadOnlyData();
        mmu.write32(CODE_VA, STR_R0_R1_POST4);
        ArmCore core = new ArmCore(mmu, SwiDispatcher.empty());
        core.setProgramCounter(CODE_VA);
        core.setRegister(0, 0xDEAD_BEEF);
        core.setRegister(1, DATA_VA);

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode(), "STR comum não deve abortar em modo privilegiado");
        assertEquals(0xDEAD_BEEF, mmu.read32(DATA_VA));
        assertEquals(DATA_VA + 4, core.register(1));
    }

    @Test
    void strtInPrivilegedModeFailsUnderCompiledJitBlockToo() {
        // O bloco compilado cai no interpretado por-op para IrOp.Load/Store#unprivileged() (ver
        // AsmNativePolicy) — prova que o fallback realmente acontece, não só o caminho step().
        TranslatingAddressSpace mmu = newMmuWithCodeAndUserReadOnlyData();
        mmu.write32(CODE_VA, STRT_R0_R1_POST4);
        ArmCore core = new ArmCore(mmu, SwiDispatcher.empty());
        core.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
        core.setProgramCounter(CODE_VA);
        core.setRegister(0, 0xDEAD_BEEF);
        core.setRegister(1, DATA_VA);
        JitRuntime compiled = JitRuntimeFactory.armThumb(16, 1);

        core.runBlock(compiled);

        assertEquals(CpuMode.ABORT, core.mode());
        assertEquals(0, mmu.read32(DATA_VA));
    }
}
