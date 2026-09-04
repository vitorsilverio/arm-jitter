package dev.vitorsilverio.armjitter.advsimd;

/// `AdvSIMDExpandImm` (ARM DDI 0406C §A7.4.6 / ARM DDI 0487, `shared/functions/vector`) — o núcleo
/// vetorial COMPARTILHADO da instrução "1-reg-and-modified-immediate" (`VMOV`/`VMVN`/`VORR`/`VBIC`
/// imediato em NEON A32, B13.9; `Vimm`/`FMOVI_v_h` em A64, B19.6). Aplicação ANTECIPADA da decisão
/// D1 da RFC B13.2 (reuso, não espelhamento): nasce aqui em vez de nascer duplicada em cada lado,
/// porque nenhum dos dois mundos tinha implementado esta instrução ainda.
///
/// Oráculo, nesta ordem: `AdvSIMDExpandImm` (ARM ARM) e a implementação real do QEMU
/// (`target/arm/tcg/translate.c`, `asimd_imm_const`) — a tabela abaixo existe só para dimensionar o
/// trabalho, não como fonte de verdade (precedente B13.3: a tabela transcrita à mão já errou uma
/// vez, venceu o QEMU).
///
/// A expansão acontece AQUI, no núcleo, mas é CHAMADA pelo decoder de cada mundo (precedente
/// `VFPExpandImm`/`Aarch64Decoder#expandFpImmediate`, com o comentário explícito "nunca no
/// executor") — o {@link Expanded#imm64} devolvido já é o valor pronto para gravar no registrador,
/// nunca recalculado por execução.
public final class AdvSimdModifiedImmediate {
    private AdvSimdModifiedImmediate() {
    }

    /// Máscara de um byte, usada para isolar `imm8` antes de deslocar.
    private static final int BYTE_MASK = 0xFF;
    /// `cmode` (4 bits) que, junto de `op=1`, é reservado em AArch32 (em AArch64 é `FMOV` de 64
    /// bits — B19.6, fora do escopo desta classe).
    private static final int CMODE_RESERVED_IN_AARCH32 = 0b1111;

    /// Resultado da expansão: a operação real (`MOV`/`MVN`/`ORR`/`BIC`, já classificada) e o
    /// imediato de 64 bits EXPANDIDO — `MVN` devolve o MESMO `imm64` de `MOV` (a inversão acontece
    /// na EXECUÇÃO, nunca aqui: ver a Decisão 2 da B13.9, "não dobrar MVN em MOV invertido").
    public record Expanded(AdvSimdModifiedImmediateOp op, long imm64) {
    }

    /// `true` quando `(cmode, op)` é o padrão reservado em AArch32 (`cmode=1111`, `op=1` — em
    /// AArch64 o mesmo padrão é `FMOV (vector, immediate)` de 64 bits, válido). O decoder A32 DEVE
    /// checar isto ANTES de chamar {@link #expand}, que não trata este padrão (não implementa
    /// semântica A64 nenhuma — fora do escopo da B13.9).
    public static boolean isReservedInAarch32(int cmode, int op) {
        return cmode == CMODE_RESERVED_IN_AARCH32 && op == 1;
    }

    /// Expande `(imm8, cmode, op)` para a operação real e o imediato de 64 bits. `imm8` é o
    /// "`abcdefgh`" de 8 bits já remontado pelo decoder (`%asimd_imm_value` do QEMU); `cmode`/`op`
    /// são os campos crus do encoding. **Não chamar com `isReservedInAarch32(cmode, op)`** — lança
    /// {@link IllegalStateException}.
    public static Expanded expand(int imm8, int cmode, int op) {
        if (isReservedInAarch32(cmode, op)) {
            throw new IllegalStateException(
                    "cmode=1111,op=1 é reservado em AArch32; o chamador deve checar isReservedInAarch32 antes");
        }
        return new Expanded(classify(cmode, op), expandImm64(imm8, cmode, op));
    }

    /// Classificação da operação real (comentário de `neon-dp.decode:376-383` do QEMU): `cmode`
    /// ímpar `< 12` alterna `ORR`/`BIC` conforme `op`; fora disso é `MOV`/`MVN`, com a ÚNICA exceção
    /// `cmode=1110,op=1` (que é `MOV` — cada bit de `imm8` vira um byte — não `MVN`).
    private static AdvSimdModifiedImmediateOp classify(int cmode, int op) {
        if (cmode == 0b1110 && op == 1) {
            return AdvSimdModifiedImmediateOp.MOV;
        }
        if ((cmode & 1) != 0 && cmode < 12) {
            return op == 0 ? AdvSimdModifiedImmediateOp.ORR : AdvSimdModifiedImmediateOp.BIC;
        }
        return op == 0 ? AdvSimdModifiedImmediateOp.MOV : AdvSimdModifiedImmediateOp.MVN;
    }

