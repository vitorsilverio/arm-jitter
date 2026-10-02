package dev.vitorsilverio.armjitter.ir64;

/// Operações A64 de acesso à memória por registrador geral: load/store (simples, par, literal),
/// exclusivos, `CAS`, atômicas (`FEAT_LSE`), `FEAT_MOPS` (`SET*`/`CPY*`) e as formas de MTE que
/// leem ou gravam tags de alocação.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface MemoryOp64 extends Ir64Op permits MemoryOp64.Load64, MemoryOp64.Store64,
        MemoryOp64.LoadStorePair, MemoryOp64.LoadLiteral64, MemoryOp64.LoadExclusive,
        MemoryOp64.StoreExclusive, MemoryOp64.LoadExclusivePair, MemoryOp64.StoreExclusivePair,
        MemoryOp64.CompareAndSwap, MemoryOp64.CompareAndSwapPair, MemoryOp64.AtomicMemoryOp,
        MemoryOp64.AtomicMemoryOpPair, MemoryOp64.MemorySet, MemoryOp64.MemoryCopy,
        MemoryOp64.MemoryTag, MemoryOp64.MemoryTagMultiple, MemoryOp64.StorePairTag,
        MemoryOp64.MemorySetTagged {

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
            int shiftAmount) implements MemoryOp64 {
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
            int shiftAmount) implements MemoryOp64 {
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
            boolean signExtend) implements MemoryOp64 {
        @Override public int kind() { return Kind.LOAD_STORE_PAIR; }
    }

    /// `LDR (literal)`/`LDRSW (literal)` (`ARM DDI 0487 C6.2.121/134`): carrega um valor de um
    /// endereço relativo ao PC da própria instrução — usado pelo idioma de "literal pool" que
    /// compiladores A64 emitem para constantes grandes. O decoder já resolveu o endereço absoluto
    /// (mesma convenção de {@link IntegerOp64.PcRelative#instructionAddress} — sem viés `+8`, o PC de uma
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
            boolean signExtend) implements MemoryOp64 {
        @Override public int kind() { return Kind.LOAD_LITERAL64; }
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
            boolean acquireRelease) implements MemoryOp64 {
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
            boolean acquireRelease) implements MemoryOp64 {
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
            boolean acquireRelease) implements MemoryOp64 {
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
            boolean acquireRelease) implements MemoryOp64 {
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
            Ir64MemSize size) implements MemoryOp64 {
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
            boolean wide) implements MemoryOp64 {
        @Override public int kind() { return Kind.COMPARE_AND_SWAP_PAIR; }
    }

    /// `LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP` (`ARM DDI 0487
    /// C6.2.{LDADD…SWP}`, B19.1, extensão LSE ARMv8.1 — completa a extensão que a B8.1 tinha
    /// implementado pela metade, só `CAS`/`CASP`). Semântica RMW atômica do ponto de vista do
    /// guest: lê `[Rn]` (`size`), calcula `<operation>(old, Rs)`, escreve o resultado de volta e
    /// grava `old` (zero-estendido) em `Rt`. `Rt==31` (`XZR`) é o alias `ST<op>` — descarta o
    /// resultado lido, o RMW acontece igual. Interpretador single-thread por construção: não
    /// precisa de RMW real de host (mesma nota de {@link CompareAndSwap}). `acquire`/`release`
    /// (bits `A`/`R` do encoding) são NOP observável — carregados no record só para um futuro
    /// emissor nativo, mesmo espírito de {@link LoadExclusive#acquireRelease}. `Rs==31` é `XZR`
    /// (lê `0`); `Rn==31` é `SP`.
    record AtomicMemoryOp(
            /// Registrador com o operando da operação (índice `0`-`31`; `31`=`XZR`, lê `0`).
            int rs,
            /// Registrador de destino do valor antigo lido (índice `0`-`31`; `31`=`XZR`, descarta —
            /// alias `ST<op>`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP` — ver {@link Load64#rn}).
            int rn,
            /// Tamanho da transferência de memória e da operação (byte/half/word/doubleword). A
            /// largura do registrador (`W`/`X`) é derivada: `X` sse `DOUBLEWORD`.
            Ir64MemSize size,
            /// Operação de leitura-modificação-escrita a aplicar.
            Ir64AtomicOp operation,
            /// `true` para as formas `LD<op>A`/`LD<op>AL` (bit `A`=1) — NOP observável.
            boolean acquire,
            /// `true` para as formas `LD<op>L`/`LD<op>AL` (bit `R`=1) — NOP observável.
            boolean release) implements MemoryOp64 {
        @Override public int kind() { return Kind.ATOMIC_MEMORY_OP; }
    }

    /// `LDCLRP`/`LDSETP`/`SWPP` (`ARM DDI 0487`, `FEAT_LSE128`, ARMv9.4-A, B19.25) — versão em PAR
    /// de {@link AtomicMemoryOp} (128 bits em vez de 8/16/32/64). Semântica confirmada contra
    /// `do_atomic128_ld` do QEMU (`target/arm/tcg/translate-a64.c`, mesma revisão fixada por E11):
    /// AO CONTRÁRIO de {@link AtomicMemoryOp} (que separa `rs`=operando de `rt`=destino do valor
    /// antigo) e de {@link CompareAndSwapPair} (que deriva o companheiro como `rs|1`/`rt|1`), aqui
    /// os DOIS registradores do par (`rt`/`rt2`) são os MESMOS que fornecem o operando de 128 bits
    /// E recebem o valor antigo lido de volta — semântica in-place, campos explícitos e
    /// independentes no encoding (sem relação par/ímpar: o hardware real não valida isso, e o
    /// decoder não precisa reproduzir essa restrição arquitetural para decodificar corretamente).
    /// Fórmula por operação, com `(lo,hi)` = par de 128 bits lido de `[Rn]` (`lo` no endereço mais
    /// baixo, little-endian): `CLR` → `novo = old & ~(rt:rt2)`; `SET` → `novo = old | (rt:rt2)`;
    /// `SWP` → `novo = (rt:rt2)`. `Rt`/`Rt2` recebem o par ANTIGO (lido antes da operação), nunca o
    /// resultado. `Rt`/`Rt2` nunca são `XZR` nem iguais entre si — o decoder já recusa esse
    /// encoding (`ARM DDI 0487`/QEMU: `UNALLOCATED`), não o executor. `acquire`/`release` (bits
    /// `A`/`R`) são NOP observável, mesmo espírito de {@link AtomicMemoryOp#acquire}.
    record AtomicMemoryOpPair(
            /// Primeiro registrador do par (operando de entrada E destino do valor antigo lido);
            /// nunca `31` (`XZR`).
            int rt,
            /// Segundo registrador do par; nunca `31` (`XZR`) nem igual a {@link #rt}.
            int rt2,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Operação de leitura-modificação-escrita a aplicar (só {@code CLR}/{@code SET}/
            /// {@code SWP} são alcançáveis por este encoding).
            Ir64AtomicOp operation,
            /// `true` para as formas com bit `A`=1 — NOP observável.
            boolean acquire,
            /// `true` para as formas com bit `R`=1 — NOP observável.
            boolean release) implements MemoryOp64 {
        @Override public int kind() { return Kind.ATOMIC_MEMORY_OP_PAIR; }
    }

    /// Fase de uma instrução `FEAT_MOPS` (`SETP`/`SETM`/`SETE`, `CPYFP`/`CPYFM`/`CPYFE`,
    /// `CPYP`/`CPYM`/`CPYE`, B19.16) — hardware real espera as 3 em sequência (prólogo/principal/
    /// epílogo), interruptível entre fases. Ver {@link MemorySet}/{@link MemoryCopy} para a
    /// simplificação que este emulador aplica (nenhuma interrupção real de instrução).
    enum Ir64MopsPhase {
        PROLOGUE, MAIN, EPILOGUE
    }

    /// `SETP`/`SETM`/`SETE` (`ARM DDI 0487`, `FEAT_MOPS`, ARMv8.8-A, B19.16) — memset acelerado em
    /// 3 fases. Este emulador não modela interrupção de instrução nem precisa "acelerar" nada: a
    /// fase que encontrar {@link #rn} (contador de bytes) diferente de zero faz o preenchimento
    /// INTEIRO num loop e zera o contador — a(s) fase(s) seguinte(s), ao ver o contador já
    /// zerado, são NOP funcional. Qualquer divisão de trabalho entre as 3 fases que produza esse
    /// efeito observável ao final está correta (decisão registrada na task, mesma disciplina de
    /// {@link IntegerOp64.PointerAuthGeneric}/{@link IntegerOp64.Crc32}: resultado determinístico e documentado em vez de
    /// modelar o protocolo real de retomada). Sempre 64 bits (`sz` fixo no encoding — não existe
    /// forma de 32 bits). Os bits `unpriv`/`nontemp` do encoding não são modelados (sem MMU de
    /// permissão nem cache neste emulador para os hints afetarem). `PSTATE.NZCV` é sempre gravado
    /// como `0b0010` ao final da fase que faz o trabalho (Option B da arquitetura, "cópia
    /// completa, sentido direto") — irrelevante para a fase seguinte, que só olha {@link #rn}.
    record MemorySet(
            /// Fase (`SETP`=PROLOGUE, `SETM`=MAIN, `SETE`=EPILOGUE).
            Ir64MopsPhase phase,
            /// Registrador de endereço de destino (`Xd`, índice `0`-`31`; `31` é `XZR` — MOPS não
            /// tem forma de endereço-base em `SP`).
            int rd,
            /// Registrador de contador de bytes (`Xn`, índice `0`-`31`; `31` é `XZR`).
            int rn,
            /// Registrador com o byte de preenchimento nos 8 bits baixos (`Xs`, índice `0`-`31`;
            /// `31` é `XZR`, preenche com `0`).
            int rs) implements MemoryOp64 {
        @Override public int kind() { return Kind.MEMORY_SET; }
    }

    /// `CPYFP`/`CPYFM`/`CPYFE` (sempre para frente) e `CPYP`/`CPYM`/`CPYE` (direção decidida por
    /// sobreposição, como `memmove`) — `FEAT_MOPS`, ARMv8.8-A, B19.16. Mesma simplificação de
    /// {@link MemorySet}: a fase que encontrar {@link #rn} diferente de zero copia tudo de uma vez
    /// (bytewise, escolhendo a direção do loop pela mesma regra de `memmove` quando
    /// {@link #forwardOnly} é `false`) e zera o contador; a(s) fase(s) seguinte(s) são NOP
    /// funcional. Sempre 64 bits. O campo `options` do encoding não é modelado (mesma razão de
    /// {@link MemorySet}).
    record MemoryCopy(
            /// Fase (`CPYxP`=PROLOGUE, `CPYxM`=MAIN, `CPYxE`=EPILOGUE).
            Ir64MopsPhase phase,
            /// `true` para `CPYFP`/`CPYFM`/`CPYFE` (sempre para frente — software garante que as
            /// regiões não se sobrepõem de um jeito que exija cópia reversa); `false` para
            /// `CPYP`/`CPYM`/`CPYE` (direção decidida por sobreposição, como `memmove`).
            boolean forwardOnly,
            /// Registrador de endereço de destino (`Xd`, índice `0`-`31`; `31` é `XZR`).
            int rd,
            /// Registrador de endereço de origem (`Xs`, índice `0`-`31`; `31` é `XZR`).
            int rs,
            /// Registrador de contador de bytes (`Xn`, índice `0`-`31`; `31` é `XZR`).
            int rn) implements MemoryOp64 {
        @Override public int kind() { return Kind.MEMORY_COPY; }
    }

    /// Direção de {@link MemoryTag} (`FEAT_MTE2`, B19.14).
    enum Ir64MemoryTagOperation {
        LOAD, STORE
    }

    /// `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G` (`ARM DDI 0487`, `FEAT_MTE2`, ARMv8.5-A, B19.14) — grava ou
    /// lê a tag de alocação de 4 bits de 1 ou 2 granules de 16 bytes. Decisão de escopo registrada
    /// na task: este emulador modela um armazenamento de tags FUNCIONAL (indexado por endereço,
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64Core#memoryTag}/{@code #setMemoryTag}), mas
    /// **não** implementa a checagem de tag em `LDR`/`STR` comuns nem a exceção de "Tag Check
    /// Fault" (G8, decisão consciente — equivalente a nunca configurar `TCR_ELx` para modo
    /// síncrono do ponto de vista observável). Para {@link Ir64MemoryTagOperation#STORE}, o
    /// REGISTRADOR de tag ({@link #rt}) é resolvido como `Rt|SP` (a tag vem dos bits `[59:56]` do
    /// PONTEIRO em `Rt`, não do endereço de destino); para {@link Ir64MemoryTagOperation#LOAD}, é
    /// um `Xt`/`XZR` comum (confirmado byte a byte contra `do_STG`/`trans_LDG`, QEMU
    /// `target/arm/tcg/translate-a64.c`, revisão fixada por E11 — ver `## Resultado` da task).
    /// {@link #zeroData} (`STZG`/`STZ2G`) também zera os `16*`{@link #granules} bytes de DADOS no
    /// endereço de acesso (sem arredondar ao granule — o hardware real exige alinhamento prévio,
    /// que este emulador não impõe por não modelar falha). {@link #granules}`==2` cobre o SEGUNDO
    /// granule em `endereço+16`, com a MESMA tag do primeiro.
    record MemoryTag(
            /// {@link Ir64MemoryTagOperation#LOAD} (`LDG`) ou {@link Ir64MemoryTagOperation#STORE}
            /// (`STG`/`STZG`/`ST2G`/`STZ2G`).
            Ir64MemoryTagOperation operation,
            /// `true` para `STZG`/`STZ2G` (zera dados além de gravar a tag); sempre `false` para
            /// `LOAD`.
            boolean zeroData,
            /// `1` (`STG`/`STZG`/`LDG`) ou `2` (`ST2G`/`STZ2G`) granules de 16 bytes afetados.
            int granules,
            /// Registrador de tag/valor (`Rt|SP` em `STORE`, `Rt`/`XZR` comum em `LOAD`).
            int rt,
            /// Registrador base de endereço (`Rn|SP`, índice `31`=`SP` — nunca `XZR` em MTE).
            int rn,
            /// Modo de endereçamento (`OFFSET`/`PRE_INDEX`/`POST_INDEX` — nunca `REGISTER_OFFSET`,
            /// que MTE não tem forma alguma com deslocamento por registrador).
            Ir64AddressingMode addressingMode,
            /// Deslocamento já escalado por 16 bytes (granule), com sinal.
            long immediate) implements MemoryOp64 {
        @Override public int kind() { return Kind.MEMORY_TAG; }
    }

    /// Direção de {@link MemoryTagMultiple} (`FEAT_MTE2`, B19.14).
    enum Ir64MemoryTagMultipleOperation {
        LOAD_TAGS, STORE_TAGS, STORE_ZERO_DATA_TAGS
    }

    /// `STGM`/`LDGM`/`STZGM` (`ARM DDI 0487`, `FEAT_MTE2`, ARMv8.5-A, B19.14) — formas "multiple":
    /// operam num BLOCO de tags inteiro por um único registrador de 64 bits, sem imediato nem
    /// writeback (`Rn|SP` sozinho, sem offset — `imm` é sempre `0` no encoding real). Simplificação
    /// documentada (mesmo espírito do resto da task): {@link Ir64MemoryTagMultipleOperation#LOAD_TAGS}/
    /// {@link Ir64MemoryTagMultipleOperation#STORE_TAGS} usam um bloco FIXO de 256 bytes (16
    /// granules — `GM_BLOCKSIZE=6` real, o único caso em que o hardware real não precisa de shift
    /// dependente de endereço dentro do bloco, ver `mte_helper.c` `HELPER(ldgm)`/`HELPER(stgm)` na
    /// revisão fixada por E11); {@link Ir64MemoryTagMultipleOperation#STORE_ZERO_DATA_TAGS}
    /// (`STZGM`) usa um bloco FIXO de 64 bytes (4 granules, um tamanho de bloco DC ZVA real comum)
    /// em vez do `DCZID_EL0.BS` de verdade (que este core anuncia como `DC ZVA` DESABILITADO, ver
    /// {@link dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId#DCZID_EL0} — decisão
    /// consciente, G8) e grava em CADA granule do bloco a tag nos 4 bits BAIXOS de {@link #rt}
    /// (não `bits[59:56]` — achado real: `HELPER(stzgm_tags)` usa `val & 0xf` diretamente, ao
    /// contrário de `STG`).
    record MemoryTagMultiple(
            /// Operação (ver enum).
            Ir64MemoryTagMultipleOperation operation,
            /// Registrador de valor: bitmap de 64 bits (`STGM`/`LDGM`) ou tag nos 4 bits baixos
            /// (`STZGM`) — sempre `Rt`/`XZR` comum, nunca `Rt|SP`.
            int rt,
            /// Registrador base de endereço (`Rn|SP`).
            int rn) implements MemoryOp64 {
        @Override public int kind() { return Kind.MEMORY_TAG_MULTIPLE; }
    }

    /// `STGP` (`ARM DDI 0487`, `FEAT_MTE2`, ARMv8.5-A, B19.14) — como `STP` de 64 bits (grava
    /// {@link #rt}/{@link #rt2} no par de doublewords em `[Rn, #imm]`), **e também** grava a tag de
    /// alocação do PRÓPRIO endereço de destino (`Rn+imm`, não de `Rt`/`Rt2` — achado real,
    /// confirmado contra `trans_STGP`: `gen_helper_stg(env, dirty_addr, dirty_addr, ...)`, o
    /// segundo argumento é o MESMO endereço, não um registrador de dado separado) no granule de 16
    /// bytes endereçado. Reaproveita {@link Ir64AddressingMode} como {@link LoadStorePair}, mas é
    /// um record PRÓPRIO (não uma extensão de {@link LoadStorePair}, que já é API pública
    /// publicada — G3) porque não existe forma de LOAD nem largura configurável aqui (sempre
    /// 64 bits, offset escalado por 16, não por 8).
    record StorePairTag(
            /// Primeiro registrador de dado (`Xt`).
            int rt,
            /// Segundo registrador de dado (`Xt2`).
            int rt2,
            /// Registrador base de endereço (`Rn|SP`).
            int rn,
            /// Modo de endereçamento (`OFFSET`/`PRE_INDEX`/`POST_INDEX`).
            Ir64AddressingMode addressingMode,
            /// Deslocamento já escalado por 16 bytes (granule), com sinal.
            long immediate) implements MemoryOp64 {
        @Override public int kind() { return Kind.STORE_PAIR_TAG; }
    }

    /// `SETGP`/`SETGM`/`SETGE` (`ARM DDI 0487`, `FEAT_MTE2`+`FEAT_MOPS`, ARMv8.8-A, B19.14) — irmãs
    /// de {@link MemorySet} (`SETP`/`SETM`/`SETE`, B19.16) que TAMBÉM gravam a tag de alocação de
    /// {@link #rd} (tag extraída dos bits `[59:56]`) em cada granule de 16 bytes tocado pelo
    /// preenchimento — mesma simplificação de fase única de {@link MemorySet} (a fase que encontra
    /// {@link #rn} diferente de zero preenche TUDO — dados E tags — de uma vez). {@link #rd} é
    /// `Xd`/`XZR` comum, mesma convenção de {@link MemorySet#rd} (MOPS não tem forma de endereço-
    /// base em `SP`).
    record MemorySetTagged(
            /// Fase (`SETGP`=PROLOGUE, `SETGM`=MAIN, `SETGE`=EPILOGUE).
            Ir64MopsPhase phase,
            /// Registrador de endereço de destino (`Xd`/`XZR` — a MESMA tag gravada em cada granule
            /// vem daqui, ao contrário de {@link MemorySet}, que não grava tag nenhuma).
            int rd,
            /// Registrador de contador de bytes (`Xn`/`XZR`).
            int rn,
            /// Registrador com o byte de preenchimento nos 8 bits baixos (`Xs`/`XZR`).
            int rs) implements MemoryOp64 {
        @Override public int kind() { return Kind.MEMORY_SET_TAGGED; }
    }
}
