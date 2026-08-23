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

    /// `CPS` de 16 bits do perfil M (B7.4, ARM DDI 0403E A6.7 T1): `1011 0110 011 im 0 0 I F` —
    /// `0xB662`=`CPSIE i`, `0xB672`=`CPSID i`. Muda SÓ PRIMASK (bit `I`) e/ou FAULTMASK (bit `F`);
    /// não é o `CPS` A-profile de B1.5 (que mexe em A/I/F/modo do CPSR). Gate {@link
    /// ArmFeature#M_PROFILE} — em qualquer preset A-profile este encoding continua caindo em
    /// UNDEFINED como antes (G3). `F=1` exige ainda {@link ArmFeature#M_FAULT_MASKING} (ARMv7-M).
    private static final int CPS16_MASK = 0xFFE0;
    private static final int CPS16_VALUE = 0xB660;
    private static final int CPS16_IM_BIT = 1 << 4;
    /// Bits 3 e 2 são reservados (SBZ); qualquer valor != 0 aqui não é um `CPS` válido.
    private static final int CPS16_RESERVED_MASK = (1 << 3) | (1 << 2);
    private static final int CPS16_I_BIT = 1 << 1;
    private static final int CPS16_F_BIT = 1 << 0;
    /// Empacotamento de `immediate` para `InstructionKind.CPS` (mesma convenção do CPS ARM/Thumb-2,
    /// interpretada por `StandardIrBuilder`): bit 1 = `changeFlags` (sempre 1 aqui), bit 0 = `imod`
    /// baixo (1 = desabilita/ID), bit 4 = `changeI`, bit 5 = `changeF`. `changeA`/`changeMode` = 0.
    private static final int CPS16_PACKED_CHANGE_FLAGS = 1 << 1;
    private static final int CPS16_PACKED_DISABLE_BIT = 1 << 0;
    private static final int CPS16_PACKED_I_SHIFT = 4;
    private static final int CPS16_PACKED_F_SHIFT = 5;

    /// `SETEND` de 16 bits (ARMv6, ARM DDI 0406C A8.8.120 T1, confirmado contra
    /// `target/arm/tcg/t16.decode` do QEMU): `1011 0110 010 1 E 000` — `0xB650`=`SETEND LE`,
    /// `0xB658`=`SETEND BE`. Mesma feature/comportamento observável do `SETEND` A32 (B1.5, `ArmDecoder`):
    /// {@link ArmFeature#SETEND_BIG_ENDIAN_DATA}, `immediate` empacotado = bit `E` (0/1).
    private static final int SETEND16_MASK = 0xFFF7;
    private static final int SETEND16_VALUE = 0xB650;
    private static final int SETEND16_E_BIT = 1 << 3;

    /// `CPS` de 16 bits **A/R-profile** (ARMv6, ARM DDI 0406C A8.8.27 T1, confirmado contra
    /// `target/arm/tcg/t16.decode`/`translate.c` do QEMU — `trans_CPS`, `!ENABLE_ARCH_6 ||
    /// ARM_FEATURE_M` recusa): MESMO prefixo de 11 bits que {@link #CPS16_VALUE} (perfil M, B7.4)
    /// — os dois perfis reaproveitam o mesmo espaço de encoding de 16 bits com um campo a mais no
    /// A/R-profile (bit 2 = flag `A`, reservado/SBZ no M-profile). Formato: `1011 0110 011 im 0 A
    /// I F`. Sem troca de modo (T16 não carrega um campo de modo de 5 bits: `M=0`/`mode=0`
    /// sempre). Gate {@link ArmFeature#MODE_CHANGE_INSTRUCTIONS} (ARMv6+, herdado por `ARMV6K`) e
    /// `!M_PROFILE` — em qualquer preset M-profile o bloco de {@link #CPS16_VALUE} acima já
    /// reivindicou o encoding primeiro.
    private static final int CPS16_A_BIT = 1 << 2;
    /// Bit 3 é reservado (SBZ) em AMBOS os perfis — só o M-profile trata o bit 2 também como
    /// reservado (sem campo `A`).
    private static final int CPS16_SBZ_BIT = 1 << 3;
    /// Empacotamento de `immediate` para `InstructionKind.CPS` na forma A/R-profile (mesma
    /// convenção do `CPS` ARM/Thumb-2 A/R-profile, `StandardIrBuilder`/`IrSystemExecutor`): bit 1
    /// = `changeFlags` (sempre 1 aqui — este T1 sempre muda A/I/F), bit 0 = `imod` baixo (0=IE,
    /// 1=ID), bit 3 = `changeA`, bit 4 = `changeI`, bit 5 = `changeF`. `changeMode`/`mode` = 0.
    /// (`%imod` do QEMU, `4:1 !function=plus_2`, soma 2 ao bit `im` bruto: `im=0` -> `imod=0b10`
    /// IE, `im=1` -> `imod=0b11` ID — os dois já têm o bit 1 setado.)
    private static final int CPS16_A_PROFILE_PACKED_CHANGE_FLAGS = 1 << 1;
    private static final int CPS16_A_PROFILE_PACKED_DISABLE_BIT = 1;
    private static final int CPS16_A_PROFILE_PACKED_A_SHIFT = 3;
    private static final int CPS16_A_PROFILE_PACKED_I_SHIFT = 4;
    private static final int CPS16_A_PROFILE_PACKED_F_SHIFT = 5;

    /// Inversão de bytes de 16 bits (ARMv6, ARM DDI 0406C A8.8.144/145/146 T1, confirmado contra
    /// `target/arm/tcg/t16.decode`, `@rdm`): `REV`=`1011 1010 00 mmm ddd` (`0xBA00`),
    /// `REV16`=mesmo prefixo com `..01..` (`0xBA40`), `REVSH`=`..11..` (`0xBAC0`) — `..10..` é
    /// reservado (mesmo buraco que a forma A32 já tem). Reaproveita {@link
    /// InstructionKind#BYTE_REVERSE} (mesmo `Kind`/executor da forma A32, B1.2) com `immediate`
    /// 0/1/2 para REV/REV16/REVSH. Gate {@link ArmFeature#BYTE_REVERSE} (mesma feature da forma A32).
    private static final int BYTE_REVERSE16_MASK = 0xFFC0;
    private static final int BYTE_REVERSE16_REV_VALUE = 0xBA00;
    private static final int BYTE_REVERSE16_REV16_VALUE = 0xBA40;
    private static final int BYTE_REVERSE16_REVSH_VALUE = 0xBAC0;

    /// Extensão de sinal/zero de 16 bits **sem acumulador** (ARMv6, ARM DDI 0406C
    /// A8.8.232/233/236/237 T1, confirmado contra `target/arm/tcg/t16.decode`+`translate.c` do
    /// QEMU). ⚠️ **Achado de triagem (B9.3)**: o `t16.decode` do QEMU nomeia estas 4 linhas
    /// `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB` (reaproveita o `trans_*` COM acumulador da forma A32), mas o
    /// formato `@extend` do T16 fixa `rn=15` — e `op_xta` (`translate.c`) só soma o acumulador
    /// quando `rn!=15`. Ou seja, a instrução T16 real (mnemônico do ARM ARM) é a forma SEM
    /// acumulador: `SXTH`/`SXTB`/`UXTH`/`UXTB Rd,Rm` — **NÃO** `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB`
    /// (essas só existem de verdade no espaço A32/T32 de 32 bits, com um `Rn` real). O plano
    /// mestre (`b7-plano-cobertura-isa.md`, linha B9.3) citava os nomes errados; corrigido aqui —
    /// ver "Triagem real" na spec desta task. `0xB200`=SXTH, `0xB240`=SXTB, `0xB280`=UXTH,
    /// `0xB2C0`=UXTB. Reaproveita {@link InstructionKind#EXTEND} (mesmo `Kind`/executor da forma
    /// A32, B1.2) com `sourceRegister=-1` (sem acumulador, rotação sempre 0). Gate {@link
    /// ArmFeature#EXTEND_ROTATE} (mesma feature da forma A32).
    private static final int EXTEND16_MASK = 0xFFC0;
    private static final int EXTEND16_SXTH_VALUE = 0xB200;
    private static final int EXTEND16_SXTB_VALUE = 0xB240;
    private static final int EXTEND16_UXTH_VALUE = 0xB280;
    private static final int EXTEND16_UXTB_VALUE = 0xB2C0;
    /// Campo `field` empacotado em `InstructionKind.EXTEND.immediate()` (convenção A32,
    /// `ArmDecoder`/`StandardIrBuilder#liftExtend`): `0b10`=byte, `0b11`=halfword (`0b00`=B16
    /// paralelo, não alcançável por este T1 de 16 bits).
    private static final int EXTEND_FIELD_BYTE = 0b10;
    private static final int EXTEND_FIELD_HALFWORD = 0b11;
    private static final int EXTEND_FIELD_SHIFT = 2;
    private static final int EXTEND_UNSIGNED_BIT = 1 << 4;

    /// Decodifica uma instrução THUMB16 (ou, com {@link ArmFeature#THUMB2}, um candidato Thumb de
    /// 32 bits) no endereço informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.fetch16(address & ~1) & 0xFFFF;
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

        // BLX (registrador): `0100 0111 1 mmmm 000` — como Thumb BX, mas também linka (ARMv5T+).
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

        // `BKPT #imm8` (B7.5, ARM DDI 0406C A6.7): `1011 1110 imm8`. ARMv5T+; sem a feature cai
        // no UNDEFINED de sempre (encoding nunca foi reconhecido antes desta task).
        if ((raw & 0xFF00) == 0xBE00) {
            if (!architecture.has(ArmFeature.BREAKPOINT)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int immediate = raw & 0xFF;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.BREAKPOINT, -1, -1, -1, immediate, false, false, false);
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

        // `CPS` de 16 bits do perfil M (B7.4): CPSIE/CPSID i/f → PRIMASK/FAULTMASK. Gate
        // M_PROFILE — sem ele, este encoding cai no UNDEFINED final como em qualquer preset
        // A-profile (G3). Ver StandardIrBuilder/IrSystemExecutor.executeChangeProcessorState.
        if (architecture.has(ArmFeature.M_PROFILE) && (raw & CPS16_MASK) == CPS16_VALUE) {
            if ((raw & CPS16_RESERVED_MASK) != 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            boolean disable = (raw & CPS16_IM_BIT) != 0;
            boolean changeI = (raw & CPS16_I_BIT) != 0;
            boolean changeF = (raw & CPS16_F_BIT) != 0;
            if (changeF && !architecture.has(ArmFeature.M_FAULT_MASKING)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int packed = CPS16_PACKED_CHANGE_FLAGS
                    | (disable ? CPS16_PACKED_DISABLE_BIT : 0)
                    | (changeI ? (1 << CPS16_PACKED_I_SHIFT) : 0)
                    | (changeF ? (1 << CPS16_PACKED_F_SHIFT) : 0);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.CPS, -1, -1, -1, packed, false, false, false);
        }

        // `SETEND` de 16 bits (ARMv6 T16, B9.3): ver constantes acima.
        if ((raw & SETEND16_MASK) == SETEND16_VALUE) {
            if (!architecture.has(ArmFeature.SETEND_BIG_ENDIAN_DATA)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int endianBit = (raw & SETEND16_E_BIT) != 0 ? 1 : 0;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.SETEND, -1, -1, -1, endianBit, false, false, false);
        }

        // `CPS` A/R-profile de 16 bits (ARMv6 T16, B9.3): ver constantes acima. Só é alcançado se
        // o bloco M-profile acima não reivindicou o mesmo prefixo de 11 bits.
        if (!architecture.has(ArmFeature.M_PROFILE) && (raw & CPS16_MASK) == CPS16_VALUE) {
            if (!architecture.has(ArmFeature.MODE_CHANGE_INSTRUCTIONS) || (raw & CPS16_SBZ_BIT) != 0) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            boolean disable = (raw & CPS16_IM_BIT) != 0;
            boolean changeA = (raw & CPS16_A_BIT) != 0;
            boolean changeI = (raw & CPS16_I_BIT) != 0;
            boolean changeF = (raw & CPS16_F_BIT) != 0;
            int packed = CPS16_A_PROFILE_PACKED_CHANGE_FLAGS
                    | (disable ? CPS16_A_PROFILE_PACKED_DISABLE_BIT : 0)
                    | (changeA ? (1 << CPS16_A_PROFILE_PACKED_A_SHIFT) : 0)
                    | (changeI ? (1 << CPS16_A_PROFILE_PACKED_I_SHIFT) : 0)
                    | (changeF ? (1 << CPS16_A_PROFILE_PACKED_F_SHIFT) : 0);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.CPS, -1, -1, -1, packed, false, false, false);
        }

        // `REV`/`REV16`/`REVSH` de 16 bits (ARMv6 T16, B9.3): ver constantes acima.
        if ((raw & BYTE_REVERSE16_MASK) == BYTE_REVERSE16_REV_VALUE
                || (raw & BYTE_REVERSE16_MASK) == BYTE_REVERSE16_REV16_VALUE
                || (raw & BYTE_REVERSE16_MASK) == BYTE_REVERSE16_REVSH_VALUE) {
            if (!architecture.has(ArmFeature.BYTE_REVERSE)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int selector = raw & BYTE_REVERSE16_MASK;
            int variant = selector == BYTE_REVERSE16_REV_VALUE ? 0 : selector == BYTE_REVERSE16_REV16_VALUE ? 1 : 2;
            int rm = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.BYTE_REVERSE, rd, rm, -1, variant, false, false, false);
        }

        // `SXTH`/`SXTB`/`UXTH`/`UXTB` de 16 bits, sem acumulador (ARMv6 T16, B9.3): ver
        // constantes acima (achado de triagem: NÃO são `SXTAH`/`SXTAB`/`UXTAH`/`UXTAB`).
        if ((raw & EXTEND16_MASK) == EXTEND16_SXTH_VALUE || (raw & EXTEND16_MASK) == EXTEND16_SXTB_VALUE
                || (raw & EXTEND16_MASK) == EXTEND16_UXTH_VALUE || (raw & EXTEND16_MASK) == EXTEND16_UXTB_VALUE) {
            if (!architecture.has(ArmFeature.EXTEND_ROTATE)) {
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, Condition.AL);
            }
            int selector = raw & EXTEND16_MASK;
            boolean unsigned = selector == EXTEND16_UXTH_VALUE || selector == EXTEND16_UXTB_VALUE;
            int field = (selector == EXTEND16_SXTB_VALUE || selector == EXTEND16_UXTB_VALUE)
                    ? EXTEND_FIELD_BYTE : EXTEND_FIELD_HALFWORD;
            int packed = (field << EXTEND_FIELD_SHIFT) | (unsigned ? EXTEND_UNSIGNED_BIT : 0);
            int rm = (raw >>> 3) & 0x7;
            int rd = raw & 0x7;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, Condition.AL,
                    InstructionKind.EXTEND, rd, -1, rm, packed, false, false, false);
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

        // Sufixo BLX (H=01): a segunda metade de um branch longo que troca para ARM (ARMv5T+).
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
        int lo = memory.fetch16((address + 2) & ~1) & 0xFFFF;
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
