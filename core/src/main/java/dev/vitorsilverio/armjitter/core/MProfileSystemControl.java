package dev.vitorsilverio.armjitter.core;

import java.util.Objects;

/// SCS (System Control Space) mínimo do perfil M (B7.3): VTOR, ICSR, AIRCR, SHPR1-3, SHCSR,
/// NVIC (ISER/ICER/ISPR/ICPR/IABR/IPR, até {@link MProfileExceptionModel#MAX_EXTERNAL_IRQS} IRQs)
/// e SysTick (CSR/RVR/CVR) — ARMv7-M ARM (DDI 0403E) §B3.2 (SCS), §B3.3 (SysTick), §B3.4 (NVIC).
///
/// `read32`/`write32` recebem o offset já relativo a `0xE000E000` — o hospedeiro é quem faz a
/// subtração ao pendurar este componente como handler de página (par natural:
/// `PagedAddressSpace.mapHandler`, mas esta classe não conhece `PagedAddressSpace`: a interface
/// é só offset→valor). Todo o estado de pendência/prioridade/preempção mora no
/// {@link MProfileExceptionModel} associado — esta classe é essencialmente um decodificador de
/// offset de registrador por cima dele, exceto pelo timer do SysTick, que é seu.
///
/// Fora de escopo (ver "Não inclui" da B7.3): MPU, DWT/ITM/FPB, CPUID/campos read-only
/// detalhados, mais de {@link MProfileExceptionModel#MAX_EXTERNAL_IRQS} IRQs externas,
/// tail-chaining, lazy-stacking, DEBUGEN.
public final class MProfileSystemControl {
    private static final int VTOR_OFFSET = 0xD08;
    private static final int ICSR_OFFSET = 0xD04;
    private static final int AIRCR_OFFSET = 0xD0C;
    private static final int SHPR1_OFFSET = 0xD18;
    private static final int SHCSR_OFFSET = 0xD24;
    private static final int NVIC_ISER0_OFFSET = 0x100;
    private static final int NVIC_ICER0_OFFSET = 0x180;
    private static final int NVIC_ISPR0_OFFSET = 0x200;
    private static final int NVIC_ICPR0_OFFSET = 0x280;
    private static final int NVIC_IABR0_OFFSET = 0x300;
    private static final int NVIC_IPR0_OFFSET = 0x400;
    private static final int SYST_CSR_OFFSET = 0x010;
    private static final int SYST_RVR_OFFSET = 0x014;
    private static final int SYST_CVR_OFFSET = 0x018;

    /// VTOR: só bits\[31:7\] são endereço válido (128-byte align mínimo da tabela de vetores).
    private static final int VTOR_ADDRESS_MASK = ~0x7F;

    private static final int ICSR_PENDSVSET_BIT = 1 << 28;
    private static final int ICSR_PENDSVCLR_BIT = 1 << 27;
    private static final int ICSR_PENDSTSET_BIT = 1 << 26;
    private static final int ICSR_PENDSTCLR_BIT = 1 << 25;
    private static final int ICSR_VECTACTIVE_MASK = 0x1FF;

    /// `AIRCR.VECTKEY`: uma escrita em AIRCR só tem efeito se bits\[31:16\] forem exatamente este
    /// valor (proteção contra escrita acidental do registrador de reset).
    private static final int AIRCR_VECTKEY = 0x05FA;
    private static final int AIRCR_VECTKEY_SHIFT = 16;
    private static final int AIRCR_VECTKEY_MASK = 0xFFFF;
    private static final int AIRCR_SYSRESETREQ_BIT = 1 << 2;

    private static final int SYST_CSR_ENABLE_BIT = 1;
    private static final int SYST_CSR_TICKINT_BIT = 1 << 1;
    private static final int SYST_CSR_CLKSOURCE_BIT = 1 << 2;
    private static final int SYST_CSR_WRITABLE_MASK =
            SYST_CSR_ENABLE_BIT | SYST_CSR_TICKINT_BIT | SYST_CSR_CLKSOURCE_BIT;
    /// `SYST_CSR.COUNTFLAG`: setado quando o contador chega a zero, limpo por QUALQUER leitura
    /// de SYST_CSR (armadilha da B3.3, não confundir com escrita).
    private static final int SYST_CSR_COUNTFLAG_BIT = 1 << 16;

