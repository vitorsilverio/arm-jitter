package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Decodifica os grupos "Load/store single data item" e "Load/store multiple" Thumb-2 de 32 bits
/// (B2.3): `LDR`/`STR`/`LDRB`/`STRB`/`LDRH`/`STRH`/`LDRSB`/`LDRSH` nas formas T2 (registrador com
/// shift), T3 (imediato de 12 bits, sempre para cima) e T4 (imediato de 8 bits, 4 modos de
/// endereçamento pré/pós-indexado), a forma literal relativa ao PC das 5 variantes de load, e
/// `LDRD`/`STRD`/`LDM.W`/`STM.W`/`PUSH.W`/`POP.W`. Anexado via
/// {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando {@link ArmFeature#THUMB2}
/// está habilitado (o chamador, {@link ThumbDecoder}, só invoca as extensões de 32 bits com essa
/// feature ligada).
///
/// Bits sempre nomeados a partir de `raw` = os dois halfwords combinados (`hi&lt;&lt;16 | lo`, hi =
/// primeiro halfword nos bits altos), igual a {@link Thumb2DataProcessingDecoder}/
/// {@link Thumb2MiscDecoder}. Encodings confirmados contra o QEMU `target/arm/tcg/t32.decode`
/// (seções "Load/store (register, immediate, literal)", "Load/Store Exclusive... and LDRD/STRD" e
/// "Load/store multiple"), que reproduz a ARM DDI 0406C A5.3.7/A5.3.8/A5.3.9.
///
/// <p><b>Fora de escopo desta task</b> (deliberado): `LDRT`/`STRT`/`LDRBT`/`STRBT`/`LDRHT`/`STRHT`/
/// `LDRSBT`/`LDRSHT` (acesso "unprivileged" — mesmo espaço de bits do T4, mas semântica de troca de
/// nível de privilégio irrelevante para GBA/NDS user-mode, nunca gerada por SDKs típicos);
/// `TBB`/`TBH` (mesmo prefixo de 7 bits desta classe para `LDRD`/`STRD`/`LDREX`/`STREX`, mas são
/// branches — ficam em B2.4, ver `b2.3-thumb2-loadstore.md`); as formas load-acquire/store-release
/// ARMv8 (`LDAEX*`/`STLEX*`/`LDA*`/`STL*`, mesmo espaço de bits de `LDREX*`/`STREX*` — fora do
/// escopo, ARM clássico também não as implementa). Todos caem no UNDEFINED controlado do
/// {@link ThumbDecoder} (`null` devolvido aqui).
///
/// <p><b>B2.7 PR3</b>: `LDREX`/`STREX`/`LDREXB/H/D`/`STREXB/H/D` de 32 bits, antes fora de escopo
/// (ver nota histórica removida acima), agora decodificados — ver
/// {@link #decodeExclusiveWordOrSized}. `CLREX`/`CPS.W` de 32 bits ficam em
/// {@link Thumb2MiscDecoder} (mesmo espaço "Miscellaneous control"/"Hints, and CPS" onde já vivem
/// `DMB`/`DSB`/`ISB`/`MRS`/`MSR`, não este).
public final class Thumb2LoadStoreDecoder implements DecoderExtension {
    private final ArmArchitecture architecture;

    /// Decoder ligado à arquitetura que o registra — precisa consultar
    /// {@link ArmFeature#LDRD_STRD} (gate fino de `LDRD`/`STRD`, dentro do `THUMB2` mais amplo já
    /// garantido pelo chamador), mesmo padrão de {@link Thumb2MiscDecoder}.
    public Thumb2LoadStoreDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    // ── Load/store single data item — A5.3.7 ("op0" incondicional, top5=0b11111) ───────────

