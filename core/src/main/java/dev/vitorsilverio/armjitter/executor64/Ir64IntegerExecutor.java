package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.Crc32Checksum;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;

import java.math.BigInteger;

/// Semântica das operações inteiras A64 ({@link IntegerOp64}): ALU, deslocamentos, bitfield,
/// multiplicação/divisão, operações sobre flags, autenticação de ponteiro e tags de endereço.
///
/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}
/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.
public final class Ir64IntegerExecutor {
    /// Índice de encoding (`31`) que designa `SP` em {@link IntegerOp64.AluExtendedRegister} — mesmo
    /// valor de {@link Ir64MemoryExecutor#BASE_REGISTER_SP_ENCODING}, nomeado separadamente porque aparece num
    /// contexto de ALU (não endereçamento de memória). A resolução SEMPRE checa o índice (`==
    /// 31`), nunca só a flag booleira do op (ver {@link #executeAluExtendedRegister} e a
    /// diferença deliberada com {@link #executeAlu}, documentada na task B6.3.1).
    private static final int ALU_STACK_POINTER_ENCODING = 31;
    /// Máscara para a metade baixa de 32 bits — mesma disciplina de largura usada no resto do
    /// executor (`W` sempre zero-estende para os 64 bits altos).
    private static final long LOW_32_BITS_MASK = 0xFFFF_FFFFL;
    /// Largura em bits de uma operação `X` (64 bits) — usada por {@link #executeBitfield} para
    /// calcular `pos`/`len` conforme {@link IntegerOp64.Bitfield#wide()} (B6.3.2).
    private static final int BITFIELD_WIDE_BITSIZE = 64;
    /// Largura em bits de uma operação `W` (32 bits) — ver {@link #BITFIELD_WIDE_BITSIZE}.
    private static final int BITFIELD_NARROW_BITSIZE = 32;
    /// Máscara de um halfword (16 bits) — B8.2, {@code executeDataProcessing1Source}/`REV16`.
    private static final long HALFWORD_MASK = 0xFFFFL;
    /// Máscara de um byte — mesmo uso de {@link #HALFWORD_MASK}.
    private static final long BYTE_MASK = 0xFFL;
    /// Máscara dos 4 bits de `N:Z:C:V` — MESMA ordem de bit de
    /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#nzcv()}, usada por
    /// {@code executeRotateIntoFlags} (`RMIF`, B8.2).
    private static final int NZCV_FIELD_MASK = 0xF;
    /// `2^64` como {@link BigInteger} — usado por {@link #addWithCarryFlags} para detectar
    /// carry-out da forma de 64 bits (que precisa de mais de 64 bits de precisão intermediária
    /// para ser exato — ver o Javadoc daquele método).
    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(Long.SIZE);
    /// Máscara dos 64 bits baixos como {@link BigInteger} — ver {@link #TWO_POW_64}.
    private static final BigInteger MASK_64_BITS = TWO_POW_64.subtract(BigInteger.ONE);

    private Ir64IntegerExecutor() {
    }

    /// Executa {@link IntegerOp64.Alu64}.
    public static boolean executeAlu(Aarch64Core core, IntegerOp64.Alu64 op) {
        // B6.14: `dstIsStackPointer`/`src1IsStackPointer` marcam a CLASSE do campo no encoding
        // (Rd|SP / Rn|SP), não que o operando SEJA SP — só é SP quando o índice também é `31`
        // (`ALU_STACK_POINTER_ENCODING`), mesma checagem dupla que `executeAluExtendedRegister`
        // já faz. Faltava aqui: um `add x4, x5, #imm` (Rd=4, Rn=5, sem `S`) lia SP em vez de X5 e
        // gravava o resultado em SP em vez de X4 — bug real, achado investigando corrupção de SP
        // no boot do `Raspi364Machine` (F11/B6.13).
        long operand1 = op.src1IsStackPointer() && op.src1() == ALU_STACK_POINTER_ENCODING
                ? core.sp() : core.xForWidth(op.src1(), op.wide());
        long operand2 = op.immediate();
        AluResult result = switch (op.opcode()) {
            case ADD -> addWithFlags(operand1, operand2, op.wide());
            case SUB -> subWithFlags(operand1, operand2, op.wide());
            case AND -> logicalWithFlags(operand1 & operand2, op.wide());
            case ORR -> logicalWithFlags(operand1 | operand2, op.wide());
            case EOR -> logicalWithFlags(operand1 ^ operand2, op.wide());
        };
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        if (op.dstIsStackPointer() && op.dst() == ALU_STACK_POINTER_ENCODING) {
            core.setSp(op.wide() ? result.value : (result.value & 0xFFFF_FFFFL));
        } else {
            core.setXForWidth(op.dst(), result.value, op.wide());
        }
        return false;
    }

