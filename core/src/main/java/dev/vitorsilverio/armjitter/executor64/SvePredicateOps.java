package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica dos predicados SVE (`FEAT_SVE`, B17.4) — o substrato que as tasks seguintes do épico B17
/// reusam: leitura/escrita de predicado como `long[]`, `PredTest` (as flags `NZCV` de toda instrução
/// SVE que produz predicado), `DecodePredCount` (o `pat:5`) e as primitivas de elemento de `Z`.
///
/// **Modelo de predicado.** `P<n>` guarda **um bit por byte do vetor** (`PL = VL/8` bits); para
/// elementos maiores que um byte só o bit do byte MAIS BAIXO de cada elemento é significativo
/// (`esz` = 0..3 ⇒ o bit do elemento `e` é o de índice `e << esz`). As operações que GERAM predicado
/// escrevem só esses bits; as que CONSOMEM ignoram os demais. O `VL` é sempre o efetivo do core
/// ({@link Aarch64Core#vectorLengthBytes()}), nunca uma constante (G6): o mesmo executor serve
/// `VL = 256`, `512`… e, em modo streaming, o `SVL`.
///
/// **Ordem de checagem.** Toda instrução faz primeiro a checagem de acesso (SVE, ou SME em modo
/// streaming) — a que trapa devolve `true` ("já entrou na exceção") — e só então lê estado. As
/// instruções do `FFR` são ilegais em modo streaming sem `FEAT_SME_FA64`.
///
/// Todas as fórmulas foram transcritas do `sve_helper.c`/`translate-sve.c` do QEMU (a mesma revisão
/// fixada em `gerar-cobertura-isa.sh`), inclusive as escolhas de flags de `PTRUES`, `BRKN` e `PNEXT`.
final class SvePredicateOps {
    private static final int BITS_PER_WORD = Long.SIZE;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = BITS_PER_WORD - 1;
    private static final int ESZ_BYTE = 0;
    private static final int PATTERN_POW2 = 0;
    private static final int PATTERN_VL8_MAX_DIRECT = 8;
    private static final int PATTERN_VL16 = 9;
    private static final int PATTERN_VL256 = 13;
    private static final int PATTERN_FIRST_VL_POWER_OF_TWO = 16;
    private static final int PATTERN_MUL4 = 29;
    private static final int PATTERN_MUL3 = 30;
    private static final int PATTERN_ALL = 31;
    private static final int MUL4 = 4;
    private static final int MUL3 = 3;
    private static final long NOT_FOUND_INDEX = -1L;

    private SvePredicateOps() {
    }

    // ── Acesso e estado ─────────────────────────────────────────────────────────────────────────

    /// Checagem de acesso da instrução: SME (`CheckSMEEnabled`) em modo streaming, SVE
    /// (`CPACR_EL1.ZEN`/`CPTR_ELx`) fora dele. `false` = a exceção JÁ foi tomada.
    static boolean accessAllowed(Aarch64Core core, long instructionAddress) {
        return core.streamingModeEnabled()
                ? core.smeEnabledCheck(instructionAddress)
                : core.sveAccessCheck(instructionAddress);
    }

    /// `FFR` não existe em modo streaming: as 4 instruções que o tocam são `UNDEFINED` ali, a menos
    /// que `FEAT_SME_FA64` esteja efetivo (`TRANS_FEAT_NONSTREAMING` do QEMU).
    static void requireNonStreaming(Aarch64Core core) {
        if (core.streamingRestrictionApplies()) {
            throw new Aarch64UndefinedInstructionException();
        }
    }

    /// Predicado em bits (`PL = VL/8`), no `VL` efetivo do core.
    private static int predicateBits(Aarch64Core core) {
        return core.vectorLengthBytes();
    }

    private static long[] read(Aarch64ScalableRegisters regs, int reg) {
        long[] out = new long[regs.wordsPerPredicate()];
        for (int w = 0; w < out.length; w++) {
            out[w] = regs.pWord(reg, w);
        }
        return out;
    }

    private static void write(Aarch64ScalableRegisters regs, int reg, long[] value) {
        for (int w = 0; w < value.length; w++) {
            regs.setPWord(reg, w, value[w]);
        }
    }

