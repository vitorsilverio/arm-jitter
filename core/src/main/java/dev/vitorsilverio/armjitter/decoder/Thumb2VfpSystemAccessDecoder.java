package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// `VMSR_VMRS` (perfil M, `reg=FPSCR`) + `VLDR_sysreg`/`VSTR_sysreg` (B15.3) —
/// `target/isa-decode/m-nocp.decode` (QEMU): as 3 formas específicas do MESMO bloco `{...}` que
/// {@link Thumb2NocpDecoder} já prioriza corretamente, checadas ANTES do `NOCP` genérico (padrão
/// mais específico primeiro). Reusa `ArmCore.fpscr()` (incondicional desde a B3.3) — perfil M não
/// tem banco FPSCR próprio, só decodifica/executa o acesso memory-mapped a ele.
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords Thumb-2 combinados (`hi<<16 | lo`),
/// igual a {@link Thumb2LoadStoreDecoder}/{@link Thumb2NocpDecoder}. `bits[31:28]` são fixos em
/// `1110` para as 3 formas — embora o `m-nocp.decode` real as escreva com `----` (don't-care) na
/// coluna correspondente, essa notação reflete o contexto de inclusão do arquivo (não reproduzido
/// no trecho citado pela task), e a arquitetura real (classe Thumb-2 "Coprocessor, Advanced SIMD,
/// and Floating-point instructions", `bits[31:27]=0b11101`) fixa esse nibble em `1110`: o espaço com
/// `bits[31:28]=1111` pertence inteiramente a `NOCP_8_1` (`Thumb2NocpDecoder`), nunca a estas 3
/// formas (confirmado contra `target/arm/tcg/translate-m-nocp.c`, que só reconhece `VMSR_VMRS`/
/// `VLDR_sysreg`/`VSTR_sysreg` no espaço pré-ARMv8.1-M).
///
/// **`VMSR_VMRS`**: `---- 1110 111 l:1 reg:4 rt:4 1010 0001 0000` — MESMO layout de bits que a forma
/// A-profile já decodificada por {@link VfpDecoder#decodeCoreTransferOrSystem} (bits[23:21]=111,
/// reg em bits[19:16], rt em bits[15:12]), reaproveitando {@link InstructionKind#VFP_SYSTEM_TRANSFER}
/// sem nenhuma mudança: a única divergência real de semântica entre A-profile e perfil M é a regra
/// de `Rt=15` (A-profile aliasa para APSR; perfil M é UNPREDICTABLE/UNDEF) — e essa lógica de
/// aliasing vive inteiramente no EXECUTOR (`IrVfpExecutor#executeVfpSystemTransfer`, comparando
/// `op.armRegister() == APSR_NZCV_ENCODING`), nunca no decoder; como este decoder recusa `Rt=15`
/// (nunca produz essa instrução com `armRegister=15`), o ramo de aliasing do executor simplesmente
/// nunca é alcançado por uma instrução M-profile, sem precisar de `boolean`/Kind dedicado.
///
/// **`VLDR_sysreg`/`VSTR_sysreg`**: `reg` (`%vldr_sysreg`, `bit22:bits[15:13]`) restrito a `1`
/// (FPSCR) nesta task — os outros 4 valores reais da arquitetura (`FPSCR_NZCVQC`/`VPR`,`P0`/
/// `FPCXT_NS`/`FPCXT_S`) dependem de B15.4/B15.6/B16, ainda não implementadas (`reg != 1` devolve
/// `null`, G8, nunca confundido com FPSCR). Não são `LOAD`/`STORE` comuns (o valor não vai para um
/// GPR): usam {@link InstructionKind#VFP_SYSREG_LOAD}/{@link InstructionKind#VFP_SYSREG_STORE}
/// dedicados, com `destinationRegister=-1` sinalizando "não é GPR" — o mesmo campo `writeback`/
/// `postIndexed` de `LOAD`/`STORE` genérico cobre a aritmética de endereço (`p=1`→offset com
/// writeback opcional; `p=0,w=1` forçado→post-indexed, mesma convenção de
/// {@link Thumb2LoadStoreDecoder}). `Rn=15` é recusado (UNPREDICTABLE no perfil M).
///
/// Anexado via {@link ArmArchitecture#thumb32DecoderExtensions()}, gateado por
/// {@link ArmFeature#M_PROFILE} — precisa vir ANTES de {@link Thumb2NocpDecoder} na lista: a forma 1
/// de `NOCP` (`hi=1110`) colide em parte do espaço destas 3 instruções (mesmo bloco QEMU, que
/// prioriza os padrões específicos primeiro).
public final class Thumb2VfpSystemAccessDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Único valor de `reg` implementado nesta task (`FPSCR`) — mesmo valor/nome que
    /// {@link VfpDecoder} já usa para a forma A-profile.
    private static final int FPSCR_REGISTER_SELECTOR = 0x1;
    /// `VPR` (B16.2, `Inclui` item 6 da task — a B15.3 documentou este valor como "fica fora
    /// (B16)"): reg = `12` no MESMO campo de `VMSR_VMRS`. Só aceito sob
    /// {@link ArmFeature#MVE_INTEGER} — sem MVE, `VPR` não existe, e este valor de `reg` continua
    /// caindo em `NOCP` genérico via {@link Thumb2NocpDecoder} (comportamento anterior, G3).
    private static final int VPR_REGISTER_SELECTOR = 0xC;
    private static final int PROGRAM_COUNTER = 15;

    // ── VMSR_VMRS: `---- 1110 111 l:1 reg:4 rt:4 1010 0001 0000` ──────────────────────────────

    private static final int VMSR_VMRS_MASK = 0xFFE0_0FFF;
    private static final int VMSR_VMRS_VALUE = 0xEEE0_0A10;
    private static final int VMSR_VMRS_LOAD_BIT = 1 << 20;
    private static final int VMSR_VMRS_REG_SHIFT = 16;
    private static final int VMSR_VMRS_REG_MASK = 0xF;
    private static final int VMSR_VMRS_RT_SHIFT = 12;
    private static final int VMSR_VMRS_RT_MASK = 0xF;

    // ── VLDR_sysreg/VSTR_sysreg: `---- 110 P . . W 1 .... ... 0 111 11 .......` ───────────────
    // (P=bit24, a=bit23, reg-high=bit22, W=bit21, L=bit20/direção, Rn=bits[19:16],
    //  reg-low=bits[15:13], imm7=bits[6:0]×4 — ver Javadoc da classe).

    private static final int VLDR_VSTR_SYSREG_MASK = 0xFE00_1F80;
    private static final int VLDR_VSTR_SYSREG_VALUE = 0xEC00_0F80;
    private static final int P_BIT = 1 << 24;
    private static final int ADD_BIT = 1 << 23;
    private static final int REG_HIGH_BIT = 1 << 22;
    private static final int WRITEBACK_BIT = 1 << 21;
    private static final int LOAD_BIT = 1 << 20;
    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0xF;
    private static final int REG_LOW_SHIFT = 13;
    private static final int REG_LOW_MASK = 0x7;
    private static final int IMM7_MASK = 0x7F;
    private static final int IMM_SCALE_BYTES = 4;

    public Thumb2VfpSystemAccessDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.M_PROFILE)) {
            return null;
        }
        DecodedInstruction vmsrVmrs = tryDecodeVmsrVmrs(raw, address, condition, architecture);
        if (vmsrVmrs != null) {
            return vmsrVmrs;
        }
        return tryDecodeVldrVstrSysreg(raw, address, condition);
    }

    private static DecodedInstruction tryDecodeVmsrVmrs(int raw, int address, Condition condition,
            ArmArchitecture architecture) {
        if ((raw & VMSR_VMRS_MASK) != VMSR_VMRS_VALUE) {
            return null;
        }
        int reg = (raw >>> VMSR_VMRS_REG_SHIFT) & VMSR_VMRS_REG_MASK;
        int rt = (raw >>> VMSR_VMRS_RT_SHIFT) & VMSR_VMRS_RT_MASK;
        if (rt == PROGRAM_COUNTER) {
            return null; // UNPREDICTABLE no perfil M (diferente do aliasing APSR da A-profile).
        }
        boolean read = (raw & VMSR_VMRS_LOAD_BIT) != 0; // l=1: VMRS (registrador -> rt).
        if (reg == FPSCR_REGISTER_SELECTOR) {
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.VFP_SYSTEM_TRANSFER, rt, -1, -1, 0, false, false, read);
        }
        if (reg == VPR_REGISTER_SELECTOR && architecture.has(ArmFeature.MVE_INTEGER)) {
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.VPR_TRANSFER, rt, -1, -1, 0, false, false, read);
        }
        return null; // FPSCR_NZCVQC/FPCXT_NS/FPCXT_S — B15.4/B15.6 (Security Extension), ainda não implementadas.
    }

    private static DecodedInstruction tryDecodeVldrVstrSysreg(int raw, int address, Condition condition) {
        if ((raw & VLDR_VSTR_SYSREG_MASK) != VLDR_VSTR_SYSREG_VALUE) {
            return null;
        }
        boolean preIndexed = (raw & P_BIT) != 0;
        if (!preIndexed && (raw & WRITEBACK_BIT) == 0) {
            return null; // P=0,W=0 é "SEE Related encodings" (outra instrução, fora deste bloco).
        }
        int reg = ((raw & REG_HIGH_BIT) != 0 ? 0b1000 : 0) | ((raw >>> REG_LOW_SHIFT) & REG_LOW_MASK);
        if (reg != FPSCR_REGISTER_SELECTOR) {
            return null; // FPSCR_NZCVQC/VPR/P0/FPCXT_NS/FPCXT_S — mesma exclusão de VMSR_VMRS.
        }
        int rn = (raw >>> RN_SHIFT) & RN_MASK;
        if (rn == PROGRAM_COUNTER) {
            return null; // UNPREDICTABLE.
        }
        boolean add = (raw & ADD_BIT) != 0;
        int imm7 = raw & IMM7_MASK;
        int offsetBytes = (add ? imm7 : -imm7) * IMM_SCALE_BYTES;
        boolean postIndexed = !preIndexed;
        // P=1: `w` é o campo real de writeback. P=0 (post-indexed): writeback é sempre implícito
        // (mesma convenção de `LOAD`/`STORE` genérico — ver `IrMemoryExecutor#executeLoad`).
        boolean writeback = postIndexed || (raw & WRITEBACK_BIT) != 0;
        boolean load = (raw & LOAD_BIT) != 0;
        InstructionKind kind = load ? InstructionKind.VFP_SYSREG_LOAD : InstructionKind.VFP_SYSREG_STORE;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                -1, rn, -1, offsetBytes, false, false, false, 4, false, writeback, postIndexed);
    }
}
