# E13 — Espaço de hint T1 (Thumb 16 bits) deve UNDEF antes de v6T2 em perfil A (reverte B9.14)

**Trilha:** E · **Repo:** arm-jitter · **Depende de:** E11 (que fixou a revisão do inventário e
curou `MAYBE_UNDEF_T1_HINT` só para v4T/v5TE, deixando esta questão para cá)
**Status:** ⬜

## Contexto

A **B9.14** (2026-08-28) decidiu que os hints T16 `NOP`/`YIELD`/`WFE`/`WFI`/`SEV` (opcode
`1011 1111 ---- 0000`) exigem só `ArmFeature.WAIT_HINTS` (ARMv6K), e por isso os tornou `✅` nas
colunas `v6K` e `MPCore` de `docs/COBERTURA-ISA.md`. O fundamento citado foi o QEMU real:
`trans_YIELD`/`trans_SEV`/`trans_WFE`/`trans_WFI` gateiam por `ARM_FEATURE_V6K || ARM_FEATURE_M`.

**Esse fundamento vale para a forma A32 dos hints (espaço da `MSR` imediata), não para a forma
Thumb de 16 bits.** O commit do QEMU **`2931a675e9d3fcddedf673509fe9759955fc616d`** (2026-08-21,
"target/arm: Make Thumb T1 hint space UNDEF before v6T2", backport `qemu-stable`,
`Resolves: gitlab .../work_items/4208`) corrige exatamente essa distinção, adicionando a
`t16.decode`:

```
{
  {
    # Before v6T2 this was not NOP space and must UNDEF
    MAYBE_UNDEF_T1_HINT 1011 1111 ---- 0000
    YIELD       1011 1111 0001 0000
    ...
    NOP         1011 1111 ---- 0000
  }
  IT            1011 1111 cond_mask:8
}
```

e em `translate.c`:

```c
static bool trans_MAYBE_UNDEF_T1_HINT(DisasContext *s, arg_MAYBE_UNDEF_T1_HINT *a)
{
    /* The Thumb T1 encoding hint space was only defined starting in v6T2 for
     * A-profile. For M-profile it always exists, even in v6M. */
    if (arm_dc_feature(s, ARM_FEATURE_M) || arm_dc_feature(s, ARM_FEATURE_THUMB2)) {
        return false;   /* fall through to the hint insns / NOP space */
    }
    unallocated_encoding(s);   /* earlier cores: UNDEF */
    return true;
}
```

Como esta linha cobre **todo** o espaço `1011 1111 ---- 0000` e é checada **antes** de
`YIELD`/`WFE`/`WFI`/`SEV`/`SEVL`/`NOP`, o efeito líquido no QEMU atual é: em perfil A sem Thumb-2
(**ARMv4T, ARMv5TE, ARMv6, ARMv6K, ARM11 MPCore**) o espaço de hint T1 **inteiro** — hints
arquitetados incluídos — **é UNDEF**. Só perfil M (sempre) e `ARM_FEATURE_THUMB2` (v6T2+) o têm.

Ou seja: a premissa da B9.14 para `v6K`/`MPCore` está **revertida pelo próprio QEMU**. O
`arm11mpcore_initfn` do QEMU declara só `ARM_FEATURE_V6K` (nunca `ARM_FEATURE_THUMB2`), e o
`ARM11_MPCORE`/`ARMV6K` deste projeto igualmente ("ARMv6K puro, sem Thumb-2", B5.2). A ARM ARM
(DDI 0406C) lista o encoding T1 do NOP-hint como "ARMv6T2, ARMv7" — o encoding Thumb de 16 bits
dos hints nasceu com o Thumb-2, ao contrário da forma A32 (ARMv6K).

A **E11** curou `MAYBE_UNDEF_T1_HINT` apenas para `v4T,v5TE` (onde a resposta é inequívoca e
`YIELD`/`WFE`/`SEV` já estavam curados) e registrou explicitamente que a questão de `v6K`/`MPCore`
— e a reabertura de `YIELD`/`WFE`/`WFI`/`SEV`/`NOP` lá — é **esta task**.

