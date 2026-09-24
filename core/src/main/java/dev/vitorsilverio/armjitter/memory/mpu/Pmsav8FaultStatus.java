package dev.vitorsilverio.armjitter.memory.mpu;

/// Código de status de falha (`FS`) do formato **PMSAv8-32, perfil NÃO-M** (`Cortex-R52`/`R52+`),
/// usado para preencher `DFSR`/`IFSR` quando {@link Pmsav8AddressSpace} nega um acesso (B20.7).
/// **Enum irmão** de {@link PmsaFaultStatus} (PMSAv7) e de
/// {@link dev.vitorsilverio.armjitter.memory.mmu.FaultStatus} (VMSA) — nunca uma extensão de
/// nenhum dos dois.
///
/// **Achado real, confirmado linha a linha no QEMU real** (`target/arm/ptw.c`,
/// `pmsav8_mpu_lookup`, e `target/arm/internals.h`, `arm_fi_to_sfsc`; lidos por `curl` direto
/// nesta sessão — Armadilha 3 da B20.7, "os códigos de falha de PMSAv8-32 não foram lidos no
/// código real"): ao contrário do PMSAv7 (que distingue `BACKGROUND(0x0)` de `PERMISSION(0xD)`
/// pelo nível do fault), a PMSAv8-32 **perfil não-M** grava `fi->level = 0` incondicionalmente
/// antes de qualquer decisão (`pmsav8_mpu_lookup`, logo após checar `arm_feature(..., ARM_FEATURE_M)`),
/// e os TRÊS desfechos de falha do laço — nenhuma região casou e o mapa de fundo não se aplica,
/// MÚLTIPLAS regiões casaram (PMSAv8 NÃO tem prioridade — Achado 2 da B20.7, ao contrário do
/// PMSAv7) e uma ÚNICA região casou mas `AP`/`XN` negam o acesso — resultam todos em
/// `fi->type = ARMFault_Permission` com o MESMO `fi->level = 0`. `arm_fi_to_sfsc` traduz
/// `ARMFault_Permission` com `level != 1` para `fsc = 0xf`. Não existe um código `BACKGROUND`
/// PRÓPRIO para PMSAv8 perfil não-M: um único código cobre os três casos.
public enum Pmsav8FaultStatus {
    /// Único código de falha que {@link Pmsav8AddressSpace} produz: nenhuma região casou (e o mapa
    /// de fundo não se aplica), múltiplas regiões casaram (sobreposição — sempre falha em PMSAv8,
    /// nunca prioridade), ou uma região casou e `AP`/`XN` negam o tipo de acesso pedido.
    PERMISSION(0b01111);

    private final int code;

    Pmsav8FaultStatus(int code) {
        this.code = code;
    }

    /// Valor de 5 bits do `FS` de PMSAv8-32 perfil não-M. O único código modelado aqui não tem o
    /// bit 4 ligado (`< 0b10000`), mesma observação já registrada por {@link PmsaFaultStatus#code}.
    public int code() {
        return code;
    }
}
