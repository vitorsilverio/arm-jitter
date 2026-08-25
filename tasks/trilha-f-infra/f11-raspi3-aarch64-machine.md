# F11 — `virtual-arm-box --machine=raspi3-64`: Raspberry Pi 3, AArch64 (kernel8.img)

**Trilha:** F (infra) · **Depende de:** F3 (M1/M2, para o `FdtPatcher`/padrão de máquina BCM —
**não** depende de M3 fechar) + B6.6.5 do arm-jitter (decoder/MMU/exceção A64, já ✅) · **Repo:**
virtual-arm-box

## Contexto

O ROADMAP (`ROADMAP.md`) previa o degrau aarch64 (`virt64`) como bloqueado no usuário desde
2026-07-26 (task `B6.6.6` do arm-jitter) — precisava de um kernel arm64 mainline pré-compilado
E de um DTB/PSCI/GIC feitos do zero para a máquina `virt` do QEMU. **Esse bloqueio nunca foi
sobre o `arm-jitter` em si** (o decoder A64, a MMU VMSA64 e o modelo de exceção EL0↔EL1 já
fecharam em B6.6.1-B6.6.5, 2026-07-26/27) — era só sobre não ter ambiente/kernel disponível.

**Achado desta rodada (2026-08-18, a pedido do usuário)**: o MESMO repositório
`raspberrypi/firmware` que deu o `kernel.img`/`bcm2708-rpi-b.dtb` prontos para a F3 (raspi1,
32-bit) **também publica `kernel8.img`** — um kernel Linux **AArch64 pré-compilado real** para
Raspberry Pi 3/3+ (e um DTB pronto, `bcm2710-rpi-3-b.dtb` ou equivalente) — baixável do mesmo
jeito, **sem precisar do toolchain `aarch64-linux-*`** que travava `B6.6.6`. Isso destrava o
degrau aarch64 sem esperar o usuário resolver ambiente, E evita as duas partes mais arriscadas
que `B6.6.6` tinha previsto para a máquina `virt` do QEMU:

- **Sem GICv2/GICv3.** O Raspberry Pi 3 real (BCM2837) não tem GIC — usa o MESMO controlador de
  interrupção legado do BCM2835/raspi1 (`brcm,bcm2835-armctrl-ic`, já implementado em
  `device/bcm2835/` pela F3) mais um controlador LOCAL por-núcleo simples
  (`brcm,bcm2836-l1-intc`, registradores de timer/mailbox por-CPU, bem mais simples que GIC).
- **DTB pronto**, não precisa de um `DeviceTreeBuilder` do zero — mesma estratégia de asset
  versionado que F3 já validou.

O que a F11 herda de risco NOVO (não coberto pela F3): o Pi 3 é **quad-core**, e o kernel real
espera 4 nós `cpu@N` no DTB. Rodar só o núcleo 0 exige **podar os `cpu@1..3` do DTB** antes do
boot — mesma técnica que a F3 já usou para desabilitar `mmc@.../usb@...`
(`FdtPatcher#withNodeDisabled`, ver sessão do CPRMAN 2026-08-17), só que removendo o nó inteiro em
vez de desabilitar. Isso evita todo o SMP bring-up (`PSCI CPU_ON`/spin-table) — **decisão de
escopo**: um núcleo só, mesmo padrão do raspi1.

## Objetivo

`virtual-arm-box --machine=raspi3-64 <kernel8.img> <initramfs> [dtb]` leva um kernel Raspberry Pi
3 AArch64 oficial até, no mínimo, o marco M2-equivalente da F3 (`Freeing unused kernel...` sem
abort), nos dois backends A64 disponíveis (interpretado e `--check`/ASM). Shell interativo
(M3-equivalente) é bônus, não bloqueador desta task — dado o histórico da F3, tratar como marco
separado se não fechar na mesma sessão.

## Inclui

1. Aquisição e versionamento dos assets em `testdata/raspi3-64/` +
   `testdata/raspi3-64/README.md` (mesmo padrão de `testdata/raspi1/README.md`: URL, tag/commit
   do `raspberrypi/firmware`, sha256 de cada arquivo). **Confirmar antes de escrever código**
   que `kernel8.img` + um DTB de Pi 3 (`bcm2710-rpi-3-b.dtb` ou o nome real do repo, CONFERIR)
   realmente existem no `boot/` do `raspberrypi/firmware` — não presumir do enunciado.
2. Extensão do `FdtPatcher` (já existe, F3) com uma operação de **remoção** de nó (distinta de
   `withNodeDisabled`, que só marca `status=disabled` — aqui precisa remover `cpu@1`/`cpu@2`/
   `cpu@3` de verdade, ou a alternativa mais simples se validada: reescrever `reg`/deixar só
   `cpu@0` sem alterar offsets do blob, CONFERIR qual é mais barato no formato FDT real).
