package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// `VLLDM`/`VLSTM` + `VSCCLRM` (perfil M, B15.5, `target/isa-decode/m-nocp.decode`) — as 3 linhas
/// finais do bloco `NOCP` que {@link Thumb2NocpDecoder} já exclui explicitamente do espaço genérico
/// (`VLLDM_VLSTM_MASK`/`VALUE`, `VSCCLRM_MASK`/`VALUE` daquela classe — os valores aqui são OS
/// MESMOS, reproduzidos porque são `private` lá):
///
/// ```
/// # Special cases which do not take an early NOCP: VLLDM and VLSTM
/// VLLDM_VLSTM  1110 1100 001 l:1 rn:4 0000 1010 op:1 000 0000
/// # VSCCLRM (new in v8.1M) is similar:
/// VSCCLRM      1110 1100 1.01 1111 .... 1011 imm:7 0   vd=%vd_dp size=3
/// VSCCLRM      1110 1100 1.01 1111 .... 1010 imm:8     vd=%vd_sp size=2
/// ```
///
/// **`VLLDM`/`VLSTM`**: "lazy" é uma otimização de HARDWARE (adia o save/restore do banco FP até a
/// primeira instrução FP do handler, `FPCCR.LSPACT`) sem semântica observável que este emulador
/// precise reproduzir — sem pipeline real, não há ciclos a economizar. O `.decode` real prioriza
/// estas 2 formas ANTES do `NOCP` genérico e as trata como `UNDEFINED` explícito ("these are the two
/// UNDEFs that must take precedence over NOCP", comentário do arquivo real) — sem FPU real no perfil
/// M (nunca haverá, ver B15.2/B15.3), `VLLDM`/`VLSTM` sempre tomam essa forma. `Rn`/`l`/`op` não são
/// extraídos: nenhum deles influencia o resultado (sempre `UNDEFINED`).
///
/// **`VSCCLRM`**: zera `D<Vd>`..`D<Vd+imm-1>` (precisão dupla, `size=3`, `%vd_dp`=`D:Vd`) ou
/// `S<Vd>`..`S<Vd+imm-1>` (precisão simples, `size=2`, `%vd_sp`=`Vd:D`) — layout `D`/`Vd` idêntico ao
/// de {@link VfpDecoder} (`VD_EXTENSION_BIT`/`VD_NIBBLE_SHIFT`). Sem consulta a
/// `CONTROL_S.SFPA`/`FPCCR.ASPEN` (que decidiriam NOP vs. zerar de verdade no hardware real): este
/// projeto não modela `FPCCR`/lazy stacking (ver B15.4 "Não inclui"), então sempre zera — mesma
/// simplificação que a spec da B15.5 documenta como aceitável na ausência desse estado.
///
/// Anexado via {@link ArmArchitecture#thumb32DecoderExtensions()}, gateado por
/// {@link ArmFeature#M_PROFILE} — precisa vir ANTES de {@link Thumb2NocpDecoder} na lista, mesmo
/// motivo de {@link Thumb2VfpSystemAccessDecoder} (formas específicas do mesmo bloco QEMU antes do
/// genérico).
public final class Thumb2VlldmVlstmVscclrmDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    // ── VLLDM_VLSTM: `1110 1100 001 l:1 rn:4 0000 1010 op:1 000 0000` (mesmos valores que a
    // exclusão de Thumb2NocpDecoder usa) ──────────────────────────────────────────────────────
    private static final int VLLDM_VLSTM_MASK = 0xFFE0_FF7F;
    private static final int VLLDM_VLSTM_VALUE = 0xEC20_0A00;

    // ── VSCCLRM dupla (`size=3`): `1110 1100 1.01 1111 .... 1011 imm:7 0` ────────────────────
    private static final int VSCCLRM_DOUBLE_MASK = 0xFFBF_0F01;
    private static final int VSCCLRM_DOUBLE_VALUE = 0xEC9F_0B00;
    private static final int DOUBLE_IMM_SHIFT = 1;
    private static final int DOUBLE_IMM_MASK = 0x7F;

    // ── VSCCLRM simples (`size=2`): `1110 1100 1.01 1111 .... 1010 imm:8` ────────────────────
    private static final int VSCCLRM_SINGLE_MASK = 0xFFBF_0F00;
    private static final int VSCCLRM_SINGLE_VALUE = 0xEC9F_0A00;
    private static final int SINGLE_IMM_MASK = 0xFF;

    // ── Campos de registrador `Vd`/`D` — mesmo layout de VfpDecoder (`vd_dp`=`D:Vd`, `vd_sp`=`Vd:D`).
    private static final int D_BIT_SHIFT = 22;
    private static final int VD_NIBBLE_SHIFT = 12;
    private static final int VD_NIBBLE_MASK = 0xF;

    public Thumb2VlldmVlstmVscclrmDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.M_PROFILE)) {
            return null;
        }
        if ((raw & VLLDM_VLSTM_MASK) == VLLDM_VLSTM_VALUE) {
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.VLLDM_VLSTM, -1, -1, -1, 0, false, false, false);
        }
        if ((raw & VSCCLRM_DOUBLE_MASK) == VSCCLRM_DOUBLE_VALUE) {
            return decodeVscclrm(raw, address, condition, true);
        }
        if ((raw & VSCCLRM_SINGLE_MASK) == VSCCLRM_SINGLE_VALUE) {
            return decodeVscclrm(raw, address, condition, false);
        }
        return null;
    }

    private static DecodedInstruction decodeVscclrm(int raw, int address, Condition condition,
            boolean doublePrecision) {
        int d = (raw >>> D_BIT_SHIFT) & 1;
        int vdNibble = (raw >>> VD_NIBBLE_SHIFT) & VD_NIBBLE_MASK;
        int vd = doublePrecision ? (d << 4) | vdNibble : (vdNibble << 1) | d;
        int count = doublePrecision
                ? (raw >>> DOUBLE_IMM_SHIFT) & DOUBLE_IMM_MASK
                : raw & SINGLE_IMM_MASK;
        int last = vd + count - 1;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.VSCCLRM,
                vd, -1, -1, last, false, false, doublePrecision);
    }
}
