# F2 — `linuxbox` → `virtual-arm-box`: rename + abstração `Machine`

**Trilha:** F (infra) · **Depende de:** F1 · **Repo:** linuxbox (→ virtual-arm-box) + arm-jitter (só docs)
**Decidido pelo usuário em 2026-08-15.**

## Contexto

O repo `linuxbox` nasceu como hospedeiro da task B4.1.5 (épico B4.1/MMU-softmmu) e hoje
boota um kernel Linux real até shell `busybox` interativo nos backends INTERPRETED e JIT.
O nome ficou estreito demais para a intenção do usuário:

> "linuxbox deve ser renomeado para virtual-arm-box, a ideia é que ele não rode apenas
> linux, mas windows, mac, emule raspberry pi, android — igual o VirtualBox consegue emular
> vários dispositivos"

O rename sozinho seria cosmético. Esta task faz o rename **e** a mudança estrutural mínima
que dá sentido a ele: uma abstração `Machine` com registro por nome e seleção por CLI
(`--machine=versatilepb`), exatamente como o `armbox` já faz com `--machine=cortex-m`. Com
isso a task F3 (Raspberry Pi 1) vira "implementar mais uma `Machine`", e não um refactor.

**Decisão do usuário (2026-08-15):** `armbox` e `virtual-arm-box` continuam **repos
separados**. `armbox` = runner **user-mode** (um binário ELF, syscalls Linux em HLE, sem MMU
de sistema). `virtual-arm-box` = emulador de **máquina completa** (CPU + MMU + periféricos +
boot de kernel/firmware). Não fundir, não mover código de um para o outro.

**Decisão do usuário (2026-08-15):** **não** criar repositório no GitHub para o
`virtual-arm-box` nesta task. O repo continua só local (ele hoje não tem `remote` nenhum).
Não rodar `git remote add`, não rodar `gh repo create`.

## Objetivo

O diretório, o artefato Maven, o pacote Java, a classe `Main`, o README e todas as
referências em documentação passam a dizer `virtual-arm-box`; e o host ganha a interface
`Machine` + seleção por `--machine=`, com `versatilepb` como única implementação e default.

## Inclui

1. Rename do diretório `C:\Users\user\IdeaProjects\linuxbox` → `virtual-arm-box`.
2. Rename do pacote raiz `dev.vitorsilverio.linuxbox` → `dev.vitorsilverio.virtualarmbox`
   (sem hífen nem ponto extra — nome de pacote Java não aceita hífen).
3. `artifactId` `linuxbox` → `virtual-arm-box` e `mainClass` do `maven-jar-plugin`.
4. Interface `Machine` nova + `VersatilePbMachine implements Machine` + registro/seleção
   por `--machine=<nome>` no `Main`.
5. `README.md` reescrito (novo nome, nova intenção, tabela de máquinas) e `ROADMAP.md` novo
   com a escada de máquinas (conteúdo ditado abaixo — não inventar).
6. Atualização das referências a "linuxbox" na documentação do **arm-jitter**
   (`README.md`, `ROADMAP.md`, `docs/RFC-SOFTMMU.md`, `tasks/README.md`,
   `tasks/FILA-EXECUCAO.md`, `tasks/trilha-b-arquiteturas/b4.1*.md`).

## NÃO inclui (não fazer)

- **Não mexer no `armbox`** — nenhum arquivo, nenhuma linha.
- **Não implementar máquina nova** (Raspberry Pi é a task F3). A `Machine` nasce com **uma**
  implementação. Não criar classes vazias/stub de `RaspiMachine`, `Virt64Machine` etc.
- Não trocar a CPU do guest, o mapa de memória, os periféricos, o modelo de tempo nem o
  protocolo de boot. Esta task não pode alterar **nenhum** comportamento observável do boot:
  o `VersatilePbBootTest` tem de continuar passando **sem mudança de asserção**.
- Não reescrever os Javadocs existentes de `VersatilePbMachine`/`device/*` além da troca
  mecânica do nome do projeto onde ele aparece.
- Não criar repositório GitHub nem remote (decisão do usuário, acima).
- Não renomear `testdata/` nem seus arquivos.

## Especificação

### 1. Interface `Machine`

