# C3 — PagedAddressSpace: dispatch de memória O(1)

**Trilha:** C · **Depende de:** — · **Repo:** arm-jitter (classe utilitária) + adoção em gbaemu/ndsemu

## Contexto

Os `AddressSpace` dos hospedeiros resolvem cada acesso com cadeias de if/switch por
região. Profiling (ver memórias PLAN 1/2) aponta o dispatch de memória como custo
relevante. Proposta: utilitário opcional de page-table no arm-jitter que os
hospedeiros adotam gradualmente.

## Especificação

1. Nova classe `memory/PagedAddressSpace implements AddressSpace`:
   - Construtor com `pageShift` configurável (ex.: 12 = 4KB; 14 = 16KB — GBA/NDS têm
     regiões alinhadas a 16KB+; constante nomeada, G6).
   - Duas tabelas de páginas (índice = `address >>> pageShift`):
     - `byte[][] ramPages` (ou `int[][]` — decidir medindo; byte[] casa com read8) —
       página presente = acesso direto inline sem virtual call;
     - `AddressSpace[] handlerPages` — página de MMIO/aberta delega ao handler.
   - API de montagem: `mapRam(int base, byte[] backing)` (fatia o backing em páginas,
     exige alinhamento — validar e lançar mensagem clara), `mapHandler(int base, int
     size, AddressSpace handler)`, `unmap(...)`.
   - `read8/16/32`, `write8/16/32`: lookup → se ramPage != null, acesso direto
     little-endian (usar `VarHandle`/`ByteArrayViewVarHandles` para 16/32 — medir vs
     aritmética manual); senão handler; senão comportamento de barramento aberto
     DELEGADO a um handler default configurável (os quirks de open-bus são do
     hospedeiro, não do utilitário).
   - `accessCycles(...)`: delegar a uma tabela por página (int[] simples) — waitstates
     são por região.
   - Espelhamento (mirrors do GBA/NDS): mapear a MESMA page array em várias janelas —
     documentar com exemplo no javadoc.
2. Compatibilidade com invalidação SMC: `InvalidationAwareAddressSpace` continua
   envolvendo por fora (G3) — mas as escritas diretas em ramPage pulariam o notify!
   **Solução obrigatória:** `PagedAddressSpace` aceita um `WriteListener` opcional por
   página (bitset "página tem código JIT" — mesma ideia do page-index do
   `BlockCache.invalidate`); páginas sem código escrevem direto, páginas com código
   notificam. Ler `BlockCache.invalidate` e `InvalidationAwareAddressSpace` ANTES de
   desenhar isso.

## Adoção (PRs separados, um por hospedeiro)

- gbaemu: EWRAM/IWRAM/ROM/VRAM como ramPages; I/O como handler.
- ndsemu: idem (cuidado com VRAM bank-mapping dinâmico — remapear páginas ao trocar
  banco; a memória do projeto registra que o cache de bank-mapping deu +10%).

## Validação

1. Testes unitários no arm-jitter: map/unmap, mirrors, alinhamento inválido, handler
   fallback, listener de escrita disparando só em página marcada.
2. Microbench (JMH ou loop manual): PagedAddressSpace vs if-chain sintética; publicar.
3. Suites gbaemu/ndsemu verdes + bench dos jogos de referência antes/depois; qualquer
   regressão de compatibilidade (os 5 GBA + JUS/MKDS/SM64DS/Platinum boot) bloqueia.

## Armadilhas

- Acesso desalinhado 16/32 cruzando fronteira de página: com pageShift ≥ 12 e os
  alinhamentos do ARM (o core já rotaciona desalinhados), um acesso de 4 bytes nunca
  cruza página SE o hospedeiro só mapear regiões alinhadas — ainda assim, tratar o
  caso (delegar ao handler) em vez de assumir.
- Não converter os hospedeiros "no atacado": uma região por PR, com bench, para achar
  regressões cedo.
