package dev.vitorsilverio.armjitter.capi;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// API C v1 do arm-jitter (task A9, PR1 — backend `INTERPRETED_IR`): expõe o núcleo ARM/THUMB
/// como uma biblioteca nativa (`arm_jitter.dll`/`.so`) consumível por qualquer linguagem com
/// FFI, via `native-image --shared` (perfil Maven `native-lib`). Ver `README.md` (seção
/// "Biblioteca nativa (C API)") para a receita de build e a tabela de funções.
///
/// ### Invariante de fronteira (obrigatória em TODO `@CEntryPoint`)
/// Nenhuma exceção Java pode escapar para o chamador C — cada método captura `Throwable`
/// internamente, registra a mensagem em {@link #setLastError} e devolve um código de erro
/// (negativo para inteiros/handles, `false`/no-op para o resto). `aj_last_error` devolve a
/// última mensagem, por handle quando possível, ou o buffer global quando o erro aconteceu
/// antes de um handle existir (ex.: `aj_create` com `architectureId` inválido).
///
/// ### Backend PR2 (Truffle)
/// `backendId=1` está reservado para a task A9 PR2 (depende de A7 verde, ver
/// `tasks/trilha-a-truffle/a9-native-shared-library.md`) — hoje devolve erro.
public final class ArmJitterCApi {
    private ArmJitterCApi() {
    }

    /// Deslocamento de página do `PagedAddressSpace` interno de cada handle (4KiB — granularidade
    /// de MMU real mais comum; ver `PagedAddressSpace` para o significado do parâmetro).
    private static final int PAGE_SHIFT = 12;

