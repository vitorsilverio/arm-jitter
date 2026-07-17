package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decoder ARM32 inicial para o caminho interpretado frio.
public final class ArmDecoder implements InstructionDecoder {
    /// Registrador PC — usado para checar UNPREDICTABLE (Rd=PC) em MOVW/MOVT (B3.1).
    private static final int PROGRAM_COUNTER = 15;
    /// Rn=1111 nas encodings de extensão ARMv6 marca a forma sem acumulador (SXTB vs SXTAB).
    private static final int EXTEND_NO_ACCUMULATOR = 0xF;
    /// Rn=1111 no encoding de USAD marca a forma sem acumulador (USAD8 vs USADA8).
    private static final int USAD_NO_ACCUMULATOR = 0xF;

    // PLD/PLDW/PLI (B2.8) — máscaras exatas do QEMU `a32-uncond.decode`, seção "Preload
    // instructions": `PLD 1111 0101 -101 ---- 1111 ---- ---- ----` (imediato/literal, 5te),
    // `PLDW 1111 0101 -001 ---- 1111 ---- ---- ----` (imediato/literal, 7mp),
    // `PLI 1111 0100 -101 ---- 1111 ---- ---- ----` (imediato/literal, 7),
    // `PLD 1111 0111 -101 ---- 1111 ----- -- 0 ----` (registrador, 5te),
    // `PLDW 1111 0111 -001 ---- 1111 ----- -- 0 ----` (registrador, 7mp),
    // `PLI 1111 0110 -101 ---- 1111 ----- -- 0 ----` (registrador, 7).
    private static final int PLD_IMM_MASK = 0xFF70_F000;
    private static final int PLD_IMM_VALUE = 0xF550_F000;
    private static final int PLDW_IMM_MASK = 0xFF70_F000;
    private static final int PLDW_IMM_VALUE = 0xF510_F000;
    private static final int PLI_IMM_MASK = 0xFF70_F000;
    private static final int PLI_IMM_VALUE = 0xF450_F000;
    private static final int PLD_REG_MASK = 0xFF70_F010;
    private static final int PLD_REG_VALUE = 0xF750_F000;
    private static final int PLDW_REG_MASK = 0xFF70_F010;
    private static final int PLDW_REG_VALUE = 0xF710_F000;
    private static final int PLI_REG_MASK = 0xFF70_F010;
    private static final int PLI_REG_VALUE = 0xF650_F000;

    // DMB/DSB/ISB (B3.1) — espaço incondicional `1111 0101 0111 1111 1111 0000 01xx oooo`;
    // bits 27:8 fixos (0x0FF77F0 preenchidos), bits 7:4 selecionam a barreira, bits 3:0 (option)
    // livres/ignorados.
    private static final int DMB_DSB_ISB_MASK = 0xFFFF_FFF0;
    private static final int DSB_VALUE = 0xF57F_F040;
    private static final int DMB_VALUE = 0xF57F_F050;
    private static final int ISB_VALUE = 0xF57F_F060;

    // MOVW/MOVT (B3.1) — `cccc 0011 0000 imm4 Rd imm12` / `cccc 0011 0100 imm4 Rd imm12`. Sem
    // este carve-out o padrão cai no dispatch ALU genérico (opcode 0x8/0xA = TST/CMP com S=0,
    // ver Armadilha da task), então precisa ser checado antes desse dispatch.
    private static final int MOVE_WIDE_MASK = 0x0FF0_0000;
    private static final int MOVW_VALUE = 0x0300_0000;
    private static final int MOVT_VALUE = 0x0340_0000;

    // Restante inteiro ARMv7 (B3.1): `MLS`, `SBFX`/`UBFX`, `BFI`/`BFC`, `RBIT`, `SDIV`/`UDIV`.
    // Todos colidem com dispatches genéricos mais abaixo (achado empírico do Passo 0 desta task:
    // MLS com o bloco de halfword-transfer `0x0000_0090`, os demais — que têm bits27:26=01 — com
    // o bloco de LDR/STR imediato `raw&0x0C00_0000==0x0400_0000`, cujo ramo interno
    // `!immediateOffset && bit4` já retorna UNIMPLEMENTED para esse padrão), então precisam vir
    // ANTES desses blocos, mesmo cuidado do UMAAL/exclusivos acima.

