package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `VMLADAV_S`/`VMLADAV_U`/`VMLSDAV`/`VMLALDAV_S`/`VMLALDAV_U`/`VMLSLDAV`/`VRMLALDAVH_S`/
/// `VRMLALDAVH_U`/`VRMLSLDAVH`/`VMAXV_S`/`VMAXV_U`/`VMINV_S`/`VMINV_U`/`VMAXAV`/`VMINAV`/
/// `VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` — sub-família 3 da B16.13 (perfil M, MVE/Helium,
/// `target/isa-decode/mve.decode`, linhas 422-494, 28 encodings, bit a bit contra o arquivo real
/// via `curl`):
///
/// ```
/// %rdahi 20:3 !function=times_2_plus_1
/// %rdalo 13:3 !function=times_2
/// %size_16 16:1 !function=plus_1
///
/// {
///   VMLADAV_S      1110 1110 1111  ... . ... . 1110 . 0 . 0 ... 0 @vmladav
///   VMLALDAV_S     1110 1110 1 ... ... . ... . 1110 . 0 . 0 ... 0 @vmlaldav
/// }
/// {
///   VMLADAV_U      1111 1110 1111  ... . ... . 1110 . 0 . 0 ... 0 @vmladav
///   VMLALDAV_U     1111 1110 1 ... ... . ... . 1110 . 0 . 0 ... 0 @vmlaldav
/// }
/// {
///   VMLSDAV        1110 1110 1111  ... . ... . 1110 . 0 . 0 ... 1 @vmladav
///   VMLSLDAV       1110 1110 1 ... ... . ... . 1110 . 0 . 0 ... 1 @vmlaldav
/// }
/// {
///   VMLSDAV        1111 1110 1111  ... 0 ... . 1110 . 0 . 0 ... 1 @vmladav_nosz
///   VRMLSLDAVH     1111 1110 1 ... ... 0 ... . 1110 . 0 . 0 ... 1 @vmlaldav_nosz
/// }
/// VMLADAV_S        1110 1110 1111  ... 0 ... . 1111 . 0 . 0 ... 1 @vmladav_nosz
/// VMLADAV_U        1111 1110 1111  ... 0 ... . 1111 . 0 . 0 ... 1 @vmladav_nosz
/// {
///   [ VMAXNMAV VMINNMAV VMAXNMV VMINNMV ]   (size=2, bits[19:16] literais)
///   [ VMAXV_S VMINV_S VMAXAV VMINAV ]       (@vmaxv)
///   VMLADAV_S      1110 1110 1111  ... 0 ... . 1111 . 0 . 0 ... 0 @vmladav_nosz
///   VRMLALDAVH_S   1110 1110 1 ... ... 0 ... . 1111 . 0 . 0 ... 0 @vmlaldav_nosz
/// }
/// {
///   [ VMAXNMAV VMINNMAV VMAXNMV VMINNMV ]   (size=1)
///   [ VMAXV_U VMINV_U ]
///   VMLADAV_U      1111 1110 1111  ... 0 ... . 1111 . 0 . 0 ... 0 @vmladav_nosz
///   VRMLALDAVH_U   1111 1110 1 ... ... 0 ... . 1111 . 0 . 0 ... 0 @vmlaldav_nosz
/// }
/// ```
///
/// **Achado de decodetree (mesma classe da Armadilha 3 da B16.13a)**: os dois grandes blocos `{}`
/// finais têm grupos ANINHADOS `[...]` — checados nesta ordem exata (mais específico primeiro,
/// verificado com um script de overlap bit a bit contra as 28 linhas reais: `VMAXNMV`/`VMAXNMAV`
/// colidem em bits com `VMAXV`/`VMINV` quando `bits[19:18]="11"`, e TODOS colidem com o catch-all
/// `VMLADAV_S`/`VRMLALDAVH_S` quando os bits específicos de cada nível não batem — achatar a ordem
/// rouba o encoding). As duas linhas soltas de `VMLADAV_S`/`VMLADAV_U` (formas byte, `bit0=1`) e o
/// catch-all dentro dos blocos grandes (`bit0=0`) decodificam para o MESMO `IrOp` — `bit0` não tem
/// significado arquitetural conhecido aqui, só desambiguação de espaço no arquivo real (verificado,
/// não presumido: nenhuma das duas formas aparece em nenhuma outra linha do arquivo).
///
/// **`VMLSDAV`/`VRMLSLDAVH` usam o prefixo `1111 1110` (normalmente "forma U")** mas continuam
/// SEMPRE assinados — não existe `vmlsdavu`/`vmlsldavu` no QEMU real (`mve_helper.c`); o prefixo
/// aqui só reaproveita espaço de bits, não indica sinal.
///
/// **`VMLADAV_U`/`VMLALDAV_U`/`VRMLALDAVH_U` não têm forma `exchange`** (`x=1` sem helper real no
/// QEMU — `fns[size][1]=NULL`/`fns[1]=NULL` — recusado no decode, G8).
///
/// **`VMAXV`/`VMINV`/`VMAXAV`/`VMINAV`/`VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` não têm bit `a`**:
/// sempre leem `Rda` ATUAL como semente (mesma categoria de `VABAV`, B16.13a) — verbatim de
/// `do_vmaxv`/`mve_helper.c`.
///
/// **`VMAXNMAV`/`VMINNMAV`/`VMAXNMV`/`VMINNMV` usam `size=2` no bloco `S` (top byte `1110 1110`) e
/// `size=1` no bloco `U` (top byte `1111 1110`)** — aqui o bit que normalmente distingue
/// assinado/não-assinado escolhe PRECISÃO (binary32/binary16), não sinal (`DO_VMAXV_FP`,
/// `fns={NULL,h,s,NULL}`). Gate `MVE_FLOAT` (`dc_isar_feature(aa32_mve_fp)`), não `MVE_INTEGER`.
///
/// Gate: {@link ArmFeature#MVE_INTEGER} (inteiro) / {@link ArmFeature#MVE_FLOAT} (as 8 formas
/// `VMAXNM*`/`VMINNM*`). TEM que ser registrado ANTES de `Thumb2NocpDecoder`
/// (`bits[27:24]=1110` desta seção sob `M_PROFILE`). Verificado, não presumido: nenhuma das 28
/// linhas colide em bits com `Thumb2MveReduceDecoder` (B16.13a, mesmo prefixo `bits[31:29]=111`/
/// `bits[27:24]=1110`) — script de overlap exaustivo contra `VADDV`/`VADDLV`/`VABAV_S`/`VABAV_U`/
/// `Vimm_1r` não achou nenhuma sobreposição real, ordem relativa entre os dois decoders não importa.
public final class Thumb2MveDualAccumulateDecoder implements DecoderExtension {
    private static final int GPR_SP = 13;
    private static final int GPR_PC = 15;

