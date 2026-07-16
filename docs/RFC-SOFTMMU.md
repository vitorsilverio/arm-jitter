# RFC — softmmu / VMSA (full-system 32-bit)

**Status: APROVADA POR REFINAMENTO 2026-07-15** (sessão de modelo forte, para
destravar B4.1.x — se o usuário discordar, reabrir antes de B4.1.1).

## Decisões

### 1. Tradução em wrapper, não no JIT (nesta fase)

`TranslatingAddressSpace implements AddressSpace` envolve o `AddressSpace` físico:
todo acesso chega com VA, resolve PA via micro-TLB em software (array direto de
2×256 entradas I/D indexado por VPN) com page-walk short-descriptor no miss.
Inline-TLB no bytecode do JIT fica explicitamente adiado: só reabrir com profiling
de kernel real mostrando o wrapper como topo (>15% do CPU).

### 2. Hospedeiro de referência: **versatilepb + ARM1176 (ARMv6K)** — repo novo `linuxbox`

A dupla clássica do QEMU (`-M versatilepb -cpu arm1176`): periféricos simples e
todos documentados (UART PL011, timer SP804, VIC PL190), kernel mainline com
`versatile_defconfig` builda até hoje, initramfs busybox. **Consequência
importante: B4.1 NÃO depende de B3/VFP** — kernel soft-float; o preset ARMv6K
(B1.x, pronto) basta. A ordem B3→B4.1 do roadmap antigo cai.

### 3. Aborts precisos

- `MemoryTranslationException extends RuntimeException` (unchecked, sem stack
  trace — `super(null, null, false, false)`), carregando VA, tipo de acesso e
  FSR (status de falha: translation/domain/permission, section/page).
- Quem lança: `TranslatingAddressSpace`. Quem captura: os DOIS motores no nível de
  instrução — `ArmCore.stepReturningInternalCycles` (interpretador) e o corpo do
  bloco JIT (o runtime já tem o padrão de sair de bloco por exceção de SWI/IRQ —
  seguir o caminho existente), convertendo em entrada de exceção
  `ArmException.DATA_ABORT`/`PREFETCH_ABORT` com FAR=VA e FSR preenchidos no CP15.
- `LDM`/`STM` abortando no meio: semântica **base-restored** (ARM1176): os
  registradores já escritos podem ficar, a base volta ao valor original — o
  executor de `MultipleTransfer` salva a base antes e restaura no catch.
- Bloco JIT que já executou ops antes do abort: aceitável — o abort é preciso no
  nível da INSTRUÇÃO (PC da instrução faltosa via o mecanismo que o guard
  condicional/`Swi` já usa para materializar PC no meio do bloco).

### 4. CP15 VMSA na lib: `Cp15VmsaCoprocessor implements CoprocessorBus`

Registradores: SCTLR (c1: bits M, V — o resto RAZ/WI documentado), TTBR0/TTBR1/
TTBCR (c2), DACR (c3), DFSR/IFSR (c5), DFAR/IFAR (c6), TLB ops (c8: invalidate
all/by-MVA — repassam ao TLB do wrapper), cache ops (c7: NOP + barreira), CONTEXTIDR
(c13, ASID). Composição: o hospedeiro instala `new Cp15VmsaCoprocessor(mmu)` e pode
encadear outro bus para o que não for VMSA (padrão decorator, `handles()` decide).

### 5. `BlockCache` por contexto

`BlockKey` ganha `int translationGeneration` (não ASID por bloco): CADA escrita de
TTBR0/CONTEXTIDR e cada TLB-invalidate-all incrementa uma geração global no
runtime; blocos de geração antiga são misses naturais (mesma técnica do tag de
ITSTATE de B2.4 — reusar o precedente). Simples e correto; per-ASID caching fica
como otimização futura medida.

### 6. Interrupções/timers/UART

100% no hospedeiro `linuxbox` (a linha IRQ/FIQ do core já existe). O PL190 agrega
e aciona `setInterruptLine`.

## Fases (tasks B4.1.1–B4.1.5 em `tasks/trilha-b-arquiteturas/`)

B4.1.1 wrapper+TLB+walk → B4.1.2 CP15 → B4.1.3 aborts precisos → B4.1.4 gerações
no BlockCache → B4.1.5 linuxbox (kernel até shell). Aceite final: versatile zImage
mainline + initramfs busybox até `#` interativo.