    /// `MLS`: `cccc 0000 0110 dddd aaaa mmmm 1001 nnnn` — Rd = Ra − Rn×Rm.
    private static final int MLS_MASK = 0x0FF0_00F0;
    private static final int MLS_VALUE = 0x0060_0090;

    /// `SBFX`/`UBFX`: `cccc 0111 101 widthm1[4:0] dddd lsb[4:0] 101 nnnn` (SBFX) /
    /// `cccc 0111 111 widthm1[4:0] dddd lsb[4:0] 101 nnnn` (UBFX).
    private static final int BIT_FIELD_EXTRACT_MASK = 0x0FE0_0070;
    private static final int SBFX_VALUE = 0x07A0_0050;
    private static final int UBFX_VALUE = 0x07E0_0050;

    /// `BFI`/`BFC`: `cccc 0111 110 msb[4:0] dddd lsb[4:0] 001 nnnn` — Rn=1111 é `BFC`.
    private static final int BIT_FIELD_INSERT_MASK = 0x0FE0_0070;
    private static final int BIT_FIELD_INSERT_VALUE = 0x07C0_0010;
    private static final int BFC_SOURCE_SENTINEL = 0xF;

    /// `RBIT`: `cccc 0110 1111 1111 dddd 1111 0011 mmmm`.
    private static final int RBIT_MASK = 0x0FFF_0FF0;
    private static final int RBIT_VALUE = 0x06FF_0F30;

    /// `SDIV`/`UDIV`: `cccc 0111 0001 dddd 1111 mmmm 0001 nnnn` (SDIV) /
    /// `cccc 0111 0011 dddd 1111 mmmm 0001 nnnn` (UDIV).
    private static final int DIVIDE_MASK = 0x0FF0_F0F0;
    private static final int SDIV_VALUE = 0x0710_F010;
    private static final int UDIV_VALUE = 0x0730_F010;

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

        // BLX (imediato): o espaço incondicional cond==1111, `1111 101H <offset de 24 bits>`. Sempre
        // linka e sempre troca para Thumb; o alvo carrega o bit 0 setado para a troca reconhecer.
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

        // WFI (ARMv6K hint): `cccc 0011 0010 0000 1111 0000 0000 0011`. No hardware real,
        // pré-v6 trataria este padrão como um MSR(registrador)-para-CPSR inofensivo (Rm=PC,
        // máscara de campo vazia) — mas por consistência com o resto deste decoder (mesmo
        // tratamento de LDREX/STREX/CLREX), a arquitetura sem WAIT_HINTS recebe UNDEFINED
        // explícito em vez de cair no decode MSR abaixo.
        if ((raw & 0x0FFF_FFFF) == 0x0320_F003) {
            if (!architecture.has(ArmFeature.WAIT_HINTS)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    InstructionKind.WAIT_FOR_INTERRUPT, -1, -1, -1, 0, false, false, false);
        }

        // SETEND (ARMv6): `1111 0001 0000 0001 0000 00 E 0 0000 0000` — espaço incondicional,
        // compartilha o prefixo de 12 bits `1111 0001 0000` com CPS (abaixo); distingue-se pelo
        // bit 16 (SETEND=1, CPS=0/SBZP).
        if ((raw & 0xFFFF_FDFF) == 0xF101_0000) {
            if (!architecture.has(ArmFeature.SETEND_BIG_ENDIAN_DATA)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int endianBit = (raw >>> 9) & 1;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.SETEND, -1, -1, -1, endianBit, false, false, false);
        }