    /// `SYST_RVR`/`SYST_CVR` só têm 24 bits implementados.
    private static final int SYST_RELOAD_MASK = 0x00FFFFFF;

    /// Primeira exceção com prioridade em SHPR1-3 (MemManage); última é SysTick (15). Formato:
    /// `SHPRn` cobre 4 exceções por word, byte `number % 4`, word `(number - 4) / 4`.
    private static final int SHPR_FIRST_EXCEPTION = 4;
    private static final int SHPR_LAST_EXCEPTION = 15;
    private static final int BYTES_PER_PRIORITY_WORD = 4;
    private static final int BITS_PER_BYTE = 8;
    private static final int PRIORITY_BYTE_MASK = 0xFF;

    private final MProfileExceptionModel exceptionModel;
    private final int externalIrqCount;
    private final Runnable systemResetRequested;

    private int shcsr;
    private int systCsr;
    private int systRvr;
    private int systCvr;
    private boolean systCountFlag;

    /// @param exceptionModel modelo de exceção associado (dono do estado de pendência/prioridade)
    /// @param externalIrqCount número de IRQs externas suportadas (0..{@link MProfileExceptionModel#MAX_EXTERNAL_IRQS})
    /// @param systemResetRequested callback disparado por `AIRCR.SYSRESETREQ` (host decide o que "reset" significa)
    public MProfileSystemControl(MProfileExceptionModel exceptionModel, int externalIrqCount,
            Runnable systemResetRequested) {
        this.exceptionModel = Objects.requireNonNull(exceptionModel, "exceptionModel");
        if (externalIrqCount < 0 || externalIrqCount > MProfileExceptionModel.MAX_EXTERNAL_IRQS) {
            throw new IllegalArgumentException(
                    "externalIrqCount must be within [0, " + MProfileExceptionModel.MAX_EXTERNAL_IRQS + "]");
        }
        this.externalIrqCount = externalIrqCount;
        this.systemResetRequested = Objects.requireNonNull(systemResetRequested, "systemResetRequested");
    }

    /// Lê um registrador do SCS pelo offset relativo a `0xE000E000`.
    public int read32(int offset) {
        if (offset == VTOR_OFFSET) {
            return exceptionModel.vectorTableOffset();
        }
        if (offset == ICSR_OFFSET) {
            return readIcsr();
        }
        if (offset == AIRCR_OFFSET) {
            return 0;
        }
        if (offset == SHCSR_OFFSET) {
            return shcsr;
        }
        if (offset == NVIC_ISER0_OFFSET || offset == NVIC_ICER0_OFFSET) {
            return readEnabledWord();
        }
        if (offset == NVIC_ISPR0_OFFSET || offset == NVIC_ICPR0_OFFSET) {
            return readPendingWord();
        }
        if (offset == NVIC_IABR0_OFFSET) {
            return readActiveWord();
        }
        if (offset == SYST_CSR_OFFSET) {
            return readSystCsr();
        }
        if (offset == SYST_RVR_OFFSET) {
            return systRvr;
        }
        if (offset == SYST_CVR_OFFSET) {
            return systCvr;
        }
        if (isShprOffset(offset)) {
            return readShpr(offset);
        }
        if (isIprOffset(offset)) {
            return readIpr(offset);
        }
        return 0;
    }

    /// Escreve um registrador do SCS pelo offset relativo a `0xE000E000`.
    public void write32(int offset, int value) {
        if (offset == VTOR_OFFSET) {
            exceptionModel.setVectorTableOffset(value & VTOR_ADDRESS_MASK);
        } else if (offset == ICSR_OFFSET) {
            writeIcsr(value);
        } else if (offset == AIRCR_OFFSET) {
            writeAircr(value);
        } else if (offset == SHCSR_OFFSET) {
            shcsr = value;
        } else if (offset == NVIC_ISER0_OFFSET) {
            setEnabledBits(value, true);
        } else if (offset == NVIC_ICER0_OFFSET) {
            setEnabledBits(value, false);
        } else if (offset == NVIC_ISPR0_OFFSET) {
            setPendingBits(value, true);
        } else if (offset == NVIC_ICPR0_OFFSET) {
            setPendingBits(value, false);
        } else if (offset == NVIC_IABR0_OFFSET) {
            // Read-only (espelho da pilha de exceções ativas) — escrita ignorada.
        } else if (offset == SYST_CSR_OFFSET) {
            systCsr = value & SYST_CSR_WRITABLE_MASK;
        } else if (offset == SYST_RVR_OFFSET) {
            systRvr = value & SYST_RELOAD_MASK;
        } else if (offset == SYST_CVR_OFFSET) {
            // B3.3: escrever QUALQUER valor em CVR zera o contador E limpa COUNTFLAG.
            systCvr = 0;
            systCountFlag = false;
        } else if (isShprOffset(offset)) {
            writeShpr(offset, value);
        } else if (isIprOffset(offset)) {
            writeIpr(offset, value);
        }
    }

