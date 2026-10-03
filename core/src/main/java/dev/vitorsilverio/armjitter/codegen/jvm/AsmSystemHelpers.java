package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Helpers estáticos invocados pelo bytecode ASM gerado: desvio/escrita em PC (interworking), PSR, SWI, coprocessador e undefined.
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}.
/// Públicos porque o bytecode gerado roda em outro class loader.
public final class AsmSystemHelpers {
    private AsmSystemHelpers() {
    }

    // ── branches ───────────────────────────────────────────────────────────────

    /// Seta o modo Thumb a partir do bit 0 do alvo e atualiza o PC (semântica de BX no ARMv4T).
    public static void branchExchange(ArmCore core, int target) {
        if (core.exceptionModel().interceptsBranch(target)) {
            core.exceptionModel().branchIntercepted(core, target);
            return;
        }
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
    }

    /// Carrega um valor no PC, alinhando a 4 bytes em modo ARM ou 2 em THUMB (ARMv4T — sem
    /// interworking). Chamado diretamente (sem passar pelo intercept do `ExceptionModel`, B7.1)
    /// só pelo data-processing para PC ({@code MOV pc,...}, que nunca interworka); LDR/LDM/POP
    /// para PC passam por {@link #loadToPc}.
    public static void loadToPcArm4(ArmCore core, int value) {
        int mask = core.cpsr().isThumbMode() ? ~1 : ~3;
        core.setProgramCounter(value & mask);
    }

    /// Carrega um valor no PC COM interworking (ARMv5T+): o bit 0 seleciona o estado THUMB/ARM.
    /// Usado por {@link #loadToPc} (LDR/LDM/POP para PC no ARMv5).
    public static void loadToPcArm5(ArmCore core, int value) {
        core.cpsr().setThumbMode((value & 1) != 0);
        core.setProgramCounter(value & ~1);
    }

    /// Load-to-PC escolhendo interworking conforme a arquitetura (decidida no emit): ponto de
    /// entrada comum de TODO load-to-PC vindo de memória/pilha (LDR/LDR literal/POP/LDM, inline
    /// ou via {@link AsmMemoryHelpers#executeMultipleTransfer}) — espelha o
    /// {@code IrExecutionSupport#loadToPc} do interpretador (B7.1): checa
    /// {@link ArmCore#exceptionModel()} antes de decidir o interworking. NÃO usado pelo
    /// data-processing para PC ({@code MOV pc,...}), que nunca interworka e por isso nunca
    /// intercepta (chama {@link #loadToPcArm4} diretamente).
    public static void loadToPc(ArmCore core, int value, boolean interwork) {
        if (core.exceptionModel().interceptsBranch(value)) {
            core.exceptionModel().branchIntercepted(core, value);
            return;
        }
        if (interwork) {
            loadToPcArm5(core, value);
        } else {
            loadToPcArm4(core, value);
        }
    }

    // ── PSR ────────────────────────────────────────────────────────────────────

    public static void executePsrRead(ArmCore core, boolean spsr, int register) {
        CpuMode mode = core.mode();
        boolean hasSPSR = mode != CpuMode.USER && mode != CpuMode.SYSTEM;
        int value = (spsr && hasSPSR) ? core.spsr(mode) : core.cpsr().get();
        core.setRegister(register, value);
    }

    public static void executePsrWrite(ArmCore core, boolean spsr, int value, int fieldMask) {
        CpuMode mode = core.mode();
        boolean hasSPSR = mode != CpuMode.USER && mode != CpuMode.SYSTEM;
        int effectiveMask = (spsr || mode != CpuMode.USER) ? fieldMask : fieldMask & 0x8;
        if (spsr) {
            if (hasSPSR) {
                core.setSpsr(mode, mergePsr(core.spsr(mode), value, effectiveMask));
            }
        } else {
            core.setCpsr(mergePsr(core.cpsr().get(), value, effectiveMask));
        }
    }

    private static int mergePsr(int current, int value, int fieldMask) {
        int mask = 0;
        if ((fieldMask & 0x1) != 0) mask |= 0x0000_00FF;
        if ((fieldMask & 0x2) != 0) mask |= 0x0000_FF00;
        if ((fieldMask & 0x4) != 0) mask |= 0x00FF_0000;
        if ((fieldMask & 0x8) != 0) mask |= 0xFF00_0000;
        return (current & ~mask) | (value & mask);
    }

    // ── SWI ────────────────────────────────────────────────────────────────────

    /// Despacha uma interrupção de software. Sempre devolve {@code true} (o PC sempre muda).
    public static boolean executeSwi(ArmCore core, int immediate, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        if (!core.exceptionModel().handlesSupervisorCall() && core.swiDispatcher().canDispatch(immediate)) {
            CpuState next = core.swiDispatcher().dispatch(immediate, core.toCpuState());
            core.apply(next);
        } else {
            core.requestException(ArmException.SWI);
        }
        return true;
    }

    // ── coprocessor ────────────────────────────────────────────────────────────

    /// @return {@code true} when PC was changed (undefined exception triggered)
    public static boolean executeCoprocessor(
            ArmCore core, boolean load, int coprocessorNum, int opcode1,
            int crn, int crm, int opcode2, int register, int sequentialPc) {
        var bus = core.coprocessorBus();
        if (!bus.handles(coprocessorNum, opcode1, crn, crm, opcode2)) {
            core.setProgramCounter(sequentialPc);
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (load) {
            int value = bus.read(coprocessorNum, opcode1, crn, crm, opcode2);
            if (register == 15) {
                core.cpsr().setNzcv(
                        (value & 0x8000_0000) != 0,
                        (value & 0x4000_0000) != 0,
                        (value & 0x2000_0000) != 0,
                        (value & 0x1000_0000) != 0);
            } else {
                core.setRegister(register, value);
            }
        } else {
            bus.write(coprocessorNum, opcode1, crn, crm, opcode2, core.register(register));
        }
        return false;
    }

    /// @return {@code true} when PC was changed (undefined exception triggered)
    public static boolean executeCoprocessorDouble(
            ArmCore core, boolean load, int coprocessorNum, int opcode1,
            int crm, int rt, int rt2, int sequentialPc) {
        var bus = core.coprocessorBus();
        if (!bus.handlesDouble(coprocessorNum, opcode1, crm)) {
            core.setProgramCounter(sequentialPc);
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (load) {
            long value = bus.readDouble(coprocessorNum, opcode1, crm);
            core.setRegister(rt, (int) value);
            core.setRegister(rt2, (int) (value >>> 32));
        } else {
            bus.writeDouble(coprocessorNum, opcode1, crm, core.register(rt), core.register(rt2));
        }
        return false;
    }

    // ── undefined ──────────────────────────────────────────────────────────────

    public static void executeUndefined(ArmCore core, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        core.requestException(ArmException.UNDEFINED);
    }
}
