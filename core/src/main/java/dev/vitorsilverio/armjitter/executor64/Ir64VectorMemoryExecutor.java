package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;

/// Semântica dos load/store de estruturas AdvSIMD (`LD1`-`LD4`/`ST1`-`ST4`, `LDnR`) e das formas de
/// movimento vetorial de {@link AdvSimdMoveOp64} que viviam em {@link Ir64BlockExecutor}.
///
/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}
/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.
public final class Ir64VectorMemoryExecutor {
    private Ir64VectorMemoryExecutor() {
    }

    /// `DUP` escalar (B19.6 bloco E) — grava só o elemento no lane `0` de {@link AdvSimdMoveOp64.DuplicateElementScalar#rd}
    /// e ZERA o resto do registrador de 128 bits ({@link Aarch64FpRegisters#setQ} com `hi=0` e `lo`
    /// mascarado ao tamanho do elemento).
    public static boolean executeDuplicateElementScalar(Aarch64Core core, AdvSimdMoveOp64.DuplicateElementScalar op) {
        Aarch64FpRegisters fp = core.fp();
        long value = fp.element(op.rn(), op.index(), op.esz());
        int elementBits = 8 << op.esz();
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        fp.setQ(op.rd(), value & mask, 0L);
        return false;
    }

    /// `MOVI`/`MVNI`/`ORR`/`BIC`/`FMOV` imediato AdvSIMD de 64 bits (B19.6 bloco G) — `imm64` já
    /// vem EXPANDIDO do decoder (núcleo compartilhado {@code AdvSimdModifiedImmediate}, ver javadoc
    /// de {@link AdvSimdMoveOp64.ModifiedImmediate64}). Aplica a MESMA operação a cada metade de 64
    /// bits independentemente; `!q` zera a metade alta (escrita destrutiva normal de A64).
    public static boolean executeAdvSimdModifiedImmediate64(Aarch64Core core, AdvSimdMoveOp64.ModifiedImmediate64 op) {
        Aarch64FpRegisters fp = core.fp();
        long currentLo = fp.word(op.rd() * 2);
        long currentHi = op.q() ? fp.word(op.rd() * 2 + 1) : 0L;
        long lo = applyAdvSimdModifiedImmediate(op.op(), currentLo, op.imm64());
        long hi = op.q() ? applyAdvSimdModifiedImmediate(op.op(), currentHi, op.imm64()) : 0L;
        fp.setQ(op.rd(), lo, hi);
        return false;
    }

    private static long applyAdvSimdModifiedImmediate(
            AdvSimdModifiedImmediateOp op, long current, long imm64) {
        return switch (op) {
            case MOV -> imm64;
            case MVN -> ~imm64;
            case ORR -> current | imm64;
            case BIC -> current & ~imm64;
        };
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store MULTIPLE structures, B8.6) — semântica conferida
    /// contra `trans_LD_mult`/`trans_ST_mult` reais do QEMU, ver {@link AdvSimdMoveOp64.LoadStoreMultiple}.
    public static boolean executeVectorLoadStoreMultiple(Aarch64Core core, AdvSimdMoveOp64.LoadStoreMultiple op) {
        long base = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
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
                        fp.setElement(register, e, op.elementSizeLog2(), Ir64MemoryExecutor.readMemory(core, address, size));
                    } else {
                        Ir64MemoryExecutor.writeMemory(core, address, size, fp.element(register, e, op.elementSizeLog2()));
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
    /// {@link AdvSimdMoveOp64.LoadStoreSingle}.
    public static boolean executeVectorLoadStoreSingle(Aarch64Core core, AdvSimdMoveOp64.LoadStoreSingle op) {
        long base = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        long address = base;
        Ir64MemSize size = memSizeForElementLog2(op.elementSizeLog2());
        int elementBytes = 1 << op.elementSizeLog2();
        Aarch64FpRegisters fp = core.fp();
        for (int xs = 0; xs < op.selem(); xs++) {
            int register = (op.rt() + xs) % Aarch64FpRegisters.V_REGISTER_COUNT;
            if (op.load()) {
                fp.setElement(register, op.index(), op.elementSizeLog2(), Ir64MemoryExecutor.readMemory(core, address, size));
            } else {
                Ir64MemoryExecutor.writeMemory(core, address, size, fp.element(register, op.index(), op.elementSizeLog2()));
                core.notifyOrdinaryWrite(address, elementBytes);
            }
            address += elementBytes;
        }
        long total = (long) op.selem() * elementBytes;
        writeVectorPostIndex(core, op.rn(), op.postIndex(), op.rm(), base, total);
        return false;
    }

    /// `LD1R`-`LD4R` (AdvSIMD load single structure and replicate, B8.6) — semântica conferida
    /// contra `trans_LD_single_repl` real do QEMU, ver {@link AdvSimdMoveOp64.LoadSingleReplicate}.
    public static boolean executeVectorLoadSingleReplicate(Aarch64Core core, AdvSimdMoveOp64.LoadSingleReplicate op) {
        long base = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        long address = base;
        Ir64MemSize size = memSizeForElementLog2(op.elementSizeLog2());
        int elementBytes = 1 << op.elementSizeLog2();
        Aarch64FpRegisters fp = core.fp();
        for (int xs = 0; xs < op.selem(); xs++) {
            int register = (op.rt() + xs) % Aarch64FpRegisters.V_REGISTER_COUNT;
            fp.replicateElement(register, Ir64MemoryExecutor.readMemory(core, address, size), op.elementSizeLog2(), op.q());
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
        Ir64MemoryExecutor.writeBaseRegister(core, rn, newBase);
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
}
