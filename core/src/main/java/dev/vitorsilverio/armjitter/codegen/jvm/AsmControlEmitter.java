package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Emissão nativa de desvio e sistema: branches, PSR, SWI, coprocessador, undefined, barreiras
/// e o par `Cycle`/`Fetch` de toda instrução.
///
/// Os records e as condições em que cada um é nativo estão em {@link #register}; o laço de
/// compilação e o register cache, em {@link AsmBlockCompiler}.
final class AsmControlEmitter extends AsmEmitterBase {
    AsmControlEmitter(AsmEmitState state) {
        super(state);
    }

    static void register(AsmEmitterRegistry.Builder registry) {
        registry.add(AsmEmission.of(IrOp.Cycle.class, AsmEmitters::control, AsmControlEmitter::emitCycle));
        registry.add(AsmEmission.of(IrOp.Fetch.class, AsmEmitters::control, AsmControlEmitter::emitFetch));
        registry.add(AsmEmission.of(BranchOp.Branch.class, AsmEmitters::control, AsmControlEmitter::emitBranch)
                .counting(AsmControlEmitter::countBranch));
        // BLX (BranchExchange com link, ThumbBlSuffix com exchange) -> interpretado.
        registry.add(AsmEmission.of(BranchOp.BranchExchange.class, AsmEmitters::control, AsmControlEmitter::emitBranchExchange)
                .accepting(AsmControlEmitter::acceptsBranchExchange)
                .counting(AsmControlEmitter::countBranchExchange));
        registry.add(AsmEmission.of(BranchOp.ThumbBlPrefix.class, AsmEmitters::control, AsmControlEmitter::emitThumbBlPrefix)
                .counting(AsmControlEmitter::countThumbBlPrefix));
        registry.add(AsmEmission.of(BranchOp.ThumbBlSuffix.class, AsmEmitters::control, AsmControlEmitter::emitThumbBlSuffix)
                .accepting(AsmControlEmitter::acceptsThumbBlSuffix)
                .counting(AsmControlEmitter::countThumbBlSuffix));
        // Helpers que leem/escrevem registradores no core: cercados de flush/reload.
        registry.add(AsmEmission.of(SystemOp.PsrTransfer.class, AsmEmitters::control, AsmControlEmitter::emitPsrTransfer)
                .spilled());
        registry.add(AsmEmission.of(SystemOp.Swi.class, AsmEmitters::control, AsmControlEmitter::emitSwi).spilled());
        registry.add(AsmEmission.of(SystemOp.Coprocessor.class, AsmEmitters::control, AsmControlEmitter::emitCoprocessor)
                .spilled());
        registry.add(AsmEmission.of(SystemOp.CoprocessorDouble.class, AsmEmitters::control, AsmControlEmitter::emitCoprocessorDouble)
                .spilled());
        registry.add(AsmEmission.of(SystemOp.Undefined.class, AsmEmitters::control, AsmControlEmitter::emitUndefined)
                .spilled());
        // DMB/DSB/ISB (Thumb-2, B2.5): NOP observável desde a task B3.6.
        registry.add(AsmEmission.of(SystemOp.MemoryBarrier.class, AsmEmitters::control, AsmControlEmitter::emitMemoryBarrier));
        // C12.7: mexem em modo/banco/CPSR/IT/exceção de guest (CPS/SETEND/SRS/RFE/WFI da B1.5,
        // IT/TBB/CBZ da B2.4, MRS/MSR SYSm do perfil M da B7.4, BKPT da B7.5, HVC/SMC/ERET/MRS_bank/
        // MSR_bank da B9.8) — semântica já escrita no executor, emitida via interpretado.
        registry.add(AsmEmission.interop(SystemOp.ChangeProcessorState.class));
        registry.add(AsmEmission.interop(SystemOp.SetEndianness.class));
        registry.add(AsmEmission.interop(SystemOp.StoreReturnState.class));
        registry.add(AsmEmission.interop(SystemOp.ReturnFromException.class));
        registry.add(AsmEmission.interop(SystemOp.WaitForInterrupt.class));
        registry.add(AsmEmission.interop(SystemOp.SetItState.class));
        registry.add(AsmEmission.interop(BranchOp.TableBranch.class));
        registry.add(AsmEmission.interop(BranchOp.CompareBranchZero.class));
        registry.add(AsmEmission.interop(SystemOp.MProfileSystemRegister.class));
        registry.add(AsmEmission.interop(SystemOp.Breakpoint.class));
        registry.add(AsmEmission.interop(SystemOp.Hvc.class));
        registry.add(AsmEmission.interop(SystemOp.Smc.class));
        registry.add(AsmEmission.interop(SystemOp.Eret.class));
        registry.add(AsmEmission.interop(SystemOp.MrsBank.class));
        registry.add(AsmEmission.interop(SystemOp.MsrBank.class));
    }

    static boolean acceptsBranchExchange(BranchOp.BranchExchange bx) {
        return !bx.link();
    }

    static boolean acceptsThumbBlSuffix(BranchOp.ThumbBlSuffix suffix) {
        return !suffix.exchange();
    }

    static void countBranch(BranchOp.Branch b, AsmAccessCounter counter) {
        if (b.link()) counter.write(LR_REGISTER);
    }

    static void countBranchExchange(BranchOp.BranchExchange bx, AsmAccessCounter counter) {
        if (bx.sourceValueOverride() == -1) counter.read(bx.sourceRegister());
    }

    static void countThumbBlPrefix(BranchOp.ThumbBlPrefix prefix, AsmAccessCounter counter) {
        counter.write(LR_REGISTER);
    }

