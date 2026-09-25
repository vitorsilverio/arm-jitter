package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Estado de uma CPU AArch64 (ARMv8-A), EL0 apenas — irmão de
/// {@link dev.vitorsilverio.armjitter.core.ArmCore}, NÃO uma extensão dele (RFC-IR-64BIT.md §3.1:
/// A64 não tem banking de registrador geral por modo, não tem PC como registrador geral, e usa
/// `PSTATE` em vez de CPSR/SPSR — um supertipo comum "gordo" com o `ArmCore` de 32 bits só
/// contaminaria as duas classes).
///
/// Escopo original (B6.1): só `EL0` (nível de privilégio de aplicação), sem MMU, sem exceções
/// síncronas/assíncronas, sem múltiplos níveis de `SP` (`SP_EL0` era o único `SP`). Esses recursos
/// entraram em tasks futuras do épico B6 (ver `b6-aarch64.md`, escopo fechado de B6.3-B6.6):
/// B6.6.4 acrescenta o PRIMEIRO estado de EL1 real (mínimo, só para abort de memória — ver
/// {@link #enterMemoryAbort} e {@link Aarch64ExceptionState} — `SVC`/`IRQ`/`FIQ`/`SError`
/// continuam fora, não é um modelo de exceção A64 completo).
public final class Aarch64Core {
    /// Quantidade de registradores de propósito geral endereçáveis (`X0`-`X30`, 31 registradores).
    /// O registrador de número 31 do encoding NUNCA é armazenado neste array — ver {@link #x} e
    /// {@link #setX}.
    private static final int GENERAL_REGISTER_COUNT = 31;
    /// Índice de encoding (`Rn`/`Rd`/`Rt` = `31`) que designa `XZR` ou `SP`, conforme a
    /// instrução — nunca um `X0`-`X30` de verdade.
    private static final int SPECIAL_REGISTER_ENCODING = 31;
    /// Máscara para a metade baixa de 32 bits (visão `W` de um registrador `X`).
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;

    /// Base em bytes, dentro da tabela de vetores apontada por `VBAR_ELx`, do grupo "exceção de um
    /// nível INFERIOR usando AArch64" (`ARM DDI 0487 D1.10`, tabela de 16 entradas de
    /// {@link #VECTOR_TABLE_ENTRY_SIZE_BYTES} cada — 4 grupos de origem × 4 tipos: `Synchronous`/
    /// `IRQ`/`FIQ`/`SError`, offset dentro do grupo por
    /// {@link #VECTOR_SYNCHRONOUS_OFFSET_WITHIN_GROUP}/{@link #VECTOR_IRQ_OFFSET_WITHIN_GROUP}).
    private static final long VECTOR_GROUP_LOWER_EL_AARCH64_BASE = 0x400L;
    /// Base em bytes do grupo "exceção do MESMO nível, `SP_ELx` bancado" (`ARM DDI 0487 D1.10`) —
    /// B10.1: usada quando uma exceção síncrona/IRQ ocorre já dentro de `EL1`-`EL3` (ex.: `BRK`
    /// dentro do próprio handler de EL1, ou de EL2/EL3 quando B10.2+/B10.4+/B10.5+ chegarem) — este
    /// emulador só modela a forma "h" (`SP_ELx` bancado, nunca `SP_EL0` dentro de `ELx`), então o
    /// grupo "mesmo nível com `SP_EL0`" (base `0x000`) nunca é usado.
    private static final long VECTOR_GROUP_CURRENT_EL_SPX_BASE = 0x200L;
    /// Deslocamento do tipo "Synchronous" dentro de QUALQUER grupo de origem (sempre a primeira
    /// das 4 entradas).
    private static final long VECTOR_SYNCHRONOUS_OFFSET_WITHIN_GROUP = 0x00L;
    /// Deslocamento do tipo "IRQ" dentro de QUALQUER grupo de origem (segunda das 4 entradas).
    private static final long VECTOR_IRQ_OFFSET_WITHIN_GROUP = 0x80L;
    /// Tamanho em bytes de cada entrada da tabela de vetores de exceção A64 (`ARM DDI 0487 D1.10`)
    /// — MUITO diferente do vetor único de 8 posições de 4 bytes do ARM32.
    private static final long VECTOR_TABLE_ENTRY_SIZE_BYTES = 0x80L;
    /// `EC` (`ESR_EL1[31:26]`) de um abort de instrução vindo de um nível de exceção inferior
    /// (`ARM DDI 0487 D17.2.30`) — CONFERIDO contra o manual nesta rodada (não `0x21`, que é
    /// "Instruction Abort taken without a change in Exception level").
    private static final long ESR_EC_INSTRUCTION_ABORT_LOWER_EL = 0x20L;
    /// `EC` (`ESR_EL1[31:26]`) de um abort de dados vindo de um nível de exceção inferior
    /// (`ARM DDI 0487 D17.2.30`).
    private static final long ESR_EC_DATA_ABORT_LOWER_EL = 0x24L;
    private static final int ESR_EC_SHIFT = 26;
    /// `IL` (`ESR_EL1[25]`): sempre `1` para uma instrução de 32 bits — A64 não tem instrução
    /// curta (Thumb) que zeraria este bit, ao contrário do ARM32.
    private static final long ESR_IL_BIT = 1L << 25;
    /// Máscara do campo `ISS[5:0]` (`DFSC`/`IFSC`) usado por esta task — mesmo código de
    /// {@link FaultStatus64#code()}, sem os demais bits de `ISS` (`WnR`/`FnV`/... fora de escopo).
    private static final long ESR_ISS_FAULT_STATUS_MASK = 0x3FL;
    /// `EC` (`ESR_EL1[31:26]`) de `BRK` (`ARM DDI 0487 D17.2.30`, B8.3) — mesma classe usada pelo
    /// Linux (`do_debug_exception`/`brk_handler`) e por GDB para reconhecer um breakpoint software.
    private static final long ESR_EC_BREAKPOINT = 0x3CL;
    /// `EC` de "Unknown reason" (`ARM DDI 0487 D17.2.30`, B8.3) — usado por `HLT` sem estado de
    /// debug (ver {@link #enterUndefinedInstructionException}).
    private static final long ESR_EC_UNKNOWN_REASON = 0x00L;
    /// Máscara do imediato de 16 bits de `BRK`, que vira `ESR_EL1.ISS[15:0]` por inteiro (ao
    /// contrário do `ISS` de abort de memória, que só usa os 6 bits baixos).
    private static final long ESR_ISS_BRK_IMMEDIATE_MASK = 0xFFFFL;
    /// `EC` (`ESR_EL2[31:26]`) de `HVC` executado em AArch64 (`ARM DDI 0487 D17.2.30`, B10.4) —
    /// diferente de `0x12` (`HVC` em AArch32, fora de escopo, este emulador é A64-only).
    private static final long ESR_EC_HVC_AARCH64 = 0x16L;
    /// `HCR_EL2.HCD` (bit 29, "Hypervisor Call Disable", `ARM DDI 0487 D19.2.53`, B10.4) — só se
    /// aplica à rota `EL1`→`EL2`; em `EL2`, `HVC` sempre entra (auto-chamada), ver
    /// {@link #enterHypervisorCall} e a task, "Armadilhas".
    private static final long HCR_EL2_HCD_BIT = 1L << 29;
    /// `EC` (`ESR_EL3[31:26]`) de `SMC` executado em AArch64 (`ARM DDI 0487 D17.2.30`, B10.5) —
    /// diferente de `0x16` (`HVC`, B10.4) e de `0x13` (`SMC` em AArch32, fora de escopo).
    private static final long ESR_EC_SMC_AARCH64 = 0x17L;
    /// `SCR_EL3.SMD` (bit 7, "Secure Monitor Call Disable", `ARM DDI 0487 D19.2.101`, B10.5) —
    /// desabilita a rota para `EL3` a partir de `EL1`/`EL2`/`EL3`, mesmo raciocínio de
    /// {@link #HCR_EL2_HCD_BIT} para `HVC`; ver {@link #enterSecureMonitorCall}.
    private static final long SCR_EL3_SMD_BIT = 1L << 7;

    // ── B17.3: SVE. `EC` de "acesso a SVE trapado" (`ARM DDI 0487 D17.2`, `EC=0x19`, `ISS=0`).
    private static final long ESR_EC_SVE_ACCESS = 0x19L;
    /// `ZCR_ELx.LEN` (`bits[3:0]`, `VL = (LEN+1) × 128`); o resto do registrador é `RES0`.
    private static final long ZCR_LEN_MASK = 0xFL;
    /// Valor de reset de `ZCR_ELx.LEN`: o máximo, então o `VL` efetivo = `VL` implementado até o
    /// guest reduzir (mesmo default do QEMU, `sve_default_vq`).
    private static final long ZCR_LEN_RESET = ZCR_LEN_MASK;
    /// `CPACR_EL1.ZEN` (`bits[17:16]`): `0b00`/`0b10` trapam tudo, `0b01` só EL0, `0b11` nada.
    private static final int CPACR_ZEN_SHIFT = 16;
    private static final long CPACR_ZEN_MASK = 0b11L;
    private static final long CPACR_ZEN_TRAP_EL0_ONLY = 0b01L;
    private static final long CPACR_ZEN_NO_TRAP = 0b11L;
    /// `CPTR_EL2.TZ` (bit 8, forma `E2H=0`, a única modelada): `1` trapa SVE para EL2.
    private static final long CPTR_EL2_TZ_BIT = 1L << 8;
    /// `CPTR_EL3.EZ` (bit 8): `0` trapa SVE para EL3.
    private static final long CPTR_EL3_EZ_BIT = 1L << 8;
    /// `ID_AA64PFR0_EL1.SVE` (`bits[35:32]`): `0b0001` = SVE implementada.
    private static final long ID_AA64PFR0_SVE_IMPLEMENTED = 1L << 32;
    /// `ID_AA64ZFR0_EL1.SVEver` (`bits[3:0]`): `0b0001` = SVE2 (`0b0000` = só SVE).
    private static final long ID_AA64ZFR0_SVEVER_SVE2 = 1L;
    /// Versão do formato de {@link #saveScalableState}.
    private static final int SCALABLE_STATE_FORMAT_VERSION = 1;

    // ── B18.1: SME. `EC` de "acesso a SME trapado" (`ARM DDI 0487 D17.2`, `EC=0x1D`); `ISS.SMTC`
    // ── (`bits[2:0]`) escolhe o motivo — valores do `SMEExceptionType` de `syndrome.h` do QEMU.
    private static final long ESR_EC_SME_ACCESS = 0x1DL;
    private static final long SMTC_ACCESS_TRAP = 0L;
    private static final long SMTC_NOT_STREAMING = 2L;
    private static final long SMTC_INACTIVE_ZA = 3L;
    private static final long SMTC_INACCESSIBLE_ZT0 = 4L;
    /// `SVCR.SM` (bit 0, modo streaming) e `SVCR.ZA` (bit 1, armazenamento `ZA` habilitado); o resto
    /// do registrador é `RES0`.
    /// `SVCR.SM` (bit 0).
    public static final long SVCR_SM_BIT = 1L;
    /// `SVCR.ZA` (bit 1).
    public static final long SVCR_ZA_BIT = 1L << 1;
    private static final long SVCR_MASK = SVCR_SM_BIT | SVCR_ZA_BIT;
    /// Valor de `FPSR` após `ResetSVEState` (mudança de `PSTATE.SM`): `0x0800009F`, o de `arm_reset_sve_state`.
    private static final long FPSR_RESET_ON_STREAMING_MODE_CHANGE = 0x0800009FL;
    /// `SMCR_ELx.LEN` (`bits[3:0]`, `SVL = (LEN+1) × 128`), `EZT0` (bit 30, `FEAT_SME2`) e `FA64`
    /// (bit 31). Máscara de escrita do `smcr_write` do QEMU.
    private static final long SMCR_LEN_MASK = 0xFL;
    private static final long SMCR_EZT0_BIT = 1L << 30;
    private static final long SMCR_FA64_BIT = 1L << 31;
    /// Reset de `SMCR_ELx.LEN`: o máximo, então o `SVL` efetivo = implementado até o guest reduzir.
    private static final long SMCR_LEN_RESET = SMCR_LEN_MASK;
    /// `CPACR_EL1.SMEN` (`bits[25:24]`): mesma codificação de `ZEN` (`0b00`/`0b10` trapam tudo,
    /// `0b01` só EL0, `0b11` nada).
    private static final int CPACR_SMEN_SHIFT = 24;
    /// `CPTR_EL2.TSM` (bit 12, forma `E2H=0`, a única modelada): `1` trapa SME para EL2.
    private static final long CPTR_EL2_TSM_BIT = 1L << 12;
    /// `CPTR_EL3.ESM` (bit 12): `0` trapa SME para EL3.
    private static final long CPTR_EL3_ESM_BIT = 1L << 12;
    /// `ID_AA64PFR1_EL1.SME` (`bits[27:24]`): `1` = SME, `2` = SME2.
    private static final int ID_AA64PFR1_SME_SHIFT = 24;
    private static final long ID_AA64PFR1_SME_SME2 = 2L;
    private static final long ID_AA64PFR1_SME_SME = 1L;
    /// `ID_AA64SMFR0_EL1.SMEver` (`bits[59:56]`): `0` = SME, `1` = SME2, `2` = SME2.1.
    private static final int ID_AA64SMFR0_SMEVER_SHIFT = 56;
    /// Versão do formato de {@link #saveMatrixState}.
    private static final int MATRIX_STATE_FORMAT_VERSION = 1;

