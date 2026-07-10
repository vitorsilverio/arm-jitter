package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decoder THUMB16 inicial para o caminho interpretado frio.
public final class ThumbDecoder implements InstructionDecoder {
    private final ArmArchitecture architecture;

    /// Decoder THUMB para a arquitetura base (ARMv4T / GBA).
    public ThumbDecoder() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Decoder THUMB ligado a uma arquitetura (para futuros gates Thumb BLX / Thumb-2).
    public ThumbDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma instrução THUMB16 no endereço informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.read16(address & ~1) & 0xFFFF;

        if ((raw & 0xE000) == 0x0000) {
            int op = (raw >>> 11) & 0x3;
            int offset = (raw >>> 6) & 0x1F;
            int rs = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            InstructionKind kind = switch (op) {
                case 0 -> InstructionKind.LSL;
                case 1 -> InstructionKind.LSR;
                case 2 -> InstructionKind.ASR;
                default -> InstructionKind.UNIMPLEMENTED;
            };
            if (kind != InstructionKind.UNIMPLEMENTED) {
                return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, kind,
                        rd, rs, -1, offset, true, true, false);
            }
        }

        if ((raw & 0xF800) == 0x1800) {
            boolean immediate = (raw & (1 << 10)) != 0;
            boolean subtract = (raw & (1 << 9)) != 0;
            int rnOrImm = (raw >>> 6) & 0x7;
            int rs = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    subtract ? InstructionKind.SUB : InstructionKind.ADD,
                    rd, rs, immediate ? -1 : rnOrImm, rnOrImm, immediate, true, false);
        }

        if ((raw & 0xF800) == 0x2000) {
            int rd = (raw >>> 8) & 0x7;
            int imm = raw & 0xFF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.MOV,
                    rd, -1, -1, imm, true, true, false);
        }

        if ((raw & 0xF800) == 0x3000) {
            int rd = (raw >>> 8) & 0x7;
            int imm = raw & 0xFF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ADD,
                    rd, rd, -1, imm, true, true, false);
        }

        if ((raw & 0xF800) == 0x2800) {
            int rn = (raw >>> 8) & 0x7;
            int imm = raw & 0xFF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.CMP,
                    -1, rn, -1, imm, true, true, false);
        }

        if ((raw & 0xF800) == 0x3800) {
            int rd = (raw >>> 8) & 0x7;
            int imm = raw & 0xFF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.SUB,
                    rd, rd, -1, imm, true, true, false);
        }

        if ((raw & 0xFC00) == 0x4000) {
            int op = (raw >>> 6) & 0xF;
            int rs = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return switch (op) {
                case 0x0 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.AND,
                        rd, rd, rs, 0, false, true, false);
                case 0x1 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.EOR,
                        rd, rd, rs, 0, false, true, false);
                case 0x2 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LSL,
                        rd, rd, rs, 0, false, true, false);
                case 0x3 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LSR,
                        rd, rd, rs, 0, false, true, false);
                case 0x4 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ASR,
                        rd, rd, rs, 0, false, true, false);
                case 0x5 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ADC,
                        rd, rd, rs, 0, false, true, false);
                case 0x6 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.SBC,
                        rd, rd, rs, 0, false, true, false);
                case 0x7 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ROR,
                        rd, rd, rs, 0, false, true, false);
                case 0x8 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.TST,
                        -1, rd, rs, 0, false, true, false);
                case 0x9 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.NEG,
                        rd, -1, rs, 0, false, true, false);
                case 0xA -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.CMP,
                        -1, rd, rs, 0, false, true, false);
                case 0xB -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.CMN,
                        -1, rd, rs, 0, false, true, false);
                case 0xD -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.MUL,
                        rd, rd, rs, 0, false, true, false);
                case 0xE -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.BIC,
                        rd, rd, rs, 0, false, true, false);
                case 0xF -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.MVN,
                        rd, -1, rs, 0, false, true, false);
                case 0xC -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ORR,
                        rd, rd, rs, 0, false, true, false);
                default -> DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            };
        }

        if ((raw & 0xF000) == 0x5000) {
            int op = (raw >>> 9) & 0x7;
            int ro = (raw >>> 6) & 0x7;
            int rb = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return switch (op) {
                case 0x0 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.STORE,
                        rd, rb, ro, 0, false, false, false, 4, false);
                case 0x1 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.STORE,
                        rd, rb, ro, 0, false, false, false, 2, false);
                case 0x2 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.STORE,
                        rd, rb, ro, 0, false, false, false, 1, false);
                case 0x3 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LOAD,
                        rd, rb, ro, 0, false, false, false, 1, true);
                case 0x4 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LOAD,
                        rd, rb, ro, 0, false, false, false, 4, false);
                case 0x5 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LOAD,
                        rd, rb, ro, 0, false, false, false, 2, false);
                case 0x6 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LOAD,
                        rd, rb, ro, 0, false, false, false, 1, false);
                case 0x7 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LOAD,
                        rd, rb, ro, 0, false, false, false, 2, true);
                default -> DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            };
        }

        if ((raw & 0xFF87) == 0x4700) {
            int rm = (raw >>> 3) & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.BRANCH_EXCHANGE,
                    -1, rm, -1, 0, false, false, false);
        }

        // BLX (register): `0100 0111 1 mmmm 000` — like Thumb BX but also links (ARMv5T+).
        if ((raw & 0xFF87) == 0x4780) {
            if (!architecture.has(ArmFeature.BLX)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int rm = (raw >>> 3) & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.BRANCH_EXCHANGE,
                    -1, rm, -1, 0, false, false, true);
        }

        if ((raw & 0xFC00) == 0x4400) {
            int op = (raw >>> 8) & 0x3;
            int highDestination = (raw >>> 7) & 0x1;
            int highSource = (raw >>> 6) & 0x1;
            int rs = ((raw >>> 3) & 0x7) | (highSource << 3);
            int rd = (raw & 0x7) | (highDestination << 3);
            return switch (op) {
                case 0x0 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ADD,
                        rd, rd, rs, 0, false, false, false);
                case 0x1 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.CMP,
                        -1, rd, rs, 0, false, true, false);
                case 0x2 -> new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.MOV,
                        rd, -1, rs, 0, false, false, false);
                default -> DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            };
        }

        if ((raw & 0xF800) == 0x4800) {
            int rd = (raw >>> 8) & 0x7;
            int literalAddress = ((address + 4) & ~3) + ((raw & 0xFF) << 2);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LOAD_LITERAL,
                    rd, -1, -1, literalAddress, true, false, false, 4, false);
        }

        if ((raw & 0xF800) == 0x6000 || (raw & 0xF800) == 0x6800) {
            boolean load = (raw & 0x0800) != 0;
            int offset = ((raw >>> 6) & 0x1F) << 2;
            int rb = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, rb, -1, offset, true, false, false, 4, false);
        }

        if ((raw & 0xF800) == 0x7000 || (raw & 0xF800) == 0x7800) {
            boolean load = (raw & 0x0800) != 0;
            int offset = (raw >>> 6) & 0x1F;
            int rb = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, rb, -1, offset, true, false, false, 1, false);
        }

        if ((raw & 0xF800) == 0x8000 || (raw & 0xF800) == 0x8800) {
            boolean load = (raw & 0x0800) != 0;
            int offset = ((raw >>> 6) & 0x1F) << 1;
            int rb = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, rb, -1, offset, true, false, false, 2, false);
        }

        if ((raw & 0xF800) == 0x9000 || (raw & 0xF800) == 0x9800) {
            boolean load = (raw & 0x0800) != 0;
            int rd = (raw >>> 8) & 0x7;
            int offset = (raw & 0xFF) << 2;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    load ? InstructionKind.LOAD : InstructionKind.STORE,
                    rd, 13, -1, offset, true, false, false, 4, false);
        }

        if ((raw & 0xF800) == 0xA000 || (raw & 0xF800) == 0xA800) {
            boolean useSp = (raw & 0x0800) != 0;
            int rd = (raw >>> 8) & 0x7;
            int offset = (raw & 0xFF) << 2;
            if (!useSp) {
                int addressValue = ((address + 4) & ~3) + offset;
                return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.MOV,
                        rd, -1, -1, addressValue, true, false, false);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ADD,
                    rd, 13, -1, offset, true, false, false);
        }

        if ((raw & 0xFF00) == 0xDE00) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
        }

        if ((raw & 0xF000) == 0xD000 && (raw & 0x0F00) != 0x0F00) {
            Condition condition = ArmDecoder.decodeCondition((raw >>> 8) & 0xF);
            int offset = signExtend(raw & 0xFF, 8) << 1;
            int target = address + 4 + offset;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.BRANCH,
                    -1, -1, -1, target, true, false, false);
        }

        if ((raw & 0xF000) == 0xC000) {
            boolean load = (raw & (1 << 11)) != 0;
            int rb = (raw >>> 8) & 0x7;
            int mask = raw & 0xFF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    load ? InstructionKind.LOAD_MULTIPLE : InstructionKind.STORE_MULTIPLE,
                    -1, rb, -1, mask, true, false, false, 4, false, true, false,
                    BlockTransferMode.IA, mask == 0);
        }

        if ((raw & 0xFF00) == 0xB000) {
            int offset = (raw & 0x7F) << 2;
            int signedOffset = (raw & 0x80) == 0 ? offset : -offset;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.ADD,
                    13, 13, -1, signedOffset, true, false, false);
        }

        if ((raw & 0xFE00) == 0xB400) {
            int mask = raw & 0xFF;
            boolean includeLr = (raw & (1 << 8)) != 0;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.PUSH,
                    -1, -1, -1, mask, true, false, includeLr);
        }

        if ((raw & 0xFE00) == 0xBC00) {
            int mask = raw & 0xFF;
            boolean includePc = (raw & (1 << 8)) != 0;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.POP,
                    -1, -1, -1, mask, true, false, includePc);
        }

        if ((raw & 0xF800) == 0xF000) {
            int highOffset = signExtend(raw & 0x7FF, 11) << 12;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LONG_BRANCH_PREFIX,
                    -1, -1, -1, highOffset, true, false, false);
        }

        if ((raw & 0xF800) == 0xF800) {
            int lowOffset = (raw & 0x7FF) << 1;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LONG_BRANCH_SUFFIX,
                    -1, -1, -1, lowOffset, true, false, true);
        }

        // BLX suffix (H=01): the second half of a long branch that exchanges to ARM (ARMv5T+).
        if ((raw & 0xF800) == 0xE800) {
            if (!architecture.has(ArmFeature.BLX)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int lowOffset = (raw & 0x7FF) << 1;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.LONG_BRANCH_SUFFIX,
                    -1, -1, -1, lowOffset, true, false, true);
        }

        if ((raw & 0xF800) == 0xE000) {
            int offset = signExtend(raw & 0x7FF, 11) << 1;
            int target = address + 4 + offset;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.BRANCH,
                    -1, -1, -1, target, true, false, false);
        }

        if ((raw & 0xFF00) == 0xDF00) {
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.SWI,
                    -1, -1, -1, raw & 0xFF, true, false, false);
        }

        return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
    }

    private static int signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }
}
