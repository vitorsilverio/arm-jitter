# E14 — Cobertura de teste de NEON em `IrBlockExecutor#execute`/`AsmNativePolicy` — família inteira, não só cripto

**Trilha:** E · **Repo:** arm-jitter (+ revalidação G5) · **Depende de:** —
**Status:** ⬜ (registrada 2026-09-24, achado da auditoria JaCoCo pós-B13.23)

## Contexto

Auditoria JaCoCo pedida pelo usuário ao fechar a **B13.23** (NEON cripto de três registradores A32)
achou 2 gaps reais, mas **não específicos daquela task** — são pré-existentes, idênticos nos
"irmãos" `NeonCryptoAes`/`NeonCryptoSha` (B13.15) e, por amostragem, aparentam se repetir em toda a
família NEON de `IrOp` (B13.1-B13.22):

1. **`IrBlockExecutor#execute(IrBlock, ArmCore)`** — o `switch` sobre `IrOp.Kind` (inteiro) que essa
   sobrecarga usa (distinto do `switch` sobre o tipo `sealed` de `executeOp`, esse sim exercitado
   pelos testes unitários de decoder). Toda entrada `NEON_*` amostrada aparece `nc` (não coberta) no
   relatório — nenhum teste roda um `IrBlock` INTEIRO (não uma `IrOp` avulsa) contendo uma
   instrução NEON. Hipótese a confirmar: nenhum ROM/corpus de teste real (gbaemu/ndsemu/armbox)
   ainda usa NEON (`ADVANCED_SIMD` não é declarada em nenhum preset desses consumidores hoje — só
   `ARMV7A_NEON`, que ainda não tem hospedeiro real, B13.22).
2. **`AsmNativePolicy`** — a classe inteira tem 576 instruções (contagem JaCoCo `INSTRUCTION_MISSED`
   do relatório de 2026-09-24) não cobertas; toda entrada `NeonXxx ignored -> false` amostrada
   (`NeonCryptoAes`/`NeonCryptoSha`/`NeonCryptoShaThree`) é `nc`. Nenhum teste afirma que a política
   RECUSA emitir nativamente para NEON — o comportamento provavelmente está correto (é `false` para
   toda a família, decisão documentada desde B13.4), mas não está PROVADO por teste, só por leitura
   de código.

Nenhuma das duas classes tem 100% de cobertura hoje nem é razoável zerar isso task por task — cada
task de B13.x fechada não vinha com o hábito de testar o caminho de `IrBlock` completo nem
`AsmNativePolicy` diretamente (o padrão do épico B13 sempre testou via `executeOp`/decode, nunca via
`execute(IrBlock, ...)` nem via `AsmNativePolicy.supports`). Fechar isso é trabalho de manutenção
transversal, não de uma sub-task de arquitetura — daí a trilha E.

## Objetivo

Medir com precisão (JaCoCo) o tamanho real do gap nas duas classes, decidir a estratégia de teste
(provavelmente: 1 teste parametrizado por `IrOp.Kind` da família NEON, construindo um `IrBlock` de
1 instrução + `Fetch`/`Cycle` mínimos e chamando `execute`, mais 1 teste que itera todo
`allKindConstants()` do tipo NEON e afirma `AsmNativePolicy.supports(...) == false` — espelhando o
padrão de `TruffleCodeEmitterSupportsCoherenceTest`) e fechar até 100% nas duas classes PARA A
FAMÍLIA NEON (não para o projeto inteiro — outras famílias/`Kind` fora de NEON com gaps ficam de
fora, a menos que a medição mostre que são poucos e baratos de fechar junto).

## Inclui

1. Rodar `mvn -o -pl core test` + ler `core/target/site/jacoco/jacoco.csv`/HTML e confirmar,
   linha por linha, quais `Kind` NEON estão `nc`/`pc` nas duas classes (não assumir a partir da
   amostra desta descoberta — medir de novo, completo).
2. Teste(s) novo(s) fechando o gap medido — reuso do padrão `sampleOp`/`allKindConstants` de
   `TruffleCodeEmitterSupportsCoherenceTest` é o candidato natural (já existe, já é reflexivo sobre
   `IrOp.Kind`).
3. Reavaliar se cabe reusar a MESMA lista de `IrOp.Kind` sem nó Truffle (os "116"/"117" descobertos
   documentados naquele teste) como proxy da família NEON, ou se a enumeração tem que ser
   independente (nomes começando com `Neon`, verificado por reflexão do próprio `record`).
4. `## Resultado` documentando a cobertura ANTES/DEPOIS nas duas classes (percentual do relatório
   JaCoCo), não só "os testes passam".

## Não inclui

- Fechar gaps de `IrBlockExecutor#execute`/`AsmNativePolicy` fora da família NEON (outros `Kind` não
  amostrados nesta auditoria) — se a medição do passo 1 achar poucos e óbvios, decisão de incluir ou
  não fica para quem executar, documentada no `## Resultado`.
- Mudar comportamento de `AsmNativePolicy` (ela já recusa NEON corretamente, `false` documentado) —
  só provar isso com teste.
- MVE (`Kind` `MVE_*`) — mesma classe de gap provável, mas família diferente (B16), fora do escopo
  desta task; se a medição do passo 1 mostrar que MVE está no mesmo barco, registrar achado e
  decidir sequenciamento, não misturar nesta sessão.

## Passos

1. Medir com JaCoCo (ver Inclui #1).
2. Desenhar e escrever o(s) teste(s).
3. `mvn -o test` + G5 (gbaemu/ndsemu/armbox; virtual-arm-box/n3dsemu congelados).
4. `INDICE.md`, `## Resultado`, commit `E14: ...`, `git push`.

## Aceite

- `docs/site/jacoco` (ou a métrica equivalente) mostra 100% de linha/branch para as entradas `Neon*`
  de `IrBlockExecutor#execute` e `AsmNativePolicy` — ou, se a medição achar um subconjunto genuína
  e documentadamente impossível de exercitar (ex.: código morto real, não gap de teste), isso vira
  achado nomeado no `## Resultado`, não silêncio.
- `mvn -o test` verde + G5 verde.
- Zero mudança de comportamento (só testes novos).

## Achado de origem

Registrado ao fechar a **B13.23**: `IrBlockExecutor#execute` linha `NEON_CRYPTO_SHA_THREE_REGISTER`
e `AsmNativePolicy` linha `NeonCryptoShaThree ignored -> false` apareceram `nc` no relatório JaCoCo
de 2026-09-24 — confirmado que as linhas irmãs `NEON_CRYPTO_AES`/`NEON_CRYPTO_SHA` (B13.15) têm
EXATAMENTE o mesmo padrão, ou seja, não é regressão da B13.23, é lacuna estrutural do épico B13
inteiro nunca fechada.
