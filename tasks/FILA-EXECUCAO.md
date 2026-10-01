# Fila de execução — para agentes com contexto limitado

**Este arquivo é enxuto de propósito** (limpo em 2026-08-28 — estava com 1113 linhas de narrativa
histórica, causando sessões novas perderem tempo tentando pegar tasks já fechadas). Status de cada
task vive no `INDICE.md` da trilha correspondente (`tasks/README.md` tem a tabela de trilhas); o
histórico narrativo completo (o que foi feito, achados, decisões) vive na própria task, seção
`## Resultado`, ou em `tasks/FILA-HISTORICO.md` para sessões antigas sem task própria. **Antes de
pegar qualquer coisa, confira o `INDICE.md` da trilha — não confie em texto solto sobre "o que falta"
sem checar o status real ali.**

## Regras de sessão (obrigatórias)

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a próxima task na
   mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes G1-G8), depois o
   `INDICE.md` da trilha (confirma que a task está ⬜/não pega uma já ✅), depois SÓ o arquivo da
   task + os fontes que ela cita. Não explorar o repo além disso.
   **Fonte citada com mais de ~1500 linhas (`Aarch64Decoder`, `IrOp`, `Ir64Op`,
   `AdvSimdLanes`, `AsmBlockCompiler`, `IrSystemExecutor`, `StandardIrBuilder`,
   `Aarch64Core`, `Aarch64DecoderCorpusTest`, ...): NUNCA `Read` o arquivo inteiro
   (2026-09-27, medido: isso sozinho já consome uma fatia grande do orçamento de contexto
   da sessão). Primeiro `Grep` pelo nome do encoding/opcode/método que a task cita para
   achar a(s) linha(s); depois `Read` só essa faixa com `offset`/`limit`. Só ler o arquivo
   inteiro se a própria task pedir isso explicitamente.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao usuário.
4. Nunca pegar itens de "Pendências que EXIGEM modelo forte" (`tasks/README.md`) nem da seção
   "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25) + G5 nos consumidores relevantes,
   status atualizado no `INDICE.md` da trilha, seção `## Resultado` na própria task, 1 commit
   começando com o ID (`B11.x: ...`), `git push`.
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale só entre repos
   DIFERENTES. Commits sempre com paths explícitos (`git add <arquivos da SUA task>`), nunca
   `git add -A`.
7. **Ao fechar uma task, não reescreva a narrativa aqui** — só atualize o `INDICE.md` da trilha (o
   `## Resultado` da própria task já é o histórico). Este arquivo só muda quando o estado descrito
   abaixo ("Onde estamos") muda de verdade.
8. **"Onde estamos" é UMA seção só — SUBSTITUA o conteúdo dela, nunca empilhe outra `## Onde
   estamos` acima/abaixo.** A narrativa completa (achados, decisões, números) já vive no `##
   Resultado` da task fechada — aqui entra só o ponteiro mínimo: task(s) fechada(s) nesta rodada (1
   linha) + "Pegáveis a seguir". Se ao editar você notar mais de uma seção dessas, consolide numa só.

## Onde estamos (atualizado 2026-10-01)

**`B18.3` fechada** — `ZERO`/`ZERO_zt0`/`MOVA`/`MOVAZ` (47 encodings) decodificam e executam contra o
banco `ZA` da B18.1, endereçamento de tile/slice reusável (`Aarch64MatrixTileAddressing`), 100% JaCoCo,
palavras conferidas contra `aarch64-none-elf-as`. **`docs/COBERTURA-ISA.md` não mudou** (achado: a
ferramenta marca `sme.decode` inteiro como "não aplicável" enquanto ele for `NOT_IN_ANY_PRESET` — só a
**B18.13** liga a curadoria por versão, como já fez para `sve.decode` na B17.26; implementação real já
pronta, só falta a medição reconhecer). Ver **Resultado** na task para detalhe.

