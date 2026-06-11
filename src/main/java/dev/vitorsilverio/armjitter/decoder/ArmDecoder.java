package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decoder ARM32 inicial para o caminho interpretado frio.
public final class ArmDecoder implements InstructionDecoder {
    private final ArmArchitecture architecture;

    /// Decoder para a arquitetura base (ARMv4T / GBA).
    public ArmDecoder() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Decoder ligado a uma arquitetura: instrucoes ARMv5+ (CLZ, etc.) so sao
    /// decodificadas se a arquitetura as suporta; o resto cai para UNIMPLEMENTED.
    public ArmDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma instrucao ARM32 no endereco informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.read32(address & ~3);
        Condition condition = decodeCondition(raw >>> 28);

        if ((raw & 0x0F00_0000) == 0x0F00_0000) {
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SWI,
                    -1, -1, -1, (raw & 0x00FF_FFFF) >> 16, true, false, false);
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

        if ((raw & 0x0FFF_0FF0) == 0x016F_0F10) {
            if (!architecture.has(ArmFeature.CLZ)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.CLZ,
                    rd, rm, -1, 0, false, false, false);
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

    /// Converte o nibble de condicao ARM para `Condition`.
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
