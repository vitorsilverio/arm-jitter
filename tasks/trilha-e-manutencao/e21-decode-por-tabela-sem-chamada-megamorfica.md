# E21 — Decode A64 por tabela sem chamada megamórfica

**Trilha:** E · **Repo:** arm-jitter · **Depende de:** E15 inteiro ✅ (até a E15.15g) · **G5:** não (só `decoder64/`)
**Status:** spec curta, criada em 2026-10-06. **Não pegar antes do fim do E15** (decisão do usuário: primeiro
encolher arquivos e tirar repetição, depois perf). Refinar com o bench de ponta a ponta da E15.15g em mãos.

## Contexto

Desde a E15.10, cada classe que virou `DecodeTable` decodifica ~7–9 ns mais devagar que a cascata antiga.
Medições: E15.10 (+8 ns), E15.11 (+9 ns), E15.15a (`AdvSimdCopyPermuteDecodeBench` 28,5 → 32,8 ns), E15.15b
(`AdvSimdThreeSameDecodeBench` inteiro 26,5 → 33,4 ns). A causa medida é a chamada `row.build().decode(word,
address)` do `DecodeTable#decode`: um construtor-lambda por linha deixa a chamada megamórfica, sem inline. Na E15.10,
achatar os arrays e criar sub-tabela por bucket não resolveram. O `step` interpretado não sente isso por causa do
cache da E15.10b; quem sente é o decode frio (o primeiro passo por um código, código automodificável, compilação JIT).

## Objetivo

Recuperar o tempo de decode da cascata (ou superá-lo) sem perder o formato declarativo das linhas (features como
coluna, teste de sobreposição, oráculos).

## Candidatas (o experimento decide)

1. **Linha acha, `switch` constrói:** cada linha ganha um id; cada `*Rows` expõe `static build(int id, int word,
   long address)` com `switch` denso (chamadas diretas, inlináveis). Primeiro passo: experimento só na `advSimdTable`.
2. **Bytecode gerado (ClassFile API, Java 25)** em `forArchitecture`: `if`/`new` diretos por preset. Só se a 1 não
   bastar; exige teste de equivalência com a tabela interpretada.
3. **Cache palavra → op** no `Aarch64Decoder` (mapeamento direto; linhas que leem `address` ficam fora por flag).
   Independente das outras, soma ganho no decode frio; conferir imutabilidade das ops.
4. **Balde grande na `advSimdTable`** (achado da E15.15e): a chave é a interseção das máscaras de TODAS as linhas,
   e na `advSimdTable` isso dá só 5 bits (`keyMask 30208400`, 32 baldes). Com 506 linhas, o balde do
   two-register misc tem 63 linhas (eram 20 antes da E15.15e) e o decode dele foi de 26,7 para 41,1 ns. Já as outras
   famílias ficaram 3–5 ns mais rápidas. A chamada megamórfica não explica isso: a causa é a varredura linear. Saídas
   possíveis: chave em dois níveis (sub-tabela por balde com chave própria, escolhida sobre as linhas DAQUELE balde)
   ou uma chave que aceite bit livre em parte das linhas (a linha entra nos dois baldes daquele bit). Medir com
   `e15.15e-scripts/BucketProbe.java` (tamanho do balde por palavra, via reflexão).

## Aceite (a refinar)

- Benches de decode das E15.10–E15.15 ≤ valor da cascata antiga, A/B intercalado.
- Oráculos das E15.* sem diff; suítes e `jacoco:check` verdes.

## Armadilhas

- Bench de decode: a op tem que ESCAPAR e a rodada tem que ser método próprio (E15.10).
- O ruído da máquina chega a 5–6%: rodadas intercaladas, o melhor de N, nada pesado em paralelo.
