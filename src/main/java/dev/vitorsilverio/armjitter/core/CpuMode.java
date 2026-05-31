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

    CpuMode(int bits) {
        this.bits = bits;
    }

    /// Retorna os cinco bits usados pelo CPSR para representar este modo.
    public int bits() {
        return bits;
    }

    /// Converte os cinco bits de modo do CPSR para um `CpuMode`.
    public static CpuMode fromBits(int bits) {
        int normalized = bits & 0b11111;
        for (CpuMode mode : values()) {
            if (mode.bits == normalized) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown ARM CPU mode bits: 0x" + Integer.toHexString(normalized));
    }
}
