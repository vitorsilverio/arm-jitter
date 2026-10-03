package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.VfpOp;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Emissão nativa do VFP (B3.6, PR2).
///
/// Os records e as condições em que cada um é nativo estão em {@link #register}; o laço de
/// compilação e o register cache, em {@link AsmBlockCompiler}.
final class AsmVfpEmitter extends AsmEmitterBase {
    AsmVfpEmitter(AsmEmitState state) {
        super(state);
    }

    static void register(AsmEmitterRegistry.Builder registry) {
        registry.add(AsmEmission.of(VfpOp.Alu.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpAlu)
                .accepting(AsmVfpEmitter::acceptsAlu));
        registry.add(AsmEmission.of(VfpOp.MoveImmediate.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpMoveImmediate));
        registry.add(AsmEmission.of(VfpOp.Compare.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpCompare));
        registry.add(AsmEmission.of(VfpOp.Convert.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpConvert));
        registry.add(AsmEmission.of(VfpOp.Load.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpLoad)
                .counting(AsmVfpEmitter::countLoad));
        registry.add(AsmEmission.of(VfpOp.Store.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpStore)
                .counting(AsmVfpEmitter::countStore));
        registry.add(AsmEmission.of(VfpOp.CoreTransfer.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpCoreTransfer)
                .accepting(AsmVfpEmitter::acceptsCoreTransfer)
                .counting(AsmVfpEmitter::countCoreTransfer));
        // Helpers que tocam registrador(es) ARM diretamente no core (fora do register cache).
        registry.add(AsmEmission.of(VfpOp.MultipleTransfer.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpMultipleTransfer)
                .spilled());
        registry.add(AsmEmission.of(VfpOp.CorePairTransfer.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpCorePairTransfer)
                .spilled());
        registry.add(AsmEmission.of(VfpOp.SystemTransfer.class, AsmEmitters::vfp, AsmVfpEmitter::emitVfpSystemTransfer)
                .spilled());
        // VMOV_64_sp/VCVT_fix (B9.5): pelo interpretado desde a task C12.7.
        registry.add(AsmEmission.interop(VfpOp.CorePairTransferSingle.class));
        registry.add(AsmEmission.interop(VfpOp.ConvertFixed.class));
    }

    /// MAXNM/MINNM (B14.4, `ArmFeature.ARMV8_FP`) não têm emissão nativa; o resto de `VfpOp.Alu`
    /// (ADD/SUB/MUL/DIV/MLA/.../FNMS) é nativo desde a B3.6.
    static boolean acceptsAlu(VfpOp.Alu alu) {
        return alu.op() != VfpOp.VfpOperation.MAXNM && alu.op() != VfpOp.VfpOperation.MINNM;
    }

    /// A forma de 16 bits (`VMOV_half`, B22.2) e as de lane NEON (8/16 bits, B22.10) não têm
    /// emissão nativa.
    static boolean acceptsCoreTransfer(VfpOp.CoreTransfer transfer) {
        return !transfer.halfWidth() && !transfer.isLaneTransfer();
    }

    static void countLoad(VfpOp.Load load, AsmAccessCounter counter) {
        if (load.baseValueOverride() == -1) counter.read(load.base());
    }

    static void countStore(VfpOp.Store store, AsmAccessCounter counter) {
        if (store.baseValueOverride() == -1) counter.read(store.base());
    }

    static void countCoreTransfer(VfpOp.CoreTransfer transfer, AsmAccessCounter counter) {
        if (transfer.toArmRegister()) {
            counter.write(transfer.armRegister());
        } else {
            counter.read(transfer.armRegister());
        }
    }

