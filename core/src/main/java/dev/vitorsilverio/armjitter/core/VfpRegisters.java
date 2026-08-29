package dev.vitorsilverio.armjitter.core;

/// Banco de registradores VFP/Advanced SIMD do lado de 32 bits: **32 registradores `D`** (double,
/// 64 bits) com vista **`Q0`-`Q15`** (128 bits, `Q<i>` = par `D<2i>` baixo + `D<2i+1>` alto) e
/// vista **`S0`-`S31`** (single, 32 bits) — `S<2i>` = metade BAIXA de `D<i>`, `S<2i+1>` = metade
/// ALTA, layout little-endian fixo do VFPv2/v3 (ARM DDI 0406C A2.6.2). `S`/`D`/`Q` são *views* do
/// MESMO armazenamento, não células independentes; **apenas `D0`-`D15` têm vista `S`** — `D16`-`D31`
/// (VFPv3-D32/NEON, B13.1) só são endereçáveis como `D`/`Q`, a arquitetura não tem `S32`+.
///
/// Sibling estrutural de {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters}
/// (SIMD&FP de AArch64), NÃO uma extensão dele: a disciplina do B6 é nunca misturar os dois
/// mundos — a interface é espelhada (mesmos nomes/assinaturas), o armazenamento é separado.
/// Diferença semântica que **permanece** entre os dois bancos: aqui `setS`/`setD` NUNCA zeram
/// bits fora do próprio registrador escrito (VFP32 clássico), ao contrário do "SIMD&FP
/// destructive write" do lado A64.
///
/// Sempre armazena BITS crus IEEE 754/inteiros: float/double são apenas *views* de conveniência,
/// nunca o tipo primário — garante save-state/snapshot bit-exatos, incluindo NaN payloads, que
/// aritmética em `float`/`double` do Java poderia canonicalizar.
public final class VfpRegisters implements dev.vitorsilverio.armjitter.advsimd.AdvSimdRegisterWords {
    /// Quantidade de registradores `S` (single-precision) endereçáveis — vista de 32 bits de
    /// `D0`-`D15`. A arquitetura ARM tem exatamente 32 `S`; `D16`-`D31` NÃO têm vista `S`.
    public static final int SINGLE_COUNT = 32;

    /// Quantidade de registradores `D` (double-precision): `D0`-`D31` (VFPv3-D32/NEON). VFPv2 e
    /// VFPv3-D16 usam só `D0`-`D15`, mas o armazenamento é sempre o banco completo — o gate de
    /// disponibilidade vive no decoder ({@code ArmFeature.VFPV3_D32}), não aqui.
    public static final int DOUBLE_COUNT = 32;

    /// Quantidade de registradores `Q` (quadword, 128 bits): `Q<i>` = `D<2i>` (baixo) + `D<2i+1>`
    /// (alto).
    public static final int QUAD_COUNT = 16;

    /// Máscara para a metade baixa de 32 bits.
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;

    /// Bits crus de cada `D<n>` (`D0`-`D31`). `S<2i>` = bits 31:0 de `d[i]`, `S<2i+1>` = bits
    /// 63:32; `Q<i>` = `d[2i]` (bits 63:0) + `d[2i+1]` (bits 127:64).
    private final long[] d = new long[DOUBLE_COUNT];

    /// Lê os bits crus de `S<index>` (`0`-`31`) — metade baixa (`index` par) ou alta (`index`
    /// ímpar) de `D<index / 2>`.
    public int s(int index) {
        int shift = (index & 1) << 5;
        return (int) (d[index >> 1] >>> shift);
    }

    /// Grava os bits crus de `S<index>` (`0`-`31`) na metade correspondente de `D<index / 2>`,
    /// SEM tocar na outra metade nem em qualquer outro registrador (semântica VFP32 clássica —
    /// oposto do "destructive write" do lado A64).
    public void setS(int index, int bits) {
        int register = index >> 1;
        if ((index & 1) == 0) {
            d[register] = (d[register] & ~LOW_32_BITS_MASK) | (bits & LOW_32_BITS_MASK);
        } else {
            d[register] = (d[register] & LOW_32_BITS_MASK) | ((long) bits << 32);
        }
    }

