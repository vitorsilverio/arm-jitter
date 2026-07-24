package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// `CodeEmitter` de diagnóstico que executa CADA bloco por dois emissores — uma `reference`
/// (oráculo, normalmente o interpretado) e um `candidate` (normalmente o ASM/JIT) — e compara
/// o estado observável da CPU, lançando ao PRIMEIRO bloco que divergir. Localiza com precisão
/// o bloco/op onde um backend diverge do outro.
///
/// Isolamento de memória: o candidato roda em um **core scratch** cuja memória GRAVA as
/// escritas no bus real (preservando semântica de espelhamento/alias do hardware) e as DESFAZ
/// ao final (restaurando os bytes originais). Assim o candidato vê a memória corretamente
/// (inclusive seus próprios stores via espelho) sem poluir o load do oráculo, que então roda
/// no core real pristino e dirige a trajetória. Usa {@link ArmCore#saveState}/{@link ArmCore#loadState}.
///
/// Blocos com {@link IrOp.Swi}, {@link IrOp.Coprocessor} ou {@link IrOp.Breakpoint} (B7.5) são
/// executados só pelo oráculo (sem comparação): dependem de colaboradores do host (SWI
/// dispatcher, CP15, BKPT dispatcher) que o core scratch não replica; suas emissões ASM
/// espelham o interpretador.
public final class DivergenceCheckingCodeEmitter implements CodeEmitter {
    private final CodeEmitter reference;
    private final CodeEmitter candidate;
    private final ArmArchitecture architecture;

    private RecordingAddressSpace scratchMemory;
    private ArmCore scratchCore;

