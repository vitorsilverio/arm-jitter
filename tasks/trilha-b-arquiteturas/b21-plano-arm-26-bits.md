# B21 — ARMv1 / ARMv2 / ARMv2a / ARMv3 (modelo de 26 bits): épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano
⚠️ **B21.1 é RFC e exige sessão de modelo forte** (ver escada)

Documento MESTRE do épico. Escrito na auditoria de 2026-08-29 ("escrever a spec de todas as
extensões ainda faltantes"). É a outra pendência que a **B12** registrou sem plano:

> `ARMv1`/`ARMv2`/`ARMv2a`/`ARMv3` (modelo de registrador/exceção pré-ARMv3 diferente do resto do
> projeto, épico próprio).

São as arquiteturas do **ARM1/ARM2/ARM3/ARM250 e ARM6/ARM7** originais — o ARM do Acorn Archimedes.
Pela regra máxima do `tasks/README.md` ("se alguma arquitetura ARM existe, ela é alvo deste
projeto"), elas são trabalho pendente, não escopo descartado: o projeto começa hoje em ARMv4T
porque foi por onde o `gbaemu` entrou, não porque alguém decidiu que o ARM começa ali.

## O que muda de verdade (não é "ARMv4T com menos instruções")

O corte é no **modelo de estado**, e é por isso que isto é épico próprio e não uma linha de gate:

1. **`R15` é PC *e* PSR ao mesmo tempo.** No modo 26 bits, `R15` = `[31:28]` flags `N`/`Z`/`C`/`V`,
   `[27:26]` máscaras `I`/`F`, `[25:2]` PC (26 bits de endereço → **64 MiB** de espaço), `[1:0]`
   modo (`USR`/`FIQ`/`IRQ`/`SVC`). Não existe `CPSR` separado, não existe `SPSR`: o retorno de
   exceção é `MOVS PC, R14`, que restaura flags E modo porque eles estavam DENTRO do registrador.
2. **4 modos, não 7.** Sem `ABT`/`UND`/`SYS` (aborto e instrução indefinida entram em `SVC` no
   ARMv1/v2), sem banco de registradores de `ABT`/`UND`.
3. **Sem `MRS`/`MSR`** (chegam no ARMv3): manipular flags é manipular `R15`.
4. **ARMv3 é a ponte**: introduz `CPSR`/`SPSR`, os 32 bits de endereço, os modos novos e o bit de
   compatibilidade que faz um ARMv3+ rodar código de 26 bits (`P` de `SCTLR`). É por isso que a
   escada termina nele, e não começa.

Diferenças de conjunto de instruções (menores, e todas gate puro): ARMv1 **não tem `MUL`/`MLA`**;
ARMv2 acrescenta `MUL`/`MLA` e o espaço de coprocessador; **ARMv2a** acrescenta `SWP` (e o
coprocessador de controle CP15 do ARM3); ARMv3 acrescenta `MRS`/`MSR`; ARMv3M acrescenta as
multiplicações longas (`UMULL`/`SMULL`/`UMLAL`/`SMLAL`).

## O que falta de infraestrutura (verificado no código)

- **`ArmCore` tem `CpsrRegister` como campo fixo** (`private final CpsrRegister cpsr`) e `R15` é um
  registrador comum lido/escrito por `register(15)`/`setRegister(15, ...)` — a fusão PC+PSR não tem
  onde morar hoje.
- **Existe o precedente EXATO de como plugar isto sem quebrar nada**: a `ExceptionModel` plugável
  (`AProfileExceptionModel`/`MProfileExceptionModel`, task B7.2 e `docs/RFC-M-PROFILE.md`). A
  decisão do perfil M foi "`ArmCore` único + modelo plugável" — a mesma pergunta se repete aqui, com
  a diferença de que agora o que varia é a REPRESENTAÇÃO do estado, não só a entrada em exceção.
- **`ArmArchitecture` não tem preset anterior a `ARMV4T`**, e o menor gate hoje (`ARMV4T`) já
  pressupõe 32 bits de endereço e `CPSR`.
- **`docs/COBERTURA-ISA.md` não tem inventário para pré-v4**: o QEMU não traz `.decode` dessas
  arquiteturas (ele não as emula). O oráculo aqui é o **ARM DDI 0100** (ARM Architecture Reference
  Manual, apêndice de arquiteturas obsoletas) e o datasheet do ARM2/ARM3 — B21.6 tem que criar o
  inventário à mão para que essas colunas existam na tabela.

## Escada (refinar em spec própria quando cada degrau for pego)

| Task | Escopo | Depende de |
|---|---|---|
| **B21.1** | ⚠️ **RFC** (modelo forte): como representar o estado de 26 bits sem quebrar o caminho de 32 bits nem o desempenho do laço quente. Opções a decidir com código na mão: (a) `ProgramStatusModel` plugável espelhando a `ExceptionModel` de B7.2, com `register(15)` compondo/decompondo sob demanda; (b) `ArmCore26` separado; (c) manter `CpsrRegister` como armazenamento REAL e fazer `R15` ser uma *view* composta (provável vencedora — preserva todo o resto do projeto). Decide também se o modo 26 bits de um ARMv3+ (`SCTLR.P`) entra agora ou depois | — |
| **B21.2** | Presets `ARMV1`/`ARMV2`/`ARMV2A`/`ARMV3`/`ARMV3M` + as `ArmFeature` que faltam para gatear o que NÃO existe em cada uma (`MUL`/`MLA`, `SWP`, `MRS`/`MSR`, multiplicação longa). Gate puro, sem decode novo — o decoder de ARMv4T já cobre esse conjunto | B21.1 |
| **B21.3** | Estado de 26 bits propriamente dito (o que a RFC decidir): fusão PC+PSR em `R15`, os 4 modos, banco de registradores reduzido | B21.1 |
| **B21.4** | Exceções de 26 bits: vetores em `0x00`-`0x1C`, entrada salvando `R15` inteiro em `R14_<modo>`, retorno por `MOVS PC,R14`/`SUBS PC,R14,#4`, `ABT`/`UND` caindo em `SVC` nas versões que não têm modo próprio | B21.3 |
| **B21.5** | Espaço de endereço de 26 bits: PC mascarado em `0x03FF_FFFC` (wrap de 64 MiB), e o que acontece com um endereço acima disso — comportamento diferente do wrap de 32 bits que `AddressSpace` assume hoje | B21.3 |
| **B21.6** | Inventário e medição: criar a fonte de verdade das colunas pré-v4 (ARM DDI 0100 + datasheet do ARM2/ARM3), estender `IsaCoverageReport` para elas e curar em `isa-nao-aplicavel.tsv` tudo que é POSTERIOR a cada uma | B21.2 |
| **B21.7** | Catálogo `ArmProcessor`: `ARM1`/`ARM2`/`ARM250`/`ARM3` (26 bits) e `ARM6`/`ARM7` (ARMv3, 32 bits com compatibilidade de 26) | B21.2 |
| **B21.8** | Validação N1-N4: alvo natural é código real do **Acorn Archimedes** (o RISC OS é o binário de 26 bits mais disponível e documentado); mesma disciplina do `virtual-arm-box` (F3) — hospedeiro próprio, se e quando o usuário priorizar | B21.4, B21.5 |

## Meta

`ARMv1`-`ARMv3M` viram colunas medidas em `docs/COBERTURA-ISA.md`, os processadores originais entram
no catálogo, e o projeto passa a cobrir o ARM **desde a primeira versão** — fechando a pendência que
a B12 registrou.

## Invariantes específicos deste épico

- **G3 é o risco central deste épico.** Tudo aqui toca `ArmCore`, que é o caminho quente de 5
  consumidores. Nenhuma mudança pode alterar o comportamento nem o custo do caminho de 32 bits —
  se a RFC B21.1 não achar um desenho que garanta isso, ela deve dizer isso e PARAR, em vez de
  entregar um desenho caro.
- **G1**: o interpretador continua sendo o oráculo; o modelo de 26 bits precisa passar pelo
  `BlockEquivalenceHarness` como qualquer outro backend.
- **G6**: as máscaras de `R15` (flags, modo, PC) são constantes arquiteturais nomeadas, nunca
  literais espalhados.

## Armadilhas conhecidas

- **`PC` lido vale `instrução + 8` também aqui**, mas os bits de PSR viajam junto — ler `R15` para
  usar como endereço sem mascarar é o bug clássico do código de 26 bits, e o emulador tem que
  reproduzir o comportamento, não "consertar".
- **`MOVS PC, R14` é retorno de exceção, não um `MOV` com flags.** No modelo de 26 bits o sufixo `S`
  com destino `R15` em modo privilegiado restaura modo e flags — se o executor tratar como
  "atualiza NZCV", o retorno de exceção fica silenciosamente errado (mesmo tipo de armadilha que a
  B9.11 achou no alias `SUBS PC,LR,#imm`).
- **ARMv2a acrescenta `SWP`, ARMv2 não o tem** — a numeração é enganosa; conferir no ARM DDI 0100 e
  não deduzir por "v2a é v2 com detalhes".
- **Não existe `Thumb` em lugar nenhum deste épico** (T16 chega no ARMv4T): nenhum preset daqui pode
  declarar `THUMB`, e a coluna de T16/T32 dessas arquiteturas é "não se aplica" de verdade — o
  único caso do projeto em que isso é uma exclusão legítima, e mesmo assim vai para o TSV curado com
  a versão que introduziu, nunca apagada.
- **Um ARMv3 declara 26 E 32 bits.** O `SCTLR.P` escolhe; um preset ARMv3 que só modele 32 bits está
  incompleto, e um que só modele 26 também.