    /// Lê os 64 bits crus de `D<index>` (`0`-`31`).
    public long d(int index) {
        return d[index];
    }

    /// Grava os 64 bits crus de `D<index>` (`0`-`31`), refletindo nas vistas `S`/`Q` que o
    /// sobrepõem, SEM zerar nenhum bit fora de `D<index>` (semântica VFP32 clássica).
    public void setD(int index, long bits) {
        d[index] = bits;
    }

    /// Lê os bits 63:0 crus de `Q<index>` (`0`-`15`), isto é `D<2 * index>`.
    public long low64(int index) {
        return d[index << 1];
    }

    /// Lê os bits 127:64 crus de `Q<index>` (`0`-`15`), isto é `D<2 * index + 1>`.
    public long high64(int index) {
        return d[(index << 1) + 1];
    }

    /// Lê os 128 bits crus de `Q<index>` (`0`-`15`) como `[baixo, alto]` (`long[2]`).
    public long[] q(int index) {
        return new long[] {d[index << 1], d[(index << 1) + 1]};
    }

    /// Grava os 128 bits crus de `Q<index>` (`0`-`15`) de uma vez — `D<2 * index>` recebe
    /// `loBits`, `D<2 * index + 1>` recebe `hiBits`.
    public void setQ(int index, long loBits, long hiBits) {
        d[index << 1] = loBits;
        d[(index << 1) + 1] = hiBits;
    }

    /// Lê um elemento (lane) de `Q<index>` (`0`-`15`) de `1 << sizeLog2` bytes, começando no
    /// índice `lane` (lane `0` = bits menos significativos), com zero-extend em um `long`.
    /// `sizeLog2` vai de `0` (byte) a `3` (doubleword) — ARM DDI 0487, notação de "arrangement"
    /// AdvSIMD. Assinatura/semântica espelhadas de
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#element(int, int, int)}.
    public long element(int index, int lane, int sizeLog2) {
        int elementBits = 8 << sizeLog2;
        int bitOffset = lane * elementBits;
        long word = bitOffset < 64 ? d[index << 1] : d[(index << 1) + 1];
        int shift = bitOffset & 0x3F;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        return (word >>> shift) & mask;
    }

    /// Grava um elemento (lane) de `Q<index>` (`0`-`15`) de `1 << sizeLog2` bytes no índice
    /// `lane`, sem afetar nenhum outro bit do registrador. Assinatura/semântica espelhadas de
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#setElement(int, int, int, long)}.
    public void setElement(int index, int lane, int sizeLog2, long value) {
        int elementBits = 8 << sizeLog2;
        int bitOffset = lane * elementBits;
        int shift = bitOffset & 0x3F;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        long shiftedValue = (value & mask) << shift;
        long clearMask = ~(mask << shift);
        int register = (index << 1) + (bitOffset < 64 ? 0 : 1);
        d[register] = (d[register] & clearMask) | shiftedValue;
    }

    /// Grava um valor escalar de `1 << sizeLog2` bytes (`sizeLog2` `0`-`3`, `B`/`H`/`S`/`D`) na
    /// lane `0` de `Q<index>` (`0`-`15`) e ZERA o resto do registrador. Espelha
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#setScalar(int, int, long)}
    /// (para `Q` inteiro use {@link #setQ}).
    public void setScalar(int index, int sizeLog2, long value) {
        int elementBits = 8 << sizeLog2;
        long mask = elementBits == 64 ? -1L : (1L << elementBits) - 1;
        d[index << 1] = value & mask;
        d[(index << 1) + 1] = 0;
    }

    /// Replica `value` (elemento de `1 << sizeLog2` bytes) por todas as lanes de `Q<index>`
    /// (`0`-`15`) — `quad`=`false` preenche só os 64 bits baixos (`D<2 * index>`) e ZERA os altos;
    /// `quad`=`true` preenche os 128 bits. Espelha
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#replicateElement(int, long, int, boolean)}.
    public void replicateElement(int index, long value, int sizeLog2, boolean quad) {
        int elementBits = 8 << sizeLog2;
        int totalBits = quad ? 128 : 64;
        int lanes = totalBits / elementBits;
        for (int lane = 0; lane < lanes; lane++) {
            setElement(index, lane, sizeLog2, value);
        }
        if (!quad) {
            d[(index << 1) + 1] = 0;
        }
    }