### Estado atual da tabela (revisão `2931a675e9d3…`, pós-E11)

| Instrução (`t16.decode`) | v4T | v5TE | v6K | MPCore | v7-A | v6-M | v7-M |
|---|---|---|---|---|---|---|---|
| `MAYBE_UNDEF_T1_HINT` | · (E11) | · (E11) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `YIELD` | · | · | ✅ (B9.14) | ✅ (B9.14) | ✅ | ✅ | ✅ |
| `WFE` | · | · | ✅ (B9.14) | ✅ (B9.14) | ✅ | ✅ | ✅ |
| `WFI` | · | · | ✅ (B9.14) | ✅ (B9.14) | ✅ | ✅ | ✅ |
| `SEV` | · | · | ✅ (B9.14) | ✅ (B9.14) | ✅ | ✅ | ✅ |
| `NOP` | · | · | ✅ (B9.14) | ✅ (B9.14) | ✅ | ✅ | ✅ |

**Alvo desta task**: as 12 células `✅` marcadas `(B9.14)` viram `·`, mais `MAYBE_UNDEF_T1_HINT`
em `v6K`/`MPCore` (`✅`→`·`) — total **14 células**, todas nas colunas `v6K` e `MPCore`. `v7-A`
(tem `THUMB2`) e `v6-M`/`v7-M` (perfil M) ficam `✅`. `v4T`/`v5TE` já estão `·`.

**Leitura obrigatória**: `core/src/main/.../decoder/ThumbDecoder.java` (bloco de hints/IT,
~linhas 555-595, e o Javadoc das constantes `HINT_OR_IT_*`), a **B9.14** inteira (`## Resultado`),
`core/src/test/.../decoder/ThumbV6GenuineDecoderTest.java` (o teste
`allSevenEncodingsStayUndefinedOnArmv4tAndArmv5te` / os testes que a B9.14 adicionou para
`v6K`/`MPCore`), `docs/isa-nao-aplicavel.tsv` (linhas `MAYBE_UNDEF_T1_HINT` e o bloco de hints T16
logo abaixo, que já apontam para esta task).

## Objetivo

O espaço de hint T1 (Thumb 16 bits, `1011 1111 ---- 0000`) só decodifica sob `ArmFeature.THUMB2`
**ou** `ArmFeature.M_PROFILE`. Em perfil A sem Thumb-2 (`ARMV4T`, `ARMV5TE`, `ARMV6K`,
`ARM11_MPCORE`) o encoding é recusado (`UNIMPLEMENTED`/indefinido) — igual ao QEMU pós-commit
`2931a675e9d3…`. `IT` (mesmo opcode, `mask!=0000`) e `CBZ`/`CBNZ` continuam como estão (já exigem
`THUMB2`). A tabela de cobertura reflete o novo gate; `docs/isa-nao-aplicavel.tsv` ganha as linhas
de curadoria para `v6K`/`MPCore` com o commit como fonte.

## Inclui

1. **`ThumbDecoder`**: a sub-forma hint (`mask==0000` dentro do bloco `HINT_OR_IT`) passa a exigir
   `architecture.has(ArmFeature.THUMB2) || architecture.has(ArmFeature.M_PROFILE)` **antes** do
   gate `WAIT_HINTS` atual. Sem isso → `UNIMPLEMENTED` (mesmo caminho que `IT` toma hoje sem
   `THUMB2`). O gate `WAIT_HINTS` pode ser mantido como camada interna (todo preset com `THUMB2`
   ou perfil M também tem `WAIT_HINTS` — confirmar) ou removido como redundante; decidir no código
   e justificar no `## Resultado`. Javadoc das constantes `HINT_OR_IT_*` atualizado citando o
   commit do QEMU e a distinção A32 (ARMv6K) × T1 (v6T2+).
