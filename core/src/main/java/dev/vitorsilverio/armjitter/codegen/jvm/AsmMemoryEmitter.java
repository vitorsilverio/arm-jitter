package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.decoder.BlockTransferMode;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Emissão nativa de load/store: LDR/STR, literal, LDM/STM/PUSH/POP (desenrolados no caso
/// comum), LDRD/STRD e acessos exclusivos.
///
/// Os records e as condições em que cada um é nativo estão em {@link #register}; o laço de
/// compilação e o register cache, em {@link AsmBlockCompiler}.
final class AsmMemoryEmitter extends AsmEmitterBase {
    AsmMemoryEmitter(AsmEmitState state) {
        super(state);
    }

    static void register(AsmEmitterRegistry.Builder registry) {
        // `unprivileged` (LDRxT/STRxT, B9.9) precisa de AddressSpace#withUnprivilegedAccess ao redor
        // do acesso — sem equivalente no emissor nativo, cai no interpretado.
        registry.add(AsmEmission.of(MemoryOp.Load.class, AsmEmitters::memory, AsmMemoryEmitter::emitLoad)
                .accepting(AsmMemoryEmitter::acceptsLoad)
                .counting(AsmMemoryEmitter::countLoad));
        registry.add(AsmEmission.of(MemoryOp.Store.class, AsmEmitters::memory, AsmMemoryEmitter::emitStore)
                .accepting(AsmMemoryEmitter::acceptsStore)
                .counting(AsmMemoryEmitter::countStore));
        registry.add(AsmEmission.of(MemoryOp.LoadLiteral.class, AsmEmitters::memory, AsmMemoryEmitter::emitLoadLiteral)
                .counting(AsmMemoryEmitter::countLoadLiteral));
        // LDM/STM/PUSH/POP: o caso comum é DESENROLADO inline (a lista de registradores é constante
        // de compilação) integrado ao register cache.
        registry.add(AsmEmission.of(MemoryOp.MultipleTransfer.class, AsmEmitters::memory, AsmMemoryEmitter::emitMultipleTransferOp)
                .counting(AsmMemoryEmitter::countMultipleTransfer));
        registry.add(AsmEmission.of(MemoryOp.Push.class, AsmEmitters::memory, AsmMemoryEmitter::emitPushInline)
                .counting(AsmMemoryEmitter::countPush));
        registry.add(AsmEmission.of(MemoryOp.Pop.class, AsmEmitters::memory, AsmMemoryEmitter::emitPopInline)
                .counting(AsmMemoryEmitter::countPop));
        registry.add(AsmEmission.of(MemoryOp.DoubleTransfer.class, AsmEmitters::memory, AsmMemoryEmitter::emitDoubleTransfer)
                .accepting(AsmMemoryEmitter::acceptsDoubleTransfer)
                .counting(AsmMemoryEmitter::countDoubleTransfer));
        // Acessos exclusivos (B1.4): nativos desde a task B1.6 — o monitor é checado/marcado por
        // helper, mesma ordem do interpretador.
        registry.add(AsmEmission.of(MemoryOp.LoadExclusive.class, AsmEmitters::memory, AsmMemoryEmitter::emitLoadExclusive)
                .counting(AsmMemoryEmitter::countLoadExclusive));
        registry.add(AsmEmission.of(MemoryOp.StoreExclusive.class, AsmEmitters::memory, AsmMemoryEmitter::emitStoreExclusive)
                .counting(AsmMemoryEmitter::countStoreExclusive));
        registry.add(AsmEmission.of(MemoryOp.ClearExclusive.class, AsmEmitters::memory, AsmMemoryEmitter::emitClearExclusive));
        // SWP/SWPB (C12.7): raro, mas não precisa mais derrubar o BLOCO inteiro.
        registry.add(AsmEmission.interop(MemoryOp.Swap.class));
    }

    static boolean acceptsLoad(MemoryOp.Load load) {
        return !load.unprivileged();
    }

    static boolean acceptsStore(MemoryOp.Store store) {
        return !store.unprivileged();
    }

    /// LDRD para o par (first,second) escreve os dois via emitStoreRegister puro, sem o tratamento
    /// de interworking que emitLoad/emitLoadLiteral dão a PC — então nenhum dos dois pode ser PC num
    /// load. STRD só LÊ os registradores (sem troca de modo), então PC como origem é seguro.
    static boolean acceptsDoubleTransfer(MemoryOp.DoubleTransfer dt) {
        return !dt.load() || (dt.first() != PC_REGISTER && dt.second() != PC_REGISTER);
    }

    static void countLoad(MemoryOp.Load load, AsmAccessCounter counter) {
        if (load.baseValueOverride() == -1) counter.read(load.base());
        counter.operand(load.offset());
        counter.write(load.dst());
        if (load.writeback() && load.base() != load.dst()) {
            counter.write(load.base());
        }
    }

    static void countStore(MemoryOp.Store store, AsmAccessCounter counter) {
        if (store.baseValueOverride() == -1) counter.read(store.base());
        counter.operand(store.offset());
        if (store.srcValueOverride() == -1) counter.read(store.src());
        if (store.writeback()) {
            counter.write(store.base());
        }
    }

    static void countLoadLiteral(MemoryOp.LoadLiteral lit, AsmAccessCounter counter) {
        counter.write(lit.dst());
    }

    static void countMultipleTransfer(MemoryOp.MultipleTransfer mt, AsmAccessCounter counter) {
        if (canInlineMultipleTransfer(mt)) { // as formas raras vão pelo helper (spill)
            counter.read(mt.base());
            if (mt.writeback()) {
                counter.write(mt.base());
            }
            for (int reg = 0; reg < PC_REGISTER; reg++) {
                if ((mt.registerMask() & (1 << reg)) != 0) {
                    if (mt.load()) {
                        counter.write(reg);
                    } else {
                        counter.read(reg);
                    }
                }
            }
        }
    }

    static void countPush(MemoryOp.Push push, AsmAccessCounter counter) {
        counter.read(SP_REGISTER);
        counter.write(SP_REGISTER);
        for (int reg = 0; reg <= 7; reg++) {
            if ((push.registerMask() & (1 << reg)) != 0) {
                counter.read(reg);
            }
        }
        if (push.includeLr()) {
            counter.read(LR_REGISTER);
        }
    }

    static void countPop(MemoryOp.Pop pop, AsmAccessCounter counter) {
        counter.read(SP_REGISTER);
        counter.write(SP_REGISTER);
        for (int reg = 0; reg <= 7; reg++) {
            if ((pop.registerMask() & (1 << reg)) != 0) {
                counter.write(reg);
            }
        }
    }

    static void countDoubleTransfer(MemoryOp.DoubleTransfer dt, AsmAccessCounter counter) {
        if (dt.baseValueOverride() == -1) {
            counter.read(dt.base());
        }
        counter.operand(dt.offset());
        if (dt.load()) {
            counter.write(dt.first());
            counter.write(dt.first() + 1);
        } else {
            counter.read(dt.first());
            counter.read(dt.first() + 1);
        }
        if (dt.writeback()) {
            counter.write(dt.base());
        }
    }

    static void countLoadExclusive(MemoryOp.LoadExclusive ldrex, AsmAccessCounter counter) {
        counter.read(ldrex.base());
        counter.write(ldrex.dst());
        if (ldrex.sizeBytes() == 8) {
            counter.write(ldrex.dst() + 1);
        }
    }

    static void countStoreExclusive(MemoryOp.StoreExclusive strex, AsmAccessCounter counter) {
        counter.read(strex.base());
        counter.read(strex.src());
        if (strex.sizeBytes() == 8) {
            counter.read(strex.src() + 1);
        }
        counter.write(strex.dst());
    }

    /// LDM/STM: desenrolado inline no caso comum; as formas raras (user-mode, lista vazia, PC na
    /// lista) caem no helper, com flush + reload.
    void emitMultipleTransferOp(MethodVisitor method, MemoryOp.MultipleTransfer mt) {
        if (canInlineMultipleTransfer(mt)) {
            emitMultipleTransferInline(method, mt);
        } else {
            emitSpilled(method, () -> emitMultipleTransfer(method, mt));
        }
    }

    /// LDRD/STRD (espelha IrMemoryExecutor.executeDoubleTransfer): dois acessos de 32 bits, um
    /// cálculo de endereço/writeback. PC no par é rejeitado pela policy (fica no interpretado).
    void emitDoubleTransfer(MethodVisitor method, MemoryOp.DoubleTransfer dt) {
        emitOperand(method, dt.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset
        if (dt.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, dt.baseValueOverride());
        } else {
            emitReadRegister(method, dt.base());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!dt.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        int second = dt.second();
        if (dt.load()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, dt.first());
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, second);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, dt.first());
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            emitReadRegister(method, second);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
        }
        // Writeback (não quando um load clobra a base — UNPREDICTABLE, como no interpretador).
        if (dt.writeback() && (!dt.load() || (dt.base() != dt.first() && dt.base() != second))) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, dt.base());
        }
    }

    // ── LDM/STM inline ─────────────────────────────────────────────────────────

    /// O caso comum de LDM/STM que pode ser desenrolado inline: sem user-mode, sem lista vazia e
    /// sem PC na lista (LDM→PC troca de bloco/interworka; STM de PC leria o r15 do core — ambos
    /// ficam no helper).
    static boolean canInlineMultipleTransfer(MemoryOp.MultipleTransfer mt) {
        return !mt.userMode()
                && !mt.emptyRegisterList()
                && mt.registerMask() != 0
                && (mt.registerMask() & (1 << PC_REGISTER)) == 0;
    }

    /// Offset compile-time do primeiro endereço acessado em relação à base (LDM/STM acessa sempre
    /// ascendente a partir do menor endereço).
    static int startOffset(BlockTransferMode mode, int count) {
        return switch (mode) {
            case IA -> 0;
            case IB -> 4;
            case DA -> -(count - 1) * 4;
            case DB -> -count * 4;
        };
    }

    /// Offset compile-time do writeback em relação à base.
    static int writebackOffset(BlockTransferMode mode, int count) {
        return switch (mode) {
            case IA, IB -> count * 4;
            case DA, DB -> -count * 4;
        };
    }

    /// LDM/STM desenrolado: espelha AsmMemoryHelpers.executeMultipleTransfer para o caso comum,
    /// registrador a registrador, lendo/escrevendo pelo register cache (sem flush/reload).
    void emitMultipleTransferInline(MethodVisitor method, MemoryOp.MultipleTransfer mt) {
        int mask = mt.registerMask();
        int count = Integer.bitCount(mask);
        int base = mt.base();
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        boolean baseInMask = (mask & (1 << base)) != 0;
        boolean needWriteback = mt.writeback();

        // ADDR = (baseValue + startOffset) & ~3 — o endereço corrente, incrementado por transferência.
        emitReadRegister(method, base);
        int start = startOffset(mt.mode(), count);
        if (start != 0) {
            AsmBytecode.visitIntConst(method, start);
            method.visitInsn(Opcodes.IADD);
        }
        AsmBytecode.visitIntConst(method, ~3);
        method.visitInsn(Opcodes.IAND);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        // TEMP2 = endereço de writeback (também é o valor gravado por STM quando a base está na
        // lista além da primeira posição).
        if (needWriteback) {
            emitReadRegister(method, base);
            AsmBytecode.visitIntConst(method, writebackOffset(mt.mode(), count));
            method.visitInsn(Opcodes.IADD);
            method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);
        }
        for (int reg = 0; reg < PC_REGISTER; reg++) {
            if ((mask & (1 << reg)) == 0) {
                continue;
            }
            if (mt.load()) {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
                AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
                emitStoreRegister(method, reg);
            } else {
                method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
                method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
                if (needWriteback && reg == base && reg != firstRegister) {
                    method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL); // STM da base pós-writeback
                } else {
                    emitReadRegister(method, reg);
                }
                AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
            }
            method.visitIincInsn(ADDR_LOCAL, 4);
        }
        if (needWriteback && !(mt.load() && baseInMask)) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            emitStoreRegister(method, base);
        }
    }

    /// PUSH desenrolado (THUMB): stores ascendentes a partir de sp-4n, depois sp = sp-4n.
    void emitPushInline(MethodVisitor method, MemoryOp.Push push) {
        int count = Integer.bitCount(push.registerMask()) + (push.includeLr() ? 1 : 0);
        emitReadRegister(method, SP_REGISTER);
        AsmBytecode.visitIntConst(method, count * 4);
        method.visitInsn(Opcodes.ISUB);
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL); // novo sp = primeiro endereço
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        for (int reg = 0; reg <= 7; reg++) {
            if ((push.registerMask() & (1 << reg)) == 0) {
                continue;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, reg);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
            method.visitIincInsn(ADDR_LOCAL, 4);
        }
        if (push.includeLr()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, LR_REGISTER);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
        }
        method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
        emitStoreRegister(method, SP_REGISTER);
    }

    /// POP desenrolado (THUMB): loads ascendentes a partir de sp; POP {..,pc} carrega o PC pelo
    /// helper de interworking da arquitetura e encerra o bloco (pc_changed).
    void emitPopInline(MethodVisitor method, MemoryOp.Pop pop) {
        emitReadRegister(method, SP_REGISTER);
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        for (int reg = 0; reg <= 7; reg++) {
            if ((pop.registerMask() & (1 << reg)) == 0) {
                continue;
            }
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, reg);
            method.visitIincInsn(ADDR_LOCAL, 4);
        }
        if (pop.includePc()) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            emitLoadToPcFromMemory(method);
            method.visitIincInsn(ADDR_LOCAL, 4);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        }
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        emitStoreRegister(method, SP_REGISTER);
    }

    // ── ARMv6/v6K (B1.4): acessos exclusivos ──────────────────────────────────────

    /// LDREX{,B,H}: helper por-valor marca o monitor e lê. LDREXD (sizeBytes=8) não cabe no
    /// helper (dois registradores de destino) — emite `markExclusive` + dois `loadWord` inline,
    /// espelhando `IrMemoryExecutor.executeLoadExclusive`. `offset` só é não-nulo para o `LDREX`
    /// word de 32 bits Thumb-2 (B2.7 PR3).
    void emitLoadExclusive(MethodVisitor method, MemoryOp.LoadExclusive load) {
        emitReadRegister(method, load.base());
        if (load.offset() != 0) {
            AsmBytecode.visitIntConst(method, load.offset());
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        if (load.sizeBytes() == 8) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 8);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "markExclusive", "(" + CORE_REF + "II)V");
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, load.dst());
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadWord", CORE_I_TO_I);
            emitStoreRegister(method, load.dst() + 1);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, load.sizeBytes());
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "loadExclusive", "(" + CORE_REF + "II)I");
            emitStoreRegister(method, load.dst());
        }
    }

    /// STREX{,B,H,D}: checa o monitor ANTES de qualquer escrita (mesma ordem do interpretador) —
    /// falha não toca a memória. Sucesso escreve, zera `dst` e consome o monitor. `offset` só é
    /// não-nulo para o `STREX` word de 32 bits Thumb-2 (B2.7 PR3).
    void emitStoreExclusive(MethodVisitor method, MemoryOp.StoreExclusive store) {
        emitReadRegister(method, store.base());
        if (store.offset() != 0) {
            AsmBytecode.visitIntConst(method, store.offset());
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        AsmBytecode.visitIntConst(method, store.sizeBytes());
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "exclusiveMonitorCovers", "(" + CORE_REF + "II)Z");
        Label fail = new Label();
        Label end = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, fail);
        if (store.sizeBytes() == 8) {
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, store.src());
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            AsmBytecode.visitIntConst(method, 4);
            method.visitInsn(Opcodes.IADD);
            emitReadRegister(method, store.src() + 1);
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "storeWord", CORE_II_TO_V);
        } else {
            String helperName = switch (store.sizeBytes()) {
                case 1 -> "storeByte";
                case 2 -> "storeHalf";
                default -> "storeWord";
            };
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
            emitReadRegister(method, store.src());
            AsmBytecode.invokeStatic(method, MEMORY_HELPERS, helperName, CORE_II_TO_V);
        }
        AsmBytecode.visitIntConst(method, 0);
        emitStoreRegister(method, store.dst());
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "clearExclusiveMonitor", "(" + CORE_REF + ")V");
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(fail);
        AsmBytecode.visitIntConst(method, 1);
        emitStoreRegister(method, store.dst());
        method.visitLabel(end);
    }

    void emitClearExclusive(MethodVisitor method, MemoryOp.ClearExclusive clear) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "clearExclusiveMonitor", "(" + CORE_REF + ")V");
    }

    // ── memory ─────────────────────────────────────────────────────────────────

    void emitLoad(MethodVisitor method, MemoryOp.Load load) {
        // base value
        if (load.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, load.baseValueOverride());
        } else {
            emitReadRegister(method, load.base());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base

        // offset value
        emitOperand(method, load.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset

        // address = post-indexed ? base : base + offset
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!load.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        // read
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        // Acesso atravessado (UNALIGNED_ACCESS) só se aplica com destino diferente do PC —
        // LDR/LDRH pc,... continuam exigindo o alinhamento legado (task B1.7, item 4).
        boolean crossed = unalignedAccess && load.dst() != PC_REGISTER;
        String readHelper = switch (load.sizeBytes()) {
            case 1 -> "loadByte";
            case 2 -> crossed ? (load.signed() ? "loadHalfSignedCrossed" : "loadHalfCrossed")
                    : (load.signed() ? "loadHalfSigned" : "loadHalf");
            default -> crossed ? "loadWordCrossed" : "loadWord";
        };
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, readHelper, CORE_I_TO_I);
        // sign-extend byte if needed (loadByte returns 0–255)
        if (load.sizeBytes() == 1 && load.signed()) {
            method.visitInsn(Opcodes.I2B);
        }

        // store to dst
        if (load.dst() == PC_REGISTER) {
            method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            emitLoadToPcFromMemory(method); // LDR pc: interworka em ARMv5
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            emitStoreRegister(method, load.dst());
        }

        // writeback: base register = base + offset (only when base != dst)
        if (load.writeback() && load.base() != load.dst()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, load.base());
        }
    }

    void emitStore(MethodVisitor method, MemoryOp.Store store) {
        // base value
        if (store.baseValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, store.baseValueOverride());
        } else {
            emitReadRegister(method, store.base());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP1_LOCAL);   // base

        emitOperand(method, store.offset());
        method.visitVarInsn(Opcodes.ISTORE, TEMP2_LOCAL);   // offset

        // address = post-indexed ? base : base + offset
        method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
        if (!store.postIndexed()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
        }
        method.visitVarInsn(Opcodes.ISTORE, ADDR_LOCAL);

        // src value
        if (store.srcValueOverride() != -1) {
            AsmBytecode.visitIntConst(method, store.srcValueOverride());
        } else {
            emitReadRegister(method, store.src());
        }
        method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);   // value

        // write
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, ADDR_LOCAL);
        method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
        String writeHelper = switch (store.sizeBytes()) {
            case 1 -> "storeByte";
            case 2 -> unalignedAccess ? "storeHalfCrossed" : "storeHalf";
            default -> unalignedAccess ? "storeWordCrossed" : "storeWord";
        };
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, writeHelper, CORE_II_TO_V);

        // writeback
        if (store.writeback()) {
            method.visitVarInsn(Opcodes.ILOAD, TEMP1_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP2_LOCAL);
            method.visitInsn(Opcodes.IADD);
            emitStoreRegister(method, store.base());
        }
    }

    void emitLoadLiteral(MethodVisitor method, MemoryOp.LoadLiteral lit) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        AsmBytecode.visitIntConst(method, lit.address());
        String readHelper = switch (lit.sizeBytes()) {
            case 1 -> "loadByte";
            case 2 -> lit.signed() ? "loadHalfSigned" : "loadHalf";
            default -> "loadWord";
        };
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, readHelper, CORE_I_TO_I);
        if (lit.sizeBytes() == 1 && lit.signed()) {
            method.visitInsn(Opcodes.I2B);
        }
        if (lit.dst() == PC_REGISTER) {
            method.visitVarInsn(Opcodes.ISTORE, TEMP3_LOCAL);
            method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
            method.visitVarInsn(Opcodes.ILOAD, TEMP3_LOCAL);
            emitLoadToPcFromMemory(method); // LDR pc,=lit: interworka em ARMv5
            method.visitInsn(Opcodes.ICONST_1);
            method.visitVarInsn(Opcodes.ISTORE, PC_CHANGED_LOCAL);
        } else {
            emitStoreRegister(method, lit.dst());
        }
    }

    // ── LDM/STM/PUSH/POP ───────────────────────────────────────────────────────

    void emitMultipleTransfer(MethodVisitor method, MemoryOp.MultipleTransfer mt) {
        method.visitVarInsn(Opcodes.ALOAD, CORE_LOCAL);
        method.visitInsn(mt.load() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, mt.base());
        AsmBytecode.visitIntConst(method, mt.registerMask());
        method.visitInsn(mt.writeback() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(mt.userMode() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitInsn(mt.emptyRegisterList() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.visitIntConst(method, mt.mode().ordinal());
        method.visitInsn(loadPcInterworks ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        AsmBytecode.invokeStatic(method, MEMORY_HELPERS, "executeMultipleTransfer",
                "(" + CORE_REF + "ZIIZZZIZ)Z");
        emitConditionalSetPcChanged(method);
    }
}
