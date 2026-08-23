package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.ParallelAluOp;
import dev.vitorsilverio.armjitter.ir.ParallelAluVariant;

/// Executa operações ALU e multiplicação da IR interpretada.
///
/// Classe e métodos públicos (task A6) para que o módulo `truffle/` possa despachar DIRETO
/// para cada método de categoria, sem passar pelo `switch` exaustivo de
/// {@link IrBlockExecutor#executeOp} — ver `AluOpNode`/`MultiplyOpNode` no módulo `truffle/`.
/// Nenhuma semântica muda aqui: só a visibilidade (G1 intacto).
public final class IrAluExecutor {
    private final IrExecutionSupport support;

    IrAluExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean execute(ArmCore core, IrOp.Alu alu) {
        if (!core.cpsr().evalCond(alu.condition())) {
            return false;
        }

        int right = support.operand(core, alu.src2());
        switch (alu.opcode()) {
            case IrOpCode.MOV -> {
                boolean carry = alu.setFlags() && support.operandCarryOut(core, alu.src2());
                if (support.writeAluDestination(core, alu.dst(), right, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setLogicFlags(core, right, carry);
                }
            }
            case IrOpCode.ADD -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = left + right;
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setAddFlags(core, left, right, result);
                }
            }
            case IrOpCode.ADC -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int carry = core.cpsr().carry() ? 1 : 0;
                int result = left + right + carry;
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setAdcFlags(core, left, right, carry, result);
                }
            }
            case IrOpCode.SUB, IrOpCode.RSB, IrOpCode.CMP, IrOpCode.SBC, IrOpCode.RSC, IrOpCode.NEG -> {
                int source = IrOpCode.NEG.equals(alu.opcode()) ? 0 : support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                boolean reverse = IrOpCode.RSB.equals(alu.opcode()) || IrOpCode.RSC.equals(alu.opcode());
                int left = reverse ? right : source;
                int subRight = reverse ? source : right;
                int borrow = (IrOpCode.SBC.equals(alu.opcode()) || IrOpCode.RSC.equals(alu.opcode()))
                        && !core.cpsr().carry() ? 1 : 0;
                int subtrahend = subRight + borrow;
                int result = left - subtrahend;
                if (!IrOpCode.CMP.equals(alu.opcode())) {
                    if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                        return true;
                    }
                }
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        support.restoreCpsrFromCurrentSpsr(core);
                    } else {
                        support.setSbcFlags(core, left, subRight, borrow, result);
                    }
                }
            }
            case IrOpCode.CMN -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = left + right;
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        support.restoreCpsrFromCurrentSpsr(core);
                    } else {
                        support.setAddFlags(core, left, right, result);
                    }
                }
            }
            case IrOpCode.AND, IrOpCode.EOR, IrOpCode.ORR, IrOpCode.BIC, IrOpCode.ORN, IrOpCode.TST, IrOpCode.TEQ -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = switch (alu.opcode()) {
                    case IrOpCode.AND -> left & right;
                    case IrOpCode.EOR -> left ^ right;
                    case IrOpCode.ORR -> left | right;
                    case IrOpCode.BIC -> left & ~right;
                    case IrOpCode.ORN -> left | ~right;
                    case IrOpCode.TST -> left & right;
                    case IrOpCode.TEQ -> left ^ right;
                    default -> throw new IllegalStateException("Unexpected logic opcode: " + alu.opcode());
                };
                boolean carry = alu.setFlags() && support.operandCarryOut(core, alu.src2());
                if (!IrOpCode.TST.equals(alu.opcode()) && !IrOpCode.TEQ.equals(alu.opcode())) {
                    if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                        return true;
                    }
                }
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        support.restoreCpsrFromCurrentSpsr(core);
                    } else {
                        support.setLogicFlags(core, result, carry);
                    }
                }
            }
            case IrOpCode.MVN -> {
                int result = ~right;
                boolean carry = alu.setFlags() && support.operandCarryOut(core, alu.src2());
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setLogicFlags(core, result, carry);
                }
            }
            case IrOpCode.CLZ -> core.setRegister(alu.dst(), Integer.numberOfLeadingZeros(
                    support.registerValue(core, alu.src1(), alu.src1ValueOverride())));
            // ARMv6 SXT*/UXT*: `right` já vem rotacionado pelo operando; o acumulador é src1
            // (forma sem acumulador: src1ValueOverride=0). Nunca escrevem flags.
            case IrOpCode.SXTB, IrOpCode.SXTH, IrOpCode.UXTB, IrOpCode.UXTH -> {
                int accumulator = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int extended = switch (alu.opcode()) {
                    case IrOpCode.SXTB -> (byte) right;
                    case IrOpCode.SXTH -> (short) right;
                    case IrOpCode.UXTB -> right & 0xFF;
                    default -> right & 0xFFFF; // UXTH
                };
                core.setRegister(alu.dst(), accumulator + extended);
            }
            // SXTB16/UXTB16 estendem os DOIS bytes pares (bits 23:16 e 7:0), cada halfword
            // acumulada de forma independente (módulo 2^16, sem carry entre as metades).
            case IrOpCode.SXTB16, IrOpCode.UXTB16 -> {
                int accumulator = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                boolean signedExtend = alu.opcode() == IrOpCode.SXTB16;
                int lowByte = signedExtend ? (byte) right : right & 0xFF;
                int highByte = signedExtend ? (byte) (right >>> 16) : (right >>> 16) & 0xFF;
                int lowHalf = (accumulator + lowByte) & 0xFFFF;
                int highHalf = ((accumulator >>> 16) + highByte) & 0xFFFF;
                core.setRegister(alu.dst(), (highHalf << 16) | lowHalf);
            }
            // ARMv6 byte-reverse. REVSH inverte apenas o halfword BAIXO e estende o sinal.
            case IrOpCode.REV, IrOpCode.REV16, IrOpCode.REVSH -> {
                int value = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = switch (alu.opcode()) {
                    case IrOpCode.REV -> Integer.reverseBytes(value);
                    case IrOpCode.REV16 -> ((value & 0xFF00_FF00) >>> 8) | ((value & 0x00FF_00FF) << 8);
                    default -> (short) (((value & 0xFF) << 8) | ((value >>> 8) & 0xFF)); // REVSH
                };
                core.setRegister(alu.dst(), result);
            }
            // PKHBT/PKHTB (ARMv6): `right` já vem shiftado pelo operando; monta o resultado com
            // um halfword de cada fonte. Nunca escrevem flags.
            case IrOpCode.PKHBT, IrOpCode.PKHTB -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = alu.opcode() == IrOpCode.PKHBT
                        ? (left & 0xFFFF) | (right & 0xFFFF_0000)
                        : (left & 0xFFFF_0000) | (right & 0xFFFF);
                core.setRegister(alu.dst(), result);
            }
            case IrOpCode.LSL, IrOpCode.LSR, IrOpCode.ASR, IrOpCode.ROR -> {
                int value = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int amount = right & 0xFF;
                int result = switch (alu.opcode()) {
                    case IrOpCode.LSL -> amount >= 32 ? 0 : value << amount;
                    case IrOpCode.LSR -> amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
                    case IrOpCode.ASR -> amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
                    case IrOpCode.ROR -> amount == 0 ? value : Integer.rotateRight(value, amount & 31);
                    default -> throw new IllegalStateException("Unexpected shift opcode: " + alu.opcode());
                };
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setLogicFlags(core, result, support.shiftCarryOut(core, value, alu.opcode(), amount,
                            alu.src2() instanceof IrOperand.Immediate));
                }
            }
            default -> throw new UnsupportedOperationException("Unknown IR ALU opcode: " + alu.opcode());
        }
        return false;
    }

    /// `MOVT` (Thumb-2, B2.2): escreve `immediate16` na metade ALTA (bits 31:16) de `dst`,
    /// preservando a metade baixa — não é um padrão `Alu` comum (nenhuma outra op ARM escreve só
    /// metade do registrador), por isso vive em seu próprio {@link IrOp}. Nunca escreve flags.
    public void executeMoveTop(ArmCore core, IrOp.MoveTop moveTop) {
        if (!core.cpsr().evalCond(moveTop.condition())) {
            return;
        }
        int current = core.register(moveTop.dst());
        core.setRegister(moveTop.dst(), (current & 0xFFFF) | (moveTop.immediate16() << 16));
    }

    public void executeMultiply(ArmCore core, IrOp.Multiply multiply) {
        if (!core.cpsr().evalCond(multiply.condition())) {
            return;
        }
        int product = support.registerValue(core, multiply.rm(), multiply.rmValueOverride())
                * support.registerValue(core, multiply.rs(), multiply.rsValueOverride());
        int result = product;
        if (multiply.accumulate()) {
            int accumulator = support.registerValue(core, multiply.rn(), multiply.rnValueOverride());
            // MLS (ARMv6T2+, B3.1): Rd = Ra - Rn*Rm, em vez de Rd = Ra + Rn*Rm de MUL/MLA.
            result = multiply.subtractFromAccumulator() ? accumulator - product : accumulator + product;
        }
        core.setRegister(multiply.dst(), result);
        if (multiply.setFlags()) {
            support.setLogicFlags(core, result);
        }
    }

    public void executeLongMultiply(ArmCore core, IrOp.LongMultiply multiply) {
        if (!core.cpsr().evalCond(multiply.condition())) {
            return;
        }
        long result;
        if (multiply.signed()) {
            result = (long) support.registerValue(core, multiply.rm(), multiply.rmValueOverride())
                    * (long) support.registerValue(core, multiply.rs(), multiply.rsValueOverride());
        } else {
            result = Integer.toUnsignedLong(support.registerValue(core, multiply.rm(), multiply.rmValueOverride()))
                    * Integer.toUnsignedLong(support.registerValue(core, multiply.rs(), multiply.rsValueOverride()));
        }
        if (multiply.accumulate()) {
            long current = (Integer.toUnsignedLong(support.registerValue(core, multiply.dstHigh(), multiply.dstHighValueOverride())) << 32)
                    | Integer.toUnsignedLong(support.registerValue(core, multiply.dstLow(), multiply.dstLowValueOverride()));
            result += current;
        }
        if (multiply.accumulateDouble()) {
            // UMAAL: RdLo e RdHi entram como DUAS parcelas de 32 bits sem sinal — não como um par
            // 64-bit. O caso máximo 0xFFFFFFFF² + 2×0xFFFFFFFF = 2^64−1 nunca estoura.
            result += Integer.toUnsignedLong(support.registerValue(core, multiply.dstLow(), multiply.dstLowValueOverride()))
                    + Integer.toUnsignedLong(support.registerValue(core, multiply.dstHigh(), multiply.dstHighValueOverride()));
        }
        core.setRegister(multiply.dstLow(), (int) result);
        core.setRegister(multiply.dstHigh(), (int) (result >>> 32));
        if (multiply.setFlags()) {
            core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
        }
    }

    /// Soma/subtração saturada ARMv5TE (QADD/QSUB/QDADD/QDSUB), sempre clampada para 32 bits com
    /// sinal, setando o flag Q sticky em qualquer saturação.
    public void executeSaturating(ArmCore core, IrOp.Saturating op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rm = core.register(op.rm());
        int rn = core.register(op.rn());
        boolean[] q = {false};
        int result = switch (op.op()) {
            case 0 -> clamp((long) rm + rn, q);                 // QADD:  sat(Rm + Rn)
            case 1 -> clamp((long) rm - rn, q);                 // QSUB:  sat(Rm - Rn)
            case 2 -> clamp((long) rm + clamp(2L * rn, q), q);  // QDADD: sat(Rm + sat(2*Rn))
            default -> clamp((long) rm - clamp(2L * rn, q), q); // QDSUB: sat(Rm - sat(2*Rn))
        };
        core.setRegister(op.dst(), result);
        if (q[0]) {
            core.cpsr().setSaturation(true); // sticky: only ever set here
        }
    }

    /// Multiplicações DSP ARMv5TE: produtos 16x16 (e 32x16 word) com sinal, com acumulador
    /// opcional. SMLA/SMLAW setam o flag Q sticky se o acumulador estourar; SMUL/SMULW/SMLAL não.
    public void executeDspMultiply(ArmCore core, IrOp.DspMultiply op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rm = core.register(op.rm());
        int rs = core.register(op.rs());
        int rsHalf = half(rs, op.y());
        switch (op.op2()) {
            case 0 -> { // SMLAxy: (Rm.x * Rs.y) + Rn
                long sum = (long) (half(rm, op.x()) * rsHalf) + core.register(op.rn());
                core.setRegister(op.dst(), (int) sum);
                if (sum != (int) sum) {
                    core.cpsr().setSaturation(true);
                }
            }
            case 1 -> { // SMLAWy (x=0) / SMULWy (x=1): (Rm * Rs.y) >> 16, opcionalmente + Rn
                int product = (int) (((long) rm * rsHalf) >> 16);
                if (op.x() == 0) {
                    long sum = (long) product + core.register(op.rn());
                    core.setRegister(op.dst(), (int) sum);
                    if (sum != (int) sum) {
                        core.cpsr().setSaturation(true);
                    }
                } else {
                    core.setRegister(op.dst(), product);
                }
            }
            case 2 -> { // SMLALxy: {RdHi:RdLo} += Rm.x * Rs.y (64 bits, sem Q)
                long acc = ((long) core.register(op.dst()) << 32)
                        | (core.register(op.rn()) & 0xFFFF_FFFFL);
                acc += (long) half(rm, op.x()) * rsHalf;
                core.setRegister(op.rn(), (int) acc);          // RdLo
                core.setRegister(op.dst(), (int) (acc >>> 32)); // RdHi
            }
            default -> // SMULxy: Rm.x * Rs.y (sem acumulador, sem Q)
                    core.setRegister(op.dst(), half(rm, op.x()) * rsHalf);
        }
    }

    /// Sentinela `Ra=15` para o acumulador das famílias `SMLAD`/`SMMLA` (B9.1): "sem acumulador",
    /// o alias `SMUAD`/`SMUSD`/`SMMUL`/`SMMLS` sem `Ra` — mesmo encoding, por definição do manual.
    private static final int DSP_MEDIA_NO_ACCUMULATOR = 15;

    /// `SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}` (ARMv6, B9.1): dois produtos 16x16 com sinal
    /// (a segunda metade de `rn` trocada quando `exchange`) somados ou subtraídos entre si.
    /// Forma curta (`!longForm`): acumula em 32 bits, seta Q sticky em overflow (equivalente, em
    /// precisão de 64 bits, ao 3-way-add real do hardware — a ordem de operações não afeta o
    /// resultado nem a detecção de overflow, só a forma como o QEMU evita um único caso de
    /// arredondamento incorreto). Forma longa: acumula em 64 bits `Ra:Rd`, nunca seta Q.
    public void executeDspDualMultiply(ArmCore core, IrOp.DspDualMultiply op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rn = core.register(op.rn());
        int rm = op.exchange() ? Integer.rotateRight(core.register(op.rm()), 16) : core.register(op.rm());
        long product1 = (long) (short) rn * (short) rm;
        long product2 = (long) (short) (rn >> 16) * (short) (rm >> 16);
        long combined = op.subtract() ? product1 - product2 : product1 + product2;
        if (op.longForm()) {
            long acc = ((long) core.register(op.dst()) << 32) | (core.register(op.ra()) & 0xFFFF_FFFFL);
            long result = acc + combined;
            core.setRegister(op.ra(), (int) result);           // RdLo
            core.setRegister(op.dst(), (int) (result >>> 32));  // RdHi
        } else {
            long acc = op.ra() == DSP_MEDIA_NO_ACCUMULATOR ? 0 : core.register(op.ra());
            long sum = combined + acc;
            core.setRegister(op.dst(), (int) sum);
            if (sum != (int) sum) {
                core.cpsr().setSaturation(true);
            }
        }
    }

    /// `SMMLA{R}`/`SMMLS{R}` (ARMv6, B9.1): multiplicação 32x32 com sinal, mantendo só a metade
    /// mais significativa de 32 bits do produto de 64 bits, somada/subtraída a um acumulador
    /// posicionado nos 32 bits altos (`Ra<<32 ± Rn×Rm`), com arredondamento opcional. Nunca seta
    /// flags (equivalente, em precisão de 64 bits, ao truque de soma em duas metades do QEMU).
    public void executeDspTopWordMultiply(ArmCore core, IrOp.DspTopWordMultiply op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        long product = (long) core.register(op.rn()) * (long) core.register(op.rm());
        long acc = op.ra() == DSP_MEDIA_NO_ACCUMULATOR ? 0 : ((long) core.register(op.ra())) << 32;
        long combined = op.subtract() ? acc - product : acc + product;
        if (op.round()) {
            combined += 0x8000_0000L;
        }
        core.setRegister(op.dst(), (int) (combined >>> 32));
    }

    /// Aritmética paralela ARMv6 (SADD16/UQSUB8/SHASX/...): cada lane é computada em precisão
    /// larga (int) e finalizada pela variante — wrap (escrevendo GE), saturação ou halving.
    /// As variantes saturadas paralelas NÃO tocam o flag Q sticky (diferente de QADD/QSUB).
    public void executeParallelAlu(ArmCore core, IrOp.ParallelAlu op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rn = core.register(op.rn());
        int rm = core.register(op.rm());
        ParallelAluVariant variant = op.variant();
        int result;
        int ge;
        if (op.op().laneBits() == 8) {
            boolean add = op.op() == ParallelAluOp.ADD8;
            result = 0;
            ge = 0;
            for (int lane = 0; lane < 4; lane++) {
                int shift = lane * 8;
                int a = laneValue(rn >> shift, 8, variant.unsigned());
                int b = laneValue(rm >> shift, 8, variant.unsigned());
                int wide = add ? a + b : a - b;
                result |= (finishLane(wide, 8, variant) & 0xFF) << shift;
                if (laneGe(wide, 8, add, variant.unsigned())) {
                    ge |= 1 << lane;
                }
            }
        } else {
            // Formas halfword: ASX/SAX cruzam as lanes de Rm e misturam soma/subtração.
            int rnLow = laneValue(rn, 16, variant.unsigned());
            int rnHigh = laneValue(rn >> 16, 16, variant.unsigned());
            int rmLow = laneValue(rm, 16, variant.unsigned());
            int rmHigh = laneValue(rm >> 16, 16, variant.unsigned());
            boolean lowAdds;
            boolean highAdds;
            int wideLow;
            int wideHigh;
            switch (op.op()) {
                case ADD16 -> { lowAdds = true; highAdds = true; wideLow = rnLow + rmLow; wideHigh = rnHigh + rmHigh; }
                case SUB16 -> { lowAdds = false; highAdds = false; wideLow = rnLow - rmLow; wideHigh = rnHigh - rmHigh; }
                case SAX -> { lowAdds = true; highAdds = false; wideLow = rnLow + rmHigh; wideHigh = rnHigh - rmLow; }
                case ASX -> { lowAdds = false; highAdds = true; wideLow = rnLow - rmHigh; wideHigh = rnHigh + rmLow; }
                default -> throw new IllegalStateException("Lane de 16 bits inesperada: " + op.op());
            }
            result = (finishLane(wideLow, 16, variant) & 0xFFFF)
                    | ((finishLane(wideHigh, 16, variant) & 0xFFFF) << 16);
            ge = (laneGe(wideLow, 16, lowAdds, variant.unsigned()) ? 0b0011 : 0)
                    | (laneGe(wideHigh, 16, highAdds, variant.unsigned()) ? 0b1100 : 0);
        }
        core.setRegister(op.dst(), result);
        if (variant.writesGe()) {
            core.cpsr().setGe(ge);
        }
    }

    /// `SEL` (ARMv6): cada byte do resultado vem de Rn quando o GE correspondente está setado,
    /// senão de Rm. Não altera flag algum.
    public void executeSel(ArmCore core, IrOp.Sel op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rn = core.register(op.rn());
        int rm = core.register(op.rm());
        int ge = core.cpsr().ge();
        int mask = 0;
        for (int lane = 0; lane < 4; lane++) {
            if ((ge & (1 << lane)) != 0) {
                mask |= 0xFF << (lane * 8);
            }
        }
        core.setRegister(op.dst(), (rn & mask) | (rm & ~mask));
    }

    /// SSAT/USAT/SSAT16/USAT16 (ARMv6): satura o operando (já shiftado nas formas word) para a
    /// largura pedida e seta o flag Q sticky quando alguma lane saturou.
    public void executeSaturate(ArmCore core, IrOp.Saturate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int value = support.operand(core, op.operand());
        boolean[] q = {false};
        int result;
        if (op.halfwords()) {
            int low = saturateTo((short) value, op.saturateBits(), op.unsignedRange(), q);
            int high = saturateTo((short) (value >> 16), op.saturateBits(), op.unsignedRange(), q);
            result = ((high & 0xFFFF) << 16) | (low & 0xFFFF);
        } else {
            result = saturateTo(value, op.saturateBits(), op.unsignedRange(), q);
        }
        core.setRegister(op.dst(), result);
        if (q[0]) {
            core.cpsr().setSaturation(true); // sticky, como QADD/QSUB
        }
    }

    /// USAD8/USADA8 (ARMv6): soma das diferenças absolutas dos quatro bytes sem sinal, com
    /// acumulador opcional. Não altera flag algum.
    public void executeAbsDiffSum(ArmCore core, IrOp.AbsDiffSum op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rm = core.register(op.rm());
        int rs = core.register(op.rs());
        int sum = 0;
        for (int lane = 0; lane < 4; lane++) {
            int shift = lane * 8;
            sum += Math.abs(((rm >> shift) & 0xFF) - ((rs >> shift) & 0xFF));
        }
        if (op.rn() >= 0) {
            sum += core.register(op.rn());
        }
        core.setRegister(op.dst(), sum);
    }

    /// `SBFX`/`UBFX` (ARMv6T2+, B3.1): move o campo para os bits altos e desloca de volta com
    /// sinal (`SBFX`) ou sem sinal (`UBFX`) — o truque padrão de extração de bit-field. Nunca
    /// toca flags.
    public void executeBitFieldExtract(ArmCore core, IrOp.BitFieldExtract op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int shiftLeft = 32 - op.lsb() - op.width();
        int shiftRight = 32 - op.width();
        int shifted = core.register(op.src()) << shiftLeft;
        int result = op.signedExtract() ? shifted >> shiftRight : shifted >>> shiftRight;
        core.setRegister(op.dst(), result);
    }

    /// `BFI`/`BFC` (ARMv6T2+, B3.1): substitui o campo `[lsb, lsb+width)` de `dst` pelos bits
    /// baixos de `src` (`BFI`) ou por zeros (`BFC`, `src == -1`), preservando o resto de `dst`.
    /// Nunca toca flags.
    public void executeBitFieldInsert(ArmCore core, IrOp.BitFieldInsert op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int mask = op.width() == 32 ? -1 : (((1 << op.width()) - 1) << op.lsb());
        int insertValue = op.src() < 0 ? 0 : core.register(op.src());
        int current = core.register(op.dst());
        core.setRegister(op.dst(), (current & ~mask) | ((insertValue << op.lsb()) & mask));
    }

    /// `RBIT` (ARMv6T2+, B3.1): inverte a ordem dos 32 bits. Nunca toca flags.
    public void executeBitReverse(ArmCore core, IrOp.BitReverse op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        core.setRegister(op.dst(), Integer.reverse(core.register(op.src())));
    }

    /// `SDIV`/`UDIV` (ARMv7, B3.1): divisão truncada para zero; divisão por zero resulta em `0`
    /// (sem exceção — ARM DDI 0406C A8.8.165). A divisão inteira de Java já wrapeia
    /// `Integer.MIN_VALUE / -1` para `Integer.MIN_VALUE` sem lançar, igual ao hardware. Nunca
    /// toca flags.
    public void executeDivide(ArmCore core, IrOp.Divide op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int dividend = core.register(op.dividend());
        int divisor = core.register(op.divisor());
        int result;
        if (divisor == 0) {
            result = 0;
        } else if (op.signedDivide()) {
            result = dividend / divisor;
        } else {
            result = Integer.divideUnsigned(dividend, divisor);
        }
        core.setRegister(op.dst(), result);
    }

    /// Satura `value` para `bits` bits (com ou sem sinal), marcando `q[0]` quando clampa.
    /// Com sinal, `bits` é 1..32 (32 nunca satura); sem sinal, 0..31 (0 clampa tudo para 0).
    private static int saturateTo(int value, int bits, boolean unsignedRange, boolean[] q) {
        int min;
        int max;
        if (unsignedRange) {
            min = 0;
            max = (1 << bits) - 1;
        } else {
            min = -(1 << (bits - 1));
            max = (1 << (bits - 1)) - 1;
        }
        int clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            q[0] = true;
        }
        return clamped;
    }

    /// Valor de uma lane já deslocada para os bits baixos, estendida por sinal ou por zero.
    private static int laneValue(int shifted, int laneBits, boolean unsigned) {
        if (laneBits == 8) {
            return unsigned ? shifted & 0xFF : (byte) shifted;
        }
        return unsigned ? shifted & 0xFFFF : (short) shifted;
    }

    /// Finaliza a lane conforme a variante: wrap (o chamador trunca), saturação ou halving.
    private static int finishLane(int wide, int laneBits, ParallelAluVariant variant) {
        if (variant.saturating()) {
            int max = variant.unsigned() ? (1 << laneBits) - 1 : (1 << (laneBits - 1)) - 1;
            int min = variant.unsigned() ? 0 : -(1 << (laneBits - 1));
            return Math.clamp(wide, min, max);
        }
        if (variant.halving()) {
            return wide >> 1;
        }
        return wide;
    }

    /// Regra GE por lane (só usada pelas variantes sem prefixo): com sinal, resultado ≥ 0;
    /// sem sinal, carry na soma (estouro da largura) e ausência de borrow na subtração.
    private static boolean laneGe(int wide, int laneBits, boolean add, boolean unsigned) {
        if (unsigned && add) {
            return wide >= (1 << laneBits);
        }
        return wide >= 0;
    }

    /// Metade de 16 bits de um registrador, com extensão de sinal: metade baixa quando `sel`==0, alta quando 1.
    private static int half(int value, int sel) {
        return sel == 0 ? (short) value : (short) (value >> 16);
    }

    private static int clamp(long value, boolean[] saturated) {
        if (value > Integer.MAX_VALUE) {
            saturated[0] = true;
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            saturated[0] = true;
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
