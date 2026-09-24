package dev.vitorsilverio.armjitter.memory.mpu;

/// Banco de estado da MPU **PMSAv7** (ARM DDI 0406C, "Protected Memory System Architecture"):
/// os arrays `DRBAR`/`DRSR`/`DRACR` por região, o índice corrente (`RGNR`) e os dois bits de
/// `SCTLR` que controlam a MPU (`M` — habilita, `BR` — Background Region). Modelo **unificado**
/// (`MPUIR.nU=0`): sem espelho de instrução (`IRBAR`/`IRSR`/`IRACR`), tarefa futura nomeada
/// (B20.2, seção "Não inclui").
///
/// Puro armazenamento — **nenhuma checagem de permissão acontece aqui**; é a B20.3
/// (`PmsaAddressSpace`) que lê este banco (via {@link #base}/{@link #sizeRegister}/
/// {@link #accessControl}) para decidir se um acesso passa. Os valores de `DRSR`/`DRACR` são
/// devolvidos CRUS (sem decompor `RSIZE`/`AP`/`XN`) — a decomposição e a regra "`RSIZE=0` é
/// inválido, região inteira ignorada" (confirmada no QEMU real, `get_phys_addr_pmsav7` em
/// `target/arm/ptw.c`) ficam por conta de quem lê, para preservar round-trip fiel da leitura
/// crua (mesma lição do `SCTLR` do `Cp15VmsaCoprocessor`, achado F3).
public final class Pmsav7MpuRegisters {
    private final int regionCount;
    private final int[] drbar;
    private final int[] drsr;
    private final int[] dracr;

    private int rgnr;
    private boolean mpuEnabled;
    private boolean backgroundRegionEnabled;

    /// @param regionCount número de regiões implementadas (vira `MPUIR.DREGION`); varia por
    ///                     processador real (Cortex-R4/R5/R7/R8 têm contagens diferentes) — sem
    ///                     default aqui de propósito, o hospedeiro decide.
    public Pmsav7MpuRegisters(int regionCount) {
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount deve ser positivo: " + regionCount);
        }
        this.regionCount = regionCount;
        this.drbar = new int[regionCount];
        this.drsr = new int[regionCount];
        this.dracr = new int[regionCount];
    }

    public int regionCount() {
        return regionCount;
    }

    public int rgnr() {
        return rgnr;
    }

    /// Escrita de `RGNR` fora de faixa (`value < 0 || value >= regionCount`): **ignorada
    /// silenciosamente**, `rgnr` mantém o valor anterior — mesmo comportamento do QEMU real
    /// (`pmsav7_rgnr_write` em `target/arm/helper.c`, que loga `LOG_GUEST_ERROR` e retorna sem
    /// escrever). O manual arquitetural deixa isto como comportamento não previsto (UNPREDICTABLE);
    /// "ignorar" é a escolha que não corrompe estado nem lança através do core (Armadilha 3 da
    /// B20.2, G8 aplicado a registrador).
    public void setRgnr(int value) {
        if (value < 0 || value >= regionCount) {
            return;
        }
        rgnr = value;
    }

    public int drbar() {
        return drbar[rgnr];
    }

    public void setDrbar(int value) {
        drbar[rgnr] = value;
    }

    public int drsr() {
        return drsr[rgnr];
    }

    public void setDrsr(int value) {
        drsr[rgnr] = value;
    }

    public int dracr() {
        return dracr[rgnr];
    }

    public void setDracr(int value) {
        dracr[rgnr] = value;
    }

    /// Leitura crua de `DRBAR` por índice de região — API pensada para o consumidor da B20.3
    /// (`PmsaAddressSpace`), que precisa varrer todas as regiões, não só a selecionada por `RGNR`.
    public int base(int region) {
        return drbar[region];
    }

    /// Leitura crua de `DRSR` por índice de região (ver Javadoc da classe: sem decompor
    /// `EN`/`RSIZE`/`SRD`).
    public int sizeRegister(int region) {
        return drsr[region];
    }

    /// Leitura crua de `DRACR` por índice de região (ver Javadoc da classe: sem decompor
    /// `B`/`C`/`S`/`TEX`/`AP`/`XN`).
    public int accessControl(int region) {
        return dracr[region];
    }

    /// `SCTLR.M` — habilita a MPU (mesma posição de bit que `SCTLR.M` do VMSA, bit 0).
    public boolean mpuEnabled() {
        return mpuEnabled;
    }

    public void setMpuEnabled(boolean mpuEnabled) {
        this.mpuEnabled = mpuEnabled;
    }

    /// `SCTLR.BR` — habilita o mapa de fundo ("Background Region") quando nenhuma região
    /// programada casa com o endereço, só em modo privilegiado (checagem de privilégio é B20.3).
    public boolean backgroundRegionEnabled() {
        return backgroundRegionEnabled;
    }

    public void setBackgroundRegionEnabled(boolean backgroundRegionEnabled) {
        this.backgroundRegionEnabled = backgroundRegionEnabled;
    }
}
