package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Semântica do endereçamento SVE (B17.12): `ADDVL`/`ADDPL`/`RDVL` e `ADR` vetorial.
///
/// `ADDVL Xd, Xn, #imm` = `Xn + imm × (VL/8)` bytes; `ADDPL` usa o tamanho do predicado (`VL/64` bytes, 8× menor);
/// `RDVL` é `ADDVL` com base zero. Ambos os registradores de `ADDVL`/`ADDPL` são `SP`-capazes (`31` = `SP`); o
/// destino de `RDVL` NÃO é (`31` = `XZR`, descartado). Os fatores vêm do core (`VL` configurado), nunca de constante.
///
/// `ADR` só CALCULA endereços por elemento — não toca memória: `Zd[i] = Zn[i] + (ext(Zm[i]) << msz)`, com o offset
/// de 32 bits estendido com sinal (`S32`) ou sem sinal (`U32`) nos elementos de 64 bits, ou do tamanho do próprio
/// elemento (`P32` = word, `P64` = doubleword).
final class SveAddressOps {
    private static final int STACK_POINTER_ENCODING = 31;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final long LOW_32_BITS = 0xFFFF_FFFFL;

    private SveAddressOps() {
    }

    /// Executa uma operação do grupo. `true` = a instrução já entrou numa exceção (acesso negado).
    static boolean execute(Aarch64Core core, Ir64Op.SveAddress op) {
        if (!SvePredicateOps.accessAllowed(core, op.instructionAddress())) {
            return true;
        }
        switch (op.op()) {
            case ADDVL -> writeStackCapable(core, op.rd(),
                    readStackCapable(core, op.rn()) + (long) op.imm() * core.vectorLengthBytes());
            case ADDPL -> writeStackCapable(core, op.rd(),
                    readStackCapable(core, op.rn()) + (long) op.imm() * core.predicateLengthBytes());
            case RDVL -> core.setX(op.rd(), (long) op.imm() * core.vectorLengthBytes());
            default -> vectorAddress(core, op);
        }
        return false;
    }

    private static long readStackCapable(Aarch64Core core, int index) {
        return index == STACK_POINTER_ENCODING ? core.sp() : core.x(index);
    }

    private static void writeStackCapable(Aarch64Core core, int index, long value) {
        if (index == STACK_POINTER_ENCODING) {
            core.setSp(value);
        } else {
            core.setX(index, value);
        }
    }

    private static void vectorAddress(Aarch64Core core, Ir64Op.SveAddress op) {
        Aarch64ScalableRegisters regs = core.scalable();
        boolean packedWord = op.op() == Ir64Op.SveAddress.Op.ADR_P32;
        int esz = packedWord ? ESZ_WORD : ESZ_DOUBLEWORD;
        int elements = core.vectorLengthBytes() >> esz;
        // Lê tudo antes de escrever: `Zd` pode ser o mesmo registrador de `Zn`/`Zm`.
        long[] result = new long[elements];
        for (int e = 0; e < elements; e++) {
            long base = SveIntegerOps.get(regs, op.rn(), e, esz);
            long offset = SveIntegerOps.get(regs, op.rm(), e, esz);
            long extended = switch (op.op()) {
                case ADR_S32 -> (long) (int) offset;
                case ADR_U32 -> offset & LOW_32_BITS;
                default -> offset;
            };
            result[e] = base + (extended << op.msz());
        }
        for (int e = 0; e < elements; e++) {
            SveIntegerOps.set(regs, op.rd(), e, esz, result[e]);
        }
    }
}
