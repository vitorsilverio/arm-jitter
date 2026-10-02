package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SvePredicateOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Predicado-como-contador do SVE2.1 (B17.28): o modelo do contador e as instruções que o consomem — `PTRUE`, `CNTP` e
/// `PEXT` ({@link SvePredicateOp64.CounterPredicate}) e os `LD1`/`ST1` multi-vetor ({@link SveMemoryOp64.MultiVectorMemory}). O
/// `WHILE*` que ESCREVE um contador vive em {@link SveCompareOps}, junto dos demais `WHILE`, e usa {@link #encode}.
///
/// **O contador NÃO é uma máscara de bits.** Um `PNn` (`n = 8..15`) é o registrador `Pn` lido pelos seus 16 bits baixos:
/// o bit 15 é `invert`, o bit menos significativo ligado entre os bits `3:0` dá o tamanho de elemento do CONTADOR
/// (`p_esz`) e os bits acima dele guardam a contagem. Nada é guardado em estrutura paralela: `MOV P8.B, P0.B` trata o
/// mesmo registrador como máscara. Transcrito de `decode_counter` (`vec_internal.h`) e dos helpers `sve2p1_cntp_c`, `pext`
/// e `sve2p1_cont_ldst_elements` (`sve_helper.c`) do QEMU, que seguem o `CounterToPredicate` do manual.
///
/// **Elemento inativo não acessa memória**, e os loads leem tudo para um buffer antes de gravar `Z` (aborto preciso).
final class SveCounterOps {
    private static final int COUNTER_REGISTER_MASK = 0xFFFF;
    private static final int COUNTER_ESZ_FIELD_MASK = 0b1111;
    private static final int COUNTER_INVERT_BIT = 15;
    private static final int COUNTER_MASK_SHIFT = 3;
    private static final int PREDICATE_REGISTERS = 16;
    private static final int VECTOR_REGISTER_MASK = 31;
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int WORD_BYTES = Long.BYTES;

    private SveCounterOps() {
    }

    /// O contador decodificado (`DecodeCounter` do QEMU): `count` elementos do tamanho do VETOR, `lg2Stride` (só se o
    /// tamanho do contador for MAIOR que o do vetor: só cada `2^lg2Stride`-ésimo elemento conta) e `invert`.
    record Counter(int count, int lg2Stride, boolean invert) {
        /// `CounterToPredicate`: `png` são os 16 bits baixos do `PNn`; `vectorBytes` é o `VL`; `vectorEsz` o log2 do
        /// tamanho de elemento da instrução.
        static Counter decode(long png, int vectorBytes, int vectorEsz) {
            int bits = (int) (png & COUNTER_REGISTER_MASK);
            if ((bits & COUNTER_ESZ_FIELD_MASK) == 0) {
                return new Counter(0, 0, false);
            }
            int counterEsz = Integer.numberOfTrailingZeros(bits);
            // maxbit_mask = ones<log2(pl em bits × 4):0> = (pow2ceil(VL) << 3) - 1
            int countMask = (powerOfTwoCeiling(vectorBytes) << COUNTER_MASK_SHIFT) - 1;
            int count = (bits & countMask) >>> (counterEsz + 1);
            boolean invert = ((bits >>> COUNTER_INVERT_BIT) & 1) != 0;
            int lg2Stride = 0;
            if (counterEsz < vectorEsz) {
                // A máscara expandida tem mais bits ligados do que o elemento consome: arredonda a contagem para cima.
                int shift = vectorEsz - counterEsz;
                int truncated = count >>> shift;
                count = truncated + (count != (truncated << shift) ? 1 : 0);
            } else if (counterEsz > vectorEsz) {
                // Só ficam ligados os múltiplos de potência de dois: sobe a contagem e informa o passo.
                lg2Stride = counterEsz - vectorEsz;
                count <<= lg2Stride;
            }
            return new Counter(count, lg2Stride, invert);
        }

