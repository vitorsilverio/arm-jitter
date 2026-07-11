package dev.vitorsilverio.armjitter.memory;

import java.util.Objects;

/// `AddressSpace` de dispatch O(1) por tabela de páginas, alternativa às cadeias de if/switch
/// por região que os hospedeiros (gbaemu/ndsemu) usam hoje.
///
/// Duas tabelas indexadas por `address >>> pageShift`: uma de páginas de RAM (acesso direto,
/// sem chamada virtual) e uma de handlers (MMIO/regiões com efeito colateral, delegação
/// normal). Endereços sem nenhuma das duas caem no handler de barramento aberto informado no
/// construtor — os quirks de open-bus (replay do último valor buscado, etc.) são do
/// hospedeiro, este utilitário só encaminha.
///
/// ### Espelhamento (mirrors)
/// Regiões espelhadas (ex.: EWRAM do GBA repetida a cada 256KB em `0x02000000-0x02FFFFFF`) usam
/// {@link #mapMirror}, que aponta várias janelas de páginas para os MESMOS arrays — uma escrita
/// por qualquer janela é visível em todas.
/// ```java
/// PagedAddressSpace bus = new PagedAddressSpace(18, openBus); // páginas de 256KB
/// bus.mapRam(0x0200_0000, ewram);              // janela primária
/// bus.mapMirror(0x0300_0000, 0x0200_0000, ewram.length); // uma janela espelhada
/// ```
///
/// ### Invalidação SMC
/// {@link dev.vitorsilverio.armjitter.jit.JitRuntime} continua envolvido por fora via
/// {@link InvalidationAwareAddressSpace} (G3) para quem já usa esse caminho. Mas escritas
/// diretas no array de uma `ramPage` não passam pelo `write8/16/32` do delegado externo — elas
/// SÃO o delegado. Por isso `PagedAddressSpace` aceita um {@link WriteListener} próprio e um
/// bit "página contém código JIT" por página (mesma ideia do índice página->blocos do
/// `BlockCache`): só as páginas marcadas pagam a chamada de notificação; escritas em páginas de
/// dados puros (a esmagadora maioria) não pagam nem essa chamada.
public final class PagedAddressSpace implements AddressSpace {
    /// Recebe o endereço de cada escrita numa página marcada com {@link #setContainsCode}.
    @FunctionalInterface
    public interface WriteListener {
        /// Notifica uma escrita numa página que contém código JIT compilado.
        void onWrite(int address);
    }

    private final int pageShift;
    private final int pageSize;
    private final int pageMask;
    private final AddressSpace openBus;
    private final WriteListener writeListener;

    private final byte[][] ramPages;
    private final AddressSpace[] handlerPages;
    private final int[] waitstates;
    private final boolean[] containsCode;
    private boolean anyWaitstates;

    /// Cria uma tabela de páginas sem rastreio de código automodificável.
    ///
    /// @param pageShift bits de deslocamento da página (ex.: 12 = páginas de 4KB, 14 = 16KB);
    ///                   quanto menor, mais fina a granularidade e maior a tabela de referências
    ///                   (`1 &lt;&lt; (32 - pageShift)` entradas por tabela)
    /// @param openBusHandler barramento usado para qualquer endereço sem página de RAM nem
    ///                        handler mapeados
    public PagedAddressSpace(int pageShift, AddressSpace openBusHandler) {
        this(pageShift, openBusHandler, null);
    }

    /// Cria uma tabela de páginas com rastreio opcional de código automodificável.
    ///
    /// @param pageShift bits de deslocamento da página
    /// @param openBusHandler barramento usado para qualquer endereço sem página de RAM nem
    ///                        handler mapeados
    /// @param writeListener chamado após escritas em páginas marcadas por
    ///                       {@link #setContainsCode}, ou `null` para nunca notificar
    public PagedAddressSpace(int pageShift, AddressSpace openBusHandler, WriteListener writeListener) {
        if (pageShift < 1 || pageShift > 30) {
            throw new IllegalArgumentException("pageShift deve estar entre 1 e 30: " + pageShift);
        }
        long pageCount = 1L << (32 - pageShift);
        if (pageCount > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException(
                    "pageShift muito pequeno para uma tabela alocável: " + pageShift);
        }
        this.pageShift = pageShift;
        this.pageSize = 1 << pageShift;
        this.pageMask = pageSize - 1;
        this.openBus = Objects.requireNonNull(openBusHandler, "openBusHandler");
        this.writeListener = writeListener;

        int count = (int) pageCount;
        this.ramPages = new byte[count][];
        this.handlerPages = new AddressSpace[count];
        this.waitstates = new int[count];
        this.containsCode = new boolean[count];
    }

