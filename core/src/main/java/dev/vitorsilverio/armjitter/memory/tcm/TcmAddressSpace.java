package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// `AddressSpace` que sobrepõe até duas TCMs (`ATCM`/`BTCM`) do Cortex-R5 ao barramento externo —
/// B20.4, degrau de **modelo de memória** do épico do perfil R (`docs/COBERTURA-ISA.md` zero-diff:
/// nenhum encoding novo, `MCR`/`MRC` já decodificam). Mesmo padrão de wrapper de
/// `docs/RFC-SOFTMMU.md` decisão 1 e de {@link dev.vitorsilverio.armjitter.memory.mpu.PmsaAddressSpace}
/// (B20.3): a cada acesso, testa as TCMs habilitadas e delega ao `AddressSpace` de baixo quando
/// nenhuma casa.
///
/// ### Ordem de composição TCM × MPU (decisão de arquitetura da B20.4, Armadilha 2)
/// Confirmado na TRM real do Cortex-R5 (ARM DDI 0460D, §4.3.21 "c6, MPU memory region programming
/// registers", nota; e §8.4.1 "TCM attributes and permissions" — lidos via `curl`+`pdftotext` do
/// PDF real nesta sessão, mirror do Wayback Machine porque `developer.arm.com` é renderizado por
/// JavaScript):
/// > "When the MPU is enabled: The MPU determines the access permissions for **all** accesses to
/// > memory, **including the TCMs**. [...] For the TCM space the processor uses the access
/// > permissions but ignores the region attributes from MPU."
/// > "Accesses to the TCMs from the LSU and PFU are checked against the MPU for access permission."
///
/// Ou seja: a MPU decide permissão para TODO endereço, TCM incluída (só o TIPO de memória/atributo
/// de cache da região MPU é ignorado para endereços de TCM — este projeto não modela atributos de
/// cache, então essa parte é automática). A composição correta é **`PmsaAddressSpace` por FORA,
/// `TcmAddressSpace` por DENTRO**: `new PmsaAddressSpace(new TcmAddressSpace(fisico, ...), mpu)`.
/// A checagem de permissão acontece sempre (usando o endereço, igual a qualquer outro), e só
/// DEPOIS de passar a checagem o dado real é resolvido — TCM se o endereço cair numa região
/// habilitada, o barramento físico caso contrário. **Software real programa uma região de MPU
/// cobrindo a faixa de TCM antes de habilitá-la** (mesma TRM, §2 "Initialization... If the MPU is
/// enabled, before using the TCM interfaces you must program MPU regions to cover the TCM
/// regions"); sem isso, um `PmsaAddressSpace` com MPU habilitada nega o acesso (fault de fundo)
/// mesmo que a TCM em si esteja corretamente programada — comportamento correto, não um bug desta
/// classe.
///
/// ### Precedência entre TCMs sobrepostas (Armadilha 3)
/// A mesma TRM (§8.4, "Each TCM interface has a dedicated base address... The ATCM and BTCM
/// interfaces must have separate base addresses and **must not overlap**") deixa isto como
/// configuração inválida do guest — não um caso que o hardware real resolve de um jeito
/// específico. Escolha desta classe (mesmo espírito do QEMU real tratando `RSIZE` inválido como
/// "região ignorada" em vez de crashar): ATCM tem precedência sobre BTCM quando as duas (por erro
/// de programação do guest) cobrem o mesmo endereço — ordem arbitrária mas DETERMINÍSTICA e
/// documentada, nunca "quem o `if` testar primeiro" por acidente de refatoração.
///
/// ### Invalidação de JIT (item 5 do "Inclui" da B20.4)
/// Reprogramar/habilitar/desabilitar uma TCM muda o CONTEÚDO visto num mesmo endereço sem que
/// ninguém escreva nele — a primeira situação do projeto assim (Armadilha 6 da B20.3). Esta classe
/// bumpa um contador local a cada reconfiguração e soma ao {@link #translationGeneration()} do
/// barramento físico por baixo (que normalmente é `0`, perfil R não tem MMU — mas a soma continua
/// correta se algum hospedeiro futuro encadear TCM sobre um barramento com MMU própria): a soma de
/// dois contadores que só CRESCEM (nunca diminuem, nunca "resetam") é ela mesma estritamente
/// crescente a cada evento que muda qualquer um dos dois — suficiente para o `JitRuntime` nunca
/// reaproveitar um bloco compilado de antes da reconfiguração (mesmo mecanismo de
/// `BlockKey`/`translationGeneration` do B4.1.4, ver Javadoc de {@link AddressSpace#translationGeneration()}).
public final class TcmAddressSpace implements AddressSpace {
    private final AddressSpace physical;
    private final TcmRegion atcm;
    private final TcmRegion btcm;
    private int localGeneration;

