# B6 — AArch64 (épico refinado: B6.1/B6.2 executáveis; B6.3 decomposta em
B6.3.1-B6.3.4; B6.4 spec própria em 3 PRs, fechada; B6.5 decomposta em
B6.5.1-B6.5.4; B6.6 decomposta em B6.6.1-B6.6.6)

**Trilha:** B · **Depende de:** B0 ✅ (RFC aprovada 2026-07-10: **Opção B** — IR-64
paralelo + `Aarch64Core` irmão + `AddressSpace64`; ver `docs/RFC-IR-64BIT.md`) ·
**Repo:** arm-jitter + armbox
**Refinada 2026-07-15**: B6.1 e B6.2 abaixo são executáveis diretamente. B6.3–B6.6
têm escopo FECHADO mas ganham spec própria quando B6.2 concluir (a spec depende do
esqueleto real existir; NÃO executar sem essa rodada — regra do tasks/README).
**Refinada de novo 2026-07-24** (rodada de spec, sem código): B6.3 era grande
demais para uma task só (misturava categorias de risco/tamanho bem diferentes —
ver a tabela "Escopo fechado" abaixo, linha B6.3, que agora só referencia as 4
sub-tasks concretas). Decomposta em
[B6.3.1](b6.3.1-aarch64-logical-imm-alu-register.md) (logical immediate
`DecodeBitMasks` + ALU shifted/extended register — cria o dispatch novo
"Data Processing — Register" do qual as próximas duas dependem),
[B6.3.2](b6.3.2-aarch64-csel-bitfield.md) (CSEL family + bitfield UBFM/SBFM/BFM,
depende de B6.3.1),
[B6.3.3](b6.3.3-aarch64-mul-div.md) (MUL/MADD/MSUB/SDIV/UDIV, depende de B6.3.1) e
[B6.3.4](b6.3.4-aarch64-exclusive-monitor.md) (LDXR/LDAXR/STXR/STLXR + monitor de
exclusividade A64, só depende de B6.2 — pode ser feita em paralelo/antes das
outras 3 se a fila priorizar). Ver `tasks/README.md` para o status individual de
cada uma e `tasks/FILA-EXECUCAO.md` para qual está na fila agora.

**Refinada de novo 2026-07-26** (rodada de spec, sem código, B6.3 100% fechada
e B6.4 já fechada — o gatilho "spec própria quando B6.2 concluir" da nota
2026-07-15 finalmente cumprido para B6.5/B6.6): B6.4 ganhou spec própria em
3 PRs (`b6.4-aarch64-asm-backend.md`) e fechou por completo no mesmo dia.
B6.5 decomposta em 4 sub-tasks
([B6.5.1](b6.5.1-aarch64-fp-register-bank.md)-[B6.5.4](b6.5.4-aarch64-fp-asm-nativo.md)),
espelhando a decomposição B3.3-B3.6 do precedente VFP32 (banco de
registradores → IR+interpretador → decoder → ASM nativo). B6.6 decomposta em
6 sub-tasks
([B6.6.1](b6.6.1-aarch64-system-register-access.md)-[B6.6.6](b6.6.6-aarch64-virt64-host.md)),
uma a mais que o precedente B4.1.1-B4.1.5 (32-bit) porque a pesquisa desta
rodada achou um pré-requisito estrutural sem equivalente 32-bit: A64 não tem
`MCR`/`MRC`, então o acesso a registrador de sistema (`MRS`/`MSR`) precisa
ser construído do zero (B6.6.1) antes de qualquer registrador de controle da
MMU poder ser programado por código guest — ver a tabela "Escopo fechado"
abaixo para o detalhe de cada sub-task e `tasks/README.md`/
`tasks/FILA-EXECUCAO.md` para status/fila atual.

Meta honesta do épico: Linux arm64 user-mode (hello → busybox) → full-system depois.
Android fora. ARMv8-A base, sem SVE/SVE2 (features futuras).

## B6.1 — Esqueleto IR-64 + `Aarch64Core` + interpretador (fatia data-processing/branches) [EXECUTÁVEL]

Aplicar a Opção B da RFC, literalmente:

1. **Pacote `ir64/`**: `Ir64Op` (sealed interface, espelho estrutural de `IrOp` mas
   com operandos `long`/registradores 0-30 + SP/XZR), começando com os records:
   `Alu64` (ADD/SUB/AND/ORR/EOR com flags opcionais, operando W ou X — campo
   `boolean wide`), `MoveWide` (MOVZ/MOVN/MOVK, hw shift 0/16/32/48), `PcRelative`
   (ADR/ADRP), `Branch64` (B/BL condicional e incondicional, BR/BLR/RET),
   `CompareBranch64` (CBZ/CBNZ/TBZ/TBNZ), `Svc`, `Cycle`/`Fetch` (mesma disciplina
   G4). XZR: registrador 31 como fonte lê 0, como destino descarta — resolver no
   EXECUTOR, nunca no decoder (campo é só o índice); SP vs XZR: decidido pelo
   encoding (campo `boolean spVariant` onde o manual distinguir).
