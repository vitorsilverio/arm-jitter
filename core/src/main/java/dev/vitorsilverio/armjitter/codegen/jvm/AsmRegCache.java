package dev.vitorsilverio.armjitter.codegen.jvm;

import java.util.Arrays;

/// Mapa registrador-guest → local JVM do bloco em compilação + flag estático de escrita (o register
/// cache descrito em {@link AsmBlockCompiler}).
final class AsmRegCache {
    static final AsmRegCache EMPTY = new AsmRegCache();
    final int[] slot = new int[AsmEmitterBase.CACHEABLE_REGISTERS];      // r0..r14; -1 = não cacheado
    final boolean[] dirty = new boolean[AsmEmitterBase.CACHEABLE_REGISTERS]; // o bloco PODE escrever o reg

    AsmRegCache() {
        Arrays.fill(slot, -1);
    }

    boolean cached(int reg) {
        return reg >= 0 && reg < AsmEmitterBase.CACHEABLE_REGISTERS && slot[reg] >= 0;
    }
}