    /// `cmode<3:1>` (bits altos de `cmode`) escolhe o GRUPO de expansão; `cmode<0>`/`op` escolhem a
    /// variante dentro do grupo (ver os métodos privados de cada ramo).
    private static long expandImm64(int imm8, int cmode, int op) {
        int group = cmode >>> 1;
        return switch (group) {
            case 0b000 -> replicate2(shiftedByte(imm8, 0));
            case 0b001 -> replicate2(shiftedByte(imm8, 8));
            case 0b010 -> replicate2(shiftedByte(imm8, 16));
            case 0b011 -> replicate2(shiftedByte(imm8, 24));
            case 0b100 -> replicate4(shiftedByte(imm8, 0));
            case 0b101 -> replicate4(shiftedByte(imm8, 8));
            case 0b110 -> replicate2(onesImmediate(imm8, cmode));
            default -> specialCase(imm8, cmode, op); // group 0b111 (cmode 14/15)
        };
    }

    /// `imm8` deslocado `shift` bits, como valor de 32 bits (ainda não replicado).
    private static long shiftedByte(int imm8, int shift) {
        return ((long) (imm8 & BYTE_MASK)) << shift;
    }

    /// Grupo `cmode<3:1>==110`: 32 bits com **uns** nos bits baixos — `cmode<0>=0` insere 8 uns
    /// (`(imm8<<8)|0xFF`), `cmode<0>=1` insere 16 uns (`(imm8<<16)|0xFFFF`).
    private static long onesImmediate(int imm8, int cmode) {
        boolean sixteenOnes = (cmode & 1) != 0;
        long shifted = sixteenOnes ? shiftedByte(imm8, 16) : shiftedByte(imm8, 8);
        long ones = sixteenOnes ? 0xFFFFL : 0xFFL;
        return shifted | ones;
    }

    /// Replica um valor de 32 bits pelas duas metades de uma palavra de 64 bits.
    private static long replicate2(long value32) {
        long v = value32 & 0xFFFF_FFFFL;
        return (v << 32) | v;
    }

    /// Replica um valor de 16 bits pelas quatro metades de uma palavra de 64 bits.
    private static long replicate4(long value16) {
        long v = value16 & 0xFFFFL;
        return (v << 48) | (v << 32) | (v << 16) | v;
    }

    /// Grupo `cmode<3:1>==111` (`cmode` 14/15) — casos especiais, discriminados por `cmode<0>`/`op`
    /// (chamador já garantiu que `(cmode,op) != (1111,1)`, o reservado em AArch32).
    private static long specialCase(int imm8, int cmode, int op) {
        boolean isCmode15 = (cmode & 1) != 0;
        if (!isCmode15) {
            // cmode=1110 (14): op=0 → VMOV.I8 (replica o byte 8×); op=1 → VMOV.I64 (cada BIT de
            // `imm8` vira um byte inteiro).
            return op == 0 ? replicate8(imm8) : perBitByteExpand(imm8);
        }
        // cmode=1111 (15), op=0 (o único caso que chega aqui — op=1 já foi rejeitado por `expand`):
        // VMOV.F32, imediato de ponto flutuante de 32 bits replicado 2×.
        return replicate2(singlePrecisionFloatBits(imm8));
    }

    /// Replica o byte `imm8` pelas 8 posições de uma palavra de 64 bits (`VMOV.I8`).
    private static long replicate8(int imm8) {
        long b = imm8 & BYTE_MASK;
        long result = 0;
        for (int bit = 0; bit < 8; bit++) {
            result |= b << (bit * 8);
        }
        return result;
    }

    /// Cada BIT de `imm8` vira um byte inteiro (`0xFF` se o bit está setado, `0x00` senão) —
    /// `VMOV.I64`.
    private static long perBitByteExpand(int imm8) {
        long result = 0;
        for (int bit = 0; bit < 8; bit++) {
            if (((imm8 >>> bit) & 1) != 0) {
                result |= 0xFFL << (bit * 8);
            }
        }
        return result;
    }

    /// `imm8<7>:NOT(imm8<6>):Replicate(imm8<6>,5):imm8<5:0>:Zeros(19)` — expansão de ponto
    /// flutuante de 32 bits (`VMOV.F32`), MESMO algoritmo conceitual do precedente escalar
    /// `VFPExpandImm`/`Aarch64Decoder#expandFpImmediate` (sinal + expoente replicado + mantissa),
    /// só que aqui o resultado É os 32 bits inteiros (não um "meio" de 16 bits a deslocar depois).
    private static long singlePrecisionFloatBits(int imm8) {
        int sign = (imm8 >>> 7) & 1;
        int bit6 = (imm8 >>> 6) & 1;
        int notBit6 = bit6 ^ 1;
        int exponentReplicate = bit6 == 1 ? 0b11111 : 0b00000;
        int mantissa6 = imm8 & 0x3F;
        return ((long) sign << 31) | ((long) notBit6 << 30) | ((long) exponentReplicate << 25) | ((long) mantissa6 << 19);
    }
}
