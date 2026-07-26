package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

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
    /// @param core core a executar
    /// @return ciclos internos consumidos (mesma convenção de
    ///         {@link dev.vitorsilverio.armjitter.core.ArmCore#stepReturningInternalCycles})
    public int step(Aarch64Core core) {
        long pc = core.pc();
        // G4: Fetch/Cycle nunca ganham guard condicional — são contabilizados incondicionalmente
        // antes de decodificar a semântica da instrução.
        Ir64Op.Fetch fetch = new Ir64Op.Fetch(pc, Aarch64Decoder.instructionSizeBytes());
        executeFetch(core, fetch);
        Ir64Op.Cycle cycle = new Ir64Op.Cycle(CYCLES_PER_INSTRUCTION);
        int cycles = executeCycle(cycle);
        core.addCycles(cycles);

        Ir64Op op = decoder.decode(core.memory(), pc);
        boolean pcChanged = execute(core, op);
        if (!pcChanged) {
            core.setProgramCounter(pc + Aarch64Decoder.instructionSizeBytes());
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
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
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
