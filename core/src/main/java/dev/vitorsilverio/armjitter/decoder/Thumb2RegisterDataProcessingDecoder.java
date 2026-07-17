package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica o grupo "Data-processing (register)" Thumb-2 de 32 bits (B2.7, PR1) — o espaço
/// inteiro de prefixo `raw[31:24] == 0xFA`: shift por registrador (`LSL.W`/`LSR.W`/`ASR.W`/
/// `ROR.W`), extensão com rotação e acumulador (`SXT*`/`UXT*`/`SXTA*`/`UXTA*`), `REV`/`REV16`/
/// `REVSH`/`CLZ`, `SEL`, saturação ARMv5TE (`QADD`/`QSUB`/`QDADD`/`QDSUB`) e as 36 formas de
/// aritmética paralela ARMv6 (`SADD8`.../`UHSAX`). Anexado via
/// {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando
/// {@link ArmFeature#THUMB2} está habilitado no preset (via {@code ARMV6K_THUMB2}).
///
/// Cada grupo é gateado pela MESMA {@link ArmFeature} que a forma ARM clássica equivalente já usa
/// ({@link ArmDecoder}) — um preset sem a feature rejeita a forma Thumb-2 também, e a elevação
/// para IR (`StandardIrBuilder`) produz exatamente a mesma `DecodedInstruction`/IR que o decoder
/// ARM produz, então nenhuma semântica nova foi introduzida (G1).
///
/// **RBIT** (`REV`-family, `op4=1010`) foi acrescentado em B3.2, gateado por
/// {@link ArmFeature#BIT_REVERSE} (ver {@link #decodeRbit}) — o resto do padrão de bits deste
/// prefixo continua reivindicado por {@link #claimsEncodingSpace} (todo o `0xFA`), então caem em
/// UNDEFINED controlado em vez de UNIMPLEMENTED silencioso ambíguo (mesmo protocolo de B2.2.2).
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`),
/// exatamente como {@link ThumbDecoder} entrega às extensões de 32 bits. Referência: QEMU
/// `target/arm/tcg/t32.decode`, seções "Data-processing (register)"/"Data-processing
/// (register-shifted register)"/"Data-processing (two source registers)"/"Register extends"/
/// "Parallel addition and subtraction" (todas sob o prefixo `1111 1010`/`0xFA`).
public final class Thumb2RegisterDataProcessingDecoder implements DecoderExtension {
    /// `raw[31:24]`: prefixo fixo de todo este grupo — QEMU `t32.decode` linha 61 em diante.
    private static final int TOP8_MASK = 0xFF00_0000;
    private static final int TOP8_VALUE = 0xFA00_0000;

    private static final int PROGRAM_COUNTER = 15;
    private static final int STACK_POINTER = 13;

    private final ArmArchitecture architecture;

    /// Constrói o decoder ligado à arquitetura corrente, usada para gatear cada subgrupo pela
    /// mesma {@link ArmFeature} do encoding ARM clássico equivalente.
    public Thumb2RegisterDataProcessingDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public boolean claimsEncodingSpace(int raw) {
        return (raw & TOP8_MASK) == TOP8_VALUE;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if ((raw & TOP8_MASK) != TOP8_VALUE) {
            return null;
        }

        // MOV_rxrr (QEMU): shift por registrador — "1111 1010 0 shty:2 s:1 rm:4 1111 rd:4 0000 rs:4".
        // Único subgrupo com nibble[7:4] (lo) == 0000 fixo; todos os outros abaixo têm nibble[7:4] != 0.
        if (isShiftByRegisterForm(raw)) {
            return decodeShiftByRegister(raw, address, condition);
        }

        // Extensão com rotação/acumulador (A5.4.x "Register extends"): nibble[7:6] (lo) == 0b10 fixo,
        // nibble[23:20] (hi) em {0..5} seleciona SXTAH/UXTAH/SXTAB16/UXTAB16/SXTAB/UXTAB.
        if (isExtendForm(raw)) {
            return decodeExtend(raw, address, condition);
        }

        // Os grupos restantes (QADD-family/REV-family/SEL/CLZ e as 36 paralelas) só existem sob
        // nibble[15:12] (lo) == 0b1111 fixo (o "Ra=1111" convencional destas famílias).
        if (((raw >>> RESERVED_PADDING_SHIFT) & 0xF) != 0xF) {
            return null; // fora de qualquer subgrupo conhecido deste prefixo
        }
        int family = (raw >>> FAMILY_SHIFT) & 0xF;   // nibble[23:20]
        int op = (raw >>> OP_SHIFT) & 0xF;            // nibble[7:4]

        // QADD-family (family=8)/REV-family (family=9)/SEL (family=A)/CLZ (family=B): op sempre >= 8.
        if (family >= FAMILY_QADD && family <= FAMILY_CLZ && op >= TWO_SOURCE_OP_MIN) {
            return decodeTwoSourceGroup(raw, address, condition, family, op);
        }
        // As 36 paralelas: family em {8,9,A,C,D,E}, op em {0,1,2,4,5,6} (3 e 7 são buracos).
        if (isParallelFamily(family) && isParallelVariantOp(op)) {
            return decodeParallelAlu(raw, address, condition, family, op);
        }
        return null; // reservado dentro do prefixo 0xFA — UNDEFINED controlado (claimsEncodingSpace)
    }

    // ── Shift por registrador — QEMU "Data-processing (register-shifted register)" ─────────

    private static final int SHIFT_BY_REGISTER_MASK = 0xFF80_F0F0;
    private static final int SHIFT_BY_REGISTER_VALUE = 0xFA00_F000;

    private static boolean isShiftByRegisterForm(int raw) {
        return (raw & SHIFT_BY_REGISTER_MASK) == SHIFT_BY_REGISTER_VALUE;
    }

    private DecodedInstruction decodeShiftByRegister(int raw, int address, Condition condition) {
        // Sem gate de feature adicional aqui: o chamador (`ThumbDecoder`) só invoca este decoder
        // quando `ArmFeature#THUMB2` já está ativo (mesmo padrão de `Thumb2DataProcessingDecoder`/
        // `Thumb2BranchDecoder`/`Thumb2MiscDecoder` — o shift por registrador não tem uma feature
        // ARMv6+ própria além do próprio Thumb-2).
        int shty = (raw >>> 21) & 0x3;
        boolean setFlags = ((raw >>> 20) & 1) != 0;
        int rm = (raw >>> 16) & 0xF; // valor a ser deslocado ("rn=0" no QEMU: MOV Rd,Rm,<shift> Rs)
        int rd = (raw >>> 8) & 0xF;
        int rs = raw & 0xF; // registrador com a quantidade de deslocamento
        if (isRestricted(rd) || isRestricted(rm) || isRestricted(rs)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        InstructionKind kind = switch (shty) {
            case 0 -> InstructionKind.LSL;
            case 1 -> InstructionKind.LSR;
            case 2 -> InstructionKind.ASR;
            default -> InstructionKind.ROR;
        };
        // destinationRegister=rd, sourceRegister=rm (valor), secondSourceRegister=rs (quantidade) —
        // mesmo layout que o shift por registrador Thumb-1 (`ThumbDecoder`, formato 4 ALU ops):
        // o executor de IrOpCode.LSL/LSR/ASR/ROR já trata src2 como quantidade, não como operando
        // pré-deslocado (ver `StandardIrBuilder#operand` e `IrAluExecutor`).
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                rd, rm, rs, 0, false, setFlags, false);
    }

    // ── Extensão com rotação/acumulador — QEMU "Register extends" ──────────────────────────

    private static final int EXTEND_MASK = 0xFF00_F0C0;
    private static final int EXTEND_VALUE = 0xFA00_F080;
    /// Rn=1111 marca a forma SEM acumulador (SXTB/UXTH/...), espelhando `ArmDecoder`.
    private static final int EXTEND_NO_ACCUMULATOR = 0xF;

    /// **Armadilha real encontrada durante a implementação**: nibble[7:6]=`0b10` (o marcador fixo
    /// deste subgrupo) coincide EXATAMENTE com os 2 bits altos de TODO valor de nibble[7:4] no
    /// intervalo `{8,9,A,B}` (`1000`/`1001`/`1010`/`1011`) — o mesmo intervalo usado por
    /// `family`/`op` do grupo QADD-family/REV-family/SEL/CLZ logo abaixo. Checar só o mask fixo
    /// (sem validar `nibble[23:20]` aqui) fazia `tryDecode` devolver `null` cedo demais para TODA
    /// instrução desse outro grupo (ex. `REV`/`CLZ`/`SEL`), nunca alcançando o dispatch de
    /// `decodeTwoSourceGroup` — pego pelos testes de round-trip contra o ARM clássico
    /// (`Thumb2RegisterDataProcessingDecoderTest`). Corrigido validando `nibble[23:20] <= 5` (o
    /// intervalo real de `SXTAH`/`UXTAH`/`SXTAB16`/`UXTAB16`/`SXTAB`/`UXTAB`) já no predicado, para
    /// que valores `family` fora desse intervalo caiam para o próximo estágio do dispatch.
    private static boolean isExtendForm(int raw) {
        if ((raw & EXTEND_MASK) != EXTEND_VALUE) {
            return false;
        }
        int op4 = (raw >>> 20) & 0xF; // nibble[23:20]: 0..5 válidos (SXTAH/UXTAH/SXTAB16/UXTAB16/SXTAB/UXTAB)
        return op4 <= 5;
    }

    private DecodedInstruction decodeExtend(int raw, int address, Condition condition) {
        int op4 = (raw >>> 20) & 0xF;
        if (!architecture.has(ArmFeature.EXTEND_ROTATE)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> 16) & 0xF; // acumulador, ou 1111 = sem acumulador
        int rd = (raw >>> 8) & 0xF;
        int rot = (raw >>> 4) & 0x3; // rotação/8
        int rm = raw & 0xF;
        int realRn = rn == EXTEND_NO_ACCUMULATOR ? -1 : rn;
        if (isRestricted(rd) || isRestricted(rm) || (realRn >= 0 && isRestricted(realRn))) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        boolean unsigned = (op4 & 1) != 0; // SXTAH=0(signed),UXTAH=1(unsigned),SXTAB16=2,... alternando
        int field = switch (op4 >>> 1) {
            case 0 -> 0b11; // SXTAH/UXTAH -> H
            case 1 -> 0b00; // SXTAB16/UXTAB16 -> B16
            default -> 0b10; // SXTAB/UXTAB -> B
        };
        // Mesmo empacotamento de `ArmDecoder#EXTEND`: bits1:0=rotação/8, bits3:2=campo, bit4=unsigned.
        int packed = rot | (field << 2) | (unsigned ? 1 << 4 : 0);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.EXTEND,
                rd, realRn, rm, packed, false, false, false);
    }

    // ── QADD-family/REV-family/SEL/CLZ — QEMU "Data-processing (two source registers)" ────

    private static final int RESERVED_PADDING_SHIFT = 12; // nibble[15:12] (lo) == 0b1111
    private static final int FAMILY_SHIFT = 20;            // nibble[23:20] (hi)
    private static final int OP_SHIFT = 4;                 // nibble[7:4] (lo)

    private static final int FAMILY_QADD = 0x8;
    private static final int FAMILY_REV = 0x9;
    private static final int FAMILY_SEL = 0xA;
    private static final int FAMILY_CLZ = 0xB;
    /// `op` (nibble[7:4]) sempre `>= 0x8` neste grupo — distingue de "as 36 paralelas", que
    /// reusam os mesmos valores de `family` com `op` em `{0,1,2,4,5,6}`.
    private static final int TWO_SOURCE_OP_MIN = 0x8;

    private DecodedInstruction decodeTwoSourceGroup(int raw, int address, Condition condition, int family, int op) {
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        return switch (family) {
            case FAMILY_QADD -> decodeSaturatingQadd(raw, address, condition, rn, rd, rm, op);
            case FAMILY_REV -> decodeReverseOrClz(raw, address, condition, rd, rm, op, false);
            case FAMILY_SEL -> op == 0x8 ? decodeSel(raw, address, condition, rn, rd, rm) : null;
            case FAMILY_CLZ -> decodeReverseOrClz(raw, address, condition, rd, rm, op, true);
            default -> null;
        };
    }

    private DecodedInstruction decodeSaturatingQadd(int raw, int address, Condition condition,
            int rn, int rd, int rm, int op) {
        if (!architecture.has(ArmFeature.SATURATING)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // nibble[7:4]: 1000=QADD(op0), 1001=QDADD(op2), 1010=QSUB(op1), 1011=QDSUB(op3) — mesma
        // numeração de `op` usada por `IrAluExecutor#executeSaturating` (0=QADD,1=QSUB,2=QDADD,3=QDSUB).
        int saturatingOp = switch (op) {
            case 0x8 -> 0;
            case 0xA -> 1;
            case 0x9 -> 2;
            case 0xB -> 3;
            default -> -1;
        };
        if (saturatingOp < 0 || isRestricted(rd) || isRestricted(rm) || isRestricted(rn)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.SATURATING,
                rd, rm, rn, saturatingOp, false, false, false);
    }

    private DecodedInstruction decodeReverseOrClz(int raw, int address, Condition condition,
            int rd, int rm, int op, boolean clzFamily) {
        if (clzFamily) {
            if (op != 0x8) {
                return null; // resto do family=B é reservado dentro do prefixo 0xFA
            }
            if (!architecture.has(ArmFeature.CLZ)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            if (isRestricted(rd) || isRestricted(rm)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.CLZ,
                    rd, rm, -1, 0, false, false, false);
        }
        // family=REV: 1000=REV, 1001=REV16, 1010=RBIT (B3.2, InstructionKind diferente — ver
        // decodeRbit), 1011=REVSH.
        if (op == 0xA) {
            return decodeRbit(raw, address, condition, rd, rm);
        }
        int variant = switch (op) {
            case 0x8 -> 0;
            case 0x9 -> 1;
            case 0xB -> 2;
            default -> -1;
        };
        if (variant < 0) {
            return null;
        }
        if (!architecture.has(ArmFeature.BYTE_REVERSE)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        if (isRestricted(rd) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.BYTE_REVERSE,
                rd, rm, -1, variant, false, false, false);
    }

    /// `RBIT` (B3.2) — reversão bit a bit dos 32 bits, `InstructionKind` PRÓPRIO
    /// ({@link InstructionKind#BIT_REVERSE}), distinto de `REV`/`REV16`/`REVSH`
    /// ({@link InstructionKind#BYTE_REVERSE}) — mesma distinção que {@link ArmDecoder} já faz para
    /// o encoding ARM clássico equivalente, gateada pela MESMA feature ({@link ArmFeature#BIT_REVERSE},
    /// não {@link ArmFeature#BYTE_REVERSE}).
    private DecodedInstruction decodeRbit(int raw, int address, Condition condition, int rd, int rm) {
        if (!architecture.has(ArmFeature.BIT_REVERSE)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        if (isRestricted(rd) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.BIT_REVERSE,
                rd, rm, -1, 0, false, false, false);
    }

    private DecodedInstruction decodeSel(int raw, int address, Condition condition, int rn, int rd, int rm) {
        if (!architecture.has(ArmFeature.PARALLEL_SIMD)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.SEL,
                rd, rn, rm, 0, false, false, false);
    }

    // ── Aritmética paralela — QEMU "Parallel addition and subtraction" ─────────────────────

    private static boolean isParallelFamily(int family) {
        return family == 0x8 || family == 0x9 || family == 0xA
                || family == 0xC || family == 0xD || family == 0xE;
    }

    private static boolean isParallelVariantOp(int op) {
        return op == 0x0 || op == 0x1 || op == 0x2 || op == 0x4 || op == 0x5 || op == 0x6;
    }

    private DecodedInstruction decodeParallelAlu(int raw, int address, Condition condition, int family, int op) {
        if (!architecture.has(ArmFeature.PARALLEL_SIMD)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // family (nibble[23:20]) -> "ttt" de ArmDecoder (000=ADD16,001=ASX,010=SAX,011=SUB16,
        // 100=ADD8,111=SUB8); op (nibble[7:4]) -> "ppp" de ArmDecoder (001=S,010=Q,011=SH,101=U,
        // 110=UQ,111=UH) — reempacotado no MESMO formato que `IrOp.ParallelAlu`/`StandardIrBuilder`
        // já esperam (`packed = variantBits | (opBits << 3)`), sem IR nova.
        int armOpBits = switch (family) {
            case 0x9 -> 0b000; // ADD16
            case 0xA -> 0b001; // ASX
            case 0xE -> 0b010; // SAX
            case 0xD -> 0b011; // SUB16
            case 0x8 -> 0b100; // ADD8
            default -> 0b111; // 0xC: SUB8
        };
        int armVariantBits = switch (op) {
            case 0x0 -> 0b001; // S (plain, signed)
            case 0x1 -> 0b010; // Q (saturating, signed)
            case 0x2 -> 0b011; // SH (halving, signed)
            case 0x4 -> 0b101; // U (plain, unsigned)
            case 0x5 -> 0b110; // UQ (saturating, unsigned)
            default -> 0b111; // 0x6: UH (halving, unsigned)
        };
        int packed = armVariantBits | (armOpBits << 3);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.PARALLEL_ALU,
                rd, rn, rm, packed, false, false, false);
    }

    /// Rd/Rn/Rm ∈ {13,15} é UNPREDICTABLE em TODO o espaço 0xFA (ARM DDI 0406C A5.3/A5.4, mesma
    /// citação de B2.2.1) — vira UNDEFINED em vez de aceito silenciosamente.
    private static boolean isRestricted(int register) {
        return register == STACK_POINTER || register == PROGRAM_COUNTER;
    }
}
