package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IntegerOp;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Emissão nativa do inteiro fora da ALU: multiplicação, ARMv5TE (saturação/DSP), ARMv6
/// (paralelas/SEL/saturação/USAD) e ARMv7 (bitfield/RBIT/divisão/MOVT).
///
/// Os records e as condições em que cada um é nativo estão em {@link #register}; o laço de
/// compilação e o register cache, em {@link AsmBlockCompiler}.
final class AsmIntegerEmitter extends AsmEmitterBase {
    AsmIntegerEmitter(AsmEmitState state) {
        super(state);
    }

    static void register(AsmEmitterRegistry.Builder registry) {
        // MLS (B3.1, subtractFromAccumulator): ISUB no lugar do IADD do caminho MLA (B3.6).
        registry.add(AsmEmission.of(IntegerOp.Multiply.class, AsmEmitters::integer, AsmIntegerEmitter::emitMultiply)
                .counting(AsmIntegerEmitter::countMultiply));
        // UMAAL (ARMv6, B1.2): acumulador duplo nativo desde a task B1.6.
        registry.add(AsmEmission.of(IntegerOp.LongMultiply.class, AsmEmitters::integer, AsmIntegerEmitter::emitLongMultiply)
                .counting(AsmIntegerEmitter::countLongMultiply));
        // ARMv5TE nativas (Mobiclip/SDK usam pesado); só as formas com escrita em PC
        // (UNPREDICTABLE/troca de bloco) ficam no interpretado.
        registry.add(AsmEmission.of(IntegerOp.Saturating.class, AsmEmitters::integer, AsmIntegerEmitter::emitSaturating)
                .accepting(AsmIntegerEmitter::acceptsSaturating)
                .counting(AsmIntegerEmitter::countSaturating));
        registry.add(AsmEmission.of(IntegerOp.DspMultiply.class, AsmEmitters::integer, AsmIntegerEmitter::emitDspMultiply)
                .accepting(AsmIntegerEmitter::acceptsDspMultiply)
                .counting(AsmIntegerEmitter::countDspMultiply));
        // ARMv6 da B1.3 (paralelas, SEL, saturação, USAD): nativas desde a task B1.6.
        registry.add(AsmEmission.of(IntegerOp.ParallelAlu.class, AsmEmitters::integer, AsmIntegerEmitter::emitParallelAlu)
                .counting(AsmIntegerEmitter::countParallelAlu));
        registry.add(AsmEmission.of(IntegerOp.Sel.class, AsmEmitters::integer, AsmIntegerEmitter::emitSel)
                .counting(AsmIntegerEmitter::countSel));
        registry.add(AsmEmission.of(IntegerOp.Saturate.class, AsmEmitters::integer, AsmIntegerEmitter::emitSaturate)
                .counting(AsmIntegerEmitter::countSaturate));
        registry.add(AsmEmission.of(IntegerOp.AbsDiffSum.class, AsmEmitters::integer, AsmIntegerEmitter::emitAbsDiffSum)
                .counting(AsmIntegerEmitter::countAbsDiffSum));
        // Inteiro ARMv7 (B3.1) e MOVT (B2.2): bytecode direto sem helper desde a task B3.6 (PR1).
        registry.add(AsmEmission.of(IntegerOp.BitFieldExtract.class, AsmEmitters::integer, AsmIntegerEmitter::emitBitFieldExtract)
                .counting(AsmIntegerEmitter::countBitFieldExtract));
        registry.add(AsmEmission.of(IntegerOp.BitFieldInsert.class, AsmEmitters::integer, AsmIntegerEmitter::emitBitFieldInsert)
                .counting(AsmIntegerEmitter::countBitFieldInsert));
        registry.add(AsmEmission.of(IntegerOp.BitReverse.class, AsmEmitters::integer, AsmIntegerEmitter::emitBitReverse)
                .counting(AsmIntegerEmitter::countBitReverse));
        registry.add(AsmEmission.of(IntegerOp.Divide.class, AsmEmitters::integer, AsmIntegerEmitter::emitDivide)
                .counting(AsmIntegerEmitter::countDivide));
        registry.add(AsmEmission.of(IntegerOp.MoveTop.class, AsmEmitters::integer, AsmIntegerEmitter::emitMoveTop)
                .counting(AsmIntegerEmitter::countMoveTop));
        // SMLAD/SMLSD/SMLALD/SMLSLD/SMMLA/SMMLS (B9.1): pelo interpretado desde a task C12.7.
        registry.add(AsmEmission.interop(IntegerOp.DspDualMultiply.class));
        registry.add(AsmEmission.interop(IntegerOp.DspTopWordMultiply.class));
    }

