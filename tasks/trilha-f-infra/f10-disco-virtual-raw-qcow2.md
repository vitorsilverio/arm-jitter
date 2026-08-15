# F10 — Disco virtual no `virtual-arm-box`: `raw` + **QCOW2** (leitura e escrita) + PL181 MMCI

**Trilha:** F (infra) · **Depende de:** F2 · **Repo:** virtual-arm-box
**Decidido pelo usuário em 2026-08-15.**
**Task LONGA (2–3 sessões).** Fatie em PRs: **PR1** = `DiskImage` + `raw` + PL181/SD (fecha o
marco de boot com raiz em disco) · **PR2** = QCOW2 leitura · **PR3** = QCOW2 escrita.
Cada PR fecha sozinho e é commitável.

## Contexto

Pedido do usuário: *"o virtual-arm-box vai usar algum tipo de disco virtual padrão compatível
com outras VMs"*.

Hoje o repo **não tem dispositivo de bloco nenhum**: o `versatilepb` recebe kernel e
initramfs carregados direto na RAM pelo host, e a raiz do sistema é um `initramfs.cpio.gz`
descomprimido pelo próprio kernel. Não existe disco, não existe persistência entre execuções.

Isso limita o projeto exatamente onde ele quer crescer: uma máquina virtual "estilo
VirtualBox" precisa de disco, e precisa de um disco que **outras VMs consigam ler**.

## Decisões (tomadas pelo usuário, **não reabrir**)

- **Formatos: `raw` e `QCOW2`, ambos com leitura E escrita.**
  `raw` é o denominador comum (todo hipervisor lê; `dd` e loop-mount funcionam). **QCOW2** é o
  padrão de fato do QEMU/libvirt: esparso, com snapshots e *backing files*, e com
  especificação pública. Com esses dois, `qemu-img convert` cobre VDI, VMDK e VHD/VHDX sem
  escrevermos uma linha para eles — e é isso que "compatível com outras VMs" significa na
  prática.
- **Primeiro periférico: PL181 MMCI (SD/MMC) no `versatilepb`**, que é o controlador de bloco
  que a placa real tem e para o qual o kernel Debian 3.2 já em uso traz o driver
  `mmci-pl18x`. Marco: montar a raiz de um `.img` em `/dev/mmcblk0`, **sem initramfs**.

## Ferramenta que muda tudo nesta task (achado de 2026-08-15)

**O QEMU 8.0.0 está instalado nesta máquina**, em `C:\Program Files\qemu\` — só binários, sem
código-fonte. Isso dá **três oráculos externos** que esta task deve usar de forma agressiva:

| Binário | Uso nesta task |
|---------|----------------|
| `qemu-img.exe` | `create`, `convert`, **`check`** (valida a estrutura de uma imagem QCOW2 que **nós** escrevemos), `info` |
| `qemu-io.exe` | lê/escreve setores arbitrários numa imagem QCOW2 — o **oráculo diferencial**: mesma sequência de escritas nossa e dele, imagens comparadas |
| `qemu-system-arm.exe` | boota o **mesmo** `versatilepb` com o **mesmo** kernel e o **mesmo** disco — A/B direto quando algo divergir |

**`qemu-img check` passando numa imagem escrita pelo nosso código é o aceite mais forte do
PR3.** Não invente um validador próprio quando existe o de referência instalado.

## Inclui

**PR1** — `disk/DiskImage` (interface), `disk/RawDiskImage`, `device/SdCard`,
`device/Pl181Mmci`, opção `--disk=<arquivo>` no `Main`, boot com raiz em disco.
**PR2** — `disk/qcow2/` com abertura e leitura (incluindo *backing file* e snapshots
presentes, em modo somente-leitura).
**PR3** — escrita em QCOW2: alocação de cluster, tabela de refcount, atualização de L2.

## NÃO inclui (não fazer)

- **Sem VDI, VMDK, VHD ou VHDX.** A interoperabilidade com eles é por `qemu-img convert`, e
  isso é **documentação**, não código. Se algum dia doer, vira task própria.
- **Sem compressão QCOW2 na escrita.** Cluster comprimido encontrado na **leitura**: ou
  descomprima (`Inflater` do Java, é deflate cru) ou recuse com erro claro — escolha e
  documente. Nunca escreva cluster comprimido.
- **Sem criar snapshots internos** e **sem escrever em imagem que tenha snapshot ou backing
  file** (ver "fronteira de segurança" abaixo).
- Sem criptografia (`crypt_method != 0` → recusar abertura, com mensagem clara).
- Sem *external data file*, sem *extended L2 entries*.
- Sem disco no `raspi1` (é a F3; quando ela chegar, reusa `DiskImage` e escreve o EMMC dela).
- Sem interface gráfica de gerenciamento de disco.

## Especificação

### `DiskImage`

```java
/// Uma imagem de disco virtual, endereçada por setor de 512 bytes.
///
/// As implementações são o contrato de compatibilidade do `virtual-arm-box` com outras VMs:
/// `raw` (lido por qualquer hipervisor) e QCOW2 (o formato do QEMU/libvirt). Outros formatos
/// — VDI, VMDK, VHD/VHDX — são atendidos por conversão externa (`qemu-img convert`), não por
/// implementações desta interface.
public interface DiskImage extends AutoCloseable {

