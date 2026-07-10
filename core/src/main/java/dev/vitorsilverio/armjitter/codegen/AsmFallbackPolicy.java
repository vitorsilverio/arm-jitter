package dev.vitorsilverio.armjitter.codegen;

/// Política de fallback quando um bloco IR contém ops não suportadas nativamente pelo
/// {@link AsmCodeEmitter}.
public enum AsmFallbackPolicy {

    /// Todo o bloco cai no interpretado quando qualquer op não for suportada (padrão, Fases 4–6).
    WHOLE_BLOCK,

    /// Cada op é compilada individualmente; ops não suportadas fazem chamada ao interpretado
    /// via {@link dev.vitorsilverio.armjitter.codegen.jvm.IrOpInterop} inline no bytecode gerado.
    /// Permite que blocos parcialmente suportados sejam compilados.
    PER_OP,

    /// Lança {@link UnsupportedOperationException} se qualquer op do bloco não for suportada.
    /// Útil para verificar cobertura durante desenvolvimento.
    FAIL_FAST
}
