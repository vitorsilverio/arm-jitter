package dev.vitorsilverio.armjitter.ir64;

/// Operação de representação intermediária para AArch64 (A64) — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.ir.IrOp}, mas um frontend IRMÃO e independente (Opção B da
/// RFC-IR-64BIT.md, aprovada 2026-07-10): registradores de 64 bits (`X0`-`X30` + `SP`/`XZR`),
/// operandos `long`, PACOTE NOVO. NENHUM tipo deste pacote é usado pelo pipeline ARMv4T/v5TE/v6/
/// v7 existente, e vice-versa (G2/G3) — os dois mundos não compartilham op, executor, nem core.
///
/// Diferença deliberada de {@link dev.vitorsilverio.armjitter.ir.IrOp}: aqui NÃO há um método
/// `condition()` universal. A64 não tem predicação geral (RFC §5.4) — só {@link Branch64} (via
/// `B.cond`) carrega uma condição de fato; o resto da ISA A64 executa sempre, e fingir uma
/// condição `AL` universal em todo op seria copiar um conceito do frontend 32-bit que não existe
/// aqui.
///
/// Convenção de registrador (RFC §"XZR/SP", decisão da task B6.1): todo campo `int` de índice de
/// registrador usa `0`-`30` para `X0`-`X30` e `31` para o registrador especial de número 31 do
/// encoding — que é `XZR` (zero register, lê `0`/descarta escritas) OU `SP` (stack pointer),
/// dependendo da instrução. O DECODER nunca resolve essa ambiguidade: ele só copia o campo de 5
/// bits do encoding para o índice. Quando o manual distingue as dumas formas (`Rd|SP` vs `Rd`),
/// o record carrega um `boolean` companheiro (`dstIsStackPointer`/`src1IsStackPointer` em
/// {@link Alu64}) setado pelo DECODER a partir do próprio encoding — nunca inferido depois. A
/// resolução final (ler `0`, descartar escrita, ou redirecionar para `SP`) acontece só no
/// EXECUTOR ({@code Ir64BlockExecutor}), nunca no decoder.
public sealed interface Ir64Op permits
        Ir64Op.Alu64, Ir64Op.MoveWide, Ir64Op.PcRelative, Ir64Op.Branch64, Ir64Op.CompareBranch64,
        Ir64Op.Svc, Ir64Op.Cycle, Ir64Op.Fetch, Ir64Op.Load64, Ir64Op.Store64,
        Ir64Op.LoadStorePair, Ir64Op.LoadLiteral64, Ir64Op.AluShiftedRegister,
        Ir64Op.AluExtendedRegister, Ir64Op.ConditionalSelect, Ir64Op.Bitfield,
        Ir64Op.MultiplyAccumulate, Ir64Op.Divide, Ir64Op.LoadExclusive, Ir64Op.StoreExclusive,
        Ir64Op.SystemRegister, Ir64Op.SystemInstruction, Ir64Op.ExceptionReturn,
        Ir64Op.Fp64Alu, Ir64Op.Fp64MoveImmediate, Ir64Op.Fp64Compare, Ir64Op.Fp64Convert,
        Ir64Op.PrivilegedCall, Ir64Op.ConditionalCompare, Ir64Op.LogicalShiftedRegister,
        Ir64Op.ShiftVariable, Ir64Op.LoadExclusivePair, Ir64Op.StoreExclusivePair,
        Ir64Op.CompareAndSwap, Ir64Op.CompareAndSwapPair, Ir64Op.AluWithCarry, Ir64Op.Extract,
        Ir64Op.DataProcessing1Source, Ir64Op.MultiplyAccumulateLong, Ir64Op.MultiplyHigh,
        Ir64Op.EvaluateIntoFlags, Ir64Op.RotateIntoFlags, Ir64Op.ConvertFlags,
        Ir64Op.InterruptMask, Ir64Op.Breakpoint, Ir64Op.UndefinedInstructionTrap,
        Ir64Op.AddressTranslate {

    /// Discriminador de tipo para dispatch O(1) no interpretador — mesma técnica de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp#kind()} (constantes contíguas a partir de `0`
    /// em {@link Kind}, permitindo `tableswitch` no executor).
    int kind();

    /// Constantes de {@link Ir64Op#kind()} — uma por subtipo selado, contíguas a partir de `0`.
    final class Kind {
        private Kind() {
        }

        public static final int ALU64 = 0;
        public static final int MOVE_WIDE = 1;
        public static final int PC_RELATIVE = 2;
        public static final int BRANCH64 = 3;
        public static final int COMPARE_BRANCH64 = 4;
        public static final int SVC = 5;
        public static final int CYCLE = 6;
        public static final int FETCH = 7;
        public static final int LOAD64 = 8;
        public static final int STORE64 = 9;
        public static final int LOAD_STORE_PAIR = 10;
        public static final int LOAD_LITERAL64 = 11;
        public static final int ALU_SHIFTED_REGISTER = 12;
        public static final int ALU_EXTENDED_REGISTER = 13;
        public static final int CONDITIONAL_SELECT = 14;
        public static final int BITFIELD = 15;
        public static final int MULTIPLY_ACCUMULATE = 16;
        public static final int DIVIDE = 17;
        public static final int LOAD_EXCLUSIVE = 18;
        public static final int STORE_EXCLUSIVE = 19;
        public static final int SYSTEM_REGISTER = 20;
        public static final int SYSTEM_INSTRUCTION = 21;
        public static final int EXCEPTION_RETURN = 22;
        /// B6.5.2: contíguo a partir de `23` — os `Kind`s `0`-`22` já estavam ocupados quando esta
        /// task foi escrita (a spec original previa `20`, desatualizada pelas tasks B6.6.1-B6.6.4
        /// intermediárias, que já tinham reivindicado `20`-`22`).
        public static final int FP64_ALU = 23;
        public static final int FP64_MOVE_IMMEDIATE = 24;
        public static final int FP64_COMPARE = 25;
        public static final int FP64_CONVERT = 26;
        /// B6.6.7: `HVC`/`SMC` — ver {@link PrivilegedCall}.
        public static final int PRIVILEGED_CALL = 27;
        /// B6.8: `CCMP`/`CCMN` — ver {@link ConditionalCompare}.
        public static final int CONDITIONAL_COMPARE = 28;
        /// B6.9: `AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` (registrador deslocado) — ver
        /// {@link LogicalShiftedRegister}.
        public static final int LOGICAL_SHIFTED_REGISTER = 29;
        /// B6.11: `LSLV`/`LSRV`/`ASRV`/`RORV` (deslocamento variável, quantidade em `Rm`) — ver
        /// {@link ShiftVariable}.
        public static final int SHIFT_VARIABLE = 30;
        /// B8.1: `LDXP`/`LDAXP` — ver {@link LoadExclusivePair}.
        public static final int LOAD_EXCLUSIVE_PAIR = 31;
        /// B8.1: `STXP`/`STLXP` — ver {@link StoreExclusivePair}.
        public static final int STORE_EXCLUSIVE_PAIR = 32;
        /// B8.1: `CAS`/`CASA`/`CASL`/`CASAL` — ver {@link CompareAndSwap}.
        public static final int COMPARE_AND_SWAP = 33;
        /// B8.1: `CASP`/`CASPA`/`CASPL`/`CASPAL` — ver {@link CompareAndSwapPair}.
        public static final int COMPARE_AND_SWAP_PAIR = 34;
        /// B8.2: `ADC`/`ADCS`/`SBC`/`SBCS` — ver {@link AluWithCarry}.
        public static final int ALU_WITH_CARRY = 35;
        /// B8.2: `EXTR` — ver {@link Extract}.
        public static final int EXTRACT = 36;
        /// B8.2: `RBIT`/`REV16`/`CLZ`/`CLS`/`CNT` — ver {@link DataProcessing1Source}.
        public static final int DATA_PROCESSING_1_SOURCE = 37;
        /// B8.2: `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` — ver {@link MultiplyAccumulateLong}.
        public static final int MULTIPLY_ACCUMULATE_LONG = 38;
        /// B8.2: `SMULH`/`UMULH` — ver {@link MultiplyHigh}.
        public static final int MULTIPLY_HIGH = 39;
        /// B8.2: `SETF8`/`SETF16` — ver {@link EvaluateIntoFlags}.
        public static final int EVALUATE_INTO_FLAGS = 40;
        /// B8.2: `RMIF` — ver {@link RotateIntoFlags}.
        public static final int ROTATE_INTO_FLAGS = 41;
        /// B8.2: `CFINV`/`XAFLAG`/`AXFLAG` — ver {@link ConvertFlags}.
        public static final int CONVERT_FLAGS = 42;
        /// B8.3: `MSR (immediate) DAIFSet`/`DAIFClr` — ver {@link InterruptMask}.
        public static final int INTERRUPT_MASK = 43;
        /// B8.3: `BRK` — ver {@link Breakpoint}.
        public static final int BREAKPOINT = 44;
        /// B8.3: `HLT` — ver {@link UndefinedInstructionTrap}.
        public static final int UNDEFINED_INSTRUCTION_TRAP = 45;
        /// B10.6: `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` — ver {@link AddressTranslate}.
        public static final int ADDRESS_TRANSLATE = 46;
    }

    /// `ADD`/`SUB`/`AND`/`ORR`/`EOR` na forma imediata (`ARM DDI 0487 C6.2.4/C6.2.339/...`). Só
    /// `ADD`/`SUB` são produzidas pelo decoder desta task ({@link Ir64AluOp}); `AND`/`ORR`/`EOR`
    /// existem no formato para quando B6.3 trouxer o decode de "logical immediate".
    record Alu64(
            /// Operação a executar.
            Ir64AluOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR` ou `SP` conforme
            /// {@link #dstIsStackPointer}).
            int dst,
            /// Registrador de origem (índice `0`-`31`; `31` é `XZR` ou `SP` conforme
            /// {@link #src1IsStackPointer}).
            int src1,
            /// Imediato já normalizado pelo decoder (`imm12` com o shift de `#0` ou `#12` já
            /// aplicado — nunca o campo cru do encoding).
            long immediate,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado
            /// sempre zero-estendido para os 64 bits altos do registrador de destino — ver
            /// Armadilhas do épico).
            boolean wide,
            /// Indica se `NZCV` deve ser atualizado (`ADDS`/`SUBS`/`ANDS` vs formas sem `S`).
            boolean setFlags,
            /// `true` quando o índice `31` em {@link #dst} significa `SP` (não `XZR`) — decidido
            /// pelo PRÓPRIO ENCODING (`ADD`/`SUB` sem `S` permitem `Rd|SP`; COM `S` o destino é
            /// sempre `Rd` normal, nunca `SP` — `ARM DDI 0487 C6.2.4`).
            boolean dstIsStackPointer,
            /// `true` quando o índice `31` em {@link #src1} significa `SP` (não `XZR`) — nas
            /// formas `ADD`/`SUB (immediate)` isto vale SEMPRE (`Rn|SP` independente de `S`).
            boolean src1IsStackPointer) implements Ir64Op {
        @Override public int kind() { return Kind.ALU64; }
    }

    /// `MOVZ`/`MOVN`/`MOVK` (`ARM DDI 0487 C6.2.203/205/206`): grava (ou compõe, no caso de
    /// `MOVK`) um imediato de 16 bits deslocado por `shift` no registrador de destino.
    record MoveWide(
            /// Sub-operação (`MOVZ`/`MOVN`/`MOVK`).
            Ir64MoveWideOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR` aqui — `MOVZ`/
            /// `MOVN`/`MOVK` não têm forma `SP`, então uma escrita em `31` é sempre descartada
            /// pelo executor, nunca redirecionada — ver o vetor de teste "XZR como destino").
            int dst,
            /// Imediato de 16 bits (`0`-`0xFFFF`), sem deslocamento aplicado.
            int immediate16,
            /// Deslocamento em bits do imediato: `0`, `16`, `32` ou `48`. As duas últimas formas
            /// só existem quando {@link #wide} (o campo `hw` de 2 bits do encoding é restrito a
            /// `0`/`1` quando `sf=0`).
            int shift,
            /// `true` para operação de 64 bits (`X`), `false` para 32 bits (`W`).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.MOVE_WIDE; }
    }

    /// `ADR`/`ADRP` (`ARM DDI 0487 C6.2.10/11`): calcula um endereço relativo ao PC da própria
    /// instrução e grava em `dst` (sempre um registrador `X` completo — não existe forma `W` nem
    /// forma `SP` para `ADR`/`ADRP`).
    record PcRelative(
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR`, escrita descartada).
            int dst,
            /// Endereço da própria instrução `ADR`/`ADRP` (o "PC" usado como base do cálculo —
            /// SEM o viés `+8` do PC arquitetural de 32 bits, que não existe em A64: o PC de uma
            /// instrução A64 É o próprio endereço dela).
            long instructionAddress,
            /// Deslocamento já resolvido pelo decoder: para `ADR`, o imediato de 21 bits com
            /// sinal (`immhi:immlo`) em bytes; para `ADRP`, o MESMO imediato de 21 bits com
            /// sinal já multiplicado por `4096` (a unidade é página, não byte). O alinhamento de
            /// 4 KiB da base (`ADRP`) é aplicado pelo EXECUTOR, não aqui — ver {@link #page}.
            long immediate,
            /// `true` para `ADRP` (a base é `instructionAddress` alinhado a 4 KiB antes de somar
            /// {@link #immediate}); `false` para `ADR` (soma direta, sem alinhamento).
            boolean page) implements Ir64Op {
        @Override public int kind() { return Kind.PC_RELATIVE; }
    }

    /// `B`/`BL`/`B.cond` (destino imediato) e `BR`/`BLR`/`RET` (destino em registrador) — ver
    /// {@link Ir64BranchForm}. É o único `Ir64Op` com uma condição de fato (`B.cond`); as demais
    /// formas sempre carregam {@link Ir64Condition#AL}.
    record Branch64(
            /// Forma do desvio (destino imediato ou em registrador).
            Ir64BranchForm form,
            /// Endereço da própria instrução de desvio — usado para calcular o link register
            /// (`instructionAddress + 4`) quando {@link #link}; para `B`/`B.cond` sem link, não
            /// tem efeito observável.
            long instructionAddress,
            /// Destino absoluto já resolvido pelo decoder, válido só quando
            /// {@link #form} é {@link Ir64BranchForm#IMMEDIATE}.
            long target,
            /// Registrador que contém o destino, válido só quando {@link #form} é
            /// {@link Ir64BranchForm#REGISTER}; `-1` na forma imediata. Nunca é `SP` — sempre um
            /// registrador `X` normal (`31` é `XZR`, um `BR`/`BLR`/`RET xzr` salta para `0`).
            int registerOperand,
            /// `true` para `BL`/`BLR` (grava `instructionAddress + 4` em `X30`).
            boolean link,
            /// Condição necessária para tomar o desvio (`AL` em todas as formas exceto
            /// `B.cond`).
            Ir64Condition condition) implements Ir64Op {
        @Override public int kind() { return Kind.BRANCH64; }
    }

    /// `CBZ`/`CBNZ`/`TBZ`/`TBNZ` (`ARM DDI 0487 C6.2.36/38/369/370`) — ver
    /// {@link Ir64CompareBranchForm}. Sempre incondicional (não existe `CBZ.cond`); por isso não
    /// carrega {@link Ir64Condition}, ao contrário de {@link Branch64}.
    record CompareBranch64(
            /// Forma do teste (registrador inteiro contra zero, ou um único bit).
            Ir64CompareBranchForm form,
            /// Registrador testado (índice `0`-`31`; `31` é `XZR` — `CBZ xzr` é sempre tomado,
            /// `CBNZ xzr` nunca é).
            int rn,
            /// Largura do registrador testado (`CBZ`/`CBNZ`: `true`=`X`, `false`=`W` — só os 32
            /// bits baixos são comparados contra zero). Irrelevante para
            /// {@link Ir64CompareBranchForm#TBZ_TBNZ} (o bit testado sempre vem do registrador
            /// `X` completo — ver {@link #bitPosition}).
            boolean wide,
            /// Posição do bit testado (`0`-`63`), só para {@link Ir64CompareBranchForm#TBZ_TBNZ};
            /// `-1` para {@link Ir64CompareBranchForm#CBZ_CBNZ}.
            int bitPosition,
            /// `true` para `CBNZ`/`TBNZ` (desvia quando a condição testada é não-zero); `false`
            /// para `CBZ`/`TBZ` (desvia quando é zero).
            boolean branchIfNonZero,
            /// Destino absoluto já resolvido pelo decoder.
            long target) implements Ir64Op {
        @Override public int kind() { return Kind.COMPARE_BRANCH64; }
    }

    /// `SVC` (`ARM DDI 0487 C6.2.311`): chamada de sistema delegada ao dispatcher do host — mesmo
    /// papel de {@link dev.vitorsilverio.armjitter.ir.IrOp.Swi} no IR de 32 bits, mas sem campo
    /// de condição (A64 não tem `SVC` condicional).
    record Svc(
            /// Imediato de 16 bits da instrução `SVC`.
            int immediate) implements Ir64Op {
        @Override public int kind() { return Kind.SVC; }
    }

    /// Contagem de ciclos agregada ao passo/bloco — mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.Cycle} (G4: nunca ganha guard condicional,
    /// já que A64 nem tem predicação geral para guardar).
    record Cycle(
            /// Quantidade de ciclos somada.
            int count) implements Ir64Op {
        @Override public int kind() { return Kind.CYCLE; }
    }

    /// Custo de busca da instrução original na memória do dispositivo — mesma disciplina de
    /// {@link dev.vitorsilverio.armjitter.ir.IrOp.Fetch} (G4).
    record Fetch(
            /// Endereço da instrução buscada.
            long address,
            /// Tamanho da instrução em bytes (sempre `4` em A64 — não há forma "curta" como
            /// Thumb; o campo existe para espelhar {@code IrOp.Fetch} e por uniformidade com o
            /// resto do executor).
            int sizeBytes) implements Ir64Op {
        @Override public int kind() { return Kind.FETCH; }
    }

    /// `LDR`/`LDRB`/`LDRH`/`LDRSB`/`LDRSH`/`LDRSW` de registrador geral (`ARM DDI 0487 C4.1.3`,
    /// classe `x1x0`, `V=0`) — cobre as 4 formas de endereçamento de
    /// {@link Ir64AddressingMode} exceto {@link Ir64AddressingMode#REGISTER_OFFSET}, que usa
    /// {@link #rm}/{@link #extendType}/{@link #shiftAmount} em vez de {@link #immediate}. `Rn` é
    /// SEMPRE `Rn|SP` (nunca `XZR`, indistintamente do encoding — convenção arquitetural do A64
    /// para o registrador BASE de qualquer load/store, resolvida direto no EXECUTOR); `Rt` segue
    /// a convenção normal (`31` = `XZR`, descarta a escrita).
    record Load64(
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`, nunca `XZR` — ver acima).
            int rn,
            /// Tamanho da transferência de memória (pode ser menor que o registrador de destino
            /// nas formas com sinal).
            Ir64MemSize size,
            /// `true` para `LDRSB`/`LDRSH`/`LDRSW` (estende o sinal do valor lido); `false` para
            /// `LDR`/`LDRB`/`LDRH` (zero-estende).
            boolean signExtend,
            /// Largura do registrador de destino: `true`=`X`, `false`=`W` (irrelevante para o
            /// zero-extend — escrever em `W` já zera os 32 bits altos por conta própria).
            boolean wide,
            /// Modo de endereçamento.
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes, válido para {@link Ir64AddressingMode#OFFSET}/
            /// {@link Ir64AddressingMode#PRE_INDEX}/{@link Ir64AddressingMode#POST_INDEX}
            /// (já normalizado pelo decoder — escalado pelo tamanho na forma "unsigned offset",
            /// cru nas formas `LDUR`/pre/post-index); `0` em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}.
            long immediate,
            /// Registrador de deslocamento (índice `0`-`31`; `31`=`XZR`), válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `-1` nas demais formas.
            int rm,
            /// Extensão aplicada a {@link #rm}, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `null` nas demais formas.
            Ir64ExtendType extendType,
            /// Quantidade de deslocamento aplicada após a extensão (`0` ou `size.log2Bytes()`),
            /// válido só em {@link Ir64AddressingMode#REGISTER_OFFSET}.
            int shiftAmount) implements Ir64Op {
        @Override public int kind() { return Kind.LOAD64; }
    }

    /// `STR`/`STRB`/`STRH` de registrador geral — mesmo formato de {@link Load64} sem
    /// {@link Load64#signExtend} (armazenamento nunca estende sinal).
    record Store64(
            /// Registrador de origem (índice `0`-`31`; `31` é `XZR`, escreve `0`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP` — ver {@link Load64#rn}).
            int rn,
            /// Tamanho da transferência de memória.
            Ir64MemSize size,
            /// Largura do registrador de origem: `true`=`X`, `false`=`W`.
            boolean wide,
            /// Modo de endereçamento.
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes — ver {@link Load64#immediate}.
            long immediate,
            /// Registrador de deslocamento, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `-1` nas demais formas.
            int rm,
            /// Extensão de {@link #rm}, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `null` nas demais formas.
            Ir64ExtendType extendType,
            /// Quantidade de deslocamento, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}.
            int shiftAmount) implements Ir64Op {
        @Override public int kind() { return Kind.STORE64; }
    }

    /// `LDP`/`STP`/`LDPSW` (`ARM DDI 0487 C6.2.126/337/125`, B8.1) — o idioma de prólogo/epílogo de
    /// qualquer binário A64 real (ver Armadilhas do épico). Só as 3 formas de endereçamento SEM
    /// registrador (não existe `LDP`/`STP` com deslocamento por registrador). Ambos os
    /// registradores (`Rt`/`Rt2`) seguem a convenção normal (`31`=`XZR`); `Rn` é SEMPRE `SP` (ver
    /// {@link Load64#rn}).
    record LoadStorePair(
            /// `true` para `LDP`/`LDPSW`, `false` para `STP`.
            boolean load,
            /// Primeiro registrador transferido (índice `0`-`31`; `31`=`XZR`).
            int rt,
            /// Segundo registrador transferido (índice `0`-`31`; `31`=`XZR`).
            int rt2,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// `true` para o par de 64 bits (`X`, cada slot de memória tem 8 bytes); `false` para
            /// o par de 32 bits (`W`/`LDPSW`, 4 bytes cada). Irrelevante quando {@link #signExtend}
            /// (`LDPSW` sempre transfere pares de 32 bits, mesmo escrevendo em `X`).
            boolean wide,
            /// Modo de endereçamento (`OFFSET`/`PRE_INDEX`/`POST_INDEX` — nunca
            /// `REGISTER_OFFSET`).
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes, já escalado pelo decoder (`imm7` × `4` ou `× 8`
            /// conforme {@link #wide}, sempre `× 4` quando {@link #signExtend}).
            long immediate,
            /// `true` só para `LDPSW` (`opc=01`, única forma com sinal — não existe `STP` com
            /// sinal): lê dois valores de 32 bits e estende o sinal de cada um para o `X`
            /// correspondente, ignorando {@link #wide}.
            boolean signExtend) implements Ir64Op {
        @Override public int kind() { return Kind.LOAD_STORE_PAIR; }
    }

    /// `LDR (literal)`/`LDRSW (literal)` (`ARM DDI 0487 C6.2.121/134`): carrega um valor de um
    /// endereço relativo ao PC da própria instrução — usado pelo idioma de "literal pool" que
    /// compiladores A64 emitem para constantes grandes. O decoder já resolveu o endereço absoluto
    /// (mesma convenção de {@link PcRelative#instructionAddress} — sem viés `+8`, o PC de uma
    /// instrução A64 é o próprio endereço dela).
    record LoadLiteral64(
            /// Registrador de destino (índice `0`-`31`; `31`=`XZR`).
            int rt,
            /// Endereço absoluto já resolvido (`instructionAddress + signExtend(imm19) * 4`).
            long address,
            /// `true` para carregar 64 bits (`X`) inteiros; `false` para 32 bits (`W`,
            /// zero-estendido). Irrelevante quando {@link #signExtend} (a única forma com sinal,
            /// `LDRSW`, sempre lê 32 bits da memória e escreve em `X`).
            boolean wide,
            /// `true` só para `LDRSW (literal)` — lê uma palavra de 32 bits e estende o sinal
            /// para os 64 bits do destino.
            boolean signExtend) implements Ir64Op {
        @Override public int kind() { return Kind.LOAD_LITERAL64; }
    }

    /// `ADD`/`SUB`/`ADDS`/`SUBS` na forma "shifted register" (`ARM DDI 0487 C6.2.4`/`C6.2.339`
    /// variante registrador, B6.3.1) — segundo operando é `Rm` inteiro deslocado por
    /// {@link #shiftType}/{@link #shiftAmount} antes da soma/subtração. `Rd`/`Rn` NUNCA são `SP`
    /// nesta forma (diferente de {@link Alu64} e de {@link AluExtendedRegister}) — por isso não
    /// há campos `dstIsStackPointer`/`src1IsStackPointer` aqui, o valor seria sempre `false`
    /// (índice `31` em {@link #dst}/{@link #src1} é sempre `XZR`).
    record AluShiftedRegister(
            /// Operação (só `ADD`/`SUB` — `ADDS`/`SUBS` são o mesmo opcode com
            /// {@link #setFlags}).
            Ir64AluOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro registrador de origem (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Segundo registrador de origem (`Rm`, índice `0`-`31`; `31` é sempre `XZR`),
            /// deslocado por {@link #shiftType}/{@link #shiftAmount} antes de operar.
            int src2,
            /// Tipo de deslocamento (`LSL`/`LSR`/`ASR` — `ROR` é reservado nesta forma, ver
            /// {@link Ir64ShiftType}).
            Ir64ShiftType shiftType,
            /// Quantidade de deslocamento, já validada pelo decoder: `0`-`63` quando
            /// {@link #wide}, `0`-`31` quando não (`sf=0` com bit5 setado é UNDEFINED — ver a
            /// task B6.3.1).
            int shiftAmount,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`).
            boolean wide,
            /// Indica se `NZCV` deve ser atualizado (`ADDS`/`SUBS` vs `ADD`/`SUB`).
            boolean setFlags) implements Ir64Op {
        @Override public int kind() { return Kind.ALU_SHIFTED_REGISTER; }
    }

    /// `ADD`/`SUB`/`ADDS`/`SUBS` na forma "extended register" (`ARM DDI 0487 C6.2.4`/`C6.2.339`
    /// variante estendida, B6.3.1) — segundo operando é uma FATIA de `Rm` (tamanho e sinal
    /// dados por {@link #extendType}) estendida para a largura da operação e então deslocada por
    /// {@link #shiftAmount} (`0`-`4`). Modo de operando genuinamente diferente de
    /// {@link AluShiftedRegister} — não a mesma operação com um parâmetro a mais (ver B6.3.1
    /// Fatos de referência #5).
    record AluExtendedRegister(
            /// Operação (só `ADD`/`SUB` — `ADDS`/`SUBS` são o mesmo opcode com
            /// {@link #setFlags}).
            Ir64AluOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR` ou `SP` conforme
            /// {@link #dstIsStackPointer} — resolvido pelo EXECUTOR checando o índice, nunca
            /// incondicionalmente).
            int dst,
            /// Primeiro registrador de origem (`Rn`, índice `0`-`31`). **Sempre** `Rn|SP` nesta
            /// forma — `31` é sempre `SP`, nunca `XZR` (arquitetural, sem exceção; por isso não
            /// há um campo `src1IsStackPointer` aqui, ao contrário de {@link Alu64}: o valor
            /// seria sempre `true`).
            int src1,
            /// Segundo registrador de origem (`Rm`, índice `0`-`31`; `31` é sempre `XZR` — `Rm`
            /// NUNCA é `SP` nesta forma), fatiado/estendido por {@link #extendType} e deslocado
            /// por {@link #shiftAmount} antes de operar.
            int src2,
            /// Extensão aplicada a {@link #src2} (8 combinações tamanho×sinal — ver
            /// {@link Ir64AluExtendType}).
            Ir64AluExtendType extendType,
            /// Quantidade de deslocamento aplicada APÓS a extensão, já validada pelo decoder:
            /// `0`-`4` (`5`-`7` são UNDEFINED, ver a task B6.3.1).
            int shiftAmount,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`).
            boolean wide,
            /// Indica se `NZCV` deve ser atualizado (`ADDS`/`SUBS` vs `ADD`/`SUB`).
            boolean setFlags,
            /// `true` quando o índice `31` em {@link #dst} significa `SP` (não `XZR`) — vale só
            /// para `ADD`/`SUB` (sem `S`); `ADDS`/`SUBS` sempre têm isto `false` (destino é
            /// sempre `Rd` normal/`XZR`, nunca `SP` — mesma regra de {@link Alu64#dstIsStackPointer}).
            boolean dstIsStackPointer) implements Ir64Op {
        @Override public int kind() { return Kind.ALU_EXTENDED_REGISTER; }
    }

    /// `AND`/`ORR`/`EOR`/`ANDS` e `BIC`/`ORN`/`EON`/`BICS` (`ARM DDI 0487 C6.2.9`/`C6.2.13`/
    /// `C6.2.53`/`C6.2.220`/`C6.2.234`/`C6.2.226`, variante "shifted register", B6.9) — segundo
    /// operando é `Rm` deslocado por {@link #shiftType}/{@link #shiftAmount} e OPCIONALMENTE
    /// invertido bit a bit ({@link #invert}, o bit `n` do encoding) ANTES de combinar com `Rn`
    /// pela operação lógica: `invert=false` produz `AND`/`ORR`/`EOR`, `invert=true` produz
    /// `BIC`/`ORN`/`EON` (o mesmo {@link #opcode}, só o operando muda). `ANDS`/`BICS` são
    /// {@link Ir64AluOp#AND} com {@link #setFlags}`=true` — mesma decisão de {@link Alu64} (D2
    /// da task B6.3.1): não há um opcode `ANDS` dedicado, os flags (`C=0,V=0` sempre) são
    /// resolvidos pelo executor (`logicalWithFlags`), não pelo opcode. Diferente de
    /// {@link AluShiftedRegister}: usa {@link Ir64LogicalShiftType} (4 valores, `ROR` válido
    /// aqui — RESERVADO na forma `ADD`/`SUB`), não {@link Ir64ShiftType}. `Rd`/`Rn`/`Rm` NUNCA
    /// são `SP` (`cpu_reg`/`read_cpu_reg` no QEMU, nunca a variante `_sp`) — sem campos
    /// `dstIsStackPointer`/`src1IsStackPointer`, seriam sempre `false`. `MOV`/`MVN`
    /// (registrador) são um alias de disassembly puro (`ORR`/`ORN` com `Rn=XZR`,`sa=0`,
    /// `st=LSL`) — não têm representação própria aqui, o caminho geral já produz o resultado
    /// correto (ver B6.9 Fatos de referência #4/Decisão D3).
    record LogicalShiftedRegister(
            /// Operação (`AND`, `ORR` ou `EOR` — nunca `SUB`/`ADD`; `ANDS`/`BICS` são `AND` com
            /// {@link #setFlags}).
            Ir64AluOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro registrador de origem (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Segundo registrador de origem (`Rm`, índice `0`-`31`; `31` é sempre `XZR`),
            /// deslocado por {@link #shiftType}/{@link #shiftAmount} e depois opcionalmente
            /// invertido ({@link #invert}) antes de operar.
            int src2,
            /// Tipo de deslocamento — as 4 combinações são válidas aqui (ver
            /// {@link Ir64LogicalShiftType}).
            Ir64LogicalShiftType shiftType,
            /// Quantidade de deslocamento, já validada pelo decoder: `0`-`63` quando
            /// {@link #wide}, `0`-`31` quando não (`sf=0` com bit5 setado é UNDEFINED — mesma
            /// regra de {@link AluShiftedRegister}).
            int shiftAmount,
            /// `true` quando o operando deslocado deve ser invertido bit a bit ANTES de operar
            /// (bit `n` do encoding) — produz `BIC`/`ORN`/`EON`/`BICS` em vez de
            /// `AND`/`ORR`/`EOR`/`ANDS`.
            boolean invert,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`).
            boolean wide,
            /// Indica se `NZCV` deve ser atualizado (`ANDS`/`BICS` vs formas sem `S`) — `C=0,V=0`
            /// sempre quando `true` (nunca há cálculo de carry/overflow em operação lógica).
            boolean setFlags) implements Ir64Op {
        @Override public int kind() { return Kind.LOGICAL_SHIFTED_REGISTER; }
    }

    /// `LSLV`/`LSRV`/`ASRV`/`RORV` (`ARM DDI 0487 C6.2.221/223/26/300`, "Data-processing (2
    /// source)", B6.11) — deslocamento de {@link #src1} por uma quantidade tomada em TEMPO DE
    /// EXECUÇÃO de {@link #src2} (`Rm mod regsize`, nunca um imediato do encoding — ao contrário
    /// de {@link LogicalShiftedRegister}, cujo `shiftAmount` já vem resolvido pelo decoder).
    /// `Rd`/`Rn`/`Rm` NUNCA são `SP` (mesmo subgrupo de {@link Divide}/{@link
    /// MultiplyAccumulate}). Reaproveita {@link Ir64LogicalShiftType} (4 valores, `ROR` incluso)
    /// em vez de um enum próprio — os bits `[11:10]` do encoding já caem na mesma ordem
    /// `LSL/LSR/ASR/ROR` do enum (Fatos de referência da task B6.11). Nunca afeta `NZCV`.
    record ShiftVariable(
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Registrador deslocado (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Registrador cujo valor (mod largura) é a quantidade de deslocamento (`Rm`, índice
            /// `0`-`31`; `31` é sempre `XZR`, ou seja, deslocamento por `0`).
            int src2,
            /// Tipo de deslocamento (as 4 combinações são válidas).
            Ir64LogicalShiftType shiftType,
            /// `true` para operação de 64 bits (`X`, quantidade `mod 64`); `false` para 32 bits
            /// (`W`, quantidade `mod 32`, resultado zero-estendido para os 64 bits altos).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.SHIFT_VARIABLE; }
    }

    /// `CSEL`/`CSINC`/`CSINV`/`CSNEG` (`ARM DDI 0487 C6.2.34-37`, B6.3.2) — a única família de A64
    /// que consome uma condição de 4 bits fora de `B.cond`. **Nunca afeta `NZCV`** (só LÊ os flags
    /// para avaliar {@link #condition}, diferente de {@link Alu64}/{@link AluShiftedRegister}/
    /// {@link AluExtendedRegister} com `setFlags`). `Rd`/`Rn`/`Rm` NUNCA são `SP` (`cpu_reg`, nunca
    /// `cpu_reg_sp` no QEMU) — por isso não há campos `dstIsStackPointer`/`src1IsStackPointer`
    /// aqui, seriam sempre `false`. Os aliases `CSET`/`CSETM`/`CINC`/`CINV`/`CNEG` (`ARM DDI 0487
    /// C6.2`, tabela de aliases) não têm representação própria — são o MESMO op com `src1==src2`
    /// (ou `==XZR`) e a condição já invertida pelo assembler; nada aqui precisa saber disso (ver
    /// Armadilhas da task: nenhum atalho de `CSET`/`CSETM` no executor).
    record ConditionalSelect(
            /// Sub-operação (`CSEL`/`CSINC`/`CSINV`/`CSNEG`).
            Ir64ConditionalSelectOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Registrador copiado quando {@link #condition} é verdadeira (`Rn`, índice `0`-`31`;
            /// `31` é sempre `XZR`).
            int src1,
            /// Registrador-base do "senão" (`Rm`, índice `0`-`31`; `31` é sempre `XZR`) —
            /// transformado por {@link #opcode} (identidade/`+1`/`~`/`-`) quando {@link #condition}
            /// é falsa.
            int src2,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado
            /// zero-estendido para os 64 bits altos do destino).
            boolean wide,
            /// Condição avaliada contra `PSTATE.{N,Z,C,V}` para escolher entre {@link #src1} e
            /// `f(`{@link #src2}`)`.
            Ir64Condition condition) implements Ir64Op {
        @Override public int kind() { return Kind.CONDITIONAL_SELECT; }
    }

    /// `SBFM`/`BFM`/`UBFM` (`ARM DDI 0487 C6.2`, B6.3.2) — extração/inserção de campo de bits.
    /// Cobre de graça os 11 aliases do épico (`UBFX`/`SBFX`/`BFI`/`BFXIL`/`LSL`/`LSR`/`ASR`/
    /// `UXTB`/`UXTH`/`SXTB`/`SXTH`/`SXTW`, ver Fatos de referência #2 da task): todos são o MESMO
    /// encoding com valores específicos de {@link #immr}/{@link #imms} — o decoder NUNCA precisa
    /// reconhecer o alias, só produzir este record a partir dos campos crus.
    ///
    /// **Decisão explícita (D2 da task): {@link #immr}/{@link #imms} ficam CRUS no IR**, sem
    /// pré-cálculo de `pos`/`len` pelo decoder — o cálculo depende de `bitsize` (32 vs 64, já
    /// disponível via {@link #wide} no executor), e auditar o executor contra o pseudocódigo do
    /// manual/QEMU é mais direto com os MESMOS nomes de campo que a fonte usa.
    record Bitfield(
            /// Sub-operação (`SBFM`/`BFM`/`UBFM`).
            Ir64BitfieldOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR` — bitfield não tem
            /// forma `SP`).
            int dst,
            /// Registrador de origem (índice `0`-`31`; `31` é sempre `XZR`).
            int src,
            /// Campo `immr` cru do encoding (`0`-`63`, mas só `0`-`31` é válido quando
            /// {@code !wide}).
            int immr,
            /// Campo `imms` cru do encoding (`0`-`63`, mesma restrição de {@link #immr}).
            int imms,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.BITFIELD; }
    }

    /// `MADD`/`MSUB` (`ARM DDI 0487 C6.2.197/226`, B6.3.3, subgrupo "Data-processing (3 source)").
    /// Os aliases `MUL`/`MNEG` (`Ra=XZR`) não têm representação própria — o caminho geral de
    /// execução já produz o resultado certo quando {@link #accumulator} é `XZR` (lê `0`), sem
    /// nenhum atalho dedicado (mesmo raciocínio já registrado para `CSET`/`CSETM` em B6.3.2; ver
    /// Fatos de referência #1 da task e a decisão D2). Nunca afeta `NZCV`; nenhum operando aceita
    /// `SP` (todos são `cpu_reg` puro no encoding, nunca `cpu_reg_sp`).
    record MultiplyAccumulate(
            /// `false` para `MADD` (soma o produto ao acumulador), `true` para `MSUB` (subtrai o
            /// produto do acumulador).
            boolean subtract,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro registrador multiplicando (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Segundo registrador multiplicando (`Rm`, índice `0`-`31`; `31` é sempre `XZR`).
            int src2,
            /// Registrador acumulador (`Ra`, índice `0`-`31`; `31` é sempre `XZR` — é assim que
            /// `MUL`/`MNEG` chegam aqui sem `case` de decode dedicado).
            int accumulator,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado sempre
            /// zero-estendido para os 64 bits altos do destino).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.MULTIPLY_ACCUMULATE; }
    }

    /// `SDIV`/`UDIV` (`ARM DDI 0487 C6.2.375/404`, B6.3.3, subgrupo "Data-processing (2 source)").
    /// Divisor `0` produz resultado `0` — SEM exceção arquitetural (ver Fatos de referência #2 da
    /// task, diferente da divisão inteira de Java, que lança `ArithmeticException`). `SDIV` com
    /// overflow (`MIN_VALUE / -1`) trunca para `MIN_VALUE`, mesma convenção de complemento-de-dois
    /// que a divisão inteira de Java já produz sem lançar. Nenhum operando aceita `SP`; nunca
    /// afeta `NZCV`.
    record Divide(
            /// `false` para `UDIV` (divisão sem sinal), `true` para `SDIV` (divisão com sinal).
            boolean signed,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Dividendo (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Divisor (`Rm`, índice `0`-`31`; `31` é sempre `XZR`).
            int src2,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado sempre
            /// zero-estendido para os 64 bits altos do destino).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.DIVIDE; }
    }

    /// `LDXR`/`LDAXR` (`ARM DDI 0487 C6.2.145/141`, B6.3.4) — carrega a memória em `rn`+0 (SEM
    /// deslocamento, ao contrário de {@link Load64}: a forma exclusiva não tem imediato nem
    /// endereçamento indexado) e marca o monitor de exclusividade com `(endereço, size.bytes())`.
    /// `acquireRelease` (`LDAXR`=`true`/`LDXR`=`false`) é NOP observável no interpretador — mesma
    /// convenção de {@link dev.vitorsilverio.armjitter.ir.IrOp.MemoryBarrier} no IR de 32 bits —
    /// carregado no IR só para um futuro emissor nativo poder emitir a barreira de host real, se
    /// algum dia importar (single-thread por construção nesta fatia).
    record LoadExclusive(
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR`, descarta a escrita).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP` — mesma convenção de
            /// {@link Load64#rn}).
            int rn,
            /// Tamanho da transferência de memória e da marcação do monitor.
            Ir64MemSize size,
            /// `true` para `LDAXR` (bit `lasr`=1); `false` para `LDXR`.
            boolean acquireRelease) implements Ir64Op {
        @Override public int kind() { return Kind.LOAD_EXCLUSIVE; }
    }

    /// `STXR`/`STLXR` (`ARM DDI 0487 C6.2.363/360`, B6.3.4) — consulta o monitor de exclusividade
    /// ANTES de qualquer escrita (armadilha crítica espelhada de `STREX`, B1.4): se a reserva do
    /// core bater exatamente `(endereço, size.bytes())`, escreve `rt` na memória e grava `0` em
    /// `rs`; senão NÃO escreve (memória intacta) e grava `1` em `rs`. `acquireRelease`
    /// (`STLXR`=`true`/`STXR`=`false`) é NOP observável — mesma convenção de {@link LoadExclusive}.
    record StoreExclusive(
            /// Registrador de STATUS (`0`=sucesso, `1`=falha) — mesmo papel de `Rd` em `STREX`
            /// (32-bit, B1.4). Índice `0`-`31`; `31` é `XZR`, descarta a escrita do status.
            int rs,
            /// Registrador de origem do valor armazenado (índice `0`-`31`; `31` é `XZR`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP` — ver {@link Load64#rn}).
            int rn,
            /// Tamanho da transferência de memória e da checagem do monitor.
            Ir64MemSize size,
            /// `true` para `STLXR` (bit `lasr`=1); `false` para `STXR`.
            boolean acquireRelease) implements Ir64Op {
        @Override public int kind() { return Kind.STORE_EXCLUSIVE; }
    }

    /// `LDXP`/`LDAXP` (`ARM DDI 0487 C6.2.144/140`, B8.1) — mesmo espírito de {@link LoadExclusive}
    /// mas para um PAR de registradores, sem deslocamento (`Rn`+`0`); marca o monitor cobrindo os
    /// DOIS slots (`2 × size.bytes()`). `Rn` é SEMPRE `SP` (ver {@link Load64#rn}); `size` é sempre
    /// `WORD` ou `DOUBLEWORD` (não existe forma byte/half de par).
    record LoadExclusivePair(
            /// Primeiro registrador de destino (índice `0`-`31`; `31`=`XZR`).
            int rt,
            /// Segundo registrador de destino (índice `0`-`31`; `31`=`XZR`).
            int rt2,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// `true` para o par de 64 bits (`X`); `false` para o par de 32 bits (`W`).
            boolean wide,
            /// `true` para `LDAXP` (bit `lasr`=1); `false` para `LDXP` — NOP observável, mesma
            /// convenção de {@link LoadExclusive#acquireRelease}.
            boolean acquireRelease) implements Ir64Op {
        @Override public int kind() { return Kind.LOAD_EXCLUSIVE_PAIR; }
    }

    /// `STXP`/`STLXP` (`ARM DDI 0487 C6.2.364/361`, B8.1) — mesmo espírito de {@link StoreExclusive}
    /// mas para um par: consulta o monitor cobrindo `2 × size.bytes()` ANTES de qualquer escrita
    /// (mesma armadilha crítica de {@link StoreExclusive}).
    record StoreExclusivePair(
            /// Registrador de STATUS (`0`=sucesso, `1`=falha). Índice `0`-`31`; `31`=`XZR`.
            int rs,
            /// Primeiro registrador de origem (índice `0`-`31`; `31`=`XZR`).
            int rt,
            /// Segundo registrador de origem (índice `0`-`31`; `31`=`XZR`).
            int rt2,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// `true` para o par de 64 bits (`X`); `false` para o par de 32 bits (`W`).
            boolean wide,
            /// `true` para `STLXP` (bit `lasr`=1); `false` para `STXP` — NOP observável.
            boolean acquireRelease) implements Ir64Op {
        @Override public int kind() { return Kind.STORE_EXCLUSIVE_PAIR; }
    }

    /// `CAS`/`CASA`/`CASL`/`CASAL` (`ARM DDI 0487 C6.2.31`, B8.1, extensão LSE ARMv8.1 — decisão
    /// explícita do plano `b7-plano-cobertura-isa.md`: implementar mesmo sendo opcional, diferente
    /// de `LDADD`/`LDCLR`/... que ficam de fora desta task). Semântica de `CMPXCHG`: lê a memória
    /// em `[Rn]`, compara com `Rs`; se igual, escreve `Rt`; **sempre** grava o valor antigo lido em
    /// `Rs` (comparação e substituição são atômicas do ponto de vista do guest — o interpretador,
    /// single-thread por construção, não precisa de CAS real de host). As variantes de
    /// acquire/release (`L`/`o0` no encoding) não são distinguidas — NOP observável, mesmo espírito
    /// de {@link LoadExclusive#acquireRelease}.
    record CompareAndSwap(
            /// Registrador de comparação/valor antigo (índice `0`-`31`; `31`=`XZR`).
            int rs,
            /// Registrador com o novo valor a escrever se a comparação bater.
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Tamanho da transferência de memória e da comparação (byte/half/word/doubleword —
            /// diferente de {@link CompareAndSwapPair}, `CAS` aceita as 4 larguras).
            Ir64MemSize size) implements Ir64Op {
        @Override public int kind() { return Kind.COMPARE_AND_SWAP; }
    }

    /// `CASP`/`CASPA`/`CASPL`/`CASPAL` (`ARM DDI 0487 C6.2.32`, B8.1, mesma extensão LSE de
    /// {@link CompareAndSwap}) — versão em PAR: compara `(Rs,Rs+1)` contra `[Rn]`/`[Rn+size]`; se
    /// ambos baterem, escreve `(Rt,Rt+1)`; sempre grava o par antigo lido em `(Rs,Rs+1)`. O manual
    /// exige `Rs`/`Rt` PARES (bit 0 do índice ignorado no encoding real), mas o decoder não
    /// verifica isso — copia o campo cru, mesma disciplina de nunca resolver convenção de
    /// registrador fora do executor; o EXECUTOR deriva o companheiro como `rs|1`/`rt|1` (nunca
    /// `+1` — preserva o comportamento definido mesmo se um binário malformado passar um índice
    /// ímpar). `size` só `WORD` ou `DOUBLEWORD` (não existe par de byte/half).
    record CompareAndSwapPair(
            /// Primeiro registrador de comparação/valor antigo (índice `0`-`31`; `31`=`XZR`).
            int rs,
            /// Primeiro registrador com o novo valor.
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// `true` para o par de 64 bits (`X`); `false` para o par de 32 bits (`W`).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.COMPARE_AND_SWAP_PAIR; }
    }

    /// `MRS`/`MSR (register)` (`ARM DDI 0487 C5.2.3`, B6.6.1) — leitura/escrita de um registrador
    /// de sistema nomeado. O registrador é identificado pela 5-upla `op0:op1:CRn:CRm:op2` do
    /// encoding, já resolvida pelo DECODER em {@link Aarch64SystemRegisterId} (nunca pelo
    /// executor a partir dos bits crus). Não existe forma `W`: o bit mais alto da instrução é
    /// parte do prefixo fixo do encoding (não um `sf`), então `Rt` é sempre o registrador `X`
    /// completo — `31` em {@link #rt} é `XZR` (`MRS` descarta a escrita; `MSR` lê `0`).
    record SystemRegister(
            /// `true` para `MRS` (leitura, `L=1`); `false` para `MSR` (escrita, `L=0`).
            boolean read,
            /// Registrador de sistema identificado pelo decoder.
            Aarch64SystemRegisterId register,
            /// Registrador geral envolvido: destino em `MRS`, origem em `MSR` (índice `0`-`31`;
            /// `31` é `XZR`).
            int rt) implements Ir64Op {
        @Override public int kind() { return Kind.SYSTEM_REGISTER; }
    }

    /// `SYS`/`SYS(L)` (`ARM DDI 0487 C5.2.3`, task B6.6.3) — subconjunto mínimo reconhecido:
    /// `TLBI VMALLE1`/`TLBI VMALLE1IS` e as barreiras `DSB`/`ISB`/`DMB`. Diferente de
    /// {@link SystemRegister}: não carrega registrador geral nenhum (`TLBI VMALLE1`/barreiras não
    /// leem/escrevem `Rt` — o campo existe no encoding só porque compartilha o formato de `SYS`,
    /// mas o decoder não precisou dele para o subconjunto coberto aqui).
    record SystemInstruction(
            /// Sub-operação identificada pelo decoder.
            Ir64SystemInstructionOp opcode) implements Ir64Op {
        @Override public int kind() { return Kind.SYSTEM_INSTRUCTION; }
    }

    /// `ERET` (`ARM DDI 0487 C6.2.111`, task B6.6.4) — retorna de EL1 para EL0:
    /// `PC←ELR_EL1`, `PSTATE.{N,Z,C,V}←SPSR_EL1`, sai de EL1. Record dedicado (não reaproveita
    /// {@link SystemInstruction}, decisão registrada na task): a semântica muda `PC` e `PSTATE`
    /// como um desvio tomado, MUITO diferente de `TLBI`/barreira (NOPs observáveis do ponto de
    /// vista do fluxo de controle) — misturar os dois no mesmo tipo confundiria o executor (teria
    /// que devolver `true`/`false` de `boolean` dependendo do sub-opcode). Sem operandos: o
    /// encoding fixa `Rn=31` (não lido, `ARM DDI 0487` pseudocódigo de `ERET`).
    record ExceptionReturn() implements Ir64Op {
        @Override public int kind() { return Kind.EXCEPTION_RETURN; }
    }

    /// `HVC`/`SMC` (`ARM DDI 0487 C6.2.148/C6.2.294`, task B6.6.7; `HVC` real desde B10.4, `SMC`
    /// real desde B10.5). `HVC` entra em EL2 de verdade via
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#enterHypervisorCall}; `SMC` entra em
    /// EL3 de verdade via
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#enterSecureMonitorCall} — ver
    /// `Ir64BlockExecutor#executePrivilegedCall`. Sem campo de imediato: o `imm16` do encoding só
    /// teria sentido para um handler em EL2/EL3 que leia a própria instrução, que este emulador não
    /// modela (ver Armadilhas das tasks B10.4/B10.5).
    ///
    /// @param isHvc `true` para `HVC` (entra em EL2, B10.4), `false` para `SMC` (entra em EL3,
    ///              B10.5)
    record PrivilegedCall(boolean isHvc) implements Ir64Op {
        @Override public int kind() { return Kind.PRIVILEGED_CALL; }
    }

    /// `AT` (`ARM DDI 0487 C6.2.23`, task B10.6) — traduz `Xt` (VA) pelo regime EL1&0 real e
    /// escreve `PAR_EL1`, SEM gerar acesso de memória nem exceção síncrona para o guest (falha vira
    /// `PAR_EL1.F=1`, nunca um abort) — ver
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters#addressTranslate}.
    /// `S1E2*`/`S1E3*`/`S12E*` não são decodificadas ainda (ver `Aarch64AddressTranslateForm`).
    ///
    /// @param form forma decodificada (`S1E1R`/`S1E1W`/`S1E0R`/`S1E0W`)
    /// @param rt   registrador de origem do VA (índice `0`-`31`; `31` é `XZR`, mesma convenção de
    ///             {@link SystemRegister#rt})
    record AddressTranslate(Aarch64AddressTranslateForm form, int rt) implements Ir64Op {
        @Override public int kind() { return Kind.ADDRESS_TRANSLATE; }
    }

    /// Sub-operação de {@link Fp64Alu} — leitura literal do épico B6.5 ("FMOV/FADD/FMUL/FDIV/
    /// FCMP/FCVT", task B6.5.2): `SQRT`/`MLA`/`MLS`/`NMUL` (existentes no precedente VFP32,
    /// {@code IrOp.VfpOperation}) ficam FORA de propósito — não citados nesta leitura, ver
    /// Armadilhas da task. `MOV` é a forma registrador↔registrador de `FMOV` (cópia de bits, sem
    /// aritmética) — não confundir com {@link Fp64MoveImmediate} (`FMOV` imediato).
    enum Fp64Operation {
        ADD, SUB, MUL, DIV, NEG, ABS, MOV
    }

    /// `FADD`/`FSUB`/`FMUL`/`FDIV`/`FNEG`/`FABS`/`FMOV` registrador↔registrador (`ARM DDI 0487
    /// C6.2` — seção exata a confirmar em B6.5.3, ver Armadilhas da task B6.5.2). Sem campo de
    /// condição (D1 da task: nenhum `Ir64Op` de dado carrega condição, só {@link Branch64}).
    record Fp64Alu(
            /// Operação a executar.
            Fp64Operation op,
            /// `true` para precisão dupla (`D<n>`), `false` para simples (`S<n>`).
            boolean doublePrecision,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Primeiro operando (`Vn`) — ignorado nas unárias (`NEG`/`ABS`/`MOV`, que usam só
            /// {@link #vm}).
            int vn,
            /// Segundo operando (`Vm`), ou único operando nas unárias.
            int vm) implements Ir64Op {
        @Override public int kind() { return Kind.FP64_ALU; }
    }

    /// `FMOV Sd, #imm`/`FMOV Dd, #imm` (`ARM DDI 0487 C6.2` — seção a confirmar em B6.5.3): grava
    /// um imediato de ponto flutuante já expandido (`VFPExpandImm`-equivalente, decodificado pelo
    /// DECODER — B6.5.3, não aqui) no registrador de destino.
    record Fp64MoveImmediate(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Bits crus já expandidos pelo decoder: 32 bits válidos quando `!doublePrecision`
            /// (bits altos ignorados), 64 bits completos quando `doublePrecision`.
            long immediateBits) implements Ir64Op {
        @Override public int kind() { return Kind.FP64_MOVE_IMMEDIATE; }
    }

    /// `FCMP`/`FCMPE` (`ARM DDI 0487 C6.2` — seção a confirmar em B6.5.3), com ou sem comparação
    /// com zero. **Escreve `PSTATE.NZCV` diretamente** (diferente de {@code IrOp.VfpCompare} no
    /// mundo de 32 bits, que escreve um `FpscrRegister.NZCV` separado, exigindo um segundo passo
    /// `VMRS APSR_nzcv` para chegar aos flags que os branches condicionais leem — em A64 não há
    /// registro de flags de FP separado do `PSTATE`, ver Fatos de referência #1 da task).
    record Fp64Compare(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// `true` para as formas `FCMP(E) Vn, #0.0` (compara com zero em vez de `vm`).
            boolean compareWithZero,
            /// `true` para `FCMPE` (bit E do encoding: sinaliza operação inválida também para NaN
            /// silencioso). Sem efeito observável adicional neste core — que não modela traps de
            /// exceção de ponto flutuante, mesmo precedente de {@code IrOp.VfpCompare} no mundo de
            /// 32 bits — carregado só para fidelidade ao encoding.
            boolean signalOnQuietNaN,
            /// Primeiro operando comparado (`Vn`, não `vd`: esta instrução nunca escreve um
            /// registrador FP).
            int vn,
            /// Segundo operando da comparação (`Vm`), ignorado quando {@link #compareWithZero}.
            int vm) implements Ir64Op {
        @Override public int kind() { return Kind.FP64_COMPARE; }
    }

    /// Sub-operação de {@link Fp64Convert} — leitura literal de "FCVT": só conversão float↔float
    /// de precisão. `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` (conversão inteiro↔float) são mnemônicos
    /// DIFERENTES, fora do escopo desta task (ver Armadilhas da task B6.5.2).
    enum Fp64Conversion {
        F32_TO_F64, F64_TO_F32
    }

    /// `FCVT` (`ARM DDI 0487 C6.2` — seção a confirmar em B6.5.3): conversão de precisão
    /// float↔float, sem mudança de valor além do arredondamento IEEE 754 (widening exato
    /// F32→F64; narrowing corretamente arredondado F64→F32).
    record Fp64Convert(
            /// Direção da conversão.
            Fp64Conversion conversion,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Registrador de origem (índice `0`-`31`, `V<n>`).
            int vm) implements Ir64Op {
        @Override public int kind() { return Kind.FP64_CONVERT; }
    }

    /// `CCMP`/`CCMN` (`ARM DDI 0487 C6.2.25/24`, B6.8) — gap achado por uma sessão de F11
    /// (`virtual-arm-box`): a PRIMEIRA instrução de praticamente todo `kernel8.img` real
    /// (`ccmp x18, #0, #0xd, pl`, truque polyglot EFI "MZ" do cabeçalho `Image` do Linux) usa esta
    /// família, nunca implementada em nenhuma sub-task de B6.3. Encoding confirmado contra o
    /// `a64.decode` do QEMU (`target/arm/tcg/a64.decode`, linha `CCMP sf:1 op:1 1 11010010 y:5
    /// cond:4 imm:1 0 rn:5 0 nzcv:4`) e `translate-a64.c#trans_CCMP` — `S`(bit29) é sempre `1`
    /// neste encoding (não um campo, parte do prefixo fixo); `op`(bit30) `0`=`CCMN`(soma),
    /// `1`=`CCMP`(subtração), MESMO bit/semântica de {@link Ir64AluOp#ADD}/{@link Ir64AluOp#SUB}
    /// em {@link AluShiftedRegister}. Um único record cobre as DUAS formas (registrador e
    /// imediato, D1 da task) — mesmo padrão de {@link Load64}/{@link Store64}: um campo
    /// {@link #immediateForm} escolhe entre {@link #rm} (registrador, `-1` na forma imediato) e
    /// {@link #immediate} (`0`-`31` cru do encoding, `-1` na forma registrador).
    ///
    /// **Nunca escreve registrador** (só {@link #rn} é lido, comparado contra {@link #rm}/
    /// {@link #immediate}) — diferente de `SUBS`/`ADDS`, que também setam `NZCV` mas têm `Rd`;
    /// os bits baixos do encoding real (`[4:0]`) são fixos em `0`, não um campo `Rd` (Armadilhas
    /// da task). `Rn` é `cpu_reg` puro no QEMU (nunca `cpu_reg_sp`) — `31` é sempre `XZR`, NUNCA
    /// `SP` (diferente de {@link AluExtendedRegister#src1}), confirmado em `trans_CCMP`.
    record ConditionalCompare(
            /// `ADD`=`CCMN`, `SUB`=`CCMP` (`op`, bit30 do encoding — mesma semântica de
            /// {@link AluShiftedRegister#opcode}).
            Ir64AluOp opcode,
            /// Primeiro operando da comparação (`Rn`, índice `0`-`31`; `31` é sempre `XZR`, nunca
            /// `SP` — ver acima).
            int rn,
            /// `true` para a forma imediato (`imm5` no lugar de `Rm`), `false` para a forma
            /// registrador.
            boolean immediateForm,
            /// Segundo operando na forma REGISTRADOR (`Rm`, índice `0`-`31`; `31` é sempre
            /// `XZR`); `-1` quando {@link #immediateForm}.
            int rm,
            /// Segundo operando na forma IMEDIATO (`0`-`31`, unsigned, cru do encoding — NÃO um
            /// índice de registrador); `-1` quando `!`{@link #immediateForm}.
            int immediate,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`).
            boolean wide,
            /// Condição avaliada contra `PSTATE.{N,Z,C,V}` — quando verdadeira, `NZCV` é
            /// recalculado a partir da comparação; quando falsa, {@link #nzcv} substitui `NZCV`
            /// diretamente, SEM ler {@link #rn}/{@link #rm} (Armadilhas da task).
            Ir64Condition condition,
            /// Os 4 bits crus `N:Z:C:V` do encoding (mesma ordem/formato de
            /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#setNzcv(int)}), usados
            /// como `NZCV` quando {@link #condition} é falsa.
            int nzcv) implements Ir64Op {
        @Override public int kind() { return Kind.CONDITIONAL_COMPARE; }
    }

    /// `ADC`/`ADCS`/`SBC`/`SBCS` (`ARM DDI 0487 C6.2.2/1/242/244`, B8.2, subgrupo "Add/subtract
    /// (carry)" de "Data Processing — Register") — soma/subtrai COM o `C` de entrada atual de
    /// `PSTATE` (diferente de {@link AluShiftedRegister}/{@link Alu64}, que nunca leem `C` como
    /// entrada, só o escrevem como saída). `Rd`/`Rn`/`Rm` NUNCA são `SP` (`cpu_reg` puro no
    /// encoding, mesmo grupo de {@link Divide}/{@link MultiplyAccumulate}).
    record AluWithCarry(
            /// `false` para `ADC`/`ADCS`, `true` para `SBC`/`SBCS` (bit `op`, MESMA posição/
            /// semântica de {@link AluShiftedRegister#opcode} — aqui como `boolean` puro em vez de
            /// {@link Ir64AluOp} porque a operação real não é `ADD`/`SUB` simples, é
            /// `AddWithCarry` de 3 operandos: reaproveitar o enum sugeriria incorretamente que o
            /// executor poderia cair no mesmo caminho de {@link #addWithFlags}/
            /// {@code #subWithFlags} de 2 operandos).
            boolean subtract,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro operando (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Segundo operando (`Rm`, índice `0`-`31`; `31` é sempre `XZR`) — NUNCA deslocado
            /// (ao contrário de {@link AluShiftedRegister#src2}, esta forma não tem campo de
            /// deslocamento no encoding).
            int src2,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado sempre
            /// zero-estendido para os 64 bits altos do destino).
            boolean wide,
            /// Indica se `NZCV` deve ser atualizado (`ADCS`/`SBCS` vs `ADC`/`SBC`).
            boolean setFlags) implements Ir64Op {
        @Override public int kind() { return Kind.ALU_WITH_CARRY; }
    }

    /// `EXTR` (`ARM DDI 0487 C6.2.113`, B8.2, subgrupo "Extract" de "Data Processing —
    /// Immediate") — concatena {@link #src1}`:`{@link #src2} (o dobro da largura da operação,
    /// `Rn` na metade ALTA) e extrai uma janela do tamanho da operação a partir do bit
    /// {@link #lsb} dessa concatenação. O alias `ROR Rd,Rs,#shift` (`Rn`=`Rm`=`Rs`) não tem
    /// representação própria — o caminho geral já produz rotação quando os dois campos coincidem
    /// (mesma decisão de não reconhecer alias já usada por {@link Bitfield}/
    /// {@link MultiplyAccumulate}). `Rd`/`Rn`/`Rm` NUNCA são `SP`.
    record Extract(
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Metade ALTA da concatenação (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Metade BAIXA da concatenação (`Rm`, índice `0`-`31`; `31` é sempre `XZR`) — quando
            /// {@link #lsb}`==0`, o resultado é exatamente este registrador, sem ler
            /// {@link #src1}.
            int src2,
            /// Deslocamento da janela dentro da concatenação de 2×largura (`0`-`31` quando
            /// `!`{@link #wide}, `0`-`63` quando {@link #wide} — já validado pelo decoder via o
            /// bit reservado da forma de 32 bits, ver `Aarch64Decoder#decodeExtract`).
            int lsb,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.EXTRACT; }
    }

    /// `RBIT`/`REV16`/`CLZ`/`CLS`/`CNT` (B8.2, subgrupo "Data-processing (1 source)" de "Data
    /// Processing — Register", `opc2`(bits`[30:29]`)`=10`) — mesmo grupo de bits fixos
    /// `[28:21]="11010110"` de {@link Divide}/{@link ShiftVariable} (subgrupo "2 source",
    /// `opc2=00`); SEM checar `opc2` o decoder confundia `REV32`/`REV64`/`CLZ`/etc com
    /// `SDIV`/`UDIV` (bug real corrigido por esta task — ver a seção "Bugs reais achados e
    /// corrigidos" de `b8.2-a64-inteiro-restante.md`). `Rd`/`Rn` NUNCA são `SP`.
    record DataProcessing1Source(
            /// Sub-operação.
            Ir64OneSourceOp opcode,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Registrador de origem (índice `0`-`31`; `31` é sempre `XZR`).
            int src,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado sempre
            /// zero-estendido para os 64 bits altos do destino).
            boolean wide) implements Ir64Op {
        @Override public int kind() { return Kind.DATA_PROCESSING_1_SOURCE; }
    }

    /// `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` (`ARM DDI 0487 C6.2.` — multiplicação 32×32→64 com
    /// acumulador de 64, B8.2, subgrupo "Data-processing (3 source)") — `sf` é FIXO em `1` no
    /// encoding (só existe a forma que produz um resultado `X`; não há variante `W`):
    /// {@link #src1}/{@link #src2} são SEMPRE lidos como `W` (32 bits), {@link #accumulator}/
    /// {@link #dst} SEMPRE como `X` (64 bits) — por isso este record não tem campo `wide`, ao
    /// contrário de {@link MultiplyAccumulate}. Os aliases `SMULL`/`SMNEGL`/`UMULL`/`UMNEGL`
    /// (`Ra=XZR`) não têm representação própria (mesma decisão de {@link MultiplyAccumulate}).
    record MultiplyAccumulateLong(
            /// `false` para `SMADDL`/`UMADDL` (soma o produto ao acumulador), `true` para
            /// `SMSUBL`/`UMSUBL` (subtrai).
            boolean subtract,
            /// `false` para `UMADDL`/`UMSUBL` (multiplicação sem sinal), `true` para
            /// `SMADDL`/`SMSUBL` (com sinal) — controla a extensão de {@link #src1}/{@link #src2}
            /// de 32 para 64 bits ANTES de multiplicar.
            boolean signed,
            /// Registrador de destino, sempre `X` (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro multiplicando, sempre `W` (`Rn`, índice `0`-`31`; `31` é sempre `WZR`).
            int src1,
            /// Segundo multiplicando, sempre `W` (`Rm`, índice `0`-`31`; `31` é sempre `WZR`).
            int src2,
            /// Acumulador, sempre `X` (`Ra`, índice `0`-`31`; `31` é sempre `XZR` — é assim que
            /// `SMULL`/`SMNEGL`/`UMULL`/`UMNEGL` chegam aqui sem `case` de decode dedicado).
            int accumulator) implements Ir64Op {
        @Override public int kind() { return Kind.MULTIPLY_ACCUMULATE_LONG; }
    }

    /// `SMULH`/`UMULH` (`ARM DDI 0487 C6.2.373/402`, B8.2, subgrupo "Data-processing (3 source)")
    /// — os 64 bits ALTOS do produto de 128 bits de {@link #src1}×{@link #src2} (os 64 baixos são
    /// o que `MUL`/{@link MultiplyAccumulate} já produz). `Ra` é FIXO em `11111`(`XZR`) no
    /// encoding — não é um acumulador de verdade (diferente de {@link MultiplyAccumulateLong}),
    /// por isso este record não tem campo `accumulator`. `sf` é FIXO em `1` (só existe a forma
    /// `X`; `SMULH`/`UMULH` de 32 bits não existem — usa-se `MUL` normal, o produto de 32×32
    /// sempre cabe em 64).
    record MultiplyHigh(
            /// `false` para `UMULH` (sem sinal, {@code Math.unsignedMultiplyHigh}), `true` para
            /// `SMULH` (com sinal, {@code Math.multiplyHigh}).
            boolean signed,
            /// Registrador de destino, sempre `X` (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro multiplicando, sempre `X` (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Segundo multiplicando, sempre `X` (`Rm`, índice `0`-`31`; `31` é sempre `XZR`).
            int src2) implements Ir64Op {
        @Override public int kind() { return Kind.MULTIPLY_HIGH; }
    }

    /// `SETF8`/`SETF16` (`ARM DDI 0487 C6.2.` — "Evaluate into flags", B8.2, `FEAT_FlagM`) —
    /// avalia o byte/halfword BAIXO de {@link #rn} como se fosse o resultado de uma soma,
    /// atualizando `N`/`Z`/`V` (`C` NUNCA muda — só as 3 outras). Sem registrador de destino: só
    /// lê {@link #rn}, nunca escreve registrador (mesmo padrão de {@link ConditionalCompare}).
    record EvaluateIntoFlags(
            /// Registrador avaliado (índice `0`-`31`; `31` é `XZR`, ou seja, sempre avalia `0`).
            int rn,
            /// Largura do campo avaliado em bits: `8` (`SETF8`) ou `16` (`SETF16`) — ver
            /// `Aarch64Decoder#EVALUATE_FLAGS_SIZE_8`/`_16`.
            int sizeBits) implements Ir64Op {
        @Override public int kind() { return Kind.EVALUATE_INTO_FLAGS; }
    }

    /// `RMIF` (`ARM DDI 0487 C6.2.` — "Rotate right into flags", B8.2, `FEAT_FlagM`) — rotaciona
    /// {@link #rn} para a direita por {@link #shift} bits, toma os 4 bits baixos do resultado
    /// como candidato a `N:Z:C:V` (MESMA ordem de bit de
    /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#setNzcv(int)}) e atualiza só os
    /// flags cujo bit correspondente está setado em {@link #mask} (também na mesma ordem
    /// `N:Z:C:V`) — os demais permanecem INALTERADOS.
    record RotateIntoFlags(
            /// Registrador rotacionado, sempre lido como `X` de 64 bits mesmo que só os 4 bits
            /// baixos do resultado importem (índice `0`-`31`; `31` é `XZR`).
            int rn,
            /// Quantidade de rotação à direita (`0`-`63`, cru do encoding — `imm6`).
            int shift,
            /// Máscara de 4 bits (`N:Z:C:V`, mesma ordem/formato de
            /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#nzcv()}) selecionando
            /// quais flags são atualizados.
            int mask) implements Ir64Op {
        @Override public int kind() { return Kind.ROTATE_INTO_FLAGS; }
    }

    /// `CFINV`/`XAFLAG`/`AXFLAG` (B8.2, `FEAT_FlagM2`, classe "System") — as 3 instruções que
    /// manipulam `PSTATE.{N,Z,C,V}` diretamente sem nenhum operando de registrador geral (`Rt` é
    /// fixo em `11111` no encoding das 3, não lido). Ver {@link Ir64FlagConversionOp} para a
    /// semântica de cada uma.
    record ConvertFlags(
            /// Sub-operação.
            Ir64FlagConversionOp opcode) implements Ir64Op {
        @Override public int kind() { return Kind.CONVERT_FLAGS; }
    }

    /// `MSR (immediate) DAIFSet`/`DAIFClr` (`ARM DDI 0487 C6.2.149/C6.2.150`, B8.3, subgrupo
    /// "MSR (immediate)" da classe System) — únicas duas formas de `MSR (immediate)` desta task
    /// com efeito observável real: as demais (`UAO`/`PAN`/`SPSel`/`SBSS`/`DIT`/`TCO`) viram
    /// {@link SystemInstruction} com {@link Ir64SystemInstructionOp#PSTATE_FIELD_NOP} porque este
    /// emulador não modela os campos correspondentes de `PSTATE` (ver javadoc daquele valor). O bit
    /// `I` de `DAIF` (mascaramento de IRQ) É modelado
    /// ({@link dev.vitorsilverio.armjitter.core64.PstateRegister#irqDisabled()}, B6.6.7) — por
    /// isso `DAIFSet`/`DAIFClr` ganham um record próprio em vez de virarem NOP como o resto do
    /// grupo.
    record InterruptMask(
            /// `true` para `DAIFSet` (seta os bits de `imm` em `DAIF`); `false` para `DAIFClr`
            /// (limpa).
            boolean set,
            /// Máscara de 4 bits do encoding (`imm[3:0]`, ordem `D:A:I:F` do manual — só o bit `I`
            /// (posição 1) tem efeito neste emulador; `D`/`A`/`F` são ignorados, mesma decisão já
            /// tomada para o resto de `DAIF` em B6.6.7).
            int mask) implements Ir64Op {
        @Override public int kind() { return Kind.INTERRUPT_MASK; }
    }

    /// `BRK` (`ARM DDI 0487 C6.2.29`, B8.3) — gera uma exceção síncrona de "Breakpoint Instruction"
    /// (`ESR_EL1.EC=0x3C`) incondicionalmente, independente de estado de debug (ao contrário de
    /// `HLT`, ver {@link UndefinedInstructionTrap}) — é assim que o Linux usa `BRK` para
    /// `BUG()`/`WARN_ON()`/UBSAN em builds de kernel normais, sem depender de nenhum debugger
    /// externo conectado. Executor lança
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64BreakpointException}, capturada no mesmo
    /// ponto que {@link dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64}
    /// (`Ir64BlockExecutor#step`/`#executeBlock`) — o endereço da própria instrução `BRK` (ELR_EL1)
    /// vem do rastreamento de `Fetch` já existente ali, não deste record.
    record Breakpoint(
            /// Imediato de 16 bits do encoding — vira `ESR_EL1.ISS[15:0]` (`comment` do `BRK`,
            /// convenção do Linux/GDB para identificar o motivo do trap).
            int immediate) implements Ir64Op {
        @Override public int kind() { return Kind.BREAKPOINT; }
    }

    /// `HLT` (`ARM DDI 0487 C6.2.148`, B8.3) — instrução de "Halting debug": sem estado de debug
    /// externo modelado neste emulador (mesma decisão já registrada para os registradores de debug
    /// em `Aarch64Core#ID_AA64DFR0_EL1_VALUE`), o pseudocódigo real do manual cai no caminho
    /// `UNDEFINED` (`Halting_instruction`, "Otherwise, treat as UNDEFINED") — mesmo tratamento
    /// arquitetural de um encoding reservado, só que agora um encoding REAL e nomeado, não uma
    /// combinação de bits arbitrária. Sem operando: o imediato de 16 bits do encoding só teria
    /// sentido para o host de debug externo, que não existe aqui (mesmo raciocínio do `imm16` de
    /// `HVC`/`SMC` descartado em {@link PrivilegedCall}).
    record UndefinedInstructionTrap() implements Ir64Op {
        @Override public int kind() { return Kind.UNDEFINED_INSTRUCTION_TRAP; }
    }
}
