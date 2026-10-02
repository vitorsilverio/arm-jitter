package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import dev.vitorsilverio.armjitter.ir.MveFpOp;
import dev.vitorsilverio.armjitter.ir.MveIntegerOp;
import dev.vitorsilverio.armjitter.ir.MveMoveOp;
import dev.vitorsilverio.armjitter.ir.MvePredicationOp;
import dev.vitorsilverio.armjitter.ir.MveReductionOp;
import dev.vitorsilverio.armjitter.ir.NeonCryptoOp;
import dev.vitorsilverio.armjitter.ir.NeonFpOp;
import dev.vitorsilverio.armjitter.ir.NeonIntegerOp;
import dev.vitorsilverio.armjitter.ir.NeonMoveOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
///
/// Regra geral: as ops são suportadas para qualquer condição — o {@code AsmBlockCompiler} emite um
/// guard {@code evalCond} por op, espelhando o interpretador. As exceções abaixo são por motivos
/// NÃO-condicionais:
/// <ul>
///   <li>{@link MemoryOp.Swap} — raro, mantém fallback.</li>
///   <li>{@link IntegerOp.Alu} com {@code dst=15} e {@code setFlags=true} — restaura SPSR.</li>
///   <li>BLX ({@link BranchOp.BranchExchange} com {@code link}, {@link BranchOp.ThumbBlSuffix} com {@code exchange}).</li>
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
            case IntegerOp.Alu alu -> supportsAlu(alu);
            // MLS (B3.1, subtractFromAccumulator=true): emitido nativamente desde a task B3.6
            // (ISUB no lugar do IADD no caminho MLA já existente).
            case IntegerOp.Multiply ignored -> true;
            // UMAAL (ARMv6, B1.2): acumulador duplo agora emitido nativamente (task B1.6).
            case IntegerOp.LongMultiply ignored -> true;
            // ARMv5TE emitidas nativamente (Mobiclip/SDK usam pesado). Só as formas com escrita
            // em PC (UNPREDICTABLE/troca de bloco) ficam no interpretado.
            case IntegerOp.Saturating s -> s.dst() != 15;
            // CRC32 (ARMv8-A, B14.3): sem emissor nativo ainda — decode+interpretado apenas
            // (a task explicitamente não inclui emissão nativa/Truffle).
            case IntegerOp.Crc32 ignored -> false;
            case IntegerOp.DspMultiply d -> d.dst() != 15 && !(d.op2() == 2 && d.rn() == 15);
            // Ops ARMv6 da B1.3 (paralelas, SEL, saturação, USAD): nativas desde a task B1.6.
            case IntegerOp.ParallelAlu ignored -> true;
            case IntegerOp.Sel ignored -> true;
            case IntegerOp.Saturate ignored -> true;
            case IntegerOp.AbsDiffSum ignored -> true;
            // Acessos exclusivos (B1.4): nativos desde a task B1.6 — o monitor de exclusividade
            // é checado/marcado por helper em AsmRuntimeHelpers, mesma ordem do interpretador.
            case MemoryOp.LoadExclusive ignored -> true;
            case MemoryOp.StoreExclusive ignored -> true;
            case MemoryOp.ClearExclusive ignored -> true;
            // LDRD para o par (first,second) escreve os dois via emitStoreRegister puro, sem o
            // tratamento de interworking que emitLoad/emitLoadLiteral dão a PC — então nenhum dos
            // dois pode ser PC num load. STRD só LÊ os registradores (sem troca de modo), então PC
            // como origem é seguro nativamente.
            case MemoryOp.DoubleTransfer d -> !d.load() || (d.first() != 15 && d.second() != 15);
            // `unprivileged` (LDRxT/STRxT, B9.9) precisa de AddressSpace#withUnprivilegedAccess
            // ao redor do acesso — sem equivalente no emissor nativo, cai no interpretado (mesma
            // simplificação já aplicada a outras variantes raras desta escada, ex. B9.8.x).
            case MemoryOp.Load l -> !l.unprivileged();   // offsets shifted-register agora emitidos nativamente
            case MemoryOp.Store s -> !s.unprivileged();
            case MemoryOp.LoadLiteral ignored -> true;
            case MemoryOp.MultipleTransfer ignored -> true;
            case BranchOp.Branch ignored -> true;
            case BranchOp.BranchExchange b -> !b.link(); // BLX -> interpretado
            case BranchOp.ThumbBlPrefix ignored -> true;
            case BranchOp.ThumbBlSuffix s -> !s.exchange(); // BLX -> interpretado
            case MemoryOp.Push ignored -> true;
            case MemoryOp.Pop ignored -> true;
            case SystemOp.PsrTransfer ignored -> true;
            case SystemOp.Swi ignored -> true;
            case SystemOp.Coprocessor ignored -> true;
            case SystemOp.CoprocessorDouble ignored -> true;
            case SystemOp.Undefined ignored -> true;
            // SWP/SWPB (C12.7): emitido nativamente desde a task C12.7 — via chamada ao
            // interpretado (IrOpInterop, mesmo mecanismo do fallback PER_OP) cercada de
            // flush/reload, mesmo argumento de PsrTransfer/Coprocessor acima: raro, mas não
            // precisa mais derrubar o BLOCO inteiro.
            case MemoryOp.Swap ignored -> true;
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
            // Instruções de sistema ARMv6 da B1.5 (CPS/SETEND/SRS/RFE/WFI): emitidas nativamente
            // desde a task C12.7 — via chamada ao interpretado (IrOpInterop) cercada de
            // flush/reload, mesmo argumento de PsrTransfer (mexem em CPSR/modo/banco, semântica
            // já escrita e testada no executor; emitir bytecode direto duplicaria a lógica de
            // troca de banco sem necessidade).
            case SystemOp.ChangeProcessorState ignored -> true;
            case SystemOp.SetEndianness ignored -> true;
            case SystemOp.StoreReturnState ignored -> true;
            case SystemOp.ReturnFromException ignored -> true;
            case SystemOp.WaitForInterrupt ignored -> true;
            // `MOVT` (Thumb-2, B2.2): emitido nativamente desde a task B3.6 (AND/OR direto,
            // preservando os 16 bits baixos existentes).
            case IntegerOp.MoveTop ignored -> true;
            // DMB/DSB/ISB (Thumb-2, B2.5): NOP observável (ver SystemOp.MemoryBarrier) — desde a task
            // B3.6 não emite nenhum bytecode além do Cycle/Fetch já emitidos separadamente no bloco.
            case SystemOp.MemoryBarrier ignored -> true;
            // IT block/branches Thumb-2 novos (B2.4): emitidas nativamente desde a task C12.7 —
            // via IrOpInterop cercado de flush/reload, mesmo mecanismo dos demais desta task.
            case SystemOp.SetItState ignored -> true;
            case BranchOp.TableBranch ignored -> true;
            case BranchOp.CompareBranchZero ignored -> true;
            // Inteiro ARMv7 (B3.1): emitidas nativamente desde a task B3.6 (PR1), bytecode direto
            // sem helper — ver `emitBitFieldExtract`/`emitBitFieldInsert`/`emitBitReverse`/`emitDivide`.
            case IntegerOp.BitFieldExtract ignored -> true;
            case IntegerOp.BitFieldInsert ignored -> true;
            case IntegerOp.BitReverse ignored -> true;
            case IntegerOp.Divide ignored -> true;
            // VFP (B3.4/B3.5): emitidas nativamente desde a task B3.6 (PR2) — cobertura completa,
            // sem exceção. VfpOp.Alu/VfpOp.MoveImmediate/VfpOp.Load/VfpOp.Store/VfpOp.CoreTransfer são bytecode
            // direto (caminho quente); VfpOp.Compare/VfpOp.Convert/VfpOp.MultipleTransfer/
            // VfpOp.CorePairTransfer/VfpOp.SystemTransfer chamam um helper estático em
            // AsmRuntimeHelpers (ver AsmBlockCompiler#emitVfpAlu e vizinhos).
            // NEON "three same" (B13.2): interpretado, como todo `Kind` vetorial novo desde B8.4/
            // B8.6 do lado A64 — a emissão nativa é task própria, depois que a escada B13 fechar.
            case NeonIntegerOp.ThreeSame ignored -> false;
            case NeonMoveOp.LoadStoreMultiple ignored -> false;
            case NeonMoveOp.LoadStoreSingle ignored -> false;
            case NeonMoveOp.LoadAllLanes ignored -> false;
            case NeonIntegerOp.Pairwise ignored -> false;
            case NeonFpOp.FpThreeSame ignored -> false;
            case NeonFpOp.FpPairwise ignored -> false;
            // NEON "2-reg shift by immediate" (B13.7): interpretado, como todo `Kind` vetorial.
            case NeonIntegerOp.ShiftImmediate ignored -> false;
            // NEON "2-reg-and-shift" estreitando/alargando + `VCVT` fixo↔float (B13.8): idem.
            case NeonIntegerOp.ShiftNarrowImmediate ignored -> false;
            case NeonIntegerOp.ShiftWidenImmediate ignored -> false;
            case NeonFpOp.ConvertFixedPoint ignored -> false;
            // NEON "1-reg-and-modified-immediate" (B13.9): idem.
            case NeonMoveOp.ModifiedImmediate ignored -> false;
            // NEON "three-reg-different-lengths" (B13.10): idem.
            case NeonIntegerOp.Widening ignored -> false;
            case NeonIntegerOp.Wide ignored -> false;
            case NeonIntegerOp.Narrow ignored -> false;
            // NEON "2-regs-plus-scalar" (B13.11): idem.
            case NeonIntegerOp.ThreeSameByElement ignored -> false;
            case NeonIntegerOp.WideningByElement ignored -> false;
            case NeonFpOp.FpThreeSameByElement ignored -> false;
            // NEON "two-register miscellaneous" `size==0b11` (B13.12): idem.
            case NeonIntegerOp.Unary ignored -> false;
            case NeonIntegerOp.NarrowUnary ignored -> false;
            case NeonFpOp.FpUnary ignored -> false;
            case NeonFpOp.FpConvertPrecision ignored -> false;
            // NEON `neon-shared` — `VCMLA`/`VCADD`/`VCMLA_scalar` (B13.17): idem.
            case NeonFpOp.Complex ignored -> false;
            case NeonFpOp.ComplexByElement ignored -> false;
            case NeonIntegerOp.DotProduct ignored -> false;
            case NeonIntegerOp.DotProductByElement ignored -> false;
            case NeonIntegerOp.MatrixMultiplyAccumulate ignored -> false;
            case NeonFpOp.FusedMultiplyAddLong ignored -> false;
            case NeonFpOp.FusedMultiplyAddLongByElement ignored -> false;
            case NeonFpOp.DotProductBFloat16 ignored -> false;
            case NeonFpOp.DotProductByElementBFloat16 ignored -> false;
            case NeonFpOp.MatrixMultiplyAccumulateBFloat16 ignored -> false;
            case NeonFpOp.FusedMultiplyAddLongBFloat16 ignored -> false;
            case NeonFpOp.FusedMultiplyAddLongByElementBFloat16 ignored -> false;
            case NeonMoveOp.SwapPermute ignored -> false;
            case NeonMoveOp.Extract ignored -> false;
            case NeonMoveOp.TableLookup ignored -> false;
            case NeonMoveOp.DuplicateScalar ignored -> false;
            // NEON "two-register miscellaneous" cripto, `size==0b11` (B13.15): idem.
            case NeonCryptoOp.Aes ignored -> false;
            case NeonCryptoOp.Sha ignored -> false;
            case NeonCryptoOp.ShaThree ignored -> false;
            // MAXNM/MINNM (B14.4, ArmFeature.ARMV8_FP): sem emissor nativo ainda ("Não inclui" da
            // task — decode + interpretado apenas, mesmo padrão de VfpOp.Select/Crc32/Nocp abaixo).
            // O resto de VfpOp.Alu (ADD/SUB/MUL/DIV/MLA/.../FNMS) segue nativo desde a B3.6.
            case VfpOp.Alu alu -> alu.op() != VfpOp.VfpOperation.MAXNM && alu.op() != VfpOp.VfpOperation.MINNM;
            case VfpOp.MoveImmediate ignored -> true;
            case VfpOp.Compare ignored -> true;
            case VfpOp.Convert ignored -> true;
            case VfpOp.Load ignored -> true;
            case VfpOp.Store ignored -> true;
            case VfpOp.MultipleTransfer ignored -> true;
            // B22.2: a forma de 16 bits (`VMOV_half`) não tem emissão nativa — cai no interpretado
            // por `AsmFallbackPolicy.PER_OP` (`VMOV_half`); B22.10: as formas de lane NEON (8/16 bits) também não têm emissão nativa.
            case VfpOp.CoreTransfer transfer -> !transfer.halfWidth() && !transfer.isLaneTransfer();
            case VfpOp.CorePairTransfer ignored -> true;
            case VfpOp.SystemTransfer ignored -> true;
            // VMOV_64_sp/VCVT_fix (B9.5): emitidas nativamente desde a task C12.7 — via
            // IrOpInterop cercado de flush/reload (mesmo mecanismo desta task inteira).
            case VfpOp.CorePairTransferSingle ignored -> true;
            case VfpOp.ConvertFixed ignored -> true;
            // VSEL (B14.4, ARMv8-A): sem emissão nativa nesta task ("Não inclui" — decode +
            // interpretado apenas, mesmo padrão de Crc32/Nocp/VfpOp.SysregMemoryTransfer acima).
            case VfpOp.Select ignored -> false;
            // VRINT/VCVT com modo explícito (B14.5, ARMv8-A): sem emissão nativa nesta task ("Não
            // inclui" — decode + interpretado apenas, mesmo padrão de VfpOp.Select acima).
            case VfpOp.Round ignored -> false;
            case VfpOp.ConvertRounded ignored -> false;
            // VMOVX/VINS (B14.6, ArmFeature.FP16_ARITHMETIC): sem emissão nativa nesta task ("Não
            // inclui" — decode + interpretado apenas, mesmo padrão de VfpOp.Select/VfpOp.Round acima).
            case VfpOp.MoveHalfLane ignored -> false;
            // B14.6b (`_hp`): mesmo padrão — decode + interpretado apenas, "Não inclui" da task.
            case VfpOp.AluHalf ignored -> false;
            case VfpOp.MoveImmediateHalf ignored -> false;
            case VfpOp.CompareHalf ignored -> false;
            case VfpOp.SelectHalf ignored -> false;
            case VfpOp.RoundHalf ignored -> false;
            case VfpOp.ConvertRoundedHalf ignored -> false;
            case VfpOp.ConvertFixedHalf ignored -> false;
            case VfpOp.LoadHalf ignored -> false;
            case VfpOp.StoreHalf ignored -> false;
            // B22.7: `VCVTB`/`VCVTT`/`VJCVT` — decode + interpretado apenas (mesmo padrão de `_hp` acima).
            case VfpOp.ConvertHalfPrecision ignored -> false;
            case VfpOp.JavascriptConvert ignored -> false;
            // MRS/MSR SYSm do perfil M (B7.4): emitido nativamente desde a task C12.7 — via
            // IrOpInterop (delega ao MProfileExceptionModel via IrSystemExecutor, sem duplicar).
            case SystemOp.MProfileSystemRegister ignored -> true;
            // BKPT (B7.5): emitido nativamente desde a task C12.7 — via IrOpInterop, mesmo
            // mecanismo de SystemOp.Swi/SystemOp.Coprocessor (que usam helper dedicado) mas sem duplicar
            // o BkptDispatcher no lado ASM.
            case SystemOp.Breakpoint ignored -> true;
            // SMLAD/SMLSD/SMLALD/SMLSLD/SMMLA/SMMLS (B9.1): emitidas nativamente desde a task
            // C12.7 — via IrOpInterop.
            case IntegerOp.DspDualMultiply ignored -> true;
            case IntegerOp.DspTopWordMultiply ignored -> true;
            // HVC (B9.8.2): emitido nativamente desde a task C12.7 — via IrOpInterop cercado de
            // flush/reload (a exceção de guest é lançada dentro do interpretado, exatamente como
            // PsrTransfer/Coprocessor já fazem para outras trocas de estado do core).
            case SystemOp.Hvc ignored -> true;
            // SMC (B9.8.3): mesmo mecanismo de Hvc.
            case SystemOp.Smc ignored -> true;
            // ERET (B9.8.4): mesmo mecanismo de Hvc/Smc.
            case SystemOp.Eret ignored -> true;
            // MRS_BANK/MSR_BANK (B9.8.5): mesmo mecanismo de Hvc/Smc/Eret.
            case SystemOp.MrsBank ignored -> true;
            case SystemOp.MsrBank ignored -> true;
            // NOCP/NOCP_8_1 (B15.2): sem emissão nativa nesta task ("Não inclui" — decode +
            // interpretado apenas, mesmo padrão do resto da trilha B) — bloco inteiro cai no
            // fallback interpretado (WHOLE_BLOCK) ou por op (PER_OP), mesmo caminho de NEON acima.
            case SystemOp.Nocp ignored -> false;
            // VLDR_sysreg/VSTR_sysreg (B15.3): sem emissão nativa nesta task ("Não inclui" — decode
            // + interpretado apenas, mesmo padrão de Nocp acima).
            case VfpOp.SysregMemoryTransfer ignored -> false;
            // SG/BXNS/BLXNS (B15.4, Security Extension): sem emissão nativa nesta task ("Não
            // inclui" — decode + interpretado apenas, mesmo padrão de Nocp/VfpOp.SysregMemoryTransfer
            // acima).
            case SystemOp.SecureGateway ignored -> false;
            case BranchOp.SecureBranchExchange ignored -> false;
            // VLLDM_VLSTM/VSCCLRM (B15.5): sem emissão nativa nesta task ("Não inclui" — decode +
            // interpretado apenas, mesmo padrão de Nocp/VfpOp.SysregMemoryTransfer/SecureGateway acima).
            case VfpOp.VlldmVlstm ignored -> false;
            case VfpOp.Vscclrm ignored -> false;
            // LOOP_START/LOOP_END (DLS/WLS/LE, B15.6): sem emissão nativa nesta task ("Não inclui"
            // — decode + interpretado apenas, mesmo padrão de Nocp/VfpOp.SysregMemoryTransfer/
            // SecureGateway/VlldmVlstm acima).
            case BranchOp.LoopStart ignored -> false;
            case BranchOp.LoopEnd ignored -> false;
            // VPST/VPNOT/VPSEL + o avanço pós-instrução (B16.2, MVE/Helium): sem emissão nativa
            // nesta task ("Não inclui" — decode + interpretado apenas, mesmo padrão de
            // Nocp/VfpOp.SysregMemoryTransfer/SecureGateway/VlldmVlstm/LoopStart acima).
            case MvePredicationOp.Vpst ignored -> false;
            case MvePredicationOp.Vpnot ignored -> false;
            case MvePredicationOp.Vpsel ignored -> false;
            // VCTP/LCTP/CLRM (B16.15): sem emissão nativa nesta task (decode + interpretado apenas).
            case MvePredicationOp.Vctp ignored -> false;
            case MvePredicationOp.LoopClearTailPredication ignored -> false;
            case IntegerOp.ClearMultiple ignored -> false;
            // MVE "long shift" sobre GPR (B16.16): sem emissão nativa nesta task (decode + interpretado).
            case MveIntegerOp.WideShift ignored -> false;
            case MvePredicationOp.AdvanceVpt ignored -> false;
            case MvePredicationOp.VprTransfer ignored -> false;
            // VLDR_VSTR (B16.3, MVE/Helium): sem emissão nativa nesta task ("Não inclui" — decode +
            // interpretado apenas, mesmo padrão de Vpst/Vpnot/Vpsel/AdvanceVpt/VprTransfer acima).
            case MveMoveOp.LoadStore ignored -> false;
            // VLDSTB_H/VLDSTB_W/VLDSTH_W (B16.4, MVE/Helium): mesmo padrão acima, sem emissão
            // nativa nesta task.
            case MveMoveOp.WideningLoadStore ignored -> false;
            // Gather/scatter, VLD2/VLD4/VST2/VST4, VIDUP/VDDUP/VIWDUP/VDWDUP e o avanço de ECI
            // (B16.5, MVE/Helium): mesmo padrão acima, sem emissão nativa nesta task.
            case MveMoveOp.GatherScatterOffset ignored -> false;
            case MveMoveOp.GatherScatterImmediate ignored -> false;
            case MveMoveOp.InterleavedLoadStore ignored -> false;
            case MveMoveOp.IncrementDup ignored -> false;
            case MveMoveOp.WrappingIncrementDup ignored -> false;
            case MvePredicationOp.AdvanceEci ignored -> false;
            // Vector 2-op inteiro, alargante, carry e soma complexa (B16.6, MVE/Helium): mesmo
            // padrão acima, sem emissão nativa nesta task.
            case MveIntegerOp.Vector2Op ignored -> false;
            case MveIntegerOp.Vector2OpWidening ignored -> false;
            case MveIntegerOp.VectorCarry ignored -> false;
            case MveIntegerOp.VectorComplexAdd ignored -> false;
            // VMAXA/VMINA, VMAXNMA/VMINNMA, VSHLL T2, VMOVN*/VQMOVN*/VQMOVUN* e a conversão
            // binary16<->binary32 "bottom"/"top" (B16.7, MVE/Helium): mesmo padrão acima, sem
            // emissão nativa nesta task.
            case MveIntegerOp.VectorAbsAccumulate ignored -> false;
            case MveFpOp.VectorFpAbsAccumulate ignored -> false;
            case MveIntegerOp.VectorShiftWidenInterleaved ignored -> false;
            case MveIntegerOp.VectorNarrowInterleaved ignored -> false;
            case MveFpOp.VectorFpConvertPrecision ignored -> false;
            case MveFpOp.VectorFpComplexMultiply ignored -> false;
            case MveIntegerOp.VectorDualMultiplyAddHigh ignored -> false;
            case MveIntegerOp.VectorDoublingWideningMultiply ignored -> false;
            case MveFpOp.VectorFpTwoOp ignored -> false;
            case MveFpOp.VectorFpComplexAdd ignored -> false;
            case MveFpOp.VectorFpComplexMultiplyAccumulate ignored -> false;
            case MvePredicationOp.VectorCompare ignored -> false;
            case MvePredicationOp.VectorCompareScalar ignored -> false;
            // Operações escalares (B16.9, MVE/Helium): mesmo padrão acima, sem emissão nativa
            // nesta task.
            case MveIntegerOp.VectorScalar ignored -> false;
            case MveIntegerOp.VectorScalarWidening ignored -> false;
            case MveFpOp.VectorFpScalar ignored -> false;
            case MveFpOp.VectorFpScalarFma ignored -> false;
            case MveIntegerOp.VectorScalarSpecial ignored -> false;
            // Deslocamentos por imediato, shift-and-insert e VSHLL T1 (B16.10, MVE/Helium): mesmo
            // padrão acima, sem emissão nativa nesta task.
            case MveIntegerOp.VectorShiftImmediate ignored -> false;
            case MveIntegerOp.VectorShiftWidenImmediateInterleaved ignored -> false;
            // Deslocamentos estreitantes (só b/h) e VSHLC (B16.11, MVE/Helium): mesmo padrão acima,
            // sem emissão nativa nesta task.
            case MveIntegerOp.VectorShiftNarrowImmediateInterleaved ignored -> false;
            case MveIntegerOp.VectorShiftLeftCarry ignored -> false;
            // VCVT (int<->fp, ponto fixo, modo de arredondamento) e VRINT* (B16.12, MVE/Helium):
            // mesmo padrão acima, sem emissão nativa nesta task.
            case MveFpOp.VectorFpConvert ignored -> false;
            case MveFpOp.VectorFpConvertFixed ignored -> false;
            // 1-op misc, VDUP, movimentos lane<->GPR, reduções e imediato modificado (B16.13a,
            // MVE/Helium): mesmo padrão acima, sem emissão nativa nesta task.
            case MveIntegerOp.VectorUnary ignored -> false;
            case MveFpOp.VectorFpUnary ignored -> false;
            case MveMoveOp.VectorDup ignored -> false;
            case MveMoveOp.MoveLanesGpr ignored -> false;
            case MveReductionOp.VectorAddAcrossVector ignored -> false;
            case MveReductionOp.VectorAddAcrossVectorLong ignored -> false;
            case MveReductionOp.VectorAbsoluteDifferenceAccumulate ignored -> false;
            case MveMoveOp.VectorModifiedImmediate ignored -> false;
            case MveReductionOp.VectorDualAccumulate ignored -> false;
            case MveReductionOp.VectorDualAccumulateLong ignored -> false;
            case MveReductionOp.VectorRoundingDualAccumulateHigh ignored -> false;
            case MveReductionOp.VectorMinMaxAcrossVector ignored -> false;
            case MveReductionOp.VectorFpMinMaxAcrossVector ignored -> false;
        };
    }

    private static boolean supportsAlu(IntegerOp.Alu alu) {
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
