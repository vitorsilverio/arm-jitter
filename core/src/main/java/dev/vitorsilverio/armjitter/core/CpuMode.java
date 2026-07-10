package dev.vitorsilverio.armjitter.core;

/// Modos de operacao expostos pelo CPSR em CPUs ARM classicas.
public enum CpuMode {
    /// Modo usuario.
    USER(0b10000),
    /// Modo FIQ.
    FIQ(0b10001),
    /// Modo IRQ.
    IRQ(0b10010),
    /// Modo supervisor.
    SUPERVISOR(0b10011),
    /// Modo abort.
    ABORT(0b10111),
    /// Modo undefined.
    UNDEFINED(0b11011),
    /// Modo system.
    SYSTEM(0b11111);

    private final int bits;

    /// Tabela de lookup indexada pelos cinco bits de modo (0..31), preenchida uma vez.
    /// Evita, em {@link #fromBits}, a alocação de `values()` e a varredura linear por chamada
    /// — `fromBits` é chamado por bloco (via `synchronizeModeFromCpsr`) e em acessos bancados.
    private static final CpuMode[] BY_BITS = buildLookup();

    CpuMode(int bits) {
        this.bits = bits;
    }

    private static CpuMode[] buildLookup() {
        CpuMode[] table = new CpuMode[32];
        for (CpuMode mode : values()) {
            table[mode.bits] = mode;
        }
        return table;
    }

    /// Retorna os cinco bits usados pelo CPSR para representar este modo.
    public int bits() {
        return bits;
    }

    /// Converte os cinco bits de modo do CPSR para um `CpuMode` em O(1).
    public static CpuMode fromBits(int bits) {
        CpuMode mode = BY_BITS[bits & 0b11111];
        if (mode == null) {
            throw new IllegalArgumentException("Unknown ARM CPU mode bits: 0x" + Integer.toHexString(bits & 0b11111));
        }
        return mode;
    }
}
