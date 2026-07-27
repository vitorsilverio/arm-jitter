package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.InterpretedIr64CodeEmitter;
import dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64NativePolicy;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64SvcHandler;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                new Ir64Op.LoadStorePair(true, 0, 1, 2, true, Ir64AddressingMode.OFFSET, 0),
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
                new Ir64Op.LoadStorePair(false, 1, 2, 0, true, Ir64AddressingMode.OFFSET, 0),
                new Ir64Op.LoadStorePair(true, 3, 4, 0, true, Ir64AddressingMode.OFFSET, 0));
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
}
