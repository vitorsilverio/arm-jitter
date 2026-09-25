package dev.vitorsilverio.armjitter.decoder64;

/// Instruções A64 que `PSTATE.SM = 1` torna `UNDEFINED` quando `FEAT_SME_FA64` não está efetivo
/// (B18.2) — as listas `FAIL` do Apêndice E1.1 do DDI0616 (SME), no formato do `sme-fa64.decode` do
/// QEMU. É uma função PURA da palavra: o estado (`SM`/`FA64`) é decidido na execução, por
/// {@code Ir64Op.StreamingRestricted}.
///
/// Semântica do decodetree do QEMU, preservada aqui: cada grupo `{ [ OK... ] FAIL }` avalia os
/// padrões `OK` primeiro (a instrução é permitida) e só então o `FAIL` do grupo.
final class StreamingModeRestrictions {
    /// Grupo 1 — AdvSIMD vetorial. `OK`: `SMOV`/`UMOV` do elemento 0 para registrador geral.
    private static final Pattern[] VECTOR_OK = {
        Pattern.of("0-00 1110 0000 0001 0010 11-- ---- ----"), // SMOV W|Xd,Vn.B[0]
        Pattern.of("0-00 1110 0000 0010 0010 11-- ---- ----"), // SMOV W|Xd,Vn.H[0]
        Pattern.of("0100 1110 0000 0100 0010 11-- ---- ----"), // SMOV Xd,Vn.S[0]
        Pattern.of("0000 1110 0000 0001 0011 11-- ---- ----"), // UMOV Wd,Vn.B[0]
        Pattern.of("0000 1110 0000 0010 0011 11-- ---- ----"), // UMOV Wd,Vn.H[0]
        Pattern.of("0000 1110 0000 0100 0011 11-- ---- ----"), // UMOV Wd,Vn.S[0]
        Pattern.of("0100 1110 0000 1000 0011 11-- ---- ----"), // UMOV Xd,Vn.D[0]
    };
    private static final Pattern VECTOR_FAIL = Pattern.of("0--0 111- ---- ---- ---- ---- ---- ----");

    /// Grupo 2 — AdvSIMD escalar de um elemento. `OK`: `FMULX`/`FRECPS`/`FRSQRTS`/`FRECPE`/`FRSQRTE`/
    /// `FRECPX` escalares (com e sem FP16).
    private static final Pattern[] SINGLE_ELEMENT_OK = {
        Pattern.of("0101 1110 --1- ---- 11-1 11-- ---- ----"), // FMULX/FRECPS/FRSQRTS (scalar)
        Pattern.of("0101 1110 -10- ---- 00-1 11-- ---- ----"), // FMULX/FRECPS/FRSQRTS (scalar, FP16)
        Pattern.of("01-1 1110 1-10 0001 11-1 10-- ---- ----"), // FRECPE/FRSQRTE/FRECPX (scalar)
        Pattern.of("01-1 1110 1111 1001 11-1 10-- ---- ----"), // FRECPE/FRSQRTE/FRECPX (scalar, FP16)
    };
    private static final Pattern SINGLE_ELEMENT_FAIL = Pattern.of("01-1 111- ---- ---- ---- ---- ---- ----");

    /// Ilegais sem exceção: estruturas `LDn`/`STn`, cripto AdvSIMD e `FJCVTZS`.
    private static final Pattern[] ALWAYS_FAIL = {
        Pattern.of("0-00 110- ---- ---- ---- ---- ---- ----"), // Advanced SIMD structure load/store
        Pattern.of("1100 1110 ---- ---- ---- ---- ---- ----"), // Advanced SIMD cryptography extensions
        Pattern.of("0001 1110 0111 1110 0000 00-- ---- ----"), // FJCVTZS
    };

    private StreamingModeRestrictions() {
    }

    /// `true` se {@code word} é ilegal em modo streaming sem `FEAT_SME_FA64`.
    static boolean isIllegalInStreamingMode(int word) {
        if (VECTOR_FAIL.matches(word)) {
            return !matchesAny(VECTOR_OK, word);
        }
        if (SINGLE_ELEMENT_FAIL.matches(word)) {
            return !matchesAny(SINGLE_ELEMENT_OK, word);
        }
        return matchesAny(ALWAYS_FAIL, word);
    }

    private static boolean matchesAny(Pattern[] patterns, int word) {
        for (Pattern pattern : patterns) {
            if (pattern.matches(word)) {
                return true;
            }
        }
        return false;
    }

    /// Padrão de bits `0`/`1`/`-` (espaços ignorados), do MSB ao LSB — o formato do decodetree.
    record Pattern(int mask, int value) {
        static Pattern of(String bits) {
            int mask = 0;
            int value = 0;
            int width = 0;
            for (char c : bits.toCharArray()) {
                if (c == ' ') {
                    continue;
                }
                mask <<= 1;
                value <<= 1;
                width++;
                if (c != '-') {
                    mask |= 1;
                    value |= c - '0';
                }
            }
            if (width != Integer.SIZE) {
                throw new IllegalArgumentException("padrão de " + width + " bits: " + bits);
            }
            return new Pattern(mask, value);
        }

        boolean matches(int word) {
            return (word & mask) == value;
        }
    }
}
