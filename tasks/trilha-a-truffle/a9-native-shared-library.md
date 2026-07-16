# A9 — arm-jitter como biblioteca nativa (.dll/.so) com API C, via GraalVM `--shared`

**Trilha:** A · **Depende de:** PR1: nada além do ambiente GraalVM (já instalado);
PR2: A7 verde · **Repos:** arm-jitter (módulo novo) · **2 PRs**
**Ambiente:** GraalVM 25 (`E:\graalvm-jdk-25.0.3+9.1`) + MSVC (`vcvars64.bat`) —
mesma receita documentada em `RELATORIO-A5.md`.

## Contexto (pedido do usuário, 2026-07-16)

Hoje o arm-jitter só é consumível por JVM (gbaemu/ndsemu/armbox). O GraalVM
`native-image --shared` compila código Java em uma **biblioteca nativa**
(`arm_jitter.dll`/`.so`) com funções C exportadas (`@CEntryPoint`) e header
gerado — qualquer linguagem com FFI (C/C++/Rust/Zig/Python-ctypes/C#/Go) passa a
poder embutir o emulador de CPU ARM. O JIT dentro da lib é o backend Truffle
(mesma motivação da trilha A inteira: ASM define classes em runtime, impossível
em native-image); o interpretador já roda em native-image desde a A5 — por isso
a task é dividida: **PR1 entrega a lib com backend interpretado (útil já), PR2
liga o Truffle quando A7 provar a compilação no SVM**.

## Desenho (decidido — não reavaliar)

### Módulo novo `capi/` no reactor Maven (irmão de `core/` e `truffle/`)

Depende de `core` (+ `truffle` no PR2) e de `org.graalvm.sdk:nativeimage` (as
anotações `@CEntryPoint`/`CCharPointer` etc.). Perfil Maven `native-lib` roda
`native-image --shared` (via `native-maven-plugin`, espelhando o perfil `native`
do armbox). O módulo NUNCA entra no build default (`mvn test` normal não exige
GraalVM — mesmo padrão de gate do armbox).

### API C v1 (`ArmJitterCApi.java`, tudo `@CEntryPoint`; nomes exportados com
prefixo `aj_`)

```c
// Isolate padrão do Graal (graal_create_isolate etc. vêm de graça no header).
long long aj_create(graal_isolatethread_t*, int architectureId, int backendId);
   // architectureId: enum estável documentado (0=ARMV4T, 1=ARMV5TE, 2=ARMV6K,
   //  3=ARMV6K_THUMB2, 4=ARMV7A quando existir); backendId: 0=INTERPRETED,
   //  1=TRUFFLE (PR2; no PR1 devolve erro). Retorna handle opaco (>0) ou -erro.
void aj_destroy(t, long long handle);
int  aj_map_ram(t, handle, unsigned int base, unsigned int size);
   // RAM alocada DENTRO da lib (PagedAddressSpace/mapRam — C3).
int  aj_write(t, handle, unsigned int addr, const void* src, unsigned int len);
int  aj_read (t, handle, unsigned int addr, void* dst, unsigned int len);
void aj_set_mmio_callbacks(t, handle, aj_read_fn, aj_write_fn, void* userData);
   // função C chamada para páginas NÃO mapeadas como RAM (mapHandler → ponte
   //  via CFunctionPointer); é assim que o hospedeiro C implementa periféricos.
int  aj_get_register(t, handle, int index);      // 0-15
void aj_set_register(t, handle, int index, int value);
unsigned int aj_get_cpsr(t, handle);  void aj_set_cpsr(t, handle, unsigned int);
void aj_set_pc(t, handle, unsigned int pc, int thumb);
long long aj_run_cycles(t, handle, long long cycles); // devolve ciclos executados
void aj_set_irq_line(t, handle, int asserted);
int  aj_save_state(t, handle, void* buf, int cap); int aj_load_state(t, handle, const void* buf, int len);
const char* aj_last_error(t, handle);             // mensagem da última falha
```

Regras: handles opacos numa tabela interna (nunca expor ponteiro de objeto Java);
NENHUMA exceção Java pode escapar de um `@CEntryPoint` (try/catch total →
código de erro + `aj_last_error`); tipos só primitivos/ponteiros C. Fora do
escopo v1 (documentar no header): múltiplos cores acoplados, GDB stub, SWI
customizado — entram por demanda.

### PR1 — lib + API com backend `INTERPRETED_IR`

1. Módulo `capi/` + API acima + perfil `native-lib` gerando
   `arm_jitter.dll` + header no Windows (o `.so` Linux é o MESMO comando em
   ambiente Linux — documentar no README; não há CI Linux hoje, fica como
   instrução verificada quando houver).
2. **Prova de consumo por outra linguagem**: `capi/src/test/c/smoke.c` —
   programa C (compilado com `cl.exe` no mesmo `vcvars64`) que: cria isolate +
   core ARMV5TE, mapeia 64K de RAM, escreve um blob ARM curto (reusar os `int[]`
   dos testes do armbox: soma + `SWI`-menos → terminar por contagem de ciclos),
   roda, lê R0 e compara. Script `build-and-run-smoke.ps1` versionado; a saída
   do smoke entra no aceite.
3. Callback MMIO testado no smoke: uma página handler cujo read devolve
   constante fornecida pelo C.

### PR2 — backend `TRUFFLE` (depende de A7 verde)

`backendId=1` liga `TruffleJitRuntimeFactory`; o build da lib inclui o runtime
Truffle (flags de build iguais às do armbox/A5). Aceite: o smoke com um loop de
2000 iterações roda mais rápido com `backendId=1` que `0` (mesma medição da A7),
e `TraceCompilation` mostra `opt done` dentro da lib.

## Aceite (PR1)

- `arm_jitter.dll` + header gerados pelo perfil; `smoke.c` compila e passa.
- `mvn -o test` normal continua verde SEM GraalVM (módulo inerte no build padrão).
- README do arm-jitter ganha seção "Biblioteca nativa (C API)" com o comando de
  build e a tabela de funções.

## Armadilhas

- `@CEntryPoint` exige método estático e o primeiro parâmetro
  `IsolateThread` — a thread C que chama PRECISA estar attachada ao isolate
  (o header gerado tem `graal_attach_thread`); documentar no README que
  callbacks MMIO são invocados NA thread que chamou `aj_run_cycles` (single
  thread, sem surpresa de concorrência).
- Callback C→Java→C reentrante: o `aj_read_fn` do usuário NÃO pode chamar de
  volta `aj_run_cycles` no mesmo handle (documentar; guarda com flag interna e
  erro claro em vez de deadlock/estado corrompido).
- Sem exceção atravessando a fronteira: qualquer `Throwable` vira `-1` +
  `aj_last_error` — teste dedicado (ex. `aj_write` fora de qualquer região).
- O nome das funções exportadas é ABI pública a partir do PR1 — mudanças depois
  são breaking change de verdade (G3 vale dobrado aqui); começar pequeno.