    /// @param reference    emissor oráculo (dirige a execução real)
    /// @param candidate    emissor sob teste (comparado contra o oráculo, isolado)
    /// @param architecture arquitetura do core scratch (deve casar com a do core real)
    public DivergenceCheckingCodeEmitter(CodeEmitter reference, CodeEmitter candidate, ArmArchitecture architecture) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.architecture = Objects.requireNonNull(architecture, "architecture");
    }

    @Override
    public CompiledBlock emit(IrBlock block) {
        CompiledBlock referenceBlock = reference.emit(block);
        CompiledBlock candidateBlock = candidate.emit(block);
        boolean hostDependentAlways = usesHostCollaborators(block);
        return core -> {
            // Perfil M (B7.5): o core scratch abaixo SEMPRE nasce com `AProfileExceptionModel`
            // (default do construtor de `ArmCore`) — nunca compartilha o `MProfileExceptionModel`
            // real, que carrega estado mutável próprio (MSP/PSP sombra, pilha de exceções ativas,
            // PRIMASK/CONTROL...) inacessível/não-isolável pelo candidato scratch. Sem este desvio,
            // qualquer bloco que toque esse estado (BX/POP para EXC_RETURN, MRS/MSR SYSm, CPS,
            // WFI) diverge por definição — não é um bug do candidato, é o scratch rodando com o
            // modelo de exceção ERRADO. Mesma ideia de "depende de colaborador do host que o
            // scratch não replica" do SWI/Coprocessor, aplicada ao core inteiro em vez de só ops
            // pontuais: comparação PER-BLOCO sob perfil M fica para uma task futura que ensine
            // `ensureScratch` a clonar o `MProfileExceptionModel` (savestate próprio, ainda
            // inexistente) em vez de descartar a granularidade inteira.
            boolean hostDependent = hostDependentAlways || core.exceptionModel() instanceof MProfileExceptionModel;
            if (hostDependent) {
                return referenceBlock.execute(core); // sem comparação (SWI/CP15/perfil M)
            }
            ensureScratch(core);

            byte[] before = saveState(core);
            scratchMemory.resetTaint();
            restoreState(scratchCore, before);
            int candidateCycles;
            CpuSnapshot candidateSnapshot;
            try {
                candidateCycles = candidateBlock.execute(scratchCore);
                candidateSnapshot = CpuSnapshot.capture(scratchCore);
            } catch (RuntimeException | Error failure) {
                throw divergence(block, "candidato lançou " + failure, failure);
            } finally {
                scratchMemory.undo(); // restaura o bus real antes do oráculo rodar
            }
            boolean ioTouched = scratchMemory.ioTainted();

            int referenceCycles = referenceBlock.execute(core);
            if (ioTouched) {
                // O candidato tocou I/O (0x04xxxxxx): efeitos colaterais de hardware
                // (registradores write-triggered, FIFO) não são sandboxáveis — não compara.
                return referenceCycles;
            }
            CpuSnapshot referenceSnapshot = CpuSnapshot.capture(core);

            try {
                referenceSnapshot.assertEqualTo(candidateSnapshot, blockLabel(block));
            } catch (EquivalenceMismatchException mismatch) {
                throw divergence(block, mismatch.getMessage(), mismatch);
            }
            if (referenceCycles != candidateCycles) {
                throw divergence(block,
                        "ciclos internos: ref=" + referenceCycles + " cand=" + candidateCycles, null);
            }
            return referenceCycles;
        };
    }

    @Override
    public CodegenBackend backend() {
        return reference.backend();
    }

    private void ensureScratch(ArmCore realCore) {
        if (scratchCore == null) {
            scratchMemory = new RecordingAddressSpace(realCore.memory());
            scratchCore = new ArmCore(scratchMemory, SwiDispatcher.empty(), architecture);
        }
    }

    private static boolean usesHostCollaborators(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (op instanceof IrOp.Swi || op instanceof IrOp.Coprocessor || op instanceof IrOp.Breakpoint) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException divergence(IrBlock block, String detail, Throwable cause) {
        StringBuilder sb = new StringBuilder("Divergência ASM/interpretador em ")
                .append(blockLabel(block)).append(": ").append(detail)
                .append("\n  bloco [0x").append(Integer.toHexString(block.startPc()))
                .append(", 0x").append(Integer.toHexString(block.endPc())).append(") ops:");
        for (IrOp op : block.operations()) {
            sb.append("\n    ").append(op);
        }
        return new IllegalStateException(sb.toString(), cause);
    }

    private static String blockLabel(IrBlock block) {
        return "block@0x" + Integer.toHexString(block.startPc());
    }

    private static byte[] saveState(ArmCore core) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            core.saveState(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void restoreState(ArmCore core, byte[] state) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(state))) {
            core.loadState(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Memória que grava as escritas do candidato no bus real (semântica de hardware:
    /// espelhamento, alias) registrando o byte antigo de cada endereço tocado, e as desfaz em
    /// {@link #undo} (replay reverso), restaurando o bus à condição pristina para o oráculo.
    private static final class RecordingAddressSpace implements AddressSpace {
        private final AddressSpace real;
        /// Log de (endereço, byte antigo) na ordem de escrita; desfeito em ordem reversa.
        private final List<int[]> writes = new ArrayList<>();
        /// `true` se o candidato tocou I/O (0x04xxxxxx) — bloco não-sandboxável.
        private boolean ioTainted;

        private RecordingAddressSpace(AddressSpace real) {
            this.real = real;
        }

        private void resetTaint() {
            ioTainted = false;
        }

        private boolean ioTainted() {
            return ioTainted;
        }

        private void undo() {
            for (int i = writes.size() - 1; i >= 0; i--) {
                int[] entry = writes.get(i);
                real.write8(entry[0], entry[1]);
            }
            writes.clear();
        }

        private static boolean isIo(int address) {
            return (address >>> 24) == 0x04;
        }

        @Override
        public int read8(int address) {
            if (isIo(address)) ioTainted = true;
            return real.read8(address);
        }

        @Override
        public int read16(int address) {
            if (isIo(address)) ioTainted = true;
            return real.read16(address);
        }

        @Override
        public int read32(int address) {
            if (isIo(address)) ioTainted = true;
            return real.read32(address);
        }

        @Override
        public void write8(int address, int value) {
            // Escritas em I/O (0x04xxxxxx) têm efeitos colaterais de hardware que o undo por-byte
            // não reverte (ex.: limpar bit de erro do IPCFIFOCNT, disparar DMA). Marca taint e
            // descarta: um store não altera registradores, e o bloco será pulado na comparação.
            if (isIo(address)) {
                ioTainted = true;
                return;
            }
            writes.add(new int[]{address, real.read8(address)});
            real.write8(address, value);
        }

        @Override
        public void write16(int address, int value) {
            write8(address, value);
            write8(address + 1, value >>> 8);
        }

        @Override
        public void write32(int address, int value) {
            write16(address, value);
            write16(address + 2, value >>> 16);
        }

        @Override
        public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
            return real.accessCycles(address, sizeBytes, type);
        }

        @Override
        public boolean providesAccessCycles() {
            return real.providesAccessCycles();
        }
    }
}
