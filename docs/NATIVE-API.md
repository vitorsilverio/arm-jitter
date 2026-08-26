# Biblioteca nativa (C API)

O módulo opcional `capi/` (task A9, PR1) expõe o núcleo ARM/THUMB como uma
biblioteca nativa (`arm_jitter.dll`/`.so`) com funções C (`aj_*`), via GraalVM
`native-image --shared` — qualquer linguagem com FFI (C/C++/Rust/Zig/Python
`ctypes`/C#/Go) pode embutir o emulador sem depender de uma JVM. O módulo é
inerte no build padrão: `mvn test`/`install` não exigem GraalVM, só o perfil
`native-lib`.

Backend hoje: `INTERPRETED_IR` (o backend ASM define classes em runtime,
incompatível com native-image). O backend Truffle fica para a task A9 PR2,
que depende da revalidação sob `native-image` fechar nos dois ambientes.

## Build

```bat
:: 1. Instala o arm-jitter (core) no repo Maven local — JBR 25 é suficiente.
set JAVA_HOME=C:\Users\user\.jdks\jbr-25.0.3
mvn -o install -DskipTests

:: 2. Build da lib nativa — precisa do ambiente MSVC carregado + JAVA_HOME=GraalVM.
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
set JAVA_HOME=E:\graalvm-jdk-25.0.3+9.1
set PATH=%JAVA_HOME%\bin;%PATH%
cd capi
mvn -Pnative-lib -DskipTests package
:: -> target\arm_jitter.dll + arm_jitter.lib + arm_jitter.h + graal_isolate.h
```

Smoke test em C (cria isolate, roda um bloco ARM real, testa callback MMIO,
save/load state e o caminho de erro sem exceção atravessando a fronteira):

```powershell
capi\build-and-run-smoke.ps1
```

## Tabela de funções (API v1)

Toda função recebe `graal_isolatethread_t*` como primeiro parâmetro (gerado de
graça pelo header `graal_isolate.h`). Handles são opacos (`long long`, nunca um
ponteiro Java); nenhuma exceção Java atravessa a fronteira — falhas viram
código de erro negativo + `aj_last_error`.

| Função | Descrição |
|---|---|
| `aj_create(t, architectureId, backendId)` | Cria um core. `architectureId`: 0=ARMV4T, 1=ARMV5TE, 2=ARMV6K, 3=ARMV6K_THUMB2, 4=ARMV7A. `backendId`: 0=INTERPRETED (único suportado no PR1), 1=TRUFFLE (PR2). Devolve handle (`>=0`) ou `-1`. |
| `aj_destroy(t, handle)` | Libera o core e o buffer nativo de erro do handle. |
| `aj_map_ram(t, handle, base, size)` | Mapeia `size` bytes de RAM zerada em `base` (ambos múltiplos de 4KiB — `PagedAddressSpace`, task C3). |
| `aj_write(t, handle, addr, src, len)` / `aj_read(t, handle, addr, dst, len)` | Acesso direto à memória do core, byte a byte. |
| `aj_set_mmio_callbacks(t, handle, readFn, writeFn, userData)` | Instala os callbacks C chamados para todo endereço FORA de qualquer região de `aj_map_ram` (o barramento aberto do handle). `readFn`/`writeFn` são invocados NA THREAD que chamou `aj_run_cycles`/`aj_read`/`aj_write` — sem concorrência. |
| `aj_get_register`/`aj_set_register(t, handle, index, value)` | R0–R15. |
| `aj_get_cpsr`/`aj_set_cpsr(t, handle, value)` | CPSR bruto. |
| `aj_set_pc(t, handle, pc, thumb)` | PC + conjunto de instruções (ARM/THUMB). |
| `aj_run_cycles(t, handle, cycles)` | Executa blocos até acumular pelo menos `cycles` ciclos internos; devolve os ciclos realmente consumidos (pode passar do pedido — blocos não são cortados no meio) ou `-1` em erro. Não pode ser chamado de novo, no mesmo handle, de dentro de um callback MMIO disparado pela mesma chamada (erro claro, não deadlock). |
| `aj_set_irq_line(t, handle, asserted)` | Linha de IRQ do core. |
| `aj_save_state`/`aj_load_state(t, handle, buf, cap/len)` | Serialização via `ArmCore#saveState`/`loadState`. |
| `aj_last_error(t, handle)` | Última mensagem de erro deste handle (ou do buffer global, se o handle já não existe — ex. `aj_create` falho). |

Fora de escopo do v1 (documentado, entram por demanda): múltiplos cores
acoplados, GDB stub, dispatcher de SWI customizado.