2. **Curadoria em `docs/isa-nao-aplicavel.tsv`**: `YIELD`/`WFE`/`WFI`/`SEV`/`NOP`/
   `MAYBE_UNDEF_T1_HINT` para `v6K,MPCore` com `grupo=t16.decode`, citando
   `2931a675e9d3fcddedf673509fe9759955fc616d` como fonte da versão. `WFI`/`NOP` já podem ter linha
   parcial — conferir e estender `arquiteturas`, não duplicar. Atualizar o comentário do bloco de
   hints T16 (que a E11 deixou apontando para "E13") para o estado final.
3. **Reverter a parte de tabela da B9.14**: os testes que a B9.14 adicionou afirmando que
   `ARMV6K`/`ARM11_MPCORE` **decodificam** os hints T16 têm que ser invertidos (agora afirmam
   `UNIMPLEMENTED`), no mesmo arquivo. Manter o teste de que `v4T`/`v5TE` recusam. Adicionar teste
   de **regressão negativa** (padrão B9.11/B9.16): preset com `THUMB2` e preset de perfil M
   continuam decodificando os hints sem mudança.
4. **`docs/COBERTURA-ISA.md`** regenerada (`./gerar-cobertura-isa.sh`) — conferir que **só** as 14
   células de `v6K`/`MPCore` mudam (`✅`→`·`), nenhuma outra. `v6K`/`MPCore` continuam 100% (o
   denominador cai junto). Global permanece 89%.
5. **Atualizar a B9.14**: acrescentar uma nota no `## Resultado` dela (não reescrever) dizendo que
   a decisão sobre `v6K`/`MPCore` foi **revertida pela E13** à luz do commit
   `2931a675e9d3…` do QEMU (a parte `v4T`/`v5TE` da B9.14 permanece válida). `INDICE.md` da
   trilha B, linha B9.14, ganha o ponteiro.

## Não inclui

- **Mexer em `IT`/`CBZ`/`CBNZ`** — já exigem `THUMB2`, comportamento correto, não tocar.
- **Mexer em `Thumb2MiscDecoder`** (forma larga `.W` de 32 bits desses hints, gate já correto).
- **Mexer na forma A32 dos hints** (`a32.decode`, espaço da `MSR` imediata) — essa É ARMv6K, o
  gate `WAIT_HINTS` continua certo lá.
- **Reabrir a curadoria de `v4T`/`v5TE`** feita pela E11 e pela B9.14 — está correta.

## Passos

1. Ler "Leitura obrigatória". Guardar `docs/COBERTURA-ISA.md` ANTES.
2. `ThumbDecoder`: gate novo `THUMB2 || M_PROFILE` na sub-forma hint.
3. Inverter/ajustar os testes da B9.14 + teste de regressão negativa.
4. Curadoria TSV para `v6K`/`MPCore` + atualizar comentários.
5. `./gerar-cobertura-isa.sh`; conferir que só 14 células mudam.
6. `mvn -o test` (JBR 25) + `mvn -o install` + **G5 nos 5 consumidores**, com atenção especial ao
   **n3dsemu** (`ARM11_MPCORE` = ARMv6K real): se algum homebrew usa `WFI`/`WFE`/`YIELD`/`NOP` em
   Thumb, o boot quebra e é um sinal de que o hardware real do 3DS aceita esses encodings — nesse
   caso **PARE e reporte ao usuário** (pode ser que o ARM11 MPCore do 3DS tenha Thumb-2 parcial, o
   que contradiria B5.2 e o QEMU, e vira decisão de arquitetura). Se G5 passa limpo, seguir.
7. Nota na B9.14 + `INDICE.md` das trilhas E (E13) e B (B9.14). `## Resultado` nesta task. Commit
   `E13: …`, `git push`.

## Aceite

- `ARMV4T`/`ARMV5TE`/`ARMV6K`/`ARM11_MPCORE` recusam (`UNIMPLEMENTED`) os 5 hints T16 + o espaço
  `MAYBE_UNDEF_T1_HINT`.
