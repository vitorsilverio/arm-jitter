# E1 — Traduzir todos os javadocs/comentários em inglês para português (regra G7)

**Trilha:** E (manutenção) · **Depende de:** — · **Repo:** arm-jitter · **1 sessão POR LOTE**

## Contexto

A regra do projeto é explícita (G7 do `tasks/README.md`: javadoc em português),
mas várias tasks antigas deixaram javadocs e comentários em inglês (ex.:
`BLX suffix (H=01): the second half of...` em `ThumbDecoder`, comentários de
`IrExecutionSupport`, arquivos do `codegen/`). Censo heurístico de 2026-07-16:
~15 arquivos em `src/main` + ~15 em `src/test` do `core/` (+ possivelmente
`truffle/`). Esta task traduz TUDO para português, sem mudar uma linha de código.

## Regras da tradução (INEGOCIÁVEIS)

1. **Só comentários** (`///`, `/** */`, `//`). Nenhuma mudança de código,
   identificadores, strings, mensagens de exceção ou formatação de código — o
   diff de cada arquivo deve conter APENAS linhas de comentário.
2. **Não traduzir termos técnicos**: mnemônicos e nomes de instrução (`LDREX`,
   `BL`/`BLX`, `writeback`), nomes de registradores/flags (`CPSR`, `NZCV`),
   nomes de classes/métodos/campos, citações de fonte (`ARM DDI 0406C A5.3.1`,
   `QEMU t32.decode`, nomes de encoding como `@ldstd_ri8`), e jargão consagrado
   sem tradução boa (`fallback`, `lifter`, `inline cache`, `snapshot`, `stale`).
   Traduz-se a PROSA ao redor, não o vocabulário técnico.
3. Preservar intacta a estrutura Javadoc: `{@link ...}`, `@param`/`@return`,
   `` `código` ``, blocos de exemplo. Traduzir só o texto corrente.
4. **Não converter estilo** `/** */` → `///` nesta task (mudança de estilo é
   outro assunto; aqui o diff é só idioma). Não "melhorar" comentários que já
   estão em português — zero retoque neles.
5. Comentário meio-a-meio (frase pt com trecho en): traduzir só o trecho em
   inglês, mantendo o resto byte a byte.

## Como gerar a lista de trabalho (rodar no início de CADA lote)

```bash
cd arm-jitter
grep -rlE "(//|\*).*\b(the [a-z]+|whether|Returns the|so that|instead of|must not|cannot|only if|do not|does not|should be)\b" \
  core/src/main/java core/src/test/java truffle/src 2>/dev/null | sort
```

A heurística tem falsos negativos: ao abrir um arquivo da lista, revisar TODOS
os comentários dele (não só as linhas que bateram no grep), e riscar o arquivo
do checklist abaixo. Se encontrar inglês num arquivo fora da lista durante o
lote, incluí-lo no lote corrente e registrar.

## Lotes (1 sessão nova por lote; marcar aqui ao concluir)

- [ ] **Lote 1** — `core/src/main`: pacotes `decoder/`, `ir/`, `ir/opt/`
- [ ] **Lote 2** — `core/src/main`: pacotes `codegen/` (todos os sub), `jit/`, `debug/`, `core/`, `memory/`, `arch/`, `coprocessor/`, `swi/`
- [ ] **Lote 3** — `core/src/test` inteiro
- [ ] **Lote 4** — `truffle/` (main + test) + varredura final do grep devolvendo vazio

## Validação (por lote)

1. `mvn -o test` na raiz (JBR 25) — um comentário malformado quebra compilação.
2. `mvn -o -pl core javadoc:javadoc` sem erros novos (o projeto já gera apidocs).
3. Auto-conferência do diff: `git diff --stat` + inspecionar que NENHUM hunk toca
   linha de código (se o editor reformatou algo, reverter o arquivo e refazer).
4. Commit por lote: `E1: traduz javadocs para portugues (lote N — <pacotes>)`.

## Armadilhas

- O maior risco é o "diff sujo": IDE reformatando imports/espaçamento junto.
  Editar como texto puro; conferir o diff antes do commit é parte do aceite.
- Comentários que citam mensagens/valores literais verificados em teste (ex. o
  texto de uma exceção esperada) — a CITAÇÃO fica como está; só a prosa muda.
- Blocos grandes de história de task (ex. javadoc de preset contando decisões de
  B2.x) devem ser traduzidos SEM resumir — o conteúdo é documentação de decisão,
  encurtar perde informação.
- Não usar tradutor automático em bloco cego: nomes como `carry-out`, `page walk`
  viram absurdos; traduzir frase a frase entendendo o contexto.
