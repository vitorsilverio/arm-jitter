package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// `DLS`/`WLS`/`LE` (perfil M, B15.6, Low Overhead Branch Extension — ARMv8.1-M) — formas PURAS
/// (sem tail-predication), `target/isa-decode/t32.decode` (`# LE and WLS immediate`):
///
/// ```
/// DLS   1111 0 0000 100     rn:4 1110 0000 0000 0001 size=4
/// WLS   1111 0 0000 100     rn:4 1100 . .......... 1 imm=%lob_imm size=4
/// LE    1111 0 0000 0 f:1 tp:1 1111 1100 . .......... 1 imm=%lob_imm
/// %lob_imm 1:10 11:1 !function=times_2
/// ```
///
/// **`LCTP`/`WLSTP`/`DLSTP`/`LETP`/`VCTP` (tail-predication, `size≠4`/`tp=1`, MESMO espaço de bits)
/// NÃO são decodificadas aqui** — ver Javadoc de {@link ArmFeature#LOW_OVERHEAD_BRANCH}: exigem
/// genuinamente `FEAT_MVE` no hardware real (confirmado contra `trans_LCTP`/`trans_DLS`/
/// `trans_WLS`/`trans_LE` do QEMU, `target/arm/tcg/translate.c`), então essas formas caem em
/// `UNIMPLEMENTED` (❌ honesto, mesmo tratamento que hardware real sem MVE dá) até a B16 trazer o
/// banco `VPR`. Isso simplifica de volta a lista original do épico B15 (que agrupava `LCTP` junto
/// das formas "puras") — achado desta task, corrigido aqui: `LCTP` compartilha o MESMO opcode-base
/// de `DLSTP` com `Rn` fixo em `1111`(15), e QEMU exige `aa32_mve` para as duas, sem exceção.
///
/// **Achado central sobre o bit `f` de `LE`** (medido contra `trans_LE` do QEMU, não deduzido do
/// nome): `f=1` ("loop-forever") NÃO decrementa `LR` nem testa nada — é um branch incondicional de
/// volta ao início do loop (`gen_jmp(s, jmp_diff(s, -a->imm))` sem checagem alguma). Só `f=0`
/// decrementa/testa `LR`, e o teste ocorre ANTES do decremento: se `LR` (não-assinado) `<= 1`, a
/// instrução NÃO desvia nem decrementa (a última iteração já rodou); senão `LR -= 1` e desvia de
/// volta. `f=1` e `tp=1` juntos são combinação inválida (`UNDEFINED` no QEMU); como `tp=1` já não
/// decodifica aqui, essa combinação cai automaticamente em `UNIMPLEMENTED` — nada a checar à parte.
///
/// **`LR`(R14) é literalmente o contador** — não há registrador oculto: `DLS Rn`/`WLS Rn` gravam
/// `Rn` em `LR`, `LE` decrementa `LR` direto. Um `BL`/`BLX` dentro do loop sobrescreve `LR` (mesmo
/// comportamento arquitetural do hardware real, não um bug deste projeto).
public final class Thumb2LowOverheadBranchDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    // ── `DLS` pura (`size=4`, sentinela sem forma tail-predicated): `1111 0000 0100 rn:4 1110
    // 0000 0000 0001` — bits[19:16]=Rn livre, resto fixo. ─────────────────────────────────────
    private static final int DLS_MASK = 0xFFF0_FFFF;
    private static final int DLS_VALUE = 0xF040_E001;

    // ── `WLS` pura (`size=4`): `1111 0000 0100 rn:4 1100 H imm10 1` — bits[19:16]=Rn,
    // bits[11]=H, bits[10:1]=imm10 livres. ─────────────────────────────────────────────────────
    private static final int WLS_MASK = 0xFFF0_F001;
    private static final int WLS_VALUE = 0xF040_C001;

    // ── `LE`: `1111 0000 00 f tp 1111 1100 H imm10 1` — bits[21]=f, bits[20]=tp, bits[11]=H,
    // bits[10:1]=imm10 livres. Checada ANTES de qualquer forma `WLS`/`DLS` de tail-predication
    // (fora do escopo desta task) porque seu padrão fixo (`Rn`-position=1111) é MAIS ESPECÍFICO —
    // mesma prioridade que o `.decode` real usa (`LE` listado antes do comentário "This is
    // WLSTP"). ──────────────────────────────────────────────────────────────────────────────────
    private static final int LE_MASK = 0xFFCF_F001;
    private static final int LE_VALUE = 0xF00F_C001;
    private static final int LE_FOREVER_BIT = 21;
    private static final int LE_TAIL_PREDICATED_BIT = 20;

    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0xF;
    private static final int IMM_H_BIT = 11;
    private static final int IMM_LOW_SHIFT = 1;
    private static final int IMM_LOW_MASK = 0x3FF;
    private static final int IMM_LOW_BITS = 10;
    private static final int LOB_IMM_SCALE = 2; // `%lob_imm !function=times_2`

    /// Endereço de origem da instrução + 4 (leitura de `PC` em Thumb, ARM DDI 0553 A5.1) — base do
    /// destino de `WLS`/`LE`, mesma convenção de `ThumbDecoder` para `CBZ`/`B.W` (`address + 4 +
    /// offset`).
    private static final int PC_READ_OFFSET = 4;

    public Thumb2LowOverheadBranchDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.LOW_OVERHEAD_BRANCH)) {
            return null;
        }
        int pcBase = address + PC_READ_OFFSET;
        if ((raw & DLS_MASK) == DLS_VALUE) {
            int rn = (raw >>> RN_SHIFT) & RN_MASK;
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_START, -1, rn, -1, 0, false, false, false);
        }
        if ((raw & WLS_MASK) == WLS_VALUE) {
            int rn = (raw >>> RN_SHIFT) & RN_MASK;
            int target = pcBase + decodeLobImmediate(raw);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_START, -1, rn, -1, target, false, false, true);
        }
        if ((raw & LE_MASK) == LE_VALUE) {
            if (((raw >>> LE_TAIL_PREDICATED_BIT) & 1) != 0) {
                // LETP: exige FEAT_MVE, ausente aqui (ver Javadoc da classe) — não decodifica.
                return null;
            }
            boolean forever = ((raw >>> LE_FOREVER_BIT) & 1) != 0;
            int target = pcBase - decodeLobImmediate(raw);
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOOP_END, -1, -1, -1, target, false, false, forever);
        }
        return null;
    }

    /// `%lob_imm 1:10 11:1 !function=times_2`: `(bit[11]<<10) | bits[10:1]`, depois `*2`.
    private static int decodeLobImmediate(int raw) {
        int h = (raw >>> IMM_H_BIT) & 1;
        int low = (raw >>> IMM_LOW_SHIFT) & IMM_LOW_MASK;
        return ((h << IMM_LOW_BITS) | low) * LOB_IMM_SCALE;
    }
}
