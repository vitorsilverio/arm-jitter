# E11 — Fixar a revisão do inventário do QEMU (a tabela de cobertura mede um alvo móvel)

**Trilha:** E · **Repo:** arm-jitter · **Depende de:** —
**Status:** ⬜

## Contexto

`docs/COBERTURA-ISA.md` é gerada por medição contra os arquivos `decodetree` do QEMU, baixados por
`gerar-cobertura-isa.sh`. Duas linhas do script, juntas, criam um problema que ninguém tinha notado:

```sh
BASE_URL="https://raw.githubusercontent.com/qemu/qemu/master/target/arm/tcg"
...
if [ ! -f "$DECODE_DIR/$file" ]; then
    curl -sfL "$BASE_URL/$file" -o "$DECODE_DIR/$file"
fi
```

1. **A origem é `master`** — um alvo em movimento, não uma revisão.
2. **O download só acontece se o arquivo não existir**, e `target/` é ignorado pelo git (`.gitignore:1`).

Consequência: **o resultado de `./gerar-cobertura-isa.sh` depende de quando a máquina que roda baixou
os `.decode` pela primeira vez.** Uma sessão com `target/` quente mede contra o QEMU de agosto; outra
que rodou `mvn clean` (ou um clone novo, ou a CI) mede contra o QEMU de hoje. Os dois gravam no MESMO
arquivo versionado, e a diferença aparece como se fosse mudança de cobertura do arm-jitter.

Nada em lugar nenhum registra qual revisão gerou a tabela vigente.

### O que a medição desta spec encontrou (2026-09-03)

Baixando os 13 `.decode` frescos e regenerando **sem mudar uma linha de `core/`**:

| Grupo | Inventário antes | Agora | Δ |
|---|---:|---:|---:|
| `t16.decode` | 86 | **87** | +1 |
| `sve.decode` | 929 | **947** | +18 |
| `sme.decode` | 623 | **651** | +28 |
| Global (células aplicáveis) | 18164 | **18171** | +7 |

E a cobertura por arquitetura muda:

| Coluna | Antes | Agora |
|---|---|---|
| v4T | **100%** (206/206) | **99%** (206/**207**) |
| v5TE | **100%** (221/221) | **99%** (221/**222**) |
| v6K / MPCore / v7-A | 100% | 100% (denominador +1, suportadas +1) |
| v6-M | 88% (82/93) | 88% (83/94) |
| v7-M | 96% (322/333) | 96% (323/334) |

**A linha nova de `t16.decode` é `MAYBE_UNDEF_T1_HINT`** (`1011 1111 ---- 0000`), introduzida pelo
commit **`2931a675e9d3fcddedf673509fe9759955fc616d`** do QEMU (2026-08-21), *"target/arm: Make Thumb
T1 hint space UNDEF before v6T2"*. Ela mede `❌` em v4T e v5TE.

⚠️ **Isso invalida a manchete da B22.6** — *"A32/T16/T32/VFP com `0 ❌` e `0 ⚠️`"* — que a
`FILA-EXECUCAO.md` e o `b22-plano-residuos-32-bits.md` repetem como fato fechado. Com o inventário
atual, T16 tem 1 `❌` em duas colunas. **Não é uma regressão do arm-jitter**: é o inventário que
cresceu. Mas o texto do projeto está desatualizado, e é exatamente o tipo de afirmação que apodrece
em silêncio — a mesma classe de problema que a C12.1 corrigiu no Javadoc de `Ir64NativePolicy`.

**Leitura obrigatória**: `gerar-cobertura-isa.sh` INTEIRO (28 linhas), o Javadoc de
`core/src/test/.../tools/IsaCoverageReport` (seção "Licença" — a razão de os `.decode` não serem
versionados), `docs/isa-nao-aplicavel.tsv` (cabeçalho: formato e regra de curadoria), a **B22.6**
(`## Resultado`) e a **B9.12** (`## Resultado` — ver o item 3 abaixo).

## Objetivo

A tabela de cobertura passa a ser **reprodutível**: qualquer máquina, a qualquer momento, gera
`docs/COBERTURA-ISA.md` byte a byte igual a partir do mesmo commit do arm-jitter. E o inventário
deixa de mudar por baixo de quem mede.

## Inclui

1. **Revisão fixada** em `gerar-cobertura-isa.sh`: `BASE_URL` passa a apontar para um **SHA de
   commit** do QEMU, não `master`. Usar
   `2931a675e9d3fcddedf673509fe9759955fc616d` (2026-08-21) ou um commit posterior escolhido
   deliberadamente — mas **um SHA**, nunca um branch.
   - Uma variável no topo (`QEMU_REV=...`) com um comentário dizendo o que ela é, quando foi
     escolhida e como bumpá-la.
   - **O cache local deixa de esconder a divergência**: se `target/isa-decode/` tiver arquivos de
     outra revisão, o script tem que baixar de novo ou avisar. Forma sugerida: gravar a revisão em
     `target/isa-decode/.rev` e re-baixar tudo quando não bater. Decidir no código e registrar.
2. **A revisão fica VISÍVEL na tabela**: `IsaCoverageReport` passa a escrever, no cabeçalho de
   `docs/COBERTURA-ISA.md`, a revisão do inventário usada (recebida por argumento ou lida do `.rev`).
   Sem isso, a próxima sessão continua sem saber contra o que a tabela vigente foi medida.
3. **Absorver a deriva já detectada**, regenerando a tabela com a revisão fixada e resolvendo a linha
   nova:
   - **`MAYBE_UNDEF_T1_HINT` em v4T/v5TE**: o próprio commit do QEMU diz que o espaço de hint T1
     *"used to UNDEF in Armv5, and so the hint insns and the NOP region must all UNDEF before
     v6T2"*. Ou seja: **não existe antes do ARMv6T2** ⇒ é caso de curadoria em
     `docs/isa-nao-aplicavel.tsv` (com `grupo` = `t16.decode`), citando o commit como fonte da
     versão — exatamente a regra do cabeçalho daquele arquivo ("a única entrada legítima é 'esta
     versão de arquitetura ainda não foi implementada, com a fonte que prova a versão real que a
     introduziu'").
   - **⚠️ Investigar antes de curar (ver item 4)**: se o espaço de hint T1 é v6T2+, a curadoria
     correta pode ser mais larga do que v4T/v5TE.
   - **`sve.decode` +18 e `sme.decode` +28**: nenhuma decodifica (os dois grupos são
     `NOT_IN_ANY_PRESET`), então o efeito é só o tamanho do inventário. **Atualizar os números
     citados nos épicos B17 (929) e B18 (623)** para os medidos, para as escadas não serem
     planejadas contra um alvo velho.
4. **Investigação escopada — o espaço de hint T16 é v6K ou v6T2?** (achado colateral desta medição.)
   Hoje a tabela mostra `YIELD`/`WFE`/`WFI`/`SEV`/`NOP` de `t16.decode` como `·` em v4T/v5TE e **`✅`
   em v6K e MPCore**; `MAYBE_UNDEF_T1_HINT` também sai `✅` nessas duas colunas. Mas o `ARMV6K` deste
   projeto **não** é ARMv6T2 (o preset deliberadamente não declara `THUMB2`, ver o Javadoc de
   `ARM11_MPCORE`), e o QEMU acabou de afirmar que o espaço UNDEF antes de v6T2.
   - Se a leitura se confirmar, **decodificar esses hints sob `ARMV6K`/`ARM11_MPCORE` é gap de
     GATING, não de denominador** — a mesma classe que a B9.11 achou em `Thumb2MiscDecoder` sob
     `ARMV6M` (10 falsos positivos) e que a B9.16 achou em `ARMV7M_FEATURES`.
   - **Regra de decisão desta task**: se o conserto for um gate por `ArmFeature` já existente
     (análogo ao `M_PROFILE_WIDE_MISC_CONTROL` da B9.11), fazer aqui. Se exigir feature nova,
     mexer em mais de um decoder, ou mudar célula em mais de 2 colunas, **PARE, documente a medição
     e abra task própria** — não estourar o escopo de uma task de manutenção de ferramenta.
   - Conferir também o `## Resultado` da **B9.12**, que registrou esses mesmos hints como "gap real"
     em v6K/MPCore: alguém os tornou `✅` depois, e é preciso saber se foi com a versão certa.
5. **Corrigir as afirmações desatualizadas** sobre "0 `❌` em 32 bits": `tasks/FILA-EXECUCAO.md`,
   `tasks/trilha-b-arquiteturas/b22-plano-residuos-32-bits.md` (`## Meta`) e
   `docs/VALIDACAO-ARQUITETURAS.md`, se houver. A redação nova tem que dizer **contra qual revisão do
   inventário** a afirmação vale — é o que a torna verificável.

## Não inclui (com destino explícito)

- **Versionar os `.decode` no repositório.** Eles são GPL e o arm-jitter é BSD-3-Clause — a decisão
  de não versioná-los é deliberada e está documentada no Javadoc de `IsaCoverageReport`. **Fixar a
  revisão resolve a reprodutibilidade sem tocar nessa decisão.**
- **Implementar qualquer instrução nova** (incluindo o espaço de hint, se o item 4 virar task
  própria).
- **Uma política de bump automático** (CI que atualiza a revisão sozinha) — pior dos dois mundos: o
  alvo volta a se mover, só que sem ninguém olhando. O bump é manual e deliberado, por definição.
- **Mexer nas escadas de B17/B18** além de corrigir os números de tamanho citados.

## Especificação

### Por que fixar, e não "sempre baixar o `master`"

Sempre baixar tornaria a tabela reprodutível **no tempo**, mas não entre commits: um `git checkout`
de um commit antigo do arm-jitter geraria uma tabela diferente da que aquele commit versionou, e
todo diff de `docs/COBERTURA-ISA.md` misturaria "o que mudou no arm-jitter" com "o que mudou no
QEMU". O valor da tabela vem de o diff significar **uma coisa só**.

Fixar a revisão também dá um lugar honesto para o crescimento do ARM aparecer: um bump de `QEMU_REV`
vira um commit próprio, com a tabela regenerada e o delta explicado — inventário novo é **trabalho
novo descoberto**, não regressão (regra máxima do `tasks/README.md`).

### O bump como rito

Documentar no cabeçalho do script (e no `## Resultado`) o procedimento:

1. trocar `QEMU_REV`;
2. `./gerar-cobertura-isa.sh`;
3. ler o diff de `docs/COBERTURA-ISA.md` **linha a linha** — cada `❌` novo é uma instrução que o ARM
   ganhou e o arm-jitter ainda não tem: vira task, nunca exclusão;
4. commit separado, só com o bump e a tabela.

## Passos

1. Ler "Leitura obrigatória". Guardar `docs/COBERTURA-ISA.md` ANTES.
2. Fixar `QEMU_REV` + invalidação do cache por `.rev`.
3. `IsaCoverageReport` escreve a revisão no cabeçalho da tabela.
4. Regenerar; conferir que o delta é EXATAMENTE o desta spec (t16 +1, sve +18, sme +28) — se for
   diferente, o QEMU andou de novo desde 2026-09-03: registrar os números reais.
5. Item 4 (investigação do espaço de hint) e, conforme a regra de decisão, curadoria ou gate.
6. Curadoria de `MAYBE_UNDEF_T1_HINT` em `docs/isa-nao-aplicavel.tsv`.
7. Atualizar os textos desatualizados (item 5) e os tamanhos de B17/B18.
8. `mvn -o test` (JBR 25) + G5 nos 5 consumidores.
9. `INDICE.md` da trilha E (linha E11), `## Resultado` nesta task, commit `E11: …`, `git push`.

## Aceite

- **Reprodutibilidade provada**: apagar `target/isa-decode/` inteiro, rodar `./gerar-cobertura-isa.sh`
  duas vezes, e obter `docs/COBERTURA-ISA.md` **byte a byte idêntica** nas duas — e idêntica à
  versão commitada.
- **Cache obsoleto é detectado**: com `target/isa-decode/` populado por outra revisão, o script
  re-baixa (ou falha com mensagem clara) em vez de medir contra arquivos velhos em silêncio. Teste
  manual, descrito no `## Resultado`.
- **A revisão aparece** em `docs/COBERTURA-ISA.md`.
- **`MAYBE_UNDEF_T1_HINT` resolvido** com fonte citada, e o resultado explicado: quais colunas
  ficaram `·`, quais `❌`, e por quê.
- **Item 4 concluído com veredito explícito**: ou o gate foi corrigido (com teste de regressão
  negativa, padrão B9.11/B9.16), ou há uma task nova aberta com a medição — **não é aceitável fechar
  E11 com o item 4 em aberto e sem registro**.
- **Os números de B17/B18** atualizados para os medidos.
- **Nenhuma afirmação de "0 `❌`" sobreviveu sem qualificação** de revisão do inventário.
- `mvn -o test` verde + G5 verde nos 5 consumidores. Mudança em `core/src/main` só se o item 4 exigir
  gate — nesse caso, zero-diff nos consumidores (nenhum é ARMv6K com hints Thumb).

## Validação

`mvn -o test` no arm-jitter (JBR 25), `mvn -o install`, `mvn -o test` nos 5 consumidores,
`./gerar-cobertura-isa.sh` reprodutível a partir de `target/` limpo.

## Armadilhas

1. **Não confundir crescimento do inventário com regressão do arm-jitter.** O `❌` novo em v4T/v5TE
   não veio de nenhum commit deste repositório. Escrever isso no `## Resultado` de forma inequívoca:
   a próxima sessão que vir "v4T 100% → 99%" no histórico vai procurar o culpado errado.
2. **O `.decode` não pode ser commitado** (GPL × BSD). Fixar a revisão é a alternativa; se em algum
   momento parecer mais fácil "só versionar os arquivos", é a decisão errada e está documentada.
3. **Curadoria exige a FONTE da versão.** Para `MAYBE_UNDEF_T1_HINT` a fonte é o commit
   `2931a675e9…` do QEMU + o ARM ARM sobre o espaço de hint T1. Uma linha de TSV sem fonte é
   exatamente o que a regra máxima proíbe.
4. **`grupo` = `t16.decode` é obrigatório** na entrada da TSV: sem ele a exclusão casa por nome
   sozinho e pode atingir outro arquivo (a razão de existir da coluna, ver B9.15).
5. **O item 4 pode ser maior do que parece.** Se ele começar a puxar `IT`/`CBZ`/outros hints, é a
   hora de parar e abrir task — a regra de decisão está no item 4 e existe para esta sessão não
   virar uma investigação aberta de 80 tool-calls.
6. **Bumpar a revisão e "consertar" a tabela até ela fechar** é o antipadrão. Se um `❌` novo aparece,
   ele é trabalho — vira task, não vira linha de TSV sem fonte.

## Não fazer

- Não versionar os arquivos `.decode`.
- Não deixar `master` no `BASE_URL`.
- Não implementar instrução nova.
- Não automatizar o bump.
- Não fechar com o item 4 sem veredito.