    /// Vista plana em palavras de 64 bits (RFC B13.2): a palavra `n` É o `D<n>` — o banco de 32
    /// bits já é literalmente um `long[32]`. Base de um operando de 64 bits (`D<n>`) = `n`; base de
    /// um operando de 128 bits (`Q<n>`) = `n * `{@link #WORDS_PER_QUAD}. É por isso que NEON
    /// consegue endereçar `D` ÍMPAR como operando de 64 bits, coisa que a API `Q`-indexada
    /// ({@link #element}) não expressa.
    @Override
    public long word(int index) {
        return d[index];
    }

    /// Grava uma palavra da vista plana (ver {@link #word}), sem afetar nenhum outro `D`.
    @Override
    public void setWord(int index, long value) {
        d[index] = value;
    }

    /// Palavras de 64 bits por registrador `Q` na vista plana (`Q<n>` = `D<2n>` + `D<2n+1>`).
    public static final int WORDS_PER_QUAD = 2;

    /// View `float` de `S<index>` (mesmos bits, sem aritmética).
    public float sFloat(int index) {
        return Float.intBitsToFloat(s(index));
    }

    /// Grava `S<index>` a partir de uma view `float` (mesmos bits, sem aritmética).
    public void setSFloat(int index, float value) {
        setS(index, Float.floatToRawIntBits(value));
    }

    /// View `double` de `D<index>` (mesmos bits, sem aritmética).
    public double dDouble(int index) {
        return Double.longBitsToDouble(d(index));
    }

    /// Grava `D<index>` a partir de uma view `double` (mesmos bits, sem aritmética).
    public void setDDouble(int index, double value) {
        setD(index, Double.doubleToRawLongBits(value));
    }

    /// Serializa o banco no formato **legado** (32 `int` = `S0`-`S31` = `D0`-`D15`) para um save
    /// state. Formato preservado byte a byte da era pré-B13.1 (G3): save-states de `gbaemu`/
    /// `ndsemu` já gravados continuam carregando. O banco estendido (`D16`-`D31`) NÃO entra aqui —
    /// ver {@link #saveStateExtended}.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        for (int i = 0; i < SINGLE_COUNT; i++) {
            out.writeInt(s(i));
        }
    }

    /// Restaura o banco gravado por {@link #saveState} (formato legado: 32 `int` = `S0`-`S31`).
    /// `D16`-`D31` ficam com o valor atual (o chamador zera antes se quiser — ver {@link #reset}).
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < SINGLE_COUNT; i++) {
            setS(i, in.readInt());
        }
    }

    /// Serializa o banco `D` **completo** (`D0`-`D31`, 32 `long`) — usado pelos consumidores que
    /// bumparem a própria versão de save-state para persistir VFPv3-D32/NEON. Nunca chamado como
    /// efeito colateral de {@link #saveState} (G3).
    public void saveStateExtended(java.io.DataOutputStream out) throws java.io.IOException {
        for (long value : d) {
            out.writeLong(value);
        }
    }

    /// Restaura o banco `D` completo gravado por {@link #saveStateExtended} (32 `long`).
    public void loadStateExtended(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < d.length; i++) {
            d[i] = in.readLong();
        }
    }

    /// Zera o banco inteiro (`D0`-`D31`) — usado ao carregar um save state de formato anterior à
    /// B3.3, que não possui banco VFP.
    public void reset() {
        java.util.Arrays.fill(d, 0L);
    }

    /// Cópia defensiva no formato **legado** (`int[32]` = `S0`-`S31` = `D0`-`D15`), usada pelo
    /// harness de equivalência (G1 — não mudar o tipo de retorno; ver {@link #snapshotD}).
    public int[] snapshot() {
        int[] out = new int[SINGLE_COUNT];
        for (int i = 0; i < SINGLE_COUNT; i++) {
            out[i] = s(i);
        }
        return out;
    }

    /// Cópia defensiva do banco `D` **completo** (`long[32]` = `D0`-`D31`).
    public long[] snapshotD() {
        return java.util.Arrays.copyOf(d, d.length);
    }
}
