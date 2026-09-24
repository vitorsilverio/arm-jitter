package dev.vitorsilverio.armjitter.memory.tcm;

/// Decodificação do campo `Size` (bits `[6:2]` do `ATCM`/`BTCM Region Register`) — Tabela 4-43/4-44
/// da Cortex-R5 TRM (ARM DDI 0460D), lida via `curl`+`pdftotext` do PDF real nesta sessão (não
/// parafraseada; a página `developer.arm.com/documentation/ddi0460/d` é renderizada por
/// JavaScript e não pôde ser lida por fetch simples, mesma limitação já registrada no Javadoc de
/// `Pmsav7MpuCoprocessor` para `SCTLR.BR` — o PDF veio de um mirror do Wayback Machine).
///
/// **Correção sobre o rascunho da spec desta task**: o campo `Size` tem 5 bits (`[6:2]`), não 4
/// (`[5:2]` como a spec original supunha) — bit `[1]` é `SBZ` (reservado), separado do campo
/// `Size`. A tabela de valores em si (progressão dobrando a partir de `0b00011`=4KB) estava
/// correta.
public final class TcmSizeField {
    private static final int SHIFT = 2;
    private static final int MASK = 0b1_1111;

    /// Menor código válido (`0b00011` = 4KB); `0b00000` significa "sem TCM" (tamanho zero,
    /// {@link TcmRegion#contains} nunca casa) e `0b00001`/`0b00010` são reservados — tratados como
    /// "sem TCM" também (G8 aplicado a campo de registrador, mesma lição do `RSIZE<rsize_min` da
    /// `PmsaAddressSpace`/B20.3: um código fora da tabela real não pode silenciosamente virar um
    /// tamanho arbitrário).
    private static final int MIN_VALID_CODE = 0b00011;
    /// Maior código válido (`0b01110` = 8MB); códigos acima são reservados.
    private static final int MAX_VALID_CODE = 0b01110;
    /// `sizeBytes = 4KB << (code - MIN_VALID_CODE)` para `code` no intervalo válido — confirmado
    /// célula a célula contra a Tabela 4-43/4-44 real (4KB, 8KB, 16KB, 32KB, 64KB, 128KB, 256KB,
    /// 512KB, 1MB, 2MB, 4MB, 8MB).
    private static final int MIN_SIZE_BYTES = 4 * 1024;

    private TcmSizeField() {
    }

    /// Extrai o campo `Size` cru (bits `[6:2]`) do valor de 32 bits do registrador de região.
    public static int extractRawCode(int regionRegisterValue) {
        return (regionRegisterValue >>> SHIFT) & MASK;
    }

    /// Decodifica o campo `Size` cru para bytes. Devolve `0` para `0b00000` ("sem TCM") e para
    /// qualquer código reservado fora de `[MIN_VALID_CODE, MAX_VALID_CODE]` — mesmo tratamento que
    /// "TCM desabilitada" ({@link TcmRegion#contains} nunca casa com tamanho `0`).
    public static int decodeBytes(int rawCode) {
        if (rawCode < MIN_VALID_CODE || rawCode > MAX_VALID_CODE) {
            return 0;
        }
        return MIN_SIZE_BYTES << (rawCode - MIN_VALID_CODE);
    }

    /// Codifica um tamanho em bytes (potência de dois entre 4KB e 8MB) de volta para o código
    /// bruto de 5 bits — usado por {@link Cp15TcmCoprocessor} para reconstruir o valor de LEITURA
    /// do campo `Size`, que é **fixo pela configuração de hardware do hospedeiro** (o TRM real diz
    /// explicitamente "On writes this field is ignored" — o software nunca escolhe o tamanho, só a
    /// base e o `Enable`; ver Javadoc de {@link TcmRegion}).
    ///
    /// @throws IllegalArgumentException se `sizeBytes` não for exatamente um dos 13 tamanhos reais
    ///                                    da tabela (`0`, ou uma potência de dois entre 4KB e 8MB)
    public static int encodeRawCode(int sizeBytes) {
        if (sizeBytes == 0) {
            return 0;
        }
        for (int code = MIN_VALID_CODE; code <= MAX_VALID_CODE; code++) {
            if (decodeBytes(code) == sizeBytes) {
                return code;
            }
        }
        throw new IllegalArgumentException(
                "sizeBytes deve ser 0 ou uma das 13 potências de dois da Tabela 4-43/4-44 (4KB..8MB): " + sizeBytes);
    }
}
