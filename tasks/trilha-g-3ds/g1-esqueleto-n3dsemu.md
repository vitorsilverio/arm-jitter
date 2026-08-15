# G1 — Repo `n3dsemu`: esqueleto, memória, loader `.3dsx` e primeira `svc` (marco M1)

**Trilha:** G · **Depende de:** F7 (arm-jitter 1.0.0 no Central) · **Repo:** n3dsemu (novo)
**Leia `tasks/trilha-g-3ds/RFC-N3DSEMU.md` inteira antes de começar.** As decisões D1–D8
estão lá e **não** devem ser reabertas.

## Contexto

Repositório novo, irmão de `gbaemu`/`ndsemu`. O lado `arm-jitter` já está pronto (B5.1
monitor de exclusividade compartilhado + B5.2 preset `ARM11_MPCORE`); esta task é o
hospedeiro nascendo.

## Objetivo (marco M1 da RFC)

`n3dsemu <arquivo.3dsx>` carrega o executável, monta o espaço de endereçamento, começa a
executar no `entrypoint` e chega à primeira `svc` do guest, imprimindo um trace legível.

## Inclui

1. Repo Maven novo + `AGENTS.md` + `README.md` + `LICENSE` (BSD 3-Clause, mesmo texto da F1)
   + `.github/workflows/ci.yml` (mesmo modelo da F6).
2. Corpus de teste: compilar exemplos do devkitPro e versionar os `.3dsx` **pequenos**.
3. `memory/` — mapa de endereçamento da RFC §3 sobre `PagedAddressSpace` do arm-jitter.
4. `loader/` — leitor do formato `.3dsx` com relocação.
5. `core/` — `ArmCore` com `ARM11_MPCORE`, `JitRuntime`, `ExclusiveMonitor` compartilhado.
6. `kernel/SvcTable` — **só o esqueleto**: intercepta a `svc`, decodifica o número, loga e
   lança `UnsupportedSvcException` para tudo. A implementação é a G2.
7. `Main` headless + `N3dsMachine`.

## NÃO inclui (não fazer)

- **Nenhum serviço, nenhuma `svc` implementada de verdade** (G2/G3).
- **Nada de gráfico** — sem LWJGL, sem Vulkan, sem janela, sem dependência nova no POM
  (G4). O `Main` desta task é headless.
- Sem segundo núcleo, sem ARM9, sem MMU, sem áudio (RFC §5).
- Sem `.cia`/`.3ds`, sem RomFS.

## Especificação

### Corpus (passo 0, antes de qualquer código)

O devkitPro está instalado em `C:\devkitPro` (verificado em 2026-08-15): `devkitARM`,
`libctru`, `C:\devkitPro\examples\3ds\` e as ferramentas em `C:\devkitPro\tools\bin\`
(`3dsxtool.exe`, `3dsxdump.exe`, `picasso.exe`, `smdhtool.exe`, `tex3ds.exe`).

Compilar, pelo shell do devkitPro (MSYS2), pelo menos:

- `examples/3ds/templates/application` — o "hello world" mínimo, alvo do M1/M2.
- `examples/3ds/graphics/printing/hello-world` — alvo do M4.
- `examples/3ds/graphics/gpu/simple_tri` — alvo do M5.
- `examples/3ds/input/read-controls` — alvo do M3.

Versionar em `testdata/` **os `.3dsx` e os `.elf`** (o `.elf` é o que dá depuração
simbólica, como no ndsemu — ver memória `ndsemu-homebrew-testing`), com um `README.md`
dizendo a versão do devkitARM/libctru usada e o comando de build. Se algum exemplo não
compilar com o toolchain instalado, anote e siga com os que compilarem — **não** conserte
exemplos do devkitPro.

### Formato `.3dsx`

Especificação em `https://www.3dbrew.org/wiki/3DSX_Format`. Resumo do que precisa:
cabeçalho com magic `3DSX`, tamanho de cabeçalho, número de tabelas de relocação, e os
tamanhos de `.text`/`.rodata`/`.data+.bss`. Segue-se o conteúdo dos três segmentos e depois
as tabelas de relocação (absoluta e relativa), aplicadas sobre a base de carga
**`0x00100000`**.

Um `.3dsx` com `headerSize > 32` traz também a extensão com ponteiros de SMDH e RomFS —
**ignore-os** nesta task (RomFS é fora de escopo), mas **leia** o campo para não desalinhar o
parsing.

Ponto de entrada = base de carga (`0x00100000`).

Ler o formato **da especificação**, não de memória. Um `.3dsx` malformado tem de dar uma
exceção clara (`Bad3dsxException`, espelhando `BadElfException` do armbox), nunca um erro
obscuro de índice.

### Memória

`PagedAddressSpace` do arm-jitter (utilitário C3, o mesmo que o gbaemu e o
`virtual-arm-box` usam). Regiões conforme a RFC §3. Regras:

- FCRAM, VRAM, DSP RAM, região de código e heap: RAM comum (`byte[]`).
- **Config memory** (`0x1FF80000`) e **shared page** (`0x1FF81000`): páginas de leitura,
  preenchidas com valores plausíveis de Old3DS. Copiar os campos e valores do
  `3dbrew.org/wiki/Configuration_Memory` e `.../Shared_Memory_Page` — em especial
  `KERNEL_VERSION`, `APPMEMTYPE`, `APPMEMALLOC`, e o modelo do console.
- Fora das regiões conhecidas: uma `OpenBus` que **loga e devolve 0** na leitura e loga na
  escrita, com o endereço. No começo, esse log é a principal ferramenta de diagnóstico.

