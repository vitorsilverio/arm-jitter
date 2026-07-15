package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrBuilder;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;

/// Interpretador frio usado para debug, step-by-step e como oráculo do JIT.
///
/// Para evitar duas implementações da semântica das instruções (uma aqui e outra no
/// caminho JIT), o interpretador decodifica uma única instrução, eleva-a para IR com
/// o mesmo [IrBuilder] do JIT e executa o bloco resultante pela mesma engine
/// ([InterpretedCodeEmitter#execute]). Assim qualquer correção de comportamento de
/// instrução vale para os dois caminhos automaticamente.
public final class ArmInterpreter {
    private final InstructionDecoder armDecoder;
    private final InstructionDecoder thumbDecoder;
    private final IrBuilder irBuilder;
    private final InterpretedCodeEmitter executor;

    /// Cria um interpretador com decoders ARM e THUMB padrao (ARMv4T).
    public ArmInterpreter() {
        this(new ArmDecoder(), new ThumbDecoder());
    }

    /// Cria um interpretador para a arquitetura informada (decoders + emitter ligados a ela).
    public ArmInterpreter(ArmArchitecture architecture) {
        this(new ArmDecoder(architecture), new ThumbDecoder(architecture),
                new StandardIrBuilder(), new InterpretedCodeEmitter(architecture));
    }

    /// Cria um interpretador com decoders customizados.
    public ArmInterpreter(InstructionDecoder armDecoder, InstructionDecoder thumbDecoder) {
        this(armDecoder, thumbDecoder, new StandardIrBuilder(), new InterpretedCodeEmitter());
    }

    /// Cria um interpretador com decoders, builder de IR e engine de execução customizados.
    public ArmInterpreter(
            InstructionDecoder armDecoder,
            InstructionDecoder thumbDecoder,
            IrBuilder irBuilder,
            InterpretedCodeEmitter executor) {
        this.armDecoder = armDecoder;
        this.thumbDecoder = thumbDecoder;
        this.irBuilder = irBuilder;
        this.executor = executor;
    }

    /// Resultado de uma instrução executada pelo interpretador frio.
    public record StepResult(DecodedInstruction instruction, int internalCycles) {
    }

    /// Executa exatamente uma instrução e retorna a instrução decodificada.
    ///
    /// A instrução é elevada para um bloco IR de uma única operação e executada pela
    /// engine compartilhada com o JIT. Os ciclos de fetch e de memória são somados
    /// pelos próprios IrOp.Fetch/acessos; os ciclos internos retornados são somados aqui.
    public DecodedInstruction step(ArmCore core) {
        return stepWithResult(core).instruction();
    }

    /// Executa exatamente uma instrução e retorna instrução e ciclos internos (`IrOp.Cycle`).
    ///
    /// Thumb-2 IT block (B2.4): ao contrário de {@link dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter}
    /// (que thread-a o estado local através de VÁRIAS instruções de um único lift), este caminho de
    /// passo único lê o ITSTATE ATUAL do CPSR a cada chamada — sempre correto, já que cada `step()`
    /// executa e AVANÇA o ITSTATE antes do próximo `step()` ser chamado (mesma mecânica de D1,
    /// aplicada instrução a instrução em vez de bloco a bloco).
    public StepResult stepWithResult(ArmCore core) {
        int pc = core.programCounter();
        InstructionDecoder decoder = core.cpsr().isThumbMode() ? thumbDecoder : armDecoder;
        DecodedInstruction instruction = decoder.decode(core.memory(), pc);
        int itState = core.cpsr().itState();
        boolean governedByIt = ItState.inProgress(itState);
        if (governedByIt) {
            instruction = instruction.withCondition(ItState.condition(itState));
        }
        IrBlock.Builder block = IrBlock.builder(pc);
        irBuilder.lift(instruction, block);
        // `IT` já emite seu próprio IrOp.SetItState dentro de irBuilder.lift (StandardIrBuilder) —
        // só falta o "avanço" pós-instrução para as demais instruções governadas por um IT block
        // ativo (mesma regra de StandardIrBlockLifter, incl. a exceção do prefixo de BL/BLX).
        if (governedByIt
                && instruction.kind() != InstructionKind.IT
                && instruction.kind() != InstructionKind.LONG_BRANCH_PREFIX) {
            block.add(new IrOp.SetItState(ItState.advance(itState), Condition.AL));
        }
        int internalCycles = executor.execute(block.sealed(), core);
        core.addCycles(internalCycles);
        return new StepResult(instruction, internalCycles);
    }
}