    /// @param physical     barramento físico por trás da checagem — nunca alterado (G3)
    /// @param atcmMemory   armazenamento do hospedeiro para a ATCM (endereçado a partir de `0`)
    /// @param atcmSizeBytes tamanho FIXO da ATCM (ver Javadoc de {@link TcmRegion})
    /// @param btcmMemory   armazenamento do hospedeiro para a BTCM (endereçado a partir de `0`)
    /// @param btcmSizeBytes tamanho FIXO da BTCM (ver Javadoc de {@link TcmRegion})
    public TcmAddressSpace(AddressSpace physical, AddressSpace atcmMemory, int atcmSizeBytes,
                            AddressSpace btcmMemory, int btcmSizeBytes) {
        this.physical = Objects.requireNonNull(physical, "physical");
        this.atcm = new TcmRegion(Objects.requireNonNull(atcmMemory, "atcmMemory"), atcmSizeBytes);
        this.btcm = new TcmRegion(Objects.requireNonNull(btcmMemory, "btcmMemory"), btcmSizeBytes);
    }

    /// Reprograma a ATCM (efeito de `MCR p15,0,Rt,c9,c1,1` — ver Javadoc de {@link Cp15TcmCoprocessor})
    /// e bumpa a geração de tradução.
    public void reconfigureAtcm(int baseAddress, boolean enabled) {
        atcm.reconfigure(baseAddress, enabled);
        localGeneration++;
    }

    /// Reprograma a BTCM (efeito de `MCR p15,0,Rt,c9,c1,0`). Ver {@link #reconfigureAtcm}.
    public void reconfigureBtcm(int baseAddress, boolean enabled) {
        btcm.reconfigure(baseAddress, enabled);
        localGeneration++;
    }

    /// Estado cru da ATCM, para o `Cp15TcmCoprocessor` reler o que programou (`MRC`).
    public TcmRegion atcm() {
        return atcm;
    }

    /// Estado cru da BTCM, para o `Cp15TcmCoprocessor` reler o que programou (`MRC`).
    public TcmRegion btcm() {
        return btcm;
    }

    @Override
    public int read8(int address) {
        TcmRegion region = resolve(address);
        return region == null ? physical.read8(address) : region.memory().read8(address - region.baseAddress());
    }

    @Override
    public int read16(int address) {
        TcmRegion region = resolve(address);
        return region == null ? physical.read16(address) : region.memory().read16(address - region.baseAddress());
    }

    @Override
    public int read32(int address) {
        TcmRegion region = resolve(address);
        return region == null ? physical.read32(address) : region.memory().read32(address - region.baseAddress());
    }

    @Override
    public void write8(int address, int value) {
        TcmRegion region = resolve(address);
        if (region == null) {
            physical.write8(address, value);
        } else {
            region.memory().write8(address - region.baseAddress(), value);
        }
    }

    @Override
    public void write16(int address, int value) {
        TcmRegion region = resolve(address);
        if (region == null) {
            physical.write16(address, value);
        } else {
            region.memory().write16(address - region.baseAddress(), value);
        }
    }

    @Override
    public void write32(int address, int value) {
        TcmRegion region = resolve(address);
        if (region == null) {
            physical.write32(address, value);
        } else {
            region.memory().write32(address - region.baseAddress(), value);
        }
    }

    /// Busca de instrução: mesma resolução TCM-ou-físico que os dados — "Aceite" da B20.4 ("busca
    /// de instrução também vê a TCM"). Sobrescrita EXPLÍCITA (não confiar no default de {@link
    /// AddressSpace#fetch16}, que delegaria corretamente aqui por acidente hoje, mas é exatamente o
    /// tipo de encaminhamento esquecido que já causou um bug real neste projeto — ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace#fetch16}).
    @Override
    public int fetch16(int address) {
        TcmRegion region = resolve(address);
        return region == null ? physical.fetch16(address) : region.memory().read16(address - region.baseAddress());
    }

    @Override
    public int fetch32(int address) {
        TcmRegion region = resolve(address);
        return region == null ? physical.fetch32(address) : region.memory().read32(address - region.baseAddress());
    }

    /// Delega ao físico sempre — "Não inclui" da B20.4: latência/ciclos de TCM não são modelados
    /// (o ponto funcional real do TCM é justamente ser mais rápido, mas este projeto não modela
    /// waitstate de perfil R nenhum). Documentado como aproximação explícita.
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
        TcmRegion region = resolve(address);
        if (region == null) {
            physical.notifyWrite(address);
        } else {
            region.memory().notifyWrite(address - region.baseAddress());
        }
    }

    /// Soma a geração local (reprogramação de TCM) à do barramento físico por baixo — ver Javadoc
    /// da classe, seção "Invalidação de JIT".
    @Override
    public int translationGeneration() {
        return physical.translationGeneration() + localGeneration;
    }

    /// ATCM tem precedência sobre BTCM em caso de sobreposição indevida (Armadilha 3, ver Javadoc
    /// da classe) — devolve `null` quando nenhuma TCM habilitada cobre o endereço.
    private TcmRegion resolve(int address) {
        if (atcm.contains(address)) {
            return atcm;
        }
        if (btcm.contains(address)) {
            return btcm;
        }
        return null;
    }
}