2. **`core64/Aarch64Core`**: `long[31] x`, `long sp`, `long pc`, PSTATE (NZCV — 
   classe própria `PstateRegister`, NÃO reusar `CpsrRegister`, decisão registrada na
   task antiga), EL0 apenas; `AddressSpace64` nova em `memory/` (mesma forma da
   `AddressSpace` com endereços `long`; adapter `AddressSpace64.wrapping(AddressSpace)`
   para reusar RAM de teste).
3. **Decoder `Aarch64Decoder`**: só os grupos da fatia — pelo op0 (bits 28:25):
   `100x` data-processing immediate (ADD/SUB imm, MOVZ/MOVN/MOVK, ADR/ADRP,
   logical imm FICA FORA da fatia), `101x` branches/exception (B/B.cond/BL/CBZ/
   CBNZ/TBZ/TBNZ/BR/BLR/RET/SVC). Referência: ARM DDI 0487 C4.1; **oráculo
   obrigatório: corpus montado** — `.s` com cada forma, montado por toolchain
   aarch64 (ver B6.2 §toolchain), golden file de decode versionado, teste compara
   campo a campo.
4. **Interpretador**: `Ir64BlockExecutor` mínimo (sem JIT, sem cache — um
   `step()`/`run()` direto no core; o pipeline tiered chega em B6.4).
5. Testes: vetores por instrução (incl. XZR-como-destino, ADRP alinhando 4KiB,
   MOVK compondo endereço de 64 bits, CBZ com W vs X) + corpus de decode.
   G3/G5: NADA do core 32-bit tocado (pacotes novos apenas).

## B6.2 — Loads/stores + ELF64 + syscalls no armbox (`--arch=aarch64`) [EXECUTÁVEL]

**Fecha:** armbox#2 (aceite #2, corpus busybox aarch64 — aceite #1 já fechado)

1. IR/decoder/executor: `Load64`/`Store64` (unsigned imm scaled, unscaled/`LDUR`,
   pre/post-index, registrador+extend), `LoadStorePair` (LDP/STP, o idioma de
   prólogo/epílogo — sem ele nenhum binário real roda), `LoadLiteral64`.
   Tamanhos B/H/W/X + sign-extend. Grupo op0 `x1x0`.
2. armbox: loader ELF64 (`EM_AARCH64`), `--arch=aarch64` instancia `Aarch64Core`;
   tradução de syscalls arm64 (números DIFEREM do ARM 32: `write`=64, `exit`=93,
   `exit_group`=94, `brk`=214, `mmap`=222 — tabela do
   `include/uapi/asm-generic/unistd.h`; implementar as ~10 que o hello/busybox-echo
   do armbox 32-bit já exigiu, espelhando o dispatcher existente).
3. **Toolchain/testdata**: `hello-aarch64.s` escrito à mão (syscall write+exit,
   sem libc) montado com `zig cc -target aarch64-linux-musl` OU binutils
   `aarch64-none-elf` — o que já estiver disponível na máquina; PERGUNTAR ao
   usuário qual instalar se nenhum estiver (única dependência externa da task).
   Mais `busybox` estático arm64 (mesma fonte dos busybox-armv5l existentes).
4. Aceite: `armbox --arch=aarch64 hello-aarch64.elf` stdout/exit idênticos ao
   esperado; `busybox echo hi` funciona no interpretador.

## Escopo fechado das próximas (spec própria após B6.2)

