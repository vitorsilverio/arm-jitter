/* Smoke test da API C do arm-jitter (task A9, PR1 — backend interpretado).
 *
 * Cobre o aceite da task: isolate + core ARMv5TE, RAM mapeada, um programa ARM
 * cru (soma, sem SWI, termina por orcamento de ciclos) rodando ate um resultado
 * conferido em R0, e um callback MMIO (aj_set_mmio_callbacks) cujo read devolve
 * uma constante fornecida por este programa C.
 *
 * Compilar com cl.exe (vcvars64 carregado) contra os headers/lib gerados pelo
 * perfil `native-lib` — ver build-and-run-smoke.ps1.
 */
#include <graal_isolate.h>
#include "arm_jitter.h"

#include <stdio.h>
#include <string.h>

#define ARCH_ARMV5TE 1
#define BACKEND_INTERPRETED 0

static int failures = 0;

#define CHECK(cond, msg)                                                     \
    do {                                                                     \
        if (!(cond)) {                                                      \
            printf("FAIL: %s (linha %d)\n", (msg), __LINE__);               \
            failures++;                                                     \
        } else {                                                            \
            printf("ok:   %s\n", (msg));                                    \
        }                                                                   \
    } while (0)

static int g_mmio_read_constant = 0x7F;
static int g_write_calls = 0;
static int g_last_write_addr = -1;
static int g_last_write_size = -1;
static int g_last_write_value = -1;

static int mmio_read(graal_isolatethread_t *thread, void *userData, int address, int sizeBytes) {
    (void) thread;
    (void) address;
    (void) sizeBytes;
    return *(int *) userData;
}

static void mmio_write(graal_isolatethread_t *thread, void *userData, int address, int sizeBytes, int value) {
    (void) thread;
    (void) userData;
    g_last_write_addr = address;
    g_last_write_size = sizeBytes;
    g_last_write_value = value;
    g_write_calls++;
}

int main(void) {
    graal_isolate_t *isolate = NULL;
    graal_isolatethread_t *thread = NULL;
    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        printf("FAIL: graal_create_isolate\n");
        return 1;
    }

    /* ---- Aceite principal: programa ARM real (soma) rodando ate resultado ---- */
    long long handle = aj_create(thread, ARCH_ARMV5TE, BACKEND_INTERPRETED);
    CHECK(handle >= 0, "aj_create devolve handle valido");

    CHECK(aj_map_ram(thread, handle, 0, 0x10000) == 0, "aj_map_ram 64KiB em 0x0");

    /* mov r0,#5 ; mov r1,#37 ; add r0,r0,r1 ; b . (loop infinito, paramos por
     * orcamento de ciclos em aj_run_cycles — sem SWI, sem ELF, sem ABI). */
    unsigned char program[16];
    unsigned int words[4] = {0xE3A00005u, 0xE3A01025u, 0xE0800001u, 0xEAFFFFFEu};
    for (int i = 0; i < 4; i++) {
        program[i * 4 + 0] = (unsigned char) (words[i] & 0xFF);
        program[i * 4 + 1] = (unsigned char) ((words[i] >> 8) & 0xFF);
        program[i * 4 + 2] = (unsigned char) ((words[i] >> 16) & 0xFF);
        program[i * 4 + 3] = (unsigned char) ((words[i] >> 24) & 0xFF);
    }
    CHECK(aj_write(thread, handle, 0, (char *) program, sizeof(program)) == 0,
          "aj_write grava o programa ARM");

    aj_set_pc(thread, handle, 0, 0);
    long long consumed = aj_run_cycles(thread, handle, 20);
    CHECK(consumed >= 20, "aj_run_cycles executa pelo menos o orcamento pedido");

    int r0 = aj_get_register(thread, handle, 0);
    CHECK(r0 == 42, "R0 == 5 + 37 apos o bloco ARM real");

    /* ---- Callback MMIO: read devolve constante fornecida pelo C ---- */
    aj_set_mmio_callbacks(thread, handle, (void *) mmio_read, (void *) mmio_write,
                           (void *) &g_mmio_read_constant);

    char readBuf[4] = {0, 0, 0, 0};
    CHECK(aj_read(thread, handle, 0x20000000, readBuf, 4) == 0, "aj_read via callback MMIO");
    int mmioOk = 1;
    for (int i = 0; i < 4; i++) {
        if ((unsigned char) readBuf[i] != (unsigned char) g_mmio_read_constant) {
            mmioOk = 0;
        }
    }
    CHECK(mmioOk, "todo byte lido == constante do callback C");

    char writeBuf[4] = {0x11, 0x22, 0x33, 0x44};
    CHECK(aj_write(thread, handle, 0x20000000, writeBuf, 4) == 0, "aj_write via callback MMIO");
    CHECK(g_write_calls == 4, "callback de escrita chamado uma vez por byte");
    CHECK(g_last_write_addr == 0x20000003, "ultimo endereco de escrita e o do byte final");
    CHECK(g_last_write_value == 0x44, "ultimo valor de escrita e o do byte final");

    /* ---- Save/load state ---- */
    char stateBuf[4096];
    int stateLen = aj_save_state(thread, handle, stateBuf, (int) sizeof(stateBuf));
    CHECK(stateLen > 0, "aj_save_state devolve tamanho positivo");
    aj_set_register(thread, handle, 0, 0);
    CHECK(aj_get_register(thread, handle, 0) == 0, "R0 zerado antes do load_state");
    CHECK(aj_load_state(thread, handle, stateBuf, stateLen) == 0, "aj_load_state ok");
    CHECK(aj_get_register(thread, handle, 0) == 42, "R0 restaurado apos aj_load_state");

    /* ---- Nenhuma excecao Java escapa: handle invalido vira erro claro, nao crash ---- */
    long long badHandle = handle + 1000;
    CHECK(aj_get_register(thread, badHandle, 0) == -1, "handle invalido devolve -1, sem crash");
    const char *err = aj_last_error(thread, badHandle);
    CHECK(err != NULL && strlen(err) > 0, "aj_last_error registra mensagem para handle inexistente");

    /* aj_write fora de qualquer regiao mapeada, sem callback (handle novo) — cobre a
     * armadilha "sem excecao atravessando a fronteira" com um handle que tem RAM mas
     * nao tem MMIO nem a regiao escrita mapeada: cai no open-bus padrao (no-op), nao
       deve falhar. */
    long long handle2 = aj_create(thread, ARCH_ARMV5TE, BACKEND_INTERPRETED);
    CHECK(handle2 >= 0, "segundo aj_create independente");
    CHECK(aj_write(thread, handle2, 0x30000000, writeBuf, 4) == 0,
          "escrita em regiao sem RAM/MMIO cai no barramento aberto, sem crash");

    aj_destroy(thread, handle);
    aj_destroy(thread, handle2);

    graal_tear_down_isolate(thread);

    if (failures == 0) {
        printf("\nPASS: todas as %s verificacoes\n", "checagens");
        return 0;
    }
    printf("\nFAIL: %d verificacao(oes) falharam\n", failures);
    return 1;
}
