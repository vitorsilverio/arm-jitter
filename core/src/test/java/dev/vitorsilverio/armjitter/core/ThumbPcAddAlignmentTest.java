package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// t121: THUMB hi-register ADD PC, Rm — alinhamento do PC lido como operando.
///
/// A spec ARM7TDMI define que quando PC é lido como operando em hi-reg ops
/// (ADD PC, Rm), o valor é (instruction_address + 4) SEM mascarar bit1.
///
/// Layout de memória:
///   0x00: MOV r0, #3             ; R0 = 3
///   0x02: ADD PC, r0             ; PC = (0x02+4) + 3 = 0x06+3 = 0x09 → 0x08
///   0x04: B f121                 ; reprova (alvo do ADD com bug &~3)
///   0x06: B f121                 ; reprova (alvo do ADD com bug &~3)
///   0x08: B arithmetic_passed    ; ← alvo correto do ADD PC
///   0x0A: MOV r1, #0             ; f121: reprova
///   0x0C: B fim                  ; (→ 0x12)
///   0x0E: NOP
///   0x10: MOV r1, #1             ; arithmetic_passed: passa
///   0x12: B fim                  ; (→ 0x14, self-loop terminal)
///   0x14: B .                    ; fim: loop infinito — garante término do bloco JIT
///
/// Bug em readSourceRegister: usa (addr + 4) & ~3 em vez de (addr + 4).
/// Com o bug: PC_lido = (0x02+4) & ~3 = 0x04 → resultado = 0x07 → 0x06 (B f121, falha).
/// Correto:   PC_lido =  0x02+4       = 0x06 → resultado = 0x09 → 0x08 (B passed, ok).
class ThumbPcAddAlignmentTest {

    private static TestAddressSpace buildMemory() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0x00, 0x2003); // MOV r0, #3
        memory.put16(0x02, 0x4487); // ADD PC, r0
        memory.put16(0x04, 0xE001); // B f121              (→ 0x0A)
        memory.put16(0x06, 0xE000); // B f121              (→ 0x0A)
        memory.put16(0x08, 0xE002); // B arithmetic_passed (→ 0x10)
        memory.put16(0x0A, 0x2100); // MOV r1, #0          ; f121
        memory.put16(0x0C, 0xE002); // B fim               (→ 0x14)
        memory.put16(0x0E, 0x46C0); // NOP
        memory.put16(0x10, 0x2101); // MOV r1, #1          ; arithmetic_passed
        memory.put16(0x12, 0xE7FF); // B fim               (→ 0x14)
        memory.put16(0x14, 0xE7FE); // B .                 ; fim: terminal, evita leitura além do buffer
        return memory;
    }

    private static ArmCore thumbCore(TestAddressSpace memory) {
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        return core;
    }

    /// Executa via interpretador puro.
    /// ADD PC, r0 deve ler PC = 0x06 (addr+4, sem &~3) e saltar para 0x08.
    @Test
    void addPcAlignmentInterpreter() {
        ArmCore core = thumbCore(buildMemory());

        // MOV r0, #3 → ADD PC, r0 → B passed → MOV r1, #1 → B fim
        core.step(5);

        assertEquals(1, core.register(1),
                "R1 deve ser 1 (arithmetic_passed). " +
                        "Se for 0, o ADD PC leu PC com &~3 (bug: 0x04+3=0x07→0x06=B f121) " +
                        "em vez de sem máscara (correto: 0x06+3=0x09→0x08=B passed).");
    }

    /// Mesmo teste via JIT (InterpretedCodeEmitter).
    /// Verifica que o caminho IR tem o mesmo comportamento correto.
    @Test
    void addPcAlignmentJit() {
        ArmCore core = thumbCore(buildMemory());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(64, 1);

        // threshold=1: cada PC é interpretado na 1ª execução, compilado na 2ª.
        // 10 blocos cobre o caminho completo com folga.
        core.runBlocks(runtime, 10);

        assertEquals(1, core.register(1),
                "R1 deve ser 1 via JIT. " +
                        "Se for 0, o IrBuilder ou CodeEmitter também está mascarando o PC com &~3.");
    }

    /// Verifica o PC diretamente após o ADD, sem depender de branches subsequentes.
    @Test
    void addPcLandsAtCorrectAddress() {
        ArmCore core = thumbCore(buildMemory());

        core.step(1); // MOV r0, #3
        assertEquals(3, core.register(0));

        core.step(1); // ADD PC, r0

        // ADD PC, r0 @ 0x02: PC_read = 0x02+4 = 0x06, result = 0x06+3 = 0x09, new_PC = 0x08
        // Com bug (&~3):      PC_read = (0x02+4)&~3 = 0x04, result = 0x07, new_PC = 0x06
        int pc = core.programCounter();
        assertEquals(0x08, pc,
                String.format("Esperado PC=0x08, obtido PC=0x%02X. " +
                        "Bug: readSourceRegister usa (addr+4)&~3=0x04 em vez de addr+4=0x06.", pc));
    }
}