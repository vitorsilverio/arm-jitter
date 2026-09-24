package dev.vitorsilverio.armjitter.memory.mpu;

/// Banco de estado da MPU **PMSAv8-32** (ARM DDI 0568A.c, "ARM Architecture Reference Manual
/// Supplement — ARMv8-R AArch32 edition", §E2.2.2-E2.2.6): os arrays `PRBAR`/`PRLAR` por região
/// EL1, o par `HPRBAR`/`HPRLAR` de EL2 (Hyp mode — B20.7 "Não inclui": armazenamento sem
/// checagem própria, o estágio 2 de proteção fica para uma task futura nomeada), os seletores
/// `PRSELR`/`HPRSELR`, os bitmaps `PRENR`/`HPRENR` (sem consumidor — nenhum acesso deste projeto
/// depende deles hoje, ver Javadoc de {@link Cp15Pmsav8MpuCoprocessor}) e os dois bits de `SCTLR`
/// que controlam a MPU de EL1 (`M`/`BR`, mesma posição de bit que {@link Pmsav7MpuRegisters}).
///
/// **Classe irmã de {@link Pmsav7MpuRegisters}, nunca uma extensão/generalização dela** (decisão
/// do épico B20, reafirmada pela Armadilha 7 da B20.7): o layout é OUTRO — base+limite (`PRBAR`/
/// `PRLAR`) em vez de base+tamanho+sub-regiões (`DRBAR`/`DRSR`/`DRACR`), granule de 64 bytes (não
/// `2^RSIZE`), `AP` de **2** bits (não 3), sem `SRD`.
///
/// Puro armazenamento — **nenhuma checagem de permissão acontece aqui**; é a {@link
/// Pmsav8AddressSpace} que lê este banco (via {@link #base}/{@link #limit}/{@link #enabled})
/// para decidir se um acesso passa. Os valores são devolvidos CRUS (sem decompor `AP`/`XN`/`AI`) —
/// mesma lição já paga pelo {@link Pmsav7MpuRegisters} (round-trip fiel da leitura crua).
public final class Pmsav8MpuRegisters {
    private final int regionCount;
    private final int[] prbar;
    private final int[] prlar;
    private int prselr;
    /// `PRENR` (bitmap de habilitação das primeiras 32 regiões) — puro armazenamento, sem
    /// consumidor: `PRLAR.EN` já é a fonte autoritativa de habilitação por região que {@link
    /// Pmsav8AddressSpace} consulta (mesmo papel de `DRSR.EN` no PMSAv7); nenhuma fonte real
    /// confirmada nesta rodada de spec/implementação exige `PRENR` como segunda fonte de verdade
    /// (o QEMU real, `target/arm/helper.c`, também não implementa um `PRENR` de EL1 — só `HPRENR`
    /// de EL2, ver {@link #hprenr}).
    private int prenr;

    private final int hRegionCount;
    private final int[] hprbar;
    private final int[] hprlar;
    private int hprselr;
    /// `HPRENR` (EL2) — puro armazenamento (ARM DDI 0568A.c; confirmado no QEMU real,
    /// `target/arm/helper.c`, `hprenr_read`/`hprenr_write`), sem consumidor: o estágio 2 de
    /// proteção (MPU de EL2 aplicada sobre EL1) não é modelado por esta task (B20.7 "Não inclui",
    /// item 1) — task futura nomeada.
    private int hprenr;

    private boolean mpuEnabled;
    private boolean backgroundRegionEnabled;

