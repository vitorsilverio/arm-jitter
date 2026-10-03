package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.MProfileException;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.core.MveVptState;

/// Constantes e helpers compartilhados pelos executores MVE (task E15.8).
final class IrMveSupport {
    private IrMveSupport() {
    }

    /// `LR`/`R14`: contador de loop (`DLSTP`/`WLSTP`/`LE*`) que {@link MveVptState#elementMask}
    /// consome quando {@link FpscrRegister#ltpsize()} `< 4` (B16.15 — antes a tail predication era
    /// desligada passando `LTPSIZE=4` fixo).
    static final int LINK_REGISTER = 14;

    /// Entra em `USAGE_FAULT` com `UFSR.INVSTATE` (`mve_eci_check` real: `ECI` reservado numa
    /// instrução MVE beatwise) — mesmo cast direto de {@link IrSystemExecutor#executeNocp}.
    ///
    /// @return sempre `true` — `enterException` sempre muda o PC para o vetor do handler.
    static boolean faultInvstate(ArmCore core) {
        MProfileExceptionModel model = (MProfileExceptionModel) core.exceptionModel();
        model.setUsageFaultInvstate();
        model.enterException(core, MProfileException.USAGE_FAULT);
        return true;
    }
}
