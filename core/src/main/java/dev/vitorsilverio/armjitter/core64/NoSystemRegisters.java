package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

/// O {@link Aarch64SystemRegisterBus} vazio retornado por {@link Aarch64SystemRegisterBus#none()}:
/// afirma não tratar nenhum registrador, então {@link #read}/{@link #write} nunca são alcançados
/// pelo executor (que sempre checa {@link #handles} antes) — lançam por defesa, mesmo padrão de
/// `NoCoprocessor` no mundo 32-bit.
enum NoSystemRegisters implements Aarch64SystemRegisterBus {
    INSTANCE;

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        return false;
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        throw new UnsupportedOperationException(
                "AArch64: nenhum Aarch64SystemRegisterBus instalado para " + register);
    }

    @Override
    public void write(Aarch64SystemRegisterId register, long value) {
        throw new UnsupportedOperationException(
                "AArch64: nenhum Aarch64SystemRegisterBus instalado para " + register);
    }
}
