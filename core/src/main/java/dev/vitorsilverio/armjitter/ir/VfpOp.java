package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações VFP (ponto flutuante escalar de 32 bits): aritmética, comparação, conversão,
/// arredondamento, transferências com registrador geral e memória, as formas de meia precisão e o
/// contexto de ponto flutuante do perfil M (`VLLDM`/`VLSTM`/`VSCCLRM`).
///
/// Sub-interface selada de {@link IrOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface VfpOp extends IrOp permits VfpOp.Alu, VfpOp.Select, VfpOp.Round,
        VfpOp.ConvertRounded, VfpOp.MoveHalfLane, VfpOp.AluHalf, VfpOp.MoveImmediateHalf,
        VfpOp.CompareHalf, VfpOp.SelectHalf, VfpOp.RoundHalf, VfpOp.ConvertRoundedHalf,
        VfpOp.ConvertFixedHalf, VfpOp.LoadHalf, VfpOp.StoreHalf, VfpOp.ConvertHalfPrecision,
        VfpOp.JavascriptConvert, VfpOp.MoveImmediate, VfpOp.Compare, VfpOp.Convert, VfpOp.Load,
        VfpOp.Store, VfpOp.MultipleTransfer, VfpOp.CoreTransfer, VfpOp.CorePairTransfer,
        VfpOp.SystemTransfer, VfpOp.CorePairTransferSingle, VfpOp.ConvertFixed,
        VfpOp.SysregMemoryTransfer, VfpOp.VlldmVlstm, VfpOp.Vscclrm {

    // ── VFP (B3.4): decode fica em B3.5, ASM nativo em B3.6 — aqui só IR + interpretador. ──

    /// Operação aritmética/unária VFP (`op` seleciona o comportamento; ver {@link Alu}).
    enum VfpOperation {
        /// `VADD`: `vd = vn + vm`.
        ADD,
        /// `VSUB`: `vd = vn - vm`.
        SUB,
        /// `VMUL`: `vd = vn * vm`.
        MUL,
        /// `VDIV`: `vd = vn / vm`.
        DIV,
        /// `VMLA`: `vd = vd + (vn * vm)`, NÃO fundido (duas operações arredondadas separadamente).
        MLA,
        /// `VMLS`: `vd = vd - (vn * vm)`, NÃO fundido.
        MLS,
        /// `VNMLA`: `vd = -vd - (vn * vm)`, NÃO fundido (ARM ARM A8.8.337: "-fd + -(fn * fm)").
        NMLA,
        /// `VNMLS`: `vd = -vd + (vn * vm)`, NÃO fundido (ARM ARM A8.8.337: "-fd + (fn * fm)").
        /// **Não** é `VMLS` com o sinal trocado: quem é negado é o ACUMULADOR, não o produto.
        NMLS,
        /// `VNMUL`: `vd = -(vn * vm)`.
        NMUL,
        /// `VNEG` (unária, usa só `vm`): inverte o bit de sinal.
        NEG,
        /// `VABS` (unária, usa só `vm`): zera o bit de sinal.
        ABS,
        /// `VSQRT` (unária, usa só `vm`): raiz quadrada corretamente arredondada.
        SQRT,
        /// `VMOV` registrador-a-registrador (unária, usa só `vm`): cópia bit a bit.
        COPY,
        /// `VFMA` (B9.6, VFPv4): `vd = vd + (vn * vm)`, FUNDIDO — um único passo de arredondamento
        /// para o produto-e-soma inteiro (`Math.fma`), ao contrário de {@link #MLA}. Mesma
        /// convenção de sinal de {@link #MLA}, só muda o arredondamento.
        FMA,
        /// `VFMS` (B9.6, VFPv4): `vd = vd - (vn * vm)`, FUNDIDO. Mesma convenção de sinal de
        /// {@link #MLS} (produto negado, não o acumulador).
        FMS,
        /// `VFNMA` (B9.6, VFPv4): `vd = -vd - (vn * vm)`, FUNDIDO. Mesma convenção de sinal de
        /// {@link #NMLA} (confirmado contra `MAKE_ONE_VFM_TRANS_FN`/`do_vfm_sp` reais do QEMU:
        /// `neg_n=true, neg_d=true` → `fma(-vd, -vn, vm)` = `-(vd + vn·vm)`).
        FNMA,
        /// `VFNMS` (B9.6, VFPv4): `vd = -vd + (vn * vm)`, FUNDIDO. Mesma convenção de sinal de
        /// {@link #NMLS} (`neg_n=false, neg_d=true` → `fma(-vd, vn, vm)` = `-vd + vn·vm`).
        FNMS,
        /// `VMAXNM` (B14.4, ARMv8-A): `vd = maxNum(vn, vm)` — variante "numérica" de `VMAX`: se só
        /// um operando é NaN, o resultado é o OUTRO (não NaN); só quando os dois são NaN o
        /// resultado é NaN. Delega a {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#maxNum}
        /// (mesmo núcleo do `FMAXNM` A64, B8.4) — **nunca** `Math.max` (ver Armadilhas de B14.4).
        MAXNM,
        /// `VMINNM` (B14.4, ARMv8-A): espelho de {@link #MAXNM} com
        /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#minNum}.
        MINNM
    }

    /// Operação aritmética/unária VFP (`VADD`/`VSUB`/`VMUL`/`VDIV`/`VMLA`/`VMLS`/`VNMUL`/`VNEG`/
    /// `VABS`/`VSQRT`/`VMOV` registrador, mais `VFMA`/`VFMS`/`VFNMA`/`VFNMS`, B9.6). `VMLA`/`VMLS`/
    /// `VNMUL`/`VNMLA`/`VNMLS` NUNCA usam `Math.fma` — o VFPv2 real não funde a multiplicação com a
    /// soma/subtração (ver Armadilhas de B3.4). Só `FMA`/`FMS`/`FNMA`/`FNMS` (VFPv4, B9.6) usam
    /// `Math.fma` de verdade — é literalmente a diferença arquitetural entre as duas famílias
    /// (fundida vs não-fundida), não uma escolha de implementação. As formas unárias (`NEG`/`ABS`/
    /// `SQRT`/`COPY`) usam somente `vm`; `MLA`/`MLS`/`FMA`/`FMS` também leem o `vd` atual como
    /// acumulador.
    record Alu(
            /// Operação a executar.
            VfpOperation op,
            /// `true` para precisão dupla (registradores `D`), `false` para simples (`S`).
            boolean doublePrecision,
            /// Registrador de destino (também acumulador de entrada para `MLA`/`MLS`).
            int vd,
            /// Primeiro registrador de origem (ignorado pelas formas unárias).
            int vn,
            /// Segundo registrador de origem (único operando das formas unárias).
            int vm,
            /// Condição necessária para executar a operação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_ALU; }
    }

    /// `VSEL` (B14.4, ARMv8-A, espaço VFP incondicional): `vd = selectCondition ? vn : vm` — cópia
    /// de BITS crua (não normaliza NaN, não toca `FPSCR`), selecionada pelos flags do **CPSR**
    /// (`Condition#EQ`/`VS`/`GE`/`GT`, os 4 únicos valores que `cc:2` produz — ARM ARM A8.8.294:
    /// `0`→EQ, `1`→VS, `2`→GE, `3`→GT). **`selectCondition` é DADO, nunca controle**: ao contrário
    /// de {@link #condition}, que (sempre {@link Condition#AL} aqui — `VSEL` mora no espaço
    /// incondicional) guarda o bloco/lifter, `selectCondition` só é lido DENTRO do executor para
    /// escolher `vn` ou `vm`. Misturar os dois (usar `selectCondition` como `condition` da
    /// instrução) faria o bloco PULAR `VSEL` quando a condição fosse falsa, em vez de escrever
    /// `vm` em `vd` — resultado silenciosamente errado (Armadilha 2 da task B14.4).
    record Select(
            /// `true` para precisão dupla (registradores `D`), `false` para simples (`S`).
            boolean doublePrecision,
            /// Registrador de destino.
            int vd,
            /// Registrador escolhido quando {@link #selectCondition} é verdadeira.
            int vn,
            /// Registrador escolhido quando {@link #selectCondition} é falsa.
            int vm,
            /// Condição de SELEÇÃO (`cc:2` do encoding, mapeado para `EQ`/`VS`/`GE`/`GT`) — avaliada
            /// contra o CPSR pelo executor, nunca contra o `FPSCR`.
            Condition selectCondition,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}: `VSEL` é incondicional).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_SELECT; }
    }

    /// `VRINT{A,N,P,M}` (B14.5, ARMv8-A, espaço VFP incondicional): `vd = roundToIntegral(vm,
    /// direction)` — arredonda para valor integral MANTENDO ponto flutuante (nunca converte para
    /// inteiro, ao contrário de {@link ConvertRounded}). `direction` vem do campo `rm` da
    /// PRÓPRIA instrução (mapeado pelo decoder), **nunca** de `FPSCR.RMode` — por isso usa
    /// {@link AdvSimdLanes.RoundingMode} (5 valores, inclui `NEAREST_TIES_AWAY`) em vez de
    /// {@link dev.vitorsilverio.armjitter.core.FpRoundingMode} (4 valores, contrato do campo
    /// `RMODE` do FPSCR — não expressa "ties away", ver Armadilha 1 da task). `NaN`/infinito passam
    /// adiante inalterados (mesma decisão de {@code FpOp64.Round}/`FRINTx` A64).
    record Round(
            /// Direção de arredondamento (do campo `rm` do encoding), ou `null` para "modo CORRENTE
            /// do `FPSCR.RMode`" (`VRINTR`/`VRINTX`, B22.7).
            AdvSimdLanes.RoundingMode direction,
            /// `true` para precisão dupla (registradores `D`), `false` para simples (`S`) — mesma
            /// precisão em `vd`/`vm`.
            boolean doublePrecision,
            /// Registrador de destino.
            int vd,
            /// Registrador de origem.
            int vm,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}: espaço incondicional).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_ROUND; }
    }

    /// `VCVT{A,N,P,M}{S,U}` (B14.5, ARMv8-A, espaço VFP incondicional): converte `vm` (ponto
    /// flutuante, precisão `doublePrecision`) para um inteiro de 32 bits em `vd` — **`vd` é SEMPRE
    /// `S`**, mesmo quando `doublePrecision` é `true` (a origem é `D`, o destino nunca é, ver
    /// Contexto da task) —, com/sem sinal (`signed`), arredondando pela `direction` da PRÓPRIA
    /// instrução. Diferente de {@link Convert} (forma default, sempre round-toward-zero
    /// implícito): aqui a direção é explícita e pode ser qualquer uma das 5.
    record ConvertRounded(
            /// Direção de arredondamento (do campo `rm`), ou `null` para "modo CORRENTE do
            /// `FPSCR.RMode`" (`VCVTR`, B22.7).
            AdvSimdLanes.RoundingMode direction,
            /// `true` = conversão com sinal (`VCVTxS`), `false` = sem sinal (`VCVTxU`).
            boolean signed,
            /// `true` quando a ORIGEM (`vm`) é precisão dupla; o destino (`vd`) é sempre simples.
            boolean doublePrecision,
            /// Registrador de destino (sempre `S`).
            int vd,
            /// Registrador de origem (`S` ou `D`, conforme `doublePrecision`).
            int vm,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}: espaço incondicional).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CONVERT_ROUNDED; }
    }

    /// `VMOVX`/`VINS` (B14.6, ARMv8-A, `ArmFeature.FP16_ARITHMETIC`, espaço VFP incondicional):
    /// troca CRUA de metades de 16 bits de um registrador `S` de 32 bits — nunca interpreta o
    /// conteúdo como float (sem arredondamento, sem `FPSCR`), ao contrário de toda a aritmética
    /// `_hp` que o resto do épico B14.6 vai trazer. **`VMOVX`**: `vd = zeroExtend(vm[31:16])`
    /// (metade alta de `vm` vira a metade baixa de `vd`, resto zerado). **`VINS`**: `vd[31:16] =
    /// vm[15:0]`, `vd[15:0]` PRESERVADO (é por isso que o executor lê o `vd` atual antes de
    /// escrever — a única operação `_hp`/`half` desta task que depende do valor prévio do
    /// destino). Ambas operam SEMPRE em `S` (nunca `D` — não há forma dupla, ver Contexto da task).
    record MoveHalfLane(
            /// `true` para `VINS`, `false` para `VMOVX`.
            boolean insert,
            /// Registrador de destino (`S`).
            int vd,
            /// Registrador de origem (`S`).
            int vm,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}: espaço incondicional).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_MOVE_HALF_LANE; }
    }

    // ── B14.6b: aritmética `_hp` (FEAT_FP16) — Kind/records PRÓPRIOS (Armadilha 2 de B14.6:
    // `boolean doublePrecision` não expressa 3 precisões; mudar seu tipo nos records SP/DP
    // quebraria G3 — pattern matching exaustivo em toda parte do projeto — então a meia precisão
    // ganha sua PRÓPRIA família de Kind/record, mesmo padrão que {@link MoveHalfLane} (B14.6a)
    // já usou). Todos operam em registradores `S` (5 bits, banco de 32) — o valor de 16 bits mora
    // SEMPRE nos bits baixos (`Sx[15:0]`); o executor zero-estende os bits altos do destino ao
    // escrever (mesma convenção de {@link MoveHalfLane#insert} `VMOVX`), decisão documentada no
    // `## Resultado` da task (o ARM ARM deixa os bits altos UNKNOWN; zero-estender é determinístico
    // e reusa a mesma convenção já adotada por VMOVX). Ponte para `binary16`:
    // {@link AdvSimdLanes#halfBits}/{@link AdvSimdLanes#halfToFloat} — o mesmo núcleo que o NEON
    // FP16 já usa (RFC B13.2 D1), nunca uma conversão de bits nova. ──

    /// Aritmética/unárias `_hp` (B14.6b): `VADD_hp`…`VFNMA_hp`/`VABS_hp`/`VNEG_hp`/`VSQRT_hp`
    /// (espaço condicional) e `VMAXNM_hp`/`VMINNM_hp` (espaço incondicional) — MESMO `op` de
    /// {@link Alu}, sem campo de precisão (sempre meia precisão). Formas unárias usam só `vm`
    /// (`vn=-1`, mesma convenção de {@link Alu#vn}).
    record AluHalf(
            /// Operação a executar (reusa {@link VfpOperation}; `MAXNM`/`MINNM` só chegam pelo
            /// espaço incondicional, o resto pelo condicional).
            VfpOperation op,
            /// Registrador de destino (também acumulador de entrada para `MLA`/`MLS`/`FMA`/`FMS`).
            int vd,
            /// Primeiro registrador de origem (ignorado pelas formas unárias).
            int vn,
            /// Segundo registrador de origem (único operando das formas unárias).
            int vm,
            /// Condição necessária para executar a operação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_ALU_HALF; }
    }

    /// `VMOV.F16 Vd,#imm` (B14.6b): grava um imediato de meia precisão já expandido
    /// (`VFPExpandImm` para `N=16`, ver `StandardIrBuilder#vfpExpandImmHalf`).
    record MoveImmediateHalf(
            /// Registrador de destino.
            int vd,
            /// Bits crus do imediato de 16 bits (zero-estendido).
            int immediateBits,
            /// Condição necessária para executar a operação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_MOVE_IMMEDIATE_HALF; }
    }

    /// `VCMP_hp`/`VCMPE_hp` (B14.6b): compara `vd` com `vm` (ou com zero) em meia precisão e grava
    /// só `FPSCR.NZCV` — mesma tabela de {@link Compare}.
    record CompareHalf(
            /// `true` para as formas `VCMP(E)_hp Vd, #0.0` (compara com zero em vez de `vm`).
            boolean compareWithZero,
            /// `true` para `VCMPE_hp`.
            boolean signalOnQuietNaN,
            /// Registrador comparado.
            int vd,
            /// Segundo operando da comparação (ignorado quando `compareWithZero`).
            int vm,
            /// Condição necessária para executar a comparação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_COMPARE_HALF; }
    }

    /// `VSEL_hp` (B14.6b, espaço VFP incondicional) — mesma semântica de {@link Select}, sem
    /// campo de precisão (sempre `S`/meia precisão): `vd = selectCondition ? vn : vm`, cópia de
    /// BITS crua (nunca aritmética).
    record SelectHalf(
            /// Registrador de destino.
            int vd,
            /// Registrador escolhido quando {@link #selectCondition} é verdadeira.
            int vn,
            /// Registrador escolhido quando {@link #selectCondition} é falsa.
            int vm,
            /// Condição de SELEÇÃO (`cc:2` do encoding) — avaliada contra o CPSR.
            Condition selectCondition,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_SELECT_HALF; }
    }

    /// `VRINT{A,N,P,M}_hp` (B14.6b, espaço VFP incondicional) — mesma semântica de {@link Round}
    /// em meia precisão: arredonda `vm` para valor integral MANTENDO ponto flutuante.
    record RoundHalf(
            /// Direção de arredondamento (do campo `rm` do encoding), ou `null` para "modo CORRENTE
            /// do `FPSCR.RMode`" (`VRINTR`/`VRINTX`, B22.7).
            AdvSimdLanes.RoundingMode direction,
            /// Registrador de destino.
            int vd,
            /// Registrador de origem.
            int vm,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_ROUND_HALF; }
    }

    /// `VCVT{A,N,P,M}{S,U}_hp` (B14.6b, espaço VFP incondicional) — mesma semântica de
    /// {@link ConvertRounded}: converte `vm` (meia precisão) para inteiro de 32 bits em `vd`.
    record ConvertRoundedHalf(
            /// Direção de arredondamento (do campo `rm`), ou `null` para "modo CORRENTE do
            /// `FPSCR.RMode`" (`VCVTR`, B22.7).
            AdvSimdLanes.RoundingMode direction,
            /// `true` = conversão com sinal (`VCVTxS`), `false` = sem sinal (`VCVTxU`).
            boolean signed,
            /// Registrador de destino (`S`, inteiro de 32 bits).
            int vd,
            /// Registrador de origem (`S`, meia precisão).
            int vm,
            /// Condição de execução do BLOCO (sempre {@link Condition#AL}).
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CONVERT_ROUNDED_HALF; }
    }

    /// `VCVT_fix_hp` (B14.6b) — mesma semântica de {@link ConvertFixed} em meia precisão:
    /// converte, no MESMO `vd`, entre meia precisão e um inteiro fixo empacotado nos bits baixos.
    record ConvertFixedHalf(
            /// `true` = float → fixo; `false` = fixo → float.
            boolean toFixedPoint,
            /// `true` = inteiro fixo sem sinal.
            boolean unsignedFixedPoint,
            /// `true` = campo fixo de 32 bits; `false` = 16 bits.
            boolean fixedPointIs32Bit,
            /// Quantidade de bits fracionários do formato fixo.
            int fractionBits,
            /// Registrador de origem e destino (mesmo registrador).
            int vd,
            /// Condição necessária para executar a conversão.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CONVERT_FIXED_HALF; }
    }

    /// `VLDR_hp` (B14.6b) — mesma semântica de {@link Load} em meia precisão: carrega
    /// `Vd[15:0]` de `[base + offsetBytes]` (halfword, 2 bytes), zero-estendendo `Vd[31:16]`.
    record LoadHalf(
            /// Registrador de destino.
            int vd,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1` — ver
            /// {@link Load#baseValueOverride}.
            int baseValueOverride,
            /// Offset em bytes (±`imm8`×4 — mesma escala fixa de {@link Load}, independente do
            /// tamanho do acesso, ver ARM DDI 0406C A8.8.333/A7.7.230), já resolvido pelo lifter.
            int offsetBytes,
            /// Condição necessária para executar o load.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_LOAD_HALF; }
    }

    /// `VSTR_hp` (B14.6b) — ver {@link LoadHalf}.
    record StoreHalf(
            /// Registrador de origem.
            int vd,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1`.
            int baseValueOverride,
            /// Offset em bytes (±`imm8`×4), já resolvido pelo lifter.
            int offsetBytes,
            /// Condição necessária para executar o store.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_STORE_HALF; }
    }

    /// Direção de conversão de {@link ConvertHalfPrecision} (`VCVTB`/`VCVTT`, B22.7). O nome diz
    /// origem→destino; qual banco (`S`/`D`) cada lado usa é fixo por membro.
    enum HalfPrecisionConversion {
        /// `VCVTB/T.F32.F16`: `Sd = f32(Sm.half[t])` (conversão exata).
        F16_TO_F32,
        /// `VCVTB/T.F64.F16`: `Dd = f64(Sm.half[t])` (conversão exata).
        F16_TO_F64,
        /// `VCVTB/T.F16.F32`: `Sd.half[t] = f16(Sm)`, a OUTRA metade de `Sd` é preservada.
        F32_TO_F16,
        /// `VCVTB/T.F16.F64`: `Sd.half[t] = f16(Dm)` (arredondamento ÚNICO double→half, nunca via
        /// float), a OUTRA metade de `Sd` é preservada.
        F64_TO_F16,
        /// `VCVTB/T.BF16.F32` (`FEAT_BF16`): `Sd.half[t] = bf16(Sm)` (round-to-nearest-even), a
        /// OUTRA metade de `Sd` é preservada.
        F32_TO_BF16
    }

    /// `VCVTB`/`VCVTT` (B22.7, VFPv3 half-precision extension + `FEAT_BF16`): converte entre a metade
    /// baixa (`top=false`, `VCVTB`) ou alta (`top=true`, `VCVTT`) de um `S` de 32 bits e um
    /// registrador de precisão simples/dupla. As formas "para half" preservam a metade NÃO
    /// selecionada do destino (é por isso que o executor lê o `vd` atual). Usa o modo de
    /// arredondamento round-to-nearest-even (sem modelo de `FPSCR.RMode`/`AHP`/`FZ` nesta família,
    /// mesma limitação documentada de `AdvSimdLanes#halfBits`).
    record ConvertHalfPrecision(
            /// Direção da conversão (fixa o banco de cada lado).
            HalfPrecisionConversion conversion,
            /// `true` para `VCVTT` (metade alta do `S` envolvido), `false` para `VCVTB` (baixa).
            boolean top,
            /// Registrador de destino (banco determinado por `conversion`).
            int vd,
            /// Registrador de origem (banco determinado por `conversion`).
            int vm,
            /// Condição necessária para executar a conversão.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CONVERT_HALF_PRECISION; }
    }

    /// `VJCVT.S32.F64 Sd, Dm` (B22.7, `FEAT_JSCVT`, ARMv8.3-A): `ToInt32` do JavaScript — trunca
    /// `Dm` em direção a zero e reduz módulo 2³² (NaN/infinito → 0; NÃO satura, ao contrário de
    /// {@link Convert}). Grava também `FPSCR.{N,Z,C,V} = 0,Z,0,0`, com `Z=1` só quando a
    /// conversão foi EXATA (entrada finita, integral e representável em 32 bits com sinal do
    /// mesmo valor). `vd` é `S`, `vm` é `D`.
    record JavascriptConvert(
            /// Registrador de destino (`S`).
            int vd,
            /// Registrador de origem (`D`).
            int vm,
            /// Condição necessária para executar a operação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_JAVASCRIPT_CONVERT; }
    }

    /// `VMOV.F32`/`VMOV.F64 Vd, #imm` (VFPv3-d16): grava um imediato de ponto flutuante já
    /// expandido pelo decoder/lifter (decode fica em B3.5).
    record MoveImmediate(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino.
            int vd,
            /// Bits crus do imediato: 32 bits baixos usados quando `!doublePrecision`, os 64 bits
            /// completos quando `doublePrecision`.
            long immediateBits,
            /// Condição necessária para executar a operação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_MOVE_IMMEDIATE; }
    }

    /// `VCMP`/`VCMPE` (com ou sem `VCMPE`/`VCMP` `#0.0`): compara `vd` com `vm` (ou com zero) e
    /// grava APENAS `FPSCR.NZCV` (ARM DDI 0406C A2.9.1) — nunca o CPSR; só `VMRS APSR_nzcv` (ver
    /// {@link SystemTransfer}) move o resultado para lá. Tabela exata: eq→N=0,Z=1,C=1,V=0;
    /// lt→N=1,Z=0,C=0,V=0; gt→N=0,Z=0,C=1,V=0; unordered (algum operando é NaN)→N=0,Z=0,C=1,V=1.
    record Compare(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// `true` para as formas `VCMP(E) Vd, #0.0` (compara com zero em vez de `vm`).
            boolean compareWithZero,
            /// `true` para `VCMPE` (bit E: sinaliza operação inválida também para NaN silencioso,
            /// não só sinalizador — sem efeito observável adicional neste core, que não modela
            /// traps de exceção de ponto flutuante; mantido para fidelidade ao encoding).
            boolean signalOnQuietNaN,
            /// Registrador comparado.
            int vd,
            /// Segundo operando da comparação (ignorado quando `compareWithZero`).
            int vm,
            /// Condição necessária para executar a comparação.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_COMPARE; }
    }

    /// Direção/tipos de uma conversão `VCVT` (forma default, arredondamento round-toward-zero para
    /// inteiro — ver {@link Convert}).
    enum VfpConversion {
        /// `VCVT.F64.F32`: simples → dupla (exata).
        F32_TO_F64,
        /// `VCVT.F32.F64`: dupla → simples (arredondada).
        F64_TO_F32,
        /// `VCVT.F32.S32`: inteiro com sinal → simples.
        S32_TO_F32,
        /// `VCVT.F64.S32`: inteiro com sinal → dupla (exata).
        S32_TO_F64,
        /// `VCVT.F32.U32`: inteiro sem sinal → simples.
        U32_TO_F32,
        /// `VCVT.F64.U32`: inteiro sem sinal → dupla (exata).
        U32_TO_F64,
        /// `VCVT.S32.F32`: simples → inteiro com sinal (round-toward-zero, satura, NaN→0).
        F32_TO_S32,
        /// `VCVT.S32.F64`: dupla → inteiro com sinal (round-toward-zero, satura, NaN→0).
        F64_TO_S32,
        /// `VCVT.U32.F32`: simples → inteiro sem sinal (round-toward-zero, satura em `[0, 2³²-1]`, NaN→0).
        F32_TO_U32,
        /// `VCVT.U32.F64`: dupla → inteiro sem sinal (round-toward-zero, satura em `[0, 2³²-1]`, NaN→0).
        F64_TO_U32,
        /// `VCVT.F16.S32` (B14.6b, `VCVT_int_hp`): inteiro com sinal → meia precisão.
        S32_TO_F16,
        /// `VCVT.F16.U32` (B14.6b, `VCVT_int_hp`): inteiro sem sinal → meia precisão.
        U32_TO_F16,
        /// `VCVT.S32.F16` (B14.6b, `VCVT_hp_int`): meia precisão → inteiro com sinal
        /// (round-toward-zero, satura, NaN→0).
        F16_TO_S32,
        /// `VCVT.U32.F16` (B14.6b, `VCVT_hp_int`): meia precisão → inteiro sem sinal
        /// (round-toward-zero, satura em `[0, 2³²-1]`, NaN→0).
        F16_TO_U32
    }

    /// `VCVT` na forma default (não `VCVTR`, que usaria `FPSCR.RMode` — fora de escopo, RMode≠RN
    /// já é rejeitado por {@link dev.vitorsilverio.armjitter.core.FpscrRegister}). Cada membro de
    /// {@link VfpConversion} já fixa qual banco (`S` ou `D`) origem/destino usam — não há campo
    /// `doublePrecision` separado porque a direção da conversão determina isso sozinha.
    record Convert(
            /// Conversão a executar.
            VfpConversion conversion,
            /// Registrador de destino (banco determinado por `conversion`).
            int vd,
            /// Registrador de origem (banco determinado por `conversion`).
            int vm,
            /// Condição necessária para executar a conversão.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CONVERT; }
    }

    /// `VLDR`: carrega `Vd` de `[base + offsetBytes]` (sempre `P=1,W=0` — VFP não tem writeback
    /// em load/store simples, ao contrário de `LDR`/`LDRD`).
    record Load(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de destino.
            int vd,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC` (`Vd, [pc, #imm]`,
            /// o idioma padrão de literal pool do `gcc` para constantes `double`/`float`), ou `-1`
            /// — mesmo mecanismo de {@link MemoryOp.Load#baseValueOverride}. Sem ele, `base` seria lido AO
            /// VIVO de `core.register(15)` em tempo de execução, que NÃO tem o viés `+8` do `PC`
            /// arquitetural do ARM: o bloco só grava `registers[PC]` no fim ({@link
            /// dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor#execute}), então
            /// durante a execução deste op `PC` ainda vale o endereço da instrução atual, não
            /// `+8` — sem o override, `VLDR`/`VSTR Vx, [pc, #imm]` lia do endereço errado (e
            /// interpretado/JIT convergiam no MESMO endereço errado, G1 preservado mas ambos
            /// incorretos).
            int baseValueOverride,
            /// Offset em bytes (±`imm8`×4), já resolvido pelo decoder/lifter.
            int offsetBytes,
            /// Condição necessária para executar o load.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_LOAD; }
    }

    /// `VSTR`: grava `Vd` em `[base + offsetBytes]` (ver {@link Load}).
    record Store(
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador de origem.
            int vd,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1` — ver
            /// {@link Load#baseValueOverride}.
            int baseValueOverride,
            /// Offset em bytes (±`imm8`×4), já resolvido pelo decoder/lifter.
            int offsetBytes,
            /// Condição necessária para executar o store.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_STORE; }
    }

    /// `VLDM`/`VSTM`/`VPUSH`/`VPOP`: transfere `count` registradores consecutivos
    /// (`firstRegister`..`firstRegister+count-1`) entre memória e o banco VFP. Só as formas `IA`
    /// e `DB` existem no VFP (ao contrário do `LDM`/`STM` ARM genérico, que também tem `IB`/`DA`);
    /// `VPUSH`/`VPOP` são aliases de `DB`/`IA` com `writeback=true` e `base=SP` — sem `IrOp`
    /// dedicado, testados via este record diretamente.
    record MultipleTransfer(
            /// `true` para `VLDM` (load), `false` para `VSTM` (store).
            boolean load,
            /// `true` para precisão dupla, `false` para simples.
            boolean doublePrecision,
            /// Registrador base do endereço.
            int base,
            /// Valor fixo para usar como base quando o registrador base é `PC`, ou `-1` — ver
            /// {@link Load#baseValueOverride} (mesmo idioma de literal pool, aqui para
            /// `VLDM`/`VSTM Rn=pc`, ainda que raro comparado a `VLDR`/`VSTR`).
            int baseValueOverride,
            /// Primeiro registrador da lista.
            int firstRegister,
            /// Quantidade de registradores consecutivos.
            int count,
            /// Indica writeback no registrador base (sempre `true` para `VPUSH`/`VPOP`).
            boolean writeback,
            /// `true` para `DB` (decrementa antes — `VPUSH`); `false` para `IA` (`VPOP`/`VLDM`/`VSTM` padrão).
            boolean decrementBefore,
            /// Condição necessária para executar a transferência.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_MULTIPLE_TRANSFER; }
    }

    /// `VMOV Rt, Sn` / `VMOV Sn, Rt` (`FMRS`/`FMSR`): transfere um único registrador `S` de/para
    /// um registrador ARM de propósito geral, bits crus (sem conversão de tipo).
    record CoreTransfer(
            /// `true` para `Sn` → `Rt` (`FMRS`); `false` para `Rt` → `Sn` (`FMSR`).
            boolean toArmRegister,
            /// Registrador ARM de propósito geral envolvido.
            int armRegister,
            /// Registrador `S` envolvido.
            int vn,
            /// `VMOV_half` (B22.2, `ArmFeature.HALF_PRECISION_FP`): transferência de **16 bits**.
            /// `toArmRegister` → `Rt = ZeroExtend(Sn[15:0], 32)`; senão → `Sn[15:0] = Rt[15:0]`
            /// e `Sn[31:16]` fica **inalterado** (ao contrário da forma de 32 bits, que escreve o
            /// `S` inteiro). `false` para `VMOV_single`/`VMOV_to_gp`/`VMOV_from_gp` (32 bits).
            boolean halfWidth,
            /// B22.10 (`VMOV.{S8,U8,S16,U16,8,16}`, NEON): largura em BITS (`8` ou `16`) do ELEMENTO de
            /// um registrador `D` transferido; `0` = não é transferência de lane.
            /// Com lane, `vn` é o índice do registrador `D` (`0`-`31`), não de um `S`.
            int laneBits,
            /// Índice do elemento dentro do `D` (ignorado quando `laneBits == 0`).
            int lane,
            /// Leitura com extensão de sinal (`S8`/`S16`); `false` = zero-extend (`U8`/`U16`).
            boolean signExtend,
            /// Condição necessária para executar a transferência.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CORE_TRANSFER; }

        /// Forma sem lane (`VMOV_single`/`VMOV_half`/`VMOV_to_gp` de 32 bits) — mantém a assinatura anterior.
        public CoreTransfer(boolean toArmRegister, int armRegister, int vn, boolean halfWidth, Condition condition) {
            this(toArmRegister, armRegister, vn, halfWidth, 0, 0, false, condition);
        }

        /// `true` para as formas NEON de 8/16 bits (transferência de UM elemento de um `D`).
        public boolean isLaneTransfer() { return laneBits != 0; }

    }

    /// `VMOV Rt, Rt2, Dm` / `VMOV Dm, Rt, Rt2` (`FMRRD`/`FMDRR`): transfere um registrador `D`
    /// inteiro de/para um par de registradores ARM (`armLow` = metade baixa, `armHigh` = metade
    /// alta — mesmo layout little-endian de {@link Load}/{@link Store} na memória).
    record CorePairTransfer(
            /// `true` para `Dm` → `(armLow,armHigh)` (`FMRRD`); `false` para o sentido inverso (`FMDRR`).
            boolean toArmRegisters,
            /// Registrador ARM que recebe/fornece a metade BAIXA.
            int armLow,
            /// Registrador ARM que recebe/fornece a metade ALTA.
            int armHigh,
            /// Registrador `D` envolvido.
            int vm,
            /// Condição necessária para executar a transferência.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CORE_PAIR_TRANSFER; }
    }

    /// `VMSR`/`VMRS FPSCR` (`FMXR`/`FMRX`): transfere o FPSCR completo de/para um registrador ARM.
    /// Caso especial obrigatório (decisão nº 4 do épico B3): `VMRS APSR_nzcv, FPSCR`
    /// (`read=true, armRegister=15`) NÃO escreve `R15` — copia só `FPSCR.NZCV` para `CPSR.NZCV`,
    /// preservando Q/GE/IT/modo/todo o resto do CPSR.
    record SystemTransfer(
            /// `true` para `VMRS` (FPSCR → destino); `false` para `VMSR` (origem → FPSCR).
            boolean read,
            /// Registrador ARM envolvido; `15` em `read=true` é o caso especial `APSR_nzcv`.
            int armRegister,
            /// Condição necessária para executar a transferência.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_SYSTEM_TRANSFER; }
    }

    // -- VFP (B9.5): VMOV_64_sp (par de S consecutivos) e VCVT_fix (fixed-point). --

    /// `VMOV_64_sp` (ARM DDI 0406C A8.8.346, forma depreciada mas VFPv2 genuina): transfere `Sm`
    /// (metade baixa) e `Sm+1` (metade alta, calculado em tempo de execucao a partir de `vm`) de/
    /// para dois registradores ARM. NAO reaproveita {@link CorePairTransfer} porque `Sm`/`Sm+1`
    /// so coincidem com um registrador `D` inteiro quando `m` e par -- para `m` impar as duas
    /// metades pertencem a `D` diferentes, e o acesso precisa ser via `S` diretamente.
    record CorePairTransferSingle(
            /// `true` para `(Sm,Sm+1)` -> `(armLow,armHigh)`; `false` para o sentido inverso.
            boolean toArmRegisters,
            /// Registrador ARM que recebe/fornece a metade BAIXA (`Sm`).
            int armLow,
            /// Registrador ARM que recebe/fornece a metade ALTA (`Sm+1`).
            int armHigh,
            /// Primeiro registrador `S` do par consecutivo (o segundo e `vm+1`).
            int vm,
            /// Condicao necessaria para executar a transferencia.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CORE_PAIR_TRANSFER_SINGLE; }
    }

    /// `VCVT_fix_{sp,dp}` (ARM DDI 0406C A8.8.397, VFPv3): converte, no MESMO registrador `vd`
    /// (fonte e destino coincidem), entre ponto flutuante e um inteiro fixo empacotado nos bits
    /// baixos do registrador. Fixo -> float sempre arredonda ao mais proximo (par); float -> fixo
    /// sempre trunca para zero e satura na largura do inteiro (16 ou 32 bits, com/sem sinal
    /// conforme `unsignedFixedPoint`) -- QEMU `vfp_helper.c` `VFP_CONV_FIX*`, conferido antes de
    /// implementar (aritmetica de conversao fixo<->float NUNCA e so um deslocamento de bits).
    record ConvertFixed(
            /// `true` para precisao dupla do lado float (`vd` e um registrador `D`), `false` simples (`S`).
            boolean doublePrecision,
            /// `true`: float -> fixo (arredonda p/ zero, satura). `false`: fixo -> float (arred. p/ perto).
            boolean toFixedPoint,
            /// `true`: inteiro fixo SEM sinal. `false`: COM sinal.
            boolean unsignedFixedPoint,
            /// `true`: inteiro fixo de 32 bits. `false`: 16 bits.
            boolean fixedPointIs32Bit,
            /// Quantidade de bits fracionarios, ja resolvida (`fixedPointIs32Bit ? 32-imm : 16-imm`).
            int fractionBits,
            /// Registrador `vd`: fonte E destino (mesma posicao nos dois sentidos).
            int vd,
            /// Condicao necessaria para executar a conversao.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_CONVERT_FIXED; }
    }

    /// `VLDR_sysreg`/`VSTR_sysreg` (perfil M, B15.3, `target/isa-decode/m-nocp.decode`): move o
    /// valor bruto de `ArmCore.fpscr()` de/para `[base {+,-}offsetBytes]`, com pré/pós-indexação e
    /// writeback opcionais — mesma semântica de endereço de {@link MemoryOp.Load}/{@link MemoryOp.Store} genérico
    /// ({@code postIndexed ? base : base + offsetBytes}, seguido de {@code base + offsetBytes}
    /// quando {@link #writeback}), só que o destino/origem não é um GPR. Único `reg` implementado
    /// nesta task é `FPSCR` (ver `Thumb2VfpSystemAccessDecoder`); os demais valores reais da
    /// arquitetura (`FPSCR_NZCVQC`/`VPR`,`P0`/`FPCXT_NS`/`FPCXT_S`) ainda não são decodificados.
    record SysregMemoryTransfer(
            /// `true` para `VLDR_sysreg` (memória -> `FPSCR`); `false` para `VSTR_sysreg`.
            boolean load,
            /// Registrador base do endereço (`Rn`).
            int base,
            /// Offset em bytes (±`imm7`×4), já resolvido pelo decoder.
            int offsetBytes,
            /// Indica writeback no registrador base.
            boolean writeback,
            /// Indica endereçamento post-index (`P=0,W=1` forçado no encoding real).
            boolean postIndexed,
            /// Condição necessária para executar a transferência.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VFP_SYSREG_MEMORY_TRANSFER; }
    }

    /// `VLLDM`/`VLSTM` (perfil M, B15.5, `target/isa-decode/m-nocp.decode`): salva/restaura o banco
    /// FP completo via lazy state preservation em hardware real — mas "lazy" é uma otimização de
    /// HARDWARE (economiza ciclos quando o handler de exceção nunca toca FP), sem semântica
    /// observável que este emulador precise reproduzir (ver Javadoc de
    /// {@code Thumb2VlldmVlstmVscclrmDecoder}). Sem FPU real no perfil M, o `.decode` real prioriza
    /// estas 2 formas ANTES do `NOCP` genérico e as trata como `UNDEFINED` explícito ("these are the
    /// two UNDEFs that must take precedence over NOCP") — via
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel#setUsageFaultUndefinstr()}
    /// (bit `UNDEFINSTR` do `UFSR`, diferente do bit `NOCP` que {@link SystemOp.Nocp} seta) seguido de
    /// `USAGE_FAULT`. Só produzida sob {@code ArmFeature.M_PROFILE}, mesmo contrato de {@link SystemOp.Nocp}.
    /// Nenhum campo além da condição é significativo — o resultado (UNDEF) não depende de `Rn`/`l`/
    /// `op` do encoding.
    record VlldmVlstm(
            /// Condição necessária para disparar a exceção.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VLLDM_VLSTM; }
    }

    /// `VSCCLRM` (perfil M, B15.5, `target/isa-decode/m-nocp.decode`): zera um intervalo contíguo
    /// de registradores FP existentes como armazenamento puro desde a B3.3 (`ArmCore.vfp()`) — usado
    /// pelo software para limpar informação residual de FP na transição Non-secure→Secure. Sem
    /// dependência de FPU real (zerar não é operação aritmética), ao contrário de {@link VlldmVlstm}.
    /// `firstRegister`/`lastRegister` (inclusive) já vêm resolvidos pelo decoder a partir de
    /// `Vd`/`imm`/`D`; `lastRegister` pode exceder o banco real (encoding `UNPREDICTABLE` com `imm`
    /// grande) — o executor recorta defensivamente, nunca lança.
    record Vscclrm(
            /// `true` para a forma de precisão dupla (`size=3`, registradores `D`); `false` para a
            /// forma de precisão simples (`size=2`, registradores `S`).
            boolean doublePrecision,
            /// Primeiro registrador do intervalo (`D<n>` ou `S<n>` conforme {@link #doublePrecision}).
            int firstRegister,
            /// Último registrador do intervalo, inclusive (não recortado ao tamanho real do banco).
            int lastRegister,
            /// Condição necessária para executar a limpeza.
            Condition condition) implements VfpOp {
        @Override public int kind() { return Kind.VSCCLRM; }
    }
}