        /// O elemento `element` (em unidades do tamanho de elemento da instrução) está ativo?
        boolean active(int element) {
            if ((element & ((1 << lg2Stride) - 1)) != 0) {
                return false;
            }
            return invert ? element >= count : element < count;
        }
    }

    /// `EncodePredCount` do manual: escreve `count` (de `elements`) num contador de tamanho `esz`. Contagem zero é o
    /// registrador todo zerado; contagem cheia vira `invert` com contagem zero (a forma canônica).
    static long encode(int elements, int count, int esz, boolean invert) {
        if (count == 0) {
            return 0L;
        }
        boolean inverted = invert;
        int stored = count;
        if (invert) {
            stored = elements - count;
        } else if (count == elements) {
            stored = 0;
            inverted = true;
        }
        long pred = (((long) stored << 1) | 1L) << esz;
        return inverted ? pred | (1L << COUNTER_INVERT_BIT) : pred;
    }

    private static int powerOfTwoCeiling(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    /// Os 16 bits baixos de `PN<index>`, o contador cru.
    private static long counterBits(Aarch64Core core, int index) {
        return core.scalable().pWord(index, 0) & COUNTER_REGISTER_MASK;
    }

    // ── PTRUE / CNTP / PEXT ──────────────────────────────────────────────────────────────────────

    /// Executa `PTRUE`/`CNTP`/`PEXT` sobre contador. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, SvePredicateOp64.CounterPredicate op) {
        boolean allowed = op.streamingOnly()
                ? core.smeStreamingEnabledCheck(op.instructionAddress())
                : SvePredicateOps.accessAllowed(core, op.instructionAddress());
        if (!allowed) {
            return true;
        }
        switch (op.op()) {
            case PTRUE -> writeCounter(core, op.pd(), canonicalTrue(op.esz()));
            case CNTP -> core.setX(op.rd(), countElements(core, op));
            case PEXT_1 -> extractPredicate(core, op, 1);
            default -> extractPredicate(core, op, 2); // PEXT_2
        }
        return false;
    }

    /// Escreve um contador: os 64 bits baixos recebem `value` e o resto do registrador é zerado.
    static void writeCounter(Aarch64Core core, int register, long value) {
        Aarch64ScalableRegisters regs = core.scalable();
        long[] words = new long[regs.wordsPerPredicate()];
        words[0] = value;
        SvePredicateOps.write(regs, register, words);
    }

    /// `PTRUE` na forma canônica: contagem 0, `invert = 1` e o bit do tamanho de elemento — "todos os elementos".
    private static long canonicalTrue(int esz) {
        return (1L << COUNTER_INVERT_BIT) | (1L << esz);
    }

    /// `CNTP <Xd>, <PNn>.<T>, <vlx2|vlx4>`: quantos elementos de `T` o contador descreve em 2 ou 4 vetores.
    private static long countElements(Aarch64Core core, SvePredicateOp64.CounterPredicate op) {
        int vectorBytes = core.vectorLengthBytes();
        Counter counter = Counter.decode(counterBits(core, op.pn()), vectorBytes, op.esz());
        int maxElements = (vectorBytes << op.index()) >> op.esz();
        int count = counter.count();
        if (counter.invert()) {
            count = count >= maxElements ? 0 : maxElements - count;
        } else {
            count = Math.min(count, maxElements);
        }
        return count >>> counter.lg2Stride();
    }