    private static boolean bit(long[] predicate, int index) {
        return ((predicate[index >>> WORD_INDEX_SHIFT] >>> (index & WORD_BIT_MASK)) & 1L) != 0L;
    }

    private static void setBit(long[] predicate, int index, boolean value) {
        long mask = 1L << (index & WORD_BIT_MASK);
        int word = index >>> WORD_INDEX_SHIFT;
        predicate[word] = value ? predicate[word] | mask : predicate[word] & ~mask;
    }

    /// Zera os bits acima de `bits` (o que sobra depois de um `NOT`).
    private static void clearAbove(long[] predicate, int bits) {
        for (int w = 0; w < predicate.length; w++) {
            int firstBit = w * BITS_PER_WORD;
            if (firstBit >= bits) {
                predicate[w] = 0L;
            } else if (firstBit + BITS_PER_WORD > bits) {
                predicate[w] &= (1L << (bits - firstBit)) - 1L;
            }
        }
    }

    /// `PredTest(mask, result, esz)` → `NZCV` (transcrição de `iter_predtest_fwd`): `N` = o primeiro
    /// elemento ATIVO do resultado é verdadeiro; `Z` = nenhum elemento ativo é verdadeiro; `C` = o
    /// último elemento ativo NÃO é verdadeiro (sem elemento ativo: `N=0`, `Z=1`, `C=1`); `V = 0`.
    static void predTest(Aarch64Core core, long[] mask, long[] result, int esz, int predicateBits) {
        int elements = predicateBits >> esz;
        boolean anyActive = false;
        boolean first = false;
        boolean anyTrue = false;
        boolean last = false;
        for (int e = 0; e < elements; e++) {
            int index = e << esz;
            if (!bit(mask, index)) {
                continue;
            }
            boolean value = bit(result, index);
            if (!anyActive) {
                first = value;
                anyActive = true;
            }
            anyTrue |= value;
            last = value;
        }
        core.pstate().setNzcv(first, !anyTrue, !last, false);
    }

    /// `PredTest(Ones(PL), result, esz)`.
    private static void predTestOnes(Aarch64Core core, long[] result, int esz, int predicateBits) {
        long[] ones = new long[result.length];
        for (int i = 0; i < predicateBits; i++) {
            setBit(ones, i, true);
        }
        predTest(core, ones, result, esz, predicateBits);
    }

    /// `DecodePredCount(pat, elements)`: quantos elementos o padrão `pat:5` seleciona. Um padrão
    /// `VLn` maior que o número de elementos, ou um `pat` sem significado (`14`-`28`), dá `0` —
    /// nunca exceção (é como o software descobre o `VL`).
    static int decodePredCount(int pattern, int elements) {
        if (pattern == PATTERN_POW2) {
            return Integer.highestOneBit(elements);
        }
        if (pattern <= PATTERN_VL8_MAX_DIRECT) {
            return elements >= pattern ? pattern : 0;
        }
        if (pattern <= PATTERN_VL256) {
            int bound = PATTERN_FIRST_VL_POWER_OF_TWO << (pattern - PATTERN_VL16);
            return elements >= bound ? bound : 0;
        }
        return switch (pattern) {
            case PATTERN_MUL4 -> elements - elements % MUL4;
            case PATTERN_MUL3 -> elements - elements % MUL3;
            case PATTERN_ALL -> elements;
            default -> 0;
        };
    }

    // ── Lógica de predicado ─────────────────────────────────────────────────────────────────────

