package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (load alargante/store estreitante, perfil M, B16.4, MVE/Helium)
/// — as 6 linhas de `target/isa-decode/mve.decode` (confirmadas via `WebFetch` nesta rodada):
///
/// ```
/// &vldr_vstr rn qd imm p a w size l u
/// @vldst_wn ... u:1 ... . . . . l:1 . rn:3 qd:3 . ... .. imm:7 &vldr_vstr
///
/// VLDSTB_H   111 . 110 0 a:1 0 1   . 0 ... ... 0 111 01 ....... @vldst_wn p=0 w=1 size=1
/// VLDSTB_H   111 . 110 1 a:1 0 w:1 . 0 ... ... 0 111 01 ....... @vldst_wn p=1 size=1
/// VLDSTB_W   111 . 110 0 a:1 0 1   . 0 ... ... 0 111 10 ....... @vldst_wn p=0 w=1 size=2
/// VLDSTB_W   111 . 110 1 a:1 0 w:1 . 0 ... ... 0 111 10 ....... @vldst_wn p=1 size=2
/// VLDSTH_W   111 . 110 0 a:1 0 1   . 1 ... ... 0 111 10 ....... @vldst_wn p=0 w=1 size=2
/// VLDSTH_W   111 . 110 1 a:1 0 w:1 . 1 ... ... 0 111 10 ....... @vldst_wn p=1 size=2
/// ```
///
/// **Achado central (Armadilha 1 da task, confirmado bit a bit contando os tokens de
/// `@vldst_wn`)**: `Rn` (`bits[18:16]`) e `Qd` (`bits[15:13]`) têm SÓ 3 bits aqui — `bit22`, que na
/// {@link Thumb2MveLoadStoreDecoder} (B16.3) é o bit alto de `%qd` de 4 bits, aqui é um LITERAL
/// fixo em `0` (não faz parte de `Qd`); um encoding com `bit22=1` não decodifica como esta
/// instrução (não casa o literal), verificado por teste dedicado.
///
/// **`u` (`bit28`) é um campo real** (diferente do `@vldr_vstr` da B16.3, que fixa `u=0`): seleciona
/// extensão com sinal (`u=0`) ou sem sinal (`u=1`) no load alargante. Para STORES, `U` tem que ser
/// `0` — o QEMU captura isso no `trans_` (`do_ldst`/`DO_VLDST_WIDE_NARROW`: `ldstfns[u][l]` tem
/// `NULL` em `[1][0]`), não no `decodetree`; aqui tratado como recusa explícita no decode (G8).
///
/// **`bit19` discrimina o tamanho em MEMÓRIA** (`0`=byte → `VLDSTB_*`, `1`=halfword → `VLDSTH_W`) e
/// `bits[8:7]` discriminam o tamanho no REGISTRADOR (`01`=halfword, `10`=word; `00`/`11` não
/// existem — `sz=11` é "related encoding", igual à B16.3). O par memória/registrador é fixo por
/// combinação: `(byte,halfword)`=`VLDSTB_H`, `(byte,word)`=`VLDSTB_W`, `(halfword,word)`=
/// `VLDSTH_W`; `(halfword,halfword)` não existe (rejeitado).
///
/// Offset escalado pelo tamanho em MEMÓRIA (`imm7 << memorySizeLog2`), confirmado via `WebFetch` de
/// `do_ldst` real (`offset = a->imm << msize`) — Armadilha 2 da task, o INVERSO do que pareceria
/// natural (tamanho do registrador).
///
/// Gate: {@link ArmFeature#MVE_INTEGER}. Usa o escape hatch de lifting
/// ({@link DecodedInstruction#lifted}), mesmo precedente de {@link Thumb2MveLoadStoreDecoder}.
public final class Thumb2MveWideningLoadStoreDecoder implements DecoderExtension {
    /// `bits[31:29]` fixo.
    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    /// `bits[27:25]` fixo — mesmo prefixo de forma 2 de `NOCP` que a B16.3 já evita (Armadilha 3).
    private static final int MID3_SHIFT = 25;
    private static final int MID3_MASK = 0x7;
    private static final int MID3_VALUE = 0b110;

