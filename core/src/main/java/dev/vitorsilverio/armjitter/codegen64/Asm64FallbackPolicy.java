package dev.vitorsilverio.armjitter.codegen64;

/// Política de fallback quando um {@link dev.vitorsilverio.armjitter.ir64.Ir64Block} contém ops não
/// suportadas nativamente pelo {@link Asm64CodeEmitter} — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy} (32 bits), introduzido na task
/// C12.2. Só nasce com os dois modos que têm consumidor hoje; {@code FAIL_FAST} fica para quando
/// houver necessidade real (ver a spec da C12.2).
public enum Asm64FallbackPolicy {

    /// Todo o bloco cai no interpretado quando qualquer op não for suportada (padrão, desde B6.4).
    WHOLE_BLOCK,

    /// Cada op é compilada individualmente; ops não suportadas fazem chamada ao interpretado
    /// via {@link dev.vitorsilverio.armjitter.codegen64.jvm64.Ir64OpInterop} inline no bytecode
    /// gerado. Permite que blocos parcialmente suportados sejam compilados.
    PER_OP
}
