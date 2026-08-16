package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Utilitários compartilhados pelos executores de IR interpretada.
final class IrExecutionSupport {
    private final ArmArchitecture architecture;

    IrExecutionSupport(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    ArmArchitecture architecture() {
        return architecture;
    }

    int operand(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.value();
            case IrOperand.Register register -> registerValue(core, register.index(), register.valueOverride());
            case IrOperand.ShiftedRegister register -> {
                int value = shiftedRegisterOperand(core, register);
                yield register.negated() ? -value : value;
            }
        };
    }

    int registerValue(ArmCore core, int register, int valueOverride) {
        // -1 é o sentinela de "sem override"; um override de PC real (address+8/12) é sempre
        // alinhado a word/half e por isso nunca pode ser -1. Comparar contra -1 (não >= 0) é
        // essencial para endereços altos como a BIOS do ARM9 em 0xFFFF0000+, cujos overrides são
        // negativos como int.
        return valueOverride != -1 ? valueOverride : core.register(register);
    }

    boolean operandCarryOut(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.carryOutKnown()
                    ? immediate.carryOut()
                    : core.cpsr().carry();
            case IrOperand.Register ignored -> core.cpsr().carry();
            case IrOperand.ShiftedRegister register -> shiftedRegisterCarryOut(core, register);
        };
    }

    void writeLoadedRegister(ArmCore core, int register, int value) {
        if (register == 15) {
            loadToPc(core, value);
            return;
        }
        core.setRegister(register, value);
    }

    void loadToPc(ArmCore core, int value) {
        if (core.exceptionModel().interceptsBranch(value)) {
            core.exceptionModel().branchIntercepted(core, value);
            return;
        }
        if (architecture.has(ArmFeature.LOAD_PC_INTERWORKING)) {
            core.cpsr().setThumbMode((value & 1) != 0);
            core.setProgramCounter(value & ~1);
        } else {
            alignAndSetPc(core, value);
        }
    }

    boolean writeAluDestination(ArmCore core, int register, int value, boolean restoreSpsr) {
        if (register != 15) {
            core.setRegister(register, value);
            return false;
        }
        if (restoreSpsr) {
            restoreCpsrFromCurrentSpsr(core);
        }
        alignAndSetPc(core, value);
        return true;
    }

    void alignAndSetPc(ArmCore core, int value) {
        int mask = core.cpsr().isThumbMode() ? ~1 : ~3;
        core.setProgramCounter(value & mask);
    }

    void restoreCpsrFromCurrentSpsr(ArmCore core) {
        CpuMode mode = core.mode();
        if (mode == CpuMode.USER || mode == CpuMode.SYSTEM) {
            return;
        }
        core.setCpsr(core.spsr(mode));
    }

    int read32Arm7(ArmCore core, int address) {
        int aligned = address & ~3;
        int value = core.memory().read32(aligned);
        core.addMemoryCycles(aligned, 4, MemoryAccessType.DATA_READ);
        int rotated = Integer.rotateRight(value, (address & 3) * 8);
        return applyDataEndiannessWord(core, rotated);
    }

    /// `LDRB`/`STRB` (byte único): invariante a `CPSR.E` por definição (BE8 é "byte-invariant" —
    /// ver a task B1.8) — nunca aplicar troca de bytes aqui.
    int read8Arm7(ArmCore core, int address) {
        int value = core.memory().read8(address);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
        return value;
    }

    int read16Arm7(ArmCore core, int address, boolean signed) {
        if (signed && (address & 1) != 0) {
            // Quirk ARMv4T de LDRSH desalinhado: vira leitura de BYTE (sign-extend do byte no
            // endereço ímpar) — acesso de byte disfarçado, sem troca de endianness (ver acima).
            int value = core.memory().read8(address);
            core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
            return (byte) value;
        }
        int aligned = address & ~1;
        int value = core.memory().read16(aligned);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_READ);
        int rotated = (address & 1) == 0 ? value : Integer.rotateRight(value, 8);
        return applyDataEndiannessHalfword(core, rotated);
    }

    void write16Arm7(ArmCore core, int address, int value) {
        core.memory().write16(address, applyDataEndiannessHalfword(core, value));
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 2);
    }

    /// `LDRB`/`STRB`: ver {@link #read8Arm7}.
    void write8Arm7(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 1);
    }

    void write32Arm7(ArmCore core, int address, int value) {
        core.memory().write32(address, applyDataEndiannessWord(core, value));
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 4);
    }

    /// Tamanho em bytes de um acesso de word, usado para custear em ciclos os acessos
    /// atravessados de {@link ArmFeature#UNALIGNED_ACCESS} (uma leitura/escrita larga, como o
    /// caminho legado — ver a task B1.7).
    private static final int WORD_ACCESS_SIZE_BYTES = 4;
    /// Tamanho em bytes de um acesso de halfword, mesmo papel que {@link #WORD_ACCESS_SIZE_BYTES}.
    private static final int HALFWORD_ACCESS_SIZE_BYTES = 2;
    /// Largura de um byte em bits, usada para compor valores atravessados little-endian.
    private static final int BITS_PER_BYTE = 8;
    /// Máscara de um byte sem sinal, usada ao compor valores atravessados little-endian.
    private static final int BYTE_MASK = 0xFF;

    /// `LDR` (word): sob {@link ArmFeature#UNALIGNED_ACCESS} e com destino diferente do PC, um
    /// endereço GENUINAMENTE desalinhado (`address % 4 != 0`) faz o acesso "atravessado" ARMv6+
    /// (little-endian, byte a byte, ARM DDI 0406C A3.2.1) em vez da rotação ARMv4T de
    /// {@link #read32Arm7}. `LDR pc,...` continua exigindo o alinhamento legado mesmo com a
    /// feature (task B1.7, item 4) — assim como LDM/STM, LDRD/STRD, LDREX/STREX e SWP, que chamam
    /// {@link #read32Arm7} diretamente e nunca este método.
    ///
    /// **Achado real (task F3/`virtual-arm-box`, ARMv6K/ARM11 rodando um kernel Linux de
    /// sistema)**: um endereço JÁ ALINHADO nunca precisa do caminho atravessado — hardware ARMv6+
    /// real faz uma ÚNICA transação de barramento de 32 bits para um `LDR` alinhado, igual ao
    /// ARMv4T/v5TE (é só o caso desalinhado que a arquitetura trata diferente). Antes desta
    /// correção, QUALQUER `LDR` não-PC sob a feature (mesmo já alinhado) passava por
    /// {@link #readCrossedWord}, que compõe o valor a partir de 4 chamadas independentes de
    /// {@link dev.vitorsilverio.armjitter.memory.AddressSpace#read8} em `address`, `address+1`,
    /// `address+2`, `address+3` — corretíssimo para RAM (onde `read8` é byte-endereçável de
    /// verdade), mas silenciosamente ERRADO para um periférico MMIO cujo `read8`/`read16` não
    /// reimplementam "byte N da word alinhada" (a maioria não faz, incluindo os do próprio
    /// `virtual-arm-box`): os 3 bytes altos caem no `default` do periférico (offset desconhecido)
    /// em vez de extrair do valor real de 32 bits, truncando SILENCIOSAMENTE todo registrador MMIO
    /// lido por `LDR` alinhado ao byte baixo. Esse foi o bug real que impedia o driver de IRQ do
    /// BCM2835 (`irq-bcm2835.c`, `get_next_armctrl_hwirq`/`armctrl_translate_bank`) de nunca ver
    /// `IRQ_PENDING_BASIC`/`IRQ_PENDING_1` diferente de zero, mesmo com o bit correto armado no
    /// periférico — o handler de IRQ nunca despachava, causando reentrada perpétua em
    /// `CpuMode.IRQ` (ver `Raspi1BootTest`, task F3). Nunca foi pego antes porque nenhum consumidor
    /// prévio rodava um sistema MMIO real sob um preset com `UNALIGNED_ACCESS` (só o `armbox`
    /// user-mode, sem periféricos MMIO com layout de registrador esparso).
    int readWordForLoad(ArmCore core, int address, boolean loadsToProgramCounter) {
        if (!architecture.has(ArmFeature.UNALIGNED_ACCESS) || loadsToProgramCounter || isWordAligned(address)) {
            return read32Arm7(core, address);
        }
        int value = readCrossedWord(core, address);
        core.addMemoryCycles(address, WORD_ACCESS_SIZE_BYTES, MemoryAccessType.DATA_READ);
        return applyDataEndiannessWord(core, value);
    }

    /// `LDRH`/`LDRSH` (halfword): mesmo fork de {@link #readWordForLoad}, mas para halfword,
    /// incluindo a mesma correção de só atravessar quando `address` é GENUINAMENTE desalinhado
    /// (`address % 2 != 0`) — ver Javadoc de {@link #readWordForLoad} para o achado real. Sob a
    /// feature, num endereço desalinhado, o valor cru atravessado (sem sinal) é devolvido — o
    /// chamador já aplica {@link #signExtendIfNeeded} por cima igual ao caminho legado, então o
    /// quirk ARMv4T de `LDRSH` desalinhado (vira `LDRSB` do byte alto, preservado em
    /// {@link #read16Arm7}) não se aplica aqui: ARMv6+ lê o halfword atravessado inteiro e
    /// sinal-estende normalmente.
    int readHalfwordForLoad(ArmCore core, int address, boolean signed, boolean loadsToProgramCounter) {
        if (!architecture.has(ArmFeature.UNALIGNED_ACCESS) || loadsToProgramCounter || isHalfwordAligned(address)) {
            return read16Arm7(core, address, signed);
        }
        int value = readCrossedHalfword(core, address);
        core.addMemoryCycles(address, HALFWORD_ACCESS_SIZE_BYTES, MemoryAccessType.DATA_READ);
        return applyDataEndiannessHalfword(core, value);
    }

    /// `STR` (word): sob {@link ArmFeature#UNALIGNED_ACCESS} e com `address` GENUINAMENTE
    /// desalinhado, escreve atravessado (little-endian, byte a byte) em vez de delegar a
    /// {@link #write32Arm7} como o caminho legado faz — que por sua vez depende de como o
    /// {@code AddressSpace} concreto trata um endereço desalinhado (algumas implementações
    /// alinham para baixo, outras já compõem por byte; não é nosso papel mudar isso no caminho
    /// legado, task B1.7 Armadilhas). Sob a feature, num endereço desalinhado, a semântica passa a
    /// ser explícita e independente do `AddressSpace` de baixo. Um endereço JÁ ALINHADO usa
    /// {@link #write32Arm7} mesmo sob a feature — mesma correção e mesmo motivo de
    /// {@link #readWordForLoad} (ver Javadoc de lá): hardware real faz uma única transação de
    /// barramento para um `STR` alinhado, e decompor em 4 `write8` mesmo assim é seguro só por
    /// coincidência para a maioria dos periféricos MMIO (word único, offset alto sem efeito
    /// colateral) — não é uma garantia arquitetural, e quebra qualquer periférico com registrador
    /// vizinho de verdade no byte adjacente.
    void writeWordForStore(ArmCore core, int address, int value) {
        if (!architecture.has(ArmFeature.UNALIGNED_ACCESS) || isWordAligned(address)) {
            write32Arm7(core, address, value);
            return;
        }
        writeCrossedWord(core, address, applyDataEndiannessWord(core, value));
        core.addMemoryCycles(address, WORD_ACCESS_SIZE_BYTES, MemoryAccessType.DATA_WRITE);
    }

    /// `STRH`: mesmo fork de {@link #writeWordForStore}, mas para halfword — mesma correção de só
    /// atravessar quando `address` é GENUINAMENTE desalinhado (`address % 2 != 0`).
    void writeHalfwordForStore(ArmCore core, int address, int value) {
        if (!architecture.has(ArmFeature.UNALIGNED_ACCESS) || isHalfwordAligned(address)) {
            write16Arm7(core, address, value);
            return;
        }
        writeCrossedHalfword(core, address, applyDataEndiannessHalfword(core, value));
        core.addMemoryCycles(address, HALFWORD_ACCESS_SIZE_BYTES, MemoryAccessType.DATA_WRITE);
    }

    /// Máscara para testar alinhamento de word (4 bytes) — nomeada por G6.
    private static final int WORD_ALIGNMENT_MASK = 0x3;
    /// Máscara para testar alinhamento de halfword (2 bytes) — nomeada por G6.
    private static final int HALFWORD_ALIGNMENT_MASK = 0x1;

    /// `true` quando `address` já cai num múltiplo de 4 — usado por {@link #readWordForLoad}/
    /// {@link #writeWordForStore} para decidir se o acesso "atravessado" byte a byte da
    /// {@link ArmFeature#UNALIGNED_ACCESS} é necessário (só quando desalinhado de verdade).
    private static boolean isWordAligned(int address) {
        return (address & WORD_ALIGNMENT_MASK) == 0;
    }

    /// `true` quando `address` já cai num múltiplo de 2 — mesmo papel de {@link #isWordAligned}
    /// para {@link #readHalfwordForLoad}/{@link #writeHalfwordForStore}.
    private static boolean isHalfwordAligned(int address) {
        return (address & HALFWORD_ALIGNMENT_MASK) == 0;
    }

    /// Compõe uma word atravessada a partir de 4 leituras de byte independentes (cada byte é
    /// trivialmente "alinhado", então isto atravessa fronteiras de página/região do
    /// `AddressSpace` corretamente, ao contrário de ler uma word larga e recortar). Ciclos NÃO
    /// são contados aqui — o chamador cobra uma vez, como uma leitura larga (task B1.7).
    private int readCrossedWord(ArmCore core, int address) {
        return (core.memory().read8(address) & BYTE_MASK)
                | ((core.memory().read8(address + 1) & BYTE_MASK) << BITS_PER_BYTE)
                | ((core.memory().read8(address + 2) & BYTE_MASK) << (2 * BITS_PER_BYTE))
                | ((core.memory().read8(address + 3) & BYTE_MASK) << (3 * BITS_PER_BYTE));
    }

    /// Mesmo princípio de {@link #readCrossedWord}, para halfword (valor sem sinal).
    private int readCrossedHalfword(ArmCore core, int address) {
        return (core.memory().read8(address) & BYTE_MASK)
                | ((core.memory().read8(address + 1) & BYTE_MASK) << BITS_PER_BYTE);
    }

    /// Escreve uma word atravessada como 4 escritas de byte independentes — mesmo raciocínio de
    /// {@link #readCrossedWord} para não depender de como o `AddressSpace` concreto trataria uma
    /// `write32` desalinhada.
    private void writeCrossedWord(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.memory().write8(address + 1, value >>> BITS_PER_BYTE);
        core.memory().write8(address + 2, value >>> (2 * BITS_PER_BYTE));
        core.memory().write8(address + 3, value >>> (3 * BITS_PER_BYTE));
        core.notifyOrdinaryWrite(address, WORD_ACCESS_SIZE_BYTES);
    }

    /// Mesmo princípio de {@link #writeCrossedWord}, para halfword.
    private void writeCrossedHalfword(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.memory().write8(address + 1, value >>> BITS_PER_BYTE);
        core.notifyOrdinaryWrite(address, HALFWORD_ACCESS_SIZE_BYTES);
    }

    /// `SETEND`/`CPSR.E` (ARMv6, task B1.8): BE8 ("byte-invariant big-endian", ARM DDI 0406C
    /// A2.9) troca a ordem em que os bytes de um acesso de dados WORD são combinados/
    /// decompostos quando `CPSR.E=1`. A busca de instrução NUNCA é afetada (sempre
    /// little-endian) — só os pontos de acesso de DADO chamam este método, nunca o fetch.
    /// Byte único (`LDRB`/`STRB`) é invariante por definição e nunca chama isto (ver
    /// {@link #read8Arm7}/{@link #write8Arm7}). Com `E=0` (default de todo preset, inclusive
    /// os que têm a feature) isto é sempre a identidade — G3.
    private int applyDataEndiannessWord(ArmCore core, int littleEndianValue) {
        return core.cpsr().isBigEndian() ? Integer.reverseBytes(littleEndianValue) : littleEndianValue;
    }

    /// Mesmo princípio de {@link #applyDataEndiannessWord}, para um acesso de dados HALFWORD:
    /// troca os 2 bytes baixos do valor (os únicos relevantes para um halfword).
    private int applyDataEndiannessHalfword(ArmCore core, int littleEndianValue) {
        if (!core.cpsr().isBigEndian()) {
            return littleEndianValue;
        }
        int low = littleEndianValue & BYTE_MASK;
        int high = (littleEndianValue >>> BITS_PER_BYTE) & BYTE_MASK;
        return (low << BITS_PER_BYTE) | high;
    }

    int effectiveRegisterMask(int mask, boolean emptyRegisterList) {
        if (!emptyRegisterList) {
            return mask;
        }
        // ARM7TDMI transfere R15 para lista vazia; ARMv5 não transfere nada (só o writeback de
        // base ±0x40, que ainda usa a contagem de 16).
        return architecture.has(ArmFeature.EMPTY_RLIST_NO_TRANSFER) ? 0 : (1 << 15);
    }

    int effectiveRegisterCount(int mask, boolean emptyRegisterList) {
        return emptyRegisterList ? 16 : Integer.bitCount(mask);
    }

    boolean shouldWriteBackMultiple(boolean writeback, boolean load, int mask, int baseRegister) {
        if (!writeback) {
            return false;
        }
        if (load && (mask & (1 << baseRegister)) != 0) {
            if (!architecture.has(ArmFeature.LDM_WRITEBACK_BASE_IN_LIST)) {
                return false; // ARMv4: a base na lista sempre fica com o valor carregado
            }
            // ARMv5: o writeback ainda acontece, exceto quando a base é o registrador mais alto
            // de uma transferência com múltiplos registradores — nesse caso vence o valor carregado.
            boolean baseIsHighest = (mask >>> baseRegister) == 1;
            boolean multiple = Integer.bitCount(mask) > 1;
            return !(baseIsHighest && multiple);
        }
        return true;
    }

    int multipleStoreRegisterValue(
            ArmCore core,
            IrOp.MultipleTransfer transfer,
            int register,
            int firstRegister,
            int writebackAddress) {
        if (transfer.userMode()) {
            return core.bankedRegister(CpuMode.USER, register);
        }
        // Quirk do ARM7TDMI: STM do registrador base, quando ele não é o primeiro da lista,
        // armazena o valor já incrementado (writeback). ARMv5 sempre armazena a base original.
        if (!architecture.has(ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL)
                && transfer.writeback()
                && register == transfer.base()
                && register != firstRegister) {
            return writebackAddress;
        }
        return registerValue(core, register, register == 15 ? transfer.pcStoreValueOverride() : -1);
    }

    int mergePsr(int current, int value, int fieldMask) {
        int mask = 0;
        if ((fieldMask & 0x1) != 0) {
            mask |= 0x0000_00FF;
        }
        if ((fieldMask & 0x2) != 0) {
            mask |= 0x0000_FF00;
        }
        if ((fieldMask & 0x4) != 0) {
            mask |= 0x00FF_0000;
        }
        if ((fieldMask & 0x8) != 0) {
            mask |= 0xFF00_0000;
        }
        return (current & ~mask) | (value & mask);
    }

    int cpsrWriteFieldMask(ArmCore core, int fieldMask) {
        return core.mode() == CpuMode.USER ? fieldMask & 0x8 : fieldMask;
    }

    int signExtendIfNeeded(int value, int sizeBytes, boolean signed) {
        if (!signed) {
            return value;
        }
        return switch (sizeBytes) {
            case 1 -> (byte) value;
            case 2 -> (short) value;
            default -> value;
        };
    }

    void setLogicFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    void setLogicFlags(ArmCore core, int result, boolean carry) {
        core.cpsr().setNzcv(result < 0, result == 0, carry, core.cpsr().overflow());
    }

    void setAddFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(result, left) < 0;
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    void setAdcFlags(ArmCore core, int left, int right, int carryIn, int result) {
        long unsigned = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right) + carryIn;
        long signed = (long) left + (long) right + carryIn;
        boolean carry = (unsigned >>> 32) != 0;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    void setSbcFlags(ArmCore core, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    boolean shiftCarryOut(ArmCore core, int value, IrOpCode opcode, int amount, boolean immediateShift) {
        if (!immediateShift && amount == 0) {
            return core.cpsr().carry();
        }
        return switch (opcode) {
            case LSL -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.LSL, amount);
            case LSR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.LSR, amount);
            case ASR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.ASR, amount);
            case ROR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.ROR, amount);
            default -> throw new IllegalStateException("Unexpected shift opcode: " + opcode);
        };
    }

    private int shiftedRegisterOperand(ArmCore core, IrOperand.ShiftedRegister register) {
        int value = registerValue(core, register.index(), register.valueOverride());
        if (register.rrx()) {
            return (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
        }
        int amount = register.amountRegister() >= 0
                ? registerValue(core, register.amountRegister(), register.amountValueOverride()) & 0xFF
                : register.amount();
        if (register.amountRegister() >= 0 && amount == 0) {
            return value;
        }
        return applyShift(value, register.shiftType(), amount);
    }

    private boolean shiftedRegisterCarryOut(ArmCore core, IrOperand.ShiftedRegister register) {
        int value = registerValue(core, register.index(), register.valueOverride());
        if (register.rrx()) {
            return (value & 1) != 0;
        }
        int amount = register.amountRegister() >= 0
                ? registerValue(core, register.amountRegister(), register.amountValueOverride()) & 0xFF
                : register.amount();
        if (amount == 0) {
            return core.cpsr().carry();
        }
        return shiftCarryOut(value, register.shiftType(), amount);
    }

    private int applyShift(int value, ShiftType shiftType, int amount) {
        return switch (shiftType) {
            case LSL -> amount >= 32 ? 0 : value << amount;
            case LSR -> amount >= 32 ? 0 : value >>> amount;
            case ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            case ROR -> Integer.rotateRight(value, amount & 31);
        };
    }

    private boolean shiftCarryOut(int value, ShiftType shiftType, int amount) {
        if (amount <= 0) {
            return false;
        }
        return switch (shiftType) {
            case LSL -> {
                if (amount < 32) {
                    yield ((value >>> (32 - amount)) & 1) != 0;
                }
                yield amount == 32 && (value & 1) != 0;
            }
            case LSR -> {
                if (amount < 32) {
                    yield ((value >>> (amount - 1)) & 1) != 0;
                }
                yield amount == 32 && value < 0;
            }
            case ASR -> amount >= 32 ? value < 0 : ((value >>> (amount - 1)) & 1) != 0;
            case ROR -> {
                int effective = amount & 31;
                yield effective == 0 ? value < 0 : ((value >>> (effective - 1)) & 1) != 0;
            }
        };
    }
}
