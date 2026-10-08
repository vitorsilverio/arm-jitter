package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64FlagConversionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;

import java.util.List;

/// E15.11 (D5 do épico E15): o espaço de sistema `1101010100 L op0 op1 CRn CRm op2 Rt` com `op0=00`
/// (hints, `WFET`/`WFIT`, barreiras, PSTATE) e `op0=01` (`SYS`/`SYSL`: `TLBI`, `AT`, `IC`, manutenção de
/// cache) como tabela — antes `Aarch64Decoder#decodeSystemInstructionBarrier`/`decodeFlagOrPstateImmediate`/
/// `decodeSystemInstructionSys` e auxiliares. `MRS`/`MSR` (`op0=1x`) é {@link SystemRegisterRows}.
///
/// **Restrições que viraram linha e que a versão em cascata não conferia** (G8 medido pela E15.11 contra o
/// `a64.decode` do QEMU — o `objdump` imprime essas palavras como `msr s0_...`, nunca `undefined`): `op0=00`
/// só existe com `L=0`; hints/barreiras/`WFxT` só com `op1=011`; hints/barreiras/PSTATE só com
/// `Rt=11111`; `CRm` fixo em `CFINV`/`XAFLAG`/`AXFLAG` (`0000`), `MSR ALLINT` (`000x`), `MSR SVCR`
/// (`0xxx`), `SB` (`0000`), `DSB nXS` (`xx10`) e `WFET`/`WFIT` (`0000`).
///
/// **Prioridade:** {@link #FALLBACK_ROWS} são os "restos" de espaços que têm linhas específicas em
/// {@link #ROWS} — hint `NOP` (todo `CRn=0010` menos `WFI`), manutenção de cache `NOP` (`CRn=0111` menos
/// `IC`; `AT` `CRm=1000` e `DC ZVA` ficam DE FORA também do resto, recusados) e `SYS`/`SYSL` não modelado.
/// O decoder só consulta o fallback quando {@link #ROWS} não casa.
final class SystemInstructionRows {
    private static final int RT_MASK = 0b1_1111;
    private static final int CRM_SHIFT = 8;
    private static final int CRM_MASK = 0b1111;
    private static final int SVCR_IMMEDIATE_MASK = 0b1;
    private static final int SVCR_SM_BIT = 1 << 1;
    private static final int SVCR_ZA_BIT = 1 << 2;

    private static final Aarch64Feature WFXT = Aarch64Feature.WFXT;
    private static final Aarch64Feature FLAGM = Aarch64Feature.FLAG_MANIPULATION;
    private static final Aarch64Feature FLAGM2 = Aarch64Feature.FLAG_MANIPULATION_2;