    /// `ADD`/`SUB`/`ADDS`/`SUBS` na forma "shifted register" (B6.3.1) — `Rd`/`Rn`/`Rm` nunca são
    /// `SP` nesta forma (índice `31` é sempre `XZR`, resolvido normalmente por
    /// {@link Aarch64Core#xForWidth}/{@link Aarch64Core#setXForWidth}, sem nenhuma checagem de
    /// `SP` — ao contrário de {@link #executeAluExtendedRegister}).
    public static boolean executeAluShiftedRegister(Aarch64Core core, IntegerOp64.AluShiftedRegister op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long rawOperand2 = core.xForWidth(op.src2(), op.wide());
        long operand2 = applyShift(rawOperand2, op.shiftType(), op.shiftAmount(), op.wide());
        AluResult result = op.opcode() == Ir64AluOp.SUB
                ? subWithFlags(operand1, operand2, op.wide())
                : addWithFlags(operand1, operand2, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        core.setXForWidth(op.dst(), result.value, op.wide());
        return false;
    }

    /// `ADD`/`SUB`/`ADDS`/`SUBS` na forma "extended register" (B6.3.1) — `Rn` é SEMPRE `Rn|SP`
    /// (`ARM DDI 0487` pseudocódigo de `ADD (extended register)`: `operand1 = if n == 31 then
    /// SP[] else X[n]`); `Rd` é `Rd|SP` só quando `!setFlags` (mesmo pseudocódigo: `if d == 31 &&
    /// !setflags then SP[] = result else X[d] = result`). **A resolução SEMPRE checa o índice
    /// contra `31`, nunca só a flag** — diferente de {@link #executeAlu} (forma imediata,
    /// B6.1), que resolve incondicionalmente pela flag; esta é a mesma disciplina já usada por
    /// {@link Ir64MemoryExecutor#readBaseRegister}/{@link Ir64MemoryExecutor#writeBaseRegister} (load/store) neste mesmo arquivo.
    /// `Rm` nunca é `SP` (sempre lido por índice normal antes de estender).
    public static boolean executeAluExtendedRegister(Aarch64Core core, IntegerOp64.AluExtendedRegister op) {
        long operand1 = readAluOperandOrStackPointer(core, op.src1(), op.wide());
        long extended = extendAluOperand(core.x(op.src2()), op.extendType());
        long operand2 = extended << op.shiftAmount();
        AluResult result = op.opcode() == Ir64AluOp.SUB
                ? subWithFlags(operand1, operand2, op.wide())
                : addWithFlags(operand1, operand2, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        if (op.dstIsStackPointer() && op.dst() == ALU_STACK_POINTER_ENCODING) {
            core.setSp(op.wide() ? result.value : (result.value & LOW_32_BITS_MASK));
        } else {
            core.setXForWidth(op.dst(), result.value, op.wide());
        }
        return false;
    }

    /// Lê `Rn|SP`: `SP` (na largura pedida) quando o índice é `31`, senão o registrador normal.
    private static long readAluOperandOrStackPointer(Aarch64Core core, int index, boolean wide) {
        if (index == ALU_STACK_POINTER_ENCODING) {
            long sp = core.sp();
            return wide ? sp : (sp & LOW_32_BITS_MASK);
        }
        return core.xForWidth(index, wide);
    }

    /// Aplica o deslocamento de {@link IntegerOp64.AluShiftedRegister} respeitando a largura da
    /// operação — `LSR`/`ASR` em `W` operam sobre os 32 bits baixos (não os 64 completos), por
    /// isso o cálculo é feito em `int` quando `!wide`, não só mascarado depois.
    private static long applyShift(long value, Ir64ShiftType shiftType, int amount, boolean wide) {
        if (wide) {
            return switch (shiftType) {
                case LSL -> value << amount;
                case LSR -> value >>> amount;
                case ASR -> value >> amount;
            };
        }
        int narrow = (int) value;
        int shifted = switch (shiftType) {
            case LSL -> narrow << amount;
            case LSR -> narrow >>> amount;
            case ASR -> narrow >> amount;
        };
        return shifted & LOW_32_BITS_MASK;
    }

    /// `AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` na forma "shifted register" (B6.9) —
    /// `Rm` é deslocado (`shiftType`/`shiftAmount`, os 4 tipos incl. `ROR`), depois OPCIONALMENTE
    /// invertido bit a bit ({@link IntegerOp64.LogicalShiftedRegister#invert}) ANTES de combinar com
    /// `Rn` (inversão sempre acontece antes da operação lógica, nunca depois — `bic rd,rn,rm` =
    /// `rn AND (NOT rm)`, não `NOT(rn AND rm)`). Flags reaproveitam {@link #logicalWithFlags}
    /// (mesmo padrão de {@link #executeAlu}, D2 da task B6.3.1: `C=0,V=0` sempre).
    public static boolean executeLogicalShiftedRegister(Aarch64Core core, IntegerOp64.LogicalShiftedRegister op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long rawOperand2 = core.xForWidth(op.src2(), op.wide());
        long shifted = applyLogicalShift(rawOperand2, op.shiftType(), op.shiftAmount(), op.wide());
        long operand2 = op.invert() ? ~shifted : shifted;
        long combined = switch (op.opcode()) {
            case AND -> operand1 & operand2;
            case ORR -> operand1 | operand2;
            case EOR -> operand1 ^ operand2;
            case ADD, SUB -> throw new IllegalStateException(
                    "LogicalShiftedRegister nunca carrega ADD/SUB: " + op.opcode());
        };
        AluResult result = logicalWithFlags(combined, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        core.setXForWidth(op.dst(), result.value, op.wide());
        return false;
    }

    /// `LSLV`/`LSRV`/`ASRV`/`RORV` (B6.11) — mesma tabela de deslocamento de
    /// {@link #executeLogicalShiftedRegister} ({@link #applyLogicalShift}), mas a quantidade vem
    /// de {@link IntegerOp64.ShiftVariable#src2} EM TEMPO DE EXECUÇÃO (`mod` largura), não de um campo
    /// já resolvido pelo decoder. Nunca afeta `NZCV`.
    public static boolean executeShiftVariable(Aarch64Core core, IntegerOp64.ShiftVariable op) {
        long operand = core.xForWidth(op.src1(), op.wide());
        long rawAmount = core.xForWidth(op.src2(), op.wide());
        int amount = (int) (rawAmount & (op.wide() ? 63L : 31L));
        long result = applyLogicalShift(operand, op.shiftType(), amount, op.wide());
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// Aplica o deslocamento de {@link IntegerOp64.LogicalShiftedRegister}, incluindo `ROR` (válido
    /// só aqui — `AluShiftedRegister`/{@link #applyShift} não tem esse caso, ver
    /// {@link dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType}).
    private static long applyLogicalShift(
            long value, Ir64LogicalShiftType shiftType, int amount, boolean wide) {
        if (wide) {
            return switch (shiftType) {
                case LSL -> value << amount;
                case LSR -> value >>> amount;
                case ASR -> value >> amount;
                case ROR -> amount == 0 ? value : (value >>> amount) | (value << (Long.SIZE - amount));
            };
        }
        int narrow = (int) value;
        int shifted = switch (shiftType) {
            case LSL -> narrow << amount;
            case LSR -> narrow >>> amount;
            case ASR -> narrow >> amount;
            case ROR -> amount == 0 ? narrow : (narrow >>> amount) | (narrow << (Integer.SIZE - amount));
        };
        return shifted & LOW_32_BITS_MASK;
    }

    /// Estende `Rm` (fatia de tamanho/sinal dados por {@code extendType}) para 64 bits, ANTES do
    /// deslocamento de {@link IntegerOp64.AluExtendedRegister#shiftAmount()} — mesmo helper conceitual
    /// de `ext_and_shift_reg` (`translate-a64.c`), citado nos Fatos de referência #5 da task.
    private static long extendAluOperand(long rawRegisterValue, Ir64AluExtendType extendType) {
        return switch (extendType) {
            case UXTB -> rawRegisterValue & 0xFFL;
            case UXTH -> rawRegisterValue & 0xFFFFL;
            case UXTW -> rawRegisterValue & LOW_32_BITS_MASK;
            case UXTX -> rawRegisterValue;
            case SXTB -> (long) (byte) rawRegisterValue;
            case SXTH -> (long) (short) rawRegisterValue;
            case SXTW -> (long) (int) rawRegisterValue;
            case SXTX -> rawRegisterValue;
        };
    }

    /// `CSEL`/`CSINC`/`CSINV`/`CSNEG` (B6.3.2) — só LÊ os flags via {@link
    /// dev.vitorsilverio.armjitter.core64.PstateRegister#evalCond} para escolher entre `src1` e
    /// `f(src2)`; NUNCA os atualiza (diferente de `executeAlu*`/`setFlags`). Sem atalho para
    /// `CSET`/`CSETM`/`CINC`/`CINV`/`CNEG` (Armadilhas da task) — o caminho geral com
    /// `src1==src2==XZR` já produz o resultado correto.
    public static boolean executeConditionalSelect(Aarch64Core core, IntegerOp64.ConditionalSelect op) {
        long result;
        if (core.pstate().evalCond(op.condition())) {
            result = core.xForWidth(op.src1(), op.wide());
        } else {
            long src2 = core.xForWidth(op.src2(), op.wide());
            result = switch (op.opcode()) {
                case CSEL -> src2;
                case CSINC -> src2 + 1;
                case CSINV -> ~src2;
                case CSNEG -> -src2;
            };
        }
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `CCMP`/`CCMN` (B6.8) — D2 da task: reaproveita {@link #addWithFlags}/{@link
    /// #subWithFlags}, o MESMO cálculo já usado por {@link #executeAluShiftedRegister}, em vez de
    /// duplicar a lógica de carry/overflow. Quando {@link IntegerOp64.ConditionalCompare#condition} é
    /// falsa, `NZCV` recebe os 4 bits CRUS do encoding (`core.pstate().setNzcv(int)`) e {@link
    /// IntegerOp64.ConditionalCompare#rn}/{@link IntegerOp64.ConditionalCompare#rm} NUNCA são lidos — prova
    /// de que a implementação de fato ramifica em vez de sempre calcular e descartar (Armadilhas
    /// da task, Testes mínimos #2). Nunca escreve registrador (só `NZCV`, ver Javadoc do record).
    public static boolean executeConditionalCompare(Aarch64Core core, IntegerOp64.ConditionalCompare op) {
        if (core.pstate().evalCond(op.condition())) {
            long operand1 = core.xForWidth(op.rn(), op.wide());
            long operand2 = op.immediateForm() ? op.immediate() : core.xForWidth(op.rm(), op.wide());
            AluResult result = op.opcode() == Ir64AluOp.SUB
                    ? subWithFlags(operand1, operand2, op.wide())
                    : addWithFlags(operand1, operand2, op.wide());
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        } else {
            core.pstate().setNzcv(op.nzcv());
        }
        return false;
    }

    /// `ADC`/`ADCS`/`SBC`/`SBCS` (B8.2) — soma/subtrai COM o `C` de entrada atual (diferente de
    /// {@link #executeAlu}/{@link #executeAluShiftedRegister}, que nunca leem `C` como entrada).
    /// `SBC` é `AddWithCarry(a, NOT(b), C)` (`ARM DDI 0487` pseudocódigo de `SBC`) — reaproveita
    /// {@link #addWithCarryFlags} invertendo `b` bit a bit em vez de duplicar o cálculo.
    public static boolean executeAluWithCarry(Aarch64Core core, IntegerOp64.AluWithCarry op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long rawOperand2 = core.xForWidth(op.src2(), op.wide());
        long operand2 = op.subtract() ? ~rawOperand2 : rawOperand2;
        // `PSTATE.C` de ENTRADA (não forçado) — mesmo para SBC: `a - b - (1-C)`, ou seja,
        // `AddWithCarry(a, NOT(b), C)` com o `C` ATUAL, nunca `1` fixo (armadilha real encontrada
        // nesta task: um `carryIn` forçado para `SBC` produziria sempre `a-b` sem nunca propagar
        // borrow entre instruções encadeadas de precisão múltipla).
        boolean carryIn = core.pstate().carry();
        AluResult result = addWithCarryFlags(operand1, operand2, carryIn, op.wide());
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative, result.zero, result.carry, result.overflow);
        }
        core.setXForWidth(op.dst(), result.value, op.wide());
        return false;
    }

    /// `EXTR` (B8.2) — concatena {@link IntegerOp64.Extract#src1}`:`{@link IntegerOp64.Extract#src2} (`Rn`
    /// na metade ALTA) e extrai uma janela do tamanho da operação a partir do bit
    /// {@link IntegerOp64.Extract#lsb}. `lsb=0` é um caso especial explícito (evita deslocamento por
    /// `64`/`32`, que em Java é UB — `x << 64` não é zero, é `x << 0`, mod-largura do shift).
    public static boolean executeExtract(Aarch64Core core, IntegerOp64.Extract op) {
        long high = core.xForWidth(op.src1(), op.wide());
        long low = core.xForWidth(op.src2(), op.wide());
        int lsb = op.lsb();
        long result;
        if (lsb == 0) {
            result = low;
        } else if (op.wide()) {
            result = (low >>> lsb) | (high << (Long.SIZE - lsb));
        } else {
            int narrowHigh = (int) high;
            int narrowLow = (int) low;
            int narrowResult = (narrowLow >>> lsb) | (narrowHigh << (Integer.SIZE - lsb));
            result = narrowResult & LOW_32_BITS_MASK;
        }
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `RBIT`/`REV16`/`REV`(`W`)/`REV32`(`X`)/`REV64`/`CLZ`/`CLS`/`CNT` (B8.2). Nenhuma forma
    /// afeta `NZCV`.
    public static boolean executeDataProcessing1Source(Aarch64Core core, IntegerOp64.DataProcessing1Source op) {
        long src = core.xForWidth(op.src(), op.wide());
        long result = switch (op.opcode()) {
            case RBIT -> op.wide()
                    ? Long.reverse(src)
                    : Integer.reverse((int) src) & LOW_32_BITS_MASK;
            case REV16 -> reverseHalfwordBytes(src, op.wide());
            case REV32 -> reverseWordBytes(src, op.wide());
            case REV64 -> Long.reverseBytes(src);
            case CLZ -> op.wide() ? Long.numberOfLeadingZeros(src) : Integer.numberOfLeadingZeros((int) src);
            case CLS -> countLeadingSignBits(src, op.wide());
            case CNT -> op.wide() ? Long.bitCount(src) : Integer.bitCount((int) src);
            case CTZ -> op.wide() ? Long.numberOfTrailingZeros(src) : Integer.numberOfTrailingZeros((int) src);
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `REV16` de A64 (B8.2): inverte a ordem dos BYTES dentro de cada halfword de 16 bits do
    /// registrador — diferente do `REV16` de 32 bits do ARM32 ({@code AsmIntegerHelpers}), que só
    /// tem 1 halfword.
    private static long reverseHalfwordBytes(long value, boolean wide) {
        int halfwordCount = wide ? Long.BYTES / Short.BYTES : Integer.BYTES / Short.BYTES;
        long result = 0;
        for (int i = 0; i < halfwordCount; i++) {
            int shift = i * Short.SIZE;
            long halfword = (value >>> shift) & HALFWORD_MASK;
            long swapped = ((halfword & BYTE_MASK) << Byte.SIZE) | ((halfword >>> Byte.SIZE) & BYTE_MASK);
            result |= swapped << shift;
        }
        return wide ? result : (result & LOW_32_BITS_MASK);
    }

    /// `REV`(`W`, 1 palavra)/`REV32`(`X`, 2 palavras) de A64 (B8.2): inverte a ordem dos BYTES
    /// DENTRO de cada palavra de 32 bits, mantendo a ORDEM das palavras — MESMO opcode do
    /// encoding para as duas larguras (ver {@code Ir64OneSourceOp#REV32}).
    private static long reverseWordBytes(long value, boolean wide) {
        if (!wide) {
            return Integer.reverseBytes((int) value) & LOW_32_BITS_MASK;
        }
        long lowWordReversed = Integer.reverseBytes((int) value) & LOW_32_BITS_MASK;
        long highWordReversed = Integer.reverseBytes((int) (value >>> Integer.SIZE)) & LOW_32_BITS_MASK;
        return (highWordReversed << Integer.SIZE) | lowWordReversed;
    }

    /// `CLS` (B8.2): conta bits à esquerda IGUAIS ao bit de sinal, SEM contar o próprio bit de
    /// sinal — `numberOfLeadingZeros(x XOR (x >> 1 aritmético)) - 1` (XOR de cada bit com seu
    /// vizinho mais significativo marca em `1` a primeira posição onde o valor DIFERE do sinal;
    /// `numberOfLeadingZeros` disso, menos o `1` que descarta o próprio bit de sinal, é exatamente
    /// a contagem pedida — mesma técnica de `CountLeadingSignBits` do `ARM DDI 0487` pseudocódigo).
    private static long countLeadingSignBits(long value, boolean wide) {
        if (wide) {
            long diffFromSign = value ^ (value >> 1);
            return Long.numberOfLeadingZeros(diffFromSign) - 1;
        }
        int narrow = (int) value;
        int diffFromSign = narrow ^ (narrow >> 1);
        return Integer.numberOfLeadingZeros(diffFromSign) - 1;
    }

    /// `SMADDL`/`SMSUBL`/`UMADDL`/`UMSUBL` (B8.2) — multiplicação 32×32→64 (sempre exata em
    /// `long`, o produto de dois valores de magnitude `<=2^32` nunca ultrapassa os 63 bits úteis
    /// de um `long` assinado) com acumulador de 64.
    public static boolean executeMultiplyAccumulateLong(Aarch64Core core, IntegerOp64.MultiplyAccumulateLong op) {
        long n = op.signed() ? (long) (int) core.x(op.src1()) : core.x(op.src1()) & LOW_32_BITS_MASK;
        long m = op.signed() ? (long) (int) core.x(op.src2()) : core.x(op.src2()) & LOW_32_BITS_MASK;
        long product = n * m;
        long accumulator = core.x(op.accumulator());
        long result = op.subtract() ? accumulator - product : accumulator + product;
        core.setX(op.dst(), result);
        return false;
    }

    /// `SMULH`/`UMULH` (B8.2) — os 64 bits ALTOS do produto de 128 bits de dois `X`. `Math`
    /// carrega os dois intrínsecos prontos desde o Java 18 (`multiplyHigh`/`unsignedMultiplyHigh`)
    /// — sem necessidade de decompor em meias-palavras manualmente.
    public static boolean executeMultiplyHigh(Aarch64Core core, IntegerOp64.MultiplyHigh op) {
        long a = core.x(op.src1());
        long b = core.x(op.src2());
        long result = op.signed() ? Math.multiplyHigh(a, b) : Math.unsignedMultiplyHigh(a, b);
        core.setX(op.dst(), result);
        return false;
    }

    /// `SETF8`/`SETF16` (B8.2, "Evaluate into flags") — avalia o campo BAIXO de {@link
    /// IntegerOp64.EvaluateIntoFlags#rn} como se fosse o resultado de uma soma: `N`=bit de sinal do
    /// campo, `Z`=campo zero, `V`=bit de sinal XOR o bit logo abaixo (`ARM DDI 0487`
    /// pseudocódigo de `SETF8`/`SETF16`). `C` NUNCA muda — por isso {@link
    /// dev.vitorsilverio.armjitter.core64.PstateRegister#carry()} é relido e devolvido como está.
    public static boolean executeEvaluateIntoFlags(Aarch64Core core, IntegerOp64.EvaluateIntoFlags op) {
        long full = core.x(op.rn());
        int sizeBits = op.sizeBits();
        long fieldMask = (1L << sizeBits) - 1;
        long value = full & fieldMask;
        int signBitPosition = sizeBits - 1;
        boolean negative = ((value >>> signBitPosition) & 1) != 0;
        boolean zero = value == 0;
        boolean nextBit = ((value >>> (signBitPosition - 1)) & 1) != 0;
        boolean overflow = negative != nextBit;
        core.pstate().setNzcv(negative, zero, core.pstate().carry(), overflow);
        return false;
    }

    /// `RMIF` (B8.2, "Rotate right into flags") — rotaciona {@link IntegerOp64.RotateIntoFlags#rn}
    /// para a direita por {@link IntegerOp64.RotateIntoFlags#shift} bits e atualiza só os flags cujo
    /// bit correspondente está setado em {@link IntegerOp64.RotateIntoFlags#mask} — os 4 bits baixos
    /// do valor rotacionado usam a MESMA ordem `N:Z:C:V` do formato bruto de
    /// {@link dev.vitorsilverio.armjitter.core64.PstateRegister#nzcv()}, então nenhuma
    /// reordenação de bit é necessária entre "candidato" e "máscara".
    public static boolean executeRotateIntoFlags(Aarch64Core core, IntegerOp64.RotateIntoFlags op) {
        long source = core.x(op.rn());
        int candidate = (int) (Long.rotateRight(source, op.shift()) & NZCV_FIELD_MASK);
        int mask = op.mask();
        int currentNzcv = core.pstate().nzcv();
        int updatedNzcv = (currentNzcv & ~mask) | (candidate & mask);
        core.pstate().setNzcv(updatedNzcv);
        return false;
    }

    /// `CFINV`/`XAFLAG`/`AXFLAG` (B8.2, `FEAT_FlagM2`) — `XAFLAG`/`AXFLAG` seguem o pseudocódigo
    /// do `ARM DDI 0487` para conversão de flags "eXternal"↔"Arm" (usadas por sequências
    /// vetoriais de comparação lane-a-lane reduzidas a um resultado escalar).
    public static boolean executeConvertFlags(Aarch64Core core, IntegerOp64.ConvertFlags op) {
        var pstate = core.pstate();
        switch (op.opcode()) {
            case INVERT_CARRY ->
                    pstate.setNzcv(pstate.negative(), pstate.zero(), !pstate.carry(), pstate.overflow());
            case EXTERNAL_TO_ARM -> {
                boolean oldZero = pstate.zero();
                boolean oldCarry = pstate.carry();
                pstate.setNzcv(false, oldZero && oldCarry, oldCarry && !oldZero, false);
            }
            case ARM_TO_EXTERNAL -> {
                boolean oldZero = pstate.zero();
                boolean oldCarry = pstate.carry();
                boolean oldOverflow = pstate.overflow();
                pstate.setNzcv(false, oldZero || oldOverflow, oldCarry && !oldOverflow, false);
            }
        }
        return false;
    }

    /// `SBFM`/`BFM`/`UBFM` (B6.3.2) — os 3 opcodes compartilham o MESMO cálculo de `pos`/`len`
    /// (dois casos, `imms >= immr` e `imms < immr`, ver Fatos de referência #2 da task); só a
    /// política de preenchimento dos bits FORA do campo copiado muda: `SBFM` estende o sinal do
    /// bit mais alto do campo, `UBFM` zera, `BFM` preserva o `Rd` existente (Armadilhas da task —
    /// erro mais fácil de trocar entre os três).
    public static boolean executeBitfield(Aarch64Core core, IntegerOp64.Bitfield op) {
        int bitsize = op.wide() ? BITFIELD_WIDE_BITSIZE : BITFIELD_NARROW_BITSIZE;
        long src = core.xForWidth(op.src(), op.wide());
        int immr = op.immr();
        int imms = op.imms();
        int len;
        int pos;
        long field;
        if (imms >= immr) {
            len = imms - immr + 1;
            pos = 0;
            field = extractBitfield(src, immr, len);
        } else {
            len = imms + 1;
            pos = bitsize - immr;
            field = extractBitfield(src, 0, len);
        }
        long result = switch (op.opcode()) {
            case UBFM -> field << pos;
            case SBFM -> signExtendBitfield(field, len) << pos;
            case BFM -> {
                long existing = core.xForWidth(op.dst(), op.wide());
                long fieldMask = maskOfBitfieldWidth(len) << pos;
                yield (existing & ~fieldMask) | (field << pos);
            }
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// Extrai `len` bits de {@code value} a partir de {@code shift}, zero-estendidos.
    private static long extractBitfield(long value, int shift, int len) {
        return (value >>> shift) & maskOfBitfieldWidth(len);
    }

    /// Máscara dos `bits` bits baixos — mesma disciplina de {@code Aarch64LogicalImmediate}
    /// (shift de 64 bits é UB por wraparound em Java, `bits == 64` é caso especial).
    private static long maskOfBitfieldWidth(int bits) {
        return bits >= Long.SIZE ? -1L : (1L << bits) - 1L;
    }

    /// Estende o sinal de um valor de `len` bits (bit `len - 1` é o sinal) para os 64 bits
    /// completos — usado só por `SBFM`.
    private static long signExtendBitfield(long field, int len) {
        if (len >= Long.SIZE) {
            return field;
        }
        long signBit = 1L << (len - 1);
        return (field ^ signBit) - signBit;
    }

    /// `MADD`/`MSUB` (B6.3.3) — sem atalho para `Ra==31` (D2 da task): o caminho geral já produz o
    /// resultado certo quando o acumulador é `XZR` (lê `0`), sem `if` especial. Cada operando-fonte
    /// é lido explicitamente via {@link Aarch64Core#xForWidth}, nunca confiando no invariante de
    /// zero-extensão do registrador cru (D2). A multiplicação/soma é feita em `long` puro — o
    /// overflow silencioso de `long*long`/`long+long` de Java já é módulo `2^64`, exatamente a
    /// truncagem exigida pela arquitetura; {@link Aarch64Core#setXForWidth} aplica a
    /// zero-extensão final quando `!wide`.
    public static boolean executeMultiplyAccumulate(Aarch64Core core, IntegerOp64.MultiplyAccumulate op) {
        long operand1 = core.xForWidth(op.src1(), op.wide());
        long operand2 = core.xForWidth(op.src2(), op.wide());
        long accumulator = core.xForWidth(op.accumulator(), op.wide());
        long product = operand1 * operand2;
        long result = op.subtract() ? (accumulator - product) : (accumulator + product);
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// `SDIV`/`UDIV` (B6.3.3) — divisor `0` produz resultado `0` SEM lançar exceção (checado ANTES
    /// de dividir; Fatos de referência #2 da task/Armadilhas — o operador `/` de Java lançaria
    /// `ArithmeticException`, que não existe na arquitetura). `SDIV`/`Long.MIN_VALUE / -1` (ou o
    /// equivalente de 32 bits) truncam para o próprio `MIN_VALUE` sem lançar — mesma convenção de
    /// complemento-de-dois que a divisão inteira de Java já produz (só lança para divisor `0`).
    public static boolean executeDivide(Aarch64Core core, IntegerOp64.Divide op) {
        long dividend = readDivideOperand(core, op.src1(), op.signed(), op.wide());
        long divisor = readDivideOperand(core, op.src2(), op.signed(), op.wide());
        long quotient;
        if (divisor == 0L) {
            quotient = 0L;
        } else if (op.signed()) {
            quotient = op.wide() ? (dividend / divisor) : (long) ((int) dividend / (int) divisor);
        } else {
            quotient = op.wide()
                    ? Long.divideUnsigned(dividend, divisor)
                    : (long) Integer.divideUnsigned((int) dividend, (int) divisor);
        }
        core.setXForWidth(op.dst(), quotient, op.wide());
        return false;
    }

    /// Lê um operando de `SDIV`/`UDIV`: `SDIV` em `W` exige sign-extend EXPLÍCITO dos 32 bits
    /// baixos (Fatos de referência #2 — `n = sign_extend32(Rn_low32)`); as demais combinações
    /// (`UDIV` em `W`, e qualquer operação em `X`) já usam {@link Aarch64Core#xForWidth} normal
    /// (`UDIV` em `W` já vem zero-estendido "de graça", invariante do core).
    private static long readDivideOperand(Aarch64Core core, int index, boolean signed, boolean wide) {
        if (signed && !wide) {
            return (long) (int) core.xForWidth(index, false);
        }
        return core.xForWidth(index, wide);
    }

    /// Executa {@link IntegerOp64.MoveWide}.
    public static boolean executeMoveWide(Aarch64Core core, IntegerOp64.MoveWide op) {
        long shiftedImmediate = ((long) op.immediate16() & 0xFFFFL) << op.shift();
        long result = switch (op.opcode()) {
            case MOVZ -> shiftedImmediate;
            case MOVN -> ~shiftedImmediate;
            case MOVK -> {
                long mask = 0xFFFFL << op.shift();
                long previous = core.xForWidth(op.dst(), op.wide());
                yield (previous & ~mask) | (shiftedImmediate & mask);
            }
        };
        core.setXForWidth(op.dst(), result, op.wide());
        return false;
    }

    /// Executa {@link IntegerOp64.PcRelative}.
    public static boolean executePcRelative(Aarch64Core core, IntegerOp64.PcRelative op) {
        long base = op.page() ? (op.instructionAddress() & ~0xFFFL) : op.instructionAddress();
        core.setX(op.dst(), base + op.immediate());
        return false;
    }

    /// `PACGA` (B19.6 bloco C) — este emulador não modela autenticação de ponteiro de verdade (ver
    /// javadoc de {@link IntegerOp64.PointerAuthGeneric}); resultado placeholder determinístico `Xd = 0`
    /// (decisão registrada na task: nenhum consumidor verifica o resultado, então qualquer valor
    /// fixo e documentado é seguro).
    public static boolean executePointerAuthGeneric(Aarch64Core core, IntegerOp64.PointerAuthGeneric op) {
        core.setX(op.rd(), 0L);
        return false;
    }

    /// `PACIA`/`PACIB`/`PACDA`/`PACDB`/`AUTIA`/`AUTIB`/`AUTDA`/`AUTDB`/`XPACI`/`XPACD` (B19.15) —
    /// rota (b) registrada na task (ver javadoc de {@link IntegerOp64.PointerAuthInPlace}): identidade.
    /// `Xd` já contém o valor "autenticado"/"assinado" (o operando é lido E escrito no hardware
    /// real, mas nada muda aqui) — nenhuma escrita de registrador é necessária.
    public static boolean executePointerAuthInPlace(Aarch64Core core, IntegerOp64.PointerAuthInPlace op) {
        return false;
    }

    /// `ABS Xd, Xn` (B19.6 bloco D) — `Math.abs` de `long`/`int` já satura `MIN_VALUE` para o
    /// próprio `MIN_VALUE` (mesma convenção de complemento de dois documentada no javadoc de
    /// {@link IntegerOp64.AbsGeneral}), sem checagem extra.
    public static boolean executeAbsGeneral(Aarch64Core core, IntegerOp64.AbsGeneral op) {
        long value = core.xForWidth(op.rn(), op.wide());
        long result = op.wide() ? Math.abs(value) : (int) Math.abs((int) value);
        core.setXForWidth(op.rd(), result, op.wide());
        return false;
    }

    /// `SMAX`/`SMIN`/`UMAX`/`UMIN` (B19.21) — MESMA técnica de leitura de operando com/sem sinal
    /// de {@code readDivideOperand} (`SDIV` em `W` exige sign-extend explícito dos 32 bits baixos;
    /// as demais combinações já vêm zero-estendidas "de graça" via {@link Aarch64Core#xForWidth}).
    public static boolean executeMinMaxGeneral(Aarch64Core core, IntegerOp64.MinMaxGeneral op) {
        boolean signed = switch (op.op()) {
            case SMAX, SMIN -> true;
            case UMAX, UMIN -> false;
        };
        boolean max = switch (op.op()) {
            case SMAX, UMAX -> true;
            case SMIN, UMIN -> false;
        };
        long value1 = readMinMaxOperand(core, op.src1(), signed, op.wide());
        long value2 = readMinMaxOperand(core, op.src2(), signed, op.wide());
        int comparison = signed ? Long.compare(value1, value2) : Long.compareUnsigned(value1, value2);
        boolean firstWins = max ? comparison >= 0 : comparison <= 0;
        core.setXForWidth(op.dst(), firstWins ? value1 : value2, op.wide());
        return false;
    }

    private static long readMinMaxOperand(Aarch64Core core, int index, boolean signed, boolean wide) {
        if (signed && !wide) {
            return (long) (int) core.xForWidth(index, false);
        }
        return core.xForWidth(index, wide);
    }

    /// `CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}` (B19.17) — laço bit-a-bit REFLETIDO delegado a
    /// {@link dev.vitorsilverio.armjitter.advsimd.Crc32Checksum} (migrado nesta forma pela B14.3,
    /// que o reusa do lado A32/T32 — zero-diff comprovado pelos testes de B19.17 sem alteração), sem
    /// a complementação de entrada/saída que `zlib`/Ethernet/iSCSI aplicam por cima, consumindo o
    /// dado byte a byte do menos para o mais significativo (ordem little-endian do valor do
    /// registrador — ver javadoc de {@link IntegerOp64.Crc32}).
    public static boolean executeCrc32(Aarch64Core core, IntegerOp64.Crc32 op) {
        int crc = (int) core.xForWidth(op.rn(), false);
        long data = core.xForWidth(op.rm(), op.dataWidthBits() == Long.SIZE);
        int result = Crc32Checksum.compute(crc, data, op.dataWidthBits(), op.castagnoli());
        core.setXForWidth(op.rd(), result & 0xFFFFFFFFL, false);
        return false;
    }

    /// `SUBP`/`SUBPS` (`FEAT_MTE2`, B19.14) — cada operando é sign-extended a partir dos 56 bits
    /// baixos ANTES da subtração (achado real, ver Javadoc de {@link IntegerOp64.SubtractPointer}).
    private static final int SUBTRACT_POINTER_SIGN_EXTEND_BITS = 56;

    /// Executa {@link IntegerOp64.SubtractPointer}.
    public static boolean executeSubtractPointer(Aarch64Core core, IntegerOp64.SubtractPointer op) {
        long n = signExtendBitfield(
                Ir64MemoryExecutor.readBaseRegister(core, op.rn()) & maskOfBitfieldWidth(SUBTRACT_POINTER_SIGN_EXTEND_BITS),
                SUBTRACT_POINTER_SIGN_EXTEND_BITS);
        long m = signExtendBitfield(
                Ir64MemoryExecutor.readBaseRegister(core, op.rm()) & maskOfBitfieldWidth(SUBTRACT_POINTER_SIGN_EXTEND_BITS),
                SUBTRACT_POINTER_SIGN_EXTEND_BITS);
        AluResult result = subWithFlags(n, m, true);
        if (op.setFlags()) {
            core.pstate().setNzcv(result.negative(), result.zero(), result.carry(), result.overflow());
        }
        core.setX(op.rd(), result.value());
        return false;
    }

    /// `IRG` (`FEAT_MTE2`, B19.14) — ver {@link Aarch64Core#insertRandomTag}.
    public static boolean executeInsertRandomTag(Aarch64Core core, IntegerOp64.InsertRandomTag op) {
        long rn = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        long rmExclude = core.x(op.rm());
        long result = core.insertRandomTag(rn, rmExclude);
        Ir64MemoryExecutor.writeBaseRegister(core, op.rd(), result);
        return false;
    }

    /// `GMI` (`FEAT_MTE2`, B19.14) — ver Javadoc de {@link IntegerOp64.TagMaskInsert}.
    public static boolean executeTagMaskInsert(Aarch64Core core, IntegerOp64.TagMaskInsert op) {
        long rn = Ir64MemoryExecutor.readBaseRegister(core, op.rn());
        int tag = Aarch64Core.allocationTagFromAddress(rn);
        long mask = core.x(op.rm());
        core.setX(op.rd(), mask | (1L << tag));
        return false;
    }

    /// `AddWithCarry(a, b, carryIn)` do `ARM DDI 0487` pseudocódigo — usado por `ADC`/`SBC`
    /// (B8.2, {@link #executeAluWithCarry}) e futuramente por qualquer soma de 3 operandos.
    /// Diferente de {@link #addWithFlags} (2 operandos): a soma de 3 operandos (`a+b+carryIn`)
    /// pode precisar de MAIS de 64 bits de precisão intermediária para calcular `carry`/
    /// `overflow` corretamente — um encadeamento ingênuo de duas somas de 64 bits (somar `a+b`,
    /// depois somar o carry) foi TESTADO E REJEITADO nesta task: o `overflow` de uma soma de 3
    /// operandos NÃO é a soma (OU) dos overflows de cada passo em sequência (contra-exemplo
    /// encontrado: `a=Long.MIN_VALUE, b=-1, carryIn=1` — a soma exata é `Long.MIN_VALUE`, dentro
    /// do range, mas o encadeamento por passos sinaliza overflow por engano no primeiro passo).
    /// {@link BigInteger} dá a precisão exata sem essa armadilha — `ADC`/`SBC` não são caminho
    /// quente o bastante para justificar bit-tricks (sem suporte ASM nativo nesta task, ver
    /// "Não inclui").
    private static AluResult addWithCarryFlags(long a, long b, boolean carryIn, boolean wide) {
        BigInteger carryInValue = carryIn ? BigInteger.ONE : BigInteger.ZERO;
        if (wide) {
            BigInteger unsignedA = a >= 0 ? BigInteger.valueOf(a) : BigInteger.valueOf(a).add(TWO_POW_64);
            BigInteger unsignedB = b >= 0 ? BigInteger.valueOf(b) : BigInteger.valueOf(b).add(TWO_POW_64);
            BigInteger unsignedSum = unsignedA.add(unsignedB).add(carryInValue);
            long result = unsignedSum.and(MASK_64_BITS).longValue();
            boolean carryOut = unsignedSum.compareTo(TWO_POW_64) >= 0;
            BigInteger signedSum = BigInteger.valueOf(a).add(BigInteger.valueOf(b)).add(carryInValue);
            boolean overflow = signedSum.compareTo(BigInteger.valueOf(result)) != 0;
            return new AluResult(result, result < 0, result == 0, carryOut, overflow);
        }
        // Forma `W`: 32+32+1 bits cabe folgado em `long` (assinado E sem sinal), sem precisar de
        // `BigInteger`.
        int ai = (int) a;
        int bi = (int) b;
        long unsignedSum = (ai & LOW_32_BITS_MASK) + (bi & LOW_32_BITS_MASK) + (carryIn ? 1L : 0L);
        int resultInt = (int) unsignedSum;
        long result = resultInt & LOW_32_BITS_MASK;
        boolean carryOut = unsignedSum != result;
        long signedSum = (long) ai + bi + (carryIn ? 1L : 0L);
        boolean overflow = signedSum != resultInt;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carryOut, overflow);
    }

    private static AluResult addWithFlags(long a, long b, boolean wide) {
        if (wide) {
            long result = a + b;
            boolean carry = Long.compareUnsigned(result, a) < 0;
            boolean overflow = (((a ^ result) & (b ^ result)) < 0);
            return new AluResult(result, result < 0, result == 0, carry, overflow);
        }
        int ai = (int) a;
        int bi = (int) b;
        int resulti = ai + bi;
        boolean carry = Integer.compareUnsigned(resulti, ai) < 0;
        boolean overflow = (((ai ^ resulti) & (bi ^ resulti)) < 0);
        long result = resulti & 0xFFFF_FFFFL;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carry, overflow);
    }

    private static AluResult subWithFlags(long a, long b, boolean wide) {
        if (wide) {
            long result = a - b;
            boolean carry = Long.compareUnsigned(a, b) >= 0;
            boolean overflow = (((a ^ b) & (a ^ result)) < 0);
            return new AluResult(result, result < 0, result == 0, carry, overflow);
        }
        int ai = (int) a;
        int bi = (int) b;
        int resulti = ai - bi;
        boolean carry = Integer.compareUnsigned(ai, bi) >= 0;
        boolean overflow = (((ai ^ bi) & (ai ^ resulti)) < 0);
        long result = resulti & 0xFFFF_FFFFL;
        return new AluResult(result, (result & 0x8000_0000L) != 0, result == 0, carry, overflow);
    }

    /// `AND`/`ORR`/`EOR` (imediato) NUNCA atualizam C/V (`ARM DDI 0487 C6.2.9`, `ANDS`
    /// imediato): diferente do barrel shifter clássico de 32 bits, que podia produzir carry a
    /// partir do próprio shift do imediato — A64 não tem esse mecanismo para a forma imediata.
    private static AluResult logicalWithFlags(long result, boolean wide) {
        long masked = wide ? result : (result & 0xFFFF_FFFFL);
        boolean negative = wide ? masked < 0 : (masked & 0x8000_0000L) != 0;
        return new AluResult(masked, negative, masked == 0, false, false);
    }

    private record AluResult(long value, boolean negative, boolean zero, boolean carry, boolean overflow) {
    }
}
