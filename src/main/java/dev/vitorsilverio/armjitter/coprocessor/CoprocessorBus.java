package dev.vitorsilverio.armjitter.coprocessor;

/// Host hook for ARM coprocessor register transfers (`MCR`/`MRC`), mirroring the SWI
/// dispatcher model. The ARM9's CP15 (system control: TCM, cache, MPU, high vectors) is the
/// primary user; a device installs an implementation on its core via
/// {@link dev.vitorsilverio.armjitter.core.ArmCore#setCoprocessorBus}.
///
/// When a coprocessor instruction targets a coprocessor that {@link #handles} reports
/// `false` for, the core raises an Undefined Instruction exception — as real hardware does
/// for an absent coprocessor. ARMv4T cores (GBA / NDS ARM7) keep the default {@link #none}.
public interface CoprocessorBus {
    /// Whether this bus services the given coprocessor number (15 = CP15).
    boolean handles(int coprocessor);

    /// `MRC`: reads a coprocessor register into the ARM core.
    ///
    /// @param coprocessor coprocessor number (15 for CP15)
    /// @param opcode1     primary opcode (bits 23-21 of the instruction)
    /// @param crn         primary coprocessor register (CRn)
    /// @param crm         secondary coprocessor register (CRm)
    /// @param opcode2     secondary opcode (bits 7-5)
    /// @return the 32-bit register value
    int read(int coprocessor, int opcode1, int crn, int crm, int opcode2);

    /// `MCR`: writes an ARM register value into a coprocessor register. Parameters mirror
    /// {@link #read}.
    void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value);

    /// A bus with no coprocessors — every transfer is treated as undefined. Default for cores
    /// that never use coprocessors (the GBA and the NDS ARM7).
    static CoprocessorBus none() {
        return NoCoprocessor.INSTANCE;
    }
}
