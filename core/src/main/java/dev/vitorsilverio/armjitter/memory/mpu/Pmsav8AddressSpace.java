package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.ModeChangeListener;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// `AddressSpace` que checa cada acesso contra as regiões da MPU **PMSAv8-32, perfil não-M**
/// (B20.7, {@link Pmsav8MpuRegisters}, regime EL1&0 — o estágio 2/EL2 fica de fora, ver Javadoc
/// da classe `Pmsav8MpuRegisters`), envolvendo um `AddressSpace` físico existente sem alterá-lo
/// (RFC-SOFTMMU decisão 1, mesmo precedente de {@link PmsaAddressSpace}/PMSAv7). **Não traduz**:
/// endereço de saída sempre igual ao de entrada, sem `translationGeneration()` próprio.
///
/// ### Semântica confirmada contra o QEMU real (`target/arm/ptw.c`, `pmsav8_mpu_lookup`, lido via
/// `curl` nesta sessão — não parafraseado; Armadilhas 2/3/4 do épico B20 resolvidas por leitura
/// direta do código, não por dedução)
/// - **Granule de 64 bytes**: `base = PRBAR &amp; ~0x3F` (bits\[31:6\]), `limit = PRLAR | 0x3F`
///   (bits\[31:6\], os 6 bits baixos viram `1`) — **limite INCLUSIVO** até o último byte do bloco.
/// - Região desabilitada (`PRLAR.EN=0`, bit 0) é ignorada por inteiro — **sem** `RSIZE`/alinhamento
///   mínimo (PMSAv8 não tem essa noção; qualquer `PRBAR`/`PRLAR` programado é válido).
/// - **Prioridade NÃO invertida como no PMSAv7** (Achado 2 do épico): o laço varre do índice MAIOR
///   para o MENOR (mesma direção do PMSAv7), mas ao encontrar uma SEGUNDA região que casa o
///   resultado é **falha imediata** (`ARMFault_Permission`), nunca "o índice maior vence". Regiões
///   PMSAv8 não devem se sobrepor; sobreposição é comportamento definido como falha.
/// - **`AP` tem 2 bits** (`PRBAR\[2:1\]`), não 3 como no PMSAv7 — tabela `simple_ap_to_rw_prot_is_user`
///   real do QEMU: `ap=0`→RW só privilegiado; `ap=1`→RW privilegiado+usuário; `ap=2`→RO só
///   privilegiado; `ap=3`→RO privilegiado+usuário. Em termos dos dois bits do campo: o bit BAIXO
///   (`PRBAR\[1\]`) é "también EL0" e o bit ALTO (`PRBAR\[2\]`) é "somente leitura".
/// - **Execução**: permitida sse (leitura OU escrita permitida) E `XN=0` (`PRBAR\[0\]`) — nunca
///   concedida sem que `AP` já dê pelo menos leitura ou escrita (mesma regra do PMSAv7).
/// - **Sem acerto e sem região de fundo aplicável**: falha (`fi->level=0` incondicional, perfil
///   não-M ⇒ `ARMFault_Permission`, NUNCA um código "background" próprio — ver Javadoc de
///   {@link Pmsav8FaultStatus}, achado que substitui o `BACKGROUND` do PMSAv7 por um único código
///   `PERMISSION` para os três desfechos de falha).
/// - **Região de fundo** (`pmsav7_use_background_region`, reusada literalmente pela PMSAv8
///   não-M): disponível só em modo privilegiado com `SCTLR.BR=1` (nunca em modo usuário, mesma
///   regra do PMSAv7). Mapa padrão idêntico ao PMSAv7 (`get_phys_addr_pmsav7_default`, ramo
///   não-M): leitura+escrita sempre; execução só na metade inferior do espaço de endereço
///   (`address&gt;=0` como `int`) — a metade de vetores altos condicionada a `SCTLR.V` é a MESMA
///   simplificação nomeada que {@link PmsaAddressSpace} já documenta, não implementada aqui
///   também.
public final class Pmsav8AddressSpace implements AddressSpace, ModeChangeListener {

    // ── layout PRBAR (ARM DDI 0568A.c §E2.2.2) ──────────────────────────────────────
    private static final int PRBAR_XN_BIT = 0b1;
    private static final int PRBAR_AP_SHIFT = 1;
    private static final int PRBAR_AP_MASK = 0b11;
    /// Granule de 64 bytes: `BASE` são os bits\[31:6\] de `PRBAR`, os 6 bits baixos são `RES0`/`SH`
    /// na leitura crua (`Pmsav8MpuRegisters` não decompõe `SH`, sem consumidor — ver Não inclui da
    /// B20.7). Máscara usada tanto para isolar `BASE` quanto para completar `LIMIT`.
    private static final int GRANULE_MASK = 0x3F;

    // ── layout PRLAR (ARM DDI 0568A.c §E2.2.3) ──────────────────────────────────────
    private static final int PRLAR_ENABLE_BIT = 0b1;

    /// Mapa de fundo (perfil não-M): metade inferior do espaço de 32 bits é sempre executável —
    /// mesma constante de {@link PmsaAddressSpace}.
    private static final int LOW_HALF_SIGN_BIT = 0x8000_0000;

    private final AddressSpace physical;
    private final Pmsav8MpuRegisters mpu;
    private boolean privileged = true;