Nomeie toda base/tamanho como constante (G6 do arm-jitter — sem números mágicos), no estilo
do `VersatilePbMachine`.

### CPU

```java
ArmArchitecture.ARM11_MPCORE   // B5.2: ARMv6K + VFPv2, sem Thumb-2
```

Registrar `VfpDecoder` e `CoprocessorDecoder`. `ExclusiveMonitor` compartilhado (B5.1) já
desde agora, mesmo com um núcleo (D1 da RFC). Backend default **JIT**; `--interp` e
`--check` disponíveis no `Main`, espelhando o `armbox`/`virtual-arm-box`.

O 3DS usa o coprocessador CP15 para o **TLS por thread** (`c13`) — vale lembrar que a
ausência do `c13` foi exatamente a causa raiz do bug que travava todo homebrew moderno no
ndsemu (memória `ndsemu-calico-boot-fix`). Implemente `c13` desde o começo, no
`CoprocessorBus`, guardando o valor por thread.

### `svc` e o esqueleto do kernel

O guest chama `svc` (ARM: `SVC #imm`). Usar o `SwiDispatcher` do arm-jitter — o mesmo
mecanismo que gbaemu e ndsemu já usam — para interceptar **sem** entrar em vetor nenhum
(não há BIOS a emular; RFC D2).

```java
/// Tabela de SVCs do kernel Horizon. Nesta task todas caem no default: logam e lançam.
public final class SvcTable { ... }
```

Formato do log (uma linha por chamada, para o diagnóstico do M1 ser útil):

```
[svc] 0x2D svcConnectToPort r0=... r1=0x00108a44 pc=0x0010035c
```

Os **nomes** das SVCs (mesmo das não implementadas) devem estar na tabela desde já —
`https://www.3dbrew.org/wiki/SVC`. Sem os nomes, o log do M1 é inútil.

### `Main`

```
uso: n3dsemu [--interp|--check] [--slices=N] [--trace-svc] <arquivo.3dsx>
```

Roda em fatias, igual ao `virtual-arm-box`; ao encontrar SVC não implementada, imprime o
trace das últimas N chamadas e sai com código 3.

## Passos

1. Compilar os 4 exemplos; versionar `.3dsx`/`.elf` + `testdata/README.md`.
2. `mvn archetype`-nada: crie o `pom.xml` **copiando o do ndsemu** e trocando `artifactId`
   (o do ndsemu já tem tudo: Java 25, surefire, dependência do arm-jitter). Depois da F7 a
   versão do arm-jitter é `1.0.0` e **não** se declara `org.ow2.asm`.
3. `AGENTS.md` espelhando o do ndsemu (hardware alvo, regras de arquitetura, convenções de
   código, build/testes) — adaptado ao 3DS e citando a RFC.
4. `loader/` + testes (um `.3dsx` sintético montado no teste, como o `SyntheticElf` do
   armbox, **mais** um teste com o `.3dsx` real do `templates/application`).
5. `memory/` + testes (cada região responde no endereço certo; config memory legível).
6. `core/` + `SvcTable` esqueleto.
7. `N3dsMachine` + `Main`.
8. Teste de integração `Application3dsxTest`: carrega o `.3dsx` real, roda até a primeira
   `svc` e afirma **qual** SVC é (a primeira do libctru costuma ser
   `svcConnectToPort("srv:")` ou uma de memória — descubra rodando, e **fixe a asserção no
   que for observado**, documentando).

## Aceite

- [ ] `mvn -o test` verde.
- [ ] `n3dsemu testdata/application.3dsx --trace-svc` imprime pelo menos 5 linhas de trace
      com nomes de SVC reais e sai com código 3 na primeira não implementada.
- [ ] Mesmo resultado nos backends JIT e `--interp` (mesma sequência de SVCs) — **este é o
      aceite mais importante**: prova que o preset `ARM11_MPCORE` executa código ARMv6K real
      de forma consistente nos dois motores.
- [ ] `LICENSE`, `README.md`, `AGENTS.md`, `.github/workflows/ci.yml` presentes.
- [ ] Nenhuma dependência de LWJGL/Vulkan no POM.
- [ ] Índice do `tasks/README.md` do arm-jitter atualizado (G1 ✅).

## Validação

`mvn -o test` no n3dsemu. G5 não se aplica (repo novo, nada compartilhado mudou) — **salvo**
se um bug do arm-jitter aparecer: aí correção em commit separado lá, com teste de regressão,
e gbaemu+ndsemu+armbox revalidados.

## Armadilhas

- **`ARM11_MPCORE` não tem Thumb-2.** Se um `.3dsx` do devkitARM vier em Thumb-2 largo, a
  decodificação falha. O libctru compila em ARM/Thumb-1 por padrão; se aparecer UNDEFINED em
  massa logo no início, **verifique a ISA do binário** (`arm-none-eabi-objdump -d`) antes de
  suspeitar do decoder.
- **Não confunda o ARM11 do 3DS com o ARM9 do NDS.** São consoles diferentes; nada do
  `ndsemu` se aplica direto, apesar do nome parecido.
- Base de periféricos e formato de framebuffer do 3DS **não** têm relação com o NDS. Não
  copie `ndsemu/video`.
- O `.3dsx` **não** é ELF. Não tente reusar o `Elf32Loader` do armbox.
- Se `C:\devkitPro\examples\3ds` não compilar (toolchain desatualizado), **PARE e reporte** —
  sem corpus não há como validar nada desta trilha. Não invente um `.3dsx` à mão.
