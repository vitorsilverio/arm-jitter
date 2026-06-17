package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Emissor que transforma IR em um bloco executavel por interpretacao de IR.
public final class InterpretedCodeEmitter implements CodeEmitter {
    private final ArmArchitecture architecture;

    /// Emissor para a arquitetura base (ARMv4T / GBA).
    public InterpretedCodeEmitter() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Emissor ligado a uma arquitetura: os poucos forks de comportamento entre versões
    /// (ex.: interworking de load->PC) consultam {@code architecture}.
    public InterpretedCodeEmitter(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    /// Emite um bloco executável que interpreta as operações IR em ordem.
    @Override
    public CompiledBlock emit(IrBlock block) {
        return core -> execute(block, core);
    }

    @Override
    public CodegenBackend backend() {
        return CodegenBackend.INTERPRETED_IR;
    }

    /// Interpreta um bloco IR sobre o core informado e devolve os ciclos internos
    /// (IrOp.Cycle) consumidos; ciclos de memória são somados diretamente ao core.
    ///
    /// Esta é a única implementação da semântica das instruções: tanto o caminho JIT
    /// (blocos compilados) quanto o interpretador frio (ArmInterpreter) a utilizam,
    /// de modo que correções de comportamento vivem em um único lugar.
    public int execute(IrBlock block, ArmCore core) {
        int cycles = 0;
        boolean pcChanged = false;

        for (IrOp op : block.operations()) {
            switch (op) {
                case IrOp.Alu alu -> pcChanged |= executeAlu(core, alu);
                case IrOp.Multiply multiply -> executeMultiply(core, multiply);
                case IrOp.LongMultiply multiply -> executeLongMultiply(core, multiply);
                case IrOp.PsrTransfer psr -> executePsrTransfer(core, psr);
                case IrOp.LoadLiteral load -> pcChanged |= executeLoadLiteral(core, load);
                case IrOp.Load load -> pcChanged |= executeLoad(core, load);
                case IrOp.Store store -> executeStore(core, store);
                case IrOp.Swap swap -> pcChanged |= executeSwap(core, swap);
                case IrOp.MultipleTransfer transfer -> pcChanged |= executeMultipleTransfer(core, transfer);
                case IrOp.Branch branch -> pcChanged |= executeBranch(core, branch);
                case IrOp.BranchExchange branch -> pcChanged |= executeBranchExchange(core, branch);
                case IrOp.ThumbBlPrefix prefix -> executeThumbBlPrefix(core, prefix);
                case IrOp.ThumbBlSuffix suffix -> pcChanged |= executeThumbBlSuffix(core, suffix);
                case IrOp.Push push -> executePush(core, push);
                case IrOp.Pop pop -> pcChanged |= executePop(core, pop);
                case IrOp.Swi swi -> pcChanged |= executeSwi(core, swi, block.endPc());
                case IrOp.Coprocessor cp -> pcChanged |= executeCoprocessor(core, cp);
                case IrOp.Undefined undefined -> pcChanged |= executeUndefined(core, undefined);
                case IrOp.Cycle cycle -> cycles += cycle.count();
                case IrOp.Fetch fetch -> core.addMemoryCycles(fetch.address(), fetch.sizeBytes(), MemoryAccessType.INSTRUCTION_FETCH);
            }
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }

    private boolean executeAlu(ArmCore core, IrOp.Alu alu) {
        if (!core.cpsr().evalCond(alu.condition())) {
            return false;
        }

        int right = operand(core, alu.src2());
        switch (alu.opcode()) {
            case IrOpCode.MOV -> {
                // Read the shifter carry-out before the destination is written, since
                // Rd may alias the shifted source register.
                boolean carry = alu.setFlags() && operandCarryOut(core, alu.src2());
                if (writeAluDestination(core, alu.dst(), right, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    setLogicFlags(core, right, carry);
                }
            }
            case IrOpCode.ADD -> {
                int left = registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = left + right;
                if (writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    setAddFlags(core, left, right, result);
                }
            }
            case IrOpCode.ADC -> {
                int left = registerValue(core, alu.src1(), alu.src1ValueOverride());
                int carry = core.cpsr().carry() ? 1 : 0;
                int result = left + right + carry;
                if (writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    setAdcFlags(core, left, right, carry, result);
                }
            }
            case IrOpCode.SUB, IrOpCode.RSB, IrOpCode.CMP, IrOpCode.SBC, IrOpCode.RSC, IrOpCode.NEG -> {
                int source = IrOpCode.NEG.equals(alu.opcode()) ? 0 : registerValue(core, alu.src1(), alu.src1ValueOverride());
                boolean reverse = IrOpCode.RSB.equals(alu.opcode()) || IrOpCode.RSC.equals(alu.opcode());
                int left = reverse ? right : source;
                int subRight = reverse ? source : right;
                int borrow = (IrOpCode.SBC.equals(alu.opcode()) || IrOpCode.RSC.equals(alu.opcode()))
                        && !core.cpsr().carry() ? 1 : 0;
                int subtrahend = subRight + borrow;
                int result = left - subtrahend;
                if (!IrOpCode.CMP.equals(alu.opcode())) {
                    if (writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                        return true;
                    }
                }
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        restoreCpsrFromCurrentSpsr(core); // ARMv4 CMP{P}: CPSR = SPSR, no NZCV write
                    } else {
                        setSbcFlags(core, left, subRight, borrow, result);
                    }
                }
            }
            case IrOpCode.CMN -> {
                int left = registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = left + right;
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        restoreCpsrFromCurrentSpsr(core); // ARMv4 CMN{P}: CPSR = SPSR
                    } else {
                        setAddFlags(core, left, right, result);
                    }
                }
            }
            case IrOpCode.AND, IrOpCode.EOR, IrOpCode.ORR, IrOpCode.BIC, IrOpCode.TST, IrOpCode.TEQ -> {
                int left = registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = switch (alu.opcode()) {
                    case IrOpCode.AND -> left & right;
                    case IrOpCode.EOR -> left ^ right;
                    case IrOpCode.ORR -> left | right;
                    case IrOpCode.BIC -> left & ~right;
                    case IrOpCode.TST -> left & right;
                    case IrOpCode.TEQ -> left ^ right;
                    default -> throw new IllegalStateException("Unexpected logic opcode: " + alu.opcode());
                };
                // Read the shifter carry-out before the destination is written, since
                // Rd may alias the shifted source register.
                boolean carry = alu.setFlags() && operandCarryOut(core, alu.src2());
                if (!IrOpCode.TST.equals(alu.opcode()) && !IrOpCode.TEQ.equals(alu.opcode())) {
                    if (writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                        return true;
                    }
                }
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        restoreCpsrFromCurrentSpsr(core); // ARMv4 TST{P}/TEQ{P}: CPSR = SPSR
                    } else {
                        setLogicFlags(core, result, carry);
                    }
                }
            }
            case IrOpCode.MVN -> {
                int result = ~right;
                // Read the shifter carry-out before the destination is written, since
                // Rd may alias the shifted source register.
                boolean carry = alu.setFlags() && operandCarryOut(core, alu.src2());
                if (writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    setLogicFlags(core, result, carry);
                }
            }
            case IrOpCode.CLZ -> core.setRegister(alu.dst(), Integer.numberOfLeadingZeros(
                    registerValue(core, alu.src1(), alu.src1ValueOverride())));
            case IrOpCode.LSL, IrOpCode.LSR, IrOpCode.ASR, IrOpCode.ROR -> {
                int value = registerValue(core, alu.src1(), alu.src1ValueOverride());
                int amount = right & 0xFF;
                int result = switch (alu.opcode()) {
                    case IrOpCode.LSL -> amount >= 32 ? 0 : value << amount;
                    case IrOpCode.LSR -> amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
                    case IrOpCode.ASR -> amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
                    case IrOpCode.ROR -> amount == 0 ? value : Integer.rotateRight(value, amount & 31);
                    default -> throw new IllegalStateException("Unexpected shift opcode: " + alu.opcode());
                };
                if (writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    setLogicFlags(core, result, shiftCarryOut(core, value, alu.opcode(), amount,
                            alu.src2() instanceof IrOperand.Immediate));
                }
            }
            default -> throw new UnsupportedOperationException("Unknown IR ALU opcode: " + alu.opcode());
        }
        return false;
    }

    private void executeMultiply(ArmCore core, IrOp.Multiply multiply) {
        if (!core.cpsr().evalCond(multiply.condition())) {
            return;
        }
        int result = registerValue(core, multiply.rm(), multiply.rmValueOverride())
                * registerValue(core, multiply.rs(), multiply.rsValueOverride());
        if (multiply.accumulate()) {
            result += registerValue(core, multiply.rn(), multiply.rnValueOverride());
        }
        core.setRegister(multiply.dst(), result);
        if (multiply.setFlags()) {
            setLogicFlags(core, result);
        }
    }

    private void executeLongMultiply(ArmCore core, IrOp.LongMultiply multiply) {
        if (!core.cpsr().evalCond(multiply.condition())) {
            return;
        }
        long result;
        if (multiply.signed()) {
            result = (long) registerValue(core, multiply.rm(), multiply.rmValueOverride())
                    * (long) registerValue(core, multiply.rs(), multiply.rsValueOverride());
        } else {
            result = Integer.toUnsignedLong(registerValue(core, multiply.rm(), multiply.rmValueOverride()))
                    * Integer.toUnsignedLong(registerValue(core, multiply.rs(), multiply.rsValueOverride()));
        }
        if (multiply.accumulate()) {
            long current = (Integer.toUnsignedLong(registerValue(core, multiply.dstHigh(), multiply.dstHighValueOverride())) << 32)
                    | Integer.toUnsignedLong(registerValue(core, multiply.dstLow(), multiply.dstLowValueOverride()));
            result += current;
        }
        core.setRegister(multiply.dstLow(), (int) result);
        core.setRegister(multiply.dstHigh(), (int) (result >>> 32));
        if (multiply.setFlags()) {
            core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
        }
    }

    private void executePsrTransfer(ArmCore core, IrOp.PsrTransfer transfer) {
        if (!core.cpsr().evalCond(transfer.condition())) {
            return;
        }
        CpuMode psrMode = core.mode();
        boolean hasSPSR = psrMode != CpuMode.USER && psrMode != CpuMode.SYSTEM;
        if (transfer.read()) {
            int value = (transfer.spsr() && hasSPSR) ? core.spsr(psrMode) : core.cpsr().get();
            core.setRegister(transfer.register(), value);
            return;
        }
        int value = transfer.immediateOperand()
                ? transfer.immediate()
                : registerValue(core, transfer.register(), transfer.registerValueOverride());
        if (transfer.spsr()) {
            if (hasSPSR) {
                core.setSpsr(psrMode, mergePsr(core.spsr(psrMode), value, transfer.fieldMask()));
            }
        } else {
            core.setCpsr(mergePsr(core.cpsr().get(), value, cpsrWriteFieldMask(core, transfer.fieldMask())));
        }
    }

    private boolean executeBranch(ArmCore core, IrOp.Branch branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        if (branch.link()) {
            core.setRegister(14, branch.returnAddress());
        }
        core.setProgramCounter(branch.target());
        return true;
    }

    private boolean executeBranchExchange(ArmCore core, IrOp.BranchExchange branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        int target = registerValue(core, branch.sourceRegister(), branch.sourceValueOverride());
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
        return true;
    }

    private void executeThumbBlPrefix(ArmCore core, IrOp.ThumbBlPrefix prefix) {
        if (!core.cpsr().evalCond(prefix.condition())) {
            return;
        }
        core.setRegister(14, prefix.address() + 4 + prefix.highOffset());
    }

    private boolean executeThumbBlSuffix(ArmCore core, IrOp.ThumbBlSuffix suffix) {
        if (!core.cpsr().evalCond(suffix.condition())) {
            return false;
        }
        int oldLink = core.register(14);
        core.setRegister(14, (suffix.address() + 2) | 1);
        core.setProgramCounter(oldLink + suffix.lowOffset());
        return true;
    }

    private boolean executeLoad(ArmCore core, IrOp.Load load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return false;
        }
        int offset = operand(core, load.offset());
        int base = load.baseValueOverride() >= 0 ? load.baseValueOverride() : core.register(load.base());
        int address = load.postIndexed() ? base : base + offset;
        int value = switch (load.sizeBytes()) {
            case 1 -> read8Arm7(core, address);
            case 2 -> read16Arm7(core, address, load.signed());
            case 4 -> read32Arm7(core, address);
            default -> throw new UnsupportedOperationException("Unsupported IR load size: " + load.sizeBytes());
        };
        value = signExtendIfNeeded(value, load.sizeBytes(), load.signed());
        writeLoadedRegister(core, load.dst(), value);
        // ARM7TDMI: if the base register is also the loaded register, the loaded value
        // wins and the base writeback is suppressed.
        if (load.writeback() && load.base() != load.dst()) {
            core.setRegister(load.base(), base + offset);
        }
        return load.dst() == 15;
    }

    private boolean executeLoadLiteral(ArmCore core, IrOp.LoadLiteral load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return false;
        }
        writeLoadedRegister(core, load.dst(), read32Arm7(core, load.address()));
        return load.dst() == 15;
    }

    private void executeStore(ArmCore core, IrOp.Store store) {
        if (!core.cpsr().evalCond(store.condition())) {
            return;
        }
        int offset = operand(core, store.offset());
        int base = store.baseValueOverride() >= 0 ? store.baseValueOverride() : core.register(store.base());
        int address = store.postIndexed() ? base : base + offset;
        int value = registerValue(core, store.src(), store.srcValueOverride());
        switch (store.sizeBytes()) {
            case 1 -> write8Arm7(core, address, value);
            case 2 -> write16Arm7(core, address, value);
            case 4 -> write32Arm7(core, address, value);
            default -> throw new UnsupportedOperationException("Unsupported IR store size: " + store.sizeBytes());
        }
        if (store.writeback()) {
            core.setRegister(store.base(), base + offset);
        }
    }

    private boolean executeSwap(ArmCore core, IrOp.Swap swap) {
        if (!core.cpsr().evalCond(swap.condition())) {
            return false;
        }
        int address = registerValue(core, swap.base(), swap.baseValueOverride());
        int memoryValue = switch (swap.sizeBytes()) {
            case 1 -> read8Arm7(core, address);
            case 4 -> read32Arm7(core, address);
            default -> throw new UnsupportedOperationException("Unsupported IR swap size: " + swap.sizeBytes());
        };
        int registerValue = registerValue(core, swap.src(), swap.srcValueOverride());
        switch (swap.sizeBytes()) {
            case 1 -> write8Arm7(core, address, registerValue);
            case 4 -> write32Arm7(core, address, registerValue);
            default -> throw new UnsupportedOperationException("Unsupported IR swap size: " + swap.sizeBytes());
        }
        writeLoadedRegister(core, swap.dst(), memoryValue);
        return swap.dst() == 15;
    }

    private boolean executeMultipleTransfer(ArmCore core, IrOp.MultipleTransfer transfer) {
        if (!core.cpsr().evalCond(transfer.condition())) {
            return false;
        }
        int mask = effectiveRegisterMask(transfer.registerMask(), transfer.emptyRegisterList());
        int count = effectiveRegisterCount(transfer.registerMask(), transfer.emptyRegisterList());
        int base = core.register(transfer.base());
        // ARM7TDMI: block transfers force word alignment of the access address; the
        // base register itself (and any writeback) keeps its unaligned value.
        int address = transfer.mode().startAddress(base, count) & ~3;
        boolean includesPc = (mask & (1 << 15)) != 0;
        boolean forceUser = transfer.userMode() && !includesPc;
        int loadedPc = 0;
        int writebackAddress = transfer.mode().writebackAddress(base, count);
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        for (int register = 0; register <= 15; register++) {
            if ((mask & (1 << register)) != 0) {
                if (transfer.load()) {
                    int value = read32Arm7(core, address);
                    if (transfer.userMode() && includesPc && register == 15) {
                        loadedPc = value;
                    } else if (forceUser) {
                        core.setBankedRegister(CpuMode.USER, register, value);
                    } else {
                        writeLoadedRegister(core, register, value);
                    }
                } else {
                    int value = multipleStoreRegisterValue(core, transfer, register, firstRegister, writebackAddress);
                    write32Arm7(core, address, value);
                }
                address += 4;
            }
        }
        if (shouldWriteBackMultiple(transfer.writeback(), transfer.load(), mask, transfer.base())) {
            core.setRegister(transfer.base(), writebackAddress);
        }
        if (transfer.load() && transfer.userMode() && includesPc) {
            restoreCpsrFromCurrentSpsr(core);
            alignAndSetPc(core, loadedPc);
        }
        return transfer.load() && includesPc;
    }

    private void executePush(ArmCore core, IrOp.Push push) {
        if (!core.cpsr().evalCond(push.condition())) {
            return;
        }
        int count = Integer.bitCount(push.registerMask()) + (push.includeLr() ? 1 : 0);
        int address = core.register(13) - count * 4;
        int current = address;
        for (int register = 0; register <= 7; register++) {
            if ((push.registerMask() & (1 << register)) != 0) {
                write32Arm7(core, current, core.register(register));
                current += 4;
            }
        }
        if (push.includeLr()) {
            write32Arm7(core, current, core.register(14));
        }
        core.setRegister(13, address);
    }

    private boolean executePop(ArmCore core, IrOp.Pop pop) {
        if (!core.cpsr().evalCond(pop.condition())) {
            return false;
        }
        int current = core.register(13);
        for (int register = 0; register <= 7; register++) {
            if ((pop.registerMask() & (1 << register)) != 0) {
                core.setRegister(register, read32Arm7(core, current));
                current += 4;
            }
        }
        boolean pcChanged = false;
        if (pop.includePc()) {
            int value = read32Arm7(core, current);
            current += 4;
            loadToPc(core, value);
            pcChanged = true;
        }
        core.setRegister(13, current);
        return pcChanged;
    }

    private boolean executeSwi(ArmCore core, IrOp.Swi swi, int sequentialPc) {
        if (!core.cpsr().evalCond(swi.condition())) {
            return false;
        }
        core.setProgramCounter(sequentialPc);
        if (core.swiDispatcher().canDispatch(swi.immediate())) {
            CpuState next = core.swiDispatcher().dispatch(swi.immediate(), core.toCpuState());
            core.apply(next);
            return true;
        }
        core.requestException(ArmException.SWI);
        return true;
    }

    private boolean executeCoprocessor(ArmCore core, IrOp.Coprocessor cp) {
        if (!core.cpsr().evalCond(cp.condition())) {
            return false;
        }
        CoprocessorBus bus = core.coprocessorBus();
        if (!bus.handles(cp.coprocessor())) {
            // No such coprocessor: behave like real hardware and take the Undefined vector.
            core.setProgramCounter(cp.sequentialPc());
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (cp.load()) {
            int value = bus.read(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2());
            if (cp.register() == 15) {
                // MRC with Rd=15 writes NZCV from bits 31-28 rather than a general register.
                core.cpsr().setNzcv((value & 0x8000_0000) != 0, (value & 0x4000_0000) != 0,
                        (value & 0x2000_0000) != 0, (value & 0x1000_0000) != 0);
            } else {
                core.setRegister(cp.register(), value);
            }
        } else {
            bus.write(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2(),
                    core.register(cp.register()));
        }
        return false;
    }

    private boolean executeUndefined(ArmCore core, IrOp.Undefined undefined) {
        if (!core.cpsr().evalCond(undefined.condition())) {
            return false;
        }
        core.setProgramCounter(undefined.sequentialPc());
        core.requestException(ArmException.UNDEFINED);
        return true;
    }

    private int operand(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.value();
            case IrOperand.Register register -> registerValue(core, register.index(), register.valueOverride());
            case IrOperand.ShiftedRegister register -> {
                int value = shiftedRegisterOperand(core, register);
                yield register.negated() ? -value : value;
            }
        };
    }

    private int shiftedRegisterOperand(ArmCore core, IrOperand.ShiftedRegister register) {
        int value = registerValue(core, register.index(), register.valueOverride());
        if (register.rrx()) {
            return (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
        }
        int amount = register.amountRegister() >= 0
                ? registerValue(core, register.amountRegister(), register.amountValueOverride()) & 0xFF
                : register.amount();
        if (register.amountRegister() >= 0 && amount == 0) {
            return value;
        }
        return applyShift(value, register.shiftType(), amount);
    }

    private boolean operandCarryOut(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.carryOutKnown()
                    ? immediate.carryOut()
                    : core.cpsr().carry();
            case IrOperand.Register ignored -> core.cpsr().carry();
            case IrOperand.ShiftedRegister register -> shiftedRegisterCarryOut(core, register);
        };
    }

    private boolean shiftedRegisterCarryOut(ArmCore core, IrOperand.ShiftedRegister register) {
        int value = registerValue(core, register.index(), register.valueOverride());
        if (register.rrx()) {
            return (value & 1) != 0;
        }
        int amount = register.amountRegister() >= 0
                ? registerValue(core, register.amountRegister(), register.amountValueOverride()) & 0xFF
                : register.amount();
        if (amount == 0) {
            return core.cpsr().carry();
        }
        return shiftCarryOut(value, register.shiftType(), amount);
    }

    private void writeLoadedRegister(ArmCore core, int register, int value) {
        if (register == 15) {
            loadToPc(core, value);
            return;
        }
        core.setRegister(register, value);
    }

    /// Escreve um valor carregado da memória em PC (LDR/LDM/POP em PC). Em ARMv5+
    /// (LOAD_PC_INTERWORKING) o bit 0 seleciona ARM/Thumb; em ARMv4T (ARM7TDMI) o bit 0 é
    /// ignorado e o bit T é preservado — apenas BX muda o estado ali.
    private void loadToPc(ArmCore core, int value) {
        if (architecture.has(ArmFeature.LOAD_PC_INTERWORKING)) {
            core.cpsr().setThumbMode((value & 1) != 0);
            core.setProgramCounter(value & ~1);
        } else {
            alignAndSetPc(core, value);
        }
    }

    private int registerValue(ArmCore core, int register, int valueOverride) {
        return valueOverride >= 0 ? valueOverride : core.register(register);
    }

    private int read32Arm7(ArmCore core, int address) {
        int aligned = address & ~3;
        int value = core.memory().read32(aligned);
        core.addMemoryCycles(aligned, 4, MemoryAccessType.DATA_READ);
        return Integer.rotateRight(value, (address & 3) * 8);
    }

    private int read8Arm7(ArmCore core, int address) {
        int value = core.memory().read8(address);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
        return value;
    }

    private int read16Arm7(ArmCore core, int address, boolean signed) {
        if (signed && (address & 1) != 0) {
            int value = core.memory().read8(address);
            core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
            return (byte) value;
        }
        int aligned = address & ~1;
        int value = core.memory().read16(aligned);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_READ);
        return (address & 1) == 0 ? value : Integer.rotateRight(value, 8);
    }

    private void write16Arm7(ArmCore core, int address, int value) {
        // Passe o endereço bruto (como Write32Arm7); forçar o alinhamento é responsabilidade da memória.
        // NRAM normal ignora o bit 0, mas um barramento de salvamento de 8 bits o usa para
        // selecionar a lane de byte, então o dispositivo deve vê-lo.
        core.memory().write16(address, value);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
    }

    private void write8Arm7(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_WRITE);
    }

    private void write32Arm7(ArmCore core, int address, int value) {
        core.memory().write32(address, value);
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
    }

    private int effectiveRegisterMask(int mask, boolean emptyRegisterList) {
        return emptyRegisterList ? 1 << 15 : mask;
    }

    private int effectiveRegisterCount(int mask, boolean emptyRegisterList) {
        return emptyRegisterList ? 16 : Integer.bitCount(mask);
    }

    private void restoreCpsrFromCurrentSpsr(ArmCore core) {
        CpuMode mode = core.mode();
        if (mode == CpuMode.USER || mode == CpuMode.SYSTEM) return;
        core.setCpsr(core.spsr(mode));
    }

    private boolean writeAluDestination(ArmCore core, int register, int value, boolean restoreSpsr) {
        if (register != 15) {
            core.setRegister(register, value);
            return false;
        }
        if (restoreSpsr) {
            restoreCpsrFromCurrentSpsr(core);
        }
        alignAndSetPc(core, value);
        return true;
    }

    private void alignAndSetPc(ArmCore core, int value) {
        int mask = core.cpsr().isThumbMode() ? ~1 : ~3;
        core.setProgramCounter(value & mask);
    }

    private int applyShift(int value, dev.vitorsilverio.armjitter.ir.ShiftType shiftType, int amount) {
        return switch (shiftType) {
            case LSL -> amount >= 32 ? 0 : value << amount;
            case LSR -> amount >= 32 ? 0 : value >>> amount;
            case ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            case ROR -> Integer.rotateRight(value, amount & 31);
        };
    }

    private boolean shiftCarryOut(ArmCore core, int value, IrOpCode opcode, int amount, boolean immediateShift) {
        if (!immediateShift && amount == 0) {
            return core.cpsr().carry();
        }
        return switch (opcode) {
            case LSL -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, dev.vitorsilverio.armjitter.ir.ShiftType.LSL, amount);
            case LSR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, dev.vitorsilverio.armjitter.ir.ShiftType.LSR, amount);
            case ASR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, dev.vitorsilverio.armjitter.ir.ShiftType.ASR, amount);
            case ROR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, dev.vitorsilverio.armjitter.ir.ShiftType.ROR, amount);
            default -> throw new IllegalStateException("Unexpected shift opcode: " + opcode);
        };
    }

    private boolean shiftCarryOut(int value, dev.vitorsilverio.armjitter.ir.ShiftType shiftType, int amount) {
        if (amount <= 0) {
            return false;
        }
        return switch (shiftType) {
            case LSL -> {
                if (amount < 32) {
                    yield ((value >>> (32 - amount)) & 1) != 0;
                }
                yield amount == 32 && (value & 1) != 0;
            }
            case LSR -> {
                if (amount < 32) {
                    yield ((value >>> (amount - 1)) & 1) != 0;
                }
                yield amount == 32 && value < 0;
            }
            case ASR -> amount >= 32 ? value < 0 : ((value >>> (amount - 1)) & 1) != 0;
            case ROR -> {
                int effective = amount & 31;
                yield effective == 0 ? value < 0 : ((value >>> (effective - 1)) & 1) != 0;
            }
        };
    }

    private int mergePsr(int current, int value, int fieldMask) {
        int mask = 0;
        if ((fieldMask & 0x1) != 0) {
            mask |= 0x0000_00FF;
        }
        if ((fieldMask & 0x2) != 0) {
            mask |= 0x0000_FF00;
        }
        if ((fieldMask & 0x4) != 0) {
            mask |= 0x00FF_0000;
        }
        if ((fieldMask & 0x8) != 0) {
            mask |= 0xFF00_0000;
        }
        return (current & ~mask) | (value & mask);
    }

    private int cpsrWriteFieldMask(ArmCore core, int fieldMask) {
        return core.mode() == CpuMode.USER ? fieldMask & 0x8 : fieldMask;
    }

    private boolean shouldWriteBackMultiple(boolean writeback, boolean load, int mask, int baseRegister) {
        return writeback && !(load && (mask & (1 << baseRegister)) != 0);
    }

    private int multipleStoreRegisterValue(
            ArmCore core,
            IrOp.MultipleTransfer transfer,
            int register,
            int firstRegister,
            int writebackAddress) {
        if (transfer.userMode()) {
            return core.bankedRegister(CpuMode.USER, register);
        }
        if (transfer.writeback()
                && register == transfer.base()
                && register != firstRegister) {
            return writebackAddress;
        }
        return registerValue(core, register, register == 15 ? transfer.pcStoreValueOverride() : -1);
    }

    private void setLogicFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    private void setLogicFlags(ArmCore core, int result, boolean carry) {
        core.cpsr().setNzcv(result < 0, result == 0, carry, core.cpsr().overflow());
    }

    private void setAddFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(result, left) < 0;
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private void setAdcFlags(ArmCore core, int left, int right, int carryIn, int result) {
        long unsigned = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right) + carryIn;
        long signed = (long) left + (long) right + carryIn;
        boolean carry = (unsigned >>> 32) != 0;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private void setSubFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(left, right) >= 0;
        boolean overflow = ((left ^ right) & (left ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private void setSbcFlags(ArmCore core, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private int signExtendIfNeeded(int value, int sizeBytes, boolean signed) {
        if (!signed) {
            return value;
        }
        return switch (sizeBytes) {
            case 1 -> (byte) value;
            case 2 -> (short) value;
            default -> value;
        };
    }
}
