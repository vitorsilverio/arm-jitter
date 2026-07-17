package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica o grupo "Data-processing" Thumb-2 de 32 bits (B2.2): modified immediate,
/// `MOVW`/`MOVT`, plain binary immediate (`ADD`/`SUB`/`ADR`) e a forma registrador com shift
/// imediato. Anexado via {@link dev.vitorsilverio.armjitter.arch.ArmArchitecture#thumb32DecoderExtensions()}
/// — só ativo quando {@link dev.vitorsilverio.armjitter.arch.ArmFeature#THUMB2} está habilitado.
///
/// B2.7 (PR1) acrescenta `SSAT`/`USAT`/`SSAT16`/`USAT16` ao mesmo grupo "plain binary immediate"
/// (`top5=0b11110`, `hi[9]==1`) — mesmo espaço arquitetural de `ADD`/`SUB`/`MOVW`/`MOVT`, gateado
/// por {@link ArmFeature#PACK_SATURATE} (a mesma feature do ARM clássico `SSAT`/`USAT`). Por isso
/// este decoder passou a precisar de uma {@link ArmArchitecture} — mesmo padrão de
/// `Thumb2LoadStoreDecoder`/`Thumb2MiscDecoder` desde B2.3/B2.5.
///
/// B3.2 acrescenta `SBFX`/`UBFX`/`BFI`/`BFC` ao MESMO grupo "plain binary immediate", gateados por
/// {@link ArmFeature#BIT_FIELD} (a mesma feature do encoding ARM clássico equivalente em
/// {@link ArmDecoder}) — zero IR nova, reusa `InstructionKind#BIT_FIELD_EXTRACT`/`BIT_FIELD_INSERT`.
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`, hi =
/// primeiro halfword nos bits altos, exatamente como {@link ThumbDecoder} entrega às extensões
/// de 32 bits). Referência: ARM DDI 0406C, A5.3.1-A5.3.4; QEMU `target/arm/tcg/t32.decode`
/// seção "Saturate, bitfield" para `SSAT`/`USAT`/`SSAT16`/`USAT16`/`SBFX`/`UBFX`/`BFI`/`BFC`.
public final class Thumb2DataProcessingDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Constrói o decoder ligado à arquitetura corrente, usada para gatear `SSAT`/`USAT`/
    /// `SSAT16`/`USAT16` por {@link ArmFeature#PACK_SATURATE} (B2.7).
    public Thumb2DataProcessingDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }
    /// Prefixo literal de 7 bits (`raw[31:25]`) do grupo "Data-processing (register)" com shift
    /// imediato — ARM DDI 0406C Figure 3-8 / A5.3.1. Subconjunto de `top5 == 0b11101`.
    private static final int REGISTER_FORM_PREFIX = 0b1110101;
    private static final int REGISTER_FORM_PREFIX_MASK = 0x7F;
    private static final int REGISTER_FORM_PREFIX_SHIFT = 25;

    /// `raw[31:27]` do grupo "Data-processing (immediate)"/"(plain binary immediate)"/`MOVW`/
    /// `MOVT` — todos vivem sob `top5 == 0b11110`.
    private static final int IMMEDIATE_GROUP_TOP5 = 0b11110;
    private static final int TOP5_MASK = 0x1F;
    private static final int TOP5_SHIFT = 27;

    /// `raw[25]` (hi bit 9): `0` = "Data-processing (modified immediate)" (A5.3.3), `1` =
    /// "(plain binary immediate)"/`MOVW`/`MOVT` (A5.3.4).
    private static final int MODIFIED_IMMEDIATE_MARKER_BIT = 25;

    /// `raw[24:21]` do subgrupo `raw[25]==1`: distingue `ADD`/`SUB`(plain binary)/`MOVW`/`MOVT`
    /// (os 4 únicos valores usados; qualquer outro é reservado/UNDEFINED).
    private static final int PLAIN_GROUP_OP_SHIFT = 21;
    private static final int PLAIN_GROUP_OP_MASK = 0xF;
    private static final int PLAIN_OP_ADD = 0b0000;
    private static final int PLAIN_OP_SUB = 0b0101;
    private static final int PLAIN_OP_MOVW = 0b0010;
    private static final int PLAIN_OP_MOVT = 0b0110;
    /// `raw[24:21]` (B2.7): `SSAT`/`USAT` com deslocamento LSL (`sh`=0) — QEMU `t32.decode`
    /// "Saturate, bitfield" `SSAT`/`USAT`. Bit 24 (MSB deste grupo) sempre 1 aqui, distinto de
    /// AND/BIC/ORR/.../MOVW/MOVT acima (bit 24 sempre 0 nesses).
    private static final int PLAIN_OP_SSAT_LSL = 0b1000;
    /// `raw[24:21]`: `SSAT`/`USAT` com deslocamento ASR (`sh`=1) — OU `SSAT16`/`USAT16` quando o
    /// campo de shift (`imm3`/`imm2`) é totalmente zero (mesmo padrão de bits, arquiteturalmente
    /// ambíguo por construção — resolvido em {@link #decodeSaturate}, mesma prioridade do QEMU).
    private static final int PLAIN_OP_SSAT_ASR_OR_16 = 0b1001;
    private static final int PLAIN_OP_USAT_LSL = 0b1100;
    private static final int PLAIN_OP_USAT_ASR_OR_16 = 0b1101;
    /// `raw[24:21]` (B3.2): `SBFX`/`UBFX`/`BFI`/`BFC` — mesmo grupo "plain binary immediate"
    /// (QEMU `t32.decode` seção "Saturate, bitfield"). Valores confirmados bit a bit contra o
    /// oráculo (ver javadoc da classe/{@link #decodeBitFieldExtractIfSupported}), não deduzidos.
    private static final int PLAIN_OP_SBFX = 0b1010;
    private static final int PLAIN_OP_BFI_BFC = 0b1011;
    private static final int PLAIN_OP_UBFX = 0b1110;

    /// `raw[24:21]` (op4) do grupo modified-immediate/register-shift-immediate — mesma tabela nos
    /// dois grupos (ARM DDI 0406C Table A5-10 "op field"), com as formas TST/TEQ/CMN/CMP obtidas
    /// via `Rd == PC` (registrador fixo `1111`, não um destino real).
    private static final int OP4_SHIFT = 21;
    private static final int OP4_MASK = 0xF;
    private static final int OP4_AND = 0b0000;
    private static final int OP4_BIC = 0b0001;
    private static final int OP4_ORR = 0b0010;
    private static final int OP4_ORN = 0b0011;
    private static final int OP4_EOR = 0b0100;
    private static final int OP4_ADD = 0b1000;
    private static final int OP4_ADC = 0b1010;
    private static final int OP4_SBC = 0b1011;
    private static final int OP4_SUB = 0b1101;
    private static final int OP4_RSB = 0b1110;

    private static final int SET_FLAGS_BIT = 20;
    private static final int PROGRAM_COUNTER = 15;
    private static final int STACK_POINTER = 13;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (isRegisterFormPrefix(raw)) {
            return decodeRegisterForm(raw, address, condition);
        }
        if (((raw >>> TOP5_SHIFT) & TOP5_MASK) != IMMEDIATE_GROUP_TOP5) {
            return null;
        }
        if ((raw & SECOND_HALFWORD_TOP_BIT) != 0) {
            // Bug de infra achado e corrigido na task B2.4: A5.3.3/A5.3.4 (ARM DDI 0406C) fixam
            // `lo[15]==0` para TODAS as formas de "Data-processing (immediate)" deste top5 — este
            // decoder nunca checava esse bit, então engolia silenciosamente todo o espaço
            // `lo[15]==1` do MESMO top5=0b11110, que arquiteturalmente pertence a "Branches and
            // miscellaneous control" (A5.3.4 do grupo de branches — `B.W`/`BL`/`BLX_i`/hints/CPS/
            // barreiras/`MRS`/`MSR`, ver `Thumb2BranchDecoder`/`Thumb2MiscDecoder`). Sem este
            // check, `Thumb2DataProcessingDecoder` decodia incondicionalmente (às vezes como uma
            // ALU real, ex. op4=`AND`) qualquer `B.W` condicional cujo campo `cond` tivesse o bit
            // mais significativo zerado (`cond<8`) — colisão real encontrada pelos testes de
            // `Thumb2BranchesItTest`. `Thumb2MiscDecoder` nunca precisou desta correção porque seus
            // `hi` reconhecidos (0xF3xx) sempre caem no ramo `decodePlainGroup` (`hi[9]==1`), que já
            // rejeitava `op4` não reconhecido — mas `decodeModifiedImmediate` (`hi[9]==0`) não tinha
            // rejeição nenhuma.
            return null;
        }
        if (((raw >>> MODIFIED_IMMEDIATE_MARKER_BIT) & 1) == 0) {
            return decodeModifiedImmediate(raw, address, condition);
        }
        return decodePlainGroup(raw, address, condition);
    }

    /// `lo[15]` (bit 15 do segundo halfword, `raw` bit 15): sempre `0` nas formas de
    /// "Data-processing (immediate)" — ver o comentário acima em {@link #tryDecode}.
    private static final int SECOND_HALFWORD_TOP_BIT = 0x8000;

    /// B2.2.2: `raw` cai dentro do prefixo de 7 bits `REGISTER_FORM_PREFIX` — o único subconjunto
    /// desta classe fora de `top5 == 0b11110` (que o `ThumbDecoder` já trata como UNDEFINED
    /// incondicional quando nenhuma extensão reivindica, sem precisar desta distinção). Usado por
    /// {@link #tryDecode} e por {@link #claimsEncodingSpace}: os `op4` reservados dentro deste
    /// prefixo (ex. `PKH`, formas MVE) devolvem `null` em {@link #decodeRegisterForm} mas ainda
    /// contam como "meu espaço, sub-encoding reservado" para o `ThumbDecoder`.
    private static boolean isRegisterFormPrefix(int raw) {
        return ((raw >>> REGISTER_FORM_PREFIX_SHIFT) & REGISTER_FORM_PREFIX_MASK) == REGISTER_FORM_PREFIX;
    }

    @Override
    public boolean claimsEncodingSpace(int raw) {
        return isRegisterFormPrefix(raw);
    }

    // ── Data-processing (modified immediate) — A5.3.3 ──────────────────────────────────────

    private DecodedInstruction decodeModifiedImmediate(int raw, int address, Condition condition) {
        int op4 = (raw >>> OP4_SHIFT) & OP4_MASK;
        boolean setFlags = ((raw >>> SET_FLAGS_BIT) & 1) != 0;
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int imm12 = modifiedImmediate12(raw);
        int expanded = thumbExpandImm(imm12);

        if (op4 == OP4_ORR && rn == PROGRAM_COUNTER) {
            if (rd == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MOV,
                    rd, -1, -1, expanded, true, setFlags, false);
        }
        if (op4 == OP4_ORN && rn == PROGRAM_COUNTER) {
            if (rd == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MVN,
                    rd, -1, -1, expanded, true, setFlags, false);
        }

        InstructionKind compareAlias = compareAliasFor(op4);
        if (rd == PROGRAM_COUNTER) {
            if (compareAlias == null || !setFlags) {
                // UNPREDICTABLE: escreve em PC sem um alias de comparação válido.
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, compareAlias,
                    -1, rn, -1, expanded, true, true, false);
        }
        if (rn == PROGRAM_COUNTER) {
            // Só ORR/ORN (tratados acima) usam Rn=PC como alias válido (sem acumulador); todo
            // outro op4 lendo PC como Rn é UNPREDICTABLE nesta forma de 32 bits.
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        InstructionKind kind = plainKindFor(op4);
        if (kind == null) {
            return null; // op4 reservado — cai no UNDEFINED controlado do ThumbDecoder (top5=11110)
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                rd, rn, -1, expanded, true, setFlags, false);
    }

    private int modifiedImmediate12(int raw) {
        int i = (raw >>> 26) & 1;
        int imm3 = (raw >>> 12) & 0x7;
        int imm8 = raw & 0xFF;
        return (i << 11) | (imm3 << 8) | imm8;
    }

    /// ThumbExpandImm (ARM DDI 0406C A5.3.2, sem o carry-out — recomputado separadamente por
    /// {@code StandardIrBuilder#operand} a partir dos mesmos bits, espelhando como o rotate-imm
    /// ARM clássico já funciona).
    private static int thumbExpandImm(int imm12) {
        if ((imm12 & 0xC00) == 0) { // imm12<11:10> == '00': sem rotação, replicação de byte
            int abcdefgh = imm12 & 0xFF;
            return switch ((imm12 >>> 8) & 0x3) {
                case 0 -> abcdefgh;
                case 1 -> (abcdefgh << 16) | abcdefgh;
                case 2 -> (abcdefgh << 24) | (abcdefgh << 8);
                default -> (abcdefgh << 24) | (abcdefgh << 16) | (abcdefgh << 8) | abcdefgh;
            };
        }
        int unrotated = 0x80 | (imm12 & 0x7F);
        int rotateAmount = (imm12 >>> 7) & 0x1F;
        return Integer.rotateRight(unrotated, rotateAmount);
    }

    // ── Data-processing (register), shift imediato — A5.3.1 ────────────────────────────────

    private DecodedInstruction decodeRegisterForm(int raw, int address, Condition condition) {
        int op4 = (raw >>> OP4_SHIFT) & OP4_MASK;
        boolean setFlags = ((raw >>> SET_FLAGS_BIT) & 1) != 0;
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (rm == PROGRAM_COUNTER || rm == STACK_POINTER) {
            // Rm=SP/PC é UNPREDICTABLE nesta forma — verificado na task B2.2.1. ARM DDI 0406C
            // A5.3.1: as tabelas de encoding de AND/BIC/ORR/ORN/EOR/ADC/SBC/RSB (register)
            // Thumb T2/T3, e também ADD/SUB (register) T3, declaram uniformemente
            // "if d IN {13,15} || n IN {13,15} || m IN {13,15} then UNPREDICTABLE" — Rm nunca é
            // exceção, mesmo nos casos em que Rd/Rn=SP/PC têm alias dedicado (compare Rd=PC+S=1,
            // MOV/MVN Rn=PC, ADD/SUB (SP plus register) Rn=SP). Confirmado de forma independente
            // pelo decodetree do QEMU (`target/arm/tcg/t32.decode`): o comentário do grupo MVE que
            // colide com este encoding registra explicitamente "Rm==13 or 15 ... UNPREDICTABLE
            // cases for MOVS/ORRS" para a mesma forma. Ao contrário do ARM clássico (onde SP é um
            // registrador comum em operações de dados), aqui SP é tão restrito quanto PC.
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }

        if (op4 == OP4_ORR && rn == PROGRAM_COUNTER) {
            if (rd == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MOV,
                    rd, -1, rm, 0, false, setFlags, false);
        }
        if (op4 == OP4_ORN && rn == PROGRAM_COUNTER) {
            if (rd == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MVN,
                    rd, -1, rm, 0, false, setFlags, false);
        }

        InstructionKind compareAlias = compareAliasFor(op4);
        if (rd == PROGRAM_COUNTER) {
            if (compareAlias == null || !setFlags) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, compareAlias,
                    -1, rn, rm, 0, false, true, false);
        }
        if (rn == PROGRAM_COUNTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        InstructionKind kind = plainKindFor(op4);
        if (kind == null) {
            return null; // op4 reservado/PKH/MVE — fora do escopo desta task (ver Armadilhas)
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                rd, rn, rm, 0, false, setFlags, false);
    }

    // ── Data processing (plain binary immediate) + MOVW/MOVT — A5.3.4 ──────────────────────

    private DecodedInstruction decodePlainGroup(int raw, int address, Condition condition) {
        int group = (raw >>> PLAIN_GROUP_OP_SHIFT) & PLAIN_GROUP_OP_MASK;
        return switch (group) {
            case PLAIN_OP_ADD -> decodeAddSubOrAdr(raw, address, condition, false);
            case PLAIN_OP_SUB -> decodeAddSubOrAdr(raw, address, condition, true);
            case PLAIN_OP_MOVW -> decodeMoveWideIfSupported(raw, address, condition, InstructionKind.MOV);
            case PLAIN_OP_MOVT -> decodeMoveWideIfSupported(raw, address, condition, InstructionKind.MOVE_TOP);
            case PLAIN_OP_SSAT_LSL -> decodeSaturateIfSupported(raw, address, condition, false, false);
            case PLAIN_OP_SSAT_ASR_OR_16 -> decodeSaturateIfSupported(raw, address, condition, false, true);
            case PLAIN_OP_USAT_LSL -> decodeSaturateIfSupported(raw, address, condition, true, false);
            case PLAIN_OP_USAT_ASR_OR_16 -> decodeSaturateIfSupported(raw, address, condition, true, true);
            case PLAIN_OP_SBFX -> decodeBitFieldExtractIfSupported(raw, address, condition, true);
            case PLAIN_OP_UBFX -> decodeBitFieldExtractIfSupported(raw, address, condition, false);
            case PLAIN_OP_BFI_BFC -> decodeBitFieldInsertIfSupported(raw, address, condition);
            default -> null; // reservado — UNDEFINED controlado do ThumbDecoder (top5=11110)
        };
    }

    // ── SBFX/UBFX/BFI/BFC (B3.2) — QEMU "Saturate, bitfield" ────────────────────────────────

    /// `lsb` Thumb-2 é montado a partir de `imm3:imm2` — bits `raw[14:12]:raw[7:6]`, NÃO
    /// contíguo no encoding (armadilha do enunciado; copiado do QEMU `%imm5_12_6`, não deduzido).
    private static int bitFieldLsb(int raw) {
        int imm3 = (raw >>> 12) & 0x7;
        int imm2 = (raw >>> 6) & 0x3;
        return (imm3 << 2) | imm2;
    }

    /// `SBFX`/`UBFX` — QEMU `t32.decode` `@bfx` (`rd`/`rn` no mesmo campo que o grupo modified-
    /// immediate, `widthm1` em `raw[4:0]`). Mesmo empacotamento `lsb | (width &lt;&lt; 5)` e mesma
    /// checagem UNPREDICTABLE (`Rd`/`Rn`=PC, `lsb+width&gt;32`) que {@link ArmDecoder} já usa para
    /// o encoding ARM clássico equivalente — reuso de IR, zero semântica nova (G1).
    private DecodedInstruction decodeBitFieldExtractIfSupported(int raw, int address, Condition condition,
            boolean signedExtract) {
        if (!architecture.has(ArmFeature.BIT_FIELD)) {
            return null; // UNDEFINED controlado do ThumbDecoder (top5=11110), mesmo sem a feature
        }
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int lsb = bitFieldLsb(raw);
        int widthMinusOne = raw & 0x1F;
        int width = widthMinusOne + 1;
        if (rd == PROGRAM_COUNTER || rn == PROGRAM_COUNTER || lsb + width > 32) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int packed = lsb | (width << 5);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.BIT_FIELD_EXTRACT, rd, rn, -1, packed, false, false, false, 0, signedExtract);
    }

    /// `BFI`/`BFC` — QEMU `t32.decode` `@bfi` (mesmo layout de `@bfx`, mas o campo final é `msb`,
    /// não `widthm1` — armadilha do enunciado, os dois formatos convivem na mesma tabela de bits
    /// e não podem ser misturados). `Rn=1111` marca `BFC` (QEMU: "bfc is bfi w/ rn=15").
    private DecodedInstruction decodeBitFieldInsertIfSupported(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.BIT_FIELD)) {
            return null; // UNDEFINED controlado do ThumbDecoder (top5=11110), mesmo sem a feature
        }
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int lsb = bitFieldLsb(raw);
        int msb = raw & 0x1F;
        boolean isBfc = rn == PROGRAM_COUNTER;
        if (rd == PROGRAM_COUNTER || (!isBfc && rn == PROGRAM_COUNTER) || msb < lsb) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int width = msb - lsb + 1;
        int packed = lsb | (width << 5);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.BIT_FIELD_INSERT, rd, isBfc ? -1 : rn, -1, packed, false, false, false);
    }

    private DecodedInstruction decodeSaturateIfSupported(int raw, int address, Condition condition,
            boolean unsigned, boolean asr) {
        if (!architecture.has(ArmFeature.PACK_SATURATE)) {
            return null; // UNDEFINED controlado do ThumbDecoder (top5=11110), mesmo sem a feature
        }
        return decodeSaturate(raw, address, condition, unsigned, asr);
    }

    /// `SSAT`/`USAT` (`asr`=false ⇒ LSL, `asr`=true ⇒ ASR) — ARM DDI 0406C A5.3.11 / QEMU
    /// `t32.decode` `@sat`. Quando `asr` E o campo de shift (`imm3`:`imm2`, 5 bits) é zero, o
    /// bit-pattern é IDÊNTICO ao de `SSAT16`/`USAT16` (QEMU `@sat16`, mesma prioridade dada ao
    /// caso de 16 bits) — não há ambiguidade real de hardware, é a mesma codificação reaproveitada
    /// arquiteturalmente. Reusa o mesmo formato empacotado de {@code IrExecutorBuilder#liftSaturate}
    /// que o `SSAT`/`USAT` ARM clássico já produz (`ArmDecoder`), sem IR nova.
    private DecodedInstruction decodeSaturate(int raw, int address, Condition condition,
            boolean unsigned, boolean asr) {
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int imm3 = (raw >>> 12) & 0x7;
        int imm2 = (raw >>> 6) & 0x3;
        int shiftImm = (imm3 << 2) | imm2;
        int satImm = raw & 0x1F;
        if (asr && shiftImm == 0) {
            int packed16 = (satImm & 0xF) | (1 << 11) | (unsigned ? 1 << 12 : 0);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.SATURATE,
                    rd, -1, rn, packed16, false, false, false);
        }
        int packed = satImm | (shiftImm << 5) | (asr ? 1 << 10 : 0) | (unsigned ? 1 << 12 : 0);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.SATURATE,
                rd, -1, rn, packed, false, false, false);
    }

    private DecodedInstruction decodeAddSubOrAdr(int raw, int address, Condition condition, boolean subtract) {
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int i = (raw >>> 26) & 1;
        int imm3 = (raw >>> 12) & 0x7;
        int imm8 = raw & 0xFF;
        int imm12 = (i << 11) | (imm3 << 8) | imm8;
        if (rn == PROGRAM_COUNTER) {
            // ADR: Rd = Align(PC,4) +/- imm12. PC lê como address+4 mesmo para esta instrução de
            // 32 bits (regra Thumb universal — StandardIrBuilder#registerValueOverride já aplica
            // isso a qualquer leitura de R15 em THUMB), então o valor já pode ser resolvido aqui
            // em tempo de decode, como o ADR THUMB1 legado já faz.
            int literalAddress = ((address + 4) & ~3) + (subtract ? -imm12 : imm12);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MOV,
                    rd, -1, -1, literalAddress, true, false, false);
        }
        if (rd == PROGRAM_COUNTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                subtract ? InstructionKind.SUB : InstructionKind.ADD,
                rd, rn, -1, imm12, true, false, false);
    }

    /// `MOVW`/`MOVT` Thumb-2 (B2.2) passam a exigir {@link ArmFeature#MOVW_MOVT} também (B3.1;
    /// antes gateado só por {@link ArmFeature#THUMB2}) — mesma feature que o carve-out ARM em
    /// {@link ArmDecoder} usa, já que é a MESMA instrução arquitetural (ARMv6T2+).
    private DecodedInstruction decodeMoveWideIfSupported(int raw, int address, Condition condition, InstructionKind kind) {
        if (!architecture.has(ArmFeature.MOVW_MOVT)) {
            return null; // UNDEFINED controlado do ThumbDecoder (top5=11110), mesmo sem a feature
        }
        return decodeMoveWide(raw, address, condition, kind);
    }

    private DecodedInstruction decodeMoveWide(int raw, int address, Condition condition, InstructionKind kind) {
        int rd = (raw >>> 8) & 0xF;
        if (rd == PROGRAM_COUNTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int imm4 = (raw >>> 16) & 0xF;
        int i = (raw >>> 26) & 1;
        int imm3 = (raw >>> 12) & 0x7;
        int imm8 = raw & 0xFF;
        int imm16 = (imm4 << 12) | (i << 11) | (imm3 << 8) | imm8;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                rd, -1, -1, imm16, true, false, false);
    }

    // ── Tabelas compartilhadas modified-immediate / register-shift-immediate ───────────────

    private static InstructionKind compareAliasFor(int op4) {
        return switch (op4) {
            case OP4_AND -> InstructionKind.TST;
            case OP4_EOR -> InstructionKind.TEQ;
            case OP4_ADD -> InstructionKind.CMN;
            case OP4_SUB -> InstructionKind.CMP;
            default -> null;
        };
    }

    private static InstructionKind plainKindFor(int op4) {
        return switch (op4) {
            case OP4_AND -> InstructionKind.AND;
            case OP4_BIC -> InstructionKind.BIC;
            case OP4_ORR -> InstructionKind.ORR;
            case OP4_ORN -> InstructionKind.ORN;
            case OP4_EOR -> InstructionKind.EOR;
            case OP4_ADD -> InstructionKind.ADD;
            case OP4_ADC -> InstructionKind.ADC;
            case OP4_SBC -> InstructionKind.SBC;
            case OP4_SUB -> InstructionKind.SUB;
            case OP4_RSB -> InstructionKind.RSB;
            default -> null;
        };
    }
}