    /// Tamanho lógico do disco, em setores de {@link #SECTOR_BYTES} bytes.
    long sectorCount();

    /// Lê `count` setores a partir de `firstSector` para `destination`, no deslocamento dado.
    void readSectors(long firstSector, int count, byte[] destination, int destinationOffset);

    /// Escreve `count` setores. Lança se a imagem foi aberta somente para leitura.
    void writeSectors(long firstSector, int count, byte[] source, int sourceOffset);

    /// Se a imagem aceita escrita — ver a fronteira de segurança documentada na task F10
    /// (imagens QCOW2 com snapshot interno ou *backing file* abrem somente para leitura).
    boolean writable();

    /// Garante que toda escrita pendente chegou ao arquivo do host.
    void flush();

    int SECTOR_BYTES = 512;
}
```

Um `DiskImages.open(Path, boolean readOnly)` detecta o formato pelo **magic** (`QFI\xFB` =
QCOW2; qualquer outra coisa = `raw`), nunca pela extensão do arquivo.

### `RawDiskImage`

`RandomAccessFile`/`FileChannel`. `sectorCount = tamanho do arquivo / 512`. Arquivo com
tamanho não múltiplo de 512: **recusar**, com mensagem clara. Escrita além do fim: recusar
(disco não cresce sozinho).

### QCOW2 — o que precisa ser lido de verdade

Especificação: `docs/interop/qcow2.txt` do QEMU (publicada em
`https://gitlab.com/qemu-project/qemu/-/blob/master/docs/interop/qcow2.txt`). **Leia-a.** Não
escreva o parser de memória — o formato tem detalhes (ordem big-endian em tudo, bits de flag
no topo dos ponteiros) que não se adivinha.

Resumo do que a implementação precisa cobrir:

- **Cabeçalho** (big-endian): magic, `version` (**suportar 2 e 3**), `backing_file_offset`/
  `_size`, `cluster_bits` (padrão 16 → clusters de 64 KiB; aceitar 9..21), `size` (tamanho
  lógico), `crypt_method`, `l1_size`, `l1_table_offset`, `refcount_table_offset`,
  `refcount_table_clusters`, `nb_snapshots`, `snapshots_offset`. Na v3 ainda:
  `incompatible_features`, `compatible_features`, `autoclear_features`, `refcount_order`,
  `header_length`.
- **Regra da v3 que não pode ser ignorada:** qualquer bit ligado em `incompatible_features`
  que a nossa implementação não conheça obriga a **recusar a abertura**. É assim que o formato
  se protege; ignorar bits desconhecidos corrompe imagem alheia.
- **Tradução de endereço** (dois níveis): do deslocamento do guest saem `l1_index` e
  `l2_index`; a entrada de L1 aponta para uma tabela L2; a entrada de L2 dá o deslocamento do
  cluster no arquivo. **Bit 63 = "copied"**, **bit 62 = "compressed"**; os bits de endereço
  precisam ser mascarados antes do uso. Entrada zerada = cluster não alocado → ler zeros (ou
  ler do *backing file*, se houver).
- **Tabela de refcount** (também dois níveis: refcount table → refcount blocks). Só importa de
  verdade no PR3, mas leia-a já no PR2 para poder validar.

### Fronteira de segurança da escrita (decisão desta task)

