package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decoder ARM32 inicial para o caminho interpretado frio.
public final class ArmDecoder implements InstructionDecoder {
    /// Decodifica uma instrucao ARM32 no endereco informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.read32(address);
        Condition condition = decodeCondition(raw >>> 28);

        if ((raw & 0x0F00_0000) == 0x0F00_0000) {
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SWI,
                    -1, -1, -1, raw & 0x00FF_FFFF, true, false, false);
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

        if ((raw & 0x0FC0_00F0) == 0x0000_0090) {
            boolean accumulate = (raw & (1 << 21)) != 0;
            if (accumulate) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            boolean setFlags = (raw & (1 << 20)) != 0;
            int rd = (raw >>> 16) & 0xF;
            int rs = (raw >>> 8) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MUL,
                    rd, rm, rs, 0, false, setFlags, false);
        }

        if ((raw & 0x0E00_0090) == 0x0000_0090 && (raw & (1 << 22)) != 0) {
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean load = (raw & (1 << 20)) != 0;
            int transferKind = (raw >>> 5) & 0x3;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int offset = ((raw >>> 4) & 0xF0) | (raw & 0xF);
            if (!preIndexed || (!load && transferKind != 0b01)) {
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
                    rd, rn, -1, signedOffset, true, false, false, sizeBytes, transferKind != 0b01);
        }

        if ((raw & 0x0C00_0000) == 0x0400_0000) {
            boolean immediateOffset = (raw & (1 << 25)) == 0;
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean byteAccess = (raw & (1 << 22)) != 0;
            boolean load = (raw & (1 << 20)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int offset = raw & 0xFFF;
            if (!immediateOffset || !preIndexed) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int signedOffset = addOffset ? offset : -offset;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, rn, -1, signedOffset, true, false, false, byteAccess ? 1 : 4, false);
        }

        if ((raw & 0x0E00_0000) == 0x0800_0000) {
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean writeback = (raw & (1 << 21)) != 0;
            boolean load = (raw & (1 << 20)) != 0;
            int rn = (raw >>> 16) & 0xF;
            int mask = raw & 0xFFFF;
            if (preIndexed || !addOffset || mask == 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    load ? InstructionKind.LOAD_MULTIPLE : InstructionKind.STORE_MULTIPLE,
                    -1, rn, -1, mask, true, false, false, 4, false, writeback);
        }

        if ((raw & 0x0C00_0000) == 0) {
            boolean immediate = (raw & (1 << 25)) != 0;
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
                case 0x5 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.ADC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x6 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SBC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x8 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.TST,
                        -1, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0x9 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.TEQ,
                        -1, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0xB -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.CMN,
                        -1, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0xD -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MOV,
                        rd, -1, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x4 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.ADD,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0x2 -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SUB,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0xA -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.CMP,
                        -1, rn, immediate ? -1 : operand, operand, immediate, true, false);
                case 0xC -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.ORR,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0xE -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.BIC,
                        rd, rn, immediate ? -1 : operand, operand, immediate, setFlags, false);
                case 0xF -> new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MVN,
                        rd, -1, immediate ? -1 : operand, operand, immediate, setFlags, false);
                default -> DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            };
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
