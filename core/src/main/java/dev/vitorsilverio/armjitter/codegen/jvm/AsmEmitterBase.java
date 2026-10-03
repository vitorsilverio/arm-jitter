package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Base dos emissores de família do {@link AsmBlockCompiler}: convenção de locais do método gerado,
/// descritores compartilhados e as emissões que todas as famílias usam (leitura/escrita de
/// registrador pelo register cache, operandos, spill, fallback ao interpretado).
///
/// Instâncias NÃO são thread-safe: o {@link AsmEmitState} muda a cada compilação.
class AsmEmitterBase {
    static final String CORE = GuestToHostMapper.ARM_CORE;
    static final String CORE_REF = "L" + CORE + ";";
    // Owners dos helpers de runtime chamados pelo bytecode gerado, um por família.
    static final String FLAG_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmFlagHelpers";
    static final String MEMORY_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmMemoryHelpers";
    static final String INTEGER_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmIntegerHelpers";
    static final String SYSTEM_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmSystemHelpers";
    static final String VFP_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmVfpHelpers";
    static final String INTEGER_CLASS = "java/lang/Integer";

    // Slots
    static final int CORE_LOCAL = 0;
    static final int CYCLES_LOCAL = 1;
    static final int PC_CHANGED_LOCAL = 2;
    static final int TEMP1_LOCAL = 3;
    static final int TEMP2_LOCAL = 4;
    static final int TEMP3_LOCAL = 5;  // used internally by emitStoreRegister
    static final int ADDR_LOCAL = 6;
    static final int LONG_RESULT_LOCAL = 7;  // long occupies slots 7+8
    static final int CACHE_BASE_LOCAL = 9;   // first guest-register cache slot

    // Registradores guest com papel arquitetural fixo.
    static final int SP_REGISTER = 13;
    static final int LR_REGISTER = 14;
    static final int PC_REGISTER = 15;
    /// r0..r14 são cacheáveis; r15 (o PC) nunca é — só é materializado por helpers/fixup.
    static final int CACHEABLE_REGISTERS = 15;

    // Descritores para helpers que compartilham a mesma assinatura
    static final String CORE_I_TO_I = "(" + CORE_REF + "I)I";
    static final String CORE_II_TO_V = "(" + CORE_REF + "II)V";
    static final String CORE_I_TO_V = "(" + CORE_REF + "I)V";
    static final String CORE_IZ_TO_V = "(" + CORE_REF + "IZ)V";
    static final String CORE_IZ_TO_Z = "(" + CORE_REF + "IZ)Z";
    static final String CORE_IZZ_TO_Z = "(" + CORE_REF + "IZZ)Z";

    final AsmEmitState state;
    /// Ver {@link AsmEmitState#loadPcInterworks}.
    final boolean loadPcInterworks;
    /// Ver {@link AsmEmitState#unalignedAccess}.
    final boolean unalignedAccess;
    /// Ver {@link AsmEmitState#perOpExecutor}.
    final IrBlockExecutor perOpExecutor;

    AsmEmitterBase(AsmEmitState state) {
        this.state = state;
        this.loadPcInterworks = state.loadPcInterworks;
        this.unalignedAccess = state.unalignedAccess;
        this.perOpExecutor = state.perOpExecutor;
    }

    /// Entrada `interop` do registro: a op inteira pelo interpretado, com o `endPc` do bloco corrente.
    void emitInterop(MethodVisitor method, IrOp op) {
        emitPerOpFallback(method, op, state.blockEndPc);
    }

    /// Emite `loadPcInterworks` seguido da chamada a {@code AsmSystemHelpers#loadToPc(core,
    /// value, interwork)} — pilha já deve ter `core, value` empilhados. Usado por TODO
    /// load-to-PC vindo de memória/pilha (LDR/LDR literal/POP inline; LDM em bloco chama o
    /// mesmo helper por dentro de `executeMultipleTransfer`). Passa pelo intercept
    /// do `ExceptionModel` (B7.1) — diferente de `MOV pc,...`, que chama {@code loadToPcArm4}
    /// diretamente e nunca intercepta.
    void emitLoadToPcFromMemory(MethodVisitor method) {
        method.visitInsn(loadPcInterworks ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "loadToPc", CORE_IZ_TO_V);
    }