    static void countThumbBlSuffix(BranchOp.ThumbBlSuffix suffix, AsmAccessCounter counter) {
        counter.read(LR_REGISTER);
        counter.write(LR_REGISTER);
    }

    /// NOP observável (ver SystemOp.MemoryBarrier) — nenhum bytecode além do Cycle/Fetch já
    /// emitidos separadamente para esta instrução.
    void emitMemoryBarrier(MethodVisitor method, SystemOp.MemoryBarrier barrier) {
    }

    // ── branches ───────────────────────────────────────────────────────────────

    void emitBranch(MethodVisitor method, BranchOp.Branch branch) {
        if (branch.link()) {
            AsmBytecode.visitIntConst(method, branch.returnAddress());
            emitStoreRegister(method, LR_REGISTER);
        }
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, branch.target());
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    void emitBranchExchange(MethodVisitor method, BranchOp.BranchExchange bx) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        if (bx.sourceValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, bx.sourceValueOverride());
        } else {
            emitReadRegister(method, bx.sourceRegister());
        }
        AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "branchExchange", CORE_I_TO_V);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    void emitThumbBlPrefix(MethodVisitor method, BranchOp.ThumbBlPrefix prefix) {
        // LR = address + 4 + highOffset (no PC change)
        AsmBytecode.visitIntConst(method, prefix.address() + 4 + prefix.highOffset());
        emitStoreRegister(method, LR_REGISTER);
    }

    void emitThumbBlSuffix(MethodVisitor method, BranchOp.ThumbBlSuffix suffix) {
        // oldLR = register(14)
        emitReadRegister(method, LR_REGISTER);
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // oldLR
        // LR = (address + 2) | 1
        AsmBytecode.visitIntConst(method, (suffix.address() + 2) | 1);
        emitStoreRegister(method, LR_REGISTER);
        // PC = oldLR + lowOffset
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        AsmBytecode.visitIntConst(method, suffix.lowOffset());
        method.visitInsn(Opcodes.IADD);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.programCounterWrite());
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    // ── PSR ────────────────────────────────────────────────────────────────────

    void emitPsrTransfer(MethodVisitor method, SystemOp.PsrTransfer psr) {
        if (psr.read()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitInsn(psr.spsr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            AsmBytecode.visitIntConst(method, psr.register());
            AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "executePsrRead", "(" + CORE_REF + "ZI)V");
        } else {
            int value;
            boolean needRuntime = false;
            if (psr.immediateOperand()) {
                value = psr.immediate();
            } else if (psr.registerValueOverride() != -1) {
                value = psr.registerValueOverride();
            } else {
                needRuntime = true;
                value = 0;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitInsn(psr.spsr() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            if (needRuntime) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, psr.register());
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
            } else {
                AsmBytecode.visitIntConst(method, value);
            }
            AsmBytecode.visitIntConst(method, psr.fieldMask());
            AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "executePsrWrite", "(" + CORE_REF + "ZII)V");
        }
    }

    // ── SWI / coprocessor / undefined ──────────────────────────────────────────

    void emitSwi(MethodVisitor method, SystemOp.Swi swi) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, swi.immediate());
        AsmBytecode.visitIntConst(method, state.blockEndPc);
        AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "executeSwi", "(" + CORE_REF + "II)Z");
        // always returns true
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    void emitCoprocessor(MethodVisitor method, SystemOp.Coprocessor cp) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(cp.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, cp.coprocessor());
        AsmBytecode.visitIntConst(method, cp.opcode1());
        AsmBytecode.visitIntConst(method, cp.crn());
        AsmBytecode.visitIntConst(method, cp.crm());
        AsmBytecode.visitIntConst(method, cp.opcode2());
        AsmBytecode.visitIntConst(method, cp.register());
        AsmBytecode.visitIntConst(method, cp.sequentialPc());
        AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "executeCoprocessor",
                "(" + CORE_REF + "ZIIIIIII)Z");
        emitConditionalSetPcChanged(method);
    }

    void emitCoprocessorDouble(MethodVisitor method, SystemOp.CoprocessorDouble cp) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(cp.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, cp.coprocessor());
        AsmBytecode.visitIntConst(method, cp.opcode1());
        AsmBytecode.visitIntConst(method, cp.crm());
        AsmBytecode.visitIntConst(method, cp.rt());
        AsmBytecode.visitIntConst(method, cp.rt2());
        AsmBytecode.visitIntConst(method, cp.sequentialPc());
        AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "executeCoprocessorDouble",
                "(" + CORE_REF + "ZIIIIII)Z");
        emitConditionalSetPcChanged(method);
    }

    void emitUndefined(MethodVisitor method, SystemOp.Undefined undef) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, undef.sequentialPc());
        AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "executeUndefined", CORE_I_TO_V);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
    }

    // ── cycle / fetch ──────────────────────────────────────────────────────────

    void emitCycle(MethodVisitor method, IrOp.Cycle cycle) {
        method.visitVarInsn(Opcodes.ILOAD, CYCLES_LOCAL);
        AsmBytecode.visitIntConst(method, cycle.count());
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, CYCLES_LOCAL);
    }

    void emitFetch(MethodVisitor method, IrOp.Fetch fetch) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, fetch.address());
        AsmBytecode.visitIntConst(method, fetch.sizeBytes());
        AsmBytecode.visitMemoryAccessType(method, MemoryAccessType.INSTRUCTION_FETCH);
        AsmBytecode.invokeVirtual(method, GuestToHostMapper.addMemoryCycles());
    }
}
