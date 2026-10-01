package dev.vitorsilverio.armjitter.core64;

/// Endereçamento de tile/slice do array `ZA` (SME, B18.3) — a peça que `MOVA`/`MOVAZ` definem e que
/// `LD1`/`ST1` de tile (B18.4), outer product (B18.5) e a família "array vectors" (B18.9-B18.12)
/// reusam. **Deliberadamente sem estado e sem depender do executor**: só aritmética de índice, para
/// não nascer embutida numa instrução só (Armadilha 1 da B18.3).
///
/// Duas fórmulas, as duas medidas contra `target/arm/tcg/translate-sme.c` (`get_tile_rowcol`/
/// `get_zarray`) e o comentário de `env->za_state.za` em `target/arm/cpu.h` do QEMU — nunca
/// deduzidas: *"for tiles of elements of esz bytes, the Nth row (horizontal slice) of tile T is in
/// ZA[T + N × esz]"*. `ZA` é modelado como `SVL` linhas de `SVL` bytes ({@link Aarch64MatrixRegisters});
/// a linha física de uma slice VERTICAL usa a MESMA fórmula (ver {@link #rowIndex}), só que iterada
/// por elemento em vez de fixada — quem itera é o chamador.
public final class Aarch64MatrixTileAddressing {
    private Aarch64MatrixTileAddressing() {
    }

    /// Índice de slice (linha horizontal OU coluna vertical, dependendo de quem chama) das formas
    /// de tile: `(registerValue arredondado para baixo a um múltiplo de group + imm) MOD (svlBytes
    /// >> esz)`. É `get_tile_rowcol` com `div_len=1` do QEMU — o único efeito de `group` é o
    /// arredondamento de `registerValue` ANTES de somar `imm` (as formas multi-vetor usam `group =
    /// count` para garantir que os `count` sub-vetores do grupo caem em slices consecutivas; a forma
    /// predicada de 1 vetor usa `group = 1`, ou seja, nenhum arredondamento).
    ///
    /// @param registerValue valor de 32 bits de `W<rs>` (os bits acima de 31 são ignorados, como no
    ///                      `tcg_gen_trunc_tl_i32` do QEMU)
    /// @param imm            o campo `off` do encoding (já multiplicado por `count` e somado ao índice
    ///                       do sub-vetor pelo chamador, nas formas multi-vetor)
    /// @param esz            tamanho de elemento (`0` = byte … `4` = quadword)
    /// @param svlBytes       `SVL` efetivo em bytes ({@link Aarch64Core#streamingVectorLengthBytes()})
    /// @param group          `1` (forma de 1 vetor) ou o `count` (`2`/`4`) das formas multi-vetor
    public static int resolveSliceIndex(long registerValue, int imm, int esz, int svlBytes, int group) {
        long index = registerValue & 0xFFFFFFFFL;
        if (group > 1) {
            index &= ~(group - 1L);
        }
        index += imm;
        int elementsPerRow = svlBytes >>> esz;
        return (int) (index % elementsPerRow);
    }

    /// Linha física de `ZA` do tile `tile` (elementos de `1 << esz` bytes) na posição `slice`
    /// (`N` da fórmula do `cpu.h`: `ZA[T + N × esz]`). A mesma fórmula serve o caso horizontal
    /// (`slice` = índice da linha, fixo para a instrução inteira) e o vertical (`slice` = `k`, o
    /// índice do elemento dentro da coluna, iterado `0`..`elementsPerRow-1` pelo chamador).
    ///
    /// @param tile  índice do tile (`0`..`(1 << esz) - 1`)
    /// @param esz   tamanho de elemento (`0` = byte … `4` = quadword)
    /// @param slice posição dentro do tile
    public static int rowIndex(int tile, int esz, int slice) {
        return tile + slice * (1 << esz);
    }

    /// Linha-base das formas "array vector" (`MOVA_az*`/`MOVA_za*`/`MOVAZ_za*`, B18.3; outer product
    /// e `LD1`/`ST1` de array futuros): `(registerValue + off) MOD (svlBytes / n)` — `get_zarray` do
    /// QEMU (`esz=byte`, `tile=0`, sem arredondamento de `registerValue`: `div_len=n`, `vec_mod=0`).
    /// O sub-vetor `i` (`0`..`n-1`) do grupo fica na linha `resolveArrayBaseRow(...) + i × (svlBytes
    /// / n)`.
    ///
    /// @param registerValue valor de 32 bits de `W<rv>`
    /// @param off           o campo `off` do encoding
    /// @param svlBytes      `SVL` efetivo em bytes
    /// @param n             `2` ou `4` — número de vetores do grupo
    public static int resolveArrayBaseRow(long registerValue, int off, int svlBytes, int n) {
        long index = (registerValue & 0xFFFFFFFFL) + off;
        int rowsPerGroup = svlBytes / n;
        return (int) (index % rowsPerGroup);
    }
}
