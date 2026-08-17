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

    /// Se este barramento implementa ESTE registrador específico. O default delega ao
    /// {@link #handles(int)} grosso — implementações existentes não mudam de comportamento.
    /// Devolver `false` faz o core entregar uma exceção Undefined ao guest, como faria para um
    /// coprocessador ausente; barramentos que atendem CP15 apenas parcialmente devem sobrepor
    /// este método para os registradores que realmente implementam.
    ///
    /// @param coprocessor número do coprocessador (15 para CP15)
    /// @param opcode1     código de operação primário (bits 23-21 da instrução)
    /// @param crn         registrador de coprocessador primário (CRn)
    /// @param crm         registrador de coprocessador secundário (CRm)
    /// @param opcode2     código de operação secundário (bits 7-5)
    default boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        return handles(coprocessor);
    }

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

    /// Se este barramento atende esta transferência DUPLA (`MCRR`/`MRRC`, F3) específica. Diferente
    /// de {@link #handles(int, int, int, int, int)}: não há `CRn` nem `opcode2` no encoding de
    /// `MCRR`/`MRRC` — só coprocessador, `opcode1` (4 bits) e `CRm`.
    ///
    /// **Diferente do default de {@link #handles(int, int, int, int, int)}**, este NÃO delega ao
    /// {@link #handles(int)} grosso — devolve `false` por padrão. Motivo (G3): toda implementação
    /// pré-F3 de {@link #handles(int)} neste código-base devolve `true` para o coprocessador
    /// inteiro (ex.: "coprocessor == 15"), sem nunca ter previsto a forma de transferência dupla
    /// (que só passou a decodificar nesta task); delegar ao predicado grosso faria QUALQUER
    /// barramento existente reivindicar `MCRR`/`MRRC` sem implementá-los de verdade, e os defaults
    /// de {@link #readDouble}/{@link #writeDouble} lançariam `UnsupportedOperationException` (um
    /// crash Java) em vez do guest receber a exceção ARM Undefined esperada. Barramentos que
    /// realmente implementam algum registrador de transferência dupla (ex.:
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor}) sobrepõem este método
    /// explicitamente — mesmo padrão de opt-in que {@link #handles(int, int, int, int, int)} já
    /// usa para os registradores que "atende só parcialmente".
    ///
    /// @param coprocessor número do coprocessador (15 para CP15)
    /// @param opcode1     código de operação (bits 7-4 da instrução, 4 bits — não confundir com o
    ///                    `opcode1` de 3 bits de `MCR`/`MRC`)
    /// @param crm         registrador de coprocessador (CRm)
    default boolean handlesDouble(int coprocessor, int opcode1, int crm) {
        return false;
    }

    /// `MRRC`: lê um par de registradores de coprocessador para dois registradores ARM.
    /// Parâmetros espelham {@link #handlesDouble}.
    ///
    /// @return os 64 bits lidos, empacotados com `Rt` nos 32 bits baixos e `Rt2` nos 32 bits altos
    default long readDouble(int coprocessor, int opcode1, int crm) {
        throw new UnsupportedOperationException(
                "CoprocessorBus.readDouble não implementado para coprocessor=" + coprocessor
                        + " opcode1=" + opcode1 + " crm=" + crm);
    }

    /// `MCRR`: escreve um par de registradores ARM em um par de registrador de coprocessador.
    /// Parâmetros espelham {@link #handlesDouble}.
    ///
    /// @param rt  registrador ARM de metade baixa (`Rt`)
    /// @param rt2 registrador ARM de metade alta (`Rt2`)
    default void writeDouble(int coprocessor, int opcode1, int crm, int rt, int rt2) {
        throw new UnsupportedOperationException(
                "CoprocessorBus.writeDouble não implementado para coprocessor=" + coprocessor
                        + " opcode1=" + opcode1 + " crm=" + crm);
    }

    /// Um barramento sem coprocessadores — toda transferência é tratada como indefinida. Padrão para cores
    /// que nunca usam coprocessadores (o GBA e o NDS ARM7).
    static CoprocessorBus none() {
        return NoCoprocessor.INSTANCE;
    }
}