    static boolean acceptsSaturating(IntegerOp.Saturating sat) {
        return sat.dst() != PC_REGISTER;
    }

    static boolean acceptsDspMultiply(IntegerOp.DspMultiply dsp) {
        return dsp.dst() != PC_REGISTER && !(dsp.op2() == 2 && dsp.rn() == PC_REGISTER);
    }

    static void countMultiply(IntegerOp.Multiply mul, AsmAccessCounter counter) {
        if (mul.rmValueOverride() == -1) counter.read(mul.rm());
        if (mul.rsValueOverride() == -1) counter.read(mul.rs());
        if (mul.accumulate() && mul.rnValueOverride() == -1) counter.read(mul.rn());
        counter.write(mul.dst());
    }

    static void countLongMultiply(IntegerOp.LongMultiply mul, AsmAccessCounter counter) {
        if (mul.rmValueOverride() == -1) counter.read(mul.rm());
        if (mul.rsValueOverride() == -1) counter.read(mul.rs());
        if (mul.accumulate() || mul.accumulateDouble()) {
            if (mul.dstLowValueOverride() == -1) counter.read(mul.dstLow());
            if (mul.dstHighValueOverride() == -1) counter.read(mul.dstHigh());
        }
        counter.write(mul.dstLow());
        counter.write(mul.dstHigh());
    }

    static void countSaturating(IntegerOp.Saturating sat, AsmAccessCounter counter) {
        counter.read(sat.rm());
        counter.read(sat.rn());
        counter.write(sat.dst());
    }

    static void countDspMultiply(IntegerOp.DspMultiply dsp, AsmAccessCounter counter) {
        counter.read(dsp.rm());
        counter.read(dsp.rs());
        if (dsp.op2() == 0 || (dsp.op2() == 1 && dsp.x() == 0)) {
            counter.read(dsp.rn());
        }
        if (dsp.op2() == 2) { // SMLAL: lê e escreve o par RdLo/RdHi
            counter.read(dsp.rn());
            counter.read(dsp.dst());
            counter.write(dsp.rn());
        }
        counter.write(dsp.dst());
    }

    static void countParallelAlu(IntegerOp.ParallelAlu pa, AsmAccessCounter counter) {
        counter.read(pa.rn());
        counter.read(pa.rm());
        counter.write(pa.dst());
    }

    static void countSel(IntegerOp.Sel sel, AsmAccessCounter counter) {
        counter.read(sel.rn());
        counter.read(sel.rm());
        counter.write(sel.dst());
    }

    static void countSaturate(IntegerOp.Saturate sat, AsmAccessCounter counter) {
        counter.operand(sat.operand());
        counter.write(sat.dst());
    }

    static void countAbsDiffSum(IntegerOp.AbsDiffSum ads, AsmAccessCounter counter) {
        counter.read(ads.rm());
        counter.read(ads.rs());
        if (ads.rn() >= 0) {
            counter.read(ads.rn());
        }
        counter.write(ads.dst());
    }

    static void countBitFieldExtract(IntegerOp.BitFieldExtract bfx, AsmAccessCounter counter) {
        counter.read(bfx.src());
        counter.write(bfx.dst());
    }

    static void countBitFieldInsert(IntegerOp.BitFieldInsert bfi, AsmAccessCounter counter) {
        counter.read(bfi.dst()); // preserva os bits fora do campo
        if (bfi.src() >= 0) {
            counter.read(bfi.src());
        }
        counter.write(bfi.dst());
    }

    static void countBitReverse(IntegerOp.BitReverse rbit, AsmAccessCounter counter) {
        counter.read(rbit.src());
        counter.write(rbit.dst());
    }

    static void countDivide(IntegerOp.Divide div, AsmAccessCounter counter) {
        counter.read(div.dividend());
        counter.read(div.divisor());
        counter.write(div.dst());
    }

    static void countMoveTop(IntegerOp.MoveTop movt, AsmAccessCounter counter) {
        counter.read(movt.dst()); // preserva a metade baixa existente
        counter.write(movt.dst());
    }

