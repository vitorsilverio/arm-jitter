package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.BranchOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;

import java.util.List;

/// E15.11 (D5 do épico E15): a classe "Branches, Exception Generating and System" (`bits[28:26]=101`) do
/// A64 FORA do espaço de sistema (`1101010100`, ver {@link SystemInstructionRows}/{@link SystemRegisterRows}):
/// branch imediato/condicional, `CBZ`/`TBZ`, `CB<cc>` (`FEAT_CMPBR`), branch registrador (com PAuth) e
/// geração de exceção. Antes, `Aarch64Decoder#decodeBranchExceptionSystem` e 12 sub-decoders.
///
/// **Restrições que viraram linha e que a versão em cascata não conferia** (G8 medido pela E15.11 contra o
/// `a64.decode` do QEMU e o `objdump`): `CBB`/`CBH` só existem com `sf=0`; `ERET` exige `Rn=11111`.
///
/// Origem das famílias: branches e exceção (B6.1/B8.3), `ERET` (B6.6.4), `HVC`/`SMC` (B10.4/B10.5), PAuth
/// (B19.15), `CB<cc>` (B19.22).
final class BranchExceptionRows {
    private static final int SF_SHIFT = 31;
    private static final int LINK_SHIFT = 31;
    private static final int NONZERO_SHIFT = 24;
    private static final int IMM26_BITS = 26;
    private static final int IMM19_SHIFT = 5;
    private static final int IMM19_BITS = 19;
    private static final int IMM14_SHIFT = 5;
    private static final int IMM14_BITS = 14;
    private static final int IMM9_SHIFT = 5;
    private static final int IMM9_BITS = 9;
    private static final int TBZ_B5_SHIFT = 31;
    private static final int TBZ_B40_SHIFT = 19;
    private static final int TBZ_B40_BITS = 5;
    private static final int TBZ_B40_MASK = (1 << TBZ_B40_BITS) - 1;
    private static final int COND_MASK = 0xF;
    private static final int CB_CC_SHIFT = 21;
    private static final int CB_CC_MASK = 0b111;
    private static final int CB_ESZ_SHIFT = 14;
    private static final int CB_ESZ_MASK = 0b11;
    private static final int CB_ESZ_BYTE = 0b10;
    private static final int CB_ESZ_HALF = 0b11;
    private static final int CB_IMM6_SHIFT = 15;
    private static final int CB_IMM6_MASK = 0b11_1111;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final int IMM16_SHIFT = 5;
    private static final int IMM16_MASK = 0xFFFF;
    private static final int BYTES_PER_BRANCH_UNIT = 4;
    /// `Rn` de {@link Ir64BranchForm#IMMEDIATE} (sem registrador).
    private static final int NO_REGISTER = -1;
    /// `bitPosition` de {@link Ir64CompareBranchForm#CBZ_CBNZ} (sem bit testado).
    private static final int NO_BIT = -1;
    /// `RETAA`/`RETAB` voltam para `X30`, como `RET`.
    private static final int LINK_REGISTER = 30;

    /// Mapeamento de `cc` de `CB<cc>` registrador — `trans_CB_cond` do QEMU; `100`/`101` não têm linha.
    private static final Ir64CompareBranchCondition[] REGISTER_CONDITIONS = {
            Ir64CompareBranchCondition.GREATER_THAN, Ir64CompareBranchCondition.GREATER_OR_EQUAL,
            Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, Ir64CompareBranchCondition.GREATER_OR_EQUAL_UNSIGNED,
            null, null, Ir64CompareBranchCondition.EQUAL, Ir64CompareBranchCondition.NOT_EQUAL};
    /// Mapeamento de `cc` de `CB<cc>` imediato — DIFERENTE do registrador (`trans_CB_cond_imm`: "CB imm and
    /// CB encode the condition differently").
    private static final Ir64CompareBranchCondition[] IMMEDIATE_CONDITIONS = {
            Ir64CompareBranchCondition.GREATER_THAN, Ir64CompareBranchCondition.LESS_THAN,
            Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, Ir64CompareBranchCondition.LESS_THAN_UNSIGNED,
            null, null, Ir64CompareBranchCondition.EQUAL, Ir64CompareBranchCondition.NOT_EQUAL};

    private static final Aarch64Feature CMPBR = Aarch64Feature.COMPARE_AND_BRANCH;
    private static final Aarch64Feature PAUTH = Aarch64Feature.POINTER_AUTHENTICATION;

