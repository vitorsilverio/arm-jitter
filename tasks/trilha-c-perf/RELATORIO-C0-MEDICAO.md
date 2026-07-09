# RELATÓRIO C0 (fase de medição) — pares bloco→bloco do chaining (2026-07-09)

**Conclusão em uma frase: o "superbloco" que vale construir não é um trace linear
longo de código de jogo — é um LOOP FECHADO minúsculo (2–4 blocos): um único loop de
idle/spin concentra 96–99% de TODOS os saltos de corrente nos 3 jogos medidos, com
estabilidade de sucessor de 100%.**

## Método

`ChainProfiler` (novo, opt-in, em `jit/ChainProfiler.java`) instalado nos dois
runtimes do ndsemu (`Main <rom> <frames> asm bench chainprof state=<save>.ss`).
Budgets de produção (ARM9=96/ARM7=8). O profiler custa ~2,5× de fps (HashMap por
salto) mas não distorce as estatísticas de comportamento; sem ele instalado o
caminho quente ficou idêntico (bench MKDS ~41–43 fps steady = baseline conhecido).

## Dados (ARM9, 600–900 frames com savestate in-game)

| Jogo | hops totais | loop dominante (4 blocos, estab. 100%) | % dos hops | runs 64+ hops | quebra por BUDGET |
|------|------------:|----------------------------------------|-----------:|--------------:|------------------:|
| MKDS (corrida) | 737M | `0200DBE0→0200FD04→0200FD0C→0200DBE4` | **97%** | 82% | 93,6% |
| SM64DS (in-game) | 789M | `02056B94→02056B90→02058AD0→02058AD8` | **99%** | 93% | 98,8% |
| JUS (vídeo) | 496M | `02052E44→02055678→02055680→02052E48` | **96,5%** | ~90% | 99,4% |

Padrão idêntico nos três: é o idle-thread/spin do NitroSDK (poll de flags/IRQ). Os
traces secundários também são loops fechados pequenos (2–3 blocos, 95–100% estáveis).
`IC_MISS` = só 0,3–2,8% das quebras — a cobertura do chaining já é ótima; o custo é o
POR-SALTO (chamada megamórfica + `core.mode()` + lookup de IC por bloco).

**ARM7 (budget 8):** média de 1,75 hops/run, 99,7% das correntes morrem no budget —
truncando uma sequência de ~16 blocos 100% estável (caminho BIOS 0x08 → wait de IRQ,
5,2M execuções de cada par no MKDS). O ARM7 paga round-trip de dispatch quase por
bloco por causa do budget minúsculo (que é necessário só no BOOT — ver task C4).

## O que isso muda no desenho da C0

1. **Alvo nº 1 = "loop-superbloco":** detectar (com este profiler ou contador leve)
   um ciclo fechado de 2–4 blocos estável e compilá-lo como UM método com o loop
   interno, checando budget/IRQ/sleep/generation por iteração DENTRO do método.
   Internaliza ~90 saltos por chamada de `execute` → 1 chamada; elimina na origem os
   26–36% de CPU medidos como dispatch (JFR 2026-07-08).
2. **A preocupação do A0 (C2 degradar em métodos gigantes) não se aplica ao caso
   dominante**: o loop-superbloco tem ~4–16 instruções. Backend ASM serve. Truffle
   para superblocos fica relevante só se formos atrás de traces longos (não é onde o
   dinheiro está, pelos dados).
3. **Ideia maior a avaliar antes de investir (possível atalho):** o loop dominante é
   IDLE — a técnica clássica de emulador é **idle-skip** (detectar o spin e saltar o
   relógio para o próximo evento em vez de emular o spin 1:1). Ganho potencial MAIOR
   que eliminar dispatch (não paga nem a execução do loop), mas mexe com timing e
   precisa de cooperação do hospedeiro (scheduler do ndsemu). Riscos: os handshakes
   IPC cross-CPU que já quebraram com chain budget alto. Decidir loop-superbloco vs
   idle-skip (ou ambos, superbloco primeiro por ser seguro) na spec de implementação.
4. **ARM7:** subir o budget pós-boot (task C4) destrava a sequência de 16 blocos; ou
   o loop-superbloco cobre também (o loop interno do método pode ter budget próprio
   maior com os MESMOS guards de segurança por iteração).

## Ferramentas que ficam

- `jit/ChainProfiler.java` + hooks opt-in no `JitRuntime` (null-check por salto,
  comportamento comprovadamente idêntico desligado — `ChainProfilerTest`).
- ndsemu `Main`: args `chainprof` (relatório por CPU no fim do run) e
  `chainbudget=<n>` (override dos DOIS budgets — só usar com savestate pós-boot).

## Próximo passo da C0

Escrever a spec de implementação do **loop-superbloco** (detector de ciclo no
chaining + emissor do método-loop + validação divergence/asmcheck + A/B bench nos 3
jogos), com a decisão idle-skip-vs-superbloco documentada. Os critérios de aceite do
épico continuam os da spec C0.
