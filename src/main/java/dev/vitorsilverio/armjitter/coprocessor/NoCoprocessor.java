package dev.vitorsilverio.armjitter.coprocessor;

/// The empty coprocessor bus returned by {@link CoprocessorBus#none()}: it claims to handle
/// nothing, so the core turns every coprocessor instruction into an Undefined exception and
/// the read/write methods are never reached.
enum NoCoprocessor implements CoprocessorBus {
    INSTANCE;

    @Override
    public boolean handles(int coprocessor) {
        return false;
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        throw new IllegalStateException("no coprocessor " + coprocessor + " present");
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        throw new IllegalStateException("no coprocessor " + coprocessor + " present");
    }
}
