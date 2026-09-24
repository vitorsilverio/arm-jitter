package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.ModeChangeListener;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// `AddressSpace` que checa cada acesso contra as regiões da MPU **PMSAv7** (B20.2,
/// {@link Pmsav7MpuRegisters}), envolvendo um `AddressSpace` físico existente sem alterá-lo
/// (RFC-SOFTMMU decisão 1: checagem em wrapper, não inline no JIT — mesmo precedente de
/// {@link dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace}). **Não traduz**: PMSA
/// não tem espaço de endereço virtual, o endereço de saída é sempre igual ao de entrada — sem
/// page-walk, sem TLB, sem {@link #translationGeneration()} próprio (default `0`, ver Javadoc de
/// {@link AddressSpace#translationGeneration()}: nenhuma reprogramação de MPU muda o CONTEÚDO
/// visto num mesmo endereço, então um bloco JIT compilado continua válido).
///
/// ### Semântica confirmada contra o QEMU real (`target/arm/ptw.c`, `get_phys_addr_pmsav7` e
/// `get_phys_addr_pmsav7_default`, lido via `curl` nesta sessão — não parafraseado)
/// - **Prioridade invertida**: o laço varre do índice de região MAIOR para o MENOR e para no
///   PRIMEIRO acerto (armadilha nº 1 do épico B20).
/// - Região desabilitada (`DRSR.EN=0`) ou com `RSIZE` inválido (`< 1` — **não `< 4`**: o `rsize_min`
///   de `4` do código real é só para perfil **M**; perfil **R**, o nosso alvo, usa `rsize_min = 1`,
///   correção do que o `## Resultado` da B20.2 havia registrado) é ignorada por inteiro.
///   `DRBAR` desalinhado ao tamanho da região também é ignorada por inteiro (mesmo tratamento de
///   erro de guest do QEMU real).
/// - Faixa: `size = 2^(RSIZE+1)` bytes, comparação **sem sinal** (`base <= address <= base+size-1`).
/// - Sub-regiões só existem quando `size >= 256` bytes (`RSIZE final >= 8`); o índice é
///   `((address - base) >>> (RSIZE final - 3)) & 0x7` — **NÃO** `(address >>> ...)` (armadilha nº 2
///   do épico: a expressão usa o deslocamento DENTRO da região, não o endereço bruto — confirmado
///   lendo `get_phys_addr_pmsav7` linha a linha). Sub-região desabilitada não conta como acerto
///   (a busca continua para a região de índice menor).
/// - Sem acerto: `BACKGROUND` fault, **exceto** em modo privilegiado com `SCTLR.BR=1` — modo
///   usuário NUNCA usa a região de fundo. O mapa de fundo (perfil não-M) dá leitura+escrita SEMPRE
///   e execução só no meio inferior do espaço de endereço (`address>=0` como `int` teria o bit 31
///   zerado); a metade superior de vetores altos (`0xF0000000..0xFFFFFFFF`, condicionada a
///   `SCTLR.V`) é uma simplificação NOMEADA desta task — ver "Não inclui".
/// - Acerto de região: permissão vem de `AP[2:0]`/`XN` de `DRACR` — tabela CONFIRMADA no `switch`
///   real do QEMU (armadilha nº 3 do épico: a tabela "leitura desta spec" original estava errada
///   para `AP=7`, reservado no perfil R, não "RO"; e leitura sempre vem acompanhada de capacidade de
///   execução, só negada depois por `XN`, nunca concedida "READ sem EXEC" pela própria tabela).
public final class PmsaAddressSpace implements AddressSpace, ModeChangeListener {

    // ── layout DRSR (ARM DDI 0406C, "c6,c1,2") ──────────────────────────────────────
    private static final int DRSR_ENABLE_BIT = 0b1;
    private static final int DRSR_RSIZE_SHIFT = 1;
    private static final int DRSR_RSIZE_MASK = 0b1_1111;
    private static final int DRSR_SUBREGION_DISABLE_SHIFT = 8;

