package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

/// Semântica dos gather loads SVE (B17.19): `LD1_zprz` (escalar + vetor de deslocamentos), `LD1_zpiz` (vetor de endereços
/// + imediato) e `LD1Q` (vetor + escalar), cada um em `LD1*` e first-fault `LDFF1*`. É o espelho do scatter da
/// `SveStoreOps` — a aritmética de endereço por elemento é a mesma; aqui só o sentido do acesso muda — e reusa a
/// infraestrutura de `FFR` da `SveLoadOps` (`recordFault`, o mesmo "tentar o elemento e capturar a falta").
///
/// **Elemento inativo não acessa a memória** e vira zero no destino. Os elementos são percorridos em ordem crescente e o
/// resultado só é gravado depois do último acesso: num `LD1*` um aborto (sempre o do elemento de MENOR índice que falha)
/// deixa `Z` intacto. Como cada elemento tem endereço próprio, um gather pode tocar tantas páginas quanto elementos.
///
/// **`LDFF1*`** (`firstFault`): o primeiro elemento ativo é um load normal (a falta é um aborto real); a partir do
/// segundo, uma falha de tradução é capturada, o `FFR` é zerado **do elemento que falhou em diante** (só limpa bits, nunca
/// os liga) e o vetor sai zerado dali em diante — a mesma regra do `sve_ldff1_z` do QEMU. Predicado sem elemento ativo:
/// vetor zerado e `FFR` intacto. Como no load contíguo, cada elemento é tentado de verdade (o `AddressSpace64` não informa
/// o tipo de memória), então um `LDFF1` sobre MMIO PODE atingir o dispositivo.
///
/// Todo gather (e `LD1Q`) é ilegal em modo streaming.
public final class SveGatherOps {
    private static final int WORDS_PER_QUADWORD = 2;
    private static final int QUADWORD_BYTES = 16;
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int ESZ_QUAD = 4;
    private static final long WORD_OFFSET_MASK = 0xFFFFFFFFL;
    private static final long WORD_BYTES = Long.BYTES;

    private SveGatherOps() {
    }

    /// Executa uma instrução do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    public static boolean execute(Aarch64Core core, SveMemoryOp64.Gather op) {
        SvePredicateOps.requireNonStreaming(core);
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        if (op.op() == SveMemoryOp64.Gather.Op.LD1Q) {
            gatherQuadword(core, op);
        } else {
            gatherElements(core, op);
        }
        return false;
    }

    // ── LD1_zprz / LD1_zpiz ──────────────────────────────────────────────────────────────────────

    private static void gatherElements(Aarch64Core core, SveMemoryOp64.Gather op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int elements = core.vectorLengthBytes() >> op.esz();
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long[] result = new long[SveLoadOps.vectorWords(core)];
        boolean first = true;
        int faulted = -1;
        for (int e = 0; e < elements; e++) {
            if (!SveLoadOps.active(predicate, e, op.esz())) {
                continue;
            }
            try {
                loadElement(core, memory, op, regs, result, e);
            } catch (MemoryTranslationException64 fault) {
                if (first || !op.firstFault()) {
                    throw fault; // load normal (ou o primeiro elemento ativo do first-fault): aborto real
                }
                faulted = e;
                break;
            }
            first = false;
        }
        if (faulted >= 0) {
            SveLoadOps.recordFault(regs, faulted << op.esz());
        }
        SveLoadOps.commitVector(core, op.rd(), result);
    }

    /// Lê UM elemento (`msz`/extensão da instrução) do endereço que a forma de endereçamento dá para `element`.
    private static void loadElement(Aarch64Core core, AddressSpace64 memory, SveMemoryOp64.Gather op,
            Aarch64ScalableRegisters regs, long[] result, int element) {
        long value = SveLoadOps.readMemory(memory, address(core, op, regs, element), op.msz());
        SveLoadOps.putElement(result, element, op.esz(), op.signExtend() ? SveLoadOps.signExtend(value, op.msz()) : value);
    }

    /// `SCALAR_PLUS_VECTOR`: `Xn|SP + (Zm[e] estendido << (scaled ? msz : 0))`. Com `esz = 3` e `xs` = `UXTW`/`SXTW` só os
    /// 32 bits baixos de cada elemento de 64 bits são o deslocamento (`SveStoreOps.element` já devolve os 32 bits baixos
    /// quando `esz = 2`). `VECTOR_PLUS_IMMEDIATE`: `Zn[e]` (zero-estendido em 32 bits quando `esz = 2`) `+ (imm5 << msz)`. `VECTOR_PLUS_SCALAR` (`LDNT1_zprz`): `Zn[e]` (idem) `+ Xm` (`XZR` se `31`).
    private static long address(Aarch64Core core, SveMemoryOp64.Gather op, Aarch64ScalableRegisters regs, int element) {
        if (op.op() == SveMemoryOp64.Gather.Op.VECTOR_PLUS_IMMEDIATE) {
            return SveStoreOps.element(regs, op.rn(), element, op.esz()) + (op.immediate() << op.msz());
        }
        if (op.op() == SveMemoryOp64.Gather.Op.VECTOR_PLUS_SCALAR) {
            return SveStoreOps.element(regs, op.rn(), element, op.esz()) + core.x(op.rm());
        }
        long offset = SveStoreOps.element(regs, op.rm(), element, op.esz());
        offset = switch (op.offsetExtend()) {
            case SveMemoryOp64.Gather.OFFSET_UXTW -> offset & WORD_OFFSET_MASK;
            case SveMemoryOp64.Gather.OFFSET_SXTW -> (long) (int) offset;
            default -> offset;
        };
        return base(core, op.rn()) + (offset << (op.scaled() ? op.msz() : 0));
    }

    private static long base(Aarch64Core core, int register) {
        return register == STACK_POINTER_ENCODING ? core.sp() : core.x(register);
    }

    // ── LD1Q ─────────────────────────────────────────────────────────────────────────────────────

    /// `LD1Q`: um quadword por segmento de 128 bits; o predicado usa o bit de índice `16 × segmento`, o endereço é o
    /// `D[0]` do segmento de `Zn` mais `Xm` (`XZR` se `31`), e o segmento inativo vira zero.
    private static void gatherQuadword(Aarch64Core core, SveMemoryOp64.Gather op) {
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int segments = core.vectorLengthBytes() / QUADWORD_BYTES;
        long[] predicate = SvePredicateOps.read(regs, op.pg());
        long offset = core.x(op.rm());
        long[] result = new long[SveLoadOps.vectorWords(core)];
        for (int s = 0; s < segments; s++) {
            if (!SveLoadOps.active(predicate, s, ESZ_QUAD)) {
                continue;
            }
            long address = regs.zWord(op.rn(), s * WORDS_PER_QUADWORD) + offset;
            result[s * WORDS_PER_QUADWORD] = memory.read64(address);
            result[s * WORDS_PER_QUADWORD + 1] = memory.read64(address + WORD_BYTES);
        }
        SveLoadOps.commitVector(core, op.rd(), result);
    }
}