    /// As linhas, bit 31 → 0 (os campos de cada família no comentário).
    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = List.of(
            // B/BL — op 00101 imm26
            row("0 00101 ..........................", BranchExceptionRows::branchImmediate),
            row("1 00101 ..........................", BranchExceptionRows::branchImmediate),
            // B.cond — 0101010 o1 imm19 o0 cond (o1=1 não existe; o0=1 é BC.cond, FEAT_HBC, sem linha ainda)
            row("0101010 0 ................... 0 ....", BranchExceptionRows::branchConditional),
            // CBZ/CBNZ — sf 011010 op imm19 Rt; TBZ/TBNZ — b5 011011 op b40 imm14 Rt
            row(". 011010 . ................... .....", BranchExceptionRows::compareBranch),
            row(". 011011 . ..... .............. .....", BranchExceptionRows::testBranch),
            // CB<cc> registrador — sf 1110100 cc Rm esz imm9 Rt (cc 10x e esz 01 reservados; CBB/CBH só sf=0)
            DecodeRow.of("0 1110100 0.. ..... 00 ......... .....", CMPBR, BranchExceptionRows::compareAndBranchRegister),
            DecodeRow.of("0 1110100 11. ..... 00 ......... .....", CMPBR, BranchExceptionRows::compareAndBranchRegister),
            DecodeRow.of("1 1110100 0.. ..... 00 ......... .....", CMPBR, BranchExceptionRows::compareAndBranchRegister),
            DecodeRow.of("1 1110100 11. ..... 00 ......... .....", CMPBR, BranchExceptionRows::compareAndBranchRegister),
            DecodeRow.of("0 1110100 0.. ..... 1. ......... .....", CMPBR, BranchExceptionRows::compareAndBranchRegister),
            DecodeRow.of("0 1110100 11. ..... 1. ......... .....", CMPBR, BranchExceptionRows::compareAndBranchRegister),
            // CB<cc> imediato — sf 1110101 cc imm6 0 imm9 Rt
            DecodeRow.of(". 1110101 0.. ...... 0 ......... .....", CMPBR, BranchExceptionRows::compareAndBranchImmediate),
            DecodeRow.of(". 1110101 11. ...... 0 ......... .....", CMPBR, BranchExceptionRows::compareAndBranchImmediate),
            // BR/BLR/RET/ERET — 1101011 opc 11111 000000 Rn 00000 (ERET: Rn=11111)
            row("1101011 0000 11111 000000 ..... 00000", branchRegister(false)),
            row("1101011 0001 11111 000000 ..... 00000", branchRegister(true)),
            row("1101011 0010 11111 000000 ..... 00000", branchRegister(false)),
            row("1101011 0100 11111 000000 11111 00000", BranchExceptionRows::exceptionReturn),
            // PAuth — 1101011 opc 11111 00001 M Rn op4 (sem autenticação modelada: delega no não autenticado)
            DecodeRow.of("1101011 0000 11111 00001 . ..... 11111", PAUTH, branchRegister(false)),
            DecodeRow.of("1101011 0001 11111 00001 . ..... 11111", PAUTH, branchRegister(true)),
            DecodeRow.of("1101011 0010 11111 00001 . 11111 11111", PAUTH, BranchExceptionRows::returnAuthenticated),
            DecodeRow.of("1101011 0100 11111 00001 . 11111 11111", PAUTH, BranchExceptionRows::exceptionReturn),
            DecodeRow.of("1101011 1000 11111 00001 . ..... .....", PAUTH, branchRegister(false)),
            DecodeRow.of("1101011 1001 11111 00001 . ..... .....", PAUTH, branchRegister(true)),
            // Exceção — 11010100 opc imm16 op2 LL
            row("11010100 000 ................ 000 01", BranchExceptionRows::supervisorCall),
            row("11010100 000 ................ 000 10", privilegedCall(true)),
            row("11010100 000 ................ 000 11", privilegedCall(false)),
            row("11010100 001 ................ 000 00", BranchExceptionRows::breakpoint),
            row("11010100 010 ................ 000 00", BranchExceptionRows::halt)
    );

