package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.MemoryAbortListener;

/// CP15 PMSAv8-32 (ARM DDI 0568A.c): liga as instruções `MCR`/`MRC` que configuram a MPU de um
/// core `ARMv8-R` AArch32 (B20.7, {@link ArmArchitecture#ARMV8R_32}) ao banco
/// {@link Pmsav8MpuRegisters}, que a {@link Pmsav8AddressSpace} LÊ para decidir permitir/negar
/// cada acesso EL1. Composição, não herança (mesmo precedente de {@link Pmsav7MpuCoprocessor}).
///
/// **`opc1` distingue EL1 (`0`) de EL2/Hyp (`4`)** — ao contrário do {@link Pmsav7MpuCoprocessor}
/// (PMSAv7 só tem `opc1=0`), a PMSAv8-32 tem registradores EL2 (`HPRBAR`/`HPRLAR`/`HPRSELR`/
/// `HPRENR`/`HMPUIR`) que reivindicam o MESMO `crn`/`crm`/`opc2` que o par EL1, diferindo só em
/// `opc1` — confirmados no QEMU real (`target/arm/helper.c`, `pmsav8r_cp_reginfo`/
/// `id_hmpuir_reginfo`, lidos por `curl` nesta sessão):
///
/// | Registrador | `opc1` | `crn` | `crm` | `opc2` |
/// |---|---|---|---|---|
/// | `MPUIR`   | 0 | 0 | 0 | 4 |
/// | `HMPUIR`  | 4 | 0 | 0 | 4 |
/// | `PRSELR`  | 0 | 6 | 2 | 1 |
/// | `HPRSELR` | 4 | 6 | 2 | 1 |
/// | `PRBAR`   | 0 | 6 | 3 | 0 |
/// | `HPRBAR`  | 4 | 6 | 3 | 0 |
/// | `PRLAR`   | 0 | 6 | 3 | 1 |
/// | `HPRLAR`  | 4 | 6 | 3 | 1 |
/// | `HPRENR`  | 4 | 6 | 1 | 1 |
///
/// **`PRENR` (`opc1=0`) NÃO foi confirmado no QEMU real** (o modelo QEMU só implementa `HPRENR`,
/// nunca um `PRENR` de EL1 — pesquisado nesta sessão, ver Javadoc de {@link Pmsav8MpuRegisters#prenr}):
/// esta classe atende `opc1=0,crn=6,crm=1,opc2=1` **por simetria inferida** com o par
/// `HPRENR`/`PRSELR`↔`HPRSELR`/`PRBAR`↔`HPRBAR`/`PRLAR`↔`HPRLAR` (todos diferindo só por `opc1`),
/// não por confirmação direta — armazenamento puro, sem consumidor (mesmo Javadoc), e se a
/// inferência estiver errada o pior caso é aceitar uma escrita que o hardware real rejeitaria
/// (nunca corrupção silenciosa de outro registrador, G8).
///
/// **Os 32 aliases diretos `PRBAR0..31`/`PRLAR0..31`/`HPRBAR0..31`/`HPRLAR0..31`** (acesso direto
/// por índice sem passar por `PRSELR`/`HPRSELR`, `crm=0b1000|bits[3:1](n)`) **NÃO são atendidos**
/// (`handles` devolve `false`, cai em `UNIMPLEMENTED` — G8, nunca confundido com outro
/// registrador): candidata a task futura nomeada, fora do orçamento desta task (B20.7 não cita os
/// aliases no "Inclui").
///
/// `SCTLR`/`DFSR`/`IFSR`/`DFAR`/`IFAR` (todos `opc1=0`) reusam o MESMO layout/posição de bit do
/// {@link Pmsav7MpuCoprocessor} (mesmos registradores físicos do ARM real, PMSAv7 e PMSAv8
/// coexistindo só pela versão de MPU).
///
/// **Gate por `ArmFeature#PMSA`** (mesmo padrão do `Pmsav7MpuCoprocessor`): o construtor recusa
/// uma {@link ArmArchitecture} sem `PMSA`.
public final class Cp15Pmsav8MpuCoprocessor implements CoprocessorBus, MemoryAbortListener {
    private static final int CP15 = 15;

