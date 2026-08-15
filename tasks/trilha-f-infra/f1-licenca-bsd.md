# F1 — Licença BSD 3-Clause em todos os projetos próprios

**Trilha:** F (infra) · **Depende de:** — · **Repo:** arm-jitter, gbaemu, ndsemu, armbox, linuxbox
**Decidida pelo usuário em 2026-08-15:** BSD 3-Clause ("New BSD"), não 2-Clause, não 0BSD.

## Contexto

Nenhum dos 5 repositórios próprios tem arquivo de licença hoje (verificado: não existe
`LICENSE*` em `arm-jitter/`, `gbaemu/`, `ndsemu/`, `armbox/`, `linuxbox/`). Sem licença
explícita o código é "todos os direitos reservados" por padrão, o que (a) impede terceiros
de usar e (b) **bloqueia a publicação no Maven Central**, que exige um bloco `<licenses>`
válido no POM (ver task F5).

Esta task é a mais barata das cinco frentes e destrava F5 — por isso é a primeira da onda 4.

## Objetivo

Todo repositório próprio tem `LICENSE` (BSD 3-Clause), o POM declara a licença no formato
que o Central exige, e o README aponta para ela.

## Inclui

1. Arquivo `LICENSE` na raiz de: `arm-jitter/`, `gbaemu/`, `ndsemu/`, `armbox/`, `linuxbox/`.
2. Bloco `<licenses>` no POM de cada repo (no POM **pai** do arm-jitter, não nos módulos —
   módulos herdam).
3. Seção `## Licença` no final do `README.md` de cada repo.

## NÃO inclui (não fazer)

- **Não tocar em `melonDS/`, `libnds/`, `nds-examples/`, `busybox-armv5l`, `PokePlat/`** nem
  em nenhum outro diretório de `C:\Users\user\IdeaProjects` que seja clone/asset de
  terceiros. Só os 5 repos próprios listados acima.
- **Não adicionar cabeçalho de licença nos arquivos `.java`.** São mais de mil arquivos; o
  `LICENSE` na raiz basta e é o que o Central exige. Se um dia isso for desejado, vira task
  própria.
- Não mexer em assets binários (`gbaemu/pokefirered.gba`, `gbaemu/gba_bios.bin`,
  `ndsemu/firmware/*`, `linuxbox/testdata/*`, `armbox/testdata/*`). Eles não são cobertos
  pela licença — ver passo 4.
- Não renomear nada (o rename `linuxbox` → `virtual-arm-box` é a task F2, e é independente:
  o texto da BSD 3-Clause não cita o nome do projeto).

## Especificação

### Texto do `LICENSE` (idêntico nos 5 repos, byte a byte)

```
BSD 3-Clause License

Copyright (c) 2026, Vitor Silverio Rodrigues
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

Esse é o template oficial do SPDX `BSD-3-Clause` com os placeholders preenchidos. **Não
reescrever, não "melhorar", não traduzir** — ferramentas de detecção de licença (GitHub,
FOSSA, o próprio Central) casam esse texto literalmente.

### Bloco `<licenses>` do POM

Inserir logo depois de `<description>` (ou de `<name>`, se não houver description):

```xml
    <licenses>
        <license>
            <name>BSD-3-Clause</name>
            <url>https://opensource.org/license/bsd-3-clause</url>
            <distribution>repo</distribution>
        </license>
    </licenses>
```

Arquivos exatos a editar:

| Repo | Arquivo |
|------|---------|
| arm-jitter | `pom.xml` (o pai, `artifactId` = `arm-jitter-parent`) — **só ele**; `core/pom.xml`, `truffle/pom.xml` e `capi/pom.xml` herdam |
| gbaemu | `pom.xml` |
| ndsemu | `pom.xml` |
| armbox | `pom.xml` |
| linuxbox | `pom.xml` |

### Seção do README

Acrescentar no fim do `README.md` de cada repo (ajustando só o nome do projeto):

```markdown
## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).

Os binários de terceiros usados em testes e execução (BIOS, firmware, ROMs, kernels,
`busybox`) **não** são cobertos por esta licença e não são redistribuídos por este projeto
salvo quando a licença original permitir; ver o `README.md` do diretório correspondente.
```

O segundo parágrafo só entra nos repos que têm assets de terceiros versionados ou esperados:
gbaemu (BIOS/ROMs), ndsemu (`firmware/`), armbox (`testdata/`), linuxbox (`testdata/`).
No arm-jitter (que não tem asset nenhum) usar só a primeira linha.

## Passos

1. Criar os 5 arquivos `LICENSE` com o texto acima (LF, UTF-8, sem BOM).
2. Editar os 5 `pom.xml` inserindo o bloco `<licenses>`.
3. Editar os 5 `README.md` acrescentando a seção.
4. Rodar `mvn -o validate` em cada repo (é suficiente para provar que o POM continua bem
   formado; não precisa rodar a suíte inteira — nenhuma linha de código foi tocada).
5. Um commit **por repo**, mensagem `F1: licença BSD 3-Clause`.

## Aceite

- [ ] `LICENSE` existe nos 5 repos e o conteúdo é byte-a-byte o template SPDX acima.
- [ ] `mvn -o validate` verde nos 5 repos.
- [ ] `<licenses>` presente nos 5 POMs (no **pai** do arm-jitter).
- [ ] README de cada repo tem a seção `## Licença`.
- [ ] Nenhum arquivo `.java` foi modificado (confirmar com `git status` em cada repo).
- [ ] Índice do `tasks/README.md` atualizado (F1 ✅).

## Validação

`mvn -o validate` nos 5 repos. Não é preciso `mvn test` (G5 não se aplica: zero mudança de
comportamento).

## Armadilhas

- **`git status` antes de commitar** em gbaemu/ndsemu: esses repos têm arquivos soltos na
  raiz (probes `.java` descartáveis no ndsemu, `frame.ppm`/`hs_err_*.log` no gbaemu). Use
  `git add LICENSE pom.xml README.md` com paths explícitos — regra 6 da `FILA-EXECUCAO.md`,
  **nunca `git add -A`**.
- O ano do copyright é **2026** (ano corrente), não o ano do primeiro commit. Um único ano,
  não intervalo.
- O nome do titular é **Vitor Silverio Rodrigues** (nome completo, sem acento — é como
  aparece no e-mail de commit `vitor.silverio.rodrigues@gmail.com`). Se o `git config
  user.name` local divergir disso, **use o texto acima assim mesmo** e reporte a divergência
  ao usuário no fim da sessão; não invente uma terceira grafia.
