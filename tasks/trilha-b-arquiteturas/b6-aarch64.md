# B6 — AArch64 (épico refinado: B6.1/B6.2 executáveis; B6.3+ escopo fechado)

**Trilha:** B · **Depende de:** B0 ✅ (RFC aprovada 2026-07-10: **Opção B** — IR-64
paralelo + `Aarch64Core` irmão + `AddressSpace64`; ver `docs/RFC-IR-64BIT.md`) ·
**Repo:** arm-jitter + armbox
**Refinada 2026-07-15**: B6.1 e B6.2 abaixo são executáveis diretamente. B6.3–B6.6
têm escopo FECHADO mas ganham spec própria quando B6.2 concluir (a spec depende do
esqueleto real existir; NÃO executar sem essa rodada — regra do tasks/README).

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
| B6.3 | Base ISA inteira restante: logical imm (decode bitmask!), shifts/extends de registrador, CSEL/CSINC/CSINV/CSNEG, bitfield UBFM/SBFM/BFM + aliases, MUL/MADD/SDIV/UDIV, LDAXR/STLXR (monitor de B5.1 com endereço long — já previsto na RFC §5) | `busybox sh -c` completo no armbox64, zero divergência vs corpus |
| B6.4 | Backend ASM 64-bit: `GuestToHostMapper` com locals `long` (2 slots), cache/tiers/chaining reusados | harness de equivalência A64 (novo `BlockEquivalenceHarness64`), busybox ≥3× interpretador |
| B6.5 | FP/SIMD escalar mínimo (FMOV/FADD/FMUL/FDIV/FCMP/FCVT — o que a libc usa) | binários musl com printf de float |
| B6.6 | MMU v8 (VMSA64, 4KiB granule, EL0/EL1) + hospedeiro `virt64` | kernel arm64 mínimo até shell (reusar aprendizado de B4.1) |

## Armadilhas do épico

- O decode de "logical immediate" A64 (N:immr:imms → bitmask) é o encoding mais
  traiçoeiro da ISA — quando chegar (B6.3), transcrever `DecodeBitMasks` do pseudocódigo
  do manual + property test contra o corpus, nunca improvisar.
- Não existe LDM/STM nem predicação geral em A64 — não "portar" conceitos do IR-32;
  o IR-64 nasce com as formas certas (pair, condicional só em CSEL/B.cond).
- `W` (32-bit) escreve zerando os 32 altos do X — SEMPRE; esquecer isso em UMA op
  corrompe silenciosamente (cobrir com property test de todas as Alu64 wide=false).
