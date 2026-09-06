package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD de troca/permuta de DOIS destinos (`VSWP`/`VTRN`/`VUZP`/`VZIP`, A32 B13.14) —
/// **sem equivalente A64**: `UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2` do A64 são SEIS instruções de
/// UM destino cada, não a mesma semântica com nome diferente (mapear 1:1 pelo nome produziria
/// metade do resultado — ver Armadilha 1 da task). Núcleo nasce em {@link AdvSimdLanes#swapPermute}
/// para o A32 (e, futuramente, o adaptador T32 da B13.16, que delega mecanicamente ao A32).
public enum AdvSimdSwapPermuteOp {
    /// `VSWP` — troca completa dos dois registradores, ignora tamanho de elemento.
    SWAP,
    /// `VTRN` — troca os elementos ÍMPARES de `Vd` com os PARES de `Vm` (transposição 2×2).
    TRN,
    /// `VUZP` — de-intercala a concatenação `[Vd, Vm]`: pares em `Vd`, ímpares em `Vm`.
    UZP,
    /// `VZIP` — intercala `Vd`/`Vm`; metade BAIXA do resultado intercalado em `Vd`, ALTA em `Vm`.
    ZIP
}