    /// @param physical barramento físico por trás da checagem — nunca alterado (G3)
    /// @param mpu banco de regiões EL1 (B20.7) que este wrapper consulta a cada acesso
    public Pmsav8AddressSpace(AddressSpace physical, Pmsav8MpuRegisters mpu) {
        this.physical = Objects.requireNonNull(physical, "physical");
        this.mpu = Objects.requireNonNull(mpu, "mpu");
    }

    /// Define se os acessos seguintes são feitos em modo privilegiado (qualquer modo exceto
    /// `USER`) ou usuário — espelha {@link PmsaAddressSpace#setPrivileged}.
    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    @Override
    public void onModeChanged(CpuMode mode) {
        setPrivileged(mode != CpuMode.USER);
    }

    @Override
    public void withUnprivilegedAccess(Runnable action) {
        boolean saved = this.privileged;
        this.privileged = false;
        try {
            action.run();
        } finally {
            this.privileged = saved;
        }
    }

    @Override
    public int read8(int address) {
        checkAccess(address, MemoryAccessType.DATA_READ);
        return physical.read8(address);
    }

    @Override
    public int read16(int address) {
        checkAccess(address, MemoryAccessType.DATA_READ);
        return physical.read16(address);
    }

    @Override
    public int read32(int address) {
        checkAccess(address, MemoryAccessType.DATA_READ);
        return physical.read32(address);
    }

    @Override
    public void write8(int address, int value) {
        checkAccess(address, MemoryAccessType.DATA_WRITE);
        physical.write8(address, value);
    }

    @Override
    public void write16(int address, int value) {
        checkAccess(address, MemoryAccessType.DATA_WRITE);
        physical.write16(address, value);
    }

    @Override
    public void write32(int address, int value) {
        checkAccess(address, MemoryAccessType.DATA_WRITE);
        physical.write32(address, value);
    }

    @Override
    public int fetch16(int address) {
        checkAccess(address, MemoryAccessType.INSTRUCTION_FETCH);
        return physical.read16(address);
    }

    @Override
    public int fetch32(int address) {
        checkAccess(address, MemoryAccessType.INSTRUCTION_FETCH);
        return physical.read32(address);
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return physical.accessCycles(address, sizeBytes, type);
    }

    @Override
    public boolean providesAccessCycles() {
        return physical.providesAccessCycles();
    }

    @Override
    public void notifyWrite(int address) {
        physical.notifyWrite(address);
    }

    /// Checagem por região (ver Javadoc da classe). `SCTLR.M=0` é bypass TOTAL, mesma decisão da
    /// {@link PmsaAddressSpace} (PMSAv7).
    private void checkAccess(int address, MemoryAccessType type) {
        if (!mpu.mpuEnabled()) {
            return;
        }
        int matchedRegion = -1;
        for (int region = mpu.regionCount() - 1; region >= 0; region--) {
            if (!mpu.enabled(region)) {
                continue;
            }
            int base = mpu.base(region) & ~GRANULE_MASK;
            int limit = mpu.limit(region) | GRANULE_MASK;
            if (Integer.compareUnsigned(address, base) < 0 || Integer.compareUnsigned(address, limit) > 0) {
                continue; // endereço fora da faixa desta região
            }
            if (matchedRegion != -1) {
                // Achado 2 do épico: PMSAv8 NÃO tem prioridade — duas regiões casando é falha,
                // nunca "o índice maior vence" (armadilha nº1 do épico, importada do PMSAv7).
                throw new Pmsav8AccessException(address, type, Pmsav8FaultStatus.PERMISSION);
            }
            matchedRegion = region;
        }
        if (matchedRegion == -1) {
            checkBackground(address, type);
            return;
        }
        checkRegionPermission(mpu.base(matchedRegion), type, address);
    }

    private void checkRegionPermission(int prbar, MemoryAccessType type, int address) {
        int ap = (prbar >>> PRBAR_AP_SHIFT) & PRBAR_AP_MASK;
        boolean executeNever = (prbar & PRBAR_XN_BIT) != 0;
        boolean readOnly = (ap & 0b10) != 0;
        boolean userAlso = (ap & 0b01) != 0;
        boolean canRead = privileged || userAlso;
        boolean canWrite = canRead && !readOnly;
        boolean canExecute = (canRead || canWrite) && !executeNever;
        if (!accessAllowed(type, canRead, canWrite, canExecute)) {
            throw new Pmsav8AccessException(address, type, Pmsav8FaultStatus.PERMISSION);
        }
    }

    /// Nenhuma região casou. Modo usuário nunca usa a região de fundo (mesma regra do PMSAv7,
    /// `pmsav7_use_background_region` reusada literalmente pela PMSAv8 não-M).
    private void checkBackground(int address, MemoryAccessType type) {
        if (!privileged || !mpu.backgroundRegionEnabled()) {
            throw new Pmsav8AccessException(address, type, Pmsav8FaultStatus.PERMISSION);
        }
        boolean canExecute = (address & LOW_HALF_SIGN_BIT) == 0;
        if (!accessAllowed(type, true, true, canExecute)) {
            throw new Pmsav8AccessException(address, type, Pmsav8FaultStatus.PERMISSION);
        }
    }

    private static boolean accessAllowed(MemoryAccessType type, boolean canRead, boolean canWrite, boolean canExecute) {
        return switch (type) {
            case DATA_READ -> canRead;
            case DATA_WRITE -> canWrite;
            case INSTRUCTION_FETCH -> canExecute;
        };
    }
}
