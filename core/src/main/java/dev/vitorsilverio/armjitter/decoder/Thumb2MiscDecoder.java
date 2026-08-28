package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;

/// Decodifica o grupo residual "Branches and miscellaneous control" Thumb-2 de 32 bits (B2.5)
/// tratado por esta task: hints (`NOP`/`YIELD`/`WFE`/`WFI`/`SEV`), barreiras de memória
/// (`DMB`/`DSB`/`ISB`) e `MRS`/`MSR` (forma registrador). Anexado via
/// {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando
/// {@link ArmFeature#THUMB2} está habilitado (o chamador, {@link ThumbDecoder}, só invoca as
/// extensões de 32 bits com essa feature ligada).
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`, hi =
/// primeiro halfword nos bits altos), igual a {@link Thumb2DataProcessingDecoder}. Encodings
/// confirmados contra o QEMU `target/arm/tcg/t32.decode` (seção "Hints, and CPS" / "Miscellaneous
/// control" / `MRS_reg`/`MSR_reg`), que reproduz a ARM DDI 0406C A5.3.
///
/// <p><b>Fora de escopo</b> (deliberado — ver `docs/isa-nao-aplicavel.tsv`): `SB` (`FEAT_SB`,
/// ARMv8.0 opcional, genuinamente POSTERIOR a v7-A — critério de versão de arquitetura, não de
/// consumidor).
///
/// <p><b>B9.7</b> acrescenta `BXJ` (trivialmente equivalente a `BX`, Jazelle não implementado —
/// mesmo `InstructionKind#BRANCH_EXCHANGE`), `UDF.W` (mesmo `InstructionKind#UDF` de B9.1) e
/// `SUBS PC, LR, #imm` (alias "T5" de exception return — reusa `InstructionKind#SUB`, o executor
/// genérico já trata `Rd==PC&amp;&amp;setFlags`, G1). `ERET`/`SMC`/`MRS_bank`/`MSR_bank`
/// continuam `UNIMPLEMENTED` — são instruções REAIS do ARMv7VE/Security Extensions (mesma versão
/// v7-A, extensões optativas, não posteriores), então NÃO entram em `isa-nao-aplicavel.tsv`
/// (regra do usuário, ver memória `feedback-nunca-excluir-instrucao-arm`, mesmo precedente da
/// escada EL1/EL2 do AArch64/B10). Implementá-las exige Hyp mode + Monitor mode de 32 bits
/// (registradores bancados `ELR_hyp`/`SPSR_hyp`/`LR_mon`/`SPSR_mon`, modos de CPU novos) — fora
/// do escopo desta task de decode (`b9.7-t32-thumb2.md`), registrado como próxima escada.
///
/// <p><b>B9.8.2</b> implementa `HVC` (A32 e T32) de verdade — ver
/// {@link #decodeHvc}/{@link ArmFeature#HYPERVISOR_CALL}. <b>B9.8.3</b> implementa `SMC` (A32 e
/// T32) — ver {@link #decodeSmc}/{@link ArmFeature#SECURE_MONITOR_CALL}; `ERET`/`MRS_bank`/
/// `MSR_bank` seguem como próximas tasks da mesma escada (`B9.8.4`/`B9.8.5`).
///
/// <p><b>B9.11</b> (achado colateral da B9.10): hints largos, `CPS.W` e `UDF.W` decodificavam sob
/// `ARMV6M` sem gate (a arquitetura real só tem as formas T1 de 16 bits desses três — ARM DDI
/// 0419C A3.3.1). Ver {@link ArmFeature#M_PROFILE_WIDE_MISC_CONTROL} (presente só em `ARMV7M`) e
/// os gates no início de {@link #decodeHintsOrCps}/{@link #decodeUdf}. O alias de exception-return
/// `SUBS PC,LR,#imm` (ver {@link #decodeExceptionReturnSub}) foi fechado para `M_PROFILE` inteiro
/// (v6-M e v7-M), não só v6-M — não existe em perfil M nenhum.
///
/// <p><b>B2.7 PR3</b>: `CPS` de 32 bits (antes fora de escopo — mesmo prefixo de hi =
/// {@code 0xF3AF} desta classe, distinguido de hints por `imod`/`M` ≠ 0, ver
/// {@link #decodeHintsOrCps}) e `CLREX` de 32 bits (antes fora de escopo — mesmo espaço
/// "Miscellaneous control" das barreiras, ver {@link #decodeMiscControl}) agora decodificados,
/// reusando {@code IrOp.ChangeProcessorState}/{@code IrOp.ClearExclusive} de B1.4/B1.5 sem
/// duplicar semântica — `LDREX`/`STREX` de 32 bits ficam em {@link Thumb2LoadStoreDecoder} (mesmo
/// prefixo de 7 bits de `LDRD`/`STRD`, não este).
public final class Thumb2MiscDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar {@link ArmFeature#WAIT_HINTS}
    /// (gate fino de `WFI`, dentro do `THUMB2` mais amplo já garantido pelo chamador),
    /// {@link ArmFeature#MEMORY_BARRIERS} (gate de `DMB`/`DSB`/`ISB`),
    /// {@link ArmFeature#EXCLUSIVE_SIZED} (gate de `CLREX`, B2.7 PR3 — MESMA feature que o
    /// ARM clássico usa para `CLREX`/`LDREX*B/H/D`/`STREX*B/H/D`) e
    /// {@link ArmFeature#MODE_CHANGE_INSTRUCTIONS} (gate de `CPS.W`, B2.7 PR3 — MESMA feature do
    /// `CPS` ARM clássico), então guarda a arquitetura — mesmo padrão de
    /// {@link ArmDecoder}/{@link ThumbDecoder}, diferente de {@link Thumb2DataProcessingDecoder}
    /// (que não precisa de gate mais fino que `THUMB2`).
    public Thumb2MiscDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    /// Registrador PC — usado para checar UNPREDICTABLE (`Rd`/`Rn`==PC) em `MRS_bank`/`MSR_bank`
    /// (B9.8.5).
    private static final int PROGRAM_COUNTER = 15;