**`B17.29` fechada** (resíduo do fechamento SVE da B17.26) — corrigido o gate `SVE2` indevido que
escondia `SMMLA`/`USMMLA`/`UMMLA` (só exigem `SVE`+`FEAT_I8MM`) e triados os ~30 `❌` restantes de
`sve.decode`: **nenhum era gap real**, todos falso-negativo do medidor (`DecodeTreeSpec` ignorava bits
só alcançáveis via `%extrator` do QEMU, ficavam sempre `0`; ganhou campo sintético com slot PRÓPRIO em
`FILL_STRATEGIES` para nunca retroagir sobre as estratégias antigas — ver **Resultado** na task para o
quase-regressão achado em `VCVT_F16_F32` do NEON e como foi evitado). Global **98%→99%**, A64 por
versão `ARMv9.0-A`-`ARMv9.5-A` **95%→99%**, grupo SVE/SVE2 **84-85%→96-97%**. `sme.decode` segue o
ÚNICO grupo `NOT_IN_ANY_PRESET` da tabela (mas já tem decode/execução reais desde a B18.3 acima).

**Protocolo (a pedido do usuário, sessões estourando orçamento de contexto em ~15 tasks/semana):** G5
(`tasks/README.md`) agora é condicional — pula suites de gbaemu/ndsemu quando o diff fica só em
`decoder64`/`executor64`/`ir64`/`codegen64`/`core64`/`Sve*`/`Sme*` (código que nenhum dos dois
consumidores executa); fora dessa lista, G5 continua obrigatório inteiro. JaCoCo virou passo fixo de
`Validação` no template de task (não é mais pedido manual). Regra 2 desta fila agora proíbe `Read`
de fonte >~1500 linhas inteiro — Grep+offset primeiro. **`E15`** (nova, [REFINAR]) abre a causa
estrutural: `Aarch64Decoder`/`IrOp`/`Ir64Op`/`AdvSimdLanes` são citados por quase toda task de
decoder/IR e sozinhos já são caros de carregar.

**⚠️ "tabela 100%" NÃO é o gatilho da `1.4.0`**: a regra reservada exige 100% de TODA a arquitetura ARM alvo. Seguem
abertos: **B18.4+** (SME, 576 encodings restantes de `sme.decode`, ainda `NOT_IN_ANY_PRESET` na MEDIÇÃO — ver
achado da B18.3 acima, a implementação real já começou), **B20** (perfil R: PMSA/MPU), **B21** (ARMv1-v3, 26 bits)
e as dimensões 2/3 do `ROADMAP-100-ARM.md` (JIT nativo, Truffle).

**Pegáveis a seguir** (specs já escritas, dependências satisfeitas): **`B18.4`** em diante (SME:
memória `LD1`/`ST1` de tile, outer product, SME2 multi-vector; `SVCR`/`ZA`/streaming/`MOVA`/`ZERO`
já têm efeito), **`B21.2`** em diante (modelo de 26 bits, Opção c), `E14`, `E15` ([REFINAR] —
decompor em sub-tasks executáveis), `C12.5`/`C12.10`. `B20.9` segue bloqueada no usuário. Pendências
nomeadas da B18.2: ligar `FEAT_SME_FA64` a preset(s); `ResetSVEState` na troca AArch64↔AArch32 com
`SM=1`. Conferir dependências no `INDICE.md` antes de pegar.

**Achados de processo ainda abertos, não resolvidos** (documentados nas specs para quem pegar a task
resolver, não bloqueiam nada além de si mesmos): bug G8 em `VfpDecoder` (não checa `bits[31:28]`,
`VSEL`/`VMAXNM`/etc. misdecode sob `ARMV7A`/T32 — spec da B14.4 corrige); `Thumb2NocpDecoder` (B15.2)
reivindica todo o espaço MVE sob `M_PROFILE` — B16 precisa registrar decoders ANTES dele na lista.