Novo arquivo `src/main/java/dev/vitorsilverio/virtualarmbox/Machine.java`. Extrair
**exatamente** os 4 métodos públicos que `VersatilePbMachine` já expõe hoje — nada a mais,
nada a menos (`typeByte`, `runSlice`, `core`, `runtime`):

```java
package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;

/// Uma máquina virtual completa (CPU + MMU + periféricos + protocolo de boot) hospedada
/// sobre o `arm-jitter`.
///
/// O contrato é deliberadamente mínimo — é o denominador comum entre placas muito
/// diferentes (`versatilepb`, Raspberry Pi, `virt` AArch64): o hospedeiro empurra o tempo
/// em fatias e observa/alimenta o console. Tudo que for específico de uma placa (mapa de
/// memória, IDs de IRQ, formato do kernel, ATAGs vs Device Tree) fica na implementação e
/// NÃO sobe para cá.
public interface Machine {

    /// Executa uma fatia de tempo: um lote fixo de blocos da CPU seguido do atendimento
    /// dos periféricos (temporizadores, controlador de interrupção, console).
    void runSlice();

    /// Entrega um byte ao console do guest, como se tivesse sido digitado no terminal.
    void typeByte(int value);

    /// O núcleo ARM principal da máquina — para testes, depuração e o `GdbServer`.
    ArmCore core();

    /// O runtime JIT do núcleo principal — para testes e diagnóstico.
    JitRuntime runtime();
}
```

`VersatilePbMachine` passa a declarar `implements Machine` e ganha `@Override` nos 4
métodos. **Nenhuma assinatura muda.** A classe continua `final`, o factory
`VersatilePbMachine.create(...)` continua idêntico.

### 2. Registro e seleção de máquina no `Main`

O `Main` hoje tem `VersatilePbMachine.Backend backend` e chama `VersatilePbMachine.create`
direto. Mudanças:

- Nova flag `--machine=<nome>`, default `versatilepb`. Nome desconhecido → `usage()` +
  `System.exit(2)`, listando os nomes válidos.
- O `enum Backend` **continua onde está** (`VersatilePbMachine.Backend`) — movê-lo para
  `Machine` seria mudança de API sem necessidade nesta task. O `Main` segue usando
  `VersatilePbMachine.Backend`.
- A construção vira um `switch` sobre o nome:

```java
    private static Machine createMachine(String machineName, byte[] kernel, byte[] initramfs,
            String cmdline, VersatilePbMachine.Backend backend) {
        return switch (machineName) {
            case MACHINE_VERSATILEPB ->
                    VersatilePbMachine.create(kernel, initramfs, cmdline, System.out, backend);
            default -> throw new IllegalArgumentException(
                    "máquina desconhecida: " + machineName + " (disponíveis: " + MACHINE_VERSATILEPB + ")");
        };
    }
```

com `private static final String MACHINE_VERSATILEPB = "versatilepb";` (G6 — sem literais
soltos repetidos). O laço de `runSlice`/`pumpStdin` do `Main` passa a operar sobre `Machine`,
não sobre `VersatilePbMachine`.

Novo texto de `usage()`:

```
uso: virtual-arm-box [--machine=versatilepb] [--interp|--check] [--cycles=N] <zImage> <initramfs.gz> ["cmdline"]
```

### 3. Renames mecânicos

| De | Para |
|----|------|
| diretório `IdeaProjects/linuxbox` | `IdeaProjects/virtual-arm-box` |
| `<artifactId>linuxbox</artifactId>` | `<artifactId>virtual-arm-box</artifactId>` |
| `dev.vitorsilverio.linuxbox.Main` (mainClass do jar) | `dev.vitorsilverio.virtualarmbox.Main` |
| pacote `dev.vitorsilverio.linuxbox` (+ `.boot`, `.device`) | `dev.vitorsilverio.virtualarmbox` (+ `.boot`, `.device`) |
| diretórios `src/{main,test}/java/dev/vitorsilverio/linuxbox/` | `.../virtualarmbox/` |

`groupId` continua `dev.vitorsilverio` e a versão continua `1.0-SNAPSHOT` (a mudança de
versão do **arm-jitter** é a task F4/F7; este repo não é publicado).

Arquivos com a string `linuxbox` a corrigir (levantar com
`grep -ril linuxbox` nos dois repos antes de começar, e conferir a lista contra esta):