    private static final int U_BIT = 28;
    private static final int P_BIT = 24;
    private static final int A_BIT = 23;
    /// Literal fixo em `0` — NÃO é o bit alto de `Qd` aqui (Armadilha 1, diferente da B16.3).
    private static final int QD_HIGH_LITERAL_BIT = 22;
    private static final int W_BIT = 21;
    private static final int L_BIT = 20;
    /// Discrimina o tamanho em MEMÓRIA: `0`=byte, `1`=halfword.
    private static final int MEMORY_HALFWORD_BIT = 19;
    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0x7;
    private static final int QD_SHIFT = 13;
    private static final int QD_MASK = 0x7;
    /// `bit12` fixo em `0`.
    private static final int ZERO_LITERAL_BIT = 12;
    /// `bits[11:9]` fixo em `111`.
    private static final int LOW3_SHIFT = 9;
    private static final int LOW3_MASK = 0x7;
    private static final int LOW3_VALUE = 0b111;
    /// `bits[8:7]`: marcador do tamanho NO REGISTRADOR.
    private static final int SIZE_MARKER_SHIFT = 7;
    private static final int SIZE_MARKER_MASK = 0x3;
    private static final int SIZE_MARKER_HALFWORD = 0b01;
    private static final int SIZE_MARKER_WORD = 0b10;
    private static final int IMM7_MASK = 0x7F;

    private static final int MEMORY_SIZE_BYTE = 0;
    private static final int MEMORY_SIZE_HALFWORD = 1;
    private static final int REGISTER_SIZE_HALFWORD = 1;
    private static final int REGISTER_SIZE_WORD = 2;

    private final ArmArchitecture architecture;

    public Thumb2MveWideningLoadStoreDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP3_SHIFT) & TOP3_MASK) != TOP3_VALUE
                || ((raw >>> MID3_SHIFT) & MID3_MASK) != MID3_VALUE) {
            return null;
        }
        if (((raw >>> QD_HIGH_LITERAL_BIT) & 1) != 0 || ((raw >>> ZERO_LITERAL_BIT) & 1) != 0
                || ((raw >>> LOW3_SHIFT) & LOW3_MASK) != LOW3_VALUE) {
            return null;
        }
        boolean load = ((raw >>> L_BIT) & 1) != 0;
        boolean unsignedLoad = ((raw >>> U_BIT) & 1) != 0;
        if (!load && unsignedLoad) {
            return null; // store com U=1 é UNDEF (do_ldst real: ldstfns[1][0] == NULL)
        }
        boolean p = ((raw >>> P_BIT) & 1) != 0;
        boolean w;
        if (p) {
            w = ((raw >>> W_BIT) & 1) != 0;
        } else if (((raw >>> W_BIT) & 1) != 0) {
            w = true; // P=0: W forçado em 1
        } else {
            return null; // P=0,W=0: "related encoding", não é nosso
        }
        int memorySizeLog2 = ((raw >>> MEMORY_HALFWORD_BIT) & 1) != 0
                ? MEMORY_SIZE_HALFWORD : MEMORY_SIZE_BYTE;
        int registerSizeLog2 = switch ((raw >>> SIZE_MARKER_SHIFT) & SIZE_MARKER_MASK) {
            case SIZE_MARKER_HALFWORD -> REGISTER_SIZE_HALFWORD;
            case SIZE_MARKER_WORD -> REGISTER_SIZE_WORD;
            default -> -1; // sz=11 "related encoding", ou marcador 00 (não existe)
        };
        if (registerSizeLog2 < 0 || registerSizeLog2 == memorySizeLog2) {
            return null; // (halfword,halfword) não existe: não há alargamento de igual-para-igual
        }
        int rn = (raw >>> RN_SHIFT) & RN_MASK;
        int qd = (raw >>> QD_SHIFT) & QD_MASK;
        boolean add = ((raw >>> A_BIT) & 1) != 0;
        int imm7 = raw & IMM7_MASK;
        int scaledOffset = imm7 << memorySizeLog2;
        int signedOffset = add ? scaledOffset : -scaledOffset;
        boolean postIndexed = !p;
        boolean signed = !unsignedLoad;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveWideningLoadStore(qd, rn, signedOffset, memorySizeLog2, registerSizeLog2,
                        load, signed, w, postIndexed, condition));
    }
}
