package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VLDR_VSTR` contíguo não-alargante (perfil M, B16.3, MVE/Helium) — as 6 linhas de
/// `target/isa-decode/mve.decode` (confirmadas via `WebFetch` nesta rodada, idênticas às citadas na
/// spec da task):
///
/// ```
/// &vldr_vstr rn qd imm p a w size l u
/// @vldr_vstr ....... . . . . l:1 rn:4 ... ...... imm:7 &vldr_vstr qd=%qd u=0
///
/// VLDR_VSTR   1110110 0 a:1 . 1   . .... ... 111100 .......   @vldr_vstr size=0 p=0 w=1
/// VLDR_VSTR   1110110 0 a:1 . 1   . .... ... 111101 .......   @vldr_vstr size=1 p=0 w=1
/// VLDR_VSTR   1110110 0 a:1 . 1   . .... ... 111110 .......   @vldr_vstr size=2 p=0 w=1
/// VLDR_VSTR   1110110 1 a:1 . w:1 . .... ... 111100 .......   @vldr_vstr size=0 p=1
/// VLDR_VSTR   1110110 1 a:1 . w:1 . .... ... 111101 .......   @vldr_vstr size=1 p=1
/// VLDR_VSTR   1110110 1 a:1 . w:1 . .... ... 111110 .......   @vldr_vstr size=2 p=1
/// ```
///
/// **Achado da spec (correção de off-by-one em relação à prosa da task)**: contando os tokens do
/// formato `@vldr_vstr` bit a bit (`7+1+1+1+1+1+4+3+6+7 = 32`), o marcador de `size`
/// (`111100`/`111101`/`111110`) ocupa `bits[12:7]`, não `bits[11:6]` — `imm7` é `bits[6:0]`, sem
/// sobreposição. `bit22` é o bit alto de `%qd` (não faz parte do marcador de `size`).
///
/// **Achado central (Armadilha 1 da spec)**: `bits[27:25] = 110` é a MESMA forma 2 (extension
/// register load/store) que {@link Thumb2NocpDecoder} reivindica — este decoder TEM que ser
/// registrado ANTES dele em {@code ArmArchitecture#ARMV8_1M_MVE}.
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. Usa o escape hatch de lifting
/// ({@link DecodedInstruction#lifted}) — a forma de operando (`Qd`+`Rn`+offset escalado+P/W/L) não
/// cabe nos campos neutros de {@link DecodedInstruction}, mesmo precedente de
/// {@link NeonLoadStoreDecoder}.
public final class Thumb2MveLoadStoreDecoder implements DecoderExtension {
    /// `bits[31:25]` fixo — mesmo prefixo da forma 2 de `NOCP` (Armadilha 1).
    private static final int TOP7_SHIFT = 25;
    private static final int TOP7_MASK = 0x7F;
    private static final int TOP7_VALUE = 0b111_0110;

    private static final int P_BIT = 24;
    private static final int A_BIT = 23;
    /// Bit alto de `%qd` (`22:1 13:3`) — não confundir com o marcador de `size` (Armadilha 2/achado
    /// acima, off-by-one da prosa da task).
    private static final int QD_HIGH_BIT = 22;
    /// `W` explícito na forma `P=1`; fixo (sempre `1`) na forma `P=0` ("related encoding" quando
    /// `0` — Armadilha 4, pertence a B16.4/B15.3, NÃO decodificado aqui).
    private static final int W_BIT = 21;
    private static final int L_BIT = 20;
    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0xF;
    private static final int QD_LOW_SHIFT = 13;
    private static final int QD_LOW_MASK = 0x7;
    /// `bits[12:7]`: marcador fixo que discrimina `size` (não é um campo decodificado — a própria
    /// literal identifica a linha, ver Javadoc da classe).
    private static final int SIZE_MARKER_SHIFT = 7;
    private static final int SIZE_MARKER_MASK = 0x3F;
    private static final int SIZE_MARKER_BYTE = 0b111100;
    private static final int SIZE_MARKER_HALFWORD = 0b111101;
    private static final int SIZE_MARKER_WORD = 0b111110;
    private static final int IMM7_MASK = 0x7F;

    private static final int PROGRAM_COUNTER = 15;
    private static final int STACK_POINTER = 13;

    private final ArmArchitecture architecture;

    public Thumb2MveLoadStoreDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP7_SHIFT) & TOP7_MASK) != TOP7_VALUE) {
            return null;
        }
        boolean p = ((raw >>> P_BIT) & 1) != 0;
        boolean w;
        if (p) {
            w = ((raw >>> W_BIT) & 1) != 0;
        } else if (((raw >>> W_BIT) & 1) != 0) {
            w = true; // P=0: W forçado em 1 (pós-index com writeback obrigatório)
        } else {
            return null; // P=0,W=0: "related encoding" (B16.4/B15.3) — Armadilha 4, não é nosso
        }
        int size = switch ((raw >>> SIZE_MARKER_SHIFT) & SIZE_MARKER_MASK) {
            case SIZE_MARKER_BYTE -> 0;
            case SIZE_MARKER_HALFWORD -> 1;
            case SIZE_MARKER_WORD -> 2;
            default -> -1; // size=3 (111111) não existe nesta forma, ou marcador não reconhecido
        };
        if (size < 0) {
            return null;
        }
        int rn = (raw >>> RN_SHIFT) & RN_MASK;
        if (rn == PROGRAM_COUNTER || (rn == STACK_POINTER && w)) {
            return null; // UNDEF (trans_VLDR_VSTR real: rn==15 || (rn==13 && w))
        }
        int qd = ((raw >>> QD_HIGH_BIT) & 1) << 3 | ((raw >>> QD_LOW_SHIFT) & QD_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qd)) {
            return null; // mve_check_qreg_bank: Qd tem que ser < 8
        }
        boolean load = ((raw >>> L_BIT) & 1) != 0;
        boolean add = ((raw >>> A_BIT) & 1) != 0;
        int imm7 = raw & IMM7_MASK;
        int scaledOffset = imm7 << size;
        int signedOffset = add ? scaledOffset : -scaledOffset;
        boolean postIndexed = !p;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveLoadStore(qd, rn, signedOffset, load, w, postIndexed, condition));
    }
}
