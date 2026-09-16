package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;

/// `VPST`/`VPNOT`/`VPSEL` (perfil M, B16.2, MVE/Helium, `target/isa-decode/mve.decode`, linhas
/// 732-739) — as 3 únicas linhas de padrão da máquina de predicação MVE (o épico B16 estimava
/// "~8"; ver Javadoc de `MveVptState` para o resto da máquina):
///
/// ```
/// VPSEL  1111 1110 0 . 11 ... 1 ... 0 1111 . 0 . 0 ... 1 @2op_nosz
/// VPNOT  1111 1110 0 0 11 000 1 000 0 1111 0100 1101
/// VPST   1111 1110 0 . 11 000 1 ... 0 1111 0100 1101 mask=%mask_22_13
/// ```
///
/// **Achado central da B16.1 (Armadilha 1), confirmado aqui**: as 3 caem no MESMO espaço de bits
/// que {@link Thumb2NocpDecoder} reivindica como forma 1 (`MCR`/`MRC` clássico, `bits[27:24] =
/// 1110`, `bits[31:29] = 111`) — este decoder TEM que ser registrado ANTES de
/// {@link Thumb2NocpDecoder} em {@code ARMV8_1M_MVE}, mesmo padrão de
/// {@link Thumb2VlldmVlstmVscclrmDecoder}.
///
/// `VPNOT` é literalmente `VPST` com `mask=0` (comentário do arquivo real: "mask == 0 is a
/// related encoding") — como `VPNOT` fixa MAIS bits (`bit22=0` e `bits[15:13]=000`, o campo
/// `mask` inteiro), checá-la primeiro resolve a ambiguidade sem precisar de exclusão explícita
/// (mesma prioridade "mais específico primeiro" do arquivo `.decode` real, que lista `VPNOT` antes
/// de `VPST`).
///
/// `VPSEL` usa `@2op_nosz` (`%qd 22:1 13:3`, `%qn 7:1 17:3`, `%qm 5:1 1:3`) — os 3 campos de
/// registrador Q produzem um índice `0`-`15` (MESMA convenção de par-D que {@link VfpRegisters#q}
/// usa), mas MVE só implementa `Q0`-`Q7`: um índice `8`-`15` faz este decoder devolver `null`
/// (G8 — não decodifica `VPSEL` com um registrador fora do banco real; o encoding cai para
/// {@link Thumb2NocpDecoder}, que o recusa nomeadamente como `NOCP` em vez de silenciosamente
/// confundir com outra instrução — comportamento real para essa faixa é UNPREDICTABLE, então
/// qualquer recusa nomeada é aceitável, ver `## Resultado` da task para a decisão registrada).
public final class Thumb2MvePredicationDecoder implements DecoderExtension {
    // ── VPNOT: totalmente fixo (`VPST` com `mask=0`) ──────────────────────────────────────────
    private static final int VPNOT_MASK = 0xFFFFFFFF;
    private static final int VPNOT_VALUE = 0xFE310F4D;

    // ── VPSEL (`@2op_nosz`): bits fixos fora dos campos qd/qn/qm ──────────────────────────────
    private static final int VPSEL_MASK = 0xFFB11F51;
    private static final int VPSEL_VALUE = 0xFE310F01;

    // ── VPST (`mask=%mask_22_13` livre, resto fixo) ───────────────────────────────────────────
    private static final int VPST_MASK = 0xFFBF1FFF;
    private static final int VPST_VALUE = 0xFE310F4D;

    // ── %qd 22:1 13:3 / %qn 7:1 17:3 / %qm 5:1 1:3 (mve.decode real) — bit alto (M) concatenado
    // com os 3 bits baixos, MESMA convenção de índice `0`-`15` (par-D) que VfpRegisters#q usa. ──
    private static final int QD_HIGH_BIT_SHIFT = 22;
    private static final int QD_LOW_FIELD_SHIFT = 13;
    private static final int QN_HIGH_BIT_SHIFT = 7;
    private static final int QN_LOW_FIELD_SHIFT = 17;
    private static final int QM_HIGH_BIT_SHIFT = 5;
    private static final int QM_LOW_FIELD_SHIFT = 1;
    private static final int LOW_FIELD_MASK = 0x7;

    // ── %mask_22_13 22:1 13:3 (mesma posição de bits que %qd — VPST não tem registrador Qd) ───
    private static final int MASK_HIGH_BIT_SHIFT = 22;
    private static final int MASK_LOW_FIELD_SHIFT = 13;

    private final ArmArchitecture architecture;

    public Thumb2MvePredicationDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if ((raw & VPNOT_MASK) == VPNOT_VALUE) {
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.VPNOT, -1, -1, -1, 0, false, false, false);
        }
        if ((raw & VPSEL_MASK) == VPSEL_VALUE) {
            return decodeVpsel(raw, address, condition);
        }
        if ((raw & VPST_MASK) == VPST_VALUE) {
            int mask = quadField(raw, MASK_HIGH_BIT_SHIFT, MASK_LOW_FIELD_SHIFT);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.VPST, -1, -1, -1, mask, false, false, false);
        }
        return null;
    }

    private DecodedInstruction decodeVpsel(int raw, int address, Condition condition) {
        int qd = quadField(raw, QD_HIGH_BIT_SHIFT, QD_LOW_FIELD_SHIFT);
        int qn = quadField(raw, QN_HIGH_BIT_SHIFT, QN_LOW_FIELD_SHIFT);
        int qm = quadField(raw, QM_HIGH_BIT_SHIFT, QM_LOW_FIELD_SHIFT);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qn)
                || !VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.VPSEL, qd, qn, qm, 0, false, false, false);
    }

    /// Extrai um campo de registrador Q no formato `%qX high:1 low:3` (bit alto concatenado com
    /// 3 bits baixos, `%mask_22_13` reusa a MESMA forma para o campo `mask` de `VPST`).
    private static int quadField(int raw, int highBitShift, int lowFieldShift) {
        int high = (raw >>> highBitShift) & 1;
        int low = (raw >>> lowFieldShift) & LOW_FIELD_MASK;
        return (high << 3) | low;
    }
}
