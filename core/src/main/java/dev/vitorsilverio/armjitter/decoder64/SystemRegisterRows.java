package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decoder64.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;

import java.util.ArrayList;
import java.util.List;

/// E15.11 (D5 do épico E15): `MRS`/`MSR (register)` (`1101010100 L 1 o0 op1 CRn CRm op2 Rt`) como tabela
/// gerada do próprio {@link Aarch64SystemRegisterId}: uma linha por constante com
/// {@link Aarch64SystemRegisterId#encoding()}, a feature dela na coluna `requires` — antes
/// `Aarch64Decoder#decodeSystemRegister` (8 gates de feature em `if`) e `decodeSystemRegisterId` com 10
/// sub-tabelas de `if` por campo (B6.6.1 e seguintes).
///
/// Não existe forma `W`: o bit 31 é prefixo fixo, `Rt` é sempre `X`. `L` (leitura) e `Rt` ficam livres;
/// `RO`/`WO` não são conferidos (decisão da B10.7: tolerar o guest).
///
/// **Prioridade:** {@link #FALLBACK_ROWS} são os escaninhos tolerantes que só valem onde nenhuma linha
/// nomeada casa — {@link Aarch64SystemRegisterId#DEBUG_UNMODELED} (todo `op0=10`, B19.6) e
/// {@link Aarch64SystemRegisterId#ID_RESERVED_RAZ} (`op0=11,op1=0,CRn=0,CRm=1..7`, "reserved, RAZ" — achado
/// F11). Por isso `ID_AA64SMFR0_EL1` sem SME lê zero em vez de ser UNDEFINED, sem regra própria.
final class SystemRegisterRows {
    private static final int SYSTEM_REGISTER_PREFIX = 0b1101010100 << 22 | 1 << 20;
    /// Bits fixos de uma linha nomeada: tudo menos `L` (bit 21) e `Rt` (bits 4:0).
    private static final int NAMED_MASK = ~(1 << 21 | 0b1_1111);
    private static final int ENCODING_SHIFT = 5;
    private static final int READ_SHIFT = 21;
    private static final int RT_MASK = 0b1_1111;

    /// Uma linha por registrador com encoding, na ordem do enum.
    static final List<DecodeRow<Ir64Op>> ROWS = namedRows();

    /// Escaninhos, consultados só quando {@link #ROWS} não casa. Sem sobreposição entre si.
    static final List<DecodeRow<Ir64Op>> FALLBACK_ROWS = List.of(
            DecodeRow.of("1101010100 . 10 ... .... .... ... .....", null,
                    access(Aarch64SystemRegisterId.DEBUG_UNMODELED)),
            DecodeRow.of("1101010100 . 11 000 0000 0001 ... .....", null,
                    access(Aarch64SystemRegisterId.ID_RESERVED_RAZ)),
            DecodeRow.of("1101010100 . 11 000 0000 001. ... .....", null,
                    access(Aarch64SystemRegisterId.ID_RESERVED_RAZ)),
            DecodeRow.of("1101010100 . 11 000 0000 01.. ... .....", null,
                    access(Aarch64SystemRegisterId.ID_RESERVED_RAZ))
    );

    private SystemRegisterRows() {
    }

    private static List<DecodeRow<Ir64Op>> namedRows() {
        List<DecodeRow<Ir64Op>> rows = new ArrayList<>();
        for (Aarch64SystemRegisterId register : Aarch64SystemRegisterId.values()) {
            if (register.encoding() != Aarch64SystemRegisterId.NO_ENCODING) {
                rows.add(new DecodeRow<>(NAMED_MASK, SYSTEM_REGISTER_PREFIX | register.encoding() << ENCODING_SHIFT,
                        register.requires(), access(register)));
            }
        }
        return List.copyOf(rows);
    }

    private static WordDecoder<Ir64Op> access(Aarch64SystemRegisterId register) {
        return (word, address) -> new SystemOp64.SystemRegister(((word >>> READ_SHIFT) & 1) != 0, register,
                word & RT_MASK);
    }
}