    private static final int TOP3_SHIFT = 29;
    private static final int TOP3_MASK = 0x7;
    private static final int TOP3_VALUE = 0b111;
    private static final int TOP24_SHIFT = 24;
    private static final int TOP24_MASK = 0xF;
    private static final int TOP24_VALUE = 0b1110;

    // ─── Campos comuns a @vmladav/@vmlaldav (com e sem `_nosz`) ────────────────────────────────────
    private static final int QN_LOW3_SHIFT = 17;
    private static final int QN_LOW3_MASK = 0x7;
    private static final int QN_HIGH_BIT = 7;
    private static final int RDALO_RAW_SHIFT = 13;
    private static final int RDALO_RAW_MASK = 0x7;
    private static final int RDAHI_RAW_SHIFT = 20;
    private static final int RDAHI_RAW_MASK = 0x7;
    private static final int SIZE16_BIT = 16;
    private static final int X_BIT = 12;
    private static final int A_BIT = 5;
    private static final int QM_SHIFT = 1;
    private static final int QM_MASK = 0x7;

    // ─── @vmaxv / @vmaxnmv ───────────────────────────────────────────────────────────────────────
    private static final int VMAXV_SIZE_SHIFT = 18;
    private static final int VMAXV_SIZE_MASK = 0x3;
    private static final int VMAXV_SIZE_INVALID = 3;
    private static final int VMAXV_RDA_SHIFT = 12;
    private static final int VMAXV_RDA_MASK = 0xF;
    private static final int VMAXV_QM_HIGH_BIT = 5;
    private static final int VMAXV_QM_LOW_SHIFT = 1;
    private static final int VMAXV_QM_LOW_MASK = 0x7;

