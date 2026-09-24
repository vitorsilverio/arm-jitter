package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Objects;

/// Estado de uma TCM (`ATCM` ou `BTCM`) do Cortex-R5 (ARM DDI 0460D, capítulo 4.3.23/4.3.24 "c9,
/// BTCM/ATCM Region Register", lido via `curl`+`pdftotext` nesta sessão a partir do PDF real da
/// TRM — não parafraseado): {@link #baseAddress}/{@link #enabled} são o subconjunto do registrador
/// de região que o SOFTWARE controla (ver {@link TcmAddressSpace} para quem programa isto a partir
/// do valor bruto de 32 bits do `MCR`); {@link #sizeBytes} é **fixo, decidido pelo hospedeiro na
/// construção** — a TRM real é explícita: *"\[6:2\] Size: Indicates the size of the BTCM on reads.
/// **On writes this field is ignored**"* — o campo `Size` reporta o tamanho de hardware
/// (implementação), o guest nunca o escolhe, só lê. `memory` é o armazenamento real da TCM — **do
/// hospedeiro** (mesma divisão do resto do projeto: a lib não aloca memória do dispositivo),
/// endereçado a partir de `0` (offset relativo à região, nunca o endereço de barramento bruto).
///
/// Puro armazenamento de estado — **nenhuma checagem de sobreposição/decisão de prioridade
/// acontece aqui**; é {@link TcmAddressSpace} quem varre as duas regiões a cada acesso (mesma
/// divisão de responsabilidade de {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav7MpuRegisters}
/// vs. {@link dev.vitorsilverio.armjitter.memory.mpu.PmsaAddressSpace}).
public final class TcmRegion {
    private final AddressSpace memory;
    private final int sizeBytes;
    private int baseAddress;
    private boolean enabled;

    /// @param memory    armazenamento do hospedeiro que respalda esta TCM — endereçado a partir de
    ///                   `0` (offset relativo ao {@link #baseAddress} corrente, nunca o endereço de
    ///                   barramento bruto que o guest vê)
    /// @param sizeBytes  tamanho FIXO desta TCM (config de hardware, não programável pelo guest) —
    ///                   `0` ("sem TCM") ou uma das 13 potências de dois de
    ///                   {@link TcmSizeField#decodeBytes} (4KB..8MB); ver
    ///                   {@link TcmSizeField#encodeRawCode} para a validação
    public TcmRegion(AddressSpace memory, int sizeBytes) {
        this.memory = Objects.requireNonNull(memory, "memory");
        TcmSizeField.encodeRawCode(sizeBytes); // valida (lança se não for um tamanho real da tabela)
        this.sizeBytes = sizeBytes;
    }

    /// Endereço de barramento onde esta TCM aparece sobrepondo o barramento externo (campo `Base`
    /// do registrador de região, bits `[31:12]`) — programável pelo guest.
    public int baseAddress() {
        return baseAddress;
    }

    /// Tamanho em bytes da faixa que esta TCM ocupa — FIXO, decidido pelo hospedeiro na
    /// construção (ver Javadoc da classe: o campo `Size` do registrador real é só leitura).
    public int sizeBytes() {
        return sizeBytes;
    }

    /// Bit `Enable` do registrador de região — `false` deixa esta TCM totalmente transparente
    /// (nenhum acesso é redirecionado, mesmo que {@link #baseAddress} tenha um valor de antes).
    public boolean enabled() {
        return enabled;
    }

    /// Reprograma a base/habilitação desta TCM (o efeito de um `MCR p15,0,Rt,c9,c1,{0,1}`
    /// decomposto por {@link TcmAddressSpace}) — o campo `Size` NUNCA muda aqui (é fixo, ver
    /// Javadoc da classe). Não decide invalidação de JIT — quem chama isto é responsável por
    /// notificar (ver {@link TcmAddressSpace#reconfigureAtcm}/{@link TcmAddressSpace#reconfigureBtcm}).
    void reconfigure(int baseAddress, boolean enabled) {
        this.baseAddress = baseAddress;
        this.enabled = enabled;
    }

    /// Se o endereço de barramento fornecido cai dentro da faixa desta TCM **e** ela está
    /// habilitada. Comparação sem sinal (mesmo padrão de {@link
    /// dev.vitorsilverio.armjitter.memory.mpu.PmsaAddressSpace}), porque `baseAddress+sizeBytes`
    /// pode passar de `0x8000_0000` como `int` sem que isso signifique "endereço negativo".
    boolean contains(int address) {
        if (!enabled || sizeBytes == 0) {
            return false;
        }
        int offset = address - baseAddress;
        return Integer.compareUnsigned(offset, sizeBytes) < 0;
    }

    /// Barramento de armazenamento real desta TCM, para {@link TcmAddressSpace} ler/escrever no
    /// offset relativo (`address - baseAddress`).
    AddressSpace memory() {
        return memory;
    }
}