    /// Linhas específicas, sem sobreposição. Colunas: `1101010100 L op0 op1 CRn CRm op2 Rt`.
    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = List.of(
            // WFI — hint #3 (os outros hints são o NOP do fallback)
            row("1101010100 0 00 011 0010 0000 011 11111", null, system(Ir64SystemInstructionOp.WFI)),
            // WFET/WFIT — mesmo tratamento de WFE (NOP)/WFI sem timeout; Rt (prazo) ignorado
            row("1101010100 0 00 011 0001 0000 000 .....", WFXT, system(Ir64SystemInstructionOp.NOP_HINT)),
            row("1101010100 0 00 011 0001 0000 001 .....", WFXT, system(Ir64SystemInstructionOp.WFI)),
            // barreiras — CLREX, DSB/DMB (CRm = domínio:tipos), DSB nXS, ISB, SB
            row("1101010100 0 00 011 0011 .... 010 11111", null, system(Ir64SystemInstructionOp.CLEAR_EXCLUSIVE)),
            row("1101010100 0 00 011 0011 .... 10. 11111", null, system(Ir64SystemInstructionOp.BARRIER)),
            row("1101010100 0 00 011 0011 ..10 001 11111", null, system(Ir64SystemInstructionOp.BARRIER)),
            row("1101010100 0 00 011 0011 .... 110 11111", null, system(Ir64SystemInstructionOp.BARRIER)),
            row("1101010100 0 00 011 0011 0000 111 11111", null, system(Ir64SystemInstructionOp.BARRIER)),
            // PSTATE op1=000 — CFINV/XAFLAG/AXFLAG (CRm=0000), MSR UAO/PAN/SPSel (CRm = imm)
            row("1101010100 0 00 000 0100 0000 000 11111", FLAGM, flags(Ir64FlagConversionOp.INVERT_CARRY)),
            row("1101010100 0 00 000 0100 0000 001 11111", FLAGM2, flags(Ir64FlagConversionOp.EXTERNAL_TO_ARM)),
            row("1101010100 0 00 000 0100 0000 010 11111", FLAGM2, flags(Ir64FlagConversionOp.ARM_TO_EXTERNAL)),
            row("1101010100 0 00 000 0100 .... 011 11111", Aarch64Feature.UAO, pstateField()),
            row("1101010100 0 00 000 0100 .... 100 11111", Aarch64Feature.PAN, pstateField()),
            row("1101010100 0 00 000 0100 .... 101 11111", null, pstateField()),
            // PSTATE op1=001 — MSR ALLINT (CRm=000 imm)
            row("1101010100 0 00 001 0100 000. 000 11111", Aarch64Feature.NMI, pstateField()),
            // PSTATE op1=011 — MSR SSBS/DIT/TCO/DAIFSet/DAIFClr (CRm = imm), SVCR (CRm = 0 mask imm, mask≠00)
            row("1101010100 0 00 011 0100 .... 001 11111", null, pstateField()),
            row("1101010100 0 00 011 0100 .... 010 11111", Aarch64Feature.DIT, pstateField()),
            row("1101010100 0 00 011 0100 .... 100 11111", null, pstateField()),
            row("1101010100 0 00 011 0100 .... 110 11111", null, interruptMask(true)),
            row("1101010100 0 00 011 0100 .... 111 11111", null, interruptMask(false)),
            row("1101010100 0 00 011 0100 0 01 . 011 11111", Aarch64Feature.SCALABLE_MATRIX_EXTENSION,
                    SystemInstructionRows::streamingModeControl),
            row("1101010100 0 00 011 0100 0 1. . 011 11111", Aarch64Feature.SCALABLE_MATRIX_EXTENSION,
                    SystemInstructionRows::streamingModeControl),
            // TLBI — os 3 regimes (op1 = EL1&0, EL2, EL3), sem TLB modelada: invalida tudo
            row("1101010100 0 01 000 1000 .... ... .....", null, system(Ir64SystemInstructionOp.TLBI_ALL)),
            row("1101010100 0 01 100 1000 .... ... .....", null, system(Ir64SystemInstructionOp.TLBI_ALL)),
            row("1101010100 0 01 110 1000 .... ... .....", null, system(Ir64SystemInstructionOp.TLBI_ALL)),
            // AT — CRn=0111 CRm=1000, uma linha por forma (op1 = regime, op2 = forma)
            row("1101010100 0 01 000 0111 1000 000 .....", null, translate(Aarch64AddressTranslateForm.S1E1R)),
            row("1101010100 0 01 000 0111 1000 001 .....", null, translate(Aarch64AddressTranslateForm.S1E1W)),
            row("1101010100 0 01 000 0111 1000 010 .....", null, translate(Aarch64AddressTranslateForm.S1E0R)),
            row("1101010100 0 01 000 0111 1000 011 .....", null, translate(Aarch64AddressTranslateForm.S1E0W)),
            row("1101010100 0 01 100 0111 1000 000 .....", null, translate(Aarch64AddressTranslateForm.S1E2R)),
            row("1101010100 0 01 100 0111 1000 001 .....", null, translate(Aarch64AddressTranslateForm.S1E2W)),
            row("1101010100 0 01 100 0111 1000 100 .....", null, translate(Aarch64AddressTranslateForm.S12E1R)),
            row("1101010100 0 01 100 0111 1000 101 .....", null, translate(Aarch64AddressTranslateForm.S12E1W)),
            row("1101010100 0 01 100 0111 1000 110 .....", null, translate(Aarch64AddressTranslateForm.S12E0R)),
            row("1101010100 0 01 100 0111 1000 111 .....", null, translate(Aarch64AddressTranslateForm.S12E0W)),
            row("1101010100 0 01 110 0111 1000 000 .....", null, translate(Aarch64AddressTranslateForm.S1E3R)),
            row("1101010100 0 01 110 0111 1000 001 .....", null, translate(Aarch64AddressTranslateForm.S1E3W)),
            // IC IALLUIS/IALLU (invalidação total) e IC IVAU, Xt — as únicas manutenções que o JIT trata
            row("1101010100 0 01 000 0111 0001 000 .....", null,
                    system(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL)),
            row("1101010100 0 01 000 0111 0101 000 .....", null,
                    system(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_ALL)),
            row("1101010100 0 01 011 0111 0101 001 .....", null, SystemInstructionRows::invalidateByAddress)
    );

