package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize;

/// Semântica dos load/store SIMD&FP escalares A64 (records de memória de {@link FpOp64}) — a
/// aritmética de ponto flutuante fica em {@link Ir64FpExecutor}.
///
/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}
/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.
public final class Ir64FpMemoryExecutor {
    private Ir64FpMemoryExecutor() {
    }

    /// `FMOV Xd, Vn.D[1]` / `FMOV Vd.D[1], Xn` (B19.6 bloco F) — ver javadoc de
    /// {@link FpOp64.HighHalfMove}: sentido GPR→FP PRESERVA a metade baixa (exceção à escrita
    /// destrutiva normal de A64).
    public static boolean executeFpHighHalfMove(Aarch64Core core, FpOp64.HighHalfMove op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.toFloat()) {
            fp.setQ(op.fpReg(), fp.word(op.fpReg() * 2), core.x(op.gpReg()));
        } else {
            core.setX(op.gpReg(), fp.word(op.fpReg() * 2 + 1));
        }
        return false;
    }

    /// `LDR` SIMD&FP registrador-imediato (B8.13): mesma resolução de endereço de
    /// {@link Ir64MemoryExecutor#executeLoad}; `Q` (128 bits) usa {@link Aarch64FpRegisters#setQ} direto (2 leituras
    /// de 64 bits), os demais tamanhos usam {@link Aarch64FpRegisters#setScalar} (escrita
    /// destrutiva — zera o resto do registro, comportamento arquitetural real).
    public static boolean executeFpLoad(Aarch64Core core, FpOp64.Load64 op) {
        long base = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        long address = Ir64MemoryExecutor.transferAddress(core, base, op.addressingMode(), op.immediate(),
                op.rm(), op.extendType(), op.shiftAmount());
        Aarch64FpRegisters fp = core.fp();
        if (op.size() == Ir64FpMemSize.QUAD) {
            fp.setQ(op.vt(), core.memory().read64(address), core.memory().read64(address + Long.BYTES));
        } else {
            fp.setScalar(op.vt(), op.size().sizeLog2(), readFpMemory(core, address, op.size()));
        }
        Ir64MemoryExecutor.writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// `STR` SIMD&FP registrador-imediato (B8.13) — espelho de {@link #executeFpLoad}.
    public static boolean executeFpStore(Aarch64Core core, FpOp64.Store64 op) {
        long base = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        long address = Ir64MemoryExecutor.transferAddress(core, base, op.addressingMode(), op.immediate(),
                op.rm(), op.extendType(), op.shiftAmount());
        Aarch64FpRegisters fp = core.fp();
        if (op.size() == Ir64FpMemSize.QUAD) {
            core.memory().write64(address, fp.low64(op.vt()));
            core.memory().write64(address + Long.BYTES, fp.high64(op.vt()));
        } else {
            writeFpMemory(core, address, op.size(), fp.element(op.vt(), 0, op.size().sizeLog2()));
        }
        core.notifyOrdinaryWrite(address, op.size().bytes());
        Ir64MemoryExecutor.writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// `LDP`/`STP` SIMD&FP (B8.13) — mesma resolução de endereço/Ir64MemoryExecutor.writeback de
    /// {@link Ir64MemoryExecutor#executeLoadStorePair}, sem forma com sinal (SIMD&FP não tem `LDPSW`).
    public static boolean executeFpLoadStorePair(Aarch64Core core, FpOp64.LoadStorePair op) {
        long base = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        long address = op.addressingMode() == Ir64AddressingMode.POST_INDEX
                ? base : base + op.immediate();
        int stride = op.size().bytes();
        Aarch64FpRegisters fp = core.fp();
        if (op.load()) {
            if (op.size() == Ir64FpMemSize.QUAD) {
                fp.setQ(op.vt(), core.memory().read64(address), core.memory().read64(address + Long.BYTES));
                fp.setQ(op.vt2(), core.memory().read64(address + stride),
                        core.memory().read64(address + stride + Long.BYTES));
            } else {
                fp.setScalar(op.vt(), op.size().sizeLog2(), readFpMemory(core, address, op.size()));
                fp.setScalar(op.vt2(), op.size().sizeLog2(), readFpMemory(core, address + stride, op.size()));
            }
        } else {
            if (op.size() == Ir64FpMemSize.QUAD) {
                core.memory().write64(address, fp.low64(op.vt()));
                core.memory().write64(address + Long.BYTES, fp.high64(op.vt()));
                core.memory().write64(address + stride, fp.low64(op.vt2()));
                core.memory().write64(address + stride + Long.BYTES, fp.high64(op.vt2()));
            } else {
                writeFpMemory(core, address, op.size(), fp.element(op.vt(), 0, op.size().sizeLog2()));
                writeFpMemory(core, address + stride, op.size(), fp.element(op.vt2(), 0, op.size().sizeLog2()));
            }
            core.notifyOrdinaryWrite(address, stride);
            core.notifyOrdinaryWrite(address + stride, stride);
        }
        if (op.addressingMode() == Ir64AddressingMode.PRE_INDEX
                || op.addressingMode() == Ir64AddressingMode.POST_INDEX) {
            Ir64MemoryExecutor.writeBaseRegister(core, op.rn(), base + op.immediate());
        }
        return false;
    }

    /// `LDR (literal)` SIMD&FP (B8.13) — mesma convenção de endereço já resolvido de
    /// {@link Ir64MemoryExecutor#executeLoadLiteral}.
    public static boolean executeFpLoadLiteral(Aarch64Core core, FpOp64.LoadLiteral64 op) {
        Aarch64FpRegisters fp = core.fp();
        if (op.size() == Ir64FpMemSize.QUAD) {
            fp.setQ(op.vt(), core.memory().read64(op.address()), core.memory().read64(op.address() + Long.BYTES));
        } else {
            fp.setScalar(op.vt(), op.size().sizeLog2(), readFpMemory(core, op.address(), op.size()));
        }
        return false;
    }

    /// Lê `size.bytes()` da memória, zero-estendido em `long` — irmão de {@link Ir64MemoryExecutor#readMemory} para
    /// {@link Ir64FpMemSize} (B8.13). `QUAD` (128 bits) NUNCA chega aqui — os chamadores tratam
    /// `Q` à parte via 2 leituras de 64 bits direto em {@link Aarch64FpRegisters#setQ}.
    private static long readFpMemory(Aarch64Core core, long address, Ir64FpMemSize size) {
        return switch (size) {
            case BYTE -> Byte.toUnsignedLong((byte) core.memory().read8(address));
            case HALF -> Short.toUnsignedLong((short) core.memory().read16(address));
            case SINGLE -> Integer.toUnsignedLong(core.memory().read32(address));
            case DOUBLE -> core.memory().read64(address);
            case QUAD -> throw new IllegalStateException("QUAD tratado à parte (128 bits)");
        };
    }

    /// Irmão de {@link #readFpMemory} para escrita — mesma exceção de `QUAD`.
    private static void writeFpMemory(Aarch64Core core, long address, Ir64FpMemSize size, long value) {
        switch (size) {
            case BYTE -> core.memory().write8(address, (int) value);
            case HALF -> core.memory().write16(address, (int) value);
            case SINGLE -> core.memory().write32(address, (int) value);
            case DOUBLE -> core.memory().write64(address, value);
            case QUAD -> throw new IllegalStateException("QUAD tratado à parte (128 bits)");
        }
    }
}