    private BranchExceptionRows() {
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static Ir64Op branchImmediate(int word, long address) {
        boolean link = bit(word, LINK_SHIFT) != 0;
        return new BranchOp64.Branch64(Ir64BranchForm.IMMEDIATE, address, target(word, address, 0, IMM26_BITS),
                NO_REGISTER, link, Ir64Condition.AL);
    }

    private static Ir64Op branchConditional(int word, long address) {
        Ir64Condition condition = Ir64Condition.decode(word & COND_MASK);
        return new BranchOp64.Branch64(Ir64BranchForm.IMMEDIATE, address,
                target(word, address, IMM19_SHIFT, IMM19_BITS), NO_REGISTER, false, condition);
    }

    private static Ir64Op compareBranch(int word, long address) {
        return new BranchOp64.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, rt(word), bit(word, SF_SHIFT) != 0,
                NO_BIT, bit(word, NONZERO_SHIFT) != 0, target(word, address, IMM19_SHIFT, IMM19_BITS));
    }

    private static Ir64Op testBranch(int word, long address) {
        int bitPosition = bit(word, TBZ_B5_SHIFT) << TBZ_B40_BITS | (word >>> TBZ_B40_SHIFT) & TBZ_B40_MASK;
        return new BranchOp64.CompareBranch64(Ir64CompareBranchForm.TBZ_TBNZ, rt(word), true, bitPosition,
                bit(word, NONZERO_SHIFT) != 0, target(word, address, IMM14_SHIFT, IMM14_BITS));
    }

    private static Ir64Op compareAndBranchRegister(int word, long address) {
        Ir64MemSize size = switch ((word >>> CB_ESZ_SHIFT) & CB_ESZ_MASK) {
            case CB_ESZ_BYTE -> Ir64MemSize.BYTE;
            case CB_ESZ_HALF -> Ir64MemSize.HALF;
            default -> bit(word, SF_SHIFT) != 0 ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        };
        return new BranchOp64.CompareAndBranchRegister(REGISTER_CONDITIONS[cc(word)], rt(word),
                (word >>> RM_SHIFT) & REGISTER_MASK, size, target(word, address, IMM9_SHIFT, IMM9_BITS));
    }

    private static Ir64Op compareAndBranchImmediate(int word, long address) {
        return new BranchOp64.CompareAndBranchImmediate(IMMEDIATE_CONDITIONS[cc(word)], rt(word),
                bit(word, SF_SHIFT) != 0, (word >>> CB_IMM6_SHIFT) & CB_IMM6_MASK,
                target(word, address, IMM9_SHIFT, IMM9_BITS));
    }

    private static WordDecoder<Ir64Op> branchRegister(boolean link) {
        return (word, address) -> new BranchOp64.Branch64(Ir64BranchForm.REGISTER, address, 0L,
                (word >>> RN_SHIFT) & REGISTER_MASK, link, Ir64Condition.AL);
    }

    private static Ir64Op returnAuthenticated(int word, long address) {
        return new BranchOp64.Branch64(Ir64BranchForm.REGISTER, address, 0L, LINK_REGISTER, false, Ir64Condition.AL);
    }

    private static Ir64Op exceptionReturn(int word, long address) {
        return new SystemOp64.ExceptionReturn();
    }

    private static Ir64Op supervisorCall(int word, long address) {
        return new SystemOp64.Svc(imm16(word));
    }

    /// `HVC` (`true`) e `SMC` (`false`): o `imm16` só importa para o handler, que lê a instrução.
    private static WordDecoder<Ir64Op> privilegedCall(boolean hypervisor) {
        return (word, address) -> new SystemOp64.PrivilegedCall(hypervisor);
    }

    private static Ir64Op breakpoint(int word, long address) {
        return new SystemOp64.Breakpoint(imm16(word));
    }

    /// `HLT`: sem estado de debug externo modelado, é UNDEFINED.
    private static Ir64Op halt(int word, long address) {
        return new SystemOp64.UndefinedInstructionTrap();
    }

    /// `address + SignExtend(imm) * 4`, com o imediato em `bits[shift + bits - 1 : shift]`.
    private static long target(int word, long address, int shift, int bits) {
        int unused = Integer.SIZE - shift - bits;
        long offset = (word << unused) >> (unused + shift);
        return address + offset * BYTES_PER_BRANCH_UNIT;
    }

    private static int cc(int word) {
        return (word >>> CB_CC_SHIFT) & CB_CC_MASK;
    }

    private static int imm16(int word) {
        return (word >>> IMM16_SHIFT) & IMM16_MASK;
    }

    private static int rt(int word) {
        return word & REGISTER_MASK;
    }

    private static int bit(int word, int shift) {
        return (word >>> shift) & 1;
    }
}
