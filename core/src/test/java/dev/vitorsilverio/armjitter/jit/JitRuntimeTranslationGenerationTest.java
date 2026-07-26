package dev.vitorsilverio.armjitter.jit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.memory.mmu.DomainAccess;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B4.1.4 (RFC-SOFTMMU §5): `translationGeneration` no `BlockKey`/`JitRuntime` — prova que o
/// mesmo VA sob mapeamentos de página DIFERENTES em duas "trocas de processo" (`TTBR0` +
/// `TLBIALL`, a técnica real de troca de contexto quando o ASID não muda) executa código
/// diferente a cada troca, sem nunca servir um bloco compilado da geração anterior (nem pelo
/// {@link JitRuntime.BlockCache lookup} nem pelo inline cache de dispatch).
///
/// Layout físico (um único `TestAddressSpace` por trás do wrapper):
/// - `0x0000_0000`: tabela L1 da "geração A" (16KiB, alinhada) — VA `0x0010_0000` -> PA `0x0020_0000`.
/// - `0x0000_4000`: tabela L1 da "geração B" (16KiB, alinhada) — VA `0x0010_0000` -> PA `0x0030_0000`.
/// - `0x0020_0000`: código da geração A: `MOV R0, #10`.
/// - `0x0030_0000`: código da geração B: `MOV R0, #20`.
///
/// O runtime usa `maxBlockInstructions=1` para que cada bloco seja exatamente a instrução `MOV`
/// (sem precisar de instrução terminal/exceção): o teste evita ruído de vetores de exceção e
/// troca de modo, focando só na identidade bloco↔geração.
class JitRuntimeTranslationGenerationTest {
    private static final int VA = 0x0010_0000;
    private static final int TABLE_A_BASE = 0x0000_0000;
    private static final int TABLE_B_BASE = 0x0000_4000; // alinhado a 16KiB (próxima tabela L1)
    private static final int CODE_A_BASE = 0x0020_0000;
    private static final int CODE_B_BASE = 0x0030_0000;

    private static final int TYPE_SECTION = 0b10;
    private static final int AP_FULL_ACCESS = 0b11;
    private static final int DOMAIN = 0;

    private static final int MOV_R0_10 = 0xE3A0_000A; // MOV R0, #10
    private static final int MOV_R0_20 = 0xE3A0_0014; // MOV R0, #20

    private static int sectionDescriptor(int physicalBase) {
        return (physicalBase & 0xFFF0_0000) | (AP_FULL_ACCESS << 10) | (DOMAIN << 5) | TYPE_SECTION;
    }

    private static int l1IndexOf(int va) {
        return va >>> 20;
    }

    private static JitRuntime newSingleInstructionRuntime() {
        return new JitRuntime(
                new BlockCache(16),
                new ArmDecoder(ArmArchitecture.ARMV4T),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                new InterpretedCodeEmitter(ArmArchitecture.ARMV4T),
                new ExecutionThreshold(1),
                1);
    }

    @Test
    void trocaDeProcessoNuncaExecutaBlocoStale() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        // Tabela L1 da geração A: VA -> PA(código A).
        physical.put32(TABLE_A_BASE + l1IndexOf(VA) * 4, sectionDescriptor(CODE_A_BASE));
        physical.put32(CODE_A_BASE, MOV_R0_10);
        // Tabela L1 da geração B: MESMO VA -> PA(código B) diferente.
        physical.put32(TABLE_B_BASE + l1IndexOf(VA) * 4, sectionDescriptor(CODE_B_BASE));
        physical.put32(CODE_B_BASE, MOV_R0_20);

        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);
        mmu.setDacr(DomainAccess.CLIENT.bits() << (DOMAIN * 2));
        mmu.setPrivileged(true);
        mmu.setTtbr0(TABLE_A_BASE);
        mmu.invalidateTlbAll();

        ArmCore core = new ArmCore(mmu, SwiDispatcher.empty());
        JitRuntime runtime = newSingleInstructionRuntime();

        // ── Geração A: primeira execução compila o bloco (miss + IC preenchido). ──
        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10, core.register(0), "geração A deve ler o código mapeado por TABLE_A_BASE");
        assertEquals(1, runtime.blockCache().size());
        assertEquals(1, runtime.icMisses);

        // ── Mesma geração de novo: deve ser IC HIT (bloco reaproveitado, sem novo lift). ──
        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10, core.register(0));
        assertEquals(1, runtime.blockCache().size(), "não deve compilar um segundo bloco na mesma geração");
        assertEquals(1, runtime.icHits, "segunda execução no mesmo VA/geração deve acertar o inline cache");

        // ── "Troca de processo": TTBR0 aponta para outra tabela + TLBIALL (RFC §5: as três
        // operações que bumpam a geração). Mesmo VA agora resolve para código FISICAMENTE
        // diferente. ──
        mmu.setTtbr0(TABLE_B_BASE);
        mmu.invalidateTlbAll();

        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(20, core.register(0),
                "geração B deve ler o código mapeado por TABLE_B_BASE — NUNCA o bloco stale da geração A");
        assertEquals(2, runtime.blockCache().size(), "os dois blocos (uma geração cada) coexistem no cache");

        // ── Mesma geração B de novo: volta a acertar o IC (que foi esvaziado na troca, mas
        // repovoado com o bloco correto da geração B). ──
        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(20, core.register(0));
        assertEquals(2, runtime.blockCache().size(), "ainda só dois blocos: nenhuma recompilação espúria");

        // ── Volta para a geração A (troca de processo de novo): a geração é um contador GLOBAL
        // monotônico (RFC-SOFTMMU §5 — "não ASID por bloco"; cache per-ASID fica como otimização
        // futura medida), então esta 3ª troca é uma geração NOVA (nunca vista) mesmo apontando
        // para a mesma tabela de página física de antes — recompila (cache cresce para 3), mas o
        // resultado continua correto: nunca serve o bloco da geração B. ──
        mmu.setTtbr0(TABLE_A_BASE);
        mmu.invalidateTlbAll();

        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10, core.register(0), "geração A de novo deve voltar a ler o código original, não o da geração B");
        assertEquals(3, runtime.blockCache().size(),
                "geração global nova (mesmo revisitando a tabela A) compila de novo — correto, não stale");
    }
}