    // ─── Máscaras/valores das 28 linhas literais (derivados bit a bit do arquivo real via script) ─
    private static final int MLADAV_S_GENERAL_MASK = 0xFFF00F51;
    private static final int MLADAV_S_GENERAL_VALUE = 0xEEF00E00;
    private static final int MLALDAV_S_MASK = 0xFF800F51;
    private static final int MLALDAV_S_VALUE = 0xEE800E00;
    private static final int MLADAV_U_GENERAL_MASK = 0xFFF00F51;
    private static final int MLADAV_U_GENERAL_VALUE = 0xFEF00E00;
    private static final int MLALDAV_U_MASK = 0xFF800F51;
    private static final int MLALDAV_U_VALUE = 0xFE800E00;
    private static final int MLSDAV_GENERAL_MASK = 0xFFF00F51;
    private static final int MLSDAV_GENERAL_VALUE = 0xEEF00E01;
    private static final int MLSLDAV_MASK = 0xFF800F51;
    private static final int MLSLDAV_VALUE = 0xEE800E01;
    private static final int MLSDAV_NOSZ_MASK = 0xFFF10F51;
    private static final int MLSDAV_NOSZ_VALUE = 0xFEF00E01;
    private static final int VRMLSLDAVH_MASK = 0xFF810F51;
    private static final int VRMLSLDAVH_VALUE = 0xFE800E01;
    private static final int MLADAV_S_LOOSE_MASK = 0xFFF10F51;
    private static final int MLADAV_S_LOOSE_VALUE = 0xEEF00F01;
    private static final int MLADAV_U_LOOSE_MASK = 0xFFF10F51;
    private static final int MLADAV_U_LOOSE_VALUE = 0xFEF00F01;

    private static final int VMAXNMAV_S_MASK = 0xFFFF0FD1;
    private static final int VMAXNMAV_S_VALUE = 0xEEEC0F00;
    private static final int VMINNMAV_S_MASK = 0xFFFF0FD1;
    private static final int VMINNMAV_S_VALUE = 0xEEEC0F80;
    private static final int VMAXNMV_S_MASK = 0xFFFF0FD1;
    private static final int VMAXNMV_S_VALUE = 0xEEEE0F00;
    private static final int VMINNMV_S_MASK = 0xFFFF0FD1;
    private static final int VMINNMV_S_VALUE = 0xEEEE0F80;
    private static final int VMAXV_S_MASK = 0xFFF30FD1;
    private static final int VMAXV_S_VALUE = 0xEEE20F00;
    private static final int VMINV_S_MASK = 0xFFF30FD1;
    private static final int VMINV_S_VALUE = 0xEEE20F80;
    private static final int VMAXAV_MASK = 0xFFF30FD1;
    private static final int VMAXAV_VALUE = 0xEEE00F00;
    private static final int VMINAV_MASK = 0xFFF30FD1;
    private static final int VMINAV_VALUE = 0xEEE00F80;
    private static final int MLADAV_S_TAIL_MASK = 0xFFF10F51;
    private static final int MLADAV_S_TAIL_VALUE = 0xEEF00F00;
    private static final int VRMLALDAVH_S_MASK = 0xFF810F51;
    private static final int VRMLALDAVH_S_VALUE = 0xEE800F00;

