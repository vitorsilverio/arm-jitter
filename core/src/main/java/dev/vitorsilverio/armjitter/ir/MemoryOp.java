package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;

/// Operações A32/T32 de acesso à memória por registrador geral: load/store (simples, duplo,
/// literal, múltiplo), `PUSH`/`POP`, `SWP` e os acessos exclusivos.
///
/// Sub-interface selada de {@link IrOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface MemoryOp extends IrOp permits MemoryOp.Load, MemoryOp.Store,
        MemoryOp.LoadExclusive, MemoryOp.StoreExclusive, MemoryOp.ClearExclusive,
        MemoryOp.DoubleTransfer, MemoryOp.Swap, MemoryOp.LoadLiteral, MemoryOp.MultipleTransfer,
        MemoryOp.Push, MemoryOp.Pop {

    /// Operação de leitura de memória.
    record Load(
            /// Registrador de destino.
            int dst,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset já normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica extensão com sinal.
            boolean signed,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index.
            boolean postIndexed,
            /// `LDRxT` (B9.9): quando `true`, o acesso à memória usa a permissão de modo `USER`
            /// mesmo que o CPU esteja em modo privilegiado — ver
            /// {@link dev.vitorsilverio.armjitter.memory.AddressSpace#withUnprivilegedAccess}.
            boolean unprivileged,
            /// Condição necessária para executar a leitura.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.LOAD; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.memoryExecutor().executeLoad(core, this); }
        @Override public int regUse() { return (baseValueOverride < 0 ? (1 << base) : 0) | offset.regUse(); }
        @Override public int regDef() { return (1 << dst) | (writeback ? (1 << base) : 0); }
    }

    /// Operação de escrita de memória.
    record Store(
            /// Registrador de origem.
            int src,
            /// Valor fixo para usar como valor armazenado, ou `-1`.
            int srcValueOverride,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset já normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index.
            boolean postIndexed,
            /// `STRxT` (B9.9): ver {@link Load#unprivileged}.
            boolean unprivileged,
            /// Condição necessária para executar a escrita.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.STORE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.memoryExecutor().executeStore(core, this); return false; }
        @Override public int regUse() {
            int mask = baseValueOverride < 0 ? (1 << base) : 0;
            if (srcValueOverride < 0) mask |= 1 << src;
            return mask | offset.regUse();
        }
        @Override public int regDef() { return writeback ? (1 << base) : 0; }
    }

    /// `LDREX{,B,H,D}` (ARMv6/v6K) e `LDREX` de 32 bits Thumb-2 (B2.7 PR3): lê a memória no
    /// endereço `base+offset` e marca o monitor de exclusividade do core. A forma doubleword
    /// (`sizeBytes=8`) carrega o par `dst`, `dst+1`. Formas com PC não passam pelo decoder
    /// (UNPREDICTABLE).
    record LoadExclusive(
            /// Registrador de destino (primeiro do par na forma doubleword).
            int dst,
            /// Registrador base do endereço (Rn).
            int base,
            /// Offset com sinal somado a `base`. Só o `LDREX` word de 32 bits Thumb-2 tem offset
            /// não-nulo (`imm8×4` — ver `Thumb2LoadStoreDecoder`); ARM clássico e as formas
            /// `B`/`H`/`D` (ARM ou Thumb-2) sempre passam `0` aqui, igual ao endereço exato `[Rn]`
            /// que a arquitetura exige para elas.
            int offset,
            /// Tamanho do acesso em bytes (1, 2, 4 ou 8).
            int sizeBytes,
            /// Condição necessária para executar a leitura.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.LOAD_EXCLUSIVE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.memoryExecutor().executeLoadExclusive(core, this); return false; }
        /// Tamanho de acesso de `LDREXD`, que carrega o par `dst`, `dst+1`.
        private static final int DOUBLEWORD_BYTES = 8;
        @Override public int regUse() { return 1 << base; }
        @Override public int regDef() { return (1 << dst) | (sizeBytes == DOUBLEWORD_BYTES ? (1 << (dst + 1)) : 0); }
    }

    /// `STREX{,B,H,D}` (ARMv6/v6K) e `STREX` de 32 bits Thumb-2 (B2.7 PR3): escreve a memória em
    /// `base+offset` APENAS se o monitor de exclusividade cobre o endereço/tamanho; `dst` recebe
    /// 0 (sucesso, monitor consumido) ou 1 (falha, a memória fica intacta). A forma doubleword
    /// armazena o par `src`, `src+1`.
    record StoreExclusive(
            /// Registrador de status (0 = sucesso, 1 = falha).
            int dst,
            /// Registrador com o valor armazenado (primeiro do par na forma doubleword).
            int src,
            /// Registrador base do endereço (Rn).
            int base,
            /// Offset com sinal somado a `base`. Ver {@link LoadExclusive#offset}: só o `STREX`
            /// word de 32 bits Thumb-2 tem offset não-nulo.
            int offset,
            /// Tamanho do acesso em bytes (1, 2, 4 ou 8).
            int sizeBytes,
            /// Condição necessária para executar a escrita.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.STORE_EXCLUSIVE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.memoryExecutor().executeStoreExclusive(core, this); return false; }
        /// Tamanho de acesso de `STREXD`, que grava o par `src`, `src+1`.
        private static final int DOUBLEWORD_BYTES = 8;
        @Override public int regUse() { return (1 << base) | (1 << src) | (sizeBytes == DOUBLEWORD_BYTES ? (1 << (src + 1)) : 0); }
        @Override public int regDef() { return 1 << dst; }
    }

    /// `CLREX` (ARMv6K): abre o monitor de exclusividade do core.
    record ClearExclusive(
            /// Condição necessária para executar (CLREX vive no espaço incondicional → AL).
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.CLEAR_EXCLUSIVE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.memoryExecutor().executeClearExclusive(core, this); return false; }
    }

    /// Transferência de palavra dupla (LDRD/STRD): dois acessos de 32 bits consecutivos a
    /// `first` e `second`, com um único cálculo de endereço/writeback.
    record DoubleTransfer(
            /// `true` para LDRD (load), `false` para STRD (store).
            boolean load,
            /// Primeiro registrador do par (Rt).
            int first,
            /// Segundo registrador do par (Rt2). No ARM clássico (ARMv5TE) o encoding só tem um
            /// campo Rd, então `second` é sempre `first + 1`; no Thumb-2 (B2.3) `Rt`/`Rt2` são
            /// campos independentes no encoding e podem ser um par arbitrário (não-adjacente).
            int second,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo da base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset já normalizado pelo decoder/lifter.
            IrOperand offset,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index.
            boolean postIndexed,
            /// Condição necessária para executar a operação.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.DOUBLE_TRANSFER; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.memoryExecutor().executeDoubleTransfer(core, this); }
        @Override public int regUse() {
            int mask = baseValueOverride < 0 ? (1 << base) : 0;
            mask |= offset.regUse();
            if (!load) mask |= (1 << first) | (1 << second); // STRD lê o par
            return mask;
        }
        @Override public int regDef() {
            int mask = load ? (1 << first) | (1 << second) : 0;
            if (writeback) mask |= 1 << base;
            return mask;
        }
    }

    /// Troca valor de memória com registrador.
    record Swap(
            /// Registrador que recebe o valor antigo da memória.
            int dst,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Registrador cujo valor será escrito na memória.
            int src,
            /// Valor fixo para usar como valor escrito, ou `-1`.
            int srcValueOverride,
            /// Tamanho do acesso em bytes.
            int sizeBytes,
            /// Condição necessária para executar a troca.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.SWAP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.memoryExecutor().executeSwap(core, this); }
        @Override public int regUse() {
            int mask = baseValueOverride < 0 ? (1 << base) : 0;
            if (srcValueOverride < 0) mask |= 1 << src;
            return mask;
        }
        @Override public int regDef() { return 1 << dst; }
    }

    /// Lê um valor de endereço absoluto literal (pool de constantes relativo ao PC).
    record LoadLiteral(
            /// Registrador de destino.
            int dst,
            /// Endereço absoluto a ler (já alinhado/resolvido pelo decoder — ver `ADR`/`LDR
            /// Rt,[PC,#imm]`).
            int address,
            /// Tamanho do acesso em bytes (1, 2 ou 4). Thumb-1 só tinha a forma word (4); as formas
            /// Thumb-2 `LDRB`/`LDRH`/`LDRSB`/`LDRSH` literais (B2.3) reusam este mesmo IrOp.
            int sizeBytes,
            /// Indica extensão com sinal (`LDRSB`/`LDRSH` literais, B2.3).
            boolean signed,
            /// Condição necessária para executar a leitura.
            Condition condition) implements MemoryOp {
        /// Cria uma leitura literal de word sem sinal (forma clássica Thumb-1).
        public LoadLiteral(int dst, int address, Condition condition) {
            this(dst, address, 4, false, condition);
        }

        @Override public int kind() { return Kind.LOAD_LITERAL; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.memoryExecutor().executeLoadLiteral(core, this); }
        @Override public int regDef() { return 1 << dst; }
    }

    /// Transferência sequencial de múltiplos registradores.
    record MultipleTransfer(
            /// `true` para load, `false` para store.
            boolean load,
            /// Registrador base.
            int base,
            /// Máscara de registradores.
            int registerMask,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Valor de `PC` a armazenar quando a máscara contém r15, ou `-1`.
            int pcStoreValueOverride,
            /// Usa banco USR/SYS ou restaura CPSR pelo SPSR em `LDM ... pc^`.
            boolean userMode,
            /// Modo de endereçamento ARM/THUMB.
            BlockTransferMode mode,
            /// Indica máscara vazia em `LDM`/`STM`, caso especial do ARM7TDMI.
            boolean emptyRegisterList,
            /// Condição necessária para executar.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.MULTIPLE_TRANSFER; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.transferExecutor().executeMultipleTransfer(core, this); }
        // Store lê todos os registradores da lista.
        @Override public int regUse() { return (1 << base) | (load ? 0 : registerMask); }
        @Override public int regDef() {
            int mask = load ? registerMask : 0;
            if (writeback) mask |= 1 << base;
            // LDM user-mode (^ sem PC) carrega no banco USER/SYS de r8-r14, não no r8-r14
            // bancado do modo atual. Exclui r8-r14 do conjunto def para que a DCE não elimine
            // escritas no r8-r14 do modo atual que precedem essa op.
            if (load && userMode && (registerMask & GprMask.PC) == 0) mask &= GprMask.UNBANKED_R0_R7;
            return mask;
        }
    }

    /// Operação de push THUMB.
    record Push(
            /// Máscara de registradores r0-r7.
            int registerMask,
            /// Indica inclusão de LR.
            boolean includeLr,
            /// Condição necessária para executar.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.PUSH; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.transferExecutor().executePush(core, this); return false; }
        @Override public int regUse() { return GprMask.SP | registerMask | (includeLr ? GprMask.LR : 0); }
        @Override public int regDef() { return GprMask.SP; }
    }

    /// Operação de pop THUMB.
    record Pop(
            /// Máscara de registradores r0-r7.
            int registerMask,
            /// Indica inclusão de PC.
            boolean includePc,
            /// Condição necessária para executar.
            Condition condition) implements MemoryOp {
        @Override public int kind() { return Kind.POP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.transferExecutor().executePop(core, this); }
        @Override public int regUse() { return GprMask.SP; }
        @Override public int regDef() { return registerMask | GprMask.SP | (includePc ? GprMask.PC : 0); }
    }
}
