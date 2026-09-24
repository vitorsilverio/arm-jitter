package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;

import java.util.Objects;

/// CP15 TCM do Cortex-R5 (ARM DDI 0460D, §4.3.4 "c0, TCM Type Register" e §4.3.23/4.3.24 "c9,
/// BTCM/ATCM Region Register" — lidos via `curl`+`pdftotext` do PDF real da TRM nesta sessão, não
/// parafraseados; mirror do Wayback Machine porque `developer.arm.com` é renderizado por
/// JavaScript, mesma limitação já registrada no Javadoc de `Pmsav7MpuCoprocessor`). Liga as
/// instruções `MCR`/`MRC` que programam as TCMs a {@link TcmAddressSpace}, que a B20.4 usa para
/// decidir se um acesso vai para a TCM ou para o barramento externo.
///
/// **Decorator encadeado, não extensão de {@link
/// dev.vitorsilverio.armjitter.memory.mpu.Pmsav7MpuCoprocessor}** — decisão explícita da task
/// (B20.4, "Inclui" item 3), seguindo o precedente já documentado no Javadoc de {@code
/// Cp15VmsaCoprocessor}: *"quem precisar de outros registradores CP15 (TCM, cache real etc.)
/// encadeia outro `CoprocessorBus` decorator na frente, igual ao precedente do `ArmboxCp15` do
/// armbox"*. O hospedeiro compõe:
/// ```
/// Pmsav7MpuCoprocessor mpuBus = new Pmsav7MpuCoprocessor(mpu, core, architecture);
/// Cp15TcmCoprocessor tcmBus = new Cp15TcmCoprocessor(tcmSpace, mpuBus);
/// core.setCoprocessorBus(tcmBus); // ArmCore só guarda UM bus — tcmBus decide + delega o resto
/// core.setMemoryAbortListener(mpuBus); // gancho independente, continua sendo o bus interno
/// ```
/// Todo registrador que esta classe não atende ({@link #handles(int, int, int, int, int)} devolve
/// `false`) cai no `delegate` — nunca reivindica o coprocessador inteiro por engano (mesma cautela
/// de `handles` fino da B4.0.4.1).
///
/// Registradores atendidos (todos `opcode1=0`):
/// - `TCMTR` (`c0,c0,2`): só leitura. Bits `[2:0]` = nº de ATCMs, bits `[18:16]` = nº de BTCMs —
///   `1` quando a TCM correspondente tem `sizeBytes() > 0` (fisicamente presente), `0` caso
///   contrário (TRM: contagem real de interfaces implementadas). **Não inclui** (B20.4, "Não
///   inclui"): modelos com mais de uma ATCM/BTCM — este projeto só cobre o par Cortex-R5 (uma de
///   cada).
/// - `BTCM Region Register` (`c9,c1,0`) / `ATCM Region Register` (`c9,c1,1`): `Base` = bits
///   `[31:12]` (programável), `Size` = bits `[6:2]` (**só leitura** — "On writes this field is
///   ignored" no TRM real, reconstruído a partir do tamanho fixo de {@link TcmRegion}, nunca do
///   que o guest escreveu), `Enable` = bit `[0]` (programável, mas **RAZ** se a TCM correspondente
///   não existir fisicamente — `sizeBytes() == 0`). Bits `[11:7]` e `[1]` são `UNP`/`SBZ` no TRM —
///   esta classe devolve `0` neles na leitura (escolha simples e segura, nenhum software real relê
///   esses bits esperando um valor específico).
public final class Cp15TcmCoprocessor implements CoprocessorBus {
    private static final int CP15 = 15;

    private static final int CRN_IDENTIFICATION = 0;
    private static final int CRM_TCMTR = 0;
    private static final int OPCODE2_TCMTR = 2;
    private static final int TCMTR_ATCM_COUNT_SHIFT = 0;
    private static final int TCMTR_BTCM_COUNT_SHIFT = 16;