    /// @param regionCount  número de regiões EL1 implementadas (vira `MPUIR.DREGION`) — varia por
    ///                      processador real (`Cortex-R52`/`R52+`); sem default de propósito.
    /// @param hRegionCount número de regiões EL2/Hyp implementadas (vira `HMPUIR`) — pode ser
    ///                      diferente do de EL1 no hardware real (banco de registradores próprio).
    public Pmsav8MpuRegisters(int regionCount, int hRegionCount) {
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount deve ser positivo: " + regionCount);
        }
        if (hRegionCount <= 0) {
            throw new IllegalArgumentException("hRegionCount deve ser positivo: " + hRegionCount);
        }
        this.regionCount = regionCount;
        this.prbar = new int[regionCount];
        this.prlar = new int[regionCount];
        this.hRegionCount = hRegionCount;
        this.hprbar = new int[hRegionCount];
        this.hprlar = new int[hRegionCount];
    }

    public int regionCount() {
        return regionCount;
    }

    public int hRegionCount() {
        return hRegionCount;
    }

    public int prselr() {
        return prselr;
    }

    /// Escrita de `PRSELR` fora de faixa: **ignorada silenciosamente**, mesmo comportamento
    /// documentado por {@link Pmsav7MpuRegisters#setRgnr} para `RGNR` (UNPREDICTABLE no manual;
    /// "ignorar" não corrompe estado nem lança através do core, G8 aplicado a registrador).
    public void setPrselr(int value) {
        if (value < 0 || value >= regionCount) {
            return;
        }
        prselr = value;
    }

    public int hprselr() {
        return hprselr;
    }

    public void setHprselr(int value) {
        if (value < 0 || value >= hRegionCount) {
            return;
        }
        hprselr = value;
    }

    public int prbar() {
        return prbar[prselr];
    }

    public void setPrbar(int value) {
        prbar[prselr] = value;
    }

    public int prlar() {
        return prlar[prselr];
    }

    public void setPrlar(int value) {
        prlar[prselr] = value;
    }

    public int hprbar() {
        return hprbar[hprselr];
    }

    public void setHprbar(int value) {
        hprbar[hprselr] = value;
    }

    public int hprlar() {
        return hprlar[hprselr];
    }

    public void setHprlar(int value) {
        hprlar[hprselr] = value;
    }

    public int prenr() {
        return prenr;
    }

    public void setPrenr(int value) {
        prenr = value;
    }

    public int hprenr() {
        return hprenr;
    }

    public void setHprenr(int value) {
        hprenr = value;
    }

    /// Leitura crua de `PRBAR` por índice de região EL1 — API pensada para o consumidor
    /// ({@link Pmsav8AddressSpace}), que precisa varrer todas as regiões, não só a selecionada por
    /// `PRSELR`.
    public int base(int region) {
        return prbar[region];
    }

    /// Leitura crua de `PRLAR` por índice de região EL1 (ver Javadoc da classe: sem decompor
    /// `EN`/`AI`).
    public int limit(int region) {
        return prlar[region];
    }

    /// `PRLAR.EN` (bit 0) da região `region` — fonte autoritativa de habilitação (ver Javadoc de
    /// {@link #prenr}).
    public boolean enabled(int region) {
        return (prlar[region] & 1) != 0;
    }

    /// `SCTLR.M` — habilita a MPU (mesma posição de bit que {@link Pmsav7MpuRegisters#mpuEnabled}).
    public boolean mpuEnabled() {
        return mpuEnabled;
    }

    public void setMpuEnabled(boolean mpuEnabled) {
        this.mpuEnabled = mpuEnabled;
    }

    /// `SCTLR.BR` — habilita o mapa de fundo ("Background Region") quando nenhuma região
    /// programada casa com o endereço, só em modo privilegiado (checagem de privilégio é
    /// {@link Pmsav8AddressSpace}) — mesma semântica confirmada no QEMU real
    /// (`pmsav7_use_background_region`, reusada literalmente pela PMSAv8 não-M, ver Javadoc de
    /// {@link Pmsav8AddressSpace}).
    public boolean backgroundRegionEnabled() {
        return backgroundRegionEnabled;
    }

    public void setBackgroundRegionEnabled(boolean backgroundRegionEnabled) {
        this.backgroundRegionEnabled = backgroundRegionEnabled;
    }
}