    // ── B6.6.7: registradores de identidade da CPU, constantes fixas (sem hospedeiro plugável —
    // ── ver javadoc de Aarch64SystemRegisterId). Valores documentados registrador a registrador.
    /// `CurrentEL` quando em EL0 (`[3:2]=0b00`, ver {@link Aarch64SystemRegisterId#CURRENT_EL}).
    private static final long CURRENT_EL_VALUE_EL0 = 0L;
    /// `CurrentEL` quando em EL1 (`[3:2]=0b01`).
    private static final long CURRENT_EL_VALUE_EL1 = 0b01L << 2;
    /// `CurrentEL` quando em EL2 (`[3:2]=0b10`) — B10.1.
    private static final long CURRENT_EL_VALUE_EL2 = 0b10L << 2;
    /// `CurrentEL` quando em EL3 (`[3:2]=0b11`) — B10.1.
    private static final long CURRENT_EL_VALUE_EL3 = 0b11L << 2;
    /// `MPIDR_EL1` constante: `RES1`(31) + `U`(30, uniprocessador) setados, `Aff0`-`Aff3=0` (core
    /// único emulado, `ARM DDI 0487 D19.2.87`).
    private static final long MPIDR_EL1_VALUE = 0x8000_0000L | (1L << 30);
    /// `MIDR_EL1` constante: Cortex-A53 real do Raspberry Pi 3 (`Implementer=0x41`, `Variant=0`,
    /// `Architecture=0xF`, `PartNum=0xD03`, `Revision=4` — valor de referência publicado, alvo
    /// primário desta task via a F11 do `virtual-arm-box`).
    private static final long MIDR_EL1_VALUE = 0x410F_D034L;
    /// `ID_AA64PFR0_EL1` constante mínima: `EL0`/`EL1=0b0001` (só AArch64), demais campos `0`
    /// (ver javadoc de {@link Aarch64SystemRegisterId#ID_AA64PFR0_EL1}).
    private static final long ID_AA64PFR0_EL1_VALUE = 0x11L;
    /// `ID_AA64ISAR0_EL1` constante: `0` (nenhuma extensão opcional implementada).
    private static final long ID_AA64ISAR0_EL1_VALUE = 0L;
    /// `ID_AA64MMFR0_EL1` constante: `PARange[3:0]=0b0101` (48 bits, casando com
    /// `TranslatingAddressSpace64`), `TGran4[31:28]=0b0000` (4KiB suportado).
    private static final long ID_AA64MMFR0_EL1_VALUE = 0x5L;
    /// `ID_AA64MMFR1_EL1` constante: `0` (nenhuma extensão opcional implementada — `VHE`/`HPDS`/
    /// `LOR`/`PAN`/`VMIDBits`/... ausentes, mesma disciplina de {@link #ID_AA64ISAR0_EL1_VALUE}).
    private static final long ID_AA64MMFR1_EL1_VALUE = 0L;
    /// `ID_AA64MMFR2_EL1`/`ID_AA64MMFR3_EL1`/`ID_AA64MMFR4_EL1`/`ID_AA64PFR1_EL1`/
    /// `ID_AA64ZFR0_EL1`/`ID_AA64DFR1_EL1`/`ID_AA64ISAR1_EL1`/`ID_AA64ISAR2_EL1`/`REVIDR_EL1`
    /// constantes: `0` (nenhuma extensão opcional implementada / sem revisão específica de
    /// implementador), mesma disciplina de {@link #ID_AA64MMFR1_EL1_VALUE} — achados reais da F11
    /// (`kernel8.img` real sonda todo este grupo em sequência em `head.S`/`cpufeature.c`).
    private static final long ID_AA64MMFR2_EL1_VALUE = 0L;
    private static final long ID_AA64MMFR3_EL1_VALUE = 0L;
    private static final long ID_AA64MMFR4_EL1_VALUE = 0L;
    private static final long ID_AA64PFR1_EL1_VALUE = 0L;
    private static final long ID_AA64ZFR0_EL1_VALUE = 0L;
    private static final long ID_AA64DFR1_EL1_VALUE = 0L;
    private static final long ID_AA64ISAR1_EL1_VALUE = 0L;
    private static final long ID_AA64ISAR2_EL1_VALUE = 0L;
    private static final long REVIDR_EL1_VALUE = 0L;
    /// `ID_AA64DFR0_EL1` constante: `DebugVer[3:0]=0b0110` (ARMv8, valor de referência — nenhum
    /// registrador de debug implementado, só o campo de versão).
    private static final long ID_AA64DFR0_EL1_VALUE = 0x6L;
    /// `CTR_EL0` constante (B6.10): Cache Type Register real do Cortex-A53 (`0x84448004`, mesmo
    /// alvo de {@link #MIDR_EL1_VALUE}, valor de referência publicado pelo QEMU).
    private static final long CTR_EL0_VALUE = 0x8444_8004L;
    /// `DCZID_EL0` constante (B6.10): só o bit `DZP`(4) setado — este emulador não implementa
    /// `DC ZVA`, então anunciar o acesso como desabilitado é o valor correto (ver javadoc de
    /// {@link Aarch64SystemRegisterId#DCZID_EL0}).
    private static final long DCZID_EL0_VALUE = 0x10L;

    // ── B19.11a: campos de `FPMR` (`ARM DDI 0487`, confirmados byte a byte contra pseudocódigo
    // ── real em `## Resultado` da task) — ver javadoc de {@link Aarch64SystemRegisterId#FPMR}.
    private static final int FPMR_F8S1_SHIFT = 0;
    private static final int FPMR_F8S2_SHIFT = 3;
    private static final int FPMR_F8D_SHIFT = 6;
    // `OSM[14]` (B19.11b: saturação de overflow na MULTIPLICAÇÃO FP8) ganha getter agora —
    // `FMLAL_hb`/`FMLALL_sb` são os primeiros consumidores reais (B19.11/B19.9 previam isso como
    // fora de escopo; achado desta task corrige a hipótese "saturação isolada": no QEMU real,
    // `OSM` é literalmente o modo de arredondamento `float_round_nearest_even_max`, cujo único
    // efeito OBSERVÁVEL aqui é saturar no máximo normal do DESTINO — binary16/binary32, não FP8 —
    // em vez de Infinito, ver `AdvSimdLanes#fp8FusedMultiplyAdd`).
    private static final int FPMR_OSM_BIT = 14;
    private static final int FPMR_OSC_BIT = 15;
    private static final int FPMR_LSCALE_SHIFT = 16;
    /// `F1CVTL` só consome os 4 bits BAIXOS de `LSCALE` (confirmado via pseudocódigo real: `2^-
    /// UInt(FPMR.LSCALE[3:0])`), apesar do campo arquitetural ter 7 bits (`[22:16]`).
    private static final long FPMR_LSCALE_CONSUMED_MASK = 0xFL;
    /// `FMLALL_sb` (B19.11b) consome o campo `LSCALE` de 7 bits INTEIRO, sem máscara — achado real
    /// medido no `HELPER(gvec_fmla_sb)`/`fp8_mul_start` do QEMU (`scale_mask=-1`, contra `0xf` de
    /// `F1CVTL`/`FMLAL_hb`): a Armadilha 2 da task B19.11b especulava reuso cego de
    /// {@link #fp8WidenScale()}, que teria truncado os 3 bits altos incorretamente.
    private static final long FPMR_LSCALE_FULL_MASK = 0x7FL;
    private static final int FPMR_NSCALE_SHIFT = 24;
    /// `NSCALE` é lido POR INTEIRO como inteiro COM SINAL de 8 bits (`SInt(FPMR.NSCALE)`,
    /// confirmado via pseudocódigo real) — ao contrário de `LSCALE`/`LSCALE2`, não há truncamento.
    private static final long FPMR_NSCALE_MASK = 0xFFL;
    private static final int FPMR_LSCALE2_SHIFT = 32;
    /// `F2CVTL` só consome os 4 bits BAIXOS de `LSCALE2` (`2^-UInt(FPMR.LSCALE2[3:0])`), mesma
    /// disciplina de {@link #FPMR_LSCALE_CONSUMED_MASK}.
    private static final long FPMR_LSCALE2_CONSUMED_MASK = 0xFL;

