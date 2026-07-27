package dev.vitorsilverio.armjitter.jit64;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B6.6.5 (espelho de `JitRuntimeTranslationGenerationTest`, precedente 32-bit B4.1.4): prova que
/// o mesmo VA sob mapeamentos de página VMSA64 DIFERENTES em duas "trocas de processo"
/// (`TranslatingAddressSpace64#setTtbr0` + `invalidateTlbAll`, a técnica real de troca de contexto
/// quando o ASID não muda) executa código diferente a cada troca, sem nunca servir um bloco
/// compilado sob a geração anterior pelo lookup do {@link BlockCache64}.
///
/// Diferente do precedente 32-bit, `jit64/` não tem inline cache (B6.4 D0) — só o ponto de lookup
/// de {@link JitRuntime64#execute} é exercitado aqui (ver Javadoc de {@link BlockKey64}/
/// {@link JitRuntime64}, pendência D3).
///
/// Layout físico (um único `TestAddressSpace` por trás do wrapper), 4 níveis L0-L3 (granule 4KiB,
/// VA 48 bits — geometria de `TranslatingAddressSpace64`), VA escolhido com índice L0=L1=L2=0 e
/// L3=1 para exercitar os 4 níveis reais do page-walk:
/// - Geração A: tabelas em `0x0000_0000`-`0x0000_3FFF` (L0/L1/L2/L3, 4KiB cada), código em
///   `0x0010_0000` (`MOVZ X0, #10`).
/// - Geração B: tabelas em `0x0000_4000`-`0x0000_7FFF`, MESMO VA, código em `0x0020_0000`
///   (`MOVZ X0, #20`).
class JitRuntime64TranslationGenerationTest {
    private static final long VA = 0x1000L; // L0=L1=L2 índice 0, L3 índice 1 (offset de 1 página)

    private static final long GEN_A_L0_BASE = 0x0000_0000L;
    private static final long GEN_A_L1_BASE = 0x0000_1000L;
    private static final long GEN_A_L2_BASE = 0x0000_2000L;
    private static final long GEN_A_L3_BASE = 0x0000_3000L;
    private static final long GEN_A_CODE_BASE = 0x0010_0000L;

    private static final long GEN_B_L0_BASE = 0x0000_4000L;
    private static final long GEN_B_L1_BASE = 0x0000_5000L;
    private static final long GEN_B_L2_BASE = 0x0000_6000L;
    private static final long GEN_B_L3_BASE = 0x0000_7000L;
    private static final long GEN_B_CODE_BASE = 0x0020_0000L;

    private static final int PHYSICAL_SIZE_BYTES = 0x0030_0000; // cobre até a maior GEN_B_CODE_BASE

    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final int MAX_BLOCK_INSTRUCTIONS = 1; // cada bloco é exatamente um MOVZ

    // MOVZ Xd, #imm16 (hw=00): 0xD2800000 | (imm16 << 5) | Rd — mesma família de
    // JitRuntime64Test#MOVZ_X0_1, conferida via aarch64-none-elf-as/objdump em tasks anteriores.
    private static final int MOVZ_X0_10 = 0xD2800000 | (10 << 5); // movz x0, #10
    private static final int MOVZ_X0_20 = 0xD2800000 | (20 << 5); // movz x0, #20

    private static long tableDescriptor(long nextTableBase) {
        return nextTableBase | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long pageDescriptor(long physicalPageBase) {
        return physicalPageBase | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static void installWalk(AddressSpace64 physical, long l0Base, long l1Base, long l2Base, long l3Base,
            long codeBase, int code) {
        physical.write64(l0Base, tableDescriptor(l1Base)); // L0[0] -> L1
        physical.write64(l1Base, tableDescriptor(l2Base)); // L1[0] -> L2
        physical.write64(l2Base, tableDescriptor(l3Base)); // L2[0] -> L3
        physical.write64(l3Base + 1 * 8, pageDescriptor(codeBase)); // L3[1] -> página de código (VA índice 1)
        physical.write32(codeBase, code);
    }

    private static JitRuntime64 newSingleInstructionRuntime() {
        return new JitRuntime64(
                new BlockCache64(),
                new StandardIr64BlockLifter(),
                new Ir64BlockExecutor(),
                new Asm64CodeEmitter(),
                new ExecutionThreshold(1),
                MAX_BLOCK_INSTRUCTIONS);
    }

    @Test
    void trocaDeProcessoNuncaExecutaBlocoStale() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        installWalk(physical, GEN_A_L0_BASE, GEN_A_L1_BASE, GEN_A_L2_BASE, GEN_A_L3_BASE, GEN_A_CODE_BASE,
                MOVZ_X0_10);
        installWalk(physical, GEN_B_L0_BASE, GEN_B_L1_BASE, GEN_B_L2_BASE, GEN_B_L3_BASE, GEN_B_CODE_BASE,
                MOVZ_X0_20);

        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(GEN_A_L0_BASE);
        mmu.invalidateTlbAll();

        Aarch64Core core = new Aarch64Core(mmu);
        JitRuntime64 runtime = newSingleInstructionRuntime();

        // ── Geração A: primeira execução compila e executa o bloco certo. ──
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10L, core.x(0), "geração A deve ler o código mapeado pelas tabelas GEN_A");
        assertEquals(1, runtime.blockCache().size());

        // ── Mesma geração de novo: bloco reaproveitado, sem recompilar. ──
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10L, core.x(0));
        assertEquals(1, runtime.blockCache().size(), "não deve compilar um segundo bloco na mesma geração");

        // ── "Troca de processo": TTBR0 aponta para outra tabela + TLBIALL. Mesmo VA agora resolve
        // para código FISICAMENTE diferente. ──
        mmu.setTtbr0(GEN_B_L0_BASE);
        mmu.invalidateTlbAll();

        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(20L, core.x(0),
                "geração B deve ler o código mapeado pelas tabelas GEN_B — NUNCA o bloco stale da geração A");
        assertEquals(2, runtime.blockCache().size(), "os dois blocos (uma geração cada) coexistem no cache");

        // ── Mesma geração B de novo: sem recompilar. ──
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(20L, core.x(0));
        assertEquals(2, runtime.blockCache().size(), "ainda só dois blocos: nenhuma recompilação espúria");

        // ── Volta para a geração A (troca de processo de novo): geração é um contador GLOBAL
        // monotônico (mesma simplificação "sem per-ASID" do precedente 32-bit) — esta 3ª troca é
        // uma geração NOVA (nunca vista) mesmo apontando para a mesma tabela física de antes:
        // recompila (cache cresce para 3), mas o resultado continua correto. ──
        mmu.setTtbr0(GEN_A_L0_BASE);
        mmu.invalidateTlbAll();

        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10L, core.x(0), "geração A de novo deve voltar a ler o código original, não o da geração B");
        assertEquals(3, runtime.blockCache().size(),
                "geração global nova (mesmo revisitando a tabela A) compila de novo — correto, não stale");
    }

    @Test
    void construtorDeCompatibilidadePreservaGeracaoZero() {
        BlockKey64 key = new BlockKey64(VA);
        assertEquals(VA, key.pc());
        assertEquals(0, key.translationGeneration());
        assertEquals(new BlockKey64(VA, 0), key);
    }
}
