package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64MatrixTileAddressing;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

/// Semântica de `LD1`/`ST1` de slice de tile, `LDR`/`STR` de vetor de `ZA` e `LDR`/`STR` de `ZT0` (SME, B18.4).
/// O endereçamento de tile/slice é {@link Aarch64MatrixTileAddressing} (B18.3); o acesso a elemento de `ZA` reusa
/// os utilitários de {@link SmeMovaOps}; o de memória, os de {@link SveLoadOps}/{@link SveStoreOps} (B17.17/18).
///
/// - **Load lê TUDO para um buffer e só depois grava `ZA`/`ZT0`**: um aborto no meio deixa o estado intacto (mesmo
///   modelo de {@link SveLoadOps}).
/// - **Predicado falso**: no load o elemento vira **zero** na slice (`sme_ld1` do QEMU zera a linha inteira antes, ou
///   `clr_fn` do elemento no caso vertical — a spec original da task dizia "não escreve", o que o QEMU refuta); no
///   store o elemento **não toca a memória**.
/// - `LD1`/`ST1` exigem modo streaming E `ZA` (`CheckStreamingSVEAndZAEnabled`); `LDR`/`STR` de `ZA` só `ZA`
///   habilitado (podem rodar fora do modo streaming — é o que um salvamento de contexto faz); `LDR`/`STR ZT0` exigem
///   `ZT0` habilitado.
/// - **Sem checagem de tag MTE** (mesma disciplina do resto do projeto, ver `memoryTags`).
public final class SmeMemoryOps {
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int WORD_BYTES = Long.BYTES;
    private static final int ESZ_QUAD = 4;

    private SmeMemoryOps() {
    }

    private static long base(Aarch64Core core, int rn) {
        return rn == STACK_POINTER_ENCODING ? core.sp() : core.x(rn);
    }

    // ── LD1 / ST1 ────────────────────────────────────────────────────────────────────────────────