    /// Restos de espaço, consultados só quando {@link #ROWS} não casa. Sem sobreposição entre si.
    static final List<DecodeRow<Aarch64Feature, Ir64Op>> FALLBACK_ROWS = List.of(
            // hints — RES NOP por definição (ARM DDI 0487 C6.2.132); sem event-stream, WFE/SEV/SEVL são NOP
            row("1101010100 0 00 011 0010 .... ... 11111", null, system(Ir64SystemInstructionOp.NOP_HINT)),
            // manutenção de cache CRn=0111 — sem cache modelada, NOP; fora: AT (CRm=1000) e DC ZVA
            // (CRm=0100 op2=001, zera memória e DCZID_EL0.DZP=1 a anuncia indisponível)
            cache("1101010100 0 01 ... 0111 00.. ... ....."),
            cache("1101010100 0 01 ... 0111 0100 000 ....."),
            cache("1101010100 0 01 ... 0111 0100 01. ....."),
            cache("1101010100 0 01 ... 0111 0100 1.. ....."),
            cache("1101010100 0 01 ... 0111 0101 ... ....."),
            cache("1101010100 0 01 ... 0111 011. ... ....."),
            cache("1101010100 0 01 ... 0111 1001 ... ....."),
            cache("1101010100 0 01 ... 0111 101. ... ....."),
            cache("1101010100 0 01 ... 0111 11.. ... ....."),
            // resto de SYS (CRn≠0111) e todo SYSL — NOP explícito (B19.6; SYSL não escreve Rt)
            unmodeled("1101010100 0 01 ... 00.. .... ... ....."),
            unmodeled("1101010100 0 01 ... 010. .... ... ....."),
            unmodeled("1101010100 0 01 ... 0110 .... ... ....."),
            unmodeled("1101010100 0 01 ... 1... .... ... ....."),
            unmodeled("1101010100 1 01 ... .... .... ... .....")
    );

    private SystemInstructionRows() {
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, Aarch64Feature requires, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, requires, build);
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> cache(String pattern) {
        return row(pattern, null, system(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP));
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> unmodeled(String pattern) {
        return row(pattern, null, system(Ir64SystemInstructionOp.MAINTENANCE_UNMODELED_NOP));
    }

    private static WordDecoder<Ir64Op> system(Ir64SystemInstructionOp opcode) {
        return (word, address) -> new SystemOp64.SystemInstruction(opcode);
    }

    /// `MSR (immediate)` de campo de `PSTATE` sem estado modelado (`UAO`/`PAN`/`SPSel`/`ALLINT`/`SSBS`/
    /// `DIT`/`TCO`).
    private static WordDecoder<Ir64Op> pstateField() {
        return system(Ir64SystemInstructionOp.PSTATE_FIELD_NOP);
    }

    private static WordDecoder<Ir64Op> flags(Ir64FlagConversionOp opcode) {
        return (word, address) -> new IntegerOp64.ConvertFlags(opcode);
    }

    /// `DAIFSet` (`true`)/`DAIFClr` (`false`) — `CRm` é a máscara `D:A:I:F`.
    private static WordDecoder<Ir64Op> interruptMask(boolean set) {
        return (word, address) -> new SystemOp64.InterruptMask(set, crm(word));
    }

    /// `MSR SVCR*` (`SMSTART`/`SMSTOP`) — `CRm = 0:ZA:SM:imm`.
    private static Ir64Op streamingModeControl(int word, long address) {
        int crm = crm(word);
        return new SystemOp64.StreamingModeControl((crm & SVCR_IMMEDIATE_MASK) != 0, (crm & SVCR_SM_BIT) != 0,
                (crm & SVCR_ZA_BIT) != 0, address);
    }

    /// `AT` — `Rt` carrega o VA de origem.
    private static WordDecoder<Ir64Op> translate(Aarch64AddressTranslateForm form) {
        return (word, address) -> new SystemOp64.AddressTranslate(form, word & RT_MASK);
    }

    private static Ir64Op invalidateByAddress(int word, long address) {
        return new SystemOp64.SystemInstruction(Ir64SystemInstructionOp.INSTRUCTION_CACHE_INVALIDATE_BY_VA,
                word & RT_MASK);
    }

    private static int crm(int word) {
        return (word >>> CRM_SHIFT) & CRM_MASK;
    }
}
