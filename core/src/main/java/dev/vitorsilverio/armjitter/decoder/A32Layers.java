package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;

import java.util.ArrayList;
import java.util.List;

/// E15.16a: as linhas do {@link ArmDecoder} (A32), em camadas.
///
/// A tabela não tem prioridade, e a cascata antiga dependia da ordem em quatro pontos. Cada camada
/// é consultada depois da anterior; dentro dela nenhuma linha se sobrepõe (`A32RowsTest`):
///
/// 1. {@link #SPECIFIC}: todo encoding com padrão próprio.
/// 2. {@link #MSR_REGISTER_SHADOW}: o `MSR` registrador com `bit25=1` (ver
///    {@link A32BranchSystemRows#MSR_REGISTER_SHADOW}) — abaixo do `WFI`, acima do `MSR` imediato.
/// 3. {@link #MSR_IMMEDIATE}: o `MSR` imediato, que é um recorte do `TEQ`/`CMN` imediato de `S=0`.
/// 4. {@link #GENERIC}: o ALU e as recusas do que sobra nos espaços de multiplicação e media.
final class A32Layers {
    /// Camada 1: encodings com padrão próprio.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> SPECIFIC = concat(A32BranchSystemRows.ROWS,
            A32DataProcessingRows.ROWS, A32MultiplyRows.ROWS, A32MediaRows.ROWS, A32LoadStoreRows.ROWS);
    /// Camada 2: `MSR` registrador com `bit25=1`.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> MSR_REGISTER_SHADOW =
            List.of(A32BranchSystemRows.MSR_REGISTER_SHADOW);
    /// Camada 3: `MSR` imediato.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> MSR_IMMEDIATE =
            List.of(A32BranchSystemRows.MSR_IMMEDIATE);
    /// Camada 4: ALU genérico e recusas de resto de espaço.
    static final List<DecodeRow<ArmFeature, DecodedInstruction>> GENERIC = concat(A32DataProcessingRows.GENERIC,
            A32MultiplyRows.RESIDUE, A32MediaRows.RESIDUE);
    /// As camadas condicionais, na ordem de consulta.
    static final List<List<DecodeRow<ArmFeature, DecodedInstruction>>> CONDITIONAL_LAYERS =
            List.of(SPECIFIC, MSR_REGISTER_SHADOW, MSR_IMMEDIATE, GENERIC);

    private A32Layers() {
    }

    /// Junta listas de linhas, na ordem dada.
    @SafeVarargs
    static List<DecodeRow<ArmFeature, DecodedInstruction>> concat(
            List<DecodeRow<ArmFeature, DecodedInstruction>>... lists) {
        List<DecodeRow<ArmFeature, DecodedInstruction>> all = new ArrayList<>();
        for (List<DecodeRow<ArmFeature, DecodedInstruction>> list : lists) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }
}
