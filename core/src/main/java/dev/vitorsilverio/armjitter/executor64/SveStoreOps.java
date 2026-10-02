package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Semântica dos stores SVE (B17.18): `ST1` contíguo (todo par `msz`/`esz`), `ST2`/`ST3`/`ST4` entrelaçados (as formas
/// `STNT1`, sem modelo de cache, são `ST1`), `STR` de vetor e de predicado e o scatter (`ST1_zprz`, `ST1_zpiz`,
/// `ST1Q`).
///
/// **Elemento inativo NUNCA escreve** — nem um byte: escrever num elemento inativo corrompe memória do programa (e, em
/// MMIO, dispara efeito colateral). Os elementos são escritos em ordem crescente, e por isso, no scatter, quando dois
/// elementos ativos apontam para o mesmo endereço **o de índice maior vence** (o QEMU, `sve_st1_z`, faz igual).
///
/// Um aborto no meio do store deixa escritos os elementos anteriores ao que falhou: o `AddressSpace64` não tem como
/// "sondar" sem escrever, e o manual permite (o pseudocódigo escreve elemento a elemento). É seguro re-executar — a base,
/// os deslocamentos e o dado são os mesmos, então a repetição regrava os mesmos bytes. Diferença conhecida para o QEMU, que
/// sonda todas as páginas antes do primeiro byte.
///
/// Só os stores contíguos e o `STR` valem em modo streaming; o scatter e o `ST1Q` são ilegais nele (a menos que
/// `FEAT_SME_FA64` esteja efetivo), assim como o `ST1` de elemento de 128 bits (`.Q`).
final class SveStoreOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int BITS_PER_BYTE = Byte.SIZE;
    private static final int WORD_BYTES = Long.BYTES;
    private static final int QUADWORD_BYTES = 16;
    private static final int WORDS_PER_QUADWORD = QUADWORD_BYTES / WORD_BYTES;
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int VECTOR_REGISTER_MASK = 31;
    private static final int ESZ_QUAD = 4;
    private static final int MSZ_QUAD = 4;
    private static final int ESZ_DOUBLE = 3;
    private static final long WORD_OFFSET_MASK = 0xFFFFFFFFL;
    private static final int VECTOR_BYTES_PER_PREDICATE_BYTE = 8;

    private SveStoreOps() {
    }

    /// Executa uma instrução do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, SveMemoryOp64.Store op) {
        if (op.nonStreaming()) {
            SvePredicateOps.requireNonStreaming(core);
        }
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        switch (op.op()) {
            case ST1 -> storeStructures(core, op);
            case STR_Z -> storeVectorRegister(core, op);
            case STR_P -> storePredicateRegister(core, op);
            case SCATTER_VECTOR_INDEX -> scatterVectorIndex(core, op);
            case SCATTER_VECTOR_BASE -> scatterVectorBase(core, op);
            case ST1Q -> scatterQuadword(core, op);
            case SCATTER_VECTOR_PLUS_SCALAR -> scatterVectorPlusScalar(core, op);
        }
        return false;
    }

    // ── Endereço e elementos ─────────────────────────────────────────────────────────────────────

    private static long base(Aarch64Core core, SveMemoryOp64.Store op) {
        return op.rn() == STACK_POINTER_ENCODING ? core.sp() : core.x(op.rn());
    }

    /// Endereço do primeiro elemento de `ST1`/`ST[234]`.
    private static long contiguousAddress(Aarch64Core core, SveMemoryOp64.Store op) {
        long base = base(core, op);
        if (op.registerOffset()) {
            return base + (core.x(op.rm()) << op.msz());
        }
        long elements = core.vectorLengthBytes() >> op.esz();
        return base + ((op.immediate() * elements * (op.nreg() + 1)) << op.msz());
    }

    private static boolean active(long[] predicate, int element, int esz) {
        int bit = element << esz;
        return ((predicate[bit >>> WORD_INDEX_SHIFT] >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    /// Os 64 bits baixos do elemento `element` (de `esz` bytes-log2) do vetor `register`. Em `esz = 4` devolve só a
    /// metade baixa; a alta vem de {@link #highWord}.
    static long element(Aarch64ScalableRegisters regs, int register, int element, int esz) {
        if (esz == ESZ_DOUBLE) {
            return regs.zWord(register, element);
        }
        int elementBits = BITS_PER_BYTE << esz;
        int bitOffset = element * elementBits;
        long mask = (1L << elementBits) - 1L;
        return (regs.zWord(register, bitOffset >>> WORD_INDEX_SHIFT) >>> (bitOffset & WORD_BIT_MASK)) & mask;
    }

    private static long highWord(Aarch64ScalableRegisters regs, int register, int quadword) {
        return regs.zWord(register, quadword * WORDS_PER_QUADWORD + 1);
    }

    static void writeMemory(AddressSpace64 memory, long address, int msz, long value) {
        switch (msz) {
            case 0 -> memory.write8(address, (int) value);
            case 1 -> memory.write16(address, (int) value);
            case 2 -> memory.write32(address, (int) value);
            default -> memory.write64(address, value);
        }
    }

    /// Escreve UM elemento (`msz`/`esz` da instrução) do vetor `register` em `address`. Em `esz = 4` o elemento tem 128
    /// bits: as formas `ST1W`/`ST1D` `.Q` gravam só os `msz` bytes baixos dele e `ST[234]Q` gravam os 128 bits.
    private static void storeElement(AddressSpace64 memory, long address, SveMemoryOp64.Store op,
            Aarch64ScalableRegisters regs, int register, int element) {
        if (op.esz() == ESZ_QUAD) {
            writeMemory(memory, address, Math.min(op.msz(), ESZ_QUAD - 1), regs.zWord(register, element * WORDS_PER_QUADWORD));
            if (op.msz() == MSZ_QUAD) {
                memory.write64(address + WORD_BYTES, highWord(regs, register, element));
            }
            return;
        }
        writeMemory(memory, address, op.msz(), element(regs, register, element, op.esz()));
    }

    // ── ST1 / ST2 / ST3 / ST4 (e STNT1) ──────────────────────────────────────────────────────────

    private static void storeStructures(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int registers = op.nreg() + 1;
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long start = contiguousAddress(core, op);
        for (int e = 0; e < elements; e++) {
            if (!active(predicate, e, op.esz())) {
                continue;
            }
            for (int k = 0; k < registers; k++) {
                long address = start + (((long) e * registers + k) << op.msz());
                storeElement(memory, address, op, regs, (op.rt() + k) & VECTOR_REGISTER_MASK, e);
            }
        }
    }

    // ── Scatter ──────────────────────────────────────────────────────────────────────────────────

    /// `ST1_zprz`: `Xn|SP + (Zm[e] estendido << (scaled ? msz : 0))`. Com `esz = 3` e `xs` = `UXTW`/`SXTW` só os 32 bits
    /// baixos de cada elemento de 64 bits são o deslocamento.
    private static void scatterVectorIndex(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long base = base(core, op);
        int shift = op.scaled() ? op.msz() : 0;
        for (int e = 0; e < elements; e++) {
            if (!active(predicate, e, op.esz())) {
                continue;
            }
            long offset = element(regs, op.rm(), e, op.esz());
            offset = switch (op.offsetExtend()) {
                case SveMemoryOp64.Store.OFFSET_UXTW -> offset & WORD_OFFSET_MASK;
                case SveMemoryOp64.Store.OFFSET_SXTW -> (long) (int) offset;
                default -> offset;
            };
            writeMemory(memory, base + (offset << shift), op.msz(), element(regs, op.rt(), e, op.esz()));
        }
    }

    /// `ST1_zpiz`: `Zn[e] (zero-estendido em 32 bits) + (imm5 << msz)`.
    private static void scatterVectorBase(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long displacement = op.immediate() << op.msz();
        for (int e = 0; e < elements; e++) {
            if (!active(predicate, e, op.esz())) {
                continue;
            }
            long address = element(regs, op.rn(), e, op.esz()) + displacement;
            writeMemory(memory, address, op.msz(), element(regs, op.rt(), e, op.esz()));
        }
    }

    /// `STNT1_zprz`: `Zn[e] (zero-estendido em 32 bits quando `esz = 2`) + Xm` (`XZR` se `31`). "Non-temporal" é só hint de
    /// cache: sem modelo de cache, o acesso é o de um `ST1`.
    private static void scatterVectorPlusScalar(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long offset = core.x(op.rm());
        for (int e = 0; e < elements; e++) {
            if (!active(predicate, e, op.esz())) {
                continue;
            }
            writeMemory(memory, element(regs, op.rn(), e, op.esz()) + offset, op.msz(), element(regs, op.rt(), e, op.esz()));
        }
    }

    /// `ST1Q`: um quadword por segmento de 128 bits; o predicado usa o bit de índice `16 × segmento`, o endereço é o
    /// `D[0]` do segmento de `Zn` mais `Xm` (`XZR` se `31`) e o dado são os 128 bits do segmento de `Zt`.
    private static void scatterQuadword(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int segments = core.vectorLengthBytes() / QUADWORD_BYTES;
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long offset = core.x(op.rm());
        for (int s = 0; s < segments; s++) {
            if (!active(predicate, s, ESZ_QUAD)) {
                continue;
            }
            long address = regs.zWord(op.rn(), s * WORDS_PER_QUADWORD) + offset;
            memory.write64(address, regs.zWord(op.rt(), s * WORDS_PER_QUADWORD));
            memory.write64(address + WORD_BYTES, highWord(regs, op.rt(), s));
        }
    }

    // ── STR de vetor e de predicado ──────────────────────────────────────────────────────────────

    private static void storeVectorRegister(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        long start = base(core, op) + op.immediate() * core.vectorLengthBytes();
        for (int w = 0; w < core.vectorLengthBytes() / WORD_BYTES; w++) {
            core.memory().write64(start + (long) w * WORD_BYTES, regs.zWord(op.rt(), w));
        }
    }

    private static void storePredicateRegister(Aarch64Core core, SveMemoryOp64.Store op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int predicateBytes = core.vectorLengthBytes() / VECTOR_BYTES_PER_PREDICATE_BYTE;
        long start = base(core, op) + op.immediate() * predicateBytes;
        for (int i = 0; i < predicateBytes; i++) {
            long word = regs.pWord(op.rt(), i / WORD_BYTES);
            core.memory().write8(start + i, (int) (word >>> ((i % WORD_BYTES) * BITS_PER_BYTE)));
        }
    }
}
