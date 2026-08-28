package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoSha3FourRegister}/{@link Ir64Op.CryptoSha3TwoSourceRotate}
/// (`FEAT_SHA3`, ARMv8.2-A — mesma ARMv8-A Cryptographic Extension opcional de
/// {@link Ir64CryptoShaThreeRegisterOp}, mas versão POSTERIOR: presente só a partir de ARMv8.2-A,
/// não confirmada no Cortex-A53 do raspi3 como a base `AES`/`SHA1`/`SHA256` de B8.11/B8.11b —
/// ver B11.12).
public enum Ir64CryptoSha3Op {
    /// `EOR3 Vd.16B, Vn.16B, Vm.16B, Va.16B`: `Vd = Vn XOR Vm XOR Va`, 128 bits inteiros.
    EOR3,
    /// `BCAX Vd.16B, Vn.16B, Vm.16B, Va.16B`: `Vd = Vn XOR (Vm AND NOT Va)`, 128 bits inteiros.
    BCAX,
    /// `RAX1 Vd.2D, Vn.2D, Vm.2D`: por lane de 64 bits, `Vd = Vn XOR ROL(Vm, 1)`.
    RAX1,
    /// `XAR Vd.2D, Vn.2D, Vm.2D, #imm6`: por lane de 64 bits, `Vd = ROR(Vn XOR Vm, imm6)`.
    XAR
}
