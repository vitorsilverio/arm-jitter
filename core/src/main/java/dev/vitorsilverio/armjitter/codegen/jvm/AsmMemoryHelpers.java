package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Helpers estáticos invocados pelo bytecode ASM gerado: acesso à memória (alinhado, desalinhado atravessado, BE8), LDM/STM/PUSH/POP e acessos exclusivos.
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}.
/// Públicos porque o bytecode gerado roda em outro class loader.
public final class AsmMemoryHelpers {
    private AsmMemoryHelpers() {
    }

    // ── memória ────────────────────────────────────────────────────────────────

    public static int loadByte(ArmCore core, int address) {
        int value = core.memory().read8(address);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
        return value;
    }

    public static int loadHalf(ArmCore core, int address) {
        int aligned = address & ~1;
        int value = core.memory().read16(aligned);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_READ);
        int rotated = (address & 1) == 0 ? value : Integer.rotateRight(value, 8);
        return applyDataEndiannessHalfword(core, rotated);
    }

    public static int loadHalfSigned(ArmCore core, int address) {
        if ((address & 1) != 0) {
            // Quirk ARMv4T de LDRSH desalinhado: vira leitura de BYTE — invariante a endianness
            // (ver o comentário de classe da seção BE8 abaixo).
            int value = core.memory().read8(address);
            core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
            return (byte) value;
        }
        int value = core.memory().read16(address);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_READ);
        return (short) applyDataEndiannessHalfword(core, value);
    }

    public static int loadWord(ArmCore core, int address) {
        int aligned = address & ~3;
        int value = core.memory().read32(aligned);
        core.addMemoryCycles(aligned, 4, MemoryAccessType.DATA_READ);
        int rotated = Integer.rotateRight(value, (address & 3) * 8);
        return applyDataEndiannessWord(core, rotated);
    }

    /// `LDRB`/`STRB` (byte único): invariante a `CPSR.E` por definição (BE8 é "byte-invariant" —
    /// ver a seção BE8 abaixo) — nunca aplicar troca de bytes aqui.
    public static void storeByte(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 1);
    }

    public static void storeHalf(ArmCore core, int address, int value) {
        core.memory().write16(address, applyDataEndiannessHalfword(core, value));
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 2);
    }

    public static void storeWord(ArmCore core, int address, int value) {
        core.memory().write32(address, applyDataEndiannessWord(core, value));
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 4);
    }

    // ── memória: acesso desalinhado atravessado (ArmFeature.UNALIGNED_ACCESS, task B1.7) ────────
    //
    // Espelham exatamente o fork de dev.vitorsilverio.armjitter.codegen.executor.IrExecutionSupport
    // (readWordForLoad/readHalfwordForLoad/writeWordForStore/writeHalfwordForStore): em vez de
    // alinhar+rotacionar (ARMv4T, loadWord/loadHalf/loadHalfSigned/storeWord/storeHalf acima), compõem
    // o acesso de bytes independentes (cada um trivialmente "alinhado" e seguro através de fronteiras
    // de página/região). Só emitidos pelo AsmBlockCompiler para LDR/STR/LDRH/STRH com a feature ligada
    // e destino diferente do PC — LDM/STM/LDRD/STRD/LDREX/STREX/SWP continuam chamando os helpers
    // legados acima incondicionalmente (ponto único de emissão, ver emitLoad/emitStore).

    private static final int BITS_PER_BYTE = 8;
    private static final int BYTE_MASK = 0xFF;

    /// Máscara para testar alinhamento de word (4 bytes) — nomeada por G6, espelha
    /// `IrExecutionSupport#isWordAligned`.
    private static final int WORD_ALIGNMENT_MASK = 0x3;
    /// Máscara para testar alinhamento de halfword (2 bytes) — nomeada por G6.
    private static final int HALFWORD_ALIGNMENT_MASK = 0x1;

    /// **Achado real (task F3/`virtual-arm-box`, ver Javadoc de
    /// `IrExecutionSupport#readWordForLoad` para a história completa)**: `address` GENUINAMENTE
    /// desalinhado é o único caso que precisa da composição byte a byte — um endereço já alinhado
    /// deve continuar valendo {@link #loadWord} (a mesma transação de barramento único que
    /// hardware real faz), senão qualquer periférico MMIO cujo `read8` não reimplemente "byte N
    /// da word alinhada" tem o valor lido silenciosamente truncado ao byte baixo. O
    /// `AsmBlockCompiler` emite uma chamada única para este helper (não decide alinhamento em
    /// tempo de compilação, já que o endereço costuma vir de um registrador) — a checagem de
    /// alinhamento acontece aqui, em tempo de execução, espelhando exatamente
    /// `IrExecutionSupport#readWordForLoad`.
    public static int loadWordCrossed(ArmCore core, int address) {
        if ((address & WORD_ALIGNMENT_MASK) == 0) {
            return loadWord(core, address);
        }
        int value = (core.memory().read8(address) & BYTE_MASK)
                | ((core.memory().read8(address + 1) & BYTE_MASK) << BITS_PER_BYTE)
                | ((core.memory().read8(address + 2) & BYTE_MASK) << (2 * BITS_PER_BYTE))
                | ((core.memory().read8(address + 3) & BYTE_MASK) << (3 * BITS_PER_BYTE));
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_READ);
        return applyDataEndiannessWord(core, value);
    }

    /// Ver Javadoc de {@link #loadWordCrossed} — mesma correção para halfword.
    public static int loadHalfCrossed(ArmCore core, int address) {
        if ((address & HALFWORD_ALIGNMENT_MASK) == 0) {
            return loadHalf(core, address);
        }
        int value = (core.memory().read8(address) & BYTE_MASK)
                | ((core.memory().read8(address + 1) & BYTE_MASK) << BITS_PER_BYTE);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_READ);
        return applyDataEndiannessHalfword(core, value);
    }

    public static int loadHalfSignedCrossed(ArmCore core, int address) {
        if ((address & HALFWORD_ALIGNMENT_MASK) == 0) {
            return loadHalfSigned(core, address);
        }
        return (short) loadHalfCrossed(core, address);
    }

    /// Ver Javadoc de {@link #loadWordCrossed} — mesma correção para `STR`.
    public static void storeWordCrossed(ArmCore core, int address, int value) {
        if ((address & WORD_ALIGNMENT_MASK) == 0) {
            storeWord(core, address, value);
            return;
        }
        int stored = applyDataEndiannessWord(core, value);
        core.memory().write8(address, stored);
        core.memory().write8(address + 1, stored >>> BITS_PER_BYTE);
        core.memory().write8(address + 2, stored >>> (2 * BITS_PER_BYTE));
        core.memory().write8(address + 3, stored >>> (3 * BITS_PER_BYTE));
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 4);
    }

    /// Ver Javadoc de {@link #loadWordCrossed} — mesma correção para `STRH`.
    public static void storeHalfCrossed(ArmCore core, int address, int value) {
        if ((address & HALFWORD_ALIGNMENT_MASK) == 0) {
            storeHalf(core, address, value);
            return;
        }
        int stored = applyDataEndiannessHalfword(core, value);
        core.memory().write8(address, stored);
        core.memory().write8(address + 1, stored >>> BITS_PER_BYTE);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
        core.notifyOrdinaryWrite(address, 2);
    }

    // ── memória: endianness de dados BE8 (`CPSR.E`, task B1.8) ───────────────────────────────
    //
    // Espelham exatamente dev.vitorsilverio.armjitter.codegen.executor.IrExecutionSupport
    // (applyDataEndiannessWord/applyDataEndiannessHalfword — invariante G1, provado pelo
    // BlockEquivalenceHarness): BE8 ("byte-invariant big-endian", ARM DDI 0406C A2.9) troca a
    // ordem em que os bytes de um acesso de dados WORD/HALFWORD são combinados/decompostos
    // quando CPSR.E=1. A busca de instrução nunca é afetada; byte único (loadByte/storeByte)
    // é invariante por definição e nunca chama estes métodos. Com E=0 (default de todo preset)
    // isto é sempre a identidade — G3.

    private static int applyDataEndiannessWord(ArmCore core, int littleEndianValue) {
        return core.cpsr().isBigEndian() ? Integer.reverseBytes(littleEndianValue) : littleEndianValue;
    }

    private static int applyDataEndiannessHalfword(ArmCore core, int littleEndianValue) {
        if (!core.cpsr().isBigEndian()) {
            return littleEndianValue;
        }
        int low = littleEndianValue & BYTE_MASK;
        int high = (littleEndianValue >>> BITS_PER_BYTE) & BYTE_MASK;
        return (low << BITS_PER_BYTE) | high;
    }

    // ── LDM/STM ───────────────────────────────────────────────────────────────

    /// @return {@code true} when PC was loaded (the JIT block must exit after this)
    public static boolean executeMultipleTransfer(
            ArmCore core, boolean load, int baseRegister, int registerMask,
            boolean writeback, boolean userMode, boolean emptyList, int modeOrdinal, boolean interwork) {
        int mask = emptyList ? (1 << 15) : registerMask;
        int count = emptyList ? 16 : Integer.bitCount(registerMask);
        int base = core.register(baseRegister);
        BlockTransferMode mode = BlockTransferMode.values()[modeOrdinal];
        int address = mode.startAddress(base, count) & ~3;
        int writebackAddress = mode.writebackAddress(base, count);
        boolean includesPc = (mask & (1 << 15)) != 0;
        boolean forceUser = userMode && !includesPc;
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        int loadedPc = 0;
        for (int reg = 0; reg <= 15; reg++) {
            if ((mask & (1 << reg)) != 0) {
                if (load) {
                    int value = loadWord(core, address);
                    if (userMode && includesPc && reg == 15) {
                        loadedPc = value;
                    } else if (forceUser) {
                        core.setBankedRegister(CpuMode.USER, reg, value);
                    } else if (reg == 15) {
                        AsmSystemHelpers.loadToPc(core, value, interwork); // LDM reg15 normal: interworka em ARMv5
                    } else {
                        core.setRegister(reg, value);
                    }
                } else {
                    int value;
                    if (userMode) {
                        value = core.bankedRegister(CpuMode.USER, reg);
                    } else if (writeback && reg == baseRegister && reg != firstRegister) {
                        value = writebackAddress;
                    } else {
                        value = core.register(reg);
                    }
                    storeWord(core, address, value);
                }
                address += 4;
            }
        }
        if (writeback && !(load && (mask & (1 << baseRegister)) != 0)) {
            core.setRegister(baseRegister, writebackAddress);
        }
        if (load && userMode && includesPc) {
            CpuMode psrMode = core.mode();
            if (psrMode != CpuMode.USER && psrMode != CpuMode.SYSTEM) {
                core.setCpsr(core.spsr(psrMode));
            }
            AsmSystemHelpers.loadToPcArm4(core, loadedPc);
        }
        return load && includesPc;
    }

    // ── ARMv6/v6K (B1.4): acessos exclusivos ────────────────────────────────────
    // Espelham IrMemoryExecutor.executeLoadExclusive/executeStoreExclusive/executeClearExclusive.

    /// LDREX{,B,H}: marca o monitor de exclusividade ANTES da leitura (mesma ordem do
    /// interpretador) e devolve o valor lido. A forma doubleword (LDREXD) não passa por este
    /// helper — o AsmBlockCompiler emite {@link #markExclusive} seguido de dois {@code loadWord}.
    public static int loadExclusive(ArmCore core, int address, int sizeBytes) {
        core.markExclusive(Integer.toUnsignedLong(address), sizeBytes);
        return switch (sizeBytes) {
            case 1 -> loadByte(core, address);
            case 2 -> loadHalf(core, address);
            default -> loadWord(core, address);
        };
    }

    /// Marca o monitor de exclusividade sem ler (forma doubleword, LDREXD — os dois
    /// {@code loadWord} são emitidos separadamente pelo AsmBlockCompiler).
    public static void markExclusive(ArmCore core, int address, int sizeBytes) {
        core.markExclusive(Integer.toUnsignedLong(address), sizeBytes);
    }

    /// STREX{,B,H,D}: {@code true} quando o monitor cobre o endereço/tamanho (sucesso — o
    /// CHAMADOR deve então escrever a memória e consumir o monitor); {@code false} = falha,
    /// nenhuma escrita deve ocorrer (mesma ordem do interpretador: checa ANTES de escrever).
    public static boolean exclusiveMonitorCovers(ArmCore core, int address, int sizeBytes) {
        return core.exclusiveMonitorCovers(Integer.toUnsignedLong(address), sizeBytes);
    }

    /// Consome o monitor de exclusividade após um STREX bem-sucedido, ou por CLREX.
    public static void clearExclusiveMonitor(ArmCore core) {
        core.clearExclusiveMonitor();
    }
}
