package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Operações A32/T32 de desvio: `B`/`BL`/`BX`/`BLX`, as duas metades do `BL` do Thumb, `TBB`/`TBH`,
/// `CBZ`/`CBNZ`, `BXNS`/`BLXNS` e os laços de baixo overhead (`WLS`/`DLS`/`LE`).
///
/// Sub-interface selada de {@link IrOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface BranchOp extends IrOp permits BranchOp.Branch, BranchOp.BranchExchange,
        BranchOp.ThumbBlPrefix, BranchOp.ThumbBlSuffix, BranchOp.TableBranch,
        BranchOp.CompareBranchZero, BranchOp.SecureBranchExchange, BranchOp.LoopStart,
        BranchOp.LoopEnd {

    /// Operação de branch.
    record Branch(
            /// Endereço absoluto de destino quando conhecido.
            int target,
            /// Valor a gravar no link register quando `link` estiver ativo.
            int returnAddress,
            /// Indica atualização do link register.
            boolean link,
            /// Condição necessária para tomar o branch.
            Condition condition,
            /// Conjunto de instruções esperado após o branch.
            InstructionSet targetSet) implements BranchOp {
        @Override public int kind() { return Kind.BRANCH; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeBranch(core, this); }
        @Override public int regDef() { return GprMask.PC | (link ? GprMask.LR : 0); }
    }

    /// Branch exchange, usado para trocar entre ARM e THUMB (e BLX quando `link`).
    record BranchExchange(
            /// Registrador que contém o destino.
            int sourceRegister,
            /// Valor fixo para usar como destino, ou `-1`.
            int sourceValueOverride,
            /// Indica gravação do endereço de retorno no link register (BLX).
            boolean link,
            /// Valor a gravar no link register quando `link` estiver ativo.
            int returnAddress,
            /// Condição necessária para tomar o branch.
            Condition condition) implements BranchOp {
        @Override public int kind() { return Kind.BRANCH_EXCHANGE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeBranchExchange(core, this); }
        @Override public int regUse() { return sourceValueOverride < 0 ? (1 << sourceRegister) : 0; }
        @Override public int regDef() { return GprMask.PC | (link ? GprMask.LR : 0); }
    }

    /// Primeira metade de `BL` THUMB.
    record ThumbBlPrefix(
            /// Valor assinado alto já deslocado.
            int highOffset,
            /// Endereço da instrução.
            int address,
            /// Condição necessária para executar a operação.
            Condition condition) implements BranchOp {
        @Override public int kind() { return Kind.THUMB_BL_PREFIX; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.branchExecutor().executeThumbBlPrefix(core, this); return false; }
        @Override public int regDef() { return GprMask.LR; }
    }

    /// Segunda metade de `BL`/`BLX` THUMB.
    record ThumbBlSuffix(
            /// Valor baixo já deslocado.
            int lowOffset,
            /// Endereço da instrução.
            int address,
            /// `true` para a forma BLX (alinha o destino e troca para ARM).
            boolean exchange,
            /// Condição necessária para executar a operação.
            Condition condition) implements BranchOp {
        @Override public int kind() { return Kind.THUMB_BL_SUFFIX; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeThumbBlSuffix(core, this); }
        @Override public int regUse() { return GprMask.LR; }
        @Override public int regDef() { return GprMask.PC; }
    }

    /// `TBB`/`TBH` (Thumb-2, ARMv6T2+, B2.4): lê um byte (`TBB`) ou halfword (`TBH`) sem sinal de
    /// uma tabela em memória indexada por `rm` a partir de `rn`, e desvia para
    /// `PC_da_instrução + 4 + 2 * valor_lido`. `IrOp` dedicado em vez de compor `Load`+`Branch`
    /// genéricos — ver a decisão D3 registrada em `b2.4-thumb2-branches-it.md`: o valor lido da
    /// tabela nunca é observável em nenhum `Rd` (não há registrador-escrátel arquitetural para
    /// modelar), o destino é `PC_base + 2*tabela` (uma composição que nenhum `IrOp` de branch
    /// existente expressa) e a instrução nunca troca de instruction-set (sempre permanece Thumb).
    record TableBranch(
            /// Registrador base da tabela (Rn); pode ser PC.
            int rn,
            /// Valor fixo para usar como base quando `rn` é PC, ou `-1`.
            int rnValueOverride,
            /// Registrador de índice (Rm).
            int rm,
            /// Valor fixo para usar como índice quando `rm` é PC, ou `-1` (PC como índice é
            /// incomum mas não rejeitado pelo decoder — mesma convenção de override do resto do
            /// IR).
            int rmValueOverride,
            /// `PC` LIDO da própria instrução `TBB`/`TBH` (endereço da instrução + 4, resolvido em
            /// tempo de decode) — a base do DESVIO (`target = pcBase + 2*tabela`). Deliberadamente
            /// SEPARADO de `rn`/`rnValueOverride` (a base da TABELA, usada só para o endereço de
            /// leitura): quando `Rn≠PC` (tabela em endereço computado por `ADR` antes), a leitura e
            /// o desvio usam bases DIFERENTES — reaproveitar `rn` para as duas coisas seria
            /// correto só no caso comum `TBB [PC,Rm]`, mas incorreto em geral.
            int pcBase,
            /// `true` para `TBH` (halfword, índice em unidades de 2 bytes); `false` para `TBB`
            /// (byte, índice em unidades de 1 byte).
            boolean halfword,
            /// Condição necessária para executar o desvio.
            Condition condition) implements BranchOp {
        @Override public int kind() { return Kind.TABLE_BRANCH; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeTableBranch(core, this); }
        @Override public int regUse() { return (rnValueOverride < 0 ? (1 << rn) : 0) | (rmValueOverride < 0 ? (1 << rm) : 0); }
    }

    /// `CBZ`/`CBNZ` (Thumb-1, ARMv6T2+, B2.4): desvia para `target` (já resolvido pelo decoder)
    /// quando `rn` (sempre R0-R7) é zero (`CBZ`) ou não-zero (`CBNZ`) — NUNCA afeta NZCV, ao
    /// contrário de um `CMP`+`Branch` equivalente, e por isso não reaproveita `IntegerOp.Alu`. `rn`
    /// nunca é PC/SP (restrito a 3 bits no encoding), então não precisa de value override.
    record CompareBranchZero(
            /// Registrador testado (R0-R7).
            int rn,
            /// Endereço absoluto de destino quando o branch é tomado.
            int target,
            /// `true` para `CBNZ` (desvia quando `rn≠0`); `false` para `CBZ` (desvia quando
            /// `rn==0`).
            boolean branchIfNonZero,
            /// Condição necessária para executar (normalmente AL; ver Armadilhas de B2.4 — CBZ/
            /// CBNZ dentro de um IT block é UNPREDICTABLE no ARM ARM, mas o lifter aplica o mesmo
            /// mecanismo uniforme de override de condição usado para toda instrução dentro de um
            /// IT block, então este campo pode carregar uma condição não-AL nesse caso raro).
            Condition condition) implements BranchOp {
        @Override public int kind() { return Kind.COMPARE_BRANCH_ZERO; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeCompareBranchZero(core, this); }
        // CBZ/CBNZ (B2.4) lê rn (nunca tem value override — sempre R0-R7).
        @Override public int regUse() { return 1 << rn; }
    }

    /// `BXNS`/`BLXNS` (perfil M, B15.4): branch-exchange com troca de estado Secure/Non-secure —
    /// mesmos campos de {@link BranchExchange}, mas despachado para
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel#secureBranchExchange}
    /// em vez da troca ARM/THUMB genérica (perfil M não tem estado ARM).
    record SecureBranchExchange(
            /// Registrador que contém o destino (`Rm`) — sempre um registrador real (`BXNS`/
            /// `BLXNS` não têm forma imediata).
            int sourceRegister,
            /// Valor fixo para usar como destino, ou `-1`.
            int sourceValueOverride,
            /// Indica gravação de retorno (`BLXNS`); `MProfileExceptionModel` decide sozinho ONDE
            /// esse retorno vai (LR direto quando não troca de estado, pilha Secure + `LR` mágico
            /// de `FNC_RETURN` quando troca — ver Javadoc do método).
            boolean link,
            /// Endereço de retorno (da instrução seguinte, com `bit0` setado) a usar quando `link`.
            int returnAddress,
            /// Condição necessária para tomar o branch.
            Condition condition) implements BranchOp {
        @Override public int kind() { return Kind.SECURE_BRANCH_EXCHANGE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeSecureBranchExchange(core, this); }
    }

    /// `DLS`/`WLS`/`DLSTP`/`WLSTP` (perfil M, B15.6/B16.15, Low Overhead Branch Extension): grava
    /// `rn` em `LR` (contador de loop); `WLS`/`WLSTP` (`hasSkipBranch=true`) desviam para `target`
    /// quando `rn==0` (loop "while", pode nunca executar) — `DLS`/`DLSTP` (`hasSkipBranch=false`)
    /// nunca desviam, só inicializam `LR`. As formas `*TP` (tail-predication, `ltpsize` de `0` a
    /// `3`) também gravam `FPSCR.LTPSIZE` — no `WLSTP` só quando o loop de fato começa (`rn!=0`,
    /// `trans_WLS` do QEMU), no `DLSTP` sempre.
    record LoopStart(
            /// Registrador cujo valor inicializa o contador de loop (`LR`).
            int rn,
            /// Endereço absoluto de destino quando o branch é tomado (`WLS` com `rn==0`);
            /// irrelevante quando `hasSkipBranch` é `false`.
            int target,
            /// `true` para `WLS`/`WLSTP` (pode desviar); `false` para `DLS`/`DLSTP` (nunca desvia).
            boolean hasSkipBranch,
            /// `FPSCR.LTPSIZE` a gravar (`0`-`3`, formas `*TP`) ou {@link #NO_LTPSIZE} (formas puras,
            /// `LTPSIZE` intocado).
            int ltpsize,
            /// Condição necessária para executar.
            Condition condition) implements BranchOp {
        /// Sentinela de "forma pura": nenhum `LTPSIZE` a gravar.
        public static final int NO_LTPSIZE = -1;

        /// Forma pura (`DLS`/`WLS`), sem tail-predication — a assinatura anterior à B16.15 (G3).
        public LoopStart(int rn, int target, boolean hasSkipBranch, Condition condition) {
            this(rn, target, hasSkipBranch, NO_LTPSIZE, condition);
        }

        @Override public int kind() { return Kind.LOOP_START; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeLoopStart(core, this); }
    }

    /// `LE`/`LETP` (perfil M, B15.6/B16.15, Low Overhead Branch Extension). **Achado medido contra
    /// `trans_LE` do QEMU real** (não deduzido do nome do bit `f`): `forever=true` (`f=1`) desvia
    /// INCONDICIONALMENTE para `target` sem tocar `LR`; `forever=false` decrementa `LR` e desvia de
    /// volta só se `LR` (não-assinado) era maior que o decremento ANTES de subtrair (a checagem
    /// ocorre antes, não depois). Sem `tailPredicated` o decremento é `1`; com ele (`LETP`) é
    /// `1 << (4 - LTPSIZE)` (elementos processados por iteração) e a saída do loop restaura
    /// `LTPSIZE = 4`.
    record LoopEnd(
            /// Endereço absoluto de destino do desvio (para trás, início do corpo do loop).
            int target,
            /// `true` para a forma "loop-forever" (`f=1`, desvio incondicional, `LR` intocado).
            boolean forever,
            /// `true` para `LETP` (decremento por `LTPSIZE`, restaura `LTPSIZE` ao sair).
            boolean tailPredicated,
            /// Condição necessária para executar.
            Condition condition) implements BranchOp {
        /// Forma pura (`LE`), sem tail-predication — a assinatura anterior à B16.15 (G3).
        public LoopEnd(int target, boolean forever, Condition condition) {
            this(target, forever, false, condition);
        }

        @Override public int kind() { return Kind.LOOP_END; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.branchExecutor().executeLoopEnd(core, this); }
    }
}
