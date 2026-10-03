# E17 — `COBERTURA-ISA.md`: medir **o que** decodifica, não só **se** decodifica (A64)

**Trilha:** E · **Repo:** arm-jitter · **Depende de:** — (achado das E15.9b/E15.9c) · **G5:** não
(só `core/src/test/.../tools/`, `docs/` e scripts) · **Status:** ⬜ — spec escrita em 2026-10-03 a
pedido do usuário ("crie a task e spec dentro dessa trilha ou vai se perder").

## Contexto

`IsaCoverageReport#probeAarch64` (`core/src/test/java/dev/vitorsilverio/armjitter/tools/
IsaCoverageReport.java:1238`) monta palavras de amostra de cada linha do `a64.decode` (as
`FILL_STRATEGIES`) e marca `✅` se **alguma** delas devolve `decode(...) != null`. Nunca olha **qual**
instrução saiu. O único freio é a lista manual `AARCH64_MISDECODED` (`⚠️`), que só contém o que
alguém já achou por acaso.

Isso já escondeu misdecodes reais quatro vezes, todos medidos `✅`:

| Task | O que a tabela dizia | O que o decoder fazia |
|---|---|---|
| B19.11e | `FSCALE_h` ✅ | outra instrução FP |
| B19.24 | `FAMAX_h` ✅ | outra instrução FP |
| E15.9b | `FADDP`/`FMAXP`/`FMINP`/`FMAXNMP`/`FMINNMP` `_v` `_h` ✅ | `INS` (AdvSIMD copy) |
| E15.9b | 38 células de ARMv8.0/8.1 (`FDIV_v`/`FMUL_v`/… `_h` sem `FEAT_FP16`) ✅ | `INS` |

A E15.9c fechou o lado "aceita o que não existe" no espaço AdvSIMD/FP escalar (guarda
`AdvSimdFpResidueG8Test`: contagem + SHA-256 do conjunto aceito, conferido no `objdump`). Ela **não**
pega uma palavra válida decodificada como **outra** instrução válida — o conjunto aceito não muda,
só o record que sai. Na E15.9c o usuário escolheu a impressão do conjunto aceito e esta checagem
(alternativa "b") ficou registrada como candidata a task própria: é esta.

## Objetivo

Toda célula `✅` do A64 em `docs/COBERTURA-ISA.md` passa a significar "decodifica **como a
instrução da linha**". Misdecode vira `⚠️` (ou falha de teste) automaticamente, sem depender de
alguém achar por acaso.

## Inclui

1. **Medição primeiro (antes de qualquer desenho final).** Script em `e17-scripts/` que, para cada
   linha A64 (`a64.decode`, `sve.decode`, `sme.decode`) e cada palavra de amostra que o decoder
   aceita, grava `nome-QEMU · palavra · record devolvido (classe + campo op/enum, se houver)` e
   cruza a palavra com o `aarch64-none-elf-objdump` do devkitA64 (mesma técnica de
   `e15.9b-scripts/objdump-residuo.sh`). Saída: tabela `nome-QEMU · mnemônico do objdump · record`.
   Números no `## Resultado`: quantas linhas, quantos mnemônicos `objdump` ≠ nome-QEMU, quantos
   pares nome→record ambíguos.
2. **Assinatura esperada por linha, versionada.** Arquivo gerado + revisado
   (`docs/isa-a64-assinaturas.tsv`, nome a confirmar na execução): `nome-QEMU#ocorrência →
   record esperado` (classe e, quando o record tem enum de operação, o valor — ex.
   `FpArithmeticPairwise/MAXNM`). Gerado pelo script do item 1; cada linha aceita só depois de o
   mnemônico do `objdump` bater com o nome-QEMU. Mnemônicos e nomes de classe são fatos/nomes
   nossos — o arquivo NÃO copia o `.decode` do QEMU (GPL), mesmo cuidado de `gerar-cobertura-isa.sh`.
3. **`probeAarch64` confere a assinatura.** Palavra aceita com record diferente do esperado ⇒
   `⚠️` (`Status.FALLBACK`), não `✅`. `AARCH64_MISDECODED` vira redundante para o A64: decidir na
   execução se some ou fica só como comentário histórico.
