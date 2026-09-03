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
///
/// <p>Desde a task B3.6 (PR1), o inteiro ARMv7 da B3.1/B3.2 também é nativo: MOVT, DMB/DSB/ISB
/// (NOP), SBFX/UBFX, BFI/BFC, RBIT, SDIV/UDIV e MLS (Multiply com {@code subtractFromAccumulator}).
/// Desde a task B3.6 (PR2), o VFP da B3.4/B3.5 também é nativo — cobertura completa, sem
/// exceção (ver os 10 casos {@code IrOp.Vfp*} abaixo).</p>
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
            // MLS (B3.1, subtractFromAccumulator=true): emitido nativamente desde a task B3.6
            // (ISUB no lugar do IADD no caminho MLA já existente).
            case IrOp.Multiply ignored -> true;
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
            // `unprivileged` (LDRxT/STRxT, B9.9) precisa de AddressSpace#withUnprivilegedAccess
            // ao redor do acesso — sem equivalente no emissor nativo, cai no interpretado (mesma
            // simplificação já aplicada a outras variantes raras desta escada, ex. B9.8.x).
            case IrOp.Load l -> !l.unprivileged();   // offsets shifted-register agora emitidos nativamente
            case IrOp.Store s -> !s.unprivileged();
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
            case IrOp.CoprocessorDouble ignored -> true;
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
            // `MOVT` (Thumb-2, B2.2): emitido nativamente desde a task B3.6 (AND/OR direto,
            // preservando os 16 bits baixos existentes).
            case IrOp.MoveTop ignored -> true;
            // DMB/DSB/ISB (Thumb-2, B2.5): NOP observável (ver IrOp.MemoryBarrier) — desde a task
            // B3.6 não emite nenhum bytecode além do Cycle/Fetch já emitidos separadamente no bloco.
            case IrOp.MemoryBarrier ignored -> true;
            // IT block/branches Thumb-2 novos (B2.4): interpretados até uma task futura de emissão
            // nativa, mesmo padrão de todo op novo B1.x/B2.x até B1.6/uma task dedicada.
            case IrOp.SetItState ignored -> false;
            case IrOp.TableBranch ignored -> false;
            case IrOp.CompareBranchZero ignored -> false;
            // Inteiro ARMv7 (B3.1): emitidas nativamente desde a task B3.6 (PR1), bytecode direto
            // sem helper — ver `emitBitFieldExtract`/`emitBitFieldInsert`/`emitBitReverse`/`emitDivide`.
            case IrOp.BitFieldExtract ignored -> true;
            case IrOp.BitFieldInsert ignored -> true;
            case IrOp.BitReverse ignored -> true;
            case IrOp.Divide ignored -> true;
            // VFP (B3.4/B3.5): emitidas nativamente desde a task B3.6 (PR2) — cobertura completa,
            // sem exceção. VfpAlu/VfpMoveImmediate/VfpLoad/VfpStore/VfpCoreTransfer são bytecode
            // direto (caminho quente); VfpCompare/VfpConvert/VfpMultipleTransfer/
            // VfpCorePairTransfer/VfpSystemTransfer chamam um helper estático em
            // AsmRuntimeHelpers (ver AsmBlockCompiler#emitVfpAlu e vizinhos).
            // NEON "three same" (B13.2): interpretado, como todo `Kind` vetorial novo desde B8.4/
            // B8.6 do lado A64 — a emissão nativa é task própria, depois que a escada B13 fechar.
            case IrOp.NeonThreeSame ignored -> false;
            case IrOp.NeonLoadStoreMultiple ignored -> false;
            case IrOp.NeonLoadStoreSingle ignored -> false;
            case IrOp.NeonLoadAllLanes ignored -> false;
            case IrOp.NeonPairwise ignored -> false;
            case IrOp.NeonFpThreeSame ignored -> false;
            case IrOp.NeonFpPairwise ignored -> false;
            // NEON "2-reg shift by immediate" (B13.7): interpretado, como todo `Kind` vetorial.
            case IrOp.NeonShiftImmediate ignored -> false;
            case IrOp.VfpAlu ignored -> true;
            case IrOp.VfpMoveImmediate ignored -> true;
            case IrOp.VfpCompare ignored -> true;
            case IrOp.VfpConvert ignored -> true;
            case IrOp.VfpLoad ignored -> true;
            case IrOp.VfpStore ignored -> true;
            case IrOp.VfpMultipleTransfer ignored -> true;
            // B22.2: a forma de 16 bits (`VMOV_half`) não tem emissão nativa — cai no interpretado
            // por `AsmFallbackPolicy.PER_OP` (caminho inerte hoje, nenhum preset tem `HALF_PRECISION_FP`).
            case IrOp.VfpCoreTransfer transfer -> !transfer.halfWidth();
            case IrOp.VfpCorePairTransfer ignored -> true;
            case IrOp.VfpSystemTransfer ignored -> true;
            // VMOV_64_sp/VCVT_fix (B9.5): sem emissao nativa ainda, cai no interpretado por
            // AsmFallbackPolicy.PER_OP -- mesma simplificacao aceita por varias tasks B8.x/B9.x.
            case IrOp.VfpCorePairTransferSingle ignored -> false;
            case IrOp.VfpConvertFixed ignored -> false;
            // MRS/MSR SYSm do perfil M (B7.4): interpretado, nunca nativo — mesmo padrão de toda op
            // de sistema nova (delega ao MProfileExceptionModel via IrSystemExecutor).
            case IrOp.MProfileSystemRegister ignored -> false;
            // BKPT (B7.5): depende do BkptDispatcher do host (semihosting) — mesmo padrão de
            // IrOp.Swi/IrOp.Coprocessor, mas sem emissão nativa ainda (op nova e rara).
            case IrOp.Breakpoint ignored -> false;
            // SMLAD/SMLSD/SMLALD/SMLSLD/SMMLA/SMMLS (B9.1): sem emissao nativa ainda, cai no
            // interpretado por AsmFallbackPolicy.PER_OP -- mesma simplificacao de VfpConvertFixed.
            case IrOp.DspDualMultiply ignored -> false;
            case IrOp.DspTopWordMultiply ignored -> false;
            // HVC (B9.8.2): sem emissao nativa ainda, cai no interpretado por AsmFallbackPolicy.PER_OP
            // -- mesma simplificacao de Breakpoint/DspDualMultiply (op nova e rara).
            case IrOp.Hvc ignored -> false;
            // SMC (B9.8.3): mesma simplificação de Hvc.
            case IrOp.Smc ignored -> false;
            // ERET (B9.8.4): mesma simplificação de Hvc/Smc.
            case IrOp.Eret ignored -> false;
            // MRS_BANK/MSR_BANK (B9.8.5): mesma simplificação de Hvc/Smc/Eret.
            case IrOp.MrsBank ignored -> false;
            case IrOp.MsrBank ignored -> false;
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
