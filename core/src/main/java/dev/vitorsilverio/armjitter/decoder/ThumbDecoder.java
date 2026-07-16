package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.ItState;
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

    /// `1011 1111 hint#4 mask#4` (B2.5/B2.4, ARM DDI 0406C A6.2.9/A6.7): mesmo opcode de 16 bits
    /// que a instrução `IT` — `mask==0000` é a forma "hint" (`NOP`/`YIELD`/`WFE`/`WFI`/`SEV`,
    /// B2.5), qualquer `mask` != 0 é `IT` (B2.4). Ambas as formas só existem a partir de ARMv6T2,
    /// então o gate desta task é {@link ArmFeature#THUMB2} (não Thumb-1).
    private static final int HINT_OR_IT_MASK = 0xFF00;
    private static final int HINT_OR_IT_VALUE = 0xBF00;
    private static final int HINT_MASK_FIELD = 0xF;
    private static final int HINT_SELECTOR_SHIFT = 4;
    private static final int HINT_SELECTOR_MASK = 0xF;
    private static final int HINT_WFI = 0x3;

    /// hw2[15:14] == 0b11 identifica a família "branch with link" (BL ou BLX imediato) no segundo
    /// halfword — ARM DDI 0308D Figure 3-9. Fixo independente de J1/J2 (bits[13] e [11], que
    /// carregam o offset estendido, não a categoria da instrução).
    private static final int BRANCH_WITH_LINK_MASK = 0xC000;

    /// `BL`/`BLX` imediato Thumb-2 de 32 bits (B2.6, ARM DDI 0406C A8.8.25): `hw2[12]` distingue
    /// `BL` (`1`) de `BLX` imediato (`0`, gateado por {@link ArmFeature#BLX}) — o mesmo bit que já
    /// distingue os sufixos legados `0xF800-0xFFFF` (BL, `top5=0b11111`) de `0xE800-0xEFFF` (BLX,
    /// `top5=0b11101`): os dois `top5` só diferem no 4º bit do nibble alto, que é `bit12`.
    /// `hw2[15:14]` já é checado por {@link #BRANCH_WITH_LINK_MASK} antes de chegar aqui.
    /// **Nota de alcance**: assim como o caminho legado de dois halfwords que esta instrução
    /// substitui (opção recomendada de B2.6 — "zero semântica nova, só o decode muda"), o offset é
    /// montado só a partir de `S:imm10:imm11:'0'` (sem o truque de extensão
    /// `I1=NOT(J1^S)`/`I2=NOT(J2^S)` do ARM ARM completo) — mesma limitação de alcance que o
    /// código já tinha antes desta task, não introduzida por ela; ver `StandardIrBuilder` onde o
    /// offset é de fato recomputado.
    private static final int BL_VS_BLX_BIT = 1 << 12;

    /// `CBZ`/`CBNZ` (B2.4, ARM DDI 0406C A6.7): `1011 nz:1 0 i:1 1 imm5:5 rn:3`. Máscara/valor dos
    /// bits fixos (15:12=`1011`, 10=`0`, 8=`1`) — `nz`(11), `i`(9), `imm5`(7:3) e `rn`(2:0) variam.
    private static final int CBZ_FIXED_MASK = 0xF500;
    private static final int CBZ_FIXED_VALUE = 0xB100;
    private static final int CBZ_NZ_BIT = 11;
    private static final int CBZ_I_BIT = 9;
    private static final int CBZ_IMM5_SHIFT = 3;
    private static final int CBZ_IMM5_MASK = 0x1F;
    private static final int CBZ_RN_MASK = 0x7;

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

        // Hints Thumb-2 de 16 bits (B2.5): NOP/YIELD/WFE/WFI/SEV. `mask!=0000` (mesmo opcode) é
        // `IT` (B2.4).
        if (architecture.has(ArmFeature.THUMB2) && (raw & HINT_OR_IT_MASK) == HINT_OR_IT_VALUE) {
            int mask = raw & HINT_MASK_FIELD;
            if (mask == 0) {
                int hintSelector = (raw >>> HINT_SELECTOR_SHIFT) & HINT_SELECTOR_MASK;
                if (hintSelector == HINT_WFI) {
                    if (!architecture.has(ArmFeature.WAIT_HINTS)) {
                        return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
                    }
                    return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                            InstructionKind.WAIT_FOR_INTERRUPT, -1, -1, -1, 0, false, false, false);
                }
                // NOP/YIELD/SEV e todo seletor reservado ("comporta-se como NOP"): sem efeito
                // observável, mesmo caminho (MSR(#imm)->CPSR com máscara vazia) usado pela forma
                // Thumb-2 de 32 bits em Thumb2MiscDecoder — ver javadoc lá para a justificativa.
                return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.MSR,
                        0, -1, -1, 0, true, false, false);
            }
            // `IT firstcond,mask` (B2.4, ARM DDI 0406C A6.7 `IT`): `firstcond` = bits[7:4],
            // `mask` (já extraído acima) = bits[3:0]. `firstcond==0b1111` (NV) é CONSTRAINED
            // UNPREDICTABLE, normalizado para AL (0b1110) — ver ItState#normalizeFirstCond e o
            // item 2 dos "Fatos de referência" da spec desta task. O `immediate` carrega o
            // ITSTATE de ENTRADA já montado (`firstcond:mask`); o LIFTER (não este decoder,
            // stateless) consome esse valor para semear/threading o estado local das até 4
            // instruções seguintes (D1).
            int firstCond = (raw >>> HINT_SELECTOR_SHIFT) & HINT_SELECTOR_MASK;
            int normalizedFirstCond = ItState.normalizeFirstCond(firstCond);
            int itStateEntry = ItState.entryState(normalizedFirstCond, mask);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL, InstructionKind.IT,
                    -1, -1, -1, itStateEntry, false, false, false);
        }

        // `CBZ`/`CBNZ` (Thumb-1, B2.4, ARM DDI 0406C A6.7): `1011 nz:1 0 i:1 1 imm5:5 rn:3` —
        // `nz` (bit 11) seleciona CBNZ(1)/CBZ(0); offset = `(i<<6)|(imm5<<1)` (sempre par,
        // 0..126); `rn` restrito a R0-R7 pelo campo de 3 bits. Checado ANTES do espaço genérico
        // 0xF800==0xF000/0xF800/0xE800 (branches longos) e do bloco de hints/IT acima (nenhum
        // overlap: top byte aqui é sempre 0xB1/0xB3/0xB9/0xBB, distinto de 0xB0 (`ADD SP,#imm`)
        // e 0xB4-0xB5/0xBC-0xBD (`PUSH`/`POP`)).
        if (architecture.has(ArmFeature.THUMB2) && (raw & CBZ_FIXED_MASK) == CBZ_FIXED_VALUE) {
            boolean nonZero = (raw & (1 << CBZ_NZ_BIT)) != 0;
            boolean iBit = (raw & (1 << CBZ_I_BIT)) != 0;
            int imm5 = (raw >>> CBZ_IMM5_SHIFT) & CBZ_IMM5_MASK;
            int rn = raw & CBZ_RN_MASK;
            int offset = (iBit ? (1 << 6) : 0) | (imm5 << 1);
            int target = address + 4 + offset;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.COMPARE_BRANCH_ZERO, -1, rn, -1, target, false, false, nonZero);
        }

        // Estes três checks (0xF000/0xF800/0xE800) só são alcançáveis sem ArmFeature#THUMB2 — com
        // THUMB2 ativo, `decode()` acima já intercepta `top5 ∈ {0b11101, 0b11110, 0b11111}` antes
        // de chegar aqui: o par BL/BLX vira uma instrução única de 32 bits em `tryDecodeThumb32`
        // (B2.6), e um sufixo isolado (endereço no meio de um BL/BLX genuíno) nunca mais decodifica
        // como `LONG_BRANCH_SUFFIX` — vira UNDEFINED controlado (indecidível sem contexto, ver o
        // javadoc de `tryDecodeThumb32`).
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

    /// Tenta decodificar um candidato Thumb-2 de 32 bits. Nunca devolve `null` (B2.6): todo
    /// candidato reconhecido por {@link #isThumb32Candidate} resolve para uma instrução concreta
    /// ou UNDEFINED controlado, buscando os dois halfwords atomicamente.
    ///
    /// - `hw2[15:14] == 0b11` (`BRANCH_WITH_LINK_MASK`) identifica `BL`/`BLX` imediato — ARM DDI
    ///   0406C A8.8.25 — independente de `top5` (o encoding cobre os três valores de `top5`, mas na
    ///   prática só `0b11110`/`0b11111` produzem `hw2[15:14]==0b11` com um `hw1` válido; ver
    ///   Armadilhas de B2.6). Decodificado aqui mesmo como instrução ÚNICA de 32 bits
    ///   (`InstructionKind.LONG_BRANCH_32`) — ANTES de consultar qualquer extensão registrada, e
    ///   ANTES do check `top5==0b11110` que despacha às extensões. Isto é o fix de B2.6: em
    ///   ARMv6T2+ o par `BL`/`BLX` é arquiteturalmente uma única instrução de 4 bytes (a execução
    ///   "meio a meio" com interrupção entre os halfwords só é válida até ARMv6) — com isso,
    ///   `decode()` nunca mais é chamado no endereço do halfword de sufixo em código são, o
    ///   "fantasma" documentado no histórico desta classe deixa de existir, e as extensões podem
    ///   reivindicar `0xF800-0xFFFF`/`0xE800-0xEFFF` livremente sem colisão.
    /// - Fora do caso acima, despacha às extensões registradas normalmente.
    /// - Quando nenhuma extensão reivindica (nem via `tryDecode` nem via
    ///   {@link DecoderExtension#claimsEncodingSpace}), o candidato vira UNDEFINED controlado
    ///   incondicionalmente — B2.6 revoga a delegação ao caminho legado de 16 bits que existia até
    ///   aqui para `top5 ∈ {0b11101, 0b11111}` (ela só existia por causa do "fantasma" do sufixo,
    ///   que não existe mais). Cair no meio de um `BL` genuíno (branch para `endereço+2`) é
    ///   UNPREDICTABLE no hardware real; UNDEFINED aqui é uma melhoria, não regressão.
    private DecodedInstruction tryDecodeThumb32(AddressSpace memory, int address, int hi, int top5) {
        int lo = memory.read16((address + 2) & ~1) & 0xFFFF;
        int raw32 = (hi << 16) | lo;
        // `hw1==11110...` (top5==0b11110) é o ÚNICO encoding real de hw1 para BL/BLX Thumb-2 (ARM
        // DDI 0406C A8.8.25: `hw1 = 11110 S imm10`). O check em `lo` (BRANCH_WITH_LINK_MASK) só
        // pode desambiguar DENTRO desse espaço — sem o guard de `top5`, ele reivindicaria
        // erroneamente candidatos de `top5 ∈ {0b11101, 0b11111}` cujo SEGUNDO halfword também
        // tenha bits[15:14]==0b11 por coincidência (ex. TBB/TBH, `hi=0xE8D0-0xE8DF`/
        // `lo=0xF000-0xF01F` — `lo` bate com a máscara mas `hi` não é `0b11110`).
        if (top5 == 0b11110 && (lo & BRANCH_WITH_LINK_MASK) == BRANCH_WITH_LINK_MASK) {
            return decodeLongBranch32(address, hi, lo, raw32);
        }
        DecodedInstruction decoded = dispatchThumb32Extensions(address, raw32);
        if (decoded != null) {
            return decoded;
        }
        // Candidato Thumb-2 genuíno sem extensão que implemente esse sub-encoding específico —
        // UNDEFINED controlado (G1: sem exceção de "extensão ausente").
        return DecodedInstruction.unimplemented(address, raw32, InstructionSet.THUMB, Condition.AL);
    }

    /// Decodifica `BL`/`BLX` imediato Thumb-2 como instrução única de 32 bits (B2.6). Os demais
    /// campos de `DecodedInstruction` não carregam o offset/link — `StandardIrBuilder` recomputa
    /// tudo a partir de `raw` (os dois halfwords), reproduzindo exatamente o par
    /// `ThumbBlPrefix`+`ThumbBlSuffix` que o caminho legado de dois halfwords já produzia.
    /// `BLX` imediato (`hw2[14]==0`) é gateado por {@link ArmFeature#BLX}, igual ao sufixo
    /// `0xE800` legado.
    private DecodedInstruction decodeLongBranch32(int address, int hi, int lo, int raw32) {
        boolean isBlx = (lo & BL_VS_BLX_BIT) == 0;
        if (isBlx && !architecture.has(ArmFeature.BLX)) {
            return DecodedInstruction.unimplemented(address, raw32, InstructionSet.THUMB, Condition.AL);
        }
        return new DecodedInstruction(address, raw32, InstructionSet.THUMB, Condition.AL,
                InstructionKind.LONG_BRANCH_32, -1, -1, -1, 0, false, false, true);
    }

    private DecodedInstruction dispatchThumb32Extensions(int address, int raw32) {
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