    /// `hi` fixo do subgrupo "Hints, and CPS" (ARM DDI 0406C A5.3.5) — `1111 0011 1010 1111`.
    private static final int HINTS_AND_CPS_HI = 0xF3AF;
    /// `lo[15:8]` fixo (`1000 0000`) que, dentro do subgrupo acima, seleciona a forma "hint"
    /// (`imod==00 && M==0`, ver `HINTS_TOP_BYTE_MASK`) em vez de `CPS`.
    private static final int HINTS_TOP_BYTE = 0x80;
    private static final int TOP_BYTE_SHIFT = 8;
    private static final int TOP_BYTE_MASK = 0xFF;
    /// `lo[7:0]` seleciona o hint específico dentro do subgrupo. Qualquer valor fora da tabela
    /// abaixo é "reserved hint, behaves as nop" (mesmo comentário do QEMU t32.decode).
    private static final int HINT_SELECTOR_MASK = 0xFF;
    private static final int HINT_NOP = 0x00;
    private static final int HINT_YIELD = 0x01;
    private static final int HINT_WFE = 0x02;
    private static final int HINT_WFI = 0x03;
    private static final int HINT_SEV = 0x04;

    /// `hi` fixo do subgrupo "Miscellaneous control" (barreiras + `CLREX`/`SB`) — `1111 0011 1011
    /// 1111`.
    private static final int MISC_CONTROL_HI = 0xF3BF;
    /// `lo[15:8]` fixo (`1000 1111`) de todo o subgrupo "Miscellaneous control".
    private static final int MISC_CONTROL_TOP_BYTE = 0x8F;
    /// `lo[7:4]` distingue a barreira; `lo[3:0]` é o campo `option` (ignorado — ver
    /// {@code IrOp.MemoryBarrier}).
    private static final int BARRIER_OP_SHIFT = 4;
    private static final int BARRIER_OP_MASK = 0xF;
    private static final int BARRIER_OPTION_MASK = 0xF;
    private static final int BARRIER_OP_DSB = 0x4;
    private static final int BARRIER_OP_DMB = 0x5;
    private static final int BARRIER_OP_ISB = 0x6;

    /// `r` do subgrupo `MRS_reg`/`MRS_bank` é o bit menos significativo do terceiro nibble de `hi`
    /// (bits 7:4 = `111r`) — bit 4, não bit 8 (esse é parte do prefixo fixo `0011` do segundo
    /// nibble, bits 11:8). `r=0`: CPSR (`MRS_reg`) ou registrador geral (`MRS_bank`); `r=1`: SPSR
    /// nos dois casos.
    private static final int MRS_R_BIT_IN_HI = 1 << 4;
    /// `lo[15:12]` fixo (`1000`) e `lo[7:0]` fixo (`0000 0000`) de `MRS_reg`; `lo[11:8]` = `rd`.
    private static final int MRS_LO_FIXED_MASK = 0xF0FF;
    private static final int MRS_LO_FIXED_VALUE = 0x8000;
    private static final int MRS_RD_SHIFT = 8;
    private static final int MRS_RD_MASK = 0xF;

    /// `MRS_bank` (B9.8.5, ARM DDI 0406C A8.8.64): mesmo prefixo `hi[15:5]` de `MRS_reg`
    /// (`1111 0011 111`), mas `hi[3:0]` deixa de ser fixo em `1111` — vira a metade alta do `sysm`
    /// (bits\[19:16\] do encoding real), e `hi[4]` continua sendo `r`. `MRS_HI_MASK`/`VALUE` genérico
    /// (livre em `hi[4:0]`) substitui a igualdade exata que o dispatch de `tryDecode` usava antes
    /// desta task — sem essa generalização, `decodeMrs` nunca é chamado para `sysm`-alto != `0xF`.
    private static final int MRS_HI_MASK = 0xFFE0;
    private static final int MRS_HI_VALUE = 0xF3E0;
    /// `lo[7:5]` fixo (`001`) e `lo[3:0]` fixo (`0000`) de `MRS_bank`/`MSR_bank` (MESMA forma nos
    /// dois — `lo[11:8]` = `rd` em `MRS_bank`/metade alta do `sysm` em `MSR_bank`, `lo[4]` = metade
    /// baixa do `sysm` nos dois). Único bit que distingue de `MRS_LO_FIXED_VALUE`/`MSR_LO_FIXED_VALUE`
    /// (`lo[5]`, sempre `0` na forma registrador) é o que evita colisão real entre as formas.
    private static final int BANKED_LO_MASK = 0xF0EF;
    private static final int BANKED_LO_VALUE = 0x8020;
    private static final int BANKED_SYSM_LOW_BIT_IN_LO = 1 << 4;
    private static final int BANKED_SYSM_HIGH_SHIFT_INTO_SYSM = 4;
    private static final int BANKED_SYSM_HIGH_MASK = 0xF;

