package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.ir.ParallelAluOp;
import dev.vitorsilverio.armjitter.ir.ParallelAluVariant;
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

    /// Guard de iteração do loop-superbloco (task C0.3): espelha as condições de sleep e
    /// interrupção do chain loop de `JitRuntime.execute`, NESSA ordem (invariante S1 — o
    /// desfecho é o mesmo para ambas: sair da corrente). Budget e generation são checados
    /// separadamente no bytecode gerado pelo {@link AsmSuperblockCompiler}.
    public static boolean superblockKeepRunning(ArmCore core) {
        return core.sleepState() == dev.vitorsilverio.armjitter.core.CpuSleepState.RUNNING
                && !core.interruptLine();
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
        core.notifyOrdinaryWrite(address, 1);
    }

    public static void storeHalf(ArmCore core, int address, int value) {
        core.memory().write16(address, value);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 2);
    }

    public static void storeWord(ArmCore core, int address, int value) {
        core.memory().write32(address, value);
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 4);
    }

    // ── memória: acesso desalinhado atravessado (ArmFeature.UNALIGNED_ACCESS, task B1.7) ────────
    //
    // Espelham exatamente o fork de dev.vitorsilverio.armjitter.codegen.executor.IrExecutionSupport
    // (readWordForLoad/readHalfwordForLoad/writeWordForStore/writeHalfwordForStore): em vez de
    // alinhar+rotacionar (ARMv4T, loadWord/loadHalf/loadHalfSigned/storeWord/storeHalf acima), compõem
    // o acesso de bytes independentes (cada um trivialmente "alinhado" e seguro através de fronteiras
    // de página/região). Só emitidos pelo AsmBlockCompiler para LDR/STR/LDRH/STRH com a feature ligada
    // e destino diferente do PC — LDM/STM/LDRD/STRD/LDREX/STREX/SWP continuam chamando os helpers
    // legados acima incondicionalmente (ponto único de emissão, ver emitLoad/emitStore).

    private static final int BITS_PER_BYTE = 8;
    private static final int BYTE_MASK = 0xFF;

    public static int loadWordCrossed(ArmCore core, int address) {
        int value = (core.memory().read8(address) & BYTE_MASK)
                | ((core.memory().read8(address + 1) & BYTE_MASK) << BITS_PER_BYTE)
                | ((core.memory().read8(address + 2) & BYTE_MASK) << (2 * BITS_PER_BYTE))
                | ((core.memory().read8(address + 3) & BYTE_MASK) << (3 * BITS_PER_BYTE));
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_READ);
        return value;
    }

    public static int loadHalfCrossed(ArmCore core, int address) {
        int value = (core.memory().read8(address) & BYTE_MASK)
                | ((core.memory().read8(address + 1) & BYTE_MASK) << BITS_PER_BYTE);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_READ);
        return value;
    }

    public static int loadHalfSignedCrossed(ArmCore core, int address) {
        return (short) loadHalfCrossed(core, address);
    }

    public static void storeWordCrossed(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.memory().write8(address + 1, value >>> BITS_PER_BYTE);
        core.memory().write8(address + 2, value >>> (2 * BITS_PER_BYTE));
        core.memory().write8(address + 3, value >>> (3 * BITS_PER_BYTE));
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 4);
    }

    public static void storeHalfCrossed(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.memory().write8(address + 1, value >>> BITS_PER_BYTE);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 2);
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

    // ── operando shifted-register ──────────────────────────────────────────────

    // Ordinais de ShiftType (LSL/LSR/ASR/ROR) — o bytecode passa `ShiftType.ordinal()`.
    private static final int SHIFT_LSL = 0;
    private static final int SHIFT_LSR = 1;
    private static final int SHIFT_ASR = 2;
    private static final int SHIFT_ROR = 3;

    /// Valor de um operando shifted-register (espelha IrExecutionSupport.shiftedRegisterOperand).
    /// `value` já foi lido pelo bytecode (via register cache); `shiftType` é o ordinal de
    /// ShiftType (LSL/LSR/ASR/ROR); para shift por registrador, `amount` já vem mascarado com
    /// 0xFF e `regSpecified`=true (amount 0 devolve o valor intacto); RRX consome o carry atual.
    public static int shiftedOperand(ArmCore core, int value, int shiftType, int amount,
                                     boolean regSpecified, boolean rrx) {
        if (rrx) {
            return (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
        }
        if (regSpecified && amount == 0) {
            return value;
        }
        return switch (shiftType) {
            case SHIFT_LSL -> amount >= 32 ? 0 : value << amount;
            case SHIFT_LSR -> amount >= 32 ? 0 : value >>> amount;
            case SHIFT_ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            default -> Integer.rotateRight(value, amount & 31);         // ROR
        };
    }

    /// Carry-out do barrel shifter de um operando shifted-register (espelha
    /// IrExecutionSupport.shiftedRegisterCarryOut) — mesmos parâmetros de {@link #shiftedOperand}.
    /// RRX devolve o bit 0 do valor; amount 0 (shift por registrador com Rs&0xFF==0) mantém o
    /// carry atual. Deve ser chamado ANTES de o resultado ser escrito nos registradores/flags.
    public static boolean shiftedOperandCarry(ArmCore core, int value, int shiftType, int amount,
                                              boolean regSpecified, boolean rrx) {
        if (rrx) {
            return (value & 1) != 0;
        }
        if (amount == 0) {
            return core.cpsr().carry();
        }
        return shiftCarryOut(value, shiftType, amount);
    }

    /// Carry-out de um shift efetivo (amount > 0), espelhando IrExecutionSupport.shiftCarryOut.
    private static boolean shiftCarryOut(int value, int shiftType, int amount) {
        if (amount <= 0) {
            return false;
        }
        return switch (shiftType) {
            case SHIFT_LSL -> amount < 32
                    ? ((value >>> (32 - amount)) & 1) != 0
                    : amount == 32 && (value & 1) != 0;
            case SHIFT_LSR -> amount < 32
                    ? ((value >>> (amount - 1)) & 1) != 0
                    : amount == 32 && value < 0;
            case SHIFT_ASR -> amount >= 32 ? value < 0 : ((value >>> (amount - 1)) & 1) != 0;
            default -> { // ROR: reduz módulo 32; múltiplo exato de 32 devolve o bit 31
                int effective = amount & 31;
                yield effective == 0 ? value < 0 : ((value >>> (effective - 1)) & 1) != 0;
            }
        };
    }

    // ── shifts de opcode ALU com S (LSLS/LSRS/ASRS/RORS) ───────────────────────
    // Espelham o caso LSL/LSR/ASR/ROR + setFlags do IrAluExecutor: N/Z do resultado, C = carry
    // do shifter (amount 0 mantém o C atual — vale para registrador E imediato, pois o builder
    // normaliza LSR#0/ASR#0 para #32 e ROR#0 para RRX), V inalterado. Devolvem o resultado.

    public static int doLslS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_LSL, amount);
        int result = doLsl(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }

    public static int doLsrS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_LSR, amount);
        int result = doLsr(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }

    public static int doAsrS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_ASR, amount);
        int result = doAsr(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }

    public static int doRorS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_ROR, amount);
        int result = doRor(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
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

    // ── ARMv6 (B1.2): extend/reverse ────────────────────────────────────────────
    // Espelham os casos SXTB16/UXTB16/REV16/REVSH de IrAluExecutor.execute. SXTB/SXTH/UXTB/UXTH
    // e REV são bytecode direto no AsmBlockCompiler (I2B/I2S/AND/Integer.reverseBytes).

    /// SXTB16/UXTB16: estende os dois bytes pares (bits 23:16 e 7:0) do valor já rotacionado pelo
    /// operando, cada halfword acumulada de forma independente (módulo 2^16, sem carry entre elas).
    public static int extendByte16(int accumulator, int right, boolean signedExtend) {
        int lowByte = signedExtend ? (byte) right : right & 0xFF;
        int highByte = signedExtend ? (byte) (right >>> 16) : (right >>> 16) & 0xFF;
        int lowHalf = (accumulator + lowByte) & 0xFFFF;
        int highHalf = ((accumulator >>> 16) + highByte) & 0xFFFF;
        return (highHalf << 16) | lowHalf;
    }

    /// REV16: troca os bytes dentro de cada halfword.
    public static int reverseHalfwords(int value) {
        return ((value & 0xFF00_FF00) >>> 8) | ((value & 0x00FF_00FF) << 8);
    }

    /// REVSH: inverte os bytes do halfword baixo e estende o sinal para 32 bits.
    public static int reverseSignedHalfword(int value) {
        return (short) (((value & 0xFF) << 8) | ((value >>> 8) & 0xFF));
    }

    // ── ARMv6 (B1.3): paralelas / SEL / saturação / USAD ────────────────────────
    // Espelham IrAluExecutor.executeParallelAlu/executeSel/executeSaturate/executeAbsDiffSum.

    /// Aritmética paralela ARMv6 (SADD16/UQSUB8/SHASX/...): cada lane é computada em precisão
    /// larga (int) e finalizada pela variante — wrap (escrevendo GE), saturação ou halving. As
    /// variantes saturadas paralelas NÃO tocam o flag Q sticky (diferente de QADD/QSUB).
    public static int parallelAlu(ArmCore core, int rn, int rm, int opOrdinal, int variantOrdinal) {
        ParallelAluOp op = ParallelAluOp.values()[opOrdinal];
        ParallelAluVariant variant = ParallelAluVariant.values()[variantOrdinal];
        int result;
        int ge;
        if (op.laneBits() == 8) {
            boolean add = op == ParallelAluOp.ADD8;
            result = 0;
            ge = 0;
            for (int lane = 0; lane < 4; lane++) {
                int shift = lane * 8;
                int a = parallelLaneValue(rn >> shift, 8, variant.unsigned());
                int b = parallelLaneValue(rm >> shift, 8, variant.unsigned());
                int wide = add ? a + b : a - b;
                result |= (parallelFinishLane(wide, 8, variant) & 0xFF) << shift;
                if (parallelLaneGe(wide, 8, add, variant.unsigned())) {
                    ge |= 1 << lane;
                }
            }
        } else {
            // Formas halfword: ASX/SAX cruzam as lanes de Rm e misturam soma/subtração.
            int rnLow = parallelLaneValue(rn, 16, variant.unsigned());
            int rnHigh = parallelLaneValue(rn >> 16, 16, variant.unsigned());
            int rmLow = parallelLaneValue(rm, 16, variant.unsigned());
            int rmHigh = parallelLaneValue(rm >> 16, 16, variant.unsigned());
            boolean lowAdds;
            boolean highAdds;
            int wideLow;
            int wideHigh;
            switch (op) {
                case ADD16 -> { lowAdds = true; highAdds = true; wideLow = rnLow + rmLow; wideHigh = rnHigh + rmHigh; }
                case SUB16 -> { lowAdds = false; highAdds = false; wideLow = rnLow - rmLow; wideHigh = rnHigh - rmHigh; }
                case SAX -> { lowAdds = true; highAdds = false; wideLow = rnLow + rmHigh; wideHigh = rnHigh - rmLow; }
                case ASX -> { lowAdds = false; highAdds = true; wideLow = rnLow - rmHigh; wideHigh = rnHigh + rmLow; }
                default -> throw new IllegalStateException("Lane de 16 bits inesperada: " + op);
            }
            result = (parallelFinishLane(wideLow, 16, variant) & 0xFFFF)
                    | ((parallelFinishLane(wideHigh, 16, variant) & 0xFFFF) << 16);
            ge = (parallelLaneGe(wideLow, 16, lowAdds, variant.unsigned()) ? 0b0011 : 0)
                    | (parallelLaneGe(wideHigh, 16, highAdds, variant.unsigned()) ? 0b1100 : 0);
        }
        if (variant.writesGe()) {
            core.cpsr().setGe(ge);
        }
        return result;
    }

    /// Valor de uma lane já deslocada para os bits baixos, estendida por sinal ou por zero.
    private static int parallelLaneValue(int shifted, int laneBits, boolean unsigned) {
        if (laneBits == 8) {
            return unsigned ? shifted & 0xFF : (byte) shifted;
        }
        return unsigned ? shifted & 0xFFFF : (short) shifted;
    }

    /// Finaliza a lane conforme a variante: wrap (o chamador trunca), saturação ou halving.
    private static int parallelFinishLane(int wide, int laneBits, ParallelAluVariant variant) {
        if (variant.saturating()) {
            int max = variant.unsigned() ? (1 << laneBits) - 1 : (1 << (laneBits - 1)) - 1;
            int min = variant.unsigned() ? 0 : -(1 << (laneBits - 1));
            return Math.clamp(wide, min, max);
        }
        if (variant.halving()) {
            return wide >> 1;
        }
        return wide;
    }

    /// Regra GE por lane (só usada pelas variantes sem prefixo): com sinal, resultado ≥ 0;
    /// sem sinal, carry na soma (estouro da largura) e ausência de borrow na subtração.
    private static boolean parallelLaneGe(int wide, int laneBits, boolean add, boolean unsigned) {
        if (unsigned && add) {
            return wide >= (1 << laneBits);
        }
        return wide >= 0;
    }

    /// `SEL` (ARMv6): cada byte do resultado vem de Rn quando o GE correspondente está setado,
    /// senão de Rm. Não altera flag algum.
    public static int sel(ArmCore core, int rn, int rm) {
        int ge = core.cpsr().ge();
        int mask = 0;
        for (int lane = 0; lane < 4; lane++) {
            if ((ge & (1 << lane)) != 0) {
                mask |= 0xFF << (lane * 8);
            }
        }
        return (rn & mask) | (rm & ~mask);
    }

    /// SSAT/USAT/SSAT16/USAT16 (ARMv6): satura o operando (já shiftado pelo chamador nas formas
    /// word) para a largura pedida e seta o flag Q sticky quando alguma lane saturou.
    public static int saturate(ArmCore core, int value, int saturateBits, boolean unsignedRange, boolean halfwords) {
        boolean[] q = {false};
        int result;
        if (halfwords) {
            int low = saturateTo((short) value, saturateBits, unsignedRange, q);
            int high = saturateTo((short) (value >> 16), saturateBits, unsignedRange, q);
            result = ((high & 0xFFFF) << 16) | (low & 0xFFFF);
        } else {
            result = saturateTo(value, saturateBits, unsignedRange, q);
        }
        if (q[0]) {
            core.cpsr().setSaturation(true); // sticky, como QADD/QSUB
        }
        return result;
    }

    /// Satura `value` para `bits` bits (com ou sem sinal), marcando `q[0]` quando clampa.
    /// Com sinal, `bits` é 1..32 (32 nunca satura); sem sinal, 0..31 (0 clampa tudo para 0).
    private static int saturateTo(int value, int bits, boolean unsignedRange, boolean[] q) {
        int min;
        int max;
        if (unsignedRange) {
            min = 0;
            max = (1 << bits) - 1;
        } else {
            min = -(1 << (bits - 1));
            max = (1 << (bits - 1)) - 1;
        }
        int clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            q[0] = true;
        }
        return clamped;
    }

    /// USAD8/USADA8 (ARMv6): soma das diferenças absolutas dos quatro bytes sem sinal, com
    /// acumulador opcional. Não altera flag algum.
    public static int absDiffSum(int rm, int rs, boolean hasAccumulator, int accumulator) {
        int sum = 0;
        for (int lane = 0; lane < 4; lane++) {
            int shift = lane * 8;
            sum += Math.abs(((rm >> shift) & 0xFF) - ((rs >> shift) & 0xFF));
        }
        if (hasAccumulator) {
            sum += accumulator;
        }
        return sum;
    }

    // ── ARMv6/v6K (B1.4): acessos exclusivos ────────────────────────────────────
    // Espelham IrMemoryExecutor.executeLoadExclusive/executeStoreExclusive/executeClearExclusive.

    /// LDREX{,B,H}: marca o monitor de exclusividade ANTES da leitura (mesma ordem do
    /// interpretador) e devolve o valor lido. A forma doubleword (LDREXD) não passa por este
    /// helper — o AsmBlockCompiler emite {@link #markExclusive} seguido de dois {@code loadWord}.
    public static int loadExclusive(ArmCore core, int address, int sizeBytes) {
        core.markExclusive(Integer.toUnsignedLong(address), sizeBytes);
        return switch (sizeBytes) {
            case 1 -> loadByte(core, address);
            case 2 -> loadHalf(core, address);
            default -> loadWord(core, address);
        };
    }

    /// Marca o monitor de exclusividade sem ler (forma doubleword, LDREXD — os dois
    /// {@code loadWord} são emitidos separadamente pelo AsmBlockCompiler).
    public static void markExclusive(ArmCore core, int address, int sizeBytes) {
        core.markExclusive(Integer.toUnsignedLong(address), sizeBytes);
    }

    /// STREX{,B,H,D}: {@code true} quando o monitor cobre o endereço/tamanho (sucesso — o
    /// CHAMADOR deve então escrever a memória e consumir o monitor); {@code false} = falha,
    /// nenhuma escrita deve ocorrer (mesma ordem do interpretador: checa ANTES de escrever).
    public static boolean exclusiveMonitorCovers(ArmCore core, int address, int sizeBytes) {
        return core.exclusiveMonitorCovers(Integer.toUnsignedLong(address), sizeBytes);
    }

    /// Consome o monitor de exclusividade após um STREX bem-sucedido, ou por CLREX.
    public static void clearExclusiveMonitor(ArmCore core) {
        core.clearExclusiveMonitor();
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
        if (!bus.handles(coprocessorNum, opcode1, crn, crm, opcode2)) {
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