    /// `PEXT`: o segmento `imm` (de `VL` bits) do contador vira uma máscara comum; `PEXT_2` escreve dois segmentos.
    private static void extractPredicate(Aarch64Core core, SvePredicateOp64.CounterPredicate op, int registers) {
        Aarch64ScalableRegisters regs = core.scalable();
        int vectorBytes = core.vectorLengthBytes();
        Counter counter = Counter.decode(counterBits(core, op.pn()), vectorBytes, op.esz());
        int maskEsz = op.esz() + counter.lg2Stride();
        for (int i = 0; i < registers; i++) {
            int part = op.index() * registers + i;
            // Contagem em bytes, ajustada ao trecho de `VL` bytes que este registrador cobre.
            long byteCount = ((long) counter.count() << op.esz()) - (long) vectorBytes * part;
            long[] result = new long[regs.wordsPerPredicate()];
            if (counter.invert()) {
                if (byteCount <= 0) {
                    setBitRange(result, 0, vectorBytes, maskEsz);
                } else if (byteCount < vectorBytes) {
                    setBitRange(result, (int) byteCount, vectorBytes, maskEsz);
                }
            } else if (byteCount > 0) {
                setBitRange(result, 0, (int) Math.min(byteCount, vectorBytes), maskEsz);
            }
            SvePredicateOps.write(regs, (op.pd() + i) % PREDICATE_REGISTERS, result);
        }
    }

    /// Liga os bits `[from, to)` que são múltiplos de `1 << esz` (`do_whilel`/`do_whileg` sob `pred_esz_masks`).
    private static void setBitRange(long[] predicate, int from, int to, int esz) {
        int step = 1 << esz;
        int first = (from + step - 1) & -step;
        for (int bit = first; bit < to; bit += step) {
            SvePredicateOps.setBit(predicate, bit, true);
        }
    }

    // ── LD1 / ST1 multi-vetor ────────────────────────────────────────────────────────────────────

    /// Executa `LD1`/`ST1` multi-vetor governado por contador. `true` = a instrução já entrou numa exceção.
    static boolean execute(Aarch64Core core, SveMemoryOp64.MultiVectorMemory op) {
        boolean allowed = op.streamingOnly()
                ? core.smeStreamingEnabledCheck(op.instructionAddress())
                : SvePredicateOps.accessAllowed(core, op.instructionAddress());
        if (!allowed) {
            return true;
        }
        int vectorBytes = core.vectorLengthBytes();
        int elementsPerVector = vectorBytes >> op.esz();
        Counter counter = Counter.decode(counterBits(core, op.pg()), vectorBytes, op.esz());
        long start = address(core, op, vectorBytes);
        AddressSpace64 memory = core.memory();
        if (op.store()) {
            Aarch64ScalableRegisters regs = core.scalable();
            for (int e = 0; e < op.registers() * elementsPerVector; e++) {
                if (counter.active(e)) {
                    int register = register(op, e / elementsPerVector);
                    writeMemory(memory, start + ((long) e << op.esz()), op.esz(),
                            SveStoreOps.element(regs, register, e % elementsPerVector, op.esz()));
                }
            }
            return false;
        }
        long[][] result = new long[op.registers()][vectorBytes / WORD_BYTES];
        for (int e = 0; e < op.registers() * elementsPerVector; e++) {
            if (counter.active(e)) {
                long value = SveLoadOps.readMemory(memory, start + ((long) e << op.esz()), op.esz());
                SveLoadOps.putElement(result[e / elementsPerVector], e % elementsPerVector, op.esz(), value);
            }
        }
        for (int k = 0; k < op.registers(); k++) {
            SveLoadOps.commitVector(core, register(op, k), result[k]);
        }
        return false;
    }

    private static int register(SveMemoryOp64.MultiVectorMemory op, int index) {
        return (op.rt() + index * op.registerStride()) & VECTOR_REGISTER_MASK;
    }

    private static long address(Aarch64Core core, SveMemoryOp64.MultiVectorMemory op, int vectorBytes) {
        long base = op.rn() == STACK_POINTER_ENCODING ? core.sp() : core.x(op.rn());
        if (op.registerOffset()) {
            return base + (core.x(op.rm()) << op.esz());
        }
        return base + op.immediate() * op.registers() * vectorBytes;
    }

    private static void writeMemory(AddressSpace64 memory, long address, int esz, long value) {
        switch (esz) {
            case 0 -> memory.write8(address, (int) value);
            case 1 -> memory.write16(address, (int) value);
            case 2 -> memory.write32(address, (int) value);
            default -> memory.write64(address, value);
        }
    }
}