    /// `hi[8:4]` fixo (`1000 0` com `r` no bit 4) de `MSR_reg`; `hi[3:0]` = `rn` (registrador
    /// geral cujo valor é escrito no PSR).
    private static final int MSR_HI_MASK = 0xFFE0;
    private static final int MSR_HI_VALUE = 0xF380;
    private static final int MSR_R_BIT_IN_HI = 1 << 4;
    private static final int MSR_RN_MASK = 0xF;
    /// `lo[15:12]` fixo (`1000`) e `lo[7:0]` fixo (`0000 0000`) de `MSR_reg`; `lo[11:8]` =
    /// `mask` (campo de campos PSR, `_fsxc`).
    private static final int MSR_LO_FIXED_MASK = 0xF0FF;
    private static final int MSR_LO_FIXED_VALUE = 0x8000;
    private static final int MSR_FIELD_MASK_SHIFT = 8;
    private static final int MSR_FIELD_MASK_MASK = 0xF;

    /// Bit empacotado em {@code DecodedInstruction.immediate()}/{@code destinationRegister()} das
    /// formas `MRS`/`MSR` (mesma convenção do {@code ArmDecoder} clássico): bit 4 = SPSR (vs CPSR).
    private static final int PSR_SPSR_BIT = 0x10;
    private static final int PSR_FIELD_MASK_BITS = 0xF;

    /// `CPS` de 32 bits (ARM DDI 0406C A5.3.5, `&cps`): dentro do subgrupo "Hints, and CPS" (mesmo
    /// `hi` de {@link #HINTS_AND_CPS_HI}), `lo = 1000 0 imod:2 M:1 A:1 I:1 F:1 mode:5` — os campos
    /// vivem em posições DIFERENTES de `MRS`/`MSR` (que usam `lo[15:12]`/`lo[11:8]` fixos), então
    /// precisam de shifts próprios.
    private static final int CPS_IMOD_SHIFT = 9;
    private static final int CPS_IMOD_MASK = 0x3;
    private static final int CPS_MODE_CHANGE_BIT = 8;
    private static final int CPS_A_BIT = 7;
    private static final int CPS_I_BIT = 6;
    private static final int CPS_F_BIT = 5;
    private static final int CPS_MODE_MASK = 0x1F;

    /// Empacotamento de `DecodedInstruction.immediate()` para `InstructionKind.CPS` — MESMA
    /// convenção que `ArmDecoder` usa para o `CPS` ARM clássico (`StandardIrBuilder` decodifica os
    /// dois encodings com o mesmo código): `imod` nos 2 bits baixos, depois `M`/`A`/`I`/`F`/`mode`.
    private static final int CPS_PACKED_MODE_CHANGE_SHIFT = 2;
    private static final int CPS_PACKED_A_SHIFT = 3;
    private static final int CPS_PACKED_I_SHIFT = 4;
    private static final int CPS_PACKED_F_SHIFT = 5;
    private static final int CPS_PACKED_MODE_SHIFT = 6;

    /// `MRS_v7m`/`MSR_v7m` (perfil M, B7.4): compartilham o `hi` de `MRS_reg`/`MSR_reg`, mas `lo`
    /// muda de significado — `lo[15:12]=1000` fixo, `lo[11:8]`=Rd (MRS) ou máscara de campos (MSR,
    /// não modelada) e `lo[7:0]`=SYSm (número do registrador especial, não precisa ser zero como no
    /// A-profile). Só interpretado assim quando {@link ArmFeature#M_PROFILE} está ativo.
    private static final int V7M_LO_FIXED_MASK = 0xF000;
    private static final int V7M_LO_FIXED_VALUE = 0x8000;
    private static final int V7M_SYSM_MASK = 0xFF;

    /// `CLREX` de 32 bits (ARM DDI 0406C A8.8.30): encoding TOTALMENTE fixo (nenhum campo de
    /// registrador) dentro do subgrupo "Miscellaneous control" — `lo = 1000 1111 0010 1111`, o
    /// MESMO `hi` de {@link #MISC_CONTROL_HI} que as barreiras `DMB`/`DSB`/`ISB` já usam.
    private static final int CLREX_LO = 0x8F2F;

