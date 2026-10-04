package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64BreakpointException;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64HypervisorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64SecureMonitorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

import java.util.Objects;

/// Interpretador mínimo para AArch64 — fatia B6.1: SEM cache de blocos, SEM JIT, um `step()`/
/// `run()` direto sobre {@link Aarch64Core}. O pipeline tiered/compilado chega em B6.4 (ver
/// `tasks/trilha-b-arquiteturas/b6-aarch64.md`); esta classe é o oráculo de referência que aquele
/// pipeline futuro terá que igualar (mesmo papel de
/// {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} no mundo de 32 bits — G1).
///
/// Cada {@link #step} decodifica exatamente UMA instrução de 4 bytes no PC atual, contabiliza
/// {@link Ir64Op.Fetch}/{@link Ir64Op.Cycle} (G4: incondicionais, sempre executados) e executa a
/// semântica decodificada. A palavra é lida da memória a todo `step` (uma leitura, como sem cache);
/// o decode dela é guardado por `pc` e reaproveitado enquanto a palavra não mudar (E15.10b).
///
/// Esta classe é só o laço (`step`/`run`/`executeBlock`). A semântica de cada operação vive nos
/// executores por família deste pacote e é alcançada por {@link Ir64Op#execute} — um único dispatch
/// por op, sem `switch` sobre {@link Ir64Op.Kind} (task E15.4).
public final class Ir64BlockExecutor {
    /// Ciclos internos atribuídos a cada instrução nesta fatia (sem custo de memória/pipeline
    /// modelado ainda — ver B6.4).
    private static final int CYCLES_PER_INSTRUCTION = 1;
    /// Entradas do cache de decode do {@link #step} (potência de 2: 32 KiB de código contíguo sem
    /// colisão).
    static final int DECODE_CACHE_ENTRIES = 1 << 13;
    private static final int DECODE_CACHE_INDEX_MASK = DECODE_CACHE_ENTRIES - 1;
    /// `log2` do tamanho da instrução: endereços de instrução alinhados viram índices consecutivos.
    private static final int INSTRUCTION_ALIGNMENT_SHIFT = 2;
    private final Aarch64Decoder decoder;
    /// E15.10b: op decodificada por `pc`, mapeamento direto. Cada entrada guarda a PALAVRA de onde a
    /// op saiu e só vale se a palavra lida agora for a mesma — {@link Aarch64Decoder#decode(int, long)}
    /// é função pura de `(palavra, pc)`, então o resultado é idêntico ao de decodificar de novo, sem
    /// depender de `IC IVAU`, troca de tradução ou detecção de escrita. A entrada é um objeto imutável
    /// (publicação segura se o executor for compartilhado entre threads).
    private final DecodedInstruction[] decodeCache = new DecodedInstruction[DECODE_CACHE_ENTRIES];

    private record DecodedInstruction(long pc, int word, Ir64Op op) {
    }

    /// Cria um executor para {@link Aarch64Architecture#ARMV8_0_A} — equivalente ao comportamento
    /// deste executor antes de B11.2 (tudo que está implementado, incondicional).
    public Ir64BlockExecutor() {
        this(Aarch64Architecture.ARMV8_0_A);
    }

    /// Cria um executor para a arquitetura informada (B11.2) — fia a mesma arquitetura no
    /// {@link Aarch64Decoder} usado internamente. Ainda sem efeito observável (zero-diff, G3): o
    /// decoder não gateia nenhum encoding por arquitetura ainda, ver o Javadoc de
    /// {@link Aarch64Decoder}.
    public Ir64BlockExecutor(Aarch64Architecture architecture) {
        this.decoder = new Aarch64Decoder(Objects.requireNonNull(architecture, "architecture"));
    }

