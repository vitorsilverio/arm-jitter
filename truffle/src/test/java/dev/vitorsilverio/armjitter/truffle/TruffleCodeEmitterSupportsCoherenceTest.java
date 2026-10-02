package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoAesOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
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
import dev.vitorsilverio.armjitter.ir.ParallelAluOp;
import dev.vitorsilverio.armjitter.ir.ParallelAluVariant;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.ir.VfpOp;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A10.1 — trava o contrato factory↔supports: {@link IrOpNodeFactory#supports} e
/// {@link IrOpNodeFactory#create} nunca podem divergir. Sem este teste a correção da A10.1 se
/// reintroduz sozinha quando uma task futura acrescentar um `Kind` só num dos dois lugares.
///
/// Para cada `IrOp.Kind` (144 desde B16.8), monta um `IrOp` representativo (só o `kind()` importa —
/// `create` nunca inspeciona outro campo para escolher o nó) e verifica:
/// <ul>
///   <li>{@code supports(op) == true}  ⇒ {@code create(op, executor)} NÃO lança;</li>
///   <li>{@code supports(op) == false} ⇒ {@code create(op, executor)} lança
///       {@link IllegalStateException} (a rede de segurança G8 do `default`).</li>
/// </ul>
class TruffleCodeEmitterSupportsCoherenceTest {
    private final IrBlockExecutor executor = new IrBlockExecutor(ArmArchitecture.ARMV7A);

    @Test
    void everyKindHasCoherentSupportsAndCreate() {
        List<Integer> kinds = allKindConstants();
        assertEquals(189, kinds.size(), "IrOp.Kind deve ter 189 constantes contíguas");

        for (int kind : kinds) {
            IrOp op = sampleOp(kind);
            assertEquals(kind, op.kind(), "sampleOp devolveu um op de kind errado");

            if (IrOpNodeFactory.supports(op)) {
                assertDoesNotThrow(() -> IrOpNodeFactory.create(op, executor),
                        "supports=true mas create lançou para kind=" + kind);
            } else {
                assertThrows(IllegalStateException.class, () -> IrOpNodeFactory.create(op, executor),
                        "supports=false mas create NÃO lançou para kind=" + kind);
            }
        }
    }

    @Test
    void theUncoveredKindsAreExactlyTheKnownList() {
        List<Integer> uncovered = new ArrayList<>();
        for (int kind : allKindConstants()) {
            if (!IrOpNodeFactory.supports(sampleOp(kind))) {
                uncovered.add(kind);
            }
        }
        // Os Kinds sem nó Truffle (NEON). Eram 33 na A10.1; B13.7 acrescentou
        // NEON_SHIFT_IMMEDIATE; B13.8 acrescentou NEON_SHIFT_NARROW_IMMEDIATE,
        // NEON_SHIFT_WIDEN_IMMEDIATE, NEON_CONVERT_FIXED_POINT; B13.9 acrescentou
        // NEON_MODIFIED_IMMEDIATE; B13.10 acrescentou NEON_WIDENING, NEON_WIDE, NEON_NARROW; B13.11
        // acrescentou NEON_THREE_SAME_BY_ELEMENT, NEON_WIDENING_BY_ELEMENT,
        // NEON_FP_THREE_SAME_BY_ELEMENT (NEON também não tem nó Truffle); A10.6 cobriu
        // DSP_DUAL_MULTIPLY/DSP_TOP_WORD_MULTIPLY (−2); A10.4 cobriu BIT_FIELD_EXTRACT,
        // BIT_FIELD_INSERT, BIT_REVERSE, DIVIDE (−4); A10.3 cobriu os 14 Kind de VFP/coprocessador
        // duplo/sysreg do perfil M (−14); A10.5 cobriu HVC/SMC/ERET/MRS_BANK/MSR_BANK/BREAKPOINT (−6);
        // B13.12 acrescentou NEON_UNARY, NEON_NARROW_UNARY, NEON_FP_UNARY (+3). B13.17 acrescentou
        // NEON_COMPLEX, NEON_COMPLEX_BY_ELEMENT (+2). B13.18 acrescentou NEON_DOT_PRODUCT,
        // NEON_DOT_PRODUCT_BY_ELEMENT (+2). B13.14 acrescentou NEON_SWAP_PERMUTE, NEON_EXTRACT,
        // NEON_TABLE_LOOKUP, NEON_DUPLICATE_SCALAR (+4, NEON também não tem nó Truffle). B13.15
        // acrescentou NEON_CRYPTO_AES, NEON_CRYPTO_SHA (+2, idem). B13.13 acrescentou
        // NEON_FP_CONVERT_PRECISION (+1, idem). B13.19 acrescentou NEON_MATRIX_MULTIPLY_ACCUMULATE
        // (+1, idem). B13.20 acrescentou NEON_FUSED_MULTIPLY_ADD_LONG,
        // NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT (+2, idem — faltava nesta lista/asserção desde
        // 2026-09-09, achado PRÉ-EXISTENTE desta sessão B13.21, corrigido junto: reproduzido num
        // checkout limpo ANTES de qualquer mudança da B13.21, mesma falha). B13.21 acrescentou
        // NEON_DOT_PRODUCT_BFLOAT16, NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16,
        // NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16, NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16,
        // NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16 (+5, idem). B15.2 acrescentou NOCP (+1
        // — "Não inclui" explícito da task: decode+interpretado apenas, sem Truffle, mesmo padrão
        // do resto da trilha B — dimensão 3 do roadmap fica para o épico A10 tratar depois). B15.3
        // acrescentou VFP_SYSREG_MEMORY_TRANSFER (+1 — mesmo "Não inclui" da B15.2: decode +
        // interpretado apenas). B15.4 acrescentou SECURE_GATEWAY, SECURE_BRANCH_EXCHANGE (+2 —
        // mesmo "Não inclui" da B15.2/B15.3: decode + interpretado apenas, dimensão 3 do roadmap
        // fica para o épico A10 tratar depois). B15.5 acrescentou VLLDM_VLSTM, VSCCLRM (+2 — mesmo
        // "Não inclui" da B15.2/B15.3/B15.4). B16.2 acrescentou ADVANCE_VPT, VPST, VPNOT, VPSEL,
        // VPR_TRANSFER (+5 — mesmo "Não inclui": decode + interpretado apenas, MVE/Helium fica
        // para o épico A10 tratar depois, dimensão 3 do roadmap). B16.3 acrescentou
        // MVE_LOAD_STORE (+1, mesmo "Não inclui" de B16.2). B16.4 acrescentou
        // MVE_WIDENING_LOAD_STORE (+1, mesmo "Não inclui"). B16.5 acrescentou
        // MVE_GATHER_SCATTER_OFFSET, MVE_GATHER_SCATTER_IMMEDIATE, MVE_INTERLEAVED_LOAD_STORE,
        // MVE_INCREMENT_DUP, MVE_WRAPPING_INCREMENT_DUP, ADVANCE_ECI (+6, mesmo "Não inclui"). B16.6
        // acrescentou MVE_VECTOR_2OP, MVE_VECTOR_2OP_WIDENING, MVE_VECTOR_CARRY,
        // MVE_VECTOR_COMPLEX_ADD (+4, mesmo "Não inclui" — faltava nesta lista/asserção desde
        // 2026-09-16, achado PRÉ-EXISTENTE desta sessão B16.7, corrigido junto). B16.7 acrescentou
        // MVE_VECTOR_ABS_ACCUMULATE, MVE_VECTOR_FP_ABS_ACCUMULATE,
        // MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED, MVE_VECTOR_NARROW_INTERLEAVED,
        // MVE_VECTOR_FP_CONVERT_PRECISION (+5, mesmo "Não inclui"). B16.7 sub-família 2 acrescentou
        // MVE_VECTOR_FP_COMPLEX_MULTIPLY, MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH,
        // MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY (+3, mesmo "Não inclui"). B16.7 sub-família 3
        // acrescentou MVE_VECTOR_FP_TWO_OP, MVE_VECTOR_FP_COMPLEX_ADD,
        // MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE (+3, mesmo "Não inclui"). B16.8 acrescentou
        // MVE_VECTOR_COMPARE, MVE_VECTOR_COMPARE_SCALAR (+2, mesmo "Não inclui": decode +
        // interpretado apenas, MVE/Helium fica para o épico A10 tratar depois). B16.9 acrescentou
        // MVE_VECTOR_SCALAR, MVE_VECTOR_SCALAR_WIDENING, MVE_VECTOR_FP_SCALAR,
        // MVE_VECTOR_FP_SCALAR_FMA, MVE_VECTOR_SCALAR_SPECIAL (+5, mesmo "Não inclui"). B16.10
        // acrescentou MVE_VECTOR_SHIFT_IMMEDIATE, MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED (+2,
        // mesmo "Não inclui"). B16.11 acrescentou MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED,
        // MVE_VECTOR_SHIFT_LEFT_CARRY (+2, mesmo "Não inclui"). B16.12 acrescentou
        // MVE_VECTOR_FP_CONVERT, MVE_VECTOR_FP_CONVERT_FIXED (+2, mesmo "Não inclui"). B16.13a
        // acrescentou MVE_VECTOR_UNARY, MVE_VECTOR_FP_UNARY, MVE_VECTOR_DUP, MVE_MOVE_LANES_GPR,
        // MVE_VECTOR_ADD_ACROSS_VECTOR, MVE_VECTOR_ADD_ACROSS_VECTOR_LONG,
        // MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE, MVE_VECTOR_MODIFIED_IMMEDIATE (+8, mesmo
        // "Não inclui"). B16.13b acrescentou MVE_VECTOR_DUAL_ACCUMULATE,
        // MVE_VECTOR_DUAL_ACCUMULATE_LONG, MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH,
        // MVE_VECTOR_MIN_MAX_ACROSS_VECTOR, MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR (+5, mesmo
        // "Não inclui"). B14.3 acrescentou CRC32 (+1 — mesmo "Não inclui": a task explicita
        // decode+interpretado apenas, emissão nativa/Truffle fica para trabalho futuro). B14.4
        // acrescentou VFP_SELECT (+1, `VSEL`, mesmo "Não inclui": decode + interpretado apenas).
        // B14.5 acrescentou VFP_ROUND, VFP_CONVERT_ROUNDED (+2, `VRINT{A,N,P,M}`/
        // `VCVT{A,N,P,M}{S,U}`, mesmo "Não inclui": decode + interpretado apenas). B14.6
        // acrescentou VFP_MOVE_HALF_LANE (+1, `VMOVX`/`VINS`, mesmo "Não inclui"). B14.6b
        // acrescentou VFP_ALU_HALF, VFP_MOVE_IMMEDIATE_HALF, VFP_COMPARE_HALF, VFP_SELECT_HALF,
        // VFP_ROUND_HALF, VFP_CONVERT_ROUNDED_HALF, VFP_CONVERT_FIXED_HALF, VFP_LOAD_HALF,
        // VFP_STORE_HALF (+9, aritmética `_hp`, mesmo "Não inclui": decode + interpretado apenas).
        // B13.23 acrescentou NEON_CRYPTO_SHA_THREE_REGISTER (+1, idem B13.15 — NEON também não tem
        // nó Truffle). B22.7 acrescentou VFP_CONVERT_HALF_PRECISION, VFP_JAVASCRIPT_CONVERT (+2,
        // `VCVTB`/`VCVTT`/`VJCVT`, mesmo "Não inclui": decode + interpretado apenas). B16.15
        // acrescentou LOOP_CLEAR_TAIL_PREDICATION, VCTP, CLEAR_MULTIPLE (+3, idem: decode +
        // interpretado apenas).
        assertEquals(123, uncovered.size(), "Kinds descobertos: " + uncovered);
        assertTrue(uncovered.containsAll(List.of(
                        IrOp.Kind.NOCP,
                        IrOp.Kind.VFP_SYSREG_MEMORY_TRANSFER,
                        IrOp.Kind.SECURE_GATEWAY,
                        IrOp.Kind.SECURE_BRANCH_EXCHANGE,
                        IrOp.Kind.VLLDM_VLSTM,
                        IrOp.Kind.VSCCLRM,
                        IrOp.Kind.LOOP_START,
                        IrOp.Kind.LOOP_END,
                        IrOp.Kind.ADVANCE_VPT,
                        IrOp.Kind.VPST,
                        IrOp.Kind.VPNOT,
                        IrOp.Kind.VPSEL,
                        IrOp.Kind.VPR_TRANSFER,
                        IrOp.Kind.MVE_LOAD_STORE,
                        IrOp.Kind.MVE_WIDENING_LOAD_STORE,
                        IrOp.Kind.MVE_GATHER_SCATTER_OFFSET,
                        IrOp.Kind.MVE_GATHER_SCATTER_IMMEDIATE,
                        IrOp.Kind.MVE_INTERLEAVED_LOAD_STORE,
                        IrOp.Kind.MVE_INCREMENT_DUP,
                        IrOp.Kind.MVE_WRAPPING_INCREMENT_DUP,
                        IrOp.Kind.ADVANCE_ECI,
                        IrOp.Kind.NEON_FP_CONVERT_PRECISION,
                        IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE,
                        IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG,
                        IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT,
                        IrOp.Kind.NEON_DOT_PRODUCT_BFLOAT16,
                        IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16,
                        IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16,
                        IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16,
                        IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16,
                        IrOp.Kind.NEON_THREE_SAME,
                        IrOp.Kind.NEON_LOAD_STORE_MULTIPLE, IrOp.Kind.NEON_LOAD_STORE_SINGLE,
                        IrOp.Kind.NEON_LOAD_ALL_LANES, IrOp.Kind.NEON_PAIRWISE, IrOp.Kind.NEON_FP_THREE_SAME,
                        IrOp.Kind.NEON_FP_PAIRWISE, IrOp.Kind.NEON_SHIFT_IMMEDIATE,
                        IrOp.Kind.NEON_SHIFT_NARROW_IMMEDIATE, IrOp.Kind.NEON_SHIFT_WIDEN_IMMEDIATE,
                        IrOp.Kind.NEON_CONVERT_FIXED_POINT, IrOp.Kind.NEON_MODIFIED_IMMEDIATE,
                        IrOp.Kind.NEON_WIDENING, IrOp.Kind.NEON_WIDE, IrOp.Kind.NEON_NARROW,
                        IrOp.Kind.NEON_THREE_SAME_BY_ELEMENT, IrOp.Kind.NEON_WIDENING_BY_ELEMENT,
                        IrOp.Kind.NEON_FP_THREE_SAME_BY_ELEMENT, IrOp.Kind.NEON_UNARY,
                        IrOp.Kind.NEON_NARROW_UNARY, IrOp.Kind.NEON_FP_UNARY,
                        IrOp.Kind.NEON_COMPLEX, IrOp.Kind.NEON_COMPLEX_BY_ELEMENT,
                        IrOp.Kind.NEON_DOT_PRODUCT, IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT,
                        IrOp.Kind.NEON_SWAP_PERMUTE, IrOp.Kind.NEON_EXTRACT,
                        IrOp.Kind.NEON_TABLE_LOOKUP, IrOp.Kind.NEON_DUPLICATE_SCALAR,
                        IrOp.Kind.NEON_CRYPTO_AES, IrOp.Kind.NEON_CRYPTO_SHA,
                        IrOp.Kind.NEON_CRYPTO_SHA_THREE_REGISTER,
                        IrOp.Kind.MVE_VECTOR_2OP, IrOp.Kind.MVE_VECTOR_2OP_WIDENING,
                        IrOp.Kind.MVE_VECTOR_CARRY, IrOp.Kind.MVE_VECTOR_COMPLEX_ADD,
                        IrOp.Kind.MVE_VECTOR_ABS_ACCUMULATE, IrOp.Kind.MVE_VECTOR_FP_ABS_ACCUMULATE,
                        IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED, IrOp.Kind.MVE_VECTOR_NARROW_INTERLEAVED,
                        IrOp.Kind.MVE_VECTOR_FP_CONVERT_PRECISION,
                        IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY, IrOp.Kind.MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH,
                        IrOp.Kind.MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY,
                        IrOp.Kind.MVE_VECTOR_FP_TWO_OP, IrOp.Kind.MVE_VECTOR_FP_COMPLEX_ADD,
                        IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE,
                        IrOp.Kind.MVE_VECTOR_COMPARE, IrOp.Kind.MVE_VECTOR_COMPARE_SCALAR,
                        IrOp.Kind.MVE_VECTOR_SCALAR, IrOp.Kind.MVE_VECTOR_SCALAR_WIDENING,
                        IrOp.Kind.MVE_VECTOR_FP_SCALAR, IrOp.Kind.MVE_VECTOR_FP_SCALAR_FMA,
                        IrOp.Kind.MVE_VECTOR_SCALAR_SPECIAL,
                        IrOp.Kind.MVE_VECTOR_SHIFT_IMMEDIATE,
                        IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED,
                        IrOp.Kind.MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED,
                        IrOp.Kind.MVE_VECTOR_SHIFT_LEFT_CARRY,
                        IrOp.Kind.MVE_VECTOR_FP_CONVERT, IrOp.Kind.MVE_VECTOR_FP_CONVERT_FIXED,
                        IrOp.Kind.MVE_VECTOR_UNARY, IrOp.Kind.MVE_VECTOR_FP_UNARY, IrOp.Kind.MVE_VECTOR_DUP,
                        IrOp.Kind.MVE_MOVE_LANES_GPR, IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR,
                        IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR_LONG,
                        IrOp.Kind.MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE,
                        IrOp.Kind.MVE_VECTOR_MODIFIED_IMMEDIATE,
                        IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE,
                        IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE_LONG,
                        IrOp.Kind.MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH,
                        IrOp.Kind.MVE_VECTOR_MIN_MAX_ACROSS_VECTOR,
                        IrOp.Kind.MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR,
                        IrOp.Kind.CRC32, IrOp.Kind.VFP_SELECT,
                        IrOp.Kind.VFP_ROUND, IrOp.Kind.VFP_CONVERT_ROUNDED, IrOp.Kind.VFP_MOVE_HALF_LANE,
                        IrOp.Kind.VFP_ALU_HALF, IrOp.Kind.VFP_MOVE_IMMEDIATE_HALF, IrOp.Kind.VFP_COMPARE_HALF,
                        IrOp.Kind.VFP_SELECT_HALF, IrOp.Kind.VFP_ROUND_HALF, IrOp.Kind.VFP_CONVERT_ROUNDED_HALF,
                        IrOp.Kind.VFP_CONVERT_FIXED_HALF, IrOp.Kind.VFP_LOAD_HALF, IrOp.Kind.VFP_STORE_HALF,
                        IrOp.Kind.VFP_CONVERT_HALF_PRECISION, IrOp.Kind.VFP_JAVASCRIPT_CONVERT,
                        IrOp.Kind.LOOP_CLEAR_TAIL_PREDICATION, IrOp.Kind.VCTP, IrOp.Kind.CLEAR_MULTIPLE,
                        IrOp.Kind.MVE_WIDE_SHIFT)),
                "lista dos Kinds descobertos mudou: " + uncovered);
    }

    private static List<Integer> allKindConstants() {
        List<Integer> kinds = new ArrayList<>();
        for (Field field : IrOp.Kind.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                try {
                    kinds.add(field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return kinds;
    }

    /// Um `IrOp` por `Kind`. Valores dos campos são irrelevantes — `IrOpNodeFactory` só olha
    /// `op.kind()` (e `op.condition()`, sempre {@link Condition#AL} aqui) — mas os construtores
    /// dos records validam aridade/tipo, então são preenchidos com valores plausíveis.
    private static IrOp sampleOp(int kind) {
        Condition c = Condition.AL;
        IrOperand imm = new IrOperand.Immediate(0);
        return switch (kind) {
            case IrOp.Kind.ALU -> new IntegerOp.Alu(IrOpCode.MOV, 0, -1, -1, imm, false, c);
            case IrOp.Kind.MULTIPLY -> new IntegerOp.Multiply(0, 1, -1, 2, -1, -1, -1, false, false, false, c);
            case IrOp.Kind.LONG_MULTIPLY -> new IntegerOp.LongMultiply(0, 1, 2, -1, 3, -1, -1, -1, false, false, false, c);
            case IrOp.Kind.SATURATING -> new IntegerOp.Saturating(0, 1, 2, 0, c);
            case IrOp.Kind.CRC32 -> new IntegerOp.Crc32(0, 1, 2, 8, false, c);
            case IrOp.Kind.DSP_MULTIPLY -> new IntegerOp.DspMultiply(0, 1, 2, 3, 0, 0, 0, c);
            case IrOp.Kind.PSR_TRANSFER -> new SystemOp.PsrTransfer(true, false, 0, -1, 0, false, 0, c);
            case IrOp.Kind.LOAD -> new MemoryOp.Load(0, 1, -1, imm, 4, false, false, false, false, c);
            case IrOp.Kind.STORE -> new MemoryOp.Store(0, -1, 1, -1, imm, 4, false, false, false, c);
            case IrOp.Kind.DOUBLE_TRANSFER -> new MemoryOp.DoubleTransfer(true, 0, 1, 2, -1, imm, false, false, c);
            case IrOp.Kind.SWAP -> new MemoryOp.Swap(0, 1, -1, 2, -1, 4, c);
            case IrOp.Kind.LOAD_LITERAL -> new MemoryOp.LoadLiteral(0, 0, c);
            case IrOp.Kind.MULTIPLE_TRANSFER ->
                    new MemoryOp.MultipleTransfer(true, 0, 1, false, -1, false, BlockTransferMode.IA, false, c);
            case IrOp.Kind.BRANCH -> new BranchOp.Branch(0, 0, false, c, InstructionSet.ARM);
            case IrOp.Kind.BRANCH_EXCHANGE -> new BranchOp.BranchExchange(0, -1, false, 0, c);
            case IrOp.Kind.THUMB_BL_PREFIX -> new BranchOp.ThumbBlPrefix(0, 0, c);
            case IrOp.Kind.THUMB_BL_SUFFIX -> new BranchOp.ThumbBlSuffix(0, 0, false, c);
            case IrOp.Kind.PUSH -> new MemoryOp.Push(0, false, c);
            case IrOp.Kind.POP -> new MemoryOp.Pop(0, false, c);
            case IrOp.Kind.SWI -> new SystemOp.Swi(0, c);
            case IrOp.Kind.COPROCESSOR -> new SystemOp.Coprocessor(true, 15, 0, 0, 0, 0, 0, 0, c);
            case IrOp.Kind.UNDEFINED -> new SystemOp.Undefined(0, c);
            case IrOp.Kind.CYCLE -> new IrOp.Cycle(0);
            case IrOp.Kind.FETCH -> new IrOp.Fetch(0, 4);
            case IrOp.Kind.PARALLEL_ALU ->
                    new IntegerOp.ParallelAlu(ParallelAluOp.ADD16, ParallelAluVariant.SIGNED, 0, 1, 2, c);
            case IrOp.Kind.SEL -> new IntegerOp.Sel(0, 1, 2, c);
            case IrOp.Kind.SATURATE -> new IntegerOp.Saturate(0, 8, false, false, imm, c);
            case IrOp.Kind.ABS_DIFF_SUM -> new IntegerOp.AbsDiffSum(0, 1, 2, -1, c);
            case IrOp.Kind.LOAD_EXCLUSIVE -> new MemoryOp.LoadExclusive(0, 1, 0, 4, c);
            case IrOp.Kind.STORE_EXCLUSIVE -> new MemoryOp.StoreExclusive(0, 1, 2, 0, 4, c);
            case IrOp.Kind.CLEAR_EXCLUSIVE -> new MemoryOp.ClearExclusive(c);
            case IrOp.Kind.CHANGE_PROCESSOR_STATE ->
                    new SystemOp.ChangeProcessorState(false, 0, false, false, false, false, false, c);
            case IrOp.Kind.SET_ENDIANNESS -> new SystemOp.SetEndianness(false, c);
            case IrOp.Kind.STORE_RETURN_STATE -> new SystemOp.StoreReturnState(0x13, BlockTransferMode.DB, false, 0, c);
            case IrOp.Kind.RETURN_FROM_EXCEPTION -> new SystemOp.ReturnFromException(0, BlockTransferMode.IA, false, 0, c);
            case IrOp.Kind.WAIT_FOR_INTERRUPT -> new SystemOp.WaitForInterrupt(c);
            case IrOp.Kind.MOVE_TOP -> new IntegerOp.MoveTop(0, 0, c);
            case IrOp.Kind.MEMORY_BARRIER -> new SystemOp.MemoryBarrier(c);
            case IrOp.Kind.SET_IT_STATE -> new SystemOp.SetItState(0, c);
            case IrOp.Kind.TABLE_BRANCH -> new BranchOp.TableBranch(0, -1, 1, -1, 0, false, c);
            case IrOp.Kind.COMPARE_BRANCH_ZERO -> new BranchOp.CompareBranchZero(0, 0, false, c);
            case IrOp.Kind.BIT_FIELD_EXTRACT -> new IntegerOp.BitFieldExtract(0, 1, 0, 8, false, c);
            case IrOp.Kind.BIT_FIELD_INSERT -> new IntegerOp.BitFieldInsert(0, 1, 0, 8, c);
            case IrOp.Kind.BIT_REVERSE -> new IntegerOp.BitReverse(0, 1, c);
            case IrOp.Kind.DIVIDE -> new IntegerOp.Divide(0, 1, 2, false, c);
            case IrOp.Kind.VFP_ALU -> new VfpOp.Alu(VfpOp.VfpOperation.ADD, false, 0, 1, 2, c);
            case IrOp.Kind.VFP_MOVE_IMMEDIATE -> new VfpOp.MoveImmediate(false, 0, 0L, c);
            case IrOp.Kind.VFP_COMPARE -> new VfpOp.Compare(false, false, false, 0, 1, c);
            case IrOp.Kind.VFP_SELECT -> new VfpOp.Select(false, 0, 1, 2, Condition.EQ, c);
            case IrOp.Kind.VFP_ROUND ->
                    new VfpOp.Round(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, false, 0, 1, c);
            case IrOp.Kind.VFP_CONVERT_ROUNDED ->
                    new VfpOp.ConvertRounded(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, true, false, 0, 1, c);
            case IrOp.Kind.VFP_MOVE_HALF_LANE -> new VfpOp.MoveHalfLane(false, 0, 1, c);
            case IrOp.Kind.VFP_ALU_HALF -> new VfpOp.AluHalf(VfpOp.VfpOperation.ADD, 0, 1, 2, c);
            case IrOp.Kind.VFP_MOVE_IMMEDIATE_HALF -> new VfpOp.MoveImmediateHalf(0, 0, c);
            case IrOp.Kind.VFP_COMPARE_HALF -> new VfpOp.CompareHalf(false, false, 0, 1, c);
            case IrOp.Kind.VFP_SELECT_HALF -> new VfpOp.SelectHalf(0, 1, 2, Condition.EQ, c);
            case IrOp.Kind.VFP_ROUND_HALF ->
                    new VfpOp.RoundHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, 0, 1, c);
            case IrOp.Kind.VFP_CONVERT_ROUNDED_HALF ->
                    new VfpOp.ConvertRoundedHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, true, 0, 1, c);
            case IrOp.Kind.VFP_CONVERT_FIXED_HALF -> new VfpOp.ConvertFixedHalf(true, false, false, 8, 0, c);
            case IrOp.Kind.VFP_LOAD_HALF -> new VfpOp.LoadHalf(0, 1, -1, 0, c);
            case IrOp.Kind.VFP_STORE_HALF -> new VfpOp.StoreHalf(0, 1, -1, 0, c);
            case IrOp.Kind.VFP_CONVERT_HALF_PRECISION ->
                    new VfpOp.ConvertHalfPrecision(VfpOp.HalfPrecisionConversion.F16_TO_F32, false, 0, 1, c);
            case IrOp.Kind.VFP_JAVASCRIPT_CONVERT -> new VfpOp.JavascriptConvert(0, 1, c);
            case IrOp.Kind.VFP_CONVERT -> new VfpOp.Convert(VfpOp.VfpConversion.F32_TO_F64, 0, 1, c);
            case IrOp.Kind.VFP_LOAD -> new VfpOp.Load(false, 0, 1, -1, 0, c);
            case IrOp.Kind.VFP_STORE -> new VfpOp.Store(false, 0, 1, -1, 0, c);
            case IrOp.Kind.VFP_MULTIPLE_TRANSFER ->
                    new VfpOp.MultipleTransfer(true, false, 0, -1, 0, 1, false, false, c);
            case IrOp.Kind.VFP_CORE_TRANSFER -> new VfpOp.CoreTransfer(true, 0, 0, false, c);
            case IrOp.Kind.VFP_CORE_PAIR_TRANSFER -> new VfpOp.CorePairTransfer(true, 0, 1, 0, c);
            case IrOp.Kind.VFP_SYSTEM_TRANSFER -> new VfpOp.SystemTransfer(true, 0, c);
            case IrOp.Kind.M_PROFILE_SYSTEM_REGISTER -> new SystemOp.MProfileSystemRegister(true, 0, 0, c);
            case IrOp.Kind.BREAKPOINT -> new SystemOp.Breakpoint(0);
            case IrOp.Kind.COPROCESSOR_DOUBLE -> new SystemOp.CoprocessorDouble(true, 15, 0, 0, 0, 1, 0, c);
            case IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE -> new VfpOp.CorePairTransferSingle(true, 0, 1, 0, c);
            case IrOp.Kind.VFP_CONVERT_FIXED -> new VfpOp.ConvertFixed(false, false, false, true, 0, 0, c);
            case IrOp.Kind.DSP_DUAL_MULTIPLY -> new IntegerOp.DspDualMultiply(0, 1, 2, 15, false, false, false, c);
            case IrOp.Kind.DSP_TOP_WORD_MULTIPLY -> new IntegerOp.DspTopWordMultiply(0, 1, 2, 15, false, false, c);
            case IrOp.Kind.HVC -> new SystemOp.Hvc(0, c);
            case IrOp.Kind.SMC -> new SystemOp.Smc(0, c);
            case IrOp.Kind.ERET -> new SystemOp.Eret(c);
            case IrOp.Kind.MRS_BANK -> new SystemOp.MrsBank(0, CpuMode.FIQ, 8, false, false, c);
            case IrOp.Kind.MSR_BANK -> new SystemOp.MsrBank(0, CpuMode.FIQ, 8, false, false, c);
            case IrOp.Kind.NEON_THREE_SAME -> new NeonIntegerOp.ThreeSame(AdvSimdThreeSameOp.ADD, false, 0, 0, 1, 2);
            case IrOp.Kind.NEON_LOAD_STORE_MULTIPLE -> new NeonMoveOp.LoadStoreMultiple(true, 0, 1, 15, 2, 1, 1, 1);
            case IrOp.Kind.NEON_LOAD_STORE_SINGLE -> new NeonMoveOp.LoadStoreSingle(true, 0, 1, 15, 2, 1, 1, 0);
            case IrOp.Kind.NEON_LOAD_ALL_LANES -> new NeonMoveOp.LoadAllLanes(0, 1, 15, 2, 1, 1, false);
            case IrOp.Kind.NEON_PAIRWISE -> new NeonIntegerOp.Pairwise(AdvSimdPairwiseOp.ADD, 2, 0, 1, 2);
            case IrOp.Kind.NEON_FP_THREE_SAME -> new NeonFpOp.FpThreeSame(AdvSimdFpThreeSameOp.ADD, false, 2, 0, 1, 2);
            case IrOp.Kind.NEON_FP_PAIRWISE -> new NeonFpOp.FpPairwise(AdvSimdFpPairwiseOp.ADD, 2, 0, 1, 2);
            case IrOp.Kind.NEON_SHIFT_IMMEDIATE ->
                    new NeonIntegerOp.ShiftImmediate(AdvSimdShiftImmediateOp.SSHR, false, 0, 1, 0, 1);
            case IrOp.Kind.NEON_SHIFT_NARROW_IMMEDIATE ->
                    new NeonIntegerOp.ShiftNarrowImmediate(AdvSimdShiftNarrowOp.SHRN, 0, 1, 0, 2);
            case IrOp.Kind.NEON_SHIFT_WIDEN_IMMEDIATE ->
                    new NeonIntegerOp.ShiftWidenImmediate(AdvSimdShiftWidenOp.SSHLL, 0, 0, 0, 2);
            case IrOp.Kind.NEON_CONVERT_FIXED_POINT ->
                    new NeonFpOp.ConvertFixedPoint(false, 2, 1, true, true, 0, 1);
            case IrOp.Kind.NEON_MODIFIED_IMMEDIATE ->
                    new NeonMoveOp.ModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0xFFL, 0);
            case IrOp.Kind.NEON_WIDENING -> new NeonIntegerOp.Widening(AdvSimdWideningOp.SADDL, 0, 0, 1, 2);
            case IrOp.Kind.NEON_WIDE -> new NeonIntegerOp.Wide(AdvSimdWideOp.SADDW, 0, 0, 2, 1);
            case IrOp.Kind.NEON_NARROW -> new NeonIntegerOp.Narrow(AdvSimdNarrowOp.ADDHN, 0, 0, 2, 4);
            case IrOp.Kind.NEON_THREE_SAME_BY_ELEMENT ->
                    new NeonIntegerOp.ThreeSameByElement(AdvSimdThreeSameOp.MUL, 1, false, 0, 1, 2, 3);
            case IrOp.Kind.NEON_WIDENING_BY_ELEMENT ->
                    new NeonIntegerOp.WideningByElement(AdvSimdWideningOp.SMULL, 1, 0, 1, 2, 3);
            case IrOp.Kind.NEON_FP_THREE_SAME_BY_ELEMENT ->
                    new NeonFpOp.FpThreeSameByElement(AdvSimdFpThreeSameOp.MUL, false, 2, 0, 1, 2, 1);
            case IrOp.Kind.NEON_UNARY -> new NeonIntegerOp.Unary(AdvSimdUnaryOp.ABS, false, 0, 0, 1);
            case IrOp.Kind.NEON_NARROW_UNARY -> new NeonIntegerOp.NarrowUnary(AdvSimdNarrowUnaryOp.XTN, 0, 0, 2);
            case IrOp.Kind.NEON_FP_UNARY -> new NeonFpOp.FpUnary(AdvSimdFpUnaryOp.ABS, false, 0, 1);
            case IrOp.Kind.NEON_COMPLEX -> new NeonFpOp.Complex(true, 90, false, 2, 0, 1, 2);
            case IrOp.Kind.NEON_COMPLEX_BY_ELEMENT -> new NeonFpOp.ComplexByElement(90, false, 2, 0, 1, 2, 0);
            case IrOp.Kind.NEON_DOT_PRODUCT -> new NeonIntegerOp.DotProduct(true, true, false, 0, 1, 2);
            case IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT ->
                    new NeonIntegerOp.DotProductByElement(true, true, false, 0, 1, 2, 0);
            case IrOp.Kind.NEON_SWAP_PERMUTE -> new NeonMoveOp.SwapPermute(AdvSimdSwapPermuteOp.TRN, false, 0, 0, 1);
            case IrOp.Kind.NEON_EXTRACT -> new NeonMoveOp.Extract(false, 3, 0, 1, 2);
            case IrOp.Kind.NEON_TABLE_LOOKUP -> new NeonMoveOp.TableLookup(false, 0, 0, 1, 2);
            case IrOp.Kind.NEON_DUPLICATE_SCALAR -> new NeonMoveOp.DuplicateScalar(0, 3, false, 0, 1);
            case IrOp.Kind.NEON_CRYPTO_AES -> new NeonCryptoOp.Aes(AdvSimdCryptoAesOp.AESE, 0, 1);
            case IrOp.Kind.NEON_CRYPTO_SHA -> new NeonCryptoOp.Sha(AdvSimdCryptoShaOp.SHA1H, 0, 1);
            case IrOp.Kind.NEON_CRYPTO_SHA_THREE_REGISTER ->
                    new NeonCryptoOp.ShaThree(AdvSimdCryptoShaThreeRegisterOp.SHA1C, 0, 2, 4);
            case IrOp.Kind.NEON_FP_CONVERT_PRECISION ->
                    new NeonFpOp.FpConvertPrecision(AdvSimdFpConvertPrecisionOp.NARROW_F16, 0, 2);
            case IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE ->
                    new NeonIntegerOp.MatrixMultiplyAccumulate(true, true, 0, 2, 4);
            case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG ->
                    new NeonFpOp.FusedMultiplyAddLong(false, false, 0, 1, 2);
            case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT ->
                    new NeonFpOp.FusedMultiplyAddLongByElement(false, false, 0, 1, 2, 0);
            case IrOp.Kind.NEON_DOT_PRODUCT_BFLOAT16 -> new NeonFpOp.DotProductBFloat16(false, 0, 1, 2);
            case IrOp.Kind.NEON_DOT_PRODUCT_BY_ELEMENT_BFLOAT16 ->
                    new NeonFpOp.DotProductByElementBFloat16(false, 0, 1, 2, 0);
            case IrOp.Kind.NEON_MATRIX_MULTIPLY_ACCUMULATE_BFLOAT16 ->
                    new NeonFpOp.MatrixMultiplyAccumulateBFloat16(0, 2, 4);
            case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BFLOAT16 ->
                    new NeonFpOp.FusedMultiplyAddLongBFloat16(false, 0, 2, 4);
            case IrOp.Kind.NEON_FUSED_MULTIPLY_ADD_LONG_BY_ELEMENT_BFLOAT16 ->
                    new NeonFpOp.FusedMultiplyAddLongByElementBFloat16(false, 0, 2, 4, 0);
            case IrOp.Kind.NOCP -> new SystemOp.Nocp(10, c);
            case IrOp.Kind.VFP_SYSREG_MEMORY_TRANSFER -> new VfpOp.SysregMemoryTransfer(true, 0, 8, false, false, c);
            case IrOp.Kind.SECURE_GATEWAY -> new SystemOp.SecureGateway(c);
            case IrOp.Kind.SECURE_BRANCH_EXCHANGE -> new BranchOp.SecureBranchExchange(0, -1, false, 0, c);
            case IrOp.Kind.VLLDM_VLSTM -> new VfpOp.VlldmVlstm(c);
            case IrOp.Kind.VSCCLRM -> new VfpOp.Vscclrm(true, 0, 1, c);
            case IrOp.Kind.LOOP_START -> new BranchOp.LoopStart(0, 0, true, c);
            case IrOp.Kind.LOOP_END -> new BranchOp.LoopEnd(0, false, c);
            case IrOp.Kind.ADVANCE_VPT -> new MvePredicationOp.AdvanceVpt(c);
            case IrOp.Kind.VPST -> new MvePredicationOp.Vpst(0b1010, c);
            case IrOp.Kind.VPNOT -> new MvePredicationOp.Vpnot(c);
            case IrOp.Kind.VPSEL -> new MvePredicationOp.Vpsel(0, 1, 2, c);
            case IrOp.Kind.VPR_TRANSFER -> new MvePredicationOp.VprTransfer(true, 0, c);
            case IrOp.Kind.LOOP_CLEAR_TAIL_PREDICATION -> new MvePredicationOp.LoopClearTailPredication(c);
            case IrOp.Kind.VCTP -> new MvePredicationOp.Vctp(0, 0, c);
            case IrOp.Kind.CLEAR_MULTIPLE -> new IntegerOp.ClearMultiple(1, c);
            case IrOp.Kind.MVE_WIDE_SHIFT ->
                    new MveIntegerOp.WideShift(MveIntegerOp.WideShiftOperation.LSLL_RI, 1, -1, 0, 1, c);
            case IrOp.Kind.MVE_LOAD_STORE -> new MveMoveOp.LoadStore(0, 1, 0, true, false, false, c);
            case IrOp.Kind.MVE_WIDENING_LOAD_STORE ->
                    new MveMoveOp.WideningLoadStore(0, 1, 0, 0, 1, true, true, false, false, c);
            case IrOp.Kind.MVE_GATHER_SCATTER_OFFSET ->
                    new MveMoveOp.GatherScatterOffset(0, 1, 2, 0, 1, true, false, true, c);
            case IrOp.Kind.MVE_GATHER_SCATTER_IMMEDIATE ->
                    new MveMoveOp.GatherScatterImmediate(0, 1, 4, 2, true, true, c);
            case IrOp.Kind.MVE_INTERLEAVED_LOAD_STORE ->
                    new MveMoveOp.InterleavedLoadStore(0, 1, 2, 0, 0, true, false, c);
            case IrOp.Kind.MVE_INCREMENT_DUP -> new MveMoveOp.IncrementDup(0, 2, 0, 1, c);
            case IrOp.Kind.MVE_WRAPPING_INCREMENT_DUP ->
                    new MveMoveOp.WrappingIncrementDup(0, 2, 3, 0, 1, false, c);
            case IrOp.Kind.ADVANCE_ECI -> new MvePredicationOp.AdvanceEci(c);
            case IrOp.Kind.MVE_VECTOR_2OP -> new MveIntegerOp.Vector2Op(AdvSimdThreeSameOp.ADD, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_2OP_WIDENING ->
                    new MveIntegerOp.Vector2OpWidening(AdvSimdWideningOp.SMULL, 0, false, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_CARRY -> new MveIntegerOp.VectorCarry(true, false, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_COMPLEX_ADD -> new MveIntegerOp.VectorComplexAdd(true, false, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_ABS_ACCUMULATE -> new MveIntegerOp.VectorAbsAccumulate(true, 0, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_ABS_ACCUMULATE -> new MveFpOp.VectorFpAbsAccumulate(true, 2, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_INTERLEAVED ->
                    new MveIntegerOp.VectorShiftWidenInterleaved(true, 0, false, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_NARROW_INTERLEAVED ->
                    new MveIntegerOp.VectorNarrowInterleaved(AdvSimdNarrowUnaryOp.XTN, 0, false, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_CONVERT_PRECISION ->
                    new MveFpOp.VectorFpConvertPrecision(false, false, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY -> new MveFpOp.VectorFpComplexMultiply(0, 2, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_DUAL_MULTIPLY_ADD_HIGH ->
                    new MveIntegerOp.VectorDualMultiplyAddHigh(true, false, false, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_DOUBLING_WIDENING_MULTIPLY ->
                    new MveIntegerOp.VectorDoublingWideningMultiply(1, false, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_FP_TWO_OP -> new MveFpOp.VectorFpTwoOp(AdvSimdFpThreeSameOp.ADD, 2, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_ADD -> new MveFpOp.VectorFpComplexAdd(true, 2, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_FP_COMPLEX_MULTIPLY_ACCUMULATE ->
                    new MveFpOp.VectorFpComplexMultiplyAccumulate(0, 2, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_COMPARE -> new MvePredicationOp.VectorCompare(
                    dev.vitorsilverio.armjitter.advsimd.MveCompareCondition.EQ, false, 2, 0, 1, 0, c);
            case IrOp.Kind.MVE_VECTOR_COMPARE_SCALAR -> new MvePredicationOp.VectorCompareScalar(
                    dev.vitorsilverio.armjitter.advsimd.MveCompareCondition.EQ, false, 2, 0, 1, 0, c);
            case IrOp.Kind.MVE_VECTOR_SCALAR -> new MveIntegerOp.VectorScalar(AdvSimdThreeSameOp.ADD, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_SCALAR_WIDENING ->
                    new MveIntegerOp.VectorScalarWidening(1, false, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_FP_SCALAR ->
                    new MveFpOp.VectorFpScalar(AdvSimdFpThreeSameOp.ADD, 2, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_FP_SCALAR_FMA -> new MveFpOp.VectorFpScalarFma(false, 2, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_SCALAR_SPECIAL -> new MveIntegerOp.VectorScalarSpecial(
                    MveIntegerOp.VectorScalarSpecial.SpecialOp.VBRSR, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_SHIFT_IMMEDIATE ->
                    new MveIntegerOp.VectorShiftImmediate(AdvSimdShiftImmediateOp.SHL, 0, 0, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_SHIFT_WIDEN_IMMEDIATE_INTERLEAVED ->
                    new MveIntegerOp.VectorShiftWidenImmediateInterleaved(true, 0, 0, false, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_SHIFT_NARROW_IMMEDIATE_INTERLEAVED -> new MveIntegerOp.VectorShiftNarrowImmediateInterleaved(
                    AdvSimdShiftNarrowOp.SHRN, 0, 1, false, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_SHIFT_LEFT_CARRY -> new MveIntegerOp.VectorShiftLeftCarry(1, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_CONVERT ->
                    new MveFpOp.VectorFpConvert(AdvSimdFpUnaryOp.SCVTF, 2, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_CONVERT_FIXED ->
                    new MveFpOp.VectorFpConvertFixed(true, true, 2, 16, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_UNARY -> new MveIntegerOp.VectorUnary(AdvSimdUnaryOp.ABS, 0, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_UNARY -> new MveFpOp.VectorFpUnary(AdvSimdFpUnaryOp.ABS, 2, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_DUP -> new MveMoveOp.VectorDup(0, 0, 1, c);
            case IrOp.Kind.MVE_MOVE_LANES_GPR -> new MveMoveOp.MoveLanesGpr(true, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR -> new MveReductionOp.VectorAddAcrossVector(false, false, 0, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_ADD_ACROSS_VECTOR_LONG ->
                    new MveReductionOp.VectorAddAcrossVectorLong(false, false, 0, 1, 0, c);
            case IrOp.Kind.MVE_VECTOR_ABSOLUTE_DIFFERENCE_ACCUMULATE ->
                    new MveReductionOp.VectorAbsoluteDifferenceAccumulate(false, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_MODIFIED_IMMEDIATE ->
                    new MveMoveOp.VectorModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, 0L, 0, c);
            case IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE ->
                    new MveReductionOp.VectorDualAccumulate(false, false, false, true, 0, 0, 1, 2, c);
            case IrOp.Kind.MVE_VECTOR_DUAL_ACCUMULATE_LONG ->
                    new MveReductionOp.VectorDualAccumulateLong(false, false, false, true, 1, 0, 1, 3, 2, c);
            case IrOp.Kind.MVE_VECTOR_ROUNDING_DUAL_ACCUMULATE_HIGH ->
                    new MveReductionOp.VectorRoundingDualAccumulateHigh(false, false, false, true, 0, 1, 3, 2, c);
            case IrOp.Kind.MVE_VECTOR_MIN_MAX_ACROSS_VECTOR ->
                    new MveReductionOp.VectorMinMaxAcrossVector(true, false, false, 2, 0, 1, c);
            case IrOp.Kind.MVE_VECTOR_FP_MIN_MAX_ACROSS_VECTOR ->
                    new MveReductionOp.VectorFpMinMaxAcrossVector(true, false, 2, 0, 1, c);
            default -> throw new AssertionError("kind sem sampleOp: " + kind);
        };
    }
}
