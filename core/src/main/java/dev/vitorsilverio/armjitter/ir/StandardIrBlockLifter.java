package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.ItState;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Objects;

/// Lifter linear de blocos usando um decoder e um builder de IR.
public final class StandardIrBlockLifter implements IrBlockLifter {
    private final InstructionDecoder decoder;
    private final IrBuilder builder;

    /// Cria um lifter com o decoder e builder informados.
    public StandardIrBlockLifter(InstructionDecoder decoder, IrBuilder builder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /// Decodifica e eleva um bloco linear a partir de `startPc`.
    ///
    /// Estado local do Thumb-2 IT block (B2.4, D1): `itStateAtEntry` semeia um par lógico
    /// (condição corrente, ITSTATE) threaded PURAMENTE localmente por este laço — não é campo
    /// mutável do decoder (que continua stateless) nem sobrevive além desta chamada. Para cada
    /// instrução coberta por um IT block ativo (`currentItState != 0`), a condição decodificada é
    /// SOBREPOSTA pela condição corrente do ITSTATE ({@link DecodedInstruction#withCondition}), e
    /// o lifter emite uma {@link IrOp.SetItState} incondicional logo depois, avançando o ITSTATE
    /// (via {@link ItState#advance}) — espelha o `ITAdvance()` do hardware real, que roda depois de
    /// TODA instrução coberta independente de o guard condicional ter passado.
    ///
    /// Exceção: o par `BL`/`BLX` legado (`LONG_BRANCH_PREFIX`+`LONG_BRANCH_SUFFIX`, dois halfwords)
    /// é UMA instrução Thumb arquitetural, mas ocupa DUAS iterações deste laço — o avanço do
    /// ITSTATE só acontece depois do SUFIXO (que consome o slot), não depois do prefixo (que
    /// herda a mesma condição do slot e não avança nada sozinho).
    @Override
    public IrBlock lift(AddressSpace memory, int startPc, int maxInstructions, int itStateAtEntry) {
        if (maxInstructions <= 0) {
            throw new IllegalArgumentException("maxInstructions must be positive");
        }

        IrBlock.Builder block = IrBlock.builder(startPc);
        int pc = startPc;
        int currentItState = itStateAtEntry;
        for (int i = 0; i < maxInstructions; i++) {
            DecodedInstruction instruction;
            try {
                instruction = decoder.decode(memory, pc);
            } catch (IndexOutOfBoundsException exception) {
                if (!block.isEmpty()) {
                    break;
                }
                throw exception;
            }
            if (instruction.kind() == InstructionKind.UNIMPLEMENTED && !block.isEmpty()) {
                break;
            }

            // Aplicado uniformemente a TODA instrução dentro de um IT block ativo — incluindo um
            // `IT` aninhado (CONSTRAINED UNPREDICTABLE per ARM ARM; decisão desta task: "continuar
            // normalmente" em vez de UNDEFINED, ver Armadilhas de b2.4-thumb2-branches-it.md). Um
            // `IT` aninhado herda a condição do bloco externo como qualquer outra instrução — se o
            // guard falhar em tempo de execução, `executeSetItState` simplesmente não abre o novo
            // IT block, mesmo mecanismo genérico de guard usado em toda a IR.
            boolean governedByIt = ItState.inProgress(currentItState);
            if (governedByIt) {
                instruction = instruction.withCondition(ItState.condition(currentItState));
            }

            builder.lift(instruction, block);

            if (instruction.kind() == InstructionKind.IT) {
                // A própria instrução IT semeia o ITSTATE para a(s) instrução(ões) seguinte(s) —
                // `immediate()` já carrega o ITSTATE de entrada montado pelo decoder.
                currentItState = instruction.immediate();
            } else if (governedByIt && instruction.kind() != InstructionKind.LONG_BRANCH_PREFIX) {
                int nextItState = ItState.advance(currentItState);
                block.add(new IrOp.SetItState(nextItState, Condition.AL));
                currentItState = nextItState;
            }
            // LONG_BRANCH_PREFIX: mesma condição/ITSTATE se aplica ao sufixo que segue (a próxima
            // iteração deste laço) — não avança aqui, o avanço acontece no sufixo.

            if (isTerminal(instruction)) {
                break;
            }
            pc += instructionWidth(instruction);
        }
        return block.sealed();
    }

    private boolean isTerminal(DecodedInstruction instruction) {
        if (instruction.kind() == InstructionKind.LOAD_MULTIPLE
                && (instruction.emptyRegisterList() || (instruction.immediate() & (1 << 15)) != 0)) {
            return true;
        }
        if ((instruction.kind() == InstructionKind.LOAD || instruction.kind() == InstructionKind.LOAD_LITERAL)
                && instruction.destinationRegister() == 15) {
            return true;
        }
        if (isAluTerminal(instruction)) {
            return true;
        }
        return switch (instruction.kind()) {
            // COPROCESSOR ends the block: a CP15 MCR can change CPU/memory state (TCM/MMU/high
            // vectors) and, critically, the ARM9 wait-for-interrupt (MCR p15,0,Rd,c7,c0,4) must end
            // the block so the run loop re-checks the interrupt line while IME is still set — the
            // libnds swiIntrWait loop toggles IME=1/halt/IME=0 within one block, so a non-terminal
            // coprocessor op would hide the only IME=1 window and the awaited IRQ would never fire.
            // RFE sempre troca o PC (como POP/LOAD com dst=PC) e WFI para a CPU (mesmo motivo do
            // MCR de wait-for-interrupt do CP15 acima): ambas terminam o bloco. TBB/TBH e CBZ/CBNZ
            // (B2.4) sempre trocam o PC (ou podem trocar, no caso de CBZ/CBNZ) — mesmo tratamento
            // que BRANCH já recebe, terminal independente do guard condicional.
            case BRANCH, BRANCH_EXCHANGE, LONG_BRANCH_SUFFIX, POP, SWI, UNIMPLEMENTED, COPROCESSOR,
                    RETURN_FROM_EXCEPTION, WAIT_FOR_INTERRUPT, TABLE_BRANCH, COMPARE_BRANCH_ZERO -> true;
            // IT (B2.4) NÃO é terminal: as instruções seguintes precisam continuar sendo lifted no
            // MESMO bloco para que a condição por-op seja anotada corretamente.
            case MOV, ADD, ADC, SUB, RSB, SBC, RSC, NEG, AND, EOR, ORR, LSL, LSR, ASR, ROR, MUL, MLA, UMULL, UMLAL, SMULL, SMLAL, CLZ, SATURATING, DSP_MULTIPLY, EXTEND, BYTE_REVERSE, UMAAL, PARALLEL_ALU, SEL, PKH, SATURATE, USAD8, LOAD_EXCLUSIVE, STORE_EXCLUSIVE, CLEAR_EXCLUSIVE, BIC, MVN, MRS, MSR, TST, TEQ, CMP, CMN, LOAD_LITERAL, LOAD, STORE, DOUBLE_TRANSFER, SWAP, LOAD_MULTIPLE, STORE_MULTIPLE, LONG_BRANCH_PREFIX, PUSH,
                    CPS, SETEND, STORE_RETURN_STATE, ORN, MOVE_TOP, MEMORY_BARRIER, IT -> false;
        };
    }

    private boolean isAluTerminal(DecodedInstruction instruction) {
        return instruction.destinationRegister() == 15
                && switch (instruction.kind()) {
                    case MOV, ADD, ADC, SUB, RSB, SBC, RSC, AND, EOR, ORR, LSL, LSR, ASR, ROR, BIC, MVN, ORN -> true;
                    default -> false;
                };
    }

    /// Largura em bytes da instrução decodificada. ARM é sempre 4; THUMB é 2 para todo encoding
    /// de 16 bits — INCLUSIVE os halfwords isolados de `LONG_BRANCH_PREFIX`/`SUFFIX`, cada um
    /// avança o PC em 2 — mas 4 para um candidato Thumb-2 GENUÍNO de 32 bits (B2.1+): o decoder
    /// empacota os dois halfwords em {@code raw()} como `(hi<<16)|lo`, e todo `hi` válido para os
    /// três padrões de top5 de 32 bits (`0b11101/0b11110/0b11111`, ver `ThumbDecoder`) tem o bit
    /// mais alto ligado — então `raw()` (assinado) é sempre NEGATIVO só nesse caso, nunca para um
    /// halfword isolado (sempre mascarado a 16 bits, 0x0000-0xFFFF, não-negativo).
    private int instructionWidth(DecodedInstruction instruction) {
        if (instruction.instructionSet() == InstructionSet.THUMB) {
            return instruction.raw() < 0 ? 4 : 2;
        }
        return 4;
    }
}
