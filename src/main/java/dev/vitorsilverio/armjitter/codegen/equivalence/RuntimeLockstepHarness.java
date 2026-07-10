package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;

/// Compara dois [JitRuntime]s em LOCKSTEP: executa `execute()` alternado sobre cores
/// espelhados e exige [CpuSnapshot] idêntico após CADA chamada (task C0.1).
///
/// Racional: o [BlockEquivalenceHarness] valida a semântica de UM bloco; este harness
/// valida a GRANULARIDADE do runtime — quantos blocos/ciclos uma chamada de `execute`
/// consome. É a validação central do loop-superbloco (invariante S1 da spec
/// `tasks/trilha-c-perf/c0-impl-loop-superbloco.md`): com o mesmo chain budget, um
/// runtime com superblocos deve parar exatamente onde o chain loop pararia, chamada a
/// chamada.
///
/// Pré-requisitos dos dois runtimes (senão a granularidade diverge por construção):
/// - mesmo `hotThreshold` (recomendado 1 — bloco inteiro por chamada desde a 1ª execução;
///   thresholds maiores fazem o caminho clássico interpretar instrução a instrução no frio
///   enquanto o tiered interpreta o bloco inteiro);
/// - mesmo `setChainCycleBudget`;
/// - runtimes SÍNCRONOS (clássicos, sem tier frio — ex.: `interpretedArmThumb`/`jvmArmThumb`).
///   O modo tiered (`armThumb`) compila em background: o momento em que o bloco entra no
///   inline cache — e portanto quando o chaining liga — é não-determinístico, e o lockstep
///   flutua sem ser bug. Recursos assíncronos sob teste (ex.: loop-superbloco da C0.3)
///   precisam de um gancho de construção síncrona no teste.
///
/// Limitação documentada: programas SEM código automodificável — invalidações (generation)
/// dependem do estado de cache de cada runtime e mudariam os pontos de parada de forma
/// legítima, gerando falso positivo.
public final class RuntimeLockstepHarness {
    /// Executa `calls` chamadas alternadas e lança [EquivalenceMismatchException] na
    /// primeira divergência de ciclos internos retornados ou de estado de CPU.
    public void assertLockstep(JitRuntime reference, JitRuntime candidate,
                               EquivalencePairFactory pairFactory, int calls) {
        EquivalencePair pair = pairFactory.create();
        ArmCore referenceCore = pair.reference();
        ArmCore candidateCore = pair.candidate();
        for (int call = 0; call < calls; call++) {
            int referenceCycles = reference.execute(referenceCore.programCounter(), referenceCore);
            int candidateCycles = candidate.execute(candidateCore.programCounter(), candidateCore);
            String label = "lockstep call " + call;
            if (referenceCycles != candidateCycles) {
                throw EquivalenceMismatchException.of(
                        label,
                        "internalCycles",
                        Integer.toString(referenceCycles),
                        Integer.toString(candidateCycles));
            }
            CpuSnapshot.capture(referenceCore)
                    .assertEqualTo(CpuSnapshot.capture(candidateCore), label);
        }
    }
}
