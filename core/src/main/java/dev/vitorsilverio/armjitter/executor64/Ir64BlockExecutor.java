package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64BreakpointException;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64HypervisorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64SecureMonitorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

import java.math.BigInteger;

/// Interpretador mínimo para AArch64 — fatia B6.1: SEM cache de blocos, SEM JIT, um `step()`/
/// `run()` direto sobre {@link Aarch64Core}. O pipeline tiered/compilado chega em B6.4 (ver
/// `tasks/trilha-b-arquiteturas/b6-aarch64.md`); esta classe é o oráculo de referência que aquele
/// pipeline futuro terá que igualar (mesmo papel de
/// {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} no mundo de 32 bits — G1).
///
/// Cada {@link #step} decodifica exatamente UMA instrução de 4 bytes no PC atual, contabiliza
/// {@link Ir64Op.Fetch}/{@link Ir64Op.Cycle} (G4: incondicionais, sempre executados) e executa a
/// semântica decodificada.
public final class Ir64BlockExecutor {
    /// Ciclos internos atribuídos a cada instrução nesta fatia (sem custo de memória/pipeline
    /// modelado ainda — ver B6.4).
    private static final int CYCLES_PER_INSTRUCTION = 1;
    /// Deslocamento em bytes do registrador X30 (link register) usado por `BL`/`BLR`.
    private static final int LINK_REGISTER = 30;
    /// Bit `I` dentro do `imm4` de `DAIFSet`/`DAIFClr` (`ARM DDI 0487`, ordem `D:A:I:F` — `I` é a
    /// posição `1`, MESMA convenção de `PstateRegister#irqDisabled` para máscara de IRQ). Único
    /// bit deste grupo com efeito observável neste emulador (B8.3, ver
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.InterruptMask}).
    private static final int DAIF_MASK_BIT_I = 1 << 1;
    /// Índice de encoding (`Rn`=`31`) do registrador BASE de qualquer load/store — sempre `SP`,
    /// nunca `XZR` (convenção arquitetural do A64, resolvida aqui e não no decoder — ver
    /// {@link Ir64Op.Load64#rn} javadoc).
    private static final int BASE_REGISTER_SP_ENCODING = 31;
    /// Deslocamento em bytes entre os dois slots de um `LDP`/`STP` de 64 bits.
    private static final int PAIR_DOUBLEWORD_STRIDE_BYTES = 8;
    /// Deslocamento em bytes entre os dois slots de um `LDP`/`STP` de 32 bits.
    private static final int PAIR_WORD_STRIDE_BYTES = 4;
    /// Índice de encoding (`31`) que designa `SP` em {@link Ir64Op.AluExtendedRegister} — mesmo
    /// valor de {@link #BASE_REGISTER_SP_ENCODING}, nomeado separadamente porque aparece num
    /// contexto de ALU (não endereçamento de memória). A resolução SEMPRE checa o índice (`==
    /// 31`), nunca só a flag booleira do op (ver {@link #executeAluExtendedRegister} e a
    /// diferença deliberada com {@link #executeAlu}, documentada na task B6.3.1).
    private static final int ALU_STACK_POINTER_ENCODING = 31;
    /// Máscara para a metade baixa de 32 bits — mesma disciplina de largura usada no resto do
    /// executor (`W` sempre zero-estende para os 64 bits altos).
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;
    /// Largura em bits de uma operação `X` (64 bits) — usada por {@link #executeBitfield} para
    /// calcular `pos`/`len` conforme {@link Ir64Op.Bitfield#wide()} (B6.3.2).
    private static final int BITFIELD_WIDE_BITSIZE = 64;
    /// Largura em bits de uma operação `W` (32 bits) — ver {@link #BITFIELD_WIDE_BITSIZE}.
    private static final int BITFIELD_NARROW_BITSIZE = 32;
    /// Máscara de um halfword (16 bits) — B8.2, {@code executeDataProcessing1Source}/`REV16`.
    private static final long HALFWORD_MASK = 0xFFFFL;
    /// Máscara de um byte — mesmo uso de {@link #HALFWORD_MASK}.
    private static final long BYTE_MASK = 0xFFL;
    /// Máscara dos 4 bits de `N:Z:C:V` — MESMA ordem de bit de
    /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#nzcv()}, usada por
    /// {@code executeRotateIntoFlags} (`RMIF`, B8.2).
    private static final int NZCV_FIELD_MASK = 0xF;
    /// `2^64` como {@link BigInteger} — usado por {@link #addWithCarryFlags} para detectar
    /// carry-out da forma de 64 bits (que precisa de mais de 64 bits de precisão intermediária
    /// para ser exato — ver o Javadoc daquele método).
    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(Long.SIZE);
    /// Máscara dos 64 bits baixos como {@link BigInteger} — ver {@link #TWO_POW_64}.
    private static final BigInteger MASK_64_BITS = TWO_POW_64.subtract(BigInteger.ONE);

    private final Aarch64Decoder decoder = new Aarch64Decoder();

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

            Ir64Op op = decoder.decode(core.memory(), pc);
            boolean pcChanged = execute(core, op);
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
    /// — nenhuma lógica de {@code executeAlu}/{@code executeBranch}/etc. é duplicada.
    ///
    /// `Cycle`/`Fetch` não são aceitos aqui (contabilizados separadamente, ver {@link
    /// #executeBlock} e G4 — nunca ganham guard condicional).
    ///
    /// @param core core a executar
    /// @param op operação a executar (nunca `Cycle`/`Fetch`)
    /// @return `true` se a própria operação já alterou o PC (desvio tomado)
    public boolean executeOp(Aarch64Core core, Ir64Op op) {
        return execute(core, op);
    }

    /// Executa um {@link Ir64Block} inteiro — oráculo de bloco usado pelo backend interpretado
    /// (B6.4, `InterpretedIr64CodeEmitter`) e pelo harness de equivalência A64. `Fetch`/`Cycle`
    /// são tratados inline (G4: incondicionais); as demais ops são despachadas via
    /// {@link #executeOp}. Instruções não-terminais (que não alteraram o PC) avançam o PC para
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
                        boolean pcChanged = executeOp(core, ops[i]);
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

    private boolean execute(Aarch64Core core, Ir64Op op) {
        return switch (op.kind()) {
            case Ir64Op.Kind.ALU64 -> executeAlu(core, (Ir64Op.Alu64) op);
            case Ir64Op.Kind.MOVE_WIDE -> executeMoveWide(core, (Ir64Op.MoveWide) op);
            case Ir64Op.Kind.PC_RELATIVE -> executePcRelative(core, (Ir64Op.PcRelative) op);
            case Ir64Op.Kind.BRANCH64 -> executeBranch(core, (Ir64Op.Branch64) op);
            case Ir64Op.Kind.COMPARE_BRANCH64 -> executeCompareBranch(core, (Ir64Op.CompareBranch64) op);
            case Ir64Op.Kind.SVC -> executeSvc(core, (Ir64Op.Svc) op);
            case Ir64Op.Kind.LOAD64 -> executeLoad(core, (Ir64Op.Load64) op);
            case Ir64Op.Kind.STORE64 -> executeStore(core, (Ir64Op.Store64) op);
            case Ir64Op.Kind.LOAD_STORE_PAIR -> executeLoadStorePair(core, (Ir64Op.LoadStorePair) op);
            case Ir64Op.Kind.LOAD_LITERAL64 -> executeLoadLiteral(core, (Ir64Op.LoadLiteral64) op);
            case Ir64Op.Kind.ALU_SHIFTED_REGISTER ->
                    executeAluShiftedRegister(core, (Ir64Op.AluShiftedRegister) op);
            case Ir64Op.Kind.ALU_EXTENDED_REGISTER ->
                    executeAluExtendedRegister(core, (Ir64Op.AluExtendedRegister) op);
            case Ir64Op.Kind.CONDITIONAL_SELECT ->
                    executeConditionalSelect(core, (Ir64Op.ConditionalSelect) op);
            case Ir64Op.Kind.CONDITIONAL_COMPARE ->
                    executeConditionalCompare(core, (Ir64Op.ConditionalCompare) op);
            case Ir64Op.Kind.LOGICAL_SHIFTED_REGISTER ->
                    executeLogicalShiftedRegister(core, (Ir64Op.LogicalShiftedRegister) op);
            case Ir64Op.Kind.SHIFT_VARIABLE ->
                    executeShiftVariable(core, (Ir64Op.ShiftVariable) op);
            case Ir64Op.Kind.BITFIELD -> executeBitfield(core, (Ir64Op.Bitfield) op);
            case Ir64Op.Kind.MULTIPLY_ACCUMULATE ->
                    executeMultiplyAccumulate(core, (Ir64Op.MultiplyAccumulate) op);
            case Ir64Op.Kind.DIVIDE -> executeDivide(core, (Ir64Op.Divide) op);
            case Ir64Op.Kind.LOAD_EXCLUSIVE -> executeLoadExclusive(core, (Ir64Op.LoadExclusive) op);
            case Ir64Op.Kind.STORE_EXCLUSIVE -> executeStoreExclusive(core, (Ir64Op.StoreExclusive) op);
            case Ir64Op.Kind.LOAD_EXCLUSIVE_PAIR ->
                    executeLoadExclusivePair(core, (Ir64Op.LoadExclusivePair) op);
            case Ir64Op.Kind.STORE_EXCLUSIVE_PAIR ->
                    executeStoreExclusivePair(core, (Ir64Op.StoreExclusivePair) op);
            case Ir64Op.Kind.COMPARE_AND_SWAP ->
                    executeCompareAndSwap(core, (Ir64Op.CompareAndSwap) op);
            case Ir64Op.Kind.COMPARE_AND_SWAP_PAIR ->
                    executeCompareAndSwapPair(core, (Ir64Op.CompareAndSwapPair) op);
            case Ir64Op.Kind.SYSTEM_REGISTER -> executeSystemRegister(core, (Ir64Op.SystemRegister) op);
            case Ir64Op.Kind.SYSTEM_INSTRUCTION ->
                    executeSystemInstruction(core, (Ir64Op.SystemInstruction) op);
            case Ir64Op.Kind.EXCEPTION_RETURN ->
                    executeExceptionReturn(core, (Ir64Op.ExceptionReturn) op);
            case Ir64Op.Kind.FP64_ALU -> Ir64FpExecutor.executeFpAlu(core, (Ir64Op.Fp64Alu) op);
            case Ir64Op.Kind.FP64_MOVE_IMMEDIATE ->
                    Ir64FpExecutor.executeFpMoveImmediate(core, (Ir64Op.Fp64MoveImmediate) op);
            case Ir64Op.Kind.FP64_COMPARE ->
                    Ir64FpExecutor.executeFpCompare(core, (Ir64Op.Fp64Compare) op);
            case Ir64Op.Kind.FP64_CONVERT ->
                    Ir64FpExecutor.executeFpConvert(core, (Ir64Op.Fp64Convert) op);
            case Ir64Op.Kind.PRIVILEGED_CALL ->
                    executePrivilegedCall((Ir64Op.PrivilegedCall) op);
            case Ir64Op.Kind.ALU_WITH_CARRY -> executeAluWithCarry(core, (Ir64Op.AluWithCarry) op);
            case Ir64Op.Kind.EXTRACT -> executeExtract(core, (Ir64Op.Extract) op);
            case Ir64Op.Kind.DATA_PROCESSING_1_SOURCE ->
                    executeDataProcessing1Source(core, (Ir64Op.DataProcessing1Source) op);
            case Ir64Op.Kind.MULTIPLY_ACCUMULATE_LONG ->
                    executeMultiplyAccumulateLong(core, (Ir64Op.MultiplyAccumulateLong) op);
            case Ir64Op.Kind.MULTIPLY_HIGH -> executeMultiplyHigh(core, (Ir64Op.MultiplyHigh) op);
            case Ir64Op.Kind.EVALUATE_INTO_FLAGS ->
                    executeEvaluateIntoFlags(core, (Ir64Op.EvaluateIntoFlags) op);
            case Ir64Op.Kind.ROTATE_INTO_FLAGS ->
                    executeRotateIntoFlags(core, (Ir64Op.RotateIntoFlags) op);
            case Ir64Op.Kind.CONVERT_FLAGS -> executeConvertFlags(core, (Ir64Op.ConvertFlags) op);
            case Ir64Op.Kind.INTERRUPT_MASK -> executeInterruptMask(core, (Ir64Op.InterruptMask) op);
            case Ir64Op.Kind.BREAKPOINT -> executeBreakpoint((Ir64Op.Breakpoint) op);
            case Ir64Op.Kind.UNDEFINED_INSTRUCTION_TRAP -> executeUndefinedInstructionTrap();
            case Ir64Op.Kind.ADDRESS_TRANSLATE ->
                    executeAddressTranslate(core, (Ir64Op.AddressTranslate) op);
            case Ir64Op.Kind.FP64_MULTIPLY_ADD ->
                    Ir64FpExecutor.executeFpMultiplyAdd(core, (Ir64Op.Fp64MultiplyAdd) op);
            case Ir64Op.Kind.FP64_CONDITIONAL_SELECT ->
                    Ir64FpExecutor.executeFpConditionalSelect(core, (Ir64Op.Fp64ConditionalSelect) op);
            case Ir64Op.Kind.FP64_CONDITIONAL_COMPARE ->
                    Ir64FpExecutor.executeFpConditionalCompare(core, (Ir64Op.Fp64ConditionalCompare) op);
            case Ir64Op.Kind.FP64_ROUND -> Ir64FpExecutor.executeFpRound(core, (Ir64Op.Fp64Round) op);
            case Ir64Op.Kind.FP64_INTEGER_CONVERT ->
                    Ir64FpExecutor.executeFpIntegerConvert(core, (Ir64Op.Fp64IntegerConvert) op);
            case Ir64Op.Kind.FP64_GENERAL_REGISTER_MOVE ->
                    Ir64FpExecutor.executeFpGeneralRegisterMove(core, (Ir64Op.Fp64GeneralRegisterMove) op);
            case Ir64Op.Kind.VECTOR_LOAD_STORE_MULTIPLE ->
                    executeVectorLoadStoreMultiple(core, (Ir64Op.VectorLoadStoreMultiple) op);
            case Ir64Op.Kind.VECTOR_LOAD_STORE_SINGLE ->
                    executeVectorLoadStoreSingle(core, (Ir64Op.VectorLoadStoreSingle) op);
            case Ir64Op.Kind.VECTOR_LOAD_SINGLE_REPLICATE ->
                    executeVectorLoadSingleReplicate(core, (Ir64Op.VectorLoadSingleReplicate) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_THREE_SAME ->
                    Ir64VectorArithmeticExecutor.executeThreeSame(core, (Ir64Op.VectorArithmeticThreeSame) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_PAIRWISE ->
                    Ir64VectorArithmeticExecutor.executePairwise(core, (Ir64Op.VectorArithmeticPairwise) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_WIDENING ->
                    Ir64VectorArithmeticExecutor.executeWidening(core, (Ir64Op.VectorArithmeticWidening) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_WIDE ->
                    Ir64VectorArithmeticExecutor.executeWide(core, (Ir64Op.VectorArithmeticWide) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_NARROW ->
                    Ir64VectorArithmeticExecutor.executeNarrow(core, (Ir64Op.VectorArithmeticNarrow) op);
            case Ir64Op.Kind.VECTOR_ACROSS_LANES ->
                    Ir64VectorArithmeticExecutor.executeAcrossLanes(core, (Ir64Op.VectorAcrossLanes) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_UNARY ->
                    Ir64VectorArithmeticExecutor.executeUnary(core, (Ir64Op.VectorArithmeticUnary) op);
            case Ir64Op.Kind.VECTOR_SCALAR_PAIRWISE_ADD ->
                    Ir64VectorArithmeticExecutor.executeScalarPairwiseAdd(core, (Ir64Op.VectorScalarPairwiseAdd) op);
            case Ir64Op.Kind.VECTOR_ARITHMETIC_NARROW_UNARY ->
                    Ir64VectorArithmeticExecutor.executeNarrowUnary(core, (Ir64Op.VectorArithmeticNarrowUnary) op);
            case Ir64Op.Kind.VECTOR_SHIFT_IMMEDIATE ->
                    Ir64VectorArithmeticExecutor.executeShiftImmediate(core, (Ir64Op.VectorShiftImmediate) op);
            case Ir64Op.Kind.VECTOR_SHIFT_NARROW_IMMEDIATE ->
                    Ir64VectorArithmeticExecutor.executeShiftNarrowImmediate(core, (Ir64Op.VectorShiftNarrowImmediate) op);
            case Ir64Op.Kind.VECTOR_SHIFT_WIDEN_IMMEDIATE ->
                    Ir64VectorArithmeticExecutor.executeShiftWidenImmediate(core, (Ir64Op.VectorShiftWidenImmediate) op);
            case Ir64Op.Kind.VECTOR_FP_ARITHMETIC_THREE_SAME ->
                    Ir64VectorFpArithmeticExecutor.executeThreeSame(core, (Ir64Op.VectorFpArithmeticThreeSame) op);
            case Ir64Op.Kind.VECTOR_FP_ARITHMETIC_PAIRWISE ->
                    Ir64VectorFpArithmeticExecutor.executePairwise(core, (Ir64Op.VectorFpArithmeticPairwise) op);
            case Ir64Op.Kind.VECTOR_FP_ARITHMETIC_UNARY ->
                    Ir64VectorFpArithmeticExecutor.executeUnary(core, (Ir64Op.VectorFpArithmeticUnary) op);
            case Ir64Op.Kind.VECTOR_EXTRACT ->
                    Ir64VectorArithmeticExecutor.executeExtract(core, (Ir64Op.VectorExtract) op);
            case Ir64Op.Kind.VECTOR_PERMUTE ->
                    Ir64VectorArithmeticExecutor.executePermute(core, (Ir64Op.VectorPermute) op);
            case Ir64Op.Kind.VECTOR_TABLE_LOOKUP ->
                    Ir64VectorArithmeticExecutor.executeTableLookup(core, (Ir64Op.VectorTableLookup) op);
            case Ir64Op.Kind.VECTOR_FP_ACROSS_LANES ->
                    Ir64VectorFpArithmeticExecutor.executeFpAcrossLanes(core, (Ir64Op.VectorFpAcrossLanes) op);
            case Ir64Op.Kind.CYCLE, Ir64Op.Kind.FETCH ->
                    throw new IllegalStateException("Cycle/Fetch não são decodificados como instrução");
            default -> throw new IllegalStateException("Ir64Op.kind desconhecido: " + op.kind());
        };
    }

    private boolean executeAlu(Aarch64Core core, Ir64Op.Alu64 op) {
        long operand1 = op.src1IsStackPointer() ? core.sp() : core.xForWidth(op.src1(), op.wide());
        long operand2 = op.immediate();
        AluResult result = switch (op.opcode()) {
            case ADD -> addWithFlags(operand1, operand2, op.wide());
            case SUB -> subWithFlags(operand1, operand2, op.wide());
            case AND -> logicalWithFlags(operand1 & operand2, op.wide());
            case ORR -> logicalWithFlags(operand1 | operand2, op.wide());
            case EOR -> logicalWithFlags(operand1 ^ operand2, op.wide());
        };
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        if (op.dstIsStackPointer()) {
            core.setSp(op.wide() ? result.value : (result.value & 0xFFFF_FFFFL));
        } else {
            core.setXForWidth(op.dst(), result.value, op.wide());
        }
        return false;
    }

    /// `ADD`/`SUB`/`ADDS`/`SUBS` na forma "shifted register" (B6.3.1) — `Rd`/`Rn`/`Rm` nunca são
    /// `SP` nesta forma (índice `31` é sempre `XZR`, resolvido normalmente por
    /// {@link Aarch64Core#xForWidth}/{@link Aarch64Core#setXForWidth}, sem nenhuma checagem de
    /// `SP` — ao contrário de {@link #executeAluExtendedRegister}).
    private boolean executeAluShiftedRegister(Aarch64Core core, Ir64Op.AluShiftedRegister op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long rawOperand2 = core.xForWidth(op.src2(), op.wide());
        long operand2 = applyShift(rawOperand2, op.shiftType(), op.shiftAmount(), op.wide());
        AluResult result = op.opcode() == Ir64AluOp.SUB
                ? subWithFlags(operand1, operand2, op.wide())
                : addWithFlags(operand1, operand2, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        core.setXForWidth(op.dst(), result.value, op.wide());
        return false;
    }

    /// `ADD`/`SUB`/`ADDS`/`SUBS` na forma "extended register" (B6.3.1) — `Rn` é SEMPRE `Rn|SP`
    /// (`ARM DDI 0487` pseudocódigo de `ADD (extended register)`: `operand1 = if n == 31 then
    /// SP[] else X[n]`); `Rd` é `Rd|SP` só quando `!setFlags` (mesmo pseudocódigo: `if d == 31 &&
    /// !setflags then SP[] = result else X[d] = result`). **A resolução SEMPRE checa o índice
    /// contra `31`, nunca só a flag** — diferente de {@link #executeAlu} (forma imediata,
    /// B6.1), que resolve incondicionalmente pela flag; esta é a mesma disciplina já usada por
    /// {@link #readBaseRegister}/{@link #writeBaseRegister} (load/store) neste mesmo arquivo.
    /// `Rm` nunca é `SP` (sempre lido por índice normal antes de estender).
    private boolean executeAluExtendedRegister(Aarch64Core core, Ir64Op.AluExtendedRegister op) {
        long operand1 = readAluOperandOrStackPointer(core, op.src1(), op.wide());
        long extended = extendAluOperand(core.x(op.src2()), op.extendType());
        long operand2 = extended << op.shiftAmount();
        AluResult result = op.opcode() == Ir64AluOp.SUB
                ? subWithFlags(operand1, operand2, op.wide())
                : addWithFlags(operand1, operand2, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        if (op.dstIsStackPointer() && op.dst() == ALU_STACK_POINTER_ENCODING) {
            core.setSp(op.wide() ? result.value : (result.value & LOW_32_BITS_MASK));
        } else {
            core.setXForWidth(op.dst(), result.value, op.wide());
        }
        return false;
    }

    /// Lê `Rn|SP`: `SP` (na largura pedida) quando o índice é `31`, senão o registrador normal.
    private static long readAluOperandOrStackPointer(Aarch64Core core, int index, boolean wide) {
        if (index == ALU_STACK_POINTER_ENCODING) {
            long sp = core.sp();
            return wide ? sp : (sp & LOW_32_BITS_MASK);
        }
        return core.xForWidth(index, wide);
    }

    /// Aplica o deslocamento de {@link Ir64Op.AluShiftedRegister} respeitando a largura da
    /// operação — `LSR`/`ASR` em `W` operam sobre os 32 bits baixos (não os 64 completos), por
    /// isso o cálculo é feito em `int` quando `!wide`, não só mascarado depois.
    private static long applyShift(long value, Ir64ShiftType shiftType, int amount, boolean wide) {
        if (wide) {
            return switch (shiftType) {
                case LSL -> value << amount;
                case LSR -> value >>> amount;
                case ASR -> value >> amount;
            };
        }
        int narrow = (int) value;
        int shifted = switch (shiftType) {
            case LSL -> narrow << amount;
            case LSR -> narrow >>> amount;
            case ASR -> narrow >> amount;
        };
        return shifted & LOW_32_BITS_MASK;
    }

    /// `AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` na forma "shifted register" (B6.9) —
    /// `Rm` é deslocado (`shiftType`/`shiftAmount`, os 4 tipos incl. `ROR`), depois OPCIONALMENTE
    /// invertido bit a bit ({@link Ir64Op.LogicalShiftedRegister#invert}) ANTES de combinar com
    /// `Rn` (inversão sempre acontece antes da operação lógica, nunca depois — `bic rd,rn,rm` =
    /// `rn AND (NOT rm)`, não `NOT(rn AND rm)`). Flags reaproveitam {@link #logicalWithFlags}
    /// (mesmo padrão de {@link #executeAlu}, D2 da task B6.3.1: `C=0,V=0` sempre).
    private boolean executeLogicalShiftedRegister(Aarch64Core core, Ir64Op.LogicalShiftedRegister op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long rawOperand2 = core.xForWidth(op.src2(), op.wide());
        long shifted = applyLogicalShift(rawOperand2, op.shiftType(), op.shiftAmount(), op.wide());
        long operand2 = op.invert() ? ~shifted : shifted;
        long combined = switch (op.opcode()) {
            case AND -> operand1 & operand2;
            case ORR -> operand1 | operand2;
            case EOR -> operand1 ^ operand2;
            case ADD, SUB -> throw new IllegalStateException(
                    "LogicalShiftedRegister nunca carrega ADD/SUB: " + op.opcode());
        };
        AluResult result = logicalWithFlags(combined, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        core.setXForWidth(op.dst(), result.value, op.wide());
        return false;
    }

    /// `LSLV`/`LSRV`/`ASRV`/`RORV` (B6.11) — mesma tabela de deslocamento de
    /// {@link #executeLogicalShiftedRegister} ({@link #applyLogicalShift}), mas a quantidade vem
    /// de {@link Ir64Op.ShiftVariable#src2} EM TEMPO DE EXECUÇÃO (`mod` largura), não de um campo
    /// já resolvido pelo decoder. Nunca afeta `NZCV`.
    private boolean executeShiftVariable(Aarch64Core core, Ir64Op.ShiftVariable op) {
        long operand = core.xForWidth(op.src1(), op.wide());
        long rawAmount = core.xForWidth(op.src2(), op.wide());
        int amount = (int) (rawAmount & (op.wide() ? 63L : 31L));
        long result = applyLogicalShift(operand, op.shiftType(), amount, op.wide());
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// Aplica o deslocamento de {@link Ir64Op.LogicalShiftedRegister}, incluindo `ROR` (válido
    /// só aqui — `AluShiftedRegister`/{@link #applyShift} não tem esse caso, ver
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType}).
    private static long applyLogicalShift(
            long value, Ir64LogicalShiftType shiftType, int amount, boolean wide) {
        if (wide) {
            return switch (shiftType) {
                case LSL -> value << amount;
                case LSR -> value >>> amount;
                case ASR -> value >> amount;
                case ROR -> amount == 0 ? value : (value >>> amount) | (value << (Long.SIZE - amount));
            };
        }
        int narrow = (int) value;
        int shifted = switch (shiftType) {
            case LSL -> narrow << amount;
            case LSR -> narrow >>> amount;
            case ASR -> narrow >> amount;
            case ROR -> amount == 0 ? narrow : (narrow >>> amount) | (narrow << (Integer.SIZE - amount));
        };
        return shifted & LOW_32_BITS_MASK;
    }

    /// Estende `Rm` (fatia de tamanho/sinal dados por {@code extendType}) para 64 bits, ANTES do
    /// deslocamento de {@link Ir64Op.AluExtendedRegister#shiftAmount()} — mesmo helper conceitual
    /// de `ext_and_shift_reg` (`translate-a64.c`), citado nos Fatos de referência #5 da task.
    private static long extendAluOperand(long rawRegisterValue, Ir64AluExtendType extendType) {
        return switch (extendType) {
            case UXTB -> rawRegisterValue & 0xFFL;
            case UXTH -> rawRegisterValue & 0xFFFFL;
            case UXTW -> rawRegisterValue & LOW_32_BITS_MASK;
            case UXTX -> rawRegisterValue;
            case SXTB -> (long) (byte) rawRegisterValue;
            case SXTH -> (long) (short) rawRegisterValue;
            case SXTW -> (long) (int) rawRegisterValue;
            case SXTX -> rawRegisterValue;
        };
    }

    /// `CSEL`/`CSINC`/`CSINV`/`CSNEG` (B6.3.2) — só LÊ os flags via {@link
    /// dev.vitorsilverio.armjitter.core64.PstateRegister#evalCond} para escolher entre `src1` e
    /// `f(src2)`; NUNCA os atualiza (diferente de `executeAlu*`/`setFlags`). Sem atalho para
    /// `CSET`/`CSETM`/`CINC`/`CINV`/`CNEG` (Armadilhas da task) — o caminho geral com
    /// `src1==src2==XZR` já produz o resultado correto.
    private boolean executeConditionalSelect(Aarch64Core core, Ir64Op.ConditionalSelect op) {
        long result;
        if (core.pstate().evalCond(op.condition())) {
            result = core.xForWidth(op.src1(), op.wide());
        } else {
            long src2 = core.xForWidth(op.src2(), op.wide());
            result = switch (op.opcode()) {
                case CSEL -> src2;
                case CSINC -> src2 + 1;
                case CSINV -> ~src2;
                case CSNEG -> -src2;
            };
        }
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `CCMP`/`CCMN` (B6.8) — D2 da task: reaproveita {@link #addWithFlags}/{@link
    /// #subWithFlags}, o MESMO cálculo já usado por {@link #executeAluShiftedRegister}, em vez de
    /// duplicar a lógica de carry/overflow. Quando {@link Ir64Op.ConditionalCompare#condition} é
    /// falsa, `NZCV` recebe os 4 bits CRUS do encoding (`core.pstate().setNzcv(int)`) e {@link
    /// Ir64Op.ConditionalCompare#rn}/{@link Ir64Op.ConditionalCompare#rm} NUNCA são lidos — prova
    /// de que a implementação de fato ramifica em vez de sempre calcular e descartar (Armadilhas
    /// da task, Testes mínimos #2). Nunca escreve registrador (só `NZCV`, ver Javadoc do record).
    private boolean executeConditionalCompare(Aarch64Core core, Ir64Op.ConditionalCompare op) {
        if (core.pstate().evalCond(op.condition())) {
            long operand1 = core.xForWidth(op.rn(), op.wide());
            long operand2 = op.immediateForm() ? op.immediate() : core.xForWidth(op.rm(), op.wide());
            AluResult result = op.opcode() == Ir64AluOp.SUB
                    ? subWithFlags(operand1, operand2, op.wide())
                    : addWithFlags(operand1, operand2, op.wide());
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        } else {
            core.pstate().setNzcv(op.nzcv());
        }
        return false;
    }

    /// `ADC`/`ADCS`/`SBC`/`SBCS` (B8.2) — soma/subtrai COM o `C` de entrada atual (diferente de
    /// {@link #executeAlu}/{@link #executeAluShiftedRegister}, que nunca leem `C` como entrada).
    /// `SBC` é `AddWithCarry(a, NOT(b), C)` (`ARM DDI 0487` pseudocódigo de `SBC`) — reaproveita
    /// {@link #addWithCarryFlags} invertendo `b` bit a bit em vez de duplicar o cálculo.
    private boolean executeAluWithCarry(Aarch64Core core, Ir64Op.AluWithCarry op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long rawOperand2 = core.xForWidth(op.src2(), op.wide());
        long operand2 = op.subtract() ? ~rawOperand2 : rawOperand2;
        // `PSTATE.C` de ENTRADA (não forçado) — mesmo para SBC: `a - b - (1-C)`, ou seja,
        // `AddWithCarry(a, NOT(b), C)` com o `C` ATUAL, nunca `1` fixo (armadilha real encontrada
        // nesta task: um `carryIn` forçado para `SBC` produziria sempre `a-b` sem nunca propagar
        // borrow entre instruções encadeadas de precisão múltipla).
        boolean carryIn = core.pstate().carry();
        AluResult result = addWithCarryFlags(operand1, operand2, carryIn, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        core.setXForWidth(op.dst(), result.value, op.wide());
        return false;
    }

    /// `EXTR` (B8.2) — concatena {@link Ir64Op.Extract#src1}`:`{@link Ir64Op.Extract#src2} (`Rn`
    /// na metade ALTA) e extrai uma janela do tamanho da operação a partir do bit
    /// {@link Ir64Op.Extract#lsb}. `lsb=0` é um caso especial explícito (evita deslocamento por
    /// `64`/`32`, que em Java é UB — `x << 64` não é zero, é `x << 0`, mod-largura do shift).
    private boolean executeExtract(Aarch64Core core, Ir64Op.Extract op) {
        long high = core.xForWidth(op.src1(), op.wide());
        long low = core.xForWidth(op.src2(), op.wide());
        int lsb = op.lsb();
        long result;
        if (lsb == 0) {
            result = low;
        } else if (op.wide()) {
            result = (low >>> lsb) | (high << (Long.SIZE - lsb));
        } else {
            int narrowHigh = (int) high;
            int narrowLow = (int) low;
            int narrowResult = (narrowLow >>> lsb) | (narrowHigh << (Integer.SIZE - lsb));
            result = narrowResult & LOW_32_BITS_MASK;
        }
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `RBIT`/`REV16`/`REV`(`W`)/`REV32`(`X`)/`REV64`/`CLZ`/`CLS`/`CNT` (B8.2). Nenhuma forma
    /// afeta `NZCV`.
    private boolean executeDataProcessing1Source(Aarch64Core core, Ir64Op.DataProcessing1Source op) {
        long src = core.xForWidth(op.src(), op.wide());
        long result = switch (op.opcode()) {
            case RBIT -> op.wide()
                    ? Long.reverse(src)
                    : Integer.reverse((int) src) & LOW_32_BITS_MASK;
            case REV16 -> reverseHalfwordBytes(src, op.wide());
            case REV32 -> reverseWordBytes(src, op.wide());
            case REV64 -> Long.reverseBytes(src);
            case CLZ -> op.wide() ? Long.numberOfLeadingZeros(src) : Integer.numberOfLeadingZeros((int) src);
            case CLS -> countLeadingSignBits(src, op.wide());
            case CNT -> op.wide() ? Long.bitCount(src) : Integer.bitCount((int) src);
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `REV16` de A64 (B8.2): inverte a ordem dos BYTES dentro de cada halfword de 16 bits do
    /// registrador — diferente do `REV16` de 32 bits do ARM32 ({@code AsmRuntimeHelpers}), que só
    /// tem 1 halfword.
    private static long reverseHalfwordBytes(long value, boolean wide) {
        int halfwordCount = wide ? Long.BYTES / Short.BYTES : Integer.BYTES / Short.BYTES;
        long result = 0;
        for (int i = 0; i < halfwordCount; i++) {
            int shift = i * Short.SIZE;
            long halfword = (value >>> shift) & HALFWORD_MASK;
            long swapped = ((halfword & BYTE_MASK) << Byte.SIZE) | ((halfword >>> Byte.SIZE) & BYTE_MASK);
            result |= swapped << shift;
        }
        return wide ? result : (result & LOW_32_BITS_MASK);
    }

    /// `REV`(`W`, 1 palavra)/`REV32`(`X`, 2 palavras) de A64 (B8.2): inverte a ordem dos BYTES
    /// DENTRO de cada palavra de 32 bits, mantendo a ORDEM das palavras — MESMO opcode do
    /// encoding para as duas larguras (ver {@code Ir64OneSourceOp#REV32}).
    private static long reverseWordBytes(long value, boolean wide) {
        if (!wide) {
            return Integer.reverseBytes((int) value) & LOW_32_BITS_MASK;
        }
        long lowWordReversed = Integer.reverseBytes((int) value) & LOW_32_BITS_MASK;
        long highWordReversed = Integer.reverseBytes((int) (value >>> Integer.SIZE)) & LOW_32_BITS_MASK;
        return (highWordReversed << Integer.SIZE) | lowWordReversed;
    }

    /// `CLS` (B8.2): conta bits à esquerda IGUAIS ao bit de sinal, SEM contar o próprio bit de
    /// sinal — `numberOfLeadingZeros(x XOR (x >> 1 aritmético)) - 1` (XOR de cada bit com seu
    /// vizinho mais significativo marca em `1` a primeira posição onde o valor DIFERE do sinal;
    /// `numberOfLeadingZeros` disso, menos o `1` que descarta o próprio bit de sinal, é exatamente
    /// a contagem pedida — mesma técnica de `CountLeadingSignBits` do `ARM DDI 0487` pseudocódigo).
    private static long countLeadingSignBits(long value, boolean wide) {
        if (wide) {
            long diffFromSign = value ^ (value >> 1);
            return Long.numberOfLeadingZeros(diffFromSign) - 1;
        }
        int narrow = (int) value;
        int diffFromSign = narrow ^ (narrow >> 1);
        return Integer.numberOfLeadingZeros(diffFromSign) - 1;
    }

    /// `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` (B8.2) — multiplicação 32×32→64 (sempre exata em
    /// `long`, o produto de dois valores de magnitude `<=2^32` nunca ultrapassa os 63 bits úteis
    /// de um `long` assinado) com acumulador de 64.
    private boolean executeMultiplyAccumulateLong(Aarch64Core core, Ir64Op.MultiplyAccumulateLong op) {
        long n = op.signed() ? (long) (int) core.x(op.src1()) : core.x(op.src1()) & LOW_32_BITS_MASK;
        long m = op.signed() ? (long) (int) core.x(op.src2()) : core.x(op.src2()) & LOW_32_BITS_MASK;
        long product = n * m;
        long accumulator = core.x(op.accumulator());
        long result = op.subtract() ? accumulator - product : accumulator + product;
        core.setX(op.dst(), result);
        return false;
    }

    /// `SMULH`/`UMULH` (B8.2) — os 64 bits ALTOS do produto de 128 bits de dois `X`. `Math`
    /// carrega os dois intrínsecos prontos desde o Java 18 (`multiplyHigh`/`unsignedMultiplyHigh`)
    /// — sem necessidade de decompor em meias-palavras manualmente.
    private boolean executeMultiplyHigh(Aarch64Core core, Ir64Op.MultiplyHigh op) {
        long a = core.x(op.src1());
        long b = core.x(op.src2());
        long result = op.signed() ? Math.multiplyHigh(a, b) : Math.unsignedMultiplyHigh(a, b);
        core.setX(op.dst(), result);
        return false;
    }

    /// `SETF8`/`SETF16` (B8.2, "Evaluate into flags") — avalia o campo BAIXO de {@link
    /// Ir64Op.EvaluateIntoFlags#rn} como se fosse o resultado de uma soma: `N`=bit de sinal do
    /// campo, `Z`=campo zero, `V`=bit de sinal XOR o bit logo abaixo (`ARM DDI 0487`
    /// pseudocódigo de `SETF8`/`SETF16`). `C` NUNCA muda — por isso {@link
    /// dev.vitorsilverio.armjitter.core64.PstateRegister#carry()} é relido e devolvido como está.
    private boolean executeEvaluateIntoFlags(Aarch64Core core, Ir64Op.EvaluateIntoFlags op) {
        long full = core.x(op.rn());
        int sizeBits = op.sizeBits();
        long fieldMask = (1L << sizeBits) - 1;
        long value = full & fieldMask;
        int signBitPosition = sizeBits - 1;
        boolean negative = ((value >>> signBitPosition) & 1) != 0;
        boolean zero = value == 0;
        boolean nextBit = ((value >>> (signBitPosition - 1)) & 1) != 0;
        boolean overflow = negative != nextBit;
        core.pstate().setNzcv(negative, zero, core.pstate().carry(), overflow);
        return false;
    }

    /// `RMIF` (B8.2, "Rotate right into flags") — rotaciona {@link Ir64Op.RotateIntoFlags#rn}
    /// para a direita por {@link Ir64Op.RotateIntoFlags#shift} bits e atualiza só os flags cujo
    /// bit correspondente está setado em {@link Ir64Op.RotateIntoFlags#mask} — os 4 bits baixos
    /// do valor rotacionado usam a MESMA ordem `N:Z:C:V` do formato bruto de
    /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#nzcv()}, então nenhuma
    /// reordenação de bit é necessária entre "candidato" e "máscara".
    private boolean executeRotateIntoFlags(Aarch64Core core, Ir64Op.RotateIntoFlags op) {
        long source = core.x(op.rn());
        int candidate = (int) (Long.rotateRight(source, op.shift()) & NZCV_FIELD_MASK);
        int mask = op.mask();
        int currentNzcv = core.pstate().nzcv();
        int updatedNzcv = (currentNzcv & ~mask) | (candidate & mask);
        core.pstate().setNzcv(updatedNzcv);
        return false;
    }

    /// `CFINV`/`XAFLAG`/`AXFLAG` (B8.2, `FEAT_FlagM2`) — `XAFLAG`/`AXFLAG` seguem o pseudocódigo
    /// do `ARM DDI 0487` para conversão de flags "eXternal"↔"Arm" (usadas por sequências
    /// vetoriais de comparação lane-a-lane reduzidas a um resultado escalar).
    private boolean executeConvertFlags(Aarch64Core core, Ir64Op.ConvertFlags op) {
        var pstate = core.pstate();
        switch (op.opcode()) {
            case INVERT_CARRY ->
                    pstate.setNzcv(pstate.negative(), pstate.zero(), !pstate.carry(), pstate.overflow());
            case EXTERNAL_TO_ARM -> {
                boolean oldZero = pstate.zero();
                boolean oldCarry = pstate.carry();
                pstate.setNzcv(false, oldZero && oldCarry, oldCarry && !oldZero, false);
            }
            case ARM_TO_EXTERNAL -> {
                boolean oldZero = pstate.zero();
                boolean oldCarry = pstate.carry();
                boolean oldOverflow = pstate.overflow();
                pstate.setNzcv(false, oldZero || oldOverflow, oldCarry && !oldOverflow, false);
            }
        }
        return false;
    }

    /// `SBFM`/`BFM`/`UBFM` (B6.3.2) — os 3 opcodes compartilham o MESMO cálculo de `pos`/`len`
    /// (dois casos, `imms >= immr` e `imms < immr`, ver Fatos de referência #2 da task); só a
    /// política de preenchimento dos bits FORA do campo copiado muda: `SBFM` estende o sinal do
    /// bit mais alto do campo, `UBFM` zera, `BFM` preserva o `Rd` existente (Armadilhas da task —
    /// erro mais fácil de trocar entre os três).
    private boolean executeBitfield(Aarch64Core core, Ir64Op.Bitfield op) {
        int bitsize = op.wide() ? BITFIELD_WIDE_BITSIZE : BITFIELD_NARROW_BITSIZE;
        long src = core.xForWidth(op.src(), op.wide());
        int immr = op.immr();
        int imms = op.imms();
        int len;
        int pos;
        long field;
        if (imms >= immr) {
            len = imms - immr + 1;
            pos = 0;
            field = extractBitfield(src, immr, len);
        } else {
            len = imms + 1;
            pos = bitsize - immr;
            field = extractBitfield(src, 0, len);
        }
        long result = switch (op.opcode()) {
            case UBFM -> field << pos;
            case SBFM -> signExtendBitfield(field, len) << pos;
            case BFM -> {
                long existing = core.xForWidth(op.dst(), op.wide());
                long fieldMask = maskOfBitfieldWidth(len) << pos;
                yield (existing & ~fieldMask) | (field << pos);
            }
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// Extrai `len` bits de {@code value} a partir de {@code shift}, zero-estendidos.
    private static long extractBitfield(long value, int shift, int len) {
        return (value >>> shift) & maskOfBitfieldWidth(len);
    }

    /// Máscara dos `bits` bits baixos — mesma disciplina de {@code Aarch64LogicalImmediate}
    /// (shift de 64 bits é UB por wraparound em Java, `bits == 64` é caso especial).
    private static long maskOfBitfieldWidth(int bits) {
        return bits >= Long.SIZE ? -1L : (1L << bits) - 1L;
    }

    /// Estende o sinal de um valor de `len` bits (bit `len - 1` é o sinal) para os 64 bits
    /// completos — usado só por `SBFM`.
    private static long signExtendBitfield(long field, int len) {
        if (len >= Long.SIZE) {
            return field;
        }
        long signBit = 1L << (len - 1);
        return (field ^ signBit) - signBit;
    }

    /// `MADD`/`MSUB` (B6.3.3) — sem atalho para `Ra==31` (D2 da task): o caminho geral já produz o
    /// resultado certo quando o acumulador é `XZR` (lê `0`), sem `if` especial. Cada operando-fonte
    /// é lido explicitamente via {@link Aarch64Core#xForWidth}, nunca confiando no invariante de
    /// zero-extensão do registrador cru (D2). A multiplicação/soma é feita em `long` puro — o
    /// overflow silencioso de `long*long`/`long+long` de Java já é módulo `2^64`, exatamente a
    /// truncagem exigida pela arquitetura; {@link Aarch64Core#setXForWidth} aplica a
    /// zero-extensão final quando `!wide`.
    private boolean executeMultiplyAccumulate(Aarch64Core core, Ir64Op.MultiplyAccumulate op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long operand2 = core.xForWidth(op.src2(), op.wide());
        long accumulator = core.xForWidth(op.accumulator(), op.wide());
        long product = operand1 * operand2;
        long result = op.subtract() ? (accumulator - product) : (accumulator + product);
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `SDIV`/`UDIV` (B6.3.3) — divisor `0` produz resultado `0` SEM lançar exceção (checado ANTES
    /// de dividir; Fatos de referência #2 da task/Armadilhas — o operador `/` de Java lançaria
    /// `ArithmeticException`, que não existe na arquitetura). `SDIV`/`Long.MIN_VALUE / -1` (ou o
    /// equivalente de 32 bits) truncam para o próprio `MIN_VALUE` sem lançar — mesma convenção de
    /// complemento-de-dois que a divisão inteira de Java já produz (só lança para divisor `0`).
    private boolean executeDivide(Aarch64Core core, Ir64Op.Divide op) {
        long dividend = readDivideOperand(core, op.src1(), op.signed(), op.wide());
        long divisor = readDivideOperand(core, op.src2(), op.signed(), op.wide());
        long quotient;
        if (divisor == 0L) {
            quotient = 0L;
        } else if (op.signed()) {
            quotient = op.wide() ? (dividend / divisor) : (long) ((int) dividend / (int) divisor);
        } else {
            quotient = op.wide()
                    ? Long.divideUnsigned(dividend, divisor)
                    : (long) Integer.divideUnsigned((int) dividend, (int) divisor);
        }
        core.setXForWidth(op.dst(), quotient, op.wide());
        return false;
    }

    /// Lê um operando de `SDIV`/`UDIV`: `SDIV` em `W` exige sign-extend EXPLÍCITO dos 32 bits
    /// baixos (Fatos de referência #2 — `n = sign_extend32(Rn_low32)`); as demais combinações
    /// (`UDIV` em `W`, e qualquer operação em `X`) já usam {@link Aarch64Core#xForWidth} normal
    /// (`UDIV` em `W` já vem zero-estendido "de graça", invariante do core).
    private static long readDivideOperand(Aarch64Core core, int index, boolean signed, boolean wide) {
        if (signed && !wide) {
            return (long) (int) core.xForWidth(index, false);
        }
        return core.xForWidth(index, wide);
    }

    private boolean executeMoveWide(Aarch64Core core, Ir64Op.MoveWide op) {
        long shiftedImmediate = ((long) op.immediate16() & 0xFFFFL) << op.shift();
        long result = switch (op.opcode()) {
            case MOVZ -> shiftedImmediate;
            case MOVN -> ~shiftedImmediate;
            case MOVK -> {
                long mask = 0xFFFFL << op.shift();
                long previous = core.xForWidth(op.dst(), op.wide());
                yield (previous & ~mask) | (shiftedImmediate & mask);
            }
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    private boolean executePcRelative(Aarch64Core core, Ir64Op.PcRelative op) {
        long base = op.page() ? (op.instructionAddress() & ~0xFFFL) : op.instructionAddress();
        core.setX(op.dst(), base + op.immediate());
        return false;
    }

    private boolean executeBranch(Aarch64Core core, Ir64Op.Branch64 op) {
        if (!core.pstate().evalCond(op.condition())) {
            return false;
        }
        long target = switch (op.form()) {
            case IMMEDIATE -> op.target();
            case REGISTER -> core.x(op.registerOperand());
        };
        if (op.link()) {
            core.setX(LINK_REGISTER, op.instructionAddress() + Aarch64Decoder.instructionSizeBytes());
        }
        core.setProgramCounter(target);
        return true;
    }

    private boolean executeCompareBranch(Aarch64Core core, Ir64Op.CompareBranch64 op) {
        boolean conditionMet = switch (op.form()) {
            case CBZ_CBNZ -> {
                long value = core.xForWidth(op.rn(), op.wide());
                yield op.branchIfNonZero() ? value != 0 : value == 0;
            }
            case TBZ_TBNZ -> {
                long bit = (core.x(op.rn()) >>> op.bitPosition()) & 1L;
                yield op.branchIfNonZero() ? bit != 0 : bit == 0;
            }
        };
        if (!conditionMet) {
            return false;
        }
        core.setProgramCounter(op.target());
        return true;
    }

    private boolean executeSvc(Aarch64Core core, Ir64Op.Svc op) {
        core.svcHandler().handle(core, op.immediate());
        return false;
    }

    private boolean executeLoad(Aarch64Core core, Ir64Op.Load64 op) {
        long base = readBaseRegister(core, op.rn());
        long address = transferAddress(core, base, op.addressingMode(), op.immediate(),
                op.rm(), op.extendType(), op.shiftAmount());
        long raw = readMemory(core, address, op.size());
        long value = op.signExtend() ? signExtendFromSize(raw, op.size()) : raw;
        core.setXForWidth(op.rt(), value, op.wide());
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    private boolean executeStore(Aarch64Core core, Ir64Op.Store64 op) {
        long base = readBaseRegister(core, op.rn());
        long address = transferAddress(core, base, op.addressingMode(), op.immediate(),
                op.rm(), op.extendType(), op.shiftAmount());
        long value = core.xForWidth(op.rt(), op.wide());
        writeMemory(core, address, op.size(), value);
        // B6.3.4: escrita comum que sobrepõe uma reserva pendente de LDXR/LDAXR abre o monitor
        // (mesma disciplina de STR/STRH/STRB de 32 bits em ArmCore — auditoria explícita da
        // Especificação #2 da task, sem esta chamada o teste de notifyOrdinaryWrite falha).
        core.notifyOrdinaryWrite(address, op.size().bytes());
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// `LDXR`/`LDAXR` (B6.3.4): lê a memória em `rn`+0 (sem deslocamento — a forma exclusiva não
    /// tem imediato) e marca o monitor de exclusividade com `(endereço, size.bytes())`.
    /// `acquireRelease` é NOP observável no interpretador (ver {@link Ir64Op.LoadExclusive}
    /// javadoc) — carregado no IR só para um futuro emissor nativo.
    private boolean executeLoadExclusive(Aarch64Core core, Ir64Op.LoadExclusive op) {
        long address = readBaseRegister(core, op.rn());
        long value = readMemory(core, address, op.size());
        core.markExclusiveMonitor(address, op.size().bytes());
        core.setXForWidth(op.rt(), value, op.size() == Ir64MemSize.DOUBLEWORD);
        return false;
    }

    /// `STXR`/`STLXR` (B6.3.4): consulta o monitor ANTES de qualquer escrita — armadilha crítica
    /// espelhada de `STREX` (B1.4): um `STXR`/`STLXR` que falha NÃO pode ter efeito colateral de
    /// memória. Sucesso escreve `rt`, grava `0` em `rs` e consome a reserva; falha grava `1` em
    /// `rs` com a memória intacta. `acquireRelease` é NOP observável (ver
    /// {@link Ir64Op.StoreExclusive} javadoc).
    private boolean executeStoreExclusive(Aarch64Core core, Ir64Op.StoreExclusive op) {
        long address = readBaseRegister(core, op.rn());
        if (!core.exclusiveMonitorCovers(address, op.size().bytes())) {
            core.setXForWidth(op.rs(), 1L, false);
            return false;
        }
        long value = core.xForWidth(op.rt(), op.size() == Ir64MemSize.DOUBLEWORD);
        writeMemory(core, address, op.size(), value);
        core.setXForWidth(op.rs(), 0L, false);
        return false;
    }

    /// `LDXP`/`LDAXP` (B8.1): mesmo espírito de {@link #executeLoadExclusive}, mas marca o
    /// monitor cobrindo os DOIS slots (`2 × size.bytes()`, `size` = `WORD`/`DOUBLEWORD` conforme
    /// {@link Ir64Op.LoadExclusivePair#wide}).
    private boolean executeLoadExclusivePair(Aarch64Core core, Ir64Op.LoadExclusivePair op) {
        long address = readBaseRegister(core, op.rn());
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        int stride = size.bytes();
        long first = readMemory(core, address, size);
        long second = readMemory(core, address + stride, size);
        core.markExclusiveMonitor(address, stride * 2);
        core.setXForWidth(op.rt(), first, op.wide());
        core.setXForWidth(op.rt2(), second, op.wide());
        return false;
    }

    /// `STXP`/`STLXP` (B8.1): consulta o monitor ANTES de qualquer escrita — mesma armadilha
    /// crítica de {@link #executeStoreExclusive}, aplicada aos DOIS slots do par.
    private boolean executeStoreExclusivePair(Aarch64Core core, Ir64Op.StoreExclusivePair op) {
        long address = readBaseRegister(core, op.rn());
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        int stride = size.bytes();
        if (!core.exclusiveMonitorCovers(address, stride * 2)) {
            core.setXForWidth(op.rs(), 1L, false);
            return false;
        }
        writeMemory(core, address, size, core.xForWidth(op.rt(), op.wide()));
        writeMemory(core, address + stride, size, core.xForWidth(op.rt2(), op.wide()));
        core.setXForWidth(op.rs(), 0L, false);
        return false;
    }

    /// `CAS`/`CASA`/`CASL`/`CASAL` (B8.1) — semântica de `CMPXCHG`: lê `[Rn]`, compara com `Rs`
    /// (truncado para {@code size}); se igual, escreve `Rt`; SEMPRE grava o valor antigo lido em
    /// `Rs` (zero-estendido). Interpretador single-thread por construção — não precisa de CAS
    /// real de host (ver javadoc de {@link Ir64Op.CompareAndSwap}).
    private boolean executeCompareAndSwap(Aarch64Core core, Ir64Op.CompareAndSwap op) {
        long address = readBaseRegister(core, op.rn());
        boolean wide = op.size() == Ir64MemSize.DOUBLEWORD;
        long current = readMemory(core, address, op.size());
        long expected = zeroTruncateToSize(core.xForWidth(op.rs(), wide), op.size());
        if (current == expected) {
            writeMemory(core, address, op.size(), core.xForWidth(op.rt(), wide));
            core.notifyOrdinaryWrite(address, op.size().bytes());
        }
        core.setXForWidth(op.rs(), current, wide);
        return false;
    }

    /// `CASP`/`CASPA`/`CASPL`/`CASPAL` (B8.1) — versão em par de {@link #executeCompareAndSwap}:
    /// compara `(Rs,Rs+1)` contra `[Rn]`/`[Rn+size]`; se AMBOS baterem, escreve `(Rt,Rt+1)`;
    /// sempre grava o par antigo lido em `(Rs,Rs+1)`. O companheiro é `rs|1`/`rt|1` (não `+1` —
    /// ver javadoc de {@link Ir64Op.CompareAndSwapPair}).
    private boolean executeCompareAndSwapPair(Aarch64Core core, Ir64Op.CompareAndSwapPair op) {
        long address = readBaseRegister(core, op.rn());
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        int stride = size.bytes();
        int rs2 = op.rs() | 1;
        int rt2 = op.rt() | 1;
        long currentLow = readMemory(core, address, size);
        long currentHigh = readMemory(core, address + stride, size);
        long expectedLow = core.xForWidth(op.rs(), op.wide());
        long expectedHigh = core.xForWidth(rs2, op.wide());
        if (currentLow == expectedLow && currentHigh == expectedHigh) {
            writeMemory(core, address, size, core.xForWidth(op.rt(), op.wide()));
            writeMemory(core, address + stride, size, core.xForWidth(rt2, op.wide()));
            core.notifyOrdinaryWrite(address, stride);
            core.notifyOrdinaryWrite(address + stride, stride);
        }
        core.setXForWidth(op.rs(), currentLow, op.wide());
        core.setXForWidth(rs2, currentHigh, op.wide());
        return false;
    }

    /// `MRS`/`MSR (register)` (B6.6.1) — delega ao {@link Aarch64SystemRegisterBus} instalado
    /// (D2 da task); registrador sem hospedeiro que o {@link Aarch64SystemRegisterBus#handles}
    /// devolve `false` lança {@link UnsupportedOperationException} aqui mesmo (mesmo padrão de
    /// "sem hospedeiro" de {@link Aarch64SystemRegisterBus#none()} — checado explicitamente antes
    /// de chamar `read`/`write`, não só confiando na exceção default deles). Não existe forma
    /// `W` (ver {@link Ir64Op.SystemRegister} javadoc): `MRS` sempre grava `X` completo via
    /// {@link Aarch64Core#setX}; `MSR` sempre lê `X` completo via {@link Aarch64Core#x} (que já
    /// devolve `0` para `rt == 31`, `XZR`).
    private boolean executeSystemRegister(Aarch64Core core, Ir64Op.SystemRegister op) {
        // B6.6.7: identidades da CPU (CurrentEL/MPIDR_EL1/MIDR_EL1/ID_AA64*/TPIDR_EL1) são
        // resolvidas DIRETO pelo core, sem passar pelo `Aarch64SystemRegisterBus` — checado
        // primeiro, mesmo quando um hospedeiro real está instalado (essas identidades nunca são
        // responsabilidade do hospedeiro, ver javadoc de `Aarch64SystemRegisterId`).
        if (core.handlesSystemRegisterIntrinsically(op.register())) {
            if (op.read()) {
                core.setX(op.rt(), core.readIntrinsicSystemRegister(op.register()));
            } else {
                core.writeIntrinsicSystemRegister(op.register(), core.x(op.rt()));
            }
            return false;
        }
        Aarch64SystemRegisterBus bus = core.systemRegisterBus();
        if (!bus.handles(op.register())) {
            throw new UnsupportedOperationException(
                    "AArch64: registrador de sistema sem hospedeiro instalado: " + op.register());
        }
        if (op.read()) {
            core.setX(op.rt(), bus.read(op.register()));
        } else {
            bus.write(op.register(), core.x(op.rt()));
        }
        return false;
    }

    /// `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` (B10.6) — delega inteiramente ao
    /// {@link Aarch64SystemRegisterBus} instalado; um barramento sem MMU (default, sem
    /// {@link Aarch64SystemRegisterBus#handles} — o método nem checa isso, ao contrário de
    /// {@link #executeSystemRegister}) lança {@link UnsupportedOperationException} direto do default
    /// de {@link Aarch64SystemRegisterBus#addressTranslate}. `rt=31` (`XZR`) lê `0` via
    /// {@link Aarch64Core#x}, mesma convenção de {@link Ir64Op.SystemRegister}. Sem escrita em
    /// registrador geral: o resultado vai só para `PAR_EL1`, dentro do bus.
    private boolean executeAddressTranslate(Aarch64Core core, Ir64Op.AddressTranslate op) {
        long va = core.x(op.rt());
        core.systemRegisterBus().addressTranslate(op.form(), va);
        return false;
    }

    /// `TLBI VMALLE1`/`TLBI VMALLE1IS`/`DSB`/`ISB`/`DMB` (B6.6.3): a barreira é sempre NOP; `TLBI`
    /// delega em {@link Aarch64SystemRegisterBus#invalidateTlbAll()} — sem checar
    /// {@link Aarch64SystemRegisterBus#handles} (diferente de {@link #executeSystemRegister}),
    /// já que o método tem default NOP no barramento vazio (mesma disciplina "sem hospedeiro =
    /// sem TLB para invalidar", não uma falta arquitetural).
    private boolean executeSystemInstruction(Aarch64Core core, Ir64Op.SystemInstruction op) {
        switch (op.opcode()) {
            case TLBI_ALL -> core.systemRegisterBus().invalidateTlbAll();
            case BARRIER, NOP_HINT, CACHE_MAINTENANCE_NOP, PSTATE_FIELD_NOP -> {
                /* NOP observável — sem cache/pipeline/event-stream/campo de PSTATE modelado. */
            }
            case WFI -> core.setSleepState(CpuSleepState.HALTED);
            case CLEAR_EXCLUSIVE -> core.clearExclusiveMonitor();
        }
        return false;
    }

    /// `MSR (immediate) DAIFSet`/`DAIFClr` (B8.3) — só o bit `I` de `DAIF` tem efeito neste
    /// emulador (`D`/`A`/`F` ignorados, ver javadoc de {@link Ir64Op.InterruptMask}).
    private boolean executeInterruptMask(Aarch64Core core, Ir64Op.InterruptMask op) {
        if ((op.mask() & DAIF_MASK_BIT_I) != 0) {
            core.pstate().setIrqDisabled(op.set());
        }
        return false;
    }

    /// `BRK` (B8.3) — sempre lança, capturado por {@link #step}/{@link #executeBlock} no MESMO
    /// ponto que {@link MemoryTranslationException64} (ver os `catch` ali).
    private boolean executeBreakpoint(Ir64Op.Breakpoint op) {
        throw new Aarch64BreakpointException(op.immediate());
    }

    /// `HLT` sem estado de debug (B8.3) — mesmo contrato de {@link #executeBreakpoint}.
    private boolean executeUndefinedInstructionTrap() {
        throw new Aarch64UndefinedInstructionException();
    }

    /// `HVC`/`SMC` (B10.4/B10.5): sempre lança, capturada por {@link #step}/{@link #executeBlock}
    /// no MESMO ponto que {@link Aarch64BreakpointException}/{@link Aarch64UndefinedInstructionException}
    /// (precisa do endereço da própria instrução, só disponível ali — mesmo motivo de
    /// {@link #executeBreakpoint}). O antigo stub `PSCI_RET_NOT_SUPPORTED` de `SMC` (pré-B10.5)
    /// foi REMOVIDO — `SMC` agora entra em EL3 de verdade via
    /// {@link Aarch64Core#enterSecureMonitorCall}, mesmo mecanismo de `HVC`/
    /// {@link Aarch64Core#enterHypervisorCall}.
    private boolean executePrivilegedCall(Ir64Op.PrivilegedCall op) {
        if (op.isHvc()) {
            throw new Aarch64HypervisorCallException();
        }
        throw new Aarch64SecureMonitorCallException();
    }

    /// `ERET` (B6.6.4, e B6.6.7 para o bit `I`; generalizado em B10.1 para os 4 níveis): `PC←
    /// ELR_ELx`, `PSTATE.{N,Z,C,V,I}←SPSR_ELx`, sai para o nível codificado em `SPSR_ELx.M[3:0]`
    /// (`x` é o nível ATUAL — `ERET` sempre lê o PRÓPRIO banco de quem o executa, nunca o de um
    /// nível fixo; o nível de DESTINO não é sempre "um abaixo do atual", ver
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel#fromSpsrValue}) — mesma
    /// ordem do precedente 32-bit (`SUBS PC,LR,#8` equivalente, mas automático aqui: A64 não
    /// precisa de subtração porque `ELR_ELx` já é o endereço exato de retomada, sem o viés `+4`/
    /// `+8` do LR bancado do ARM32).
    private boolean executeExceptionReturn(Aarch64Core core, Ir64Op.ExceptionReturn op) {
        Aarch64ExceptionState exceptionState = core.exceptionState();
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        long returnAddress = exceptionState.elr(source);
        long rawSpsr = exceptionState.spsr(source);
        core.pstate().setFromSpsrFormat(rawSpsr);
        exceptionState.setCurrentEl(Aarch64ExceptionLevel.fromSpsrValue(rawSpsr));
        core.setProgramCounter(returnAddress);
        return true;
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store MULTIPLE structures, B8.6) — semântica conferida
    /// contra `trans_LD_mult`/`trans_ST_mult` reais do QEMU, ver {@link Ir64Op.VectorLoadStoreMultiple}.
    private boolean executeVectorLoadStoreMultiple(Aarch64Core core, Ir64Op.VectorLoadStoreMultiple op) {
        long base = readBaseRegister(core, op.rn());
        long address = base;
        Ir64MemSize size = memSizeForElementLog2(op.elementSizeLog2());
        int elementBytes = 1 << op.elementSizeLog2();
        int elementsPerRegister = (op.q() ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES)
                >> op.elementSizeLog2();
        Aarch64FpRegisters fp = core.fp();
        for (int r = 0; r < op.rpt(); r++) {
            for (int e = 0; e < elementsPerRegister; e++) {
                for (int xs = 0; xs < op.selem(); xs++) {
                    int register = (op.rt() + r + xs) % Aarch64FpRegisters.V_REGISTER_COUNT;
                    if (op.load()) {
                        fp.setElement(register, e, op.elementSizeLog2(), readMemory(core, address, size));
                    } else {
                        writeMemory(core, address, size, fp.element(register, e, op.elementSizeLog2()));
                        core.notifyOrdinaryWrite(address, elementBytes);
                    }
                    address += elementBytes;
                }
            }
        }
        if (op.load() && !op.q()) {
            // "SIMD&FP destructive write" (B6.5.1 D3): forma não-quad só escreveu os 64 bits
            // baixos de cada registrador tocado — os altos precisam ser zerados explicitamente
            // (o loop principal, espelhando o QEMU real, faz isso numa passada separada DEPOIS da
            // cópia, sobre os `rpt*selem` registradores distintos — nunca há sobreposição, porque
            // `rpt>1` só ocorre quando `selem=1` e vice-versa).
            for (int r = 0; r < op.rpt() * op.selem(); r++) {
                int register = (op.rt() + r) % Aarch64FpRegisters.V_REGISTER_COUNT;
                fp.setQ(register, fp.low64(register), 0L);
            }
        }
        long total = (long) op.rpt() * op.selem()
                * (op.q() ? Aarch64FpRegisters.QUADWORD_BYTES : Aarch64FpRegisters.DOUBLEWORD_BYTES);
        writeVectorPostIndex(core, op.rn(), op.postIndex(), op.rm(), base, total);
        return false;
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store SINGLE structure, sem replicar, B8.6) —
    /// semântica conferida contra `trans_LD_single`/`trans_ST_single` reais do QEMU, ver
    /// {@link Ir64Op.VectorLoadStoreSingle}.
    private boolean executeVectorLoadStoreSingle(Aarch64Core core, Ir64Op.VectorLoadStoreSingle op) {
        long base = readBaseRegister(core, op.rn());
        long address = base;
        Ir64MemSize size = memSizeForElementLog2(op.elementSizeLog2());
        int elementBytes = 1 << op.elementSizeLog2();
        Aarch64FpRegisters fp = core.fp();
        for (int xs = 0; xs < op.selem(); xs++) {
            int register = (op.rt() + xs) % Aarch64FpRegisters.V_REGISTER_COUNT;
            if (op.load()) {
                fp.setElement(register, op.index(), op.elementSizeLog2(), readMemory(core, address, size));
            } else {
                writeMemory(core, address, size, fp.element(register, op.index(), op.elementSizeLog2()));
                core.notifyOrdinaryWrite(address, elementBytes);
            }
            address += elementBytes;
        }
        long total = (long) op.selem() * elementBytes;
        writeVectorPostIndex(core, op.rn(), op.postIndex(), op.rm(), base, total);
        return false;
    }

    /// `LD1R`-`LD4R` (AdvSIMD load single structure and replicate, B8.6) — semântica conferida
    /// contra `trans_LD_single_repl` real do QEMU, ver {@link Ir64Op.VectorLoadSingleReplicate}.
    private boolean executeVectorLoadSingleReplicate(Aarch64Core core, Ir64Op.VectorLoadSingleReplicate op) {
        long base = readBaseRegister(core, op.rn());
        long address = base;
        Ir64MemSize size = memSizeForElementLog2(op.elementSizeLog2());
        int elementBytes = 1 << op.elementSizeLog2();
        Aarch64FpRegisters fp = core.fp();
        for (int xs = 0; xs < op.selem(); xs++) {
            int register = (op.rt() + xs) % Aarch64FpRegisters.V_REGISTER_COUNT;
            fp.replicateElement(register, readMemory(core, address, size), op.elementSizeLog2(), op.q());
            address += elementBytes;
        }
        long total = (long) op.selem() * elementBytes;
        writeVectorPostIndex(core, op.rn(), op.postIndex(), op.rm(), base, total);
        return false;
    }

    /// Escrita de volta pós-índice compartilhada pelas 3 formas de AdvSIMD load/store (B8.6):
    /// `rm=-1` (sentinela do decoder) é o pós-índice IMEDIATO (avança `total` bytes, o tamanho
    /// inteiro transferido pela instrução); qualquer outro valor é um registrador `X` real.
    private static void writeVectorPostIndex(Aarch64Core core, int rn, boolean postIndex, int rm, long base,
            long total) {
        if (!postIndex) {
            return;
        }
        long newBase = rm == -1 ? base + total : base + core.x(rm);
        writeBaseRegister(core, rn, newBase);
    }

    private static Ir64MemSize memSizeForElementLog2(int sizeLog2) {
        return switch (sizeLog2) {
            case 0 -> Ir64MemSize.BYTE;
            case 1 -> Ir64MemSize.HALF;
            case 2 -> Ir64MemSize.WORD;
            case 3 -> Ir64MemSize.DOUBLEWORD;
            default -> throw new IllegalStateException("elementSizeLog2 inválido: " + sizeLog2);
        };
    }

    private boolean executeLoadStorePair(Aarch64Core core, Ir64Op.LoadStorePair op) {
        long base = readBaseRegister(core, op.rn());
        long address = op.addressingMode() == Ir64AddressingMode.POST_INDEX
                ? base : base + op.immediate();
        // LDPSW (B8.1): sempre transfere pares de WORD, mesmo escrevendo em X — ver javadoc de
        // Ir64Op.LoadStorePair#signExtend.
        int stride = (op.wide() && !op.signExtend()) ? PAIR_DOUBLEWORD_STRIDE_BYTES : PAIR_WORD_STRIDE_BYTES;
        Ir64MemSize size = (op.wide() && !op.signExtend()) ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        if (op.load()) {
            long first = readMemory(core, address, size);
            long second = readMemory(core, address + stride, size);
            if (op.signExtend()) {
                first = signExtendFromSize(first, size);
                second = signExtendFromSize(second, size);
                core.setX(op.rt(), first);
                core.setX(op.rt2(), second);
            } else {
                core.setXForWidth(op.rt(), first, op.wide());
                core.setXForWidth(op.rt2(), second, op.wide());
            }
        } else {
            writeMemory(core, address, size, core.xForWidth(op.rt(), op.wide()));
            writeMemory(core, address + stride, size, core.xForWidth(op.rt2(), op.wide()));
            // B6.3.4: STP também é escrita comum — mesma auditoria de executeStore.
            core.notifyOrdinaryWrite(address, size.bytes());
            core.notifyOrdinaryWrite(address + stride, size.bytes());
        }
        if (op.addressingMode() == Ir64AddressingMode.PRE_INDEX
                || op.addressingMode() == Ir64AddressingMode.POST_INDEX) {
            writeBaseRegister(core, op.rn(), base + op.immediate());
        }
        return false;
    }

    private boolean executeLoadLiteral(Aarch64Core core, Ir64Op.LoadLiteral64 op) {
        long value;
        if (op.signExtend()) {
            // LDRSW (literal): única forma com sinal — sempre lê 32 bits e estende para X.
            value = (long) (int) Integer.toUnsignedLong(core.memory().read32(op.address()));
        } else if (op.wide()) {
            value = core.memory().read64(op.address());
        } else {
            value = Integer.toUnsignedLong(core.memory().read32(op.address()));
        }
        core.setX(op.rt(), value);
        return false;
    }

    /// Lê o registrador BASE de um load/store — sempre `SP` quando o campo de encoding é `31`
    /// (nunca `XZR`, ver {@link Ir64Op.Load64#rn}).
    private static long readBaseRegister(Aarch64Core core, int rn) {
        return rn == BASE_REGISTER_SP_ENCODING ? core.sp() : core.x(rn);
    }

    private static void writeBaseRegister(Aarch64Core core, int rn, long value) {
        if (rn == BASE_REGISTER_SP_ENCODING) {
            core.setSp(value);
        } else {
            core.setX(rn, value);
        }
    }

    private static long transferAddress(Aarch64Core core, long base, Ir64AddressingMode mode,
            long immediate, int rm, Ir64ExtendType extendType, int shiftAmount) {
        return switch (mode) {
            case OFFSET, PRE_INDEX -> base + immediate;
            case POST_INDEX -> base;
            case REGISTER_OFFSET -> base + extendRegisterOffset(core, rm, extendType, shiftAmount);
        };
    }

    private static long extendRegisterOffset(Aarch64Core core, int rm, Ir64ExtendType extendType, int shiftAmount) {
        long extended = switch (extendType) {
            case UXTW -> core.xForWidth(rm, false);
            case SXTW -> (long) (int) core.xForWidth(rm, false);
            case LSL, SXTX -> core.x(rm);
        };
        return extended << shiftAmount;
    }

    private static void writeback(Aarch64Core core, int rn, Ir64AddressingMode mode, long base, long immediate) {
        if (mode == Ir64AddressingMode.PRE_INDEX || mode == Ir64AddressingMode.POST_INDEX) {
            writeBaseRegister(core, rn, base + immediate);
        }
    }

    private static long readMemory(Aarch64Core core, long address, Ir64MemSize size) {
        return switch (size) {
            case BYTE -> Byte.toUnsignedLong((byte) core.memory().read8(address));
            case HALF -> Short.toUnsignedLong((short) core.memory().read16(address));
            case WORD -> Integer.toUnsignedLong(core.memory().read32(address));
            case DOUBLEWORD -> core.memory().read64(address);
        };
    }

    private static void writeMemory(Aarch64Core core, long address, Ir64MemSize size, long value) {
        switch (size) {
            case BYTE -> core.memory().write8(address, (int) value);
            case HALF -> core.memory().write16(address, (int) value);
            case WORD -> core.memory().write32(address, (int) value);
            case DOUBLEWORD -> core.memory().write64(address, value);
        }
    }

    /// Estende o sinal de um valor já lido (zero-estendido pela largura de {@code size}) para os
    /// 64 bits completos — usado por `LDRSB`/`LDRSH`/`LDRSW`.
    private static long signExtendFromSize(long zeroExtended, Ir64MemSize size) {
        return switch (size) {
            case BYTE -> (long) (byte) zeroExtended;
            case HALF -> (long) (short) zeroExtended;
            case WORD -> (long) (int) zeroExtended;
            case DOUBLEWORD -> zeroExtended;
        };
    }

    /// Trunca um registrador para a largura de `size`, zero-estendido — usado por `CAS` (B8.1)
    /// para comparar `Rs` contra um valor de memória já zero-estendido por {@link #readMemory}.
    private static long zeroTruncateToSize(long value, Ir64MemSize size) {
        return switch (size) {
            case BYTE -> value & 0xFFL;
            case HALF -> value & 0xFFFFL;
            case WORD -> value & 0xFFFF_FFFFL;
            case DOUBLEWORD -> value;
        };
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

    /// `AddWithCarry(a, b, carryIn)` do `ARM DDI 0487` pseudocódigo — usado por `ADC`/`SBC`
    /// (B8.2, {@link #executeAluWithCarry}) e futuramente por qualquer soma de 3 operandos.
    /// Diferente de {@link #addWithFlags} (2 operandos): a soma de 3 operandos (`a+b+carryIn`)
    /// pode precisar de MAIS de 64 bits de precisão intermediária para calcular `carry`/
    /// `overflow` corretamente — um encadeamento ingênuo de duas somas de 64 bits (somar `a+b`,
    /// depois somar o carry) foi TESTADO E REJEITADO nesta task: o `overflow` de uma soma de 3
    /// operandos NÃO é a soma (OU) dos overflows de cada passo em sequência (contra-exemplo
    /// encontrado: `a=Long.MIN_VALUE, b=-1, carryIn=1` — a soma exata é `Long.MIN_VALUE`, dentro
    /// do range, mas o encadeamento por passos sinaliza overflow por engano no primeiro passo).
    /// {@link BigInteger} dá a precisão exata sem essa armadilha — `ADC`/`SBC` não são caminho
    /// quente o bastante para justificar bit-tricks (sem suporte ASM nativo nesta task, ver
    /// "Não inclui").
    private static AluResult addWithCarryFlags(long a, long b, boolean carryIn, boolean wide) {
        BigInteger carryInValue = carryIn ? BigInteger.ONE : BigInteger.ZERO;
        if (wide) {
            BigInteger unsignedA = a >= 0 ? BigInteger.valueOf(a) : BigInteger.valueOf(a).add(TWO_POW_64);
            BigInteger unsignedB = b >= 0 ? BigInteger.valueOf(b) : BigInteger.valueOf(b).add(TWO_POW_64);
            BigInteger unsignedSum = unsignedA.add(unsignedB).add(carryInValue);
            long result = unsignedSum.and(MASK_64_BITS).longValue();
            boolean carryOut = unsignedSum.compareTo(TWO_POW_64) >= 0;
            BigInteger signedSum = BigInteger.valueOf(a).add(BigInteger.valueOf(b)).add(carryInValue);
            boolean overflow = signedSum.compareTo(BigInteger.valueOf(result)) != 0;
            return new AluResult(result, result < 0, result == 0, carryOut, overflow);
        }
        // Forma `W`: 32+32+1 bits cabe folgado em `long` (assinado E sem sinal), sem precisar de
        // `BigInteger`.
        int ai = (int) a;
        int bi = (int) b;
        long unsignedSum = (ai & LOW_32_BITS_MASK) + (bi & LOW_32_BITS_MASK) + (carryIn ? 1L : 0L);
        int resultInt = (int) unsignedSum;
        long result = resultInt & LOW_32_BITS_MASK;
        boolean carryOut = unsignedSum != result;
        long signedSum = (long) ai + bi + (carryIn ? 1L : 0L);
        boolean overflow = signedSum != resultInt;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carryOut, overflow);
    }

    private static AluResult addWithFlags(long a, long b, boolean wide) {
        if (wide) {
            long result = a + b;
            boolean carry = Long.compareUnsigned(result, a) < 0;
            boolean overflow = (((a ^ result) & (b ^ result)) < 0);
            return new AluResult(result, result < 0, result == 0, carry, overflow);
        }
        int ai = (int) a;
        int bi = (int) b;
        int resulti = ai + bi;
        boolean carry = Integer.compareUnsigned(resulti, ai) < 0;
        boolean overflow = (((ai ^ resulti) & (bi ^ resulti)) < 0);
        long result = resulti & 0xFFFF_FFFFL;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carry, overflow);
    }

    private static AluResult subWithFlags(long a, long b, boolean wide) {
        if (wide) {
            long result = a - b;
            boolean carry = Long.compareUnsigned(a, b) >= 0;
            boolean overflow = (((a ^ b) & (a ^ result)) < 0);
            return new AluResult(result, result < 0, result == 0, carry, overflow);
        }
        int ai = (int) a;
        int bi = (int) b;
        int resulti = ai - bi;
        boolean carry = Integer.compareUnsigned(ai, bi) >= 0;
        boolean overflow = (((ai ^ bi) & (ai ^ resulti)) < 0);
        long result = resulti & 0xFFFF_FFFFL;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carry, overflow);
    }

    /// `AND`/`ORR`/`EOR` (imediato) NUNCA atualizam C/V (`ARM DDI 0487 C6.2.9`, `ANDS`
    /// imediato): diferente do barrel shifter clássico de 32 bits, que podia produzir carry a
    /// partir do próprio shift do imediato — A64 não tem esse mecanismo para a forma imediata.
    private static AluResult logicalWithFlags(long result, boolean wide) {
        long masked = wide ? result : (result & 0xFFFF_FFFFL);
        boolean negative = wide ? masked < 0 : (masked & 0x8000_0000L) != 0;
        return new AluResult(masked, negative, masked == 0, false, false);
    }

    private record AluResult(long value, boolean negative, boolean zero, boolean carry, boolean overflow) {
    }
}