4. **Guarda de consistência** (teste): toda linha A64 do inventário fixado (`QEMU_REV`) tem
   assinatura no TSV (ou motivo explícito de ausência); entrada órfã no TSV quebra o teste.
5. Cada misdecode que a medição achar: **não** corrigir aqui — vira `⚠️` na tabela e entra numa
   task de correção por família (mesma disciplina da E15.9b → E15.9c), salvo correção trivial de uma
   linha que o usuário aprovar na hora.

## Não inclui

- Colunas de 32 bits (`ArmDecoder`/`Thumb*Decoder`/VFP/NEON/MVE): mesmo problema, task irmã depois
  desta (o devkitARM tem `arm-none-eabi-objdump` para a mesma técnica).
- Conferir operandos/campos (registradores, imediatos) — só a identidade da instrução. Campo errado
  com instrução certa é trabalho dos testes de decoder por família.
- Semântica (executor) — a tabela continua não provando semântica (ver "O que ✅ NÃO significa" no
  cabeçalho do `COBERTURA-ISA.md`).

## Especificação / decisões a tomar na execução

- **Granularidade da assinatura:** só a classe do record é fraca (`FpArithmeticThreeSame` cobre
  `FADD`/`FSUB`/`FMUL`/…); classe + enum `op` resolve a maioria. Records sem enum (ex.
  `AdvSimdMoveOp64.Extract`) ficam só com a classe. Medir no item 1 quantos ficam ambíguos antes de
  fixar o formato.
- **Aliases do `objdump`:** o `objdump` imprime o alias preferido (`mov` para `ORR`/`DUP`/`INS`/
  `UMOV`, `cmp` para `SUBS`, `lsl` para `UBFM`…). O cruzamento nome-QEMU × mnemônico precisa de uma
  tabela de aliases pequena e explícita; o que não casar vai para revisão manual, nunca para
  "ignorado".
- **Várias estratégias de preenchimento:** hoje basta UMA palavra aceitar. Com a assinatura, a
  regra proposta é: toda palavra aceita tem de bater a assinatura (uma que decodifica como outra
  coisa é misdecode mesmo que outra estratégia acerte).
- **Preset:** a assinatura vale por linha, independente da coluna — mas uma linha cuja feature não
  está no preset da coluna pode cair noutra instrução real (foi o caso das 38 células da E15.9b): é
  exatamente o que a checagem precisa pegar.

## Passos

1. Ler `IsaCoverageReport` (`probeAarch64`, `FILL_STRATEGIES`, `encode`, `AARCH64_MISDECODED`,
   `requireFirst`), `gerar-cobertura-isa.sh` e os scripts `e15.9b-scripts/`/`e15.9c-scripts/`.
2. Item 1 (medição) → registrar números → **parar e mostrar ao usuário** antes de fixar o formato do
   TSV (o tamanho do resíduo decide se a correção cabe nesta task ou vira tasks por família).
3. Itens 2–4.
4. Regenerar `docs/COBERTURA-ISA.md`; toda célula que sair de `✅` vai listada no `## Resultado`
   com o record que saiu e o mnemônico real.

## Aceite

- `./gerar-cobertura-isa.sh` usa a assinatura; diff de `docs/COBERTURA-ISA.md` explicado célula a
  célula.
- Teste de consistência do TSV verde; regressão: as palavras de `FSCALE_h`/`FAMAX_h`/`F*P_v _h`
  decodificadas com o código ANTERIOR às correções delas (record errado) seriam `⚠️` — conferir com um
  teste que injeta assinatura trocada.
- JaCoCo nas classes tocadas (regra do `tasks/README.md`).

## Armadilhas

- `FEAT_SME` embrulha AdvSIMD em `StreamingRestricted` — desembrulhar antes de comparar a classe.
- Formas `_h` vs `_sd` e escalar vs vetorial reusam o mesmo record com `esz`/`scalar` diferentes:
  se a assinatura não incluir esses campos, `FADDP_s` e `FADDP_v` ficam indistinguíveis — decidir
  conscientemente (item "granularidade").
- `objdump` desmonta com todas as features ligadas e pode não conhecer extensões recentes (FP8/LUT/
  SME2p1) — conferir no `a64.decode` antes de chamar divergência de bug.
- `awk` do Git Bash com saída Java CRLF: `tr -d '\r'` antes de comparar (armadilha da E15.9c).
