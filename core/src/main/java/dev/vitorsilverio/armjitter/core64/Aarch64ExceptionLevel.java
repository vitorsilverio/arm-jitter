package dev.vitorsilverio.armjitter.core64;

/// Nível de exceção AArch64 (`ARM DDI 0487 D1.1`) — `EL0` (aplicação) a `EL3` (Secure Monitor).
/// Task B10.1: substitui o antigo `boolean inEl1` de {@link Aarch64ExceptionState} (que só sabia
/// distinguir EL0/EL1) por um valor real de 4 níveis, base de todo o épico B10 (`EL2`/`EL3`
/// completos — ver `tasks/trilha-b-arquiteturas/b10-plano-el2-el3.md`).
///
/// Cada valor carrega o campo `M[3:0]` real de `SPSR_ELx` (`ARM DDI 0487 C5.2.19`) que identifica
/// o nível/seleção de `SP` de ORIGEM de uma exceção — usado por `ERET` para decidir para QUAL
/// nível voltar (não é sempre "um nível abaixo do atual": um handler de EL3 pode retornar direto
/// para EL1, por exemplo). Este emulador só modela a forma "h" (`SP_ELx` bancado, nunca `SP_EL0`
/// enquanto dentro de `ELx`) — mesma simplificação já usada para EL1 desde B6.6.4 (não há
/// modelagem de `PSTATE.SP`/troca de pilha dentro do mesmo nível).
public enum Aarch64ExceptionLevel {
    /// `EL0t` (`M[3:0]=0b0000`) — não tem `SP` bancado nem registradores de exceção próprios
    /// (`SP_EL0`/nenhum `ELRx`/`SPSRx`/`ESRx`/`FARx`/`VBARx`); usa {@link Aarch64Core#spEl0} e não
    /// tem banco em {@link Aarch64ExceptionState}.
    EL0(0b0000),
    /// `EL1h` (`M[3:0]=0b0101`).
    EL1(0b0101),
    /// `EL2h` (`M[3:0]=0b1001`) — armazenamento adicionado por B10.1; `MRS`/`MSR` dos
    /// registradores de sistema EL2 (`HCR_EL2`, ...) chegam em B10.2.
    EL2(0b1001),
    /// `EL3h` (`M[3:0]=0b1101`) — idem, `MRS`/`MSR` de EL3 chegam em B10.3.
    EL3(0b1101);

    private static final int M_FIELD_EL_SHIFT = 2;
    private static final int M_FIELD_EL_MASK = 0b11;

    private final int spsrMode;

    Aarch64ExceptionLevel(int spsrMode) {
        this.spsrMode = spsrMode;
    }

    /// Campo `M[3:0]` real que este nível grava em `SPSR_ELx` na entrada de uma exceção (forma
    /// "h", `SP_ELx` bancado — ver javadoc da classe).
    public int spsrMode() {
        return spsrMode;
    }

    /// Decodifica o nível-ALVO de um `ERET` a partir do valor bruto de `SPSR_ELx` lido do banco do
    /// nível ATUAL (`ARM DDI 0487` pseudocódigo `AArch64.ExceptionReturn`: só os bits `M[3:2]`
    /// importam para identificar o nível — `M[0]` seleciona `SP_EL0` vs `SP_ELx` dentro dele, não
    /// modelado aqui, ver javadoc da classe).
    public static Aarch64ExceptionLevel fromSpsrValue(long spsrValue) {
        int elField = (int) ((spsrValue >>> M_FIELD_EL_SHIFT) & M_FIELD_EL_MASK);
        return switch (elField) {
            case 0 -> EL0;
            case 1 -> EL1;
            case 2 -> EL2;
            case 3 -> EL3;
            default -> throw new IllegalStateException("elField fora de 0-3: " + elField);
        };
    }
}