    private static final int CRN_TCM_REGION = 9;
    private static final int CRM_TCM_REGION = 1;
    private static final int OPCODE2_BTCM = 0;
    private static final int OPCODE2_ATCM = 1;

    private static final int REGION_BASE_MASK = 0xFFFF_F000;
    private static final int REGION_ENABLE_BIT = 0b1;

    private final TcmAddressSpace tcm;
    private final CoprocessorBus delegate;

    /// @param tcm      barramento de memória que este coprocessador programa (B20.4)
    /// @param delegate `CoprocessorBus` que atende os demais registradores CP15 (ex.:
    ///                  `Pmsav7MpuCoprocessor`) — todo registrador que esta classe não reconhece
    ///                  cai aqui
    public Cp15TcmCoprocessor(TcmAddressSpace tcm, CoprocessorBus delegate) {
        this.tcm = Objects.requireNonNull(tcm, "tcm");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15 || delegate.handles(coprocessor);
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor == CP15 && ownsRegister(crn, crm, opcode2)) {
            return true;
        }
        return delegate.handles(coprocessor, opcode1, crn, crm, opcode2);
    }

    private static boolean ownsRegister(int crn, int crm, int opcode2) {
        if (crn == CRN_IDENTIFICATION) {
            return crm == CRM_TCMTR && opcode2 == OPCODE2_TCMTR;
        }
        if (crn == CRN_TCM_REGION) {
            return crm == CRM_TCM_REGION && (opcode2 == OPCODE2_BTCM || opcode2 == OPCODE2_ATCM);
        }
        return false;
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor == CP15 && ownsRegister(crn, crm, opcode2)) {
            return crn == CRN_IDENTIFICATION ? tcmtr() : regionValue(opcode2 == OPCODE2_ATCM ? tcm.atcm() : tcm.btcm());
        }
        return delegate.read(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        if (coprocessor == CP15 && ownsRegister(crn, crm, opcode2)) {
            if (crn == CRN_IDENTIFICATION) {
                return; // TCMTR é só leitura (ARM DDI 0460D) — escrita ignorada.
            }
            int base = value & REGION_BASE_MASK;
            boolean enabled = (value & REGION_ENABLE_BIT) != 0;
            if (opcode2 == OPCODE2_ATCM) {
                tcm.reconfigureAtcm(base, enabled && tcm.atcm().sizeBytes() > 0);
            } else {
                tcm.reconfigureBtcm(base, enabled && tcm.btcm().sizeBytes() > 0);
            }
            return;
        }
        delegate.write(coprocessor, opcode1, crn, crm, opcode2, value);
    }

    private int tcmtr() {
        int atcmCount = tcm.atcm().sizeBytes() > 0 ? 1 : 0;
        int btcmCount = tcm.btcm().sizeBytes() > 0 ? 1 : 0;
        return (atcmCount << TCMTR_ATCM_COUNT_SHIFT) | (btcmCount << TCMTR_BTCM_COUNT_SHIFT);
    }

    private int regionValue(TcmRegion region) {
        int sizeRawCode = TcmSizeField.encodeRawCode(region.sizeBytes());
        int enableBit = region.sizeBytes() > 0 && region.enabled() ? REGION_ENABLE_BIT : 0; // RAZ se ausente
        return (region.baseAddress() & REGION_BASE_MASK) | (sizeRawCode << 2) | enableBit;
    }

    @Override
    public boolean handlesDouble(int coprocessor, int opcode1, int crm) {
        return delegate.handlesDouble(coprocessor, opcode1, crm);
    }

    @Override
    public long readDouble(int coprocessor, int opcode1, int crm) {
        return delegate.readDouble(coprocessor, opcode1, crm);
    }

    @Override
    public void writeDouble(int coprocessor, int opcode1, int crm, int rt, int rt2) {
        delegate.writeDouble(coprocessor, opcode1, crm, rt, rt2);
    }
}
