package dev.vitorsilverio.armjitter.coprocessor;

/// O CoprocessorBus vazio retornado por {@link CoprocessorBus#none()}: ele afirma não tratar
/// nada, então o core transforma toda instrução de coprocessor em exceção Undefined e
/// os métodos de leitura/escrita nunca são alcançados.
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
