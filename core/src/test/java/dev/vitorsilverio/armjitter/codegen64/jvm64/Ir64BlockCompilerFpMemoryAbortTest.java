package dev.vitorsilverio.armjitter.codegen64.jvm64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.codegen.jvm.Jvm64BlockLoader;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// C12.5, Armadilha 1: a op FP/SIMD emitida nativamente TEM que ficar DENTRO do range `try` do
/// {@link Ir64BlockCompiler} e gravar `LOCAL_FAULT_PC` antes — a mesma classe de bug que a **E7**
/// corrigiu para o lado inteiro (`Ir64BlockCompilerMemoryAbortTest`, F11). Este teste prova o
/// equivalente para `FpLoad64` (o primeiro dos 7 `Kind` da C12.5): um bloco de DUAS instruções
/// (`MOVZ` nativo, sem falta; `LDR D<t>,[X<n>]` nativo, com falta) sobre uma
/// {@link TranslatingAddressSpace64} identity-mapped SÓ na página de código — o alvo do `LDR`
/// fica DELIBERADAMENTE fora do mapeamento, então só a op FP falta, nunca o `Fetch`.
class Ir64BlockCompilerFpMemoryAbortTest {
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final int AP_SHIFT = 6;
    private static final int AP_FULL_ACCESS = 0b01;
    private static final long OUTPUT_ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L;

    private static final long L0_TABLE_BASE = 0x1000L;
    private static final long L1_TABLE_BASE = 0x2000L;
    private static final long L2_TABLE_BASE = 0x3000L;
    private static final long L3_TABLE_BASE = 0x4000L;
    private static final int PHYSICAL_SIZE_BYTES = 0x6000;

    private static final long HANDLER_PHYSICAL_ADDRESS = 0x400L;
    private static final long ESR_EC_SHIFT = 26;
    private static final long ESR_EC_DATA_ABORT_LOWER_EL = 0x24L;

    /// `movz x1, #0x1000, lsl #32` — mesmo valor sentinela de `Ir64BlockCompilerMemoryAbortTest`
    /// (`1L << 44`, L0 index 32: sem descritor, garante falta fora do mapeamento identity da
    /// página de código em `0x0`).
    private static final long FAULTING_VA = 1L << 44;
    private static final int MOVZ_IMMEDIATE16 = 0x1000;
    private static final int MOVZ_SHIFT = 32;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long identityPageDescriptor() {
        return ((long) AP_FULL_ACCESS << AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    /// Identity-map só a página que contém `0x0`-`0x8` (as 2 instruções do bloco) — `FAULTING_VA`
    /// (`1L << 44`) cai num índice L0 diferente, sem descritor (sempre falta).
    private static TranslatingAddressSpace64 identityMappedCodePageMmu(AddressSpace64 physical) {
        physical.write64(L0_TABLE_BASE, tableDescriptor(L1_TABLE_BASE));
        physical.write64(L1_TABLE_BASE, tableDescriptor(L2_TABLE_BASE));
        physical.write64(L2_TABLE_BASE, tableDescriptor(L3_TABLE_BASE));
        physical.write64(L3_TABLE_BASE, identityPageDescriptor());
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(L0_TABLE_BASE);
        return mmu;
    }

    @Test
    void nativeFpLoadFaultEntersGuestAbortHandlerWithPartialCyclesAndCorrectFaultPc() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        physical.write32(0x400, 0xd69f_03e0); // eret (handler mínimo, endereço do vetor síncrono)
        TranslatingAddressSpace64 mmu = identityMappedCodePageMmu(physical);
        Aarch64Core core = new Aarch64Core(mmu);

        Ir64Block.Builder builder = Ir64Block.builder(0L);
        builder.add(new Ir64Op.Fetch(0x0L, 4));
        builder.add(new Ir64Op.Cycle(2));
        builder.add(new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, MOVZ_IMMEDIATE16, MOVZ_SHIFT, true));
        builder.add(new Ir64Op.Fetch(0x4L, 4));
        builder.add(new Ir64Op.Cycle(3));
        builder.add(new Ir64Op.FpLoad64(0, 1, Ir64FpMemSize.DOUBLE,
                Ir64AddressingMode.OFFSET, 0L, -1, null, 0));
        builder.endPc(0x8L);
        Ir64Block block = builder.sealed();

        assertTrue(Ir64NativePolicy.supports(block),
                "MOVZ + FpLoad64 (LDR D) devem ser nativamente suportados após a C12.5");

        byte[] bytecode = new Ir64BlockCompiler().compile(
                "dev/vitorsilverio/armjitter/codegen/generated/Ir64BlockCompilerFpMemoryAbortTestBlock", block);
        CompiledBlock64 compiled = new Jvm64BlockLoader().load(bytecode,
                "dev/vitorsilverio/armjitter/codegen/generated/Ir64BlockCompilerFpMemoryAbortTestBlock");

        int cycles = compiled.execute(core);

        assertEquals(FAULTING_VA, core.x(1), "MOVZ deve ter executado antes da falta do LDR D");
        // G4: Cycle/Fetch nunca ganham guard condicional — o Cycle(3) da PRÓPRIA instrução
        // faltosa já foi somado ANTES do LDR faltar (ordem [Fetch, Cycle, op] do lifter).
        assertEquals(5, cycles, "ciclos PARCIAIS: 2 (MOVZ) + 3 (Cycle do LDR, incondicional) — nunca o bloco inteiro");
        assertTrue(core.exceptionState().inEl1(), "abort de memória deve entrar em EL1");
        assertEquals(HANDLER_PHYSICAL_ADDRESS, core.pc(),
                "PC deve saltar para VBAR_EL1(0) + offset síncrono de nível inferior (0x400)");
        assertEquals(4L, core.exceptionState().elr1(),
                "ELR_EL1 deve ser o endereço da PRÓPRIA LDR D faltosa (LOCAL_FAULT_PC), não o do MOVZ nem do bloco");
        assertEquals(FAULTING_VA, core.exceptionState().far1(), "FAR_EL1 deve ser o VA faltoso lido de X1");
        long ec = core.exceptionState().esr1() >>> ESR_EC_SHIFT;
        assertEquals(ESR_EC_DATA_ABORT_LOWER_EL, ec, "ESR_EL1.EC deve ser Data Abort de EL inferior (0x24)");
    }
}