3. Periféricos NOVOS mínimos em `device/bcm2836/` (ou reaproveitando `device/bcm2835/` com um
   parâmetro de variante, CONFERIR o que for mais simples): controlador de interrupção local
   por-núcleo (`brcm,bcm2836-l1-intc` — timer ARM genérico + mailboxes IPI, só o necessário para
   1 núcleo). O resto (PL011, mailbox, timer de sistema, IC legado) é **reuso direto** do que a
   F3 já escreveu para `device/bcm2835/` — o BCM2837 é peça de silício compatível.
4. `Raspi364Machine implements Machine`, CPU `ArmArchitecture` A64 (preset já existente do épico
   B6 — CONFERIR o nome exato do preset A64 genérico usado pelos testes de B6.6.1-B6.6.5),
   registrada como `raspi3-64` no `Main`.
5. Testes de unidade por periférico novo + `Raspi364BootTest` (mesmo formato de
   `Raspi1BootTest`).

## NÃO inclui (não fazer)

- **Sem PSCI.** Com só `cpu@0` no DTB, nenhum `CPU_ON` deveria ser chamado. Se o kernel ainda
  assim tentar um `SMC`/`HVC` de PSCI por outro motivo (ex.: `cpuidle`/`cpufreq` consultando
  `PSCI_VERSION`), **não implementar PSCI de verdade** — documentar como achado e decidir com o
  usuário se vale (mesmo espírito de "não fazer periférico não especificado" da F3).
- **Sem GIC.** Decisão explícita acima — o Pi 3 real não usa, não introduzir.
- **Sem SMP de verdade** (múltiplos `ArmCore`) — 1 núcleo, mesmo padrão de toda a fila até aqui.
- **Sem SD/MMC/USB/rede** — mesmo padrão da F3, tudo fora disso é `OpenBus`.
- **Sem mudança no `arm-jitter`.** Exceção: bug real da lib (já aconteceu 4x em B4.1.5, 1x em
  F3) — commit separado, teste de regressão, revalidar G5.
- **B6.6.6 (o plano original `virt64`/QEMU) fica formalmente [REFINAR]/em espera** — não
  cancelada, só sem prioridade enquanto esta rota mais barata não for esgotada. Se a F11 travar
  numa parede real (ex.: o kernel Raspberry Pi exige algo que só a máquina `virt` evitaria),
  registrar isso explicitamente antes de voltar para B6.6.6.

## Especificação

Mesma estrutura de handoff/protocolo de boot da F3 (host = bootloader, `X0`=DTB, MMU desligada,
EL1, ver B6.6.6 armadilhas para a convenção A64 exata — `X0..X3` reservados/zero exceto `X0`).
Mapa de memória BCM2837: **mesma base `0x3F000000`** da F3 documentar como PEGADINHA — é
DIFERENTE da `0x20000000` do raspi1/BCM2835 (a F3 já registrou essa armadilha ao contrário, para
quem vier da doc de Pi 2/3 por engano; aqui é o caso inverso: confirmar contra `hw/arm/raspi.c`
do QEMU, que também modela `raspi3ap`, antes de reusar endereços do raspi1 sem conferir).

## Aceite

- [ ] `testdata/raspi3-64/README.md` com proveniência e sha256 dos assets.
- [ ] Kernel avança até um marco equivalente ao M2 da F3 (mensagem de "freeing"/fim de
      descompressão de init, sem abort) nos 2 backends A64.
- [ ] `mvn -o test` verde no virtual-arm-box; `VersatilePbBootTest`/`Raspi1BootTest` continuam
      verdes (nenhuma regressão nas máquinas existentes).
- [ ] Índice do `tasks/README.md` atualizado.
- [ ] (bônus, não bloqueador) shell interativo, mesmo formato de aceite M3 da F3.

## Validação

`mvn -o test` no virtual-arm-box. G5 completo (arm-jitter+gbaemu+ndsemu+armbox+virtual-arm-box)
só se algum arquivo do arm-jitter for tocado.

## Armadilhas

- **Base de periféricos `0x3F000000` no Pi 3, não `0x20000000` do raspi1** — não copiar
  constantes de `Bcm2835Machine` sem ajustar.
- **Poda de `cpu@N` no FDT precisa preservar o blob válido** — remover um nó do meio da árvore
  FDT muda offsets internos (tamanho de `struct`, strings) de um jeito que
  `FdtPatcher#withNodeDisabled` (que só reescreve uma property in-place) não cobre; ler o
  formato de remoção com cuidado antes de implementar (o parser/writer FDT já existe da F3,
  reusar a base).
- **Histórico de risco “fechamento parcial” da F3 se repete aqui.** Reservar tempo de sessão
  generoso; um M2-equivalente fechado já é o aceite mínimo desta task, shell interativo é bônus.
- Antes de escrever qualquer periférico, **confirmar que os assets (`kernel8.img` + DTB do Pi 3)
  realmente estão publicados no `raspberrypi/firmware`** — se não estiverem (ex.: só existirem
  para Pi 4/`bcm2711`), documentar e devolver ao usuário em vez de trocar de alvo sem avisar.

## Resultado (sessão 7, 2026-08-24/25)

