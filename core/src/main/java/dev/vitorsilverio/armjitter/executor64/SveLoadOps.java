package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

/// Semântica dos loads contíguos SVE (B17.17): `LD1` (todos os `dtype`) e `LD2`/`LD3`/`LD4` desentrelaçados, `LD1R*`,
/// `LD1RQ`/`LD1RO`, `LDR` de vetor e de predicado, `PRF*` (hint: no-op) e a classe própria **`LDFF1`/`LDNF1`**.
///
/// **Elemento inativo não acessa a memória** — nem para aborto, nem para `FFR`, nem para efeito colateral de MMIO — e
/// vira zero no destino. **Todo load lê primeiro para um buffer e só então grava os registradores**: um aborto no meio
/// de `LD1`/`LD[234]`/`LDR` deixa `Z`/`P` intactos (o modelo de aborto preciso da B6.6.4).
///
/// **`LDFF1`/`LDNF1` são a exceção deliberada a esse modelo** e vivem aqui, num caminho separado, sem tocar o executor de
/// `Load` comum. Em `LDFF1` o PRIMEIRO elemento ativo é um load normal (a falha é um aborto real); a partir do
/// segundo, uma falha de tradução é capturada — a exceção é sem estado e sem `stack trace`, então "tentar sem abortar"
/// é só capturá-la — e o `FFR` é zerado **do elemento que falhou em diante** (a operação só limpa bits, nunca os liga, e
/// não altera os de baixo). Em `LDNF1` nem o primeiro elemento aborta. O vetor sai zerado do elemento que falhou em
/// diante (o QEMU faz o mesmo: o valor de um elemento com falha é `UNKNOWN` no manual). Predicado sem elemento ativo:
/// vetor zerado e `FFR` intacto. Diferença para o QEMU, que é permitida pelo manual: ele recusa (registra falha para)
/// todo elemento do lado da segunda página e todo MMIO; aqui cada elemento é tentado de verdade, porque o
/// `AddressSpace64` não informa o tipo de memória — um `LDNF1` sobre MMIO PODE, portanto, atingir o dispositivo.
///
/// `LDFF1`, `LDNF1`, `LD1RO`, `LD1W`/`LD1D` de elemento de 128 bits e `PRF_ns` são ilegais em modo streaming.
final class SveLoadOps {
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;
    private static final int BITS_PER_BYTE = Byte.SIZE;
    private static final int WORD_BYTES = Long.BYTES;
    private static final int QUADWORD_BYTES = 16;
    private static final int OCTAWORD_BYTES = 32;
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int VECTOR_REGISTER_MASK = 31;
    private static final int ESZ_QUAD = 4;
    private static final int MSZ_QUAD = 4;
    private static final int MSZ_DOUBLE = 3;
    private static final int VECTOR_BYTES_PER_PREDICATE_BYTE = 8;

    private SveLoadOps() {
    }

