# C4 — Chain budget pós-boot (repo: ndsemu)

**Trilha:** C · **Depende de:** — · **Repo:** **ndsemu** (arm-jitter só se precisar de API)

## Contexto

O encadeamento de blocos (`JitRuntime.setChainCycleBudget`) rende ~+9% medido, mas
budgets altos durante o BOOT quebram handshakes cross-CPU do NitroSDK (IPC-sync):
**ARM7 com budget ≥16 quebra o boot de Pokémon Platinum e SM64DS** (fato medido,
registrado na memória do projeto). Budgets atuais seguros: ARM9=96, ARM7=8. A ideia:
budgets baixos durante o boot, altos depois.

## Especificação

1. No ndsemu (onde os dois `JitRuntime` são criados): configuração
   `bootChainBudget{Arm9,Arm7}` e `runtimeChainBudget{Arm9,Arm7}`, com defaults
   = valores atuais (96/8) para AMBAS as fases — ou seja, comportamento idêntico até
   alguém subir os valores de runtime (G3).
2. **Critério de troca boot→runtime:** trocar após N frames emulados (config
   `chainBudgetSwitchFrame`, default a determinar MEDINDO — começar com 3000 ≈ 50s de
   boot; se os 4 jogos de referência bootam em menos, reduzir). Implementar o gatilho
   no loop de frames do ndsemu, chamando `setChainCycleBudget` uma única vez na troca.
3. Valores de runtime a explorar (bench): ARM9 96→128/192; ARM7 8→16/32/64.

## Protocolo de validação (obrigatório e literal)

Para CADA combinação candidata de budgets de runtime:

1. Boot FRIO (sem savestate) de **4 jogos**: JUS, MKDS, SM64DS, Pokémon Platinum —
   todos precisam chegar ao mesmo ponto que hoje (JUS: logos das publishers; MKDS:
   menu; SM64DS: in-game; Platinum: overworld). Se QUALQUER um regredir no boot com a
   troca DEPOIS do boot, o gatilho está cedo demais — aumente `switchFrame` antes de
   descartar o budget.
2. Gameplay: entrar em corrida (MKDS) / fase (SM64DS/JUS) e jogar ~1min — sem travas.
3. `Main <rom> 1500 bench` antes/depois para os 4; publicar tabela no PR.
4. Suites ndsemu verdes.

## Aceite

- Config + gatilho implementados, defaults inalterados.
- Tabela de bench + a combinação recomendada documentada no commit/PR.
- Se nenhum budget de runtime der ganho ≥3% estável, registrar o resultado negativo e
  fechar a task mesmo assim (o dado vale por si).

## Armadilhas

- O perigo NÃO é só o boot: IPC ARM9↔ARM7 acontece em gameplay também (áudio/input).
  Sintomas de budget alto demais no ARM7: som atrasado/loopado, input perdido.
- Trocar o budget é seguro em fronteira de frame (fora de `execute`) — não trocar no
  meio de um slice.
