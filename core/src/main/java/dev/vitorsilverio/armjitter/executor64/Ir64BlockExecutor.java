package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

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
            case Ir64Op.Kind.BITFIELD -> executeBitfield(core, (Ir64Op.Bitfield) op);
            case Ir64Op.Kind.MULTIPLY_ACCUMULATE ->
                    executeMultiplyAccumulate(core, (Ir64Op.MultiplyAccumulate) op);
            case Ir64Op.Kind.DIVIDE -> executeDivide(core, (Ir64Op.Divide) op);
            case Ir64Op.Kind.LOAD_EXCLUSIVE -> executeLoadExclusive(core, (Ir64Op.LoadExclusive) op);
            case Ir64Op.Kind.STORE_EXCLUSIVE -> executeStoreExclusive(core, (Ir64Op.StoreExclusive) op);
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
                    executePrivilegedCall(core, (Ir64Op.PrivilegedCall) op);
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

    /// `TLBI VMALLE1`/`TLBI VMALLE1IS`/`DSB`/`ISB`/`DMB` (B6.6.3): a barreira é sempre NOP; `TLBI`
    /// delega em {@link Aarch64SystemRegisterBus#invalidateTlbAll()} — sem checar
    /// {@link Aarch64SystemRegisterBus#handles} (diferente de {@link #executeSystemRegister}),
    /// já que o método tem default NOP no barramento vazio (mesma disciplina "sem hospedeiro =
    /// sem TLB para invalidar", não uma falta arquitetural).
    private boolean executeSystemInstruction(Aarch64Core core, Ir64Op.SystemInstruction op) {
        switch (op.opcode()) {
            case TLBI_ALL -> core.systemRegisterBus().invalidateTlbAll();
            case BARRIER, NOP_HINT -> { /* NOP observável — sem cache/pipeline/event-stream. */ }
            case WFI -> core.setSleepState(CpuSleepState.HALTED);
        }
        return false;
    }

    /// `HVC`/`SMC` (B6.6.7) — sem EL2/EL3 modelados, sempre devolve `PSCI_RET_NOT_SUPPORTED`
    /// (`-1`) em `X0` (ver javadoc de {@link Ir64Op.PrivilegedCall}). `setXForWidth(..., false)`
    /// (não `setX`) porque o valor de retorno real de uma chamada PSCI de 32 bits (`SMC32`/`HVC32`,
    /// a convenção mais comum) é lido pelo guest via `W0` — zero-estender os 32 bits altos evita
    /// que um `CMP W0, #0` do guest veja lixo ali (mesmo cuidado de qualquer escrita `W` no resto
    /// do executor).
    private boolean executePrivilegedCall(Aarch64Core core, Ir64Op.PrivilegedCall op) {
        final int returnRegister = 0;
        final long pscretNotSupported = 0xFFFF_FFFFL;
        core.setXForWidth(returnRegister, pscretNotSupported, false);
        return false;
    }

    /// `ERET` (B6.6.4, e B6.6.7 para o bit `I`): `PC←ELR_EL1`, `PSTATE.{N,Z,C,V,I}←SPSR_EL1`, sai
    /// de EL1 — mesma ordem do precedente 32-bit (`SUBS PC,LR,#8` equivalente, mas automático
    /// aqui: A64 não precisa de subtração porque `ELR_EL1` já é o endereço exato de retomada, sem
    /// o viés `+4`/`+8` do LR bancado do ARM32).
    private boolean executeExceptionReturn(Aarch64Core core, Ir64Op.ExceptionReturn op) {
        Aarch64ExceptionState exceptionState = core.exceptionState();
        long returnAddress = exceptionState.elr1();
        core.pstate().setFromSpsrFormat(exceptionState.spsr1());
        exceptionState.setInEl1(false);
        core.setProgramCounter(returnAddress);
        return true;
    }

    private boolean executeLoadStorePair(Aarch64Core core, Ir64Op.LoadStorePair op) {
        long base = readBaseRegister(core, op.rn());
        long address = op.addressingMode() == Ir64AddressingMode.POST_INDEX
                ? base : base + op.immediate();
        int stride = op.wide() ? PAIR_DOUBLEWORD_STRIDE_BYTES : PAIR_WORD_STRIDE_BYTES;
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        if (op.load()) {
            long first = readMemory(core, address, size);
            long second = readMemory(core, address + stride, size);
            core.setXForWidth(op.rt(), first, op.wide());
            core.setXForWidth(op.rt2(), second, op.wide());
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
