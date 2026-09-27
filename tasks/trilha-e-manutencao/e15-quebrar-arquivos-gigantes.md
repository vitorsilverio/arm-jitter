# E15 — Quebrar os arquivos-fonte gigantes citados por quase toda task de decoder/IR

**Trilha:** E · **Repo:** arm-jitter (+ revalidação G5 completa — toca código compartilhado) ·
**Depende de:** — · **[REFINAR]** (ver "Por que REFINAR" abaixo)
**Status:** ⬜ (registrada 2026-09-27, a pedido do usuário — sessões estourando orçamento de
contexto em ~15 tasks/semana)

## Contexto

Medido em 2026-09-27 (`wc -l` sobre `core/src/main/java`), maiores arquivos do projeto:

| Arquivo | Linhas |
|---|---|
| `decoder64/Aarch64Decoder.java` | 7741 |
| `ir/IrOp.java` | 5036 |
| `ir64/Ir64Op.java` | 4769 |
| `advsimd/AdvSimdLanes.java` | 3677 |
| `codegen/jvm/AsmBlockCompiler.java` | 2575 |
| `codegen/executor/IrSystemExecutor.java` | 2508 |
| `executor64/Ir64BlockExecutor.java` | 2281 |
| `core64/Aarch64Core.java` | 1713 |
| `ir/StandardIrBuilder.java` | 1352 |
| `decoder/ArmDecoder.java` | 1174 |
| `codegen/jvm/AsmRuntimeHelpers.java` | 1161 |
| `codegen64/jvm64/Ir64BlockCompiler.java` | 1151 |
| `decoder/VfpDecoder.java` | 1130 |

O protocolo de sessão (`tasks/README.md` regra 2) manda ler "a task + os fontes que ela cita" — e
praticamente toda task de decoder A64 (trilha B, épicos B17/B18/B19/B21) cita `Aarch64Decoder`
e/ou `Ir64Op`/`IrOp`. Ler um desses arquivos inteiro já consome uma fatia grande do orçamento de
contexto de UMA sessão; isso, multiplicado por ~15 tasks numa semana, é a causa raiz apontada pelo
usuário para o esgotamento de crédito semanal (junto com G5 incondicional e o nag manual de
JaCoCo — ambos já corrigidos no protocolo em 2026-09-27, ver `tasks/README.md` G5 e "Estrutura de
uma task"). A instrução de leitura em `FILA-EXECUCAO.md` (Grep+offset em vez de `Read` inteiro)
já mitiga o sintoma; esta task ataca a causa estrutural.

## Objetivo

Reduzir o tamanho de arquivo que uma sessão típica precisa carregar para executar uma task de
decoder/IR, sem mudar comportamento (G1/G3) e sem quebrar API pública consumida por
gbaemu/ndsemu/armbox/virtual-arm-box/n3dsemu (G3).

## Por que [REFINAR]

Cada um dos arquivos acima tem uma estratégia de split diferente e um risco diferente; não é uma
única task executável em 1 sessão. Antes de qualquer split, uma sessão de spec precisa:

1. Decidir, por arquivo, a linha de corte (ex.: `Aarch64Decoder` por família de encoding —
   `sve.decode`/`sme.decode`/AdvSIMD/escalar —, tipicamente decoders parciais compostos, não um
   split arbitrário por número de linha; `IrOp`/`Ir64Op` são `sealed interface` com centenas de
   `record` — candidato a quebrar por família em arquivos irmãos no mesmo pacote, já que Java não
   exige que todos os `permits` estejam no mesmo arquivo desde que fiquem no mesmo módulo/pacote
   conforme a regra de `sealed`).
2. Confirmar que o split não quebra `IsaCoverageReport`/`gerar-cobertura-isa.sh` (que podem
   depender de reflexão sobre nomes de classe/pacote) nem serialização de savestate (se algum
   `record` de `IrOp`/`Ir64Op` for serializado por nome/posição).
3. Ordenar os splits por risco: `AdvSimdLanes` (tabelas de dados, baixo risco) antes de
   `Aarch64Decoder`/`IrOp`/`Ir64Op` (lógica de dispatch, alto risco de regressão sutil).
4. Escrever uma task por arquivo (ou por grupo pequeno), cada uma pequena o bastante para 1 sessão
   fechar com G5 completo (esta mudança É código compartilhado — G5 sempre obrigatório aqui,
   nenhuma exceção da regra nova se aplica a refactor de `IrOp`/`Aarch64Decoder`).

## Não inclui

- Mudar qualquer comportamento, semântica de instrução ou API pública (G1/G3) — é refactor puro,
  arquivo muda, `.class`/comportamento não.
- Arquivos de teste gigantes (`Aarch64DecoderCorpusTest` 4296 linhas, etc.) — são citados com menos
  frequência que os fontes de produção; ficam de fora a menos que a sessão de spec ache barato
  incluir junto.
- Resolver o achado G8 do `VfpDecoder` (B14.4) ou o conflito `Thumb2NocpDecoder`×MVE (B16) —
  achados de processo já rastreados em `FILA-EXECUCAO.md`, ortogonais a este split.

## Passos (desta task, a de spec)

1. Para cada um dos 4 arquivos de maior risco/tamanho (`Aarch64Decoder`, `IrOp`, `Ir64Op`,
   `AdvSimdLanes`), decidir e documentar a linha de corte concreta (nomes de arquivo/pacote
   resultantes, quantas linhas cada pedaço fica).
2. Escrever as tasks executáveis (E15.1, E15.2, ... — 1 arquivo por task, ou menos se um arquivo
   pedir mais de uma sessão) com `Especificação`/`Passos`/`Aceite`/`Validação` completos, ordenadas
   por risco crescente.
3. Atualizar `INDICE.md` da trilha E com as sub-tasks.

## Aceite (desta task, a de spec)

- Uma sub-task por arquivo (ou grupo), cada uma pequena o bastante para fechar em 1 sessão com G5
  completo.
- Nenhuma sub-task muda comportamento — só organização de arquivo/pacote.
- Ordem de execução explícita (baixo risco primeiro).

## Validação

Esta task em si não toca `core/` — não há JaCoCo/G5 a rodar para ELA. As sub-tasks que ela gerar
seguem G5 completo (código compartilhado, sem exceção da regra nova) e o passo de JaCoCo padrão de
`tasks/README.md`.

## Armadilhas

- Não confundir "arquivo grande" com "arquivo mal projetado" — `Aarch64Decoder`/`IrOp`/`Ir64Op` são
  grandes porque o ARM é grande (regra máxima do projeto, topo de `tasks/README.md`); o objetivo
  aqui é navegabilidade/custo de contexto, não reduzir escopo nem "simplificar" cobertura de ISA.
- Split de `sealed interface` exige atenção ao `permits`/pacote — não vale a pena introduzir
  abstração nova (ex.: interface extra) só para dividir arquivo; preferir arquivos irmãos no mesmo
  pacote quando a linguagem permitir.