    /// Avança o SysTick em `cycles` ciclos consumidos (chamado pelo hospedeiro/runner, mesmo
    /// padrão dos timers do gbaemu/ndsemu). Sem efeito quando `SYST_CSR.ENABLE` está limpo.
    /// Decrementa `CVR`; a cada vez que chega a zero, recarrega de `RVR`, seta `COUNTFLAG` e
    /// pende {@link MProfileException#SYSTICK} se `TICKINT` estiver setado.
    public void tick(int cycles) {
        if (cycles <= 0 || (systCsr & SYST_CSR_ENABLE_BIT) == 0) {
            return;
        }
        long remaining = cycles;
        long counter = Integer.toUnsignedLong(systCvr);
        while (remaining > 0) {
            if (counter == 0) {
                counter = Integer.toUnsignedLong(systRvr);
                if (counter == 0) {
                    // RVR=0: sem período configurado, o contador fica parado em 0 (sem novos
                    // eventos de COUNTFLAG até o hospedeiro escrever um RVR não-zero).
                    break;
                }
            }
            // O evento (recarga + COUNTFLAG + pend) dispara exatamente quando `counter` cruza
            // para 0 — não só quando um `tick()` consome o período inteiro de uma vez (armadilha:
            // chamadas pequenas e frequentes, ex. 1 ciclo/step, precisam do mesmo comportamento
            // de chamadas grandes e raras).
            if (remaining >= counter) {
                remaining -= counter;
                counter = 0;
                systCountFlag = true;
                if ((systCsr & SYST_CSR_TICKINT_BIT) != 0) {
                    exceptionModel.pendException(MProfileException.SYSTICK.number());
                }
            } else {
                counter -= remaining;
                remaining = 0;
            }
        }
        systCvr = (int) counter;
    }

    private int readIcsr() {
        int value = exceptionModel.currentException() & ICSR_VECTACTIVE_MASK;
        if (exceptionModel.pending(MProfileException.PENDSV.number())) {
            value |= ICSR_PENDSVSET_BIT;
        }
        if (exceptionModel.pending(MProfileException.SYSTICK.number())) {
            value |= ICSR_PENDSTSET_BIT;
        }
        return value;
    }

    private void writeIcsr(int value) {
        if ((value & ICSR_PENDSVSET_BIT) != 0) {
            exceptionModel.pendException(MProfileException.PENDSV.number());
        }
        if ((value & ICSR_PENDSVCLR_BIT) != 0) {
            exceptionModel.clearPending(MProfileException.PENDSV.number());
        }
        if ((value & ICSR_PENDSTSET_BIT) != 0) {
            exceptionModel.pendException(MProfileException.SYSTICK.number());
        }
        if ((value & ICSR_PENDSTCLR_BIT) != 0) {
            exceptionModel.clearPending(MProfileException.SYSTICK.number());
        }
        // VECTACTIVE não é escrito por software (read-only, espelho do IPSR) — ignorado aqui.
    }

    private void writeAircr(int value) {
        if (((value >>> AIRCR_VECTKEY_SHIFT) & AIRCR_VECTKEY_MASK) != AIRCR_VECTKEY) {
            return;
        }
        if ((value & AIRCR_SYSRESETREQ_BIT) != 0) {
            systemResetRequested.run();
        }
    }

    private int readSystCsr() {
        int value = systCsr & SYST_CSR_WRITABLE_MASK;
        if (systCountFlag) {
            value |= SYST_CSR_COUNTFLAG_BIT;
        }
        systCountFlag = false; // leitura limpa COUNTFLAG (B3.3).
        return value;
    }

