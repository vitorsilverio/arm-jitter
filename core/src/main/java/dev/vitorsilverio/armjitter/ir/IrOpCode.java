package dev.vitorsilverio.armjitter.ir;

public enum IrOpCode {
    MOV,
    ADD,
    ADC,
    SUB,
    RSB,
    CMP,
    SBC,
    RSC,
    CMN, AND, EOR, ORR, BIC, TST, TEQ, MVN, CLZ, LSL, LSR, ASR, ROR, NEG,
    // ---- ARMv6 (B1.2): extensão com rotação (acumulador opcional via src1; src1 < 0 = sem
    // acumulador, com src1ValueOverride=0) e inversão de bytes. Nenhuma escreve flags.
    SXTB, SXTH, SXTB16, UXTB, UXTH, UXTB16, REV, REV16, REVSH,
    // ---- ARMv6 (B1.3): pack halfword. src1 = Rn; src2 = Rm já shiftado pelo operando
    // (PKHBT: LSL imm; PKHTB: ASR imm, com imm=0 significando ASR #32). Não escrevem flags.
    PKHBT, PKHTB,
    // ---- Thumb-2 (B2.2): ORN = Rn | ~operando (forma "OR NOT" nova no encoding de 32 bits;
    // sem equivalente ARM classico). Emissão ASM nativa fica para uma task futura de B2 (mesmo
    // padrão de B1.2-B1.5 até B1.6) — ver AsmNativePolicy.supportsAlu.
    ORN
}