    /// `BXJ` (B9.7, ARM DDI 0406C A8.8.27): `hi[15:4]=1111 0011 1100`, `rm`=`hi[3:0]`,
    /// `lo=1000 1111 0000 0000` fixo. QEMU `trans_BXJ` real: sem suporte a Jazelle no host,
    /// comportamento "trivialmente equivalente a BX" (`gen_bx`) — reusa `InstructionKind
    /// #BRANCH_EXCHANGE` sem semântica nova (G1), mesmo Kind que `BX` ARM clássico/Thumb-1 já usa.
    private static final int BXJ_HI_MASK = 0xFFF0;
    private static final int BXJ_HI_VALUE = 0xF3C0;
    private static final int BXJ_LO = 0x8F00;

    /// `UDF.W` (B9.7, ARM DDI 0406C A8.8.247): `hi[15:4]=1111 0111 1111` (nibble baixo de `hi`
    /// ignorado, sem campo nomeado — QEMU `t32.decode` usa `----`), `lo[15:12]=1010` fixo (resto
    /// ignorado). Mesmo `InstructionKind#UDF` do encoding ARM clássico equivalente (B9.1,
    /// {@link ArmDecoder}) — instrução permanentemente indefinida, sem feature/gate.
    private static final int UDF_HI_MASK = 0xFFF0;
    private static final int UDF_HI_VALUE = 0xF7F0;
    private static final int UDF_LO_MASK = 0xF000;
    private static final int UDF_LO_VALUE = 0xA000;

    /// `SUBS PC, LR, #imm` (B9.7, alias "T5" de exception return, ARM DDI 0406C B9.3.19): mesmo
    /// encoding-espaço de `ERET` — QEMU `trans_ERET` exige {@code ARM_FEATURE_V7VE} (Extensões de
    /// Virtualização, modo Hyp) e devolve `false` sem ela, caindo no fallback `trans_SUB_rri`
    /// (ARM ARM: "at v6T2, this is the T5 encoding of SUBS PC, LR, #IMM ... with v7VE, IMM=0 is
    /// redefined as ERET"). Este projeto não modela EL2/Hyp de 32 bits (sem `MRS_bank`/`MSR_bank`,
    /// ver `docs/isa-nao-aplicavel.tsv`) — `ERET` fica de fora, mas a forma-base `SUB` (TODOS os
    /// `imm8`, incl. `0`) é real e reusa `InstructionKind#SUB` sem IR nova: o executor genérico de
    /// `SUB` já trata `Rd==PC &amp;&amp; setFlags` como exception return
    /// ({@code IrAluExecutor#restoreCpsrFromCurrentSpsr}, mesmo caminho que `MOVS PC,LR` ARM
    /// clássico já usa) — G1.
    private static final int EXCEPTION_RETURN_SUB_HI = 0xF3DE;
    private static final int EXCEPTION_RETURN_SUB_LO_FIXED_MASK = 0xFF00;
    private static final int EXCEPTION_RETURN_SUB_LO_FIXED_VALUE = 0x8F00;
    private static final int EXCEPTION_RETURN_DESTINATION = 15; // PC
    private static final int EXCEPTION_RETURN_SOURCE = 14; // LR

    /// `HVC` T32 (B9.8.2, ARM DDI 0406C A8.8.65): `hi = 1111 0111 1110 xxxx` (nibble baixo de
    /// `hi` = bits\[19:16\] do `imm16`), `lo = 1000 xxxx xxxx xxxx` (`lo[15:12]=1000` fixo,
    /// `lo[11:0]` = bits\[11:0\] do `imm16`). Confirmado contra `target/arm/tcg/t32.decode` real do
    /// QEMU (`HVC 1111 0111 1110 .... 1000 .... .... .... &amp;i imm=%imm16_16_0`, com
    /// `%imm16_16_0 16:4 26:1... 0:12` = bits altos do halfword 1 seguidos dos 12 baixos do
    /// halfword 2) — MESMO prefixo de `hi` que `SMC`/`UDF.W` (`0xF7Fx`/`0xF7Ex`), distinguido pelo
    /// nibble baixo de `hi` (`1110`, não `1111`).
    private static final int HVC_HI_MASK = 0xFFF0;
    private static final int HVC_HI_VALUE = 0xF7E0;
    private static final int HVC_HI_IMM_MASK = 0xF;
    private static final int HVC_HI_IMM_SHIFT_IN_IMM16 = 12;
    private static final int HVC_LO_FIXED_MASK = 0xF000;
    private static final int HVC_LO_FIXED_VALUE = 0x8000;
    private static final int HVC_LO_IMM_MASK = 0xFFF;

