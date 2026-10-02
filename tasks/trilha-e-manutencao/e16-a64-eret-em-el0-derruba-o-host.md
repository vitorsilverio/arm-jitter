# E16 — A64: `ERET` executado em `EL0` derruba o host em vez de entrar na exceção de instrução indefinida

**Trilha:** E (manutenção) · **Repo:** arm-jitter · **Depende de:** — · **Achado por:**
[E15.1](e15.1-rede-de-seguranca-contrato-e-catraca.md)
**Status:** ⬜

## Contexto

Achado ao montar a amostra de `Ir64Op.ExceptionReturn` do `Ir64OpContractTest` (E15.1): com o core
em `EL0`, `Ir64BlockExecutor#executeExceptionReturn` chama `exceptionState.elr(EL0)`, que cai em
`Aarch64ExceptionState#requireBankedLevel` e lança `IllegalArgumentException("EL0 não tem banco de
registradores de exceção")` — uma exceção de HOST, que nenhum dos cinco `catch` de `step`/
`executeBlock` trata.

Reproduzido de ponta a ponta em 2026-10-02 (não só pela amostra sintética):

```java
TestAddressSpace memory = new TestAddressSpace(0x1000);
memory.put32(0x100, 0xD69F03E0); // ERET
Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(memory)); // nasce em EL0
core.setProgramCounter(0x100);
new Ir64BlockExecutor().step(core); // IllegalArgumentException escapa
```

No hardware, `ERET` em `EL0` é `UNDEFINED` (pseudocódigo do `ERET` no Arm ARM: `if PSTATE.EL == EL0
then UNDEFINED`). Consequência prática: um programa de usuário rodando sobre um kernel emulado
derruba o emulador inteiro com uma instrução, em vez de receber a exceção que o kernel converteria
em `SIGILL`. É a mesma classe de bug da [E7](e7-a64-jit-guest-exceptions-escaping-to-host.md)
(exceção de guest virando exceção de host).

## Objetivo

`ERET` em `EL0` entra na exceção de instrução indefinida do guest, nos três caminhos de execução
(`step`, `executeBlock`, bloco compilado).

## Inclui

1. `Ir64BlockExecutor#executeExceptionReturn`: `EL0` → `throw new
   Aarch64UndefinedInstructionException()` antes de tocar o banco de exceção.
2. Conferir as formas autenticadas (`ERETAA`/`ERETAB`, `FEAT_PAuth`): se passam por outro executor,
   mesma checagem lá.
3. Testes: `step` e `executeBlock` com `ERET` em `EL0` terminam com o core em `EL1`, `ESR_EL1` de
   instrução indefinida e `ELR_EL1` apontando para o `ERET`; equivalência com o backend ASM
   (`BlockEquivalenceHarness64`).
4. `Ir64OpContractTest`: acrescentar o caso `EL0` de `ExceptionReturn` (hoje a amostra roda em
   `EL1` justamente por causa deste bug).

## Não inclui

- Outras instruções privilegiadas em `EL0` (`MSR`/`MRS` de registrador de `EL1`, `AT`, `TLBI`):
  não foram verificadas aqui. Se o levantamento do item 2 mostrar que o problema é geral, abrir
  task própria em vez de alargar esta.

## Passos

1. Teste que reproduz (o trecho do Contexto), vermelho.
2. Correção no executor; conferir `ERETAA`/`ERETAB`.
3. `mvn -o -pl core -am test jacoco:report`; depois `mvn -o install`.

## Aceite

- O trecho do Contexto não lança; o core entra na exceção de instrução indefinida.
- Zero diff de comportamento para `ERET` em `EL1`/`EL2`/`EL3` (suites existentes verdes).
- `jacoco:check` verde (piso da E15.1).

## Validação

G5: **não se aplica** — o diff fica em `executor64/` (lista de pacotes AArch64-only do
`tasks/README.md`). `mvn install` local obrigatório. JaCoCo: 0 linha e 0 branch `MISSED` no código
novo.

## Armadilhas

- A checagem tem de vir ANTES de `exceptionState.elr(source)`: é essa chamada que lança.
- O bloco compilado chama o mesmo executor via helper (`Ir64OpInterop`), então a correção no
  executor cobre o backend ASM — mas o teste de equivalência é o que prova.
