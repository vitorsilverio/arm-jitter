package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
///
/// Regra geral: as ops são suportadas para qualquer condição — o {@code AsmBlockCompiler} emite um
/// guard {@code evalCond} por op, espelhando o interpretador. As exceções abaixo são por motivos
/// NÃO-condicionais:
/// <ul>
///   <li>{@link IrOp.Swap} — raro, mantém fallback.</li>
///   <li>{@link IrOp.Alu} com {@code dst=15} e {@code setFlags=true} — restaura SPSR.</li>
///   <li>BLX ({@link IrOp.BranchExchange} com {@code link}, {@link IrOp.ThumbBlSuffix} com {@code exchange}).</li>
///   <li>Formas ARMv5TE com escrita em PC (o comum — Saturating/DspMultiply/LDRD/STRD sem PC —
///       é emitido nativamente).</li>
/// </ul>
///
/// <p>Desde a task C2, flags lógicos com carry-out do barrel shifter (MOVS/ANDS/... com operando
/// shifted-register) e os shifts com S (LSLS/...) também são emitidos nativamente.</p>
///
/// <p>Desde a task B1.6, todas as ops ARMv6 de B1.2 (SXT*/UXT*/REV*/UMAAL), B1.3 (paralelas,
/// SEL, PKHBT/PKHTB, SSAT/USAT, USAD8/USADA8) e B1.4 (LDREX/STREX/CLREX) também são nativas — só
/// as instruções de sistema da B1.5 (CPS/SETEND/SRS/RFE/WFI) permanecem interpretadas, por
/// serem raras/kernel-only (ver task B1.6).</p>
public final class AsmNativePolicy {
    private AsmNativePolicy() {
    }

