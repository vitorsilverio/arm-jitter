package dev.vitorsilverio.armjitter.memory.mmu;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// `AddressSpace64` que checa cada acesso contra as regiões da MPU **PMSAv8-64, EL1** ({@link
/// Pmsav8SystemRegisters64}, regime "Secure EL1&amp;0" — o estágio 2/EL2 fica de fora desta task,
/// ver Javadoc de {@link Pmsav8SystemRegisters64}) — B20.8, ARM DDI 0600A.d capítulo C1, lido via
/// `curl` nesta sessão (não parafraseado). Envolve um `AddressSpace64` físico existente sem
/// alterá-lo (mesmo precedente RFC-SOFTMMU de {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav8AddressSpace}/PMSAv8-32). **Não
/// traduz**: endereço de saída sempre igual ao de entrada, sem `translationGeneration()` próprio.
///
/// ### Semântica confirmada no ARM DDI 0600A.d (não deduzida do layout de 32 bits — Armadilha 1)
/// - **Granule de 64 bytes**: `base = PRBAR_EL1 &amp; ~0x3F` (bits\[51:6\]), `limit = PRLAR_EL1 |
///   0x3F` (bits\[51:6\]) — **limite INCLUSIVO** até o último byte do bloco (`C1.3`: "ADDRESS is in
///   the protection region n if and only if `PRBAR&lt;n&gt;.BASE:'000000' &lt;= ADDRESS &lt;=
///   PRLAR&lt;n&gt;.LIMIT:'111111'`").
/// - Região desabilitada (`PRLAR_EL1.EN=0`, bit 0) é ignorada por inteiro.
/// - **Achado real que DIFERE do PMSAv8-32 (B20.7)**: a `Table C1-4` ("EL1 MPU fault types") do
///   DDI 0600A.d distingue 4 desfechos, e "múltiplas regiões casando" NÃO é `Permission fault`
///   como na versão de 32 bits — é `Translation fault` (mesmo código de "nenhuma região casou"):
///
///   | Casamento de região | Permissão | Resposta da MPU |
///   |---|---|---|
///   | Nenhuma | — | Translation fault |
///   | Múltiplas | — | Translation fault |
///   | Única | Negada | Permission fault |
///   | Única | Permitida | Válido |
///
///   PMSAv8-64 "Must not overlap" (`C1.1.2`) é comportamento definido como falha, mas com o código
///   de "sem tradução válida" (nível 0), não o de "permissão negada" — ver {@link
///   FaultStatus64#TRANSLATION_FAULT_L0}/{@link FaultStatus64#PERMISSION_FAULT_L0}.
/// - **`AP` ocupa bits\[3:2\] de `PRBAR_EL1`, código de 2 bits** (`DDI 0600A.d §G1.3.16`): `0b00`→
///   RW só EL1; `0b01`→RW EL1+EL0; `0b10`→RO só EL1; `0b11`→RO EL1+EL0 — MESMA tabela do PMSAv8-32
///   (`simple_ap_to_rw_prot_is_user` do QEMU, citado no Javadoc de {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav8AddressSpace}), só
///   a POSIÇÃO do campo muda (bits\[3:2\] aqui, bits\[2:1\] lá).
/// - **Execução**: permitida sse (leitura OU escrita permitida) E `XN=0` (`PRBAR_EL1\[1\]`) — mesma
///   regra do PMSAv8-32.
/// - **Região de fundo** (`SCTLR_EL1.BR`, `C1.4`): o DDI 0600A.d deixa o mapa padrão explicitamente
///   **IMPLEMENTATION DEFINED** ("the Armv8-R AArch64 architecture defines only the condition to
///   access the default memory map, but not the memory map itself") — ao contrário do PMSAv8-32,
///   que tem um mapa concreto confirmado no QEMU real (`get_phys_addr_pmsav7_default`). **Decisão
///   de implementação desta task** (não fonte normativa, documentada como tal): a região de fundo,
///   quando habilitada e em modo privilegiado, permite leitura/escrita mas **nunca execução** —
///   escolha conservadora diante de um mapa que a arquitetura recusa definir; candidata a revisão
///   se um hospedeiro real (QEMU não implementa `cortex-r82`/PMSAv8-64 nesta rodada, confirmado por
///   busca no código-fonte) surgir com uma convenção observável.
/// - Modo usuário nunca usa a região de fundo (mesma regra do PMSAv8-32).
public final class Pmsav8AddressSpace64 implements AddressSpace64 {

    // ── layout PRBAR_EL1 (DDI 0600A.d §G1.3.16) ─────────────────────────────────────
    private static final long PRBAR_XN_BIT = 0b10L;
    private static final int PRBAR_AP_SHIFT = 2;
    private static final long PRBAR_AP_MASK = 0b11L;
    /// Granule de 64 bytes: `BASE` são os bits\[51:6\] de `PRBAR_EL1`. Máscara usada tanto para
    /// isolar `BASE` quanto para completar `LIMIT`.
    private static final long GRANULE_MASK = 0x3FL;

    private final AddressSpace64 physical;
    private final Pmsav8SystemRegisters64 mpu;
    private boolean privileged = true;

    /// @param physical barramento físico por trás da checagem — nunca alterado (G3)
    /// @param mpu banco de regiões de EL1 (B20.8) que este wrapper consulta a cada acesso
    public Pmsav8AddressSpace64(AddressSpace64 physical, Pmsav8SystemRegisters64 mpu) {
        this.physical = Objects.requireNonNull(physical, "physical");
        this.mpu = Objects.requireNonNull(mpu, "mpu");
    }