Escrever num cluster cujo **refcount > 1** exige *copy-on-write* — é o mecanismo que faz
snapshots internos e backing files funcionarem, e é onde se corrompe imagem de terceiro sem
perceber. Em vez de implementar COW mal:

> **Uma imagem QCOW2 com `nb_snapshots > 0` ou com *backing file* abre SOMENTE PARA LEITURA.**
> A tentativa de abrir para escrita falha com mensagem explicando o motivo e sugerindo
> `qemu-img convert`.

Isso mantém o PR3 honesto e pequeno. COW real fica registrado como possível task futura.

### Escrita em QCOW2 (PR3), passo a passo

Para um cluster ainda não alocado:
1. Alocar espaço no fim do arquivo, alinhado ao cluster.
2. **Atualizar a tabela de refcount ANTES** de publicar o ponteiro (refcount = 1).
3. Escrever os dados do cluster.
4. Escrever a entrada de L2 com o bit `copied` ligado (alocando a tabela L2 antes, se a
   entrada de L1 estiver zerada — a tabela L2 também consome um cluster e também precisa de
   refcount).
5. `flush` na ordem certa.

Escrita parcial de cluster (o caso comum: 512 bytes num cluster de 64 KiB) num cluster novo
exige **preencher o resto com zeros** — senão sobra lixo do arquivo.

### `SdCard` e `Pl181Mmci`

O PL181 é o controlador; o cartão SD é um dispositivo separado que responde a comandos
(`CMD0` GO_IDLE, `CMD8`, `ACMD41`, `CMD2`/`CMD3` identificação, `CMD9` CSD, `CMD7` seleção,
`CMD16` tamanho de bloco, `CMD17`/`CMD18` leitura simples/múltipla, `CMD24`/`CMD25` escrita).
Modele os dois separados (`device/SdCard` + `device/Pl181Mmci`), como o QEMU faz — misturar os
dois numa classe torna o `raspi1` (F3) mais difícil, já que lá o controlador é outro e o
cartão é o mesmo.

**Fonte:** `hw/sd/pl181.c` e `hw/sd/sd.c` do QEMU, mais a *SD Physical Layer Simplified
Specification* (pública). O código-fonte do QEMU **não está local** (só os binários) — obtenha
os dois arquivos do repositório público do projeto antes de escrever o periférico, do mesmo
jeito que PL011/SP804/PL190 foram transcritos na B4.1.5.

Registrar o PL181 em `0x1000_0000`... **não presuma**: confira a base real do MMCI no
`versatilepb` (`hw/arm/versatilepb.c`) e o ID de IRQ no VIC. O mapa que já está no
`VersatilePbMachine` é o modelo de como registrar.

### CLI

```
virtual-arm-box [--machine=versatilepb] [--disk=<arquivo>] [--disk-read-only]
                [--interp|--check] [--cycles=N] <zImage> [initramfs.gz] ["cmdline"]
```

Com `--disk` e sem initramfs, a cmdline padrão passa a ser
`console=ttyAMA0 root=/dev/mmcblk0 rw`. O initramfs vira opcional (hoje é obrigatório).

## Passos

1. **PR1:** `DiskImage` + `RawDiskImage` + testes; `SdCard` + `Pl181Mmci` + testes de unidade
   por comando; `--disk`; imagem de teste em `testdata/` (ver "corpus" abaixo); marco de boot.
2. **PR2:** QCOW2 leitura. Gere as imagens de teste com `qemu-img create -f qcow2` e
   `qemu-img convert`, populadas por `qemu-io`; o teste lê com o nosso código e compara com o
   conteúdo esperado byte a byte.
3. **PR3:** QCOW2 escrita, com o ciclo de validação descrito no aceite.

### Corpus de teste (`testdata/disk/`)

Uma imagem de raiz pequena (dezenas de MiB) com um sistema de arquivos ext2/ext4 contendo o
`busybox-armv5l` que o repo **já versiona**, montável pelo kernel em uso. Documente em
`testdata/disk/README.md` o comando exato que a produziu (proveniência reprodutível — mesmo
padrão do `testdata/README.md` atual).

Se não houver como montar/popular um ext2 nesta máquina (Windows, sem WSL), **PARE e reporte**
antes de gastar a sessão: uma alternativa é gerar a imagem com `genext2fs`/`mke2fs` do MSYS2
(`C:\devkitPro\msys2`), se existirem lá.