    /// `raw[31:24]` fixo do grupo unsigned (`STRB`/`LDRB`/`STRH`/`LDRH`/`STR`/`LDR`).
    private static final int SINGLE_UNSIGNED_TOP8 = 0b1111_1000;
    /// `raw[31:24]` fixo do grupo signed-only (`LDRSB`/`LDRSH` — sem forma de store).
    private static final int SINGLE_SIGNED_TOP8 = 0b1111_1001;
    private static final int TOP8_SHIFT = 24;
    private static final int TOP8_MASK = 0xFF;

    /// `raw[23]`: seletor T3 (imediato de 12 bits, sempre `U=1`) vs grupo T4/T2 (`raw[23]=0`) para
    /// um registrador base comum; quando `Rn=PC` (literal), o mesmo bit vira o `U` explícito
    /// (ida/volta), ver {@link #decodeLiteral}.
    private static final int SIZE12_OR_U_BIT = 23;

    /// `raw[22:20]`: tamanho do acesso e load/store, tabela compartilhada pelos dois grupos
    /// (unsigned/signed) de `TOP8`.
    private static final int SIZE_L_SHIFT = 20;
    private static final int SIZE_L_MASK = 0x7;
    private static final int SIZE_L_STRB = 0b000;
    private static final int SIZE_L_LDRB = 0b001;
    private static final int SIZE_L_STRH = 0b010;
    private static final int SIZE_L_LDRH = 0b011;
    private static final int SIZE_L_STR = 0b100;
    private static final int SIZE_L_LDR = 0b101;
    private static final int SIZE_L_LDRSB = 0b001; // dentro do grupo signed
    private static final int SIZE_L_LDRSH = 0b011; // dentro do grupo signed

    private static final int RN_SHIFT = 16;
    private static final int RN_MASK = 0xF;
    private static final int RT_SHIFT = 12;
    private static final int RT_MASK = 0xF;
    private static final int PROGRAM_COUNTER = 15;

    private static final int IMM12_MASK = 0xFFF;

    /// `raw[11:6]` tem que ser zero para a forma T2 registrador (`[Rn, Rm, LSL #imm2]`) dentro do
    /// grupo T4/T2 (`raw[23]=0`); qualquer valor diferente de zero no bit 11 marca o grupo de
    /// imediato de 8 bits (T4) em vez do registrador.
    private static final int REGISTER_FORM_FIXED_SHIFT = 6;
    private static final int REGISTER_FORM_FIXED_MASK = 0x3F;
    private static final int REGISTER_FORM_IMM2_SHIFT = 4;
    private static final int REGISTER_FORM_IMM2_MASK = 0x3;
    private static final int REGISTER_FORM_RM_MASK = 0xF;

