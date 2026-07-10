package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decoder ARM32 inicial para o caminho interpretado frio.
public final class ArmDecoder implements InstructionDecoder {
    /// Rn=1111 nas encodings de extensão ARMv6 marca a forma sem acumulador (SXTB vs SXTAB).
    private static final int EXTEND_NO_ACCUMULATOR = 0xF;

    private final ArmArchitecture architecture;

    /// Decoder para a arquitetura base (ARMv4T / GBA).
    public ArmDecoder() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Decoder ligado a uma arquitetura: instruções ARMv5+ (CLZ, etc.) só são
    /// decodificadas se a arquitetura as suporta; o resto cai para UNIMPLEMENTED.
    public ArmDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma instrução ARM32 no endereço informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.read32(address & ~3);
        Condition condition = decodeCondition(raw >>> 28);

        if ((raw & 0x0F00_0000) == 0x0F00_0000) {
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SWI,
                    -1, -1, -1, (raw & 0x00FF_FFFF) >> 16, true, false, false);
        }

        // BLX (immediate): the cond==1111 unconditional space, `1111 101H <24-bit offset>`. Always
        // links and always switches to Thumb; the target carries bit 0 set so the exchange picks it.
        if ((raw & 0xFE00_0000) == 0xFA00_0000) {
            if (!architecture.has(ArmFeature.BLX)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int halfword = (raw >>> 24) & 1;
            int offset = (signExtend(raw & 0x00FF_FFFF, 24) << 2) + (halfword << 1);
            int target = address + 8 + offset;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.BRANCH_EXCHANGE, -1, -1, -1, target | 1, false, false, true);
        }

        if ((raw & 0x0E00_0000) == 0x0A00_0000) {
            boolean link = (raw & (1 << 24)) != 0;
            int offset = signExtend(raw & 0x00FF_FFFF, 24) << 2;
            int target = address + 8 + offset;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.BRANCH,
                    -1, -1, -1, target, true, false, link);
        }

        if ((raw & 0x0FFF_FFF0) == 0x012F_FF10) {
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.BRANCH_EXCHANGE,
                    -1, rm, -1, 0, false, false, false);
        }

        // BLX (register): `cccc 0001 0010 1111 1111 1111 0011 mmmm` — like BX but also links.
        if ((raw & 0x0FFF_FFF0) == 0x012F_FF30) {
            if (!architecture.has(ArmFeature.BLX)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.BRANCH_EXCHANGE,
                    -1, rm, -1, 0, false, false, true);
        }

        if ((raw & 0x0FFF_0FF0) == 0x016F_0F10) {
            if (!architecture.has(ArmFeature.CLZ)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.CLZ,
                    rd, rm, -1, 0, false, false, false);
        }

        // Saturating arithmetic (ARMv5TE): QADD/QSUB/QDADD/QDSUB. `cccc 0001 0PP0 nnnn dddd 0000 0101 mmmm`.
        // Only intercepted when the architecture has the feature, so ARMv4T keeps its prior behaviour.
        if ((raw & 0x0F90_0FF0) == 0x0100_0050 && architecture.has(ArmFeature.SATURATING)) {
            int op = (raw >>> 21) & 0x3;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SATURATING,
                    rd, rm, rn, op, false, false, false);
        }

        // DSP multiplies (ARMv5TE): `cccc 0001 0PP0 dddd nnnn ssss 1yx0 mmmm`. The 16-bit halves and
        // the accumulator register are packed into the immediate for the builder to unpack.
        if ((raw & 0x0F90_0090) == 0x0100_0080 && architecture.has(ArmFeature.DSP_MULTIPLY)) {
            int op2 = (raw >>> 21) & 0x3;
            int rd = (raw >>> 16) & 0xF;
            int rn = (raw >>> 12) & 0xF;
            int rs = (raw >>> 8) & 0xF;
            int x = (raw >>> 5) & 1;
            int y = (raw >>> 6) & 1;
            int rm = raw & 0xF;
            int packed = rn | (op2 << 4) | (x << 6) | (y << 7);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.DSP_MULTIPLY,
                    rd, rm, rs, packed, false, false, false);
        }

        // ARMv6 sign/zero-extend com rotação e as formas com acumulador:
        // `cccc 0110 1uff nnnn dddd rr00 0111 mmmm` — u=unsigned, ff: 00=B16, 10=B, 11=H
        // (ff=01 é indefinido), rr = rotação do operando em múltiplos de 8 bits. Rn=1111 é a
        // forma SEM acumulador (SXTB/UXTH/...); qualquer outro Rn acumula (SXTAB/UXTAH/...).
        if ((raw & 0x0F80_03F0) == 0x0680_0070 && ((raw >>> 20) & 0x3) != 0b01
                && architecture.has(ArmFeature.EXTEND_ROTATE)) {
            boolean unsigned = (raw & (1 << 22)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rotate = (raw >>> 10) & 0x3;
            int rm = raw & 0xF;
            int packed = rotate | (((raw >>> 20) & 0x3) << 2) | (unsigned ? 1 << 4 : 0);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.EXTEND,
                    rd, rn == EXTEND_NO_ACCUMULATOR ? -1 : rn, rm, packed, false, false, false);
        }

        // ARMv6 byte-reverse: REV `cccc 0110 1011 1111 dddd 1111 0011 mmmm`,
        // REV16 (idem com bits 7:4 = 1011), REVSH `cccc 0110 1111 1111 dddd 1111 1011 mmmm`.
        if (architecture.has(ArmFeature.BYTE_REVERSE)) {
            int variant = switch (raw & 0x0FFF_0FF0) {
                case 0x06BF_0F30 -> 0; // REV
                case 0x06BF_0FB0 -> 1; // REV16
                case 0x06FF_0FB0 -> 2; // REVSH
                default -> -1;
            };
            if (variant >= 0) {
                int rd = (raw >>> 12) & 0xF;
                int rm = raw & 0xF;
                return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                        InstructionKind.BYTE_REVERSE, rd, rm, -1, variant, false, false, false);
            }
        }

        if ((raw & 0x0FBF_0FFF) == 0x010F_0000) {
            boolean spsr = (raw & (1 << 22)) != 0;
            int rd = (raw >>> 12) & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MRS,
                    rd, -1, -1, spsr ? 1 : 0, true, false, false);
        }

        if ((raw & 0x0DB0_FFF0) == 0x0120_F000) {
            boolean spsr = (raw & (1 << 22)) != 0;
            int fieldMask = (raw >>> 16) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MSR,
                    -1, rm, -1, (spsr ? 0x10 : 0) | fieldMask, false, false, false);
        }

        if ((raw & 0x0FB0_F000) == 0x0320_F000) {
            boolean spsr = (raw & (1 << 22)) != 0;
            int fieldMask = (raw >>> 16) & 0xF;
            int value = rotateRight(raw & 0xFF, ((raw >>> 8) & 0xF) * 2);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MSR,
                    (spsr ? 0x10 : 0) | fieldMask, -1, -1, value, true, false, false);
        }

        if ((raw & 0x0FB0_0FF0) == 0x0100_0090) {
            boolean byteAccess = (raw & (1 << 22)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SWAP,
                    rd, rn, rm, 0, false, false, false, byteAccess ? 1 : 4, false);
        }

        // UMAAL (ARMv6): `cccc 0000 0100 hhhh llll ssss 1001 mmmm` — soma RdLo e RdHi (cada um
        // zero-estendido, como parcelas independentes) ao produto unsigned de 64 bits; sem flags.
        // Precisa vir antes do bloco de halfword-transfer, que engoliria o padrão como
        // UNIMPLEMENTED.
        if ((raw & 0x0FF0_00F0) == 0x0040_0090 && architecture.has(ArmFeature.UMAAL)) {
            int rdHigh = (raw >>> 16) & 0xF;
            int rdLow = (raw >>> 12) & 0xF;
            int rs = (raw >>> 8) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.UMAAL,
                    rdLow, rm, rs, rdHigh, false, false, false);
        }

        if ((raw & 0x0F80_00F0) == 0x0080_0090) {
            boolean signed = (raw & (1 << 22)) != 0;
            boolean accumulate = (raw & (1 << 21)) != 0;
            boolean setFlags = (raw & (1 << 20)) != 0;
            int rdHigh = (raw >>> 16) & 0xF;
            int rdLow = (raw >>> 12) & 0xF;
            int rs = (raw >>> 8) & 0xF;
            int rm = raw & 0xF;
            InstructionKind kind = switch ((signed ? 0b10 : 0) | (accumulate ? 0b01 : 0)) {
                case 0b00 -> InstructionKind.UMULL;
                case 0b01 -> InstructionKind.UMLAL;
                case 0b10 -> InstructionKind.SMULL;
                case 0b11 -> InstructionKind.SMLAL;
                default -> throw new IllegalStateException("Unexpected long multiply mode");
            };
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, kind,
                    rdLow, rm, rs, rdHigh, false, setFlags, false);
        }

        if ((raw & 0x0FC0_00F0) == 0x0000_0090) {
            boolean accumulate = (raw & (1 << 21)) != 0;
            boolean setFlags = (raw & (1 << 20)) != 0;
            int rd = (raw >>> 16) & 0xF;
            int rn = (raw >>> 12) & 0xF;
            int rs = (raw >>> 8) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    accumulate ? InstructionKind.MLA : InstructionKind.MUL,
                    rd, rm, rs, rn, false, setFlags, false);
        }

        if ((raw & 0x0E00_0090) == 0x0000_0090) {
            boolean immediateOffset = (raw & (1 << 22)) != 0;
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean writeback = (raw & (1 << 21)) != 0;
            boolean load = (raw & (1 << 20)) != 0;
            int transferKind = (raw >>> 5) & 0x3;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int offset = immediateOffset ? ((raw >>> 4) & 0xF0) | (raw & 0xF) : raw & 0xF;
            // LDRD/STRD (ARMv5TE): L=0 with transferKind 10 (LDRD) or 11 (STRD). Checked before the
            // generic store rejection below; only when the architecture has the feature.
            if (!load && (transferKind == 0b10 || transferKind == 0b11) && architecture.has(ArmFeature.LDRD_STRD)) {
                boolean isLoad = transferKind == 0b10; // 10 = LDRD, 11 = STRD
                int signed = addOffset ? offset : -offset;
                return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.DOUBLE_TRANSFER,
                        rd, rn, immediateOffset ? -1 : offset, signed, immediateOffset, false, isLoad,
                        8, false, writeback || !preIndexed, !preIndexed);
            }
            if (!load && transferKind != 0b01) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            if (!immediateOffset && (raw & 0x0F00) != 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int signedOffset = addOffset ? offset : -offset;
            int sizeBytes = switch (transferKind) {
                case 0b01 -> 2;
                case 0b10 -> 1;
                case 0b11 -> 2;
                default -> -1;
            };
            if (sizeBytes < 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, rn, immediateOffset ? -1 : offset, signedOffset, immediateOffset, false, false,
                    sizeBytes, transferKind != 0b01, writeback || !preIndexed, !preIndexed);
        }

        if ((raw & 0x0C00_0000) == 0x0400_0000) {
            boolean immediateOffset = (raw & (1 << 25)) == 0;
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean byteAccess = (raw & (1 << 22)) != 0;
            boolean writeback = (raw & (1 << 21)) != 0;
            boolean load = (raw & (1 << 20)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int offset = immediateOffset ? raw & 0xFFF : raw & 0xF;
            if (!immediateOffset && (raw & 0x10) != 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int signedOffset = addOffset ? offset : -offset;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, rn, immediateOffset ? -1 : offset, signedOffset, immediateOffset, false, false,
                    byteAccess ? 1 : 4, false, writeback || !preIndexed, !preIndexed);
        }

        if ((raw & 0x0E00_0000) == 0x0800_0000) {
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean userMode = (raw & (1 << 22)) != 0;
            boolean writeback = (raw & (1 << 21)) != 0;
            boolean load = (raw & (1 << 20)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int mask = raw & 0xFFFF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    load ? InstructionKind.LOAD_MULTIPLE : InstructionKind.STORE_MULTIPLE,
                    -1, rn, -1, mask, true, false, userMode, 4, false, writeback,
                    false, BlockTransferMode.fromArmBits(preIndexed, addOffset), mask == 0);
        }

        if ((raw & 0x0C00_0000) == 0) {
            boolean immediate = (raw & (1 << 25)) != 0;
            if (!immediate && (raw & (1 << 4)) != 0 && (raw & (1 << 7)) != 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int opcode = (raw >>> 21) & 0xF;
            boolean setFlags = (raw & (1 << 20)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int operand = immediate ? rotateRight(raw & 0xFF, ((raw >>> 8) & 0xF) * 2) : raw & 0xF;

            return switch (opcode) {
                case 0x0 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.AND,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x1 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.EOR,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x3 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.RSB,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x5 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.ADC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x6 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SBC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x7 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.RSC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x8 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.TST,
                        rd, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0x9 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.TEQ,
                        rd, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0xB -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.CMN,
                        rd, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0xD -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MOV,
                        rd, -1, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x4 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.ADD,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x2 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SUB,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0xA -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.CMP,
                        rd, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0xC -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.ORR,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0xE -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.BIC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0xF -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MVN,
                        rd, -1, immediate ? -1 : operand, operand, immediate, setFlags, false);
                default -> DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            };
        }

        // Instruction groups a higher architecture adds (e.g. the ARMv5 BLX/DSP space)
        // plug in here without touching the shared decoder. Empty on ARMv4T/ARMv5TE today.
        for (DecoderExtension extension : architecture.decoderExtensions()) {
            DecodedInstruction decoded = extension.tryDecode(raw, address, condition);
            if (decoded != null) {
                return decoded;
            }
        }
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    /// Converte o nibble de condição ARM para `Condition`.
    public static Condition decodeCondition(int bits) {
        return switch (bits & 0xF) {
            case 0x0 -> Condition.EQ;
            case 0x1 -> Condition.NE;
            case 0x2 -> Condition.CS;
            case 0x3 -> Condition.CC;
            case 0x4 -> Condition.MI;
            case 0x5 -> Condition.PL;
            case 0x6 -> Condition.VS;
            case 0x7 -> Condition.VC;
            case 0x8 -> Condition.HI;
            case 0x9 -> Condition.LS;
            case 0xA -> Condition.GE;
            case 0xB -> Condition.LT;
            case 0xC -> Condition.GT;
            case 0xD -> Condition.LE;
            default -> Condition.AL;
        };
    }

    private static int rotateRight(int value, int amount) {
        return Integer.rotateRight(value, amount);
    }

    private static int signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }
}