    /// Tamanho de página em bytes (`1 &lt;&lt; pageShift`).
    public int pageSize() {
        return pageSize;
    }

    // ── montagem ─────────────────────────────────────────────────────────────────

    /// Mapeia uma região de RAM a partir de `base`, fatiando `backing` em páginas próprias
    /// (cópia — os arrays internos passam a ser a fonte da verdade; `backing` não fica
    /// aliasado). `base` e `backing.length` devem ser múltiplos de {@link #pageSize()}.
    public void mapRam(int base, byte[] backing) {
        Objects.requireNonNull(backing, "backing");
        requireAligned(base, "base");
        if (backing.length % pageSize != 0) {
            throw new IllegalArgumentException(
                    "backing.length (" + backing.length + ") deve ser múltiplo de pageSize (" + pageSize + ")");
        }
        int firstPage = pageIndex(base);
        int pages = backing.length / pageSize;
        for (int i = 0; i < pages; i++) {
            byte[] page = new byte[pageSize];
            System.arraycopy(backing, i * pageSize, page, 0, pageSize);
            ramPages[firstPage + i] = page;
            handlerPages[firstPage + i] = null;
        }
    }

    /// Mapeia um handler (MMIO ou qualquer `AddressSpace` com efeito colateral) para as
    /// páginas cobertas por `[base, base+size)`. `base` e `size` devem ser múltiplos de
    /// {@link #pageSize()}.
    public void mapHandler(int base, int size, AddressSpace handler) {
        Objects.requireNonNull(handler, "handler");
        requireAligned(base, "base");
        requireSizeAligned(size, "size");
        int firstPage = pageIndex(base);
        int pages = size / pageSize;
        for (int i = 0; i < pages; i++) {
            handlerPages[firstPage + i] = handler;
            ramPages[firstPage + i] = null;
        }
    }

    /// Espelha `[sourceBase, sourceBase+size)`, já mapeada, em `mirrorBase`: as páginas da
    /// janela nova apontam para os MESMOS arrays/handlers da janela de origem (mesma
    /// referência — não é cópia), reproduzindo o espelhamento de hardware do GBA/NDS.
    public void mapMirror(int mirrorBase, int sourceBase, int size) {
        requireAligned(mirrorBase, "mirrorBase");
        requireAligned(sourceBase, "sourceBase");
        requireSizeAligned(size, "size");
        int srcFirst = pageIndex(sourceBase);
        int dstFirst = pageIndex(mirrorBase);
        int pages = size / pageSize;
        for (int i = 0; i < pages; i++) {
            ramPages[dstFirst + i] = ramPages[srcFirst + i];
            handlerPages[dstFirst + i] = handlerPages[srcFirst + i];
            waitstates[dstFirst + i] = waitstates[srcFirst + i];
            containsCode[dstFirst + i] = containsCode[srcFirst + i];
        }
    }

    /// Desmapeia `[base, base+size)`: volta a cair no handler de barramento aberto.
    public void unmap(int base, int size) {
        requireAligned(base, "base");
        requireSizeAligned(size, "size");
        int firstPage = pageIndex(base);
        int pages = size / pageSize;
        for (int i = 0; i < pages; i++) {
            ramPages[firstPage + i] = null;
            handlerPages[firstPage + i] = null;
            waitstates[firstPage + i] = 0;
            containsCode[firstPage + i] = false;
        }
    }

