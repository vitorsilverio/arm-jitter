package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;

/// Operações AdvSIMD de movimentação de dados: load/store de estruturas, `DUP`/`INS`/`SMOV`/`UMOV`,
/// permutação, extração, consulta a tabela e imediato modificado.
///
/// Sub-interface selada de {@link AdvSimdOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface AdvSimdMoveOp64 extends AdvSimdOp64 permits
        AdvSimdMoveOp64.LoadStoreMultiple, AdvSimdMoveOp64.LoadStoreSingle,
        AdvSimdMoveOp64.LoadSingleReplicate, AdvSimdMoveOp64.Extract, AdvSimdMoveOp64.Permute,
        AdvSimdMoveOp64.TableLookup, AdvSimdMoveOp64.DuplicateElement,
        AdvSimdMoveOp64.DuplicateGeneral, AdvSimdMoveOp64.InsertGeneral,
        AdvSimdMoveOp64.InsertElement, AdvSimdMoveOp64.MoveElement,
        AdvSimdMoveOp64.DuplicateElementScalar, AdvSimdMoveOp64.ModifiedImmediate64,
        AdvSimdMoveOp64.LookupTable {

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store MULTIPLE structures, B8.6) — transfere `rpt`
    /// repetições de `selem` registradores consecutivos (`Vt`, `Vt+1`, ... módulo `32`), cada um
    /// com `(q ? 16 : 8) >> elementSizeLog2` elementos, para/de memória CONSECUTIVA (elementos
    /// intercalados quando `selem>1` — estrutura "array of structures"). Semântica conferida contra
    /// `target/arm/tcg/translate-a64.c` real do QEMU (`trans_LD_mult`/`trans_ST_mult`): para
    /// `r` em `0..rpt`, `e` em `0..elementos`, `xs` em `0..selem`, escreve/lê o elemento `e` do
    /// registrador `(Vt+r+xs) % 32`, avançando o endereço `1 << elementSizeLog2` bytes a cada
    /// elemento. Para `LD` (não `ST`), os registradores tocados têm os 64 bits altos ZERADOS
    /// quando `!q` (mesma disciplina "SIMD&FP destructive write" de {@link FpOp64.Alu}, mas aplicada
    /// por registrador INTEIRO aqui, não por elemento).
    record LoadStoreMultiple(
            /// `true` para `LD1`-`LD4`, `false` para `ST1`-`ST4`.
            boolean load,
            /// Primeiro registrador `V` transferido (índice `0`-`31`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`, ver {@link MemoryOp64.Load64#rn}).
            int rn,
            /// Registrador de deslocamento pós-índice (índice `0`-`30`); `-1` quando não há
            /// pós-índice OU quando o pós-índice é IMEDIATO (encoding `Rm=11111`, avança
            /// `rpt * selem * (q ? 16 : 8)` bytes — o próprio decoder já resolveu essa
            /// ambiguidade, o executor nunca lê `31` como registrador real).
            int rm,
            /// `true` para arranjo de 128 bits (`Vt.16B`/`.8H`/`.4S`/`.2D`), `false` para 64 bits
            /// (`Vt.8B`/`.4H`/`.2S`/`.1D`).
            boolean q,
            /// `true` quando há escrita de volta em {@link #rn} após a transferência (forma
            /// pós-indexada, imediata ou por registrador conforme {@link #rm}).
            boolean postIndex,
            /// `log2` do tamanho de cada elemento em bytes: `0`=byte, `1`=halfword, `2`=word,
            /// `3`=doubleword.
            int elementSizeLog2,
            /// Quantas vezes o grupo de {@link #selem} registradores se repete (`1`-`4`) — só
            /// `selem=1` permite `rpt>1` (`LD1`/`ST1` com `1`-`4` registradores); as demais
            /// combinações (`LD2`-`LD4`/`ST2`-`ST4`) têm `rpt=1`.
            int rpt,
            /// Quantos registradores compõem UMA estrutura entrelaçada na memória (`1`=`LD1`/
            /// `ST1`, `2`=`LD2`/`ST2`, `3`=`LD3`/`ST3`, `4`=`LD4`/`ST4`).
            int selem) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_LOAD_STORE_MULTIPLE; }
    }

    /// `LD1`-`LD4`/`ST1`-`ST4` (AdvSIMD load/store SINGLE structure, sem replicar, B8.6) —
    /// transfere UM elemento de `1 << elementSizeLog2` bytes para/de cada um de {@link #selem}
    /// registradores consecutivos (`Vt`, `Vt+1`, ... módulo `32`), no índice de lane {@link #index}
    /// de cada um, SEM afetar nenhum outro bit desses registradores (diferente de
    /// {@link LoadStoreMultiple}, que sempre toca o registrador inteiro). Semântica
    /// conferida contra `trans_LD_single`/`trans_ST_single` reais do QEMU: para `xs` em
    /// `0..selem`, escreve/lê o elemento {@link #index} do registrador `(Vt+xs) % 32`, avançando
    /// o endereço `1 << elementSizeLog2` bytes a cada elemento.
    record LoadStoreSingle(
            /// `true` para `LD1`-`LD4`, `false` para `ST1`-`ST4`.
            boolean load,
            /// Primeiro registrador `V` transferido (índice `0`-`31`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Registrador de deslocamento pós-índice, mesma convenção de
            /// {@link LoadStoreMultiple#rm} (`-1`=sem pós-índice ou pós-índice imediato,
            /// que aqui avança `selem << elementSizeLog2` bytes).
            int rm,
            /// `true` quando há escrita de volta em {@link #rn}.
            boolean postIndex,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`), ver
            /// {@link LoadStoreMultiple#elementSizeLog2}.
            int elementSizeLog2,
            /// Quantos registradores consecutivos recebem/fornecem o elemento (`1`-`4`).
            int selem,
            /// Índice da lane (dentro do registrador de 128 bits) que recebe/fornece o elemento —
            /// faixa depende de {@link #elementSizeLog2} (`0`-`15` byte, `0`-`7` halfword, `0`-`3`
            /// word, `0`-`1` doubleword; o bit mais significativo do índice É o próprio `Q` do
            /// encoding real, resolvido pelo decoder).
            int index) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_LOAD_STORE_SINGLE; }
    }

    /// `LD1R`-`LD4R` (AdvSIMD load single structure and Replicate to all lanes, B8.6) — lê UM
    /// elemento de `1 << elementSizeLog2` bytes por registrador (mesmo padrão de endereçamento de
    /// {@link LoadStoreSingle}, `selem` registradores consecutivos) e REPLICA esse valor por
    /// todas as lanes de cada registrador (`(q ? 16 : 8) >> elementSizeLog2` cópias) — não existe
    /// forma `ST` (só faz sentido para leitura). Semântica conferida contra `trans_LD_single_repl`
    /// real do QEMU.
    record LoadSingleReplicate(
            /// Primeiro registrador `V` preenchido (índice `0`-`31`).
            int rt,
            /// Registrador base (índice `0`-`31`; `31` é SEMPRE `SP`).
            int rn,
            /// Registrador de deslocamento pós-índice, mesma convenção de
            /// {@link LoadStoreMultiple#rm} (`-1`=sem pós-índice ou pós-índice imediato, que
            /// aqui avança `selem << elementSizeLog2` bytes).
            int rm,
            /// `true` para replicar pelos 128 bits do registrador, `false` para só os 64 baixos
            /// (zerando os altos, ver {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#replicateElement}).
            boolean q,
            /// `true` quando há escrita de volta em {@link #rn}.
            boolean postIndex,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int elementSizeLog2,
            /// Quantos registradores consecutivos são preenchidos (`1`=`LD1R`, `2`=`LD2R`,
            /// `3`=`LD3R`, `4`=`LD4R`).
            int selem) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_LOAD_SINGLE_REPLICATE; }
    }

    /// `EXT` (AdvSIMD extract, B8.10) — concatena `Rm:Rn` (`Rn` ocupa os bits BAIXOS, `Rm` os
    /// ALTOS, `ARM DDI 0487` `Extract`) e extrai uma janela de `datasize` bits (`64` sem
    /// {@link #q}, `128` com) começando no deslocamento `imm*8` bits — puramente reorganização de
    /// bytes, sem aritmética. Único mnemônico desta forma (`EXT_d`/`EXT_q` do inventário são o
    /// MESMO encoding, distinto só pela largura do campo `imm`, já resolvida pelo decoder).
    record Extract(
            /// `true` para arranjo de 128 bits (`imm` até `15`), `false` para 64 bits (`imm` até
            /// `7`).
            boolean q,
            /// Deslocamento em BYTES (não bits) dentro da janela concatenada — faixa depende de
            /// {@link #q}.
            int imm,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1 (metade BAIXA da concatenação).
            int rn,
            /// Registrador `V` fonte 2 (metade ALTA da concatenação).
            int rm) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_EXTRACT; }
    }

    /// `UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2` (AdvSIMD permute, B8.10) — reorganiza os elementos
    /// de `Rn`/`Rm` numa ordem fixa (ver {@link Ir64VectorPermuteOp}), sem aritmética. Único
    /// tamanho de elemento livre desta família (diferente de {@link Extract}, que opera
    /// sempre em bytes).
    record Permute(
            /// Operação a executar.
            Ir64VectorPermuteOp op,
            /// `true` para arranjo de 128 bits, `false` para 64 bits.
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte 1.
            int rn,
            /// Registrador `V` fonte 2.
            int rm) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_PERMUTE; }
    }

    /// `TBL`/`TBX` (AdvSIMD table lookup, B8.10) — trata os registradores `Rn`, `Rn+1`, ...,
    /// `Rn+len` (módulo `32`, {@link #len} registradores no total) como UMA tabela contígua de
    /// bytes (`16*(len+1)` bytes) e substitui cada BYTE de `Rm` pelo byte da tabela no índice que
    /// ele contém — índice `>= 16*(len+1)` produz `0` ({@code TBL}) ou preserva o byte ATUAL de
    /// `Rd` ({@code TBX}, {@link #tbx}). Opera sempre byte a byte, sem `esz` (mesmo padrão de
    /// {@link Extract}).
    record TableLookup(
            /// `true` para `TBX` (índice fora da tabela preserva `Rd`), `false` para `TBL` (produz
            /// `0`).
            boolean tbx,
            /// Quantos registradores ALÉM de {@link #rn} compõem a tabela, MENOS `1` (`0`=`1`
            /// registrador, ..., `3`=`4` registradores) — nome espelha o campo `len` do encoding
            /// real (`ARM DDI 0487`), não "quantidade" para evitar off-by-one silencioso.
            int len,
            /// `true` para processar os 16 bytes de {@link #rm} (arranjo `16b`), `false` para só
            /// os 8 baixos (arranjo `8b`).
            boolean q,
            /// Registrador `V` de destino.
            int rd,
            /// Primeiro registrador `V` da tabela (índice `0`-`31`; os demais são
            /// `(rn+1)%32`...`(rn+len)%32`).
            int rn,
            /// Registrador `V` com os índices (um por byte).
            int rm) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_TABLE_LOOKUP; }
    }

    /// `DUP` (AdvSIMD copy, elemento vetorial, B8.12) — replica o elemento `esz` de `Vn[index]`
    /// por todas as lanes de `Vd` (`ARM DDI 0487 C6.2.109`). `esz`/`index` vêm de `imm5` no
    /// encoding real (`esz = LowestSetBit(imm5)`, `index = imm5 >>> (esz+1)`), já resolvidos pelo
    /// decoder.
    record DuplicateElement(
            /// `true` para arranjo de 128 bits, `false` para 64 (zera os bits altos, mesma
            /// disciplina de {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#setD}).
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`; `3` exige {@link #q}).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn,
            /// Índice do elemento fonte dentro de {@link #rn}.
            int index) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_DUPLICATE_ELEMENT; }
    }

    /// `DUP` (AdvSIMD copy, registrador geral, B8.12) — replica `Wn`/`Xn` (`esz`{@code ==3}
    /// escolhe `Xn`, senão `Wn`) por todas as lanes de `Vd`.
    record DuplicateGeneral(
            /// `true` para arranjo de 128 bits, `false` para 64 (zera os bits altos).
            boolean q,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`; `3` exige {@link #q}).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador geral fonte (índice `0`-`31`; `31` é `WZR`/`XZR`, sem forma `SP`).
            int rn) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_DUPLICATE_GENERAL; }
    }

    /// `INS` (AdvSIMD copy, registrador geral, B8.12) — grava `Wn`/`Xn` no elemento `esz` de
    /// `Vd[index]`, SEM afetar o resto de `Vd` (escrita não-destrutiva, `ARM DDI 0487 C6.2.176`,
    /// forma "general"). `Q` é sempre `1` no encoding real (não uma escolha de arranjo — o
    /// decoder já validou isso).
    record InsertGeneral(
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Registrador `V` de destino (elemento único modificado, resto preservado).
            int rd,
            /// Registrador geral fonte (índice `0`-`31`; `31` é `WZR`/`XZR`).
            int rn,
            /// Índice do elemento de destino dentro de {@link #rd}.
            int index) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_INSERT_GENERAL; }
    }

    /// `INS` (AdvSIMD copy, elemento vetorial, B8.12) — copia o elemento `esz` de
    /// `Vn[srcIndex]` para `Vd[destIndex]`, SEM afetar o resto de `Vd` (`ARM DDI 0487 C6.2.176`,
    /// forma "element"). `Q` é sempre `1` no encoding real (decoder já validou).
    record InsertElement(
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Registrador `V` de destino (elemento único modificado, resto preservado).
            int rd,
            /// Registrador `V` fonte.
            int rn,
            /// Índice do elemento de destino dentro de {@link #rd}.
            int destIndex,
            /// Índice do elemento fonte dentro de {@link #rn}.
            int srcIndex) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_INSERT_ELEMENT; }
    }

    /// `SMOV`/`UMOV` (AdvSIMD copy, B8.12) — lê o elemento `esz` de `Vn[index]` e grava em `Rd`
    /// (`Wd` ou `Xd`, conforme {@link #wide}), com ou sem extensão de sinal conforme
    /// {@link #signed} (`ARM DDI 0487 C6.2.240/C6.2.355`). Um único record cobre as duas
    /// instruções — mesma técnica de {@link FpOp64.GeneralRegisterMove} — porque a única diferença
    /// semântica é sinal vs. zero-extensão; o decoder já valida as combinações `esz`/`wide`
    /// permitidas por instrução (`UMOV`: `wide == (esz==3)` sempre; `SMOV`: `esz<3`, e `esz==2`
    /// exige `wide`).
    record MoveElement(
            /// `true` para `SMOV` (extensão de sinal), `false` para `UMOV` (zero-extensão — já
            /// implícita em {@link dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters#element}).
            boolean signed,
            /// `true` para `Xd` (64 bits), `false` para `Wd` (32 bits, zero os altos do `X`
            /// correspondente).
            boolean wide,
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Registrador geral de destino (índice `0`-`31`; `31` é `WZR`/`XZR`).
            int rd,
            /// Registrador `V` fonte.
            int rn,
            /// Índice do elemento fonte dentro de {@link #rn}.
            int index) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_MOVE_ELEMENT; }
    }

    /// `DUP <V><d>, <Vn>.<T>[<index>]` (`ARM DDI 0487`, B19.6 bloco E, "Advanced SIMD scalar copy")
    /// — forma ESCALAR do {@link DuplicateElement}: grava só o elemento no LANE `0` de
    /// {@link #rd} e ZERA o resto do registrador de 128 bits (nunca replica pelas outras lanes,
    /// diferente da forma vetorial, que é por isso um record separado em vez de reaproveitar
    /// {@link DuplicateElement} com um `boolean scalar` — a semântica de "zera tudo fora do
    /// elemento" não é um simples `!q`).
    record DuplicateElementScalar(
            /// `log2` do tamanho do elemento em bytes (`0`-`3`).
            int esz,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn,
            /// Índice do elemento fonte dentro de {@link #rn}.
            int index) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_DUPLICATE_ELEMENT_SCALAR; }
    }

    /// `MOVI`/`MVNI`/`ORR`/`BIC` imediato AdvSIMD (`Vimm`) + `FMOV` de meia precisão imediato
    /// (`FMOVI_v_h`, `FEAT_FP16`) — `ARM DDI 0487`, B19.6 bloco G, irmão A64 direto de
    /// `IrOp.NeonModifiedImmediate` (B13.9, 32 bits): MESMO núcleo compartilhado
    /// {@link dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediate}, `imm64` já EXPANDIDO
    /// pelo decoder (nunca recalculado na execução). Diferença real vs o irmão de 32 bits: escrita
    /// SEMPRE destrutiva — {@link #q}{@code ==false} zera `Rd[127:64]` (A32 não tem esse conceito,
    /// `D` é um registrador independente); `{@link #op}==MOV` com `cmode=1111,op=1` é `FMOV`
    /// (imediato de 64 bits, reaproveita `Aarch64Decoder#expandFpImmediate` — combinação reservada
    /// em AArch32, válida aqui).
    record ModifiedImmediate64(
            /// Operação real (`MOV`/`MVN`/`ORR`/`BIC` — `FMOVI_v_h`/`FMOV` de 64 bits classificam
            /// como `MOV`, mesmo padrão de {@code AdvSimdModifiedImmediate#classify}).
            AdvSimdModifiedImmediateOp op,
            /// `true` para arranjo de 128 bits (aplica a {@link #imm64} às DUAS metades, cada uma
            /// independentemente), `false` para 64 (zera a metade alta).
            boolean q,
            /// Registrador `V` de destino.
            int rd,
            /// Imediato de 64 bits já expandido (aplicado por METADE, não replicado cru).
            long imm64) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.ADV_SIMD_MODIFIED_IMMEDIATE_64; }
    }

    /// `LUTI2`/`LUTI4` (AdvSIMD lookup table, `FEAT_LUT`, B19.8) — consulta de tabela por lane com
    /// índices EMPACOTADOS: cada elemento de `Rm` carrega vários índices de `2` (`LUTI2`) ou `4`
    /// (`LUTI4`) bits, e {@link #idx} seleciona qual grupo empacotado usar. A tabela é `Rn` (mais
    /// `(rn+1)%32` quando a tabela ocupa 2 registradores, forma `LUTI4_2h`) — **confirmado contra
    /// o fonte real do QEMU** (`target/arm/tcg/vec_helper.c`, `HELPER(gvec_luti2_b)`/etc.: o
    /// argumento `table` do helper recebe o operando `Rn`, `indexes` recebe `Rm`), MESMA convenção
    /// já usada por {@link TableLookup} (`TBL`/`TBX`, onde `Rn` é a tabela e `Rm` os
    /// índices) — a task original invertia essa leitura ("índices em `Rn`, tabela em `Rm`"), erro
    /// de transcrição corrigido nesta implementação (ver `## Resultado` da task).
    record LookupTable(
            /// `true` para `LUTI4` (índice de 4 bits, tabela de 16 entradas), `false` para `LUTI2`
            /// (índice de 2 bits, tabela de 4 entradas).
            boolean four,
            /// `log2` do tamanho do elemento em bytes: `0`=byte (`_1b`), `1`=halfword (`_2h`/`_1h`).
            /// `LUTI4_2h` é o único caso com tabela de 2 registradores (`esz=1` e `four=true`).
            int esz,
            /// Seleciona qual grupo empacotado de índices usar dentro de {@link #rm} — largura
            /// depende de {@link #four}/{@link #esz} (`LUTI2_1b`: 2 bits; `LUTI2_1h`: 3 bits;
            /// `LUTI4_1b`: 1 bit; `LUTI4_2h`: 2 bits), já validada pelo decoder.
            int idx,
            /// Registrador `V` de destino.
            int rd,
            /// Primeiro registrador `V` da tabela (o segundo, quando existir, é `(rn+1)%32`).
            int rn,
            /// Registrador `V` com os índices empacotados.
            int rm) implements AdvSimdMoveOp64 {
        @Override public int kind() { return Kind.VECTOR_LOOKUP_TABLE; }
    }
}