    /// `SMC` T32 (B9.8.3, ARM DDI 0406C A8.8.20): `hi = 1111 0111 1111 iiii` (`imm4` = nibble
    /// baixo de `hi`), `lo = 1000 0000 0000 0000` (fixo, sem imediato — ao contrário de `HVC`, o
    /// `imm4` mora inteiro em `hi`). Confirmado contra `target/arm/tcg/t32.decode` real do QEMU
    /// (`SMC 1111 0111 1111 imm:4 1000 0000 0000 0000 &amp;i`).
    ///
    /// <p><b>MESMO prefixo de `hi` que `UDF.W`</b> (`UDF_HI_MASK`/`UDF_HI_VALUE` = `0xF7F0`,
    /// nibble baixo de `hi` ali é apenas ignorado, não um imediato) — só o `lo` distingue as duas
    /// (`UDF`: `lo[15:12]=1010`; `SMC`: `lo` inteiro fixo em `0x8000`). {@link #tryDecode} tenta
    /// {@link #decodeUdf} primeiro e, se o `lo` não bater, tenta {@link #decodeSmc} — sem isso,
    /// todo `SMC` T32 cairia silenciosamente em `null` (G8: instrução real engolida por outro
    /// dispatch em vez de virar `UNIMPLEMENTED`, mesma categoria de bug já corrigida pela `E6`/
    /// `E8` em outros decoders).
    private static final int SMC_HI_IMM_MASK = 0xF;
    private static final int SMC_LO_VALUE = 0x8000;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        int hi = raw >>> 16;
        int lo = raw & 0xFFFF;