        // CPS/CPSIE/CPSID (ARMv6): `1111 0001 0000 imod M 0 0000000 A I F 0 mode` — espaço
        // incondicional (cond=1111, forçado AL). `imod` bits 19:18, `M` bit 17 (troca de modo
        // válida), `A`/`I`/`F` bits 8/7/6, `mode` bits 4:0 (válido só com M=1).
        if ((raw & 0xFFF1_FE20) == 0xF100_0000) {
            if (!architecture.has(ArmFeature.MODE_CHANGE_INSTRUCTIONS)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int imod = (raw >>> 18) & 0x3;
            int modeChange = (raw >>> 17) & 1;
            int a = (raw >>> 8) & 1;
            int i = (raw >>> 7) & 1;
            int f = (raw >>> 6) & 1;
            int mode = raw & 0x1F;
            int packed = imod | (modeChange << 2) | (a << 3) | (i << 4) | (f << 5) | (mode << 6);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.CPS, -1, -1, -1, packed, false, false, false);
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

        // BLX (registrador): `cccc 0001 0010 1111 1111 1111 0011 mmmm` — como BX, mas também linka.
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

        // Aritmética saturante (ARMv5TE): QADD/QSUB/QDADD/QDSUB. `cccc 0001 0PP0 nnnn dddd 0000 0101 mmmm`.
        // Só interceptada quando a arquitetura tem a feature, então ARMv4T mantém o comportamento anterior.
        if ((raw & 0x0F90_0FF0) == 0x0100_0050 && architecture.has(ArmFeature.SATURATING)) {
            int op = (raw >>> 21) & 0x3;
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SATURATING,
                    rd, rm, rn, op, false, false, false);
        }

        // Multiplicações DSP (ARMv5TE): `cccc 0001 0PP0 dddd nnnn ssss 1yx0 mmmm`. As metades de 16 bits
        // e o registrador acumulador são empacotados no imediato para o builder desempacotar.
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

