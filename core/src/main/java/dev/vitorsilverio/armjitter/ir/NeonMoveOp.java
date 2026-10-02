package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações NEON de movimentação de dados: load/store de estruturas, permutação, extração,
/// consulta a tabela, `VDUP` e imediato modificado.
///
/// Sub-interface selada de {@link NeonOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface NeonMoveOp extends NeonOp permits NeonMoveOp.LoadStoreMultiple,
        NeonMoveOp.LoadStoreSingle, NeonMoveOp.LoadAllLanes, NeonMoveOp.ModifiedImmediate,
        NeonMoveOp.SwapPermute, NeonMoveOp.Extract, NeonMoveOp.TableLookup,
        NeonMoveOp.DuplicateScalar {

    /// `VLD1`-`VLD4`/`VST1`-`VST4` NEON A32, forma "multiple structures" (B13.3) — transfere
    /// {@link #nregs} repetições de {@link #interleave} registradores `D` para/de memória
    /// CONSECUTIVA, com os elementos de uma estrutura ENTRELAÇADOS quando `interleave > 1`
    /// ("array of structures"). Cada registrador tocado tem `8 >> esz` elementos de `1 << esz`
    /// bytes; o registrador `D` acessado é `vd + reg + stride * xs` (`reg` em `0..nregs`, `xs` em
    /// `0..interleave`), exatamente o `tt` de `trans_VLDST_multiple` do QEMU real
    /// (`target/arm/tcg/translate-neon.c`).
    ///
    /// Espelho estrutural de
    /// {@link dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64.LoadStoreMultiple}, mas com
    /// diferenças reais: NEON de 32 bits tem `stride` ("double spacing", inexistente no A64), NÃO
    /// faz wrap-around módulo 32 (registrador além de `D31` é UNDEFINED, recusado no decoder) e
    /// nunca escreve destrutivamente fora do `D` nomeado (VFP32 não zera bits altos).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record LoadStoreMultiple(
            /// `true` para `VLD1`-`VLD4`, `false` para `VST1`-`VST4`.
            boolean load,
            /// Primeiro registrador `D` transferido (índice `0`-`31`).
            int vd,
            /// Registrador base ARM (índice `0`-`14`; `15`/PC é recusado no decoder).
            int rn,
            /// Campo `rm` CRU do encoding: `15` = sem escrita de volta; `13` = escrita de volta
            /// IMEDIATA (`Rn += nregs * interleave * 8` bytes); qualquer outro valor = registrador
            /// ARM cujo conteúdo é somado a `Rn` depois da transferência.
            int rm,
            /// `log2` do tamanho de cada elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword (só válido quando `interleave == 1 && stride == 1`).
            int esz,
            /// Quantas vezes o grupo de {@link #interleave} registradores se repete (`1`-`4`).
            int nregs,
            /// Quantos registradores `D` compõem UMA estrutura entrelaçada (`1`=`VLD1`/`VST1`, ...,
            /// `4`=`VLD4`/`VST4`).
            int interleave,
            /// Espaçamento entre registradores `D` de uma estrutura (`1` = consecutivos, `2` =
            /// "double spacing", `D<n>`, `D<n+2>`, ...).
            int stride) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_LOAD_STORE_MULTIPLE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonLoadStoreMultiple(core, this); return false; }
    }

    /// `VLD1`-`VLD4`/`VST1`-`VST4` NEON A32, forma "single structure to one lane" (B13.3) —
    /// transfere UM elemento de `1 << esz` bytes para/de a lane {@link #index} de cada um dos
    /// {@link #selem} registradores `vd + stride * xs` (`xs` em `0..selem`), SEM afetar nenhum
    /// outro bit desses registradores. Espelho de
    /// {@link dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64.LoadStoreSingle} (mesmas diferenças
    /// que {@link LoadStoreMultiple}). {@link #condition()} sempre {@link Condition#AL}.
    record LoadStoreSingle(
            /// `true` para `VLD1`-`VLD4`, `false` para `VST1`-`VST4`.
            boolean load,
            /// Primeiro registrador `D` transferido (índice `0`-`31`).
            int vd,
            /// Registrador base ARM (índice `0`-`14`; `15`/PC é recusado no decoder).
            int rn,
            /// Campo `rm` CRU do encoding, mesma convenção de {@link LoadStoreMultiple#rm}
            /// (escrita de volta imediata avança `selem << esz` bytes).
            int rm,
            /// `log2` do tamanho do elemento em bytes (`0`-`2`; não há forma doubleword de lane
            /// única).
            int esz,
            /// Quantos registradores `D` consecutivos (por {@link #stride}) recebem/fornecem o
            /// elemento (`1`=`VLD1`/`VST1`, ..., `4`=`VLD4`/`VST4`).
            int selem,
            /// Espaçamento entre registradores `D` (`1` ou `2`), ver
            /// {@link LoadStoreMultiple#stride}.
            int stride,
            /// Índice da lane que recebe/fornece o elemento (faixa depende de `esz`: `0`-`7`
            /// byte, `0`-`3` halfword, `0`-`1` word).
            int index) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_LOAD_STORE_SINGLE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonLoadStoreSingle(core, this); return false; }
    }

    /// `VLD1R`-`VLD4R` NEON A32, forma "single structure to all lanes" (B13.3) — lê UM elemento
    /// de `1 << esz` bytes por registrador (mesmo padrão de endereçamento de
    /// {@link LoadStoreSingle}, `selem` registradores por {@link #stride}) e REPLICA esse
    /// valor por todas as lanes do `D`; quando {@link #quad}, replica também no `D` seguinte do
    /// par (`selem` é sempre `1` nesse caso). Não existe forma `VST`. Espelho de
    /// {@link dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64.LoadSingleReplicate}.
    /// {@link #condition()} sempre {@link Condition#AL}.
    record LoadAllLanes(
            /// Primeiro registrador `D` preenchido (índice `0`-`31`).
            int vd,
            /// Registrador base ARM (índice `0`-`14`; `15`/PC é recusado no decoder).
            int rn,
            /// Campo `rm` CRU do encoding, mesma convenção de {@link LoadStoreMultiple#rm}
            /// (escrita de volta imediata avança `selem << esz` bytes).
            int rm,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Quantos registradores `D` são preenchidos (`1`=`VLD1R`, ..., `4`=`VLD4R`).
            int selem,
            /// Espaçamento entre registradores `D` (`1` ou `2`), ver
            /// {@link LoadStoreMultiple#stride}.
            int stride,
            /// `true` (só possível quando `selem == 1`) para replicar também no `D` seguinte
            /// (`bit t` do encoding, arranjo de 128 bits nomeado por DOIS `D`).
            boolean quad) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_LOAD_ALL_LANES; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonLoadAllLanes(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits, "1-reg-and-modified-immediate" (B13.9): `VMOV`/`VMVN`/`VORR`/
    /// `VBIC` imediato — `cmode`/`op` discriminam as 4 famílias na função de trans (uma linha de
    /// decodetree, `Vimm_1r`). **Não há `Vm`/`Vn`**: bits[3:0] são metade do imediato, não um
    /// registrador.
    ///
    /// {@link #imm64} já vem EXPANDIDO pelo decoder (núcleo COMPARTILHADO
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate#expand}, RFC B13.2 D1,
    /// aplicada ANTES da duplicação — o lado A64/`Vimm` da B19.6 reusa o MESMO núcleo). `MVN`
    /// carrega o MESMO `imm64` de `MOV` (a inversão acontece na EXECUÇÃO — Decisão 2 da B13.9, não
    /// dobrar `MVN` em `MOV` invertido).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ModifiedImmediate(
            /// Operação a executar (`MOV`/`MVN`/`ORR`/`BIC`, já classificada pelo decoder).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`, bit `Q` do encoding), `false` para o de
            /// 64 bits (`D<d>`).
            boolean quad,
            /// Imediato de 64 bits já EXPANDIDO (ver acima) — nunca recalculado no executor.
            long imm64,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`. Também é FONTE em `ORR`/`BIC` (leem `Vd` atual).
            int vd) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_MODIFIED_IMMEDIATE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonModifiedImmediate(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VSWP`/`VTRN`/`VUZP`/`VZIP` (B13.14, "2-reg-misc grouping"
    /// `opc1=0b10` `opc2` `0000`-`0011` — MESMO frame/decoder de {@link NeonIntegerOp.Unary} e companhia,
    /// B13.12). **Exceção do épico**: sem equivalente A64 (`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`
    /// são SEIS instruções de UM destino, não a mesma semântica — ver Javadoc de
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp}). {@link #vd}/{@link #vm}
    /// são FONTE **e** DESTINO (troca no lugar) — núcleo COMPARTILHADO
    /// ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#swapPermute}) usa buffer (E10).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record SwapPermute(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp op,
            /// `true` para o arranjo de 128 bits (`Q<d>`/`Q<m>`), `false` para o de 64 bits
            /// (`D<d>`/`D<m>`).
            boolean quad,
            /// `log2` do tamanho do elemento em bytes — `0`-`2` (byte/half/word; `3` é reservado,
            /// recusado pelo decoder). Ignorado por {@link #op}=`SWAP` (troca completa).
            int esz,
            /// Registrador FONTE e DESTINO 1, em índice de `D` (`0`-`31`); na forma `quad` é o `D`
            /// par que inicia o `Q`.
            int vd,
            /// Registrador FONTE e DESTINO 2, em índice de `D` (`0`-`31`); na forma `quad` é o `D`
            /// par que inicia o `Q`.
            int vm) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_SWAP_PERMUTE; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonSwapPermute(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VEXT` (B13.14, fora do sub-layout "2-reg-misc": bit24=0
    /// distingue do resto de `size==0b11`). Concatena `Vm:Vn` (`Vn` nos bytes BAIXOS) e extrai uma
    /// janela de {@code datasize} bytes começando em {@link #imm} bytes — puramente reorganização de
    /// bytes, sem aritmética. Migração D1 do MESMO algoritmo de
    /// {@link dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64.Extract} (B8.10) — núcleo COMPARTILHADO
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#extract}.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Extract(
            /// `true` para arranjo de 128 bits (`imm` até `15`), `false` para 64 bits (`imm` até
            /// `7`).
            boolean quad,
            /// Deslocamento em BYTES (não bits) dentro da janela concatenada.
            int imm,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte 1 (metade BAIXA da concatenação), em índice de `D` (ver
            /// {@link #vd}).
            int vn,
            /// Registrador fonte 2 (metade ALTA da concatenação), em índice de `D` (ver
            /// {@link #vd}).
            int vm) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_EXTRACT; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonExtract(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VTBL`/`VTBX` (B13.14, fora do sub-layout "2-reg-misc":
    /// bit11=1 distingue do resto de `size==0b11`). Trata `Vn`, `Vn+1`, ..., `Vn+len` ({@link #len}
    /// registradores `D` consecutivos, `Vn+len` não pode passar de `D31` — checado pelo decoder,
    /// G8) como UMA tabela contígua de bytes, e substitui cada byte de {@link #vm} pelo byte da
    /// tabela no índice que ele contém — índice fora da tabela produz `0` (`VTBL`) ou preserva o
    /// byte ATUAL de {@link #vd} (`VTBX`, {@link #tbx}). Migração D1 do MESMO algoritmo de
    /// {@link dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64.TableLookup} (B8.10) — núcleo
    /// COMPARTILHADO {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes#tableLookup}.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record TableLookup(
            /// `true` para `VTBX` (índice fora da tabela preserva `Vd`), `false` para `VTBL`
            /// (produz `0`).
            boolean tbx,
            /// Quantos registradores ALÉM de {@link #vn} compõem a tabela, MENOS `1` (`0`=`1`
            /// registrador `D`, ..., `3`=`4` registradores) — nome espelha o campo `len` do encoding
            /// real.
            int len,
            /// Registrador de destino, em índice de `D` (`0`-`31`).
            int vd,
            /// Primeiro registrador `D` da tabela (`0`-`31`); os demais são `(vn+1)`...`(vn+len)`.
            int vn,
            /// Registrador `D` com os índices (um por byte).
            int vm) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_TABLE_LOOKUP; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonTableLookup(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits — `VDUP` escalar (B13.14, `VDUP_scalar`, fora do sub-layout
    /// "2-reg-misc": bit11=1 distingue do resto de `size==0b11`, MESMO espaço de `VTBL`/`VTBX`).
    /// Replica o elemento {@link #index} de {@link #vm} (tamanho {@link #esz}) por todas as lanes de
    /// {@link #vd}. **3 linhas de encoding, não campo `size` livre**: o tamanho vem do PADRÃO de
    /// bits do imediato (posição do bit `1` mais baixo em `imm4`), decodificado ANTES de construir
    /// este record — ver Javadoc do decoder.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record DuplicateScalar(
            /// `log2` do tamanho do elemento em bytes — `0`-`2` (byte/half/word), vindo do PADRÃO de
            /// bits do imediato, não de um campo `size` livre.
            int esz,
            /// Índice do elemento dentro de {@link #vm} a replicar.
            int index,
            /// `true` para o arranjo de 128 bits (`Q<d>`), `false` para o de 64 bits (`D<d>`).
            boolean quad,
            /// Registrador de destino, em índice de `D` (`0`-`31`); na forma `quad` é o `D` par que
            /// inicia o `Q`.
            int vd,
            /// Registrador fonte, em índice de `D` (`0`-`31`).
            int vm) implements NeonMoveOp {
        @Override public int kind() { return Kind.NEON_DUPLICATE_SCALAR; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonDuplicateScalar(core, this); return false; }
    }
}
