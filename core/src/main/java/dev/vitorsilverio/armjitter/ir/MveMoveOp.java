package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações MVE de movimentação de dados: load/store (contíguo, alargante, gather/scatter,
/// intercalado), `VDUP`/`VIDUP`/`VIWDUP`, `VMOV` entre lanes e GPR e imediato modificado.
///
/// Sub-interface selada de {@link MveOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface MveMoveOp extends MveOp permits MveMoveOp.LoadStore,
        MveMoveOp.WideningLoadStore, MveMoveOp.GatherScatterOffset,
        MveMoveOp.GatherScatterImmediate, MveMoveOp.InterleavedLoadStore, MveMoveOp.IncrementDup,
        MveMoveOp.WrappingIncrementDup, MveMoveOp.VectorDup, MveMoveOp.MoveLanesGpr,
        MveMoveOp.VectorModifiedImmediate {

    /// `VLDR_VSTR` (perfil M, B16.3, MVE/Helium, `target/isa-decode/mve.decode`): move os 128 bits
    /// de `Qd` de/para memória, byte a byte, respeitando o `elementMask` corrente (lane mascarada
    /// num load preserva o valor atual do registrador; lane mascarada num store preserva o byte de
    /// memória — Armadilha 5 da task, predicação é por BYTE, não "tudo ou nada"). Beatwise (mesmo
    /// gancho de {@link MvePredicationOp.AdvanceVpt} que {@link MvePredicationOp.Vpst}/{@link MvePredicationOp.Vpnot}/{@link MvePredicationOp.Vpsel} usam — instalado
    /// manualmente por {@code StandardIrBuilder#lift}, já que este `IrOp` chega via o escape hatch
    /// {@code DecodedInstruction#liftedOp}, fora do switch de `InstructionKind`).
    record LoadStore(
            /// `Qd` (`0`-`7`, já validado por
            /// {@link dev.vitorsilverio.armjitter.core.VfpRegisters#isValidMveQuadRegister}).
            int qd,
            /// `Rn` (base, já recusado se `15`, ou `13` com writeback — UNDEF no decode).
            int rn,
            /// Offset com sinal, JÁ escalado pelo tamanho do elemento (`imm7 << size`, nunca `<< 2`
            /// fixo — Armadilha 2 da task).
            int offset,
            /// `true` para `VLDR` (memória → `Qd`); `false` para `VSTR` (`Qd` → memória).
            boolean load,
            /// `true` quando `Rn` recebe o endereço pós-offset (writeback SEMPRE incondicional —
            /// G4, nunca predicado por `elementMask`, mesmo com a instrução totalmente mascarada).
            boolean writeback,
            /// `true` para pós-index (endereço de acesso = `Rn` ANTES do offset; `P=0`, `W`
            /// forçado); `false` para pré-index/offset (endereço de acesso = `Rn ± offset`; `P=1`).
            boolean postIndexed,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_LOAD_STORE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveLoadStore(core, this); }
    }

    /// `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (perfil M, B16.4, MVE/Helium, `target/isa-decode/mve.decode`):
    /// load que ALARGA (lê `1 << memorySizeLog2` bytes da memória, estende para
    /// `1 << registerSizeLog2` bytes na lane de `Qd`) ou store que ESTREITA (trunca cada lane de
    /// `Qd` para `1 << memorySizeLog2` bytes na memória). Predicação por ELEMENTO (não por byte,
    /// diferente de {@link LoadStore}): a máscara é indexada em passos de
    /// `1 << registerSizeLog2` (o tamanho do REGISTRADOR — verbatim de `DO_VLDR`/`DO_VSTR`,
    /// `target/arm/tcg/mve_helper.c`, `for (b = 0, e = 0; b < 16; b += ESIZE, e++)` com
    /// `ESIZE` = tamanho do registrador), enquanto o ENDEREÇO avança em passos de
    /// `1 << memorySizeLog2` (`addr += MSIZE`). Beatwise (mesmo gancho de {@link MvePredicationOp.AdvanceVpt} que
    /// {@link LoadStore} usa).
    ///
    /// No load, um elemento cujo beat já foi abandonado (bit de `eciMask` desligado) não é tocado
    /// (comportamento UNKNOWN permitido pelo hardware real, "R_SXTM" — implementado como
    /// preservar o valor atual da lane); um elemento cujo beat está ativo mas falha o predicado de
    /// `VPT` (bit de `eciMask` ligado, bit da máscara cheia desligado) grava ZERO na lane — as duas
    /// máscaras são DISTINTAS aqui (diferente de {@link LoadStore}, que usa uma única máscara
    /// fundida). No store, só a máscara cheia importa: elemento fora dela não é escrito na memória.
    record WideningLoadStore(
            /// `Qd` (`0`-`7` por construção — `@vldst_wn` extrai só 3 bits, "no D bit").
            int qd,
            /// `Rn` (base, `0`-`7` por construção — nunca `13`/`15`, checagem da B16.3 vacuamente
            /// satisfeita aqui, ver Armadilha 1/item 1 da task).
            int rn,
            /// Offset com sinal, JÁ escalado pelo tamanho em MEMÓRIA (`imm7 << memorySizeLog2` —
            /// achado medido contra `do_ldst` real: é o `msize` da macro `DO_VLDST_WIDE_NARROW`
            /// que escala, não o tamanho do registrador).
            int offset,
            /// Log2 do tamanho do elemento NA MEMÓRIA (`0`=byte, `1`=halfword).
            int memorySizeLog2,
            /// Log2 do tamanho do elemento NO REGISTRADOR (`1`=halfword, `2`=word) — sempre maior
            /// que {@link #memorySizeLog2} (é sempre um alargamento/estreitamento real).
            int registerSizeLog2,
            /// `true` para `VLDR*` (memória → `Qd`, com extensão); `false` para `VSTR*` (`Qd` →
            /// memória, com truncamento). Só `load` pode ter {@link #signed} `false` (`u=1`); um
            /// store sempre tem `U=0` (recusado no decode, campo aqui é sempre irrelevante).
            boolean load,
            /// `true` = estende com SINAL (`u=0`); `false` = estende com ZERO (`u=1`). Ignorado
            /// quando {@link #load} é `false` (store trunca, não estende).
            boolean signed,
            /// `true` quando `Rn` recebe o endereço pós-offset (writeback SEMPRE incondicional —
            /// G4, mesma regra de {@link LoadStore}).
            boolean writeback,
            /// `true` para pós-index (`P=0`, `W` forçado); `false` para pré-index/offset (`P=1`).
            boolean postIndexed,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_WIDENING_LOAD_STORE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveWideningLoadStore(core, this); }
    }

    /// `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (perfil M, B16.5, MVE/Helium, `target/isa-decode/mve.decode`):
    /// gather load / scatter store por vetor de OFFSETS em `qm` — cada lane de `qm` (largura
    /// {@link #registerSizeLog2}) é somada a `Rn` (mais o escalonamento de {@link #offsetScaled},
    /// pelo tamanho em MEMÓRIA) para formar um endereço INDEPENDENTE por lane (verbatim de
    /// `DO_VLDR_SG`/`DO_VSTR_SG`, `target/arm/tcg/mve_helper.c`). Duas máscaras distintas no load
    /// (mesmo padrão de {@link WideningLoadStore}): `eciMask` decide se a lane é tocada (beat
    /// abandonado preserva); `elementMask` decide entre carregar de verdade ou gravar ZERO. No
    /// store só `elementMask` importa. **Sem writeback** (`@vldst_sg` não tem campo `w`).
    record GatherScatterOffset(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`), o vetor de offsets — recusado no decode quando `Qd == Qm`
            /// (UNPREDICTABLE real: o registrador de offsets seria sobrescrito no meio da operação).
            int qm,
            /// `Rn` (base escalar; `15` recusado no decode — UNPREDICTABLE).
            int rn,
            /// Log2 do tamanho do elemento NA MEMÓRIA (`msize`, `0`-`3`).
            int memorySizeLog2,
            /// Log2 do tamanho do elemento NO REGISTRADOR (`size`, `0`-`3`) — também a largura em
            /// que `qm` é lido lane a lane. `3` (doubleword) usa o par de acessos de 32 bits
            /// verbatim de `DO_VLDR64_SG`/`DO_VSTR64_SG` (offset lido só das lanes PARES de `qm`).
            int registerSizeLog2,
            /// `true` estende com SINAL (`VLDR_S_sg`); `false` estende com ZERO (`VLDR_U_sg`/
            /// `VSTR_sg`, campo irrelevante no store).
            boolean signedLoad,
            /// `os`: `true` escala o offset lido de `qm` por `1 << memorySizeLog2` antes de somar a
            /// `Rn` (`ADDR_ADD_OSH`/`OSW`/`OSD`); `false` soma sem escalar (`ADDR_ADD`).
            boolean offsetScaled,
            /// `true` para `VLDR_S_sg`/`VLDR_U_sg` (memória → `Qd`); `false` para `VSTR_sg`.
            boolean load,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_GATHER_SCATTER_OFFSET; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveGatherScatterOffset(core, this); }
    }

    /// `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (perfil M, B16.5, MVE/Helium):
    /// gather/scatter com base VETORIAL (`qm`, cada lane já contém um ENDEREÇO completo — não um
    /// offset) mais um imediato ESCALAR somado a todas as lanes (`do_ldst_sg_imm` real: os
    /// parâmetros "base"/"offset" trocam de papel em relação a {@link GatherScatterOffset}, mas
    /// a fórmula de endereço é a MESMA soma). **`Qm` vem do campo normalmente rotulado `Qn`**
    /// (`@vldst_sg_imm qm=%qn` — comentário literal do arquivo, Armadilha 1 da task). Writeback é
    /// POR LANE (`w=1`: cada lane de `qm` recebe seu próprio endereço calculado, não um único `Rn`
    /// escalar) e roda sempre que a lane está ativa por `ECI` (independente do `elementMask` de
    /// `VPT` — verbatim do `if (WB) { m[e] = addr; }` dentro do `if (eci_mask)` de `DO_VLDR_SG`).
    record GatherScatterImmediate(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Qm` (`0`-`7`, extraído via `%qn` — Armadilha 1), o vetor de endereços base.
            int qm,
            /// Offset com sinal (`imm7 << sizeLog2`, `a=0` nega — mesma convenção de
            /// {@link LoadStore#offset}).
            int offset,
            /// Log2 do tamanho do elemento (`2`=`W`, `3`=`D` — memória e registrador SEMPRE do
            /// mesmo tamanho aqui, sem alargamento).
            int sizeLog2,
            /// `true` quando cada lane ativa de `qm` recebe de volta seu endereço calculado.
            boolean writeback,
            /// `true` para `VLDRW_sg_imm`/`VLDRD_sg_imm`; `false` para `VSTRW_sg_imm`/`VSTRD_sg_imm`.
            boolean load,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_GATHER_SCATTER_IMMEDIATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveGatherScatterImmediate(core, this); }
    }

    /// `VLD2`/`VLD4`/`VST2`/`VST4` (perfil M, B16.5, MVE/Helium): desentrelaçamento/entrelaçamento
    /// de um grupo de {@link #groupSize} registradores `Q` consecutivos a partir de `Qd`, em 4
    /// "beats" de 32 bits cada (verbatim de `DO_VLD2*`/`DO_VLD4*`/`DO_VST2*`/`DO_VST4*`,
    /// `target/arm/tcg/mve_helper.c` — tabelas `off[]` por {@link #pat} transcritas em
    /// `IrSystemExecutor`). **Beatwise mas NÃO predicado** (comentário literal do QEMU real): só
    /// `eciMask` gate cada beat, `elementMask`/`VPT` nunca é consultado — por isso usa
    /// {@link MvePredicationOp.AdvanceEci} (não {@link MvePredicationOp.AdvanceVpt}) como gancho pós-instrução, já que
    /// `mve_update_and_store_eci` NUNCA toca `VPR.MASK01`/`MASK23`, ao contrário de
    /// `mve_advance_vpt`. Writeback (quando presente) é incondicional, soma `groupSize * 16` bytes
    /// a `Rn` (`do_vldst_il` real: `addrinc` = `32` para grupo 2, `64` para grupo 4).
    record InterleavedLoadStore(
            /// `Qd`, primeiro registrador do grupo (`VLD2`/`VST2`: `Qd <= 6`; `VLD4`/`VST4`:
            /// `Qd <= 4` — recusado no decode senão, `Qd+groupSize-1` estouraria `Q7`).
            int qd,
            /// `Rn` (base; `15` sempre recusado, `13` recusado quando {@link #writeback}).
            int rn,
            /// `2` (`VLD2`/`VST2`) ou `4` (`VLD4`/`VST4`) — quantos `Q` consecutivos o grupo cobre.
            int groupSize,
            /// Log2 do tamanho do elemento (`0`=byte, `1`=halfword, `2`=word).
            int sizeLog2,
            /// `pat` (`0`-`3`): qual "fatia" do grupo esta instrução move — a arquitetura real
            /// decompõe um `VLD4.8 {Qd-Qd+3}` em 4 instruções `VLD4` consecutivas, uma por `pat`,
            /// cada uma cobrindo 4 estruturas via `off[]` (Armadilha 6 da task: `pat` não é "qual
            /// registrador").
            int pat,
            /// `true` para `VLD2`/`VLD4` (memória → grupo); `false` para `VST2`/`VST4`.
            boolean load,
            /// `true` quando `Rn` recebe o endereço pós-incremento (incondicional — G4).
            boolean writeback,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_INTERLEAVED_LOAD_STORE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveInterleavedLoadStore(core, this); }
    }

    /// `VIDUP`/`VDDUP` (perfil M, B16.5, MVE/Helium): preenche `Qd` com `Rn, Rn+passo, Rn+2·passo,
    /// ...` (verbatim de `DO_VIDUP`, `target/arm/tcg/mve_helper.c`) e escreve de volta em `Rn` o
    /// valor de continuação (SEM truncar ao tamanho do elemento — só a gravação em `Qd` trunca,
    /// `Rn` acumula em 32 bits cheios para sempre). `VDDUP` é `VIDUP` com {@link #imm} já NEGADO
    /// pelo decoder (`a->imm = -a->imm`, `trans_VDDUP` real) — mesmo `IrOp`, sem campo de direção.
    /// Predicado por `elementMask` (lane mascarada preserva o valor atual de `Qd`, via
    /// `mergemask`); beatwise ({@link MvePredicationOp.AdvanceVpt}, a própria `HELPER` chama `mve_advance_vpt`).
    record IncrementDup(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Rn` (sempre PAR por construção do encoding — `%vidup_rn`, nunca `13`/`15`).
            int rn,
            /// Log2 do tamanho do elemento (`0`-`2`; `size==3` é "outro encoding", recusado no
            /// decode).
            int sizeLog2,
            /// Passo somado a cada lane sucessiva — já com o sinal aplicado (`VDDUP` chega aqui com
            /// `imm` negativo).
            int imm,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_INCREMENT_DUP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveIncrementDup(core, this); }
    }

    /// `VIWDUP`/`VDWDUP` (perfil M, B16.5, MVE/Helium): como {@link IncrementDup}, mas o
    /// contador ENVOLVE (wrap) em `Rm` — `VIWDUP`: `offset+=imm; if (offset==Rm) offset=0`
    /// (`do_add_wrap` real); `VDWDUP`: `if (offset==0) offset=Rm; offset-=imm` (`do_sub_wrap`
    /// real) — comportamentos DIFERENTES o suficiente para não caberem no mesmo `imm` com sinal
    /// trocado (ao contrário de {@link IncrementDup}, por isso um campo {@link #decrement}
    /// explícito em vez de negar `imm`).
    record WrappingIncrementDup(
            /// `Qd` (`0`-`7`).
            int qd,
            /// `Rn` (sempre PAR por construção — `%vidup_rn`).
            int rn,
            /// `Rm` (sempre ÍMPAR por construção — `%vidup_rm`; `13`/`15` recusados no decode —
            /// UNPREDICTABLE).
            int rm,
            /// Log2 do tamanho do elemento (`0`-`2`; `size==3` recusado no decode).
            int sizeLog2,
            /// Passo (sempre não-negativo — a direção vem de {@link #decrement}, não do sinal).
            int imm,
            /// `true` para `VDWDUP` (`do_sub_wrap`); `false` para `VIWDUP` (`do_add_wrap`).
            boolean decrement,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_WRAPPING_INCREMENT_DUP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveWrappingIncrementDup(core, this); }
    }

    /// `VDUP` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode`,
    /// linhas 391-395, 3 dos 15 encodings da sub-família 2): replica `Rt` (truncado a `esz` bytes)
    /// em todas as lanes ATIVAS de `Qd` — verbatim de `HELPER(mve_vdup)` (`mve_helper.c`): `mergemask`
    /// por byte, preserva lanes inativas. **`Qd` vem de `%qn`, não `%qd`** (comentário literal do
    /// arquivo real: "Qd is in the fields usually named Qn") — achado da task, testado
    /// explicitamente. `dev.vitorsilverio.armjitter.core.VfpRegisters#replicateElement` NÃO é
    /// reusado aqui porque não suporta predicação (sobrescreve todas as lanes incondicionalmente);
    /// auditado, não reusado.
    record VectorDup(
            /// `0`(byte)/`1`(halfword)/`2`(word) — `B`/`E` do encoding real (bits 22/5).
            int esz,
            /// `Qd` (`0`-`7`, extraído de `%qn` — ver Javadoc da classe).
            int qd,
            /// `Rt` (`13`/`15` recusados no decode).
            int rt,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_VECTOR_DUP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorDup(core, this); }
    }

    /// `VMOV_to_2gp`/`VMOV_from_2gp` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`,
    /// `target/isa-decode/mve.decode`, linhas 206-208, sub-família 1 completa, 2 encodings):
    /// move 2 lanes de 32 bits de `Qd` para `Rt`/`Rt2` (`toGpr=true`) ou o inverso (`toGpr=false`).
    /// **NÃO é predicado por `VPR`** (achado confirmado verbatim contra `trans_VMOV_to_2gp`/
    /// `trans_VMOV_from_2gp`, `translate-mve.c`: só é "beatwise" pelo `ECI`, nunca chama
    /// `mve_element_mask` — mesma categoria "beatwise mas não predicado" de {@link
    /// InterleavedLoadStore}, `AdvanceEci` em vez de `AdvanceVpt`). `idx` seleciona o par de lanes
    /// de 32 bits: `idx=0` → `{lane 0, lane 2}`; `idx=1` → `{lane 1, lane 3}` (`vd=Qd*2`/`vd+1` em
    /// termos de `D`, `idx` é o índice de 32 bits DENTRO de cada `D`). **Achado real que CORRIGE a
    /// task**: o QEMU real só recusa `rt == rt2` em `VMOV_to_2gp` — `VMOV_from_2gp` NÃO tem essa
    /// checagem (`trans_VMOV_from_2gp` omite `a->rt == a->rt2` da condição, ao contrário do
    /// `trans_VMOV_to_2gp`; confirmado lendo as duas funções lado a lado, não a versão genérica que
    /// a task citava). `Rt`/`Rt2` `∈ {13,15}` recusados nos dois sentidos.
    record MoveLanesGpr(
            /// `true` = `Qd` → `Rt`/`Rt2` (`VMOV_to_2gp`); `false` = `Rt`/`Rt2` → `Qd`
            /// (`VMOV_from_2gp`).
            boolean toGpr,
            /// `Qd` (`0`-`7`).
            int qd,
            /// Seleciona o par de lanes de 32 bits (`{0,2}` se `0`, `{1,3}` se `1`).
            int idx,
            /// `Rt` (lane par do par selecionado).
            int rt,
            /// `Rt2` (lane ímpar do par selecionado).
            int rt2,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_MOVE_LANES_GPR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveMoveLanesGpr(core, this); }
    }

    /// `Vimm_1r` (perfil M, B16.13a, MVE/Helium, `FEAT_MVE_INTEGER`, `target/isa-decode/mve.decode`,
    /// linha 597, sub-família 4): `VORR`/`VBIC`/`VMOV`/`VMVN` de imediato modificado, decididos pelo
    /// DECODER via {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate#expand}
    /// (MESMO núcleo do NEON A32, `NeonMoveOp.ModifiedImmediate`/RFC B13.2 D1) — `op`/`imm64` chegam JÁ
    /// resolvidos, como {@code NeonMoveOp.ModifiedImmediate} já faz. Diferença do NEON A32: aqui a operação
    /// é PREDICADA por byte a granularidade de palavra de 64 bits (`DO_1OP_IMM`/`mergemask`,
    /// `mve_helper.c`) — lane cujo byte de máscara está desligado PRESERVA o destino.
    record VectorModifiedImmediate(
            /// Operação já classificada pelo decoder (`MOV`/`MVN`/`ORR`/`BIC`).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp op,
            /// Imediato de 64 bits já expandido pelo decoder.
            long imm64,
            /// `Qd` (`0`-`7`).
            int qd,
            /// Condição necessária para executar.
            Condition condition) implements MveMoveOp {
        @Override public int kind() { return Kind.MVE_VECTOR_MODIFIED_IMMEDIATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { return executor.systemExecutor().executeMveVectorModifiedImmediate(core, this); }
    }
}
