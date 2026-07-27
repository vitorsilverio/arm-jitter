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
        Ir64Op.SystemRegister, Ir64Op.SystemInstruction, Ir64Op.ExceptionReturn {

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

    /// `LDP`/`STP` (`ARM DDI 0487 C6.2.126/337`) — o idioma de prólogo/epílogo de qualquer
    /// binário A64 real (ver Armadilhas do épico). Só as 3 formas de endereçamento SEM registrador
    /// (não existe `LDP`/`STP` com deslocamento por registrador). Ambos os registradores (`Rt`/
    /// `Rt2`) seguem a convenção normal (`31`=`XZR`); `Rn` é SEMPRE `SP` (ver {@link Load64#rn}).
    record LoadStorePair(
            /// `true` para `LDP`, `false` para `STP`.
            boolean load,
            /// Primeiro registrador transferido (índice `0`-`31`; `31`=`XZR`).
            int rt,
            /// Segundo registrador transferido (índice `0`-`31`; `31`=`XZR`).
            int rt2,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// `true` para o par de 64 bits (`X`, cada slot de memória tem 8 bytes); `false` para
            /// o par de 32 bits (`W`, 4 bytes cada).
            boolean wide,
            /// Modo de endereçamento (`OFFSET`/`PRE_INDEX`/`POST_INDEX` — nunca
            /// `REGISTER_OFFSET`).
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes, já escalado pelo decoder (`imm7` × `4` ou `× 8`
            /// conforme {@link #wide}).
            long immediate) implements Ir64Op {
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
}
