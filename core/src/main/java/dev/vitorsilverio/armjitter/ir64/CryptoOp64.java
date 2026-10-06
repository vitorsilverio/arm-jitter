package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64CryptoExecutor;

/// Operações da Cryptographic Extension do A64 (AES, SHA-1/SHA-2, SHA-3, SHA-512, SM3, SM4).
///
/// Sub-interface selada de {@link AdvSimdOp64} (task E15.2): os records desta família vivem aqui, e
/// o `switch` por padrão sobre {@link Ir64Op} continua exaustivo pela hierarquia selada.
public sealed interface CryptoOp64 extends AdvSimdOp64 permits CryptoOp64.Aes,
        CryptoOp64.ShaThreeRegister, CryptoOp64.ShaTwoRegister, CryptoOp64.Sha3FourRegister,
        CryptoOp64.Sha3TwoSourceRotate, CryptoOp64.Sha512ThreeRegister,
        CryptoOp64.Sha512TwoRegister, CryptoOp64.Sm3ThreeRegister, CryptoOp64.Sm3FourRegister,
        CryptoOp64.Sm3ThreeRegisterImm2, CryptoOp64.Sm4Encrypt, CryptoOp64.Sm4KeyUpdate {

    /// `AESE`/`AESD`/`AESMC`/`AESIMC` (B8.11, ARMv8-A Cryptographic Extension) — sempre opera nos
    /// 128 bits inteiros (`Q` fixo em `1` no encoding real, sem forma "metade"). Para `AESE`/
    /// `AESD`, {@link #rn} é o SEGUNDO operando (`Rm` no manual — o decoder já resolve o alias
    /// `Rn=Rd` do encoding real, ver `ARM DDI 0487` "AESE"); `Rd` ATUAL é lido como primeiro
    /// operando pelo executor (escrita destrutiva real, não uma cópia). Para `AESMC`/`AESIMC`,
    /// {@link #rn} é o ÚNICO operando (`Rd` atual é ignorado).
    record Aes(
            /// Operação a executar.
            Ir64CryptoAesOp op,
            /// Registrador `V` de destino (e, para `AESE`/`AESD`, primeiro operando).
            int rd,
            /// Registrador `V` fonte (segundo operando para `AESE`/`AESD`; único operando para
            /// `AESMC`/`AESIMC`).
            int rn) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_AES; }
        @Override public boolean execute(Aarch64Core core) { return Ir64CryptoExecutor.executeAes(core, this); }
    }

    /// "Cryptographic three-register SHA" (B8.11b, mesma ARMv8-A Cryptographic Extension de
    /// {@link Aes}) — `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/`SHA256H2`/`SHA256SU1`.
    /// Sempre opera nos 128 bits inteiros (`Q` fixo em `1`, sem forma "metade"). `SHA1C`/`SHA1P`/
    /// `SHA1M` só usam a palavra 0 (32 bits baixos) de {@link #rn} (o "hash chain E" escalar do
    /// SHA1, codificado como registrador `S`); `SHA256H`/`SHA256H2`/`SHA256SU1` usam os 4 elementos
    /// de {@link #rn}. `Rn` NUNCA é escrito de volta por nenhuma destas operações — só {@link #rd}.
    record ShaThreeRegister(
            /// Operação a executar.
            Ir64CryptoShaThreeRegisterOp op,
            /// Registrador `V` de destino (lido E escrito — estado corrente do hash).
            int rd,
            /// Segundo operando (fonte, nunca modificado).
            int rn,
            /// Terceiro operando (fonte, nunca modificado — bloco de mensagem `W`).
            int rm) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SHA_THREE_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeShaThreeRegister(core, this);
        }
    }

    /// "Cryptographic two-register SHA" (B8.11b, mesma extensão) — `SHA1H`/`SHA1SU1`/`SHA256SU0`.
    /// Para `SHA1H`, {@link #rd} atual é ignorado (função pura de {@link #rn}); para `SHA1SU1`/
    /// `SHA256SU0`, {@link #rd} é lido E escrito (acumula sobre o estado corrente).
    record ShaTwoRegister(
            /// Operação a executar.
            Ir64CryptoShaTwoRegisterOp op,
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SHA_TWO_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeShaTwoRegister(core, this);
        }
    }

    /// "Cryptographic four-register" (`FEAT_SHA3`, ARMv8.2-A, B11.12) — `EOR3`/`BCAX`. Sempre opera
    /// nos 128 bits inteiros ({@code 16B}, sem forma de tamanho de elemento — a operação é bit a
    /// bit e não depende de arranjo). {@link #ra} tem 5 bits (`bits[14:10]`, `V0`-`V31`) como os
    /// outros registradores — E15.15a corrigiu a leitura de 4 bits da B11.12, que recusava `Ra ≥ 16`
    /// (`eor3 v0.16b, v1.16b, v2.16b, v20.16b` = `0xce025020` no `aarch64-none-elf-as`).
    record Sha3FourRegister(
            /// Operação a executar (`EOR3` ou `BCAX`).
            Ir64CryptoSha3Op op,
            /// Registrador `V` de destino.
            int rd,
            /// Primeiro operando fonte.
            int rn,
            /// Segundo operando fonte.
            int rm,
            /// Terceiro operando fonte (`bits[14:10]`).
            int ra) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SHA3_FOUR_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSha3FourRegister(core, this);
        }
    }

    /// "Cryptographic three-register, imm2" (`FEAT_SHA3`, ARMv8.2-A, B11.12) — `RAX1`/`XAR`. Opera
    /// por lane de 64 bits ({@code 2D}, único arranjo válido). {@link #rotateAmount} só se aplica a
    /// `XAR` (campo real `imm6`, `0`-`63`, rotação à DIREITA); `RAX1` não tem campo de imediato no
    /// encoding real (rotação à ESQUERDA fixa por `1`) — o executor trata `RAX1` como caso próprio,
    /// nunca lendo {@link #rotateAmount} para ele, e o decoder deixa o campo em `0` nesse caso.
    record Sha3TwoSourceRotate(
            /// Operação a executar (`RAX1` ou `XAR`).
            Ir64CryptoSha3Op op,
            /// Registrador `V` de destino.
            int rd,
            /// Primeiro operando fonte.
            int rn,
            /// Segundo operando fonte.
            int rm,
            /// Quantidade de rotação à direita usada só por `XAR`, `0`-`63` (ver javadoc da classe).
            int rotateAmount) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SHA3_TWO_SOURCE_ROTATE; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSha3TwoSourceRotate(core, this);
        }
    }

    /// "Cryptographic three-register SHA512" (`FEAT_SHA512`, ARMv8.2-A, B19.10) — `SHA512H`/
    /// `SHA512H2`/`SHA512SU1`. Opera em elementos de **64 bits** ({@link #rd}/{@link #rn}/
    /// {@link #rm} sempre os 128 bits inteiros, `2D`) — diferente de {@link ShaThreeRegister}
    /// (SHA1/SHA256, 32 bits). `Rd` é sempre lido E escrito (estado corrente do hash); `Rn`/`Rm`
    /// nunca são escritos.
    record Sha512ThreeRegister(
            /// Operação a executar.
            Ir64CryptoSha512Op op,
            /// Registrador `V` de destino (lido E escrito).
            int rd,
            /// Segundo operando (fonte, nunca modificado).
            int rn,
            /// Terceiro operando (fonte, nunca modificado).
            int rm) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SHA512_THREE_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSha512ThreeRegister(core, this);
        }
    }

    /// `SHA512SU0` (`FEAT_SHA512`, ARMv8.2-A, B19.10) — atualização de agenda de mensagem SHA-512,
    /// forma de 2 registradores (irmã de `SHA256SU0`, ver {@link ShaTwoRegister}, mas em
    /// elementos de 64 bits). {@link #rd} é lido E escrito (acumula sobre o estado corrente).
    record Sha512TwoRegister(
            /// Registrador `V` de destino.
            int rd,
            /// Registrador `V` fonte.
            int rn) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SHA512_TWO_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSha512TwoRegister(core, this);
        }
    }

    /// "Cryptographic three-register SM3" (`FEAT_SM3`, ARMv8.2-A, B19.10) — `SM3PARTW1`/
    /// `SM3PARTW2`, atualização de agenda de mensagem do hash SM3 (GB/T 32905-2016). Elementos de
    /// 32 bits, `Rd` lido E escrito, `Rn`/`Rm` nunca modificados.
    record Sm3ThreeRegister(
            /// Operação a executar.
            Ir64CryptoSm3Op op,
            /// Registrador `V` de destino (lido E escrito).
            int rd,
            /// Segundo operando (fonte, nunca modificado).
            int rn,
            /// Terceiro operando (fonte, nunca modificado).
            int rm) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SM3_THREE_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSm3ThreeRegister(core, this);
        }
    }

    /// `SM3SS1` (`FEAT_SM3`, ARMv8.2-A, B19.10) — função pura de {@link #rn}/{@link #rm}/
    /// {@link #ra} (`Rd` atual nunca é lido); só a palavra ALTA (elemento `3`, bits[127:96]) de
    /// cada operando participa da fórmula (`ARM DDI 0487`), e só a palavra alta de {@link #rd} é
    /// escrita — as 3 palavras baixas são zeradas. {@link #ra} vem do MESMO campo de 5 bits
    /// (`bits[14:10]`) que {@link Sha3FourRegister#ra}, MESMO layout de encoding (`op0=0b010`,
    /// `bit15=0`).
    record Sm3FourRegister(
            /// Registrador `V` de destino.
            int rd,
            /// Primeiro operando fonte.
            int rn,
            /// Segundo operando fonte.
            int rm,
            /// Terceiro operando fonte (`bits[14:10]`).
            int ra) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SM3_FOUR_REGISTER; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSm3FourRegister(core, this);
        }
    }

    /// "Cryptographic three-register SM3, imm2" (`FEAT_SM3`, ARMv8.2-A, B19.10) — `SM3TT1A`/
    /// `SM3TT1B`/`SM3TT2A`/`SM3TT2B`. {@link #op} vem de bits[13:12] do encoding real (seleciona a
    /// variante); {@link #imm2} vem de bits[11:10] (seleciona QUAL das 4 palavras de {@link #rm}
    /// entra na fórmula) — os dois campos são DIFERENTES, ver a Armadilha 3 da task (nomes fáceis
    /// de trocar). `Rd` é lido E escrito (estado corrente); `Rn`/`Rm` nunca modificados.
    record Sm3ThreeRegisterImm2(
            /// Variante a executar (`TT1A`/`TT1B`/`TT2A`/`TT2B`).
            Ir64CryptoSm3TtOp op,
            /// Registrador `V` de destino (lido E escrito).
            int rd,
            /// Segundo operando (fonte, nunca modificado).
            int rn,
            /// Terceiro operando (fonte, nunca modificado — bloco de mensagem `W`).
            int rm,
            /// Seleciona qual das 4 palavras de {@link #rm} entra na fórmula (`0`-`3`).
            int imm2) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SM3_THREE_REGISTER_IMM2; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSm3ThreeRegisterImm2(core, this);
        }
    }

    /// `SM4E` (`FEAT_SM4`, ARMv8.2-A, B19.10) — rodada de cifra SM4 (GB/T 32907-2016), forma de 2
    /// registradores: {@link #rd} é o estado ATUAL do bloco cifrado (lido E escrito, mesmo padrão
    /// destrutivo de `AESE`/`AESD`, ver {@link Aes}), {@link #rn} carrega as 4 subchaves de
    /// rodada (`rk[i..i+3]`) desta chamada — processa **4 rodadas** de uma vez.
    record Sm4Encrypt(
            /// Registrador `V` de destino (e primeiro operando — estado atual do bloco).
            int rd,
            /// Registrador `V` fonte — as 4 subchaves de rodada.
            int rn) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SM4_ENCRYPT; }
        @Override public boolean execute(Aarch64Core core) { return Ir64CryptoExecutor.executeSm4Encrypt(core, this); }
    }

    /// `SM4EKEY` (`FEAT_SM4`, ARMv8.2-A, B19.10) — expansão de chave SM4, forma de 3 registradores:
    /// função PURA de {@link #rn} (estado atual da chave, `K[i..i+3]`) e {@link #rm} (as 4
    /// constantes de rodada `CK[i..i+3]`) — ao contrário de {@link Sm4Encrypt}, `Rd` NUNCA é
    /// lido. Processa **4 rodadas** de expansão de uma vez.
    record Sm4KeyUpdate(
            /// Registrador `V` de destino.
            int rd,
            /// Primeiro operando fonte — estado atual da chave.
            int rn,
            /// Segundo operando fonte — as 4 constantes de rodada `CK`.
            int rm) implements CryptoOp64 {
        @Override public int kind() { return Kind.CRYPTO_SM4_KEY_UPDATE; }
        @Override public boolean execute(Aarch64Core core) {
            return Ir64CryptoExecutor.executeSm4KeyUpdate(core, this);
        }
    }
}