    /// Executa uma única instrução no PC atual do core e avança o PC (a menos que a própria
    /// instrução já tenha alterado o PC — um desvio tomado).
    ///
    /// B6.6.4 (espelho de `IrBlockExecutor#execute`, 32-bit): uma
    /// {@link MemoryTranslationException64} pode ser lançada tanto pelo PRÓPRIO
    /// {@link #executeFetch} (`AddressSpace64#accessCycles` de um `TranslatingAddressSpace64` já
    /// traduz — e pode faltar — o endereço de busca ANTES do decode em si tocar a memória) quanto
    /// pelo `decode`/execução de load-store — por isso o `try` cerca Fetch+Cycle+decode+execução
    /// inteiros (G4 continua satisfeito: nada aqui é condicionado à condição da PRÓPRIA
    /// instrução — Fetch/Cycle continuam incondicionais mesmo dentro do `try`, que só existe para
    /// capturar uma falta de HARDWARE, não para pular trabalho). Sem custo no caminho quente
    /// (faltas de tradução são raras por natureza). No `catch`, `pc` já É o endereço da instrução
    /// faltosa (fetch ou execução, sempre a MESMA instrução que `step` está processando).
    ///
    /// @param core core a executar
    /// @return ciclos internos consumidos (mesma convenção de
    ///         {@link dev.vitorsilverio.armjitter.core.ArmCore#stepReturningInternalCycles})
    public int step(Aarch64Core core) {
        // B6.6.7 (espelho de `ArmCore#servicePendingIrq`, 32-bit): checado ANTES de qualquer
        // fetch/decode desta rodada — uma IRQ entregue já redirecionou PC/PSTATE, e o core parado
        // em WFI (sem IRQ pendente ainda) não avança PC nenhum enquanto dorme.
        if (core.servicePendingIrq()) {
            core.addCycles(CYCLES_PER_INSTRUCTION);
            return CYCLES_PER_INSTRUCTION;
        }
        if (core.sleepState() != CpuSleepState.RUNNING) {
            core.addCycles(CYCLES_PER_INSTRUCTION);
            return CYCLES_PER_INSTRUCTION;
        }
        long pc = core.pc();
        int cycles = 0;
        try {
            // G4: Fetch/Cycle nunca ganham guard condicional — são contabilizados
            // incondicionalmente antes de decodificar a semântica da instrução.
            Ir64Op.Fetch fetch = new Ir64Op.Fetch(pc, Aarch64Decoder.instructionSizeBytes());
            executeFetch(core, fetch);
            Ir64Op.Cycle cycle = new Ir64Op.Cycle(CYCLES_PER_INSTRUCTION);
            cycles = executeCycle(cycle);
            core.addCycles(cycles);

            Ir64Op op = decodeCached(core.memory().read32(pc), pc);
            boolean pcChanged = op.execute(core);
            if (!pcChanged) {
                core.setProgramCounter(pc + Aarch64Decoder.instructionSizeBytes());
            }
        } catch (MemoryTranslationException64 fault) {
            core.enterMemoryAbort(pc, fault);
        } catch (Aarch64BreakpointException brk) {
            core.enterBreakpointException(pc, brk.immediate());
        } catch (Aarch64UndefinedInstructionException undefined) {
            core.enterUndefinedInstructionException(pc);
        } catch (Aarch64HypervisorCallException hvc) {
            core.enterHypervisorCall(pc);
        } catch (Aarch64SecureMonitorCallException smc) {
            core.enterSecureMonitorCall(pc);
        }
        return cycles;
    }

    /// Executa `instructionCount` instruções em sequência a partir do PC atual.
    ///
    /// @param core core a executar
    /// @param instructionCount quantidade de instruções a executar
    /// @return total de ciclos internos consumidos
    public long run(Aarch64Core core, int instructionCount) {
        if (instructionCount < 0) {
            throw new IllegalArgumentException("instructionCount must be >= 0");
        }
        long total = 0;
        for (int i = 0; i < instructionCount; i++) {
            total += step(core);
        }
        return total;
    }

    /// Executa uma única operação já decodificada, sem repetir fetch/cycle — ponto de entrada
    /// PÚBLICO usado pelo backend ASM (B6.4, `Ir64AsmRuntimeHelpers`) para despachar através do
    /// MESMO caminho de execução do interpretador: garante G1 (interpretador é o oráculo) POR
    /// CONSTRUÇÃO, já que o bytecode gerado chama esta mesma implementação em vez de reescrevê-la
    /// — nenhuma lógica de {@code executeAlu}/{@code executeBranch}/etc. é duplicada. Equivale a
    /// {@link Ir64Op#execute} (E15.4); mantido como ponto de entrada estável do backend ASM.
    ///
    /// `Cycle`/`Fetch` não são aceitos aqui (contabilizados separadamente, ver {@link
    /// #executeBlock} e G4 — nunca ganham guard condicional).
    ///
    /// @param core core a executar
    /// @param op operação a executar (nunca `Cycle`/`Fetch`)
    /// @return `true` se a própria operação já alterou o PC (desvio tomado)
    public boolean executeOp(Aarch64Core core, Ir64Op op) {
        return op.execute(core);
    }