    /// Define os ciclos extras (waitstate) cobrados por acesso em `[base, base+size)`.
    public void setWaitstates(int base, int size, int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles deve ser >= 0: " + cycles);
        }
        requireAligned(base, "base");
        requireSizeAligned(size, "size");
        int firstPage = pageIndex(base);
        int pages = size / pageSize;
        for (int i = 0; i < pages; i++) {
            waitstates[firstPage + i] = cycles;
        }
        anyWaitstates |= cycles != 0;
    }

    /// Marca (ou desmarca) a página que contém `address` como portadora de código JIT
    /// compilado — só páginas marcadas disparam {@link WriteListener#onWrite}. Espelha a
    /// ideia do índice página-&gt;blocos do `BlockCache`: o hospedeiro chama isto quando um
    /// bloco é compilado/invalidado naquela página, do mesmo jeito que já mantém esse bit lá.
    public void setContainsCode(int address, boolean containsCodeFlag) {
        containsCode[pageIndex(address)] = containsCodeFlag;
    }

    // ── AddressSpace ─────────────────────────────────────────────────────────────

    @Override
    public int read8(int address) {
        int page = pageIndex(address);
        byte[] ram = ramPages[page];
        if (ram != null) {
            return ram[address & pageMask] & 0xFF;
        }
        return handlerOrOpenBus(page).read8(address);
    }

    @Override
    public int read16(int address) {
        int page = pageIndex(address);
        int offset = address & pageMask;
        byte[] ram = ramPages[page];
        if (ram != null) {
            if (offset + 1 < pageSize) {
                return (ram[offset] & 0xFF) | ((ram[offset + 1] & 0xFF) << 8);
            }
            // Cruza fronteira de página: nunca acontece se o hospedeiro só mapeia regiões
            // alinhadas, mas não assumimos isso silenciosamente (ver Armadilhas da task C3).
            return (read8(address) & 0xFF) | ((read8(address + 1) & 0xFF) << 8);
        }
        return handlerOrOpenBus(page).read16(address);
    }

    @Override
    public int read32(int address) {
        int page = pageIndex(address);
        int offset = address & pageMask;
        byte[] ram = ramPages[page];
        if (ram != null) {
            if (offset + 3 < pageSize) {
                return (ram[offset] & 0xFF)
                        | ((ram[offset + 1] & 0xFF) << 8)
                        | ((ram[offset + 2] & 0xFF) << 16)
                        | ((ram[offset + 3] & 0xFF) << 24);
            }
            return (read8(address) & 0xFF)
                    | ((read8(address + 1) & 0xFF) << 8)
                    | ((read8(address + 2) & 0xFF) << 16)
                    | ((read8(address + 3) & 0xFF) << 24);
        }
        return handlerOrOpenBus(page).read32(address);
    }

    @Override
    public void write8(int address, int value) {
        int page = pageIndex(address);
        byte[] ram = ramPages[page];
        if (ram != null) {
            ram[address & pageMask] = (byte) value;
        } else {
            handlerOrOpenBus(page).write8(address, value);
        }
        notifyIfCode(page, address);
    }

    @Override
    public void write16(int address, int value) {
        int page = pageIndex(address);
        int offset = address & pageMask;
        byte[] ram = ramPages[page];
        if (ram != null) {
            if (offset + 1 < pageSize) {
                ram[offset] = (byte) value;
                ram[offset + 1] = (byte) (value >>> 8);
            } else {
                write8(address, value);
                write8(address + 1, value >>> 8);
                return;
            }
        } else {
            handlerOrOpenBus(page).write16(address, value);
        }
        notifyIfCode(page, address);
    }

    @Override
    public void write32(int address, int value) {
        int page = pageIndex(address);
        int offset = address & pageMask;
        byte[] ram = ramPages[page];
        if (ram != null) {
            if (offset + 3 < pageSize) {
                ram[offset] = (byte) value;
                ram[offset + 1] = (byte) (value >>> 8);
                ram[offset + 2] = (byte) (value >>> 16);
                ram[offset + 3] = (byte) (value >>> 24);
            } else {
                write16(address, value);
                write16(address + 2, value >>> 16);
                return;
            }
        } else {
            handlerOrOpenBus(page).write32(address, value);
        }
        notifyIfCode(page, address);
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return waitstates[pageIndex(address)];
    }

    @Override
    public boolean providesAccessCycles() {
        return anyWaitstates;
    }

    @Override
    public void notifyWrite(int address) {
        notifyIfCode(pageIndex(address), address);
    }

    // ── internos ─────────────────────────────────────────────────────────────────

    private AddressSpace handlerOrOpenBus(int page) {
        AddressSpace handler = handlerPages[page];
        return handler != null ? handler : openBus;
    }

    private void notifyIfCode(int page, int address) {
        if (writeListener != null && containsCode[page]) {
            writeListener.onWrite(address);
        }
    }

    private int pageIndex(int address) {
        return address >>> pageShift;
    }

    private void requireAligned(int address, String name) {
        if ((address & pageMask) != 0) {
            throw new IllegalArgumentException(
                    name + " (0x" + Integer.toHexString(address) + ") deve ser alinhado a pageSize (" + pageSize + ")");
        }
    }

    private void requireSizeAligned(int size, String name) {
        if (size <= 0 || size % pageSize != 0) {
            throw new IllegalArgumentException(
                    name + " (" + size + ") deve ser um múltiplo positivo de pageSize (" + pageSize + ")");
        }
    }
}
