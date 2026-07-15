package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Chave de cache para um bloco compilado.
///
/// `itState` (B2.4, decisão D1 de `b2.4-thumb2-branches-it.md`): o ITSTATE\[7:0\] do Thumb-2 IT
/// block ATIVO no momento do lift (`0` fora de um IT block, o caso comum). Um mesmo `pc` pode ser
/// lifted com ITSTATE de entrada DIFERENTES ao longo do tempo (ex.: um branch pousando no meio de
/// um IT block real — CONSTRAINED UNPREDICTABLE, raro, mas não impossível) e cada combinação
/// produz condições por-op BAKED diferentes no `IrBlock` resultante; sem este campo na chave, o
/// `BlockCache` reaproveitaria silenciosamente o bloco do primeiro ITSTATE visto para qualquer
/// ITSTATE seguinte no MESMO `pc`, executando condições erradas. Quando `itState==0` a chave é
/// idêntica à de antes da B2.4 (zero overhead no caso comum, garantido pelo construtor de
/// compatibilidade abaixo).
public record BlockKey(
        /// Program counter inicial do bloco.
        int pc,
        /// Conjunto de instruções usado para decodificar o bloco.
        InstructionSet instructionSet,
        /// ITSTATE\[7:0\] do Thumb-2 IT block ativo no momento do lift (`0` = fora de IT block).
        int itState) {

    /// Construtor de compatibilidade (pré-B2.4): `itState=0`, preserva o comportamento/chamadas
    /// existentes (G3) — a esmagadora maioria dos lifts começa fora de um IT block.
    public BlockKey(int pc, InstructionSet instructionSet) {
        this(pc, instructionSet, 0);
    }
}