    /// `raw[10:8]` = P/U/W explícitos do imediato de 8 bits (T4) — ARM DDI 0406C A5.3.7 tabela
    /// "Encoding T4"; o quarto modo (P=1,U=1,W=0) é o acesso "unprivileged" (`LDRT`/`STRT`/...),
    /// fora do escopo desta task.
    private static final int T4_P_SHIFT = 10;
    private static final int T4_U_SHIFT = 9;
    private static final int T4_W_SHIFT = 8;
    private static final int IMM8_MASK = 0xFF;

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        int top8 = (raw >>> TOP8_SHIFT) & TOP8_MASK;
        if (top8 == SINGLE_UNSIGNED_TOP8) {
            return decodeSingleTransfer(raw, address, condition, false);
        }
        if (top8 == SINGLE_SIGNED_TOP8) {
            return decodeSingleTransfer(raw, address, condition, true);
        }
        return decodeExtraLoadStoreOrMultiple(raw, address, condition);
    }

    /// B2.2.2: `raw` cai estruturalmente no espaço "Load/store single data item" (`top8`) ou
    /// "Load/store dual/exclusive/table branch"/"Load/store multiple" (`EXTRA_TOP7`) que esta
    /// classe reconhece — mesmo quando o sub-encoding específico (ex. a forma unprivileged do T4,
    /// `TBB`/`TBH`, `STM` com `PC` na lista) devolve `null` em {@link #tryDecode}. Usado pelo
    /// `ThumbDecoder` para decidir UNDEFINED controlado em vez de delegar ao caminho legado
    /// `BL`/`BLX` — ver {@link dev.vitorsilverio.armjitter.arch.DecoderExtension#claimsEncodingSpace}.
    @Override
    public boolean claimsEncodingSpace(int raw) {
        int top8 = (raw >>> TOP8_SHIFT) & TOP8_MASK;
        if (top8 == SINGLE_UNSIGNED_TOP8 || top8 == SINGLE_SIGNED_TOP8) {
            return true;
        }
        return ((raw >>> TOP7_SHIFT) & TOP7_MASK) == EXTRA_TOP7;
    }

    // ── LDR/STR/LDRB/STRB/LDRH/STRH/LDRSB/LDRSH: T2/T3/T4 + literal ────────────────────────

    private DecodedInstruction decodeSingleTransfer(int raw, int address, Condition condition, boolean signedGroup) {
        int sizeL = (raw >>> SIZE_L_SHIFT) & SIZE_L_MASK;
        SingleTransferKind kind = kindFor(signedGroup, sizeL);
        if (kind == null) {
            return null; // combinação reservada de tamanho/L — UNDEFINED controlado
        }
        int rn = (raw >>> RN_SHIFT) & RN_MASK;
        int rt = (raw >>> RT_SHIFT) & RT_MASK;

        // "Load, unsigned (literal) overlaps all other load encodings": Rn=PC reivindica a forma
        // literal para as 5 variantes de load, INDEPENDENTE dos bits 23/11/10/9/8 (que para
        // qualquer outro Rn selecionariam T3/T4/T2) — ARM DDI 0406C A6.3.7, nota de prioridade.
        if (kind.load && rn == PROGRAM_COUNTER) {
            return decodeLiteral(raw, address, condition, kind, rt);
        }

        boolean sizeTwelveBits = ((raw >>> SIZE12_OR_U_BIT) & 1) != 0;
        if (sizeTwelveBits) {
            return decodeT3(raw, address, condition, kind, rn, rt);
        }
        boolean registerForm = ((raw >>> REGISTER_FORM_FIXED_SHIFT) & REGISTER_FORM_FIXED_MASK) == 0;
        if (registerForm) {
            return decodeT2Register(raw, address, condition, kind, rn, rt);
        }
        return decodeT4(raw, address, condition, kind, rn, rt);
    }

    /// T3 (ARM DDI 0406C A5.3.7 "Encoding T3"): imediato de 12 bits, sempre `P=1,U=1,W=0` (offset,
    /// sem writeback, sempre para cima) — a armadilha citada na task: NÃO confundir com o P/U/W
    /// explícito do T4 abaixo.
    private DecodedInstruction decodeT3(int raw, int address, Condition condition, SingleTransferKind kind, int rn, int rt) {
        int imm12 = raw & IMM12_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind.instructionKind,
                rt, rn, -1, imm12, true, false, false, kind.sizeBytes, kind.signed, false, false);
    }

    /// T4 (ARM DDI 0406C A5.3.7 "Encoding T4"): imediato de 8 bits com P/U/W explícitos. `W=1`
    /// cobre os 4 modos pré/pós-indexados com writeback; `P=1,U=0,W=0` é o offset negativo sem
    /// writeback (complementa o T3, que só cobre offset positivo); `P=1,U=1,W=0` é a forma
    /// "unprivileged" (`LDRT`/`STRT`/...), fora do escopo — UNDEFINED controlado.
    private DecodedInstruction decodeT4(int raw, int address, Condition condition, SingleTransferKind kind, int rn, int rt) {
        boolean p = ((raw >>> T4_P_SHIFT) & 1) != 0;
        boolean u = ((raw >>> T4_U_SHIFT) & 1) != 0;
        boolean w = ((raw >>> T4_W_SHIFT) & 1) != 0;
        int imm8 = raw & IMM8_MASK;
        boolean writeback;
        boolean postIndexed;
        if (w) {
            writeback = true;
            postIndexed = !p;
        } else if (p && !u) {
            writeback = false;
            postIndexed = false;
        } else {
            return null; // p&&u (unprivileged) ou !p&&!w (reservado) — fora do escopo/UNDEFINED
        }
        int signedOffset = u ? imm8 : -imm8;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind.instructionKind,
                rt, rn, -1, signedOffset, true, false, false, kind.sizeBytes, kind.signed, writeback, postIndexed);
    }

    /// T2 (ARM DDI 0406C A5.3.7 "Encoding T2"): `[Rn, Rm, LSL #imm2]`, sempre offset addressing
    /// (`P=1,U=1,W=0`) — nunca writeback nem post-index. O shift (`imm2`, bits 5:4) é lido
    /// diretamente de `raw` por {@code StandardIrBuilder#offset} (mesmo padrão do offset com shift
    /// do ARM clássico, que também lê `raw` em vez de carregar um campo próprio no
    /// {@code DecodedInstruction}).
    private DecodedInstruction decodeT2Register(int raw, int address, Condition condition, SingleTransferKind kind, int rn, int rt) {
        int rm = raw & REGISTER_FORM_RM_MASK;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind.instructionKind,
                rt, rn, rm, 0, false, false, false, kind.sizeBytes, kind.signed, false, false);
    }

    /// Forma literal (`Rn=PC`) das 5 variantes de load: endereço resolvido em tempo de decode como
    /// `Align(PC,4) ± imm12` — mesma centralização de alinhamento que {@link ThumbDecoder} já usa
    /// para `LDR Rt,[PC,#imm8]` (Thumb-1) e `ADR` (B2.2). `U` (bit 23) dá sinal explícito, ao
    /// contrário do Thumb-1 (sempre para cima).
    private DecodedInstruction decodeLiteral(int raw, int address, Condition condition, SingleTransferKind kind, int rt) {
        boolean up = ((raw >>> SIZE12_OR_U_BIT) & 1) != 0;
        int imm12 = raw & IMM12_MASK;
        int literalAddress = ((address + 4) & ~3) + (up ? imm12 : -imm12);
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.LOAD_LITERAL,
                rt, -1, -1, literalAddress, true, false, false, kind.sizeBytes, kind.signed);
    }

    private record SingleTransferKind(InstructionKind instructionKind, boolean load, int sizeBytes, boolean signed) {
    }

    private static SingleTransferKind kindFor(boolean signedGroup, int sizeL) {
        if (signedGroup) {
            return switch (sizeL) {
                case SIZE_L_LDRSB -> new SingleTransferKind(InstructionKind.LOAD, true, 1, true);
                case SIZE_L_LDRSH -> new SingleTransferKind(InstructionKind.LOAD, true, 2, true);
                default -> null;
            };
        }
        return switch (sizeL) {
            case SIZE_L_STRB -> new SingleTransferKind(InstructionKind.STORE, false, 1, false);
            case SIZE_L_LDRB -> new SingleTransferKind(InstructionKind.LOAD, true, 1, false);
            case SIZE_L_STRH -> new SingleTransferKind(InstructionKind.STORE, false, 2, false);
            case SIZE_L_LDRH -> new SingleTransferKind(InstructionKind.LOAD, true, 2, false);
            case SIZE_L_STR -> new SingleTransferKind(InstructionKind.STORE, false, 4, false);
            case SIZE_L_LDR -> new SingleTransferKind(InstructionKind.LOAD, true, 4, false);
            default -> null;
        };
    }

    // ── LDRD/STRD e LDM.W/STM.W/PUSH.W/POP.W — top5=0b11101, raw[25]=0 ──────────────────────

    /// `raw[31:25]` fixo compartilhado por `LDRD`/`STRD`, `LDM.W`/`STM.W` e (fora do escopo desta
    /// classe) `LDREX`/`STREX`/`CLREX`/`TBB`/`TBH` de 32 bits — ARM DDI 0406C A5.3.8/A5.3.9. O
    /// subgrupo é distinguido por `raw[22]` (ver {@link #decodeExtraLoadStoreOrMultiple}).
    private static final int EXTRA_TOP7 = 0b1110_100;
    private static final int TOP7_SHIFT = 25;
    private static final int TOP7_MASK = 0x7F;

    private static final int P_BIT = 24;
    private static final int U_OR_FIXED_BIT = 23;
    private static final int LDRD_MARKER_BIT = 22; // 1 = LDRD/STRD; 0 = LDM/STM/LDREX/STREX/TBB/TBH
    private static final int W_BIT = 21;
    private static final int L_BIT = 20;

    private static final int RN2_SHIFT = 16;
    private static final int RN2_MASK = 0xF;
    private static final int RT_SHIFT_LDRD = 12; // Rt (primeiro do par)
    private static final int RT2_SHIFT = 8;        // Rt2 (segundo do par, campo independente)
    private static final int RT_MASK_LDRD = 0xF;
    private static final int IMM8_SHIFT_LDRD = 0;
    private static final int IMM8_MASK_LDRD = 0xFF;
    private static final int IMM8X4_SHIFT = 2; // %imm8x4: o imediato é sempre múltiplo de 4

    private static final int STACK_POINTER = 13;
    private static final int MINIMUM_BLOCK_TRANSFER_REGISTERS = 2;

    private DecodedInstruction decodeExtraLoadStoreOrMultiple(int raw, int address, Condition condition) {
        int top7 = (raw >>> TOP7_SHIFT) & TOP7_MASK;
        if (top7 != EXTRA_TOP7) {
            return null;
        }
        boolean p = ((raw >>> P_BIT) & 1) != 0;
        boolean w = ((raw >>> W_BIT) & 1) != 0;
        boolean ldrdMarker = ((raw >>> LDRD_MARKER_BIT) & 1) != 0;
        if (ldrdMarker) {
            // (P=0,W=0) não aparece na tabela de LDRD/STRD — esse ponto do espaço de bits
            // pertence a LDREX/STREX/TBB/TBH (B2.7 PR3, ver decodeExclusiveWordOrSized; TBB/TBH
            // ficam fora daqui, já tratados por Thumb2BranchDecoder desde B2.4).
            if (!p && !w) {
                return decodeExclusiveWordOrSized(raw, address, condition);
            }
            if (!architecture.has(ArmFeature.LDRD_STRD)) {
                // Mesma convenção de gating explícito de WFI/LDREX/STREX/CLREX/DMB (B1.4/B1.5/B2.5):
                // UNDEFINED direto em vez de deixar cair silenciosamente no fallback do ThumbDecoder.
                return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
            }
            return decodeDoubleTransfer(raw, address, condition, p, w);
        }
        boolean upOrFixed = ((raw >>> U_OR_FIXED_BIT) & 1) != 0;
        // LDM.W/STM.W (Thumb-2) só suportam IA (P=0,"U"=1) e DB (P=1,"U"=0) — ao contrário do ARM
        // clássico, que tem as 4 combinações; qualquer outra combinação (incl. LDREX/STREX/TBB/TBH,
        // que também vivem sob `raw[22]=0`) cai fora daqui.
        BlockTransferMode mode;
        if (!p && upOrFixed) {
            mode = BlockTransferMode.IA;
        } else if (p && !upOrFixed) {
            mode = BlockTransferMode.DB;
        } else {
            return null;
        }
        return decodeMultipleTransfer(raw, address, condition, mode, w);
    }

    /// `LDRD`/`STRD` Thumb-2 (ARM DDI 0406C A5.3.8): ao contrário do ARM clássico (`Rt2` sempre
    /// `Rt+1`, um único campo no encoding), aqui `Rt` e `Rt2` são campos de 4 bits INDEPENDENTES —
    /// pares arbitrários, não-adjacentes, são válidos (confirmado no manual e no QEMU
    /// `@ldstd_ri8`: `rt:4 rt2:4` sem qualquer relação aritmética entre os dois campos).
    /// `secondSourceRegister` carrega `Rt2` aqui porque essa forma nunca usa offset por registrador
    /// (sempre imediato) — ver {@code StandardIrBuilder#DOUBLE_TRANSFER}.
    private DecodedInstruction decodeDoubleTransfer(int raw, int address, Condition condition, boolean p, boolean w) {
        boolean load = ((raw >>> L_BIT) & 1) != 0;
        boolean up = ((raw >>> U_OR_FIXED_BIT) & 1) != 0;
        int rn = (raw >>> RN2_SHIFT) & RN2_MASK;
        int rt = (raw >>> RT_SHIFT_LDRD) & RT_MASK_LDRD;
        int rt2 = (raw >>> RT2_SHIFT) & RT_MASK_LDRD;
        int imm8 = (raw >>> IMM8_SHIFT_LDRD) & IMM8_MASK_LDRD;
        int signedOffset = (up ? imm8 : -imm8) << IMM8X4_SHIFT;
        boolean postIndexed = !p;
        boolean writeback = w || postIndexed;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, InstructionKind.DOUBLE_TRANSFER,
                rt, rn, rt2, signedOffset, true, false, load, 8, false, writeback, postIndexed);
    }

    /// `LDM.W`/`STM.W`/`PUSH.W`/`POP.W` (ARM DDI 0406C A5.3.9): `PUSH.W`/`POP.W` não têm decode
    /// próprio — são o mesmo encoding de `STMDB SP!`/`LDMIA SP!` com lista completa de 16
    /// registradores, exatamente como o ARM clássico já trata o alias (nenhuma diferença de bits
    /// especial a tratar aqui). Restrições UNPREDICTABLE tratadas como UNDEFINED (mesmo padrão de
    /// B1.x): `Rn=PC`, lista com `SP`, `STM` com `PC` na lista, menos de 2 registradores, e
    /// writeback com a própria base na lista.
    private DecodedInstruction decodeMultipleTransfer(int raw, int address, Condition condition, BlockTransferMode mode, boolean writeback) {
        boolean load = ((raw >>> L_BIT) & 1) != 0;
        int rn = (raw >>> RN2_SHIFT) & RN2_MASK;
        int mask = raw & 0xFFFF;
        if (rn == PROGRAM_COUNTER
                || (mask & (1 << STACK_POINTER)) != 0
                || (!load && (mask & (1 << PROGRAM_COUNTER)) != 0)
                || Integer.bitCount(mask) < MINIMUM_BLOCK_TRANSFER_REGISTERS
                || (writeback && (mask & (1 << rn)) != 0)) {
            return null;
        }
        InstructionKind kind = load ? InstructionKind.LOAD_MULTIPLE : InstructionKind.STORE_MULTIPLE;
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition, kind,
                -1, rn, -1, mask, true, false, false, 4, false, writeback, false, mode);
    }

    // ── LDREX/STREX/LDREXB/H/D/STREXB/H/D — raw[22]=1,P=0,W=0 dentro de EXTRA_TOP7 (B2.7 PR3) ──

    /// `raw[7:4]`: dentro do subgrupo `P=0,W=0` (a forma "sized", `raw[23]=1`), distingue
    /// byte/half/doubleword de `TBB`/`TBH` (op4 `0`/`1`, tratados em {@link Thumb2BranchDecoder})
    /// e das formas load-acquire/store-release ARMv8 (`LDAEX*`/`STLEX*`/`LDA*`/`STL*`, op4 `8`-`F`
    /// — mesmo espaço de bits que o ARM clássico também não implementa, fora do escopo).
    private static final int EXCLUSIVE_OP4_SHIFT = 4;
    private static final int EXCLUSIVE_OP4_MASK = 0xF;
    private static final int EXCLUSIVE_OP4_BYTE = 0b0100;
    private static final int EXCLUSIVE_OP4_HALF = 0b0101;
    private static final int EXCLUSIVE_OP4_DOUBLE = 0b0111;

    /// Marcador fixo `1111` que ocupa o campo `Rt2`/`Rd` quando ele não é usado por uma forma
    /// específica (`LDREXB/H`'s `Rt2`, e o `Rd` sempre ausente de `LDREX*`).
    private static final int EXCLUSIVE_FIXED_MARKER = 0b1111;
    private static final int EXCLUSIVE_RD_SHIFT = 0;
    private static final int EXCLUSIVE_RD_MASK = 0xF;

    /// `raw[22]=1,P=0,W=0`: espaço de `LDREX{,B,H,D}`/`STREX{,B,H,D}` de 32 bits — ARM DDI 0406C
    /// A5.3.8/A8.8.75/A8.8.212 (e correspondentes de load), confirmado no QEMU `t32.decode` seção
    /// "Load/Store Exclusive, Load-Acquire/Store-Release, and Table Branch" (`@strex_i`/
    /// `@strex_0`/`@strex_d`/`@ldrex_i`/`@ldrex_0`/`@ldrex_d`). `TBB`/`TBH` (`@tbranch`, op4∈{0,1})
    /// vivem no MESMO prefixo mas são branches, tratados por {@link Thumb2BranchDecoder} (B2.4) —
    /// não duplicados aqui.
    private DecodedInstruction decodeExclusiveWordOrSized(int raw, int address, Condition condition) {
        boolean sizedForm = ((raw >>> U_OR_FIXED_BIT) & 1) != 0;
        boolean load = ((raw >>> L_BIT) & 1) != 0;
        if (!sizedForm) {
            return decodeWordExclusive(raw, address, condition, load);
        }
        int op4 = (raw >>> EXCLUSIVE_OP4_SHIFT) & EXCLUSIVE_OP4_MASK;
        return switch (op4) {
            case EXCLUSIVE_OP4_BYTE -> decodeSizedExclusive(raw, address, condition, load, 1);
            case EXCLUSIVE_OP4_HALF -> decodeSizedExclusive(raw, address, condition, load, 2);
            case EXCLUSIVE_OP4_DOUBLE -> decodeSizedExclusive(raw, address, condition, load, 8);
            default -> null; // TBB/TBH ou load-acquire/store-release ARMv8, fora do escopo
        };
    }

    /// `LDREX`/`STREX` (word, sem sufixo de tamanho): ao contrário do ARM clássico (acesso exato
    /// em `[Rn]`, sem offset), o Thumb-2 tem um imediato de 8 bits `×4` somado à base
    /// (`%imm8x4` no QEMU — o MESMO campo/macro que `LDRD`/`STRD` já usam, sempre para cima, sem
    /// bit `U` — a armadilha citada na task). Gate {@link ArmFeature#EXCLUSIVE_WORD}, igual ao ARM
    /// clássico. Layout: `rn`(19:16) `rt`(15:12) `rd-ou-marcador`(11:8) `imm8`(7:0) — `Rd`(status)
    /// só existe em `STREX`; `LDREX` tem o marcador fixo `1111` nessa posição.
    private DecodedInstruction decodeWordExclusive(int raw, int address, Condition condition, boolean load) {
        if (!architecture.has(ArmFeature.EXCLUSIVE_WORD)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> RN2_SHIFT) & RN2_MASK;
        int rt = (raw >>> RT_SHIFT_LDRD) & RT_MASK_LDRD;
        int offset = (raw & IMM8_MASK_LDRD) << IMM8X4_SHIFT;
        if (rn == PROGRAM_COUNTER || rt == PROGRAM_COUNTER) {
            return null; // UNPREDICTABLE
        }
        if (load) {
            int marker = (raw >>> RT2_SHIFT) & RT_MASK_LDRD;
            if (marker != EXCLUSIVE_FIXED_MARKER) {
                return null;
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOAD_EXCLUSIVE, rt, rn, -1, offset, true, false, false, 4, false);
        }
        int rd = (raw >>> RT2_SHIFT) & RT_MASK_LDRD;
        if (rd == PROGRAM_COUNTER || rd == rn || rd == rt) {
            return null; // UNPREDICTABLE: Rd não pode coincidir com PC/Rn/Rt
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.STORE_EXCLUSIVE, rd, rn, rt, offset, true, false, false, 4, false);
    }

    /// `LDREXB/H/D`/`STREXB/H/D`: sem offset (`imm=0`, igual ao ARM clássico — só a forma word
    /// acima tem offset). A forma doubleword tem `Rt2` como campo de 4 bits INDEPENDENTE no
    /// encoding (`@strex_d`/`@ldrex_d`, ao contrário do ARM clássico que só tem um campo `Rt`), mas
    /// permanece UNPREDICTABLE se `Rt2 != Rt+1` ou `Rt` ímpar (ARM DDI 0406C A8.8.75/A8.8.212,
    /// "Rt2 must be Rt+1") — então, ao contrário de `LDRD`/`STRD` (B2.3), o par continua adjacente
    /// na prática; {@code IrOp.LoadExclusive}/{@code IrOp.StoreExclusive} (B1.4) já assumem isso
    /// (`dst`/`dst+1`), sem precisar de um campo `second` independente como
    /// {@code IrOp.DoubleTransfer} ganhou.
    private DecodedInstruction decodeSizedExclusive(int raw, int address, Condition condition, boolean load, int sizeBytes) {
        if (!architecture.has(ArmFeature.EXCLUSIVE_SIZED)) {
            return DecodedInstruction.unimplemented(address, raw, InstructionSet.THUMB, condition);
        }
        int rn = (raw >>> RN2_SHIFT) & RN2_MASK;
        int rt = (raw >>> RT_SHIFT_LDRD) & RT_MASK_LDRD;
        if (rn == PROGRAM_COUNTER || rt == PROGRAM_COUNTER) {
            return null; // UNPREDICTABLE
        }
        int upperField = (raw >>> RT2_SHIFT) & RT_MASK_LDRD;
        if (sizeBytes == 8) {
            if ((rt & 1) != 0 || upperField != rt + 1) {
                return null; // UNPREDICTABLE: Rt precisa ser par e Rt2==Rt+1 (ver javadoc acima)
            }
        } else if (upperField != EXCLUSIVE_FIXED_MARKER) {
            return null; // campo Rt2/marcador inválido para a forma B/H
        }
        if (load) {
            int marker = (raw >>> EXCLUSIVE_RD_SHIFT) & EXCLUSIVE_RD_MASK;
            if (marker != EXCLUSIVE_FIXED_MARKER) {
                return null;
            }
            return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                    InstructionKind.LOAD_EXCLUSIVE, rt, rn, -1, 0, false, false, false, sizeBytes, false);
        }
        int rd = (raw >>> EXCLUSIVE_RD_SHIFT) & EXCLUSIVE_RD_MASK;
        if (rd == PROGRAM_COUNTER || rd == rn || rd == rt || (sizeBytes == 8 && rd == upperField)) {
            return null; // UNPREDICTABLE: Rd (status) não pode coincidir com PC/Rn/Rt/Rt2
        }
        return new DecodedInstruction(address, raw, InstructionSet.THUMB, condition,
                InstructionKind.STORE_EXCLUSIVE, rd, rn, rt, 0, false, false, false, sizeBytes, false);
    }
}
