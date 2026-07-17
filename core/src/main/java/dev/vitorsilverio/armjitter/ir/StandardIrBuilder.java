package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Builder padrão que converte o subconjunto decodificado atual em IR.
public final class StandardIrBuilder implements IrBuilder {
    /// Eleva uma instrução decodificada para uma ou mais operações IR.
    @Override
    public void lift(DecodedInstruction instruction, IrBlock.Builder block) {
        // BL/BLX Thumb-2 de 32 bits (B2.6): caso especial ANTES do switch principal — emite
        // manualmente o MESMO par Cycle+Fetch que o caminho legado de dois halfwords emitia (uma
        // vez por halfword), não o Cycle+Fetch único da cauda comum abaixo (G4 — ver
        // `liftThumbBl32`). `return` evita duplicar a contagem.
        if (instruction.kind() == InstructionKind.LONG_BRANCH_32) {
            liftThumbBl32(instruction, block);
            return;
        }
        switch (instruction.kind()) {
            case MOV -> liftAlu(IrOpCode.MOV, instruction, block);
            case ADD -> liftAlu(IrOpCode.ADD, instruction, block);
            case ADC -> liftAlu(IrOpCode.ADC, instruction, block);
            case SUB -> liftAlu(IrOpCode.SUB, instruction, block);
            case RSB -> liftAlu(IrOpCode.RSB, instruction, block);
            case SBC -> liftAlu(IrOpCode.SBC, instruction, block);
            case RSC -> liftAlu(IrOpCode.RSC, instruction, block);
            case NEG -> liftAlu(IrOpCode.NEG, instruction, block);
            case AND -> liftAlu(IrOpCode.AND, instruction, block);
            case EOR -> liftAlu(IrOpCode.EOR, instruction, block);
            case ORR -> liftAlu(IrOpCode.ORR, instruction, block);
            case ORN -> liftAlu(IrOpCode.ORN, instruction, block);
            case BIC -> liftAlu(IrOpCode.BIC, instruction, block);
            case MVN -> liftAlu(IrOpCode.MVN, instruction, block);
            // MOVT (Thumb-2, B2.2): `immediate` já carrega o imediato de 16 bits expandido pelo
            // decoder — não é um padrão Alu comum (escreve só a metade alta), por isso é um IrOp
            // próprio em vez de reusar liftAlu.
            case MOVE_TOP -> block.add(new IrOp.MoveTop(
                    instruction.destinationRegister(), instruction.immediate(), instruction.condition()));
            case MRS -> block.add(new IrOp.PsrTransfer(
                    true,
                    instruction.immediate() != 0,
                    instruction.destinationRegister(),
                    -1,
                    0,
                    false,
                    0,
                    instruction.condition()));
            case MSR -> block.add(new IrOp.PsrTransfer(
                    false,
                    ((instruction.immediateOperand() ? instruction.destinationRegister() : instruction.immediate()) & 0x10) != 0,
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.immediateOperand() ? instruction.immediate() : 0,
                    instruction.immediateOperand(),
                    (instruction.immediateOperand() ? instruction.destinationRegister() : instruction.immediate()) & 0xF,
                    instruction.condition()));
            case TST -> liftAlu(IrOpCode.TST, instruction, block);
            case TEQ -> liftAlu(IrOpCode.TEQ, instruction, block);
            case LSL -> liftAlu(IrOpCode.LSL, instruction, block);
            case LSR -> liftAlu(IrOpCode.LSR, instruction, block);
            case ASR -> liftAlu(IrOpCode.ASR, instruction, block);
            case ROR -> liftAlu(IrOpCode.ROR, instruction, block);
            case MUL, MLA -> block.add(new IrOp.Multiply(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    instruction.kind() == InstructionKind.MLA ? instruction.immediate() : -1,
                    instruction.kind() == InstructionKind.MLA
                            ? registerValueOverride(instruction, instruction.immediate())
                            : -1,
                    instruction.kind() == InstructionKind.MLA,
                    instruction.setFlags(),
                    instruction.condition()));
            case UMULL, UMLAL, SMULL, SMLAL -> block.add(new IrOp.LongMultiply(
                    instruction.destinationRegister(),
                    instruction.immediate(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    registerValueOverride(instruction, instruction.immediate()),
                    registerValueOverride(instruction, instruction.destinationRegister()),
                    instruction.kind() == InstructionKind.SMULL || instruction.kind() == InstructionKind.SMLAL,
                    instruction.kind() == InstructionKind.UMLAL || instruction.kind() == InstructionKind.SMLAL,
                    instruction.setFlags(),
                    instruction.condition()));
            case CLZ -> block.add(new IrOp.Alu(
                    IrOpCode.CLZ,
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    new IrOperand.Immediate(0),
                    false,
                    instruction.condition()));
            case EXTEND -> liftExtend(instruction, block);
            case BYTE_REVERSE -> block.add(new IrOp.Alu(
                    switch (instruction.immediate()) {
                        case 0 -> IrOpCode.REV;
                        case 1 -> IrOpCode.REV16;
                        default -> IrOpCode.REVSH;
                    },
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    new IrOperand.Immediate(0),
                    false,
                    instruction.condition()));
            case UMAAL -> block.add(new IrOp.LongMultiply(
                    instruction.destinationRegister(),
                    instruction.immediate(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    registerValueOverride(instruction, instruction.immediate()),
                    registerValueOverride(instruction, instruction.destinationRegister()),
                    false,  // unsigned
                    false,  // sem o acumulador de par 64-bit de UMLAL/SMLAL
                    true,   // acumulador duplo (RdLo + RdHi como parcelas de 32 bits)
                    false,  // UMAAL nunca escreve flags
                    instruction.condition()));
            case PARALLEL_ALU -> {
                int packed = instruction.immediate();
                block.add(new IrOp.ParallelAlu(
                        parallelAluOp((packed >>> 3) & 0x7),
                        parallelAluVariant(packed & 0x7),
                        instruction.destinationRegister(),
                        instruction.sourceRegister(),
                        instruction.secondSourceRegister(),
                        instruction.condition()));
            }
            // `instruction.immediate()` carrega o offset (`imm8×4`) só no `LDREX`/`STREX` word de
            // 32 bits Thumb-2 (B2.7 PR3, `Thumb2LoadStoreDecoder`) — o `ArmDecoder` clássico e as
            // formas B/H/D (ARM ou Thumb-2) sempre passam `immediate=0` aqui.
            case LOAD_EXCLUSIVE -> block.add(new IrOp.LoadExclusive(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.accessSizeBytes(),
                    instruction.condition()));
            case STORE_EXCLUSIVE -> block.add(new IrOp.StoreExclusive(
                    instruction.destinationRegister(),
                    instruction.secondSourceRegister(),
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.accessSizeBytes(),
                    instruction.condition()));
            case CLEAR_EXCLUSIVE -> block.add(new IrOp.ClearExclusive(instruction.condition()));
            case SEL -> block.add(new IrOp.Sel(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    instruction.secondSourceRegister(),
                    instruction.condition()));
            case PKH -> liftPkh(instruction, block);
            case SATURATE -> liftSaturate(instruction, block);
            case USAD8 -> block.add(new IrOp.AbsDiffSum(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    instruction.secondSourceRegister(),
                    instruction.immediate(),
                    instruction.condition()));
            case CMP -> liftAlu(IrOpCode.CMP, instruction, block);
            case CMN -> liftAlu(IrOpCode.CMN, instruction, block);
            case LOAD_LITERAL -> block.add(new IrOp.LoadLiteral(
                    instruction.destinationRegister(),
                    instruction.immediate(),
                    instruction.accessSizeBytes(),
                    instruction.signedAccess(),
                    instruction.condition()));
            case LOAD -> block.add(new IrOp.Load(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    offset(instruction),
                    instruction.accessSizeBytes(),
                    instruction.signedAccess(),
                    instruction.writeback(),
                    instruction.postIndexed(),
                    instruction.condition()));
            case STORE -> block.add(new IrOp.Store(
                    instruction.destinationRegister(),
                    storeSourceValueOverride(instruction),
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    offset(instruction),
                    instruction.accessSizeBytes(),
                    instruction.writeback(),
                    instruction.postIndexed(),
                    instruction.condition()));
            // ARM clássico (ARMv5TE): o encoding só tem um campo Rd, então `second` = `first + 1`
            // sempre (`secondSourceRegister` carrega o registrador de offset Rm nessa forma, não
            // Rt2 — ver `offset()`). Thumb-2 (B2.3) não tem forma de offset por registrador para
            // LDRD/STRD, então `secondSourceRegister` fica livre e é reaproveitado para carregar
            // Rt2 (par arbitrário, independente de Rt — ver `Thumb2LoadStoreDecoder`).
            case DOUBLE_TRANSFER -> block.add(new IrOp.DoubleTransfer(
                    instruction.link(), // carrega LDRD (load) vs STRD (store)
                    instruction.destinationRegister(),
                    instruction.instructionSet() == InstructionSet.THUMB
                            ? instruction.secondSourceRegister()
                            : instruction.destinationRegister() + 1,
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    offset(instruction),
                    instruction.writeback(),
                    instruction.postIndexed(),
                    instruction.condition()));
            case SWAP -> block.add(new IrOp.Swap(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    instruction.accessSizeBytes(),
                    instruction.condition()));
            case LOAD_MULTIPLE -> block.add(new IrOp.MultipleTransfer(
                    true,
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.writeback(),
                    pcStoreValueOverride(instruction),
                    instruction.link(),
                    instruction.blockTransferMode(),
                    instruction.emptyRegisterList(),
                    instruction.condition()));
            case STORE_MULTIPLE -> block.add(new IrOp.MultipleTransfer(
                    false,
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.writeback(),
                    pcStoreValueOverride(instruction),
                    instruction.link(),
                    instruction.blockTransferMode(),
                    instruction.emptyRegisterList(),
                    instruction.condition()));
            case SATURATING -> block.add(new IrOp.Saturating(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    instruction.secondSourceRegister(),
                    instruction.immediate(),
                    instruction.condition()));
            case DSP_MULTIPLY -> {
                int packed = instruction.immediate();
                block.add(new IrOp.DspMultiply(
                        instruction.destinationRegister(),
                        packed & 0xF,            // Rn (RdLo in SMLAL)
                        instruction.sourceRegister(),
                        instruction.secondSourceRegister(),
                        (packed >> 4) & 0x3,     // op2
                        (packed >> 6) & 0x1,     // x
                        (packed >> 7) & 0x1,     // y
                        instruction.condition()));
            }
            case BRANCH -> block.add(new IrOp.Branch(
                    instruction.immediate(),
                    instruction.address() + instructionWidth(instruction),
                    instruction.link(),
                    instruction.condition(),
                    instruction.instructionSet()));
            case BRANCH_EXCHANGE -> block.add(new IrOp.BranchExchange(
                    instruction.sourceRegister(),
                    // BLX-imediato carrega seu alvo (forçado a Thumb) no campo immediate; as formas
                    // registrador (BX/BLX reg) pegam o destino de um registrador.
                    instruction.sourceRegister() < 0
                            ? instruction.immediate()
                            : registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.link(),
                    // BLX a partir de Thumb retorna para Thumb, então o endereço de link mantém o bit 0 setado.
                    instruction.instructionSet() == InstructionSet.THUMB
                            ? ((instruction.address() + instructionWidth(instruction)) | 1)
                            : (instruction.address() + instructionWidth(instruction)),
                    instruction.condition()));
            case LONG_BRANCH_PREFIX -> block.add(new IrOp.ThumbBlPrefix(
                    instruction.immediate(),
                    instruction.address(),
                    instruction.condition()));
            case LONG_BRANCH_SUFFIX -> block.add(new IrOp.ThumbBlSuffix(
                    instruction.immediate(),
                    instruction.address(),
                    (instruction.raw() & 0xF800) == 0xE800, // H=01 -> BLX (switch to ARM)
                    instruction.condition()));
            case PUSH -> block.add(new IrOp.Push(
                    instruction.immediate(),
                    instruction.link(),
                    instruction.condition()));
            case POP -> block.add(new IrOp.Pop(
                    instruction.immediate(),
                    instruction.link(),
                    instruction.condition()));
            case SWI -> block.add(new IrOp.Swi(instruction.immediate(), instruction.condition()));
            case COPROCESSOR -> block.add(new IrOp.Coprocessor(
                    instruction.link(),
                    instruction.immediate() & 0xF,
                    (instruction.immediate() >>> 4) & 0x7,
                    instruction.sourceRegister(),
                    instruction.secondSourceRegister(),
                    (instruction.immediate() >>> 8) & 0x7,
                    instruction.destinationRegister(),
                    instruction.address() + instructionWidth(instruction),
                    instruction.condition()));
            case CPS -> {
                int packed = instruction.immediate();
                block.add(new IrOp.ChangeProcessorState(
                        ((packed >>> 2) & 1) != 0,   // changeMode (M)
                        (packed >>> 6) & 0x1F,        // mode cru
                        ((packed >>> 1) & 1) != 0,    // changeFlags (imod bit 1)
                        (packed & 1) == 0,            // enable: imod bit 0 == 0 -> IE
                        ((packed >>> 3) & 1) != 0,    // changeA
                        ((packed >>> 4) & 1) != 0,    // changeI
                        ((packed >>> 5) & 1) != 0,    // changeF
                        instruction.condition()));
            }
            case SETEND -> block.add(new IrOp.SetEndianness(
                    instruction.immediate() != 0,
                    instruction.condition()));
            case STORE_RETURN_STATE -> block.add(new IrOp.StoreReturnState(
                    instruction.immediate(),
                    instruction.blockTransferMode(),
                    instruction.writeback(),
                    instruction.address() + instructionWidth(instruction),
                    instruction.condition()));
            case RETURN_FROM_EXCEPTION -> block.add(new IrOp.ReturnFromException(
                    instruction.sourceRegister(),
                    instruction.blockTransferMode(),
                    instruction.writeback(),
                    instruction.address() + instructionWidth(instruction),
                    instruction.condition()));
            case WAIT_FOR_INTERRUPT -> block.add(new IrOp.WaitForInterrupt(instruction.condition()));
            // DMB/DSB/ISB (Thumb-2, B2.5): NOP observável — ver javadoc de IrOp.MemoryBarrier.
            case MEMORY_BARRIER -> block.add(new IrOp.MemoryBarrier(instruction.condition()));
            // IT (Thumb-2, B2.4): grava o ITSTATE de entrada já montado pelo decoder. A condição
            // desta op é a da própria instrução IT (normalmente AL; ver IrOp.SetItState).
            case IT -> block.add(new IrOp.SetItState(instruction.immediate(), instruction.condition()));
            // TBB/TBH (Thumb-2, B2.4): ver a decisão D3 em b2.4-thumb2-branches-it.md.
            case TABLE_BRANCH -> block.add(new IrOp.TableBranch(
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    instruction.address() + instructionWidth(instruction),
                    (instruction.immediate() & 1) != 0,
                    instruction.condition()));
            // CBZ/CBNZ (Thumb-1, B2.4): nunca afeta flags.
            case COMPARE_BRANCH_ZERO -> block.add(new IrOp.CompareBranchZero(
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.link(),
                    instruction.condition()));
            case UNIMPLEMENTED -> block.add(new IrOp.Undefined(
                    instruction.address() + instructionWidth(instruction),
                    instruction.condition()));
        }

        block.add(new IrOp.Cycle(1));
        block.add(new IrOp.Fetch(instruction.address(), instructionWidth(instruction)));
        block.endPc(instruction.address() + instructionWidth(instruction));
    }

    /// Eleva `BL`/`BLX` imediato Thumb-2 de 32 bits (B2.6) para o MESMO par de `IrOp`
    /// (`ThumbBlPrefix`+`ThumbBlSuffix`) que o caminho legado de dois halfwords já produzia — zero
    /// semântica nova, só o decode que gerou `instruction` mudou. O offset/exchange são
    /// recomputados diretamente de `instruction.raw()` (os dois halfwords combinados,
    /// `(hi<<16)|lo`) com a MESMA fórmula que `ThumbDecoder#decode` usava para o prefixo/sufixo
    /// isolados (sem o truque de extensão de alcance `I1`/`I2` — limitação pré-existente, não
    /// introduzida aqui). `Cycle`/`Fetch` são emitidos DUAS vezes (um por halfword), reproduzindo
    /// byte a byte o que duas iterações do lifter emitiam para o par legado (G4).
    private void liftThumbBl32(DecodedInstruction instruction, IrBlock.Builder block) {
        int raw32 = instruction.raw();
        int hi = (raw32 >>> 16) & 0xFFFF;
        int lo = raw32 & 0xFFFF;
        int highOffset = signExtend(hi & 0x7FF, 11) << 12;
        int lowOffset = (lo & 0x7FF) << 1;
        boolean exchange = (lo & 0xF800) == 0xE800;
        int prefixAddress = instruction.address();
        int suffixAddress = prefixAddress + 2;

        block.add(new IrOp.ThumbBlPrefix(highOffset, prefixAddress, instruction.condition()));
        block.add(new IrOp.Cycle(1));
        block.add(new IrOp.Fetch(prefixAddress, 2));

        block.add(new IrOp.ThumbBlSuffix(lowOffset, suffixAddress, exchange, instruction.condition()));
        block.add(new IrOp.Cycle(1));
        block.add(new IrOp.Fetch(suffixAddress, 2));

        block.endPc(instruction.address() + instructionWidth(instruction));
    }

    private static int signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }

    /// Converte os bits 7:5 do encoding de aritmética paralela ARMv6 na operação-base.
    private static ParallelAluOp parallelAluOp(int opBits) {
        return switch (opBits) {
            case 0b000 -> ParallelAluOp.ADD16;
            case 0b001 -> ParallelAluOp.ASX;
            case 0b010 -> ParallelAluOp.SAX;
            case 0b011 -> ParallelAluOp.SUB16;
            case 0b100 -> ParallelAluOp.ADD8;
            case 0b111 -> ParallelAluOp.SUB8;
            default -> throw new IllegalStateException("Operação paralela ARMv6 inválida: " + opBits);
        };
    }

    /// Converte os bits 22:20 do encoding de aritmética paralela ARMv6 na variante de prefixo.
    private static ParallelAluVariant parallelAluVariant(int variantBits) {
        return switch (variantBits) {
            case 0b001 -> ParallelAluVariant.SIGNED;
            case 0b010 -> ParallelAluVariant.SIGNED_SATURATING;
            case 0b011 -> ParallelAluVariant.SIGNED_HALVING;
            case 0b101 -> ParallelAluVariant.UNSIGNED;
            case 0b110 -> ParallelAluVariant.UNSIGNED_SATURATING;
            case 0b111 -> ParallelAluVariant.UNSIGNED_HALVING;
            default -> throw new IllegalStateException("Variante paralela ARMv6 inválida: " + variantBits);
        };
    }

    /// Eleva PKHBT/PKHTB (ARMv6): o Rm shiftado vira o operando src2 (PKHBT: LSL imm, com
    /// imm=0 virando {@link IrOperand.Register} puro; PKHTB: ASR imm, com imm=0 significando
    /// ASR #32, como nos shifts imediatos do ARM).
    private void liftPkh(DecodedInstruction instruction, IrBlock.Builder block) {
        int packed = instruction.immediate();
        int shiftImm = packed & 0x1F;
        boolean tb = (packed & (1 << 5)) != 0;
        int rm = instruction.secondSourceRegister();
        int rmOverride = registerValueOverride(instruction, rm);
        IrOperand src2;
        if (tb) {
            src2 = new IrOperand.ShiftedRegister(rm, ShiftType.ASR,
                    shiftImm == 0 ? 32 : shiftImm, -1, rmOverride, -1, false, false);
        } else {
            src2 = shiftImm == 0
                    ? new IrOperand.Register(rm, rmOverride)
                    : new IrOperand.ShiftedRegister(rm, ShiftType.LSL, shiftImm, -1,
                            rmOverride, -1, false, false);
        }
        block.add(new IrOp.Alu(
                tb ? IrOpCode.PKHTB : IrOpCode.PKHBT,
                instruction.destinationRegister(),
                instruction.sourceRegister(),
                registerValueOverride(instruction, instruction.sourceRegister()),
                src2,
                false,
                instruction.condition()));
    }

    /// Eleva SSAT/USAT/SSAT16/USAT16 (ARMv6). A largura de saturação é normalizada aqui:
    /// formas com sinal usam sat_imm+1; sem sinal usam sat_imm puro.
    private void liftSaturate(DecodedInstruction instruction, IrBlock.Builder block) {
        int packed = instruction.immediate();
        int satImm = packed & 0x1F;
        int shiftImm = (packed >>> 5) & 0x1F;
        boolean asr = (packed & (1 << 10)) != 0;
        boolean halfwords = (packed & (1 << 11)) != 0;
        boolean unsigned = (packed & (1 << 12)) != 0;
        int rm = instruction.secondSourceRegister();
        int rmOverride = registerValueOverride(instruction, rm);
        IrOperand operand;
        if (halfwords || (shiftImm == 0 && !asr)) {
            operand = new IrOperand.Register(rm, rmOverride);
        } else {
            operand = new IrOperand.ShiftedRegister(rm, asr ? ShiftType.ASR : ShiftType.LSL,
                    asr && shiftImm == 0 ? 32 : shiftImm, -1, rmOverride, -1, false, false);
        }
        block.add(new IrOp.Saturate(
                instruction.destinationRegister(),
                unsigned ? satImm : satImm + 1,
                unsigned,
                halfwords,
                operand,
                instruction.condition()));
    }

    /// Eleva SXT*/UXT* (ARMv6): o operando rotacionado vira um {@link IrOperand.ShiftedRegister}
    /// com ROR imediato (rotação 0 usa {@link IrOperand.Register} — ROR #0 significaria RRX);
    /// a forma sem acumulador usa {@code src1=-1} com {@code src1ValueOverride=0}, então o
    /// executor soma um acumulador fixo 0 sem ler registrador algum.
    private void liftExtend(DecodedInstruction instruction, IrBlock.Builder block) {
        int packed = instruction.immediate();
        int rotation = (packed & 0x3) * 8;          // bits 1:0 = rotação/8
        int field = (packed >>> 2) & 0x3;           // 00=B16, 10=B, 11=H
        boolean unsigned = (packed & (1 << 4)) != 0;
        IrOpCode opcode = switch (field) {
            case 0b00 -> unsigned ? IrOpCode.UXTB16 : IrOpCode.SXTB16;
            case 0b10 -> unsigned ? IrOpCode.UXTB : IrOpCode.SXTB;
            case 0b11 -> unsigned ? IrOpCode.UXTH : IrOpCode.SXTH;
            default -> throw new IllegalStateException("Campo de extensão ARMv6 inválido: " + field);
        };
        int rm = instruction.secondSourceRegister();
        IrOperand src2 = rotation == 0
                ? new IrOperand.Register(rm, registerValueOverride(instruction, rm))
                : new IrOperand.ShiftedRegister(rm, ShiftType.ROR, rotation, -1,
                        registerValueOverride(instruction, rm), -1, false, false);
        int rn = instruction.sourceRegister();
        block.add(new IrOp.Alu(
                opcode,
                instruction.destinationRegister(),
                rn,
                rn < 0 ? 0 : registerValueOverride(instruction, rn),
                src2,
                false,
                instruction.condition()));
    }

    private void liftAlu(IrOpCode opcode, DecodedInstruction instruction, IrBlock.Builder block) {
        block.add(new IrOp.Alu(
                opcode,
                instruction.destinationRegister(),
                instruction.sourceRegister(),
                aluSourceValueOverride(instruction),
                operand(instruction),
                instruction.setFlags() || instruction.kind() == InstructionKind.CMP || instruction.kind() == InstructionKind.CMN,
                instruction.condition()));
    }

    private IrOperand operand(DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            if (instruction.instructionSet() == InstructionSet.ARM) {
                int rotate = ((instruction.raw() >>> 8) & 0xF) * 2;
                return new IrOperand.Immediate(instruction.immediate(), rotate != 0, instruction.immediate() < 0);
            }
            if (instruction.instructionSet() == InstructionSet.THUMB
                    && instruction.immediate() == 0
                    && (instruction.kind() == InstructionKind.LSR || instruction.kind() == InstructionKind.ASR)) {
                return new IrOperand.Immediate(32);
            }
            if (isThumb32(instruction) && isThumb2ModifiedImmediate(instruction)) {
                // ThumbExpandImm_C (Thumb-2 B2.2): carry-out só é conhecido (e igual ao bit 31 do
                // valor já expandido pelo decoder — mesmo truque do rotate-imm ARM acima) quando
                // i:imm3[2] != 0b00, i.e. quando a instrução ROTACIONA em vez de replicar bytes.
                // i = bit26 (hi[10]); imm3[2] = bit14 (topo do campo lo[14:12]).
                boolean rotates = ((instruction.raw() >>> 26) & 1) != 0 || ((instruction.raw() >>> 14) & 1) != 0;
                return new IrOperand.Immediate(instruction.immediate(), rotates, instruction.immediate() < 0);
            }
            return new IrOperand.Immediate(instruction.immediate());
        }
        if (isThumb32(instruction) && !isRegisterControlledShiftKind(instruction.kind())) {
            // Data-processing (register) Thumb-2, forma com shift imediato: shty = lo[5:4],
            // imm5 = lo[14:12]:lo[7:6] (imm3 alto + imm2 baixo, mesmo split de ARM classico).
            int shiftBits = (instruction.raw() >>> 4) & 0x3;
            int amount = (((instruction.raw() >>> 12) & 0x7) << 2) | ((instruction.raw() >>> 6) & 0x3);
            return new IrOperand.ShiftedRegister(
                    instruction.secondSourceRegister(),
                    shiftType(shiftBits),
                    normalizedShiftAmount(shiftBits, amount),
                    -1,
                    -1,
                    -1,
                    amount == 0 && shiftBits == 3,
                    false);
        }
        if (instruction.instructionSet() == InstructionSet.ARM) {
            if ((instruction.raw() & (1 << 4)) != 0) {
                int shiftBits = (instruction.raw() >>> 5) & 0x3;
                int amountRegister = (instruction.raw() >>> 8) & 0xF;
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        shiftType(shiftBits),
                        0,
                        amountRegister,
                        armRegisterShiftPcValue(instruction, instruction.secondSourceRegister()),
                        armRegisterShiftPcValue(instruction, amountRegister),
                        false,
                        false);
            }
            int amount = (instruction.raw() >>> 7) & 0x1F;
            int shiftBits = (instruction.raw() >>> 5) & 0x3;
            if (amount != 0 || shiftBits != 0) {
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        shiftType(shiftBits),
                        normalizedShiftAmount(shiftBits, amount),
                        -1,
                        registerValueOverride(instruction, instruction.secondSourceRegister()),
                        -1,
                        amount == 0 && shiftBits == 3,
                        false);
            }
        }
        return new IrOperand.Register(
                instruction.secondSourceRegister(),
                registerValueOverride(instruction, instruction.secondSourceRegister()));
    }

    private ShiftType shiftType(int bits) {
        return switch (bits) {
            case 0 -> ShiftType.LSL;
            case 1 -> ShiftType.LSR;
            case 2 -> ShiftType.ASR;
            case 3 -> ShiftType.ROR;
            default -> throw new IllegalArgumentException("Invalid ARM shift type: " + bits);
        };
    }

    private int normalizedShiftAmount(int shiftBits, int amount) {
        if (amount == 0 && (shiftBits == 1 || shiftBits == 2)) {
            return 32;
        }
        return amount;
    }

    private IrOperand offset(DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return new IrOperand.Immediate(instruction.immediate());
        }
        if (instruction.instructionSet() == InstructionSet.ARM) {
            boolean negated = instruction.immediate() < 0;
            if (isArmSingleDataTransfer(instruction.raw())) {
                int amount = (instruction.raw() >>> 7) & 0x1F;
                int shiftBits = (instruction.raw() >>> 5) & 0x3;
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        shiftType(shiftBits),
                        normalizedShiftAmount(shiftBits, amount),
                        -1,
                        registerValueOverride(instruction, instruction.secondSourceRegister()),
                        -1,
                        amount == 0 && shiftBits == 3,
                        negated);
            }
            return new IrOperand.ShiftedRegister(
                    instruction.secondSourceRegister(),
                    ShiftType.LSL,
                    0,
                    -1,
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    -1,
                    false,
                    negated);
        }
        // Thumb-2 (B2.3) forma T2 registrador `[Rn, Rm, LSL #imm2]`: distinta do offset por
        // registrador Thumb-1 (`LDR Rd,[Rb,Ro]`, sem shift) só nas instruções de 32 bits — o bit
        // mais alto de `raw` está sempre ligado num candidato Thumb-2 genuíno (nunca num halfword
        // isolado), mesmo truque de `StandardIrBlockLifter#instructionWidth` desde B2.2.
        if (instruction.raw() < 0) {
            int imm2 = (instruction.raw() >>> THUMB2_REGISTER_OFFSET_SHIFT_SHIFT) & THUMB2_REGISTER_OFFSET_SHIFT_MASK;
            if (imm2 != 0) {
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        ShiftType.LSL,
                        imm2,
                        -1,
                        registerValueOverride(instruction, instruction.secondSourceRegister()),
                        -1,
                        false,
                        false);
            }
        }
        return new IrOperand.Register(
                instruction.secondSourceRegister(),
                registerValueOverride(instruction, instruction.secondSourceRegister()));
    }

    /// `imm2` (bits 5:4) do offset de registrador Thumb-2 T2 (`[Rn, Rm, LSL #imm2]`) — ARM DDI
    /// 0406C A5.3.1 `@ldst_rr`.
    private static final int THUMB2_REGISTER_OFFSET_SHIFT_SHIFT = 4;
    private static final int THUMB2_REGISTER_OFFSET_SHIFT_MASK = 0x3;

    private int baseValueOverride(DecodedInstruction instruction) {
        if (instruction.sourceRegister() != 15) {
            return -1;
        }
        return registerValueOverride(instruction, 15);
    }

    private int registerValueOverride(DecodedInstruction instruction, int register) {
        if (register != 15) {
            return -1;
        }
        return switch (instruction.instructionSet()) {
            case ARM -> instruction.address() + 8;
            case THUMB -> (instruction.address() + 4);
        };
    }

    /// Override de PC do operando-1 (Rn) de uma instrução data-processing. O ARM7TDMI lê R15
    /// como PC+12 quando a instrução usa um shift especificado por registrador (o ciclo extra de
    /// shift avança o prefetch), senão como o PC+8 usual.
    private int aluSourceValueOverride(DecodedInstruction instruction) {
        if (instruction.sourceRegister() == 15 && usesRegisterSpecifiedShift(instruction)) {
            return instruction.address() + 12;
        }
        return registerValueOverride(instruction, instruction.sourceRegister());
    }

    /// Valor de PC para Rm/Rs de um operando com shift especificado por registrador: R15 lê como PC+12.
    private int armRegisterShiftPcValue(DecodedInstruction instruction, int register) {
        return register == 15 ? instruction.address() + 12 : -1;
    }

    private boolean usesRegisterSpecifiedShift(DecodedInstruction instruction) {
        return instruction.instructionSet() == InstructionSet.ARM
                && !instruction.immediateOperand()
                && (instruction.raw() & (1 << 4)) != 0;
    }

    /// Override de PC do dado gravado por um `STR` isolado. O ARM7TDMI grava R15 como PC+12 (uma
    /// largura de instrução além do valor de leitura PC+8); stores isolados THUMB não podem ter PC como alvo.
    private int storeSourceValueOverride(DecodedInstruction instruction) {
        if (instruction.destinationRegister() == 15
                && instruction.instructionSet() == InstructionSet.ARM) {
            return instruction.address() + 12;
        }
        return registerValueOverride(instruction, instruction.destinationRegister());
    }

    private int pcStoreValueOverride(DecodedInstruction instruction) {
        // Penalidade de PC do block-store do ARM7TDMI: um PC gravado fica uma largura de instrução
        // extra à frente do valor de leitura (ARM lê addr+8/grava addr+12; THUMB lê
        // addr+4/grava addr+6). Só relevante para o quirk de STM com rlist vazia em THUMB.
        return switch (instruction.instructionSet()) {
            case ARM -> instruction.address() + 12;
            case THUMB -> instruction.address() + 6;
        };
    }

    private boolean isArmSingleDataTransfer(int raw) {
        return (raw & 0x0C00_0000) == 0x0400_0000;
    }

    /// Detecta um candidato Thumb-2 de 32 bits (B2.1+) — ver o javadoc de {@link #instructionWidth}.
    private boolean isThumb32(DecodedInstruction instruction) {
        return instruction.instructionSet() == InstructionSet.THUMB && instruction.raw() < 0;
    }

    /// B2.7 (PR1): `LSL.W`/`LSR.W`/`ASR.W`/`ROR.W Rd,Rn,Rm` (`Thumb2RegisterDataProcessingDecoder`,
    /// espaço `0xFA`) usa os MESMOS `InstructionKind` que o shift por registrador Thumb-1 (formato 4
    /// ALU ops de `ThumbDecoder`) — `secondSourceRegister` carrega o registrador de quantidade, que
    /// deve virar um {@link IrOperand.Register} simples (o executor de `IrOpCode.LSL/LSR/ASR/ROR`
    /// já trata `src2` como quantidade de deslocamento, não como operando pré-deslocado). Sem este
    /// desvio, a instrução (que É um candidato Thumb-2 de 32 bits, `raw()&lt;0`) cairia por engano no
    /// ramo de "forma com shift imediato" logo abaixo, que assume o layout de bits do
    /// `Thumb2DataProcessingDecoder` (B2.2) — um decoder DIFERENTE que nunca produz estes kinds.
    private static boolean isRegisterControlledShiftKind(InstructionKind kind) {
        return kind == InstructionKind.LSL || kind == InstructionKind.LSR
                || kind == InstructionKind.ASR || kind == InstructionKind.ROR;
    }

    /// Distingue "Data-processing (modified immediate)" de "Data processing (plain binary
    /// immediate)"/`MOVW`/`MOVT` dentro do espaço Thumb-2 top5=0b11110: hi[9] (bit global 25) é
    /// `0` só no primeiro grupo (ARM DDI 0406 A5.3.3 vs A5.3.4).
    private boolean isThumb2ModifiedImmediate(DecodedInstruction instruction) {
        return ((instruction.raw() >>> 25) & 1) == 0;
    }

    /// Largura em bytes da instrução decodificada — ver o javadoc do mesmo método em
    /// {@link StandardIrBlockLifter} para a regra completa (inclusive o candidato Thumb-2 de 32
    /// bits detectado por {@code raw() < 0}). Usado para `IrOp.Fetch`, `endPc` e o valor de
    /// retorno de `BL`/`BLX`.
    private int instructionWidth(DecodedInstruction instruction) {
        if (instruction.instructionSet() == InstructionSet.THUMB) {
            return instruction.raw() < 0 ? 4 : 2;
        }
        return 4;
    }
}