    private static final int OPC1_EL1 = 0;
    private static final int OPC1_EL2 = 4;

    private static final int CRN_IDENTIFICATION = 0;
    private static final int CRM_MPUIR = 0;
    private static final int OPCODE2_MPUIR = 4;

    private static final int CRN_SYSTEM_CONTROL = 1;
    private static final int CRM_PRIMARY = 0;
    private static final int OPCODE2_SCTLR = 0;

    /// `PRENR`/`HPRENR` moram sob `CRn=6,CRm=1` — diferente de PMSAv7 (`RGNR` está em `CRm=2`).
    private static final int CRN_MPU_REGION = 6;
    private static final int CRM_PRENR = 1;
    private static final int OPCODE2_PRENR = 1;
    private static final int CRM_PRSELR = 2;
    private static final int OPCODE2_PRSELR = 1;
    private static final int CRM_REGION_CONFIG = 3;
    private static final int OPCODE2_PRBAR = 0;
    private static final int OPCODE2_PRLAR = 1;

    private static final int CRN_FAULT_STATUS = 5;
    private static final int OPCODE2_DFSR = 0;
    private static final int OPCODE2_IFSR = 1;
    private static final int OPCODE2_DFAR = 0;
    private static final int OPCODE2_IFAR = 2;

    private static final int MPUIR_DREGION_SHIFT = 8;

    private static final int SCTLR_M_BIT = 1;
    private static final int SCTLR_V_BIT = 1 << 13;
    private static final int SCTLR_BR_BIT = 1 << 17;

    private final Pmsav8MpuRegisters mpu;
    private final ArmCore core;

    private int sctlr;
    private int dfsr;
    private int ifsr;
    private int dfar;
    private int ifar;