    // ── ARMv5TE (saturação / DSP / LDRD-STRD) ──────────────────────────────────

    /// QADD/QSUB/QDADD/QDSUB via helper por-valor (o bit Q sticky é o único efeito no core).
    void emitSaturating(MethodVisitor method, IntegerOp.Saturating sat) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitReadRegister(method, sat.rm());
        emitReadRegister(method, sat.rn());
        AsmBytecode.visitIntConst(method, sat.op());
        AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "saturating", "(" + CORE_REF + "III)I");
        emitStoreRegister(method, sat.dst());
    }

    /// Lê a metade de 16 bits (com sinal) de um registrador para a pilha: baixa (sel 0) ou alta.
    void emitReadHalf(MethodVisitor method, int register, int sel) {
        emitReadRegister(method, register);
        if (sel != 0) {
            AsmBytecode.visitIntConst(method, 16);
            method.visitInsn(Opcodes.ISHR);
        }
        method.visitInsn(Opcodes.I2S);
    }

    /// SMLAxy / SMLAWy / SMULWy / SMLALxy / SMULxy (espelha IrAluExecutor.executeDspMultiply).
    void emitDspMultiply(MethodVisitor method, IntegerOp.DspMultiply dsp) {
        switch (dsp.op2()) {
            case 0 -> { // SMLAxy: (Rm.x * Rs.y) + Rn, Q em overflow
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                emitReadHalf(method, dsp.rm(), dsp.x());
                emitReadHalf(method, dsp.rs(), dsp.y());
                emitReadRegister(method, dsp.rn());
                AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "dspSmla", "(" + CORE_REF + "III)I");
                emitStoreRegister(method, dsp.dst());
            }
            case 1 -> {
                if (dsp.x() == 0) { // SMLAWy
                    method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                    emitReadRegister(method, dsp.rm());
                    emitReadHalf(method, dsp.rs(), dsp.y());
                    emitReadRegister(method, dsp.rn());
                    AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "dspSmlaw", "(" + CORE_REF + "III)I");
                } else { // SMULWy
                    emitReadRegister(method, dsp.rm());
                    emitReadHalf(method, dsp.rs(), dsp.y());
                    AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "dspSmulw", "(II)I");
                }
                emitStoreRegister(method, dsp.dst());
            }
            case 2 -> { // SMLALxy: {RdHi:RdLo} += Rm.x * Rs.y
                emitReadRegister(method, dsp.dst()); // RdHi
                emitReadRegister(method, dsp.rn());  // RdLo
                emitReadHalf(method, dsp.rm(), dsp.x());
                emitReadHalf(method, dsp.rs(), dsp.y());
                AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "dspSmlal", "(IIII)J");
                method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);
                method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
                method.visitInsn(Opcodes.L2I);
                emitStoreRegister(method, dsp.rn());  // RdLo primeiro, como no interpretador
                method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
                AsmBytecode.visitIntConst(method, 32);
                method.visitInsn(Opcodes.LUSHR);
                method.visitInsn(Opcodes.L2I);
                emitStoreRegister(method, dsp.dst()); // RdHi
            }
            default -> { // SMULxy: Rm.x * Rs.y (puro)
                emitReadHalf(method, dsp.rm(), dsp.x());
                emitReadHalf(method, dsp.rs(), dsp.y());
                method.visitInsn(Opcodes.IMUL);
                emitStoreRegister(method, dsp.dst());
            }
        }
    }

    // ── ARMv6 (B1.3): paralelas / SEL / saturação / USAD ─────────────────────────

    /// Aritmética paralela (SADD16/UQSUB8/SHASX/...) via helper por-valor; a variante decide
    /// dentro do helper se GE é escrito no core (ver AsmIntegerHelpers.parallelAlu).
    void emitParallelAlu(MethodVisitor method, IntegerOp.ParallelAlu op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitReadRegister(method, op.rn());
        emitReadRegister(method, op.rm());
        AsmBytecode.visitIntConst(method, op.op().ordinal());
        AsmBytecode.visitIntConst(method, op.variant().ordinal());
        AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "parallelAlu", "(" + CORE_REF + "IIII)I");
        emitStoreRegister(method, op.dst());
    }

    void emitSel(MethodVisitor method, IntegerOp.Sel op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitReadRegister(method, op.rn());
        emitReadRegister(method, op.rm());
        AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "sel", "(" + CORE_REF + "II)I");
        emitStoreRegister(method, op.dst());
    }

    void emitSaturate(MethodVisitor method, IntegerOp.Saturate op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        emitOperand(method, op.operand());
        AsmBytecode.visitIntConst(method, op.saturateBits());
        method.visitInsn(op.unsignedRange() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.halfwords() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "saturate", "(" + CORE_REF + "IIZZ)I");
        emitStoreRegister(method, op.dst());
    }

    /// `rn=-1` (forma sem acumulador, USAD8) empilha `hasAccumulator=false` e um valor
    /// dummy — o helper ignora o valor quando a flag é falsa.
    void emitAbsDiffSum(MethodVisitor method, IntegerOp.AbsDiffSum op) {
        emitReadRegister(method, op.rm());
        emitReadRegister(method, op.rs());
        boolean hasAccumulator = op.rn() >= 0;
        method.visitInsn(hasAccumulator ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        if (hasAccumulator) {
            emitReadRegister(method, op.rn());
        } else {
            method.visitInsn(Opcodes.ICONST_0);
        }
        AsmBytecode.invokeStatic(method, INTEGER_HELPERS, "absDiffSum", "(IIZI)I");
        emitStoreRegister(method, op.dst());
    }

    // ── multiply ────────────────────────────────────────────────────────────────

    void emitMultiply(MethodVisitor method, IntegerOp.Multiply mul) {
        emitSrc1(method, mul.rm(), mul.rmValueOverride());
        emitSrc1(method, mul.rs(), mul.rsValueOverride());
        method.visitInsn(Opcodes.IMUL);
        if (mul.accumulate()) {
            emitSrc1(method, mul.rn(), mul.rnValueOverride());
            if (mul.subtractFromAccumulator()) {
                // MLS (B3.1): Rd = Ra - Rm*Rs. Pilha tem [produto, acumulador] — SWAP para
                // subtrair na ordem certa (acumulador - produto).
                method.visitInsn(Opcodes.SWAP);
                method.visitInsn(Opcodes.ISUB);
            } else {
                method.visitInsn(Opcodes.IADD);
            }
        }
        if (!mul.setFlags()) {
            emitStoreRegister(method, mul.dst());
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // result
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        emitStoreRegister(method, mul.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateNzFlags", CORE_I_TO_V);
    }

    void emitLongMultiply(MethodVisitor method, IntegerOp.LongMultiply mul) {
        // Carrega rm como long
        emitSrc1(method, mul.rm(), mul.rmValueOverride());
        emitAsLong(method, mul.signed());
        // Carrega rs como long
        emitSrc1(method, mul.rs(), mul.rsValueOverride());
        emitAsLong(method, mul.signed());
        method.visitInsn(Opcodes.LMUL);

        if (mul.accumulate()) {
            // current = Integer.toUnsignedLong(dstHigh) << 32 | Integer.toUnsignedLong(dstLow)
            emitSrc1(method, mul.dstHigh(), mul.dstHighValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            AsmBytecode.visitIntConst(method, 32);
            method.visitInsn(Opcodes.LSHL);
            emitSrc1(method, mul.dstLow(), mul.dstLowValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LOR);
            method.visitInsn(Opcodes.LADD);
        }
        if (mul.accumulateDouble()) {
            // UMAAL (ARMv6): RdLo e RdHi somam ao produto como DUAS parcelas de 32 bits sem sinal
            // independentes — não como um par 64-bit (accumulate regular). O caso máximo
            // 0xFFFFFFFF² + 2×0xFFFFFFFF nunca estoura 64 bits.
            emitSrc1(method, mul.dstLow(), mul.dstLowValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LADD);
            emitSrc1(method, mul.dstHigh(), mul.dstHighValueOverride());
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
            method.visitInsn(Opcodes.LADD);
        }
        method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);  // slots 7+8

        if (mul.setFlags()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeStatic(method, FLAG_HELPERS, "updateLongNzFlags", "(" + CORE_REF + "J)V");
        }
        // store low half: (int) result
        method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
        method.visitInsn(Opcodes.L2I);
        emitStoreRegister(method, mul.dstLow());
        // store high half: (int)(result >>> 32)
        method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
        AsmBytecode.visitIntConst(method, 32);
        method.visitInsn(Opcodes.LUSHR);
        method.visitInsn(Opcodes.L2I);
        emitStoreRegister(method, mul.dstHigh());
    }

    // ── inteiro ARMv7 (B3.1, emissão nativa B3.6/PR1) ────────────────────────────

    /// SBFX/UBFX: move o campo para os bits altos com `ISHL` e desloca de volta com sinal
    /// (`ISHR`) ou sem sinal (`IUSHR`) — mesmo truque do interpretado (`executeBitFieldExtract`).
    void emitBitFieldExtract(MethodVisitor method, IntegerOp.BitFieldExtract op) {
        emitReadRegister(method, op.src());
        AsmBytecode.visitIntConst(method, 32 - op.lsb() - op.width());
        method.visitInsn(Opcodes.ISHL);
        AsmBytecode.visitIntConst(method, 32 - op.width());
        method.visitInsn(op.signedExtract() ? Opcodes.ISHR : Opcodes.IUSHR);
        emitStoreRegister(method, op.dst());
    }

    /// BFI/BFC: a máscara do campo é uma constante pré-computada no emit (não em tempo de
    /// execução). `BFC` (`src == -1`) só aplica a máscara de preservação — inserir um valor
    /// zero via OR seria um no-op, então o passo de inserção é pulado inteiramente.
    void emitBitFieldInsert(MethodVisitor method, IntegerOp.BitFieldInsert op) {
        int mask = op.width() == 32 ? -1 : (((1 << op.width()) - 1) << op.lsb());
        emitReadRegister(method, op.dst());
        AsmBytecode.visitIntConst(method, ~mask);
        method.visitInsn(Opcodes.IAND);
        if (op.src() >= 0) {
            emitReadRegister(method, op.src());
            AsmBytecode.visitIntConst(method, op.lsb());
            method.visitInsn(Opcodes.ISHL);
            AsmBytecode.visitIntConst(method, mask);
            method.visitInsn(Opcodes.IAND);
            method.visitInsn(Opcodes.IOR);
        }
        emitStoreRegister(method, op.dst());
    }

    /// RBIT: `Integer.reverse` (intrínseco JIT), igual ao REV/`Integer.reverseBytes` de B1.6.
    void emitBitReverse(MethodVisitor method, IntegerOp.BitReverse op) {
        emitReadRegister(method, op.src());
        AsmBytecode.invokeStatic(method, INTEGER_CLASS, "reverse", "(I)I");
        emitStoreRegister(method, op.dst());
    }

    /// SDIV/UDIV: guarda o divisor 0 ANTES do `IDIV` (a ordem importa — ver Armadilhas da task
    /// B3.6). `Integer.MIN_VALUE / -1` não precisa de guard: a divisão inteira da JVM já devolve
    /// `MIN_VALUE` sem lançar, igual ao hardware.
    void emitDivide(MethodVisitor method, IntegerOp.Divide op) {
        emitReadRegister(method, op.dividend());
        emitReadRegister(method, op.divisor());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // divisor
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // dividendo
        Label divByZero = new Label();
        Label done = new Label();
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitJumpInsn(Opcodes.IFEQ, divByZero);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        if (op.signedDivide()) {
            method.visitInsn(Opcodes.IDIV);
        } else {
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "divideUnsigned", "(II)I");
        }
        method.visitJumpInsn(Opcodes.GOTO, done);
        method.visitLabel(divByZero);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitLabel(done);
        emitStoreRegister(method, op.dst());
    }

    /// MOVT: preserva os 16 bits baixos existentes de `dst` (AND) e insere o imediato nos 16
    /// bits altos (OR) — nunca toca flags, sem operando shiftado.
    void emitMoveTop(MethodVisitor method, IntegerOp.MoveTop op) {
        emitReadRegister(method, op.dst());
        AsmBytecode.visitIntConst(method, 0xFFFF);
        method.visitInsn(Opcodes.IAND);
        AsmBytecode.visitIntConst(method, op.immediate16() << 16);
        method.visitInsn(Opcodes.IOR);
        emitStoreRegister(method, op.dst());
    }

    /// Converte o int no topo da pilha para long. Usa I2L (com sinal) ou Integer.toUnsignedLong (sem sinal).
    void emitAsLong(MethodVisitor method, boolean signed) {
        if (signed) {
            method.visitInsn(Opcodes.I2L);
        } else {
            AsmBytecode.invokeStatic(method, INTEGER_CLASS, "toUnsignedLong", "(I)J");
        }
    }
}
