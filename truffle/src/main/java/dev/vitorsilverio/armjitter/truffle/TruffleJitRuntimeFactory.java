package dev.vitorsilverio.armjitter.truffle;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.StandardIrOptimizer;
import dev.vitorsilverio.armjitter.jit.BlockCache;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.jit.JitRuntime;

/// Fábrica de runtimes JIT com o backend Truffle (tarefa A4), espelhando
/// {@link dev.vitorsilverio.armjitter.jit.JitRuntimeFactory#armThumb(int, int) JitRuntimeFactory.armThumb}
/// do core, mas trocando o emissor `JVM_BYTECODE` (ASM) pelo {@link TruffleCodeEmitter}. Vive no
/// módulo opcional `arm-jitter-truffle` porque o core não pode depender de API Truffle (G3/A1).
///
/// <h2>Quando escolher Truffle em vez de ASM</h2>
///
/// A task A4 mediu, com os EMISSORES DE PRODUÇÃO (não o protótipo descartável da A0), em JBR 25
/// com Truffle Unchained, que o backend Truffle/Graal perde para o ASM (HotSpot C2) em blocos
/// pequenos — típicos de jogo — por causa do overhead fixo de chamada (~5× pior a 20 instruções),
/// mas **inverte e vence** à medida que o bloco cresce, porque o C2 degrada em métodos retos
/// gigantes enquanto o Graal escala melhor (confirma a direção do spike A0, com magnitude própria
/// medida aqui):
///
/// | Bloco     | ASM (JVM_BYTECODE) | Truffle (Graal) | truffle/asm |
/// |-----------|--------------------:|-----------------:|------------:|
/// | 20 instr  | 3,93 ns             | 20,36 ns          | 5,17× (pior) |
/// | 80 instr  | 58,36 ns            | **43,51 ns**      | 0,75× (melhor) |
/// | 320 instr | 734,62 ns           | **134,62 ns**     | 0,18× (5,5× melhor) |
///
/// (JBR 25.0.3, mesma máquina/sessão, best-of-5 × 2M execuções; ver a seção "Runtime JIT" do
/// README para a tabela completa, JVM exata e a limitação sobre GraalVM CE standalone
/// [PENDENTE de instalação].)
///
/// **Recomendação prática:** para o tamanho de bloco típico de jogo (pequeno) o ASM
/// ({@link dev.vitorsilverio.armjitter.jit.JitRuntimeFactory#armThumb(int, int)}) continua o
/// backend correto — é o default dos consumidores (gbaemu/ndsemu) e não muda (G3). O Truffle
/// compensa em cenários de bloco/trace grande (candidato natural: superblocos/loop-superblocos da
/// trilha C) e é obrigatório sob `native-image` (ASM define classes em runtime, o que
/// `native-image` não permite — ver A5), não como substituto direto do ASM em produção hoje.
public final class TruffleJitRuntimeFactory {
    private TruffleJitRuntimeFactory() {
    }

    /// Cria um runtime ARM/THUMB com backend Truffle e otimizador GBA (ARMv4T).
    ///
    /// <p>Mesmo pipeline tiered de
    /// {@link dev.vitorsilverio.armjitter.jit.JitRuntimeFactory#armThumb(int, int)}: tier frio
    /// interpretado (sem custo de compilação) + tier quente compilado em background, aqui via
    /// {@link TruffleCodeEmitter} em vez de bytecode ASM. Aplica
    /// {@link StandardIrOptimizer#gba()} (constant fold → DCE → flag merge) antes da emissão.</p>
    public static JitRuntime truffleArmThumb(int cacheEntries, int hotThreshold) {
        return truffleArmThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM/THUMB com backend Truffle e otimizador GBA para a arquitetura informada.
    public static JitRuntime truffleArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        TruffleCodeEmitter hotEmitter = new TruffleCodeEmitter(architecture);
        InterpretedCodeEmitter coldEmitter = new InterpretedCodeEmitter(architecture);
        // TruffleCodeEmitter, ao contrário do AsmCodeEmitter, não recebe o IrOptimizer no próprio
        // construtor (não tem noção de "compilação" que se beneficie de pré-otimizar o IR antes de
        // montar a árvore Truffle — quem otimiza de verdade é o Graal via partial evaluation). Para
        // manter o MESMO pipeline observável (constant fold → DCE → flag merge antes da execução
        // "quente"), o otimizador entra aqui no nível do JitRuntime em vez de dentro do emissor.
        return new JitRuntime(
                new BlockCache(cacheEntries),
                new ArmDecoder(architecture),
                new ThumbDecoder(architecture),
                new StandardIrBuilder(),
                StandardIrOptimizer.gba(),
                hotEmitter,
                coldEmitter,
                new ExecutionThreshold(hotThreshold),
                64);
    }
}