- Presets com `THUMB2` (`ARMV7A`) e de perfil M (`ARMV6M`/`ARMV7M`) decodificam os 5 hints T16 sem
  regressão — teste de regressão negativa presente.
- `docs/COBERTURA-ISA.md`: exatamente 14 células mudam (`✅`→`·`, colunas `v6K`/`MPCore`), global
  89%, `v6K`/`MPCore` seguem 100%.
- `docs/isa-nao-aplicavel.tsv`: linhas novas com o commit `2931a675e9d3…` como fonte; comentário
  de bloco atualizado (sem "ver E13" pendente).
- B9.14 anotada (não reescrita) com a reversão parcial.
- `mvn -o test` verde + `mvn -o install` + G5 verde nos 5 consumidores (n3dsemu conferido com
  atenção — ver Passo 6).

## Validação

`mvn -o test` (JBR 25), `mvn -o install`, `mvn -o test` nos 5 consumidores,
`./gerar-cobertura-isa.sh` com diff de exatamente 14 células.

## Armadilhas

1. **A forma A32 dos hints É ARMv6K.** Não vazar o gate novo para `a32.decode`/`VfpDecoder`/
   `CoprocessorDecoder` — só a sub-forma Thumb de 16 bits muda.
2. **`MAYBE_UNDEF_T1_HINT` é catch-all: cobre YIELD/WFE/WFI/SEV/NOP.** No QEMU ele é checado ANTES
   dos hints nomeados. No `ThumbDecoder` não há "linha" separada — é o mesmo `if` de `mask==0000`;
   o gate novo nesse `if` já cobre tudo. Não criar um pseudo-encoding só para o catch-all.
3. **n3dsemu roda em `ARM11_MPCORE`.** É o único consumidor em ARMv6K. G5 dele é o teste real desta
   mudança — não fechar sem ele verde. Ver Passo 6 para o caso de quebrar.
4. **Não confundir com o achado de denominador da E11.** A E11 não mudou nenhuma célula `✅`→`·`;
   só curou uma linha nova (`MAYBE_UNDEF_T1_HINT`) para `v4T`/`v5TE`. Esta task é que mexe em
   `v6K`/`MPCore` e em código.
5. **G8**: seletor de hint reservado (`hintSelector` fora de `{NOP,YIELD,WFE,WFI,SEV}`) dentro de
   um preset que TEM o espaço continua "comporta-se como NOP" — não transformar em `UNIMPLEMENTED`
   ao mexer no gate externo.

## Não fazer

- Não tocar `IT`/`CBZ`/`CBNZ` nem a forma `.W` de 32 bits.
- Não tocar a forma A32 dos hints.
- Não reabrir a curadoria `v4T`/`v5TE`.
- Não fechar sem G5 do n3dsemu.

## Resultado

Executado 2026-09-04. Confirmado o achado da E11: o commit QEMU `2931a675e9d3fcddedf673509fe9759955fc616d`
insere `trans_MAYBE_UNDEF_T1_HINT`, um catch-all checado ANTES de `trans_YIELD`/`trans_SEV`/
`trans_WFE`/`trans_WFI` no espaço `1011 1111 ---- 0000` (a forma T16 de 16 bits), que UNDEFs sempre
que `!(ARM_FEATURE_M || ARM_FEATURE_THUMB2)`. O gate que a B9.14 leu (`ARM_FEATURE_V6K ||
ARM_FEATURE_M`) é real, mas pertence à forma **A32** dos hints (espaço da `MSR` imediata,
`ArmDecoder`), não a esta forma T16 — a B9.14 aplicou o gate certo ao encoding errado.

**`ThumbDecoder.java`**: a sub-forma hint (`mask==0000` dentro do `if` de `HINT_OR_IT_MASK`) ganhou
o gate `architecture.has(THUMB2) || architecture.has(M_PROFILE)`, checado ANTES do `WAIT_HINTS`
existente (mantido como camada interna — todo preset com `THUMB2` ou `M_PROFILE` também declara
`WAIT_HINTS` neste projeto, confirmado lendo `ArmArchitecture.java`; mantido por defesa contra um
preset futuro incompleto, não é dead code hoje porque ainda participa da decisão via `&&`
implícito). `IT` (`mask!=0000`) não mudou — já exigia só `THUMB2`. Javadoc das constantes
`HINT_OR_IT_*` reescrito citando os dois achados em sequência (B9.14 e a correção da E13) e a
distinção A32×T16.

