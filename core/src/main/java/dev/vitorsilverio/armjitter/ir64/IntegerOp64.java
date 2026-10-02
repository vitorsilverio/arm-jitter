package dev.vitorsilverio.armjitter.ir64;

/// Operações A64 de processamento de dados em registrador geral: aritmética, lógica, deslocamento,
/// seleção/comparação condicional, multiplicação/divisão, manipulação de `NZCV`, PAuth, CRC32 e as
/// formas de MTE que só tocam registradores (`SUBP`/`IRG`/`GMI`).
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface IntegerOp64 extends Ir64Op permits IntegerOp64.Alu64, IntegerOp64.MoveWide,
        IntegerOp64.PcRelative, IntegerOp64.AluShiftedRegister, IntegerOp64.AluExtendedRegister,
        IntegerOp64.LogicalShiftedRegister, IntegerOp64.ShiftVariable,
        IntegerOp64.ConditionalSelect, IntegerOp64.Bitfield, IntegerOp64.MultiplyAccumulate,
        IntegerOp64.Divide, IntegerOp64.ConditionalCompare, IntegerOp64.AluWithCarry,
        IntegerOp64.Extract, IntegerOp64.DataProcessing1Source, IntegerOp64.MultiplyAccumulateLong,
        IntegerOp64.MultiplyHigh, IntegerOp64.EvaluateIntoFlags, IntegerOp64.RotateIntoFlags,
        IntegerOp64.ConvertFlags, IntegerOp64.PointerAuthGeneric, IntegerOp64.PointerAuthInPlace,
        IntegerOp64.AbsGeneral, IntegerOp64.Crc32, IntegerOp64.SubtractPointer,
        IntegerOp64.InsertRandomTag, IntegerOp64.TagMaskInsert, IntegerOp64.MinMaxGeneral {

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
            boolean src1IsStackPointer) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
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
            boolean page) implements IntegerOp64 {
        @Override public int kind() { return Kind.PC_RELATIVE; }
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
            boolean setFlags) implements IntegerOp64 {
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
            boolean dstIsStackPointer) implements IntegerOp64 {
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
            boolean setFlags) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
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
            Ir64Condition condition) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
        @Override public int kind() { return Kind.DIVIDE; }
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
    /// imediato, D1 da task) — mesmo padrão de {@link MemoryOp64.Load64}/{@link MemoryOp64.Store64}: um campo
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
            int nzcv) implements IntegerOp64 {
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
            boolean setFlags) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
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
            boolean wide) implements IntegerOp64 {
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
            int accumulator) implements IntegerOp64 {
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
            int src2) implements IntegerOp64 {
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
            int sizeBits) implements IntegerOp64 {
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
            int mask) implements IntegerOp64 {
        @Override public int kind() { return Kind.ROTATE_INTO_FLAGS; }
    }

    /// `CFINV`/`XAFLAG`/`AXFLAG` (B8.2, `FEAT_FlagM2`, classe "System") — as 3 instruções que
    /// manipulam `PSTATE.{N,Z,C,V}` diretamente sem nenhum operando de registrador geral (`Rt` é
    /// fixo em `11111` no encoding das 3, não lido). Ver {@link Ir64FlagConversionOp} para a
    /// semântica de cada uma.
    record ConvertFlags(
            /// Sub-operação.
            Ir64FlagConversionOp opcode) implements IntegerOp64 {
        @Override public int kind() { return Kind.CONVERT_FLAGS; }
    }

    /// `PACGA Xd, Xn, Xm` (`ARM DDI 0487 C6.2.232`, B19.6 bloco C, `FEAT_PAuth`) — autenticação de
    /// ponteiro GENÉRICA (calcula um código de autenticação, não modifica um ponteiro como
    /// `PACIA`/`PACDA`). Este emulador não modela autenticação de ponteiro de verdade (nenhum
    /// `AUTG*`/`AUTIA`/... verifica o resultado) — decisão registrada na task: decodifica e executa
    /// como placeholder DETERMINÍSTICO (`Xd = 0`, ver o executor), nunca lança.
    record PointerAuthGeneric(
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR`).
            int rd,
            /// Primeiro operando (`Xn`, o ponteiro).
            int rn,
            /// Segundo operando (`Xm`, o modificador).
            int rm) implements IntegerOp64 {
        @Override public int kind() { return Kind.POINTER_AUTH_GENERIC; }
    }

    /// `PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/`AUTDA`/`AUTDB`/`XPACI`/`XPACD` (`ARM DDI
    /// 0487 C6.2.*`, B19.15, `FEAT_PAuth`) — formas de propósito geral que operam IN-PLACE sobre
    /// `Xd` (assinar/autenticar/remover assinatura de um ponteiro). MESMA disciplina de placeholder
    /// de {@link PointerAuthGeneric} (`PACGA`, B19.6 bloco C, precedente direto citado na task): este
    /// emulador não modela autenticação de ponteiro real, então a rota adotada (decisão registrada
    /// na task B19.15) é IDENTIDADE — `Xd` sai com o MESMO valor de entrada (`PAC*`/`AUT*` sempre
    /// "bem-sucedidas", `XPAC*` não têm bits de assinatura para remover). `Xn` (modificador, quando
    /// o encoding tem o campo — a forma "Z" usa modificador zero implícito) e a chave A-vs-B (`op`)
    /// são decodificados só por fidelidade de desmontagem: sob esta rota nenhum dos dois afeta o
    /// resultado observável (documentado, não uma omissão).
    record PointerAuthInPlace(
            /// Sub-operação decodificada (só documentação/desmontagem, ver acima).
            Ir64PointerAuthOp op,
            /// Registrador operando (`Xd`; lido E escrito no hardware real, permanece inalterado
            /// sob identidade — índice `0`-`31`, `31` é `XZR`).
            int rd,
            /// Modificador (`Xn`; `-1` quando o encoding não tem este campo — `XPACI`/`XPACD`,
            /// cujo `Rn` é fixo/reservado no encoding, não um operando real).
            int rn) implements IntegerOp64 {
        @Override public int kind() { return Kind.POINTER_AUTH_IN_PLACE; }
    }

    /// `ABS Xd, Xn` (`ARM DDI 0487`, B19.6 bloco D, `FEAT_CSSC`) — valor absoluto de registrador
    /// geral (diferente do `ABS` vetorial AdvSIMD, já `✅` desde B8.7 — mnemônico homônimo em classe
    /// de encoding diferente). `INT_MIN` não satura: o resultado é o próprio `INT_MIN` (complemento
    /// de dois).
    record AbsGeneral(
            /// Registrador de destino (índice `0`-`31`; `31` é `XZR`).
            int rd,
            /// Registrador de origem (índice `0`-`31`; `31` é `XZR`).
            int rn,
            /// `true` para `X` (64 bits), `false` para `W` (32, zero-estendido).
            boolean wide) implements IntegerOp64 {
        @Override public int kind() { return Kind.ABS_GENERAL; }
    }

    /// `CRC32{B,H,W,X} Wd, Wn, Rm` / `CRC32C{B,H,W,X} Wd, Wn, Rm` (`ARM DDI 0487 C6.2.75/76`,
    /// B19.17, `FEAT_CRC32`) — checksum CRC-32 (polinômio IEEE 802.3) ou CRC-32C (Castagnoli, o
    /// mesmo do iSCSI/`SSE4.2 CRC32C`) sobre registrador geral. `Wd`/`Wn` são SEMPRE `W` (32 bits) —
    /// mesmo na forma `X`, cujo `sf=1` só amplia a LARGURA DO DADO lido de {@link #rm} (`Xm`
    /// completo), não o acumulador nem o destino. A instrução em si não complementa entrada nem
    /// saída (ao contrário do CRC-32 "clássico" de `zlib`/Ethernet) — quem chama fornece `Wn` já
    /// invertido (tipicamente `~0`) se quiser reproduzir o checksum padrão bit a bit.
    record Crc32(
            /// Registrador de destino (índice `0`-`31`; `31` é `WZR`). Sempre 32 bits.
            int rd,
            /// Acumulador de entrada (`Wn`, índice `0`-`31`; `31` é `WZR`). Sempre 32 bits.
            int rn,
            /// Registrador de dado (`Rm`, índice `0`-`31`; `31` é `WZR`/`XZR`).
            int rm,
            /// Largura do dado lido de {@link #rm} em bits: `8`/`16`/`32` (formas `B`/`H`/`W`, `Rm`
            /// lido como `W`) ou `64` (forma `X`, única que lê `Rm` como `X`).
            int dataWidthBits,
            /// `true` para o polinômio Castagnoli (`CRC32C*`), `false` para IEEE 802.3 (`CRC32*`).
            boolean castagnoli) implements IntegerOp64 {
        @Override public int kind() { return Kind.CRC32; }
    }

    /// `SUBP`/`SUBPS` (`ARM DDI 0487`, `FEAT_MTE2`, ARMv8.5-A, B19.14) — subtrai dois ponteiros
    /// IGNORANDO os bits de tag: cada operando é sign-extended a partir dos 56 bits baixos
    /// (`bits[55:0]`, achado real confirmado contra `do_subp` do QEMU — não é uma máscara sem
    /// sinal) ANTES da subtração. `Rn`/`Rm` são `Rn|SP`/`Rm|SP`; `Rd` é `Xd`/`XZR` comum. Sempre 64
    /// bits (não existe forma de 32 bits — `sf` é fixo em `1` no encoding real).
    record SubtractPointer(
            /// `true` para `SUBPS` (atualiza `NZCV` como uma subtração comum); `false` para `SUBP`.
            boolean setFlags,
            /// Registrador de destino (`Xd`/`XZR`).
            int rd,
            /// Primeiro operando (`Rn|SP`).
            int rn,
            /// Segundo operando (`Rm|SP`).
            int rm) implements IntegerOp64 {
        @Override public int kind() { return Kind.SUBTRACT_POINTER; }
    }

    /// `IRG` (`ARM DDI 0487`, `FEAT_MTE2`, ARMv8.5-A, B19.14) — gera uma tag lógica pseudoaleatória
    /// (algoritmo determinístico, ver
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#insertRandomTag} — mesma disciplina de
    /// "determinístico, não criptográfico" da task, necessária para savestates/replay
    /// reprodutíveis) respeitando a máscara de exclusão de `GCR_EL1`/`Rm`, e a insere em `Rn|SP`,
    /// resultado em `Rd|SP`.
    record InsertRandomTag(
            /// Registrador de destino (`Rd|SP`).
            int rd,
            /// Registrador de endereço base (`Rn|SP`).
            int rn,
            /// Máscara de exclusão adicional (`Rm`, comum — combinada por OR com `GCR_EL1.Exclude`).
            int rm) implements IntegerOp64 {
        @Override public int kind() { return Kind.INSERT_RANDOM_TAG; }
    }

    /// `GMI` (`ARM DDI 0487`, `FEAT_MTE2`, ARMv8.5-A, B19.14) — "Tag Mask Insert": marca em
    /// {@link #rd} o bit correspondente à tag ATUAL de {@link #rn} (`bits[59:56]`), OR'ado com a
    /// máscara já acumulada em {@link #rm} — usado por alocadores para não reusar tags adjacentes.
    record TagMaskInsert(
            /// Registrador de destino (`Rd`, comum — máscara resultante).
            int rd,
            /// Registrador cuja tag ATUAL é extraída (`Rn|SP`).
            int rn,
            /// Máscara já acumulada (`Rm`, comum).
            int rm) implements IntegerOp64 {
        @Override public int kind() { return Kind.TAG_MASK_INSERT; }
    }

    /// `SMAX`/`SMIN`/`UMAX`/`UMIN Rd, Rn, Rm` (`ARM DDI 0487`, B19.21, `FEAT_CSSC`) — máximo/mínimo
    /// entre dois registradores gerais, com ou sem sinal (subgrupo "Data-processing (2 source)" de
    /// "Data Processing — Register", `opc2=00`, MESMO campo de opcode de 6 bits que
    /// {@link SubtractPointer}/{@link InsertRandomTag}/{@link TagMaskInsert} já reusam). Diferente
    /// das formas homônimas AdvSIMD (`✅` desde B8.x, espaço de encoding totalmente diferente).
    record MinMaxGeneral(
            /// Sub-operação (`SMAX`/`SMIN`/`UMAX`/`UMIN`).
            Ir64MinMaxOp op,
            /// Registrador de destino (índice `0`-`31`; `31` é sempre `XZR`).
            int dst,
            /// Primeiro operando (`Rn`, índice `0`-`31`; `31` é sempre `XZR`).
            int src1,
            /// Segundo operando (`Rm`, índice `0`-`31`; `31` é sempre `XZR`).
            int src2,
            /// `true` para operação de 64 bits (`X`); `false` para 32 bits (`W`, resultado sempre
            /// zero-estendido para os 64 bits altos do destino).
            boolean wide) implements IntegerOp64 {
        @Override public int kind() { return Kind.MIN_MAX_GENERAL; }
    }
}
