package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica o grupo "Multiply, multiply accumulate, and absolute difference" +
/// "Long multiply, long multiply accumulate, and divide" Thumb-2 de 32 bits (B2.7, PR2) — o
/// espaço inteiro de prefixo `raw[31:24] == 0xFB`: `MUL.W`/`MLA.W`, `SMULL`/`UMULL`/`SMLAL`/
/// `UMLAL`, `UMAAL`, `USAD8`/`USADA8` e as multiplicações DSP ARMv5TE (`SMLA<x><y>`/`SMLAW<y>`/
/// `SMULW<y>`/`SMUL<x><y>`/`SMLAL<x><y>`). Anexado via
/// {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando {@link ArmFeature#THUMB2}
/// está habilitado no preset (via {@code ARMV6K_THUMB2}).
///
/// Cada grupo é gateado pela MESMA {@link ArmFeature} que a forma ARM clássica equivalente já usa
/// ({@link ArmDecoder}), e a elevação para IR ({@code StandardIrBuilder}) produz exatamente a
/// mesma `DecodedInstruction`/IR que o decoder ARM produz — nenhuma semântica nova (G1).
///
/// B3.2 acrescenta `MLS` (`family=0000,op=0001`, gate {@link ArmFeature#MLS_MULTIPLY}) e
/// `SDIV`/`UDIV` (`family=1001`/`1011`, `op` fixo `1111`, gate {@link ArmFeature#DIVIDE}) — mesmas
/// features que o encoding ARM clássico equivalente em {@link ArmDecoder} já usa, zero IR nova.
///
/// **`SMLAD`/`SMLSD`/`SMLALD`/`SMLSLD`/`SMMLA`/`SMMLS`** (ARMv7, ainda fora de escopo — nenhuma
/// task os cobre ainda) ficam fora de escopo aqui, mas o padrão de bits ainda é
/// reivindicado por {@link #claimsEncodingSpace} (todo o prefixo `0xFB`), então caem em
/// UNDEFINED controlado em vez de UNIMPLEMENTED silencioso ambíguo (mesmo protocolo de B2.2.2).
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`),
/// exatamente como {@link ThumbDecoder} entrega às extensões de 32 bits. Referência: QEMU
/// `target/arm/tcg/t32.decode`, seções "Multiply and multiply accumulate" e "Long multiply, long
/// multiply accumulate, and divide" (todas sob o prefixo `1111 1011`/`0xFB`).
///
/// **Achado de campo (mesmo padrão de `MUL.W`/`MLA.W`)**: ao contrário do ARM clássico — onde
/// `SMUL<x><y>`/`SMULW<y>` têm `op2` (bits 22:21) PRÓPRIO, distinto de `SMLA<x><y>`/`SMLAW<y>`
/// (ARM ARM A5.2.7: op2=11 é `SMUL<x><y>`, op2=01+bit5=1 é `SMULW<y>`) — o Thumb-2 reusa o MESMO
/// `family`/`op` bit-pattern para as formas com e sem acumulador, distinguindo pelo campo `Ra`
/// (`raw[15:12]`) valer `1111` (QEMU `t32.decode`: entradas `SMULBB`/`SMULWB`/... usam `@rn0dm`,
/// que fixa esse campo em `1111` no encoding; `SMLABB`/`SMLAWB`/... usam `@rnadm`, campo livre).
/// Decodificado aqui traduzindo esse sentinel para o `op2`/`x` que {@code IrAluExecutor
/// #executeDspMultiply} já espera (op2=0↔SMLA<x><y>, op2=3↔SMUL<x><y> sem acumulador; op2=1 com
/// `x`=0↔SMLAW<y> acumula, `x`=1↔SMULW<y> não acumula) — mesma IR de B1.3/B1.6, sem mudança de
/// executor/ASM.
public final class Thumb2MultiplyDecoder implements DecoderExtension {
    /// `raw[31:24]`: prefixo fixo de todo este grupo — QEMU `t32.decode` linha 261 em diante.
    private static final int TOP8_MASK = 0xFF00_0000;
    private static final int TOP8_VALUE = 0xFB00_0000;

    private static final int FAMILY_SHIFT = 20; // nibble[23:20] (hi)
    private static final int OP_SHIFT = 4;       // nibble[7:4] (lo)

    private static final int FAMILY_MUL_MLA = 0x0; // também MLS (op=0x1, B3.2)
    private static final int FAMILY_SMLA_XY = 0x1;   // SMLA<x><y>/SMUL<x><y> (16x16)
    private static final int FAMILY_SMLAD = 0x2;     // SMLAD{X} (B9.7)
    private static final int FAMILY_SMLAW_Y = 0x3;   // SMLAW<y>/SMULW<y>
    private static final int FAMILY_SMLSD = 0x4;     // SMLSD{X} (B9.7)
    private static final int FAMILY_SMMLA = 0x5;     // SMMLA{R} (B9.7)
    private static final int FAMILY_SMMLS = 0x6;     // SMMLS{R} (B9.7)
    private static final int FAMILY_SDIV = 0x9;      // B3.2
    private static final int FAMILY_USADA8 = 0x7;
    private static final int FAMILY_SMULL = 0x8;
    private static final int FAMILY_UMULL = 0xA;
    private static final int FAMILY_UDIV = 0xB;      // B3.2
    private static final int FAMILY_SMLAL = 0xC; // também SMLAL<x><y> (op=0x8..0xB) e SMLALD{X} (op=0xC/0xD, B9.7)
    private static final int FAMILY_SMLSLD = 0xD;    // SMLSLD{X} (B9.7)
    private static final int FAMILY_UMLAL = 0xE; // também UMAAL (op=0x6)

    /// `op` (nibble[7:4]) das formas com dois produtos 16x16 (`SMLAD{X}`/`SMLSD{X}`) e das formas
    /// 32x32 truncadas (`SMMLA{R}`/`SMMLS{R}`) — bit0 seleciona `X`/`R` (exchange/round), QEMU
    /// `t32.decode` linhas 312-325 (`@rnadm`, mesmo layout de registrador do resto deste decoder).
    private static final int DSP_OP_PLAIN = 0x0;
    private static final int DSP_OP_EXCHANGE_OR_ROUND = 0x1;
    /// `op` (nibble[7:4]) das formas longas `SMLALD{X}`/`SMLSLD{X}` dentro de `FAMILY_SMLAL`/
    /// `FAMILY_SMLSLD` — QEMU `t32.decode` linhas 317-320 (`1100`/`1101` no nibble alto do `op`,
    /// distinto do `1000`/`1001`/`1010`/`1011` de `SMLAL&lt;x&gt;&lt;y&gt;`).
    private static final int DUAL_LONG_OP_BASE = 0xC;

    /// `op` (nibble[7:4]) fixo `1111` em `SDIV`/`UDIV` (QEMU `@rndm`, "1111 Rd 1111 Rm") — mesmo
    /// valor do sentinel `NO_ACCUMULATOR`, mas com significado diferente aqui (não é Ra).
    private static final int DIVIDE_OP = 0xF;

    private static final int NO_ACCUMULATOR = 0xF; // Ra=1111: MUL.W/SMUL<x><y>/SMULW<y>

    private static final int PROGRAM_COUNTER = 15;
    private static final int STACK_POINTER = 13;

    private final ArmArchitecture architecture;

    /// Constrói o decoder ligado à arquitetura corrente, usada para gatear cada subgrupo pela
    /// mesma {@link ArmFeature} do encoding ARM clássico equivalente.
    public Thumb2MultiplyDecoder(ArmArchitecture architecture) {
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
        int family = (raw >>> FAMILY_SHIFT) & 0xF;
        int op = (raw >>> OP_SHIFT) & 0xF;

        return switch (family) {
            case FAMILY_MUL_MLA -> switch (op) {
                case 0 -> decodeMulMla(raw, address, condition);
                case 1 -> decodeMls(raw, address, condition);
                default -> null;
            };
            case FAMILY_SMLA_XY -> decodeDspMultiplySixteenBySixteen(raw, address, condition, op);
            case FAMILY_SMLAD -> (op == DSP_OP_PLAIN || op == DSP_OP_EXCHANGE_OR_ROUND)
                    ? decodeDspDualMultiply(raw, address, condition, false, op == DSP_OP_EXCHANGE_OR_ROUND, false)
                    : null;
            case FAMILY_SMLAW_Y -> (op == 0 || op == 1)
                    ? decodeDspMultiplyWordBySixteen(raw, address, condition, op)
                    : null;
            case FAMILY_SMLSD -> (op == DSP_OP_PLAIN || op == DSP_OP_EXCHANGE_OR_ROUND)
                    ? decodeDspDualMultiply(raw, address, condition, true, op == DSP_OP_EXCHANGE_OR_ROUND, false)
                    : null;
            case FAMILY_SMMLA -> (op == DSP_OP_PLAIN || op == DSP_OP_EXCHANGE_OR_ROUND)
                    ? decodeDspTopWordMultiply(raw, address, condition, false, op == DSP_OP_EXCHANGE_OR_ROUND)
                    : null;
            case FAMILY_SMMLS -> (op == DSP_OP_PLAIN || op == DSP_OP_EXCHANGE_OR_ROUND)
                    ? decodeDspTopWordMultiply(raw, address, condition, true, op == DSP_OP_EXCHANGE_OR_ROUND)
                    : null;
            case FAMILY_USADA8 -> op == 0 ? decodeUsad8(raw, address, condition) : null;
            case FAMILY_SMULL -> op == 0 ? decodeLongMultiply(raw, address, condition,
                    InstructionKind.SMULL) : null;
            case FAMILY_UMULL -> op == 0 ? decodeLongMultiply(raw, address, condition,
                    InstructionKind.UMULL) : null;
            case FAMILY_SDIV -> op == DIVIDE_OP ? decodeDivide(raw, address, condition, true) : null;
            case FAMILY_UDIV -> op == DIVIDE_OP ? decodeDivide(raw, address, condition, false) : null;
            case FAMILY_SMLAL -> decodeSmlalFamily(raw, address, condition, op);
            case FAMILY_SMLSLD -> (op == DUAL_LONG_OP_BASE || op == (DUAL_LONG_OP_BASE | 1))
                    ? decodeDspDualMultiply(raw, address, condition, true, (op & 1) != 0, true)
                    : null;
            case FAMILY_UMLAL -> decodeUmlalFamily(raw, address, condition, op);
            default -> null; // reservado dentro do prefixo 0xFB — UNDEFINED controlado (claimsEncodingSpace)
        };
    }

    // ── MUL.W/MLA.W — QEMU "Multiply and multiply accumulate" ──────────────────────────────

    private DecodedInstruction decodeMulMla(int raw, int address, Condition condition) {
        // Layout: 1111 1011 0000 Rn(19:16) Ra(15:12) Rd(11:8) 0000 Rm(3:0). Ra=1111 é MUL.W (sem
        // acumulador); qualquer outro valor é MLA.W (ARM DDI 0406C A8.8.105 nota T1: "Ra=1111 is
        // MUL", mesmo achado descrito em B3.2/PR1 para as demais formas com sentinel de acumulador).
        int rn = (raw >>> 16) & 0xF;
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        boolean accumulate = ra != NO_ACCUMULATOR;
        if (accumulate && ra == STACK_POINTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // destinationRegister=rd, sourceRegister/secondSourceRegister=os dois multiplicandos
        // (multiplicação comutativa, ordem não importa), immediate=Ra (só lido pelo builder
        // quando kind==MLA — ver StandardIrBuilder#lift, caso MUL/MLA).
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                accumulate ? InstructionKind.MLA : InstructionKind.MUL,
                rd, rn, rm, ra, false, false, false);
    }

    // ── MLS (B3.2) — QEMU "Multiply and multiply accumulate" (family=0000, op=0001) ────────

    private DecodedInstruction decodeMls(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MLS_MULTIPLY)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Mesmo layout de registrador de MUL.W/MLA.W: Rn(19:16), Ra(15:12, sempre real — não há
        // sentinel "sem acumulador" para MLS), Rd(11:8), Rm(3:0).
        int rn = (raw >>> 16) & 0xF;
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm) || isRestricted(ra)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MLS,
                rd, rn, rm, ra, false, false, false);
    }

    // ── SDIV/UDIV (B3.2) — QEMU "Long multiply, long multiply accumulate, and divide" ──────

    private DecodedInstruction decodeDivide(int raw, int address, Condition condition, boolean signedDivide) {
        if (!architecture.has(ArmFeature.DIVIDE)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> 16) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Mesmo truque de `ArmDecoder`: reusa o campo `signedAccess` do construtor de 14 args para
        // guardar `signedDivide` (accessSizeBytes=0, sem acesso de memória real) — evita um record
        // widening só para esta instrução, mesma decisão do encoding ARM clássico equivalente.
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.DIVIDE,
                rd, rn, rm, 0, false, false, false, 0, signedDivide);
    }

    // ── SMULL/UMULL/SMLAL/UMLAL — QEMU "Long multiply..." (formas planas, op=0000) ─────────

    private DecodedInstruction decodeLongMultiply(int raw, int address, Condition condition, InstructionKind kind) {
        // Layout: 1111 1011 10s0 Rn(19:16) RdLo(15:12) RdHi(11:8) 0000 Rm(3:0) — s=0 SMULL/UMULL
        // (family=1000/1010), s=1 SMLAL/UMLAL (family=1100/1110, decodeSmlalFamily/decodeUmlalFamily).
        int rn = (raw >>> 16) & 0xF;
        int rdLow = (raw >>> 12) & 0xF;
        int rdHigh = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rdLow) || isRestricted(rdHigh) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // destinationRegister=RdLo, sourceRegister/secondSourceRegister=os dois multiplicandos,
        // immediate=RdHi — mesmo layout que StandardIrBuilder#lift (caso UMULL/UMLAL/SMULL/SMLAL)
        // já consome, produzindo IrOp.LongMultiply idêntico ao ARM clássico.
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                rdLow, rn, rm, rdHigh, false, false, false);
    }

    private DecodedInstruction decodeSmlalFamily(int raw, int address, Condition condition, int op) {
        if (op == 0) {
            return decodeLongMultiply(raw, address, condition, InstructionKind.SMLAL);
        }
        // SMLALD{X} (op=1100/1101, B9.7): ACHADO REAL — antes desta task, `(op & 0x8) != 0` sozinho
        // desviava op=0xC/0xD para `decodeDspMultiplySixtyFourBitAccumulate` (SMLAL<x><y>), que
        // computa x=(op>>1)&1/y=op&1 IDÊNTICOS aos de op=0x8 (SMLALBB) — um caso de decode ERRADO
        // e SILENCIOSO (G8): o encoding decodificava sem lançar, mas como a instrução errada
        // (soma de um produto 16x16 em vez de dois produtos 16x16 somados entre si). A tabela
        // `docs/COBERTURA-ISA.md` já marcava `SMLALD`/`SMLALDX` como ✅ antes desta correção — a
        // tabela mede só "decodifica", não semântica (mesmo aviso do cabeçalho do arquivo),
        // exatamente o padrão de bug que motivou a nota "STREX (E3) decodificava e estava errado".
        if (op == DUAL_LONG_OP_BASE || op == (DUAL_LONG_OP_BASE | 1)) {
            return decodeDspDualMultiply(raw, address, condition, false, (op & 1) != 0, true);
        }
        // SMLAL<x><y> (op=1000..1011): acumulador 64 bits {RdHi:RdLo} += Rn.x * Rm.y — IrOp.DspMultiply
        // op2=2 (mesma semântica de IrAluExecutor#executeDspMultiply, caso 2).
        if ((op & 0x8) != 0) {
            return decodeDspMultiplySixtyFourBitAccumulate(raw, address, condition, op);
        }
        return null; // op=0001..0111 reservado
    }

    // ── SMLAD{X}/SMLSD{X}/SMLALD{X}/SMLSLD{X} — QEMU "Multiply..." (B9.7, famílias 0010/0100/1100/1101) ──

    /// Mesma semântica/empacotamento do encoding ARM clássico equivalente (B9.1, {@link ArmDecoder}
    /// `InstructionKind#DSP_DUAL_MULTIPLY`) — zero IR nova (G1). Layout de registrador `@rnadm`:
    /// `rn`(19:16)=Rn do manual (bits 3:0 na forma ARM — papel de `secondSourceRegister`),
    /// `ra`(15:12)=acumulador (`RdLo` na forma longa), `rd`(11:8)=Rd do manual (bits 19:16 na forma
    /// ARM — `destinationRegister`, `RdHi` na forma longa), `rm`(3:0)=Rm do manual (bits 11:8 na
    /// forma ARM — papel de `sourceRegister`). Mesma troca de posições entre A32/T32 já documentada
    /// no cabeçalho da classe para `SMLA&lt;x&gt;&lt;y&gt;`.
    private DecodedInstruction decodeDspDualMultiply(int raw, int address, Condition condition,
            boolean subtract, boolean exchange, boolean longForm) {
        if (!architecture.has(ArmFeature.SIGNED_MULTIPLY_MEDIA)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> 16) & 0xF;
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rm) || isRestricted(rn) || (longForm && isRestricted(ra))) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Ra=15 é o alias sem acumulador (SMUAD/SMUSD) na forma curta — UNPREDICTABLE só na longa
        // (SMLALD/SMLSLD sempre acumulam), mesma regra do encoding ARM clássico.
        int packed = ra | (subtract ? 1 << 4 : 0) | (exchange ? 1 << 5 : 0) | (longForm ? 1 << 6 : 0);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.DSP_DUAL_MULTIPLY, rd, rm, rn, packed, false, false, false);
    }

    // ── SMMLA{R}/SMMLS{R} — QEMU "Multiply..." (B9.7, famílias 0101/0110) ──────────────────

    /// Mesma semântica/empacotamento do encoding ARM clássico equivalente (B9.1, {@link ArmDecoder}
    /// `InstructionKind#DSP_TOP_WORD_MULTIPLY`). Layout `@rnadm`: `rn`(19:16)=Rn do manual
    /// (`sourceRegister`), `ra`(15:12)=acumulador, `rd`(11:8)=Rd do manual (`destinationRegister`),
    /// `rm`(3:0)=Rm do manual (`secondSourceRegister`).
    private DecodedInstruction decodeDspTopWordMultiply(int raw, int address, Condition condition,
            boolean subtract, boolean round) {
        if (!architecture.has(ArmFeature.SIGNED_MULTIPLY_MEDIA)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> 16) & 0xF;
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rm) || isRestricted(rn)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int packed = ra | (subtract ? 1 << 4 : 0) | (round ? 1 << 5 : 0);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.DSP_TOP_WORD_MULTIPLY, rd, rn, rm, packed, false, false, false);
    }

    private DecodedInstruction decodeUmlalFamily(int raw, int address, Condition condition, int op) {
        if (op == 0) {
            return decodeLongMultiply(raw, address, condition, InstructionKind.UMLAL);
        }
        if (op == 0x6) {
            return decodeUmaal(raw, address, condition);
        }
        return null; // resto do family=E é reservado dentro do prefixo 0xFB
    }

    // ── UMAAL — QEMU "Long multiply..." (`UMAAL`, family=1110 op=0110) ─────────────────────

    private DecodedInstruction decodeUmaal(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.UMAAL)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Mesmo layout de registrador de SMULL/UMULL: Rn(19:16), RdLo(15:12), RdHi(11:8), Rm(3:0).
        int rn = (raw >>> 16) & 0xF;
        int rdLow = (raw >>> 12) & 0xF;
        int rdHigh = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rdLow) || isRestricted(rdHigh) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.UMAAL,
                rdLow, rn, rm, rdHigh, false, false, false);
    }

    // ── USAD8/USADA8 — QEMU "Multiply and multiply accumulate" (`USADA8`, family=0111) ─────

    private DecodedInstruction decodeUsad8(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.PACK_SATURATE)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Layout: 1111 1011 0111 Rn(19:16) Ra(15:12) Rd(11:8) 0000 Rm(3:0). Ra=1111 é USAD8 (sem
        // acumulador) — mesmo sentinel de MUL/MLA.
        int rn = (raw >>> 16) & 0xF;
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int accumulator = ra == NO_ACCUMULATOR ? -1 : ra;
        if (accumulator >= 0 && accumulator == STACK_POINTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // destinationRegister=rd, sourceRegister/secondSourceRegister=os dois operandos de |a-b|,
        // immediate=acumulador (ou -1) — mesmo layout de StandardIrBuilder#lift (caso USAD8).
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.USAD8,
                rd, rn, rm, accumulator, false, false, false);
    }

    // ── SMLA<x><y>/SMUL<x><y> — QEMU "Multiply..." (family=0001) ───────────────────────────

    private DecodedInstruction decodeDspMultiplySixteenBySixteen(int raw, int address, Condition condition, int op) {
        if (!architecture.has(ArmFeature.DSP_MULTIPLY)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Layout: 1111 1011 0001 Rn(19:16) Ra(15:12) Rd(11:8) 00NM Rm(3:0) — N (op bit1) seleciona
        // a metade de Rn (o "Rm" de x na nomenclatura ARM clássica), M (op bit0) a metade de Rm (o
        // "Rs" de y). Ra=1111 é SMUL<x><y> (sem acumulador, op2=3 no IrOp.DspMultiply); qualquer
        // outro valor é SMLA<x><y> (op2=0, acumula com Ra).
        int rn = (raw >>> 16) & 0xF; // registrador "x" (metade selecionada por N)
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF; // registrador "y" (metade selecionada por M)
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        boolean accumulate = ra != NO_ACCUMULATOR;
        if (accumulate && ra == STACK_POINTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int x = (op >>> 1) & 1;
        int y = op & 1;
        int op2 = accumulate ? 0 : 3;
        int accumulatorField = accumulate ? ra : 0; // ignorado pelo executor quando op2==3
        int packed = accumulatorField | (op2 << 4) | (x << 6) | (y << 7);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.DSP_MULTIPLY,
                rd, rn, rm, packed, false, false, false);
    }

    // ── SMLAW<y>/SMULW<y> — QEMU "Multiply..." (family=0011) ───────────────────────────────

    private DecodedInstruction decodeDspMultiplyWordBySixteen(int raw, int address, Condition condition, int op) {
        if (!architecture.has(ArmFeature.DSP_MULTIPLY)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Layout: 1111 1011 0011 Rn(19:16) Ra(15:12) Rd(11:8) 000M Rm(3:0) — M (op bit0) seleciona
        // a metade de Rm (y). Rn (32 bits inteiros) é multiplicado pela metade de Rm. Ra=1111 é
        // SMULW<y> (op2=1, x=1, sem acumulador); qualquer outro valor é SMLAW<y> (op2=1, x=0,
        // acumula com Ra) — mesmo sentinel usado pelas demais formas deste decoder.
        int rn = (raw >>> 16) & 0xF; // multiplicando de 32 bits inteiro
        int ra = (raw >>> 12) & 0xF;
        int rd = (raw >>> 8) & 0xF;
        int rm = raw & 0xF; // registrador "y" (metade selecionada por M)
        if (isRestricted(rd) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        boolean accumulate = ra != NO_ACCUMULATOR;
        if (accumulate && ra == STACK_POINTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int y = op & 1;
        int x = accumulate ? 0 : 1; // x seleciona SMLAWy (0, acumula) vs SMULWy (1, não acumula)
        int accumulatorField = accumulate ? ra : 0; // ignorado pelo executor quando x!=0
        int packed = accumulatorField | (1 << 4) | (x << 6) | (y << 7); // op2=1
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.DSP_MULTIPLY,
                rd, rn, rm, packed, false, false, false);
    }

    // ── SMLAL<x><y> — QEMU "Long multiply..." (family=1100, op=1000..1011) ─────────────────

    private DecodedInstruction decodeDspMultiplySixtyFourBitAccumulate(int raw, int address, Condition condition, int op) {
        if (!architecture.has(ArmFeature.DSP_MULTIPLY)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        // Layout: 1111 1011 1100 Rn(19:16) RdLo(15:12) RdHi(11:8) 10NM Rm(3:0) — sempre acumula
        // (não existe forma "sem acumulador" para o par de 64 bits); RdLo é um registrador REAL
        // aqui, nunca o sentinel 1111 de "sem acumulador".
        int rn = (raw >>> 16) & 0xF;
        int rdLow = (raw >>> 12) & 0xF;
        int rdHigh = (raw >>> 8) & 0xF;
        int rm = raw & 0xF;
        if (isRestricted(rdLow) || isRestricted(rdHigh) || isRestricted(rn) || isRestricted(rm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int x = (op >>> 1) & 1;
        int y = op & 1;
        int packed = rdLow | (2 << 4) | (x << 6) | (y << 7); // op2=2 (SMLAL<x><y>, IrAluExecutor caso 2)
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.DSP_MULTIPLY,
                rdHigh, rn, rm, packed, false, false, false);
    }

    /// Rd/Rn/Rm ∈ {13,15} é UNPREDICTABLE em TODO o espaço 0xFB (ARM DDI 0406C A5.3, mesma
    /// citação de B2.2.1/B2.7-PR1) — vira UNDEFINED em vez de aceito silenciosamente.
    private static boolean isRestricted(int register) {
        return register == STACK_POINTER || register == PROGRAM_COUNTER;
    }
}