    // ── VFP (B3.6, PR2) ──────────────────────────────────────────────────────────
    // VfpOp.Alu/VfpOp.MoveImmediate/VfpOp.Load/VfpOp.Store/VfpOp.CoreTransfer são bytecode direto (caminho
    // quente, decisão da task B3.6). VfpOp.Compare/VfpOp.Convert/VfpOp.MultipleTransfer/
    // VfpOp.CorePairTransfer/VfpOp.SystemTransfer chamam um helper estático em AsmVfpHelpers.

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`/`VNEG`/`VABS`/`VMOV` registrador (bytecode direto);
    /// `VMLA`/`VMLS`/`VNMLA`/`VNMLS`/`VNMUL`/`VSQRT` (mais raras) chamam
    /// {@code AsmVfpHelpers#vfpAluCold}.
    void emitVfpAlu(MethodVisitor method, VfpOp.Alu op) {
        switch (op.op()) {
            case ADD, SUB, MUL, DIV -> emitVfpArith(method, op);
            case NEG -> emitVfpSignBit(method, op, true);
            case ABS -> emitVfpSignBit(method, op, false);
            case COPY -> emitVfpCopy(method, op);
            case MLA, MLS, NMLA, NMLS, NMUL, SQRT, FMA, FMS, FNMA, FNMS -> {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, op.op().ordinal());
                method.visitInsn(op.doublePrecision() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                AsmBytecode.visitIntConst(method, op.vd());
                AsmBytecode.visitIntConst(method, op.vn());
                AsmBytecode.visitIntConst(method, op.vm());
                AsmBytecode.invokeStatic(method, VFP_HELPERS, "vfpAluCold", "(" + CORE_REF + "IZIII)V");
            }
        }
    }

    /// `VADD`/`VSUB`/`VMUL`/`VDIV`: converte os bits crus para `float`/`double` (view de
    /// {@code VfpRegisters}), aplica o opcode JVM nativo e grava de volta via
    /// {@code setSFloat}/{@code setDDouble} — que já usa `floatToRawIntBits`/`doubleToRawLongBits`
    /// por dentro (nunca a forma não-raw, que canonicalizaria NaN — Armadilha da task B3.6).
    void emitVfpArith(MethodVisitor method, VfpOp.Alu op) {
        if (op.doublePrecision()) {
            emitVfpRead(method, GuestToHostMapper.vfpDDouble(), op.vn());
            emitVfpRead(method, GuestToHostMapper.vfpDDouble(), op.vm());
            method.visitInsn(doubleArithOpcode(op.op()));
            method.visitVarInsn(Opcodes.DSTORE, LONG_RESULT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.DLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetDDouble());
        } else {
            emitVfpRead(method, GuestToHostMapper.vfpSFloat(), op.vn());
            emitVfpRead(method, GuestToHostMapper.vfpSFloat(), op.vm());
            method.visitInsn(singleArithOpcode(op.op()));
            method.visitVarInsn(Opcodes.FSTORE, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.FLOAD, TEMP1_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetSFloat());
        }
    }

    /// Empilha `core.vfp().<accessor>(index)` (recebedor + índice já resolvidos).
    void emitVfpRead(MethodVisitor method, HostMethodBinding accessor, int index) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
        AsmBytecode.visitIntConst(method, index);
        AsmBytecode.invokeVirtual(method, accessor);
    }

    static int singleArithOpcode(VfpOp.VfpOperation op) {
        return switch (op) {
            case ADD -> Opcodes.FADD;
            case SUB -> Opcodes.FSUB;
            case MUL -> Opcodes.FMUL;
            case DIV -> Opcodes.FDIV;
            default -> throw new IllegalStateException("emitVfpArith: op inesperado " + op);
        };
    }

    static int doubleArithOpcode(VfpOp.VfpOperation op) {
        return switch (op) {
            case ADD -> Opcodes.DADD;
            case SUB -> Opcodes.DSUB;
            case MUL -> Opcodes.DMUL;
            case DIV -> Opcodes.DDIV;
            default -> throw new IllegalStateException("emitVfpArith: op inesperado " + op);
        };
    }

    /// `VNEG`/`VABS`: manipula só o bit de sinal via XOR/AND com uma constante crua (NUNCA `0-x`/
    /// `Math.abs`, que canonicalizariam NaN e quebrariam em `-0.0` — mesma armadilha de
    /// `IrVfpExecutor`, aqui em bytecode).
    void emitVfpSignBit(MethodVisitor method, VfpOp.Alu op, boolean negate) {
        if (op.doublePrecision()) {
            emitVfpRead(method, GuestToHostMapper.vfpD(), op.vm());
            method.visitLdcInsn(negate ? Long.MIN_VALUE : Long.MAX_VALUE);
            method.visitInsn(negate ? Opcodes.LXOR : Opcodes.LAND);
            method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetD());
        } else {
            emitVfpRead(method, GuestToHostMapper.vfpS(), op.vm());
            AsmBytecode.visitIntConst(method, negate ? Integer.MIN_VALUE : Integer.MAX_VALUE);
            method.visitInsn(negate ? Opcodes.IXOR : Opcodes.IAND);
            method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vd());
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VMOV` registrador-a-registrador: cópia bit a bit crua (sem conversão de tipo).
    void emitVfpCopy(MethodVisitor method, VfpOp.Alu op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
        AsmBytecode.visitIntConst(method, op.vd());
        emitVfpRead(method, op.doublePrecision() ? GuestToHostMapper.vfpD() : GuestToHostMapper.vfpS(), op.vm());
        AsmBytecode.invokeVirtual(method, op.doublePrecision() ? GuestToHostMapper.vfpSetD() : GuestToHostMapper.vfpSetS());
    }

