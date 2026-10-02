package dev.vitorsilverio.armjitter.ir64;

/// Operações A64 de ponto flutuante escalar: aritmética, comparação, conversão, arredondamento,
/// `FMOV` e os load/store de registrador SIMD&amp;FP.
///
/// Sub-interface selada de {@link Ir64Op} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface FpOp64 extends Ir64Op permits FpOp64.Load64, FpOp64.Store64,
        FpOp64.LoadStorePair, FpOp64.LoadLiteral64, FpOp64.Alu, FpOp64.MoveImmediate,
        FpOp64.Compare, FpOp64.Convert, FpOp64.MultiplyAdd, FpOp64.ConditionalSelect,
        FpOp64.ConditionalCompare, FpOp64.Round, FpOp64.RoundRangeLimited, FpOp64.IntegerConvert,
        FpOp64.GeneralRegisterMove, FpOp64.JavascriptConvert, FpOp64.HighHalfMove,
        FpOp64.ConvertToBf16, FpOp64.HalfPrecisionGeneralRegisterMove, FpOp64.ConvertHalfPrecision {

    /// `LDR` SIMD&FP registrador-imediato (`ARM DDI 0487 C4.1.5`, `V=1` — B8.13): mesmas 4 formas
    /// de endereçamento de {@link MemoryOp64.Load64}, mas o destino é `V<t>` (banco
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters}), tamanhos `B`/`H`/`S`/`D`/`Q`
    /// (só {@link MemoryOp64.Load64} tem `W`/`X`; aqui a escrita é sempre "destructive" — zera o resto do
    /// registro, exceto `Q` onde os 128 bits inteiros já são o resultado). Sem sinal — SIMD&FP não
    /// tem forma equivalente a `LDRSB`/`LDRSH`/`LDRSW`. `Rn` é SEMPRE `Rn|SP` (nunca `XZR`, mesma
    /// convenção de {@link MemoryOp64.Load64#rn}).
    record Load64(
            /// Registrador de destino `V<t>` (índice `0`-`31`).
            int vt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Tamanho da transferência/registro (`B`/`H`/`S`/`D`/`Q`).
            Ir64FpMemSize size,
            /// Modo de endereçamento.
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes — ver {@link MemoryOp64.Load64#immediate}.
            long immediate,
            /// Registrador de deslocamento, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `-1` nas demais formas.
            int rm,
            /// Extensão de {@link #rm}, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `null` nas demais formas.
            Ir64ExtendType extendType,
            /// Quantidade de deslocamento, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}.
            int shiftAmount) implements FpOp64 {
        @Override public int kind() { return Kind.FP_LOAD64; }
    }

    /// `STR` SIMD&FP registrador-imediato — mesmo formato de {@link Load64}, fonte em vez de
    /// destino.
    record Store64(
            /// Registrador de origem `V<t>` (índice `0`-`31`).
            int vt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Tamanho da transferência/registro (`B`/`H`/`S`/`D`/`Q`).
            Ir64FpMemSize size,
            /// Modo de endereçamento.
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes.
            long immediate,
            /// Registrador de deslocamento, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `-1` nas demais formas.
            int rm,
            /// Extensão de {@link #rm}, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}; `null` nas demais formas.
            Ir64ExtendType extendType,
            /// Quantidade de deslocamento, válido só em
            /// {@link Ir64AddressingMode#REGISTER_OFFSET}.
            int shiftAmount) implements FpOp64 {
        @Override public int kind() { return Kind.FP_STORE64; }
    }

    /// `LDP`/`STP` SIMD&FP (`ARM DDI 0487 C6.2.127`/`C6.2.338`, `V=1` — B8.13): mesmo idioma de
    /// {@link MemoryOp64.LoadStorePair}, mas os 2 registradores são `V<t>`/`V<t2>` e o tamanho do par é
    /// `S`/`D`/`Q` (nunca `B`/`H` — não existe `LDP` de byte/halfword) e sem forma com sinal (ao
    /// contrário de {@link MemoryOp64.LoadStorePair#signExtend}, `LDPSW`).
    record LoadStorePair(
            /// `true` para `LDP`, `false` para `STP`.
            boolean load,
            /// Primeiro registrador transferido `V<t>` (índice `0`-`31`).
            int vt,
            /// Segundo registrador transferido `V<t2>` (índice `0`-`31`).
            int vt2,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Tamanho de cada elemento do par (`SINGLE`/`DOUBLE`/`QUAD` — nunca `BYTE`/`HALF`).
            Ir64FpMemSize size,
            /// Modo de endereçamento (`OFFSET`/`PRE_INDEX`/`POST_INDEX` — nunca
            /// `REGISTER_OFFSET`).
            Ir64AddressingMode addressingMode,
            /// Deslocamento imediato em bytes, já escalado pelo decoder (`imm7` × `size.bytes()`).
            long immediate) implements FpOp64 {
        @Override public int kind() { return Kind.FP_LOAD_STORE_PAIR; }
    }

    /// `LDR (literal)` SIMD&FP (`ARM DDI 0487 C6.2.122`, `V=1` — B8.13): mesmo idioma de
    /// {@link MemoryOp64.LoadLiteral64}, mas o destino é `V<t>` e o tamanho é `S`/`D`/`Q` (campo `opc` de 2
    /// bits — `11` reservado, nunca alcança aqui).
    record LoadLiteral64(
            /// Registrador de destino `V<t>` (índice `0`-`31`).
            int vt,
            /// Endereço absoluto já resolvido pelo decoder (mesma convenção de
            /// {@link MemoryOp64.LoadLiteral64#address}).
            long address,
            /// Tamanho do registro (`SINGLE`/`DOUBLE`/`QUAD`).
            Ir64FpMemSize size) implements FpOp64 {
        @Override public int kind() { return Kind.FP_LOAD_LITERAL64; }
    }

    /// Sub-operação de {@link Alu} — leitura literal do épico B6.5 ("FMOV/FADD/FMUL/FDIV/
    /// FCMP/FCVT", task B6.5.2): `SQRT`/`MLA`/`MLS`/`NMUL` (existentes no precedente VFP32,
    /// {@code VfpOp.VfpOperation}) ficam FORA de propósito — não citados nesta leitura, ver
    /// Armadilhas da task. `MOV` é a forma registrador↔registrador de `FMOV` (cópia de bits, sem
    /// aritmética) — não confundir com {@link MoveImmediate} (`FMOV` imediato).
    ///
    /// B8.4 estende com `NMUL`/`SQRT` (unárias/binárias que a B6.5.2 tinha deixado de fora de
    /// propósito, não por não existirem em A64 — `FMLA`/`FMLS` continuam de fora: essas formas
    /// escalares fundidas vivem no espaço Advanced SIMD escalar `neon-dp.decode`, não em
    /// "Floating-point data-processing", ver Não inclui da task) e `MAX`/`MIN`/`MAXNM`/`MINNM`
    /// (binárias novas, sem equivalente no VFP32 — `FMAX`/`FMIN` só existem a partir do A64).
    enum Fp64Operation {
        ADD, SUB, MUL, DIV, NEG, ABS, MOV, NMUL, SQRT, MAX, MIN, MAXNM, MINNM
    }

    /// `FADD`/`FSUB`/`FMUL`/`FDIV`/`FNEG`/`FABS`/`FMOV` registrador↔registrador (`ARM DDI 0487
    /// C6.2` — seção exata a confirmar em B6.5.3, ver Armadilhas da task B6.5.2). Sem campo de
    /// condição (D1 da task: nenhum `Ir64Op` de dado carrega condição, só {@link BranchOp64.Branch64}).
    record Alu(
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
            int vm) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_ALU; }
    }

    /// `FMOV Sd, #imm`/`FMOV Dd, #imm` (`ARM DDI 0487 C6.2` — seção a confirmar em B6.5.3): grava
    /// um imediato de ponto flutuante já expandido (`VFPExpandImm`-equivalente, decodificado pelo
    /// DECODER — B6.5.3, não aqui) no registrador de destino.
    record MoveImmediate(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Bits crus já expandidos pelo decoder: 32 bits válidos quando `!doublePrecision`
            /// (bits altos ignorados), 64 bits completos quando `doublePrecision`.
            long immediateBits) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_MOVE_IMMEDIATE; }
    }

    /// `FCMP`/`FCMPE` (`ARM DDI 0487 C6.2` — seção a confirmar em B6.5.3), com ou sem comparação
    /// com zero. **Escreve `PSTATE.NZCV` diretamente** (diferente de {@code VfpOp.Compare} no
    /// mundo de 32 bits, que escreve um `FpscrRegister.NZCV` separado, exigindo um segundo passo
    /// `VMRS APSR_nzcv` para chegar aos flags que os branches condicionais leem — em A64 não há
    /// registro de flags de FP separado do `PSTATE`, ver Fatos de referência #1 da task).
    record Compare(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// `true` para as formas `FCMP(E) Vn, #0.0` (compara com zero em vez de `vm`).
            boolean compareWithZero,
            /// `true` para `FCMPE` (bit E do encoding: sinaliza operação inválida também para NaN
            /// silencioso). Sem efeito observável adicional neste core — que não modela traps de
            /// exceção de ponto flutuante, mesmo precedente de {@code VfpOp.Compare} no mundo de
            /// 32 bits — carregado só para fidelidade ao encoding.
            boolean signalOnQuietNaN,
            /// Primeiro operando comparado (`Vn`, não `vd`: esta instrução nunca escreve um
            /// registrador FP).
            int vn,
            /// Segundo operando da comparação (`Vm`), ignorado quando {@link #compareWithZero}.
            int vm) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_COMPARE; }
    }

    /// Sub-operação de {@link Convert} — leitura literal de "FCVT": só conversão float↔float
    /// de precisão. `SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` (conversão inteiro↔float) são mnemônicos
    /// DIFERENTES, fora do escopo desta task (ver Armadilhas da task B6.5.2).
    enum Fp64Conversion {
        F32_TO_F64, F64_TO_F32
    }

    /// `FCVT` (`ARM DDI 0487 C6.2` — seção a confirmar em B6.5.3): conversão de precisão
    /// float↔float, sem mudança de valor além do arredondamento IEEE 754 (widening exato
    /// F32→F64; narrowing corretamente arredondado F64→F32).
    record Convert(
            /// Direção da conversão.
            Fp64Conversion conversion,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Registrador de origem (índice `0`-`31`, `V<n>`).
            int vm) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_CONVERT; }
    }

    /// `FMADD`/`FMSUB`/`FNMADD`/`FNMSUB` (`ARM DDI 0487 C6.2` "Floating-point data-processing,
    /// 3 source", B8.4): multiplicação-acumulação FUNDIDA (arredondamento único, `Math.fma`) — as
    /// 4 formas são a MESMA operação `Vd = fma(±Vn, Vm, ±Va)` com sinais diferentes em `Vn`/`Va`
    /// (confirmado contra `do_fmadd`/`translate-a64.c` real do QEMU: `neg_a`/`neg_n` como
    /// parâmetros booleanos do mesmo helper para as 4 instruções — nenhuma delas nega `Vm`).
    /// `FMADD`=(false,false), `FNMADD`=(true,true), `FMSUB`=(false,true), `FNMSUB`=(true,false).
    /// A negação acontece no BIT DE SINAL (não `0-x`), mesma armadilha de {@link Fp64Operation#NEG} —
    /// preserva o sinal de um `NaN` de entrada em vez de canonizá-lo.
    record MultiplyAdd(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// `true` inverte o sinal de `Va` antes da soma (`FNMADD`/`FNMSUB`).
            boolean negateAddend,
            /// `true` inverte o sinal de `Vn` antes da multiplicação (`FMSUB`/`FNMSUB`).
            boolean negateProduct,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Primeiro fator da multiplicação.
            int vn,
            /// Segundo fator da multiplicação.
            int vm,
            /// Acumulador (somado — ou subtraído, ver {@link #negateAddend} — ao produto).
            int va) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_MULTIPLY_ADD; }
    }

    /// `FCSEL` (`ARM DDI 0487 C6.2.75`, "Floating-point conditional select", B8.5) — seleciona
    /// entre {@link #vn} (condição verdadeira) e {@link #vm} (falsa), sem aritmética. Espelho FP
    /// de {@link IntegerOp64.ConditionalSelect}, mas só a forma direta (`FCSEL` não tem as variantes
    /// `CSINC`/`CSINV`/`CSNEG` de {@link Ir64ConditionalSelectOp} — não existem para FP).
    record ConditionalSelect(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Operando escolhido quando {@link #condition} é verdadeira.
            int vn,
            /// Operando escolhido quando {@link #condition} é falsa.
            int vm,
            /// Condição avaliada contra `PSTATE.{N,Z,C,V}`.
            Ir64Condition condition) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_CONDITIONAL_SELECT; }
    }

    /// `FCCMP`/`FCCMPE` (`ARM DDI 0487 C6.2.74`, "Floating-point conditional compare", B8.5) —
    /// espelho FP de {@link IntegerOp64.ConditionalCompare}: quando {@link #condition} é verdadeira, `NZCV`
    /// recebe o resultado de comparar {@link #vn}/{@link #vm} (MESMA tabela de resultado que
    /// {@link Compare} — unordered/equal/less/greater); quando falsa, os 4 bits crus de
    /// {@link #nzcv} substituem `NZCV` diretamente, SEM ler {@link #vn}/{@link #vm} (mesma
    /// armadilha de {@link IntegerOp64.ConditionalCompare}, Testes mínimos daquela task).
    record ConditionalCompare(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// `true` para `FCCMPE` (bit `E`) — sem efeito observável adicional neste core, mesmo
            /// precedente de {@link Compare#signalOnQuietNaN}.
            boolean signalOnQuietNaN,
            /// Primeiro operando da comparação.
            int vn,
            /// Segundo operando da comparação.
            int vm,
            /// Condição avaliada contra `PSTATE.{N,Z,C,V}`.
            Ir64Condition condition,
            /// Os 4 bits crus `N:Z:C:V` do encoding, usados como `NZCV` quando {@link #condition}
            /// é falsa.
            int nzcv) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_CONDITIONAL_COMPARE; }
    }

    /// Direção de arredondamento de {@link Round} e (no sentido float→inteiro) de
    /// {@link IntegerConvert}.
    enum Fp64RoundingDirection {
        NEAREST_TIES_EVEN, TOWARD_POSITIVE_INFINITY, TOWARD_NEGATIVE_INFINITY, TOWARD_ZERO,
        NEAREST_TIES_AWAY
    }

    /// `FRINTN`/`FRINTP`/`FRINTM`/`FRINTZ`/`FRINTA`/`FRINTX`/`FRINTI` (`ARM DDI 0487 C6.2`
    /// "Floating-point data-processing (1 source)", B8.5) — arredonda para um valor integral
    /// MANTENDO o resultado em ponto flutuante (diferente de {@link IntegerConvert}, que
    /// converte para um registrador geral). `FRINTX`/`FRINTI` usam a MESMA direção de `FRINTN`
    /// (`NEAREST_TIES_EVEN`) — este core não modela `FPCR.RMode` em A64 (pendência documentada
    /// desde B6.5.1), então "modo de arredondamento corrente" (`FRINTI`) degenera para o default,
    /// e a única diferença real de `FRINTX` no hardware (sinalizar `FPSR.IXC` quando o resultado
    /// não é exato) não é observável neste core, que não modela `FPSR` em nenhuma outra operação
    /// de A64.
    record Round(
            /// Direção de arredondamento (`FRINTN`/`X`/`I`→`NEAREST_TIES_EVEN`,
            /// `FRINTP`→`TOWARD_POSITIVE_INFINITY`, `FRINTM`→`TOWARD_NEGATIVE_INFINITY`,
            /// `FRINTZ`→`TOWARD_ZERO`, `FRINTA`→`NEAREST_TIES_AWAY`).
            Fp64RoundingDirection direction,
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Registrador de origem.
            int vn) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_ROUND; }
    }

    /// `FRINT32Z`/`FRINT32X`/`FRINT64Z`/`FRINT64X` (`ARM DDI 0487` "Floating-point data-processing
    /// (1 source)", B19.18, `FEAT_FRINTTS`) — arredonda para um valor integral (mantendo o
    /// resultado em ponto flutuante, MESMA convenção de {@link Round}) e depois SATURA o
    /// resultado para caber no alcance de um inteiro de `32` ou `64` bits COM SINAL: qualquer valor
    /// (incluindo `±Infinito`, mas não `NaN`) fora de `[-2^rangeBits, 2^rangeBits]` vira exatamente
    /// `±2^rangeBits` (como ponto flutuante — `2^rangeBits` em si NÃO satura, é o próprio limite
    /// válido, achado medido contra o algoritmo real do QEMU: `int32_min/max_as_float32` são
    /// literalmente `∓2^31`, não `∓(2^31-1)`). `X` usa `FPCR.RMode` no hardware real; este core não
    /// modela `RMode` (decisão herdada de B8.5/B8.15), então `X` degenera para
    /// `NEAREST_TIES_EVEN`, MESMA simplificação de {@link Round#direction()} para
    /// `FRINTX`/`FRINTI`. `Z` SEMPRE usa `TOWARD_ZERO`, nunca `FPCR.RMode` (isso é real no
    /// hardware, não uma simplificação).
    record RoundRangeLimited(
            /// `TOWARD_ZERO` para as formas `Z` (`FRINT32Z`/`FRINT64Z`); `NEAREST_TIES_EVEN` para
            /// as formas `X` (`FRINT32X`/`FRINT64X`, simplificação herdada de `FPCR.RMode` não
            /// modelado).
            Fp64RoundingDirection direction,
            /// `true`: satura para o alcance de um inteiro de 64 bits com sinal (`FRINT64*`,
            /// `±2^63`). `false`: 32 bits (`FRINT32*`, `±2^31`).
            boolean rangeIs64Bit,
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Registrador de origem.
            int vn) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_ROUND_RANGE_LIMITED; }
    }

    /// `SCVTF`/`UCVTF`/`FCVTNS`/`FCVTNU`/`FCVTPS`/`FCVTPU`/`FCVTMS`/`FCVTMU`/`FCVTZS`/`FCVTZU`/
    /// `FCVTAS`/`FCVTAU` (forma REGISTRADOR-GERAL, `ARM DDI 0487 C6.2`, B8.5): conversão entre um
    /// registrador GERAL (`Wn`/`Xn`) e um registrador FP escalar (`Sn`/`Dn`), nos dois sentidos.
    /// Um único record cobre TRÊS classes reais do encoding (mesmo padrão de
    /// {@link IntegerOp64.ConditionalCompare} unificando registrador/imediato): "Conversion between
    /// floating-point and integer (general register)" ({@link #fixedPointFractionBits}`=0`, os 5
    /// modos de arredondamento por opcode) e "Conversion between floating-point and fixed-point
    /// (general register)" ({@link #fixedPointFractionBits}`>0`, só `SCVTF`/`UCVTF`/`FCVTZS`/
    /// `FCVTZU` existem nesse grupo — `Z` é literal no nome, arredondamento SEMPRE
    /// `TOWARD_ZERO` no sentido float→inteiro, e o sentido inteiro→float é SEMPRE
    /// `NEAREST_TIES_EVEN` nos dois grupos, nunca configurável).
    record IntegerConvert(
            /// `true`: `Wn`/`Xn` (inteiro) → `Sd`/`Dd` (float) — `SCVTF`/`UCVTF`. `false`:
            /// `Sn`/`Dn` (float) → `Wd`/`Xd` (inteiro) — `FCVTxS`/`FCVTxU`.
            boolean toFloat,
            /// `true` para as formas com sinal (`SCVTF`/`FCVTxS`); `false` sem sinal
            /// (`UCVTF`/`FCVTxU`).
            boolean signed,
            /// Direção de arredondamento no sentido float→inteiro; IGNORADA no sentido
            /// inteiro→float (sempre `NEAREST_TIES_EVEN`, ver Javadoc da classe).
            Fp64RoundingDirection rounding,
            /// `true` para registrador FP de precisão dupla (`Dn`/`Dd`), `false` simples
            /// (`Sn`/`Sd`).
            boolean doublePrecision,
            /// `true` para registrador geral de 64 bits (`Xn`/`Xd`), `false` de 32 (`Wn`/`Wd`).
            boolean wide,
            /// Bits fracionários do ponto fixo: `0` nas formas inteiras puras (`SCVTF`/`UCVTF`/
            /// `FCVTNS`/etc. sem escala); `1`-`32` (`!wide`) ou `1`-`64` (`wide`) nas formas de
            /// ponto fixo com escala (`SCVTF`/`UCVTF`/`FCVTZS`/`FCVTZU` com `#fbits`).
            int fixedPointFractionBits,
            /// Registrador FP (índice `0`-`31`, `V<n>`) — destino quando {@link #toFloat}, origem
            /// senão.
            int fpReg,
            /// Registrador geral (índice `0`-`31`) — origem quando {@link #toFloat}, destino
            /// senão.
            int gpReg) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_INTEGER_CONVERT; }
    }

    /// `FMOV` entre registrador geral e FP escalar SEM conversão de valor — cópia CRUA de bits
    /// (`FMOV Wd,Sn`/`FMOV Sd,Wn`/`FMOV Xd,Dn`/`FMOV Dd,Xn`, B8.5). **Não inclui** as formas que
    /// tocam a metade alta de um registrador de 128 bits (`FMOV Xd,Vn.D[1]`/`FMOV Vd.D[1],Xn`) —
    /// este core só armazena `D`/`S` por `V<n>` (decisão de B6.5.1, sem os 64 bits altos), que
    /// depende do armazenamento de 128 bits que só chega com AdvSIMD (B8.6+).
    record GeneralRegisterMove(
            /// `true`: `Xn`/`Wn` → `Vd` (bits crus). `false`: `Vn` → `Xd`/`Wd` (bits crus).
            boolean toFloat,
            /// `true` para 64 bits (`Xn`/`Dd` ou `Dn`/`Xd`), `false` para 32 (`Wn`/`Sd` ou
            /// `Sn`/`Wd`).
            boolean wide,
            /// Registrador FP (índice `0`-`31`, `V<n>`).
            int fpReg,
            /// Registrador geral (índice `0`-`31`).
            int gpReg) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_GENERAL_REGISTER_MOVE; }
    }

    /// `FJCVTZS Wd, Dn` (`ARM DDI 0487 C6.2.92`, B19.29, `FEAT_JSCVT`) — converte `Dn` (sempre
    /// precisão dupla, nunca simples) para inteiro de 32 bits com sinal, truncando em direção a
    /// zero: EXATAMENTE `ToInt32` do ECMAScript. Difere de {@link IntegerConvert} com
    /// {@code rounding=TOWARD_ZERO}/{@code signed=true} em dois pontos — por isso é um record
    /// próprio em vez de reaproveitar aquele: (1) `NaN`/infinito produzem `0` e o overflow reduz
    /// MÓDULO 2³² (B22.7 corrigiu a versão original, que devolvia `0`), nunca o valor de saturação
    /// (`INT32_MAX`/`INT32_MIN`); (2) `PSTATE.Z` recebe um sinalizador de EXATIDÃO da conversão
    /// (`1` se {@link #rn} já era um inteiro de 32 bits representável sem parte fracionária nem
    /// overflow e não é `-0.0`, `0` caso contrário) — não a semântica comum de "resultado é zero"
    /// (um valor de entrada `+0.0` exato seta `Z=1`, mas um overflow que produz `Wd=0` seta `Z=0`,
    /// já que a conversão NÃO foi exata). `N`/`C`/`V` são sempre zerados.
    record JavascriptConvert(
            /// Registrador geral de destino (`Wd`, índice `0`-`31`; `31` é `WZR`). Sempre 32 bits.
            int rd,
            /// Registrador FP de origem (`Dn`, índice `0`-`31`). Sempre precisão dupla.
            int rn) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_JAVASCRIPT_CONVERT; }
    }

    /// `FMOV Xd, Vn.D[1]` / `FMOV Vd.D[1], Xn` (`ARM DDI 0487`, B19.6 bloco F) — move entre
    /// registrador geral de 64 bits e a metade ALTA (bits `127:64`) de um registrador vetorial de
    /// 128 bits. Diferente de {@link GeneralRegisterMove} (que sempre usa a metade BAIXA e, no
    /// sentido GPR→FP, ZERA o resto do registrador): aqui o sentido FP→GPR simplesmente LÊ a
    /// metade alta, e o sentido GPR→FP PRESERVA a metade baixa (exceção à escrita destrutiva normal
    /// de A64 — ver Armadilhas da task).
    record HighHalfMove(
            /// `true`: `Xn` → `Vd.D[1]` (preserva `Vd.D[0]`). `false`: `Vn.D[1]` → `Xd`.
            boolean toFloat,
            /// Registrador FP (índice `0`-`31`, `V<n>`).
            int fpReg,
            /// Registrador geral (índice `0`-`31`).
            int gpReg) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_HIGH_HALF_MOVE; }
    }

    /// `BFCVT` (`FEAT_BF16`, B19.7) — converte `Sn` (`binary32`) para `bf16` (16 bits) na metade
    /// baixa de `Vd`, zerando o resto do registrador ("SIMD&FP destructive write", mesma disciplina
    /// de {@link Convert}). Record PRÓPRIO em vez de estender {@link Convert}: aquele
    /// assume larguras F32/F64 simétricas nos dois lados; `bf16` não tem via de registrador nativa.
    record ConvertToBf16(
            /// Registrador `V` de destino (`bf16`, 16 bits, resto zerado).
            int vd,
            /// Registrador `V` fonte (`Sn`, `binary32`).
            int vn) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_CONVERT_TO_BF16; }
    }

    /// `FMOV_hx`/`FMOV_xh` (`ARM DDI 0487`, `FEAT_FP16`, ARMv8.2-A, B19.26) — cópia CRUA de bits
    /// entre um registrador geral e o escalar `H<n>` (16 bits), sibling de
    /// {@link GeneralRegisterMove} (que só cobre `W`↔`S`/`X`↔`D`). Record PRÓPRIO em vez de
    /// estender aquele: lá {@link GeneralRegisterMove#wide} escolhe SIMULTANEAMENTE a largura do
    /// lado geral E do lado FP (sempre pareadas, `W`↔`S`/`X`↔`D`); aqui o lado FP é sempre `H` (16
    /// bits) e o `sf` do encoding pode ser `0` OU `1` produzindo o MESMO estado final — medido
    /// contra o corpus real (`aarch64-none-elf-as -march=armv8.2-a+fp16`): como só os 16 bits baixos
    /// do lado geral importam (`FMOV_hx`) e como escrever `Wd` já zero-estende os 32 bits altos de
    /// `Xd` (convenção AArch64, `FMOV_xh`), o valor de `sf` não muda o estado observável — por isso
    /// este record não carrega `sf`/`wide` nenhum, e o executor sempre resolve o lado geral como
    /// `X` completo (ver Armadilhas da task).
    record HalfPrecisionGeneralRegisterMove(
            /// `true`: `Xn`/`Wn` → `Hd` (bits crus, zera o resto de `Vd`). `false`: `Hn` → `Xd`/`Wd`
            /// (bits crus, zero-estendido).
            boolean toFloat,
            /// Registrador FP (índice `0`-`31`, `V<n>`).
            int fpReg,
            /// Registrador geral (índice `0`-`31`).
            int gpReg) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_HALF_PRECISION_GENERAL_REGISTER_MOVE; }
    }

    /// Direção de {@link ConvertHalfPrecision} — as 4 combinações de `FCVT` entre meia precisão
    /// e simples/dupla que faltavam depois de {@link Convert} (`F32_TO_F64`/`F64_TO_F32`, sem
    /// meia precisão).
    enum Fp64HalfPrecisionConversion {
        HALF_TO_SINGLE, SINGLE_TO_HALF, HALF_TO_DOUBLE, DOUBLE_TO_HALF
    }

    /// `FCVT_s_hs`/`FCVT_s_hd`/`FCVT_s_sh`/`FCVT_s_dh` (`ARM DDI 0487`, `FEAT_FP16`, ARMv8.2-A,
    /// B19.26) — conversão REAL de valor (com arredondamento) entre meia precisão e simples/dupla,
    /// completando as 2 combinações que {@link Convert} já cobria (`F32_TO_F64`/`F64_TO_F32`).
    /// Record PRÓPRIO em vez de estender {@link Convert} (mesmo precedente de
    /// {@link ConvertToBf16}): aquele é natively-supported pelo `Ir64NativePolicy` (C12.4) e
    /// adicionar valores novos ao enum {@link Fp64Conversion} sem emitir nativo para eles quebraria
    /// a premissa "toda `Kind` no `switch` do compilador tem TODOS os seus casos cobertos" — um
    /// `Kind` novo cai automaticamente no interpretador (Não fazer da task: sem caso nativo).
    /// **`SINGLE_TO_HALF`/`DOUBLE_TO_HALF` reusam `Float.floatToFloat16`**
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#halfBits}); `DOUBLE_TO_HALF` passa
    /// por `float` intermediário (`(float) valor` seguido de `floatToFloat16`) — simplificação
    /// EXPLÍCITA (double rounding, ver Armadilhas da task): o hardware real arredonda `double`→`H`
    /// num único passo, mas os dois só divergem em casos extremos de meio-a-meio bem no limite da
    /// mantissa de `float`, e o projeto já aceita simplificações equivalentes em `FRINTX`/`FRINTI`
    /// (`FPCR.RMode` não modelado em A64).
    record ConvertHalfPrecision(
            /// Direção da conversão.
            Fp64HalfPrecisionConversion conversion,
            /// Registrador de destino (índice `0`-`31`, `V<n>`).
            int vd,
            /// Registrador de origem (índice `0`-`31`, `V<n>`).
            int vn) implements FpOp64 {
        @Override public int kind() { return Kind.FP64_CONVERT_HALF_PRECISION; }
    }
}