    private static final int VMAXNMAV_U_MASK = 0xFFFF0FD1;
    private static final int VMAXNMAV_U_VALUE = 0xFEEC0F00;
    private static final int VMINNMAV_U_MASK = 0xFFFF0FD1;
    private static final int VMINNMAV_U_VALUE = 0xFEEC0F80;
    private static final int VMAXNMV_U_MASK = 0xFFFF0FD1;
    private static final int VMAXNMV_U_VALUE = 0xFEEE0F00;
    private static final int VMINNMV_U_MASK = 0xFFFF0FD1;
    private static final int VMINNMV_U_VALUE = 0xFEEE0F80;
    private static final int VMAXV_U_MASK = 0xFFF30FD1;
    private static final int VMAXV_U_VALUE = 0xFEE20F00;
    private static final int VMINV_U_MASK = 0xFFF30FD1;
    private static final int VMINV_U_VALUE = 0xFEE20F80;
    private static final int MLADAV_U_TAIL_MASK = 0xFFF10F51;
    private static final int MLADAV_U_TAIL_VALUE = 0xFEF00F00;
    private static final int VRMLALDAVH_U_MASK = 0xFF810F51;
    private static final int VRMLALDAVH_U_VALUE = 0xFE800F00;

    private final ArmArchitecture architecture;

