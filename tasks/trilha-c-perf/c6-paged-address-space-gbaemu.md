# C6 — Adotar `PagedAddressSpace` no gbaemu

**Trilha:** C · **Depende de:** C3 (✅ utilitário pronto no arm-jitter) · **Repo:** gbaemu

## Contexto

C3 entregou `memory/PagedAddressSpace` no arm-jitter (dispatch O(1) por página:
`mapRam`/`mapHandler`/`mapMirror`/`unmap`, `WriteListener` por página gateado por
`setContainsCode`, open-bus configurável, waitstates por página) e mediu ~19-26%
mais rápido que um if-chain realista de 8 braços em microbench. O gbaemu ainda usa
o if-chain. **Beneficia os DOIS backends** (o dispatch de memória é do hospedeiro —
e o default do gbaemu é `INTERPRETED`, então o ganho chega ao modo padrão).

## Inclui

1. Localizar a implementação de `AddressSpace` do gbaemu (a classe que hoje faz o
   if-chain de regiões: BIOS/EWRAM/IWRAM/IO/Palette/VRAM/OAM/ROM/SRAM) e
   reimplementá-la SOBRE `PagedAddressSpace` (composição, não herança, se o
   utilitário for final):
   - regiões de RAM pura (EWRAM/IWRAM/Palette/VRAM/OAM) → `mapRam` + `mapMirror`
     para os espelhos que o GBA tem (EWRAM/IWRAM espelham; VRAM espelha com dobra
     96K→128K — se o `mapMirror` não expressar a dobra da VRAM, essa página fica
     em `mapHandler`, registrar no PR);
   - IO/ROM-com-waitstate/SRAM/EEPROM → `mapHandler` com os handlers atuais;
   - waitstates por página onde a tabela atual for por região.
2. **Invalidação de JIT**: o gbaemu envolve o bus em `InvalidationAwareAddressSpace`
   (fix do bug de self-modifying-code — ver memória `gba-game-compat`). Decidir
   pelo caminho equivalente com o listener por página (`setContainsCode` nas
   páginas de EWRAM/IWRAM/VRAM onde código roda) MANTENDO a semântica: toda escrita
   em página com código invalida o `BlockCache`. O `JitInterpreterDivergenceTest`
   é o juiz.
3. Manter `providesAccessCycles()` coerente (o `memoryProvidesAccessCycles` do
   `ArmCore` muda o custo de ciclo — não pode mudar de valor com a migração, senão
   o timing do emulador inteiro desloca).

## Não inclui

Mudança de default de backend, chaining (C5 ✅), qualquer coisa no arm-jitter (se o
utilitário precisar de ajuste, PARE e reporte — vira mini-task no arm-jitter).

## Aceite

1. Suíte gbaemu completa verde (216+), incluindo `JitInterpreterDivergenceTest` e
   gba-tests (ROMs de teste).
2. Bench headless dos 5 jogos de referência (FireRed, SMW, Castlevania, Metroid,
   MarioKart — o mesmo bench de C5) antes/depois, nos DOIS backends: nenhuma
   regressão; ganho esperado single-digit a ~15% (o microbench de C3 mede só o
   dispatch; o emulador inteiro dilui). Publicar a tabela no PR.
3. Timing: os testes de IRQ/timer/DMA existentes verdes (guardas de que waitstate
   não mudou).
4. Validação de gameplay pelo usuário (padrão do projeto): pedir boot+save nos 5
   jogos antes de dar a task por fechada.

## Armadilhas

- Página contendo código ≠ página de IO: `setContainsCode` só nas páginas de RAM
  executável — marcar tudo mata o ganho (todo store dispararia listener).
- Open-bus do GBA (leitura de região não mapeada devolve prefetch/último valor) —
  conferir o comportamento atual do if-chain e reproduzir com o open-bus
  configurável do utilitário; os gba-tests pegam diferenças aqui.
- SRAM é 8-bit-only no GBA — se o handler atual trata isso, o novo mapeamento
  precisa manter (é handler, não RAM mapeada).