    private final long[] x = new long[GENERAL_REGISTER_COUNT];
    /// `SP_EL0` — pilha de EL0. Só usada por {@link #sp()}/{@link #setSp(long)} quando
    /// {@code !exceptionState.inEl1()}; dentro de um handler de abort (B6.6.4), as duas leem/
    /// escrevem {@link Aarch64ExceptionState#sp1()} (`SP_EL1`) em vez deste campo.
    private long spEl0;
    private long pc;
    private final PstateRegister pstate = new PstateRegister();
    private final AddressSpace64 memory;
    private long cycles;
    private Aarch64SvcHandler svcHandler = Aarch64SvcHandler.none();
    /// Monitor de exclusividade `LDXR`/`LDAXR`/`STXR`/`STLXR` (B6.3.4) — próprio deste core por
    /// padrão (comportamento single-core), substituível por {@link #setExclusiveMonitor} caso este
    /// core algum dia precise compartilhar reservas com outro (mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore}, B5.1).
    private Aarch64ExclusiveMonitor exclusiveMonitor = new Aarch64ExclusiveMonitor();
    /// Banco de registradores FP escalar `V0`-`V31` (B6.5.1) — sempre alocado, mesmo padrão de
    /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters} em
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore} (sem flag de presença; quem gateia é o
    /// decoder por feature quando B6.5.3 chegar).
    private final Aarch64FpRegisters fp;
    /// Banco escalável (B17.3): dono do armazenamento de `Z`/`P`/`FFR`; {@link #fp} é a vista baixa
    /// de 128 bits. Em presets sem `FEAT_SVE` é só o banco `Z` de 128 bits (sem predicados).
    private final Aarch64ScalableRegisters scalable;
    /// `true` quando a arquitetura declara `FEAT_SVE` (B18.2: o banco escalável também existe em
    /// SME-sem-SVE, então {@link Aarch64ScalableRegisters#hasPredicates()} deixou de responder isso).
    private final boolean sve;
    /// `VL` implementado de SVE, fora do modo streaming (B18.2). **Não** é a largura do banco: com
    /// `FEAT_SME` o banco comporta `max(VL, SVL)`, porque em modo streaming `Z`/`P`/`FFR` têm `SVL` bits.
    private final int implementedVectorLengthBits;
    /// `ZCR_EL1`/`ZCR_EL2`/`ZCR_EL3` (`LEN`, B17.3) — resetam para o máximo.
    private long zcrEl1 = ZCR_LEN_RESET;
    private long zcrEl2 = ZCR_LEN_RESET;
    private long zcrEl3 = ZCR_LEN_RESET;
    /// Armazenamento matricial (B18.1): `ZA`/`ZT0`, ambos `null` até `SVCR.ZA` ser ligado. Existe
    /// (vazio) em todo core, mas só é usado em presets com `FEAT_SME`.
    private final Aarch64MatrixRegisters matrix;
    /// `SVCR` (`SM`/`ZA`, B18.1/B18.2). Só muda por {@link #setSvcr}, que aplica os efeitos: `SM`
    /// muda → `ResetSVEState` e `VL` efetivo = `SVL`; `ZA` 0→1 aloca o armazenamento zerado e 1→0 o libera.
    private long svcr;
    /// `SMCR_EL1`/`SMCR_EL2`/`SMCR_EL3` (`LEN`/`EZT0`/`FA64`, B18.1) — `LEN` reseta para o máximo,
    /// `EZT0` para `0` (`ZT0` inacessível até o firmware liberar).
    private long smcrEl1 = SMCR_LEN_RESET;
    private long smcrEl2 = SMCR_LEN_RESET;
    private long smcrEl3 = SMCR_LEN_RESET;
    /// Barramento de registrador de sistema `MRS`/`MSR` (B6.6.1) — sem hospedeiro por padrão (ver
    /// {@link Aarch64SystemRegisterBus#none()}); B6.6.3 instala um real de MMU.
    private Aarch64SystemRegisterBus systemRegisterBus = Aarch64SystemRegisterBus.none();
    /// Estado mínimo de exceção EL0→EL1 (B6.6.4) — sempre presente (não pluggable como
    /// {@link #svcHandler}/{@link #systemRegisterBus}: `SP_EL1`/`inEl1` não têm um "sem
    /// hospedeiro" sensato, já que {@link #enterMemoryAbort} precisa deles mesmo sem nenhuma MMU
    /// instalada). Única fonte de verdade de `ELR_EL1`/`SPSR_EL1`/`ESR_EL1`/`FAR_EL1`/`VBAR_EL1`
    /// — ver javadoc de {@link Aarch64ExceptionState}.
    private final Aarch64ExceptionState exceptionState = new Aarch64ExceptionState();
    /// `TPIDR_EL1` (B6.6.7) — escaninho de 64 bits do guest (ponteiro de dados de thread do
    /// kernel), armazenamento puro sem host plugável (ver javadoc de
    /// {@link Aarch64SystemRegisterId#TPIDR_EL1}).
    private long tpidrEl1;
    /// `TPIDR_EL0`/`TPIDRRO_EL0` (B8.14) — mesmos escaninhos de {@link #tpidrEl1}, mas o par
    /// acessível de EL0 (o TLS base que TODO `crt0` real grava antes de `main()` — ver javadoc de
    /// {@link Aarch64SystemRegisterId#TPIDR_EL0}).
    private long tpidrEl0;
    private long tpidrRoEl0;
    /// `FPCR`/`FPSR` (B8.15) — armazenamento puro, sem efeito semântico real ainda (ver javadoc de
    /// {@link Aarch64SystemRegisterId#FPCR}/{@link Aarch64SystemRegisterId#FPSR}).
    private long fpcr;
    private long fpsr;
    /// `FPMR` (B19.11a) — AO CONTRÁRIO de {@link #fpcr}/{@link #fpsr}, os campos deste registrador
    /// são lidos DE VERDADE (ver getters abaixo) pela `FEAT_FP8` (B19.11) — não é armazenamento
    /// decorativo. `RES0` fora dos campos documentados não é mascarado na escrita (só documentado,
    /// mesma disciplina de {@link Aarch64SystemRegisterId#FPMR}).
    private long fpmr;
    /// `DIT`/`SSBS`/`TCO`/`SPSel`/`PAN`/`UAO`/`ALLINT` (B8.17) — armazenamento puro, sem efeito
    /// real (ver javadoc de cada constante em {@link Aarch64SystemRegisterId}).
    private long dit;
    private long ssbs;
    private long tco;
    private long spsel;
    private long pan;
    private long uao;
    private long allint;
    /// `Aarch64SystemRegisterId#DEBUG_UNMODELED` (B19.6) — escaninho ÚNICO e COMPARTILHADO por
    /// toda a região `SYS`/`SYSL` `op0=2` sem registrador nomeado (ver javadoc da constante).
    private long debugUnmodeled;
    /// `RGSR_EL1`/`GCR_EL1` (B19.14, `FEAT_MTE2`) — ver javadoc de
    /// {@link Aarch64SystemRegisterId#RGSR_EL1}/{@link Aarch64SystemRegisterId#GCR_EL1}.
    private long rgsrEl1;
    private long gcrEl1;
    /// Tag de alocação MTE (4 bits) por granule de 16 bytes (B19.14) — mapa ESPARSO indexado por
    /// `endereço >>> 4` (nunca um array denso: os testes/consumidores tocam endereços arbitrários e
    /// distantes entre si). Granule ausente do mapa lê como tag `0` (mesmo valor "canônico" de um
    /// granule nunca tagueado no hardware real). Decisão de escopo da task: este mapa é a ÚNICA
    /// fonte de verdade de tags — `LDR`/`STR` comuns NUNCA o consultam (sem checagem de tag
    /// modelada, G8).
    private final Map<Long, Integer> memoryTags = new HashMap<>();
    /// Linha de IRQ nível-sensível controlada pelo hospedeiro (mesmo papel de
    /// {@code ArmCore#interruptLine}, 32-bit) — B6.6.7. `true` = interrupção pendente até o
    /// hospedeiro desassertar; sem GIC modelado, cabe ao hospedeiro decidir quando assertar/
    /// desassertar (ver a task, "Não inclui").
    private boolean interruptLine;
    /// Estado de espera do core (`WFI`, B6.6.7) — reaproveita {@link CpuSleepState} diretamente
    /// (genérico o bastante, mesma disciplina de `ExecutionThreshold` em B6.4 PR1); só
    /// {@code RUNNING}/{@code HALTED} têm consumidor aqui (sem "parada profunda" modelada).
    private CpuSleepState sleepState = CpuSleepState.RUNNING;
    /// Arquitetura A64 deste core (B11.2) — ainda sem efeito observável (nenhum decoder/executor
    /// consulta {@link #architecture} de dentro deste core; quem precisa dela hoje é o dono do
    /// {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder}, construído separadamente com a
    /// MESMA arquitetura, ver `tasks/trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`).
    /// Exposta para esse dono conseguir configurar o decoder certo sem duplicar a escolha de
    /// arquitetura em dois lugares.
    private final Aarch64Architecture architecture;

    /// Cria um core conectado a uma memória de 64 bits, para {@link Aarch64Architecture#ARMV8_0_A}
    /// — equivalente ao comportamento deste core antes de B11.2. Estado inicial: todos os
    /// registradores zerados, `PC = 0`, `PSTATE` zerado.
    public Aarch64Core(AddressSpace64 memory) {
        this(memory, Aarch64Architecture.ARMV8_0_A);
    }

    /// Cria um core conectado a uma memória de 64 bits, para a arquitetura informada (B11.2). Sem
    /// efeito observável ainda — ver o Javadoc de {@link #architecture}. Estado inicial: todos os
    /// registradores zerados, `PC = 0`, `PSTATE` zerado.
    public Aarch64Core(AddressSpace64 memory, Aarch64Architecture architecture) {
        this(memory, architecture, Aarch64ScalableRegisters.DEFAULT_VECTOR_LENGTH_BITS);
    }

    /// Como {@link #Aarch64Core(AddressSpace64, Aarch64Architecture)}, com o `VL` implementado de
    /// SVE escolhido (B17.3, RFC B17.2): múltiplo de 128 bits entre 128 e 2048. Ignorado (banco de
    /// 128 bits, sem predicados) quando {@code architecture} não declara
    /// {@link Aarch64Feature#SVE} — um Cortex-A53 não paga pelo estado escalável.
    public Aarch64Core(AddressSpace64 memory, Aarch64Architecture architecture, int vectorLengthBits) {
        this(memory, architecture, vectorLengthBits, Aarch64MatrixRegisters.DEFAULT_STREAMING_VECTOR_LENGTH_BITS);
    }

    /// Como {@link #Aarch64Core(AddressSpace64, Aarch64Architecture, int)}, com o `SVL` implementado
    /// de SME escolhido também (B18.1): múltiplo de 128 bits entre 128 e 2048, **independente** do
    /// `VL` (um núcleo real pode ter `VL=128` e `SVL=512`). Ignorado quando {@code architecture} não
    /// declara {@link Aarch64Feature#SCALABLE_MATRIX_EXTENSION} — o `ZA` nunca é alocado sem
    /// `SVCR.ZA`, então o valor só dimensiona o que seria alocado.
    public Aarch64Core(AddressSpace64 memory, Aarch64Architecture architecture, int vectorLengthBits,
            int streamingVectorLengthBits) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.architecture = Objects.requireNonNull(architecture, "architecture");
        this.sve = architecture.has(Aarch64Feature.SVE);
        boolean sme = architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
        this.implementedVectorLengthBits = sve
                ? vectorLengthBits
                : Aarch64ScalableRegisters.MIN_VECTOR_LENGTH_BITS;
        // B18.2: em modo streaming `Z`/`P`/`FFR` têm `SVL` bits — o banco tem que comportar o maior.
        int bankBits = sme ? Math.max(implementedVectorLengthBits, streamingVectorLengthBits)
                : implementedVectorLengthBits;
        this.scalable = new Aarch64ScalableRegisters(bankBits, sve || sme);
        this.fp = new Aarch64FpRegisters(scalable);
        this.matrix = new Aarch64MatrixRegisters(sme
                ? streamingVectorLengthBits
                : Aarch64MatrixRegisters.MIN_STREAMING_VECTOR_LENGTH_BITS);
    }

    /// Retorna a arquitetura configurada para este core (B11.2).
    public Aarch64Architecture architecture() {
        return architecture;
    }

    /// Lê um registrador geral pelo índice de encoding (`0`-`31`). O índice `31` é sempre `XZR`
    /// neste método — sempre lê `0`, independente da instrução (para a leitura de `SP`, use
    /// {@link #sp()} explicitamente; a escolha entre os dois é feita pelo EXECUTOR a partir do
    /// campo `spVariant` do `Ir64Op`, nunca aqui).
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @return valor de 64 bits do registrador, ou `0` se `index == 31` (`XZR`)
    public long x(int index) {
        checkRegisterIndex(index);
        return index == SPECIAL_REGISTER_ENCODING ? 0L : x[index];
    }

    /// Escreve um registrador geral pelo índice de encoding (`0`-`31`). Escrever em `31` é
    /// descartada silenciosamente (`XZR`) — ver {@link #x(int)}.
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @param value novo valor de 64 bits
    public void setX(int index, long value) {
        checkRegisterIndex(index);
        if (index == SPECIAL_REGISTER_ENCODING) {
            return;
        }
        x[index] = value;
    }

    /// Lê um registrador geral na largura indicada: `X` (64 bits) ou `W` (32 bits, zero-estendido
    /// para o valor de retorno `long`).
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @param wide `true` para ler a visão `X` completa; `false` para a visão `W` (32 bits baixos)
    /// @return valor lido, já na largura pedida
    public long xForWidth(int index, boolean wide) {
        long value = x(index);
        return wide ? value : (value & LOW_32_BITS_MASK);
    }

    /// Escreve um registrador geral na largura indicada. Na largura `W` (`wide == false`), os 32
    /// bits ALTOS do registrador de 64 bits são SEMPRE zerados — comportamento arquitetural do
    /// A64 (nenhuma instrução `W` preserva os bits altos do `X` correspondente; ver Armadilhas do
    /// épico B6 em `b6-aarch64.md`), não uma opção.
    ///
    /// @param index índice de encoding do registrador, `0`-`31`
    /// @param value valor a escrever (só os bits relevantes à largura são usados)
    /// @param wide `true` para escrever a visão `X` completa; `false` para a visão `W`
    ///             (zera os 32 bits altos)
    public void setXForWidth(int index, long value, boolean wide) {
        setX(index, wide ? value : (value & LOW_32_BITS_MASK));
    }

    /// Retorna o stack pointer ATIVO: `SP_EL0` quando o core está em `EL0` (comportamento de
    /// sempre, pré-B6.6.4), ou `SP_ELx` do nível atual (`EL1`-`EL3`, generalizado em B10.1 — era
    /// só `EL1` até então) — resolução automática que preserva TODO código existente do executor
    /// (`readBaseRegister`/ALU `SP`) sem precisar checar o nível explicitamente em cada uso.
    public long sp() {
        Aarch64ExceptionLevel level = exceptionState.currentEl();
        return level == Aarch64ExceptionLevel.EL0 ? spEl0 : exceptionState.sp(level);
    }

    /// Atualiza o stack pointer ATIVO (`SP_EL0` ou `SP_ELx` do nível atual, mesma resolução de
    /// {@link #sp()}).
    public void setSp(long value) {
        Aarch64ExceptionLevel level = exceptionState.currentEl();
        if (level == Aarch64ExceptionLevel.EL0) {
            spEl0 = value;
        } else {
            exceptionState.setSp(level, value);
        }
    }

    /// Retorna o program counter atual.
    public long pc() {
        return pc;
    }

    /// Atualiza o program counter.
    public void setProgramCounter(long value) {
        pc = value;
    }

    /// Retorna o `PSTATE` mutável associado ao core (só `N`/`Z`/`C`/`V` nesta task).
    public PstateRegister pstate() {
        return pstate;
    }

    /// Retorna o banco de registradores FP escalar (`V0`-`V31`, B6.5.1).
    public Aarch64FpRegisters fp() {
        return fp;
    }

    /// Retorna o banco escalável (`Z`/`P`/`FFR`, B17.3). Em presets sem SVE só tem `Z` de 128 bits
    /// (ver {@link #hasSve()}).
    public Aarch64ScalableRegisters scalable() {
        return scalable;
    }

    /// `true` quando a arquitetura deste core declara {@link Aarch64Feature#SVE}.
    public boolean hasSve() {
        return sve;
    }

    /// `VL` implementado em bits FORA do modo streaming — `128` sem SVE. (A largura do banco pode ser
    /// maior: com `FEAT_SME` ela é `max(VL, SVL)`, ver {@link #scalable()}.)
    public int implementedVectorLengthBits() {
        return implementedVectorLengthBits;
    }

    /// `VL` EFETIVO em bits — **o comprimento de vetor que todo laço de lane lê** (B18.2). Com
    /// `PSTATE.SM = 1` (modo streaming) é o `SVL` efetivo ({@link #streamingVectorLengthBits()}); fora
    /// dele, o menor entre o `VL` implementado e `(ZCR_ELx.LEN + 1) × 128` de cada `ZCR_ELx` que se
    /// aplica ao EL atual (EL0/EL1 sofrem `ZCR_EL1`, `ZCR_EL2` e `ZCR_EL3`; EL2 sofre `ZCR_EL2` e
    /// `ZCR_EL3`; EL3 só `ZCR_EL3`, mesma regra de `sve_vqm1_for_el` do QEMU).
    ///
    /// **Decisão de desenho da B18.2 (opção (a) da spec):** a troca `VL` → `SVL` mora AQUI, não em cada
    /// executor — os executores SVE (B17.4+) leem sempre este método e saem corretos de graça, do mesmo
    /// modo que o QEMU troca a fonte de tamanho uma vez no `DisasContext` (`vec_reg_size` vs
    /// `streaming_vec_reg_size`) em vez de em cada `trans_`. O custo é este método deixar de ser função
    /// só do `ZCR`; o ganho é que esquecer um executor deixou de ser possível.
    public int vectorLengthBits() {
        if (streamingModeEnabled()) {
            return streamingVectorLengthBits();
        }
        int el = exceptionState.currentEl().ordinal();
        long len = zcrEl3;
        if (el <= Aarch64ExceptionLevel.EL2.ordinal()) {
            len = Math.min(len, zcrEl2);
        }
        if (el <= Aarch64ExceptionLevel.EL1.ordinal()) {
            len = Math.min(len, zcrEl1);
        }
        int requested = (int) (len + 1) * Aarch64ScalableRegisters.MIN_VECTOR_LENGTH_BITS;
        return Math.min(requested, implementedVectorLengthBits);
    }

    /// {@link #vectorLengthBits()} em bytes (`VL/8`).
    public int vectorLengthBytes() {
        return vectorLengthBits() / Byte.SIZE;
    }

    /// Tamanho de um predicado em bytes (`VL/64`), no `VL` efetivo.
    public int predicateLengthBytes() {
        return vectorLengthBytes() / Byte.SIZE;
    }

    /// Estreita o estado escalável ao `VL` efetivo do EL atual (zera `Z`/`P`/`FFR` acima dele) —
    /// chamado quando `ZCR_ELx` diminui e a cada troca de EL (`aarch64_sve_change_el` do QEMU).
    /// Não faz nada em presets sem SVE.
    public void narrowScalableStateToCurrentVectorLength() {
        if (hasSve() || hasSme()) {
            scalable.narrowTo(vectorLengthBits());
        }
    }

    /// `sve_access_check` (B17.3): o EL para o qual um acesso SVE no EL atual seria TRAPADO, ou
    /// vazio se permitido. Ordem do `sve_exception_el` do QEMU: `CPACR_EL1.ZEN` (EL0/EL1, trap
    /// para EL1), `CPTR_EL2.TZ` (até EL2, trap para EL2), `CPTR_EL3.EZ` (qualquer EL, trap para
    /// EL3). Cada registrador só é consultado se o {@link #systemRegisterBus()} instalado o atende
    /// (sem hospedeiro, não há estado de trap modelado — acesso permitido). Sempre vazio sem SVE
    /// (o decoder já recusa a instrução, G8).
    public Optional<Aarch64ExceptionLevel> sveAccessTrapLevel() {
        if (!hasSve()) {
            return Optional.empty();
        }
        Aarch64ExceptionLevel el = exceptionState.currentEl();
        if (el.ordinal() <= Aarch64ExceptionLevel.EL1.ordinal()
                && systemRegisterBus.handles(Aarch64SystemRegisterId.CPACR_EL1)) {
            long zen = (systemRegisterBus.read(Aarch64SystemRegisterId.CPACR_EL1) >>> CPACR_ZEN_SHIFT)
                    & CPACR_ZEN_MASK;
            boolean allowed = zen == CPACR_ZEN_NO_TRAP
                    || (zen == CPACR_ZEN_TRAP_EL0_ONLY && el != Aarch64ExceptionLevel.EL0);
            if (!allowed) {
                return Optional.of(Aarch64ExceptionLevel.EL1);
            }
        }
        if (el.ordinal() <= Aarch64ExceptionLevel.EL2.ordinal()
                && systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL2)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.CPTR_EL2) & CPTR_EL2_TZ_BIT) != 0) {
            return Optional.of(Aarch64ExceptionLevel.EL2);
        }
        if (systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL3)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.CPTR_EL3) & CPTR_EL3_EZ_BIT) == 0) {
            return Optional.of(Aarch64ExceptionLevel.EL3);
        }
        return Optional.empty();
    }

    /// `sve_access_check` completo: se o acesso é trapado, entra na exceção síncrona
    /// (`EC=0x19`, `ISS=0`) no EL alvo e devolve `false` (a instrução NÃO executa).
    ///
    /// @param instructionAddress endereço da instrução SVE (`ELR_ELx`)
    /// @return `true` se o acesso é permitido
    public boolean sveAccessCheck(long instructionAddress) {
        Optional<Aarch64ExceptionLevel> trap = sveAccessTrapLevel();
        if (trap.isEmpty()) {
            return true;
        }
        enterSynchronousException(trap.get(), instructionAddress, (ESR_EC_SVE_ACCESS << ESR_EC_SHIFT) | ESR_IL_BIT);
        return false;
    }

    /// Serializa o estado escalável (banco `Z`/`P`/`FFR` + `ZCR_EL1/2/3`, formato versionado com o
    /// `VL`). O banco `V` "de sempre" continua em {@link Aarch64FpRegisters#saveState}.
    public void saveScalableState(DataOutputStream out) throws IOException {
        out.writeInt(SCALABLE_STATE_FORMAT_VERSION);
        out.writeLong(zcrEl1);
        out.writeLong(zcrEl2);
        out.writeLong(zcrEl3);
        scalable.saveState(out);
    }

    /// Restaura o que {@link #saveScalableState} gravou.
    public void loadScalableState(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != SCALABLE_STATE_FORMAT_VERSION) {
            throw new IOException("Versão de estado escalável do core desconhecida: " + version);
        }
        zcrEl1 = in.readLong() & ZCR_LEN_MASK;
        zcrEl2 = in.readLong() & ZCR_LEN_MASK;
        zcrEl3 = in.readLong() & ZCR_LEN_MASK;
        scalable.loadState(in);
    }

    /// Cópia defensiva do estado escalável para o harness de equivalência: `ZCR_EL1/2/3` seguidos
    /// de {@link Aarch64ScalableRegisters#snapshot()}. Vazio sem SVE.
    public long[] scalableSnapshot() {
        if (!hasSve()) {
            return new long[0];
        }
        long[] bank = scalable.snapshot();
        long[] out = Arrays.copyOf(new long[] {zcrEl1, zcrEl2, zcrEl3}, 3 + bank.length);
        System.arraycopy(bank, 0, out, 3, bank.length);
        return out;
    }

    /// `true` quando a arquitetura deste core declara {@link Aarch64Feature#SCALABLE_MATRIX_EXTENSION}.
    public boolean hasSme() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
    }

    /// `true` quando a arquitetura declara {@link Aarch64Feature#SCALABLE_MATRIX_EXTENSION_2}
    /// (habilita `ZT0` e `SMCR_ELx.EZT0`).
    public boolean hasSme2() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    }

    /// Armazenamento matricial (`ZA`/`ZT0`, B18.1) — vazio (nada alocado) enquanto `SVCR.ZA` for `0`.
    public Aarch64MatrixRegisters matrix() {
        return matrix;
    }

    /// `SVL` implementado em bits — `0` sem `FEAT_SME`. **Independente de `VL`** (Armadilha 4 da
    /// B18.1): nunca use {@link #vectorLengthBits()} para dimensionar `ZA`.
    public int implementedStreamingVectorLengthBits() {
        return hasSme() ? matrix.streamingVectorLengthBits() : 0;
    }

    /// `SVL` EFETIVO em bits no EL atual: o menor entre o `SVL` implementado e
    /// `(SMCR_ELx.LEN + 1) × 128` de cada `SMCR_ELx` que se aplica ao EL atual (mesma regra por nível
    /// de {@link #vectorLengthBits()} para `ZCR_ELx`, `sve_vqm1_for_el_sm` do QEMU). `0` sem
    /// `FEAT_SME`. Todo laço de tile/slice SME lê ISTO, nunca uma constante (G6).
    public int streamingVectorLengthBits() {
        if (!hasSme()) {
            return 0;
        }
        int el = exceptionState.currentEl().ordinal();
        long len = smcrEl3 & SMCR_LEN_MASK;
        if (el <= Aarch64ExceptionLevel.EL2.ordinal()) {
            len = Math.min(len, smcrEl2 & SMCR_LEN_MASK);
        }
        if (el <= Aarch64ExceptionLevel.EL1.ordinal()) {
            len = Math.min(len, smcrEl1 & SMCR_LEN_MASK);
        }
        int requested = (int) (len + 1) * Aarch64MatrixRegisters.MIN_STREAMING_VECTOR_LENGTH_BITS;
        return Math.min(requested, matrix.streamingVectorLengthBits());
    }

    /// {@link #streamingVectorLengthBits()} em bytes (`SVL/8`, que também é o número de linhas de `ZA`).
    public int streamingVectorLengthBytes() {
        return streamingVectorLengthBits() / Byte.SIZE;
    }

    /// `SVCR` atual (`bit0`=`SM`, `bit1`=`ZA`; o resto é `0`).
    public long svcr() {
        return svcr;
    }

    /// `PSTATE.SM` / `SVCR.SM` (modo streaming): com ele ligado o `VL` efetivo é o `SVL`
    /// ({@link #vectorLengthBits()}) e as instruções ilegais em streaming são recusadas
    /// ({@link #streamingRestrictionApplies()}).
    public boolean streamingModeEnabled() {
        return (svcr & SVCR_SM_BIT) != 0;
    }

    /// `SVCR.ZA` (armazenamento `ZA` habilitado).
    public boolean zaEnabled() {
        return (svcr & SVCR_ZA_BIT) != 0;
    }

    /// `MSR SVCR` (forma registrador) e o efeito de `MSR SVCR<mask>, #imm` (`SMSTART`/`SMSTOP`) — o
    /// ÚNICO ponto que muda `PSTATE.SM`/`PSTATE.ZA` (B18.1/B18.2). Só `SM`/`ZA` são guardados (`RES0` no
    /// resto). Como no `aarch64_set_svcr` do QEMU, escrever um valor igual ao atual não faz nada.
    ///
    /// - **`SM` mudando de valor (0→1 ou 1→0)** executa `ResetSVEState`: zera `Z0-Z31`, `P0-P15` e `FFR`,
    ///   põe `FPSR = 0x0800009F` e `FPMR = 0` — é o que impede vazar dados de vetor entre a fronteira
    ///   normal/streaming, e software real depende disso. O `VL` efetivo passa a ser o `SVL`
    ///   ({@link #vectorLengthBits()}).
    /// - **`ZA` 0→1** aloca `ZA` zerado (e `ZT0` volta a zero); **1→0** o libera.
    ///
    /// `SM` e `ZA` são eixos independentes. Exceção e `ERET` **não** tocam nenhum dos dois (nem o
    /// `SPSR_ELx` os guarda, como no QEMU) — o sistema operacional salva/restaura `SVCR` na troca de
    /// contexto.
    ///
    /// @throws IllegalStateException se o preset não declara `FEAT_SME`
    public void setSvcr(long value) {
        if (!hasSme()) {
            throw new IllegalStateException("SVCR exige FEAT_SME: " + architecture.name());
        }
        long next = value & SVCR_MASK;
        long change = svcr ^ next;
        if ((change & SVCR_SM_BIT) != 0) {
            resetSveState();
        }
        if ((change & SVCR_ZA_BIT) != 0) {
            if ((next & SVCR_ZA_BIT) != 0) {
                matrix.enableZa();
            } else {
                matrix.releaseZa();
            }
        }
        svcr = next;
    }

    /// `ResetSVEState` (B18.2, `arm_reset_sve_state` do QEMU): zera `Z`/`P`/`FFR` inteiros (a largura
    /// do banco, não só o `VL` efetivo), `FPSR ← 0x0800009F` e `FPMR ← 0`.
    private void resetSveState() {
        scalable.reset();
        fpsr = FPSR_RESET_ON_STREAMING_MODE_CHANGE;
        fpmr = 0L;
    }

    /// `FEAT_SME_FA64` efetivo no EL atual (`sme_fa64` do QEMU): a feature E `SMCR_ELx.FA64` de cada
    /// nível aplicável. `EL2`/`EL3` só entram quando o {@link #systemRegisterBus()} os modela (mesmo
    /// critério de {@link #zt0AccessTrapLevel()}). Sempre `false` sem {@link Aarch64Feature#SME_FA64}.
    public boolean fa64Enabled() {
        if (!architecture.has(Aarch64Feature.SME_FA64)) {
            return false;
        }
        Aarch64ExceptionLevel el = exceptionState.currentEl();
        if (el.ordinal() <= Aarch64ExceptionLevel.EL1.ordinal() && (smcrEl1 & SMCR_FA64_BIT) == 0) {
            return false;
        }
        if (el.ordinal() <= Aarch64ExceptionLevel.EL2.ordinal()
                && systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL2)
                && (smcrEl2 & SMCR_FA64_BIT) == 0) {
            return false;
        }
        return !systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL3) || (smcrEl3 & SMCR_FA64_BIT) != 0;
    }

    /// `true` quando as instruções ilegais em modo streaming (AdvSIMD vetorial, estruturas `LDn`/`STn`,
    /// cripto, `FJCVTZS` — ver `Ir64Op.StreamingRestricted`) devem ser recusadas agora: `PSTATE.SM = 1`
    /// e sem {@link #fa64Enabled()}.
    public boolean streamingRestrictionApplies() {
        return streamingModeEnabled() && !fa64Enabled();
    }

    /// `ID_AA64PFR1_EL1.SME` (`bits[27:24]`) do preset: `0` sem SME, `1` = SME, `2` = SME2 ou melhor
    /// (o `aarch64_cpu_sme_finalize` do QEMU faz o mesmo).
    private long smeIdField() {
        if (!hasSme()) {
            return 0L;
        }
        boolean sme2OrBetter = hasSme2() || architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);
        return (sme2OrBetter ? ID_AA64PFR1_SME_SME2 : ID_AA64PFR1_SME_SME) << ID_AA64PFR1_SME_SHIFT;
    }

    /// `ID_AA64SMFR0_EL1` do preset (B18.1): SÓ `SMEver` (`bits[59:56]`); nenhum campo de capacidade
    /// (`I16I64`/`F64F64`/`MOP4`/…) é aceso aqui — cada um é aceso pela task que fecha a família
    /// (Armadilha 5: anunciar o que não existe faz software real escolher caminho que bate em
    /// `UNIMPLEMENTED`). Zero sem SME e sob SME 1 (`SMEver = 0`).
    private long smeVersionField() {
        if (!hasSme()) {
            return 0L;
        }
        long version = architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1) ? 2L
                : hasSme2() ? 1L : 0L;
        return version << ID_AA64SMFR0_SMEVER_SHIFT;
    }

    /// `smcr_write` (B18.1): guarda `LEN`, `FA64` e — só com `FEAT_SME2` — `EZT0`; o resto é `RES0`.
    /// **Não** estreita `Z`/`P`/`FFR` nem `ZA` (B18.2/B18.3): `ZA` mantém o conteúdo, a escolha
    /// "manter" que o QEMU documenta para o caso CONSTRAINED UNPREDICTABLE.
    private void writeSmcr(Aarch64SystemRegisterId register, long value) {
        long validMask = SMCR_LEN_MASK | SMCR_FA64_BIT | (hasSme2() ? SMCR_EZT0_BIT : 0L);
        long masked = value & validMask;
        int before = vectorLengthBits();
        switch (register) {
            case SMCR_EL1 -> smcrEl1 = masked;
            case SMCR_EL2 -> smcrEl2 = masked;
            default -> smcrEl3 = masked;
        }
        // B18.2: em modo streaming o `SVL` efetivo É o `VL` efetivo — se encolheu, o estado acima dele
        // é zerado (`smcr_write` do QEMU → `aarch64_sve_narrow_vq`). Fora de streaming, `SMCR` não afeta
        // `Z`/`P`/`FFR` (`vectorLengthBits()` não muda) e o `ZA` mantém o conteúdo.
        if (vectorLengthBits() < before) {
            narrowScalableStateToCurrentVectorLength();
        }
    }

    /// `CheckSMEAccess` (B18.1): o EL para o qual um acesso SME no EL atual seria TRAPADO, ou vazio se
    /// permitido. Ordem do `sme_exception_el` do QEMU: `CPACR_EL1.SMEN` (EL0/EL1, trap para EL1),
    /// `CPTR_EL2.TSM` (até EL2, trap para EL2), `CPTR_EL3.ESM` (qualquer EL, trap para EL3). Cada
    /// registrador só é consultado se o {@link #systemRegisterBus()} o atende (mesma disciplina de
    /// {@link #sveAccessTrapLevel()}). **Não é o trap de SVE** (Armadilha 6): bits e síndrome
    /// próprios. Sempre vazio sem `FEAT_SME`.
    public Optional<Aarch64ExceptionLevel> smeAccessTrapLevel() {
        if (!hasSme()) {
            return Optional.empty();
        }
        Aarch64ExceptionLevel el = exceptionState.currentEl();
        if (el.ordinal() <= Aarch64ExceptionLevel.EL1.ordinal()
                && systemRegisterBus.handles(Aarch64SystemRegisterId.CPACR_EL1)) {
            long smen = (systemRegisterBus.read(Aarch64SystemRegisterId.CPACR_EL1) >>> CPACR_SMEN_SHIFT)
                    & CPACR_ZEN_MASK;
            boolean allowed = smen == CPACR_ZEN_NO_TRAP
                    || (smen == CPACR_ZEN_TRAP_EL0_ONLY && el != Aarch64ExceptionLevel.EL0);
            if (!allowed) {
                return Optional.of(Aarch64ExceptionLevel.EL1);
            }
        }
        if (el.ordinal() <= Aarch64ExceptionLevel.EL2.ordinal()
                && systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL2)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.CPTR_EL2) & CPTR_EL2_TSM_BIT) != 0) {
            return Optional.of(Aarch64ExceptionLevel.EL2);
        }
        if (systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL3)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.CPTR_EL3) & CPTR_EL3_ESM_BIT) == 0) {
            return Optional.of(Aarch64ExceptionLevel.EL3);
        }
        return Optional.empty();
    }

    /// EL para o qual `ZT0` está inacessível (`zt0_exception_el` do QEMU): o primeiro nível aplicável
    /// cujo `SMCR_ELx.EZT0` é `0`. `EL2`/`EL3` só são consultados se o `systemRegisterBus` os modela
    /// (`CPTR_EL2`/`CPTR_EL3` atendidos) — sem isso um core que boota direto em EL1 (sem firmware)
    /// nunca conseguiria liberar `ZT0`. Vazio quando `ZT0` é acessível.
    public Optional<Aarch64ExceptionLevel> zt0AccessTrapLevel() {
        if (!hasSme2()) {
            return Optional.empty();
        }
        Aarch64ExceptionLevel el = exceptionState.currentEl();
        if (el.ordinal() <= Aarch64ExceptionLevel.EL1.ordinal() && (smcrEl1 & SMCR_EZT0_BIT) == 0) {
            return Optional.of(Aarch64ExceptionLevel.EL1);
        }
        if (el.ordinal() <= Aarch64ExceptionLevel.EL2.ordinal()
                && systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL2)
                && (smcrEl2 & SMCR_EZT0_BIT) == 0) {
            return Optional.of(Aarch64ExceptionLevel.EL2);
        }
        if (systemRegisterBus.handles(Aarch64SystemRegisterId.CPTR_EL3) && (smcrEl3 & SMCR_EZT0_BIT) == 0) {
            return Optional.of(Aarch64ExceptionLevel.EL3);
        }
        return Optional.empty();
    }

    /// `CheckSMEEnabled` — nível 0 das checagens SME: só a permissão de acesso (`SMEN`/`TSM`/`ESM`).
    /// Se trapado, entra na exceção síncrona (`EC=0x1D`, `SMTC=0`) no EL alvo e devolve `false`.
    ///
    /// @param instructionAddress endereço da instrução SME (`ELR_ELx`)
    /// @return `true` se o acesso é permitido
    public boolean smeEnabledCheck(long instructionAddress) {
        Optional<Aarch64ExceptionLevel> trap = smeAccessTrapLevel();
        if (trap.isEmpty()) {
            return true;
        }
        raiseSmeTrap(trap.get(), instructionAddress, SMTC_ACCESS_TRAP);
        return false;
    }

    /// `CheckStreamingSVEEnabled` — {@link #smeEnabledCheck} mais `PSTATE.SM = 1`; com `SM = 0` a
    /// instrução não executa (`SMTC=2`, "não está em modo streaming").
    public boolean smeStreamingEnabledCheck(long instructionAddress) {
        if (!smeEnabledCheck(instructionAddress)) {
            return false;
        }
        if (!streamingModeEnabled()) {
            raiseSmeTrap(smeSynchronousTarget(), instructionAddress, SMTC_NOT_STREAMING);
            return false;
        }
        return true;
    }

    /// `CheckSMEAndZAEnabled` — {@link #smeEnabledCheck} mais `PSTATE.ZA = 1`; com `ZA = 0` a
    /// instrução não executa (`SMTC=3`, "`ZA` inativo").
    public boolean smeZaEnabledCheck(long instructionAddress) {
        if (!smeEnabledCheck(instructionAddress)) {
            return false;
        }
        if (!zaEnabled()) {
            raiseSmeTrap(smeSynchronousTarget(), instructionAddress, SMTC_INACTIVE_ZA);
            return false;
        }
        return true;
    }

    /// `CheckStreamingSVEAndZAEnabled` — as duas condições, `SM` antes de `ZA` (ordem do
    /// `sme_enabled_check_with_svcr` do QEMU).
    public boolean smeStreamingAndZaEnabledCheck(long instructionAddress) {
        return smeStreamingEnabledCheck(instructionAddress) && smeZaEnabledCheck(instructionAddress);
    }

    /// `CheckSMEZT0Enabled` — {@link #smeZaEnabledCheck} mais `SMCR_ELx.EZT0` (`SMTC=4`, "`ZT0`
    /// inacessível", entra no EL de {@link #zt0AccessTrapLevel()}).
    public boolean smeZt0EnabledCheck(long instructionAddress) {
        if (!smeZaEnabledCheck(instructionAddress)) {
            return false;
        }
        Optional<Aarch64ExceptionLevel> trap = zt0AccessTrapLevel();
        if (trap.isEmpty()) {
            return true;
        }
        raiseSmeTrap(trap.get(), instructionAddress, SMTC_INACCESSIBLE_ZT0);
        return false;
    }

    /// EL alvo das exceções de SME que não são de acesso: EL1, ou o EL atual se for mais privilegiado
    /// (`enterSynchronousException` recusa redução de privilégio).
    private Aarch64ExceptionLevel smeSynchronousTarget() {
        Aarch64ExceptionLevel el = exceptionState.currentEl();
        return el == Aarch64ExceptionLevel.EL0 ? Aarch64ExceptionLevel.EL1 : el;
    }

    private void raiseSmeTrap(Aarch64ExceptionLevel target, long instructionAddress, long smtc) {
        enterSynchronousException(target, instructionAddress,
                (ESR_EC_SME_ACCESS << ESR_EC_SHIFT) | ESR_IL_BIT | smtc);
    }

    /// Serializa o estado matricial (`SVCR`, `SMCR_EL1/2/3`, `ZA`/`ZT0` — formato versionado com o
    /// `SVL`). Um core que nunca alocou `ZA` grava só os marcadores de presença.
    public void saveMatrixState(DataOutputStream out) throws IOException {
        out.writeInt(MATRIX_STATE_FORMAT_VERSION);
        out.writeLong(svcr);
        out.writeLong(smcrEl1);
        out.writeLong(smcrEl2);
        out.writeLong(smcrEl3);
        matrix.saveState(out);
    }

    /// Restaura o que {@link #saveMatrixState} gravou. Um estado sem `ZA` **não** o aloca.
    public void loadMatrixState(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != MATRIX_STATE_FORMAT_VERSION) {
            throw new IOException("Versão de estado matricial do core desconhecida: " + version);
        }
        svcr = in.readLong() & SVCR_MASK;
        long smcrValidMask = SMCR_LEN_MASK | SMCR_FA64_BIT | SMCR_EZT0_BIT;
        smcrEl1 = in.readLong() & smcrValidMask;
        smcrEl2 = in.readLong() & smcrValidMask;
        smcrEl3 = in.readLong() & smcrValidMask;
        matrix.loadState(in);
    }

    /// Cópia defensiva do estado matricial para o harness de equivalência: `SVCR` e `SMCR_EL1/2/3`
    /// seguidos de {@link Aarch64MatrixRegisters#snapshot()}. Vazio sem `FEAT_SME`.
    public long[] matrixSnapshot() {
        if (!hasSme()) {
            return new long[0];
        }
        long[] bank = matrix.snapshot();
        long[] out = Arrays.copyOf(new long[] {svcr, smcrEl1, smcrEl2, smcrEl3}, 4 + bank.length);
        System.arraycopy(bank, 0, out, 4, bank.length);
        return out;
    }

    /// Retorna o barramento de memória conectado ao core.
    public AddressSpace64 memory() {
        return memory;
    }

    /// Retorna o handler de `SVC` instalado (padrão: {@link Aarch64SvcHandler#none()}).
    public Aarch64SvcHandler svcHandler() {
        return svcHandler;
    }

    /// Instala o handler de `SVC` usado pelo host (ex. tradução de syscalls no armbox, B6.2).
    public void setSvcHandler(Aarch64SvcHandler svcHandler) {
        this.svcHandler = Objects.requireNonNull(svcHandler, "svcHandler");
    }

    /// Soma ciclos consumidos pelo interpretador.
    public void addCycles(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cycles must be positive");
        }
        cycles += amount;
    }

    /// Retorna o total de ciclos acumulados.
    public long cycles() {
        return cycles;
    }

    /// Instala um monitor de exclusividade COMPARTILHADO (mesmo papel de
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore#setExclusiveMonitor} no mundo de 32 bits).
    /// Default é um monitor próprio deste core.
    public void setExclusiveMonitor(Aarch64ExclusiveMonitor exclusiveMonitor) {
        this.exclusiveMonitor = Objects.requireNonNull(exclusiveMonitor, "exclusiveMonitor");
    }

    /// Marca o monitor de exclusividade (próprio ou compartilhado) para o endereço/tamanho de um
    /// `LDXR`/`LDAXR`, com este core como dono da reserva.
    ///
    /// @param address endereço do acesso, sem sinal
    /// @param sizeBytes tamanho do acesso (1, 2, 4 ou 8)
    public void markExclusiveMonitor(long address, int sizeBytes) {
        exclusiveMonitor.markExclusive(this, address, sizeBytes);
    }

    /// Consulta um `STXR`/`STLXR` deste core contra o monitor de exclusividade. Exige marcação
    /// exata (mesmo endereço e mesmo tamanho); a reserva é SEMPRE consumida quando a região bate
    /// (mesmo se o dono for outro core, quando o monitor é compartilhado), mas só devolve `true`
    /// quando este core é o dono.
    public boolean exclusiveMonitorCovers(long address, int sizeBytes) {
        return exclusiveMonitor.consumeIfCovered(this, address, sizeBytes);
    }

    /// Abre (limpa) o monitor de exclusividade deste core. Chamado por {@link #enterMemoryAbort}
    /// (B6.6.4, fecha a pendência registrada em B6.3.4) — um `STXR`/`STLXR` após uma entrada de
    /// exceção deve falhar e refazer o par `LDXR`/`STXR`, mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} (32-bit).
    public void clearExclusiveMonitor() {
        exclusiveMonitor.clear(this);
    }

    /// Notifica o monitor de exclusividade de uma escrita comum (`STR`/`STP`, não-exclusiva) deste
    /// core. Se sobrepuser uma reserva pendente, abre a reserva, como no hardware real.
    public void notifyOrdinaryWrite(long address, int sizeBytes) {
        if (exclusiveMonitor.isArmed()) {
            exclusiveMonitor.notifyOrdinaryWrite(address, sizeBytes);
        }
    }

    /// Endereço marcado no monitor de exclusividade, ou `-1` quando aberto. Exposto para o harness
    /// de equivalência (`CpuSnapshot`) futuro detectar divergência de backend.
    public long exclusiveMonitorAddress() {
        return exclusiveMonitor.address(this);
    }

    /// Tamanho em bytes marcado no monitor de exclusividade (0 quando aberto).
    public int exclusiveMonitorSizeBytes() {
        return exclusiveMonitor.sizeBytes(this);
    }

    /// Retorna o barramento de registrador de sistema instalado (padrão:
    /// {@link Aarch64SystemRegisterBus#none()}).
    public Aarch64SystemRegisterBus systemRegisterBus() {
        return systemRegisterBus;
    }

    /// Instala o barramento de registrador de sistema usado pelo host (ex. MMU v8, B6.6.3).
    public void setSystemRegisterBus(Aarch64SystemRegisterBus systemRegisterBus) {
        this.systemRegisterBus = Objects.requireNonNull(systemRegisterBus, "systemRegisterBus");
    }

    /// `true` quando {@code register} é uma identidade da CPU resolvida DIRETO por este core
    /// (B6.6.7) — ver javadoc de {@link Aarch64SystemRegisterId}. `false` para qualquer outro
    /// registrador (inclui o timer genérico, que continua indo para
    /// {@link #systemRegisterBus()}), delegado ao executor decidir a rota.
    public boolean handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CURRENT_EL, MPIDR_EL1, MIDR_EL1, ID_AA64PFR0_EL1, ID_AA64PFR1_EL1,
                 ID_AA64ISAR0_EL1, ID_AA64ISAR1_EL1, ID_AA64ISAR2_EL1, ID_AA64MMFR0_EL1,
                 ID_AA64MMFR1_EL1, ID_AA64MMFR2_EL1, ID_AA64MMFR3_EL1, ID_AA64MMFR4_EL1,
                 ID_AA64ZFR0_EL1, ID_AA64DFR0_EL1, ID_AA64DFR1_EL1, REVIDR_EL1, TPIDR_EL1,
                 TPIDR_EL0, TPIDRRO_EL0, FPCR, FPSR, FPMR, NZCV, DAIF, DIT, SSBS, TCO, SPSEL, PAN,
                 UAO, ALLINT, CTR_EL0, DCZID_EL0, DEBUG_UNMODELED, RGSR_EL1, GCR_EL1,
                 ZCR_EL1, ZCR_EL2, ZCR_EL3, SVCR, SMCR_EL1, SMCR_EL2, SMCR_EL3, ID_AA64SMFR0_EL1 -> true;
            default -> false;
        };
    }

    /// `MRS` de uma identidade da CPU (B6.6.7, {@link #handlesSystemRegisterIntrinsically} deve
    /// ser checado antes pelo chamador). `CurrentEL` é o único campo dinâmico (reflete
    /// {@link Aarch64ExceptionState#currentEl()}, generalizado para os 4 níveis em B10.1); os
    /// demais são constantes fixas deste core.
    public long readIntrinsicSystemRegister(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CURRENT_EL -> switch (exceptionState.currentEl()) {
                case EL0 -> CURRENT_EL_VALUE_EL0;
                case EL1 -> CURRENT_EL_VALUE_EL1;
                case EL2 -> CURRENT_EL_VALUE_EL2;
                case EL3 -> CURRENT_EL_VALUE_EL3;
            };
            case MPIDR_EL1 -> MPIDR_EL1_VALUE;
            case MIDR_EL1 -> MIDR_EL1_VALUE;
            case ID_AA64PFR0_EL1 -> ID_AA64PFR0_EL1_VALUE | (hasSve() ? ID_AA64PFR0_SVE_IMPLEMENTED : 0L);
            case ID_AA64ISAR0_EL1 -> ID_AA64ISAR0_EL1_VALUE;
            case ID_AA64MMFR0_EL1 -> ID_AA64MMFR0_EL1_VALUE;
            case ID_AA64MMFR1_EL1 -> ID_AA64MMFR1_EL1_VALUE;
            case ID_AA64MMFR2_EL1 -> ID_AA64MMFR2_EL1_VALUE;
            case ID_AA64MMFR3_EL1 -> ID_AA64MMFR3_EL1_VALUE;
            case ID_AA64MMFR4_EL1 -> ID_AA64MMFR4_EL1_VALUE;
            case ID_AA64PFR1_EL1 -> ID_AA64PFR1_EL1_VALUE | smeIdField();
            case ID_AA64SMFR0_EL1 -> smeVersionField();
            case SVCR -> svcr;
            case SMCR_EL1 -> smcrEl1;
            case SMCR_EL2 -> smcrEl2;
            case SMCR_EL3 -> smcrEl3;
            case ID_AA64ZFR0_EL1 -> !hasSve() ? ID_AA64ZFR0_EL1_VALUE
                    : architecture.has(Aarch64Feature.SVE2) ? ID_AA64ZFR0_SVEVER_SVE2 : ID_AA64ZFR0_EL1_VALUE;
            case ZCR_EL1 -> zcrEl1;
            case ZCR_EL2 -> zcrEl2;
            case ZCR_EL3 -> zcrEl3;
            case ID_AA64DFR1_EL1 -> ID_AA64DFR1_EL1_VALUE;
            case ID_AA64ISAR1_EL1 -> ID_AA64ISAR1_EL1_VALUE;
            case ID_AA64ISAR2_EL1 -> ID_AA64ISAR2_EL1_VALUE;
            case REVIDR_EL1 -> REVIDR_EL1_VALUE;
            case ID_AA64DFR0_EL1 -> ID_AA64DFR0_EL1_VALUE;
            case TPIDR_EL1 -> tpidrEl1;
            case TPIDR_EL0 -> tpidrEl0;
            case TPIDRRO_EL0 -> tpidrRoEl0;
            case FPCR -> fpcr;
            case FPSR -> fpsr;
            case FPMR -> fpmr;
            case NZCV -> pstate.toNzcvRegisterFormat();
            case DAIF -> pstate.toDaifRegisterFormat();
            case DIT -> dit;
            case SSBS -> ssbs;
            case TCO -> tco;
            case SPSEL -> spsel;
            case PAN -> pan;
            case UAO -> uao;
            case ALLINT -> allint;
            case DEBUG_UNMODELED -> debugUnmodeled;
            case RGSR_EL1 -> rgsrEl1;
            case GCR_EL1 -> gcrEl1;
            case CTR_EL0 -> CTR_EL0_VALUE;
            case DCZID_EL0 -> DCZID_EL0_VALUE;
            default -> throw new IllegalArgumentException(
                    "Não é uma identidade intrínseca: " + register);
        };
    }

    /// `MSR` de uma identidade da CPU (B6.6.7). Só {@link Aarch64SystemRegisterId#TPIDR_EL1} é
    /// realmente gravável pelo guest (escaninho de thread); os demais são `RO`/`WI` de hardware —
    /// escrita lança (nenhuma instrução real gerada por um compilador visa `MSR` para eles, `WI`
    /// silencioso esconderia um bug de decodificação/uso incorreto em vez de sinalizar).
    public void writeIntrinsicSystemRegister(Aarch64SystemRegisterId register, long value) {
        switch (register) {
            case TPIDR_EL1 -> tpidrEl1 = value;
            case TPIDR_EL0 -> tpidrEl0 = value;
            // TPIDRRO_EL0 é RO de EL0/RW de EL1 no hardware real — este emulador não modela essa
            // distinção de privilégio (mesma simplificação de B10.7), aceita escrita dos 2 lados.
            case TPIDRRO_EL0 -> tpidrRoEl0 = value;
            case FPCR -> fpcr = value;
            case FPSR -> fpsr = value;
            case FPMR -> fpmr = value;
            case NZCV -> pstate.setFromNzcvRegisterFormat(value);
            case DAIF -> pstate.setFromDaifRegisterFormat(value);
            case DIT -> dit = value;
            case SSBS -> ssbs = value;
            case TCO -> tco = value;
            case SPSEL -> spsel = value;
            case PAN -> pan = value;
            case UAO -> uao = value;
            case ALLINT -> allint = value;
            case DEBUG_UNMODELED -> debugUnmodeled = value;
            case RGSR_EL1 -> rgsrEl1 = value;
            case GCR_EL1 -> gcrEl1 = value;
            case ZCR_EL1 -> writeZcr(register, value);
            case ZCR_EL2 -> writeZcr(register, value);
            case ZCR_EL3 -> writeZcr(register, value);
            case SVCR -> setSvcr(value);
            case SMCR_EL1, SMCR_EL2, SMCR_EL3 -> writeSmcr(register, value);
            default -> throw new UnsupportedOperationException(
                    "AArch64: registrador de identidade é somente leitura: " + register);
        }
    }

    /// `MSR ZCR_ELx` (B17.3): só `LEN` (`bits[3:0]`) é guardado (`RES0` no resto) e, se o `VL`
    /// efetivo do EL atual diminuiu, o estado acima dele é zerado (`aarch64_sve_narrow_vq`).
    private void writeZcr(Aarch64SystemRegisterId register, long value) {
        int before = vectorLengthBits();
        long len = value & ZCR_LEN_MASK;
        switch (register) {
            case ZCR_EL1 -> zcrEl1 = len;
            case ZCR_EL2 -> zcrEl2 = len;
            default -> zcrEl3 = len;
        }
        if (vectorLengthBits() < before) {
            narrowScalableStateToCurrentVectorLength();
        }
    }

    /// `FPMR.F8S1` (B19.11a) — formato do PRIMEIRO operando/stream FP8 (`F1CVTL`/`BF1CVTL`
    /// consultam este campo). Valores reservados (`0b010`-`0b111`) decodificados pelo bit 0, mesma
    /// disciplina "tolerante" do resto deste core (ver javadoc de {@link Aarch64Fp8Format}).
    public Aarch64Fp8Format fp8SourceFormat1() {
        return decodeFp8Format(fpmr >>> FPMR_F8S1_SHIFT);
    }

    /// `FPMR.F8S2` (B19.11a) — formato do SEGUNDO operando/stream FP8 (`F2CVTL`/`BF2CVTL`
    /// consultam este campo), mesma disciplina de {@link #fp8SourceFormat1()}.
    public Aarch64Fp8Format fp8SourceFormat2() {
        return decodeFp8Format(fpmr >>> FPMR_F8S2_SHIFT);
    }

    /// `FPMR.F8D` (B19.11a) — formato de DESTINO das conversões PARA FP8 (`FCVTN_bh`/`FCVTN_bs`
    /// consultam este campo), mesma disciplina de {@link #fp8SourceFormat1()}.
    public Aarch64Fp8Format fp8DestinationFormat() {
        return decodeFp8Format(fpmr >>> FPMR_F8D_SHIFT);
    }

    private static Aarch64Fp8Format decodeFp8Format(long fieldShiftedToBit0) {
        return (fieldShiftedToBit0 & 1) == 0 ? Aarch64Fp8Format.E5M2 : Aarch64Fp8Format.E4M3;
    }

    /// `FPMR.NSCALE` (B19.11a) — fator de escala COM SINAL aplicado ANTES de converter outro
    /// formato PARA FP8 (`FCVTN_bh`/`FCVTN_bs`: `2^SInt(FPMR.NSCALE)`). Campo inteiro de 8 bits,
    /// sem truncamento (ao contrário de {@link #fp8WidenScale()}/{@link #fp8WidenScale2()}) —
    /// confirmado via pseudocódigo real, ver `## Resultado` da task.
    public int fp8NarrowScale() {
        return (byte) ((fpmr >>> FPMR_NSCALE_SHIFT) & FPMR_NSCALE_MASK);
    }

    /// `FPMR.LSCALE[3:0]` (B19.11a) — fator de downscale SEM SINAL aplicado DEPOIS de converter o
    /// PRIMEIRO stream FP8 para outro formato (`F1CVTL`: `2^-UInt(FPMR.LSCALE[3:0])`). Só os 4 bits
    /// baixos do campo arquitetural de 7 bits são consumidos (confirmado via pseudocódigo real —
    /// ver Armadilha 2 da task B19.11a).
    public int fp8WidenScale() {
        return (int) ((fpmr >>> FPMR_LSCALE_SHIFT) & FPMR_LSCALE_CONSUMED_MASK);
    }

    /// `FPMR.LSCALE2[3:0]` (B19.11a) — mesmo papel de {@link #fp8WidenScale()}, mas para o
    /// SEGUNDO stream FP8 (`F2CVTL`: `2^-UInt(FPMR.LSCALE2[3:0])`). Mapeamento `F1CVTL`→`LSCALE` ×
    /// `F2CVTL`→`LSCALE2` confirmado via pseudocódigo real (Armadilha 3 da task B19.11a, resolvida
    /// nesta sessão — ver `## Resultado`).
    public int fp8WidenScale2() {
        return (int) ((fpmr >>> FPMR_LSCALE2_SHIFT) & FPMR_LSCALE2_CONSUMED_MASK);
    }

    /// `FPMR.OSC` (B19.11a) — `true` quando overflow numa conversão PARA FP8 satura no máximo
    /// normal do formato de destino, em vez do default arquitetural (`Infinity`/`NaN`).
    public boolean fp8OverflowSaturatesToMaxNormal() {
        return ((fpmr >>> FPMR_OSC_BIT) & 1) != 0;
    }

    /// `FPMR.LSCALE[6:0]` (B19.11b) — mesmo campo de {@link #fp8WidenScale()}, mas SEM a máscara de
    /// 4 bits: `FMLALL_sb`/`FMLALL_sb_vi` consomem o campo INTEIRO (`2^-UInt(FPMR.LSCALE)`,
    /// confirmado via `fp8_mul_start(env, -1)` do QEMU real — `-1` não mascara nada, ao contrário do
    /// `0xf` que `FMLAL_hb`/`F1CVTL` usam). Nome deliberadamente DIFERENTE de
    /// {@link #fp8WidenScale()} (que é consumido por `F1CVTL`/`FMLAL_hb`) para não sugerir que os
    /// dois são intercambiáveis.
    public int fp8MultiplyDownscale() {
        return (int) ((fpmr >>> FPMR_LSCALE_SHIFT) & FPMR_LSCALE_FULL_MASK);
    }

    /// `FPMR.OSM` (B19.11b) — `true` quando overflow numa multiplicação-acumulação FP8
    /// (`FMLAL_hb`/`FMLALL_sb`) satura no máximo normal do formato de DESTINO (`binary16`/
    /// `binary32`, nunca FP8 — diferente de {@link #fp8OverflowSaturatesToMaxNormal()}, que é sobre
    /// o destino FP8 de `FCVTN_bh`/`FCVTN_bs`), em vez de Infinito. Achado real (QEMU
    /// `fp8_mul_start`): `OSM` é literalmente o modo de arredondamento
    /// `float_round_nearest_even_max` — este getter expõe só o efeito OBSERVÁVEL (saturação), que é
    /// tudo que {@code AdvSimdLanes#fp8FusedMultiplyAdd} precisa.
    public boolean fp8OverflowSaturatesToMaxNormalOnMultiply() {
        return ((fpmr >>> FPMR_OSM_BIT) & 1) != 0;
    }

    /// Tamanho em bytes de um granule de tag MTE (B19.14, `TAG_GRANULE` real do ARM DDI 0487 —
    /// SEMPRE 16, não configurável por nenhuma feature).
    public static final int MEMORY_TAG_GRANULE_BYTES = 16;
    private static final int MEMORY_TAG_GRANULE_LOG2 = 4;
    private static final long MEMORY_TAG_GRANULE_ALIGN_MASK = ~(MEMORY_TAG_GRANULE_BYTES - 1L);
    private static final int MEMORY_TAG_NIBBLE_MASK = 0xF;
    /// Tamanho do bloco de tags de `STGM`/`LDGM` (B19.14) — `256` bytes/`16` granules, o único
    /// tamanho real (`GM_BLOCKSIZE=6`) em que a leitura/escrita não depende de um deslocamento
    /// DENTRO do bloco (ver javadoc de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.MemoryTagMultiple}).
    private static final int MEMORY_TAG_BLOCK_GRANULES = 16;
    private static final long MEMORY_TAG_BLOCK_ALIGN_MASK =
            ~((long) MEMORY_TAG_BLOCK_GRANULES * MEMORY_TAG_GRANULE_BYTES - 1L);
    /// Tamanho do bloco "DC ZVA" simulado para `STZGM` (B19.14) — `64` bytes/`4` granules, decisão
    /// documentada (ver javadoc de {@link dev.vitorsilverio.armjitter.ir64.Ir64Op.MemoryTagMultiple}).
    private static final int MEMORY_TAG_STZGM_BLOCK_GRANULES = 4;
    /// Tamanho em bytes do bloco "DC ZVA" simulado de {@link #stzgmBlockBaseAndSetTags} — o
    /// executor usa esta constante para saber quantos bytes de DADOS zerar a partir do endereço-
    /// base retornado.
    public static final int MEMORY_TAG_STZGM_BLOCK_BYTES =
            MEMORY_TAG_STZGM_BLOCK_GRANULES * MEMORY_TAG_GRANULE_BYTES;
    private static final long MEMORY_TAG_STZGM_BLOCK_ALIGN_MASK = ~(MEMORY_TAG_STZGM_BLOCK_BYTES - 1L);

    /// Zera `bits[59:56]` de um ponteiro (B19.14, `gen_address_with_allocation_tag0` do ARM DDI
    /// 0487/QEMU) — usado para obter um endereço "físico" canônico ANTES de indexar o armazenamento
    /// de tags ou tocar {@link #memory()}. Este emulador não implementa TBI geral (nenhum
    /// `LDR`/`STR` comum limpa endereço nenhum, decisão já documentada em toda a base) — esta é
    /// só a versão MÍNIMA e auto-contida necessária para que as 26 instruções de `FEAT_MTE2` sejam
    /// consistentes entre si (armazenamento de tags indexado pelo endereço FÍSICO, independente da
    /// tag lógica do ponteiro que o acessa — dois ponteiros com o MESMO endereço físico e tags
    /// DIFERENTES têm que enxergar o MESMO granule).
    private static long stripAllocationTag(long pointer) {
        return pointer & ~(0xFL << 56);
    }

    /// Lê a tag de alocação de 4 bits do granule de 16 bytes que contém {@code address} (B19.14).
    /// Um granule nunca gravado lê `0` (tag canônica default, mesmo valor de um granule "novo" no
    /// hardware real).
    public int memoryTag(long address) {
        return memoryTags.getOrDefault(stripAllocationTag(address) & MEMORY_TAG_GRANULE_ALIGN_MASK, 0);
    }

    /// Grava a tag de alocação de 4 bits (só os 4 bits baixos de {@code tag} são usados) no granule
    /// de 16 bytes que contém {@code address} (B19.14).
    public void setMemoryTag(long address, int tag) {
        memoryTags.put(stripAllocationTag(address) & MEMORY_TAG_GRANULE_ALIGN_MASK, tag & MEMORY_TAG_NIBBLE_MASK);
    }

    /// Endereço "físico" (sem a tag lógica em `bits[59:56]`) para acesso de DADOS das instruções de
    /// `FEAT_MTE2` (B19.14) — ver javadoc de {@link #stripAllocationTag}. Público porque o executor
    /// (`Ir64BlockExecutor`, pacote irmão) precisa dele antes de chamar {@link #memory()}.
    public static long physicalMemoryTagAddress(long pointer) {
        return stripAllocationTag(pointer);
    }

    /// Empacota os `16` granules de tag do bloco de 256 bytes que contém {@code address} num
    /// `long` (4 bits por granule, granule de endereço mais baixo nos bits mais baixos) — usado por
    /// `STGM`/`LDGM` (B19.14).
    public long memoryTagBlock(long address) {
        long blockBase = stripAllocationTag(address) & MEMORY_TAG_BLOCK_ALIGN_MASK;
        long bits = 0L;
        for (int i = 0; i < MEMORY_TAG_BLOCK_GRANULES; i++) {
            bits |= (long) memoryTag(blockBase + (long) i * MEMORY_TAG_GRANULE_BYTES) << (i * 4);
        }
        return bits;
    }

    /// Desempacota `value` (4 bits por granule) e grava os `16` granules do bloco de 256 bytes que
    /// contém {@code address} (B19.14).
    public void setMemoryTagBlock(long address, long value) {
        long blockBase = stripAllocationTag(address) & MEMORY_TAG_BLOCK_ALIGN_MASK;
        for (int i = 0; i < MEMORY_TAG_BLOCK_GRANULES; i++) {
            setMemoryTag(blockBase + (long) i * MEMORY_TAG_GRANULE_BYTES, (int) (value >>> (i * 4)));
        }
    }

    /// `STZGM` (B19.14) — zera os dados do bloco "DC ZVA" simulado de 64 bytes que contém
    /// {@code address} e grava {@code tagNibble} (4 bits baixos) em cada um dos 4 granules do
    /// bloco. Retorna o endereço-base do bloco (para o executor zerar a memória de dados).
    public long stzgmBlockBaseAndSetTags(long address, int tagNibble) {
        long blockBase = stripAllocationTag(address) & MEMORY_TAG_STZGM_BLOCK_ALIGN_MASK;
        for (int i = 0; i < MEMORY_TAG_STZGM_BLOCK_GRANULES; i++) {
            setMemoryTag(blockBase + (long) i * MEMORY_TAG_GRANULE_BYTES, tagNibble);
        }
        return blockBase;
    }

    /// Extrai a tag lógica (`bits[59:56]`) de um ponteiro (B19.14, `allocation_tag_from_addr` do
    /// ARM DDI 0487).
    public static int allocationTagFromAddress(long pointer) {
        return (int) ((pointer >>> 56) & MEMORY_TAG_NIBBLE_MASK);
    }

    /// Substitui `bits[59:56]` de {@code pointer} por {@code tag} (4 bits baixos), preservando o
    /// resto (B19.14, `address_with_allocation_tag` do ARM DDI 0487).
    public static long withAllocationTag(long pointer, int tag) {
        long cleared = pointer & ~(0xFL << 56);
        return cleared | (((long) tag & MEMORY_TAG_NIBBLE_MASK) << 56);
    }

    /// `IRG` (B19.14) — gera uma tag pseudoaleatória DETERMINÍSTICA (algoritmo LFSR real do ARM
    /// DDI 0487/QEMU `helper_irg`, sem entropia externa: savestates/replay exigem reprodutibilidade
    /// exata, ver "Não fazer" da task) a partir do estado em {@link #rgsrEl1}, respeitando a máscara
    /// de exclusão `rmExclude OR GCR_EL1.Exclude[15:0]`, e a insere no ponteiro {@code rn}.
    /// Atualiza {@link #rgsrEl1} (novo `TAG`/`SEED`) como efeito colateral real, igual ao hardware.
    public long insertRandomTag(long rn, long rmExclude) {
        int exclude = (int) ((rmExclude | gcrEl1) & 0xFFFFL);
        int start = (int) (rgsrEl1 & MEMORY_TAG_NIBBLE_MASK);
        int seed = (int) ((rgsrEl1 >>> 8) & 0xFFFF);
        if (seed == 0) {
            // Decisão documentada: hardware real recorreria a entropia de verdade aqui (GCR_EL1.RRND).
            // Este core nunca modela isso — sempre determinístico, mesma seed inicial fixa.
            seed = 1;
        }
        int offset = 0;
        for (int i = 0; i < 4; i++) {
            int top = ((seed >>> 5) ^ (seed >>> 3) ^ (seed >>> 2) ^ seed) & 1;
            seed = (top << 15) | (seed >>> 1);
            offset |= top << i;
        }
        int rtag = chooseNonExcludedTag(start, offset, exclude);
        rgsrEl1 = (rtag & MEMORY_TAG_NIBBLE_MASK) | ((long) seed << 8);
        return withAllocationTag(rn, rtag);
    }

    private static int chooseNonExcludedTag(int tag, int offset, int exclude) {
        if (exclude == 0xFFFF) {
            return 0;
        }
        if (offset == 0) {
            while (((exclude >>> tag) & 1) != 0) {
                tag = (tag + 1) & 0xF;
            }
        } else {
            do {
                do {
                    tag = (tag + 1) & 0xF;
                } while (((exclude >>> tag) & 1) != 0);
            } while (--offset > 0);
        }
        return tag;
    }

    /// Linha de IRQ nível-sensível (B6.6.7) — ver javadoc do campo {@link #interruptLine}.
    public boolean interruptLine() {
        return interruptLine;
    }

    /// Assert/desassert da linha de IRQ pelo hospedeiro.
    public void setInterruptLine(boolean interruptLine) {
        this.interruptLine = interruptLine;
    }

    /// Estado de espera do core (B6.6.7, `WFI`).
    public CpuSleepState sleepState() {
        return sleepState;
    }

    /// Força o estado de espera do core — usado pelo executor (`WFI`) e por
    /// {@link #enterIrq}/{@link #enterMemoryAbort} (uma exceção sempre acorda o core).
    public void setSleepState(CpuSleepState sleepState) {
        this.sleepState = Objects.requireNonNull(sleepState, "sleepState");
    }

    /// Checa e, se pendente e não mascarada, entrega uma IRQ — chamado pelo executor ANTES de
    /// buscar/decodificar a próxima instrução (mesmo ponto de verificação de
    /// {@code ArmCore#servicePendingIrq}, 32-bit). Também acorda o core de `WFI` mesmo quando a
    /// IRQ está mascarada (`ARM DDI 0487` pseudocódigo de `WFI`: uma interrupção pendente acorda o
    /// core mesmo mascarada — ela só não é ENTREGUE enquanto mascarada, mas a execução retoma).
    ///
    /// @return `true` quando uma IRQ foi entregue nesta chamada (o chamador não deve prosseguir
    ///         para fetch/decode desta rodada — o PC já foi redirecionado para o handler)
    public boolean servicePendingIrq() {
        if (!interruptLine) {
            return false;
        }
        if (sleepState != CpuSleepState.RUNNING) {
            sleepState = CpuSleepState.RUNNING;
        }
        if (pstate.irqDisabled()) {
            return false;
        }
        enterIrq();
        return true;
    }

    /// Entrada de exceção por IRQ (B6.6.7; alvo generalizado em B10.1) — espelho de
    /// {@link #enterMemoryAbort}, mas SEM tocar `ESR_ELx`/`FAR_ELx` (uma IRQ não tem síndrome de
    /// falta associada; o hardware real deixa esses registradores com o valor anterior). `ELR_ELx`
    /// recebe o PC ATUAL (endereço da PRÓXIMA instrução que executaria — IRQ é assíncrona, ao
    /// contrário de um abort síncrono, que salva o endereço da instrução FALTOSA). `PSTATE.I` é
    /// forçado a `1` na entrada (mascara IRQ aninhada dentro do handler — `ERET` restaura o valor
    /// salvo em `SPSR_ELx`).
    ///
    /// Alvo fixo em `EL1` até B10.4/B10.5 chegarem (nenhum roteamento por `HCR_EL2.IMO`/
    /// `SCR_EL3.IRQ` ainda — comportamento IDÊNTICO ao de antes de B10.1, só implementado sobre o
    /// armazenamento genérico por nível agora).
    public void enterIrq() {
        Aarch64ExceptionLevel target = Aarch64ExceptionLevel.EL1;
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        exceptionState.setElr(target, pc);
        exceptionState.setSpsr(target, pstate.toSpsrFormat() | source.spsrMode());
        exceptionState.setCurrentEl(target);
        narrowScalableStateToCurrentVectorLength();
        pstate.setIrqDisabled(true);
        setProgramCounter(exceptionState.vbar(target) + vectorGroupBase(source, target)
                + VECTOR_IRQ_OFFSET_WITHIN_GROUP);
    }

    /// Retorna o estado de exceção EL0→EL1 (B6.6.4) — `ELR_EL1`/`SPSR_EL1`/`ESR_EL1`/`FAR_EL1`/
    /// `VBAR_EL1`/`SP_EL1`/nível atual. Exposto para o host configurar `VBAR_EL1` diretamente
    /// (setup de teste sem MMU) e para
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters}-equivalentes
    /// delegarem `MRS`/`MSR` desses registradores aqui em vez de duplicar armazenamento.
    public Aarch64ExceptionState exceptionState() {
        return exceptionState;
    }

    /// Converte uma {@link MemoryTranslationException64} capturada pelo executor
    /// (`Ir64BlockExecutor`, B6.6.4) numa entrada de exceção síncrona — mesmo contrato do
    /// precedente {@link dev.vitorsilverio.armjitter.core.ArmCore#enterMemoryAbort}:
    /// {@code instructionAddress} é o endereço da PRÓPRIA instrução faltosa (fetch ou load/store),
    /// não o sequencial seguinte.
    ///
    /// Preenche `ESR_ELx` (`EC`+`IL`+`ISS[5:0]`, `ARM DDI 0487 D17.2.30`) e `FAR_ELx`, salva
    /// `ELR_ELx←instructionAddress` e `SPSR_ELx←PSTATE` atual, abre o monitor de exclusividade
    /// (mesma disciplina de {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} — um
    /// `STXR`/`STLXR` após o retorno deve falhar e refazer o par `LDXR`/`STXR`, fecha a pendência
    /// de B6.3.4) e salta pro vetor certo (`ARM DDI 0487 D1.10`).
    ///
    /// Alvo fixo em `EL1` até B10.4/B10.5 chegarem (nenhum roteamento por `HCR_EL2`/`SCR_EL3`
    /// ainda — comportamento IDÊNTICO ao de antes de B10.1).
    ///
    /// @param instructionAddress endereço da instrução que causou a falta
    /// @param fault falta de tradução capturada
    public void enterMemoryAbort(long instructionAddress, MemoryTranslationException64 fault) {
        Aarch64ExceptionLevel target = Aarch64ExceptionLevel.EL1;
        boolean isInstructionFetch = fault.accessType() == MemoryAccessType.INSTRUCTION_FETCH;
        long ec = isInstructionFetch ? ESR_EC_INSTRUCTION_ABORT_LOWER_EL : ESR_EC_DATA_ABORT_LOWER_EL;
        long faultStatusCode = fault.faultStatus().code() & ESR_ISS_FAULT_STATUS_MASK;
        long esr = (ec << ESR_EC_SHIFT) | ESR_IL_BIT | faultStatusCode;
        exceptionState.setFar(target, fault.virtualAddress());
        enterSynchronousException(target, instructionAddress, esr);
    }

    /// `BRK` (`ARM DDI 0487 C6.2.29`, B8.3) — mesma entrada de exceção síncrona de
    /// {@link #enterMemoryAbort}, mas SEM tocar `FAR_ELx` (`BRK` não tem endereço de falta —
    /// `ARM DDI 0487` deixa o registrador com o valor anterior, mesma disciplina já aplicada a
    /// {@link #enterIrq}). `ESR_ELx.ISS[15:0]` recebe o imediato de 16 bits da própria instrução
    /// (convenção do Linux/GDB para identificar o motivo do trap sem reler a instrução). Alvo fixo
    /// em `EL1`, mesma ressalva de {@link #enterMemoryAbort}.
    ///
    /// @param instructionAddress endereço da própria instrução `BRK` (`ELR_ELx`)
    /// @param immediate imediato de 16 bits do encoding
    public void enterBreakpointException(long instructionAddress, int immediate) {
        long esr = (ESR_EC_BREAKPOINT << ESR_EC_SHIFT) | ESR_IL_BIT
                | (immediate & ESR_ISS_BRK_IMMEDIATE_MASK);
        enterSynchronousException(Aarch64ExceptionLevel.EL1, instructionAddress, esr);
    }

    /// `HLT` sem estado de debug externo modelado (B8.3) — pseudocódigo real do manual cai no
    /// caminho `UNDEFINED` (`Halting_instruction`), mesma classe (`EC=0x00`, "Unknown reason") de
    /// qualquer encoding reservado que um `Aarch64Decoder` real rejeitasse. Mesmo contrato de
    /// {@link #enterBreakpointException}, sem imediato (`ISS=0`, "Unknown reason" não carrega
    /// síndrome). Alvo fixo em `EL1`, mesma ressalva de {@link #enterMemoryAbort}.
    ///
    /// @param instructionAddress endereço da própria instrução `HLT` (`ELR_ELx`)
    public void enterUndefinedInstructionException(long instructionAddress) {
        long esr = (ESR_EC_UNKNOWN_REASON << ESR_EC_SHIFT) | ESR_IL_BIT;
        enterSynchronousException(Aarch64ExceptionLevel.EL1, instructionAddress, esr);
    }

    /// `HVC` real (`ARM DDI 0487 C6.2.83`, B10.4) — árvore de decisão do pseudocódigo real da
    /// instrução (não "sempre EL2"): `EL0`/`EL3` não têm `HVC` definida (cai em `UNDEFINED`, mesmo
    /// caminho de {@link #enterUndefinedInstructionException} — `EL3` chama `SMC` para o monitor,
    /// não `HVC`, ver B10.5); em `EL1`, `HCR_EL2.HCD` desabilita a rota (mesmo destino `UNDEFINED`);
    /// em `EL2`, `HVC` sempre entra (auto-chamada, "mesmo nível" — caso real do manual, usado por
    /// virtualização aninhada, `HCD` não se aplica aqui). Sem hospedeiro de `HCR_EL2` instalado
    /// (`systemRegisterBus` "none"), `HCD` é tratado como `0` (não desabilitado — mesmo padrão
    /// "registrador sem hospedeiro = zerado" já usado alhures).
    ///
    /// @param instructionAddress endereço da própria instrução `HVC` (`ELR_ELx` quando aplicável)
    public void enterHypervisorCall(long instructionAddress) {
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        if (source == Aarch64ExceptionLevel.EL0) {
            // enterUndefinedInstructionException() tem alvo fixo em EL1, correto aqui: EL0 nunca
            // trata sua própria exceção (não bancado), EL1 é o único alvo real de um UNDEFINED
            // vindo de EL0.
            enterUndefinedInstructionException(instructionAddress);
            return;
        }
        if (source == Aarch64ExceptionLevel.EL3) {
            // NÃO reusa enterUndefinedInstructionException() aqui: seu alvo fixo em EL1 seria uma
            // redução de privilégio (EL3->EL1), que enterSynchronousException recusa (G8, ver
            // vectorGroupBase). UNDEFINED em EL3 se auto-trata (mesmo nível), mesmo raciocínio do
            // ramo EL1 abaixo.
            long esr = (ESR_EC_UNKNOWN_REASON << ESR_EC_SHIFT) | ESR_IL_BIT;
            enterSynchronousException(Aarch64ExceptionLevel.EL3, instructionAddress, esr);
            return;
        }
        if (source == Aarch64ExceptionLevel.EL1 && hypervisorCallDisabled()) {
            // Alvo fixo em EL1 de enterUndefinedInstructionException() colide com `source` aqui
            // (EL1->EL1, "mesmo nível") — correto, mesmo raciocínio do ramo EL3 acima.
            enterUndefinedInstructionException(instructionAddress);
            return;
        }
        long esr = (ESR_EC_HVC_AARCH64 << ESR_EC_SHIFT) | ESR_IL_BIT;
        enterSynchronousException(Aarch64ExceptionLevel.EL2, instructionAddress, esr);
    }

    /// Leitura de `HCR_EL2.HCD` para {@link #enterHypervisorCall} — via {@link #systemRegisterBus}
    /// em vez de armazenamento próprio deste core (mesma disciplina de todo registrador de sistema
    /// EL2/EL3 desde B10.2/B10.3: a fonte única é o barramento instalado pelo hospedeiro, `Aarch64Core`
    /// não duplica estado).
    private boolean hypervisorCallDisabled() {
        return systemRegisterBus.handles(Aarch64SystemRegisterId.HCR_EL2)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.HCR_EL2) & HCR_EL2_HCD_BIT) != 0;
    }

    /// `SMC` real (`ARM DDI 0487 C6.2.294`, B10.5) — árvore de decisão do pseudocódigo real da
    /// instrução, SIMPLIFICADA (sem o ramo `HCR_EL2.TSC` que roteia `EL1`→`EL2`, deliberadamente
    /// fora de escopo, ver a task "Não inclui" — mesma simplificação que B10.4 aplicou a `HVC` para
    /// `HCR_EL2.TGE`): `EL0` não tem `SMC` definida (cai em `UNDEFINED`, mesmo caminho de
    /// {@link #enterUndefinedInstructionException}); em `EL1`/`EL2`/`EL3`, `SCR_EL3.SMD` desabilita
    /// a rota (self-trap `UNDEFINED` no PRÓPRIO nível de origem — nunca reduz nem aumenta
    /// privilégio); senão entra em `EL3` (auto-chamada quando a origem já é `EL3`, "mesmo nível" —
    /// mesmo caso real do manual usado pelo ramo `EL2→EL2` de {@link #enterHypervisorCall}). Sem
    /// hospedeiro de `SCR_EL3` instalado (`systemRegisterBus` "none"), `SMD` é tratado como `0`
    /// (não desabilitado, mesmo padrão "registrador sem hospedeiro = zerado" de {@link #hypervisorCallDisabled}).
    ///
    /// @param instructionAddress endereço da própria instrução `SMC` (`ELR_ELx` quando aplicável)
    public void enterSecureMonitorCall(long instructionAddress) {
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        if (source == Aarch64ExceptionLevel.EL0) {
            // Mesmo raciocínio do ramo EL0 de enterHypervisorCall: EL1 é o único alvo real de um
            // UNDEFINED vindo de EL0 (não bancado), alvo fixo do método de conveniência é correto
            // aqui.
            enterUndefinedInstructionException(instructionAddress);
            return;
        }
        if (secureMonitorCallDisabled()) {
            if (source == Aarch64ExceptionLevel.EL1) {
                // Alvo fixo EL1 de enterUndefinedInstructionException() coincide com `source`
                // aqui (EL1->EL1, "mesmo nível") — correto, mesmo raciocínio do ramo EL1 de
                // enterHypervisorCall.
                enterUndefinedInstructionException(instructionAddress);
                return;
            }
            // EL2/EL3 com SMD setado: NÃO reusa enterUndefinedInstructionException() (alvo fixo
            // EL1 seria uma redução de privilégio, que enterSynchronousException recusa via
            // vectorGroupBase, G8 — mesmo achado registrado nas Armadilhas de B10.4 para o ramo
            // EL3 de HVC). UNDEFINED se auto-trata no próprio nível de origem.
            long undefinedEsr = (ESR_EC_UNKNOWN_REASON << ESR_EC_SHIFT) | ESR_IL_BIT;
            enterSynchronousException(source, instructionAddress, undefinedEsr);
            return;
        }
        long esr = (ESR_EC_SMC_AARCH64 << ESR_EC_SHIFT) | ESR_IL_BIT;
        enterSynchronousException(Aarch64ExceptionLevel.EL3, instructionAddress, esr);
    }

    /// Leitura de `SCR_EL3.SMD` para {@link #enterSecureMonitorCall} — via
    /// {@link #systemRegisterBus}, mesma disciplina de {@link #hypervisorCallDisabled}.
    private boolean secureMonitorCallDisabled() {
        return systemRegisterBus.handles(Aarch64SystemRegisterId.SCR_EL3)
                && (systemRegisterBus.read(Aarch64SystemRegisterId.SCR_EL3) & SCR_EL3_SMD_BIT) != 0;
    }

    /// Núcleo comum de toda exceção síncrona (`ARM DDI 0487` pseudocódigo `AArch64.TakeException`,
    /// extraído de {@link #enterMemoryAbort} em B8.3, generalizado para nível-alvo explícito em
    /// B10.1) — reaproveitado por {@link #enterBreakpointException}/
    /// {@link #enterUndefinedInstructionException} e por `HVC`/`SMC` reais ({@link #enterHypervisorCall}/
    /// {@link #enterSecureMonitorCall}, B10.4/B10.5): todas compartilham `ELR_ELx←instructionAddress`, `SPSR_ELx←PSTATE` atual (com o
    /// nível de ORIGEM codificado no campo `M[3:0]`, para `ERET` saber pra onde voltar — ver
    /// {@link Aarch64ExceptionLevel}), fecham o monitor de exclusividade, entram no nível-alvo e
    /// saltam pro vetor certo (grupo "mesmo nível" ou "nível inferior", conforme
    /// {@link #vectorGroupBase} — só `ESR_ELx`/`FAR_ELx` mudam por tipo de falta, e `FAR_ELx` é
    /// preenchido pelo CHAMADOR antes de vir aqui quando aplicável).
    ///
    /// @param target nível de exceção que vai tratar esta exceção (nunca `EL0`, nunca abaixo do
    ///               nível atual — ver {@link #vectorGroupBase})
    private void enterSynchronousException(Aarch64ExceptionLevel target, long instructionAddress, long esr) {
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        exceptionState.setEsr(target, esr);
        exceptionState.setElr(target, instructionAddress);
        exceptionState.setSpsr(target, pstate.toSpsrFormat() | source.spsrMode());
        clearExclusiveMonitor();
        exceptionState.setCurrentEl(target);
        narrowScalableStateToCurrentVectorLength();
        // B6.6.7: qualquer entrada de exceção mascara IRQ (`ARM DDI 0487` pseudocódigo
        // `AArch64.TakeException` seta `PSTATE.{D,A,I,F}=1`) — `spsr` acima já capturou o valor
        // ANTIGO da máscara (o que `ERET` deve restaurar), então esta linha só afeta o `PSTATE`
        // ATIVO durante o handler em si.
        pstate.setIrqDisabled(true);
        setProgramCounter(exceptionState.vbar(target) + vectorGroupBase(source, target)
                + VECTOR_SYNCHRONOUS_OFFSET_WITHIN_GROUP);
    }

    /// Base do grupo de origem certo na tabela de vetores (`ARM DDI 0487 D1.10`, B10.1): "mesmo
    /// nível, `SP_ELx`" quando a exceção ocorre já dentro do nível-alvo (ex.: `BRK` dentro do
    /// próprio handler de EL1), "nível inferior usando AArch64" quando entra vindo de um nível
    /// mais baixo (o caso de toda entrada EL0→EL1 até B10.1). Uma exceção NUNCA reduz o nível de
    /// privilégio — `target` abaixo de `source` é um bug de chamador, não um caso arquitetural
    /// real, por isso lança em vez de silenciosamente escolher um grupo errado (G8).
    private static long vectorGroupBase(Aarch64ExceptionLevel source, Aarch64ExceptionLevel target) {
        if (target.ordinal() < source.ordinal()) {
            throw new IllegalStateException(
                    "entrada de exceção não pode reduzir o nível de privilégio: " + source + " -> " + target);
        }
        return target == source ? VECTOR_GROUP_CURRENT_EL_SPX_BASE : VECTOR_GROUP_LOWER_EL_AARCH64_BASE;
    }

    private static void checkRegisterIndex(int index) {
        if (index < 0 || index > SPECIAL_REGISTER_ENCODING) {
            throw new IndexOutOfBoundsException(
                    "AArch64 register encoding index must be between 0 and 31: " + index);
        }
    }
}