    /// Executa um {@link Ir64Block} inteiro — oráculo de bloco usado pelo backend interpretado
    /// (B6.4, `InterpretedIr64CodeEmitter`) e pelo harness de equivalência A64. `Fetch`/`Cycle`
    /// são tratados inline (G4: incondicionais); as demais ops são despachadas via
    /// {@link Ir64Op#execute}. Instruções não-terminais (que não alteraram o PC) avançam o PC para
    /// `fetch.address() + tamanhoDaInstrução` — a mesma informação de "próximo PC" que o
    /// {@code Ir64BlockCompiler} (backend ASM) grava como CONSTANTE de compilação, já que ambos
    /// derivam da mesma garantia estrutural do lifter: ops aparecem em grupos `[Fetch, Cycle, op]`
    /// na ordem do PC linear.
    ///
    /// @param core core a executar
    /// @param block bloco lifted por {@link dev.vitorsilverio.armjitter.ir64.Ir64BlockLifter}
    /// @return total de ciclos internos (soma de {@link Ir64Op.Cycle#count()}) consumidos pelo bloco
    ///
    /// B6.6.4: uma {@link MemoryTranslationException64} lançada no meio do laço é capturada aqui
    /// (mesmo padrão de {@link #step}/`IrBlockExecutor#execute`, 32-bit) — `lastFetchAddress` já é
    /// o endereço da instrução dona da op que lançou (G4: cada instrução aparece sempre como
    /// `[Fetch, Cycle, op]` na ordem do PC linear, então o `Fetch` mais recente antes de QUALQUER
    /// índice é sempre o desta instrução).
    public int executeBlock(Aarch64Core core, Ir64Block block) {
        // B6.6.7: mesmo ponto de verificação de IRQ/WFI de `step()` — checado no LIMITE do bloco,
        // nunca no meio (um bloco lifted é atômico do ponto de vista do JIT/tiering futuro, mesma
        // disciplina do precedente 32-bit `ArmCore#runBlock`).
        if (core.servicePendingIrq() || core.sleepState() != CpuSleepState.RUNNING) {
            core.addCycles(CYCLES_PER_INSTRUCTION);
            return CYCLES_PER_INSTRUCTION;
        }
        Ir64Op[] ops = block.operationsArray();
        int[] kinds = block.kindsArray();
        int cycles = 0;
        long lastFetchAddress = -1L;
        int lastFetchSizeBytes = 0;
        try {
            for (int i = 0; i < ops.length; i++) {
                switch (kinds[i]) {
                    case Ir64Op.Kind.FETCH -> {
                        Ir64Op.Fetch fetch = (Ir64Op.Fetch) ops[i];
                        executeFetch(core, fetch);
                        lastFetchAddress = fetch.address();
                        lastFetchSizeBytes = fetch.sizeBytes();
                    }
                    case Ir64Op.Kind.CYCLE -> cycles += executeCycle((Ir64Op.Cycle) ops[i]);
                    default -> {
                        boolean pcChanged = ops[i].execute(core);
                        if (!pcChanged) {
                            core.setProgramCounter(lastFetchAddress + lastFetchSizeBytes);
                        }
                    }
                }
            }
        } catch (MemoryTranslationException64 fault) {
            core.enterMemoryAbort(lastFetchAddress, fault);
        } catch (Aarch64BreakpointException brk) {
            core.enterBreakpointException(lastFetchAddress, brk.immediate());
        } catch (Aarch64UndefinedInstructionException undefined) {
            core.enterUndefinedInstructionException(lastFetchAddress);
        } catch (Aarch64HypervisorCallException hvc) {
            core.enterHypervisorCall(lastFetchAddress);
        } catch (Aarch64SecureMonitorCallException smc) {
            core.enterSecureMonitorCall(lastFetchAddress);
        }
        return cycles;
    }

    /// A op de `word` em `pc`: a do cache se a entrada for desta mesma palavra neste mesmo `pc`, senão
    /// decodifica e substitui a entrada. Encoding recusado pelo decoder lança e não entra no cache.
    private Ir64Op decodeCached(int word, long pc) {
        int index = (int) (pc >>> INSTRUCTION_ALIGNMENT_SHIFT) & DECODE_CACHE_INDEX_MASK;
        DecodedInstruction entry = decodeCache[index];
        if (entry != null && entry.pc == pc && entry.word == word) {
            return entry.op;
        }
        Ir64Op op = decoder.decode(word, pc);
        decodeCache[index] = new DecodedInstruction(pc, word, op);
        return op;
    }

    private void executeFetch(Aarch64Core core, Ir64Op.Fetch op) {
        int extra = core.memory().accessCycles(op.address(), op.sizeBytes(),
                MemoryAccessType.INSTRUCTION_FETCH);
        if (extra > 0) {
            core.addCycles(extra);
        }
    }

    private int executeCycle(Ir64Op.Cycle op) {
        return op.count();
    }
}
