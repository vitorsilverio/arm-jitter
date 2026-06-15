package dev.vitorsilverio.armjitter.coprocessor;

/// Gancho do hospedeiro para transferências de registrador de coprocessador ARM (`MCR`/`MRC`), espelhando o modelo do
/// dispatcher de SWI. O CP15 do ARM9 (controle do sistema: TCM, cache, MPU, vetores altos) é o
/// usuário primário; um dispositivo instala uma implementação em seu core via
/// {@link dev.vitorsilverio.armjitter.core.ArmCore#setCoprocessorBus}.
///
/// Quando uma instrução de coprocessador aponta para um coprocessador que {@link #handles} retorna
/// `false`, o core lança uma exceção de Instrução Indefinida — como o hardware real faz
/// para um coprocessador ausente. Cores ARMv4T (GBA / NDS ARM7) mantêm o padrão {@link #none}.
public interface CoprocessorBus {
    /// Se este barramento atende o número de coprocessador fornecido (15 = CP15).
    boolean handles(int coprocessor);

    /// `MRC`: lê um registrador de coprocessador para o core ARM.
    ///
    /// @param coprocessor número do coprocessador (15 para CP15)
    /// @param opcode1     código de operação primário (bits 23-21 da instrução)
    /// @param crn         registrador de coprocessador primário (CRn)
    /// @param crm         registrador de coprocessador secundário (CRm)
    /// @param opcode2     código de operação secundário (bits 7-5)
    /// @return o valor do registrador de 32 bits
    int read(int coprocessor, int opcode1, int crn, int crm, int opcode2);

    /// `MCR`: escreve um valor de registrador ARM em um registrador de coprocessador. Os parâmetros espelham
    /// {@link #read}.
    void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value);

    /// Um barramento sem coprocessadores — toda transferência é tratada como indefinida. Padrão para cores
    /// que nunca usam coprocessadores (o GBA e o NDS ARM7).
    static CoprocessorBus none() {
        return NoCoprocessor.INSTANCE;
    }
}