    /// `VMOV.F32`/`VMOV.F64 Vd, #imm`: grava o imediato já expandido pelo decoder/lifter.
    void emitVfpMoveImmediate(MethodVisitor method, VfpOp.MoveImmediate op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
        AsmBytecode.visitIntConst(method, op.vd());
        if (op.doublePrecision()) {
            method.visitLdcInsn(op.immediateBits());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetD());
        } else {
            AsmBytecode.visitIntConst(method, (int) op.immediateBits());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VCMP`/`VCMPE`: sem registrador ARM envolvido (só `FPSCR`) — chamado direto, sem
    /// {@code emitSpilled} (o register cache de r0-r14 nunca fica stale por isto).
    void emitVfpCompare(MethodVisitor method, VfpOp.Compare op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.doublePrecision() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.compareWithZero() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.vd());
        AsmBytecode.visitIntConst(method, op.vm());
        AsmBytecode.invokeStatic(method, VFP_HELPERS, "executeVfpCompare", "(" + CORE_REF + "ZZII)V");
    }

    /// `VCVT` (forma default): sem registrador ARM envolvido — mesma observação de {@link #emitVfpCompare}.
    void emitVfpConvert(MethodVisitor method, VfpOp.Convert op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, op.conversion().ordinal());
        AsmBytecode.visitIntConst(method, op.vd());
        AsmBytecode.visitIntConst(method, op.vm());
        AsmBytecode.invokeStatic(method, VFP_HELPERS, "executeVfpConvert", "(" + CORE_REF + "III)V");
    }

    /// `VLDR`: dupla precisão lê 2 words little-endian consecutivas via {@code loadWord}
    /// (metade baixa no endereço menor); `base` é lido pelo register cache.
    void emitVfpLoad(MethodVisitor method, VfpOp.Load load) {
        // `baseValueOverride` (`Vd, [pc, #imm]` — literal pool de `double`/`float` do `gcc`): sem
        // isso, `emitReadRegister` leria `R15` do register cache/core AO VIVO, que não tem o viés
        // `+8` do `PC` arquitetural nesta janela (ver o javadoc de {@link VfpOp.Load#baseValueOverride}).
        if (load.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, load.baseValueOverride());
        } else {
            emitReadRegister(method, load.base());
        }
        AsmBytecode.visitIntConst(method, load.offsetBytes());
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        if (load.doublePrecision()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            AsmBytecode.invokeStatic(method, VFP_HELPERS, "packDoubleWords", "(II)J");
            method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, load.vd());
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetD());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, load.vd());
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VSTR`: ver {@link #emitVfpLoad}.
    void emitVfpStore(MethodVisitor method, VfpOp.Store store) {
        // Ver {@link #emitVfpLoad} — mesmo tratamento de `baseValueOverride`.
        if (store.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, store.baseValueOverride());
        } else {
            emitReadRegister(method, store.base());
        }
        AsmBytecode.visitIntConst(method, store.offsetBytes());
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        if (store.doublePrecision()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, store.vd());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpD());
            method.visitVarInsn(Opcodes.LSTORE, LONG_RESULT_LOCAL);

            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            method.visitInsn(Opcodes.L2I);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);

            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            method.visitVarInsn(Opcodes.LLOAD, LONG_RESULT_LOCAL);
            AsmBytecode.visitIntConst(method, 32);
            method.visitInsn(Opcodes.LUSHR);
            method.visitInsn(Opcodes.L2I);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, store.vd());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpS());
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
        }
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: sempre via helper — cercado por {@code emitSpilled} no ponto
    /// de despacho (toca `base` diretamente no core, fora do register cache).
    void emitVfpMultipleTransfer(MethodVisitor method, VfpOp.MultipleTransfer op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.doublePrecision() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.base());
        AsmBytecode.visitIntConst(method, op.baseValueOverride());
        AsmBytecode.visitIntConst(method, op.firstRegister());
        AsmBytecode.visitIntConst(method, op.count());
        method.visitInsn(op.writeback() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(op.decrementBefore() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, VFP_HELPERS, "executeVfpMultipleTransfer",
                "(" + CORE_REF + "ZZIIIIZZ)V");
    }

    /// `VMOV Rt,Sn` / `VMOV Sn,Rt` (`FMRS`/`FMSR`): bytecode direto — `armRegister` é lido/escrito
    /// pelo register cache via {@link #emitReadRegister}/{@link #emitStoreRegister} (um único
    /// registrador, sem tocar o core por fora do cache, então sem necessidade de spill).
    void emitVfpCoreTransfer(MethodVisitor method, VfpOp.CoreTransfer op) {
        if (op.toArmRegister()) {
            emitVfpRead(method, GuestToHostMapper.vfpS(), op.vn());
            emitStoreRegister(method, op.armRegister());
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfp());
            AsmBytecode.visitIntConst(method, op.vn());
            emitReadRegister(method, op.armRegister());
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.vfpSetS());
        }
    }

    /// `VMOV Rt,Rt2,Dm` / `VMOV Dm,Rt,Rt2` (`FMRRD`/`FMDRR`): sempre via helper — cercado por
    /// {@code emitSpilled} no ponto de despacho (toca `armLow`/`armHigh` diretamente no core).
    void emitVfpCorePairTransfer(MethodVisitor method, VfpOp.CorePairTransfer op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.toArmRegisters() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.armLow());
        AsmBytecode.visitIntConst(method, op.armHigh());
        AsmBytecode.visitIntConst(method, op.vm());
        AsmBytecode.invokeStatic(method, VFP_HELPERS, "executeVfpCorePairTransfer", "(" + CORE_REF + "ZIII)V");
    }

    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`): sempre via helper — cercado por {@code emitSpilled} no
    /// ponto de despacho (toca `armRegister` diretamente no core, incl. o caso `APSR_nzcv`).
    void emitVfpSystemTransfer(MethodVisitor method, VfpOp.SystemTransfer op) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(op.read() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, op.armRegister());
        AsmBytecode.invokeStatic(method, VFP_HELPERS, "executeVfpSystemTransfer", "(" + CORE_REF + "ZI)V");
    }
}
