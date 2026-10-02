package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações A32/T32 de processamento de dados em registrador geral: aritmética e lógica (`ALU`),
/// multiplicação e divisão, saturação e DSP (ARMv5TE), SIMD paralelo em GPR (ARMv6), campo de bits,
/// `MOVT`, `CRC32` e `CLRM`.
///
/// Sub-interface selada de {@link IrOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface IntegerOp extends IrOp permits IntegerOp.Alu, IntegerOp.Multiply,
        IntegerOp.LongMultiply, IntegerOp.Saturating, IntegerOp.Crc32, IntegerOp.DspMultiply,
        IntegerOp.DspDualMultiply, IntegerOp.DspTopWordMultiply, IntegerOp.ParallelAlu,
        IntegerOp.Sel, IntegerOp.Saturate, IntegerOp.AbsDiffSum, IntegerOp.MoveTop,
        IntegerOp.BitFieldExtract, IntegerOp.BitFieldInsert, IntegerOp.BitReverse, IntegerOp.Divide,
        IntegerOp.ClearMultiple {

    /// Operacao ALU generica.
    record Alu(
            /// Mnemonico ou identificador interno da operacao.
            IrOpCode opcode,
            /// Registrador de destino.
            int dst,
            /// Primeiro registrador de origem.
            int src1,
            /// Valor fixo para usar no lugar de `src1`, ou `-1`.
            int src1ValueOverride,
            /// Segundo operando, que pode ser registrador ou imediato.
            IrOperand src2,
            /// Indica se NZCV deve ser atualizado.
            boolean setFlags,
            /// Condicao necessaria para executar a operacao.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.ALU; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.aluExecutor().execute(core, this); }
    }

    /// Operacao de multiplicacao baixa, com acumulador opcional.
    record Multiply(
            /// Registrador de destino.
            int dst,
            /// Primeiro fator.
            int rm,
            /// Valor fixo para `rm`, ou `-1`.
            int rmValueOverride,
            /// Segundo fator.
            int rs,
            /// Valor fixo para `rs`, ou `-1`.
            int rsValueOverride,
            /// Registrador acumulador, ou `-1` quando não se aplica.
            int rn,
            /// Valor fixo para `rn`, ou `-1`.
            int rnValueOverride,
            /// Indica se o acumulador deve ser somado.
            boolean accumulate,
            /// `true` para `MLS` (ARMv6T2+, B3.1): `Rd = Ra − Rm×Rs` em vez de `Rd = Ra + Rm×Rs`.
            /// Só válido quando {@code accumulate} também é `true`; `MUL`/`MLA` sempre passam `false`.
            boolean subtractFromAccumulator,
            /// Indica se NZ deve ser atualizado.
            boolean setFlags,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeMultiply(core, this); return false; }
    }

    /// Operação de multiplicação longa, com acumulador opcional.
    record LongMultiply(
            /// Registrador que recebe os 32 bits baixos.
            int dstLow,
            /// Registrador que recebe os 32 bits altos.
            int dstHigh,
            /// Primeiro fator.
            int rm,
            /// Valor fixo para `rm`, ou `-1`.
            int rmValueOverride,
            /// Segundo fator.
            int rs,
            /// Valor fixo para `rs`, ou `-1`.
            int rsValueOverride,
            /// Valor fixo para o registrador alto atual em acumulação, ou `-1`.
            int dstHighValueOverride,
            /// Valor fixo para o registrador baixo atual em acumulação, ou `-1`.
            int dstLowValueOverride,
            /// Indica multiplicação com sinal.
            boolean signed,
            /// Indica se o par destino (como valor único de 64 bits) deve ser somado ao produto.
            boolean accumulate,
            /// Acumulador duplo do `UMAAL` (ARMv6): soma RdLo e RdHi ao produto como duas parcelas
            /// de 32 bits sem sinal independentes — não como um par de 64 bits.
            boolean accumulateDouble,
            /// Indica se NZ deve ser atualizado a partir do resultado de 64 bits.
            boolean setFlags,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        /// Construtor de compatibilidade (pré-ARMv6), sem o acumulador duplo do `UMAAL`.
        public LongMultiply(int dstLow, int dstHigh, int rm, int rmValueOverride, int rs,
                int rsValueOverride, int dstHighValueOverride, int dstLowValueOverride,
                boolean signed, boolean accumulate, boolean setFlags, Condition condition) {
            this(dstLow, dstHigh, rm, rmValueOverride, rs, rsValueOverride, dstHighValueOverride,
                    dstLowValueOverride, signed, accumulate, false, setFlags, condition);
        }

        @Override public int kind() { return Kind.LONG_MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeLongMultiply(core, this); return false; }
    }

    /// Aritmética de saturação ARMv5TE (QADD/QSUB/QDADD/QDSUB). `op`: 0=QADD, 1=QSUB,
    /// 2=QDADD, 3=QDSUB. Satura em 32 bits com sinal e ativa o bit Q em overflow.
    record Saturating(
            /// Registrador de destino.
            int dst,
            /// Operando somado/subtraído (Rm).
            int rm,
            /// Operando "n" (Rn), dobrado nas formas QD*.
            int rn,
            /// Seleciona a operação (0..3).
            int op,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.SATURATING; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeSaturating(core, this); return false; }
    }

    /// `CRC32{B,H,W}`/`CRC32C{B,H,W}` (A32+T32, ARMv8-A, B14.3) — espelho de 32 bits de
    /// {@link dev.vitorsilverio.armjitter.ir64.IntegerOp64.Crc32}, sem a forma `X` (dado de 64 bits, que
    /// não existe em AArch32). O algoritmo (laço refletido bit-a-bit) vive em
    /// {@link dev.vitorsilverio.armjitter.advsimd.Crc32Checksum}, compartilhado com o lado A64.
    record Crc32(
            /// Registrador de destino (Rd).
            int dst,
            /// Acumulador de entrada (Rn).
            int rn,
            /// Registrador de dado (Rm).
            int rm,
            /// Largura do dado lido de {@link #rm} em bits: `8`/`16`/`32` (formas `B`/`H`/`W`).
            int dataWidthBits,
            /// `true` para o polinômio Castagnoli (`CRC32C*`), `false` para IEEE 802.3 (`CRC32*`).
            boolean castagnoli,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.CRC32; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeCrc32(core, this); return false; }
    }

    /// Multiplicações DSP ARMv5TE. `op2`: 0=SMLAxy, 1=SMLAW(x=0)/SMULW(x=1), 2=SMLALxy, 3=SMULxy.
    /// `x`/`y` selecionam a metade (baixa/alta) de Rm/Rs (em SMLAW/SMULW, `x` escolhe acumular).
    /// Em SMLAL, `dst` é RdHi e `rn` é RdLo.
    record DspMultiply(
            /// Registrador de destino (RdHi em SMLAL).
            int dst,
            /// Acumulador Rn (RdLo em SMLAL).
            int rn,
            /// Primeiro fator (Rm).
            int rm,
            /// Segundo fator (Rs).
            int rs,
            /// Subtipo (0..3).
            int op2,
            /// Seleção de metade de Rm (ou seletor SMLAW/SMULW quando op2=1).
            int x,
            /// Seleção de metade de Rs.
            int y,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.DSP_MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeDspMultiply(core, this); return false; }
    }

    /// `SMLAD{X}`/`SMLSD{X}`/`SMLALD{X}`/`SMLSLD{X}` (B9.1, ARMv6). Ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#DSP_DUAL_MULTIPLY}.
    record DspDualMultiply(
            /// Registrador de destino (RdHi na forma longa).
            int dst,
            /// Primeiro operando do produto (Rm, bits 11:8).
            int rm,
            /// Segundo operando do produto (Rn, bits 3:0).
            int rn,
            /// Acumulador Ra (RdLo na forma longa); `15` = sem acumulador (`SMUAD`/`SMUSD`).
            int ra,
            /// `true`: produto1 − produto2 (`SMLSD*`); `false`: produto1 + produto2 (`SMLAD*`).
            boolean subtract,
            /// `true`: forma `X` — troca as metades de `rn` antes de multiplicar.
            boolean exchange,
            /// `true`: acumula em 64 bits `Ra:Rd`, sem flag Q (`SMLALD*`/`SMLSLD*`).
            boolean longForm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.DSP_DUAL_MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeDspDualMultiply(core, this); return false; }
    }

    /// `SMMLA{R}`/`SMMLS{R}` (B9.1, ARMv6). Ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#DSP_TOP_WORD_MULTIPLY}.
    record DspTopWordMultiply(
            /// Registrador de destino.
            int dst,
            /// Primeiro operando do produto (Rn, bits 3:0).
            int rn,
            /// Segundo operando do produto (Rm, bits 11:8).
            int rm,
            /// Acumulador Ra; `15` = sem acumulador (`SMMUL`/`SMMLS` sem Ra).
            int ra,
            /// `true`: `SMMLS*` (`Ra<<32 − Rn×Rm`); `false`: `SMMLA*` (`Ra<<32 + Rn×Rm`).
            boolean subtract,
            /// `true`: soma `0x8000_0000` antes de truncar (`SMMLAR`/`SMMLSR`, arredonda).
            boolean round,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.DSP_TOP_WORD_MULTIPLY; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeDspTopWordMultiply(core, this); return false; }
    }

    /// Aritmética paralela ARMv6 em lanes de 8/16 bits (SADD16/UQSUB8/SHASX/...). A operação-base
    /// define as lanes e o cruzamento (ASX/SAX); a variante define sinal, saturação, halving e a
    /// escrita dos flags GE. Formas com PC em qualquer registrador são UNPREDICTABLE no hardware e
    /// não passam pelo decoder.
    record ParallelAlu(
            /// Operação-base (lanes somadas/subtraídas e largura).
            ParallelAluOp op,
            /// Variante de prefixo (S/Q/SH/U/UQ/UH).
            ParallelAluVariant variant,
            /// Registrador de destino.
            int dst,
            /// Primeiro operando (Rn).
            int rn,
            /// Segundo operando (Rm).
            int rm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.PARALLEL_ALU; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeParallelAlu(core, this); return false; }
    }

    /// `SEL` (ARMv6): seleciona cada byte do resultado de Rn ou Rm conforme o flag GE
    /// correspondente do CPSR (GE\[i\]=1 → byte de Rn; 0 → byte de Rm).
    record Sel(
            /// Registrador de destino.
            int dst,
            /// Fonte escolhida quando o GE da lane está setado.
            int rn,
            /// Fonte escolhida quando o GE da lane está limpo.
            int rm,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.SEL; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeSel(core, this); return false; }
    }

    /// Saturação ARMv6 (`SSAT`/`USAT`/`SSAT16`/`USAT16`): satura o operando (possivelmente
    /// shiftado nas formas word) para `saturateBits` bits, com ou sem sinal, por word inteira
    /// ou por halfword. Seta o flag Q sticky quando alguma lane satura.
    record Saturate(
            /// Registrador de destino.
            int dst,
            /// Largura da saturação em bits: `SSAT`/`SSAT16` usam sat_imm+1 (1..32/1..16);
            /// `USAT`/`USAT16` usam sat_imm puro (0..31/0..15).
            int saturateBits,
            /// `true` para faixa sem sinal (`USAT`/`USAT16`: \[0, 2^n−1\]);
            /// `false` para com sinal (`SSAT`/`SSAT16`: \[−2^(n−1), 2^(n−1)−1\]).
            boolean unsignedRange,
            /// `true` para as formas de halfword (`SSAT16`/`USAT16`), que saturam cada
            /// halfword (estendido por sinal) de forma independente.
            boolean halfwords,
            /// Operando de entrada: Rm puro ou Rm shiftado (LSL imm / ASR imm; ASR #32 nas
            /// formas word com imm=0).
            IrOperand operand,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.SATURATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeSaturate(core, this); return false; }
    }

    /// `USAD8`/`USADA8` (ARMv6): soma das diferenças absolutas dos quatro bytes (sem sinal)
    /// de Rm e Rs, com acumulador opcional Rn.
    record AbsDiffSum(
            /// Registrador de destino.
            int dst,
            /// Primeiro operando (Rm).
            int rm,
            /// Segundo operando (Rs).
            int rs,
            /// Acumulador (Rn), ou `-1` na forma sem acumulador (`USAD8`).
            int rn,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.ABS_DIFF_SUM; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeAbsDiffSum(core, this); return false; }
    }

    /// `MOVT` (Thumb-2, B2.2): escreve um imediato de 16 bits na metade ALTA de `dst`,
    /// preservando a metade baixa existente. Nunca escreve flags; sem operando shiftado.
    record MoveTop(
            /// Registrador de destino.
            int dst,
            /// Imediato de 16 bits a escrever em bits[31:16].
            int immediate16,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.MOVE_TOP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeMoveTop(core, this); return false; }
    }

    /// `SBFX`/`UBFX` (ARM/Thumb-2, ARMv6T2+, B3.1): extrai `width` bits de `src` a partir do bit
    /// `lsb` e estende para 32 bits (com ou sem sinal). Nunca toca flags.
    record BitFieldExtract(
            /// Registrador de destino.
            int dst,
            /// Registrador de origem (Rn).
            int src,
            /// Posição do bit menos significativo do campo (0..31).
            int lsb,
            /// Largura do campo em bits (1..32, com `lsb + width <= 32`).
            int width,
            /// `true` para `SBFX` (extensão com sinal); `false` para `UBFX` (com zero).
            boolean signedExtract,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.BIT_FIELD_EXTRACT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeBitFieldExtract(core, this); return false; }
    }

    /// `BFI`/`BFC` (ARM/Thumb-2, ARMv6T2+, B3.1): substitui `width` bits de `dst` a partir do bit
    /// `lsb` pelos bits baixos de `src` (`BFI`) ou por zeros (`BFC`, quando {@code src == -1}),
    /// preservando os demais bits de `dst`. Nunca toca flags.
    record BitFieldInsert(
            /// Registrador de destino (também lido, para preservar os bits fora do campo).
            int dst,
            /// Registrador de origem (Rn), ou `-1` para `BFC` (insere zeros).
            int src,
            /// Posição do bit menos significativo do campo (0..31).
            int lsb,
            /// Largura do campo em bits (1..32, com `lsb + width <= 32`).
            int width,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.BIT_FIELD_INSERT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeBitFieldInsert(core, this); return false; }
    }

    /// `RBIT` (ARM/Thumb-2, ARMv6T2+, B3.1): inverte a ordem dos 32 bits de `src`. Nunca toca flags.
    record BitReverse(
            /// Registrador de destino.
            int dst,
            /// Registrador de origem (Rm).
            int src,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.BIT_REVERSE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeBitReverse(core, this); return false; }
    }

    /// `SDIV`/`UDIV` (ARM/Thumb-2, ARMv7, B3.1): divisão inteira truncada para zero. Divisão por
    /// zero resulta em `0` (sem exceção); `Integer.MIN_VALUE / -1` resulta em `Integer.MIN_VALUE`
    /// (overflow silencioso, igual ao hardware — ARM DDI 0406C A8.8.165). Nunca toca flags.
    record Divide(
            /// Registrador de destino.
            int dst,
            /// Registrador dividendo (Rn).
            int dividend,
            /// Registrador divisor (Rm).
            int divisor,
            /// `true` para `SDIV` (com sinal); `false` para `UDIV` (sem sinal).
            boolean signedDivide,
            /// Condição necessária para executar a operação.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.DIVIDE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.aluExecutor().executeDivide(core, this); return false; }
    }

    /// `CLRM {list}` (perfil M com Security Extension, B16.15): zera `R0`-`R12`/`LR` (bits 0-14 de
    /// `list`) e o `APSR` (bit 15, como `MSR APSR_nzcvqg, #0`).
    record ClearMultiple(
            /// Lista de registradores (16 bits; bit 13 — `SP` — nunca é `1` aqui, recusado no decode).
            int list,
            /// Condição necessária para executar.
            Condition condition) implements IntegerOp {
        @Override public int kind() { return Kind.CLEAR_MULTIPLE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.systemExecutor().executeClrm(core, this); return false; }
    }
}