    private int readEnabledWord() {
        int value = 0;
        for (int irq = 0; irq < externalIrqCount; irq++) {
            if (exceptionModel.externalIrqEnabled(irq)) {
                value |= 1 << irq;
            }
        }
        return value;
    }

    private void setEnabledBits(int value, boolean enable) {
        for (int irq = 0; irq < externalIrqCount; irq++) {
            if ((value & (1 << irq)) != 0) {
                exceptionModel.setExternalIrqEnabled(irq, enable);
            }
        }
    }

    private int readPendingWord() {
        int value = 0;
        for (int irq = 0; irq < externalIrqCount; irq++) {
            if (exceptionModel.pending(MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + irq)) {
                value |= 1 << irq;
            }
        }
        return value;
    }

    private void setPendingBits(int value, boolean pend) {
        for (int irq = 0; irq < externalIrqCount; irq++) {
            if ((value & (1 << irq)) != 0) {
                int number = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + irq;
                if (pend) {
                    exceptionModel.pendException(number);
                } else {
                    exceptionModel.clearPending(number);
                }
            }
        }
    }

    private int readActiveWord() {
        int value = 0;
        for (int irq = 0; irq < externalIrqCount; irq++) {
            if (exceptionModel.active(MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + irq)) {
                value |= 1 << irq;
            }
        }
        return value;
    }

    private static boolean isShprOffset(int offset) {
        int wordBaseException = shprWordBaseException(offset);
        return wordBaseException >= SHPR_FIRST_EXCEPTION && wordBaseException <= SHPR_LAST_EXCEPTION
                && (offset - SHPR1_OFFSET) % BYTES_PER_PRIORITY_WORD == 0;
    }

    private static int shprWordBaseException(int offset) {
        return SHPR_FIRST_EXCEPTION + ((offset - SHPR1_OFFSET) / BYTES_PER_PRIORITY_WORD) * BYTES_PER_PRIORITY_WORD;
    }

    private int readShpr(int offset) {
        int baseException = shprWordBaseException(offset);
        int value = 0;
        for (int i = 0; i < BYTES_PER_PRIORITY_WORD; i++) {
            value |= (exceptionModel.priority(baseException + i) & PRIORITY_BYTE_MASK) << (BITS_PER_BYTE * i);
        }
        return value;
    }

    private void writeShpr(int offset, int value) {
        int baseException = shprWordBaseException(offset);
        for (int i = 0; i < BYTES_PER_PRIORITY_WORD; i++) {
            exceptionModel.setPriority(baseException + i, (value >>> (BITS_PER_BYTE * i)) & PRIORITY_BYTE_MASK);
        }
    }

    private boolean isIprOffset(int offset) {
        if (offset < NVIC_IPR0_OFFSET || (offset - NVIC_IPR0_OFFSET) % BYTES_PER_PRIORITY_WORD != 0) {
            return false;
        }
        int wordIndex = (offset - NVIC_IPR0_OFFSET) / BYTES_PER_PRIORITY_WORD;
        return wordIndex * BYTES_PER_PRIORITY_WORD < externalIrqCount;
    }

    private int readIpr(int offset) {
        int baseIrq = (offset - NVIC_IPR0_OFFSET) / BYTES_PER_PRIORITY_WORD * BYTES_PER_PRIORITY_WORD;
        int value = 0;
        for (int i = 0; i < BYTES_PER_PRIORITY_WORD; i++) {
            int irq = baseIrq + i;
            if (irq >= externalIrqCount) {
                continue;
            }
            int number = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + irq;
            value |= (exceptionModel.priority(number) & PRIORITY_BYTE_MASK) << (BITS_PER_BYTE * i);
        }
        return value;
    }

    private void writeIpr(int offset, int value) {
        int baseIrq = (offset - NVIC_IPR0_OFFSET) / BYTES_PER_PRIORITY_WORD * BYTES_PER_PRIORITY_WORD;
        for (int i = 0; i < BYTES_PER_PRIORITY_WORD; i++) {
            int irq = baseIrq + i;
            if (irq >= externalIrqCount) {
                continue;
            }
            int number = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + irq;
            exceptionModel.setPriority(number, (value >>> (BITS_PER_BYTE * i)) & PRIORITY_BYTE_MASK);
        }
    }
}