        if (hi == HINTS_AND_CPS_HI) {
            return decodeHintsOrCps(raw, address, condition, lo);
        }
        if (hi == MISC_CONTROL_HI) {
            return decodeMiscControl(raw, address, condition, lo);
        }
        if ((hi & MRS_HI_MASK) == MRS_HI_VALUE) {
            return decodeMrs(raw, address, condition, hi, lo);
        }
        if ((hi & MSR_HI_MASK) == MSR_HI_VALUE) {
            return decodeMsr(raw, address, condition, hi, lo);
        }
        if ((hi & BXJ_HI_MASK) == BXJ_HI_VALUE) {
            return decodeBxj(raw, address, condition, hi, lo);
        }
        if ((hi & UDF_HI_MASK) == UDF_HI_VALUE) {
            // UDF.W e SMC compartilham o mesmo prefixo de hi (0xF7Fx) — só o lo distingue (ver
            // Javadoc de SMC_LO_VALUE). Tentar UDF primeiro e cair para SMC evita que um SMC
            // válido seja engolido como null (G8).
            DecodedInstruction udf = decodeUdf(raw, address, condition, lo);
            if (udf != null) {
                return udf;
            }
            return decodeSmc(raw, address, condition, hi, lo);
        }
        if ((hi & HVC_HI_MASK) == HVC_HI_VALUE) {
            return decodeHvc(raw, address, condition, hi, lo);
        }
        if (hi == EXCEPTION_RETURN_SUB_HI) {
            return decodeExceptionReturnSub(raw, address, condition, lo);
        }
        return null;
    }

    // ── SUBS PC, LR, #imm (alias "T5" de exception return) — A8.8.121/B9.3.19 ──────────────

    private DecodedInstruction decodeExceptionReturnSub(int raw, int address, Condition condition, int lo) {
        if (architecture.has(ArmFeature.M_PROFILE)) {
            // O alias T5 de exception-return não existe em perfil M nenhum (v6-M OU v7-M): a
            // arquitetura M-profile não tem Hyp mode/SPSR desse tipo, o retorno de exceção é via
            // EXC_RETURN (BX/POP), nunca SUBS PC,LR (B9.11, achado colateral da B9.10 — mesma
            // categoria do bxjIsUndefinedUnderMProfile já existente para BXJ).
            return null;
        }
        if ((lo & EXCEPTION_RETURN_SUB_LO_FIXED_MASK) != EXCEPTION_RETURN_SUB_LO_FIXED_VALUE) {
            return null;
        }
        int imm8 = lo & 0xFF;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.SUB,
                EXCEPTION_RETURN_DESTINATION, EXCEPTION_RETURN_SOURCE, -1, imm8, true, true, false);
    }

    // ── BXJ — A8.8.27 ────────────────────────────────────────────────────────────────────

    private DecodedInstruction decodeBxj(int raw, int address, Condition condition, int hi, int lo) {
        if (lo != BXJ_LO) {
            return null;
        }
        if (architecture.has(ArmFeature.M_PROFILE)) {
            // QEMU `trans_BXJ`: `arm_dc_feature(s, ARM_FEATURE_M)` devolve `false` (UNDEFINED) —
            // BXJ não existe no perfil M.
            return null;
        }
        int rm = hi & 0xF;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.BRANCH_EXCHANGE, -1, rm, -1, 0, false, false, false);
    }

    // ── UDF.W — A8.8.247 ─────────────────────────────────────────────────────────────────

    private DecodedInstruction decodeUdf(int raw, int address, Condition condition, int lo) {
        if ((lo & UDF_LO_MASK) != UDF_LO_VALUE) {
            return null;
        }
        if (architecture.has(ArmFeature.M_PROFILE) && !architecture.has(ArmFeature.M_PROFILE_WIDE_MISC_CONTROL)) {
            // ARMv6-M: só a forma T1 de 16 bits de UDF existe (B9.11, achado colateral da B9.10).
            return null;
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.UDF,
                -1, -1, -1, 0, false, false, false);
    }

    // ── HVC — A8.8.65 (B9.8.2) ───────────────────────────────────────────────────────────

    private DecodedInstruction decodeHvc(int raw, int address, Condition condition, int hi, int lo) {
        if ((lo & HVC_LO_FIXED_MASK) != HVC_LO_FIXED_VALUE) {
            return null;
        }
        if (!architecture.has(ArmFeature.HYPERVISOR_CALL)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int imm16 = ((hi & HVC_HI_IMM_MASK) << HVC_HI_IMM_SHIFT_IN_IMM16) | (lo & HVC_LO_IMM_MASK);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.HVC,
                -1, -1, -1, imm16, false, false, false);
    }

    // ── SMC — A8.8.20 (B9.8.3) ───────────────────────────────────────────────────────────

    private DecodedInstruction decodeSmc(int raw, int address, Condition condition, int hi, int lo) {
        if (lo != SMC_LO_VALUE) {
            return null;
        }
        if (!architecture.has(ArmFeature.SECURE_MONITOR_CALL)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int imm4 = hi & SMC_HI_IMM_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.SMC,
                -1, -1, -1, imm4, false, false, false);
    }

    // ── Hints (NOP/YIELD/WFE/WFI/SEV) — A5.3.5, "Hints, and CPS" ────────────────────────────

    private DecodedInstruction decodeHintsOrCps(int raw, int address, Condition condition, int lo) {
        if (architecture.has(ArmFeature.M_PROFILE) && !architecture.has(ArmFeature.M_PROFILE_WIDE_MISC_CONTROL)) {
            // ARMv6-M: nem os hints largos (NOP.W/YIELD.W/WFE.W/WFI.W/SEV.W/ESB) nem CPS.W de 32
            // bits existem — só as formas T1 de 16 bits (B9.11, achado colateral da B9.10). Sem
            // este gate, o ramo "reserved hint, behaves as nop" (default de decodeHintsOrCps,
            // abaixo) aceitava QUALQUER encoding deste subgrupo incondicionalmente.
            return null;
        }
        if (((lo >>> TOP_BYTE_SHIFT) & TOP_BYTE_MASK) != HINTS_TOP_BYTE) {
            // imod!=00 ou M=1: é CPS de 32 bits (B2.7 PR3).
            return decodeCps32(raw, address, condition, lo);
        }
        int selector = lo & HINT_SELECTOR_MASK;
        return switch (selector) {
            case HINT_WFI -> decodeWaitForInterrupt(raw, address, condition);
            // YIELD/SEV (e qualquer seletor reservado, incl. NOP canônico) não têm efeito
            // observável neste core: mesmo tratamento que YIELD/SEV/NOP já recebem em ARM
            // clássico (B1.5) — lá eles nunca ganharam decode próprio porque o encoding ARM já é,
            // por acidente, um MSR(registrador)->CPSR com máscara de campo vazia. Aqui o encoding
            // Thumb-2 é dedicado (não alias de MSR), então reproduzimos o mesmo no-op explicitamente
            // via o mesmo IrOp.PsrTransfer (sem duplicar semântica, só o decode é novo).
            default -> noOpHint(raw, address, condition);
        };
    }

    /// `WFI`: exige {@link ArmFeature#WAIT_HINTS} para virar `WAIT_FOR_INTERRUPT` de verdade —
    /// espelha exatamente a regra do `WFI` ARM clássico em `ArmDecoder` (B1.5): sem a feature,
    /// UNDEFINED explícito em vez de cair no no-op genérico, por consistência de gating com o
    /// resto do decoder (mesmo raciocínio usado para LDREX/STREX/CLREX).
    private DecodedInstruction decodeWaitForInterrupt(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.WAIT_HINTS)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.WAIT_FOR_INTERRUPT, -1, -1, -1, 0, false, false, false);
    }

    /// `CPS`/`CPSIE`/`CPSID` de 32 bits (B2.7 PR3): MESMO empacotamento de `immediate` que
    /// `ArmDecoder` usa para o `CPS` ARM clássico (`imod | (M&lt;&lt;2) | (A&lt;&lt;3) |
    /// (I&lt;&lt;4) | (F&lt;&lt;5) | (mode&lt;&lt;6)`) — `StandardIrBuilder` já sabe interpretar
    /// esse formato para os dois encodings, sem duplicar semântica (`IrOp.ChangeProcessorState`,
    /// B1.5). Gate {@link ArmFeature#MODE_CHANGE_INSTRUCTIONS}, igual ao ARM clássico.
    private DecodedInstruction decodeCps32(int raw, int address, Condition condition, int lo) {
        if (!architecture.has(ArmFeature.MODE_CHANGE_INSTRUCTIONS)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int imod = (lo >>> CPS_IMOD_SHIFT) & CPS_IMOD_MASK;
        int modeChange = (lo >>> CPS_MODE_CHANGE_BIT) & 1;
        int a = (lo >>> CPS_A_BIT) & 1;
        int i = (lo >>> CPS_I_BIT) & 1;
        int f = (lo >>> CPS_F_BIT) & 1;
        int mode = lo & CPS_MODE_MASK;
        int packed = imod
                | (modeChange << CPS_PACKED_MODE_CHANGE_SHIFT)
                | (a << CPS_PACKED_A_SHIFT)
                | (i << CPS_PACKED_I_SHIFT)
                | (f << CPS_PACKED_F_SHIFT)
                | (mode << CPS_PACKED_MODE_SHIFT);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.CPS, -1, -1, -1, packed, false, false, false);
    }

    private DecodedInstruction noOpHint(int raw, int address, Condition condition) {
        // MSR(imediato)->CPSR com máscara de campo vazia: nenhum campo do PSR é escrito,
        // idêntico ao caminho que NOP/YIELD/SEV já usam implicitamente em ARM clássico.
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MSR,
                0, -1, -1, 0, true, false, false);
    }

    // ── Barreiras de memória: DMB/DSB/ISB — A5.3.5, "Miscellaneous control" ─────────────────

    private DecodedInstruction decodeMiscControl(int raw, int address, Condition condition, int lo) {
        if (((lo >>> TOP_BYTE_SHIFT) & TOP_BYTE_MASK) != MISC_CONTROL_TOP_BYTE) {
            return null;
        }
        if (lo == CLREX_LO) {
            return decodeClearExclusive(raw, address, condition);
        }
        int barrierOp = (lo >>> BARRIER_OP_SHIFT) & BARRIER_OP_MASK;
        boolean isBarrier = barrierOp == BARRIER_OP_DSB || barrierOp == BARRIER_OP_DMB || barrierOp == BARRIER_OP_ISB;
        if (!isBarrier) {
            // SB (mesmo subgrupo) ou reservado: fora do escopo desta task, ver javadoc.
            return null;
        }
        if (!architecture.has(ArmFeature.MEMORY_BARRIERS)) {
            // Mesma convenção de gating explícito do WFI/LDREX/STREX/CLREX: UNDEFINED direto em
            // vez de deixar a chamada cair silenciosamente no fallback do ThumbDecoder.
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int option = lo & BARRIER_OPTION_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.MEMORY_BARRIER, -1, -1, -1, option, false, false, false);
    }

    /// `CLREX` de 32 bits (B2.7 PR3): abre o monitor de exclusividade — MESMA `IrOp.ClearExclusive`
    /// (B1.4) que o `CLREX` ARM clássico usa, sem duplicar semântica. Gate
    /// {@link ArmFeature#EXCLUSIVE_SIZED}, igual ao ARM clássico (`LDREX*B/H/D`/`STREX*B/H/D`
    /// compartilham a mesma feature — ver `Thumb2LoadStoreDecoder`).
    private DecodedInstruction decodeClearExclusive(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.EXCLUSIVE_SIZED)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.CLEAR_EXCLUSIVE, -1, -1, -1, 0, false, false, false);
    }

    // ── MRS (forma registrador) — A5.3.5 `MRS_reg` ──────────────────────────────────────────

    private DecodedInstruction decodeMrs(int raw, int address, Condition condition, int hi, int lo) {
        if (architecture.has(ArmFeature.M_PROFILE)) {
            return decodeMrsMProfile(raw, address, condition, lo);
        }
        if ((lo & MRS_LO_FIXED_MASK) != MRS_LO_FIXED_VALUE) {
            // MRS_bank/MRS_v7m compartilham o mesmo hi, mas lo diferente (B9.8.5: tenta bank antes
            // de desistir, mesmo padrão de decodeUdf->decodeSmc).
            return decodeMrsBank(raw, address, condition, hi, lo);
        }
        boolean spsr = (hi & MRS_R_BIT_IN_HI) != 0;
        int rd = (lo >>> MRS_RD_SHIFT) & MRS_RD_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MRS,
                rd, -1, -1, spsr ? 1 : 0, true, false, false);
    }

    // ── MRS_bank (B9.8.5) — A8.8.64 ─────────────────────────────────────────────────────────

    private DecodedInstruction decodeMrsBank(int raw, int address, Condition condition, int hi, int lo) {
        if ((lo & BANKED_LO_MASK) != BANKED_LO_VALUE) {
            return null; // Nem registrador, nem bank — forma realmente desconhecida.
        }
        int rd = (lo >>> MRS_RD_SHIFT) & MRS_RD_MASK;
        if (rd == PROGRAM_COUNTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        if (!architecture.has(ArmFeature.VIRTUALIZATION_EXTENSIONS)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        boolean r = (hi & MRS_R_BIT_IN_HI) != 0;
        int sysm = (hi & BANKED_SYSM_HIGH_MASK)
                | (((lo & BANKED_SYSM_LOW_BIT_IN_LO) != 0 ? 1 : 0) << BANKED_SYSM_HIGH_SHIFT_INTO_SYSM);
        int packed = BankedRegisterSysm.resolve(r, sysm);
        if (packed < 0) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MRS_BANK,
                rd, -1, -1, packed, false, false, false);
    }

    /// `MRS Rd, <SYSm>` do perfil M (B7.4): `lo[15:12]=1000` fixo, `lo[11:8]`=Rd, `lo[7:0]`=SYSm.
    private DecodedInstruction decodeMrsMProfile(int raw, int address, Condition condition, int lo) {
        if ((lo & V7M_LO_FIXED_MASK) != V7M_LO_FIXED_VALUE) {
            return null;
        }
        int rd = (lo >>> MRS_RD_SHIFT) & MRS_RD_MASK;
        int sysm = lo & V7M_SYSM_MASK;
        if (!isSupportedSysm(sysm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.MPROFILE_MRS, rd, -1, -1, sysm, true, false, false);
    }

    /// SYSm implementados nesta task (ARMv7-M ARM B5.1.1). BASEPRI/BASEPRI_MAX/FAULTMASK exigem o
    /// perfil M **completo** ({@link ArmFeature#M_FAULT_MASKING}, ausente no ARMv6-M) — sem ela, o
    /// SYSm vira UNDEFINED direto no decode (mesma convenção de WFI/CLREX). Os demais SYSm (4,
    /// 10-15, 21+) não existem → UNDEFINED.
    private boolean isSupportedSysm(int sysm) {
        return switch (sysm) {
            case MProfileExceptionModel.SYSM_APSR, MProfileExceptionModel.SYSM_IAPSR,
                 MProfileExceptionModel.SYSM_EAPSR, MProfileExceptionModel.SYSM_XPSR,
                 MProfileExceptionModel.SYSM_IPSR, MProfileExceptionModel.SYSM_EPSR,
                 MProfileExceptionModel.SYSM_IEPSR, MProfileExceptionModel.SYSM_MSP,
                 MProfileExceptionModel.SYSM_PSP, MProfileExceptionModel.SYSM_PRIMASK,
                 MProfileExceptionModel.SYSM_CONTROL -> true;
            case MProfileExceptionModel.SYSM_BASEPRI, MProfileExceptionModel.SYSM_BASEPRI_MAX,
                 MProfileExceptionModel.SYSM_FAULTMASK -> architecture.has(ArmFeature.M_FAULT_MASKING);
            default -> false;
        };
    }

    // ── MSR (forma registrador) — A5.3.5 `MSR_reg` ──────────────────────────────────────────

    private DecodedInstruction decodeMsr(int raw, int address, Condition condition, int hi, int lo) {
        if (architecture.has(ArmFeature.M_PROFILE)) {
            return decodeMsrMProfile(raw, address, condition, hi, lo);
        }
        if ((lo & MSR_LO_FIXED_MASK) != MSR_LO_FIXED_VALUE) {
            // MSR_bank/MSR_v7m compartilham o mesmo hi, mas lo diferente (B9.8.5: tenta bank antes
            // de desistir, mesmo padrão de decodeMrs acima).
            return decodeMsrBank(raw, address, condition, hi, lo);
        }
        boolean spsr = (hi & MSR_R_BIT_IN_HI) != 0;
        int sourceRegister = hi & MSR_RN_MASK;
        int fieldMask = (lo >>> MSR_FIELD_MASK_SHIFT) & MSR_FIELD_MASK_MASK;
        int packed = (spsr ? PSR_SPSR_BIT : 0) | (fieldMask & PSR_FIELD_MASK_BITS);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MSR,
                -1, sourceRegister, -1, packed, false, false, false);
    }

    // ── MSR_bank (B9.8.5) — A8.8.66 ─────────────────────────────────────────────────────────

    private DecodedInstruction decodeMsrBank(int raw, int address, Condition condition, int hi, int lo) {
        if ((lo & BANKED_LO_MASK) != BANKED_LO_VALUE) {
            return null; // Nem registrador, nem bank — forma realmente desconhecida.
        }
        int rn = hi & MSR_RN_MASK;
        if (rn == PROGRAM_COUNTER) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        if (!architecture.has(ArmFeature.VIRTUALIZATION_EXTENSIONS)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        boolean r = (hi & MSR_R_BIT_IN_HI) != 0;
        int sysm = ((lo >>> MSR_FIELD_MASK_SHIFT) & BANKED_SYSM_HIGH_MASK)
                | (((lo & BANKED_SYSM_LOW_BIT_IN_LO) != 0 ? 1 : 0) << BANKED_SYSM_HIGH_SHIFT_INTO_SYSM);
        int packed = BankedRegisterSysm.resolve(r, sysm);
        if (packed < 0) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.MSR_BANK,
                -1, rn, -1, packed, false, false, false);
    }

    /// `MSR <SYSm>, Rn` do perfil M (B7.4): `lo[15:12]=1000` fixo, `lo[7:0]`=SYSm, `hi[3:0]`=Rn. A
    /// máscara de campos (`lo[11:8]`) não é modelada — o próprio SYSm já decide o efeito da escrita.
    private DecodedInstruction decodeMsrMProfile(int raw, int address, Condition condition, int hi, int lo) {
        if ((lo & V7M_LO_FIXED_MASK) != V7M_LO_FIXED_VALUE) {
            return null;
        }
        int rn = hi & MSR_RN_MASK;
        int sysm = lo & V7M_SYSM_MASK;
        if (!isSupportedSysm(sysm)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.MPROFILE_MSR, -1, rn, -1, sysm, false, false, false);
    }
}