        // Aritmética paralela ARMv6 (SIMD 8/16 bits): `cccc 0110 0ppp nnnn dddd 1111 ttt1 mmmm` —
        // ppp = variante (001=S, 010=Q, 011=SH, 101=U, 110=UQ, 111=UH; 000/100 são buracos
        // indefinidos), ttt = operação (000=ADD16, 001=ASX, 010=SAX, 011=SUB16, 100=ADD8,
        // 111=SUB8; 101/110 são buracos). Precisa vir antes do bloco de single-data-transfer
        // (01xx), que engoliria o padrão registrador+bit4 como UNIMPLEMENTED.
        if ((raw & 0x0F80_0F10) == 0x0600_0F10 && architecture.has(ArmFeature.PARALLEL_SIMD)) {
            int variantBits = (raw >>> 20) & 0x7;
            int opBits = (raw >>> 5) & 0x7;
            boolean variantValid = variantBits != 0b000 && variantBits != 0b100;
            boolean opValid = opBits != 0b101 && opBits != 0b110;
            if (variantValid && opValid) {
                int rn = (raw >>> 16) & 0xF;
                int rd = (raw >>> 12) & 0xF;
                int rm = raw & 0xF;
                int packed = variantBits | (opBits << 3);
                return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                        InstructionKind.PARALLEL_ALU, rd, rn, rm, packed, false, false, false);
            }
        }

        // SEL (ARMv6): `cccc 0110 1000 nnnn dddd 1111 1011 mmmm` — seleciona bytes por GE.
        // Faz parte do grupo PARALLEL_SIMD (consome os GE que a aritmética paralela produz).
        if ((raw & 0x0FF0_0FF0) == 0x0680_0FB0 && architecture.has(ArmFeature.PARALLEL_SIMD)) {
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SEL,
                    rd, rn, rm, 0, false, false, false);
        }

        // PKHBT/PKHTB (ARMv6): `cccc 0110 1000 nnnn dddd iiii it01 mmmm` — t (bit 6) escolhe
        // TB (1, Rm ASR imm com imm=0 → ASR #32) ou BT (0, Rm LSL imm).
        if ((raw & 0x0FF0_0030) == 0x0680_0010 && architecture.has(ArmFeature.PACK_SATURATE)) {
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int shiftImm = (raw >>> 7) & 0x1F;
            boolean tb = (raw & (1 << 6)) != 0;
            int rm = raw & 0xF;
            int packed = shiftImm | (tb ? 1 << 5 : 0);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.PKH,
                    rd, rn, rm, packed, false, false, false);
        }

        // SSAT/USAT (ARMv6): `cccc 0110 1u1s ssss dddd iiii ir01 mmmm` — u=unsigned, sssss =
        // sat_imm, iiiii = shift imm5, r (bit 6) = ASR (0=LSL). Vem depois de PKH (bits 5:4
        // iguais, bits 27:20 distintos) e antes das formas 16 (bits 5:4 diferentes lá).
        if ((raw & 0x0FA0_0030) == 0x06A0_0010 && architecture.has(ArmFeature.PACK_SATURATE)) {
            boolean unsigned = (raw & (1 << 22)) != 0;
            int satImm = (raw >>> 16) & 0x1F;
            int rd = (raw >>> 12) & 0xF;
            int shiftImm = (raw >>> 7) & 0x1F;
            boolean asr = (raw & (1 << 6)) != 0;
            int rm = raw & 0xF;
            int packed = satImm | (shiftImm << 5) | (asr ? 1 << 10 : 0) | (unsigned ? 1 << 12 : 0);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SATURATE,
                    rd, -1, rm, packed, false, false, false);
        }

        // SSAT16/USAT16 (ARMv6): `cccc 0110 1u10 ssss dddd 1111 0011 mmmm` — satura cada
        // halfword de forma independente; sat_imm de 4 bits.
        if ((raw & 0x0FB0_0FF0) == 0x06A0_0F30 && architecture.has(ArmFeature.PACK_SATURATE)) {
            boolean unsigned = (raw & (1 << 22)) != 0;
            int satImm = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            int packed = satImm | (1 << 11) | (unsigned ? 1 << 12 : 0);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.SATURATE,
                    rd, -1, rm, packed, false, false, false);
        }

        // USAD8/USADA8 (ARMv6): `cccc 0111 1000 dddd nnnn ssss 0001 mmmm` — Rd em 19:16 e o
        // acumulador Rn em 15:12; Rn=1111 é a forma sem acumulador (USAD8).
        if ((raw & 0x0FF0_00F0) == 0x0780_0010 && architecture.has(ArmFeature.PACK_SATURATE)) {
            int rd = (raw >>> 16) & 0xF;
            int rn = (raw >>> 12) & 0xF;
            int rs = (raw >>> 8) & 0xF;
            int rm = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.USAD8,
                    rd, rm, rs, rn == USAD_NO_ACCUMULATOR ? -1 : rn, false, false, false);
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

        // Acessos exclusivos ARMv6/v6K: `cccc 0001 1sz1 nnnn dddd 1111 1001 1111` (LDREX*) e
        // `cccc 0001 1sz0 nnnn dddd 1111 1001 mmmm` (STREX*), com sz: 00=word, 01=doubleword,
        // 10=byte, 11=halfword. Word é gateado por EXCLUSIVE_WORD; B/H/D por EXCLUSIVE_SIZED.
        // Precisa vir antes do bloco de halfword-transfer (0x0000_0090), que engoliria o padrão.
        if ((raw & 0x0F80_0FF0) == 0x0180_0F90) {
            boolean load = (raw & (1 << 20)) != 0;
            int sizeBits = (raw >>> 21) & 0x3;
            int sizeBytes = switch (sizeBits) {
                case 0b00 -> 4;
                case 0b01 -> 8;
                case 0b10 -> 1;
                default -> 2;
            };
            ArmFeature required = sizeBytes == 4 ? ArmFeature.EXCLUSIVE_WORD : ArmFeature.EXCLUSIVE_SIZED;
            boolean formValid = !load || (raw & 0xF) == 0xF; // LDREX* tem os bits 3:0 fixos em 1111
            // Este padrão de bits colide com outras encodings mais abaixo no decoder (halfword
            // transfer, etc.) — retorna UNDEFINED explicitamente quando a arquitetura não tem a
            // feature ou a forma é inválida, em vez de cair (fall-through) num decode errado
            // (mesmo cuidado do BLX/CLZ/Saturating acima).
            if (!architecture.has(required) || !formValid) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int rn = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            if (exclusiveRegistersValid(load, sizeBytes, rd, rn, rm)) {
                return load
                        ? new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                                InstructionKind.LOAD_EXCLUSIVE, rd, rn, -1, 0, false, false, false,
                                sizeBytes, false)
                        : new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                                InstructionKind.STORE_EXCLUSIVE, rd, rn, rm, 0, false, false, false,
                                sizeBytes, false);
            }
            // Formas UNPREDICTABLE (Rd/Rn/Rm=PC, Rd sobreposto ao par do STREX, par ímpar
            // do LDREXD/STREXD) seguem para UNDEFINED em vez de aceitar silenciosamente.
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
        }

        // CLREX (ARMv6K): encoding exato 0xF57FF01F, no espaço incondicional (cond=1111). Este
        // padrão de bits colide com o encoding de LDR/STR mais abaixo — retorna UNDEFINED
        // explicitamente quando a feature falta, em vez de cair no decode de LDR/STR.
        if (raw == 0xF57F_F01F) {
            if (!architecture.has(ArmFeature.EXCLUSIVE_SIZED)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.CLEAR_EXCLUSIVE, -1, -1, -1, 0, false, false, false);
        }

        // PLD/PLDW/PLI (B2.8): hints de preload de cache no espaço incondicional (cond=1111) —
        // ARM DDI 0406C A8.8.128/A8.8.129/A8.8.130, confirmado no QEMU `a32-uncond.decode`
        // ("Preload instructions", 6 linhas: PLD/PLDW/PLI x {imediato/literal, registrador}).
        // Nenhum efeito observável além de ciclo/fetch — nem endereço é acessado (um PLD em
        // endereço não mapeado não falha). Reusa o mesmo truque de MSR(imediato)->CPSR com
        // máscara de campo vazia que WFI/hints já usam (ver `noOpHint` de Thumb2MiscDecoder).
        if ((raw & PLD_IMM_MASK) == PLD_IMM_VALUE
                || (raw & PLDW_IMM_MASK) == PLDW_IMM_VALUE
                || (raw & PLI_IMM_MASK) == PLI_IMM_VALUE
                || (raw & PLD_REG_MASK) == PLD_REG_VALUE
                || (raw & PLDW_REG_MASK) == PLDW_REG_VALUE
                || (raw & PLI_REG_MASK) == PLI_REG_VALUE) {
            if (!architecture.has(ArmFeature.PRELOAD_HINTS)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.MSR, 0, -1, -1, 0, true, false, false);
        }

        // DMB/DSB/ISB (ARMv7, B3.1): espaço incondicional (cond=1111) `1111 0101 0111 1111 1111
        // 0000 01xx oooo` — `option` (bits 3:0) ignorado (NOP observável, ver
        // `IrOp.MemoryBarrier`); `op` (bits 7:4) distingue DSB(0100)/DMB(0101)/ISB(0110). Este
        // padrão de bits colide com o encoding de LDR/STR imediato mais abaixo (raw&0x0C00_0000
        // == 0x0400_0000) — precisa ser checado ANTES e virar UNDEFINED explícito sem a feature,
        // mesmo cuidado do CLREX/PLD acima (achado em tempo de implementação: o Passo 0 desta
        // task provou empiricamente que sem este carve-out o `ArmDecoder` base decodifica DMB/
        // DSB/ISB como um LOAD incorreto, não UNIMPLEMENTED).
        if ((raw & DMB_DSB_ISB_MASK) == DSB_VALUE || (raw & DMB_DSB_ISB_MASK) == DMB_VALUE
                || (raw & DMB_DSB_ISB_MASK) == ISB_VALUE) {
            if (!architecture.has(ArmFeature.MEMORY_BARRIERS)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int option = raw & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.MEMORY_BARRIER, -1, -1, -1, option, false, false, false);
        }

        // MLS (ARMv6T2+, B3.1): precisa vir antes do bloco de halfword-transfer (0x0000_0090
        // logo abaixo, que a engoliria como UNIMPLEMENTED), mesmo cuidado do UMAAL.
        if ((raw & MLS_MASK) == MLS_VALUE) {
            if (!architecture.has(ArmFeature.MLS_MULTIPLY)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int rd = (raw >>> 16) & 0xF;
            int ra = (raw >>> 12) & 0xF;
            int rm = (raw >>> 8) & 0xF;
            int rn = raw & 0xF;
            if (rd == PROGRAM_COUNTER || ra == PROGRAM_COUNTER || rm == PROGRAM_COUNTER || rn == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.MLS,
                    rd, rn, rm, ra, false, false, false);
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
            // LDRD/STRD (ARMv5TE): L=0 com transferKind 10 (LDRD) ou 11 (STRD). Checado antes da
            // rejeição genérica de store abaixo; só quando a arquitetura tem a feature.
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

        // SBFX/UBFX/BFI/BFC/RBIT/SDIV/UDIV (ARMv6T2+/ARMv7, B3.1): todas têm bits27:26=01 e
        // bit25=1,bit4=1 — precisam vir ANTES do bloco de LDR/STR imediato logo abaixo, cujo
        // ramo interno (`!immediateOffset && bit4`) já as engoliria como UNIMPLEMENTED (achado
        // empírico do Passo 0 desta task; mesmo espaço "media instructions" do ARM DDI 0406C
        // A5.4, adjacente ao de LDR/STR).
        if ((raw & BIT_FIELD_EXTRACT_MASK) == SBFX_VALUE || (raw & BIT_FIELD_EXTRACT_MASK) == UBFX_VALUE) {
            if (!architecture.has(ArmFeature.BIT_FIELD)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            boolean signedExtract = (raw & BIT_FIELD_EXTRACT_MASK) == SBFX_VALUE;
            int widthMinusOne = (raw >>> 16) & 0x1F;
            int rd = (raw >>> 12) & 0xF;
            int lsb = (raw >>> 7) & 0x1F;
            int rn = raw & 0xF;
            int width = widthMinusOne + 1;
            if (rd == PROGRAM_COUNTER || rn == PROGRAM_COUNTER || lsb + width > 32) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int packed = lsb | (width << 5);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    InstructionKind.BIT_FIELD_EXTRACT, rd, rn, -1, packed, false, false, false, 0, signedExtract);
        }

        if ((raw & BIT_FIELD_INSERT_MASK) == BIT_FIELD_INSERT_VALUE) {
            if (!architecture.has(ArmFeature.BIT_FIELD)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int msb = (raw >>> 16) & 0x1F;
            int rd = (raw >>> 12) & 0xF;
            int lsb = (raw >>> 7) & 0x1F;
            int rn = raw & 0xF;
            boolean isBfc = rn == BFC_SOURCE_SENTINEL;
            if (rd == PROGRAM_COUNTER || (!isBfc && rn == PROGRAM_COUNTER) || msb < lsb) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int width = msb - lsb + 1;
            int packed = lsb | (width << 5);
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                    InstructionKind.BIT_FIELD_INSERT, rd, isBfc ? -1 : rn, -1, packed, false, false, false);
        }

        if ((raw & RBIT_MASK) == RBIT_VALUE) {
            if (!architecture.has(ArmFeature.BIT_REVERSE)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int rd = (raw >>> 12) & 0xF;
            int rm = raw & 0xF;
            if (rd == PROGRAM_COUNTER || rm == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.BIT_REVERSE,
                    rd, rm, -1, 0, false, false, false);
        }

        if ((raw & DIVIDE_MASK) == SDIV_VALUE || (raw & DIVIDE_MASK) == UDIV_VALUE) {
            if (!architecture.has(ArmFeature.DIVIDE)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            boolean signedDivide = (raw & DIVIDE_MASK) == SDIV_VALUE;
            int rd = (raw >>> 16) & 0xF;
            int rm = (raw >>> 8) & 0xF;
            int rn = raw & 0xF;
            if (rd == PROGRAM_COUNTER || rm == PROGRAM_COUNTER || rn == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            return new DecodedInstruction(address, raw, InstructionSet.ARM, condition, InstructionKind.DIVIDE,
                    rd, rn, rm, 0, false, false, false, 0, signedDivide);
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

        // SRS (ARMv6): `1111 100 P U 1 W 0 1101 0000 0101 000 mode` — empilha LR e SPSR ATUAIS na
        // pilha do modo alvo. Espaço incondicional; compartilha bits 27:25=100 com LDM/STM
        // (abaixo), então precisa vir antes e virar UNDEFINED explícito sem a feature (em vez de
        // cair no decode de LDM/STM, mesmo cuidado do LDREX/STREX/CLREX).
        if ((raw & 0xFE5F_FFE0) == 0xF84D_0500) {
            if (!architecture.has(ArmFeature.MODE_CHANGE_INSTRUCTIONS)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean writeback = (raw & (1 << 21)) != 0;
            int mode = raw & 0x1F;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.STORE_RETURN_STATE, -1, -1, -1, mode, false, false, false,
                    0, false, writeback, false, BlockTransferMode.fromArmBits(preIndexed, addOffset));
        }

        // RFE (ARMv6): `1111 100 P U 0 W 1 Rn 0000 1010 0000 0000` — carrega PC e CPSR da pilha
        // apontada por Rn. Mesmo motivo de ordem/gating que SRS acima; bit 22=0 e bit 20=1
        // (load) a distinguem de SRS (bit22=1, bit20=0/store).
        if ((raw & 0xFE50_FFFF) == 0xF810_0A00) {
            if (!architecture.has(ArmFeature.MODE_CHANGE_INSTRUCTIONS)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            boolean preIndexed = (raw & (1 << 24)) != 0;
            boolean addOffset = (raw & (1 << 23)) != 0;
            boolean writeback = (raw & (1 << 21)) != 0;
            int rn = (raw >>> 16) & 0xF;
            return new DecodedInstruction(address, raw, InstructionSet.ARM, Condition.AL,
                    InstructionKind.RETURN_FROM_EXCEPTION, -1, rn, -1, 0, false, false, false,
                    0, false, writeback, false, BlockTransferMode.fromArmBits(preIndexed, addOffset));
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

        // MOVW/MOVT (ARMv6T2+, B3.1): precisam ser checados ANTES do dispatch ALU genérico
        // abaixo — sem este carve-out, o Passo 0 desta task provou que o padrão de bits cai no
        // dispatch ALU como TST/CMP com S=0 (opcode 0x8/0xA), decodificando errado em vez de
        // UNIMPLEMENTED (mesmo cuidado do DMB/DSB/ISB e do CLREX/PLD acima).
        if ((raw & MOVE_WIDE_MASK) == MOVW_VALUE || (raw & MOVE_WIDE_MASK) == MOVT_VALUE) {
            if (!architecture.has(ArmFeature.MOVW_MOVT)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            boolean moveTop = (raw & MOVE_WIDE_MASK) == MOVT_VALUE;
            int imm4 = (raw >>> 16) & 0xF;
            int rd = (raw >>> 12) & 0xF;
            if (rd == PROGRAM_COUNTER) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
            }
            int imm12 = raw & 0xFFF;
            int imm16 = (imm4 << 12) | imm12;
            return moveTop
                    ? new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                            InstructionKind.MOVE_TOP, rd, -1, -1, imm16, true, false, false)
                    : new DecodedInstruction(address, raw, InstructionSet.ARM, condition,
                            InstructionKind.MOV, rd, -1, -1, imm16, true, false, false);
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

        // Grupos de instrução que uma arquitetura superior adiciona (ex.: o espaço BLX/DSP do ARMv5)
        // se plugam aqui sem tocar o decoder compartilhado. Vazio em ARMv4T/ARMv5TE hoje.
        for (DecoderExtension extension : architecture.decoderExtensions()) {
            DecodedInstruction decoded = extension.tryDecode(raw, address, condition);
            if (decoded != null) {
                return decoded;
            }
        }
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    /// Restrições de registrador dos acessos exclusivos (UNPREDICTABLE no hardware → aqui
    /// viram UNDEFINED): PC em qualquer campo; nas formas doubleword o primeiro registrador do
    /// par deve ser par e diferente de r14; no STREX o status Rd não pode coincidir com a base
    /// nem com o(s) registrador(es) de dado.
    private static boolean exclusiveRegistersValid(boolean load, int sizeBytes, int rd, int rn, int rm) {
        if (rn == 15 || rd == 15) {
            return false;
        }
        boolean pair = sizeBytes == 8;
        if (load) {
            return !pair || (rd % 2 == 0 && rd != 14);
        }
        if (rm == 15) {
            return false;
        }
        if (pair && (rm % 2 != 0 || rm == 14)) {
            return false;
        }
        if (rd == rn || rd == rm) {
            return false;
        }
        return !pair || rd != rm + 1;
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
