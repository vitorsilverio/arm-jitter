package dev.vitorsilverio.armjitter.jit64;

/// Chave de cache para um bloco A64 compilado — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.jit.BlockKey} (32 bits), introduzido na task B6.4, mas
/// deliberadamente MAIS SIMPLES (ver `b6.4-aarch64-asm-backend.md`, decisão D5): sem
/// `instructionSet` (A64 não tem Thumb) e sem `itState` (não existe IT block em A64).
///
/// `translationGeneration` (B6.6.5, espelho de `BlockKey#translationGeneration` do precedente
/// 32-bit, B4.1.4): a geração de tradução MMU
/// ({@link dev.vitorsilverio.armjitter.memory.AddressSpace64#translationGeneration()}) vigente no
/// momento do lift. Sem este campo, um bloco compilado sob um mapeamento de página
/// (`TTBR0_EL1`) ficaria em cache indexado só por `pc`, e uma troca de processo que remapeia o
/// mesmo VA para código físico diferente executaria silenciosamente o bloco velho. Com o campo, a
/// troca de geração é um miss natural no {@link BlockCache64} — o bloco antigo simplesmente nunca
/// é encontrado pela chave nova, sem precisar de invalidação explícita. `0` (default, ver
/// construtor de compatibilidade abaixo) para todo consumidor sem MMU (G3).
///
/// **Pendência (D3 de `b6.6.5-aarch64-translation-generation.md`)**: `jit64/` hoje não tem inline
/// cache nem encadeamento de blocos (B6.4 D0) — só o lookup de {@link BlockCache64} em
/// {@link JitRuntime64#execute} consome este campo. Se uma PR futura adicionar IC ou encadeamento
/// a `jit64/`, ela precisa LEMBRAR de checar `translationGeneration` nesses pontos novos também
/// (mesmos dois pontos extras que o precedente 32-bit `BlockKey`/`JitRuntime` já consome).
///
/// @param pc program counter inicial do bloco (endereço de 64 bits)
/// @param translationGeneration geração de tradução MMU vigente no momento do lift (`0` fora de
///         contexto MMU)
public record BlockKey64(long pc, int translationGeneration) {

    /// Construtor de compatibilidade (pré-B6.6.5): `translationGeneration=0`, preserva o
    /// comportamento/chamadas existentes (G3) — todo consumidor sem MMU permanece nesta geração
    /// constante para sempre.
    public BlockKey64(long pc) {
        this(pc, 0);
    }
}
