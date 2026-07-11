package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
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

    /// Máscara dos bits[15:11] do primeiro halfword — usada para achar `top5` e comparar com
    /// {@link #isThumb32Candidate(int)} (candidato a instrução Thumb de 32 bits, ARM DDI 0308D
    /// Table 3-1).
    private static final int TOP5_MASK = 0xF800;
    private static final int TOP5_SHIFT = 11;

    /// hw2[15:14] == 0b11 identifica a família "branch with link" (BL ou BLX imediato) no segundo
    /// halfword — ARM DDI 0308D Figure 3-9. Fixo independente de J1/J2 (bits[13] e [11], que
    /// carregam o offset estendido, não a categoria da instrução).
    private static final int BRANCH_WITH_LINK_MASK = 0xC000;

    /// Decodifica uma instrução THUMB16 (ou, com {@link ArmFeature#THUMB2}, um candidato Thumb de
    /// 32 bits) no endereço informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.read16(address & ~1) & 0xFFFF;
        int top5 = (raw & TOP5_MASK) >>> TOP5_SHIFT;

        if (architecture.has(ArmFeature.THUMB2) && isThumb32Candidate(top5)) {
            DecodedInstruction thumb32 = tryDecodeThumb32(memory, address, raw, top5);
            if (thumb32 != null) {
                return thumb32;
            }
            // null == nenhuma extensão Thumb-2 reivindicou o encoding: cai no caminho legado
            // ARMv4T/v5T de BL/BLX em dois halfwords (prefixo 0xF000 + sufixo 0xF800/0xE800)
            // abaixo, inalterado byte a byte.
        }

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

    /// `0b11101`, `0b11110`, `0b11111`: os únicos valores de hw1[15:11] usados por instruções
    /// Thumb de 32 bits (Table 3-1). Todo resto do espaço é 16-bit.
    private static boolean isThumb32Candidate(int top5) {
        return top5 == 0b11101 || top5 == 0b11110 || top5 == 0b11111;
    }

    /// Tenta decodificar um candidato Thumb-2 de 32 bits. Retorna `null` quando o chamador deve
    /// cair no caminho legado de 16 bits (prefixo/sufixo BL/BLX de dois halfwords).
    ///
    /// - `top5 == 0b11110` é o único caso ambíguo com o espaço legado (armadilha principal desta
    ///   task, ver B2.1): o hardware real decide "é BL/BLX ou é Thumb-2 genuíno" pelos bits
    ///   [15:14] do **segundo** halfword, não pelo primeiro — `BRANCH_WITH_LINK_MASK` reproduz
    ///   essa regra (Figure 3-9). Quando bate, devolvemos `null`: o prefixo é decodificado
    ///   exatamente como hoje (`LONG_BRANCH_PREFIX`, 2 bytes) e o sufixo em `address+2` continua
    ///   caindo nos checks 0xF800/0xE800 já existentes mais abaixo, inalterados.
    /// - `top5 == 0b11101` / `0b11111` hoje só existem, na prática, como o segundo halfword desse
    ///   mesmo par legado (Load/store double+exclusive+table-branch e Load/store single+hints+
    ///   coprocessor genuinamente Thumb-2 chegam em B2.2/B2.3) — por isso continuam delegando
    ///   ao caminho legado sempre que nenhuma extensão reivindica o encoding.
    private DecodedInstruction tryDecodeThumb32(AddressSpace memory, int address, int hi, int top5) {
        int lo = memory.read16((address + 2) & ~1) & 0xFFFF;
        if (top5 == 0b11110 && (lo & BRANCH_WITH_LINK_MASK) == BRANCH_WITH_LINK_MASK) {
            return null;
        }
        DecodedInstruction decoded = dispatchThumb32Extensions(address, hi, lo);
        if (decoded != null) {
            return decoded;
        }
        if (top5 == 0b11110) {
            // Fora do espaço BL/BLX (Data processing: immediate, ou Branches/misc que não seja
            // "branch with link"): candidato Thumb-2 genuíno sem extensão registrada ainda —
            // UNDEFINED controlado, buscando os dois halfwords atomicamente (G1: sem exceção de
            // "extensão ausente").
            return DecodedInstruction.unimplemented(address, (hi << 16) | lo, InstructionSet.THUMB, Condition.AL);
        }
        return null;
    }

    private DecodedInstruction dispatchThumb32Extensions(int address, int hi, int lo) {
        int raw32 = (hi << 16) | lo;
        for (DecoderExtension extension : architecture.thumb32DecoderExtensions()) {
            DecodedInstruction decoded = extension.tryDecode(raw32, address, Condition.AL);
            if (decoded != null) {
                return decoded;
            }
        }
        return null;
    }

    private static int signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }
}