| Sub | Escopo (fixo) | Aceite |
|-----|---------------|--------|
| ~~B6.3~~ | **DECOMPOSTA em 2026-07-24** (era grande demais para uma task só — 5 categorias de risco/tamanho bem diferentes misturadas). Escopo original ("Base ISA inteira restante: logical imm (decode bitmask!), shifts/extends de registrador, CSEL/CSINC/CSINV/CSNEG, bitfield UBFM/SBFM/BFM + aliases, MUL/MADD/SDIV/UDIV, LDAXR/STLXR") preservado por completo, só redistribuído nas 4 sub-tasks abaixo — nenhum item foi removido ou adicionado ao que estava fechado. | (herdado pelas 4 sub-tasks — o aceite agregado "`busybox sh -c` completo no armbox64, zero divergência vs corpus" só fecha quando as 4 fecharem E o bloqueio de toolchain/busybox arm64 registrado em `tasks/FILA-EXECUCAO.md` (🧑, mesmo bloqueio do aceite #2 de B6.2) for resolvido) |
| [B6.3.1](b6.3.1-aarch64-logical-imm-alu-register.md) | Logical immediate (`DecodeBitMasks`, `AND`/`ORR`/`EOR`/`ANDS` imediato) + ALU shifted/extended register (`ADD`/`SUB`/`ADDS`/`SUBS`, as duas formas) — cria o dispatch novo de classe top-level "Data Processing — Register" do qual B6.3.2/B6.3.3 dependem | corpus + property test do `DecodeBitMasks` passando; suíte verde |
| [B6.3.2](b6.3.2-aarch64-csel-bitfield.md) | `CSEL`/`CSINC`/`CSINV`/`CSNEG` (+ aliases `CSET`/`CSETM`/`CINC`/`CINV`/`CNEG`) + `UBFM`/`SBFM`/`BFM` (+ aliases `UBFX`/`SBFX`/`BFI`/`BFXIL`/`LSL`/`LSR`/`ASR`/`UXTB`/`UXTH`/`SXTB`/`SXTH`/`SXTW`) | corpus cobrindo os aliases citados; suíte verde |
| [B6.3.3](b6.3.3-aarch64-mul-div.md) | `MADD`/`MSUB` (+ aliases `MUL`/`MNEG`), `SDIV`/`UDIV` | corpus + testes de divisor-zero/overflow; suíte verde |
| [B6.3.4](b6.3.4-aarch64-exclusive-monitor.md) | `LDXR`/`LDAXR`/`STXR`/`STLXR` (encoding único, as 4 mnemônicas — ver D0 da sub-task) + `Aarch64ExclusiveMonitor` novo (sibling do monitor de B1.4/B5.1, endereço `long` já previsto na RFC §5) | corpus + testes espelhando B1.4; suíte verde |
| [B6.8](b6.8-aarch64-conditional-compare.md) | `CCMP`/`CCMN` (registrador e imediato) — gap real na classe "Data Processing — Register" achado pela F11 (`virtual-arm-box`) na primeira instrução de um `kernel8.img` real, fora da decomposição original de B6.3 | corpus real incl. o vetor `ccmp x18,#0,#0xd,pl` da F11; suíte verde |
| B6.4 | Backend ASM 64-bit: `GuestToHostMapper` com locals `long` (2 slots), cache/tiers/chaining reusados | harness de equivalência A64 (novo `BlockEquivalenceHarness64`), busybox ≥3× interpretador — **spec própria escrita 2026-07-26** ([b6.4-aarch64-asm-backend.md](b6.4-aarch64-asm-backend.md), 3 PRs) — **FECHADA** (PR1/PR2/PR3 ✅, mesma data) |
| ~~B6.5~~ | **DECOMPOSTA em 2026-07-26** (rodada de spec — mesmo padrão de B6.3: 4 categorias de risco/tamanho distintas, espelhando a decomposição B3.3-B3.6 do precedente VFP32). Escopo original ("FP/SIMD escalar mínimo — FMOV/FADD/FMUL/FDIV/FCMP/FCVT — o que a libc usa") preservado por completo, só redistribuído nas 4 sub-tasks abaixo — nenhum item foi removido ou adicionado ao que estava fechado (achados de gap real registrados nas sub-tasks: leitura literal de "FCVT" exclui `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU`; nenhum record de load/store FP entra nesta leitura — ver `b6.5.2-aarch64-fp-ir-interpretador.md` Armadilhas). | (herdado pelas 4 sub-tasks — o aceite agregado "binários musl com printf de float" só fecha quando as 4 fecharem E os gaps de `SCVTF`/`UCVTF`/load-store FP registrados em B6.5.2 forem resolvidos por uma sub-task futura) |
| [B6.5.1](b6.5.1-aarch64-fp-register-bank.md) | Banco de registradores FP escalar (`Aarch64FpRegisters`, V0-V31 só bits 63:0, sem FPCR/FPSR) | testes de banco + `Aarch64CpuSnapshot` estendido; suíte verde |
| [B6.5.2](b6.5.2-aarch64-fp-ir-interpretador.md) | `Ir64Op`s de FP (`Fp64Alu`/`Fp64MoveImmediate`/`Fp64Compare`/`Fp64Convert`) + executor interpretado | testes de executor por vetor concreto; suíte verde |
| [B6.5.3](b6.5.3-aarch64-fp-decoder.md) | Decoder da classe "Data Processing — Scalar FP" (`bit26=1`, ramo novo em `decodeDataProcessingRegister`) | corpus real + regressão negativa (SIMD vetorial fora de escopo); suíte verde |
| [B6.5.4](b6.5.4-aarch64-fp-asm-nativo.md) | Emissão ASM nativa de FP (extensão de `Ir64NativePolicy`/`Ir64BlockCompiler`, B6.4) | `BlockEquivalenceHarness64` cobrindo os 4 `Kind`s de FP; suíte verde |
| ~~B6.6~~ | **DECOMPOSTA em 2026-07-26** (rodada de spec — 6 sub-tasks em vez das 5 do precedente B4.1/MMU 32-bit, porque a pesquisa desta rodada achou um PRÉ-REQUISITO ESTRUTURAL sem equivalente 32-bit: A64 não tem `MCR`/`MRC`, então o acesso a registrador de sistema — `MRS`/`MSR` — precisa ser construído do zero antes de qualquer coisa de MMU poder ser programada por código guest; ver B6.6.1). Escopo original ("MMU v8 [VMSA64, 4KiB granule, EL0/EL1] + hospedeiro `virt64`") preservado por completo, só redistribuído — nenhum item removido ou adicionado (2 achados de pré-requisito adicional registrados nas sub-tasks, além do de B6.6.1: `TLBI`/`ERET` também não são `MRS`/`MSR` e precisam de decode próprio — ver B6.6.3/B6.6.4). | (herdado pelas 6 sub-tasks — o aceite agregado "kernel arm64 mínimo até shell" só fecha quando as 6 fecharem E o mesmo bloqueio de toolchain/kernel real de B6.2/B4.1.5 for resolvido, registrado em `tasks/FILA-EXECUCAO.md` 🧑 para a sub-task final B6.6.6) |
| [B6.6.1](b6.6.1-aarch64-system-register-access.md) | Acesso a registrador de sistema (`MRS`/`MSR (register)`) — pré-requisito descoberto nesta rodada, sem equivalente no mundo 32-bit (`MCR`/`MRC` já existia desde B1.x) | corpus real (`SCTLR_EL1`/`TTBR0_EL1`/`VBAR_EL1` etc.); suíte verde |
| [B6.6.2](b6.6.2-aarch64-translating-address-space.md) | `TranslatingAddressSpace64`: page-walk VMSA64 (4KiB granule, VA 48 bits, 4 níveis L0-L3) + micro-TLB, independente de B6.6.1 | testes unitários contra tabelas de página montadas à mão; suíte verde |
| [B6.6.3](b6.6.3-aarch64-system-register-mmu-bridge.md) | `Aarch64VmsaSystemRegisters` ligando B6.6.1↔B6.6.2 + decode mínimo de `TLBI VMALLE1` (achado: `TLBI` não é `MRS`/`MSR`, encoding `SYS`/`SYS(L)` próprio) | sequência real `TTBR0_EL1→TCR_EL1→SCTLR_EL1.M=1` mudando a tradução; suíte verde |
| [B6.6.4](b6.6.4-aarch64-precise-aborts-el1.md) | Modelo mínimo de exceção EL0→EL1 (`Aarch64ExceptionState`) + `ERET` (achado: não decodificado ainda) + aborts precisos capturados no interpretador | abort→handler-EL1→`ERET`→continuação funcionando ponta-a-ponta; suíte verde |
| [B6.6.5](b6.6.5-aarch64-translation-generation.md) | `translationGeneration` em `jit64/BlockKey64`/`JitRuntime64` (RFC-IR-64BIT.md §5 item 3: tag própria do runtime A64, não empacotamento de 32 bits) | troca de "processo" executando código diferente sem servir bloco stale; suíte verde |
| [B6.6.6](b6.6.6-aarch64-virt64-host.md) | Hospedeiro `virt64` (repo novo): kernel arm64 mínimo até shell — **🧑 bloqueada no usuário** (mesmo bloqueio de toolchain/kernel real de B6.2/B4.1.5) | kernel mainline arm64 até shell busybox interativo, INTERPRETED e ASM |

## Armadilhas do épico

- O decode de "logical immediate" A64 (N:immr:imms → bitmask) é o encoding mais
  traiçoeiro da ISA — quando chegar (B6.3), transcrever `DecodeBitMasks` do pseudocódigo
  do manual + property test contra o corpus, nunca improvisar.
- Não existe LDM/STM nem predicação geral em A64 — não "portar" conceitos do IR-32;
  o IR-64 nasce com as formas certas (pair, condicional só em CSEL/B.cond).
- `W` (32-bit) escreve zerando os 32 altos do X — SEMPRE; esquecer isso em UMA op
  corrompe silenciosamente (cobrir com property test de todas as Alu64 wide=false).
