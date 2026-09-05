package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64SvcHandler;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64FlagConversionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/// Prova o Aceite do PR1 da task B6.4: {@link InterpretedIr64CodeEmitter} (oráculo) e
/// {@link Asm64CodeEmitter} (backend nativo) produzem o MESMO {@link Aarch64CpuSnapshot} e os
/// MESMOS ciclos internos para todo o conjunto de ops coberto pelo PR1 (`Alu64`/`MoveWide`/
/// `PcRelative`/`Branch64`/`CompareBranch64` — reta + desvios da B6.1).
class BlockEquivalenceHarness64Test {
    private final BlockEquivalenceHarness64 harness = new BlockEquivalenceHarness64();
    private final InterpretedIr64CodeEmitter interpreted = new InterpretedIr64CodeEmitter();
    private final Asm64CodeEmitter asm = new Asm64CodeEmitter();

    private static EquivalencePairFactory64 pair() {
        return () -> new EquivalencePair64(newCore(), newCore());
    }

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
    }

    /// Empacota `ops` num bloco com `Cycle`/`Fetch` incondicionais antes de cada op real —
    /// mesma disciplina do lifter real (G4).
    private static Ir64Block blockOf(long startPc, Ir64Op... ops) {
        Ir64Block.Builder builder = Ir64Block.builder(startPc);
        long pc = startPc;
        for (Ir64Op op : ops) {
            builder.add(new Ir64Op.Fetch(pc, 4));
            builder.add(new Ir64Op.Cycle(1));
            builder.add(op);
            pc += 4;
        }
        builder.endPc(pc);
        return builder.sealed();
    }

    @Test
    void everyOpInBlockIsNativelySupported() {
        Ir64Block block = blockOf(0,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 5, true, false, false, false));
        assertTrue(Ir64NativePolicy.supports(block));
    }

    @Test
    void aluAddImmediateWithFlags() {
        Ir64Block block = blockOf(0x1000,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 4, 5, 0x123, true, true, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void aluSubNarrowSetsFlagsAndZeroExtends() {
        Ir64Block block = blockOf(0x2000,
                new Ir64Op.Alu64(Ir64AluOp.SUB, 2, 3, 1, false, true, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void aluLogicalNeverTouchesCarryOverflow() {
        Ir64Block block = blockOf(0x3000,
                new Ir64Op.Alu64(Ir64AluOp.AND, 0, 1, 0xFF, true, true, false, false),
                new Ir64Op.Alu64(Ir64AluOp.ORR, 1, 2, 0xF0, true, false, false, false),
                new Ir64Op.Alu64(Ir64AluOp.EOR, 2, 3, 0x0F, true, false, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void aluDestinationStackPointer() {
        Ir64Block block = blockOf(0x4000,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 31, 31, 0x10, true, false, true, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void moveWideMovzMovnMovk() {
        Ir64Block block = blockOf(0x5000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1234, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 0, 0x5678, 16, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 1, 0x0F0F, 0, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void pcRelativeAdrAndAdrp() {
        Ir64Block block = blockOf(0x6000,
                new Ir64Op.PcRelative(0, 0x6000L, 0x40L, false),
                new Ir64Op.PcRelative(1, 0x6004L, 0x1000L, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void branchUnconditionalWithLink() {
        Ir64Block block = blockOf(0x7000,
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x7000L, 0x7100L, -1, true, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void branchConditionalTakenAndNotTaken() {
        // EQ tomado (candidato/referência começam com Z=1 via um SUBS que zera antes do branch).
        Ir64Block taken = blockOf(0x8000,
                new Ir64Op.Alu64(Ir64AluOp.SUB, 2, 0, 0, true, true, false, false), // X0-0 == 0 -> Z=1
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x8004L, 0x8100L, -1, false, Ir64Condition.EQ));
        harness.assertEquivalent(interpreted, asm, taken, pair());

        Ir64Block notTaken = blockOf(0x9000,
                new Ir64Op.Alu64(Ir64AluOp.ADD, 2, 0, 1, true, true, false, false), // X0+1 != 0 -> Z=0
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x9004L, 0x9100L, -1, false, Ir64Condition.EQ));
        harness.assertEquivalent(interpreted, asm, notTaken, pair());
    }

    @Test
    void branchRegisterFormsBrBlrRet() {
        // BR: registrador puro, sem link.
        Ir64Block br = blockOf(0xA000,
                new Ir64Op.Branch64(Ir64BranchForm.REGISTER, 0xA000L, -1L, 2, false, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, br, () -> {
            Aarch64Core reference = newCore();
            reference.setX(2, 0xA200L);
            Aarch64Core candidate = newCore();
            candidate.setX(2, 0xA200L);
            return new EquivalencePair64(reference, candidate);
        });

        // BLR: grava o link register.
        Ir64Block blr = blockOf(0xB000,
                new Ir64Op.Branch64(Ir64BranchForm.REGISTER, 0xB000L, -1L, 3, true, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, blr, () -> {
            Aarch64Core reference = newCore();
            reference.setX(3, 0xB200L);
            Aarch64Core candidate = newCore();
            candidate.setX(3, 0xB200L);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void compareBranchCbzCbnzTakenAndNotTaken() {
        Ir64Block cbzTaken = blockOf(0xC000,
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 5, true, -1, false, 0xC100L));
        harness.assertEquivalent(interpreted, asm, cbzTaken, pair()); // X5 = 0 por padrão -> CBZ toma

        Ir64Block cbnzTaken = blockOf(0xD000,
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 6, true, -1, true, 0xD100L));
        harness.assertEquivalent(interpreted, asm, cbnzTaken, () -> {
            Aarch64Core reference = newCore();
            reference.setX(6, 1L);
            Aarch64Core candidate = newCore();
            candidate.setX(6, 1L);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void compareBranchTbzTbnz() {
        Ir64Block tbnzTaken = blockOf(0xE000,
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.TBZ_TBNZ, 7, true, 3, true, 0xE100L));
        harness.assertEquivalent(interpreted, asm, tbnzTaken, () -> {
            Aarch64Core reference = newCore();
            reference.setX(7, 1L << 3);
            Aarch64Core candidate = newCore();
            candidate.setX(7, 1L << 3);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void multiInstructionStraightLineBlockThenBranch() {
        Ir64Block block = blockOf(0xF000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x0001, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 0, 0x0002, 16, true),
                new Ir64Op.Alu64(Ir64AluOp.ADD, 1, 0, 0x10, true, false, false, false),
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0xF00CL, 0xF100L, -1, false, Ir64Condition.AL));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void listOfPr1KindsAllNativelySupported() {
        List<Ir64Op> ops = List.of(
                new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 1, true, false, false, false),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true),
                new Ir64Op.PcRelative(0, 0, 0, false),
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0, 4, -1, false, Ir64Condition.AL),
                new Ir64Op.CompareBranch64(Ir64CompareBranchForm.CBZ_CBNZ, 0, true, -1, false, 4));
        for (Ir64Op op : ops) {
            assertTrue(Ir64NativePolicy.supports(op), op.getClass().getSimpleName());
        }
    }

    // ---- PR2: Load64/Store64/LoadStorePair/LoadLiteral64/Svc ----

    @Test
    void listOfPr2KindsAllNativelySupported() {
        List<Ir64Op> ops = List.of(
                new Ir64Op.Svc(0),
                new Ir64Op.Load64(0, 1, Ir64MemSize.WORD, false, true,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.Store64(0, 1, Ir64MemSize.WORD, true,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.LoadStorePair(true, 0, 1, 2, true, Ir64AddressingMode.OFFSET, 0, false),
                new Ir64Op.LoadLiteral64(0, 0x1000L, true, false));
        for (Ir64Op op : ops) {
            assertTrue(Ir64NativePolicy.supports(op), op.getClass().getSimpleName());
        }
    }

    /// `STR`/`LDR` (offset imediato) round-trip: escreve `X1` em `[X0]` e relê em `X2` — cobre a
    /// forma de endereçamento mais comum ({@link Ir64AddressingMode#OFFSET}, sem writeback).
    @Test
    void storeThenLoadOffsetRoundTrip() {
        Ir64Block block = blockOf(0x1000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x100, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0xABCD, 0, true),
                new Ir64Op.Store64(1, 0, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.Load64(2, 0, Ir64MemSize.WORD, false, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `LDRSB` (sign-extend): escreve um byte `0xFF` e relê estendendo o sinal para `X` completo
    /// (`-1` em complemento de dois) — prova que {@link Ir64Op.Load64#signExtend} é respeitado
    /// identicamente pelos dois backends.
    @Test
    void loadSignedByteSignExtends() {
        Ir64Block block = blockOf(0x2000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x110, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0xFF, 0, true),
                new Ir64Op.Store64(1, 0, Ir64MemSize.BYTE, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.Load64(2, 0, Ir64MemSize.BYTE, true, true,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// Pré-índice e pós-índice: prova que o writeback de `Rn|SP` (mesmo valor final em ambos os
    /// backends) acontece igual — armadilha clássica de load/store indexado (G4 não aplica aqui,
    /// mas o writeback em si é um efeito colateral fácil de esquecer replicar).
    @Test
    void storePreAndPostIndexWriteback() {
        Ir64Block preIndex = blockOf(0x3000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x120, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x55, 0, true),
                new Ir64Op.Store64(1, 0, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.PRE_INDEX, 8, -1, null, 0));
        harness.assertEquivalent(interpreted, asm, preIndex, pair());

        Ir64Block postIndex = blockOf(0x4000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x130, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x66, 0, true),
                new Ir64Op.Store64(1, 0, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.POST_INDEX, 8, -1, null, 0));
        harness.assertEquivalent(interpreted, asm, postIndex, pair());
    }

    /// {@link Ir64AddressingMode#REGISTER_OFFSET}: endereço = `Rn + extend(Rm)` — a única forma
    /// que carrega {@link Ir64Op.Load64#rm}/{@link Ir64Op.Load64#extendType} (campos `null`/`-1`
    /// nos demais testes acima; este cobre o caminho onde o compilador ASM precisa reconstruir um
    /// enum possivelmente-`null` corretamente, ver {@code Ir64BlockCompiler#emitEnumConstantOrNull}).
    @Test
    void loadStoreRegisterOffsetAddressing() {
        Ir64Block block = blockOf(0x5000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x140, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 3, 0x8, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x77, 0, true),
                new Ir64Op.Store64(1, 0, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.REGISTER_OFFSET, 0, 3, Ir64ExtendType.LSL, 0),
                new Ir64Op.Load64(2, 0, Ir64MemSize.WORD, false, false,
                        Ir64AddressingMode.REGISTER_OFFSET, 0, 3, Ir64ExtendType.LSL, 0));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `STP`/`LDP` round-trip de 64 bits — idioma de prólogo/epílogo mais comum de binários A64
    /// reais.
    @Test
    void loadStorePairRoundTrip() {
        Ir64Block block = blockOf(0x6000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x200, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x11, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0x22, 0, true),
                new Ir64Op.LoadStorePair(false, 1, 2, 0, true, Ir64AddressingMode.OFFSET, 0, false),
                new Ir64Op.LoadStorePair(true, 3, 4, 0, true, Ir64AddressingMode.OFFSET, 0, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `LDPSW` (B8.1): par de 32 bits com sinal escrito em `X` completo — prova que
    /// {@link Ir64Op.LoadStorePair#signExtend} é respeitado identicamente pelos dois backends
    /// (mesmo cuidado de {@link #loadSignedByteSignExtends} para o par).
    @Test
    void loadStorePairSignedWordSignExtends() {
        Ir64Block block = blockOf(0x6100,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x210, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 1, 0, 0, false),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 2, 0, 0, false),
                new Ir64Op.LoadStorePair(false, 1, 2, 0, false, Ir64AddressingMode.OFFSET, 0, false),
                new Ir64Op.LoadStorePair(true, 3, 4, 0, false, Ir64AddressingMode.OFFSET, 0, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `LDR (literal)`: lê um endereço absoluto pré-preenchido por um `STR` anterior no mesmo
    /// bloco — prova {@link Ir64Op.LoadLiteral64} sem depender de um decoder real montando o
    /// deslocamento relativo ao PC (fora do escopo aqui, já coberto pelos corpus tests do
    /// decoder).
    @Test
    void loadLiteralReadsAbsoluteAddress() {
        Ir64Block block = blockOf(0x7000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x300, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x1234, 0, true),
                new Ir64Op.Store64(1, 0, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.LoadLiteral64(2, 0x300L, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `SVC`: despacha para o {@link Aarch64SvcHandler} instalado no core — instala o MESMO
    /// handler (grava o imediato em `X0`) nos dois cores do par para provar que o dispatch do
    /// backend ASM (reconstrução do record `Svc` + `Ir64BlockExecutor#executeOp`) chega ao mesmo
    /// handler que o interpretado.
    @Test
    void svcDispatchesToInstalledHandler() {
        Ir64Block block = blockOf(0x8000, new Ir64Op.Svc(0x42));
        harness.assertEquivalent(interpreted, asm, block, () -> {
            Aarch64SvcHandler handler = (core, immediate) -> core.setX(0, immediate);
            Aarch64Core reference = newCore();
            reference.setSvcHandler(handler);
            Aarch64Core candidate = newCore();
            candidate.setSvcHandler(handler);
            return new EquivalencePair64(reference, candidate);
        });
    }

    // ---- PR3: AluShiftedRegister/AluExtendedRegister/ConditionalSelect/Bitfield/
    // MultiplyAccumulate/Divide/LoadExclusive/StoreExclusive ----

    @Test
    void listOfPr3KindsAllNativelySupported() {
        List<Ir64Op> ops = List.of(
                new Ir64Op.AluShiftedRegister(Ir64AluOp.ADD, 0, 1, 2, Ir64ShiftType.LSL, 0, true, false),
                new Ir64Op.AluExtendedRegister(
                        Ir64AluOp.ADD, 0, 31, 2, Ir64AluExtendType.UXTX, 0, true, false, false),
                new Ir64Op.ConditionalSelect(Ir64ConditionalSelectOp.CSEL, 0, 1, 2, true, Ir64Condition.EQ),
                new Ir64Op.Bitfield(Ir64BitfieldOp.UBFM, 0, 1, 0, 7, true),
                new Ir64Op.MultiplyAccumulate(false, 0, 1, 2, 3, true),
                new Ir64Op.Divide(true, 0, 1, 2, true),
                new Ir64Op.LoadExclusive(0, 31, Ir64MemSize.WORD, false),
                new Ir64Op.StoreExclusive(0, 1, 31, Ir64MemSize.WORD, false));
        for (Ir64Op op : ops) {
            assertTrue(Ir64NativePolicy.supports(op), op.getClass().getSimpleName());
        }
    }

    @Test
    void aluShiftedRegisterAddWithFlagsAndLsl() {
        Ir64Block block = blockOf(0x9100,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x10, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x1, 0, true),
                new Ir64Op.AluShiftedRegister(Ir64AluOp.ADD, 2, 0, 1, Ir64ShiftType.LSL, 4, true, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `AluExtendedRegister` com `Rn|SP`: cobre a resolução POR ÍNDICE (não pela flag) descrita em
    /// {@code Ir64BlockExecutor#executeAluExtendedRegister} — `src1=31` sempre lê `SP`.
    @Test
    void aluExtendedRegisterReadsAndWritesStackPointer() {
        Ir64Block block = blockOf(0x9200,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0x20, 0, true),
                new Ir64Op.AluExtendedRegister(
                        Ir64AluOp.ADD, 31, 31, 2, Ir64AluExtendType.UXTX, 0, true, false, true));
        harness.assertEquivalent(interpreted, asm, block, () -> {
            Aarch64Core reference = newCore();
            reference.setSp(0x1000L);
            Aarch64Core candidate = newCore();
            candidate.setSp(0x1000L);
            return new EquivalencePair64(reference, candidate);
        });
    }

    @Test
    void conditionalSelectAllFourOpcodes() {
        Ir64Block block = blockOf(0x9300,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x11, 0, true), // condição EQ falsa (Z=0)
                new Ir64Op.Alu64(Ir64AluOp.SUB, 5, 0, 0, true, true, false, false),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x7, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0x9, 0, true),
                new Ir64Op.ConditionalSelect(Ir64ConditionalSelectOp.CSEL, 10, 1, 2, true, Ir64Condition.EQ),
                new Ir64Op.ConditionalSelect(Ir64ConditionalSelectOp.CSINC, 11, 1, 2, true, Ir64Condition.EQ),
                new Ir64Op.ConditionalSelect(Ir64ConditionalSelectOp.CSINV, 12, 1, 2, true, Ir64Condition.EQ),
                new Ir64Op.ConditionalSelect(Ir64ConditionalSelectOp.CSNEG, 13, 1, 2, true, Ir64Condition.EQ));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void bitfieldUbfmSbfmBfm() {
        Ir64Block block = blockOf(0x9400,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0xFFFF, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 1, 0x1234, 0, true),
                new Ir64Op.Bitfield(Ir64BitfieldOp.UBFM, 2, 0, 4, 11, true),
                new Ir64Op.Bitfield(Ir64BitfieldOp.SBFM, 3, 0, 4, 11, true),
                new Ir64Op.Bitfield(Ir64BitfieldOp.BFM, 1, 0, 4, 11, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    @Test
    void multiplyAccumulateMaddAndMsub() {
        Ir64Block block = blockOf(0x9500,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 6, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 7, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 100, 0, true),
                new Ir64Op.MultiplyAccumulate(false, 3, 0, 1, 2, true),
                new Ir64Op.MultiplyAccumulate(true, 4, 0, 1, 2, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `SDIV`/`UDIV`, incluindo divisor `0` (resultado `0` SEM lançar — Fatos de referência #2 de
    /// B6.3.3) e a leitura assinada explícita em `W` (`SDIV` narrow).
    @Test
    void divideSignedUnsignedAndByZero() {
        Ir64Block block = blockOf(0x9600,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 100, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 7, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0, 0, true),
                new Ir64Op.Divide(false, 3, 0, 1, true),
                new Ir64Op.Divide(true, 4, 0, 1, false),
                new Ir64Op.Divide(true, 5, 0, 2, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `LDXR`/`STXR` round-trip: carrega com exclusividade em `[X0]`, depois grava de volta —
    /// prova que o monitor de exclusividade (marcado por `LoadExclusive`, checado por
    /// `StoreExclusive` ANTES da escrita) produz o MESMO `rs`/memória/`Aarch64CpuSnapshot` nos
    /// dois backends.
    @Test
    void loadExclusiveThenStoreExclusiveSucceeds() {
        Ir64Block block = blockOf(0x9700,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x400, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 3, 0xABCD, 0, true),
                new Ir64Op.LoadExclusive(1, 0, Ir64MemSize.WORD, false),
                new Ir64Op.StoreExclusive(2, 3, 0, Ir64MemSize.WORD, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `STXR` sem `LDXR` antes: monitor nunca foi marcado, então a escrita falha (`rs`=1, memória
    /// intacta) — prova que os dois backends concordam também no caminho de FALHA.
    @Test
    void storeExclusiveWithoutReservationFails() {
        Ir64Block block = blockOf(0x9800,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x500, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 3, 0x1111, 0, true),
                new Ir64Op.StoreExclusive(2, 3, 0, Ir64MemSize.WORD, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    // ---- B6.5.4: Fp64Alu/Fp64MoveImmediate/Fp64Compare/Fp64Convert ----

    /// Aplica `setup` a dois cores novos idênticos — mesmo padrão dos pares customizados acima,
    /// mas para os registros de FP ({@code core.fp()}, banco `V`, B6.5.1) em vez de `X`/`SP`.
    private static EquivalencePairFactory64 fpPair(java.util.function.Consumer<Aarch64Core> setup) {
        return () -> {
            Aarch64Core reference = newCore();
            setup.accept(reference);
            Aarch64Core candidate = newCore();
            setup.accept(candidate);
            return new EquivalencePair64(reference, candidate);
        };
    }

    @Test
    void listOfB654FpKindsAllNativelySupported() {
        List<Ir64Op> ops = List.of(
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ADD, true, 0, 1, 2),
                new Ir64Op.Fp64MoveImmediate(false, 0, 0x3F800000L),
                new Ir64Op.Fp64Compare(true, false, false, 0, 1),
                new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F32_TO_F64, 0, 1));
        for (Ir64Op op : ops) {
            assertTrue(Ir64NativePolicy.supports(op), op.getClass().getSimpleName());
        }
    }

    @Test
    void fp64AluAddSubMulDivSingleAndDouble() {
        Ir64Block single = blockOf(0xA100,
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ADD, false, 2, 0, 1),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.SUB, false, 3, 0, 1),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MUL, false, 4, 0, 1),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.DIV, false, 5, 0, 1));
        harness.assertEquivalent(interpreted, asm, single, fpPair(core -> {
            core.fp().setSFloat(0, 0.1f);
            core.fp().setSFloat(1, 0.2f);
        }));

        Ir64Block dbl = blockOf(0xA200,
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ADD, true, 2, 0, 1),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.SUB, true, 3, 0, 1),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MUL, true, 4, 0, 1),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.DIV, true, 5, 0, 1));
        harness.assertEquivalent(interpreted, asm, dbl, fpPair(core -> {
            core.fp().setDDouble(0, 5.0);
            core.fp().setDDouble(1, 2.0);
        }));
    }

    /// `NEG`/`ABS`/`MOV` preservam payload de NaN via manipulação crua de bits (Ir64FpExecutorTest)
    /// — aqui só prova que o backend ASM concorda bit-a-bit com o interpretado para o mesmo caso.
    @Test
    void fp64AluNegAbsMovPreserveNanPayloadSingleAndDouble() {
        int nanWithPayloadSingle = 0x7FC0BEEF;
        Ir64Block single = blockOf(0xA300,
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NEG, false, 1, 0, 0),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ABS, false, 2, 0, 0),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MOV, false, 3, 0, 0));
        harness.assertEquivalent(interpreted, asm, single, fpPair(core -> core.fp().setS(0, nanWithPayloadSingle)));

        long nanWithPayloadDouble = 0xFFF8_0000_0000_BEEFL;
        Ir64Block dbl = blockOf(0xA400,
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NEG, true, 1, 0, 0),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ABS, true, 2, 0, 0),
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MOV, true, 3, 0, 0));
        harness.assertEquivalent(interpreted, asm, dbl, fpPair(core -> core.fp().setD(0, nanWithPayloadDouble)));
    }

    /// `FMOV #imm`: os 4 vetores canônicos citados na task (`0.0`/`1.0`/`-1.0`/`2.0`), single e
    /// double — bits já expandidos, o compilador ASM só reconstrói o record `Fp64MoveImmediate`.
    @Test
    void fp64MoveImmediateFourCanonicalVectorsSingleAndDouble() {
        long[] singleBits = {
                Float.floatToRawIntBits(0.0f) & 0xFFFF_FFFFL,
                Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL,
                Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL,
                Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL};
        Ir64Op[] singleOps = new Ir64Op[singleBits.length];
        for (int i = 0; i < singleBits.length; i++) {
            singleOps[i] = new Ir64Op.Fp64MoveImmediate(false, i, singleBits[i]);
        }
        harness.assertEquivalent(interpreted, asm, blockOf(0xA500, singleOps), pair());

        long[] doubleBits = {
                Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(1.0),
                Double.doubleToRawLongBits(-1.0),
                Double.doubleToRawLongBits(2.0)};
        Ir64Op[] doubleOps = new Ir64Op[doubleBits.length];
        for (int i = 0; i < doubleBits.length; i++) {
            doubleOps[i] = new Ir64Op.Fp64MoveImmediate(true, i, doubleBits[i]);
        }
        harness.assertEquivalent(interpreted, asm, blockOf(0xA600, doubleOps), pair());
    }

    /// `FCMP`: os 4 quadrantes de NZCV (igual/menor/maior/desordenado-NaN), incl. `compareWithZero`
    /// e `FCMPE` (`signalOnQuietNaN`).
    @Test
    void fp64CompareFourQuadrantsNzcv() {
        harness.assertEquivalent(interpreted, asm,
                blockOf(0xA700, new Ir64Op.Fp64Compare(false, false, false, 0, 1)),
                fpPair(core -> {
                    core.fp().setSFloat(0, 1.0f);
                    core.fp().setSFloat(1, 1.0f);
                }));

        harness.assertEquivalent(interpreted, asm,
                blockOf(0xA800, new Ir64Op.Fp64Compare(false, false, false, 0, 1)),
                fpPair(core -> {
                    core.fp().setSFloat(0, 1.0f);
                    core.fp().setSFloat(1, 2.0f);
                }));

        harness.assertEquivalent(interpreted, asm,
                blockOf(0xA900, new Ir64Op.Fp64Compare(false, false, false, 0, 1)),
                fpPair(core -> {
                    core.fp().setSFloat(0, 2.0f);
                    core.fp().setSFloat(1, 1.0f);
                }));

        harness.assertEquivalent(interpreted, asm,
                blockOf(0xAA00, new Ir64Op.Fp64Compare(false, false, true, 0, 1)),
                fpPair(core -> {
                    core.fp().setSFloat(0, Float.NaN);
                    core.fp().setSFloat(1, 1.0f);
                }));

        harness.assertEquivalent(interpreted, asm,
                blockOf(0xAB00, new Ir64Op.Fp64Compare(true, true, false, 0, -1)),
                fpPair(core -> core.fp().setDDouble(0, -1.0)));
    }

    /// `FCVT` nas duas direções, incl. narrowing com perda de precisão (`0.1` double->float) e
    /// widening exato.
    @Test
    void fp64ConvertBothDirectionsIncludingPrecisionLoss() {
        harness.assertEquivalent(interpreted, asm,
                blockOf(0xAC00, new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F32_TO_F64, 1, 0)),
                fpPair(core -> core.fp().setSFloat(0, 1.5f)));

        harness.assertEquivalent(interpreted, asm,
                blockOf(0xAD00, new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F64_TO_F32, 1, 0)),
                fpPair(core -> core.fp().setDDouble(0, 0.1)));
    }

    /// Property test (mesmo espírito de `b3.6-vfp-asm-nativo.md` item 2): N valores aleatórios com
    /// seed fixa x as 4 operações de `Fp64Alu` x single/double — bits idênticos interpretado x ASM.
    @Test
    void fp64AluPropertyTestRandomValuesFixedSeed() {
        Random random = new Random(0x6_5_4L);
        // B8.4 estende o property test com as 6 operações novas — NMUL/MAX/MIN/MAXNM/MINNM
        // binárias (vn=0, vm=1, mesma convenção das 4 originais) e SQRT unária (só lê `vm=1`,
        // mesma convenção de NEG/ABS/MOV — `vn=0` fica sem uso, harmless).
        Ir64Op.Fp64Operation[] operations = {
                Ir64Op.Fp64Operation.ADD, Ir64Op.Fp64Operation.SUB,
                Ir64Op.Fp64Operation.MUL, Ir64Op.Fp64Operation.DIV,
                Ir64Op.Fp64Operation.NMUL, Ir64Op.Fp64Operation.SQRT,
                Ir64Op.Fp64Operation.MAX, Ir64Op.Fp64Operation.MIN,
                Ir64Op.Fp64Operation.MAXNM, Ir64Op.Fp64Operation.MINNM};
        long pc = 0xB000;
        for (int i = 0; i < 50; i++) {
            boolean doublePrecision = random.nextBoolean();
            Ir64Op.Fp64Operation op = operations[random.nextInt(operations.length)];
            long a = doublePrecision ? Double.doubleToRawLongBits(random.nextDouble() * 1000 - 500)
                    : Float.floatToRawIntBits(random.nextFloat() * 1000 - 500) & 0xFFFF_FFFFL;
            long b = doublePrecision ? Double.doubleToRawLongBits(random.nextDouble() * 1000 - 500)
                    : Float.floatToRawIntBits(random.nextFloat() * 1000 - 500) & 0xFFFF_FFFFL;
            Ir64Block block = blockOf(pc, new Ir64Op.Fp64Alu(op, doublePrecision, 2, 0, 1));
            harness.assertEquivalent(interpreted, asm, block, fpPair(core -> {
                if (doublePrecision) {
                    core.fp().setD(0, a);
                    core.fp().setD(1, b);
                } else {
                    core.fp().setS(0, (int) a);
                    core.fp().setS(1, (int) b);
                }
            }));
            pc += 0x100;
        }
    }

    // ---- C12.3: inteiro restante (ALU registrador, comparação condicional, 1-source/
    // multiplicação, exclusivos/atômicos de par, manipulação de flags) — 16 Kind ----

    @Test
    void listOfC123KindsAllNativelySupported() {
        List<Ir64Op> ops = List.of(
                new Ir64Op.ConditionalCompare(Ir64AluOp.SUB, 0, false, 1, -1, true, Ir64Condition.EQ, 0),
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.AND, 0, 1, 2, Ir64LogicalShiftType.LSL, 0, false, true, false),
                new Ir64Op.ShiftVariable(0, 1, 2, Ir64LogicalShiftType.LSL, true),
                new Ir64Op.AluWithCarry(false, 0, 1, 2, true, false),
                new Ir64Op.Extract(0, 1, 2, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLZ, 0, 1, true),
                new Ir64Op.MultiplyAccumulateLong(false, false, 0, 1, 2, 3),
                new Ir64Op.MultiplyHigh(false, 0, 1, 2),
                new Ir64Op.CompareAndSwap(0, 1, 31, Ir64MemSize.WORD),
                new Ir64Op.CompareAndSwapPair(0, 2, 31, true),
                new Ir64Op.LoadExclusivePair(0, 1, 31, true, false),
                new Ir64Op.StoreExclusivePair(0, 1, 2, 31, true, false),
                new Ir64Op.AtomicMemoryOp(0, 1, 31, Ir64MemSize.WORD, Ir64AtomicOp.ADD, false, false),
                new Ir64Op.EvaluateIntoFlags(0, 8),
                new Ir64Op.RotateIntoFlags(0, 4, 0b1010),
                new Ir64Op.ConvertFlags(Ir64FlagConversionOp.INVERT_CARRY));
        for (Ir64Op op : ops) {
            assertTrue(Ir64NativePolicy.supports(op), op.getClass().getSimpleName());
        }
    }

    /// `CCMP`/`CCMN`: condição VERDADEIRA recalcula `NZCV` da comparação real; condição FALSA
    /// escreve o `nzcv` imediato direto, sem ler `rn`/`rm` (Armadilha 4 da spec).
    @Test
    void conditionalCompareTrueRecomputesFalseWritesImmediateNzcv() {
        Ir64Block conditionTrue = blockOf(0xC000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 5, 0, true), // Z=1 antes -> AL sempre verdadeiro
                new Ir64Op.Alu64(Ir64AluOp.SUB, 9, 0, 5, true, true, false, false), // força Z=1
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 5, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 3, 0, true),
                new Ir64Op.ConditionalCompare(Ir64AluOp.SUB, 1, false, 2, -1, true, Ir64Condition.EQ, 0b0101));
        harness.assertEquivalent(interpreted, asm, conditionTrue, pair());

        Ir64Block conditionFalse = blockOf(0xC100,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 5, 0, true),
                new Ir64Op.Alu64(Ir64AluOp.ADD, 9, 0, 1, true, true, false, false), // Z=0
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 5, 0, true),
                new Ir64Op.ConditionalCompare(Ir64AluOp.ADD, 1, true, -1, 7, true, Ir64Condition.EQ, 0b1001));
        harness.assertEquivalent(interpreted, asm, conditionFalse, pair());
    }

    /// `LogicalShiftedRegister`: as 4 combinações de {@link Ir64LogicalShiftType} (incl. `ROR`,
    /// exclusiva desta forma) e `invert` produzindo `BIC`/`ORN`/`EON` a partir do MESMO opcode.
    @Test
    void logicalShiftedRegisterAllShiftTypesAndInvert() {
        Ir64Block block = blockOf(0xC200,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0xFF00, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x00F0, 0, true),
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.AND, 2, 0, 1, Ir64LogicalShiftType.LSL, 0, false, true, true),
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.ORR, 3, 0, 1, Ir64LogicalShiftType.LSR, 4, false, true, false),
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.EOR, 4, 0, 1, Ir64LogicalShiftType.ASR, 4, false, true, false),
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.AND, 5, 0, 1, Ir64LogicalShiftType.ROR, 8, true, true, false), // BIC
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.ORR, 6, 0, 1, Ir64LogicalShiftType.LSL, 0, true, true, false), // ORN
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.EOR, 7, 0, 1, Ir64LogicalShiftType.LSL, 0, true, true, false)); // EON
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `LSLV`/`LSRV`/`ASRV`/`RORV`: quantidade tomada de um REGISTRADOR (não imediato), incluindo o
    /// mascaramento `mod regsize` quando o valor excede a largura (Aceite: deslocamento `>= 64`).
    @Test
    void shiftVariableAllTypesAndModuloMasking() {
        Ir64Block block = blockOf(0xC300,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 65, 0, true), // 65 mod 64 == 1 (wide)
                new Ir64Op.ShiftVariable(2, 0, 1, Ir64LogicalShiftType.LSL, true),
                new Ir64Op.ShiftVariable(3, 0, 1, Ir64LogicalShiftType.LSR, true),
                new Ir64Op.ShiftVariable(4, 0, 1, Ir64LogicalShiftType.ASR, true),
                new Ir64Op.ShiftVariable(5, 0, 1, Ir64LogicalShiftType.ROR, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 6, 33, 0, true), // 33 mod 32 == 1 (narrow)
                new Ir64Op.ShiftVariable(7, 0, 6, Ir64LogicalShiftType.LSL, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `ADC`/`SBC`: `C` de entrada `0` e `1` produzem resultados diferentes (Aceite explícito).
    @Test
    void aluWithCarryAdcAndSbcWithCarryInZeroAndOne() {
        Ir64Block carryOut1 = blockOf(0xC400,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 0, 0, 0, true), // X0 = 0xFFFF...FFFF
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 1, 0, true),
                new Ir64Op.Alu64(Ir64AluOp.ADD, 9, 0, 1, true, true, false, false), // gera C=1
                new Ir64Op.AluWithCarry(false, 2, 0, 1, true, true), // ADCS com C=1 de entrada
                new Ir64Op.AluWithCarry(true, 3, 0, 1, true, true)); // SBCS com C=1 de entrada
        harness.assertEquivalent(interpreted, asm, carryOut1, pair());

        Ir64Block carryOut0 = blockOf(0xC500,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 1, 0, true),
                new Ir64Op.Alu64(Ir64AluOp.SUB, 9, 0, 5, true, true, false, false), // gera C=0
                new Ir64Op.AluWithCarry(false, 2, 0, 1, true, false), // ADC sem flags, C=0 de entrada
                new Ir64Op.AluWithCarry(true, 3, 0, 1, true, false));
        harness.assertEquivalent(interpreted, asm, carryOut0, pair());
    }

    /// `EXTR`: janela nos dois extremos de `lsb` (`0` = resultado é exatamente `src2`; máximo válido
    /// `63` para `wide`).
    @Test
    void extractLsbZeroAndMaximum() {
        Ir64Block block = blockOf(0xC600,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1111, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 0, 0x2222, 16, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x3333, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 1, 0x4444, 16, true),
                new Ir64Op.Extract(2, 0, 1, 0, true),
                new Ir64Op.Extract(3, 0, 1, 63, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `DataProcessing1Source`: as 7 sub-operações de {@link Ir64OneSourceOp}.
    @Test
    void dataProcessing1SourceAllSubOperations() {
        Ir64Block block = blockOf(0xC700,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x1234, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVK, 0, 0x5678, 16, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.RBIT, 1, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV16, 2, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV32, 3, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.REV64, 4, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLZ, 5, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLS, 6, 0, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CNT, 7, 0, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` — com `Ra != XZR` (Aceite explícito).
    @Test
    void multiplyAccumulateLongSignedAndUnsigned() {
        Ir64Block block = blockOf(0xC800,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 0, 5, 0, false), // W0 = -6 (negativo em W)
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 7, 0, false),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 1000, 0, true),
                new Ir64Op.MultiplyAccumulateLong(false, true, 3, 0, 1, 2), // SMADDL
                new Ir64Op.MultiplyAccumulateLong(true, true, 4, 0, 1, 2), // SMSUBL
                new Ir64Op.MultiplyAccumulateLong(false, false, 5, 0, 1, 2), // UMADDL
                new Ir64Op.MultiplyAccumulateLong(true, false, 6, 0, 1, 2)); // UMSUBL
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `SMULH`/`UMULH` no MESMO padrão de bits (Aceite explícito: assinado × não assinado diverge).
    @Test
    void multiplyHighSignedVersusUnsignedSameBitPattern() {
        Ir64Block block = blockOf(0xC900,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVN, 0, 0, 0, true), // X0 = -1 (todos os bits 1)
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 2, 0, true),
                new Ir64Op.MultiplyHigh(true, 2, 0, 1), // SMULH: -1 * 2 = -2, high = -1
                new Ir64Op.MultiplyHigh(false, 3, 0, 1)); // UMULH: valor grande sem sinal
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `CAS`: sucesso (comparação bate, escreve `Rt`, `Rs` recebe o valor antigo) E falha
    /// (comparação não bate, memória intacta, `Rs` ainda recebe o valor antigo — Aceite explícito).
    /// `rn=31` é sempre `SP` (Armadilha do índice 31 nesta forma).
    @Test
    void compareAndSwapSuccessAndFailure() {
        Ir64Block success = blockOf(0xCA00,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, 0x600, 0, true), // endereço em X5
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x11, 0, true),
                new Ir64Op.Store64(1, 5, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0), // memória[0x600] = 0x11
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x11, 0, true), // Rs = 0x11 (bate)
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0x22, 0, true), // Rt = 0x22 (novo valor)
                new Ir64Op.CompareAndSwap(0, 2, 5, Ir64MemSize.WORD));
        harness.assertEquivalent(interpreted, asm, success, pair());

        Ir64Block failure = blockOf(0xCB00,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, 0x610, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x33, 0, true),
                new Ir64Op.Store64(1, 5, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0), // memória[0x610] = 0x33
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x99, 0, true), // Rs = 0x99 (NÃO bate)
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0x44, 0, true),
                new Ir64Op.CompareAndSwap(0, 2, 5, Ir64MemSize.WORD));
        harness.assertEquivalent(interpreted, asm, failure, pair());
    }

    /// `CASP`: par de 64 bits, sucesso comparando `(Rs,Rs+1)` contra `[Rn]`/`[Rn+8]`.
    @Test
    void compareAndSwapPairRoundTrip() {
        Ir64Block block = blockOf(0xCC00,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, 0x620, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 10, 0xAA, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 11, 0xBB, 0, true),
                new Ir64Op.LoadStorePair(false, 10, 11, 5, true, Ir64AddressingMode.OFFSET, 0, false),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0xAA, 0, true), // Rs = 0xAA
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0xBB, 0, true), // Rs+1 = 0xBB
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0xCC, 0, true), // Rt
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 3, 0xDD, 0, true), // Rt+1
                new Ir64Op.CompareAndSwapPair(0, 2, 5, true));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `LDXP`/`STXP`: round-trip de par com sucesso E `STXP` sem reserva prévia (falha) — mesmo
    /// espírito de {@link #loadExclusiveThenStoreExclusiveSucceeds}/
    /// {@link #storeExclusiveWithoutReservationFails}, para a forma de PAR.
    @Test
    void loadExclusivePairThenStoreExclusivePairSucceedsAndFailsWithoutReservation() {
        Ir64Block success = blockOf(0xCD00,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, 0x630, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 10, 0x100, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 11, 0x200, 0, true),
                new Ir64Op.LoadExclusivePair(0, 1, 5, true, false),
                new Ir64Op.StoreExclusivePair(2, 10, 11, 5, true, false));
        harness.assertEquivalent(interpreted, asm, success, pair());

        Ir64Block failure = blockOf(0xCE00,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, 0x640, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 10, 0x300, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 11, 0x400, 0, true),
                new Ir64Op.StoreExclusivePair(2, 10, 11, 5, true, false));
        harness.assertEquivalent(interpreted, asm, failure, pair());
    }

    /// `AtomicMemoryOp`: cada uma das 9 {@link Ir64AtomicOp} E as variantes de ordenação (`A`/`R`,
    /// Aceite explícito) — `Rt` grava o valor ANTIGO lido; a memória fica com o resultado do RMW.
    @Test
    void atomicMemoryOpEachOperationAndOrderingVariant() {
        long pc = 0xD000;
        Ir64AtomicOp[] operations = Ir64AtomicOp.values();
        for (Ir64AtomicOp operation : operations) {
            for (boolean acquire : new boolean[]{false, true}) {
                for (boolean release : new boolean[]{false, true}) {
                    long address = 0x700 + (pc & 0xFF);
                    Ir64Block block = blockOf(pc,
                            new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, (int) address, 0, true),
                            new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 5, 0, true),
                            new Ir64Op.Store64(1, 5, Ir64MemSize.WORD, false,
                                    Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                            new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 3, 0, true),
                            new Ir64Op.AtomicMemoryOp(0, 2, 5, Ir64MemSize.WORD, operation, acquire, release));
                    harness.assertEquivalent(interpreted, asm, block, pair());
                    pc += 0x10;
                }
            }
        }
    }

    /// `SWP` (alias de `AtomicMemoryOp` com {@link Ir64AtomicOp#SWP}) com `Rt==XZR`: alias `ST<op>`
    /// que descarta o valor antigo — cobre o caminho `rt=31`.
    @Test
    void atomicMemoryOpStAliasDiscardsOldValue() {
        Ir64Block block = blockOf(0xE000,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 5, 0x800, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x77, 0, true),
                new Ir64Op.Store64(1, 5, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x88, 0, true),
                new Ir64Op.AtomicMemoryOp(0, 31, 5, Ir64MemSize.WORD, Ir64AtomicOp.SWP, false, false));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `SETF8`/`SETF16`: larguras `8` e `16` avaliando bytes diferentes do MESMO registrador.
    @Test
    void evaluateIntoFlagsSize8And16() {
        Ir64Block block = blockOf(0xE100,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0xFF80, 0, true),
                new Ir64Op.EvaluateIntoFlags(0, 8),
                new Ir64Op.EvaluateIntoFlags(0, 16));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `RMIF`: máscara PARCIAL (só alguns dos 4 flags atualizados; os demais permanecem
    /// inalterados — Aceite "valores extremos").
    @Test
    void rotateIntoFlagsPartialMaskLeavesRestUnchanged() {
        Ir64Block block = blockOf(0xE200,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 5, 0, true),
                new Ir64Op.Alu64(Ir64AluOp.SUB, 9, 0, 5, true, true, false, false), // NZCV inicial conhecido
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0b1011, 0, true), // candidato N:Z:C:V
                new Ir64Op.RotateIntoFlags(1, 0, 0b0101), // só Z e V atualizados
                new Ir64Op.RotateIntoFlags(1, 2, 0b1111)); // rotação != 0, todos os 4 atualizados
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `CFINV`/`XAFLAG`/`AXFLAG`: as 3 sub-operações de {@link Ir64FlagConversionOp}.
    @Test
    void convertFlagsAllThreeSubOperations() {
        Ir64Block block = blockOf(0xE300,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 3, 0, true),
                new Ir64Op.Alu64(Ir64AluOp.SUB, 9, 0, 3, true, true, false, false),
                new Ir64Op.ConvertFlags(Ir64FlagConversionOp.INVERT_CARRY),
                new Ir64Op.ConvertFlags(Ir64FlagConversionOp.ARM_TO_EXTERNAL),
                new Ir64Op.ConvertFlags(Ir64FlagConversionOp.EXTERNAL_TO_ARM));
        harness.assertEquivalent(interpreted, asm, block, pair());
    }

    /// `XZR`/`SP` (Aceite explícito, classe de bug da B6.14): `dst=31` em ops de escrita descarta
    /// (`XZR`), e `rn=31` nas formas de memória desta task é SEMPRE `SP` — nunca `XZR`.
    @Test
    void xzrAndStackPointerDistinctionAcrossC123Kinds() {
        // dst=31 (XZR) descarta a escrita em ops de registrador puro.
        Ir64Block discardsToXzr = blockOf(0xE400,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 5, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 7, 0, true),
                new Ir64Op.LogicalShiftedRegister(
                        Ir64AluOp.ORR, 31, 0, 1, Ir64LogicalShiftType.LSL, 0, false, true, false),
                new Ir64Op.AluWithCarry(false, 31, 0, 1, true, false),
                new Ir64Op.Extract(31, 0, 1, 4, true),
                new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CLZ, 31, 0, true),
                new Ir64Op.MultiplyAccumulateLong(false, false, 31, 0, 1, 0),
                new Ir64Op.MultiplyHigh(false, 31, 0, 1));
        harness.assertEquivalent(interpreted, asm, discardsToXzr, pair());

        // rn=31 nas formas de memória é SEMPRE SP (nunca XZR) — usa o SP corrente do core.
        Ir64Block rnIsAlwaysSp = blockOf(0xE500,
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 1, 0x42, 0, true),
                new Ir64Op.Store64(1, 31, Ir64MemSize.WORD, false,
                        Ir64AddressingMode.OFFSET, 0, -1, null, 0),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 0x42, 0, true),
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 2, 0x43, 0, true),
                new Ir64Op.CompareAndSwap(0, 2, 31, Ir64MemSize.WORD));
        harness.assertEquivalent(interpreted, asm, rnIsAlwaysSp, () -> {
            Aarch64Core reference = newCore();
            reference.setSp(0x900L);
            Aarch64Core candidate = newCore();
            candidate.setSp(0x900L);
            return new EquivalencePair64(reference, candidate);
        });
    }
}
