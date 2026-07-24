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
        Ir64Op.Svc, Ir64Op.Cycle, Ir64Op.Fetch {

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
}