    /// Define se os acessos seguintes são feitos em modo privilegiado (EL1) ou EL0 — espelha
    /// {@link TranslatingAddressSpace64#setPrivileged}. Sem gancho automático de transição de EL
    /// ainda (mesma lacuna já presente no precedente VMSA64 — nenhum chamador real liga isto hoje,
    /// candidata a task futura compartilhada entre os dois).
    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    @Override
    public int read8(long address) {
        checkAccess(address, MemoryAccessType.DATA_READ);
        return physical.read8(address);
    }

    @Override
    public int read16(long address) {
        checkAccess(address, MemoryAccessType.DATA_READ);
        return physical.read16(address);
    }

    @Override
    public int read32(long address) {
        checkAccess(address, MemoryAccessType.DATA_READ);
        return physical.read32(address);
    }

    @Override
    public void write8(long address, int value) {
        checkAccess(address, MemoryAccessType.DATA_WRITE);
        physical.write8(address, value);
    }

    @Override
    public void write16(long address, int value) {
        checkAccess(address, MemoryAccessType.DATA_WRITE);
        physical.write16(address, value);
    }

    @Override
    public void write32(long address, int value) {
        checkAccess(address, MemoryAccessType.DATA_WRITE);
        physical.write32(address, value);
    }

    /// **Ponto real de enforcement do fetch de instrução** — achado de leitura do precedente
    /// {@link TranslatingAddressSpace64#accessCycles}/{@code translateFetch}: `Aarch64Decoder#decode`
    /// só chama {@link #read32} (tipado como `DATA_READ`, sem distinção de fetch na interface
    /// {@link AddressSpace64} — ao contrário do mundo de 32 bits, que tem `fetch16`/`fetch32`
    /// próprios), e é `Ir64BlockExecutor#executeFetch` quem chama `accessCycles` com
    /// `INSTRUCTION_FETCH` ANTES do decode tocar a memória (ver o Javadoc de
    /// `Ir64BlockExecutor#step`). Este override é onde a checagem de `XN`/execução acontece de
    /// verdade — mesma disciplina do precedente VMSA64, espelhada aqui.
    @Override
    public int accessCycles(long address, int sizeBytes, MemoryAccessType type) {
        if (type == MemoryAccessType.INSTRUCTION_FETCH) {
            checkAccess(address, type);
        }
        return physical.accessCycles(address, sizeBytes, type);
    }

    @Override
    public boolean providesAccessCycles() {
        return physical.providesAccessCycles();
    }

    @Override
    public void notifyWrite(long address) {
        physical.notifyWrite(address);
    }

    /// Checagem por região (ver Javadoc da classe). `SCTLR_EL1.M=0` é bypass TOTAL, mesma decisão
    /// do precedente PMSAv8-32.
    private void checkAccess(long address, MemoryAccessType type) {
        if (!mpu.mpuEnabled()) {
            return;
        }
        int matchedRegion = -1;
        boolean multipleMatch = false;
        for (int region = mpu.regionCount() - 1; region >= 0; region--) {
            if (!mpu.regionEnabled(region)) {
                continue;
            }
            long base = mpu.base(region) & ~GRANULE_MASK;
            long limit = mpu.limit(region) | GRANULE_MASK;
            if (Long.compareUnsigned(address, base) < 0 || Long.compareUnsigned(address, limit) > 0) {
                continue; // endereço fora da faixa desta região
            }
            if (matchedRegion != -1) {
                // Achado real (Table C1-4 do DDI 0600A.d): "Multiple" é Translation fault, NÃO
                // Permission fault como no PMSAv8-32 — ver Javadoc da classe.
                multipleMatch = true;
                break;
            }
            matchedRegion = region;
        }
        if (multipleMatch) {
            throw new MemoryTranslationException64(address, type, FaultStatus64.TRANSLATION_FAULT_L0);
        }
        if (matchedRegion == -1) {
            checkBackground(address, type);
            return;
        }
        checkRegionPermission(mpu.base(matchedRegion), type, address);
    }

    private void checkRegionPermission(long prbar, MemoryAccessType type, long address) {
        long ap = (prbar >>> PRBAR_AP_SHIFT) & PRBAR_AP_MASK;
        boolean executeNever = (prbar & PRBAR_XN_BIT) != 0;
        boolean readOnly = (ap & 0b10) != 0;
        boolean userAlso = (ap & 0b01) != 0;
        boolean canRead = privileged || userAlso;
        boolean canWrite = canRead && !readOnly;
        boolean canExecute = (canRead || canWrite) && !executeNever;
        if (!accessAllowed(type, canRead, canWrite, canExecute)) {
            throw new MemoryTranslationException64(address, type, FaultStatus64.PERMISSION_FAULT_L0);
        }
    }

    /// Nenhuma região casou — "No match" da Table C1-4, sempre `Translation fault`, mesmo quando a
    /// causa real é "região de fundo indisponível" (modo usuário, ou `SCTLR_EL1.BR=0`).
    private void checkBackground(long address, MemoryAccessType type) {
        if (!privileged || !mpu.backgroundRegionEnabled()) {
            throw new MemoryTranslationException64(address, type, FaultStatus64.TRANSLATION_FAULT_L0);
        }
        // Decisão de implementação (ver Javadoc da classe): leitura/escrita sempre, execução NUNCA.
        boolean canExecute = false;
        if (!accessAllowed(type, true, true, canExecute)) {
            throw new MemoryTranslationException64(address, type, FaultStatus64.PERMISSION_FAULT_L0);
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