    /// @param mpu          banco de estado que este coprocessador programa
    /// @param core         core cujo `SCTLR.V` (vetores altos) este coprocessador sincroniza
    /// @param architecture arquitetura do core, só para o gate de {@link ArmFeature#PMSA}
    /// @throws IllegalArgumentException se `architecture` não declara {@link ArmFeature#PMSA}
    public Cp15Pmsav8MpuCoprocessor(Pmsav8MpuRegisters mpu, ArmCore core, ArmArchitecture architecture) {
        if (!architecture.has(ArmFeature.PMSA)) {
            throw new IllegalArgumentException(
                    "Cp15Pmsav8MpuCoprocessor exige ArmFeature.PMSA; arquitetura fornecida não tem PMSA (perfil não-R?)");
        }
        this.mpu = mpu;
        this.core = core;
        mpu.setMpuEnabled(false);
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor != CP15) {
            return false;
        }
        boolean isEl1 = opcode1 == OPC1_EL1;
        boolean isEl2 = opcode1 == OPC1_EL2;
        if (!isEl1 && !isEl2) {
            return false;
        }
        return switch (crn) {
            case CRN_IDENTIFICATION -> crm == CRM_MPUIR && opcode2 == OPCODE2_MPUIR;
            case CRN_SYSTEM_CONTROL -> isEl1 && crm == CRM_PRIMARY && opcode2 == OPCODE2_SCTLR;
            case CRN_MPU_REGION -> (crm == CRM_PRENR && opcode2 == OPCODE2_PRENR)
                    || (crm == CRM_PRSELR && opcode2 == OPCODE2_PRSELR)
                    || (crm == CRM_REGION_CONFIG && (opcode2 == OPCODE2_PRBAR || opcode2 == OPCODE2_PRLAR))
                    || (isEl1 && crm == CRM_PRIMARY && (opcode2 == OPCODE2_DFAR || opcode2 == OPCODE2_IFAR));
            case CRN_FAULT_STATUS -> isEl1 && crm == CRM_PRIMARY && (opcode2 == OPCODE2_DFSR || opcode2 == OPCODE2_IFSR);
            default -> false;
        };
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        boolean isEl1 = opcode1 == OPC1_EL1;
        return switch (crn) {
            case CRN_IDENTIFICATION -> (isEl1 ? mpu.regionCount() : mpu.hRegionCount()) << MPUIR_DREGION_SHIFT;
            case CRN_SYSTEM_CONTROL -> sctlrValue();
            case CRN_MPU_REGION -> readMpuRegion(isEl1, crm, opcode2);
            case CRN_FAULT_STATUS -> opcode2 == OPCODE2_DFSR ? dfsr : ifsr;
            default -> throw unsupported(crn, crm, opcode2);
        };
    }

    private int readMpuRegion(boolean isEl1, int crm, int opcode2) {
        if (crm == CRM_PRENR) {
            return isEl1 ? mpu.prenr() : mpu.hprenr();
        }
        if (crm == CRM_PRSELR) {
            return isEl1 ? mpu.prselr() : mpu.hprselr();
        }
        if (crm == CRM_PRIMARY) {
            return opcode2 == OPCODE2_DFAR ? dfar : ifar;
        }
        if (isEl1) {
            return opcode2 == OPCODE2_PRBAR ? mpu.prbar() : mpu.prlar();
        }
        return opcode2 == OPCODE2_PRBAR ? mpu.hprbar() : mpu.hprlar();
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        boolean isEl1 = opcode1 == OPC1_EL1;
        switch (crn) {
            case CRN_IDENTIFICATION -> {
                // MPUIR/HMPUIR são só leitura — escrita ignorada.
            }
            case CRN_SYSTEM_CONTROL -> applySctlr(value);
            case CRN_MPU_REGION -> writeMpuRegion(isEl1, crm, opcode2, value);
            case CRN_FAULT_STATUS -> {
                if (opcode2 == OPCODE2_DFSR) {
                    dfsr = value;
                } else {
                    ifsr = value;
                }
            }
            default -> throw unsupported(crn, crm, opcode2);
        }
    }

    private void writeMpuRegion(boolean isEl1, int crm, int opcode2, int value) {
        if (crm == CRM_PRENR) {
            if (isEl1) {
                mpu.setPrenr(value);
            } else {
                mpu.setHprenr(value);
            }
            return;
        }
        if (crm == CRM_PRSELR) {
            if (isEl1) {
                mpu.setPrselr(value);
            } else {
                mpu.setHprselr(value);
            }
            return;
        }
        if (crm == CRM_PRIMARY) {
            if (opcode2 == OPCODE2_DFAR) {
                dfar = value;
            } else {
                ifar = value;
            }
            return;
        }
        if (isEl1) {
            if (opcode2 == OPCODE2_PRBAR) {
                mpu.setPrbar(value);
            } else {
                mpu.setPrlar(value);
            }
        } else {
            if (opcode2 == OPCODE2_PRBAR) {
                mpu.setHprbar(value);
            } else {
                mpu.setHprlar(value);
            }
        }
    }

    @Override
    public void onDataAbort(int faultAddress, int faultStatus) {
        dfar = faultAddress;
        dfsr = faultStatus;
    }

    @Override
    public void onPrefetchAbort(int faultAddress, int faultStatus) {
        ifar = faultAddress;
        ifsr = faultStatus;
    }

    private void applySctlr(int value) {
        sctlr = value;
        mpu.setMpuEnabled((value & SCTLR_M_BIT) != 0);
        mpu.setBackgroundRegionEnabled((value & SCTLR_BR_BIT) != 0);
        core.setHighVectors((value & SCTLR_V_BIT) != 0);
    }

    private int sctlrValue() {
        int value = sctlr;
        value = mpu.mpuEnabled() ? (value | SCTLR_M_BIT) : (value & ~SCTLR_M_BIT);
        value = mpu.backgroundRegionEnabled() ? (value | SCTLR_BR_BIT) : (value & ~SCTLR_BR_BIT);
        value = core.highVectors() ? (value | SCTLR_V_BIT) : (value & ~SCTLR_V_BIT);
        return value;
    }

    private static IllegalStateException unsupported(int crn, int crm, int opcode2) {
        return new IllegalStateException(
                "bug: executor não consultou handles fino: crn=c%d crm=c%d opcode2=%d".formatted(crn, crm, opcode2));
    }
}