Retomada depois que `B6.9`/`B6.13`/`B6.14` fecharam (a última, 2026-08-24, corrigiu a corrupção
real de `SP` em `ADD`/`SUB (immediate)` que era a causa raiz do sétimo bloqueio antigo, refutando
a hipótese original de `B6.13` sobre `TTBR1_EL1`). Com o `.m2` local atualizado, o boot avançou
MUITO além do ponto anterior (`0x139e82c`) e bateu 4 gaps reais novos, todos fechados no
`arm-jitter` nesta mesma sessão (encodings conferidos via `aarch64-none-elf-as`/`objdump` reais,
devkitA64 disponível — G1):

1. **`CPACR_EL1`** (`op0=3,op1=0,CRn=1,CRm=0,op2=2`) — escrito logo após `SCTLR_EL1` em `head.S`,
   nunca decodificado (mesmo `CRn`/`CRm` de `SCTLR_EL1`, só `op2` diferia). Armazenamento puro
   (mesma disciplina de `CPTR_EL2`/`CPTR_EL3`).
2. **Grupo inteiro `ID_AA64*` restante** (`CRn=0,CRm=4-7`: `ID_AA64PFR1_EL1`/`ID_AA64ZFR0_EL1`/
   `ID_AA64DFR1_EL1`/`ID_AA64ISAR1_EL1`/`ID_AA64ISAR2_EL1`/`ID_AA64MMFR1_EL1`-`ID_AA64MMFR4_EL1`/
   `REVIDR_EL1`) — `head.S`/`cpufeature.c` sondam todos em sequência; resolvidos de uma vez em vez
   de gap-a-gap (cada iteração anterior custava um boot inteiro) — todos constantes `0`
   (nenhuma extensão opcional implementada), mesma disciplina de `ID_AA64ISAR0_EL1`.
3. **`TTBR1_EL1` implementado de verdade** (não só decodificado): `TranslatingAddressSpace64`
   ganhou `setTtbr1`/segunda tabela-base; `walk()` agora seleciona `TTBR0`/`TTBR1` pelo bit **55**
   do VA (fato já registrado por `B6.13` contra `aa64_va_parameters` real do QEMU — a hipótese de
   `B6.13` sobre a MECÂNICA estava certa, só a causa do bloqueio antigo estava errada). Achado
   real de teste: o VA de kernel usa índices L0-L3 diferentes do VA de identidade usado pelos
   testes existentes — um teste inicial cometeu o mesmo erro (índice L3 errado), corrigido.

Com os 3 fechados (arm-jitter `mvn -o test` verde, 2167+ testes, `mvn -o install` local), o
backend **INTERPRETED roda 10 minutos inteiros sem lançar exceção nenhuma** — não há mais
bloqueio "duro" conhecido, mas também não alcança `EARLYCON_BANNER` dentro do orçamento (o
`@Timeout` de 10min interrompe antes de `MAX_SLICES` esgotar). Causa (boot genuinamente mais
lento agora vs. laço ocioso real, mesmo padrão da investigação de silêncio da F3 em 2026-08-16)
**não isolada nesta sessão** (disciplina de custo). O backend **JIT** foi tentado como alternativa
mais barata e diverge MUITO mais cedo com uma falha DIFERENTE
(`MemoryTranslationException64: TRANSLATION_FAULT_L3 em 0x200`, ~1s) — não investigado.

**Achado operacional relevante**: dois processos `surefirebooter` órfãos de tentativas anteriores
(mortas pelo agente mas não pelo SO) ficaram consumindo ~7,6GB de RAM, causando
`OutOfMemoryError`/crash de JVM nas tentativas seguintes ("insufficient memory... Native memory
allocation (mmap) failed") — resolvido matando os PIDs órfãos (`taskkill /F /T`) antes de repetir
o teste. Vale checar processos `java.exe`/`surefirebooter` travados antes de sessões futuras desta
task, caso o mesmo sintoma reapareça.

`mvn -o test` verde no virtual-arm-box (suíte completa, 3 testes de marco textual voltam a
`@Disabled` com o achado atualizado); G5 completo revalidado nesta sessão: arm-jitter ✅,
gbaemu ✅, ndsemu ✅, armbox 40/41 (falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não
relacionada), n3dsemu ✅. Sem marco de release (nenhuma mudança de cobertura de ISA mensurada —
estes registradores não entram no `docs/COBERTURA-ISA.md`, que mede A64 de forma ampla, não por
task).

**F11 segue 🟡 PARCIAL** — nenhum bloqueio "duro" (decode gap/exceção) restante conhecido, mas o
aceite (`EARLYCON_BANNER` no console) ainda não foi alcançado. Candidatas à próxima sessão: (a)
profiling do INTERPRETED (slices/s, procurar `WFI`/loop-e-volta) para separar "lento de verdade"
de "preso"; (b) investigar a divergência do JIT separadamente (endereço `0x200` sugere algo bem
anterior aos 3 gaps fechados aqui, possivelmente não relacionado).