    /// Executa uma instrução do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveLoad op) {
        if (op.nonStreaming()) {
            SvePredicateOps.requireNonStreaming(core);
        }
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        switch (op.op()) {
            case PRF -> { /* hint: sem efeito arquitetural observável */ }
            case LDR_Z -> loadVectorRegister(core, op);
            case LDR_P -> loadPredicateRegister(core, op);
            case LD1 -> loadStructures(core, op);
            case LDFF1, LDNF1 -> loadFaultTolerant(core, op);
            case LD1R -> loadReplicate(core, op);
            case LD1RQ, LD1RO -> loadBroadcast(core, op);
        }
        return false;
    }

    // ── Endereço ─────────────────────────────────────────────────────────────────────────────────

    private static long base(Aarch64Core core, Ir64Op.SveLoad op) {
        return op.rn() == STACK_POINTER_ENCODING ? core.sp() : core.x(op.rn());
    }

    /// Endereço do primeiro elemento das formas `LD1`/`LD[234]`/`LDFF1`/`LDNF1`.
    private static long contiguousAddress(Aarch64Core core, Ir64Op.SveLoad op) {
        long base = base(core, op);
        if (op.registerOffset()) {
            return base + (core.x(op.rm()) << op.msz());
        }
        long elements = core.vectorLengthBytes() >> op.esz();
        long multiplier = op.op() == Ir64Op.SveLoad.Op.LD1 ? op.nreg() + 1 : 1;
        return base + ((op.immediate() * elements * multiplier) << op.msz());
    }

    private static boolean active(long[] predicate, int element, int esz) {
        int bit = element << esz;
        return ((predicate[bit >>> WORD_INDEX_SHIFT] >>> (bit & WORD_BIT_MASK)) & 1L) != 0L;
    }

    // ── Acesso à memória ─────────────────────────────────────────────────────────────────────────

    private static long readMemory(AddressSpace64 memory, long address, int msz) {
        return switch (msz) {
            case 0 -> Byte.toUnsignedLong((byte) memory.read8(address));
            case 1 -> Short.toUnsignedLong((short) memory.read16(address));
            case 2 -> Integer.toUnsignedLong(memory.read32(address));
            default -> memory.read64(address);
        };
    }

    private static long signExtend(long value, int msz) {
        int shift = Long.SIZE - (BITS_PER_BYTE << msz);
        return (value << shift) >> shift;
    }

    /// Lê UM elemento (`msz`/`esz`/extensão da instrução) de `address` e o grava no elemento `element` de `words`. Em
    /// `esz = 4` o elemento tem 128 bits: `LD1W`/`LD1D` `.Q` zero-estendem o dado até ele; `LD[234]Q` leem 128 bits.
    private static void loadElement(AddressSpace64 memory, long address, Ir64Op.SveLoad op, long[] words, int element) {
        if (op.esz() == ESZ_QUAD) {
            long low = readMemory(memory, address, Math.min(op.msz(), MSZ_DOUBLE));
            long high = op.msz() == MSZ_QUAD ? memory.read64(address + WORD_BYTES) : 0L;
            words[element * 2] = low;
            words[element * 2 + 1] = high;
            return;
        }
        long value = readMemory(memory, address, op.msz());
        putElement(words, element, op.esz(), op.signExtend() ? signExtend(value, op.msz()) : value);
    }

    private static void putElement(long[] words, int element, int esz, long value) {
        int elementBits = BITS_PER_BYTE << esz;
        int bitOffset = element * elementBits;
        if (elementBits == Long.SIZE) {
            words[element] = value;
            return;
        }
        long mask = (1L << elementBits) - 1L;
        words[bitOffset >>> WORD_INDEX_SHIFT] |= (value & mask) << (bitOffset & WORD_BIT_MASK);
    }

    private static void commitVector(Aarch64Core core, int register, long[] words) {
        Aarch64ScalableRegisters regs = core.scalable();
        for (int w = 0; w < words.length; w++) {
            regs.setZWord(register, w, words[w]);
        }
    }

    private static int vectorWords(Aarch64Core core) {
        return core.vectorLengthBytes() / WORD_BYTES;
    }

    // ── LD1 / LD2 / LD3 / LD4 (e LDNT1) ──────────────────────────────────────────────────────────

    private static void loadStructures(Aarch64Core core, Ir64Op.SveLoad op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int registers = op.nreg() + 1;
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long start = contiguousAddress(core, op);
        long[][] result = new long[registers][vectorWords(core)];
        for (int e = 0; e < elements; e++) {
            if (!active(predicate, e, op.esz())) {
                continue;
            }
            for (int k = 0; k < registers; k++) {
                long address = start + (((long) e * registers + k) << op.msz());
                loadElement(memory, address, op, result[k], e);
            }
        }
        for (int k = 0; k < registers; k++) {
            commitVector(core, (op.rd() + k) & VECTOR_REGISTER_MASK, result[k]);
        }
    }

    // ── LDFF1 / LDNF1 ────────────────────────────────────────────────────────────────────────────

    private static void loadFaultTolerant(Aarch64Core core, Ir64Op.SveLoad op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long start = contiguousAddress(core, op);
        long[] result = new long[vectorWords(core)];
        boolean first = true;
        int faulted = -1;
        for (int e = 0; e < elements; e++) {
            if (!active(predicate, e, op.esz())) {
                continue;
            }
            try {
                loadElement(memory, start + ((long) e << op.msz()), op, result, e);
            } catch (MemoryTranslationException64 fault) {
                if (first && op.op() == Ir64Op.SveLoad.Op.LDFF1) {
                    throw fault; // o primeiro elemento ativo de LDFF1 é um load normal: aborto real
                }
                faulted = e;
                break;
            }
            first = false;
        }
        if (faulted >= 0) {
            recordFault(regs, faulted << op.esz());
        }
        commitVector(core, op.rd(), result);
    }

    /// Zera os bits do `FFR` do bit `bit` em diante (nunca liga um bit, nunca mexe nos de baixo).
    private static void recordFault(Aarch64ScalableRegisters regs, int bit) {
        int word = bit >>> WORD_INDEX_SHIFT;
        int inWord = bit & WORD_BIT_MASK;
        if (inWord != 0) {
            regs.setFfrWord(word, regs.ffrWord(word) & ((1L << inWord) - 1L));
            word++;
        }
        for (; word < regs.wordsPerPredicate(); word++) {
            regs.setFfrWord(word, 0L);
        }
    }

    // ── LD1R* ────────────────────────────────────────────────────────────────────────────────────

    private static void loadReplicate(Aarch64Core core, Ir64Op.SveLoad op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long[] result = new long[vectorWords(core)];
        boolean anyActive = false;
        for (int e = 0; e < elements && !anyActive; e++) {
            anyActive = active(predicate, e, op.esz());
        }
        if (anyActive) {
            long value = readMemory(core.memory(), base(core, op) + (op.immediate() << op.msz()), op.msz());
            if (op.signExtend()) {
                value = signExtend(value, op.msz());
            }
            for (int e = 0; e < elements; e++) {
                if (active(predicate, e, op.esz())) {
                    putElement(result, e, op.esz(), value);
                }
            }
        }
        commitVector(core, op.rd(), result);
    }

    // ── LD1RQ / LD1RO ────────────────────────────────────────────────────────────────────────────

    /// Carrega o primeiro quadword (`LD1RQ`) ou octaword (`LD1RO`) sob o predicado — só os bits do PRIMEIRO segmento
    /// contam — e o replica pelo vetor. No `LD1RO` a replicação é em unidades de 32 bytes e a sobra (VL que não é
    /// múltiplo de 32) fica zerada; com `VL < 256` a instrução é indefinida.
    private static void loadBroadcast(Aarch64Core core, Ir64Op.SveLoad op) {
        boolean octaword = op.op() == Ir64Op.SveLoad.Op.LD1RO;
        int chunkBytes = octaword ? OCTAWORD_BYTES : QUADWORD_BYTES;
        int vectorBytes = core.vectorLengthBytes();
        if (vectorBytes < chunkBytes) {
            throw new Aarch64UndefinedInstructionException();
        }
        Aarch64ScalableRegisters regs = core.scalable();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long start = op.registerOffset()
                ? base(core, op) + (core.x(op.rm()) << op.msz())
                : base(core, op) + op.immediate() * chunkBytes;
        int chunkWords = chunkBytes / WORD_BYTES;
        long[] chunk = new long[chunkWords];
        int chunkElements = chunkBytes >> op.esz();
        for (int e = 0; e < chunkElements; e++) {
            if (active(predicate, e, op.esz())) {
                loadElement(core.memory(), start + ((long) e << op.msz()), op, chunk, e);
            }
        }
        long[] result = new long[vectorWords(core)];
        int replicated = vectorBytes / chunkBytes * chunkWords;
        for (int w = 0; w < replicated; w++) {
            result[w] = chunk[w % chunkWords];
        }
        commitVector(core, op.rd(), result);
    }

    // ── LDR de vetor e de predicado ──────────────────────────────────────────────────────────────

    private static void loadVectorRegister(Aarch64Core core, Ir64Op.SveLoad op) {
        int vectorBytes = core.vectorLengthBytes();
        long start = base(core, op) + op.immediate() * vectorBytes;
        long[] result = new long[vectorWords(core)];
        for (int w = 0; w < result.length; w++) {
            result[w] = core.memory().read64(start + (long) w * WORD_BYTES);
        }
        commitVector(core, op.rd(), result);
    }

    private static void loadPredicateRegister(Aarch64Core core, Ir64Op.SveLoad op) {
        Aarch64ScalableRegisters regs = core.scalable();
        int predicateBytes = core.vectorLengthBytes() / VECTOR_BYTES_PER_PREDICATE_BYTE;
        long start = base(core, op) + op.immediate() * predicateBytes;
        long[] result = new long[regs.wordsPerPredicate()];
        for (int i = 0; i < predicateBytes; i++) {
            long value = Byte.toUnsignedLong((byte) core.memory().read8(start + i));
            result[i / WORD_BYTES] |= value << ((i % WORD_BYTES) * BITS_PER_BYTE);
        }
        SvePredicateOps.write(regs, op.rd(), result);
    }
}