    public Thumb2MveDualAccumulateDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.MVE_INTEGER)) {
            return null;
        }
        if (((raw >>> TOP3_SHIFT) & TOP3_MASK) != TOP3_VALUE) {
            return null;
        }
        if (((raw >>> TOP24_SHIFT) & TOP24_MASK) != TOP24_VALUE) {
            return null;
        }
        DecodedInstruction result;
        if ((result = tryDualAccumulate32(raw, address, condition, MLADAV_S_GENERAL_MASK, MLADAV_S_GENERAL_VALUE,
                false, false, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate64(raw, address, condition, MLALDAV_S_MASK, MLALDAV_S_VALUE,
                false, false, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLADAV_U_GENERAL_MASK, MLADAV_U_GENERAL_VALUE,
                true, false, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate64(raw, address, condition, MLALDAV_U_MASK, MLALDAV_U_VALUE,
                true, false, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLSDAV_GENERAL_MASK, MLSDAV_GENERAL_VALUE,
                false, true, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate64(raw, address, condition, MLSLDAV_MASK, MLSLDAV_VALUE,
                false, true, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLSDAV_NOSZ_MASK, MLSDAV_NOSZ_VALUE,
                false, true, false)) != null) {
            return result;
        }
        if ((result = tryRoundingHigh(raw, address, condition, VRMLSLDAVH_MASK, VRMLSLDAVH_VALUE,
                false, true, true)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLADAV_S_LOOSE_MASK, MLADAV_S_LOOSE_VALUE,
                false, false, false)) != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLADAV_U_LOOSE_MASK, MLADAV_U_LOOSE_VALUE,
                true, false, false)) != null) {
            return result;
        }
        if ((result = tryMaxMinBlockS(raw, address, condition)) != null) {
            return result;
        }
        return tryMaxMinBlockU(raw, address, condition);
    }

    /// Bloco `S` (linhas 464-478): ordem EXATA — grupo `[NMAV/NMV]`, grupo `[V/AV]`, catch-all
    /// `VMLADAV_S`/`VRMLALDAVH_S` (Armadilha 3: achatar a ordem rouba o encoding).
    private DecodedInstruction tryMaxMinBlockS(int raw, int address, Condition condition) {
        final int FP_SIZE = 2;
        DecodedInstruction result;
        if ((result = tryFpMinMax(raw, address, condition, VMAXNMAV_S_MASK, VMAXNMAV_S_VALUE, true, true, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryFpMinMax(raw, address, condition, VMINNMAV_S_MASK, VMINNMAV_S_VALUE, false, true, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryFpMinMax(raw, address, condition, VMAXNMV_S_MASK, VMAXNMV_S_VALUE, true, false, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryFpMinMax(raw, address, condition, VMINNMV_S_MASK, VMINNMV_S_VALUE, false, false, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryIntMinMax(raw, address, condition, VMAXV_S_MASK, VMAXV_S_VALUE, true, false, false))
                != null) {
            return result;
        }
        if ((result = tryIntMinMax(raw, address, condition, VMINV_S_MASK, VMINV_S_VALUE, false, false, false))
                != null) {
            return result;
        }
        if ((result = tryIntMinMax(raw, address, condition, VMAXAV_MASK, VMAXAV_VALUE, true, false, true))
                != null) {
            return result;
        }
        if ((result = tryIntMinMax(raw, address, condition, VMINAV_MASK, VMINAV_VALUE, false, false, true))
                != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLADAV_S_TAIL_MASK, MLADAV_S_TAIL_VALUE,
                false, false, false)) != null) {
            return result;
        }
        return tryRoundingHigh(raw, address, condition, VRMLALDAVH_S_MASK, VRMLALDAVH_S_VALUE, false, false, true);
    }

    /// Bloco `U` (linhas 480-493): mesma ordem do bloco `S`.
    private DecodedInstruction tryMaxMinBlockU(int raw, int address, Condition condition) {
        final int FP_SIZE = 1;
        DecodedInstruction result;
        if ((result = tryFpMinMax(raw, address, condition, VMAXNMAV_U_MASK, VMAXNMAV_U_VALUE, true, true, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryFpMinMax(raw, address, condition, VMINNMAV_U_MASK, VMINNMAV_U_VALUE, false, true, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryFpMinMax(raw, address, condition, VMAXNMV_U_MASK, VMAXNMV_U_VALUE, true, false, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryFpMinMax(raw, address, condition, VMINNMV_U_MASK, VMINNMV_U_VALUE, false, false, FP_SIZE))
                != null) {
            return result;
        }
        if ((result = tryIntMinMax(raw, address, condition, VMAXV_U_MASK, VMAXV_U_VALUE, true, true, false))
                != null) {
            return result;
        }
        if ((result = tryIntMinMax(raw, address, condition, VMINV_U_MASK, VMINV_U_VALUE, false, true, false))
                != null) {
            return result;
        }
        if ((result = tryDualAccumulate32(raw, address, condition, MLADAV_U_TAIL_MASK, MLADAV_U_TAIL_VALUE,
                true, false, false)) != null) {
            return result;
        }
        return tryRoundingHigh(raw, address, condition, VRMLALDAVH_U_MASK, VRMLALDAVH_U_VALUE, true, false, false);
    }

    /// `VMLADAV_S`/`VMLADAV_U`/`VMLSDAV` (32 bits, `Rda`). `sizeFromField=true` extrai `size` via
    /// `%size_16`; `false` fixa `size=0` (formas `_nosz`).
    private DecodedInstruction tryDualAccumulate32(int raw, int address, Condition condition, int mask, int value,
            boolean unsignedForm, boolean subtract, boolean sizeFromField) {
        if ((raw & mask) != value) {
            return null;
        }
        int qn = extractQn(raw);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qn)) {
            return null;
        }
        boolean exchange = ((raw >>> X_BIT) & 1) != 0;
        if (exchange && unsignedForm) {
            return null;
        }
        int size = sizeFromField ? extractSize16(raw) : 0;
        int qm = (raw >>> QM_SHIFT) & QM_MASK;
        int rda = ((raw >>> RDALO_RAW_SHIFT) & RDALO_RAW_MASK) * 2;
        boolean accumulate = ((raw >>> A_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorDualAccumulate(unsignedForm, subtract, exchange, accumulate, size, qn, qm, rda,
                        condition));
    }

    /// `VMLALDAV_S`/`VMLALDAV_U`/`VMLSLDAV` (64 bits, `RdaHi:RdaLo`).
    private DecodedInstruction tryDualAccumulate64(int raw, int address, Condition condition, int mask, int value,
            boolean unsignedForm, boolean subtract, boolean sizeFromField) {
        if ((raw & mask) != value) {
            return null;
        }
        int qn = extractQn(raw);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qn)) {
            return null;
        }
        int rdahi = ((raw >>> RDAHI_RAW_SHIFT) & RDAHI_RAW_MASK) * 2 + 1;
        if (rdahi == GPR_SP || rdahi == GPR_PC) {
            return null;
        }
        boolean exchange = ((raw >>> X_BIT) & 1) != 0;
        if (exchange && unsignedForm) {
            return null;
        }
        int size = sizeFromField ? extractSize16(raw) : 0;
        int qm = (raw >>> QM_SHIFT) & QM_MASK;
        int rdalo = ((raw >>> RDALO_RAW_SHIFT) & RDALO_RAW_MASK) * 2;
        boolean accumulate = ((raw >>> A_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorDualAccumulateLong(unsignedForm, subtract, exchange, accumulate, size, qn, qm,
                        rdahi, rdalo, condition));
    }

    /// `VRMLALDAVH_S`/`VRMLALDAVH_U`/`VRMLSLDAVH` (elementos SEMPRE word, `RdaHi:RdaLo`).
    private DecodedInstruction tryRoundingHigh(int raw, int address, Condition condition, int mask, int value,
            boolean unsignedForm, boolean subtract, boolean allowExchange) {
        if ((raw & mask) != value) {
            return null;
        }
        int qn = extractQn(raw);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qn)) {
            return null;
        }
        int rdahi = ((raw >>> RDAHI_RAW_SHIFT) & RDAHI_RAW_MASK) * 2 + 1;
        if (rdahi == GPR_SP || rdahi == GPR_PC) {
            return null;
        }
        boolean exchange = ((raw >>> X_BIT) & 1) != 0;
        if (exchange && !allowExchange) {
            return null;
        }
        int qm = (raw >>> QM_SHIFT) & QM_MASK;
        int rdalo = ((raw >>> RDALO_RAW_SHIFT) & RDALO_RAW_MASK) * 2;
        boolean accumulate = ((raw >>> A_BIT) & 1) != 0;
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorRoundingDualAccumulateHigh(unsignedForm, subtract, exchange, accumulate, qn, qm,
                        rdahi, rdalo, condition));
    }

    /// `VMAXV_S`/`VMAXV_U`/`VMINV_S`/`VMINV_U`/`VMAXAV`/`VMINAV` (`@vmaxv`, `size` extraído).
    private DecodedInstruction tryIntMinMax(int raw, int address, Condition condition, int mask, int value,
            boolean max, boolean unsignedForm, boolean absoluteForm) {
        if ((raw & mask) != value) {
            return null;
        }
        int size = (raw >>> VMAXV_SIZE_SHIFT) & VMAXV_SIZE_MASK;
        if (size == VMAXV_SIZE_INVALID) {
            return null;
        }
        int rda = (raw >>> VMAXV_RDA_SHIFT) & VMAXV_RDA_MASK;
        if (rda == GPR_SP || rda == GPR_PC) {
            return null;
        }
        int qm = ((raw >>> VMAXV_QM_HIGH_BIT) & 1) << 3 | ((raw >>> VMAXV_QM_LOW_SHIFT) & VMAXV_QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorMinMaxAcrossVector(max, unsignedForm, absoluteForm, size, qm, rda, condition));
    }

    /// `VMAXNMV`/`VMINNMV`/`VMAXNMAV`/`VMINNMAV` (`@vmaxnmv`, `size` fixo pelo bloco). Gate
    /// {@link ArmFeature#MVE_FLOAT}.
    private DecodedInstruction tryFpMinMax(int raw, int address, Condition condition, int mask, int value,
            boolean max, boolean absoluteForm, int esz) {
        if ((raw & mask) != value) {
            return null;
        }
        if (!architecture.has(ArmFeature.MVE_FLOAT)) {
            return null;
        }
        int rda = (raw >>> VMAXV_RDA_SHIFT) & VMAXV_RDA_MASK;
        if (rda == GPR_SP || rda == GPR_PC) {
            return null;
        }
        int qm = ((raw >>> VMAXV_QM_HIGH_BIT) & 1) << 3 | ((raw >>> VMAXV_QM_LOW_SHIFT) & VMAXV_QM_LOW_MASK);
        if (!VfpRegisters.isValidMveQuadRegister(architecture, qm)) {
            return null;
        }
        return DecodedInstruction.lifted(address, raw, InstructionSet.THUMB, condition,
                new IrOp.MveVectorFpMinMaxAcrossVector(max, absoluteForm, esz, qm, rda, condition));
    }

    private static int extractQn(int raw) {
        return ((raw >>> QN_HIGH_BIT) & 1) << 3 | ((raw >>> QN_LOW3_SHIFT) & QN_LOW3_MASK);
    }

    private static int extractSize16(int raw) {
        return ((raw >>> SIZE16_BIT) & 1) + 1;
    }
}
