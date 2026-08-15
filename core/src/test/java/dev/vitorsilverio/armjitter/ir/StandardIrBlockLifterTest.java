package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;
import dev.vitorsilverio.armjitter.support.FaultingAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandardIrBlockLifterTest {
    @Test
    void liftsLinearBlockUntilBranch() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        memory.put32(8, 0xEA00_0000);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(memory, 0, 8);

        assertEquals(12, block.endPc());
        assertEquals(9, block.operations().size());
        assertInstanceOf(IrOp.Alu.class, block.operations().get(0));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(1));
        assertFetch(block.operations().get(2), 0, 4);
        assertInstanceOf(IrOp.Alu.class, block.operations().get(3));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(4));
        assertFetch(block.operations().get(5), 4, 4);
        assertInstanceOf(IrOp.Branch.class, block.operations().get(6));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(7));
        assertFetch(block.operations().get(8), 8, 4);
    }

    /// B4.1.5 (achado real do boot do Linux no `linuxbox`): a leitura ADIANTADA do lifter pode
    /// tocar uma página não mapeada que a CPU talvez nunca execute. Nesse caso o bloco só TERMINA
    /// — mesmo tratamento que `IndexOutOfBoundsException` (barramento sem MMU) sempre teve. Sem
    /// isto o host recebe um PREFETCH_ABORT no PC de INÍCIO do bloco, "conserta" o endereço errado
    /// e volta para o mesmo lugar: o boot do kernel travava aí, e só no runtime tiered (o caminho
    /// não-tiered interpreta instrução a instrução e nunca lê adiante).
    @Test
    void endsBlockWhenReadAheadFaultsInTranslation() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001); // MOV r0,#1
        memory.put32(4, 0xE280_0002); // ADD r0,r0,#2
        FaultingAddressSpace faulting = new FaultingAddressSpace(memory);
        faulting.faultOn(8, MemoryAccessType.INSTRUCTION_FETCH);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(faulting, 0, 8);

        assertEquals(8, block.endPc(), "o bloco termina onde a tradução falhou");
        assertEquals(6, block.operations().size());
    }

    /// A contrapartida: se a falta é da PRIMEIRA instrução (bloco ainda vazio), ela É a instrução
    /// que a CPU vai executar agora — propagar é o correto, para virar o abort real.
    @Test
    void propagatesTranslationFaultOfFirstInstruction() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        FaultingAddressSpace faulting = new FaultingAddressSpace(memory);
        faulting.faultOn(0, MemoryAccessType.INSTRUCTION_FETCH);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        assertThrows(MemoryTranslationException.class, () -> lifter.lift(faulting, 0, 8));
    }

    @Test
    void respectsMaxInstructionLimit() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(memory, 0, 1);

        assertEquals(4, block.endPc());
        assertEquals(3, block.operations().size());
    }

    @Test
    void stopsBlockAtLoadIntoPc() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE590_F000);
        memory.put32(4, 0xE3A0_0001);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(memory, 0, 4);

        assertEquals(4, block.endPc());
        assertEquals(3, block.operations().size());
        assertInstanceOf(IrOp.Load.class, block.operations().get(0));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(1));
        assertFetch(block.operations().get(2), 0, 4);
    }

    private void assertFetch(IrOp op, int address, int sizeBytes) {
        IrOp.Fetch fetch = assertInstanceOf(IrOp.Fetch.class, op);
        assertEquals(address, fetch.address());
        assertEquals(sizeBytes, fetch.sizeBytes());
    }
}
