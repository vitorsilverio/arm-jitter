# C7 — Adotar `PagedAddressSpace` no ndsemu (ARM9 e ARM7)

**Trilha:** C · **Depende de:** C3 (✅) · **Repo:** ndsemu

## Contexto

Mesma migração da C6, no ndsemu — que tem DOIS espaços de endereçamento (ARM9 com
TCM/VRAM-banks, ARM7 com WRAM compartilhada) e já teve +10% com o cache de
bank-mapping de VRAM (ver plano `ndsemu-perf-plan`). O dispatch por página soma-se
a isso. Fazer DEPOIS de C6 (aprender no hospedeiro mais simples primeiro).

## Inclui

1. Migrar os `AddressSpace` do ARM9 e do ARM7 para `PagedAddressSpace`:
   - RAM principal/WRAMs → `mapRam`/`mapMirror`;
   - **VRAM do ARM9: os bancos remapeáveis (VRAMCNT) viram `mapRam`/`unmap`
     dinâmicos por página no momento do remap** — isso SUBSTITUI o cache de
     bank-mapping atual (é a mesma ideia, generalizada); cuidado para não perder o
     ganho já medido: o bench decide;
   - ITCM/DTCM do ARM9 (base/size móveis via CP15) → remap por página nas escritas
     de CP15 correspondentes;
   - IO/GBA-slot/portas → `mapHandler`.
2. Invalidação de JIT: mesmo movimento da C6 (o ndsemu usa
   `DualInvalidationAwareAddressSpace` — manter semântica via
   `setContainsCode`/listener; `asmcheck`/divergence é o juiz).
3. Waitstates/timing: preservar valores atuais por região.

## Não inclui

Mudar budgets de chaining (C4 ✅), scheduler, qualquer item do plano ndsemu-perf.

## Aceite

1. Suíte ndsemu 175+ verde.
2. **Boot dos 4 jogos de referência** (JUS, MKDS, SM64DS, Platinum — regra da
   memória `arm-jitter-perf-plan`: mexeu em caminho quente, valida boot dos 4).
3. Bench `Main <frames> bench` (JUS/MKDS/SM64DS) antes/depois: sem regressão;
   publicar tabela no PR. Atenção especial: o remap de VRAM não pode ficar mais
   lento que o cache atual (jogos remapeiam bancos por frame).
4. `asmcheck` (JUS 800 frames) zero divergências.
5. Gameplay validado pelo usuário (MKDS corrida completa + JUS intro) antes de
   fechar.

## Armadilhas

- Remap de banco VRAM acontece DENTRO do frame — o custo de `mapRam`/`unmap`
  precisa ser O(páginas do banco), não O(espaço inteiro); se o utilitário do C3
  não der isso, reporte em vez de contornar.
- DTCM move com frequência em jogos (stack no DTCM) — remap barato obrigatório.
- Páginas com código no ARM7 (WRAM compartilhada) mudam de dono via WRAMCNT — o
  `setContainsCode` tem que seguir o remap.
