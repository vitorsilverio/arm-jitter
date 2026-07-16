# D5 — ndsemu: Pokémon Platinum trava e não passa da intro (Buneary/título→menu) ⚠️ MODELO FORTE

**Trilha:** D · **Depende de:** — · **Repo:** ndsemu · **NÃO passar a agente comum**

## Sintoma (aberto há semanas, sem task até 2026-07-16 — formalizado a pedido do usuário)

Platinum renderiza intro (filme com a Buneary), attract mode e até overworld em
teste anterior, mas **trava ao ir para o menu do título** — o usuário descreve
como "não passa do Buneary". Registro técnico anterior (memória
`ndsemu-game-compat`): ARM9 preso em **`0x020B2688`**.

## AVISO sobre pistas falsas

A memória `ndsemu-game-compat` registra explicitamente que há **falsas pistas
documentadas** de investigações anteriores deste bug — quem pegar esta task deve
ler aquele arquivo ANTES, para não re-perseguir hipóteses já descartadas.
Os 3 outros jogos de referência (MKDS/SM64DS/JUS) estão jogáveis — o bug é
específico do que o Platinum exercita.

## Protocolo (a artilharia já existe, usar nesta ordem)

1. **Caracterizar o spin**: headless até a trava; no PC `0x020B2688`, usar o
   `GdbServer` do arm-jitter (`Main <rom> gdb`, porta 3333) para ler o loop —
   que endereço/flag ele espia (IPC? IF de IRQ? FIFO? card)? Isso classifica o
   bug em: espera de IPC ARM7, espera de IRQ que nunca chega, ou espera de
   dado do cartucho (o blocker anterior do JUS era card DMA — padrão parecido).
2. **Oráculo melonDS**: técnica documentada na memória `ndsemu-melonds-oracle` —
   rodar o mesmo ponto no melonDS, comparar o estado (registradores de IO na
   região espiada) no momento equivalente; a diferença aponta o periférico.
3. **Bisect de subsistema, não de commit**: sabendo O QUE ele espera, comparar o
   comportamento desse periférico com o GBATEK/melonDS (ex.: se for IRQ de
   card, revisitar o start-mode de card DMA — mesmo tema do fix do JUS).
4. Fix + teste de regressão headless (boot até o menu do título vira asserção
   de PC/DISPCNT, padrão dos testes de boot existentes).

## Aceite

Platinum chega ao menu do título e inicia jogo novo (validação do usuário);
MKDS/SM64DS/JUS re-validados (boot headless ×4, regra da memória
`arm-jitter-perf-plan`); suite ndsemu 175+ verde; pistas falsas novas (se
descobertas) registradas na memória `ndsemu-game-compat` para o próximo.