## Aceite

**PR1**
- [ ] `mvn -o test` verde; `VersatilePbBootTest` continua verde.
- [ ] Testes de unidade do `SdCard` (cada comando implementado) e do `Pl181Mmci` (FIFO,
      registradores de status, IRQ).
- [ ] **Marco:** o kernel monta a raiz de `testdata/disk/rootfs.img` em `/dev/mmcblk0`, **sem
      initramfs**, e chega ao shell `busybox` interativo — nos backends JIT e interpretado,
      com a mesma técnica de digitação byte a byte do `VersatilePbBootTest`.
- [ ] Escrita persiste: o teste escreve um arquivo no guest, o guest é desligado, uma segunda
      execução lê o arquivo de volta.

**PR2**
- [ ] Lê imagens QCOW2 v2 **e** v3 criadas por `qemu-img`, com clusters de 64 KiB e de pelo
      menos mais um tamanho.
- [ ] Cluster não alocado lê zeros; com *backing file*, lê do backing.
- [ ] Imagem criptografada, com `incompatible_features` desconhecido, ou com *extended L2*:
      **recusa a abertura com mensagem clara** (teste negativo para cada caso).
- [ ] Imagem com snapshot ou backing file abre, mas `writable()` devolve `false`.

**PR3**
- [ ] **`qemu-img check` sem erro** numa imagem que o nosso código criou e populou. *(Este é o
      aceite central do PR3.)*
- [ ] **Round-trip cruzado:** escrevemos com o nosso código → `qemu-io` lê e confere; `qemu-io`
      escreve → o nosso código lê e confere.
- [ ] **`qemu-img convert -f qcow2 -O raw`** da nossa imagem produz exatamente o mesmo
      conteúdo que a nossa leitura devolve.
- [ ] Escrita esparsa real: escrever 1 setor numa imagem de 1 GiB deixa o arquivo com poucos
      MiB, não 1 GiB.
- [ ] O boot do PR1 funciona também com a raiz numa imagem QCOW2 (`--disk=rootfs.qcow2`).

**Geral**
- [ ] `README.md` do virtual-arm-box com uma seção `## Discos` explicando os dois formatos e
      dando os comandos de `qemu-img convert` para VDI/VMDK/VHD.
- [ ] `ROADMAP.md` do virtual-arm-box atualizado.
- [ ] Índice do `tasks/README.md` atualizado (F10, com o PR alcançado).

## Validação

`mvn -o test` no virtual-arm-box. Nenhum outro repo depende dele — G5 não se aplica, salvo se
um bug do arm-jitter aparecer (aí: correção em commit separado lá, teste de regressão, e
gbaemu+ndsemu+armbox revalidados).

## Armadilhas

- **QCOW2 é big-endian em tudo**, inclusive nos campos de 64 bits. Java lê big-endian por
  padrão em `DataInputStream`/`ByteBuffer`, mas confira — um campo lido invertido produz
  deslocamentos absurdos que parecem corrupção de imagem.
- **Bits 63 e 62 das entradas de L2 fazem parte do valor lido.** Usar a entrada como
  deslocamento sem mascarar aponta para o fim do universo. Mesma armadilha nas entradas de L1.
- **Ordem de escrita importa.** Publicar o ponteiro de L2 antes de o refcount estar gravado
  produz uma imagem que `qemu-img check` reprova (e que corrompe se a execução for
  interrompida no meio). Refcount primeiro, sempre.
- **Nunca escreva numa imagem de terceiro sem cópia.** Ao testar contra imagens geradas pelo
  `qemu-img`, copie antes. Um bug do PR3 numa imagem original destrói o corpus.
- O PL181 tem FIFO de 16 palavras; o driver do kernel lê/escreve em rajada e consulta os bits
  de status. Um `TXFIFOEMPTY`/`RXFIFOFULL` errado trava o driver em espera — **o mesmo tipo de
  armadilha do FIFO de 16 posições do PL011** que já custou uma rodada na B4.1.5.
- `qemu-system-arm.exe` está instalado: se o boot com disco travar e você não souber se o bug é
  do PL181 ou da imagem, **boote a mesma imagem no QEMU** com o mesmo kernel e compare. É a
  ferramenta mais barata desta task inteira — use antes de depurar às cegas.
