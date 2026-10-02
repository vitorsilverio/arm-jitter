package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;

/// Semântica dos acessos à memória A64 de registrador geral ({@link MemoryOp64}): load/store, pares,
/// exclusivos, atômicos (LSE), cópia/preenchimento (MOPS) e tags de memória (MTE). Hospeda também
/// os auxiliares de registrador-base, writeback e leitura/escrita que os load/store SIMD&FP reusam.
///
/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}
/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.
public final class Ir64MemoryExecutor {
    /// Índice de encoding (`Rn`=`31`) do registrador BASE de qualquer load/store — sempre `SP`,
    /// nunca `XZR` (convenção arquitetural do A64, resolvida aqui e não no decoder — ver
    /// {@link MemoryOp64.Load64#rn} javadoc).
    private static final int BASE_REGISTER_SP_ENCODING = 31;
    /// Deslocamento em bytes entre os dois slots de um `LDP`/`STP` de 64 bits.
    private static final int PAIR_DOUBLEWORD_STRIDE_BYTES = 8;
    /// Deslocamento em bytes entre os dois slots de um `LDP`/`STP` de 32 bits.
    private static final int PAIR_WORD_STRIDE_BYTES = 4;

    private Ir64MemoryExecutor() {
    }

    /// Executa {@link MemoryOp64.Load64}.
    public static boolean executeLoad(Aarch64Core core, MemoryOp64.Load64 op) {
        long base = readBaseRegister(core, op.rn());
        long address = transferAddress(core, base, op.addressingMode(), op.immediate(),
                op.rm(), op.extendType(), op.shiftAmount());
        long raw = readMemory(core, address, op.size());
        long value = op.signExtend() ? signExtendFromSize(raw, op.size()) : raw;
        core.setXForWidth(op.rt(), value, op.wide());
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// Executa {@link MemoryOp64.Store64}.
    public static boolean executeStore(Aarch64Core core, MemoryOp64.Store64 op) {
        long base = readBaseRegister(core, op.rn());
        long address = transferAddress(core, base, op.addressingMode(), op.immediate(),
                op.rm(), op.extendType(), op.shiftAmount());
        long value = core.xForWidth(op.rt(), op.wide());
        writeMemory(core, address, op.size(), value);
        // B6.3.4: escrita comum que sobrepõe uma reserva pendente de LDXR/LDAXR abre o monitor
        // (mesma disciplina de STR/STRH/STRB de 32 bits em ArmCore — auditoria explícita da
        // Especificação #2 da task, sem esta chamada o teste de notifyOrdinaryWrite falha).
        core.notifyOrdinaryWrite(address, op.size().bytes());
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// `LDXR`/`LDAXR` (B6.3.4): lê a memória em `rn`+0 (sem deslocamento — a forma exclusiva não
    /// tem imediato) e marca o monitor de exclusividade com `(endereço, size.bytes())`.
    /// `acquireRelease` é NOP observável no interpretador (ver {@link MemoryOp64.LoadExclusive}
    /// javadoc) — carregado no IR só para um futuro emissor nativo.
    public static boolean executeLoadExclusive(Aarch64Core core, MemoryOp64.LoadExclusive op) {
        long address = readBaseRegister(core, op.rn());
        long value = readMemory(core, address, op.size());
        core.markExclusiveMonitor(address, op.size().bytes());
        core.setXForWidth(op.rt(), value, op.size() == Ir64MemSize.DOUBLEWORD);
        return false;
    }

    /// `STXR`/`STLXR` (B6.3.4): consulta o monitor ANTES de qualquer escrita — armadilha crítica
    /// espelhada de `STREX` (B1.4): um `STXR`/`STLXR` que falha NÃO pode ter efeito colateral de
    /// memória. Sucesso escreve `rt`, grava `0` em `rs` e consome a reserva; falha grava `1` em
    /// `rs` com a memória intacta. `acquireRelease` é NOP observável (ver
    /// {@link MemoryOp64.StoreExclusive} javadoc).
    public static boolean executeStoreExclusive(Aarch64Core core, MemoryOp64.StoreExclusive op) {
        long address = readBaseRegister(core, op.rn());
        if (!core.exclusiveMonitorCovers(address, op.size().bytes())) {
            core.setXForWidth(op.rs(), 1L, false);
            return false;
        }
        long value = core.xForWidth(op.rt(), op.size() == Ir64MemSize.DOUBLEWORD);
        writeMemory(core, address, op.size(), value);
        core.setXForWidth(op.rs(), 0L, false);
        return false;
    }

    /// `LDXP`/`LDAXP` (B8.1): mesmo espírito de {@link #executeLoadExclusive}, mas marca o
    /// monitor cobrindo os DOIS slots (`2 × size.bytes()`, `size` = `WORD`/`DOUBLEWORD` conforme
    /// {@link MemoryOp64.LoadExclusivePair#wide}).
    public static boolean executeLoadExclusivePair(Aarch64Core core, MemoryOp64.LoadExclusivePair op) {
        long address = readBaseRegister(core, op.rn());
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        int stride = size.bytes();
        long first = readMemory(core, address, size);
        long second = readMemory(core, address + stride, size);
        core.markExclusiveMonitor(address, stride * 2);
        core.setXForWidth(op.rt(), first, op.wide());
        core.setXForWidth(op.rt2(), second, op.wide());
        return false;
    }

    /// `STXP`/`STLXP` (B8.1): consulta o monitor ANTES de qualquer escrita — mesma armadilha
    /// crítica de {@link #executeStoreExclusive}, aplicada aos DOIS slots do par.
    public static boolean executeStoreExclusivePair(Aarch64Core core, MemoryOp64.StoreExclusivePair op) {
        long address = readBaseRegister(core, op.rn());
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        int stride = size.bytes();
        if (!core.exclusiveMonitorCovers(address, stride * 2)) {
            core.setXForWidth(op.rs(), 1L, false);
            return false;
        }
        writeMemory(core, address, size, core.xForWidth(op.rt(), op.wide()));
        writeMemory(core, address + stride, size, core.xForWidth(op.rt2(), op.wide()));
        core.setXForWidth(op.rs(), 0L, false);
        return false;
    }

    /// `CAS`/`CASA`/`CASL`/`CASAL` (B8.1) — semântica de `CMPXCHG`: lê `[Rn]`, compara com `Rs`
    /// (truncado para {@code size}); se igual, escreve `Rt`; SEMPRE grava o valor antigo lido em
    /// `Rs` (zero-estendido). Interpretador single-thread por construção — não precisa de CAS
    /// real de host (ver javadoc de {@link MemoryOp64.CompareAndSwap}).
    public static boolean executeCompareAndSwap(Aarch64Core core, MemoryOp64.CompareAndSwap op) {
        long address = readBaseRegister(core, op.rn());
        boolean wide = op.size() == Ir64MemSize.DOUBLEWORD;
        long current = readMemory(core, address, op.size());
        long expected = zeroTruncateToSize(core.xForWidth(op.rs(), wide), op.size());
        if (current == expected) {
            writeMemory(core, address, op.size(), core.xForWidth(op.rt(), wide));
            core.notifyOrdinaryWrite(address, op.size().bytes());
        }
        core.setXForWidth(op.rs(), current, wide);
        return false;
    }

    /// `CASP`/`CASPA`/`CASPL`/`CASPAL` (B8.1) — versão em par de {@link #executeCompareAndSwap}:
    /// compara `(Rs,Rs+1)` contra `[Rn]`/`[Rn+size]`; se AMBOS baterem, escreve `(Rt,Rt+1)`;
    /// sempre grava o par antigo lido em `(Rs,Rs+1)`. O companheiro é `rs|1`/`rt|1` (não `+1` —
    /// ver javadoc de {@link MemoryOp64.CompareAndSwapPair}).
    public static boolean executeCompareAndSwapPair(Aarch64Core core, MemoryOp64.CompareAndSwapPair op) {
        long address = readBaseRegister(core, op.rn());
        Ir64MemSize size = op.wide() ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        int stride = size.bytes();
        int rs2 = op.rs() | 1;
        int rt2 = op.rt() | 1;
        long currentLow = readMemory(core, address, size);
        long currentHigh = readMemory(core, address + stride, size);
        long expectedLow = core.xForWidth(op.rs(), op.wide());
        long expectedHigh = core.xForWidth(rs2, op.wide());
        if (currentLow == expectedLow && currentHigh == expectedHigh) {
            writeMemory(core, address, size, core.xForWidth(op.rt(), op.wide()));
            writeMemory(core, address + stride, size, core.xForWidth(rt2, op.wide()));
            core.notifyOrdinaryWrite(address, stride);
            core.notifyOrdinaryWrite(address + stride, stride);
        }
        core.setXForWidth(op.rs(), currentLow, op.wide());
        core.setXForWidth(rs2, currentHigh, op.wide());
        return false;
    }

    /// `LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP` (`FEAT_LSE`,
    /// B19.1) — RMW atômico do ponto de vista do guest (interpretador single-thread, não precisa
    /// de RMW real de host, ver javadoc de {@link MemoryOp64.AtomicMemoryOp}): lê `[Rn]`, calcula
    /// `<operation>(old, Rs)`, escreve de volta e grava `old` (zero-estendido) em `Rt`. Ao
    /// contrário de `CAS` (store condicional), o store aqui é INCONDICIONAL — por isso o
    /// {@link Aarch64Core#notifyOrdinaryWrite} SEMPRE dispara (derruba reserva `LDXR`/`LDAXR`
    /// pendente e invalida bloco de JIT em código automodificável). O monitor de exclusividade
    /// não é setado nem checado (LSE atômico não é exclusivo). `Rt==31` (`XZR`) é o alias
    /// `ST<op>`: RMW acontece, só a escrita em `X[31]` vira no-op. `acquire`/`release` NOP.
    public static boolean executeAtomicMemoryOp(Aarch64Core core, MemoryOp64.AtomicMemoryOp op) {
        long address = readBaseRegister(core, op.rn());
        boolean wide = op.size() == Ir64MemSize.DOUBLEWORD;
        long old = readMemory(core, address, op.size()); // já zero-truncado a size
        long rsValue = zeroTruncateToSize(core.xForWidth(op.rs(), wide), op.size()); // 31 => XZR => 0
        long newValue = switch (op.operation()) {
            case ADD -> old + rsValue;
            case CLR -> old & ~rsValue;
            case EOR -> old ^ rsValue;
            case SET -> old | rsValue;
            // SMAX/SMIN: comparar COM sinal a partir de `size` bits; o valor cru é regravado —
            // `writeMemory(size)` re-mascara (não pré-mascarar, perderia o sinal na comparação).
            case SMAX -> Math.max(signExtendFromSize(old, op.size()), signExtendFromSize(rsValue, op.size()));
            case SMIN -> Math.min(signExtendFromSize(old, op.size()), signExtendFromSize(rsValue, op.size()));
            case UMAX -> Long.compareUnsigned(old, rsValue) >= 0 ? old : rsValue;
            case UMIN -> Long.compareUnsigned(old, rsValue) <= 0 ? old : rsValue;
            case SWP -> rsValue;
        };
        writeMemory(core, address, op.size(), newValue);
        core.notifyOrdinaryWrite(address, op.size().bytes());
        core.setXForWidth(op.rt(), old, wide); // 31 => XZR, descarta (alias ST<op>)
        return false;
    }

    /// `LDCLRP`/`LDSETP`/`SWPP` (`FEAT_LSE128`, B19.25) — versão em PAR de
    /// {@link #executeAtomicMemoryOp} (128 bits, `(lo,hi)` little-endian em `[Rn]`/`[Rn+8]`): lê o
    /// par, aplica `CLR`/`SET`/`SWP` usando o PRÓPRIO par `(Rt,Rt2)` como operando (ver javadoc de
    /// {@link MemoryOp64.AtomicMemoryOpPair} — semântica in-place, diferente de {@link
    /// #executeAtomicMemoryOp}, que separa `Rs`=operando de `Rt`=destino), escreve o par novo de
    /// volta e SÓ DEPOIS sobrescreve `(Rt,Rt2)` com o par antigo lido (a ordem importa: `Rt`/`Rt2`
    /// são lidos como operando ANTES de virarem destino). Store incondicional: `notifyOrdinaryWrite`
    /// SEMPRE dispara para os 16 bytes inteiros, mesma disciplina de toda a família LSE do épico
    /// B19. `Rt`/`Rt2` nunca são `XZR` nem iguais (decoder já recusa, ver
    /// {@link dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder}), então não há alias `ST<op>`
    /// aqui, ao contrário de {@link #executeAtomicMemoryOp}.
    public static boolean executeAtomicMemoryOpPair(Aarch64Core core, MemoryOp64.AtomicMemoryOpPair op) {
        long address = readBaseRegister(core, op.rn());
        long lo = core.x(op.rt());
        long hi = core.x(op.rt2());
        long oldLo = readMemory(core, address, Ir64MemSize.DOUBLEWORD);
        long oldHi = readMemory(core, address + Ir64MemSize.DOUBLEWORD.bytes(), Ir64MemSize.DOUBLEWORD);
        long newLo = switch (op.operation()) {
            case CLR -> oldLo & ~lo;
            case SET -> oldLo | lo;
            case SWP -> lo;
            default -> throw new IllegalStateException("unreachable: decoder só produz CLR/SET/SWP");
        };
        long newHi = switch (op.operation()) {
            case CLR -> oldHi & ~hi;
            case SET -> oldHi | hi;
            case SWP -> hi;
            default -> throw new IllegalStateException("unreachable: decoder só produz CLR/SET/SWP");
        };
        writeMemory(core, address, Ir64MemSize.DOUBLEWORD, newLo);
        writeMemory(core, address + Ir64MemSize.DOUBLEWORD.bytes(), Ir64MemSize.DOUBLEWORD, newHi);
        core.notifyOrdinaryWrite(address, Ir64MemSize.DOUBLEWORD.bytes());
        core.notifyOrdinaryWrite(address + Ir64MemSize.DOUBLEWORD.bytes(), Ir64MemSize.DOUBLEWORD.bytes());
        core.setX(op.rt(), oldLo);
        core.setX(op.rt2(), oldHi);
        return false;
    }

    /// `NZCV` que a fase que completa um `SETP`/`CPYP`/`CPYM`/`CPYE` grava ao final — "Option B" da
    /// arquitetura (`N=0,Z=0,C=1,V=0` sentido direto; `N=1,Z=0,C=1,V=0` sentido reverso). Só
    /// observável se algum software real ler `NZCV` entre fases (este emulador nunca precisa disso
    /// — a fase seguinte só olha `Xn`==0 — mas gravar o valor arquiteturalmente correto é mais
    /// barato que documentar mais uma simplificação).
    private static final int MOPS_COMPLETED_FORWARD_NZCV = 0b0010;

    private static final int MOPS_COMPLETED_BACKWARD_NZCV = 0b1010;

    /// `SETP`/`SETM`/`SETE` (B19.16) — ver javadoc de {@link MemoryOp64.MemorySet}: a fase que
    /// encontra {@link MemoryOp64.MemorySet#rn} diferente de zero preenche tudo de uma vez; as
    /// demais (contador já zerado) são NOP funcional — mesma disciplina caso alguma delas seja
    /// executada sozinha, fora de sequência.
    public static boolean executeMemorySet(Aarch64Core core, MemoryOp64.MemorySet op) {
        long count = core.x(op.rn());
        if (count == 0) {
            return false;
        }
        if (count < 0) {
            count = Long.MAX_VALUE; // saturação: Xn[63]==1 (ARM DDI 0487)
        }
        long address = core.x(op.rd());
        int fillByte = (int) core.x(op.rs());
        for (long i = 0; i < count; i++) {
            core.memory().write8(address + i, fillByte);
        }
        core.notifyOrdinaryWrite(address, (int) Math.min(count, Integer.MAX_VALUE));
        core.setX(op.rd(), address + count);
        core.setX(op.rn(), 0L);
        core.pstate().setNzcv(MOPS_COMPLETED_FORWARD_NZCV);
        return false;
    }

    /// `CPYFP`/`CPYFM`/`CPYFE`/`CPYP`/`CPYM`/`CPYE` (B19.16) — mesma disciplina de
    /// {@link #executeMemorySet}: a fase que encontra {@link MemoryOp64.MemoryCopy#rn} diferente de
    /// zero copia tudo (byte a byte); {@link MemoryOp64.MemoryCopy#forwardOnly}==`false` (`CPYP`/
    /// `CPYM`/`CPYE`) escolhe a direção do loop pela MESMA regra de `memmove`: se a origem vem
    /// antes do destino e as regiões se sobrepõem, copiar de trás para frente evita que a escrita
    /// destrua bytes de origem ainda não lidos.
    public static boolean executeMemoryCopy(Aarch64Core core, MemoryOp64.MemoryCopy op) {
        long count = core.x(op.rn());
        if (count == 0) {
            return false;
        }
        if (count < 0) {
            count = Long.MAX_VALUE; // saturação: Xn[63]==1 (ARM DDI 0487)
        }
        long dst = core.x(op.rd());
        long src = core.x(op.rs());
        boolean backward = !op.forwardOnly() && src < dst && (src + count) > dst;
        if (backward) {
            for (long i = count - 1; i >= 0; i--) {
                core.memory().write8(dst + i, core.memory().read8(src + i));
            }
        } else {
            for (long i = 0; i < count; i++) {
                core.memory().write8(dst + i, core.memory().read8(src + i));
            }
        }
        core.notifyOrdinaryWrite(dst, (int) Math.min(count, Integer.MAX_VALUE));
        core.setX(op.rd(), dst + count);
        core.setX(op.rs(), src + count);
        core.setX(op.rn(), 0L);
        core.pstate().setNzcv(backward ? MOPS_COMPLETED_BACKWARD_NZCV : MOPS_COMPLETED_FORWARD_NZCV);
        return false;
    }

    /// `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G` (`FEAT_MTE2`, B19.14) — ver Javadoc de {@link MemoryOp64.MemoryTag}.
    public static boolean executeMemoryTag(Aarch64Core core, MemoryOp64.MemoryTag op) {
        long base = readBaseRegister(core, op.rn());
        long addr = transferAddress(core, base, op.addressingMode(), op.immediate(), -1, null, 0);
        long granuleAddress = addr & ~(Aarch64Core.MEMORY_TAG_GRANULE_BYTES - 1L);
        if (op.operation() == MemoryOp64.Ir64MemoryTagOperation.LOAD) {
            int tag = core.memoryTag(granuleAddress);
            core.setX(op.rt(), Aarch64Core.withAllocationTag(core.x(op.rt()), tag));
        } else {
            int tag = Aarch64Core.allocationTagFromAddress(readBaseRegister(core, op.rt()));
            core.setMemoryTag(granuleAddress, tag);
            if (op.granules() == 2) {
                core.setMemoryTag(granuleAddress + Aarch64Core.MEMORY_TAG_GRANULE_BYTES, tag);
            }
            if (op.zeroData()) {
                long dataAddress = Aarch64Core.physicalMemoryTagAddress(addr);
                int totalBytes = Aarch64Core.MEMORY_TAG_GRANULE_BYTES * op.granules();
                for (int i = 0; i < totalBytes; i++) {
                    core.memory().write8(dataAddress + i, 0);
                }
                core.notifyOrdinaryWrite(dataAddress, totalBytes);
            }
        }
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// `STGM`/`LDGM`/`STZGM` (`FEAT_MTE2`, B19.14) — ver Javadoc de {@link MemoryOp64.MemoryTagMultiple}.
    public static boolean executeMemoryTagMultiple(Aarch64Core core, MemoryOp64.MemoryTagMultiple op) {
        long addr = readBaseRegister(core, op.rn());
        switch (op.operation()) {
            case LOAD_TAGS -> core.setX(op.rt(), core.memoryTagBlock(addr));
            case STORE_TAGS -> core.setMemoryTagBlock(addr, core.x(op.rt()));
            case STORE_ZERO_DATA_TAGS -> {
                int tagNibble = (int) core.x(op.rt());
                long blockBase = core.stzgmBlockBaseAndSetTags(addr, tagNibble);
                for (int i = 0; i < Aarch64Core.MEMORY_TAG_STZGM_BLOCK_BYTES; i++) {
                    core.memory().write8(blockBase + i, 0);
                }
                core.notifyOrdinaryWrite(blockBase, Aarch64Core.MEMORY_TAG_STZGM_BLOCK_BYTES);
            }
        }
        return false;
    }

    /// `STGP` (`FEAT_MTE2`, B19.14) — ver Javadoc de {@link MemoryOp64.StorePairTag}: a tag gravada vem
    /// do PRÓPRIO endereço de destino, não de {@link MemoryOp64.StorePairTag#rt}/{@link MemoryOp64.StorePairTag#rt2}.
    public static boolean executeStorePairTag(Aarch64Core core, MemoryOp64.StorePairTag op) {
        long base = readBaseRegister(core, op.rn());
        long addr = transferAddress(core, base, op.addressingMode(), op.immediate(), -1, null, 0);
        long dataAddress = Aarch64Core.physicalMemoryTagAddress(addr);
        core.memory().write64(dataAddress, core.x(op.rt()));
        core.memory().write64(dataAddress + Long.BYTES, core.x(op.rt2()));
        core.notifyOrdinaryWrite(dataAddress, Long.BYTES * 2);
        int tag = Aarch64Core.allocationTagFromAddress(addr);
        core.setMemoryTag(dataAddress, tag);
        writeback(core, op.rn(), op.addressingMode(), base, op.immediate());
        return false;
    }

    /// `SETGP`/`SETGM`/`SETGE` (`FEAT_MTE2`+`FEAT_MOPS`, B19.14) — mesma disciplina de
    /// {@link #executeMemorySet}, mas também grava a tag de {@link MemoryOp64.MemorySetTagged#rd} em
    /// cada granule de 16 bytes tocado pelo preenchimento.
    public static boolean executeMemorySetTagged(Aarch64Core core, MemoryOp64.MemorySetTagged op) {
        long count = core.x(op.rn());
        if (count == 0) {
            return false;
        }
        if (count < 0) {
            count = Long.MAX_VALUE; // saturação: Xn[63]==1 (ARM DDI 0487)
        }
        long address = core.x(op.rd());
        long dataAddress = Aarch64Core.physicalMemoryTagAddress(address);
        int fillByte = (int) core.x(op.rs());
        int tag = Aarch64Core.allocationTagFromAddress(address);
        for (long i = 0; i < count; i++) {
            core.memory().write8(dataAddress + i, fillByte);
        }
        core.notifyOrdinaryWrite(dataAddress, (int) Math.min(count, Integer.MAX_VALUE));
        long granuleStart = dataAddress & ~(Aarch64Core.MEMORY_TAG_GRANULE_BYTES - 1L);
        long granuleEnd = (dataAddress + count - 1) & ~(Aarch64Core.MEMORY_TAG_GRANULE_BYTES - 1L);
        for (long g = granuleStart; g <= granuleEnd; g += Aarch64Core.MEMORY_TAG_GRANULE_BYTES) {
            core.setMemoryTag(g, tag);
        }
        core.setX(op.rd(), address + count);
        core.setX(op.rn(), 0L);
        core.pstate().setNzcv(MOPS_COMPLETED_FORWARD_NZCV);
        return false;
    }

    /// Executa {@link MemoryOp64.LoadStorePair}.
    public static boolean executeLoadStorePair(Aarch64Core core, MemoryOp64.LoadStorePair op) {
        long base = readBaseRegister(core, op.rn());
        long address = op.addressingMode() == Ir64AddressingMode.POST_INDEX
                ? base : base + op.immediate();
        // LDPSW (B8.1): sempre transfere pares de WORD, mesmo escrevendo em X — ver javadoc de
        // MemoryOp64.LoadStorePair#signExtend.
        int stride = (op.wide() && !op.signExtend()) ? PAIR_DOUBLEWORD_STRIDE_BYTES : PAIR_WORD_STRIDE_BYTES;
        Ir64MemSize size = (op.wide() && !op.signExtend()) ? Ir64MemSize.DOUBLEWORD : Ir64MemSize.WORD;
        if (op.load()) {
            long first = readMemory(core, address, size);
            long second = readMemory(core, address + stride, size);
            if (op.signExtend()) {
                first = signExtendFromSize(first, size);
                second = signExtendFromSize(second, size);
                core.setX(op.rt(), first);
                core.setX(op.rt2(), second);
            } else {
                core.setXForWidth(op.rt(), first, op.wide());
                core.setXForWidth(op.rt2(), second, op.wide());
            }
        } else {
            writeMemory(core, address, size, core.xForWidth(op.rt(), op.wide()));
            writeMemory(core, address + stride, size, core.xForWidth(op.rt2(), op.wide()));
            // B6.3.4: STP também é escrita comum — mesma auditoria de executeStore.
            core.notifyOrdinaryWrite(address, size.bytes());
            core.notifyOrdinaryWrite(address + stride, size.bytes());
        }
        if (op.addressingMode() == Ir64AddressingMode.PRE_INDEX
                || op.addressingMode() == Ir64AddressingMode.POST_INDEX) {
            writeBaseRegister(core, op.rn(), base + op.immediate());
        }
        return false;
    }

    /// Executa {@link MemoryOp64.LoadLiteral64}.
    public static boolean executeLoadLiteral(Aarch64Core core, MemoryOp64.LoadLiteral64 op) {
        long value;
        if (op.signExtend()) {
            // LDRSW (literal): única forma com sinal — sempre lê 32 bits e estende para X.
            value = (long) (int) Integer.toUnsignedLong(core.memory().read32(op.address()));
        } else if (op.wide()) {
            value = core.memory().read64(op.address());
        } else {
            value = Integer.toUnsignedLong(core.memory().read32(op.address()));
        }
        core.setX(op.rt(), value);
        return false;
    }

    /// Lê o registrador BASE de um load/store — sempre `SP` quando o campo de encoding é `31`
    /// (nunca `XZR`, ver {@link MemoryOp64.Load64#rn}).
    static long readBaseRegister(Aarch64Core core, int rn) {
        return rn == BASE_REGISTER_SP_ENCODING ? core.sp() : core.x(rn);
    }

    static void writeBaseRegister(Aarch64Core core, int rn, long value) {
        if (rn == BASE_REGISTER_SP_ENCODING) {
            core.setSp(value);
        } else {
            core.setX(rn, value);
        }
    }

    static long transferAddress(Aarch64Core core, long base, Ir64AddressingMode mode,
            long immediate, int rm, Ir64ExtendType extendType, int shiftAmount) {
        return switch (mode) {
            case OFFSET, PRE_INDEX -> base + immediate;
            case POST_INDEX -> base;
            case REGISTER_OFFSET -> base + extendRegisterOffset(core, rm, extendType, shiftAmount);
        };
    }

    private static long extendRegisterOffset(Aarch64Core core, int rm, Ir64ExtendType extendType, int shiftAmount) {
        long extended = switch (extendType) {
            case UXTW -> core.xForWidth(rm, false);
            case SXTW -> (long) (int) core.xForWidth(rm, false);
            case LSL, SXTX -> core.x(rm);
        };
        return extended << shiftAmount;
    }

    static void writeback(Aarch64Core core, int rn, Ir64AddressingMode mode, long base, long immediate) {
        if (mode == Ir64AddressingMode.PRE_INDEX || mode == Ir64AddressingMode.POST_INDEX) {
            writeBaseRegister(core, rn, base + immediate);
        }
    }

    static long readMemory(Aarch64Core core, long address, Ir64MemSize size) {
        return switch (size) {
            case BYTE -> Byte.toUnsignedLong((byte) core.memory().read8(address));
            case HALF -> Short.toUnsignedLong((short) core.memory().read16(address));
            case WORD -> Integer.toUnsignedLong(core.memory().read32(address));
            case DOUBLEWORD -> core.memory().read64(address);
        };
    }

    static void writeMemory(Aarch64Core core, long address, Ir64MemSize size, long value) {
        switch (size) {
            case BYTE -> core.memory().write8(address, (int) value);
            case HALF -> core.memory().write16(address, (int) value);
            case WORD -> core.memory().write32(address, (int) value);
            case DOUBLEWORD -> core.memory().write64(address, value);
        }
    }

    /// Estende o sinal de um valor já lido (zero-estendido pela largura de {@code size}) para os
    /// 64 bits completos — usado por `LDRSB`/`LDRSH`/`LDRSW`.
    static long signExtendFromSize(long zeroExtended, Ir64MemSize size) {
        return switch (size) {
            case BYTE -> (long) (byte) zeroExtended;
            case HALF -> (long) (short) zeroExtended;
            case WORD -> (long) (int) zeroExtended;
            case DOUBLEWORD -> zeroExtended;
        };
    }

    /// Trunca um registrador para a largura de `size`, zero-estendido — usado por `CAS` (B8.1)
    /// para comparar `Rs` contra um valor de memória já zero-estendido por {@link #readMemory}.
    static long zeroTruncateToSize(long value, Ir64MemSize size) {
        return switch (size) {
            case BYTE -> value & 0xFFL;
            case HALF -> value & 0xFFFFL;
            case WORD -> value & 0xFFFF_FFFFL;
            case DOUBLEWORD -> value;
        };
    }
}
