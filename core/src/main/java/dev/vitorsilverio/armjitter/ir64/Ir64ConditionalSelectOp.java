package dev.vitorsilverio.armjitter.ir64;

/// Sub-operação de {@link Ir64Op.ConditionalSelect} (`ARM DDI 0487 C6.2.34-37`, B6.3.2):
/// distinguidas só pelos bits `else_inv`(30)/`else_inc`(10) do encoding — `00`=`CSEL`,
/// `01`=`CSINC`, `10`=`CSINV`, `11`=`CSNEG` (`translate-a64.c:9468-9499`, `trans_CSEL`, ver os
/// Fatos de referência da task). Os aliases `CSET`/`CSETM`/`CINC`/`CINV`/`CNEG` não têm valor
/// próprio aqui — são o MESMO opcode com `src1==src2` (ou `==XZR`) e a condição já invertida pelo
/// assembler (decisão D1 da task).
public enum Ir64ConditionalSelectOp {
    /// `Rd = cond ? Rn : Rm`.
    CSEL,
    /// `Rd = cond ? Rn : (Rm + 1)`.
    CSINC,
    /// `Rd = cond ? Rn : ~Rm`.
    CSINV,
    /// `Rd = cond ? Rn : -Rm`.
    CSNEG
}