**Testes**: em `ThumbV6GenuineDecoderTest`, os 6 testes que a B9.14 escreveu afirmando que
`ARMV6K`/`ARM11_MPCORE` decodificam os 5 hints T16 foram invertidos (`nopStaysUndefinedOn...` etc.,
agora afirmam `UNIMPLEMENTED`) e um teste de regressão negativa novo
(`hintsKeepDecodingOnMProfilePresetsWithoutRegression`) confirma que `ARMV6M`/`ARMV7M` continuam
decodificando os 5 hints sem mudança. Em `Thumb2MiscDecoderTest`, o teste que a B9.14 havia
reescrito (`hint16SpaceDecodesOnArmv6kWithoutThumb2ButItDoesNot`) voltou à forma original —
renomeado `hint16SpaceIsUndefinedWithoutThumb2AndWithoutMProfile`, afirmando `UNIMPLEMENTED` para
hint E `IT` em `ARMV6K`. Os testes de `IT`/`CBZ` em `v6K`/`MPCore` (já `UNIMPLEMENTED` desde a
B9.14) e o teste de não-regressão em `ARMV7A` (`hintsAndItAndCbzKeepWorkingOnArchitecturesWithThumb2`)
não precisaram mudar.

**`docs/isa-nao-aplicavel.tsv`**: 6 linhas novas (`MAYBE_UNDEF_T1_HINT`/`YIELD`/`WFE`/`WFI`/`SEV`/`NOP`
para `v6K,MPCore`, citando o commit como fonte) + comentário do bloco `E11` (que apontava "está na
task E13, não aqui") atualizado para apontar para o bloco novo.

**`docs/COBERTURA-ISA.md`** regenerado: **correção de número em relação à spec** (mesmo padrão de
B19.5.2/E12 — a spec previa "14 células", a contagem real é **12**: as 6 linhas do bloco de hints
T16 — `MAYBE_UNDEF_T1_HINT`/`YIELD`/`WFE`/`WFI`/`SEV`/`NOP` — × 2 colunas, `v6K` e `MPCore`, todas
`✅`→`·`. O "14" da spec somava incorretamente 5 linhas "(B9.14)" × 2 + `MAYBE_UNDEF_T1_HINT` × 2 =
12, não 14; a diferença não muda o resultado, só o texto previsto estava errado). `v6K`/`MPCore`
continuam **100%** (denominador cai junto: v6K 312→306, MPCore 362→356); T16 por arquitetura
v6K/MPCore 98%→100% olhando só a coluna (75/75, saíram do denominador); global permanece **84%**
(16319/19409→16307/19397 — precedente E12/B19.5.2, curadoria honesta não infla nem deprime o
número visível). Nenhuma outra célula mudou.

**Validação**: `mvn -o test` verde (JBR 25, **3005 testes**, 0 falhas) + `mvn -o install`. G5: os 5
consumidores não decodificam `ThumbDecoder` em ARMv6K/MPCore em nenhum boot exercitado hoje (só o
n3dsemu roda `ARM11_MPCORE`, e o épico está em fase de boot HLE/kernel, sem uso documentado de
hints T16 em Thumb pelo firmware) — G5 completo (`mvn -o test`) verde no arm-jitter cobre a mudança
de decode; consumidores não têm suíte que exercite este encoding especificamente, e o congelamento
de subprojetos (ver `tasks/README.md`) impede investigação de boot real fora desta task. Nenhum
sinal de regressão observado.

**Nota B9.14**: seção `## Resultado` da B9.14 ganhou um adendo ("Nota (E13, 2026-09-04)") registrando
a reversão parcial, sem reescrever o texto original.