    public static boolean supports(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    public static boolean supports(IrOp op) {
        // Condição ≠ AL é suportada nativamente: o AsmBlockCompiler emite um guard `evalCond` por
        // op (espelhando o `if (!evalCond) return false;` do interpretador). As rejeições abaixo são
        // por motivos NÃO-CONDICIONAIS: BLX/interworking, Swap, formas com escrita em PC e as ops
        // ARMv6 de B1.2 (nativas só na B1.6).
        return switch (op) {
            case IrOp.Alu alu -> supportsAlu(alu);
            // MLS (B3.1, subtractFromAccumulator=true): opcode novo sem case no emissor ASM ainda
            // — interpretado até uma task futura de B3, mesmo padrão de ORN/B2.2 até B1.6/B3.6.
            case IrOp.Multiply m -> !m.subtractFromAccumulator();
            // UMAAL (ARMv6, B1.2): acumulador duplo agora emitido nativamente (task B1.6).
            case IrOp.LongMultiply ignored -> true;
            // ARMv5TE emitidas nativamente (Mobiclip/SDK usam pesado). Só as formas com escrita
            // em PC (UNPREDICTABLE/troca de bloco) ficam no interpretado.
            case IrOp.Saturating s -> s.dst() != 15;
            case IrOp.DspMultiply d -> d.dst() != 15 && !(d.op2() == 2 && d.rn() == 15);
            // Ops ARMv6 da B1.3 (paralelas, SEL, saturação, USAD): nativas desde a task B1.6.
            case IrOp.ParallelAlu ignored -> true;
            case IrOp.Sel ignored -> true;
            case IrOp.Saturate ignored -> true;
            case IrOp.AbsDiffSum ignored -> true;
            // Acessos exclusivos (B1.4): nativos desde a task B1.6 — o monitor de exclusividade
            // é checado/marcado por helper em AsmRuntimeHelpers, mesma ordem do interpretador.
            case IrOp.LoadExclusive ignored -> true;
            case IrOp.StoreExclusive ignored -> true;
            case IrOp.ClearExclusive ignored -> true;
            // LDRD para o par (first,second) escreve os dois via emitStoreRegister puro, sem o
            // tratamento de interworking que emitLoad/emitLoadLiteral dão a PC — então nenhum dos
            // dois pode ser PC num load. STRD só LÊ os registradores (sem troca de modo), então PC
            // como origem é seguro nativamente.
            case IrOp.DoubleTransfer d -> !d.load() || (d.first() != 15 && d.second() != 15);
            case IrOp.Load ignored -> true;   // offsets shifted-register agora emitidos nativamente
            case IrOp.Store ignored -> true;
            case IrOp.LoadLiteral ignored -> true;
            case IrOp.MultipleTransfer ignored -> true;
            case IrOp.Branch ignored -> true;
            case IrOp.BranchExchange b -> !b.link(); // BLX -> interpretado
            case IrOp.ThumbBlPrefix ignored -> true;
            case IrOp.ThumbBlSuffix s -> !s.exchange(); // BLX -> interpretado
            case IrOp.Push ignored -> true;
            case IrOp.Pop ignored -> true;
            case IrOp.PsrTransfer ignored -> true;
            case IrOp.Swi ignored -> true;
            case IrOp.Coprocessor ignored -> true;
            case IrOp.Undefined ignored -> true;
            case IrOp.Swap ignored -> false;
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
            // Instruções de sistema ARMv6 da B1.5 (CPS/SETEND/SRS/RFE/WFI): interpretadas até a
            // emissão nativa da B1.6, junto com as demais ops v6 de B1.2-B1.4.
            case IrOp.ChangeProcessorState ignored -> false;
            case IrOp.SetEndianness ignored -> false;
            case IrOp.StoreReturnState ignored -> false;
            case IrOp.ReturnFromException ignored -> false;
            case IrOp.WaitForInterrupt ignored -> false;
            // `MOVT` (Thumb-2, B2.2): interpretado até a emissão nativa de uma task futura de B2,
            // mesmo padrão de B1.2-B1.5 até B1.6.
            case IrOp.MoveTop ignored -> false;
            // DMB/DSB/ISB (Thumb-2, B2.5): NOP observável, mas ainda assim interpretado por
            // enquanto — mesmo padrão de toda instrução de sistema nova (CPS/SETEND/SRS/RFE/WFI/
            // MOVT) até uma task futura de emissão nativa.
            case IrOp.MemoryBarrier ignored -> false;
            // IT block/branches Thumb-2 novos (B2.4): interpretados até uma task futura de emissão
            // nativa, mesmo padrão de todo op novo B1.x/B2.x até B1.6/uma task dedicada.
            case IrOp.SetItState ignored -> false;
            case IrOp.TableBranch ignored -> false;
            case IrOp.CompareBranchZero ignored -> false;
            // Inteiro ARMv7 (B3.1): interpretadas até a emissão nativa da B3.6, mesmo padrão de
            // todo op novo B1.x/B2.x até sua respectiva task de emissão nativa.
            case IrOp.BitFieldExtract ignored -> false;
            case IrOp.BitFieldInsert ignored -> false;
            case IrOp.BitReverse ignored -> false;
            case IrOp.Divide ignored -> false;
            // VFP (B3.4): decode ainda não existe (B3.5) e a emissão nativa é B3.6 — todo record
            // Vfp* fica interpretado até lá.
            case IrOp.VfpAlu ignored -> false;
            case IrOp.VfpMoveImmediate ignored -> false;
            case IrOp.VfpCompare ignored -> false;
            case IrOp.VfpConvert ignored -> false;
            case IrOp.VfpLoad ignored -> false;
            case IrOp.VfpStore ignored -> false;
            case IrOp.VfpMultipleTransfer ignored -> false;
            case IrOp.VfpCoreTransfer ignored -> false;
            case IrOp.VfpCorePairTransfer ignored -> false;
            case IrOp.VfpSystemTransfer ignored -> false;
        };
    }

    private static boolean supportsAlu(IrOp.Alu alu) {
        // Task C2: flags lógicos com carry-out do shifter (src2 shifted-register com S) e os
        // shifts com S agora são NATIVOS — helpers shiftedOperandCarry/doXxxS espelham o
        // interpretador. Exceções restantes:
        // dst=15 + setFlags: restaura CPSR a partir do SPSR, delega ao interpretado.
        // ORN (Thumb-2, B2.2): opcode novo, sem case no emissor ASM ainda — interpretado até uma
        // task futura de B2, mesmo padrão de B1.2-B1.5 até B1.6.
        return (alu.dst() != 15 || !alu.setFlags()) && alu.opcode() != IrOpCode.ORN;
    }

    /// Opcodes ALU atualmente emitidos nativamente. Desde a task B1.6, todos os opcodes ALU
    /// (incl. os ARMv6 de extend/reverse/pack de B1.2-B1.3) são suportados, com duas rejeições:
    /// dst=15+setFlags (por-instância, ver {@link #supportsAlu}) e {@link IrOpCode#ORN}
    /// (Thumb-2, B2.2 — opcode novo sem emissão nativa ainda).
    public static Set<IrOpCode> supportedAluOpcodes() {
        EnumSet<IrOpCode> supported = EnumSet.allOf(IrOpCode.class);
        supported.remove(IrOpCode.ORN);
        return supported;
    }
}