- virtual-arm-box: `pom.xml`, `README.md`, todos os `.java` (`package`/`import`/Javadoc),
  `testdata/README.md`.
- arm-jitter (**só documentação, nenhum `.java`**): `README.md`, `ROADMAP.md`,
  `docs/RFC-SOFTMMU.md`, `tasks/README.md`, `tasks/FILA-EXECUCAO.md`,
  `tasks/trilha-b-arquiteturas/b4.1-mmu-softmmu.md` e demais `b4.1*.md`.
  Nas entradas **históricas** de tasks já fechadas (FILA/índice), escrever
  `virtual-arm-box (ex-`linuxbox`)` na primeira ocorrência de cada arquivo, para o histórico
  continuar rastreável, e só `virtual-arm-box` nas seguintes.

### 4. `README.md` do virtual-arm-box

Reescrever com esta estrutura (o texto pode ser melhorado, o conteúdo factual não pode
mudar):

- Título `# virtual-arm-box`, uma linha: emulador de **máquina ARM completa** (CPU + MMU +
  periféricos + boot) sobre a biblioteca `arm-jitter`; irmão do `armbox` (user-mode) e dos
  emuladores `gbaemu`/`ndsemu`.
- Seção `## Máquinas`, com a tabela — **exatamente estas linhas, sem inventar status**:

| Máquina | `--machine=` | CPU do guest | Estado |
|---------|--------------|--------------|--------|
| ARM VersatilePB | `versatilepb` | ARM926EJ-S (ARMv5TE + VFPv2) | ✅ boota Linux real até shell `busybox` interativo (JIT e interpretado) |
| Raspberry Pi 1 / Zero | `raspi1` | ARM1176JZF-S (ARMv6K + VFPv2) | 🔜 task F3 |

- Seção `## Rodar` — atualizar o comando com `--machine=`.
- Uma linha em `## Máquinas` dizendo que **disco virtual (`raw`/QCOW2) é a task F10** e ainda
  não existe: hoje a raiz vem de `initramfs`.
- Seção `## Não é objetivo` (importante, evita expectativa errada): esta é uma máquina
  **ARM**; rodar Windows/Android exige a máquina AArch64 `virt` com UEFI, que depende de
  B6.6.6 fechar no arm-jitter. **macOS não é alvo** (Apple Silicon não é documentado e o boot
  é acorrentado ao hardware da Apple). Ver `ROADMAP.md`.
- Seção `## Licença` (vinda da F1) preservada.

### 5. `ROADMAP.md` do virtual-arm-box (arquivo novo)

Conteúdo ditado — a escada realista de máquinas, em ordem de dependência. **Copiar o
conteúdo desta seção para o arquivo, sem reordenar nem acrescentar máquinas.**

1. **`versatilepb` (✅ feito)** — ARMv5TE, ATAGs, Linux 3.2 Debian pré-compilado + busybox.
   Prova a MMU/softmmu (épico B4.1) fim-a-fim.
2. **`raspi1` (task F3)** — ARM1176JZF-S/ARMv6K, BCM2835: mini-UART/PL011, timer do
   sistema, controlador de IRQ próprio, mailbox. Primeira máquina com **Device Tree** em vez
   de ATAGs, e primeira validação do preset `ARMV6K` num kernel de sistema real (hoje o
   ARMv6K só foi validado em user-mode, no armbox). Kernel e DTB são baixáveis prontos do
   repositório `raspberrypi/firmware` — **não** dependem de toolchain `arm-linux-*`, que é o
   bloqueio histórico de B4.0.3/B6.2/B6.6.6.
3. **`virt64` (bloqueado em B6.6.6 do arm-jitter)** — AArch64 `-M virt`, GIC + virtio-mmio
   + PSCI. É o único caminho para guests modernos: Linux arm64, **Android** (que é Linux +
   userspace) e, mais adiante, **Windows on ARM** — este último exige, além da máquina,
   firmware **UEFI** (edk2 `QEMU_EFI.fd`) e virtio de disco/rede. Enquanto B6.6.6 não fechar
   (falta kernel/toolchain `aarch64-linux-*` reais), esta linha não anda.
4. **macOS — fora de escopo permanente.** Apple Silicon não tem documentação pública de
   plataforma, e o boot depende de hardware (Secure Enclave/iBoot) que não é emulável de
   forma legítima. Registrado aqui para nunca virar task.

