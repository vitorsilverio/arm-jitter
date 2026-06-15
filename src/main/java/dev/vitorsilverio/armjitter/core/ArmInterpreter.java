package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrBuilder;
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

    /// Executa exatamente uma instrução e retorna a instrução decodificada.
    ///
    /// A instrução é elevada para um bloco IR de uma única operação e executada pela
    /// engine compartilhada com o JIT. Os ciclos de fetch e de memória são somados
    /// pelos próprios IrOp.Fetch/acessos; os ciclos internos retornados são somados aqui.
    public DecodedInstruction step(ArmCore core) {
        int pc = core.programCounter();
        InstructionDecoder decoder = core.cpsr().isThumbMode() ? thumbDecoder : armDecoder;
        DecodedInstruction instruction = decoder.decode(core.memory(), pc);
        IrBlock.Builder block = IrBlock.builder(pc);
        irBuilder.lift(instruction, block);
        core.addCycles(executor.execute(block.sealed(), core));
        return instruction;
    }
}