    static boolean executeLogical(Aarch64Core core, Ir64Op.SvePredicateLogical op) {
        if (!accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int bits = predicateBits(core);
        long[] pg = read(regs, op.pg());
        long[] pn = read(regs, op.pn());
        long[] pm = read(regs, op.pm());
        long[] result = new long[pg.length];
        for (int w = 0; w < result.length; w++) {
            long g = pg[w];
            long n = pn[w];
            long m = pm[w];
            result[w] = switch (op.op()) {
                case AND -> n & m & g;
                case BIC -> n & ~m & g;
                case EOR -> (n ^ m) & g;
                case SEL -> (n & g) | (m & ~g);
                case ORR -> (n | m) & g;
                case ORN -> (n | ~m) & g;
                case NOR -> ~(n | m) & g;
                case NAND -> ~(n & m) & g;
            };
        }
        clearAbove(result, bits);
        write(regs, op.pd(), result);
        if (op.setFlags()) {
            predTest(core, pg, result, ESZ_BYTE, bits);
        }
        return false;
    }

    // ── Misc: PTEST / PTRUE / PFALSE / FFR / PFIRST / PNEXT ──────────────────────────────────────

    static boolean executeMisc(Aarch64Core core, Ir64Op.SvePredicateMisc op) {
        boolean usesFfr = switch (op.op()) {
            case SETFFR, RDFFR, RDFFR_PREDICATED, WRFFR -> true;
            default -> false;
        };
        if (usesFfr) {
            requireNonStreaming(core);
        }
        if (!accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int bits = predicateBits(core);
        switch (op.op()) {
            case PTEST -> predTest(core, read(regs, op.pg()), read(regs, op.pn()), ESZ_BYTE, bits);
            case PTRUE -> predicateTrue(core, op, bits);
            case PFALSE -> write(regs, op.pd(), new long[regs.wordsPerPredicate()]);
            case SETFFR -> {
                long[] all = new long[regs.wordsPerPredicate()];
                for (int i = 0; i < bits; i++) {
                    setBit(all, i, true);
                }
                write(regs, Aarch64ScalableRegisters.FFR_INDEX, all);
            }
            case RDFFR -> write(regs, op.pd(), read(regs, Aarch64ScalableRegisters.FFR_INDEX));
            case RDFFR_PREDICATED -> {
                long[] pg = read(regs, op.pg());
                long[] result = read(regs, Aarch64ScalableRegisters.FFR_INDEX);
                for (int w = 0; w < result.length; w++) {
                    result[w] &= pg[w];
                }
                write(regs, op.pd(), result);
                if (op.setFlags()) {
                    predTest(core, pg, result, ESZ_BYTE, bits);
                }
            }
            case WRFFR -> write(regs, Aarch64ScalableRegisters.FFR_INDEX, read(regs, op.pn()));
            case PFIRST -> predicateFirst(core, op, bits);
            default -> predicateNext(core, op, bits); // PNEXT
        }
        return false;
    }

    /// `PTRUE`/`PTRUES`. As flags de `PTRUES` seguem o `do_predset` do QEMU: `N = Z-invertido =
    /// (count != 0)`, `C = (count == 0)`, `V = 0` — a máscara do teste é o próprio resultado.
    private static void predicateTrue(Aarch64Core core, Ir64Op.SvePredicateMisc op, int bits) {
        Aarch64ScalableRegisters regs = core.scalable();
        int elements = bits >> op.esz();
        int count = decodePredCount(op.pattern(), elements);
        long[] result = new long[regs.wordsPerPredicate()];
        for (int e = 0; e < count; e++) {
            setBit(result, e << op.esz(), true);
        }
        write(regs, op.pd(), result);
        if (op.setFlags()) {
            boolean any = count != 0;
            core.pstate().setNzcv(any, !any, !any, false);
        }
    }

    /// `PFIRST Pdn, Pg, Pdn`: liga em `Pdn` o bit do primeiro elemento ativo de `Pg`; o resto de
    /// `Pdn` fica como está. Sempre seta flags (`PredTest(Pg, Pdn)`).
    private static void predicateFirst(Aarch64Core core, Ir64Op.SvePredicateMisc op, int bits) {
        Aarch64ScalableRegisters regs = core.scalable();
        long[] pg = read(regs, op.pg());
        long[] pd = read(regs, op.pd());
        for (int i = 0; i < bits; i++) {
            if (bit(pg, i)) {
                setBit(pd, i, true);
                break;
            }
        }
        write(regs, op.pd(), pd);
        predTest(core, pg, pd, ESZ_BYTE, bits);
    }

    /// `PNEXT Pdn, Pg, Pdn`: `Pdn` passa a conter SÓ o próximo elemento ativo de `Pg` depois do
    /// último elemento verdadeiro de `Pdn` (sem nenhum, a busca começa no elemento 0); nada achado ⇒
    /// `Pdn` zerado. O "último elemento de `Pdn`" NÃO é mascarado por `Pg`. Sempre seta flags.
    private static void predicateNext(Aarch64Core core, Ir64Op.SvePredicateMisc op, int bits) {
        Aarch64ScalableRegisters regs = core.scalable();
        int esz = op.esz();
        long[] pg = read(regs, op.pg());
        long[] pd = read(regs, op.pd());
        int next = 0;
        for (int i = bits - 1; i >= 0; i--) {
            if ((i & ((1 << esz) - 1)) == 0 && bit(pd, i)) {
                next = i + (1 << esz);
                break;
            }
        }
        long[] result = new long[pd.length];
        for (int i = next; i < bits; i += 1 << esz) {
            if (bit(pg, i)) {
                setBit(result, i, true);
                break;
            }
        }
        write(regs, op.pd(), result);
        predTest(core, pg, result, esz, bits);
    }

    // ── Partition break ─────────────────────────────────────────────────────────────────────────

    static boolean executePartitionBreak(Aarch64Core core, Ir64Op.SvePartitionBreak op) {
        if (!accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int bits = predicateBits(core);
        long[] pg = read(regs, op.pg());
        long[] pn = read(regs, op.pn());
        long[] result;
        switch (op.op()) {
            case BRKA -> result = brk(pg, pn, read(regs, op.pd()), bits, true, op.merging());
            case BRKB -> result = brk(pg, pn, read(regs, op.pd()), bits, false, op.merging());
            case BRKPA, BRKPB -> {
                boolean after = op.op() == Ir64Op.SvePartitionBreak.Op.BRKPA;
                result = lastActiveIsTrue(pg, pn, bits)
                        ? brk(pg, read(regs, op.pm()), null, bits, after, false)
                        : new long[pg.length];
            }
            default -> { // BRKN
                // `Pdm` só sobrevive se o último elemento ativo de Pn for verdadeiro.
                result = lastActiveIsTrue(pg, pn, bits) ? read(regs, op.pd()) : new long[pg.length];
                write(regs, op.pd(), result);
                if (op.setFlags()) {
                    // As if PredTest(Ones(PL), Pdm, byte) — não usa Pg como máscara.
                    predTestOnes(core, result, ESZ_BYTE, bits);
                }
                return false;
            }
        }
        write(regs, op.pd(), result);
        if (op.setFlags()) {
            predTest(core, pg, result, ESZ_BYTE, bits);
        }
        return false;
    }

    /// `LastActive(pg, pn)`: o último elemento ativo de `pg` é verdadeiro em `pn`.
    private static boolean lastActiveIsTrue(long[] pg, long[] pn, int bits) {
        for (int i = bits - 1; i >= 0; i--) {
            if (bit(pg, i)) {
                return bit(pn, i);
            }
        }
        return false;
    }

    /// `BreakAfter`/`BreakBefore` (`compute_brk_z`/`compute_brk_m` do QEMU): entre os elementos
    /// ativos de `pg`, liga os anteriores ao primeiro `pg & source` (e ele mesmo, se `after`); os
    /// demais ativos ficam `0`. Elementos inativos: `0` (zeroing) ou o valor antigo de `destination`
    /// (`merging`).
    private static long[] brk(long[] pg, long[] source, long[] destination, int bits, boolean after,
            boolean merging) {
        long[] result = new long[pg.length];
        boolean broken = false;
        for (int i = 0; i < bits; i++) {
            if (!bit(pg, i)) {
                if (merging) {
                    setBit(result, i, bit(destination, i));
                }
                continue;
            }
            if (broken) {
                continue;
            }
            boolean hit = bit(source, i);
            if (hit) {
                broken = true;
            }
            setBit(result, i, after || !hit);
        }
        return result;
    }

    // ── Contagem por predicado ──────────────────────────────────────────────────────────────────

    static boolean executePredicateCount(Aarch64Core core, Ir64Op.SvePredicateCount op) {
        if (!accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        Aarch64ScalableRegisters regs = core.scalable();
        int bits = predicateBits(core);
        long[] pg = read(regs, op.pg());
        long[] pn = read(regs, op.pn());
        switch (op.op()) {
            case CNTP, INCDECP_SCALAR, INCDECP_VECTOR, SINCDECP_SCALAR_32, SINCDECP_SCALAR_64,
                    SINCDECP_VECTOR -> {
                long count = countActive(pg, pn, op.esz(), bits);
                applyCount(core, op, count);
            }
            case FIRSTP -> core.setX(op.rd(), firstActiveElement(pg, pn, op.esz(), bits));
            default -> core.setX(op.rd(), lastActiveElement(pg, pn, op.esz(), bits)); // LASTP
        }
        return false;
    }

    private static long countActive(long[] pg, long[] pn, int esz, int bits) {
        long count = 0;
        for (int e = 0; e < (bits >> esz); e++) {
            int index = e << esz;
            if (bit(pg, index) && bit(pn, index)) {
                count++;
            }
        }
        return count;
    }

    private static long firstActiveElement(long[] pg, long[] pn, int esz, int bits) {
        for (int e = 0; e < (bits >> esz); e++) {
            int index = e << esz;
            if (bit(pg, index) && bit(pn, index)) {
                return e;
            }
        }
        return NOT_FOUND_INDEX;
    }

    private static long lastActiveElement(long[] pg, long[] pn, int esz, int bits) {
        for (int e = (bits >> esz) - 1; e >= 0; e--) {
            int index = e << esz;
            if (bit(pg, index) && bit(pn, index)) {
                return e;
            }
        }
        return NOT_FOUND_INDEX;
    }

    private static void applyCount(Aarch64Core core, Ir64Op.SvePredicateCount op, long count) {
        switch (op.op()) {
            case CNTP -> core.setX(op.rd(), count);
            case INCDECP_SCALAR ->
                    core.setX(op.rd(), op.decrement() ? core.x(op.rd()) - count : core.x(op.rd()) + count);
            case INCDECP_VECTOR -> updateVector(core, op.rd(), op.esz(), count, op.decrement(), false, false);
            case SINCDECP_SCALAR_32 ->
                    core.setX(op.rd(), saturate32(core.x(op.rd()), count, op.decrement(), op.unsigned()));
            case SINCDECP_SCALAR_64 ->
                    core.setX(op.rd(), saturate64(core.x(op.rd()), count, op.decrement(), op.unsigned()));
            default -> // SINCDECP_VECTOR
                    updateVector(core, op.rd(), op.esz(), count, op.decrement(), true, op.unsigned());
        }
    }

    // ── Contagem de elementos ───────────────────────────────────────────────────────────────────

    static boolean executeElementCount(Aarch64Core core, Ir64Op.SveElementCount op) {
        if (!accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        int elements = core.vectorLengthBytes() >> op.esz();
        long amount = (long) decodePredCount(op.pattern(), elements) * op.multiplier();
        switch (op.op()) {
            case CNT -> core.setX(op.rd(), amount);
            case INCDEC_SCALAR ->
                    core.setX(op.rd(), op.decrement() ? core.x(op.rd()) - amount : core.x(op.rd()) + amount);
            case SINCDEC_SCALAR_32 ->
                    core.setX(op.rd(), saturate32(core.x(op.rd()), amount, op.decrement(), op.unsigned()));
            case SINCDEC_SCALAR_64 ->
                    core.setX(op.rd(), saturate64(core.x(op.rd()), amount, op.decrement(), op.unsigned()));
            case INCDEC_VECTOR -> {
                if (amount != 0) {
                    updateVector(core, op.rd(), op.esz(), amount, op.decrement(), false, false);
                }
            }
            default -> { // SINCDEC_VECTOR
                if (amount != 0) {
                    updateVector(core, op.rd(), op.esz(), amount, op.decrement(), true, op.unsigned());
                }
            }
        }
        return false;
    }

    // ── Aritmética saturante escalar/vetorial (do_sat_addsub_*) ──────────────────────────────────

    /// `SQINC*`/`UQINC*` (e `DEC`) de 32 bits: o operando é `Wn` sign-/zero-estendido; o resultado
    /// (saturado em 32 bits) volta estendido a 64.
    static long saturate32(long register, long amount, boolean decrement, boolean unsigned) {
        long value = unsigned ? register & 0xFFFF_FFFFL : (long) (int) register;
        if (amount == 0) {
            return value;
        }
        long result = decrement ? value - amount : value + amount;
        long min = unsigned ? 0L : Integer.MIN_VALUE;
        long max = unsigned ? 0xFFFF_FFFFL : Integer.MAX_VALUE;
        return Math.max(min, Math.min(max, result));
    }

    /// `SQINC*`/`UQINC*` (e `DEC`) de 64 bits.
    static long saturate64(long register, long amount, boolean decrement, boolean unsigned) {
        if (amount == 0) {
            return register;
        }
        if (unsigned) {
            if (decrement) {
                return Long.compareUnsigned(register, amount) < 0 ? 0L : register - amount;
            }
            long sum = register + amount;
            return Long.compareUnsigned(sum, register) < 0 ? -1L : sum;
        }
        // `amount` é uma contagem (>= 0): subtrair só estoura para baixo, somar só para cima.
        if (decrement) {
            long difference = register - amount;
            return ((register ^ amount) & (register ^ difference)) < 0 ? Long.MIN_VALUE : difference;
        }
        long sum = register + amount;
        return ((register ^ sum) & (amount ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /// Soma/subtrai `amount` (não negativo) a CADA elemento de `Z<reg>` no `VL` efetivo; `saturating`
    /// escolhe entre aritmética modular (`INCP`/`INCH`) e saturante (`SQINC*`/`UQINC*`).
    private static void updateVector(Aarch64Core core, int reg, int esz, long amount, boolean decrement,
            boolean saturating, boolean unsigned) {
        Aarch64ScalableRegisters regs = core.scalable();
        int elementBits = Byte.SIZE << esz;
        int elements = core.vectorLengthBits() / elementBits;
        long mask = elementBits == Long.SIZE ? -1L : (1L << elementBits) - 1L;
        for (int e = 0; e < elements; e++) {
            long value = elementOf(regs, reg, e, esz);
            long updated = !saturating
                    ? (decrement ? value - amount : value + amount)
                    : saturateElement(value, amount, decrement, unsigned, elementBits);
            setElementOf(regs, reg, e, esz, updated & mask);
        }
    }

    /// Soma/subtrai `amount` a um elemento de `elementBits` bits saturando no limite do tipo do elemento
    /// (com ou sem sinal); `value` chega zero-estendido.
    private static long saturateElement(long value, long amount, boolean decrement, boolean unsigned,
            int elementBits) {
        if (elementBits == Long.SIZE) {
            return saturate64(value, amount, decrement, unsigned);
        }
        long operand = unsigned ? value : signExtend(value, elementBits);
        long wide = decrement ? operand - amount : operand + amount;
        long max = unsigned ? (1L << elementBits) - 1L : (1L << (elementBits - 1)) - 1L;
        long min = unsigned ? 0L : -(1L << (elementBits - 1));
        return Math.max(min, Math.min(max, wide));
    }

    private static long signExtend(long value, int bits) {
        int shift = Long.SIZE - bits;
        return (value << shift) >> shift;
    }

    /// Elemento `index` (tamanho `1 << esz` bytes) de `Z<reg>`, zero-estendido.
    static long elementOf(Aarch64ScalableRegisters regs, int reg, int index, int esz) {
        int elementBits = Byte.SIZE << esz;
        int bitOffset = index * elementBits;
        long word = regs.zWord(reg, bitOffset >>> WORD_INDEX_SHIFT);
        long shifted = word >>> (bitOffset & WORD_BIT_MASK);
        return elementBits == Long.SIZE ? shifted : shifted & ((1L << elementBits) - 1L);
    }

    /// Grava o elemento `index` (tamanho `1 << esz` bytes) de `Z<reg>`, preservando os vizinhos.
    static void setElementOf(Aarch64ScalableRegisters regs, int reg, int index, int esz, long value) {
        int elementBits = Byte.SIZE << esz;
        int bitOffset = index * elementBits;
        int wordIndex = bitOffset >>> WORD_INDEX_SHIFT;
        if (elementBits == Long.SIZE) {
            regs.setZWord(reg, wordIndex, value);
            return;
        }
        int shift = bitOffset & WORD_BIT_MASK;
        long fieldMask = ((1L << elementBits) - 1L) << shift;
        long word = regs.zWord(reg, wordIndex);
        regs.setZWord(reg, wordIndex, (word & ~fieldMask) | ((value << shift) & fieldMask));
    }
}