    // ── layout DRACR (ARM DDI 0406C, "c6,c1,4") ─────────────────────────────────────
    private static final int DRACR_AP_SHIFT = 8;
    private static final int DRACR_AP_MASK = 0b111;
    private static final int DRACR_XN_BIT = 1 << 12;

    /// `rsize_min` do QEMU real para core que NÃO é perfil M (`get_phys_addr_pmsav7`: a variável
    /// só sobe para 4 ou 7 dentro do `if (arm_feature(env, ARM_FEATURE_M))`) — perfil R usa `1`.
    private static final int RSIZE_MIN_NON_M_PROFILE = 1;
    /// Sub-regiões só existem quando o tamanho final da região é `>= 2^8` (256 bytes) — comentário
    /// literal do QEMU real: "no subregions for regions < 256 bytes".
    private static final int MIN_RSIZE_WITH_SUBREGIONS = 8;
    private static final int SUBREGION_COUNT = 8;
    private static final int SUBREGION_INDEX_MASK = SUBREGION_COUNT - 1;
    /// Deslocamento de `AP`/`SRD` para `RSIZE` em sub-região: `subRsize = rsize - SUBREGION_SIZE_SHIFT_DELTA`.
    private static final int SUBREGION_SIZE_SHIFT_DELTA = 3;

    /// Mapa de fundo (perfil não-M): metade inferior do espaço de 32 bits é sempre executável —
    /// equivale a `address >= 0` tratando `address` como `int` comum (bit 31 zerado).
    private static final int LOW_HALF_SIGN_BIT = 0x8000_0000;

    /// Tabela `AP[2:0]` REAL (QEMU `get_phys_addr_pmsav7`, ramo "Priv. mode AP bits decoding"):
    /// `AP=4` e `AP=7` são reservados no perfil R (não M) — sem acesso algum, ao contrário do que a
    /// tabela "leitura desta spec" da B20.3 supunha para `AP=7`.
    private static final boolean[] PRIVILEGED_READ = {false, true, true, true, false, true, true, false};
    private static final boolean[] PRIVILEGED_WRITE = {false, true, true, true, false, false, false, false};
    /// Tabela `AP[2:0]` REAL (ramo "User mode AP bit decoding"): mesma reserva de `AP=4`/`AP=7`.
    private static final boolean[] USER_READ = {false, false, true, true, false, false, true, false};
    private static final boolean[] USER_WRITE = {false, false, false, true, false, false, false, false};

    private final AddressSpace physical;
    private final Pmsav7MpuRegisters mpu;
    private boolean privileged = true;

    /// @param physical barramento físico por trás da checagem — nunca alterado (G3)
    /// @param mpu banco de regiões (B20.2) que este wrapper consulta a cada acesso
    public PmsaAddressSpace(AddressSpace physical, Pmsav7MpuRegisters mpu) {
        this.physical = Objects.requireNonNull(physical, "physical");
        this.mpu = Objects.requireNonNull(mpu, "mpu");
    }

    /// Define se os acessos seguintes são feitos em modo privilegiado (qualquer modo exceto
    /// `USER`) ou usuário — os bits `AP` tratam os dois casos de forma diferente, e a região de
    /// fundo nunca vale para usuário. Espelha {@link
    /// dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace#setPrivileged}; quem
    /// sincroniza a partir de troca de modo do core é este `PmsaAddressSpace`
    /// (`ModeChangeListener`), não o `Pmsav7MpuCoprocessor` — decisão registrada no `## Resultado`.
    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    /// Implementação de {@link ModeChangeListener}: o host registra
    /// `core.setModeChangeListener(pmsaAddressSpace)` (gancho independente de
    /// `core.setCoprocessorBus(pmsav7MpuCoprocessor)`) para que toda troca de modo atualize
    /// {@link #setPrivileged} automaticamente — mesmo papel que `Cp15VmsaCoprocessor` cumpre para
    /// VMSA, só que aqui vive no `AddressSpace`, não no `CoprocessorBus`, porque é este objeto (não
    /// o banco de registradores) que precisa do estado de privilégio para decidir permissão.
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