    private static final Map<Long, CoreHandle> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);

    // Buffer nativo persistente para erros sem handle associado (ex.: `aj_create` falho).
    // Alocado sob demanda dentro de um @CEntryPoint (nunca em inicializador estático — um
    // ponteiro `malloc`ado em tempo de BUILD do native-image seria inválido em runtime).
    // Sem inicializador explícito de propósito: um `WordFactory.nullPointer()` aqui vira
    // parte do inicializador estático da classe, que o native-image tenta SIMULAR em tempo
    // de build (`SimulateClassInitializerSupport`) — um valor Word não pode entrar no heap
    // da imagem como se fosse um `Object`, e o build falha em TODO `@CEntryPoint` da classe
    // com "Unsupported kind: Object" (achado real desta task). O padrão zero-inicializado
    // (bytecode `null`) de um campo estático Word É o ponteiro nulo em runtime sob SVM — não
    // precisa (e não deve) ser atribuído explicitamente.
    private static volatile CCharPointer globalErrorBuffer;

    private static synchronized CCharPointer globalErrorBuffer() {
        if (globalErrorBuffer.isNull()) {
            CCharPointer buffer = UnmanagedMemory.malloc(WordFactory.unsigned(CoreHandle.ERROR_BUFFER_SIZE));
            buffer.write(0, (byte) 0);
            globalErrorBuffer = buffer;
        }
        return globalErrorBuffer;
    }

    private static void setLastError(CoreHandle handle, Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            message = t.getClass().getSimpleName();
        }
        writeError(globalErrorBuffer(), message);
        if (handle != null) {
            writeError(handle.errorBuffer, message);
        }
    }

    private static void writeError(CCharPointer buffer, String message) {
        byte[] utf8 = message.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(utf8.length, CoreHandle.ERROR_BUFFER_SIZE - 1);
        for (int i = 0; i < n; i++) {
            buffer.write(i, utf8[i]);
        }
        buffer.write(n, (byte) 0);
    }

    private static CoreHandle lookup(long handle) {
        return HANDLES.get(handle);
    }

    /// Registra no buffer global (nenhum handle existe para anexar) que `handleId` não
    /// corresponde a nenhum handle vivo — chamado em todo ponto de entrada antes de devolver
    /// o código de erro, para que `aj_last_error` tenha uma mensagem útil mesmo sem handle.
    private static void recordHandleNotFound(long handleId) {
        writeError(globalErrorBuffer(), "handle desconhecido ou já destruído: " + handleId);
    }

    private static ArmArchitecture architectureFor(int architectureId) {
        return switch (architectureId) {
            case 0 -> ArmArchitecture.ARMV4T;
            case 1 -> ArmArchitecture.ARMV5TE;
            case 2 -> ArmArchitecture.ARMV6K;
            case 3 -> ArmArchitecture.ARMV6K_THUMB2;
            case 4 -> ArmArchitecture.ARMV7A;
            default -> throw new IllegalArgumentException("architectureId desconhecido: " + architectureId);
        };
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────────

    @CEntryPoint(name = "aj_create")
    static long ajCreate(IsolateThread thread, int architectureId, int backendId) {
        try {
            ArmArchitecture architecture = architectureFor(architectureId);
            if (backendId == 1) {
                // PR2 (task A9): backend TRUFFLE, depende de A7 verde nos dois ambientes — ver
                // tasks/trilha-a-truffle/a9-native-shared-library.md.
                throw new UnsupportedOperationException(
                        "backendId=1 (TRUFFLE) ainda não suportado nesta lib (task A9 PR2)");
            }
            if (backendId != 0) {
                throw new IllegalArgumentException("backendId desconhecido: " + backendId);
            }
            CallbackOpenBus openBus = new CallbackOpenBus();
            PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, openBus);
            ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
            JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(1024, 3, architecture);
            CoreHandle handle = new CoreHandle(core, runtime, memory, openBus);
            long id = NEXT_HANDLE.getAndIncrement();
            HANDLES.put(id, handle);
            return id;
        } catch (Throwable t) {
            setLastError(null, t);
            return -1L;
        }
    }

    @CEntryPoint(name = "aj_destroy")
    static void ajDestroy(IsolateThread thread, long handleId) {
        try {
            CoreHandle handle = HANDLES.remove(handleId);
            if (handle != null) {
                handle.destroy();
            }
        } catch (Throwable t) {
            setLastError(null, t);
        }
    }

    // ── Memória ──────────────────────────────────────────────────────────────────

    @CEntryPoint(name = "aj_map_ram")
    static int ajMapRam(IsolateThread thread, long handleId, int base, int size) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1;
        }
        try {
            handle.memory.mapRam(base, new byte[size]);
            return 0;
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1;
        }
    }

    @CEntryPoint(name = "aj_write")
    static int ajWrite(IsolateThread thread, long handleId, int addr, CCharPointer src, int len) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1;
        }
        try {
            for (int i = 0; i < len; i++) {
                handle.core.memory().write8(addr + i, src.read(i) & 0xFF);
            }
            return 0;
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1;
        }
    }

    @CEntryPoint(name = "aj_read")
    static int ajRead(IsolateThread thread, long handleId, int addr, CCharPointer dst, int len) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1;
        }
        try {
            for (int i = 0; i < len; i++) {
                dst.write(i, (byte) handle.core.memory().read8(addr + i));
            }
            return 0;
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1;
        }
    }

    @CEntryPoint(name = "aj_set_mmio_callbacks")
    static void ajSetMmioCallbacks(IsolateThread thread, long handleId,
                                    MmioReadFunctionPointer readFn, MmioWriteFunctionPointer writeFn,
                                    PointerBase userData) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return;
        }
        try {
            handle.openBus.setCallbacks(readFn, writeFn, userData);
        } catch (Throwable t) {
            setLastError(handle, t);
        }
    }

    // ── Registradores / execução ─────────────────────────────────────────────────

    @CEntryPoint(name = "aj_get_register")
    static int ajGetRegister(IsolateThread thread, long handleId, int index) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1;
        }
        try {
            return handle.core.register(index);
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1;
        }
    }

    @CEntryPoint(name = "aj_set_register")
    static void ajSetRegister(IsolateThread thread, long handleId, int index, int value) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return;
        }
        try {
            handle.core.setRegister(index, value);
        } catch (Throwable t) {
            setLastError(handle, t);
        }
    }

    @CEntryPoint(name = "aj_get_cpsr")
    static int ajGetCpsr(IsolateThread thread, long handleId) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return 0;
        }
        try {
            return handle.core.cpsr().get();
        } catch (Throwable t) {
            setLastError(handle, t);
            return 0;
        }
    }

    @CEntryPoint(name = "aj_set_cpsr")
    static void ajSetCpsr(IsolateThread thread, long handleId, int value) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return;
        }
        try {
            handle.core.setCpsr(value);
        } catch (Throwable t) {
            setLastError(handle, t);
        }
    }

    @CEntryPoint(name = "aj_set_pc")
    static void ajSetPc(IsolateThread thread, long handleId, int pc, int thumb) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return;
        }
        try {
            handle.core.setProgramCounter(pc);
            handle.core.setInstructionSet(thumb != 0 ? InstructionSet.THUMB : InstructionSet.ARM);
        } catch (Throwable t) {
            setLastError(handle, t);
        }
    }

    @CEntryPoint(name = "aj_run_cycles")
    static long ajRunCycles(IsolateThread thread, long handleId, long cycles) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1L;
        }
        if (handle.running) {
            setLastError(handle, new IllegalStateException(
                    "aj_run_cycles reentrante: um callback MMIO desta mesma chamada tentou chamá-lo de novo"));
            return -1L;
        }
        handle.running = true;
        try {
            long target = Math.max(cycles, 0L);
            long consumed = 0L;
            while (consumed < target) {
                consumed += handle.core.runBlock(handle.runtime);
            }
            return consumed;
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1L;
        } finally {
            handle.running = false;
        }
    }

    @CEntryPoint(name = "aj_set_irq_line")
    static void ajSetIrqLine(IsolateThread thread, long handleId, int asserted) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return;
        }
        try {
            handle.core.setInterruptLine(asserted != 0);
        } catch (Throwable t) {
            setLastError(handle, t);
        }
    }

    // ── Save state ───────────────────────────────────────────────────────────────

    @CEntryPoint(name = "aj_save_state")
    static int ajSaveState(IsolateThread thread, long handleId, CCharPointer buf, int cap) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                handle.core.saveState(out);
            }
            byte[] data = bytes.toByteArray();
            if (data.length > cap) {
                throw new IllegalArgumentException(
                        "buffer pequeno demais: precisa de " + data.length + ", cap=" + cap);
            }
            for (int i = 0; i < data.length; i++) {
                buf.write(i, data[i]);
            }
            return data.length;
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1;
        }
    }

    @CEntryPoint(name = "aj_load_state")
    static int ajLoadState(IsolateThread thread, long handleId, CCharPointer buf, int len) {
        CoreHandle handle = lookup(handleId);
        if (handle == null) {
            recordHandleNotFound(handleId);
            return -1;
        }
        try {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = buf.read(i);
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
                handle.core.loadState(in);
            }
            return 0;
        } catch (Throwable t) {
            setLastError(handle, t);
            return -1;
        }
    }

    // ── Diagnóstico ──────────────────────────────────────────────────────────────

    @CEntryPoint(name = "aj_last_error")
    static CCharPointer ajLastError(IsolateThread thread, long handleId) {
        CoreHandle handle = lookup(handleId);
        return handle != null ? handle.errorBuffer : globalErrorBuffer();
    }
}
