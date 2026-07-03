package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Helpers estáticos invocados pelo bytecode ASM gerado.
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}.
public final class AsmRuntimeHelpers {
    /// Cacheado: {@link Condition#values()} clona o array a cada chamada; o guard condicional
    /// roda por op compilado, então indexamos este array fixo pelo ordinal.
    private static final Condition[] CONDITIONS = Condition.values();

    private AsmRuntimeHelpers() {
    }

    // ── condição ───────────────────────────────────────────────────────────────

    /// Avalia a condição de execução de uma op a partir do ordinal de {@link Condition}.
    ///
    /// Chama o MESMO {@code cpsr().evalCond} do interpretador (ver topo de cada executor, ex.
    /// {@link dev.vitorsilverio.armjitter.codegen.executor.IrAluExecutor}), garantindo que o guard
    /// condicional emitido pelo {@link AsmBlockCompiler} seja idêntico por construção. A JVM inlina.
    public static boolean evalCond(ArmCore core, int ordinal) {
        return core.cpsr().evalCond(CONDITIONS[ordinal]);
    }

    // Guards especializados POR CONDIÇÃO, escolhidos em tempo de compilação pelo
    // AsmBlockCompiler: eliminam o switch-por-execução de evalCond (o guard roda por op
    // condicional compilado — código ARM é denso em condições, e este era um dos leaves mais
    // quentes do JFR). Cada um espelha um caso de CpsrRegister.evalCond e inlina a um teste
    // de bits.

    public static boolean condEq(ArmCore core) {
        return core.cpsr().zero();
    }

    public static boolean condNe(ArmCore core) {
        return !core.cpsr().zero();
    }

    public static boolean condCs(ArmCore core) {
        return core.cpsr().carry();
    }

    public static boolean condCc(ArmCore core) {
        return !core.cpsr().carry();
    }

    public static boolean condMi(ArmCore core) {
        return core.cpsr().negative();
    }

    public static boolean condPl(ArmCore core) {
        return !core.cpsr().negative();
    }

    public static boolean condVs(ArmCore core) {
        return core.cpsr().overflow();
    }

    public static boolean condVc(ArmCore core) {
        return !core.cpsr().overflow();
    }

    public static boolean condHi(ArmCore core) {
        return core.cpsr().carry() && !core.cpsr().zero();
    }

    public static boolean condLs(ArmCore core) {
        return !core.cpsr().carry() || core.cpsr().zero();
    }

    public static boolean condGe(ArmCore core) {
        return core.cpsr().negative() == core.cpsr().overflow();
    }

    public static boolean condLt(ArmCore core) {
        return core.cpsr().negative() != core.cpsr().overflow();
    }

    public static boolean condGt(ArmCore core) {
        return !core.cpsr().zero() && core.cpsr().negative() == core.cpsr().overflow();
    }

    public static boolean condLe(ArmCore core) {
        return core.cpsr().zero() || core.cpsr().negative() != core.cpsr().overflow();
    }

    // ── flags ALU ──────────────────────────────────────────────────────────────

    public static void updateCmpFlags(ArmCore core, int left, int right) {
        int result = left - right;
        updateSbcFlags(core, left, right, 0, result);
    }

    public static void updateAddFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(result, left) < 0;
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    public static void updateAdcFlags(ArmCore core, int left, int right, int carryIn, int result) {
        long unsigned = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right) + carryIn;
        long signed = (long) left + (long) right + carryIn;
        boolean carry = (unsigned >>> 32) != 0;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    public static void updateSbcFlags(ArmCore core, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    public static void updateLogicFlags(ArmCore core, int result, boolean carry) {
        core.cpsr().setNzcv(result < 0, result == 0, carry, core.cpsr().overflow());
    }

    public static void updateNzFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    public static void updateLongNzFlags(ArmCore core, long result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    // ── shifts ─────────────────────────────────────────────────────────────────

    public static int doLsl(int value, int amount) {
        return amount >= 32 ? 0 : value << amount;
    }

    public static int doLsr(int value, int amount) {
        return amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
    }

    public static int doAsr(int value, int amount) {
        return amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
    }

    public static int doRor(int value, int amount) {
        return amount == 0 ? value : Integer.rotateRight(value, amount & 31);
    }

    // ── memória ────────────────────────────────────────────────────────────────

    public static int loadByte(ArmCore core, int address) {
        int value = core.memory().read8(address);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
        return value;
    }

    public static int loadHalf(ArmCore core, int address) {
        int aligned = address & ~1;
        int value = core.memory().read16(aligned);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_READ);
        return (address & 1) == 0 ? value : Integer.rotateRight(value, 8);
    }

    public static int loadHalfSigned(ArmCore core, int address) {
        if ((address & 1) != 0) {
            int value = core.memory().read8(address);
            core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
            return (byte) value;
        }
        int value = core.memory().read16(address);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_READ);
        return (short) value;
    }