    /// Checagem por região (ver Javadoc da classe). `SCTLR.M=0` é bypass TOTAL — nenhuma checagem,
    /// nem mesmo o mapa de fundo que o QEMU real ainda aplicaria com a MPU desligada; decisão
    /// explícita desta task (Aceite): mais simples e suficiente para todo software que desliga a
    /// MPU esperando memória plana.
    private void checkAccess(int address, MemoryAccessType type) {
        if (!mpu.mpuEnabled()) {
            return;
        }
        for (int region = mpu.regionCount() - 1; region >= 0; region--) {
            int drsr = mpu.sizeRegister(region);
            if ((drsr & DRSR_ENABLE_BIT) == 0) {
                continue;
            }
            int rawRsize = (drsr >>> DRSR_RSIZE_SHIFT) & DRSR_RSIZE_MASK;
            if (rawRsize < RSIZE_MIN_NON_M_PROFILE) {
                continue;
            }
            int rsize = rawRsize + 1; // tamanho real = 2^rsize bytes
            int regionMask = (int) ((1L << rsize) - 1);
            int base = mpu.base(region);
            if ((base & regionMask) != 0) {
                continue; // DRBAR desalinhado ao tamanho da região (erro de guest, QEMU real)
            }
            if (Integer.compareUnsigned(address, base) < 0
                    || Integer.compareUnsigned(address, base + regionMask) > 0) {
                continue; // endereço fora da faixa desta região
            }
            if (rsize >= MIN_RSIZE_WITH_SUBREGIONS) {
                int subRsize = rsize - SUBREGION_SIZE_SHIFT_DELTA;
                int subRegionIndex = ((address - base) >>> subRsize) & SUBREGION_INDEX_MASK;
                boolean subRegionDisabled =
                        ((drsr >>> (DRSR_SUBREGION_DISABLE_SHIFT + subRegionIndex)) & 1) != 0;
                if (subRegionDisabled) {
                    continue; // sub-região desabilitada: não conta como acerto
                }
            }
            checkRegionPermission(mpu.accessControl(region), type, address);
            return;
        }
        checkBackground(address, type);
    }

    private void checkRegionPermission(int dracr, MemoryAccessType type, int address) {
        int ap = (dracr >>> DRACR_AP_SHIFT) & DRACR_AP_MASK;
        boolean executeNever = (dracr & DRACR_XN_BIT) != 0;
        boolean canRead = (privileged ? PRIVILEGED_READ : USER_READ)[ap];
        boolean canWrite = (privileged ? PRIVILEGED_WRITE : USER_WRITE)[ap];
        boolean canExecute = canRead && !executeNever;
        if (!accessAllowed(type, canRead, canWrite, canExecute)) {
            throw new PmsaAccessException(address, type, PmsaFaultStatus.PERMISSION);
        }
    }

    /// Nenhuma região casou. Modo usuário nunca usa a região de fundo (QEMU real,
    /// `pmsav7_use_background_region`: `if (is_user) return false;`), então vira `BACKGROUND`
    /// fault direto, mesmo com `SCTLR.BR=1`.
    private void checkBackground(int address, MemoryAccessType type) {
        if (!privileged || !mpu.backgroundRegionEnabled()) {
            throw new PmsaAccessException(address, type, PmsaFaultStatus.BACKGROUND);
        }
        // Mapa de fundo padrão (perfil não-M): leitura+escrita sempre; execução só na metade
        // inferior do espaço de endereço — a metade de vetores altos (0xF0000000+, condicionada a
        // SCTLR.V no QEMU real) fica de fora aqui, simplificação nomeada (ver Javadoc da classe).
        boolean canExecute = (address & LOW_HALF_SIGN_BIT) == 0;
        if (!accessAllowed(type, true, true, canExecute)) {
            throw new PmsaAccessException(address, type, PmsaFaultStatus.PERMISSION);
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
