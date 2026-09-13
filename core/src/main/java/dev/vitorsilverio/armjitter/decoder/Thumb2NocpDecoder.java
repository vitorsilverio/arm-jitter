package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// `NOCP`/`NOCP_8_1` (perfil M, B15.2) — `target/isa-decode/m-nocp.decode` (QEMU): "For M-profile,
/// the architecture specifies that NOCP UsageFaults should take precedence over UNDEF faults over
/// the whole wide range of coprocessor-space encodings". Reconhece as 3 linhas do bloco `NOCP` do
/// arquivo real (as 9 formas específicas que o MESMO bloco prioriza antes de cair aqui — `VLLDM`/
/// `VLSTM`/`VSCCLRM`/`VMSR_VMRS`/`VLDR_sysreg`/`VSTR_sysreg` — ficam para B15.3/B15.5, que devem
/// ser plugadas ANTES desta extensão na lista de {@code thumb32DecoderExtensions}):
///
/// ```
/// NOCP         111- 1110 ---- ---- ---- cp:4 ---- ---- &nocp
/// NOCP         111- 110- ---- ---- ---- cp:4 ---- ---- &nocp
/// NOCP_8_1     111- 1111 ---- ---- ---- ---- ---- ---- &nocp cp=10
/// ```
///
/// **Achado central da spec (Armadilha 1)**: a forma 1 (`hi ∈ {0xEE, 0xFE}`) é o MESMO espaço de
/// bits que {@link Thumb2CoprocessorDecoder} (`MCR`/`MRC`) reivindica incondicionalmente — mas o
/// perfil M não tem coprocessador genérico de verdade (nenhuma FPU real neste emulador, e nenhum
/// teste pré-existente exercitava `MCR`/`MRC` sob `ARMV7M`/`ARMV7M_PURE`, confirmado por grep antes
/// desta task): qualquer encoding nesse espaço é NOCP em hardware real, regardless de `bit4`
/// (`Thumb2CoprocessorDecoder` só reconhecia o subconjunto `bit4=1`, e só `hi=0xEE`, nunca
/// `hi=0xFE`). Por isso este decoder SUBSTITUI `Thumb2CoprocessorDecoder` nos presets M-profile em
/// vez de coexistir com ele (`ArmArchitecture.ARMV6M`/`ARMV7M`/`ARMV7M_PURE`).
public final class Thumb2NocpDecoder implements DecoderExtension {
    /// Máscara/valor de `bits[31:29] = 111` (prefixo comum às 3 formas — nibble alto `∈ {0xE,0xF}`,
    /// bit 28 livre).
    private static final int TOP_THREE_BITS_MASK = 0xE000_0000;
    private static final int TOP_THREE_BITS_VALUE = 0xE000_0000;
    /// Forma 1 (`MCR`/`MRC` clássico): `bits[27:24] = 1110`.
    private static final int FORM1_MASK = 0x0F00_0000;
    private static final int FORM1_VALUE = 0x0E00_0000;
    /// Forma 2 (extension register load/store, coprocessador de 64 bits): `bits[27:25] = 110`,
    /// `bit24` livre.
    private static final int FORM2_MASK = 0x0E00_0000;
    private static final int FORM2_VALUE = 0x0C00_0000;
    /// `NOCP_8_1` (ARMv8.1-M): `bits[27:24] = 1111` fixo.
    private static final int NOCP_8_1_MASK = 0x0F00_0000;
    private static final int NOCP_8_1_VALUE = 0x0F00_0000;
    /// Campo `cp` (bits\[11:8\]) — comum às formas 1 e 2; `NOCP_8_1` usa um valor fixo em vez de
    /// extrair este campo (ver {@link #NOCP_8_1_FIXED_COPROCESSOR}).
    private static final int COPROCESSOR_FIELD_SHIFT = 8;
    private static final int COPROCESSOR_FIELD_MASK = 0xF;
    /// `NOCP_8_1` sempre reporta coprocessador 10 (`cp=10` fixo na gramática real do QEMU — a
    /// partir de ARMv8.1-M todo o espaço `1111` vira NOCP, não só coprocessador 10/11).
    private static final int NOCP_8_1_FIXED_COPROCESSOR = 10;

    /// Exclusão de `VLLDM_VLSTM` (`target/isa-decode/m-nocp.decode`: `1110 1100 001 l:1 rn:4 0000
    /// 1010 op:1 000 0000`) — vive DENTRO do espaço da forma 2, mas o QEMU prioriza este padrão
    /// específico ANTES do `NOCP` genérico (achado central desta task, medido pelo delta de
    /// `docs/COBERTURA-ISA.md`: sem esta exclusão, a célula `VLLDM_VLSTM` falsamente virava `✅` —
    /// o probe de cobertura usa os bits FIXOS do encoding real, então o padrão inteiro cai dentro
    /// da máscara genérica da forma 2). `l`/`rn`/`op` ficam livres (não fazem parte da máscara).
    /// Decodificar isto como `VLLDM`/`VLSTM` é B15.5, fora desta task — aqui só garante que a
    /// célula continua `❌` honesto (decode ausente) em vez de `NOCP` errado (G8: a arquitetura real
    /// NUNCA gera `NOCP` para `VLLDM`/`VLSTM`, mesmo sem FPU).
    private static final int VLLDM_VLSTM_MASK = 0xFFE0_FF7F;
    private static final int VLLDM_VLSTM_VALUE = 0xEC20_0A00;
    /// Exclusão de `VSCCLRM` (duas linhas do arquivo real, `1110 1100 1.01 1111 .... 101{0,1}
    /// imm`) — mesmo motivo/mesma técnica de {@link #VLLDM_VLSTM_MASK}: as duas variantes
    /// (precisão simples/dupla) só diferem no bit 8 (`1010` vs `1011`), por isso uma máscara só
    /// que deixa esse bit livre cobre as duas.
    private static final int VSCCLRM_MASK = 0xFFBF_0E00;
    private static final int VSCCLRM_VALUE = 0xEC9F_0A00;

    private final ArmArchitecture architecture;

    public Thumb2NocpDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if (!architecture.has(ArmFeature.M_PROFILE)) {
            return null;
        }
        if ((raw & TOP_THREE_BITS_MASK) != TOP_THREE_BITS_VALUE) {
            return null;
        }
        if ((raw & NOCP_8_1_MASK) == NOCP_8_1_VALUE) {
            return nocp(raw, address, condition, NOCP_8_1_FIXED_COPROCESSOR);
        }
        if ((raw & FORM1_MASK) == FORM1_VALUE) {
            int coprocessor = (raw >>> COPROCESSOR_FIELD_SHIFT) & COPROCESSOR_FIELD_MASK;
            return nocp(raw, address, condition, coprocessor);
        }
        if ((raw & FORM2_MASK) == FORM2_VALUE) {
            if ((raw & VLLDM_VLSTM_MASK) == VLLDM_VLSTM_VALUE || (raw & VSCCLRM_MASK) == VSCCLRM_VALUE) {
                return null;
            }
            int coprocessor = (raw >>> COPROCESSOR_FIELD_SHIFT) & COPROCESSOR_FIELD_MASK;
            return nocp(raw, address, condition, coprocessor);
        }
        return null;
    }

    private static DecodedInstruction nocp(int raw, int address, Condition condition, int coprocessor) {
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.NOCP,
                -1, -1, -1, coprocessor, false, false, false);
    }
}