    /// @return `true` = a instrução já entrou numa exceção (acesso negado)
    public static boolean execute(Aarch64Core core, SmeOp64.TileLoadStore op) {
        if (!core.smeStreamingAndZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        Aarch64ScalableRegisters regs = core.scalable();
        AddressSpace64 memory = core.memory();
        int esz = op.esz();
        int svlBytes = core.streamingVectorLengthBytes();
        int elements = svlBytes >>> esz;
        int slice = Aarch64MatrixTileAddressing.resolveSliceIndex(core.x(op.registerIndex()), op.offset(), esz,
                svlBytes, 1);
        long start = base(core, op.rn()) + (core.x(op.rm()) << esz);
        if (op.store()) {
            for (int e = 0; e < elements; e++) {
                if (SmeMovaOps.predicateActive(regs, op.pg(), e, esz)) {
                    storeElement(memory, matrix, op, start + ((long) e << esz), e, slice);
                }
            }
            return false;
        }
        long[] low = new long[elements];
        long[] high = new long[elements];
        for (int e = 0; e < elements; e++) {
            if (SmeMovaOps.predicateActive(regs, op.pg(), e, esz)) {
                long address = start + ((long) e << esz);
                if (esz == ESZ_QUAD) {
                    low[e] = memory.read64(address);
                    high[e] = memory.read64(address + WORD_BYTES);
                } else {
                    low[e] = SveLoadOps.readMemory(memory, address, esz);
                }
            }
        }
        for (int e = 0; e < elements; e++) {
            writeTileElement(matrix, op, e, slice, low[e], high[e]);
        }
        return false;
    }

    /// Linha de `ZA` e deslocamento (em bytes, dentro da linha) do elemento `e` da slice `slice`: no horizontal a
    /// slice É a linha e `e` varre as colunas; no vertical `e` varre as linhas e `slice` é a coluna.
    private static int row(SmeOp64.TileLoadStore op, int e, int slice) {
        return Aarch64MatrixTileAddressing.rowIndex(op.tile(), op.esz(), op.vertical() ? e : slice);
    }

    private static int byteOffset(SmeOp64.TileLoadStore op, int e, int slice) {
        return (op.vertical() ? slice : e) << op.esz();
    }

    private static void writeTileElement(Aarch64MatrixRegisters matrix, SmeOp64.TileLoadStore op, int e, int slice,
            long low, long high) {
        int row = row(op, e, slice);
        int byteOffset = byteOffset(op, e, slice);
        if (op.esz() == ESZ_QUAD) {
            SmeMovaOps.setZaWordAt(matrix, row, byteOffset / WORD_BYTES, low);
            SmeMovaOps.setZaWordAt(matrix, row, byteOffset / WORD_BYTES + 1, high);
        } else {
            SmeMovaOps.setZaElement(matrix, row, byteOffset, op.esz(), low);
        }
    }

    private static void storeElement(AddressSpace64 memory, Aarch64MatrixRegisters matrix, SmeOp64.TileLoadStore op,
            long address, int e, int slice) {
        int row = row(op, e, slice);
        int byteOffset = byteOffset(op, e, slice);
        if (op.esz() == ESZ_QUAD) {
            memory.write64(address, SmeMovaOps.zaWordAt(matrix, row, byteOffset / WORD_BYTES));
            memory.write64(address + WORD_BYTES, SmeMovaOps.zaWordAt(matrix, row, byteOffset / WORD_BYTES + 1));
        } else {
            SveStoreOps.writeMemory(memory, address, op.esz(),
                    SmeMovaOps.zaElement(matrix, row, byteOffset, op.esz()));
        }
    }

    // ── LDR / STR de vetor de ZA ─────────────────────────────────────────────────────────────────

    /// `ZA[(W<rv> + imm) MOD SVL_B]` ↔ `X<rn>|SP + imm × SVL_B`: a linha é `ZA0H.B[(W + imm) MOD SVL_B]` (o QEMU
    /// usa `get_tile_rowcol` com `esz = byte`, a mesma resolução de {@link
    /// Aarch64MatrixTileAddressing#resolveSliceIndex}).
    public static boolean execute(Aarch64Core core, SmeOp64.ArrayLoadStore op) {
        if (!core.smeZaEnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        AddressSpace64 memory = core.memory();
        int svlBytes = core.streamingVectorLengthBytes();
        int row = Aarch64MatrixTileAddressing.resolveSliceIndex(core.x(op.registerIndex()), op.imm(), 0, svlBytes, 1);
        long start = base(core, op.rn()) + (long) op.imm() * svlBytes;
        int rowWords = svlBytes / WORD_BYTES;
        if (op.store()) {
            for (int w = 0; w < rowWords; w++) {
                memory.write64(start + (long) w * WORD_BYTES, SmeMovaOps.zaWordAt(matrix, row, w));
            }
            return false;
        }
        long[] buffer = new long[rowWords];
        for (int w = 0; w < rowWords; w++) {
            buffer[w] = memory.read64(start + (long) w * WORD_BYTES);
        }
        for (int w = 0; w < rowWords; w++) {
            SmeMovaOps.setZaWordAt(matrix, row, w, buffer[w]);
        }
        return false;
    }

    // ── LDR / STR de ZT0 ─────────────────────────────────────────────────────────────────────────

    /// Executa {@link SmeOp64.Zt0LoadStore}.
    public static boolean execute(Aarch64Core core, SmeOp64.Zt0LoadStore op) {
        if (!core.smeZt0EnabledCheck(op.instructionAddress())) {
            return true;
        }
        Aarch64MatrixRegisters matrix = core.matrix();
        AddressSpace64 memory = core.memory();
        long start = base(core, op.rn());
        int words = Aarch64MatrixRegisters.ZT0_BITS / Long.SIZE;
        if (op.store()) {
            for (int w = 0; w < words; w++) {
                memory.write64(start + (long) w * WORD_BYTES, matrix.zt0Word(w));
            }
            return false;
        }
        long[] buffer = new long[words];
        for (int w = 0; w < words; w++) {
            buffer[w] = memory.read64(start + (long) w * WORD_BYTES);
        }
        for (int w = 0; w < words; w++) {
            matrix.setZt0Word(w, buffer[w]);
        }
        return false;
    }
}