Cada item além do 2 é **[REFINAR]**: vira spec própria quando o anterior fechar.

**Eixo transversal — armazenamento.** Independente da escada de máquinas, o
`virtual-arm-box` usa **disco virtual em formato padrão, compatível com outras VMs**:
`raw` e **QCOW2** (leitura e escrita), com VDI/VMDK/VHD atendidos por `qemu-img convert`.
Primeiro controlador: **PL181 MMCI (SD/MMC)** no `versatilepb`. Isso é a task **F10** — cite-a
no `ROADMAP.md` como seção própria (`## Armazenamento`), não como um degrau da escada, porque
toda máquina nova herda a mesma camada `DiskImage`.

## Passos

1. `grep -ril linuxbox` nos repos `linuxbox` e `arm-jitter`; salvar a lista (vai ser o
   checklist do aceite).
2. Renomear o diretório do repo (o `.git` vai junto; não há remote a atualizar).
3. Renomear os diretórios de pacote em `src/main` e `src/test` e trocar `package`/`import`
   em todos os `.java`. Use `git mv` para os diretórios, para o histórico seguir os arquivos.
4. `pom.xml`: `artifactId` + `mainClass`.
5. Criar `Machine.java`; `VersatilePbMachine implements Machine` + `@Override`.
6. `Main`: flag `--machine=`, `createMachine(...)`, laço sobre `Machine`, novo `usage()`.
7. Reescrever `README.md`, criar `ROADMAP.md`.
8. Corrigir as referências na documentação do arm-jitter.
9. `mvn -o test` no virtual-arm-box; conferir que `VersatilePbBootTest` passa **sem
   nenhuma asserção alterada**.
10. Commits: um no virtual-arm-box (`F2: rename para virtual-arm-box + abstração Machine`)
    e um no arm-jitter (`F2: atualiza referências a linuxbox na documentação`).

## Aceite

- [ ] `grep -ri linuxbox` no repo virtual-arm-box não retorna nada **exceto**
      `testdata/README.md` se lá houver citação histórica de proveniência — nesse caso,
      ajustada para `virtual-arm-box (ex-linuxbox)`.
- [ ] `grep -ri linuxbox` no arm-jitter só retorna ocorrências na forma
      `virtual-arm-box (ex-linuxbox)` em entradas históricas.
- [ ] `mvn -o test` verde no virtual-arm-box, com `VersatilePbBootTest` incluído e
      **nenhuma asserção do teste modificada** (`git diff` do arquivo de teste só pode
      mostrar troca de `package`/`import`).
- [ ] `java -cp ... dev.vitorsilverio.virtualarmbox.Main --machine=versatilepb <kernel>
      <initramfs>` boota igual a antes; `--machine=raspi1` sai com código 2 e mensagem
      listando as máquinas disponíveis.
- [ ] `Machine.java` existe com exatamente os 4 métodos, e `VersatilePbMachine` é a única
      implementação.
- [ ] `README.md` e `ROADMAP.md` novos, com a tabela e a escada ditadas acima.
- [ ] Índice do `tasks/README.md` atualizado (F2 ✅).

## Validação

`mvn -o test` no virtual-arm-box. Os outros repos **não** são afetados (nenhum depende dele)
— G5 não se aplica; não é preciso rodar gbaemu/ndsemu/armbox.

## Armadilhas

- **O boot é sensível e caro de depurar.** Se `VersatilePbBootTest` falhar depois do rename,
  a causa é quase certamente um `import` errado ou um caminho de recurso de teste, **não** a
  lógica. Não "conserte" a máquina: reverta e refaça o rename com cuidado.
- O `VersatilePbBootTest` leva ~12s (JIT) e ~18s (interpretado) por boot completo. Não é
  travamento — espere.
- Renomear diretório de repo no Windows com o IntelliJ aberto no projeto pode falhar por
  lock. Feche o IDE antes do passo 2 (peça ao usuário se necessário).
- `dev.vitorsilverio.virtualarmbox` — tudo junto, minúsculo, **sem hífen** (hífen é ilegal em
  nome de pacote Java) e sem `_`.
- O `armbox` também tem uma classe chamada `Main` e uma flag `--machine=`. São projetos
  diferentes: **não** copie código de lá nem "unifique" nada.