    public static int loadWord(ArmCore core, int address) {
        int aligned = address & ~3;
        int value = core.memory().read32(aligned);
        core.addMemoryCycles(aligned, 4, MemoryAccessType.DATA_READ);
        return Integer.rotateRight(value, (address & 3) * 8);
    }

    public static void storeByte(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_WRITE);
    }

    public static void storeHalf(ArmCore core, int address, int value) {
        core.memory().write16(address, value);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
    }

    public static void storeWord(ArmCore core, int address, int value) {
        core.memory().write32(address, value);
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
    }

    // ── branches ───────────────────────────────────────────────────────────────

    /// Sets thumb mode from bit 0 of target and updates PC (ARMv4T BX semantics).
    public static void branchExchange(ArmCore core, int target) {
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
    }

    /// Loads a value into PC, aligning to 4 bytes in ARM mode or 2 in THUMB (ARMv4T — no interworking).
    public static void loadToPcArm4(ArmCore core, int value) {
        int mask = core.cpsr().isThumbMode() ? ~1 : ~3;
        core.setProgramCounter(value & mask);
    }

    /// Loads a value into PC WITH interworking (ARMv5T+): bit 0 selects THUMB/ARM state.
    /// Used by LDR/LDM/POP to PC on ARMv5; data-processing to PC still uses {@link #loadToPcArm4}.
    public static void loadToPcArm5(ArmCore core, int value) {
        core.cpsr().setThumbMode((value & 1) != 0);
        core.setProgramCounter(value & ~1);
    }

    /// Load-to-PC escolhendo interworking conforme a arquitetura (decidida no emit).
    private static void loadToPc(ArmCore core, int value, boolean interwork) {
        if (interwork) {
            loadToPcArm5(core, value);
        } else {
            loadToPcArm4(core, value);
        }
    }

    // ── LDM/STM/PUSH/POP ───────────────────────────────────────────────────────

    public static void executePush(ArmCore core, int registerMask, boolean includeLr) {
        int count = Integer.bitCount(registerMask) + (includeLr ? 1 : 0);
        int address = core.register(13) - count * 4;
        int current = address;
        for (int reg = 0; reg <= 7; reg++) {
            if ((registerMask & (1 << reg)) != 0) {
                storeWord(core, current, core.register(reg));
                current += 4;
            }
        }
        if (includeLr) {
            storeWord(core, current, core.register(14));
        }
        core.setRegister(13, address);
    }

    /// @param interwork `true` em ARMv5+ (POP {pc} interworka pelo bit 0 do valor carregado)
    /// @return {@code true} when PC was loaded (the JIT block must exit after this)
    public static boolean executePop(ArmCore core, int registerMask, boolean includePc, boolean interwork) {
        int current = core.register(13);
        for (int reg = 0; reg <= 7; reg++) {
            if ((registerMask & (1 << reg)) != 0) {
                core.setRegister(reg, loadWord(core, current));
                current += 4;
            }
        }
        if (includePc) {
            loadToPc(core, loadWord(core, current), interwork);
            current += 4;
            core.setRegister(13, current);
            return true;
        }
        core.setRegister(13, current);
        return false;
    }

    /// @return {@code true} when PC was loaded (the JIT block must exit after this)
    public static boolean executeMultipleTransfer(
            ArmCore core, boolean load, int baseRegister, int registerMask,
            boolean writeback, boolean userMode, boolean emptyList, int modeOrdinal, boolean interwork) {
        int mask = emptyList ? (1 << 15) : registerMask;
        int count = emptyList ? 16 : Integer.bitCount(registerMask);
        int base = core.register(baseRegister);
        BlockTransferMode mode = BlockTransferMode.values()[modeOrdinal];
        int address = mode.startAddress(base, count) & ~3;
        int writebackAddress = mode.writebackAddress(base, count);
        boolean includesPc = (mask & (1 << 15)) != 0;
        boolean forceUser = userMode && !includesPc;
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        int loadedPc = 0;
        for (int reg = 0; reg <= 15; reg++) {
            if ((mask & (1 << reg)) != 0) {
                if (load) {
                    int value = loadWord(core, address);
                    if (userMode && includesPc && reg == 15) {
                        loadedPc = value;
                    } else if (forceUser) {
                        core.setBankedRegister(CpuMode.USER, reg, value);
                    } else if (reg == 15) {
                        loadToPc(core, value, interwork); // LDM reg15 normal: interworka em ARMv5
                    } else {
                        core.setRegister(reg, value);
                    }
                } else {
                    int value;
                    if (userMode) {
                        value = core.bankedRegister(CpuMode.USER, reg);
                    } else if (writeback && reg == baseRegister && reg != firstRegister) {
                        value = writebackAddress;
                    } else {
                        value = core.register(reg);
                    }
                    storeWord(core, address, value);
                }
                address += 4;
            }
        }
        if (writeback && !(load && (mask & (1 << baseRegister)) != 0)) {
            core.setRegister(baseRegister, writebackAddress);
        }
        if (load && userMode && includesPc) {
            CpuMode psrMode = core.mode();
            if (psrMode != CpuMode.USER && psrMode != CpuMode.SYSTEM) {
                core.setCpsr(core.spsr(psrMode));
            }
            loadToPcArm4(core, loadedPc);
        }
        return load && includesPc;
    }

    // ── ARMv5TE (saturação / DSP) ──────────────────────────────────────────────
    // Espelham IrAluExecutor.executeSaturating/executeDspMultiply. Recebem VALORES e devolvem o
    // resultado (o bytecode lê/escreve registradores pelo register cache); só o bit Q sticky é
    // efeito colateral no core.

    /// QADD/QSUB/QDADD/QDSUB. `op`: 0=QADD, 1=QSUB, 2=QDADD, 3=QDSUB. Satura em 32 bits com sinal
    /// e seta o bit Q em overflow (de qualquer etapa, como no interpretador).
    public static int saturating(ArmCore core, int rm, int rn, int op) {
        boolean q = false;
        long result;
        if (op == 0) {
            result = (long) rm + rn;
        } else if (op == 1) {
            result = (long) rm - rn;
        } else {
            long doubled = 2L * rn;
            int clamped;
            if (doubled > Integer.MAX_VALUE) {
                q = true;
                clamped = Integer.MAX_VALUE;
            } else if (doubled < Integer.MIN_VALUE) {
                q = true;
                clamped = Integer.MIN_VALUE;
            } else {
                clamped = (int) doubled;
            }
            result = op == 2 ? (long) rm + clamped : (long) rm - clamped;
        }
        int out;
        if (result > Integer.MAX_VALUE) {
            q = true;
            out = Integer.MAX_VALUE;
        } else if (result < Integer.MIN_VALUE) {
            q = true;
            out = Integer.MIN_VALUE;
        } else {
            out = (int) result;
        }
        if (q) {
            core.cpsr().setSaturation(true); // sticky
        }
        return out;
    }

    /// SMLAxy: (Rm.x * Rs.y) + Rn, com Q se o acúmulo de 32 bits estoura.
    public static int dspSmla(ArmCore core, int rmHalf, int rsHalf, int rn) {
        long sum = (long) (rmHalf * rsHalf) + rn;
        if (sum != (int) sum) {
            core.cpsr().setSaturation(true);
        }
        return (int) sum;
    }

    /// SMLAWy: ((Rm * Rs.y) >> 16) + Rn, com Q se o acúmulo estoura.
    public static int dspSmlaw(ArmCore core, int rm, int rsHalf, int rn) {
        int product = (int) (((long) rm * rsHalf) >> 16);
        long sum = (long) product + rn;
        if (sum != (int) sum) {
            core.cpsr().setSaturation(true);
        }
        return (int) sum;
    }

    /// SMULWy: (Rm * Rs.y) >> 16 (sem acumular, sem Q).
    public static int dspSmulw(int rm, int rsHalf) {
        return (int) (((long) rm * rsHalf) >> 16);
    }

    /// SMLALxy: {RdHi:RdLo} + RmHalf*RsHalf em 64 bits (sem Q).
    public static long dspSmlal(int rdHi, int rdLo, int rmHalf, int rsHalf) {
        long acc = ((long) rdHi << 32) | (rdLo & 0xFFFF_FFFFL);
        return acc + (long) rmHalf * rsHalf;
    }

    // ── PSR ────────────────────────────────────────────────────────────────────

    public static void executePsrRead(ArmCore core, boolean spsr, int register) {
        CpuMode mode = core.mode();
        boolean hasSPSR = mode != CpuMode.USER && mode != CpuMode.SYSTEM;
        int value = (spsr && hasSPSR) ? core.spsr(mode) : core.cpsr().get();
        core.setRegister(register, value);
    }

    public static void executePsrWrite(ArmCore core, boolean spsr, int value, int fieldMask) {
        CpuMode mode = core.mode();
        boolean hasSPSR = mode != CpuMode.USER && mode != CpuMode.SYSTEM;
        int effectiveMask = (spsr || mode != CpuMode.USER) ? fieldMask : fieldMask & 0x8;
        if (spsr) {
            if (hasSPSR) {
                core.setSpsr(mode, mergePsr(core.spsr(mode), value, effectiveMask));
            }
        } else {
            core.setCpsr(mergePsr(core.cpsr().get(), value, effectiveMask));
        }
    }

    private static int mergePsr(int current, int value, int fieldMask) {
        int mask = 0;
        if ((fieldMask & 0x1) != 0) mask |= 0x0000_00FF;
        if ((fieldMask & 0x2) != 0) mask |= 0x0000_FF00;
        if ((fieldMask & 0x4) != 0) mask |= 0x00FF_0000;
        if ((fieldMask & 0x8) != 0) mask |= 0xFF00_0000;
        return (current & ~mask) | (value & mask);
    }

    // ── SWI ────────────────────────────────────────────────────────────────────

    /// Dispatches a software interrupt. Always returns {@code true} (PC always changes).
    public static boolean executeSwi(ArmCore core, int immediate, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        if (core.swiDispatcher().canDispatch(immediate)) {
            CpuState next = core.swiDispatcher().dispatch(immediate, core.toCpuState());
            core.apply(next);
        } else {
            core.requestException(ArmException.SWI);
        }
        return true;
    }

    // ── coprocessor ────────────────────────────────────────────────────────────

    /// @return {@code true} when PC was changed (undefined exception triggered)
    public static boolean executeCoprocessor(
            ArmCore core, boolean load, int coprocessorNum, int opcode1,
            int crn, int crm, int opcode2, int register, int sequentialPc) {
        var bus = core.coprocessorBus();
        if (!bus.handles(coprocessorNum)) {
            core.setProgramCounter(sequentialPc);
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (load) {
            int value = bus.read(coprocessorNum, opcode1, crn, crm, opcode2);
            if (register == 15) {
                core.cpsr().setNzcv(
                        (value & 0x8000_0000) != 0,
                        (value & 0x4000_0000) != 0,
                        (value & 0x2000_0000) != 0,
                        (value & 0x1000_0000) != 0);
            } else {
                core.setRegister(register, value);
            }
        } else {
            bus.write(coprocessorNum, opcode1, crn, crm, opcode2, core.register(register));
        }
        return false;
    }

    // ── undefined ──────────────────────────────────────────────────────────────

    public static void executeUndefined(ArmCore core, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        core.requestException(ArmException.UNDEFINED);
    }
}