    /// Prólogo: carrega cada registrador cacheado do core para o seu local.
    void emitCachePrologue(MethodVisitor method) {
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (state.cache.slot[reg] >= 0) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, reg);
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
                method.visitVarInsn(Opcodes.ISTORE, state.cache.slot[reg]);
            }
        }
    }

    /// Descarrega os registradores cacheados possivelmente escritos de volta ao core.
    void emitCacheFlush(MethodVisitor method) {
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (state.cache.slot[reg] >= 0 && state.cache.dirty[reg]) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, reg);
                method.visitVarInsn(Opcodes.ILOAD, state.cache.slot[reg]);
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
            }
        }
    }

    /// Recarrega todos os registradores cacheados do core (após um helper que pode tê-los mudado).
    void emitCacheReload(MethodVisitor method) {
        for (int reg = 0; reg < CACHEABLE_REGISTERS; reg++) {
            if (state.cache.slot[reg] >= 0) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                AsmBytecode.visitIntConst(method, reg);
                AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
                method.visitVarInsn(Opcodes.ISTORE, state.cache.slot[reg]);
            }
        }
    }

    /// Emite um op "spilled": flush do cache, corpo, reload — o helper vê e deixa o core coerente.
    void emitSpilled(MethodVisitor method, Runnable body) {
        emitCacheFlush(method);
        body.run();
        emitCacheReload(method);
    }

    /// Lê um registrador guest para a pilha: do cache se cacheado, senão do core.
    void emitReadRegister(MethodVisitor method, int reg) {
        if (state.cache.cached(reg)) {
            method.visitVarInsn(Opcodes.ILOAD, state.cache.slot[reg]);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, reg);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerRead());
        }
    }

    /// Emite bytecode que delega uma op não suportada ao interpretado via {@link IrOpInterop}.
    void emitPerOpFallback(MethodVisitor method, IrOp op, int blockEndPc) {
        int opId = IrOpInterop.register(op, perOpExecutor);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, opId);
        AsmBytecode.visitIntConst(method, blockEndPc);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "dev/vitorsilverio/armjitter/codegen/jvm/IrOpInterop",
                "executeInterpreted",
                "(" + CORE_REF + "II)Z",
                false);
        emitConditionalSetPcChanged(method);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    void emitSrc1(MethodVisitor method, int register, int valueOverride) {
        if (valueOverride != -1) {
            AsmBytecode.visitIntConst(method, valueOverride);
            return;
        }
        emitReadRegister(method, register);
    }

    void emitOperand(MethodVisitor method, IrOperand operand) {
        switch (operand) {
            case IrOperand.Immediate imm -> AsmBytecode.visitIntConst(method, imm.value());
            case IrOperand.Register reg -> {
                if (reg.valueOverride() >= 0) {
                    AsmBytecode.visitIntConst(method, reg.valueOverride());
                } else {
                    emitReadRegister(method, reg.index());
                }
            }
            case IrOperand.ShiftedRegister sr -> {
                // shiftedOperand(core, value, shiftType, amount, regSpecified, rrx) espelha o
                // interpretador; o VALOR e a QUANTIDADE fluem pelo register state.cache.
                emitShiftedOperandArgs(method, sr);
                AsmBytecode.invokeStatic(method, FLAG_HELPERS, "shiftedOperand", "(" + CORE_REF + "IIIZZ)I");
                if (sr.negated()) {
                    method.visitInsn(Opcodes.INEG);
                }
            }
        }
    }

    /// Empilha os argumentos `(core, value, shiftType, amount, regSpecified, rrx)` compartilhados
    /// por {@code shiftedOperand} (valor) e {@code shiftedOperandCarry} (carry do shifter).
    void emitShiftedOperandArgs(MethodVisitor method, IrOperand.ShiftedRegister sr) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        if (sr.valueOverride() != -1) {
            AsmBytecode.visitIntConst(method, sr.valueOverride());
        } else {
            emitReadRegister(method, sr.index());
        }
        AsmBytecode.visitIntConst(method, sr.shiftType().ordinal());
        boolean regSpecified = sr.amountRegister() >= 0;
        if (regSpecified) {
            if (sr.amountValueOverride() != -1) {
                AsmBytecode.visitIntConst(method, sr.amountValueOverride() & 0xFF);
            } else {
                emitReadRegister(method, sr.amountRegister());
                AsmBytecode.visitIntConst(method, 0xFF);
                method.visitInsn(Opcodes.IAND);
            }
        } else {
            AsmBytecode.visitIntConst(method, sr.amount());
        }
        method.visitInsn(regSpecified ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(sr.rrx() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
    }

    void emitStoreRegister(MethodVisitor method, int dst) {
        if (dst != PC_REGISTER && state.cache.cached(dst)) {
            method.visitVarInsn(Opcodes.ISTORE, state.cache.slot[dst]);
            return;
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
        if (dst == PC_REGISTER) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            AsmBytecode.invokeStatic(method, SYSTEM_HELPERS, "loadToPcArm4", CORE_I_TO_V);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            AsmBytecode.visitIntConst(method, dst);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            AsmBytecode.invokeVirtual(method, GuestToHostMapper.registerWrite());
        }
    }

    /// Emite bytecode que desempilha um resultado booleano e seta PC_CHANGED_LOCAL para 1 se verdadeiro.
    void emitConditionalSetPcChanged(MethodVisitor method) {
        Label skip = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, skip);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        method.visitLabel(skip);
    }
}
